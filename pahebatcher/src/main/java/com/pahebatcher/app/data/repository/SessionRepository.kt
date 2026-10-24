package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One row of the Android session list — the Room-backed equivalent of a Python
 * `pahe_cache/<session>/session.json` entry as [com.pahebatcher.app.sessions.SessionManager.get_sessions]
 * reported it (`sessions.py`).
 *
 * @param episodeCount Python's `ep_count`: how many episode rows exist under
 *   this session (segment/byte totals are the download engine's own progress
 *   data and are read from the episode rows instead of scanning directories).
 * @param resumeUrl Python's `run()` resume target (`meta["url"]`): the anime URL
 *   reconstructed from the stored host + session UUID — the SAME URL format the
 *   parser produces (`https://<host>/anime/<uuid>`).
 */
data class SessionSummary(
    val session: DownloadSessionEntity,
    val episodeCount: Int,
    val resumeUrl: String,
)

/**
 * Phase 12A backend/data foundation — download-session persistence over Room,
 * the Android equivalent of Python's `session.json` files + `SessionManager`
 * (`sessions.py` 3.6.0).
 *
 * Behavioral parity:
 *
 * - [observe] = `SessionManager.get_sessions` listing: sessions ordered newest
 *   first (the DAO's `updatedAt DESC` mirrors Python's `st_mtime` descending).
 *   Python read one JSON file per cache folder; Room holds the same rows, so a
 *   corrupt entry cannot silently vanish — it either exists or does not.
 * - [summaries] = the Python listing's `title` + `ep_count` fields, derived
 *   from the same database the download engine writes (no second source of
 *   truth, no directory scanning).
 * - [resumeUrl] = `SessionManager.run()`'s `return str(target["url"])`.
 * - [setState] validates against the SAME five states the entity declares
 *   (`CREATED`, `DOWNLOADING`, `PAUSED`, `COMPLETED`, `FAILED`); an unknown
 *   state is rejected instead of being persisted as an unreadable string.
 *
 * Session rows are CREATED by the production download pipeline
 * (`DownloadRepository.queueResolvedEpisode`); this repository adds the read,
 * summary, state-transition, and deletion surface the sessions screen will bind
 * to. No schema or migration is introduced (schema v3 unchanged).
 */
class SessionRepository(
    private val sessionDao: DownloadSessionDao,
    private val episodeDao: EpisodeDownloadDao,
) {

    /** All sessions, newest first (Python's `st_mtime` descending order). */
    fun observe(): Flow<List<DownloadSessionEntity>> = sessionDao.getAll()

    /** Session summaries with episode counts — the Python `get_sessions` rows. */
    fun summaries(): Flow<List<SessionSummary>> =
        sessionDao.getAll().map { sessions ->
            sessions.map { session -> summaryOf(session) }
        }

    /** One session by id, or `null` (Python: no `session.json` for that folder). */
    suspend fun get(sessionId: String): DownloadSessionEntity? =
        sessionDao.getById(sessionId)

    /** Synchronous summary for a single session. */
    suspend fun summary(sessionId: String): SessionSummary? =
        sessionDao.getById(sessionId)?.let { summaryOf(it) }

    suspend fun count(): Int = sessionDao.count()

    /**
     * Create or refresh a session row (Python writing `session.json`).
     * `createdAt` is preserved when the row already exists.
     *
     * Never via an `INSERT OR REPLACE` upsert (DATA-005): replacing the parent
     * fires `ON DELETE CASCADE` on `episode_downloads` and would erase every
     * episode already downloaded under this session (AGENTS §9) — and on the
     * read-then-write race below, REPLACE would do it to a row a concurrent
     * writer had just created. Creation goes through `insertIfAbsent`
     * (INSERT OR IGNORE), refresh through `update`; the same branch
     * `DownloadRepository.ensureSession` takes.
     */
    suspend fun upsert(session: DownloadSessionEntity) {
        val existing = sessionDao.getById(session.sessionId)
        if (existing == null) {
            // DATA-005: INSERT OR IGNORE — a no-op if a concurrent writer created
            // this row between the read above and this call. A lost metadata merge
            // on that exact race is strictly cheaper than losing episodes.
            sessionDao.insertIfAbsent(session)
        } else {
            sessionDao.update(session.copy(createdAt = existing.createdAt))
        }
    }

    /**
     * Move a session to [state] — Python updating the `state` field of
     * `session.json`.
     *
     * @throws IllegalArgumentException for an unknown state, so a typo can never
     *   become a persisted, unrenderable value.
     */
    suspend fun setState(sessionId: String, state: String) {
        require(VALID_STATES.contains(state)) {
            "Unknown session state: $state (expected one of $VALID_STATES)"
        }
        sessionDao.updateState(sessionId, state)
    }

    /**
     * Refresh mutable metadata WITHOUT touching `createdAt` or the episode rows.
     * Uses the DAO's `@Update` (never `upsert`), because `INSERT OR REPLACE`
     * would cascade-delete the session's episodes — see `DownloadSessionDao.update`.
     */
    suspend fun updateMetadata(session: DownloadSessionEntity) {
        sessionDao.update(session)
    }

    /** Delete a session row (Python deleting its cache folder). */
    suspend fun remove(sessionId: String) = sessionDao.delete(sessionId)

    /** Delete every session row. */
    suspend fun removeAll() = sessionDao.deleteAll()

    /** Episode rows under a session — Python's `ep_count`. */
    suspend fun episodeCount(sessionId: String): Int =
        episodeDao.countBySession(sessionId)

    private suspend fun summaryOf(session: DownloadSessionEntity) = SessionSummary(
        session = session,
        episodeCount = episodeDao.countBySession(session.sessionId),
        resumeUrl = resumeUrlOf(session),
    )

    companion object {

        /** The five states `DownloadSessionEntity` declares (Python session.json). */
        val VALID_STATES = setOf(
            DownloadSessionEntity.STATE_CREATED,
            DownloadSessionEntity.STATE_DOWNLOADING,
            DownloadSessionEntity.STATE_PAUSED,
            DownloadSessionEntity.STATE_COMPLETED,
            DownloadSessionEntity.STATE_FAILED,
        )

        /**
         * Reconstruct the anime URL for a session — Python's `meta["url"]`,
         * which is exactly `https://<host>/anime/<session uuid>`.
         */
        fun resumeUrlOf(session: DownloadSessionEntity): String =
            "https://${session.host}/anime/${session.animeSession}"
    }
}
