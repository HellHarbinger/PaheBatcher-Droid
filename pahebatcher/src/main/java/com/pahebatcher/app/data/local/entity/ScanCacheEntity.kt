package com.pahebatcher.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pahebatcher.app.domain.model.AnimeInfo

/**
 * Room entity for scan cache.
 * Replaces Python's _scan_cache.json files.
 */
@Entity(tableName = "scan_cache")
data class ScanCacheEntity(
    @PrimaryKey
    val session: String,

    val title: String,

    val host: String,

    val totalEpisodes: Int,

    /** Serialized episodes list as JSON string */
    val episodesJson: String,

    /** Timestamp when cache was created */
    val cachedAt: Long,

    /** Cache TTL in minutes (default 60) */
    val cacheTtlMinutes: Int = 60
) {
    /**
     * Check if this cache entry has expired.
     * Matches Python's cache TTL logic.
     */
    fun isExpired(): Boolean {
        val currentTime = System.currentTimeMillis()
        val cacheTimeMillis = cachedAt + (cacheTtlMinutes * 60L * 1000L)
        return currentTime > cacheTimeMillis
    }
}