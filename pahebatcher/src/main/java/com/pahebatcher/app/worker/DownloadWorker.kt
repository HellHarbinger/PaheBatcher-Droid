package com.pahebatcher.app.worker

import android.content.Context
import android.content.pm.ServiceInfo
import com.pahebatcher.app.logging.AppLog
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pahebatcher.app.data.download.DownloadConfig
import com.pahebatcher.app.data.download.EpisodeOutputVerifier
import com.pahebatcher.app.data.download.applyDownloadWorkPolicy
import com.pahebatcher.app.data.download.awaitUniqueWorkInfos
import com.pahebatcher.app.data.hls.AesKeyCache
import com.pahebatcher.app.data.hls.DownloadErrorClass
import com.pahebatcher.app.data.hls.EpisodeDownloader
import com.pahebatcher.app.data.hls.FfmpegEpisodeAssembler
import com.pahebatcher.app.data.hls.SegmentFileStore
import com.pahebatcher.app.data.export.ExportOutcome
import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.download.FailureCategory
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.notification.DownloadNotifyPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * WorkManager Worker for background episode downloads.
 *
 *
 * Responsibilities:
 * - Read episode state from Room
 * - Set foreground notification
 * - Call EpisodeDownloader (NOT StreamResolver/KwikResolver/WebView)
 * - Update Room state/progress/error/completion
 * - Trigger next deferred episode on EVERY completion (§6: success, failure,
 *   cancellation, and exception paths — corrective F-C2)
 *
 * NEVER calls StreamResolver, KwikResolver, CloudflareSessionProvider, or WebView.
 *
 * maxParallel gating before DOWNLOADING transition.
 * ForegroundInfo specifies FOREGROUND_SERVICE_TYPE_DATA_SYNC.
 * Progress writes use non-blocking suspend calls.
 *
 * Phase 11.5 item (e) (reviewers M-5/L-8, LIMITATIONS §27.1/§27.3):
 * - entering the Worker is an atomic CAS claim ([claimEntryState]) — a stale
 *   Worker never overwrites a newer terminal state;
 * - every failure write is CAS-guarded via `updateErrorIfState(DOWNLOADING)`;
 * - a CancellationException propagates (terminal cleanup under NonCancellable,
 *   then rethrow) instead of becoming an ordinary failure Result.
 *
 * Pause/resume run-ownership (v4→v5): every run generates one opaque token and
 * stamps it into `episode_downloads.downloadRunId` by the entry claim itself
 * ([claimEntryState] → `claimEntryRunWithRunId`). EVERY state write this run
 * then issues that asserts "the row is still mine" — the cancellation cleanup,
 * the failure writes, the remuxing claim, the auto-resume park, the completion
 * and the replacement commit/restore — is a single atomic UPDATE guarded by
 * BOTH the expected state and `downloadRunId = <this run's token>`. A stale
 * run whose cancellation cleanup drains past a pause+resume therefore loses
 * every write against the resumed run's row, while the CURRENT run's identical
 * writes still land. User and recovery actors (repository, reconciler) keep
 * their state-only CAS and never touch the token.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val dao: EpisodeDownloadDao,
    private val notificationManager: DownloadNotificationManager,
    private val hlsClient: com.pahebatcher.app.domain.hls.HlsClient,
    private val keyCache: AesKeyCache,
    config: DownloadConfig = DownloadConfig(),
    // persistent settings for this run. Null in tests
    // that inject an explicit `config`; production supplies
    // AppConfigDownloadConfigSource via DownloadWorkerFactory.
    private val configSource: com.pahebatcher.app.data.download.DownloadConfigSource? = null,
    // Slice 6 (§30.1): nullable so pre-export-slice test factories keep compiling;
    // production (DownloadWorkerFactory from AppContainer) supplies the real
    // exporter. Phase 13 (DECISIONS 92.2): the interface is the destination
    // router — SAF tree when configured/valid, MediaStore default otherwise.
    private val exporter: EpisodeExporter? = null,
    // `Replace file` (DECISIONS 152): the ONE §29 completed-output rule,
    // injected so the replacement paths below decide "did the existing export
    // survive?" with the SAME rule DownloadRepository, the reconciler and the
    // export gate use — never a bespoke path/size guess. Defaulted from the
    // constructor's own Context so a test factory that omits it resolves
    // identically to production. There is no null case on purpose: a failure
    // path that could not check would have to guess.
    private val outputVerifier: EpisodeOutputVerifier = EpisodeOutputVerifier(context.contentResolver),
    // the CURRENT Wi-Fi-only setting, read fresh at
    // the deferred-episode enqueue below — a NEW WorkRequest reflects the value
    // at the moment it is built, never the run-start snapshot. Null-safe default
    // (OFF) so existing test factories enqueue exactly as before; production
    // supplies `{ appConfig.wifiOnly.first() }` through DownloadWorkerFactory.
    private val wifiOnlyProvider: suspend () -> Boolean = { false },
    // the CURRENT completion/failure notification
    // preferences, read fresh at the moment a terminal notification is posted
    // (DECISIONS 105.4). Default = the shipped DataStore defaults (both ON), so
    // every existing test factory keeps posting exactly as before; production
    // supplies the AppConfig-backed reader through DownloadWorkerFactory.
    private val notifyPrefsProvider: suspend () -> DownloadNotifyPrefs = { DownloadNotifyPrefs() },
    // the CURRENT `Auto-resume downloads`
    // setting, read fresh at the moment a transport failure is decided — never
    // at run start — so flipping the switch shapes the very next failure instead
    // of waiting for an app restart (DECISIONS 105.4, same contract as the
    // notification preferences above). Default OFF = the setting's own shipped
    // default and today's terminal-failure behavior, so every existing test
    // factory behaves exactly as before.
    private val autoResumeProvider: suspend () -> Boolean = { false },
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"

        /**
         * The auto-resume bound.
         *
         * WorkManager reports `runAttemptCount` as **0 on the first run**, so a
         * request that keeps returning `Result.retry()` executes runs 1..7
         * (counts 0..6) and the 8th run (count 7), whose failure is still
         * TRANSPORT, takes the terminal path instead. Cumulative gaps before
         * those runs are 30s, 1m, 2m, 4m, 8m, 16m, 32m — well under WorkManager's
         * 5-hour exponential cap.
         */
        const val AUTO_RESUME_MAX_RETRIES = 7

        /**
         * The deferred trigger examines at most
         * this many candidates per trigger so the per-trigger WorkInfo lookups
         * are bounded. Without this cap, a large backlog would cause every
         * worker completion to trigger N blocking WorkInfo lookups.
         */
        const val TRIGGER_CANDIDATE_CAP = 50

        /**
         * `Replace file` (DECISIONS 152) — the staging directory a confirmed
         * replacement downloads into, INSIDE the episode's own `segmentDir`.
         *
         * Beside (not instead of) the real directory matters twice:
         *
         * * `EpisodeDownloader`'s "output already exists, skip the download"
         *   short-circuit and the step-7 skip both resolve through
         *   `SegmentFileStore.getDirectory()`. Pointing that at an EMPTY
         *   `replace/` is what makes a restarted run download for real instead
         *   of handing back the OLD `.mp4` as if it were the new result — the
         *   failure this column exists to prevent.
         * * the episode's own directory, the old export inside it, and every
         *   existing `segmentDir`-based cleanup/reconciliation path keep
         *   working unchanged, because `row.segmentDir` is never rewritten.
         *
         * The final name is identical, so committing is a same-directory
         * `rename` onto the previous output (see `commitReplacementOutput`).
         */
        const val SEGMENT_STAGING_DIR_NAME = "replace"

        /**
         * The ONE auto-resume eligibility
         * decision, pure and testable without Android.
         *
         * Returns `true` only when ALL three hold:
         *
         * * the `Auto-resume downloads` setting is ON (**default OFF**, so
         *   upgrading never changes anyone's failure semantics — DECISIONS
         *   105.5);
         * * [errorClass] is [DownloadErrorClass.TRANSPORT] — a network-origin
         *   failure. Every other class is deterministic (gone stream, empty
         *   playlist, FFmpeg, local I/O) or worker-originating and must reach
         *   today's terminal path untouched;
         * * [runAttemptCount] is still below [AUTO_RESUME_MAX_RETRIES].
         *
         * The decision reads the TYPED field only — never `error` text
         * (PHASE14_PLAN §14).
         */
        fun shouldAutoResume(
            errorClass: DownloadErrorClass,
            enabled: Boolean,
            runAttemptCount: Int,
        ): Boolean =
            enabled &&
                errorClass == DownloadErrorClass.TRANSPORT &&
                runAttemptCount < AUTO_RESUME_MAX_RETRIES
    }

    /**
     * Effective configuration for THIS run.
     *
     * Seeded from the constructor ([config] — the test/default path) and refreshed
     * ONCE at the start of [runWork] from [configSource] when present. A run keeps
     * the configuration it started with, mirroring the Python reference, which reads
     * `config.json` once at process start. A settings change therefore applies to
     * the next run, never mid-run.
     */
    private var config: DownloadConfig = config

    /**
     * Load the persisted settings exactly once per Worker run.
     *
     * A read failure is logged and keeps the current configuration — configuration
     * loading must never prevent, delay, or corrupt a download (the Console/logging
     * abstraction is likewise never a dependency of download execution).
     */
    private suspend fun refreshConfig() {
        val source = configSource ?: return
        config = try {
            source.downloadConfig()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Settings read failed; continuing with current config: ${e.message}")
            config
        }
        AppLog.i(
            TAG,
            "Run config: maxParallel=${config.maxParallel} hlsWorkers=${config.hlsWorkers} " +
                "keepTemp=${config.keepTemp} episodeRetry=${config.episodeRetryCount}",
        )
    }

    override suspend fun doWork(): Result {
        // The liveness guarantee requires EVERY Worker
        // completion — success, failure, cancellation, or exception — to
        // trigger the next deferred episode. Failure and cancellation free an
        // active slot exactly like success, so the liveness guarantee must not
        // depend on the outcome. The trigger runs under NonCancellable so it
        // also executes after this Worker was cancelled (the terminal Room
        // write has already happened by then). Trigger failure is logged, never
        // propagated — it must not mask the download result (startup
        // reconciliation is the deterministic fallback).
        return try {
            runWork()
        } finally {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    triggerNextDeferredEpisode()
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to trigger next deferred episode: ${e.message}")
            }
        }
    }

    private suspend fun runWork(): Result {
        // one settings read per run, BEFORE the maxParallel gate and the
        // state claim, so the whole run uses one consistent configuration snapshot.
        refreshConfig()

        val episodeId = inputData.getString("episode_id") ?: return Result.failure()

        AppLog.d(TAG, "Starting download work for $episodeId")

        // Load episode from Room
        val episode = dao.getById(episodeId)
        if (episode == null) {
            AppLog.e(TAG, "Episode $episodeId not found in database")
            return Result.failure()
        }

        // maxParallel active-count gating
        // Architecture §4: Check active count before transitioning to DOWNLOADING
        val activeCount = dao.countActive()
        if (activeCount >= config.maxParallel) {
            AppLog.d(TAG, "Active count $activeCount >= maxParallel ${config.maxParallel}, deferring $episodeId")
            // Return success (not failure) — episode remains QUEUED, will be retried on next trigger
            return Result.success()
        }

        // 11.5E item (e), reviewer M-5 (LIMITATIONS §27.1): the entry transition
        // is an atomic CAS CLAIM, not a blind `updateState(DOWNLOADING)`.
        // Losing the claim means a stale Worker started after a newer terminal
        // write (the user's Cancel is the common case) — it must not touch the
        // row at all, so it exits exactly like the maxParallel deferral.
        //
        // Run ownership: ONE opaque token per doWork() execution, stamped into
        // the row by the claim itself and required by every run-owned write
        // below. A retry run re-claims and re-stamps (a fresh token), so a
        // parked-then-retried run never carries a previous attempt's identity.
        val runId = UUID.randomUUID().toString()
        if (!claimEntryState(episodeId, runId)) {
            AppLog.d(TAG, "Skipping stale Worker for $episodeId: row is no longer claimable")
            return Result.success()
        }

        // Set foreground with notification (WorkManager manages the foreground service).
        // Everything below runs while this Worker OWNS the row (state DOWNLOADING
        // + downloadRunId = runId):
        // a CancellationException propagates to runWork()'s handler, which persists
        // the terminal state under NonCancellable and rethrows (L-8).
        val notificationId = notificationManager.notificationIdFor(episodeId)
        return try {
            executeClaimedWork(episode, notificationId, runId)
        } catch (e: CancellationException) {
            // L-8 (LIMITATIONS §4/§27.3): cancellation is NOT an ordinary failure.
            // Cleanup first — the coroutine is already cancelled, so the writes must
            // run under NonCancellable — then propagate so WorkManager observes a
            // cancellation instead of a returned failure Result. A cleanup problem
            // must never mask the cancellation itself: it is logged and the original
            // CancellationException is rethrown unconditionally below.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    // CAS: only a row in a state THIS run owns AND still holds
                    // (downloadRunId = runId) is flipped — DOWNLOADING (the entry
                    // claim) or, final-audit H4, ASSEMBLING (this run's own
                    // remuxing write). A user Cancel that already wrote CANCELLED,
                    // any newer terminal state, OR a row re-claimed by a NEWER run
                    // (the pause→resume race this token exists for) wins — never
                    // overwritten here. `Replace file` (DECISIONS 152): a confirmed
                    // replacement that was cancelled returns to the COMPLETED state
                    // and export it was overlaying instead of becoming CANCELLED.
                    val changed = persistOwnedCancellation(episode, runId)
                    if (changed > 0) {
                        AppLog.d(TAG, "Cancellation persisted for $episodeId (terminal CANCELLED)")
                    } else {
                        AppLog.d(
                            TAG,
                            "Cancellation of $episodeId lost the state/ownership write — " +
                                "a newer state or run wins",
                        )
                    }
                } catch (cleanupError: Exception) {
                    AppLog.e(TAG, "Cancellation state write failed for $episodeId: ${cleanupError.message}")
                }
                try {
                    notificationManager.cancelNotification(notificationId)
                } catch (cleanupError: Exception) {
                    AppLog.e(TAG, "Cancellation notification cleanup failed for $episodeId: ${cleanupError.message}")
                }
            }
            throw e
        } catch (e: Exception) {
            // 11.5E item (e), reviewer M7: an unexpected NON-cancellation exception
            // escaping executeClaimedWork (a DAO error, a notification failure, a
            // runtime error outside its own guarded handlers) must not strand the
            // row in DOWNLOADING forever — startup reconciliation would then only
            // re-enqueue it while the stale failure stays invisible. Persist an
            // honest, CAS-guarded FAILED (only while the row is still the
            // DOWNLOADING state WE claimed — a newer state always wins) and fail
            // the Work. The message is sanitized to the exception type so a URL
            // from `e.message` is never persisted or logged here (reviewer n2).
            val reason = "Unexpected worker error (${e.javaClass.simpleName})"
            AppLog.e(TAG, "Unexpected error for $episodeId: $reason", e)
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    // Replacement-aware: a confirmed replacement that dies here
                    // restores the export it never got to replace (DECISIONS 152).
                    reportTerminalFailure(
                        episode = episode,
                        errorMessage = reason,
                        notificationId = notificationId,
                        runId = runId,
                    )
                }
            } catch (cleanupError: Exception) {
                AppLog.e(TAG, "Unexpected-error cleanup failed for $episodeId: ${cleanupError.message}")
            }
            return Result.failure()
        }
    }

    /**
     * Final-audit H4 + run ownership — CAS a terminal state from a row THIS run
     * still owns.
     *
     * A run claims the row as `DOWNLOADING` on entry ([claimEntryState], which
     * stamps [runId]) and is the only actor that can produce `ASSEMBLING` (its
     * own remuxing hook), so the write tries `DOWNLOADING` first and
     * `ASSEMBLING` second — both additionally requiring `downloadRunId = runId`
     * atomically. Every other situation — a user's Cancel/Pause landing
     * concurrently, a terminal `COMPLETED`/`FAILED`/`CANCELLED`, or a row a
     * NEWER run re-claimed after this one was paused/cancelled — loses BOTH
     * attempts and is left exactly as it is.
     *
     * @return rows transitioned (0 = a newer state or run won).
     */
    private suspend fun persistTerminalFromOwnedState(
        episodeId: String,
        newState: String,
        runId: String,
    ): Int {
        val fromDownloading = dao.updateStateIfOwned(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_DOWNLOADING,
            newState = newState,
            runId = runId,
        )
        if (fromDownloading > 0) return fromDownloading
        return dao.updateStateIfOwned(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_ASSEMBLING,
            newState = newState,
            runId = runId,
        )
    }

    /**
     * [persistTerminalFromOwnedState] for the failure text — same two-state
     * claim, same ownership requirement, same "a newer state or run always
     * wins" refusal (SPEC §26: an error is never stamped over a state the user
     * or another actor already wrote).
     */
    private suspend fun persistErrorFromOwnedState(
        episodeId: String,
        errorMessage: String,
        runId: String,
    ): Int {
        val fromDownloading = dao.updateErrorIfOwned(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_DOWNLOADING,
            errorMessage = errorMessage,
            runId = runId,
        )
        if (fromDownloading > 0) return fromDownloading
        return dao.updateErrorIfOwned(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_ASSEMBLING,
            errorMessage = errorMessage,
            runId = runId,
        )
    }

    /**
     * `Replace file` (DECISIONS 152) — the result of ONE replacement-aware
     * terminal write, so the caller can choose both the state AND the wording.
     *
     * @property rowsAffected rows transitioned (0 = a newer state won, so
     *   nothing may be posted).
     * @property restoredExistingExport true when this run was a confirmed
     *   replacement that produced NO verified new output and the row therefore
     *   went back to `COMPLETED` — the export it was replacing is still there,
     *   so the failure must be reported as a failed REPLACEMENT, never as a
     *   failed download of a file that no longer exists.
     */
    private data class TerminalWrite(val rowsAffected: Int, val restoredExistingExport: Boolean)

    /**
     * `Replace file` — end an in-flight replacement that never committed, when
     * its previous export still verifies under the ONE §29 rule.
     *
     * Tries `DOWNLOADING` first and `ASSEMBLING` second, exactly like every
     * other owned-state write here, so a newer state is never stamped over.
     *
     * @return rows restored (0 = the export is gone, or a newer state won)
     */
    private suspend fun restoreOwnedReplacement(episode: EpisodeDownloadEntity, runId: String): Int {
        if (!outputVerifier.isPresent(episode.outputUri, episode.outputPath)) return 0
        val fromDownloading = dao.restoreCompletedForUncommittedReplacementIfOwned(
            episodeId = episode.episodeId,
            expectedState = EpisodeDownloadEntity.STATE_DOWNLOADING,
            runId = runId,
        )
        if (fromDownloading > 0) return fromDownloading
        return dao.restoreCompletedForUncommittedReplacementIfOwned(
            episodeId = episode.episodeId,
            expectedState = EpisodeDownloadEntity.STATE_ASSEMBLING,
            runId = runId,
        )
    }

    /**
     * `Replace file` (DECISIONS 152) — THE failure write for this Worker.
     *
     * An ordinary run writes `FAILED` through [persistErrorFromOwnedState],
     * unchanged. A confirmed replacement does NOT, because a replacement is an
     * overlay on an export that was never touched: when it produces nothing
     * verifiable, `COMPLETED` is still the truth about the row and stamping
     * `FAILED` would claim a completed download has vanished when it has not.
     *
     * Only when the previous export itself no longer verifies does the
     * ordinary `FAILED` run — and then the replacement intent is dropped in the
     * next statement so `replaceRequested` can never outlive the run that set
     * it. Either way nothing here ever writes `COMPLETED` while claiming a NEW
     * file: the row only returns to `COMPLETED` when the file that `outputUri`
     * / `outputPath` name is still there.
     */
    private suspend fun persistReplacementAwareFailure(
        episode: EpisodeDownloadEntity,
        errorMessage: String,
        runId: String,
    ): TerminalWrite {
        if (!episode.replaceRequested) {
            return TerminalWrite(persistErrorFromOwnedState(episode.episodeId, errorMessage, runId), false)
        }
        val restored = restoreOwnedReplacement(episode, runId)
        if (restored > 0) {
            AppLog.w(
                TAG,
                "Replacement for ${episode.episodeId} produced no verified new output — existing export kept",
            )
            return TerminalWrite(restored, true)
        }
        val changed = persistErrorFromOwnedState(episode.episodeId, errorMessage, runId)
        if (changed > 0) dao.clearReplacementIntent(episode.episodeId)
        return TerminalWrite(changed, false)
    }

    /**
     * `Replace file` (DECISIONS 152) — the cancellation write for this Worker.
     *
     * Same split as [persistReplacementAwareFailure]: cancelling a confirmed
     * replacement must return the row to the `COMPLETED` state and export it
     * held BEFORE the confirmation, because nothing the replacement did ever
     * touched that export. When it no longer verifies, the ordinary
     * `CANCELLED` path runs (and the intent is dropped with it).
     */
    private suspend fun persistOwnedCancellation(
        episode: EpisodeDownloadEntity,
        runId: String,
    ): Int {
        if (!episode.replaceRequested) {
            return persistTerminalFromOwnedState(episode.episodeId, EpisodeDownloadEntity.STATE_CANCELLED, runId)
        }
        val restored = restoreOwnedReplacement(episode, runId)
        if (restored > 0) {
            AppLog.d(TAG, "Cancellation of ${episode.episodeId} aborted the replacement — existing export kept")
            return restored
        }
        val changed = persistTerminalFromOwnedState(episode.episodeId, EpisodeDownloadEntity.STATE_CANCELLED, runId)
        if (changed > 0) dao.clearReplacementIntent(episode.episodeId)
        return changed
    }

    /**
     * The common tail for EVERY terminal failure write: the guarded row write,
     * the progress-notification teardown, and the terminal notification — in
     * that order, with the notification gated by the CAS exactly as before.
     *
     * `Replace file` (DECISIONS 152) is handled inside
     * [persistReplacementAwareFailure], so a caller that passes a
     * replacement-aware episode gets a row that returns to `COMPLETED` and a
     * notification that says a REPLACEMENT failed; every other episode behaves
     * byte-identically to the previous direct `updateErrorIfState` call.
     */
    private suspend fun reportTerminalFailure(
        episode: EpisodeDownloadEntity,
        errorMessage: String,
        notificationId: Int,
        runId: String,
        category: FailureCategory? = null,
    ) {
        val write = persistReplacementAwareFailure(episode, errorMessage, runId)
        notificationManager.cancelNotification(notificationId)
        notifyTerminal(
            episode = episode,
            completion = false,
            casLanded = write.rowsAffected > 0,
            category = category,
            replacementKeptExisting = write.restoredExistingExport,
        )
    }

    /**
     * 11.5E item (e), reviewer M-5: claim the row for THIS Worker run.
     *
     * Accepted entry states are exactly the ones the pipeline legitimately hands
     * a Worker:
     *
     * * [EpisodeDownloadEntity.STATE_QUEUED] — fresh `resolveComplete` hand-off or
     *   a reconciler resume (`DOWNLOADING → QUEUED` re-enqueue);
     * * [EpisodeDownloadEntity.STATE_DOWNLOADING] — WorkManager restarting work
     *   that was interrupted by process death (the reconciler leaves the row alone
     *   while its work is still ENQUEUED/RUNNING, `DownloadStateReconciler.kt:248`);
     * * [EpisodeDownloadEntity.STATE_ASSEMBLING] — the reconciler re-enqueues an
     *   incomplete assembly WITHOUT changing the state (`DownloadStateReconciler.kt:229-246`),
     *   so an ASSEMBLING row must be claimable or that re-enqueue would always
     *   lose here.
     *
     * Precision on the ASSEMBLING case (reviewer M6): accepting it here does NOT
     * guarantee this run proceeds — the `maxParallel` gate runs BEFORE this claim
     * and `countActive()` counts ASSEMBLING rows too, so a row in ASSEMBLING can
     * still be deferred (returning `Result.success()` with the state untouched).
     * That is deliberate, not a dead end: the row keeps its state and the next
     * trigger/reconcile pass re-enqueues it. This claim only decides whether the
     * run that passes the gate OWNS the row.
     *
     * Terminal states (`FAILED`/`CANCELLED`/`COMPLETED`) and `RESOLVING` lose the
     * claim: a stale Worker must never resurrect a cancelled episode or overturn a
     * newer terminal write (the inverse of the 11.5E-7 invariant).
     *
     * @return true when this run owns the row (state is now `DOWNLOADING` and
     *   `downloadRunId` is [runId]).
     */
    private suspend fun claimEntryState(episodeId: String, runId: String): Boolean {
        val claimableStates = listOf(
            EpisodeDownloadEntity.STATE_QUEUED,
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            EpisodeDownloadEntity.STATE_ASSEMBLING,
        )
        for (expectedState in claimableStates) {
            // SPEC §25 stale-rate guard (1 of 2 — the mapper's DOWNLOADING-only
            // gate is 2 of 2) lives INSIDE this statement: the claim CAS clears
            // `speed`/`etaSeconds` in the same UPDATE that moves the row to
            // DOWNLOADING, so a run that has only just claimed the row can never
            // be observed carrying whatever run wrote last, and this run re-measures
            // its own rate from its own samples. Bytes and segment counts are
            // deliberately preserved — they are real persisted progress, and the
            // first flush replaces them with this run's resume base.
            //
            // Run ownership lives INSIDE the same statement too: [runId] is
            // stamped by the very UPDATE that wins the state, so the row can
            // never be DOWNLOADING on behalf of one run while carrying another
            // run's token — every *IfOwned write below is atomic against this.
            val claimed = dao.claimEntryRunWithRunId(
                episodeId = episodeId,
                expectedState = expectedState,
                runId = runId,
            )
            if (claimed > 0) return true
        }
        return false
    }

    /**
     * The download itself, run only after [claimEntryState] succeeded (this run
     * owns the row: `DOWNLOADING` + `downloadRunId = runId`).
     *
     * All four failure writes go through the run-owned guarded write
     * (`updateErrorIfOwned(expectedState = DOWNLOADING|ASSEMBLING, runId)`) —
     * a Cancel that landed after our claim, OR a row a newer run re-claimed,
     * is never converted into FAILED by this run. A CancellationException is logged and
     * rethrown — never turned into `Result.failure()`.
     */
    private suspend fun executeClaimedWork(
        episode: EpisodeDownloadEntity,
        notificationId: Int,
        runId: String,
    ): Result {
        val episodeId = episode.episodeId

        try {
            notificationManager.createNotificationChannel()
            val notification = notificationManager.buildProgressNotification(
                episodeId = episodeId,
                episodeTitle = episode.title,
                audio = episode.audio
            )
            val foregroundInfo = ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            setForeground(foregroundInfo)
        } catch (e: CancellationException) {
            // L-8: `setForeground` is a suspend fun — a cancellation here must
            // propagate (runWork() cleans up and rethrows), never be swallowed by
            // the generic handler below as if it were an ordinary foreground error.
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to set foreground: ${e.message}")
            // Continue anyway — Worker can still run without foreground
        }

        // Read persisted stream info from Room
        val m3u8Url = episode.m3u8Url
        val userAgent = episode.userAgent ?: ""
        val referer = episode.referer ?: ""
        val segmentDirPath = episode.segmentDir

        if (m3u8Url == null || segmentDirPath == null) {
            AppLog.e(TAG, "Episode $episodeId has no persisted stream info")
            reportTerminalFailure(episode, "No stream info available", notificationId, runId)
            return Result.failure()
        }

        // Create StreamInfo from persisted data
        val streamInfo = StreamInfo(
            url = m3u8Url,
            userAgent = userAgent,
            referer = referer,
            cookies = emptyList()
        )

        // Create episode-specific components.
        //
        // `Replace file` (DECISIONS 152): a confirmed replacement downloads and
        // assembles inside `<segmentDir>/replace/`, so the OLD export sitting at
        // `<segmentDir>/<same name>.mp4` is outside every skip-existing and
        // every write this run performs. `row.segmentDir` itself stays canonical
        // (only the DIRECTORY this run uses changes), so cleanup, reconciliation
        // and SessionActions keep addressing the episode's real directory.
        val segmentDir = if (episode.replaceRequested) {
            File(segmentDirPath, SEGMENT_STAGING_DIR_NAME)
        } else {
            File(segmentDirPath)
        }
        if (!segmentDir.exists()) segmentDir.mkdirs()

        val segmentStore = SegmentFileStore(segmentDir)
        val assembler = FfmpegEpisodeAssembler()

        // Final-audit H4 (SPEC §25 "remuxing"): whether THIS run really
        // persisted `ASSEMBLING`. The row is ASSEMBLING from the hook below
        // until this run writes its terminal state, so every later guarded
        // write must expect ASSEMBLING instead of DOWNLOADING — a CAS against
        // DOWNLOADING would lose against a state WE wrote and the completion
        // would be silently dropped. The hook only claims from DOWNLOADING,
        // so a claim that lost (a newer state won) leaves this false and the
        // writes keep expecting DOWNLOADING: nothing is ever stamped over a
        // state another actor owns.
        var assemblyPhase = false

        /**
         * The state every post-download guarded write must expect: ASSEMBLING
         * once this run persisted it, otherwise DOWNLOADING (unchanged).
         * Read after [downloader.download] returns, so it always reflects the
         * final value the hook wrote.
         */
        fun expectedWriteState(): String =
            if (assemblyPhase) EpisodeDownloadEntity.STATE_ASSEMBLING
            else EpisodeDownloadEntity.STATE_DOWNLOADING

        // Create EpisodeDownloader
        val downloader = EpisodeDownloader(
            hlsClient = hlsClient,
            keyCache = keyCache,
            segmentStore = segmentStore,
            assembler = assembler,
            hlsWorkers = config.hlsWorkers,
            autoRetry = config.episodeRetryCount,
            // `keep_temp` — Python keeps the segment directory when true
            // and deletes it after a successful mux when false (config_manager.py).
            keepTemp = config.keepTemp,
        )

        // Progress tracking with throttling.
        // One bounded flush coroutine persists progress to Room at most every
        // progressThrottleMs; notification updates are throttled in-callback.
        // No runBlocking, no per-callback coroutine launches.
        val progressTracker = ProgressTracker(
            throttleMs = config.progressThrottleMs,
            // SPEC §25: the real persisted metrics — bytes the downloader really
            // wrote, the rate measured over the bounded window, and an ETA that
            // is 0 whenever it is unknown. (The zeros this used to write are gone.)
            writeProgress = { completed, downloadedBytes, speed, etaSeconds ->
                try {
                    dao.updateProgress(
                        episodeId = episodeId,
                        completedSegments = completed,
                        downloadedBytes = downloadedBytes,
                        speed = speed,
                        etaSeconds = etaSeconds
                    )
                } catch (e: CancellationException) {
                    // L-8: a cancelled progress write is part of the cancellation —
                    // propagate it instead of logging it as a best-effort failure.
                    throw e
                } catch (e: Exception) {
                    // Best-effort progress write — never abort the download for this
                    AppLog.w(TAG, "Progress write failed (best-effort): ${e.message}")
                }
            },
            notifyProgress = { percent ->
                notificationManager.updateNotification(
                    notificationId,
                    notificationManager.buildProgressNotification(
                        episodeId = episodeId,
                        episodeTitle = episode.title,
                        audio = episode.audio,
                        progress = percent
                    )
                )
            }
        )

        // Download
        val result = try {
            coroutineScope {
                // Single bounded flusher — the ONLY progress-write coroutine.
                val flusher = launch {
                    while (true) {
                        delay(config.progressThrottleMs)
                        progressTracker.flush()
                    }
                }
                try {
                    downloader.download(
                        streamInfo = streamInfo,
                        episodeNumber = episode.episodeNumber,
                        episodeTitle = episode.title,
                        onProgress = { completed, total, downloadedBytes ->
                            progressTracker.onProgress(completed, total, downloadedBytes)
                        },
                        // Phase 11 §16.2: one-time totalSegments persistence at
                        // manifest resolution — a single write-on-change DAO
                        // statement issued by the Worker (the component that already
                        // performs all progress writes). NOT via
                        // ProgressTracker.flush() (completedSegments-only, throttle
                        // contract unchanged). Best-effort: never aborts the
                        // download; the write carries the
                        // `AND totalSegments != :total` guard so it only fires on
                        // change (once per normal run).
                        onManifestResolved = { total ->
                            try {
                                dao.updateTotalSegments(episodeId, total)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLog.w(TAG, "totalSegments write failed (best-effort): ${e.message}")
                            }
                        },
                        // Final-audit H4 (SPEC §25 "remuxing"): exactly one
                        // production writer of STATE_ASSEMBLING, issued the
                        // instant before FFmpeg starts muxing. Best-effort by
                        // contract (see EpisodeDownloader.download): a state row
                        // that cannot be written never stops the mux, it only
                        // leaves [assemblyPhase] false so the writes below keep
                        // expecting DOWNLOADING.
                        onAssemblyStart = {
                            val claimed = dao.updateStateIfOwned(
                                episodeId = episodeId,
                                expectedState = EpisodeDownloadEntity.STATE_DOWNLOADING,
                                newState = EpisodeDownloadEntity.STATE_ASSEMBLING,
                                runId = runId,
                            )
                            assemblyPhase = claimed > 0
                            if (claimed > 0) {
                                AppLog.d(TAG, "ASSEMBLING persisted for $episodeId (SPEC §25 remuxing)")
                            } else {
                                AppLog.d(TAG, "ASSEMBLING claim for $episodeId lost — a newer state won")
                            }
                        }
                    )
                } finally {
                    flusher.cancel()
                }
            }
        } catch (e: CancellationException) {
            // L-8: log only and propagate. The terminal cleanup (CAS DOWNLOADING →
            // CANCELLED under NonCancellable + notification removal) and the
            // rethrow live in runWork()'s CancellationException handler — a
            // cancellation is never converted into `Result.failure()` here.
            AppLog.d(TAG, "Download cancelled for $episodeId")
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Download exception for $episodeId: ${e.message}")
            // 11.5E item (e), M-5: guarded — a Cancel that landed after our claim
            // (row no longer DOWNLOADING), or a row a newer run re-claimed, is
            // never overwritten by this failure.
            // `Replace file` (DECISIONS 152): a confirmed replacement that dies
            // here keeps its existing export and returns to COMPLETED.
            reportTerminalFailure(
                episode = episode,
                errorMessage = e.message ?: "Unknown error",
                notificationId = notificationId,
                runId = runId,
            )
            return Result.failure()
        }

        // Force final progress write (latches the tracker — no stale writes after).
        // 11.5E-6: skipped when the run short-circuited on an existing output —
        // no segment work ran, so writing `doneIndices()` (often 0 after a
        // cleanup) would erase the row's real persisted progress.
        if (!result.reusedExistingOutput) {
            progressTracker.forceFinal(result.completedSegments)
        }

        // Handle result
        return when {
            result.wasCancelled -> {
                // 11.5E item (e), reviewer M1: a cancellation RESULT must never
                // overwrite a newer state. EpisodeDownloader swallowed the
                // CancellationException into `wasCancelled`, so the coroutine is
                // already cancelled here and only a NonCancellable write can land;
                // the write itself is a CAS on a row THIS run owns (DOWNLOADING, or
                // ASSEMBLING once the remuxing hook persisted it) — a Cancel/
                // terminal state written concurrently wins.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    // `Replace file` (DECISIONS 152): cancelling a confirmed
                    // replacement returns the row to the export it overlaid,
                    // not to CANCELLED.
                    val changed = persistOwnedCancellation(episode, runId)
                    if (changed > 0) {
                        AppLog.d(TAG, "Cancelled download persisted for $episodeId (terminal CANCELLED)")
                    } else {
                        AppLog.d(
                            TAG,
                            "Cancellation of $episodeId lost the state write — a newer state wins",
                        )
                    }
                    notificationManager.cancelNotification(notificationId)
                }
                // L-8: cancellation is not an ordinary outcome — re-check the job so
                // the real cancellation propagates (runWork()'s handler rethrows it
                // to WorkManager) instead of being converted into a failure Result.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // Unreachable in production: `wasCancelled` is only ever reported for
                // an actual cancellation, which the line above rethrows. Kept as an
                // honest fallback for a cancel flag raised while the job is still
                // active (WorkManager then records the run as failed, never cancelled).
                Result.failure()
            }
            result.assemblyError != null -> {
                // 11.5E item (e), M-5: guarded failure write (see above) — and,
                // final-audit H4, against ASSEMBLING when this run persisted it.
                // `result.error` carries the human-readable reason (SPEC §26),
                // including the out-of-space wording H2 reports. `Replace file`
                // (DECISIONS 152): a replacement that fails here never touched
                // its existing export, so the row returns to COMPLETED and the
                // notification says exactly that.
                reportTerminalFailure(
                    episode = episode,
                    errorMessage = result.error ?: "Assembly failed: ${result.assemblyError}",
                    notificationId = notificationId,
                    runId = runId,
                    // Slice C: the typed origin is still in scope at the remux
                    // site (ASSEMBLY, or DISK_FULL when FFmpeg ran out of
                    // space), so the notification can name it truthfully.
                    category = FailureCategory.fromDownloadErrorClass(result.errorClass),
                )
                Result.failure()
            }
            !result.success -> {
                // the ONLY failure that may
                // leave this branch as `Result.retry()` is a TRANSPORT failure
                // with auto-resume ON and the attempt budget left. Everything
                // else — and auto-resume OFF, the shipped default — falls
                // through to today's terminal path untouched.
                if (autoResumeEligible(result.errorClass)) {
                    val parked = parkForAutoResume(episodeId, notificationId, runId)
                    if (parked != null) return parked
                    // The park lost its CAS (a concurrent Cancel, a newer state,
                    // or a newer run won) — fall through to today's terminal
                    // handling.
                }
                // 11.5E item (e), M-5: guarded failure write (see above).
                // `Replace file` (DECISIONS 152): a confirmed replacement that
                // never produced a verified file keeps its existing export and
                // reports a failed REPLACEMENT instead of a failed download.
                reportTerminalFailure(
                    episode = episode,
                    errorMessage = result.error ?: "Download failed",
                    notificationId = notificationId,
                    runId = runId,
                    // Slice C: typed origin, read from the result still in scope
                    // (TRANSPORT / EXPIRED / NO_CONTENT / DISK_FULL /
                    // IN_RUN_RETRYABLE). A parked TRANSPORT already returned
                    // above, so nothing here announces a run that is actually
                    // still being retried.
                    category = FailureCategory.fromDownloadErrorClass(result.errorClass),
                )
                Result.failure()
            }
            else -> {
                // Success — update completion.
                // 11.5E item (e), reviewer M5: CAS-guarded completion. Only the
                // row WE claimed may become COMPLETED — if a newer state (a Cancel
                // that landed while the last bytes were written, or the
                // reconciler's re-enqueue) already won, this run must not stamp
                // COMPLETED over it and must skip the export for a row it no longer
                // owns. Final-audit H4: the claim is ASSEMBLING when this run
                // persisted the remuxing state, otherwise DOWNLOADING.
                //
                // `Replace file` (DECISIONS 152): a confirmed replacement
                // COMMITS before it claims COMPLETED, never after. The staged
                // file is validated and moved onto the previous export's path by
                // a single rename first, and only then does `finishReplacement`
                // CAS the row — so state and UI begin describing the new output
                // only once that output is the file actually on disk, and every
                // refusal above leaves the old export byte-for-byte where it was.
                val outputPath: String
                val completed: Int
                if (episode.replaceRequested) {
                    val installed = commitReplacementOutput(
                        episode = episode,
                        staged = result.outputFile,
                        notificationId = notificationId,
                        runId = runId,
                    ) ?: return Result.failure()
                    outputPath = installed
                    completed = dao.finishReplacementIfOwned(
                        episodeId = episodeId,
                        expectedState = expectedWriteState(),
                        outputPath = installed,
                        runId = runId,
                    )
                } else {
                    outputPath = result.outputFile?.absolutePath ?: ""
                    completed = dao.updateCompletedIfOwned(
                        episodeId = episodeId,
                        expectedState = expectedWriteState(),
                        outputPath = outputPath,
                        runId = runId,
                    )
                }
                if (completed == 0) {
                    AppLog.d(
                        TAG,
                        "Completion of $episodeId lost the state write — a newer state wins, export skipped",
                    )
                    notificationManager.cancelNotification(notificationId)
                    return Result.failure()
                }
                AppLog.d(TAG, "Download completed for $episodeId: $outputPath")

                // Slice 6 (§30.1): export the completed MP4 into MediaStore while
                // this Worker still runs in foreground. NEVER demotes the completed
                // state or changes the Result — the local file remains the source
                // of truth and startup reconciliation retries the export (§30.4).
                exportToMediaStore(episodeId)

                notificationManager.cancelNotification(notificationId)
                // the completion CAS landed (the CAS-lost path
                // returned above), so the completion notification is posted —
                // gated by the `notify_completion` preference, read fresh.
                notifyTerminal(episode, completion = true, casLanded = true)

                // Next-deferred-episode trigger runs in doWork()'s finally on
                // ALL completion paths (F-C2) — not only on success.

                Result.success()
            }
        }
    }

    /**
     * Is THIS failure eligible for auto-resume?
     *
     * Reads the setting FRESH at the moment a transport failure is decided, so
     * flipping the switch shapes the very next failure instead of the next app
     * start (DECISIONS 105.4, same contract as the notification preferences).
     * A read failure is treated as OFF — a setting problem must never turn a
     * terminal failure into a retry loop.
     */
    private suspend fun autoResumeEligible(errorClass: DownloadErrorClass): Boolean {
        // A deterministic failure is terminal whatever the setting says, so the
        // store is not even read for it (§14: typed field only, never `error`).
        if (errorClass != DownloadErrorClass.TRANSPORT) return false
        val enabled = try {
            autoResumeProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Auto-resume setting read failed; treating as OFF: ${e.message}")
            false
        }
        return shouldAutoResume(errorClass, enabled, runAttemptCount)
    }

    /**
     * The retry-park sequence, replacing the
     * terminal path only when [autoResumeEligible] said yes.
     *
     * 1. CAS `DOWNLOADING → QUEUED`. Losing it means a concurrent Cancel (or
     *    any newer state) already won, so `null` is returned and the caller
     *    falls through to today's terminal handling — a park never fights a
     *    newer write.
     * 2. Cancel the progress notification; **no failure notification** is
     *    posted, because the row is not terminal.
     * 3. `Result.retry()` — WorkManager parks the request ENQUEUED with
     *    exponential backoff; `runAttemptCount` increments when that retry
     *    run next starts (ENQUEUED → RUNNING), not at this return (round-3
     *    NEW-2: WorkManager 2.11.2 increments in `trySetRunning`).
     *
     * The `finally` in [doWork] still runs `triggerNextDeferredEpisode` on this
     * path, and the parked row is an ordinary `QUEUED` row: it leaves
     * `countActive()` (DOWNLOADING|ASSEMBLING only), so the maxParallel gate and
     * the reconciler both see exactly what they see after today's deferrals.
     * The next eligible run re-claims QUEUED and resumes from `doneIndices`, so
     * completed segments are never re-downloaded.
     *
     * @return `Result.retry()` when the row really was parked, `null` when the
     * park lost its CAS.
     */
    private suspend fun parkForAutoResume(
        episodeId: String,
        notificationId: Int,
        runId: String,
    ): Result? {
        // Only reachable for DownloadErrorClass.TRANSPORT (see autoResumeEligible),
        // which is constructed exclusively from network fetches BEFORE Step 7 —
        // so this run has not persisted ASSEMBLING (final-audit H4) and the park
        // correctly expects DOWNLOADING. Ownership-guarded: a stale run must
        // never park a row a newer run has re-claimed back into QUEUED.
        val parked = dao.updateStateIfOwned(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_DOWNLOADING,
            newState = EpisodeDownloadEntity.STATE_QUEUED,
            runId = runId,
        )
        if (parked <= 0) {
            AppLog.w(
                TAG,
                "Auto-resume park of $episodeId lost the state/ownership write — " +
                    "a newer state or run wins, treating it as terminal",
            )
            return null
        }
        AppLog.i(
            TAG,
            "Auto-resume: parked $episodeId back to QUEUED " +
                "(runAttemptCount=$runAttemptCount, bound=$AUTO_RESUME_MAX_RETRIES runs before terminal)",
        )
        notificationManager.cancelNotification(notificationId)
        return Result.retry()
    }

    /**
     * `Replace file` (DECISIONS 152) — the COMMIT half of a replacement:
     * validate the staged output under the ONE §29 rule, then move it onto the
     * path the previous export occupies.
     *
     * The ordering is the safety property (not a claim of atomicity):
     *
     *  1. the staged file must verify as a real output (`exists && size > 0`,
     *     the same rule every consumer of a completed output applies) —
     *     otherwise nothing is touched and the old export is untouched;
     *  2. `renameTo` runs between two files inside the episode's OWN directory,
     *     so it is one same-filesystem `rename(2)`: the destination is replaced
     *     in a single step and a refusal leaves it byte-for-byte intact. There
     *     is never a moment where the target exists but is empty or partial;
     *  3. only after a successful move may the caller write `COMPLETED`
     *     ([EpisodeDownloadDao.finishReplacement]).
     *
     * The staging directory dies as soon as its contents have moved out — its
     * `.ts` segments are inputs that have already been consumed.
     *
     * @return the path the new output now occupies when the move landed (the
     *   caller may write `COMPLETED`); `null` when the commit was refused, in
     *   which case the failure is ALREADY persisted and posted and the caller
     *   only has to return `Result.failure()`.
     */
    private suspend fun commitReplacementOutput(
        episode: EpisodeDownloadEntity,
        staged: File?,
        notificationId: Int,
        runId: String,
    ): String? {
        if (staged == null || !outputVerifier.isPresent(null, staged.absolutePath)) {
            AppLog.w(TAG, "Replacement for ${episode.episodeId} produced no verifiable output — previous export kept")
            reportTerminalFailure(episode, "Replacement produced no verifiable output", notificationId, runId)
            return null
        }

        // The target is exactly the path the previous export occupies; only a
        // row that never recorded one falls back to its own directory plus the
        // staged file's (identical) name.
        val recordedPath = episode.outputPath?.takeIf { it.isNotBlank() }
        val canonicalDir = episode.segmentDir?.takeIf { it.isNotBlank() }
        val target = when {
            recordedPath != null -> File(recordedPath)
            canonicalDir != null -> File(canonicalDir, staged.name)
            else -> null
        }
        if (target == null) {
            AppLog.w(TAG, "Replacement for ${episode.episodeId} has no target path — previous export kept")
            reportTerminalFailure(episode, "No output path recorded for the replacement", notificationId, runId)
            return null
        }

        if (staged.absolutePath != target.absolutePath) {
            target.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (!staged.renameTo(target)) {
                AppLog.w(
                    TAG,
                    "Replacement move refused for ${episode.episodeId} — previous export untouched",
                )
                reportTerminalFailure(episode, "Could not install the replacement file", notificationId, runId)
                return null
            }
        }

        try {
            // A2.1 Decision 4 (DL-005): the replacement's staging directory
            // obeys `Keep temporary files` too — with it ON, the staged
            // segments stay as diagnostics after the swap, exactly like a
            // normal assembly's leftovers (EpisodeDownloader). The swapped-in
            // output itself lives at the TARGET path and is unaffected.
            staged.parentFile
                ?.takeIf { it.name == SEGMENT_STAGING_DIR_NAME && !config.keepTemp }
                ?.deleteRecursively()
        } catch (e: Exception) {
            AppLog.w(TAG, "Staging cleanup after replacement failed for ${episode.episodeId}: ${e.message}")
        }
        return target.absolutePath
    }

    /**
     * Slice 6 (PHASE11_ARCHITECTURE_V2 §30.1/§30.4): idempotent MediaStore export
     * of the just-completed local MP4.
     *
     * - Re-reads the row so the export sees the persisted outputPath/state.
     * - [ExportOutcome.Failed] (insert/copy/verify error, missing source) is
     *   logged only: the episode REMAINS COMPLETED with its local file intact;
     *   startup reconciliation retries the export.
     * - [CancellationException] propagates (worker/process death mid-copy leaves
     *   an invisible IS_PENDING row that §30.3 reuse or the §28 sweep handles).
     * - Never affects the Worker's [Result]: the download itself succeeded.
     */
    private suspend fun exportToMediaStore(episodeId: String) {
        val target = exporter ?: return
        val row = dao.getById(episodeId)
        if (row == null || row.outputPath.isNullOrBlank()) {
            AppLog.w(TAG, "Export skipped for $episodeId: no local output recorded")
            return
        }
        try {
            when (val outcome = target.export(row)) {
                is ExportOutcome.Exported ->
                    AppLog.i(TAG, "Exported $episodeId -> ${outcome.uri}")
                is ExportOutcome.AlreadyExported ->
                    AppLog.i(TAG, "Export already present for $episodeId -> ${outcome.uri}")
                is ExportOutcome.Failed ->
                    AppLog.w(TAG, "Export failed for $episodeId (local kept, reconciler retries): ${outcome.reason}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Export error for $episodeId (local kept): ${e.message}")
        }
    }

    /**
     * Post the terminal notification for
     * [episode] — completion or failure — under two hard rules:
     *
     *  1. **Only when the terminal CAS landed.** [casLanded] is the rows-affected
     *     value of the guarded write that just ran; `false` means a newer state
     *     won and this run no longer owns the row, so NOTHING is posted.
     *  2. **Only when the matching preference is ON**, read FRESH here
     *     (DECISIONS 105.4) so a Settings change applies to the next
     *     notification instead of to an in-flight run.
     *
     * Never throws: by the time this runs the Room terminal state already
     * exists, so a preference-read or posting problem must not change the
     * Worker's [Result]. An unreadable preference is treated as "do not post" —
     * posting is a user-visible action gated by an explicit opt-in flag, and
     * silence is the non-spamming failure mode.
     */
    private suspend fun notifyTerminal(
        episode: EpisodeDownloadEntity,
        completion: Boolean,
        casLanded: Boolean,
        /**
         * Slice C — the typed origin of a FAILURE, read from the result that is
         * still in scope at the call site. `null` means the failure had no typed
         * origin (a Worker-originating error such as missing stream info, or an
         * exception that escaped the Worker — neither travels through
         * `DownloadErrorClass`), and the notification then carries no category
         * at all rather than an invented one.
         */
        category: FailureCategory? = null,
        /**
         * `Replace file` (DECISIONS 152) — true only when the row this failure
         * belongs to went back to `COMPLETED` because a CONFIRMED replacement
         * produced nothing verifiable while its previous export survived. The
         * notification then says so explicitly: without this, a user would read
         * "Download failed" for an episode the Downloads screen still shows as
         * completed — two different stories about the same row.
         */
        replacementKeptExisting: Boolean = false,
    ) {
        if (!casLanded) return

        val enabled = try {
            val prefs = notifyPrefsProvider()
            if (completion) prefs.completion else prefs.failure
        } catch (e: CancellationException) {
            // The prefs read is a suspension point — a cancellation here is part
            // of the run and must propagate, never be swallowed as a skip.
            throw e
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "Notification preference read failed; skipping terminal notification: ${e.message}",
            )
            return
        }
        if (!enabled) return

        try {
            val terminalId = notificationManager.terminalIdFor(episode.episodeId)
            val notification = if (completion) {
                notificationManager.buildCompletionNotification(
                    episodeId = episode.episodeId,
                    episodeTitle = episode.title,
                    audio = episode.audio,
                )
            } else {
                notificationManager.buildFailureNotification(
                    episodeId = episode.episodeId,
                    episodeTitle = episode.title,
                    audio = episode.audio,
                    category = category,
                    replacementKeptExisting = replacementKeptExisting,
                )
            }
            notificationManager.updateNotification(terminalId, notification)
        } catch (e: Exception) {
            AppLog.w(TAG, "Terminal notification failed for ${episode.episodeId}: ${e.message}")
        }
    }

    /**
     * Trigger the next deferred episode if available.
     *
     *
     * The candidate set is now
     * ALL queued+m3u8 rows (oldest first), not just the oldest. The first
     * candidate whose unique work does not exist or is not unfinished is
     * enqueued; if every candidate has live work, this is a no-op (today's
     * behavior). This prevents a parked row (in backoff after auto-resume) from
     * blocking later deferred rows from being enqueued.
     */
    private suspend fun triggerNextDeferredEpisode() {
        val candidates = dao.getQueuedWithM3u8().take(TRIGGER_CANDIDATE_CAP)
        if (candidates.isEmpty()) return
        val activeCount = dao.countActive()
        if (activeCount < config.maxParallel) {
            // Phase 13 (DECISIONS 105) + Phase 14 (PHASE14_PLAN §9): a freshly
            // built request reads the CURRENT Wi-Fi-only and Auto-resume
            // settings (105.4) — this is one of the three build sites.
            val wifiOnly = wifiOnlyProvider()
            val autoResume = autoResumeProvider()
            val workManager = androidx.work.WorkManager.getInstance(applicationContext)

            for (nextEpisode in candidates) {
                val uniqueName = "episode_download_${nextEpisode.episodeId}"
                // DL-006/AQR-006: await the WorkManager future through its
                // completion listener (now the SHARED awaitUniqueWorkInfos —
                // this copy and DownloadStateReconciler's were byte-for-byte
                // duplicates). The old `future.get()` here blocked a dispatcher
                // thread for every candidate WorkManager round-trip — up to
                // TRIGGER_CANDIDATE_CAP times per finished episode.
                val workInfos = awaitUniqueWorkInfos(workManager, uniqueName)
                val hasLiveWork = workInfos.any { !it.state.isFinished }
                if (hasLiveWork) {
                    AppLog.d(TAG, "Skipping $uniqueName — work is unfinished")
                    continue
                }

                AppLog.d(TAG, "Triggering next deferred episode: ${nextEpisode.episodeId}")
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(androidx.work.workDataOf("episode_id" to nextEpisode.episodeId))
                    .addTag("episode_download_${nextEpisode.episodeId}")
                    .apply { applyDownloadWorkPolicy(wifiOnly, autoResume) }
                    .build()

                workManager.enqueueUniqueWork(
                    uniqueName,
                    androidx.work.ExistingWorkPolicy.KEEP,
                    workRequest
                )
                return
            }
        }
    }
}
