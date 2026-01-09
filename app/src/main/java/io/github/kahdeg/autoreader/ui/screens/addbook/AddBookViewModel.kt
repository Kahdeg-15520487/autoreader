package io.github.kahdeg.autoreader.ui.screens.addbook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kahdeg.autoreader.browser.GhostBrowser
import io.github.kahdeg.autoreader.browser.ScraperService
import io.github.kahdeg.autoreader.data.db.dao.BookDao
import io.github.kahdeg.autoreader.data.db.dao.SiteBlueprintDao
import io.github.kahdeg.autoreader.data.db.entity.Book
import io.github.kahdeg.autoreader.llm.LlmProvider
import io.github.kahdeg.autoreader.llm.ScoutAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject

sealed interface AddBookUiState {
    data object Idle : AddBookUiState
    data object Loading : AddBookUiState
    data class Scouting(val message: String) : AddBookUiState
    data class Success(val bookUrl: String) : AddBookUiState
    data class Error(val message: String) : AddBookUiState
}

@HiltViewModel
class AddBookViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val siteBlueprintDao: SiteBlueprintDao,
    private val ghostBrowser: GhostBrowser,
    private val scraperService: ScraperService,
    private val scoutAgent: ScoutAgent,
    private val llmProvider: LlmProvider
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<AddBookUiState>(AddBookUiState.Idle)
    val uiState: StateFlow<AddBookUiState> = _uiState.asStateFlow()
    
    var urlInput by mutableStateOf("")
        private set
    
    var sourceLanguage by mutableStateOf("zh")
        private set
    
    var targetLanguage by mutableStateOf("en")
        private set
    
    /**
     * Check if LLM is configured
     */
    val isLlmConfigured: Boolean
        get() = llmProvider.isConfigured()
    
    fun onUrlChange(value: String) {
        urlInput = value
        if (_uiState.value is AddBookUiState.Error) {
            _uiState.value = AddBookUiState.Idle
        }
    }
    
    fun onSourceLanguageChange(value: String) {
        sourceLanguage = value.lowercase().take(5)
    }
    
    fun onTargetLanguageChange(value: String) {
        targetLanguage = value.lowercase().take(5)
    }
    
    fun onAddBook() {
        val url = urlInput.trim()
        
        // Validate URL
        if (url.isBlank()) {
            _uiState.value = AddBookUiState.Error("Please enter a URL")
            return
        }
        
        // Check LLM configuration
        if (!llmProvider.isConfigured()) {
            _uiState.value = AddBookUiState.Error("Please configure LLM settings first (Settings → API Key)")
            return
        }
        
        val domain = try {
            URI(url).host ?: throw IllegalArgumentException()
        } catch (e: Exception) {
            _uiState.value = AddBookUiState.Error("Invalid URL format")
            return
        }
        
        _uiState.value = AddBookUiState.Loading
        
        viewModelScope.launch {
            try {
                // Step 1: Load the page
                _uiState.value = AddBookUiState.Scouting("Loading page...")
                ghostBrowser.loadUrl(url).getOrThrow()
                
                // Step 2: Check if we have a blueprint for this domain
                var blueprint = siteBlueprintDao.getByDomain(domain)
                
                if (blueprint == null && llmProvider.isConfigured()) {
                    // Step 3: Use Scout Agent to analyze the page
                    _uiState.value = AddBookUiState.Scouting("Analyzing page structure with AI...")
                    val html = ghostBrowser.extractHtml()
                    val blueprintResult = scoutAgent.analyzeHtml(html, domain)
                    
                    if (blueprintResult.isSuccess) {
                        blueprint = blueprintResult.getOrThrow()
                        
                        // Step 3b: If contentSelector is empty, we need to visit a chapter page
                        if (blueprint.contentSelector.isBlank()) {
                            _uiState.value = AddBookUiState.Scouting("Analyzing chapter content structure...")
                            
                            // Get first chapter link from the page
                            val chapterLinks = ghostBrowser.extractLinks(blueprint.chapterCssSelector)
                            if (chapterLinks.isNotEmpty()) {
                                val firstChapterUrl = try {
                                    val base = URI(url)
                                    base.resolve(chapterLinks.first().second).toString()
                                } catch (e: Exception) {
                                    chapterLinks.first().second
                                }
                                
                                // Load the chapter page
                                ghostBrowser.loadUrl(firstChapterUrl).getOrThrow()
                                val chapterHtml = ghostBrowser.extractHtml()
                                
                                // Analyze the chapter page for content selector
                                val contentSelectorResult = scoutAgent.analyzeChapterHtml(chapterHtml)
                                if (contentSelectorResult.isSuccess) {
                                    blueprint = blueprint.copy(
                                        contentSelector = contentSelectorResult.getOrThrow()
                                    )
                                }
                                
                                // Navigate back to the book page for chapter scraping
                                ghostBrowser.loadUrl(url)
                            }
                        }
                        
                        siteBlueprintDao.upsert(blueprint)
                    }
                }
                
                // Step 4: Fetch book info
                _uiState.value = AddBookUiState.Scouting("Fetching book info...")
                val bookInfo = scraperService.fetchBookInfo(url)
                
                val info = bookInfo.getOrNull()
                
                // Step 5: Create and save book
                val book = Book(
                    bookUrl = url,
                    domain = domain,
                    title = info?.title ?: "New Book",
                    author = info?.author ?: "Unknown",
                    coverUrl = null,
                    synopsis = "",
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    currentChapterIndex = 0,
                    lookAheadLimit = 5
                )
                
                bookDao.upsert(book)
                
                // Step 6: Save chapters if we got them
                if (info != null && info.chapters.isNotEmpty()) {
                    _uiState.value = AddBookUiState.Scouting("Saving ${info.chapters.size} chapters...")
                    scraperService.saveChapters(url, info.chapters)
                }
                
                _uiState.value = AddBookUiState.Success(url)
                
            } catch (e: Exception) {
                _uiState.value = AddBookUiState.Error(e.message ?: "Failed to add book")
            }
        }
    }
}
