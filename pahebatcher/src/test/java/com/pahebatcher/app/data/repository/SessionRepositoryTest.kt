package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * In-memory [DownloadSessionDao] (same fake-DAO pattern as ScanCacheRepositoryTest).
 *
 * DATA-005: the DAO has deliberately NO `INSERT OR REPLACE` upsert — REPLACE
 * deletes the conflicting parent row first, and that delete fires
 * `ON DELETE CASCADE` on `episode_downloads` (EpisodeDownloadEntity.kt:24).
 * [insertIfAbsent] models the only creation path (INSERT OR IGNORE) and [update]
 * the only refresh path, tracked through [episodeChildren] so a test can prove
 * why a metadata refresh never touches a session's episodes.
 */
class FakeDownloadSessionDao : DownloadSessionDao {
    val rows = LinkedHashMap<String, DownloadSessionEntity>()

    /** Episode rows per session — erased by a REPLACE cascade, never by [update]. */
    val episodeChildren = LinkedHashMap<String, MutableList<String>>()

    override suspend fun getById(sessionId: String): DownloadSessionEntity? = rows[sessionId]

    override fun getAll(): Flow<List<DownloadSessionEntity>> =
        flowOf(rows.values.sortedByDescending { it.updatedAt })

    /**
     * Slice B (DECISIONS 146) — `INSERT OR IGNORE`, modelled faithfully.
     *
     * [DownloadRepository.ensureSession] uses this instead of Room's `REPLACE`
     * because `REPLACE` deletes the conflicting parent first and the FK's
     * ON DELETE CASCADE takes every episode row with it — a duplicate submission
     * would therefore have destroyed the very rows it was supposed to leave alone.
     * So an existing key must be left COMPLETELY untouched here, children
     * included, and reported as `-1`.
     */
    override suspend fun insertIfAbsent(session: DownloadSessionEntity): Long {
        if (rows.containsKey(session.sessionId)) return -1L
        rows[session.sessionId] = session
        return 1L
    }

    override suspend fun update(session: DownloadSessionEntity) {
        if (rows.containsKey(session.sessionId)) rows[session.sessionId] = session
    }

    override suspend fun updateState(sessionId: String, state: String, timestamp: Long) {
        rows[sessionId]?.let { rows[sessionId] = it.copy(state = state, updatedAt = timestamp) }
    }

    override suspend fun delete(sessionId: String) {
        rows.remove(sessionId)
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun count(): Int = rows.size
}

/**
 * Minimal [EpisodeDownloadDao] — only [countBySession] is exercised by
 * [SessionRepository]; every other method is out of scope for this suite and
 * fails loudly if it is ever reached (never a silent pass).
 */
class FakeEpisodeDownloadDaoForSessions : EpisodeDownloadDao {
    val counts = mutableMapOf<String, Int>()

    override suspend fun countBySession(sessionId: String): Int = counts[sessionId] ?: 0

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by SessionRepository")

    override suspend fun getById(episodeId: String): EpisodeDownloadEntity? = unused()
    override fun getBySessionId(sessionId: String): Flow<List<EpisodeDownloadEntity>> = unused()
    override fun getByState(state: String): Flow<List<EpisodeDownloadEntity>> = unused()
    override suspend fun upsert(episode: EpisodeDownloadEntity) = unused()
    override suspend fun insertIfAbsent(episode: EpisodeDownloadEntity): Long = unused()
    override suspend fun claimTerminalRequeue(episodeId: String, expectedState: String, sessionId: String, episodeNumber: Float, episodeSession: String, title: String, fansub: String, audio: String, playUrl: String, createdAt: Long, timestamp: Long): Int = unused()
    override suspend fun repairOutputUriIfState(episodeId: String, expectedState: String, outputUri: String, timestamp: Long): Int = unused()
    override suspend fun updateProgress(episodeId: String, completedSegments: Int, downloadedBytes: Long, speed: Long, etaSeconds: Long, timestamp: Long) = unused()
    override suspend fun updateState(episodeId: String, state: String, timestamp: Long) = unused()
    override suspend fun updateError(episodeId: String, errorMessage: String, timestamp: Long) = unused()
    override suspend fun updateCompleted(episodeId: String, outputPath: String, timestamp: Long) = unused()
    override suspend fun updateCompletedIfState(episodeId: String, expectedState: String, outputPath: String, timestamp: Long): Int = unused()
    override suspend fun delete(episodeId: String) = unused()
    override suspend fun countActive(): Int = unused()
    override suspend fun countResolvedPending(): Int = unused()
    override suspend fun getTotalEpisodeCount(): Int = unused()
    override suspend fun clearStreamInfo(episodeId: String, timestamp: Long) = unused()
    override suspend fun updateStateForResume(episodeId: String, expectedState: String, newState: String, timestamp: Long): Int = unused()
    override suspend fun beginRetry(episodeId: String, expectedState: String, timestamp: Long): Int = unused()
    override suspend fun updateErrorIfState(episodeId: String, expectedState: String, errorMessage: String, timestamp: Long): Int = unused()
    override suspend fun cancelIfNotTerminal(episodeId: String, timestamp: Long): Int = unused()
    override suspend fun getNextQueuedWithM3u8(): EpisodeDownloadEntity? = unused()
    override suspend fun getQueuedWithM3u8(): List<EpisodeDownloadEntity> = unused()
    override suspend fun getEpisodesForReconciliation(): List<EpisodeDownloadEntity> = unused()
    override suspend fun saveStreamInfo(episodeId: String, m3u8Url: String, userAgent: String, referer: String, segmentDir: String, timestamp: Long) = unused()
    override suspend fun updateTotalSegments(episodeId: String, total: Int, timestamp: Long) = unused()
    override suspend fun updateOutputUri(episodeId: String, outputUri: String?, timestamp: Long) = unused()
    override fun observeAllOrdered(): Flow<List<EpisodeDownloadEntity>> = unused()
    override suspend fun getAllOutputUris(): List<String> = unused()
    override suspend fun otherReferenceAudios(outputUri: String, episodeId: String): List<String> = unused()
    // `Replace file` (DECISIONS 152) — out of scope for SessionRepository, but
    // the interface grew them, so the fake fails loudly if they are reached.
    override suspend fun beginReplacement(episodeId: String, expectedState: String, replacePriorUri: String?, timestamp: Long): Int = unused()
    override suspend fun finishReplacement(episodeId: String, expectedState: String, outputPath: String, timestamp: Long): Int = unused()
    override suspend fun restoreCompletedForUncommittedReplacement(episodeId: String, expectedState: String, timestamp: Long): Int = unused()
    override suspend fun clearReplacementIntent(episodeId: String, timestamp: Long): Int = unused()
    override suspend fun clearReplacePriorUri(episodeId: String, timestamp: Long) = unused()
    // Run-ownership guards (pause/resume race fix) — out of scope for
    // SessionRepository (Worker-only writers), but the interface grew them, so
    // the fake fails loudly if they are reached.
    override suspend fun claimEntryRunWithRunId(episodeId: String, expectedState: String, runId: String, timestamp: Long): Int = unused()
    override suspend fun updateStateIfOwned(episodeId: String, expectedState: String, newState: String, runId: String, timestamp: Long): Int = unused()
    override suspend fun updateErrorIfOwned(episodeId: String, expectedState: String, errorMessage: String, runId: String, timestamp: Long): Int = unused()
    override suspend fun updateCompletedIfOwned(episodeId: String, expectedState: String, outputPath: String, runId: String, timestamp: Long): Int = unused()
    override suspend fun finishReplacementIfOwned(episodeId: String, expectedState: String, outputPath: String, runId: String, timestamp: Long): Int = unused()
    override suspend fun restoreCompletedForUncommittedReplacementIfOwned(episodeId: String, expectedState: String, runId: String, timestamp: Long): Int = unused()
}

/**
 * Session persistence semantics ported from Python `SessionManager`
 * (`sessions.py` 3.6.0): newest-first listing, title + episode-count summaries,
 * the resume URL, and validated state values.
 */
class SessionRepositoryTest {

    private lateinit var sessionDao: FakeDownloadSessionDao
    private lateinit var episodeDao: FakeEpisodeDownloadDaoForSessions
    private lateinit var repository: SessionRepository

    @Before
    fun setup() {
        sessionDao = FakeDownloadSessionDao()
        episodeDao = FakeEpisodeDownloadDaoForSessions()
        repository = SessionRepository(sessionDao, episodeDao)
    }

    private fun session(
        sessionId: String = "sess-1",
        title: String = "Alpha Anime",
        host: String = "animepahe.pw",
        animeSession: String = "uuid-1",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        state: String = DownloadSessionEntity.STATE_CREATED,
    ) = DownloadSessionEntity(
        sessionId = sessionId,
        title = title,
        animeSession = animeSession,
        host = host,
        quality = 1080,
        audioLang = "jpn",
        outputDir = "./downloads",
        cacheDir = "pahe_cache",
        createdAt = createdAt,
        updatedAt = updatedAt,
        state = state,
    )

    @Test
    fun `summaries list sessions newest first with episode counts`() = runBlocking {
        sessionDao.insertIfAbsent(session(sessionId = "older", updatedAt = 1_000L))
        sessionDao.insertIfAbsent(session(sessionId = "newer", title = "Beta Show", updatedAt = 9_000L))
        episodeDao.counts["newer"] = 12

        val summaries = repository.summaries().first()

        assertEquals(
            "Python get_sessions orders by mtime descending → Room orders by updatedAt DESC",
            listOf("newer", "older"),
            summaries.map { it.session.sessionId },
        )
        assertEquals("Python ep_count", 12, summaries[0].episodeCount)
        assertEquals(0, summaries[1].episodeCount)
        assertEquals("Beta Show", summaries[0].session.title)
    }

    @Test
    fun `resumeUrl reconstructs the python meta url`() {
        val url = SessionRepository.resumeUrlOf(
            session(host = "animepahe.pw", animeSession = "uuid-42"),
        )
        assertEquals("https://animepahe.pw/anime/uuid-42", url)
    }

    @Test
    fun `setState accepts the five declared states and rejects anything else`() = runBlocking {
        sessionDao.insertIfAbsent(session())

        repository.setState("sess-1", DownloadSessionEntity.STATE_DOWNLOADING)
        assertEquals(DownloadSessionEntity.STATE_DOWNLOADING, repository.get("sess-1")?.state)

        repository.setState("sess-1", DownloadSessionEntity.STATE_COMPLETED)
        assertEquals(DownloadSessionEntity.STATE_COMPLETED, repository.get("sess-1")?.state)

        for (state in SessionRepository.VALID_STATES) {
            repository.setState("sess-1", state)
            assertEquals(state, repository.get("sess-1")?.state)
        }

        val beforeInvalid = repository.get("sess-1")!!.state
        try {
            repository.setState("sess-1", "NOT_A_STATE")
            fail("unknown state must be rejected, never persisted")
        } catch (expected: IllegalArgumentException) {
            assertEquals(beforeInvalid, repository.get("sess-1")?.state)
        }
    }

    @Test
    fun `upsert preserves createdAt for an existing session`() = runBlocking {
        repository.upsert(session(createdAt = 1_000L, updatedAt = 1_000L))
        repository.upsert(session(title = "Renamed", createdAt = 7_777L, updatedAt = 5_000L))

        val stored = repository.get("sess-1")!!
        assertEquals("createdAt is never reset by a refresh", 1_000L, stored.createdAt)
        assertEquals("title refresh applies", "Renamed", stored.title)
        assertEquals(5_000L, stored.updatedAt)
    }

    @Test
    fun `upsert of an existing session never deletes its episode rows`() = runBlocking {
        repository.upsert(session(createdAt = 1_000L))
        // Episode rows already downloaded under this session (AGENTS §9).
        sessionDao.episodeChildren["sess-1"] = mutableListOf("ep-1", "ep-2")

        repository.upsert(session(title = "Refreshed", updatedAt = 5_000L))

        assertEquals("title refresh applies", "Refreshed", repository.get("sess-1")?.title)
        assertEquals(
            "the REPLACE cascade must NOT fire for an existing parent — every " +
                "episode row (state, outputPath, progress) survives a metadata refresh",
            listOf("ep-1", "ep-2"),
            sessionDao.episodeChildren["sess-1"],
        )
    }

    @Test
    fun `remove deletes a single session and removeAll clears the table`() = runBlocking {
        sessionDao.insertIfAbsent(session(sessionId = "a"))
        sessionDao.insertIfAbsent(session(sessionId = "b"))

        repository.remove("a")
        assertNull(repository.get("a"))
        assertNotNull(repository.get("b"))
        assertEquals(1, repository.count())

        repository.removeAll()
        assertEquals(0, repository.count())
    }

    @Test
    fun `get returns null for an unknown session`() = runBlocking {
        assertNull(repository.get("missing"))
        assertNull(repository.summary("missing"))
        assertTrue(repository.summaries().first().isEmpty())
    }

    @Test
    fun `episodeCount delegates to the episode rows`() = runBlocking {
        episodeDao.counts["sess-1"] = 24
        assertEquals(24, repository.episodeCount("sess-1"))
        assertEquals(0, repository.episodeCount("unknown"))
    }
}
