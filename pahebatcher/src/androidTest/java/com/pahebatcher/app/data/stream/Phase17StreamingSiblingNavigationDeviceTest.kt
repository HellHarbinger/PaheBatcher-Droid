package com.pahebatcher.app.data.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.di.AppViewModelFactory
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.logging.LogUrlSanitizer
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.ui.navigation.PlayerMode
import com.pahebatcher.app.ui.player.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.IdentityHashMap

/**
 * Phase 17 §15/§23 — SPEC "next episode"/"previous episode" for a STREAM entry,
 * executed against the REAL AnimePahe backend and the REAL CDN on a physical
 * device.
 *
 * Nothing is stubbed: the ViewModel is built by the production
 * [AppViewModelFactory] over the app's own [PaheBatcherApplication.appContainer]
 * (real WebView/Cloudflare session, real `StreamResolverImpl`, real
 * `HlsPlaybackSourceFactory`, real Room ladder), and every episode it plays is
 * discovered, scanned, resolved and streamed from the network.
 *
 * What it pins, in order:
 *
 *  1. seed at N (the second-to-last episode) — READY, real duration, no error;
 *  2. `playNextEpisode()` → N+1 — the identity really changes (the current
 *     media id), the player is READY again, and it is still the SAME ExoPlayer
 *     instance and the SAME ViewModel (one player, one stack — §24.2);
 *  3. `playNextEpisode()` at the LAST episode — the boundary is refused: the
 *     media id does not move, the index does not move, no error is published;
 *  4. `playPreviousEpisode()` → back to N — identity changes again and the
 *     player is healthy again;
 *  5. MediaSession coherence — the session still serves the one player.
 *
 * The surface is attached by the test as soon as the ViewModel publishes its
 * player, exactly as the real [com.pahebatcher.app.ui.player.PlayerScreen] does:
 * the video renderer needs a consumer before Media3 will finish preparing a
 * real video stream. Nothing about playback is faked — the surface is
 * Media3-facing output plumbing, not a substitute for the stream.
 *
 * Documented, deliberately NOT asserted as current: the MediaSession's episode
 * id is seeded once, when the player is first created (`MediaSessionHost
 * .onPlayerCreated`), and is not re-seeded on an episode switch. That route-seed
 * behaviour is a pre-existing, separately documented limitation; this test
 * asserts the coherence that does hold — the session still serves the same
 * single player — rather than papering over it.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class Phase17StreamingSiblingNavigationDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var store: ViewModelStore? = null
    private var viewModel: PlayerViewModel? = null
    private val lifecycleToken = Any()

    @After
    fun tearDown() {
        val vm = viewModel
        // onCleared() releases the player and the MediaSession — both are
        // main-thread-only Media3 objects, so the whole teardown runs there.
        instrumentation.runOnMainSync {
            vm?.onDetached(lifecycleToken, destroyedForConfigChange = false)
            store?.clear()
        }
        store = null
        viewModel = null
        instrumentation.runOnMainSync {
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
        }
    }

    @Test
    fun realCdnNextAndPreviousEpisodeNavigationStaysHealthyCoherentAndBounded() {
        val container = (context.applicationContext as PaheBatcherApplication).appContainer

        // ── 1. real discovery ─────────────────────────────────────────────────
        val results = runBlocking {
            container.animeRepository.search(AnimeRepository.currentHost, SEARCH_QUERY)
        }
        assertTrue(
            "STEP 1 (search): the backend must return at least one title " +
                "(got ${results.size}) — if this fails the backend blocked the step",
            results.isNotEmpty(),
        )
        val firstSession = requireNotNull(results.first()["session"] as? String) {
            "STEP 1 (search): the result must carry an anime session: ${results.first().keys}"
        }
        assertTrue("STEP 1 (search): the session must not be blank", firstSession.isNotBlank())

        // ── 2. real scan → the ordered episode list the VM navigates ─────────
        // The backend exposes different episode counts per title, so every
        // search candidate is scanned and its exposure logged; the FIRST title
        // with a navigable pair wins. If none has one, the assertion below
        // reports exactly what the backend returned for each — the honest
        // "the backend blocked this step" evidence rather than a guess.
        var chosenSession: String? = null
        var episodes: List<EpisodeInfo> = emptyList()
        val exposure = mutableListOf<String>()
        for (result in results) {
            val session = result["session"] as? String ?: continue
            val scanned = runBlocking {
                container.episodeRepository.scan(AnimeRepository.currentHost, session, 60)
            }
            val navigable = scanned.episodes.filter { it.playUrl.startsWith("https://") }
            exposure += "session=$session total=${scanned.episodes.size} " +
                "navigable=${navigable.size} sample=" +
                scanned.episodes.take(4).joinToString(" | ") {
                    "${it.epStr}/${it.audio}/${if (it.playUrl.isBlank()) "blank" else hostPath(it.playUrl)}"
                }
            Log.i(TAG, "scan: ${exposure.last()}")
            if (navigable.size >= 2 && episodes.isEmpty()) {
                chosenSession = session
                episodes = navigable
            }
        }
        assertTrue(
            "STEP 2 (scan): the backend must expose at least two navigable " +
                "episodes for N → N+1 → boundary → back — if this fails the " +
                "backend blocked the step. Exposure per scanned title: " +
                exposure.joinToString(" ;; "),
            episodes.size >= 2,
        )
        val session = requireNotNull(chosenSession) { "STEP 2: no session exposed a navigable pair" }

        val seedEpisode = episodes[episodes.size - 2]
        val nextEpisode = episodes.last()
        val seedId = EpisodeId.build(session, seedEpisode)
        val nextId = EpisodeId.build(session, nextEpisode)
        Log.i(TAG, "navigating: total=${episodes.size} seed=${seedEpisode.epStr} next=${nextEpisode.epStr}")

        // ── 3. the PRODUCTION ViewModel (real container, nothing stubbed) ─────
        val ownerToken = lifecycleToken
        val vm = ViewModelProvider(
            ViewModelStore().also { store = it },
            AppViewModelFactory(container),
        ).get(PlayerViewModel::class.java)
        viewModel = vm
        instrumentation.runOnMainSync {
            vm.onAttached(ownerToken)
            vm.onResumed(ownerToken)
        }

        // ── 4. seed at N = second-to-last ─────────────────────────────────────
        // Every ViewModel entry point runs on the main thread — exactly as the
        // real player screen invokes them.
        instrumentation.runOnMainSync { vm.load(seedId, PlayerMode.STREAM) }

        assertTrue(
            "STEP 4 (seed at N): the production ViewModel must publish a player",
            awaitCondition(60_000) { vm.uiState.value.player != null },
        )
        // The real screen attaches a surface the moment the player exists; the
        // renderer needs a consumer before a real video stream can prepare.
        instrumentation.runOnMainSync {
            val texture = SurfaceTexture(0)
            surfaceTexture = texture
            val output = Surface(texture)
            surface = output
            vm.uiState.value.player?.setVideoSurface(output)
        }

        val seedState = awaitHealthy(vm, "STEP 4 (seed at N)")
        assertEquals(
            "STEP 4: the seeded media id must be the episode that was loaded",
            seedId,
            seedState.mediaId,
        )
        val exo = requireNotNull(vm.uiState.value.player) { "player vanished" }

        assertTrue(
            "STEP 4: the sibling list must be published once the episode plays",
            awaitCondition(60_000) { vm.uiState.value.siblingEpisodeIds.isNotEmpty() },
        )
        assertEquals(
            "STEP 4: the index must point at the seeded (second-to-last) episode",
            episodes.size - 2,
            vm.uiState.value.siblingIndex,
        )

        // ── 5. NEXT → N+1 ─────────────────────────────────────────────────────
        // The switch re-resolves the next episode through the real
        // WebView/Cloudflare session, so the identity change — not merely READY
        // (which the seed already satisfies) — is the thing to wait for.
        instrumentation.runOnMainSync { vm.playNextEpisode() }
        assertTrue(
            "STEP 5: the current media must really become the NEXT episode " +
                "(current=${currentMediaId(vm)} wanted=$nextId)",
            awaitCondition(180_000) { currentMediaId(vm) == nextId },
        )
        val nextState = awaitHealthy(vm, "STEP 5 (next → N+1)")
        assertEquals(
            "STEP 5: the current media must really be the NEXT episode",
            nextId,
            nextState.mediaId,
        )
        assertEquals(
            "STEP 5: the index must have moved to the last episode",
            episodes.size - 1,
            vm.uiState.value.siblingIndex,
        )
        assertSame(
            "STEP 5 (§24.2 one player, one stack): a switch must reuse the SAME " +
                "ExoPlayer — a new instance per episode is a stack/perf defect",
            exo,
            vm.uiState.value.player,
        )
        assertEquals("STEP 5: the mode must still be STREAM", PlayerMode.STREAM, vm.uiState.value.mode)
        assertNull("STEP 5: a healthy switch publishes no load error", vm.uiState.value.loadError)

        // ── 6. NEXT at the LAST episode — the boundary must refuse ────────────
        // A refusal is a no-op: nothing re-resolves, nothing moves. The wait is
        // deliberately generous (real resolution of a wrongly-attempted switch
        // would surface as an identity change well within it), so "nothing
        // happened for a full minute" is the evidence, not a lucky snapshot.
        instrumentation.runOnMainSync { vm.playNextEpisode() }
        val movedDuringRefusal = awaitCondition(60_000) { currentMediaId(vm) != nextId }
        assertTrue(
            "STEP 6: a boundary Next must NOT move the media — the id changed " +
                "to ${currentMediaId(vm)} within the refusal window",
            !movedDuringRefusal,
        )
        assertEquals(
            "STEP 6: a boundary Next must NOT move the media (backend/stack " +
                "refusal is a no-op, never a crash or an off-list id)",
            nextId,
            currentMediaId(vm),
        )
        assertEquals(
            "STEP 6: a boundary Next must NOT move the index",
            episodes.size - 1,
            vm.uiState.value.siblingIndex,
        )
        assertNull(
            "STEP 6: a refused boundary switch must not publish an error",
            vm.uiState.value.loadError,
        )
        val boundaryState = awaitHealthy(vm, "STEP 6 (boundary refusal keeps the player healthy)")
        assertEquals(
            "STEP 6: the last episode must still be the one playing after the refusal",
            nextId,
            boundaryState.mediaId,
        )

        // ── 7. PREVIOUS → back to N ───────────────────────────────────────────
        instrumentation.runOnMainSync { vm.playPreviousEpisode() }
        assertTrue(
            "STEP 7: the current media must really become the seeded episode " +
                "again (current=${currentMediaId(vm)} wanted=$seedId)",
            awaitCondition(180_000) { currentMediaId(vm) == seedId },
        )
        val backState = awaitHealthy(vm, "STEP 7 (previous → back to N)")
        assertEquals(
            "STEP 7: the current media must really be the seeded episode again",
            seedId,
            backState.mediaId,
        )
        assertEquals(
            "STEP 7: the index must be back at the second-to-last episode",
            episodes.size - 2,
            vm.uiState.value.siblingIndex,
        )
        assertSame(
            "STEP 7 (§24.2): the way back must reuse the SAME ExoPlayer too",
            exo,
            vm.uiState.value.player,
        )
        assertEquals("STEP 7: the mode must still be STREAM", PlayerMode.STREAM, vm.uiState.value.mode)

        // ── 8. MediaSession coherence ─────────────────────────────────────────
        // The session was seeded once, at player creation (route-seed
        // limitation, documented and deliberately not asserted as current); what
        // must hold is that it still serves the one player through every switch.
        assertSame(
            "STEP 8: the MediaSession must still serve the SAME player after " +
                "two real episode switches",
            exo,
            container.mediaSessionHost.player,
        )
        assertTrue(
            "STEP 8: the MediaSession must still carry its seeded episode id",
            !container.mediaSessionHost.episodeId.isNullOrBlank(),
        )
        Log.i(
            TAG,
            "verified: seed=${seedState.mediaId} → next=${nextState.mediaId} " +
                "(refused boundary) → back=${backState.mediaId}; " +
                "sessionEpisode=${container.mediaSessionHost.episodeId}",
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private data class Healthy(val mediaId: String?, val durationMs: Long)

    /**
     * The player must be READY with a real duration and no error — the real
     * evidence that the CDN stream for the CURRENT media parsed end to end.
     *
     * Diagnostics (test-only slice): on failure this reports the whole Media3
     * [PlaybackException] — error code, `ExoPlaybackException` subclass detail
     * and the complete cause chain — instead of only the generic
     * `"Source error"` message, which is all Media3 puts in
     * [PlaybackException.getMessage]. The health predicate, the 150 s budget,
     * the 100 ms poll and every assertion this feeds are unchanged; only the
     * failure MESSAGE grew.
     *
     * Every rendered value passes through [LogUrlSanitizer.diagnostic]: a
     * Media3 error message can quote the failing expiring stream URL, and this
     * text reaches logcat and the instrumentation report (AGENTS §10).
     */
    private fun awaitHealthy(vm: PlayerViewModel, step: String): Healthy {
        var mediaId: String? = null
        var durationMs = -1L
        var state = Player.STATE_IDLE
        var error: String? = null
        var playerError: PlaybackException? = null
        var loadError: String? = null
        var positionMs = -1L
        var bufferedMs = -1L
        var playWhenReady = false
        var playing = false
        var currentTracks: Tracks? = null
        val healthy = awaitCondition(150_000) {
            instrumentation.runOnMainSync {
                val exo = vm.uiState.value.player
                state = exo?.playbackState ?: Player.STATE_IDLE
                durationMs = exo?.duration ?: -1L
                error = exo?.playerError?.message ?: vm.uiState.value.loadError
                mediaId = exo?.currentMediaItem?.mediaId
                // Added for failure reporting only — read on the main thread
                // exactly like the fields above, from the same player.
                playerError = exo?.playerError
                loadError = vm.uiState.value.loadError
                positionMs = exo?.currentPosition ?: -1L
                bufferedMs = exo?.bufferedPosition ?: -1L
                playWhenReady = exo?.playWhenReady ?: false
                playing = exo?.isPlaying ?: false
                currentTracks = exo?.currentTracks
            }
            state == Player.STATE_READY && durationMs > 0 && error == null
        }
        assertTrue(
            "$step: Media3 must reach READY on the real CDN stream — state=$state " +
                "duration=$durationMs error=$error mediaId=$mediaId" +
                if (healthy) {
                    ""
                } else {
                    renderPlaybackDiagnostics(
                        step = step,
                        state = state,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedMs,
                        playWhenReady = playWhenReady,
                        isPlaying = playing,
                        mediaId = mediaId,
                        loadError = loadError,
                        tracks = currentTracks,
                        playerError = playerError,
                    )
                },
            healthy,
        )
        return Healthy(mediaId = mediaId, durationMs = durationMs)
    }

    /**
     * Renders everything the player already holds when the health check failed.
     *
     * It REPORTS evidence and never infers it: an absent error renders as
     * `(none)`, an absent cause as `(no message)`, a media that published no
     * groups as `(empty ...)`. Nothing here can turn a failure into a pass, and
     * nothing here is consulted by any assertion — it is appended to the failure
     * message only.
     */
    private fun renderPlaybackDiagnostics(
        step: String,
        state: Int,
        positionMs: Long,
        durationMs: Long,
        bufferedMs: Long,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        mediaId: String?,
        loadError: String?,
        tracks: Tracks?,
        playerError: PlaybackException?,
    ): String = LogUrlSanitizer.diagnostic(
        buildString {
            append("\n  --- playback diagnostics (reported, never inferred) ---")
            append("\n  step                   : ").append(step)
            append("\n  playbackState          : ").append(stateName(state))
            append("\n  playWhenReady          : ").append(playWhenReady)
            append("\n  isPlaying              : ").append(isPlaying)
            append("\n  positionMs             : ").append(positionMs)
            append("\n  durationMs             : ").append(durationMs)
            append("\n  bufferedMs             : ").append(bufferedMs)
            append("\n  mediaId                : ").append(mediaId ?: "(none)")
            append("\n  loadError (VM UiState) : ").append(loadError ?: "(none)")
            append("\n  currentTracks          : ").append(renderTracks(tracks))
            append("\n  playerError            : ").append(
                playerError?.javaClass?.name ?: "(none)",
            )
            if (playerError != null) {
                // Media3 keeps the generic sentence in getMessage() and the real
                // failure underneath — the code names the class of failure and
                // the chain names the exact one.
                append("\n  errorCode              : ")
                    .append(playerError.errorCode)
                    .append(" (")
                    .append(playerError.errorCodeName)
                    .append(')')
                append("\n  errorTimestampMs       : ").append(playerError.timestampMs)
                appendExoPlaybackDetail(playerError)
            }
            append("\n  cause chain            :").append(causeChainOf(playerError))
        },
    )

    /**
     * The [ExoPlaybackException] fields that say WHERE the failure happened —
     * the loader versus a decoder — which the generic message cannot express.
     */
    private fun StringBuilder.appendExoPlaybackDetail(error: PlaybackException) {
        if (error !is ExoPlaybackException) {
            append("\n  exoDetail              : (not an ExoPlaybackException — ")
                .append(error.javaClass.name)
                .append(')')
            return
        }
        append("\n  exoErrorType           : ").append(exoTypeName(error.type))
        when (error.type) {
            ExoPlaybackException.TYPE_SOURCE -> {
                val source = runCatching { error.sourceException }.getOrNull()
                append("\n  sourceException        : ").append(
                    source?.let { "${it.javaClass.name}: ${sanitizedDetail(it)}" } ?: "(none)",
                )
            }
            ExoPlaybackException.TYPE_RENDERER -> {
                val format = error.rendererFormat
                append("\n  rendererName           : ").append(error.rendererName ?: "(none)")
                append("\n  rendererIndex          : ").append(error.rendererIndex)
                append("\n  rendererFormat         : ").append(
                    format?.let { "${it.sampleMimeType}/${it.codecs}" } ?: "(none)",
                )
                append("\n  rendererFormatSupport  : ")
                    .append(error.rendererFormatSupport)
                    .append(" (")
                    .append(formatSupportName(error.rendererFormatSupport))
                    .append(')')
            }
            ExoPlaybackException.TYPE_UNEXPECTED -> {
                val unexpected = runCatching { error.unexpectedException }.getOrNull()
                append("\n  unexpectedException    : ").append(
                    unexpected?.let { "${it.javaClass.name}: ${sanitizedDetail(it)}" } ?: "(none)",
                )
            }
        }
    }

    /**
     * The complete cause chain, bounded and cycle-safe.
     *
     * Media3 nests the real failure (`ParserException`, `InvalidResponseCodeException`,
     * …) several levels below the generic `"Source error"`, so the chain is the
     * only place the actual cause appears. Traversal is capped at
     * [MAX_CAUSE_DEPTH] and de-duplicated by identity, so neither a deep chain
     * nor a cyclic `cause` graph can hang the reporter.
     */
    private fun causeChainOf(error: Throwable?): String {
        if (error == null) return " (no player error)"
        val seen = IdentityHashMap<Throwable, Boolean>()
        return buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null) {
                if (seen.put(current, true) != null) {
                    append("\n    [").append(depth)
                        .append("] (cycle: ").append(current.javaClass.name)
                        .append(" already reported — traversal stopped)")
                    return@buildString
                }
                if (depth >= MAX_CAUSE_DEPTH) {
                    append("\n    ... traversal stopped at the ")
                        .append(MAX_CAUSE_DEPTH)
                        .append("-level cap (deeper causes are not shown)")
                    return@buildString
                }
                append("\n    [").append(depth)
                    .append("] ").append(current.javaClass.name)
                    .append(": ").append(sanitizedDetail(current))
                current = current.cause
                depth++
            }
        }
    }

    /** A message may quote the failing URL — never emit it verbatim (AGENTS §10). */
    private fun sanitizedDetail(error: Throwable): String =
        LogUrlSanitizer.diagnostic(error.message ?: "(no message)")

    /**
     * Whether the media even DECLARED groups: a stream that parsed its manifest
     * but published no track groups is a different failure from one that
     * declared video and then died loading it.
     */
    private fun renderTracks(tracks: Tracks?): String = when {
        tracks == null -> "(none)"
        tracks.isEmpty -> "(empty — no groups published)"
        else -> tracks.groups.joinToString(", ") { group ->
            "${trackTypeName(group.type)}/${group.length} track(s)" +
                if (group.isSelected) " selected" else ""
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "$state (STATE_IDLE)"
        Player.STATE_BUFFERING -> "$state (STATE_BUFFERING)"
        Player.STATE_READY -> "$state (STATE_READY)"
        Player.STATE_ENDED -> "$state (STATE_ENDED)"
        else -> "$state (STATE_UNKNOWN)"
    }

    private fun exoTypeName(type: Int): String = when (type) {
        ExoPlaybackException.TYPE_SOURCE -> "$type (TYPE_SOURCE — the media LOADER failed)"
        ExoPlaybackException.TYPE_RENDERER -> "$type (TYPE_RENDERER — a decoder failed)"
        ExoPlaybackException.TYPE_UNEXPECTED -> "$type (TYPE_UNEXPECTED)"
        ExoPlaybackException.TYPE_REMOTE -> "$type (TYPE_REMOTE)"
        else -> "$type (unknown type)"
    }

    private fun trackTypeName(type: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "text"
        C.TRACK_TYPE_METADATA -> "metadata"
        C.TRACK_TYPE_IMAGE -> "image"
        C.TRACK_TYPE_CAMERA_MOTION -> "cameraMotion"
        C.TRACK_TYPE_NONE -> "none"
        else -> "type=$type"
    }

    private fun formatSupportName(support: Int): String = when (support) {
        C.FORMAT_HANDLED -> "FORMAT_HANDLED"
        C.FORMAT_EXCEEDS_CAPABILITIES -> "FORMAT_EXCEEDS_CAPABILITIES"
        C.FORMAT_UNSUPPORTED_DRM -> "FORMAT_UNSUPPORTED_DRM"
        C.FORMAT_UNSUPPORTED_SUBTYPE -> "FORMAT_UNSUPPORTED_SUBTYPE"
        C.FORMAT_UNSUPPORTED_TYPE -> "FORMAT_UNSUPPORTED_TYPE"
        else -> "unknown"
    }

    /**
     * The current media id — read on the main thread because that is the only
     * thread ExoPlayer accepts (and the only thread the real UI calls from).
     */
    private fun currentMediaId(vm: PlayerViewModel): String? {
        var mediaId: String? = null
        instrumentation.runOnMainSync {
            mediaId = vm.uiState.value.player?.currentMediaItem?.mediaId
        }
        return mediaId
    }

    /** `host + path` only — never the expiring query string (AGENTS §10). */
    private fun hostPath(raw: String): String = try {
        val uri = URI(raw)
        "${uri.host.orEmpty()}${uri.path.orEmpty()}"
    } catch (error: Exception) {
        "[unparseable-url]"
    }

    private companion object {
        const val TAG = "SiblingNavVerify"

        /** Previously device-verified on `REDACTED-SERIAL` (PARITY §5.1 evidence). */
        const val SEARCH_QUERY = "spy x family"

        /**
         * Upper bound for the reported cause chain. Real Media3 chains are 3-5
         * deep; the cap exists only so a pathological graph cannot hang the
         * failure reporter, and hitting it is itself reported rather than silent.
         */
        const val MAX_CAUSE_DEPTH = 16
    }
}
