package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.RecordStreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resume behavior.
 * Phase 10 architecture v10 §6, §11, §16 (ResumeTest).
 *
 * EMULATOR-VERIFIED:
 * - A FAILED episode can be re-enqueued: 11.5E-7 re-runs a FRESH resolution
 *   (injected fake resolver), persists the fresh stream, increments
 *   retryCount exactly once and inserts a new Worker after terminal work.
 * - Reconciliation resumes a stranded DOWNLOADING episode (process-death
 *   resume path) — QUEUED + Worker re-enqueued (unchanged, seed carries a
 *   stream URL).
 *
 * NOTE (11.5E-7): the pre-11.5E-7 defect made the resumed Worker fail
 * deterministically with "No stream info available" because reEnqueue cleared
 * the stream and never re-resolved (§24.1). The resumed Worker now receives
 * the FRESH stream — this test asserts its failure is a REAL transport
 * failure (loopback fast-fail URL), never "No stream info available".
 */
@RunWith(AndroidJUnit4::class)
class ResumeTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-resume-test"

    /**
     * 11.5E-7: the retry re-enters the EXISTING queueResolvedEpisode pipeline,
     * whose key is `<session>_<epStr>_<audio>` (episode 1, audio "sub").
     */
    private val episodeId = "${sessionId}_1_sub"
    private val uniqueName = "episode_download_$episodeId"

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
        workManager.cancelUniqueWork(uniqueName)
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    private fun seed(state: String, retryCount: Int = 0) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Resume Test Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = state,
        retryCount = retryCount,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
        userAgent = "OldUA",
        referer = "https://old.example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId)
    )

    @Test
    fun resume_failed_episode_requeues_and_runs_worker_again() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(seed(EpisodeDownloadEntity.STATE_FAILED, retryCount = 1))

            // Terminal work exists so KEEP-insert-after-terminal is exercised
            repository.enqueueWorker(episodeId)
            val firstRunFinished = Phase10TestSupport.awaitUntil(60_000) {
                val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                infos.isNotEmpty() && infos.all { it.state.isFinished }
            }
            // First run: stream info present → Worker attempts and fails (loopback)
            assertTrue("First Worker run must finish", firstRunFinished)

            // 11.5E-7: explicit resume/retry performs a FRESH resolution and
            // persists its result (fake resolver returning the loopback
            // fast-fail URL so the resumed run fails deterministically too).
            val retryRepository = DownloadRepository(
                context = context,
                dao = dao,
                sessionDao = db.downloadSessionDao(),
                streamResolver = RecordStreamResolver {
                    StreamInfo(
                        url = Phase10TestSupport.FAST_FAIL_URL,
                        userAgent = "ResumeRetryUA",
                        referer = "https://kwik.cx/e/resume-retry",
                    )
                },
            )
            retryRepository.reEnqueue(episodeId)

            val row = dao.getById(episodeId)
            assertEquals("retryCount increments only on re-enqueue", 2, row?.retryCount)
            // Fresh credentials REPLACED the stale ones — the resumed Worker
            // is given a real stream (the §24.1 defect left these null).
            assertEquals(Phase10TestSupport.FAST_FAIL_URL, row?.m3u8Url)
            assertEquals("ResumeRetryUA", row?.userAgent)
            assertEquals("https://kwik.cx/e/resume-retry", row?.referer)
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)

            // New Worker inserted (previous chain terminal → KEEP inserts)
            val resumed = Phase10TestSupport.awaitUntil(15_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .any { !it.state.isFinished }
            }
            assertTrue("Resume must enqueue a new Worker", resumed)

            // Resumed Worker runs with the FRESH stream and fails as a REAL
            // transport failure (loopback fast-fail) after its bounded
            // internal retries — never "No stream info available".
            val resumedFinished = Phase10TestSupport.awaitUntil(90_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .all { it.state.isFinished }
            }
            assertTrue(resumedFinished)
            val finalRow = dao.getById(episodeId)
            assertEquals(EpisodeDownloadEntity.STATE_FAILED, finalRow?.state)
            assertNotNull("real transport failure must be reported", finalRow?.errorMessage)
            assertNotEquals(
                "the resumed Worker MUST have received a stream (§24.1 fix)",
                "No stream info available",
                finalRow?.errorMessage,
            )
            // retryCount unchanged by Worker failure (only reEnqueue increments)
            assertEquals(2, finalRow?.retryCount)
        }
    }

    @Test
    fun resume_after_stranded_downloading_via_reconciliation() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            // Stranded DOWNLOADING row, no Worker (process death leftover)
            dao.upsert(seed(EpisodeDownloadEntity.STATE_DOWNLOADING))

            val reconciler = DownloadStateReconciler(dao, workManager)
            reconciler.reconcile()

            // Resume path: atomic DOWNLOADING → QUEUED + re-enqueue
            val requeued = Phase10TestSupport.awaitUntil(15_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .any { !it.state.isFinished }
            }
            assertTrue("Reconciliation must resume the stranded download", requeued)
        }
    }
}
