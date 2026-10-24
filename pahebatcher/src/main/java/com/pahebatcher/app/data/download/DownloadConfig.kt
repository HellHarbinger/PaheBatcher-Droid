package com.pahebatcher.app.data.download

/**
 * Configuration for background downloads.
 *
 */
data class DownloadConfig(
    /**
     * Maximum concurrent downloads (best-effort soft limit, not hard semaphore).
     * default 2 = Python `max_parallel` default (config_manager.py).
     * The settings value now reaches the worker (see AppConfigDownloadConfigSource),
     * so the default is the Python parity default rather than the v10 guess of 3.
     */
    val maxParallel: Int = 2,

    /** Maximum concurrent HLS segment downloads per episode. */
    val hlsWorkers: Int = 24,

    /**
     * Keep temporary segment files after muxing (Python `keep_temp`).
     * read from persistent settings per worker run.
     */
    val keepTemp: Boolean = false,

    /** HTTP retry attempts for M3U8/segment/key fetches. */
    val httpRetryAttempts: Int = 5,

    /** HTTP retry base delay in milliseconds. */
    val httpRetryBaseDelayMs: Long = 500L,

    /**
     * Episode-level retry budget passed to EpisodeDownloader as `autoRetry`.
     * Python `auto_retry` is the RAW setting (0..2); EpisodeDownloader turns it into
     * `autoRetry + 1` attempts (downloader.py: `auto_retry + 1` tries), so this field
     * carries the raw value, not the attempt count. Default 2 = Python default.
     */
    val episodeRetryCount: Int = 2,

    /** Episode retry base delay in milliseconds. */
    val episodeRetryBaseDelayMs: Long = 1500L,

    /** Progress write throttle interval in milliseconds. */
    val progressThrottleMs: Long = 5000L
    // dead notificationChannelId/notificationChannelName
    // fields removed — they were never read and their value ("download_channel")
    // contradicted the real channel ("download_progress") owned by
    // DownloadNotificationManager.
) {
    companion object {
        /**
         * Map a persisted [com.pahebatcher.app.domain.model.AppContext]
         * snapshot onto the worker configuration.
         *
         * Consumed mappings (a setting is only wired when production code reads it):
         * - `max_parallel` -> [maxParallel] (soft episode concurrency cap)
         * - `hls_workers`  -> [hlsWorkers] (segment concurrency per episode)
         * - `auto_retry`   -> [episodeRetryCount] (RAW `auto_retry`; EpisodeDownloader
         *                      derives `autoRetry + 1` attempts — Python parity)
         * - `keep_temp`    -> [keepTemp] (temp segment retention after mux)
         *
         * NOT mapped here (each still has a real consumer elsewhere — a setting is
         * only ever wired where production code reads it):
         * - `quality`, `cache_ttl` -> consumed at resolution/scan time
         *   (AnimeDetailsViewModel), not by the worker
         * - `resolve_ahead` -> consumed by the BATCH staging gate
         *   (`ResolveAheadGate`, Phase 12C) — it bounds how far stream resolution
         *   may run ahead of download capacity, so it is read at resolution time
         *   (AnimeDetailsViewModel / WatchlistChecker), not by the worker
         * - `cookie_string` -> WebView session owns cookies (Decision 26)
         * - `output_dir`    -> Phase 13 ships the Android-native SAF destination
         *   as its own key `output_tree_uri` (DECISIONS 92); this path key stays
         *   internal without a row (LIMITATIONS §5)
         * - `audio_lang`    -> consumed by variant SELECTION at resolution time
         *   (`AnimeDetailsViewModel.configuredAudioLang`, Phase 12C), not by the
         *   worker; per-variant row taps still pass their own language
         */
        fun from(context: com.pahebatcher.app.domain.model.AppContext): DownloadConfig =
            DownloadConfig(
                maxParallel = context.maxParallel,
                hlsWorkers = context.hlsWorkers,
                keepTemp = context.keepTemp,
                // RAW auto_retry (Python parity): EpisodeDownloader computes attempts.
                episodeRetryCount = context.autoRetry,
            )
    }
}
