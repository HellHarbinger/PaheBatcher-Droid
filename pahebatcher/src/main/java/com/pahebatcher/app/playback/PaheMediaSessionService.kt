package com.pahebatcher.app.playback

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.R
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.notification.NotificationChannels

/**
 * Thin MediaSessionService host.
 *
 * Lifecycle:
 * - onStartCommand/onCreate → ensureSession():
 *     - host.player present → MediaSession.Builder(this, player).setSessionActivity(...).build()
 *     - host.player absent   → [exitWithoutSession] (media-button/intent recreation after release exits
 *       immediately — A2.1 Decision 7: the FGS-contract exit is still guaranteed here, because a
 *       FOREGROUND start can arrive from outside our host path (system media-button delivery,
 *       media3's own start-self intents, any external `startForegroundService()` on this exported
 *       service) and the system REQUIRES a `startForeground()` transition even on the way out.
 *       Our own host now uses a plain `startService()` (PB-004 fix) so the normal session path
 *       carries no pending foreground contract until media3 promotes it on active playback.)
 * - onGetSession(controllerInfo) = policy-gated host.session (AQR-010: null for
 *   foreign untrusted controllers; PB-002: null when no session exists)
 * - media3 DefaultMediaNotificationProvider → posts/updates the media notification + its own
 *   channel; media3 ALSO owns foreground promotion (startForeground on user-engaged playback,
 *   stopForeground on the user-engaged timeout) — this service never calls startForeground
 *   on the session path
 *
 * The session wraps the existing VM-owned ExoPlayer instance; media3 sessions do not
 * own or create the player. The service is started/stopped explicitly by MediaSessionHost;
 * media3 owns the notification content/posting AND the foreground promotion lifecycle.
 *
 * Controller policy (AQR-010, DECISIONS 159): both gates — [onGetSession] and the
 * session callback's `onConnect` — apply [MediaSessionControllerPolicy]
 * (own package OR platform-trusted, everyone else rejected via
 * `ConnectionResult.reject()` / a null session), because the session metadata
 * exposes the resolved HLS URLs to whoever connects. Allowed controllers keep the
 * exact pre-policy default acceptance (`super.onConnect`). System surfaces (media
 * notification, lock screen, quick settings, media keys/Bluetooth) ride the
 * framework path with an `isTrusted` ControllerInfo and are unaffected.
 */
class PaheMediaSessionService : MediaSessionService() {

    private val TAG = "PaheMediaSessionService"

    companion object {
        /**
         * A2.1 Decision 7 (PB-004): the placeholder notification id for the
         * no-session FGS exit. NEGATIVE on purpose: every per-episode
         * notification id is the non-negative `notificationIdFor` hash
         * (AQR-054's mask), and -9001/-9002 are taken by the watchlist and
         * batch-failure summaries — so this constant can never REPLACE a real
         * notification during the microseconds it exists before
         * [Service.STOP_FOREGROUND_REMOVE] takes it down.
         */
        internal const val FOREGROUND_EXIT_NOTIFICATION_ID = -9004
    }

    // AQR-031: EXTRA_EPISODE_ID removed — the start-intent payload was written
    // by MediaSessionHost.startService but never read here (ensureSession uses
    // MediaSessionHost.episodeId), so the constant and its putExtra were dead.

    override fun onCreate() {
        super.onCreate()
        ensureSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureSession()
        return START_STICKY
    }

    // AQR-010: `ControllerInfo.isTrusted` is `@UnstableApi` in media3 1.11.1 —
    // opted in here at the two gate functions only (the rest of the class keeps
    // lint's protection against accidental unstable-API use).
    @OptIn(markerClass = [UnstableApi::class])
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // AQR-010 (Option 1, first gate): only our own package or a
        // platform-trusted controller gets the session object at all — the
        // session's MediaMetadata carries the resolved HLS URLs, and media3's
        // default accepts every controller (AGENTS §11). The same predicate
        // gates onConnect below; system surfaces (media notification, lock
        // screen, quick settings, media keys) never bind this service and
        // arrive trusted through the framework path instead.
        if (!MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = packageName,
                controllerPackage = controllerInfo.packageName,
                trusted = controllerInfo.isTrusted,
            )
        ) {
            // Package names are not credentials (AGENTS §10) — recording which
            // caller was refused makes a real mis-rejection diagnosable.
            AppLog.i(TAG, "onGetSession: rejected foreign controller ${controllerInfo.packageName}")
            return null
        }
        // PB-002: media3's contract is "the session for this controller, or null when
        // the service has no session". A no-player process restart (host==null) or the
        // window after onPlayerReleased() (session==null) must be a defined "no session"
        // rejection, not an IllegalStateException crossing the Binder boundary.
        val session = MediaSessionHostHolder.host?.session
        if (session == null) {
            AppLog.d(TAG, "onGetSession: no session available")
        }
        return session
    }

    private fun ensureSession() {
        val host = MediaSessionHostHolder.host
        val player = host?.player
        val episodeId = host?.episodeId

        if (player == null) {
            AppLog.w(TAG, "ensureSession: no player available — stopping service")
            exitWithoutSession()
            return
        }

        if (host.session != null) {
            AppLog.d(TAG, "MediaSession already exists for ${episodeId ?: "unknown"}")
            return
        }

        // AQR-138: the session activity target must name the playback MODE as
        // well as the episode. The host writes episodeId and mode together, so a
        // null mode here means the playback context is not fully recorded — the
        // defined rejection below is preferred to silently emitting a target
        // that would re-open the episode as a DOWNLOAD.
        val mode = host.mode
        if (mode == null) {
            AppLog.w(TAG, "ensureSession: no playback mode recorded — stopping service")
            exitWithoutSession()
            return
        }

        AppLog.d(TAG, "Creating MediaSession for $episodeId ($mode)")

        // AQR-032: tracks a session built in this attempt so the failure path
        // below can release it — otherwise a session that was built but never
        // published to the host (addSession threw) would hold the ExoPlayer
        // reference forever.
        var builtSession: MediaSession? = null

        try {
            val sessionActivity = MainActivity.createSessionActivityIntent(this, episodeId, mode)
            val mediaSession = MediaSession.Builder(this, player)
                .setId("pahe_media_session")
                .setSessionActivity(sessionActivity)
                // AQR-010 (Option 1, second gate): media3 1.11.1's supported
                // rejection mechanism — onConnect returning
                // ConnectionResult.reject(). Allowed controllers get
                // super.onConnect(...), i.e. byte-identical DEFAULT acceptance
                // (same commands, same result as before the gate existed), so
                // only foreign untrusted controllers change behaviour. The
                // framework/notification/media-key path builds a trusted
                // ControllerInfo (isTrustedForMediaControl) and keeps working;
                // see [MediaSessionControllerPolicy] for the verified rule.
                .setCallback(object : MediaSession.Callback {
                    // AQR-010: `ControllerInfo.isTrusted` is `@UnstableApi`
                    // (media3 1.11.1) — opted in on this gate function only.
                    @OptIn(markerClass = [UnstableApi::class])
                    override fun onConnect(
                        session: MediaSession,
                        controllerInfo: MediaSession.ControllerInfo,
                    ): MediaSession.ConnectionResult {
                        val allowed = MediaSessionControllerPolicy.isControllerAllowed(
                            ownPackage = packageName,
                            controllerPackage = controllerInfo.packageName,
                            trusted = controllerInfo.isTrusted,
                        )
                        if (allowed) {
                            return super<MediaSession.Callback>.onConnect(session, controllerInfo)
                        }
                        // Package names are not credentials (AGENTS §10).
                        AppLog.i(TAG, "onConnect: rejected foreign controller ${controllerInfo.packageName}")
                        return MediaSession.ConnectionResult.reject()
                    }
                })
                .build()
            builtSession = mediaSession

            // Register the session with the service so MediaSessionService manages
            // the foreground service lifecycle (startForeground/stopForeground).
            addSession(mediaSession)
            host.session = mediaSession
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to create MediaSession — stopping service", e)
            // AQR-032: release an orphaned session (built here, never published).
            // Every cleanup step is best-effort and guarded so it can never mask
            // the original failure: detach from the service only if addSession
            // actually registered it (possibly partially), then release.
            val orphan = builtSession
            if (orphan != null) {
                try {
                    if (isSessionAdded(orphan)) removeSession(orphan)
                } catch (cleanup: Exception) {
                    AppLog.w(TAG, "Orphan session was not registered with the service", cleanup)
                }
                try {
                    orphan.release()
                } catch (cleanup: Exception) {
                    AppLog.w(TAG, "Orphan session release failed", cleanup)
                }
            }
            host.session = null
            exitWithoutSession()
        }
    }

    /**
     * A2.1 Decision 7 (PB-004): the FGS-contract exit for a service that has just
     * decided it owns no session.
     *
     * The contract applies whenever the start that created this service instance
     * was a FOREGROUND start. Our own [MediaSessionHost] now starts with a plain
     * `startService()` (PB-004 fix), but a foreground start can still arrive from
     * outside that path — system media-button delivery, media3's own start-self
     * intents, or any external `startForegroundService()` on this exported
     * service — and Android REQUIRES `startForeground()` within the system
     * timeout even when the service immediately stops itself: skipping it raises
     * `ForegroundServiceDidNotStartInTimeException` at the next system check. So
     * the transition is guaranteed HERE: post a minimal placeholder on the
     * existing LOW-importance downloads channel, then remove it and stop — in the
     * same frame, so the user never sees it and no second persistent notification
     * exists. On a plain-started instance the placeholder exists only for a frame
     * (never contract-required, never lingering); on a foreground-started one it
     * IS the contract call. media3's media notification (the session path via
     * [addSession]) remains the only notification that ever stays, and the session
     * route never runs this.
     *
     * Every step is guarded: a failure here must never mask the stop below —
     * the STOP is the original behavior and always runs.
     */
    private fun exitWithoutSession() {
        try {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            NotificationChannels.ensure(notificationManager)
            startForeground(
                FOREGROUND_EXIT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, NotificationChannels.DOWNLOAD_PROGRESS)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle("PaheBatcher")
                    .setContentText("Playback service stopped")
                    .setSilent(true)
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: Exception) {
            // Best-effort only: never mask the stop below (PB-004's guarantee
            // is the STOP path; the system contract call is attempted, real).
            AppLog.w(TAG, "startForeground on the no-session exit failed: ${e.message}")
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val host = MediaSessionHostHolder.host
        host?.session?.run {
            AppLog.d(TAG, "Releasing MediaSession on service destroy")
            release()
        }
        host?.session = null
        super.onDestroy()
    }
}

/**
 * Simple holder to share the MediaSessionHost instance between the service and
 * the ViewModel factory. The host is instantiated once in AppContainer (app-scoped).
 */
object MediaSessionHostHolder {
    @Volatile var host: MediaSessionHost? = null
}