package com.pahebatcher.app.data.session

import android.content.Context
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.domain.session.SessionPersistence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §3.3 regression test (DECISIONS 157): expired-session recovery empties the
 * WebView cookie store BEFORE the forced re-establishment.
 *
 * A `cf_clearance` can be stored in several DIFFERENT cookie tuples — host-only,
 * `Domain=`-scoped, `Secure`/`SameSite`-attributed — and a cookie CREATED BY THE
 * WEBVIEW (the real challenge-issued one) was proven on device (2026-10-19, live
 * Stage-E cookie) to match NO `setCookie` deletion tuple at all while still being
 * visible to `getCookie`. The recovery loop's presence-only check
 * (`cookies.contains("cf_clearance")`) keeps passing on any survivor and reports a
 * false success (review finding 12 + live evidence). Production therefore uses the
 * store-wide `removeAllCookies` reset; this suite pins it against the REAL
 * `android.webkit.CookieManager` — no network, no WebView page, no challenge:
 * after the clearing NOTHING (any storage form, any creator) remains on either
 * AnimePahe host, and a fresh solve can re-seed the store and be cleared again.
 */
@RunWith(AndroidJUnit4::class)
class StaleClearanceCookieClearingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cookieManager = CookieManager.getInstance()
    private val session = AndroidCloudflareSession(
        context,
        object : SessionPersistence {
            override suspend fun saveSession(session: SessionPersistence.PersistedSession) = Unit
            override suspend fun loadSession(): SessionPersistence.PersistedSession? = null
            override suspend fun hasSession(): Boolean = false
            override suspend fun clearSession() = Unit
        },
    )

    private fun seedAllForms() {
        // Host-only form on both hosts (no Domain attribute → its own tuple).
        cookieManager.setCookie("https://animepahe.com", "cf_clearance=hostonly-com; Path=/")
        cookieManager.setCookie("https://animepahe.pw", "cf_clearance=hostonly-pw; Path=/")
        // Domain-scoped form on both hosts (a different cookie tuple each).
        cookieManager.setCookie(
            "https://animepahe.com",
            "cf_clearance=domain-com; Path=/; Domain=animepahe.com",
        )
        cookieManager.setCookie(
            "https://animepahe.pw",
            "cf_clearance=domain-pw; Path=/; Domain=animepahe.pw",
        )
        // The `__ddg1_` challenge identifier: seeded to prove the reset removes
        // it too — the fresh re-establishment re-creates it, and any old cookie
        // surviving would defeat the reset.
        cookieManager.setCookie("https://animepahe.com", "__ddg1_=challenge-id-com; Path=/")
        cookieManager.setCookie("https://animepahe.pw", "__ddg1_=challenge-id-pw; Path=/")
        // flush() forces the async cookie store to settle before reading back.
        cookieManager.flush()
    }

    private fun clearanceVisible(host: String): Boolean =
        cookieManager.getCookie("https://$host")?.contains("cf_clearance") ?: false

    @Before
    fun seed() {
        seedAllForms()
        // Loud precondition: both hosts actually show a cf_clearance before the
        // clearing runs — a store that silently rejected the seeds would make
        // every assertion below pass vacuously.
        assertTrue(
            "precondition: cf_clearance visible on animepahe.com before clearing",
            clearanceVisible("animepahe.com"),
        )
        assertTrue(
            "precondition: cf_clearance visible on animepahe.pw before clearing",
            clearanceVisible("animepahe.pw"),
        )
    }

    @After
    fun cleanup() {
        // The reset empties the store; nothing else needs explicit expiry.
        runBlocking { session.clearStaleClearanceCookie() }
    }

    @Test
    fun clearStaleClearanceCookie_emptiesTheStore_forAFreshSolve() {
        runBlocking { session.clearStaleClearanceCookie() }
        cookieManager.flush()

        val com = cookieManager.getCookie("https://animepahe.com") ?: ""
        val pw = cookieManager.getCookie("https://animepahe.pw") ?: ""
        assertFalse("no cf_clearance survives on .com", com.contains("cf_clearance"))
        assertFalse("no cf_clearance survives on .pw", pw.contains("cf_clearance"))
        assertFalse(
            "the `__ddg1_` challenge identifier is reset too — the fresh " +
                "re-establishment run re-creates every cookie the flow needs, and " +
                "leaving ANY old cookie would defeat the reset",
            com.contains("__ddg1_"),
        )
        assertFalse("…on .pw as well", pw.contains("__ddg1_"))
    }

    @Test
    fun clearStaleClearanceCookie_clearsTheNextStaleFormAfterAFreshSolve() {
        runBlocking { session.clearStaleClearanceCookie() }
        cookieManager.flush()
        assertFalse("first clearing removes the stale forms", clearanceVisible("animepahe.com"))

        // A fresh solve after recovery writes clearance again; the NEXT recovery
        // cycle must clear the NEW cookie too — otherwise the presence-only loop
        // check keeps passing on it and the recovery reports false success.
        seedAllForms()
        assertTrue("re-seeded clearance is visible again", clearanceVisible("animepahe.com"))
        runBlocking { session.clearStaleClearanceCookie() }
        cookieManager.flush()
        assertFalse(
            "a second clearing removes the re-seeded form too (loop termination)",
            clearanceVisible("animepahe.com"),
        )
        assertFalse("…and on .pw", clearanceVisible("animepahe.pw"))
    }
}
