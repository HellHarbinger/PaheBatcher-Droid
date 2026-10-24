package com.pahebatcher.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pahebatcher.app.data.local.entity.PlaybackPositionEntity

/**
 * Playback-position persistence (PHASE11_ARCHITECTURE_V2 §29).
 * Upsert-on-save (PK episodeId), read-before-play; indefinite retention in v1
 * (§29.1 — no delete/cleanup statements exist by design).
 */
@Dao
interface PlaybackPositionDao {

    /**
     * Get the persisted position for an episode, or null if never played.
     */
    @Query("SELECT * FROM playback_position WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getByEpisodeId(episodeId: String): PlaybackPositionEntity?

    /**
     * Save (or overwrite) the playback position for an episode.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: PlaybackPositionEntity)
}
