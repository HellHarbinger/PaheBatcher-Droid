package com.pahebatcher.app.domain.model

import com.pahebatcher.app.domain.util.StringUtils

/**
 * Represents a single episode with all its metadata.
 *
 * Ported from Python models.py EpisodeInfo dataclass.
 * Preserves all fields and behavior from the Python reference.
 */
data class EpisodeInfo(
    /** Episode number (supports fractional like 13.5) */
    val number: Float,

    /** Episode session UUID */
    val session: String,

    /** Episode title */
    val title: String,

    /** Fansub group name */
    val fansub: String,

    /** Audio language: "jpn" (SUB) or "eng" (DUB) */
    val audio: String,

    /** Play page URL: https://{host}/play/{anime_session}/{episode_session} */
    val playUrl: String
) {
    /**
     * String representation of episode number.
     * Whole numbers show as integer, fractional show with decimal.
     * E.g., 1.0 -> "1", 13.5 -> "13.5"
     *
     * A2.1 (UI-004): delegates to the ONE renderer — every surface now agrees.
     */
    val epStr: String
        get() = StringUtils.episodeNumber(number)

    /**
     * Returns the directory name for this episode's cache.
     * Format: Ep_{number}_{AUDIO}
     * E.g., Ep_001_JPN, Ep_013_ENG
     */
    fun cacheDirectoryName(): String {
        val numStr = if (number == number.toLong().toFloat()) {
            java.util.Locale.ROOT.let { locale ->
                String.format(locale, "%03d", number.toLong())
            }
        } else {
            java.util.Locale.ROOT.let { locale ->
                String.format(locale, "%05.1f", number)
            }
        }
        val audioTag = audio.uppercase()
        return "Ep_${numStr}_$audioTag"
    }

    companion object {
        /** Audio language constants matching Python reference */
        const val AUDIO_JAPANESE = "jpn"
        const val AUDIO_ENGLISH = "eng"
    }
}