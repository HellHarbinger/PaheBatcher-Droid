package com.pahebatcher.app.ui.console

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pahebatcher.app.logging.LogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase 12A diagnostics Console — an OPT-IN, read-only view of the bounded
 * in-memory log buffer.
 *
 * Behaviour:
 * - retention itself is gated by `Show Console` (Phase 12B / DECISIONS 86):
 *   while the setting is OFF nothing is retained, so this screen only ever
 *   shows lines collected since it was enabled;
 * - newest entry first, so opening the screen shows the most recent activity
 *   without scrolling;
 * - [Clear] empties the shared buffer (memory only — nothing is persisted and
 *   process restart legitimately starts empty);
 * - monospace rows, one line per record with timestamp, level, tag and message;
 *   a record with a throwable shows its rendered trace on the following lines;
 * - no filtering/search/exports: those would grow a diagnostics surface far
 *   beyond its purpose (bounded retention is the contract).
 *
 * Pushed route: Up icon mirrors system back (§10.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    entries: List<LogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag("console_root")) {
        TopAppBar(
            title = { Text("Console") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            },
        )

        if (entries.isEmpty()) {
            Text(
                text = "No log entries yet. Logs are collected only while Show Console is " +
                    "enabled and are cleared when the app restarts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            val listState = rememberLazyListState()
            // Newest first: the list is already at the newest record on open.
            LaunchedEffect(entries.size) { listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("console_list"),
            ) {
                items(
                    items = entries.asReversed(),
                    key = { it.sequence },
                ) { entry ->
                    ConsoleRow(entry)
                }
            }
        }
    }
}

@Composable
private fun ConsoleRow(entry: LogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = buildString {
                append(TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampMs)))
                append(' ')
                append(entry.level.name.first())
                append('/')
                append(entry.tag)
                append(": ")
                append(entry.message)
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        if (entry.throwable != null) {
            Text(
                text = entry.throwable,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Level letter shown in a row: D / I / W / E (Logcat's own abbreviations). */
// UI-009: DateTimeFormatter is immutable and thread-safe, unlike the previous
// static SimpleDateFormat (which is mutable and must not be shared across threads).
private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US).withZone(ZoneId.systemDefault())
