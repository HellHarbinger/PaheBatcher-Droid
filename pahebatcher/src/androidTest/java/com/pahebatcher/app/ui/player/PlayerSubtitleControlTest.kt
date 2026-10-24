package com.pahebatcher.app.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.ui.navigation.PlayerMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC §15 "subtitles where available" — the subtitle control of the player,
 * on a physical device.
 *
 * What this pins:
 *
 * 1. a media that publishes NO text track gets no subtitle control at all —
 *    and one that publishes tracks does (including appearing and disappearing
 *    when the media changes underneath);
 * 2. the chooser lists **Off** plus every published track, labels included;
 * 3. picking an entry forwards its exact index, `-1` for Off, and closes the
 *    chooser while keeping the rest of the controls visible;
 * 4. the track the player renders is marked in its own row only.
 *
 * The ViewModel side of the same requirement (choosing a track really makes
 * ExoPlayer render it, the choice surviving an episode switch, and no
 * assertion when there is no text track) is verified end-to-end by
 * `SubtitleFixturePlaybackDeviceTest` against a real HLS fixture.
 */
@OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlayerSubtitleControlTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private lateinit var player: ExoPlayer

    private object NoopLifecycle : PlayerLifecycle {
        override fun onAttached(owner: Any) = Unit
        override fun onResumed(owner: Any) = Unit
        override fun onPausedBelowResumed(owner: Any) = Unit
        override fun onDetached(owner: Any, destroyedForConfigChange: Boolean) = Unit
    }

    @Before
    fun setUp() {
        instrumentation.runOnMainSync { player = ExoPlayer.Builder(context).build() }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player.release() }
    }

    @Test
    fun aMediaWithNoTextTrackShowsNoSubtitleControlAtAll() {
        render(mutableStateOf(state()))

        composeRule.onNodeWithContentDescription(Control).assertDoesNotExist()
        composeRule.onNodeWithTag("subtitle_control").assertDoesNotExist()
        composeRule.onNodeWithText("Off").assertDoesNotExist()
    }

    @Test
    fun losingTheTextTrackRemovesTheControlAndPublishingOneBringsItBack() {
        val uiState = mutableStateOf(state(options = options()))
        render(uiState)

        composeRule.onNodeWithContentDescription(Control).assertExists()

        uiState.value = state(options = emptyList())
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(Control).assertDoesNotExist()

        uiState.value = state(options = options())
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(Control).assertExists()
    }

    @Test
    fun theChooserListsOffAndEveryPublishedTrackOnlyAfterTheControlIsTapped() {
        render(mutableStateOf(state(options = options(), selectedIndex = 1)))

        // Closed by default: the control alone is shown, not the list.
        composeRule.onNodeWithContentDescription(Control).assertExists()
        composeRule.onNodeWithText("Off").assertDoesNotExist()

        composeRule.onNodeWithTag("subtitle_control").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Subtitles").assertExists()
        composeRule.onNodeWithText("Off").assertExists()
        composeRule.onNodeWithText("English").assertExists()
        composeRule.onNodeWithText("English (2)").assertExists()
        composeRule.onNodeWithText("日本語").assertExists()
    }

    @Test
    fun choosingATrackForwardsItsExactIndexAndClosesTheChooser() {
        var chosen: Int? = null
        render(mutableStateOf(state(options = options()))) { chosen = it }

        composeRule.onNodeWithTag("subtitle_control").performClick()
        composeRule.onNodeWithText("日本語").performClick()
        composeRule.waitForIdle()

        assertEquals("the third option is index 2", 2, chosen)
        composeRule.onNodeWithText("Off").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(Control).assertExists()
    }

    @Test
    fun choosingOffForwardsMinusOneAndClosesTheChooser() {
        var chosen: Int? = null
        render(mutableStateOf(state(options = options(), selectedIndex = 0))) { chosen = it }

        composeRule.onNodeWithTag("subtitle_control").performClick()
        composeRule.onNodeWithText("Off").performClick()
        composeRule.waitForIdle()

        assertEquals("Off is signalled as -1", -1, chosen)
        composeRule.onNodeWithText("Off").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(Control).assertExists()
    }

    @Test
    fun theRenderedTrackIsMarkedInItsOwnRowOnly() {
        render(mutableStateOf(state(options = options(), selectedIndex = 1)))

        composeRule.onNodeWithTag("subtitle_control").performClick()

        assertRowMarked("English (2)", marked = true)
        assertRowMarked("English", marked = false)
        assertRowMarked("Off", marked = false)
        assertRowMarked("日本語", marked = false)
        composeRule.onAllNodes(hasContentDescription(Selected)).assertCountEquals(1)
    }

    @Test
    fun nothingIsMarkedWhileSubtitlesAreOff() {
        render(mutableStateOf(state(options = options(), selectedIndex = -1)))

        composeRule.onNodeWithTag("subtitle_control").performClick()

        assertRowMarked("Off", marked = true)
        assertRowMarked("English", marked = false)
        assertRowMarked("日本語", marked = false)
        composeRule.onAllNodes(hasContentDescription(Selected)).assertCountEquals(1)
    }

    @Test
    fun tappingTheControlAgainClosesTheChooserButKeepsTheControlsVisible() {
        render(mutableStateOf(state(options = options())))

        composeRule.onNodeWithTag("subtitle_control").performClick()
        composeRule.onNodeWithText("Off").assertExists()

        // The control is its own dismiss affordance: the second tap closes the
        // list and the rest of the controls stay up.
        composeRule.onNodeWithTag("subtitle_control").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Off").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(Control).assertExists()
    }

    // -------------------------------------------------------------- helpers

    /**
     * Whether the row carrying [label] carries the selection marker too. The
     * marker may be merged into the row (the button merges its descendants) or
     * still be its own node, so both shapes are accepted.
     */
    private fun assertRowMarked(label: String, marked: Boolean) {
        val row = hasText(label) and (
            hasContentDescription(Selected) or
                hasAnyDescendant(hasContentDescription(Selected))
            )
        composeRule.onAllNodes(row).assertCountEquals(if (marked) 1 else 0)
    }

    private fun state(
        options: List<SubtitleOption> = emptyList(),
        selectedIndex: Int = -1,
    ) = PlayerUiState(
        mode = PlayerMode.STREAM,
        player = player,
        loading = false,
        title = "Spy x Family · Episode 1 [SUB]",
        subtitleOptions = options,
        selectedSubtitleIndex = selectedIndex,
    )

    private fun options() = listOf(
        SubtitleOption(groupIndex = 1, trackIndex = 0, label = "English"),
        SubtitleOption(groupIndex = 2, trackIndex = 0, label = "English (2)"),
        SubtitleOption(groupIndex = 3, trackIndex = 0, label = "日本語"),
    )

    private fun render(
        uiState: MutableState<PlayerUiState>,
        onSelectSubtitle: (Int) -> Unit = {},
    ) {
        composeRule.setContent {
            PlayerScreen(
                uiState = uiState.value,
                playerLifecycle = NoopLifecycle,
                onBack = {},
                onRetryLoad = {},
                onRetryPlayback = {},
                onNext = {},
                onPrevious = {},
                onSelectSubtitle = onSelectSubtitle,
            )
        }
    }

    private companion object {
        const val Control = "Subtitles"
        const val Selected = "Subtitle selected"
    }
}
