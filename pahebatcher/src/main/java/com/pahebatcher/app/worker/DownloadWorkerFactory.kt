package com.pahebatcher.app.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.pahebatcher.app.data.download.DownloadConfig
import com.pahebatcher.app.data.hls.AesKeyCache
import com.pahebatcher.app.data.hls.OkHttpHlsClient
import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.watchlist.WatchlistChecker
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.notification.DownloadNotifyPrefs
import com.pahebatcher.app.notification.WatchlistNotificationManager

/**
 * Custom WorkerFactory for dependency injection into DownloadWorker and
 * WatchlistCheckWorker.
 * Phase 10 architecture v10 §7 (extended in Phase 12D, DECISIONS 91).
 *
 * WorkManager requires a zero-argument constructor or custom WorkerFactory.
 * This factory manually provides all dependencies.
 *
 * Contract, unchanged for every class this factory does not own: an unknown
 * `workerClassName` still returns `null`, so WorkManager falls back to its own
 * default construction exactly as before (Phase 10 behaviour, still covered by
 * the tests that exercise DownloadWorker creation).
 *
 * The watchlist branch is fed the ONE application-scoped
 * [WatchlistChecker] `AppContainer` already builds over Room + the app-scoped
 * WebView transport — no second checker, no second repository graph, no Activity.
 * Production wires it in both `PaheBatcherApplication` factory call sites; a
 * composition that forgot it is a programming error, so that case fails loudly
 * instead of silently never scheduling a check.
 */
class DownloadWorkerFactory(
    private val database: PaheDatabase,
    private val notificationManager: DownloadNotificationManager,
    private val config: DownloadConfig,
    // Slice 6 (§30.1): nullable so pre-export-slice call sites keep compiling.
    // the destination router behind the interface.
    private val exporter: EpisodeExporter? = null,
    // persistent settings read once per Worker run.
    // Null in tests/legacy call sites, which then keep the injected `config`.
    private val configSource: com.pahebatcher.app.data.download.DownloadConfigSource? = null,
    // the app-scoped watchlist use case for
    // WatchlistCheckWorker. Null only in compositions that never enqueue it.
    private val watchlistChecker: WatchlistChecker? = null,
    // the CURRENT Wi-Fi-only setting, read fresh at
    // each deferred-episode enqueue inside DownloadWorker. Default OFF = the
    // setting's own default, so existing call sites behave exactly as before.
    private val wifiOnlyProvider: suspend () -> Boolean = { false },
    // the CURRENT completion/failure notification
    // preferences, read fresh at each terminal post inside DownloadWorker.
    // Default = the shipped DataStore defaults (both ON), so existing call
    // sites behave exactly as before.
    private val notifyPrefsProvider: suspend () -> DownloadNotifyPrefs = { DownloadNotifyPrefs() },
    // the CURRENT `New watchlist episodes`
    // preference, read fresh by WatchlistCheckWorker after each check. Default
    // = the shipped DataStore default (ON), so existing call sites behave
    // exactly as before.
    private val watchlistNotifyProvider: suspend () -> Boolean = { true },
    // the CURRENT `Auto-resume
    // downloads` setting, read fresh at the moment a transport failure is
    // decided inside DownloadWorker (DECISIONS 105.4). Default OFF = the
    // setting's own shipped default, so existing call sites fail terminally
    // exactly as before.
    private val autoResumeProvider: suspend () -> Boolean = { false },
    // Final parity cycle: the ONE app-scoped per-host request limiter, so every
    // Worker this factory creates shares Python's `limit_per_host` ceiling
    // instead of each contributing an unbounded `hls_workers` of its own.
    // Test-composition fallback only (AQR-062): production reaches this exact
    // limiter through the shared hlsClient, so the fallback below never runs there.
    private val hostLimiter: com.pahebatcher.app.data.hls.HlsHostConcurrencyLimiter? = null,
    // A2.1 Decision 5 (DL-007/DATA-012): the ONE app-scoped HLS client (one
    // OkHttp connection pool for the whole process) and the ONE AES-key cache
    // every worker SHARES, built once by AppContainer — Python parity with
    // aiohttp's single session plus its single TTL key cache. Null keeps the
    // previous per-worker construction for test compositions that never
    // download.
    private val hlsClient: HlsClient? = null,
    private val keyCache: AesKeyCache? = null,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        if (workerClassName == WatchlistCheckWorker::class.java.name) {
            val checker = watchlistChecker ?: error(
                "WatchlistCheckWorker requested but no WatchlistChecker was provided " +
                    "to DownloadWorkerFactory (see PaheBatcherApplication)",
            )
            return WatchlistCheckWorker(
                context = appContext,
                params = workerParameters,
                checker = checker,
                // Built here rather than injected: it holds no state beyond the
                // Context WorkManager already gives us, so a composition cannot
                // forget it — while the *preference* that gates it stays
                // injectable, because that is the value under test.
                notificationManager = WatchlistNotificationManager(appContext),
                watchlistNotifyProvider = watchlistNotifyProvider,
            )
        }
        if (workerClassName != DownloadWorker::class.java.name) {
            return null
        }

        val client = hlsClient ?: OkHttpHlsClient(hostLimiter)
        val cache = keyCache ?: AesKeyCache()
        val dao = database.episodeDownloadDao()

        return DownloadWorker(
            context = appContext,
            params = workerParameters,
            dao = dao,
            notificationManager = notificationManager,
            hlsClient = client,
            keyCache = cache,
            config = config,
            configSource = configSource,
            exporter = exporter,
            wifiOnlyProvider = wifiOnlyProvider,
            notifyPrefsProvider = notifyPrefsProvider,
            autoResumeProvider = autoResumeProvider,
        )
    }
}
