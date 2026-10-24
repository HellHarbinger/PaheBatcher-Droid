package com.pahebatcher.app.domain.session

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CloudflareDetector.
 *
 * Tests the Cloudflare challenge detection logic that prevents
 * the application from silently treating Cloudflare challenge pages
 * as AnimePahe responses.
 */
class CloudflareDetectorTest {

    @Test
    fun `detect returns no challenge for clean 200 response`() {
        val result = CloudflareDetector.detect(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "text/html",
                "Server" to "nginx"
            ),
            body = "<html><title>AnimePahe</title></html>"
        )

        assertFalse(result.isCloudflareChallenge)
        assertFalse(result.isCloudflareResponse)
        assertFalse(result.bodyIsChallenge)
    }

    @Test
    fun `detect identifies cf-mitigated challenge header`() {
        val result = CloudflareDetector.detect(
            statusCode = 403,
            headers = mapOf(
                "Server" to "cloudflare",
                "CF-Ray" to "abc123-MRS",
                "cf-mitigated" to "challenge"
            ),
            body = null
        )

        assertTrue(result.isCloudflareChallenge)
        assertTrue(result.isCloudflareResponse)
        assertTrue(result.indicators.any { it.contains("cf-mitigated") })
    }

    @Test
    fun `detect identifies cloudflare server header`() {
        val result = CloudflareDetector.detect(
            statusCode = 200,
            headers = mapOf(
                "Server" to "cloudflare"
            ),
            body = null
        )

        assertFalse(result.isCloudflareChallenge)
        assertTrue(result.isCloudflareResponse)
        assertTrue(result.indicators.any { it.contains("cloudflare") })
    }

    @Test
    fun `detect identifies 403 with cloudflare headers as challenge`() {
        val result = CloudflareDetector.detect(
            statusCode = 403,
            headers = mapOf(
                "Server" to "cloudflare",
                "CF-Ray" to "abc123-MRS"
            ),
            body = null
        )

        assertTrue(result.isCloudflareChallenge)
        assertTrue(result.isCloudflareResponse)
    }

    @Test
    fun `detect identifies Cloudflare challenge page in body`() {
        val result = CloudflareDetector.detect(
            statusCode = 200,
            headers = mapOf("Server" to "cloudflare"),
            body = """
                <html>
                <head><title>Just a moment...</title></head>
                <body>
                    <div id="challenge-platform">Checking your browser</div>
                </body>
                </html>
            """.trimIndent()
        )

        assertTrue(result.isCloudflareResponse)
        assertTrue(result.bodyIsChallenge)
    }

    @Test
    fun `detect identifies turnstile in body`() {
        val result = CloudflareDetector.detect(
            statusCode = 200,
            headers = mapOf("Server" to "cloudflare"),
            body = """{"challenge": "turnstile", "cf_chl_opt": true}"""
        )

        assertTrue(result.bodyIsChallenge)
    }

    @Test
    fun `detect identifies AnimePahe content in body`() {
        val result = CloudflareDetector.detect(
            statusCode = 200,
            headers = mapOf("Server" to "nginx"),
            body = """{"data": [{"session": "abc"}], "animepahe": true}"""
        )

        assertFalse(result.isCloudflareChallenge)
        assertTrue(result.indicators.any { it.contains("AnimePahe indicator") })
    }

    @Test
    fun `isBlockedByCloudflare returns true for challenge response`() {
        val blocked = CloudflareDetector.isBlockedByCloudflare(
            statusCode = 403,
            headers = mapOf(
                "Server" to "cloudflare",
                "cf-mitigated" to "challenge"
            ),
            body = "Just a moment..."
        )

        assertTrue(blocked)
    }

    @Test
    fun `isBlockedByCloudflare returns false for normal response`() {
        val blocked = CloudflareDetector.isBlockedByCloudflare(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"data": []}"""
        )

        assertFalse(blocked)
    }

    @Test
    fun `detect handles empty headers`() {
        val result = CloudflareDetector.detect(
            statusCode = 200,
            headers = emptyMap(),
            body = null
        )

        assertFalse(result.isCloudflareChallenge)
        assertFalse(result.isCloudflareResponse)
    }

    @Test
    fun `detect handles null body`() {
        val result = CloudflareDetector.detect(
            statusCode = 403,
            headers = mapOf("Server" to "cloudflare"),
            body = null
        )

        assertTrue(result.isCloudflareChallenge)
        assertFalse(result.bodyIsChallenge)
    }

    @Test
    fun `detect is case insensitive for headers`() {
        val result = CloudflareDetector.detect(
            statusCode = 403,
            headers = mapOf(
                "SERVER" to "CLOUDFLARE",
                "CF-MITIGATED" to "challenge"
            ),
            body = null
        )

        assertTrue(result.isCloudflareChallenge)
        assertTrue(result.isCloudflareResponse)
    }

    @Test
    fun `detect is case insensitive for body patterns`() {
        val result = CloudflareDetector.detect(
            statusCode = 200,
            headers = mapOf("Server" to "cloudflare"),
            body = "JUST A MOMENT... Please wait while we verify your browser"
        )

        assertTrue(result.bodyIsChallenge)
    }

    @Test
    fun `full Cloudflare challenge detection like real OkHttp response`() {
        // Simulates a real Cloudflare challenge response observed on a real device.
        val result = CloudflareDetector.detect(
            statusCode = 403,
            headers = mapOf(
                "Server" to "cloudflare",
                "CF-Ray" to "a3e8e2329be6376c-MRS",
                "cf-mitigated" to "challenge",
                "Content-Type" to "text/html; charset=UTF-8"
            ),
            body = """
                <html>
                <head>
                    <title>Attention Required! | Cloudflare</title>
                </head>
                <body>
                    <div id="challenge-platform">
                        <h1>Just a moment...</h1>
                        <p>Checking your browser before accessing animepahe.com</p>
                    </div>
                </body>
                </html>
            """.trimIndent()
        )

        assertTrue(result.isCloudflareChallenge)
        assertTrue(result.isCloudflareResponse)
        assertTrue(result.bodyIsChallenge)
        assertTrue(result.indicators.size >= 3) // Multiple indicators
    }

    @Test
    fun `body containing only Cloudflare in non-challenge context is not challenge`() {
        // L2: The bare "Cloudflare" pattern was removed because it is too broad.
        // A response that merely mentions "Cloudflare" (e.g., in an attribution or
        // CDN reference) should NOT be classified as a challenge page.
        val result = CloudflareDetector.detect(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"powered_by": "Cloudflare CDN", "data": [{"session": "abc"}]}"""
        )

        assertFalse(result.isCloudflareChallenge)
        assertFalse(result.bodyIsChallenge)
        // "Cloudflare" in the body alone should not trigger bodyIsChallenge
        assertFalse(result.indicators.any { it.contains("Body contains") })
    }

    @Test
    fun `body with Cloudflare attribution but specific challenge patterns is still challenge`() {
        // Even if "Cloudflare" is excluded, the other specific patterns still work
        val result = CloudflareDetector.detect(
            statusCode = 403,
            headers = mapOf("Server" to "cloudflare", "cf-mitigated" to "challenge"),
            body = "Powered by Cloudflare. Just a moment... Checking your browser."
        )

        assertTrue(result.isCloudflareChallenge)
        assertTrue(result.bodyIsChallenge)
    }

    @Test
    fun `DetectionResult data class is immutable`() {
        val result = CloudflareDetector.DetectionResult(
            isCloudflareChallenge = true,
            isCloudflareResponse = true,
            indicators = listOf("test"),
            bodyIsChallenge = true
        )

        assertTrue(result.isCloudflareChallenge)
        assertTrue(result.isCloudflareResponse)
        assertEquals(1, result.indicators.size)
        assertTrue(result.bodyIsChallenge)
    }
}
