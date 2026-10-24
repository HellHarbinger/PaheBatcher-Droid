package com.pahebatcher.app.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.R
import com.pahebatcher.app.ui.details.AnimeDetailsScreen
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import com.pahebatcher.app.ui.console.ConsoleScreen
import com.pahebatcher.app.ui.console.ConsoleViewModel
import com.pahebatcher.app.ui.downloads.DownloadsScreen
import com.pahebatcher.app.ui.downloads.DownloadsViewModel
import com.pahebatcher.app.ui.home.HomeScreen
import com.pahebatcher.app.ui.home.HomeViewModel
import com.pahebatcher.app.ui.player.PlayerScreen
import com.pahebatcher.app.ui.player.PlayerViewModel
import com.pahebatcher.app.ui.sessions.SessionsScreen
import com.pahebatcher.app.ui.sessions.SessionsViewModel
import com.pahebatcher.app.ui.settings.SettingsScreen
import com.pahebatcher.app.ui.settings.SettingsViewModel
import com.pahebatcher.app.ui.watchlist.WatchlistScreen
import com.pahebatcher.app.ui.watchlist.WatchlistViewModel

/**
 * Flat single-`NavHost` application shell (PHASE11_ARCHITECTURE_V2 §10).
 *
 * - Bottom `NavigationBar` contains EXACTLY two destinations: Home and Downloads
 *   (§10.2). Settings is pushed from the Home top app bar — never a bottom tab.
 * - Tab switches use the shared [navigateToTab] rule (§10.4): saveState /
 *   restoreState / launchSingleTop, so per-tab stacks are retained and repeat
 *   taps never duplicate destinations.
 * - Tab switches clear focus (and thus hide the IME) so the keyboard never
 *   survives navigation (§14.1).
 * - Screens are stateless: they receive UiState + lambdas only; ViewModels are
 *   resolved nav-entry-scoped via [viewModelFactory] (§11, §34).
 * - The player destination hides the bottom bar (§10.1) and is registered by the
 *   player slice; ValidationScreen is NOT a route (§31) — it stays an overlay
 *   entry owned by MainActivity.
 *
 * @param pendingNavTarget consumed `navigate_to` deep-link value (§19); non-null
 *   values trigger the §19.3 single-top navigation exactly once, then are cleared.
 */
@OptIn(markerClass = [UnstableApi::class])
@Composable
fun AppNavHost(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    pendingNavTarget: String?,
    onNavTargetConsumed: () -> Unit,
    onOpenValidation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // the notification row's "Open settings" hands the
    // user to the SYSTEM notification settings for this app.
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()

    // `Replace file` (DECISIONS 152): the episode whose confirmation dialog was
    // requested from a notification action. Held HERE rather than inside the
    // Downloads entry so that arriving from the Home tab, from the player, or
    // from a cold start all converge on the same one-shot request. It is cleared
    // by the entry below as soon as it has been handed to the ViewModel, so a
    // configuration change, a tab switch or a process recreation can never
    // re-open a dialog the user already dismissed.
    var pendingReplacementEpisodeId by remember { mutableStateOf<String?>(null) }

    fun clearFocusAndHideIme() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    // §19: only the single known value navigates; unknown/absent values are
    // discarded without navigation or crash (plain app open).
    androidx.compose.runtime.LaunchedEffect(pendingNavTarget, navController) {
        if (pendingNavTarget != null) {
            if (pendingNavTarget == "downloads") {
                clearFocusAndHideIme()
                // §36-5 player case: a notification tap while the player is top
                // must pop the player FIRST (releasing its player, §24.3 exit
                // path) before the single-top tab navigation, so the stack
                // converges on [Home, Downloads] without stacking.
                if (backStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
                    navController.popBackStack()
                }
                navController.navigateToTab(DownloadsRoute)
            }
            // the watchlist notification's
            // tap-to-open destination. Same `EXTRA_NAVIGATE_TO` contract; the
            // player is popped FIRST because nothing may push over the player
            // entry (§10.1), then WatchlistRoute is pushed exactly the way the
            // Home top bar pushes it (§10.2 — never a bottom tab).
            if (pendingNavTarget == "watchlist") {
                clearFocusAndHideIme()
                if (backStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
                    navController.popBackStack()
                }
                navController.navigate(WatchlistRoute) { launchSingleTop = true }
            }
            // MediaSession tap-to-open destination.
            // Encoded as "player:<episodeId>:<mode>" per MainActivity's contract
            // (PlayerTarget, AQR-138).
            val playerTarget = PlayerTarget.parse(pendingNavTarget)
            if (playerTarget != null) {
                val episodeId = playerTarget.episodeId
                clearFocusAndHideIme()
                // Blank/unparseable id or an unsupported mode token → default
                // routing, never a crash (DECISIONS 118.2, PHASE14_PLAN §7):
                // PlayerTarget.parse rejects both, and the target is CONSUMED
                // without navigation — exactly how §19.1 discards unknown
                // values. No PlayerRoute(""/"   ") (which could only render an
                // error screen) is ever created, cold start lands on the start
                // destination, and the consume-once rule guarantees the Activity
                // never re-processes the value.
                val top = backStackEntry
                // Same episode already top → no-op: no popBackStack() and no
                // re-navigation, so the entry, its PlayerViewModel, its
                // PlayerMode and its live player are all untouched
                // (PHASE11_ARCHITECTURE_V2 §24.2 re-entry — at most one player
                // entry; a STREAM session's tap keeps STREAM, and a target whose
                // mode differs from the running one does NOT switch it).
                val sameEpisodeOnTop = top != null &&
                    top.destination.hasRoute<PlayerRoute>() &&
                    top.toRoute<PlayerRoute>().episodeId == episodeId
                if (!sameEpisodeOnTop) {
                    // Player not top, or a different episode: navigateToPlayer's
                    // own popUpTo(player) + launchSingleTop replace-in-place
                    // (§24.2) is the ONE stack mechanism. The previous manual
                    // popBackStack() here released and recreated the player on
                    // every tap, including same-episode taps.
                    // AQR-138: the mode travels EXPLICITLY from the target — the
                    // route's DOWNLOAD default is never what a notification
                    // depends on.
                    navController.navigateToPlayer(episodeId, playerTarget.mode)
                }
            }
            // `Replace file` (DECISIONS 152): "replace:<episodeId>" opens the
            // Downloads tab with that episode's confirmation request armed.
            //
            // This branch ONLY routes. Nothing is deleted, enqueued or
            // overwritten here — the dialog, and only the dialog, decides what
            // happens next, and the request is delivered by id so the ViewModel
            // can re-validate it against Room before anything destructive is
            // ever offered to the user.
            if (pendingNavTarget.startsWith(MainActivity.NAV_TARGET_REPLACE_PREFIX)) {
                clearFocusAndHideIme()
                // Same single-top tab convergence as the plain "downloads"
                // target (§10.4), including the §36-5 pop-first rule so a tap
                // arriving while the player is top cannot stack over it.
                if (backStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
                    navController.popBackStack()
                }
                // AQR-140: land Downloads at its ROOT, never at a restored
                // nested destination (e.g. Sessions). The delivery hook below
                // lives inside composable<DownloadsRoute>, so the request is
                // handed to the ViewModel only once this tab is the ACTIVE
                // destination — the confirmation dialog can never be armed
                // behind a screen the user has to leave manually, and it is
                // never deferred indefinitely.
                //
                // AQR-140 fix: navigating while Downloads is ALREADY the active
                // destination pops-and-recreates its entry, and the dying
                // composition's delivery hook can consume the pending id before
                // the fresh composition observes it — arming a discarded
                // ViewModel instead of the live one. Skipping the navigation in
                // that case keeps the live entry-scoped ViewModel, whose hook
                // delivers the request to the screen actually on top.
                val topDestination = navController.currentBackStackEntry?.destination
                if (topDestination?.hasRoute<DownloadsRoute>() != true) {
                    navController.navigateToDownloadsForReplacement()
                }
                pendingReplacementEpisodeId =
                    pendingNavTarget.substringAfter(MainActivity.NAV_TARGET_REPLACE_PREFIX)
            }
            onNavTargetConsumed()
        }
    }

    Scaffold(
        modifier = modifier,
        // §10.1: the bottom bar exists on every route EXCEPT the player —
        // hidden while a player entry is top (also what keeps the invariant
        // that nothing can push over the player in v1).
        bottomBar = {
            if (backStackEntry?.destination?.hasRoute<PlayerRoute>() != true) {
                NavigationBar {
                    NavigationBarItem(
                        selected = backStackEntry?.destination?.hasRoute<HomeRoute>() == true,
                        onClick = {
                            clearFocusAndHideIme()
                            navController.navigateToTab(HomeRoute)
                        },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = backStackEntry?.destination?.hasRoute<DownloadsRoute>() == true,
                        onClick = {
                            clearFocusAndHideIme()
                            navController.navigateToTab(DownloadsRoute)
                        },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_stat_download),
                                contentDescription = null,
                            )
                        },
                        label = { Text("Downloads") },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier
                .padding(innerPadding)
                // The bottom bar + nav-bar insets are already applied here — consume
                // them so per-screen Scaffolds do not double-apply (§4 edge-to-edge).
                .consumeWindowInsets(innerPadding),
        ) {
            composable<HomeRoute> {
                val viewModel: HomeViewModel = viewModel(factory = viewModelFactory)
                val searchState by viewModel.searchState.collectAsStateWithLifecycle()
                HomeScreen(
                    searchState = searchState,
                    onQueryChange = viewModel::onQueryChange,
                    onSubmitSearch = viewModel::submitSearch,
                    onRetrySearch = viewModel::submitSearch,
                    onOpenSettings = {
                        clearFocusAndHideIme()
                        navController.navigate(SettingsRoute) { launchSingleTop = true }
                    },
                    onOpenDownloads = {
                        clearFocusAndHideIme()
                        navController.navigateToTab(DownloadsRoute)
                    },
                    // 11.5E-2: the tapped row's session IS the route argument.
                    onOpenDetails = { session ->
                        clearFocusAndHideIme()
                        navController.navigate(AnimeDetailsRoute(session)) {
                            launchSingleTop = true
                        }
                    },
                    // watchlist list surface (top-bar push, §10.2).
                    onOpenWatchlist = {
                        clearFocusAndHideIme()
                        navController.navigate(WatchlistRoute) { launchSingleTop = true }
                    },
                    onLongPress = onOpenValidation,
                )
            }

            // 11.5E-2 anime details — ID-only `session` argument; the ViewModel
            // re-reads title/episodes through EpisodeRepository (never carried in
            // the back stack). AnimeDetailsViewModel.load is idempotent for the
            // session already loading/loaded, so this effect re-running on
            // recreation never discards a rendered episode list or repeats the scan.
            composable<AnimeDetailsRoute> { entry ->
                val route = entry.toRoute<AnimeDetailsRoute>()
                val viewModel: AnimeDetailsViewModel = viewModel(factory = viewModelFactory)
                LaunchedEffect(route.session) { viewModel.load(route.session) }
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                AnimeDetailsScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onRetry = viewModel::retry,
                    // 11.5E-3: a real episode selection resolves through the
                    // production StreamResolver bound by AppContainer.
                    onSelectEpisode = viewModel::selectEpisode,
                    onRetryResolution = viewModel::retryResolution,
                    // 11.5E-4: the resolved stream is handed to the EXISTING
                    // download pipeline (enqueue → resolveComplete → WorkManager).
                    onDownload = viewModel::downloadResolvedEpisode,
                    // direct HLS streaming. Navigation carries ONLY the
                    // episode id + PlayerMode.STREAM — never the play page URL,
                    // never the resolved StreamInfo, never any header.
                    onStream = {
                        clearFocusAndHideIme()
                        viewModel.streamEpisodeId()?.let { episodeId ->
                            navController.navigateToPlayer(episodeId, PlayerMode.STREAM)
                        }
                    },
                    // batch selection → batch resolution through the
                    // SAME resolver and queue (minimal Phase 13 surface).
                    onToggleBatchMode = { viewModel.setBatchMode(!uiState.batchMode) },
                    onToggleBatchEpisode = viewModel::toggleBatchEpisode,
                    onSelectAllEpisodes = viewModel::selectAllEpisodes,
                    onClearBatchSelection = viewModel::clearBatchSelection,
                    onSelectEpisodeRange = viewModel::selectEpisodeRange,
                    // Lazy-loading slice: the SEPARATE bounded ordinal range
                    // selector and the visible-page pager. Both are ViewModel
                    // transitions, so the details route contract still gains no
                    // new argument.
                    onRequestEpisodeRange = viewModel::requestEpisodeRange,
                    onGoToPage = viewModel::goToPage,
                    // SPEC §22: newest-N selection — same ViewModel state, so
                    // the details route contract gains no new argument.
                    onSelectLatestEpisodes = viewModel::selectLatestEpisodes,
                    onStartBatch = viewModel::startBatchDownload,
                    // Page-scoped batch: queue exactly the app page on screen.
                    // A separate ViewModel transition, so the details route
                    // contract still gains no new argument.
                    onStartPageBatch = viewModel::startPageBatchDownload,
                    onCancelBatch = viewModel::cancelBatch,
                    // Option C (DECISIONS 143, SPEC §22): the screen-level audio
                    // context change and ONE number's explicit override — both
                    // are single-writer ViewModel transitions; navigation only
                    // wires the raw values through.
                    onSelectAudio = viewModel::setSelectedAudio,
                    onSetEpisodeAudio = viewModel::setEpisodeAudio,
                    // back while a batch runs asks
                    // first; confirming cancels the submission loop (rows
                    // already in WorkManager keep running) and then pops.
                    onRequestLeaveBatch = viewModel::requestLeaveBatchWarning,
                    onStayInBatch = viewModel::dismissLeaveBatchWarning,
                    onConfirmLeaveBatch = {
                        viewModel.confirmLeaveBatch()
                        navController.popBackStack()
                    },
                    // the one-time prompt's real answer.
                    onNotificationPermissionResult = viewModel::onNotificationPermissionResult,
                    // Post-audio slice: the bounded re-probe from this loaded
                    // screen. Deliberately NOT routed through `load` — the load
                    // guard makes that a no-op here — and deliberately not in a
                    // LaunchedEffect, so navigation/recomposition can never fire
                    // a probe on their own.
                    onRetryProbe = viewModel::retryAudioProbe,
                )
            }

            composable<DownloadsRoute> {
                val viewModel: DownloadsViewModel = viewModel(factory = viewModelFactory)
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                // Final-audit H5 (SPEC §26): a failed Cancel/Retry/Pause/Resume
                // must reach the user — the banner mirrors the ViewModel's
                // actionError until the user dismisses it.
                val actionError by viewModel.actionError.collectAsStateWithLifecycle()
                // `Replace file` (DECISIONS 152): the open confirmation dialog.
                val replacementTarget by viewModel.replacementTarget.collectAsStateWithLifecycle()
                // One-shot delivery of the notification action's request: handed
                // to the ViewModel exactly once, then cleared here so nothing
                // can re-open the dialog on a recomposition, a rotation, or a
                // return to this tab.
                LaunchedEffect(pendingReplacementEpisodeId) {
                    val requested = pendingReplacementEpisodeId ?: return@LaunchedEffect
                    pendingReplacementEpisodeId = null
                    viewModel.requestReplacement(requested)
                }
                DownloadsScreen(
                    uiState = uiState,
                    onCancel = viewModel::cancel,
                    onRetry = viewModel::retry,
                    // §24.2: Play (COMPLETED rows only — the §17 matrix already
                    // gates it) opens the single player entry by id.
                    onPlay = { episodeId ->
                        clearFocusAndHideIme()
                        navController.navigateToPlayer(episodeId)
                    },
                    // Session Manager entry.
                    onOpenSessions = {
                        clearFocusAndHideIme()
                        navController.navigate(SessionsRoute) { launchSingleTop = true }
                    },
                    // SPEC §20 Pause / Resume — both are persisted state flips in
                    // the repository, never a UI-only toggle.
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    // Final-audit H5 (SPEC §26).
                    actionError = actionError,
                    onDismissActionError = viewModel::dismissActionError,
                    // `Replace file` (DECISIONS 152): request → dialog → confirm.
                    // Only the confirm ever reaches the repository.
                    onReplaceFile = viewModel::requestReplacement,
                    replacementTarget = replacementTarget,
                    onConfirmReplacement = viewModel::confirmReplacement,
                    onDismissReplacement = viewModel::dismissReplacement,
                )
            }

            // the Session Manager — Room
            // session rows + episode rows projected by the Sessions ViewModel;
            // every action goes through SessionActions (export-first delete,
            // terminal-only gates, temporary-only cache scope).
            composable<SessionsRoute> {
                val viewModel: SessionsViewModel = viewModel(factory = viewModelFactory)
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                SessionsScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    // DECISIONS 94 Resume: navigation back into the download
                    // flow for that session — not a state flip.
                    onResume = { animeSession ->
                        clearFocusAndHideIme()
                        navController.navigate(AnimeDetailsRoute(animeSession)) {
                            launchSingleTop = true
                        }
                    },
                    onCancel = viewModel::cancelSession,
                    onRetry = viewModel::retrySession,
                    // SPEC §20 Pause / Resume-all — real state flips through
                    // SessionActions, distinct from the navigate-Resume above.
                    onPause = viewModel::pauseSession,
                    onResumeDownloads = viewModel::resumeSession,
                    onDelete = viewModel::requestDeleteSession,
                    onDeleteTempCache = { sessionId ->
                        viewModel.deleteTemporaryCache(sessionId)
                    },
                    onConfirmDelete = viewModel::confirmDeleteSession,
                    onDismissDelete = viewModel::dismissDeleteSession,
                    onRequestClearCache = viewModel::requestClearCache,
                    onConfirmClearCache = viewModel::confirmClearCache,
                    onDismissClearCache = viewModel::dismissClearCache,
                )
            }

            // §23/§24 player destination — ID-only route arg; the VM re-reads
            // everything from Room. Seeded once per episodeId (§24.2), owned
            // nav-entry-scoped (§24.1), released when this entry pops.
            composable<PlayerRoute> { entry ->
                val route = entry.toRoute<PlayerRoute>()
                val viewModel: PlayerViewModel = viewModel(factory = viewModelFactory)
                // `mode` is part of the seed key so a STREAM entry can
                // never inherit a DOWNLOAD seed (and vice versa). Nothing else
                // about the route travels here — no URL, no StreamInfo.
                LaunchedEffect(route.episodeId, route.mode) {
                    viewModel.load(route.episodeId, route.mode)
                }
                val uiState by viewModel.uiState
                PlayerScreen(
                    uiState = uiState,
                    playerLifecycle = viewModel,
                    onBack = { navController.popBackStack() },
                    onRetryLoad = viewModel::retryLoad,
                    onRetryPlayback = viewModel::retryPlayback,
                    // SPEC §15: STREAM episode navigation — the ViewModel swaps the
                    // source in place (§24.2); DOWNLOAD keeps Media3's own controls.
                    onNext = viewModel::playNextEpisode,
                    onPrevious = viewModel::playPreviousEpisode,
                    // SPEC §15 "subtitles where available" — index into the
                    // option list, or -1 for Off.
                    onSelectSubtitle = viewModel::selectSubtitle,
                )
            }

            composable<SettingsRoute> {
                val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                // the notification permission projection
                // is a separate state (platform fact, not a DataStore flow).
                val notificationPermissionState by
                    viewModel.notificationPermissionState.collectAsStateWithLifecycle()
                // the SAF export-destination projection
                // is a separate state too (grant/folder fact, not a DataStore flow).
                val outputLocationState by
                    viewModel.outputLocationState.collectAsStateWithLifecycle()
                SettingsScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onThemeMode = viewModel::setThemeMode,
                    onDynamicColor = viewModel::setDynamicColor,
                    // engine settings — each persisted value has a real
                    // production consumer (see SettingsUiState mapping table).
                    onQuality = viewModel::setQuality,
                    // Audio has NO callback: it is per-episode (SPEC §22), not a
                    // global Settings value — the former `onAudioLang` is gone.
                    onMaxParallel = viewModel::setMaxParallel,
                    onHlsWorkers = viewModel::setHlsWorkers,
                    onCacheTtl = viewModel::setCacheTtl,
                    onKeepTemp = viewModel::setKeepTemp,
                    onAutoRetry = viewModel::setAutoRetry,
                    onResolveAhead = viewModel::setResolveAhead,
                    onShowConsole = viewModel::setShowConsole,
                    onOpenConsole = {
                        clearFocusAndHideIme()
                        navController.navigate(ConsoleRoute) { launchSingleTop = true }
                    },
                    // Wi-Fi-only downloads.
                    onWifiOnly = viewModel::setWifiOnly,
                    onAutoResume = viewModel::setAutoResume,
                    // terminal notification toggles.
                    onNotifyCompletion = viewModel::setNotifyCompletion,
                    onNotifyFailure = viewModel::setNotifyFailure,
                    // watchlist detection toggle.
                    onNotifyWatchlist = viewModel::setNotifyWatchlist,
                    // background check frequency.
                    onWatchlistCheckInterval = viewModel::setWatchlistCheckIntervalMinutes,
                    // notification permission status row.
                    notificationPermissionState = notificationPermissionState,
                    onRefreshNotificationPermission = viewModel::refreshNotificationPermission,
                    onNotificationPermissionResult = viewModel::onNotificationPermissionResult,
                    onOpenNotificationSystemSettings = {
                        clearFocusAndHideIme()
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                    // `Output location` — the SAF
                    // export destination row and its picker result.
                    outputLocationState = outputLocationState,
                    onRefreshOutputLocation = viewModel::refreshOutputLocation,
                    onOutputTreePicked = viewModel::onOutputTreePicked,
                    onUseDefaultOutputLocation = viewModel::useDefaultOutputLocation,
                )
            }

            // opt-in diagnostics Console (reached only from the Settings
            // row that appears when `Show Console` is ON). Read-only view over the
            // bounded in-memory buffer — no repository, no persistence.
            composable<ConsoleRoute> {
                val viewModel: ConsoleViewModel = viewModel(factory = viewModelFactory)
                val entries by viewModel.entries.collectAsStateWithLifecycle()
                ConsoleScreen(
                    entries = entries,
                    onBack = { navController.popBackStack() },
                    onClear = viewModel::clear,
                )
            }
            // watchlist list surface pushed from the Home top bar
            // (§10.2 — never a bottom tab). Room rows + the foreground check.
            composable<WatchlistRoute> {
                val viewModel: WatchlistViewModel = viewModel(factory = viewModelFactory)
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                WatchlistScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onUrlChange = viewModel::onUrlChange,
                    onAdd = viewModel::addByUrl,
                    onRemove = viewModel::remove,
                    onCheckNow = viewModel::checkNow,
                    // the manual relink flow.
                    onRelink = viewModel::startRelink,
                    onSelectRelinkCandidate = viewModel::selectRelinkCandidate,
                    onConfirmRelink = viewModel::confirmRelink,
                    onDismissRelink = viewModel::dismissRelink,
                )
            }
        }
    }
}
