package com.pahebatcher.app.logging

import com.pahebatcher.app.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SPEC §33 — "have debug logging disabled/restricted in release".
 *
 * `AppLog` owns the ONE diagnostics policy: DEBUG/INFO are gated by the
 * `BuildConfig.DEBUG`-backed switch ([AppLog.isDiagnosticLoggingEnabled]),
 * WARN/ERROR never are, and the gate is consulted BEFORE either sink so a
 * suppressed diagnostic line can reach neither Logcat nor the Console buffer.
 *
 * The diagnostic-variant tests below move the gate through
 * [AppLog.setDiagnosticLoggingEnabled] to exercise the OTHER branch of the
 * compile-time default deterministically — the same pattern [LogBufferTest] uses
 * for the `Show Console` retention gate. Every test restores the gate it found.
 *
 * Which default is real depends on the variant this class is compiled for:
 * `testDebugUnitTest` compiles `BuildConfig.DEBUG = true`, `testReleaseUnitTest`
 * compiles it `false`. [AppLogReleaseVariantTest] pins the release side against
 * the release-compiled AppLog; the "gate default" case below therefore asserts
 * the COUPLING (`gate == BuildConfig.DEBUG`) rather than a hard-coded `true`,
 * which is the property that has to hold in both variants.
 */
class AppLogDiagnosticsGateTest {

    private var rememberedGate = true

    @Before
    fun openBufferAndRememberGate() {
        rememberedGate = AppLog.isDiagnosticLoggingEnabled
        AppLog.setBufferingEnabled(true)
    }

    /** Restore BOTH process-wide gates exactly as they were found. */
    @After
    fun restoreGates() {
        AppLog.setDiagnosticLoggingEnabled(rememberedGate)
        AppLog.setBufferingEnabled(false)
    }

    @Test
    fun `debug build default keeps every level - existing behavior unchanged`() {
        AppLog.setDiagnosticLoggingEnabled(true)

        AppLog.d("DiagnosticsGate", "detail")
        AppLog.i("DiagnosticsGate", "info")
        AppLog.w("DiagnosticsGate", "careful")
        AppLog.e("DiagnosticsGate", "broken")

        val mine = AppLog.buffer.entries.value.takeLast(4)
        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            mine.map { it.level },
        )
    }

    @Test
    fun `release suppresses diagnostic DEBUG and INFO - neither sink sees them`() {
        AppLog.setDiagnosticLoggingEnabled(false)
        val before = AppLog.buffer.size()

        AppLog.d("DiagnosticsGate", "suppressed-detail")
        AppLog.i("DiagnosticsGate", "suppressed-info")

        assertEquals(
            "a suppressed diagnostic line must not reach the Console buffer",
            before,
            AppLog.buffer.size(),
        )
        assertTrue(
            "…and must not be retained even while Show Console is ON",
            AppLog.buffer.entries.value.none {
                it.message == "suppressed-detail" || it.message == "suppressed-info"
            },
        )
    }

    @Test
    fun `release keeps WARN available - both overloads`() {
        AppLog.setDiagnosticLoggingEnabled(false)
        val before = AppLog.buffer.size()

        AppLog.w("DiagnosticsGate", "warn-still-emitted")
        AppLog.w("DiagnosticsGate", "warn-with-cause", IllegalStateException("cause"))

        val mine = AppLog.buffer.entries.value.takeLast(2)
        assertEquals(before + 2, AppLog.buffer.size())
        assertEquals(listOf(LogLevel.WARN, LogLevel.WARN), mine.map { it.level })
        assertEquals("warn-still-emitted", mine[0].message)
        assertEquals("warn-with-cause", mine[1].message)
        assertTrue(mine[1].throwable.orEmpty().contains("IllegalStateException: cause"))
    }

    @Test
    fun `release keeps ERROR available - both overloads`() {
        AppLog.setDiagnosticLoggingEnabled(false)
        val before = AppLog.buffer.size()

        AppLog.e("DiagnosticsGate", "error-still-emitted")
        AppLog.e("DiagnosticsGate", "error-with-cause", IllegalStateException("boom"))

        val mine = AppLog.buffer.entries.value.takeLast(2)
        assertEquals(before + 2, AppLog.buffer.size())
        assertEquals(listOf(LogLevel.ERROR, LogLevel.ERROR), mine.map { it.level })
        assertTrue(mine[1].throwable.orEmpty().contains("IllegalStateException: boom"))
    }

    @Test
    fun `the gate drives both sinks together - warn survives while debug is dropped`() {
        AppLog.setBufferingEnabled(true)
        AppLog.setDiagnosticLoggingEnabled(false)

        AppLog.d("DiagnosticsGate", "dropped")
        AppLog.w("DiagnosticsGate", "kept")

        val mine = AppLog.buffer.entries.value.takeLast(1)
        assertEquals(1, mine.size)
        assertEquals("kept", mine[0].message)
        assertEquals(
            "the buffer must mirror Logcat exactly — never a partial policy",
            LogLevel.WARN,
            mine[0].level,
        )
    }

    @Test
    fun `re-opening the gate resumes diagnostic logging for later lines`() {
        AppLog.setDiagnosticLoggingEnabled(false)
        AppLog.d("DiagnosticsGate", "while-closed")
        AppLog.setDiagnosticLoggingEnabled(true)

        assertTrue(AppLog.isDiagnosticLoggingEnabled)
        assertTrue(
            "a line dropped while closed is never retro-logged",
            AppLog.buffer.entries.value.none { it.message == "while-closed" },
        )

        AppLog.d("DiagnosticsGate", "while-open")
        assertTrue(AppLog.buffer.entries.value.any { it.message == "while-open" })
    }

    @Test
    fun `gate default matches the compiled variant`() {
        // The production default is the compile-time BuildConfig.DEBUG: the debug
        // variant starts open, the release variant starts closed, and the two
        // can never drift apart. Asserted as the coupling so this case holds
        // under BOTH `testDebugUnitTest` and `testReleaseUnitTest` (the release
        // run is the one that proves the closed default is real).
        assertEquals(
            "diagnostic logging defaults to BuildConfig.DEBUG",
            BuildConfig.DEBUG,
            AppLog.isDiagnosticLoggingEnabled,
        )
        assertFalse("sanity: the setter really moves the gate", run {
            AppLog.setDiagnosticLoggingEnabled(false)
            AppLog.isDiagnosticLoggingEnabled
        })
    }
}
