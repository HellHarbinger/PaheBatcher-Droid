package com.pahebatcher.app.data.hls

import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket

/**
 * End-to-end proof that the SANITIZER is really on the WARN/ERROR path of the
 * production HLS client — not merely present in the source.
 *
 * Two real failures are driven through the real [OkHttpHlsClient]:
 *
 *  1. a connection refusal (the ERROR branch), and
 *  2. a real HTTP 404 from a local origin (the WARN branch),
 *
 * each against a URL that carries an expiring authorisation in its query
 * string. The assertion is made on what the log SINK actually retained
 * (`AppLog.buffer`, fed by the same call that feeds Logcat), so a regression
 * that re-inlines `$url` fails here instead of leaking in production.
 *
 * AGENTS §10 / AppLog §M7.
 */
class OkHttpHlsClientLogSanitizationTest {

    private val context = HlsClient.RequestContext(
        userAgent = "SanitizerTest/1.0",
        referer = "https://kwik.example.invalid/page",
        cookies = mapOf("session" to "COOKIE-SECRET"),
    )

    private var bufferingWasEnabled = false

    @Before
    fun captureGateAndOpenBuffer() {
        bufferingWasEnabled = AppLog.isBufferingEnabled
        AppLog.setBufferingEnabled(true)
        AppLog.buffer.clear()
    }

    @After
    fun restoreGate() {
        AppLog.setBufferingEnabled(bufferingWasEnabled)
        AppLog.buffer.clear()
    }

    /** The most recent line this class emitted, or null when nothing was logged. */
    private fun lastClientLine() =
        AppLog.buffer.entries.value.lastOrNull { it.tag == "OkHttpHlsClient" }

    /**
     * Nothing anywhere in the retained buffer — any tag, any level, message or
     * rendered throwable — may carry a value the request authenticated with.
     * Covers the cookie half of AGENTS §10 (the cookie travels in a HEADER, so
     * the only way it could leak is by being logged by hand).
     */
    private fun assertNeverLogged(secret: String) {
        val leaked = AppLog.buffer.entries.value.filter {
            it.message.contains(secret) || it.throwable.orEmpty().contains(secret)
        }
        assertTrue("$secret must never reach a log sink (leaked in $leaked)", leaked.isEmpty())
    }

    @Test
    fun `a connection refusal logs host and path but never the expiring query`() = runBlocking {
        val url = "http://127.0.0.1:1/stream/seg0.ts?token=SUPERSECRET&expires=1793529600"

        val result = OkHttpHlsClient().getText(url, context, timeoutMs = 3_000L)

        assertTrue("the failure must be a network error (got $result)", result is HlsTextResult.NetworkError)

        val line = lastClientLine()
        assertNotNull("the ERROR branch must have logged", line)
        val message = line!!.message
        assertTrue("host must survive for diagnosis: $message", message.contains("127.0.0.1"))
        assertTrue("path must survive for diagnosis: $message", message.contains("/stream/seg0.ts"))
        assertFalse("the query string must never be logged: $message", message.contains("token="))
        assertFalse("…nor its value: $message", message.contains("SUPERSECRET"))
        assertFalse("…nor the expiry: $message", message.contains("1793529600"))
        assertFalse(
            "the rendered stack trace must not carry it either",
            line.throwable.orEmpty().contains("SUPERSECRET"),
        )
        assertNeverLogged("COOKIE-SECRET")
    }

    @Test
    fun `an http error logs host and path but never the expiring query`() {
        val origin = ServerSocket(0).also { socket ->
            Thread({
                try {
                    val connection = socket.accept()
                    connection.use { client ->
                        // Read the request head up to the blank line, then 404.
                        val input = client.getInputStream()
                        val seen = StringBuilder()
                        while (seen.length < 8_192) {
                            val byte = input.read()
                            if (byte == -1) break
                            seen.append(byte.toChar())
                            val text = seen.toString()
                            if (text.endsWith("\r\n\r\n") || text.endsWith("\n\n")) break
                        }
                        val out: OutputStream = client.getOutputStream()
                        out.write(
                            ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(),
                        )
                        out.flush()
                    }
                } catch (_: Exception) {
                    // The assertions below report an origin that never answered.
                }
            }, "sanitizer-404-origin").apply { isDaemon = true }.start()
        }

        try {
            val url = "http://127.0.0.1:${origin.localPort}/playlist.m3u8?token=SUPERSECRET"
            val result = runBlocking { OkHttpHlsClient().getText(url, context, timeoutMs = 10_000L) }

            assertTrue("the origin answered 404 (got $result)", result is HlsTextResult.HttpError)

            val line = lastClientLine()
            assertNotNull("the WARN branch must have logged", line)
            val message = line!!.message
            assertTrue("host must survive for diagnosis: $message", message.contains("127.0.0.1"))
            assertTrue("path must survive for diagnosis: $message", message.contains("/playlist.m3u8"))
            assertFalse("the query string must never be logged: $message", message.contains("token="))
            assertFalse("…nor its value: $message", message.contains("SUPERSECRET"))
            assertNeverLogged("COOKIE-SECRET")
        } finally {
            origin.close()
        }
    }
}
