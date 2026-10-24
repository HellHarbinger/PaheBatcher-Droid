package com.pahebatcher.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pahebatcher.app.data.local.entity.ScanCacheEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for scan cache operations.
 * Replaces Python's _scan_cache.json file operations.
 */
@Dao
interface ScanCacheDao {

    /**
     * Get cached scan result by session UUID.
     * Returns null if not found or expired.
     */
    @Query("SELECT * FROM scan_cache WHERE session = :session LIMIT 1")
    suspend fun getBySession(session: String): ScanCacheEntity?

    /**
     * Get all cached scan results.
     */
    @Query("SELECT * FROM scan_cache")
    fun getAll(): Flow<List<ScanCacheEntity>>

    /**
     * Insert or update scan cache entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: ScanCacheEntity)

    /**
     * Delete expired cache entries.
     */
    @Query("DELETE FROM scan_cache WHERE cachedAt < :expiryTime")
    suspend fun deleteExpired(expiryTime: Long)

    /**
     * Delete specific cache entry.
     */
    @Query("DELETE FROM scan_cache WHERE session = :session")
    suspend fun delete(session: String)

    /**
     * Delete all cache entries.
     */
    @Query("DELETE FROM scan_cache")
    suspend fun deleteAll()

    /**
     * Get count of cached entries.
     */
    @Query("SELECT COUNT(*) FROM scan_cache")
    suspend fun count(): Int
}