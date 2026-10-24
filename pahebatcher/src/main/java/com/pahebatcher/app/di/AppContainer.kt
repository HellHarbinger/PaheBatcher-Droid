package com.pahebatcher.app.di

import android.content.ContentResolver
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.export.ExportDestinationRouter
import com.pahebatcher.app.data.export.MediaStoreExporter
import com.pahebatcher.app.data.export.OutputTreeRepository
import com.pahebatcher.app.data.export.SafExporter
import com.pahebatcher.app.data.hls.AesKeyCache
import com.pahebatcher.app.data.hls.HlsHostConcurrencyLimiter
import com.pahebatcher.app.data.hls.OkHttpHlsClient
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.network.AndroidNetworkMonitor
import com.pahebatcher.app.data.network.NetworkMonitor
import com.pahebatcher.app.data.network.NetworkStatus
import com.pahebatcher.app.data.playback.PlaybackPositionRepository
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.AudioOptionRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.data.repository.SessionActions
import com.pahebatcher.app.data.repository.SessionRepository
import com.pahebatcher.app.data.repository.WatchlistRepository
import com.pahebatcher.app.data.session.AndroidCloudflareSession
import com.pahebatcher.app.data.session.AndroidSessionPersistence
import com.pahebatcher.app.data.session.AndroidWebViewRequestBridge
import com.pahebatcher.app.data.session.WebViewHttpClient
import com.pahebatcher.app.data.stream.HlsPlaybackSourceFactory
import com.pahebatcher.app.data.stream.KwikResolver
import com.pahebatcher.app.data.stream.StreamResolverImpl
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.SessionPersistence
import com.pahebatcher.app.domain.session.WebViewRequestBridge
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.domain.watchlist.WatchlistChecker
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.notification.NotificationPermissionRepository
import com.pahebatcher.app.playback.MediaSessionHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manual DI composition root (PHASE11_ARCHITECTURE_V2 §34).
 *
 * Owned by [com.pahebatcher.app.PaheBatcherApplication]. No Hilt/Koin: the container
 * fits a single file and an extra DI framework would add KSP processing, runtime, and
 * APK surface for no gain (§34 rationale, §32 avoidability test).
 *
 * ViewModels obtain collaborators via [AppViewModelFactory] keyed to this container;
 * composables never see it (screens receive state + lambdas only).
 *
 * Composition order mirrors the session chain (AGENTS §11 — session handling stays
 * behind explicit abstractions):
 *
 *   sessionPersistence → cloudflareSession → requestBridge → WebViewHttpClient →
 *   AnimeRepository (search foundation, §14)
 *
 * plus the Phase 10 download orchestration repository (dashboard actions, §16) and
 * the Phase 11 §20 notification-permission state. Export's [contentResolver] backs
 * the Slice 6 [mediaStoreExporter] (§30.1), shared by workers and reconciliation.
 * The 11.5E-2 details slice adds the episode-scan pair — [scanCacheRepository] over
 * Room and [episodeRepository] over [animeRepository] — on top of that same chain.
 * The 11.5E-3 slice binds the EXISTING [StreamResolverImpl] over that same
 * [cloudflareSession] (no second session, no duplicate resolver) so an episode
 * selection can resolve its stream through the production graph.
 *
 * The container binds the ONE [HlsPlaybackSourceFactory], whose type
 * is `@UnstableApi`. The opt-in is declared here (class level) rather than on
 * the property itself because Lint's `UnsafeOptInUsageError` only honours
 * `@OptIn` on classes and functions, not on Kotlin properties.
 */
@OptIn(markerClass = [UnstableApi::class])
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Room database (Phase 10 singleton — shared with WorkManager's factory). */
    val database: PaheDatabase by lazy { PaheDatabase.getInstance(appContext) }

    /** Existing preferences DataStore (§6: theme keys live here, no second store). */
    val appConfig: AppConfig by lazy { AppConfig(appContext) }

    /**
     * Slice C — the app-scoped notification builder/poster, shared with the
     * WorkManager factory chain so a ViewModel-posted batch summary and a
     * Worker-posted episode failure are built by the same object (same channel
     * set, same group, same destination contract).
     */
    val downloadNotificationManager: DownloadNotificationManager by lazy {
        DownloadNotificationManager(appContext)
    }

    /**
     * The ONE application-scoped connectivity source
     * — a default-network callback exposed as [NetworkStatus]. Every
     * network-dependent ViewModel receives this exact instance through
     * `AppViewModelFactory`; no composable registers or owns a callback.
     */
    val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext) }

    /**
     * App-scoped MediaSession host.
     * Holds the player/episodeId/session triangle and starts/stops the
     * PaheMediaSessionService. Injected into PlayerViewModel.
     */
    val mediaSessionHost: MediaSessionHost by lazy { MediaSessionHost(appContext) }

    /**
     * Injected for MediaStore export/reconciliation (§30.1) — export resolves
     * `outputUri` through this resolver, never through scattered static lookups.
     */
    val contentResolver: ContentResolver
        get() = appContext.contentResolver

    /**
     * Slice 6 (§30.1): the process-wide MediaStore export owner. One instance
     * per AppContainer, shared by every DownloadWorker (via DownloadWorkerFactory)
     * and by startup reconciliation's retry/sweep path; the exporter's companion
     * `operationLock` serializes all export/sweep operations app-wide (§30.4).
     */
    val mediaStoreExporter: MediaStoreExporter by lazy {
        MediaStoreExporter(
            contentResolver = contentResolver,
            episodeDao = database.episodeDownloadDao(),
            // Gate A: the per-anime export directory derives from the parent
            // session row's title (DownloadSessionEntity.title — the single
            // persisted title source, no duplicate title state).
            sessionDao = database.downloadSessionDao(),
        )
    }

    /**
     * SAF output-tree persistence and
     * validation over the EXISTING DataStore (`output_tree_uri`). Startup
     * reconciliation calls [OutputTreeRepository.validate] once per process;
     * the Settings output-location row consumes the same state later.
     */
    val outputTreeRepository: OutputTreeRepository by lazy {
        OutputTreeRepository(
            contentResolver = contentResolver,
            config = appConfig,
        )
    }

    /**
     * The ONE export surface every caller uses —
     * the destination router picks the validated SAF tree or the MediaStore
     * default per export. Shared by DownloadWorker (via DownloadWorkerFactory,
     * both factory sites in PaheBatcherApplication) and startup reconciliation.
     */
    val episodeExporter: EpisodeExporter by lazy {
        ExportDestinationRouter(
            outputTrees = outputTreeRepository,
            config = appConfig,
            safExporter = SafExporter(
                contentResolver = contentResolver,
                episodeDao = database.episodeDownloadDao(),
                config = appConfig,
                sessionDao = database.downloadSessionDao(),
            ),
            mediaStoreExporter = mediaStoreExporter,
        )
    }

    /**
     * Application-context seam for the Phase 11 §24.1 player: the nav-entry-scoped
     * PlayerViewModel constructs its single ExoPlayer through
     * [AppViewModelFactory] using this context — never from a composable.
     */
    val context: Context
        get() = appContext

    /** Playback-position persistence over Room v3 (§29 — PK episodeId, no TTL). */
    val playbackPositionRepository: PlaybackPositionRepository by lazy {
        PlaybackPositionRepository(database.playbackPositionDao())
    }

    // --- Search foundation chain (over the session persistence machinery) ---

    private val sessionPersistence: SessionPersistence by lazy {
        AndroidSessionPersistence(appContext)
    }

    private val cloudflareSession: CloudflareSessionProvider by lazy {
        AndroidCloudflareSession(appContext, sessionPersistence)
    }

    /**
     * SPEC §6.10 (final-audit H1): the force-refresh the user's prompt triggers.
     *
     * The ONLY way the UI reaches session handling: the root prompt's ViewModel
     * calls this through [AppViewModelFactory], never a WebView or provider
     * reference directly (AGENTS §4/§11 — session handling stays behind the
     * provider abstraction; UI components see state + lambdas only).
     *
     * `forceRefresh = true` destroys the current session and runs a fresh
     * bootstrap — the same operation the bridge performs for its own automatic
     * retry, now also available on explicit user request.
     *
     * AQR-013/AQR-024: this UI entry point takes the SAME page lock the bridge
     * and resolver hold for their page work — without it a user-initiated
     * force-refresh destroyed the shared WebView underneath an in-flight page
     * operation (cross-captures / restore races). It must NOT go through
     * [AndroidWebViewRequestBridge] (its retry re-enters `establishSession`
     * while holding this very lock — Mutex is not re-entrant, that deadlocks);
     * only serialising HERE against the lock is safe.
     */
    suspend fun forceRefreshWebViewSession(): CloudflareSessionProvider.SessionInfo =
        webViewPageLock.withLock {
            cloudflareSession.establishSession(forceRefresh = true)
        }

    /**
     * The ONE page lock over the app-scoped WebView.
     *
     * The transport is a single `WebView(applicationContext)` whose capture client
     * is swapped per request, so exactly one page operation may run at a time.
     * Both production entry points take this lock:
     *
     * - [requestBridge] → every AnimePahe HTTP request (scan/search/title/pages)
     * - [streamResolver] → play-page + Kwik resolution
     *
     * Phase 12D made the second caller systematic: the background
     * `WatchlistCheckWorker` runs the periodic check while any screen is doing its
     * own requests. One instance, shared by both — a per-caller lock would still
     * let the two entry points interleave.
     */
    private val webViewPageLock: Mutex by lazy { Mutex() }

    private val requestBridge: WebViewRequestBridge by lazy {
        AndroidWebViewRequestBridge(cloudflareSession, webViewPageLock)
    }

    private val httpClient: HttpClient by lazy { WebViewHttpClient(requestBridge) }

    /**
     * Real AnimePahe search repository. Built over the WebView session client so
     * Cloudflare-protected requests reuse the established session (AGENTS §11/§4 —
     * the UI never performs HTTP directly).
     */
    val animeRepository: AnimeRepository by lazy { AnimeRepository(httpClient) }

    // --- Episode scan (11.5E-2: the anime details destination) ---

    /**
     * Room-backed scan cache — the SAME cache [EpisodeRepository] already used in
     * tests (TTL lookup/save); nothing here duplicates it, the binding only makes
     * the existing implementation constructible in production.
     */
    val scanCacheRepository: ScanCacheRepository by lazy {
        ScanCacheRepository(database.scanCacheDao())
    }

    /**
     * Episode scanning for the details destination: variant discovery + paginated
     * episode API over the existing search repository, cache behind
     * [scanCacheRepository]. Constructed here, unchanged (11.5E-2 wiring only).
     */
    val episodeRepository: EpisodeRepository by lazy {
        EpisodeRepository(
            animeRepository = animeRepository,
            scanCacheRepository = scanCacheRepository,
        )
    }

    /**
     * DECISIONS 144 — the bounded play-page audio-option probe: the targets come
     * from the scan, the pages come from the SAME [cloudflareSession] the scan,
     * HTTP bridge and resolver use, the pass is serialised by the SAME
     * [webViewPageLock] those page operations already hold, and the result is
     * persisted by the SAME [scanCacheRepository]. No second session, no second
     * HTTP stack, no second cache.
     */
    val audioOptionRepository: AudioOptionRepository by lazy {
        AudioOptionRepository(
            sessionProvider = cloudflareSession,
            pageLock = webViewPageLock,
            scanCacheRepository = scanCacheRepository,
        )
    }

    // --- Stream resolution (11.5E-3: episode selection → StreamResolverImpl) ---

    /**
     * Kwik → M3U8 extraction over the SAME [cloudflareSession] the search and scan
     * chain already use (Decision 29: WebView session instead of curl_cffi/FlareSolverr).
     * Only bound here so the existing implementation is constructible in production —
     * the class itself is unchanged.
     */
    val kwikResolver: KwikResolver by lazy { KwikResolver(cloudflareSession) }

    /**
     * Production stream resolver (11.5E-3): play-page fetch through the established
     * Cloudflare session → quality/audio selection → Kwik → M3U8 stream information.
     *
     * The ONE binding of the EXISTING [StreamResolverImpl]; the anime details entry
     * reaches it through [AppViewModelFactory]. No fake resolver exists in production,
     * and the class is bound as the domain [StreamResolver] interface. It shares
     * [webViewPageLock] with [requestBridge] (Phase 12D) so resolution and HTTP
     * requests can never drive the one WebView concurrently.
     */
    val streamResolver: StreamResolver by lazy {
        StreamResolverImpl(
            sessionProvider = cloudflareSession,
            kwikResolver = kwikResolver,
            pageLock = webViewPageLock,
        )
    }

    /**
     * The ONE binding of [HlsPlaybackSourceFactory].
     *
     * App-scoped rather than per-player-entry so its single OkHttp client (and
     * therefore its connection pool) is shared across entries instead of being
     * rebuilt for every playback — the transport itself never touches the
     * WebView session; the [StreamInfo] headers it replays come from the
     * resolver's result, which is the boundary (§3.2).
     *
     * Only the `HlsMediaSource` is created per stream, by the player.
     */
    val hlsPlaybackSourceFactory: HlsPlaybackSourceFactory by lazy {
        HlsPlaybackSourceFactory()
    }

    // --- HLS download transport (A2.1 Decision 5 — DL-007/DATA-012) ---

    /**
     * The ONE process-wide per-host request limiter.
     *
     * Python caps concurrent requests to a single host through its shared aiohttp
     * session (`http.py` `TCPConnector(limit_per_host=hls_workers)`). Every Worker
     * this container feeds shares this single instance, so however many run at
     * once they still share one ceiling per host instead of each adding their own
     * `hls_workers` worth. A2.1 moved it here from `PaheBatcherApplication` so the
     * shared [hlsClient] can be built around it.
     *
     * A2.1 Decision 6: the persisted `hls_workers` is AUTHORITATIVE — read fresh
     * the first time each host is seen (settings-change semantics: new hosts get
     * the current value; already-tracked hosts keep their creation-time
     * ceiling). The clamp mirrors the Settings write path's own band, so a
     * corrupt persisted value can never undershoot the floor — let alone reach
     * 0, which would freeze the host behind a zero-permit semaphore.
     */
    val hlsHostLimiter: HlsHostConcurrencyLimiter by lazy {
        HlsHostConcurrencyLimiter(
            limitProvider = {
                appConfig.currentAppContext()
                    .hlsWorkers
                    .coerceIn(AppContext.MIN_HLS_WORKERS, AppContext.MAX_HLS_WORKERS)
            },
        )
    }

    /**
     * A2.1 Decision 5 (DL-007/DATA-012): the ONE app-scoped HLS client — and
     * therefore ONE OkHttp connection pool — every DownloadWorker SHARES,
     * instead of each worker building its own client (own pool) around the same
     * limiter. Python parity: aiohttp's single session. All authorisation stays
     * PER-REQUEST (AGENTS §10 — no cookies or headers are cached here), and the
     * budget-derived clients the caller derives share this pool too.
     */
    val hlsClient: HlsClient by lazy { OkHttpHlsClient(hlsHostLimiter) }

    /**
     * A2.1 Decision 5 (DL-007/DATA-012): the ONE app-scoped AES-128 key cache
     * (TTL 1 h / 128 keys, Python's TTLCache parity) every worker SHARES, so an
     * encrypted episode's key is fetched once per process, not once per worker.
     * Thread-safe by construction (ConcurrentHashMap).
     */
    val aesKeyCache: AesKeyCache by lazy { AesKeyCache() }

    // --- Downloads (Phase 10 orchestration; Phase 11 dashboard drives it, §16) ---

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(
            appContext,
            database.episodeDownloadDao(),
            // 11.5E-4: the parent session row must exist before an episode row —
            // `episode_downloads.sessionId` is a foreign key (PRAGMA foreign_keys=ON).
            database.downloadSessionDao(),
            // 11.5E-7 (§24.1): Downloads-screen Retry re-resolves through the
            // SAME production resolver the details screen uses (app-scoped
            // WebView session) — never a second pipeline.
            streamResolver = streamResolver,
            // fresh Wi-Fi-only read at every enqueue
            // (initial queue and retry re-enqueue both end in enqueueWorker).
            wifiOnlyProvider = { appConfig.wifiOnly.first() },
            // fresh Auto-resume read at every enqueue.
            autoResumeProvider = { appConfig.autoResume.first() },
            // Slice B (DECISIONS 146): production wires the REAL resolver, so a
            // persisted `outputUri` is verified through ContentResolver before
            // any caller is told the episode is already downloaded — the same
            // §30.1 precedent as DownloadStateReconciler. Appended last so the
            // 20+ positional host-constructed call sites keep compiling.
            contentResolver = contentResolver,
            // §3.1 (DECISIONS 157): the destination probe, so a re-download over a
            // surviving export is suppressed at the gate instead of adopting the old
            // file and discarding the fresh bytes.
            destinationProbe = episodeExporter,
        )
    }

    // --- Sessions & watchlist (Phase 12A backend/data foundation) ---

    /**
     * Read/summary/state/deletion surface over the session rows the
     * production pipeline already writes (`DownloadRepository` creates them).
     * Bound here so the foundation is constructible in production; the sessions
     * screen binds to it in the UI phase (no UI is shipped in 12A/12B itself).
     */
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            sessionDao = database.downloadSessionDao(),
            episodeDao = database.episodeDownloadDao(),
        )
    }

    /**
     * Phase 13 (DECISIONS 94/106/107) + final SPEC §20 gap: the Sessions
     * screen's aggregate actions (Cancel, Retry, Pause, Resume, Delete session,
     * Delete temporary cache, Clear all cache) — bound to the production
     * per-row pipeline (cancel/reEnqueue/pause/resume), the room-backed session
     * repository and the destination-routing exporter for export-first deletion.
     * The UI reaches them only through this instance.
     */
    val sessionActions: SessionActions by lazy {
        SessionActions(
            sessionRepository = sessionRepository,
            episodeDao = database.episodeDownloadDao(),
            cancelRow = { downloadRepository.cancel(it) },
            retryRow = { downloadRepository.reEnqueue(it) },
            pauseRow = { downloadRepository.pause(it) },
            resumeRow = { downloadRepository.resume(it) },
            exporter = episodeExporter,
        )
    }

    /**
     * Watchlist persistence over the EXISTING `watchlist` table
     * (schema v3, no migration), implementing Python `WatchlistManager`
     * semantics (add/find/remove + downloaded history). Bound here as the
     * production constructible path; the watchlist UI arrives in a later phase.
     */
    val watchlistRepository: WatchlistRepository by lazy {
        WatchlistRepository(database.watchlistDao())
    }

    /**
     * `run_watchlist_check` (`watchlist.py:685-1118`) as a use case,
     * bound to the PRODUCTION collaborators — the WebView-backed repositories
     * (scan/search), the production resolver, the production download queue, and
     * the same app-private directory [DownloadRepository] writes into
     * (`filesDir/downloads`), which is this checker's "series folder".
     *
     * Consumed from two places since Phase 12D (DECISIONS 91), through this ONE
     * instance: the Watchlist screen's `Check now` and the background
     * `WatchlistCheckWorker` (reached through `DownloadWorkerFactory`). Both
     * drive the application-scoped WebView transport — no Activity, no second
     * session, no Worker→Activity hand-off of any kind.
     */
    val watchlistChecker: WatchlistChecker by lazy {
        WatchlistChecker(
            episodeRepository = episodeRepository,
            animeRepository = animeRepository,
            scanCacheRepository = scanCacheRepository,
            watchlistRepository = watchlistRepository,
            streamResolver = streamResolver,
            downloadQueue = downloadRepository,
            downloadRoot = { java.io.File(appContext.filesDir, "downloads") },
            configProvider = { appConfig.currentAppContext() },
        )
    }

    // --- Notification permission state (Phase 11 §20) ---

    val notificationPermissionRepository: NotificationPermissionRepository by lazy {
        NotificationPermissionRepository(appConfig, appContext)
    }
}
