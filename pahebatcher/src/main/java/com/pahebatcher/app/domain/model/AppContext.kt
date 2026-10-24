package com.pahebatcher.app.domain.model

/**
 * Application context holding configuration and runtime state.
 *
 * Ported from Python models.py AppContext dataclass.
 * Contains all configuration options from the Python reference.
 */
data class AppContext(
    /** Output directory for completed downloads */
    val outputDir: String,

    /** Cache directory for temporary segments */
    val cacheDir: String,

    /** Video quality preference: 360, 720, or 1080 */
    val quality: Int,

    // No `audioLang` (SPEC §22/§23): audio is not a global preference — every
    // download takes the audio from the episode variant the user selected.

    /** Number of concurrent episode downloads (1-6) */
    val maxParallel: Int,

    /** Number of HLS segment workers per episode (8-32) */
    val hlsWorkers: Int,

    /** Whether to keep temporary segment files after muxing */
    val keepTemp: Boolean,

    /** Whether to only list episodes without downloading */
    val listOnly: Boolean,

    /** FlareSolverr URL (Android: WebView-based session) */
    val flaresolverrUrl: String,

    /** Episodes ahead resolver stays (default 999) */
    val resolveAhead: Int,

    /** Scan cache TTL in minutes (default 60) */
    val cacheTtl: Int,

    /** Browser cookies for Cloudflare */
    val cookieString: String,

    /** Auto-retry failed episodes (0-2) */
    val autoRetry: Int
) {
    companion object {
        /** Default AppContext matching Python defaults */
        fun defaults(): AppContext {
            return AppContext(
                outputDir = "./downloads",
                cacheDir = "pahe_cache",
                quality = 1080,
                maxParallel = 2,
                hlsWorkers = 24,
                keepTemp = false,
                listOnly = false,
                flaresolverrUrl = "http://localhost:8191/v1",
                resolveAhead = 999,
                cacheTtl = 60,
                cookieString = "",
                autoRetry = 2
            )
        }

        /** Valid quality values */
        val VALID_QUALITIES = setOf(360, 720, 1080)

        // No `VALID_AUDIO_LANGUAGES`: audio is an open-set string chosen per
        // episode — there is no closed list to validate against (SPEC §22).

        /** Parallel download limits */
        const val MIN_PARALLEL = 1
        const val MAX_PARALLEL = 6

        /** HLS worker limits */
        const val MIN_HLS_WORKERS = 8
        const val MAX_HLS_WORKERS = 32

        /** Auto-retry limits */
        const val MIN_AUTO_RETRY = 0
        const val MAX_AUTO_RETRY = 2
    }
}