package com.pahebatcher.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.model.AudioProbeState
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.ScanProgress
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.ui.details.AnimeDetailsScreen
import com.pahebatcher.app.ui.details.AnimeDetailsUiState
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 11.5E-2 — render contract of [AnimeDetailsScreen] (PHASE11_ARCHITECTURE_V2 §13,
 * AGENTS §8). Extended by 11.5E-3 with the selection → resolution branches.
 *
 * The screen is composed IN ISOLATION with hand-built immutable
 * [AnimeDetailsUiState]s, so no repository, database or network is touched; the
 * production wiring itself is covered by [AnimeDetailsDestinationTest],
 * [StreamResolverProductionWiringTest] and the device verification of the slice.
 *
 * Covered: the four honest scan branches (loading / success / empty / error),
 * that a completed empty scan never renders as an error, that a failure never
 * renders as an episode list, that only metadata the [EpisodeInfo] model really
 * carries is shown, that Retry is bound to the caller's action — and, for 11.5E-3,
 * that a row is genuinely selectable (reporting the exact episode), that idle
 * renders NO resolution panel, and that resolving / resolved / failed render
 * exactly one honest branch each (the resolved branch showing the resolver's own
 * M3U8 URL, the failed branch binding Retry to the caller's action).
 */
class AnimeDetailsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(
        state: AnimeDetailsUiState,
        onRetry: () -> Unit = {},
        onSelectEpisode: (EpisodeInfo) -> Unit = {},
        onRetryResolution: () -> Unit = {},
        onDownload: () -> Unit = {},
        onStream: () -> Unit = {},
        onRetryProbe: () -> Unit = {},
        onGoToPage: (Int) -> Unit = {},
    ) {
        composeRule.setContent {
            PaheBatcherTheme {
                AnimeDetailsScreen(
                    uiState = state,
                    onBack = {},
                    onRetry = onRetry,
                    onSelectEpisode = onSelectEpisode,
                    onRetryResolution = onRetryResolution,
                    onDownload = onDownload,
                    onStream = onStream,
                    onRetryProbe = onRetryProbe,
                    onGoToPage = onGoToPage,
                )
            }
        }
    }

    @Test
    fun loadingBranchShowsScanProgressAndTheBackAffordance() {
        setScreen(AnimeDetailsUiState(session = "sess-1", loading = true))

        composeRule.onNodeWithTag("anime_details_loading").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        // No result/empty/error content may leak into the pending branch.
        composeRule.onNodeWithTag("anime_details_empty").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
        composeRule.onNodeWithText("Episode 1").assertDoesNotExist()
    }

    @Test
    fun successBranchRendersOnlyMetadataTheEpisodeModelReallyHas() {
        setScreen(
            AnimeDetailsUiState(
                session = "sess-1",
                loading = false,
                title = "One Piece",
                // Option C: the screen renders ONE context row per number, so a
                // hand-built success state names its context explicitly.
                selectedAudio = "jpn",
                episodes = listOf(
                    EpisodeInfo(
                        number = 1f,
                        session = "e1",
                        title = "Romance Dawn",
                        fansub = "SubsPlease",
                        audio = "jpn",
                        playUrl = "https://example.invalid/play",
                    ),
                    EpisodeInfo(
                        number = 13.5f,
                        session = "e2",
                        title = "",
                        fansub = "",
                        audio = "jpn",
                        playUrl = "https://example.invalid/play",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("One Piece").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_episodes").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 1").assertIsDisplayed()
        composeRule.onNodeWithText("Romance Dawn").assertIsDisplayed()
        composeRule.onNodeWithText("jpn · SubsPlease").assertIsDisplayed()
        // Fractional episode number renders as its number; with a blank title and
        // blank fansub the row still renders through its own audio meta.
        composeRule.onNodeWithText("Episode 13.5").assertIsDisplayed()
        composeRule.onNodeWithText("jpn").assertIsDisplayed()
        // The model's playUrl is the RESOLVER'S INPUT (11.5E-3), never display
        // content: only what the resolver really produced may surface here.
        composeRule.onNodeWithText("https://example.invalid/play").assertDoesNotExist()

        composeRule.onNodeWithTag("anime_details_empty").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun emptyBranchIsExplicitAndIsNeverRenderedAsAnError() {
        setScreen(
            AnimeDetailsUiState(
                session = "sess-1",
                loading = false,
                title = "Nothing Here",
                episodes = emptyList(),
            ),
        )

        composeRule.onNodeWithTag("anime_details_empty").assertIsDisplayed()
        composeRule.onNodeWithText(AnimeDetailsViewModel.NO_EPISODES).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_loading").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_episodes").assertDoesNotExist()
    }

    @Test
    fun errorBranchShowsTheRealFailureAndBindsRetryToTheCallersAction() {
        var retries = 0
        setScreen(
            AnimeDetailsUiState(session = "sess-1", loading = false, error = "network down"),
            onRetry = { retries++ },
        )

        composeRule.onNodeWithText("network down").assertIsDisplayed()
        // A failure never renders as an empty list or a spinner.
        composeRule.onNodeWithTag("anime_details_episodes").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_empty").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_loading").assertDoesNotExist()

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    // -----------------------------------------------------------------------
    // 11.5E-3 — episode selection → stream resolution branches
    // -----------------------------------------------------------------------

    /** A real-shaped scan result: two episodes, nothing selected. */
    private fun episodesState() = AnimeDetailsUiState(
        session = "sess-1",
        loading = false,
        title = "One Piece",
        // Option C (DECISIONS 143): both numbers belong to the same context,
        // so the screen shows BOTH as its one-row-per-number list.
        selectedAudio = "jpn",
        episodes = listOf(
            EpisodeInfo(
                number = 1f,
                session = "e1",
                title = "Romance Dawn",
                fansub = "SubsPlease",
                audio = "jpn",
                playUrl = "https://example.invalid/play/e1",
            ),
            EpisodeInfo(
                number = 2f,
                session = "e2",
                title = "They Mistook Him for a Pirate",
                fansub = "Erai-raws",
                audio = "jpn",
                playUrl = "https://example.invalid/play/e2",
            ),
        ),
    )

    @Test
    fun anIdleSelectionRendersNoResolutionPanelAtAll() {
        setScreen(episodesState())

        composeRule.onNodeWithTag("anime_details_episodes").assertIsDisplayed()
        // Idle = NOTHING (no empty card, no placeholder, no dead affordance).
        composeRule.onNodeWithTag("anime_details_resolution_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_resolution_resolving").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_resolution_success").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_resolution_error").assertDoesNotExist()
        composeRule.onAllNodesWithTag("anime_details_episode")[0].assertIsNotSelected()
        composeRule.onAllNodesWithTag("anime_details_episode")[1].assertIsNotSelected()
    }

    @Test
    fun anEpisodeRowIsSelectableAndReportsTheExactEpisodeToTheCaller() {
        val expected = episodesState().episodes
        var selected: EpisodeInfo? = null
        var state by androidx.compose.runtime.mutableStateOf(episodesState())

        composeRule.setContent {
            PaheBatcherTheme {
                AnimeDetailsScreen(
                    uiState = state,
                    onBack = {},
                    onRetry = {},
                    onSelectEpisode = { episode ->
                        selected = episode
                        // What the production ViewModel does: mark it selected and resolving.
                        state = state.copy(selectedEpisode = episode, resolving = true)
                    },
                    onRetryResolution = {},
                    onDownload = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("anime_details_episode")[0].assertIsNotSelected()
        composeRule.onNodeWithTag("anime_details_resolution_panel").assertDoesNotExist()

        composeRule.onAllNodesWithTag("anime_details_episode")[0].performClick()

        composeRule.runOnIdle {
            assertEquals("the caller receives the exact scanned episode", expected[0], selected)
        }
        composeRule.onAllNodesWithTag("anime_details_episode")[0].assertIsSelected()
        composeRule.onAllNodesWithTag("anime_details_episode")[1].assertIsNotSelected()
        composeRule.onNodeWithTag("anime_details_resolution_resolving").assertIsDisplayed()
    }

    @Test
    fun theResolvingBranchRendersWhileTheStateIsResolving() {
        val state = episodesState()
        setScreen(
            state.copy(selectedEpisode = state.episodes[0], resolving = true),
        )

        composeRule.onNodeWithTag("anime_details_resolution_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_resolution_resolving").assertIsDisplayed()
        composeRule.onNodeWithText("Resolving stream for Episode 1…").assertIsDisplayed()
        // Exactly one branch: no success, no failure — and the list stays usable.
        composeRule.onNodeWithTag("anime_details_resolution_success").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_resolution_error").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_episodes").assertIsDisplayed()
        composeRule.onAllNodesWithTag("anime_details_episode")[0].assertIsSelected()
    }

    @Test
    fun theResolvedBranchRendersExactlyTheStreamTheResolverReturned() {
        val state = episodesState()
        val resolved = StreamInfo(
            url = "https://cdn.example.invalid/uwu.m3u8?token=abc123",
            userAgent = "Mozilla/5.0 (Linux; Android 16)",
            referer = "https://example.invalid/play/e1",
            title = "Romance Dawn - AnimePahe",
            audio = "jpn",
            fansub = "SubsPlease",
        )
        setScreen(
            state.copy(selectedEpisode = state.episodes[0], resolvedStream = resolved),
        )

        composeRule.onNodeWithTag("anime_details_resolution_success").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_stream_url").assertIsDisplayed()
        // The resolver's own output, verbatim.
        composeRule.onNodeWithText(resolved.url).assertIsDisplayed()
        composeRule.onNodeWithText("Stream resolved").assertIsDisplayed()
        composeRule.onNodeWithText("Romance Dawn - AnimePahe").assertIsDisplayed()
        // The panel carries the resolved audio/fansub too — once for the selected
        // row and once for the resolved result (exactly two, never a missing one).
        composeRule.onAllNodesWithText("jpn · SubsPlease").assertCountEquals(2)
        // The play page URL is the input and never the result.
        composeRule.onNodeWithText("https://example.invalid/play/e1").assertDoesNotExist()
        // 11.5E-4: the resolved branch carries the ONE downstream control that is
        // really wired — Download, bound to the caller's hand-off. Playback is
        // still unwired, so no Play affordance is ever offered (AGENTS §5).
        composeRule.onNodeWithTag("anime_details_download").assertIsDisplayed()
        composeRule.onNodeWithText("Download").assertIsDisplayed()
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
        composeRule.onNodeWithText("In the download queue").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_resolution_resolving").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_resolution_error").assertDoesNotExist()
    }

    // ── 11.5E-4: the resolved → queue hand-off ──────────────────────────────

    /**
     * A resolved selection whose hand-off is in the given (real) state.
     *
     * Slice B: the terminal verdict is now the repository's own
     * [EnqueueOutcome] rather than a `Boolean`, so every honest message this
     * panel can render is reachable from a fixture.
     */
    private fun resolvedState(
        enqueuing: Boolean = false,
        enqueueOutcome: EnqueueOutcome? = null,
        enqueueError: String? = null,
    ) = episodesState().copy(
        selectedEpisode = episodesState().episodes[0],
        resolvedStream = StreamInfo(
            url = "https://cdn.example.invalid/uwu.m3u8?token=abc123",
            userAgent = "Mozilla/5.0 (Linux; Android 16)",
            referer = "https://kwik.si/e/abc123",
            title = "Romance Dawn - AnimePahe",
            audio = "jpn",
            fansub = "SubsPlease",
        ),
        enqueuing = enqueuing,
        enqueueOutcome = enqueueOutcome,
        enqueueError = enqueueError,
    )

    @Test
    fun theDownloadControlIsBoundToTheCallersHandOffAction() {
        var downloads = 0
        setScreen(resolvedState(), onDownload = { downloads++ })

        composeRule.onNodeWithTag("anime_details_download")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("the control is bound to the caller's action", 1, downloads)
        }
    }

    // ── Phase 17: the resolved → STREAM hand-off ────────────────────────────

    @Test
    fun theStreamControlIsRenderedNextToDownloadAndHandsOffToTheCaller() {
        var streams = 0
        var downloads = 0
        setScreen(resolvedState(), onStream = { streams++ }, onDownload = { downloads++ })

        composeRule.onNodeWithTag("anime_details_stream")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("the stream action invokes exactly the caller's callback", 1, streams)
            assertEquals("the stream action must not double-fire the download", 0, downloads)
        }
        // Both affordances coexist — streaming is not a replacement for queueing.
        composeRule.onNodeWithTag("anime_details_download").assertIsDisplayed()
    }

    @Test
    fun theStreamControlOnlyExistsOnceAStreamIsActuallyResolved() {
        // Idle selection (no resolver result yet): offering Stream would be a
        // dead control (AGENTS §5), so it must not be rendered at all.
        setScreen(episodesState().copy(selectedEpisode = episodesState().episodes[0]))

        composeRule.onNodeWithTag("anime_details_stream").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_download").assertDoesNotExist()
    }

    @Test
    fun theStreamControlIsStillOfferedWhenTheEpisodeIsAlreadyQueued() {
        // Streaming is independent of the queue: an already-queued episode may
        // still be streamed, so queueing must not hide the stream action.
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.Accepted), onStream = {})

        composeRule.onNodeWithTag("anime_details_stream").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_download").assertDoesNotExist()
    }

    @Test
    fun theStreamControlSurvivesAFailedDownloadHandOff() {
        setScreen(resolvedState(enqueueError = "Network unavailable"), onStream = {})

        composeRule.onNodeWithTag("anime_details_stream").assertIsDisplayed()
    }

    @Test
    fun queueingIsShownAsProgressAndNeverClaimsTheEpisodeIsQueued() {
        setScreen(resolvedState(enqueuing = true))

        // Progress + a disabled control (never re-entrant), and NOT the queued
        // confirmation: nothing may claim queued before the database accepted it.
        composeRule.onNodeWithText("Queueing…").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_download").assertIsNotEnabled()
        composeRule.onNodeWithText("In the download queue").assertDoesNotExist()
    }

    @Test
    fun aQueuedEpisodeReportsThatInsteadOfOfferingTheControlAgain() {
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.Accepted))

        composeRule.onNodeWithText("In the download queue").assertIsDisplayed()
        // Terminal for this action: the queue owns the episode from here.
        composeRule.onNodeWithTag("anime_details_download").assertDoesNotExist()
        composeRule.onNodeWithText("Download").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_download_error").assertDoesNotExist()
    }

    // ── Slice B (DECISIONS 146): one honest message per verdict ─────────────
    //
    // One verdict per test rather than a loop: `setContent` may be called once
    // per test, and a verdict deserves its own named assertion anyway — a loop
    // that fails halfway reports one opaque failure instead of naming which
    // message lied.

    @Test
    fun anAlreadyDownloadingEpisodeSaysSoInsteadOfClaimingAFreshQueueEntry() {
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.AlreadyActive))

        composeRule.onNodeWithText("Already in the download queue").assertIsDisplayed()
        composeRule.onNodeWithText("In the download queue").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_download").assertDoesNotExist()
        composeRule.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test
    fun anAlreadyDownloadedEpisodeSaysSoInsteadOfClaimingAFreshQueueEntry() {
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.AlreadyDownloaded))

        // The suppressed-verdict wording itself. This is still an EXACT match
        // (Compose `onNodeWithText` is exact by default) — it was re-pointed at
        // the full sentence the notice actually renders, never relaxed to a
        // substring, so a wording regression still fails here.
        composeRule.onNodeWithText(
            "Already downloaded — delete the existing file to download it again",
        ).assertIsDisplayed()
        // The whole point of the slice: a suppressed request must not be able
        // to render the message that used to appear for every request.
        composeRule.onNodeWithText("In the download queue").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_download").assertDoesNotExist()
    }

    @Test
    fun anEpisodeWhoseExportVanishedSaysItIsBeingDownloadedAgain() {
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.OutputMissing))

        composeRule
            .onNodeWithText("Previous download is missing — starting it again")
            .assertIsDisplayed()
        composeRule.onNodeWithText("In the download queue").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_download").assertDoesNotExist()
    }

    @Test
    fun aPausedEpisodeIsPointedAtTheControlThatActuallyResumesIt() {
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.AlreadyPaused))

        composeRule
            .onNodeWithText("Paused — resume it from Downloads")
            .assertIsDisplayed()
        // No dead end: the message names the screen that really holds Resume
        // (SPEC §20) rather than offering a Download button that would do
        // nothing, and never suggests per-episode deletion, which this slice
        // does not build.
        composeRule.onNodeWithText("In the download queue").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_download").assertDoesNotExist()
    }

    @Test
    fun aStreamIsStillOfferedWhenTheEpisodeIsAlreadyDownloading() {
        // Streaming is independent of the queue verdict: withholding the
        // Download control must not take the Stream control with it.
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.AlreadyActive), onStream = {})
        composeRule.onNodeWithTag("anime_details_stream").assertIsDisplayed()
    }

    @Test
    fun aStreamIsStillOfferedWhenTheEpisodeIsAlreadyDownloaded() {
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.AlreadyDownloaded), onStream = {})
        composeRule.onNodeWithTag("anime_details_stream").assertIsDisplayed()
    }

    @Test
    fun aStreamIsStillOfferedWhenTheEpisodeIsPaused() {
        setScreen(resolvedState(enqueueOutcome = EnqueueOutcome.AlreadyPaused), onStream = {})
        composeRule.onNodeWithTag("anime_details_stream").assertIsDisplayed()
    }

    @Test
    fun aFailedHandOffShowsTheRealMessageAndOffersTheSameRetry() {
        var downloads = 0
        setScreen(
            resolvedState(enqueueError = "database is locked"),
            onDownload = { downloads++ },
        )

        composeRule.onNodeWithTag("anime_details_download_error")
            .assertIsDisplayed()
        composeRule.onNodeWithText("database is locked").assertIsDisplayed()
        composeRule.onNodeWithText("Retry download").assertIsDisplayed()

        composeRule.onNodeWithTag("anime_details_download").performClick()
        composeRule.runOnIdle {
            assertEquals("a failure retries the SAME hand-off", 1, downloads)
        }
    }

    @Test
    fun noDownloadControlExistsBeforeTheStreamIsResolved() {
        val state = episodesState()
        // Resolving branch: the panel exists, but queueing is impossible.
        setScreen(state.copy(selectedEpisode = state.episodes[0], resolving = true))

        composeRule.onNodeWithTag("anime_details_resolution_resolving").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_download").assertDoesNotExist()
        composeRule.onNodeWithText("Download").assertDoesNotExist()
        composeRule.onNodeWithText("In the download queue").assertDoesNotExist()
    }

    @Test
    fun theFailedBranchShowsTheRealResolverMessageAndBindsRetryToTheCallersAction() {
        var retries = 0
        val state = episodesState()
        val failure = "Could not resolve Kwik URL: https://kwik.si/e/abc"
        setScreen(
            state.copy(selectedEpisode = state.episodes[0], resolutionError = failure),
            onRetryResolution = { retries++ },
        )

        composeRule.onNodeWithTag("anime_details_resolution_error").assertIsDisplayed()
        composeRule.onNodeWithText(failure).assertIsDisplayed()
        // A failure never masquerades as a resolved stream or a pending spinner.
        composeRule.onNodeWithTag("anime_details_resolution_success").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_resolution_resolving").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_stream_url").assertDoesNotExist()

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    // ── Post-audio slice: probe status, representative disclosure, retry ────

    /**
     * The loaded screen carrying ONE probe state.
     *
     * `audioOptions` is the discovered set (the probe's union here), so the
     * selector renders alongside the status line exactly as production does.
     */
    private fun probedState(
        probe: AudioProbeState,
        probing: Boolean = false,
        batchRunning: Boolean = false,
    ) = episodesState().copy(
        audioProbeState = probe,
        audioProbing = probing,
        audioOptions = listOf("jpn"),
        batchMode = batchRunning,
        batchRunning = batchRunning,
    )

    @Test
    fun aCompletedProbeStatesTheSetWasSampledFromRepresentativeEpisodes() {
        setScreen(probedState(AudioProbeState.OK_VALUES))

        composeRule.onNodeWithTag("anime_details_probe_status").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Audio options sampled from representative episodes — other episodes may offer more languages.",
        ).assertIsDisplayed()
        // Nothing to re-check: the control that would lie about it is absent.
        composeRule.onNodeWithTag("anime_details_probe_retry").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_probe_running").assertDoesNotExist()
    }

    @Test
    fun anEmptyProbeResultIsReportedAsAnEmptyResultNotAsAFailure() {
        setScreen(probedState(AudioProbeState.OK_EMPTY))

        composeRule.onNodeWithText(
            "Sampled pages listed no audio options — showing languages from the episode list.",
        ).assertIsDisplayed()
        // A COMPLETED, empty answer is not a degraded one — the failure wording
        // must not appear for it.
        composeRule.onNodeWithText(
            "Couldn't check audio on the sampled pages — showing languages from the episode list.",
        ).assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_probe_retry").assertDoesNotExist()
    }

    @Test
    fun aFailedProbeAdmitsTheShownLanguagesAreTheListingFallbackAndOffersRetry() {
        var retries = 0
        setScreen(probedState(AudioProbeState.FAILED), onRetryProbe = { retries++ })

        composeRule.onNodeWithText(
            "Couldn't check audio on the sampled pages — showing languages from the episode list.",
        ).assertIsDisplayed()
        // Never dressed up as a confirmed probe result.
        composeRule.onNodeWithText(
            "Audio options sampled from representative episodes — other episodes may offer more languages.",
        ).assertDoesNotExist()

        composeRule.onNodeWithTag("anime_details_probe_retry")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                "the affordance is bound to the caller's re-probe exactly once",
                1,
                retries,
            )
        }
    }

    @Test
    fun aPartialProbeIsReportedAsIncompleteAndIsRetryable() {
        setScreen(probedState(AudioProbeState.PARTIAL))

        composeRule.onNodeWithText(
            "Only some sampled pages loaded — the audio list may be incomplete.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_probe_retry").assertIsDisplayed()
        // A partial result is neither a success nor a total failure.
        composeRule.onNodeWithText(
            "Audio not checked for this title — showing languages from the episode list.",
        ).assertDoesNotExist()
    }

    @Test
    fun aTitleWhoseProbeNeverRanSaysSoInsteadOfOfferingARetryThatCannotWork() {
        setScreen(probedState(AudioProbeState.NOT_PROBED))

        composeRule.onNodeWithText(
            "Audio not checked for this title — showing languages from the episode list.",
        ).assertIsDisplayed()
        // No targets → no probe is possible, so the affordance is not rendered.
        composeRule.onNodeWithTag("anime_details_probe_retry").assertDoesNotExist()
    }

    @Test
    fun aRunningProbeReplacesTheStateLineWithItsOwnDisclosure() {
        setScreen(probedState(AudioProbeState.FAILED, probing = true))

        composeRule.onNodeWithTag("anime_details_probe_running").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Checking audio on representative episodes…",
        ).assertIsDisplayed()
        // The in-flight pass owns the tap — no second control to stack passes.
        composeRule.onNodeWithTag("anime_details_probe_retry").assertDoesNotExist()
        // And the earlier degraded wording is not shown while it is running.
        composeRule.onNodeWithText(
            "Couldn't check audio on the sampled pages — showing languages from the episode list.",
        ).assertDoesNotExist()
    }

    @Test
    fun theRetryIsInertWhileABatchIsRunning() {
        var retries = 0
        setScreen(
            probedState(AudioProbeState.FAILED, batchRunning = true),
            onRetryProbe = { retries++ },
        )

        // Visible but disabled, exactly like the selector's own running guard.
        composeRule.onNodeWithTag("anime_details_probe_retry")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(
                "a frozen execution mapping is not rewritten under a running batch",
                0,
                retries,
            )
        }
    }

    @Test
    fun scanProgressLineRendersTheObservedCountsWhileTheScanRuns() {
        setScreen(
            AnimeDetailsUiState(
                session = "sess-1",
                loading = true,
                scanProgress = ScanProgress(
                    sessionIndex = 1,
                    sessionCount = 2,
                    page = 3,
                    pagesInSession = 7,
                ),
            ),
        )

        composeRule.onNodeWithTag("anime_details_scan_progress").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Scanning episodes · session 1 of 2 · page 3 of 7",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_loading").assertIsDisplayed()
    }

    @Test
    fun noScanProgressLineExistsBeforeTheRepositoryHasReportedOne() {
        setScreen(AnimeDetailsUiState(session = "sess-1", loading = true))

        // Nothing observed yet → nothing claimed: the spinner stands alone.
        composeRule.onNodeWithTag("anime_details_scan_progress").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_loading").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // Device acceptance — direct numbered APP-page navigation.
    //
    // The reported defect: a 1,180-episode title showed only a text indicator
    // ("Page 1 of 12") with Prev/Next, with no way to CHOOSE a page. These
    // tests pin the corrected contract: visible numbered APP-page buttons, a
    // validated page-number input, and the rule that malformed or out-of-range
    // input never navigates.
    // -----------------------------------------------------------------------

    /** A 1,180-episode title over 30-row API windows: 12 APP pages of 100. */
    private fun onePiecePagerState(
        page: Int = 1,
        pageRows: List<EpisodeInfo> = emptyList(),
    ) = AnimeDetailsUiState(
        session = "sess-paged",
        loading = false,
        title = "One Piece",
        apiTotal = 1180,
        perPage = 30,
        apiLastPage = 40,
        currentPage = page,
        pageRows = pageRows,
        episodes = pageRows,
    )

    /**
     * Phase 12 UI review — a title whose APP page count comes from its total (a
     * >100-episode title is 100 per app page), so a test can pick the exact
     * number of pages the pager must render.
     */
    private fun pagedState(
        apiTotal: Int,
        page: Int = 1,
        pageRows: List<EpisodeInfo> = emptyList(),
    ) = AnimeDetailsUiState(
        session = "sess-paged",
        loading = false,
        title = "Paged",
        apiTotal = apiTotal,
        perPage = 30,
        apiLastPage = (apiTotal + 29) / 30,
        currentPage = page,
        pageRows = pageRows,
        episodes = pageRows,
    )

    /**
     * Phase 12 UI review — compose the screen inside a fixed-width [Box] so the
     * pager re-measures its number strip at that width. Decision 111.3: an
     * in-window surface receives exactly the constraints a resized window would
     * give it, with no window-size-class dependency.
     */
    private fun setScreenConstrainedToWidth(state: AnimeDetailsUiState, widthDp: Int) {
        composeRule.setContent {
            PaheBatcherTheme {
                Box(Modifier.width(widthDp.dp)) {
                    AnimeDetailsScreen(
                        uiState = state,
                        onBack = {},
                        onRetry = {},
                        onSelectEpisode = {},
                        onRetryResolution = {},
                        onDownload = {},
                        onStream = {},
                        onRetryProbe = {},
                        onGoToPage = {},
                    )
                }
            }
        }
    }

    @Test
    fun aTwelvePageTitleShowsNumberedAPPPageButtonsNotJustATextIndicator() {
        setScreen(onePiecePagerState(page = 1))

        // The pager itself, the current page and the numbered APP pages.
        composeRule.onNodeWithTag("anime_details_page_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_current").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_number_2").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_number_3").assertIsDisplayed()
        // The LAST page is always reachable from the window, without walking
        // through every page in between.
        composeRule.onNodeWithTag("anime_details_page_number_12").assertIsDisplayed()
        // Prev/Next survive the rewrite.
        composeRule.onNodeWithTag("anime_details_page_prev").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_next").assertIsDisplayed()
        // The page numbers are APP pages: 1,180 episodes over 100 per page.
        composeRule.onNodeWithTag("anime_details_page_indicator")
            .assertTextEquals("Page 1 of 12 · 1180 episodes")
    }

    @Test
    fun clickingANumberedPageHandsThatAPPPageToTheViewModel() {
        val visited = mutableListOf<Int>()
        setScreen(onePiecePagerState(page = 1), onGoToPage = { visited += it })

        composeRule.onNodeWithTag("anime_details_page_number_12").performClick()
        assertEquals(listOf(12), visited)

        composeRule.onNodeWithTag("anime_details_page_number_2").performClick()
        assertEquals(listOf(12, 2), visited)
    }

    @Test
    fun thePageNumberInputNavigatesToAWellFormedInRangePage() {
        val visited = mutableListOf<Int>()
        setScreen(onePiecePagerState(page = 1), onGoToPage = { visited += it })

        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("7")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()
        assertEquals("a valid page number navigates to exactly that APP page", listOf(7), visited)
    }

    @Test
    fun zeroBlankMalformedAndOutOfRangeInputNeverNavigate() {
        val visited = mutableListOf<Int>()
        setScreen(onePiecePagerState(page = 1), onGoToPage = { visited += it })

        // Zero.
        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("0")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()
        // Out of range (a 13th page of a 12-page title does not exist).
        composeRule.onNodeWithTag("anime_details_page_input").performTextClearance()
        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("13")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()
        // Malformed — not an integer at all.
        composeRule.onNodeWithTag("anime_details_page_input").performTextClearance()
        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("1.5")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()

        assertTrue(
            "no invalid entry may move the user to a page",
            visited.isEmpty(),
        )
        // The last rejection (malformed) is reported under the field, so the
        // failure is visible rather than a silent no-op.
        composeRule.onNodeWithTag("anime_details_page_input_error").assertIsDisplayed()
        // And the current page is unchanged.
        composeRule.onNodeWithTag("anime_details_page_indicator")
            .assertTextEquals("Page 1 of 12 · 1180 episodes")
    }

    @Test
    fun anOutOfRangePageInputNamesTheValidBoundsInItsFeedback() {
        setScreen(onePiecePagerState(page = 1))

        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("99")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()

        composeRule.onNodeWithTag("anime_details_page_input_error")
            .assertTextEquals("Enter a page between 1 and 12")
    }

    @Test
    fun aPageBelowTheFirstPageIsRejectedAndTheFirstPageButtonIsTheCurrentOne() {
        val visited = mutableListOf<Int>()
        setScreen(onePiecePagerState(page = 1), onGoToPage = { visited += it })

        // On page 1 the Prev button is not offered (nothing precedes it) and
        // the current-page button stands in for page 1.
        composeRule.onNodeWithTag("anime_details_page_prev").assertIsNotEnabled()
        composeRule.onNodeWithTag("anime_details_page_number_1").assertDoesNotExist()

        // Negative input is rejected exactly like any other invalid bound.
        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("-3")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()
        assertTrue(visited.isEmpty())
    }

    @Test
    fun aLaterPageStillShowsItsNumberedNeighboursAndTheFirstPage() {
        val visited = mutableListOf<Int>()
        setScreen(onePiecePagerState(page = 11), onGoToPage = { visited += it })

        composeRule.onNodeWithTag("anime_details_page_indicator")
            .assertTextEquals("Page 11 of 12 · 1180 episodes")
        // Its numbered neighbours are rendered too, so the window really is a
        // window around the current page rather than only its edges.
        composeRule.onNodeWithTag("anime_details_page_number_10").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_number_12").assertIsDisplayed()
        // Page 1 stays reachable from anywhere: no need to walk pages 2..10.
        composeRule.onNodeWithTag("anime_details_page_number_1").performClick()
        assertEquals(listOf(1), visited)
    }

    /**
     * Phase 12 UI review — the Go-to-page field is pure duplication whenever
     * every page is already a numbered button. A four-page title renders 1, 2, 3
     * and 4 with no ellipsis, so the field (and its Go button) must not be
     * composed at all.
     */
    @Test
    fun fourPageTitleHidesGoToPageBecauseEveryPageNumberIsVisible() {
        setScreen(pagedState(apiTotal = 400))  // 4 APP pages of 100

        composeRule.onNodeWithTag("anime_details_page_input").assertDoesNotExist()
        composeRule.onNodeWithTag("anime_details_page_go").assertDoesNotExist()
        // Every page really is a button.
        composeRule.onNodeWithTag("anime_details_page_number_2").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_number_3").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_number_4").assertIsDisplayed()
    }

    /**
     * Phase 12 UI review — when the number window collapses behind an ellipsis (a
     * page is omitted from the strip) the Go-to-page field appears, because that
     * page is otherwise unreachable by button.
     */
    @Test
    fun manyPagesWithAnEllipsisShowGoToPage() {
        setScreen(onePiecePagerState(page = 1))  // 12 APP pages

        composeRule.onNodeWithTag("anime_details_page_input").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_go").assertIsDisplayed()
    }

    /**
     * Phase 12 UI review — the strip re-measures as the available width changes
     * (the Box width is driven from state, so ONE setContent observes the change)
     * and that must not break the field: after a width change the Go-to-page
     * field is still present for an omitted-page title and still rejects an
     * out-of-range page rather than navigating. The budget growth itself (a wide
     * window fitting every page) is covered by the JVM test of
     * [pageNumberButtonBudget], because it needs a window wider than this device
     * provides.
     */
    @Test
    fun aWidthChangeKeepsPageValidationWorking() {
        var widthDp by mutableStateOf<Int?>(null)  // null = the full window
        val visited = mutableListOf<Int>()
        composeRule.setContent {
            PaheBatcherTheme {
                val width = widthDp
                Box(modifier = if (width != null) Modifier.width(width.dp) else Modifier) {
                    AnimeDetailsScreen(
                        uiState = onePiecePagerState(page = 1),  // 12 APP pages
                        onBack = {},
                        onRetry = {},
                        onSelectEpisode = {},
                        onRetryResolution = {},
                        onDownload = {},
                        onStream = {},
                        onRetryProbe = {},
                        onGoToPage = { visited += it },
                    )
                }
            }
        }
        // At full width the twelve pages collapse behind an ellipsis, so the
        // field is present and a well-formed page navigates.
        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("5")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()
        assertEquals(listOf(5), visited)

        // Narrowing the window re-measures the strip; the field stays present and
        // an out-of-range page is still rejected rather than navigated.
        widthDp = 280
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("anime_details_page_input").performTextClearance()
        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("99")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()
        composeRule.onNodeWithTag("anime_details_page_input_error").assertIsDisplayed()
        assertEquals(listOf(5), visited)  // 99 was never navigated
    }

    /**
     * Phase 12 UI review — the responsive change must not break validation: at a
     * constrained width where the field IS present, an out-of-range page is still
     * rejected rather than silently navigated.
     */
    @Test
    fun goToPageValidationStillAppliesAtAConstrainedWidth() {
        val visited = mutableListOf<Int>()
        composeRule.setContent {
            PaheBatcherTheme {
                Box(Modifier.width(280.dp)) {
                    AnimeDetailsScreen(
                        uiState = onePiecePagerState(page = 1),  // 12 APP pages
                        onBack = {},
                        onRetry = {},
                        onSelectEpisode = {},
                        onRetryResolution = {},
                        onDownload = {},
                        onStream = {},
                        onRetryProbe = {},
                        onGoToPage = { visited += it },
                    )
                }
            }
        }
        // At 280dp the twelve pages collapse, so the field is present.
        composeRule.onNodeWithTag("anime_details_page_input").performTextInput("99")
        composeRule.onNodeWithTag("anime_details_page_go").performClick()
        composeRule.onNodeWithTag("anime_details_page_input_error").assertIsDisplayed()
        assertEquals(emptyList<Int>(), visited)  // never navigated
    }

    /**
     * Phase 12 UI review — at the largest font scale the pager stays accessible:
     * the numbers are still buttons (reachable, scrolling rather than clipping)
     * and Prev/Next survive.
     */
    @Test
    fun thePagerStaysAccessibleAtDoubleFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                PaheBatcherTheme {
                    AnimeDetailsScreen(
                        uiState = onePiecePagerState(page = 1),
                        onBack = {},
                        onRetry = {},
                        onSelectEpisode = {},
                        onRetryResolution = {},
                        onDownload = {},
                        onStream = {},
                        onRetryProbe = {},
                        onGoToPage = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("anime_details_page_prev").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_next").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_number_2").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_page_number_12").assertIsDisplayed()
    }
}
