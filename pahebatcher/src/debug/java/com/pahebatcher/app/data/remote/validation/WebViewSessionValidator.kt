package com.pahebatcher.app.data.remote.validation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import com.pahebatcher.app.logging.AppLog
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Validation spike for WebView-based Cloudflare session establishment.
 *
 * Purpose: Determine whether Android WebView can:
 * 1. Load AnimePahe site
 * 2. Handle Cloudflare challenges
 * 3. Extract usable session cookies
 *
 * This is a validation tool, NOT production code.
 */
class WebViewSessionValidator(private val context: Context) {

    companion object {
        private const val TAG = "WebViewValidator"
        private const val ANIMEPAHE_URL = "https://animepahe.com"
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val CF_CHECK_DELAY_MS = 5_000L
    }

    /**
     * Result of WebView validation attempt.
     */
    data class ValidationResult(
        val pageLoaded: Boolean,
        val cloudflareChallengeDetected: Boolean,
        val cloudflareChallengeCompleted: Boolean,
        val animePaheAccessible: Boolean,
        val cookies: Map<String, String>,
        val userAgent: String,
        val pageTitle: String,
        val currentUrl: String,
        val diagnosticMessages: List<String>,
        val success: Boolean
    )

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Perform WebView validation.
     * Must be called from main thread or with proper dispatching.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun validate(): ValidationResult = withContext(Dispatchers.Main) {
        val diagnostics = mutableListOf<String>()
        val pageLoaded = CompletableDeferred<Boolean>()
        val cfChallengeDetected = CompletableDeferred<Boolean>()
        val cfChallengeCompleted = CompletableDeferred<Boolean>()

        var finalUrl = ""
        var pageTitle = ""
        var cfDetected = false
        var cfCompleted = false
        var interceptedResponses = mutableListOf<String>()

        try {
            // Create WebView
            val wv = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    // Allow mixed content for debugging
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        AppLog.d(TAG, "Page started: $url")
                        diagnostics.add("Page started: $url")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        AppLog.d(TAG, "Page finished: $url")
                        diagnostics.add("Page finished: $url")
                        finalUrl = url ?: ""
                        pageTitle = view?.title ?: ""
                        pageLoaded.complete(true)
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        AppLog.w(TAG, "SSL Error: ${error?.toString()}")
                        diagnostics.add("SSL Error: ${error?.toString()}")
                        handler?.proceed() // Allow for validation - NOT for production
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: ""
                        // Log Cloudflare-related responses
                        if (url.contains("cloudflare") || url.contains("challenge") ||
                            url.contains("cf-") || url.contains("turnstile")) {
                            diagnostics.add("CF-related request: $url")
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        AppLog.d(TAG, "Progress: $newProgress%")
                        if (newProgress >= 100) {
                            diagnostics.add("Progress reached 100%")
                        }
                    }
                }
            }

            webView = wv

            // Load the page
            diagnostics.add("Loading $ANIMEPAHE_URL")
            wv.loadUrl(ANIMEPAHE_URL)

            // Wait for page load with timeout
            val loadResult = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                pageLoaded.await()
            }

            if (loadResult == null) {
                diagnostics.add("Page load timed out after ${LOAD_TIMEOUT_MS}ms")
            }

            // Check for Cloudflare challenge indicators
            val pageContent = withContext(Dispatchers.IO) {
                // Small delay to allow JavaScript to execute
                delay(CF_CHECK_DELAY_MS)
                // Get cookies from both domains
                val cookieManager = CookieManager.getInstance()
                val cookies1 = cookieManager.getCookie("https://animepahe.com") ?: ""
                val cookies2 = cookieManager.getCookie("https://animepahe.pw") ?: ""
                cookies1 + " " + cookies2
            }

            // Analyze for Cloudflare indicators
            cfDetected = pageContent.contains("cf_clearance") ||
                    pageContent.contains("__cfduid") ||
                    pageTitle.lowercase().contains("just a moment") ||
                    pageTitle.lowercase().contains("checking your browser") ||
                    finalUrl.contains("challenge") ||
                    finalUrl.contains("captcha")

            cfCompleted = pageContent.contains("cf_clearance") ||
                    (pageTitle.lowercase().contains("animepahe") && !cfDetected)

            if (cfDetected) {
                diagnostics.add("Cloudflare challenge detected")
                cfChallengeDetected.complete(true)

                // Wait for potential challenge completion
                delay(CF_CHECK_DELAY_MS)

                // Re-check cookies after delay
                val cookieManager = CookieManager.getInstance()
                val updatedCookies1 = cookieManager.getCookie("https://animepahe.com") ?: ""
                val updatedCookies2 = cookieManager.getCookie("https://animepahe.pw") ?: ""
                val updatedCookies = updatedCookies1 + " " + updatedCookies2
                cfCompleted = updatedCookies.contains("cf_clearance") ||
                        (pageTitle.lowercase().contains("animepahe") && !updatedCookies.contains("challenge"))
                if (cfCompleted) {
                    diagnostics.add("Cloudflare challenge completed")
                    cfChallengeCompleted.complete(true)
                } else {
                    diagnostics.add("Cloudflare challenge NOT completed after wait")
                }
            } else {
                diagnostics.add("No Cloudflare challenge detected")
                cfChallengeDetected.complete(false)
                cfChallengeCompleted.complete(false)
            }

            // Extract cookies from both domains (page may have redirected)
            val cookieManager = CookieManager.getInstance()
            val cookiesFromCom = cookieManager.getCookie("https://animepahe.com") ?: ""
            val cookiesFromPw = cookieManager.getCookie("https://animepahe.pw") ?: ""
            val cookieString = if (cookiesFromPw.isNotEmpty()) cookiesFromPw else cookiesFromCom
            val cookies = parseCookies(cookieString)
            // Cookie diagnostics expose NAMES/KEYS ONLY — never raw cookie values
            // (Decision 70 / LIMITATIONS §16). The header string itself must not
            // reach `diagnostics`, which ValidationScreen renders verbatim.
            diagnostics.add("Cookie names from animepahe.com: ${parseCookies(cookiesFromCom).keys}")
            diagnostics.add("Cookie names from animepahe.pw: ${parseCookies(cookiesFromPw).keys}")

            // Get User-Agent
            val userAgent = wv.settings.userAgentString

            // Check if AnimePahe is accessible
            val animePaheAccessible = pageTitle.lowercase().contains("animepahe") ||
                    finalUrl.contains("animepahe")

            val success = animePaheAccessible && cfCompleted

            AppLog.i(TAG, "Validation complete: success=$success, cfChallenge=$cfDetected, cfCompleted=$cfCompleted")
            diagnostics.add("Validation complete: success=$success")

            ValidationResult(
                pageLoaded = loadResult != null,
                cloudflareChallengeDetected = cfDetected,
                cloudflareChallengeCompleted = cfCompleted,
                animePaheAccessible = animePaheAccessible,
                cookies = cookies,
                userAgent = userAgent,
                pageTitle = pageTitle,
                currentUrl = finalUrl,
                diagnosticMessages = diagnostics,
                success = success
            )

        } catch (e: Exception) {
            AppLog.e(TAG, "Validation failed", e)
            diagnostics.add("Exception: ${e.message}")
            ValidationResult(
                pageLoaded = false,
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = false,
                animePaheAccessible = false,
                cookies = emptyMap(),
                userAgent = "",
                pageTitle = "",
                currentUrl = "",
                diagnosticMessages = diagnostics,
                success = false
            )
        } finally {
            // Cleanup
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    /**
     * Parse cookie string into map.
     */
    private fun parseCookies(cookieString: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        cookieString.split(";").forEach { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) {
                cookies[parts[0].trim()] = parts[1].trim()
            }
        }
        return cookies
    }

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
    }
}
