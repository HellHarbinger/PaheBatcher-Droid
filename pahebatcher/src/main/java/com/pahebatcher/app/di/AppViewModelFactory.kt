package com.pahebatcher.app.di

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.stream.StreamEpisodeReconstructor
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.notification.DownloadNotifyPrefs
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import com.pahebatcher.app.ui.downloads.DownloadsViewModel
import com.pahebatcher.app.ui.home.HomeViewModel
import com.pahebatcher.app.playback.MediaSessionHost
import com.pahebatcher.app.ui.player.PlayerViewModel
import com.pahebatcher.app.ui.settings.SettingsViewModel
import com.pahebatcher.app.ui.watchlist.WatchlistViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * [ViewModelProvider.Factory] backed by the manual [AppContainer] composition root
 * (PHASE11_ARCHITECTURE_V2 §34: no Hilt/Koin — a small factory keyed to the
 * container). ViewModels obtain collaborators here; composables never see the
 * container (screens receive state + lambdas only).
 *
 * Callers pass `viewModel(factory = …)` from a NavBackStackEntry, so instances are
 * nav-entry scoped (§24.1 player lifecycle relies on exactly this scoping).
 */
class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    @OptIn(markerClass = [UnstableApi::class])
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(
                animeRepository = container.animeRepository,
                // the ONE app-scoped connectivity source.
                networkMonitor = container.networkMonitor,
            ) as T

        modelClass.isAssignableFrom(DownloadsViewModel::class.java) ->
            DownloadsViewModel(downloadRepository = container.downloadRepository) as T

        // Final-audit H1 (SPEC §6.10): the root session-refresh prompt. The
        // ViewModel observes the domain signal and runs the REAL force-refresh
        // through the container — it never sees the WebView or the provider.
        modelClass.isAssignableFrom(
            com.pahebatcher.app.ui.sessionprompt.SessionRefreshViewModel::class.java,
        ) ->
            com.pahebatcher.app.ui.sessionprompt.SessionRefreshViewModel(
                forceRefresh = { container.forceRefreshWebViewSession() },
            ) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(
                appConfig = container.appConfig,
                // the notification permission status row.
                notificationPermissionRepository = container.notificationPermissionRepository,
                // the `Output location` row — the
                // SAME validated repository the exporters route through.
                outputTreeRepository = container.outputTreeRepository,
            ) as T

        // diagnostics Console — a read-only projection of the bounded
        // in-memory log buffer (no repository, no persistence, no dependencies).
        modelClass.isAssignableFrom(com.pahebatcher.app.ui.console.ConsoleViewModel::class.java) ->
            com.pahebatcher.app.ui.console.ConsoleViewModel() as T

        // 11.5E-2/11.5E-3/11.5E-4: the details entry scans episodes through the
        // EXISTING EpisodeRepository, resolves a selected episode through the
        // EXISTING StreamResolverImpl, and hands a resolved stream to the EXISTING
        // download pipeline (EpisodeDownloadQueue → DownloadRepository) — all
        // constructed by the container, unchanged.
        modelClass.isAssignableFrom(AnimeDetailsViewModel::class.java) ->
            AnimeDetailsViewModel(
                episodeRepository = container.episodeRepository,
                streamResolver = container.streamResolver,
                downloadQueue = container.downloadRepository,
                // persisted `quality` (resolution) and `cache_ttl` (scan)
                // are consumed through this provider — one read per operation.
                configProvider = { container.appConfig.currentAppContext() },
                // offline indication + guarded actions.
                networkMonitor = container.networkMonitor,
                // the one-time first-enqueue prompt.
                notificationPermissionRepository = container.notificationPermissionRepository,
                // DECISIONS 144: the bounded play-page audio-option probe — one
                // pass per load after the scan, never per episode.
                audioOptionRepository = container.audioOptionRepository,
                // Slice C: the `notify_failure` preference, read FRESH at the
                // moment the batch summary is considered (105.4 read-fresh).
                notifyFailurePrefs = {
                    DownloadNotifyPrefs(
                        failure = container.appConfig.notifyFailure.first(),
                    )
                },
                // Slice C: the ONE aggregated batch-failure notification, built
                // and posted by the same manager every Worker uses. The reason
                // breakdown (typed category labels only) rides along so the
                // summary line can name WHY without quoting any raw error.
                batchFailureNotifier = { failed, total, animeTitle, reasonCounts ->
                    container.downloadNotificationManager.postBatchFailureNotification(
                        failed = failed,
                        total = total,
                        animeTitle = animeTitle,
                        reasonCounts = reasonCounts,
                    )
                },
            ) as T

        // watchlist list surface — Room rows + the check use case
        // (production repositories, production resolver, production queue).
        modelClass.isAssignableFrom(WatchlistViewModel::class.java) ->
            WatchlistViewModel(
                watchlistRepository = container.watchlistRepository,
                checker = container.watchlistChecker,
                configProvider = { container.appConfig.currentAppContext() },
                // offline banner + guarded check/relink.
                networkMonitor = container.networkMonitor,
            ) as T

        // Session Manager entry (DECISIONS 110 — Sessions is pushed
        // from THIS top bar, never a bottom tab).
        modelClass.isAssignableFrom(com.pahebatcher.app.ui.sessions.SessionsViewModel::class.java) ->
            com.pahebatcher.app.ui.sessions.SessionsViewModel(
                sessionRepository = container.sessionRepository,
                actions = container.sessionActions,
            ) as T

        // §24.1: the factory builds the ONE ExoPlayer the PlayerViewModel owns;
        // the app context comes from the container (never from a composable).
        // inject MediaSessionHost to wrap the player
        // in a MediaSession for system transport controls / media notification.
        // inject the STREAM collaborators — the resolver, the
        // OkHttp/HLS source factory and the Room → cache → network ladder. All
        // optional in the ViewModel so pre-existing constructions keep working.
        modelClass.isAssignableFrom(PlayerViewModel::class.java) ->
            PlayerViewModel(
                playerFactory = { ExoPlayer.Builder(container.context).build() },
                downloadRepository = container.downloadRepository,
                positionRepository = container.playbackPositionRepository,
                mediaSessionHost = container.mediaSessionHost,
                streamResolver = container.streamResolver,
                hlsSourceFactory = container.hlsPlaybackSourceFactory,
                streamReconstructor = streamReconstructor(container),
                configProvider = { container.appConfig.currentAppContext() },
                // SPEC §15: the ordered episode list the STREAM entry navigates —
                // cache-first through the SAME EpisodeRepository.scan the details
                // screen and the reconstruction ladder already use, so the common
                // case answers from the scan cache without another network run.
                streamEpisodeLister = { animeSession ->
                    container.episodeRepository
                        .scan(AnimeRepository.currentHost, animeSession, configuredCacheTtlMinutes(container))
                        .episodes
                },
            ) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}

/**
 * The STREAM reconstruction ladder bound to the real sources:
 *
 * 1. the existing `episode_downloads` row (metadata only — its state is never
 *    inspected and nothing is written);
 * 2. the UNEXPIRED scan cache (`ScanCacheRepository` enforces the TTL and never
 *    performs I/O), matched on `(epStr, audio)`;
 * 3. `EpisodeRepository.scan` — the only step that reaches the network, and only
 *    reached from a STREAM entry whose id already passed the shape gate.
 *
 * A blank `playUrl` on the Room row is treated as absent so step 3 still runs
 * rather than resolving against an empty URL.
 */
private fun streamReconstructor(container: AppContainer) = StreamEpisodeReconstructor(
    roomEpisode = { episodeId ->
        container.downloadRepository.getEpisode(episodeId)
            ?.takeIf { it.playUrl.isNotBlank() }
            ?.let { row ->
                EpisodeInfo(
                    number = row.episodeNumber,
                    session = row.episodeSession,
                    title = row.title,
                    fansub = row.fansub,
                    audio = row.audio,
                    playUrl = row.playUrl,
                )
            }
    },
    cachedEpisode = { animeSession, episodeString, audio ->
        val ttl = configuredCacheTtlMinutes(container)
        if (ttl <= 0) {
            null
        } else {
            container.scanCacheRepository
                .getCachedScan(animeSession, ttl)
                ?.episodes
                ?.firstOrNull { it.epStr == episodeString && it.audio == audio }
        }
    },
    scannedEpisodes = { animeSession ->
        val ttl = configuredCacheTtlMinutes(container)
        container.episodeRepository
            .scan(AnimeRepository.currentHost, animeSession, ttl)
            .episodes
    },
)

/** Same honest settings-read fallback as the details slice (defaults → 60 min). */
private suspend fun configuredCacheTtlMinutes(container: AppContainer): Int = try {
    container.appConfig.currentAppContext().cacheTtl
} catch (e: CancellationException) {
    // The settings read is a suspension point — a cancelled caller must not
    // have the default silently substituted for its real answer (AQR-009).
    throw e
} catch (error: Exception) {
    AppContext.defaults().cacheTtl
}
