package com.pahebatcher.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.support.allContentDescriptions
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.ensureTestMp4
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.support.isPlayerPlaying
import com.pahebatcher.app.support.playerProbe
import com.pahebatcher.app.support.seedCompletedEpisodes
import com.pahebatcher.app.ui.navigation.PlayerRoute
import com.pahebatcher.app.ui.navigation.navigateToPlayer
import com.pahebatcher.app.ui.player.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * UI-008 (fixed 2026-10-20) — the seek-slider drag/hold contract, on device.
 *
 * Derived contract (DECISION 98's commit-on-finish slider posture + the
 * inventory's recorded fix shape): while a drag gesture is active the slider
 * displays a LOCALLY HELD value and the 500 ms position ticker is suppressed
 * for the slider; the player is seeked exactly ONCE per gesture, at
 * `onValueChangeFinished`; afterwards the ticker resumes driving the slider.
 * Before the fix the slider seeked on every drag movement and the ticker
 * fought the drag — no test exercised any of that (AQR-068: existing slider
 * tests cover only size/target/`SetProgress`).
 *
 * All four tests drive REAL interaction (touch gestures via `performTouchInput`
 * and the slider's accessibility `SetProgress` action) on the real player with
 * the real cached MP4 fixture, and assert against [playerProbe] — the VM-owned
 * ExoPlayer's live state — the VM ticker's own `uiState.positionMs`, a literal
 * seek counter attached as a [Player.Listener], and the slider's own
 * `ProgressBarRangeInfo`: observable behaviour at every stage, never an
 * internal callback invocation. Device tests on purpose: gesture injection +
 * a real ticking player have no JVM equivalent.
 *
 * Known-unverified edge (AQR-078): hardware keyboard / D-pad slider adjustment
 * is neither confirmed nor drivable here — Compose test exposes no key-event
 * injection for this node and material3 sources are not in the local Gradle
 * cache — so no keyboard commit path is claimed.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSeekSliderDragTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var db: PaheDatabase
    private lateinit var mediaPath: String

    /** The seek slider: the only progress node that also exposes SetProgress. */
    private val seekSlider = SemanticsMatcher
        .keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        .and(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = PaheDatabase.getInstance(context)
        mediaPath = ensureTestMp4()
    }

    // -----------------------------------------------------------------------
    // 1) Drag begins + hold across ticks: the held value pins the slider,
    //    the VM ticker and the player keep advancing underneath, no seek.
    // -----------------------------------------------------------------------

    @Test
    fun draggingPinsTheSliderToTheHeldValueWhileTheTickerKeepsThePlayerUntouched() {
        openRealPlayback()

        val baseline = composeRule.playerProbe()
        assertNotNull("player probe available after playback starts", baseline)
        val baselinePositionMs = baseline!!.positionMs
        val tickerBefore = vmTickerPositionMs()

        try {
            // DRAG BEGINS: press at ~45 % of the slider, then move to ~55 % —
            // the first real drag delta of the gesture (past touch slop) makes
            // the HELD value take over from the ticker-derived one (playback
            // is still at the very start, so the two differ by design).
            composeRule.onNode(seekSlider).performTouchInput {
                down(percentOffset(0.45f, 0.5f))
                moveTo(percentOffset(0.55f, 0.5f))
            }
            val heldFraction = sliderFraction()
            assertTrue(
                "drag begin: the held value (~0.55) pins the slider " +
                    "(slider=$heldFraction, player position=${composeRule.playerProbe()?.positionMs} ms)",
                heldFraction in 0.47f..0.62f,
            )

            // DRAG/HOLD while playback ticks: keep holding until the PLAYER's
            // own position advances ≥1.5 s (real 500 ms ticks passed
            // underneath)…
            assertTrue(
                "the ticker keeps advancing the real player during the hold",
                awaitCondition(6_000) {
                    (composeRule.playerProbe()?.positionMs ?: 0L) >= baselinePositionMs + 1_500
                },
            )
            // …the VM's ticker state (the slider's value SOURCE) emitted those
            // updates too — so the pinned thumb below is suppression, not a
            // dead ticker…
            assertTrue(
                "the VM ticker keeps emitting uiState.positionMs during the hold " +
                    "(before=$tickerBefore ms, now=${vmTickerPositionMs()} ms)",
                awaitCondition(6_000) { vmTickerPositionMs() >= tickerBefore + 1_500 },
            )
            // …and the slider is STILL pinned to the held value across them.
            val heldAfterTicks = sliderFraction()
            assertTrue(
                "slider still pinned to the held value after ≥1 ticker tick " +
                    "(slider=$heldAfterTicks, was $heldFraction)",
                heldAfterTicks in 0.47f..0.62f,
            )

            // NO live seek during the hold: the authoritative player position
            // must still be the early playback position (a live seek to ~55 %
            // of the 60 s fixture would read ≈33 000 ms). This assertion fails
            // against the pre-fix implementation, which seeked on every
            // movement.
            val probeDuringHold = composeRule.playerProbe()
            assertNotNull("player probe still available mid-gesture", probeDuringHold)
            assertTrue(
                "no live seek while holding the drag " +
                    "(position=${probeDuringHold?.positionMs} ms, baseline=$baselinePositionMs ms)",
                probeDuringHold!!.positionMs < baselinePositionMs + 15_000,
            )

            // DRAG CONTINUES: a further move to ~75 % — the held value follows
            // the finger, still with no player seek behind it.
            composeRule.onNode(seekSlider).performTouchInput {
                moveTo(percentOffset(0.75f, 0.5f))
            }
            val movedFraction = sliderFraction()
            assertTrue(
                "drag continue: the slider follows the finger to ~0.75 (slider=$movedFraction)",
                movedFraction in 0.68f..0.84f,
            )
        } finally {
            // AQR-081: never leave a pointer down if an assertion failed
            // mid-gesture (cleanup-only; the pointer may already be up).
            runCatching { composeRule.onNode(seekSlider).performTouchInput { up() } }
        }
    }

    // -----------------------------------------------------------------------
    // 2) Drag completes: a LITERAL seek count of one (Player.Listener) lands
    //    the released position; subsequent progress updates resume from ticker.
    // -----------------------------------------------------------------------

    @Test
    fun releasingTheDragSeeksOnceToTheReleasedPositionAndTheTickerResumes() {
        openRealPlayback()

        val probe = composeRule.playerProbe()
        assertNotNull("player probe available after playback starts", probe)
        val durationMs = probe!!.durationMs
        assertTrue("duration is real", durationMs > 0L)
        val targetPositionMs = (durationMs * 0.75).toLong()

        // AQR-080: count the player's own seek discontinuities for the whole
        // gesture — the literal "exactly one seek" observable.
        val counter = SeekCounter()
        val player = vmPlayer()
        assertNotNull("VM-owned player reachable for seek counting", player)
        composeRule.runOnIdle { player!!.addListener(counter) }
        try {
            composeRule.onNode(seekSlider).performTouchInput {
                down(percentOffset(0.5f, 0.5f))
                moveTo(percentOffset(0.75f, 0.5f))
            }

            // Pre-release: the whole gesture so far produced ZERO seeks — the
            // player is still near the start of the 60 s fixture (directional
            // bound — AQR-081), nowhere near the ~45 000 ms release target.
            val beforeRelease = composeRule.playerProbe()!!.positionMs
            assertTrue(
                "no seek before release (position=$beforeRelease ms, target≈$targetPositionMs ms)",
                beforeRelease < durationMs / 4,
            )
            assertEquals("zero player seeks while the finger is down", 0, counter.seeks)

            // DRAG COMPLETES → the single commit seek lands at the released spot.
            composeRule.onNode(seekSlider).performTouchInput { up() }
            assertTrue(
                "commit seek lands at the released position after onValueChangeFinished",
                awaitCondition(6_000) {
                    val p = composeRule.playerProbe()
                    p != null && abs(p.positionMs - targetPositionMs) <= 3_000
                },
            )
            assertTrue(
                "exactly one seek discontinuity for the whole gesture",
                awaitCondition(4_000) { counter.seeks == 1 },
            )
            // The slider catches up with the ticker-driven value at the seeked
            // position (≤1 tick after the commit).
            assertTrue(
                "slider resumes from the ticker at the seeked position",
                awaitCondition(4_000) { sliderFraction() in 0.68f..0.84f },
            )

            // SUBSEQUENT PROGRESS UPDATES: playback keeps advancing and the
            // ticker keeps driving the slider after the gesture is over…
            val first = sliderFraction()
            assertTrue(
                "progress resumes advancing after the drag (before=$first)",
                awaitCondition(4_000) { sliderFraction() > first + 0.004f },
            )
            // …and no STALE/duplicate seek arrives late (count still 1 after
            // several further ticks).
            assertEquals("no late duplicate seek", 1, counter.seeks)
        } finally {
            runCatching { composeRule.onNode(seekSlider).performTouchInput { up() } }
            runCatching {
                composeRule.runOnIdle { vmPlayer()?.removeListener(counter) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 3) Accessibility commit path (§21.6 keeps SetProgress intact): the
    //    slider's own semantics action must reach the SAME commit point.
    // -----------------------------------------------------------------------

    @Test
    fun accessibilitySetProgressCommitsTheSeekAndReleasesTheHeldValue() {
        openRealPlayback()

        val probe = composeRule.playerProbe()
        assertNotNull("player probe available after playback starts", probe)
        val durationMs = probe!!.durationMs
        assertTrue("duration is real", durationMs > 0L)
        val targetPositionMs = (durationMs * 0.75).toLong()

        // The TalkBack/assistive progress route: invoke the slider's own
        // SetProgress action with 0.75 (the slider's range is 0..1).
        composeRule.onNode(seekSlider).performSemanticsAction(SemanticsActions.SetProgress) {
            setProgress -> setProgress(0.75f)
        }

        assertTrue(
            "SetProgress route commits the seek to 0.75 of duration",
            awaitCondition(6_000) {
                val p = composeRule.playerProbe()
                p != null && abs(p.positionMs - targetPositionMs) <= 3_000
            },
        )
        // The held value was released too: if dragFraction had survived, the
        // thumb would FROZEN at 0.75 while playback advances — instead the
        // slider must keep following the ticker past the committed value.
        assertTrue(
            "slider keeps advancing after SetProgress (held value released)",
            awaitCondition(5_000) { sliderFraction() > 0.76f },
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Counts the player's own seek discontinuities (literal seek count). */
    private class SeekCounter : Player.Listener {
        @Volatile var seeks: Int = 0

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks++
        }
    }

    /** The VM-owned player (main thread), or null when not on the player route. */
    private fun vmPlayer(): Player? {
        var player: Player? = null
        composeRule.runOnIdle {
            val entry = composeRule.activity.navController?.currentBackStackEntry
            if (entry != null) {
                player = ViewModelProvider(entry)[PlayerViewModel::class.java]
                    .uiState.value.player
            }
        }
        return player
    }

    /** The VM ticker's own emitted position (uiState.positionMs), main thread. */
    private fun vmTickerPositionMs(): Long {
        var positionMs = -1L
        composeRule.runOnIdle {
            val entry = composeRule.activity.navController?.currentBackStackEntry
            if (entry != null) {
                positionMs = ViewModelProvider(entry)[PlayerViewModel::class.java]
                    .uiState.value.positionMs
            }
        }
        return positionMs
    }

    /** Navigates to the player with the real MP4 fixture and awaits controls. */
    private fun openRealPlayback() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigateToPlayer(episodeId)
        }
        assertTrue(
            "player route opens",
            awaitCondition(15_000) { isOnRoute<PlayerRoute>(composeRule.activity) },
        )
        assertTrue(
            "playback is active",
            awaitCondition(15_000) { composeRule.isPlayerPlaying() },
        )
        assertTrue("controls + seek slider visible", awaitControlsReady())
    }

    /**
     * Established controls-ready pattern (TouchTargetAndFocusTest): Back +
     * play/pause + the slider must be present; while a control is missing the
     * surface tap toggles the controls' visibility (no auto-hide timer exists —
     * the tap is a visibility toggle, nothing more).
     */
    private fun awaitControlsReady(): Boolean =
        awaitCondition(15_000) {
            val cds = composeRule.allContentDescriptions()
            val sliderPresent = composeRule.onAllNodes(seekSlider).fetchSemanticsNodes().isNotEmpty()
            val ready = cds.any { it == "Back" } &&
                (cds.any { it == "Play" } || cds.any { it == "Pause" }) &&
                sliderPresent
            if (!ready) {
                try {
                    composeRule.onNodeWithTag("player_root").performTouchInput {
                        click(percentOffset(0.08f, 0.6f))
                    }
                } catch (_: AssertionError) {
                    // transitional frame — next poll retries
                }
            }
            ready
        }

    /** The seek slider's rendered thumb fraction (0..1) from its own semantics. */
    private fun sliderFraction(): Float =
        composeRule.onNode(seekSlider).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current

    // -----------------------------------------------------------------------
    // 4) Interrupted gesture (AQR-091 / AQR-118): a pointer-cancel mid-drag
    //    resolves at the SAME single finish point as a release — the held
    //    value's ONE commit seek, never a live seek — the held value clears
    //    there, and the gesture machinery recovers so the next full drag
    //    still commits exactly once.
    // -----------------------------------------------------------------------

    @Test
    fun pointerCancelMidDrag_endsAtTheSingleFinishPointAndTheSliderRecovers() {
        openRealPlayback()

        val probe = composeRule.playerProbe()
        assertNotNull("player probe available after playback starts", probe)
        val durationMs = probe!!.durationMs
        assertTrue("duration is real", durationMs > 0L)
        val heldTargetMs = (durationMs * 0.55).toLong()

        // Literal seek count for the whole scenario (AQR-080's counter).
        val counter = SeekCounter()
        val player = vmPlayer()
        assertNotNull("VM-owned player reachable for seek counting", player)
        composeRule.runOnIdle { player!!.addListener(counter) }
        try {
            // DRAG BEGINS: press ~45 %, move to ~55 % — the held value takes
            // over from the ticker (test 1's established begin contract).
            composeRule.onNode(seekSlider).performTouchInput {
                down(percentOffset(0.45f, 0.5f))
                moveTo(percentOffset(0.55f, 0.5f))
            }
            val heldFraction = sliderFraction()
            assertTrue(
                "drag begin: the held value (~0.55) pins the slider (slider=$heldFraction)",
                heldFraction in 0.47f..0.62f,
            )
            assertEquals("no seek before the interruption", 0, counter.seeks)

            // INTERRUPTED GESTURE: the pointer is cancelled mid-drag (a system
            // takeover — notification shade, dialog, gesture-navigation steal).
            composeRule.onNode(seekSlider).performTouchInput { cancel() }

            // The interruption resolves at the same finish point as a release:
            // ONE commit seek at the held value — never a live seek, never a
            // second one — and it lands where the finger left it.
            assertTrue(
                "the commit seek lands at the held value after the cancel",
                awaitCondition(6_000) {
                    val p = composeRule.playerProbe()
                    p != null && abs(p.positionMs - heldTargetMs) <= 3_000
                },
            )
            assertEquals("exactly one seek for the interrupted gesture", 1, counter.seeks)

            // The held value cleared at that finish point: the slider is
            // ticker-driven again and ADVANCES past the held value (a held
            // value stuck at ~0.55 would never move again).
            assertTrue(
                "the slider resumes advancing after the interruption (held value cleared)",
                awaitCondition(6_000) { sliderFraction() > 0.56f },
            )

            // RECOVERY: a subsequent full drag still commits exactly once.
            composeRule.onNode(seekSlider).performTouchInput {
                down(percentOffset(0.6f, 0.5f))
                moveTo(percentOffset(0.75f, 0.5f))
            }
            composeRule.onNode(seekSlider).performTouchInput { up() }
            assertTrue(
                "the next full drag commits exactly one further seek",
                awaitCondition(6_000) { counter.seeks == 2 },
            )
        } finally {
            // AQR-081: never leave a pointer down if an assertion failed
            // mid-gesture (the pointer may already be up or cancelled).
            runCatching { composeRule.onNode(seekSlider).performTouchInput { up() } }
            runCatching {
                composeRule.runOnIdle { vmPlayer()?.removeListener(counter) }
            }
        }
    }
}
