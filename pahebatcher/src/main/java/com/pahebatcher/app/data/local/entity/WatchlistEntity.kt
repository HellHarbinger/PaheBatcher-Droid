package com.pahebatcher.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for watchlist entries.
 * Replaces Python's watchlist.json.
 * Tracks anime for new episode detection.
 */
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey
    val sessionId: String,

    /** Anime URL */
    val url: String,

    /** Anime session UUID */
    val animeSession: String,

    /** Domain */
    val host: String,

    /** Anime title */
    val title: String,

    /** Quality preference */
    val quality: Int,

    /**
     * Per-entry audio preference (NOT the removed global setting — the column
     * and this field remain, and this row is the only thing that carries it):
     *
     * * [AUDIO_ANY] (`"any"`) — what every NEW entry stores (SPEC §22): the
     *   check picks each number's deterministic default variant
     *   ([com.pahebatcher.app.domain.model.AnimeInfo.defaultVariant]);
     * * a concrete value (`"jpn"`, `"eng"`, or any legacy/open-set string) —
     *   used STRICTLY: a number without that exact audio is skipped with a
     *   recorded reason, never substituted.
     *
     * Legacy rows written before [AUDIO_ANY] existed hold `"jpn"`/`"eng"` and
     * keep behaving strictly as those concrete values.
     */
    val audioLang: String,

    /** Output directory */
    val outputDir: String,

    /** Max parallel downloads */
    val maxParallel: Int,

    /** HLS workers */
    val hlsWorkers: Int,

    /** Keep temporary files */
    val keepTemp: Boolean,

    /** Auto retry setting */
    val autoRetry: Int,

    /** Timestamp when added to watchlist */
    val addedAt: Long,

    /** Timestamp when last checked for new episodes */
    val lastCheckedAt: Long,

    /** List of episode numbers that have been downloaded (JSON array) */
    val downloadedEpisodesJson: String = "[]"
) {
    /**
     * Check if watchlist entry is stale (needs checking).
     * Uses cache TTL from configuration.
     */
    fun isStale(cacheTtlMinutes: Int): Boolean {
        val currentTime = System.currentTimeMillis()
        val checkTimeMillis = lastCheckedAt + (cacheTtlMinutes * 60L * 1000L)
        return currentTime > checkTimeMillis
    }

    companion object {
        /**
         * The audio preference of every entry added since the audio-variant
         * slice (SPEC §22): "no concrete audio pinned" — the watchlist check
         * resolves each number through the deterministic default variant.
         * Chosen over Python's `audio_lang` default (`"jpn"`) because a
         * watchlist entry must not silently pin one audio forever, and chosen
         * as a literal sentinel because the column is a plain String
         * (no Room migration).
         */
        const val AUDIO_ANY = "any"
    }
}