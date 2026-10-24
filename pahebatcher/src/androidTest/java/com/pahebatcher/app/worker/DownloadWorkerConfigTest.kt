package com.pahebatcher.app.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.download.AppConfigDownloadConfigSource
import com.pahebatcher.app.data.download.DownloadConfig
import com.pahebatcher.app.data.download.DownloadConfigSource
import com.pahebatcher.app.data.hls.AesKeyCache
import com.pahebatcher.app.data.hls.OkHttpHlsClient
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.SettingsTestSupport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 12A reviewer finding H3 — persisted settings really reach ONE Worker
 * run, exactly ONCE, and a settings read failure never blocks a download.
 *
 * Contract under test ([DownloadWorker] refreshConfig):
 *
 * 1. the configuration is read ONCE per `doWork()` through the production
 *    [AppConfigDownloadConfigSource] (real DataStore, real mapping), and that
 *    snapshot — not the constructor default — is the one the run uses;
 * 2. a failing settings read is logged and the run CONTINUES with the
 *    configuration it was constructed with (settings are never a dependency of
 *    download execution, exactly like the Console/logging abstraction).
 *
 * Each case is observed through two independent, non-vacuous signals:
 *
 * - the `Run config:` line production code logs after loading, which names the
 *   EFFECTIVE snapshot (persisted values in case 1, injected values in case 2);
 * - the maxParallel gate: with one active row the persisted `max_parallel = 1`
 *   DEFERS the run (row stays QUEUED), while the injected `maxParallel = 6`
 *   would have let it claim the row and run to FAILED.
 *
 * The test double ([failingSource]) lives here only — never in production.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWorkerConfigTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-worker-config-$runStamp"
    private val blockerId = "ep-config-blocker-$runStamp"
    private val targetId = "ep-config-target-$runStamp"

    /** Number of `downloadConfig()` invocations the worker run performed. */
    private val sourceReads = CopyOnWriteArrayList<Int>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)
        // Known precondition: the values written by each test must be the only
        // source of the effective run configuration.
        SettingsTestSupport.resetToDefaults(context)
        // AppLog retains lines ONLY while the
        // persisted `Show Console` flag is ON, and the assertions below read
        // real production log lines — so the run opts in through the SAME flag
        // the Application collector reads (teardown resets it to OFF). Waiting
        // on the observed gate — not just issuing the write — makes the
        // ordered DataStore emission the synchronization point.
        runBlocking { AppConfig(context).updateShowConsole(true) }
        assertTrue(
            "the retention gate must follow the persisted Show Console flag",
            Phase10TestSupport.awaitUntil(5_000) { AppLog.isBufferingEnabled },
        )
    }

    @After
    fun teardown() {
        listOf(blockerId, targetId).forEach { id ->
            WorkManager.getInstance(context).cancelUniqueWork("episode_download_$id")
        }
        Phase10TestSupport.deleteSession(db, sessionId)
        SettingsTestSupport.resetToDefaults(context)
        // Leave the process-wide retention gate at its default (OFF) so the
        // next suite starts from the documented precondition — asserted, not
        // awaited blindly, so a stuck gate fails HERE instead of later.
        assertTrue(
            "the retention gate must return to OFF after the suite",
            Phase10TestSupport.awaitUntil(5_000) { !AppLog.isBufferingEnabled },
        )
    }

    private fun seed(id: String, state: String) = EpisodeDownloadEntity(
        episodeId = id,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$id",
        title = "Config Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://animepahe.com/play/$sessionId/eps-$id",
        state = state,
        retryCount = 0,
        errorMessage = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        // No stream info on purpose: a run that wrongly PROCEEDS claims the row
        // and fails honestly (FAILED) — which is exactly what these tests assert
        // did NOT happen. It also keeps the row ineligible for any other queued
        // Worker (`getNextQueuedWithM3u8` requires a non-null m3u8Url).
        m3u8Url = null,
        userAgent = null,
        referer = null,
        segmentDir = Phase10TestSupport.segmentDirFor(context, id),
    )

    /**
     * Seeds one active (DOWNLOADING) row and returns the resulting active count.
     *
     * The gate only discriminates while that count is inside 1..5 (persisted
     * `max_parallel = 1` defers; injected `maxParallel = 6` would proceed), so a
     * count outside the range is reported as a precondition failure instead of
     * silently producing a vacuous assertion.
     */
    private suspend fun seedBlockerAndRequireDiscriminatingCount(): Int {
        val dao = db.episodeDownloadDao()
        val baseline = dao.countActive()
        dao.upsert(seed(blockerId, EpisodeDownloadEntity.STATE_DOWNLOADING))
        val active = dao.countActive()
        assertTrue(
            "this test needs 1..5 active rows to discriminate the gate " +
                "(baseline $baseline + blocker) — got $active",
            active in 1..5,
        )
        return active
    }

    private fun buildWorker(
        injected: DownloadConfig,
        source: DownloadConfigSource?,
    ): ListenableWorker =
        TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
            .setInputData(workDataOf("episode_id" to targetId))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = DownloadWorker(
                        context = appContext,
                        params = workerParameters,
                        dao = db.episodeDownloadDao(),
                        notificationManager = DownloadNotificationManager(appContext),
                        hlsClient = OkHttpHlsClient(),
                        keyCache = AesKeyCache(),
                        config = injected,
                        configSource = source,
                    )
                },
            )
            .build()

    /** Production `Run config:` line — names the EFFECTIVE snapshot of the run. */
    private fun runConfigLogged(message: String): Boolean =
        AppLog.buffer.entries.value.any { it.tag == "DownloadWorker" && it.message == message }

    // ── H3 #1: the persisted settings are read once and drive the run ───────

    @Test
    fun persistedSettingsAreReadOnceAndDriveTheRun() = runBlocking {
        val active = seedBlockerAndRequireDiscriminatingCount()

        val appConfig = AppConfig(context)
        appConfig.updateMaxParallel(1)
        appConfig.updateHlsWorkers(16)
        appConfig.updateKeepTemp(true)
        appConfig.updateAutoRetry(0)

        db.episodeDownloadDao().upsert(seed(targetId, EpisodeDownloadEntity.STATE_QUEUED))

        // Injected configuration deliberately differs from the persisted one:
        // maxParallel 6 would let the run claim the row (active = $active), and
        // 8/false/2 would show up in the `Run config:` line instead of 16/true/0.
        val injected = DownloadConfig(
            maxParallel = 6,
            hlsWorkers = 8,
            keepTemp = false,
            episodeRetryCount = 2,
        )
        val worker = buildWorker(injected = injected, source = recordingProductionSource())

        val result = worker.startWork().get(90, TimeUnit.SECONDS)

        assertEquals("the settings must be read exactly once per run", 1, sourceReads.size)
        assertEquals(
            "a deferring run is still a successful Result",
            ListenableWorker.Result.success(),
            result,
        )
        assertTrue(
            "the run must use the persisted snapshot " +
                "(maxParallel=1 hlsWorkers=16 keepTemp=true episodeRetry=0), not the injected one",
            runConfigLogged("Run config: maxParallel=1 hlsWorkers=16 keepTemp=true episodeRetry=0"),
        )
        assertEquals(
            "persisted max_parallel = 1 against $active active row(s) must DEFER the run " +
                "(an unconsumed setting would have claimed the row and failed it)",
            EpisodeDownloadEntity.STATE_QUEUED,
            db.episodeDownloadDao().getById(targetId)?.state,
        )
    }

    // ── H3 #2: a settings read failure never blocks a download run ──────────

    @Test
    fun settingsReadFailureKeepsTheInjectedConfigAndNeverBlocksTheRun() = runBlocking {
        seedBlockerAndRequireDiscriminatingCount()
        db.episodeDownloadDao().upsert(seed(targetId, EpisodeDownloadEntity.STATE_QUEUED))

        // Injected snapshot (maxParallel = 1) is what the run must fall back to:
        // the log line names those exact values, which differ from both the
        // production default (2/24/false/2) and the values of test #1.
        val injected = DownloadConfig(
            maxParallel = 1,
            hlsWorkers = 8,
            keepTemp = true,
            episodeRetryCount = 0,
        )
        val worker = buildWorker(injected = injected, source = failingSource())

        val result = worker.startWork().get(90, TimeUnit.SECONDS)

        assertEquals(
            "the failing read must still be attempted exactly once",
            1,
            sourceReads.size,
        )
        assertEquals(
            "a settings read failure must never fail or crash the run",
            ListenableWorker.Result.success(),
            result,
        )
        assertTrue(
            "the read failure must be logged (diagnostics, not control flow)",
            AppLog.buffer.entries.value.any {
                it.tag == "DownloadWorker" && it.message.contains("Settings read failed")
            },
        )
        assertTrue(
            "the run must continue with its injected configuration",
            runConfigLogged("Run config: maxParallel=1 hlsWorkers=8 keepTemp=true episodeRetry=0"),
        )
        assertNotNull("the episode row must still exist", db.episodeDownloadDao().getById(targetId))
        assertEquals(
            "the injected maxParallel = 1 gate must defer against the active blocker",
            EpisodeDownloadEntity.STATE_QUEUED,
            db.episodeDownloadDao().getById(targetId)?.state,
        )
    }

    // ── seams (test-only) ───────────────────────────────────────────────────

    /** Production source over the real DataStore, with every read recorded. */
    private fun recordingProductionSource(): DownloadConfigSource {
        val delegate = AppConfigDownloadConfigSource(AppConfig(context))
        return object : DownloadConfigSource {
            override suspend fun downloadConfig(): DownloadConfig {
                sourceReads.add(sourceReads.size + 1)
                return delegate.downloadConfig()
            }
        }
    }

    /** A settings source that cannot be read (DataStore I/O failure). */
    private fun failingSource(): DownloadConfigSource = object : DownloadConfigSource {
        override suspend fun downloadConfig(): DownloadConfig {
            sourceReads.add(sourceReads.size + 1)
            throw IllegalStateException("settings unavailable (test seam)")
        }
    }
}
