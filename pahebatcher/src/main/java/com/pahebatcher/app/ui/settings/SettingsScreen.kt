package com.pahebatcher.app.ui.settings

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pahebatcher.app.data.export.OutputTreeState
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.ThemeMode

/**
 * Phase 12A Settings content: the Phase 11 theme controls PLUS the engine
 * settings production code really consumes (see [SettingsUiState] for the exact
 * consumed-mapping table). Every row is DataStore-backed end to end — a control
 * with no consumer must never exist here (AGENTS §5).
 *
 * `resolve_ahead` gained a real consumer (the batch staging gate),
 * so it has a row here. `audio_lang` had one too — both it and its row are
 * GONE as of the audio-variant slice: audio is selected per episode
 * (SPEC §22), so this screen has no Preferred-audio row and no production
 * code reads `audio_lang` (legacy DataStore bytes for the key may remain,
 * inert and unread). Still deferred with NO row: `cookie_string` (Decision 26)
 * and `output_dir` — Phase 13 ships the Android-native SAF destination as its
 * own key (`output_tree_uri`, DECISIONS 92) behind the `Output location` row;
 * each documented in [SettingsUiState].
 *
 * The content column scrolls (§13 growth), the TopAppBar stays pinned, and every
 * row carries the shared `settings_row` test tag.
 *
 * Pushed route: Up icon in the TopAppBar mirrors system back (§10.4) and
 * carries its §21.1 content description.
 *
 * @param onOpenConsole shown only when `Show Console` is ON — the Console entry
 *   point (diagnostics, opt-in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onQuality: (Int) -> Unit,
    onMaxParallel: (Int) -> Unit,
    onHlsWorkers: (Int) -> Unit,
    onCacheTtl: (Int) -> Unit,
    onKeepTemp: (Boolean) -> Unit,
    onAutoRetry: (Int) -> Unit,
    onResolveAhead: (Int) -> Unit,
    onShowConsole: (Boolean) -> Unit,
    onOpenConsole: () -> Unit,
    // `wifi_only` — persisted toggle whose only
    // consumer is the enqueue-time WorkManager constraint.
    onWifiOnly: (Boolean) -> Unit,
    // `auto_resume` — persisted
    // toggle consumed by DownloadWorker's transport-failure eligibility decision
    // (and the enqueue-time constraint helper at the three build sites).
    onAutoResume: (Boolean) -> Unit,
    // the two episode-terminal notification toggles —
    // consumed at the single post site each (DownloadWorker completion / failure).
    onNotifyCompletion: (Boolean) -> Unit,
    onNotifyFailure: (Boolean) -> Unit,
    // the watchlist detection toggle — consumed
    // at WatchlistCheckWorker's single post site.
    onNotifyWatchlist: (Boolean) -> Unit,
    // the background check frequency — consumed by
    // the Application collector that re-registers the unique periodic work.
    onWatchlistCheckInterval: (Long) -> Unit,
    // the notification permission status row — the real
    // platform state, its refresh points, the one-time request result, and the
    // manual system-settings escape hatch.
    notificationPermissionState: NotificationPermissionUiState,
    onRefreshNotificationPermission: () -> Unit,
    onNotificationPermissionResult: (Boolean) -> Unit,
    onOpenNotificationSystemSettings: () -> Unit,
    // the `Output location` row — the persisted SAF
    // export destination (DECISIONS 92), its refresh points, the picker result,
    // and the revert-to-default action.
    outputLocationState: OutputTreeState,
    onRefreshOutputLocation: () -> Unit,
    onOutputTreePicked: (String) -> Unit,
    onUseDefaultOutputLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // the row's ONE-time POST_NOTIFICATIONS request —
    // the same asked-once latch the Phase 13 first-enqueue prompt consumes, so
    // whichever happens first is the only ask.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onNotificationPermissionResult(granted) }

    // the SAF folder picker. A cancelled picker
    // returns null → no state change; a real tree is handed to the ViewModel,
    // which takes the persistable grant and persists the key. When the folder
    // is already configured the picker opens AT it (EXTRA_INITIAL_URI via the
    // contract's input), so "Change" starts where the user already is.
    val outputTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onOutputTreePicked(uri.toString()) }

    // The grant can change while this screen stays composed (a system-settings
    // round trip) — re-project on every resume so the row never lies. The same
    // applies to the output tree: the picker is a round trip too, and a folder
    // can be deleted in the Files app meanwhile.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshNotificationPermission()
                onRefreshOutputLocation()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize().testTag("settings_root")) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Appearance")

            // Row 1: theme mode.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_row")
                    .selectableGroup(),
            ) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                RadioOption(
                    label = "System",
                    selected = uiState.themeSettings.themeMode == ThemeMode.SYSTEM,
                    onSelect = { onThemeMode(ThemeMode.SYSTEM) },
                )
                RadioOption(
                    label = "Dark",
                    selected = uiState.themeSettings.themeMode == ThemeMode.DARK,
                    onSelect = { onThemeMode(ThemeMode.DARK) },
                )
                RadioOption(
                    label = "Light",
                    selected = uiState.themeSettings.themeMode == ThemeMode.LIGHT,
                    onSelect = { onThemeMode(ThemeMode.LIGHT) },
                )
            }

            // Row 2: dynamic color.
            SwitchRow(
                title = "Dynamic color",
                description = "Replaces the curated palette with your system wallpaper colors.",
                checked = uiState.themeSettings.dynamicColor,
                onCheckedChange = onDynamicColor,
            )

            HorizontalDivider()
            // Section title deliberately avoids the bottom-bar word "Downloads" —
            // NavigationTest taps the bottom-nav "Downloads" while this screen is
            // pushed, and a second identical text node would make that ambiguous.
            SectionHeader("Download engine")

            // Row 3: quality — consumed by AnimeDetailsViewModel at resolution time.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_row")
                    .selectableGroup(),
            ) {
                Text(
                    text = "Quality",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                RadioOption(
                    label = "360p",
                    selected = uiState.quality == 360,
                    onSelect = { onQuality(360) },
                )
                RadioOption(
                    label = "720p",
                    selected = uiState.quality == 720,
                    onSelect = { onQuality(720) },
                )
                RadioOption(
                    label = "1080p",
                    selected = uiState.quality == 1080,
                    onSelect = { onQuality(1080) },
                )
                RowDescription("Resolution requested when an episode's stream is resolved.")
            }

            // Quality is the LAST download-engine row that selects a source
            // attribute globally. Audio deliberately has NO row here: audio is
            // chosen per episode from that episode's own variants
            // (SPEC §22 "Audio selection (per episode)" / §23 — audio is not a
            // global Settings preference), so the former `Preferred audio`
            // row and its `audio_lang` key are gone.

            // Row 4: max parallel — consumed by the DownloadWorker concurrency gate.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_row")
                    .selectableGroup(),
            ) {
                Text(
                    text = "Max parallel downloads",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    (AppContext.MIN_PARALLEL..AppContext.MAX_PARALLEL).forEach { value ->
                        RadioOption(
                            label = value.toString(),
                            selected = uiState.maxParallel == value,
                            onSelect = { onMaxParallel(value) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                RowDescription("How many episodes download at the same time.")
            }

            // Row 5: HLS workers — consumed by EpisodeDownloader's segment semaphore.
            SliderRow(
                title = "HLS workers",
                valueText = "${uiState.hlsWorkers} segments at once",
                description = "Concurrent segment downloads per episode.",
                value = uiState.hlsWorkers.toFloat(),
                range = AppContext.MIN_HLS_WORKERS.toFloat()..AppContext.MAX_HLS_WORKERS.toFloat(),
                steps = AppContext.MAX_HLS_WORKERS - AppContext.MIN_HLS_WORKERS - 1,
                onCommit = { onHlsWorkers(it.toInt()) },
                testTag = "settings_hls_workers",
            )

            // Row 6: scan cache TTL — consumed by EpisodeRepository.scan.
            SliderRow(
                title = "Scan cache TTL",
                valueText = "${uiState.cacheTtlMinutes} min",
                description = "Cached episode lists expire after this many minutes. 0 disables the cache.",
                value = uiState.cacheTtlMinutes.toFloat(),
                range = 0f..240f,
                steps = 239,
                onCommit = { onCacheTtl(it.toInt()) },
                testTag = "settings_cache_ttl",
            )

            // Row 7: keep temp — consumed by EpisodeDownloader after muxing.
            SwitchRow(
                title = "Keep temp files",
                description = "Keep downloaded segments after muxing instead of deleting them.",
                checked = uiState.keepTemp,
                onCheckedChange = onKeepTemp,
            )

            // Row 8: auto retry — consumed by EpisodeDownloader's episode retry budget.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_row")
                    .selectableGroup(),
            ) {
                Text(
                    text = "Auto retry",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                RadioOption(
                    label = "None",
                    selected = uiState.autoRetry == 0,
                    onSelect = { onAutoRetry(0) },
                )
                RadioOption(
                    label = "Once",
                    selected = uiState.autoRetry == 1,
                    onSelect = { onAutoRetry(1) },
                )
                RadioOption(
                    label = "Twice",
                    selected = uiState.autoRetry == 2,
                    onSelect = { onAutoRetry(2) },
                )
                RowDescription("Extra attempts for an episode that fails.")
            }

            // Phase 12C Row: resolve_ahead — the batch staging bound. Values are
            // the practical depths; 999 is Python's default (effectively
            // unbounded), so the shipped default never throttles a batch.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_row")
                    .selectableGroup(),
            ) {
                Text(
                    text = "Resolve ahead",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                listOf(
                    0 to "0 — resolve only when a download starts",
                    1 to "1 episode",
                    2 to "2 episodes",
                    4 to "4 episodes",
                    8 to "8 episodes",
                    999 to "Unlimited (999, default)",
                ).forEach { (value, label) ->
                    RadioOption(
                        label = label,
                        selected = uiState.resolveAhead == value,
                        onSelect = { onResolveAhead(value) },
                    )
                }
                RowDescription(
                    "How many resolved episodes may wait ahead of the running downloads during a batch.",
                )
            }

            // Wi-Fi-only downloads — consumed ONLY
            // at enqueue time (`NetworkType.UNMETERED` at the three WorkRequest
            // build sites); queued work keeps the constraints it was built with,
            // so flipping this never cancels or rewrites an existing download.
            SwitchRow(
                title = "Wi-Fi-only downloads",
                description = "Only start downloads on unmetered (Wi-Fi) networks. " +
                    "Downloads already waiting keep their current behavior.",
                checked = uiState.wifiOnly,
                onCheckedChange = onWifiOnly,
            )

            // Auto-resume —
            // consumed by `DownloadWorker.shouldAutoResume`, which parks a
            // TRANSPORT-class failure back to QUEUED and returns `Result.retry()`
            // (bounded, exponential backoff) instead of failing terminally. The
            // row is only visible when it changes real behavior (AGENTS §5):
            // every other failure class still fails immediately.
            SwitchRow(
                title = "Auto-resume downloads",
                description = "Retries failed downloads automatically when connectivity " +
                    "returns; bounded attempts.",
                checked = uiState.autoResume,
                onCheckedChange = onAutoResume,
            )

            // SAF export destination — the persisted
            // `output_tree_uri` the exporters validate before every export
            // (MediaStore `Movies/<anime title>/` while unset or unusable). The
            // consumer is OutputTreeRepository — no decorative row (AGENTS §5).
            HorizontalDivider()
            SectionHeader("Storage")
            OutputLocationRow(
                state = outputLocationState,
                onPickFolder = {
                    outputTreeLauncher.launch(
                        (outputLocationState as? OutputTreeState.Valid)
                            ?.let { Uri.parse(it.treeUri) },
                    )
                },
                onUseDefault = onUseDefaultOutputLocation,
            )

            // notification permission status — the real
            // platform state with its ONE-time request ("Allow", same asked-once
            // latch as the first-enqueue prompt) or the manual "Open settings"
            // escape hatch. The consumer is NotificationPermissionRepository —
            // no decorative row exists (AGENTS §5).
            HorizontalDivider()
            SectionHeader("Notifications")
            NotificationPermissionRow(
                state = notificationPermissionState,
                onRequestPermission = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onOpenSystemSettings = onOpenNotificationSystemSettings,
            )

            // which notifications are posted at all.
            // All three are read fresh at their post site, so a flip applies to
            // the NEXT notification of that type and never touches one already
            // shown. None of them can gate `download_progress` — that is the
            // foreground-service notification of running work (§R7).
            SwitchRow(
                title = "Completion notifications",
                description = "Notify when an episode finishes downloading.",
                checked = uiState.notifyCompletion,
                onCheckedChange = onNotifyCompletion,
            )
            SwitchRow(
                title = "Failure notifications",
                description = "Notify when an episode fails after all retries.",
                checked = uiState.notifyFailure,
                onCheckedChange = onNotifyFailure,
            )
            // §12.2: reports DETECTION of new episodes; a foreground `checkNow()`
            // never posts at all, so this row only shapes background checks.
            SwitchRow(
                title = "New watchlist episodes",
                description = "Notify when a background check finds new episodes.",
                checked = uiState.notifyWatchlist,
                onCheckedChange = onNotifyWatchlist,
            )

            // how often the background
            // check re-registers. Deliberately its own section — this is a
            // schedule, not a notification choice — and deliberately bounded to
            // values >= WorkManager's 15-minute periodic minimum (SPEC §19).
            HorizontalDivider()
            SectionHeader("Background checks")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_row")
                    .selectableGroup(),
            ) {
                Text(
                    text = "Check frequency",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                listOf(
                    15L to "15 minutes (default)",
                    30L to "30 minutes",
                    60L to "1 hour",
                    240L to "4 hours",
                    720L to "12 hours",
                ).forEach { (value, label) ->
                    RadioOption(
                        label = label,
                        selected = uiState.watchlistCheckIntervalMinutes == value,
                        onSelect = { onWatchlistCheckInterval(value) },
                    )
                }
                RowDescription("How often the background watchlist check runs.")
            }

            HorizontalDivider()
            SectionHeader("Diagnostics")

            // Row 9: show console — gates the Console entry (default OFF) AND
            // whether log lines are retained at all (DECISIONS 86).
            SwitchRow(
                title = "Show Console",
                description = "Adds an in-app log console. Logs are kept only while this is on.",
                checked = uiState.showConsole,
                onCheckedChange = onShowConsole,
            )

            // Row 10: only rendered when the user opted in — never a dead row.
            if (uiState.showConsole) {
                ClickableRow(
                    title = "Open console",
                    description = "View the in-app diagnostic log (cleared when the app restarts).",
                    onClick = onOpenConsole,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun RowDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun RadioOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row")
            .selectable(
                selected = checked,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The notification permission status row.
 *
 * Display AND action are derived from the same three reported facts — the UI
 * never invents a state the repository did not report:
 *
 * - granted → honest "Allowed" status, no action (nothing decorative);
 * - not granted + the runtime prompt still available and never asked → "Allow"
 *   launches the one-time system prompt;
 * - otherwise (denied after asking, or API ≤ 32 with the permission revoked) →
 *   "Open settings" hands the user to the system notification settings.
 */
@Composable
private fun NotificationPermissionRow(
    state: NotificationPermissionUiState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val canRequest = !state.granted && state.runtimeRequestApplies && !state.askedOnce
    val status = when {
        state.granted -> "Allowed — download progress can be shown."
        canRequest -> "Not allowed yet — progress notifications are hidden."
        else -> "Blocked — progress notifications are hidden. Allow them in system settings."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Notifications", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            canRequest -> TextButton(onClick = onRequestPermission) { Text("Allow") }
            !state.granted -> TextButton(onClick = onOpenSystemSettings) { Text("Open settings") }
            // granted → the status text says everything; no dead control.
        }
    }
}

/**
 * The `Output location` row — the persisted SAF
 * export destination ([OutputTreeRepository] validates it before every export;
 * while unset or unusable exports land in MediaStore `Movies/<anime title>/`,
 * Gate A per-anime directory).
 *
 * Display AND actions derive from the reported state; the row never invents a
 * state the repository did not report (AGENTS §5):
 *
 * - [OutputTreeState.NotConfigured] → honest "not set" + "Choose" opens the
 *   folder picker;
 * - [OutputTreeState.Valid] with [OutputTreeState.Valid.renameOk] true → the
 *   decoded folder identity, "Change" re-opens the picker AT the current
 *   folder, "Default" reverts (clears the key AND releases the persistable
 *   grant);
 * - [OutputTreeState.Valid] with `renameOk` false (DECISIONS 104.4, the
 *   second mandated consumer of `output_tree_rename_ok`) → the same actions
 *   but the description must stop claiming the folder receives exports: the
 *   export-destination router is already sending everything to the MediaStore
 *   default, so the row says so (exact clause derived by
 *   [outputLocationDescription], JVM-tested);
 * - [OutputTreeState.Unusable] → the repository's real reason (revoked /
 *   missing / malformed — never paraphrased away) + "Choose" re-picks.
 */
@Composable
private fun OutputLocationRow(
    state: OutputTreeState,
    onPickFolder: () -> Unit,
    onUseDefault: () -> Unit,
) {
    val description = outputLocationDescription(state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Output location", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            OutputTreeState.NotConfigured ->
                TextButton(onClick = onPickFolder) { Text("Choose") }

            is OutputTreeState.Valid -> {
                TextButton(onClick = onPickFolder) { Text("Change") }
                TextButton(onClick = onUseDefault) { Text("Default") }
            }

            is OutputTreeState.Unusable ->
                TextButton(onClick = onPickFolder) { Text("Choose") }
        }
    }
}

/**
 * The `Output location` row description —
 * pure derivation over the reported [state] so the EXACT texts (including the
 * 104.4 rename-less clause) are JVM-testable instead of only reachable through
 * a composed screen.
 *
 * The rename-less clause is Decision 104.4's mandated wording "this folder
 * can't be used — exports go to …", sentence-cased to stand alone as the row's
 * description. The destination is spelled `Movies/<anime title>` — the
 * per-anime directory structure exports really use (Gate A: Python output
 * parity via OutputPathNames/MediaStoreExporter).
 * Every other state stays byte-identical to the original inline mapping.
 */
internal fun outputLocationDescription(state: OutputTreeState): String =
    when (state) {
        OutputTreeState.NotConfigured ->
            "Not set — exports go to Movies/<anime title> (system default)."
        is OutputTreeState.Valid ->
            if (state.renameOk) {
                "Exports go to: ${outputTreeLabel(state.treeUri)}"
            } else {
                "This folder can't be used — exports go to Movies/<anime title>."
            }
        is OutputTreeState.Unusable ->
            "Problem: ${state.reason} — exports go to Movies/<anime title> " +
                "until you choose the folder again."
    }

@Composable
private fun ClickableRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row")
            .selectable(
                selected = false,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The slider commit point as a plain unit (AGENTS
 * §8): a drag updates ONLY this local gesture state — never DataStore — and
 * [release] hands the final dragged value to the ViewModel exactly once, so
 * one gesture is ONE DataStore write instead of one per tick. The composable
 * holds the instance in `remember`, which is the "dragged value held in local
 * UI state until release" the decision requires.
 */
internal class SliderGesture(
    initial: Float,
    private val persist: (Float) -> Unit,
) {

    /** Compose snapshot state — each drag tick recomposes the thumb locally. */
    private val state = mutableStateOf(initial)

    /** Current (dragged) value — the Slider thumb reads straight from here. */
    val value: Float
        get() = state.value

    /** A drag tick — LOCAL only; nothing is written to DataStore here. */
    fun drag(newValue: Float) {
        state.value = newValue
    }

    /** Release: the single commit point — one write per gesture (98.3). */
    fun release() {
        persist(state.value)
    }
}

/**
 * Label + live value + real [Slider] (integer steps) for the bounded
 * numeric settings `hls_workers` (8..32) and `cache_ttl` (0..240 min).
 *
 * The gesture commits ON FINISH — the dragged value
 * lives in a local [SliderGesture] until release (exactly one DataStore write
 * per gesture, never one per tick), and the committed ViewModel value re-seeds
 * the gesture through [remember] once it lands.
 */
@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
    testTag: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_row")
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // DECISIONS 98.3: drag → local gesture state only; release → the ONE
        // write per gesture. [remember] re-seeds from the committed value.
        val gesture = remember(value) { SliderGesture(value, onCommit) }
        Slider(
            value = gesture.value,
            onValueChange = { gesture.drag(it) },
            onValueChangeFinished = { gesture.release() },
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
        )
        RowDescription(description)
    }
}
