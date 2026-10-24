package com.pahebatcher.app.ui.downloads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.ui.mapper.DownloadRowActions
import com.pahebatcher.app.ui.mapper.DownloadRowUi
import com.pahebatcher.app.ui.mapper.DownloadUserConcept
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Slice B (DECISIONS 146) — a Downloads row names the audio it was made with.
 *
 * The SUB and DUB downloads of one episode are two distinct rows (the episode
 * id carries the audio), but the dashboard rendered them identically, so the
 * only way to tell them apart was to guess from the details screen's current
 * selection — which is a different screen and a different moment. The row now
 * shows its own persisted audio beside the episode number.
 *
 * What these tests pin down is the thing that makes that trustworthy: the label
 * comes from the ROW, it never invents a language for a code it does not
 * recognise, and a row with no audio shows nothing at all rather than a default.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsRowAudioLabelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun row(
        number: Float,
        audio: String,
        episodeId: String,
    ) = DownloadRowUi(
        episodeId = episodeId,
        title = "Label Anime",
        episodeNumber = number,
        concept = DownloadUserConcept.COMPLETED,
        subLabel = null,
        retryCount = 0,
        errorMessage = null,
        downloadedBytes = 0,
        speedBps = null,
        etaSeconds = null,
        completedSegments = 100,
        totalSegments = 100,
        progressPercent = 100,
        actions = DownloadRowActions(cancel = false, retry = false, play = true, pause = false, resume = false),
        audio = audio,
    )

    private fun composeDownloads(vararg rows: DownloadRowUi) {
        composeRule.setContent {
            PaheBatcherTheme {
                DownloadsScreen(
                    uiState = DownloadsUiState(rows = rows.toList(), isLoading = false),
                    onCancel = {},
                    onRetry = {},
                    onPlay = {},
                )
            }
        }
    }

    @Test
    fun theSubAndDubDownloadsOfOneEpisodeAreDistinguishableOnTheDashboard() {
        composeDownloads(
            row(1f, "jpn", "sess_label_1_jpn"),
            row(1f, "eng", "sess_label_1_eng"),
        )

        composeRule.onNodeWithText("Episode 1 · Japanese").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 1 · English").assertIsDisplayed()
    }

    @Test
    fun anUnrecognisedAudioCodeIsEchoedRatherThanGuessedAt() {
        composeDownloads(row(2f, "kor", "sess_label_2_kor"))

        // SPEC §22 is an open set: 'kor' is not reduced to a SUB/DUB boolean and
        // is not silently relabelled as some language the code never named.
        composeRule.onNodeWithText("Episode 2 · KOR").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 2").assertDoesNotExist()
    }

    @Test
    fun aRowWithNoAudioShowsNoLabelAtAll() {
        composeDownloads(row(3f, "", "sess_label_3_none"))

        composeRule.onNodeWithText("Episode 3").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 3 · ").assertDoesNotExist()
    }

    @Test
    fun theLabelRidesOnTheExistingEpisodeLineWithoutAddingASecondOne() {
        // The label is a suffix of the line that already existed — no new row,
        // no new column, and the title/status/progress/actions around it are
        // untouched.
        composeDownloads(row(4f, "jpn", "sess_label_4_jpn"))

        composeRule.onNodeWithText("Label Anime").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 4 · Japanese").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 4").assertDoesNotExist()
    }

    @Test
    fun aFractionalEpisodeNumberKeepsItsExistingFormattingWithTheLabel() {
        composeDownloads(row(12.5f, "eng", "sess_label_125_eng"))

        composeRule.onNodeWithText("Episode 12.5 · English").assertIsDisplayed()
    }
}
