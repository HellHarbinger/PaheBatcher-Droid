package com.pahebatcher.app.ui.watchlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.domain.watchlist.WatchlistCheckResult
import com.pahebatcher.app.domain.watchlist.WatchlistCheckStatus
import com.pahebatcher.app.ui.common.OfflineBanner

/**
 * The watchlist list screen (minimal Phase 13 prerequisite surface).
 *
 * Stateless (AGENTS §4): [WatchlistUiState] in, lambdas out; every visible line
 * is either a persisted row or a real check result. It renders the reference's
 * `wl` behaviors the user can reach:
 *
 * * **Add** — offline URL validation + persisted entry (`wl add`);
 * * **Check now** — one foreground run over every entry (`wl check`), showing
 *   each entry's result as it lands: queued new episodes, up-to-date, deleted
 *   episodes skipped, relinked/ambiguous/dead link, or a scan failure;
 * * **Remove** — `wl remove`.
 * * **Relink** (Phase 13, DECISIONS 108) — a per-row manual relink over the
 *   shared candidate search (`cli_relink` with an identifier): candidate
 *   dialog, confirm → persist + standard rescan on the new key.
 *
 * Nothing here fetches, resolves or downloads: the screen only calls the
 * ViewModel, which calls the use case (AGENTS §4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    uiState: WatchlistUiState,
    onBack: () -> Unit,
    onUrlChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onCheckNow: () -> Unit,
    // the manual relink flow — open/search, list
    // selection, confirm (persist + standard rescan), dismiss (zero writes).
    onRelink: (WatchlistEntity) -> Unit,
    onSelectRelinkCandidate: (Int) -> Unit,
    onConfirmRelink: () -> Unit,
    onDismissRelink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag("watchlist_root")) {
        TopAppBar(
            title = { Text("Watchlist") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                // `wl check` — one run, gated by the ViewModel so it can never
                // run twice concurrently.
                IconButton(
                    onClick = onCheckNow,
                    enabled = !uiState.checking && uiState.entries.isNotEmpty() && !uiState.offline,
                    modifier = Modifier.testTag("watchlist_check_now"),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Check now for new episodes")
                }
            },
        )

        // the Watchlist's network-bound
        // controls (Check now) are guarded while OFFLINE — `wl add` stays
        // enabled because it validates offline, exactly like the reference.
        if (uiState.offline) {
            OfflineBanner()
        }

        // --- `wl add` -------------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = uiState.urlInput,
                onValueChange = onUrlChange,
                singleLine = true,
                label = { Text(text = "Anime URL") },
                isError = uiState.addError != null,
                supportingText = uiState.addError?.let { { Text(text = it) } },
                enabled = !uiState.checking,
                modifier = Modifier.weight(1f).testTag("watchlist_url_field"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onAdd,
                enabled = !uiState.checking,
                modifier = Modifier.testTag("watchlist_add"),
            ) { Text(text = "Add") }
        }

        uiState.addedMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("watchlist_add_message"),
            )
        }
        uiState.removedMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("watchlist_remove_message"),
            )
        }
        uiState.loadError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("watchlist_load_error"),
            )
        }

        if (uiState.checking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("watchlist_checking"),
                )
            }
        }

        if (uiState.entries.isEmpty()) {
            // Honest empty state — the same style as the §12 guidance text.
            Text(
                text = "Nothing saved yet. Add an animepahe anime URL to follow new episodes.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp).testTag("watchlist_empty"),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.entries, key = { it.sessionId }) { entry ->
                    WatchlistRow(
                        entry = entry,
                        result = uiState.results[entry.sessionId],
                        checking = uiState.checking,
                        running = uiState.runningSessionId == entry.sessionId,
                        // 108.2: EVERY entry — no status gating — but the
                        // network-bound search is guarded like `Check now` (97.4).
                        relinkEnabled = !uiState.checking && !uiState.offline,
                        onRelink = { onRelink(entry) },
                        onRemove = { onRemove(entry.sessionId) },
                    )
                }
            }
        }
    }

    // the candidate dialog sits above the screen —
    // one at a time, rendered only while the ViewModel holds an open session.
    uiState.relink?.let { state ->
        RelinkDialog(
            state = state,
            onSelect = onSelectRelinkCandidate,
            onConfirm = onConfirmRelink,
            onDismiss = onDismissRelink,
        )
    }
}

/**
 * One saved anime. The `result` line is the last [WatchlistCheckResult] for
 * that session; while it is being checked, a small indicator plus its session
 * show which entry the run is on (real progress, not an animation).
 */
@Composable
private fun WatchlistRow(
    entry: WatchlistEntity,
    result: WatchlistCheckResult?,
    checking: Boolean,
    running: Boolean,
    relinkEnabled: Boolean,
    onRelink: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A placeholder title is the session UUID (`wl add` before the first check).
    val title = displayEntryTitle(entry)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("watchlist_row"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                text = "${entry.quality}p · ${entry.audioLang} · retry ${entry.autoRetry} · " +
                    "parallel ${entry.maxParallel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.lastCheckedAt > 0L) {
                Text(
                    text = "Checked ${formatCheckedAt(entry.lastCheckedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            result?.let { value ->
                val failed = value.status != WatchlistCheckStatus.UP_TO_DATE &&
                    value.status != WatchlistCheckStatus.NEW_EPISODES
                Text(
                    text = describeResult(value),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp).testTag("watchlist_row_result"),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Row(modifier = Modifier.padding(top = 8.dp)) {
                // manual relink on EVERY row —
                // present regardless of the entry's status, guarded exactly
                // like `Check now` (97.4 network, AGENTS §12 re-entrancy).
                OutlinedButton(
                    onClick = onRelink,
                    enabled = relinkEnabled,
                    modifier = Modifier.weight(1f).testTag("watchlist_row_relink"),
                ) { Text(text = "Relink") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !checking,
                    modifier = Modifier.weight(1f).testTag("watchlist_row_remove"),
                ) { Text(text = "Remove") }
            }
        }
    }
}

/**
 * The manual Relink dialog.
 *
 * - **loading** (108.5): the shared candidate search in flight;
 * - **outcome** (108.6): the exact wording the automatic path uses for
 *   "no session found" / "search unreachable" — one source, never paraphrased;
 * - **candidates** (108.5): selectable list carrying the search row's metadata
 *   (title · type · year · eps · score) plus the shortened session;
 * - a single candidate is pre-selected (108.7), Confirm stays disabled until a
 *   selection exists (108.8), and Current → New session ids frame the change
 *   (108.9, shortened);
 * - Cancel/dismiss writes nothing (108.9) and is blocked only while the
 *   confirm flow is in flight (AGENTS §12).
 */
@Composable
private fun RelinkDialog(
    state: RelinkState,
    onSelect: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = state.candidates.getOrNull(state.selectedIndex)
    AlertDialog(
        onDismissRequest = { if (!state.confirming) onDismiss() },
        title = { Text(text = "Relink \"${displayEntryTitle(state.entry)}\"") },
        text = {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Searching for sessions…",
                        modifier = Modifier.testTag("relink_searching"),
                    )
                }

                state.message != null -> Text(
                    text = state.message,
                    modifier = Modifier.testTag("relink_outcome"),
                )

                else -> Column {
                    // 108.9: the identity being changed, both ends shortened.
                    Text(
                        text = "Current: ${shortenSession(state.entry.animeSession)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (selected != null) {
                        Text(
                            text = "New: ${shortenSession(selected.session)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("relink_new_session"),
                        )
                    }
                    state.candidates.forEachIndexed { index, candidate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = index == state.selectedIndex,
                                    enabled = !state.confirming,
                                    onClick = { onSelect(index) },
                                    role = Role.RadioButton,
                                )
                                .testTag("relink_candidate"),
                        ) {
                            RadioButton(
                                selected = index == state.selectedIndex,
                                onClick = null,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.title,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                // The same metadata string family as
                                // SearchResultRow — fields the parser really
                                // produced, never fabricated (108.5).
                                val meta = listOfNotNull(
                                    candidate.type,
                                    candidate.year?.toString(),
                                    candidate.episodes?.let { "$it eps" },
                                    candidate.score,
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = shortenSession(candidate.session),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (state.confirming) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Rechecking the new link…",
                                modifier = Modifier.testTag("relink_confirming"),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.message == null && !state.loading) {
                TextButton(
                    onClick = onConfirm,
                    // 108.8: disabled until a candidate is selected; dead again
                    // once the confirm flow is in flight (AGENTS §12).
                    enabled = state.selectedIndex >= 0 && !state.confirming,
                    modifier = Modifier.testTag("relink_confirm"),
                ) { Text(text = "Confirm") }
            }
        },
        dismissButton = {
            if (!state.confirming) {
                TextButton(
                    onClick = onDismiss, // 108.9: zero writes
                    modifier = Modifier.testTag("relink_cancel"),
                ) { Text(text = if (state.message != null) "Close" else "Cancel") }
            }
        },
    )
}

/**
 * The row/dialog identity: a stored title once the first check filled it, else
 * the session UUID placeholder (`wl add` before the first check) — the exact
 * expression the list row has always used, shared with the Relink dialog so
 * both name the identical entry (DECISIONS 108.9).
 */
internal fun displayEntryTitle(entry: WatchlistEntity): String =
    if (entry.title.isBlank() || entry.title == entry.animeSession) {
        entry.animeSession
    } else {
        entry.title
    }

/**
 * A session UUID shortened for display — enough of
 * the id to disambiguate in a dialog line, never all 36 characters.
 */
internal fun shortenSession(session: String): String =
    if (session.length <= 8) session else session.take(8) + "…"

/** One honest sentence per check result — never a generic success/failure. */
internal fun describeResult(result: WatchlistCheckResult): String = when (result.status) {
    WatchlistCheckStatus.UP_TO_DATE,
    WatchlistCheckStatus.NEW_EPISODES,
    -> result.message ?: "Checked."

    WatchlistCheckStatus.LINK_DEAD,
    WatchlistCheckStatus.UNKNOWN_TITLE,
    WatchlistCheckStatus.AMBIGUOUS,
    WatchlistCheckStatus.FAILED,
    -> result.message ?: "Check failed."
}

/**
 * `lastCheckedAt` is a wall-clock epoch; render it relative so the row does not
 * age into a misleading bare timestamp.
 */
private fun formatCheckedAt(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    return when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L} min ago"
        delta < 86_400_000L -> "${delta / 3_600_000L} h ago"
        else -> "${delta / 86_400_000L} d ago"
    }
}
