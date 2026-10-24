package com.pahebatcher.app.ui.sessionprompt

import androidx.compose.runtime.Immutable
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.SessionRefreshSignal
import com.pahebatcher.app.logging.AppLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SPEC §6.10 (final-audit H1) — the state the root-level session prompt renders.
 *
 * @property visible whether the prompt is on screen (set by a
 *   [SessionRefreshSignal] request, cleared by a successful refresh or a
 *   dismissal).
 * @property refreshing true while the user's `Refresh session` action is
 *   running — the confirm button is disabled meanwhile so a double tap cannot
 *   start two bootstraps.
 * @property error human-readable failure of the LAST refresh attempt; `null`
 *   when none. Shown inside the still-visible prompt (SPEC §26 — a failed
 *   refresh is never swallowed).
 */
@Immutable
data class SessionRefreshPromptState(
    val visible: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Root prompt ViewModel for SPEC §6.10: "Prompt the user to refresh the
 * WebView session instead of silently failing."
 *
 * - Observes the process-wide [SessionRefreshSignal] raised by the detection
 *   sites (bridge / `fetchPage`), so the data layer never touches the UI
 *   (AGENTS §4).
 * - **One quiet attempt, then prompt** (SPEC §6.10 refinement): the first
 *   recovery requirement after the session went bad runs [forceRefresh]
 *   silently; a success shows NOTHING, a failure raises this dialog and sets a
 *   latch so every later requirement shows the dialog immediately instead of
 *   quietly retrying. See [silentAttemptFailed].
 * - The refresh action runs the REAL force-refresh supplied by the composition
 *   root ([com.pahebatcher.app.di.AppContainer.forceRefreshWebViewSession]
 *   through [com.pahebatcher.app.di.AppViewModelFactory]) — this class never
 *   builds a WebView or holds a session reference itself.
 * - The prompt survives configuration changes (Activity-scoped ViewModel) and
 *   is dropped with the Activity, never persisted: a session prompt is only
 *   meaningful while the app is on screen.
 */
class SessionRefreshViewModel(
    private val forceRefresh: suspend () -> CloudflareSessionProvider.SessionInfo,
) : ViewModel() {

    companion object {
        private const val TAG = "SessionRefreshVM"
    }

    private val _state = MutableStateFlow(SessionRefreshPromptState())
    val state: StateFlow<SessionRefreshPromptState> = _state.asStateFlow()

    /**
     * Recovery latch — "one quiet attempt, then prompt".
     *
     * `false` (initial): the NEXT time session recovery is required this
     * ViewModel makes ONE background attempt using the existing supported
     * mechanism ([forceRefresh], i.e. `AppContainer.forceRefreshWebViewSession`
     * → `establishSession(forceRefresh = true)`). Success raises NO prompt, so
     * a user whose background refresh keeps working is never interrupted.
     *
     * `true`: a quiet attempt has already failed for this recovery episode, so
     * every later requirement surfaces the user-facing UI immediately — the app
     * neither retries silently forever nor hides a persistent session failure.
     *
     * Held by this Activity-scoped ViewModel on purpose (the product decision
     * for this slice): it survives recomposition and Activity recreation, so a
     * configuration change does not reset the first-attempt state, and it dies
     * with the Activity because a session prompt is only meaningful while the
     * app is on screen (see the class KDoc).
     */
    private var silentAttemptFailed = false

    /**
     * Coalescing guard for [onRecoveryRequired]: while one quiet attempt is
     * running, further requests that need the same recovery must NOT start a
     * second refresh or a second prompt (SPEC §6.10 under concurrency). The
     * signal is a `SharedFlow` collected by this single coroutine, so attempts
     * are already serialized — this guard simply drops the ones raised during
     * an attempt that is still settling.
     */
    private var silentAttemptInFlight = false

    init {
        viewModelScope.launch {
            // No replay on the signal: only a prompt raised while THIS prompt
            // surface is alive shows; the next failing operation raises again.
            SessionRefreshSignal.requests.collect { reason ->
                onRecoveryRequired(reason)
            }
        }
    }

    /**
     * Session recovery is required — decide between the QUIET attempt and the
     * user-facing prompt.
     *
     * Order matters:
     *  1. latch already set → prompt at once (the quiet attempt has failed
     *     before, so trying again silently would just defer an interruption the
     *     user needs to see);
     *  2. an attempt is already running → drop this signal (concurrent requests
     *     share ONE refresh);
     *  3. otherwise make the single quiet attempt, and only surface the prompt
     *     if it fails.
     *
     * [reason] is a URL-free label from the detection site and is used only for
     * deciding that the prompt is warranted — it is never rendered and never
     * logged here (AGENTS §10).
     */
    private fun onRecoveryRequired(reason: String) {
        if (silentAttemptFailed) {
            showPromptPreservingVisibleError()
            return
        }
        if (silentAttemptInFlight) return
        silentAttemptInFlight = true
        viewModelScope.launch {
            try {
                val info = forceRefresh()
                if (info.isReady) {
                    // Quiet success: no prompt, and the latch stays CLEAR so a
                    // genuinely later staleness earns its own single quiet
                    // attempt instead of jumping straight to the dialog.
                    AppLog.i(TAG, "Session recovered by the background refresh")
                } else {
                    silentAttemptFailed = true
                    showPromptPreservingVisibleError()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                silentAttemptFailed = true
                // AGENTS §10: an exception message may quote a signed URL, so
                // only the exception TYPE is logged — never the message.
                AppLog.w(TAG, "Background session refresh failed: ${e.javaClass.simpleName}")
                showPromptPreservingVisibleError()
            } finally {
                silentAttemptInFlight = false
            }
        }
    }

    /**
     * Surface the prompt.
     *
     * Re-raising an ALREADY visible prompt is a no-op: it must not wipe the
     * honest failure reason the user is currently reading (SPEC §26 — a failed
     * refresh is never swallowed).
     */
    private fun showPromptPreservingVisibleError() {
        _state.update { current ->
            if (current.visible) current else current.copy(visible = true, error = null)
        }
    }

    /**
     * The dialog's `Refresh session` action: run the REAL force-refresh.
     *
     * Success hides the prompt (the session is ready again); a refused or
     * throwing refresh keeps the prompt open with an honest reason — never a
     * silent close after a failure (SPEC §26).
     */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            try {
                val info = forceRefresh()
                if (info.isReady) {
                    // A user-confirmed recovery clears the latch: the session is
                    // healthy again, so a later staleness earns its own single
                    // quiet attempt before the dialog returns.
                    silentAttemptFailed = false
                    _state.value = SessionRefreshPromptState()
                    AppLog.i(TAG, "Session refreshed on user request")
                } else {
                    // Still broken after the user's own attempt → keep the latch
                    // set so the next required recovery shows the UI at once
                    // rather than quietly trying a method that just failed.
                    silentAttemptFailed = true
                    _state.update {
                        it.copy(refreshing = false, error = "Refresh failed: ${info.state}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                silentAttemptFailed = true
                // AGENTS §10: log the exception TYPE only — a message may quote
                // a signed stream URL, which must never reach a log.
                AppLog.w(TAG, "Session refresh failed: ${e.javaClass.simpleName}")
                // Same AGENTS §10 rule as the Downloads banner: no URL may reach
                // the UI — a URL-bearing message falls back to the type.
                val detail = e.message?.takeIf { "://" !in it } ?: e.javaClass.simpleName
                _state.update { it.copy(refreshing = false, error = "Refresh failed: $detail") }
            }
        }
    }

    /** The dialog's `Not now` action: hide the prompt (a later failure re-raises it). */
    fun dismiss() {
        _state.update { it.copy(visible = false) }
    }
}
