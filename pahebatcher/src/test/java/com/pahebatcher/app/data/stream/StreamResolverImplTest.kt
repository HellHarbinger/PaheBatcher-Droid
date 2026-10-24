package com.pahebatcher.app.data.stream

import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.WebViewRequestBridge
import com.pahebatcher.app.domain.stream.StreamResolutionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for StreamResolverImpl — full stream resolution integration.
 *
 * Tests the complete flow:
 * 1. Play page fetch via WebView
 * 2. Resolution button parsing
 * 3. Quality and audio selection
 * 4. Kwik URL resolution
 * 5. StreamInfo construction
 */
class StreamResolverImplTest {

    private lateinit var fakeSessionProvider: FakeStreamSessionProvider
    private lateinit var fakeKwikResolver: FakeKwikResolver
    private lateinit var resolver: StreamResolverImpl

    @Before
    fun setup() {
        fakeSessionProvider = FakeStreamSessionProvider()
        fakeKwikResolver = FakeKwikResolver()
        resolver = StreamResolverImpl(fakeSessionProvider, fakeKwikResolver)
    }

    // ── Successful Resolution ────────────────────────────────────────────

    @Test
    fun `resolve returns StreamInfo with M3U8 URL`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <head><title>Spy x Family Episode 1 - AnimePahe</title></head>
            <body>
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc123" data-resolution="1080" data-audio="jpn">1080p</button>
                <button data-src="https://kwik.si/e/def456" data-resolution="720" data-audio="jpn">720p</button>
            </div>
            </body>
            </html>
        """.trimIndent()

        fakeKwikResolver.m3u8Url = "https://cdn.example.com/uwu.m3u8?token=abc123"

        val result = resolver.resolve(
            playUrl = "https://animepahe.com/play/uuid1/uuid2",
            quality = 1080,
            audio = "jpn"
        )

        assertNotNull(result)
        assertEquals("https://cdn.example.com/uwu.m3u8?token=abc123", result.url)
        assertEquals("jpn", result.audio)
        assertEquals("Spy x Family Episode 1 - AnimePahe", result.title)
        // 11.5E-4 parity fix: Python `_resolve_kwik()` sets referer to the KWIK
        // PAGE that produced the playlist (the Kwik CDN 403s any other referrer),
        // never to the AnimePahe play page.
        assertEquals("https://kwik.si/e/abc123", result.referer)
        // Scoping: the AnimePahe play-page fetch carries NO caller headers — the
        // Kwik referrer is added by KwikResolver for the Kwik host only.
        assertEquals(emptyMap<String, String>(), fakeSessionProvider.lastExtraHeaders)
    }

    @Test
    fun `resolve selects correct quality`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <head><title>Test</title></head>
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/1080" data-resolution="1080" data-audio="jpn">1080p</button>
                <button data-src="https://kwik.si/e/720" data-resolution="720" data-audio="jpn">720p</button>
                <button data-src="https://kwik.si/e/360" data-resolution="360" data-audio="jpn">360p</button>
            </div>
        """.trimIndent()

        fakeKwikResolver.m3u8Url = "https://cdn.example.com/uwu.m3u8"

        val result = resolver.resolve(
            playUrl = "https://animepahe.com/play/uuid1/uuid2",
            quality = 720,
            audio = "jpn"
        )

        assertNotNull(result)
        assertEquals("https://kwik.si/e/720", fakeKwikResolver.lastResolvedUrl)
    }

    @Test
    fun `resolve selects DUB when requested`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/sub1080" data-resolution="1080" data-audio="jpn">1080p</button>
                <button data-src="https://kwik.si/e/dub1080" data-resolution="1080" data-audio="eng">1080p DUB</button>
            </div>
        """.trimIndent()

        fakeKwikResolver.m3u8Url = "https://cdn.example.com/uwu.m3u8"

        val result = resolver.resolve(
            playUrl = "https://animepahe.com/play/uuid1/uuid2",
            quality = 1080,
            audio = "eng"
        )

        assertNotNull(result)
        assertEquals("eng", result.audio)
        assertEquals("https://kwik.si/e/dub1080", fakeKwikResolver.lastResolvedUrl)
    }

    @Test
    fun `menu-less pages pass the same exact audio filter as the menu branch`() = runTest {
        // No #resolutionMenu div: Python fills quality_map from the fallback
        // regex with plain `setdefault` (kwik.py 241-247) — NO audio filtering
        // in that branch — and would serve the eng-labelled 1080p URL to a SUB
        // request. The approved port labels each URL (`eng`/`dub → eng`,
        // `jpn`/`sub → jpn`) and applies the SAME exact filter as the menu
        // branch (deliberate divergence D3), so the eng-labelled entry is
        // invisible to a `jpn` request and the SUB-labelled 720p wins.
        fakeSessionProvider.playPageHtml = """
            <html>
            <head><title>Fallback Episode</title></head>
            <body>
            <a href="https://kwik.si/e/eng1080">Watch 1080p</a>
            <a href="https://kwik.si/e/sub720">Watch 720p</a>
            </body>
            </html>
        """.trimIndent()

        fakeKwikResolver.m3u8Url = "https://cdn.example.com/uwu.m3u8"

        val result = resolver.resolve(
            playUrl = "https://animepahe.com/play/uuid1/uuid2",
            quality = 1080,
            audio = "jpn"
        )

        assertEquals("https://kwik.si/e/sub720", fakeKwikResolver.lastResolvedUrl)
        assertEquals("jpn", result.audio)
        // …and the winning page is the referrer, as in the menu branch.
        assertEquals("https://kwik.si/e/sub720", result.referer)
    }

    @Test
    fun `menu-less pages serve the URL-labelled audio that matches the request`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <head><title>Fallback Episode</title></head>
            <body>
            <a href="https://kwik.si/e/eng1080">Watch 1080p</a>
            <a href="https://kwik.si/e/sub720">Watch 720p</a>
            </body>
            </html>
        """.trimIndent()

        fakeKwikResolver.m3u8Url = "https://cdn.example.com/uwu.m3u8"

        val result = resolver.resolve(
            playUrl = "https://animepahe.com/play/uuid1/uuid2",
            quality = 1080,
            audio = "eng"
        )

        assertEquals("https://kwik.si/e/eng1080", fakeKwikResolver.lastResolvedUrl)
        assertEquals("eng", result.audio)
    }

    @Test
    fun `menu-less pages fail explicitly for an audio no URL label carries`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <head><title>Fallback Episode</title></head>
            <body>
            <a href="https://kwik.si/e/eng1080">Watch 1080p</a>
            <a href="https://kwik.si/e/sub720">Watch 720p</a>
            </body>
            </html>
        """.trimIndent()

        fakeKwikResolver.m3u8Url = "https://cdn.example.com/uwu.m3u8"

        try {
            resolver.resolve(
                playUrl = "https://animepahe.com/play/uuid1/uuid2",
                quality = 1080,
                audio = "kor"
            )
            fail("Expected StreamResolutionException")
        } catch (e: StreamResolutionException) {
            assertTrue(e.message!!.contains("Requested audio 'kor' is not available"))
        }
        assertNull(fakeKwikResolver.lastResolvedUrl)
    }

    // ── Failure Cases ────────────────────────────────────────────────────

    @Test
    fun `resolve throws when play page fetch fails`() = runTest {
        fakeSessionProvider.shouldFail = true

        try {
            resolver.resolve(
                playUrl = "https://animepahe.com/play/uuid1/uuid2",
                quality = 1080,
                audio = "jpn"
            )
            fail("Expected StreamResolutionException")
        } catch (e: StreamResolutionException) {
            assertTrue(e.message!!.contains("Failed to fetch episode page"))
        }
    }

    @Test
    fun `resolve throws when no Kwik links found`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <head><title>Test</title></head>
            <body><p>No video content</p></body>
            </html>
        """.trimIndent()

        try {
            resolver.resolve(
                playUrl = "https://animepahe.com/play/uuid1/uuid2",
                quality = 1080,
                audio = "jpn"
            )
            fail("Expected StreamResolutionException")
        } catch (e: StreamResolutionException) {
            assertTrue(e.message!!.contains("No Kwik link found"))
        }
    }

    @Test
    fun `resolve throws when Kwik resolution fails`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="1080" data-audio="jpn">1080p</button>
            </div>
        """.trimIndent()

        fakeKwikResolver.shouldFail = true

        try {
            resolver.resolve(
                playUrl = "https://animepahe.com/play/uuid1/uuid2",
                quality = 1080,
                audio = "jpn"
            )
            fail("Expected StreamResolutionException")
        } catch (e: StreamResolutionException) {
            assertTrue(e.message!!.contains("Could not resolve Kwik URL"))
        }
    }

    @Test
    fun `a failed Kwik resolution never puts the link's query into the exception message`() = runTest {
        // Finding B, final closure round (DECISIONS 138): this message travels
        // to DownloadRepository's release-emitted ERROR line AND the persisted
        // errorMessage — the production throw site must keep host+path only.
        fakeSessionProvider.playPageHtml = """
            <html>
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc?token=SUPERSECRET" data-resolution="1080" data-audio="jpn">1080p</button>
            </div>
        """.trimIndent()

        fakeKwikResolver.shouldFail = true

        try {
            resolver.resolve(
                playUrl = "https://animepahe.com/play/uuid1/uuid2",
                quality = 1080,
                audio = "jpn"
            )
            fail("Expected StreamResolutionException")
        } catch (e: StreamResolutionException) {
            val message = e.message!!
            assertTrue("host+path must survive for diagnosis: $message", message.contains("kwik.si/e/abc"))
            assertFalse("no query marker: $message", message.contains("?"))
            assertFalse("no query parameter: $message", message.contains("token="))
            assertFalse("no token value: $message", message.contains("SUPERSECRET"))
            assertFalse("no scheme-complete URL: $message", message.contains("https://"))
        }
    }

    // ── Audio-variant Semantics (SPEC §22) ───────────────────────────────

    @Test
    fun `resolve preserves SUB audio when SUB selected`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/sub" data-resolution="1080" data-audio="jpn">1080p</button>
                <button data-src="https://kwik.si/e/dub" data-resolution="1080" data-audio="eng">1080p DUB</button>
            </div>
        """.trimIndent()

        fakeKwikResolver.m3u8Url = "https://cdn.example.com/uwu.m3u8"

        val result = resolver.resolve(
            playUrl = "https://animepahe.com/play/uuid1/uuid2",
            quality = 1080,
            audio = "jpn"
        )

        assertEquals("jpn", result.audio)
        assertEquals("https://kwik.si/e/sub", fakeKwikResolver.lastResolvedUrl)
    }

    @Test
    fun `resolve fails explicitly when the requested audio is unavailable — never substitutes`() = runTest {
        fakeSessionProvider.playPageHtml = """
            <html>
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/sub1080" data-resolution="1080" data-audio="jpn">1080p</button>
                <button data-src="https://kwik.si/e/sub720" data-resolution="720" data-audio="jpn">720p</button>
            </div>
        """.trimIndent()

        fakeKwikResolver.m3u8Url = "https://cdn.example.com/uwu.m3u8"

        // Request DUB while the page only carries SUB: INVERTED behavior
        // (SPEC §22, approved §7) — the reference re-filters to ALL entries
        // (kwik.py:230-232) and serves SUB as if it were the requested audio;
        // the port fails with the recorded reason and never reaches Kwik.
        try {
            resolver.resolve(
                playUrl = "https://animepahe.com/play/uuid1/uuid2",
                quality = 1080,
                audio = "eng"
            )
            fail("Expected StreamResolutionException")
        } catch (e: StreamResolutionException) {
            assertTrue(e.message!!.contains("Requested audio 'eng' is not available"))
        }
        assertNull(
            "the kwik resolver must never be asked for a substitute audio",
            fakeKwikResolver.lastResolvedUrl,
        )
    }
}

/**
 * Fake session provider for StreamResolverImpl tests.
 */
class FakeStreamSessionProvider : CloudflareSessionProvider {
    var playPageHtml: String = ""
    var shouldFail = false

    /** Headers of the most recent fetchPage (11.5E-4 scoping assertion). */
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
        lastExtraHeaders = extraHeaders
        if (shouldFail) {
            return CloudflareSessionProvider.PageResult(
                success = false, html = null, json = null,
                finalUrl = "", statusCode = 0, error = "Connection failed",
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = false,
                diagnostics = emptyList()
            )
        }

        return CloudflareSessionProvider.PageResult(
            success = true,
            html = playPageHtml,
            json = null,
            finalUrl = url,
            statusCode = 200,
            error = null,
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = false,
            diagnostics = emptyList()
        )
    }

    override fun releaseSession() {}
}

/**
 * Fake KwikResolver for StreamResolverImpl tests.
 */
class FakeKwikResolver : KwikResolver(FakeStreamSessionProvider()) {
    var m3u8Url: String = "https://cdn.example.com/uwu.m3u8"
    var shouldFail = false
    var lastResolvedUrl: String? = null
        private set

    override suspend fun resolve(kwikUrl: String): KwikResolution? {
        lastResolvedUrl = kwikUrl
        // Direct (non-rotated) success: the source is the page we were asked for,
        // which is exactly what the production resolver reports.
        return if (shouldFail) null else KwikResolution(m3u8Url, kwikUrl)
    }
}
