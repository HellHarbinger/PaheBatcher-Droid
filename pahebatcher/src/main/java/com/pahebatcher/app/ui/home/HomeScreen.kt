package com.pahebatcher.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pahebatcher.app.ui.common.OfflineBanner
import com.pahebatcher.app.ui.mapper.PosterUiState
import com.pahebatcher.app.ui.mapper.SearchResultUi

/**
 * Phase 11 §12/§14/§15: Home screen.
 *
 * §12 required content IN ORDER: (1) the single-sentence tagline, (2) the M3
 * search field wired to the §14 foundation, (3) the real storage location
 * (`Movies/<anime title>/`, Gate A per-anime directory), (4) the honest
 * empty-Downloads guidance that
 * navigates to the downloads tab. Nothing else — no cards, no recommendations,
 * no invented rows.
 *
 * Stateless (AGENTS §4): receives [SearchUiState] + lambdas only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    searchState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onRetrySearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    /** Anime details destination: receives the tapped row's session UUID (11.5E-2). */
    onOpenDetails: (String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Watchlist destination pushed from the top bar (§10.2 — never a
     * bottom tab). Defaults to a no-op so existing fixtures compile; production
     * wiring lives in [com.pahebatcher.app.ui.navigation.AppNavHost].
     */
    onOpenWatchlist: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val error = searchState.error
    val results = searchState.results

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_root")
            // Long-press entry to ValidationScreen (§10.3) — the BuildConfig.DEBUG
            // gate lives at the MainActivity handler (§31).
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        TopAppBar(
            title = { Text("PaheBatcher") },
            actions = {
                // the watchlist entry — pushed as its own route from
                // the top bar, so the documented 2-tab bottom bar is untouched.
                IconButton(
                    onClick = onOpenWatchlist,
                    modifier = Modifier.testTag("home_watchlist"),
                ) {
                    Icon(Icons.Filled.List, contentDescription = "Watchlist")
                }
                // §10.2: Settings is pushed from THIS gear — never a bottom tab.
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )

        // Home/Search is network-dependent —
        // the honest offline indication, driven purely by ViewModel UiState.
        if (searchState.offline) {
            OfflineBanner()
        }

        // §12.1 — one honest sentence explaining what the app does.
        Text(
            text = "Search animepahe, pick episodes, download and watch them.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // §12.2 — search field (§14: typing debounces via the ViewModel; the IME
        // Search action submits through the SAME path).
        OutlinedTextField(
            value = searchState.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("search_field"),
            placeholder = { Text("Search anime titles…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchState.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
        )

        // §12.3 — where completed videos really go (Gate A: one directory per
        // anime title beneath Movies/, Python output parity).
        Text(
            text = "Videos are saved to Movies/<anime title>/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> ErrorPanel(message = error, onRetry = onRetrySearch)

                searchState.loading -> CircularProgressIndicator()

                results.isEmpty() && searchState.query.isNotBlank() ->
                    Text(HomeViewModel.NO_RESULTS, style = MaterialTheme.typography.bodyLarge)

                // First use (blank query): nothing but the §12 composition.
                results.isEmpty() -> Unit

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.session }) { result ->
                        SearchResultRow(
                            result = result,
                            onClick = {
                                // §14.1: tapping a result clears focus (hiding the IME).
                                // 11.5E-2: the row's session opens the anime details
                                // destination (ID-only argument, AppRoutes §10.3).
                                focusManager.clearFocus()
                                onOpenDetails(result.session)
                            },
                        )
                    }
                }
            }
        }

        // §12.4 — honest empty-downloads guidance; tapping opens the downloads tab.
        TextButton(
            onClick = {
                focusManager.clearFocus()
                onOpenDownloads()
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("empty_downloads_guidance"),
        ) {
            Text(
                text = "No downloads yet — downloads you start will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shared honest error branch — message + Retry bound to the caller's real failing
 * call. `internal` so the anime details destination reuses exactly this panel
 * rather than a second copy of it (11.5E-2).
 */
@Composable
internal fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(24.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text("Retry", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(result: SearchResultUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Deterministic test seam: the row's title text is NOT unique (the
            // search FIELD holds the same query text), so a tap target that is
            // only ever a result row keeps UI tests from hitting the field.
            .testTag("search_result_row")
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterSlot(result = result)
        val meta = listOfNotNull(
            result.type,
            result.year?.toString(),
            result.episodes?.let { "$it eps" },
            result.score,
        ).joinToString(" · ")
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * §15 poster slot — rendering keyed on the explicit four-state model:
 *
 * - [PosterUiState.Placeholder] (state 4 — the ONLY state Phase 11 produces,
 *   §15 rule 2): title-initial monogram with the §21.1 `"<title> poster"`
 *   content description.
 * - States 1–3 require the future image loader (§15 rule 3 — no Coil/Glide in
 *   Phase 11, §32). Until a verified URL source AND that loader exist, even a
 *   theoretical non-placeholder state renders the honest state-4 slot over the
 *   real title rather than a fake image.
 */
@Composable
private fun PosterSlot(result: SearchResultUi, modifier: Modifier = Modifier) {
    val posterTitle = when (val poster = result.poster) {
        is PosterUiState.Placeholder -> poster.title
        is PosterUiState.Available -> result.title
        is PosterUiState.Loading -> result.title
        is PosterUiState.Failed -> result.title
    }
    val monogram = posterTitle.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 66.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            )
            // §21.1: the slot announces real data; the monogram glyph itself is not
            // separately spoken (clearAndSetSemantics replaces subtree semantics).
            .clearAndSetSemantics { contentDescription = "$posterTitle poster" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogram,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
