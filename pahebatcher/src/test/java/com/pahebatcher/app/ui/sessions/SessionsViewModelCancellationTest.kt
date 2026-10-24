package com.pahebatcher.app.ui.sessions

import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.export.ExportOutcome
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.repository.FakeDownloadSessionDao
import com.pahebatcher.app.data.repository.SessionActions
import com.pahebatcher.app.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AQR-051(b) / NET-001 — the UI-006 cancellation contract of
 * [SessionsViewModel.launchAction], exercised on the JVM over the real
 * [SessionActions] with fake DAOs (the same fake-DAO construction
 * `SessionRepositoryTest` proves).
 *
 * When the action's work is abandoned (its row operation throws
 * [CancellationException]), the arm must RETHROW instead of folding it into a
 * failure message: no spurious message is published and `finish()` never runs
 * (the row stays busy). The swallowing defect published e.g. the CE's own
 * message as "…failed" for work that was merely cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelCancellationTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Episode DAO serving exactly this scenario's rows; anything else fails loudly. */
    private class ScenarioEpisodeDao(
        private val rows: List<EpisodeDownloadEntity>,
    ) : EpisodeDownloadDao {
        override fun observeAllOrdered(): Flow<List<EpisodeDownloadEntity>> = flowOf(rows)

        override fun getBySessionId(sessionId: String): Flow<List<EpisodeDownloadEntity>> =
            flowOf(rows.filter { it.sessionId == sessionId })

        override suspend fun countBySession(sessionId: String): Int =
            rows.count { it.sessionId == sessionId }

        private fun unused(): Nothing =
            throw UnsupportedOperationException("not exercised by SessionsViewModelCancellationTest")

        override suspend fun getById(episodeId: String): EpisodeDownloadEntity? = unused()
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
        override suspend fun getAllOutputUris(): List<String> = unused()
        override suspend fun otherReferenceAudios(outputUri: String, episodeId: String): List<String> = unused()
        override suspend fun beginReplacement(episodeId: String, expectedState: String, replacePriorUri: String?, timestamp: Long): Int = unused()
        override suspend fun finishReplacement(episodeId: String, expectedState: String, outputPath: String, timestamp: Long): Int = unused()
        override suspend fun restoreCompletedForUncommittedReplacement(episodeId: String, expectedState: String, timestamp: Long): Int = unused()
        override suspend fun claimEntryRunWithRunId(episodeId: String, expectedState: String, runId: String, timestamp: Long): Int = unused()
        override suspend fun updateStateIfOwned(episodeId: String, expectedState: String, newState: String, runId: String, timestamp: Long): Int = unused()
        override suspend fun updateErrorIfOwned(episodeId: String, expectedState: String, errorMessage: String, runId: String, timestamp: Long): Int = unused()
        override suspend fun updateCompletedIfOwned(episodeId: String, expectedState: String, outputPath: String, runId: String, timestamp: Long): Int = unused()
        override suspend fun finishReplacementIfOwned(episodeId: String, expectedState: String, outputPath: String, runId: String, timestamp: Long): Int = unused()
        override suspend fun restoreCompletedForUncommittedReplacementIfOwned(episodeId: String, expectedState: String, runId: String, timestamp: Long): Int = unused()
        override suspend fun clearReplacementIntent(episodeId: String, timestamp: Long): Int = unused()
        override suspend fun clearReplacePriorUri(episodeId: String, timestamp: Long) = unused()
    }

    /** Never reached in this scenario — the cancellation happens first. */
    private class UnusedExporter : EpisodeExporter {
        override suspend fun export(episode: EpisodeDownloadEntity): ExportOutcome =
            throw UnsupportedOperationException("not exercised by SessionsViewModelCancellationTest")

        override suspend fun sweepStalePending(olderThanEpochMs: Long): Int =
            throw UnsupportedOperationException("not exercised by SessionsViewModelCancellationTest")
    }

    @Test
    fun `a cancelled session action publishes no failure message and never finishes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        val session = DownloadSessionEntity(
            sessionId = "sess-1",
            title = "Some Anime",
            animeSession = "anime-uuid-42",
            host = "animepahe.si",
            quality = 1080,
            audioLang = "sub",
            outputDir = "/storage/out",
            cacheDir = "/data/cache",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            state = DownloadSessionEntity.STATE_CREATED,
        )
        val episode = EpisodeDownloadEntity(
            episodeId = "ep-1",
            sessionId = "sess-1",
            episodeNumber = 1.0f,
            episodeSession = "ep-sess-1",
            title = "Episode",
            fansub = "",
            audio = "sub",
            playUrl = "https://example.com/play",
            state = EpisodeDownloadEntity.STATE_DOWNLOADING,
            createdAt = 1_000L,
            updatedAt = 2_000L,
        )

        val sessionDao = FakeDownloadSessionDao()
        sessionDao.rows[session.sessionId] = session
        val episodeDao = ScenarioEpisodeDao(listOf(episode))
        val repository = SessionRepository(sessionDao, episodeDao)
        val actions = SessionActions(
            sessionRepository = repository,
            episodeDao = episodeDao,
            cancelRow = { throw CancellationException("caller abandoned the work") },
            retryRow = { },
            pauseRow = { false },
            resumeRow = { false },
            exporter = UnusedExporter(),
        )
        val vm = SessionsViewModel(repository, actions)
        advanceUntilIdle() // init combine settles

        vm.cancelSession("sess-1")
        advanceUntilIdle()

        assertEquals(
            "cancellation must not publish a spurious failure message " +
                "(UI-006/AQR-017 — a swallowed CE set message to its own text)",
            null,
            vm.uiState.value.message,
        )
        assertEquals(
            "the action never FINISHED (finish() runs only past the CE arm) — " +
                "its row stays busy",
            "sess-1",
            vm.uiState.value.busySessionId,
        )
    }
}
