package com.pahebatcher.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.domain.watchlist.RelinkCandidate
import com.pahebatcher.app.domain.watchlist.WatchlistCheckResult
import com.pahebatcher.app.domain.watchlist.WatchlistCheckStatus
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import com.pahebatcher.app.ui.watchlist.RelinkState
import com.pahebatcher.app.ui.watchlist.WatchlistScreen
import com.pahebatcher.app.ui.watchlist.WatchlistUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Render contract of the watchlist list surface (minimal Phase 13
 * prerequisite; AGENTS §8).
 *
 * Composed IN ISOLATION from hand-built immutable state, so no repository,
 * database or network is touched. It proves that the four `wl` verbs the user
 * can reach are bound to the caller's actions (add / check now / remove /
 * relink), that the screen renders the real entries and the real per-entry
 * check result — including an honest failure line — that a running check
 * disables the controls that must not start a second run, and (Phase 13,
 * DECISIONS 108.5-108.8) that the Relink dialog renders every honest state:
 * loading/outcome, preselected single candidate with Current→New session ids,
 * multiple candidates gated on an explicit selection, and Cancel.
 *
 * The check BEHIND those controls and the relink's repository writes are
 * covered by the JVM suites (`WatchlistCheckerTest`, `WatchlistRelinkTest`).
 */
@RunWith(AndroidJUnit4::class)
class WatchlistScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(
        session: String = "sess-1",
        title: String = "One Piece",
        lastCheckedAt: Long = 0L,
    ) = WatchlistEntity(
        sessionId = session,
        url = "https://animepahe.local/anime/$session",
        animeSession = session,
        host = "animepahe.local",
        title = title,
        quality = 1080,
        audioLang = "jpn",
        outputDir = "./downloads",
        maxParallel = 2,
        hlsWorkers = 24,
        keepTemp = false,
        autoRetry = 2,
        addedAt = 1_000L,
        lastCheckedAt = lastCheckedAt,
        downloadedEpisodesJson = "[]",
    )

    private var typedUrl: String? = null
    private var lastRemoved: String? = null
    private var addClicks = 0
    private var checkClicks = 0
    private var lastRelinked: WatchlistEntity? = null
    private var selectedCandidate: Int? = null
    private var confirmClicks = 0
    private var dismissClicks = 0

    /**
     * Held in Compose state and updated by [onUrlChange] exactly the way the
     * production ViewModel updates `WatchlistUiState.urlInput` — so the field is
     * round-tripped through state instead of being fed a value that never
     * changes (which is what production never does).
     */
    private var uiState by mutableStateOf(WatchlistUiState())

    private fun setScreen(state: WatchlistUiState) {
        uiState = state
        composeRule.setContent {
            PaheBatcherTheme {
                WatchlistScreen(
                    uiState = uiState,
                    onBack = {},
                    onUrlChange = { value ->
                        typedUrl = value
                        uiState = uiState.copy(urlInput = value)
                    },
                    onAdd = { addClicks++ },
                    onRemove = { lastRemoved = it },
                    onCheckNow = { checkClicks++ },
                    onRelink = { lastRelinked = it },
                    // Round-tripped through state exactly like production:
                    // the ViewModel updates `relink.selectedIndex` on select.
                    onSelectRelinkCandidate = { index ->
                        selectedCandidate = index
                        uiState = uiState.copy(
                            relink = uiState.relink?.copy(selectedIndex = index),
                        )
                    },
                    // Production closes the dialog after the flow — mirrored here.
                    onConfirmRelink = {
                        confirmClicks++
                        uiState = uiState.copy(relink = null)
                    },
                    onDismissRelink = {
                        dismissClicks++
                        uiState = uiState.copy(relink = null)
                    },
                )
            }
        }
    }

    @Test
    fun anEmptyWatchlistSaysSoAndOffersNoCheck() {
        setScreen(WatchlistUiState())

        composeRule.onNodeWithTag("watchlist_empty").assertIsDisplayed()
        // Nothing to check → the action is honestly disabled, not decorative.
        composeRule.onNodeWithTag("watchlist_check_now").assertIsNotEnabled()
        composeRule.onNodeWithTag("watchlist_row").assertDoesNotExist()
    }

    @Test
    fun theAddFieldAndButtonAreBoundToTheCallersActions() {
        setScreen(WatchlistUiState(urlInput = ""))

        composeRule
            .onNodeWithTag("watchlist_url_field")
            .performTextInput("https://animepahe.local/anime/sess-9")
        // The field really holds what was typed (round-tripped through state,
        // exactly as the ViewModel does in production).
        composeRule.onNodeWithText("https://animepahe.local/anime/sess-9").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_add").performClick()

        assertEquals(
            "the typed URL is what the ViewModel receives",
            "https://animepahe.local/anime/sess-9",
            typedUrl,
        )
        assertEquals(1, addClicks)
    }

    @Test
    fun checkNowIsEnabledForSavedEntriesAndBoundToTheCaller() {
        setScreen(WatchlistUiState(entries = listOf(entry())))

        composeRule.onNodeWithText("One Piece").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_check_now").assertIsEnabled().performClick()

        assertEquals(1, checkClicks)
    }

    @Test
    fun eachRowRendersItsOwnRealCheckResult() {
        val queued = WatchlistCheckResult(
            sessionId = "sess-1",
            title = "One Piece",
            status = WatchlistCheckStatus.NEW_EPISODES,
            episodesTotal = 12,
            newEpisodes = 2,
            queued = 2,
            message = "2 new episode(s) queued.",
        )
        setScreen(
            WatchlistUiState(
                entries = listOf(entry()),
                results = mapOf("sess-1" to queued),
            ),
        )

        composeRule.onNodeWithText("2 new episode(s) queued.").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_row_remove").assertIsDisplayed()
    }

    @Test
    fun aFailedCheckIsRenderedAsAFailureNotAsASuccess() {
        val dead = WatchlistCheckResult(
            sessionId = "sess-1",
            title = "One Piece",
            status = WatchlistCheckStatus.LINK_DEAD,
            message = "Link dead for 'One Piece' — no session found. Re-add it with the anime's URL.",
        )
        setScreen(
            WatchlistUiState(
                entries = listOf(entry(title = "sess-1")),
                results = mapOf("sess-1" to dead),
            ),
        )

        composeRule
            .onNodeWithText(
                "Link dead for 'One Piece' — no session found. Re-add it with the anime's URL.",
            )
            .assertIsDisplayed()
        // The placeholder title never masquerades as a real anime name.
        composeRule.onNodeWithText("sess-1").assertIsDisplayed()
    }

    @Test
    fun removeIsBoundToTheEntriesSession() {
        setScreen(WatchlistUiState(entries = listOf(entry(session = "sess-7"))))

        composeRule.onNodeWithTag("watchlist_row_remove").performClick()

        assertEquals("sess-7", lastRemoved)
    }

    @Test
    fun aRunningCheckDisablesEverythingThatCouldStartASecondRun() {
        setScreen(
            WatchlistUiState(
                entries = listOf(entry()),
                checking = true,
                runningSessionId = "sess-1",
            ),
        )

        composeRule.onNodeWithTag("watchlist_checking").assertIsDisplayed()
        composeRule.onNodeWithTag("watchlist_check_now").assertIsNotEnabled()
        composeRule.onNodeWithTag("watchlist_add").assertIsNotEnabled()
        composeRule.onNodeWithTag("watchlist_row_remove").assertIsNotEnabled()
        // DECISIONS 108.4/97.4: the relink's candidate search is network-bound
        // work too — no second scan may start while a check run is active.
        composeRule.onNodeWithTag("watchlist_row_relink").assertIsNotEnabled()
    }

    // -----------------------------------------------------------------------
    // manual relink render contract
    // -----------------------------------------------------------------------

    @Test
    fun everyEntryExposesARelinkActionBoundToItsOwnEntry() {
        setScreen(
            WatchlistUiState(
                entries = listOf(
                    entry(session = "sess-1", title = "One Piece"),
                    entry(session = "sess-2", title = "Two Piece"),
                ),
            ),
        )

        // 108.2: EVERY entry — no status gating — each with its own control.
        assertEquals(
            "one Relink control per saved entry",
            2,
            composeRule.onAllNodesWithTag("watchlist_row_relink").fetchSemanticsNodes().size,
        )
        composeRule.onAllNodesWithTag("watchlist_row_relink")[1].performClick()

        assertEquals(
            "the second row's Relink carries the second entry",
            "sess-2",
            lastRelinked?.sessionId,
        )
    }

    @Test
    fun relinkDialogShowsTheHonestNoCandidateOutcome() {
        setScreen(
            WatchlistUiState(
                entries = listOf(entry()),
                relink = RelinkState(
                    entry = entry(),
                    message = "Link dead for 'One Piece' — no session found. " +
                        "Re-add it with the anime's URL.",
                ),
            ),
        )

        // 108.6: the EXACT wording shared with the automatic path.
        composeRule
            .onNodeWithText(
                "Link dead for 'One Piece' — no session found. Re-add it with the anime's URL.",
            )
            .assertIsDisplayed()
        // No candidates → no Confirm control at all (nothing selectable).
        composeRule.onNodeWithTag("relink_confirm").assertDoesNotExist()
        composeRule.onNodeWithTag("relink_cancel").assertIsDisplayed().performClick()

        assertEquals(1, dismissClicks)
        composeRule.onNodeWithTag("relink_cancel").assertDoesNotExist()
    }

    @Test
    fun relinkDialogPreselectsASingleCandidateAndBindsConfirm() {
        val candidate = RelinkCandidate(
            session = "sess-new-1234567890",
            title = "One Piece",
            type = "TV",
            year = 1999,
            episodes = 1000,
            score = "8.7",
        )
        setScreen(
            WatchlistUiState(
                entries = listOf(entry()),
                relink = RelinkState(
                    entry = entry(),
                    candidates = listOf(candidate),
                    selectedIndex = 0, // 108.7: single candidate preselected
                ),
            ),
        )

        composeRule.onNodeWithTag("relink_candidate").assertIsDisplayed()
        // 108.5: the search row's real metadata, never a fabricated field.
        composeRule.onNodeWithText("TV · 1999 · 1000 eps · 8.7").assertIsDisplayed()
        // 108.9: current → new session, both shortened.
        composeRule.onNodeWithText("Current: sess-1").assertIsDisplayed()
        composeRule.onNodeWithTag("relink_new_session").assertIsDisplayed()
        // 108.8: preselection means Confirm is usable immediately.
        composeRule.onNodeWithTag("relink_confirm").assertIsEnabled().performClick()

        assertEquals(1, confirmClicks)
        // The production flow closes the dialog after the rescan — mirrored here.
        composeRule.onNodeWithTag("relink_confirm").assertDoesNotExist()
    }

    @Test
    fun relinkDialogRequiresAnExplicitSelectionBeforeConfirm() {
        setScreen(
            WatchlistUiState(
                entries = listOf(entry()),
                relink = RelinkState(
                    entry = entry(),
                    candidates = listOf(
                        RelinkCandidate(session = "sess-cand-a", title = "One Piece"),
                        RelinkCandidate(session = "sess-cand-b", title = "One Piece"),
                    ),
                    selectedIndex = -1, // 108.8: multiple → nothing selected yet
                ),
            ),
        )

        composeRule.onNodeWithTag("relink_confirm").assertIsNotEnabled()

        composeRule.onAllNodesWithTag("relink_candidate")[1].performClick()
        assertEquals("the second candidate was selected", 1, selectedCandidate)
        // The selection round-tripped through state → Confirm becomes usable.
        composeRule.onNodeWithTag("relink_confirm").assertIsEnabled().performClick()
        assertEquals(1, confirmClicks)
    }
}
