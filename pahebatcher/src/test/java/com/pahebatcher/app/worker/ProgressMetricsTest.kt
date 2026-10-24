package com.pahebatcher.app.worker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §25 — the metric behaviour of [ProgressTracker]: real byte totals, a
 * rate measured over a bounded window of real samples, and an ETA that is 0
 * whenever it cannot honestly be known.
 *
 * Every test drives the injected clock, so the arithmetic is deterministic —
 * no wall-clock timing, no sleeps, no flakiness. All the properties asserted
 * here are the ones a stalled, resumed or denominator-less download must keep:
 * no NaN, no Infinity, no negative ETA, no manufactured progress.
 */
class ProgressMetricsTest {

    private companion object {
        const val THROTTLE_MS = 5000L
    }

    /** Deterministic clock the test advances manually. */
    private class FakeClock {
        var now = 1_000_000L
        fun advance(ms: Long) { now += ms }
    }

    /** Captured Room writes — (completed, bytes, speed, eta) per flush. */
    private class WriteLog {
        data class Write(val completed: Int, val bytes: Long, val speed: Long, val eta: Long)

        val entries = mutableListOf<Write>()

        fun tracker(clock: FakeClock) = ProgressTracker(
            throttleMs = THROTTLE_MS,
            now = { clock.now },
            writeProgress = { completed, bytes, speed, eta ->
                entries += Write(completed, bytes, speed, eta)
            },
            notifyProgress = {},
        )
    }

    // -----------------------------------------------------------------------
    // Speed
    // -----------------------------------------------------------------------

    @Test
    fun `increasing bytes produce a positive real rate`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        tracker.onProgress(1, 10, 1_000_000L)
        clock.advance(5_000L)
        tracker.onProgress(2, 10, 2_000_000L)

        // 1,000,000 bytes gained over 5,000 ms = 200,000 bytes/second.
        assertEquals(200_000L, tracker.speedBps)
    }

    @Test
    fun `static bytes produce zero speed - a stall cannot look like progress`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        tracker.onProgress(1, 10, 1_000_000L)
        clock.advance(30_000L)
        tracker.onProgress(1, 10, 1_000_000L)

        assertEquals("no bytes gained → no rate", 0L, tracker.speedBps)
        assertEquals("…and therefore no countdown either", 0L, tracker.etaSeconds)
    }

    @Test
    fun `no elapsed time produces zero speed`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        tracker.onProgress(1, 10, 1_000_000L)
        // Same instant: bytes jumped but no time passed — dividing would be ∞.
        tracker.onProgress(2, 10, 9_000_000L)

        assertEquals(0L, tracker.speedBps)
        assertEquals(0L, tracker.etaSeconds)
    }

    @Test
    fun `a single sample has no interval and therefore no rate`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        tracker.onProgress(3, 10, 3_000_000L)

        assertEquals(0L, tracker.speedBps)
        assertEquals(0L, tracker.etaSeconds)
    }

    @Test
    fun `a burst of samples inside one window still yields a bounded real rate`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        // 1,000 segments in 5 seconds — far more samples than MAX_SAMPLES, so the
        // window cap really runs, and the rate must stay plausible throughout.
        tracker.onProgress(0, 1000, 0L)
        repeat(1000) { i ->
            clock.advance(5L)
            tracker.onProgress(i + 1, 1000, (i + 1) * 100_000L)
        }

        val speed = tracker.speedBps
        // 100,000 bytes / 5 ms = 20,000,000 bytes/second — the exact production
        // rate of the scripted feed; nothing was lost or duplicated by trimming.
        assertEquals(20_000_000L, speed)
        assertTrue("rate must be non-negative", speed >= 0L)
    }

    // -----------------------------------------------------------------------
    // ETA
    // -----------------------------------------------------------------------

    @Test
    fun `eta is remaining bytes over the measured rate`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        tracker.onProgress(1, 10, 1_000_000L)
        clock.advance(5_000L)
        tracker.onProgress(3, 10, 3_000_000L)

        // speed  = (3M - 1M) / 5 s        = 400,000 B/s
        // avg    = 3,000,000 / 3 segments = 1,000,000 B
        // remain = 7 segments * avg        = 7,000,000 B
        // eta    = 7,000,000 / 400,000     = 17.5 s → 17 s (never fractional)
        assertEquals(400_000L, tracker.speedBps)
        assertEquals(17L, tracker.etaSeconds)
    }

    @Test
    fun `an unknown total gives no eta even with a real rate`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        tracker.onProgress(1, 0, 1_000_000L) // totalSegments not yet persisted
        clock.advance(5_000L)
        tracker.onProgress(2, 0, 2_000_000L)

        assertEquals("a real rate with no denominator", 200_000L, tracker.speedBps)
        assertEquals("no fabricated denominator → no fabricated countdown", 0L, tracker.etaSeconds)
    }

    @Test
    fun `nothing remaining gives no eta`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        tracker.onProgress(8, 10, 1_000_000L)
        clock.advance(5_000L)
        tracker.onProgress(10, 10, 2_000_000L)

        assertEquals(200_000L, tracker.speedBps)
        assertEquals("completed == total → nothing left to download", 0L, tracker.etaSeconds)
    }

    @Test
    fun `a zero completed count gives no eta`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        tracker.onProgress(0, 10, 0L)
        clock.advance(5_000L)
        tracker.onProgress(0, 10, 1_000_000L) // bytes with no completed denominator

        assertEquals(200_000L, tracker.speedBps)
        assertEquals("no denominator → no ETA", 0L, tracker.etaSeconds)
    }

    @Test
    fun `every emitted metric is finite and non-negative`() {
        val clock = FakeClock()
        val tracker = WriteLog().tracker(clock)

        // Degenerate inputs: zero everything, then a huge jump, then a stall.
        tracker.onProgress(0, 0, 0L)
        assertEquals(0L, tracker.speedBps)
        assertEquals(0L, tracker.etaSeconds)

        clock.advance(1L)
        tracker.onProgress(1, Int.MAX_VALUE, Long.MAX_VALUE / 2)
        assertTrue("speed is finite by construction", tracker.speedBps >= 0L)
        assertTrue("eta is finite by construction", tracker.etaSeconds >= 0L)

        clock.advance(60_000L)
        tracker.onProgress(1, Int.MAX_VALUE, Long.MAX_VALUE / 2)
        assertEquals(0L, tracker.speedBps)
        assertEquals(0L, tracker.etaSeconds)
    }

    @Test
    fun `a negative byte input can never be persisted as negative`() = runBlocking {
        val clock = FakeClock()
        val log = WriteLog()
        val tracker = log.tracker(clock)

        tracker.onProgress(1, 10, -5_000L)
        assertEquals(0L, tracker.downloadedBytes)

        tracker.flush()
        assertEquals(0L, log.entries.single().bytes)
    }

    // -----------------------------------------------------------------------
    // Persistence of the metrics
    // -----------------------------------------------------------------------

    @Test
    fun `flush carries the real metric payload to the dao`() = runBlocking {
        val clock = FakeClock()
        val log = WriteLog()
        val tracker = log.tracker(clock)

        tracker.onProgress(4, 10, 4_194_304L)
        tracker.flush()

        val write = log.entries.single()
        assertEquals(4, write.completed)
        assertEquals("the real byte total reaches Room", 4_194_304L, write.bytes)
        assertEquals("no interval elapsed → no rate yet", 0L, write.speed)
        assertEquals(0L, write.eta)
    }

    @Test
    fun `forceFinal persists the byte total with a cleared rate`() = runBlocking {
        val clock = FakeClock()
        val log = WriteLog()
        val tracker = log.tracker(clock)

        tracker.onProgress(2, 10, 2_000_000L)
        clock.advance(5_000L)
        tracker.onProgress(4, 10, 4_000_000L)
        val liveSpeed = tracker.speedBps
        assertTrue("the run really had a rate to clear", liveSpeed > 0L)

        tracker.forceFinal(10)

        val terminal = log.entries.last()
        assertEquals(10, terminal.completed)
        assertEquals("bytes are real, not reset", 4_000_000L, terminal.bytes)
        assertEquals("a finished run reports no rate", 0L, terminal.speed)
        assertEquals("…and no countdown", 0L, terminal.eta)
    }
}
