package com.pahebatcher.app.ui.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pahebatcher.app.data.network.NetworkMonitor
import com.pahebatcher.app.data.network.NetworkStatus
import com.pahebatcher.app.data.network.UnknownNetworkMonitor
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.AudioOptionRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.stream.AudioSelector
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.download.FailureCategory
import com.pahebatcher.app.domain.download.ResolveAheadGate
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.AudioProbeResult
import com.pahebatcher.app.domain.model.AudioProbeState
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.ScanProgress
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.model.variantOrCarrier
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.domain.stream.StreamResolutionException
import com.pahebatcher.app.domain.stream.StreamResolutionRetry
import com.pahebatcher.app.domain.util.EpisodePaging
import com.pahebatcher.app.domain.util.EpisodeRangeParser
import com.pahebatcher.app.domain.util.StringUtils
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.notification.DownloadNotifyPrefs
import com.pahebatcher.app.notification.NotificationPermissionRepository
import com.pahebatcher.app.ui.mapper.DownloadRowMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Immutable anime-details UiState (PHASE11_ARCHITECTURE_V2 §13, 11.5E-2, extended by 11.5E-3).
 *
 * Branch semantics mirror the other screens — one state object, no sealed
 * hierarchy:
 *
 * Scan branches (11.5E-2):
 *
 * - `loading = true` — the scan for [session] is pending (the construction
 *   default too, exactly like [com.pahebatcher.app.ui.downloads.DownloadsUiState.isLoading]:
 *   a details entry always starts a scan).
 * - `loading = false`, `error = null`, `episodes` non-empty — real episodes from
 *   [EpisodeRepository.scan].
 * - `loading = false`, `error = null`, `episodes` empty — an honest empty result
 *   (the scan answered, nothing was found); never converted into an error.
 * - `error != null` — the scan failed; the message is the repository's own
 *   failure (or [ERROR_SCAN_FAILED] when it carries none). Never silently
 *   rendered as an empty list.
 *
 * Stream-resolution branches (11.5E-3) — at most one is active and they are
 * derived from four fields, exactly like the other flat states:
 *
 * - idle: [selectedEpisode] is `null` — nothing selected, no resolution panel.
 * - resolving: [selectedEpisode] set, [resolving] `true`, result/error both `null`.
 * - resolved: [selectedEpisode] set, [resolving] `false`, [resolvedStream] non-null
 *   — the StreamInfo the injected [StreamResolver] really returned.
 * - failed: [selectedEpisode] set, [resolving] `false`, [resolutionError] non-null —
 *   the resolver's own failure (or [ERROR_RESOLUTION_FAILED] when it carries none),
 *   with [resolvedStream] `null`. A failure never renders as success.
 *
 * Download hand-off branches (11.5E-4) — only meaningful once a stream is
 * resolved, and always about the SAME [selectedEpisode]:
 *
 * * [enqueuing] `true` — the hand-off to [EpisodeDownloadQueue] is running.
 * * [enqueueOutcome] non-null — the repository's own verdict on that hand-off.
 *   Only [EnqueueOutcome.Accepted] means the episode really entered the queue
 *   (and it is the only value [enqueued] is `true` for); the other FIVE are
 *   honest verdicts of their own — `AlreadyActive`, `AlreadyDownloaded` and
 *   `AlreadyPaused` describe work the pipeline already covers,
 *   [EnqueueOutcome.OutputMissing] starts it again, and
 *   [EnqueueOutcome.Refused] means the pipeline declined outright — and the
 *   screen renders each one's own message instead of claiming a queue entry
 *   (DECISIONS 146).
 * * [enqueueError] non-null — the hand-off failed; tapping Download again retries
 *   the same real call.
 *
 * [title] is whatever the repository resolved (HTML title falling back to the API
 * title, `EpisodeRepository.scan`); it is `null` until a scan completes, so the
 * screen never shows an invented name.
 *
 * Audio-context model (Option C, DECISIONS 143 / SPEC §22) — three behavioral
 * states, no stored execution map:
 *
 * - [selectedAudio] — the screen-level audio context (an open-set value the
 *   option set offers; the deterministic INITIAL value is
 *   [initialAudioContext], a starting point only, never labelled "original");
 * - [selectedNumbers] — the selected episode numbers — a context change keeps
 *   ALL of them (DECISIONS 144.6: availability is decided when an audio
 *   resolves, so nothing is ever deselected up front and nothing is reported);
 * - [explicitAudioOverrides] — per-number exceptions chosen explicitly
 *   against the context; a number's effective audio is `override ?: context`
 *   ([effectiveAudio]) for single resolution and batch execution alike;
 * - [audioOptions] / [audioProbeState] — the discovered option set and the
 *   persisted probe state it came from (DECISIONS 144).
 *
 * The list the screen renders is derived from those states
 * ([visibleEpisodeRows]): one row per episode NUMBER carrying its effective
 * audio — variants of other audios are never separate top-level rows, and no
 * number is hidden because release-listing metadata lacks an audio.
 */
/**
 * Slice C — one batch episode that never reached the download pipeline, carrying
 * the category it was CLASSIFIED as at the moment the failure happened.
 *
 * The category is decided where the failure occurs, while the exception object
 * is still in scope, so it comes from typed origin and is never re-derived from
 * message text afterwards. That distinction matters: the sentence
 * `AudioSelector.unavailableMessage` produces is emitted both for a play page
 * that genuinely lacks the requested audio and for an episode number that is
 * absent from the listing (where the resolver is never called), so reading the
 * sentence back would mis-report the second as the first.
 *
 * This is UI state, not authoritative state. A resolution-stage failure creates
 * no download row by definition ([com.pahebatcher.app.data.download.DownloadRepository.createRow]
 * runs only once a stream resolved), so nothing here survives the ViewModel —
 * and no process-death recovery is claimed for it.
 */
data class BatchEpisodeFailure(
    /** The episode NUMBER this failure belongs to. */
    val number: Float,
    /** The typed origin of the failure. */
    val category: FailureCategory,
    /** The honest human-readable reason shown to the user (SPEC §26). */
    val reason: String,
    /** The audio that was REQUESTED, or `null` when the run requested none. */
    val audio: String? = null,
) {
    /** The user-facing audio label, or `null` when no audio was requested. */
    val audioLabel: String?
        get() = DownloadRowMapper.audioLabelFor(audio)

    /**
     * The episode number as the rest of the app renders it (whole numbers
     * without the trailing `.0`). A2.1 (UI-004): delegates to the ONE
     * `StringUtils.episodeNumber` renderer this KDoc previously only mirrored.
     */
    val episodeStr: String
        get() = StringUtils.episodeNumber(number)
}
@Immutable
data class AnimeDetailsUiState(
    /** Anime session UUID this entry is scanning (the route's ID-only argument). */
    val session: String = "",
    /** True while the scan for [session] is pending. */
    val loading: Boolean = true,
    /**
     * Post-audio slice — the OBSERVED scan progress, or `null` when there is
     * nothing truthful to show: before variant discovery has reported a
     * session, after the scan completed or failed, and on a cache hit (where
     * no page is fetched at all, so no progress is claimed).
     *
     * Rendered only in the loading branch, so it can never be mistaken for a
     * state of a finished screen.
     */
    val scanProgress: ScanProgress? = null,
    /** Resolved anime title; `null` until the first completed scan. */
    val title: String? = null,
    /** Real, deduplicated episodes (number, audio) in repository order. */
    val episodes: List<EpisodeInfo> = emptyList(),
    /** Non-null only when the scan genuinely failed. */
    val error: String? = null,

    /**
     * True while [NetworkStatus.OFFLINE] —
     * renders the offline banner and guards load/resolution/batch starts.
     * `false` for UNKNOWN/ONLINE (97.2: never a false offline claim).
     */
    val offline: Boolean = false,

    // --- 11.5E-3: episode selection → stream resolution ---

    /**
     * The episode the user selected; `null` is the idle state. Always one of the
     * real [episodes] — the screen can only hand over what the scan produced.
     */
    val selectedEpisode: EpisodeInfo? = null,
    /** True while [selectedEpisode]'s stream is being resolved. */
    val resolving: Boolean = false,
    /** The resolved stream for [selectedEpisode]; `null` until resolution succeeds. */
    val resolvedStream: StreamInfo? = null,
    /**
     * Non-null only when the resolution of [selectedEpisode] genuinely failed.
     */
    val resolutionError: String? = null,

    // --- 11.5E-4: resolved stream → the EXISTING download pipeline ---

    /**
     * True while the resolved [selectedEpisode] is being handed to
     * [EpisodeDownloadQueue] (parent session row + episode row + stream info).
     */
    val enqueuing: Boolean = false,
    /**
     * What the last hand-off of [selectedEpisode] to
     * [EpisodeDownloadQueue] REALLY did — `null` before any hand-off and after
     * every reset (reselection, a new resolution, a failure).
     *
     * Slice B (DECISIONS 146): this used to be a `Boolean` the ViewModel set to
     * `true` unconditionally, so a request the pipeline suppressed (`already
     * downloading`, `paused`, `already downloaded`) still painted "In the
     * download queue". The screen now renders the outcome itself and the
     * repository's verdict is the single source of truth.
     */
    val enqueueOutcome: EnqueueOutcome? = null,
    /** Non-null only when that hand-off genuinely failed. */
    val enqueueError: String? = null,

    /**
     * True while the ONE-time `POST_NOTIFICATIONS`
     * prompt must be launched — set by the ViewModel at the first successful
     * enqueue while `NotificationPermissionRepository.shouldRequest()` allows it,
     * cleared by [AnimeDetailsViewModel.onNotificationPermissionResult]. The
     * screen renders the ActivityResult launcher ONLY while this is true, and no
     * test ever sets it (the repository defaults to `null`), so instrumentation
     * never meets a system dialog.
     */
    val requestNotificationPermission: Boolean = false,

    // --- Phase 12C + Option C (DECISIONS 143): selection → batch resolution ---

    /**
     * True while the screen is in batch-selection mode. In this mode a row tap
     * toggles its episode NUMBER in [selectedNumbers] instead of resolving one
     * stream — one toggle selects/deselects that number's single context row
     * (`prompts.py:139-147`; the audio itself lives in [selectedAudio] /
     * [explicitAudioOverrides] and a row tap never changes it, DECISIONS 143.1).
     */
    val batchMode: Boolean = false,
    /** Episode NUMBERS selected for the batch (Python selects numbers, not variants). */
    val selectedNumbers: Set<Float> = emptySet(),

    // --- Option C (DECISIONS 143.1, SPEC §22 "Audio selection (per episode)") ---

    /**
     * The current screen-level audio context: an OPEN-set value the screen's
     * option set offers ([audioOptions]) — `jpn`, `eng`, `hin`, `spa`, … never a
     * hard-coded pair, established at load by the deterministic initial rule
     * ([initialAudioContext]) and changed only by the user through the Anime
     * Details selector. `""` only while no scan has produced episodes yet.
     * There is NO global Settings audio preference (SPEC §23), and no state
     * here ever labels a value "original" (SPEC §22).
     */
    val selectedAudio: String = "",
    /**
     * DECISIONS 143.1 — explicit per-episode audio overrides: `number → audio`
     * ONLY for episodes where the user explicitly chose an audio other than
     * the current [selectedAudio]. A number's effective audio is
     * `override ?: selectedAudio` ([effectiveAudio]) for single-episode
     * resolution and batch execution alike.
     *
     * Cleared by (a) a selector change (all), (b) choosing the context audio
     * for that episode (that one), (c) load/session replacement (all) — NOT by
     * entering/leaving batch mode or clearing the selection
     * (DECISIONS 143.5, superseding the 12C batch-exit audio clear).
     */
    val explicitAudioOverrides: Map<Float, String> = emptyMap(),
    /**
     * DECISIONS 144.6 / SPEC §22 — the screen's audio OPTION SET: the union of
     * `data-audio` strings the bounded play-page probe discovered for this
     * title, or — when the probe completed empty, has not completed, or is not
     * bound — the release-listing audio values. Listing audio is
     * display/identity metadata and the option-set FALLBACK only; it never
     * proves an audio unavailable (that is decided when an audio resolves).
     *
     * Open-set (`jpn`, `eng`, `kor`, …), never reduced to a SUB/DUB pair and
     * never labelled "original". This — not `availableAudios(episodes)` — is
     * what the screen-level selector and the per-episode override control offer.
     */
    val audioOptions: List<String> = emptyList(),
    /**
     * DECISIONS 144.5 — the persisted state of this title's audio probe. Used by
     * the screen and tests to tell a COMPLETED probe from a listing fallback
     * honestly; it is never rendered as a claim about per-episode availability.
     */
    val audioProbeState: AudioProbeState = AudioProbeState.NOT_PROBED,
    /**
     * Post-audio slice — true ONLY while a user-triggered re-probe
     * ([AnimeDetailsViewModel.retryAudioProbe]) is running.
     *
     * The INITIAL probe runs inside [AnimeDetailsViewModel.load] while
     * [loading] is still `true`, so it needs no separate flag: the screen
     * already shows the scan spinner for it. This flag exists for the retry,
     * which happens on an ALREADY-LOADED screen, where nothing else would say
     * that network work is in flight.
     *
     * It is cleared by the probe's own completion (success or failure) and
     * implicitly by any state replacement in [AnimeDetailsViewModel.load].
     */
    val audioProbing: Boolean = false,
    /** True while [startBatchDownload]'s resolve-and-queue loop is running. */
    val batchRunning: Boolean = false,
    /** Episodes queued into the download pipeline during the current batch. */
    val batchQueued: Int = 0,
    /**
     * Episodes the pipeline did NOT start a Worker for during the current batch
     * (Python `already exists`).
     *
     * Every verdict other than [EnqueueOutcome.Accepted] and
     * [EnqueueOutcome.OutputMissing] lands here, so it means "not newly queued",
     * not "already on disk": `AlreadyActive`, `AlreadyDownloaded` and
     * `AlreadyPaused` all describe an episode the pipeline already covers, while
     * [EnqueueOutcome.Refused] describes one it declined outright (cancelled,
     * failed or removed around the submission) and holds no Worker at all.
     * The user-facing summary is built from this counter and [batchQueued]
     * alone, so the wording never claims a queued episode for a refusal.
     */
    val batchSkipped: Int = 0,
    /** Episodes that exhausted every batch attempt (Python `✗ failed`). */
    val batchFailed: Int = 0,
    /** Total numbers in the current batch (denominator of the progress copy). */
    val batchTotal: Int = 0,
    /** True once a batch run ended — renders the summary even at 0 queued. */
    val batchFinished: Boolean = false,
    /**
     * Non-null only for a batch-level problem: a range expression that matched
     * nothing, or a batch that had to stop (e.g. staging capacity never freed).
     * Per-episode failures are counted in [batchFailed] and enumerated in
     * [batchFailures], not here.
     */
    val batchError: String? = null,
    /**
     * Slice C — the per-episode failures of the batch that just finished, in
     * episode order, each with its typed [BatchEpisodeFailure.category].
     *
     * Populated from the last COMPLETED retry pass, whose keys are exactly the
     * numbers [batchFailed] counts — a number that failed on an early pass but
     * succeeded on a later one is neither listed nor counted.
     *
     * It is `emptyList()` whenever no pass completed: a cancelled run, and a
     * capacity abort ([batchError]), whose numbers were never attempted at all.
     * (The aggregate [batchFailed] counter is left as-is on a cancel because it
     * is pre-existing behavior; the list simply makes no per-episode claim.)
     *
     * Dropped when a new batch starts, and does NOT survive the ViewModel:
     * resolution failures have no download row (see [BatchEpisodeFailure]), so
     * nothing claims recovery after process death.
     */
    val batchFailures: List<BatchEpisodeFailure> = emptyList(),

    // --- Phase 13 (DECISIONS 93.2): back-press confirmation while a batch runs ---

    /**
     * True while the back-navigation confirmation warning is open. The batch
     * keeps running while the warning is shown; only confirming leaves.
     */
    val leaveBatchWarning: Boolean = false,
    /**
     * Submitted count the warning states: numbers handed to the download
     * pipeline so far ([batchQueued] at warning time, refreshed while open).
     */
    val leaveBatchSubmitted: Int = 0,
    /**
     * Pending count the warning states: selected numbers NOT yet concluded by
     * the loop — exactly what leaving drops. Dropped numbers are never
     * reported as failed or skipped (DECISIONS 93.2 honesty).
     */
    val leaveBatchPending: Int = 0,

    // --- Lazy-loading slice (Prompt 1): API-driven pagination ---

    /**
     * The API paginator's OWN `total` for this listing — `null` when the
     * response carried no usable figure.
     *
     * Never conflated with 0 (which would mean the source really reports zero
     * episodes) and never replaced by a locally derived count: when it is
     * `null` the screen says the episode count is unknown instead of guessing,
     * and [AnimeInfo.total] (the derived count of what was actually loaded)
     * remains the separate figure it always was.
     */
    val apiTotal: Int? = null,
    /** The API's `per_page` window (30 by default); `null` when unknown. */
    val perPage: Int? = null,
    /** The API's `last_page` — the last page the source reports. */
    val apiLastPage: Int? = null,
    /**
     * The 1-based page currently displayed. Paging is OFF when [apiLastPage]
     * is `null` or `1`, in which case this stays `1` and the screen renders the
     * whole loaded listing exactly as it did before this slice.
     */
    val currentPage: Int = 1,
    /**
     * The rows belonging to [currentPage], or `null` when paging is off.
     *
     * `null` is the important case: it means "one undivided listing", so every
     * pre-existing construction of this state (tests, a legacy cache payload, a
     * single-page anime) keeps rendering [episodes] unchanged. A non-null value
     * is ALWAYS the rows of [currentPage] — never another page's rows left
     * behind by a switch, and never stale content on a failed page.
     */
    val pageRows: List<EpisodeInfo>? = null,
    /** True while [currentPage] (or a range's pages) is being fetched. */
    val pageLoading: Boolean = false,
    /**
     * Non-null only when the CURRENT page or range genuinely failed to load.
     * Scoped to that request: it never replaces the whole screen's [error] and
     * never marks the listing untrustworthy — the other loaded pages stay
     * usable.
     */
    val pageError: String? = null,
) {
    /**
     * Slice B (DECISIONS 146, review finding H-3) — derived, never stored.
     *
     * The hand-off "succeeded" only when the repository says it really started
     * a download: [EnqueueOutcome.Accepted]. `AlreadyActive`,
     * `AlreadyDownloaded`, `AlreadyPaused`, `OutputMissing` and `Refused` are
     * honest outcomes, not successes, so they keep this `false` while still
     * being rendered as their own messages.
     *
     * Deliberately a getter rather than a second stored field: two fields that
     * must agree can drift, and a missed reset would leave one episode
     * inheriting another's outcome after reselection — hiding the Download
     * button for an episode that was never downloaded. Making it derived means
     * [enqueueOutcome] alone decides, so `copy(...)` cannot desynchronise them.
     */
    val enqueued: Boolean
        get() = enqueueOutcome == EnqueueOutcome.Accepted
}

// ---------------------------------------------------------------------------
// Option C (DECISIONS 143, SPEC §22) — the audio derivations shared by the
// ViewModel, the Anime Details screen and their tests. Pure functions over
// immutable state: the SAME rule drives the rendered rows, the bulk-selection
// input and single/batch resolution, so the UI can never display one audio
// while execution requests another (AGENTS §4 — derivation lives behind the
// ViewModel; the screen only reads it).
// ---------------------------------------------------------------------------

/** The OPEN set of audio values the current listing exposes, in scan order. */
internal fun availableAudios(episodes: List<EpisodeInfo>): List<String> =
    episodes.map { it.audio }.distinct()

/**
 * The deterministic INITIAL audio context (SPEC §22, DECISIONS 143.4/144.6):
 * `jpn` when the OPTION SET offers it, otherwise the first value in the set's
 * order, otherwise none (`""` — an empty set offers no context).
 *
 * The set handed here is the discovered option set (the probe's union, or the
 * listing fallback) — exactly what [AnimeDetailsUiState.audioOptions] holds —
 * so the starting context is never drawn from a set the screen does not show.
 *
 * A STARTING value only: this rule runs once per load, never re-applies over
 * a user-chosen context (bulk actions use [AnimeDetailsUiState.selectedAudio]
 * as it stands), never claims any value is "original", and hard-codes no
 * language beyond the one mandated `jpn`-first preference order.
 */
internal fun initialAudioContext(options: List<String>): String = when {
    AudioSelector.AUDIO_JAPANESE in options -> AudioSelector.AUDIO_JAPANESE
    else -> options.firstOrNull() ?: ""
}

/** DECISIONS 143.1 — a number's effective audio: explicit override, else context. */
internal fun effectiveAudio(state: AnimeDetailsUiState, number: Float): String =
    state.explicitAudioOverrides[number] ?: state.selectedAudio

/**
 * Slice B (DECISIONS 146) — the honest message for ONE [EnqueueOutcome].
 *
 * Every value states something the pipeline actually did or actually refused to
 * do, which is the whole point: the previous implementation showed a single
 * "In the download queue" for all of them. No branch promises an in-app control
 * the app does not offer — [EnqueueOutcome.AlreadyPaused] points at the Downloads
 * screen, whose Resume control exists (SPEC §20), and no branch advertises a
 * per-episode deletion button this slice does not build (AGENTS §5).
 *
 * [EnqueueOutcome.AlreadyDownloaded] names the user's OWN file management as the
 * way to re-download, because that is factually the only way: the guard will not
 * queue a re-download while §29 still proves a real, non-empty output exists.
 * Stating that is not an in-app promise — the app neither performs nor offers the
 * deletion; it simply refuses to pretend bytes moved when they did not.
 *
 * `null` is not an outcome, so it is not handled here: the caller renders the
 * Download button and the error channel instead.
 */
internal fun enqueueNotice(outcome: EnqueueOutcome): String = when (outcome) {
    EnqueueOutcome.Accepted -> "In the download queue"
    EnqueueOutcome.AlreadyActive -> "Already in the download queue"
    // Redownload slice: the row is COMPLETED and the §29 rule proved a real,
    // non-empty output still exists, so NOTHING is queued and NO bytes move —
    // this is the deliberate idempotency guard (DECISIONS 146.2; Decision 74
    // Fix-1), and it mirrors Python, which skips an episode whose output
    // already exists (`downloader.py:60-62`).
    //
    // The old wording said only "Already downloaded", which reads as though a
    // NEW download had just finished: no file appeared and no timestamp
    // changed, so the report was honest about the state but silent about the
    // decision. The wording now states the no-op and names the one action that
    // really re-downloads it — removing the existing output, after which the
    // same §29 rule reports it missing and the legitimate re-queue runs
    // (`OutputMissing`, below).
    EnqueueOutcome.AlreadyDownloaded ->
        "Already downloaded — delete the existing file to download it again"
    EnqueueOutcome.OutputMissing -> "Previous download is missing — starting it again"
    EnqueueOutcome.AlreadyPaused -> "Paused — resume it from Downloads"
    // F1: this submission's hand-off was refused, so NOTHING is queued. The
    // wording names every row state `outcomeOf` can derive this from (failed,
    // cancelled, or deleted) without asserting which one it was, and offers
    // only an action the screen really has — tapping Download again.
    EnqueueOutcome.Refused -> "Not queued — the download was cancelled, failed or removed; try again"
}

/**
 * The rendered episode rows (SPEC §22, DECISIONS 144.6): ONE row per episode
 * NUMBER, carrying that number's EFFECTIVE audio (`override ?: context`).
 *
 * The number's own listing variant is used when the listing has one; otherwise
 * the row is that number's play page carrying the REQUESTED audio as intent
 * ([variantOrCarrier]). So what the screen shows is exactly what selection,
 * bulk execution and resolution will ask for — never one audio on screen and
 * another in the pipeline.
 *
 * What NEVER happens here: a number disappearing because release-listing
 * metadata lacks the current audio (it cannot prove per-episode availability —
 * DECISIONS 144), a variant of another audio becoming a second top-level row,
 * or any substitution (the requested audio is carried through verbatim and is
 * only ever dropped by an explicit resolver failure).
 *
 * No context (`selectedAudio` blank, i.e. before any scan) still renders an
 * empty list, exactly as before.
 */
internal fun visibleEpisodeRows(state: AnimeDetailsUiState): List<EpisodeInfo> =
    state.episodes.map { it.number }.distinct().mapNotNull { number ->
        val requested = effectiveAudio(state, number)
        if (requested.isBlank()) return@mapNotNull null
        variantOrCarrier(state.episodes, number, requested)
    }

/**
 * Lazy-loading slice — the rows the episode list actually RENDERS.
 *
 * Paging OFF ([AnimeDetailsUiState.pageRows] `null`) is a straight pass-through
 * to [visibleEpisodeRows], so a state built without pagination information — a
 * legacy cache payload, an unknown `per_page`, a single-page anime, or any test
 * that sets `episodes` directly — behaves exactly as it did before this slice.
 *
 * Paging ON shows ONLY [AnimeDetailsUiState.currentPage]'s rows, run through the
 * SAME production rules ([visibleEpisodeRows]) so the rendered rows still carry
 * the effective audio context of the full state. A page switch therefore can
 * never leave the previous page's rows on screen under the new page number.
 */
internal fun displayEpisodeRows(state: AnimeDetailsUiState): List<EpisodeInfo> {
    val page = state.pageRows ?: return visibleEpisodeRows(state)
    return visibleEpisodeRows(state.copy(episodes = page))
}

/**
 * Lazy-loading slice — the episode rows that drive everything derived from
 * "what exists on this screen right now": the rendered list, the batch-mode
 * toggle surface and the multi-variant override control.
 *
 * Identical to [visibleEpisodeRows] whenever paging is off, and identical to
 * [displayEpisodeRows] always — this is the one place the screen should ask.
 */
internal fun displayEpisodeEpisodes(state: AnimeDetailsUiState): List<EpisodeInfo> =
    state.pageRows ?: state.episodes

/**
 * Lazy-loading slice — the END of the initially ELIGIBLE ordinal window, i.e.
 * `min(total, 100)`, so a title over 100 episodes exposes episodes 1..100 and
 * "select all" stops there instead of silently meaning the whole anime.
 *
 * Three genuinely different answers, never collapsed:
 *
 * * `null` — there is no ordinal model to bound against (paging off: a legacy
 *   cache payload, a single-page title, or a response with no usable
 *   `per_page`), so callers keep the pre-slice behaviour of offering whatever
 *   the catalogue holds;
 * * `0` — the source really reports ZERO episodes;
 * * `1..n` with `n ≤ 100`.
 *
 * When `total` itself is unknown but the page count is not, the bound
 * `last_page × per_page` is used — an UPPER BOUND read straight from the
 * metadata, never a claim about the total — and it is still capped at 100.
 */
internal fun eligibleOrdinalEnd(state: AnimeDetailsUiState): Int? {
    val lastPage = state.apiLastPage ?: return null
    val perPage = state.perPage ?: return null
    if (lastPage <= 1 || perPage <= 0) return null
    state.apiTotal?.let { return EpisodePaging.eligibleEnd(it) }
    return EpisodePaging.eligibleEnd(lastPage * perPage)
}

/**
 * Lazy-loading slice — the APP page size this listing shows, or `null` when
 * there is no ordinal model at all.
 *
 * The app page is deliberately NOT the API's HTTP window ([AnimeDetailsUiState.perPage],
 * which stays the server's own 30-row figure): a series over 100 episodes
 * shows 100 per page, one of 100 or fewer shows 30, and an unknown total
 * falls back to the API's own window — never to zero and never to a guessed
 * figure. See [EpisodePaging.appPageSize].
 */
internal fun appPageSizeOf(state: AnimeDetailsUiState): Int? =
    EpisodePaging.appPageSize(state.apiTotal, state.perPage)

/**
 * How many APP pages the listing has, or `null` when it cannot be decided.
 *
 * With an unknown total this is the metadata BOUND (`per_page × last_page`)
 * laid out in app pages — an upper bound the source itself stated, never a
 * claim about the total, which the pager renders as "total unknown" rather
 * than as a number. See [EpisodePaging.ordinalCountBound].
 */
internal fun appPageCountOf(state: AnimeDetailsUiState): Int? =
    EpisodePaging.appPageCount(state.apiTotal, state.perPage, state.apiLastPage)

/**
 * Anime details ViewModel (11.5E-2, extended by 11.5E-3).
 *
 * Owns exactly THREE concerns of one screen, all through EXISTING collaborators:
 *
 * 1. run the EXISTING [EpisodeRepository.scan] for the session handed by the
 *    navigation argument (11.5E-2) — no second repository or cache, the scan
 *    cache stays behind [EpisodeRepository];
 * 2. resolve a selected episode through the EXISTING [StreamResolver] — which in
 *    production is the container-bound `StreamResolverImpl` (11.5E-3), reaching
 *    the WebView session and the Kwik parser. The UI never performs HTTP itself;
 * 3. hand a resolved stream to the EXISTING download pipeline (11.5E-4) through
 *    [EpisodeDownloadQueue] — implemented by `DownloadRepository`, so the episode
 *    reaches WorkManager, `DownloadWorker` and the HLS engine through the ONE
 *    path the Phase 10 orchestration and the Downloads dashboard already run on.
 *    The UI still never performs HTTP, HLS parsing or database writes itself.
 * 4. own the Anime Details audio-selection state (Option C, DECISIONS 143):
 *    the screen-level context, the selected numbers and the explicit
 *    per-episode overrides — all derived through the pure helpers above, with
 *    the execution mapping built once at batch start (never a second stored
 *    map) and strict no-substitution resolution on every path.
 *
 * Playback belongs to a later slice; a queued download is this entry's terminal
 * output.
 *
 * Host selection reuses [AnimeRepository.currentHost] (the host the successful
 * search selected), the same value HomeViewModel scans with — never a hard-coded
 * host or session.
 */
class AnimeDetailsViewModel(
    private val episodeRepository: EpisodeRepository,
    private val streamResolver: StreamResolver,
    private val downloadQueue: EpisodeDownloadQueue,
    /**
     * Persisted user settings, read at the moments a
     * setting is actually consumed — once per scan (cache TTL) and once per
     * resolution (quality). Defaults to [AppContext.defaults] (Python defaults) so
     * tests and pre-DI construction behave exactly like the reference.
     *
     * Read failures fall back to the defaults and are logged; a settings problem
     * must never block a scan or a resolution.
     */
    private val configProvider: suspend () -> AppContext = { AppContext.defaults() },
    /**
     * Application-scoped connectivity. Production
     * injects `AppContainer.networkMonitor`; the default [UnknownNetworkMonitor]
     * (UNKNOWN = online, 97.2) keeps every existing construction unchanged.
     */
    private val networkMonitor: NetworkMonitor = UnknownNetworkMonitor,
    /**
     * The ONE-time `POST_NOTIFICATIONS` prompt state,
     * issued at the first enqueue. Nullable-defaulted so every existing
     * direct-constructed test VM (BatchPersistence/BatchRecreation/DetailsHandoff)
     * compiles and behaves as before — with `null` no prompt is ever requested.
     * Production injects `AppContainer.notificationPermissionRepository`.
     */
    private val notificationPermissionRepository: NotificationPermissionRepository? = null,
    /**
     * DECISIONS 144 — the bounded play-page audio-option probe, run once per
     * user-driven load after the scan (never before it, never per episode,
     * never during a batch or a Watchlist check). Nullable-defaulted so every
     * existing direct-constructed test VM compiles and behaves as the
     * listing-fallback path; production injects
     * `AppContainer.audioOptionRepository`.
     */
    private val audioOptionRepository: AudioOptionRepository? = null,
    /**
     * Slice C — the `notify_failure` preference, read FRESH at the moment the
     * aggregated batch-failure notification is considered (the same read-fresh
     * contract DECISIONS 105.4 gives the Worker). Defaults to the shipped
     * [DownloadNotifyPrefs] defaults, so a composition that has not been wired
     * yet behaves exactly like production's default.
     */
    private val notifyFailurePrefs: suspend () -> DownloadNotifyPrefs = { DownloadNotifyPrefs() },
    /**
     * Slice C — posts the ONE aggregated batch-failure notification for a run
     * that ended with failures. Nullable-defaulted so every existing
     * direct-constructed test VM compiles and posts nothing; production
     * injects the real notifier through the ViewModel factory.
     *
     * Post-audio slice — it also carries `reasonCounts`, the label→count
     * breakdown of the run's TYPED failure categories (labels only, never a
     * message string), so the summary line can name the reasons without ever
     * quoting a raw error (AGENTS §10).
     *
     * @see notifyBatchFailure for the gates that decide when this is called.
     */
    private val batchFailureNotifier: ((
        failed: Int,
        total: Int,
        animeTitle: String,
        reasonCounts: Map<String, Int>,
    ) -> Unit)? = null,
) : ViewModel() {

    companion object {
        private const val TAG = "AnimeDetails"
        /** Honest fallback when a failure carries no message of its own. */
        const val ERROR_SCAN_FAILED = "Couldn't load episodes."

        /** Empty-branch copy shown when a completed scan returned no episodes. */
        const val NO_EPISODES = "No episodes found."

        /** Honest fallback when a resolution failure carries no message of its own. */
        const val ERROR_RESOLUTION_FAILED = "Couldn't resolve this episode's stream."

        /** Honest fallback when a queueing failure carries no message of its own. */
        const val ERROR_DOWNLOAD_FAILED = "Couldn't queue this download."

        /**
         * Fallback quality when settings cannot be read — also the Python default
         * (`quality = 1080`). The user's persisted quality is what production code
         * uses (Phase 12A wiring); this constant is only the honest last resort and
         * the value existing tests bind to.
         */
        const val RESOLUTION_QUALITY = 1080

        /** Python default scan cache TTL (minutes) — fallback when settings read fails. */
        const val DEFAULT_CACHE_TTL_MINUTES = 60

        /**
         * Phase 12C batch defaults — the Python defaults used when settings
         * cannot be read (`config_manager.py:18-25`), so a settings failure
         * never changes which episodes a batch picks or how often it retries.
         *
         * The former `DEFAULT_AUDIO_LANG` is GONE with the global audio
         * preference (SPEC §22): a batch's per-number audio is DERIVED at
         * execution time from [AnimeDetailsUiState.selectedAudio] and
         * [AnimeDetailsUiState.explicitAudioOverrides] (DECISIONS 143.6).
         */
        const val DEFAULT_AUTO_RETRY = 2
        const val DEFAULT_MAX_PARALLEL = 2
        const val DEFAULT_RESOLVE_AHEAD = 999

        /** Python batch-pass backoff — `sleep(2 * batch_attempt)` (`downloader.py:177-185`). */
        const val BATCH_RETRY_DELAY_BASE_MS = 2000L

        /** Range expression matched no episode (Python `✗ No episodes matched`). */
        const val NO_EPISODES_MATCHED = "No episodes matched that selection."

        /** A selected number has no variant left in the current listing. */
        const val NO_VARIANT_AVAILABLE = "No variant available for this episode."

        /**
         * Lazy-loading slice — a page (or a range's pages) could not be fetched
         * or read. PAGE-scoped: the listing itself stays loaded and every other
         * page keeps working, so this never replaces the whole screen's
         * [ERROR_SCAN_FAILED] and never claims the listing failed.
         */
        const val ERROR_PAGE_FAILED = "Couldn't load those episodes."

        /**
         * Honest refusal while OFFLINE — the
         * scan/resolution/batch is never issued and never faked; Retry runs the
         * real call once connectivity is back.
         */
        const val ERROR_OFFLINE = "You're offline — reconnect to continue."
    }

    private val _uiState = MutableStateFlow(AnimeDetailsUiState())
    val uiState: StateFlow<AnimeDetailsUiState> = _uiState.asStateFlow()

    /**
     * Latest connectivity, kept here because [load]
     * rebuilds the WHOLE UiState (four construction sites) — a `copy()`-only
     * flag would be silently dropped by the next full replacement. Only the
     * collector below writes it, and both run on `viewModelScope` (main), so
     * there is no interleaving between a write and a rebuild reading it.
     */
    private var offlineNow: Boolean = false

    init {
        // DECISIONS 97.3/97.4: the banner appears and clears through this
        // event-driven Flow (no polling, no timers); the guards below read
        // [offlineNow] from the same stream.
        viewModelScope.launch {
            networkMonitor.networkStatus.collect { status ->
                offlineNow = status == NetworkStatus.OFFLINE
                _uiState.update { it.copy(offline = offlineNow) }
            }
        }
    }

    /** The single in-flight scan — a re-load supersedes it instead of racing it. */
    private var scanJob: Job? = null

    /**
     * Post-audio slice — the single in-flight probe RETRY. Taps while it is
     * active are swallowed (see [retryAudioProbe]), which is what keeps a
     * repeated tap or a re-fired callback from stacking bounded passes into an
     * unbounded one.
     */
    private var probeJob: Job? = null

    /**
     * Post-audio slice — the [AnimeInfo] the CURRENT screen actually loaded.
     *
     * The retry needs the probe TARGETS and the session, which
     * [AnimeDetailsUiState] deliberately does not carry (it holds the rendered
     * option set, not the sampled URL list), so the scan result is retained
     * here for the lifetime of one load. Replaced by every successful scan,
     * dropped implicitly when a load replaces the screen state, and never read
     * before `loading == false && error == null`.
     */
    private var loadedAnime: AnimeInfo? = null

    /** The single in-flight resolution — a new selection supersedes it. */
    private var resolveJob: Job? = null

    /**
     * The single in-flight batch run (Python's one
     * `BatchOrchestrator.download()` call). Cancelled when the user stops it or
     * when a different session replaces the state ([load]).
     */
    private var batchJob: Job? = null

    /**
     * Lazy-loading slice — the single in-flight PAGE/RANGE fetch. A new one
     * supersedes it instead of racing it, exactly as [scanJob] does for the
     * whole screen, and [load] cancels it along with the others so a page
     * requested for one title can never land on another.
     */
    private var pageJob: Job? = null

    /**
     * The quality the CURRENTLY displayed [AnimeDetailsUiState.resolvedStream]
     * was really resolved with — written only when a resolution lands (same guard as
     * the state write), so the download submission can never record a quality the
     * stream does not have.
     */
    private var resolutionQuality: Int = RESOLUTION_QUALITY

    /**
     * Persisted quality, read once per resolution.
     * A settings failure logs and falls back to [RESOLUTION_QUALITY] (the Python
     * default) — configuration must never block a resolution.
     */
    private suspend fun configuredQuality(): Int = try {
        configProvider().quality
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e(TAG, "Settings read failed; using default quality: ${e.message}")
        RESOLUTION_QUALITY
    }

    /**
     * Persisted scan cache TTL (Python `cache_ttl`, minutes), read once
     * per scan. Same honest fallback as [configuredQuality] → [DEFAULT_CACHE_TTL_MINUTES].
     */
    private suspend fun configuredCacheTtlMinutes(): Int = try {
        configProvider().cacheTtl
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e(TAG, "Settings read failed; using default cache TTL: ${e.message}")
        DEFAULT_CACHE_TTL_MINUTES
    }

    /**
     * One settings read, honest fallback — the pattern of
     * [configuredQuality]/[configuredCacheTtlMinutes] shared by the batch keys
     * (`auto_retry`, `max_parallel`, `resolve_ahead`).
     *
     * A settings failure is logged and the PYTHON DEFAULT is used: configuration
     * must never block a batch, and the fallback values are the reference ones.
     *
     * (The former `configuredAudioLang()` was removed with the global audio
     * preference — a batch no longer reads any `audio_lang` setting; each
     * number's audio comes from the execution-time derivation of
     * [AnimeDetailsUiState.selectedAudio] + [AnimeDetailsUiState.explicitAudioOverrides].)
     */
    private suspend fun <T> configuredOrDefault(
        setting: String,
        fallback: T,
        read: (AppContext) -> T,
    ): T = try {
        read(configProvider())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e(TAG, "Settings read failed; using default $setting: ${e.message}")
        fallback
    }

    /** Persisted `auto_retry` — batch passes and resolution attempts (`+1`). */
    private suspend fun configuredAutoRetry(): Int =
        configuredOrDefault("auto_retry", DEFAULT_AUTO_RETRY) { it.autoRetry }

    /** Persisted `max_parallel` — part of Python's staging capacity. */
    private suspend fun configuredMaxParallel(): Int =
        configuredOrDefault("max_parallel", DEFAULT_MAX_PARALLEL) { it.maxParallel }

    /** Persisted `resolve_ahead` — how far resolution may run ahead. */
    private suspend fun configuredResolveAhead(): Int =
        configuredOrDefault("resolve_ahead", DEFAULT_RESOLVE_AHEAD) { it.resolveAhead }

    /**
     * Scan [session]. Seeds the loading state BEFORE the repository call so the
     * screen never renders a stale previous anime's episodes under a new session.
     *
     * Idempotent for the session that is ALREADY loading or already loaded: the
     * route's `LaunchedEffect(session)` re-runs on every configuration change
     * (rotation, dark-mode/font-scale/language change) while the nav-entry-scoped
     * ViewModel keeps its state, so re-seeding would throw away a rendered episode
     * list (and any selection/resolution on it) and repeat the repository call
     * (AGENTS §10 — avoid unnecessary requests). A FAILED scan is never guarded,
     * so a recreated entry retries the real call honestly and [retry] always re-runs it.
     */
    fun load(session: String) {
        val current = _uiState.value
        if (session.isNotBlank() &&
            current.session == session &&
            current.error == null &&
            (current.loading || current.title != null)
        ) {
            // Same entry re-seeded: an in-flight or completed scan already owns
            // this session — keep it (never reset to the loading branch).
            return
        }
        scanJob?.cancel()
        // A previous session's PAGE fetch belongs to the listing it was asked
        // for; letting it land would splice another title's rows into this one.
        pageJob?.cancel()
        // A different (or previously failed) session replaces the whole state below,
        // so any resolution still in flight belongs to state that no longer exists.
        resolveJob?.cancel()
        // likewise a batch run started for the previous session.
        batchJob?.cancel()
        // Post-audio slice: a probe RETRY belongs to the screen it was started
        // on — cancelling it here keeps an old session's pass from landing on
        // (or being reported by) the new one.
        probeJob?.cancel()
        // …and so does the retained scan: a retry must never probe the PREVIOUS
        // title's targets under a new session.
        loadedAnime = null
        _uiState.value = AnimeDetailsUiState(session = session, loading = true, offline = offlineNow)
        if (session.isBlank()) {
            _uiState.value = AnimeDetailsUiState(
                session = session,
                loading = false,
                error = ERROR_SCAN_FAILED,
                offline = offlineNow,
            )
            return
        }
        // DECISIONS 97.4 (SPEC §27): while OFFLINE a scan cannot succeed — refuse
        // honestly and immediately instead of driving a doomed WebView attempt.
        // Retry re-runs this real call once connectivity is back.
        if (offlineNow) {
            _uiState.value = AnimeDetailsUiState(
                session = session,
                loading = false,
                error = ERROR_OFFLINE,
                offline = true,
            )
            return
        }
        scanJob = viewModelScope.launch {
            try {
                // cache_ttl is consumed here — one settings read per scan.
                val cacheTtlMinutes = configuredCacheTtlMinutes()
                // Lazy-loading slice: OPEN the listing (the paginator header
                // plus page 1 of each variant session) instead of running the
                // full multi-page [EpisodeRepository.scan]. The screen must not
                // fetch every page merely because it opened — a 1000-episode
                // title costs a handful of requests here instead of dozens, and
                // later pages are read only when the user navigates to them or
                // requests a range that intersects them.
                val anime = episodeRepository.openListing(
                    AnimeRepository.currentHost,
                    session,
                    cacheTtlMinutes,
                ) { progress -> publishScanProgress(session, progress) }
                // A listing whose FIRST page could not be read is NOT an episode
                // inventory - the reference refuses to cache one and says so
                // (`extract/scanner.py:267-279,295-302`). Hand it to this
                // screen's EXISTING failure branch so a truncated list can never
                // be rendered as the complete anime, and keep `loadedAnime`
                // unset (exactly as an errored load does) so no later action
                // operates on a listing that is known to be missing pages.
                if (!anime.completed) {
                    _uiState.value = AnimeDetailsUiState(
                        session = session,
                        loading = false,
                        error = ERROR_SCAN_FAILED,
                        offline = offlineNow,
                    )
                    return@launch
                }
                // App-page 1 is the page the screen SHOWS, and it is BUILT
                // from the API windows its ordinals span: an app page of 100
                // over a server window of 30 needs four of them. [openListing]
                // deliberately read only the first (plus the bounded probe
                // page), so the rest are read here — still never the whole
                // series: a 250-episode title opens by reading 4 of its 9
                // pages, and a 60-episode title by reading 1 of its 2.
                val model = appPageModel(anime)
                val wanted = if (model == null) {
                    emptyList()
                } else {
                    EpisodePaging.apiPagesForAppPage(1, model.appPageSize, model.apiPageSize)
                        .filter { it in 1..(anime.apiLastPage ?: Int.MAX_VALUE) }
                }
                val (opened, appPageOneWindows) =
                    ensureWindows(anime, wanted, cacheTtlMinutes)
                // Post-audio slice: retained for a possible retry - the probe
                // targets live on the scan result, not in the UiState.
                loadedAnime = opened
                // Option C (DECISIONS 143.5c): ONE atomic replacement — the audio
                // state rebuilds with the new listing (context recomputed by the
                // deterministic initial rule; numbers and overrides can never
                // leak from the previous anime/session).
                //
                // DECISIONS 144 — bounded audio-option discovery runs AFTER the
                // scan completes and exactly once per user-driven load (the load
                // guard above stops repeat and concurrent loads from doubling
                // it; a superseding load cancels this job). A completed cached
                // result costs zero requests, so the second load of a fresh cache
                // entry reuses it. With no repository bound (direct-constructed
                // test VMs) the persisted result, if any, is read as-is and the
                // option set is the listing fallback.
                val probe = audioOptionRepository?.ensureProbed(opened)
                    ?: opened.audioProbe
                    ?: AudioProbeResult()
                val options = probe.audios.ifEmpty { availableAudios(opened.episodes) }
                _uiState.value = AnimeDetailsUiState(
                    session = session,
                    loading = false,
                    title = opened.title,
                    episodes = opened.episodes,
                    offline = offlineNow,
                    selectedAudio = initialAudioContext(options),
                    audioOptions = options,
                    audioProbeState = probe.state,
                    // Lazy-loading slice — the source's own paginator numbers
                    // and the ONE app page this open populates. `pageRows = null`
                    // (a single app page / unknown `per_page`) keeps the whole
                    // loaded list on screen, exactly as before this slice.
                    apiTotal = opened.apiTotal,
                    perPage = opened.perPage,
                    apiLastPage = opened.apiLastPage,
                    currentPage = 1,
                    pageRows = model?.let {
                        appPageRows(
                            windows = appPageOneWindows,
                            undivided = opened.episodes,
                            appPage = 1,
                            appPageSize = it.appPageSize,
                            apiPerPage = it.apiPageSize,
                        )
                    },
                    pageLoading = false,
                    pageError = null,
                )
            } catch (e: CancellationException) {
                // A superseded/replaced scan must die, never land in the error
                // branch and clobber the newer session's state (§14 pattern).
                throw e
            } catch (e: Exception) {
                _uiState.value = AnimeDetailsUiState(
                    session = session,
                    loading = false,
                    error = e.message?.takeIf { it.isNotBlank() } ?: ERROR_SCAN_FAILED,
                    offline = offlineNow,
                )
            }
        }
    }

    /**
     * Ensure [windows] are open and return the refreshed listing PLUS the rows
     * of every one of them, keyed by the 1-based window index.
     *
     * The rows are collected AS the windows become available — never re-read
     * from the page cache afterwards — because that cache is bounded
     * ([EpisodePaging.MAX_CACHED_PAGES]): a window that is open now can be
     * evicted while a later one is being read, and re-reading afterwards would
     * hand back an app page MISSING the windows it just paid for. So the
     * already-open windows are read first, and each missing window's rows are
     * read immediately after its own fetch, while it is the newest entry in
     * the cache. An app page is therefore either really whole or honestly
     * failed — never silently short.
     */
    private suspend fun ensureWindows(
        anime: AnimeInfo,
        windows: List<Int>,
        cacheTtlMinutes: Int,
    ): Pair<AnimeInfo, Map<Int, List<EpisodeInfo>>> {
        val collected = LinkedHashMap<Int, List<EpisodeInfo>>()
        windows.forEach { page ->
            episodeRepository.rowsForPage(anime, page)
                ?.takeIf { it.isNotEmpty() }
                ?.let { collected[page] = it }
        }
        var opened = anime
        for (page in windows) {
            if (collected.containsKey(page)) continue
            opened = episodeRepository.loadListingPage(
                AnimeRepository.currentHost,
                opened,
                page,
                cacheTtlMinutes,
            )
            collected[page] = episodeRepository.rowsForPage(opened, page).orEmpty()
        }
        return opened to collected
    }

    /** What this listing needs to be paged, or `null` when it must not be. */
    private data class AppPageModel(val appPageSize: Int, val apiPageSize: Int, val appPageCount: Int)

    /**
     * The paging model of a loaded listing: the app page size, the API window
     * it is built from and how many app pages there are — or `null` when the
     * listing spans a SINGLE app page (paging off, whole listing on screen).
     */
    private fun appPageModel(anime: AnimeInfo): AppPageModel? {
        val appPer = EpisodePaging.appPageSize(anime.apiTotal, anime.perPage) ?: return null
        val apiPer = anime.perPage ?: return null
        val count = EpisodePaging.appPageCount(anime.apiTotal, anime.perPage, anime.apiLastPage) ?: return null
        if (count <= 1) return null
        return AppPageModel(appPageSize = appPer, apiPageSize = apiPer, appPageCount = count)
    }

    /**
     *
     * Gated on the two conditions that make the number meaningful: it must
     * belong to the session this screen is rendering, and the screen must
     * still be loading. A progress tick that arrives after the scan finished
     * (or for a session a newer load replaced) is therefore dropped instead of
     * resurrecting a spinner or painting one anime's progress onto another.
     *
     * `_uiState.update` rather than a plain write, so a tick can never clobber
     * a concurrent state transition.
     */
    private fun publishScanProgress(session: String, progress: ScanProgress) {
        _uiState.update { state ->
            if (state.session == session && state.loading) {
                state.copy(scanProgress = progress)
            } else {
                state
            }
        }
    }

    /** Retry: the SAME real repository call for the SAME session (§35). */
    fun retry() {
        load(_uiState.value.session)
    }

    /**
     * Post-audio slice — the ONE targeted re-probe for an ALREADY-LOADED screen.
     *
     * [retry] cannot serve this purpose: it calls [load], whose idempotence
     * guard returns immediately for a session that is already loaded
     * (`error == null && title != null`), so the documented "a FAILED probe is
     * re-attempted on the next user-driven load" was unreachable from exactly
     * the screen that offers the Retry affordance. This is the scoped state
     * transition instead.
     *
     * Bounded by construction — every gate below exists to keep the probe the
     * DECISIONS 144 contract says it is:
     *
     * * **one pass at a time** — an in-flight [probeJob] swallows the tap, so
     *   repeated taps (or a navigation/recomposition that fires the callback
     *   again) can never stack concurrent passes;
     * * **one pass per tap** — nothing here retries automatically, loops, or
     *   re-arms itself on failure; the only way to spend more requests is to
     *   ask again;
     * * **same targets** — [AudioOptionRepository.ensureProbed] re-derives them
     *   from the SAME [AnimeInfo.audioProbeTargets] this load produced, so the
     *   `merged sessions + 1` bound, the 400 ms pacing, the shared page lock
     *   and the "no in-probe retry" rule are untouched;
     * * **completed results cost nothing** — `OK_VALUES`/`OK_EMPTY` are refused
     *   up front instead of being turned into a pointless network pass (and
     *   `ensureProbed` would return them as-is anyway);
     * * **nothing to probe costs nothing** — a title whose rows carry no
     *   `play_url` (state [AudioProbeState.NOT_PROBED]) is refused rather than
     *   re-answered;
     * * **no freshness change** — the result lands through
     *   [ScanCacheRepository.saveAudioProbe], which rewrites only the probe
     *   member of the existing cache row and copies `cachedAt`/`cacheTtlMinutes`
     *   verbatim;
     * * **no migration, no new table** — the probe still persists in the row it
     *   has always used.
     *
     * An unexpected exception is logged and leaves the PREVIOUS state standing
     * (it only clears [AnimeDetailsUiState.audioProbing]): a retry that could
     * not run must not be rendered as a probe result.
     */
    fun retryAudioProbe() {
        val current = _uiState.value
        val anime = loadedAnime ?: return
        val repository = audioOptionRepository ?: return
        if (probeJob?.isActive == true) return
        // Not a loaded screen (still scanning, or a scan error the OTHER retry
        // owns) → nothing to re-probe here.
        if (current.loading || current.error != null) return
        // DECISIONS 143 running guard: a batch's execution mapping is FROZEN
        // when it starts, and a probe that landed mid-run would rewrite
        // `audioOptions` (and possibly the screen context) underneath it. The
        // selector is inert for exactly this reason — the affordance is too.
        if (current.batchRunning) return
        if (current.audioProbeState.completed) return
        if (anime.audioProbeTargets.isEmpty()) return
        // DECISIONS 97.4: the banner is up, so a doomed page pass is refused
        // instead of issued — the affordance simply works once connectivity is
        // back, exactly like scan/resolution/batch.
        if (offlineNow) return

        probeJob = viewModelScope.launch {
            _uiState.update { it.copy(audioProbing = true) }
            try {
                val probe = repository.ensureProbed(anime)
                applyProbeResult(anime, probe)
            } catch (e: CancellationException) {
                // Superseded by a load that replaced this screen: die quietly —
                // the replacement state already cleared `audioProbing`.
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Audio probe retry failed: ${e.message}")
                _uiState.update { it.copy(audioProbing = false) }
            }
        }
    }

    /**
     * Post-audio slice — publish ONE probe result onto the ALREADY-LOADED
     * state.
     *
     * Mirrors the load-path rule exactly (`probe.audios` when the probe saw
     * pages, the release-listing values otherwise), so a retry can never show a
     * different option set than the same result would have produced on a fresh
     * load. Everything the user has already done — the selection, the batch
     * mode, the chosen numbers, the explicit per-episode overrides — is kept
     * verbatim by `copy`.
     *
     * The screen-level context is kept too when the new set still offers it,
     * and only falls back to the deterministic [initialAudioContext] rule when
     * it does not: a retry that narrows the set must not silently leave the
     * selector pointing at a value it no longer offers. Explicit per-episode
     * overrides are NEVER dropped here — they are the user's own recorded
     * choices, and availability of a requested audio is decided when it
     * RESOLVES (SPEC §22), not by the option set.
     */
    private fun applyProbeResult(anime: AnimeInfo, probe: AudioProbeResult) {
        val options = probe.audios.ifEmpty { availableAudios(anime.episodes) }
        // Keep the retained scan truthful: it is the object a NEXT retry would
        // hand to `ensureProbed`, so it must carry the result this one just
        // produced — otherwise a completed pass could still be re-issued.
        loadedAnime = anime.copy(audioProbe = probe)
        _uiState.update { state ->
            state.copy(
                audioProbeState = probe.state,
                audioOptions = options,
                selectedAudio = if (state.selectedAudio in options) {
                    state.selectedAudio
                } else {
                    initialAudioContext(options)
                },
                audioProbing = false,
            )
        }
    }

    // -----------------------------------------------------------------------
    // 11.5E-3 — episode selection → stream resolution
    // -----------------------------------------------------------------------

    /**
     * Select [episode]'s number and resolve the stream of its EFFECTIVE audio.
     *
     * Selecting is a real action (no dead row): it starts the resolution for
     * the effective variant of the number, cancelling any resolution still in
     * flight so only the newest selection can ever land.
     *
     * Effective audio (Option C, DECISIONS 143.1 / SPEC §22): the SAME rule
     * the rendered row and the batch loop use — `explicit override ?: current
     * audio context` — resolved against the REAL variant of the listing
     * ([effectiveVariantOf]), so the play page asked for is always the one
     * that audio was scanned under. A requested audio the listing does not
     * offer fails explicitly ([publishAudioUnavailable]) — never another
     * audio (SPEC §22 no-substitution).
     *
     * Re-tapping the row that is ALREADY the active selection does not repeat
     * the whole Cloudflare/Kwik round-trip (AGENTS §10): while it is pending
     * the running resolution keeps going, and once it resolved its result
     * stands. A FAILED selection — or a different effective variant — always
     * starts a resolution, so tapping is also the natural retry affordance.
     *
     * The resolution runs at the user's persisted quality (Phase 12A:
     * `quality` setting, read once per resolution; Python default 1080 when
     * settings cannot be read).
     */
    fun selectEpisode(episode: EpisodeInfo) {
        val current = _uiState.value
        val target = effectiveVariantOf(current, episode.number)
        if (target == null) {
            publishAudioUnavailable(current, episode)
            return
        }
        if (current.selectedEpisode == target &&
            (current.resolving || current.resolvedStream != null)
        ) {
            // Already the active selection and nothing failed: keep it.
            return
        }
        startResolution(target)
    }

    /**
     * Retry: the SAME effective-audio resolution for the SAME selected number
     * (§35). Bound to the resolution error branch only; a no-op when nothing
     * is selected. If the requested audio is STILL not offered by the
     * listing, the honest failure is restated — a retry never falls back to a
     * different audio.
     */
    fun retryResolution() {
        val current = _uiState.value
        val selected = current.selectedEpisode ?: return
        val target = effectiveVariantOf(current, selected.number)
        if (target == null) {
            publishAudioUnavailable(current, selected)
            return
        }
        startResolution(target)
    }

    /**
     * The REAL variant a number's effective audio names — the one object both
     * resolution paths (select and retry) start from, so they can never
     * disagree with the rendered row about which play page they ask for.
     *
     * DECISIONS 144.4 — availability is decided at RESOLVE, never at
     * selection: when the listing has no row for the effective (requested)
     * audio but the number EXISTS, the number's own row carries the REQUESTED
     * audio as intent onto its play page ([variantOrCarrier]), and the resolver
     * either finds that exact audio there or fails with a message naming it
     * (SPEC §22 no-substitution). `null` means the NUMBER itself does not
     * exist in this listing — the unchanged missing-number path.
     */
    private fun effectiveVariantOf(state: AnimeDetailsUiState, number: Float): EpisodeInfo? =
        variantOrCarrier(state.episodes, number, effectiveAudio(state, number))

    /**
     * Publish the explicit failure for a number that does not exist in this
     * listing at all (SPEC §22): an error naming the REQUESTED audio, with no
     * resolver call and no other audio ever substituted. A number that EXISTS
     * never reaches here for an audio the listing lacks — it resolves strictly
     * instead (DECISIONS 144.4).
     */
    private fun publishAudioUnavailable(current: AnimeDetailsUiState, shown: EpisodeInfo) {
        _uiState.value = current.copy(
            selectedEpisode = shown,
            resolving = false,
            resolvedStream = null,
            resolutionError = AudioSelector.unavailableMessage(
                effectiveAudio(current, shown.number),
            ),
            enqueuing = false,
            enqueueOutcome = null,
            enqueueError = null,
        )
    }

    /**
     * Cancel the previous resolution, publish the resolving state, then run the
     * single in-flight [streamResolver] call for [episode].
     *
     * Stale results cannot win twice over: the superseded job is cancelled
     * (a cancelled coroutine rethrows [CancellationException] at its next
     * suspension point, so it never reaches the write below) AND every write is
     * guarded by the selection that started it — a result can only land while its
     * own episode is still the selected one.
     */
    private fun startResolution(episode: EpisodeInfo) {
        resolveJob?.cancel()
        // DECISIONS 97.4 (SPEC §27): while OFFLINE the WebView resolution cannot
        // succeed — select the episode honestly with an error instead of running
        // a doomed attempt. Retry re-runs this real resolution when back online.
        if (offlineNow) {
            _uiState.value = _uiState.value.copy(
                selectedEpisode = episode,
                resolving = false,
                resolvedStream = null,
                resolutionError = ERROR_OFFLINE,
                enqueuing = false,
                enqueueOutcome = null,
                enqueueError = null,
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            selectedEpisode = episode,
            resolving = true,
            resolvedStream = null,
            resolutionError = null,
            // Queueing state belongs to the previous selection: a new (or renewed)
            // resolution starts it over, so the panel can never claim an episode is
            // queued before its own stream exists. `enqueueOutcome` is cleared with
            // it — otherwise the NEXT selection inherits this one's verdict and its
            // Download button disappears (review finding H-3, bug (b)).
            enqueuing = false,
            enqueueOutcome = null,
            enqueueError = null,
        )
        resolveJob = viewModelScope.launch {
            try {
                // the persisted quality is consumed here (one read per
                // resolution), replacing the Phase 13 placeholder comment.
                val quality = configuredQuality()
                val stream = streamResolver.resolve(
                    playUrl = episode.playUrl,
                    quality = quality,
                    audio = episode.audio,
                )
                if (_uiState.value.selectedEpisode == episode) {
                    // Same guard as the state write: the recorded quality is the one
                    // THIS resolved stream was produced with.
                    resolutionQuality = quality
                    _uiState.value = _uiState.value.copy(
                        resolving = false,
                        resolvedStream = stream,
                        resolutionError = null,
                    )
                }
            } catch (e: CancellationException) {
                // A superseded selection must die, never land in the newer
                // selection's state (§14 pattern).
                throw e
            } catch (e: Exception) {
                if (_uiState.value.selectedEpisode == episode) {
                    _uiState.value = _uiState.value.copy(
                        resolving = false,
                        resolvedStream = null,
                        resolutionError = e.message?.takeIf { it.isNotBlank() }
                            ?: ERROR_RESOLUTION_FAILED,
                    )
                }
            }
        }
    }

    /**
     * Hand the RESOLVED stream of the selected episode to the EXISTING download
     * pipeline — one call, the single path into WorkManager and the HLS engine.
     *
     * The ViewModel performs NO persistence itself: it packages the real scan
     * data (anime session, title, host, quality) with the episode and the
     * [StreamInfo] the resolver really produced and gives them to
     * [EpisodeDownloadQueue] (AGENTS §4). The production implementation is
     * `DownloadRepository`, which orders the Phase 10 contract itself: parent
     * session row → episode row → persisted stream info → unique worker work.
     *
     * The resolution already happened in the WebView session, so nothing here
     * resolves, fetches or parses anything — this is state hand-off only.
     *
     * * Impossible while the stream is not resolved ([resolvedStream] is null),
     *   which is exactly the condition the screen binds the Download control to.
     * * Not re-entrant: a second tap while [AnimeDetailsUiState.enqueuing] is
     *   true returns immediately, and [AnimeDetailsUiState.enqueued] blocks it
     *   after success (a repeated hand-off would be suppressed by the pipeline
     *   anyway).
     * * Re-tappable after a failure: [AnimeDetailsUiState.enqueueError] does not
     *   lock the control, so the same real call is retried, not a different one.
     * * Every UI write is guarded by the selection that started it, the same way
     *   a resolution result is: a superseded hand-off still completes in the
     *   database (its data is real) but never paints its flags onto a different
     *   episode's state.
     */
    fun downloadResolvedEpisode() {
        val current = _uiState.value
        val episode = current.selectedEpisode ?: return
        val stream = current.resolvedStream ?: return
        val title = current.title ?: return
        if (current.enqueuing || current.enqueued) return

        val submission = ResolvedEpisodeSubmission(
            animeSession = current.session,
            animeTitle = title,
            host = AnimeRepository.currentHost,
            // the quality the resolved stream REALLY has (persisted
            // setting captured at resolution time), so the session row never
            // records a quality the download does not deliver.
            quality = resolutionQuality,
            episode = episode,
            stream = stream,
        )

        // Published BEFORE the coroutine starts (exactly like startResolution):
        // the re-entrancy guard then holds even on a dispatcher that has not run
        // the hand-off yet, so a double-tap cannot submit twice. The previous
        // outcome is cleared with it — a NEW attempt must never show the OLD
        // verdict (review finding H-3, bug (b)).
        _uiState.value = _uiState.value.copy(
            enqueuing = true,
            enqueueOutcome = null,
            enqueueError = null,
        )

        viewModelScope.launch {
            try {
                // Slice B: the repository decides, this caller only reports it.
                // Before this the call returned Unit and `enqueued = true` was
                // written unconditionally, so a suppressed request (already
                // downloading / paused / already downloaded) still painted
                // "In the download queue".
                val outcome = downloadQueue.queueResolvedEpisode(submission)
                if (_uiState.value.selectedEpisode == episode) {
                    _uiState.value = _uiState.value.copy(
                        enqueuing = false,
                        enqueueOutcome = outcome,
                        enqueueError = null,
                    )
                }
                // the ONE-time `POST_NOTIFICATIONS`
                // prompt still fires on every non-failing hand-off, exactly as
                // it did before this slice — the permission question is whether
                // this device will deliver download notifications at all, not
                // whether THIS call started the work. A row that is already
                // active will still notify, so suppressing the prompt there
                // would be a behaviour change this slice does not ask for.
                maybeRequestNotificationPermission()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.selectedEpisode == episode) {
                    _uiState.value = _uiState.value.copy(
                        enqueuing = false,
                        enqueueOutcome = null,
                        enqueueError = e.message?.takeIf { it.isNotBlank() }
                            ?: ERROR_DOWNLOAD_FAILED,
                    )
                }
            }
        }
    }

    /**
     * The id the Stream action navigates with.
     *
     * Returns `null` (and the caller performs no navigation) unless this entry
     * really has a selected episode WITH a resolved stream and a non-blank
     * session — the same conditions the Stream control is rendered under, so the
     * action can never navigate for a state the screen does not show.
     *
     * The id is derived through the shared [EpisodeId.build], never by
     * re-implementing the string pattern in the UI (§10 of the Phase 17 brief).
     * NOTHING else is handed to navigation: no `playUrl`, no `StreamInfo`, no
     * headers — the player re-resolves just-in-time.
     */
    fun streamEpisodeId(): String? {
        val current = _uiState.value
        val episode = current.selectedEpisode ?: return null
        if (current.resolvedStream == null) return null
        if (current.session.isBlank()) return null
        return EpisodeId.build(current.session, episode)
    }

    /**
     * Set the moment the user really answered the
     * one-time prompt, closing the in-flight window — a gate that already read
     * the fresh latch, then suspended while the answer landed, must not re-arm
     * the prompt afterwards. Main-confined (viewModelScope + the screen's
     * result callback both run on the main dispatcher).
     */
    private var notificationPromptAnswered = false

    /**
     * The ONE-time `POST_NOTIFICATIONS` prompt gate,
     * evaluated after a REAL enqueue succeeded (single path and batch path both
     * call it).
     *
     * [NotificationPermissionRepository.shouldRequest] is the asked-once latch —
     * false after the very first request on any screen and false on API ≤ 32 —
     * `isGranted()` skips a pointless launch when the app can already post, and
     * [notificationPromptAnswered] refuses every gate after the first answer.
     * With no repository injected (every directly-constructed test VM) this is a
     * no-op, so no instrumentation run can ever meet a system dialog.
     */
    private fun maybeRequestNotificationPermission() {
        val repository = notificationPermissionRepository ?: return
        viewModelScope.launch {
            // Fast path: already answered → never even suspend (95.A).
            if (notificationPromptAnswered) return@launch
            val canAsk = repository.shouldRequest() && !repository.isGranted()
            // DECISIONS 95.A: the guard is re-read AFTER both suspensions —
            // an answer that landed while this gate was suspended must not
            // re-arm the prompt. The re-read and the set are in one
            // main-confined step (no suspension between them), so no later
            // answer can be overtaken by a late set; the answer path's own
            // clear runs after its synchronous guard flip and therefore after
            // any set that preceded it.
            if (canAsk && !notificationPromptAnswered) {
                _uiState.update { it.copy(requestNotificationPermission = true) }
            }
        }
    }

    /**
     * The ActivityResult launcher reported the user's
     * real answer.
     *
     * Ordering matters: the session guard flips FIRST (synchronously), so an
     * in-flight gate can never re-arm the prompt after this point; then the
     * asked-once latch is persisted; only then does the single-shot prompt flag
     * clear — at which moment every later gate reads `shouldRequest() == false`
     * anyway. With no repository injected (test VMs) only the guard and the
     * flag change. §20: a denial is never re-asked automatically — the
     * Settings permission row (DECISIONS 96) is the manual path from there.
     */
    fun onNotificationPermissionResult(granted: Boolean) {
        notificationPromptAnswered = true
        val repository = notificationPermissionRepository
        if (repository == null) {
            _uiState.update { it.copy(requestNotificationPermission = false) }
            return
        }
        viewModelScope.launch {
            repository.recordRequestOutcome(granted)
            _uiState.update { it.copy(requestNotificationPermission = false) }
        }
    }

    // -----------------------------------------------------------------------
    // Option C (DECISIONS 143, SPEC §22) — screen-level audio context + the
    // explicit per-episode override. Both are single-writer state transitions;
    // the execution mapping is NEVER stored — it is derived where it runs.
    // -----------------------------------------------------------------------

    /**
     * Change the screen-level audio context — ONE coherent transition
     * (SPEC §22, DECISIONS 143.2 / 144.6), written in a single state assignment
     * so no frame can observe the old context with the new rows:
     *
     * 1. adopt [audio] (it must be a value the option set exposes — the set is
     *    open, but a context is never invented);
     * 2. clear EVERY explicit per-episode override;
     * 3. keep ALL selected numbers — no deselection and no notice: whether the
     *    new context's audio really serves a given number is decided when that
     *    number is resolved (DECISIONS 144.6), never inferred up front from
     *    release-listing audio, which cannot prove per-episode availability.
     *
     * Never substitutes an audio, and never runs while a batch is consuming
     * the execution mapping (running-state guard).
     */
    fun setSelectedAudio(audio: String) {
        val current = _uiState.value
        if (current.batchRunning) return
        if (audio == current.selectedAudio) return
        if (audio !in current.audioOptions) return
        _uiState.value = current.copy(
            selectedAudio = audio,
            selectedNumbers = current.selectedNumbers,
            explicitAudioOverrides = emptyMap(),
            batchError = null,
        )
    }

    /**
     * Record (or reset) ONE explicit per-episode audio override — the row's
     * "change audio" control (DECISIONS 143.1).
     *
     * Choosing the CURRENT context audio for the number is an explicit reset
     * of its override (143.5(b)); any other value becomes
     * `explicitAudioOverrides[number] = audio`. The control only offers the
     * title-level option set (DECISIONS 144.6), so the choice is recorded as
     * handed instead of re-validated here — execution stays the strict gate
     * either way: a requested audio the play page does not carry fails
     * explicitly, never another audio. Not allowed while a batch runs.
     */
    fun setEpisodeAudio(number: Float, audio: String) {
        val current = _uiState.value
        if (current.batchRunning) return
        if (audio.isBlank()) return
        val overrides = current.explicitAudioOverrides.toMutableMap()
        if (audio == current.selectedAudio) {
            overrides.remove(number)
        } else {
            overrides[number] = audio
        }
        _uiState.value = current.copy(
            explicitAudioOverrides = overrides,
            batchError = null,
        )
    }

    // -----------------------------------------------------------------------
    // batch selection → batch resolution (Python BatchOrchestrator)
    // -----------------------------------------------------------------------

    /**
     * Enter/leave batch-selection mode.
     *
     * Leaving still discards the SELECTION so a later batch never starts from
     * an invisible set (Phase 12C, unchanged). EXPLICIT AUDIO OVERRIDES ARE
     * KEPT across both transitions (DECISIONS 143.5 supersedes the 12C
     * batch-exit audio clear): they remain visible row state and keep
     * affecting single-episode resolution; they are cleared only by a
     * selector change, by choosing the context audio for that episode, or by
     * a load/session replacement.
     */
    fun setBatchMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            batchMode = enabled,
            selectedNumbers = if (enabled) _uiState.value.selectedNumbers else emptySet(),
            batchError = null,
        )
    }

    /**
     * Toggle [episode]'s NUMBER in the batch selection — and nothing else.
     *
     * Selection is by NUMBER (`prompts.py:139-147`); under the Option C model
     * (DECISIONS 143.1, superseding Decision 142.4's row-tap audio switch) a
     * tap records no audio at all: the screen shows ONE context row per
     * number, and audio lives in [AnimeDetailsUiState.selectedAudio] /
     * [AnimeDetailsUiState.explicitAudioOverrides], changed only through
     * [setSelectedAudio] and [setEpisodeAudio].
     */
    fun toggleBatchEpisode(episode: EpisodeInfo) {
        val current = _uiState.value
        if (current.batchRunning) return
        val numbers = current.selectedNumbers.toMutableSet()
        if (!numbers.remove(episode.number)) {
            numbers += episode.number
        }
        _uiState.value = current.copy(
            selectedNumbers = numbers,
            batchError = null,
        )
    }

    /**
     * Select every episode NUMBER in the catalogue (Python `--all`) — each
     * selected number resolves through `override ?: context` at execution time
     * and bulk actions create no overrides (DECISIONS 143.3 / 144.6: no
     * listing-only gate, so a number whose release row lacks the context audio
     * is still selectable).
     *
     * Lazy-loading slice: with an ELIGIBLE window known (paging on), "all" means
     * `1..min(total, 100)` and the pages that window spans are fetched first —
     * so over a 1000-episode title "select all" selects the eligible 100, never
     * the whole anime, and never silently means something else either. With no
     * ordinal model (paging off — a single-page title, a legacy cache payload, a
     * response without `per_page`) the catalogue in hand IS the catalogue and
     * this behaves exactly as it did before this slice.
     */
    fun selectAllEpisodes() {
        val current = _uiState.value
        if (current.batchRunning || current.pageLoading) return
        val eligible = eligibleOrdinalEnd(current)
        if (eligible == null) {
            applyBulkSelection(
                current,
                EpisodeRangeParser.selectEpisodes(visibleEpisodeRows(current), "all")
                    .map { it.number },
                batchError = null,
            )
            return
        }
        if (eligible <= 0) {
            // Distinguished from "unknown": the source really reports zero
            // episodes, so there is nothing to select.
            applyBulkSelection(current, emptyList(), batchError = NO_EPISODES_MATCHED)
            return
        }
        selectOrdinalWindow(current, 1, eligible)
    }

    /**
     * Drop the batch SELECTION. Explicit audio overrides are valid row state,
     * not selection state, so they survive untouched (DECISIONS 143.5 —
     * `clearBatchSelection()` must never erase them).
     */
    fun clearBatchSelection() {
        if (_uiState.value.batchRunning) return
        _uiState.value = _uiState.value.copy(
            selectedNumbers = emptySet(),
            batchError = null,
        )
    }

    /**
     * Select by range expression (Python `--range`, `prompts.py:24-39`):
     * `"1-12"`, `"13-"`, `"1,4,7"` … The real parser decides what matches —
     * against every episode number the catalogue lists (DECISIONS 144.6: no
     * listing-only gate), so an expression that matches nothing at all reports
     * an honest message instead of silently starting an empty batch (Python
     * `✗ No episodes matched`).
     */
    fun selectEpisodeRange(raw: String) {
        val current = _uiState.value
        if (current.batchRunning) return
        val matches = EpisodeRangeParser.selectEpisodes(
            visibleEpisodeRows(current),
            "range",
            raw,
        )
        applyBulkSelection(
            current,
            matches.map { it.number },
            batchError = if (matches.isEmpty()) NO_EPISODES_MATCHED else null,
        )
    }

    /**
     * Select the newest `n` episode numbers (SPEC §22, Python `--latest N` →
     * `noninteractive_episodes(mode="latest")`).
     *
     * The SAME production parser decides which numbers are newest — over every
     * episode number the catalogue lists (DECISIONS 144.6: no listing-only
     * gate) — and the numbers land in the SAME
     * [AnimeDetailsUiState.selectedNumbers] set All and Range use, so the
     * batch pipeline downstream is untouched. `n` greater than the catalogue
     * selects every available episode (the parser's own clamp), and selection
     * stays keyed by NUMBER, so a newest episode with several audio variants
     * collapses into that one number.
     *
     * `n < 1` is rejected HERE instead of being handed to the parser, whose
     * `coerceIn(1, size)` clamp would silently turn 0 or a negative count into
     * episode 1 — an invented selection. An empty context/catalogue reports
     * the same honest message an empty range does rather than claiming a match.
     */
    fun selectLatestEpisodes(n: Int) {
        val current = _uiState.value
        if (current.batchRunning) return
        if (n < 1) return
        val visible = visibleEpisodeRows(current)
        if (visible.isEmpty()) {
            applyBulkSelection(
                current,
                emptyList(),
                batchError = NO_EPISODES_MATCHED,
            )
            return
        }
        val matches = EpisodeRangeParser.selectEpisodes(visible, "latest", latestN = n)
        applyBulkSelection(
            current,
            matches.map { it.number },
            batchError = if (matches.isEmpty()) NO_EPISODES_MATCHED else null,
        )
    }

    /**
     * The ONE bulk-selection writer (SPEC §22, DECISIONS 143.3): replaces
     * [AnimeDetailsUiState.selectedNumbers] with [numbers] and touches
     * NOTHING else — the current audio context stays what it is, and no
     * explicit per-episode override is created, removed or rewritten by a
     * bulk action (every matched number simply resolves through
     * `override ?: context` when the batch runs).
     */
    private fun applyBulkSelection(
        current: AnimeDetailsUiState,
        numbers: List<Float>,
        batchError: String?,
    ) {
        _uiState.value = current.copy(
            selectedNumbers = numbers.toSet(),
            batchError = batchError,
        )
    }

    // ------------------------------------------------------------------
    // Lazy-loading slice (Prompt 1): paging + the bounded range selector
    // ------------------------------------------------------------------

    /** Paging is ON: the listing spans MORE than one APP page of a known size. */
    private fun pagingOn(state: AnimeDetailsUiState): Boolean =
        (appPageCountOf(state) ?: 0) > 1

    /**
     * Fetch [pages] of the current listing and hand the REFRESHED listing to
     * [onDone], or the failure message to [onFailed] (with the page that really
     * failed, or `-1` when it could not be pinned down).
     *
     * Pages already open cost zero requests — the repository answers them from
     * its own page cache — so a range whose pages are in memory needs no
     * network at all. A page that cannot be read is NEVER cached, NEVER rendered
     * under another page's number and NEVER reported as complete. WHOSE error
     * slot the message belongs in is the CALLER's decision: a page navigation
     * fails into [AnimeDetailsUiState.pageError] (scoped to that window), while
     * a range request fails into [AnimeDetailsUiState.batchError] (scoped to
     * that request) — so a failed range never blanks a perfectly good page list.
     *
     * The second argument is the rows of each requested window, ASSEMBLED WHILE
     * THEY ARE READ ([ensureWindows]): the page cache is bounded, so re-reading
     * it afterwards could hand back an app page with a window that was evicted
     * while its neighbours were being fetched. The caller therefore always
     * receives the rows that were really there, never a silently short page.
     */
    private fun fetchPages(
        pages: Collection<Int>,
        onDone: (AnimeInfo, Map<Int, List<EpisodeInfo>>) -> Unit,
        onFailed: (message: String, page: Int) -> Unit,
    ) {
        val anime = loadedAnime ?: return
        val wanted = pages.distinct().sorted().filter { it >= 1 }
        if (wanted.isEmpty()) {
            _uiState.update { it.copy(pageLoading = false) }
            onDone(anime, emptyMap())
            return
        }
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            try {
                val ttl = configuredCacheTtlMinutes()
                val (refreshed, windows) = ensureWindows(anime, wanted, ttl)
                loadedAnime = refreshed
                _uiState.update { it.copy(episodes = refreshed.episodes, pageLoading = false) }
                onDone(refreshed, windows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EpisodeRepository.PageLoadException) {
                _uiState.update { it.copy(pageLoading = false) }
                onFailed(ERROR_PAGE_FAILED, e.page)
            } catch (e: Exception) {
                _uiState.update { it.copy(pageLoading = false) }
                onFailed(
                    e.message?.takeIf { m -> m.isNotBlank() } ?: ERROR_PAGE_FAILED,
                    -1,
                )
            }
        }
    }

    /**
     * Move the visible window to APP page [page] (1-based, inside
     * `1..appPageCount`).
     *
     * The window is committed BEFORE the fetch, so the number on screen always
     * names the rows being shown: a failed page leaves an EMPTY window plus the
     * page error, never the previous page's rows under the new page number, and
     * the selected page SURVIVES the failure (the retry re-requests the same
     * app page). Only the API windows this app page is built from are
     * requested, and only from each variant session — which is what keeps it
     * correct across SUB and DUB — so an app page of 100 over a 30-row server
     * window costs its four windows, with the boundary page shared with the
     * neighbouring app page and therefore free when it is already open.
     */
    fun goToPage(page: Int) {
        val current = _uiState.value
        if (current.batchRunning || current.pageLoading) return
        val appPer = appPageSizeOf(current) ?: return
        val lastPage = appPageCountOf(current) ?: return
        val apiPer = current.perPage ?: return
        if (page < 1 || page > lastPage) return
        if (offlineNow) {
            _uiState.value = current.copy(pageError = ERROR_OFFLINE)
            return
        }
        val wanted = EpisodePaging.apiPagesForAppPage(page, appPer, apiPer)
            .filter { it in 1..(current.apiLastPage ?: Int.MAX_VALUE) }
        _uiState.value = current.copy(
            currentPage = page,
            pageRows = emptyList(),
            pageLoading = true,
            pageError = null,
        )
        fetchPages(
            pages = wanted,
            onDone = { anime, windows ->
                _uiState.update { state ->
                    state.copy(
                        pageRows = appPageRows(
                            windows = windows,
                            undivided = anime.episodes,
                            appPage = page,
                            appPageSize = appPer,
                            apiPerPage = apiPer,
                        ),
                    )
                }
            },
            // The APP page was already committed above, so it is kept: the
            // failed index is an API window, which must never be shown as a
            // page number the user could have asked for.
            onFailed = { message, _ ->
                _uiState.update { state ->
                    state.copy(
                        pageRows = emptyList(),
                        pageError = message,
                    )
                }
            },
        )
    }

    /**
     * The SEPARATE bounded range selector (prompt requirement C) — request
     * ordinals `[start, end]` of the API's stable `episode_asc` ordering.
     *
     * An ordinal is a POSITION in that ordering, not an episode number: only a
     * position maps deterministically to pages (`ceil(p / per_page)`), which is
     * why `EpisodePaging` refuses to guess from `episode number / 30` when
     * specials such as 1.5 shift the numbering. The rows of the pages the range
     * covers ARE the episodes in it, so the mapping from ordinal to a stable
     * episode id is simply "read those rows".
     *
     * Rejections (reversed, out-of-bounds, over 100 episodes, unknown total)
     * land in [AnimeDetailsUiState.batchError] WITHOUT touching the selection
     * or fetching anything.
     */
    fun requestEpisodeRange(start: Int, end: Int) {
        val current = _uiState.value
        if (current.batchRunning || current.pageLoading) return
        val rejection = EpisodePaging.validateRange(
            start,
            end,
            current.apiTotal,
            EpisodePaging.ordinalCountBound(current.apiTotal, current.perPage, current.apiLastPage),
        )
        if (rejection != null) {
            _uiState.value = current.copy(batchError = rejection.message)
            return
        }
        if (offlineNow) {
            _uiState.value = current.copy(batchError = ERROR_OFFLINE)
            return
        }
        selectOrdinalWindow(current, start, end)
    }

    /**
     * Load exactly the pages `[start, end]` spans and select the numbers whose
     * ORDINAL falls inside it.
     *
     * Deliberately computed from the ROWS OF THOSE PAGES ONLY (offset by the
     * window's first ordinal), so a range far from the start — 301..360 sitting
     * on pages 11..12 of a listing whose accumulated rows begin at page 11 —
     * still resolves correctly instead of indexing a partial list from zero.
     */
    private fun selectOrdinalWindow(current: AnimeDetailsUiState, start: Int, end: Int) {
        val perPage = current.perPage
        val pages: List<Int> =
            if (pagingOn(current) && perPage != null) {
                EpisodePaging.pagesForRange(start, end, perPage).toList()
            } else {
                emptyList()
            }

        _uiState.value = current.copy(
            pageLoading = pages.isNotEmpty(),
            batchError = null,
        )

        fetchPages(
            pages = pages,
            onDone = { anime, windows ->
                val numbers = rowsInOrdinalWindow(
                    windows = windows,
                    undivided = anime.episodes,
                    start = start,
                    end = end,
                    apiPerPage = perPage,
                ).map { it.number }.distinct().sorted()
                _uiState.value = _uiState.value.copy(
                    selectedNumbers = numbers.toSet(),
                    pageLoading = false,
                    batchError = if (numbers.isEmpty()) NO_EPISODES_MATCHED else null,
                )
            },
            // Scoped to the RANGE request: the visible page keeps its rows and
            // its number, and only the range selector reports the problem.
            onFailed = { message, _ ->
                _uiState.update { it.copy(pageLoading = false, batchError = message) }
            },
        )
    }

    /**
     * The rows whose ORDINAL lies in `[start, end]`, in `(number, audio)`
     * order — the ONE place an ordinal window becomes rows.
     *
     * [windows] are the API windows this request really has in hand, keyed by
     * their 1-based index and assembled while they were read ([ensureWindows]),
     * so a page or a range is cut from rows that are KNOWN present instead of
     * being re-read from a bounded cache that may have evicted one of them.
     *
     * The ranking starts at the FIRST window's own first ordinal — never at
     * zero — so a window far down the listing (301..360 sitting on windows
     * 11..12) resolves correctly, and a number's position is decided by
     * ORDERING, never by the episode NUMBER it carries (specials included).
     *
     * Paging OFF (`[apiPerPage]` absent) is the one undivided listing, so a
     * number's rank in it IS its ordinal — exactly the pre-paging behaviour.
     */
    private fun rowsInOrdinalWindow(
        windows: Map<Int, List<EpisodeInfo>>,
        undivided: List<EpisodeInfo>,
        start: Int,
        end: Int,
        apiPerPage: Int?,
    ): List<EpisodeInfo> {
        val rows: List<EpisodeInfo>
        val windowStart: Int
        when {
            apiPerPage == null || apiPerPage <= 0 -> {
                rows = undivided
                windowStart = 1
            }
            // Paging is ON but nothing of this request was really read: the
            // listing in hand belongs to OTHER pages, so it is never re-ranked
            // into this one (that would silently select someone else's rows).
            windows.isEmpty() -> return emptyList()
            else -> {
                rows = windows.entries.sortedBy { it.key }.flatMap { it.value }
                    .sortedWith(compareBy({ it.number }, { it.audio }))
                windowStart = (windows.keys.min() - 1) * apiPerPage + 1
            }
        }
        // The list's FIRST row already sits at `windowStart`, so reaching the
        // requested `start` means dropping `start - windowStart` positions —
        // NOT `windowStart - 1` positions, which would skip the window's own
        // offset and silently hand back a range shifted by it.
        val wanted = rows.map { it.number }.distinct().sorted()
            .drop((start - windowStart).coerceAtLeast(0))
            .take(end - start + 1)
            .toSet()
        return rows.filter { it.number in wanted }
    }

    /**
     * The rows of APP page [appPage] — its ordinal range, assembled from the
     * windows that range spans.
     *
     * This is what makes an app page of 100 real: its ordinals cover up to FOUR
     * 30-row windows, concatenated in ordinal order and cut to the range. A
     * final short page simply yields fewer rows, and a window whose fetch
     * failed never reaches here — [goToPage] renders the page error in that
     * case, never a silently shorter page.
     */
    private fun appPageRows(
        windows: Map<Int, List<EpisodeInfo>>,
        undivided: List<EpisodeInfo>,
        appPage: Int,
        appPageSize: Int,
        apiPerPage: Int,
    ): List<EpisodeInfo> {
        val ordinals = EpisodePaging.appPageOrdinalRange(appPage, appPageSize)
        return rowsInOrdinalWindow(windows, undivided, ordinals.first, ordinals.last, apiPerPage)
    }

    /**
     * Cancel the running batch loop. The episodes already queued stay queued —
     * they are real downloads owned by WorkManager from that point on.
     */
    fun cancelBatch() {
        batchJob?.cancel()
        batchJob = null
        val current = _uiState.value
        if (current.batchRunning) {
            _uiState.value = current.copy(
                batchRunning = false,
                batchFinished = true,
                // Slice C: a cancelled run makes NO per-episode claim. No retry
                // pass ever completed, so there is no honest list of "which
                // failed" — and posting one would assert a finished batch that
                // never happened. The aggregate counters above are the run's
                // partial progress and are left exactly as they were.
                batchFailures = emptyList(),
                leaveBatchWarning = false,
            )
        } else if (current.leaveBatchWarning) {
            _uiState.value = current.copy(leaveBatchWarning = false)
        }
    }

    // --- Phase 13 (DECISIONS 93.2): leave-a-running-batch confirmation ---

    /**
     * The user tried to navigate away while [AnimeDetailsUiState.batchRunning]
     * — open the confirmation warning with the submitted-vs-pending counts
     * instead of leaving. No-op when no batch is running (the caller navigates
     * away directly).
     */
    fun requestLeaveBatchWarning() {
        val current = _uiState.value
        if (!current.batchRunning) return
        _uiState.value = current.copy(
            leaveBatchWarning = true,
            leaveBatchSubmitted = current.batchQueued,
            leaveBatchPending = pendingOf(
                total = current.batchTotal,
                queued = current.batchQueued,
                skipped = current.batchSkipped,
                failed = current.batchFailed,
            ),
        )
    }

    /**
     * Stay on the screen: the warning closes and the batch keeps running
     * untouched (same guarantee as a configuration change, 93.2).
     */
    fun dismissLeaveBatchWarning() {
        if (_uiState.value.leaveBatchWarning) {
            _uiState.value = _uiState.value.copy(leaveBatchWarning = false)
        }
    }

    /**
     * Confirm leaving: exactly the shipped `Stop batch` semantics — cancelBatch()
     * drops the un-submitted tail (never counted as failed/skipped) while rows
     * already handed to WorkManager keep running.
     */
    fun confirmLeaveBatch() {
        cancelBatch()
    }

    /** Numbers of [total] never concluded by the loop — what leaving drops. */
    private fun pendingOf(total: Int, queued: Int, skipped: Int, failed: Int): Int =
        (total - (queued + skipped + failed)).coerceAtLeast(0)

    /**
     * Resolve AND queue every selected episode number — the Android
     * equivalent of `BatchOrchestrator.download(chosen)` (`downloader.py:167-327`).
     *
     * Behavior, point by point against the reference:
     *
     * * **Variant choice** — STRICTLY the DERIVED audio map: for every
     *   selected number its explicit override, otherwise the current audio
     *   context (`override ?: selectedAudio`), built ONCE below from the
     *   three states (DECISIONS 143.6 — derived at execution time, no stored
     *   duplicate mapping, no `defaultVariant` fallback). The reference's
     *   `get_variant(num, audio_lang) or variants[0]`
     *   fallback (`downloader.py:214-217`) is deliberately NOT ported: an
     *   audio no longer offered by the listing records an explicit failure
     *   ([AudioSelector.unavailableMessage]) instead of downloading a
     *   different audio (SPEC §22 — never silently substitute).
     * * **Completed/queued reuse** — an episode that already has a live or
     *   finished download is skipped BEFORE resolving (`_find_existing`,
     *   `downloader.py:224-230`), so a repeated batch costs no resolver round-trip.
     * * **Staging bound (`resolve_ahead`)** — before each resolution the batch
     *   waits for free capacity `resolve_ahead + max_parallel`
     *   (`downloader.py:205-208`); resolution therefore never runs unbounded
     *   ahead of the downloaders.
     * * **Retry** — a resolution gets `auto_retry + 1` attempts with Python's
     *   backoff and the same permanent-error rule, and the batch re-runs its
     *   failed episodes for `auto_retry + 1` passes (`downloader.py:170-185`),
     *   skipping successes (never re-queued).
     * * **Isolation** — one episode's failure never stops the loop and never
     *   touches an already-queued episode (each is a separate row + unique work).
     * * **Result keying by NUMBER** — the reference keys results by the variant's
     *   session id, which misreports a variant-swapped success as a failure (the
     *   reference's own derived quirk); this port keys by number instead.
     *
     * The UI state is only written while THIS run's session is still current, so
     * a superseded run cannot paint another anime's counters.
     */
    fun startBatchDownload() {
        val current = _uiState.value
        if (current.batchRunning) return
        val title = current.title ?: return
        val numbers = current.selectedNumbers.sorted()
        if (numbers.isEmpty()) return
        // A SELECTION batch is bounded only by the distinct-episode ceiling —
        // no page scope, exactly as before the page-scoped action existed.
        beginBatch(current, title, numbers, pageScope = null)
    }

    /**
     * Page-scoped batch download (correction, requirement 4) — queue EXACTLY
     * the episodes of the APP page the user is looking at.
     *
     * The contents AND the scope are the visible page's own rows, never
     * [AnimeDetailsUiState.selectedNumbers] (a selection legitimately spans
     * pages — "select all" is 100 episodes over four API windows) and never the
     * in-memory accumulator (which holds every page visited). So "this page" can
     * only ever name this page's episodes, and deriving both from that window is
     * what makes a cross-page batch structurally impossible rather than merely
     * discouraged.
     *
     * TWO guards still run inside this EXECUTION path, not merely in the UI:
     *
     * 1. every number must be inside [EpisodePaging.validateBatch]'s page scope —
     *    so a future caller that passed a wider list is refused outright instead
     *    of being quietly trimmed;
     * 2. the DISTINCT episode count may not exceed
     *    [EpisodePaging.MAX_BATCH_EPISODES]; audio variants and repeated
     *    references to one number collapse before counting, so a page of 100
     *    numbers carrying 150 rows is a batch of 100, not 150.
     *
     * Both refusals are rendered outcomes with nothing queued — never a quiet
     * truncation to the first 100.
     */
    fun startPageBatchDownload() {
        val current = _uiState.value
        if (current.batchRunning) return
        val title = current.title ?: return
        val onPage = displayEpisodeEpisodes(current)
        val numbers = onPage.map { it.number }.distinct().sorted()
        if (numbers.isEmpty()) {
            _uiState.value = current.copy(batchError = NO_EPISODES_MATCHED)
            return
        }
        beginBatch(
            current = current,
            title = title,
            numbers = numbers,
            pageScope = numbers.toSet(),
            sourceRows = onPage,
        )
    }

    /**
     * THE batch start — the single execution boundary every batch goes through
     * (selection batches and page-scoped batches alike), so the distinct
     * ceiling, the page scope and the offline refusal cannot be bypassed by a
     * second entry point.
     *
     * @param pageScope the episodes of the one page the batch is scoped to, or
     *   `null` for a scope-free selection batch
     * @param sourceRows the rows THIS batch was derived from. They are merged
     *   into the listing the run resolves against, so a page-scoped batch keeps
     *   resolving its own episodes even if the bounded page cache evicted those
     *   windows while another request ran — the alternative would be honest but
     *   wrong-looking "not in listing" failures for episodes that ARE on screen.
     *   Empty for a selection batch, whose input stays exactly what it was.
     */
    private fun beginBatch(
        current: AnimeDetailsUiState,
        title: String,
        numbers: List<Float>,
        pageScope: Set<Float>?,
        sourceRows: List<EpisodeInfo> = emptyList(),
    ) {
        // Lazy-loading slice — THE batch execution boundary, enforced in the
        // execution path and not merely in the range/selection UI: more than
        // 100 UNIQUE episodes is refused with an explicit, rendered outcome
        // instead of being quietly truncated to the first 100. The identical
        // rule guards the non-UI caller (WatchlistChecker.autoDownload), so a
        // caller that bypasses this screen cannot slip 101 episodes through.
        val rejection = EpisodePaging.validateBatch(numbers, pageScope)
        if (rejection != null) {
            _uiState.value = current.copy(batchError = rejection.message)
            return
        }
        // DECISIONS 97.4 (SPEC §27): a batch starts by resolving every selected
        // stream — impossible while OFFLINE. Refuse honestly (the panel renders
        // this message) instead of starting a run that would fail one entry at a
        // time. Downloading itself is never paused by the monitor (97.5): a batch
        // already handed to WorkManager keeps its existing terms.
        if (offlineNow) {
            _uiState.value = current.copy(batchError = ERROR_OFFLINE)
            return
        }

        batchJob?.cancel()
        // DECISIONS 143.6: the number → requested-audio execution mapping is
        // DERIVED HERE, once, from the three states — selected numbers + the
        // current audio context + explicit overrides (an override wins) — and
        // then FROZEN for the whole run: the loop never reads live selection
        // state, and no second stored mapping exists anywhere in the app.
        val requestedAudioByNumber: Map<Float, String> =
            numbers.associateWith { current.explicitAudioOverrides[it] ?: current.selectedAudio }
        _uiState.value = current.copy(
            batchRunning = true,
            batchFinished = false,
            batchQueued = 0,
            batchSkipped = 0,
            batchFailed = 0,
            batchTotal = numbers.size,
            batchError = null,
            // Slice C: a fresh run never inherits the previous run's per-episode
            // failures — same rule as `batchError` above.
            batchFailures = emptyList(),
            // A fresh run never inherits a stale leave-warning (93.2).
            leaveBatchWarning = false,
        )
        batchJob = viewModelScope.launch {
            runBatch(title, numbers, requestedAudioByNumber, sourceRows)
        }
    }

    /** The batch loop — see [startBatchDownload] for the parity contract. */
    private suspend fun CoroutineScope.runBatch(
        title: String,
        numbers: List<Float>,
        requestedAudioByNumber: Map<Float, String>,
        sourceRows: List<EpisodeInfo> = emptyList(),
    ) {
        val startedFor = _uiState.value.session
        // The listing this run resolves against: the accumulated pages PLUS the
        // rows the batch was derived from (see [beginBatch] — empty for a
        // selection batch, whose input is exactly what it always was).
        val episodes = (
            _uiState.value.episodes + sourceRows
            ).distinctBy { it.number to it.audio }
            .sortedWith(compareBy({ it.number }, { it.audio }))
        val host = AnimeRepository.currentHost

        // One settings read per batch (Python builds one AppContext per run).
        // Audio is NOT a setting: it comes from this run's DERIVED mapping —
        // context + overrides frozen at start (SPEC §22, DECISIONS 143.6).
        val quality = configuredQuality()
        val autoRetry = configuredAutoRetry()
        val stagingCapacity = configuredResolveAhead() + configuredMaxParallel()
        val maxPasses = autoRetry + 1
        val maxResolutionAttempts = autoRetry + 1

        val anime = AnimeInfo(session = startedFor, title = title, host = host, episodes = episodes)
        var queued = 0
        var skipped = 0
        var remaining = numbers
        var pass = 0
        // Slice C: the reasons behind [batchFailed]. The per-pass map is
        // recreated each pass exactly like the counter it feeds, so only the
        // pass that finally ended the batch decides what is REPORTED — its keys
        // are the same numbers `remaining` hands to [publishBatchDone], so the
        // list can never disagree with the count.
        var lastPassFailures: LinkedHashMap<Float, BatchEpisodeFailure> = linkedMapOf()

        try {
            while (remaining.isNotEmpty() && pass < maxPasses && isActive) {
                if (pass > 0) {
                    // Python: `await asyncio.sleep(2 * batch_attempt)` before a
                    // retry pass (downloader.py:177-185).
                    delay(BATCH_RETRY_DELAY_BASE_MS * pass)
                }
                val passFailures = LinkedHashMap<Float, BatchEpisodeFailure>()
                for (number in remaining) {
                    ensureActive()
                    // Order is the reference's: the handled/no-variant checks come
                    // BEFORE any capacity wait or resolver work, so a repeated batch
                    // costs neither a gate wait nor a round-trip
                    // (`_find_existing`, downloader.py:224-230).
                    // DECISIONS 144.4 — the listing-only gate is GONE where the
                    // number exists: the requested audio (this run's derived
                    // mapping — override ?: context) is carried onto that
                    // number's own play page ([variantOrCarrier]) and the
                    // RESOLVER decides whether the page really offers it. Only a
                    // number that does not exist in this listing fails here,
                    // with the pre-existing reason — and NO other audio is ever
                    // substituted.
                    val requestedAudio = requestedAudioByNumber[number]
                    val variant = requestedAudio
                        ?.let { audio -> variantOrCarrier(anime.episodes, number, audio) }
                    if (variant == null) {
                        // Slice C: this branch is reached ONLY when the NUMBER
                        // itself is absent from the listing — `variantOrCarrier`
                        // manufactures a carrier for any number that has a row,
                        // so a missing number is the sole cause here. The reason
                        // is therefore number-centric and the category is
                        // EPISODE_NOT_IN_LISTING: the resolver was never
                        // consulted, so calling this "requested audio not
                        // available" would assert evidence this path does not
                        // have. The requested audio is still carried on the
                        // failure so the report can name what was asked for, and
                        // no other audio is ever substituted.
                        if (requestedAudio != null) {
                            AppLog.w(
                                TAG,
                                "Batch: $NO_VARIANT_AVAILABLE " +
                                    "(episode $number, audio $requestedAudio) — " +
                                    "recorded, not substituted.",
                            )
                        }
                        passFailures[number] = BatchEpisodeFailure(
                            number = number,
                            category = FailureCategory.EPISODE_NOT_IN_LISTING,
                            reason = NO_VARIANT_AVAILABLE,
                            audio = requestedAudio,
                        )
                        publishBatchProgress(startedFor, queued, skipped, passFailures.size)
                        continue
                    }
                    if (downloadQueue.isEpisodeAlreadyHandled(startedFor, variant)) {
                        // Python: `mark_done("already exists")` (downloader.py:224-230).
                        skipped++
                        publishBatchProgress(startedFor, queued, skipped, passFailures.size)
                        continue
                    }
                    if (!ResolveAheadGate.awaitCapacity(downloadQueue, stagingCapacity)) {
                        // The gate's budget expired: `number` and every later number
                        // of this pass were never examined, so they must be reported
                        // rather than silently dropped from the counters.
                        val unattempted = remaining.size - remaining.indexOf(number)
                        publishBatchProgress(
                            startedFor,
                            queued,
                            skipped,
                            passFailures.size + unattempted,
                        )
                        publishBatchError(
                            startedFor,
                            capacityMessage(stagingCapacity) +
                                " ($unattempted of ${remaining.size} episodes in this pass " +
                                "were never attempted)",
                        )
                        return
                    }
                    try {
                        val stream = StreamResolutionRetry.resolveWithRetry(
                            resolver = streamResolver,
                            episode = variant,
                            quality = quality,
                            maxAttempts = maxResolutionAttempts,
                        )
                        val outcome = downloadQueue.queueResolvedEpisode(
                            ResolvedEpisodeSubmission(
                                animeSession = startedFor,
                                animeTitle = title,
                                host = host,
                                quality = quality,
                                episode = variant,
                                stream = stream,
                            ),
                        )
                        // Slice B: the counter reflects what the pipeline REALLY
                        // did. `Accepted` and `OutputMissing` both mean a Worker
                        // was handed this episode (the second is the legitimate
                        // re-download of an export the user deleted), so both
                        // are "queued". Anything else means no new download
                        // started — counting it as queued would claim work the
                        // app never performed.
                        if (outcome == EnqueueOutcome.Accepted ||
                            outcome == EnqueueOutcome.OutputMissing
                        ) {
                            queued++
                        } else {
                            skipped++
                        }
                        // the same first-enqueue gate as
                        // the single path — after the asked-once latch is consumed
                        // every later call is a cheap no-op.
                        maybeRequestNotificationPermission()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Isolated: recorded, then the loop continues with the next number.
                        //
                        // Slice C: the category is read off the exception OBJECT,
                        // which is still in scope here — never from its text.
                        // This block wraps BOTH the resolver call and
                        // `queueResolvedEpisode`, so a non-resolution exception
                        // is reported as UNKNOWN rather than being guessed at:
                        // "the episode did not get queued, and we do not know
                        // why" is the strongest honest claim available. A plain
                        // exception whose message merely LOOKS like an
                        // audio-unavailability message still classifies UNKNOWN.
                        val reason = e.message?.takeIf { it.isNotBlank() }
                            ?: ERROR_RESOLUTION_FAILED
                        val category = if (e is StreamResolutionException) {
                            FailureCategory.fromResolutionCode(e.code)
                        } else {
                            FailureCategory.UNKNOWN
                        }
                        passFailures[number] = BatchEpisodeFailure(
                            number = number,
                            category = category,
                            reason = reason,
                            audio = requestedAudio,
                        )
                    }
                    publishBatchProgress(startedFor, queued, skipped, passFailures.size)
                }
                lastPassFailures = passFailures
                remaining = passFailures.keys.sorted()
                pass++
            }
            publishBatchDone(startedFor, title, queued, skipped, remaining.size, lastPassFailures)
        } catch (e: CancellationException) {
            // A cancelled batch (Cancel button, or a session replacement in
            // `load`) stops here — [cancelBatch]/[load] own the state write.
            throw e
        } catch (e: Exception) {
            publishBatchError(startedFor, e.message?.takeIf { it.isNotBlank() } ?: ERROR_DOWNLOAD_FAILED)
        }
    }

    /** Write batch progress only while this run's session is still the current one. */
    private fun publishBatchProgress(session: String, queued: Int, skipped: Int, failed: Int) {
        if (_uiState.value.session != session) return
        val current = _uiState.value
        _uiState.value = current.copy(
            batchQueued = queued,
            batchSkipped = skipped,
            batchFailed = failed,
            // DECISIONS 93.2: while the leave-warning is open its counts stay
            // live — the dialog states what leaving WOULD drop right now.
            leaveBatchSubmitted = if (current.leaveBatchWarning) queued else current.leaveBatchSubmitted,
            leaveBatchPending = if (current.leaveBatchWarning) {
                pendingOf(current.batchTotal, queued, skipped, failed)
            } else {
                current.leaveBatchPending
            },
        )
    }

    /**
     * Slice C — the ONLY legal site for the aggregated batch-failure
     * notification, and the point where the per-episode reasons finally reach
     * the user.
     *
     * @param failures the LAST pass's failures, whose keys are exactly the
     *   numbers [failed] counts, so the rendered list can never disagree with
     *   the rendered count.
     */
    private fun publishBatchDone(
        session: String,
        title: String,
        queued: Int,
        skipped: Int,
        failed: Int,
        failures: Map<Float, BatchEpisodeFailure>,
    ) {
        if (_uiState.value.session != session) return
        val total = _uiState.value.batchTotal
        _uiState.value = _uiState.value.copy(
            batchRunning = false,
            batchFinished = true,
            batchQueued = queued,
            batchSkipped = skipped,
            batchFailed = failed,
            // Slice C: episode-ordered so the rendered list reads naturally.
            batchFailures = failures.values.sortedBy { it.number },
            // Nothing pending anymore — leaving needs no confirmation now.
            leaveBatchWarning = false,
        )
        // `failed > 0` is the first gate: a batch that queued or skipped
        // everything posts nothing at all.
        if (failed > 0) {
            notifyBatchFailure(
                failed = failed,
                total = total,
                title = title,
                reasonCounts = failureReasonCounts(failures.values),
            )
        }
    }

    /**
     * Post-audio slice — the label→count breakdown the batch summary renders.
     *
     * Built from [BatchEpisodeFailure.category] ONLY: `label` is a typed,
     * hand-written sentence per [FailureCategory], and `null` for
     * [FailureCategory.UNKNOWN] — which this mapping therefore drops rather
     * than inventing a cause for. The per-episode `reason` string is
     * deliberately NOT used: it is exception prose, and AGENTS §10 forbids
     * putting text that may quote a stream URL into a notification.
     *
     * A `LinkedHashMap` keyed by label, so insertion order is stable for the
     * renderer (which re-sorts by count anyway).
     */
    internal fun failureReasonCounts(failures: Collection<BatchEpisodeFailure>): Map<String, Int> =
        failures.mapNotNull { it.category.label }
            .groupingBy { it }
            .eachCount()

    /**
     * Slice C — post the ONE aggregated batch-failure notification.
     *
     * Deliberate gates, in order:
     *
     *  - only ever reached from [publishBatchDone], so a capacity abort
     *    ([publishBatchError]) can never post: those numbers were never
     *    attempted, and announcing them as failures would be a lie;
     *  - never reached after a cancellation, which rethrows out of the batch
     *    loop before this function is called;
     *  - the `notify_failure` preference, read FRESH here so a Settings change
     *    shapes the next notification rather than an in-flight run;
     *  - a null notifier (every existing direct-constructed test VM) is a
     *    no-op.
     *
     * Posting is best-effort by contract: a notification problem must never
     * fail a batch that already finished, matching `WatchlistCheckWorker`.
     */
    private fun notifyBatchFailure(
        failed: Int,
        total: Int,
        title: String,
        reasonCounts: Map<String, Int>,
    ) {
        val notifier = batchFailureNotifier ?: return
        viewModelScope.launch {
            val enabled = try {
                notifyFailurePrefs().failure
            } catch (e: CancellationException) {
                // The prefs read is a suspension point — a cancellation here is
                // part of the ViewModel's own teardown and must propagate.
                throw e
            } catch (e: Exception) {
                AppLog.w(
                    TAG,
                    "Notification preference read failed; suppressing batch failure notification: ${e.message}",
                )
                return@launch
            }
            if (!enabled) return@launch
            try {
                notifier(failed, total, title, reasonCounts)
            } catch (e: CancellationException) {
                // The notifier is a suspension point — a cancelled ViewModel
                // must not have its teardown reported as a notification fault
                // (AQR-009).
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Batch failure notification failed: ${e.message}")
            }
        }
    }

    private fun publishBatchError(session: String, message: String) {
        if (_uiState.value.session != session) return
        _uiState.value = _uiState.value.copy(
            batchRunning = false,
            batchFinished = true,
            batchError = message,
            leaveBatchWarning = false,
        )
    }

    private fun capacityMessage(capacity: Int): String =
        "Timed out waiting for download capacity (resolve_ahead staging: $capacity episodes)."
}
