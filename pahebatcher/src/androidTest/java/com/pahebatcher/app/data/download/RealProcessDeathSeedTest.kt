package com.pahebatcher.app.data.download

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.worker.DownloadWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Protocol record written by RealProcessDeathSeedTest (protocol mode) and
 * consumed by RealProcessDeathCleanupTest (`-e verifyAttemptCount true`):
 * the pre-death {pid, request id, runAttemptCount} baseline the §15 claim is
 * asserted against (review round-2 finding H-1).
 */
private const val PD_RECORD_FILE = "real_pd_phase1.txt"

/**
 * REAL process-death verification harness.
 * Phase 10 architecture v10 §16 (ProcessDeathRecoveryTest — device track).
 *
 * This is NOT a simulated process death. Execution protocol (adb-driven):
 *
 *  1. `am instrument -e class ...RealProcessDeathSeedTest -e leaveSeed true`
 *     → seeds a QUEUED episode, enqueues a real Worker, waits until RUNNING,
 *       asserts the pre-death baseline (`runAttemptCount == 1`), RECORDS
 *       {pid, request id, count} to `filesDir/real_pd_phase1.txt`, and
 *       deliberately leaves the Worker running (grace sleep). The operator
 *       then force-stops the package WHILE the instrumentation is in that
 *       sleep — the instrumentation command fails (process died), which is
 *       the expected signal of a REAL mid-run kill (LIMITATIONS §20: the
 *       kill must come from outside the app's own process).
 *  2. The app is relaunched via `adb shell am start`. WorkManager's force-stop
 *     reschedule and/or PaheBatcherApplication reconciliation recover the
 *     stranded work. Logcat evidence is captured by the operator.
 *  3. `am instrument -e class ...RealProcessDeathCleanupTest` verifies the
 *       recovery and removes the seeded row and work. Passing
 *       `-e verifyAttemptCount true` additionally asserts the §15
 *       death-interrupted attempt-count claim (PHASE14_PLAN §15 "Process
 *       death and WorkManager recreation"): the post-relaunch run reports a
 *       DIFFERENT pid, the SAME request, and an advanced
 *       `runAttemptCount` — recorded by review round-2 finding H-1 as the
 *       step that must exist rather than be assumed.
 *
 * In a normal test-suite run (no `-e leaveSeed true`), the seed happens and is
 * then fully cleaned up inside this test, so no state leaks into subsequent
 * tests (the protocol record file is removed too). The executed protocol run
 * and its evidence are recorded in LIMITATIONS §34.11 / PARITY.md.
 */
@RunWith(AndroidJUnit4::class)
class RealProcessDeathSeedTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun seed_running_worker_then_exit_without_cleanup() {
        // Protocol mode (-e leaveSeed true): the seeded state is left behind so the
        // harness can kill the process mid-download (see class KDoc).
        // Normal suite mode: the same seed happens, then it is fully cleaned up
        // inside this test so no state leaks into subsequent tests.
        val leaveSeed = InstrumentationRegistry.getArguments()
            .getString("leaveSeed") == "true"

        runBlocking {
            val dao = db.episodeDownloadDao()
            val sessionId = "sess-real-pd"
            val episodeId = "ep-real-pd"

            // Idempotent: remove leftovers from a previous protocol run
            workManager.cancelUniqueWork("episode_download_$episodeId")
            Phase10TestSupport.deleteSession(db, sessionId)
            Phase10TestSupport.insertSession(db, sessionId)

            val now = System.currentTimeMillis()
            dao.upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = sessionId,
                    episodeNumber = 1.0f,
                    episodeSession = "eps-$episodeId",
                    title = "Real Process Death Episode",
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

            workManager.enqueueUniqueWork(
                "episode_download_$episodeId",
                androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<com.pahebatcher.app.worker.DownloadWorker>()
                    .setInputData(androidx.work.workDataOf("episode_id" to episodeId))
                    .build()
            )

            val running = Phase10TestSupport.awaitUntil(30_000) {
                workManager.getWorkInfosForUniqueWork("episode_download_$episodeId").get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }
            assertTrue("Seed Worker must be RUNNING before process death", running)

            // DOWNLOADING must be persisted BEFORE any process death could occur
            assertEquals(
                EpisodeDownloadEntity.STATE_DOWNLOADING,
                dao.getById(episodeId)?.state
            )

            // Pre-death baseline for the §15 attempt-count claim (H-1): the
            // run that is about to be killed has executed exactly once.
            val runningInfo = workManager.getWorkInfosForUniqueWork("episode_download_$episodeId")
                .get().single { it.state == WorkInfo.State.RUNNING }
            assertEquals(
                "The interrupted run must be the FIRST executed run " +
                    "(runAttemptCount=1 while RUNNING)",
                1,
                runningInfo.runAttemptCount
            )

            if (!leaveSeed) {
                // Normal suite mode: self-clean so this protocol step has no
                // side effects on other tests in the same run.
                workManager.cancelUniqueWork("episode_download_$episodeId").result.get()
                Phase10TestSupport.deleteSession(db, sessionId)
                assertNull("Suite mode must leave no seeded row", dao.getById(episodeId))
                // …and no stale protocol record from an earlier aborted run.
                File(context.filesDir, PD_RECORD_FILE).delete()
            } else {
                // Protocol mode (-e leaveSeed true): record the pre-death
                // baseline the post-relaunch assertion compares against, then
                // intentionally NO cleanup. Grace window: keeps the Worker (and
                // its foreground notification) alive while the operator issues
                // `am force-stop` — killing the instrumentation process itself,
                // which is the real mid-run death (see class KDoc step 1).
                File(context.filesDir, PD_RECORD_FILE).writeText(
                    "pid=${Process.myPid()}\n" +
                        "workId=${runningInfo.id}\n" +
                        "count=${runningInfo.runAttemptCount}\n"
                )
                Thread.sleep(30_000)
            }
        }
    }
}

/**
 * Cleanup step of the real process-death protocol (see RealProcessDeathSeedTest).
 * Skipped in normal suite runs; invoked explicitly via
 * `-e class ...RealProcessDeathCleanupTest` after relaunch verification.
 */
@RunWith(AndroidJUnit4::class)
class RealProcessDeathCleanupTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun cleanup_seeded_process_death_state() {
        // Always executes. In the adb protocol this runs AFTER relaunch
        // verification and removes the state the seed step left behind.
        // `-e verifyAttemptCount true` additionally asserts the §15
        // death-interrupted attempt-count claim against the baseline the seed
        // recorded (review round-2 finding H-1). In a normal suite run it
        // verifies no protocol state is present at all.
        val verifyAttemptCount = InstrumentationRegistry.getArguments()
            .getString("verifyAttemptCount") == "true"
        runBlocking {
            val record = File(context.filesDir, PD_RECORD_FILE)

            if (verifyAttemptCount) {
                assertTrue(
                    "The seed step must have recorded its pre-death baseline to $PD_RECORD_FILE",
                    record.exists()
                )
                val fields = record.readLines().filter { it.contains('=') }.associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to parts[1]
                }
                val prePid = fields["pid"]?.toInt()
                    ?: throw AssertionError("record is missing pid: ${record.readText()}")
                val preWorkId = fields["workId"]
                    ?: throw AssertionError("record is missing workId: ${record.readText()}")
                val preCount = fields["count"]?.toInt()
                    ?: throw AssertionError("record is missing count: ${record.readText()}")
                assertEquals(
                    "The pre-death baseline must be the first executed run",
                    1,
                    preCount
                )

                // 1. The process REALLY died and was replaced (not a simulation).
                assertNotEquals(
                    "The app process must have REALLY died and restarted " +
                        "(recorded pid=$prePid)",
                    prePid,
                    Process.myPid()
                )

                // 2. §15 claim: the death-interrupted request is re-run after
                //    relaunch with an ADVANCED runAttemptCount — asserted, not
                //    assumed. Bounded wait: WorkManager's force-stop reschedule
                //    (or the reconciler) starts the run within seconds of launch.
                val advanced = Phase10TestSupport.awaitUntil(60_000) {
                    workManager.getWorkInfosForUniqueWork("episode_download_ep-real-pd").get()
                        .any { it.runAttemptCount > preCount }
                }
                val infos = workManager.getWorkInfosForUniqueWork("episode_download_ep-real-pd")
                    .get()
                assertTrue(
                    "The death-interrupted request must be re-run after relaunch with an " +
                        "advanced runAttemptCount (pre-death=$preCount; post-relaunch " +
                        infos.joinToString { "${it.state}/count=${it.runAttemptCount}" } + ")",
                    advanced
                )
                val advancedInfo = infos.first { it.runAttemptCount > preCount }
                assertEquals(
                    "The advanced count must belong to the SAME request — no silent " +
                        "budget reset via request recreation",
                    preWorkId,
                    advancedInfo.id.toString()
                )
                assertTrue(
                    "The advanced count must still sit inside the auto-resume bound " +
                        "(count=${advancedInfo.runAttemptCount})",
                    advancedInfo.runAttemptCount < DownloadWorker.AUTO_RESUME_MAX_RETRIES
                )
            }

            workManager.cancelUniqueWork("episode_download_ep-real-pd").result.get()
            // Harden the protocol step: wait for the cancelled work to actually
            // finish BEFORE cascading the row away, so the dying worker cannot
            // re-write state after the delete (race present since 11.5E-6).
            Phase10TestSupport.awaitUntil(20_000) {
                workManager.getWorkInfosForUniqueWork("episode_download_ep-real-pd").get()
                    .let { it.isEmpty() || it.all { info -> info.state.isFinished } }
            }
            Phase10TestSupport.deleteSession(db, "sess-real-pd")

            val row = db.episodeDownloadDao().getById("ep-real-pd")
            assertNull("No seeded process-death row may remain", row)
            // The baseline record is protocol scratch — always remove it.
            record.delete()
        }
    }
}
