package com.pahebatcher.app.data.download

import android.content.ContentResolver
import com.pahebatcher.app.logging.AppLog
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.export.ExportOutcome
import com.pahebatcher.app.data.hls.SegmentFileStore
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reconciles download state on app startup.
 *
 *
 * The COMPLETED invariant is now
 * outputUri-aware:
 *
 *   validity = (outputUri present ∧ URI resolves ∧ size > 0)
 *            ∨ (outputUri absent ∧ outputPath exists ∧ size > 0)
 *
 * Pre-export rows (outputUri NULL) keep working unchanged. An exported-then-
 * user-deleted video flips to FAILED ("file missing") — honest state with the
 * existing Retry affordance. When no ContentResolver is injected (host-constructed
 * tests without a resolver), rows with outputUri fall back to the outputPath rule
 * (documented §29 extension behavior — resolver is required to resolve URIs, and
 * §30.1 mandates constructor injection from the AppContainer in production).
 *
 * Handles all states defined by v10:
 * - QUEUED without m3u8Url: awaiting foreground resolution (no action)
 * - QUEUED with m3u8Url: resolved but Worker not enqueued → enqueue
 * - RESOLVING: resolution interrupted → reset to QUEUED
 * - DOWNLOADING: Worker may have died → check WorkManager, re-enqueue if needed
 * - ASSEMBLING: may have completed before process death → check output
 * - COMPLETED: verify invariant (outputUri-aware), retry export (Slice 6, §30),
 *   clean orphaned segments
 * - FAILED/CANCELLED: terminal, no action
 * - PAUSED (final SPEC §20 gap): a deliberate user stop — NOT a stale row.
 *   Never re-enqueued, never reset, never auto-resumed by a connectivity sweep:
 *   only `DownloadRepository.resume()` (or an explicit Cancel/Retry) leaves it.
 *
 * Slice 6 (PHASE11_ARCHITECTURE_V2 §28/§30/§36-7):
 * - VALID COMPLETED rows run one idempotent [EpisodeExporter.export] attempt:
 *   finishes interrupted bookkeeping (linger­ing IS_PENDING, surviving local
 *   duplicate — §30.3 rows 1/4) or retries an export the Worker never started
 *   (process death between `updateCompleted` and export). Export outcomes are
 *   logged ONLY — reconciliation never flips state because of them (§30.4).
 * - Invalid rows get a fresh `getById` re-read before the FAILED demotion: export
 *   persists `outputUri` (X4) before deleting the local file (X6), so a snapshot
 *   that looked broken may have become valid concurrently.
 * - Stale `IS_PENDING` rows from a previous process are swept once per run (§28),
 *   bounded by [sweepCutoffEpochMs] and never removing Room-referenced URIs.
 *
 * Idempotent: safe to run repeatedly.
 */
class DownloadStateReconciler(
    private val episodeDao: EpisodeDownloadDao,
    private val workManager: WorkManager,
    /**
     * §30.1: constructor-injected resolver used to resolve persisted `outputUri`
     * values (query/openInputStream) — never scattered static/context lookups.
     * Optional (default null) so existing host-constructed tests keep compiling;
     * production wiring (AppContainer) passes the real resolver.
     */
    private val contentResolver: ContentResolver? = null,
    /**
     * Slice 6 (§30): export owner for (a) retrying exports of VALID COMPLETED
     * rows whose Worker died before finishing §30.1 and (b) the §28 stale
     * IS_PENDING sweep. Optional (default null): host-constructed tests keep
     * pre-export behavior; production passes AppContainer.episodeExporter —
     * Phase 13's destination router (DECISIONS 92.2), which behaves exactly like
     * the MediaStore exporter whenever no usable SAF tree is configured.
     */
    private val exporter: EpisodeExporter? = null,
    /**
     * Slice 6 (§28): pending rows with `DATE_ADDED` strictly older than this are
     * sweep-eligible. Defaults to this construction instant — ≈ process start in
     * production — so pending rows created by the CURRENT run are never swept
     * while a copy may still be in flight.
     */
    private val sweepCutoffEpochMs: Long = System.currentTimeMillis(),
    /**
     * The CURRENT Wi-Fi-only setting, read fresh at
     * every re-enqueue — reconciliation builds NEW WorkRequests, so each one
     * reflects the persisted value at that moment (never a run-start snapshot).
     * Default OFF keeps every existing host-constructed test enqueueing exactly
     * as before (the setting's own default, 105.5).
     */
    private val wifiOnlyProvider: suspend () -> Boolean = { false },
    // the CURRENT `Auto-resume downloads` setting,
    // read fresh at enqueue time. Default OFF = the setting's own shipped
    // default, so existing call sites keep today's behavior.
    private val autoResumeProvider: suspend () -> Boolean = { false },
    /**
     * A2.1 Decision 4 (DL-005): the CURRENT `Keep temporary files` setting,
     * consulted per COMPLETED row before the segment sweep. Default OFF keeps
     * every existing host-constructed test (and the shipped default) cleaning
     * exactly as before; with it ON, a completed episode's leftover `.ts` are
     * diagnostic data the user asked to keep and startup reconciliation must
     * never delete them behind the user's back.
     */
    private val keepTempProvider: suspend () -> Boolean = { false },
) {

    companion object {
        private const val TAG = "DownloadStateReconciler"
    }

    /**
     * Slice B (review finding M-1): the §29 rule now lives in ONE place shared
     * with `DownloadRepository`, so the two consumers can never contradict each
     * other about the same row. Built from the injected [contentResolver]; the
     * constructor signature is unchanged, so every host-constructed test keeps
     * building this exactly as before.
     */
    private val outputVerifier = EpisodeOutputVerifier(contentResolver)

    /**
     * Run reconciliation.
     * Idempotent: safe to call multiple times.
     */
    suspend fun reconcile() {
        AppLog.d(TAG, "Starting reconciliation")

        // Fast-path: skip if no episodes exist at all
        val totalCount = episodeDao.getTotalEpisodeCount()
        if (totalCount == 0) {
            AppLog.d(TAG, "No episodes, skipping reconciliation")
            // Slice 6 (§28): an empty episode table never implies an empty export
            // directory — a previous process can leave IS_PENDING rows behind.
            sweepStalePending()
            return
        }

        // Get all episodes that may need reconciliation
        val episodes = episodeDao.getEpisodesForReconciliation()
        AppLog.d(TAG, "Found ${episodes.size} episodes for reconciliation")

        for (episode in episodes) {
            try {
                reconcileEpisode(episode)
            } catch (e: CancellationException) {
                // AQR-004: the reconcile run is cancellable — a cancelled loop
                // must stop, not be logged as a per-episode error.
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Error reconciling ${episode.episodeId}: ${e.message}")
            }
        }

        // Slice 6 (§28): sweep stale pending rows once per run AFTER the episode
        // loop. This run's own pending rows postdate sweepCutoffEpochMs and are
        // excluded by the cutoff; orphans from a previous process are removed.
        sweepStalePending()

        AppLog.d(TAG, "Reconciliation complete")
    }

    /**
     * Slice 6 (§28): delete `IS_PENDING` export rows orphaned by a previous
     * process. No-op without an injected exporter (host-constructed tests).
     * Failures are logged, never propagated — a sweep problem must not fail
     * reconciliation.
     */
    private suspend fun sweepStalePending() {
        val target = exporter ?: return
        try {
            val swept = target.sweepStalePending(sweepCutoffEpochMs)
            if (swept > 0) {
                AppLog.d(TAG, "Swept $swept stale pending export row(s)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Stale pending sweep failed: ${e.message}")
        }
    }

    /**
     * Slice 6 (§30.1/§30.4): one idempotent export attempt for a VALID COMPLETED
     * row. No-op when no exporter is injected (host-constructed tests).
     *
     * Every outcome is logged ONLY: reconciliation never changes episode state
     * because of an export result. The row already satisfies the §29 invariant
     * and a failed export keeps the local file as source of truth (§30.4) —
     * the next startup retries.
     */
    private suspend fun attemptExport(episode: EpisodeDownloadEntity) {
        val target = exporter ?: return
        try {
            when (val outcome = target.export(episode)) {
                is ExportOutcome.Exported ->
                    AppLog.d(TAG, "Reconcile exported ${episode.episodeId} -> ${outcome.uri}")
                is ExportOutcome.AlreadyExported ->
                    AppLog.d(TAG, "Reconcile verified export for ${episode.episodeId} -> ${outcome.uri}")
                is ExportOutcome.Failed ->
                    AppLog.w(TAG, "Reconcile export retry failed for ${episode.episodeId} (local kept): ${outcome.reason}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Reconcile export retry errored for ${episode.episodeId}: ${e.message}")
        }
    }

    private suspend fun reconcileEpisode(episode: EpisodeDownloadEntity) {
        when (episode.state) {
            EpisodeDownloadEntity.STATE_COMPLETED -> {
                // COMPLETED invariant is outputUri-aware —
                // (outputUri present ∧ resolves ∧ size > 0) ∨ (outputUri absent ∧
                // outputPath exists ∧ size > 0). Exported rows prefer the URI even
                // if the local MP4 was deleted post-export (X6).
                var current = episode
                if (!completedOutputValid(current)) {
                    // Slice 6 (§36-7): fresh read before demoting. Export persists
                    // outputUri (X4) BEFORE deleting the local file (X6), so a
                    // snapshot that looked broken may have become valid concurrently
                    // (Worker finishing its export right now) — never demote it.
                    val fresh = episodeDao.getById(current.episodeId)
                    if (fresh == null || !completedOutputValid(fresh)) {
                        // §3.1 (DECISIONS 157): destination-aware convergence — the
                        // remembered pointer is dead but the export itself still
                        // survives at this episode's destination (MediaStore URI
                        // churn: backup/restore, storage migration). Repair the row
                        // onto the surviving URI instead of demoting it, so THIS
                        // consumer can never contradict the submission gate, which
                        // calls the very same probe "already downloaded" (Slice B
                        // M-1: one question, one answer — the demotion's "file
                        // missing" may only fire when the file really is gone).
                        val survived = fresh?.let { destinationSurvives(it) }
                        if (survived == null) {
                            AppLog.w(TAG, "COMPLETED episode ${current.episodeId} output missing/empty, marking FAILED")
                            // §29: honest failure carrying the retry affordance's reason.
                            // 11.5E item (e), reviewer M5: CAS-guarded — the fresh read
                            // above could already be stale (the row re-completed or was
                            // re-enqueued concurrently), so the demotion may only land on
                            // a row still COMPLETED.
                            episodeDao.updateErrorIfState(
                                episodeId = current.episodeId,
                                expectedState = EpisodeDownloadEntity.STATE_COMPLETED,
                                errorMessage = "file missing",
                            )
                            return
                        }
                        val repaired = episodeDao.repairOutputUriIfState(
                            episodeId = fresh.episodeId,
                            expectedState = EpisodeDownloadEntity.STATE_COMPLETED,
                            outputUri = survived,
                        )
                        if (repaired == 0) {
                            // The row moved (Cancel/Retry/re-queue) between the probe
                            // and the repair — the state it produced owns the next step.
                            return
                        }
                        AppLog.d(TAG, "COMPLETED episode ${fresh.episodeId} re-pointed to surviving export")
                        current = fresh.copy(outputUri = survived)
                    } else {
                        current = fresh
                    }
                }

                // Slice 6 (§30): idempotent export bookkeeping for a VALID row —
                //  - outputUri set: finish interrupted bookkeeping (clear a lingering
                //    IS_PENDING, remove a surviving local duplicate — §30.3 rows 1/4)
                //  - outputUri absent: retry the export the Worker never finished
                //    (process death between updateCompleted and export — §36-7)
                // Never state-flipping: the row is valid either way (§30.4).
                attemptExport(current)

                // Local source of truth for cleanup: capture the (possibly
                // export-refreshed) segmentDir once — a val the compiler can
                // smart-cast instead of nesting two identical null checks.
                val segDirPath = current.segmentDir
                if (segDirPath != null) {
                    // Output exists — remove leftover segment artifacts.
                    // selective cleanup — the
                    // assembled MP4 lives INSIDE segmentDir (architecture §2),
                    // so deleteRecursively() here would delete the completed
                    // output and flip the episode to FAILED on the next run.
                    // Slice 6 note: if the export above just removed the local MP4
                    // (X6), cleanup only sees segment leftovers — still correct.
                    val segDir = File(segDirPath)
                    // A2.1 Decision 4 (DL-005): the sweep HONOURS `Keep temporary
                    // files`, read fresh per row (provider, never a snapshot).
                    if (segDir.exists() && !keepTempProvider()) {
                        SegmentFileStore(segDir).cleanup()
                        AppLog.d(TAG, "Cleaned orphaned segments for ${current.episodeId}")
                    }
                }
            }

            EpisodeDownloadEntity.STATE_FAILED, EpisodeDownloadEntity.STATE_CANCELLED -> {
                // Terminal — no action (user must re-enqueue)
            }

            EpisodeDownloadEntity.STATE_PAUSED -> {
                // Deliberate user intent (SPEC §20 Pause) — NOT a stale row.
                //
                // Paused is non-terminal, so every recovery rule that would fire
                // for a live row is exactly wrong here: the Worker was cancelled
                // ON PURPOSE, the segment directory is warm and must be kept for
                // the resume, and an auto-resume/connectivity sweep must not
                // restart work the user stopped. Nothing is written, nothing is
                // re-enqueued — only [DownloadRepository.resume] leaves this
                // state (or Cancel/Retry, which the user asks for explicitly).
            }

            EpisodeDownloadEntity.STATE_ASSEMBLING -> {
                // outputPath is NULL during ASSEMBLING — compute expected path from metadata
                if (episode.segmentDir != null) {
                    val outputFile = computeExpectedOutput(episode)
                    if (outputFile != null && outputFile.exists() && outputFile.length() > 0L) {
                        // Assembly completed before process death — mark COMPLETED
                        AppLog.d(TAG, "ASSEMBLING episode ${episode.episodeId} output exists, marking COMPLETED")
                        episodeDao.updateCompleted(episode.episodeId, outputFile.absolutePath)
                    } else {
                        // Assembly incomplete — re-enqueue Worker
                        AppLog.d(TAG, "ASSEMBLING episode ${episode.episodeId} incomplete, re-enqueuing")
                        reEnqueueWorker(episode.episodeId)
                    }
                } else {
                    // No segmentDir — cannot compute output path, re-enqueue
                    reEnqueueWorker(episode.episodeId)
                }
            }

            EpisodeDownloadEntity.STATE_DOWNLOADING -> {
                // Check if Worker is already running before enqueuing
                // AQR-006: the shared awaitUniqueWorkInfos (this helper was a
                // byte-for-byte duplicate of DownloadWorker's DL-006 bridge).
                val workInfos = awaitUniqueWorkInfos(workManager, "episode_download_${episode.episodeId}")
                val isRunning = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                if (!isRunning) {
                    // Worker not running — transition to QUEUED and re-enqueue
                    AppLog.d(TAG, "DOWNLOADING episode ${episode.episodeId} has no running Worker, re-enqueuing")
                    episodeDao.updateStateForResume(
                        episode.episodeId,
                        EpisodeDownloadEntity.STATE_DOWNLOADING,
                        EpisodeDownloadEntity.STATE_QUEUED
                    )
                    reEnqueueWorker(episode.episodeId)
                }
                // else: Worker already running — no action needed
            }

            EpisodeDownloadEntity.STATE_RESOLVING -> {
                // Resolution interrupted — reset to QUEUED (requires foreground WebView)
                AppLog.d(TAG, "RESOLVING episode ${episode.episodeId} interrupted, resetting to QUEUED")
                episodeDao.updateState(episode.episodeId, EpisodeDownloadEntity.STATE_QUEUED)
                episodeDao.clearStreamInfo(episode.episodeId)
            }

            EpisodeDownloadEntity.STATE_QUEUED -> {
                if (episode.m3u8Url != null) {
                    // Resolved but Worker not enqueued — enqueue Worker
                    AppLog.d(TAG, "QUEUED episode ${episode.episodeId} has m3u8Url, enqueuing Worker")
                    reEnqueueWorker(episode.episodeId)
                }
                // else: awaiting foreground resolution — no action
            }
        }
    }

    /**
     * Phase 11 §29 outputUri-aware COMPLETED validity check.
     *
     * Delegates to the shared [EpisodeOutputVerifier] (Slice B, review finding
     * M-1) — the rule itself is unchanged from what this class owned before:
     *
     * - `outputUri` present + resolver present → the URI must resolve (query
     *   succeeds) with size > 0; a user-deleted export → invalid → FAILED.
     * - `outputUri` present + NO resolver injected (host-constructed tests) →
     *   fall back to the outputPath rule (documented extension behavior).
     * - `outputUri` absent (pre-export row) → today's outputPath rule unchanged.
     */
    private fun completedOutputValid(episode: EpisodeDownloadEntity): Boolean =
        outputVerifier.isPresent(outputUri = episode.outputUri, outputPath = episode.outputPath)

    /**
     * §3.1 (DECISIONS 157): the surviving-URI probe, consulted BEFORE a COMPLETED
     * row is demoted to `FAILED ("file missing")`.
     *
     * The [exporter] injected here is the production destination router, so this
     * asks the exact same question — with the exact same row-derived arguments —
     * as `DownloadRepository.destinationSurvives` at submission time: one probe,
     * one answer, two consumers (Slice B M-1). A `null` exporter (host-constructed
     * tests), an unprobed (SAF) destination, or ANY probe failure resolves to
     * "no surviving export" → the demotion proceeds exactly as before: a probe
     * may only ever UNDER-suppress, never keep a genuinely broken row COMPLETED.
     */
    private suspend fun destinationSurvives(episode: EpisodeDownloadEntity): String? {
        val probe = exporter ?: return null
        return try {
            withContext(Dispatchers.IO) {
                probe.destinationOutput(
                    episodeNumber = episode.episodeNumber,
                    title = episode.title,
                    audio = episode.audio,
                    animeTitle = null, // the exporter resolves the session-row title itself
                    sessionId = episode.sessionId,
                )
            }?.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Destination probe errored; demoting as before: ${e.message}")
            null
        }
    }

    /**
     * Compute expected output path from episode metadata.
     * Used during ASSEMBLING reconciliation when outputPath is not yet persisted.
     */
    private fun computeExpectedOutput(episode: EpisodeDownloadEntity): File? {
        val segmentDir = episode.segmentDir ?: return null
        val dir = File(segmentDir)
        // Look for any .mp4 file in the segment directory
        val mp4Files = dir.listFiles { file -> file.extension == "mp4" && file.length() > 0 }
        return mp4Files?.firstOrNull()
    }

    /**
     * Re-enqueue a Worker for an episode.
     *
     * Phase 13 (DECISIONS 105.4) + Phase 14 (PHASE14_PLAN §9): suspends to
     * read the CURRENT Wi-Fi-only and Auto-resume settings — this is one of
     * the three WorkRequest build sites, and a NEW request must reflect the
     * values at the moment it is built.
     */
    private suspend fun reEnqueueWorker(episodeId: String) {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.pahebatcher.app.worker.DownloadWorker>()
            .setInputData(androidx.work.workDataOf("episode_id" to episodeId))
            .apply { applyDownloadWorkPolicy(wifiOnlyProvider(), autoResumeProvider()) }
            .build()

        workManager.enqueueUniqueWork(
            "episode_download_$episodeId",
            androidx.work.ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}
