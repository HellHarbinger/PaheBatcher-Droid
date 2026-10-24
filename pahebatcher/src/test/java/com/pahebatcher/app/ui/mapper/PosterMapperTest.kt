package com.pahebatcher.app.ui.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §36-12 — Poster four-state mapper test (PHASE11_ARCHITECTURE_V2 §15, F-15):
 * all four states representable and transition-correct; no state can synthesize
 * a URL.
 */
class PosterMapperTest {

    @Test
    fun allFourStates_areRepresentable() {
        assertTrue(PosterMapper.map("https://cdn.example/p.jpg", "Title", PosterLoadStatus.LOADED) is PosterUiState.Available)
        assertTrue(PosterMapper.map("https://cdn.example/p.jpg", "Title", PosterLoadStatus.LOADING) is PosterUiState.Loading)
        assertTrue(PosterMapper.map("https://cdn.example/p.jpg", "Title", PosterLoadStatus.ERROR) is PosterUiState.Failed)
        assertTrue(PosterMapper.map(null, "Title", PosterLoadStatus.NONE) is PosterUiState.Placeholder)
        assertTrue(PosterMapper.placeholderFor("Title") is PosterUiState.Placeholder)
    }

    @Test
    fun blankOrNullUrl_alwaysPlaceholder_state4Dominates() {
        // §15 rule 2: until a source is verified, state 4 everywhere — a slot with
        // no verified URL can never become states 1/2/3 regardless of load status.
        listOf(PosterLoadStatus.NONE, PosterLoadStatus.LOADING, PosterLoadStatus.LOADED, PosterLoadStatus.ERROR)
            .forEach { status ->
                assertTrue(PosterMapper.map(null, "Title", status) is PosterUiState.Placeholder)
                assertTrue(PosterMapper.map("", "Title", status) is PosterUiState.Placeholder)
                assertTrue(PosterMapper.map("   ", "Title", status) is PosterUiState.Placeholder)
            }
    }

    @Test
    fun transitions_followLoadStatus_forVerifiedUrl() {
        val url = "https://cdn.example/verified.jpg"
        // NONE → loading (a verified URL that has not been requested yet)
        assertTrue(PosterMapper.map(url, "T", PosterLoadStatus.NONE) is PosterUiState.Loading)
        assertTrue(PosterMapper.map(url, "T", PosterLoadStatus.LOADING) is PosterUiState.Loading)
        // success → available
        val available = PosterMapper.map(url, "T", PosterLoadStatus.LOADED)
        assertTrue(available is PosterUiState.Available)
        assertEquals(url, (available as PosterUiState.Available).posterUrl)
        // failure → failed (no crash, no infinite spinner — honest failed state)
        val failed = PosterMapper.map(url, "T", PosterLoadStatus.ERROR)
        assertTrue(failed is PosterUiState.Failed)
        assertEquals(url, (failed as PosterUiState.Failed).posterUrl)
    }

    @Test
    fun noState_canSynthesizeAUrl_passThroughIsByteForByte() {
        // §15 rule 1: posters only from real parsed data — the mapper never
        // invents, constructs, or guesses poster URLs (no session-derived paths,
        // no placeholder domains, no URL-pattern guessing).
        val parsedUrl = "https://cdn.example/abc123.jpg?sig=real"
        listOf(PosterLoadStatus.NONE, PosterLoadStatus.LOADING, PosterLoadStatus.LOADED, PosterLoadStatus.ERROR)
            .forEach { status ->
                val state = PosterMapper.map(parsedUrl, "Some Title", status)
                val url = when (state) {
                    is PosterUiState.Available -> state.posterUrl
                    is PosterUiState.Loading -> state.posterUrl
                    is PosterUiState.Failed -> state.posterUrl
                    is PosterUiState.Placeholder -> null
                }
                url?.let { assertEquals("URL must pass through unchanged", parsedUrl, it) }
            }
    }

    @Test
    fun placeholder_carriesOnlyTitle_neverAUrl() {
        val placeholder = PosterMapper.placeholderFor("Attack on Titan")
        assertEquals("Attack on Titan", (placeholder as PosterUiState.Placeholder).title)
        // The placeholder data class has no URL surface at all — assert the shape
        // by ensuring the instance model of the placeholder contains no URL-ish
        // fields beyond `title` (ignoring compiler-generated static members such
        // as the Compose stability marker).
        val fields = placeholder::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertEquals(listOf("title"), fields)
        assertFalse(placeholder.toString().contains("http"))
    }
}
