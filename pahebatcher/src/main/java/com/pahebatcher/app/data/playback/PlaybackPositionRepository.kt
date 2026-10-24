package com.pahebatcher.app.data.playback

import com.pahebatcher.app.data.local.dao.PlaybackPositionDao
import com.pahebatcher.app.data.local.entity.PlaybackPositionEntity

/**
 * Playback-position persistence (PHASE11_ARCHITECTURE_V2 §29).
 *
 * Thin repository over [PlaybackPositionDao]: upsert-on-save keyed by episodeId,
 * read-before-play (both in the ViewModel and for the in-session next/prev
 * restore), **indefinite retention** — the DAO deliberately has no delete/TTL
 * statement; a future episode-deletion feature must add one and remove the
 * stored positions at the same time.
 *
 * Negative inputs are coerced to 0 here so a bad caller can never persist a
 * position before the start of the media.
 */
class PlaybackPositionRepository(
    private val dao: PlaybackPositionDao,
) {

    /** Last persisted position for [episodeId], or null if the episode never played. */
    suspend fun getPosition(episodeId: String): Long? =
        dao.getByEpisodeId(episodeId)?.positionMs

    /** Persist (create or overwrite) the position for [episodeId] (§29 upsert). */
    suspend fun save(episodeId: String, positionMs: Long, durationMs: Long) {
        dao.upsert(
            PlaybackPositionEntity(
                episodeId = episodeId,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
