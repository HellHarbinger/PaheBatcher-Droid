package com.pahebatcher.app.ui.player

import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 17 §13/§20-D — the JUST-IN-TIME resolution inputs, updated for the
 * audio-variant slice (SPEC §22).
 *
 * The stream resolves with three inputs and three inputs only:
 * the episode's own `playUrl`, the configured `quality`, and the episode's own
 * `audio`. The assertion that matters most is the last one: there is NO global
 * preferred-audio preference anymore (`configuredAudioLang()` and the
 * `audio_lang` key were removed with the Settings row), and even before its
 * removal the global value never decided which track a STREAM entry plays —
 * the user picked that episode's audio on the episode row.
 */
class StreamResolveInputsTest {

    private class RecordingResolver(
        private val result: StreamInfo = StreamInfo(
            url = "https://cdn.example/master.m3u8",
            userAgent = "ua",
            referer = "https://kwik.example/page",
        ),
    ) : StreamResolver {
        var calls = 0
        var lastPlayUrl: String? = null
        var lastQuality: Int? = null
        var lastAudio: String? = null

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            calls++
            lastPlayUrl = playUrl
            lastQuality = quality
            lastAudio = audio
            return result
        }
    }

    private fun episode(
        audio: String = EpisodeInfo.AUDIO_JAPANESE,
        playUrl: String = "https://animepahe.example/play/anime-session/ep-session",
    ) = EpisodeInfo(
        number = 1f,
        session = "ep-session",
        title = "A Title",
        fansub = "SubsPlease",
        audio = audio,
        playUrl = playUrl,
    )

    private fun config(quality: Int = 720): AppContext =
        AppContext.defaults().copy(quality = quality)

    @Test
    fun `resolves with the episode play url the configured quality and the episode audio`() =
        runBlocking {
            val resolver = RecordingResolver()
            val target = episode(audio = EpisodeInfo.AUDIO_JAPANESE)

            val info = resolveStreamFor(target, resolver) { config(quality = 720) }

            assertEquals(1, resolver.calls)
            assertEquals(target.playUrl, resolver.lastPlayUrl)
            assertEquals(720, resolver.lastQuality)
            assertEquals(EpisodeInfo.AUDIO_JAPANESE, resolver.lastAudio)
            assertEquals("https://cdn.example/master.m3u8", info.url)
        }

    @Test
    fun `no global audio preference exists and the episode's own audio decides the stream`() =
        runBlocking {
            // Inverted, not deleted: this used to configure a global DUB
            // preference and prove it lost to the episode's SUB audio. The
            // preference itself is now GONE (SPEC §22/§23), which the model
            // proves by reflection, and the episode's audio must still be the
            // audio handed to the resolver.
            val audioFields = AppContext::class.java.declaredFields.map { it.name }
            assertTrue(
                "AppContext must carry no audio preference",
                audioFields.none { it.contains("audio", ignoreCase = true) },
            )

            val resolver = RecordingResolver()

            resolveStreamFor(episode(audio = EpisodeInfo.AUDIO_JAPANESE), resolver) {
                config(quality = 1080)
            }

            assertEquals(
                "the episode's own audio must be the resolver input",
                EpisodeInfo.AUDIO_JAPANESE,
                resolver.lastAudio,
            )
        }

    @Test
    fun `a DUB episode resolves with the DUB audio code`() = runBlocking {
        val resolver = RecordingResolver()

        resolveStreamFor(episode(audio = EpisodeInfo.AUDIO_ENGLISH), resolver) {
            config()
        }

        assertEquals(EpisodeInfo.AUDIO_ENGLISH, resolver.lastAudio)
    }

    @Test
    fun `an open-set audio code passes through to the resolver untouched`() = runBlocking {
        val resolver = RecordingResolver()

        resolveStreamFor(episode(audio = "kor"), resolver) { config() }

        assertEquals("kor", resolver.lastAudio)
    }

    @Test
    fun `quality comes from persisted settings rather than a hard coded default`() = runBlocking {
        val resolver = RecordingResolver()

        resolveStreamFor(episode(), resolver) { config(quality = 360) }

        assertEquals(360, resolver.lastQuality)
    }

    @Test
    fun `a settings read failure falls back to the python default quality`() = runBlocking {
        val resolver = RecordingResolver()

        resolveStreamFor(episode(), resolver) {
            throw IllegalStateException("settings unreadable")
        }

        assertEquals(RESOLUTION_QUALITY, resolver.lastQuality)
        assertEquals(1080, RESOLUTION_QUALITY)
        assertEquals(EpisodeInfo.AUDIO_JAPANESE, resolver.lastAudio)
    }

    @Test
    fun `a settings cancellation is rethrown and never resolved with defaults`() = runBlocking {
        val resolver = RecordingResolver()
        val cancellation = kotlinx.coroutines.CancellationException("leave")

        try {
            resolveStreamFor(episode(), resolver) { throw cancellation }
            fail("cancellation must propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertEquals(cancellation, expected)
        }

        assertTrue("no resolution may happen after a cancelled settings read", resolver.calls == 0)
    }
}
