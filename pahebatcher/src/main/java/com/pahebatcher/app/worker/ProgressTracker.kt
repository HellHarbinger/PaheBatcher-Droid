package com.pahebatcher.app.worker

/**
 * Throttled progress tracker for DownloadWorker.
 * Phase 10 architecture v10 §12 (Progress Persistence), extended by the final
 * SPEC §25 gap (downloaded bytes / speed / ETA).
 *
 * Design constraints (Phase 10 corrective, Finding 7):
 * - The EpisodeDownloader.onProgress callback fires per segment and is NOT a
 *   suspend function, so Room writes cannot happen directly inside it.
 * - No runBlocking: the Worker coroutine thread is never blocked.
 * - No unbounded coroutine launches: the caller launches exactly ONE bounded
 *   flush coroutine that periodically calls [flush].
 * - Timestamp-based throttling: both Room writes and notification updates are
 *   gated to at most one per [throttleMs] interval.
 * - Stale-write guard: after [forceFinal], all further writes are suppressed so
 *   a late in-memory progress value can never overwrite the final state.
 *
 * Rate metrics (SPEC §25):
 * - `downloadedBytes` arrives from the downloader (resume base + every segment
 *   whose atomic write succeeded) — this class never invents byte counts.
 * - `speed` is a real byte rate over a BOUNDED time window of those samples:
 *   bytes gained / elapsed milliseconds, 0 whenever nothing was gained or no
 *   time has passed. Samples are capped in count, so memory stays bounded no
 *   matter how many segments complete inside one window.
 * - `etaSeconds` is remaining bytes / speed, where remaining is derived from
 *   the persisted average segment size (bytes / completed segments). It is 0
 *   (unknown) whenever the rate is unusable or the `completed/total`
 *   denominator is missing — never NaN, never Infinity, never negative.
 * - A stall or retry simply stops the sample from growing, so the rate decays
 *   to 0 instead of manufacturing progress.
 *
 * All metric state is guarded by one short lock: the downloader invokes
 * [onProgress] from several segment coroutines at once (Dispatchers.IO), and
 * the sample window must not be mutated concurrently. The lock is only ever
 * held for in-memory arithmetic — never across a Room write or a notification.
 *
 * This class is intentionally free of Android/Room dependencies so it can be
 * unit tested with an injected clock (see ProgressThrottlingTest and
 * ProgressMetricsTest).
 *
 * @param throttleMs minimum interval between Room writes and notification updates
 * @param now injected clock (millis) for deterministic tests
 * @param writeProgress performs the actual Room write (suspend, called only from
 *   flush/forceFinal) with (completedSegments, downloadedBytes, speedBps, etaSeconds)
 * @param notifyProgress delivers a percent value (0-100) or -1 for indeterminate to the notification layer
 */
internal class ProgressTracker(
    private val throttleMs: Long,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val writeProgress: suspend (
        completedSegments: Int,
        downloadedBytes: Long,
        speedBps: Long,
        etaSeconds: Long,
    ) -> Unit,
    private val notifyProgress: (progressPercent: Int) -> Unit
) {

    private data class Sample(val timeMs: Long, val bytes: Long)

    /** One immutable Room-write payload (copied under the lock). */
    private data class Write(
        val completedSegments: Int,
        val downloadedBytes: Long,
        val speedBps: Long,
        val etaSeconds: Long,
    )

    companion object {
        /**
         * A rate is measured over at most this much history. Older samples are
         * dropped only once a newer one inside the window exists, so a slow
         * producer still has an anchor to measure against.
         */
        internal const val SPEED_WINDOW_MS = 10_000L

        /** Hard cap on retained samples — memory stays bounded regardless of window. */
        internal const val MAX_SAMPLES = 64
    }

    private val lock = Any()

    private var lastWriteTime = 0L
    private var lastNotifyTime = 0L
    private var latestCompleted = 0
    private var latestTotal = 0
    private var latestBytes = 0L
    private var latestSpeed = 0L
    private var latestEtaSeconds = 0L
    private var pendingWrite = false
    private val samples = ArrayDeque<Sample>()

    /** Set once the terminal write has happened; blocks all stale writes. */
    @Volatile
    private var finished = false

    /** Latest completed segment count (in-memory). */
    val completed: Int get() = synchronized(lock) { latestCompleted }

    /** Latest downloaded-byte total (in-memory, real bytes only). */
    val downloadedBytes: Long get() = synchronized(lock) { latestBytes }

    /** Latest rate over the bounded window (bytes/second; 0 = unknown/stalled). */
    val speedBps: Long get() = synchronized(lock) { latestSpeed }

    /** Latest ETA (seconds; 0 = unknown). */
    val etaSeconds: Long get() = synchronized(lock) { latestEtaSeconds }

    /** True once forceFinal has run; no further writes are permitted. */
    val isFinished: Boolean get() = finished

    /**
     * Called from the per-segment progress callback (non-suspend).
     * Updates in-memory state and emits a throttled notification update.
     * Room writes are deferred to [flush], which runs on a single bounded coroutine.
     *
     * @param downloadedBytes running total of successfully written decrypted
     *   segment bytes (resume base + this run's atomic writes)
     */
    fun onProgress(completedSegments: Int, totalSegments: Int, downloadedBytes: Long) {
        var shouldNotify = false
        var notifyPercent = -1
        synchronized(lock) {
            if (finished) return
            val currentTime = now()
            // Several segment workers call back at once (Dispatchers.IO), so the
            // cumulative byte total can be DELIVERED out of order: thread A may
            // compute 20 bytes, thread B 47 bytes, and A's callback then run
            // afterwards. Within one attempt the total only ever grows (resume
            // base + atomic writes), so a smaller value is by definition a stale
            // delivery — not a real regression. Clamping keeps the persisted
            // "downloaded bytes" from ever walking 47 → 20 backwards on screen.
            latestCompleted = completedSegments
            latestTotal = totalSegments
            latestBytes = downloadedBytes.coerceAtLeast(0L).coerceAtLeast(latestBytes)

            recordSampleLocked(currentTime, latestBytes)
            latestSpeed = computeSpeedLocked()
            latestEtaSeconds = computeEtaLocked(latestSpeed)

            pendingWrite = true

            if (currentTime - lastNotifyTime >= throttleMs) {
                lastNotifyTime = currentTime
                notifyPercent = percent(completedSegments, totalSegments)
                shouldNotify = true
            }
        }
        // Flag-gated, not value-gated: -1 is a legitimate percent (unknown total).
        if (shouldNotify) notifyProgress(notifyPercent)
    }

    /**
     * Flush the latest in-memory progress to Room if the throttle interval
     * has elapsed. Called periodically by ONE bounded coroutine launched by
     * DownloadWorker. No-op after [forceFinal].
     */
    suspend fun flush() {
        val write: Write
        synchronized(lock) {
            if (finished || !pendingWrite) return
            val currentTime = now()
            if (currentTime - lastWriteTime < throttleMs) return

            lastWriteTime = currentTime
            pendingWrite = false
            write = Write(latestCompleted, latestBytes, latestSpeed, latestEtaSeconds)
        }
        writeProgress(
            write.completedSegments,
            write.downloadedBytes,
            write.speedBps,
            write.etaSeconds,
        )
    }

    /**
     * Terminal write. Always writes exactly once, regardless of throttle state,
     * then latches [finished] so no stale in-memory value can be written later.
     *
     * The terminal row carries the real byte total this run produced but ZERO
     * rate metrics: the download is over, so a lingering speed/ETA could never
     * describe it honestly (the row mapper's DOWNLOADING-only gate is the
     * second line of defence behind this write).
     */
    suspend fun forceFinal(completedSegments: Int) {
        val write: Write
        synchronized(lock) {
            if (finished) return
            finished = true
            latestCompleted = completedSegments
            latestSpeed = 0L
            latestEtaSeconds = 0L
            pendingWrite = false
            write = Write(completedSegments, latestBytes, 0L, 0L)
        }
        writeProgress(
            write.completedSegments,
            write.downloadedBytes,
            write.speedBps,
            write.etaSeconds,
        )
    }

    // --- metric arithmetic (always called under [lock]) ---------------------

    private fun recordSampleLocked(timeMs: Long, bytes: Long) {
        samples.addLast(Sample(timeMs, bytes))
        // Keep the newest sample inside the window plus the one just outside it
        // (the anchor): a producer slower than the window still measures the
        // interval it really had, instead of collapsing to "no growth".
        while (samples.size > 2 && timeMs - samples[1].timeMs > SPEED_WINDOW_MS) {
            samples.removeFirst()
        }
        // Hard bound — a burst of segments inside one window cannot grow this.
        while (samples.size > MAX_SAMPLES) {
            samples.removeFirst()
        }
    }

    private fun computeSpeedLocked(): Long {
        if (samples.size < 2) return 0L
        val first = samples.first()
        val last = samples.last()
        val elapsedMs = last.timeMs - first.timeMs
        val gained = last.bytes - first.bytes
        // No elapsed time or no byte progress → no rate (never a fake speed).
        if (elapsedMs <= 0L || gained <= 0L) return 0L
        val bytesPerSecond = gained * 1000.0 / elapsedMs.toDouble()
        if (!bytesPerSecond.isFinite() || bytesPerSecond <= 0.0) return 0L
        return bytesPerSecond.toLong()
    }

    private fun computeEtaLocked(speedBps: Long): Long {
        // Without a usable rate there is no honest countdown.
        if (speedBps <= 0L) return 0L
        val total = latestTotal
        val completed = latestCompleted
        // Unknown denominator (totalSegments = 0) or nothing left → 0.
        if (total <= 0 || completed <= 0 || completed >= total) return 0L
        // Remaining bytes from this run's real average segment size.
        val averageSegmentBytes = latestBytes.toDouble() / completed.toDouble()
        val remainingBytes = (total - completed).toDouble() * averageSegmentBytes
        if (!remainingBytes.isFinite() || remainingBytes <= 0.0) return 0L
        val seconds = remainingBytes / speedBps.toDouble()
        if (!seconds.isFinite() || seconds < 0.0) return 0L
        return seconds.toLong()
    }

    private fun percent(completedSegments: Int, totalSegments: Int): Int =
        if (totalSegments > 0) completedSegments * 100 / totalSegments else -1
}
