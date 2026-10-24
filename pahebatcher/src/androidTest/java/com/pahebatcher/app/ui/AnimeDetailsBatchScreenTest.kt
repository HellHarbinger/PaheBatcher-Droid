package com.pahebatcher.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.percentOffset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.ui.details.AnimeDetailsScreen
import com.pahebatcher.app.ui.details.AnimeDetailsUiState
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 12C + Option C — render contract of the batch-selection surface on
 * [AnimeDetailsScreen] (AGENTS §8).
 *
 * The screen is composed IN ISOLATION with hand-built immutable states, so no
 * repository, database or network is touched: this suite proves that every
 * batch control is bound to the caller's action, that the selection it
 * displays is the real state — the selected NUMBERS over the CURRENT audio
 * context's one-row-per-number list (DECISIONS 143; there is no per-variant
 * highlight store anymore) — and that a running batch reports live counters
 * with a working Stop instead of a frozen spinner.
 *
 * The resolution/queueing BEHIND those controls is covered by the JVM suites
 * (`AnimeDetailsViewModelBatchTest`, `AnimeDetailsAudioSelectionTest`) and the
 * production hand-off instrumentation tests.
 */
@RunWith(AndroidJUnit4::class)
class AnimeDetailsBatchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val episodes = listOf(
        com.pahebatcher.app.domain.model.EpisodeInfo(
            number = 1f,
            session = "ep1-jpn",
            title = "Beginnings",
            fansub = "SubsPlease",
            audio = "jpn",
            playUrl = "https://example.invalid/play/1",
        ),
        com.pahebatcher.app.domain.model.EpisodeInfo(
            number = 1f,
            session = "ep1-eng",
            title = "Beginnings",
            fansub = "DubsPlease",
            audio = "eng",
            playUrl = "https://example.invalid/play/1b",
        ),
        com.pahebatcher.app.domain.model.EpisodeInfo(
            number = 2f,
            session = "ep2-jpn",
            title = "Next Step",
            fansub = "SubsPlease",
            audio = "jpn",
            playUrl = "https://example.invalid/play/2",
        ),
    )

    // A longer list for the layout test: enough rows that some sit BELOW the
    // scrollable batch panel's initial viewport and must be reached by scroll.
    private val manyEpisodes = (1..12).map { n ->
        com.pahebatcher.app.domain.model.EpisodeInfo(
            number = n.toFloat(),
            session = "ep$n-jpn",
            title = "Episode $n",
            fansub = "SubsPlease",
            audio = "jpn",
            playUrl = "https://example.invalid/play/$n",
        )
    }

    private var lastRange: String? = null
    private var lastLatest: Int? = null
    private var lastOrdinalFrom: Int? = null
    private var lastOrdinalTo: Int? = null

    private fun setScreen(
        state: AnimeDetailsUiState,
        onToggleBatchMode: () -> Unit = {},
        onToggleBatchEpisode: (com.pahebatcher.app.domain.model.EpisodeInfo) -> Unit = {},
        onSelectAll: () -> Unit = {},
        onClear: () -> Unit = {},
        onSelectRange: (String) -> Unit = { lastRange = it },
        onSelectLatest: (Int) -> Unit = { lastLatest = it },
        onStart: () -> Unit = {},
        onStartPage: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            PaheBatcherTheme {
                AnimeDetailsScreen(
                    uiState = state,
                    onBack = {},
                    onRetry = {},
                    onSelectEpisode = {},
                    onRetryResolution = {},
                    onDownload = {},
                    onToggleBatchMode = onToggleBatchMode,
                    onToggleBatchEpisode = onToggleBatchEpisode,
                    onSelectAllEpisodes = onSelectAll,
                    onClearBatchSelection = onClear,
                    onSelectEpisodeRange = onSelectRange,
                    onRequestEpisodeRange = { from, to ->
                        lastOrdinalFrom = from
                        lastOrdinalTo = to
                    },
                    onSelectLatestEpisodes = onSelectLatest,
                    onStartBatch = onStart,
                    onStartPageBatch = onStartPage,
                    onCancelBatch = onCancel,
                )
            }
        }
    }

    private fun batchState(
        batchMode: Boolean = true,
        selectedNumbers: Set<Float> = emptySet(),
        // Option C (DECISIONS 143): the screen-level context + explicit
        // overrides — the only audio states a hand-built state can carry.
        selectedAudio: String = "jpn",
        explicitAudioOverrides: Map<Float, String> = emptyMap(),
        batchRunning: Boolean = false,
        batchFinished: Boolean = false,
        batchQueued: Int = 0,
        batchSkipped: Int = 0,
        batchFailed: Int = 0,
        batchTotal: Int = 0,
        batchError: String? = null,
        // Paging metadata. `null` (the default) exercises the UNKNOWN-total
        // branch; a real total turns the ordinal range selector's fallback off
        // and lets it take the fetching ordinal path.
        apiTotal: Int? = null,
        perPage: Int? = null,
        apiLastPage: Int? = null,
    ) = AnimeDetailsUiState(
        session = "sess-batch",
        loading = false,
        title = "Test Anime",
        episodes = episodes,
        selectedAudio = selectedAudio,
        explicitAudioOverrides = explicitAudioOverrides,
        batchMode = batchMode,
        selectedNumbers = selectedNumbers,
        batchRunning = batchRunning,
        batchFinished = batchFinished,
        batchQueued = batchQueued,
        batchSkipped = batchSkipped,
        batchFailed = batchFailed,
        batchTotal = batchTotal,
        batchError = batchError,
        apiTotal = apiTotal,
        perPage = perPage,
        apiLastPage = apiLastPage,
    )

    @Test
    fun enteringBatchModeIsOfferedByTheTopBarAction() {
        setScreen(batchState(batchMode = false))

        composeRule.onNodeWithContentDescription("Select episodes").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Exit episode selection").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_batch_panel").assertDoesNotExist()
    }

    @Test
    fun whileInBatchModeTheSameActionAdvertisesLeavingIt() {
        setScreen(batchState(batchMode = true))

        composeRule.onNodeWithContentDescription("Exit episode selection").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Select episodes").assertDoesNotExist()
    }

    @Test
    fun togglingTheTopBarActionIsBoundToTheCallersAction() {
        var toggled = 0
        setScreen(batchState(batchMode = false), onToggleBatchMode = { toggled++ })

        composeRule.onNodeWithTag("anime_details_batch_toggle").performClick()

        assertEquals(1, toggled)
    }

    @Test
    fun thePanelCountsEpisodeNumbersNotVariants() {
        setScreen(batchState(selectedNumbers = setOf(1f)))

        composeRule.onNodeWithTag("anime_details_batch_panel").assertIsDisplayed()
        composeRule
            .onNodeWithText("1 of 2 episode numbers selected")
            .assertIsDisplayed()
        // Two NUMBERS exist in the jpn context (the eng sibling of episode 1
        // is not a separate row) — the denominator counts what is selectable.
        composeRule.onNodeWithText("Download selected (1)").assertIsDisplayed()
    }

    @Test
    fun allClearAndStartAreBoundToTheCallersActions() {
        var all = 0
        var clear = 0
        var start = 0
        setScreen(
            batchState(selectedNumbers = setOf(1f)),
            onSelectAll = { all++ },
            onClear = { clear++ },
            onStart = { start++ },
        )

        composeRule.onNodeWithTag("anime_details_batch_all").performClick()
        composeRule.onNodeWithTag("anime_details_batch_clear").performClick()
        composeRule.onNodeWithTag("anime_details_batch_download").performClick()

        assertEquals(1, all)
        assertEquals(1, clear)
        assertEquals(1, start)
    }

    /**
     * Phase 12 UX correction — the panel previously showed TWO range controls
     * (an expression field AND the ordinal From/To), which the user found
     * cluttered and confusing. The expression field is removed; only the ordinal
     * selector remains, so exactly one range surface is on screen.
     */
    @Test
    fun onlyOneRangeControlIsShownAfterConsolidation() {
        setScreen(batchState())

        // The removed expression field is gone entirely.
        composeRule.onNodeWithTag("anime_details_batch_range").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_batch_range_apply").assertDoesNotExist()
        // The ordinal selector is the single surviving range control.
        composeRule.onNodeWithTag("anime_details_batch_ordinal_from").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_batch_ordinal_to").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_batch_ordinal_apply").assertIsDisplayed()
    }

    /**
     * With a KNOWN total the From/To bounds are handed to the ordinal transition
     * (`onRequestEpisodeRange`) as the parsed integers — the path that validates
     * and fetches only the windows the range spans.
     */
    @Test
    fun theOrdinalRangeFieldHandsBoundsToTheProductionTransition() {
        lastOrdinalFrom = null
        lastOrdinalTo = null
        setScreen(batchState(apiTotal = 50, perPage = 30, apiLastPage = 2))

        composeRule.onNodeWithTag("anime_details_batch_ordinal_from").performTextInput("3")
        composeRule.onNodeWithTag("anime_details_batch_ordinal_to").performTextInput("7")
        composeRule.onNodeWithTag("anime_details_batch_ordinal_apply").performClick()

        assertEquals(3, lastOrdinalFrom)
        assertEquals(7, lastOrdinalTo)
    }

    /**
     * When the listing exposes NO total the ordinal path cannot validate or fetch
     * (`EpisodePaging.validateRange` reports `UnknownTotal`), so the same bounds
     * fall back to the loaded-catalogue NUMBER range via `selectEpisodeRange`,
     * keeping a range affordance on titles without pagination metadata instead of
     * dead-ending (SPEC §22's `1-12`).
     */
    @Test
    fun unknownTotalFallsBackToTheLoadedCatalogueRange() {
        lastRange = null
        lastOrdinalFrom = null
        // `batchState()` defaults to apiTotal = null (unknown total).
        setScreen(batchState())

        composeRule.onNodeWithTag("anime_details_batch_ordinal_from").performTextInput("1")
        composeRule.onNodeWithTag("anime_details_batch_ordinal_to").performTextInput("2")
        composeRule.onNodeWithTag("anime_details_batch_ordinal_apply").performClick()

        assertEquals("1-2", lastRange)
        assertNull("the ordinal transition was NOT taken for an unknown total", lastOrdinalFrom)
    }

    /**
     * The core accessibility regression: opening batch mode must NOT hide the
     * episode list. Both the batch panel and an episode row are on screen at once
     * (the panel scrolls WITH the list instead of starving it of height).
     */
    @Test
    fun batchControlsDoNotHideTheEpisodeList() {
        setScreen(batchState(selectedNumbers = setOf(1f)))

        composeRule.onNodeWithTag("anime_details_batch_panel").assertIsDisplayed()
        composeRule.onAllNodesWithTag("anime_details_episode")[0].assertIsDisplayed()
    }

    /**
     * Episode rows below the (now scrollable) batch panel stay REACHABLE: a row
     * past the initial viewport is scrolled into view rather than being pushed
     * off-screen by the controls — the reported "batch view makes the list
     * inaccessible" defect.
     */
    @Test
    fun episodeRowsRemainReachableWhileBatchIsActive() {
        setScreen(batchState().copy(episodes = manyEpisodes))

        // The first row is visible under the panel without scrolling…
        composeRule.onNodeWithText("Episode 1").assertIsDisplayed()
        // …and a row far below the fold is reachable by scrolling the list.
        composeRule.onNodeWithTag("anime_details_episodes")
            .performScrollToNode(hasText("Episode 10"))
        composeRule.onNodeWithText("Episode 10").assertIsDisplayed()
    }

    /**
     * SPEC §22 — a clear selection summary. The count alone cannot show WHICH
     * numbers a selection holds, so the panel names them (sorted, range-collapsed).
     */
    @Test
    fun selectionSummaryNamesTheSelectedNumbers() {
        setScreen(batchState(selectedNumbers = setOf(1f, 2f, 3f, 5f)))

        composeRule.onNodeWithTag("anime_details_batch_selection_summary")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Selected: 1–3, 5").assertIsDisplayed()
    }

    /**
     * Phase 12 UX correction — the batch panel is the episode list's FIRST item,
     * so it is only on screen when the list is at the very top. A list scrolled
     * down before batch mode was entered would otherwise keep that offset and
     * leave the panel hidden above the viewport: the user would enter batch mode
     * and see no controls. Turning batch mode ON must reset the scroll to the
     * top so the panel comes into view.
     */
    @Test
    fun enteringBatchModeResetsTheScrollSoThePanelIsVisible() {
        var batchMode by mutableStateOf(false)
        composeRule.setContent {
            PaheBatcherTheme {
                AnimeDetailsScreen(
                    uiState = batchState(batchMode = batchMode).copy(episodes = manyEpisodes),
                    onBack = {},
                    onRetry = {},
                    onSelectEpisode = {},
                    onRetryResolution = {},
                    onDownload = {},
                    onToggleBatchMode = { batchMode = !batchMode },
                )
            }
        }

        // Not in batch mode yet, so no panel exists — scroll the long list far
        // down, well past where the panel would sit.
        composeRule.onNodeWithTag("anime_details_episodes")
            .performScrollToNode(hasText("Episode 10"))
        composeRule.onNodeWithText("Episode 10").assertIsDisplayed()

        // Entering batch mode must reset the scroll to the top; without the
        // reset the panel (index 0) would be scrolled off-screen above and this
        // assertion would fail.
        composeRule.onNodeWithTag("anime_details_batch_toggle").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("anime_details_batch_panel").assertIsDisplayed()
    }

    /** SPEC §22 — the newest-N entry exists, gates itself and hands over n. */
    @Test
    fun theLatestFieldOnlyOffersActionForACountOfAtLeastOne() {
        lastLatest = null
        setScreen(batchState())

        val field = composeRule.onNodeWithTag("anime_details_batch_latest")
        val apply = composeRule.onNodeWithTag("anime_details_batch_latest_apply")
        field.assertIsDisplayed()
        apply.assertIsNotEnabled()

        field.performTextInput("3")
        composeRule.onNodeWithTag("anime_details_batch_latest_apply").assertIsEnabled()
        apply.performClick()

        assertEquals(3, lastLatest)
    }

    /** A count of 0 is rejected at the control — never forwarded as episode 1. */
    @Test
    fun aLatestCountOfZeroIsNeverActionable() {
        lastLatest = null
        setScreen(batchState())

        composeRule.onNodeWithTag("anime_details_batch_latest").performTextInput("0")
        composeRule.onNodeWithTag("anime_details_batch_latest_apply").assertIsNotEnabled()

        assertNull("nothing was handed to the ViewModel", lastLatest)
    }

    /** Like every other selection control, Latest is inert while a batch runs. */
    @Test
    fun theLatestControlIsDisabledWhileABatchIsRunning() {
        lastLatest = null
        setScreen(batchState(batchRunning = true))

        composeRule.onNodeWithTag("anime_details_batch_latest").assertIsNotEnabled()
        composeRule.onNodeWithTag("anime_details_batch_latest_apply").assertIsNotEnabled()

        assertNull(lastLatest)
    }

    @Test
    fun aRowTapInBatchModeReportsThatEpisodesNumber() {
        var toggled: com.pahebatcher.app.domain.model.EpisodeInfo? = null
        setScreen(batchState(), onToggleBatchEpisode = { toggled = it })

        val rows = composeRule.onAllNodesWithTag("anime_details_episode")
        // Click the row's NUMBER header — the row's own touch area — never the
        // nested "change audio" control below it (that control has its own test).
        rows[0].performTouchInput { click(percentOffset(0.5f, 0.12f)) }

        assertEquals(
            "the first row is episode 1 — the tap hands that NUMBER's row over",
            1f,
            toggled!!.number,
        )
    }

    /**
     * Option C (DECISIONS 143 / 144.6) — the batch list is ONE row per episode
     * NUMBER: with `selectedAudio = eng` BOTH numbers render, exactly once
     * each (number 2's row carries the eng request on its own play page), and
     * only the SELECTED number is highlighted.
     *
     * The former behaviour — hiding number 2 because release metadata lacked
     * eng — was the listing-only gate DECISIONS 144.6 removed: selection and
     * row visibility are decided by the NUMBER, availability by the play page.
     */
    @Test
    fun theBatchListRendersOneRowPerNumberWhateverTheCurrentContextIs() {
        setScreen(
            batchState(
                selectedAudio = "eng",
                selectedNumbers = setOf(1f),
            ),
        )

        val rows = composeRule.onAllNodesWithTag("anime_details_episode")
        rows.assertCountEquals(2)
        rows[0].assertIsSelected()
        rows[1].assertIsNotSelected()
        composeRule.onNodeWithText("Episode 1").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 2").assertIsDisplayed()
    }

    @Test
    fun aRunningBatchShowsLiveCountersAndAStopThatIsBoundToTheCaller() {
        var cancelled = 0
        setScreen(
            batchState(
                selectedNumbers = setOf(1f, 2f),
                batchRunning = true,
                batchQueued = 1,
                batchFailed = 1,
                batchTotal = 2,
            ),
            onCancel = { cancelled++ },
        )

        composeRule
            .onNodeWithText("Queued 1 · Skipped 0 · Failed 1 of 2")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_batch_cancel").performClick()
        assertEquals(1, cancelled)

        // Controls that would change the selection are disabled while it runs.
        composeRule.onNodeWithTag("anime_details_batch_all").assertIsNotEnabled()
        composeRule.onNodeWithTag("anime_details_batch_download").assertDoesNotExist()
    }

    @Test
    fun aFinishedBatchShowsItsSummaryAndItsHonestError() {
        setScreen(
            batchState(
                // The selection survives a finished run so the failed numbers
                // can be started again — that is why it must still be enabled.
                // A strict SUBSET of the page ({1} vs the page's {1, 2}) so
                // "Download selected" is shown (when the selection is exactly
                // the page, only the page-scoped button appears instead).
                selectedNumbers = setOf(1f),
                batchFinished = true,
                batchQueued = 1,
                batchSkipped = 0,
                batchFailed = 1,
                batchTotal = 2,
                batchError = "Timed out waiting for download capacity.",
            ),
        )

        composeRule
            .onNodeWithText("Done: queued 1 · skipped 0 · failed 1 of 2")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Timed out waiting for download capacity.")
            .assertIsDisplayed()
        // The selection survives a finished run so it can be started again.
        composeRule.onNodeWithTag("anime_details_batch_download").assertIsEnabled()
    }

    /**
     * Phase 12 UI review — "Download this page" is the primary one-step action
     * that queues the current app page, and it previously had NO Compose-level
     * coverage. It must be bound to the caller, labelled with the page's REAL
     * distinct-episode count (audio variants of one number are one episode), and
     * disabled only when it must be.
     */
    @Test
    fun downloadThisPageIsBoundAndLabelledWithTheRealDistinctEpisodeCount() {
        var pageStarts = 0
        // The fixture carries episodes 1 (jpn), 1 (eng) and 2 (jpn): two
        // DISTINCT numbers, so the page offers two episodes.
        setScreen(batchState(), onStartPage = { pageStarts++ })

        composeRule.onNodeWithText("Download this page (2 episodes)").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_batch_page_download").performClick()
        assertEquals(1, pageStarts)
    }

    /**
     * Phase 12 UI review — when a bulk action selects EXACTLY the current page's
     * numbers, "Download selected" would queue the identical set as "Download
     * this page". Only the page-scoped (more robust) button is shown, so the user
     * never sees two identical primary CTAs for one set.
     */
    @Test
    fun whenTheSelectionIsExactlyThePageOnlyThePageScopedButtonShows() {
        // The page offers numbers {1, 2}; selecting precisely that set collapses
        // the two CTAs into the page-scoped one.
        setScreen(batchState(selectedNumbers = setOf(1f, 2f)))

        composeRule.onNodeWithTag("anime_details_batch_page_download").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_batch_download").assertDoesNotExist()
    }

    /**
     * Phase 12 UI review — a genuine SUBSET (or a cross-page set) still needs both
     * actions: "Download this page" for the whole page, "Download selected" for
     * the chosen subset.
     */
    @Test
    fun whenTheSelectionIsASubsetBothDownloadButtonsShow() {
        setScreen(batchState(selectedNumbers = setOf(1f)))

        composeRule.onNodeWithTag("anime_details_batch_page_download").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_batch_download").assertIsDisplayed()
    }

    /**
     * Phase 12 UI review — "All" was relabelled "Select all" so its SELECTION-only
     * role (Python `--all`, SPEC §22) cannot be read as a download and is not
     * confused with "Download this page" (which queues the current page in one
     * step). It remains a distinct, required workflow: on a ≤100-episode title it
     * selects the whole title across its app pages, which "Download this page"
     * (current page only) cannot.
     */
    @Test
    fun selectAllIsLabelledAsASelectionNotADownload() {
        setScreen(batchState())

        composeRule.onNodeWithText("Select all").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_batch_all").assertIsEnabled()
    }

    @Test
    fun leavingBatchModeHidesThePanelAndRestoresTheSingleEpisodeFlow() {
        setScreen(batchState(batchMode = false))

        composeRule.onNodeWithTag("anime_details_batch_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_batch_toggle").assertIsDisplayed()
    }
}
