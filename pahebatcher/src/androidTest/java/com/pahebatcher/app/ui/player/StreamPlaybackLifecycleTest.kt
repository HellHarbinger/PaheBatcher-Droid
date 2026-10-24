package com.pahebatcher.app.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.pahebatcher.app.domain.stream.StreamResolutionException
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.playback.MediaSessionHost
import com.pahebatcher.app.playback.MediaSessionHostHolder
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.ui.navigation.PlayerMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 17 §21-B/§21-C — STREAM lifecycle over the REAL Room database, the REAL
 * [StreamEpisodeReconstructor] ladder and the REAL Media3 stack, with the
 * resolver replaced by a scripted test double (the production resolver needs a
 * live WebView plus a Cloudflare challenge, which this test deliberately does
 * not touch, so it can run anywhere).
 *
 * Covered:
 *
 *  1. a STREAM entry reconstructs `playUrl` from the persisted row and resolves
 *     JUST-IN-TIME with that play page, the configured quality and the episode's
 *     own audio — and writes NO download row / state;
 *  2. a brand-new player instance over the SAME database reconstructs again,
 *     with the ladder's network rung wired to FAIL the test if it is reached;
 *  3. a resolution failure is reported honestly, never retried automatically,
 *     and each user retry spends exactly one budgeted attempt until the budget
 *     is spent;
 *  4. a playback (HTTP) error re-resolves exactly once per user Retry;
 *  5. DOWNLOAD mode on the same player never enters the stream path.
 *
 * Position retention across a source swap is asserted at the policy level in
 * the JVM test `StreamRetryPolicyTest` — observing it end-to-end needs a real,
 * advancing stream, which only real playback can provide.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class StreamPlaybackLifecycleTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var db: PaheDatabase
    private lateinit var server: RecordingHlsServer
    private lateinit var downloadRepository: DownloadRepository
    private lateinit var positionRepository: PlaybackPositionRepository

    private lateinit var animeSession: String
    private lateinit var episodeId: String
    private lateinit var playUrl: String

    private val stores = mutableListOf<ViewModelStore>()
    private var priorHost: MediaSessionHost? = null

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    private data class ResolverCall(val playUrl: String, val quality: Int, val audio: String)

    private sealed interface Outcome {
        data class Url(val playlistUrl: String?) : Outcome
        data class Fail(val message: String) : Outcome
    }

    /** Scripted resolver: records every call, then returns the scripted outcome. */
    private class ScriptedResolver(
        private val scripted: List<Outcome> = listOf(Outcome.Url(null)),
        private val urlForCall: (Int) -> String = { "http://127.0.0.1:9/not-a-real-playlist.m3u8" },
    ) : StreamResolver {
        val calls = mutableListOf<ResolverCall>()

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            val index = calls.size
            calls += ResolverCall(playUrl, quality, audio)
            val outcome = scripted.getOrNull(index) ?: scripted.last()
            if (outcome is Outcome.Fail) throw StreamResolutionException(outcome.message)
            val scriptedUrl = (outcome as Outcome.Url).playlistUrl
            return StreamInfo(
                url = scriptedUrl ?: urlForCall(index),
                cookies = emptyList(),
                userAgent = "Phase17LifecycleAgent/1.0",
                referer = "https://kwik.example.invalid/page",
                title = "",
                audio = audio,
                fansub = "",
            )
        }
    }

    /**
     * A resolver that records rather than throws: if a DOWNLOAD entry ever
     * reaches it the counter goes up and the assertion fails, without an
     * uncaught AssertionError tearing down the test process first.
     */
    private class ForbiddenResolver : StreamResolver {
        var calls = 0

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            calls++
            return StreamInfo(
                url = "http://127.0.0.1:9/must-not-be-resolved.m3u8",
                cookies = emptyList(),
                userAgent = "must-not-be-used",
                referer = "https://kwik.example.invalid/must-not-be-used",
                audio = audio,
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
        server = RecordingHlsServer()
        priorHost = MediaSessionHostHolder.host

        animeSession = UUID.randomUUID().toString()
        episodeId = "${animeSession}_1_jpn"
        playUrl = "https://animepahe.example.invalid/play/$animeSession/ep1"

        runBlocking {
            Phase10TestSupport.insertSession(db, animeSession)
            db.episodeDownloadDao().upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = animeSession,
                    episodeNumber = 1f,
                    episodeSession = "ep-$episodeId",
                    title = "Beginnings",
                    fansub = "SubsPlease",
                    audio = EpisodeInfo.AUDIO_JAPANESE,
                    playUrl = playUrl,
                    // NOT completed: the stream path must not depend on state,
                    // and the DOWNLOAD branch must take its own route.
                    state = EpisodeDownloadEntity.STATE_RESOLVING,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        downloadRepository = DownloadRepository(
            context,
            db.episodeDownloadDao(),
            db.downloadSessionDao(),
        )
        positionRepository = PlaybackPositionRepository(db.playbackPositionDao())
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        stores.clear()
        MediaSessionHostHolder.host = priorHost
        runBlocking { db.downloadSessionDao().delete(animeSession) }
        server.close()
    }

    /**
     * The ladder exactly as production wires it, except that the NETWORK rung
     * is only COUNTERED: this test has no Cloudflare session, so reaching it
     * would mean reconstruction fell back to the network when persisted
     * metadata should have answered. Counting (instead of throwing) keeps the
     * failure as a clean assertion rather than an uncaught AssertionError inside
     * the ViewModel's scope.
     */
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
        scannedEpisodes = { session ->
            networkScanCalls++
            lastNetworkScanSession = session
            emptyList()
        },
    )

    private var networkScanCalls = 0
    private var lastNetworkScanSession: String? = null

    private fun newPlayer(
        resolver: StreamResolver,
        siblingList: suspend (String) -> List<EpisodeInfo> = { emptyList() },
    ): PlayerViewModel {
        val viewModel = PlayerViewModel(
            playerFactory = { ExoPlayer.Builder(context).build() },
            downloadRepository = downloadRepository,
            positionRepository = positionRepository,
            mediaSessionHost = MediaSessionHost(context),
            streamResolver = resolver,
            hlsSourceFactory = HlsPlaybackSourceFactory(),
            streamReconstructor = ladder(),
            configProvider = { AppContext.defaults() },
            streamEpisodeLister = siblingList,
        )
        val store = ViewModelStore()
        stores += store
        return ViewModelProvider(store, SingleViewModelFactory(viewModel))
            .get(PlayerViewModel::class.java)
    }

    private fun load(viewModel: PlayerViewModel, mode: PlayerMode) {
        instrumentation.runOnMainSync { viewModel.load(episodeId, mode) }
    }

    /**
     * ExoPlayer may only be queried on the main thread (Media3 enforces this),
     * while `awaitCondition` polls from the instrumentation thread — so every
     * read of the player goes through here.
     */
    private fun currentMediaId(viewModel: PlayerViewModel): String? {
        var mediaId: String? = null
        instrumentation.runOnMainSync {
            mediaId = viewModel.uiState.value.player?.currentMediaItem?.mediaId
        }
        return mediaId
    }

    private fun downloadStateSnapshot(): List<Pair<String, String>> = runBlocking {
        db.episodeDownloadDao().getById(episodeId)
            ?.let { listOf(it.episodeId to it.state) }
            ?: emptyList()
    }

    // -----------------------------------------------------------------------
    // 1 + 6. Just-in-time resolve from persisted metadata, no download writes
    // -----------------------------------------------------------------------

    @Test
    fun aStreamEntryResolvesJustInTimeFromThePersistedPlayUrlWithoutTouchingDownloadState() {
        val resolver = ScriptedResolver()
        val before = downloadStateSnapshot()
        val viewModel = newPlayer(resolver)

        load(viewModel, PlayerMode.STREAM)

        assertTrue(
            "the stream must resolve just-in-time (state=${viewModel.uiState.value})",
            awaitCondition(20_000) { resolver.calls.isNotEmpty() },
        )
        val call = resolver.calls.first()
        assertEquals("the episode's own play page", playUrl, call.playUrl)
        assertEquals("the configured quality", AppContext.defaults().quality, call.quality)
        assertEquals("the episode's own audio", EpisodeInfo.AUDIO_JAPANESE, call.audio)
        assertEquals(
            "exactly one resolve — no automatic re-resolve loop",
            1,
            resolver.calls.size,
        )

        assertTrue(
            "the player must hold a media item keyed by the SAME episode id " +
                "(one position key for both modes)",
            awaitCondition(15_000) { currentMediaId(viewModel) == episodeId },
        )
        assertEquals(
            "streaming must not create or modify a download row",
            before,
            downloadStateSnapshot(),
        )
        assertEquals(
            "Room answered on its own — the network scan must not have been reached " +
                "(session=$lastNetworkScanSession)",
            0,
            networkScanCalls,
        )
    }

    // -----------------------------------------------------------------------
    // 2. Reconstruction after a brand-new player instance (§21-C)
    // -----------------------------------------------------------------------

    @Test
    fun aFreshPlayerReconstructsTheSameStreamFromPersistedMetadataAlone() {
        val first = ScriptedResolver()
        val firstPlayer = newPlayer(first)
        load(firstPlayer, PlayerMode.STREAM)
        assertTrue(awaitCondition(20_000) { first.calls.isNotEmpty() })

        // "Recreation": a completely new ViewModel over the same database — no
        // inherited in-memory state, only what Room still holds.
        val second = ScriptedResolver()
        val secondPlayer = newPlayer(second)
        load(secondPlayer, PlayerMode.STREAM)

        assertTrue(
            "the fresh player must reconstruct and resolve again",
            awaitCondition(20_000) { second.calls.isNotEmpty() },
        )
        assertEquals(playUrl, second.calls.first().playUrl)
        assertEquals(
            "reconstruction answered entirely from Room",
            1,
            second.calls.size,
        )
        assertEquals(
            "no network scan may have been reached " +
                "(session=$lastNetworkScanSession)",
            0,
            networkScanCalls,
        )
    }

    // -----------------------------------------------------------------------
    // 3. Bounded, user-driven retry on resolution failures
    // -----------------------------------------------------------------------

    @Test
    fun aFailedResolutionIsNeverRetriedAutomaticallyAndStopsWhenTheBudgetIsSpent() {
        val alwaysFails = ScriptedResolver(
            scripted = List(10) { Outcome.Fail("Could not resolve Kwik URL: https://kwik.example.invalid/x") },
        )
        val viewModel = newPlayer(alwaysFails)

        load(viewModel, PlayerMode.STREAM)
        assertTrue(
            "the failure must be reported honestly",
            awaitCondition(20_000) { viewModel.uiState.value.loadError != null },
        )
        assertEquals(
            "one resolve and no automatic retry",
            1,
            alwaysFails.calls.size,
        )
        val firstError = viewModel.uiState.value.loadError
        assertNotNull(firstError)
        assertTrue(
            "a stream failure must not use the download-path copy",
            !firstError!!.contains("no longer available"),
        )

        // Each user retry spends exactly one attempt…
        repeat(3) { attempt ->
            instrumentation.runOnMainSync { viewModel.retryLoad() }
            assertTrue(
                "retry #${attempt + 1} must re-resolve",
                awaitCondition(20_000) { alwaysFails.calls.size == attempt + 2 },
            )
        }
        assertEquals(4, alwaysFails.calls.size)

        // …and once the budget is spent the player stops instead of looping.
        instrumentation.runOnMainSync { viewModel.retryLoad() }
        Thread.sleep(1_500)
        assertEquals(
            "the budget must stop further re-resolution",
            4,
            alwaysFails.calls.size,
        )
        assertEquals(STREAM_RETRY_EXHAUSTED, viewModel.uiState.value.loadError)
    }

    // -----------------------------------------------------------------------
    // 4. A playback error re-resolves exactly once per user Retry
    // -----------------------------------------------------------------------

    @Test
    fun aPlaybackErrorReResolvesOncePerUserRetryAndNeverOnItsOwn() {
        // A URL that really exists on the loopback server but returns 404 — a
        // real HTTP failure the Media3 stack turns into a playback error.
        val resolver = ScriptedResolver(
            scripted = List(10) { Outcome.Url("${server.baseUrl}/missing.m3u8") },
        )
        val viewModel = newPlayer(resolver)

        load(viewModel, PlayerMode.STREAM)
        assertTrue(
            "the 404 must surface as a playback error (state=${viewModel.uiState.value})",
            awaitCondition(30_000) { viewModel.uiState.value.playerError },
        )
        assertEquals(1, resolver.calls.size)

        Thread.sleep(1_500)
        assertEquals(
            "a playback error must NEVER re-resolve on its own",
            1,
            resolver.calls.size,
        )

        instrumentation.runOnMainSync { viewModel.retryPlayback() }
        assertTrue(
            "the user Retry must re-resolve and rebuild the HLS source",
            awaitCondition(20_000) { resolver.calls.size == 2 },
        )
        assertEquals(2, resolver.calls.size)
        assertEquals(
            "the reconstructed playUrl is stable across retries",
            playUrl,
            resolver.calls[1].playUrl,
        )
    }

    // -----------------------------------------------------------------------
    // SPEC §15. Streaming episode navigation — next / previous, in place
    // -----------------------------------------------------------------------

    @Test
    fun streamingNavigationSwitchesTheEpisodeInPlaceAndNeverOutsideTheSiblingList() {
        val nextEpisodeId = "${animeSession}_2_jpn"
        val nextPlayUrl = "https://animepahe.example.invalid/play/$animeSession/ep2"
        runBlocking {
            db.episodeDownloadDao().upsert(
                EpisodeDownloadEntity(
                    episodeId = nextEpisodeId,
                    sessionId = animeSession,
                    episodeNumber = 2f,
                    episodeSession = "ep-$nextEpisodeId",
                    title = "Escalation",
                    fansub = "SubsPlease",
                    audio = EpisodeInfo.AUDIO_JAPANESE,
                    playUrl = nextPlayUrl,
                    state = EpisodeDownloadEntity.STATE_COMPLETED,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        val before = downloadStateSnapshot()
        val resolver = ScriptedResolver()
        // The session as the details screen caches it: two SUB episodes plus the
        // DUB of episode 1, which must never appear in a SUB navigation.
        val siblingEpisodes = listOf(
            streamEpisode(episodeId, 1f, playUrl, EpisodeInfo.AUDIO_JAPANESE),
            streamEpisode(nextEpisodeId, 2f, nextPlayUrl, EpisodeInfo.AUDIO_JAPANESE),
            streamEpisode(
                "${animeSession}_1_eng",
                1f,
                "https://animepahe.example.invalid/play/$animeSession/ep1-dub",
                EpisodeInfo.AUDIO_ENGLISH,
            ),
        )
        val listerCalls = AtomicInteger(0)
        val viewModel = newPlayer(resolver) {
            listerCalls.incrementAndGet()
            siblingEpisodes
        }

        load(viewModel, PlayerMode.STREAM)
        assertTrue(
            "the episode must resolve just-in-time first",
            awaitCondition(20_000) { resolver.calls.isNotEmpty() },
        )
        assertTrue(
            "the sibling list must be read once the episode is playing " +
                "(state=${viewModel.uiState.value})",
            awaitCondition(20_000) { viewModel.uiState.value.siblingEpisodeIds.size == 2 },
        )
        assertEquals(
            "the navigation is bounded by the SAME audio variant",
            listOf(episodeId, nextEpisodeId),
            viewModel.uiState.value.siblingEpisodeIds,
        )
        assertEquals(PlayerMode.STREAM, viewModel.uiState.value.mode)
        assertEquals(0, viewModel.uiState.value.siblingIndex)
        assertEquals(1, listerCalls.get())

        // ── next, in place (§24.2: same player, same route) ─────────────────
        assertTrue(awaitCondition(20_000) { !viewModel.uiState.value.loading })
        instrumentation.runOnMainSync { viewModel.playNextEpisode() }
        assertTrue(
            "next must put the neighbouring episode on the SAME player",
            awaitCondition(20_000) { currentMediaId(viewModel) == nextEpisodeId },
        )
        assertTrue(awaitCondition(20_000) { !viewModel.uiState.value.loading })
        assertEquals(
            "each episode gets its own just-in-time resolve",
            2,
            resolver.calls.size,
        )
        assertEquals(nextPlayUrl, resolver.calls[1].playUrl)
        assertEquals(1, viewModel.uiState.value.siblingIndex)
        assertEquals(
            "the overlay must announce the episode now on screen",
            "Escalation · Episode 2 [SUB]",
            viewModel.uiState.value.title,
        )

        // ── bounded at the end: no wrap, no extra resolve ───────────────────
        instrumentation.runOnMainSync { viewModel.playNextEpisode() }
        Thread.sleep(1_500)
        assertEquals(
            "navigation must not run past the last episode",
            2,
            resolver.calls.size,
        )
        assertEquals(nextEpisodeId, currentMediaId(viewModel))

        // ── previous, back onto the entry's own episode ─────────────────────
        instrumentation.runOnMainSync { viewModel.playPreviousEpisode() }
        assertTrue(
            "previous must return to the first episode",
            awaitCondition(20_000) {
                resolver.calls.size == 3 && currentMediaId(viewModel) == episodeId
            },
        )
        assertTrue(awaitCondition(20_000) { !viewModel.uiState.value.loading })
        assertEquals(0, viewModel.uiState.value.siblingIndex)

        // ── bounded at the start as well ────────────────────────────────────
        instrumentation.runOnMainSync { viewModel.playPreviousEpisode() }
        Thread.sleep(1_500)
        assertEquals(3, resolver.calls.size)

        // ── streaming never touches download state ──────────────────────────
        assertEquals(
            "streaming must not create or modify a download row",
            before,
            downloadStateSnapshot(),
        )
        assertEquals(
            "one list read per entry — a switch never re-reads it",
            1,
            listerCalls.get(),
        )
    }

    private fun streamEpisode(id: String, number: Float, playUrl: String, audio: String) =
        EpisodeInfo(
            number = number,
            session = "ep-$id",
            title = "Episode $number",
            fansub = "SubsPlease",
            audio = audio,
            playUrl = playUrl,
        )

    // -----------------------------------------------------------------------
    // 5. DOWNLOAD mode never enters the stream path
    // -----------------------------------------------------------------------

    @Test
    fun downloadModeKeepsItsOwnPathAndNeverResolvesOrScans() {
        val forbidden = ForbiddenResolver()
        val listerCalls = AtomicInteger(0)
        val viewModel = newPlayer(forbidden) {
            listerCalls.incrementAndGet()
            emptyList()
        }

        load(viewModel, PlayerMode.DOWNLOAD)

        assertTrue(
            "the local branch must reach its own honest verdict " +
                "(state=${viewModel.uiState.value})",
            awaitCondition(20_000) { viewModel.uiState.value.loadError != null },
        )
        // The seeded row is not COMPLETED, so the LOCAL path says what it has
        // always said for a row with no playable file.
        assertEquals(
            "This episode has no playable file.",
            viewModel.uiState.value.loadError,
        )
        assertEquals(
            "DOWNLOAD must not consult the stream resolver",
            0,
            forbidden.calls,
        )
        assertTrue(
            "DOWNLOAD must not run the stream reconstruction ladder either",
            viewModel.uiState.value.loadError != STREAM_SESSION_EXPIRED,
        )

        // SPEC §15: episode navigation belongs to the stream path — the local
        // playlist keeps Media3's own skip controls and must never ask this
        // player for a sibling list.
        instrumentation.runOnMainSync {
            viewModel.playNextEpisode()
            viewModel.playPreviousEpisode()
        }
        Thread.sleep(1_000)
        assertEquals("DOWNLOAD must not read the stream sibling list", 0, listerCalls.get())
        assertEquals("DOWNLOAD must not resolve through the stream resolver", 0, forbidden.calls)
    }
}
