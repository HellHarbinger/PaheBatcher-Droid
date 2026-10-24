package com.pahebatcher.app.domain.model

/**
 * A single resolution option parsed from the AnimePahe play page.
 *
 * Ported from Python kwik.py _parse_resolution_buttons() return type, with the
 * audio component GENERALIZED by the approved audio-variant architecture: the
 * reference carries a closed `is_dub` boolean (kwik.py:201-210), while this
 * port preserves the raw `data-audio` value the source actually exposes so any
 * audio string (`jpn`, `eng`, `kor`, …) survives parsing → selection →
 * resolution → routing untouched (SPEC §22 "Audio selection (per episode)").
 *
 * @param resolution Vertical resolution (e.g., 1080, 720, 360)
 * @param kwikUrl The Kwik URL for this resolution
 * @param audio The audio variant this entry offers — the source's own raw
 *   `data-audio` value (lowercased/trimmed), or the narrow label heuristic
 *   documented in [com.pahebatcher.app.data.remote.parser.PlayPageParser] when
 *   the source carried no `data-audio`. Never a forced default.
 * @param fansub Fansub group name (descriptive metadata, NOT part of the
 *   source identity)
 */
data class ResolutionEntry(
    val resolution: Int,
    val kwikUrl: String,
    val audio: String,
    val fansub: String
)
