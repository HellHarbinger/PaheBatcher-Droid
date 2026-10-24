package com.pahebatcher.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import com.pahebatcher.app.ui.navigation.AnimeDetailsRoute
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 11.5E-2 — the anime details destination is REGISTERED in the production
 * [MainActivity] NavHost and its ViewModel resolves through the production
 * [com.pahebatcher.app.di.AppViewModelFactory] (not a test factory).
 *
 * The route is pushed with a BLANK session on purpose: [AnimeDetailsViewModel.load]
 * rejects it before [com.pahebatcher.app.data.repository.EpisodeRepository.scan]
 * is ever reached, so the whole registration → factory → ViewModel → screen chain
 * is exercised with ZERO network traffic. The real search → details → episode
 * scan chain is verified on the physical device (11.5E-2 report) and the tap→
 * session contract is asserted in [SearchImeTest].
 */
class AnimeDetailsDestinationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun animeDetailsDestinationIsRegisteredAndResolvesItsProductionViewModel() {
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigate(AnimeDetailsRoute(session = ""))
        }

        assertTrue(
            "AnimeDetailsRoute must be the top destination after navigating to it",
            awaitCondition(5_000) { topRouteIs<AnimeDetailsRoute>() },
        )

        // The screen rendered through the real factory — its honest error branch
        // for the rejected blank session (no fabricated episodes, no fake success).
        composeRule.onNodeWithText(AnimeDetailsViewModel.ERROR_SCAN_FAILED).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    private inline fun <reified T : Any> topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<T>(activity) }
    }
}
