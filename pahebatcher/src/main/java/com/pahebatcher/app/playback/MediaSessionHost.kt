package com.pahebatcher.app.playback

import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.ui.navigation.PlayerMode

/**
 * App-scoped MediaSession host.
 *
 * Ownership triangle:
 * - PlayerViewModel owns the ExoPlayer (unchanged).
 * - MediaSessionHost holds a reference to the player + episodeId + playback mode
 *   + MediaSession.
 * - PaheMediaSessionService is started/stopped by this host and serves the session.
 *
 * Lifecycle:
 * - PlayerViewModel.ensurePlayer() → onPlayerCreated(player, episodeId, mode)
 *   → starts PaheMediaSessionService for the episode recorded on the host.
 * - PlayerViewModel.onCleared() → onPlayerReleased() → session.release() + stopService()
 *   (session release MUST happen before player release — see PlayerViewModel.onCleared).
 *
 * The service is started explicitly (deterministic, independent of media3 controller
 * binding — we use no MediaController). On the session path, foreground promotion belongs
 * entirely to media3:
 * MediaSessionService becomes a foreground service itself when playback is user-engaged
 * (it re-issues its own start-self intent and posts the ONE media notification), and a
 * plain started service is the supported state until then — see [startService] for the
 * PB-004 contract analysis.
 */
class MediaSessionHost(
    private val context: Context,
) {
    private val TAG = "MediaSessionHost"

    init {
        MediaSessionHostHolder.host = this
    }

    @Volatile var player: ExoPlayer? = null
    @Volatile var episodeId: String? = null

    /**
     * The playback mode the recorded episode is playing in (AQR-138).
     *
     * Written together with [episodeId] in [onPlayerCreated] and cleared with it
     * in [onPlayerReleased], so the two are one playback context: the media
     * notification's session activity names the episode AND the mode it is
     * playing in, and a STREAM session can no longer be re-opened as a download.
     */
    @Volatile var mode: PlayerMode? = null
        private set

    @Volatile internal var session: MediaSession? = null

    /**
     * Called when PlayerViewModel creates the ExoPlayer.
     * Starts PaheMediaSessionService for the episode recorded on the host
     * ([episodeId]; the start intent itself carries no extras — see [startService]).
     *
     * [mode] is the playback path that episode is playing on (AQR-138); the
     * service encodes it into the session activity target.
     *
     * PB-003: the service start is invoked SYNCHRONOUSLY on the caller's
     * (main) thread, like the session release below. The previous `scope.launch`
     * ran on `Dispatchers.Default`, so a stop issued right after a start (episode
     * re-entry: release → create) could be executed FIRST on another thread and
     * kill the freshly started service. Running both on the caller's thread makes
     * start/stop strictly ordered — both are cheap `Context` calls.
     */
    fun onPlayerCreated(player: ExoPlayer, episodeId: String, mode: PlayerMode) {
        this.player = player
        this.episodeId = episodeId
        this.mode = mode
        startService(episodeId)
    }

    /**
     * Called when PlayerViewModel releases the ExoPlayer (in onCleared, BEFORE
     * exo.release()). Releases the MediaSession and stops the service.
     *
     * F-01 corrective (PHASE14_PLAN §11): the release must be ORDERED, not just
     * requested. [MediaSession.release] is a synchronous, cheap media3 call (the
     * documented media3 pattern invokes it on the main thread from onDestroy), so
     * it runs HERE on the caller's thread: the session is guaranteed released
     * before this function returns, hence before PlayerViewModel.onCleared()
     * proceeds to `exo.release()`. [stopService] is likewise invoked synchronously
     * (PB-003) so it can never overtake a subsequent [onPlayerCreated].
     */
    fun onPlayerReleased() {
        session?.run {
            AppLog.d(TAG, "Releasing MediaSession for ${episodeId ?: "unknown"}")
            release()
        }
        session = null
        player = null
        episodeId = null
        mode = null
        stopService()
    }

    /**
     * PB-004 (fixed 2026-10-20): a PLAIN [Context.startService] start.
     *
     * The previous `startForegroundService()` created the platform foreground-
     * service contract at PLAYER-CREATION time, but the contract's owner is
     * media3 — and media3 1.11.1 promotes the service only when playback is
     * user-engaged: `MediaNotificationManager.shouldRunInForeground` is true
     * only for `playWhenReady && (READY || BUFFERING)`, and for an empty
     * timeline `shouldShowNotification` returns false and the notification is
     * REMOVED (never posted). So any idle/empty player — the state every
     * player passes through between `ensurePlayer()` and the first real frame
     * — left the contract permanently unsatisfied and Android killed the app
     * with `ForegroundServiceDidNotStartInTimeException` (live-reproduced on
     * device REDACTED-SERIAL; the reported origin was this method's start call).
     *
     * media3's documented model is the reverse ownership: the service is
     * STARTED here (deterministic session setup, independent of controller
     * binding), and media3 itself performs the foreground promotion when
     * playback starts — `MediaNotificationManager.startForeground()` issues
     * its own `startForegroundService` self-intent plus
     * `Util.setForegroundServiceNotification` with the ONE real media
     * notification, and demotes back on the user-engaged timeout. A plain
     * started service carries NO pending foreground contract, so the idle
     * window is platform-legal by construction; when playback is active the
     * service is a foreground mediaPlayback service exactly as before.
     *
     * Call-site timing: both PlayerViewModel paths reach here from
     * user-initiated playback entry (app in the foreground), where a plain
     * start is always legal; any refused start keeps its previous defined
     * behavior — logged and swallowed below (AGENTS §16: never crash on a
     * degraded session host). Start/stop ordering (PB-003) is unchanged.
     */
    private fun startService(episodeId: String) {
        // AQR-031: no extras — onStartCommand/ensureSession read the episode from
        // [episodeId] (this host), and nothing ever consumed the previous
        // EXTRA_EPISODE_ID payload from the start intent; it was written and
        // never read. Removing it changes no behaviour (media3 only inspects
        // its own action strings on the intent).
        val intent = Intent(context, PaheMediaSessionService::class.java)
        try {
            context.startService(intent)
            AppLog.d(TAG, "Started PaheMediaSessionService for $episodeId")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to start PaheMediaSessionService", e)
        }
    }

    private fun stopService() {
        val intent = Intent(context, PaheMediaSessionService::class.java)
        context.stopService(intent)
        AppLog.d(TAG, "Stopped PaheMediaSessionService")
    }
}