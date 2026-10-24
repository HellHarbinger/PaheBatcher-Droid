package com.pahebatcher.app.ui.downloads

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Final-audit H5 (SPEC §26 "Display human-readable errors") — the Downloads
 * screen must actually RENDER the ViewModel's action-failure message:
 *
 *  - a non-null message shows as the `downloads_action_error` banner with a
 *    Dismiss action;
 *  - the banner carries a POLITE live region (§21.2-style announcement) and
 *    its text IS the message (never color alone, §21);
 *  - with no message (the default) the banner does not exist — existing
 *    render/navigation tests keep their exact semantics trees.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsScreenActionErrorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actionErrorRendersAsADismissibleAnnouncedBanner() {
        var dismissed = false
        composeRule.setContent {
            PaheBatcherTheme {
                DownloadsScreen(
                    uiState = DownloadsUiState(isLoading = false),
                    onCancel = {},
                    onRetry = {},
                    onPlay = {},
                    actionError = "Cancel failed: something broke",
                    onDismissActionError = { dismissed = true },
                )
            }
        }

        val banner = composeRule.onNodeWithTag("downloads_action_error")
        banner.assertIsDisplayed()
        composeRule.onNodeWithText("Cancel failed: something broke").assertIsDisplayed()

        // §21: the message is TEXT (not color-only) and announced politely.
        val config = banner.fetchSemanticsNode().config
        assertTrue(
            "the banner must carry a live region so TalkBack reports it",
            config.contains(SemanticsProperties.LiveRegion),
        )
        assertTrue(
            "the announcement must be polite (never interrupt the user)",
            config[SemanticsProperties.LiveRegion] == LiveRegionMode.Polite,
        )

        // Dismiss reaches the callback wired to ViewModel.dismissActionError().
        composeRule.onNodeWithText("Dismiss").performClick()
        assertTrue("Dismiss must invoke the acknowledgement callback", dismissed)
    }

    @Test
    fun withoutAMessageTheBannerDoesNotExist() {
        composeRule.setContent {
            PaheBatcherTheme {
                DownloadsScreen(
                    uiState = DownloadsUiState(isLoading = false),
                    onCancel = {},
                    onRetry = {},
                    onPlay = {},
                )
            }
        }

        composeRule.onNodeWithTag("downloads_action_error").assertDoesNotExist()
        composeRule.onNodeWithText("Dismiss").assertDoesNotExist()
    }
}
