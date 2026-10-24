package com.pahebatcher.app.domain.session

import com.pahebatcher.app.data.session.AndroidWebViewRequestBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AQR-017 / AQR-051(a) — NET-001 regression tests for the bridge's
 * `CancellationException` arm (`AndroidWebViewRequestBridge.fetchExclusively`).
 *
 * A cancelled caller (aborted scan/watchlist/download) must STOP the bridge
 * immediately: no second retry attempt, no invalidate + force re-establishment
 * of the ONE shared WebView session, and no spurious user prompt — exactly the
 * damage NET-001 described when that arm swallowed the cancellation into a
 * normal failure result that kept driving the loop.
 *
 * Pure JVM against a fake [CloudflareSessionProvider] — no WebView, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidWebViewRequestBridgeCancellationTest {

    private val config = WebViewRequestBridge.RequestConfig(
        url = "https://animepahe.com/api?m=search&q=test",
        maxRetries = 1,
    )

    /** Ready session whose fetchPage always reports "the caller cancelled". */
    private class CancelledFetchProvider : CloudflareSessionProvider {
        var fetchPageCalls = 0
            private set
        var invalidateCalls = 0
        var forcedRefreshCalls = 0
        private var state = CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun currentState() = state

        override fun getSessionInfo() = CloudflareSessionProvider.SessionInfo(
            state = state,
            userAgent = "TestUA",
            cloudflareCompleted = true,
            finalUrl = "",
            isReady = true,
            lastValidatedAt = 0L,
            cookieCount = 0,
            activeHost = "animepahe.local",
        )

        override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo {
            if (forceRefresh) forcedRefreshCalls++
            return getSessionInfo()
        }

        override suspend fun isSessionValid() = true

        override fun invalidateSession() {
            invalidateCalls++
            state = CloudflareSessionProvider.SessionState.EXPIRED
        }

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>,
        ): CloudflareSessionProvider.PageResult {
            fetchPageCalls++
            throw CancellationException("caller abandoned the work")
        }

        override fun releaseSession() {
            state = CloudflareSessionProvider.SessionState.NOT_STARTED
        }
    }

    @Test
    fun `a cancelled fetch propagates instead of driving the retry loop`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        val provider = CancelledFetchProvider()
        val bridge = AndroidWebViewRequestBridge(provider)

        try {
            bridge.fetch(config)
            fail("cancellation must propagate out of the bridge (NET-001)")
        } catch (e: CancellationException) {
            // expected — the arm rethrows instead of converting to a result
        }
        runCurrent()

        assertEquals(
            "no second attempt after cancellation (a swallowed CE ran " +
                "attempt 2: invalidate + force re-establish + fetch again)",
            1,
            provider.fetchPageCalls,
        )
        assertEquals("the shared session must not be invalidated", 0, provider.invalidateCalls)
        assertEquals(
            "no forced re-establishment for abandoned work",
            0,
            provider.forcedRefreshCalls,
        )
        assertTrue("no spurious user prompt, got: $received", received.isEmpty())
        collector.cancel()
    }
}
