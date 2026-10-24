package com.pahebatcher.app.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.ThemeMode
import com.pahebatcher.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persistent application configuration using DataStore.
 * Ported from Python config_manager.py.
 * Persists user settings across app restarts.
 */
class AppConfig(private val context: Context) {

    companion object {
        /**
         * THE single DataStore instance for the settings file.
         *
         * Phase 12A correction: the delegate used to live in the class body, which
         * gave EVERY [AppConfig] instance its own DataStore for the same file —
         * AndroidX DataStore then aborts with "multiple DataStores active for the
         * same file" as soon as a second instance exists (settings read from the
         * test/worker side while the app container holds one). Hosting the delegate
         * in the companion (a process-wide singleton, exactly like
         * `PaheDatabase.getInstance`) makes every accessor share ONE store.
         */
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pahebatcher_settings")

        // Preference keys matching Python config keys.
        // NOTE: `audio_lang` is deliberately NOT among them — audio is selected
        // per episode (SPEC §22), so no code reads or writes the key anymore;
        // bytes a previous install left under it remain, inert and unread
        // (no migration machinery by decision).
        private val KEY_QUALITY = intPreferencesKey("quality")
        private val KEY_MAX_PARALLEL = intPreferencesKey("max_parallel")
        private val KEY_HLS_WORKERS = intPreferencesKey("hls_workers")
        private val KEY_OUTPUT_DIR = stringPreferencesKey("output_dir")
        private val KEY_KEEP_TEMP = booleanPreferencesKey("keep_temp")
        private val KEY_RESOLVE_AHEAD = intPreferencesKey("resolve_ahead")
        private val KEY_CACHE_TTL = intPreferencesKey("cache_ttl")
        private val KEY_COOKIE_STRING = stringPreferencesKey("cookie_string")
        private val KEY_AUTO_RETRY = intPreferencesKey("auto_retry")

        // whether the in-app diagnostics Console is reachable
        // from Settings. Android-only convenience setting; has no Python equivalent.
        private val KEY_SHOW_CONSOLE = booleanPreferencesKey("show_console")

        // Wi-Fi-only downloads — a SPEC §23 setting
        // whose ONLY consumer is the enqueue-time WorkManager constraint at the
        // three WorkRequest build sites (NetworkType.UNMETERED). Default OFF =
        // zero behavior change for existing users (105.5).
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")

        // `Auto-resume
        // downloads`, default OFF. OFF keeps failure semantics byte-for-byte
        // today's (DECISIONS 105.5) until the user opts in. §18 names two
        // consumers, both reading it FRESH: `DownloadWorker`'s auto-resume
        // eligibility (only a TRANSPORT-class failure with this ON and the
        // attempt budget left may become `Result.retry()`), and the enqueue-time
        // constraint helper at the three WorkRequest build sites (§9).
        private val KEY_AUTO_RESUME = booleanPreferencesKey("auto_resume")

        // the three notification-posting toggles on
        // the EXISTING DataStore (§6: no second store). All default ON, so a
        // fresh install and an upgrade from a pre-Phase-14 install (keys absent)
        // behave exactly like the app does today — flipping any of them is a
        // deliberate user opt-OUT, never an implicit opt-in.
        private val KEY_NOTIFY_COMPLETION = booleanPreferencesKey("notify_completion")
        private val KEY_NOTIFY_FAILURE = booleanPreferencesKey("notify_failure")
        private val KEY_NOTIFY_WATCHLIST = booleanPreferencesKey("notify_watchlist")

        // how often the background
        // watchlist check runs. `intPreferencesKey`, default 15 = this release's
        // only previous interval, so upgrading changes nothing.
        private val KEY_WATCHLIST_INTERVAL_MINUTES =
            intPreferencesKey("watchlist_check_interval_minutes")

        /**
         * The ONLY accepted `watchlist_check_interval_minutes` values
         * (PHASE14_PLAN §12.1). Every entry is >= WorkManager's 15-minute
         * `MIN_PERIODIC_INTERVAL_MILLIS`, satisfying SPEC §19 "Respect Android's
         * minimum periodic-work constraints". Declared here, next to the setter
         * that validates against it, so the offered set and the accepted set
         * cannot drift.
         */
        val WATCHLIST_INTERVAL_OPTIONS: List<Long> = listOf(15L, 30L, 60L, 240L, 720L)

        /**
         * The shipped default — also the interval `WatchlistCheckScheduler` is
         * constructed with, so an absent key and an explicit reset both land on
         * the interval production already schedules.
         */
        const val DEFAULT_WATCHLIST_INTERVAL_MINUTES = 15L

        // theme keys on the EXISTING DataStore.
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")

        // notification-permission flags on the
        // EXISTING DataStore — the asked-once latch and the last observed grant state.
        val KEY_NOTIFICATION_PERMISSION_ASKED_ONCE =
            booleanPreferencesKey("notification_permission_asked_once")
        val KEY_NOTIFICATION_PERMISSION_GRANTED =
            booleanPreferencesKey("notification_permission_granted")

        // the picked SAF output tree on the
        // EXISTING DataStore — `output_tree_uri` is the Android-native counterpart
        // of Python's retired `output_dir` path key; `output_tree_rename_ok` records
        // whether the tree's provider supported rename on first export (persisted
        // false → the export-destination selector stays on the MediaStore default).
        private val KEY_OUTPUT_TREE_URI = stringPreferencesKey("output_tree_uri")
        private val KEY_OUTPUT_TREE_RENAME_OK = booleanPreferencesKey("output_tree_rename_ok")
    }

    /**
     * Read a boolean preference from the EXISTING DataStore (§6: no second store).
     * Used by the §20 notification-permission architecture for its two latch flags.
     */
    fun getBoolean(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        context.dataStore.data
            .map { preferences -> preferences[key] ?: default }
            // DATA-009: DataStore.data re-emits on ANY preference write; this derived
            // flow only cares about `key`, so suppress unrelated re-emissions.
            .distinctUntilChanged()

    /** Write a boolean preference to the EXISTING DataStore. */
    suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { preferences -> preferences[key] = value }
    }

    /**
     * Flow of theme settings (§6).
     *
     * Absent keys resolve to code defaults: [ThemeMode.DARK] and dynamic color OFF —
     * a fresh install is dark with zero reads/writes required, and migration from
     * pre-existing installs is a no-op. No second persistence mechanism (§6/§8).
     */
    val themeSettings: Flow<ThemeSettings> = context.dataStore.data.map { preferences ->
        ThemeSettings.from(
            themeModeRaw = preferences[KEY_THEME_MODE],
            dynamicColorRaw = preferences[KEY_DYNAMIC_COLOR],
        )
    }.distinctUntilChanged()

    /**
     * Persist the theme mode (§6). Stores [ThemeMode.storageKey] (`system | dark | light`).
     */
    suspend fun updateThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[KEY_THEME_MODE] = themeMode.storageKey
        }
    }

    /**
     * Persist the dynamic-color toggle (§7). Default when absent is OFF.
     * When enabled the system scheme replaces the curated §5 palette (documented in
     * the Settings label so the user knows the outer-space palette is suspended).
     */
    suspend fun updateDynamicColor(dynamicColor: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DYNAMIC_COLOR] = dynamicColor
        }
    }

    /**
     * Flow of current AppContext.
     * Emits updated configuration whenever preferences change.
     */
    val appContextFlow: Flow<AppContext> = context.dataStore.data.map { preferences ->
        mapPreferencesToAppContext(preferences)
    }.distinctUntilChanged()

    /**
     * Suspend read of the current configuration snapshot.
     *
     * The only read API on this class (reviewer L2: the former blocking
     * `getCurrentAppContext()` — `runBlocking { flow.first() }` — had no caller
     * and invited main-thread DataStore reads, so it was removed rather than
     * left as a trap). Consumers that must read once per operation (worker run
     * start, resolution start) use this so they see fresh persisted values.
     */
    suspend fun currentAppContext(): AppContext = appContextFlow.first()

    /**
     * Whether the in-app diagnostics Console entry is shown in Settings.
     * Default OFF — the Console is opt-in diagnostics, not a required surface.
     */
    val showConsole: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SHOW_CONSOLE] ?: false
    }.distinctUntilChanged()

    /** Persist the `Show Console` toggle. */
    suspend fun updateShowConsole(showConsole: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SHOW_CONSOLE] = showConsole
        }
    }

    /**
     * `Wi-Fi-only downloads`, default OFF.
     *
     * Consumed ONLY at enqueue time: each of the three WorkRequest build sites
     * (`DownloadRepository.enqueueWorker`, the deferred-episode trigger in
     * `DownloadWorker`, and `DownloadStateReconciler`'s re-enqueue) reads the
     * CURRENT value fresh (105.4). Already-queued work keeps the constraints it
     * was created with and is never rewritten when the toggle flips (105.2).
     */
    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_WIFI_ONLY] ?: false
    }.distinctUntilChanged()

    /** Persist the `Wi-Fi-only downloads` toggle. */
    suspend fun updateWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_WIFI_ONLY] = wifiOnly
        }
    }

    /**
     * `Auto-resume downloads`,
     * default OFF.
     *
     * Read FRESH at the moment a transport failure is decided inside
     * `DownloadWorker` (DECISIONS 105.4) — never cached at run start — so
     * flipping the switch shapes the very next failure instead of the next app
     * restart. Absent key resolves to `false`, so a fresh install and an upgrade
     * from a pre-Phase-14 install behave exactly like the app does today: a
     * terminal failure with no retry loop.
     */
    val autoResume: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_RESUME] ?: false
    }.distinctUntilChanged()

    /** Persist the `Auto-resume downloads` toggle. */
    suspend fun updateAutoResume(autoResume: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_RESUME] = autoResume
        }
    }

    /**
     * `Completion notifications`, default ON.
     *
     * Consumed at exactly ONE site: `DownloadWorker`'s completion post, which
     * reads this flow FRESH at the moment of posting — never cached — so a
     * Settings change takes effect on the next completed episode instead of at
     * the next app start.
     *
     * The `download_progress` foreground-service notification is deliberately
     * NOT gated by this or any preference: it is the platform requirement of
     * running work, not a message choice (§R7).
     *
     * Absent key → `true` ([resetToDefaults] clears the store, so "reset" and
     * "never set" resolve to the same ON).
     */
    val notifyCompletion: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_NOTIFY_COMPLETION] ?: true
    }.distinctUntilChanged()

    /**
     * `Failure notifications`, default ON.
     *
     * Consumed at exactly ONE site: `DownloadWorker`'s terminal-failure post,
     * read fresh at posting time under the same contract as [notifyCompletion].
     */
    val notifyFailure: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_NOTIFY_FAILURE] ?: true
    }.distinctUntilChanged()

    /** Persist the `Completion notifications` toggle. */
    suspend fun updateNotifyCompletion(notifyCompletion: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NOTIFY_COMPLETION] = notifyCompletion
        }
    }

    /** Persist the `Failure notifications` toggle. */
    suspend fun updateNotifyFailure(notifyFailure: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NOTIFY_FAILURE] = notifyFailure
        }
    }

    /**
     * `New watchlist episodes`, default ON.
     *
     * Consumed at exactly ONE site: `WatchlistCheckWorker`, which reads this
     * flow FRESH after `checkAll()` returns, so a Settings change takes effect
     * on the NEXT background check rather than at the next app start. The
     * foreground `WatchlistViewModel.checkNow()` never posts at all (§12.2),
     * so this flag cannot turn a manual check into a notification.
     *
     * Absent key → `true` ([resetToDefaults] clears the store, so "reset" and
     * "never set" resolve to the same ON).
     */
    val notifyWatchlist: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_NOTIFY_WATCHLIST] ?: true
    }.distinctUntilChanged()

    /**
     * `watchlist_check_interval_minutes`,
     * default [DEFAULT_WATCHLIST_INTERVAL_MINUTES].
     *
     * The interval the `WatchlistCheckScheduler` collector re-registers with;
     * it is a Int because [KEY_WATCHLIST_INTERVAL_MINUTES] is an
     * `intPreferencesKey`, and surfaces as Long only where WorkManager's
     * periodic-work API wants one.
     *
     * Absent key → [DEFAULT_WATCHLIST_INTERVAL_MINUTES] (an upgrading install
     * keeps the only interval production ever scheduled).
     */
    val watchlistCheckIntervalMinutes: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[KEY_WATCHLIST_INTERVAL_MINUTES] ?: DEFAULT_WATCHLIST_INTERVAL_MINUTES).toLong()
    }.distinctUntilChanged()

    /** Persist the `New watchlist episodes` toggle. */
    suspend fun updateNotifyWatchlist(notifyWatchlist: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NOTIFY_WATCHLIST] = notifyWatchlist
        }
    }

    /**
     * Persist the watchlist check frequency.
     *
     * Membership is validated against [WATCHLIST_INTERVAL_OPTIONS]; a
     * non-member value deterministically writes
     * [DEFAULT_WATCHLIST_INTERVAL_MINUTES] instead of being clamped to a
     * neighbour, so an unexpected caller can never register an interval
     * WorkManager would reject (its minimum is 15 minutes). The UI only ever
     * offers members.
     */
    suspend fun updateWatchlistCheckIntervalMinutes(minutes: Long) {
        val validated =
            if (minutes in WATCHLIST_INTERVAL_OPTIONS) minutes else DEFAULT_WATCHLIST_INTERVAL_MINUTES
        context.dataStore.edit { preferences ->
            preferences[KEY_WATCHLIST_INTERVAL_MINUTES] = validated.toInt()
        }
    }

    /**
     * Update a single configuration value.
     * Validates and clamps values according to Python reference.
     */
    suspend fun update(validator: (Preferences) -> Preferences) {
        context.dataStore.edit { preferences ->
            validator(preferences)
        }
    }

    /**
     * Update quality setting.
     * Valid values: 360, 720, 1080
     */
    suspend fun updateQuality(quality: Int) {
        val validated = when (quality) {
            360, 720, 1080 -> quality
            else -> 1080 // default
        }
        context.dataStore.edit { preferences ->
            preferences[KEY_QUALITY] = validated
        }
    }

    // No `updateAudioLang()`: audio is not a global preference (SPEC §22/§23).
    // A legacy `audio_lang` byte in the store is ignored on every read.

    /**
     * Update max parallel downloads.
     * Clamped to [1, 6]
     */
    suspend fun updateMaxParallel(maxParallel: Int) {
        val clamped = maxParallel.coerceIn(AppContext.MIN_PARALLEL, AppContext.MAX_PARALLEL)
        context.dataStore.edit { preferences ->
            preferences[KEY_MAX_PARALLEL] = clamped
        }
    }

    /**
     * Update HLS workers.
     * Clamped to [8, 32]
     */
    suspend fun updateHlsWorkers(hlsWorkers: Int) {
        val clamped = hlsWorkers.coerceIn(AppContext.MIN_HLS_WORKERS, AppContext.MAX_HLS_WORKERS)
        context.dataStore.edit { preferences ->
            preferences[KEY_HLS_WORKERS] = clamped
        }
    }

    /**
     * Update output directory.
     */
    suspend fun updateOutputDir(outputDir: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_OUTPUT_DIR] = outputDir
        }
    }

    /**
     * Update keep temporary files setting.
     */
    suspend fun updateKeepTemp(keepTemp: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_KEEP_TEMP] = keepTemp
        }
    }

    /**
     * Flow of the persisted SAF output tree URI, or
     * null when no destination is configured (→ MediaStore default, 92.2).
     */
    val outputTreeUri: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_OUTPUT_TREE_URI]
    }.distinctUntilChanged()

    /**
     * Whether the configured tree's provider has
     * proven rename support. Default true (a fresh install, or a tree picked
     * before any export, has not failed yet); persisted false after a
     * rename-less provider falls back to the MediaStore default.
     */
    val outputTreeRenameOk: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_OUTPUT_TREE_RENAME_OK] ?: true
    }.distinctUntilChanged()

    /**
     * Persist the picked SAF output tree.
     * Selecting a (new) tree always resets `output_tree_rename_ok` to true —
     * a different provider gets a fresh rename-capability attempt.
     */
    suspend fun updateOutputTree(treeUri: String?) {
        context.dataStore.edit { preferences ->
            if (treeUri == null) {
                preferences.remove(KEY_OUTPUT_TREE_URI)
                preferences.remove(KEY_OUTPUT_TREE_RENAME_OK)
            } else {
                preferences[KEY_OUTPUT_TREE_URI] = treeUri
                preferences[KEY_OUTPUT_TREE_RENAME_OK] = true
            }
        }
    }

    /**
     * Persist the rename-capability probe result
     * for the current tree (false once a provider refused `renameDocument`).
     */
    suspend fun updateOutputTreeRenameOk(renameOk: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_OUTPUT_TREE_RENAME_OK] = renameOk
        }
    }

    /**
     * Update resolve ahead setting.
     * Clamped to >= 0
     */
    suspend fun updateResolveAhead(resolveAhead: Int) {
        val clamped = resolveAhead.coerceAtLeast(0)
        context.dataStore.edit { preferences ->
            preferences[KEY_RESOLVE_AHEAD] = clamped
        }
    }

    /**
     * Update cache TTL in minutes.
     * Clamped to >= 0
     */
    suspend fun updateCacheTtl(cacheTtl: Int) {
        val clamped = cacheTtl.coerceAtLeast(0)
        context.dataStore.edit { preferences ->
            preferences[KEY_CACHE_TTL] = clamped
        }
    }

    /**
     * Update cookie string.
     */
    suspend fun updateCookieString(cookieString: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_COOKIE_STRING] = cookieString
        }
    }

    /**
     * Update auto retry setting.
     * Clamped to [0, 2]
     */
    suspend fun updateAutoRetry(autoRetry: Int) {
        val clamped = autoRetry.coerceIn(AppContext.MIN_AUTO_RETRY, AppContext.MAX_AUTO_RETRY)
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_RETRY] = clamped
        }
    }

    /**
     * Reset all settings to defaults.
     * (Theme keys return to their §6/§7 code defaults: dark + dynamic color off.)
     */
    suspend fun resetToDefaults() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * Map Preferences to AppContext.
     * Uses defaults from Python reference when not set.
     */
    private fun mapPreferencesToAppContext(preferences: Preferences): AppContext {
        val defaults = AppContext.defaults()
        return AppContext(
            outputDir = preferences[KEY_OUTPUT_DIR] ?: defaults.outputDir,
            cacheDir = defaults.cacheDir, // Not user-configurable on Android
            quality = preferences[KEY_QUALITY] ?: defaults.quality,
            maxParallel = preferences[KEY_MAX_PARALLEL] ?: defaults.maxParallel,
            hlsWorkers = preferences[KEY_HLS_WORKERS] ?: defaults.hlsWorkers,
            keepTemp = preferences[KEY_KEEP_TEMP] ?: defaults.keepTemp,
            listOnly = defaults.listOnly, // Not configurable via UI
            flaresolverrUrl = defaults.flaresolverrUrl, // Android uses WebView
            resolveAhead = preferences[KEY_RESOLVE_AHEAD] ?: defaults.resolveAhead,
            cacheTtl = preferences[KEY_CACHE_TTL] ?: defaults.cacheTtl,
            cookieString = preferences[KEY_COOKIE_STRING] ?: defaults.cookieString,
            autoRetry = preferences[KEY_AUTO_RETRY] ?: defaults.autoRetry
        )
    }
}