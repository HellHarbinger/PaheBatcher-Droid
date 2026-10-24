package com.pahebatcher.app.logging

import java.io.IOException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding C of the final closure round (DECISIONS 138): a Throwable logged at
 * WARN/ERROR could reintroduce a complete request URL AFTER the call-site
 * sanitization, because `stackTraceToString` reproduces the throwable's own
 * `toString()` line and every `Caused by:` message verbatim — and WARN/ERROR
 * are the levels that still emit in release.
 *
 * The fix is central (`AppLog.renderThrowable` → `LogUrlSanitizer.diagnostic`),
 * so these tests pin the shared rendering itself: top-level message, nested
 * cause, frame metadata, null message, and the ordinary exception that must
 * keep its diagnostics untouched. Assertions run on the observable sink
 * (`AppLog.buffer`, fed by the same `renderThrowable` value that is handed to
 * Logcat), never on the sanitizer method in isolation.
 *
 * AGENTS §10 / DECISIONS 136 / AppLog §M7.
 */
class AppLogThrowableSanitizationTest {

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

    private fun onlyLine(): LogEntry {
        val entries = AppLog.buffer.entries.value
        assertTrue("expected exactly one retained line, got $entries", entries.size == 1)
        return entries.first()
    }

    @Test
    fun `an ERROR whose throwable quotes a complete URL is sanitized before the sink`() {
        AppLog.e(
            "ThrowableSanitization",
            "segment fetch failed",
            IOException(
                "GET https://api.example.com/v1/stream.m3u8?token=SUPERSECRET&expires=1793529600 " +
                    "returned 403",
            ),
        )

        val line = onlyLine()
        val rendered = line.throwable
        assertNotNull("the throwable overload must retain a rendered trace", rendered)

        // Useful diagnostics survive: type, prose, host+path, a real frame.
        assertTrue("exception type survives: $rendered", rendered!!.contains("IOException"))
        assertTrue("message prose survives: $rendered", rendered.contains("returned 403"))
        assertTrue(
            "host+path survive for diagnosis: $rendered",
            rendered.contains("api.example.com/v1/stream.m3u8"),
        )
        assertTrue("a stack frame survives: $rendered", rendered.contains("at com.pahebatcher.app"))

        // …and nothing that authenticates does.
        assertFalse("no scheme-complete URL: $rendered", rendered.contains("https://"))
        assertFalse("no query parameter: $rendered", rendered.contains("token="))
        assertFalse("no token value: $rendered", rendered.contains("SUPERSECRET"))
        assertFalse("no expiry: $rendered", rendered.contains("expires"))
        assertFalse("no expiry value: $rendered", rendered.contains("1793529600"))

        // The message channel of the same line stays clean as well.
        assertFalse(line.message.contains("SUPERSECRET"))
    }

    @Test
    fun `a nested cause quoting a URL is sanitized in the rendered chain`() {
        val cause = IllegalStateException(
            "inner resolution failed for https://inner.example.com/p?token=INNERSECRET",
        )
        val outer = IOException("outer https://outer.example.com/p?token=OUTERSECRET", cause)
        // Short synthetic stacks: the 4000-char buffer bound (LogBuffer
        // MAX_THROWABLE_CHARS) would otherwise truncate JUnit's reflection
        // frames before the cause chain — the chain is what this case pins.
        outer.stackTrace = arrayOf(
            StackTraceElement("com.pahebatcher.app.Frame", "outerFrame", "Frame.kt", 10),
        )
        cause.stackTrace = arrayOf(
            StackTraceElement("com.pahebatcher.app.Frame", "innerFrame", "Frame.kt", 20),
        )

        AppLog.w("ThrowableSanitization", "warn path", outer)

        val rendered = onlyLine().throwable.orEmpty()
        assertTrue("the cause chain type survives: $rendered", rendered.contains("Caused by"))
        assertTrue("the inner exception type survives: $rendered", rendered.contains("IllegalStateException"))
        assertTrue(
            "outer host+path survive: $rendered",
            rendered.contains("outer.example.com/p"),
        )
        assertTrue(
            "inner host+path survive: $rendered",
            rendered.contains("inner.example.com/p"),
        )
        assertFalse(rendered.contains("OUTERSECRET"))
        assertFalse(rendered.contains("INNERSECRET"))
        assertFalse(rendered.contains("token="))
        assertFalse(rendered.contains("https://"))
    }

    @Test
    fun `a URL hidden in frame metadata is sanitized while the frame itself stays readable`() {
        val exception = RuntimeException("boom")
        exception.stackTrace = arrayOf(
            StackTraceElement(
                "com.pahebatcher.app.Frame",
                "work",
                "https://frames.example.com/f?token=FRAMESECRET",
                42,
            ),
        )

        AppLog.e("ThrowableSanitization", "frame path", exception)

        val rendered = onlyLine().throwable.orEmpty()
        assertTrue("the frame stays readable: $rendered", rendered.contains("Frame.work"))
        assertFalse("no token from frame metadata: $rendered", rendered.contains("FRAMESECRET"))
        assertFalse("no query parameter: $rendered", rendered.contains("token="))
        assertFalse("no scheme-complete URL: $rendered", rendered.contains("https://"))
        assertTrue(
            "host+path survive for diagnosis: $rendered",
            rendered.contains("frames.example.com/f"),
        )
    }

    @Test
    fun `a null throwable message renders safely and keeps the exception type`() {
        AppLog.e("ThrowableSanitization", "null message", IllegalStateException(null as String?))

        val rendered = onlyLine().throwable.orEmpty()
        assertTrue(
            "the exception type must survive a null message: $rendered",
            rendered.contains("IllegalStateException"),
        )
    }

    @Test
    fun `an ordinary exception keeps its message and frames verbatim`() {
        AppLog.e(
            "ThrowableSanitization",
            "plain",
            IllegalStateException("disk full at step 3? retry later"),
        )

        val rendered = onlyLine().throwable.orEmpty()
        assertTrue(
            "prose must not be edited by the sanitizer: $rendered",
            rendered.contains("disk full at step 3? retry later"),
        )
        assertTrue(rendered.contains("IllegalStateException"))
        assertTrue("a real frame survives: $rendered", rendered.contains("at com.pahebatcher.app"))
    }
}
