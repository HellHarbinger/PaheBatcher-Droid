package com.pahebatcher.app.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pahebatcher.app.data.network.NetworkMonitor
import com.pahebatcher.app.data.network.NetworkStatus
import com.pahebatcher.app.data.network.UnknownNetworkMonitor
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.SearchOutcome
import com.pahebatcher.app.ui.mapper.SearchResultMapper
import com.pahebatcher.app.ui.mapper.SearchResultUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable search UiState for Home (PHASE11_ARCHITECTURE_V2 §13/§14).
 *
 * Branch semantics:
 * - First use: `query` blank, `loading = false`, no results, no error → the §12
 *   first-use composition renders with NO result/empty/error content.
 * - Results appear only when the real repository returned them (never invented).
 * - `error != null` → honest message + Retry bound to the real failing call (§35).
 */
@Immutable
data class SearchUiState(
    val query: String = "",
    /** True while a debounced or executing search is pending — covers the debounce window. */
    val loading: Boolean = false,
    val results: List<SearchResultUi> = emptyList(),
    val error: String? = null,
    /**
     * True while the network-dependent
     * surface is [com.pahebatcher.app.data.network.NetworkStatus.OFFLINE] —
     * renders the offline banner and guards [HomeViewModel.executeSearch].
     * `false` for UNKNOWN/ONLINE (97.2: never a false offline claim).
     */
    val offline: Boolean = false,
)

/**
 * Home search foundation (PHASE11_ARCHITECTURE_V2 §14, §14.1).
 *
 * - Debounce [debounceMs] (production [DEBOUNCE_MS] = 350 ms — inside the mandated 300–400 ms band) on typing.
 * - Exactly ONE in-flight search job: a new query (or IME submit) cancels the
 *   previous job, including a still-debouncing pending job and a suspended
 *   repository call (§14 "cancels superseded queries").
 * - IME Search uses the SAME execution path as typed input: [submitSearch] cancels
 *   the pending debounce and executes the current query immediately — one code
 *   path, not a second one (§14.1).
 * - Blank input clears state and never calls the repository.
 * - All-hosts-unreachable surfaces an error branch; a reachable host with zero
 *   results surfaces as an honest empty result set ("No results found.").
 */
class HomeViewModel(
    private val animeRepository: AnimeRepository,
    /**
     * Debounce window used by [onQueryChange]. Every production construction
     * (including [com.pahebatcher.app.di.AppViewModelFactory]) uses the default,
     * which is [DEBOUNCE_MS] — the production value and behavior are unchanged.
     * Instrumentation injects a larger window only where the test harness's
     * IME-dispatch latency would otherwise race the production value
     * (SearchImeTest §36-10); the 350 ms clock contract itself is pinned by
     * HomeViewModelTest's fake-clock tests.
     */
    private val debounceMs: Long = DEBOUNCE_MS,
    /**
     * The application-scoped connectivity source.
     * Production (`AppViewModelFactory`) injects `AppContainer.networkMonitor`;
     * the default [UnknownNetworkMonitor] is UNKNOWN = treated online (97.2),
     * i.e. the exact pre-Phase-13 behavior for every existing construction.
     */
    private val networkMonitor: NetworkMonitor = UnknownNetworkMonitor,
) : ViewModel() {

    companion object {
        /** §14 debounce band is 300–400 ms; 350 ms is the Phase 11 value. */
        const val DEBOUNCE_MS = 350L

        /** Honest error copy when no search host responded at all (§35). */
        const val ERROR_UNREACHABLE = "Couldn't reach animepahe. Check your connection."

        /** Empty-branch copy shown when a search completed with zero results (§35). */
        const val NO_RESULTS = "No results found."

        /**
         * The honest refusal while OFFLINE —
         * the search is never issued and never faked; Retry re-runs the real call
         * once connectivity is back.
         */
        const val OFFLINE_ERROR = "You're offline — search needs a connection."
    }

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    init {
        // DECISIONS 97.3/97.4: connectivity lives in UiState; the banner appears
        // and clears through this event-driven Flow (no polling, no timers) and
        // executeSearch guards on the same flag.
        viewModelScope.launch {
            networkMonitor.networkStatus.collect { status ->
                _searchState.update { it.copy(offline = status == NetworkStatus.OFFLINE) }
            }
        }
    }

    /** The single in-flight job — debounce delay and repository call share it. */
    private var searchJob: Job? = null

    /**
     * Typing event: replaces the whole state (stale results for a previous query
     * are cleared so mismatched rows are never shown), then schedules a debounced
     * search. Blank input clears without scheduling.
     */
    fun onQueryChange(query: String) {
        _searchState.update {
            // Full-state rebuilds (a new query drops stale results) must carry
            // the connectivity flag — it is observed, never typed (97.3).
            SearchUiState(
                query = query,
                loading = query.isNotBlank(),
                offline = it.offline,
            )
        }
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(debounceMs)
            executeSearch(query)
        }
    }

    /**
     * IME Search action / Retry: flushes the CURRENT query immediately through the
     * same [executeSearch] path (§14.1) by cancelling any pending debounce or
     * in-flight job first — the pending query is flushed, never double-fired.
     */
    fun submitSearch() {
        val query = _searchState.value.query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchState.update { SearchUiState(query = query, offline = it.offline) }
            return
        }
        searchJob = viewModelScope.launch {
            executeSearch(query)
        }
    }

    private suspend fun executeSearch(query: String) {
        // DECISIONS 97.4 / SPEC §27: while OFFLINE the request is refused with an
        // honest message — never issued, never faked. UNKNOWN/ONLINE run the real
        // call (97.2: no offline claim before the platform reported one).
        if (_searchState.value.offline) {
            _searchState.update { it.copy(loading = false, error = OFFLINE_ERROR, results = emptyList()) }
            return
        }
        _searchState.update { it.copy(loading = true, error = null) }
        try {
            when (val outcome = animeRepository.searchWithOutcome(AnimeRepository.currentHost, query)) {
                is SearchOutcome.Results -> _searchState.update {
                    it.copy(
                        loading = false,
                        error = null,
                        results = SearchResultMapper.mapAll(outcome.results),
                    )
                }

                SearchOutcome.Unreachable -> _searchState.update {
                    it.copy(loading = false, error = ERROR_UNREACHABLE, results = emptyList())
                }
            }
        } catch (e: CancellationException) {
            // A superseded job must die, not fall through to the error branch
            // below and clobber the newer query's state (§14 single in-flight).
            throw e
        } catch (e: Exception) {
            _searchState.update {
                it.copy(
                    loading = false,
                    error = e.message?.takeIf { msg -> msg.isNotBlank() } ?: "Search failed.",
                    results = emptyList(),
                )
            }
        }
    }
}
