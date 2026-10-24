package com.pahebatcher.app.support

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.notification.NotificationChannels
import java.io.File

/**
 * Shared helpers for Phase 10 instrumentation tests.
 *
 * These tests exercise the REAL application WorkManager (initialized by
 * PaheBatcherApplication with DownloadWorkerFactory) and the REAL Room
 * database singleton, because DownloadWorker always reads from
 * PaheDatabase.getInstance() — an in-memory test database would be invisible
 * to the Worker.
 */
object Phase10TestSupport {

    /** URL that fails fast: nothing listens on loopback port 1 (connection refused). */
    const val FAST_FAIL_URL = "http://127.0.0.1:1/playlist.m3u8"

    /**
     * URL used to keep a Worker RUNNING deterministically: 10.255.255.1/8 is
     * TEST-NET-3 (unroutable). Since targetSdk 37 the platform default denies
     * CLEARTEXT to it, each request actually fails FAST (policy denial within
     * ms — NOT a 15 s connect block); the worker nevertheless stays RUNNING
     * for seconds because of the in-run retry pacing (5 HTTP attempts with
     * exponential backoff per episode attempt × `auto_retry + 1` episode
     * attempts), which is the window in which foreground state, notifications
     * and cancellation are observed. (Review L-1: the previous wording —
     * "connects block until OkHttp's 15s connect timeout" — misdescribed the
     * layer that keeps the run alive and would have sent a maintainer to fix
     * the wrong thing.)
     */
    const val HANGING_URL = "http://10.255.255.1:81/playlist.m3u8"

    /** Poll until condition is true or timeout elapses. */
    fun awaitUntil(timeoutMs: Long, intervalMs: Long = 250, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        return condition()
    }

    /**
     * True when [episodeId] is parked by Phase 14 auto-resume: the row is back
     * in `QUEUED` and its unique request is `ENQUEUED` with **at least one
     * executed run** (`runAttemptCount >= 1`).
     *
     * The attempt-count clause is what makes this an unambiguous "parked"
     * signal: a freshly enqueued request is also `QUEUED` + `ENQUEUED`, but it
     * has not run yet and still reports 0. WorkManager increments
     * `runAttemptCount` exactly when a request first moves ENQUEUED → RUNNING
     * (`WorkerWrapper.trySetRunning`), so `>= 1` means the run happened and the
     * worker returned `Result.retry()` — i.e. the auto-resume park CAS landed.
     */
    fun isParkedForAutoResume(
        db: PaheDatabase,
        workManager: WorkManager,
        episodeId: String,
    ): Boolean = kotlinx.coroutines.runBlocking {
        db.episodeDownloadDao().getById(episodeId)?.state ==
            EpisodeDownloadEntity.STATE_QUEUED &&
            workManager.getWorkInfosForUniqueWork("episode_download_$episodeId").get()
                .let { infos ->
                    infos.isNotEmpty() &&
                        infos.all {
                            it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount >= 1
                        }
                }
    }

    /**
     * Grant POST_NOTIFICATIONS via shell (test-harness only) on API 33+.
     *
     * Deliberately unchanged in Phase 14 after a verified investigation — do
     * not "fix" the missing `--user` here without re-reading the whole effect:
     *
     * `pm` defaults to user 0, so on this multi-user device (current user 10)
     * this command prints `Failure [package not found]` and grants NOTHING.
     * That is load-bearing. It is called FIRST by `data.download.CancellationTest`,
     * `UniqueWorkTest`, `WifiOnlyConstraintTest` and (since Phase 14)
     * `worker.AutoResumeConnectivityTest` — the first packages in the
     * alphabetical suite order — so if it really granted the running user's
     * permission, `ui.FirstEnqueuePromptTest`'s three `a_denied_*` tests would
     * hit their `Assume` ("baseline is GRANTED and revoking would kill the
     * running test process") and skip, silently removing Phase 13's denied-path
     * coverage. Android gives no way back: revoking a runtime permission
     * force-stops the process and the instrumentation run with it.
     *
     * The suite instead relies on `FirstEnqueuePromptTest.ensureRealPermissionGranted`
     * (correctly `--user`-targeted) granting the running user at suite position
     * 47, which is what every `worker`-package posting assertion — including
     * `ForegroundWorkerTest` — already depends on. Verified 2026-10-03: with
     * this function returning to its original form the suite is 394/0/0/0 and
     * with it "corrected" the same suite reports 3 assumption violations in
     * `FirstEnqueuePromptTest.a_denied_*`.
     */
    fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand(
                        "pm grant com.pahebatcher.app android.permission.POST_NOTIFICATIONS"
                    )
                pfd.close()
            } catch (_: Exception) {
                // Best effort — FGS still runs without the grant
            }
        }
    }

    /** IDs of currently active notifications. */
    fun activeNotificationIds(context: Context): Set<Int> {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.map { it.id }.toSet()
    }

    /** Notification IDs posted on our download channel. */
    fun activeDownloadNotificationIds(context: Context): Set<Int> {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications
            .filter { it.notification.channelId == NotificationChannels.DOWNLOAD_PROGRESS }
            .map { it.id }
            .toSet()
    }

    /** Create a parent session row in the REAL database. */
    fun insertSession(db: PaheDatabase, sessionId: String) {
        val now = System.currentTimeMillis()
        val session = DownloadSessionEntity(
            sessionId = sessionId,
            title = "Phase10 Test Session",
            animeSession = "anime-$sessionId",
            host = "animepahe.local",
            quality = 2,
            audioLang = "sub",
            outputDir = "/downloads",
            cacheDir = "/cache",
            createdAt = now,
            updatedAt = now,
            state = "CREATED"
        )
        runBlockingSuspend { db.downloadSessionDao().insertIfAbsent(session) }
    }

    /** Delete a session row (episodes cascade). */
    fun deleteSession(db: PaheDatabase, sessionId: String) {
        runBlockingSuspend { db.downloadSessionDao().delete(sessionId) }
    }

    /** Real per-episode segment directory path (matches DownloadRepository). */
    fun segmentDirFor(context: Context, episodeId: String): String {
        val dir = File(context.filesDir, "downloads/$episodeId")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    private fun runBlockingSuspend(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
