package com.pahebatcher.app.domain.download

import kotlinx.coroutines.delay

/**
 * The ONE place `resolve_ahead` is enforced.
 *
 * Python's only consumption point for the key is the staging queue capacity
 * (`downloader.py:205-208`):
 *
 * ```python
 * download_queue = asyncio.Queue(maxsize=resolve_ahead + self.ctx.max_parallel)
 * ```
 *
 * i.e. how many RESOLVED-but-not-yet-started episodes the single resolver may
 * stage in front of the `max_parallel` downloaders; `await download_queue.put(...)`
 * blocks once the queue is full.
 *
 * The Android equivalent is [EpisodeDownloadQueue.resolvedAheadCount] — Room rows
 * that are `QUEUED` with a persisted stream — and this gate blocks the caller
 * until the count drops below `capacity` (`resolve_ahead + max_parallel`). A batch
 * (or a watchlist check) resolves through this gate, so lowering `resolve_ahead`
 * in Settings genuinely throttles how far resolution runs ahead of downloading,
 * and the shipped default (999) never throttles at all (Python's default).
 *
 * Bounded (AGENTS §12): the wait expires after [TIMEOUT_MS] and returns `false`
 * so a stalled download queue can never park a coroutine forever — Python has no
 * such limit because its loop is the caller's process lifetime.
 */
object ResolveAheadGate {

    /** Re-read the staging depth at this cadence while throttled. */
    const val POLL_MS = 500L

    /** Upper bound on ONE wait — 30 minutes. */
    const val TIMEOUT_MS = 30L * 60L * 1000L

    /**
     * Suspend until fewer than [capacity] resolved episodes are staged.
     *
     * @return `true` when capacity was (or immediately became) available;
     *   `false` when the bounded wait expired — the caller must stop and report,
     *   never silently ignore the setting.
     */
    suspend fun awaitCapacity(queue: EpisodeDownloadQueue, capacity: Int): Boolean {
        var waited = 0L
        while (queue.resolvedAheadCount() >= capacity) {
            if (waited >= TIMEOUT_MS) return false
            delay(POLL_MS)
            waited += POLL_MS
        }
        return true
    }
}
