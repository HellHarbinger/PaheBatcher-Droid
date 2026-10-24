package com.pahebatcher.app.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.playback.PlaybackPlaylist
import com.pahebatcher.app.data.playback.PlaybackPositionRepository
import com.pahebatcher.app.data.playback.PlaybackSourceResolver
import com.pahebatcher.app.data.stream.HlsPlaybackSourceFactory
import com.pahebatcher.app.data.stream.StreamEpisodeReconstructor
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.domain.stream.StreamResolutionRetry
import com.pahebatcher.app.domain.util.StringUtils
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.playback.MediaSessionHost
import com.pahebatcher.app.ui.navigation.PlayerMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Immutable snapshot of everything the player screen renders (§23 controls,
 * loading, and error states — the composable never touches ExoPlayer state
 * directly except to hand the VM-owned instance to the Media3 surface).
 */
@OptIn(markerClass = [UnstableApi::class])
@Immutable
data class PlayerUiState(
    val player: ExoPlayer? = null,
    /** True only while a genuine `prepare()` is in flight (§23 — no fake buffering). */
    val loading: Boolean = true,
    /** Playlist/seed load failure — the full-screen retry path. */
    val loadError: String? = null,
    /** In-player `PlaybackException` — the error-overlay retry path (§23). */
    val playerError: Boolean = false,
    val isPlaying: Boolean = false,
    /** Current episode title ("\<title\> · Episode \<n\>") — always-visible overlay. */
    val title: String? = null,

    /**
     * SPEC §15 — which playback path this entry is on. The screen needs it to
     * choose between Media3's own playlist skip controls (DOWNLOAD: the sibling
     * `COMPLETED` rows already are one ExoPlayer playlist, §24.2) and the STREAM
     * sibling navigation this ViewModel drives itself.
     */
    val mode: PlayerMode = PlayerMode.DOWNLOAD,

    /**
     * SPEC §15 "episode navigation / next episode / previous episode" for a
     * STREAM entry: the ordered ids of this anime session's episodes in the SAME
     * audio variant, as computed by [streamSiblingIds] from the episode list.
     *
     * Empty until the list has really been read — an unreadable list yields NO
     * skip controls rather than a control that pretends a next episode exists.
     */
    val siblingEpisodeIds: List<String> = emptyList(),

    /**
     * Index of the episode currently playing inside [siblingEpisodeIds],
     * `-1` while the list is unknown. Bounds are read from THIS value, so the
     * navigation can never wrap around or point at an episode not on the list.
     */
    val siblingIndex: Int = -1,
    /**
     * Ticking playback position/duration (ms) feeding the §23 readout + seek
     * slider via [startPositionTicker] (real ExoPlayer reads every tick). The
     * UI reads ONLY these values: Media3's own ProgressStateJob-driven
     * TimeText/slider state proved inert in this app — both consumers froze at
     * init-time values while playback advanced (instrumented) —
     * so the authoritative clock lives on the viewModelScope path whose
     * delivery is verifiable on this screen.
     */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,

    /**
     * SPEC §15 "subtitles where available" — every text track the current media
     * publishes, in manifest order. EMPTY means this media has no subtitles, and
     * that is what hides the subtitle control entirely (AGENTS §5 — no control
     * that pretends to do something).
     */
    val subtitleOptions: List<SubtitleOption> = emptyList(),

    /**
     * Index into [subtitleOptions] of the text track the player is actually
     * rendering, `-1` when subtitles are off (or none exist). Read back from
     * `Player.currentTracks`, never assumed.
     */
    val selectedSubtitleIndex: Int = -1,
)

/**
 * Signals from the player screen's lifecycle observer into the ViewModel
 * (PHASE11_ARCHITECTURE_V2.md §24.3 pause semantics, §24.4 rotation survival).
 *
 * The [owner] token is the nav-entry LifecycleOwner identity. Implementations
 * must be order-independent: an attach can race the new entry's ON_RESUME after
 * a configuration change.
 */
interface PlayerLifecycle {
    /** An observed owner reached at least RESUMED (attach or ON_RESUME catch-up). */
    fun onAttached(owner: Any)

    /** The same observed owner resumed — any pending identity-pause is discarded. */
    fun onResumed(owner: Any)

    /** An observed owner's lifecycle dropped below RESUMED → pause + persist (§24.3). */
    fun onPausedBelowResumed(owner: Any)

    /**
     * An observed owner was destroyed (or its composition disposed). With
     * [destroyedForConfigChange] true (rotation) the pending "was playing" state
     * survives so the next attach may resume (§24.4); otherwise the pending state
     * is cleared so a later (re)creation must NOT auto-resume (§24.3).
     */
    fun onDetached(owner: Any, destroyedForConfigChange: Boolean)
}

/**
 * Playback state holder for ONE nav entry (§23/§24.1).
 *
 * - Exactly one ExoPlayer exists per ViewModel: created lazily on first load,
 *   released in [onCleared] (paired creation/release, §24.1).
 * - The route argument episodeId is the entry SEED only: the playlist lives in
 *   this VM and next/previous switch MediaItems in place — never stacked
 *   player routes (§24.2).
 * - Positions persist to Room on pause/stop/interval/seek/end/exit and are
 *   restored before the first play (§24.5, §29 indefinite retention).
 * - Rotation (configuration change) does NOT clear this ViewModel: the pending
 *   pause survives the detach and the next attach resumes playback only if it
 *   was playing (§24.4). Background/cover returns never auto-resume (§24.3).
 * - Phase 14 (PHASE14_PLAN §11): wraps the VM-owned ExoPlayer in a MediaSession
 *   via MediaSessionHost for system transport controls / media notification.
 */
@OptIn(markerClass = [UnstableApi::class])
class PlayerViewModel(
    private val playerFactory: () -> ExoPlayer,
    private val downloadRepository: DownloadRepository,
    private val positionRepository: PlaybackPositionRepository,
    private val mediaSessionHost: MediaSessionHost,
    /**
     * Direct HLS streaming collaborators. All four are nullable with
     * inert defaults so every pre-existing construction (and every DOWNLOAD-mode
     * test) behaves exactly as before; production wires them through
     * `AppViewModelFactory`.
     *
     * * [streamResolver] — the foreground WebView/Kwik resolver. The player holds
     *   it only long enough to turn a reconstructed `playUrl` into a [StreamInfo]
     *   and never retains any WebView/session object (§3.2 — `StreamInfo` is the
     *   boundary).
     * * [hlsSourceFactory] — builds the OkHttp-backed `HlsMediaSource` per stream.
     * * [streamReconstructor] — the Room → scan-cache → network ladder that
     *   recovers `playUrl` from the id alone (§12).
     * * [configProvider] — the persisted `quality` the stream resolves with.
     */
    private val streamResolver: StreamResolver? = null,
    private val hlsSourceFactory: HlsPlaybackSourceFactory? = null,
    private val streamReconstructor: StreamEpisodeReconstructor? = null,
    private val configProvider: suspend () -> AppContext = { AppContext.defaults() },
    /**
     * SPEC §15 (stream episode navigation) — the ordered episode list of a
     * STREAM entry's anime session.
     *
     * Production wires this to the cache-first `EpisodeRepository.scan`, i.e. the
     * same read the details screen and the reconstruction ladder already use, so
     * the usual answer comes from the scan cache and never reaches the network.
     * The inert default returns an empty list: without it navigation is simply
     * unavailable — never faked (AGENTS §5).
     */
    private val streamEpisodeLister: suspend (animeSession: String) -> List<EpisodeInfo> = { emptyList() },
) : ViewModel(), PlayerLifecycle {

    /**
     * UI-001: an app-scoped teardown scope for the FINAL position save.
     *
     * [viewModelScope] is already cancelled when [onCleared] runs, so the last
     * write must not use it. This scope is owned by the ViewModel and cancelled
     * once that single write completes — a Room DAO write cannot suspend forever,
     * so it cannot leak a collector or a coroutine. The main thread is never
     * blocked (the old `runBlocking { persistCurrentPosition() }` parked it for
     * the duration of the write).
     */
    private val TAG = "PlayerViewModel"

    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = mutableStateOf(PlayerUiState())
    val uiState: State<PlayerUiState> = _uiState

    private var player: ExoPlayer? = null
    private var playlist: List<EpisodeDownloadEntity> = emptyList()
    private var savedPositions: Map<String, Long> = emptyMap()

    /** Seed guard: load() is idempotent per route id; a different id reseeds (§24.2). */
    private var seededRouteEpisodeId: String? = null

    /** The route's playback path — the second half of the seed guard. */
    private var seededRouteMode: PlayerMode = PlayerMode.DOWNLOAD

    /**
     * SPEC §15: the episode a STREAM entry is actually on — the route seed, or
     * the sibling it last switched to in place (§24.2: never a new route).
     *
     * The retry target and the sibling index both follow THIS value instead of
     * the seed, so a Retry after navigating retries the episode on screen rather
     * than jumping back to the entry's seed.
     */
    private var currentStreamEpisodeId: String? = null

    /**
     * SPEC §15: the STREAM episode whose load is in flight — the supersession
     * token every suspend point in [loadStreamPlaylist] is checked against. A
     * navigation (or a retry) overwrites it, which is what stops a slow resolve
     * of the episode being left behind from landing on top of the newer one.
     */
    private var streamLoadEpisodeId: String? = null

    /**
     * True only while the NEXT stream load is a user-driven retry, so
     * a fresh entry clears the retry budget/retained position while a retry keeps
     * them (§15/§16).
     */
    private var streamRetryPending: Boolean = false

    /** Bounded, user-driven stream retry budget + retained position (§16). */
    private val streamRetry = StreamRetryState()

    /** The last STREAM failure message, used to classify the next retry. */
    private var lastStreamError: String? = null

    private var persistenceStarted = false
    private var tickerStarted = false

    // --- Pause state machine (§24.3/§24.4, order-independent) ---

    /** Owner most recently observed below RESUMED (identity-pause token). */
    private var pausedOwner: Any? = null

    /** Whether playback was running when the pause began (rotation resume gate). */
    private var pausedWhilePlaying: Boolean = false

    /** A configuration-change detach is pending resolution by the next attach. */
    private var detachedForConfigChange: Boolean = false

    /**
     * SPEC §15 — the viewer's subtitle choice for the WHOLE entry.
     *
     * It lives here rather than in [PlayerUiState] because a new source replaces
     * that state wholesale: when the next episode's tracks arrive, the choice is
     * re-applied from this field. `Unset` means "the player decides" — the
     * default is never fought — and `Off` means the viewer asked for none.
     */
    private var subtitleChoice: SubtitleChoice = SubtitleChoice.Unset

    /** What the viewer asked the subtitle control to do; see [subtitleChoice]. */
    private sealed interface SubtitleChoice {
        /** Nothing asked yet — whatever Media3 selects on its own stands. */
        data object Unset : SubtitleChoice

        /** The viewer explicitly turned subtitles off for this entry. */
        data object Off : SubtitleChoice

        /** The viewer picked the option with this (deduplicated) label. */
        data class On(val label: String) : SubtitleChoice
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                restorePositionAfterTransition(mediaItem)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            when (reason) {
                Player.DISCONTINUITY_REASON_SEEK -> {
                    val oldId = oldPosition.mediaItem?.mediaId
                    val newId = newPosition.mediaItem?.mediaId
                    // Next/previous across items: keep the position of the item
                    // being left (mid-episode switches must not lose progress).
                    if (oldId != null && oldId != newId && oldPosition.positionMs > 0) {
                        savePositionAsync(oldId, oldPosition.positionMs)
                    }
                    if (newId != null && newPosition.positionMs > 0) {
                        savePositionAsync(newId, newPosition.positionMs)
                    }
                }
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    // Natural end of an item → replay semantics: position resets
                    // to 0 for the finished episode (no duration exists on
                    // PositionInfo, verified via javap).
                    val oldId = oldPosition.mediaItem?.mediaId ?: return
                    savePositionAsync(oldId, 0L)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Clear the genuine-prepare spinner only once playback can render
            // (BUFFERING/IDLE keep it; §23 forbids fake buffering for local data).
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                if (_uiState.value.loading) {
                    _uiState.value = _uiState.value.copy(loading = false)
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                val id = player?.currentMediaItem?.mediaId ?: return
                savePositionAsync(id, 0L)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            // a stream failure must not lose where the viewer was
            // — the position is captured here so the user-driven Retry re-enters
            // the new HLS source at the same place. Never logged: Media3 messages
            // can carry the failing media URL (AGENTS §10).
            if (seededRouteMode == PlayerMode.STREAM) {
                streamRetry.retainPosition(player?.currentPosition ?: 0L)
                lastStreamError = null
            }
            _uiState.value = _uiState.value.copy(playerError = true, loading = false)
        }

        override fun onTracksChanged(tracks: Tracks) {
            // SPEC §15 "subtitles where available": Media3 reports the text
            // tracks of the CURRENT source here — the only source of truth for
            // the subtitle menu, and the moment a recorded choice has to be
            // re-applied to a new episode.
            refreshSubtitles(tracks)
        }
    }

    // --- SPEC §15 "subtitles where available" (Media3 text tracks) ----------

    /**
     * SPEC §15 — choose a subtitle track by index into
     * [PlayerUiState.subtitleOptions], or `-1` for Off.
     *
     * The choice is recorded for the whole entry and applied immediately; a
     * later source change re-applies it from [subtitleChoice] when that source's
     * tracks arrive. An out-of-range index is treated as Off rather than being
     * ignored, so a stale tap can never leave the menu and the player disagreeing.
     *
     * Main thread only (it reads and writes ExoPlayer track parameters) — which
     * is exactly how the control calls it.
     */
    fun selectSubtitle(optionIndex: Int) {
        val options = _uiState.value.subtitleOptions
        subtitleChoice = if (optionIndex in options.indices) {
            SubtitleChoice.On(options[optionIndex].label)
        } else {
            SubtitleChoice.Off
        }
        player?.let { refreshSubtitles(it.currentTracks) }
    }

    /**
     * Re-derive the subtitle section from what the player reports, then make the
     * player agree with [subtitleChoice].
     *
     * `Unset` never mutates the player: untouched media keeps whatever Media3
     * selected by itself, so playback is not forced either way. A recorded choice
     * that diverges from the observed selection is applied once, and the resulting
     * `onTracksChanged` then finds agreement and stops — a selection can never
     * bounce between two states.
     */
    private fun refreshSubtitles(tracks: Tracks) {
        val derived = deriveSubtitleState(subtitleTracksOf(tracks))
        val desired = when (val choice = subtitleChoice) {
            SubtitleChoice.Unset -> derived.selectedIndex
            SubtitleChoice.Off -> -1
            // A recorded choice that this source does not publish resolves to
            // "off" rather than to an arbitrary track.
            is SubtitleChoice.On -> derived.options.indexOfFirst { it.label == choice.label }
        }
        val exo = player
        if (exo != null && desired != derived.selectedIndex) {
            applySubtitleSelection(exo, desired, derived)
        }
        _uiState.value = _uiState.value.copy(
            subtitleOptions = derived.options,
            selectedSubtitleIndex = if (desired in -1 until derived.options.size) desired else -1,
        )
    }

    /**
     * Make the player render — or stop rendering — the chosen text track.
     *
     * Off disables the text track type, which is Media3's own switch for "no
     * subtitles"; a selection re-enables it and pins the exact group + track with
     * a `TrackSelectionOverride` built from the player's CURRENT tracks, so an
     * override can never reference a group belonging to another media item.
     */
    private fun applySubtitleSelection(
        exo: ExoPlayer,
        desired: Int,
        derived: PlayerSubtitleState,
    ) {
        if (desired < 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            return
        }
        val option = derived.options.getOrNull(desired) ?: return
        val group = exo.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    /**
     * Loads the playlist for the route seed and starts playback preparation.
     * Idempotent per route id (re-entering the same player entry never restarts
     * playback — §24.2); a different id (in-place route change under
     * launchSingleTop) persists the outgoing position and reseeds.
     *
     * `mode` selects the branch — [PlayerMode.DOWNLOAD] keeps the
     * pre-existing local-playlist behaviour byte-for-byte, [PlayerMode.STREAM]
     * runs the reconstruction → JIT resolution → `HlsMediaSource` path. The guard
     * now includes the mode so a STREAM entry can never inherit a DOWNLOAD seed.
     */
    fun load(routeEpisodeId: String, mode: PlayerMode = PlayerMode.DOWNLOAD) {
        if (seededRouteEpisodeId == routeEpisodeId && seededRouteMode == mode) return
        seededRouteEpisodeId = routeEpisodeId
        seededRouteMode = mode
        when (mode) {
            PlayerMode.DOWNLOAD -> loadLocalPlaylist(routeEpisodeId)
            PlayerMode.STREAM -> loadStreamPlaylist(routeEpisodeId)
        }
    }

    /**
     * The unchanged DOWNLOAD branch: Room is the source of truth, the playlist is
     * the sibling `COMPLETED` rows and every `MediaItem` comes from
     * [PlaybackSourceResolver]. Phase 17 only extracted this body verbatim from
     * [load] — no behaviour changed.
     */
    private fun loadLocalPlaylist(routeEpisodeId: String) {
        viewModelScope.launch {
            try {
                // Persist the outgoing episode's position before swapping (§24.2).
                persistCurrentPosition()

                val seed = downloadRepository.getEpisode(routeEpisodeId)
                if (seed == null) {
                    _uiState.value = PlayerUiState(
                        player = player,
                        loading = false,
                        loadError = "This episode is no longer available.",
                    )
                    return@launch
                }
                if (seededRouteEpisodeId != routeEpisodeId) return@launch // superseded

                // Local playlist, narrowed to the SEED's own audio (SPEC §15 /
                // audio-variant slice): completed rows of ANY other audio are
                // excluded, so next/previous walks one track only and a
                // mixed-audio download history can never insert a different
                // recording of the same number.
                val rows = PlaybackPlaylist.completedPlayable(
                    downloadRepository.getCompletedPlaylist(seed.sessionId),
                    seed.audio,
                )
                if (seededRouteEpisodeId != routeEpisodeId) return@launch // superseded
                playlist = rows

                val items = rows.mapNotNull { row ->
                    val source = PlaybackSourceResolver.sourceFor(row) ?: return@mapNotNull null
                    MediaItem.Builder()
                        .setMediaId(row.episodeId)
                        .setUri(source)
                        .build()
                }
                val startIndex = items.indexOfFirst { it.mediaId == routeEpisodeId }
                if (startIndex < 0) {
                    _uiState.value = PlayerUiState(
                        player = player,
                        loading = false,
                        loadError = "This episode has no playable file.",
                    )
                    return@launch
                }

                savedPositions = rows.associate { row ->
                    row.episodeId to (positionRepository.getPosition(row.episodeId) ?: 0L)
                }
                val startMs = savedPositions[routeEpisodeId] ?: 0L
                val startRow = rows.first { it.episodeId == routeEpisodeId }

                val exo = ensurePlayer()
                _uiState.value = PlayerUiState(
                    player = exo,
                    loading = true,
                    title = titleFor(startRow),
                )
                exo.setMediaItems(items, startIndex, startMs.coerceAtLeast(0L))
                exo.prepare()
                exo.playWhenReady = true // restore position before first play (§24.5)
                startPeriodicPersistence()
                startPositionTicker()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    player = player,
                    loading = false,
                    loadError = error.message ?: "Unable to load this episode.",
                )
            }
        }
    }

    /**
     * The STREAM branch, deliberately separate from
     * [loadLocalPlaylist] so the proven local path cannot regress.
     *
     * Order:
     *
     * 1. persist the outgoing position (§24.2, same as the local branch);
     * 2. [planPlayerLoad] — shape gate + the Room → scan-cache → network
     *    reconstruction ladder; a malformed id never reaches the network;
     * 3. [resolveStreamFor] — JUST-IN-TIME resolution with the episode's OWN
     *    audio and the configured quality (audio is never a global setting —
     *    SPEC §22);
     * 4. [HlsPlaybackSourceFactory.create] — OkHttp transport + headers, one
     *    `HlsMediaSource` for this stream only (`setMediaSource`, never a global
     *    media-source factory);
     * 5. restore the saved position for this SAME `mediaId` (§15) — `mediaId` is
     *    the episode id, so there is exactly one position key for both modes.
     *
     * No WorkManager, no `EpisodeDownloadEntity`, no FFmpeg, no download row is
     * created or required — only `PlaybackPositionEntity` is ever written (§3.1).
     */
    private fun loadStreamPlaylist(routeEpisodeId: String) {
        val isRetry = streamRetryPending
        streamRetryPending = false
        if (!isRetry) {
            // A fresh entry starts a fresh budget and forgets any position kept
            // for a previous entry's retry (§16).
            streamRetry.reset()
            streamRetry.clearRetainedPosition()
            lastStreamError = null
        }
        // SPEC §15: whichever way we got here (entry seed, user navigation or a
        // user retry) THIS is the episode the viewer is on now, and this load is
        // the one allowed to land — every later load supersedes it.
        currentStreamEpisodeId = routeEpisodeId
        streamLoadEpisodeId = routeEpisodeId
        viewModelScope.launch {
            try {
                persistCurrentPosition()
                if (streamLoadEpisodeId != routeEpisodeId) return@launch // superseded

                val plan = planPlayerLoad(PlayerMode.STREAM, routeEpisodeId, streamReconstructor)
                if (streamLoadEpisodeId != routeEpisodeId) return@launch // superseded
                if (plan is PlayerLoadPlan.Failed) {
                    showStreamError(plan.message)
                    return@launch
                }
                val episode = (plan as PlayerLoadPlan.Stream).episode

                val resolver = streamResolver
                val sourceFactory = hlsSourceFactory
                if (resolver == null || sourceFactory == null) {
                    // Honest refusal — never a fabricated stream (AGENTS §5).
                    showStreamError(STREAM_UNAVAILABLE)
                    return@launch
                }

                val info = resolveStreamFor(episode, resolver, configProvider)
                if (streamLoadEpisodeId != routeEpisodeId) return@launch // superseded

                val title = streamTitleFor(episode)
                val source = sourceFactory.create(info, mediaId = routeEpisodeId, title = title)

                // Restore the position recorded for this episode id, keeping any
                // position a failed attempt retained (§15 — a source swap never
                // resets the viewer to zero).
                val saved = positionRepository.getPosition(routeEpisodeId) ?: 0L
                val startMs = maxOf(saved, streamRetry.retainedPositionMs).coerceAtLeast(0L)

                val exo = ensurePlayer()
                // Carried over deliberately: the sibling list already read for
                // this session stays valid across a switch, and only the index
                // moves with the episode (§24.2 — one player, one list).
                val siblings = _uiState.value.siblingEpisodeIds
                _uiState.value = PlayerUiState(
                    mode = PlayerMode.STREAM,
                    player = exo,
                    loading = true,
                    title = title,
                    siblingEpisodeIds = siblings,
                    siblingIndex = siblings.indexOf(routeEpisodeId),
                )
                exo.setMediaSource(source, startMs)
                exo.prepare()
                exo.playWhenReady = true
                // §16: a successful re-resolution resets the retry budget.
                streamRetry.reset()
                lastStreamError = null
                startPeriodicPersistence()
                startPositionTicker()
                // SPEC §15: the sibling list is read only once the episode is
                // really playing — navigation must never delay playback.
                refreshStreamSiblings(routeEpisodeId, episode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                showStreamError(
                    error.message?.takeIf { it.isNotBlank() } ?: STREAM_RESOLVE_FAILED,
                )
            }
        }
    }

    /** Publish an honest STREAM failure (never the download-path copy). */
    private fun showStreamError(message: String) {
        lastStreamError = message
        // The error view replaces the video surface: whatever was still playing
        // underneath must stop while the viewer is asked to act on the failure
        // (a failed episode switch must not leave the previous episode's audio
        // running behind a full-screen error).
        player?.playWhenReady = false
        val prior = _uiState.value
        _uiState.value = PlayerUiState(
            mode = PlayerMode.STREAM,
            player = player,
            loading = false,
            loadError = message,
            title = prior.title,
            // Kept: the session's episode list is still valid, and the index still
            // points at the episode the viewer was on — a refused switch never
            // moves the position of the navigation.
            siblingEpisodeIds = prior.siblingEpisodeIds,
            siblingIndex = prior.siblingIndex,
        )
    }

    // ------------------------------------------------------------------
    // SPEC §15 — streaming episode navigation (next / previous episode)
    // ------------------------------------------------------------------

    /**
     * SPEC §15 "next episode" for a STREAM entry — see [playSibling].
     */
    fun playNextEpisode() = playSibling(+1)

    /**
     * SPEC §15 "previous episode" for a STREAM entry — see [playSibling].
     */
    fun playPreviousEpisode() = playSibling(-1)

    /**
     * SPEC §15 episode navigation: switch a STREAM entry to its neighbouring
     * episode IN PLACE — same ExoPlayer, same player route (§24.2, never a
     * stacked route, never a second player).
     *
     * The switch is not a shortcut around the stream path: it re-enters
     * [loadStreamPlaylist], so the target episode goes through the very same
     * reconstruction ladder, the same just-in-time resolve and the same bounded
     * retry budget as the entry's own seed. The outgoing episode's position is
     * persisted by that load before anything is swapped.
     *
     * Refuses (silently, without a fabricated action) when:
     *
     * * the entry is not streaming — DOWNLOAD mode keeps Media3's own playlist
     *   skip controls, whose sibling `COMPLETED` rows are one player playlist;
     * * a load is already in flight (`loading`) — one switch at a time, so a
     *   double tap cannot race two resolves;
     * * the requested index is off either end of [PlayerUiState.siblingEpisodeIds]
     *   — navigation is bounded and never wraps.
     */
    private fun playSibling(delta: Int) {
        if (seededRouteMode != PlayerMode.STREAM) return
        val state = _uiState.value
        if (state.loading) return
        val targetIndex = state.siblingIndex + delta
        val targetId = state.siblingEpisodeIds.getOrNull(targetIndex) ?: return

        // A navigation is a NEW episode, not a retry of this one: no retained
        // position from a failed attempt may leak into the next episode (§15/§16),
        // and the failure that would have shown for the outgoing episode no
        // longer describes what is on screen.
        streamRetry.reset()
        streamRetry.clearRetainedPosition()
        lastStreamError = null
        // Loading first: the spinner replaces the surface immediately, which is
        // both honest (a real prepare is about to start) and what makes the
        // `loading` gate above hold for the whole switch.
        _uiState.value = state.copy(
            mode = PlayerMode.STREAM,
            loading = true,
            playerError = false,
            loadError = null,
            siblingIndex = targetIndex,
        )
        loadStreamPlaylist(targetId)
    }

    /**
     * SPEC §15 — read the ordered sibling episode list of a STREAM entry,
     * fire-and-forget, so playback is never delayed by it.
     *
     * Failure (or an unwireable lister) simply leaves the list as it was: an
     * unreadable list means NO skip controls, never a control that claims a next
     * episode which we have not seen (AGENTS §5). The index is recomputed from
     * [currentStreamEpisodeId] when the answer lands, so a navigation that raced
     * this read still ends up pointing at the episode actually playing.
     */
    private fun refreshStreamSiblings(episodeId: String, episode: EpisodeInfo) {
        val animeSession = EpisodeId.parse(episodeId)?.animeSession ?: return
        // One read per entry. The list is scoped to the session that was opened
        // and is what bounds every later switch, so re-reading it after each
        // in-place switch would only add scans (AGENTS §10) while letting the
        // bounds move under the viewer's finger mid-navigation. An empty first
        // answer is still retried by the next load (a retry path, or a session
        // whose list only became readable later) — an empty list is also what
        // disables navigation, so no switch can have raced ahead of it.
        if (_uiState.value.siblingEpisodeIds.isNotEmpty()) return
        val audio = episode.audio
        viewModelScope.launch {
            val episodes = try {
                streamEpisodeLister(animeSession)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return@launch
            }
            val ids = streamSiblingIds(animeSession, episodes, audio)
            val current = currentStreamEpisodeId ?: return@launch
            _uiState.value = _uiState.value.copy(
                siblingEpisodeIds = ids,
                siblingIndex = ids.indexOf(current),
            )
        }
    }

    /**
     * The ONLY path that re-resolves a stream. Bounded,
     * user-driven (bound to the two existing retry controls), never automatic:
     * a permanent resolution failure is reported again instead of retried, an
     * exhausted budget reports [STREAM_RETRY_EXHAUSTED], and a playback error
     * carries the current position into the next attempt.
     */
    private fun retryStream(fromPlaybackError: Boolean) {
        // SPEC §15: retry the episode the viewer is actually on — after an
        // in-place switch that is the sibling the viewer navigated to, never a
        // jump back to the entry's route seed.
        val routeId = currentStreamEpisodeId ?: seededRouteEpisodeId ?: return
        val priorError = if (fromPlaybackError) {
            null // Media3 gave us a playback failure, not a resolution verdict.
        } else {
            lastStreamError ?: _uiState.value.loadError
        }
        if (priorError != null && streamRetry.isPermanentFailure(priorError)) {
            showStreamError(priorError)
            return
        }
        if (!streamRetry.attemptAllowed()) {
            showStreamError(STREAM_RETRY_EXHAUSTED)
            return
        }
        streamRetry.recordAttempt()
        if (fromPlaybackError) {
            streamRetry.retainPosition(player?.currentPosition ?: 0L)
        }
        streamRetryPending = true
        val prior = _uiState.value
        _uiState.value = prior.copy(
            mode = PlayerMode.STREAM,
            player = player,
            loading = true,
            playerError = false,
            loadError = null,
        )
        // Straight into the load, NOT through load(): the seed guard must keep
        // naming the route argument, so a rotation after a retry still never
        // restarts playback (§24.2/§24.4).
        loadStreamPlaylist(routeId)
    }

    /** Full reload after a playlist/seed load failure (clears the seed guard). */
    fun retryLoad() {
        val routeId = seededRouteEpisodeId ?: return
        val mode = seededRouteMode
        if (mode == PlayerMode.STREAM) {
            retryStream(fromPlaybackError = false)
            return
        }
        seededRouteEpisodeId = null
        _uiState.value = PlayerUiState(player = player, loading = true)
        load(routeId, mode)
    }

    /**
     * Re-prepare after an in-player playback error (§23 retry-on-error control).
     *
     * In STREAM mode the same control re-resolves and rebuilds the HLS
     * source (still user-driven and budgeted) instead of blind `prepare()` — the
     * resolved playlist URL is expiring, so re-preparing the dead source could
     * not work. DOWNLOAD mode is unchanged.
     */
    fun retryPlayback() {
        if (seededRouteMode == PlayerMode.STREAM) {
            retryStream(fromPlaybackError = true)
            return
        }
        val exo = player ?: return
        _uiState.value = _uiState.value.copy(playerError = false, loading = true)
        exo.prepare()
    }

    /**
     * Player title for a STREAM entry: the episode's own title
     * plus the audio pill the reference renders
     * (`stream.py:342`, `--force-media-title=… [{audio_tag}]`), derived from the
     * episode's ACTUAL audio track — never from a global preferred-audio
     * setting (which no longer exists, SPEC §22). The pill comes from
     * [StringUtils.audioBadge]: `jpn → [SUB]`, `eng → [DUB]`, and any other
     * source code shows its OWN uppercase tag (`kor → [KOR]`) instead of being
     * mislabelled SUB or DUB; a blank audio adds no pill at all. A blank
     * episode title falls back to the positional label instead of an invented
     * name.
     */
    private fun streamTitleFor(episode: EpisodeInfo): String {
        val label = episode.epStr
        val cleaned = TRAILING_SUB_DUB.replace(episode.title, "").trim()
        val body = if (cleaned.isBlank()) "Episode $label" else "$cleaned · Episode $label"
        val tag = StringUtils.audioBadge(episode.audio)
        return if (tag.isEmpty()) body else "$body [$tag]"
    }

    // ------------------------------------------------------------------
    // PlayerLifecycle state machine (§24.3/§24.4)
    // ------------------------------------------------------------------

    override fun onAttached(owner: Any) {
        // Configuration-change pending: resume only if playback was running
        // when the rotation pause began (§24.4 play/pause state survives).
        if (detachedForConfigChange) {
            val shouldResume = pausedWhilePlaying
            clearPending()
            if (shouldResume) player?.play()
        }
    }

    override fun onResumed(owner: Any) {
        // Rotation path: a config-change detach is still pending — the attach
        // (which may arrive after this ON_RESUME) resolves it. Never clear here.
        if (detachedForConfigChange) return
        // Same owner returned to visibility (background/cover): no auto-resume (§24.3).
        if (pausedOwner === owner) clearPending()
    }

    override fun onPausedBelowResumed(owner: Any) {
        val exo = player ?: return
        // ON_PAUSE then ON_STOP must not overwrite the original "was playing"
        // snapshot with the already-paused state; a pending config-change
        // detach must keep its snapshot even if a NEW owner pauses first.
        val firstPause = pausedOwner !== owner && !detachedForConfigChange
        pausedOwner = owner
        if (firstPause) pausedWhilePlaying = exo.playWhenReady && exo.isPlaying
        exo.pause() // §24.3: entry below RESUMED → pause (covers background/occlusion/covered)
        viewModelScope.launch { persistCurrentPosition() } // §24.3: + persist position
    }

    override fun onDetached(owner: Any, destroyedForConfigChange: Boolean) {
        if (destroyedForConfigChange) {
            // Rotation: keep pausedWhilePlaying so the next attach resumes (§24.4).
            detachedForConfigChange = true
        } else {
            // Route exit / background-kill-recreate: pending state discarded so a
            // later (re)creation never auto-plays (§24.3 no auto-resume).
            clearPending()
        }
    }

    private fun clearPending() {
        pausedOwner = null
        pausedWhilePlaying = false
        detachedForConfigChange = false
    }

    // ------------------------------------------------------------------
    // Player ownership + persistence internals
    // ------------------------------------------------------------------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val exo = playerFactory()
        exo.addListener(listener)
        player = exo
        livePlayerCount.incrementAndGet()
        // notify MediaSessionHost of player creation
        // with the current route's episodeId so the session activity can be built.
        // AQR-138: the playback MODE travels with the episode id — both are read
        // from the same route seed, so the media notification's target always
        // names the mode that episode is actually playing in.
        val currentEpisodeId = seededRouteEpisodeId
        if (currentEpisodeId != null) {
            mediaSessionHost.onPlayerCreated(exo, currentEpisodeId, seededRouteMode)
        }
        return exo
    }

    private fun restorePositionAfterTransition(mediaItem: MediaItem?) {
        val exo = player ?: return
        val id = mediaItem?.mediaId ?: return
        val saved = savedPositions[id] ?: return
        if (saved > 0 && exo.currentPosition < saved) {
            exo.seekTo(saved) // restore before continuing play (§24.5)
        }
    }

    private fun updateTitle() {
        val exo = player ?: return
        val id = exo.currentMediaItem?.mediaId ?: return
        val row = playlist.firstOrNull { it.episodeId == id } ?: return
        _uiState.value = _uiState.value.copy(title = titleFor(row))
    }

    private fun titleFor(row: EpisodeDownloadEntity): String {
        // A2.1 (UI-004): the ONE renderer decides the number's text.
        val label = StringUtils.episodeNumber(row.episodeNumber)
        return "${row.title} · Episode $label"
    }

    /**
     * AQR-042: a best-effort position write. Every call site updates the
     * in-memory [savedPositions] map BEFORE the Room write, so a failed write
     * degrades to a logged warning — never an uncaught exception reaching the
     * default handler and crashing the process. Neither [viewModelScope] nor
     * [teardownScope] installs a `CoroutineExceptionHandler`, and
     * `invokeOnCompletion` does not catch thrown exceptions, so a `SQLiteException`
     * from `dao.upsert` used to crash after route exit / during playback.
     * Cancellation still propagates (NET-001 contract); AppLog's throwable
     * sanitizer owns the rendering of [e] (AGENTS §10).
     */
    private suspend fun saveQuietly(mediaId: String, positionMs: Long, durationMs: Long) {
        try {
            positionRepository.save(mediaId, positionMs, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Position save failed (kept in memory only)", e)
        }
    }

    /**
     * Fire-and-forget save from listener callbacks (non-suspend context). The
     * in-memory [savedPositions] map updates synchronously so an in-session
     * next/previous restore sees the latest value; the Room write runs in the
     * VM scope (Room dispatches suspend DAO work off the main thread).
     */
    private fun savePositionAsync(mediaId: String, positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        savedPositions = savedPositions + (mediaId to position)
        val durationMs = player?.duration ?: 0L
        viewModelScope.launch {
            saveQuietly(mediaId, position, durationMs)
        }
    }

    /** Direct synchronous-position save for coroutine contexts (pause/loop/exit). */
    private suspend fun persistCurrentPosition() {
        val exo = player ?: return
        val id = exo.currentMediaItem?.mediaId ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        savedPositions = savedPositions + (id to position)
        saveQuietly(id, position, exo.duration)
    }

    private fun startPeriodicPersistence() {
        if (persistenceStarted) return
        persistenceStarted = true
        viewModelScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                val exo = player ?: continue
                if (exo.isPlaying) persistCurrentPosition()
            }
        }
    }

    /**
     * §23 readout ticker: refreshes [PlayerUiState.positionMs]/durationMs from
     * the VM-owned player. Replaces reliance on Media3's ProgressStateJob
     * consumers (TimeText/progress slider), which never delivered a single
     * post-init update in instrumentation while the player advanced — observed
     * across three runs, no exception, init-render + recreate-both-fine.
     */
    private fun startPositionTicker() {
        if (tickerStarted) return
        tickerStarted = true
        viewModelScope.launch {
            while (isActive) {
                delay(POSITION_UI_TICK_MS)
                val exo = player ?: break
                val duration = exo.duration
                _uiState.value = _uiState.value.copy(
                    positionMs = exo.currentPosition.coerceAtLeast(0L),
                    durationMs = if (duration > 0L) duration else _uiState.value.durationMs,
                )
            }
        }
    }

    override fun onCleared() {
        // UI-001: viewModelScope is already cancelled here, so the final save must
        // not use it. The ExoPlayer READS below stay on the main thread (ExoPlayer
        // is not thread-safe); only the Room WRITE moves to Dispatchers.IO in a
        // scope that outlives this ViewModel — so route-exit no longer blocks the
        // main thread on a database write (the old `runBlocking` could stall it for
        // the duration of the write). The values and their order are unchanged.
        val finalSave = player?.let { exo ->
            val id = exo.currentMediaItem?.mediaId ?: return@let null
            Triple(id, exo.currentPosition.coerceAtLeast(0L), exo.duration)
        }
        if (finalSave != null) {
            val (id, position, duration) = finalSave
            savedPositions = savedPositions + (id to position)
            // The scope is cancelled only once this write's job COMPLETES, so the
            // write always runs to finish; the scope then holds nothing and is
            // garbage after [onCleared] returns. saveQuietly keeps a Room failure
            // out of the default uncaught handler (AQR-042).
            teardownScope
                .launch { saveQuietly(id, position, duration) }
                .invokeOnCompletion { teardownScope.cancel() }
        }
        // MediaSession release MUST
        // happen before ExoPlayer release — the session wraps the player and must
        // clean up its reference first. (No `try/finally` wrapper is needed: the
        // final save above cannot throw, since the Room write runs inside
        // `teardownScope` and is best-effort [saveQuietly] rather than on this
        // call stack.)
        mediaSessionHost.onPlayerReleased()
        player?.let { exo ->
            exo.removeListener(listener)
            exo.release()
            releasedPlayerCount.incrementAndGet()
            // Gauge: created − released. ensurePlayer() took one; releasing here
            // gives it back, so live returns to the pre-entry value and §36-6 can
            // observe "the ExoPlayer was released exactly once" per entry.
            livePlayerCount.decrementAndGet()
            player = null
        }
        // (UI-001: no teardownScope.cancel() here — cancelling immediately after
        // launch would cancel the just-dispatched write before it ran. The scope is
        // cancelled by invokeOnCompletion on the write's job above, so nothing
        // outlives this ViewModel.)
        super.onCleared()
    }

    companion object {
        /** Periodic position persistence cadence while playing (§29 save-on-interval). */
        const val POSITION_SAVE_INTERVAL_MS = 10_000L

        /** Readout/slider refresh cadence (§23 position label + seek slider). */
        const val POSITION_UI_TICK_MS = 500L

        /**
         * Instrumentation-visible creation/release accounting (§24.1/§36-6 release hook).
         * [livePlayerCount] is a live gauge (created minus released): `ensurePlayer()`
         * takes one, each release in `onCleared()` gives one back, so it returns to the
         * pre-entry value once the entry's ExoPlayer is released. [releasedPlayerCount]
         * counts releases — one per cleared player entry that owned a player.
         */
        val livePlayerCount = AtomicInteger(0)
        val releasedPlayerCount = AtomicInteger(0)
    }
}

// ---------------------------------------------------------------------------
// pure stream-entry collaborators.
//
// These are deliberately TOP-LEVEL and free of Android/Media3 types so the
// mode decision, the reconstruction ladder and the retry budget are verifiable
// on the JVM (AGENTS §8) exactly as they run in the player.
// ---------------------------------------------------------------------------

/**
 * SPEC §15 (episode navigation / next / previous) — the ordered sibling ids a
 * STREAM entry navigates.
 *
 * Rules, each of which is a deliberate choice:
 *
 * * **one audio variant only** — a SUB viewer moves through the SUB episodes;
 *   mixing the DUB track of the same number in would make "next" jump to a
 *   different recording of a different list;
 * * **ordered by episode number** — numeric order, not the order the scan
 *   happened to return and not a string sort (`10` must follow `2`, and `13.5`
 *   sits between `13` and `14`);
 * * **built through [EpisodeId.build]** — so every id handed back is exactly the
 *   id the reconstruction ladder and the position repository already understand;
 * * **distinct** — a scan that reported the same episode twice must not offer
 *   the same "next" twice.
 *
 * Returns an empty list for an empty/mismatched input: no navigation is the
 * honest answer when we cannot see the episode list (AGENTS §5).
 */
fun streamSiblingIds(
    animeSession: String,
    episodes: List<EpisodeInfo>,
    audio: String,
): List<String> = episodes.asSequence()
    .filter { it.audio == audio }
    .sortedBy { it.number }
    .map { EpisodeId.build(animeSession, it) }
    .distinct()
    .toList()

/**
 * Honest copy for a STREAM entry whose `playUrl` could not be
 * recovered (or whose route id failed the shape gate).
 *
 * Never the download path's "This episode is no longer available.": the episode
 * may well exist — it is the stream SESSION that could not be rebuilt. The
 * action the user must take (reopen the episode list) is stated explicitly.
 */
const val STREAM_SESSION_EXPIRED =
    "This stream session has expired. Reopen the episode list and try again."

/** The bounded user-driven stream retry budget is spent. */
const val STREAM_RETRY_EXHAUSTED =
    "Streaming failed several times. Reopen the episode list and try again."

/** The stream collaborators were not wired into this player instance. */
const val STREAM_UNAVAILABLE = "Streaming is not available right now."

/** A resolution attempt failed with an unclassifiable, honest message. */
const val STREAM_RESOLVE_FAILED = "Couldn't resolve this episode's stream."

/** Persisted-quality fallback when settings cannot be read (Python default). */
const val RESOLUTION_QUALITY = 1080

/** Python `_display_title`/mpv title: a trailing `SUB`/`DUB` is shown as the pill. */
private val TRAILING_SUB_DUB = Regex("""\s+(?:DUB|SUB)\s*$""", RegexOption.IGNORE_CASE)

/**
 * What the player entry decided to do for its route seed.
 *
 * `DOWNLOAD` is the pre-existing behaviour (Room playlist of local files);
 * `STREAM` carries the fully reconstructed episode the player will resolve;
 * `Failed` is an honest refusal that must NOT touch the network.
 */
sealed interface PlayerLoadPlan {
    /** Unchanged local playback: seed the playlist from the completed rows. */
    data class Local(val episodeId: String) : PlayerLoadPlan

    /** STREAM: identity + `playUrl` recovered by [StreamEpisodeReconstructor]. */
    data class Stream(val episode: EpisodeInfo) : PlayerLoadPlan

    /** STREAM: nothing recoverable (malformed id or exhausted ladder). */
    data class Failed(val message: String) : PlayerLoadPlan
}

/**
 * The single branch decision of `PlayerViewModel.load`.
 *
 * * non-STREAM modes are routed to the unchanged local path and the
 *   reconstruction ladder is **never invoked** — not even its cache step, so a
 *   DOWNLOAD entry with no completed row can never start a STREAM network scan;
 * * STREAM first applies the [EpisodeId] shape gate: a malformed id fails
 *   locally with [STREAM_SESSION_EXPIRED] and no source function runs;
 * * only then does the ladder run (Room → scan cache → network scan).
 */
suspend fun planPlayerLoad(
    mode: PlayerMode,
    episodeId: String,
    reconstructor: StreamEpisodeReconstructor?,
): PlayerLoadPlan {
    if (mode != PlayerMode.STREAM) return PlayerLoadPlan.Local(episodeId)
    val parts = EpisodeId.parse(episodeId)
        ?: return PlayerLoadPlan.Failed(STREAM_SESSION_EXPIRED)
    val ladder = reconstructor ?: return PlayerLoadPlan.Failed(STREAM_SESSION_EXPIRED)
    return when (val recovered = ladder.reconstruct(episodeId, parts)) {
        is StreamEpisodeReconstructor.Result.Recovered ->
            PlayerLoadPlan.Stream(recovered.episode)
        is StreamEpisodeReconstructor.Result.Unrecoverable ->
            PlayerLoadPlan.Failed(STREAM_SESSION_EXPIRED)
    }
}

/**
 * The JUST-IN-TIME resolution inputs.
 *
 * * `playUrl` — the episode's own play page, recovered by the ladder;
 * * `quality` — the persisted configured quality (Python `ctx.quality`);
 * * `audio` — **the episode's own audio** (`episode.audio`), which is what the
 *   user selected on the episode row. No global preferred-audio setting exists
 *   anymore (the former `configuredAudioLang()` / Python's `ctx.audio_lang`
 *   was removed with the global audio preference, SPEC §22/§23) — streaming
 *   the exact variant the user picked is the whole point of the entry.
 */
suspend fun resolveStreamFor(
    episode: EpisodeInfo,
    resolver: StreamResolver,
    configProvider: suspend () -> AppContext,
): StreamInfo {
    val quality = try {
        configProvider().quality
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        RESOLUTION_QUALITY
    }
    return resolver.resolve(
        playUrl = episode.playUrl,
        quality = quality,
        audio = episode.audio,
    )
}

/**
 * The bounded, USER-DRIVEN stream retry budget.
 *
 * The reference budget (`StreamResolutionRetry`) is an automatic backoff loop
 * used by batch/watchlist polling; streaming must not loop on its own, so this
 * policy only ever advances when the user taps one of the two existing retry
 * controls (`Retry` after a load failure, or the in-player error `Retry`):
 *
 * * at most [DEFAULT_MAX_ATTEMPTS] attempts per stream session — calling
 *   [attemptAllowed] never advances the counter, so nothing retries by itself;
 * * a resolution error carrying a PERMANENT marker (`StreamResolutionRetry`)
 *   stops immediately and is reported as-is instead of being retried;
 * * a successful re-resolution ([reset]) restores the full budget;
 * * [retainPosition] keeps the viewer's position across a source swap, so a
 *   retry never restarts at zero.
 *
 * Holds no Android or Media3 type: unit-testable on the JVM.
 */
class StreamRetryState(private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS) {

    /** Attempts consumed by user actions so far. */
    var attempts: Int = 0
        private set

    /** Position captured for the next attempt (0 when nothing was retained). */
    var retainedPositionMs: Long = 0L
        private set

    /** Whether another user-driven attempt fits inside the budget. */
    fun attemptAllowed(): Boolean = attempts < maxAttempts

    /** Consume one attempt (capped at the budget — never exceeds it). */
    fun recordAttempt() {
        if (attempts < maxAttempts) attempts++
    }

    /** A successful re-resolution restores the budget (§16). */
    fun reset() {
        attempts = 0
    }

    /** Keep where the viewer was so a swapped source re-enters at that point. */
    fun retainPosition(positionMs: Long) {
        retainedPositionMs = positionMs.coerceAtLeast(0L)
    }

    /** A fresh (non-retry) stream entry forgets any previously retained point. */
    fun clearRetainedPosition() {
        retainedPositionMs = 0L
    }

    /** True when [message] is a resolution failure the reference never retries. */
    fun isPermanentFailure(message: String?): Boolean =
        !message.isNullOrBlank() &&
            (message.contains(StreamResolutionRetry.PERMANENT_ERROR) ||
                message.contains(StreamResolutionRetry.PERMANENT_ERROR_NO_UUID))

    /** The one gate [PlayerViewModel] consults before spending an attempt. */
    fun retryAllowed(message: String?): Boolean =
        !isPermanentFailure(message) && attemptAllowed()

    companion object {
        /** Same budget shape as the reference (`auto_retry` default 2 + 1). */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
