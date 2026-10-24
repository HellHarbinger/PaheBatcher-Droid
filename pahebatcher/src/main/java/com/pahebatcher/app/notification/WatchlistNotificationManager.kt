package com.pahebatcher.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.R

/**
 * Construction and posting of the
 * ONE aggregated watchlist notification.
 *
 * Rules encoded here (the caller owns the *decision* to post):
 *  - exactly ONE notification per check run, on the fixed [WATCHLIST_NOTIFICATION_ID]
 *    so each run REPLACES the previous one — never stacks, never per-entry;
 *  - channel [NotificationChannels.WATCHLIST_NEW_EPISODES] (`DEFAULT`);
 *  - group [GROUP] so the system stacks it under the watchlist key;
 *  - tap-to-open destination `navigate_to="watchlist"` (PHASE14_PLAN §7) —
 *    no action buttons (SPEC §18 defines the interaction as tap-to-open);
 *  - `setAutoCancel(true)` — tapping dismisses it;
 *  - content is a count summary only, never a URL or session value.
 *
 * Channel creation happens inside [post]: Android refuses a post to a channel
 * that does not exist yet, and the channel set must never depend on a
 * preference flag (PHASE14_PLAN §8).
 */
class WatchlistNotificationManager(private val context: Context) {

    companion object {
        /**
         * Fixed notification ID — every run replaces the previous notification
         * instead of stacking a new one (PHASE14_PLAN §8).
         */
        const val WATCHLIST_NOTIFICATION_ID = -9001

        /** Group key for every watchlist notification (PHASE14_PLAN §8). */
        const val GROUP = "pahebatcher.watchlist"

        /** PendingIntent request code — dedicated, never shared (§7). */
        private const val REQUEST_CODE = WATCHLIST_NOTIFICATION_ID
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Builds the aggregated notification. Pure construction — nothing is
     * posted, so tests can assert on the built object.
     *
     * @param newEpisodes total detected new episodes across all entries
     * @param seriesCount number of entries that reported `newEpisodes > 0`
     */
    fun buildNotification(newEpisodes: Int, seriesCount: Int): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, "watchlist")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, NotificationChannels.WATCHLIST_NEW_EPISODES)
            .setSmallIcon(R.drawable.ic_stat_watchlist)
            .setContentTitle("New episodes detected")
            .setContentText(summaryText(newEpisodes, seriesCount))
            .setGroup(GROUP)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    /**
     * Creates the channels (idempotent) and posts, replacing any previous
     * watchlist notification. Best-effort by contract: callers wrap this in a
     * `try/catch` so a posting problem never fails the check run
     * (PHASE14_PLAN §12.2).
     */
    fun post(newEpisodes: Int, seriesCount: Int) {
        NotificationChannels.ensure(notificationManager)
        notificationManager.notify(WATCHLIST_NOTIFICATION_ID, buildNotification(newEpisodes, seriesCount))
    }

    /** `"3 new episodes in 2 series"` / `"1 new episode in 1 series"`. */
    private fun summaryText(newEpisodes: Int, seriesCount: Int): String {
        val episodes = if (newEpisodes == 1) "1 new episode" else "$newEpisodes new episodes"
        val series = if (seriesCount == 1) "1 series" else "$seriesCount series"
        return "$episodes in $series"
    }
}
