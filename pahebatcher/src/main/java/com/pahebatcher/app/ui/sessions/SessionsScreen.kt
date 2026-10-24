package com.pahebatcher.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pahebatcher.app.domain.util.StringUtils

/**
 * The Sessions screen.
 *
 * Display list exactly per SPEC §20: title, URL, quality, audio language,
 * episode count, download progress, completed/incomplete episodes, cache
 * size (temporary artifacts only) and the derived state (DECISIONS 109).
 *
 * Actions exactly per SPEC §20: **Resume** (navigates to the download flow — the
 * composable only reports the session id; DECISIONS 94), **Pause**, **Cancel**,
 * **Retry**, **Delete session**, **Delete temporary cache**, **Clear all cache**,
 * plus **Resume all** which appears only while the session holds `PAUSED` rows
 * and puts them back to work.
 *
 * Pause/Resume are the final SPEC gap closure. DECISIONS 94.2 said "there is
 * intentionally no Pause control" — that described the PYTHON reference, which
 * has none and prints `Paused` as a literal (`sessions.py:75`); SPEC §20 lists
 * Pause among the required session actions and SPEC §25 requires a `paused`
 * state, so Android implements a real one: `DownloadRepository.pause()` writes
 * the durable `PAUSED` intent before the unique Work is cancelled (WorkManager
 * has no pause primitive — see DECISIONS 94.2's supersession note).
 *
 * Terminal-only gates (DECISIONS 106) render as DISABLED actions WITH their
 * reason shown — the state is never hidden. Confirmations name what they
 * remove; exported media is never in scope (SPEC §20).
 *
 * Layout uses fill/weight/wrapping only — no hard-coded phone widths
 * (DECISIONS 111) — and every action has a text label for accessibility.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionsScreen(
    uiState: SessionsUiState,
    onBack: () -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteTempCache: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onRequestClearCache: () -> Unit,
    onConfirmClearCache: () -> Unit,
    onDismissClearCache: () -> Unit,
    modifier: Modifier = Modifier,
    // SPEC §20 Pause / Resume-all (final gap closure). Production wires both;
    // the defaults keep render-only tests free of irrelevant plumbing.
    onPause: (String) -> Unit = {},
    onResumeDownloads: (String) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().testTag("sessions_root")) {
        TopAppBar(
            title = { Text("Sessions") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        when {
            uiState.isLoading && uiState.rows.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.rows.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No sessions yet — sessions you start will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "summary") {
                    CacheSummaryCard(
                        globalCacheBytes = uiState.globalCacheBytes,
                        clearAllEnabled = uiState.clearAllEnabled && uiState.busySessionId == null,
                        disabledReason = uiState.clearAllDisabledReason,
                        onRequestClearCache = onRequestClearCache,
                    )
                }
                items(uiState.rows, key = { it.sessionId }) { row ->
                    SessionCard(
                        row = row,
                        actionsEnabled = uiState.busySessionId == null,
                        onResume = onResume,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onDelete = onDelete,
                        onDeleteTempCache = onDeleteTempCache,
                        onPause = onPause,
                        onResumeDownloads = onResumeDownloads,
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        uiState.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("sessions_message")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    uiState.deleteDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            modifier = Modifier.testTag("sessions_delete_dialog"),
            title = { Text("Delete session \"${dialog.title}\"?") },
            text = {
                Column {
                    Text(
                        "Removes temporary download cache: " +
                            StringUtils.fmtBytes(dialog.temporaryBytes),
                    )
                    if (dialog.unexportedNames.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Not yet exported — these video files will be " +
                                "deleted too:",
                            fontWeight = FontWeight.Bold,
                        )
                        dialog.unexportedNames.forEach { name ->
                            Text(text = "• $name")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Exported videos in your library are never touched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmDelete,
                    modifier = Modifier.testTag("sessions_confirm_delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissDelete,
                    modifier = Modifier.testTag("sessions_cancel_delete"),
                ) { Text("Cancel") }
            },
        )
    }

    uiState.clearCacheDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = onDismissClearCache,
            modifier = Modifier.testTag("sessions_clear_dialog"),
            title = { Text("Clear all cache?") },
            text = {
                Text(
                    "Removes ${StringUtils.fmtBytes(dialog.totalBytes)} of temporary " +
                        "download files. Sessions and completed videos are kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmClearCache,
                    modifier = Modifier.testTag("sessions_confirm_clear"),
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissClearCache,
                    modifier = Modifier.testTag("sessions_cancel_clear"),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CacheSummaryCard(
    globalCacheBytes: Long,
    clearAllEnabled: Boolean,
    disabledReason: String?,
    onRequestClearCache: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Total cache size",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = StringUtils.fmtBytes(globalCacheBytes),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("sessions_global_cache"),
                    )
                }
                Button(
                    onClick = onRequestClearCache,
                    enabled = clearAllEnabled,
                    modifier = Modifier.testTag("sessions_clear_all"),
                ) { Text("Clear all cache") }
            }
            if (disabledReason != null) {
                Text(
                    text = disabledReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    row: SessionRowUi,
    actionsEnabled: Boolean,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteTempCache: (String) -> Unit,
    onPause: (String) -> Unit,
    onResumeDownloads: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("sessions_row"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("sessions_row_title"),
            )
            Text(
                text = row.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.qualityLabel} · ${row.audioLang}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${row.episodeCount} episodes · " +
                    "${row.completedEpisodes} completed · " +
                    "${row.incompleteEpisodes} incomplete",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            // §20 download progress: determinate only when the persisted
            // denominator exists (§16.1 — never a fabricated 0%).
            val percent = row.segmentPercent
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = "$percent% · ${row.completedSegments}/${row.totalSegments} segments",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (row.completedSegments > 0) {
                Text(
                    text = "${row.completedSegments} segments",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                SessionStateChip(state = row.displayState)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Cache: ${StringUtils.fmtBytes(row.cacheBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("sessions_row_cache"),
                )
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { onResume(row.resumeSession) },
                    enabled = actionsEnabled,
                ) { Text("Resume") }
                // SPEC §20 Pause — enabled only while some row really is
                // pausable (SessionActions re-derives the same predicate).
                TextButton(
                    onClick = { onPause(row.sessionId) },
                    enabled = actionsEnabled && row.pauseCount > 0,
                ) { Text("Pause") }
                // Shown ONLY while paused rows exist, so a healthy session never
                // offers a Resume-all that would do nothing.
                if (row.resumeCount > 0) {
                    TextButton(
                        onClick = { onResumeDownloads(row.sessionId) },
                        enabled = actionsEnabled,
                    ) { Text("Resume all") }
                }
                TextButton(
                    onClick = { onCancel(row.sessionId) },
                    enabled = actionsEnabled && row.cancelCount > 0,
                ) { Text("Cancel") }
                TextButton(
                    onClick = { onRetry(row.sessionId) },
                    enabled = actionsEnabled && row.retryCount > 0,
                ) { Text("Retry") }
                TextButton(
                    onClick = { onDelete(row.sessionId) },
                    enabled = actionsEnabled && row.deleteEnabled,
                ) { Text("Delete") }
                TextButton(
                    onClick = { onDeleteTempCache(row.sessionId) },
                    enabled = actionsEnabled && row.tempCacheEnabled,
                ) { Text("Delete temporary cache") }
            }

            // The disabled STATE is shown with its reason (DECISIONS 106).
            (row.deleteDisabledReason ?: row.tempCacheDisabledReason)?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("sessions_row_disabled_reason"),
                )
            }
        }
    }
}

/** §21.3-style state chip: the state IS the text (never color alone). */
@Composable
private fun SessionStateChip(state: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = state.lowercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag("sessions_row_state")
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
