package com.pahebatcher.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.support.SettingsTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings is a REAL configuration surface end to end:
 *
 * 1. a control write reaches the persistent DataStore (read back through the
 *    production [AppConfig] accessor, not through the screen's own state);
 * 2. the value survives Activity recreation (configuration change), i.e. the
 *    ViewModel/DataStore round trip is genuine;
 * 3. `Show Console` gates the diagnostics Console entry — OFF means the row
 *    does not exist, ON reveals it, and it opens the Console, which renders the
 *    bounded in-memory log buffer and clears it through the real
 *    [AppLog.buffer] (memory only, nothing persisted);
 * 4. Phase 12B (DECISIONS 86): retention itself is gated — a line emitted while
 *    the toggle is OFF is never retained, and enabling it (through the
 *    Application's `showConsole` collector) is what starts retention.
 *
 * [SettingsTestSupport.resetToDefaults] runs before and after every test so
 * ordering-dependent suites (SettingsContentTest's exact-text-set,
 * ConcurrencyTest's maxParallel gate) keep their deterministic preconditions.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val appConfig by lazy { AppConfig(targetContext) }

    @Before
    fun reset() {
        SettingsTestSupport.resetToDefaults(targetContext)
    }

    @After
    fun restoreDefaults() {
        SettingsTestSupport.resetToDefaults(targetContext)
    }

    private fun openSettings() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Read of the PRODUCTION persisted configuration (DataStore), not screen state. */
    private fun current() = runBlocking { appConfig.currentAppContext() }

    private fun showConsoleEnabled(): Boolean = runBlocking { appConfig.showConsole.first() }

    /**
     * Read the SAME flows `DownloadWorker` reads at
     * the post site — not screen state — so what this suite asserts is exactly
     * what a real completion/failure notification will be gated by.
     */
    private fun notifyCompletionEnabled(): Boolean =
        runBlocking { appConfig.notifyCompletion.first() }

    private fun notifyFailureEnabled(): Boolean =
        runBlocking { appConfig.notifyFailure.first() }

    /** The flow `WatchlistCheckWorker` reads after each check. */
    private fun notifyWatchlistEnabled(): Boolean =
        runBlocking { appConfig.notifyWatchlist.first() }

    /** The interval the Application collector re-registers. */
    private fun watchlistIntervalMinutes(): Long =
        runBlocking { appConfig.watchlistCheckIntervalMinutes.first() }

    /**
     * The flow `DownloadWorker`'s
     * `autoResumeProvider` reads at the moment a transport failure is decided —
     * not screen state.
     */
    private fun autoResumeEnabled(): Boolean = runBlocking { appConfig.autoResume.first() }

    /** The flow the three enqueue sites read. */
    private fun wifiOnlyEnabled(): Boolean = runBlocking { appConfig.wifiOnly.first() }

    /**
     * Compose tests do NOT auto-scroll: a row below the fold has real (off-screen)
     * bounds, so `performClick()` injects events that land outside the visible
     * content and silently do nothing (reviewer M3/C2 — the earlier manual
     * `swipeUp()` loop compared the node bounds against the raw display height,
     * which is larger than the visible content once the pinned TopAppBar and the
     * bottom NavigationBar are subtracted, so a clipped row could pass the check
     * and its click was then dropped).
     *
     * [performScrollTo] drives the REAL scrollable ancestor until the node is
     * visible — the same mechanism a user scroll uses — and [assertIsDisplayed]
     * then proves the target is inside the visible content before the click is
     * injected.
     */
    private fun clickSetting(text: String) {
        val matches = composeRule.onAllNodesWithText(text).fetchSemanticsNodes()
        assertTrue(
            "expected exactly one '$text' control (found ${matches.size})",
            matches.size == 1,
        )
        composeRule.onNodeWithText(text).performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text).assertIsDisplayed()
        composeRule.onNodeWithText(text).performClick()
    }

    @Test
    fun qualitySelectionPersistsAcrossRecreation() {
        openSettings()

        // Real round trip: tap → ViewModel → AppConfig.updateQuality → DataStore.
        clickSetting("720p")
        composeRule.waitUntil(10_000) { current().quality == 720 }
        assertEquals("written through the production config", 720, current().quality)

        // Selection state reflects the persisted value.
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithText("720p").fetchSemanticsNode()
                .config[SemanticsProperties.Selected]
        }
        composeRule.onNodeWithText("720p").assertIsSelected()

        // Activity recreation (configuration-change class): the state must come
        // back from DataStore, not from a live ViewModel field.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithText("720p").fetchSemanticsNode()
                .config[SemanticsProperties.Selected]
        }
        assertEquals("survives recreation", 720, current().quality)

        clickSetting("1080p")
        composeRule.waitUntil(10_000) { current().quality == 1080 }
    }

    @Test
    fun engineSettingsReachTheProductionConfig() {
        openSettings()

        // max_parallel (consumed by the DownloadWorker concurrency gate).
        clickSetting("4")
        composeRule.waitUntil(10_000) { current().maxParallel == 4 }
        assertEquals(4, current().maxParallel)

        // auto_retry (consumed by EpisodeDownloader's episode retry budget).
        clickSetting("Once")
        composeRule.waitUntil(10_000) { current().autoRetry == 1 }
        assertEquals(1, current().autoRetry)

        // keep_temp (consumed by EpisodeDownloader after muxing).
        clickSetting("Keep temp files")
        composeRule.waitUntil(10_000) { current().keepTemp }
        assertTrue(current().keepTemp)

        // The published range is 1..6 — there is no "9" option to select.
        composeRule.onNodeWithText("9").assertDoesNotExist()
        clickSetting("6")
        composeRule.waitUntil(10_000) { current().maxParallel == 6 }
        assertEquals(6, current().maxParallel)
    }

    /**
     * `notify_completion`
     * and `notify_failure` round-trip through the PRODUCTION DataStore and both
     * come back to the shipped default (ON) after `resetToDefaults()`.
     *
     * The opening assertions prove the helper restores the shipped default; the
     * closing ones exercise PRODUCTION [AppConfig.resetToDefaults], a plain
     * `preferences.clear()` — after it both keys are ABSENT, so those two
     * assertions are also the direct proof of the shipped `?: true` default, the
     * same branch a fresh install takes. `@After` then rewrites every other
     * default explicitly through the precondition helper, so nothing downstream
     * inherits a cleared store.
     *
     * Each step asserts against `AppConfig`, the object `DownloadWorker` reads
     * fresh at the post site, so a green result here means the post site will
     * observe the toggled value — not merely that a Switch re-rendered.
     */
    @Test
    fun notificationTogglesPersistAndResetToDefaults() {
        openSettings()

        // Shipped default: BOTH post sites enabled (the suite's @Before restored
        // them through SettingsTestSupport.resetToDefaults).
        assertTrue("notify_completion default is ON", notifyCompletionEnabled())
        assertTrue("notify_failure default is ON", notifyFailureEnabled())

        // Real row round trip: click → ViewModel → AppConfig → DataStore.
        clickSetting("Completion notifications")
        composeRule.waitUntil(10_000) { !notifyCompletionEnabled() }
        assertFalse(
            "notify_completion must persist as false",
            notifyCompletionEnabled(),
        )

        // The two toggles are independent — turning one off must not move the other.
        assertTrue(
            "failure toggle must stay ON while completion is OFF",
            notifyFailureEnabled(),
        )
        clickSetting("Failure notifications")
        composeRule.waitUntil(10_000) { !notifyFailureEnabled() }
        assertFalse("notify_failure must persist as false", notifyFailureEnabled())

        // Activity recreation (configuration-change class): both must come back
        // from DataStore rather than from a live ViewModel field.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("notify_completion survives recreation", notifyCompletionEnabled())
        assertFalse("notify_failure survives recreation", notifyFailureEnabled())

        // PRODUCTION reset: `preferences.clear()` removes both keys entirely, so
        // what follows is the shipped `?: true` default winning on an ABSENT key
        // — the same branch a fresh install takes.
        runBlocking { appConfig.resetToDefaults() }
        assertTrue(
            "AppConfig.resetToDefaults() must restore notify_completion to ON",
            notifyCompletionEnabled(),
        )
        assertTrue(
            "AppConfig.resetToDefaults() must restore notify_failure to ON",
            notifyFailureEnabled(),
        )
    }

    /**
     * Watchlist preference persistence:
     * `notify_watchlist` and `watchlist_check_interval_minutes` round-trip
     * through the PRODUCTION DataStore.
     *
     * Three contracts are proven here, all against `AppConfig` — the object
     * `WatchlistCheckWorker` and the Application collector read — rather than
     * against screen state:
     *
     *  1. the toggle round-trips and is independent of the two terminal toggles;
     *  2. EVERY value the Settings radio group can produce is one the setter
     *     accepts, so the offered set and `AppConfig.WATCHLIST_INTERVAL_OPTIONS`
     *     cannot drift apart silently while both stay green;
     *  3. a non-member value falls back deterministically to the default, and a
     *     production `resetToDefaults()` restores both settings (absent key →
     *     shipped default, the fresh-install branch).
     */
    @Test
    fun watchlistPreferencesPersistValidateAndResetToDefaults() {
        openSettings()

        // Shipped defaults: notification ON, frequency 15 minutes.
        assertTrue("notify_watchlist default is ON", notifyWatchlistEnabled())
        assertEquals(
            "the shipped default interval is 15 minutes",
            AppConfig.DEFAULT_WATCHLIST_INTERVAL_MINUTES,
            watchlistIntervalMinutes(),
        )

        // Real row round trip, and the toggle is INDEPENDENT of the two terminal
        // toggles — those must still sit at their shipped ON.
        clickSetting("New watchlist episodes")
        composeRule.waitUntil(10_000) { !notifyWatchlistEnabled() }
        assertFalse("notify_watchlist must persist as false", notifyWatchlistEnabled())
        assertTrue("completion toggle must stay ON", notifyCompletionEnabled())
        assertTrue("failure toggle must stay ON", notifyFailureEnabled())

        // Walk the whole offered set through the REAL radio row. Each click must
        // land on the value that was asked for, which is only possible when the
        // label and AppConfig.WATCHLIST_INTERVAL_OPTIONS agree.
        for (minutes in AppConfig.WATCHLIST_INTERVAL_OPTIONS) {
            val label = intervalLabel(minutes)
            clickSetting(label)
            composeRule.waitUntil(10_000) { watchlistIntervalMinutes() == minutes }
            assertEquals(
                "the offered option '$label' must be accepted by the setter",
                minutes,
                watchlistIntervalMinutes(),
            )
        }

        // Activity recreation: both settings must come back from DataStore.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("notify_watchlist survives recreation", notifyWatchlistEnabled())
        assertEquals(
            "the chosen interval survives recreation",
            AppConfig.WATCHLIST_INTERVAL_OPTIONS.last(),
            watchlistIntervalMinutes(),
        )

        // A value OUTSIDE the allowed set must land on the default
        // deterministically — never a neighbour, never WorkManager's rejection.
        runBlocking { appConfig.updateWatchlistCheckIntervalMinutes(45L) }
        assertEquals(
            "a non-member interval must fall back to the default",
            AppConfig.DEFAULT_WATCHLIST_INTERVAL_MINUTES,
            watchlistIntervalMinutes(),
        )

        // PRODUCTION reset clears the store: absent keys resolve to the shipped
        // defaults for BOTH settings.
        runBlocking { appConfig.resetToDefaults() }
        assertTrue(
            "AppConfig.resetToDefaults() must restore notify_watchlist to ON",
            notifyWatchlistEnabled(),
        )
        assertEquals(
            "AppConfig.resetToDefaults() must restore the default interval",
            AppConfig.DEFAULT_WATCHLIST_INTERVAL_MINUTES,
            watchlistIntervalMinutes(),
        )
    }

    /**
     * `auto_resume` is a real
     * DataStore-backed toggle with a single production consumer —
     * `DownloadWorker.shouldAutoResume` — and gating a worker decision on it is
     * only meaningful while the value actually round-trips.
     *
     * Asserted against `AppConfig` (the exact object `autoResumeProvider`
     * reads), never screen state, and never through the worker here: what the
     * worker DOES with the value is `AutoResumeEligibilityTest` (JVM) plus the
     * device connectivity fixture; what Settings stores is this test.
     *
     *  1. shipped default **OFF** — today's terminal-failure semantics;
     *  2. the real row round-trips through the production setter, and the
     *     neighbouring `wifi_only` key is untouched (independent keys);
     *  3. the value survives Activity recreation;
     *  4. `AppConfig.resetToDefaults()` clears it back to the shipped default.
     */
    @Test
    fun autoResumePreferencePersistsDefaultsAndResetsIndependently() {
        openSettings()

        assertFalse("auto_resume default is OFF", autoResumeEnabled())

        // Real row round trip, tap → ViewModel → AppConfig.updateAutoResume →
        // DataStore, read back through the production flow.
        clickSetting("Auto-resume downloads")
        composeRule.waitUntil(10_000) { autoResumeEnabled() }
        assertTrue("auto_resume must persist as true", autoResumeEnabled())
        assertFalse(
            "wifi_only must stay at its shipped OFF — independent keys",
            wifiOnlyEnabled(),
        )

        // Activity recreation: it comes back from DataStore, not a ViewModel
        // field.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("auto_resume survives recreation", autoResumeEnabled())

        // PRODUCTION reset clears the store: absent key → shipped default OFF.
        runBlocking { appConfig.resetToDefaults() }
        assertFalse(
            "AppConfig.resetToDefaults() must restore auto_resume to OFF",
            autoResumeEnabled(),
        )
    }

    /**
     * The exact label [com.pahebatcher.app.ui.settings.SettingsScreen] renders
     * for each allowed interval. The `else` branch is unreachable while the two
     * lists agree and fails loudly the moment they stop agreeing.
     */
    private fun intervalLabel(minutes: Long): String = when (minutes) {
        15L -> "15 minutes (default)"
        30L -> "30 minutes"
        60L -> "1 hour"
        240L -> "4 hours"
        720L -> "12 hours"
        else -> error("interval $minutes has no Settings label — the offered set drifted")
    }

    @Test
    fun showConsoleGatesTheConsoleEntryAndTheConsoleWorks() {
        // retention must be OFF before the opt-in —
        // this line is emitted first so its ABSENCE proves the gate works, and
        // later so the marker assertions stay marker-specific (new app logs may
        // arrive after Clear, so nothing asserts "buffer empty").
        composeRule.waitUntil(5_000) { !AppLog.isBufferingEnabled }
        AppLog.i("SettingsPersistenceTest", BEFORE_MARKER)
        assertTrue(
            "Show Console OFF must retain nothing",
            AppLog.buffer.entries.value.none { it.message == BEFORE_MARKER },
        )

        openSettings()

        // OFF (default): no Console row at all — an opt-in diagnostics surface.
        composeRule.onNodeWithText("Open console").assertDoesNotExist()
        assertFalse(showConsoleEnabled())

        clickSetting("Show Console")
        composeRule.waitUntil(10_000) { showConsoleEnabled() }
        // The Application collector has now observed the persisted ON value —
        // the same production mechanism that opens the retention gate (ordered
        // DataStore emission, so no earlier OFF value can arrive afterwards).
        composeRule.waitUntil(5_000) { AppLog.isBufferingEnabled }

        // Retention starts only AFTER the opt-in: this marker is kept.
        AppLog.i("SettingsPersistenceTest", MARKER)
        assertTrue(
            "Show Console ON retains an emitted line",
            AppLog.buffer.entries.value.any {
                it.message == MARKER && it.tag == "SettingsPersistenceTest"
            },
        )

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Open console").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Open console").assertHasClickAction()
        // The row's description is the copy the opt-in decision is made on.
        composeRule
            .onNodeWithText("View the in-app diagnostic log (cleared when the app restarts).")
            .assertExists()

        // The row opens the Console, which renders the shared in-memory buffer.
        clickSetting("Open console")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("console_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("console_root").assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            AppLog.buffer.entries.value.any { it.message == MARKER && it.tag == "SettingsPersistenceTest" }
        }

        // Clear empties the REAL shared buffer (memory only, nothing persisted).
        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitUntil(5_000) {
            AppLog.buffer.entries.value.none { it.message == MARKER }
        }
        assertTrue(AppLog.buffer.entries.value.none { it.message == MARKER })
        assertTrue(
            "the pre-opt-in line was never retained in the first place",
            AppLog.buffer.entries.value.none { it.message == BEFORE_MARKER },
        )
        // With the buffer emptied, the Console renders its documented empty state
        // (the copy that states the retention rule).
        composeRule.onNodeWithText(
            "No log entries yet. Logs are collected only while Show Console is " +
                "enabled and are cleared when the app restarts.",
        ).assertExists()

        // Back pops to Settings with the toggle still ON (persisted).
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Open console").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Show Console persists", showConsoleEnabled())
    }

    @Test
    fun togglingShowConsoleOffRemovesTheEntryAndStopsRetention() {
        openSettings()

        // Start ON so the entry exists, then switch it off — the row must vanish
        // (no dead control left behind, AGENTS §5).
        clickSetting("Show Console")
        composeRule.waitUntil(10_000) { showConsoleEnabled() }
        composeRule.onNodeWithText("Open console").assertExists()
        // Retention is open through the production collector: keep a line so the
        // OFF path has something to drop.
        composeRule.waitUntil(5_000) { AppLog.isBufferingEnabled }
        AppLog.i("SettingsPersistenceTest", ON_MARKER)
        assertTrue(
            "precondition: the ON path retains a line",
            AppLog.buffer.entries.value.any { it.message == ON_MARKER },
        )

        clickSetting("Show Console")
        composeRule.waitUntil(10_000) { !showConsoleEnabled() }
        composeRule.onNodeWithText("Open console").assertDoesNotExist()
        assertFalse(showConsoleEnabled())

        // The OFF half of DECISIONS 86, observed through the SAME production
        // mechanism users hit: the Application collector must close retention
        // (future lines dropped) AND drop what was retained.
        composeRule.waitUntil(5_000) { !AppLog.isBufferingEnabled }
        assertTrue(
            "turning Show Console off drops the buffer's retention",
            AppLog.buffer.entries.value.none { it.message == ON_MARKER },
        )
        AppLog.i("SettingsPersistenceTest", OFF_MARKER)
        assertTrue(
            "future lines are no longer retained either",
            AppLog.buffer.entries.value.none { it.message == OFF_MARKER },
        )
    }

    private companion object {
        const val MARKER = "console-marker-before-clear"
        const val BEFORE_MARKER = "console-marker-before-opt-in"
        const val ON_MARKER = "console-marker-while-enabled"
        const val OFF_MARKER = "console-marker-after-opt-out"
    }
}
