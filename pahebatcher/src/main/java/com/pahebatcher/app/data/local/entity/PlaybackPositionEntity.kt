package com.pahebatcher.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted playback position for an episode (PHASE11_ARCHITECTURE_V2 §29).
 *
 * Android-native extension (Python has no equivalent — PARITY §11.2 📱): position
 * survives rotation, configuration change, process death, and app restart
 * (§24: cold re-entry restores the saved position before play).
 *
 * Retention (§29.1): rows persist indefinitely in v1 — PK = episodeId (one row per
 * episode, upserted on save); **no TTL, no cleanup pass, no eviction in v1**.
 *
 * Deliberately **no foreign key** to `episode_downloads` (§29.1): keeps the additive
 * v3 migration free of FK constraints. A future episode-deletion feature must remove
 * positions via a later FK cascade or an explicit repository call; neither
 * cleanup path exists in the current schema.
 */
@Entity(tableName = "playback_position")
data class PlaybackPositionEntity(
    @PrimaryKey
    val episodeId: String,

    /** Last persisted playback position in milliseconds. */
    val positionMs: Long,

    /** Duration at the time of the save, in milliseconds (0 if unknown). */
    val durationMs: Long,

    /** When this position was saved. */
    val updatedAt: Long,
)
