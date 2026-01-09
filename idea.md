This document consolidates all architectural decisions, strategies, and technical specifications discussed for the **"Ghost Browser" Project**. You can paste this entire markdown directly into Claude Code or any AI coding assistant to provide full context for implementation.

---

# Project Specification: Android "Ghost Browser" & Web-to-Ebook Engine

## 1. Project Overview

**Goal:** Create an Android browser that functions as a "Universal Web-to-Ebook Engine."
**Core Concept:** The user provides a URL for a story. The app uses a background "Ghost Browser" (Headless WebView) to navigate, scrape, and process content using LLMs. The user interacts *only* with a clean, native "Reader Mode" interface (similar to Kindle/Moon+ Reader), while the actual web navigation happens invisibly in the background.

## 2. Architecture: "The Ghost Pipeline"

The system is divided into two distinct layers:

1. **Foreground (UI):** Native Android Views (RecyclerView, TextView). No visible WebViews during reading.
2. **Background (Ghost Browser):** A Service managing hidden WebViews that bypass anti-bot protections by acting as a real user agent.

### The Pipeline Flow

1. **Ingestion:** User inputs URL -> Ghost Browser loads page.
2. **Scouting (Agent 1):** LLM analyzes HTML to generate a `ScrapingBlueprint` (CSS selectors, navigation logic).
3. **Indexing:** App scrapes Chapter List (Title, URL) into local DB.
4. **Pre-Fetching:** Queue Manager identifies the next 3 chapters.
5. **Extraction:** Ghost Browser loads chapter URL -> Extracts raw text.
6. **Translation/Refinement (Agent 2):** LLM translates and fixes grammar.
7. **Storage:** Clean content saved to Room Database.
8. **Reading:** UI loads from Database.

---

## 3. Data Models (Kotlin & JSON)

### A. Site Blueprint Cache (Domain-Level)

Blueprints are cached **per-domain** to avoid redundant LLM calls when adding multiple books from the same site.

```kotlin
enum class ListType { SIMPLE, PAGINATED, LOAD_MORE, EXPANDABLE_TOGGLE }

@Entity(tableName = "site_blueprints")
data class SiteBlueprint(
    @PrimaryKey val domain: String,      // e.g. "royalroad.com"
    val listType: ListType,
    val chapterCssSelector: String,      // e.g. ".chapter-list a"
    val contentSelector: String,         // e.g. ".chapter-content"
    val nextButtonSelector: String?,     // e.g. ".pagination .next"
    val triggerSelector: String?,        // For "Load More" buttons
    val waitStrategy: String = "NETWORK",// NETWORK, MUTATION, or TIMEOUT
    val lastValidated: Long,             // Timestamp for staleness detection
    val version: Int = 1                 // Increment on re-scout
)
```

### B. The Book & Chapter Schema (Room DB)

```kotlin
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val bookUrl: String,
    val domain: String,                  // FK to SiteBlueprint
    val title: String,
    val author: String,
    val coverUrl: String?,               // Just display name, no download
    val synopsis: String,
    val sourceLanguage: String,          // e.g. "zh", "ko", "en"
    val targetLanguage: String,          // e.g. "en", "vi"
    val currentChapterIndex: Int = 0,    // Reading progress
    val lookAheadLimit: Int = 5          // User-configurable pre-fetch limit
) {
    // If same language, just cleanup; otherwise translate
    val processingMode: ProcessingMode 
        get() = if (sourceLanguage == targetLanguage) ProcessingMode.CLEANUP 
                else ProcessingMode.TRANSLATION
}

enum class ProcessingMode { CLEANUP, TRANSLATION }

enum class ChapterStatus { 
    PENDING,       // Not yet fetched
    FETCHING,      // Ghost browser loading
    FETCH_FAILED,  // Network or parse error
    TRANSLATING,   // LLM processing
    READY,         // Fully processed
    USER_FLAGGED   // User reported issue (triggers re-scout)
}

@Entity(tableName = "chapters")
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUrl: String,                 // FK to Book
    val index: Int,
    val title: String,
    val rawUrl: String,
    val status: ChapterStatus,
    val rawText: String?,                // Original extracted text
    val rawLanguage: String?,            // Detected source language (e.g. "zh", "ko")
    val processedText: String?,          // Translated/cleaned text
    val processedLanguage: String?,      // Target language (e.g. "en")
    val errorMessage: String?,           // Populated on FETCH_FAILED
    val expectedMinLength: Int? = null   // For detecting truncated content
)
```

### C. Error Detection Logic

```kotlin
fun Chapter.hasValidContent(): Boolean {
    if (rawText.isNullOrBlank()) return false
    val minLength = expectedMinLength ?: 500 // Chapters usually > 500 chars
    return rawText.length >= minLength
}
```

---

## 4. LLM Provider Interface (Provider-Agnostic)

The system uses an abstraction layer to support multiple LLM backends.

```kotlin
interface LlmProvider {
    val name: String
    suspend fun complete(request: LlmRequest): LlmResponse
    suspend fun isAvailable(): Boolean
}

data class LlmRequest(
    val systemPrompt: String,
    val userMessage: String,
    val maxTokens: Int = 4096,
    val temperature: Float = 0.3f,
    val jsonMode: Boolean = false       // Request structured JSON output
)

data class LlmResponse(
    val content: String,
    val tokensUsed: Int,
    val provider: String,
    val latencyMs: Long
)

// OpenAI-Compatible Provider (works with OpenAI, Ollama, LM Studio, etc.)
class OpenAiCompatibleProvider(
    private val baseUrl: String,         // e.g. "https://api.openai.com/v1" or "http://localhost:11434/v1"
    private val apiKey: String?,         // Optional for local models
    private val modelName: String        // e.g. "gpt-4o-mini", "llama3.2"
) : LlmProvider {
    override val name = "openai-compatible"
    
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val response = httpClient.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = modelName,
                messages = listOf(
                    Message("system", request.systemPrompt),
                    Message("user", request.userMessage)
                ),
                max_tokens = request.maxTokens,
                temperature = request.temperature,
                response_format = if (request.jsonMode) ResponseFormat("json_object") else null
            ))
        }
        // Parse response...
    }
}

// Factory with fallback chain
class LlmProviderManager(
    private val providers: List<LlmProvider>,
    private val preferences: UserPreferences
) {
    suspend fun getProvider(task: LlmTask): LlmProvider {
        // User can assign preferred providers per task type
        val preferred = preferences.getPreferredProvider(task)
        return providers.firstOrNull { it.name == preferred && it.isAvailable() }
            ?: providers.first { it.isAvailable() }
    }
}

enum class LlmTask { SITE_SCOUT, TRANSLATION, LANGUAGE_DETECTION }
```

---

## 5. AI Agent Strategy

We use two specialized LLM prompts to minimize token costs and latency.

### Agent 1: "The Selector Scout" (Site Analysis)

* **Trigger:** When adding a book from a new domain (no cached blueprint).
* **Input:** Raw HTML of the Table of Contents page.
* **Goal:** Identify the navigation pattern and CSS selectors.
* **System Prompt:**
```text
You are a CSS Selector Expert. Analyze the provided HTML.
Your goal is to provide a machine-readable Blueprint to scrape this site.
1. Identify the CSS Selector for chapter links.
2. Identify the CSS Selector for chapter content body.
3. Check for navigation (Next Page, Load More, Show All).
4. Classify the structure: SIMPLE, PAGINATED, LOAD_MORE, or EXPANDABLE_TOGGLE.
Return JSON matching the SiteBlueprint schema.
```

### Agent 2: "The Editor" (Translation & Cleanup)

* **Trigger:** When pre-fetching a chapter (runs on next N chapters based on lookAheadLimit).
* **Input:** Raw text extracted from the chapter body.
* **Mode Selection:** Based on `book.processingMode`:
  - **CLEANUP** (source == target): Grammar fixes only, no translation
  - **TRANSLATION** (source != target): Full translation + cleanup

#### Cleanup Mode System Prompt (Same Language):
```text
You are a professional fiction editor.
1. Fix grammar, spelling, and punctuation errors.
2. Fix gender consistency issues (he/she confusion).
3. Preserve all paragraph breaks. Output plain text only.
4. Do not change the meaning or summarize. Keep all narrative details.
```

#### Translation Mode System Prompt (Different Languages):
```text
You are a professional fiction translator and editor.
1. Translate the text from [Source Language] to [Target Language].
2. Maintain the author's tone and style.
3. Fix gender consistency (he/she errors common in MTL).
4. Preserve all paragraph breaks. Output plain text only.
5. Do not summarize. Keep all narrative details.
```



---

## 6. Technical Implementation Details

### A. The "Ghost Browser" (Headless WebView)

This component executes the navigation logic defined by the Blueprint.

```kotlin
class GhostBrowser(private val context: Context) {
    private val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10)... Chrome/..."
        settings.blockNetworkImage = true // Save bandwidth
    }

    // The "Smart Loop" for handling complex navigation
    suspend fun executeBlueprint(url: String, blueprint: SiteBlueprint): Result<List<ChapterLink>> {
        var currentUrl = url
        var keepScraping = true
        val allChapters = mutableListOf<ChapterLink>()
        
        while (keepScraping) {
            loadUrl(currentUrl)
            
            // 1. Scrape current items
            val html = extractHtml()
            val chapters = parseChapters(html, blueprint.chapterCssSelector)
            allChapters.addAll(chapters)

            // 2. Handle Navigation
            when (blueprint.listType) {
                ListType.SIMPLE -> keepScraping = false
                ListType.PAGINATED -> {
                    val nextLink = findLink(html, blueprint.nextButtonSelector)
                    if (nextLink != null) currentUrl = nextLink else keepScraping = false
                }
                ListType.LOAD_MORE -> {
                    val success = injectClickScript(blueprint.triggerSelector)
                    if (!success) keepScraping = false
                }
                ListType.EXPANDABLE_TOGGLE -> {
                    injectClickScript(blueprint.triggerSelector)
                    keepScraping = false 
                }
            }
        }
        return Result.success(allChapters)
    }
    
    suspend fun extractChapterContent(url: String, contentSelector: String): Result<String> {
        loadUrl(url)
        val html = extractHtml()
        val text = Jsoup.parse(html).select(contentSelector).text()
        
        return if (text.isNotBlank()) Result.success(text)
        else Result.failure(Exception("Content selector returned empty"))
    }
}
```

### B. Pre-Fetch Queue Manager

Manages the look-ahead fetching based on user's current reading position.

```kotlin
class PrefetchManager(
    private val ghostBrowser: GhostBrowser,
    private val llmManager: LlmProviderManager,
    private val chapterDao: ChapterDao,
    private val blueprintDao: SiteBlueprintDao
) {
    suspend fun ensureChaptersReady(book: Book) {
        val currentIndex = book.currentChapterIndex
        val lookAhead = book.lookAheadLimit  // User-configurable, default 5
        
        val chaptersToProcess = chapterDao.getChaptersInRange(
            bookUrl = book.bookUrl,
            startIndex = currentIndex,
            endIndex = currentIndex + lookAhead
        ).filter { it.status == ChapterStatus.PENDING }
        
        for (chapter in chaptersToProcess) {
            processChapter(chapter, book)
        }
    }
    
    private suspend fun processChapter(chapter: Chapter, book: Book) {
        // 1. Update status
        chapterDao.updateStatus(chapter.id, ChapterStatus.FETCHING)
        
        // 2. Get blueprint
        val blueprint = blueprintDao.getByDomain(book.domain) 
            ?: return markFailed(chapter, "No blueprint for domain")
        
        // 3. Fetch raw content
        val result = ghostBrowser.extractChapterContent(
            chapter.rawUrl, 
            blueprint.contentSelector
        )
        
        if (result.isFailure) {
            return markFailed(chapter, result.exceptionOrNull()?.message)
        }
        
        val rawText = result.getOrThrow()
        
        // 4. Validate content (error detection)
        if (rawText.length < (chapter.expectedMinLength ?: 500)) {
            return markFailed(chapter, "Content too short, possible parse error")
        }
        
        // 5. Detect language & translate
        chapterDao.updateRaw(chapter.id, rawText, detectLanguage(rawText))
        chapterDao.updateStatus(chapter.id, ChapterStatus.TRANSLATING)
        
        val translated = translateChapter(rawText, book.targetLanguage)
        chapterDao.updateProcessed(chapter.id, translated, book.targetLanguage)
        chapterDao.updateStatus(chapter.id, ChapterStatus.READY)
    }
    
    private fun markFailed(chapter: Chapter, error: String?) {
        chapterDao.updateStatus(chapter.id, ChapterStatus.FETCH_FAILED)
        chapterDao.updateError(chapter.id, error ?: "Unknown error")
    }
}
```

### C. User Flag Button (Re-Scout Trigger)

```kotlin
// In ReaderViewModel
fun onUserFlaggedChapter(chapter: Chapter) {
    viewModelScope.launch {
        // Mark chapter for re-processing
        chapterDao.updateStatus(chapter.id, ChapterStatus.USER_FLAGGED)
        
        // Optionally trigger blueprint re-scout if multiple flags on same domain
        val flagCount = chapterDao.getFlaggedCountForDomain(chapter.bookUrl)
        if (flagCount >= 3) {
            // Invalidate blueprint - next fetch will trigger Scout Agent
            blueprintDao.invalidate(book.domain)
        }
    }
}
```

### D. Handling "Load More" (AJAX)

For sites where clicking a button adds content without navigation.

**JS Injection Strategy:**

1. Click the element matching `triggerSelector`.
2. Use a `MutationObserver` in JS to detect when new nodes are added to the DOM.
3. Return a signal to Kotlin when the DOM settles.

### E. State Management

* **GhostBrowserService:** Foreground Service managing the WebView instance. Ensures only 1 background WebView is active to prevent memory crashes.
* **WorkManager:** Handles background pre-fetching if the user minimizes the app.

---

## 7. Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI | Jetpack Compose |
| Networking | Retrofit (LLM API), OkHttp |
| HTML Parsing | Jsoup |
| Browser Engine | Android WebView |
| Database | Room (SQLite) |
| Background Work | WorkManager + Foreground Service |
| LLM Integration | Provider-agnostic interface |

---

## 8. UI Architecture

### Reader Screen (Vertical Scroll)
- **LazyColumn** with smooth fling behavior
- Each chapter rendered as a section within the continuous scroll
- Chapter title as sticky header
- Bottom sheet for chapter navigation & settings
- **Flag Button** (⚠️) in toolbar to report parsing issues

### Settings Screen
- LLM Provider configuration (base URL, API key, model name)
- Source/Target language selector per book
- Look-ahead limit slider (1-10 chapters)
- Theme (light/dark/sepia)

---

## 9. Next Steps for Implementation

1. **Scaffold:** Set up Android project with Room, Retrofit, Hilt, and Jsoup.
2. **Database:** Implement Room entities (`SiteBlueprint`, `Book`, `Chapter`) and DAOs.
3. **LLM Layer:** Create `LlmProvider` interface and `OpenAiCompatibleProvider` implementation.
4. **Ghost Browser:** Implement `GhostBrowser` class with `evaluateJavascript` and WebView lifecycle.
5. **Agents:** Create Scout and Editor prompts with mode selection.
6. **Queue:** Build `PrefetchManager` with configurable look-ahead.
7. **UI:** Build vertical scroll Reader screen with Compose.
8. **Settings:** LLM provider config, language selection, look-ahead slider.