package com.pahebatcher.app.domain.hls

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for HlsTextResult and HlsBytesResult typed results.
 *
 *
 * Verifies:
 * - Success contains correct data
 * - HttpError preserves HTTP status codes
 * - NetworkError preserves error messages
 * - Permanent errors (403/404) are distinguishable from transient (5xx)
 * - ByteArray equality works correctly for HlsBytesResult.Success
 */
class HlsResultClassificationTest {

    @Test
    fun hlsTextResult_success_contains_content() {
        val result = HlsTextResult.Success("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000")
        assertTrue(result is HlsTextResult.Success)
        assertEquals("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000", (result as HlsTextResult.Success).content)
    }

    @Test
    fun hlsTextResult_httpError_preserves_code() {
        val result = HlsTextResult.HttpError(403, "https://example.com/playlist.m3u8")
        assertTrue(result is HlsTextResult.HttpError)
        assertEquals(403, (result as HlsTextResult.HttpError).code)
        assertEquals("https://example.com/playlist.m3u8", result.url)
    }

    @Test
    fun hlsTextResult_networkError_preserves_message() {
        val result = HlsTextResult.NetworkError("Connection timed out", "https://example.com/playlist.m3u8")
        assertTrue(result is HlsTextResult.NetworkError)
        assertEquals("Connection timed out", (result as HlsTextResult.NetworkError).message)
        assertEquals("https://example.com/playlist.m3u8", result.url)
    }

    @Test
    fun hlsBytesResult_success_contains_bytes() {
        val data = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val result = HlsBytesResult.Success(data)
        assertTrue(result is HlsBytesResult.Success)
        assertArrayEquals(data, (result as HlsBytesResult.Success).bytes)
    }

    @Test
    fun hlsBytesResult_success_equality() {
        val data1 = byteArrayOf(0x00, 0x01, 0x02)
        val data2 = byteArrayOf(0x00, 0x01, 0x02)
        val data3 = byteArrayOf(0x00, 0x01, 0x03)

        val result1 = HlsBytesResult.Success(data1)
        val result2 = HlsBytesResult.Success(data2)
        val result3 = HlsBytesResult.Success(data3)

        assertEquals(result1, result2) // Same content
        assertNotEquals(result1, result3) // Different content
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun hlsBytesResult_httpError_preserves_code() {
        val result = HlsBytesResult.HttpError(404, "https://example.com/segment.ts")
        assertTrue(result is HlsBytesResult.HttpError)
        assertEquals(404, (result as HlsBytesResult.HttpError).code)
        assertEquals("https://example.com/segment.ts", result.url)
    }

    @Test
    fun hlsBytesResult_networkError_preserves_message() {
        val result = HlsBytesResult.NetworkError("DNS resolution failed", "https://example.com/key.bin")
        assertTrue(result is HlsBytesResult.NetworkError)
        assertEquals("DNS resolution failed", (result as HlsBytesResult.NetworkError).message)
    }

    @Test
    fun permanent_errors_are_403_and_404() {
        // These should NOT be retried
        val error403 = HlsTextResult.HttpError(403, "url")
        val error404 = HlsTextResult.HttpError(404, "url")
        val error400 = HlsTextResult.HttpError(400, "url")
        val error500 = HlsTextResult.HttpError(500, "url")
        val error503 = HlsTextResult.HttpError(503, "url")

        // Permanent: 403, 404
        assertTrue(isPermanentError(error403))
        assertTrue(isPermanentError(error404))

        // Not permanent: 400, 500, 503
        assertFalse(isPermanentError(error400))
        assertFalse(isPermanentError(error500))
        assertFalse(isPermanentError(error503))
    }

    @Test
    fun network_errors_are_transient() {
        val networkError = HlsTextResult.NetworkError("Timeout", "url")
        val networkError2 = HlsBytesResult.NetworkError("DNS failed", "url")

        assertFalse(isPermanentError(networkError))
        assertFalse(isPermanentError(networkError2))
    }

    /**
     * Helper to classify errors (mirrors EpisodeDownloader logic).
     */
    private fun isPermanentError(result: Any): Boolean {
        return when (result) {
            is HlsTextResult.HttpError -> result.code == 403 || result.code == 404
            is HlsBytesResult.HttpError -> result.code == 403 || result.code == 404
            else -> false
        }
    }
}
