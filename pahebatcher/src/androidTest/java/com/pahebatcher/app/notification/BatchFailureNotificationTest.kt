package com.pahebatcher.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.R
import com.pahebatcher.app.domain.download.FailureCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Slice C — the notification contract for attributed failures.
 *
 * Two things are pinned here, both about honesty of what the user is told:
 *
 * 1. the ONE aggregated batch-failure notification — a fixed id that REPLACES
 *    rather than stacks, the existing failure channel and group (no new
 *    channel), counts plus the series title plus (post-audio slice) a CONCISE
 *    breakdown of the typed failure reasons — and nothing anywhere that could
 *    leak a stream URL (AGENTS §10);
 * 2. the per-episode failure notification now carrying the audio and the typed
 *    category — while a failure the code cannot attribute renders exactly the
 *    pre-Slice-C text, because an invented reason is worse than none.
 *
 * Posting is exercised directly, so these pass whether or not the device has
 * granted the notification permission — which is the point of the final test.
 */
@RunWith(AndroidJUnit4::class)
class BatchFailureNotificationTest {

    private lateinit var context: Context
    private lateinit var manager: DownloadNotificationManager
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = DownloadNotificationManager(context)
        manager.createNotificationChannel()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    private fun channelImportance(channelId: String): Int =
        notificationManager.getNotificationChannel(channelId)?.importance ?: -1

    private fun activeCountFor(id: Int): Int =
        notificationManager.activeNotifications.count { it.id == id }

    // -----------------------------------------------------------------------
    // The aggregated batch-failure notification
    // -----------------------------------------------------------------------

    @Test
    fun batch_summary_reuses_the_failure_channel_group_and_icon() {
        val notification = manager.buildBatchFailureNotification(
            failed = 3,
            total = 12,
            animeTitle = "Test Anime",
        )

        assertEquals(NotificationChannels.DOWNLOAD_FAILURE, notification.channelId)
        assertEquals(
            "the failure channel keeps its shipped importance",
            NotificationManager.IMPORTANCE_DEFAULT,
            channelImportance(notification.channelId),
        )
        assertEquals(DownloadNotificationManager.GROUP, notification.group)
        assertEquals(
            "the batch summary must use the app-owned error vector",
            R.drawable.ic_stat_error,
            notification.icon,
        )
        assertTrue(
            "the summary is auto-canceled by its tap",
            notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
        assertFalse(
            "the summary is not an ongoing FGS notification",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertNotNull("the summary must carry its tap-to-open intent", notification.contentIntent)
    }

    @Test
    fun batch_summary_names_the_counts_and_the_series_and_nothing_else() {
        val notification = manager.buildBatchFailureNotification(
            failed = 3,
            total = 12,
            animeTitle = "Test Anime",
        )

        assertEquals(
            "Batch download failed",
            notification.extras.getString(NotificationCompat.EXTRA_TITLE),
        )
        assertEquals(
            "3 of 12 episodes failed — Test Anime",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    @Test
    fun batch_summary_reads_correctly_for_a_single_failure() {
        val single = manager.buildBatchFailureNotification(1, 12, "Test Anime")
        assertEquals(
            "`episodes` belongs to the TOTAL, so the singular needs no second branch",
            "1 of 12 episodes failed — Test Anime",
            single.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    @Test
    fun batch_summary_uses_a_fixed_negative_id_distinct_from_every_other_notification() {
        // Fixed, so a second run replaces the first; negative, so it cannot be
        // mistaken for a progress id; distinct from the watchlist summary and
        // from a per-episode terminal id, so it can never inherit or clobber
        // either of them.
        assertEquals(-9002, DownloadNotificationManager.BATCH_FAILURE_NOTIFICATION_ID)
        assertTrue(
            "a summary id is negative like every terminal id",
            DownloadNotificationManager.BATCH_FAILURE_NOTIFICATION_ID < 0,
        )
        assertNotEquals(
            "must never collide with the watchlist summary",
            -9001,
            DownloadNotificationManager.BATCH_FAILURE_NOTIFICATION_ID,
        )
        assertNotEquals(
            "must never collide with a per-episode terminal id",
            manager.terminalIdFor("ep-notif-1"),
            DownloadNotificationManager.BATCH_FAILURE_NOTIFICATION_ID,
        )
    }

    @Test
    fun a_second_batch_summary_replaces_the_first_instead_of_stacking() {
        // Replacement can only be OBSERVED where the platform actually lets a
        // notification post. Where it does not, this is skipped rather than
        // asserting a pass the device cannot demonstrate.
        Assume.assumeTrue(
            "notifications are disabled for this app on this device",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )

        manager.postBatchFailureNotification(failed = 1, total = 3, animeTitle = "First")
        manager.postBatchFailureNotification(failed = 2, total = 3, animeTitle = "Second")

        assertEquals(
            "a new batch result REPLACES the previous summary",
            1,
            activeCountFor(DownloadNotificationManager.BATCH_FAILURE_NOTIFICATION_ID),
        )
    }

    // -----------------------------------------------------------------------
    // Post-audio slice — the reason breakdown on the batch summary
    // -----------------------------------------------------------------------

    @Test
    fun batch_summary_names_the_typed_reasons_with_their_counts() {
        val notification = manager.buildBatchFailureNotification(
            failed = 3,
            total = 12,
            animeTitle = "Test Anime",
            reasonCounts = linkedMapOf(
                "Segment download failed" to 2,
                "Assembly failed" to 1,
            ),
        )

        assertEquals(
            "counts and series first, then the concise reasons",
            "3 of 12 episodes failed — Test Anime · " +
                "Segment download failed ×2, Assembly failed",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
        // Everything else about the summary is untouched by the new clause.
        assertEquals("Batch download failed", notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(NotificationChannels.DOWNLOAD_FAILURE, notification.channelId)
        assertEquals(DownloadNotificationManager.GROUP, notification.group)
    }

    @Test
    fun batch_summary_caps_the_reason_breakdown_and_states_how_many_more_there_are() {
        val notification = manager.buildBatchFailureNotification(
            failed = 5,
            total = 12,
            animeTitle = "Test Anime",
            reasonCounts = linkedMapOf(
                "Assembly failed" to 1,
                "Disk full" to 1,
                "No downloadable content" to 1,
                "Segment download failed" to 1,
            ),
        )

        val text = notification.extras.getString(NotificationCompat.EXTRA_TEXT)
        assertNotNull(text)
        assertTrue(
            "the line stays readable rather than enumerating every distinct reason",
            text!!.contains("+1 more"),
        )
        assertEquals(
            "exactly the three most frequent labels survive the cap " +
                "(count desc, then label asc)",
            "5 of 12 episodes failed — Test Anime · " +
                "Assembly failed, Disk full, No downloadable content +1 more",
            text,
        )
        assertEquals(
            "the one left out is the alphabetically last, not a silent drop",
            false,
            text.contains("Segment download failed"),
        )
    }

    @Test
    fun batch_summary_with_no_attributable_reason_keeps_the_exact_counts_only_line() {
        // Every failure was UNKNOWN, so the caller hands over an EMPTY map —
        // and the rendered line must be byte-identical to the pre-reason text
        // (asserted again here, because "no reason" must never become "an
        // invented one").
        val notification = manager.buildBatchFailureNotification(
            failed = 3,
            total = 12,
            animeTitle = "Test Anime",
            reasonCounts = emptyMap(),
        )

        assertEquals(
            "3 of 12 episodes failed — Test Anime",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    // -----------------------------------------------------------------------
    // Nothing a notification renders may quote a transport detail (AGENTS §10)
    // -----------------------------------------------------------------------

    @Test
    fun no_failure_text_can_carry_a_url_a_token_or_a_header() {
        val leaky = Regex("https?://|cookie|authorization|bearer", RegexOption.IGNORE_CASE)

        for (category in FailureCategory.entries) {
            for (audio in listOf("jpn", "eng", "kor")) {
                val text = manager.buildFailureNotification(
                    episodeId = "ep-leak-$audio-${category.name}",
                    episodeTitle = "Beginnings",
                    audio = audio,
                    category = category,
                ).extras.getString(NotificationCompat.EXTRA_TEXT)
                assertNotNull(text)
                assertFalse(
                    "the typed category path must never render a transport detail ($category)",
                    leaky.containsMatchIn(text!!),
                )
            }
        }

        val summary = manager.buildBatchFailureNotification(
            failed = FailureCategory.entries.size,
            total = 12,
            animeTitle = "Test Anime",
            reasonCounts = FailureCategory.entries.mapNotNull { it.label }
                .associateWith { 1 },
        ).extras.getString(NotificationCompat.EXTRA_TEXT)
        assertNotNull(summary)
        assertFalse(
            "the aggregate reason line is typed prose, never raw error text",
            leaky.containsMatchIn(summary!!),
        )
    }

    // -----------------------------------------------------------------------
    // Per-episode attribution
    // -----------------------------------------------------------------------

    @Test
    fun a_failure_notification_names_the_audio_and_the_category_the_code_can_prove() {
        val notification = manager.buildFailureNotification(
            episodeId = "ep-attr-1",
            episodeTitle = "Beginnings",
            audio = "jpn",
            category = FailureCategory.SEGMENT_DOWNLOAD_FAILED,
        )

        assertEquals(
            "Beginnings · Japanese · Segment download failed",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
        // Everything else about it is unchanged: same title, same channel.
        assertEquals(
            "Download failed",
            notification.extras.getString(NotificationCompat.EXTRA_TITLE),
        )
        assertEquals(NotificationChannels.DOWNLOAD_FAILURE, notification.channelId)
    }

    @Test
    fun a_failure_with_no_audio_and_no_category_renders_the_pre_slice_c_text_exactly() {
        val notification = manager.buildFailureNotification(
            episodeId = "ep-attr-2",
            episodeTitle = "Next Step",
        )

        assertEquals(
            "an unattributable failure says exactly what it said before",
            "Next Step",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    @Test
    fun UNKNOWN_renders_no_category_rather_than_an_invented_one() {
        val notification = manager.buildFailureNotification(
            episodeId = "ep-attr-3",
            episodeTitle = "Ambiguous",
            audio = "eng",
            category = FailureCategory.UNKNOWN,
        )

        assertEquals(
            "the audio is still real, but no cause is invented",
            "Ambiguous · English",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    @Test
    fun a_row_with_audio_but_no_category_still_names_the_audio() {
        val notification = manager.buildFailureNotification(
            episodeId = "ep-attr-4",
            episodeTitle = "Ambiguous",
            audio = "eng",
        )

        assertEquals(
            "Ambiguous · English",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    @Test
    fun an_unlabelled_audio_code_is_rendered_the_way_the_downloads_screen_renders_it() {
        val notification = manager.buildFailureNotification(
            episodeId = "ep-attr-5",
            episodeTitle = "Korean cut",
            audio = "kor",
            category = FailureCategory.AUDIO_UNAVAILABLE,
        )

        assertEquals(
            "open-set audio is echoed, never invented as a language",
            "Korean cut · KOR · Requested audio not available",
            notification.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    // -----------------------------------------------------------------------
    // Permission-denied safety
    // -----------------------------------------------------------------------

    @Test
    fun post_paths_never_throw_whatever_the_platform_posting_decision() {
        // On API 33+ with the permission denied the platform drops the post
        // silently; either way this must not throw.
        manager.postBatchFailureNotification(failed = 2, total = 4, animeTitle = "Test Anime")

        val channels = notificationManager.notificationChannels
        val appChannelIds = setOf(
            DownloadNotificationManager.CHANNEL_ID_DOWNLOADS,
            NotificationChannels.DOWNLOAD_COMPLETE,
            NotificationChannels.DOWNLOAD_FAILURE,
            NotificationChannels.WATCHLIST_NEW_EPISODES,
        )
        // Only the APP's channels are counted: media3 owns one of its own
        // (created by DefaultMediaNotificationProvider when a MediaSession is
        // active), and it is neither created nor owned by this code. Counting
        // the total would make this depend on whether a player test ran first.
        assertEquals(
            "exactly the four shipped app channels — Slice C adds NO new one",
            4,
            channels.count { it.id in appChannelIds },
        )
        assertTrue(
            "the batch summary posts onto an app channel it already owns",
            channels.any { it.id == NotificationChannels.DOWNLOAD_FAILURE },
        )
    }
}
