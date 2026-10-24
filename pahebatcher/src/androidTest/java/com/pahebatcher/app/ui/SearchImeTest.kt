package com.pahebatcher.app.ui

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.ui.home.HomeScreen
import com.pahebatcher.app.ui.home.HomeViewModel
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §36-10 — Search IME tests (PHASE11_ARCHITECTURE_V2 §14.1, F-16), composed
 * against a FAKE in-test [HttpClient] so no real network is ever touched:
 *
 *  - IME Search action executes the SAME submission path immediately
 *    (timestamped: the request fires well inside the PENDING debounce window —
 *    the IME test injects [TEST_DEBOUNCE_MS], see below);
 *  - exactly ONE request — the pending debounce is cancelled, never double-fired;
 *  - a purely typed query never submits immediately and still executes through
 *    the debounce (both timing tests inject [TEST_DEBOUNCE_MS], see below);
 *  - focus stays in the field while editing AND after submitting (no focus steal);
 *  - tapping a result clears focus (IME dismissal is the platform consequence —
 *    the window-level IME assertion for navigation lives in NavigationTest) AND
 *    hands the row's own session to the anime details destination callback
 *    (11.5E-2);
 *  - the result's poster slot exposes its §21.1 `"<title> poster"` description.
 *
 * The unit-level debounce/cancellation clock tests live in HomeViewModelTest;
 * this class proves the Compose wiring end to end.
 *
 * Timing seam: the two timing-sensitive tests (IME submit and typed-query
 * debounce) inject [TEST_DEBOUNCE_MS] (10 s) instead of the production 350 ms:
 *  - IME test: the harness's `performImeAction` dispatch latency (internal
 *    runOnIdle → semantics action on the main looper) can drift past 350 ms on
 *    a loaded emulator, letting the pending debounce fire BEFORE the IME
 *    submit (first request ≥ 364 ms, and debounce + submit = two requests).
 *  - typed-query test: `typedAt` is captured after `performTextInput` returns
 *    while the debounce coroutine can start a few ms EARLIER during text
 *    commit, so `firstRequest - typedAt` can legitimately land a few ms under
 *    the 350 ms bound (observed 344 ms) with no production defect.
 * The proofs themselves are unchanged — typing schedules a pending debounce and
 * never submits immediately, the IME submit preempts its debounce, exactly one
 * request each — and become deterministic against the injected window. The
 * production 350 ms value and its clock behavior remain pinned by
 * HomeViewModelTest's fake-clock tests (band check, 349/350 ms boundary,
 * flush-without-wait, no-double-fire); the typed-query floor assertion below
 * still cites DEBOUNCE_MS but measures ~9.65 s clear of that boundary.
 */
class SearchImeTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Fixed honest search rows returned for ANY query (test fixture data — androidTest only). */
    private class FakeSearchClient : HttpClient {
        val requestedAtMillis = CopyOnWriteArrayList<Long>()

        override suspend fun fetchJson(url: String): String? {
            requestedAtMillis.add(SystemClock.elapsedRealtime())
            return SEARCH_RESPONSE
        }

        override suspend fun fetchHtml(url: String): String? = null
    }

    private fun setupScreen(
        debounceMs: Long = HomeViewModel.DEBOUNCE_MS,
        onOpenDetails: (String) -> Unit = {},
    ): Pair<HomeViewModel, FakeSearchClient> {
        val client = FakeSearchClient()
        val viewModel = HomeViewModel(AnimeRepository(client), debounceMs)
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
                    onOpenDetails = onOpenDetails,
                    onLongPress = {},
                )
            }
        }
        return viewModel to client
    }

    @Test
    fun imeSearchActionExecutesTheSameSubmissionPathImmediately() {
        val (viewModel, client) = setupScreen(debounceMs = TEST_DEBOUNCE_MS)

        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.onNodeWithTag("search_field").performTextInput("naruto")
        val typedAt = SystemClock.elapsedRealtime()

        // Contract: typing alone must NOT submit — the scheduled debounce is
        // pending (TEST_DEBOUNCE_MS away) and the rule is idle, so any request
        // recorded here would mean the typing path submitted immediately.
        composeRule.waitForIdle()
        assertTrue("typing must not submit before the IME action", client.requestedAtMillis.isEmpty())

        // IME Search action → identical submitSearch() event (§14.1 one path).
        composeRule.onNodeWithTag("search_field").performImeAction()

        // Window covers the injected debounce so BOTH possible racers (the IME
        // dispatch and, in a pathological run, the 10 s debounce) have resolved
        // before the assertions — an honest contract failure instead of a
        // waitUntil timeout.
        composeRule.waitUntil(15_000) { viewModel.searchState.value.results.isNotEmpty() }
        composeRule.onNodeWithText("Naruto Shippuden", substring = true).assertExists()
        composeRule.onNodeWithText("Bleach", substring = true).assertExists()

        // Discriminator: the request fired via the submit path — it preempted
        // the still-pending debounce. The bound is the INJECTED window (10 s,
        // ≫ the harness's worst observed IME-dispatch latency of ~0.4 s), so
        // what is proven here is the ORDERING contract; the production 350 ms
        // clock contract itself lives in HomeViewModelTest's fake-clock tests.
        val firstRequest = client.requestedAtMillis.first()
        assertTrue(
            "IME submit must preempt the pending ${TEST_DEBOUNCE_MS}ms debounce " +
                "(elapsed ${firstRequest - typedAt}ms)",
            firstRequest - typedAt < TEST_DEBOUNCE_MS,
        )
        // Exactly one request: the pending debounce was cancelled, no double-fire.
        assertEquals(
            "pending debounce must be cancelled by the IME submit (one request total)",
            1,
            client.requestedAtMillis.size,
        )
        // State settled: not loading, no error.
        assertEquals(false, viewModel.searchState.value.loading)
        assertEquals(null, viewModel.searchState.value.error)
    }

    @Test
    fun focusStaysInTheFieldWhileEditingAndAfterSubmitting() {
        val (viewModel, _) = setupScreen()

        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.onNodeWithTag("search_field").assertIsFocused()

        composeRule.onNodeWithTag("search_field").performTextInput("naruto")
        composeRule.onNodeWithTag("search_field").assertIsFocused()

        composeRule.onNodeWithTag("search_field").performImeAction()
        composeRule.waitUntil(5_000) { viewModel.searchState.value.results.isNotEmpty() }

        // §14.1 "focus remains in the field" — submission never steals focus.
        composeRule.onNodeWithTag("search_field").assertIsFocused()
    }

    @Test
    fun typedQueryExecutesThroughTheDebounceWithoutAnySubmission() {
        val (viewModel, client) = setupScreen(debounceMs = TEST_DEBOUNCE_MS)

        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.onNodeWithTag("search_field").performTextInput("bleach")
        val typedAt = SystemClock.elapsedRealtime()

        // Contract: typing alone must NOT submit immediately — no IME action is
        // performed in this test, so the scheduled debounce (TEST_DEBOUNCE_MS
        // away) is the only trigger; any request recorded here would mean the
        // typing path submitted on its own.
        composeRule.waitForIdle()
        assertTrue("typing must not submit immediately", client.requestedAtMillis.isEmpty())

        // NO IME action — the plain typing path must fire by itself through the
        // debounce. The window covers the INJECTED TEST_DEBOUNCE_MS (+ margin),
        // so a pathological run reports an honest failure, not a timeout.
        composeRule.waitUntil(15_000) { viewModel.searchState.value.results.isNotEmpty() }

        assertEquals("exactly one debounced request", 1, client.requestedAtMillis.size)

        // Debounce-path discriminator: the request waited out at least the
        // production window. Under the injected 10 s debounce the measured
        // value sits ~9.65 s above this bound, so the ±ms harness timestamp
        // jitter (typedAt vs. text-commit ordering) that broke the old
        // boundary measurement (observed 344 ms) cannot reach it. The exact
        // 350 ms clock contract is HomeViewModelTest's fake-clock job.
        val elapsed = client.requestedAtMillis.first() - typedAt
        assertTrue(
            "typed input waits out the ${HomeViewModel.DEBOUNCE_MS}ms debounce (elapsed ${elapsed}ms)",
            elapsed >= HomeViewModel.DEBOUNCE_MS,
        )

        // Resulting state is correct for the typed query.
        val state = viewModel.searchState.value
        assertEquals("bleach", state.query)
        assertEquals(false, state.loading)
        assertEquals(null, state.error)
        assertEquals(listOf("Naruto Shippuden", "Bleach"), state.results.map { it.title })
    }

    @Test
    fun tappingAResultClearsFocusAndExposesThePosterDescription() {
        val (viewModel, _) = setupScreen()

        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.onNodeWithTag("search_field").performTextInput("naruto")
        composeRule.onNodeWithTag("search_field").performImeAction()
        composeRule.waitUntil(5_000) { viewModel.searchState.value.results.isNotEmpty() }

        // §21.1: the poster slot announces real data.
        composeRule
            .onNodeWithContentDescription("Naruto Shippuden poster")
            .assertExists()

        // §14.1: tapping a result clears focus (keyboard dismissal is the
        // platform's consequence of losing focus; window-level IME visibility
        // for the navigation case is asserted in NavigationTest).
        composeRule.onNodeWithText("Naruto Shippuden", substring = true).performClick()
        composeRule.onNodeWithTag("search_field").assertIsNotFocused()
    }

    // -----------------------------------------------------------------------
    // 11.5E-2 — the tapped row opens the anime details destination
    // -----------------------------------------------------------------------

    @Test
    fun tappingAResultHandsItsSessionToTheAnimeDetailsDestination() {
        val openedSessions = mutableListOf<String>()
        val (viewModel, _) = setupScreen(onOpenDetails = openedSessions::add)

        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.onNodeWithTag("search_field").performTextInput("naruto")
        composeRule.onNodeWithTag("search_field").performImeAction()
        composeRule.waitUntil(5_000) { viewModel.searchState.value.results.isNotEmpty() }

        composeRule.onNodeWithText("Naruto Shippuden", substring = true).performClick()

        // The destination argument is the row's OWN session from the parsed
        // response (s1) — never a title/year reconstruction, never another row's.
        assertEquals(
            "the tapped result opens AnimeDetailsRoute with its session",
            listOf("s1"),
            openedSessions,
        )
    }

    companion object {
        /**
         * Injected debounce for the two timing tests ONLY (IME wiring +
         * typed-query) — production remains [HomeViewModel.DEBOUNCE_MS]
         * (350 ms, pinned by HomeViewModelTest's fake-clock tests). 10 s sits
         * far above both harness hazards: the worst observed IME-dispatch
         * latency (~0.4 s) and the ±ms typedAt/text-commit timestamp jitter,
         * so both ordering contracts hold deterministically.
         */
        private const val TEST_DEBOUNCE_MS = 10_000L

        private const val SEARCH_RESPONSE =
            """{"data":""" +
                """[{"session":"s1","title":"Naruto Shippuden","type":"TV","year":2007,"episodes":500,"score":"8.2"},""" +
                """{"session":"s2","title":"Bleach","type":"TV","year":2004,"episodes":366,"score":"8.0"}]}"""
    }
}
