package io.github.kahdeg.autoreader.ui.screens.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kahdeg.autoreader.browser.GhostBrowser
import io.github.kahdeg.autoreader.browser.ScraperService
import io.github.kahdeg.autoreader.data.db.dao.BookDao
import io.github.kahdeg.autoreader.data.db.dao.ChapterDao
import io.github.kahdeg.autoreader.data.db.dao.SiteBlueprintDao
import io.github.kahdeg.autoreader.data.db.entity.Book
import io.github.kahdeg.autoreader.data.db.entity.Chapter
import io.github.kahdeg.autoreader.llm.LlmProvider
import io.github.kahdeg.autoreader.llm.ScoutAgent
import io.github.kahdeg.autoreader.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookDetailUiState(
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val hasBlueprint: Boolean = false,
    val blueprintInfo: String? = null
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val siteBlueprintDao: SiteBlueprintDao,
    private val ghostBrowser: GhostBrowser,
    private val scraperService: ScraperService,
    private val scoutAgent: ScoutAgent,
    private val llmProvider: LlmProvider
) : ViewModel() {
    
    private val bookUrl: String = savedStateHandle.toRoute<Route.BookDetail>().bookUrl
    
    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()
    
    init {
        loadBook()
        observeChapters()
    }
    
    private fun loadBook() {
        viewModelScope.launch {
            val book = bookDao.getByUrl(bookUrl)
            val blueprint = book?.domain?.let { siteBlueprintDao.getByDomain(it) }
            val blueprintInfo = blueprint?.let {
                """
                |Chapter selector: ${it.chapterCssSelector}
                |Content selector: ${it.contentSelector}
                |List type: ${it.listType}
                |Next button: ${it.nextButtonSelector ?: ""}
                """.trimMargin()
            }
            _uiState.update { 
                it.copy(
                    book = book, 
                    isLoading = false,
                    hasBlueprint = blueprint != null,
                    blueprintInfo = blueprintInfo
                ) 
            }
        }
    }
    
    private fun observeChapters() {
        viewModelScope.launch {
            chapterDao.getChaptersForBook(bookUrl).collect { chapters ->
                _uiState.update { it.copy(chapters = chapters) }
            }
        }
    }
    
    /**
     * Refresh chapters - re-analyze the page and fetch chapter list
     */
    fun refreshChapters() {
        val book = _uiState.value.book ?: return
        
        if (!llmProvider.isConfigured()) {
            _uiState.update { it.copy(error = "LLM not configured. Go to Settings to add API key.") }
            return
        }
        
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        
        viewModelScope.launch {
            try {
                // Load the page
                val loadResult = ghostBrowser.loadUrl(book.bookUrl)
                if (loadResult.isFailure) {
                    _uiState.update { 
                        it.copy(
                            isRefreshing = false, 
                            error = "Failed to load page: ${loadResult.exceptionOrNull()?.message}"
                        ) 
                    }
                    return@launch
                }
                
                // Check for existing blueprint or create new one
                var blueprint = siteBlueprintDao.getByDomain(book.domain)
                
                if (blueprint == null) {
                    // Use Scout Agent to analyze
                    val html = ghostBrowser.extractHtml()
                    val blueprintResult = scoutAgent.analyzeHtml(html, book.domain)
                    
                    if (blueprintResult.isSuccess) {
                        blueprint = blueprintResult.getOrThrow()
                        siteBlueprintDao.upsert(blueprint)
                        _uiState.update { it.copy(hasBlueprint = true) }
                    } else {
                        _uiState.update { 
                            it.copy(
                                isRefreshing = false, 
                                error = "Scout Agent failed: ${blueprintResult.exceptionOrNull()?.message}"
                            ) 
                        }
                        return@launch
                    }
                }
                
                // Generate content selector if missing (whether blueprint is new or existing)
                if (blueprint != null && blueprint.contentSelector.isBlank()) {
                    val chapterLinks = ghostBrowser.extractLinks(blueprint.chapterCssSelector)
                    if (chapterLinks.isNotEmpty()) {
                        val firstChapterUrl = try {
                            java.net.URI(book.bookUrl).resolve(chapterLinks.first().second).toString()
                        } catch (e: Exception) {
                            chapterLinks.first().second
                        }
                        
                        android.util.Log.d("BookDetailVM", "Visiting chapter to get content selector: $firstChapterUrl")
                        ghostBrowser.loadUrl(firstChapterUrl).getOrThrow()
                        val chapterHtml = ghostBrowser.extractHtml()
                        
                        val contentSelectorResult = scoutAgent.analyzeChapterHtml(chapterHtml)
                        if (contentSelectorResult.isSuccess) {
                            val newSelector = contentSelectorResult.getOrThrow()
                            android.util.Log.d("BookDetailVM", "Got content selector: $newSelector")
                            blueprint = blueprint.copy(contentSelector = newSelector)
                            siteBlueprintDao.upsert(blueprint)
                            loadBook() // Refresh UI to show new selector
                        }
                        
                        // Navigate back to book page
                        ghostBrowser.loadUrl(book.bookUrl)
                    }
                }
                
                // Fetch chapters
                val bookInfo = scraperService.fetchBookInfo(book.bookUrl)
                
                if (bookInfo.isSuccess) {
                    val info = bookInfo.getOrThrow()
                    
                    // Update book title/author if we got better data
                    if (info.title != "Unknown Title" || info.author != "Unknown Author") {
                        bookDao.upsert(book.copy(
                            title = info.title,
                            author = info.author
                        ))
                    }
                    
                    // Save chapters
                    if (info.chapters.isNotEmpty()) {
                        scraperService.saveChapters(book.bookUrl, info.chapters)
                    }
                    
                    _uiState.update { 
                        it.copy(
                            isRefreshing = false, 
                            error = if (info.chapters.isEmpty()) "No chapters found. The selectors may need adjustment." else null
                        ) 
                    }
                } else {
                    _uiState.update { 
                        it.copy(
                            isRefreshing = false, 
                            error = "Failed to fetch chapters: ${bookInfo.exceptionOrNull()?.message}"
                        ) 
                    }
                }
                
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(isRefreshing = false, error = "Error: ${e.message}") 
                }
            }
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    /**
     * Delete the current book and all its chapters
     */
    fun deleteBook(onDeleted: () -> Unit) {
        val book = _uiState.value.book ?: return
        
        viewModelScope.launch {
            try {
                // Delete all chapters for this book
                chapterDao.deleteAllForBook(book.bookUrl)
                // Delete the book
                bookDao.delete(book)
                onDeleted()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete: ${e.message}") }
            }
        }
    }
    
    /**
     * Reset the blueprint and re-analyze the site
     */
    fun resetBlueprint() {
        val book = _uiState.value.book ?: return
        
        viewModelScope.launch {
            try {
                // Delete existing blueprint
                siteBlueprintDao.deleteByDomain(book.domain)
                _uiState.update { 
                    it.copy(
                        hasBlueprint = false, 
                        blueprintInfo = null,
                        error = "Blueprint cleared. Press Refresh to re-analyze."
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to reset: ${e.message}") }
            }
        }
    }
    
    /**
     * Manually update blueprint selectors
     */
    fun updateBlueprint(
        listType: String,
        chapterSelector: String,
        contentSelector: String,
        nextButtonSelector: String?
    ) {
        val book = _uiState.value.book ?: return
        
        viewModelScope.launch {
            try {
                val blueprint = io.github.kahdeg.autoreader.data.db.entity.SiteBlueprint(
                    domain = book.domain,
                    listType = when (listType.uppercase()) {
                        "PAGINATED" -> io.github.kahdeg.autoreader.data.db.entity.ListType.PAGINATED
                        "LOAD_MORE" -> io.github.kahdeg.autoreader.data.db.entity.ListType.LOAD_MORE
                        "EXPANDABLE_TOGGLE" -> io.github.kahdeg.autoreader.data.db.entity.ListType.EXPANDABLE_TOGGLE
                        else -> io.github.kahdeg.autoreader.data.db.entity.ListType.SIMPLE
                    },
                    chapterCssSelector = chapterSelector,
                    contentSelector = contentSelector,
                    nextButtonSelector = nextButtonSelector?.takeIf { it.isNotBlank() },
                    triggerSelector = null,
                    lastValidated = System.currentTimeMillis(),
                    version = 1
                )
                
                siteBlueprintDao.upsert(blueprint)
                
                // Reload blueprint info
                loadBook()
                
                _uiState.update { 
                    it.copy(error = "Blueprint saved! Press Fetch Chapters to test.")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to save: ${e.message}") }
            }
        }
    }
}
