package com.pahebatcher.app.data.stream

import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.WebViewRequestBridge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for KwikResolver — Kwik URL resolution with domain rotation.
 *
 * Tests the Android fallback chain:
 * 1. WebView fetch of primary Kwik URL
 * 2. WebView fetch with domain rotation
 * 3. Returns null if all fail
 */
class KwikResolverTest {

    private lateinit var mockSessionProvider: FakeCloudflareSessionProvider
    private lateinit var resolver: KwikResolver

    @Before
    fun setup() {
        mockSessionProvider = FakeCloudflareSessionProvider()
        resolver = KwikResolver(mockSessionProvider)
    }

    // ── Direct Resolution ────────────────────────────────────────────────

    @Test
    fun `resolve returns M3U8 URL from direct WebView fetch`() = runTest {
        mockSessionProvider.nextPageResult = CloudflareSessionProvider.PageResult(
            success = true,
            html = """
                <html>
                <body>
                <video src="https://cdn.example.com/uwu.m3u8?token=abc123"></video>
                </body>
                </html>
            """.trimIndent(),
            json = null,
            finalUrl = "https://kwik.si/e/abc123",
            statusCode = 200,
            error = null,
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = false,
            diagnostics = emptyList()
        )

        val result = resolver.resolve("https://kwik.si/e/abc123")

        assertNotNull(result)
        assertTrue(result!!.m3u8Url.contains("uwu.m3u8"))
        // 11.5E-4: the source URL is the page the playlist came from, not a guess.
        assertEquals("https://kwik.si/e/abc123", result.sourceUrl)
        assertEquals(1, mockSessionProvider.fetchPageCallCount)
    }

    // ── 11.5E-4 parity: request headers (Python kwik.py `_HEADERS`) ────────

    @Test
    fun `kwik page fetch sends the Python referrer header`() = runTest {
        mockSessionProvider.nextPageResult = CloudflareSessionProvider.PageResult(
            success = true,
            html = """<video src="https://cdn.example.com/uwu.m3u8"></video>""",
            json = null, finalUrl = "https://kwik.cx/e/abc123", statusCode = 200,
            error = null, cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = false, diagnostics = emptyList()
        )

        resolver.resolve("https://kwik.cx/e/abc123")

        val headers = mockSessionProvider.lastExtraHeaders
        // Python sends this referrer on every Kwik request; without it the Kwik
        // WAF answers HTTP 403 (measured in 11.5E-4).
        assertEquals("https://animepahe.com/", headers["Referer"])
        assertEquals(setOf("Referer"), headers.keys)
    }

    @Test
    fun `kwik referrer constants match the Python _HEADERS referer`() {
        assertEquals("https://animepahe.com/", KwikResolver.KWIK_REFERER)
        assertEquals(
            mapOf("Referer" to "https://animepahe.com/"),
            KwikResolver.KWIK_REQUEST_HEADERS
        )
    }

    @Test
    fun `resolve returns null when WebView fetch fails`() = runTest {
        mockSessionProvider.nextPageResult = CloudflareSessionProvider.PageResult(
            success = false,
            html = null,
            json = null,
            finalUrl = "",
            statusCode = 0,
            error = "Connection failed",
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = false,
            diagnostics = emptyList()
        )

        val result = resolver.resolve("https://kwik.si/e/abc123")

        assertNull(result)
    }

    // ── Domain Rotation ──────────────────────────────────────────────────

    @Test
    fun `resolve tries domain rotation when primary fails`() = runTest {
        var callCount = 0
        mockSessionProvider.fetchPageHandler = { url ->
            callCount++
            if (url.contains("kwik.si")) {
                // Primary domain fails
                CloudflareSessionProvider.PageResult(
                    success = false, html = null, json = null,
                    finalUrl = "", statusCode = 0, error = "Failed",
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList()
                )
            } else if (url.contains("kwik.cx")) {
                // First rotation domain succeeds
                CloudflareSessionProvider.PageResult(
                    success = true,
                    html = """<video src="https://cdn.example.com/uwu.m3u8?token=rotated"></video>""",
                    json = null, finalUrl = url, statusCode = 200, error = null,
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList()
                )
            } else {
                CloudflareSessionProvider.PageResult(
                    success = false, html = null, json = null,
                    finalUrl = "", statusCode = 0, error = "Failed",
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList()
                )
            }
        }

        val result = resolver.resolve("https://kwik.si/e/abc123")

        assertNotNull(result)
        assertTrue(result!!.m3u8Url.contains("uwu.m3u8"))
        // The reported source is the ROTATED page that actually produced it —
        // Python `_resolve_kwik()` sets StreamInfo.referer to the winning URL.
        assertTrue(result.sourceUrl.contains("kwik.cx"))
        // Should have tried primary + at least one rotation
        assertTrue(mockSessionProvider.fetchPageCallCount >= 2)
    }

    @Test
    fun `domain rotation drops the query string like Python`() = runTest {
        val requested = mutableListOf<String>()
        mockSessionProvider.fetchPageHandler = { url ->
            requested += url
            if (url.contains("kwik.si")) {
                CloudflareSessionProvider.PageResult(
                    success = false, html = null, json = null,
                    finalUrl = "", statusCode = 0, error = "Failed",
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList()
                )
            } else {
                CloudflareSessionProvider.PageResult(
                    success = true,
                    html = """<video src="https://cdn.example.com/uwu.m3u8"></video>""",
                    json = null, finalUrl = url, statusCode = 200, error = null,
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList()
                )
            }
        }

        val result = resolver.resolve("https://kwik.si/e/abc123?token=secret")

        assertNotNull(result)
        val rotated = requested.first { it.contains("kwik.cx") }
        // Python `_swap_kwik_domain` re-emits only scheme://netloc + path, so the
        // query string is dropped (kwik.py:85-90) — a stale token must not be
        // replayed against the rotated host.
        assertEquals("https://kwik.cx/e/abc123", rotated)
    }

    @Test
    fun `resolve returns null when all domains fail`() = runTest {
        mockSessionProvider.nextPageResult = CloudflareSessionProvider.PageResult(
            success = false, html = null, json = null,
            finalUrl = "", statusCode = 0, error = "Failed",
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = false,
            diagnostics = emptyList()
        )

        val result = resolver.resolve("https://kwik.si/e/abc123")

        assertNull(result)
        // Should have tried primary + all rotation TLDs
        assertTrue(mockSessionProvider.fetchPageCallCount > 1)
    }

    // ── Domain Swap ──────────────────────────────────────────────────────

    @Test
    fun `resolve does not try same TLD in rotation`() = runTest {
        val triedUrls = mutableListOf<String>()
        mockSessionProvider.fetchPageHandler = { url ->
            triedUrls.add(url)
            CloudflareSessionProvider.PageResult(
                success = false, html = null, json = null,
                finalUrl = "", statusCode = 0, error = "Failed",
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = false,
                diagnostics = emptyList()
            )
        }

        resolver.resolve("https://kwik.si/e/abc123")

        // Should not have tried kwik.si twice
        val siCount = triedUrls.count { it.contains("kwik.si") }
        assertEquals(1, siCount)
    }

    // ── NET-001 cancellation (AQR-017) ─────────────────────────────────

    @Test
    fun `a cancelled fetch aborts resolution instead of rotating domains`() = runTest {
        mockSessionProvider.fetchPageHandler = {
            throw kotlinx.coroutines.CancellationException("caller abandoned the work")
        }

        try {
            resolver.resolve("https://kwik.si/e/abc123")
            fail("cancellation must propagate out of resolve (NET-001)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected — a swallowed CE would instead return null and keep
            // rotating through every KWIK_TLD after the caller left
        }

        assertEquals(
            "exactly the one aborted fetch — no domain rotation may follow " +
                "a cancellation (the swallowed-CE defect issued 1 + 6 attempts)",
            1,
            mockSessionProvider.fetchPageCallCount,
        )
    }

    // ── TLD List ─────────────────────────────────────────────────────────

    @Test
    fun `KWIK_TLDS matches Python reference`() {
        val expected = listOf("cx", "gg", "si", "me", "net", "in", "cc")
        assertEquals(expected, KwikResolver.KWIK_TLDS)
    }
}

/**
 * Fake CloudflareSessionProvider for testing.
 */
class FakeCloudflareSessionProvider : CloudflareSessionProvider {
    var nextPageResult: CloudflareSessionProvider.PageResult? = null
    var fetchPageHandler: ((String) -> CloudflareSessionProvider.PageResult)? = null
    var fetchPageCallCount = 0
        private set

    /**
     * Headers of the MOST RECENT fetchPage call (11.5E-4 parity assertions).
     * Values are compared only against the public `Referer` constant — no
     * cookies or session values are involved.
     */
    var lastExtraHeaders: Map<String, String> = emptyMap()
        private set

    override fun currentState(): CloudflareSessionProvider.SessionState =
        CloudflareSessionProvider.SessionState.ESTABLISHED

    override fun getSessionInfo(): CloudflareSessionProvider.SessionInfo =
        CloudflareSessionProvider.SessionInfo(
            state = CloudflareSessionProvider.SessionState.ESTABLISHED,
            userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36",
            cloudflareCompleted = true,
            finalUrl = "https://animepahe.com",
            isReady = true,
            lastValidatedAt = System.currentTimeMillis(),
            cookieCount = 5,
            activeHost = "animepahe.com"
        )

    override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo =
        getSessionInfo()

    override suspend fun isSessionValid(): Boolean = true

    override fun invalidateSession() {}

    override suspend fun fetchPage(
        url: String,
        waitForCloudflare: Boolean,
        timeoutMs: Long,
        contentType: WebViewRequestBridge.ContentType,
        extraHeaders: Map<String, String>
    ): CloudflareSessionProvider.PageResult {
        fetchPageCallCount++
        lastExtraHeaders = extraHeaders
        return fetchPageHandler?.invoke(url)
            ?: nextPageResult
            ?: CloudflareSessionProvider.PageResult(
                success = false, html = null, json = null,
                finalUrl = "", statusCode = 0, error = "No result set",
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = false,
                diagnostics = emptyList()
            )
    }

    override fun releaseSession() {}
}
