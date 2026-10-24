package com.pahebatcher.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.export.OutputTreeRepository
import com.pahebatcher.app.data.export.OutputTreeState
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.ThemeMode
import com.pahebatcher.app.domain.model.ThemeSettings
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.notification.NotificationPermissionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * SettingsUiState — Phase 12A: the theme controls from Phase 11 PLUS the engine
 * settings that production code really consumes (Phase 12A config wiring).
 *
 * Every field here is a persisted [AppConfig] value with a production consumer:
 *
 * | field            | persisted key | consumed by                                  |
 * |------------------|---------------|----------------------------------------------|
 * | themeSettings    | theme_mode /  | PaheBatcherTheme                             |
 * |                  | dynamic_color |                                              |
 * | quality          | quality       | AnimeDetailsViewModel → StreamResolver       |
 * | maxParallel      | max_parallel  | DownloadWorker gate + batch staging capacity |
 * | hlsWorkers       | hls_workers   | EpisodeDownloader segment semaphore          |
 * | cacheTtlMinutes  | cache_ttl     | EpisodeRepository.scan cache TTL             |
 * | keepTemp         | keep_temp     | EpisodeDownloader temp retention             |
 * | autoRetry        | auto_retry    | EpisodeDownloader retry budget + batch       |
 * |                  |               | passes / resolution attempts                 |
 * | resolveAhead     | resolve_ahead | batch staging bound (resolve-ahead depth)    |
 * | showConsole      | show_console  | Settings "Open console" entry (diagnostics)  |
 * | notifyCompletion | notify_       | DownloadWorker completion post site (14)    |
 * |                  | completion    |                                              |
 * | notifyFailure    | notify_       | DownloadWorker terminal-failure post site (14) |
 * |                  | failure       |                                              |
 * | notifyWatchlist  | notify_       | WatchlistCheckWorker post site (14, §12.2)   |
 * |                  | watchlist     |                                              |
 * | watchlistCheck   | watchlist_    | Application collector → periodic-work UPDATE |
 * | IntervalMinutes  | check_        | (14, §12.1)                                  |
 * |                  | interval_min  |                                              |
 *
 * NOT exposed (documented deferrals — a row must never exist without a consumer,
 * AGENTS §5): `cookie_string` (WebView session owns cookies, Decision 26),
 * `output_dir` (Phase 13 ships the Android-native SAF destination as its OWN
 * key `output_tree_uri` with the separate `Output location` projection — see
 * DECISIONS 92; this Python path key itself stays row-less, LIMITATIONS §5).
 *
 * Phase 12C re-evaluated `resolve_ahead`, which now has a real consumer (the
 * batch resolve-ahead gate), so it is exposed. It is offered as
 * a fixed set of practical depths including Python's default 999 (= unbounded
 * staging), which is what the batch really uses.
 *
 * `audio_lang` is deliberately ABSENT (SPEC §22/§23): audio is selected per
 * episode from that episode's variants, so [SettingsUiState] carries no
 * `audioLang`, [SettingsViewModel] has no setter for it, and no production
 * code reads the key.
 */
@Immutable
data class SettingsUiState(
    val themeSettings: ThemeSettings = ThemeSettings.DEFAULT,
    val quality: Int = AppContext.defaults().quality,
    val maxParallel: Int = AppContext.defaults().maxParallel,
    val hlsWorkers: Int = AppContext.defaults().hlsWorkers,
    val cacheTtlMinutes: Int = AppContext.defaults().cacheTtl,
    val keepTemp: Boolean = AppContext.defaults().keepTemp,
    val autoRetry: Int = AppContext.defaults().autoRetry,
    val resolveAhead: Int = AppContext.defaults().resolveAhead,
    val showConsole: Boolean = false,
    /**
     * `Wi-Fi-only downloads` — default OFF. The
     * ONLY consumer is the enqueue-time `NetworkType.UNMETERED` constraint at
     * the three WorkRequest build sites; toggling never rewrites queued work.
     */
    val wifiOnly: Boolean = false,
    /**
     * `Auto-resume downloads`,
     * default OFF — gates whether a TRANSPORT-class download failure may park
     * the row and return `Result.retry()` instead of failing terminally.
     */
    val autoResume: Boolean = false,
    /**
     * `Completion notifications`, default ON —
     * gates only the completed-episode post in `DownloadWorker`. The
     * foreground-service `download_progress` notification has no toggle (§R7).
     */
    val notifyCompletion: Boolean = true,
    /**
     * `Failure notifications`, default ON — gates
     * only the terminal-failure post in `DownloadWorker`.
     */
    val notifyFailure: Boolean = true,
    /**
     * `New watchlist episodes`, default ON — gates
     * only the aggregated post in `WatchlistCheckWorker`. A foreground
     * `checkNow()` never posts regardless of this flag (§12.2).
     */
    val notifyWatchlist: Boolean = true,
    /**
     * `watchlist_check_interval_minutes`,
     * default 15 — one of [AppConfig.WATCHLIST_INTERVAL_OPTIONS], re-registered
     * onto the unique periodic work by the Application collector.
     */
    val watchlistCheckIntervalMinutes: Long = AppConfig.DEFAULT_WATCHLIST_INTERVAL_MINUTES,
)

/**
 * The notification permission status row.
 *
 * Deliberately a SEPARATE projection from [SettingsUiState]: the grant state is
 * a platform fact (not a DataStore flow), so it is projected on demand — once at
 * construction, after every row action, and on every lifecycle resume (the user
 * may change it in system settings while this screen stays composed).
 *
 * The row derives everything from these three facts:
 *
 * - [granted] → "Allowed", no action;
 * - [runtimeRequestApplies] && !askedOnce → "Allow" launches the one-time
 *   system prompt (the same latch the first-enqueue prompt consumes);
 * - otherwise → "Open settings" (system notification settings for this app).
 */
@Immutable
data class NotificationPermissionUiState(
    /** The POST_NOTIFICATIONS runtime prompt exists (API 33+). */
    val runtimeRequestApplies: Boolean = false,
    /** Real grant state (install-time truth on API ≤ 32). */
    val granted: Boolean = false,
    /** The asked-once latch: false until the first runtime request is issued. */
    val askedOnce: Boolean = false,
)

/**
 * The four notification/scheduling flows folded into ONE upstream so
 * the outer `combine` in [SettingsViewModel] stays inside its typed five-flow
 * overload. A plain holder rather than four nested `Pair`s, which would
 * destructure into unreadable nested patterns — `combine` has no reusable
 * four-value tuple of its own.
 */
private data class NotificationSettings(
    val notifyCompletion: Boolean,
    val notifyFailure: Boolean,
    val notifyWatchlist: Boolean,
    val watchlistCheckIntervalMinutes: Long,
)

/**
 * Settings ViewModel over the EXISTING preferences DataStore (§6: no second
 * persistence mechanism). Writes go through [AppConfig] so the value shown here,
 * the value the theme applies, and the value the engine reads (worker/resolution)
 * share ONE source of truth.
 *
 * Each setter validates/clamps exactly like the Python reference
 * (`config_manager.py`) through the existing [AppConfig] update methods.
 */
class SettingsViewModel(
    private val appConfig: AppConfig,
    /**
     * Permission-state source for the notification
     * status row. Nullable so directly-constructed test VMs keep compiling;
     * with `null` the projection stays at its default and every action is a
     * no-op (the row is never rendered from tests anyway).
     */
    private val notificationPermissionRepository: NotificationPermissionRepository? = null,
    /**
     * The SAF export-destination source for the
     * `Output location` row. Same nullable-test-VM contract as above: `null`
     * keeps the projection at [OutputTreeState.NotConfigured] and every action
     * is a no-op.
     */
    private val outputTreeRepository: OutputTreeRepository? = null,
) : ViewModel() {

    private companion object {
        private const val TAG = "SettingsViewModel"
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        appConfig.themeSettings,
        appConfig.appContextFlow,
        appConfig.showConsole,
        appConfig.wifiOnly,
        // `kotlinx.coroutines.flow.combine` only has typed overloads up
        // to FIVE flows, so everything past the first four is folded into ONE
        // upstream before the outer combine rather than falling back to the
        // untyped `Array<*>` overload (which would need casts). The three
        // notification toggles, the check interval and the auto-resume toggle
        // are exactly five, so they fit a typed overload as a PAIR — auto-resume
        // is an engine setting, so it is not folded into `NotificationSettings`
        // itself, only carried alongside it.
        combine(
            appConfig.notifyCompletion,
            appConfig.notifyFailure,
            appConfig.notifyWatchlist,
            appConfig.watchlistCheckIntervalMinutes,
            appConfig.autoResume,
        ) { notifyCompletion, notifyFailure, notifyWatchlist, intervalMinutes, autoResume ->
            NotificationSettings(
                notifyCompletion,
                notifyFailure,
                notifyWatchlist,
                intervalMinutes,
            ) to autoResume
        },
    ) { themeSettings, appContext, showConsole, wifiOnly, engineAndNotifications ->
        val (notifications, autoResume) = engineAndNotifications
        val (notifyCompletion, notifyFailure, notifyWatchlist, watchlistCheckIntervalMinutes) =
            notifications
        SettingsUiState(
            themeSettings = themeSettings,
            quality = appContext.quality,
            maxParallel = appContext.maxParallel,
            hlsWorkers = appContext.hlsWorkers,
            cacheTtlMinutes = appContext.cacheTtl,
            keepTemp = appContext.keepTemp,
            autoRetry = appContext.autoRetry,
            resolveAhead = appContext.resolveAhead,
            showConsole = showConsole,
            wifiOnly = wifiOnly,
            autoResume = autoResume,
            notifyCompletion = notifyCompletion,
            notifyFailure = notifyFailure,
            notifyWatchlist = notifyWatchlist,
            watchlistCheckIntervalMinutes = watchlistCheckIntervalMinutes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        // §6/§7 code defaults until the first DataStore emission.
        initialValue = SettingsUiState(),
    )

    // --- Phase 13 (DECISIONS 96): notification permission status projection ---

    private val _notificationPermissionState = MutableStateFlow(NotificationPermissionUiState())
    val notificationPermissionState: StateFlow<NotificationPermissionUiState> =
        _notificationPermissionState.asStateFlow()

    // --- Phase 13 (DECISIONS 92/96): SAF export-destination projection ---
    //
    // Declared BEFORE init (Main.immediate can run the init block synchronously
    // during construction — same ordering rule as the permission state above).
    // A separate StateFlow again: `combine` in `uiState` is already at its
    // 5-overload limit and the tree state is not a DataStore flow.

    private val _outputLocationState = MutableStateFlow<OutputTreeState>(
        OutputTreeState.NotConfigured,
    )
    val outputLocationState: StateFlow<OutputTreeState> = _outputLocationState.asStateFlow()

    init {
        // Project the real permission state on construction (the remaining
        // refresh points are every row action and every lifecycle resume, both
        // driven from the screen).
        refreshNotificationPermission()
        // Project the persisted SAF destination on construction too — a fresh
        // validation (`OutputTreeRepository.validate()` is what Settings uses,
        // per its KDoc) so a folder revoked/deleted since the last run is
        // reported honestly the moment this screen is opened.
        refreshOutputLocation()
        // DECISIONS 104.4: the export path can flip `output_tree_rename_ok`
        // while this screen stays composed (a rename-less provider is
        // discovered at export time). Re-project the flag into the projected
        // state so the row never keeps claiming a destination exports stopped
        // using. DataStore re-emits on every write; a capability-flag flip
        // needs no provider re-query (AGENTS §10).
        viewModelScope.launch {
            appConfig.outputTreeRenameOk.collect { renameOk ->
                val current = _outputLocationState.value
                if (current is OutputTreeState.Valid && current.renameOk != renameOk) {
                    _outputLocationState.value = current.copy(renameOk = renameOk)
                }
            }
        }
    }

    /** Persist the theme mode (§4/§6: system | dark | light, default dark). */
    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch { appConfig.updateThemeMode(themeMode) }
    }

    /** Persist the dynamic-color toggle (§7: OFF by default). */
    fun setDynamicColor(dynamicColor: Boolean) {
        viewModelScope.launch { appConfig.updateDynamicColor(dynamicColor) }
    }

    /** Persist `quality` (360 | 720 | 1080, default 1080). */
    fun setQuality(quality: Int) {
        viewModelScope.launch { appConfig.updateQuality(quality) }
    }

    /** Persist `max_parallel` (clamped 1..6, default 2). */
    fun setMaxParallel(maxParallel: Int) {
        viewModelScope.launch { appConfig.updateMaxParallel(maxParallel) }
    }

    /** Persist `hls_workers` (clamped 8..32, default 24). */
    fun setHlsWorkers(hlsWorkers: Int) {
        viewModelScope.launch { appConfig.updateHlsWorkers(hlsWorkers) }
    }

    /** Persist `cache_ttl` in minutes (>= 0, default 60). */
    fun setCacheTtl(cacheTtlMinutes: Int) {
        viewModelScope.launch { appConfig.updateCacheTtl(cacheTtlMinutes) }
    }

    /** Persist `keep_temp` (default false). */
    fun setKeepTemp(keepTemp: Boolean) {
        viewModelScope.launch { appConfig.updateKeepTemp(keepTemp) }
    }

    /** Persist `auto_retry` (clamped 0..2, default 2). */
    fun setAutoRetry(autoRetry: Int) {
        viewModelScope.launch { appConfig.updateAutoRetry(autoRetry) }
    }

    /**
     * `resolve_ahead` (>= 0, default 999 = Python's unbounded
     * staging) — how far the batch resolver may run ahead of the downloaders.
     * [AppConfig.updateResolveAhead] applies the reference validation
     * (`max(0, int(v))`).
     */
    fun setResolveAhead(resolveAhead: Int) {
        viewModelScope.launch { appConfig.updateResolveAhead(resolveAhead) }
    }

    /** `show_console` (default OFF — the Console is opt-in). */
    fun setShowConsole(showConsole: Boolean) {
        viewModelScope.launch { appConfig.updateShowConsole(showConsole) }
    }

    /**
     * `wifi_only` (default OFF). Persisted only —
     * the value takes effect at the NEXT enqueue, and queued work keeps the
     * constraints it was built with (105.2).
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch { appConfig.updateWifiOnly(wifiOnly) }
    }

    /**
     * Persist `Auto-resume downloads`.
     *
     * Validation-free by design — it is a boolean; the eligibility rules live in
     * `DownloadWorker.shouldAutoResume`, not here.
     */
    fun setAutoResume(autoResume: Boolean) {
        viewModelScope.launch { appConfig.updateAutoResume(autoResume) }
    }

    /**
     * `notify_completion` (default ON). Persisted
     * only — the value is read fresh by `DownloadWorker` when it posts the next
     * completion notification, so it never rewrites anything already shown.
     */
    fun setNotifyCompletion(notifyCompletion: Boolean) {
        viewModelScope.launch { appConfig.updateNotifyCompletion(notifyCompletion) }
    }

    /** `notify_failure` (default ON), same fresh-read contract. */
    fun setNotifyFailure(notifyFailure: Boolean) {
        viewModelScope.launch { appConfig.updateNotifyFailure(notifyFailure) }
    }

    /**
     * `notify_watchlist` (default ON). The
     * `WatchlistCheckWorker` reads it fresh after each check, so a flip applies
     * to the NEXT background check and never to a notification already shown.
     */
    fun setNotifyWatchlist(notifyWatchlist: Boolean) {
        viewModelScope.launch { appConfig.updateNotifyWatchlist(notifyWatchlist) }
    }

    /**
     * `watchlist_check_interval_minutes`.
     *
     * Persisted only — the Application collector sees the new value and
     * re-registers the SAME unique periodic work with `ExistingPeriodicWorkPolicy.UPDATE`
     * (§12.1), so nothing else in the app is rescheduled and no download work is
     * touched. Membership in [AppConfig.WATCHLIST_INTERVAL_OPTIONS] is validated
     * by the AppConfig setter, not here: one validation site, not two.
     */
    fun setWatchlistCheckIntervalMinutes(minutes: Long) {
        viewModelScope.launch { appConfig.updateWatchlistCheckIntervalMinutes(minutes) }
    }

    // --- Phase 13 (DECISIONS 96): notification permission status row ---

    /**
     * One-shot projection of the REAL permission state — a platform fact, not a
     * DataStore flow, so every refresh point drives it explicitly: construction,
     * after each row action, and every lifecycle resume (the grant can change in
     * system settings while this screen stays composed).
     */
    fun refreshNotificationPermission() {
        val repository = notificationPermissionRepository ?: return
        viewModelScope.launch {
            _notificationPermissionState.value = projectSafely(
                fallback = _notificationPermissionState.value,
                tag = TAG,
                what = "Notification permission projection",
            ) { repository.projectPermissionState() }
        }
    }

    /**
     * The row's "Allow" launcher reported the user's real answer. The asked-once
     * latch is consumed EITHER way — a denial is never re-asked automatically
     * (the same latch the Phase 13 first-enqueue prompt checks), and "Open
     * settings" becomes the manual path from there.
     */
    fun onNotificationPermissionResult(granted: Boolean) {
        val repository = notificationPermissionRepository ?: return
        viewModelScope.launch {
            // AQR-041: both the latch WRITE and the re-projection run guarded —
            // a DataStore IOException keeps the previous projection instead of
            // crashing (CE still propagates).
            projectSafely(
                fallback = Unit,
                tag = TAG,
                what = "Recording notification permission outcome",
            ) {
                repository.recordRequestOutcome(granted)
                _notificationPermissionState.value = repository.projectPermissionState()
            }
        }
    }

    /** The single projection rule shared by every refresh point. */
    private suspend fun NotificationPermissionRepository.projectPermissionState(): NotificationPermissionUiState {
        val applies = runtimeRequestApplies()
        return NotificationPermissionUiState(
            runtimeRequestApplies = applies,
            granted = isGranted(),
            // shouldRequest() = runtimeRequestApplies && !askedOnce — so the
            // latch itself is derived without touching DataStore directly.
            askedOnce = applies && !shouldRequest(),
        )
    }

    // --- Phase 13 (DECISIONS 92/96): `Output location` row ---

    /**
     * One-shot projection of the persisted SAF destination — a grant/folder
     * fact (not a DataStore flow), so each refresh point drives it explicitly:
     * construction, after the picker result, after reverting to the default,
     * and every lifecycle resume (the folder can be deleted in the system
     * Files app while this screen stays composed).
     *
     * Uses [OutputTreeRepository.validate] — the fresh check its KDoc says
     * "startup / Settings use" — never the process-cached [OutputTreeRepository.state].
     */
    fun refreshOutputLocation() {
        val repository = outputTreeRepository ?: return
        viewModelScope.launch {
            _outputLocationState.value = projectSafely(
                fallback = _outputLocationState.value,
                tag = TAG,
                what = "Output location validation",
            ) { repository.validate() }
        }
    }

    /**
     * The SAF folder picker returned a real tree (DECISIONS 92 picker flow):
     * [OutputTreeRepository.setOutputTree] takes the persistable grant, persists
     * `output_tree_uri`, resets `output_tree_rename_ok` (104.4), and returns
     * the validated state — Valid on success. A cancelled picker never reaches
     * this method.
     *
     * The take can still fail (92.3: the grant offer is only valid while the
     * result is fresh — provider gone, offer expired), and this is the FIRST
     * production caller of `setOutputTree`, whose take is unguarded: catch here
     * so the failure degrades to the same honest Unusable reasons `validateTree`
     * reports instead of killing the viewModelScope. The key is deliberately
     * NOT persisted on failure — the repository configures only after the grant
     * is in hand.
     */
    fun onOutputTreePicked(treeUri: String) {
        val repository = outputTreeRepository ?: return
        viewModelScope.launch {
            _outputLocationState.value = try {
                repository.setOutputTree(treeUri)
            } catch (e: CancellationException) {
                // The tree write is a suspension point — a cancelled ViewModel
                // must not have its state mutated by a stale result (AQR-009).
                throw e
            } catch (e: Exception) {
                OutputTreeState.Unusable(
                    treeUri = treeUri,
                    reason = if (e is SecurityException) {
                        "folder access denied"
                    } else {
                        "folder inaccessible: ${e.message}"
                    },
                )
            }
        }
    }

    /**
     * Revert to the unset default (DECISIONS 92.2: MediaStore
     * `Movies/PaheBatcher/`): clears `output_tree_uri` AND releases the
     * persistable grant, landing on [OutputTreeState.NotConfigured].
     */
    fun useDefaultOutputLocation() {
        val repository = outputTreeRepository ?: return
        viewModelScope.launch {
            try {
                repository.clearOutputTree()
                _outputLocationState.value = OutputTreeState.NotConfigured
            } catch (e: CancellationException) {
                // The grant release is a suspension point (AQR-009).
                throw e
            } catch (e: Exception) {
                // The clear genuinely failed — leave the previous state in place
                // rather than reporting a default the repository did not land on.
                AppLog.w(TAG, "Clearing output tree failed: ${e.message}")
            }
        }
    }
}

/**
 * Human-readable identity of a SAF tree URI for the
 * `Output location` row description — the percent-decoded tree document ID
 * (`primary%3APaheBatcher` → `primary:PaheBatcher`, the format the system
 * picker itself displays).
 *
 * Pure string math (no `android.net.Uri`, no IPC) so it is JVM-testable; a
 * URI without a `/tree/` segment is malformed — return it verbatim rather than
 * inventing a label, and the row's Unusable reason explains the rest.
 */
internal fun outputTreeLabel(treeUri: String): String {
    val marker = "/tree/"
    val start = treeUri.indexOf(marker)
    if (start < 0) return treeUri
    val encoded = treeUri.substring(start + marker.length).substringBefore('/')
    return try {
        java.net.URLDecoder.decode(encoded, "UTF-8")
    } catch (_: Exception) {
        encoded
    }
}

/**
 * AQR-041 — the one-shot projection guard shared by the Settings refresh
 * points (`refreshNotificationPermission`, `onNotificationPermissionResult`,
 * `refreshOutputLocation`).
 *
 * Runs [block] and returns its value. On a NON-cancellation failure it logs
 * and returns [fallback] — the PREVIOUS projection — because these blocks
 * read DataStore inside `viewModelScope`, where an `IOException` from a
 * corrupt/unreadable prefs file used to reach the default uncaught handler
 * and crash the process on Settings open/resume. CancellationException always
 * propagates: a suspended ViewModel's own teardown must not be reported as a
 * projection fault (NET-001 contract, AQR-009).
 *
 * `internal` so `SettingsViewModelGuardTest` pins the contract on the JVM
 * with a throwing block — the production sites all route through here.
 */
internal suspend fun <T> projectSafely(
    fallback: T,
    tag: String,
    what: String,
    block: suspend () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppLog.w(tag, "$what failed: ${e.message}")
    fallback
}
