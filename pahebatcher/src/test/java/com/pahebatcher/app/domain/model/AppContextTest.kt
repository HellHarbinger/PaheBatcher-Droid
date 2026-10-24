package com.pahebatcher.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppContextTest {

    @Test
    fun `defaults returns correct default values`() {
        val defaults = AppContext.defaults()
        assertEquals("./downloads", defaults.outputDir)
        assertEquals("pahe_cache", defaults.cacheDir)
        assertEquals(1080, defaults.quality)
        assertEquals(2, defaults.maxParallel)
        assertEquals(24, defaults.hlsWorkers)
        assertEquals(false, defaults.keepTemp)
        assertEquals(false, defaults.listOnly)
        assertEquals("http://localhost:8191/v1", defaults.flaresolverrUrl)
        assertEquals(999, defaults.resolveAhead)
        assertEquals(60, defaults.cacheTtl)
        assertEquals("", defaults.cookieString)
        assertEquals(2, defaults.autoRetry)
    }

    @Test
    fun `valid qualities are 360, 720, 1080`() {
        assertEquals(setOf(360, 720, 1080), AppContext.VALID_QUALITIES)
    }

    @Test
    fun `AppContext carries no global audio preference at all`() {
        // Inverted, not deleted (SPEC §22/§23): audio used to live on AppContext
        // as `audioLang` with `VALID_AUDIO_LANGUAGES` restricting it to a closed
        // jpn/eng pair. Audio is per-episode now, so the field and the constant
        // must be GONE from the model — asserted by reflection so the test fails
        // the moment either is reintroduced.
        val names = AppContext::class.java.declaredFields.map { it.name }
        assertFalse("AppContext.audioLang must not exist", names.contains("audioLang"))
        assertFalse(
            "AppContext.VALID_AUDIO_LANGUAGES must not exist",
            names.contains("VALID_AUDIO_LANGUAGES"),
        )
    }

    @Test
    fun `parallel limits are correct`() {
        assertEquals(1, AppContext.MIN_PARALLEL)
        assertEquals(6, AppContext.MAX_PARALLEL)
    }

    @Test
    fun `hls workers limits are correct`() {
        assertEquals(8, AppContext.MIN_HLS_WORKERS)
        assertEquals(32, AppContext.MAX_HLS_WORKERS)
    }

    @Test
    fun `auto retry limits are correct`() {
        assertEquals(0, AppContext.MIN_AUTO_RETRY)
        assertEquals(2, AppContext.MAX_AUTO_RETRY)
    }
}