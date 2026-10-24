package com.pahebatcher.app.worker

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import com.pahebatcher.app.data.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12D (SPEC §19), extended by Phase 14 (PHASE14_PLAN §12.1): the ONE
 * background schedule for watchlist checks.
 *
 * Pure request-spec assertions — the WorkManager-side behaviour (UPDATE applies
 * a changed interval to the request that already exists, exactly one request
 * exists, it is ENQUEUED) is asserted against a real WorkManager in
 * `WatchlistCheckWorkerTest`, because that is where it is observable: reading
 * an `ExistingPeriodicWorkPolicy` literal back out would only prove the source
 * says what it says, while re-scheduling with a different interval and finding
 * the single request carrying the NEW one is what UPDATE actually promises
 * (`KEEP` would leave the old interval in place).
 */
class WatchlistCheckSchedulerTest {

    @Test
    fun `the schedule targets the WatchlistCheckWorker class`() {
        val request = WatchlistCheckScheduler.request()
        assertTrue(
            "the periodic work must run WatchlistCheckWorker",
            request.tags.contains(WatchlistCheckWorker::class.java.name),
        )
    }

    @Test
    fun `the interval is the 15 minute periodic minimum`() {
        val request = WatchlistCheckScheduler.request()
        assertTrue(request.workSpec.isPeriodic)
        assertEquals(
            15L * 60L * 1000L,
            request.workSpec.intervalDuration,
        )
        assertEquals(
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            WatchlistCheckScheduler.INTERVAL_MINUTES * 60_000L,
        )
    }

    /**
     * [WatchlistCheckScheduler.request] takes an
     * interval, so a Settings choice reaches WorkManager instead of being
     * swallowed by a hard-coded 15.
     */
    @Test
    fun `an explicit interval overrides the default request interval`() {
        for (minutes in listOf(30L, 60L, 240L, 720L)) {
            val request = WatchlistCheckScheduler.request(minutes)
            assertTrue("request($minutes) must still be periodic", request.workSpec.isPeriodic)
            assertEquals(
                "request($minutes) must build a ${minutes}-minute period",
                minutes * 60L * 1000L,
                request.workSpec.intervalDuration,
            )
            assertTrue(
                "request($minutes) must still target WatchlistCheckWorker",
                request.tags.contains(WatchlistCheckWorker::class.java.name),
            )
        }
    }

    /**
     * The Settings row can only offer intervals WorkManager accepts.
     * Asserted against the SAME list the setter validates with, so a value added
     * to one and forgotten in the other fails here rather than on a device.
     */
    @Test
    fun `every offered interval satisfies the WorkManager periodic minimum`() {
        for (minutes in AppConfig.WATCHLIST_INTERVAL_OPTIONS) {
            assertTrue(
                "interval $minutes must be >= the WorkManager periodic minimum",
                minutes * 60_000L >= PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            )
        }
    }

    /**
     * An ABSENT `watchlist_check_interval_minutes` key resolves to
     * `AppConfig.DEFAULT_WATCHLIST_INTERVAL_MINUTES`, and the scheduler's own
     * default parameter is `INTERVAL_MINUTES` — if those two ever disagree, an
     * upgrading install would silently switch cadence.
     */
    @Test
    fun `the config default and the scheduler default are the same interval`() {
        assertEquals(
            WatchlistCheckScheduler.INTERVAL_MINUTES,
            AppConfig.DEFAULT_WATCHLIST_INTERVAL_MINUTES,
        )
    }

    @Test
    fun `a check only runs when there is network, and for no other reason`() {
        val constraints = WatchlistCheckScheduler.request().workSpec.constraints
        assertEquals(
            "the check is pure HTTP/HTML discovery — network is the only gate",
            NetworkType.CONNECTED,
            constraints.requiredNetworkType,
        )
        // No battery/idle/charging/storage gate that could postpone a check
        // indefinitely. TEST-011: asserted as VALUE EQUALITY against a
        // baseline that carries ONLY the network gate — not via the previous
        // substring search over `toString()`, whose result depended on a
        // third-party rendering format (if androidx.work ever changed it,
        // every check would pass for the wrong reason and the guarantee
        // would silently vanish). `Constraints` implements equals/hashCode
        // over exactly these gate fields.
        val networkOnly = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        assertEquals(
            "network is the ONLY scheduling gate — nothing may postpone a check",
            networkOnly,
            constraints,
        )
    }

    @Test
    fun `the unique work name is a stable non-blank constant`() {
        assertEquals("watchlist_periodic_check", WatchlistCheckScheduler.UNIQUE_WORK_NAME)
        assertTrue(WatchlistCheckScheduler.UNIQUE_WORK_NAME.isNotBlank())
    }
}
