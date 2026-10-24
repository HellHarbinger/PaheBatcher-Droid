package com.pahebatcher.app.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.playback.PlaybackPositionRepository
import com.pahebatcher.app.data.stream.HlsPlaybackSourceFactory
import com.pahebatcher.app.data.stream.RecordingHlsServer
import com.pahebatcher.app.data.stream.StreamEpisodeReconstructor
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.playback.MediaSessionHost
import com.pahebatcher.app.playback.MediaSessionHostHolder
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.ui.navigation.PlayerMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * SPEC §15 "subtitles where available" — the text-track capability exercised
 * end to end on a physical device: a REAL HLS fixture with a REAL subtitle
 * rendition, the REAL Room → reconstruction → just-in-time resolve ladder, the
 * REAL [HlsPlaybackSourceFactory] transport and the REAL ExoPlayer/Media3
 * track-selection machinery. Only the resolver is scripted (its production
 * implementation needs a live WebView + Cloudflare), exactly as in
 * [StreamPlaybackLifecycleTest].
 *
 * Covered:
 *
 *  1. a master playlist that publishes a subtitle rendition makes
 *     `PlayerUiState.subtitleOptions` non-empty — detection + availability;
 *  2. picking an entry really makes ExoPlayer render that text track;
 *  3. `-1` really turns subtitles off (the text track type is disabled, no
 *     text track is selected);
 *  4. choosing again turns them back on;
 *  5. a sibling episode with NO subtitle rendition publishes no options at all —
 *     the state the control is hidden on — while playback stays healthy;
 *  6. coming back to the subtitled episode restores the viewer's choice WITHOUT
 *     another user action, on the same player and route.
 *
 * The fixture is generated on-device (the proven FFmpegKit recipe; the host
 * has no ffmpeg) and served from a loopback socket — no external network.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class SubtitleFixturePlaybackDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var db: PaheDatabase
    private lateinit var server: RecordingHlsServer
    private lateinit var downloadRepository: DownloadRepository
    private lateinit var positionRepository: PlaybackPositionRepository

    private lateinit var animeSession: String
    private lateinit var episodeId: String
    private lateinit var plainEpisodeId: String
    private lateinit var playUrl: String
    private lateinit var plainPlayUrl: String

    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null

    private val stores = mutableListOf<ViewModelStore>()
    private var priorHost: MediaSessionHost? = null

    // -----------------------------------------------------------------------
    // Test double — the production resolver needs a live WebView + Cloudflare
    // -----------------------------------------------------------------------

    /**
     * Scripted resolver: an episode's own playUrl maps to its fixture URL, so
     * resolution stays deterministic however often an episode is resolved —
     * next → previous resolves episode 1 TWICE, and a call-index script would
     * hand it the wrong playlist on the way back.
     */
    private class FixtureResolver(private val byPlayUrl: Map<String, String>) : StreamResolver {
        val calls = mutableListOf<String>()

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            calls += playUrl
            val playlistUrl = byPlayUrl[playUrl]
                ?: throw com.pahebatcher.app.domain.stream.StreamResolutionException(
                    "no fixture scripted for $playUrl",
                )
            return StreamInfo(
                url = playlistUrl,
                cookies = emptyList(),
                userAgent = "SubtitleFixtureAgent/1.0",
                referer = "https://kwik.example.invalid/page",
                title = "",
                audio = audio,
                fansub = "",
            )
        }
    }

    private class SingleViewModelFactory(private val viewModel: ViewModel) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    @Before
    fun setUp() {
        db = PaheDatabase.getInstance(context)
        server = RecordingHlsServer(resources = fixtureResources())
        priorHost = MediaSessionHostHolder.host

        animeSession = UUID.randomUUID().toString()
        episodeId = "${animeSession}_1_jpn"
        plainEpisodeId = "${animeSession}_2_jpn"
        playUrl = "https://animepahe.example.invalid/play/$animeSession/ep1"
        plainPlayUrl = "https://animepahe.example.invalid/play/$animeSession/ep2"

        runBlocking {
            Phase10TestSupport.insertSession(db, animeSession)
            db.episodeDownloadDao().upsert(episodeRow(episodeId, 1f, playUrl))
            db.episodeDownloadDao().upsert(episodeRow(plainEpisodeId, 2f, plainPlayUrl))
        }

        downloadRepository = DownloadRepository(
            context,
            db.episodeDownloadDao(),
            db.downloadSessionDao(),
        )
        positionRepository = PlaybackPositionRepository(db.playbackPositionDao())

        instrumentation.runOnMainSync {
            val texture = SurfaceTexture(0)
            surfaceTexture = texture
            surface = Surface(texture)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        stores.clear()
        MediaSessionHostHolder.host = priorHost
        runBlocking { db.downloadSessionDao().delete(animeSession) }
        instrumentation.runOnMainSync {
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
        }
        server.close()
    }

    private fun episodeRow(id: String, number: Float, url: String) = EpisodeDownloadEntity(
        episodeId = id,
        sessionId = animeSession,
        episodeNumber = number,
        episodeSession = "ep-$id",
        title = "Episode $number",
        fansub = "SubsPlease",
        audio = EpisodeInfo.AUDIO_JAPANESE,
        playUrl = url,
        // NOT completed: the stream path must not depend on download state.
        state = EpisodeDownloadEntity.STATE_RESOLVING,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    /** The ladder exactly as production wires it, with Room as the only rung. */
    private fun ladder() = StreamEpisodeReconstructor(
        roomEpisode = { id ->
            db.episodeDownloadDao().getById(id)
                ?.takeIf { it.playUrl.isNotBlank() }
                ?.let { row ->
                    EpisodeInfo(
                        number = row.episodeNumber,
                        session = row.episodeSession,
                        title = row.title,
                        fansub = row.fansub,
                        audio = row.audio,
                        playUrl = row.playUrl,
                    )
                }
        },
        cachedEpisode = { _, _, _ -> null },
        scannedEpisodes = { emptyList() },
    )

    private fun newPlayer(resolver: StreamResolver): PlayerViewModel {
        val viewModel = PlayerViewModel(
            playerFactory = {
                ExoPlayer.Builder(context).build().also { exo ->
                    // A real output surface: without one the video renderer
                    // never advances and Media3 may never finish preparing the
                    // period (the same reason StreamHlsHeaderTest attaches one).
                    surface?.let { exo.setVideoSurface(it) }
                }
            },
            downloadRepository = downloadRepository,
            positionRepository = positionRepository,
            mediaSessionHost = MediaSessionHost(context),
            streamResolver = resolver,
            hlsSourceFactory = HlsPlaybackSourceFactory(),
            streamReconstructor = ladder(),
            configProvider = { AppContext.defaults() },
            streamEpisodeLister = { siblingEpisodes() },
        )
        val store = ViewModelStore()
        stores += store
        return ViewModelProvider(store, SingleViewModelFactory(viewModel))
            .get(PlayerViewModel::class.java)
    }

    /** The sibling list as the details screen caches it: both SUB episodes. */
    private fun siblingEpisodes() = listOf(
        EpisodeInfo(
            number = 1f,
            session = "ep-$episodeId",
            title = "Episode 1",
            fansub = "SubsPlease",
            audio = EpisodeInfo.AUDIO_JAPANESE,
            playUrl = playUrl,
        ),
        EpisodeInfo(
            number = 2f,
            session = "ep-$plainEpisodeId",
            title = "Episode 2",
            fansub = "SubsPlease",
            audio = EpisodeInfo.AUDIO_JAPANESE,
            playUrl = plainPlayUrl,
        ),
    )

    private fun load(viewModel: PlayerViewModel, mode: PlayerMode) {
        instrumentation.runOnMainSync { viewModel.load(episodeId, mode) }
    }

    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync { block() }
    }

    // -----------------------------------------------------------------------
    // Player observation (every ExoPlayer read happens on the main thread)
    // -----------------------------------------------------------------------

    /**
     * What the player reports about one text track.
     *
     * Media3 exposes an HLS subtitle rendition as `application/x-media3-cues`
     * carrying the manifest's `NAME`/`LANGUAGE` (instrumented: `English`/`en`),
     * and an MPEG-TS decoder additionally manufactures an unlabelled,
     * language-less `application/cea-608` placeholder for every stream.
     */
    private data class TextTrackInfo(
        val label: String?,
        val language: String?,
        val selected: Boolean,
    ) {
        /** A rendition the media itself named — never the CEA-608 placeholder. */
        val declared: Boolean get() = !label.isNullOrBlank() || !language.isNullOrBlank()

        override fun toString(): String =
            "${label ?: "-"}/${language ?: "-"}" + if (selected) " (on)" else ""
    }

    private data class Snapshot(
        /** Every text track the player currently exposes. */
        val textTracks: List<TextTrackInfo>,
        val textTypeDisabled: Boolean,
        /** The player's own parameters, as the player reports them. */
        val params: String,
        val playbackState: Int,
        val mediaId: String?,
        val error: String?,
    )

    private fun snapshot(viewModel: PlayerViewModel): Snapshot {
        var result = Snapshot(emptyList(), false, "no-player", Player.STATE_IDLE, null, null)
        onMain {
            val player = viewModel.uiState.value.player
            if (player != null) {
                val textGroups = player.currentTracks.groups.filter {
                    it.type == C.TRACK_TYPE_TEXT
                }
                val params = player.trackSelectionParameters
                result = Snapshot(
                    textTracks = textGroups.flatMap { group ->
                        (0 until group.length).map { index ->
                            val format = group.getTrackFormat(index)
                            TextTrackInfo(
                                label = format.label,
                                language = format.language,
                                selected = group.isTrackSelected(index),
                            )
                        }
                    },
                    textTypeDisabled = params.disabledTrackTypes
                        .contains(C.TRACK_TYPE_TEXT),
                    params = "disabled=${params.disabledTrackTypes} overrides=${params.overrides}",
                    playbackState = player.playbackState,
                    mediaId = player.currentMediaItem?.mediaId,
                    error = player.playerError?.message,
                )
            } else {
                result = result.copy(mediaId = null, playbackState = Player.STATE_IDLE)
            }
        }
        return result
    }

    private fun mediaId(viewModel: PlayerViewModel): String? {
        var id: String? = null
        onMain { id = viewModel.uiState.value.player?.currentMediaItem?.mediaId }
        return id
    }

    private fun select(viewModel: PlayerViewModel, index: Int) = onMain {
        viewModel.selectSubtitle(index)
    }

    /**
     * Selection state of the renditions the media itself named — the track
     * these assertions are about. The decoder's unlabelled CEA-608 placeholder
     * (a text track the manifest never published, which the derivation drops
     * from the menu) is filtered out here too, by the same rule.
     */
    private fun Snapshot.namedRenditionSelection(): List<Boolean> =
        textTracks.filter { it.declared }.map { it.selected }

    private fun describe(viewModel: PlayerViewModel): String =
        "state=${viewModel.uiState.value} " +
            "snapshot=${snapshot(viewModel)} " +
            "server=${server.requests().map { it.path }} " +
            "serverFailures=${server.failures()}"

    // -----------------------------------------------------------------------
    // 1–6. Detect, select, disable, re-select, no-control, restore
    // -----------------------------------------------------------------------

    @Test
    fun aSubtitleRenditionIsDetectedSelectedDisabledAndRestoredAcrossAnEpisodeSwitch() {
        val resolver = FixtureResolver(
            mapOf(
                playUrl to "${server.baseUrl}/master.m3u8",
                plainPlayUrl to "${server.baseUrl}/plain.m3u8",
            ),
        )
        val viewModel = newPlayer(resolver)

        load(viewModel, PlayerMode.STREAM)

        // ── 1. detection + availability ────────────────────────────────────
        assertTrue(
            "the subtitle rendition must be detected once the master playlist " +
                "is parsed (${describe(viewModel)})",
            awaitCondition(30_000) { viewModel.uiState.value.subtitleOptions.isNotEmpty() },
        )
        assertEquals(
            "exactly the one published rendition must be offered " +
                "(${describe(viewModel)})",
            1,
            viewModel.uiState.value.subtitleOptions.size,
        )
        assertTrue(
            "the offered track must carry a name the viewer can read",
            viewModel.uiState.value.subtitleOptions.single().label.isNotBlank(),
        )
        assertTrue(
            "the subtitle rendition must be exposed as a TEXT track (${describe(viewModel)})",
            awaitCondition(20_000) { snapshot(viewModel).namedRenditionSelection().isNotEmpty() },
        )

        // ── real playback must be healthy with a subtitle rendition present ─
        assertTrue(
            "playback must reach READY with subtitles available (${describe(viewModel)})",
            awaitCondition(30_000) { snapshot(viewModel).playbackState == Player.STATE_READY },
        )

        // ── 2. select ──────────────────────────────────────────────────────
        select(viewModel, 0)
        assertEquals(
            "the chosen option must be reported as the selected one",
            0,
            viewModel.uiState.value.selectedSubtitleIndex,
        )
        assertTrue(
            "ExoPlayer must really render the chosen text track (${describe(viewModel)})",
            awaitCondition(20_000) { snapshot(viewModel).namedRenditionSelection() == listOf(true) },
        )
        assertFalse(
            "selecting must not leave the text track type disabled",
            snapshot(viewModel).textTypeDisabled,
        )
        assertTrue(
            "the selected rendition must really be requested",
            awaitCondition(20_000) { server.requestsFor("/subs0.vtt").isNotEmpty() },
        )

        // ── 3. disable ─────────────────────────────────────────────────────
        select(viewModel, -1)
        assertEquals(
            "Off must be reported as no selection",
            -1,
            viewModel.uiState.value.selectedSubtitleIndex,
        )
        assertTrue(
            "Off must disable the text track type (${describe(viewModel)})",
            awaitCondition(20_000) { snapshot(viewModel).textTypeDisabled },
        )
        assertTrue(
            "Off must leave the rendition unselected (${describe(viewModel)})",
            awaitCondition(20_000) { snapshot(viewModel).namedRenditionSelection().all { !it } },
        )

        // ── 4. re-select ───────────────────────────────────────────────────
        select(viewModel, 0)
        assertTrue(
            "subtitles must come back on (${describe(viewModel)})",
            awaitCondition(20_000) {
                viewModel.uiState.value.selectedSubtitleIndex == 0 &&
                    snapshot(viewModel).namedRenditionSelection() == listOf(true) &&
                    !snapshot(viewModel).textTypeDisabled
            },
        )

        // ── 5. an episode with NO subtitle rendition publishes no options ───
        onMain { viewModel.playNextEpisode() }
        assertTrue(
            "the sibling episode must be loaded on the same player (${describe(viewModel)})",
            awaitCondition(30_000) { mediaId(viewModel) == plainEpisodeId },
        )
        assertTrue(
            "the sibling episode must finish loading before it is judged",
            awaitCondition(20_000) { !viewModel.uiState.value.loading },
        )
        Thread.sleep(1_500)
        assertEquals(
            "a media without a text track must publish NO subtitle options " +
                "(${describe(viewModel)})",
            emptyList<SubtitleOption>(),
            viewModel.uiState.value.subtitleOptions,
        )
        assertEquals(
            "…and therefore no selection",
            -1,
            viewModel.uiState.value.selectedSubtitleIndex,
        )
        assertTrue(
            "no subtitle rendition may be exposed for that media — only the " +
                "decoder's unlabelled caption placeholder is allowed to be " +
                "(${describe(viewModel)})",
            snapshot(viewModel).namedRenditionSelection().isEmpty(),
        )
        assertEquals(
            "a media without subtitles must still play normally",
            null,
            viewModel.uiState.value.loadError,
        )

        // ── 6. going back restores the viewer's choice unaided ─────────────
        onMain { viewModel.playPreviousEpisode() }
        assertTrue(
            "the subtitled episode must be back on the same player (${describe(viewModel)})",
            awaitCondition(30_000) { mediaId(viewModel) == episodeId },
        )
        assertTrue(
            "its subtitle rendition must be detected again (${describe(viewModel)})",
            awaitCondition(20_000) { viewModel.uiState.value.subtitleOptions.isNotEmpty() },
        )
        assertTrue(
            "the viewer's choice must survive the switch WITHOUT another tap " +
                "(${describe(viewModel)})",
            awaitCondition(20_000) { viewModel.uiState.value.selectedSubtitleIndex == 0 },
        )
        assertTrue(
            "and ExoPlayer must really be rendering it again",
            awaitCondition(20_000) { snapshot(viewModel).namedRenditionSelection() == listOf(true) },
        )

        assertEquals(
            "one resolve per episode transition, never a loop",
            listOf(playUrl, plainPlayUrl, playUrl),
            resolver.calls,
        )
    }

    // -----------------------------------------------------------------------
    // No text track at all → no control, at first load
    // -----------------------------------------------------------------------

    @Test
    fun aStreamWithoutASubtitleRenditionPublishesNoSubtitleControlEver() {
        val resolver = FixtureResolver(
            mapOf(playUrl to "${server.baseUrl}/plain.m3u8"),
        )
        val viewModel = newPlayer(resolver)

        load(viewModel, PlayerMode.STREAM)

        assertTrue(
            "the plain stream must reach a healthy state first (${describe(viewModel)})",
            awaitCondition(30_000) { snapshot(viewModel).playbackState == Player.STATE_READY },
        )
        Thread.sleep(2_000)

        assertEquals(
            "a media that publishes no text track must offer no subtitle control " +
                "(${describe(viewModel)})",
            emptyList<SubtitleOption>(),
            viewModel.uiState.value.subtitleOptions,
        )
        assertEquals(-1, viewModel.uiState.value.selectedSubtitleIndex)
        assertTrue(
            "…while still playing normally",
            viewModel.uiState.value.loadError == null,
        )
        assertEquals("no subtitle rendition may be requested", 0, server.requestsFor("/subs.m3u8").size)
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    /**
     * A master playlist with one subtitle rendition, its video media playlist
     * (real FFmpegKit-generated H.264 segments), the rendition's WebVTT media
     * playlist and the WebVTT itself — plus a subtitle-free stream for the
     * "no control" half of the requirement.
     */
    private fun fixtureResources(): Map<String, ByteArray> {
        val segment = ensureSegmentBytes()
        return mapOf(
            "/master.m3u8" to MASTER_PLAYLIST.toByteArray(),
            "/video.m3u8" to VIDEO_PLAYLIST.toByteArray(),
            "/plain.m3u8" to VIDEO_PLAYLIST.toByteArray(),
            "/subs.m3u8" to SUBTITLE_PLAYLIST.toByteArray(),
            "/subs0.vtt" to SUBTITLES.toByteArray(),
            "/seg0.ts" to segment,
        )
    }

    private fun ensureSegmentBytes(): ByteArray {
        cachedSegment?.let { cached -> return cached }
        val dir = File(context.filesDir, "subtitle_fixture").apply { mkdirs() }
        val output = File(dir, "seg0.ts")
        if (!output.exists() || output.length() == 0L) {
            val command = "-y -f lavfi -i color=c=navy:s=320x240:d=6:r=25 " +
                "-c:v libopenh264 -g 25 -pix_fmt yuv420p -f mpegts ${output.absolutePath}"
            val session = FFmpegKit.execute(command)
            check(ReturnCode.isSuccess(session.returnCode)) {
                "subtitle fixture TS generation failed: ${session.output}"
            }
        }
        val bytes = output.readBytes()
        cachedSegment = bytes
        return bytes
    }

    private companion object {
        @Volatile
        private var cachedSegment: ByteArray? = null

        private val MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,FORCED=NO,URI="subs.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=320x240,SUBTITLES="subs"
            video.m3u8
        """.trimIndent()

        private val VIDEO_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        private val SUBTITLE_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6.0,
            subs0.vtt
            #EXT-X-ENDLIST
        """.trimIndent()

        private val SUBTITLES = """
            WEBVTT

            00:00.000 --> 00:03.000
            Fixture subtitle line one

            00:03.000 --> 00:06.000
            Fixture subtitle line two
        """.trimIndent()
    }
}
