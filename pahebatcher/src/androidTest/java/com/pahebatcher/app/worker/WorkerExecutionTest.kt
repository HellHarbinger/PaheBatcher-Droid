package com.pahebatcher.app.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Worker execution test on a real device/emulator.
 * Phase 10 architecture v10 §16 (WorkerExecutionTest).
 *
 * EMULATOR-VERIFIED (not simulated): the real Application WorkManager
 * (custom DownloadWorkerFactory) instantiates the real DownloadWorker,
 * which reads/writes the real Room database and runs the real
 * EpisodeDownloader pipeline.
 *
 * Uses a deterministic loopback URL that refuses connections — this verifies
 * the Worker architecture (state machine, persistence, failure handling).
 * It deliberately does NOT claim CDN/Kwik/HLS-content validation.
 */
@RunWith(AndroidJUnit4::class)
class WorkerExecutionTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-worker-test"
    private val episodeId = "ep-worker-test"
    private val uniqueName = "episode_download_$episodeId"

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
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    @Test
    fun worker_executes_full_failure_path_and_persists_state() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val now = System.currentTimeMillis()

            // Seed a QUEUED episode WITH persisted stream info (post-resolution shape)
            dao.upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = sessionId,
                    episodeNumber = 1.0f,
                    episodeSession = "eps-$episodeId",
                    title = "Worker Execution Episode",
                    fansub = "TestFansub",
                    audio = "sub",
                    playUrl = "https://example.com/play",
                    state = EpisodeDownloadEntity.STATE_QUEUED,
                    createdAt = now,
                    updatedAt = now,
                    m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                    userAgent = "TestUA",
                    referer = "https://example.com",
                    segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId)
                )
            )

            // Enqueue through the real repository (unique work, KEEP)
            repository.enqueueWorker(episodeId)

            // 1. Custom WorkerFactory must instantiate the Worker → it runs
            val started = Phase10TestSupport.awaitUntil(20_000) {
                val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                infos.any { it.state == WorkInfo.State.RUNNING } ||
                    infos.any { it.state.isFinished }
            }
            assertTrue("Worker never started", started)

            // 2. Worker transitions to DOWNLOADING (or already progressed past it)
            //    — the state transition is persisted in Room by the Worker itself
            val reachedDownloading = dao.getById(episodeId)?.state.let {
                it == EpisodeDownloadEntity.STATE_DOWNLOADING ||
                    it == EpisodeDownloadEntity.STATE_FAILED ||
                    it == EpisodeDownloadEntity.STATE_COMPLETED
            }
            assertTrue(
                "Worker must persist a post-QUEUED state (was: ${dao.getById(episodeId)?.state})",
                reachedDownloading
            )

            // 3. Worker completes the failure path deterministically
            //    (connection refused → retries exhausted → FAILED with error persisted)
            val finished = Phase10TestSupport.awaitUntil(120_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .all { it.state.isFinished }
            }
            assertTrue("Worker must finish", finished)

            val workInfo = workManager.getWorkInfosForUniqueWork(uniqueName).get().first()
            assertEquals(
                "Deterministic network failure must map to work failure",
                WorkInfo.State.FAILED,
                workInfo.state
            )

            // 4. Room state and error persisted
            val row = dao.getById(episodeId)
            assertEquals(EpisodeDownloadEntity.STATE_FAILED, row?.state)
            assertNotNull("errorMessage must be persisted", row?.errorMessage)
            assertTrue(row!!.errorMessage!!.isNotBlank())

            // 5. Foreground/notification cleaned up after terminal state
            Phase10TestSupport.awaitUntil(5_000) {
                Phase10TestSupport.activeDownloadNotificationIds(context)
                    .none { it == DownloadNotificationManager(context).notificationIdFor(episodeId) }
            }
            val stillNotified = Phase10TestSupport.activeDownloadNotificationIds(context)
                .contains(DownloadNotificationManager(context).notificationIdFor(episodeId))
            assertFalse("Notification must be cancelled after terminal state", stillNotified)
        }
    }

    @Test
    fun worker_missing_episode_fails_without_crash() {
        runBlocking {
            // Enqueue work for an episode that does not exist in Room
            repository.enqueueWorker("ep-does-not-exist")

            val infos = workManager.getWorkInfosForUniqueWork("episode_download_ep-does-not-exist").get()
            val finished = Phase10TestSupport.awaitUntil(20_000) {
                infos.all { it.state.isFinished } ||
                    workManager.getWorkInfosForUniqueWork("episode_download_ep-does-not-exist")
                        .get().all { it.state.isFinished }
            }
            assertTrue("Worker must handle missing episode deterministically", finished)

            val finalInfos = workManager.getWorkInfosForUniqueWork("episode_download_ep-does-not-exist").get()
            assertTrue(finalInfos.isNotEmpty())
            assertEquals(WorkInfo.State.FAILED, finalInfos[0].state)
        }
    }
}
