package com.pahebatcher.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.ui.details.AnimeDetailsScreen
import com.pahebatcher.app.ui.details.AnimeDetailsUiState
import com.pahebatcher.app.ui.home.HomeScreen
import com.pahebatcher.app.ui.home.SearchUiState
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import com.pahebatcher.app.ui.watchlist.WatchlistScreen
import com.pahebatcher.app.ui.watchlist.WatchlistUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline
 * banner on the three network-dependent surfaces, state in / callbacks out.
 *
 * Asserted:
 *
 * - Home/Search, Anime Details and Watchlist render the banner EXACTLY while
 *   their UiState says OFFLINE (97.2: a non-offline state shows nothing —
 *   no false offline claim ever appears);
 * - the Watchlist's network-bound `Check now` control is disabled while
 *   OFFLINE and enabled again online (97.4), while the offline-validated
 *   `wl add` controls stay enabled (add is offline by design);
 * - the banner is plain readable text announced as a POLITE live region
 *   (Decision 55 / V2 §21.1: status changes are announced, never silent);
 * - typing still works while offline (the surface degrades honestly — the
 *   ViewModel refuses the search, the input itself never bricks).
 *
 * Stateless render tests: every screen is fed a UiState + recording lambdas,
 * no repositories, no Activity, no network.
 */
@RunWith(AndroidJUnit4::class)
class OfflineBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var lastQuery: String? = null

    private fun homeContent(offline: Boolean) {
        composeRule.setContent {
            PaheBatcherTheme {
                HomeScreen(
                    searchState = SearchUiState(offline = offline),
                    onQueryChange = { lastQuery = it },
                    onSubmitSearch = {},
                    onRetrySearch = {},
                    onOpenSettings = {},
                    onOpenDownloads = {},
                    onOpenDetails = {},
                    onLongPress = {},
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Home / Search
    // -----------------------------------------------------------------------

    @Test
    fun homeShowsTheBannerWhileOffline() {
        homeContent(offline = true)
        composeRule.onNodeWithTag("offline_banner").assertIsDisplayed()
        composeRule
            .onNodeWithText("You're offline — network features are unavailable.")
            .assertIsDisplayed()
    }

    @Test
    fun homeShowsNoBannerWhileOnline() {
        homeContent(offline = false)
        composeRule.onNodeWithTag("offline_banner").assertDoesNotExist()
    }

    @Test
    fun homeKeepsTheSearchInputInteractiveWhileOffline() {
        homeContent(offline = true)
        // The ViewModel refuses the search; the field itself never bricks.
        composeRule.onNodeWithTag("search_field").performTextInput("naruto")
        assertEquals("naruto", lastQuery)
    }

    // -----------------------------------------------------------------------
    // Anime details
    // -----------------------------------------------------------------------

    @Test
    fun animeDetailsShowsTheBannerWhileOffline() {
        composeRule.setContent {
            PaheBatcherTheme {
                AnimeDetailsScreen(
                    uiState = AnimeDetailsUiState(loading = false, offline = true),
                    onBack = {}, onRetry = {}, onSelectEpisode = {},
                    onRetryResolution = {}, onDownload = {},
                )
            }
        }
        composeRule.onNodeWithTag("offline_banner").assertIsDisplayed()
    }

    @Test
    fun animeDetailsShowsNoBannerWhileOnline() {
        composeRule.setContent {
            PaheBatcherTheme {
                AnimeDetailsScreen(
                    uiState = AnimeDetailsUiState(loading = false, offline = false),
                    onBack = {}, onRetry = {}, onSelectEpisode = {},
                    onRetryResolution = {}, onDownload = {},
                )
            }
        }
        composeRule.onNodeWithTag("offline_banner").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------
    // Watchlist
    // -----------------------------------------------------------------------

    private fun entry() = WatchlistEntity(
        sessionId = "sess-1",
        url = "https://animepahe.com/anime/sess-1",
        animeSession = "sess-1",
        host = "animepahe.com",
        title = "Test Anime",
        quality = 720,
        audioLang = "jpn",
        outputDir = "./downloads",
        maxParallel = 2,
        hlsWorkers = 24,
        keepTemp = false,
        autoRetry = 0,
        addedAt = 1_000L,
        lastCheckedAt = 0L,
        downloadedEpisodesJson = "[]",
    )

    @Test
    fun watchlistDisablesCheckNowWhileOfflineButKeepsAddEnabled() {
        composeRule.setContent {
            PaheBatcherTheme {
                WatchlistScreen(
                    uiState = WatchlistUiState(entries = listOf(entry()), offline = true),
                    onBack = {},
                    onUrlChange = {},
                    onAdd = {},
                    onRemove = {},
                    onCheckNow = {},
                    onRelink = {},
                    onSelectRelinkCandidate = {},
                    onConfirmRelink = {},
                    onDismissRelink = {},
                )
            }
        }

        composeRule.onNodeWithTag("offline_banner").assertIsDisplayed()
        // Network-bound control guarded (97.4)...
        composeRule.onNodeWithTag("watchlist_check_now").assertIsNotEnabled()
        // ...the manual relink's candidate search is network-bound too (108.4)...
        composeRule.onNodeWithTag("watchlist_row_relink").assertIsNotEnabled()
        // ...while the offline-validated `wl add` controls stay fully usable.
        composeRule.onNodeWithTag("watchlist_add").assertIsEnabled()
    }

    @Test
    fun watchlistEnablesCheckNowAgainOnceOnline() {
        composeRule.setContent {
            PaheBatcherTheme {
                WatchlistScreen(
                    uiState = WatchlistUiState(entries = listOf(entry()), offline = false),
                    onBack = {},
                    onUrlChange = {},
                    onAdd = {},
                    onRemove = {},
                    onCheckNow = {},
                    onRelink = {},
                    onSelectRelinkCandidate = {},
                    onConfirmRelink = {},
                    onDismissRelink = {},
                )
            }
        }

        composeRule.onNodeWithTag("offline_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("watchlist_check_now").assertIsEnabled()
        composeRule.onNodeWithTag("watchlist_row_relink").assertIsEnabled()
    }

    // -----------------------------------------------------------------------
    // Accessibility (Decision 55 / ROADMAP:766)
    // -----------------------------------------------------------------------

    @Test
    fun theBannerIsAPoliteLiveRegionOfPlainText() {
        homeContent(offline = true)

        val banner = composeRule.onNodeWithTag("offline_banner").fetchSemanticsNode()
        assertEquals(
            "the banner must be announced politely when it appears/disappears",
            LiveRegionMode.Polite,
            banner.config[SemanticsProperties.LiveRegion],
        )
        val text = banner.config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue(
            "the banner carries readable text, never colour alone (actual: '$text')",
            text.contains("offline"),
        )
    }
}
