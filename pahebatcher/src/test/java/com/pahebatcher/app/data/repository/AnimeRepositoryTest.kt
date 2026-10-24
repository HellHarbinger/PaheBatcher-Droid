package com.pahebatcher.app.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnimeRepositoryTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var repository: AnimeRepository

    @Before
    fun setup() {
        httpClient = FakeHttpClient()
        repository = AnimeRepository(httpClient)
    }

    @Test
    fun `search returns results from successful host`() = runBlocking {
        val searchResponse = """
            {
                "data": [
                    {"session": "s1", "title": "Test Anime", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"}
                ]
            }
        """.trimIndent()

        httpClient.stubJson("animepahe.com", searchResponse)

        val results = repository.search("animepahe.com", "Test")
        assertEquals(1, results.size)
        assertEquals("s1", results[0]["session"])
    }

    @Test
    fun `search returns empty list when all hosts fail`() = runBlocking {
        // No stubs configured - all hosts return null

        val results = repository.search("animepahe.com", "Test")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search tries multiple hosts on failure`() = runBlocking {
        val searchResponse = """
            {"data": [{"session": "s1", "title": "Test"}]}
        """.trimIndent()

        // First host fails
        httpClient.stubJson("animepahe.com", null)
        // Second host succeeds
        httpClient.stubJson("animepahe.pw", searchResponse)

        val results = repository.search("animepahe.com", "Test")
        assertEquals(1, results.size)
    }

    @Test
    fun `fetchTitle returns extracted title`() = runBlocking {
        val html = "<html><body><h1>Test Anime Title</h1></body></html>"
        httpClient.stubHtml("/anime/", html)

        val title = repository.fetchTitle("animepahe.com", "session1")
        assertEquals("Test Anime Title", title)
    }

    @Test
    fun `fetchTitle returns Unknown Anime on failure`() = runBlocking {
        // No stubs configured

        val title = repository.fetchTitle("animepahe.com", "session1")
        assertEquals("Unknown Anime", title)
    }

    @Test
    fun `fetchEpisodePage returns primary endpoint data`() = runBlocking {
        val pageData = """{"data": [], "last_page": 1}"""
        httpClient.stubJson("/api?m=release", pageData)

        val result = repository.fetchEpisodePage("animepahe.com", "session1", 1)
        assertEquals(pageData, result)
    }

    @Test
    fun `fetchEpisodePage falls back to alternative endpoint`() = runBlocking {
        val pageData = """{"data": [], "last_page": 1}"""
        // Primary endpoint fails
        httpClient.stubJson("/api?m=release", null)
        // Fallback endpoint succeeds
        httpClient.stubJson("/releases", pageData)

        val result = repository.fetchEpisodePage("animepahe.com", "session1", 1)
        assertEquals(pageData, result)
    }

    @Test
    fun `fetchEpisodePage returns null on failure`() = runBlocking {
        // No stubs configured

        val result = repository.fetchEpisodePage("animepahe.com", "session1", 1)
        assertEquals(null, result)
    }

    @Test
    fun `search encodes query properly`() = runBlocking {
        val searchResponse = """{"data": []}"""
        httpClient.stubJson("animepahe.com", searchResponse)

        repository.search("animepahe.com", "Test Query")

        assertTrue(httpClient.requestedUrls.any { it.contains("q=Test+Query") })
    }
}
