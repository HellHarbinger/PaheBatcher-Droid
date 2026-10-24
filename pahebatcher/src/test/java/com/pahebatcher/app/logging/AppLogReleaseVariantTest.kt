package com.pahebatcher.app.logging

import com.pahebatcher.app.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SPEC §33 — "have debug logging disabled/restricted in release", executed
 * against the **release-compiled** `AppLog` (`gradlew testReleaseUnitTest`),
 * where `BuildConfig.DEBUG == false` is the real compile-time default rather
 * than a gate a test moved by hand.
 *
 * Why a JVM variant run (and not a release APK on a device): the repository has
 * NO signing configuration and no keystore (`pahebatcher/build.gradle.kts` declares
 * none, and no `*.jks`/`*.keystore` exists), so `assembleRelease` can only
 * produce an UNSIGNED APK that Android refuses to install. This is the
 * repository-sanctioned Option B for closing the gap: run the actual
 * release-variant bytecode. Nothing in production was changed to make this
 * testable — the gate is still the untouched `BuildConfig.DEBUG` default.
 *
 * What this proves, and what it deliberately does not:
 *
 * * `BuildConfig.DEBUG == false` in the release variant, and the gate defaults
 *   to closed because of it (the coupling asserted in [AppLogDiagnosticsGateTest]);
 * * DEBUG/INFO reach NEITHER sink in release — observed here on the Console
 *   buffer, which is fed by the same `emit()` check that guards Logcat, so a
 *   suppressed line cannot reach Logcat "instead";
 * * WARN/ERROR keep emitting in release (both the message and the throwable
 *   overload);
 * * the gate is the DEFAULT, never a hard requirement: a test can still open it,
 *   which is what the existing diagnostic-gate cases exercise.
 *
 * Logcat itself is not observable from a JVM test (`android.util.Log` returns
 * defaults under `unitTests.isReturnDefaultValues`), so the on-device Logcat
 * half of the release claim is NOT made here; only a real install can
 * verify it.
 */
class AppLogReleaseVariantTest {

    private var rememberedGate = true

    @Before
    fun openBufferAndRememberGate() {
        rememberedGate = AppLog.isDiagnosticLoggingEnabled
        AppLog.setBufferingEnabled(true)
        AppLog.buffer.clear()
    }

    @After
    fun restoreGates() {
        AppLog.setDiagnosticLoggingEnabled(rememberedGate)
        AppLog.setBufferingEnabled(false)
        AppLog.buffer.clear()
    }

    @Test
    fun `the release variant compiles the diagnostics gate closed by default`() {
        assertEquals(
            "the gate must be the compile-time BuildConfig.DEBUG, never a hard-coded value",
            BuildConfig.DEBUG,
            AppLog.isDiagnosticLoggingEnabled,
        )
        if (!BuildConfig.DEBUG) {
            assertFalse(
                "a release-compiled AppLog must start with DEBUG/INFO suppressed",
                AppLog.isDiagnosticLoggingEnabled,
            )
        }
    }

    @Test
    fun `debug and info reach neither sink while warn and error keep emitting`() {
        // The branch below is chosen by the COMPILED variant: under
        // `testReleaseUnitTest` it runs against the untouched release default
        // (closed); under `testDebugUnitTest` the same expectations are asserted
        // for the debug default (open), so neither run asserts a vacuous truth.
        val diagnosticsAllowed = BuildConfig.DEBUG

        AppLog.d("ReleaseGate", "diagnostic-detail")
        AppLog.i("ReleaseGate", "diagnostic-info")
        AppLog.w("ReleaseGate", "warn-kept")
        AppLog.e("ReleaseGate", "error-kept")
        AppLog.e("ReleaseGate", "error-kept-with-cause", IllegalStateException("boom"))

        val mine = AppLog.buffer.entries.value
        if (diagnosticsAllowed) {
            assertEquals(
                "debug variant: every level is emitted",
                listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.ERROR),
                mine.map { it.level },
            )
        } else {
            assertEquals(
                "release variant: DEBUG/INFO must reach NEITHER sink",
                listOf(LogLevel.WARN, LogLevel.ERROR, LogLevel.ERROR),
                mine.map { it.level },
            )
            assertTrue(
                "release variant: no diagnostic line may be retained",
                mine.none { it.message.startsWith("diagnostic-") },
            )
        }

        // The release guarantee itself, asserted in BOTH variants.
        assertTrue("WARN must survive in release", mine.any { it.message == "warn-kept" })
        assertTrue("ERROR must survive in release", mine.any { it.message == "error-kept" })
        val withCause = mine.first { it.message == "error-kept-with-cause" }
        assertTrue(
            "the throwable overload must survive in release",
            withCause.throwable.orEmpty().contains("IllegalStateException: boom"),
        )
    }

    @Test
    fun `a suppressed release line is dropped before the sink, not buffered elsewhere`() {
        // Under release this exercises the real closed default; the count check
        // proves the line never landed anywhere, i.e. `emit()` is consulted
        // BEFORE both sinks rather than after Logcat.
        val diagnosticsAllowed = BuildConfig.DEBUG
        AppLog.d("ReleaseGate", "dropped-detail")
        AppLog.i("ReleaseGate", "dropped-info")

        val mine = AppLog.buffer.entries.value
        if (diagnosticsAllowed) {
            assertEquals(2, mine.size)
        } else {
            assertEquals(
                "release: a diagnostic line must leave no trace",
                0,
                mine.size,
            )
        }

        // Retention stays governed by `Show Console`, unchanged by the variant.
        AppLog.buffer.clear() // drop whatever the variant-dependent block above left
        AppLog.w("ReleaseGate", "while-buffering")
        assertEquals(1, AppLog.buffer.entries.value.size)
        AppLog.setBufferingEnabled(false)
        AppLog.w("ReleaseGate", "while-not-buffering")
        assertEquals(
            "turning retention off still drops future lines, in every variant",
            0,
            AppLog.buffer.entries.value.size,
        )
    }

    @Test
    fun `the WARN and ERROR lines that keep emitting in release stay URL-sanitized`() {
        // Final closure round (DECISIONS 138): WARN/ERROR are the levels that
        // SURVIVE the release gate, so their throwable rendering is the
        // release-reachable half of the URL-leak invariant. Asserted here,
        // against the release-compiled AppLog, that the throwable channel is
        // sanitized while both levels still emit — the message-channel half is
        // pinned at the call sites (HlsPlaylistParserUrlLogSanitizationTest,
        // ReResolutionLogSanitizationTest).
        AppLog.w(
            "ReleaseGate",
            "warn detail",
            IllegalStateException("failed https://cdn.example.com/seg.ts?token=RELEASESECRET"),
        )
        AppLog.e(
            "ReleaseGate",
            "error detail",
            IllegalStateException("failed https://cdn.example.com/seg2.ts?token=RELEASESECRET"),
        )

        val mine = AppLog.buffer.entries.value.filter { it.tag == "ReleaseGate" }
        assertEquals("WARN and ERROR must both emit while the gate is closed", 2, mine.size)
        mine.forEach { line ->
            val rendered = line.throwable.orEmpty()
            assertTrue("level ${line.level} must retain a rendered trace", rendered.isNotEmpty())
            assertFalse("no token value: $rendered", rendered.contains("RELEASESECRET"))
            assertFalse("no query parameter: $rendered", rendered.contains("token="))
            assertFalse("no scheme-complete URL: $rendered", rendered.contains("https://"))
            assertTrue(
                "host+path must survive for diagnosis: $rendered",
                rendered.contains("cdn.example.com/seg"),
            )
        }
    }
}
