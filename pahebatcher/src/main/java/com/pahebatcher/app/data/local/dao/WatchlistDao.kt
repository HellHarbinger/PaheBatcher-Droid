package com.pahebatcher.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for watchlist operations.
 * Replaces Python's watchlist.json file operations.
 */
@Dao
interface WatchlistDao {

    /**
     * Get watchlist entry by session UUID.
     */
    @Query("SELECT * FROM watchlist WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: String): WatchlistEntity?

    /**
     * Get all watchlist entries.
     */
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WatchlistEntity>>

    /**
     * Insert or update watchlist entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchlistEntity)

    /**
     * Update last checked timestamp.
     */
    @Query("UPDATE watchlist SET lastCheckedAt = :timestamp WHERE sessionId = :sessionId")
    suspend fun updateLastChecked(sessionId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Update downloaded episodes list.
     */
    @Query("UPDATE watchlist SET downloadedEpisodesJson = :downloadedJson WHERE sessionId = :sessionId")
    suspend fun updateDownloadedEpisodes(sessionId: String, downloadedJson: String)

    /**
     * Delete watchlist entry.
     */
    @Query("DELETE FROM watchlist WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    /**
     * DATA-001 — atomically move an entry to a new primary key.
     *
     * A relink deletes the old row and re-inserts the SAME entry under the new
     * session UUID. Running those as two independent DAO calls opens a window in
     * which a process death (or a failed upsert) leaves the watchlist entry
     * deleted and never re-created — permanent loss of the user's watch history
     * and per-entry preferences. Wrapping both in one transaction makes the move
     * all-or-nothing (AGENTS §9: persisted state must survive interruption).
     */
    @Transaction
    suspend fun relink(oldSessionId: String, target: WatchlistEntity) {
        delete(oldSessionId)
        upsert(target)
    }

    /**
     * Delete all watchlist entries.
     */
    @Query("DELETE FROM watchlist")
    suspend fun deleteAll()

    /**
     * Get count of watchlist entries.
     */
    @Query("SELECT COUNT(*) FROM watchlist")
    suspend fun count(): Int
}