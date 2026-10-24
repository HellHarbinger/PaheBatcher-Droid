package com.pahebatcher.app.domain.hls

/**
 * HTTP client interface for HLS operations.
 *
 * Provides two operations needed by Phase 7/10:
 * 1. Text GET for M3U8 playlists → returns HlsTextResult
 * 2. Binary GET for AES-128 keys and segments → returns HlsBytesResult
 *
 * This is a dedicated HLS abstraction, separate from the existing
 * HttpClient (which is designed for AnimePahe API requests).
 *
 * The implementation should support:
 * - Request headers (User-Agent, Referer, Cookies)
 * - Redirects
 * - Timeout handling
 * - Typed error handling (HTTP status codes preserved)
 *
 * Typed results for both text and byte responses.
 */
interface HlsClient {

    /**
     * Request context for HLS HTTP requests.
     * Contains all headers/cookies needed for CDN requests.
     *
     * Constraint 1: Keep request headers/cookies injectable.
     * Do not hard-code assumptions about cookie requirements.
     */
    data class RequestContext(
        val userAgent: String = "",
        val referer: String = "",
        val cookies: Map<String, String> = emptyMap()
    )

    /**
     * Fetch text content (for M3U8 playlists).
     *
     * Returns typed result with HTTP status codes for error classification:
     * - HlsTextResult.Success: Content retrieved successfully
     * - HlsTextResult.HttpError: HTTP error (403/404 = permanent, 5xx = transient)
     * - HlsTextResult.NetworkError: Network-level error (transient)
     *
     * @param url The URL to fetch
     * @param context Request headers/cookies
     * @param timeoutMs Timeout in milliseconds
     * @return Typed result with content or error information
     */
    suspend fun getText(
        url: String,
        context: RequestContext,
        timeoutMs: Long = 30_000L
    ): HlsTextResult

    /**
     * Fetch binary content (for segments and AES keys).
     *
     * Returns typed result with HTTP status codes for error classification:
     * - HlsBytesResult.Success: Bytes retrieved successfully
     * - HlsBytesResult.HttpError: HTTP error (403/404 = permanent, 5xx = transient)
     * - HlsBytesResult.NetworkError: Network-level error (transient)
     *
     * @param url The URL to fetch
     * @param context Request headers/cookies
     * @param timeoutMs Timeout in milliseconds
     * @return Typed result with bytes or error information
     */
    suspend fun getBytes(
        url: String,
        context: RequestContext,
        timeoutMs: Long = 60_000L
    ): HlsBytesResult
}
