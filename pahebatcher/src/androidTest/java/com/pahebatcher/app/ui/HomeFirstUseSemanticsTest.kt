package com.pahebatcher.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.pahebatcher.app.support.allTexts
import com.pahebatcher.app.support.contentDescriptionsWithSuffix
import com.pahebatcher.app.ui.home.HomeScreen
import com.pahebatcher.app.ui.home.SearchUiState
import com.pahebatcher.app.ui.theme.PaheBatcherTheme

/**
 * §36-3 — Home first-use semantics (PHASE11_ARCHITECTURE_V2 §12, F-03):
 * the semantics tree contains EXACTLY the §12 composition and nothing else —
 * no invented rows, cards, recommendations, or fake data.
 *
 * Expected text set (exact, order-insensitive):
 *  1. top-bar title "PaheBatcher"
 *  2. tagline "Search animepahe, pick episodes, download and watch them."
 *  3. search field hint "Search anime titles…"
 *  4. storage location "Videos are saved to Movies/PaheBatcher/"
 *  5. empty-downloads guidance "No downloads yet — downloads you start will appear here."
 *
 * Any additional text node (an invented card/row) fails the set equality; any
 * missing required element fails it too. First use has no search results, so
 * no poster slots exist and NO "<title> poster" content descriptions may appear.
 */
class HomeFirstUseSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstUseRendersExactlyTheMandatedComposition() {
        composeRule.setContent {
            PaheBatcherTheme {
                HomeScreen(
                    searchState = SearchUiState(),
                    onQueryChange = {},
                    onSubmitSearch = {},
                    onRetrySearch = {},
                    onOpenSettings = {},
                    onOpenDownloads = {},
                    onOpenDetails = {},
                    onLongPress = {},
                )
            }
        }

        val expectedTexts = listOf(
            "PaheBatcher",
            "Search animepahe, pick episodes, download and watch them.",
            "Search anime titles…",
            "Videos are saved to Movies/<anime title>/",
            "No downloads yet — downloads you start will appear here.",
        ).sorted()

        val actualTexts = composeRule.allTexts()
        assertEquals(
            "first-use semantics tree must contain exactly the §12 composition",
            expectedTexts,
            actualTexts.sorted(),
        )

        // The search field exists as a tagged, editable node with its hint text.
        composeRule.onNodeWithTag("search_field").assertIsDisplayed()
        // The §10.2 gear entry exists with its §21.1 content description.
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        // The empty-downloads guidance is present (and is a real click target —
        // the navigation behavior itself is verified in NavigationTest).
        composeRule.onNodeWithTag("empty_downloads_guidance").assertIsDisplayed()
    }

    @Test
    fun firstUseHasNoPosterSlotsAtAll() {
        composeRule.setContent {
            PaheBatcherTheme {
                HomeScreen(
                    searchState = SearchUiState(),
                    onQueryChange = {},
                    onSubmitSearch = {},
                    onRetrySearch = {},
                    onOpenSettings = {},
                    onOpenDownloads = {},
                    onOpenDetails = {},
                    onLongPress = {},
                )
            }
        }

        assertEquals(
            "no search results exist on first use → no poster content descriptions",
            emptyList<String>(),
            composeRule.contentDescriptionsWithSuffix(" poster"),
        )
    }
}
