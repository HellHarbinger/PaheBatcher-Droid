package com.pahebatcher.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGENTS §10 ("never log … session tokens … credentials") and AppLog §M7
 * ("prefer the host + path prefix over a full signed URL") — the sanitizer that
 * stands between an expiring stream URL and a log sink.
 *
 * Each case pins one half of that contract: what must SURVIVE (host + path, so
 * the failure is still diagnosable) and what must NEVER survive (query string,
 * fragment, userinfo, and the original text of anything unparseable).
 */
class LogUrlSanitizerTest {

    // ── what survives ─────────────────────────────────────────────────────

    @Test
    fun `host and path survive so the failure stays diagnosable`() {
        assertEquals(
            "vault-13.owocdn.top/stream/./uwu.m3u8",
            LogUrlSanitizer.url("https://vault-13.owocdn.top/stream/./uwu.m3u8"),
        )
    }

    @Test
    fun `a port survives because it identifies the endpoint`() {
        assertEquals(
            "127.0.0.1:8080/playlist.m3u8",
            LogUrlSanitizer.url("http://127.0.0.1:8080/playlist.m3u8"),
        )
    }

    @Test
    fun `a scheme-less host with no path still reports its host`() {
        assertEquals("example.com", LogUrlSanitizer.url("https://example.com"))
    }

    // ── what must never survive ───────────────────────────────────────────

    @Test
    fun `query parameters are removed`() {
        val logged = LogUrlSanitizer.url(
            "https://cdn.example/stream/uwu.m3u8?token=abc123&expires=1793529600",
        )
        assertEquals("cdn.example/stream/uwu.m3u8", logged)
        assertFalse("no query separator may survive", logged.contains("?"))
        assertFalse("no query key may survive", logged.contains("token"))
        assertFalse("no query value may survive", logged.contains("abc123"))
        assertFalse("no expiry may survive", logged.contains("1793529600"))
    }

    @Test
    fun `credentials in userinfo are removed`() {
        val logged = LogUrlSanitizer.url("https://user:pass@cdn.example/seg0.ts?sig=zzz")
        assertEquals("cdn.example/seg0.ts", logged)
        assertFalse("neither the user nor the password may survive", logged.contains("pass"))
        assertFalse("…nor the signature", logged.contains("zzz"))
    }

    @Test
    fun `fragments are removed`() {
        val logged = LogUrlSanitizer.url("https://cdn.example/playlist.m3u8#session-token")
        assertEquals("cdn.example/playlist.m3u8", logged)
        assertFalse(logged.contains("#"))
        assertFalse(logged.contains("session-token"))
    }

    @Test
    fun `a path long enough to carry a token is bounded`() {
        val path = "/" + "a".repeat(LogUrlSanitizer.MAX_PATH_CHARS + 50)
        val logged = LogUrlSanitizer.url("https://cdn.example$path")
        assertTrue(logged.length <= "cdn.example".length + LogUrlSanitizer.MAX_PATH_CHARS + 1)
        assertTrue("the retained prefix keeps the diagnostic value", logged.startsWith("cdn.example/"))
        assertFalse("the tail of the path is dropped", logged.endsWith("a"))
    }

    // ── fail safe ─────────────────────────────────────────────────────────

    @Test
    fun `a malformed url fails safe without echoing the original`() {
        val secrets = "ht tp://cdn.example/seg?token=SUPERSECRET"
        val logged = LogUrlSanitizer.url(secrets)
        assertEquals(LogUrlSanitizer.UNPARSEABLE, logged)
        assertFalse("the malformed input must not be echoed", logged.contains("SUPERSECRET"))
        assertFalse(logged.contains("cdn.example"))
    }

    @Test
    fun `a relative url fails safe rather than leaking its query`() {
        val logged = LogUrlSanitizer.url("/stream/seg0.ts?token=SUPERSECRET")
        assertEquals(LogUrlSanitizer.UNPARSEABLE, logged)
        assertFalse(logged.contains("SUPERSECRET"))
    }

    @Test
    fun `blank and null inputs are reported as such`() {
        assertEquals(LogUrlSanitizer.NO_URL, LogUrlSanitizer.url(null))
        assertEquals(LogUrlSanitizer.NO_URL, LogUrlSanitizer.url(""))
        assertEquals(LogUrlSanitizer.NO_URL, LogUrlSanitizer.url("   "))
    }

    // ── free text (exception messages, HTML snippets) ─────────────────────

    @Test
    fun `a url quoted inside free text is rewritten too`() {
        val logged = LogUrlSanitizer.message(
            "Failed to connect to https://cdn.example/seg0.ts?token=SUPERSECRET: refused",
        )
        assertEquals("Failed to connect to cdn.example/seg0.ts: refused", logged)
        assertFalse(logged.contains("SUPERSECRET"))
    }

    @Test
    fun `free text without a url is returned unchanged`() {
        val text = "Read timed out after 60000ms"
        assertEquals(text, LogUrlSanitizer.message(text))
    }

    @Test
    fun `a null message renders exactly like the template it replaced`() {
        assertEquals("null", LogUrlSanitizer.message(null))
    }

    @Test
    fun `a url in a multi-url message is rewritten at every occurrence`() {
        val logged = LogUrlSanitizer.message(
            "tried https://a.example/x.m3u8?t=1 then https://b.example/y.m3u8?t=2",
        )
        assertEquals("tried a.example/x.m3u8 then b.example/y.m3u8", logged)
        assertFalse(logged.contains("t=1"))
        assertFalse(logged.contains("t=2"))
    }

    //  diagnostic(): parser/exception detail on WARN/ERROR paths  (DECISIONS 138)

    @Test
    fun `diagnostic strips a scheme-less relative's query that message would leave alone`() {
        assertEquals(
            "Illegal character in path at index 3: seg 0.ts",
            LogUrlSanitizer.diagnostic(
                "Illegal character in path at index 3: seg 0.ts?token=SECRET&expires=1793529600",
            ),
        )
    }

    @Test
    fun `diagnostic rewrites the parseable prefix and still strips the broken url's tail`() {
        // A URL with an ILLEGAL character stops the scheme match at that
        // character — everything after it (query + fragment included) is
        // outside any rewrite, which is exactly what `diagnostic` exists for.
        val out = LogUrlSanitizer.diagnostic(
            "failed https://cdn.example.com/vault dir/f.m3u8?token=SECRET#frag at index 29",
        )
        assertTrue("host survives: $out", out.contains("cdn.example.com/vault"))
        assertTrue("path survives: $out", out.contains("dir/f.m3u8"))
        assertFalse(out.contains("?"))
        assertFalse(out.contains("#"))
        assertFalse(out.contains("SECRET"))
    }

    @Test
    fun `diagnostic never edits prose that merely contains a question mark`() {
        assertEquals(
            "retry? yes later",
            LogUrlSanitizer.diagnostic("retry? yes later"),
        )
    }

    @Test
    fun `diagnostic of null renders exactly like message did`() {
        assertEquals("null", LogUrlSanitizer.diagnostic(null))
    }
}
