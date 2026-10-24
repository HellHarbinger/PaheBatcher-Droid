package com.pahebatcher.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pahebatcher.app.domain.util.StringUtils
import com.pahebatcher.app.ui.mapper.DownloadRowMapper
import com.pahebatcher.app.ui.mapper.DownloadRowUi
import com.pahebatcher.app.ui.mapper.DownloadUserConcept

/**
 * Phase 11 §16/§17: Downloads dashboard.
 *
 * - Seven user concepts only (RESOLVING shows as `queued` + the "preparing link"
 *   sub-label, `PAUSED` shows as `paused`); the engine's 8 states never leak
 *   into the vocabulary. `PAUSED` exists because SPEC §25 lists `paused` among
 *   the states the dashboard must show.
 * - Progress is a pure product of the persisted Room row (§16.1): determinate
 *   bar + numeric percentage only when `totalSegments > 0`; indeterminate
 *   styling for in-flight rows with an unknown total; never a fabricated `0%`.
 * - Speed/ETA render ONLY from non-null mapper output — `0 B/s` / `0:00` are
 *   unrepresentable (§16.1).
 * - Actions are exactly the §17.1 matrix: Cancel / Retry / Play plus the SPEC
 *   §20 Pause and Resume controls (Pause on a pausable row, Resume on a paused
 *   one). No export control.
 * - Terminal concepts (completed/failed/cancelled) announce via a polite live
 *   region; per-persist progress ticks never announce (§21.2).
 * - Final-audit H5 (SPEC §26 "Display human-readable errors"): a failed action
 *   (Cancel/Retry/Pause/Resume threw) renders a dismissible error banner under
 *   the top app bar — polite live region, never silently swallowed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    uiState: DownloadsUiState,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
    // the Session Manager entry point — the ONLY
    // way into Sessions, always visible from this screen's top app bar.
    onOpenSessions: () -> Unit = {},
    // SPEC §20 Pause / Resume (final gap closure). Production wires both; the
    // default keeps screen-level render tests free of irrelevant plumbing.
    onPause: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    // Final-audit H5 (SPEC §26): the ViewModel's user-visible action-failure
    // message and its acknowledgement. Default keeps render tests unchanged.
    actionError: String? = null,
    onDismissActionError: () -> Unit = {},
    // `Replace file` (DECISIONS 152): the COMPLETED-row affordance and its
    // confirmation dialog. All four default to inert so screen-level render
    // tests keep working unchanged, and nothing here can start a download.
    onReplaceFile: (String) -> Unit = {},
    replacementTarget: String? = null,
    onConfirmReplacement: () -> Unit = {},
    onDismissReplacement: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().testTag("downloads_root")) {
        TopAppBar(
            title = { Text("Downloads") },
            actions = {
                IconButton(
                    onClick = onOpenSessions,
                    modifier = Modifier.testTag("downloads_open_sessions"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "Sessions",
                    )
                }
            },
        )

        // Final-audit H5 (SPEC §26): the one place an action failure becomes
        // visible. Text IS the message (never color alone, §21), announced via
        // a polite live region so TalkBack reports it without interrupting.
        if (actionError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("downloads_action_error")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = actionError,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissActionError) { Text("Dismiss") }
                }
            }
        }

        // A2.1 Decision 9 (UI-010): the mapper's sanitized note — some rows
        // could not be displayed while every other row renders below. Same
        // text-first, polite-live-region treatment as the action banner; it
        // clears itself when the next emission maps cleanly (state, not event).
        uiState.loadError?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("downloads_load_error")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

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
                    text = "No downloads yet — downloads you start will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.rows, key = { it.episodeId }) { row ->
                    DownloadRowCard(
                        row = row,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onPlay = onPlay,
                        onPause = onPause,
                        onResume = onResume,
                        // Rendered OUTSIDE the §17.1 matrix on purpose: the
                        // matrix governs the five repository actions, and
                        // "Replace file" is a confirmation flow of its own that
                        // applies to the one terminal concept that owns a file
                        // (DECISIONS 152).
                        onReplaceFile = onReplaceFile,
                    )
                }
            }
        }

        // `Replace file` (DECISIONS 152): the ONE destructive dialog in the
        // app. Two properties are load-bearing:
        //
        //  * **the row lookup is by id, not by position** — a row that is gone
        //    (deleted, or renamed away) renders no dialog at all instead of a
        //    confirmation about nothing;
        //  * **Cancel and back do exactly the same thing** — a state clear in
        //    the ViewModel, with no repository call behind it. The export, its
        //    state and its output references are untouched until
        //    "Confirm replacement" is pressed, and pressing it twice cannot
        //    start a second replacement.
        replacementTarget?.let { targetEpisodeId ->
            val targetRow = uiState.rows.firstOrNull { it.episodeId == targetEpisodeId }
            if (targetRow != null) {
                AlertDialog(
                    onDismissRequest = onDismissReplacement,
                    title = { Text("Replace file") },
                    text = {
                        Text(
                            text = "The file already downloaded for " +
                                "“${targetRow.title}” (${episodeLabel(targetRow.episodeNumber, targetRow.audioLabel)}) " +
                                "will be replaced. The existing file is kept until the new " +
                                "download is finished and verified — but once the new file is " +
                                "in place, the old one is gone. Continue?",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = onConfirmReplacement) {
                            Text("Confirm replacement")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissReplacement) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadRowCard(
    row: DownloadRowUi,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    // `Replace file` (DECISIONS 152): inert default so every existing
    // screen/row render test keeps working without the new plumbing.
    onReplaceFile: (String) -> Unit = {},
) {
    val conceptLabel = row.concept.name.lowercase()
    val isTerminal = row.concept == DownloadUserConcept.COMPLETED ||
        row.concept == DownloadUserConcept.FAILED ||
        row.concept == DownloadUserConcept.CANCELLED
    val percent = row.progressPercent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Row content order (§16 + §21.7): title → episode → chip → progress → actions.
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Slice B: the row's OWN persisted audio rides on the existing
                // episode line as a compact secondary label, so the SUB and DUB
                // downloads of one episode are tellable apart without a new
                // column, a new control or a second line (AGENTS §6 — smallest
                // change that answers the question).
                text = episodeLabel(row.episodeNumber, row.audioLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                ConceptChip(
                    label = conceptLabel,
                    // §21.2: terminal transitions are announced; progress ticks never are.
                    announceOnChange = isTerminal,
                )
                if (row.subLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = row.subLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.errorMessage != null) {
                Text(
                    text = row.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // §16.1: determinate only when the persisted total is known; otherwise
            // in-flight rows get indeterminate styling and QUEUED rows get no bar.
            when {
                percent != null -> LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )

                row.concept == DownloadUserConcept.DOWNLOADING ||
                    row.concept == DownloadUserConcept.ASSEMBLING -> LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }

            val metrics = buildList {
                segmentsText(row)?.let { add(it) }
                // SPEC §25: real bytes written. A row that has not written any
                // shows nothing — never a fabricated "0 B".
                if (row.downloadedBytes > 0) add(StringUtils.fmtBytes(row.downloadedBytes))
                if (percent != null) add("$percent%")
                DownloadRowMapper.formatSpeed(row.speedBps)?.let { add(it) }
                DownloadRowMapper.formatEta(row.etaSeconds)?.let { add(it) }
                if (row.retryCount > 0) add("retries: ${row.retryCount}")
            }
            if (metrics.isNotEmpty()) {
                Text(
                    text = metrics.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // §17.1 matrix: a control is rendered only when its flag is true, so
            // Pause and Resume are mutually exclusive by construction (a row is
            // either pausable or paused, never both).
            if (row.actions.pause || row.actions.resume || row.actions.cancel ||
                row.actions.retry || row.actions.play
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (row.actions.pause) {
                        TextButton(onClick = { onPause(row.episodeId) }) { Text("Pause") }
                    }
                    if (row.actions.resume) {
                        TextButton(onClick = { onResume(row.episodeId) }) { Text("Resume") }
                    }
                    if (row.actions.cancel) {
                        TextButton(onClick = { onCancel(row.episodeId) }) { Text("Cancel") }
                    }
                    if (row.actions.retry) {
                        TextButton(onClick = { onRetry(row.episodeId) }) { Text("Retry") }
                    }
                    if (row.actions.play) {
                        TextButton(onClick = { onPlay(row.episodeId) }) { Text("Play") }
                    }
                }
            }

            // `Replace file` (DECISIONS 152): rendered OUTSIDE the §17.1 matrix
            // and only for the one concept that owns a finished file. The
            // matrix governs the five repository actions the mapper may
            // enable; this is a confirmation FLOW, so it is not one of the
            // mapper's flags and cannot change what that matrix asserts.
            //
            // The affordance only asks for the dialog — it performs no
            // download, no deletion and no overwrite by itself.
            if (row.concept == DownloadUserConcept.COMPLETED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onReplaceFile(row.episodeId) },
                        modifier = Modifier.testTag("replace_file_${row.episodeId}"),
                    ) {
                        Text("Replace file")
                    }
                }
            }
        }
    }
}

/**
 * §16.1 segment text: `completed/total` only from the persisted denominator;
 * a bare completed count (denominator unknown) is always real and may show;
 * a row with neither shows no segment text at all.
 */
private fun segmentsText(row: DownloadRowUi): String? = when {
    row.totalSegments != null -> "${row.completedSegments}/${row.totalSegments} segments"
    row.completedSegments > 0 -> "${row.completedSegments} segments"
    else -> null
}

/**
 * The episode line: `Episode N`, extended with the row's audio label when the
 * persisted row carries one.
 *
 * Slice B (DECISIONS 146): the label is `DownloadRowUi.audioLabel`, derived from
 * the row's OWN `audio` column — `jpn`/`eng` render as their language, any other
 * code as itself, and a blank/legacy value renders nothing at all
 * (`DownloadRowMapper.audioLabelFor`). So this function never decides a language
 * and never invents one; it only joins what the mapper already decided.
 */
private fun episodeLabel(episodeNumber: Float, audioLabel: String? = null): String {
    // A2.1 (UI-004): the ONE renderer decides the number's text.
    val label = StringUtils.episodeNumber(episodeNumber)
    return listOfNotNull("Episode $label", audioLabel).joinToString(" · ")
}

/** §16/§21.3: an M3-styled concept chip whose TEXT is the state (never color alone). */
@Composable
private fun ConceptChip(label: String, announceOnChange: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .then(
                    if (announceOnChange) {
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
