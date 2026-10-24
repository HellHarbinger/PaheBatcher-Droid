package com.pahebatcher.app.domain.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `resolve_ahead` has exactly one consumption point, and this is it.
 *
 * Python's staging queue is `asyncio.Queue(maxsize=resolve_ahead + max_parallel)`
 * (`downloader.py:205-208`) and `await queue.put(...)` blocks the resolver while
 * it is full. [ResolveAheadGate] is that block: it must (a) not wait when there
 * is room, (b) actually wait while the staging depth is at capacity, and (c)
 * give up after its bounded timeout instead of parking forever (AGENTS §12).
 */
class ResolveAheadGateTest {

    /** Queue whose staged count is scripted per call; records how often it was read. */
    private class ScriptedQueue(private val staged: () -> Int) : EpisodeDownloadQueue {
        var calls = 0

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission) = EnqueueOutcome.Accepted

        override suspend fun resolvedAheadCount(): Int {
            calls++
            return staged()
        }
    }

    @Test
    fun `free capacity means no wait at all`() = runTest {
        val queue = ScriptedQueue { 0 }

        assertTrue(ResolveAheadGate.awaitCapacity(queue, capacity = 3))

        assertEquals("the count is read exactly once when there is room", 1, queue.calls)
    }

    @Test
    fun `waits while staging is at capacity and proceeds once it drains`() = runTest {
        // Three polls report a full staging queue, the fourth sees room again.
        var reads = 0
        val queue = ScriptedQueue {
            reads++
            if (reads <= 3) 10 else 0
        }

        assertTrue(ResolveAheadGate.awaitCapacity(queue, capacity = 5))

        assertEquals("one read per poll: 3 blocked + 1 that found room", 4, queue.calls)
    }

    @Test
    fun `a permanently full staging queue times out instead of waiting forever`() = runTest {
        val queue = ScriptedQueue { 99 }

        assertFalse(ResolveAheadGate.awaitCapacity(queue, capacity = 1))

        // The wait really was bounded polling, not an instant give-up.
        assertTrue(
            "the gate polled while waiting",
            queue.calls > 1_000,
        )
    }
}
