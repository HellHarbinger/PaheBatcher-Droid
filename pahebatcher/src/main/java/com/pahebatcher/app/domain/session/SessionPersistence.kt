package com.pahebatcher.app.domain.session

/**
 * Interface for persisting browser session metadata.
 *
 * Important design decisions:
 * - Does NOT persist raw WebView objects
 * - Does NOT persist raw cookie values (they are TLS-bound and cannot be transferred)
 * - Persists only safe metadata: User-Agent, active host, last validation time
 * - Uses Android-secure storage for any sensitive information
 *
 * Architecture context:
 * - Cloudflare cf_clearance cookies are bound to the TLS fingerprint that solved the challenge
 * - Transferring cookies from WebView to OkHttp does NOT bypass Cloudflare (validated)
 * - Therefore, we persist only metadata needed to know IF a session is valid,
 *   not the session credentials themselves
 */
interface SessionPersistence {

    /**
     * Persisted session metadata (safe for storage, no sensitive data).
     */
    data class PersistedSession(
        /** The User-Agent string used by the browser session */
        val userAgent: String,
        /** The active AnimePahe domain */
        val activeHost: String,
        /** Timestamp when the session was last validated (epoch millis) */
        val lastValidatedAt: Long,
        /** Whether Cloudflare challenge was completed */
        val cloudflareCompleted: Boolean,
        /** Number of cookies in the session (NOT the cookie values) */
        val cookieCount: Int,
        /** The final URL after session establishment */
        val finalUrl: String
    )

    /**
     * Save session metadata to persistent storage.
     */
    suspend fun saveSession(session: PersistedSession)

    /**
     * Load persisted session metadata, or null if none exists.
     */
    suspend fun loadSession(): PersistedSession?

    /**
     * Check if a persisted session exists.
     */
    suspend fun hasSession(): Boolean

    /**
     * Clear all persisted session data.
     */
    suspend fun clearSession()

    /**
     * Check if the persisted session is still fresh (not older than maxAgeMillis).
     */
    suspend fun isSessionFresh(maxAgeMillis: Long = 30 * 60 * 1000): Boolean {
        val session = loadSession() ?: return false
        val age = System.currentTimeMillis() - session.lastValidatedAt
        return age < maxAgeMillis && session.cloudflareCompleted
    }
}
