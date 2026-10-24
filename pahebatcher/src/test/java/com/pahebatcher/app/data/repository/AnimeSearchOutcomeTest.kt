package com.pahebatcher.app.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §35 / §14 — `searchWithOutcome` distinguishes the two "empty" situations the
 * Home screen renders differently (PHASE11_ARCHITECTURE_V2 §14, §35):
 *
 * - reachable host + zero matches → [SearchOutcome.Results] with an empty list
 *   ("No results found." — no error/retry implied);
 * - every host failed → [SearchOutcome.Unreachable] (error branch + Retry).
 *
 * The original `search()` contract is untouched (its 9 tests stay green).
 */
class AnimeSearchOutcomeTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var repository: AnimeRepository

    @Before
    fun setup() {
        httpClient = FakeHttpClient()
        repository = AnimeRepository(httpClient)
    }

    private val rowJson = """
        {"data": [{"session": "s1", "title": "Test Anime", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"}]}
    """.trimIndent()

    @Test
    fun `successful host returns Results carrying the parsed rows`() = runBlocking {
        httpClient.stubJson("animepahe", rowJson)

        val outcome = repository.searchWithOutcome("animepahe.com", "Test")

        assertTrue(outcome is SearchOutcome.Results)
        val results = (outcome as SearchOutcome.Results).results
        assertEquals(1, results.size)
        assertEquals("s1", results[0]["session"])
        assertEquals("Test Anime", results[0]["title"])
    }

    @Test
    fun `reachable host with zero matches returns Results empty not Unreachable`() = runBlocking {
        httpClient.stubJson("animepahe", """{"data": []}""")

        val outcome = repository.searchWithOutcome("animepahe.com", "nothing")

        assertTrue(
            "a answered-but-empty search must not be reported as Unreachable",
            outcome is SearchOutcome.Results,
        )
        assertTrue((outcome as SearchOutcome.Results).results.isEmpty())
    }

    @Test
    fun `every host failing returns Unreachable`() = runBlocking {
        // No stubs configured — every host returns null (no response at all)

        val outcome = repository.searchWithOutcome("animepahe.com", "Test")

        assertEquals(SearchOutcome.Unreachable, outcome)
    }

    @Test
    fun `hosts are tried in order until one responds with rows`() = runBlocking {
        // First-listed host fails; a fallback host answers with a row
        httpClient.stubJson("animepahe.pw", rowJson)

        val outcome = repository.searchWithOutcome("animepahe.com", "Test")

        assertTrue(outcome is SearchOutcome.Results)
        assertEquals(1, (outcome as SearchOutcome.Results).results.size)
    }

    @Test
    fun `unusable response body yields Results empty without crashing`() = runBlocking {
        // A reachable host answering with non-JSON content (the Phase 11.5B defect
        // symptom) must preserve the answered-but-empty contract, not crash and not
        // read as Unreachable.
        httpClient.stubJson("animepahe", "<html><body>challenge</body></html>")

        val outcome = repository.searchWithOutcome("animepahe.com", "Test")

        assertTrue(
            "unusable content must not be reported as Unreachable",
            outcome is SearchOutcome.Results,
        )
        assertTrue((outcome as SearchOutcome.Results).results.isEmpty())
    }
}
