package com.pahebatcher.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.support.allContentDescriptions
import com.pahebatcher.app.support.allTexts
import com.pahebatcher.app.ui.downloads.DownloadsScreen
import com.pahebatcher.app.ui.downloads.DownloadsUiState
import com.pahebatcher.app.ui.home.HomeScreen
import com.pahebatcher.app.ui.home.HomeViewModel
import com.pahebatcher.app.ui.home.SearchUiState
import com.pahebatcher.app.ui.mapper.DownloadRowActions
import com.pahebatcher.app.ui.mapper.DownloadRowUi
import com.pahebatcher.app.ui.mapper.DownloadUserConcept
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §36-9 — Accessibility semantics validation (PHASE11_ARCHITECTURE_V2 §21,
 * F-08), all against the REAL production screens:
 *
 *  - §21.1 semantics: poster slots + icon-only actions expose content
 *    descriptions built from real data; decorative glyphs (leading search icon,
 *    monogram, bottom-nav glyphs) expose NONE — proven by EXACT-set equality;
 *  - §21.2 progress: the determinate indicator exposes semantic
 *    `ProgressBarRangeInfo` with the real fraction; no fabricated `0%` text;
 *  - §21.2/§21.3 state: concept chips announce via TEXT (the six user
 *    concepts) and ONLY terminal chips carry a polite live region — progress
 *    ticks never announce;
 *  - §21.7 traversal order on a Downloads row: title → episode → chip →
 *    progress → metrics → action;
 *  - §21.4 font scale 2.0: Home and a full Downloads row render without
 *    clipped or overlapping text (every text fully inside the root, geometry
 *    pairs disjoint, scaled line heights) and long titles keep their FULL
 *    text in semantics while ellipsized visually;
 *  - dialog focus restoration (§21.7/§36-9) is vacuous in Phase 11: production
 *    contains ZERO AlertDialog/Dialog call sites (grep-verified; the §20
 *    permission rationale dialog is explicitly future work) — the routed-back
 *    focus-return half is asserted in [TouchTargetAndFocusTest].
 *
 * Touch-target sizes (§21.5, incl. custom player slots) live in
 * [TouchTargetAndFocusTest] because they require the real MainActivity.
 */
class AccessibilitySemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    // -----------------------------------------------------------------------
    // §21.1 — content descriptions: present on real data, absent on ornament
    // -----------------------------------------------------------------------

    @Test
    fun posterSlotsAndIconOnlyActionsCarryRealDescriptionsWhileDecorativeGlyphsCarryNone() {
        val viewModel = HomeViewModel(AnimeRepository(FakeSearchClient()))
        composeRule.setContent {
            PaheBatcherTheme {
                val searchState by viewModel.searchState.collectAsState()
                HomeScreen(
                    searchState = searchState,
                    onQueryChange = viewModel::onQueryChange,
                    onSubmitSearch = viewModel::submitSearch,
                    onRetrySearch = viewModel::submitSearch,
                    onOpenSettings = {},
                    onOpenDownloads = {},
                    onOpenDetails = {},
                    onLongPress = {},
                )
            }
        }

        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.onNodeWithTag("search_field").performTextInput("naruto")
        composeRule.onNodeWithTag("search_field").performImeAction()
        composeRule.waitUntil(5_000) { viewModel.searchState.value.results.isNotEmpty() }

        // EXACT set: the gear + the watchlist icon (icon-only actions) and both
        // posters (real data) plus the clear glyph — anything else would be an
        // unlabeled or decorative description leaking in (leading search icon: null CD).
        assertEquals(
            "Home must expose exactly the real-data content descriptions",
            listOf(
                "Bleach poster",
                "Clear search",
                "Naruto Shippuden poster",
                "Settings",
                "Watchlist",
            ),
            composeRule.allContentDescriptions().sorted(),
        )

        // §21.1 monogram: clearAndSetSemantics replaces the subtree, so the
        // title-initial glyphs ("N", "B") are NOT separately announced.
        val texts = composeRule.allTexts()
        assertFalse("monogram N must not be a separate text node", texts.contains("N"))
        assertFalse("monogram B must not be a separate text node", texts.contains("B"))
    }

    // -----------------------------------------------------------------------
    // §21.2 — semantic progress + no fabricated percentage
    // -----------------------------------------------------------------------

    @Test
    fun determinateProgressExposesSemanticProgressAndNoZeroPercentIsFabricated() {
        composeDownloads(
            downloadingRow(percent = 42, completed = 42, total = 100),
            // Unknown-total row: must never receive a determinate fraction.
            downloadingRow(episodeId = "ep-unknown", episodeNumber = 8f, percent = null, completed = 5, total = null),
        )

        val progressInfos = composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ProgressBarRangeInfo] }

        val determinateNonZero = progressInfos.filter { it.current > 0.01f }
        assertEquals(
            "exactly ONE node reports determinate non-zero progress " +
                "(the known-total row; infos=$progressInfos)",
            1,
            determinateNonZero.size,
        )
        val info = determinateNonZero.single()
        assertEquals(0f, info.range.start, 0.0001f)
        assertEquals(1f, info.range.endInclusive, 0.0001f)
        assertEquals(
            "semantic progress equals the persisted fraction (42%)",
            0.42f,
            info.current,
            0.01f,
        )

        // §16.1/§21.2: a row with no persisted denominator never renders a
        // fabricated percentage (0% or otherwise).
        assertFalse(
            "no fabricated 0% may appear: ${composeRule.allTexts()}",
            composeRule.allTexts().any { "0%" in it },
        )
        // The unknown-total row still reports its REAL segment count as text.
        assertTrue(
            "real segment text without denominator renders: ${composeRule.allTexts()}",
            composeRule.allTexts().any { it.contains("5 segments") },
        )
    }

    // -----------------------------------------------------------------------
    // §21.3 + §21.2 — chips use text; ONLY terminal transitions announce
    // -----------------------------------------------------------------------

    @Test
    fun conceptChipsUseTextLabelsAndOnlyTerminalStatesCarryLiveRegions() {
        composeDownloads(
            downloadingRow(),
            DownloadRowUi(
                episodeId = "ep-done",
                title = "Finished Anime",
                episodeNumber = 1f,
                concept = DownloadUserConcept.COMPLETED,
                subLabel = null,
                retryCount = 0,
                errorMessage = null,
                downloadedBytes = 0,
                speedBps = null,
                etaSeconds = null,
                completedSegments = 100,
                totalSegments = null,
                progressPercent = null,
                actions = DownloadRowActions(cancel = false, retry = false, play = true, pause = false, resume = false),
                audio = "jpn",
            ),
            DownloadRowUi(
                episodeId = "ep-failed",
                title = "Failed Anime",
                episodeNumber = 2f,
                concept = DownloadUserConcept.FAILED,
                subLabel = null,
                retryCount = 1,
                errorMessage = "Network error",
                downloadedBytes = 0,
                speedBps = null,
                etaSeconds = null,
                completedSegments = 0,
                totalSegments = null,
                progressPercent = null,
                actions = DownloadRowActions(cancel = false, retry = true, play = false, pause = false, resume = false),
                audio = "jpn",
            ),
            DownloadRowUi(
                episodeId = "ep-cancelled",
                title = "Cancelled Anime",
                episodeNumber = 3f,
                concept = DownloadUserConcept.CANCELLED,
                subLabel = null,
                retryCount = 0,
                errorMessage = null,
                downloadedBytes = 0,
                speedBps = null,
                etaSeconds = null,
                completedSegments = 0,
                totalSegments = null,
                progressPercent = null,
                actions = DownloadRowActions(cancel = false, retry = true, play = false, pause = false, resume = false),
                audio = "jpn",
            ),
        )

        // §21.3: state is TEXT, never color alone — all four chip labels exist.
        val texts = composeRule.allTexts()
        listOf("downloading", "completed", "failed", "cancelled").forEach { label ->
            assertTrue("chip text '$label' must exist: $texts", texts.contains(label))
        }

        // §21.2: EXACTLY the terminal chips are live regions, all polite.
        val allNodes = composeRule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        val liveNodes = allNodes.filter { it.config.contains(SemanticsProperties.LiveRegion) }
        val liveTexts = liveNodes.map { node ->
            node.config[SemanticsProperties.Text].joinToString { it.text }
        }.sorted()
        assertEquals(
            "only terminal transitions announce (never progress ticks)",
            listOf("cancelled", "completed", "failed"),
            liveTexts,
        )
        liveNodes.forEach { node ->
            assertEquals(
                "terminal announcements are polite",
                LiveRegionMode.Polite,
                node.config[SemanticsProperties.LiveRegion],
            )
        }

        // The in-flight chip exists but must NOT announce.
        val downloadingNodes = allNodes.filter { node ->
            node.config.contains(SemanticsProperties.Text) &&
                node.config[SemanticsProperties.Text].any { it.text == "downloading" }
        }
        assertTrue("downloading chip text node exists", downloadingNodes.isNotEmpty())
        downloadingNodes.forEach { node ->
            assertFalse(
                "progress ticks must never be live regions",
                node.config.contains(SemanticsProperties.LiveRegion),
            )
        }

        // §21.1 + DECISIONS 110 (supersedes the Phase 11 "zero content
        // descriptions" snapshot): the Sessions top-bar entry is Downloads'
        // ONLY icon-only control, and §21.1/Decision 55 REQUIRE a real-string
        // content description on it. Exact-set equality proves both halves of
        // the contract — the icon-only action IS labeled ("Sessions"), and
        // nothing else exposes a redundant or unlabeled description (row
        // Cancel/Retry/Play remain text-labeled).
        assertEquals(
            "Downloads exposes exactly the labeled icon-only action (Sessions)",
            listOf("Sessions"),
            composeRule.allContentDescriptions().sorted(),
        )
        composeRule
            .onNodeWithContentDescription("Sessions")
            .assertHasClickAction()
    }

    // -----------------------------------------------------------------------
    // §21.7 — TalkBack traversal order follows visual order on a row
    // -----------------------------------------------------------------------

    @Test
    fun downloadsRowTraversalOrderFollowsVisualOrder() {
        composeDownloads(
            downloadingRow(
                episodeId = "ep-traversal",
                title = "Traversal Anime",
                episodeNumber = 7f,
                percent = 42,
                completed = 42,
                total = 100,
            ),
        )

        // Unmerged tree = raw layout order (title → episode → chip → progress
        // → metrics → action, the §16.1/§21.7 row sequence).
        val nodes = composeRule
            .onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()

        fun hasText(node: androidx.compose.ui.semantics.SemanticsNode, expected: String): Boolean =
            node.config.contains(SemanticsProperties.Text) &&
                node.config[SemanticsProperties.Text].any { it.text == expected }

        // Slice B: the episode line carries the row's own audio label after the
        // number (`Episode 7 · Japanese`), so it is matched by its leading
        // episode text. The node's POSITION is what this test pins, not its
        // exact wording.
        fun hasTextStartingWith(node: androidx.compose.ui.semantics.SemanticsNode, prefix: String): Boolean =
            node.config.contains(SemanticsProperties.Text) &&
                node.config[SemanticsProperties.Text].any { it.text.startsWith(prefix) }

        val titleIdx = nodes.indexOfFirst { hasText(it, "Traversal Anime") }
        val episodeIdx = nodes.indexOfFirst { hasTextStartingWith(it, "Episode 7") }
        val chipIdx = nodes.indexOfFirst { hasText(it, "downloading") }
        val progressIdx = nodes.indexOfFirst {
            it.config.contains(SemanticsProperties.ProgressBarRangeInfo)
        }
        val metricsIdx = nodes.indexOfFirst { hasText(it, "42/100 segments · 42%") }
        val actionIdx = nodes.indexOfFirst { hasText(it, "Cancel") }

        val order = mapOf(
            "title" to titleIdx,
            "episode" to episodeIdx,
            "chip" to chipIdx,
            "progress" to progressIdx,
            "metrics" to metricsIdx,
            "action" to actionIdx,
        )
        assertTrue("all row elements exist in the traversal: $order", order.values.all { it >= 0 })
        val sequence = listOf(titleIdx, episodeIdx, chipIdx, progressIdx, metricsIdx, actionIdx)
        assertEquals(
            "traversal order must be title → episode → chip → progress → metrics → action " +
                "($order)",
            sequence,
            sequence.sorted(),
        )
    }

    // -----------------------------------------------------------------------
    // §21.4 — font scale 2.0: no clipped or overlapping text
    // -----------------------------------------------------------------------

    @Test
    fun homeRendersAtDoubleFontScaleWithoutClippingOrOverlap() {
        composeAtDoubleFontScale {
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

        val expectedTexts = listOf(
            "PaheBatcher",
            "Search animepahe, pick episodes, download and watch them.",
            "Search anime titles…",
            "Videos are saved to Movies/<anime title>/",
            "No downloads yet — downloads you start will appear here.",
        )
        assertEquals(
            "the §12 composition survives font scale 2.0 unchanged",
            expectedTexts.sorted(),
            composeRule.allTexts().sorted(),
        )
        assertNoClippingOrOverlap(expectedTexts)
    }

    @Test
    fun downloadRowRendersAtDoubleFontScaleWithoutClippingOrOverlap() {
        composeAtDoubleFontScale {
            DownloadsScreen(
                uiState = DownloadsUiState(
                    rows = listOf(
                        downloadingRow(
                            episodeId = "ep-scale",
                            title = LONG_TITLE,
                            episodeNumber = 7f,
                            percent = 42,
                            completed = 42,
                            total = 100,
                        ),
                    ),
                    isLoading = false,
                ),
                onCancel = {},
                onRetry = {},
                onPlay = {},
            )
        }

        // §21.4: the ellipsized 2-line title keeps its FULL text in semantics
        // (the full-text equivalent required for single/limited-line labels).
        assertTrue(
            "full title preserved in semantics while visually ellipsized",
            composeRule.allTexts().contains(LONG_TITLE),
        )

        val visibleTexts = listOf(
            LONG_TITLE,
            "Episode 7 · Japanese",
            "downloading",
            "42/100 segments · 42%",
            "Cancel",
            "Downloads",
        )
        assertNoClippingOrOverlap(visibleTexts)
    }

    // -----------------------------------------------------------------------
    // Helpers + fixtures
    // -----------------------------------------------------------------------

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

    private fun composeAtDoubleFontScale(content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                PaheBatcherTheme { content() }
            }
        }
    }

    /**
     * §21.4 assertions for [texts] at the current (double) font scale:
     * every text is fully inside the root (no screen clipping), each text line
     * box is at least 24dp tall (proves the 2x scale actually applied AND that
     * no fixed-height container squashes it — at 1x these lines are ≤20dp), and
     * no two text boxes overlap.
     */
    private fun assertNoClippingOrOverlap(texts: List<String>) {
        val rootBounds: Rect = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val minHeightPx = with(composeRule.density) { 24.dp.toPx() }
        val labels = mutableListOf<String>()
        val rects = mutableListOf<Rect>()

        texts.forEach { text ->
            // Unmerged tree: Home's root is combinedClickable (mergeDescendants),
            // so in the MERGED tree every text collapses into ONE full-screen
            // node — each "geometry" check would measure that same root box
            // against itself instead of the real text layout box.
            val interaction = composeRule.onNodeWithText(text, useUnmergedTree = true)
            interaction.assertIsDisplayed()
            val rect = interaction.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "'$text' must be fully inside the root at font scale 2.0 " +
                    "(rect=$rect root=$rootBounds)",
                rect.top >= rootBounds.top - 1f &&
                    rect.left >= rootBounds.left - 1f &&
                    rect.bottom <= rootBounds.bottom + 1f &&
                    rect.right <= rootBounds.right + 1f,
            )
            assertTrue(
                "'$text' line box must be ≥24dp at font scale 2.0 (scaled, not clipped): " +
                    "${with(composeRule.density) { rect.height.toDp() }}",
                rect.height >= minHeightPx,
            )
            labels += text
            rects += rect
        }

        rects.indices.forEach { i ->
            (i + 1 until rects.size).forEach { j ->
                assertFalse(
                    "texts must not overlap at font scale 2.0: '${labels[i]}' vs '${labels[j]}'" +
                        " (rects: ${rects[i]} vs ${rects[j]})",
                    rects[i].overlaps(rects[j]),
                )
            }
        }
    }

    /** Determinate downloading row (§16.1 field semantics). */
    private fun downloadingRow(
        episodeId: String = "ep-a11y",
        title: String = "Test Anime",
        episodeNumber: Float = 7f,
        percent: Int? = 42,
        completed: Int = 42,
        total: Int? = 100,
    ) = DownloadRowUi(
        episodeId = episodeId,
        title = title,
        episodeNumber = episodeNumber,
        concept = DownloadUserConcept.DOWNLOADING,
        subLabel = null,
        retryCount = 0,
        errorMessage = null,
        downloadedBytes = 0,
        speedBps = null,
        etaSeconds = null,
        completedSegments = completed,
        totalSegments = total,
        progressPercent = percent,
        actions = DownloadRowActions(cancel = true, retry = false, play = false, pause = true, resume = false),
        audio = "jpn",
    )

    /** Fixed honest search rows returned for ANY query (androidTest fixture only). */
    private class FakeSearchClient : HttpClient {
        val requestedAtMillis = CopyOnWriteArrayList<Long>()

        override suspend fun fetchJson(url: String): String? {
            requestedAtMillis.add(android.os.SystemClock.elapsedRealtime())
            return SEARCH_RESPONSE
        }

        override suspend fun fetchHtml(url: String): String? = null
    }

    private companion object {
        const val LONG_TITLE =
            "A Very Long Anime Title That Will Definitely Wrap Across Multiple Lines " +
                "When The Font Scale Doubles On A Small Screen"

        const val SEARCH_RESPONSE =
            """{"data":""" +
                """[{"session":"s1","title":"Naruto Shippuden","type":"TV","year":2007,"episodes":500,"score":"8.2"},""" +
                """{"session":"s2","title":"Bleach","type":"TV","year":2004,"episodes":366,"score":"8.0"}]}"""
    }
}
