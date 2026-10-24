package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.worker.DownloadWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Process-death recovery — SIMULATED harness verification.
 * Phase 10 architecture v10 §11, §16 (ProcessDeathRecoveryTest).
 *
 * IMPORTANT DISTINCTION:
 * This test SIMULATES the database state a killed process leaves behind
 * (a persisted DOWNLOADING/RESOLVING row with no running Worker) and
 * verifies that reconciliation reconstructs the correct state.
 *
 * It is NOT equivalent to killing the real application process. A real
 * process kill (force-stop) followed by relaunch reconciliation is verified
 * separately on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    // Review L-3: the attempt-count row uses a run-scoped episode id (like
    // AutoResumeConnectivityTest/TriggerNextDeferredEpisodeTest) so an ABORTED
    // prior run can never leave a stale ENQUEUED request behind a fixed unique
    // name for this run's KEEP logic to sample.
    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-pdr-test"
    private val downloadingId = "ep-pdr-downloading"
    private val resolvingId = "ep-pdr-resolving"
    private val assemblingId = "ep-pdr-assembling"
    private val attemptId = "ep-pdr-attempt-$runStamp"

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
        listOf(downloadingId, resolvingId, assemblingId, attemptId).forEach {
            workManager.cancelUniqueWork("episode_download_$it")
        }
        // the attempt-count variant flips the persisted setting —
        // ALWAYS restore the shipped default (OFF).
        runBlocking { AppConfig(context).updateAutoResume(false) }
        File(context.filesDir, "downloads/$attemptId").deleteRecursively()
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    private fun seed(id: String, state: String, withStreamInfo: Boolean) = EpisodeDownloadEntity(
        episodeId = id,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$id",
        title = "PDR Episode $id",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = state,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        m3u8Url = if (withStreamInfo) Phase10TestSupport.FAST_FAIL_URL else null,
        userAgent = if (withStreamInfo) "TestUA" else null,
        referer = if (withStreamInfo) "https://example.com" else null,
        segmentDir = Phase10TestSupport.segmentDirFor(context, id)
    )

    @Test
    fun simulated_death_during_downloading_is_reset_and_requeued() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            // Simulated leftover: row says DOWNLOADING but no Worker exists
            dao.upsert(seed(downloadingId, EpisodeDownloadEntity.STATE_DOWNLOADING, withStreamInfo = true))
            val workBefore = workManager.getWorkInfosForUniqueWork("episode_download_$downloadingId").get()
            // TEST-010: the old `isEmpty() || all { isFinished }` made the
            // real check short-circuitable and read as two conditions. One
            // falsifiable statement covers both shapes: NO recorded WorkInfo
            // may be live (a PENDING/RUNNING entry — the worker this scenario
            // must NOT have — fails here; an empty history correctly means
            // "never enqueued", exactly what the seed comment above states).
            assertTrue(
                "precondition: no live worker exists for $downloadingId",
                workBefore.all { it.state.isFinished },
            )

            val reconciler = DownloadStateReconciler(dao, workManager)
            reconciler.reconcile()

            // Atomic DOWNLOADING → QUEUED transition must have happened
            assertEquals(
                EpisodeDownloadEntity.STATE_QUEUED,
                dao.getById(downloadingId)?.state
            )
            // Worker re-enqueued (recovery)
            val requeued = Phase10TestSupport.awaitUntil(10_000) {
                workManager.getWorkInfosForUniqueWork("episode_download_$downloadingId").get()
                    .any { !it.state.isFinished }
            }
            assertTrue("Reconciliation must re-enqueue the interrupted episode", requeued)

            // Idempotent: running again does not duplicate work
            reconciler.reconcile()
            Thread.sleep(500)
            val infos = workManager.getWorkInfosForUniqueWork("episode_download_$downloadingId").get()
            assertTrue(
                "Idempotent reconcile must not create concurrent duplicates",
                infos.count { !it.state.isFinished } <= 1
            )
        }
    }

    @Test
    fun simulated_death_during_resolving_is_reset_to_queued() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(
                seed(resolvingId, EpisodeDownloadEntity.STATE_RESOLVING, withStreamInfo = false)
                    .copy(m3u8Url = null, userAgent = null, referer = null)
            )

            val reconciler = DownloadStateReconciler(dao, workManager)
            reconciler.reconcile()

            val row = dao.getById(resolvingId)
            // Resolution requires foreground WebView → reset to QUEUED
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
            assertNull(row?.m3u8Url)
        }
    }

    @Test
    fun simulated_death_during_assembling_incomplete_is_requeued() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            // segmentDir exists but contains no completed output → incomplete
            dao.upsert(seed(assemblingId, EpisodeDownloadEntity.STATE_ASSEMBLING, withStreamInfo = true))

            val reconciler = DownloadStateReconciler(dao, workManager)
            reconciler.reconcile()

            val requeued = Phase10TestSupport.awaitUntil(10_000) {
                workManager.getWorkInfosForUniqueWork("episode_download_$assemblingId").get()
                    .any { !it.state.isFinished }
            }
            assertTrue("Incomplete ASSEMBLING episode must be re-enqueued", requeued)
        }
    }

    /**
     * Phase 14 (PHASE14_PLAN §17 row "Process-death attempt-count semantics",
     * §15 "Process death and WorkManager recreation" + "Reconciler-created
     * fresh requests").
     *
     * §15's claim is that the auto-resume budget is accounted for by
     * WorkManager's OWN persisted `runAttemptCount`, so:
     *
     *  1. every `Result.retry()` return advances the count **on the same
     *     request** — a rescheduled run never silently restarts the budget;
     *  2. reconciliation of a stranded row whose request still exists leaves
     *     that request — and its count — untouched (no reset, no duplicate);
     *  3. only a genuinely NEW request (the old one is gone) starts over, and
     *     that reset is the single documented place the budget reopens.
     *
     * A REAL kill cannot be staged from inside a run: this suite executes in
     * the app's own process, so `am force-stop` would abort the instrumentation
     * (LIMITATIONS §20, which prescribes the operator-driven protocol for the
     * true kill). The WorkManager request below is nevertheless REAL — it
     * executes, fails with a TRANSPORT error and is parked by `Result.retry()`
     * — and the "process was killed" step is reproduced with this class's
     * documented technique: the persisted row + request a killed process leaves
     * behind, observed through reconciliation and WorkManager's own reschedule.
     */
    @Test
    fun rescheduledRequest_runAttemptCount_isPersistedIncrementedAndResetOnlyWhenRecreated() {
        runBlocking {
            AppConfig(context).updateAutoResume(true)
            try {
                val dao = db.episodeDownloadDao()
                val uniqueName = "episode_download_$attemptId"
                dao.upsert(
                    seed(attemptId, EpisodeDownloadEntity.STATE_QUEUED, withStreamInfo = true)
                )

                // ── 1. a REAL request executes and parks on its first retry ──
                repository.enqueueWorker(attemptId)
                assertTrue(
                    "The transport failure must park the row on WorkManager's first retry",
                    Phase10TestSupport.awaitUntil(120_000) {
                        Phase10TestSupport.isParkedForAutoResume(db, workManager, attemptId)
                    }
                )
                val first = workManager.getWorkInfosForUniqueWork(uniqueName).get().single()
                assertEquals(
                    "WorkManager reports runAttemptCount=1 once the first run has executed",
                    1,
                    first.runAttemptCount
                )

                // ── 2. relaunch-style reconciliation must not reset it ────────
                val reconciler = DownloadStateReconciler(dao, workManager)
                reconciler.reconcile()
                Thread.sleep(500)
                val kept = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                assertEquals("reconciliation must not create a duplicate request", 1, kept.size)
                assertEquals(
                    "reconciliation must keep the surviving request, not replace it",
                    first.id,
                    kept.single().id
                )
                assertEquals(
                    "a surviving request keeps its persisted runAttemptCount",
                    first.runAttemptCount,
                    kept.single().runAttemptCount
                )

                // ── 3. WorkManager's own reschedule advances the budget ──────
                // Sample only once run 2 has executed AND parked again, so the
                // request is quiescent (ENQUEUED in backoff) rather than RUNNING
                // — cancelling a RUNNING request has the worker persist terminal
                // CANCELLED to the row, which is a different contract.
                val rescheduled = Phase10TestSupport.awaitUntil(180_000) {
                    workManager.getWorkInfosForUniqueWork(uniqueName).get()
                        .singleOrNull()
                        ?.let {
                            it.runAttemptCount > first.runAttemptCount &&
                                it.state == WorkInfo.State.ENQUEUED
                        } == true
                }
                assertTrue(
                    "WorkManager must re-run the parked request with an advanced runAttemptCount " +
                        "(still ${first.runAttemptCount} after the backoff window)",
                    rescheduled
                )
                val second = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                assertEquals("still exactly one request (never duplicated)", 1, second.size)
                assertEquals(
                    "the rescheduled run reuses the SAME request",
                    first.id,
                    second.single().id
                )
                assertTrue(
                    "the rescheduled request must carry the advanced count " +
                        "(${second.single().runAttemptCount})",
                    second.single().runAttemptCount > first.runAttemptCount
                )
                assertTrue(
                    "the rescheduled request must still sit inside the auto-resume bound",
                    second.single().runAttemptCount < DownloadWorker.AUTO_RESUME_MAX_RETRIES
                )

                // ── 4. only a GONE request is recreated — and then it resets ──
                workManager.cancelUniqueWork(uniqueName)
                assertTrue(
                    "The old request must finish after cancellation",
                    Phase10TestSupport.awaitUntil(20_000) {
                        workManager.getWorkInfosForUniqueWork(uniqueName).get()
                            .let { it.isNotEmpty() && it.all { info -> info.state.isFinished } }
                    }
                )
                assertEquals(
                    "Cancelling a PARKED request must leave the row QUEUED (only the request is gone)",
                    EpisodeDownloadEntity.STATE_QUEUED,
                    dao.getById(attemptId)?.state
                )

                reconciler.reconcile()
                assertTrue(
                    "A stranded row whose request is gone must be re-enqueued",
                    Phase10TestSupport.awaitUntil(20_000) {
                        workManager.getWorkInfosForUniqueWork(uniqueName).get()
                            .any { !it.state.isFinished }
                    }
                )
                val fresh = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .first { !it.state.isFinished }
                assertNotEquals(
                    "a recreated request must be a NEW WorkManager request",
                    second.single().id,
                    fresh.id
                )
                assertTrue(
                    "a recreated request must restart the budget " +
                        "(fresh=${fresh.runAttemptCount} after the old one reached " +
                        "${second.single().runAttemptCount})",
                    fresh.runAttemptCount <= 1
                )
            } finally {
                AppConfig(context).updateAutoResume(false)
            }
        }
    }
}
