package com.pahebatcher.app.worker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Progress throttling tests for ProgressTracker.
 * Phase 10 architecture v10 §12, §16 (ProgressThrottlingTest).
 *
 * Verifies:
 * - Room writes are throttled to at most one per throttle interval
 *   (not one per segment callback)
 * - Notification updates are throttled to the same interval
 * - First notification is emitted immediately
 * - forceFinal always writes exactly once, bypassing the throttle
 * - After forceFinal, stale in-memory progress can NEVER be written
 *   (final state cannot be overwritten)
 * - No writes occur without a progress callback (flush is a no-op when clean)
 *
 * The SPEC §25 metric behaviour (bytes/speed/ETA) is pinned separately in
 * [ProgressMetricsTest]; the writes here assert that the four-value payload is
 * carried through the unchanged throttling contract.
 */
class ProgressThrottlingTest {

    private companion object {
        const val THROTTLE_MS = 5000L
    }

    /** Deterministic clock the test advances manually. */
    private class FakeClock {
        var now = 1_000_000L
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `room writes are throttled - not one per segment callback`() = runBlocking {
        val clock = FakeClock()
        val writes = mutableListOf<Pair<Int, Long>>()
        val tracker = ProgressTracker(
            throttleMs = THROTTLE_MS,
            now = { clock.now },
            writeProgress = { completed, _, _, _ -> writes.add(completed to clock.now) },
            notifyProgress = {}
        )

        // 100 rapid segment callbacks within one throttle interval
        repeat(100) { i ->
            tracker.onProgress(i + 1, 100, (i + 1) * 1000L)
            clock.advance(10) // 10ms apart — 1000ms total, still within 5000ms
        }

        // One bounded flush call (as the single flush coroutine would do)
        tracker.flush()

        // First flush: lastWriteTime=0 so interval has "elapsed" — exactly ONE write
        assertEquals(1, writes.size)
        assertEquals(100, writes[0].first)

        // More callbacks + flushes within interval → no additional writes
        repeat(50) { tracker.onProgress(it + 1, 100, (it + 1) * 1000L); clock.advance(10) }
        tracker.flush()
        assertEquals("Second flush within interval must not write", 1, writes.size)

        // Advance beyond interval → exactly one more write
        clock.advance(THROTTLE_MS)
        tracker.flush()
        assertEquals("Flush after interval must write once", 2, writes.size)
        assertEquals(50, writes[1].first)
    }

    @Test
    fun `flush without pending progress does not write`() = runBlocking {
        val clock = FakeClock()
        var writeCount = 0
        val tracker = ProgressTracker(
            throttleMs = THROTTLE_MS,
            now = { clock.now },
            writeProgress = { _, _, _, _ -> writeCount++ },
            notifyProgress = {}
        )

        clock.advance(THROTTLE_MS * 10)
        tracker.flush()
        tracker.flush()

        assertEquals("No progress callback → no Room writes", 0, writeCount)
    }

    @Test
    fun `notification updates are throttled to the interval`() {
        val clock = FakeClock()
        val notifications = mutableListOf<Int>()
        val tracker = ProgressTracker(
            throttleMs = THROTTLE_MS,
            now = { clock.now },
            writeProgress = { _, _, _, _ -> },
            notifyProgress = { notifications.add(it) }
        )

        // First callback emits immediately
        tracker.onProgress(1, 10, 1000L)
        assertEquals("First notification must be emitted", 1, notifications.size)
        assertEquals(10, notifications[0])

        // Rapid callbacks within interval → suppressed
        repeat(20) { tracker.onProgress(it + 2, 10, (it + 2) * 1000L); clock.advance(100) }
        assertEquals("Notifications within interval must be throttled", 1, notifications.size)

        // Beyond interval → emitted once
        clock.advance(THROTTLE_MS)
        tracker.onProgress(7, 10, 7000L)
        assertEquals(2, notifications.size)
        assertEquals(70, notifications[1])
    }

    @Test
    fun `forceFinal writes exactly once bypassing throttle`() = runBlocking {
        val clock = FakeClock()
        val writes = mutableListOf<Int>()
        val tracker = ProgressTracker(
            throttleMs = THROTTLE_MS,
            now = { clock.now },
            writeProgress = { completed, _, _, _ -> writes.add(completed) },
            notifyProgress = {}
        )

        // First flush writes (no prior write yet), establishing the interval baseline
        tracker.onProgress(5, 10, 5000L)
        tracker.flush()
        assertEquals(listOf(5), writes)

        // A flush 1ms after progress but within the interval since the last
        // write must be throttled
        tracker.onProgress(6, 10, 6000L)
        clock.advance(1)
        tracker.flush()
        assertEquals("Flush within interval must be throttled", listOf(5), writes)

        // forceFinal must write regardless of throttle
        tracker.forceFinal(10)
        assertEquals("forceFinal must bypass throttle", listOf(5, 10), writes)
        assertTrue(tracker.isFinished)

        // Second forceFinal is a no-op
        tracker.forceFinal(99)
        assertEquals("forceFinal must write exactly once", listOf(5, 10), writes)
    }

    @Test
    fun `stale progress after forceFinal can never overwrite final state`() = runBlocking {
        val clock = FakeClock()
        val writes = mutableListOf<Int>()
        val tracker = ProgressTracker(
            throttleMs = THROTTLE_MS,
            now = { clock.now },
            writeProgress = { completed, _, _, _ -> writes.add(completed) },
            notifyProgress = {}
        )

        tracker.forceFinal(42)
        assertEquals(listOf(42), writes)

        // A stale callback arrives after completion (e.g. late segment callback)
        tracker.onProgress(7, 10, 7000L)
        // TEST-002: assert the invariant directly. The old `completed == 42 ||
        // tracker.isFinished` was vacuous — forceFinal sets isFinished (checked at
        // the start of this test), so the second disjunct was always true and the
        // "stale onProgress is ignored" property was never actually observed.
        assertEquals("Stale onProgress must not change completed", 42, tracker.completed)

        // Even time passes and flush is called, nothing may be written
        clock.advance(THROTTLE_MS * 100)
        tracker.flush()
        tracker.flush()

        assertEquals("No stale write may overwrite the final state", listOf(42), writes)
    }

    @Test
    fun `total of zero reports indeterminate progress`() {
        val notifications = mutableListOf<Int>()
        val tracker = ProgressTracker(
            throttleMs = THROTTLE_MS,
            now = { System.currentTimeMillis() },
            writeProgress = { _, _, _, _ -> },
            notifyProgress = { notifications.add(it) }
        )

        tracker.onProgress(0, 0, 0L)
        assertEquals("Zero total must report indeterminate (-1)", listOf(-1), notifications)
    }
}
