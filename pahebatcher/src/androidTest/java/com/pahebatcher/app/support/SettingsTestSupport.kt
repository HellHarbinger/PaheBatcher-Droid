package com.pahebatcher.app.support

import android.content.Context
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.export.OutputTreeRepository
import kotlinx.coroutines.runBlocking

/**
 * Phase 12A test precondition helper: the Settings screen now persists engine
 * settings (quality, max_parallel, hls_workers, cache_ttl, keep_temp,
 * auto_retry, show_console), so any suite whose assertions depend on the
 * DEFAULT values resets them first — and restores them afterwards — instead of
 * inheriting whatever an earlier test left behind.
 *
 * Every value here is the Python reference default (`config_manager.py`):
 * quality 1080, max_parallel 2, hls_workers 24, cache_ttl 60,
 * keep_temp false, resolve_ahead 999, auto_retry 2; `show_console` defaults to
 * OFF (opt-in diagnostics). There is no audio_lang reset line: that key stopped
 * existing with the audio-variant slice (SPEC §22/§23) — audio is chosen per
 * episode, so nothing here could or should write it.
 */
object SettingsTestSupport {

    fun resetToDefaults(context: Context) {
        val appConfig = AppConfig(context.applicationContext)
        runBlocking {
            appConfig.updateQuality(1080)
            // (No `updateAudioLang`: audio is not a global preference —
            // SPEC §22/§23 — so this reset helper has no audio line to write.)
            appConfig.updateMaxParallel(2)
            appConfig.updateHlsWorkers(24)
            appConfig.updateCacheTtl(60)
            appConfig.updateKeepTemp(false)
            appConfig.updateResolveAhead(999)
            appConfig.updateAutoRetry(2)
            appConfig.updateShowConsole(false)
            // the Wi-Fi-only toggle — default OFF,
            // so a suite that enqueues WorkRequests starts unconstrained again.
            appConfig.updateWifiOnly(false)
            // `auto_resume` —
            // default OFF, so a suite asserting on the shipped failure
            // semantics starts there rather than inheriting an earlier suite's
            // opt-in.
            appConfig.updateAutoResume(false)
            // the three notification toggles
            // (default ON) and the background-check frequency (default 15m).
            // This helper rewrites every other key explicitly, so it must not
            // leave these carrying an earlier suite's opt-out or its chosen
            // interval — otherwise a suite asserting on the shipped defaults
            // would inherit whatever the previous test left behind.
            appConfig.updateNotifyCompletion(true)
            appConfig.updateNotifyFailure(true)
            appConfig.updateNotifyWatchlist(true)
            appConfig.updateWatchlistCheckIntervalMinutes(AppConfig.DEFAULT_WATCHLIST_INTERVAL_MINUTES)
            // the SAF export destination — default
            // UNSET (MediaStore `Movies/PaheBatcher/`), which also releases any
            // persistable grant an earlier suite took, so the `Output location`
            // row always renders its deterministic NotConfigured text.
            OutputTreeRepository(
                context.applicationContext.contentResolver,
                appConfig,
            ).clearOutputTree()
        }
    }
}
