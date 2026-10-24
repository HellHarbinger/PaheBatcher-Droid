package com.pahebatcher.app.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.R
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.domain.model.ThemeMode
import com.pahebatcher.app.domain.model.ThemeSettings
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.awaitConditionQuietly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §36-11 — theme persistence and first-frame validation (PHASE11_ARCHITECTURE_V2
 * §6/§7/§8, F-17), against the REAL app-singleton [AppConfig] (the same DataStore
 * instance MainActivity collects — never a second DataStore on the same file):
 *
 *  1. missing keys decode to [ThemeSettings.DEFAULT] at the DataStore layer
 *     (fresh install = DARK, dynamic color OFF — `resetToDefaults()` clears all
 *     keys, which is the honest absent-key state; the app is reinstalled between
 *     connected test runs so no other suite observes this wipe);
 *  2. theme mode round-trips system/dark/light through real writes + flow reads;
 *  3. dynamic color round-trips on/off and stays OFF when only the mode is set;
 *  4. `window_background` is the dark first frame (#05070D) in BOTH the `values/`
 *     and `values-night/` qualifiers (the §8 dark-XML-first-frame contract — the
 *     LIGHT-persisted dark first frame is the accepted §8 v1 tradeoff);
 *  5. a launch with absent keys settles DARK, and a launch with LIGHT persisted
 *     settles to the light scheme (real MainActivity, real screen-luminance
 *     sampling of the rendered window).
 *
 * Note on "cold": instrumentation runs IN the app process, so the process cannot
 * be force-stopped from inside its own test run (it would kill the runner). Each
 * leg therefore destroys the previous Activity and launches a fresh one — the
 * full `onCreate` → `collectAsState(initial = DEFAULT)` → DataStore-emission
 * settle path that defines §36-11's "settles to" behavior. Process-death theme
 * behavior is not separable from the runner here (it cannot become
 * user-visible; there is exactly one persistence mechanism).
 */
@RunWith(AndroidJUnit4::class)
class ThemePersistenceTest {

    private lateinit var context: Application
    private lateinit var config: AppConfig

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        config = (context as PaheBatcherApplication).appContainer.appConfig
        // All preference keys ABSENT — the honest fresh-install state.
        runBlocking { config.resetToDefaults() }
    }

    @After
    fun tearDown() {
        // Never leave a persisted LIGHT/dynamic state behind for later suites.
        runBlocking {
            config.updateThemeMode(ThemeMode.DARK)
            config.updateDynamicColor(false)
        }
    }

    // -----------------------------------------------------------------------
    // DataStore round-trip (the instrumentation half of ThemeSettingsTest)
    // -----------------------------------------------------------------------

    @Test
    fun missingKeysYieldTheDefaultDarkTheme() = runBlocking {
        assertEquals(ThemeSettings.DEFAULT, config.themeSettings.first())
        assertEquals(ThemeMode.DARK, config.themeSettings.first().themeMode)
        assertEquals(false, config.themeSettings.first().dynamicColor)
    }

    @Test
    fun themeModeRoundTripsThroughTheRealDataStore() = runBlocking {
        config.updateThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, config.themeSettings.first().themeMode)
        config.updateThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, config.themeSettings.first().themeMode)
        config.updateThemeMode(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, config.themeSettings.first().themeMode)
    }

    @Test
    fun dynamicColorRoundTripsThroughTheRealDataStore() = runBlocking {
        assertEquals(false, config.themeSettings.first().dynamicColor)
        config.updateDynamicColor(true)
        assertEquals(true, config.themeSettings.first().dynamicColor)
        config.updateDynamicColor(false)
        assertEquals(false, config.themeSettings.first().dynamicColor)
    }

    @Test
    fun dynamicColorStaysOffWhenOnlyTheModeIsPersisted() = runBlocking {
        config.updateThemeMode(ThemeMode.LIGHT)
        val settings = config.themeSettings.first()
        assertEquals(ThemeMode.LIGHT, settings.themeMode)
        assertEquals(false, settings.dynamicColor)
    }

    // -----------------------------------------------------------------------
    // §8 dark XML first frame — both uiMode qualifiers
    // -----------------------------------------------------------------------

    @Test
    fun windowBackgroundIsTheDarkFirstFrameInBothUiModeQualifiers() {
        assertEquals(
            "values/ window_background must be the dark first frame",
            0xFF05070D.toInt(),
            colorInUiMode(Configuration.UI_MODE_NIGHT_NO),
        )
        assertEquals(
            "values-night/ window_background must be the dark first frame",
            0xFF05070D.toInt(),
            colorInUiMode(Configuration.UI_MODE_NIGHT_YES),
        )
    }

    // -----------------------------------------------------------------------
    // Real-launch settle behavior (absent keys → DARK; LIGHT persisted → light)
    // -----------------------------------------------------------------------

    @Test
    fun launchWithAbsentKeysSettlesToDark() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitResumed(scenario)
            awaitStableMedianLuma("launch with absent keys settles DARK") {
                it <= DARK_MAX_MEDIAN_LUMA
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun lightPersistedLaunchSettlesToTheLightScheme() {
        runBlocking { config.updateThemeMode(ThemeMode.LIGHT) }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitResumed(scenario)
            // Give the fresh window time to draw (so a stale launcher frame can
            // never satisfy the light predicate), then require the RENDERED
            // window to be light: the dark XML first frame (§8) is transient and
            // the DataStore emission must settle the composition to LIGHT.
            Thread.sleep(1_000)
            awaitStableMedianLuma("LIGHT-persisted launch settles to the light scheme") {
                it >= LIGHT_MIN_MEDIAN_LUMA
            }
        } finally {
            scenario.close()
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Resolves `R.color.window_background` under an explicit uiMode qualifier. */
    private fun colorInUiMode(uiModeNight: Int): Int {
        val cfg = Configuration(context.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or uiModeNight
        val qualified = context.createConfigurationContext(cfg)
        return qualified.resources.getColor(R.color.window_background, null)
    }

    private fun awaitResumed(scenario: ActivityScenario<MainActivity>) {
        assertTrue(
            "MainActivity reaches RESUMED",
            awaitConditionQuietly(10_000) {
                var resumed = false
                scenario.onActivity {
                    resumed = it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                }
                resumed
            },
        )
    }

    /**
     * Polls full-screen screenshots until the median luma of a coarse content
     * grid satisfies [predicate] for [STABLE_SAMPLES] consecutive samples —
     * a single stale frame can never satisfy the assertion.
     */
    private fun awaitStableMedianLuma(description: String, predicate: (Int) -> Boolean): Int {
        var last = Int.MIN_VALUE
        var streak = 0
        val ok = awaitCondition(timeoutMs = 15_000, intervalMs = 500) {
            last = medianContentLuma()
            if (predicate(last)) {
                streak++
                streak >= STABLE_SAMPLES
            } else {
                streak = 0
                false
            }
        }
        assertTrue("$description — last median luma=$last", ok)
        return last
    }

    /** Median perceptual-ish luma (0..255) of a coarse grid over the screen. */
    private fun medianContentLuma(): Int {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: return -1
        val samples = ArrayList<Int>(64)
        try {
            var y = shot.height / 6
            while (y < shot.height * 5 / 6) {
                var x = shot.width / 6
                while (x < shot.width * 5 / 6) {
                    val pixel = shot.getPixel(x, y)
                    samples.add(
                        (Color.red(pixel) * 299 +
                            Color.green(pixel) * 587 +
                            Color.blue(pixel) * 114) / 1000,
                    )
                    x += shot.width / 12
                }
                y += shot.height / 12
            }
        } finally {
            shot.recycle()
        }
        if (samples.isEmpty()) return -1
        samples.sort()
        return samples[samples.size / 2]
    }

    private companion object {
        /** Dark scheme background #05070D has luma ~7; text pixels stay a minority. */
        const val DARK_MAX_MEDIAN_LUMA = 60

        /** Light scheme background #F6F7FC has luma ~247. */
        const val LIGHT_MIN_MEDIAN_LUMA = 200

        /** Consecutive matching samples required (rejects stale-frame passes). */
        const val STABLE_SAMPLES = 2
    }
}
