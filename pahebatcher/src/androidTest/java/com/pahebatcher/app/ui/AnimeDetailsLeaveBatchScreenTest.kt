package com.pahebatcher.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.ui.details.AnimeDetailsScreen
import com.pahebatcher.app.ui.details.AnimeDetailsUiState
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The leave-a-running-batch
 * confirmation as rendered by [AnimeDetailsScreen] (state in, lambdas out).
 *
 * Asserted:
 *
 * - the top-bar back icon routes to the warning WHILE a batch runs instead of
 *   navigating, and navigates directly when idle;
 * - the SYSTEM back gesture is intercepted by the same warning while running;
 * - the warning states the submitted-vs-pending counts;
 * - Stay dismisses without confirming; Leave confirms exactly once and never
 *   also dismisses;
 * - the warning renders at the largest font scale (DECISIONS 111 evidence for
 *   this new confirmation surface).
 */
@RunWith(AndroidJUnit4::class)
class AnimeDetailsLeaveBatchScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The system back path — what the gesture/navigation button sends. */
    private fun pressSystemBack() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private var backs = 0
    private var requested = 0
    private var stayed = 0
    private var confirmed = 0

    private fun setScreen(
        state: AnimeDetailsUiState,
        // DECISIONS 111: null keeps the device's own font scale (existing
        // tests unchanged); a value scales the whole surface — the dialog
        // sub-composition inherits the provided locals.
        fontScale: Float? = null,
    ) {
        val screen: @androidx.compose.runtime.Composable () -> Unit = {
            PaheBatcherTheme {
                AnimeDetailsScreen(
                    uiState = state,
                    onBack = { backs++ },
                    onRetry = {},
                    onSelectEpisode = {},
                    onRetryResolution = {},
                    onDownload = {},
                    onRequestLeaveBatch = { requested++ },
                    onStayInBatch = { stayed++ },
                    onConfirmLeaveBatch = { confirmed++ },
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

    private fun runningState(
        leaveBatchWarning: Boolean = false,
        submitted: Int = 0,
        pending: Int = 0,
    ) = AnimeDetailsUiState(
        session = "sess-batch",
        loading = false,
        title = "Test Anime",
        batchMode = true,
        batchRunning = true,
        batchTotal = submitted + pending,
        batchQueued = submitted,
        leaveBatchWarning = leaveBatchWarning,
        leaveBatchSubmitted = submitted,
        leaveBatchPending = pending,
    )

    @Test
    fun topBarBackWhileRunningRequestsTheWarningInsteadOfNavigating() {
        setScreen(runningState())

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals("the warning is requested", 1, requested)
        assertEquals("no navigation happened", 0, backs)
    }

    @Test
    fun topBarBackWhenIdleNavigatesDirectly() {
        setScreen(runningState().copy(batchRunning = false, batchMode = false))

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
        assertEquals(0, requested)
    }

    @Test
    fun systemBackWhileRunningOpensTheWarningNotTheBackStack() {
        setScreen(runningState())

        pressSystemBack()

        assertEquals("the back dispatcher was intercepted", 1, requested)
        assertEquals(0, backs)
    }

    @Test
    fun theWarningStatesTheSubmittedAndPendingCounts() {
        setScreen(
            runningState(leaveBatchWarning = true, submitted = 3, pending = 2),
        )

        composeRule.onNodeWithTag("anime_details_leave_batch_dialog").assertIsDisplayed()
        composeRule
            .onNodeWithText("Submitted: 3 · Not yet submitted: 2")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Downloads already submitted keep running. Episodes not yet submitted will not be queued.")
            .assertIsDisplayed()
    }

    @Test
    fun stayDismissesTheWarningWithoutConfirming() {
        setScreen(runningState(leaveBatchWarning = true, submitted = 1, pending = 1))

        composeRule.onNodeWithTag("anime_details_stay_batch").performClick()

        assertEquals("Stay is a dismissal", 1, stayed)
        assertEquals("…never a leave", 0, confirmed)
    }

    @Test
    fun systemBackWhileTheWarningIsOpenReportsStay() {
        setScreen(runningState(leaveBatchWarning = true, submitted = 1, pending = 1))

        // Back while the warning shows must dismiss it (Stay), never leave.
        pressSystemBack()

        assertEquals(1, stayed)
        assertEquals(0, confirmed)
    }

    @Test
    fun leaveConfirmsExactlyOnce() {
        setScreen(runningState(leaveBatchWarning = true, submitted = 1, pending = 1))

        composeRule.onNodeWithTag("anime_details_confirm_leave").performClick()

        assertEquals("Leave is the confirmation", 1, confirmed)
        assertEquals("…never also a dismissal", 0, stayed)
    }

    @Test
    fun theWarningSurvivesTheLargestFontScale() {
        // DECISIONS 111: this navigation-away confirmation is a NEW Phase 13
        // surface — it must render at the largest font scale.
        setScreen(
            runningState(leaveBatchWarning = true, submitted = 3, pending = 2),
            fontScale = 2f,
        )

        composeRule.onNodeWithTag("anime_details_leave_batch_dialog").assertIsDisplayed()
        composeRule
            .onNodeWithText("Submitted: 3 · Not yet submitted: 2")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_stay_batch").assertIsDisplayed()
        composeRule.onNodeWithTag("anime_details_confirm_leave").assertIsDisplayed()
    }
}
