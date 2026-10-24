package com.pahebatcher.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notification behavior tests.
 * Phase 10 architecture v10 §10, §16 (NotificationTest), extended by Phase 14
 * (PHASE14_PLAN §17).
 *
 * Verifies:
 * - Download channel is created (API 26+) with low importance (silent)
 * - Progress notifications use APP-OWNED small icons
 *   (Phase 10 Finding 8: no android.R.drawable system icons)
 * - Notification IDs are stable and positive
 * - Phase 14: completion/failure/watchlist builders — channel, group key,
 *   autoCancel, app-owned icon, presence of the tap-to-open contentIntent
 * - Phase 14: terminal ids are negative and never collide with progress ids
 * - Phase 14: the watchlist builder's fixed id (-9001) and its own group
 *
 * The tap destinations themselves (`navigate_to=downloads` / `watchlist`) are
 * proven end-to-end in `NotificationInteractionTest`, which fires the real
 * contentIntent and asserts the resulting route; here we only assert that a
 * contentIntent exists.
 *
 * Foreground posting DURING a real Worker run is verified separately in
 * ForegroundWorkerTest (emulator runtime verification).
 */
@RunWith(AndroidJUnit4::class)
class NotificationTest {

    private lateinit var context: Context
    private lateinit var manager: DownloadNotificationManager
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = DownloadNotificationManager(context)
        manager.createNotificationChannel()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun channel_is_created_and_silent() {
        val channel = notificationManager.getNotificationChannel(DownloadNotificationManager.CHANNEL_ID_DOWNLOADS)
        assertNotNull("Download channel must be created", channel)
        // IMPORTANCE_LOW = silent progress (no sound/vibration)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
    }

    @Test
    fun progress_notification_uses_app_owned_icon() {
        val notification = manager.buildProgressNotification(
            episodeId = "ep-notif-1",
            episodeTitle = "Episode 1",
            progress = 42,
            isActive = true
        )
        // Finding 8: must be an application-owned drawable, not android.R.drawable
        assertEquals(
            "Progress notification must use the app-owned icon",
            R.drawable.ic_stat_download,
            notification.icon
        )
        assertEquals(42, notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS, -1))
        assertFalse(
            "Determinate progress must not be indeterminate",
            notification.extras.getBoolean(NotificationCompat.EXTRA_PROGRESS_INDETERMINATE, false)
        )
    }

    @Test
    fun notification_ids_are_stable_and_positive() {
        val id1 = manager.notificationIdFor("ep-notif-abc")
        val id2 = manager.notificationIdFor("ep-notif-abc")
        val id3 = manager.notificationIdFor("ep-notif-xyz")

        assertEquals("IDs must be stable for the same episode", id1, id2)
        assertNotEquals("Different episodes must get different IDs", id1, id3)
        assertTrue("IDs must be positive (required by NotificationManager)", id1 > 0)
        assertTrue(id3 > 0)
    }

    @Test
    fun indeterminate_progress_notification_renders() {
        val notification = manager.buildProgressNotification(
            episodeId = "ep-notif-4",
            episodeTitle = "Episode 4",
            progress = -1,
            isActive = true
        )
        // -1 progress → setProgress(0, 0, true) → indeterminate flag set
        assertTrue(
            "Progress = -1 must render as indeterminate",
            notification.extras.getBoolean(NotificationCompat.EXTRA_PROGRESS_INDETERMINATE, false)
        )
        assertEquals(R.drawable.ic_stat_download, notification.icon)
        assertEquals("Downloading", notification.extras.getString(NotificationCompat.EXTRA_TITLE))
    }

    // ── Phase 14 (PHASE14_PLAN §8) ─────────────────────────────────────────

    @Test
    fun every_episode_notification_carries_the_download_group_key() {
        val progress = manager.buildProgressNotification("ep-notif-g1", "Episode G1", progress = 1)
        val completion = manager.buildCompletionNotification("ep-notif-g1", "Episode G1")
        val failure = manager.buildFailureNotification("ep-notif-g1", "Episode G1")

        assertEquals(
            "progress must carry the downloads group",
            DownloadNotificationManager.GROUP,
            progress.group,
        )
        assertEquals(
            "completion must carry the downloads group",
            DownloadNotificationManager.GROUP,
            completion.group,
        )
        assertEquals(
            "failure must carry the downloads group",
            DownloadNotificationManager.GROUP,
            failure.group,
        )
        assertEquals("pahebatcher.downloads", DownloadNotificationManager.GROUP)
    }

    @Test
    fun completion_notification_uses_its_own_channel_group_icon_and_tap_intent() {
        val notification = manager.buildCompletionNotification("ep-notif-c1", "Episode C1")

        assertEquals(
            NotificationChannels.DOWNLOAD_COMPLETE,
            notification.channelId,
        )
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channelImportance(notification.channelId))
        assertEquals(DownloadNotificationManager.GROUP, notification.group)
        assertTrue(
            "completion is auto-canceled by its tap",
            notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
        assertFalse(
            "completion is not an ongoing FGS notification",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertEquals(
            "completion must use the app-owned done vector",
            R.drawable.ic_stat_download_done,
            notification.icon,
        )
        assertNotNull(
            "completion must carry the tap-to-open contentIntent",
            notification.contentIntent,
        )
        assertEquals(
            "Download completed",
            notification.extras.getString(NotificationCompat.EXTRA_TITLE),
        )
        assertEquals(
            "Episode C1",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    @Test
    fun failure_notification_uses_its_own_channel_group_icon_and_tap_intent() {
        val notification = manager.buildFailureNotification("ep-notif-f1", "Episode F1")

        assertEquals(
            NotificationChannels.DOWNLOAD_FAILURE,
            notification.channelId,
        )
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channelImportance(notification.channelId))
        assertEquals(DownloadNotificationManager.GROUP, notification.group)
        assertTrue(
            "failure is auto-canceled by its tap",
            notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
        assertFalse(
            "failure is not an ongoing FGS notification",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertEquals(
            "failure must use the app-owned error vector",
            R.drawable.ic_stat_error,
            notification.icon,
        )
        assertNotNull(
            "failure must carry the tap-to-open contentIntent",
            notification.contentIntent,
        )
        assertEquals(
            "Download failed",
            notification.extras.getString(NotificationCompat.EXTRA_TITLE),
        )
        assertEquals(
            "Episode F1",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
        // §8 content rule: the raw failure message must never be rendered.
        assertTrue(
            "terminal text must not leak a URL",
            !notification.extras.getString(NotificationCompat.EXTRA_TEXT).orEmpty().contains("http"),
        )
    }

    @Test
    fun terminal_ids_are_negative_and_never_collide_with_progress_ids() {
        val ids = listOf("ep-notif-t1", "ep-notif-t2", "ep-notif-t3", "")

        ids.forEach { episodeId ->
            val progress = manager.notificationIdFor(episodeId)
            val terminal = manager.terminalIdFor(episodeId)

            assertTrue("progress id stays positive for '$episodeId'", progress >= 0)
            assertTrue("terminal id must be negative for '$episodeId'", terminal < 0)
            assertNotEquals(
                "terminal and progress ids must never collide for '$episodeId'",
                progress,
                terminal,
            )
            assertEquals(
                "terminal id must be stable for '$episodeId'",
                terminal,
                manager.terminalIdFor(episodeId),
            )
            assertEquals(
                "terminal id is progress id with the sign bit set",
                progress or Int.MIN_VALUE,
                terminal,
            )
        }
    }

    @Test
    fun terminal_ids_of_different_episodes_differ() {
        assertNotEquals(
            manager.terminalIdFor("ep-notif-x1"),
            manager.terminalIdFor("ep-notif-x2"),
        )
    }

    @Test
    fun watchlist_notification_uses_its_own_channel_group_icon_and_fixed_id() {
        val watchlist = WatchlistNotificationManager(context)
        val notification = watchlist.buildNotification(newEpisodes = 3, seriesCount = 2)

        assertEquals(NotificationChannels.WATCHLIST_NEW_EPISODES, notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channelImportance(notification.channelId))
        assertEquals("pahebatcher.watchlist", notification.group)
        assertNotEquals(
            "watchlist uses its own group, never the download group",
            DownloadNotificationManager.GROUP,
            notification.group,
        )
        assertTrue(
            "watchlist notification is auto-canceled by its tap",
            notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
        assertEquals(
            "watchlist must use the app-owned watchlist vector",
            R.drawable.ic_stat_watchlist,
            notification.icon,
        )
        assertNotNull(notification.contentIntent)
        assertEquals(
            "New episodes detected",
            notification.extras.getString(NotificationCompat.EXTRA_TITLE),
        )
        assertEquals(
            "3 new episodes in 2 series",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
        assertEquals(-9001, WatchlistNotificationManager.WATCHLIST_NOTIFICATION_ID)
    }

    @Test
    fun watchlist_notification_summary_pluralizes_honestly() {
        val watchlist = WatchlistNotificationManager(context)
        val singular = watchlist.buildNotification(newEpisodes = 1, seriesCount = 1)
        assertEquals(
            "1 new episode in 1 series",
            singular.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    // NOTE (Phase 14): the LIVE watchlist-posting assertion ("exactly ONE live
    // watchlist notification, fixed id -9001 replaces the previous run") is
    // deliberately NOT here. The `notification` package runs BEFORE the `ui`
    // package, i.e. before `FirstEnqueuePromptTest` has granted the running
    // user's POST_NOTIFICATIONS, and Android force-stops a process whose
    // runtime permission is revoked — so a post made here cannot be observed.
    // Plan §17 places that assertion in `WatchlistCheckWorkerTest` (M3), which
    // lives in the `worker` package where the grant is in effect; the post
    // PATH itself is already exercised under a platform-refused post by
    // `NotificationPermissionTest.postPathsAreSafeWhateverThePlatformPostingDecision`.

    private fun channelImportance(channelId: String): Int =
        notificationManager.getNotificationChannel(channelId)?.importance
            ?: NotificationManager.IMPORTANCE_NONE
}
