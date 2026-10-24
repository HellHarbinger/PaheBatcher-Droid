package com.pahebatcher.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for episode download operations.
 * Tracks individual episode download state.
 *
 * Methods and behavior notes:
 * - updateError() no longer increments retryCount
 * - Added countActive(), clearStreamInfo()
 * - Added updateStateForResume(), getNextQueuedWithM3u8()
 * - Added getEpisodesForReconciliation(), getTotalEpisodeCount()
 *
 * Later additions:
 * - Added updateTotalSegments() (§16.2 one-time write-on-change)
 * - Added updateOutputUri() (§30.1 step 5)
 * - Added observeAllOrdered() (§13 dashboard query)
 *
 * 11.5E-7 (DECISIONS 75):
 * - Added beginRetry() (atomic terminal-row claim: state→RESOLVING, retryCount + 1,
 *   stale credentials/error/outputUri cleared) and updateErrorIfState() (CAS error
 *   write that never converts a newer state into FAILED)
 *
 * retryCount: incremented by [beginRetry] (production, user Retry) only — never by
 * [updateError]. The pre-11.5E-7 `incrementRetryCount()` V10 §12 path was deleted as
 * dead code in Phase 11.5 item (c): its only caller ever was a test.
 *
 * Run-ownership (v4→v5, pause/resume race fix):
 * - [claimEntryRunWithRunId] stamps the per-run token in the claim UPDATE;
 * - [updateStateIfOwned], [updateErrorIfOwned], [updateCompletedIfOwned],
 *   [finishReplacementIfOwned], [restoreCompletedForUncommittedReplacementIfOwned]
 *   are the run-owned variants of the state-only CAS helpers above — the SAME
 *   atomic statement also requires `downloadRunId = :runId`, so a stale run
 *   can never mutate a row a newer run re-claimed;
 * - user/recovery writes keep the unguarded statements (newest actor wins).
 */
@Dao
interface EpisodeDownloadDao {

    /**
     * Get episode by ID.
     */
    @Query("SELECT * FROM episode_downloads WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getById(episodeId: String): EpisodeDownloadEntity?

    /**
     * Get episodes for a session.
     */
    @Query("SELECT * FROM episode_downloads WHERE sessionId = :sessionId ORDER BY episodeNumber")
    fun getBySessionId(sessionId: String): Flow<List<EpisodeDownloadEntity>>

    /**
     * Get episodes by state.
     */
    @Query("SELECT * FROM episode_downloads WHERE state = :state")
    fun getByState(state: String): Flow<List<EpisodeDownloadEntity>>

    /**
     * Insert or update episode.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(episode: EpisodeDownloadEntity)

    /**
     * Atomic TERMINAL-row re-queue claim (Slice B closure, review findings
     * F2 and F7).
     *
     * ONE guarded UPDATE that replaces the read → `upsert` → `updateState`
     * triple `DownloadRepository.enqueue()` used to perform. That triple was
     * the only UNGUARDED write in an otherwise compare-and-set codebase, and it
     * opened three real windows:
     *
     * * **F2** — two submissions both observing a `FAILED` row both blind-wrote
     *   it, then BOTH won a hand-off CAS (`RESOLVING → QUEUED` for the first,
     *   `QUEUED → QUEUED` for the second), so both reported `Accepted` for one
     *   download. Here only the first writer moves the row; every loser gets `0`
     *   and must re-derive its outcome instead of claiming work.
     * * **F7a** — `upsert(QUEUED)` followed by `updateState(RESOLVING)` left the
     *   row briefly `QUEUED`, which `cancelIfNotTerminal` accepts. A Cancel
     *   landing in that window was then forced straight back to `RESOLVING` by
     *   the second blind write, so a Worker could be registered AFTER the user
     *   cancelled. Writing `RESOLVING` directly removes that intermediate state
     *   entirely — the same reasoning `createRow`/DECISIONS 146.5 applies.
     * * **F7b** — a concurrent `beginRetry` claim (`retryCount + 1`, cleared
     *   credentials, cleared `outputUri`) was silently overwritten by the blind
     *   `upsert` of a fresh entity carrying `retryCount = 0`.
     *
     * The SET list is exactly the columns the old `upsert` reset (a submission
     * always hands `enqueue()` a freshly built entity), so the WINNING caller's
     * observable end state is byte-for-byte what it was before; only the guard
     * is new. The caller must pass the observed terminal state as
     * [expectedState], so a row that changed underneath is refused rather than
     * forced.
     *
     * Returns `1` when THIS call claimed the row, `0` when it was no longer in
     * [expectedState] — another submitter, a Cancel, or a retry won first.
     */
    @Query(
        "UPDATE episode_downloads SET " +
            "sessionId = :sessionId, episodeNumber = :episodeNumber, " +
            "episodeSession = :episodeSession, title = :title, fansub = :fansub, " +
            "audio = :audio, playUrl = :playUrl, " +
            "state = 'RESOLVING', " +
            "totalSegments = 0, completedSegments = 0, downloadedBytes = 0, " +
            "speed = 0, etaSeconds = 0, retryCount = 0, " +
            "errorMessage = NULL, outputPath = NULL, outputUri = NULL, " +
            "m3u8Url = NULL, userAgent = NULL, referer = NULL, segmentDir = NULL, " +
            "replaceRequested = 0, " +
            "createdAt = :createdAt, updatedAt = :timestamp " +
            "WHERE episodeId = :episodeId AND state = :expectedState",
    )
    suspend fun claimTerminalRequeue(
        episodeId: String,
        expectedState: String,
        sessionId: String,
        episodeNumber: Float,
        episodeSession: String,
        title: String,
        fansub: String,
        audio: String,
        playUrl: String,
        createdAt: Long,
        timestamp: Long = System.currentTimeMillis(),
    ): Int

    /**
     * Update episode progress.
     *
     * SPEC §25 carries the full metric set: the segment count, the byte total
     * the downloader really wrote, the measured rate and the derived ETA. `0`
     * speed/ETA always means "unknown", never "measured as zero".
     */
    @Query("UPDATE episode_downloads SET completedSegments = :completedSegments, downloadedBytes = :downloadedBytes, speed = :speed, etaSeconds = :etaSeconds, updatedAt = :timestamp WHERE episodeId = :episodeId")
    suspend fun updateProgress(episodeId: String, completedSegments: Int, downloadedBytes: Long, speed: Long, etaSeconds: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * Update episode state.
     */
    @Query("UPDATE episode_downloads SET state = :state, updatedAt = :timestamp WHERE episodeId = :episodeId")
    suspend fun updateState(episodeId: String, state: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Update episode error.
     * Does NOT increment retryCount — retryCount increments only via
     * [beginRetry] (production, from [DownloadRepository.reEnqueue]), never here.
     */
    @Query("UPDATE episode_downloads SET state = 'FAILED', errorMessage = :errorMessage, updatedAt = :timestamp WHERE episodeId = :episodeId")
    suspend fun updateError(episodeId: String, errorMessage: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Update episode completion.
     */
    @Query("UPDATE episode_downloads SET state = 'COMPLETED', outputPath = :outputPath, updatedAt = :timestamp WHERE episodeId = :episodeId")
    suspend fun updateCompleted(episodeId: String, outputPath: String, timestamp: Long = System.currentTimeMillis())

    /**
     * State-guarded completion write (11.5E item (e), reviewer M5).
     *
     * Same effect as [updateCompleted] but ONLY while the row is still in
     * [expectedState]: a Worker whose completion write races a newer state
     * change (the user's Cancel, or the reconciler's `DOWNLOADING → QUEUED`
     * re-enqueue) must never stamp `COMPLETED` over it — and must skip the
     * MediaStore export for a row it no longer owns. Returns `0` when the guard
     * refused the write.
     */
    @Query("UPDATE episode_downloads SET state = 'COMPLETED', outputPath = :outputPath, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState")
    suspend fun updateCompletedIfState(episodeId: String, expectedState: String, outputPath: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Run-owned completion write (pause/resume run-ownership race fix).
     *
     * Same effect as [updateCompletedIfState] plus the atomic
     * `downloadRunId = :runId` ownership requirement: only the run that still
     * owns the row may claim `COMPLETED` — and therefore only it may reach the
     * MediaStore export that follows this CAS. A stale run whose completion
     * arrives after a newer run re-claimed the row loses with `0` (the caller
     * skips the export exactly as the state-guarded refusal already taught it).
     */
    @Query("UPDATE episode_downloads SET state = 'COMPLETED', outputPath = :outputPath, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState AND downloadRunId = :runId")
    suspend fun updateCompletedIfOwned(episodeId: String, expectedState: String, outputPath: String, runId: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Delete episode.
     */
    @Query("DELETE FROM episode_downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)

    // === Phase 10 additions ===

    /**
     * Count active episodes (consuming network/disk resources).
     * Used by Worker to check maxParallel soft limit.
     * Only counts DOWNLOADING and ASSEMBLING.
     */
    @Query("SELECT COUNT(*) FROM episode_downloads WHERE state IN ('DOWNLOADING', 'ASSEMBLING')")
    suspend fun countActive(): Int

    /**
     * Get total episode count (any state).
     * Used by reconciliation fast-path: skip if no episodes exist at all.
     */
    @Query("SELECT COUNT(*) FROM episode_downloads")
    suspend fun getTotalEpisodeCount(): Int

    /**
     * Episode rows under one session — Python's `ep_count`
     * (`sessions.py SessionManager.get_sessions` counted `Ep_*` folders per
     * session; Room counts the same rows the engine writes).
     * Query-only: no schema change.
     */
    @Query("SELECT COUNT(*) FROM episode_downloads WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    /**
     * Episodes whose stream is RESOLVED and staged
     * but which no Worker has started yet — Python's `download_queue` depth,
     * whose capacity is `resolve_ahead + max_parallel` (`downloader.py:205-208`).
     *
     * `state = 'QUEUED' AND m3u8Url IS NOT NULL` is exactly the "resolved, not
     * started" set: a QUEUED row without stream info has never been resolved,
     * and `DOWNLOADING`/`ASSEMBLING` rows are already out of the staging queue.
     * Query-only: no schema change.
     */
    @Query("SELECT COUNT(*) FROM episode_downloads WHERE state = 'QUEUED' AND m3u8Url IS NOT NULL")
    suspend fun countResolvedPending(): Int

    /**
     * Clear stream info (m3u8Url, userAgent, referer).
     * Called when resetting an episode for fresh resolution.
     * Called in both reEnqueue() and RESOLVING reset.
     */
    @Query("UPDATE episode_downloads SET m3u8Url = NULL, userAgent = NULL, referer = NULL, updatedAt = :timestamp WHERE episodeId = :episodeId")
    suspend fun clearStreamInfo(episodeId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Atomic state transition for recovery.
     * Only transitions if current state matches expectedState.
     * Used by reconciliation to safely transition DOWNLOADING → QUEUED.
     *
     *
     * 11.5E-7: returns the number of rows transitioned so callers can use it as
     * an atomic CLAIM — `1` means this caller won the transition, `0` means the
     * row was no longer in `expectedState` (someone else won, or the user
     * changed the state first). `DownloadRepository.reEnqueue()` uses exactly
     * that to ensure a double-tapped Retry claims the row only once.
     *
     * SPEC §25 stale-rate guard (1 of 2 — the row mapper's DOWNLOADING-only gate
     * is 2 of 2): a rate belongs to the state it was measured in, so the SAME
     * statement that moves the row clears `speed`/`etaSeconds`. `0` here always
     * means "no measurement applies from now on", never "measured as zero", and
     * because it is one atomic UPDATE there is no window in which the row can
     * already advertise the new state while still carrying the previous run's
     * rate. Byte and segment progress are deliberately untouched — they are real
     * persisted state that resumes from exactly where it stopped.
     */
    @Query("UPDATE episode_downloads SET state = :newState, speed = 0, etaSeconds = 0, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState")
    suspend fun updateStateForResume(episodeId: String, expectedState: String, newState: String, timestamp: Long = System.currentTimeMillis()): Int

    // === Run-ownership guards (pause/resume run-ownership race fix, v4→v5) ===
    //
    // A download RUN is one DownloadWorker.doWork() execution. It claims the row
    // with [claimEntryRunWithRunId], which stamps a fresh per-run token into
    // `downloadRunId` in the SAME atomic UPDATE that moves the row to
    // DOWNLOADING. Every later write the run issues that asserts "this row is
    // still mine" goes through the *IfOwned variants below, whose WHERE clause
    // requires `downloadRunId = :runId` atomically with the state guard — so a
    // stale run (a paused/cancelled worker whose cancellation cleanup drained
    // past a resume) can never mutate a row a newer run has re-claimed, while
    // the CURRENT run's identical write still lands.
    //
    // Deliberately NOT run-guarded (unchanged state-only CAS): every user
    // action (pause/resume/cancel/retry/replace) and every recovery actor
    // (reconciler, resolver) — those are "newest actor wins" by construction and
    // must never be blocked by a run token. They also never clear the token: it
    // dies with the next claim.

    /**
     * The ONE run-claim statement: atomically move [expectedState] → `DOWNLOADING`
     * and stamp [runId] as the row's new owner (SPEC §25 stale-rate guard
     * unchanged — `speed`/`etaSeconds` cleared in the same statement; segment
     * and byte progress deliberately preserved).
     *
     * Because the token is written by the SAME UPDATE that claims the state,
     * there is no window in which a row is `DOWNLOADING` on behalf of one run
     * while carrying another run's token.
     *
     * Returns `1` when THIS run claimed the row, `0` when the state guard
     * refused it (exactly the [updateStateForResume] contract).
     */
    @Query("UPDATE episode_downloads SET state = 'DOWNLOADING', downloadRunId = :runId, speed = 0, etaSeconds = 0, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState")
    suspend fun claimEntryRunWithRunId(episodeId: String, expectedState: String, runId: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Run-owned state transition: [updateStateForResume] plus the atomic
     * `downloadRunId = :runId` ownership requirement.
     *
     * Used by the Worker for every state change it makes on a row it owns —
     * the terminal CANCELLED write after a cancellation, the `DOWNLOADING →
     * ASSEMBLING` remuxing claim (final-audit H4), the auto-resume park
     * (`DOWNLOADING → QUEUED`), and the terminal `X → CANCELLED`/`X → FAILED`
     * attempts. A stale run whose token no longer matches loses with `0` and
     * the newer run's row is left exactly as it is.
     *
     * Returns `1` when the owning transition landed, `0` when the state guard
     * OR the ownership guard refused it.
     */
    @Query("UPDATE episode_downloads SET state = :newState, speed = 0, etaSeconds = 0, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState AND downloadRunId = :runId")
    suspend fun updateStateIfOwned(episodeId: String, expectedState: String, newState: String, runId: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Atomic retry claim (11.5E-7, §24.1).
     *
     * ONE guarded UPDATE so `DownloadRepository.reEnqueue()` both claims the
     * terminal row exactly once (a double-tapped Retry loses this claim and
     * returns before touching the resolver) and performs every claim-time
     * mutation atomically:
     *
     * * `state → RESOLVING` (the retry is in foreground resolution);
     * * `retryCount + 1` (only the winning attempt counts);
     * * stale resolved credentials cleared (`m3u8Url`/`userAgent`/`referer`) —
     *   never `playUrl`, `segmentDir`, progress or `outputPath`;
     * * the stale `errorMessage` cleared (the old reason must not outlive the
     *   attempt it describes);
     * * the stale `outputUri` cleared — a re-downloaded episode must not keep
     *   the previous export's URI, because §29 validation prefers `outputUri`
     *   over `outputPath` and would demote the FRESH download to `FAILED`
     *   ("file missing") at the next startup. The referenced MediaStore file
     *   is NOT touched — only this row's reference.
     *
     * Returns `1` when the claim won, `0` when the row was no longer in
     * [expectedState] (another retry or a state change won).
     */
    @Query("UPDATE episode_downloads SET state = 'RESOLVING', retryCount = retryCount + 1, m3u8Url = NULL, userAgent = NULL, referer = NULL, errorMessage = NULL, outputUri = NULL, replaceRequested = 0, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState")
    suspend fun beginRetry(episodeId: String, expectedState: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * `Replace file` (DECISIONS 152) — the atomic CONFIRMED-replacement claim.
     *
     * The exact counterpart of [beginRetry] for a user who explicitly asked to
     * replace a valid export, and the reason the ordinary suppression guard may
     * be bypassed exactly once:
     *
     * * `state → RESOLVING` through the same CAS shape the ordinary pipeline
     *   uses, so `resolveComplete`'s `RESOLVING → QUEUED` hand-off is
     *   unchanged and a second confirmation simply loses this claim;
     * * `replaceRequested = 1` — the persisted intent that tells the Worker to
     *   stage under `<segmentDir>/replace/` and that survives process death;
     * * `replacePriorUri = :replacePriorUri` — the export to retire after the
     *   new output is verified (`NULL` when the previous export is app-private);
     * * `retryCount + 1`, stale credentials/error and this run's progress
     *   reset — the same claim-time cleanup [beginRetry] performs;
     * * **`outputPath` and `outputUri` are deliberately KEPT.** They are the
     *   old export, and the old export must stay referenced and untouched for
     *   the whole replacement. That is the entire difference from
     *   [claimTerminalRequeue]/[beginRetry], which null them because their
     *   re-queue has nothing to preserve.
     *
     * The `replaceRequested = 0` guard makes the claim idempotent: a repeated
     * notification tap or a second confirmation returns `0` and never starts a
     * competing run.
     *
     * Returns `1` when THIS call claimed the row, `0` when it was no longer in
     * [expectedState] or a replacement was already in flight.
     */
    @Query(
        "UPDATE episode_downloads SET " +
            "state = 'RESOLVING', " +
            "replaceRequested = 1, replacePriorUri = :replacePriorUri, " +
            "retryCount = retryCount + 1, " +
            "totalSegments = 0, completedSegments = 0, downloadedBytes = 0, " +
            "speed = 0, etaSeconds = 0, " +
            "m3u8Url = NULL, userAgent = NULL, referer = NULL, errorMessage = NULL, " +
            "updatedAt = :timestamp " +
            "WHERE episodeId = :episodeId AND state = :expectedState AND replaceRequested = 0",
    )
    suspend fun beginReplacement(
        episodeId: String,
        expectedState: String,
        replacePriorUri: String?,
        timestamp: Long = System.currentTimeMillis(),
    ): Int

    /**
     * `Replace file` — the guarded COMMIT write for a replacement.
     *
     * Same effect as [updateCompletedIfState] plus `replaceRequested = 0`, and
     * **`replacePriorUri` is deliberately KEPT**: the new output is now
     * referenced, so the old export still has to be retired by the exporter
     * (or by the next reconciliation pass if this process dies first). Clearing
     * it here would leak the file this replacement exists to remove.
     *
     * Returns `1` when this call landed, `0` when a newer state won — in which
     * case the caller must skip the export exactly as the ordinary path does.
     */
    @Query(
        "UPDATE episode_downloads SET state = 'COMPLETED', outputPath = :outputPath, " +
            "replaceRequested = 0, updatedAt = :timestamp " +
            "WHERE episodeId = :episodeId AND state = :expectedState",
    )
    suspend fun finishReplacement(
        episodeId: String,
        expectedState: String,
        outputPath: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Int

    /**
     * Run-owned COMMIT write for a replacement (pause/resume run-ownership race
     * fix).
     *
     * Same effect as [finishReplacement] plus the atomic
     * `downloadRunId = :runId` ownership requirement: only the run that still
     * owns the row may commit a replacement — a stale run must never commit its
     * staged output over a row a newer run has re-claimed. The caller's refusal
     * path (rows = 0) already skips the export and the retirement untouched.
     */
    @Query(
        "UPDATE episode_downloads SET state = 'COMPLETED', outputPath = :outputPath, " +
            "replaceRequested = 0, updatedAt = :timestamp " +
            "WHERE episodeId = :episodeId AND state = :expectedState AND downloadRunId = :runId",
    )
    suspend fun finishReplacementIfOwned(
        episodeId: String,
        expectedState: String,
        outputPath: String,
        runId: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Int

    /**
     * `Replace file` — end a run that produced NO verified new output, so the
     * row returns to the `COMPLETED` state that still describes its SURVIVING
     * export.
     *
     * This is the shared "the replacement did not commit" write: it is used by
     * the Worker's failure paths, by a Cancel of an in-flight replacement, and
     * by a re-resolution that was torn down mid-way. The row returns to
     * `COMPLETED` because that is the truth — nothing was replaced, so the
     * completed download the user already had is exactly what the row
     * describes again. Both replacement columns are cleared in the same
     * statement: there is nothing to stage and nothing to retire, because
     * `outputUri` still points at the surviving old export.
     *
     * CAS-guarded on [expectedState] exactly like [updateErrorIfState]: a newer
     * state (a Cancel, a concurrent terminal write) always wins and is never
     * stamped over.
     *
     * Returns `1` when this call restored the row, `0` otherwise.
     */
    @Query(
        "UPDATE episode_downloads SET state = 'COMPLETED', errorMessage = NULL, " +
            "speed = 0, etaSeconds = 0, " +
            "replaceRequested = 0, replacePriorUri = NULL, updatedAt = :timestamp " +
            "WHERE episodeId = :episodeId AND state = :expectedState",
    )
    suspend fun restoreCompletedForUncommittedReplacement(
        episodeId: String,
        expectedState: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Int

    /**
     * Run-owned "the replacement did not commit" write (pause/resume
     * run-ownership race fix).
     *
     * Same effect as [restoreCompletedForUncommittedReplacement] plus the atomic
     * `downloadRunId = :runId` ownership requirement: only the run that still
     * owns the row may end an in-flight replacement on it. The user-facing
     * Cancel of a replacement keeps using the unguarded statement — the user is
     * a newer actor than any run.
     */
    @Query(
        "UPDATE episode_downloads SET state = 'COMPLETED', errorMessage = NULL, " +
            "speed = 0, etaSeconds = 0, " +
            "replaceRequested = 0, replacePriorUri = NULL, updatedAt = :timestamp " +
            "WHERE episodeId = :episodeId AND state = :expectedState AND downloadRunId = :runId",
    )
    suspend fun restoreCompletedForUncommittedReplacementIfOwned(
        episodeId: String,
        expectedState: String,
        runId: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Int

    /**
     * `Replace file` — clear the in-flight intent on a row that reached an
     * ordinary terminal state while a replacement was still marked.
     *
     * Guarded on `replaceRequested = 1` so it can never clear a COMPLETED
     * row's pending [replacePriorUri] retirement — that column only ever dies
     * with the export it describes.
     */
    @Query(
        "UPDATE episode_downloads SET replaceRequested = 0, replacePriorUri = NULL, " +
            "updatedAt = :timestamp WHERE episodeId = :episodeId AND replaceRequested = 1",
    )
    suspend fun clearReplacementIntent(episodeId: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * `Replace file` — drop only the retirement reference after the new export
     * has been committed and the old one removed.
     *
     * `replaceRequested` is untouched: this runs after the commit write has
     * already cleared it, and clearing it again here would be a second writer
     * for one flag.
     */
    @Query("UPDATE episode_downloads SET replacePriorUri = NULL, updatedAt = :timestamp WHERE episodeId = :episodeId")
    suspend fun clearReplacePriorUri(episodeId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * State-guarded failure write (11.5E-7).
     *
     * Same effect as [updateError] but ONLY while the row is still in
     * [expectedState]: a resolution failure that lands after the user's Cancel
     * (or any other state change) must not overwrite the newer state — a
     * cancellation is never converted into a failure. Returns `0` when the
     * guard refused the write.
     */
    @Query("UPDATE episode_downloads SET state = 'FAILED', errorMessage = :errorMessage, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState")
    suspend fun updateErrorIfState(episodeId: String, expectedState: String, errorMessage: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Run-owned failure write (pause/resume run-ownership race fix).
     *
     * Same effect as [updateErrorIfState] plus the atomic
     * `downloadRunId = :runId` ownership requirement: a run may only record its
     * own failure on a row it still owns. A stale run that fails after a newer
     * run re-claimed the row loses with `0` — the newer run's state (and its own
     * eventual failure or completion) always wins.
     */
    @Query("UPDATE episode_downloads SET state = 'FAILED', errorMessage = :errorMessage, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState AND downloadRunId = :runId")
    suspend fun updateErrorIfOwned(episodeId: String, expectedState: String, errorMessage: String, runId: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Terminal-guarded cancellation write (final-audit M1).
     *
     * Same effect a blind `updateState(..., 'CANCELLED')` had — any non-terminal
     * row (QUEUED / RESOLVING / DOWNLOADING / ASSEMBLING / PAUSED) really does
     * become CANCELLED, so `Cancel` on `PAUSED` still discards the pause exactly
     * as the §17.1 action matrix promises — but the three TERMINAL states can
     * never be flipped to CANCELLED:
     *
     * * `COMPLETED` → `CANCELLED` was reachable through the plain statement
     *   whenever a Cancel raced the Worker's completion CAS (the user tapped
     *   Cancel on a DOWNLOADING row; the last bytes landed first). The finished
     *   episode then presented as cancelled while its MP4 stayed on disk.
     * * `FAILED` → `CANCELLED` would erase the persisted failure reason.
     * * `CANCELLED` → `CANCELLED` is a no-op double tap.
     *
     * Returns `1` when this call really cancelled the row, `0` when the guard
     * refused the write (terminal row, or no such row).
     *
     * `speed`/`etaSeconds` are cleared in the same statement for the same reason
     * [updateStateForResume] clears them: a rate never outlives the state it was
     * measured in (SPEC §25 stale-rate guard).
     */
    @Query("UPDATE episode_downloads SET state = 'CANCELLED', speed = 0, etaSeconds = 0, updatedAt = :timestamp WHERE episodeId = :episodeId AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun cancelIfNotTerminal(episodeId: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Get next queued episode with persisted M3U8 URL.
     * Used by Worker completion trigger to schedule deferred episodes.
     *
     */
    @Query("SELECT * FROM episode_downloads WHERE state = 'QUEUED' AND m3u8Url IS NOT NULL ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextQueuedWithM3u8(): EpisodeDownloadEntity?

    /**
     * Get ALL queued episodes with persisted M3U8 URL, oldest first.
     * the deferred trigger
     * iterates these candidates to find the first whose unique work is not
     * unfinished, so a parked row (in backoff) never blocks later rows.
     */
    @Query("SELECT * FROM episode_downloads WHERE state = 'QUEUED' AND m3u8Url IS NOT NULL ORDER BY createdAt ASC")
    suspend fun getQueuedWithM3u8(): List<EpisodeDownloadEntity>

    /**
     * Get all episodes that may need reconciliation.
     * Includes non-terminal episodes AND COMPLETED episodes (for segment cleanup).
     *
     */
    @Query("SELECT * FROM episode_downloads WHERE state NOT IN ('FAILED', 'CANCELLED')")
    suspend fun getEpisodesForReconciliation(): List<EpisodeDownloadEntity>

    /**
     * Save persisted stream info after resolution.
     * DownloadRepository persists m3u8Url/userAgent/referer/segmentDir.
     */
    @Query("UPDATE episode_downloads SET m3u8Url = :m3u8Url, userAgent = :userAgent, referer = :referer, segmentDir = :segmentDir, updatedAt = :timestamp WHERE episodeId = :episodeId")
    suspend fun saveStreamInfo(episodeId: String, m3u8Url: String, userAgent: String, referer: String, segmentDir: String, timestamp: Long = System.currentTimeMillis())

    // === Phase 11 additions (PHASE11_ARCHITECTURE_V2 §13/§16.2/§29/§30) ===

    /**
     * One-time persistence of the manifest-resolved segment total (§16.2).
     *
     * Write-on-change guard: only writes when the persisted value differs from
     * [total] (`AND totalSegments != :total`) — issued once by DownloadWorker at
     * manifest resolution, never per-segment and never via ProgressTracker.flush()
     * (that path stays completedSegments-only with its ≥5000 ms throttle unchanged).
     *
     * `0` remains "not yet known" (pre-manifest) — the UI never fabricates a
     * denominator (§16.1).
     */
    @Query("UPDATE episode_downloads SET totalSegments = :total, updatedAt = :timestamp WHERE episodeId = :episodeId AND totalSegments != :total")
    suspend fun updateTotalSegments(episodeId: String, total: Int, timestamp: Long = System.currentTimeMillis())

    /**
     * Persist the MediaStore export URI (§30.1 step 5 — written BEFORE clearing
     * IS_PENDING, so no visible MediaStore file exists that Room does not know about).
     */
    @Query("UPDATE episode_downloads SET outputUri = :outputUri, updatedAt = :timestamp WHERE episodeId = :episodeId")
    suspend fun updateOutputUri(episodeId: String, outputUri: String?, timestamp: Long = System.currentTimeMillis())

    /**
     * §3.1 (DECISIONS 157): CAS-guarded re-point of a COMPLETED row's `outputUri`
     * onto the export that still survives at the episode's destination.
     *
     * The startup reconciler calls this instead of demoting a `COMPLETED` row to
     * `FAILED ("file missing")` when the row's remembered URI no longer resolves
     * but [EpisodeExporter.destinationOutput] finds a live export at the exact
     * destination. The `state = :expectedState` guard makes it a no-op unless the
     * row is still `COMPLETED` (a concurrent re-queue / Cancel / Retry landing
     * between the probe and this write is never overwritten), mirroring the
     * demotion's own CAS so the two are interchangeable under the same race.
     *
     * @return 1 when the row was repaired, 0 when the CAS lost (row moved).
     */
    @Query("UPDATE episode_downloads SET outputUri = :outputUri, updatedAt = :timestamp WHERE episodeId = :episodeId AND state = :expectedState")
    suspend fun repairOutputUriIfState(episodeId: String, expectedState: String, outputUri: String, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Ordered observe-all for the Downloads dashboard (§13: query-only addition,
     * no schema change). Newest-updated first; episodeId breaks updatedAt ties
     * deterministically (stable ordering for Compose lists).
     */
    @Query("SELECT * FROM episode_downloads ORDER BY updatedAt DESC, episodeId DESC")
    fun observeAllOrdered(): Flow<List<EpisodeDownloadEntity>>

    /**
     * All persisted export URIs (Slice 6, §30.4): the stale-IS_PENDING sweep must
     * never delete a MediaStore row that Room still references. Query-only —
     * no schema change.
     */
    @Query("SELECT outputUri FROM episode_downloads WHERE outputUri IS NOT NULL")
    suspend fun getAllOutputUris(): List<String>

    /**
     * Slice A (per-audio export identity): the `audio` value of every OTHER row
     * that already references this export URI — empty means the file is an
     * orphan, so it may be size-verified and adopted as a legacy alias.
     *
     * The callers refuse to adopt AND refuse to delete the file while this is
     * non-empty, whatever the audio value: a URI two rows point at belongs to
     * whichever episode produced it, so replacing or sharing it would damage
     * the other row (Slice A cross-reference guard). Query-only — no schema
     * change, no migration.
     */
    @Query("SELECT audio FROM episode_downloads WHERE outputUri = :outputUri AND episodeId != :episodeId")
    suspend fun otherReferenceAudios(outputUri: String, episodeId: String): List<String>

    /**
     * Slice B (review finding D) — the atomic CREATION claim for an episode row.
     *
     * `queueResolvedEpisode` used to read (`getById`) and then write
     * (`upsert` = `INSERT OR REPLACE`) with no atomic step between them, so two
     * concurrent submissions for the same episode+audio that both observed "no
     * row" both reported a fresh enqueue. Unique Work (`ExistingWorkPolicy.KEEP`)
     * already guaranteed only one Worker ever ran, but the OUTCOME was not
     * honest — exactly what this slice exists to fix.
     *
     * `INSERT OR IGNORE` closes that window: exactly one caller can create the
     * row, and Room returns the new rowId for the winner and `-1` for every
     * loser. The loser then re-classifies from the row that now exists instead
     * of claiming a fresh enqueue (review finding M-7).
     *
     * Query-only — **no column, no index, no Room migration**. `episodeId` is a
     * String `@PrimaryKey`, so the returned value is the implicit SQLite rowid;
     * callers must compare against `-1`, never dereference it.
     *
     * Not a second queueing mechanism: it is the missing atomic counterpart to
     * the existing CAS helpers ([updateStateForResume], [beginRetry]) over the
     * same read-then-write window.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(episode: EpisodeDownloadEntity): Long
}
