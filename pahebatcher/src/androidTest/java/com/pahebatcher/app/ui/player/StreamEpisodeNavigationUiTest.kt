package com.pahebatcher.app.ui.player

import androidx.annotation.OptIn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * SPEC §15 (episode navigation / next episode / previous episode) — the centre
 * controls of the player, on a physical device.
 *
 * What this pins:
 *
 * 1. a STREAM entry's skip buttons are bound to the ViewModel's neighbour
 *    switch (not to the player's playlist — a stream has ONE media item);
 * 2. they are bounded by the sibling list: at either end the control exists but
 *    is DISABLED, which is how Media3 presents a playlist that ends, so an
 *    unreadable episode list can never look like a working control;
 * 3. DOWNLOAD mode is untouched: its skip controls come from Media3's own row
 *    and ignore the stream sibling list entirely.
 *
 * The player is a real ExoPlayer with no media items, which is exactly the
 * discriminating fixture: Media3's own "Next" is disabled on such a player, so
 * an enabled "Next" can only come from the STREAM row.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class StreamEpisodeNavigationUiTest {

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

    private fun state(
        mode: PlayerMode,
        siblingEpisodeIds: List<String> = emptyList(),
        siblingIndex: Int = -1,
    ) = PlayerUiState(
        mode = mode,
        player = player,
        loading = false,
        title = "Spy x Family · Episode 1 [SUB]",
        siblingEpisodeIds = siblingEpisodeIds,
        siblingIndex = siblingIndex,
    )

    private fun render(
        uiState: PlayerUiState,
        onNext: () -> Unit = {},
        onPrevious: () -> Unit = {},
    ) {
        composeRule.setContent {
            PlayerScreen(
                uiState = uiState,
                playerLifecycle = NoopLifecycle,
                onBack = {},
                onRetryLoad = {},
                onRetryPlayback = {},
                onNext = onNext,
                onPrevious = onPrevious,
            )
        }
    }

    @Test
    fun streamSkipControlsAreBoundToTheViewModelsNeighbourSwitch() {
        var nextCalls = 0
        var previousCalls = 0
        render(
            uiState = state(
                mode = PlayerMode.STREAM,
                siblingEpisodeIds = listOf("s_1_jpn", "s_2_jpn", "s_3_jpn"),
                siblingIndex = 1,
            ),
            onNext = { nextCalls++ },
            onPrevious = { previousCalls++ },
        )

        composeRule.onNodeWithContentDescription("Next").performClick()
        composeRule.onNodeWithContentDescription("Previous").performClick()

        assertEquals("the Next control must reach the ViewModel", 1, nextCalls)
        assertEquals("the Previous control must reach the ViewModel", 1, previousCalls)
        // Media3's own row is still there around them: play/pause and the seek
        // controls are Media3's, unchanged.
        composeRule.onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun aStreamSkipControlIsDisabledAtTheEndOfTheSiblingList() {
        render(
            uiState = state(
                mode = PlayerMode.STREAM,
                siblingEpisodeIds = listOf("s_1_jpn", "s_2_jpn"),
                siblingIndex = 1,
            ),
        )

        composeRule.onNodeWithContentDescription("Next").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Previous").assertIsEnabled()
    }

    @Test
    fun streamSkipControlsAreDisabledUntilTheSiblingListIsKnown() {
        // The list is read after the episode starts playing: until then — and
        // forever, if it could not be read — nothing is claimed to exist.
        render(uiState = state(mode = PlayerMode.STREAM, siblingEpisodeIds = emptyList()))

        composeRule.onNodeWithContentDescription("Next").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Previous").assertIsNotEnabled()
    }

    @Test
    fun downloadModeIgnoresTheStreamSiblingListAndKeepsMediasOwnSkipControls() {
        // The fixture that makes the branch observable: Media3's own Next is
        // disabled on a player with a single/absent playlist, while the STREAM
        // row would be enabled for this exact state. DOWNLOAD must show the former.
        render(
            uiState = state(
                mode = PlayerMode.DOWNLOAD,
                siblingEpisodeIds = listOf("s_1_jpn", "s_2_jpn", "s_3_jpn"),
                siblingIndex = 0,
            ),
        )

        composeRule.onNodeWithContentDescription("Next").assertIsNotEnabled()
    }
}
