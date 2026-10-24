package com.pahebatcher.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for download sessions.
 * Replaces Python's session.json files.
 * Tracks overall download session state.
 */
@Entity(tableName = "download_sessions")
data class DownloadSessionEntity(
    @PrimaryKey
    val sessionId: String,

    /** Anime title */
    val title: String,

    /** Anime session UUID */
    val animeSession: String,

    /** Domain */
    val host: String,

    /** Quality preference for this session */
    val quality: Int,

    /** Audio language preference */
    val audioLang: String,

    /** Output directory */
    val outputDir: String,

    /** Cache directory */
    val cacheDir: String,

    /** Session creation timestamp */
    val createdAt: Long,

    /** Last update timestamp */
    val updatedAt: Long,

    /** Session state: CREATED, DOWNLOADING, PAUSED, COMPLETED, FAILED */
    val state: String = "CREATED"
) {
    companion object {
        const val STATE_CREATED = "CREATED"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
    }
}