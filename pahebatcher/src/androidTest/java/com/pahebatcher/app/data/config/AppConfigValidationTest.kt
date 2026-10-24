package com.pahebatcher.app.data.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.domain.model.ThemeSettings
import com.pahebatcher.app.support.SettingsTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The persisted configuration is VALIDATED the way the class
 * documents it, on the real DataStore (the JVM cannot host DataStore reads, so
 * this behavior is pinned on device).
 *
 * Contract under test:
 *
 * 1. a fresh store (or a reset one) yields the Python reference defaults from
 *    `config_manager.py` — quality 1080, max_parallel 2,
 *    hls_workers 24, cache_ttl 60, keep_temp false, auto_retry 2, and
 *    `show_console` OFF (Android-only, opt-in). There is NO audio default:
 *    `audio_lang` stopped being a configuration key with the audio-variant
 *    slice (SPEC §22/§23 — pinned by `audioIsNoLongerAConfigurationKey`);
 * 2. out-of-range writes are CLAMPED to the published ranges rather than stored
 *    raw (deliberate Android-vs-Python difference: Python raises ValueError —
 *    Android never fails the write);
 * 3. invalid enum-ish values fall back to the Python default instead of being
 *    persisted as an unreadable value;
 * 4. updating one key never disturbs another (independent keys, one store).
 *
 * Every test restores the documented defaults afterwards so the ordering-
 * dependent suites (SettingsContentTest's exact-text-set, ConcurrencyTest's
 * maxParallel gate, DownloadWorkerConfigTest's persisted snapshot) keep their
 * deterministic preconditions.
 */
@RunWith(AndroidJUnit4::class)
class AppConfigValidationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appConfig by lazy { AppConfig(context) }

    /**
     * State captured BEFORE the suite mutates anything. `aFullReset…` clears the
     * WHOLE file (theme keys and the notification-permission latch included),
     * so these are written back afterwards — the suite must never leave shared
     * device state mutated (AGENTS §8; reviewer M5).
     */
    private var themeBefore: ThemeSettings? = null
    private var notificationAskedBefore: Boolean = false
    private var notificationGrantedBefore: Boolean = false

    @Before
    fun setUp() {
        runBlocking {
            themeBefore = appConfig.themeSettings.first()
            notificationAskedBefore = appConfig
                .getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, false).first()
            notificationGrantedBefore = appConfig
                .getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, false).first()
        }
        SettingsTestSupport.resetToDefaults(context)
        runBlocking {
            // resolve_ahead is not written by SettingsTestSupport; restore the
            // documented default so the clamp test starts from a known value.
            appConfig.updateResolveAhead(999)
        }
    }

    @After
    fun tearDown() {
        SettingsTestSupport.resetToDefaults(context)
        runBlocking {
            appConfig.updateResolveAhead(999)
            themeBefore?.let {
                appConfig.updateThemeMode(it.themeMode)
                appConfig.updateDynamicColor(it.dynamicColor)
            }
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, notificationAskedBefore)
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, notificationGrantedBefore)
        }
    }

    private fun current() = runBlocking { appConfig.currentAppContext() }

    @Test
    fun everyEngineKeyRestoresItsPythonReferenceDefault() = runBlocking {
        // SettingsTestSupport has just written every documented default; the
        // ABSENT-key path (a genuinely fresh store) is proven by
        // aFullResetReturnsEveryEngineKeyToItsDefault below, which clears the
        // whole file and reads the code defaults back.
        val ctx = current()
        assertEquals(1080, ctx.quality)
        assertEquals(2, ctx.maxParallel)
        assertEquals(24, ctx.hlsWorkers)
        assertEquals(60, ctx.cacheTtl)
        assertFalse(ctx.keepTemp)
        assertEquals(2, ctx.autoRetry)
        assertFalse("the diagnostics Console is opt-in", appConfig.showConsole.first())
    }

    @Test
    fun numericSettingsClampToTheirPublishedRanges() = runBlocking {
        appConfig.updateMaxParallel(99)
        assertEquals("max_parallel upper bound", 6, current().maxParallel)
        appConfig.updateMaxParallel(0)
        assertEquals("max_parallel lower bound", 1, current().maxParallel)

        appConfig.updateHlsWorkers(1)
        assertEquals("hls_workers lower bound", 8, current().hlsWorkers)
        appConfig.updateHlsWorkers(999)
        assertEquals("hls_workers upper bound", 32, current().hlsWorkers)

        appConfig.updateCacheTtl(-5)
        assertEquals("cache_ttl is never negative", 0, current().cacheTtl)

        appConfig.updateAutoRetry(5)
        assertEquals("auto_retry upper bound", 2, current().autoRetry)
        appConfig.updateAutoRetry(-1)
        assertEquals("auto_retry lower bound", 0, current().autoRetry)

        appConfig.updateResolveAhead(-3)
        assertEquals("resolve_ahead is never negative (deferred key, still validated)", 0, current().resolveAhead)
    }

    @Test
    fun invalidEnumValuesFallBackToThePythonDefault() = runBlocking {
        appConfig.updateQuality(480)
        assertEquals("an unknown quality never persists raw", 1080, current().quality)

        appConfig.updateQuality(720)
        assertEquals("a valid quality is kept", 720, current().quality)
    }

    @Test
    fun updatingOneKeyNeverDisturbsAnother() = runBlocking {
        appConfig.updateQuality(720)
        appConfig.updateMaxParallel(5)
        appConfig.updateHlsWorkers(16)
        appConfig.updateCacheTtl(30)
        appConfig.updateKeepTemp(true)
        appConfig.updateAutoRetry(1)

        // A later write to a DIFFERENT key (including the Android-only
        // diagnostics flag) must leave all of the above intact.
        appConfig.updateShowConsole(true)

        val ctx = current()
        assertEquals(720, ctx.quality)
        assertEquals(5, ctx.maxParallel)
        assertEquals(16, ctx.hlsWorkers)
        assertEquals(30, ctx.cacheTtl)
        assertEquals(true, ctx.keepTemp)
        assertEquals(1, ctx.autoRetry)
        assertEquals(true, appConfig.showConsole.first())
    }

    @Test
    fun aFullResetReturnsEveryEngineKeyToItsDefault() = runBlocking {
        appConfig.updateQuality(360)
        appConfig.updateMaxParallel(6)
        appConfig.updateHlsWorkers(8)
        appConfig.updateCacheTtl(0)
        appConfig.updateKeepTemp(true)
        appConfig.updateAutoRetry(0)
        appConfig.updateShowConsole(true)

        appConfig.resetToDefaults()

        val ctx = current()
        assertEquals(1080, ctx.quality)
        assertEquals(2, ctx.maxParallel)
        assertEquals(24, ctx.hlsWorkers)
        assertEquals(60, ctx.cacheTtl)
        assertFalse(ctx.keepTemp)
        assertEquals(2, ctx.autoRetry)
        assertFalse("reset turns the opt-in Console back off", appConfig.showConsole.first())
        assertEquals(
            "an absent key resolves to the code default (fresh-install state)",
            999,
            ctx.resolveAhead,
        )
    }

    @Test
    fun audioIsNoLongerAConfigurationKey() = runBlocking {
        // INVERTED, not deleted (SPEC §22/§23): audio_lang used to be engine
        // config (validated to jpn/eng, defaulted to jpn). The preference, its
        // validator and the AppContext field are gone; a value a previous
        // install persisted under `audio_lang` stays in the file, unread, and
        // there is no writer left that could refresh it.
        val contextFields = com.pahebatcher.app.domain.model.AppContext::class.java
            .declaredFields
            .map { it.name }
        assertFalse(
            "AppContext must carry no audio preference field",
            contextFields.any { it.contains("audio", ignoreCase = true) },
        )

        val configWriters = AppConfig::class.java.methods.map { it.name }
        assertFalse(
            "AppConfig must expose no audio writer (updateAudioLang is gone)",
            configWriters.any { it.startsWith("update") && it.contains("Audio", ignoreCase = true) },
        )

        // The defaults that DO remain are still the reference defaults — the
        // removal touched audio only.
        val ctx = current()
        assertEquals(1080, ctx.quality)
        assertEquals(2, ctx.maxParallel)
    }
}
