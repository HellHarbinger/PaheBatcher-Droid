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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cancellation behavior.
 * Phase 10 architecture v10 §10, §16 (CancellationTest).
 *
 * EMULATOR-VERIFIED:
 * - Cancelling during an active (RUNNING) download persists CANCELLED in Room
 * - The Worker's WorkInfo reaches CANCELLED
 * - A subsequent reconciliation does NOT resurrect a CANCELLED episode
 *   (terminal → no re-enqueue, no new Worker)
 *
 * Uses the HANGING_URL fixture to keep the Worker RUNNING deterministically:
 * since targetSdk 37 the platform default denies CLEARTEXT to it, so each
 * request fails fast (policy denial within ms) and the run stays alive purely
 * because of the in-run retry pacing (5 HTTP attempts with exponential backoff
 * per episode attempt × `auto_retry + 1` episode attempts) — see the
 * `Phase10TestSupport.HANGING_URL` KDoc (review L-1).
 */
@RunWith(AndroidJUnit4::class)
class CancellationTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    // Review L-3: run-scoped ids (pattern of AutoResumeConnectivityTest /
    // TriggerNextDeferredEpisodeTest) so an ABORTED prior run cannot leave a
    // stale ENQUEUED request behind a fixed unique name for this run's KEEP
    // logic to sample.
    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-cancel-test-$runStamp"
    private val episodeId = "ep-cancel-test-$runStamp"
    private val uniqueName = "episode_download_$episodeId"
    // Review M-1: a second, newer episode whose worker completion drives a REAL
    // deferred-trigger round while the cancelled row above is present.
    private val triggerProbeId = "ep-cancel-probe-$runStamp"

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
        workManager.cancelUniqueWork(uniqueName)
        workManager.cancelUniqueWork("episode_download_$triggerProbeId")
        Phase10TestSupport.deleteSession(db, sessionId)
        // Phase 14 (PHASE14_PLAN §17, "cancellation interaction"): the
        // auto-resume variant flips the persisted setting — ALWAYS restore the
        // shipped default (OFF) so no later suite (or the device) inherits it.
        runBlocking { AppConfig(context).updateAutoResume(false) }
    }

    @Test
    fun cancel_during_download_persists_cancelled_and_is_not_resurrected() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val now = System.currentTimeMillis()

            dao.upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = sessionId,
                    episodeNumber = 1.0f,
                    episodeSession = "eps-$episodeId",
                    title = "Cancel Test Episode",
                    fansub = "TestFansub",
                    audio = "sub",
                    playUrl = "https://example.com/play",
                    state = EpisodeDownloadEntity.STATE_QUEUED,
                    createdAt = now,
                    updatedAt = now,
                    m3u8Url = Phase10TestSupport.HANGING_URL,
                    userAgent = "TestUA",
                    referer = "https://example.com",
                    segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId)
                )
            )

            repository.enqueueWorker(episodeId)

            // Worker must be actively downloading before we cancel
            val running = Phase10TestSupport.awaitUntil(20_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }
            assertTrue("Worker must be RUNNING before cancellation", running)
            assertEquals(EpisodeDownloadEntity.STATE_DOWNLOADING, dao.getById(episodeId)?.state)

            // Cancel during download
            repository.cancel(episodeId)

            // CANCELLED must be persisted immediately (durable user intent)
            assertEquals(
                EpisodeDownloadEntity.STATE_CANCELLED,
                dao.getById(episodeId)?.state
            )

            // WorkInfo reaches CANCELLED
            val workCancelled = Phase10TestSupport.awaitUntil(20_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .all { it.state.isFinished }
            }
            assertTrue("Work must finish after cancellation", workCancelled)
            val states = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                .map { it.state }
                .toSet()
            assertTrue(
                "Work must end CANCELLED (saw: $states)",
                // 11.5E item (e), reviewer m3: tightened from `CANCELLED || FAILED`.
                // With the L-8/M1 fix the swallowed download cancellation is
                // re-propagated (NonCancellable CAS write + ensureActive rethrow),
                // so WorkManager must observe a cancellation — a FAILED outcome
                // here would mean the cancellation was still converted into an
                // ordinary failure.
                states.contains(WorkInfo.State.CANCELLED),
            )

            // Foreground notification removed after cancellation
            val notificationCleared = Phase10TestSupport.awaitUntil(10_000) {
                val notificationId = com.pahebatcher.app.notification
                    .DownloadNotificationManager(context).notificationIdFor(episodeId)
                notificationId !in Phase10TestSupport.activeDownloadNotificationIds(context)
            }
            assertTrue("Notification must be removed after cancellation", notificationCleared)

            // Reconciliation must NOT resurrect a terminal CANCELLED episode
            val reconciler = DownloadStateReconciler(dao, workManager)
            reconciler.reconcile()
            Thread.sleep(1_000)

            val row = dao.getById(episodeId)
            assertEquals(
                "CANCELLED is terminal — reconciliation must not change it",
                EpisodeDownloadEntity.STATE_CANCELLED,
                row?.state
            )
            val resurrected = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                .any { !it.state.isFinished }
            assertFalse(
                "Reconciliation must not enqueue new work for CANCELLED episode",
                resurrected
            )
        }
    }

    /**
     * Phase 14 (PHASE14_PLAN §17, "cancellation interaction"; exit criterion
     * "cancellation-during-backoff green").
     *
     * With `auto_resume` ON a transport failure parks the row back in QUEUED
     * while its request sits in WorkManager backoff (ENQUEUED, not finished).
     * A user Cancel of that parked row must be terminal:
     *
     *  - Room goes to CANCELLED immediately (durable user intent),
     *  - the parked unique work is cancelled (never runs again),
     *  - neither the reconciler nor a later trigger round resurrects it —
     *    BOTH halves are driven actively below (a real `reconcile()` call,
     *    then a real trigger round fired by a second episode's worker
     *    completion), not asserted after a sleep (review M-1).
     */
    @Test
    fun cancel_whileParkedInAutoResumeBackoff_isTerminalAndNotResurrected() {
        runBlocking {
            // The persisted setting read by the production factory's
            // autoResumeProvider at the transport-failure decision.
            AppConfig(context).updateAutoResume(true)

            try {
                val dao = db.episodeDownloadDao()
                val now = System.currentTimeMillis()
                dao.upsert(
                    EpisodeDownloadEntity(
                        episodeId = episodeId,
                        sessionId = sessionId,
                        episodeNumber = 1.0f,
                        episodeSession = "eps-$episodeId",
                        title = "Parked Cancel Episode",
                        fansub = "TestFansub",
                        audio = "sub",
                        playUrl = "https://example.com/play",
                        state = EpisodeDownloadEntity.STATE_QUEUED,
                        createdAt = now,
                        updatedAt = now,
                        // Nothing listens on loopback port 1 → real transport
                        // failure → DownloadErrorClass.TRANSPORT → auto-resume park.
                        m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                        userAgent = "TestUA",
                        referer = "https://example.com",
                        segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId)
                    )
                )

                repository.enqueueWorker(episodeId)

                // The row must PARK — back to QUEUED with its request ENQUEUED in
                // backoff — rather than terminate as FAILED.
                val parked = Phase10TestSupport.awaitUntil(120_000) {
                    Phase10TestSupport.isParkedForAutoResume(db, workManager, episodeId)
                }
                assertTrue(
                    "a transport failure with auto-resume ON must park the row in QUEUED " +
                        "with its request ENQUEUED (state=${dao.getById(episodeId)?.state}, " +
                        "work=${workManager.getWorkInfosForUniqueWork(uniqueName).get().map { it.state }})",
                    parked
                )
                assertNotEquals(
                    "the park must not be terminal FAILED",
                    EpisodeDownloadEntity.STATE_FAILED,
                    dao.getById(episodeId)?.state
                )

                // Cancel the PARKED row.
                repository.cancel(episodeId)
                assertEquals(
                    "CANCELLED must be persisted immediately (durable user intent)",
                    EpisodeDownloadEntity.STATE_CANCELLED,
                    dao.getById(episodeId)?.state
                )

                // The parked request must finish as CANCELLED, never run again.
                val workFinished = Phase10TestSupport.awaitUntil(20_000) {
                    workManager.getWorkInfosForUniqueWork(uniqueName).get()
                        .all { it.state.isFinished }
                }
                assertTrue("The parked request must finish after cancellation", workFinished)
                val states = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .map { it.state }
                    .toSet()
                assertTrue(
                    "The parked request must end CANCELLED (saw: $states)",
                    states.contains(WorkInfo.State.CANCELLED)
                )

                // Reconciliation must not resurrect the terminal row.
                val reconciler = DownloadStateReconciler(dao, workManager)
                reconciler.reconcile()
                Thread.sleep(1_000)

                assertEquals(
                    "CANCELLED is terminal — reconciliation must not change it",
                    EpisodeDownloadEntity.STATE_CANCELLED,
                    dao.getById(episodeId)?.state
                )
                assertFalse(
                    "Reconciliation must not enqueue new work for the parked CANCELLED episode",
                    workManager.getWorkInfosForUniqueWork(uniqueName).get()
                        .any { !it.state.isFinished }
                )

                // ── Active trigger round (reviewer M-1). The deferred trigger
                // fires from the completion path's `finally`, so a sleep proves
                // nothing — seed a SECOND, newer QUEUED+m3u8 episode and let ITS
                // worker run to completion (transport failure → auto-resume park
                // → `Result.retry()` → `finally` → trigger round) with this
                // CANCELLED row present as the OLDER candidate. The row contract
                // (PHASE14_PLAN §17 row 422: "no reconciler/trigger
                // resurrection") is then asserted against a round that really
                // happened.
                val snapshotIds = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .map { it.id }
                    .toSet()
                assertTrue("The cancelled row must have a request to protect", snapshotIds.isNotEmpty())

                dao.upsert(
                    EpisodeDownloadEntity(
                        episodeId = triggerProbeId,
                        sessionId = sessionId,
                        episodeNumber = 2.0f,
                        episodeSession = "eps-$triggerProbeId",
                        title = "Trigger Probe Episode",
                        fansub = "TestFansub",
                        audio = "sub",
                        playUrl = "https://example.com/play",
                        state = EpisodeDownloadEntity.STATE_QUEUED,
                        // Newer than the cancelled row → the CANCELLED row is the
                        // older candidate a widened query would pick first.
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                        userAgent = "TestUA",
                        referer = "https://example.com",
                        segmentDir = Phase10TestSupport.segmentDirFor(context, triggerProbeId)
                    )
                )
                repository.enqueueWorker(triggerProbeId)

                // The probe's worker has run and returned `Result.retry()` — its
                // WorkInfo is ENQUEUED-in-backoff, which can only happen AFTER the
                // `finally` block (and therefore the trigger round) completed.
                val probeRan = Phase10TestSupport.awaitUntil(120_000) {
                    Phase10TestSupport.isParkedForAutoResume(db, workManager, triggerProbeId)
                }
                assertTrue(
                    "The trigger-round probe must run and park — otherwise no real " +
                        "trigger round happened while the CANCELLED row was present",
                    probeRan
                )

                assertEquals(
                    "A real trigger round must not resurrect the CANCELLED row",
                    EpisodeDownloadEntity.STATE_CANCELLED,
                    dao.getById(episodeId)?.state
                )
                val after = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                assertEquals(
                    "A real trigger round must not enqueue a NEW request for the CANCELLED " +
                        "row (a resurrection would add a WorkInfo id)",
                    snapshotIds,
                    after.map { it.id }.toSet()
                )
                assertTrue(
                    "The CANCELLED row's original request must stay finished",
                    after.all { it.state.isFinished }
                )
            } finally {
                // Always restore the shipped default, even on assertion failure.
                AppConfig(context).updateAutoResume(false)
            }
        }
    }
}
