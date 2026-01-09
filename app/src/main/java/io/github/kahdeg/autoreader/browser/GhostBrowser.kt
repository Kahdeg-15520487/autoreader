package io.github.kahdeg.autoreader.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import io.github.kahdeg.autoreader.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Ghost Browser - Headless WebView for background web scraping.
 * Handles page loading, JavaScript execution, and HTML extraction.
 */
@Singleton
class GhostBrowser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var webView: WebView? = null
    private var pageLoadDeferred: CompletableDeferred<Boolean>? = null
    
    /**
     * Initialize the WebView on the main thread.
     * Must be called before using other methods.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun initialize() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext
        
        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                blockNetworkImage = false // Save bandwidth
                loadsImagesAutomatically = false
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageLoadDeferred?.complete(true)
                }
                
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    pageLoadDeferred?.complete(false)
                }
                
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // Allow all navigations within the WebView
                    return true
                }
            }
            
            // Add JavaScript interface for callbacks
            addJavascriptInterface(JsInterface(), "GhostBrowser")
        }
    }
    
    /**
     * Load a URL and wait for the page to finish loading.
     * Includes a delay for JavaScript content to render.
     */
    suspend fun loadUrl(url: String, timeoutMs: Long = 30000, jsDelayMs: Long = 2000): Result<Unit> = withContext(Dispatchers.Main) {
        initialize()
        
        pageLoadDeferred = CompletableDeferred()
        webView?.loadUrl(url)
        
        val success = withTimeoutOrNull(timeoutMs) {
            pageLoadDeferred?.await()
        } ?: false
        
        if (success) {
            // Wait for JavaScript to finish rendering dynamic content
            kotlinx.coroutines.delay(jsDelayMs)
            Result.success(Unit)
        } else {
            Result.failure(Exception("Page load timeout or error"))
        }
    }
    
    /**
     * Wait for an element matching the CSS selector to appear in the DOM.
     * Polls every 200ms until the element exists or timeout is reached.
     */
    suspend fun waitForElement(cssSelector: String, timeoutMs: Long = 10000): Boolean = withContext(Dispatchers.Main) {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val exists = suspendCancellableCoroutine<Boolean> { cont ->
                val js = """
                    (function() {
                        var elements = document.querySelectorAll('$cssSelector');
                        return elements.length > 0;
                    })();
                """.trimIndent()
                
                webView?.evaluateJavascript(js) { result ->
                    cont.resume(result == "true")
                }
            }
            
            if (exists) {
                AppLog.d("GhostBrowser", "waitForElement: '$cssSelector' found after ${System.currentTimeMillis() - startTime}ms")
                return@withContext true
            }
            
            kotlinx.coroutines.delay(200)
        }
        
        AppLog.d("GhostBrowser", "waitForElement: '$cssSelector' timeout after ${timeoutMs}ms")
        false
    }
    
    /**
     * Extract the current page's HTML content.
     */
    suspend fun extractHtml(): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView?.evaluateJavascript(
                "(function() { return document.documentElement.outerHTML; })();"
            ) { result ->
                // Result comes as a JSON string, use proper JSON parsing to unescape
                val html = try {
                    // Wrap in array brackets and parse as JSON to properly decode the string
                    val jsonArray = org.json.JSONArray("[$result]")
                    jsonArray.getString(0)
                } catch (e: Exception) {
                    AppLog.e("GhostBrowser", "Failed to parse HTML JSON: ${e.message}")
                    // Fallback to manual unescaping if JSON parsing fails
                    result
                        ?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                        ?: ""
                }
                
                cont.resume(html)
            }
        }
    }
    
    /**
     * Extract text content from a specific CSS selector.
     */
    suspend fun extractText(cssSelector: String): String = withContext(Dispatchers.Main) {
        val html = extractHtml()
        withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.parse(html)
                doc.select(cssSelector).text()
            } catch (e: Exception) {
                ""
            }
        }
    }
    
    /**
     * Extract all links matching a CSS selector.
     * Returns list of pairs: (text, href)
     */
    suspend fun extractLinks(cssSelector: String): List<Pair<String, String>> = withContext(Dispatchers.Main) {
        val html = extractHtml()
        val baseUrl = webView?.url ?: ""
        
        withContext(Dispatchers.IO) {
            try {
                AppLog.d("GhostBrowser", "extractLinks: baseUrl='$baseUrl', html length=${html.length}")
                
                // Parse with base URL for proper abs:href resolution
                var chapterListRaw = html.indexOf("chapter-list")
                AppLog.d("GhostBrowser", "extractLinks: chapterListRaw=$chapterListRaw")
                val doc = Jsoup.parse(html, baseUrl)
                val elements = doc.select(cssSelector)

                var elements1 = doc.select("#chapter-list")
                AppLog.d("GhostBrowser", "extractLinks: selector='#chapter-list' found ${elements1.size} elements")
                
                AppLog.d("GhostBrowser", "extractLinks: selector='$cssSelector' found ${elements.size} elements")
                
                // Debug: if no elements found, check what's in the document
                if (elements.isEmpty()) {
                    // Check if the parent element exists
                    val selectorParts = cssSelector.split(" ")
                    if (selectorParts.size > 1) {
                        val parentSelector = selectorParts.dropLast(1).joinToString(" ")
                        val parentElements = doc.select(parentSelector)
                        AppLog.d("GhostBrowser", "  Parent selector '$parentSelector' found ${parentElements.size} elements")
                        if (parentElements.isNotEmpty()) {
                            val firstParent = parentElements.first()
                            AppLog.d("GhostBrowser", "  First parent HTML (first 500 chars): ${firstParent?.html()?.take(500)}")
                        }
                    }
                    // Log a snippet of the body to see what's there
                    val body = doc.body()
                    AppLog.d("GhostBrowser", "  Body children count: ${body?.children()?.size ?: 0}")
                    AppLog.d("GhostBrowser", "  Body HTML (first 1000 chars): ${body?.html()?.take(1000)}")
                }
                
                elements.mapNotNull { element ->
                    // Try abs:href first (resolves relative URLs), then raw href
                    val href = element.attr("abs:href").ifEmpty { element.attr("href") }
                    val text = element.text().ifEmpty { element.attr("title") }
                    
                    // Filter out javascript: links, empty links, and anchor-only links
                    val isValidUrl = href.isNotBlank() && 
                        !href.startsWith("javascript:", ignoreCase = true) &&
                        href != "#"
                    
                    if (isValidUrl) {
                        AppLog.d("GhostBrowser", "  Link: '$text' -> $href")
                        text to href
                    } else {
                        if (href.isNotBlank()) {
                            AppLog.d("GhostBrowser", "  Skipping invalid link: '$text' -> $href")
                        }
                        null
                    }
                }
            } catch (e: Exception) {
                AppLog.e("GhostBrowser", "extractLinks error: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * Click an element matching the CSS selector.
     */
    suspend fun clickElement(cssSelector: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    var element = document.querySelector('$cssSelector');
                    if (element) {
                        element.click();
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()
            
            webView?.evaluateJavascript(js) { result ->
                cont.resume(result == "true")
            }
        }
    }
    
    /**
     * Wait for DOM mutations (useful after clicking "Load More" buttons).
     */
    suspend fun waitForDomChange(timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<Boolean>()
        
        val js = """
            (function() {
                var observer = new MutationObserver(function(mutations) {
                    observer.disconnect();
                    GhostBrowser.onDomChanged();
                });
                observer.observe(document.body, { childList: true, subtree: true });
                setTimeout(function() {
                    observer.disconnect();
                    GhostBrowser.onDomTimeout();
                }, $timeoutMs);
            })();
        """.trimIndent()
        
        domChangeCallback = { changed ->
            result.complete(changed)
        }
        
        webView?.evaluateJavascript(js, null)
        
        withTimeoutOrNull(timeoutMs + 1000) {
            result.await()
        } ?: false
    }
    
    /**
     * Scroll to the bottom of the page (for infinite scroll sites).
     */
    suspend fun scrollToBottom(): Unit = withContext(Dispatchers.Main) {
        webView?.evaluateJavascript(
            "window.scrollTo(0, document.body.scrollHeight);",
            null
        )
    }
    
    /**
     * Get the current page URL.
     */
    suspend fun getCurrentUrl(): String? = withContext(Dispatchers.Main) {
        webView?.url
    }
    
    /**
     * Clean up resources.
     */
    suspend fun destroy() = withContext(Dispatchers.Main) {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
    }
    
    // Callback for DOM change detection
    private var domChangeCallback: ((Boolean) -> Unit)? = null
    
    private inner class JsInterface {
        @JavascriptInterface
        fun onDomChanged() {
            domChangeCallback?.invoke(true)
            domChangeCallback = null
        }
        
        @JavascriptInterface
        fun onDomTimeout() {
            domChangeCallback?.invoke(false)
            domChangeCallback = null
        }
    }
}
