package com.pahebatcher.app.data.download

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for DownloadConfig.
 *
 */
class DownloadConfigTest {

    @Test
    fun defaultConfig_hasExpectedValues() {
        val config = DownloadConfig()
        // default 2 = Python `max_parallel` (config_manager.py).
        assertEquals(2, config.maxParallel)
        assertEquals(24, config.hlsWorkers)
        // default false = Python `keep_temp`.
        assertFalse(config.keepTemp)
        assertEquals(5, config.httpRetryAttempts)
        assertEquals(500L, config.httpRetryBaseDelayMs)
        assertEquals(2, config.episodeRetryCount)
        assertEquals(1500L, config.episodeRetryBaseDelayMs)
        assertEquals(5000L, config.progressThrottleMs)
    }

    @Test
    fun maxParallel_isPositive() {
        val config = DownloadConfig()
        assertTrue("maxParallel must be positive, was ${config.maxParallel}", config.maxParallel > 0)
    }

    @Test
    fun hlsWorkers_isPositive() {
        val config = DownloadConfig()
        assertTrue("hlsWorkers must be positive, was ${config.hlsWorkers}", config.hlsWorkers > 0)
    }

    @Test
    fun episodeRetryCount_isPositive() {
        val config = DownloadConfig()
        assertTrue("episodeRetryCount must be positive, was ${config.episodeRetryCount}", config.episodeRetryCount > 0)
    }

    @Test
    fun progressThrottleMs_isPositive() {
        val config = DownloadConfig()
        assertTrue("progressThrottleMs must be positive, was ${config.progressThrottleMs}", config.progressThrottleMs > 0)
    }

    @Test
    fun dataClass_equality() {
        val config1 = DownloadConfig()
        val config2 = DownloadConfig()
        assertEquals(config1, config2)
    }

    @Test
    fun dataClass_copy_modifiesField() {
        val original = DownloadConfig()
        val modified = original.copy(maxParallel = 5)
        assertEquals(5, modified.maxParallel)
        assertEquals(original.hlsWorkers, modified.hlsWorkers)
    }
}
