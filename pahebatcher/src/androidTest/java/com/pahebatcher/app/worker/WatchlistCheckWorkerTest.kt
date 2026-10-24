package com.pahebatcher.app.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.data.download.DownloadConfig
import com.pahebatcher.app.data.hls.AesKeyCache
import com.pahebatcher.app.data.hls.OkHttpHlsClient
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.WatchlistDao
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.data.repository.WatchlistRepository
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.watchlist.WatchlistChecker
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.notification.WatchlistNotificationManager
import com.pahebatcher.app.support.RecordStreamResolver
import com.pahebatcher.app.ui.watchlist.WatchlistViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `WatchlistCheckWorker` + `WatchlistCheckScheduler` on a device,
 * extended by Phase 14 (PHASE14_PLAN §12.1/§12.2) for the check-frequency
 * setting and the aggregated watchlist notification.
 *
 * What these tests own (nothing here duplicates the 16-test
 * `WatchlistCheckerTest` JVM suite, which already covers detection, relink,
 * capacity, dead links, deletion and per-entry isolation):
 *
 * 1. **Worker construction** — the production `WorkerFactory` composition really
 *    builds a `WatchlistCheckWorker`, while the existing `DownloadWorker`
 *    branch and the "unknown class returns null" contract are untouched.
 * 2. **Worker execution** — a background run reaches the existing
 *    `WatchlistChecker`, persists `last_checked` on the REAL Room row, issues the
 *    scan, resolves nothing when nothing is pending, and never touches an
 *    Activity (this class never launches one); an empty watchlist is a no-op
 *    success; a watchlist read failure returns `retry` instead of stranding the
 *    schedule.
 * 3. **Scheduling** — repeated registration of the unique periodic work leaves
 *    exactly one ENQUEUED request, and a CHANGED interval replaces that request
 *    (`ExistingPeriodicWorkPolicy.UPDATE`, §12.1), running [WatchlistCheckWorker]
 *    with a network constraint.
 * 4. **Watchlist notification (§12.2)** — a detecting run posts EXACTLY ONE
 *    notification on the fixed `-9001` id and a second run REPLACES it instead
 *    of stacking; a detection-free run posts nothing; an opted-out run posts
 *    nothing; and a FOREGROUND `checkNow()` never posts at all. The notification
 *    is observed through the real system `NotificationManager`, not a mock — the
 *    post site is the production one.
 *
 * The transport itself is stubbed (`StubScanClient`) because the real
 * WebView/Cloudflare path is exercised by the physical-device verification, not
 * by a test that must be deterministic.
 */
@RunWith(AndroidJUnit4::class)
class WatchlistCheckWorkerTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var downloadRoot: File
    private val sessionId = "watchlist-12d-${System.nanoTime()}"
    private val resolver = RecordStreamResolver()
    private val queue = HandledEpisodeQueue()
    private lateinit var client: StubScanClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        downloadRoot = File(context.cacheDir, "watchlist-12d").apply { mkdirs() }
        client = StubScanClient(ownSession = sessionId)
    }

    @After
    fun tearDown() {
        // Leave the schedule on the shipped default: this class deliberately
        // registers a NON-default interval, and until the app next launches
        // (when the Application collector repairs it) a device parked at 60
        // minutes would mislead anyone reading WatchlistCheckScheduler.describe.
        WatchlistCheckScheduler.schedule(
            WorkManager.getInstance(context),
            WatchlistCheckScheduler.INTERVAL_MINUTES,
        )
        // Leave the device clean: a notification that survives a test would
        // turn the NEXT test's "no notification" assertion into a false failure.
        cancelWatchlistNotification()
        runBlocking { db.watchlistDao().delete(sessionId) }
        downloadRoot.deleteRecursively()
    }

    // ── 1. Worker construction ───────────────────────────────────────────

    @Test
    fun `the production factory composition builds a WatchlistCheckWorker`() {
        val app = context.applicationContext as PaheBatcherApplication
        val productionFactory = app.workManagerConfiguration.workerFactory

        val worker = TestListenableWorkerBuilder
            .from(context, WatchlistCheckWorker::class.java)
            .setWorkerFactory(productionFactory)
            .build()

        assertTrue(
            "the real Application composition must be able to create the worker",
            worker is WatchlistCheckWorker,
        )
    }

    @Test
    fun `unknown worker classes still return null and DownloadWorker still builds`() {
        val parameters = captureWorkerParameters()
        val factory = DownloadWorkerFactory(
            database = db,
            notificationManager = DownloadNotificationManager(context),
            config = DownloadConfig(),
            watchlistChecker = checker(),
        )

        assertNull(
            "an unknown class must keep the Phase 10 'not mine' contract",
            factory.createWorker(context, "com.example.NotAWorker", parameters),
        )
        assertTrue(
            factory.createWorker(context, DownloadWorker::class.java.name, parameters)
                is DownloadWorker,
        )
        assertTrue(
            factory.createWorker(context, WatchlistCheckWorker::class.java.name, parameters)
                is WatchlistCheckWorker,
        )
    }

    @Test
    fun `a composition that never wired a checker fails loudly instead of silently skipping`() {
        val parameters = captureWorkerParameters()
        val factory = DownloadWorkerFactory(
            database = db,
            notificationManager = DownloadNotificationManager(context),
            config = DownloadConfig(),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            factory.createWorker(context, WatchlistCheckWorker::class.java.name, parameters)
        }
        assertNotNull(failure.message)
        assertTrue(failure.message!!.contains("WatchlistChecker"))
    }

    // ── 2. Worker execution ──────────────────────────────────────────────

    @Test
    fun `a background check persists last_checked through the existing checker`() {
        val inserted = insertEntry()

        val result = buildWorker(checker()).startWork().get(90, TimeUnit.SECONDS)

        assertTrue("the run must succeed", result is ListenableWorker.Result.Success)

        val persisted = runBlocking { db.watchlistDao().getBySessionId(inserted.sessionId) }
        assertNotNull(persisted)
        assertTrue(
            "last_checked must be persisted on the real row",
            persisted!!.lastCheckedAt > 0L,
        )
        assertTrue(
            "the scan must really have run against this entry",
            client.requestedUrls.any { it.contains(inserted.animeSession) },
        )
        assertTrue(
            "nothing was pending, so the background check must resolve nothing",
            resolver.calls.isEmpty(),
        )
    }

    @Test
    fun `an empty watchlist is a successful no-op that issues no request`() {
        val worker = buildWorker(checker(ScriptedWatchlistDao(entries = emptyList())))

        val result = worker.startWork().get(90, TimeUnit.SECONDS)

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(client.requestedUrls.isEmpty())
        assertTrue(resolver.calls.isEmpty())
    }

    @Test
    fun `a watchlist read failure retries instead of ending the schedule`() {
        val worker = buildWorker(checker(ScriptedWatchlistDao(entries = null)))

        val result = worker.startWork().get(90, TimeUnit.SECONDS)

        assertTrue(
            "an infrastructure fault must be retried, never returned as a failure",
            result is ListenableWorker.Result.Retry,
        )
    }

    // ── 3. Scheduling ────────────────────────────────────────────────────

    /**
     * `ExistingPeriodicWorkPolicy.UPDATE`.
     *
     * Two facts, both read BACK from WorkManager rather than from the argument
     * that was passed in:
     *
     *  1. registering the same interval twice still leaves ONE request (UPDATE
     *     may never duplicate);
     *  2. registering a DIFFERENT interval replaces the request already held —
     *     `KEEP`, which this replaced, would have left the interval at 15
     *     forever and made the Settings row decorative.
     */
    @Test
    fun `scheduling applies a changed interval while still leaving exactly one request`() {
        val workManager = WorkManager.getInstance(context)

        WatchlistCheckScheduler.schedule(workManager, 15L).result.get(30, TimeUnit.SECONDS)
        WatchlistCheckScheduler.schedule(workManager, 15L).result.get(30, TimeUnit.SECONDS)

        var infos = workManager
            .getWorkInfosForUniqueWork(WatchlistCheckScheduler.UNIQUE_WORK_NAME)
            .get()

        assertEquals("UPDATE must never duplicate the schedule", 1, infos.size)
        val first = infos[0]
        assertTrue(
            "the single request must still be schedulable (state=${first.state})",
            first.state == WorkInfo.State.ENQUEUED || first.state == WorkInfo.State.RUNNING,
        )
        assertTrue(
            first.tags.contains(WatchlistCheckWorker::class.java.name),
        )
        assertEquals(
            "the shipped interval must be the 15 minute periodic minimum",
            15L * 60_000L,
            first.periodicityInfo?.repeatIntervalMillis,
        )

        // The contract the Settings row depends on: a chosen interval has to
        // reach the request WorkManager is actually holding.
        WatchlistCheckScheduler.schedule(workManager, 60L).result.get(30, TimeUnit.SECONDS)
        infos = workManager
            .getWorkInfosForUniqueWork(WatchlistCheckScheduler.UNIQUE_WORK_NAME)
            .get()

        assertEquals("UPDATE must replace, never duplicate", 1, infos.size)
        assertEquals(
            "the changed interval must reach the single registered request",
            60L * 60_000L,
            infos[0].periodicityInfo?.repeatIntervalMillis,
        )
        assertTrue(
            "the application-level schedule must be readable back from WorkManager",
            WatchlistCheckScheduler.describe(workManager, 60L).contains("count=1"),
        )
    }

    // ── 4. Watchlist notification (Phase 14, PHASE14_PLAN §12.2) ─────────

    /**
     * §12.2 trigger + the fixed id: a run that DETECTS new episodes posts
     * exactly one notification, and the next run REPLACES it.
     *
     * The entry starts with no download history and a queue that handles
     * nothing, so both stubbed episodes stay pending — `newEpisodes = 2`. The
     * second run widens the canned listing to 3, which is only observable if the
     * SAME notification id was reused: a per-run id would show two live entries,
     * and a stale one would keep showing `2`.
     */
    @Test
    fun `a detecting run posts exactly one watchlist notification and the next run replaces it`() {
        insertEntry()
        cancelWatchlistNotification()

        val first = buildWorker(checker(downloadQueue = FreshEpisodeQueue()))
            .startWork().get(90, TimeUnit.SECONDS)
        assertTrue("the detecting run must succeed", first is ListenableWorker.Result.Success)

        assertEquals(
            "exactly one live watchlist notification after a detecting run",
            1,
            liveWatchlistNotifications(),
        )
        assertEquals(
            "the post must report the detected counts",
            "2 new episodes in 1 series",
            liveWatchlistNotificationText(),
        )

        client.episodeCount = 3
        val second = buildWorker(checker(downloadQueue = FreshEpisodeQueue()))
            .startWork().get(90, TimeUnit.SECONDS)
        assertTrue("the second detecting run must succeed", second is ListenableWorker.Result.Success)

        assertEquals(
            "the second run must replace the first, never stack beside it",
            1,
            liveWatchlistNotifications(),
        )
        assertEquals(
            "the replacement must carry the NEW counts",
            "3 new episodes in 1 series",
            liveWatchlistNotificationText(),
        )
    }

    /**
     * The complementary half of the trigger: `newEpisodes == 0` posts NOTHING,
     * even though the run really happened (§18: no notification spam).
     */
    @Test
    fun `a run that detects nothing posts no watchlist notification`() {
        val inserted = insertEntry()
        cancelWatchlistNotification()

        val result = buildWorker(checker()).startWork().get(90, TimeUnit.SECONDS)

        assertTrue("the run must still succeed", result is ListenableWorker.Result.Success)
        val persisted = runBlocking { db.watchlistDao().getBySessionId(inserted.sessionId) }
        assertNotNull(persisted)
        assertTrue(
            "last_checked must be persisted — otherwise 'no notification' proves nothing",
            persisted!!.lastCheckedAt > 0L,
        )
        assertTrue(
            "the scan must really have run",
            client.requestedUrls.any { it.contains(inserted.animeSession) },
        )
        assertEquals(
            "a detection-free run must post nothing",
            0,
            liveWatchlistNotifications(),
        )
    }

    /**
     * §12.2/§18: `notify_watchlist` gates the post, and reading it must not
     * disturb the check itself — the run still succeeds and the detection really
     * happened (proven by the recorded resolution calls).
     */
    @Test
    fun `the watchlist notification preference suppresses an otherwise required post`() {
        insertEntry()
        cancelWatchlistNotification()

        val result = buildWorker(
            checker(downloadQueue = FreshEpisodeQueue()),
            watchlistNotifyProvider = { false },
        ).startWork().get(90, TimeUnit.SECONDS)

        assertTrue("the check itself must still succeed", result is ListenableWorker.Result.Success)
        assertTrue(
            "the run really did detect and resolve pending episodes — otherwise this proves nothing",
            resolver.calls.isNotEmpty(),
        )
        assertEquals(
            "an opted-out run must post nothing",
            0,
            liveWatchlistNotifications(),
        )
    }

    /**
     * F-04 (PHASE14_PLAN §12.2 "cancellation → rethrow"): [WatchlistCheckWorker]
     * reads the preference through a SUSPENDING provider, so a stopped run raises
     * `CancellationException` exactly there. It must propagate — never be absorbed
     * by the generic `catch (e: Exception)`, which would turn a cancelled run into
     * a "notification disabled" decision and let `doWork()` return a Result from a
     * cancelled coroutine. Same contract doWork already applies to `checkAll()`.
     *
     * The detection is proven by the recorded resolution calls, so reaching the
     * preference read (and therefore the cancellation point) is not in doubt.
     */
    @Test
    fun `cancellation at the notification preference read propagates instead of becoming a decision`() {
        insertEntry()
        cancelWatchlistNotification()

        val worker = buildWorker(
            checker(downloadQueue = FreshEpisodeQueue()),
            watchlistNotifyProvider = { throw CancellationException("the run was stopped") },
        )

        runBlocking {
            try {
                worker.doWork()
                fail(
                    "a cancelled run must never return a Result — CancellationException must propagate",
                )
            } catch (expected: CancellationException) {
                // propagation IS the contract under test
            }
        }

        assertTrue(
            "the run really did detect and resolve pending episodes — otherwise the preference read was never reached",
            resolver.calls.isNotEmpty(),
        )
        assertEquals(
            "a cancelled run must post nothing",
            0,
            liveWatchlistNotifications(),
        )
    }

    /**
     * §12.2: the FOREGROUND `checkNow()` never posts — only this worker may.
     *
     * Asserted through the real `WatchlistViewModel` against the same checker,
     * and only after proving the foreground run really detected episodes, so an
     * absent notification cannot be explained by an empty or failed check.
     */
    @Test
    fun `a foreground check never posts the watchlist notification`() {
        insertEntry()
        cancelWatchlistNotification()

        val viewModel = WatchlistViewModel(
            watchlistRepository = WatchlistRepository(db.watchlistDao()),
            checker = checker(downloadQueue = FreshEpisodeQueue()),
        )

        awaitUntil("the Room observer must publish the inserted entry") {
            viewModel.uiState.value.entries.isNotEmpty()
        }
        viewModel.checkNow()
        awaitUntil("the foreground check must finish") {
            viewModel.uiState.value.results.isNotEmpty() && !viewModel.uiState.value.checking
        }

        val foregroundResult = viewModel.uiState.value.results[sessionId]
        assertNotNull("the foreground run must have produced a result", foregroundResult)
        assertEquals(
            "the foreground run must really have detected new episodes",
            2,
            foregroundResult!!.newEpisodes,
        )
        assertEquals(
            "only the background worker may post the watchlist notification",
            0,
            liveWatchlistNotifications(),
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun insertEntry(): WatchlistEntity {
        val entry = WatchlistEntity(
            sessionId = sessionId,
            url = "https://animepahe.com/anime/$sessionId",
            animeSession = sessionId,
            host = "animepahe.com",
            title = "Phase12D Test Anime",
            quality = 720,
            audioLang = "jpn",
            outputDir = "./downloads",
            maxParallel = 2,
            hlsWorkers = 24,
            keepTemp = false,
            autoRetry = 0,
            addedAt = 1_000L,
            lastCheckedAt = 0L,
            downloadedEpisodesJson = "[]",
        )
        runBlocking { db.watchlistDao().upsert(entry) }
        return entry
    }

    private fun checker(
        watchlistDao: WatchlistDao = db.watchlistDao(),
        downloadQueue: EpisodeDownloadQueue = queue,
    ): WatchlistChecker {
        val animeRepository = AnimeRepository(client)
        val scanCacheRepository = ScanCacheRepository(db.scanCacheDao())
        return WatchlistChecker(
            episodeRepository = EpisodeRepository(
                animeRepository = animeRepository,
                scanCacheRepository = scanCacheRepository,
            ),
            animeRepository = animeRepository,
            scanCacheRepository = scanCacheRepository,
            watchlistRepository = WatchlistRepository(watchlistDao),
            streamResolver = resolver,
            downloadQueue = downloadQueue,
            downloadRoot = { downloadRoot },
            configProvider = { AppContext.defaults() },
        )
    }

    private fun buildWorker(
        checker: WatchlistChecker,
        watchlistNotifyProvider: suspend () -> Boolean = { true },
    ): WatchlistCheckWorker {
        val factory = DownloadWorkerFactory(
            database = db,
            notificationManager = DownloadNotificationManager(context),
            config = DownloadConfig(),
            watchlistChecker = checker,
            watchlistNotifyProvider = watchlistNotifyProvider,
        )
        return TestListenableWorkerBuilder
            .from(context, WatchlistCheckWorker::class.java)
            .setWorkerFactory(factory)
            .build() as WatchlistCheckWorker
    }

    // ── Watchlist-notification observation (Phase 14, §12.2) ─────────────

    private fun systemNotificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun cancelWatchlistNotification() {
        systemNotificationManager().cancel(WatchlistNotificationManager.WATCHLIST_NOTIFICATION_ID)
    }

    /**
     * How many live notifications carry the watchlist id. Read from the REAL
     * system `NotificationManager`, so this observes the production post site
     * rather than a test double — a fake would happily report "posted" while the
     * real channel, permission or id were wrong.
     */
    private fun liveWatchlistNotifications(): Int =
        systemNotificationManager().activeNotifications
            .count { it.id == WatchlistNotificationManager.WATCHLIST_NOTIFICATION_ID }

    private fun liveWatchlistNotificationText(): String? =
        systemNotificationManager().activeNotifications
            .firstOrNull { it.id == WatchlistNotificationManager.WATCHLIST_NOTIFICATION_ID }
            ?.notification
            ?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()

    /**
     * Poll a condition from the instrumentation thread. Sleeping lets the MAIN
     * looper run, which is where the `WatchlistViewModel`'s `viewModelScope`
     * coroutines are dispatched — a bare `while` would starve them.
     */
    private fun awaitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("$what within ${timeoutMs}ms")
            Thread.sleep(50)
        }
    }

    /** Obtain a real [WorkerParameters] through the WorkManager test builder. */
    private fun captureWorkerParameters(): WorkerParameters {
        val captured = AtomicReference<WorkerParameters>()
        TestListenableWorkerBuilder
            .from(context, DownloadWorker::class.java)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker {
                    captured.set(workerParameters)
                    return DownloadWorker(
                        context = appContext,
                        params = workerParameters,
                        dao = db.episodeDownloadDao(),
                        notificationManager = DownloadNotificationManager(appContext),
                        hlsClient = OkHttpHlsClient(),
                        keyCache = AesKeyCache(),
                        config = DownloadConfig(),
                    )
                }
            })
            .setInputData(workDataOf("episode_id" to "captured-parameters"))
            .build()

        val parameters = captured.get()
        assertNotNull("the test builder must have produced WorkerParameters", parameters)
        return parameters
    }
}

/**
 * HTTP stub for the scan: answers ONE watchlist entry with a live listing and
 * nothing else, so a check against any other row on the device can never be
 * answered with somebody else's title (a test must not rewrite production data).
 */
private class StubScanClient(private val ownSession: String) : HttpClient {

    val requestedUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /**
     * How many episodes the canned listing contains. Default 2 is what every
     * pre-Phase-14 test was written against; Phase 14 widens it mid-test so a
     * REPLACED watchlist notification can be told apart from a stale one by its
     * counts (`WatchlistCheckWorkerTest`). The watchlist check always scans
     * with a 0-minute cache TTL, so run 2 really re-fetches this.
     */
    var episodeCount: Int = 2

    override suspend fun fetchJson(url: String): String? {
        requestedUrls.add(url)
        if (!url.contains(ownSession) || !url.contains("m=release")) return null
        val episodes = (1..episodeCount).joinToString(",\n") { index ->
            """{"episode": $index, "session": "ep$index-jpn", "title": "Episode $index",""" +
                """ "fansub": "SubsPlease", "audio": "jpn"}"""
        }
        return """
            {"data": [
                $episodes
            ], "last_page": 1, "anime": {"title": "Phase12D Test Anime"}}
        """.trimIndent()
    }

    override suspend fun fetchHtml(url: String): String? {
        requestedUrls.add(url)
        return null
    }
}

/** Every episode is already handled: the run must detect nothing to download. */
private class HandledEpisodeQueue : EpisodeDownloadQueue {
    override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission) = EnqueueOutcome.Accepted
    override suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo) = true
}

/**
 * The complement of [HandledEpisodeQueue] — NOTHING is
 * handled yet, so every detected episode stays pending and the run reports real
 * `newEpisodes`. Queueing stays a no-op: a test must never start a download.
 */
private class FreshEpisodeQueue : EpisodeDownloadQueue {
    override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission) = EnqueueOutcome.Accepted
    override suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo) = false
}

/**
 * Watchlist DAO double with two scripts: a fixed entry list, or a read that
 * fails outright (`entries == null`) — the infrastructure-fault case the worker
 * must answer with a retry.
 */
private class ScriptedWatchlistDao(
    private val entries: List<WatchlistEntity>?,
) : WatchlistDao {

    override fun getAll(): Flow<List<WatchlistEntity>> =
        entries?.let { flowOf(it) }
            ?: flow<List<WatchlistEntity>> { error("watchlist table unavailable") }

    override suspend fun getBySessionId(sessionId: String): WatchlistEntity? =
        entries?.firstOrNull { it.sessionId == sessionId }

    override suspend fun upsert(entry: WatchlistEntity): Unit = Unit

    override suspend fun updateLastChecked(sessionId: String, timestamp: Long): Unit = Unit

    override suspend fun updateDownloadedEpisodes(sessionId: String, downloadedJson: String): Unit =
        Unit

    override suspend fun delete(sessionId: String): Unit = Unit

    override suspend fun deleteAll(): Unit = Unit

    override suspend fun count(): Int = entries?.size ?: 0
}
