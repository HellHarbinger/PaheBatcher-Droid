package com.pahebatcher.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * The FOUR app-owned notification
 * channel definitions and the one idempotent creator for all of them.
 *
 * Importance is **sticky after creation** (Android keeps the user's customized
 * importance and ignores a later re-set), so the complete set is declared in
 * one place and created together — never partially, never gated on a
 * preference flag: a channel must exist for its Settings row to have meaning.
 *
 * | Channel | Importance | Contents |
 * |---|---|---|
 * | [DOWNLOAD_PROGRESS] `download_progress` | `LOW` (unchanged, silent) | progress + queued FGS notification |
 * | [DOWNLOAD_COMPLETE] `download_complete` | `DEFAULT` | completion notification |
 * | [DOWNLOAD_FAILURE] `download_failure` | `DEFAULT` | failure notification |
 * | [WATCHLIST_NEW_EPISODES] `watchlist_new_episodes` | `DEFAULT` | aggregated watchlist detection |
 *
 * The media notification channel is NOT declared here: media3's
 * `DefaultMediaNotificationProvider` owns and creates it (PHASE14_PLAN §8).
 *
 * `ensure()` is invoked wherever `createNotificationChannel()` was called
 * before (`DownloadWorker`) and from the watchlist post path
 * (`WatchlistNotificationManager`). All four app channels suppress the launcher
 * badge, extending the pre-existing progress-channel decision — a notification
 * is never a reason to badge the launcher icon.
 */
object NotificationChannels {

    /** Progress/queued foreground notification — `LOW`, silent, unchanged. */
    const val DOWNLOAD_PROGRESS = "download_progress"

    /** Terminal success — `DEFAULT` (banner + sound). */
    const val DOWNLOAD_COMPLETE = "download_complete"

    /** Terminal failure — `DEFAULT` (banner + sound). */
    const val DOWNLOAD_FAILURE = "download_failure"

    /** Aggregated watchlist detection — `DEFAULT` (banner + sound). */
    const val WATCHLIST_NEW_EPISODES = "watchlist_new_episodes"

    private const val PROGRESS_NAME = "Download Progress"
    private const val COMPLETE_NAME = "Download completed"
    private const val FAILURE_NAME = "Download failed"
    private const val WATCHLIST_NAME = "New watchlist episodes"

    private const val PROGRESS_DESCRIPTION = "Shows download progress for episodes"
    private const val COMPLETE_DESCRIPTION = "Announces a completed episode download"
    private const val FAILURE_DESCRIPTION = "Announces a failed episode download"
    private const val WATCHLIST_DESCRIPTION = "Announces newly detected watchlist episodes"

    /**
     * Creates every app channel. Idempotent — safe to call on every Worker run
     * and before every post (an already-created channel keeps its importance,
     * so repeated calls never reset a user's customization).
     */
    fun ensure(notificationManager: NotificationManager) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_PROGRESS,
                PROGRESS_NAME,
                NotificationManager.IMPORTANCE_LOW, // Silent — no sound/vibration
            ).apply {
                description = PROGRESS_DESCRIPTION
                setShowBadge(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_COMPLETE,
                COMPLETE_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = COMPLETE_DESCRIPTION
                setShowBadge(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_FAILURE,
                FAILURE_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = FAILURE_DESCRIPTION
                setShowBadge(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                WATCHLIST_NEW_EPISODES,
                WATCHLIST_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = WATCHLIST_DESCRIPTION
                setShowBadge(false)
            },
        )
    }
}
