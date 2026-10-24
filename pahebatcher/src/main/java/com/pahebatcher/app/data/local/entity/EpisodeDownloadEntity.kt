package com.pahebatcher.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for episode downloads.
 * Tracks individual episode download state.
 *
 * Schema and behavior notes:
 * - Added m3u8Url, userAgent, referer, segmentDir columns
 * - Removed STATE_PAUSED from canResume()
 * - STATE_PAUSED constant removed (Phase 10 replaces PAUSED with QUEUED)
 *
 * Reinstated by the final SPEC gap closure (SPEC §25 "paused"): `PAUSED` is back
 * as a first-class, user-authored state — a durable intent written by
 * `DownloadRepository.pause()` BEFORE the unique work is cancelled, and read back
 * by resume/reconcile/UI. `state` is a TEXT column, so this is a value, not a
 * schema change: no migration, and no previously persisted row can carry it.
 *
 * Run-ownership (migration v4→v5): `downloadRunId` is the persisted identity of
 * the download run that owns the row — the pause/resume race fix. See the
 * column's own KDoc for the full ownership lifecycle.
 */
@Entity(
    tableName = "episode_downloads",
    foreignKeys = [
        ForeignKey(
            entity = DownloadSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class EpisodeDownloadEntity(
    @PrimaryKey
    val episodeId: String,

    /** Parent session ID */
    val sessionId: String,

    /** Episode number */
    val episodeNumber: Float,

    /** Episode session UUID */
    val episodeSession: String,

    /** Episode title */
    val title: String,

    /** Fansub group */
    val fansub: String,

    /** Audio language */
    val audio: String,

    /** Play page URL */
    val playUrl: String,

    /** Total segments expected (0 if not yet known) */
    val totalSegments: Int = 0,

    /** Completed segments count */
    val completedSegments: Int = 0,

    /** Downloaded bytes */
    val downloadedBytes: Long = 0,

    /**
     * Episode state: QUEUED, RESOLVING, DOWNLOADING, ASSEMBLING, COMPLETED,
     * FAILED, CANCELLED, PAUSED (SPEC §25 lists paused among the states).
     */
    val state: String = "QUEUED",

    /** Current download speed (bytes per second) */
    val speed: Long = 0,

    /** Estimated time remaining (seconds) */
    val etaSeconds: Long = 0,

    /** Number of retry attempts (incremented ONLY on explicit user re-enqueue) */
    val retryCount: Int = 0,

    /** Error message if failed */
    val errorMessage: String? = null,

    /** Output file path (if completed) */
    val outputPath: String? = null,

    /** Creation timestamp */
    val createdAt: Long,

    /** Last update timestamp */
    val updatedAt: Long,

    // Phase 10 columns (added in migration v1→v2)

    /** M3U8 playlist URL (persisted after resolution, used by Worker) */
    val m3u8Url: String? = null,

    /** User-Agent header for HLS requests (persisted after resolution) */
    val userAgent: String? = null,

    /** Referer header for HLS requests (persisted after resolution) */
    val referer: String? = null,

    /** Per-episode segment directory path (filesDir/downloads/{episodeId}) */
    val segmentDir: String? = null,

    // Phase 11 columns (added in migration v2→v3, PHASE11_ARCHITECTURE_V2 §29)

    /**
     * MediaStore `content://` URI of the exported copy in `Movies/PaheBatcher/`
     * (§27/§30). NULL until exported. Pre-export rows keep `outputPath` as the
     * local source of truth (§29: both columns kept, no destructive migration).
     */
    val outputUri: String? = null,

    // Replacement-by-confirmation columns (added in migration v3→v4).

    /**
     * True EXACTLY while a user-CONFIRMED replacement run for this episode is
     * in flight (the `Replace file` action, DECISIONS 152).
     *
     * This is the persisted difference between two requests that would
     * otherwise be indistinguishable:
     *
     * * an ordinary request over `COMPLETED` + a valid export is SUPPRESSED
     *   (`DownloadRepository.suppressedOutcome` → `AlreadyDownloaded`);
     * * a confirmed replacement CASes the row out of `COMPLETED` with this flag
     *   set, so the pipeline may download despite that valid export.
     *
     * The flag is what makes the intent survive process death: the Worker reads
     * it at run start and stages every byte under `<segmentDir>/replace/`, so a
     * restarted run can never mistake the OLD export for its own result
     * (`EpisodeDownloader`'s "output already exists" short-circuit looks only in
     * the staging directory). It is cleared by the same guarded write that
     * ends the run — never by a reader, and never while a run still owns the
     * row.
     *
     * Not an output reference: [EpisodeOutputVerifier] never reads this column,
     * so the completed-output rule (§29) is untouched by replacement.
     */
    @ColumnInfo(name = "replaceRequested", defaultValue = "0")
    val replaceRequested: Boolean = false,

    /**
     * The export URI this in-flight replacement must RETIRE once — and only
     * once — the new output is committed and verified (DECISIONS 152).
     *
     * NULL in every ordinary row, and NULL for a replacement whose previous
     * export was app-private (nothing lives outside the app to retire; the
     * staged file is renamed onto `outputPath` instead).
     *
     * It stays set while `outputUri` still points at the OLD export (the usual
     * case) AND after `outputUri` has already been switched to the NEW export
     * (the window between the exporter's X4 and the retirement). That is
     * precisely why it cannot be derived from `outputUri`: a process killed in
     * that window must still be able to find — and remove — the file it was
     * replacing, without ever guessing which URI is which.
     */
    @ColumnInfo(name = "replacePriorUri")
    val replacePriorUri: String? = null,

    // Run-ownership column (added in migration v4→v5).

    /**
     * The identity of the download RUN that currently owns this row, written
     * atomically by the Worker's entry claim ([EpisodeDownloadDao.claimEntryRunWithRunId])
     * and required by every run-owned guarded write
     * ([EpisodeDownloadDao.updateStateIfOwned] and friends).
     *
     * This column exists to close the pause/resume run-ownership race: a paused
     * run's cancellation cleanup may drain long after a resumed run re-claimed
     * the row as `DOWNLOADING`, and a state-only CAS
     * (`DOWNLOADING → CANCELLED`) cannot tell the OLD run's row from the NEW
     * run's row. With the token persisted, the stale write's own
     * `downloadRunId = :runId` guard atomically refuses it, while the CURRENT
     * run's identical write still lands.
     *
     * Ownership lifecycle: SET by the claim that wins `→ DOWNLOADING`; OVERWRITTEN
     * by the next claim (a restart, a resume, a reconciler re-enqueue re-claim);
     * never read, written or cleared by user actions (pause/resume/cancel/retry)
     * or by recovery actors (reconciler, resolver) — their state-only CAS is
     * already "newest actor wins" and must never be blocked by a run token.
     * Never cleared on terminal writes: a stale token on a finished row is dead
     * data that the next claim overwrites, and leaving it keeps every terminal
     * write a single atomic statement.
     */
    @ColumnInfo(name = "downloadRunId")
    val downloadRunId: String? = null
) {
    companion object {
        const val STATE_QUEUED = "QUEUED"
        const val STATE_RESOLVING = "RESOLVING"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_ASSEMBLING = "ASSEMBLING"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"

        /**
         * SPEC §25 paused state, SPEC §20 Pause action. Written only by
         * `DownloadRepository.pause()` — never derived, never a placeholder.
         */
        const val STATE_PAUSED = "PAUSED"

        /**
         * DATA-013: hoisted out of [isTerminal] so the per-row predicate does not
         * allocate a fresh list on every call.
         */
        private val TERMINAL_STATES = setOf(STATE_COMPLETED, STATE_FAILED, STATE_CANCELLED)

        /** DATA-013: same hoisting for [canResume]. */
        private val RESUMABLE_STATES = setOf(STATE_FAILED, STATE_QUEUED, STATE_PAUSED)

        /**
         * SPEC §20 "Pause" — the states a pause may be requested from.
         *
         * `RESOLVING` and `DOWNLOADING` are the working states; `QUEUED` is a
         * worker-ready row (its work may not have started yet, so there is
         * nothing running to stop but the intent is still meaningful).
         *
         * Deliberately NOT pausable:
         *
         * - `ASSEMBLING` — muxing cannot be interrupted halfway without risking a
         *   partial MP4 being mistaken for an output later; it finishes on its own.
         * - `PAUSED` itself (nothing to pause twice) and every terminal state.
         */
        val PAUSABLE_STATES = listOf(STATE_QUEUED, STATE_RESOLVING, STATE_DOWNLOADING)
    }

    /**
     * Get progress as percentage (0-100).
     */
    fun progressPercent(): Int {
        if (totalSegments <= 0) return 0
        return ((completedSegments.toFloat() / totalSegments) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Check if episode is in a terminal state.
     *
     * DATA-013: a [Set] instead of `listOf(...)` — [isTerminal] runs inside
     * per-row loops (session summaries, dashboard mapping), and a fresh list
     * allocation per call is avoidable; `in` on a set is O(1).
     */
    fun isTerminal(): Boolean {
        return state in TERMINAL_STATES
    }

    /**
     * Check if episode can be resumed.
     *
     * FAILED, QUEUED and PAUSED rows are all resumable. PAUSED was once removed
     * (there was then no way to write one), then reinstated once pausing became a
     * first-class user action, so a paused row is resumable again alongside FAILED
     * and QUEUED.
     */
    fun canResume(): Boolean {
        return state in RESUMABLE_STATES
    }

    /**
     * Whether the user may pause this row (SPEC §20) — see [PAUSABLE_STATES].
     */
    fun isPausable(): Boolean = state in PAUSABLE_STATES
}
