package com.pahebatcher.app.domain.model

/**
 * The five persisted states of one title's bounded play-page audio probe
 * (SPEC §22 "Audio selection (per episode)", DECISIONS 144.5).
 *
 * The state lives inside the EXISTING scan-cache row's JSON payload — there is
 * no new table, no Room migration and no independent TTL; probe state expires
 * with the row that carries it.
 */
enum class AudioProbeState {
    /**
     * No probe result has ever been persisted for this cache row (fresh scan,
     * or a legacy payload that carried none).
     */
    NOT_PROBED,

    /** Every recorded target was fetched and at least one audio string was found. */
    OK_VALUES,

    /**
     * Every recorded target was fetched and NONE carried a parseable audio
     * string — a COMPLETED, empty result, not a failure. The option set falls
     * back to release-listing audio exactly as it does for [FAILED].
     */
    OK_EMPTY,

    /** Some targets were fetched, others failed — the union covers the successes only. */
    PARTIAL,

    /**
     * Not a single target could be fetched. This is NEVER evidence that an
     * audio is absent: the option set falls back to release-listing audio and
     * the probe is re-attempted on the next user-driven load.
     */
    FAILED;

    /** True for [OK_VALUES] and [OK_EMPTY] — the states that need no further network. */
    val completed: Boolean
        get() = this == OK_VALUES || this == OK_EMPTY
}

/**
 * The recorded outcome of ONE audio-option probe (DECISIONS 144.2/144.5).
 *
 * Only AUDIO STRINGS are recorded: never play-page HTML, never a stream URL,
 * never a target list outside the scan-cache payload that already carries
 * `play_url`.
 *
 * @property state the five-state probe outcome (see [AudioProbeState]).
 * @property audios the union of `data-audio` values found, in first-seen order
 *   across the sampled play pages, deduplicated and open-set (never reduced to
 *   a SUB/DUB boolean). Non-empty only when the probe actually saw pages.
 * @property targets how many play-page targets this attempt covered.
 * @property fetched how many of [targets] were fetched successfully.
 */
data class AudioProbeResult(
    val state: AudioProbeState = AudioProbeState.NOT_PROBED,
    val audios: List<String> = emptyList(),
    val targets: Int = 0,
    val fetched: Int = 0,
) {
    /**
     * Whether this result needs no further network for the life of the cache
     * row it is stored in ([AudioProbeState.completed]).
     */
    val isCompleted: Boolean
        get() = state.completed
}
