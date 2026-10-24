package com.pahebatcher.app.ui.downloads

import androidx.compose.runtime.Immutable
import com.pahebatcher.app.logging.AppLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.domain.download.ReplacementOutcome
import com.pahebatcher.app.ui.mapper.DownloadRowMapper
import com.pahebatcher.app.ui.mapper.DownloadRowUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * DownloadsUiState (PHASE11_ARCHITECTURE_V2 §13).
 *
 * @property rows immutable dashboard rows — pure product of the Room row via
 *   [DownloadRowMapper]; the UI never computes its own progress (§16).
 * @property isLoading true only until the first Room emission (initial state) —
 *   after that the list itself (including empty) IS the state (§35).
 * @property loadError A2.1 Decision 9 (UI-010): non-null when at least one row
 *   could not be mapped for display — every other row still renders, and this
 *   is a sanitized, human-readable note (never a URL, AGENTS §10).
 */
@Immutable
data class DownloadsUiState(
    val rows: List<DownloadRowUi> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
)

/**
 * Downloads dashboard ViewModel (PHASE11_ARCHITECTURE_V2 §13, §16).
 *
 * Room remains the source of truth: the ordered observe-all Flow is mapped to
 * immutable rows and exposed as [StateFlow]. No polling, no view-model timers —
 * the engine's 5000 ms ProgressTracker throttle bounds the emission rate (§13).
 *
 * Actions delegate to the real repository and are invoked by the screen only when
 * the §17.1 matrix permits them (the mapper already gates availability).
 *
 * Final-audit H5 (SPEC §26 "Do not hide exceptions / Display human-readable
 * errors"): an action that throws is still logged, but it is NO LONGER silent —
 * a sanitized human-readable message reaches [actionError] and the screen shows
 * it until [dismissActionError].
 */
class DownloadsViewModel(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "DownloadsViewModel"

        /** Keep the Room subscription warm briefly across config/destination changes. */
        private const val STOP_TIMEOUT_MS = 5000L
    }

    val uiState: StateFlow<DownloadsUiState> = downloadRepository.observeAll()
        .map { entities ->
            // A2.1 Decision 9 (UI-010): per-row isolation — one unmappable row
            // surfaces a sanitized loadError instead of killing the emission.
            val mapped = DownloadRowMapper.mapAll(entities)
            DownloadsUiState(
                rows = mapped.rows,
                isLoading = false,
                loadError = mapped.loadError,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = DownloadsUiState(),
        )

    /**
     * Final-audit H5 (SPEC §26): the ONE user-visible action-failure channel.
     *
     * A single nullable message — newest failure replaces the previous one,
     * [dismissActionError] clears it. StateFlow (not a one-shot event stream)
     * so a rotation or a recomposition never drops a message the user has not
     * acknowledged yet: the banner simply re-renders from the current value.
     */
    private val _actionError = MutableStateFlow<String?>(null)

    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /**
     * `Replace file` (DECISIONS 152) — the episode whose confirmation dialog is
     * open, or `null`.
     *
     * Held as an id rather than as a pre-resolved "replaceable" flag, so the
     * confirm step re-derives everything from Room at the moment of
     * confirmation. Nothing destructive is decided when the dialog is requested
     * — the request only decides whether there is anything to confirm.
     */
    private val _replacementTarget = MutableStateFlow<String?>(null)

    val replacementTarget: StateFlow<String?> = _replacementTarget.asStateFlow()

    /** SPEC §26: the user acknowledged the message — clear it. */
    fun dismissActionError() {
        _actionError.value = null
    }

    /**
     * SPEC §26 "Do not hide exceptions / Display human-readable errors":
     * log the full diagnostic, publish a SANITIZED human-readable message.
     *
     * The published text never carries a URL (`://` anywhere in the exception
     * message falls back to the exception type), matching the Worker's rule
     * that no URL from `e.message` is ever surfaced — AGENTS §10 (never log or
     * expose session/stream locations) applies to what the UI shows too.
     */
    private fun reportActionFailure(action: String, episodeId: String, e: Exception) {
        AppLog.e(TAG, "$action failed for $episodeId", e)
        val detail = e.message?.takeIf { "://" !in it } ?: e.javaClass.simpleName
        _actionError.value = "$action failed: $detail"
    }

    /**
     * Final-audit H5 — the ONE failure path of an action coroutine. Nothing is
     * swallowed (SPEC §26), but structured concurrency is preserved:
     *
     *  - **Our own cancellation** (the ViewModel is cleared, `viewModelScope`
     *    torn down): the current coroutine context is cancelled, so
     *    [ensureActive] rethrows and the cancellation propagates — a cleared
     *    ViewModel publishes nothing.
     *  - **Everything else**, INCLUDING a `CancellationException` raised from
     *    INSIDE the repository while this coroutine is still alive (measured:
     *    Room surfaces a closed database as `JobCancellationException`, which
     *    the old `catch (CancellationException) { throw }` silently swallowed):
     *    logged and surfaced as the real failure it is.
     */
    private suspend fun handleActionFailure(action: String, episodeId: String, e: Exception) {
        if (e is CancellationException) {
            currentCoroutineContext().ensureActive()
        }
        reportActionFailure(action, episodeId, e)
    }

    /** Cancel an active row (matrix: QUEUED/RESOLVING/DOWNLOADING/ASSEMBLING only). */
    fun cancel(episodeId: String) {
        viewModelScope.launch {
            try {
                downloadRepository.cancel(episodeId)
            } catch (e: Exception) {
                handleActionFailure("Cancel", episodeId, e)
            }
        }
    }

    /** Retry a terminal-failed row (matrix: FAILED/CANCELLED only). */
    fun retry(episodeId: String) {
        viewModelScope.launch {
            try {
                downloadRepository.reEnqueue(episodeId)
            } catch (e: Exception) {
                handleActionFailure("Retry", episodeId, e)
            }
        }
    }

    /**
     * SPEC §20 **Pause** (matrix: `EpisodeDownloadEntity.PAUSABLE_STATES` only).
     *
     * The repository persists the `PAUSED` intent before it cancels the unique
     * Work, so the UI must not optimistically flip anything — the Room emission
     * that follows is the state of record (SPEC §25: observe persisted state).
     */
    fun pause(episodeId: String) {
        viewModelScope.launch {
            try {
                downloadRepository.pause(episodeId)
            } catch (e: Exception) {
                handleActionFailure("Pause", episodeId, e)
            }
        }
    }

    /**
     * SPEC §20 **Resume** (matrix: `PAUSED` only) — the persisted `PAUSED → QUEUED`
     * claim, or a re-resolution when the row owns no stream. Same rule as [pause]:
     * nothing is faked in view state, the next Room emission is what the UI shows.
     */
    fun resume(episodeId: String) {
        viewModelScope.launch {
            try {
                downloadRepository.resume(episodeId)
            } catch (e: Exception) {
                handleActionFailure("Resume", episodeId, e)
            }
        }
    }

    /**
     * `Replace file` (DECISIONS 152) — arm the confirmation dialog for
     * [episodeId], or explain why there is nothing to confirm.
     *
     * Two properties matter here:
     *
     *  * it is a REQUEST, not an action — nothing is claimed, enqueued,
     *    deleted or overwritten until [confirmReplacement] runs;
     *  * the dialog is gated on the repository's §29 rule, so a stale
     *    notification action (the export is gone, the row is no longer
     *    completed, the download was deleted meanwhile) surfaces an honest
     *    message instead of a confirmation dialog that would replace nothing.
     */
    fun requestReplacement(episodeId: String) {
        viewModelScope.launch {
            try {
                if (!downloadRepository.isReplacementAvailable(episodeId)) {
                    _actionError.value =
                        "Replace file is unavailable — there is no completed download to replace"
                    return@launch
                }
                _replacementTarget.value = episodeId
            } catch (e: Exception) {
                handleActionFailure("Replace file", episodeId, e)
            }
        }
    }

    /**
     * `Replace file` (DECISIONS 152) — the confirmation button.
     *
     * The dialog is dismissed BEFORE the repository is called, so this is a
     * single decision: it either claims the row (and the run takes over from
     * there) or it reports why it did not. The repository re-validates
     * everything, so a confirmation that arrives after the export disappeared
     * changes nothing and says so.
     */
    fun confirmReplacement() {
        val episodeId = _replacementTarget.value ?: return
        _replacementTarget.value = null
        viewModelScope.launch {
            try {
                val outcome = downloadRepository.confirmReplacement(episodeId)
                _actionError.value = when (outcome) {
                    ReplacementOutcome.Started -> null
                    ReplacementOutcome.AlreadyInFlight ->
                        "Replace file is already running for this episode"
                    is ReplacementOutcome.Unavailable ->
                        "Replace file is unavailable — ${outcome.reason}"
                    ReplacementOutcome.Refused ->
                        "Replace file could not be started"
                }
            } catch (e: Exception) {
                handleActionFailure("Replace file", episodeId, e)
            }
        }
    }

    /**
     * `Replace file` (DECISIONS 152) — the cancel button and every dismissal.
     *
     * A pure state clear: no repository call, no write, no coroutine. The
     * export, its state and its output references are untouched, which is the
     * whole point of the button existing separately from Confirm.
     */
    fun dismissReplacement() {
        _replacementTarget.value = null
    }
}
