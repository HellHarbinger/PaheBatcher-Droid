package com.pahebatcher.app.domain.session

/**
 * Interface for the WebView request bridge.
 *
 * This defines the mechanism through which application code can request
 * Cloudflare-protected AnimePahe operations through the WebView session.
 *
 * The implementation isolates WebView-specific code from:
 * - Domain models
 * - Repositories
 * - Download engine
 * - Media3
 * - Room
 *
 * The bridge handles:
 * - Ensuring a valid WebView session before each request
 * - Navigating the WebView to the requested URL
 * - Waiting for page load completion
 * - Extracting page content (HTML or JSON)
 * - Handling Cloudflare challenges transparently
 * - Providing retry on session expiry
 */
interface WebViewRequestBridge {

    /**
     * Request type for the bridge.
     */
    enum class ContentType {
        /** Expect HTML response (for parsing with regex/DOM) */
        HTML,
        /** Expect JSON response (for API endpoints) */
        JSON
    }

    /**
     * Configuration for a single request through the bridge.
     */
    data class RequestConfig(
        /** The URL to request */
        val url: String,
        /** Whether this is an API (JSON) or page (HTML) request */
        val contentType: ContentType = ContentType.HTML,
        /** Whether to wait for Cloudflare challenge completion */
        val waitForCloudflare: Boolean = true,
        /** Maximum time to wait for the page to load (ms) */
        val timeoutMs: Long = 30_000L,
        /** Maximum number of retries on session expiry */
        val maxRetries: Int = 1
    )

    /**
     * Result of a bridge request.
     */
    data class BridgeResult(
        /** Whether the request succeeded */
        val success: Boolean,
        /** The response content (HTML or JSON string) */
        val content: String?,
        /** The final URL after any redirects */
        val finalUrl: String,
        /** Error message if the request failed */
        val error: String?,
        /** Whether a Cloudflare challenge was encountered and handled */
        val cloudflareHandled: Boolean,
        /** Whether the session was refreshed during this request */
        val sessionRefreshed: Boolean,
        /** Diagnostic messages (no sensitive data) */
        val diagnostics: List<String>
    )

    /**
     * Fetch a page through the WebView session.
     *
     * This is the primary method for all Cloudflare-protected AnimePahe requests.
     * The implementation will:
     * 1. Ensure a valid session exists (refresh if needed)
     * 2. Navigate the WebView to the requested URL
     * 3. Wait for page load completion
     * 4. Handle any Cloudflare challenges
     * 5. Extract the page content
     * 6. Return the result
     *
     * @param config Request configuration
     * @return BridgeResult with the fetched content
     */
    suspend fun fetch(config: RequestConfig): BridgeResult

    /**
     * Convenience method: fetch HTML content.
     */
    suspend fun fetchHtml(
        url: String,
        timeoutMs: Long = 30_000L
    ): BridgeResult = fetch(
        RequestConfig(
            url = url,
            contentType = ContentType.HTML,
            timeoutMs = timeoutMs
        )
    )

    /**
     * Convenience method: fetch JSON content.
     */
    suspend fun fetchJson(
        url: String,
        timeoutMs: Long = 30_000L
    ): BridgeResult = fetch(
        RequestConfig(
            url = url,
            contentType = ContentType.JSON,
            timeoutMs = timeoutMs
        )
    )

    /**
     * Get the current session state.
     */
    fun getSessionState(): CloudflareSessionProvider.SessionState

    /**
     * Release the bridge's session resources.
     */
    fun release()
}
