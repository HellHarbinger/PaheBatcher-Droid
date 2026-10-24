package com.pahebatcher.app.data.download

import com.pahebatcher.app.domain.model.AppContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping persisted user settings onto the download engine's run
 * configuration ([DownloadConfig.from]).
 *
 * A setting counts as wired only because production code consumes the mapped
 * field, so these tests pin BOTH directions:
 *
 * - every mapped field carries the persisted value,
 * - the fields that are deliberately NOT wired (documented deferrals) are not
 *   silently mapped onto something else.
 */
class DownloadConfigFromTest {

    private fun context(
        maxParallel: Int = 4,
        hlsWorkers: Int = 16,
        keepTemp: Boolean = true,
        autoRetry: Int = 1,
        quality: Int = 720,
        cacheTtl: Int = 15,
        resolveAhead: Int = 7,
        cookieString: String = "secret-cookie",
        outputDir: String = "/storage/emulated/0/Movies",
    ) = AppContext.defaults().copy(
        maxParallel = maxParallel,
        hlsWorkers = hlsWorkers,
        keepTemp = keepTemp,
        autoRetry = autoRetry,
        quality = quality,
        cacheTtl = cacheTtl,
        resolveAhead = resolveAhead,
        cookieString = cookieString,
        outputDir = outputDir,
    )

    @Test
    fun `maps max_parallel hls_workers and keep_temp to the persisted values`() {
        val config = DownloadConfig.from(context())
        assertEquals(4, config.maxParallel)
        assertEquals(16, config.hlsWorkers)
        assertTrue(config.keepTemp)
    }

    @Test
    fun `auto_retry maps as the RAW python value not attempt count`() {
        // EpisodeDownloader derives attempts (`autoRetry + 1`) exactly like
        // Python's `auto_retry + 1`, so +1 must NOT be applied here.
        assertEquals(1, DownloadConfig.from(context(autoRetry = 1)).episodeRetryCount)
        assertEquals(0, DownloadConfig.from(context(autoRetry = 0)).episodeRetryCount)
        assertEquals(2, DownloadConfig.from(context(autoRetry = 2)).episodeRetryCount)
    }

    @Test
    fun `defaults match the python reference`() {
        val config = DownloadConfig.from(AppContext.defaults())
        assertEquals(2, config.maxParallel)
        assertEquals(24, config.hlsWorkers)
        assertFalse(config.keepTemp)
        assertEquals(2, config.episodeRetryCount)
    }

    @Test
    fun `deferrals are not silently mapped onto engine fields`() {
        // quality/cache_ttl are consumed at resolution/scan time, never here:
        // the run config equals EXACTLY the four mapped fields, nothing else.
        val config = DownloadConfig.from(context(quality = 360, cacheTtl = 5))
        assertEquals(
            DownloadConfig(
                maxParallel = 4,
                hlsWorkers = 16,
                keepTemp = true,
                episodeRetryCount = 1,
            ),
            config,
        )
        // resolve_ahead, cookie_string and output_dir have no engine consumer yet
        // (documented deferrals) — changing them must not change the run config.
        assertEquals(
            DownloadConfig.from(context(resolveAhead = 0, cookieString = "", outputDir = "./x")),
            DownloadConfig.from(context(resolveAhead = 999, cookieString = "other", outputDir = "/y")),
        )
    }

    @Test
    fun `http retry and progress throttle constants stay fixed`() {
        val config = DownloadConfig.from(context())
        assertEquals(5, config.httpRetryAttempts)
        assertEquals(500L, config.httpRetryBaseDelayMs)
        assertEquals(1500L, config.episodeRetryBaseDelayMs)
        assertEquals(5000L, config.progressThrottleMs)
    }
}
