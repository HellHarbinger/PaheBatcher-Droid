package com.pahebatcher.app.domain.session

import com.pahebatcher.app.data.remote.parser.AnimePaheParser
import com.pahebatcher.app.data.session.AndroidWebViewRequestBridge
import com.pahebatcher.app.data.session.WebViewHttpClient
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for WebViewRequestBridge contract.
 *
 * Tests the request bridge data classes and configuration.
 */
class WebViewRequestBridgeTest {

    /**
     * Fake implementation of WebViewRequestBridge for testing.
     */
    private class FakeWebViewRequestBridge : WebViewRequestBridge {
        private var sessionState = CloudflareSessionProvider.SessionState.NOT_STARTED
        var fetchCallCount = 0
        var releaseCallCount = 0
        var lastConfig: WebViewRequestBridge.RequestConfig? = null
        /** Optional scripted result; when null the default success result is returned. */
        var nextResult: WebViewRequestBridge.BridgeResult? = null

        fun setSessionState(state: CloudflareSessionProvider.SessionState) {
            sessionState = state
        }

        override suspend fun fetch(config: WebViewRequestBridge.RequestConfig): WebViewRequestBridge.BridgeResult {
            fetchCallCount++
            lastConfig = config
            nextResult?.let { return it }
            return WebViewRequestBridge.BridgeResult(
                success = true,
                content = "test content",
                finalUrl = config.url,
                error = null,
                cloudflareHandled = false,
                sessionRefreshed = false,
                diagnostics = listOf("Test bridge fetch: ${config.url}")
            )
        }

        override fun getSessionState() = sessionState

        override fun release() {
            releaseCallCount++
            sessionState = CloudflareSessionProvider.SessionState.NOT_STARTED
        }
    }

    @Test
    fun `ContentType enum has required values`() {
        val types = WebViewRequestBridge.ContentType.values()
        assertEquals(2, types.size)
        // TEST-010: the two assertNotNull(...) lines that followed were
        // tautologies — enum entries are non-null by TYPE. The falsifiable
        // form pins the required members BY NAME (rename/removal fails here).
        assertEquals(
            setOf("HTML", "JSON"),
            types.map { it.name }.toSet(),
        )
    }

    @Test
    fun `RequestConfig holds all required fields`() {
        val config = WebViewRequestBridge.RequestConfig(
            url = "https://animepahe.com/api?m=search&q=test",
            contentType = WebViewRequestBridge.ContentType.JSON,
            waitForCloudflare = true,
            timeoutMs = 30_000L,
            maxRetries = 2
        )

        assertEquals("https://animepahe.com/api?m=search&q=test", config.url)
        assertEquals(WebViewRequestBridge.ContentType.JSON, config.contentType)
        assertTrue(config.waitForCloudflare)
        assertEquals(30_000L, config.timeoutMs)
        assertEquals(2, config.maxRetries)
    }

    @Test
    fun `RequestConfig has sensible defaults`() {
        val config = WebViewRequestBridge.RequestConfig(
            url = "https://animepahe.com"
        )

        assertEquals(WebViewRequestBridge.ContentType.HTML, config.contentType)
        assertTrue(config.waitForCloudflare)
        assertEquals(30_000L, config.timeoutMs)
        assertEquals(1, config.maxRetries)
    }

    @Test
    fun `BridgeResult data class holds all required fields`() {
        val result = WebViewRequestBridge.BridgeResult(
            success = true,
            content = "test",
            finalUrl = "https://test.com",
            error = null,
            cloudflareHandled = true,
            sessionRefreshed = false,
            diagnostics = listOf("diagnostic")
        )

        assertTrue(result.success)
        assertEquals("test", result.content)
        assertEquals("https://test.com", result.finalUrl)
        assertNull(result.error)
        assertTrue(result.cloudflareHandled)
        assertFalse(result.sessionRefreshed)
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun `fetchHtml convenience method uses HTML content type`() = kotlinx.coroutines.runBlocking {
        val bridge = FakeWebViewRequestBridge()
        val result = bridge.fetchHtml("https://animepahe.com/anime/test")

        assertEquals(1, bridge.fetchCallCount)
        assertEquals(WebViewRequestBridge.ContentType.HTML, bridge.lastConfig?.contentType)
        assertEquals("https://animepahe.com/anime/test", bridge.lastConfig?.url)
        assertTrue(result.success)
    }

    @Test
    fun `fetchJson convenience method uses JSON content type`() = kotlinx.coroutines.runBlocking {
        val bridge = FakeWebViewRequestBridge()
        val result = bridge.fetchJson("https://animepahe.com/api?m=search&q=test")

        assertEquals(1, bridge.fetchCallCount)
        assertEquals(WebViewRequestBridge.ContentType.JSON, bridge.lastConfig?.contentType)
        assertEquals("https://animepahe.com/api?m=search&q=test", bridge.lastConfig?.url)
        assertTrue(result.success)
    }

    @Test
    fun `fetchJson has default timeout of 30 seconds`() = kotlinx.coroutines.runBlocking {
        val bridge = FakeWebViewRequestBridge()
        bridge.fetchJson("https://animepahe.com/api")

        assertEquals(30_000L, bridge.lastConfig?.timeoutMs)
    }

    @Test
    fun `fetchHtml has default timeout of 30 seconds`() = kotlinx.coroutines.runBlocking {
        val bridge = FakeWebViewRequestBridge()
        bridge.fetchHtml("https://animepahe.com/test")

        assertEquals(30_000L, bridge.lastConfig?.timeoutMs)
    }

    @Test
    fun `fetch accepts custom timeout`() = kotlinx.coroutines.runBlocking {
        val bridge = FakeWebViewRequestBridge()
        bridge.fetch(
            WebViewRequestBridge.RequestConfig(
                url = "https://test.com",
                timeoutMs = 60_000L
            )
        )

        assertEquals(60_000L, bridge.lastConfig?.timeoutMs)
    }

    @Test
    fun `getSessionState delegates to underlying state`() {
        val bridge = FakeWebViewRequestBridge()
        assertEquals(CloudflareSessionProvider.SessionState.NOT_STARTED, bridge.getSessionState())

        bridge.setSessionState(CloudflareSessionProvider.SessionState.ESTABLISHED)
        assertEquals(CloudflareSessionProvider.SessionState.ESTABLISHED, bridge.getSessionState())
    }

    @Test
    fun `release increments release count`() {
        val bridge = FakeWebViewRequestBridge()
        bridge.release()
        assertEquals(1, bridge.releaseCallCount)
        assertEquals(CloudflareSessionProvider.SessionState.NOT_STARTED, bridge.getSessionState())
    }

    // ========== H3: Retry Logic Tests ==========

    /**
     * Fake CloudflareSessionProvider that simulates a failed first fetchPage,
     * then succeeds on subsequent calls (after session refresh).
     */
    private class RetryFakeProvider : CloudflareSessionProvider {
        private var state = CloudflareSessionProvider.SessionState.NOT_STARTED
        var fetchCallCount = 0
        var invalidateCallCount = 0
        var establishCallCount = 0

        override fun currentState() = state

        override fun getSessionInfo() = CloudflareSessionProvider.SessionInfo(
            state = state,
            userAgent = "TestAgent",
            cloudflareCompleted = true,
            finalUrl = "https://animepahe.com",
            isReady = state == CloudflareSessionProvider.SessionState.ESTABLISHED,
            lastValidatedAt = System.currentTimeMillis(),
            cookieCount = 5,
            activeHost = "animepahe.com"
        )

        override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo {
            establishCallCount++
            state = CloudflareSessionProvider.SessionState.ESTABLISHED
            return getSessionInfo()
        }

        override suspend fun isSessionValid() =
            state == CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun invalidateSession() {
            invalidateCallCount++
            state = CloudflareSessionProvider.SessionState.EXPIRED
        }

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>
        ): CloudflareSessionProvider.PageResult {
            fetchCallCount++
            return if (fetchCallCount == 1) {
                // First call fails as a REFUSED session (A2.1 Decision 2: only a
                // real 401/403 main-frame status earns the refresh this test pins)
                CloudflareSessionProvider.PageResult(
                    success = false,
                    html = null,
                    json = null,
                    finalUrl = url,
                    statusCode = 403,
                    error = "HTTP 403",
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = listOf("Simulated failure")
                )
            } else {
                // Subsequent calls succeed — simulates recovery after session refresh
                CloudflareSessionProvider.PageResult(
                    success = true,
                    html = "<html>recovered</html>",
                    json = null,
                    finalUrl = url,
                    statusCode = 0,
                    error = null,
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = true,
                    diagnostics = listOf("Success after retry")
                )
            }
        }

        override fun releaseSession() {
            state = CloudflareSessionProvider.SessionState.NOT_STARTED
        }
    }

    @Test
    fun `failed page fetch triggers session refresh and retry`() = kotlinx.coroutines.runBlocking {
        val provider = RetryFakeProvider()
        val bridge = com.pahebatcher.app.data.session.AndroidWebViewRequestBridge(provider)

        // First attempt will fail, triggering retry with session refresh
        val result = bridge.fetch(
            WebViewRequestBridge.RequestConfig(
                url = "https://animepahe.com/api?m=search&q=test",
                maxRetries = 1
            )
        )

        assertTrue("Bridge should succeed after retry", result.success)
        assertEquals("<html>recovered</html>", result.content)
        assertTrue(result.sessionRefreshed)
        // First call failed, second succeeded
        assertEquals(2, provider.fetchCallCount)
        // Session was invalidated once (retry) and re-established.
        // establishCallCount includes: initial ensureSession() + retry establishSession()
        assertEquals(1, provider.invalidateCallCount)
        assertEquals(2, provider.establishCallCount) // 1 from ensureSession + 1 from retry
    }

    @Test
    fun `successful first fetch does not trigger retry`() = kotlinx.coroutines.runBlocking {
        val provider = RetryFakeProvider()
        val bridge = com.pahebatcher.app.data.session.AndroidWebViewRequestBridge(provider)

        // Simulate first call succeeding by making fetchCallCount start at 1
        provider.fetchCallCount = 1

        val result = bridge.fetch(
            WebViewRequestBridge.RequestConfig(
                url = "https://animepahe.com/api?m=search&q=test",
                maxRetries = 1
            )
        )

        assertTrue(result.success)
        assertFalse(result.sessionRefreshed)
        // Only one fetch call (no retry): pre-set 1 + bridge call = 2
        assertEquals(2, provider.fetchCallCount)
        assertEquals(0, provider.invalidateCallCount)
        // establishCallCount includes initial ensureSession() call
        assertEquals(1, provider.establishCallCount) // from ensureSession()
    }

    @Test
    fun `exhausted retries returns failure`() = kotlinx.coroutines.runBlocking {
        // Provider that always fails
        val provider = object : CloudflareSessionProvider {
            private var state = CloudflareSessionProvider.SessionState.NOT_STARTED
            var fetchCallCount = 0
            var invalidateCallCount = 0
            var establishCallCount = 0

            override fun currentState() = state
            override fun getSessionInfo() = CloudflareSessionProvider.SessionInfo(
                state = state, userAgent = "TestAgent", cloudflareCompleted = true,
                finalUrl = "https://animepahe.com",
                isReady = state == CloudflareSessionProvider.SessionState.ESTABLISHED,
                lastValidatedAt = System.currentTimeMillis(), cookieCount = 5,
                activeHost = "animepahe.com"
            )
            override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo {
                establishCallCount++
                state = CloudflareSessionProvider.SessionState.ESTABLISHED
                return getSessionInfo()
            }
            override suspend fun isSessionValid() = state == CloudflareSessionProvider.SessionState.ESTABLISHED
            override fun invalidateSession() {
                invalidateCallCount++
                state = CloudflareSessionProvider.SessionState.EXPIRED
            }
            override suspend fun fetchPage(url: String, waitForCloudflare: Boolean, timeoutMs: Long, contentType: WebViewRequestBridge.ContentType, extraHeaders: Map<String, String>): CloudflareSessionProvider.PageResult {
                fetchCallCount++
                return CloudflareSessionProvider.PageResult(
                    // A2.1 Decision 2: a PERSISTENT refusal (403 on every attempt)
                    // so each retry re-establishes — retry exhaustion with refresh.
                    success = false, html = null, json = null,
                    finalUrl = url, statusCode = 403, error = "HTTP 403",
                    cloudflareChallengeDetected = false, cloudflareChallengeCompleted = false,
                    diagnostics = listOf("Always fails")
                )
            }
            override fun releaseSession() { state = CloudflareSessionProvider.SessionState.NOT_STARTED }
        }
        val bridge = com.pahebatcher.app.data.session.AndroidWebViewRequestBridge(provider)

        val result = bridge.fetch(
            WebViewRequestBridge.RequestConfig(
                url = "https://animepahe.com/test",
                maxRetries = 2
            )
        )

        assertFalse(result.success)
        assertNull(result.content)
        assertNotNull(result.error)
        // Initial attempt + 2 retries = 3 total fetch calls
        assertEquals(3, provider.fetchCallCount)
        // Session was refreshed for each retry (2 times)
        assertEquals(2, provider.invalidateCallCount)
        // establishCallCount includes: initial ensureSession + 2 retry refreshes = 3
        assertEquals(3, provider.establishCallCount)
    }

    // ========== Phase 11.5B: JSON content-type propagation ==========

    /**
     * Fake CloudflareSessionProvider that records the contentType the bridge
     * forwards into the session fetch and returns a caller-configured PageResult.
     */
    private class ContentTypeRecordingProvider : CloudflareSessionProvider {
        var lastContentType: WebViewRequestBridge.ContentType? = null
        var nextResult: CloudflareSessionProvider.PageResult? = null
        private var state = CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun currentState() = state

        override fun getSessionInfo() = CloudflareSessionProvider.SessionInfo(
            state = state,
            userAgent = "TestAgent",
            cloudflareCompleted = true,
            finalUrl = "https://animepahe.com",
            isReady = true,
            lastValidatedAt = System.currentTimeMillis(),
            cookieCount = 5,
            activeHost = "animepahe.com"
        )

        override suspend fun establishSession(forceRefresh: Boolean) = getSessionInfo()

        override suspend fun isSessionValid() = true

        override fun invalidateSession() {
            state = CloudflareSessionProvider.SessionState.EXPIRED
        }

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>
        ): CloudflareSessionProvider.PageResult {
            lastContentType = contentType
            return nextResult ?: CloudflareSessionProvider.PageResult(
                success = true,
                html = null,
                json = null,
                finalUrl = url,
                statusCode = 0,
                error = null,
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = true,
                diagnostics = listOf("Test fetch")
            )
        }

        override fun releaseSession() {
            state = CloudflareSessionProvider.SessionState.NOT_STARTED
        }
    }

    @Test
    fun `JSON request forwards JSON content type and exposes raw JSON body to the parser`() = kotlinx.coroutines.runBlocking {
        val rawJson = """{"data":[{"session":"s1","title":"Tom & Jerry <TV>","type":"TV","year":2024,"episodes":12,"score":"8.5"}],"total":1}"""
        val provider = ContentTypeRecordingProvider().apply {
            nextResult = CloudflareSessionProvider.PageResult(
                success = true,
                html = null,
                json = rawJson,
                finalUrl = "https://animepahe.com/api?m=search&q=Test",
                statusCode = 0,
                error = null,
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = true,
                diagnostics = listOf("JSON body captured")
            )
        }
        val bridge = AndroidWebViewRequestBridge(provider)

        val result = bridge.fetch(
            WebViewRequestBridge.RequestConfig(
                url = "https://animepahe.com/api?m=search&q=Test",
                contentType = WebViewRequestBridge.ContentType.JSON
            )
        )

        assertEquals(WebViewRequestBridge.ContentType.JSON, provider.lastContentType)
        assertTrue(result.success)
        assertEquals(rawJson, result.content)
        // The parser receives the raw JSON body — not DOM HTML — and produces rows.
        val parsed = AnimePaheParser.parseSearchResults(result.content!!)
        assertEquals(1, parsed.size)
        assertEquals("Tom & Jerry <TV>", parsed[0]["title"])
    }

    @Test
    fun `HTML request forwards HTML content type and exposes html content unchanged`() = kotlinx.coroutines.runBlocking {
        val html = "<html><body><h1>Play</h1></body></html>"
        val provider = ContentTypeRecordingProvider().apply {
            nextResult = CloudflareSessionProvider.PageResult(
                success = true,
                html = html,
                json = null,
                finalUrl = "https://animepahe.com/anime/test",
                statusCode = 0,
                error = null,
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = true,
                diagnostics = listOf("HTML captured")
            )
        }
        val bridge = AndroidWebViewRequestBridge(provider)

        val result = bridge.fetch(
            WebViewRequestBridge.RequestConfig(url = "https://animepahe.com/anime/test")
        )

        assertEquals(WebViewRequestBridge.ContentType.HTML, provider.lastContentType)
        assertTrue(result.success)
        assertEquals(html, result.content)
    }

    @Test
    fun `WebViewHttpClient fetchJson issues a JSON-typed request and returns its content`() = kotlinx.coroutines.runBlocking {
        val fakeBridge = FakeWebViewRequestBridge()
        val client = WebViewHttpClient(fakeBridge)

        val content = client.fetchJson("https://animepahe.com/api?m=search&q=test")

        assertEquals(1, fakeBridge.fetchCallCount)
        assertEquals(WebViewRequestBridge.ContentType.JSON, fakeBridge.lastConfig?.contentType)
        assertEquals("test content", content)
    }

    @Test
    fun `WebViewHttpClient fetchHtml issues an HTML-typed request and returns its content`() = kotlinx.coroutines.runBlocking {
        val fakeBridge = FakeWebViewRequestBridge()
        val client = WebViewHttpClient(fakeBridge)

        val content = client.fetchHtml("https://animepahe.com/anime/test")

        assertEquals(1, fakeBridge.fetchCallCount)
        assertEquals(WebViewRequestBridge.ContentType.HTML, fakeBridge.lastConfig?.contentType)
        assertEquals("test content", content)
    }

    @Test
    fun `WebViewHttpClient fetchJson returns null when the bridge reports failure`() = kotlinx.coroutines.runBlocking {
        val fakeBridge = FakeWebViewRequestBridge().apply {
            nextResult = WebViewRequestBridge.BridgeResult(
                success = false,
                content = null,
                finalUrl = "",
                error = "Simulated failure",
                cloudflareHandled = false,
                sessionRefreshed = false,
                diagnostics = listOf("Simulated failure")
            )
        }
        val client = WebViewHttpClient(fakeBridge)

        assertNull(client.fetchJson("https://animepahe.com/api?m=search&q=test"))
    }
}
