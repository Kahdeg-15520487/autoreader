package io.github.kahdeg.autoreader.browser

import io.github.kahdeg.autoreader.data.db.dao.BookDao
import io.github.kahdeg.autoreader.data.db.dao.ChapterDao
import io.github.kahdeg.autoreader.data.db.dao.SiteBlueprintDao
import io.github.kahdeg.autoreader.data.db.entity.Book
import io.github.kahdeg.autoreader.data.db.entity.Chapter
import io.github.kahdeg.autoreader.data.db.entity.ChapterStatus
import io.github.kahdeg.autoreader.data.db.entity.ListType
import io.github.kahdeg.autoreader.data.db.entity.SiteBlueprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import io.github.kahdeg.autoreader.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scraper Service - Orchestrates the Ghost Browser to fetch book info and chapters.
 * Uses SiteBlueprint to navigate and extract content.
 */
@Singleton
class ScraperService @Inject constructor(
    private val ghostBrowser: GhostBrowser,
    private val siteBlueprintDao: SiteBlueprintDao,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) {
    
    /**
     * Fetch book metadata and chapter list from a URL.
     * If no blueprint exists for the domain, this will need the Scout Agent first.
     */
    suspend fun fetchBookInfo(bookUrl: String): Result<BookInfo> = withContext(Dispatchers.IO) {
        try {
            val domain = URI(bookUrl).host ?: return@withContext Result.failure(Exception("Invalid URL"))
            
            // Load the page
            ghostBrowser.loadUrl(bookUrl).getOrThrow()
            
            // Extract basic book info
            val html = ghostBrowser.extractHtml()
            val title = extractTitle(html)
            val author = extractAuthor(html)
            
            // Check if we have a blueprint for this domain
            val blueprint = siteBlueprintDao.getByDomain(domain)
            
            val chapters = if (blueprint != null) {
                fetchChaptersWithBlueprint(bookUrl, blueprint)
            } else {
                // No blueprint - return empty chapters, Scout Agent needed
                emptyList()
            }
            
            Result.success(BookInfo(
                title = title,
                author = author,
                chapters = chapters,
                hasBlueprint = blueprint != null
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Fetch chapter list using the site blueprint.
     */
    private suspend fun fetchChaptersWithBlueprint(
        startUrl: String,
        blueprint: SiteBlueprint
    ): List<ChapterInfo> {
        val allChapters = mutableListOf<ChapterInfo>()
        val seenUrls = mutableSetOf<String>() // Track seen URLs to detect duplicates
        var currentUrl = startUrl
        var keepScraping = true
        var pageCount = 0
        val maxPages = 50 // Safety limit
        
        ghostBrowser.loadUrl(currentUrl)
        
        while (keepScraping && pageCount < maxPages) {
            pageCount++
            
            // Extract chapters from current page
            val links = ghostBrowser.extractLinks(blueprint.chapterCssSelector)
            var newChaptersAdded = 0
            
            links.forEach { (title, url) ->
                val resolvedUrl = resolveUrl(currentUrl, url)
                // Only add if we haven't seen this URL before
                if (seenUrls.add(resolvedUrl)) {
                    allChapters.add(ChapterInfo(
                        index = allChapters.size,
                        title = title.ifBlank { "Chapter ${allChapters.size + 1}" },
                        url = resolvedUrl
                    ))
                    newChaptersAdded++
                }
            }
            
            AppLog.d("ScraperService", "Page $pageCount: found ${links.size} links, added $newChaptersAdded new chapters (total: ${allChapters.size})")
            
            // Handle navigation based on list type
            when (blueprint.listType) {
                ListType.SIMPLE -> {
                    keepScraping = false
                }
                ListType.PAGINATED -> {
                    // Stop if this page had no new chapters (duplicate detection via seenUrls)
                    if (newChaptersAdded == 0) {
                        AppLog.d("ScraperService", "No new chapters on page $pageCount, stopping")
                        keepScraping = false
                        break
                    }
                    
                    val nextSelector = blueprint.nextButtonSelector ?: break
                    
                    // Use click-based navigation for pagination (handles javascript:void(0) links)
                    val clicked = ghostBrowser.clickElement(nextSelector)
                    if (clicked) {
                        // Wait for DOM to update with new chapter list
                        ghostBrowser.waitForDomChange(5000)
                        delay(500) // Extra delay for content to settle
                    } else {
                        AppLog.d("ScraperService", "Next button not found or not clickable, stopping")
                        keepScraping = false
                    }
                }
                ListType.LOAD_MORE -> {
                    val triggerSelector = blueprint.triggerSelector ?: break
                    val clicked = ghostBrowser.clickElement(triggerSelector)
                    if (clicked) {
                        ghostBrowser.waitForDomChange(5000)
                        delay(500)
                    } else {
                        keepScraping = false
                    }
                }
                ListType.EXPANDABLE_TOGGLE -> {
                    val triggerSelector = blueprint.triggerSelector ?: break
                    ghostBrowser.clickElement(triggerSelector)
                    ghostBrowser.waitForDomChange(3000)
                    // Only click once for toggle
                    keepScraping = false
                    // Re-extract after expansion
                    val expandedLinks = ghostBrowser.extractLinks(blueprint.chapterCssSelector)
                    allChapters.clear()
                    expandedLinks.forEachIndexed { index, (title, url) ->
                        allChapters.add(ChapterInfo(
                            index = index,
                            title = title.ifBlank { "Chapter ${index + 1}" },
                            url = resolveUrl(currentUrl, url)
                        ))
                    }
                }
            }
        }
        
        return allChapters
    }
    
    /**
     * Fetch the content of a single chapter.
     */
    suspend fun fetchChapterContent(
        chapter: Chapter,
        book: Book
    ): Result<String> = withContext(Dispatchers.IO) {
        AppLog.d("ScraperService", "fetchChapterContent: id=${chapter.id}, index=${chapter.index}, title=${chapter.title}")
        AppLog.d("ScraperService", "  Chapter URL: ${chapter.rawUrl}")
        
        try {
            // Update status
            chapterDao.updateStatus(chapter.id, ChapterStatus.FETCHING)
            
            // Get blueprint
            val blueprint = siteBlueprintDao.getByDomain(book.domain)
                ?: return@withContext Result.failure(Exception("No blueprint for domain ${book.domain}"))
            
            AppLog.d("ScraperService", "  Content selector: ${blueprint.contentSelector}")
            
            // Load chapter page
            ghostBrowser.loadUrl(chapter.rawUrl).getOrThrow()
            
            // Extract content
            val content = ghostBrowser.extractText(blueprint.contentSelector)
            AppLog.d("ScraperService", "  Extracted content length: ${content.length}")
            
            if (content.isBlank()) {
                chapterDao.updateStatus(chapter.id, ChapterStatus.FETCH_FAILED)
                chapterDao.updateError(chapter.id, "Content selector returned empty")
                return@withContext Result.failure(Exception("Empty content"))
            }
            
            // Validate content length
            val minLength = chapter.expectedMinLength ?: 200
            if (content.length < minLength) {
                chapterDao.updateStatus(chapter.id, ChapterStatus.FETCH_FAILED)
                chapterDao.updateError(chapter.id, "Content too short (${content.length} chars)")
                return@withContext Result.failure(Exception("Content too short"))
            }
            
            // Save raw content
            AppLog.d("ScraperService", "  Saving content to chapter id=${chapter.id}")
            chapterDao.updateRaw(chapter.id, content, book.sourceLanguage)
            
            Result.success(content)
        } catch (e: Exception) {
            AppLog.e("ScraperService", "  Fetch failed: ${e.message}")
            chapterDao.updateStatus(chapter.id, ChapterStatus.FETCH_FAILED)
            chapterDao.updateError(chapter.id, e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * Save chapters to database.
     */
    suspend fun saveChapters(bookUrl: String, chapters: List<ChapterInfo>) {
        val chapterEntities = chapters.map { info ->
            Chapter(
                bookUrl = bookUrl,
                index = info.index,
                title = info.title,
                rawUrl = info.url,
                status = ChapterStatus.PENDING,
                rawText = null,
                rawLanguage = null,
                processedText = null,
                processedLanguage = null,
                errorMessage = null
            )
        }
        chapterDao.upsertAll(chapterEntities)
    }
    
    // Helper functions
    
    private fun extractTitle(html: String): String {
        val doc = org.jsoup.Jsoup.parse(html)
        // Try common title selectors
        return doc.select("h1").firstOrNull()?.text()
            ?: doc.select(".book-title, .novel-title, .story-title").firstOrNull()?.text()
            ?: doc.select("title").firstOrNull()?.text()?.substringBefore(" - ")
            ?: "Unknown Title"
    }
    
    private fun extractAuthor(html: String): String {
        val doc = org.jsoup.Jsoup.parse(html)
        // Try common author selectors
        return doc.select(".author, .book-author, .novel-author, [itemprop=author]").firstOrNull()?.text()
            ?: doc.select("a[href*=author]").firstOrNull()?.text()
            ?: "Unknown Author"
    }
    
    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val base = URI(baseUrl)
            base.resolve(relativeUrl).toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }
}

data class BookInfo(
    val title: String,
    val author: String,
    val chapters: List<ChapterInfo>,
    val hasBlueprint: Boolean
)

data class ChapterInfo(
    val index: Int,
    val title: String,
    val url: String
)
