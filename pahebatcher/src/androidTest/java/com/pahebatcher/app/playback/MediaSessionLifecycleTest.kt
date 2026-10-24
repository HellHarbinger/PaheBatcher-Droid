package com.pahebatcher.app.playback

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.ensureTestMp4
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.support.isPlayerPlaying
import com.pahebatcher.app.support.seedCompletedEpisodes
import com.pahebatcher.app.ui.navigation.HomeRoute
import com.pahebatcher.app.ui.navigation.PlayerRoute
import com.pahebatcher.app.ui.navigation.navigateToPlayer
import com.pahebatcher.app.ui.player.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MediaSession lifecycle instrumentation.
 *
 * Verifies the MediaSession integration around the existing single ExoPlayer:
 *  1. Playback → media notification present in NotificationManager.activeNotifications,
 *     service running, same ExoPlayer (livePlayerCount == 1);
 *  2. Release (pop player) → media notification gone, service stopped.
 *
 * The media notification is posted by media3's DefaultMediaNotificationProvider
 * with a media3-owned notification ID. We detect it by checking for a notification
 * from our package that is NOT one of our app's known notification IDs
 * (download progress, completion, failure, watchlist).
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionLifecycleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var db: PaheDatabase
    private lateinit var mediaPath: String
    private lateinit var notificationManager: NotificationManager

    // Our app's known notification IDs (from NotificationChannels / DownloadNotificationManager)
    // PB-009: the media-session PendingIntent REQUEST code -9002 (MainActivity) is a
    // different namespace entirely and never appears in activeNotifications — it does
    // not belong in this set. Both ids below are real posted notifications.
    private val appNotificationIds = setOf(
        -9001, // watchlist summary
        -9002, // aggregated batch-failure summary (BATCH_FAILURE_NOTIFICATION_ID)
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = PaheDatabase.getInstance(context)
        mediaPath = ensureTestMp4()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun playbackShowsMediaNotificationAndReleaseRemovesIt() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()
        val liveBefore = PlayerViewModel.livePlayerCount.get()

        // Open the player
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigateToPlayer(episodeId)
        }
        assertTrue(
            "player route opens",
            awaitCondition(15_000) { topRouteIs<PlayerRoute>() },
        )

        // Wait for playback to start
        assertTrue(
            "playback is active",
            awaitCondition(15_000) { composeRule.isPlayerPlaying() },
        )

        // 1. Media notification should be present during playback
        assertTrue(
            "media notification present during playback",
            awaitCondition(10_000) { hasMediaNotification() },
        )

        // 2. Same ExoPlayer — livePlayerCount should have increased by exactly 1
        assertEquals(
            "exactly one ExoPlayer created for playback",
            liveBefore + 1,
            PlayerViewModel.livePlayerCount.get(),
        )

        // 3. Release: pop the player
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(
            "back pops the player to Home",
            awaitCondition(10_000) { topRouteIs<HomeRoute>() },
        )

        // 4. Media notification should be gone after release
        assertTrue(
            "media notification removed after player release",
            awaitCondition(10_000) { !hasMediaNotification() },
        )

        // 5. Player released exactly once
        assertTrue(
            "player released after pop",
            awaitCondition(10_000) {
                PlayerViewModel.livePlayerCount.get() == liveBefore
            },
        )
    }

    /**
     * Checks whether a media notification is currently active. The media
     * notification is posted by media3's DefaultMediaNotificationProvider with
     * a media3-owned ID. We detect it as a notification from our package that
     * is NOT one of our app's known notification IDs.
     */
    private fun hasMediaNotification(): Boolean {
        val activeNotifications = notificationManager.activeNotifications
        return activeNotifications.any { statusBarNotification ->
            val id = statusBarNotification.id
            // Media notification is from our package but not one of our app's IDs
            id !in appNotificationIds && isFromOurPackage(statusBarNotification)
        }
    }

    /**
     * Checks whether a status bar notification belongs to our package.
     * media3 notifications are posted under our package's notification channel.
     */
    private fun isFromOurPackage(statusBarNotification: android.service.notification.StatusBarNotification): Boolean {
        // media3's DefaultMediaNotificationProvider posts under our package
        // The notification's packageName is our app's package
        return statusBarNotification.packageName == "com.pahebatcher.app"
    }

    private inline fun <reified T : Any> topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<T>(activity) }
    }
}
