package com.pahebatcher.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for download session operations.
 * Replaces Python's session.json file operations.
 */
@Dao
interface DownloadSessionDao {

    /**
     * Get session by ID.
     */
    @Query("SELECT * FROM download_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): DownloadSessionEntity?

    /**
     * Get all sessions.
     */
    @Query("SELECT * FROM download_sessions ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<DownloadSessionEntity>>

    /**
     * Update an EXISTING session in place.
     *
     * There is deliberately NO `INSERT OR REPLACE` upsert on this DAO: REPLACE
     * deletes the conflicting parent row before re-inserting it — and that delete
     * fires `ON DELETE CASCADE` on `episode_downloads`, destroying every episode
     * already queued under this session. Session metadata must therefore always be
     * refreshed here (AGENTS §9: persistent download state survives ordinary
     * operations), and an absent row must be created with [insertIfAbsent].
     */
    @Update
    suspend fun update(session: DownloadSessionEntity)

    /**
     * Slice B (review finding M-8) — the atomic parent-row creation claim.
     *
     * `ensureSession` read (`getById`) and then wrote with `upsert` when the row
     * was absent. Two concurrent FIRST-EVER submissions for one anime both read
     * `null`, and the second `INSERT OR REPLACE` deleted the parent row the
     * first had just created — firing `ON DELETE CASCADE` on the episode row the
     * other caller had just inserted. That reopened the very race the
     * episode-row claim ([EpisodeDownloadDao.insertIfAbsent]) is meant to close.
     *
     * `INSERT OR IGNORE` makes the create idempotent: one caller inserts, every
     * other caller observes `-1` and refreshes in place with [update]. No
     * existing row can ever be deleted from this path.
     *
     * Query-only — no schema change and no migration.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(session: DownloadSessionEntity): Long

    /**
     * Update session state.
     */
    @Query("UPDATE download_sessions SET state = :state, updatedAt = :timestamp WHERE sessionId = :sessionId")
    suspend fun updateState(sessionId: String, state: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Delete session.
     */
    @Query("DELETE FROM download_sessions WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    /**
     * Delete all sessions.
     */
    @Query("DELETE FROM download_sessions")
    suspend fun deleteAll()

    /**
     * Get count of sessions.
     */
    @Query("SELECT COUNT(*) FROM download_sessions")
    suspend fun count(): Int
}