package com.pahebatcher.app.domain.session

import com.pahebatcher.app.data.session.AndroidWebViewRequestBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2.1 Decisions 1 + 2 (findings AQR-014 and NET-004) and AQR-025 — the
 * bridge's recovery ownership and narrow-invalidation contract:
 *
 *  - A transient failure (5xx, timeout, transport exception) retries against
 *    the SAME valid session: zero `invalidateSession`, zero forced
 *    re-establishments (NET-004 — a failed fetch must not store-wide-wipe the
 *    Cloudflare session).
 *  - A refused main-frame status (401/403) triggers EXACTLY ONE recovery run,
 *    in a pinned order — not the historical two (AQR-014: bridge retry +
 *    quiet-refresh prompt for the same failure).
 *  - Main-frame status reports raised while the bridge fetches are suppressed
 *    (the bridge owns recovery); direct provider callers — KwikResolver, audio
 *    probe, scan — keep their signal untouched.
 *  - Exception diagnostics are sanitized (AQR-025, AGENTS §10).
 *
 * Cancellation during the retry loop is pinned separately by
 * [AndroidWebViewRequestBridgeCancellationTest]; the "not ready"/"refresh
 * failed" prompt sites by [AndroidWebViewRequestBridgeSessionPromptTest].
 *
 * Pure JVM against fake [CloudflareSessionProvider]s — no WebView, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidWebViewRequestBridgeRecoveryTest {

    private sealed interface Outcome {
        data class Page(val statusCode: Int, val success: Boolean, val error: String?) : Outcome
        data class Fault(val message: String) : Outcome
    }

    /**
     * Ready-on-demand provider with a scripted fetch sequence and an event log
     * so tests can assert the NUMBER and the ORDER of recovery attempts.
     */
    private class ScriptedProvider(
        private val outcomes: List<Outcome>,
        private val emitMainFrameStatusOnFetch: Boolean = false,
    ) : CloudflareSessionProvider {
        val events = mutableListOf<String>()
        var fetchPageCalls = 0
            private set
        var invalidateCalls = 0
            private set
        var forcedRefreshCalls = 0
            private set
        private var state = CloudflareSessionProvider.SessionState.NOT_STARTED

        override fun currentState() = state

        override fun getSessionInfo() = CloudflareSessionProvider.SessionInfo(
            state = state,
            userAgent = "TestUA",
            cloudflareCompleted = state == CloudflareSessionProvider.SessionState.ESTABLISHED,
            finalUrl = "",
            isReady = state == CloudflareSessionProvider.SessionState.ESTABLISHED,
            lastValidatedAt = 0L,
            cookieCount = 0,
            activeHost = "animepahe.local",
        )

        override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo {
            if (forceRefresh) {
                forcedRefreshCalls++
                events += "establish(force)"
            } else {
                events += "establish"
            }
            state = CloudflareSessionProvider.SessionState.ESTABLISHED
            return getSessionInfo()
        }

        override suspend fun isSessionValid() =
            state == CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun invalidateSession() {
            invalidateCalls++
            events += "invalidate"
            state = CloudflareSessionProvider.SessionState.EXPIRED
        }

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>,
        ): CloudflareSessionProvider.PageResult {
            events += "fetch"
            val outcome = outcomes[minOf(fetchPageCalls, outcomes.lastIndex)]
            fetchPageCalls++
            if (emitMainFrameStatusOnFetch && outcome is Outcome.Page &&
                (outcome.statusCode == 401 || outcome.statusCode == 403)
            ) {
                // Simulates AndroidCloudflareSession reporting the REAL main-frame
                // status during result construction — i.e. inside the bridge's window.
                SessionRefreshSignal.reportMainFrameStatus(outcome.statusCode)
            }
            return when (outcome) {
                is Outcome.Fault -> throw IllegalStateException(outcome.message)
                is Outcome.Page -> CloudflareSessionProvider.PageResult(
                    success = outcome.success,
                    html = if (outcome.success) "<html>ok</html>" else null,
                    json = null,
                    finalUrl = "",
                    statusCode = outcome.statusCode,
                    error = outcome.error,
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList(),
                )
            }
        }

        override fun releaseSession() {
            state = CloudflareSessionProvider.SessionState.NOT_STARTED
        }
    }

    private fun config(maxRetries: Int = 1) = WebViewRequestBridge.RequestConfig(
        url = "https://animepahe.com/api?m=search&q=test",
        maxRetries = maxRetries,
    )

    private fun transient(status: Int, error: String) =
        Outcome.Page(statusCode = status, success = false, error = error)

    private val ok = Outcome.Page(statusCode = 200, success = true, error = null)

    @Test
    fun `a transient 5xx failure retries without touching the session`() = runTest {
        val provider = ScriptedProvider(listOf(transient(503, "HTTP 503"), ok))
        val bridge = AndroidWebViewRequestBridge(provider)

        val result = bridge.fetch(config())

        assertTrue("the second attempt must succeed", result.success)
        assertEquals(2, provider.fetchPageCalls)
        assertEquals("a 503 outage is not an invalid session (NET-004)", 0, provider.invalidateCalls)
        assertEquals("no store-wide wipe for a transient failure", 0, provider.forcedRefreshCalls)
        assertFalse("no refresh happened", result.sessionRefreshed)
    }

    @Test
    fun `a timeout retries against the same session`() = runTest {
        val provider = ScriptedProvider(listOf(transient(0, "Page load timed out"), ok))
        val bridge = AndroidWebViewRequestBridge(provider)

        val result = bridge.fetch(config())

        assertTrue("recovered on the retry", result.success)
        assertEquals(2, provider.fetchPageCalls)
        assertEquals("a timeout is not an invalid session (NET-004)", 0, provider.invalidateCalls)
        assertEquals(0, provider.forcedRefreshCalls)
    }

    @Test
    fun `a transport exception retries without a store-wide wipe`() = runTest {
        val provider = ScriptedProvider(listOf(Outcome.Fault("network reset"), ok))
        val bridge = AndroidWebViewRequestBridge(provider)

        val result = bridge.fetch(config())

        assertTrue("recovered on the retry", result.success)
        assertEquals(2, provider.fetchPageCalls)
        assertEquals(
            "an exception is no evidence of an invalid session (NET-004)",
            0,
            provider.invalidateCalls,
        )
        assertEquals(0, provider.forcedRefreshCalls)
    }

    @Test
    fun `a refused 403 invalidates and re-establishes exactly once, in order`() = runTest {
        val provider = ScriptedProvider(listOf(transient(403, "HTTP 403"), ok))
        val bridge = AndroidWebViewRequestBridge(provider)

        val result = bridge.fetch(config())

        assertTrue("recovered after the refresh", result.success)
        assertTrue("the refresh is visible on the result", result.sessionRefreshed)
        assertEquals("exactly ONE invalidate (AQR-014: not two)", 1, provider.invalidateCalls)
        assertEquals("exactly ONE forced re-establishment (AQR-014: not two)", 1, provider.forcedRefreshCalls)
        assertEquals(
            "number AND sequence: fail → invalidate → re-establish → fetch again",
            listOf("establish", "fetch", "invalidate", "establish(force)", "fetch"),
            provider.events,
        )
    }

    @Test
    fun `status reports raised during a bridge fetch stay silent`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        val provider = ScriptedProvider(
            listOf(transient(403, "HTTP 403"), ok),
            emitMainFrameStatusOnFetch = true,
        )
        val bridge = AndroidWebViewRequestBridge(provider)
        val result = bridge.fetch(config())
        runCurrent()

        assertTrue("the bridge recovers on its own", result.success)
        assertEquals("the bridge's single recovery ran", 1, provider.invalidateCalls)
        assertTrue(
            "AQR-014: no SECOND recovery via the quiet-refresh prompt, got: $received",
            received.isEmpty(),
        )
        collector.cancel()
    }

    @Test
    fun `status reports from a direct fetch outside the bridge still prompt`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        val provider = ScriptedProvider(
            listOf(transient(403, "HTTP 403")),
            emitMainFrameStatusOnFetch = true,
        )
        // KwikResolver / audio probe / scan call the provider DIRECTLY (not the
        // bridge) — their quiet-refresh signal must be untouched by Decision 1.
        provider.fetchPage(
            url = "https://animepahe.com/watch/x",
            waitForCloudflare = true,
            timeoutMs = 1_000L,
            contentType = WebViewRequestBridge.ContentType.HTML,
            extraHeaders = emptyMap(),
        )
        runCurrent()

        assertEquals("direct callers keep today's quiet-refresh signal", listOf("HTTP 403"), received)
        collector.cancel()
    }

    @Test
    fun `exception diagnostics never carry the URL or its query`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                Outcome.Fault("fetch died for https://kwik.example/p/abc?token=secret123"),
                ok,
            ),
        )
        val bridge = AndroidWebViewRequestBridge(provider)

        val result = bridge.fetch(config())
        assertTrue("recovered on the retry", result.success)

        val entry = result.diagnostics.single { it.startsWith("Exception:") }
        assertTrue("the sanitized entry is present: $entry", entry.startsWith("Exception: "))
        assertFalse(
            "the URL query must never reach diagnostics (AGENTS §10)",
            entry.contains("token=secret123"),
        )
        assertFalse("the raw scheme URL must be sanitized", entry.contains("https://"))
        assertTrue("host+path survive as the sanitizer's documented form", entry.contains("kwik.example/p/abc"))
    }
}
