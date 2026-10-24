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
 * Foreground Worker test on a real device/emulator.
 * Phase 10 architecture v10 §16 (ForegroundWorkerTest) + notification posting.
 *
 * EMULATOR-VERIFIED: the real DownloadWorker calls setForeground() with
 * FOREGROUND_SERVICE_TYPE_DATA_SYNC and the real WorkManager
 * SystemForegroundService (dataSync) starts. This test asserts:
 * - Worker reaches RUNNING
 * - A download-channel notification is actively posted while running
 *   (foreground notification)
 * - No MissingForegroundServiceTypeException / SecurityException /
 *   "Failed to set foreground" appears in the app's logcat
 *
 * Uses a hanging (unroutable TEST-NET-3) URL purely to keep the Worker
 * RUNNING long enough to observe foreground state. This does NOT validate
 * CDN/Kwik/HLS content.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundWorkerTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-fg-test"
    private val episodeId = "ep-fg-test"
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
        // Persist cancellation intent, then stop the work
        runBlocking {
            db.episodeDownloadDao().updateState(
                episodeId, EpisodeDownloadEntity.STATE_CANCELLED
            )
        }
        // F-G1: block until the cancellation fully completes — including the
        // worker's NonCancellable finally-trigger — so no stray
        // triggerNextDeferredEpisode() leaks into the next suite test
        // (TriggerNextDeferredEpisodeTest asserts WorkManager quiescence
        // before seeding its deferred episode).
        try {
            workManager.cancelUniqueWork(uniqueName).result.get()
        } catch (_: Exception) {
            // Cancellation may already be complete — fall through to the
            // terminal-state wait below.
        }
        Phase10TestSupport.awaitUntil(30_000) {
            workManager.getWorkInfosForUniqueWork(uniqueName).get()
                .all { it.state.isFinished }
        }
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    private fun readLogcatErrors(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "3000"))
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text.lines()
                .filter {
                    it.contains("MissingForegroundServiceTypeException") ||
                        it.contains("SecurityException") ||
                        it.contains("Failed to set foreground") ||
                        it.contains("startForeground") && it.contains("did not then call")
                }
                .joinToString("\n")
        } catch (_: Exception) {
            "" // logcat read is best effort
        }
    }

    @Test
    fun worker_enters_foreground_and_posts_notification_without_fgs_exception() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val now = System.currentTimeMillis()
            val notificationId = DownloadNotificationManager(context).notificationIdFor(episodeId)

            dao.upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = sessionId,
                    episodeNumber = 1.0f,
                    episodeSession = "eps-$episodeId",
                    title = "Foreground Test Episode",
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

            // 1. Worker must actually run (custom WorkerFactory instantiation)
            val running = Phase10TestSupport.awaitUntil(20_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }
            assertTrue("Worker must reach RUNNING", running)

            // 2. Foreground notification must be actively posted on our channel
            //    (this is the dataSync foreground-service notification)
            val notified = Phase10TestSupport.awaitUntil(15_000) {
                Phase10TestSupport.activeDownloadNotificationIds(context)
                    .contains(notificationId)
            }
            assertTrue(
                "Foreground download notification must be posted while Worker is RUNNING",
                notified
            )

            // 3. Worker state persisted as DOWNLOADING
            assertEquals(
                EpisodeDownloadEntity.STATE_DOWNLOADING,
                dao.getById(episodeId)?.state
            )

            // 4. No foreground-service exceptions in logcat
            val errors = readLogcatErrors()
            assertTrue(
                "Foreground service errors found:\n$errors",
                errors.isBlank()
            )

            // 5. Worker is still alive (foreground keeps it running)
            val stillRunning = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                .any { it.state == WorkInfo.State.RUNNING }
            assertTrue("Worker must remain RUNNING while hanging on network", stillRunning)
        }
    }
}
