package io.github.kahdeg.autoreader.queue

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.kahdeg.autoreader.browser.ScraperService
import io.github.kahdeg.autoreader.data.db.dao.BookDao
import io.github.kahdeg.autoreader.data.db.dao.ChapterDao
import io.github.kahdeg.autoreader.data.db.entity.Book
import io.github.kahdeg.autoreader.data.db.entity.Chapter
import io.github.kahdeg.autoreader.data.db.entity.ChapterStatus
import io.github.kahdeg.autoreader.llm.EditorAgent
import io.github.kahdeg.autoreader.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Status of the prefetch manager
 */
data class PrefetchStatus(
    val isRunning: Boolean = false,
    val currentBook: String? = null,
    val currentChapter: String? = null,
    val pendingCount: Int = 0,
    val lastError: String? = null
)

/**
 * PrefetchManager - Manages the queue of chapters to fetch and process.
 * Ensures chapters are ready ahead of the user's reading position.
 */
@Singleton
class PrefetchManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scraperService: ScraperService,
    private val editorAgent: EditorAgent,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prefetchJob: Job? = null
    
    private val _status = MutableStateFlow(PrefetchStatus())
    val status: StateFlow<PrefetchStatus> = _status.asStateFlow()
    
    private val prefs by lazy { context.getSharedPreferences("autoreader_settings", Context.MODE_PRIVATE) }
    
    private fun getLookAheadLimit(): Int = prefs.getInt("look_ahead_limit", 5)
    
    /**
     * Start prefetching for a specific book.
     */
    fun startPrefetch(bookUrl: String) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            processPrefetchQueue(bookUrl)
        }
    }
    
    /**
     * Stop all prefetching.
     */
    fun stopPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        _status.value = PrefetchStatus(isRunning = false)
    }
    
    /**
     * Process the prefetch queue for a book.
     */
    private suspend fun processPrefetchQueue(bookUrl: String) {
        _status.value = _status.value.copy(isRunning = true, currentBook = bookUrl)
        
        try {
            // Reset any chapters stuck in TRANSLATING (app was terminated mid-translation)
            val resetCount = chapterDao.resetStuckTranslating(bookUrl)
            if (resetCount > 0) {
                AppLog.d("PrefetchManager", "Reset $resetCount stuck TRANSLATING chapters to PENDING")
            }
            
            val book = bookDao.getByUrl(bookUrl) ?: return
            
            while (true) {
                // Get chapters that need processing
                val chaptersToProcess = getChaptersToProcess(book)
                
                if (chaptersToProcess.isEmpty()) {
                    // Nothing to do, wait and check again
                    delay(5000)
                    continue
                }
                
                _status.value = _status.value.copy(pendingCount = chaptersToProcess.size)
                
                for (chapter in chaptersToProcess) {
                    processChapter(chapter, book)
                    delay(1000) // Polite delay between requests
                }
            }
        } catch (e: Exception) {
            _status.value = _status.value.copy(
                isRunning = false,
                lastError = e.message
            )
        }
    }
    
    /**
     * Get chapters that need to be fetched/processed based on look-ahead.
     */
    private suspend fun getChaptersToProcess(book: Book): List<Chapter> {
        val currentIndex = book.currentChapterIndex
        val lookAhead = getLookAheadLimit()  // Use global setting
        
        return chapterDao.getChaptersInRange(
            bookUrl = book.bookUrl,
            startIndex = currentIndex,
            endIndex = currentIndex + lookAhead
        ).filter { 
            it.status == ChapterStatus.PENDING || it.status == ChapterStatus.USER_FLAGGED
        }.take(3) // Process max 3 at a time
    }
    
    /**
     * Process a single chapter: fetch content then translate/cleanup.
     */
    private suspend fun processChapter(chapter: Chapter, book: Book) {
        _status.value = _status.value.copy(currentChapter = chapter.title)
        
        try {
            // Step 1: Fetch raw content if not already fetched
            if (chapter.rawText.isNullOrBlank()) {
                val fetchResult = scraperService.fetchChapterContent(chapter, book)
                if (fetchResult.isFailure) {
                    return // Error already logged by ScraperService
                }
            }
            
            // Refresh chapter data after fetch
            val updatedChapter = chapterDao.getById(chapter.id) ?: return
            
            // Step 2: Process with LLM (translate or cleanup)
            if (updatedChapter.processedText.isNullOrBlank() && !updatedChapter.rawText.isNullOrBlank()) {
                val chapterInfo = "chapter ${chapter.index}: ${chapter.title}"
                AppLog.d("PrefetchManager", "Starting translation for $chapterInfo")
                chapterDao.updateStatus(chapter.id, ChapterStatus.TRANSLATING)
                
                // Use NonCancellable to prevent interruption during translation
                val processResult = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        editorAgent.processContent(
                            rawText = updatedChapter.rawText!!,
                            mode = book.processingMode,
                            sourceLanguage = book.sourceLanguage,
                            targetLanguage = book.targetLanguage
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Log but don't propagate - we want to complete the operation
                        AppLog.w("PrefetchManager", "Translation interrupted for $chapterInfo: ${e.message}")
                        Result.failure<String>(Exception("Translation interrupted"))
                    } catch (e: Exception) {
                        AppLog.e("PrefetchManager", "Translation exception for $chapterInfo: ${e.message}")
                        Result.failure<String>(e)
                    }
                }
                
                AppLog.d("PrefetchManager", "Translation result for $chapterInfo: isSuccess=${processResult.isSuccess}")
                
                if (processResult.isSuccess) {
                    val text = processResult.getOrThrow()
                    AppLog.d("PrefetchManager", "Saving ${text.length} chars for $chapterInfo")
                    chapterDao.updateProcessed(
                        id = chapter.id,
                        processedText = text,
                        processedLanguage = book.targetLanguage
                    )
                    AppLog.d("PrefetchManager", "✓ $chapterInfo READY")
                    chapterDao.updateStatus(chapter.id, ChapterStatus.READY)
                } else {
                    AppLog.e("PrefetchManager", "✗ $chapterInfo FAILED: ${processResult.exceptionOrNull()?.message}")
                    chapterDao.updateStatus(chapter.id, ChapterStatus.FETCH_FAILED)
                    chapterDao.updateError(chapter.id, processResult.exceptionOrNull()?.message ?: "Processing failed")
                }
            }
        } catch (e: Exception) {
            chapterDao.updateStatus(chapter.id, ChapterStatus.FETCH_FAILED)
            chapterDao.updateError(chapter.id, e.message ?: "Unknown error")
        }
    }
    
    /**
     * Manually trigger processing for a specific chapter.
     */
    suspend fun processChapterNow(chapterId: Long) {
        val chapter = chapterDao.getById(chapterId) ?: return
        val book = bookDao.getByUrl(chapter.bookUrl) ?: return
        
        // Reset status if it was failed
        if (chapter.status == ChapterStatus.FETCH_FAILED || chapter.status == ChapterStatus.USER_FLAGGED) {
            chapterDao.updateStatus(chapterId, ChapterStatus.PENDING)
        }
        
        val updatedChapter = chapterDao.getById(chapterId) ?: return
        processChapter(updatedChapter, book)
    }
}
