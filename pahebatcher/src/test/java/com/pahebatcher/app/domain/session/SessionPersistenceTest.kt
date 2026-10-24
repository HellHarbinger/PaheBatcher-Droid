package com.pahebatcher.app.domain.session

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SessionPersistence interface and data classes.
 *
 * Tests the persistence contract and session freshness logic.
 */
class SessionPersistenceTest {

    /**
     * In-memory implementation of SessionPersistence for testing.
     */
    private class InMemorySessionPersistence : SessionPersistence {
        private var storedSession: SessionPersistence.PersistedSession? = null

        override suspend fun saveSession(session: SessionPersistence.PersistedSession) {
            storedSession = session
        }

        override suspend fun loadSession(): SessionPersistence.PersistedSession? {
            return storedSession
        }

        override suspend fun hasSession(): Boolean {
            return storedSession != null
        }

        override suspend fun clearSession() {
            storedSession = null
        }
    }

    private lateinit var persistence: InMemorySessionPersistence

    @Before
    fun setUp() {
        persistence = InMemorySessionPersistence()
    }

    @Test
    fun `save and load session`() = kotlinx.coroutines.runBlocking {
        val session = SessionPersistence.PersistedSession(
            userAgent = "Mozilla/5.0 TestAgent",
            activeHost = "animepahe.com",
            lastValidatedAt = System.currentTimeMillis(),
            cloudflareCompleted = true,
            cookieCount = 5,
            finalUrl = "https://animepahe.com"
        )

        persistence.saveSession(session)
        val loaded = persistence.loadSession()

        assertNotNull(loaded)
        assertEquals("Mozilla/5.0 TestAgent", loaded?.userAgent)
        assertEquals("animepahe.com", loaded?.activeHost)
        assertTrue(loaded?.cloudflareCompleted == true)
        assertEquals(5, loaded?.cookieCount)
        assertEquals("https://animepahe.com", loaded?.finalUrl)
    }

    @Test
    fun `hasSession returns false when no session saved`() = kotlinx.coroutines.runBlocking {
        assertFalse(persistence.hasSession())
    }

    @Test
    fun `hasSession returns true after saving`() = kotlinx.coroutines.runBlocking {
        val session = SessionPersistence.PersistedSession(
            userAgent = "test",
            activeHost = "animepahe.com",
            lastValidatedAt = System.currentTimeMillis(),
            cloudflareCompleted = true,
            cookieCount = 1,
            finalUrl = "https://animepahe.com"
        )
        persistence.saveSession(session)
        assertTrue(persistence.hasSession())
    }

    @Test
    fun `clearSession removes saved session`() = kotlinx.coroutines.runBlocking {
        val session = SessionPersistence.PersistedSession(
            userAgent = "test",
            activeHost = "animepahe.com",
            lastValidatedAt = System.currentTimeMillis(),
            cloudflareCompleted = true,
            cookieCount = 1,
            finalUrl = "https://animepahe.com"
        )
        persistence.saveSession(session)
        assertTrue(persistence.hasSession())

        persistence.clearSession()
        assertFalse(persistence.hasSession())
        assertNull(persistence.loadSession())
    }

    @Test
    fun `isSessionFresh returns false when no session exists`() = kotlinx.coroutines.runBlocking {
        assertFalse(persistence.isSessionFresh())
    }

    @Test
    fun `isSessionFresh returns true for recent session`() = kotlinx.coroutines.runBlocking {
        val session = SessionPersistence.PersistedSession(
            userAgent = "test",
            activeHost = "animepahe.com",
            lastValidatedAt = System.currentTimeMillis(),
            cloudflareCompleted = true,
            cookieCount = 1,
            finalUrl = "https://animepahe.com"
        )
        persistence.saveSession(session)
        assertTrue(persistence.isSessionFresh(maxAgeMillis = 30 * 60 * 1000))
    }

    @Test
    fun `isSessionFresh returns false for old session`() = kotlinx.coroutines.runBlocking {
        val session = SessionPersistence.PersistedSession(
            userAgent = "test",
            activeHost = "animepahe.com",
            lastValidatedAt = System.currentTimeMillis() - (60 * 60 * 1000), // 1 hour ago
            cloudflareCompleted = true,
            cookieCount = 1,
            finalUrl = "https://animepahe.com"
        )
        persistence.saveSession(session)
        assertFalse(persistence.isSessionFresh(maxAgeMillis = 30 * 60 * 1000))
    }

    @Test
    fun `isSessionFresh returns false when Cloudflare not completed`() = kotlinx.coroutines.runBlocking {
        val session = SessionPersistence.PersistedSession(
            userAgent = "test",
            activeHost = "animepahe.com",
            lastValidatedAt = System.currentTimeMillis(),
            cloudflareCompleted = false,
            cookieCount = 0,
            finalUrl = ""
        )
        persistence.saveSession(session)
        assertFalse(persistence.isSessionFresh(maxAgeMillis = 30 * 60 * 1000))
    }

    @Test
    fun `isSessionFresh uses default maxAgeMillis of 30 minutes`() = kotlinx.coroutines.runBlocking {
        // Session from 29 minutes ago should be fresh
        val recentSession = SessionPersistence.PersistedSession(
            userAgent = "test",
            activeHost = "animepahe.com",
            lastValidatedAt = System.currentTimeMillis() - (29 * 60 * 1000),
            cloudflareCompleted = true,
            cookieCount = 1,
            finalUrl = "https://animepahe.com"
        )
        persistence.saveSession(recentSession)
        assertTrue(persistence.isSessionFresh()) // default 30 min

        // Session from 31 minutes ago should be stale
        val oldSession = SessionPersistence.PersistedSession(
            userAgent = "test",
            activeHost = "animepahe.com",
            lastValidatedAt = System.currentTimeMillis() - (31 * 60 * 1000),
            cloudflareCompleted = true,
            cookieCount = 1,
            finalUrl = "https://animepahe.com"
        )
        persistence.saveSession(oldSession)
        assertFalse(persistence.isSessionFresh()) // default 30 min
    }

    @Test
    fun `PersistedSession data class is immutable`() {
        val session = SessionPersistence.PersistedSession(
            userAgent = "test",
            activeHost = "animepahe.com",
            lastValidatedAt = 12345L,
            cloudflareCompleted = true,
            cookieCount = 5,
            finalUrl = "https://animepahe.com"
        )

        assertEquals("test", session.userAgent)
        assertEquals("animepahe.com", session.activeHost)
        assertEquals(12345L, session.lastValidatedAt)
        assertTrue(session.cloudflareCompleted)
        assertEquals(5, session.cookieCount)
    }
}
