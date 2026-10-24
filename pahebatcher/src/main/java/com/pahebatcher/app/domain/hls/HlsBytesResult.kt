package com.pahebatcher.app.domain.hls

/**
 * Typed result for binary HLS responses (segments, AES keys).
 *
 * Preserves HTTP status codes for error classification:
 * - 403/404: Permanent errors (resource unavailable) — should NOT be retried
 * - 5xx: Transient errors — may be retried
 * - Network errors: Transient — may be retried
 *
 *
 */
sealed class HlsBytesResult {
    /**
     * Successful binary response.
     * @param bytes The response body bytes
     */
    data class Success(val bytes: ByteArray) : HlsBytesResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * HTTP error response with status code.
     * @param code HTTP status code (e.g., 403, 404, 500)
     * @param url The request URL
     */
    data class HttpError(val code: Int, val url: String) : HlsBytesResult()

    /**
     * Network-level error (timeout, DNS failure, connection refused, etc.).
     * @param message Error description
     * @param url The request URL
     */
    data class NetworkError(val message: String, val url: String) : HlsBytesResult()
}
