package com.pahebatcher.app.ui

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.support.backStackSize
import com.pahebatcher.app.support.isImeVisibleOnActivity
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.ui.navigation.DownloadsRoute
import com.pahebatcher.app.ui.navigation.HomeRoute
import com.pahebatcher.app.ui.navigation.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * §36-4 — Navigation tests (PHASE11_ARCHITECTURE_V2 §10, F-02):
 *
 *  - bottom bar = EXACTLY Home + Downloads (Settings is never a tab);
 *  - tab switches navigate and REPEAT TAPS never stack duplicate destinations
 *    (`launchSingleTop` + `popUpTo(home)` — back-stack size stays bounded);
 *  - per-tab stack is retained across tab switches (`saveState`/`restoreState`,
 *    §10.4 "each tab keeps its own back stack": Home → Settings → Downloads →
 *    Home restores Settings);
 *  - Activity recreation (SavedState path — graph + pushed destination +
 *    selected tab restore, the §36-4 restoration contract);
 *  - the empty-downloads guidance really navigates to the downloads tab;
 *  - §36-10: navigating away never leaves the IME visible.
 *
 * Runs against the REAL MainActivity (no fakes): Home requires no network for
 * any interaction performed here (nothing is ever typed — see SearchImeTest
 * for the fake-repository search tests).
 */
class NavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // -----------------------------------------------------------------------
    // Bottom bar composition (§10.1/§10.2)
    // -----------------------------------------------------------------------

    @Test
    fun bottomBarContainsExactlyHomeAndDownloadsAndSettingsIsNotATab() {
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Downloads").assertIsDisplayed()
        assertEquals(
            "exactly one Home bottom-nav item",
            1,
            composeRule.onAllNodesWithText("Home").fetchSemanticsNodes().size,
        )
        assertEquals(
            "exactly one Downloads bottom-nav item",
            1,
            composeRule.onAllNodesWithText("Downloads").fetchSemanticsNodes().size,
        )
        // Settings exists ONLY as the Home top-bar gear (content description) —
        // no Settings TEXT node may exist while Home is showing (not a tab).
        assertEquals(
            "Settings must not appear as a bottom-nav label",
            0,
            composeRule.onAllNodesWithText("Settings").fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // Tab switching + duplicate-tap safety (§10.4)
    // -----------------------------------------------------------------------

    @Test
    fun tabSwitchNavigatesAndRepeatTapsNeverStackDuplicateDestinations() {
        composeRule.waitForIdle()

        repeat(3) {
            composeRule.onNodeWithText("Downloads").performClick()
            awaitNode("downloads_root")
            assertEquals(
                "stack after Home → Downloads must be exactly [Home, Downloads]",
                2,
                stackSize(),
            )

            composeRule.onNodeWithText("Home").performClick()
            awaitNode("home_root")
            assertEquals(
                "returning to Home pops Downloads (saveState keeps it restorable)",
                1,
                stackSize(),
            )
        }

        // Repeat-tap on the already-selected tab is a no-op (launchSingleTop).
        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitForIdle()
        assertEquals(1, stackSize())
        assertTrue("still on Home", topRouteIs<HomeRoute>())
    }

    // -----------------------------------------------------------------------
    // Per-tab stack retention (§36-4 restoreState)
    // -----------------------------------------------------------------------

    @Test
    fun perTabBackStackIsRetainedAcrossTabSwitches() {
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        awaitNode("settings_root")
        assertEquals("[Home, Settings] after pushing Settings", 2, stackSize())

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")
        assertTrue(topRouteIs<DownloadsRoute>())

        composeRule.onNodeWithText("Home").performClick()
        awaitNode("settings_root")
        assertEquals(
            "Home tab restored its stack — Settings is back on top (restoreState)",
            2,
            stackSize(),
        )
        assertTrue(topRouteIs<SettingsRoute>())
    }

    // -----------------------------------------------------------------------
    // SavedState restoration (§36-4 recreation)
    // -----------------------------------------------------------------------

    @Test
    fun recreationRestoresGraphPushedDestinationAndSelectedTab() {
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        awaitNode("settings_root")

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        awaitNode("settings_root")
        assertEquals("pushed destination restored after recreation", 2, stackSize())
        assertTrue(topRouteIs<SettingsRoute>())

        // Up from restored Settings returns to Home (stack unwinds correctly).
        composeRule.onNodeWithContentDescription("Back").performClick()
        awaitNode("home_root")
        assertEquals(1, stackSize())
    }

    @Test
    fun recreationOnDownloadsRestoresSelectedTab() {
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        awaitNode("downloads_root")
        assertTrue("selected tab restored", topRouteIs<DownloadsRoute>())
        // The bottom nav marks Downloads selected (§10.1 selected state) — the
        // Downloads TOP-APP-BAR title carries the same word, so disambiguate by
        // the Tab role (the earlier failure dump confirmed Role = Tab on the bar).
        composeRule
            .onNode(
                SemanticsMatcher("Downloads tab") { node ->
                    node.config.contains(SemanticsProperties.Role) &&
                        node.config[SemanticsProperties.Role] == Role.Tab &&
                        node.config.contains(SemanticsProperties.Text) &&
                        node.config[SemanticsProperties.Text].any { it.text == "Downloads" }
                },
            )
            .assertIsSelected()
    }

    // -----------------------------------------------------------------------
    // §12.4 guidance really navigates
    // -----------------------------------------------------------------------

    @Test
    fun emptyDownloadsGuidanceOpensDownloadsTab() {
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("No downloads yet — downloads you start will appear here.")
            .performClick()

        awaitNode("downloads_root")
        assertTrue(topRouteIs<DownloadsRoute>())
        assertEquals(2, stackSize())
    }

    // -----------------------------------------------------------------------
    // §36-10: navigation never leaves the IME visible
    // -----------------------------------------------------------------------

    @Test
    fun navigatingAwayLeavesNoImeWindowVisible() {
        composeRule.waitForIdle()

        // Focus the search field so the IME opens. NO TEXT is typed, so no
        // search is ever scheduled (§10 network discipline).
        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.waitUntil(5_000) { composeRule.isImeVisibleOnActivity() }

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")
        composeRule.waitUntil(5_000) { !composeRule.isImeVisibleOnActivity() }
    }

    @Test
    fun systemBackClosesTheKeyboardBeforeAnyNavigation() {
        composeRule.waitForIdle()

        // Focus the search field so the IME opens (no text → no search, §10).
        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.waitUntil(5_000) { composeRule.isImeVisibleOnActivity() }

        // §14.1: system back with the IME open FIRST closes the keyboard
        // (standard platform ordering — never overridden) and that back must
        // NOT navigate anywhere.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        composeRule.waitUntil(5_000) { !composeRule.isImeVisibleOnActivity() }
        composeRule.onNodeWithTag("home_root").assertExists()
        assertTrue(
            "the keyboard-closing back must not navigate — still on Home",
            topRouteIs<HomeRoute>(),
        )
        assertEquals(
            "back was consumed by the IME — back stack unchanged",
            1,
            stackSize(),
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun awaitNode(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertExists()
    }

    private fun stackSize(): Int {
        val activity = composeRule.activity // read on the test thread (canonical usage)
        return composeRule.runOnIdle { backStackSize(activity) }
    }

    private inline fun <reified T : Any> topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<T>(activity) }
    }
}
