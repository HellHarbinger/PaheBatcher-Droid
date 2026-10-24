package com.pahebatcher.app.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.support.ensureTestMp4
import com.pahebatcher.app.ui.navigation.PlayerMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PB-004 (fixed 2026-10-20) — the MediaSession foreground-service lifecycle
 * is contract-free until media3 promotes it.
 *
 * [MediaSessionHost] starts [PaheMediaSessionService] with a PLAIN
 * `startService()` (the fix): media3 1.11.1 owns foreground promotion and
 * re-issues its own foreground-start self-intent + `startForeground()` only
 * when playback is user-engaged (`playWhenReady && READY/BUFFERING`), while
 * an EMPTY player is deliberately invisible to it (empty timeline ⇒ the
 * notification is removed, never posted). The previous
 * `startForegroundService()` created the platform contract at player-creation
 * time, so the empty-player session path left it unsatisfied and the system
 * raised `ForegroundServiceDidNotStartInTimeException` — live-reproduced on
 * device REDACTED-SERIAL before the fix (this file's session-path case).
 *
 * The three situations around the service start are pinned here:
 *
 *  1. **external foreground start, no player** (media-button/intent
 *     recreation after release, or any external `startForegroundService()` on
 *     the exported service): the system's contract check must be SATISFIED by
 *     the guaranteed no-session exit — the run survives the window, the
 *     placeholder never lingers, the service stops itself;
 *  2. **idle session path** (the former PB-004 failure): a real player with
 *     an empty playlist builds a session but media3 never promotes — the path
 *     must carry NO pending contract (crash-freedom across a window longer
 *     than the system timeout), and the placeholder must never appear;
 *  3. **active playback session**: real media reaches playing, media3
 *     promotes the service (its own foreground start + the ONE media
 *     notification), and the run survives across the window; release through
 *     the production path removes the notification and stops the service.
 *
 * Device tests on purpose: `Service.startForeground`, the system's FGS
 * contract enforcement and posted-notification observation are Android
 * framework behaviour with no JVM equivalent. Observation notes:
 * `ActivityManager.getRunningServices` no longer exists in SDK 37, so "the
 * service stopped itself" is read through `Context.stopService`'s return
 * value (false = nothing was still running), the media3 promotion is observed
 * through its media notification in `NotificationManager.activeNotifications`,
 * and the contract itself is proven by crash-freedom across a window longer
 * than the system timeout.
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionServiceForegroundExitTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var priorHolder: MediaSessionHost? = null
    private var player: ExoPlayer? = null

    @Before
    fun setUp() {
        priorHolder = MediaSessionHostHolder.host
        // AQR-073: clear leftover own-package notifications (e.g. per-episode
        // download progress from another suite) so hasMediaNotification() can
        // only ever be satisfied by the media3 notification this test drives,
        // and its post-release wait can never stall on an unrelated leftover.
        NotificationManagerCompat.from(context).cancelAll()
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, PaheMediaSessionService::class.java))
        instrumentation.runOnMainSync {
            player?.release()
            player = null
        }
        MediaSessionHostHolder.host = priorHolder
    }

    private fun placeholderActive(): Boolean =
        NotificationManagerCompat.from(context).activeNotifications
            .any { it.id == PaheMediaSessionService.FOREGROUND_EXIT_NOTIFICATION_ID }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun `a no-player start satisfies the FGS contract and stops itself cleanly`() {
        // Host with NO player — exactly the media-button/intent recreation
        // after PlayerViewModel.onCleared() released everything.
        MediaSessionHostHolder.host = MediaSessionHost(context)

        // An EXTERNAL foreground start (media-button/intent recreation) — production's own MediaSessionHost.startService now issues a plain startService() (PB-004).
        context.startForegroundService(Intent(context, PaheMediaSessionService::class.java))

        // The system checks the startForeground() contract within ~5s and
        // raises ForegroundServiceDidNotStartInTimeException (killing this
        // instrumentation run) when it is missing — surviving a LONGER window
        // is therefore the transition proof. Meanwhile the placeholder may be
        // posted for a frame (that IS the contract call) but must never linger.
        Thread.sleep(8_000)
        assertFalse(
            "the PB-004 placeholder must never linger after the exit",
            placeholderActive(),
        )

        // stopService reports whether anything was still running: false = the
        // service already stopped itself via its own stopSelf during the exit.
        assertFalse(
            "the service must have stopped itself inside the window",
            context.stopService(Intent(context, PaheMediaSessionService::class.java)),
        )
    }

    @Test
    fun `the idle session path carries no pending FGS contract and never posts the exit placeholder`() {
        // The production route with an EMPTY player: a real player →
        // onPlayerCreated → plain startService → session built → media3
        // deliberately never promotes (empty timeline = no notification, no
        // foreground). Before the PB-004 fix this exact path died with
        // ForegroundServiceDidNotStartInTimeException ~5 s in (device REDACTED-SERIAL).
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build()
        }
        val host = MediaSessionHost(context)
        host.onPlayerCreated(player!!, "pb004-idle-session", PlayerMode.DOWNLOAD)

        assertTrue(
            "the session must be built on the host for a real player",
            waitUntil(timeoutMs = 4_000) { host.session != null },
        )

        // Hold the idle session path OPEN across a window LONGER than the
        // platform's FGS timeout: under the old foreground start this is where
        // the system killed the app; with the plain start there is no pending
        // contract to miss. Sample the shade the whole time — the placeholder
        // must never appear on the session path.
        val sampleUntil = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < sampleUntil) {
            assertFalse(
                "the exit placeholder must never be posted on the session path",
                placeholderActive(),
            )
            Thread.sleep(100)
        }

        // Cleanup through the production release path (ordered, F-01):
        // session release + stopService, then the player release in tearDown.
        instrumentation.runOnMainSync { host.onPlayerReleased() }
        assertNull("release path clears the session (release-once)", host.session)
        assertNull("release path clears the player reference", host.player)
        assertFalse(
            "the service must be stopped after the production release path",
            context.stopService(Intent(context, PaheMediaSessionService::class.java)),
        )
    }

    @Test
    fun `an active-playback session is promoted by media3 and survives the FGS window`() {
        // Production ordering exactly: player created → onPlayerCreated (plain
        // start, idle session) → media set → prepare → play. media3 observes
        // the playback-state events and promotes the service itself when
        // playback becomes user-engaged (its own foreground-start self-intent
        // + the ONE media notification).
        val mediaPath = ensureTestMp4()
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build()
        }
        val host = MediaSessionHost(context)
        host.onPlayerCreated(player!!, "pb004-active-playback", PlayerMode.DOWNLOAD)
        assertTrue(
            "the session must be built on the host for a real player",
            waitUntil(timeoutMs = 4_000) { host.session != null },
        )

        instrumentation.runOnMainSync {
            player!!.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(File(mediaPath)))))
            player!!.prepare()
            player!!.playWhenReady = true
        }

        assertTrue(
            "real media reaches active playback (media3's promotion trigger)",
            waitUntil(timeoutMs = 15_000) { isPlayingNow(player!!) },
        )
        assertTrue(
            "media3 posts its ONE media notification on active playback",
            waitUntil(timeoutMs = 10_000) { hasMediaNotification() },
        )

        // Hold ACTIVE playback across a window longer than the platform FGS
        // timeout: the promoted foreground contract is media3's own (the posted
        // media notification IS the foreground notification), so the run must
        // survive it, and the PB-004 placeholder must never appear.
        val sampleUntil = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < sampleUntil) {
            assertFalse(
                "the exit placeholder must never be posted while playing",
                placeholderActive(),
            )
            Thread.sleep(200)
        }
        assertTrue(
            "the media notification stays while playback is active",
            hasMediaNotification(),
        )

        // Cleanup through the production release path (ordered, F-01):
        // session release + service stop FIRST, then the player release.
        instrumentation.runOnMainSync {
            host.onPlayerReleased()
            player?.release()
            player = null
        }
        assertNull("release path clears the session (release-once)", host.session)
        assertTrue(
            "media notification removed after the production release path",
            waitUntil(timeoutMs = 10_000) { !hasMediaNotification() },
        )
        assertFalse(
            "the service must be stopped after the production release path",
            context.stopService(Intent(context, PaheMediaSessionService::class.java)),
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Reads the player's playback state on the player's (main) looper. */
    private fun isPlayingNow(p: ExoPlayer): Boolean {
        var playing = false
        instrumentation.runOnMainSync { playing = p.playWhenReady && p.isPlaying }
        return playing
    }

    // Our app's known notification IDs (watchlist summary / aggregated
    // batch-failure summary) — same rule as MediaSessionLifecycleTest.
    private val appNotificationIds = setOf(-9001, -9002)

    /**
     * The media3-owned media notification: a posted notification from our
     * package that is neither an app notification ID nor the PB-004 exit
     * placeholder.
     */
    private fun hasMediaNotification(): Boolean =
        NotificationManagerCompat.from(context).activeNotifications.any { statusbar ->
            statusbar.packageName == context.packageName &&
                statusbar.id !in appNotificationIds &&
                statusbar.id != PaheMediaSessionService.FOREGROUND_EXIT_NOTIFICATION_ID
        }
}
