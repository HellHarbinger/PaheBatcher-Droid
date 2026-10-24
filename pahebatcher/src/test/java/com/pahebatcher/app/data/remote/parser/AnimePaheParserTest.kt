package com.pahebatcher.app.data.remote.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimePaheParserTest {

    // ========== parseAnimeUrl tests ==========

    @Test
    fun `parseAnimeUrl extracts host and session from valid URL`() {
        val (host, session) = AnimePaheParser.parseAnimeUrl(
            "https://animepahe.pw/anime/540562da-0708-2e0f-2178-01306c59b207"
        )
        assertEquals("animepahe.pw", host)
        assertEquals("540562da-0708-2e0f-2178-01306c59b207", session)
    }

    @Test
    fun `parseAnimeUrl handles different domain`() {
        val (host, _) = AnimePaheParser.parseAnimeUrl(
            "https://animepahe.com/anime/540562da-0708-2e0f-2178-01306c59b207"
        )
        assertEquals("animepahe.com", host)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseAnimeUrl rejects invalid domain`() {
        AnimePaheParser.parseAnimeUrl("https://google.com/test")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseAnimeUrl rejects invalid scheme`() {
        AnimePaheParser.parseAnimeUrl("ftp://animepahe.com/test")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseAnimeUrl rejects URL without UUID`() {
        AnimePaheParser.parseAnimeUrl("https://animepahe.com/anime/")
    }

    @Test
    fun `parseAnimeUrl handles trailing slash`() {
        val (host, session) = AnimePaheParser.parseAnimeUrl(
            "https://animepahe.pw/anime/540562da-0708-2e0f-2178-01306c59b207/"
        )
        assertEquals("animepahe.pw", host)
        assertEquals("540562da-0708-2e0f-2178-01306c59b207", session)
    }

    @Test
    fun `parseAnimeUrl handles org domain`() {
        val (host, _) = AnimePaheParser.parseAnimeUrl(
            "https://animepahe.org/anime/540562da-0708-2e0f-2178-01306c59b207"
        )
        assertEquals("animepahe.org", host)
    }

    // ========== parseSearchResults tests ==========

    @Test
    fun `parseSearchResults extracts results from valid JSON`() {
        val json = """
            {
                "data": [
                    {"session": "s1", "title": "Test Anime", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"},
                    {"session": "s2", "title": "Another Anime", "type": "TV", "year": 2023, "episodes": 24, "score": "7.5"}
                ]
            }
        """.trimIndent()

        val results = AnimePaheParser.parseSearchResults(json)
        assertEquals(2, results.size)
        assertEquals("s1", results[0]["session"])
        assertEquals("Test Anime", results[0]["title"])
        assertEquals("TV", results[0]["type"])
        assertEquals(2024, results[0]["year"])
        assertEquals(12, results[0]["episodes"])
    }

    @Test
    fun `parseSearchResults returns empty list for empty data`() {
        val json = """{"data": []}"""
        val results = AnimePaheParser.parseSearchResults(json)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseSearchResults returns empty list for missing data key`() {
        val json = """{"other": "data"}"""
        val results = AnimePaheParser.parseSearchResults(json)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseSearchResults returns empty list for invalid JSON`() {
        val results = AnimePaheParser.parseSearchResults("not json")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `a malformed year degrades to 0 without discarding the other rows`() {
        // NET-011: one row's `year` being a non-integer must not throw and thereby
        // collapse the WHOLE result list to empty — the unaffected rows still parse.
        val json = """
            {
                "data": [
                    {"session": "s1", "title": "Good", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"},
                    {"session": "s2", "title": "Bad year", "type": "TV", "year": "2023-2024", "episodes": 24, "score": "7.5"}
                ]
            }
        """.trimIndent()

        val results = AnimePaheParser.parseSearchResults(json)
        assertEquals("the malformed row must not discard the good one", 2, results.size)
        assertEquals(2024, results[0]["year"])
        assertEquals("the malformed year degrades to the 0 default", 0, results[1]["year"])
        assertEquals("Bad year", results[1]["title"])
    }

    // ========== parseEpisodePage tests ==========

    @Test
    fun `parseEpisodePage extracts episodes correctly`() {
        val json = """
            {
                "data": [
                    {"episode": 1, "session": "ep-sess-1", "title": "Test Episode", "fansub": "SubsPlease", "audio": "jpn"},
                    {"episode": 2, "session": "ep-sess-2", "title": "Test Episode DUB", "fansub": "Yameii", "audio": "eng"}
                ]
            }
        """.trimIndent()

        val episodes = AnimePaheParser.parseEpisodePage(json, "animepahe.com", "anime-session")
        assertEquals(2, episodes.size)

        assertEquals(1.0f, episodes[0].number)
        assertEquals("ep-sess-1", episodes[0].session)
        assertEquals("jpn", episodes[0].audio)
        assertEquals("https://animepahe.com/play/anime-session/ep-sess-1", episodes[0].playUrl)

        assertEquals(2.0f, episodes[1].number)
        assertEquals("eng", episodes[1].audio)
    }

    @Test
    fun `parseEpisodePage returns empty list for empty data`() {
        val json = """{"data": []}"""
        val episodes = AnimePaheParser.parseEpisodePage(json, "host", "session")
        assertTrue(episodes.isEmpty())
    }

    @Test
    fun `parseEpisodePage handles question mark title`() {
        val json = """
            {"data": [{"episode": 1, "session": "s1", "title": "?", "fansub": "", "audio": "jpn"}]}
        """.trimIndent()

        val episodes = AnimePaheParser.parseEpisodePage(json, "host", "session")
        assertEquals(1, episodes.size)
        assertEquals("", episodes[0].title)
    }

    @Test
    fun `parseEpisodePage detects dub from title`() {
        val json = """
            {"data": [{"episode": 1, "session": "s1", "title": "DUB Episode", "fansub": "", "audio": "jpn"}]}
        """.trimIndent()

        val episodes = AnimePaheParser.parseEpisodePage(json, "host", "session")
        assertEquals(1, episodes.size)
        assertEquals("eng", episodes[0].audio)
    }

    @Test
    fun `parseEpisodePage skips empty session`() {
        val json = """
            {"data": [{"episode": 1, "session": "", "title": "T", "fansub": "", "audio": "jpn"}]}
        """.trimIndent()

        val episodes = AnimePaheParser.parseEpisodePage(json, "host", "session")
        assertTrue(episodes.isEmpty())
    }

    @Test
    fun `parseEpisodePage handles fractional episode numbers`() {
        val json = """
            {"data": [{"episode": 13.5, "session": "s1", "title": "Special", "fansub": "", "audio": "jpn"}]}
        """.trimIndent()

        val episodes = AnimePaheParser.parseEpisodePage(json, "host", "session")
        assertEquals(1, episodes.size)
        assertEquals(13.5f, episodes[0].number)
    }

    // ========== extractTitle tests ==========

    @Test
    fun `extractTitle extracts from h1 tag`() {
        val html = """
            <html>
            <body>
                <h1>Test Anime Title</h1>
                <p>Some content</p>
            </body>
            </html>
        """.trimIndent()

        val title = AnimePaheParser.extractTitle(html)
        assertEquals("Test Anime Title", title)
    }

    @Test
    fun `extractTitle deduplicates doubled titles`() {
        val html = """
            <html>
            <body>
                <h1>Test TitleTest Title</h1>
            </body>
            </html>
        """.trimIndent()

        val title = AnimePaheParser.extractTitle(html)
        assertEquals("Test Title", title)
    }

    @Test
    fun `extractTitle falls back to title tag`() {
        val html = """
            <html>
            <head>
                <title>Watch Test Anime | AnimePahe</title>
            </head>
            <body>
                <p>No h1 tag here</p>
            </body>
            </html>
        """.trimIndent()

        val title = AnimePaheParser.extractTitle(html)
        assertEquals("Test Anime", title)
    }

    @Test
    fun `extractTitle returns Unknown Anime for empty HTML`() {
        val title = AnimePaheParser.extractTitle("")
        assertEquals("Unknown Anime", title)
    }

    @Test
    fun `extractTitle handles malformed HTML`() {
        val title = AnimePaheParser.extractTitle("<html><body>content</body>")
        assertNotNull(title)
    }

    // ========== extractAnimeTitle tests ==========

    @Test
    fun `extractAnimeTitle extracts title from API response`() {
        val json = """
            {
                "anime": {"title": "Test Anime"},
                "data": []
            }
        """.trimIndent()

        val title = AnimePaheParser.extractAnimeTitle(json)
        assertEquals("Test Anime", title)
    }

    @Test
    fun `extractAnimeTitle returns empty for missing anime data`() {
        val json = """{"data": []}"""
        val title = AnimePaheParser.extractAnimeTitle(json)
        assertEquals("", title)
    }

    @Test
    fun `extractAnimeTitle handles invalid JSON`() {
        val title = AnimePaheParser.extractAnimeTitle("not json")
        assertEquals("", title)
    }

    // ========== normalizeTitle tests ==========

    @Test
    fun `normalizeTitle lowercases and removes special chars`() {
        val normalized = AnimePaheParser.normalizeTitle("Test Anime: Season 1!")
        assertEquals("test anime season 1", normalized)
    }

    @Test
    fun `normalizeTitle collapses whitespace`() {
        val normalized = AnimePaheParser.normalizeTitle("Test   Anime   Title")
        assertEquals("test anime title", normalized)
    }

    @Test
    fun `normalizeTitle handles empty string`() {
        val normalized = AnimePaheParser.normalizeTitle("")
        assertEquals("", normalized)
    }

    // ========== classifyEpisodePage tests (scan-completion classification) ==========
    //
    // The scan may only be cached when it read EVERY required page, and that
    // decision is taken from this classification (`extract/scanner.py:267-287`).

    @Test
    fun `a page carrying rows keeps pagination going`() {
        val json = """
            {"data": [{"episode": 1, "session": "s1", "title": "Ep 1", "fansub": "", "audio": "jpn"}], "last_page": 3}
        """.trimIndent()

        assertEquals(EpisodePageKind.ROWS, AnimePaheParser.classifyEpisodePage(json))
    }

    @Test
    fun `an empty data array is a completed pagination, not a failure`() {
        assertEquals(
            "Python: `if not data.get(\"data\"): break` leaves completed_all True",
            EpisodePageKind.END_OF_PAGINATION,
            AnimePaheParser.classifyEpisodePage("""{"data": [], "last_page": 1}"""),
        )
    }

    @Test
    fun `a missing data key is a completed pagination`() {
        assertEquals(
            EpisodePageKind.END_OF_PAGINATION,
            AnimePaheParser.classifyEpisodePage("""{"anime": {"title": "Test"}, "last_page": 1}"""),
        )
    }

    @Test
    fun `a null data is a completed pagination`() {
        assertEquals(
            EpisodePageKind.END_OF_PAGINATION,
            AnimePaheParser.classifyEpisodePage("""{"data": null}"""),
        )
    }

    @Test
    fun `a page whose rows are all filtered out still carries data`() {
        // parseEpisodePage drops a blank session, so this page parses to ZERO
        // episodes - yet the response did carry rows, and Python keeps paginating
        // past it instead of ending the session here.
        val json = """{"data": [{"episode": 1, "session": "", "title": "Ep 1", "fansub": "", "audio": "jpn"}], "last_page": 2}"""

        assertEquals(EpisodePageKind.ROWS, AnimePaheParser.classifyEpisodePage(json))
        assertTrue(AnimePaheParser.parseEpisodePage(json, "host", "session").isEmpty())
    }

    @Test
    fun `an unparsable body is unreadable and can never complete a scan`() {
        assertEquals(
            EpisodePageKind.UNREADABLE,
            AnimePaheParser.classifyEpisodePage("<html><body>502 Bad Gateway</body>"),
        )
        assertEquals(EpisodePageKind.UNREADABLE, AnimePaheParser.classifyEpisodePage("not json"))
        assertEquals(EpisodePageKind.UNREADABLE, AnimePaheParser.classifyEpisodePage("""{"data": [1, 2"""))
    }

    @Test
    fun `a non-object root is unreadable`() {
        assertEquals(EpisodePageKind.UNREADABLE, AnimePaheParser.classifyEpisodePage("""[{"session": "s1"}]"""))
    }

    @Test
    fun `a data that is not an array is unreadable`() {
        assertEquals(EpisodePageKind.UNREADABLE, AnimePaheParser.classifyEpisodePage("""{"data": {"session": "s1"}}"""))
    }
}