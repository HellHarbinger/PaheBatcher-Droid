package com.pahebatcher.app.data.download

import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.domain.model.AppContext

/**
 * How a [DownloadWorker] run obtains its configuration.
 *
 * The worker reads the configuration ONCE at the start of each `doWork()` — a run
 * keeps the configuration it started with, exactly like the Python reference, which
 * builds one `AppContext` at process start. A settings change therefore applies to
 * the NEXT run, never mid-run.
 *
 * Production implementation: [AppConfigDownloadConfigSource] (persistent DataStore
 * settings). Tests inject fakes.
 */
fun interface DownloadConfigSource {
    /** @return the configuration snapshot for one worker run. Never null. */
    suspend fun downloadConfig(): DownloadConfig
}

/**
 * Reads the persisted user settings ([AppConfig]) and maps them onto [DownloadConfig].
 *
 * Only settings that are actually consumed by the download engine are mapped; see
 * [DownloadConfig.from] for the exact mapping. The keys it does NOT map are not
 * fake-wired either — each has its own real consumer at resolution/scan time
 * (`resolve_ahead`, `audio_lang`, `quality`, `cache_ttl`)); the remaining two are
 * superseded by Android-native mechanisms (`cookie_string` -> WebView session, `output_dir` -> SAF).
 */
class AppConfigDownloadConfigSource(
    private val appConfig: AppConfig,
) : DownloadConfigSource {

    override suspend fun downloadConfig(): DownloadConfig =
        DownloadConfig.from(appConfig.currentAppContext())
}
