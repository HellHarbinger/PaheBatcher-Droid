package com.pahebatcher.app.ui.details

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.FailureCategory
import com.pahebatcher.app.domain.model.AudioProbeState
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.util.EpisodePaging
import com.pahebatcher.app.domain.util.StringUtils
import com.pahebatcher.app.ui.common.OfflineBanner
import com.pahebatcher.app.ui.home.ErrorPanel

/**
 * Anime details destination — title + the real episode list.
 * an episode is selectable and its stream resolution is rendered.
 *
 * Content is EXACTLY what the existing scan/resolver produced (AGENTS §5 —
 * nothing invented, nothing dead):
 *
 * - Loading: the scan is running ([AnimeDetailsUiState.loading]).
 * - Success: one card per [EpisodeInfo] carrying only fields the model really
 *   has — episode number, its title when the API supplied one, and the audio /
 *   fansub metadata when non-blank.
 * - Empty: [AnimeDetailsViewModel.NO_EPISODES] — a completed scan that returned
 *   nothing, never presented as an error.
 * - Error: the shared [ErrorPanel], Retry bound to the same real scan.
 *
 * Selection → resolution (11.5E-3): rows are `selectable`, and selecting one
 * runs the injected production [com.pahebatcher.app.domain.stream.StreamResolver]
 * through the ViewModel. The panel above the list renders exactly one of the four
 * honest resolution branches — resolving (progress), resolved (the StreamInfo the
 * resolver really returned, including its real M3U8 URL), failed (the resolver's
 * own message + Retry bound to the same real resolution) — and nothing at all
 * while idle.
 *
 * Resolved → queue (11.5E-4): the resolved branch carries the downstream
 * controls that are actually wired — Download, bound to
 * [AnimeDetailsViewModel.downloadResolvedEpisode], which hands the stream to the
 * existing download pipeline and reports its three real outcomes (queueing,
 * queued, failed + retry), and the Phase 17 Stream action ([onStream]) with the
 * same resolver-backed path used by the watchlist check.
 *
 * Stateless (AGENTS §4): receives [AnimeDetailsUiState] + lambdas only.
 *
 * Phase 12C batch surface (a minimal Phase 13 prerequisite): the top bar's
 * selection action switches the screen into batch mode, where a row tap toggles
 * that episode NUMBER and the batch panel resolves+queues every selected number
 * through the SAME resolver and queue the single-episode path uses (Python's
 * `BatchOrchestrator`). Every callback is a plain lambda — the screen still
 * performs no HTTP, no HLS work and no database access itself.
 *
 * Option C audio surface (DECISIONS 143 / 144, SPEC §22): a screen-level audio
 * selector over the DISCOVERED option set for the current title (open set —
 * the bounded probe's union, falling back to the release listing), ONE row per
 * episode NUMBER carrying that number's requested audio ([visibleEpisodeRows] —
 * variants of other audios are never separate top-level rows, and a number
 * never hides because release metadata lacks the current audio), and a
 * per-episode "change audio" control inside the row when — and only when — that
 * number offers several variants. The control's taps are consumed by the
 * control itself (never the row's selection gesture), every audio control is
 * inert while a batch runs, and both callbacks hand raw values to the
 * ViewModel — the coherent state transition (context change, override write) is
 * owned there, never here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailsScreen(
    uiState: AnimeDetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectEpisode: (EpisodeInfo) -> Unit,
    onRetryResolution: () -> Unit,
    onDownload: () -> Unit,
    // direct HLS streaming of the already-resolved episode. Carries no
    // URL — the host derives the episode id and navigates with STREAM mode.
    onStream: () -> Unit = {},
    modifier: Modifier = Modifier,
    onToggleBatchMode: () -> Unit = {},
    onToggleBatchEpisode: (EpisodeInfo) -> Unit = {},
    onSelectAllEpisodes: () -> Unit = {},
    onClearBatchSelection: () -> Unit = {},
    // Consolidated range control (Phase 12 UX correction): the screen now has
    // ONE range control (From/To, wired to `onRequestEpisodeRange`). This
    // callback is NO longer a separate expression field — it is kept solely as
    // the UNKNOWN-TOTAL fallback: when the listing exposes no total, the From/To
    // bounds are handed here as a loaded-catalogue NUMBER range
    // (`selectEpisodeRange`), because the ordinal path cannot validate or fetch
    // windows without a total.
    onSelectEpisodeRange: (String) -> Unit = {},
    // The PRIMARY range selector — ordinals `[start, end]` of the API's stable
    // `episode_asc` ordering, with its own validation (≤ 100, in bounds, known
    // total), its own page fetching and its own rejection messages. It fetches
    // only the windows the range spans, so it works for unloaded pages.
    onRequestEpisodeRange: (Int, Int) -> Unit = { _, _ -> },
    // Lazy-loading slice (requirement 4): page navigation. `null`/out-of-range
    // pages are rejected by the ViewModel, not by the button's enabled state.
    onGoToPage: (Int) -> Unit = {},
    // SPEC §22: select the newest N episode numbers (same selection state, same
    // production parser — the only new surface is the numeric entry).
    onSelectLatestEpisodes: (Int) -> Unit = {},
    onStartBatch: () -> Unit = {},
    // Page-scoped batch (correction, requirement 4): queue EXACTLY the
    // episodes of the app page on screen. The ViewModel derives the contents
    // from that page's rows and re-validates the scope and the 100-episode
    // ceiling at execution, so the label's count is a promise the execution
    // path really keeps.
    onStartPageBatch: () -> Unit = {},
    onCancelBatch: () -> Unit = {},
    // Option C (DECISIONS 143): the two audio events — the screen-level context
    // change and ONE number's explicit override. Both are ViewModel-owned state
    // transitions; the screen only hands the raw values over.
    onSelectAudio: (String) -> Unit = {},
    onSetEpisodeAudio: (Float, String) -> Unit = { _, _ -> },
    // back-navigation while a batch runs opens the
    // confirmation warning instead of leaving. The screen renders the warning
    // from state and stays stateless — the host wires these to the ViewModel
    // (confirm = cancelBatch() + the actual back navigation).
    onRequestLeaveBatch: () -> Unit = {},
    onStayInBatch: () -> Unit = {},
    onConfirmLeaveBatch: () -> Unit = {},
    // the ActivityResult callback for the one-time
    // POST_NOTIFICATIONS prompt (wired to the ViewModel's outcome recorder).
    onNotificationPermissionResult: (Boolean) -> Unit = {},
    // Post-audio slice: re-run the BOUNDED audio probe from an already-loaded
    // screen. The screen only hands the tap over — every gate (in-flight,
    // completed result, target bound, offline) lives in the ViewModel.
    onRetryProbe: () -> Unit = {},
) {
    // DECISIONS 93.2: both back paths (system gesture AND the top-bar icon)
    // route through the same warning while a batch is running. The warning's
    // own handler wins first so back dismisses it instead of double-firing.
    BackHandler(enabled = uiState.leaveBatchWarning) { onStayInBatch() }
    BackHandler(enabled = uiState.batchRunning && !uiState.leaveBatchWarning) {
        onRequestLeaveBatch()
    }

    // the ONE-time POST_NOTIFICATIONS prompt at the
    // first enqueue. The launcher is registered unconditionally (ActivityResult
    // contract requirement); the launch itself runs ONLY while the ViewModel's
    // asked-once gate set the flag — no test ever sets it, so instrumentation
    // never meets a system dialog.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onNotificationPermissionResult(granted) }
    if (uiState.requestNotificationPermission) {
        // UI-003: a rotation while the prompt is in flight re-runs this effect.
        // A `rememberSaveable` latch makes the launch happen exactly once per
        // activity instance across recreation, so the system dialog is never
        // presented twice for one request.
        val permissionPromptLaunched = rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!permissionPromptLaunched.value) {
                permissionPromptLaunched.value = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Option C (DECISIONS 143): derived ONCE per relevant state change — the
    // rendered rows and the selector options come from the SAME production
    // rules the ViewModel's bulk actions and resolution paths use, so the UI
    // can never show one audio while execution requests another. The keys are
    // exactly what the derivations read: batch progress ticks never recompute
    // them.
    // Lazy-loading slice: the WINDOW this screen renders — the current page's
    // rows when paging is on, the whole loaded listing when it is not (the
    // `null` case). Both derivations read it so the rendered rows and the
    // override control always describe the SAME set, and a page switch can
    // never leave the previous page's rows behind.
    val pageEpisodes = displayEpisodeEpisodes(uiState)
    val rows = remember(
        pageEpisodes,
        uiState.selectedAudio,
        uiState.explicitAudioOverrides,
    ) {
        displayEpisodeRows(uiState)
    }
    // SPEC §22 / DECISIONS 143.1: the explicit per-episode override exists only
    // when the NUMBER offers several audio variants (`getAllVariants(N)` > 1) —
    // a single-variant number gets no override surface. Rows are unique per
    // (number, audio) by construction (EpisodeRepository dedup), so counting a
    // number's rows counts its audio variants.
    val multiVariantNumbers = remember(pageEpisodes) {
        pageEpisodes.groupingBy { it.number }.eachCount()
            .filterValues { it > 1 }.keys
    }
    // DECISIONS 144.6 — the selector offers the DISCOVERED option set (the
    // bounded probe's union, or the listing fallback), never a re-derivation
    // from the episode rows.
    val audioOptions = uiState.audioOptions

    // Phase 12 UX correction: the batch panel is the episode LazyColumn's FIRST
    // item, so it is only visible when the list is scrolled to the very top.
    // A list scrolled down before batch mode was entered would otherwise keep
    // that offset and hide the panel entirely — the user would enter batch mode
    // and see no controls. The same applies after paging: `goToPage` clears the
    // rows, the list flips to the spinner and back, and the rebuilt list (whose
    // first item is the panel) would resume at the old offset. Keying on the
    // page as well as the mode snaps to the top on entering batch mode AND on
    // every page change while it is open, so the panel is always in view.
    val episodeListState = rememberLazyListState()
    LaunchedEffect(uiState.batchMode, uiState.currentPage) {
        if (uiState.batchMode) episodeListState.scrollToItem(0)
    }

    Column(modifier = modifier.fillMaxSize().testTag("anime_details_root")) {
        TopAppBar(
            // Real title as soon as a scan resolves it; a generic screen name
            // before that (never a fabricated anime name).
            title = { Text(uiState.title ?: "Anime details") },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (uiState.batchRunning) onRequestLeaveBatch() else onBack()
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                // the only entry into batch selection. Its label is the
                // CURRENT mode, so the control never lies about what a tap does.
                IconButton(
                    onClick = onToggleBatchMode,
                    enabled = uiState.episodes.isNotEmpty() && !uiState.batchRunning,
                    modifier = Modifier.testTag("anime_details_batch_toggle"),
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = if (uiState.batchMode) {
                            "Exit episode selection"
                        } else {
                            "Select episodes"
                        },
                    )
                }
            },
        )

        // Anime Details is network-dependent —
        // scan/resolution are guarded in the ViewModel; this is the indication.
        if (uiState.offline) {
            OfflineBanner()
        }

        // 11.5E-3: the resolution panel exists ONLY while an episode is selected
        // (idle renders nothing — no empty card, no placeholder). Phase 12C: in
        // batch mode selection means something else, so the single-episode panel
        // is hidden rather than shown alongside a mode that cannot produce it.
        if (uiState.selectedEpisode != null && !uiState.batchMode) {
            ResolutionPanel(
                uiState = uiState,
                onRetry = onRetryResolution,
                onDownload = onDownload,
                onStream = onStream,
            )
        }

        // Option C (DECISIONS 143.1/144.6, SPEC §22): the screen-level audio
        // context over the DISCOVERED option set. Rendered only for a REAL
        // listing — loading/error/empty branches expose no audio values, so
        // there is nothing to select.
        if (uiState.episodes.isNotEmpty()) {
            AudioContextSelector(
                audios = audioOptions,
                selectedAudio = uiState.selectedAudio,
                enabled = !uiState.batchRunning,
                onSelect = onSelectAudio,
            )
            // Post-audio slice: what the option set ABOVE really rests on — a
            // COMPLETED representative probe, or the release-listing fallback
            // because the probe is partial/failed/never ran. Rendered for every
            // real listing so the distinction is never silent, and it carries
            // the ONLY probe affordance the screen has (the Retry, which the
            // ViewModel gates).
            AudioProbeStatus(
                state = uiState.audioProbeState,
                probing = uiState.audioProbing,
                enabled = !uiState.batchRunning,
                onRetry = onRetryProbe,
            )
            // DECISIONS 144.6: a context change reports NOTHING — every selected
            // number is kept, so the former deselect notice (and its test tag)
            // is gone rather than rendered empty.
        }

        // Lazy-loading slice (requirement 4) — the visible APP page. Rendered
        // only when the listing really spans more than one of them, so a
        // single-page title (and every response with no usable paginator)
        // shows no pager at all, exactly as before this slice. The count is the
        // APP page count, not the API's page count: a 250-episode title is
        // three pages of 100, even though the server answers in windows of 30.
        if ((appPageCountOf(uiState) ?: 0) > 1) {
            PageBar(
                page = uiState.currentPage,
                lastPage = appPageCountOf(uiState) ?: 1,
                total = uiState.apiTotal,
                loading = uiState.pageLoading,
                enabled = !uiState.batchRunning,
                onGoToPage = onGoToPage,
            )
        }

        when {
            uiState.error != null -> ErrorPanel(
                message = uiState.error,
                onRetry = onRetry,
            )

            uiState.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.testTag("anime_details_loading"))
                    // Post-audio slice: the OBSERVED scan progress — session
                    // position and the server's own page totals — shown only
                    // when the repository actually reported some. Before that
                    // (and on a cache hit, which reports none) the spinner
                    // stands alone exactly as it did before.
                    uiState.scanProgress?.let { progress ->
                        Text(
                            text = progress.describe(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .testTag("anime_details_scan_progress"),
                        )
                    }
                }
            }

            // A failed PAGE is not a failed listing: the window the user asked
            // for reports its own reason and offers its own retry, while every
            // other loaded page keeps working. Scoped deliberately AFTER the
            // whole-screen `error` above, which stays the stronger claim.
            uiState.pageError != null -> ErrorPanel(
                message = uiState.pageError,
                onRetry = { onGoToPage(uiState.currentPage) },
            )

            pageEpisodes.isEmpty() && uiState.pageLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.testTag("anime_details_page_loading"),
                )
            }

            pageEpisodes.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = AnimeDetailsViewModel.NO_EPISODES,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(24.dp)
                        .testTag("anime_details_empty"),
                )
            }

            else -> LazyColumn(
                // weight() keeps the list in the space the panel leaves over, so a
                // selected episode never pushes rows off-screen.
                state = episodeListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("anime_details_episodes"),
            ) {
                // The batch controls scroll WITH the episode list so they can
                // never starve it of height. Pinned above the list they consumed
                // ~782dp of an ~871dp content area on a tall phone, collapsing the
                // episode LazyColumn to a single row — and to zero on shorter
                // devices — which is exactly the reported "batch view hides the
                // list" defect. As list items they take only the height the scroll
                // gives them and every row stays reachable by scrolling.
                if (uiState.batchMode) {
                    item {
                        BatchPanel(
                            uiState = uiState,
                            onSelectAll = onSelectAllEpisodes,
                            onClear = onClearBatchSelection,
                            onSelectRange = onSelectEpisodeRange,
                            onRequestOrdinalRange = onRequestEpisodeRange,
                            onSelectLatest = onSelectLatestEpisodes,
                            onStart = onStartBatch,
                            onStartPage = onStartPageBatch,
                            onCancel = onCancelBatch,
                        )
                    }
                }

                items(
                    // Option C (DECISIONS 143.1): ONE context row per episode
                    // number — rows for audio values outside the current context
                    // are never separate top-level entries (SPEC §22).
                    items = rows,
                    // Unique by construction: EpisodeRepository deduplicates on
                    // (number, audio) and episode sessions come from the API —
                    // a composite key can never collide (no duplicate Compose key).
                    key = { "${it.number}|${it.audio}|${it.session}" },
                ) { episode ->
                    val overrideAudio = uiState.explicitAudioOverrides[episode.number]
                    EpisodeRow(
                        episode = episode,
                        selected = if (uiState.batchMode) {
                            // One context row per number: the highlight IS the
                            // number's selection (DECISIONS 143.1) — there is no
                            // second per-variant audio state to match against.
                            episode.number in uiState.selectedNumbers
                        } else {
                            uiState.selectedEpisode == episode
                        },
                        batchMode = uiState.batchMode,
                        onSelect = {
                            if (uiState.batchMode) {
                                onToggleBatchEpisode(episode)
                            } else {
                                onSelectEpisode(episode)
                            }
                        },
                        // Option C: the per-episode override surface. Only a
                        // number that REALLY offers several variants gets the
                        // control (SPEC §22 `getAllVariants(N)` > 1).
                        overrideAudio = overrideAudio,
                        severalVariants = episode.number in multiVariantNumbers,
                        audioOptions = uiState.audioOptions,
                        audioEnabled = !uiState.batchRunning,
                        onSetAudio = { audio ->
                            onSetEpisodeAudio(episode.number, audio)
                        },
                    )
                }
            }
        }
    }

    // the leave-a-running-batch confirmation.
    // Counts are the ViewModel's honest submitted-vs-pending snapshot —
    // dropped numbers are never presented as failed or skipped.
    if (uiState.leaveBatchWarning) {
        AlertDialog(
            onDismissRequest = onStayInBatch,
            modifier = Modifier.testTag("anime_details_leave_batch_dialog"),
            title = { Text("Leave batch?") },
            text = {
                Column {
                    Text(
                        text = "Submitted: ${uiState.leaveBatchSubmitted} · " +
                            "Not yet submitted: ${uiState.leaveBatchPending}",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Downloads already submitted keep running. " +
                            "Episodes not yet submitted will not be queued.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmLeaveBatch,
                    modifier = Modifier.testTag("anime_details_confirm_leave"),
                ) { Text("Leave") }
            },
            dismissButton = {
                TextButton(
                    onClick = onStayInBatch,
                    modifier = Modifier.testTag("anime_details_stay_batch"),
                ) { Text("Stay") }
            },
        )
    }
}

/**
 * The 11.5E-3 resolution panel — exactly ONE honest branch of
 * [AnimeDetailsUiState]: resolving / resolved / failed. Idle renders nothing at
 * all, and so does any state that matches no branch (never an empty card, which
 * would be a dead surface — AGENTS §5).
 *
 * 11.5E-4: the resolved branch also renders the queueing outcome of the Download
 * hand-off ([AnimeDetailsUiState.enqueuing] / [AnimeDetailsUiState.enqueueOutcome]
 * / [AnimeDetailsUiState.enqueueError]) — those flags are only ever non-default
 * while a stream is resolved, so they never create a branch of their own.
 *
 * Slice B (DECISIONS 146): the outcome itself decides the branch's terminal
 * rendering. A non-null [AnimeDetailsUiState.enqueueOutcome] renders the
 * repository's own message and WITHHOLDS the Download control, so a request the
 * pipeline suppressed (`AlreadyActive`, `AlreadyDownloaded`, `AlreadyPaused`,
 * `OutputMissing`) can no longer be shown as "In the download queue".
 */
@Composable
private fun ResolutionPanel(
    uiState: AnimeDetailsUiState,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onStream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = uiState.selectedEpisode ?: return

    when {
        uiState.resolving -> ResolutionCard(modifier = modifier) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("anime_details_resolution_resolving"),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Resolving stream for Episode ${episode.epStr}…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        uiState.resolutionError != null -> ResolutionCard(modifier = modifier) {
            Box(modifier = Modifier.testTag("anime_details_resolution_error")) {
                // Shared honest error branch, Retry bound to the same real
                // resolution call (§35) — no second copy of the panel.
                ErrorPanel(
                    message = uiState.resolutionError,
                    onRetry = onRetry,
                )
            }
        }

        uiState.resolvedStream != null -> ResolutionCard(modifier = modifier) {
            ResolvedStreamPanel(
                episode = episode,
                stream = uiState.resolvedStream,
                enqueuing = uiState.enqueuing,
                enqueueOutcome = uiState.enqueueOutcome,
                enqueueError = uiState.enqueueError,
                onDownload = onDownload,
                onStream = onStream,
            )
        }

        // Unreachable: a selection always starts as "resolving" (see
        // AnimeDetailsViewModel.startResolution) — and an unexplained state
        // renders no card at all rather than an empty one.
        else -> {}
    }
}

/** The shared card shell of a non-idle resolution branch. */
@Composable
private fun ResolutionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("anime_details_resolution_panel"),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * Success branch — the StreamInfo the resolver really returned: its page title,
 * the episode's audio/fansub metadata and the actual M3U8 URL. No invented
 * metadata, no cookies/user-agent (AGENTS §10).
 *
 * 11.5E-4: also carries the ONE downstream control that is really wired —
 * Download — and the three honest outcomes of that hand-off: disabled while the
 * repository call runs, an "in the queue" confirmation once it is persisted, or
 * the failure message with a retry-able button otherwise. Nothing is shown as
 * queued before the repository accepted it.
 */
@Composable
private fun ResolvedStreamPanel(
    episode: EpisodeInfo,
    stream: StreamInfo,
    enqueuing: Boolean,
    enqueueOutcome: EnqueueOutcome?,
    enqueueError: String?,
    onDownload: () -> Unit,
    onStream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(12.dp)
            .testTag("anime_details_resolution_success"),
    ) {
        Text(
            text = "Stream resolved",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "Episode ${episode.epStr}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (stream.title.isNotBlank()) {
            Text(
                text = stream.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val meta = listOfNotNull(
            stream.audio.takeIf { it.isNotBlank() },
            stream.fansub.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The slice's terminal output: the real playlist URL the resolver produced.
        Text(
            text = stream.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).testTag("anime_details_stream_url"),
        )

        // Slice B: the repository's verdict is rendered verbatim. A non-null
        // outcome means the request already reached a decision, so the Download
        // control is NOT offered for it — re-offering "Download" beneath "Already
        // downloaded" or "Paused — resume it from Downloads" would invite an
        // action the app either cannot take or must not take. The Stream control
        // below is unaffected and stays available in every branch.
        if (enqueueOutcome != null) {
            Text(
                text = enqueueNotice(enqueueOutcome),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("anime_details_download_notice"),
            )
        } else {
            if (enqueueError != null) {
                Text(
                    text = enqueueError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("anime_details_download_error"),
                )
            }
            Button(
                onClick = onDownload,
                // Never re-entrant: the hand-off is one repository call chain.
                enabled = !enqueuing,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("anime_details_download"),
            ) {
                if (enqueuing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Queueing…")
                } else {
                    Text(text = if (enqueueError == null) "Download" else "Retry download")
                }
            }
        }

        // direct HLS streaming of the SAME resolved episode. The
        // callback carries nothing but the episode id — the player re-resolves
        // just-in-time and rebuilds every header itself (§3.3), so this control
        // never hands a URL, a StreamInfo or any credential to navigation.
        Button(
            onClick = onStream,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("anime_details_stream"),
        ) {
            Text(text = "Stream")
        }
    }
}

/**
 * One selectable episode card — episode number always, then only metadata the
 * model actually carries (blank fields render NOTHING rather than an empty
 * label). Selecting it is a real action: it resolves that episode's stream.
 *
 * In batch mode the SAME card becomes a checkbox row (Role.Checkbox,
 * checked = this episode's NUMBER is selected) and tapping it toggles the batch
 * selection instead of resolving — the visual highlight is the selection state,
 * never an advertisement. Selection is by NUMBER: this row is the number's ONE
 * context row (DECISIONS 143.1), so the highlight needs no second audio state.
 *
 * Option C (DECISIONS 143.1, SPEC §22): a number that offers SEVERAL audio
 * variants additionally renders the explicit per-episode override control —
 * `Audio: <effective>` visibly marked `… · override` when it differs from the
 * screen-level context, plus a **Change audio** menu whose options are exactly
 * the screen-level DISCOVERED option set (DECISIONS 144.6 — the bounded probe's
 * union, or the listing fallback), never fetched ad hoc and never invented.
 * The control consumes its OWN taps (the menu opens in a popup
 * and the button is a nested clickable target, so the row's selection gesture
 * never fires), every target is at least 48dp, and the control is inert while
 * a batch is consuming the execution mapping (143 running-state guard).
 */
@Composable
private fun EpisodeRow(
    episode: EpisodeInfo,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    batchMode: Boolean = false,
    overrideAudio: String? = null,
    severalVariants: Boolean = false,
    audioOptions: List<String> = emptyList(),
    audioEnabled: Boolean = true,
    onSetAudio: (String) -> Unit = {},
) {
    // The number's "change audio" menu — local presentation state only.
    var audioMenuOpen by remember { mutableStateOf(false) }
    // Effective audio as the user must SEE it: the explicit override when one
    // exists, otherwise the row's own variant (which IS the context variant —
    // the defensive stale-override case still shows the override it carries so
    // it can never become hidden, inactive state).
    val effective = overrideAudio ?: episode.audio

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .selectable(
                selected = selected,
                role = if (batchMode) Role.Checkbox else Role.Button,
                onClick = onSelect,
            )
            // On the SAME node as the `selected` semantics, so tests can assert
            // which row is selected without depending on text merging.
            .testTag("anime_details_episode"),
        // Unselected rows keep the default Card colours; the selected row is
        // highlighted so the current selection is visible state, not a hint.
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (batchMode) {
                // Display-only: the parent row IS the toggle (one action, one
                // target), so a tap can never fire twice.
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Column(modifier = Modifier.padding(if (batchMode) 8.dp else 12.dp)) {
                Text(
                    text = "Episode ${episode.epStr}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (episode.title.isNotBlank()) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = listOfNotNull(
                    episode.audio.takeIf { it.isNotBlank() },
                    episode.fansub.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Option C: only a genuinely multi-variant number gets the
                // override control (single-variant numbers have nothing to
                // choose — a dead control would be a fake affordance, AGENTS
                // §5), and only while the discovered option set itself offers
                // more than one value to choose between.
                if (severalVariants && audioOptions.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp),
                    ) {
                        Text(
                            text = "Audio: " + effective.uppercase() +
                                (if (overrideAudio != null) " · override" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // The effective audio is the value execution will
                            // actually request — it must be visible, not implied.
                            modifier = Modifier.testTag("anime_details_episode_audio"),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Box {
                            TextButton(
                                onClick = { audioMenuOpen = true },
                                enabled = audioEnabled,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("anime_details_change_audio"),
                            ) {
                                Text(text = "Change audio")
                            }
                            DropdownMenu(
                                expanded = audioMenuOpen,
                                onDismissRequest = { audioMenuOpen = false },
                            ) {
                                audioOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(text = option.uppercase()) },
                                        onClick = {
                                            // Selection menu only: the tap lands
                                            // on the menu item (popup), never on
                                            // the row beneath it.
                                            audioMenuOpen = false
                                            onSetAudio(option)
                                        },
                                        leadingIcon = if (option == effective) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("anime_details_audio_option_$option"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lazy-loading slice (requirement 4, corrected) — the visible-page control.
 *
 * It renders ONLY real state: the APP page position and count (a
 * 250-episode title is three pages of 100 even though the server answers in
 * windows of 30), the source's own `total`, a spinner while the page is in
 * flight, and disabled edges at the ends. It never claims a page it was not
 * asked for and never fetches anything itself — every request goes through
 * `onGoToPage`.
 *
 * Direct numbered navigation (device acceptance): the pager shows the FIRST,
 * LAST and a small window of APP page numbers around the current one
 * ([EpisodePaging.pageNumberWindow] — `1 2 3 … 12`), each one directly
 * clickable, plus a page-number field whose Go only fires for a well-formed
 * integer inside `1..lastPage`. Zero, negative, blank, malformed and
 * out-of-range input never navigates: it stays unapplied and is reported
 * under the field, so the user is never silently moved to another page. Page
 * numbers here are APP pages, never API windows.
 *
 * An unknown `total` is stated as UNKNOWN rather than filled in with a derived
 * figure (and never rendered as a real zero).
 */
@Composable
private fun PageBar(
    page: Int,
    lastPage: Int,
    total: Int?,
    loading: Boolean,
    enabled: Boolean,
    onGoToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The typed page number is ephemeral UI state: it is validated here and
    // handed to the ViewModel only when it is a real page of this listing.
    var pageInput by rememberSaveable { mutableStateOf("") }
    var pageInputError by rememberSaveable { mutableStateOf<String?>(null) }
    val controlsEnabled = enabled && !loading

    // Responsive pager (Phase 12 UX correction): the number strip's AVAILABLE
    // width — the weight(1f) slot between Prev and Next — measured once after
    // layout. It is fixed by this Row (Prev and Next take their own space) and
    // is independent of whether the Go-to-page row below renders, so deriving
    // the button budget from it cannot feed back into the measurement: no
    // circular layout.
    var numbersSlotWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    // How many NUMBERS the strip fits at this width and the current font scale.
    // A wide window fits every page (no ellipsis → the Go-to-page field is pure
    // duplication and stays hidden); a narrow one collapses the window behind
    // ellipses, at which point the field appears.
    val maxButtons = pageNumberButtonBudget(
        slotWidth = numbersSlotWidth,
        lastPage = lastPage,
        fontScale = density.fontScale,
        fontSizeSp = MaterialTheme.typography.labelLarge.fontSize.value,
    )
    val numbers = EpisodePaging.pageNumberWindow(page, lastPage, maxButtons)
    // An ellipsis is rendered wherever two consecutive numbers are not adjacent
    // — which is EXACTLY when a page is omitted from the strip (the renderer
    // below uses the same predicate), so the field's visibility can never
    // disagree with what the pager actually shows.
    val hasEllipsis = numbers.zipWithNext().any { (a, b) -> b > a + 1 }
    // A rejection recorded while the field was visible must not survive under a
    // now-hidden field (e.g. the page count shrinks once the total is learned)
    // only to reappear later: clear it whenever the field is off screen.
    LaunchedEffect(hasEllipsis) {
        if (!hasEllipsis) pageInputError = null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("anime_details_page_bar"),
        ) {
            OutlinedButton(
                onClick = { onGoToPage(page - 1) },
                enabled = controlsEnabled && page > 1,
                modifier = Modifier.testTag("anime_details_page_prev"),
            ) { Text(text = "Prev") }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { size ->
                        numbersSlotWidth = with(density) { size.width.toDp() }
                    }
                    .horizontalScroll(rememberScrollState()),
            ) {
                var previous = 0
                numbers.forEach { number ->
                    if (previous != 0 && number > previous + 1) {
                        // The gap between two rendered numbers is an ellipsis,
                        // never a silent jump.
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    previous = number
                    if (number == page) {
                        // The current page is visibly identified by the filled
                        // button, and clicking it is a no-op (already there).
                        Button(
                            onClick = {},
                            enabled = controlsEnabled,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .padding(horizontal = 1.dp)
                                .testTag("anime_details_page_current"),
                        ) { Text(text = "$number") }
                    } else {
                        OutlinedButton(
                            onClick = { onGoToPage(number) },
                            enabled = controlsEnabled,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .padding(horizontal = 1.dp)
                                .testTag("anime_details_page_number_$number"),
                        ) { Text(text = "$number") }
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            OutlinedButton(
                onClick = { onGoToPage(page + 1) },
                enabled = controlsEnabled && page < lastPage,
                modifier = Modifier.testTag("anime_details_page_next"),
            ) { Text(text = "Next") }
        }
        Text(
            // The total is only ever the SOURCE's own number. An unknown total
            // is stated as unknown — never filled in with a derived figure, and
            // never confused with a real zero.
            text = if (total != null) {
                "Page $page of $lastPage · $total episodes"
            } else {
                "Page $page of $lastPage · total unknown"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("anime_details_page_indicator"),
        )
        // Go-to-page earns its permanently-pinned space only when a page is
        // genuinely omitted from the strip above (an ellipsis hides it). When
        // every page is already a visible button the field and its Go button
        // are pure duplication and are not composed at all — reclaiming the
        // vertical room for the episode list.
        if (hasEllipsis) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = {
                        pageInput = it
                        // Editing the field clears the previous rejection: the
                        // message always describes the text currently shown.
                        pageInputError = null
                    },
                    singleLine = true,
                    isError = pageInputError != null,
                    label = { Text(text = "Go to page") },
                    enabled = controlsEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("anime_details_page_input"),
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val requested = pageInput.trim().toIntOrNull()
                        pageInputError = when {
                            requested == null -> PAGE_INPUT_MALFORMED
                            requested < 1 || requested > lastPage -> "Enter a page between 1 and $lastPage"
                            else -> {
                                onGoToPage(requested)
                                null
                            }
                        }
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier.testTag("anime_details_page_go"),
                ) { Text(text = "Go") }
            }
            // The rejection is rendered as its OWN element (never merged into
            // the field's text, where a test or a screen reader could not tell
            // it from the typed value) and it names the valid bounds, so
            // invalid input is reported rather than silently unapplied.
            pageInputError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("anime_details_page_input_error"),
                )
            }
        }
    }
}

/** The page-number field's rejection of blank / non-integer input. */
internal const val PAGE_INPUT_MALFORMED = "Enter a whole page number"

/**
 * The floor (and pre-measurement fallback) for the pager's number budget — the
 * device-measured width that a phone shows today (DECISIONS 154.3), so a narrow
 * window keeps rendering exactly the window it does now and never collapses a
 * small page count behind an ellipsis it does not need.
 */
internal const val PAGE_NUMBER_BUDGET = 4

/**
 * How many page-number buttons the pager's strip can show at once for
 * [lastPage] pages in a slot [slotWidth] wide — the budget handed to
 * [EpisodePaging.pageNumberWindow].
 *
 * The per-button width is derived from the widest page label at the current
 * [fontScale] plus the button's horizontal padding and margin, so the budget
 * grows on a wide window (every page fits → no ellipsis → the Go-to-page field
 * is unnecessary) and shrinks on a narrow one (pages collapse behind an
 * ellipsis → the field appears). It reads no layout state, so it cannot feed
 * back into the measurement the caller performs; the caller measures the
 * strip's available width once, outside the strip.
 *
 * Before the strip is measured ([slotWidth] is zero) it returns the phone
 * floor, matching what a narrow window shows.
 */
internal fun pageNumberButtonBudget(
    slotWidth: Dp,
    lastPage: Int,
    fontScale: Float,
    fontSizeSp: Float,
): Int {
    if (lastPage <= 1) return lastPage.coerceAtLeast(1)
    if (slotWidth <= 0.dp) return PAGE_NUMBER_BUDGET
    // The widest page label at this font scale, plus the button's 16 dp content
    // padding and 2 dp margin — but Material buttons never shrink below their
    // minimum interactive width, so the real pitch is the larger of the two.
    // This is the width a budget of 5 would have pushed past the row's edge
    // (DECISIONS 154.3): only ~4 real buttons fit a phone strip, which is why
    // the estimate must honour the minimum, not just the text.
    val widestLabel = (fontSizeSp * fontScale * 0.55f * lastPage.toString().length).dp
    val perButton = maxOf(ButtonDefaults.MinWidth, widestLabel + 16.dp) + 2.dp
    // An ellipsis stands in for the omitted run it replaces.
    val ellipsis = (fontSizeSp * fontScale).dp + 8.dp
    // Every page fits with no omission → show all of them (no ellipsis).
    if (perButton * lastPage <= slotWidth) return lastPage
    // Otherwise collapse to what fits WITH up to two ellipses, never below the
    // phone floor.
    val fits = ((slotWidth - ellipsis * 2) / perButton).toInt()
    return fits.coerceAtLeast(PAGE_NUMBER_BUDGET)
}

/**
 * Phase 12C batch panel — the minimal surface that makes batch behavior
 * observable (a limited Phase 13 prerequisite, not a new design language).
 *
 * Everything it renders is real state from [AnimeDetailsUiState]:
 *
 * * the selection is the set of episode NUMBERS the user picked;
 * * **Select all** / **Clear** run the existing
 *   [com.pahebatcher.app.domain.util.EpisodeRangeParser] through the ViewModel
 *   (Python `--all`);
 * * the **From** and **To** range control is the single ordinal range selector:
 *   it fetches only the windows a `[from, to]` span needs (and, when the
 *   listing exposes no total, falls back to the parser as a loaded-catalogue
 *   number range). It replaces the older duplicate expression field that made
 *   the two range controls confusing;
 * * the **Latest** field + button select the newest N numbers through the SAME
 *   parser (`--latest N`), feeding the SAME selection state (SPEC §22);
 * * **Download selected** starts [AnimeDetailsViewModel.startBatchDownload] —
 *   but only when the selection is NOT exactly the current page. When a bulk
 *   action selects precisely this page's numbers, only **Download this page**
 *   (the more robust, page-scoped path) is shown, so two identical primary CTAs
 *   are never presented for the same set;
 * * while a run is active, the live queued/skipped/failed counters and a real
 *   **Cancel**;
 * * after a run, the same counters as the summary plus the batch-level error
 *   when the run had to stop.
 *
 * No fabricated progress: `batchQueued` only counts episodes the queue really
 * accepted, `batchSkipped` only episodes it really did not start a Worker for
 * (already covered, or declined outright — see `batchSkipped`'s own contract).
 */
@Composable
private fun BatchPanel(
    uiState: AnimeDetailsUiState,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSelectRange: (String) -> Unit,
    onRequestOrdinalRange: (Int, Int) -> Unit,
    onSelectLatest: (Int) -> Unit,
    onStart: () -> Unit,
    onStartPage: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var latestText by rememberSaveable { mutableStateOf("") }
    // Lazy-loading slice (requirement C) — the bounded ordinal range selector,
    // now the ONLY range control on the panel (the older expression field was
    // removed as a confusing duplicate). Two plain ordinal bounds, parsed once
    // here so the button's enabled state and the value handed to the ViewModel
    // always agree: an empty, zero or non-numeric bound is simply not
    // actionable, and every semantic rejection (reversed, out of bounds, over
    // 100, unknown total) is the ViewModel's to report — the screen never
    // pre-judges it into silence.
    var rangeFromText by rememberSaveable { mutableStateOf("") }
    var rangeToText by rememberSaveable { mutableStateOf("") }
    val rangeFrom = rangeFromText.trim().toIntOrNull()
    val rangeTo = rangeToText.trim().toIntOrNull()
    val ordinalRangeEnabled = !uiState.batchRunning &&
        !uiState.pageLoading &&
        rangeFrom != null && rangeFrom >= 1 &&
        rangeTo != null && rangeTo >= 1
    // Parsed ONCE here: the button's enabled state and the value handed to the
    // ViewModel must agree, so an empty/0/negative/non-numeric entry is simply
    // not actionable instead of silently selecting episode 1.
    val latestN = latestText.trim().toIntOrNull()
    val latestEnabled = !uiState.batchRunning && latestN != null && latestN >= 1
    val selected = uiState.selectedNumbers.size
    // Page-scoped batch (requirement 4) — the DISTINCT episode numbers the
    // page on screen really offers right now. The button's label is built from
    // this same number, so it can never promise more than the execution path
    // queues (audio variants of one number are ONE episode, exactly as the
    // batch ceiling counts them).
    val onPageNumbers = displayEpisodeEpisodes(uiState).map { it.number }.distinct().toSet()
    val onPage = onPageNumbers.size
    // Page vs selection: the two download CTAs only differ when the selection
    // is NOT exactly the current page. When a bulk action ("Select all" on page
    // 1, or a whole-page Range) selects precisely this page's numbers,
    // "Download selected" would queue the identical set as "Download this page"
    // — so only the page-scoped button (the more robust execution path, with
    // its page guard and source-row merge) is shown, removing the duplicate
    // primary CTA without losing either workflow. Any other selection (a
    // subset, or a cross-page set) still shows both.
    val selectionIsExactlyThePage = selected > 0 && uiState.selectedNumbers == onPageNumbers
    // Option C (DECISIONS 143): the denominator is the number of rows the
    // CURRENT context offers — every selectable number, one row each. A number
    // the context does not carry is not part of this batch at all, so it is
    // never counted as a "missing" selection.
    //
    // Lazy-loading slice: with an ELIGIBLE window known, that window is the
    // denominator (a range can legitimately select 100 episodes while only one
    // page of them is on screen, and "100 of 30" would be a lie). Without one,
    // it falls back to the rendered rows exactly as before.
    val total = eligibleOrdinalEnd(uiState)
        ?: displayEpisodeRows(uiState).size

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("anime_details_batch_panel"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Batch download",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "$selected of $total episode numbers selected",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp).testTag("anime_details_batch_count"),
            )
            // SPEC §22 — "a clear selection summary before downloading". The
            // count alone cannot show WHICH numbers a multi-page row-tap
            // accumulation holds (selection survives paging), so the selected
            // numbers are named here, sorted and range-collapsed. An empty
            // selection adds nothing extra.
            if (selected > 0) {
                Text(
                    text = "Selected: " + compactSelectedNumbers(uiState.selectedNumbers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .testTag("anime_details_batch_selection_summary"),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                // "Select all" is a SELECTION action (SPEC §22 "all episodes"),
                // never a download: it fills the eligible window (up to 100
                // ordinals, or a whole ≤100-episode title across its app pages)
                // so the user can prune and then press "Download selected".
                // Relabelled from the bare "All" so its selection-only role
                // cannot be read as a download and is not confused with
                // "Download this page" (which queues the current page in one
                // step).
                OutlinedButton(
                    onClick = onSelectAll,
                    enabled = !uiState.batchRunning,
                    modifier = Modifier.testTag("anime_details_batch_all"),
                ) { Text(text = "Select all") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onClear,
                    enabled = !uiState.batchRunning && selected > 0,
                    modifier = Modifier.testTag("anime_details_batch_clear"),
                ) { Text(text = "Clear") }
            }

            // Consolidated range control (Phase 12 UX correction). The panel
            // previously ALSO carried an expression field ("Range e.g. 1-12",
            // `selectEpisodeRange`) beside this ordinal selector, duplicating the
            // "pick a range" operation with confusingly similar labels. The
            // ordinal selector below is strictly better for that job: it validates
            // against the listing metadata and FETCHES only the windows the range
            // spans, so it works for unloaded pages where the expression field
            // silently matched nothing. The expression field is removed from the UI.
            //
            // The range selector is expressed in ORDINALS of the API's stable
            // `episode_asc` ordering rather than in episode numbers: only a
            // position maps deterministically to pages, so this is what lets a
            // range far down a 1000-episode title fetch only the two or three
            // pages it spans. It validates (≤ 100, in bounds, known total) and
            // fetches in the ViewModel; rejections render as `batchError` below
            // without touching the current selection.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = rangeFromText,
                    onValueChange = { rangeFromText = it },
                    singleLine = true,
                    label = { Text(text = "From") },
                    enabled = !uiState.batchRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("anime_details_batch_ordinal_from"),
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = rangeToText,
                    onValueChange = { rangeToText = it },
                    singleLine = true,
                    label = { Text(text = "To") },
                    enabled = !uiState.batchRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("anime_details_batch_ordinal_to"),
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val from = rangeFrom
                        val to = rangeTo
                        if (from != null && to != null) {
                            if (uiState.apiTotal == null) {
                                // Unknown total: the ordinal path cannot validate
                                // or fetch windows without a total
                                // (`EpisodePaging.validateRange` reports
                                // `UnknownTotal`), so the SAME bounds fall back to
                                // the tested `selectEpisodeRange` as a loaded-
                                // catalogue NUMBER range (SPEC §22's `1-12`).
                                // Without this a title whose API omits pagination
                                // metadata would have NO range affordance at all.
                                onSelectRange("$from-$to")
                            } else {
                                onRequestOrdinalRange(from, to)
                            }
                        }
                    },
                    enabled = ordinalRangeEnabled,
                    modifier = Modifier.testTag("anime_details_batch_ordinal_apply"),
                ) { Text(text = "Range") }
            }

            // Honest label: what "From"/"To" MEAN depends on whether the listing
            // exposes a total. Positions (the normal case) are fetched; without a
            // total they fall back to numbers over loaded pages. Users are never
            // left guessing which meaning the fields carry right now.
            Text(
                text = if (uiState.apiTotal == null) {
                    "Episode numbers, e.g. 1-12 · selects from loaded pages"
                } else {
                    "Episode positions 1-100 · unloaded pages are fetched automatically"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("anime_details_batch_ordinal_help"),
            )

            // SPEC §22 — "newest N". Same selection set, same parser; only the
            // entry is new. Disabled while a batch runs (like every other
            // selection control) and while the entry cannot yield n >= 1.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = latestText,
                    onValueChange = { latestText = it },
                    singleLine = true,
                    label = { Text(text = "Latest N") },
                    enabled = !uiState.batchRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("anime_details_batch_latest"),
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { latestN?.let(onSelectLatest) },
                    enabled = latestEnabled,
                    modifier = Modifier.testTag("anime_details_batch_latest_apply"),
                ) { Text(text = "Latest") }
            }

            if (uiState.batchRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Queued ${uiState.batchQueued} · Skipped ${uiState.batchSkipped} · " +
                            "Failed ${uiState.batchFailed} of ${uiState.batchTotal}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("anime_details_batch_progress"),
                    )
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = 8.dp).testTag("anime_details_batch_cancel"),
                ) { Text(text = "Stop batch") }
            } else {
                if (uiState.batchFinished) {
                    Text(
                        text = "Done: queued ${uiState.batchQueued} · skipped ${uiState.batchSkipped} · " +
                            "failed ${uiState.batchFailed} of ${uiState.batchTotal}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp).testTag("anime_details_batch_summary"),
                    )
                    // Slice C: the per-episode reasons behind `failed`. The count
                    // alone never said WHICH episode failed, WHICH audio was
                    // requested, or WHY — and left a refusal indistinguishable
                    // from a resolution failure.
                    if (uiState.batchFailures.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            uiState.batchFailures.forEach { failure ->
                                Text(
                                    text = batchFailureText(failure),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag(
                                        "anime_details_batch_failure_${failure.episodeStr}",
                                    ),
                                )
                            }
                        }
                    }
                }
                if (uiState.batchError != null) {
                    Text(
                        text = uiState.batchError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp).testTag("anime_details_batch_error"),
                    )
                }
                // Page-scoped batch (requirement 4) — the action that queues
                // EXACTLY this page, stated in full: the count is the distinct
                // episodes the page offers right now, and the scope is the page
                // itself, so it can never be read as "the first 100 episodes"
                // nor as "everything currently loaded in memory".
                Button(
                    onClick = onStartPage,
                    enabled = !uiState.batchRunning && onPage > 0,
                    modifier = Modifier.padding(top = 8.dp).testTag("anime_details_batch_page_download"),
                ) {
                    Text(
                        text = "Download this page ($onPage episode" +
                            (if (onPage == 1) ")" else "s)"),
                    )
                }
                if (!selectionIsExactlyThePage) {
                    Button(
                        onClick = onStart,
                        enabled = selected > 0,
                        modifier = Modifier.padding(top = 8.dp).testTag("anime_details_batch_download"),
                    ) {
                        Text(text = "Download selected ($selected)")
                    }
                }
            }
        }
    }
}

/**
 * Slice C — one rendered batch failure.
 *
 * Reads as **identity, then cause**: the episode number first (what failed),
 * the REQUESTED audio second when one was asked for (which variant failed),
 * then the category label and the reason.
 *
 * [FailureCategory.UNKNOWN] has no label, so it renders only its reason — a
 * failure the app cannot attribute must never be dressed up as one it can.
 * When the label and the reason are the same sentence, the duplicate is shown
 * once.
 */
private fun batchFailureText(failure: BatchEpisodeFailure): String {
    val identity = listOfNotNull("Ep ${failure.episodeStr}", failure.audioLabel)
        .joinToString(" · ")
    val detail = listOfNotNull(failure.category.label, failure.reason)
        .distinct()
        .joinToString(" — ")
    return "$identity · $detail"
}

/**
 * Render one episode number without a trailing `.0` (a whole number reads as
 * `13`, a special as `13.5`). A2.1 (UI-004): delegates to the ONE
 * `StringUtils.episodeNumber` renderer this helper previously only mirrored.
 */
private fun renderEpisodeNumber(number: Float): String = StringUtils.episodeNumber(number)

/**
 * Collapse a set of selected episode numbers into a readable summary, e.g.
 * `{1, 2, 3, 5, 7, 8, 9}` -> `"1–3, 5, 7–9"`. This backs SPEC §22's "a clear
 * selection summary before downloading": the bare "N of M" count cannot show
 * WHICH numbers a multi-page row-tap accumulation actually holds (selection
 * survives paging), so the panel names them — sorted and range-collapsed, the
 * same compaction Python's `compact_ep_range` performs for its summary.
 */
private fun compactSelectedNumbers(numbers: Set<Float>): String {
    val sorted = numbers.sorted()
    if (sorted.isEmpty()) return "none"
    if (sorted.size == 1) return renderEpisodeNumber(sorted[0])
    data class Run(val start: Float, val end: Float)
    val runs = mutableListOf<Run>()
    var start = sorted[0]
    var previous = sorted[0]
    for (n in sorted.drop(1)) {
        if (n == previous + 1f) {
            previous = n
        } else {
            runs.add(Run(start, previous))
            start = n
            previous = n
        }
    }
    runs.add(Run(start, previous))
    return runs.joinToString(", ") { (a, b) ->
        if (a == b) renderEpisodeNumber(a)
        else "${renderEpisodeNumber(a)}–${renderEpisodeNumber(b)}"
    }
}

/**
 * Post-audio slice — the honest one-line account of WHERE the screen's audio
 * option set came from.
 *
 * One sentence per [AudioProbeState], never merged: a COMPLETED probe
 * ([AudioProbeState.OK_VALUES] / [AudioProbeState.OK_EMPTY]) is reported as
 * sampled, while a degraded or absent one ([AudioProbeState.PARTIAL] /
 * [AudioProbeState.FAILED] / [AudioProbeState.NOT_PROBED]) says plainly that
 * the shown languages come from the episode LISTING — the fallback — and are
 * therefore not a confirmed audio set.
 *
 * Two claims this wording deliberately does NOT make: that the sample covers
 * every episode (it is representative by construction — DECISIONS 144.3), and
 * that a failed check means an audio is missing (it means only that no page
 * could be fetched).
 */
internal fun probeStatusMessage(state: AudioProbeState): String = when (state) {
    AudioProbeState.OK_VALUES ->
        "Audio options sampled from representative episodes — other episodes may offer more languages."

    AudioProbeState.OK_EMPTY ->
        "Sampled pages listed no audio options — showing languages from the episode list."

    AudioProbeState.PARTIAL ->
        "Only some sampled pages loaded — the audio list may be incomplete."

    AudioProbeState.FAILED ->
        "Couldn't check audio on the sampled pages — showing languages from the episode list."

    AudioProbeState.NOT_PROBED ->
        "Audio not checked for this title — showing languages from the episode list."
}

/** Shown instead of the state message while a user-triggered re-probe runs. */
internal const val PROBE_RUNNING_MESSAGE =
    "Checking audio on representative episodes…"

/** The two states a Retry can actually do something for (see `retryAudioProbe`). */
internal fun AudioProbeState.retryable(): Boolean =
    this == AudioProbeState.FAILED || this == AudioProbeState.PARTIAL

/**
 * Post-audio slice — the probe status line under the audio selector.
 *
 * Pure state rendering: it never starts work itself, and the Retry is only
 * rendered for the two states where a bounded re-probe is meaningful
 * ([retryable]) — a completed result costs zero requests and a title with no
 * probe targets cannot be probed at all, so offering the button there would be
 * a control that lies about what it does. While [probing] the button is absent
 * (the in-flight pass already owns the tap) and a small indicator says network
 * work is happening on an otherwise fully loaded screen. [enabled] mirrors the
 * selector's own running guard: while a batch is running the affordance stays
 * visible but inert, because the execution mapping that batch is running with
 * must not be rewritten under it (DECISIONS 143).
 */
@Composable
private fun AudioProbeStatus(
    state: AudioProbeState,
    probing: Boolean,
    enabled: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("anime_details_probe_status"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (probing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .testTag("anime_details_probe_running"),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (probing) PROBE_RUNNING_MESSAGE else probeStatusMessage(state),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("anime_details_probe_message"),
            )
        }
        if (!probing && state.retryable()) {
            TextButton(
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.testTag("anime_details_probe_retry"),
            ) {
                Text(text = "Retry audio check")
            }
        }
    }
}

/**
 * Option C (DECISIONS 143.1, SPEC §22) — the screen-level audio context
 * selector.
 *
 * * options = [audios], the OPEN set the probe discovered for this title (or
 *   the release-listing fallback), in first-seen order — never a hard-coded
 *   pair, never an "original" label, and never a value the title does not
 *   offer;
 * * the selected value is visibly marked (FilterChip `selected`) and each
 *   option is its own accessible node (`Audio <value>` content description,
 *   ≥48dp touch target);
 * * the whole row is inert while a batch is running (DECISIONS 143 running
 *   guard) — the execution mapping frozen at start cannot change under it;
 * * a tap hands only the RAW value to the ViewModel: the coherent transition
 *   (adopt the context, clear overrides, KEEP every selected number) is owned
 *   there, so this composable never mutates state itself.
 */
@Composable
private fun AudioContextSelector(
    audios: List<String>,
    selectedAudio: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (audios.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("anime_details_audio_selector"),
    ) {
        Text(
            text = "Audio",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(end = 8.dp),
        )
        audios.forEach { audio ->
            FilterChip(
                selected = audio == selectedAudio,
                onClick = { onSelect(audio) },
                enabled = enabled,
                label = { Text(text = audio.uppercase()) },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .height(48.dp)
                    .semantics { contentDescription = "Audio $audio" }
                    .testTag("anime_details_audio_chip_$audio"),
            )
        }
    }
}
