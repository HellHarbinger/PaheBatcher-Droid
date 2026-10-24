package com.pahebatcher.app.ui

import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.data.network.NetworkMonitor
import com.pahebatcher.app.data.network.NetworkStatus
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import com.pahebatcher.app.data.repository.FakeScanCacheDao
import com.pahebatcher.app.data.repository.FakeWatchlistDao
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.data.repository.WatchlistRepository
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.domain.watchlist.WatchlistChecker
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import com.pahebatcher.app.ui.home.HomeViewModel
import com.pahebatcher.app.ui.watchlist.WatchlistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ONE [NetworkMonitor] consumed per screen.
 *
 * Covers the mandated transition matrix **UNKNOWN → ONLINE → OFFLINE → ONLINE**
 * on all three network-dependent ViewModels (Home/Search, Anime Details,
 * Watchlist) and the DECISIONS 97.4 guards: while OFFLINE the network-bound
 * actions are refused honestly and issue **zero** repository/HTTP traffic
 * (SPEC §27 — never pretend an unavailable operation succeeded), while
 * UNKNOWN/ONLINE run the real call unchanged (97.2 — no false offline claim
 * ever blocks work before the platform reported connectivity).
 *
 * The monitor is the test's [FakeNetworkMonitor]; every repository collaborator
 * is the project's existing JVM fake ([FakeHttpClient] records each request, so
 * "no traffic" is asserted on real call counts, not on mocks).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorOfflineTest {

    /** Scriptable monitor: the test drives every transition explicitly. */
    private class FakeNetworkMonitor : NetworkMonitor {
        val status = MutableStateFlow(NetworkStatus.UNKNOWN)
        override val networkStatus: Flow<NetworkStatus> = status
    }

    /** Honest "no knowledge" resolver — any resolution at all fails this test. */
    private class NeverResolver : StreamResolver {
        var calls = 0
        override suspend fun resolve(playUrl: String, quality: Int, audio: String) =
            throw AssertionError("stream resolution must not run under this test's conditions")
    }

    /** Queue that records hand-offs; the guarded tests assert it stays empty. */
    private class RecordingQueue : EpisodeDownloadQueue {
        val submissions = mutableListOf<ResolvedEpisodeSubmission>()
        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            return EnqueueOutcome.Accepted
        }
    }

    /** Share runTest's virtual clock with Dispatchers.Main (viewModelScope). */
    private fun kotlinx.coroutines.test.TestScope.givenVirtualMain() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Transition matrix (Decision 97.6)
    // -----------------------------------------------------------------------

    @Test
    fun `home follows unknown online offline online transitions`() = runTest {
        givenVirtualMain()
        val monitor = FakeNetworkMonitor()
        val vm = HomeViewModel(
            animeRepository = AnimeRepository(FakeHttpClient()),
            networkMonitor = monitor,
        )

        // UNKNOWN (97.2): treated as online — no banner before the platform speaks.
        advanceUntilIdle()
        assertFalse(vm.searchState.value.offline)

        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        assertFalse(vm.searchState.value.offline)

        monitor.status.value = NetworkStatus.OFFLINE
        advanceUntilIdle()
        assertTrue("OFFLINE must set the banner flag", vm.searchState.value.offline)

        // Restoration clears the banner automatically (97.4 — no polling).
        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        assertFalse("back ONLINE must clear the flag", vm.searchState.value.offline)
    }

    @Test
    fun `anime details follows the same transition matrix`() = runTest {
        givenVirtualMain()
        val monitor = FakeNetworkMonitor()
        val vm = AnimeDetailsViewModel(
            episodeRepository = EpisodeRepository(
                animeRepository = AnimeRepository(FakeHttpClient()),
                scanCacheRepository = ScanCacheRepository(FakeScanCacheDao()),
            ),
            streamResolver = NeverResolver(),
            downloadQueue = RecordingQueue(),
            networkMonitor = monitor,
        )

        advanceUntilIdle()
        assertFalse(vm.uiState.value.offline)

        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        assertFalse(vm.uiState.value.offline)

        monitor.status.value = NetworkStatus.OFFLINE
        advanceUntilIdle()
        assertTrue(vm.uiState.value.offline)

        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        assertFalse(vm.uiState.value.offline)
    }

    @Test
    fun `watchlist follows the same transition matrix`() = runTest {
        givenVirtualMain()
        val monitor = FakeNetworkMonitor()
        val client = FakeHttpClient()
        val vm = watchlistViewModel(client, monitor)

        advanceUntilIdle()
        assertFalse(vm.uiState.value.offline)

        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        assertFalse(vm.uiState.value.offline)

        monitor.status.value = NetworkStatus.OFFLINE
        advanceUntilIdle()
        assertTrue(vm.uiState.value.offline)

        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        assertFalse(vm.uiState.value.offline)
    }

    // -----------------------------------------------------------------------
    // Guards (Decision 97.4 / SPEC §27): refuse honestly, zero traffic
    // -----------------------------------------------------------------------

    @Test
    fun `home refuses search while offline and runs the real call once online`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient()
        val monitor = FakeNetworkMonitor()
        val vm = HomeViewModel(AnimeRepository(client), networkMonitor = monitor)

        monitor.status.value = NetworkStatus.OFFLINE
        advanceUntilIdle()

        vm.onQueryChange("naruto")
        advanceUntilIdle() // the debounce window elapses into the guard

        assertEquals(HomeViewModel.OFFLINE_ERROR, vm.searchState.value.error)
        assertFalse("the guard must not leave a spinner running", vm.searchState.value.loading)
        assertTrue(
            "no request may be issued while offline (actual: ${client.requestedUrls})",
            client.requestedUrls.isEmpty(),
        )

        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        vm.submitSearch()
        advanceUntilIdle()

        assertTrue(
            "the real call runs once connectivity is back (actual: ${client.requestedUrls})",
            client.requestedUrls.isNotEmpty(),
        )
        assertNotEquals(
            "the offline refusal must be replaced by the real outcome",
            HomeViewModel.OFFLINE_ERROR,
            vm.searchState.value.error,
        )
    }

    @Test
    fun `watchlist refuses checkNow while offline and really checks once online`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient()
        val monitor = FakeNetworkMonitor()
        val vm = watchlistViewModel(client, monitor)
        advanceUntilIdle() // entries are loaded from the fake Room

        monitor.status.value = NetworkStatus.OFFLINE
        advanceUntilIdle()

        vm.checkNow()
        advanceUntilIdle()

        assertFalse("a guarded check never starts a run", vm.uiState.value.checking)
        assertTrue(
            "no result may be invented while offline",
            vm.uiState.value.results.isEmpty(),
        )
        assertTrue(
            "no request may be issued while offline (actual: ${client.requestedUrls})",
            client.requestedUrls.isEmpty(),
        )

        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        vm.checkNow()
        advanceUntilIdle()

        assertTrue(
            "the real check must reach the repository once online (actual: ${client.requestedUrls})",
            client.requestedUrls.isNotEmpty(),
        )
        assertTrue(
            "the entry must be processed and reported",
            vm.uiState.value.results.containsKey("sess-old"),
        )
        assertFalse(vm.uiState.value.checking)
    }

    @Test
    fun `anime details refuses scan resolution and batch while offline`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient()
        val monitor = FakeNetworkMonitor()
        val resolver = NeverResolver()
        val queue = RecordingQueue()
        val animeRepository = AnimeRepository(client)
        val vm = AnimeDetailsViewModel(
            episodeRepository = EpisodeRepository(
                animeRepository = animeRepository,
                scanCacheRepository = ScanCacheRepository(FakeScanCacheDao()),
            ),
            streamResolver = resolver,
            downloadQueue = queue,
            networkMonitor = monitor,
        )

        // --- scan: refused with the honest message, zero traffic ------------
        monitor.status.value = NetworkStatus.OFFLINE
        advanceUntilIdle()
        vm.load("sess-offline")
        advanceUntilIdle()

        assertEquals(AnimeDetailsViewModel.ERROR_OFFLINE, vm.uiState.value.error)
        assertFalse(vm.uiState.value.loading)
        assertTrue(
            "no scan request may be issued while offline (actual: ${client.requestedUrls})",
            client.requestedUrls.isEmpty(),
        )

        // --- resolution + batch: first a REAL online load, then go offline ---
        monitor.status.value = NetworkStatus.ONLINE
        advanceUntilIdle()
        client.stubJson("m=release", episodesPage())
        vm.load("sess-offline")
        advanceUntilIdle()
        assertEquals("Test Anime", vm.uiState.value.title)

        monitor.status.value = NetworkStatus.OFFLINE
        advanceUntilIdle()

        val episode = vm.uiState.value.episodes.first()
        val requestsBefore = client.requestedUrls.size
        vm.selectEpisode(episode)
        advanceUntilIdle()

        assertEquals(
            "a resolution while offline must be refused honestly",
            AnimeDetailsViewModel.ERROR_OFFLINE,
            vm.uiState.value.resolutionError,
        )
        assertFalse(vm.uiState.value.resolving)
        assertEquals("the resolver must never be invoked offline", 0, resolver.calls)

        vm.selectAllEpisodes()
        vm.startBatchDownload()
        advanceUntilIdle()

        assertEquals(
            "a batch start while offline must be refused honestly",
            AnimeDetailsViewModel.ERROR_OFFLINE,
            vm.uiState.value.batchError,
        )
        assertFalse("the batch must not start", vm.uiState.value.batchRunning)
        assertTrue("nothing may be queued while offline", queue.submissions.isEmpty())
        assertTrue(
            "no further traffic may be issued while offline",
            client.requestedUrls.size == requestsBefore,
        )
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private fun episodesPage(): String = """
        {"data": [
            {"episode": 1, "session": "ep1", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 2, "session": "ep2", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Test Anime"}}
    """.trimIndent()

    private fun storedEntry() = WatchlistEntity(
        sessionId = "sess-old",
        url = "https://animepahe.com/anime/sess-old",
        animeSession = "sess-old",
        host = "animepahe.com",
        title = "Test Anime",
        quality = 720,
        audioLang = "jpn",
        outputDir = "./downloads",
        maxParallel = 2,
        hlsWorkers = 24,
        keepTemp = false,
        autoRetry = 0,
        addedAt = 1_000L,
        lastCheckedAt = 0L,
        downloadedEpisodesJson = "[]",
    )

    /**
     * The real repository + checker chain (same construction as
     * WatchlistCheckerTest) over the fake DAO — so `Check now` executes the
     * production pipeline and every HTTP attempt lands in [FakeHttpClient].
     *
     * The row is seeded BEFORE the ViewModel exists: `observe()` is the fake
     * DAO's one-shot `flowOf`, so the entry must be present when the init
     * collector starts (it runs at the first `advanceUntilIdle`).
     */
    private suspend fun watchlistViewModel(client: FakeHttpClient, monitor: NetworkMonitor): WatchlistViewModel {
        val dao = FakeWatchlistDao()
        dao.upsert(storedEntry())
        val repository = WatchlistRepository(dao)
        val animeRepository = AnimeRepository(client)
        val scanCache = ScanCacheRepository(FakeScanCacheDao())
        val checker = WatchlistChecker(
            episodeRepository = EpisodeRepository(
                animeRepository = animeRepository,
                scanCacheRepository = scanCache,
            ),
            animeRepository = animeRepository,
            scanCacheRepository = scanCache,
            watchlistRepository = repository,
            streamResolver = NeverResolver(),
            downloadQueue = RecordingQueue(),
            downloadRoot = { File(".") },
            configProvider = { AppContext.defaults() },
        )
        return WatchlistViewModel(
            watchlistRepository = repository,
            checker = checker,
            networkMonitor = monitor,
        )
    }
}
