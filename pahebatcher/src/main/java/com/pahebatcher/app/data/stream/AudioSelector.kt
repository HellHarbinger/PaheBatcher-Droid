package com.pahebatcher.app.data.stream

/**
 * Audio-variant matching for AnimePahe streams — the open-set replacement for
 * the reference's closed `is_dub` bridge (kwik.py:228-232).
 *
 * Audio is an open-set string (`jpn`, `eng`, `kor`, …): selection is an
 * EXACT comparison of the requested value against the value the source
 * exposed. There is no boolean reduction, no closed enum, and no substitution
 * of one audio value for another (SPEC §22 "Audio selection (per episode)").
 */
object AudioSelector {

    /** Japanese audio (SUB) — the value AnimePahe's `data-audio` uses for the sub track. */
    const val AUDIO_JAPANESE = "jpn"

    /** English audio (DUB) — the value AnimePahe's `data-audio` uses for the dub track. */
    const val AUDIO_ENGLISH = "eng"

    /**
     * Exact audio match — the ONLY selection rule. Requested and available are
     * compared verbatim (after the parser's lowercase/trim normalization), so
     * a requested `eng` never matches a `jpn` source and vice versa, and an
     * arbitrary value such as `kor` behaves exactly like the built-ins.
     */
    fun matches(requested: String, available: String): Boolean = requested == available

    /**
     * The honest, shared failure text when the requested audio is not offered
     * by the source — used by single resolution (as a [com.pahebatcher.app.domain.stream.StreamResolutionException]
     * message) and by the batch loop (as a recorded per-episode failure
     * reason). It states the audio that was asked for and that it was NOT
     * silently replaced. The Watchlist check no longer pre-skips on audio
     * (DECISIONS 144.4): its attempt either resolves or lands in the FAILED
     * status with this same text.
     */
    fun unavailableMessage(requested: String): String =
        "Requested audio '$requested' is not available for this episode."
}
