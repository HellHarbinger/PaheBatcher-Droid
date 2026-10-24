package com.pahebatcher.app.ui.watchlist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.data.network.NetworkMonitor
import com.pahebatcher.app.data.network.NetworkStatus
import com.pahebatcher.app.data.network.UnknownNetworkMonitor
import com.pahebatcher.app.data.remote.parser.AnimePaheParser
import com.pahebatcher.app.data.repository.WatchlistRepository
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.watchlist.RelinkCandidate
import com.pahebatcher.app.domain.watchlist.SEARCH_UNREACHABLE_MESSAGE
import com.pahebatcher.app.domain.watchlist.WatchlistCheckResult
import com.pahebatcher.app.domain.watchlist.WatchlistCheckStatus
import com.pahebatcher.app.domain.watchlist.WatchlistChecker
import com.pahebatcher.app.domain.watchlist.noSessionFoundMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 12C watchlist surface — the minimal list screen that makes the Python
 * `wl add` / `wl check` / `wl remove` behavior observable (a limited Phase 13
 * prerequisite, not a new design language).
 *
 * Every field is real persisted state ([WatchlistRepository] over the existing
 * Room table) or a real check result ([WatchlistChecker]):
 *
 * | field | source |
 * |---|---|
 * | entries | `watchlist` rows (Flow — UI is never authoritative, AGENTS §9) |
 * | results | the last [WatchlistCheckResult] per anime session |
 * | checking / runningSessionId | the foreground check loop's own progress |
 * | urlInput / addError / addedMessage | the offline `wl add` validation |
 *
 * Checks run in [viewModelScope] on the UI side (each result is published as it
 * lands, so the list shows real progress). The scan itself needs the
 * **application-scoped** WebView Cloudflare session, not this screen: since
 * Phase 12D the same [WatchlistChecker] also runs in the background
 * (`WatchlistCheckWorker`), which `WatchlistCheckScheduler` registers every 15
 * minutes from `Application.onCreate` — the narrow
 * Worker→transport boundary. This screen's `Check now` stays the manual
 * counterpart of Python's one-shot `wl check`.
 */
class WatchlistViewModel(
    private val watchlistRepository: WatchlistRepository,
    private val checker: WatchlistChecker,
    /**
     * Settings snapshotted onto a new entry — Python `wl add` copies the CURRENT
     * config onto the entry (`watchlist.py:630-665`) and the check then reads the
     * ENTRY, never a later global change (`watchlist.py:972-985`).
     *
     * Which of those snapshot values the check really consumes today:
     *
     * | snapshotted | consumed per entry? |
     * |---|---|
     * | `quality`, `audioLang`, `maxParallel` (staging gate), `autoRetry` (resolve attempts) | **yes** — `WatchlistChecker` reads them off the row |
     * | `outputDir`, `hlsWorkers`, `keepTemp` | no — snapshotted for schema parity with the reference; the download worker still reads the GLOBAL Settings for these (LIMITATIONS §33.8 records the divergence) |
     *
     * `resolveAhead` is deliberately NOT snapshotted: it is read globally for every
     * check, exactly like the reference (`watchlist.py:982`).
     */
    private val configProvider: suspend () -> AppContext = { AppContext.defaults() },
    /**
     * Application-scoped connectivity. Production
     * injects `AppContainer.networkMonitor`; the default [UnknownNetworkMonitor]
     * (UNKNOWN = online, 97.2) keeps every existing construction unchanged.
     */
    private val networkMonitor: NetworkMonitor = UnknownNetworkMonitor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    init {
        // Room is the source of truth; every check that relinks or re-checks an
        // entry re-emits and the list follows automatically.
        viewModelScope.launch {
            try {
                watchlistRepository.observe().collect { entries ->
                    _uiState.update { it.copy(entries = entries) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loadError = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't read the watchlist.") }
            }
        }
        // DECISIONS 97.3/97.4: offline indication + guard for the network-bound
        // actions (Check now, candidate search). `wl add` stays available — it is
        // validated OFFLINE by design, exactly like the reference.
        viewModelScope.launch {
            networkMonitor.networkStatus.collect { status ->
                _uiState.update { it.copy(offline = status == NetworkStatus.OFFLINE) }
            }
        }
    }

    /** [com.pahebatcher.app.ui.navigation.WatchlistScreen] text field. */
    fun onUrlChange(value: String) {
        _uiState.update { it.copy(urlInput = value, addError = null) }
    }

    /**
     * `wl add <URL>` (`watchlist.py:613-676`) — validated OFFLINE: scheme, host
     * and UUID are checked, the entry is stored with today's config, and its
     * title starts as the session placeholder (updated by the first check).
     * No network request is made, exactly like the reference.
     */
    fun addByUrl() {
        val raw = _uiState.value.urlInput.trim()
        if (raw.isEmpty()) {
            _uiState.update { it.copy(addError = "Paste an animepahe anime URL first.") }
            return
        }
        val parsed = try {
            AnimePaheParser.parseAnimeUrl(raw)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(addError = e.message ?: "Not a valid animepahe anime URL.") }
            return
        }
        val (host, session) = parsed

        viewModelScope.launch {
            val prefs = try {
                configProvider()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppContext.defaults()
            }
            val entry = WatchlistEntity(
                sessionId = session,
                url = "https://$host/anime/$session",
                animeSession = session,
                host = host,
                // Placeholder until the first check fills the real title, as in
                // the reference (`watchlist.py:645` — "will be updated on first
                // check"). addOrUpdate keeps an existing REAL title (repository
                // merge rule), so re-adding never downgrades it.
                title = session,
                quality = prefs.quality,
                // SPEC §22: new entries pin NO concrete audio — the check
                // resolves each number through the deterministic default
                // variant (legacy rows keep their stored value, honored strictly).
                audioLang = WatchlistEntity.AUDIO_ANY,
                outputDir = prefs.outputDir,
                maxParallel = prefs.maxParallel,
                hlsWorkers = prefs.hlsWorkers,
                keepTemp = prefs.keepTemp,
                autoRetry = prefs.autoRetry,
                addedAt = System.currentTimeMillis(),
                lastCheckedAt = 0L,
                downloadedEpisodesJson = "[]",
            )
            // UI-007: a Room write failure must surface an honest message like every
            // sibling ViewModel (Downloads/Sessions/AnimeDetails), not escape the
            // viewModelScope (which has no handler and would reach the thread's
            // default uncaught handler). `addError` is the field this screen already
            // renders as the add-failure text.
            val result = try {
                watchlistRepository.addOrUpdate(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        addError = e.message?.takeIf { m -> m.isNotBlank() }
                            ?: "Couldn't save that entry.",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    urlInput = "",
                    addError = null,
                    addedMessage = if (result.isNew) {
                        "Added — the first check fills its title."
                    } else {
                        "Already saved — preferences kept."
                    },
                )
            }
        }
    }

    /** `wl remove <id|session|url|title>` — [WatchlistRepository.remove] resolves it. */
    fun remove(identifier: String) {
        viewModelScope.launch {
            // UI-007: same failure path as add — an honest message rather than an
            // uncaught exception out of viewModelScope.
            val removed = try {
                watchlistRepository.remove(identifier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        removedMessage = e.message?.takeIf { m -> m.isNotBlank() }
                            ?: "Couldn't remove that entry.",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    results = it.results - (removed?.sessionId ?: identifier),
                    removedMessage = if (removed != null) "Removed." else "Nothing matched that entry.",
                )
            }
        }
    }

    /**
     * `wl check` (`watchlist.py:685-1118`): every entry, sequentially, each
     * result published as soon as its check finishes so the list shows real
     * progress instead of a spinner over stale state.
     *
     * Re-entrant: a second press while one run is active is ignored, so a check
     * can never run twice concurrently (AGENTS §12).
     */
    fun checkNow() {
        if (_uiState.value.checking) return
        // DECISIONS 97.4: a check is entirely network-bound — guarded while
        // OFFLINE (the banner states why); the screen also disables the control.
        if (_uiState.value.offline) return
        val entries = _uiState.value.entries
        if (entries.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(checking = true, addedMessage = null, removedMessage = null) }
            try {
                for (entry in entries) {
                    _uiState.update { state -> state.copy(runningSessionId = entry.sessionId) }
                    val result = try {
                        checker.check(entry)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // AQR-008: the checker's internal guards do not cover the
                        // DAO reads made before its own try block — a Room fault
                        // here must not escape into viewModelScope (a crash). The
                        // Python reference isolates one entry's failure from the
                        // run (`watchlist.py:773-782`): record an honest FAILED
                        // result and continue with the remaining entries.
                        WatchlistCheckResult(
                            sessionId = entry.sessionId,
                            title = entry.title,
                            status = WatchlistCheckStatus.FAILED,
                            message = "Check failed: ${e.message ?: "unexpected error"}",
                        )
                    }
                    _uiState.update { state ->
                        state.copy(results = state.results + (result.sessionId to result))
                    }
                }
            } finally {
                _uiState.update { it.copy(checking = false, runningSessionId = null) }
            }
        }
    }

    // -------------------------------------------------------------------
    // manual relink — Python `cli_relink` with an
    // identifier, as a dialog over the entry's stored title.
    // -------------------------------------------------------------------

    /**
     * 108.4: open the dialog and run the SHARED candidate search over the
     * entry's stored title ([WatchlistChecker.findCandidateRows] — one
     * implementation with the automatic path, never a second search).
     *
     * Guarded twice (97.4): while OFFLINE the search is network-bound like
     * `Check now` (silent no-op — the screen shows the banner and disables the
     * control), and only one dialog/search may exist at a time (AGENTS §12).
     * The search itself writes nothing — Cancel stays a zero-write outcome.
     */
    fun startRelink(entry: WatchlistEntity) {
        if (_uiState.value.relink != null) return
        if (_uiState.value.checking) return
        if (_uiState.value.offline) return
        // AQR-040: capture the EXACT dialog instance this search opens. The
        // completion below may run long after the user cancelled (cancel is
        // allowed while loading) and a second relink was started — those
        // results belong to THIS instance only, never to a dialog opened later.
        val opened = RelinkState(entry = entry, loading = true)
        _uiState.update { it.copy(relink = opened) }
        viewModelScope.launch {
            // AQR-008: same DAO-fault guard as `checkNow` — the dialog must not
            // be left spinning (or the exception crash the app) if the shared
            // search path faults; an escaped failure is the honest
            // unreachable-search state, never "no session found".
            val rows = try {
                checker.findCandidateRows(entry, entry.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _uiState.update { state ->
                val dialog = state.relink ?: return@update state // dismissed meanwhile
                // AQR-040: identity guard — a NEWER dialog (another entry's
                // relink, or a reopen after Cancel) must never receive rows
                // searched for `entry`; the first result used to overwrite it.
                if (dialog !== opened) return@update state
                state.copy(
                    relink = when {
                        // Unreachable search — an honest failure, never dressed
                        // up as "no session found" (108.6, shared wording).
                        rows == null -> dialog.copy(
                            loading = false,
                            message = SEARCH_UNREACHABLE_MESSAGE,
                        )
                        rows.isEmpty() -> dialog.copy(
                            loading = false,
                            message = noSessionFoundMessage(entry.title),
                        )
                        // 108.7: a single candidate is shown pre-selected.
                        else -> dialog.copy(
                            loading = false,
                            candidates = rows,
                            selectedIndex = if (rows.size == 1) 0 else -1,
                        )
                    },
                )
            }
        }
    }

    /** 108.8: the list selection — Confirm stays disabled until this lands. */
    fun selectRelinkCandidate(index: Int) {
        _uiState.update { state ->
            val dialog = state.relink ?: return@update state
            if (dialog.confirming || index !in dialog.candidates.indices) state
            else state.copy(relink = dialog.copy(selectedIndex = index))
        }
    }

    /** 108.9: Cancel/dismiss — ZERO writes; the entry is untouched. */
    fun dismissRelink() {
        _uiState.update { state ->
            if (state.relink?.confirming == true) state else state.copy(relink = null)
        }
    }

    /**
     * 108.9: persist ONLY the chosen candidate (session/host/url — title,
     * history and per-entry preferences preserved by [WatchlistRepository.relink]),
     * then 108.13: run the standard check on the new key immediately. The Room
     * observer re-emits the list on its own (108.12) — no list surgery.
     *
     * Room is the source of truth (AGENTS §9): the row is re-read right before
     * the write, exactly like `WatchlistChecker.checkEntry` — a dialog held
     * open across a background check (Phase 12D's 15-minute worker) must never
     * push its stale snapshot over newer history/title/prefs, and a row removed
     * meanwhile aborts with zero writes instead of resurrecting it.
     *
     * Dismissal is blocked while confirming, so this flow owns the dialog until
     * it ends; `finally` guarantees an unexpected fault can't leave it stuck
     * (same discipline as [checkNow]).
     */
    fun confirmRelink() {
        val dialog = _uiState.value.relink ?: return
        if (dialog.confirming) return
        val candidate = dialog.candidates.getOrNull(dialog.selectedIndex) ?: return
        val entry = dialog.entry
        _uiState.update { it.copy(relink = dialog.copy(confirming = true)) }
        viewModelScope.launch {
            try {
                val fresh = watchlistRepository.get(entry.sessionId)
                if (fresh == null) {
                    // Removed while the dialog was open — Room wins; no write.
                    _uiState.update { state -> state.copy(relink = null) }
                    return@launch
                }
                val rekeyed = watchlistRepository.relink(fresh, candidate.session, fresh.host)
                val result = checker.checkRelinked(rekeyed)
                _uiState.update { state ->
                    state.copy(
                        relink = null,
                        // The result map follows the re-keyed entry; the old
                        // key's stale result is dropped with it.
                        results = (state.results - entry.sessionId) +
                            (result.sessionId to result),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // UI-007: relink/check failures close the dialog and report an honest
                // message (removedMessage is the row-level message this screen has);
                // the finally below still runs to guarantee the dialog is cleared.
                _uiState.update { state ->
                    state.copy(
                        removedMessage = e.message?.takeIf { m -> m.isNotBlank() }
                            ?: "Couldn't relink that entry.",
                    )
                }
            } finally {
                _uiState.update { state ->
                    if (state.relink == null) state else state.copy(relink = null)
                }
            }
        }
    }
}

/**
 * Watchlist screen state — see [WatchlistViewModel] for the source of each
 * field. UI state is never authoritative (AGENTS §9): entries come from Room.
 */
@Immutable
data class WatchlistUiState(
    /** Watchlist rows, newest first (`WatchlistDao.getAll`). */
    val entries: List<WatchlistEntity> = emptyList(),
    /** True while a check run is active. */
    val checking: Boolean = false,
    /** Session the foreground check is currently evaluating. */
    val runningSessionId: String? = null,
    /** Last check result per anime session (empty = never checked here). */
    val results: Map<String, WatchlistCheckResult> = emptyMap(),
    /** Raw text of the `wl add` URL field. */
    val urlInput: String = "",
    /** Non-null only when an add was rejected (offline validation). */
    val addError: String? = null,
    /** Non-null after a successful add. */
    val addedMessage: String? = null,
    /** Non-null after a remove attempt. */
    val removedMessage: String? = null,
    /** Non-null only when the Room read itself failed. */
    val loadError: String? = null,
    /**
     * True while [NetworkStatus.OFFLINE] —
     * renders the offline banner and disables the network-bound controls.
     * `wl add` remains enabled (offline validation, like the reference).
     */
    val offline: Boolean = false,
    /**
     * The manual Relink dialog — `null` = closed.
     * Everything inside is transient UI state; the ONLY write in the flow is
     * the repository call on confirm (108.9).
     */
    val relink: RelinkState? = null,
)

/**
 * One open session of the Relink dialog.
 * Exactly one of the terminal shapes renders: `loading` (search in flight),
 * `message` (honest 0-candidate / unreachable outcome, 108.6), or `candidates`
 * (selectable list, 108.5). `confirming` blocks every mutation until the
 * persist + rescan flow ends (AGENTS §12).
 */
@Immutable
data class RelinkState(
    /** The entry being relinked — captured at dialog open. */
    val entry: WatchlistEntity,
    /** Shared candidate search in flight. */
    val loading: Boolean = false,
    /** Confirm pressed: repository relink + standard rescan in flight. */
    val confirming: Boolean = false,
    /** Candidate rows from the shared search (empty while loading/outcome). */
    val candidates: List<RelinkCandidate> = emptyList(),
    /** -1 until selected; `0` preselected when there is exactly one (108.7). */
    val selectedIndex: Int = -1,
    /** Honest outcome text for empty results / unreachable search (108.6). */
    val message: String? = null,
)
