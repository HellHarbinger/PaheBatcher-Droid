package com.pahebatcher.app.data.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Phase 13 (DECISIONS 105) + Phase 14 (PHASE14_PLAN §9, Decision 3) — the
 * ONE canonical network-constraint rule, shared by the THREE WorkRequest build
 * sites:
 *
 *  1. `DownloadRepository.enqueueWorker` (initial queue / retry re-enqueue),
 *  2. `DownloadWorker.triggerNextDeferredEpisode` (deferred-episode trigger),
 *  3. `DownloadStateReconciler.reEnqueueWorker` (startup reconciliation).
 *
 * Phase 14 extends the rule from one toggle to two. The matrix is explicit:
 *
 * | Wi-Fi-only | Auto-resume | Attached constraint | Behavior |
 * |---|---|---|---|
 * | OFF | OFF | none | byte-for-byte today's default behavior |
 * | OFF | ON | `CONNECTED` | starts/retries only with any network |
 * | ON | OFF | `UNMETERED` | exactly Phase 13's shipped behavior |
 * | ON | ON | `UNMETERED` | Wi-Fi dominates — `UNMETERED` implies connectivity |
 *
 * Contract (unchanged from Phase 13):
 *
 *  - The value is read fresh at every enqueue (105.4); already-enqueued
 *    requests keep the constraints they were built with and are never
 *    rewritten when a toggle flips (105.2).
 *  - The matrix is an **enqueue-time** rule by design; the retry decision
 *    (§10) is a **run-time** rule.
 *
 * `wifiOnlyConstraints(wifiOnly)` as a standalone entry point is removed in the
 * same slice; `WifiOnlyConstraintTest` moves to the combined function and
 * asserts the full 4-case matrix (§17).
 */

/** Exponential backoff base for auto-resume retries. */
const val AUTO_RESUME_BACKOFF_SECONDS = 30L

/**
 * The canonical constraint decision.
 *
 * Returns `null` when neither toggle is ON (byte-for-byte today's behavior).
 * When both are ON, `UNMETERED` dominates because it subsumes `CONNECTED`.
 */
fun downloadNetworkConstraints(wifiOnly: Boolean, autoResume: Boolean): Constraints? =
    when {
        wifiOnly -> Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        autoResume -> Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        else -> null
    }

/**
 * Apply the full download work policy to a
 * WorkRequest builder: the canonical network constraint (when either toggle is
 * ON) and the exponential backoff criteria (§15).
 *
 * This is the ONE function all three build sites call — no site composes
 * constraints or backoff independently.
 */
fun <B : WorkRequest.Builder<B, *>> B.applyDownloadWorkPolicy(wifiOnly: Boolean, autoResume: Boolean) {
    downloadNetworkConstraints(wifiOnly, autoResume)?.let { setConstraints(it) }
    setBackoffCriteria(BackoffPolicy.EXPONENTIAL, AUTO_RESUME_BACKOFF_SECONDS, TimeUnit.SECONDS)
}
