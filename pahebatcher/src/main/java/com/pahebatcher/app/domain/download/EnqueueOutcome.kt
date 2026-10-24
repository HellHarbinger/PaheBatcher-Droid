package com.pahebatcher.app.domain.download

import androidx.compose.runtime.Immutable

/**
 * What ONE [EpisodeDownloadQueue.queueResolvedEpisode] call REALLY did.
 *
 * Slice B: before this type the seam returned `Unit`, so every caller had to
 * ASSUME success — and `DownloadRepository` suppressed four whole classes of
 * request (`DOWNLOADING`, `ASSEMBLING`, `PAUSED`, and `COMPLETED` with a
 * present output) with a silent `return`, while the Anime Details screen still
 * painted "In the download queue" and batch/Watchlist counters still did
 * `queued++`. The row was never queued; the message was a lie.
 *
 * The model is the smallest set that every state the production row can
 * actually be in maps onto. No value exists that the pipeline cannot produce,
 * and no reachable state is left without one:
 *
 * | persisted row when the call observes it | outcome |
 * |---|---|
 * | no row, no surviving destination | [Accepted] |
 * | no row, surviving export at the destination (§3.1) | [AlreadyDownloaded] |
 * | `FAILED` / `CANCELLED` | [Accepted] (the legitimate re-queue) |
 * | `COMPLETED`, output verified present | [AlreadyDownloaded] |
 * | `COMPLETED`, output missing but a surviving export sits at the destination (§3.1) | [AlreadyDownloaded] |
 * | `COMPLETED`, output missing and no surviving destination | [OutputMissing] |
 * | `PAUSED` | [AlreadyPaused] |
 * | `DOWNLOADING` / `ASSEMBLING` | [AlreadyActive] |
 * | `QUEUED` WITH a persisted stream | [AlreadyActive] |
 * | `RESOLVING` | [AlreadyActive] |
 * | `QUEUED` WITHOUT a persisted stream | [Accepted] |
 *
 * **The destination-aware rows (§3.1, DECISIONS 157).** Two rows above are
 * answered with the exporter's destination probe in addition to the row
 * lookup: a submission whose row is absent (app-private state destroyed) or
 * whose `COMPLETED` pointers are severed (MediaStore URI churn) but whose
 * export still survives at the episode's own destination is the SAME no-op as
 * a healthy `COMPLETED` row — [AlreadyDownloaded] — instead of a re-download
 * whose only observable result already exists. The startup reconciler asks the
 * same probe and REPAIRS such a row onto the surviving URI rather than
 * demoting it to `FAILED ("file missing")`, so the two consumers can never
 * contradict each other about one row (Slice B M-1). A probe that finds
 * nothing — or cannot be probed at all — falls back to the rows above
 * unchanged: the destination question may only ever tighten a suppression,
 * never invent one.
 *
 * The last two rows are the subtle ones and they are deliberately different:
 *
 * * A `QUEUED` row that already owns a stream will be delivered by the Worker
 *   the pipeline already registered, so this call adds nothing the user needs
 *   to know about — [AlreadyActive].
 * * A `QUEUED` row WITHOUT a stream is the reconciler's stream-less "zombie"
 *   (`DownloadStateReconciler` resets an interrupted `RESOLVING` row to
 *   `QUEUED` and clears its credentials). Nothing will ever download it until a
 *   foreground submission repopulates the stream and [resolveComplete] registers
 *   the Worker — which is exactly what this call does. Calling that
 *   "already active" would understate work the app just performed, so it is
 *   [Accepted] (review finding H-1).
 *
 * **Honesty contract.** [Accepted] is only returned when the Worker hand-off
 * really happened: `resolveComplete`'s CAS is propagated back out, so a Cancel,
 * Pause or reconciler move landing between the row write and the hand-off is
 * reported as the state it produced instead of as success (review finding H-2).
 * A genuine persistence or WorkManager failure still THROWS — the caller keeps
 * reporting the real error through its own error channel — so a non-throwing
 * return is the only source of these values.
 *
 * **The sixth value.** The table above classifies a row the call found BEFORE
 * it mutated anything. It does not cover the one row that can change *during*
 * the call: a hand-off whose CAS lost, leaving the row `FAILED`, `CANCELLED`
 * or deleted. No row in the table describes that, because none of them is
 * about a row the pipeline actively refuses to own — hence [Refused] (review
 * finding F1). Before it existed that row was reported [AlreadyActive], whose
 * own contract promises a row "the pipeline owns", so the user was told
 * "Already in the download queue" for an episode that was cancelled or failed
 * and has no Worker at all.
 *
 * Compose reads this type from [com.pahebatcher.app.ui.details.AnimeDetailsUiState],
 * which is `@Immutable`; every value is a stable singleton.
 */
@Immutable
sealed interface EnqueueOutcome {

    /**
     * This submission really entered the pipeline: the row was created, or a
     * terminal row was re-queued, or a stream-less `QUEUED` row was repaired —
     * and the unique `DownloadWorker` hand-off completed.
     */
    data object Accepted : EnqueueOutcome

    /**
     * No new download was started by this call — the episode already had a row
     * the pipeline owns (`QUEUED` with a stream, `RESOLVING`, `DOWNLOADING`,
     * `ASSEMBLING`).
     *
     * State-wise, not action-wise: a call that observes one of these rows may
     * still refresh the persisted stream and re-assert the unique work under
     * `ExistingWorkPolicy.KEEP`. What it never does is start a second download.
     */
    data object AlreadyActive : EnqueueOutcome

    /**
     * The episode's output was verified present — a MediaStore/SAF URI that
     * really resolves with size > 0, or (pre-export rows) a non-empty local
     * file — OR the §3.1 destination probe found the surviving export at this
     * episode's own destination (audio-suffixed name, per-anime directory,
     * owner-scoped, non-pending, non-empty) even though the row is absent or
     * its pointers are severed. Never inferred from a database column or a
     * non-blank URI alone.
     */
    data object AlreadyDownloaded : EnqueueOutcome

    /**
     * The episode is `COMPLETED` in Room but its output is missing, empty or
     * unresolvable — the user deleted the export, or the reference is stale —
     * AND the §3.1 destination probe found no surviving export either.
     *
     * Nothing is deleted and nothing is renamed in response: the existing safe
     * re-queue path runs and the row is downloaded again. The message must
     * never read as "already downloaded".
     */
    data object OutputMissing : EnqueueOutcome

    /**
     * The episode is `PAUSED` — a durable user intent (`SPEC §20`) that only
     * Resume, Cancel or Retry may leave. A re-submission must not restart work
     * the user deliberately stopped, and must not claim it did.
     */
    data object AlreadyPaused : EnqueueOutcome

    /**
     * This submission's hand-off was REFUSED by a concurrent state change, and
     * the row it found is terminal (`FAILED` / `CANCELLED`) or gone — so this
     * call queued NOTHING.
     *
     * Produced only by the refused-hand-off path: `resolveComplete`'s CAS lost
     * to a Cancel, Pause, retry or reconciler move, so no `DownloadWorker` was
     * registered for THIS submission. It is deliberately distinct from
     * [AlreadyActive], which promises a row the pipeline owns and will deliver
     * on its own — a cancelled or failed row is delivered by nobody, and the
     * honest response is to tell the user so they can retry.
     *
     * Never interchangeable with [Accepted]: a refused claim can never be
     * reported as success (review finding H-2), and [AlreadyDownloaded],
     * [OutputMissing] and [AlreadyPaused] each describe a row that really does
     * exist and really does mean what they say.
     */
    data object Refused : EnqueueOutcome
}
