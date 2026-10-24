package com.pahebatcher.app.domain.download

import androidx.compose.runtime.Immutable

/**
 * What ONE `Replace file` confirmation REALLY did (`DownloadRepository.confirmReplacement`).
 *
 * The counterpart of [EnqueueOutcome] for the single request type that is
 * allowed to proceed past a valid completed export (DECISIONS 152). It is a
 * separate type rather than a new [EnqueueOutcome] value on purpose: every
 * ordinary enqueue path must keep returning the unchanged outcomes, so the
 * existing suppression tests and the §17.1 notice mapping stay byte-identical.
 *
 * | persisted row when the confirmation observes it | outcome |
 * |---|---|
 * | no row | [Unavailable] |
 * | not `COMPLETED` (a run is already in flight) | [Unavailable] |
 * | `COMPLETED`, output verified present, claim won | [Started] |
 * | `COMPLETED`, output verified present, claim lost to a concurrent confirmation | [AlreadyInFlight] |
 * | `COMPLETED`, output NOT present | [Unavailable] — the ordinary redownload path owns this case; no destructive dialog may be shown for it |
 * | row changed underneath the claim | [Refused] |
 */
@Immutable
sealed interface ReplacementOutcome {

    /**
     * THIS call claimed the row: `replaceRequested` is persisted and the
     * replacement is entering the ordinary resolve → queue → Worker pipeline.
     */
    data object Started : ReplacementOutcome

    /**
     * A confirmed replacement for this episode is already in flight — the
     * repeat was a no-op, never a second run. Idempotent by construction: the
     * claim is a guarded UPDATE with `replaceRequested = 0`.
     */
    data object AlreadyInFlight : ReplacementOutcome

    /**
     * There is no valid completed export to replace.
     *
     * This is the STALE-ACTION case (the episode vanished, is no longer
     * completed, or its output is gone) and also the "output was already
     * missing" case, which the ordinary re-download/recovery path owns. Either
     * way no confirmation dialog may be shown and no replacement is started.
     */
    data class Unavailable(val reason: String) : ReplacementOutcome

    /**
     * Nothing was queued: the row changed between classification and claim
     * (a Cancel, a concurrent completion, a deletion). Never reported as
     * [Started].
     */
    data object Refused : ReplacementOutcome
}
