package com.pahebatcher.app.data.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.pahebatcher.app.logging.AppLog
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.SessionPersistence
import com.pahebatcher.app.domain.session.SessionRefreshSignal
import com.pahebatcher.app.domain.session.WebViewRequestBridge
import com.pahebatcher.app.logging.LogUrlSanitizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Android WebView-based implementation of CloudflareSessionProvider.
 *
 * This is the real browser/session mechanism for all Cloudflare-protected AnimePahe operations.
 * It creates an actual Android WebView, loads AnimePahe, handles Cloudflare challenges,
 * and extracts session metadata.
 *
 * Architecture context:
 * - Cloudflare binds cf_clearance cookies to the TLS fingerprint that solved the challenge
 * - OkHttp's TLS fingerprint differs from WebView's, so cookie transfer does NOT bypass Cloudflare
 * - All Cloudflare-protected AnimePahe requests must go through this WebView mechanism
 * - OkHttp can only be used for non-Cloudflare endpoints (HLS segments, AES keys)
 *
 * Thread safety:
 * - Uses Mutex for session establishment to prevent competing WebView creation
 * - WebView operations are dispatched to main thread
 * - Session state is updated atomically
 * - WebView destruction is always dispatched to the main thread
 *
 * Lifecycle:
 * - Does NOT hold Activity/Context reference in long-lived state
 * - Uses application context for WebView creation
 * - WebView is destroyed when session is released
 */
class AndroidCloudflareSession(
    private val applicationContext: Context,
    private val sessionPersistence: SessionPersistence
) : CloudflareSessionProvider {

    companion object {
        private const val TAG = "CFSession"
        private const val ANIMEPAHE_BASE_URL = "https://animepahe.com"
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val CF_CHECK_DELAY_MS = 5_000L
        private const val MAX_CF_WAIT_CYCLES = 3

        /** Host fragment identifying a Kwik page request (11.5E-4 referrer evidence). */
        private const val KWIK_HOST_MARKER = "kwik."

        /** The one header name this class records presence of (never its value). */
        private const val REFERRER_HEADER = "Referer"
        /** Session freshness threshold: 20 minutes. Cloudflare cookies typically expire in ~30 min. */
        private const val SESSION_FRESHNESS_MS = 20 * 60 * 1000L

        /**
         * Maximum time to wait for the evaluateJavascript JSON capture callback
         * after the page has finished loading.
         */
        private const val JSON_CAPTURE_TIMEOUT_MS = 5_000L

        /**
         * JavaScript that captures the page's DOM serialization (used for HTML requests).
         */
        internal const val HTML_CAPTURE_JS =
            "(function() { return document.documentElement.outerHTML; })()"

        /**
         * JavaScript that returns the raw response body text of the rendered document.
         *
         * The WebView renders a JSON endpoint response as a text document (Chromium
         * wraps the body in a <pre> element). Reading that <pre> element's textContent
         * yields the exact response body text — no HTML escaping and no DOM
         * serialization — which is what the JSON parser requires. Falls back to
         * body.innerText for text documents rendered without a <pre> element.
         */
        internal const val JSON_BODY_EXTRACTION_JS =
            "(function() { var pre = document.querySelector('pre'); " +
            "if (pre && document.body && document.body.contains(pre)) { return pre.textContent; } " +
            "var body = document.body; return body ? body.innerText : ''; })()"

        /**
         * Decode a JavaScript string literal returned by evaluateJavascript into the
         * exact string it represents.
         *
         * evaluateJavascript returns its result as a JSON string literal (or the bare
         * literal `null` when the script produced no value). Decoding through the JSON
         * parser guarantees the original response body text is reconstructed exactly —
         * including quotes, backslashes, newlines, unicode and HTML-significant
         * characters — which a manual unescape cannot guarantee for arbitrary payloads.
         *
         * @return the decoded string, or "" for null/unreadable input
         */
        internal fun decodeJsStringLiteral(value: String?): String {
            if (value.isNullOrEmpty() || value == "null") return ""
            return try {
                Json.parseToJsonElement(value).jsonPrimitive.content
            } catch (e: Exception) {
                // evaluateJavascript must return a valid JSON string literal; if it
                // somehow does not, report no content rather than a corrupted body.
                ""
            }
        }
    }

    // Thread-safe session state
    private val _sessionState = AtomicReference(CloudflareSessionProvider.SessionState.NOT_STARTED)
    private val _sessionInfo = AtomicReference<CloudflareSessionProvider.SessionInfo?>(null)
    private val establishMutex = Mutex()

    // WebView management - only created on main thread
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var webView: WebView? = null

    override fun currentState(): CloudflareSessionProvider.SessionState {
        return _sessionState.get()
    }

    override fun getSessionInfo(): CloudflareSessionProvider.SessionInfo {
        return _sessionInfo.get() ?: CloudflareSessionProvider.SessionInfo(
            state = _sessionState.get(),
            userAgent = "",
            cloudflareCompleted = false,
            finalUrl = "",
            isReady = false,
            lastValidatedAt = 0L,
            cookieCount = 0,
            activeHost = ""
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo {
        return establishMutex.withLock {
            // Check if we already have a valid session (and not forcing refresh)
            if (!forceRefresh && _sessionState.get() == CloudflareSessionProvider.SessionState.ESTABLISHED) {
                val info = _sessionInfo.get()
                if (info != null && info.isReady) {
                    AppLog.d(TAG, "Reusing existing valid session")
                    return@withLock info
                }
            }

            // M6: If session was invalidated while waiting for the mutex, don't overwrite
            // the EXPIRED state with ESTABLISHING. Let the caller decide.
            if (!forceRefresh && _sessionState.get() == CloudflareSessionProvider.SessionState.EXPIRED) {
                AppLog.d(TAG, "Session was invalidated, skipping re-establishment without forceRefresh")
                return@withLock getSessionInfo()
            }

            // NET-008: a persisted-session lookup was here but its result only fed a
            // log line — `persisted` never influenced establishment (a non-forced
            // re-establish always runs the WebView verification regardless). The dead
            // SharedPreferences read is removed; expiry is still detected reactively by
            // the fetch path and proactively by [isSessionValid].

            AppLog.i(TAG, "Establishing new browser session")
            _sessionState.set(CloudflareSessionProvider.SessionState.ESTABLISHING)

            try {
                // §3.3 (DECISIONS 157): a FORCED re-establishment retires any stale
                // cf_clearance BEFORE the initial load. The completion check in
                // [waitForCloudflareCompletion] — and the validity probe in
                // [isSessionValid] — are PRESENCE-only, so a revoked-but-still-present
                // cookie makes a forced refresh "succeed" while every real fetch keeps
                // returning 401/403 (the historical failure only broken by clearing app
                // data). Expiring it here forces a real challenge, so the presence check
                // can only pass on a cookie the WebView earns fresh this run. The healthy
                // (non-forced) path and every other cookie are untouched.
                if (forceRefresh) {
                    clearStaleClearanceCookie()
                }

                val result = withContext(Dispatchers.Main) {
                    performSessionEstablishment()
                }

                if (result.isReady) {
                    _sessionState.set(CloudflareSessionProvider.SessionState.ESTABLISHED)
                    AppLog.i(TAG, "Session established successfully: host=${result.activeHost}")
                } else {
                    _sessionState.set(CloudflareSessionProvider.SessionState.FAILED)
                    AppLog.w(TAG, "Session establishment failed")
                }

                _sessionInfo.set(result)

                // Persist session metadata
                if (result.isReady) {
                    sessionPersistence.saveSession(
                        SessionPersistence.PersistedSession(
                            userAgent = result.userAgent,
                            activeHost = result.activeHost,
                            lastValidatedAt = result.lastValidatedAt,
                            cloudflareCompleted = result.cloudflareCompleted,
                            cookieCount = result.cookieCount,
                            finalUrl = result.finalUrl
                        )
                    )
                }

                result
            } catch (e: CancellationException) {
                // NET-001: propagate cancellation instead of reporting a FAILED session
                // (which the bridge would retry / prompt on for abandoned work).
                AppLog.d(TAG, "Session establishment cancelled")
                _sessionState.set(CloudflareSessionProvider.SessionState.FAILED)
                _sessionInfo.set(null)
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Session establishment exception", e)
                // M7: Clean up WebView on failure
                withContext(Dispatchers.Main) {
                    destroyWebView()
                }
                _sessionState.set(CloudflareSessionProvider.SessionState.FAILED)
                val failInfo = CloudflareSessionProvider.SessionInfo(
                    state = CloudflareSessionProvider.SessionState.FAILED,
                    userAgent = "",
                    cloudflareCompleted = false,
                    finalUrl = "",
                    isReady = false,
                    lastValidatedAt = System.currentTimeMillis(),
                    cookieCount = 0,
                    activeHost = ""
                )
                _sessionInfo.set(failInfo)
                failInfo
            }
        }
    }

    override suspend fun isSessionValid(): Boolean {
        val state = _sessionState.get()
        if (state != CloudflareSessionProvider.SessionState.ESTABLISHED) {
            return false
        }

        val info = _sessionInfo.get() ?: return false

        // H1: Check session freshness using lastValidatedAt timestamp.
        // Cloudflare cookies typically expire in ~30 minutes. We use a 20-minute threshold
        // to proactively detect stale sessions before they cause request failures.
        val sessionAge = System.currentTimeMillis() - info.lastValidatedAt
        if (sessionAge > SESSION_FRESHNESS_MS) {
            AppLog.d(TAG, "Session is stale: age=${sessionAge}ms, threshold=${SESSION_FRESHNESS_MS}ms")
            return false
        }

        // Check cookie presence via CookieManager (background thread)
        return withContext(Dispatchers.IO) {
            try {
                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie("https://animepahe.com")
                    ?: cookieManager.getCookie("https://animepahe.pw")
                    ?: ""
                cookies.contains("cf_clearance")
            } catch (e: Exception) {
                AppLog.w(TAG, "Session validity check failed", e)
                false
            }
        }
    }

    override fun invalidateSession() {
        AppLog.i(TAG, "Session invalidated")
        _sessionState.set(CloudflareSessionProvider.SessionState.EXPIRED)
        _sessionInfo.set(null)
    }

    override suspend fun fetchPage(
        url: String,
        waitForCloudflare: Boolean,
        timeoutMs: Long,
        contentType: WebViewRequestBridge.ContentType,
        extraHeaders: Map<String, String>
    ): CloudflareSessionProvider.PageResult {
        // Ensure we have a valid session
        val sessionInfo = ensureSession()

        if (!sessionInfo.isReady) {
            // SPEC §6.10 (final-audit H1): the resolver calls fetchPage directly
            // (no bridge pre-check), so this is where ITS session-not-ready case
            // must prompt instead of failing silently. State name only (§10).
            SessionRefreshSignal.requestUserPrompt("Session not ready: ${sessionInfo.state}")
            return CloudflareSessionProvider.PageResult(
                success = false,
                html = null,
                json = null,
                finalUrl = "",
                statusCode = 0,
                error = "Session not ready: ${sessionInfo.state}",
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = false,
                diagnostics = listOf("Session state: ${sessionInfo.state}")
            )
        }

        // Perform the actual page fetch via WebView
        return withContext(Dispatchers.Main) {
            performPageFetch(url, waitForCloudflare, timeoutMs, contentType, extraHeaders)
        }
    }

    override fun releaseSession() {
        AppLog.i(TAG, "Releasing session")
        // C2: Ensure WebView destruction happens on the main thread.
        // WebView.stopLoading() and WebView.destroy() must be called on the thread
        // that created the WebView (the main thread).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyWebView()
        } else {
            mainHandler.post { destroyWebView() }
        }
        _sessionState.set(CloudflareSessionProvider.SessionState.NOT_STARTED)
        _sessionInfo.set(null)
    }

    // ==================== Internal Implementation ====================

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun performSessionEstablishment(): CloudflareSessionProvider.SessionInfo {
        val diagnostics = mutableListOf<String>()
        val pageLoaded = CompletableDeferred<Boolean>()
        var finalUrl = ""
        var pageTitle = ""
        var cfDetected = false
        var cfCompleted = false

        return try {
            // Destroy any existing WebView
            destroyWebView()

            // Create new WebView on main thread
            val wv = createWebView(diagnostics, pageLoaded) { url, title ->
                finalUrl = url
                pageTitle = title
            }

            webView = wv

            // Load the page
            diagnostics.add("Loading $ANIMEPAHE_BASE_URL")
            wv.loadUrl(ANIMEPAHE_BASE_URL)

            // Wait for page load with timeout
            val loadResult = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                pageLoaded.await()
            }

            if (loadResult == null) {
                diagnostics.add("Page load timed out after ${LOAD_TIMEOUT_MS}ms")
            }

            // Wait for Cloudflare challenge to complete
            if (waitForCloudflareCompletion(diagnostics)) {
                cfCompleted = true
            }

            // Extract cookies — NET-013: `getCookie` is a blocking platform read,
            // the same call `isSessionValid` deliberately takes to Dispatchers.IO;
            // establishment runs on Main, so hop to IO for the read only.
            val cookieString = withContext(Dispatchers.IO) {
                val cookieManager = CookieManager.getInstance()
                val cookiesFromCom = cookieManager.getCookie("https://animepahe.com") ?: ""
                val cookiesFromPw = cookieManager.getCookie("https://animepahe.pw") ?: ""
                cookiesFromPw.ifEmpty { cookiesFromCom }
            }
            val cookieCount = cookieString.split(";").count { it.trim().isNotEmpty() }

            // Get User-Agent
            val userAgent = wv.settings.userAgentString

            // Check if AnimePahe is accessible
            val animePaheAccessible = pageTitle.lowercase().contains("animepahe") ||
                    finalUrl.contains("animepahe")

            // Determine active host
            val activeHost = when {
                finalUrl.contains("animepahe.com") -> "animepahe.com"
                finalUrl.contains("animepahe.pw") -> "animepahe.pw"
                finalUrl.contains("animepahe.org") -> "animepahe.org"
                else -> "animepahe.com"
            }

            val isReady = animePaheAccessible && cfCompleted

            diagnostics.add("Session establishment complete: ready=$isReady")

            CloudflareSessionProvider.SessionInfo(
                state = if (isReady) CloudflareSessionProvider.SessionState.ESTABLISHED
                else CloudflareSessionProvider.SessionState.FAILED,
                userAgent = userAgent,
                cloudflareCompleted = cfCompleted,
                finalUrl = finalUrl,
                isReady = isReady,
                lastValidatedAt = System.currentTimeMillis(),
                cookieCount = cookieCount,
                activeHost = activeHost
            )
        } catch (e: CancellationException) {
            // AQR-019: propagate scope teardown instead of converting it into a
            // FAILED SessionInfo — a cancelled establishment is not a failure the
            // caller may retry/prompt on (NET-001 contract, restated here because
            // the generic arm below would otherwise swallow the cancellation).
            AppLog.d(TAG, "Session establishment cancelled")
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Session establishment failed", e)
            diagnostics.add("Exception: ${e.message}")
            CloudflareSessionProvider.SessionInfo(
                state = CloudflareSessionProvider.SessionState.FAILED,
                userAgent = "",
                cloudflareCompleted = false,
                finalUrl = "",
                isReady = false,
                lastValidatedAt = System.currentTimeMillis(),
                cookieCount = 0,
                activeHost = ""
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        diagnostics: MutableList<String>,
        pageLoaded: CompletableDeferred<Boolean>,
        onPageFinished: (url: String, title: String) -> Unit
    ): WebView {
        return WebView(applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                // M4: Use secure default — never allow mixed content in production.
                // WebView defaults to MIXED_CONTENT_NEVER_ALLOW which is correct.
                // Do NOT set MIXED_CONTENT_ALWAYS_ALLOW even for debugging.
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // NET-009: host + path only — diagnostics render on screen.
                    val where = LogUrlSanitizer.url(url)
                    AppLog.d(TAG, "Page started: $where")
                    diagnostics.add("Page started: $where")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val where = LogUrlSanitizer.url(url)
                    AppLog.d(TAG, "Page finished: $where")
                    diagnostics.add("Page finished: $where")
                    onPageFinished(url ?: "", view?.title ?: "")
                    pageLoaded.complete(true)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""
                    if (url.contains("cloudflare") || url.contains("challenge") ||
                        url.contains("cf-") || url.contains("turnstile")) {
                        // NET-009: host + path only (AGENTS §10 — diagnostics render on screen).
                        diagnostics.add("CF-related request: ${LogUrlSanitizer.url(url)}")
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                // M5: onRenderProcessGone is available in API 26+ but the override
                // signature must exactly match the compiled API. Adding it back requires
                // verifying the exact bytecode signature. For now, WebView destruction
                // is handled by releaseSession() and the error path.
            }

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 100) {
                        diagnostics.add("Progress reached 100%")
                    }
                }
            }
        }
    }

    /**
     * §3.3 (DECISIONS 157): reset the WebView cookie store during a FORCED
     * re-establishment only.
     *
     * Both [waitForCloudflareCompletion] and [isSessionValid] decide "Cloudflare is
     * done / the session is good" purely by the PRESENCE of `cf_clearance`. That
     * cannot distinguish a cookie the WebView just earned from one Cloudflare has
     * already revoked server-side. Left in place, a revoked-but-present cookie makes
     * every forced refresh report success while every real fetch keeps failing
     * 401/403 — recovery loops forever and only clearing app data (which wipes
     * CookieManager) breaks it. Removing the cookie BEFORE the initial load forces a
     * genuine challenge, so the presence check can only pass on a fresh cookie.
     *
     * Mechanism — `removeAllCookies`, not per-cookie expiry (review finding 12,
     * upgraded by live evidence 2026-10-19): a cookie CREATED BY THE WEBVIEW (the
     * real challenge-issued `cf_clearance`) is visible to `getCookie` yet matches
     * NONE of the `setCookie` deletion tuples — host-only, `Domain=`-scoped, or with
     * `Secure`/`SameSite` attributes — while cookies the same API created are deleted
     * fine (verified against a live Stage-E cookie on device REDACTED-SERIAL). A cookie that
     * survives would keep re-arming the presence-only success check, which is the
     * exact false-success loop this method exists to break. `removeAllCookies` is the
     * documented store-wide reset that cannot miss (it covers every storage form and
     * partition). Everything it clears belongs to the AnimePahe flow (this WebView
     * only ever loads the AnimePahe hosts) and the fresh challenge run re-establishes
     * what the flow needs — a new `cf_clearance`, `__ddg1_`, `SERVERID`, … — before
     * any presence check runs.
     *
     * Scope unchanged: only on [establishSession] with `forceRefresh = true` (the
     * user's explicit refresh and the recovery path). No app data is cleared, no
     * native HTTP session is touched, and the healthy session path
     * (`forceRefresh = false`) is unaffected.
     *
     * `internal` (was private) so the instrumented regression test can drive the
     * clearing directly — it needs no network, no WebView page and no challenge;
     * the `forceRefresh` call-site guard itself stays private to [establishSession].
     *
     * Threading: `removeAllCookies` REQUIRES a thread with a running Looper
     * (verified on device 2026-10-19 — it throws `IllegalStateException` from
     * `Dispatchers.IO`), unlike `setCookie` which is thread-agnostic. The reset
     * therefore runs on the main looper; only the durability flush runs on IO.
     */
    internal suspend fun clearStaleClearanceCookie() {
        try {
            withContext(Dispatchers.Main) {
                val cookieManager = CookieManager.getInstance()
                suspendCancellableCoroutine { cont ->
                    cookieManager.removeAllCookies { cont.resume(Unit) }
                }
            }
            withContext(Dispatchers.IO) { CookieManager.getInstance().flush() }
            AppLog.d(TAG, "Cleared stale cf_clearance before forced re-establishment")
        } catch (e: CancellationException) {
            // AQR-019: a cancelled reset must propagate (NET-001 contract) — the
            // establishSession CE arm above restores the session state itself.
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to clear cf_clearance", e)
        }
    }

    private suspend fun waitForCloudflareCompletion(diagnostics: MutableList<String>): Boolean {
        var cfCompleted = false

        for (cycle in 1..MAX_CF_WAIT_CYCLES) {
            delay(CF_CHECK_DELAY_MS)

            // NET-013: same blocking read as above — keep it off the main looper
            // (the poll loop runs up to MAX_CF_WAIT_CYCLES × CF_CHECK_DELAY_MS).
            val allCookies = withContext(Dispatchers.IO) {
                val cookieManager = CookieManager.getInstance()
                val cookies1 = cookieManager.getCookie("https://animepahe.com") ?: ""
                val cookies2 = cookieManager.getCookie("https://animepahe.pw") ?: ""
                "$cookies1 $cookies2"
            }

            if (allCookies.contains("cf_clearance")) {
                cfCompleted = true
                diagnostics.add("Cloudflare challenge completed (cycle $cycle)")
                break
            }

            diagnostics.add("Cloudflare check cycle $cycle: no cf_clearance yet")
        }

        return cfCompleted
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun performPageFetch(
        url: String,
        waitForCloudflare: Boolean,
        timeoutMs: Long,
        contentType: WebViewRequestBridge.ContentType,
        extraHeaders: Map<String, String> = emptyMap()
    ): CloudflareSessionProvider.PageResult {
        val diagnostics = mutableListOf<String>()
        val pageLoaded = CompletableDeferred<Boolean>()
        // JSON capture state: every callback stores the LATEST body in capturedJsonBody;
        // jsonCaptureArrived only records that a capture callback has arrived so the
        // post-Cloudflare-wait read can be bounded. Latest-write-before-read — the same
        // effective capture semantics as capturedHtml below.
        val jsonCaptureArrived = CompletableDeferred<Unit>()
        var capturedJsonBody: String? = null
        var finalUrl = ""
        var pageTitle = ""
        // CRITICAL: Capture HTML in onPageFinished BEFORE Cloudflare redirects.
        // Cloudflare challenge pages redirect via JavaScript, which triggers
        // another onPageFinished. If we extract HTML after redirects, we get
        // the redirect target page, not the original content.
        var capturedHtml: String? = null
        // Final-audit M2: the REAL main-frame HTTP status, reported by
        // `onReceivedHttpError` below. `0` is the "WebView reported no HTTP
        // error" sentinel — never a claimed 200 and never a claimed 0 status.
        // Latest-write, exactly like capturedHtml: a challenge/redirect chain
        // keeps the status of the LAST main-frame response.
        var capturedStatusCode = 0
        // Final-audit M3: hoisted OUT of the try so the exception path can put
        // the temporary capture client back. A WebView may hold exactly one
        // client, and this WebView is application-scoped — see the catch block.
        var sessionWebView: WebView? = null
        var clientToRestore: WebViewClient? = null

        return try {
            // H2: Get existing WebView — do NOT create a fallback.
            // If webView is null after ensureSession(), the session lifecycle is broken.
            val wv = webView
            if (wv == null) {
                diagnostics.add("WebView not available after session establishment")
                return CloudflareSessionProvider.PageResult(
                    success = false,
                    html = null,
                    json = null,
                    finalUrl = "",
                    statusCode = 0,
                    error = "WebView not available after session establishment",
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = diagnostics
                )
            }

            // Set up a temporary WebViewClient to capture content on page load
            sessionWebView = wv
            val originalClient = wv.webViewClient
            clientToRestore = originalClient
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // NET-009: host + path only — diagnostics render on screen.
                    val where = LogUrlSanitizer.url(url)
                    AppLog.d(TAG, "Fetch page started: $where")
                    diagnostics.add("Page started: $where")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val where = LogUrlSanitizer.url(url)
                    AppLog.d(TAG, "Fetch page finished: $where")
                    diagnostics.add("Page finished: $where")
                    finalUrl = url ?: ""
                    pageTitle = view?.title ?: ""
                    pageLoaded.complete(true)

                    // CRITICAL: Capture content NOW, before Cloudflare redirects.
                    // evaluateJavascript runs synchronously on the main thread.
                    if (contentType == WebViewRequestBridge.ContentType.JSON) {
                        // JSON request: capture the raw response body rendered by the WebView
                        // instead of the DOM serialization. Every callback overwrites
                        // capturedJsonBody, so a redirect/challenge chain (multiple
                        // onPageFinished cycles) resolves to the latest capture available
                        // when the body is read after the Cloudflare wait — matching the
                        // effective capture semantics of the HTML path below. The signal
                        // only records that a callback arrived (for the bounded read).
                        view?.evaluateJavascript(JSON_BODY_EXTRACTION_JS) { value ->
                            val body = decodeJsStringLiteral(value)
                            capturedJsonBody = body
                            jsonCaptureArrived.complete(Unit)
                            AppLog.d(TAG, "Captured JSON body in onPageFinished: ${body.length} chars")
                        }
                    } else {
                        view?.evaluateJavascript(
                            HTML_CAPTURE_JS
                        ) { value ->
                            capturedHtml = decodeJsonString(value)
                            AppLog.d(TAG, "Captured HTML in onPageFinished: ${capturedHtml?.length ?: 0} chars")
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    // Final-audit M2: the only place a REAL HTTP status becomes
                    // available through WebView (API 23+, minSdk 32). ONLY the
                    // main frame counts — sub-resource 404s are normal on a page
                    // and must not restate the page's own status. WebView calls
                    // this for 4xx/5xx, so an untouched `capturedStatusCode == 0`
                    // correctly means "no HTTP error was reported".
                    if (request?.isForMainFrame == true) {
                        val status = errorResponse?.statusCode ?: 0
                        if (status > 0) {
                            capturedStatusCode = status
                            diagnostics.add("Main-frame HTTP status: $status")
                            AppLog.w(TAG, "Fetch page returned HTTP $status")
                        }
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    // 11.5E-4 evidence: the Kwik WAF rejects requests without a
                    // referrer (measured against the live host), so record WHETHER
                    // the Referer header reached the request. Header values and
                    // cookies are never recorded (AGENTS §10).
                    if (request?.url?.toString()?.contains(KWIK_HOST_MARKER) == true) {
                        val referrerPresent =
                            request.requestHeaders.containsKey(REFERRER_HEADER)
                        diagnostics.add("Kwik request referrer present: $referrerPresent")
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            // Navigate to the URL. Caller-supplied headers apply to THIS top-level
            // navigation only (WebView semantics) — empty for AnimePahe requests.
            // NET-009: host + path only in diagnostics (AGENTS §10).
            diagnostics.add("Navigating to ${LogUrlSanitizer.url(url)}")
            if (extraHeaders.isNotEmpty()) {
                // Header NAMES only — never values (AGENTS §10).
                diagnostics.add("Extra headers: ${extraHeaders.keys.sorted()}")
                wv.loadUrl(url, extraHeaders)
            } else {
                wv.loadUrl(url)
            }

            // Wait for page load
            val loadResult = withTimeoutOrNull(timeoutMs) {
                pageLoaded.await()
            }

            if (loadResult == null) {
                diagnostics.add("Page load timed out after ${timeoutMs}ms")
                wv.webViewClient = originalClient
                return CloudflareSessionProvider.PageResult(
                    success = false,
                    html = null,
                    json = null,
                    finalUrl = finalUrl,
                    statusCode = 0,
                    error = "Page load timed out",
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = diagnostics
                )
            }

            // Wait for Cloudflare if requested
            if (waitForCloudflare) {
                val cfCompleted = waitForCloudflareCompletion(diagnostics)
                if (!cfCompleted) {
                    diagnostics.add("Cloudflare challenge not completed")
                }
            }

            // Use the HTML captured in onPageFinished (before redirects)
            val htmlContent = capturedHtml ?: ""

            // Restore original WebViewClient
            wv.webViewClient = originalClient

            if (contentType == WebViewRequestBridge.ContentType.JSON) {
                // JSON request: expose the raw response body, not the DOM serialization.
                // Bounded wait for the first capture callback (it may arrive after the
                // Cloudflare wait), then read the LATEST captured body: with a
                // challenge/redirect, the last onPageFinished capture wins — the same
                // latest-write-before-read behavior as the HTML path. No callback within
                // the bound leaves the body null → empty content → existing failure path.
                withTimeoutOrNull(JSON_CAPTURE_TIMEOUT_MS) { jsonCaptureArrived.await() }
                val jsonContent = capturedJsonBody ?: ""
                diagnostics.add("Page JSON content: ${jsonContent.length} chars")
                // Final-audit M2: the reported status is the REAL main-frame
                // status (0 = WebView reported no HTTP error). A 4xx/5xx body is
                // NOT a successful fetch even when it is non-empty — a Cloudflare
                // challenge page is exactly that case — so it fails with the
                // status in the message instead of passing content along as OK.
                val fetchedStatus = capturedStatusCode
                val statusAccepted = fetchedStatus == 0 || fetchedStatus in 200..299
                // SPEC §6.9-6.10 (final-audit H1): a refused main-frame status
                // on a finished load = expired/invalid session credentials →
                // prompt (no-op for every other status).
                SessionRefreshSignal.reportMainFrameStatus(fetchedStatus)

                return CloudflareSessionProvider.PageResult(
                    success = statusAccepted && jsonContent.isNotEmpty(),
                    html = null,
                    json = if (jsonContent.isNotEmpty()) jsonContent else null,
                    finalUrl = finalUrl,
                    statusCode = fetchedStatus,
                    error = when {
                        !statusAccepted -> "HTTP $fetchedStatus"
                        jsonContent.isEmpty() -> "Page content is empty"
                        else -> null
                    },
                    cloudflareChallengeDetected = false,
                    // NET-010: was a hardcoded `true`. A 4xx/5xx main-frame status
                    // is exactly the UNPASSED Cloudflare case this function's own
                    // comment above describes, so the flag now follows the real
                    // status (AGENTS §11: never claim Cloudflare success). The only
                    // behavioural reader — the bridge's `cloudflareHandled` — reads
                    // it solely on the success branch, where statusAccepted is true
                    // by definition, so that path is unchanged; failed fetches and
                    // the validation diagnostics now report the truth.
                    cloudflareChallengeCompleted = statusAccepted,
                    diagnostics = diagnostics
                )
            }

            diagnostics.add("Page content: ${htmlContent.length} chars")
            // Final-audit M2 — same rule as the JSON path above: the real
            // main-frame status (0 = no HTTP error reported) decides success,
            // so a non-empty 403/503 body is a failure carrying its status.
            val fetchedStatus = capturedStatusCode
            val statusAccepted = fetchedStatus == 0 || fetchedStatus in 200..299
            // SPEC §6.9-6.10 (final-audit H1): same rule as the JSON path above.
            SessionRefreshSignal.reportMainFrameStatus(fetchedStatus)

            CloudflareSessionProvider.PageResult(
                success = statusAccepted && htmlContent.isNotEmpty(),
                html = if (htmlContent.isNotEmpty()) htmlContent else null,
                json = null,
                finalUrl = finalUrl,
                statusCode = fetchedStatus,
                error = when {
                    !statusAccepted -> "HTTP $fetchedStatus"
                    htmlContent.isEmpty() -> "Page content is empty"
                    else -> null
                },
                cloudflareChallengeDetected = false,
                // NET-010: follows the real status instead of a hardcoded `true`
                // (see the JSON path above — a challenge page that returned 403/503
                // has NOT completed a challenge).
                cloudflareChallengeCompleted = statusAccepted,
                diagnostics = diagnostics
            )
        } catch (e: CancellationException) {
            // NET-001 (AQR-001: this arm must come FIRST — CancellationException is
            // an Exception, so a generic arm above it made it unreachable dead code
            // that logged abandonment as an ERROR "Page fetch failed"). A cancelled
            // page fetch must still restore the pre-fetch WebViewClient (M3 hygiene —
            // the shared app-scoped WebView must not keep the throwaway capture
            // client), THEN propagate the cancellation so the caller's retry loop
            // does not treat abandonment as a transient failure.
            val restoreTo = clientToRestore
            val target = sessionWebView
            if (restoreTo != null && target != null) {
                target.webViewClient = restoreTo
                diagnostics.add("WebViewClient restored after cancellation")
            }
            AppLog.d(TAG, "Page fetch cancelled")
            throw e
        } catch (e: Exception) {
            // Final-audit M3: the NORMAL path (below) and the page-load TIMEOUT
            // path both restore the pre-fetch WebViewClient, but an exception
            // thrown between install and restore used to return WITHOUT it. The
            // shared, application-scoped WebView then kept running this
            // throwaway capture client — completing page loads and capturing
            // HTML for every later navigation — instead of the session's own
            // client. Restore first, then report the failure.
            val restoreTo = clientToRestore
            val target = sessionWebView
            if (restoreTo != null && target != null) {
                target.webViewClient = restoreTo
                diagnostics.add("WebViewClient restored after failure")
            }
            AppLog.e(TAG, "Page fetch failed", e)
            // NET-009: the message travels to the resolver's release-emitted
            // ERROR line and the persisted errorMessage — sanitize any URL it
            // quotes before it leaves this class.
            diagnostics.add("Exception: ${LogUrlSanitizer.message(e.message)}")
            CloudflareSessionProvider.PageResult(
                success = false,
                html = null,
                json = null,
                finalUrl = finalUrl,
                statusCode = 0,
                error = e.message?.let { LogUrlSanitizer.message(it) },
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = false,
                diagnostics = diagnostics
            )
        }
    }

    /**
     * Decode a JSON-encoded string returned by evaluateJavascript.
     *
     * evaluateJavascript returns its result as a JSON string literal, meaning:
     * - The entire string is wrapped in double quotes
     * - Special characters are escaped: \n, \t, \", \\, \/
     * - HTML characters are Unicode-escaped: \u003C (<), \u003E (>), \u0026 (&)
     *
     * This method properly decodes all these escapes to produce the raw string.
     *
     * Implementation note (NET-003): decoding is delegated to
     * [decodeJsStringLiteral], which reconstructs the body exactly through the
     * JSON parser — the previous manual unescaper applied `\n`/`\t`/… before `\\`
     * and could corrupt a literal backslash-then-letter in the serialized DOM.
     */
    private fun decodeJsonString(value: String?): String = decodeJsStringLiteral(value)

    /**
     * Destroy the WebView instance. Must be called on the main thread.
     * All WebView lifecycle methods (stopLoading, destroy) must execute on the
     * thread that created the WebView.
     */
    private fun destroyWebView() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
    }
}
