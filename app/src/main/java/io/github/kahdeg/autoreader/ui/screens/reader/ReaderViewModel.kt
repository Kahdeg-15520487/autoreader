package io.github.kahdeg.autoreader.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kahdeg.autoreader.data.db.dao.BookDao
import io.github.kahdeg.autoreader.data.db.dao.ChapterDao
import io.github.kahdeg.autoreader.data.db.entity.Book
import io.github.kahdeg.autoreader.data.db.entity.Chapter
import io.github.kahdeg.autoreader.data.db.entity.ChapterStatus
import io.github.kahdeg.autoreader.queue.PrefetchManager
import io.github.kahdeg.autoreader.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val bookTitle: String = "",
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val isLoading: Boolean = true,
    val isPrefetching: Boolean = false
) {
    val currentChapter: Chapter? 
        get() = chapters.getOrNull(currentChapterIndex)
    
    val hasPreviousChapter: Boolean 
        get() = currentChapterIndex > 0
    
    val hasNextChapter: Boolean 
        get() = currentChapterIndex < chapters.size - 1
    
    val totalChapters: Int 
        get() = chapters.size
    
    /**
     * Get the content to display - prioritize processed, then raw
     */
    val displayContent: String?
        get() = when {
            currentChapter?.processedText?.isNotBlank() == true -> currentChapter?.processedText
            currentChapter?.rawText?.isNotBlank() == true -> currentChapter?.rawText
            else -> null
        }
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val prefetchManager: PrefetchManager
) : ViewModel() {
    
    private val route = savedStateHandle.toRoute<Route.Reader>()
    private val bookUrl = route.bookUrl
    
    private val _uiState = MutableStateFlow(ReaderUiState(currentChapterIndex = route.chapterIndex))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    
    init {
        loadBook()
        observeChapters()
        observePrefetchStatus()
        startPrefetching()
    }
    
    private fun loadBook() {
        viewModelScope.launch {
            android.util.Log.d("ReaderVM", "Loading book: $bookUrl")
            val book = bookDao.getByUrl(bookUrl)
            android.util.Log.d("ReaderVM", "Book loaded: ${book?.title}")
            _uiState.update { 
                it.copy(
                    book = book,
                    bookTitle = book?.title ?: "Reading",
                    isLoading = false
                )
            }
        }
    }
    
    private fun observeChapters() {
        viewModelScope.launch {
            chapterDao.getChaptersForBook(bookUrl).collect { chapters ->
                android.util.Log.d("ReaderVM", "Chapters updated: ${chapters.size} chapters")
                _uiState.update { it.copy(chapters = chapters) }
                
                // Check if we should start streaming for current chapter
                checkAndStartTranslation()
            }
        }
    }
    
    private fun observePrefetchStatus() {
        viewModelScope.launch {
            prefetchManager.status.collect { status ->
                _uiState.update { it.copy(isPrefetching = status.isRunning) }
            }
        }
    }
    
    private fun startPrefetching() {
        prefetchManager.startPrefetch(bookUrl)
    }
    
    private fun checkAndStartTranslation() {
        val state = _uiState.value
        val chapter = state.currentChapter ?: return
        
        // Only trigger translation if:
        // 1. Chapter has raw text but no processed text
        // 2. Chapter is not already being processed
        val needsTranslation = !chapter.rawText.isNullOrBlank() && 
            chapter.processedText.isNullOrBlank() &&
            chapter.status != ChapterStatus.TRANSLATING &&
            chapter.status != ChapterStatus.FETCH_FAILED
            
        if (needsTranslation) {
            android.util.Log.d("ReaderVM", "Triggering translation for chapter ${chapter.index}")
            // Delegate to PrefetchManager which runs in independent scope
            viewModelScope.launch {
                prefetchManager.processChapterNow(chapter.id)
            }
        }
    }
    
    /**
     * Manually trigger translation for current chapter (even if already has content)
     */
    fun retranslate() {
        val chapter = _uiState.value.currentChapter ?: return
        
        if (chapter.rawText.isNullOrBlank()) {
            android.util.Log.e("ReaderVM", "No raw text to translate")
            return
        }
        
        android.util.Log.d("ReaderVM", "Retranslating chapter ${chapter.index}")
        viewModelScope.launch {
            // Clear processed text and reset status to trigger reprocessing
            chapterDao.updateProcessed(chapter.id, "", "")
            chapterDao.updateStatus(chapter.id, ChapterStatus.PENDING)
            prefetchManager.processChapterNow(chapter.id)
        }
    }
    
    fun onFlagCurrentChapter() {
        viewModelScope.launch {
            val currentIndex = _uiState.value.currentChapterIndex
            val chapter = _uiState.value.chapters.getOrNull(currentIndex) ?: return@launch
            
            chapterDao.updateStatus(chapter.id, ChapterStatus.USER_FLAGGED)
            chapterDao.updateError(chapter.id, "Flagged by user for review")
        }
    }
    
    fun updateCurrentChapter(index: Int) {
        _uiState.update { 
            it.copy(currentChapterIndex = index) 
        }
        viewModelScope.launch {
            bookDao.updateReadingProgress(bookUrl, index)
            checkAndStartTranslation()
        }
    }
    
    fun retryChapter(chapterId: Long) {
        viewModelScope.launch {
            prefetchManager.processChapterNow(chapterId)
        }
    }
    
    fun goToNextChapter() {
        val state = _uiState.value
        if (state.hasNextChapter) {
            updateCurrentChapter(state.currentChapterIndex + 1)
        }
    }
    
    fun goToPreviousChapter() {
        val state = _uiState.value
        if (state.hasPreviousChapter) {
            updateCurrentChapter(state.currentChapterIndex - 1)
        }
    }
    
    fun refreshCurrentChapter() {
        val chapter = _uiState.value.currentChapter ?: return
        viewModelScope.launch {
            chapterDao.updateStatus(chapter.id, ChapterStatus.PENDING)
            chapterDao.updateError(chapter.id, "")
            prefetchManager.processChapterNow(chapter.id)
        }
    }
    
}
