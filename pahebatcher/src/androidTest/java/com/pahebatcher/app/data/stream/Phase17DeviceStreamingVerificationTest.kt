package com.pahebatcher.app.data.stream

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.support.awaitCondition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/**
 * Phase 17 §22/§23 — the ONE physical-device streaming verification.
 *
 * Everything below is the production graph and the real internet: the app's own
 * [PaheBatcherApplication.appContainer] supplies the real WebView/Cloudflare
 * session, the real `StreamResolverImpl`, the real `HlsPlaybackSourceFactory`
 * (OkHttp + `HlsMediaSource`) and the real CDN. Nothing is stubbed, so a pass
 * is evidence that a genuine AnimePahe episode actually streams through the
 * Android stack rather than that a fixture plays.
 *
 * Evidence it captures (§23):
 *
 *  1. discovery, scan and the episode's own `playUrl` come from the network;
 *  2. resolution returns an `.m3u8` playlist URL plus the UA/Referer it must be
 *     fetched with — logged as `hostPath` only (the expiring query string is
 *     deliberately never printed, AGENTS §10);
 *  3. `duration > 0` — Media3 parsed the REAL media playlist end to end;
 *  4. the playhead advances between two samples — real segments were fetched,
 *     decrypted and rendered, not merely buffered;
 *  5. no `PlaybackException`.
 *
 * [PlaceholderSurface] supplies a real GL-backed consumer so Media3's video
 * renderer has somewhere to put frames; nothing is ever displayed and nothing
 * is faked — it is Media3's own "decode without a window" surface.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class Phase17DeviceStreamingVerificationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private var player: ExoPlayer? = null
    private var placeholder: PlaceholderSurface? = null

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            player?.release()
            player = null
            placeholder?.release()
            placeholder = null
        }
    }

    @Test
    fun aRealAnimePaheEpisodeStreamedByTheProductionStackPlaysOnThisDevice() {
        val container = (context.applicationContext as PaheBatcherApplication).appContainer

        // ---- §22.1 real discovery ------------------------------------------------
        val results = runBlocking {
            container.animeRepository.search(AnimeRepository.currentHost, SEARCH_QUERY)
        }
        assertTrue("search must return at least one title (got ${results.size})", results.isNotEmpty())
        val animeSession = requireNotNull(results.first()["session"] as? String) {
            "search result must carry an anime session: ${results.first().keys}"
        }
        assertTrue(animeSession.isNotBlank())
        Log.i(TAG, "discover: host=${AnimeRepository.currentHost} session=$animeSession")

        // ---- §22.2 real scan → the episode's own play page ----------------------
        val anime = runBlocking {
            container.episodeRepository.scan(AnimeRepository.currentHost, animeSession, 60)
        }
        assertTrue("scan must return episodes (got ${anime.episodes.size})", anime.episodes.isNotEmpty())
        val episode = anime.episodes.first()
        assertTrue(
            "the episode must expose a play page URL (got '${episode.playUrl}')",
            episode.playUrl.startsWith("https://"),
        )
        Log.i(TAG, "episode: number=${episode.number} audio=${episode.audio} epStr=${episode.epStr}")

        // ---- §22.3 JUST-IN-TIME resolution with the episode's OWN audio ---------
        val quality = runBlocking { container.appConfig.currentAppContext().quality }
        val info = runBlocking {
            container.streamResolver.resolve(episode.playUrl, quality = quality, audio = episode.audio)
        }
        assertTrue(
            "resolution must return a playlist (got '${info.url}')",
            info.url.contains(".m3u8"),
        )
        assertTrue("the resolver must supply a User-Agent", info.userAgent.isNotBlank())
        assertTrue("the resolver must supply the KWIK Referer", info.referer.isNotBlank())
        // The expiring authorisation lives in the query string, so only the
        // host + path may ever be logged (AGENTS §10).
        Log.i(TAG, "resolved: quality=$quality audio=${episode.audio} hostPath=${hostPath(info.url)}")
        Log.i(TAG, "resolved: userAgentPresent=${info.userAgent.isNotBlank()} referer=${hostPath(info.referer)}")

        // ---- §23 playback through the production HLS source --------------------
        val source = container.hlsPlaybackSourceFactory.create(
            info = info,
            mediaId = EpisodeId.build(animeSession, episode),
            title = episode.title,
        )

        instrumentation.runOnMainSync {
            val surface = PlaceholderSurface.newInstance(context, false)
            placeholder = surface
            val exo = ExoPlayer.Builder(context).build()
            player = exo
            exo.setVideoSurface(surface)
            exo.setMediaSource(source, 0L)
            exo.prepare()
            exo.playWhenReady = true
        }

        var durationMs = Long.MIN_VALUE
        var state = Player.STATE_IDLE
        var error: String? = null

        val ready = awaitCondition(90_000) {
            instrumentation.runOnMainSync {
                val exo = player ?: return@runOnMainSync
                state = exo.playbackState
                durationMs = exo.duration
                error = exo.playerError?.message
            }
            state == Player.STATE_READY && durationMs > 0 && error == null
        }
        Log.i(TAG, "playlist: state=$state durationMs=$durationMs error=$error")
        assertTrue(
            "Media3 must reach READY with a duration parsed from the real media " +
                "playlist (state=$state durationMs=$durationMs error=$error)",
            ready,
        )
        assertTrue("duration must come from the playlist (got $durationMs)", durationMs > 0)

        var first = -1L
        var second = -1L
        instrumentation.runOnMainSync { first = player?.currentPosition ?: -1L }
        Thread.sleep(3_000)
        instrumentation.runOnMainSync {
            second = player?.currentPosition ?: -1L
            error = player?.playerError?.message
        }
        Log.i(TAG, "playhead: first=$first second=$second error=$error")

        assertEquals("playback must not fail once playing (§23)", null, error)
        assertTrue(
            "the playhead must advance over 3s — real segments were fetched and " +
                "rendered (first=$first second=$second)",
            second > first,
        )
        Log.i(TAG, "verified: played ${second}ms of a ${durationMs}ms stream")
    }

    /** `host + path` only — never the expiring query string (AGENTS §10). */
    private fun hostPath(raw: String): String = try {
        val uri = URI(raw)
        "${uri.host.orEmpty()}${uri.path.orEmpty()}"
    } catch (error: Exception) {
        "[unparseable-url]"
    }

    private companion object {
        const val TAG = "Phase17Verify"

        /** Previously device-verified on `REDACTED-SERIAL` (PARITY §5.1 evidence). */
        const val SEARCH_QUERY = "spy x family"
    }
}
