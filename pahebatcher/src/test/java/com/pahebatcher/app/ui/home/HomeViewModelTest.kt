package com.pahebatcher.app.ui.home

import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §36-6 — Home search foundation (PHASE11_ARCHITECTURE_V2 §14, §14.1, §35):
 *
 * - Debounce lives inside the mandated 300–400 ms band (350 ms) and the search
 *   does NOT fire before the window elapses (virtual-clock verified).
 * - Exactly ONE in-flight job: a newer query cancels both a still-debouncing
 *   pending job AND a suspended repository call; the superseded call can never
 *   clobber the newer query's state (CancellationException rethrown, §14).
 * - IME submit flushes the current query IMMEDIATELY through the same
 *   [HomeViewModel.submitSearch] path and never double-fires (§14.1).
 * - Blank input never reaches the repository.
 * - All-hosts-unreachable → honest error branch (Retry = same real call);
 *   reachable host with zero matches → NOT an error ("No results found." path).
 *
 * All repository traffic is FakeHttpClient/ScriptedHttpClient — no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    /** Pattern matching every search host so the FIRST host always answers → one request. */
    private val anyHost = "animepahe"

    private lateinit var httpClient: FakeHttpClient
    private lateinit var repository: AnimeRepository

    private fun newRepository(client: HttpClient = FakeHttpClient().also { httpClient = it }): AnimeRepository =
        AnimeRepository(client)

    private fun rowJson(title: String): String = """
        {"data": [{"session": "sess-$title", "title": "$title", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"}]}
    """.trimIndent()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Share runTest's virtual clock with Dispatchers.Main (viewModelScope dispatcher). */
    private fun kotlinx.coroutines.test.TestScope.givenVirtualMain() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    }

    // -----------------------------------------------------------------------
    // Debounce window
    // -----------------------------------------------------------------------

    @Test
    fun `debounce constant is inside the mandated 300-400ms band`() {
        assertEquals(350L, HomeViewModel.DEBOUNCE_MS)
        assertTrue(HomeViewModel.DEBOUNCE_MS in 300L..400L)
    }

    @Test
    fun `typed query does not fire before the debounce window and fires once after`() = runTest {
        givenVirtualMain()
        val repo = newRepository()
        httpClient.stubJson(anyHost, rowJson("Alpha"))
        val vm = HomeViewModel(repo)

        vm.onQueryChange("alpha")
        testScheduler.runCurrent()
        assertEquals("no request at t=0", 0, httpClient.requestedUrls.size)
        assertTrue("loading covers the debounce window", vm.searchState.value.loading)

        testScheduler.advanceTimeBy(349)
        assertEquals("still inside the 350ms window", 0, httpClient.requestedUrls.size)

        testScheduler.advanceTimeBy(10)
        testScheduler.runCurrent()
        assertEquals("exactly one debounced request", 1, httpClient.requestedUrls.size)

        val state = vm.searchState.value
        assertEquals("alpha", state.query)
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(listOf("Alpha"), state.results.map { it.title })
    }

    @Test
    fun `typing a new query cancels the still-pending debounce`() = runTest {
        givenVirtualMain()
        val repo = newRepository()
        httpClient.stubJson(anyHost, rowJson("Beta"))
        val vm = HomeViewModel(repo)

        vm.onQueryChange("alpha")
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(100) // inside alpha's window

        vm.onQueryChange("beta")
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        assertEquals("only the newest query hit the network", 1, httpClient.requestedUrls.size)
        assertTrue(httpClient.requestedUrls[0].contains("q=beta"))
        assertEquals("beta", vm.searchState.value.query)
        assertEquals(listOf("Beta"), vm.searchState.value.results.map { it.title })
    }

    // -----------------------------------------------------------------------
    // Single in-flight — superseded suspended call cannot clobber state
    // -----------------------------------------------------------------------

    @Test
    fun `superseded in-flight search is cancelled and never clobbers the newer query`() = runTest {
        givenVirtualMain()
        val slow = ScriptedHttpClient { url ->
            if (url.contains("q=alpha")) {
                delay(10_000) // hangs longer than the test — only cancellation ends it
                rowJson("Alpha Result")
            } else {
                rowJson("Beta Result")
            }
        }
        val vm = HomeViewModel(AnimeRepository(slow))

        vm.onQueryChange("alpha")
        testScheduler.advanceTimeBy(400) // debounce fires; alpha call suspends in delay(10s)
        testScheduler.runCurrent()
        assertEquals("alpha request in flight", 1, slow.requested.size)

        vm.onQueryChange("beta") // cancels the suspended call (§14 single in-flight)
        testScheduler.advanceTimeBy(400) // beta's debounce fires
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle() // must complete WITHOUT alpha's 10s delay elapsing

        val state = vm.searchState.value
        assertEquals("beta", state.query)
        assertEquals(listOf("Beta Result"), state.results.map { it.title })
        assertFalse("superseded job left no loading spinner", state.loading)
        assertNull("cancellation must not surface an error branch", state.error)
        assertEquals("alpha attempted exactly once", 1, slow.requested.count { it.contains("q=alpha") })
    }

    // -----------------------------------------------------------------------
    // §14.1 IME submit = same path, immediate flush, no double fire
    // -----------------------------------------------------------------------

    @Test
    fun `submitSearch flushes immediately through the same path without waiting for debounce`() = runTest {
        givenVirtualMain()
        val repo = newRepository()
        httpClient.stubJson(anyHost, rowJson("Flushed"))
        val vm = HomeViewModel(repo)

        vm.onQueryChange("flushed")
        testScheduler.runCurrent()
        assertEquals("debounce still pending", 0, httpClient.requestedUrls.size)

        vm.submitSearch()
        testScheduler.runCurrent()
        assertEquals("IME submit executed without the 350ms wait", 1, httpClient.requestedUrls.size)

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals("cancelled debounce never double-fires", 1, httpClient.requestedUrls.size)

        val state = vm.searchState.value
        assertNull(state.error)
        assertEquals(listOf("Flushed"), state.results.map { it.title })
    }

    // -----------------------------------------------------------------------
    // Blank input
    // -----------------------------------------------------------------------

    @Test
    fun `blank input clears state and never calls the repository`() = runTest {
        givenVirtualMain()
        val repo = newRepository()
        httpClient.stubJson(anyHost, rowJson("Never Shown"))
        val vm = HomeViewModel(repo)

        vm.onQueryChange("   ")
        testScheduler.advanceUntilIdle()
        assertTrue("typing blanks performs no request", httpClient.requestedUrls.isEmpty())

        vm.submitSearch()
        testScheduler.advanceUntilIdle()
        assertTrue("submitting blanks performs no request", httpClient.requestedUrls.isEmpty())

        val state = vm.searchState.value
        assertEquals("   ", state.query)
        assertFalse(state.loading)
        assertTrue(state.results.isEmpty())
        assertNull(state.error)
    }

    // -----------------------------------------------------------------------
    // §35 branches: Unreachable vs reachable-empty
    // -----------------------------------------------------------------------

    @Test
    fun `all hosts unreachable surfaces the error branch and Retry recovers through the same call`() = runTest {
        givenVirtualMain()
        val repo = newRepository() // no stubs → every host returns null
        val vm = HomeViewModel(repo)

        vm.onQueryChange("alpha")
        testScheduler.advanceUntilIdle()

        var state = vm.searchState.value
        assertEquals(HomeViewModel.ERROR_UNREACHABLE, state.error)
        assertTrue(state.results.isEmpty())
        assertFalse(state.loading)

        // Retry (bound to the SAME real repository call) after connectivity returns
        httpClient.stubJson(anyHost, rowJson("Recovered"))
        vm.submitSearch()
        testScheduler.advanceUntilIdle()

        state = vm.searchState.value
        assertNull("successful retry clears the error", state.error)
        assertEquals(listOf("Recovered"), state.results.map { it.title })
        assertFalse(state.loading)
    }

    @Test
    fun `reachable host with zero matches is an empty result set not an error`() = runTest {
        givenVirtualMain()
        val repo = newRepository()
        httpClient.stubJson(anyHost, """{"data": []}""") // host answers, nothing matched
        val vm = HomeViewModel(repo)

        vm.onQueryChange("nothing-matches")
        testScheduler.advanceUntilIdle()

        val state = vm.searchState.value
        assertNull("reachable zero results is NOT an error", state.error)
        assertTrue(state.results.isEmpty())
        assertFalse(state.loading)
        assertEquals("nothing-matches", state.query)
    }

    /** Minimal scripted client: per-URL responses, optional in-call delay. */
    private class ScriptedHttpClient(
        private val respond: suspend (String) -> String?,
    ) : HttpClient {
        val requested = mutableListOf<String>()

        override suspend fun fetchJson(url: String): String? {
            requested.add(url)
            return respond(url)
        }

        override suspend fun fetchHtml(url: String): String? = respond(url)
    }
}
