package com.pahebatcher.app.worker

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The ONE application-level schedule for background watchlist checks.
 *
 * SPEC §19 is explicit about the mechanism: *"Use WorkManager periodic work"*,
 * *"Respect Android's minimum periodic-work constraints"*, *"Do not continuously
 * run a background service"*. No AlarmManager, no timer, no foreground service,
 * no daemon exists or is needed — this is WorkManager only, as the phase
 * constraint requires.
 *
 * | property | value | why |
 * |---|---|---|
 * | unique name | [UNIQUE_WORK_NAME] | `enqueueUniquePeriodicWork` + [ExistingPeriodicWorkPolicy.UPDATE] make scheduling idempotent in COUNT and authoritative in CONTENT: every `Application.onCreate`, process restart or Activity recreation re-runs [schedule] and can never add a second request, while a changed interval is applied to the one that already exists |
 * | interval | [INTERVAL_MINUTES] = 15 by default, otherwise the persisted `watchlist_check_interval_minutes` | the shipped default is WorkManager's minimum periodic interval (15 minutes) and equals the only interval this release ever scheduled, so an upgrading install changes nothing; the Settings row offers 15 / 30 / 60 / 240 / 720, all >= that minimum (SPEC §19) |
 * | policy | [ExistingPeriodicWorkPolicy.UPDATE] | `KEEP` would silently DROP an interval change because the old request is already registered; `CANCEL_AND_REENQUEUE` would reset the cadence anchor and can fire an immediate extra check. With no prior request, UPDATE simply enqueues — so it is correct on first run and on every re-registration |
 * | constraint | [NetworkType].CONNECTED | a check is pure HTTP/HTML discovery — running it with no network can only produce a failed scan, and Python's host-side cron is likewise a network job |
 * | inexactness | by design | periodic work is deliberately inexact (system batched, deferred in Doze); the code never pretends otherwise |
 *
 * [schedule] is called from `PaheBatcherApplication.onCreate` through
 * `appConfig.watchlistCheckIntervalMinutes.distinctUntilChanged()` — process-level
 * lifecycle, never an Activity, never the Watchlist screen, never a button
 * press. The `distinctUntilChanged()` matters because the AppConfig flow maps
 * the whole `dataStore.data` stream, which re-emits on ANY settings write:
 * without it every unrelated toggle would re-issue a periodic UPDATE.
 */
object WatchlistCheckScheduler {

    /** The stable unique-work name — a typo here would silently duplicate the schedule. */
    const val UNIQUE_WORK_NAME = "watchlist_periodic_check"

    /**
     * The shipped default interval, in minutes: WorkManager's minimum periodic
     * interval, and the only value this release ever scheduled. Persisted as
     * `watchlist_check_interval_minutes` (PHASE14_PLAN §12.1), so an absent key
     * and an explicit reset both land here.
     */
    const val INTERVAL_MINUTES = 15L

    /**
     * The one constraint of a check — network available. Held as a constant so
     * [describe] can report it without reaching into `WorkSpec` (a
     * library-group-restricted API, and lint enforces that in production code).
     */
    val REQUIRED_NETWORK_TYPE: NetworkType = NetworkType.CONNECTED

    /**
     * The single periodic request this application ever enqueues for checks.
     *
     * @param intervalMinutes one of `AppConfig.WATCHLIST_INTERVAL_OPTIONS`;
     *   callers are responsible for passing a persisted (already validated)
     *   value, and the default keeps every pre-Phase-14 call site identical.
     */
    fun request(intervalMinutes: Long = INTERVAL_MINUTES): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<WatchlistCheckWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(REQUIRED_NETWORK_TYPE)
                    .build(),
            )
            .build()

    /**
     * Register the periodic check, or apply a changed interval to the request
     * that already exists. Idempotent in COUNT: [UNIQUE_WORK_NAME] guarantees
     * exactly one periodic request at all times. Authoritative in CONTENT:
     * [ExistingPeriodicWorkPolicy.UPDATE] is what makes a Settings change take
     * effect at all — `KEEP` would silently keep the old interval.
     *
     * Acceptance is asserted by test (a single request carrying the new
     * interval), never by a wall-clock claim; periodic work stays system-batched
     * and Doze-deferred regardless.
     */
    fun schedule(workManager: WorkManager, intervalMinutes: Long = INTERVAL_MINUTES): Operation =
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request(intervalMinutes),
        )

    /**
     * Observable description of the CURRENT WorkManager state for this schedule:
     * the request count, its states and the worker-class tags are read back from
     * WorkManager itself (never from a local variable), so a log line or a test
     * can prove that exactly one request exists and is still schedulable. The
     * interval and network type are the spec values this object registered.
     * Blocking — call it off the main thread.
     *
     * @param intervalMinutes the interval the caller just registered with
     *   [schedule]; the default preserves pre-Phase-14 callers.
     */
    fun describe(workManager: WorkManager, intervalMinutes: Long = INTERVAL_MINUTES): String {
        val infos = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        return "uniqueWork='$UNIQUE_WORK_NAME' interval=${intervalMinutes}m " +
            "policy=UPDATE network=$REQUIRED_NETWORK_TYPE count=${infos.size} " +
            "state=${infos.joinToString(prefix = "[", postfix = "]") { it.state.name }} " +
            "tags=${infos.firstOrNull()?.tags?.joinToString(prefix = "[", postfix = "]") ?: "[]"}"
    }
}
