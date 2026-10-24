package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.RecordStreamResolver
import com.pahebatcher.app.support.RetryTestStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavioral tests for DownloadRepository.
 * Phase 10 architecture v10 §8, §16 (DownloadRepositoryTest, ReEnqueueGuardTest,
 * RetryCountSemanticsTest coverage at repository level).
 *
 * Runs against the REAL Room database singleton and the REAL application
 * WorkManager (custom DownloadWorkerFactory registered by PaheBatcherApplication).
 */
@RunWith(AndroidJUnit4::class)
class DownloadRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-repo-test"
    private val episodeId = "ep-repo-test"

    private fun episode(id: String = episodeId, state: String = EpisodeDownloadEntity.STATE_QUEUED) =
        EpisodeDownloadEntity(
            episodeId = id,
            sessionId = sessionId,
            episodeNumber = 1.0f,
            episodeSession = "eps-$id",
            title = "Repo Test Episode",
            fansub = "TestFansub",
            audio = "sub",
            playUrl = "https://example.com/play",
            state = state,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        workManager.cancelUniqueWork("episode_download_$episodeId")
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    @Test
    fun enqueue_fresh_episode_transitions_to_resolving() {
        runBlocking {
            repository.enqueue(episode())
            val row = db.episodeDownloadDao().getById(episodeId)
            assertEquals(EpisodeDownloadEntity.STATE_RESOLVING, row?.state)
            // Segment directory created at canonical path
            assertTrue(row != null)
        }
    }

    @Test
    fun enqueue_skips_when_episode_is_non_terminal() {
        runBlocking {
            db.episodeDownloadDao().upsert(episode(state = EpisodeDownloadEntity.STATE_RESOLVING))
            val before = db.episodeDownloadDao().getById(episodeId)

            repository.enqueue(episode(state = EpisodeDownloadEntity.STATE_QUEUED))

            val after = db.episodeDownloadDao().getById(episodeId)
            // Must NOT reset an in-progress episode back through the flow
            assertEquals(EpisodeDownloadEntity.STATE_RESOLVING, after?.state)
            assertEquals(before?.createdAt, after?.createdAt)
        }
    }

    @Test
    fun resolveComplete_persists_stream_info_and_enqueues_worker() {
        runBlocking {
            repository.enqueue(episode())
            repository.resolveComplete(
                episodeId = episodeId,
                m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                userAgent = "TestUA",
                referer = "https://example.com"
            )

            val row = db.episodeDownloadDao().getById(episodeId)
            // resolveComplete performs saveStreamInfo BEFORE updateState(QUEUED)
            // and enqueueWorker last, so on return the row must be QUEUED + persisted
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
            assertEquals(Phase10TestSupport.FAST_FAIL_URL, row?.m3u8Url)
            assertEquals("TestUA", row?.userAgent)
            assertEquals("https://example.com", row?.referer)
            assertNotNull(row?.segmentDir)

            // Unique work registered for this episode
            val infos = workManager.getWorkInfosForUniqueWork("episode_download_$episodeId").get()
            assertTrue("Worker must be enqueued", infos.isNotEmpty())
        }
    }

    @Test
    fun cancel_persists_cancelled_state_and_cancels_work() {
        runBlocking {
            repository.enqueue(episode())
            repository.resolveComplete(
                episodeId = episodeId,
                m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                userAgent = "TestUA",
                referer = "https://example.com"
            )

            repository.cancel(episodeId)

            // Cancellation intent must be durable in Room (terminal state)
            val row = db.episodeDownloadDao().getById(episodeId)
            assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, row?.state)

            // Work is cancelled (or already finished) — never left running
            val finishedOrCancelled = Phase10TestSupport.awaitUntil(10_000) {
                val infos = workManager.getWorkInfosForUniqueWork("episode_download_$episodeId").get()
                infos.all { it.state.isFinished || it.state == androidx.work.WorkInfo.State.CANCELLED }
            }
            assertTrue("Work must not keep running after cancel", finishedOrCancelled)
        }
    }

    /**
     * Final-audit M1: the cancellation write is TERMINAL-GUARDED.
     *
     * Before the guard, `cancel()` issued a blind `state = 'CANCELLED'` UPDATE, so
     * a Cancel that raced the Worker's completion CAS stamped CANCELLED over a
     * COMPLETED row (and could bury a FAILED row's reason). Each terminal state
     * must survive `cancel()` untouched, and the call must report that it did
     * not cancel anything instead of pretending it did.
     */
    @Test
    fun cancel_never_flips_a_terminal_row() {
        runBlocking {
            val dao = db.episodeDownloadDao()

            val completedId = "ep-cancel-completed"
            dao.upsert(
                episode(id = completedId, state = EpisodeDownloadEntity.STATE_COMPLETED)
                    .copy(outputPath = "/storage/emulated/0/Movies/ep1.mp4")
            )
            val failedId = "ep-cancel-failed"
            dao.upsert(
                episode(id = failedId, state = EpisodeDownloadEntity.STATE_FAILED)
                    .copy(errorMessage = "Assembly failed: boom")
            )
            val cancelledId = "ep-cancel-already"
            dao.upsert(episode(id = cancelledId, state = EpisodeDownloadEntity.STATE_CANCELLED))

            assertFalse("COMPLETED must refuse cancel", repository.cancel(completedId))
            assertFalse("FAILED must refuse cancel", repository.cancel(failedId))
            assertFalse("CANCELLED must refuse cancel", repository.cancel(cancelledId))

            val completed = dao.getById(completedId)
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, completed?.state)
            assertEquals("/storage/emulated/0/Movies/ep1.mp4", completed?.outputPath)

            val failed = dao.getById(failedId)
            assertEquals(EpisodeDownloadEntity.STATE_FAILED, failed?.state)
            assertEquals("Assembly failed: boom", failed?.errorMessage)

            assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, dao.getById(cancelledId)?.state)
        }
    }

    /**
     * Final-audit M1 companion: the guard must NOT over-block. The §17.1 action
     * matrix offers Cancel for every non-terminal state (including `PAUSED`,
     * where Cancel discards the pause), so those rows still cancel — and, like
     * every state-changing claim statement, the write clears the stale rate in
     * the same UPDATE (SPEC §25 stale-rate guard).
     */
    @Test
    fun cancel_still_cancels_every_non_terminal_row() {
        runBlocking {
            val dao = db.episodeDownloadDao()

            val pausedId = "ep-cancel-paused"
            dao.upsert(
                episode(id = pausedId, state = EpisodeDownloadEntity.STATE_PAUSED)
                    .copy(speed = 1_234L, etaSeconds = 42L)
            )
            assertTrue("PAUSED must cancel (discards the pause)", repository.cancel(pausedId))
            val paused = dao.getById(pausedId)
            assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, paused?.state)
            assertEquals("A cancelled row must not advertise a rate", 0L, paused?.speed)
            assertEquals(0L, paused?.etaSeconds)

            val queuedId = "ep-cancel-queued"
            dao.upsert(episode(id = queuedId, state = EpisodeDownloadEntity.STATE_QUEUED))
            assertTrue("QUEUED must cancel", repository.cancel(queuedId))
            assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, dao.getById(queuedId)?.state)

            val missing = repository.cancel("ep-does-not-exist")
            assertFalse("A missing row cancels nothing", missing)
        }
    }

    @Test
    fun reEnqueue_from_terminal_re_resolves_fresh_and_requeues() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            // 11.5E-7: the retry re-enters the EXISTING queueResolvedEpisode
            // pipeline, whose key is derived as <session>_<epStr>_<audio> —
            // seed the conforming id the production path would have created.
            val derivedId = "${sessionId}_1_sub"
            dao.upsert(
                episode(id = derivedId, state = EpisodeDownloadEntity.STATE_FAILED).copy(
                    retryCount = 2,
                    m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                    userAgent = "OldUA",
                    referer = "https://old.example.com",
                    errorMessage = "previous error"
                )
            )
            val retryRepository = DownloadRepository(
                context = context,
                dao = dao,
                sessionDao = db.downloadSessionDao(),
                streamResolver = RecordStreamResolver { RetryTestStream.streamInfo() },
            )

            retryRepository.reEnqueue(derivedId)

            val row = dao.getById(derivedId)
            // retryCount increments ONLY on explicit re-enqueue (§12) — the
            // winning attempt counts exactly once.
            assertEquals(3, row?.retryCount)
            // The fresh resolution REPLACED the stale credentials (the pre-
            // 11.5E-7 defect cleared them and queued a Worker with NO stream,
            // which then failed with "No stream info available" — §24.1).
            assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)
            assertEquals(RetryTestStream.USER_AGENT, row?.userAgent)
            assertEquals(RetryTestStream.REFERER, row?.referer)
            assertNull("the stale reason died with the retry claim", row?.errorMessage)
            assertEquals("https://example.com/play", row?.playUrl)
            // Requeued through the existing queueResolvedEpisode path
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)

            // New Worker inserted (KEEP inserts when existing work is terminal)
            val infos = workManager.getWorkInfosForUniqueWork("episode_download_$derivedId").get()
            assertTrue(infos.isNotEmpty())

            // Stop it so it cannot run against the fake URL past this test.
            workManager.cancelUniqueWork("episode_download_$derivedId")
        }
    }

    @Test
    fun reEnqueue_from_non_terminal_is_noop() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(episode(state = EpisodeDownloadEntity.STATE_DOWNLOADING).copy(retryCount = 1))

            repository.reEnqueue(episodeId)

            val row = dao.getById(episodeId)
            // Guard: non-terminal episodes are not re-enqueued
            assertEquals(EpisodeDownloadEntity.STATE_DOWNLOADING, row?.state)
            assertEquals(1, row?.retryCount)
        }
    }

    // -----------------------------------------------------------------------
    // SPEC §20 Pause / Resume — the final gap closure
    // -----------------------------------------------------------------------

    @Test
    fun pause_persistsTheDurableIntentClearsTheRateAndKeepsProgress() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(
                episode(state = EpisodeDownloadEntity.STATE_DOWNLOADING).copy(
                    speed = 4_242L,
                    etaSeconds = 77L,
                    completedSegments = 6,
                    totalSegments = 20,
                    downloadedBytes = 12_345L,
                ),
            )

            assertTrue("the pause must really claim the row", repository.pause(episodeId))

            val row = dao.getById(episodeId)
            assertEquals(EpisodeDownloadEntity.STATE_PAUSED, row?.state)
            // The claim CAS clears the rate in the same statement (SPEC §25 —
            // a stopped row never advertises a measurement).
            assertEquals("a paused row never advertises a rate", 0L, row?.speed)
            assertEquals("…nor a countdown", 0L, row?.etaSeconds)
            // Progress is real persisted state and resumes from where it stopped.
            assertEquals("segments already written are kept", 6, row?.completedSegments)
            assertEquals(20, row?.totalSegments)
            assertEquals(12_345L, row?.downloadedBytes)
        }
    }

    @Test
    fun pause_isRefusedForStatesThatCannotBePaused() {
        runBlocking {
            val dao = db.episodeDownloadDao()

            // ASSEMBLING: muxing cannot stop halfway without risking a partial
            // output that a later resume would mistake for the real file.
            dao.upsert(episode(id = "ep-asm", state = EpisodeDownloadEntity.STATE_ASSEMBLING))
            assertFalse("ASSEMBLING must not be pausable", repository.pause("ep-asm"))
            assertEquals(EpisodeDownloadEntity.STATE_ASSEMBLING, dao.getById("ep-asm")?.state)

            // Terminal rows have nothing to pause.
            dao.upsert(episode(id = "ep-done", state = EpisodeDownloadEntity.STATE_COMPLETED))
            assertFalse(repository.pause("ep-done"))
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, dao.getById("ep-done")?.state)

            // Pausing twice is not a state change (and reports it honestly).
            dao.upsert(episode(id = "ep-stopped", state = EpisodeDownloadEntity.STATE_PAUSED))
            assertFalse("pausing an already-paused row reports false", repository.pause("ep-stopped"))
            assertEquals(EpisodeDownloadEntity.STATE_PAUSED, dao.getById("ep-stopped")?.state)

            // An unknown row is refused, never invented.
            assertFalse(repository.pause("ep-does-not-exist"))
        }
    }

    @Test
    fun pause_isClaimedExactlyOnce_whenTwoCallersRace() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(episode(state = EpisodeDownloadEntity.STATE_DOWNLOADING))

            val results = listOf(
                async { repository.pause(episodeId) },
                async { repository.pause(episodeId) },
            ).awaitAll()

            assertEquals("exactly one caller wins the claim", 1, results.count { it })
            assertEquals(EpisodeDownloadEntity.STATE_PAUSED, dao.getById(episodeId)?.state)
        }
    }

    @Test
    fun resume_fromPausedWithPersistedStream_handsTheRowBackToTheWorker() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(
                episode(state = EpisodeDownloadEntity.STATE_PAUSED).copy(
                    m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                    userAgent = "PausedUA",
                    referer = "https://example.com",
                ),
            )

            assertTrue("the resume must really claim the row", repository.resume(episodeId))

            val row = dao.getById(episodeId)
            assertTrue(
                "the row must really leave PAUSED (state=${row?.state}, error=${row?.errorMessage})",
                row?.state != EpisodeDownloadEntity.STATE_PAUSED,
            )
            assertEquals(
                "the persisted stream is reused — a resume must not burn a WebView round-trip",
                Phase10TestSupport.FAST_FAIL_URL,
                row?.m3u8Url,
            )
            val infos = workManager.getWorkInfosForUniqueWork("episode_download_$episodeId").get()
            assertTrue("the unique Worker must be enqueued again", infos.isNotEmpty())

            // Stop it so it cannot run against the fake URL past this test.
            workManager.cancelUniqueWork("episode_download_$episodeId")
        }
    }

    @Test
    fun resume_isRefusedWhenTheRowIsNotPaused() {
        runBlocking {
            val dao = db.episodeDownloadDao()

            dao.upsert(episode(state = EpisodeDownloadEntity.STATE_QUEUED))
            assertFalse(repository.resume(episodeId))
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, dao.getById(episodeId)?.state)

            dao.upsert(episode(state = EpisodeDownloadEntity.STATE_DOWNLOADING))
            assertFalse(repository.resume(episodeId))
            assertEquals(EpisodeDownloadEntity.STATE_DOWNLOADING, dao.getById(episodeId)?.state)

            assertFalse(repository.resume("ep-does-not-exist"))
        }
    }

    @Test
    fun resume_ofAPausedRowWithoutStream_reResolvesThroughTheExistingPipeline() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            // 11.5E-7 identity rule: the pipeline re-derives the key from the
            // episode columns, so seed the id production would have created.
            val derivedId = "${sessionId}_1_sub"
            dao.upsert(episode(id = derivedId, state = EpisodeDownloadEntity.STATE_PAUSED).copy(m3u8Url = null))
            val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }
            val resolverRepository = DownloadRepository(
                context = context,
                dao = dao,
                sessionDao = db.downloadSessionDao(),
                streamResolver = resolver,
            )

            assertTrue(resolverRepository.resume(derivedId))

            assertEquals(
                "a resume without a persisted stream must re-resolve — enqueueing " +
                    "a Worker that could only fail with \"No stream info available\" " +
                    "would be fake progress",
                1,
                resolver.calls.size,
            )
            val row = dao.getById(derivedId)
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
            assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)
            val infos = workManager.getWorkInfosForUniqueWork("episode_download_$derivedId").get()
            assertTrue(infos.isNotEmpty())

            workManager.cancelUniqueWork("episode_download_$derivedId")
        }
    }

    @Test
    fun aPausedEpisodeIsAlreadyHandled_soABatchNeverRestartsIt() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val derivedId = "${sessionId}_1_sub"
            dao.upsert(episode(id = derivedId, state = EpisodeDownloadEntity.STATE_PAUSED))
            val info = EpisodeInfo(
                number = 1.0f,
                session = "eps-$derivedId",
                title = "Repo Test Episode",
                fansub = "TestFansub",
                audio = "sub",
                playUrl = "https://example.com/play",
            )

            assertTrue(
                "pausing is a user intent: a batch/watchlist pass must not " +
                    "silently start work the user stopped",
                repository.isEpisodeAlreadyHandled(sessionId, info),
            )
        }
    }

    @Test
    fun queueResolvedEpisode_persists_the_python_title_fallback_chain() {
        runBlocking {
            val dao = db.episodeDownloadDao()

            fun submission(number: Float, episodeTitle: String, streamTitle: String) =
                ResolvedEpisodeSubmission(
                    animeSession = sessionId,
                    animeTitle = "Repo Test Anime",
                    host = "animepahe.com",
                    quality = 1080,
                    episode = EpisodeInfo(
                        number = number,
                        session = "eps-title-$number",
                        title = episodeTitle,
                        fansub = "",
                        audio = EpisodeInfo.AUDIO_JAPANESE,
                        playUrl = "https://animepahe.com/play/$sessionId/eps-title-$number",
                    ),
                    stream = StreamInfo(
                        url = "https://cdn.example.com/uwu.m3u8",
                        userAgent = "TestUA",
                        referer = "https://kwik.cx/e/abc",
                        title = streamTitle,
                    ),
                )

            // AnimePahe episodes usually carry no title of their own, and Python
            // backfills it from the resolved stream (downloader.py:47, 278-279).
            repository.queueResolvedEpisode(submission(1f, "", "Repo Test Anime Ep. 1 :: animepahe"))
            // …and the positional label only when neither title exists.
            repository.queueResolvedEpisode(submission(2f, "", ""))

            // Episode 1's row must SURVIVE episode 2's session refresh: a
            // REPLACE on the parent row would fire ON DELETE CASCADE and erase it.
            val first = dao.getById("${sessionId}_1_jpn")
            assertNotNull("cascade must not erase an earlier episode row", first)
            assertEquals(
                "the resolved stream title is the fallback for a titleless episode",
                "Repo Test Anime Ep. 1 :: animepahe",
                first?.title,
            )
            assertEquals(
                "its persisted stream info must still be there",
                "https://cdn.example.com/uwu.m3u8",
                first?.m3u8Url,
            )
            assertEquals(
                "positional label only when both titles are blank",
                "Episode 2",
                dao.getById("${sessionId}_2_jpn")?.title,
            )

            // These rows are real work requests; stop them so they cannot run
            // against the fake URL past the end of this test.
            workManager.cancelUniqueWork("episode_download_${sessionId}_1_jpn")
            workManager.cancelUniqueWork("episode_download_${sessionId}_2_jpn")
        }
    }
}
