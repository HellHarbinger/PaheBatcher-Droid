package com.pahebatcher.app.ui.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §36 — Search row mapping (PHASE11_ARCHITECTURE_V2 §14, §15):
 *
 * - Whitelist matches `AnimePaheParser.parseSearchResults` exactly.
 * - Parser defaults (`0` / `""`) map to `null` — the UI never renders a fake
 *   `0` year or empty label.
 * - Rows without a renderable session/title are dropped (never navigable/renderable).
 * - Poster is ALWAYS the state-4 Placeholder carrying the real title (§15 rule 2:
 *   no poster source is verified, no URL may exist in Phase 11).
 * - Order is preserved.
 */
class SearchResultMapperTest {

    private fun row(
        session: Any? = "sess-1",
        title: Any? = "Alpha",
        type: Any? = "TV",
        year: Any? = 2024,
        episodes: Any? = 12,
        score: Any? = "8.5",
    ): Map<String, Any> = buildMap {
        session?.let { put("session", it) }
        title?.let { put("title", it) }
        type?.let { put("type", it) }
        year?.let { put("year", it) }
        episodes?.let { put("episodes", it) }
        score?.let { put("score", it) }
    }

    @Test
    fun `full row maps every parsed field through the whitelist`() {
        val ui = SearchResultMapper.map(row())!!

        assertEquals("sess-1", ui.session)
        assertEquals("Alpha", ui.title)
        assertEquals("TV", ui.type)
        assertEquals(2024, ui.year)
        assertEquals(12, ui.episodes)
        assertEquals("8.5", ui.score)
    }

    @Test
    fun `parser default values map to null instead of fake zeros`() {
        val ui = SearchResultMapper.map(
            row(type = "", year = 0, episodes = 0, score = ""),
        )!!

        assertNull("blank type", ui.type)
        assertNull("zero year must not render as 0", ui.year)
        assertNull("zero episodes must not render as 0", ui.episodes)
        assertNull("blank score", ui.score)
        // Required fields still present
        assertEquals("sess-1", ui.session)
        assertEquals("Alpha", ui.title)
    }

    @Test
    fun `poster is always the state-4 placeholder carrying the real title`() {
        val ui = SearchResultMapper.map(row(title = "Poster Title"))!!

        val poster = ui.poster
        assertTrue("Phase 11 renders state 4 everywhere", poster is PosterUiState.Placeholder)
        assertEquals("Poster Title", (poster as PosterUiState.Placeholder).title)
    }

    @Test
    fun `rows without session or title are dropped by map`() {
        assertNull(SearchResultMapper.map(row(session = null)))
        assertNull(SearchResultMapper.map(row(session = "")))
        assertNull(SearchResultMapper.map(row(title = null)))
        assertNull(SearchResultMapper.map(row(title = "   ")))
    }

    @Test
    fun `mapAll drops unrenderable rows and preserves order`() {
        val raw = listOf(
            row(session = "s1", title = "First"),
            row(session = null, title = "No Session"),
            row(session = "s3", title = "Third"),
            row(session = "s4", title = ""),
        )

        val mapped = SearchResultMapper.mapAll(raw)

        assertEquals(listOf("First", "Third"), mapped.map { it.title })
        assertEquals(listOf("s1", "s3"), mapped.map { it.session })
    }

    @Test
    fun `mapAll of an empty list is empty`() {
        assertTrue(SearchResultMapper.mapAll(emptyList()).isEmpty())
    }
}
