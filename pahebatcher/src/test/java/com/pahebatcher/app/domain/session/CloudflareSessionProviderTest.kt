package com.pahebatcher.app.domain.session

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CloudflareSessionProvider contract.
 *
 * Tests the session state model, session info, and page result data classes.
 * These tests validate the domain contract without requiring Android WebView.
 */
class CloudflareSessionProviderTest {

    /**
     * Fake implementation of CloudflareSessionProvider for testing the contract.
     */
    private class FakeCloudflareSessionProvider : CloudflareSessionProvider {
        private var state = CloudflareSessionProvider.SessionState.NOT_STARTED
        private var sessionInfo: CloudflareSessionProvider.SessionInfo? = null
        var establishCallCount = 0
        var invalidateCallCount = 0
        var releaseCallCount = 0
        var fetchCallCount = 0

        override fun currentState() = state

        override fun getSessionInfo(): CloudflareSessionProvider.SessionInfo {
            return sessionInfo ?: CloudflareSessionProvider.SessionInfo(
                state = state,
                userAgent = "",
                cloudflareCompleted = false,
                finalUrl = "",
                isReady = false,
                lastValidatedAt = 0L,
                cookieCount = 0,
                activeHost = ""
            )
        }

        override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo {
            establishCallCount++
            state = CloudflareSessionProvider.SessionState.ESTABLISHED
            sessionInfo = CloudflareSessionProvider.SessionInfo(
                state = CloudflareSessionProvider.SessionState.ESTABLISHED,
                userAgent = "FakeAgent/1.0",
                cloudflareCompleted = true,
                finalUrl = "https://animepahe.com",
                isReady = true,
                lastValidatedAt = System.currentTimeMillis(),
                cookieCount = 5,
                activeHost = "animepahe.com"
            )
            return sessionInfo!!
        }

        override suspend fun isSessionValid() = state == CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun invalidateSession() {
            invalidateCallCount++
            state = CloudflareSessionProvider.SessionState.EXPIRED
            sessionInfo = null
        }

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>
        ): CloudflareSessionProvider.PageResult {
            fetchCallCount++
            return CloudflareSessionProvider.PageResult(
                success = true,
                html = "<html>test content</html>",
                json = null,
                finalUrl = url,
                statusCode = 200,
                error = null,
                cloudflareChallengeDetected = false,
                cloudflareChallengeCompleted = true,
                diagnostics = listOf("Test fetch")
            )
        }

        override fun releaseSession() {
            releaseCallCount++
            state = CloudflareSessionProvider.SessionState.NOT_STARTED
            sessionInfo = null
        }
    }

    @Test
    fun `SessionState enum has all required states`() {
        val states = CloudflareSessionProvider.SessionState.values()
        assertEquals(5, states.size)
        // TEST-010: the five assertNotNull(...) lines that followed were
        // tautologies — enum entries are non-null by TYPE, so they could never
        // fail. The falsifiable form: every required state must exist BY NAME,
        // so renaming or deleting one fails this test.
        assertEquals(
            setOf("NOT_STARTED", "ESTABLISHING", "ESTABLISHED", "EXPIRED", "FAILED"),
            states.map { it.name }.toSet(),
        )
    }

    @Test
    fun `FakeSessionProvider starts in NOT_STARTED state`() {
        val provider = FakeCloudflareSessionProvider()
        assertEquals(CloudflareSessionProvider.SessionState.NOT_STARTED, provider.currentState())
    }

    @Test
    fun `establishSession changes state to ESTABLISHED`() = kotlinx.coroutines.runBlocking {
        val provider = FakeCloudflareSessionProvider()
        val info = provider.establishSession()

        assertEquals(CloudflareSessionProvider.SessionState.ESTABLISHED, provider.currentState())
        assertTrue(info.isReady)
        assertEquals("FakeAgent/1.0", info.userAgent)
        assertTrue(info.cloudflareCompleted)
        assertEquals(5, info.cookieCount)
    }

    @Test
    fun `ensureSession calls establish when not started`() = kotlinx.coroutines.runBlocking {
        val provider = FakeCloudflareSessionProvider()
        val info = provider.ensureSession()

        assertEquals(1, provider.establishCallCount)
        assertTrue(info.isReady)
    }

    @Test
    fun `ensureSession reuses existing session when established`() = kotlinx.coroutines.runBlocking {
        val provider = FakeCloudflareSessionProvider()
        provider.establishSession()
        provider.establishCallCount = 0 // Reset counter

        val info = provider.ensureSession()

        // Should not call establish again since session is already established
        assertEquals(0, provider.establishCallCount)
        assertTrue(info.isReady)
    }

    @Test
    fun `invalidateSession changes state to EXPIRED`() {
        val provider = FakeCloudflareSessionProvider()
        provider.invalidateSession()

        assertEquals(CloudflareSessionProvider.SessionState.EXPIRED, provider.currentState())
        assertEquals(1, provider.invalidateCallCount)
    }

    @Test
    fun `releaseSession changes state to NOT_STARTED`() = kotlinx.coroutines.runBlocking {
        val provider = FakeCloudflareSessionProvider()
        provider.establishSession()
        provider.releaseSession()

        assertEquals(CloudflareSessionProvider.SessionState.NOT_STARTED, provider.currentState())
        assertEquals(1, provider.releaseCallCount)
    }

    @Test
    fun `fetchPage calls the provider`() = kotlinx.coroutines.runBlocking {
        val provider = FakeCloudflareSessionProvider()
        val result = provider.fetchPage("https://animepahe.com/api?m=search&q=test")

        assertEquals(1, provider.fetchCallCount)
        assertTrue(result.success)
        assertEquals("<html>test content</html>", result.html)
        assertEquals("https://animepahe.com/api?m=search&q=test", result.finalUrl)
    }

    @Test
    fun `SessionInfo data class holds all required fields`() {
        val info = CloudflareSessionProvider.SessionInfo(
            state = CloudflareSessionProvider.SessionState.ESTABLISHED,
            userAgent = "Mozilla/5.0",
            cloudflareCompleted = true,
            finalUrl = "https://animepahe.com",
            isReady = true,
            lastValidatedAt = 1234567890L,
            cookieCount = 5,
            activeHost = "animepahe.com"
        )

        assertEquals(CloudflareSessionProvider.SessionState.ESTABLISHED, info.state)
        assertEquals("Mozilla/5.0", info.userAgent)
        assertTrue(info.cloudflareCompleted)
        assertEquals("https://animepahe.com", info.finalUrl)
        assertTrue(info.isReady)
        assertEquals(1234567890L, info.lastValidatedAt)
        assertEquals(5, info.cookieCount)
        assertEquals("animepahe.com", info.activeHost)
    }

    @Test
    fun `PageResult data class holds all required fields`() {
        val result = CloudflareSessionProvider.PageResult(
            success = true,
            html = "<html></html>",
            json = null,
            finalUrl = "https://animepahe.com",
            statusCode = 200,
            error = null,
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = true,
            diagnostics = listOf("test")
        )

        assertTrue(result.success)
        assertEquals("<html></html>", result.html)
        assertNull(result.json)
        assertEquals(200, result.statusCode)
        assertNull(result.error)
        assertFalse(result.cloudflareChallengeDetected)
        assertTrue(result.cloudflareChallengeCompleted)
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun `isSessionValid returns false when not established`() = kotlinx.coroutines.runBlocking {
        val provider = FakeCloudflareSessionProvider()
        assertFalse(provider.isSessionValid())
    }

    @Test
    fun `isSessionValid returns true when established`() = kotlinx.coroutines.runBlocking {
        val provider = FakeCloudflareSessionProvider()
        provider.establishSession()
        assertTrue(provider.isSessionValid())
    }

    @Test
    fun `getSessionInfo returns default when no session`() {
        val provider = FakeCloudflareSessionProvider()
        val info = provider.getSessionInfo()

        assertEquals(CloudflareSessionProvider.SessionState.NOT_STARTED, info.state)
        assertFalse(info.isReady)
        assertEquals(0, info.cookieCount)
    }

    /**
     * Fake implementation that supports stale session testing.
     * Allows setting an arbitrary lastValidatedAt timestamp.
     */
    private class StaleAwareFakeProvider(
        private var validatedAt: Long = System.currentTimeMillis()
    ) : CloudflareSessionProvider {
        private var state = CloudflareSessionProvider.SessionState.NOT_STARTED

        fun setLastValidatedAt(timestamp: Long) {
            validatedAt = timestamp
        }

        override fun currentState() = state

        override fun getSessionInfo() = CloudflareSessionProvider.SessionInfo(
            state = state,
            userAgent = "TestAgent",
            cloudflareCompleted = true,
            finalUrl = "https://animepahe.com",
            isReady = state == CloudflareSessionProvider.SessionState.ESTABLISHED,
            lastValidatedAt = validatedAt,
            cookieCount = 5,
            activeHost = "animepahe.com"
        )

        override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo {
            state = CloudflareSessionProvider.SessionState.ESTABLISHED
            validatedAt = System.currentTimeMillis()
            return getSessionInfo()
        }

        override suspend fun isSessionValid(): Boolean {
            // Replicate AndroidCloudflareSession's H1 logic:
            // Session older than 20 minutes is considered stale.
            if (state != CloudflareSessionProvider.SessionState.ESTABLISHED) return false
            val info = getSessionInfo()
            val age = System.currentTimeMillis() - info.lastValidatedAt
            return age <= 20 * 60 * 1000L
        }

        override fun invalidateSession() {
            state = CloudflareSessionProvider.SessionState.EXPIRED
        }

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>
        ) = CloudflareSessionProvider.PageResult(
            success = true, html = "<html></html>", json = null,
            finalUrl = url, statusCode = 0, error = null,
            cloudflareChallengeDetected = false, cloudflareChallengeCompleted = true,
            diagnostics = emptyList()
        )

        override fun releaseSession() {
            state = CloudflareSessionProvider.SessionState.NOT_STARTED
        }
    }

    @Test
    fun `isSessionValid returns false when session is older than 20 minutes`() = kotlinx.coroutines.runBlocking {
        val provider = StaleAwareFakeProvider()
        provider.establishSession()

        // Simulate a session that was validated 25 minutes ago
        provider.setLastValidatedAt(System.currentTimeMillis() - 25 * 60 * 1000L)

        assertFalse(provider.isSessionValid())
    }

    @Test
    fun `isSessionValid returns true when session is recent`() = kotlinx.coroutines.runBlocking {
        val provider = StaleAwareFakeProvider()
        provider.establishSession()

        // Session was just established (within the last few ms)
        assertTrue(provider.isSessionValid())
    }

    @Test
    fun `isSessionValid returns false when session is 21 minutes old`() = kotlinx.coroutines.runBlocking {
        val provider = StaleAwareFakeProvider()
        provider.establishSession()

        // Exactly 21 minutes — just past the 20-minute threshold
        provider.setLastValidatedAt(System.currentTimeMillis() - 21 * 60 * 1000L)

        assertFalse(provider.isSessionValid())
    }

    @Test
    fun `isSessionValid returns true when session is 19 minutes old`() = kotlinx.coroutines.runBlocking {
        val provider = StaleAwareFakeProvider()
        provider.establishSession()

        // 19 minutes — within the 20-minute threshold
        provider.setLastValidatedAt(System.currentTimeMillis() - 19 * 60 * 1000L)

        assertTrue(provider.isSessionValid())
    }
}
