package com.pahebatcher.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.export.OutputTreeState
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.domain.watchlist.RelinkCandidate
import com.pahebatcher.app.ui.common.OfflineBanner
import com.pahebatcher.app.ui.settings.NotificationPermissionUiState
import com.pahebatcher.app.ui.settings.SettingsScreen
import com.pahebatcher.app.ui.settings.SettingsUiState
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import com.pahebatcher.app.ui.watchlist.RelinkState
import com.pahebatcher.app.ui.watchlist.WatchlistScreen
import com.pahebatcher.app.ui.watchlist.WatchlistUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Responsive evidence for the NEW Phase 13
 * surfaces this slice ships: the Settings rows (Wi-Fi-only / Output location /
 * Notifications), the offline banner, and the watchlist relink row + dialog.
 *
 * The Decision 111 matrix:
 *
 * | axis | simulated as |
 * |---|---|
 * | compact width | `Box(360dp)` |
 * | expanded width (≥600dp) | `Box(720dp)` |
 * | split-screen / multi-window resize | `Box(480×320)` — a genuinely different box from both above |
 * | default font scale | the device's own scale (1.15 here) — covered by the real-window suites: `SettingsContentTest`, `OfflineBannerTest`, `WatchlistScreenTest` |
 * | largest font scale | `Density(density, fontScale = 2f)` |
 *
 * Window sizes use the same capability the Sessions evidence uses (Decision
 * 111.3 — the existing `fillMaxWidth`/`weight` patterns + Box constraints; no
 * new framework): an in-window surface receives exactly the constraints a
 * resized window would give it. The relink dialog is its OWN system window
 * whose width follows the host window at the platform level, so its width axis
 * cannot be simulated from a parent Box — this file verifies its FONT axis,
 * while its content stays constraint-driven (`fillMaxWidth` rows, no
 * hard-coded phone widths, 111.3).
 *
 * "Reachable" means scrolled into view AND displayed — a clipped or
 * unreachable row fails, not merely a composed one.
 */
@RunWith(AndroidJUnit4::class)
class Phase13ResponsiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * One composition under the requested window constraints + font scale.
     * `fontScale = null` keeps the device's own scale (identity override).
     */
    private fun composeAt(
        widthDp: Int? = null,
        heightDp: Int? = null,
        fontScale: Float? = null,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
            ) {
                when {
                    widthDp != null && heightDp != null ->
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) { content() }

                    widthDp != null -> Box(Modifier.width(widthDp.dp)) { content() }

                    else -> content()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Settings — the new Phase 13 rows (DECISIONS 92/96/105)
    // -----------------------------------------------------------------------

    /** Settings composed DIRECTLY from hand-built state — no DataStore, no nav. */
    private fun settingsContent(): @Composable () -> Unit = {
        PaheBatcherTheme {
            SettingsScreen(
                uiState = SettingsUiState(),
                onBack = {},
                onThemeMode = {},
                onDynamicColor = {},
                onQuality = {},
                onMaxParallel = {},
                onHlsWorkers = {},
                onCacheTtl = {},
                onKeepTemp = {},
                onAutoRetry = {},
                onResolveAhead = {},
                onShowConsole = {},
                onOpenConsole = {},
                onWifiOnly = {},
                onAutoResume = {},
                onNotifyCompletion = {},
                onNotifyFailure = {},
                onNotifyWatchlist = {},
                onWatchlistCheckInterval = {},
                notificationPermissionState = NotificationPermissionUiState(),
                onRefreshNotificationPermission = {},
                onNotificationPermissionResult = {},
                onOpenNotificationSystemSettings = {},
                outputLocationState = OutputTreeState.NotConfigured,
                onRefreshOutputLocation = {},
                onOutputTreePicked = {},
                onUseDefaultOutputLocation = {},
            )
        }
    }

    /**
     * Every Phase 13 row must be REACHABLE (scrolled into view, then
     * displayed) at the current configuration — and the exact row inventory
     * (14 in Phase 13; 19 after Phase 14 added the auto-resume row, the three
     * notification switches and the background-check frequency group; 18 now
     * that the audio-variant slice removed the global `Preferred audio` row,
     * SPEC §22/§23) must survive the resize unchanged.
     */
    private fun assertSettingsRowsReachable() {
        composeRule.onNodeWithText("Wi-Fi-only downloads").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Auto-resume downloads").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Output location").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("Not set — exports go to Movies/<anime title> (system default).")
            .performScrollTo()
            .assertIsDisplayed()
        // The notification row's real action label under the hand-built default
        // state (granted = false → the system-settings escape hatch).
        composeRule.onNodeWithText("Open settings").performScrollTo().assertIsDisplayed()
        assertEquals(
            "all eighteen settings_row tags survive this configuration (no audio row)",
            18,
            composeRule.onAllNodesWithTag("settings_row").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun phase13SettingsRowsAreReachableAtCompactWidth() {
        composeAt(widthDp = 360, content = settingsContent())

        assertSettingsRowsReachable()
    }

    @Test
    fun phase13SettingsRowsAreReachableAtExpandedWidth() {
        composeAt(widthDp = 720, content = settingsContent())

        assertSettingsRowsReachable()
    }

    @Test
    fun phase13SettingsRowsAreReachableAtMultiWindowResize() {
        composeAt(widthDp = 480, heightDp = 320, content = settingsContent())

        assertSettingsRowsReachable()
    }

    @Test
    fun phase13SettingsRowsAreReachableAtTheLargestFontScale() {
        composeAt(fontScale = 2f, content = settingsContent())

        assertSettingsRowsReachable()
    }

    // -----------------------------------------------------------------------
    // Offline banner (DECISIONS 97.3)
    // -----------------------------------------------------------------------

    private fun assertBannerVisible() {
        composeRule
            .onNodeWithText("You're offline — network features are unavailable.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("offline_banner").assertIsDisplayed()
    }

    @Test
    fun offlineBannerDisplaysAtCompactWidth() {
        composeAt(widthDp = 360, content = { PaheBatcherTheme { OfflineBanner() } })

        assertBannerVisible()
    }

    @Test
    fun offlineBannerDisplaysAtExpandedWidth() {
        composeAt(widthDp = 720, content = { PaheBatcherTheme { OfflineBanner() } })

        assertBannerVisible()
    }

    @Test
    fun offlineBannerDisplaysAtMultiWindowResize() {
        composeAt(widthDp = 480, heightDp = 160, content = { PaheBatcherTheme { OfflineBanner() } })

        assertBannerVisible()
    }

    @Test
    fun offlineBannerDisplaysAtTheLargestFontScale() {
        composeAt(fontScale = 2f, content = { PaheBatcherTheme { OfflineBanner() } })

        assertBannerVisible()
    }

    // -----------------------------------------------------------------------
    // Manual relink row + dialog (DECISIONS 108)
    // -----------------------------------------------------------------------

    private fun entry() = WatchlistEntity(
        sessionId = "sess-1",
        url = "https://animepahe.com/anime/sess-1",
        animeSession = "sess-1",
        host = "animepahe.com",
        title = "One Piece",
        quality = 720,
        audioLang = "jpn",
        outputDir = "./downloads",
        maxParallel = 2,
        hlsWorkers = 24,
        keepTemp = false,
        autoRetry = 0,
        addedAt = 1_000L,
        lastCheckedAt = 0L,
        downloadedEpisodesJson = "[]",
    )

    private fun watchlistContent(relink: RelinkState? = null): @Composable () -> Unit = {
        PaheBatcherTheme {
            WatchlistScreen(
                uiState = WatchlistUiState(entries = listOf(entry()), relink = relink),
                onBack = {},
                onUrlChange = {},
                onAdd = {},
                onRemove = {},
                onCheckNow = {},
                onRelink = {},
                onSelectRelinkCandidate = {},
                onConfirmRelink = {},
                onDismissRelink = {},
            )
        }
    }

    @Test
    fun relinkRowActionIsReachableAtNarrowWidth() {
        // Decision 111's width axis at the narrowest phone bucket (320dp):
        // both row actions must stay reachable — never clipped, never pushed
        // out of the row's bounds.
        composeAt(widthDp = 320, content = watchlistContent())

        composeRule.onNodeWithText("One Piece").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_row_relink").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_row_remove").assertIsDisplayed()
    }

    @Test
    fun relinkRowActionIsReachableAtCompactWidth() {
        composeAt(widthDp = 360, content = watchlistContent())

        composeRule.onNodeWithText("One Piece").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_row_relink").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_row_remove").assertIsDisplayed()
    }

    @Test
    fun relinkRowActionIsReachableAtExpandedWidth() {
        composeAt(widthDp = 720, content = watchlistContent())

        composeRule.onNodeWithText("One Piece").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_row_relink").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_row_remove").assertIsDisplayed()
    }

    @Test
    fun relinkDialogSurvivesTheLargestFontScale() {
        // Window-level width follows the platform (see the class KDoc) — the
        // simulatable axis for a dialog is the font scale.
        composeAt(
            fontScale = 2f,
            content = watchlistContent(
                relink = RelinkState(
                    entry = entry(),
                    candidates = listOf(
                        RelinkCandidate(
                            session = "sess-cand-a",
                            title = "One Piece",
                            type = "TV",
                            year = 1999,
                        ),
                        RelinkCandidate(
                            session = "sess-cand-b",
                            title = "One Piece",
                            type = "TV",
                            year = 2001,
                        ),
                    ),
                    selectedIndex = -1,
                ),
            ),
        )

        composeRule.onNodeWithText("Current: sess-1").assertIsDisplayed()
        assertEquals(
            "both candidates stay selectable at font scale 2.0",
            2,
            composeRule.onAllNodesWithTag("relink_candidate").fetchSemanticsNodes().size,
        )
        // 108.8 still holds visually at the largest scale: no selection yet.
        composeRule.onNodeWithTag("relink_confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("relink_cancel").assertIsDisplayed()
    }
}
