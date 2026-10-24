package com.pahebatcher.app.domain.stream

import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver retry budget the batch and the watchlist check share.
 *
 * Reference rules (`downloader.py:233-275`):
 *
 * * `max_attempts = auto_retry + 1` attempts in total;
 * * `await asyncio.sleep(1.5 * 2**attempt)` between attempts;
 * * the permanent condition `\"No Kwik link\" in msg or \"No anime UUID\" in msg`
 *   is never retried (downloader.py:263; both markers exist on Android);
 * * whatever the last failure was, it is re-thrown to the caller, which records
 *   it against that ONE episode.
 */
class StreamResolutionRetryTest {

    private val episode = EpisodeInfo(
        number = 1f,
        session = "ep-1",
        title = "Beginnings",
        fansub = "SubsPlease",
        audio = "jpn",
        playUrl = "https://animepahe.com/play/anime-sess/ep-1",
    )

    private fun streamOf(url: String) = StreamInfo(
        url = url,
        userAgent = "Mozilla/5.0 (test)",
        referer = "https://animepahe.com/play/anime-sess/ep-1",
    )

    /** Resolver that fails the first [failures] calls, then succeeds. */
    private class ScriptedResolver(
        private val failures: Int,
        private val message: String = "timed out",
    ) : StreamResolver {
        var calls = 0
        var lastQuality: Int? = null
        var lastAudio: String? = null

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            calls++
            lastQuality = quality
            lastAudio = audio
            if (calls <= failures) throw IOExceptionLike(message)
            return StreamInfo(
                url = "https://cdn.example.invalid/ok.m3u8",
                userAgent = "Mozilla/5.0 (test)",
                referer = playUrl,
            )
        }
    }

    private class IOExceptionLike(message: String) : Exception(message)

    @Test
    fun `a success on the first attempt costs exactly one call`() = runTest {
        val resolver = ScriptedResolver(failures = 0)

        val stream = StreamResolutionRetry.resolveWithRetry(
            resolver = resolver,
            episode = episode,
            quality = 1080,
            maxAttempts = 3,
        )

        assertEquals(1, resolver.calls)
        assertEquals("https://cdn.example.invalid/ok.m3u8", stream.url)
        assertEquals("the configured quality is forwarded", 1080, resolver.lastQuality)
        assertEquals("the episode's audio is forwarded", "jpn", resolver.lastAudio)
    }

    @Test
    fun `transient failures are retried up to the attempt budget`() = runTest {
        // auto_retry = 2 → max_attempts = 3: fail, fail, succeed.
        val resolver = ScriptedResolver(failures = 2)

        val stream = StreamResolutionRetry.resolveWithRetry(
            resolver = resolver,
            episode = episode,
            quality = 720,
            maxAttempts = 3,
        )

        assertEquals("3 attempts consumed", 3, resolver.calls)
        assertEquals("https://cdn.example.invalid/ok.m3u8", stream.url)
    }

    @Test
    fun `the last failure is re-thrown after the budget is spent`() = runTest {
        val resolver = ScriptedResolver(failures = 99, message = "timed out")

        var thrown: Throwable? = null
        try {
            StreamResolutionRetry.resolveWithRetry(
                resolver = resolver,
                episode = episode,
                quality = 1080,
                maxAttempts = 2,
            )
        } catch (e: Exception) {
            thrown = e
        }

        assertEquals("the resolver's own message survives", "timed out", thrown?.message)
        assertEquals("exactly auto_retry + 1 attempts", 2, resolver.calls)
    }

    @Test
    fun `the permanent No Kwik link condition is never retried`() = runTest {
        val resolver = ScriptedResolver(
            failures = 99,
            message = "No Kwik link found on episode page",
        )

        var thrown: Throwable? = null
        try {
            StreamResolutionRetry.resolveWithRetry(
                resolver = resolver,
                episode = episode,
                quality = 1080,
                maxAttempts = 3,
            )
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue(thrown?.message.orEmpty().contains(StreamResolutionRetry.PERMANENT_ERROR))
        assertEquals("one attempt only — Python breaks on this message", 1, resolver.calls)
    }

    @Test
    fun `the permanent no anime UUID condition is never retried either`() = runTest {
        // The second marker of downloader.py:263 — Android raises it from
        // AnimePaheParser when the play URL carries no anime UUID, and retrying a
        // malformed URL cannot change the answer.
        val resolver = ScriptedResolver(
            failures = 99,
            message = "No anime UUID in URL. Expected: https://animepahe.pw/anime/<uuid>",
        )

        var thrown: Throwable? = null
        try {
            StreamResolutionRetry.resolveWithRetry(
                resolver = resolver,
                episode = episode,
                quality = 1080,
                maxAttempts = 3,
            )
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue(thrown?.message.orEmpty().contains(StreamResolutionRetry.PERMANENT_ERROR_NO_UUID))
        assertEquals("one attempt only — Python breaks on this message too", 1, resolver.calls)
    }
}
