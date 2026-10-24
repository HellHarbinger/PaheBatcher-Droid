package com.pahebatcher.app.ui.mapper

import androidx.compose.runtime.Immutable

/**
 * Pure mapper: parser search result → immutable search-row UI model
 * (PHASE11_ARCHITECTURE_V2 §14, §15).
 *
 * Whitelist matches `AnimePaheParser.parseSearchResults` exactly — the fields the
 * Python TUI renders (session / title / type / year / episodes / score). Nothing is
 * invented:
 *
 * - Optional fields that the parser fills with `0` / `""` defaults map to `null`
 *   so the composable renders the field or NOTHING (never a fake `0` year or an
 *   empty label).
 * - Rows without a non-blank `session` or `title` are dropped — neither can be
 *   rendered or (Phase 13) navigated honestly.
 * - [SearchResultUi.poster] is always [PosterUiState.Placeholder] (state 4, §15):
 *   no poster field is parsed today and this mapper never constructs a URL.
 *
 * Lives outside composables so §36 tests exercise it directly (§13).
 */
@Immutable
data class SearchResultUi(
    /** Anime session UUID — the ID-only navigation argument for Phase 13 details. */
    val session: String,
    val title: String,
    /** `null` when the parser produced a blank value. */
    val type: String?,
    /** `null` when the parser produced `0`. */
    val year: Int?,
    /** `null` when the parser produced `0`. */
    val episodes: Int?,
    /** `null` when the parser produced a blank value. */
    val score: String?,
    /** Always [PosterUiState.Placeholder] in Phase 11 (§15 rule 2 — state 4 everywhere). */
    val poster: PosterUiState,
)

object SearchResultMapper {

    /** Map one parser row; returns `null` when the row cannot be rendered honestly. */
    fun map(raw: Map<String, Any>): SearchResultUi? {
        val session = (raw["session"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val title = (raw["title"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return SearchResultUi(
            session = session,
            title = title,
            type = (raw["type"] as? String)?.takeIf { it.isNotBlank() },
            year = (raw["year"] as? Int)?.takeIf { it > 0 },
            episodes = (raw["episodes"] as? Int)?.takeIf { it > 0 },
            score = (raw["score"] as? String)?.takeIf { it.isNotBlank() },
            poster = PosterMapper.placeholderFor(title),
        )
    }

    /** Map a result list, dropping unrenderable rows; order is preserved. */
    fun mapAll(raw: List<Map<String, Any>>): List<SearchResultUi> = raw.mapNotNull(::map)
}
