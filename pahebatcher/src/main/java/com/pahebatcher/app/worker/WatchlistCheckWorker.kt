package com.pahebatcher.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pahebatcher.app.domain.watchlist.WatchlistCheckResult
import com.pahebatcher.app.domain.watchlist.WatchlistCheckStatus
import com.pahebatcher.app.domain.watchlist.WatchlistChecker
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.notification.WatchlistNotificationManager
import kotlinx.coroutines.CancellationException

/**
 * The Python `wl check` entry point running as background work.
 *
 * `watchlist.py:run_watchlist_check` is a **one-shot command** the host schedules
 * (cron / Windows Task Scheduler — `pahebatcher wl check`); Android's equivalent
 * host is WorkManager, which schedules THIS worker on the configurable interval
 * chosen in Settings (`watchlist_check_interval_minutes`, default 15 —
 * [WatchlistCheckScheduler]). The periodic work is the cron entry.
 *
 * Since Phase 14 a run that DETECTS new episodes also posts the single
 * aggregated watchlist notification (PHASE14_PLAN §12.2); see
 * [notifyDetectedEpisodes] for the exact rules and their enforcement.
 *
 * The worker is **orchestration only**: it calls [WatchlistChecker.checkAll] and
 * turns the finished run into a WorkManager result. It contains no scan, no
 * detection, no relink and no download logic — that is all in the existing use
 * case, which is shared verbatim with the foreground `Check now` path
 * (`WatchlistViewModel.checkNow`), so background and foreground runs can never
 * drift apart.
 *
 * Dependencies come from [com.pahebatcher.app.worker.DownloadWorkerFactory], which
 * hands over the ONE application-scoped [WatchlistChecker] built by
 * `AppContainer` (Room repositories + the app-scoped WebView/Cloudflare
 * `HttpClient`). No second session, no second repository graph, no Context of its
 * own beyond the application context WorkManager provides to every Worker.
 *
 * ## Architectural boundary (narrow amendment, DECISIONS 91)
 *
 * ```
 * Worker → WatchlistChecker → WebViewHttpClient → AndroidCloudflareSession(appContext)
 * ```
 *
 * is allowed, and nothing else about the Phase 10 boundary changes:
 *
 * | | |
 * |---|---|
 * | allowed | Worker → the existing application-scoped WebView HTTP transport (scan/search), and — when an entry really has pending episodes — Worker → the existing stream-resolution path over that same transport |
 * | still prohibited | Worker → Activity, Worker → UI lifecycle, Worker → UI callback, Worker → a WebView an Activity owns |
 *
 * The reason the transport is usable here at all is that it is **not** an
 * Activity's WebView: `AndroidCloudflareSession` builds
 * `WebView(applicationContext)` on the main looper (`AndroidCloudflareSession.kt`
 * `createWebView`) and never attaches it to a window, so the same object the UI
 * uses is reachable from any thread of this process.
 *
 * ## Result semantics
 *
 * | outcome | result | why |
 * |---|---|---|
 * | run finished, every entry healthy | `success` | the check happened; the next period is the retry |
 * | run finished, some entries `FAILED` / `LINK_DEAD` / … | `success` | `WatchlistChecker` isolates per-entry failures and persists `last_checked` for each (`watchlist.py:773-782`); one broken entry is a *result*, not a Worker failure |
 * | empty watchlist | `success` | Python prints "watchlist is empty" and exits 0 |
 * | exception escaped `checkAll()` (Room/settings read failure) | `retry` | an infrastructure fault, not a business outcome: back off and try again instead of ending the run |
 * | cancellation (`stop()`) | rethrow | a cancelled run is never converted into a returned Result |
 *
 * `Result.failure()` is deliberately never used: there is no user-fixable input
 * for a periodic check, and a terminal failure would strand a schedule nobody can
 * restart. This is the place where the exact transient condition (an exception
 * that escaped the checker's own per-entry isolation) justifies it. Phase 14
 * added a second, unrelated `Result.retry()` in `DownloadWorker` — auto-resume
 * (PHASE14_PLAN §10/§15) — which fires only for a `TRANSPORT`-class download
 * failure with the `Auto-resume downloads` setting ON and the attempt budget
 * left, so "no other `Result.retry()` in production" no longer holds.
 *
 * ## Progress on a large watchlist
 *
 * The run is sequential and every entry persists its own `lastCheckedAt` as it
 * finishes ([WatchlistChecker.check]), so a Worker stopped part-way through a
 * long list keeps the progress already made and the next period re-checks
 * everything — exactly like the reference, which always walks the whole
 * watchlist on every run.
 */
class WatchlistCheckWorker(
    context: Context,
    params: WorkerParameters,
    private val checker: WatchlistChecker,
    /**
     * The aggregated watchlist notification.
     * Non-null by contract — the factory builds it from the Context WorkManager
     * hands over, so no composition can accidentally run a check that is unable
     * to report what it found.
     */
    private val notificationManager: WatchlistNotificationManager,
    /**
     * The `New watchlist episodes` preference,
     * read FRESH after each check — never cached — so a Settings flip applies
     * to the next check instead of the next app start. Default = the shipped
     * DataStore default (ON).
     */
    private val watchlistNotifyProvider: suspend () -> Boolean = { true },
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WatchlistCheckWorker"
    }

    override suspend fun doWork(): Result {
        val results = try {
            checker.checkAll()
        } catch (e: CancellationException) {
            // WorkManager stopped the run: propagate, never report it as a Result.
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Background watchlist check aborted: ${e.message}")
            // PHASE14_PLAN §12.2: a check that never finished has nothing to
            // report, so this path posts NOTHING — it returns before the
            // notification below is even considered.
            return Result.retry()
        }

        if (results.isEmpty()) {
            AppLog.i(TAG, "Background check finished: watchlist is empty")
            return Result.success()
        }

        val summary = results.groupingBy { it.status }.eachCount()
        AppLog.i(
            TAG,
            "Background check finished: ${results.size} entr${if (results.size == 1) "y" else "ies"}, " +
                "upToDate=${summary[WatchlistCheckStatus.UP_TO_DATE] ?: 0}, " +
                "newEpisodes=${summary[WatchlistCheckStatus.NEW_EPISODES] ?: 0}, " +
                "relinked=${results.count { it.relinked }}, " +
                "failed=${summary[WatchlistCheckStatus.FAILED] ?: 0}, " +
                "queued=${results.sumOf { it.queued }}, " +
                "skippedExisting=${results.sumOf { it.skippedExisting }}, " +
                "skippedDeleted=${results.sumOf { it.skippedDeleted }}",
        )

        notifyDetectedEpisodes(results)

        // Per-entry outcomes are already isolated AND persisted by the checker;
        // the periodic schedule is their retry, so the run itself succeeded.
        return Result.success()
    }

    /**
     * At most ONE notification per
     * check run, on the fixed `-9001` id so it REPLACES the previous one.
     *
     * | rule | how it is enforced here |
     * |---|---|
     * | trigger is the DETECTION count | [WatchlistCheckResult.newEpisodes] summed over every entry — deliberately not the status summary, because a `capacityTimedOut` entry is reported `FAILED` while still detecting real pending episodes |
     * | only when `> 0` | a check that found nothing posts nothing (SPEC §18 "do not create notification spam") |
     * | preference first | [watchlistNotifyProvider] is read BEFORE anything is built, so an OFF flag costs no work |
     * | posting never fails the run | the whole block is best-effort: a throw is logged and swallowed, because a notification problem is not a check failure |
     * | a thrown check posts nothing | [doWork] returns from its catch before reaching this function |
     * | foreground `checkNow()` posts nothing | enforced at the ViewModel — this worker is the ONLY caller of [notificationManager] |
     */
    private suspend fun notifyDetectedEpisodes(results: List<WatchlistCheckResult>) {
        val newEpisodes = results.sumOf { it.newEpisodes }
        if (newEpisodes <= 0) return

        val enabled = try {
            watchlistNotifyProvider()
        } catch (e: CancellationException) {
            // F-04 corrective (PHASE14_PLAN §12.2 "cancellation → rethrow"):
            // watchlistNotifyProvider() suspends (DataStore read), so a stopped
            // run throws CancellationException here. Swallowing it would convert
            // a cancelled run into a "notification disabled" decision and let
            // doWork return a Result from a cancelled coroutine. Cancellation
            // must propagate normally.
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Notification preference unreadable, suppressing: ${e.message}")
            false
        }
        if (!enabled) {
            AppLog.i(
                TAG,
                "Detected $newEpisodes new episode(s); the watchlist notification is disabled",
            )
            return
        }

        val seriesCount = results.count { it.newEpisodes > 0 }
        try {
            notificationManager.post(newEpisodes, seriesCount)
            AppLog.i(
                TAG,
                "Posted the aggregated watchlist notification: $newEpisodes new episode(s) " +
                    "in $seriesCount series (id=${WatchlistNotificationManager.WATCHLIST_NOTIFICATION_ID})",
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to post the watchlist notification: ${e.message}")
        }
    }
}
