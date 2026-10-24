package com.pahebatcher.app.domain.hls

/**
 * Typed result for text-based HLS responses (M3U8 playlists).
 *
 * Preserves HTTP status codes for error classification:
 * - 403/404: Permanent errors (stream expired) — should NOT be retried
 * - 5xx: Transient errors — may be retried
 * - Network errors: Transient — may be retried
 *
 *
 */
sealed class HlsTextResult {
    /**
     * Successful text response.
     * @param content The M3U8 playlist content
     */
    data class Success(val content: String) : HlsTextResult()

    /**
     * HTTP error response with status code.
     * @param code HTTP status code (e.g., 403, 404, 500)
     * @param url The request URL
     */
    data class HttpError(val code: Int, val url: String) : HlsTextResult()

    /**
     * Network-level error (timeout, DNS failure, connection refused, etc.).
     * @param message Error description
     * @param url The request URL
     */
    data class NetworkError(val message: String, val url: String) : HlsTextResult()
}
