package com.pahebatcher.app.data.hls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [HlsHostConcurrencyLimiter].
 *
 * These observe ACTUAL simultaneous in-flight work (a counter incremented and
 * decremented inside the critical section) rather than asserting that a semaphore
 * method was called, so a cap that is merely constructed but never enforced fails.
 */
class HlsHostConcurrencyLimiterTest {

    /**
     * Records real concurrency: `peak` is the highest number of blocks that were
     * demonstrably executing at the same instant, per host and overall.
     */
    private class InFlight {
        private val current = ConcurrentHashMap<String, AtomicInteger>()
        private val peak = ConcurrentHashMap<String, AtomicInteger>()
        private val totalPeak = AtomicInteger(0)
        private val totalCurrent = AtomicInteger(0)

        fun enter(host: String) {
            val now = current.computeIfAbsent(host) { AtomicInteger(0) }.incrementAndGet()
            peak.computeIfAbsent(host) { AtomicInteger(0) }.updateAndGet { maxOf(it, now) }
            val tot = totalCurrent.incrementAndGet()
            totalPeak.updateAndGet { maxOf(it, tot) }
        }

        fun exit(host: String) {
            current[host]?.decrementAndGet()
            totalCurrent.decrementAndGet()
        }

        fun peak(host: String): Int = peak[host]?.get() ?: 0
        fun totalPeak(): Int = totalPeak.get()
        fun outstanding(): Int = totalCurrent.get()
    }

    @Test
    fun `same-host requests never exceed the per-host cap but do reach it`() = runTest {
        val limit = 4
        val limiter = HlsHostConcurrencyLimiter(limitPerHost = limit)
        val tracker = InFlight()

        coroutineScope {
            repeat(40) { i ->
                launch {
                    limiter.withHostSlot("https://cdn-a.example/seg$i.ts") {
                        tracker.enter("cdn-a.example")
                        delay(10)
                        tracker.exit("cdn-a.example")
                    }
                }
            }
        }

        assertEquals("Cap must never be exceeded", limit, tracker.peak("cdn-a.example"))
        assertEquals("Cap must actually be reached, not just constructed", limit, tracker.totalPeak())
        assertEquals("Every slot must be released", 0, tracker.outstanding())
    }

    @Test
    fun `different hosts get independent caps rather than sharing one budget`() = runTest {
        val limitPerHost = 3
        val limiter = HlsHostConcurrencyLimiter(limitPerHost = limitPerHost)
        val tracker = InFlight()

        coroutineScope {
            repeat(12) { i ->
                launch {
                    limiter.withHostSlot("https://cdn-a.example/seg$i.ts") {
                        tracker.enter("cdn-a.example")
                        delay(10)
                        tracker.exit("cdn-a.example")
                    }
                }
                launch {
                    limiter.withHostSlot("https://cdn-b.example/seg$i.ts") {
                        tracker.enter("cdn-b.example")
                        delay(10)
                        tracker.exit("cdn-b.example")
                    }
                }
            }
        }

        // Python's limit_per_host is per host, so unrelated hosts must not contend.
        assertEquals(limitPerHost, tracker.peak("cdn-a.example"))
        assertEquals(limitPerHost, tracker.peak("cdn-b.example"))
        assertTrue(
            "Independent hosts must be able to run at the same time " +
                "(got ${tracker.totalPeak()} simultaneous)",
            tracker.totalPeak() >= limitPerHost * 2,
        )
        assertEquals(0, tracker.outstanding())
    }

    @Test
    fun `cancelling a waiter releases nothing and leaks no slot`() = runTest {
        val limiter = HlsHostConcurrencyLimiter(limitPerHost = 1)
        val tracker = InFlight()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()

        val holder = launch {
            limiter.withHostSlot("https://cdn-a.example/seg0.ts") {
                tracker.enter("cdn-a.example")
                firstStarted.complete(Unit)
                releaseFirst.await()
                tracker.exit("cdn-a.example")
            }
        }
        firstStarted.await()

        // This one queues behind the holder, then is cancelled while still waiting.
        val waiter = launch {
            limiter.withHostSlot("https://cdn-a.example/seg1.ts") {
                tracker.enter("cdn-a.example")
                tracker.exit("cdn-a.example")
            }
        }
        waiter.cancelAndJoin()

        releaseFirst.complete(Unit)
        holder.join()

        assertEquals("Only the holder ever entered", 1, tracker.totalPeak())

        // A cancelled acquire must NOT have released a permit it never held - if it
        // had, the cap would now be 2 and this burst would exceed it.
        coroutineScope {
            repeat(6) { i ->
                launch {
                    limiter.withHostSlot("https://cdn-a.example/after$i.ts") {
                        tracker.enter("cdn-a.example")
                        delay(10)
                        tracker.exit("cdn-a.example")
                    }
                }
            }
        }
        assertEquals("Cap must still hold after a cancellation", 1, tracker.peak("cdn-a.example"))
        assertEquals("No slot may leak", 0, tracker.outstanding())
    }

    @Test
    fun `a failing request propagates its error and releases its slot`() = runTest {
        val limit = 2
        val limiter = HlsHostConcurrencyLimiter(limitPerHost = limit)
        val tracker = InFlight()
        var failures = 0

        coroutineScope {
            repeat(20) { i ->
                launch {
                    try {
                        limiter.withHostSlot("https://cdn-a.example/seg$i.ts") {
                            tracker.enter("cdn-a.example")
                            try {
                                delay(5)
                                if (i % 4 == 0) error("segment $i transport failure")
                            } finally {
                                tracker.exit("cdn-a.example")
                            }
                        }
                    } catch (e: IllegalStateException) {
                        failures++
                    }
                }
            }
        }

        assertEquals("Every 4th request must surface its failure", 5, failures)
        assertEquals("A failure must not widen the cap", limit, tracker.peak("cdn-a.example"))
        assertEquals("A failure must not leak its slot", 0, tracker.outstanding())
    }

    @Test
    fun `repeated episodes neither leak permits nor deadlock`() = runTest {
        val limit = 3
        val limiter = HlsHostConcurrencyLimiter(limitPerHost = limit)
        val tracker = InFlight()

        // Mimics several episodes' worth of segment bursts over one shared limiter,
        // the situation Android previously had no bound for at all.
        repeat(8) { episode ->
            coroutineScope {
                repeat(12) { seg ->
                    launch {
                        limiter.withHostSlot("https://cdn-a.example/ep$episode/seg$seg.ts") {
                            tracker.enter("cdn-a.example")
                            delay(5)
                            tracker.exit("cdn-a.example")
                        }
                    }
                }
            }
            assertEquals("Episode $episode must drain completely", 0, tracker.outstanding())
        }

        assertEquals("The cap must hold across every episode", limit, tracker.peak("cdn-a.example"))
    }

    @Test
    fun `unparseable host is passed through without deadlocking`() = runTest {
        val limiter = HlsHostConcurrencyLimiter(limitPerHost = 1)
        val ran = AtomicInteger(0)

        limiter.withHostSlot("not a url at all") { ran.incrementAndGet() }
        limiter.withHostSlot("") { ran.incrementAndGet() }

        assertEquals("Malformed URLs must still execute", 2, ran.get())
    }

    @Test
    fun `limiter exposes the configured python limit`() = runBlocking {
        assertEquals(24, HlsHostConcurrencyLimiter(24).limitPerHost())
        assertEquals(8, HlsHostConcurrencyLimiter(8).limitPerHost())
    }

    // -----------------------------------------------------------------------
    // A2.1 Decision 6: the persisted hls_workers is AUTHORITATIVE
    // -----------------------------------------------------------------------

    @Test
    fun `a host first seen after a settings change gets the new ceiling`() = runTest {
        var hlsWorkers = 2
        val limiter = HlsHostConcurrencyLimiter(limitProvider = { hlsWorkers })
        val tracker = InFlight()

        coroutineScope {
            repeat(8) { i ->
                launch {
                    limiter.withHostSlot("https://cdn-a.example/seg$i.ts") {
                        tracker.enter("cdn-a.example")
                        delay(5)
                        tracker.exit("cdn-a.example")
                    }
                }
            }
        }
        assertEquals("the initial ceiling holds", 2, tracker.peak("cdn-a.example"))

        hlsWorkers = 4
        coroutineScope {
            repeat(16) { i ->
                launch {
                    limiter.withHostSlot("https://cdn-b.example/seg$i.ts") {
                        tracker.enter("cdn-b.example")
                        delay(5)
                        tracker.exit("cdn-b.example")
                    }
                }
            }
        }
        assertEquals(
            "a NEW host must see the CURRENT setting, not a startup snapshot",
            4,
            tracker.peak("cdn-b.example"),
        )
    }

    @Test
    fun `a tracked host keeps its creation-time ceiling after the setting changes`() = runTest {
        var hlsWorkers = 1
        val limiter = HlsHostConcurrencyLimiter(limitProvider = { hlsWorkers })
        val tracker = InFlight()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()

        val holder = launch {
            limiter.withHostSlot("https://cdn-a.example/seg0.ts") {
                tracker.enter("cdn-a.example")
                firstStarted.complete(Unit)
                releaseFirst.await()
                tracker.exit("cdn-a.example")
            }
        }
        firstStarted.await()

        // The user raises the setting — but this host's semaphore already exists.
        hlsWorkers = 8
        val second = launch {
            limiter.withHostSlot("https://cdn-a.example/seg1.ts") {
                tracker.enter("cdn-a.example")
                tracker.exit("cdn-a.example")
            }
        }
        delay(50)
        assertFalse(
            "the tracked host must NOT resize live — in-flight permits would strand",
            second.isCompleted,
        )

        releaseFirst.complete(Unit)
        holder.join()
        second.join()
        assertEquals(
            "the ceiling is fixed at first sighting (defined settings-change semantics)",
            1,
            tracker.peak("cdn-a.example"),
        )
    }

    @Test
    fun `the ceiling is floored at one so a zero limit cannot freeze a host`() = runTest {
        val limiter = HlsHostConcurrencyLimiter(limitProvider = { 0 })

        assertEquals("floor", 1, limiter.limitPerHost())
        assertEquals(
            "the host must still run traffic, not deadlock behind a zero-permit semaphore",
            7,
            limiter.withHostSlot("https://cdn-a.example/seg0.ts") { 7 },
        )
    }

    @Test
    fun `a failing live read falls back to the shipped default`() = runTest {
        val limiter = HlsHostConcurrencyLimiter(limitProvider = { error("datastore unavailable") })

        assertEquals(
            "a failed read falls back to the shipped hls_workers default (24), never 0",
            HlsHostConcurrencyLimiter.FALLBACK_LIMIT_PER_HOST,
            limiter.limitPerHost(),
        )
        assertEquals(
            "traffic must still flow on the fallback ceiling",
            1,
            limiter.withHostSlot("https://cdn-a.example/seg0.ts") { 1 },
        )
    }

    @Test
    fun `a cancelled live read propagates instead of being swallowed`() = runTest {
        val limiter = HlsHostConcurrencyLimiter(
            limitProvider = { throw CancellationException("settings read cancelled") },
        )

        try {
            limiter.limitPerHost()
            fail("a cancelled read must propagate, not silently resolve to a fallback")
        } catch (e: CancellationException) {
            // Expected — cancellation is structural, never an error to absorb.
        }
    }
}
