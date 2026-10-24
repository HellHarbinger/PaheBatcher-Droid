package com.pahebatcher.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.ui.sessions.ClearCacheDialogState
import com.pahebatcher.app.ui.sessions.DeleteSessionDialogState
import com.pahebatcher.app.ui.sessions.SessionRowUi
import com.pahebatcher.app.ui.sessions.SessionsScreen
import com.pahebatcher.app.ui.sessions.SessionsUiState
import com.pahebatcher.app.ui.sessions.SessionsViewModel
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Render contract of the
 * Sessions screen, state in / callbacks out (no repositories here).
 *
 * Asserted:
 *
 * - every SPEC §20 display field (title, URL, quality, audio, episode count,
 *   progress, completed/incomplete, cache size, state);
 * - per-action enablement with the reason shown while disabled (106);
 * - **Pause** (SPEC §20) exists, is bound, and is enabled only while some row
 *   really is pausable; **Resume all** appears only while the session holds
 *   paused rows (supersedes DECISIONS 94.2, which described the Python
 *   reference — it has no pause either);
 * - delete confirmation names unexported local media explicitly (SPEC §20)
 *   and clear-all shows the size it frees (107);
 * - every action button is bound to the caller's lambda;
 * - the screen renders at compact width, expanded width, a split-screen
 *   (multi-window) resize and a large font scale — the delete and clear-cache
 *   confirmations included (DECISIONS 111 evidence for this surface).
 */
@RunWith(AndroidJUnit4::class)
class SessionsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var resumed: String? = null
    private var paused: String? = null
    private var resumedDownloads: String? = null
    private var cancelled: String? = null
    private var retried: String? = null
    private var deleted: String? = null
    private var tempDeleted: String? = null
    private var confirmedDelete = 0
    private var dismissedDelete = 0
    private var requestedClear = 0
    private var confirmedClear = 0
    private var dismissedClear = 0

    private fun row(
        sessionId: String = "sess-1",
        title: String = "Some Anime",
        state: String = DownloadSessionEntity.STATE_COMPLETED,
        episodeCount: Int = 12,
        completedEpisodes: Int = 8,
        incompleteEpisodes: Int = 4,
        completedSegments: Int = 20,
        totalSegments: Int = 24,
        cacheBytes: Long = 512L,
        cancelCount: Int = 0,
        retryCount: Int = 1,
        pauseCount: Int = 0,
        resumeCount: Int = 0,
        deleteEnabled: Boolean = true,
        deleteDisabledReason: String? = null,
        tempCacheEnabled: Boolean = true,
        tempCacheDisabledReason: String? = null,
    ) = SessionRowUi(
        sessionId = sessionId,
        resumeSession = "anime-$sessionId",
        title = title,
        url = "https://animepahe.com/anime/anime-$sessionId",
        qualityLabel = "1080p",
        audioLang = "sub",
        episodeCount = episodeCount,
        completedEpisodes = completedEpisodes,
        incompleteEpisodes = incompleteEpisodes,
        completedSegments = completedSegments,
        totalSegments = totalSegments,
        segmentPercent = if (totalSegments > 0) {
            ((completedSegments.toFloat() / totalSegments) * 100).toInt()
        } else {
            null
        },
        cacheBytes = cacheBytes,
        displayState = state,
        cancelCount = cancelCount,
        retryCount = retryCount,
        pauseCount = pauseCount,
        resumeCount = resumeCount,
        deleteEnabled = deleteEnabled,
        deleteDisabledReason = deleteDisabledReason,
        tempCacheEnabled = tempCacheEnabled,
        tempCacheDisabledReason = tempCacheDisabledReason,
    )

    private fun state(
        rows: List<SessionRowUi> = listOf(row()),
        isLoading: Boolean = false,
        globalCacheBytes: Long = 512L,
        clearAllEnabled: Boolean = true,
        clearAllDisabledReason: String? = null,
        busySessionId: String? = null,
        deleteDialog: DeleteSessionDialogState? = null,
        clearCacheDialog: ClearCacheDialogState? = null,
    ) = SessionsUiState(
        isLoading = isLoading,
        rows = rows,
        globalCacheBytes = globalCacheBytes,
        clearAllEnabled = clearAllEnabled,
        clearAllDisabledReason = clearAllDisabledReason,
        busySessionId = busySessionId,
        deleteDialog = deleteDialog,
        clearCacheDialog = clearCacheDialog,
    )

    private fun setScreen(
        uiState: SessionsUiState,
        // DECISIONS 111: the font axis — null keeps the device's own scale so
        // every existing test runs exactly as before; a value wraps the WHOLE
        // surface (dialogs inherit the provided locals through their
        // sub-composition) in the scaled density.
        fontScale: Float? = null,
        content: @androidx.compose.runtime.Composable () -> Unit = {},
    ) {
        val screen: @androidx.compose.runtime.Composable () -> Unit = {
            PaheBatcherTheme {
                content()
                SessionsScreen(
                    uiState = uiState,
                    onBack = {},
                    onResume = { resumed = it },
                    onCancel = { cancelled = it },
                    onRetry = { retried = it },
                    onDelete = { deleted = it },
                    onDeleteTempCache = { tempDeleted = it },
                    onPause = { paused = it },
                    onResumeDownloads = { resumedDownloads = it },
                    onConfirmDelete = { confirmedDelete++ },
                    onDismissDelete = { dismissedDelete++ },
                    onRequestClearCache = { requestedClear++ },
                    onConfirmClearCache = { confirmedClear++ },
                    onDismissClearCache = { dismissedClear++ },
                )
            }
        }
        composeRule.setContent {
            if (fontScale == null) {
                screen()
            } else {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                ) { screen() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // SPEC §20 display list
    // -----------------------------------------------------------------------

    @Test
    fun everySpec20DisplayFieldIsRendered() {
        setScreen(state())

        composeRule.onNodeWithText("Some Anime").assertIsDisplayed()
        composeRule.onNodeWithText("https://animepahe.com/anime/anime-sess-1").assertIsDisplayed()
        composeRule.onNodeWithText("1080p · sub").assertIsDisplayed()
        composeRule
            .onNodeWithText("12 episodes · 8 completed · 4 incomplete")
            .assertIsDisplayed()
        composeRule.onNodeWithText("83% · 20/24 segments").assertIsDisplayed()
        // 512 bytes → "512.0 B" (StringUtils.fmtBytes).
        composeRule.onNodeWithText("Cache: 512.0 B").assertIsDisplayed()
        // Derived state chip (lowercased, DECISIONS 109).
        composeRule.onNodeWithTag("sessions_row_state").assertIsDisplayed()
        composeRule.onNodeWithText("completed").assertIsDisplayed()
        // Total cache size row (107).
        composeRule.onNodeWithText("Total cache size").assertIsDisplayed()
        composeRule.onNodeWithText("512.0 B").assertIsDisplayed()
    }

    @Test
    fun progressNeverFabricatesAPercentWithoutADenominator() {
        setScreen(
            state(
                rows = listOf(
                    row(
                        state = EpisodeDownloadEntity.STATE_QUEUED,
                        completedEpisodes = 0,
                        incompleteEpisodes = 12,
                        completedSegments = 0,
                        totalSegments = 0,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("%", substring = true).assertDoesNotExist()
        // No segment line either while nothing is persisted.
        composeRule.onNodeWithText("segments", substring = true).assertDoesNotExist()
    }

    @Test
    fun emptySessionListOffersTheHonestEmptyState() {
        setScreen(state(rows = emptyList()))
        composeRule
            .onNodeWithText("No sessions yet — sessions you start will appear here.")
            .assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // DECISIONS 94 + SPEC §20 — actions bound, Pause / Resume all included
    // -----------------------------------------------------------------------

    @Test
    fun everyActionIsBoundToTheCallersLambda() {
        setScreen(state(rows = listOf(row(sessionId = "sess-9", cancelCount = 1))))

        composeRule.onNodeWithText("Resume").performClick()
        assertEquals("anime-sess-9", resumed)
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals("sess-9", cancelled)
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals("sess-9", retried)
        // Exact match: "Delete" must not resolve to "Delete temporary cache".
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals("sess-9", deleted)
        composeRule.onNodeWithText("Delete temporary cache").performClick()
        assertEquals("sess-9", tempDeleted)
    }

    @Test
    fun pauseAndResumeAllAreBoundToTheCallersLambda() {
        // SPEC §20: Pause and its Resume counterpart are REAL controls. "Resume all"
        // only exists while the session holds paused rows, so a healthy session
        // never shows a resume that would do nothing.
        setScreen(
            state(
                rows = listOf(
                    row(
                        sessionId = "sess-p",
                        pauseCount = 2,
                        resumeCount = 1,
                        cancelCount = 0,
                        retryCount = 0,
                        deleteEnabled = false,
                        tempCacheEnabled = false,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Pause").assertIsEnabled().performClick()
        assertEquals("sess-p", paused)
        composeRule.onNodeWithText("Resume all").assertIsEnabled().performClick()
        assertEquals("sess-p", resumedDownloads)
    }

    @Test
    fun pauseControlIsDisabledUnlessARowReallyCanBePaused() {
        // The inverse guard, kept positive: the control EXISTS (SPEC §20) but is
        // inert while nothing is pausable — a button that would do nothing is
        // never offered as if it worked — and "Resume all" is not shown at all
        // when no row is paused (nothing to resume).
        setScreen(state(rows = listOf(row(sessionId = "sess-q", pauseCount = 0))))
        composeRule.onNodeWithText("Pause").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Resume all").assertDoesNotExist()
    }

    @Test
    fun clearAllCacheIsBoundAndShowsTheSizeItWillFree() {
        setScreen(state(globalCacheBytes = 2_048L))

        composeRule.onNodeWithText("2.0 KB").assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_clear_all").performClick()
        assertEquals(1, requestedClear)
    }

    // -----------------------------------------------------------------------
    // DECISIONS 106 — disabled gates show the reason
    // -----------------------------------------------------------------------

    @Test
    fun terminalOnlyActionsAreDisabledWithTheirReasonShown() {
        setScreen(
            state(
                rows = listOf(
                    row(
                        deleteEnabled = false,
                        deleteDisabledReason = SessionsViewModel.DELETE_DISABLED_REASON,
                        tempCacheEnabled = false,
                        tempCacheDisabledReason = SessionsViewModel.TEMP_CACHE_DISABLED_REASON,
                    ),
                ),
                clearAllEnabled = false,
                clearAllDisabledReason = SessionsViewModel.CLEAR_ALL_DISABLED_REASON,
            ),
        )

        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
        composeRule.onNodeWithText("Delete temporary cache").assertIsNotEnabled()
        composeRule.onNodeWithTag("sessions_clear_all").assertIsNotEnabled()

        // The disabled STATE is visible with its reason (never hidden).
        composeRule
            .onNodeWithText(SessionsViewModel.DELETE_DISABLED_REASON)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(SessionsViewModel.CLEAR_ALL_DISABLED_REASON)
            .assertIsDisplayed()
    }

    @Test
    fun cancelAndRetryAreGatedByTheirRowCounts() {
        setScreen(state(rows = listOf(row(cancelCount = 0, retryCount = 0))))
        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()
        composeRule.onNodeWithText("Retry").assertIsNotEnabled()
        composeRule.onNodeWithText("Resume").assertIsEnabled()
    }

    @Test
    fun busySessionsDisableEveryActionUntilTheAggregateFinishes() {
        setScreen(state(busySessionId = "sess-1"))
        composeRule.onNodeWithText("Resume").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    // -----------------------------------------------------------------------
    // Confirmations (SPEC §20 naming, DECISIONS 107)
    // -----------------------------------------------------------------------

    @Test
    fun deleteConfirmationNamesUnexportedMediaAndBindsBothButtons() {
        setScreen(
            state(
                deleteDialog = DeleteSessionDialogState(
                    sessionId = "sess-1",
                    title = "Some Anime",
                    exportedCount = 1,
                    unexportedNames = listOf("Some_Anime-001.mp4", "Some_Anime-002.mp4"),
                    temporaryBytes = 2_048L,
                ),
            ),
        )

        composeRule.onNodeWithTag("sessions_delete_dialog").assertIsDisplayed()
        composeRule
            .onNodeWithText("Not yet exported — these video files will be deleted too:", substring = true)
            .assertIsDisplayed()
        // Production lists each unexported file as a bullet item.
        composeRule.onNodeWithText("• Some_Anime-001.mp4").assertIsDisplayed()
        composeRule.onNodeWithText("• Some_Anime-002.mp4").assertIsDisplayed()
        composeRule
            .onNodeWithText("Exported videos in your library are never touched.")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Removes temporary download cache: 2.0 KB")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("sessions_confirm_delete").performClick()
        assertEquals(1, confirmedDelete)
        assertEquals(0, dismissedDelete)
    }

    @Test
    fun deleteConfirmationCancelDismissesWithoutDeleting() {
        setScreen(
            state(
                deleteDialog = DeleteSessionDialogState(
                    sessionId = "sess-1",
                    title = "Some Anime",
                    exportedCount = 0,
                    unexportedNames = emptyList(),
                    temporaryBytes = 0L,
                ),
            ),
        )
        composeRule.onNodeWithTag("sessions_cancel_delete").performClick()
        assertEquals(1, dismissedDelete)
        assertEquals(0, confirmedDelete)
    }

    @Test
    fun clearAllConfirmationShowsTheSizeAndBindsBothButtons() {
        setScreen(state(clearCacheDialog = ClearCacheDialogState(totalBytes = 1_600_000L)))

        composeRule.onNodeWithTag("sessions_clear_dialog").assertIsDisplayed()
        // 1_600_000 bytes → fmtBytes = "1.5 MB".
        composeRule
            .onNodeWithText("Removes 1.5 MB of temporary download files. Sessions and completed videos are kept.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("sessions_confirm_clear").performClick()
        assertEquals(1, confirmedClear)
        composeRule.onNodeWithTag("sessions_cancel_clear").performClick()
        assertEquals(1, dismissedClear)
    }

    // -----------------------------------------------------------------------
    // DECISIONS 111 — responsive evidence for this new surface
    // -----------------------------------------------------------------------

    @Test
    fun primaryContentIsReachableAtCompactWidth() {
        composeRule.setContent {
            PaheBatcherTheme {
                Box(Modifier.size(320.dp, 720.dp)) {
                    SessionsScreen(
                        uiState = state(),
                        onBack = {},
                        onResume = {}, onCancel = {}, onRetry = {}, onDelete = {},
                        onDeleteTempCache = {}, onConfirmDelete = {}, onDismissDelete = {},
                        onRequestClearCache = {}, onConfirmClearCache = {},
                        onDismissClearCache = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Some Anime").assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_clear_all").assertIsDisplayed()
        composeRule.onNodeWithText("Resume").assertIsDisplayed()
    }

    @Test
    fun primaryContentIsReachableAtExpandedWidth() {
        composeRule.setContent {
            PaheBatcherTheme {
                Box(Modifier.size(1_000.dp, 720.dp)) {
                    SessionsScreen(
                        uiState = state(),
                        onBack = {},
                        onResume = {}, onCancel = {}, onRetry = {}, onDelete = {},
                        onDeleteTempCache = {}, onConfirmDelete = {}, onDismissDelete = {},
                        onRequestClearCache = {}, onConfirmClearCache = {},
                        onDismissClearCache = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Some Anime").assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_clear_all").assertIsDisplayed()
        composeRule.onNodeWithText("Delete temporary cache").assertIsDisplayed()
    }

    @Test
    fun primaryContentSurvivesAMultiWindowResize() {
        composeRule.setContent {
            PaheBatcherTheme {
                // DECISIONS 111: split-screen / freeform resize — a window that
                // is BOTH narrower than a full phone and much shorter than the
                // tall test heights used above (480×360, neither 320×720 nor
                // 1000×720), so the layout has to survive a real resize.
                Box(Modifier.size(480.dp, 360.dp)) {
                    SessionsScreen(
                        uiState = state(),
                        onBack = {},
                        onResume = {}, onCancel = {}, onRetry = {}, onDelete = {},
                        onDeleteTempCache = {}, onConfirmDelete = {}, onDismissDelete = {},
                        onRequestClearCache = {}, onConfirmClearCache = {},
                        onDismissClearCache = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Some Anime").assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_clear_all").assertIsDisplayed()
        // 360dp tall: the row's action band sits below the fold — REACHABLE by
        // scrolling is the property a resized window must keep (Decision 111:
        // the content survives, it is not clipped away or lost).
        composeRule.onNodeWithText("Resume").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun primaryContentSurvivesALargeFontScale() {
        composeRule.setContent {
            PaheBatcherTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 1.6f),
                ) {
                    SessionsScreen(
                        uiState = state(),
                        onBack = {},
                        onResume = {}, onCancel = {}, onRetry = {}, onDelete = {},
                        onDeleteTempCache = {}, onConfirmDelete = {}, onDismissDelete = {},
                        onRequestClearCache = {}, onConfirmClearCache = {},
                        onDismissClearCache = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Some Anime").assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_clear_all").assertIsDisplayed()
        composeRule.onNodeWithText("Resume").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // DECISIONS 111 — the confirmations are new surfaces too
    // -----------------------------------------------------------------------

    @Test
    fun deleteConfirmationSurvivesTheLargestFontScale() {
        setScreen(
            state(
                deleteDialog = DeleteSessionDialogState(
                    sessionId = "sess-1",
                    title = "Some Anime",
                    exportedCount = 1,
                    unexportedNames = listOf("Some_Anime-001.mp4", "Some_Anime-002.mp4"),
                    temporaryBytes = 2_048L,
                ),
            ),
            fontScale = 2f,
        )

        composeRule.onNodeWithTag("sessions_delete_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("• Some_Anime-001.mp4").assertIsDisplayed()
        composeRule
            .onNodeWithText("Exported videos in your library are never touched.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_confirm_delete").assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_cancel_delete").assertIsDisplayed()
    }

    @Test
    fun clearAllConfirmationSurvivesTheLargestFontScale() {
        setScreen(
            state(clearCacheDialog = ClearCacheDialogState(totalBytes = 1_600_000L)),
            fontScale = 2f,
        )

        composeRule.onNodeWithTag("sessions_clear_dialog").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Removes 1.5 MB of temporary download files. Sessions and completed videos are kept.",
            )
            .assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_confirm_clear").assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_cancel_clear").assertIsDisplayed()
    }
}
