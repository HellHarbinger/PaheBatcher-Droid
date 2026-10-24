package com.pahebatcher.app.notification

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.support.onTopRoute
import com.pahebatcher.app.ui.navigation.DownloadsRoute
import com.pahebatcher.app.ui.navigation.HomeRoute
import com.pahebatcher.app.ui.navigation.WatchlistRoute
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification TAP destinations.
 *
 * The other notification tests only assert on the built object; this one FIRES
 * the real `PendingIntent` the production builders attach and asserts the
 * route the application actually lands on, so the §7 contract
 * ("tap-to-open, per-type destination") is proven end-to-end:
 *
 *  - completion/failure → `navigate_to="downloads"` → [DownloadsRoute];
 *  - watchlist → `navigate_to="watchlist"` → [WatchlistRoute] (the Phase 14
 *    `pendingNavTarget` branch).
 *
 * Nothing here POSTS a notification, so the suite's ordering relative to the
 * `ui` package's POST_NOTIFICATIONS grant is irrelevant.
 *
 * ### Why the assertion does not go through [ActivityScenario]
 *
 * The notification intent carries `FLAG_ACTIVITY_NEW_TASK or
 * FLAG_ACTIVITY_CLEAR_TOP` and `MainActivity` uses the default `standard`
 * launch mode, so a tap either delivers to a fresh instance or recreates the
 * current one — an [ActivityScenario] bound to a previous instance would be
 * invalidated. Each test therefore resets to a known Home screen first (so the
 * destination assertion cannot pass vacuously), closes that scenario, fires the
 * real contentIntent, and then resolves whichever `MainActivity` is RESUMED
 * through the instrumentation lifecycle monitor. Both the cold-start and the
 * warm-delivery path converge on the same observable: the top route.
 *
 * The lifecycle monitor enforces main-thread access
 * (`ActivityLifecycleMonitorImpl.checkMainThread`), so every poll runs on the
 * main thread via `Instrumentation.runOnMainSync`.
 */
class NotificationInteractionTest {

    private fun launchIntent(): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(
            context.packageManager.getLaunchIntentForPackage(context.packageName),
        ) { "launch intent not found" }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** Opens the app plainly and closes it, leaving a known, clean start state. */
    private fun resetToHome() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(
                "the app must open at Home before the notification tap is fired",
                awaitCondition { scenario.onTopRoute<HomeRoute>() },
            )
        }
    }

    /**
     * Polls until a RESUMED [MainActivity] satisfies [predicate], evaluated on
     * the main thread (both the lifecycle monitor and the NavController read
     * require it).
     */
    private fun awaitTopRoute(predicate: (MainActivity) -> Boolean): Boolean =
        awaitCondition(20_000) {
            var reached = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                reached = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .any(predicate)
            }
            reached
        }

    @Test
    fun failureNotificationTapOpensDownloads() {
        resetToHome()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = DownloadNotificationManager(context)
        manager.createNotificationChannel()
        val notification = manager.buildFailureNotification(
            episodeId = "notif-intent-failure",
            episodeTitle = "Notification Intent Failure",
        )

        notification.contentIntent.send()

        assertTrue(
            "firing the failure contentIntent must land on the Downloads destination",
            awaitTopRoute { isOnRoute<DownloadsRoute>(it) },
        )
    }

    @Test
    fun completionNotificationTapOpensDownloads() {
        resetToHome()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = DownloadNotificationManager(context)
        manager.createNotificationChannel()
        val notification = manager.buildCompletionNotification(
            episodeId = "notif-intent-completion",
            episodeTitle = "Notification Intent Completion",
        )

        notification.contentIntent.send()

        assertTrue(
            "firing the completion contentIntent must land on the Downloads destination",
            awaitTopRoute { isOnRoute<DownloadsRoute>(it) },
        )
    }

    @Test
    fun watchlistNotificationTapOpensWatchlist() {
        resetToHome()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notification = WatchlistNotificationManager(context).buildNotification(
            newEpisodes = 2,
            seriesCount = 1,
        )

        notification.contentIntent.send()

        assertTrue(
            "firing the watchlist contentIntent must land on the Watchlist destination",
            awaitTopRoute { isOnRoute<WatchlistRoute>(it) },
        )
    }
}
