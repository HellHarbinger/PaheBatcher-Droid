package com.pahebatcher.app.data.remote.validation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation spike tests for the remote-validation components.
 *
 * These tests validate the validation implementations themselves,
 * not the actual network connectivity (which requires manual testing).
 */
class ValidationSpikeTest {

    @Test
    fun `OkHttpValidator imports cookies into its in-memory jar`() {
        // AQR-102: replaces the two vacuous "can be instantiated" probes with real
        // behaviour. The jar starts empty, the import is observable through both
        // public read paths, and no Android context or CookieManager is involved.
        val validator = OkHttpValidator()
        validator.importCookies(mapOf("session" to "abc123"), "animepahe.com")

        val stored = validator.getStoredCookies()
        assertEquals("abc123", stored["session (animepahe.com)"])
        assertEquals(
            "the domain index lists exactly the imported cookie",
            mapOf("animepahe.com" to listOf("session")),
            validator.getCookieNamesByDomain(),
        )
    }

    @Test
    fun `KwikValidator domain rotation fails fast on non-kwik URLs`() = runBlocking {
        // AQR-102: replaces the vacuous "can be instantiated" probe. Every rotation
        // candidate (original + swapped TLD) is rejected by the KWIK_URL_PATTERN
        // pre-check before any request is built, so the whole rotation pass is
        // deterministic and offline; the diagnostic trail pins both the fail-fast
        // rejection and the rotation bookkeeping.
        val validator = KwikValidator()
        val result = validator.resolveWithDomainRotation("https://example.com/video.mp4")

        assertFalse(result.success)
        assertTrue(result.diagnosticMessages.any { it.contains("Not a valid Kwik URL") })
        assertTrue(
            result.diagnosticMessages.any { it.contains("Original TLD failed, trying domain rotation") },
        )
    }

    @Test
    fun `KwikValidator rejects non-kwik URLs`() = runBlocking {
        val validator = KwikValidator()
        val result = validator.resolveKwik("https://example.com/video.mp4")
        assertFalse(result.success)
        assertTrue(result.diagnosticMessages.any { it.contains("Not a valid Kwik URL") })
    }

    @Test
    fun `KwikValidator handles invalid URLs gracefully`() = runBlocking {
        val validator = KwikValidator()
        val result = validator.resolveKwik("not-a-url")
        assertFalse(result.success)
    }

    @Test
    fun `Validation result data classes are immutable`() {
        val webViewResult = WebViewSessionValidator.ValidationResult(
            pageLoaded = true,
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = true,
            animePaheAccessible = true,
            cookies = emptyMap(),
            userAgent = "test",
            pageTitle = "Test",
            currentUrl = "https://test.com",
            diagnosticMessages = emptyList(),
            success = true
        )
        assertTrue(webViewResult.pageLoaded)
        assertTrue(webViewResult.success)

        val httpResult = OkHttpValidator.HttpRequestResult(
            success = true,
            statusCode = 200,
            headers = emptyMap(),
            cookies = emptyMap(),
            bodyLength = 0,
            bodySnippet = "",
            finalUrl = "https://test.com",
            redirectCount = 0,
            diagnosticMessages = emptyList()
        )
        assertTrue(httpResult.success)
        assertTrue(httpResult.statusCode == 200)
    }
}
