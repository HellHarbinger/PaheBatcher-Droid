package com.pahebatcher.app.data.export

import android.net.Uri
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.sync.Mutex

/**
 * The export-surface contract consumed by the
 * DownloadWorker success path and startup reconciliation.
 *
 * Implementations:
 *  - [MediaStoreExporter] — the Phase 11 `Movies/PaheBatcher/` destination
 *    (§30.1 X1–X6, unchanged);
 *  - [ExportDestinationRouter] — production wiring: SAF tree when configured
 *    and usable, MediaStore default otherwise (DECISIONS 92.2).
 *
 * The Worker and the reconciler depend on this interface only, so a
 * destination change never touches their (Phase 11/12) logic.
 */
interface EpisodeExporter {

    /**
     * One idempotent export attempt for [episode]. Never deletes the local
     * source before the destination has been byte-verified (X6 rule, §30.4);
     * [ExportOutcome.Failed] always keeps the local file.
     */
    suspend fun export(episode: EpisodeDownloadEntity): ExportOutcome

    /**
     * Delete export-temporary rows orphaned by a previous process (Phase 11
     * §28 stale-`IS_PENDING` sweep on the MediaStore path; the SAF path has no
     * pending state — its `.pb.part` cleanup runs at export start,
     * DECISIONS 104.3).
     *
     * @return number of rows deleted.
     */
    suspend fun sweepStalePending(olderThanEpochMs: Long): Int

    /**
     * §3.1 (DECISIONS 157): the surviving-EXPORT probe — does a live export
     * already exist at this episode's DESTINATION, and at which URI?
     *
     * Two consumers ask this one question about one destination, so the app can
     * never hold two answers about one row (the Slice B M-1 rule):
     *
     * * The submission gate (`DownloadRepository`) treats a non-null answer as
     *   the same "already downloaded" no-op as a COMPLETED row with a present
     *   output — whether the row is absent (app-private state destroyed) or its
     *   output pointers are severed — instead of re-downloading bytes whose
     *   observable result already exists.
     * * The startup reconciler (`DownloadStateReconciler`) uses the returned URI
     *   to REPAIR a COMPLETED row whose remembered pointer no longer resolves,
     *   so the row the gate called "already downloaded" is never later demoted
     *   to `FAILED ("file missing")`.
     *
     * The arguments mirror the exact destination identity the export computes —
     * the audio-suffixed leaf name within the per-anime relative directory — so
     * the probe and the export can never disagree about which file is THIS
     * episode's (and a different audio variant's file can never match).
     *
     * The default `null` is the conservative "no live destination detected (or
     * this destination cannot be probed) → the caller proceeds exactly as
     * today", so test doubles and unprobed destinations keep the previous
     * behaviour. Real destinations override it with a real probe. Callers must
     * additionally treat an exception thrown by any implementation as "cannot
     * be probed" (admit / demote as if `null`): a probe may only ever
     * UNDER-suppress, never block a legitimate submission.
     *
     * @return the surviving export's `content://` URI, or null when no live
     *   destination is present (or this destination is not probed).
     */
    suspend fun destinationOutput(
        episodeNumber: Float,
        title: String,
        audio: String,
        animeTitle: String?,
        sessionId: String,
    ): Uri? = null
}

/**
 * Process-wide serialization of export/sweep critical sections across EVERY
 * destination and every constructing site (Worker success path, startup
 * reconciliation, AppContainer). One global lock keeps the §30.1 sequence
 * non-interleavable when the router can pick either destination for the same
 * episode, and matches §30.4's "sequential per-episode" bound (AGENTS §12).
 */
internal object ExportOperationLock {
    val mutex = Mutex()
}
