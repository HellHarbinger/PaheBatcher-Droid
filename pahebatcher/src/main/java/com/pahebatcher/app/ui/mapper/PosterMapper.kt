package com.pahebatcher.app.ui.mapper

import androidx.compose.runtime.Immutable

/**
 * Poster four-state contract (PHASE11_ARCHITECTURE_V2 §15, F-15).
 *
 * Every poster slot in the UI is exactly one of four states, modeled explicitly
 * in UiState (sealed subtype + payload — not inferred ad hoc in composables) so
 * §36-12 can test all four transitions.
 *
 * Rules:
 * 1. Posters only from real parsed data — this mapper NEVER constructs, guesses,
 *    or derives a URL (no session-derived paths, no placeholder domains). An
 *    Available/Loading/Failed state can only carry a URL its caller already has
 *    from a verified, parsed source.
 * 2. Until a poster source is verified (EXTERNAL BLOCKER — no poster field is
 *    parsed today), [PosterUiState.Placeholder] (state 4) is the state for ALL
 *    poster slots in Phase 11 — honest empty-state design, not fake content.
 * 3. No image library in Phase 11 (Coil/Glide rejected, §32) — states 2/3 exist
 *    in the model so a future loader must implement all four (§15 rule 3).
 */
@Immutable
sealed interface PosterUiState {

    /** State 1: real poster available — actual image from a verified, parsed URL. */
    data class Available(val posterUrl: String) : PosterUiState

    /** State 2: poster loading — a verified URL has been requested by a future loader. */
    data class Loading(val posterUrl: String) : PosterUiState

    /** State 3: poster failed — load error for a verified URL. */
    data class Failed(val posterUrl: String) : PosterUiState

    /**
     * State 4: vector/title-initial placeholder — **the state for ALL poster slots
     * in Phase 11** (no verified source exists). Carries only the real title; it
     * cannot hold a URL by construction.
     */
    data class Placeholder(val title: String) : PosterUiState
}

/** Load phase of a verified poster URL (future loader states 1–3). */
enum class PosterLoadStatus {
    /** A verified URL exists but has not been requested yet → treated as [LOADING]. */
    NONE,
    LOADING,
    LOADED,
    ERROR,
}

object PosterMapper {

    /**
     * Pure mapping: [posterUrl] + [status] → one of the four states.
     *
     * - Blank/null URL → [PosterUiState.Placeholder] regardless of status — a slot
     *   with no verified source can never become states 1/2/3 (Phase 11 reality:
     *   no poster field is parsed yet, so this branch is what production renders).
     * - The URL passes through byte-for-byte: this function never edits, joins,
     *   prefixes, or otherwise synthesizes a URL (§15 rule 1).
     */
    fun map(posterUrl: String?, title: String, status: PosterLoadStatus): PosterUiState {
        val url = posterUrl?.takeIf { it.isNotBlank() }
            ?: return PosterUiState.Placeholder(title)
        return when (status) {
            PosterLoadStatus.NONE,
            PosterLoadStatus.LOADING,
            -> PosterUiState.Loading(url)

            PosterLoadStatus.LOADED -> PosterUiState.Available(url)
            PosterLoadStatus.ERROR -> PosterUiState.Failed(url)
        }
    }

    /**
     * Phase 11 production entry point: **state 4 everywhere** — no poster field is
     * parsed (§15), so every slot is a title-initial placeholder until a source is
     * verified end to end (URL in a real API response + parser whitelist extension
     * + round-trip test, §15 rule 1).
     */
    fun placeholderFor(title: String): PosterUiState = PosterUiState.Placeholder(title)
}
