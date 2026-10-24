package com.pahebatcher.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.notification.NotificationPermissionRepository
import com.pahebatcher.app.support.SettingsTestSupport
import com.pahebatcher.app.support.allTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * §36-13 — Settings content test, extended by Phase 12A.
 *
 * Phase 11 Settings exposed ONLY the theme controls behind exactly TWO
 * `settings_row` tags. Phase 12A adds the engine settings that production code
 * really consumes (quality, max_parallel, hls_workers, cache_ttl, keep_temp,
 * auto_retry) plus the opt-in diagnostics Console entry (`Show Console`,
 * default OFF → the "Open console" row must be ABSENT until enabled).
 *
 * The exact-text-set assertion still does its original job: any dead row, any
 * control without a persisted value, or any missing control fails it. Rows
 * whose value text depends on persisted state (HLS workers, cache TTL) are
 * reset to the Python defaults in [resetToDefaults] first, so the assertion is
 * deterministic regardless of test ordering.
 *
 * Phase 13 (DECISIONS 96/105) adds two rows: `Wi-Fi-only downloads` and the
 * `Notifications` permission status row. The latter's text reflects the REAL
 * device permission state, so its expectation is computed from the same
 * repository the row projects — the assertion stays strict in every state
 * instead of hard-coding one the device may not be in.
 *
 * Phase 14 (PHASE14_PLAN §R7) adds the two terminal-notification switches to
 * the same `Notifications` section, plus (§12.2) the third `New watchlist
 * episodes` switch and, in the new `Background checks` section (§12.1), the
 * check-frequency radio group. The switches are `SwitchRow`s, so unlike the
 * radio groups their on/off state contributes no text — but they are still
 * counted by the `settings_row` assertion, which is what stops a row from being
 * shipped without the `AppConfig` key and the production post site behind it.
 * The radio group contributes one `settings_row` for the whole group, exactly
 * like Theme/Quality/Max-parallel/Auto-retry/Resolve-ahead.
 * §18 then adds `Auto-resume downloads`, the one Phase 14 row outside the two
 * notification sections — it sits directly after `Wi-Fi-only downloads` in the
 * download-engine section because it is consumed by the same class of
 * production code (`DownloadWorker`), and it too must contribute BOTH its
 * `settings_row` and its two texts.
 *
 * Audio-variant slice (SPEC §22/§23): the former `Preferred audio` radio group
 * is GONE — audio is chosen per episode, not globally. Its four texts are
 * deliberately absent from [settingsExposesExactlyTheRealRows]' exact set (the
 * set is strict, so any audio row reappearing fails the test), and the row
 * count dropped 19 → 18 with it.
 *
 * Entry is the §10.2 path: Settings is pushed from the Home top-bar gear
 * (never a bottom tab — NavigationTest proves the gear is not a tab).
 */
class SettingsContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetEngineSettings() {
        // Known precondition: Python defaults (other suites may have moved them).
        SettingsTestSupport.resetToDefaults(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @Test
    fun settingsExposesExactlyTheRealRows() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }

        // Phase 12A/12C/13/14: every row is DataStore-backed with a production
        // consumer — theme, dynamic color, quality, max
        // parallel, HLS workers, cache TTL, keep temp, auto retry, resolve
        // ahead (12C), Wi-Fi-only (13/DECISIONS 105), auto-resume (14/§10),
        // output location (13/DECISIONS 92), notification permission status
        // (13/DECISIONS 96), completion + failure + watchlist notifications
        // (14/PHASE14_PLAN §R7), background check frequency (14/§12.1), show
        // console = 18 rows with `Show Console` OFF (the conditional "Open
        // console" row must not exist yet). NO audio row: the global
        // `Preferred audio` control was removed with the audio-variant slice
        // (SPEC §22/§23) — its absence is enforced by the exact-text set below.
        assertEquals(
            "exactly eighteen settings_row tags with Show Console OFF",
            18,
            composeRule.onAllNodesWithTag("settings_row").fetchSemanticsNodes().size,
        )

        // The notification row reports the REAL device permission state, which
        // differs per run (fresh install = denied; an earlier suite may have
        // granted it — revoking is impossible here, it would kill this running
        // process). Compute the expectation from the SAME repository the row
        // projects so the exact-set assertion stays strict in every state.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val permissionRepository = NotificationPermissionRepository(
            AppConfig(targetContext),
            targetContext,
        )
        val granted = runBlocking { permissionRepository.isGranted() }
        val applies = permissionRepository.runtimeRequestApplies()
        val canRequest =
            !granted && applies && runBlocking { permissionRepository.shouldRequest() }
        val notificationStatus = when {
            granted -> "Allowed — download progress can be shown."
            canRequest -> "Not allowed yet — progress notifications are hidden."
            else -> "Blocked — progress notifications are hidden. Allow them in system settings."
        }
        val notificationAction = when {
            canRequest -> listOf("Allow")
            !granted -> listOf("Open settings")
            else -> emptyList()
        }
        // The projection (VM init + lifecycle resume) is async relative to the
        // compose idle signal — DataStore IO is not idling-tracked, so wait for
        // the real text before asserting the exact set.
        composeRule.waitUntil(5_000) {
            composeRule.allTexts().contains(notificationStatus)
        }

        // Exact full text composition — a dead row would add text here. The two
        // bottom-bar labels are the persistent §10.1 NavigationBar (Home +
        // Downloads remain visible on every non-player route by design), and
        // "Downloads" also appears as a section header (two occurrences total).
        val expectedTexts = listOf(
            "Settings",
            // Appearance
            "Appearance",
            "Theme",
            "System",
            "Dark",
            "Light",
            "Dynamic color",
            "Replaces the curated palette with your system wallpaper colors.",
            // Downloads (section title says "Download engine" so the bottom-nav
            // word "Downloads" stays unique on this route)
            "Download engine",
            "Quality",
            "360p",
            "720p",
            "1080p",
            "Resolution requested when an episode's stream is resolved.",
            // NO "Preferred audio" block (audio-variant slice, SPEC §22/§23):
            // the global audio radio group and its four texts were removed, and
            // this exact set fails the moment any of them comes back.
            "Max parallel downloads",
            "1",
            "2",
            "3",
            "4",
            "5",
            "6",
            "How many episodes download at the same time.",
            "HLS workers",
            "24 segments at once",
            "Concurrent segment downloads per episode.",
            "Scan cache TTL",
            "60 min",
            "Cached episode lists expire after this many minutes. 0 disables the cache.",
            "Keep temp files",
            "Keep downloaded segments after muxing instead of deleting them.",
            "Auto retry",
            "None",
            "Once",
            "Twice",
            "Extra attempts for an episode that fails.",
            // Resolve ahead (Phase 12C) — consumed by the batch staging gate
            "Resolve ahead",
            "0 — resolve only when a download starts",
            "1 episode",
            "2 episodes",
            "4 episodes",
            "8 episodes",
            "Unlimited (999, default)",
            "How many resolved episodes may wait ahead of the running downloads during a batch.",
            // Wi-Fi-only downloads (Phase 13, DECISIONS 105) — the Switch has
            // no text of its own; title + description carry the row.
            "Wi-Fi-only downloads",
            "Only start downloads on unmetered (Wi-Fi) networks. " +
                "Downloads already waiting keep their current behavior.",
            // Auto-resume downloads (Phase 14, PHASE14_PLAN §10/§18) — directly
            // after the Wi-Fi-only row, exactly as §18 places it. The Switch
            // carries no text of its own; title + description are the row.
            "Auto-resume downloads",
            "Retries failed downloads automatically when connectivity returns; bounded attempts.",
            // Output location (Phase 13, DECISIONS 92) — resetToDefaults cleared
            // the tree, so the row renders its deterministic NotConfigured text
            // ("Storage" is its section header).
            "Storage",
            "Output location",
            "Not set — exports go to Movies/<anime title> (system default).",
            "Choose",
            // Notifications (Phase 13, DECISIONS 96) — the section header AND
            // the row title both read "Notifications", plus the REAL per-device
            // status and its action label (when one exists).
            "Notifications",
            "Notifications",
            notificationStatus,
            // Terminal notifications (Phase 14, PHASE14_PLAN §R7) — two switches
            // in the same Notifications section; the Switch carries no text of
            // its own, so title + description are the row's whole contribution.
            "Completion notifications",
            "Notify when an episode finishes downloading.",
            "Failure notifications",
            "Notify when an episode fails after all retries.",
            "New watchlist episodes",
            "Notify when a background check finds new episodes.",
            // Background checks (Phase 14, PHASE14_PLAN §12.1) — a new section
            // between Notifications and Diagnostics holding the check-frequency
            // radio group. The default interval is reset by @Before, so the
            // "(default)" label's selection is deterministic (its SELECTED state
            // is asserted separately by SettingsPersistenceTest).
            "Background checks",
            "Check frequency",
            "15 minutes (default)",
            "30 minutes",
            "1 hour",
            "4 hours",
            "12 hours",
            "How often the background watchlist check runs.",
            // Diagnostics
            "Diagnostics",
            "Show Console",
            "Adds an in-app log console. Logs are kept only while this is on.",
            // Bottom bar
            "Home",
            "Downloads",
        ) + notificationAction
        assertEquals(
            "Settings must contain only real, persisted controls",
            expectedTexts.sorted(),
            composeRule.allTexts().sorted(),
        )

        // The §21.1 Up affordance exists on the pushed route.
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun themeRadiosAndDynamicColorSwitchAreInteractive() {
        openSettings()

        // Theme radios are real click targets with real selection state.
        composeRule.onNodeWithText("System").assertHasClickAction()
        composeRule.onNodeWithText("Dark").assertHasClickAction()
        composeRule.onNodeWithText("Light").assertHasClickAction()

        // Establish a known persisted state first (other tests may have left a
        // different theme behind — the default-DARK contract itself is unit
        // tested in ThemeSettingsTest / Slice 7 theme tests).
        composeRule.onNodeWithText("Dark").performClick()
        awaitSelected("Dark")

        // Selecting Light flips the selection through the REAL round trip:
        // ViewModel → AppConfig (DataStore write) → flow → recomposition.
        composeRule.onNodeWithText("Light").performClick()
        awaitSelected("Light")

        // Restore the default for other tests' deterministic expectations.
        composeRule.onNodeWithText("Dark").performClick()
        awaitSelected("Dark")

        // Dynamic color row is a real toggle target (its description text proves
        // the row content; the Switch itself is merged into the row semantics).
        composeRule
            .onNodeWithText("Replaces the curated palette with your system wallpaper colors.")
            .assertHasClickAction()
    }

    @Test
    fun backFromSettingsReturnsToHome() {
        openSettings()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("home_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("home_root").assertExists()
    }

    private fun openSettings() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Waits until the option with [label] reports Selected = true — the real
     * DataStore write + flow emission + recomposition must complete (compose
     * idling does not track DataStore IO, so poll the semantics).
     */
    private fun awaitSelected(label: String) {
        composeRule.waitUntil(5_000) {
            val node = composeRule.onNodeWithText(label).fetchSemanticsNode()
            node.config.contains(SemanticsProperties.Selected) &&
                node.config[SemanticsProperties.Selected]
        }
        composeRule.onNodeWithText(label).assertIsSelected()
    }
}
