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
 * Final-audit H1 (SPEC §6.10 "Prompt the user to refresh the WebView session
 * instead of silently failing") — the bridge's TWO detection sites:
 *
 *  1. the session is not ready BEFORE the fetch (automatic establishment
 *     produced nothing usable) → "Session not ready: …";
 *  2. the bridge's OWN automatic re-establishment already ran and still
 *     failed → "Session refresh failed: …" — the exact case SPEC §6.10 names.
 *
 * Plus the negative control: a successful fetch raises nothing.
 *
 * Pure JVM against fake [CloudflareSessionProvider]s — no WebView, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidWebViewRequestBridgeSessionPromptTest {

    private val config = WebViewRequestBridge.RequestConfig(
        url = "https://animepahe.com/api?m=search&q=test",
        maxRetries = 1,
    )

    /** Ready initial session whose fetches FAIL and whose forced refresh FAILS. */
    private class RefreshFailProvider : CloudflareSessionProvider {
        private var state = CloudflareSessionProvider.SessionState.ESTABLISHED

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
            if (forceRefresh) state = CloudflareSessionProvider.SessionState.FAILED
            return getSessionInfo()
        }

        override suspend fun isSessionValid() =
            state == CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun invalidateSession() {
            state = CloudflareSessionProvider.SessionState.EXPIRED
        }

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>,
        ) = CloudflareSessionProvider.PageResult(
            // A2.1 Decision 2: the bridge only re-establishes for a REFUSED
            // main-frame status, so this fake fails the way an expired
            // cf_clearance actually fails: HTTP 403 on every attempt.
            success = false,
            html = null,
            json = null,
            finalUrl = "",
            statusCode = 403,
            error = "HTTP 403",
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = false,
            diagnostics = emptyList(),
        )

        override fun releaseSession() {
            state = CloudflareSessionProvider.SessionState.NOT_STARTED
        }
    }

    /** A session that is NEVER ready — establishment itself fails. */
    private class NotReadyProvider : CloudflareSessionProvider {
        override fun currentState() = CloudflareSessionProvider.SessionState.FAILED

        override fun getSessionInfo() = CloudflareSessionProvider.SessionInfo(
            state = CloudflareSessionProvider.SessionState.FAILED,
            userAgent = "TestUA",
            cloudflareCompleted = false,
            finalUrl = "",
            isReady = false,
            lastValidatedAt = 0L,
            cookieCount = 0,
            activeHost = "animepahe.local",
        )

        override suspend fun establishSession(forceRefresh: Boolean) = getSessionInfo()

        override suspend fun isSessionValid() = false

        override fun invalidateSession() = Unit

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>,
        ) = error("fetchPage must never be reached: the pre-check fails first")

        override fun releaseSession() = Unit
    }

    /** Fully working provider: ready session, first fetch succeeds. */
    private class HealthyProvider : CloudflareSessionProvider {
        override fun currentState() = CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun getSessionInfo() = CloudflareSessionProvider.SessionInfo(
            state = CloudflareSessionProvider.SessionState.ESTABLISHED,
            userAgent = "TestUA",
            cloudflareCompleted = true,
            finalUrl = "",
            isReady = true,
            lastValidatedAt = 0L,
            cookieCount = 0,
            activeHost = "animepahe.local",
        )

        override suspend fun establishSession(forceRefresh: Boolean) = getSessionInfo()

        override suspend fun isSessionValid() = true

        override fun invalidateSession() = Unit

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>,
        ) = CloudflareSessionProvider.PageResult(
            success = true,
            html = "<html>ok</html>",
            json = null,
            finalUrl = url,
            statusCode = 200,
            error = null,
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = false,
            diagnostics = emptyList(),
        )

        override fun releaseSession() = Unit
    }

    @Test
    fun `a session that is not ready before the fetch raises the prompt`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        val bridge = AndroidWebViewRequestBridge(NotReadyProvider())
        val result = bridge.fetch(config)
        runCurrent()

        assertFalse("the fetch must honestly fail", result.success)
        assertEquals(listOf("Session not ready: FAILED"), received)
        collector.cancel()
    }

    @Test
    fun `an automatic refresh that also fails raises the prompt instead of failing silently`() =
        runTest {
            val received = mutableListOf<String>()
            val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
            runCurrent()

            val bridge = AndroidWebViewRequestBridge(RefreshFailProvider())
            val result = bridge.fetch(config)
            runCurrent()

            assertFalse("the fetch must honestly fail after the retry", result.success)
            assertTrue("the bridge must have attempted its refresh", result.sessionRefreshed)
            assertEquals(
                "SPEC §6.10: the user is prompted exactly when the automatic " +
                    "refresh already failed",
                listOf("Session refresh failed: FAILED"),
                received,
            )
            collector.cancel()
        }

    @Test
    fun `a successful fetch raises no prompt`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        val bridge = AndroidWebViewRequestBridge(HealthyProvider())
        val result = bridge.fetch(config)
        runCurrent()

        assertTrue("the fetch must succeed", result.success)
        assertTrue("no prompt for a healthy session, got: $received", received.isEmpty())
        collector.cancel()
    }
}
