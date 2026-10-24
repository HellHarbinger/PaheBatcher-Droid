package com.pahebatcher.app.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded in-memory log retention backing the diagnostics Console,
 * extended by Phase 12B for the retention GATE (DECISIONS 86): while
 * `Show Console` is OFF nothing is retained at all.
 *
 * These tests pin the contract that makes the Console safe to ship:
 * fixed capacity, per-message truncation, immutable snapshots, a real clear(),
 * JVM-safe logging through [AppLog] (android.util.Log is stubbed to no-op by
 * `unitTests.isReturnDefaultValues`), and the buffering gate itself.
 */
class LogBufferTest {

    /** Whatever the compiled variant's diagnostic default was before the test. */
    private var rememberedDiagnostics = true

    /**
     * The gate defaults to OFF (Show Console is opt-in), so the facade tests
     * opt in explicitly — the same call the production collector in
     * `PaheBatcherApplication` makes when the persisted flag is ON.
     *
     * The DIAGNOSTIC gate (DEBUG/INFO) is opened explicitly too: these cases are
     * about the retention machinery, and they must behave identically whether
     * they run under `testDebugUnitTest` or `testReleaseUnitTest` (whose
     * `BuildConfig.DEBUG = false` default would otherwise suppress the INFO
     * probes). Which default is real is pinned elsewhere —
     * `AppLogDiagnosticsGateTest` (coupling) and `AppLogReleaseVariantTest`
     * (release default) — so nothing is lost by making the fixture explicit.
     */
    @Before
    fun enableBufferingAndDiagnostics() {
        rememberedDiagnostics = AppLog.isDiagnosticLoggingEnabled
        AppLog.setDiagnosticLoggingEnabled(true)
        AppLog.setBufferingEnabled(true)
    }

    /**
     * Leave the process-wide gates exactly as the defaults find them: Show
     * Console OFF, and the diagnostic gate at whatever this variant compiled.
     */
    @After
    fun restoreGates() {
        AppLog.setBufferingEnabled(false)
        AppLog.setDiagnosticLoggingEnabled(rememberedDiagnostics)
    }

    @Test
    fun `appends entries in sequence order`() {
        val buffer = LogBuffer(capacity = 8)
        buffer.append(LogLevel.INFO, "A", "first")
        buffer.append(LogLevel.WARN, "B", "second")

        val entries = buffer.entries.value
        assertEquals(2, entries.size)
        assertEquals("first", entries[0].message)
        assertEquals("second", entries[1].message)
        assertTrue("sequence numbers must increase", entries[1].sequence > entries[0].sequence)
        assertEquals(2, buffer.size())
    }

    @Test
    fun `capacity is a hard bound - oldest entries are evicted`() {
        val buffer = LogBuffer(capacity = 3)
        repeat(5) { buffer.append(LogLevel.DEBUG, "T", "msg-$it") }

        val entries = buffer.entries.value
        assertEquals("retention is O(capacity)", 3, entries.size)
        assertEquals(3, buffer.size())
        assertEquals("oldest entries evicted first", listOf("msg-2", "msg-3", "msg-4"), entries.map { it.message })
    }

    @Test
    fun `oversized messages are truncated`() {
        val buffer = LogBuffer(capacity = 4)
        val huge = "x".repeat(LogBuffer.MAX_MESSAGE_CHARS * 3)
        val stored = buffer.append(LogLevel.ERROR, "T", huge)

        assertTrue(
            "message must be truncated to the bound",
            stored.message.length <= LogBuffer.MAX_MESSAGE_CHARS + "…(truncated)".length,
        )
        assertTrue(stored.message.endsWith("…(truncated)"))
    }

    @Test
    fun `oversized throwables are truncated to the same bound - reviewer M1`() {
        val buffer = LogBuffer(capacity = 4)
        val hugeTrace = "java.lang.IllegalStateException: deep\n\tat frame\n"
            .repeat(LogBuffer.MAX_THROWABLE_CHARS)
        val stored = buffer.append(LogLevel.ERROR, "T", "boom", throwable = hugeTrace)

        assertEquals("a throwable was stored", true, stored.throwable != null)
        val trace = stored.throwable.orEmpty()
        assertTrue(
            "rendered stack traces must be bounded too (unbounded traces would pin memory)",
            trace.length <= LogBuffer.MAX_THROWABLE_CHARS + "…(truncated)".length,
        )
        assertTrue(trace.endsWith("…(truncated)"))
        assertEquals(
            "the message field keeps its own independent bound",
            "boom",
            stored.message,
        )
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = LogBuffer(capacity = 4)
        buffer.append(LogLevel.INFO, "T", "one")
        buffer.append(LogLevel.INFO, "T", "two")
        assertEquals(2, buffer.size())

        buffer.clear()

        assertEquals(0, buffer.size())
        assertTrue(buffer.entries.value.isEmpty())
    }

    @Test
    fun `entries snapshot is immutable for collectors`() {
        val buffer = LogBuffer(capacity = 4)
        buffer.append(LogLevel.INFO, "T", "one")
        val snapshot = buffer.entries.value

        buffer.append(LogLevel.INFO, "T", "two")

        assertEquals("old snapshot is never mutated", 1, snapshot.size)
        assertEquals("new snapshot has both", 2, buffer.entries.value.size)
    }

    @Test
    fun `throwable is rendered next to the message`() {
        val buffer = LogBuffer(capacity = 4)
        val stored = buffer.append(LogLevel.ERROR, "T", "boom", throwable = "java.lang.IllegalStateException: nope")

        assertEquals("java.lang.IllegalStateException: nope", stored.throwable)
    }

    @Test
    fun `AppLog feeds both sinks - logcat call plus buffer entry`() {
        val before = AppLog.buffer.size()

        AppLog.i("LogBufferTest", "through the facade")
        AppLog.w("LogBufferTest", "careful")
        AppLog.e("LogBufferTest", "broken")
        AppLog.d("LogBufferTest", "detail")

        val entries = AppLog.buffer.entries.value
        assertEquals(before + 4, entries.size)
        val mine = entries.takeLast(4)
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG), mine.map { it.level })
        assertTrue(mine.all { it.tag == "LogBufferTest" })
    }

    @Test
    fun `AppLog throwable overload stores the rendered trace`() {
        AppLog.e("LogBufferTest", "with cause", IllegalStateException("bad state"))
        val last = AppLog.buffer.entries.value.last()
        assertEquals("with cause", last.message)
        assertTrue(last.throwable!!.contains("IllegalStateException: bad state"))
        assertFalse(last.throwable!!.isBlank())
    }

    // ── Phase 12B: the retention gate (DECISIONS 86) ───────────────────────

    @Test
    fun `gate off drops future lines and clears retention`() {
        AppLog.i("LogBufferTest", "retained-while-on")
        assertTrue(AppLog.buffer.entries.value.any { it.message == "retained-while-on" })

        AppLog.setBufferingEnabled(false)
        assertFalse(AppLog.isBufferingEnabled)
        assertTrue(
            "turning Show Console off drops the buffer's retention",
            AppLog.buffer.entries.value.none { it.message == "retained-while-on" },
        )

        AppLog.i("LogBufferTest", "dropped-while-off")
        assertTrue(
            "while Show Console is OFF no line may be retained at all",
            AppLog.buffer.entries.value.none { it.message == "dropped-while-off" },
        )
        assertEquals("the disabled buffer retains nothing", 0, AppLog.buffer.size())
    }

    @Test
    fun `re-enabling the gate starts a fresh buffer`() {
        AppLog.setBufferingEnabled(false)
        AppLog.i("LogBufferTest", "before-re-enable")
        AppLog.setBufferingEnabled(true)

        assertTrue(
            "lines dropped while OFF are never resurrected by enabling the gate",
            AppLog.buffer.entries.value.none { it.message == "before-re-enable" },
        )

        AppLog.i("LogBufferTest", "after-re-enable")
        assertTrue(AppLog.buffer.entries.value.any { it.message == "after-re-enable" })
    }

    @Test
    fun `a disable never races an in-flight append - the disabled buffer stays empty`() {
        AppLog.setBufferingEnabled(true)
        val logging = AtomicBoolean(true)
        val racer = Thread {
            var n = 0
            while (logging.get()) {
                AppLog.i("LogBufferTest", "racing-${n++}")
            }
        }
        racer.start()
        try {
            // Let appends pile up, then disable WHILE the logger is running.
            Thread.sleep(50)
            AppLog.setBufferingEnabled(false)

            // Once setBufferingEnabled(false) returns, the gate+append critical
            // section is closed: no in-flight append may land afterwards.
            assertEquals(
                "the disabled buffer retains nothing, even with concurrent loggers",
                0,
                AppLog.buffer.size(),
            )
            AppLog.i("LogBufferTest", "logged-after-disable")
            assertEquals(0, AppLog.buffer.size())
        } finally {
            logging.set(false)
            racer.join(5_000)
        }
        assertEquals(
            "quiescence changes nothing: the gate stays closed until re-enabled",
            0,
            AppLog.buffer.size(),
        )
    }
}
