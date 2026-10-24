package com.pahebatcher.app

import android.app.Application
import com.pahebatcher.app.logging.AppLog
import androidx.work.Configuration
import androidx.work.WorkManager
import com.pahebatcher.app.data.download.DownloadConfig
import com.pahebatcher.app.data.download.DownloadStateReconciler
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.di.AppContainer
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.notification.DownloadNotifyPrefs
import com.pahebatcher.app.worker.DownloadWorkerFactory
import com.pahebatcher.app.worker.WatchlistCheckScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application class for PaheBatcher.
 *
 *
 * Responsibilities:
 * - Initialize WorkManager with custom WorkerFactory
 * - Register the single periodic watchlist check (Phase 12D, DECISIONS 91),
 *   re-registering it when the Phase 14 check-frequency setting changes
 *   (PHASE14_PLAN §12.1)
 * - Trigger reconciliation on startup (idempotent)
 * - Manage application-scoped coroutine scope
 *
 * Uses explicit WorkManager initialization (not automatic ContentProvider)
 * to ensure the custom DownloadWorkerFactory is registered.
 */
class PaheBatcherApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "PaheBatcherApp"
    }

    /** Manual DI composition root - the app-wide dependency graph is built here. */
    val appContainer: AppContainer by lazy { AppContainer(this) }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { PaheDatabase.getInstance(this) }
    // PB-005 (A2.1): the ONE app-scoped DownloadNotificationManager — the
    // container already builds it (Slice C) for the ViewModel-posted surfaces,
    // so the worker-factory chain uses THAT instance instead of a second
    // manager over the same context.
    private val notificationManager: DownloadNotificationManager
        get() = appContainer.downloadNotificationManager
    private val config by lazy { DownloadConfig() }

    // A2.1 Decision 5 (DL-007/DATA-012): the per-host limiter, the HLS client
    // (one connection pool) and the AES-key cache now live app-scoped in
    // [AppContainer] so every Worker shares them — Python parity with aiohttp's
    // single session. (The limiter's KDoc moved with it.)

    /**
     * Persistent settings -> worker configuration.
     * The Worker reads it once per run (fresh persisted values, never a
     * process-lifetime snapshot).
     */
    private val downloadConfigSource by lazy {
        com.pahebatcher.app.data.download.AppConfigDownloadConfigSource(appContainer.appConfig)
    }

    /**
     * The CURRENT `Wi-Fi-only downloads` setting as
     * a fresh DataStore read — handed to every WorkRequest build site (both
     * worker-factory compositions and startup reconciliation). Invoked only at
     * an actual enqueue, so a settings change applies to the NEXT enqueue and
     * never rewrites already-queued work (105.2).
     */
    private val wifiOnlyProvider: suspend () -> Boolean = {
        appContainer.appConfig.wifiOnly.first()
    }

    /**
     * The terminal-notification preferences, read
     * FRESH at the moment a post is attempted — never cached, never read once at
     * startup — so flipping a Settings switch takes effect on the very next
     * terminal notification rather than at the next app start.
     *
     * Supplied through `DownloadWorkerFactory` (the plan's "same factory chain"
     * requirement) at BOTH WorkManager compositions below, so a worker running
     * after a Configuration rebuild reads the same live values as one started
     * from `onCreate`.
     */
    private val notifyPrefsProvider: suspend () -> DownloadNotifyPrefs = {
        DownloadNotifyPrefs(
            completion = appContainer.appConfig.notifyCompletion.first(),
            failure = appContainer.appConfig.notifyFailure.first(),
        )
    }

    /**
     * The `New watchlist episodes`
     * preference, read FRESH by `WatchlistCheckWorker` after each check — same
     * live-read contract as [notifyPrefsProvider], so a Settings flip shapes
     * the next background check without an app restart.
     */
    private val watchlistNotifyProvider: suspend () -> Boolean = {
        appContainer.appConfig.notifyWatchlist.first()
    }

    /**
     * The `Auto-resume downloads`
     * setting, read FRESH at the moment a transport failure is decided — never
     * cached at Worker start — so flipping the switch shapes the very next
     * failure instead of the next app restart. Supplied through
     * `DownloadWorkerFactory` at BOTH WorkManager compositions, exactly like
     * [notifyPrefsProvider] and [watchlistNotifyProvider].
     */
    private val autoResumeProvider: suspend () -> Boolean = {
        appContainer.appConfig.autoResume.first()
    }

    /**
     * A2.1 Decision 4 (DL-005): the CURRENT `Keep temporary files` setting,
     * read FRESH at each startup-reconciliation row so flipping the switch
     * shapes the very next reconcile pass instead of the next app restart —
     * the same live-read discipline as [wifiOnlyProvider]/[autoResumeProvider].
     * Download COMPLETION already reads it per-request through DownloadConfig.
     */
    private val keepTempProvider: suspend () -> Boolean = {
        appContainer.appConfig.currentAppContext().keepTemp
    }

    /**
     * DATA-010: the ONE `DownloadWorkerFactory`, shared by `onCreate`'s explicit
     * `WorkManager.initialize` and by [workManagerConfiguration]. Composing it in
     * both places duplicated 11 named arguments — any future argument added to one
     * site and not the other silently produced two differently-wired factories.
     */
    private val workerFactory by lazy {
        DownloadWorkerFactory(
            database = database,
            notificationManager = notificationManager,
            config = config,
            configSource = downloadConfigSource,
            exporter = appContainer.episodeExporter,
            // the app-scoped watchlist use case the
            // background WatchlistCheckWorker runs through.
            watchlistChecker = appContainer.watchlistChecker,
            // fresh Wi-Fi-only read at each
            // deferred-episode enqueue the worker performs.
            wifiOnlyProvider = wifiOnlyProvider,
            // fresh terminal-notification
            // preference read at the moment each post is attempted.
            notifyPrefsProvider = notifyPrefsProvider,
            // fresh watchlist-notification preference
            // read by WatchlistCheckWorker after each check.
            watchlistNotifyProvider = watchlistNotifyProvider,
            // fresh auto-resume read at the
            // moment a transport failure is decided.
            autoResumeProvider = autoResumeProvider,
            // Final parity cycle: shared per-host request ceiling.
            hostLimiter = appContainer.hlsHostLimiter,
            // A2.1 Decision 5 (DL-007/DATA-012): the ONE app-scoped HLS client
            // (one connection pool) + AES-key cache every worker shares.
            hlsClient = appContainer.hlsClient,
            keyCache = appContainer.aesKeyCache,
        )
    }

    private val reconciliationRun = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        AppLog.d(TAG, "Application created")

        // the in-memory log buffer is retained ONLY
        // while the persisted `Show Console` setting is ON. OFF (the default)
        // means AppLog keeps no history at all; turning it ON starts retention,
        // turning it OFF drops future lines and clears the retention. Logcat
        // output is unconditional either way.
        applicationScope.launch {
            // AQR-041 (related site): a corrupt/unreadable DataStore makes this
            // collect THROW instead of emitting — uncaught in applicationScope
            // that is a crash loop. Degrade to "buffering keeps its last value"
            // for the rest of this process (CE still propagates).
            try {
                appContainer.appConfig.showConsole.collect { enabled ->
                    AppLog.setBufferingEnabled(enabled)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Console setting observation stopped: ${e.message}")
            }
        }

        // Explicitly initialize WorkManager with custom WorkerFactory
        // This ensures the DownloadWorkerFactory is registered before any Worker is triggered
        val workManagerConfig = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
        WorkManager.initialize(this, workManagerConfig)
        AppLog.d(TAG, "WorkManager initialized with custom WorkerFactory")

        // Phase 12D/Phase 14 (§12.1): the ONE periodic watchlist check (SPEC
        // §19: WorkManager periodic work, minimum interval, no background
        // service). Registered from the process-level lifecycle — never from an
        // Activity, the Watchlist screen or a button press — and re-registered
        // whenever the persisted frequency changes.
        //
        // `distinctUntilChanged()` is load-bearing: `watchlistCheckIntervalMinutes`
        // maps the whole `dataStore.data` stream, which re-emits on ANY settings
        // write, so without it every unrelated toggle would re-issue a periodic
        // UPDATE. The first emission registers (or reconciles) the stored value;
        // later ones apply real interval changes only.
        applicationScope.launch {
            try {
                val workManager = WorkManager.getInstance(this@PaheBatcherApplication)
                appContainer.appConfig.watchlistCheckIntervalMinutes
                    .distinctUntilChanged()
                    .collect { minutes ->
                        WatchlistCheckScheduler.schedule(workManager, minutes)
                        AppLog.i(
                            TAG,
                            "Watchlist check schedule: " +
                                WatchlistCheckScheduler.describe(workManager, minutes),
                        )
                    }
            } catch (e: CancellationException) {
                // Same rule as every other coroutine catch in this codebase: scope
                // cancellation is a normal lifecycle event, not a failure to log.
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to schedule the periodic watchlist check: ${e.message}")
            }
        }

        // Run reconciliation on first process start
        // Idempotent: AtomicBoolean ensures reconciliation runs only once per process
        if (reconciliationRun.compareAndSet(false, true)) {
            applicationScope.launch {
                try {
                    // re-validate the persisted
                    // SAF output tree once per restart — grant + tree document.
                    // Never blocks or fails a download (92.3): an unusable tree
                    // only means the MediaStore default for future exports, plus
                    // the surfaced Settings state.
                    val treeState = appContainer.outputTreeRepository.validate()
                    AppLog.d(TAG, "Output tree state: $treeState")

                    val reconciler = DownloadStateReconciler(
                        episodeDao = database.episodeDownloadDao(),
                        workManager = WorkManager.getInstance(this@PaheBatcherApplication),
                        contentResolver = contentResolver,
                        // Slice 6 (§30): export retry for COMPLETED rows whose local
                        // file never made it to MediaStore + §28 stale-IS_PENDING sweep.
                        // sweepCutoffEpochMs defaults to this construction instant
                        // (≈ process start), so current-run pending rows are never swept.
                        exporter = appContainer.episodeExporter,
                        // re-enqueues build NEW requests,
                        // so each reads the CURRENT Wi-Fi-only setting.
                        wifiOnlyProvider = wifiOnlyProvider,
                        // same fresh-read contract for
                        // the Auto-resume setting.
                        autoResumeProvider = autoResumeProvider,
                        // A2.1 Decision 4 (DL-005): same fresh-read contract for
                        // Keep temporary files — the COMPLETED-row segment sweep
                        // asks the setting per row, never a startup snapshot.
                        keepTempProvider = keepTempProvider,
                    )
                    reconciler.reconcile()
                    AppLog.d(TAG, "Reconciliation completed")
                } catch (e: CancellationException) {
                    // AQR-004: a cancelled startup job is not a failure.
                    throw e
                } catch (e: Exception) {
                    AppLog.e(TAG, "Reconciliation failed: ${e.message}")
                }
            }
        }
    }

    /**
     * WorkManager Configuration.Provider implementation.
     * Provides custom WorkerFactory for dependency injection.
     *
     * Note: We also call WorkManager.initialize() explicitly in onCreate()
     * to ensure the custom factory is registered before any ContentProvider
     * tries to use WorkManager.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
