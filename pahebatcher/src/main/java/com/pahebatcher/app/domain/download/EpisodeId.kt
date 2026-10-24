package com.pahebatcher.app.domain.download

import com.pahebatcher.app.domain.model.EpisodeInfo

/**
 * The ONE shared implementation of the episode download/playlist key.
 *
 * The format is the long-standing `{animeSession}_{epStr}_{audio}` string that
 * [com.pahebatcher.app.data.download.DownloadRepository] has derived since
 * Phase 10 (the same key Python groups its segment directories by: `session` +
 * `Ep_<num>_<AUDIO>`). Nothing about the format changes in Phase 17 — the only
 * change is that the construction now lives in one place instead of being
 * duplicated, because streaming navigation needs to derive the SAME id from the
 * details screen without reaching into the download repository.
 *
 * Shape rules (the "shape gate" [parse] enforces):
 *
 * * exactly three `_`-separated parts;
 * * every part non-blank;
 * * the middle part is an episode string — a decimal number such as `1`, `13`
 *   or `13.5` ([EpisodeInfo.epStr] never emits anything else);
 * * the last part is an audio code matching `^[a-z0-9]{1,16}$` — `jpn` and
 *   `eng` for every row downloaded so far, and equally `kor` or any other
 *   lowercase open-set code the source may expose (audio-variant slice, SPEC
 *   §22). The gate stays CLOSED in shape (no punctuation, whitespace, path or
 *   separator characters can pass into a scan target) while no longer being
 *   closed in vocabulary.
 *
 * A malformed id is REJECTED instead of being partially trusted: the streaming
 * branch uses [parse] to decide whether it may reach the network at all, so a
 * garbage route argument must fail locally (AGENTS §10 — avoid unnecessary
 * requests) rather than trigger a scan of an unknown session.
 */
object EpisodeId {

    /** Episode-string shape: integer or one-decimal number, exactly as `epStr` emits. */
    private val EPISODE_STRING = Regex("""\d+(?:\.\d+)?""")

    /**
     * Audio code shape: 1-16 lowercase letters/digits — the open-set audio
     * identity the audio-variant slice uses (`jpn`, `eng`, `kor`, …). Anything
     * outside that shape (whitespace, `_`, `.`, `/`, uppercase, or an
     * over-long token such as a pasted sentence) is malformed and rejected
     * rather than forwarded into a network scan.
     */
    private val AUDIO_COMPONENT = Regex("""[a-z0-9]{1,16}""")

    /**
     * Stable id of ONE episode row: `<anime session>_<episode>_<audio>`.
     *
     * Unique per (anime, episode number, audio), so the SUB and DUB track of one
     * episode stay separate downloads and a second call for the same episode
     * finds the existing row instead of duplicating it.
     */
    fun build(animeSession: String, episode: EpisodeInfo): String =
        "${animeSession}_${episode.epStr}_${episode.audio}"

    /**
     * The three components of a well-formed id, or `null` when [id] does not
     * satisfy the shape gate above.
     */
    data class Parts(
        val animeSession: String,
        val episodeString: String,
        val audio: String,
    )

    /**
     * Split [id] back into its components, returning `null` for anything that is
     * not exactly `{session}_{epStr}_{audio}`.
     *
     * Extra guards beyond the three-part shape: no part may contain whitespace
     * (a stray sentence or a URL fragment must never be mistaken for a session),
     * and the audio must match `^[a-z0-9]{1,16}$`. Every one of these is a
     * decision to FAIL LOCALLY rather than reach the network with an
     * id we do not fully understand.
     */
    fun parse(id: String): Parts? {
        val parts = id.split('_')
        if (parts.size != 3) return null
        val (session, episodeString, audio) = parts
        if (parts.any { it.isBlank() }) return null
        if (parts.any { part -> part.any { it.isWhitespace() } }) return null
        if (!EPISODE_STRING.matches(episodeString)) return null
        if (!AUDIO_COMPONENT.matches(audio)) return null
        return Parts(animeSession = session, episodeString = episodeString, audio = audio)
    }
}
