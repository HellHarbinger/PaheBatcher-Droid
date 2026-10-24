package com.pahebatcher.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.ui.details.AnimeDetailsScreen
import com.pahebatcher.app.ui.details.AnimeDetailsUiState
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Option C audio UX on [AnimeDetailsScreen] (SPEC §22, DECISIONS 143) — the
 * RENDER contract of the screen-level selector, the optional context notice,
 * the one-row-per-number list and the per-episode override control, composed
 * IN ISOLATION from hand-built immutable states (no repository/network —
 * AGENTS §5; the state transitions themselves are JVM-tested in
 * `AnimeDetailsAudioSelectionTest`).
 *
 * Covered:
 *
 * 26. the selector is visible, offers exactly the listing's values (open set),
 *     marks the current context, hands the raw value to the caller, and no
 *     value is ever labelled "original";
 * 27. the top-level list is ONE row per episode NUMBER for the current
 *     context — other-audio variants never appear as extra rows;
 * 28. the override control exists only for numbers with several variants;
 * 29. tapping the override control is consumed by the control (menu opens,
 *     no row-selection toggle fires; picking an option reports the override
 *     and still never toggles the row);
 * 30. the audio controls are inert while a batch is running;
 * 31. the EFFECTIVE audio is visibly indicated (context or `… · override`),
 *     with the rendered row carrying the effective variant's own metadata;
 * plus the screen-level notice rendering (§22: the deselection report is
 * screen-level UI state, shown while present and gone when cleared).
 */
@RunWith(AndroidJUnit4::class)
class AnimeDetailsAudioScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 3 variants across 2 numbers: ep1 has SUB+DUB, ep2 SUB only. */
    private val episodes = listOf(
        EpisodeInfo(
            number = 1f,
            session = "ep1-jpn",
            title = "Beginnings",
            fansub = "SubsPlease",
            audio = "jpn",
            playUrl = "https://example.invalid/play/1",
        ),
        EpisodeInfo(
            number = 1f,
            session = "ep1-eng",
            title = "Beginnings",
            fansub = "DubsPlease",
            audio = "eng",
            playUrl = "https://example.invalid/play/1b",
        ),
        EpisodeInfo(
            number = 2f,
            session = "ep2-jpn",
            title = "Next Step",
            fansub = "SubsPlease",
            audio = "jpn",
            playUrl = "https://example.invalid/play/2",
        ),
    )

    private fun audioState(
        selectedAudio: String = "jpn",
        explicitAudioOverrides: Map<Float, String> = emptyMap(),
        selectedNumbers: Set<Float> = emptySet(),
        batchMode: Boolean = false,
        batchRunning: Boolean = false,
        /**
         * The screen's option set — defaults to the two values this listing
         * exposes; the probe-discovered case supplies its own (DECISIONS 144.6).
         */
        audioOptions: List<String> = listOf("jpn", "eng"),
    ) = AnimeDetailsUiState(
        session = "sess-audio",
        loading = false,
        title = "Dual Audio",
        episodes = episodes,
        selectedAudio = selectedAudio,
        explicitAudioOverrides = explicitAudioOverrides,
        selectedNumbers = selectedNumbers,
        batchMode = batchMode,
        batchRunning = batchRunning,
        audioOptions = audioOptions,
    )

    // Callback recorders for the two audio events (+ the batch row toggle).
    private var lastSelectedAudio: String? = null
    private var lastSetEpisodeAudio: Pair<Float, String>? = null
    private var rowToggles = 0

    private fun setScreen(
        state: AnimeDetailsUiState,
        onSelectAudio: (String) -> Unit = { lastSelectedAudio = it },
        onSetEpisodeAudio: (Float, String) -> Unit = { number, audio ->
            lastSetEpisodeAudio = number to audio
        },
        onToggleBatchEpisode: (EpisodeInfo) -> Unit = { rowToggles++ },
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
                    onToggleBatchEpisode = onToggleBatchEpisode,
                    onSelectAudio = onSelectAudio,
                    onSetEpisodeAudio = onSetEpisodeAudio,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 26 — the selector
    // -----------------------------------------------------------------------

    @Test
    fun theAudioSelectorIsVisibleOffersExactlyTheListingValuesAndHandsTheRawValueOver() {
        setScreen(audioState())

        composeRule.onNodeWithTag("anime_details_audio_selector").assertIsDisplayed()
        // Open set, but only what THIS listing exposes: both real values, no
        // invented third, no hard-coded pair beyond the listing itself.
        composeRule.onNodeWithTag("anime_details_audio_chip_jpn").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_audio_chip_eng").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_audio_chip_kor").assertDoesNotExist()
        // The current context is visibly marked.
        composeRule.onNodeWithTag("anime_details_audio_chip_jpn").assertIsSelected()
        composeRule.onNodeWithTag("anime_details_audio_chip_eng").assertIsNotSelected()
        // Accessible labels on every option.
        composeRule.onNodeWithContentDescription("Audio jpn").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Audio eng").assertIsDisplayed()
        // SPEC §22: no value is ever labelled "original".
        composeRule.onNodeWithText("Original").assertDoesNotExist()

        composeRule.onNodeWithTag("anime_details_audio_chip_eng").performClick()
        composeRule.runOnIdle {
            assertEquals(
                "the control hands ONLY the raw value to the ViewModel",
                "eng",
                lastSelectedAudio,
            )
        }
    }

    // -----------------------------------------------------------------------
    // 27 — one row per episode number for the current context
    // -----------------------------------------------------------------------

    @Test
    fun theEpisodeListShowsOneRowPerNumberAndFollowsTheContext() {
        var state by mutableStateOf(audioState())
        composeRule.setContent {
            PaheBatcherTheme {
                AnimeDetailsScreen(
                    uiState = state,
                    onBack = {},
                    onRetry = {},
                    onSelectEpisode = {},
                    onRetryResolution = {},
                    onDownload = {},
                    // Simulates what the production ViewModel does on a
                    // selector tap: adopt the new context (single write).
                    onSelectAudio = { state = state.copy(selectedAudio = it) },
                )
            }
        }

        // Context jpn: 3 variants collapse into 2 NUMBER rows.
        composeRule.onAllNodesWithTag("anime_details_episode").assertCountEquals(2)
        composeRule.onNodeWithText("Episode 1").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 2").assertIsDisplayed()

        // Context eng: BOTH numbers still render — number 1 with its listing
        // eng row, number 2 carrying the eng request on its own play page
        // (DECISIONS 144.6). No second top-level row appears for ep1's other
        // variant, and no number ever hides because release metadata lacks eng.
        composeRule.onNodeWithTag("anime_details_audio_chip_eng").performClick()
        composeRule.onAllNodesWithTag("anime_details_episode").assertCountEquals(2)
        composeRule.onNodeWithText("Episode 1").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 2").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // 28 — the override control exists only where there is a real choice
    // -----------------------------------------------------------------------

    @Test
    fun theOverrideControlRendersOnlyForNumbersWithSeveralAudioVariants() {
        setScreen(audioState())

        // Exactly ONE multi-variant number (ep1) → exactly one control; ep2's
        // single-variant row never gets a dead affordance (AGENTS §5).
        composeRule.onAllNodesWithTag("anime_details_change_audio").assertCountEquals(1)
        // The effective-audio line is a plain Text INSIDE the selectable row —
        // its tag lives only in the unmerged tree (the row's own tag wins the
        // merge), so it must be looked up there.
        composeRule
            .onNodeWithTag("anime_details_episode_audio", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Change audio").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // 29 — the control consumes its own taps
    // -----------------------------------------------------------------------

    @Test
    fun tappingTheOverrideControlNeverTogglesTheRowSelection() {
        setScreen(audioState(batchMode = true), onToggleBatchEpisode = { rowToggles++ })

        composeRule.onNodeWithTag("anime_details_change_audio").performClick()

        // The menu opened — and the ROW's selection gesture did not fire.
        composeRule.onNodeWithTag("anime_details_audio_option_eng").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("the control consumed its own tap", 0, rowToggles)
        }
        composeRule.onAllNodesWithTag("anime_details_episode")[0].assertIsNotSelected()

        // Picking an option reports the OVERRIDE — and still no row toggle.
        composeRule.onNodeWithTag("anime_details_audio_option_eng").performClick()
        composeRule.runOnIdle {
            assertEquals("the override reaches the caller", 1f to "eng", lastSetEpisodeAudio)
            assertEquals("choosing an audio never selects the number", 0, rowToggles)
        }
        composeRule.onAllNodesWithTag("anime_details_episode")[0].assertIsNotSelected()
        composeRule.onNodeWithTag("anime_details_audio_option_eng").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------
    // 30 — inert while a batch executes
    // -----------------------------------------------------------------------

    @Test
    fun audioControlsAreInertWhileABatchExecutionRuns() {
        setScreen(audioState(batchMode = true, batchRunning = true))

        composeRule.onNodeWithTag("anime_details_audio_selector").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_audio_chip_jpn").assertIsNotEnabled()
        composeRule.onNodeWithTag("anime_details_audio_chip_eng").assertIsNotEnabled()
        composeRule.onNodeWithTag("anime_details_change_audio").assertIsNotEnabled()
    }

    // -----------------------------------------------------------------------
    // 31 — the effective audio is visible
    // -----------------------------------------------------------------------

    @Test
    fun theEffectiveAudioIsVisiblyIndicatedWithAndWithoutAnOverride() {
        setScreen(audioState())

        // No override: the row shows the context value, no override marker.
        // (Plain Text inside the selectable row → unmerged-tree lookup, as
        // above.)
        composeRule
            .onNodeWithTag("anime_details_episode_audio", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Audio: JPN").assertIsDisplayed()
        composeRule.onNodeWithText("Audio: ENG").assertDoesNotExist()
    }

    @Test
    fun anOverrideShowsTheOverriddenEffectiveAudioAndItsVariantMetadata() {
        setScreen(audioState(explicitAudioOverrides = mapOf(1f to "eng")))

        // Effective audio for number 1 is the OVERRIDE, visibly marked…
        composeRule.onNodeWithText("Audio: ENG · override").assertIsDisplayed()
        // …and the rendered row IS that effective variant (its own fansub) —
        // the number the batch/single path will resolve is on screen.
        composeRule.onNodeWithText("eng · DubsPlease").assertIsDisplayed()
        // The screen-level context itself did not move (only the selector
        // changes that).
        composeRule.onNodeWithTag("anime_details_audio_chip_jpn").assertIsSelected()
    }

    // -----------------------------------------------------------------------
    // The option set is the DISCOVERED one, and the deselection notice is gone
    // -----------------------------------------------------------------------

    @Test
    fun theSelectorOffersEveryDiscoveredOptionIncludingOneNoReleaseRowOffers() {
        setScreen(
            audioState(
                selectedAudio = "eng",
                // A value the bounded probe found on a play page but that no
                // release row carries — release-listing audio can never veto
                // an option (DECISIONS 144).
                audioOptions = listOf("jpn", "eng", "kor"),
            ),
        )

        composeRule.onNodeWithTag("anime_details_audio_chip_jpn").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_audio_chip_eng").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_audio_chip_kor").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_audio_chip_kor").assertIsNotSelected()
        composeRule.onNodeWithTag("anime_details_audio_chip_eng").assertIsSelected()
    }

    @Test
    fun noDeselectionNoticeIsRenderedBecauseNothingIsEverDeselected() {
        // DECISIONS 144.6 removed the notice (and its state field) with the
        // deselect-and-report rule it reported: a context change keeps every
        // selected number, so there is nothing to report — and no leftover
        // empty message node either.
        setScreen(audioState())

        composeRule.onNodeWithTag("anime_details_audio_notice").assertDoesNotExist()
        composeRule.onNodeWithText("was deselected").assertDoesNotExist()
        composeRule.onNodeWithText("were deselected").assertDoesNotExist()
    }
}
