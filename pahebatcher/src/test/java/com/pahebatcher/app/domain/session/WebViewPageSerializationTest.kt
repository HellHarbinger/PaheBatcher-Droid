package com.pahebatcher.app.domain.session

import com.pahebatcher.app.data.session.AndroidWebViewRequestBridge
import com.pahebatcher.app.data.session.WebViewHttpClient
import com.pahebatcher.app.data.stream.FakeKwikResolver
import com.pahebatcher.app.data.stream.StreamResolverImpl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ONE page operation at a time on the shared WebView.
 *
 * `AndroidCloudflareSession.performPageFetch` swaps the capture `WebViewClient`
 * on a single `WebView(applicationContext)` and restores it afterwards. Two page
 * operations that overlap therefore cross-capture content and interleave the
 * client restoration — the reason 11.5E-7 introduced the single-flight gate in
 * `StreamResolverImpl`.
 *
 * Phase 12D adds a second, independent caller of that transport: the background
 * `WatchlistCheckWorker` (Python's scheduled `wl check`) whose scan/search
 * requests go through `WebViewHttpClient` while any screen may be resolving a
 * stream. Production therefore shares ONE [Mutex] between the HTTP bridge and the
 * resolver (`AppContainer.webViewPageLock`).
 *
 * These tests assert the observable property — never two page operations in
 * flight — with an instrumented [CloudflareSessionProvider] that records its own
 * concurrency. Each operation is held inside the fake for 50 ms of (virtual)
 * time, so without the lock the operations really would overlap.
 */
class WebViewPageSerializationTest {

    private val playPageHtml = """
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

    @Test
    fun `two concurrent HTTP requests never drive the shared WebView at once`() = runTest {
        val provider = RecordingSessionProvider(playPageHtml)
        val pageLock = Mutex()
        val client = WebViewHttpClient(AndroidWebViewRequestBridge(provider, pageLock))

        val first = async { client.fetchHtml("https://animepahe.test/anime/one") }
        val second = async { client.fetchHtml("https://animepahe.test/anime/two") }
        awaitAll(first, second)

        // Both requests really ran…
        assertEquals(2, provider.fetches.get())
        // …and never at the same time: max observed concurrency is one.
        assertEquals(1, provider.maxConcurrent.get())
        assertNotNull(first.await())
        assertNotNull(second.await())
    }

    @Test
    fun `an HTTP request and a stream resolution never overlap either`() = runTest {
        val provider = RecordingSessionProvider(playPageHtml)
        val pageLock = Mutex()
        val client = WebViewHttpClient(AndroidWebViewRequestBridge(provider, pageLock))
        // The production composition passes the SAME lock to both components
        // (AppContainer.webViewPageLock) — that sharing is what this asserts.
        val resolver = StreamResolverImpl(provider, FakeKwikResolver(), pageLock)

        val scan = async { client.fetchJson("https://animepahe.test/api?m=release") }
        val resolution = async {
            resolver.resolve(
                playUrl = "https://animepahe.com/play/uuid1/uuid2",
                quality = 1080,
                audio = "jpn",
            )
        }
        awaitAll(scan, resolution)

        // One HTTP page fetch + one play-page fetch (the Kwik step is faked).
        assertEquals(2, provider.fetches.get())
        assertEquals(1, provider.maxConcurrent.get())
        assertNotNull(scan.await())
        assertEquals("https://cdn.example.com/uwu.m3u8", resolution.await().url)
    }

    @Test
    fun `two concurrent stream resolutions stay serialized`() = runTest {
        val provider = RecordingSessionProvider(playPageHtml)
        val resolver = StreamResolverImpl(provider, FakeKwikResolver(), Mutex())

        val first = async {
            resolver.resolve("https://animepahe.com/play/uuid1/uuid2", 1080, "jpn")
        }
        val second = async {
            resolver.resolve("https://animepahe.com/play/uuid1/uuid3", 720, "jpn")
        }
        awaitAll(first, second)

        assertEquals(2, provider.fetches.get())
        assertEquals(1, provider.maxConcurrent.get())
    }
}

/**
 * Session provider that reports its own concurrency instead of a WebView.
 *
 * Every [fetchPage] stays "in flight" for 50 ms of virtual time, which is how an
 * unguarded composition would be caught: the second caller would enter while the
 * first is still inside.
 */
private class RecordingSessionProvider(
    private val playPageHtml: String
) : CloudflareSessionProvider {

    val fetches = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)

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

    override fun releaseSession() {}

    override suspend fun fetchPage(
        url: String,
        waitForCloudflare: Boolean,
        timeoutMs: Long,
        contentType: WebViewRequestBridge.ContentType,
        extraHeaders: Map<String, String>
    ): CloudflareSessionProvider.PageResult {
        val current = inFlight.incrementAndGet()
        maxConcurrent.updateAndGet { existing -> maxOf(existing, current) }
        fetches.incrementAndGet()
        delay(50)
        inFlight.decrementAndGet()

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
}
