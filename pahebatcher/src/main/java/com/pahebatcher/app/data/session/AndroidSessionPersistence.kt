package com.pahebatcher.app.data.session

import android.content.Context
import android.content.SharedPreferences
import com.pahebatcher.app.domain.session.SessionPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of session persistence using SharedPreferences.
 *
 * Security considerations:
 * - Uses MODE_PRIVATE (no other app can access)
 * - Does NOT persist raw cookie values (they are TLS-bound and useless)
 * - Does NOT persist WebView objects
 * - Persists only metadata: User-Agent, host, timestamps
 * - No sensitive authentication data stored
 *
 * Design rationale:
 * - Cloudflare cf_clearance cookies are bound to the TLS fingerprint that solved the challenge
 * - Transferring cookies from WebView to OkHttp does NOT bypass Cloudflare (validated)
 * - Therefore, we persist only metadata needed to know IF a session is valid
 */
class AndroidSessionPersistence(
    context: Context
) : SessionPersistence {

    companion object {
        private const val PREFS_NAME = "pahe_session"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_ACTIVE_HOST = "active_host"
        private const val KEY_LAST_VALIDATED = "last_validated_at"
        private const val KEY_CF_COMPLETED = "cloudflare_completed"
        private const val KEY_COOKIE_COUNT = "cookie_count"
        private const val KEY_FINAL_URL = "final_url"
        private const val KEY_HAS_SESSION = "has_session"
    }

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun saveSession(session: SessionPersistence.PersistedSession) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_USER_AGENT, session.userAgent)
                .putString(KEY_ACTIVE_HOST, session.activeHost)
                .putLong(KEY_LAST_VALIDATED, session.lastValidatedAt)
                .putBoolean(KEY_CF_COMPLETED, session.cloudflareCompleted)
                .putInt(KEY_COOKIE_COUNT, session.cookieCount)
                .putString(KEY_FINAL_URL, session.finalUrl)
                .putBoolean(KEY_HAS_SESSION, true)
                .apply()
        }
    }

    override suspend fun loadSession(): SessionPersistence.PersistedSession? {
        return withContext(Dispatchers.IO) {
            if (!prefs.getBoolean(KEY_HAS_SESSION, false)) {
                return@withContext null
            }

            SessionPersistence.PersistedSession(
                userAgent = prefs.getString(KEY_USER_AGENT, "") ?: "",
                activeHost = prefs.getString(KEY_ACTIVE_HOST, "") ?: "",
                lastValidatedAt = prefs.getLong(KEY_LAST_VALIDATED, 0L),
                cloudflareCompleted = prefs.getBoolean(KEY_CF_COMPLETED, false),
                cookieCount = prefs.getInt(KEY_COOKIE_COUNT, 0),
                finalUrl = prefs.getString(KEY_FINAL_URL, "") ?: ""
            )
        }
    }

    override suspend fun hasSession(): Boolean {
        return withContext(Dispatchers.IO) {
            prefs.getBoolean(KEY_HAS_SESSION, false)
        }
    }

    override suspend fun clearSession() {
        withContext(Dispatchers.IO) {
            prefs.edit().clear().apply()
        }
    }
}
