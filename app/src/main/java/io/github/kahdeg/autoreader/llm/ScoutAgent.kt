package io.github.kahdeg.autoreader.llm

import io.github.kahdeg.autoreader.data.db.entity.ListType
import io.github.kahdeg.autoreader.data.db.entity.SiteBlueprint
import io.github.kahdeg.autoreader.data.db.entity.WaitStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scout Agent - Analyzes HTML to generate a SiteBlueprint for scraping.
 */
@Singleton
class ScoutAgent @Inject constructor(
    private val llmProvider: LlmProvider
) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    companion object {
        private val SYSTEM_PROMPT = """
You are a CSS Selector Expert analyzing web novel/story websites.
Your goal is to provide a machine-readable Blueprint to scrape chapter lists from this site.

Analyze the provided HTML and return a JSON object with these fields:
- listType: "SIMPLE" | "PAGINATED" | "LOAD_MORE" | "EXPANDABLE_TOGGLE"
- chapterCssSelector: CSS selector for chapter links
- contentSelector: CSS selector for chapter content body (leave empty if analyzing book page, not chapter page)
- nextButtonSelector: CSS selector for "Next Page" link (REQUIRED for PAGINATED)
- triggerSelector: CSS selector for load/expand button (for LOAD_MORE or EXPANDABLE_TOGGLE)
- waitStrategy: "NETWORK" | "MUTATION" | "TIMEOUT"

IMPORTANT - How to detect listType:
1. PAGINATED - Look for these indicators:
   - Page numbers like "1 2 3 4" or "« 1 2 3 »" in boxes or links
   - Pagination divs/nav with class containing "paging", "pagination", "page-nav", "page-numbers"
   - Links with text like "Next", "»", ">", "Sau", "Tiếp", "Cuối", "下一页", "다음"
   - Vietnamese: "Cuối »" means "Last", look for this pattern
   - If you see ANY pagination elements, ALWAYS use listType="PAGINATED"
   
2. LOAD_MORE - Button that loads more content without page change:
   - Buttons with text like "Load More", "Show More", "Xem thêm"
   - Usually has onclick/AJAX behavior
   
3. EXPANDABLE_TOGGLE - Button that reveals hidden content:
   - "Show All Chapters", "Expand", "View All"
   - Hidden content becomes visible after click
   
4. SIMPLE - Use ONLY if:
   - ALL chapters are visible on one page
   - NO pagination numbers, NO navigation links
   - Very rare - most novel sites are PAGINATED

Common chapter selectors (Vietnamese novel sites):
- "#list-chapter a", ".chapter-list a", "ul#list-chapter li a"
- ".list-chapter a", ".chapter-item a", "#chapter-list a"
- "ul.list-chap li a", ".chapters-list a"

Common pagination/next button selectors:
- ".pagination li:last-child a", ".pagination a.next"
- ".paging a:last-child", "a[rel=next]"
- "ul.pagination li a[title*='Next']", "nav.pagination a:last-of-type"
- For ">": "a:contains('>')" or ".pagination li:nth-last-child(2) a"

Return ONLY valid JSON, no markdown or explanation.

Example for PAGINATED site:
{
  "listType": "PAGINATED",
  "chapterCssSelector": "#list-chapter a",
  "contentSelector": "",
  "nextButtonSelector": ".pagination a:last-child",
  "triggerSelector": null,
  "waitStrategy": "NETWORK"
}

Example for SIMPLE site:
{
  "listType": "SIMPLE",
  "chapterCssSelector": ".chapter-list a",
  "contentSelector": "",
  "nextButtonSelector": null,
  "triggerSelector": null,
  "waitStrategy": "NETWORK"
}
        """.trimIndent()
        
        private val CONTENT_SYSTEM_PROMPT = """
You are a CSS Selector Expert analyzing a chapter/content page from a web novel/story website.
Your goal is to find the CSS selector for the main chapter text content.

Look for these patterns:
- Main content containers with class: "chapter-content", "content", "noi-dung", "reading-content", "text-content", "entry-content"
- IDs like "chapter-content", "content", "detail-content"
- Large text blocks with paragraphs inside a div/article
- The element that contains the actual story text, NOT navigation, ads, or comments

AVOID selecting:
- Navigation elements (prev/next chapter links)
- Advertisement containers
- Comment sections
- Social sharing buttons
- Related stories/recommendations

Return ONLY a JSON object with:
{
  "contentSelector": "CSS selector for the main story content"
}

Return ONLY valid JSON, no markdown or explanation.
        """.trimIndent()
    }
    
    /**
     * Analyze HTML and generate a SiteBlueprint.
     */
    suspend fun analyzeHtml(html: String, domain: String): Result<SiteBlueprint> {
        // Truncate HTML to reduce tokens (keep head and main content)
        val truncatedHtml = truncateHtml(html, maxLength = 15000)
        
        val request = LlmRequest(
            systemPrompt = SYSTEM_PROMPT,
            userMessage = "Analyze this HTML and return a JSON blueprint:\n\n$truncatedHtml",
            maxTokens = 500,
            temperature = 0.1f,
            jsonMode = true
        )
        
        return try {
            val response = llmProvider.complete(request).getOrThrow()
            response.reasoningContent?.let { 
                android.util.Log.d("ScoutAgent", "analyzeHtml reasoning: $it") 
            }
            android.util.Log.d("ScoutAgent", "analyzeHtml response (${response.tokensUsed} tokens): ${response.content}")
            val blueprint = parseBlueprintResponse(response.content, domain)
            android.util.Log.d("ScoutAgent", "Parsed blueprint: listType=${blueprint.listType}, chapterSelector=${blueprint.chapterCssSelector}, contentSelector=${blueprint.contentSelector}")
            Result.success(blueprint)
        } catch (e: Exception) {
            android.util.Log.e("ScoutAgent", "analyzeHtml failed: ${e.message}")
            Result.failure(Exception("Scout Agent failed: ${e.message}"))
        }
    }
    
    private fun truncateHtml(html: String, maxLength: Int): String {
        if (html.length <= maxLength) return html
        
        // Try to keep the meaningful parts
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        val bodyStart = html.indexOf("<body", ignoreCase = true)
        
        return if (headEnd > 0 && bodyStart > 0) {
            val head = html.substring(0, minOf(headEnd + 7, 3000))
            val body = html.substring(bodyStart, minOf(bodyStart + maxLength - head.length, html.length))
            head + "\n...\n" + body
        } else {
            html.take(maxLength)
        }
    }
    
    private fun parseBlueprintResponse(response: String, domain: String): SiteBlueprint {
        // Clean up response
        val jsonStr = response
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        
        val parsed = json.decodeFromString<BlueprintDto>(jsonStr)
        
        return SiteBlueprint(
            domain = domain,
            listType = when (parsed.listType.uppercase()) {
                "PAGINATED" -> ListType.PAGINATED
                "LOAD_MORE" -> ListType.LOAD_MORE
                "EXPANDABLE_TOGGLE" -> ListType.EXPANDABLE_TOGGLE
                else -> ListType.SIMPLE
            },
            chapterCssSelector = parsed.chapterCssSelector,
            contentSelector = parsed.contentSelector,
            nextButtonSelector = parsed.nextButtonSelector?.takeIf { it.isNotBlank() },
            triggerSelector = parsed.triggerSelector?.takeIf { it.isNotBlank() },
            waitStrategy = when (parsed.waitStrategy?.uppercase()) {
                "MUTATION" -> WaitStrategy.MUTATION
                "TIMEOUT" -> WaitStrategy.TIMEOUT
                else -> WaitStrategy.NETWORK
            },
            lastValidated = System.currentTimeMillis(),
            version = 1
        )
    }
    
    /**
     * Analyze a chapter page HTML to extract the content selector.
     * Call this after analyzeHtml() with HTML from an actual chapter page.
     */
    suspend fun analyzeChapterHtml(html: String): Result<String> {
        val truncatedHtml = truncateHtml(html, maxLength = 15000)
        
        val request = LlmRequest(
            systemPrompt = CONTENT_SYSTEM_PROMPT,
            userMessage = "Analyze this chapter page HTML and find the content selector:\n\n$truncatedHtml",
            maxTokens = 200,
            temperature = 0.1f,
            jsonMode = true
        )
        
        return try {
            val response = llmProvider.complete(request).getOrThrow()
            response.reasoningContent?.let { 
                android.util.Log.d("ScoutAgent", "analyzeChapterHtml reasoning: $it") 
            }
            android.util.Log.d("ScoutAgent", "analyzeChapterHtml response (${response.tokensUsed} tokens): ${response.content}")
            
            val jsonStr = response.content
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            android.util.Log.d("ScoutAgent", "Parsed JSON string: $jsonStr")
            val parsed = json.decodeFromString<ContentSelectorDto>(jsonStr)
            android.util.Log.d("ScoutAgent", "Extracted content selector: ${parsed.contentSelector}")
            
            if (parsed.contentSelector.isNotBlank()) {
                Result.success(parsed.contentSelector)
            } else {
                Result.failure(Exception("Empty content selector"))
            }
        } catch (e: Exception) {
            android.util.Log.e("ScoutAgent", "Failed to analyze chapter HTML: ${e.message}", e)
            Result.failure(Exception("Failed to analyze chapter content: ${e.message}"))
        }
    }
}

@Serializable
private data class BlueprintDto(
    val listType: String,
    val chapterCssSelector: String,
    val contentSelector: String = "",
    val nextButtonSelector: String? = null,
    val triggerSelector: String? = null,
    val waitStrategy: String? = null
)

@Serializable
private data class ContentSelectorDto(
    val contentSelector: String
)
