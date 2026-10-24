package com.pahebatcher.app.domain.model

/**
 * Represents anime metadata with all associated episodes.
 *
 * Ported from Python models.py AnimeInfo dataclass.
 * Preserves all fields and behavior from the Python reference.
 */
data class AnimeInfo(
    /** Anime session UUID */
    val session: String,

    /** Anime title */
    val title: String,

    /** Domain (animepahe.com/pw/org) */
    val host: String,

    /** Unique episode count */
    val total: Int = 0,

    /** List of episodes */
    val episodes: List<EpisodeInfo> = emptyList(),

    /** Whether cache directory exists for this anime */
    val hasSession: Boolean = false,

    /**
     * DECISIONS 144.2 — the play-page URLs the bounded audio probe samples:
     * the lowest-numbered row of every merged session plus the highest-numbered
     * row of the opened session, deduplicated and ordered discovery-minimums
     * first with the opened-maximum last (at most `merged sessions + 1` URLs).
     *
     * These are `play_url` values — the category the scan cache already
     * persists — so a re-probe after a cache hit reaches exactly the same
     * deduplicated sessions without another listing request. Empty for a legacy
     * payload, which then simply is never probed.
     */
    val audioProbeTargets: List<String> = emptyList(),

    /**
     * DECISIONS 144.5 - the probe result persisted with this scan-cache row
     * (`null` = [AudioProbeState.NOT_PROBED]). A completed result is reused with
     * zero network for the life of the row; it is reset by a fresh scan.
     */
    val audioProbe: AudioProbeResult? = null,

    /**
     * `false` only when pagination ended BEFORE every required page had been
     * read — a truncated listing, not an episode inventory.
     *
     * Python's `completed_all` (`extract/scanner.py:252,267-270,295-302`): such
     * a scan still returns the episodes it did collect, but the reference never
     * caches it and tells the user to re-run. Every other construction path
     * (a cache read, direct construction, a completed scan) is a completed
     * result and defaults to `true`; a partial result is never persisted, so a
     * cache row can only ever deserialize to `true`.
     */
    val completed: Boolean = true,

    /**
     * Lazy-loading slice — the API paginator's own `total` for this listing, or
     * `null` when the response carried no usable figure.
     *
     * DELIBERATELY distinct from [total]: [total] is the DERIVED count of
     * distinct episode numbers in the episodes actually loaded here (it is what
     * the scan cache persists and what a partial listing can still answer
     * honestly), whereas this is the source's declared row count, used only for
     * eligibility bounds and range validation. `null` means unknown and is
     * never coerced to 0.
     */
    val apiTotal: Int? = null,

    /** The API's `per_page` window (30 by default) — `null` when unknown. */
    val perPage: Int? = null,

    /** The API's `last_page` — the last page the source reports. `null` when unknown. */
    val apiLastPage: Int? = null,

    /**
     * The variant sessions (SUB/DUB) this listing was read from, in discovery
     * order with the opened session first. Empty for a cache read of a payload
     * that predates the field.
     */
    val variantSessions: List<String> = emptyList(),

    /**
     * The 1-based API page indexes whose rows are present in [episodes].
     *
     * Empty means "no paging information" — the whole loaded listing is one
     * undivided result, which is exactly what a pre-lazy cache payload or a
     * single-page anime looks like.
     */
    val loadedPages: Set<Int> = emptySet(),
) {
    /**
     * Get a specific variant by episode number and audio language.
     * Returns null if no match found.
     */
    fun getVariant(number: Float, audio: String): EpisodeInfo? {
        return episodes.find { it.number == number && it.audio == audio }
    }

    /**
     * Get all variants for a given episode number.
     * Returns list sorted by audio (stable across runs).
     */
    fun getAllVariants(number: Float): List<EpisodeInfo> {
        return episodes
            .filter { it.number == number }
            .sortedBy { it.audio }
    }

    /**
     * The neutral deterministic DEFAULT variant for a bulk action
     * (SPEC §22 "Audio selection (per episode)"): this number's `jpn` variant
     * when the source offers one, otherwise its first available variant.
     *
     * This is a visible pre-confirmation default, NOT a claim about "original"
     * audio — the application never labels any value "original" (the source
     * provides no such metadata). A number with no variants at all yields
     * `null`, which callers report as an explicit failure.
     */
    fun defaultVariant(number: Float): EpisodeInfo? =
        getVariant(number, EpisodeInfo.AUDIO_JAPANESE) ?: getAllVariants(number).firstOrNull()

    /**
     * Get unique episode numbers sorted.
     */
    fun getUniqueEpisodeNumbers(): List<Float> {
        return episodes.map { it.number }.distinct().sorted()
    }

    /**
     * Get episodes grouped by episode number.
     */
    fun getEpisodesGroupedByNumber(): Map<Float, List<EpisodeInfo>> {
        return episodes.groupBy { it.number }
    }

    /**
     * Check if this anime has both SUB and DUB variants.
     */
    fun hasDualAudio(): Boolean {
        val audios = episodes.map { it.audio }.toSet()
        return EpisodeInfo.AUDIO_JAPANESE in audios && EpisodeInfo.AUDIO_ENGLISH in audios
    }
}

/**
 * DECISIONS 144.4 — the REQUESTED audio's play-page carrier for [number]: the
 * listing's own variant of that audio when it has one, otherwise any existing
 * row of that number carrying the requested audio as intent
 * (`copy(audio = requestedAudio)`).
 *
 * Availability of a requested audio is decided when it is RESOLVED, never at
 * selection (SPEC §22). The carrier supplies only identity (number/session) and
 * the real `play_url`; it is NEVER a substitution — every caller hands the
 * carrier's copied `audio` to the resolver, which either finds that exact audio
 * on the play page or fails with an explicit message naming it.
 *
 * Returns `null` only when the number itself has no rows at all; callers keep
 * their pre-existing missing-number behavior for that case.
 *
 * Used by the details batch gate, single-episode resolution and the Watchlist
 * concrete-audio path — ONE rule everywhere (SPEC §22).
 */
fun variantOrCarrier(
    episodes: List<EpisodeInfo>,
    number: Float,
    requestedAudio: String,
): EpisodeInfo? = episodes.firstOrNull { it.number == number && it.audio == requestedAudio }
    ?: episodes.firstOrNull { it.number == number }?.copy(audio = requestedAudio)