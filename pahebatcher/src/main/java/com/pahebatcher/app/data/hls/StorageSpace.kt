package com.pahebatcher.app.data.hls

import java.io.File
import java.util.Locale

/**
 * Final-audit H2 — the ONE place insufficient storage is measured and
 * recognised (SPEC §13 "Handle: insufficient storage", SPEC §26 error type
 * `DiskFull`).
 *
 * Two independent mechanisms, both honest:
 *
 * 1. **Detection at the origin** — [isNoSpaceLeft] is called from the segment
 *    write catch site in `EpisodeDownloader`, which then constructs the typed
 *    [DownloadErrorClass.DISK_FULL]. Android has no typed out-of-space
 *    exception: the kernel reports `ENOSPC` as text on the [java.io.IOException]
 *    (and the platform may wrap it), so this object is the SINGLE place that
 *    text is read. Every other component reads the typed class and never a
 *    message (Phase 14 §14 doctrine).
 * 2. **A pre-flight refusal** — [estimatedRemainingBytes] + [isInsufficient]
 *    let a resumed run stop BEFORE spending bandwidth on segments the volume
 *    demonstrably cannot hold, instead of discovering the full disk at write
 *    time.
 *
 * Free space comes from [File.usableSpace] (the Java/Android API the platform
 * itself uses — no new dependency, AGENTS §17). It returns `0` both for a
 * genuinely empty volume and when the figure is unavailable, so `0` is treated
 * as *unknown* and never refuses a run: detection (1) is the backstop for the
 * truly-full case. Only a positive figure smaller than the estimate refuses.
 */
object StorageSpace {

    private const val NO_SPACE_LEFT = "No space left on device"

    /** errno `ENOSPC` — what an OS-level failure reports when wrapped. */
    private const val ENOSPC = "ENOSPC"

    /** Bound on the cause-chain walk so a self-referential chain cannot spin. */
    private const val MAX_CAUSE_DEPTH = 8

    /**
     * Did this failure (or anything it was wrapped in) run out of space?
     *
     * Walks the cause chain because Android's filesystem layers wrap the
     * original [java.io.IOException]; an identity-based `seen` set keeps a
     * self-referential chain from looping.
     */
    fun isNoSpaceLeft(error: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            if (isNoSpaceLeft(current.message)) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Text-only overload for origins that report the reason as a plain string —
     * FFmpeg's stderr is the only signal an assembly failure carries (no
     * exception, no errno), and that construction site must still be able to
     * report `DiskFull` (SPEC §26) instead of a generic muxing error.
     */
    fun isNoSpaceLeft(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        return reason.contains(NO_SPACE_LEFT, ignoreCase = true) || reason.contains(ENOSPC, ignoreCase = true)
    }

    /**
     * Bytes free on the volume holding [directory], or `0` when the platform
     * will not report a figure (never negative — callers treat `<= 0` as
     * unknown, see the object doc).
     */
    fun availableBytes(directory: File): Long = try {
        directory.usableSpace
    } catch (error: Exception) {
        0L
    }

    /**
     * Best-effort bytes still needed for the [pendingCount] segments not yet on
     * disk, derived from the [completedBytes] of the [completedCount] segments
     * that are.
     *
     * Returns `0` — "no estimate, do not gate" — whenever the sample is
     * missing: a fresh run has no completed segment to average from, and a run
     * with no pending work needs nothing. Segment sizes differ, so the number
     * is an estimate; [isInsufficient] therefore refuses only on a strict
     * shortfall, and the ENOSPC detection above still catches an underestimate.
     */
    fun estimatedRemainingBytes(completedCount: Int, completedBytes: Long, pendingCount: Int): Long {
        if (completedCount <= 0 || pendingCount <= 0 || completedBytes <= 0L) return 0L
        val average = completedBytes / completedCount
        if (average <= 0L) return 0L
        return average * pendingCount
    }

    /**
     * Is [availableBytes] genuinely short of [requiredBytes]?
     *
     * `false` when either side is unknown (`<= 0`) — a missing measurement must
     * never be read as "full", and a missing estimate must never block work.
     */
    fun isInsufficient(availableBytes: Long, requiredBytes: Long): Boolean =
        requiredBytes > 0L && availableBytes > 0L && availableBytes < requiredBytes

    /** Human-readable pair for the persisted/visible error message (SPEC §26). */
    fun describe(availableBytes: Long, requiredBytes: Long): String =
        "${formatBytes(availableBytes)} free, about ${formatBytes(requiredBytes)} needed"

    // Locale.US (lint DefaultLocale): the pair is persisted + shown verbatim, so
    // the decimal separator must not change with the device locale.
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
