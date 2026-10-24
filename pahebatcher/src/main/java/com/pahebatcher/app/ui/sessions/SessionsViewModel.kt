package com.pahebatcher.app.ui.sessions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.repository.SessionActions
import com.pahebatcher.app.data.repository.SessionDeletePreparation
import com.pahebatcher.app.data.repository.SessionDisplay
import com.pahebatcher.app.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One immutable row of the Sessions screen (SPEC §20 display list).
 *
 * @param resumeSession the anime session UUID for `AnimeDetailsRoute` — the
 *   Resume target (DECISIONS 94: Resume navigates, it is not a state flip).
 * @param displayState derived per DECISIONS 109 — display-only, never written.
 * @param pauseCount how many rows of this session the SPEC §20 Pause action
 *   would really stop (`EpisodeDownloadEntity.PAUSABLE_STATES`) — the button is
 *   enabled only while this is > 0.
 * @param resumeCount how many rows of this session are actually `PAUSED` — the
 *   SPEC §20 Resume-all control appears only while this is > 0.
 * @param deleteEnabled / tempCacheEnabled the 106.1 terminal-only gates with
 *   their honest disabled reasons (the state IS shown while disabled).
 */
@Immutable
data class SessionRowUi(
    val sessionId: String,
    val resumeSession: String,
    val title: String,
    val url: String,
    val qualityLabel: String,
    val audioLang: String,
    val episodeCount: Int,
    val completedEpisodes: Int,
    val incompleteEpisodes: Int,
    val completedSegments: Int,
    val totalSegments: Int,
    val segmentPercent: Int?,
    val cacheBytes: Long,
    val displayState: String,
    val cancelCount: Int,
    val retryCount: Int,
    val pauseCount: Int,
    val resumeCount: Int,
    val deleteEnabled: Boolean,
    val deleteDisabledReason: String?,
    val tempCacheEnabled: Boolean,
    val tempCacheDisabledReason: String?,
)

/** The explicit delete-session confirmation (SPEC §20 naming requirement). */
@Immutable
data class DeleteSessionDialogState(
    val sessionId: String,
    val title: String,
    val exportedCount: Int,
    val unexportedNames: List<String>,
    val temporaryBytes: Long,
)

/** The clear-all-cache confirmation carrying the size it will free (107). */
@Immutable
data class ClearCacheDialogState(
    val totalBytes: Long,
)

@Immutable
data class SessionsUiState(
    val isLoading: Boolean = true,
    val rows: List<SessionRowUi> = emptyList(),
    val globalCacheBytes: Long = 0L,
    /** The 106.2 gate: enabled only while NO episode anywhere is non-terminal. */
    val clearAllEnabled: Boolean = false,
    val clearAllDisabledReason: String? = null,
    /** Non-null while an aggregate action (e.g. export-first) is running. */
    val busySessionId: String? = null,
    val deleteDialog: DeleteSessionDialogState? = null,
    val clearCacheDialog: ClearCacheDialogState? = null,
    /** One-shot honest feedback line (action result or refusal). */
    val message: String? = null,
)

/**
 * The Sessions screen's
 * ViewModel.
 *
 * Observes the Room-backed session and episode rows through
 * [SessionActions] (one source of truth) and performs every action through
 * the same object — the composable receives state and lambdas only
 * (AGENTS §4). Cache sizes are recomputed from the filesystem on the IO
 * dispatcher, debounced behind the first emission so a progress tick never
 * forces a walk on the main thread (DECISIONS 107.3).
 */
class SessionsViewModel(
    private val sessionRepository: SessionRepository,
    private val actions: SessionActions,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Debounced per-episode emissions → filesystem size pass on IO.
            // First emission uses a 0 ms timeout so the initial sizes appear
            // with the first rows; later recomputations settle after the
            // quiet period instead of walking files per progress tick.
            var sizesPrimed = false
            val sizes = actions.observeEpisodes()
                .debounce {
                    val timeout = if (sizesPrimed) CACHE_SIZE_DEBOUNCE_MS else 0L
                    sizesPrimed = true
                    timeout
                }
                .mapLatest {
                    withContext(Dispatchers.IO) { actions.cacheSizesBySession() }
                }

            combine(
                actions.observeSessions(),
                actions.observeEpisodes(),
                sizes,
            ) { sessions, episodes, sizeMap -> Triple(sessions, episodes, sizeMap) }
                .collect { (sessions, episodes, sizeMap) ->
                    val bySession = episodes.groupBy { it.sessionId }
                    val rows = sessions.map { session ->
                        buildSessionRow(
                            session = session,
                            episodes = bySession[session.sessionId].orEmpty(),
                            cacheBytes = sizeMap[session.sessionId] ?: 0L,
                        )
                    }
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            rows = rows,
                            globalCacheBytes = sizeMap.values.sum(),
                            clearAllEnabled = episodes.none { !it.isTerminal() },
                            clearAllDisabledReason = if (episodes.any { !it.isTerminal() }) {
                                CLEAR_ALL_DISABLED_REASON
                            } else {
                                null
                            },
                        )
                    }
                }
        }
    }

    // ------------------------------------------------------------------
    // Actions (94) — every mutation goes through SessionActions
    // ------------------------------------------------------------------

    fun cancelSession(sessionId: String) = launchAction(sessionId) {
        val cancelled = actions.cancelSession(sessionId)
        if (cancelled > 0) "Cancelled $cancelled download(s)" else null
    }

    fun retrySession(sessionId: String) = launchAction(sessionId) {
        val attempted = actions.retrySession(sessionId)
        if (attempted > 0) "Retrying $attempted episode(s)" else "Nothing to retry"
    }

    /**
     * SPEC §20 **Pause** — stops every pausable row of this session. The counts
     * on the row (and therefore the button enablement) come from the same Room
     * rows, so the message reports what really happened rather than what was
     * requested.
     */
    fun pauseSession(sessionId: String) = launchAction(sessionId) {
        val paused = actions.pauseSession(sessionId)
        when {
            paused > 0 -> "Paused $paused download(s)"
            else -> "Nothing to pause"
        }
    }

    /**
     * SPEC §20 **Resume** — the counterpart of [pauseSession]. Distinct from the
     * Resume button that NAVIGATES into the session (DECISIONS 94); this one
     * flips `PAUSED` rows back to work.
     */
    fun resumeSession(sessionId: String) = launchAction(sessionId) {
        val resumed = actions.resumeSession(sessionId)
        when {
            resumed > 0 -> "Resumed $resumed download(s)"
            else -> "Nothing to resume"
        }
    }

    /**
     * Delete temporary cache for ONE session (94) — the SessionActions gate
     * refuses while any episode of this session is non-terminal.
     */
    fun deleteTemporaryCache(sessionId: String) = launchAction(sessionId) {
        val cleared = actions.deleteTemporaryCache(sessionId)
        if (cleared) "Temporary cache removed" else SessionsViewModel.TEMP_CACHE_DISABLED_REASON
    }

    /** Export-first preparation, then the explicit confirmation dialog (94/106). */
    fun requestDeleteSession(sessionId: String) {
        if (_uiState.value.busySessionId != null) return
        _uiState.update { it.copy(busySessionId = sessionId, message = null) }
        viewModelScope.launch {
            // AQR-002 (the UI-006 rule this file states but did NOT apply here):
            // `runCatching` also caught CancellationException, so abandoning this
            // export-first preparation — the slowest Session operation — published a
            // spurious "Delete failed" and swallowed the cancellation. Same shape as
            // launchAction: cancellation rethrows; only real failures become a message.
            val preparation = try {
                actions.prepareSessionDelete(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(sessionId) { copy(message = e.message ?: "Delete failed") }
                return@launch
            }
            finish(sessionId) {
                when (preparation) {
                    is SessionDeletePreparation.Ready -> copy(
                        deleteDialog = DeleteSessionDialogState(
                            sessionId = preparation.plan.sessionId,
                            title = rows.firstOrNull { it.sessionId == sessionId }?.title
                                ?: sessionId,
                            exportedCount = preparation.plan.exportedCount,
                            unexportedNames = preparation.plan.unexportedNames,
                            temporaryBytes = preparation.plan.temporaryBytes,
                        ),
                    )

                    is SessionDeletePreparation.Refused -> copy(message = preparation.reason)
                }
            }
        }
    }

    fun confirmDeleteSession() {
        val dialog = _uiState.value.deleteDialog ?: return
        _uiState.update { it.copy(deleteDialog = null) }
        launchAction(dialog.sessionId) {
            val removed = actions.deleteSession(
                sessionId = dialog.sessionId,
                deleteUnexportedLocalMedia = true, // the confirmation named them
            )
            if (removed) "Session deleted" else "Not deleted — downloads in progress"
        }
    }

    fun dismissDeleteSession() {
        _uiState.update { it.copy(deleteDialog = null) }
    }

    fun requestClearCache() {
        if (!_uiState.value.clearAllEnabled) return
        viewModelScope.launch {
            val sizes = withContext(Dispatchers.IO) { actions.cacheSizesBySession() }
            _uiState.update { it.copy(clearCacheDialog = ClearCacheDialogState(sizes.values.sum())) }
        }
    }

    fun confirmClearCache() {
        _uiState.update { it.copy(clearCacheDialog = null) }
        viewModelScope.launch {
            // AQR-002: same UI-006 rule — CancellationException rethrows instead of
            // being folded into `false` (which would publish a misleading
            // "nothing cleared" message for work that was merely abandoned).
            // A real failure keeps its existing mapping to `false` unchanged.
            val cleared = try {
                actions.clearAllCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _uiState.update {
                it.copy(message = if (cleared) "Cache cleared" else CLEAR_ALL_DISABLED_REASON)
            }
        }
    }

    fun dismissClearCache() {
        _uiState.update { it.copy(clearCacheDialog = null) }
    }

    // AQR-043: `dismissMessage()` removed — it had zero call sites anywhere
    // (production or tests), so the rendered `message` was never user-
    // dismissible through it anyway. Wiring an actual dismiss control would
    // be a product decision (AQR-046 territory), not taken here.

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * One guarded action: the [busySessionId] lock serializes the aggregate
     * operations (export-first can take seconds) and the result message is
     * published only if this row still exists.
     */
    private fun launchAction(
        sessionId: String,
        action: suspend () -> String?,
    ) {
        if (_uiState.value.busySessionId != null) return
        _uiState.update { it.copy(busySessionId = sessionId, message = null) }
        viewModelScope.launch {
            // UI-006: `runCatching` also caught CancellationException, turning a
            // normal viewModelScope teardown into a spurious "…failed" message and
            // swallowing the cancellation signal. Cancellation rethrows; only real
            // failures become a message (same pattern as DownloadsViewModel).
            val message = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Action failed"
            }
            finish(sessionId) { copy(message = message) }
        }
    }

    private fun finish(sessionId: String, mutate: SessionsUiState.() -> SessionsUiState) {
        _uiState.update { current ->
            if (current.busySessionId == sessionId) {
                current.copy(busySessionId = null).mutate()
            } else {
                current
            }
        }
    }

    companion object {
        const val CLEAR_ALL_DISABLED_REASON = "Downloads in progress — nothing cleared"
        const val DELETE_DISABLED_REASON = "Cancel first — downloads in progress"
        const val TEMP_CACHE_DISABLED_REASON = "Downloads in progress"

        /**
         * Quiet period before a filesystem size recomputation after an episode
         * emission — progress writes arrive every few seconds per active row,
         * and a directory walk per tick would be pointless work.
         */
        const val CACHE_SIZE_DEBOUNCE_MS = 1_500L
    }
}

/**
 * Pure row construction (DECISIONS 94/107/109) — JVM-testable without any
 * Android dependency: stats and state from the rows, enablement from the
 * 106 gates, cache size from the passed precomputed value.
 */
fun buildSessionRow(
    session: DownloadSessionEntity,
    episodes: List<EpisodeDownloadEntity>,
    cacheBytes: Long,
): SessionRowUi {
    val stats = SessionDisplay.deriveStats(episodes)
    val allTerminal = episodes.all { it.isTerminal() }
    val cancelCount = episodes.count { !it.isTerminal() }
    val retryCount = episodes.count {
        it.state == EpisodeDownloadEntity.STATE_FAILED ||
            it.state == EpisodeDownloadEntity.STATE_CANCELLED
    }
    // SPEC §20 Pause/Resume — counted from the SAME persisted rows the buttons
    // will act on, so a disabled control (count 0) is never a lie: SessionActions
    // re-derives the identical predicate when it runs.
    val pauseCount = episodes.count { it.isPausable() }
    val resumeCount = episodes.count { it.state == EpisodeDownloadEntity.STATE_PAUSED }
    return SessionRowUi(
        sessionId = session.sessionId,
        // Resume target: the anime session UUID `AnimeDetailsRoute` re-scans
        // (DECISIONS 94 — production rows carry it in both columns).
        resumeSession = session.animeSession,
        title = session.title,
        url = SessionRepository.resumeUrlOf(session),
        qualityLabel = "${session.quality}p",
        audioLang = session.audioLang,
        episodeCount = stats.episodeCount,
        completedEpisodes = stats.completedEpisodes,
        incompleteEpisodes = stats.incompleteEpisodes,
        completedSegments = stats.completedSegments,
        totalSegments = stats.totalSegments,
        segmentPercent = stats.segmentPercent,
        cacheBytes = cacheBytes,
        displayState = stats.state,
        cancelCount = cancelCount,
        retryCount = retryCount,
        pauseCount = pauseCount,
        resumeCount = resumeCount,
        deleteEnabled = allTerminal,
        deleteDisabledReason = if (allTerminal) null else SessionsViewModel.DELETE_DISABLED_REASON,
        tempCacheEnabled = allTerminal,
        tempCacheDisabledReason = if (allTerminal) {
            null
        } else {
            SessionsViewModel.TEMP_CACHE_DISABLED_REASON
        },
    )
}
