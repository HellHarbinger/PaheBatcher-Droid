package com.pahebatcher.app.worker

import android.content.Context
import android.app.NotificationManager
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.pahebatcher.app.data.download.DownloadConfig
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.support.Phase10TestSupport
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 11.5 item (e) — reviewer findings M-5 and L-8 (LIMITATIONS §27.1/§27.3).
 *
 * M-5: the Worker entry transition is an atomic CAS claim and every failure write
 * is CAS-guarded, so a stale Worker can never overturn a newer terminal state and
 * a Cancel that lands mid-run is never converted into FAILED. All four tests
 * assert *observable Room state*, never the internal helper that produced it.
 *
 * L-8: a CancellationException raised while the Worker is entering the foreground
 * must propagate out of [DownloadWorker.doWork] (a cancelled run, not a returned
 * failure Result) and must leave a terminal CANCELLED row — never FAILED.
 *
 * Runs against the REAL Room database singleton and the REAL application
 * WorkManager (custom DownloadWorkerFactory registered by PaheBatcherApplication)
 * except for the seam tests, which build the Worker directly through
 * `TestListenableWorkerBuilder` (androidx.work:work-testing) because they must
 * inject a DAO/foreground seam. Test doubles live here only — never in production.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWorkerStateGuardTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-worker-guard-$runStamp"
    private val staleId = "ep-guard-stale-$runStamp"
    private val assemblingId = "ep-guard-assembling-$runStamp"
    private val downloadingId = "ep-guard-downloading-$runStamp"
    private val racingId = "ep-guard-racing-$runStamp"
    private val cancellingId = "ep-guard-cancelling-$runStamp"
    private val allEpisodeIds = listOf(staleId, assemblingId, downloadingId, racingId, cancellingId)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        allEpisodeIds.forEach { workManager.cancelUniqueWork("episode_download_$it") }
        allEpisodeIds.forEach { File(context.filesDir, "downloads/$it").deleteRecursively() }
        // never leave a terminal notification behind on the device.
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        allEpisodeIds.forEach {
            notificationManager.cancel(DownloadNotificationManager(context).terminalIdFor(it))
        }
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    private fun seed(
        id: String,
        state: String,
        m3u8Url: String?,
        errorMessage: String? = "previous failure",
    ) = EpisodeDownloadEntity(
        episodeId = id,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$id",
        title = "State Guard Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://animepahe.com/play/$sessionId/eps-$id",
        state = state,
        retryCount = 1,
        errorMessage = errorMessage,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        m3u8Url = m3u8Url,
        userAgent = "GuardUA",
        referer = "https://example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, id),
    )

    private fun workInfos(id: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork("episode_download_$id").get()

    private fun allFinished(id: String): Boolean {
        val infos = workInfos(id)
        return infos.isNotEmpty() && infos.all { it.state.isFinished }
    }

    // ── M-5 #1: a stale Worker must not touch a terminal row ─────────────────

    @Test
    fun stale_worker_leaves_a_cancelled_row_completely_untouched() = runBlocking {
        val dao = db.episodeDownloadDao()
        // A fully resolved row the user already cancelled: WorkManager must still
        // really start a Worker for the still-existing unique work request.
        val seeded = seed(staleId, EpisodeDownloadEntity.STATE_CANCELLED, Phase10TestSupport.FAST_FAIL_URL)
        dao.upsert(seeded)

        // Reviewer m1: the assertions below must not be vacuous — a Worker that
        // never reached the claim (e.g. deferred by the maxParallel gate) would
        // leave the row untouched too. The recording seam proves the claim path
        // executed AND lost (every attempted CAS is recorded and none of them
        // mutated the row).
        val recorder = ClaimRecordingDao(dao)
        val worker = TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
            .setInputData(workDataOf("episode_id" to staleId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = DownloadWorker(
                    context = appContext,
                    params = workerParameters,
                    dao = recorder,
                    notificationManager = DownloadNotificationManager(appContext),
                    hlsClient = com.pahebatcher.app.data.hls.OkHttpHlsClient(),
                    keyCache = com.pahebatcher.app.data.hls.AesKeyCache(),
                    config = DownloadConfig(),
                )
            })
            .build()

        val result = worker.startWork().get(90, TimeUnit.SECONDS)
        assertNotNull("the Worker must produce a result", result)
        assertEquals(
            "a claim-loser must exit exactly like the maxParallel deferral",
            ListenableWorker.Result.success(),
            result,
        )
        assertEquals(
            "the claim path must have RUN (all three entry states attempted, in order) — " +
                "otherwise the untouched row below proves nothing",
            listOf(
                EpisodeDownloadEntity.STATE_QUEUED,
                EpisodeDownloadEntity.STATE_DOWNLOADING,
                EpisodeDownloadEntity.STATE_ASSEMBLING,
            ),
            recorder.claimAttempts.map { it.second },
        )
        assertTrue(
            "every recorded CAS attempt must have targeted this episode",
            recorder.claimAttempts.all { it.first == staleId },
        )

        val after = dao.getById(staleId)
        assertNotNull("row must still exist", after)
        assertEquals(
            "a stale Worker must never resurrect a CANCELLED row",
            EpisodeDownloadEntity.STATE_CANCELLED,
            after!!.state,
        )
        assertEquals(
            "the stale Worker must not write any failure (it never owned the row)",
            seeded.errorMessage,
            after.errorMessage,
        )
        assertEquals(
            "a claim-loser must produce ZERO mutation of the row",
            seeded,
            after,
        )
    }

    // ── M-5 #2: the legitimate ASSEMBLING entry the reconciler hands out ─────

    @Test
    fun entry_claim_accepts_the_assembling_state_and_the_worker_runs() = runBlocking {
        val dao = db.episodeDownloadDao()
        // DownloadStateReconciler re-enqueues an incomplete ASSEMBLING row WITHOUT
        // changing its state, so ASSEMBLING must be a claimable entry state —
        // otherwise assembly can never resume (the row would stay ASSEMBLING forever).
        dao.upsert(seed(assemblingId, EpisodeDownloadEntity.STATE_ASSEMBLING, Phase10TestSupport.FAST_FAIL_URL))

        repository.enqueueWorker(assemblingId)

        assertTrue("the Worker must run to completion", Phase10TestSupport.awaitUntil(90_000) {
            allFinished(assemblingId)
        })

        val after = dao.getById(assemblingId)
        assertNotNull(after)
        assertEquals(
            "the Worker must have claimed the ASSEMBLING row and attempted the download " +
                "(an unclaimed run would leave the row untouched in ASSEMBLING)",
            EpisodeDownloadEntity.STATE_FAILED,
            after!!.state,
        )
        assertNotNull("the honest transport failure must be reported", after.errorMessage)
        assertNotEquals(
            "the run must have used the persisted stream (not the no-stream-info branch)",
            "No stream info available",
            after.errorMessage,
        )

        // the FAILED CAS LANDED, so the failure
        // notification must be posted on its terminal (sign-bit) id — never on
        // the progress id, which is cancelled first.
        val terminalId = DownloadNotificationManager(context).terminalIdFor(assemblingId)
        assertTrue(
            "a landed terminal CAS must post the failure notification (id=$terminalId)",
            terminalId < 0 && Phase10TestSupport.awaitUntil(10_000) {
                Phase10TestSupport.activeNotificationIds(context).contains(terminalId)
            },
        )
    }

    // ── M-5 #2b: the DOWNLOADING entry left by process death (reviewer m2) ───

    @Test
    fun entry_claim_accepts_the_downloading_state_left_by_process_death() = runBlocking {
        val dao = db.episodeDownloadDao()
        // Process death leaves the row in DOWNLOADING. This test enqueues the
        // Worker DIRECTLY (no reconciler pass in between) so the claim's
        // DOWNLOADING branch is what must accept the entry — with it rejected the
        // row would stay DOWNLOADING forever (the vacuous outcome this test
        // forbids by asserting the honest FAILED result of a real run).
        dao.upsert(seed(downloadingId, EpisodeDownloadEntity.STATE_DOWNLOADING, Phase10TestSupport.FAST_FAIL_URL))

        repository.enqueueWorker(downloadingId)

        assertTrue("the Worker must run to completion", Phase10TestSupport.awaitUntil(90_000) {
            allFinished(downloadingId)
        })

        val after = dao.getById(downloadingId)
        assertNotNull(after)
        assertEquals(
            "the Worker must have claimed the DOWNLOADING row and run the pipeline " +
                "(an unclaimed run would strand the row in DOWNLOADING)",
            EpisodeDownloadEntity.STATE_FAILED,
            after!!.state,
        )
        assertNotNull("the honest transport failure must be reported", after.errorMessage)
        assertNotEquals(
            "the run must have used the persisted stream (not the no-stream-info branch)",
            "No stream info available",
            after.errorMessage,
        )
    }

    // ── M-5 #3: a Cancel landing right before the failure write must win ─────

    @Test
    fun failure_write_refuses_to_convert_a_concurrent_cancel_into_failed() = runBlocking {
        val dao = db.episodeDownloadDao()
        // No pre-existing error: if the guarded write were unguarded, the Worker's
        // own failure message would appear here after the forced Cancel.
        dao.upsert(seed(racingId, EpisodeDownloadEntity.STATE_QUEUED, Phase10TestSupport.FAST_FAIL_URL, errorMessage = null))

        val worker = TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
            .setInputData(workDataOf("episode_id" to racingId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = DownloadWorker(
                    context = appContext,
                    params = workerParameters,
                    // Seam: every failure write is preceded (by this test) by the user's
                    // Cancel, i.e. the exact race M-5 guards against is forced, not timed.
                    dao = CancelRacingDao(db.episodeDownloadDao()),
                    notificationManager = DownloadNotificationManager(appContext),
                    hlsClient = com.pahebatcher.app.data.hls.OkHttpHlsClient(),
                    keyCache = com.pahebatcher.app.data.hls.AesKeyCache(),
                    config = DownloadConfig(),
                )
            })
            .build()

        val result = worker.startWork().get(90, TimeUnit.SECONDS)
        assertNotNull("the Worker must produce a result", result)

        val after = dao.getById(racingId)
        assertNotNull(after)
        assertEquals(
            "a Cancel that lands before the Worker's failure write must win",
            EpisodeDownloadEntity.STATE_CANCELLED,
            after!!.state,
        )
        assertNull(
            "the guarded failure write must have been refused (no FAILED, no error message)",
            after.errorMessage,
        )

        // the terminal CAS was REFUSED, so NO
        // failure notification may be posted — a newer state (the user's Cancel)
        // won, and this run no longer owns the row.
        assertFalse(
            "a refused terminal CAS must post NO failure notification",
            Phase10TestSupport.activeNotificationIds(context)
                .contains(DownloadNotificationManager(context).terminalIdFor(racingId)),
        )
    }

    // ── L-8: CancellationException must propagate, never become a failure ────

    @Test
    fun cancellation_during_foreground_setup_propagates_and_leaves_no_failed_row() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(
                seed(
                    cancellingId,
                    EpisodeDownloadEntity.STATE_QUEUED,
                    Phase10TestSupport.FAST_FAIL_URL,
                    errorMessage = null,
                ),
            )

            val foreground = BlockingForegroundUpdater()
            val worker = TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
                .setInputData(workDataOf("episode_id" to cancellingId))
                .setWorkerFactory(
                    DownloadWorkerFactory(db, DownloadNotificationManager(context), DownloadConfig()),
                )
                .setForegroundUpdater(foreground)
                .build()

            // Record WHAT the run produced: a Result means the cancellation was
            // swallowed and converted into an ordinary outcome; a
            // CancellationException means it propagated. Reviewer m5: capture ANY
            // Throwable (not only CancellationException) so an unexpected
            // IllegalStateException/SQLiteException abort is reported verbatim in
            // the assertion message instead of dying silently in the launch.
            val outcome = AtomicReference<Any?>()
            val job = launch(Dispatchers.Default) {
                outcome.set(
                    try {
                        worker.doWork()
                    } catch (t: Throwable) {
                        t
                    },
                )
            }

            assertTrue(
                "the Worker must reach setForeground()",
                foreground.entered.await(10, TimeUnit.SECONDS),
            )
            assertTrue(
                "the entry claim must have landed before foreground setup",
                Phase10TestSupport.awaitUntil(5_000) {
                    kotlinx.coroutines.runBlocking {
                        dao.getById(cancellingId)?.state == EpisodeDownloadEntity.STATE_DOWNLOADING
                    }
                },
            )

            job.cancelAndJoin()

            val produced = outcome.get()
            assertTrue(
                "doWork must PROPAGATE CancellationException instead of returning an " +
                    "ordinary Result (got: $produced)",
                produced is CancellationException,
            )

            val after = dao.getById(cancellingId)
            assertNotNull(after)
            assertEquals(
                "the cancellation cleanup must persist the terminal CANCELLED state",
                EpisodeDownloadEntity.STATE_CANCELLED,
                after!!.state,
            )
            assertNull(
                "a cancellation must never be recorded as a failure",
                after.errorMessage,
            )
        }
    }

    /**
     * DAO seam for the M-5 stale-Worker test: it only RECORDS the CAS attempts
     * `claimEntryState()` makes and delegates everything else, so the test can
     * prove the claim path actually executed (and was refused three times)
     * instead of inferring it from a row that stayed untouched for any other
     * reason (reviewer m1). It never changes any write.
     *
     * Records through the run-owned claim statement
     * (`claimEntryRunWithRunId`, the pause/resume race fix) — that is the ONE
     * statement the entry claim issues since the run-ownership token landed.
     */
    private class ClaimRecordingDao(
        private val delegate: EpisodeDownloadDao,
    ) : EpisodeDownloadDao by delegate {

        val claimAttempts = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun claimEntryRunWithRunId(
            episodeId: String,
            expectedState: String,
            runId: String,
            timestamp: Long,
        ): Int {
            claimAttempts.add(episodeId to expectedState)
            return delegate.claimEntryRunWithRunId(episodeId, expectedState, runId, timestamp)
        }
    }

    /**
     * DAO seam for the M-5 race test: it replays "the user tapped Cancel in the
     * instant BEFORE the Worker's own write", then delegates. With the unguarded
     * `updateError` the row ends FAILED (test fails); with the run-owned
     * guarded write (`updateErrorIfOwned(expectedState = DOWNLOADING, runId)`)
     * the write is refused and the row stays CANCELLED.
     */
    private class CancelRacingDao(
        private val delegate: EpisodeDownloadDao,
    ) : EpisodeDownloadDao by delegate {

        private suspend fun cancelFirst(episodeId: String) {
            delegate.updateState(episodeId, EpisodeDownloadEntity.STATE_CANCELLED)
        }

        override suspend fun updateError(episodeId: String, errorMessage: String, timestamp: Long) {
            cancelFirst(episodeId)
            delegate.updateError(episodeId, errorMessage, timestamp)
        }

        override suspend fun updateErrorIfOwned(
            episodeId: String,
            expectedState: String,
            errorMessage: String,
            runId: String,
            timestamp: Long,
        ): Int {
            cancelFirst(episodeId)
            return delegate.updateErrorIfOwned(episodeId, expectedState, errorMessage, runId, timestamp)
        }
    }

    /**
     * Foreground seam for the L-8 test: signals when the Worker enters
     * `setForeground()` and then never completes, so the run stays suspended there
     * until the test cancels it (the real WorkManager would keep it suspended for
     * as long as the foreground service transition takes).
     */
    private class BlockingForegroundUpdater : ForegroundUpdater {
        val entered = CountDownLatch(1)

        override fun setForegroundAsync(
            context: Context,
            id: UUID,
            foregroundInfo: ForegroundInfo,
        ): ListenableFuture<Void> {
            // Never completed — only cancellation can release the awaiting Worker.
            val blocked: ListenableFuture<Void> =
                CallbackToFutureAdapter.getFuture { "blocked-setForeground" }
            entered.countDown()
            return blocked
        }
    }
}
