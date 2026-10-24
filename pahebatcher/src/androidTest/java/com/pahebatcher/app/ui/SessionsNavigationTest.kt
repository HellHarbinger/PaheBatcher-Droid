package com.pahebatcher.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Session Manager entry point: reached from the
 * Downloads top app bar, back pops to Downloads. Real activity, real
 * navigation, Room-backed ViewModel — no test doubles on this path.
 */
@RunWith(AndroidJUnit4::class)
class SessionsNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun downloadsTopBarOpensSessionsAndBackReturnsToDownloads() {
        // The bottom tab is always visible; switch to Downloads first.
        composeRule.onNodeWithText("Downloads").performClick()
        composeRule.onNodeWithTag("downloads_root").assertIsDisplayed()

        // DECISIONS 110: the ONLY way into Sessions is this top-bar action.
        composeRule.onNodeWithTag("downloads_open_sessions").performClick()
        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeRule.onNodeWithTag("sessions_root").assertIsDisplayed()

        // Back pops to Downloads (the Sessions route is pushed, not a tab).
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Sessions").assertDoesNotExist()
        composeRule.onNodeWithTag("downloads_root").assertIsDisplayed()
    }

    @Test
    fun sessionsIsNotOfferedAsABottomTab() {
        // DECISIONS 110: Sessions is pushed from Downloads, never a tab.
        composeRule.onNodeWithText("Sessions").assertDoesNotExist()
    }
}
