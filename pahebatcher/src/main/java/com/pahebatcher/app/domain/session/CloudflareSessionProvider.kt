package com.pahebatcher.app.domain.session

/**
 * Domain interface representing the browser/session mechanism required by AnimePahe.
 *
 * This interface does NOT expose Android WebView types to the domain layer.
 * It provides only the minimum capabilities required by the rest of the application.
 *
 * The implementation is platform-specific (Android WebView) but the interface
 * remains pure Kotlin for testability and clean architecture.
 *
 * Architecture context:
 * - All Cloudflare-protected AnimePahe requests (search, episodes, play pages) must
 *   go through this mechanism.
 * - OkHttp cannot bypass Cloudflare TLS detection even with transferred cookies.
 * - This is validated on real Android 16 devices.
 */
interface CloudflareSessionProvider {

    /**
     * Current state of the browser session.
     */
    enum class SessionState {
        /** No session established yet */
        NOT_STARTED,
        /** Session is being established (Cloudflare challenge in progress) */
        ESTABLISHING,
        /** Session is valid and ready for requests */
        ESTABLISHED,
        /** Session has expired or is invalid */
        EXPIRED,
        /** Session establishment failed */
        FAILED
    }

    /**
     * Safe session metadata for downstream components.
     * Does NOT expose raw cookies, WebView objects, or other sensitive internals.
     */
    data class SessionInfo(
        /** Current session state */
        val state: SessionState,
        /** The User-Agent string used by the browser session */
        val userAgent: String,
        /** Whether Cloudflare challenge was detected and completed */
        val cloudflareCompleted: Boolean,
        /** The final URL after session establishment (if any) */
        val finalUrl: String,
        /** Whether the session can serve requests */
        val isReady: Boolean,
        /** Timestamp when the session was last validated (epoch millis) */
        val lastValidatedAt: Long,
        /** Sanitized cookie count (NOT raw cookie values) */
        val cookieCount: Int,
        /** The active AnimePahe domain (animepahe.com/pw/org) */
        val activeHost: String
    )

    /**
     * Result of a page request through the WebView session.
     */
    data class PageResult(
        /** Whether the request succeeded */
        val success: Boolean,
        /** HTML content of the page (if fetched as HTML) */
        val html: String?,
        /** JSON content of the page (if fetched as JSON/API endpoint) */
        val json: String?,
        /** The final URL after any redirects */
        val finalUrl: String,
        /** HTTP status code (or 0 if not available) */
        val statusCode: Int,
        /** Error message if the request failed */
        val error: String?,
        /** Whether a Cloudflare challenge was detected during the request */
        val cloudflareChallengeDetected: Boolean,
        /** Whether the Cloudflare challenge was completed successfully */
        val cloudflareChallengeCompleted: Boolean,
        /** Sanitized diagnostic information (no cookies or tokens) */
        val diagnostics: List<String>
    )

    /**
     * Get the current session state without performing any network operations.
     */
    fun currentState(): SessionState

    /**
     * Get current session information.
     * Safe for logging and UI display — no sensitive data exposed.
     */
    fun getSessionInfo(): SessionInfo

    /**
     * Establish or re-establish a valid browser session.
     *
     * If a session is already established and valid, this may return immediately.
     * If the session is expired or not started, a new session will be established.
     *
     * This operation:
     * 1. Creates/reuses a WebView instance
     * 2. Loads the AnimePahe base URL
     * 3. Waits for Cloudflare challenge completion
     * 4. Verifies AnimePahe accessibility
     * 5. Captures session metadata
     *
     * @param forceRefresh If true, destroy existing session and create a new one
     * @return SessionInfo reflecting the new session state
     */
    suspend fun establishSession(forceRefresh: Boolean = false): SessionInfo

    /**
     * Ensure a valid session exists. If not, establish one.
     * Convenience method combining currentState check + establishSession.
     */
    suspend fun ensureSession(): SessionInfo {
        val state = currentState()
        return when (state) {
            SessionState.ESTABLISHED -> getSessionInfo()
            else -> establishSession()
        }
    }

    /**
     * Check if the current session is still valid.
     * May perform a lightweight validation request.
     */
    suspend fun isSessionValid(): Boolean

    /**
     * Mark the current session as expired.
     * Subsequent requests will trigger session re-establishment.
     */
    fun invalidateSession()

    /**
     * Fetch a page through the established browser session.
     *
     * This is the primary mechanism for all Cloudflare-protected AnimePahe requests.
     * The implementation will:
     * 1. Ensure a valid session exists
     * 2. Navigate the WebView to the requested URL
     * 3. Wait for page load / Cloudflare challenge completion
     * 4. Extract the page content (HTML or JSON)
     * 5. Return the result
     *
     * @param url The URL to fetch
     * @param waitForCloudflare Whether to wait for Cloudflare challenge completion
     * @param timeoutMs Maximum time to wait for the page to load
     * @param contentType Whether the caller expects HTML page content or a raw JSON
     *        response body. JSON requests populate [PageResult.json] with the exact
     *        response body text; HTML requests populate [PageResult.html] as before.
     * @param extraHeaders Additional HTTP headers attached to the TOP-LEVEL navigation
     *        of this fetch (the WebView applies them to that request only). Empty by
     *        default: AnimePahe requests must send no caller-supplied headers — the
     *        session owns its cookies/User-Agent (SPEC §6). The Kwik host is the one
     *        caller that legitimately needs a `Referer`, because the Kwik WAF requires
     *        a referrer (Python `extract/kwik.py::_HEADERS`). Header values are never
     *        written to [PageResult.diagnostics] or logs (AGENTS §10).
     * @return PageResult with the fetched content
     */
    suspend fun fetchPage(
        url: String,
        waitForCloudflare: Boolean = true,
        timeoutMs: Long = 30_000L,
        contentType: WebViewRequestBridge.ContentType = WebViewRequestBridge.ContentType.HTML,
        extraHeaders: Map<String, String> = emptyMap()
    ): PageResult

    /**
     * Release all session resources.
     * Call when the session is no longer needed (e.g., app going to background
     * for extended period, or user explicitly clearing session).
     */
    fun releaseSession()
}
