package com.pahebatcher.app.domain.hls

import com.pahebatcher.app.logging.AppLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding A of the final closure round (DECISIONS 138): the WARN inside
 * `HlsPlaylistParser.resolveUrl` used to interpolate the raw `baseUrl` —
 * which is the expiring CDN playlist URL, authorisation in its query — plus
 * the parser's own message, which EMBEDS the input the URI parser choked on.
 * WARN is un-gated in release (AppLog `emit()`), so that line was
 * release-reachable.
 *
 * Drives the real failure through the real parser: a segment reference that
 * `URI.resolve` rejects (illegal character), against a base URL carrying
 * credentials, a query token and a fragment. The assertion is on what the log
 * SINK actually retained (`AppLog.buffer`, fed by the same call that feeds
 * Logcat) — the emitted line must still say WHICH object failed (host + path)
 * and must carry no query, fragment, credentials or token, while the parse
 * itself keeps its fallback behaviour.
 *
 * AGENTS §10 / DECISIONS 136 / AppLog §M7.
 */
class HlsPlaylistParserUrlLogSanitizationTest {

    private var bufferingWasEnabled = false

    @Before
    fun openBuffer() {
        bufferingWasEnabled = AppLog.isBufferingEnabled
        AppLog.setBufferingEnabled(true)
        AppLog.buffer.clear()
    }

    @After
    fun restoreGate() {
        AppLog.setBufferingEnabled(bufferingWasEnabled)
        AppLog.buffer.clear()
    }

    /** The WARN line this class provoked, or null when nothing was logged. */
    private fun resolutionWarning() =
        AppLog.buffer.entries.value.lastOrNull {
            it.tag == "HlsPlaylistParser" && it.message.contains("URL resolution failed")
        }

    /** No retained line, message or rendered throwable, may carry a secret. */
    private fun assertNeverLogged(secret: String) {
        val leaked = AppLog.buffer.entries.value.filter {
            it.message.contains(secret) || it.throwable.orEmpty().contains(secret)
        }
        assertTrue("$secret must never reach a log sink (leaked in $leaked)", leaked.isEmpty())
    }

    @Test
    fun `a URI resolution failure logs host and path but never the query fragment or credentials`() {
        val baseUrl =
            "https://cdnuser:cdnpass@cdn.example.com/vault/dir/master.m3u8" +
                "?token=SUPERSECRET&expires=1793529600#frag"
        val playlist =
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg 0.ts?token=SEGSECRET&expires=1793529600
            """.trimIndent()

        // The malformed segment reference drives URI.resolve into its catch.
        val parsed = HlsPlaylistParser.parse(playlist, baseUrl)

        // Parsing behaviour itself is unchanged: the fallback kept the segment.
        assertEquals("the fallback must still yield the segment", 1, parsed.segments.size)

        val line = resolutionWarning()
        assertNotNull("the resolution failure must have logged", line)
        val message = line!!.message

        // Useful diagnosis survives: the WHICH-object half of the line.
        assertTrue("host+path must survive: $message", message.contains("cdn.example.com"))
        assertTrue("host+path must survive: $message", message.contains("/vault/dir/master.m3u8"))
        assertTrue("the relative segment line must survive: $message", message.contains("seg 0.ts"))

        // …and NOTHING that authenticates or identifies a fragment does.
        assertFalse("no query marker: $message", message.contains("?"))
        assertFalse("no fragment marker: $message", message.contains("#"))
        assertFalse("no query parameter names: $message", message.contains("token="))
        assertFalse("no expiry: $message", message.contains("expires"))
        assertFalse("no query value: $message", message.contains("1793529600"))
        assertFalse("no base-URL token: $message", message.contains("SUPERSECRET"))
        assertFalse("no segment token: $message", message.contains("SEGSECRET"))
        assertFalse("no credentials: $message", message.contains("cdnpass"))
        assertFalse("no userinfo: $message", message.contains("cdnuser"))

        assertNeverLogged("SUPERSECRET")
        assertNeverLogged("SEGSECRET")
        assertNeverLogged("cdnpass")
    }
}
