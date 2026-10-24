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
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.domain.watchlist.RelinkCandidate
import com.pahebatcher.app.domain.watchlist.SEARCH_UNREACHABLE_MESSAGE
import com.pahebatcher.app.domain.watchlist.WatchlistCheckStatus
import com.pahebatcher.app.domain.watchlist.WatchlistChecker
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manual relink ViewModel flow over the REAL
 * repository + checker chain (production [WatchlistRepository] over
 * [FakeWatchlistDao], production [WatchlistChecker] over [FakeHttpClient]) —
 * the same pipeline `Check now` uses, driven through the dialog's actions.
 *
 * | decision | proven here |
 * |---|---|
 * | 108.4 | the dialog opens by running the SHARED candidate search (real `m=search` traffic, real filter) |
 * | 108.6 | 0 candidates / unreachable → the exact shared wording, entry untouched |
 * | 108.7 | a single candidate is preselected |
 * | 108.8 | multiple candidates wait for an explicit selection; Confirm without one is a no-op |
 * | 108.9 | confirm persists ONLY session/host/url (history/title/prefs kept); Cancel = zero writes |
 * | 108.12| the result map follows the re-keyed entry; the Room observer owns the list |
 * | 108.13| confirm runs the standard check on the new key immediately (UP_TO_DATE lands) |
 * | 97.4  | OFFLINE refuses to start the search — zero HTTP traffic |
 * | AQR-040| a stale search (cancelled dialog, reopened on another entry) never overwrites the newer dialog |
 *
 * No network (AGENTS §5): every request lands in [FakeHttpClient], and the
 * resolver/queue are test-only doubles (the resolver FAILS the test if ever
 * invoked — the confirm path must resolve nothing when history covers all
 * episodes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistRelinkTest {

    private lateinit var dao: FakeWatchlistDao
    private lateinit var repository: WatchlistRepository
    private lateinit var client: FakeHttpClient

    /** Scriptable monitor: the test drives every transition explicitly. */
    private class FakeNetworkMonitor : NetworkMonitor {
        val status = MutableStateFlow(NetworkStatus.UNKNOWN)
        override val networkStatus: Flow<NetworkStatus> = status
    }

    /** Honest "no knowledge" resolver — any resolution at all fails this test. */
    private class NeverResolver : StreamResolver {
        override suspend fun resolve(playUrl: String, quality: Int, audio: String) =
            throw AssertionError("the confirm rescan must not resolve a stream")
    }

    /** Queue that records hand-offs; every test asserts it stays empty. */
    private class RecordingQueue : EpisodeDownloadQueue {
        val submissions = mutableListOf<ResolvedEpisodeSubmission>()
        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            return EnqueueOutcome.Accepted
        }

        override suspend fun isEpisodeAlreadyHandled(
            animeSession: String,
            episode: EpisodeInfo,
        ): Boolean = false

        override suspend fun isEpisodeOutputPresent(
            animeSession: String,
            episode: EpisodeInfo,
        ): Boolean = false

        override suspend fun resolvedAheadCount(): Int = 0
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
    // Fixtures
    // -----------------------------------------------------------------------

    private fun entry() = WatchlistEntity(
        sessionId = "sess-old",
        url = "https://animepahe.com/anime/sess-old",
        animeSession = "sess-old",
        host = "animepahe.com",
        title = "Test Anime",
        quality = 1080,
        audioLang = "jpn",
        outputDir = "./downloads",
        maxParallel = 2,
        hlsWorkers = 24,
        keepTemp = false,
        autoRetry = 2,
        addedAt = 1_000L,
        lastCheckedAt = 0L,
        downloadedEpisodesJson = "[]",
    )

    /** Live listing: episodes 1 and 2 — both already in history → UP_TO_DATE. */
    private fun livePage(): String = """
        {"data": [
            {"episode": 1, "session": "ep1-jpn", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 2, "session": "ep2-jpn", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Test Anime"}}
    """.trimIndent()

    private fun searchResults(vararg sessions: String, title: String = "Test Anime"): String =
        sessions.joinToString(prefix = "{\"data\": [", postfix = "]}") { session ->
            """{"session": "$session", "title": "$title", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"}"""
        }

    /**
     * The real repository + checker chain (same construction as
     * WatchlistCheckerTest / NetworkMonitorOfflineTest) over the fake DAO —
     * `confirm` executes the production pipeline and every HTTP attempt lands
     * in [client].
     */
    private suspend fun viewModel(
        monitor: NetworkMonitor = FakeNetworkMonitor(),
    ): WatchlistViewModel {
        client = FakeHttpClient()
        dao = FakeWatchlistDao()
        dao.upsert(entry())
        repository = WatchlistRepository(dao)
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

    private fun rowsSnapshot(): Map<String, WatchlistEntity> = dao.rows.toMap()

    // -----------------------------------------------------------------------
    // 108.6 — honest outcomes before any selection exists
    // -----------------------------------------------------------------------

    @Test
    fun `zero candidates shows the shared honest outcome and writes nothing`() = runTest {
        givenVirtualMain()
        val vm = viewModel()
        client.stubJson("m=search", """{"data": []}""")
        advanceUntilIdle()
        val before = rowsSnapshot()

        vm.startRelink(vm.uiState.value.entries.first())
        advanceUntilIdle()

        val dialog = vm.uiState.value.relink
        assertNotNull("the dialog opened", dialog)
        assertEquals(
            "the EXACT wording shared with the automatic path (108.6)",
            "Link dead for 'Test Anime' — no session found. Re-add it with the anime's URL.",
            dialog!!.message,
        )
        assertEquals(emptyList<RelinkCandidate>(), dialog.candidates)
        assertEquals(-1, dialog.selectedIndex)
        assertEquals(
            "a search outcome never touches the entry (108.9)",
            before,
            rowsSnapshot(),
        )
    }

    @Test
    fun `unreachable search reports the unreachable wording not a found-outcome`() = runTest {
        givenVirtualMain()
        val vm = viewModel()
        advanceUntilIdle() // client has NO m=search stub → no host answers

        vm.startRelink(vm.uiState.value.entries.first())
        advanceUntilIdle()

        assertEquals(
            SEARCH_UNREACHABLE_MESSAGE,
            vm.uiState.value.relink?.message,
        )
    }

    @Test
    fun `offline refuses to start the search with zero traffic`() = runTest {
        givenVirtualMain()
        val monitor = FakeNetworkMonitor()
        val vm = viewModel(monitor)
        client.stubJson("m=search", searchResults("sess-new"))
        advanceUntilIdle()
        monitor.status.value = NetworkStatus.OFFLINE
        advanceUntilIdle()

        vm.startRelink(vm.uiState.value.entries.first())
        advanceUntilIdle()

        assertNull("no dialog while OFFLINE (97.4)", vm.uiState.value.relink)
        assertTrue(
            "the search never issued a request",
            client.requestedUrls.none { it.contains("m=search") },
        )
    }

    // -----------------------------------------------------------------------
    // 108.7 / 108.8 — selection contract
    // -----------------------------------------------------------------------

    @Test
    fun `a single candidate is preselected`() = runTest {
        givenVirtualMain()
        val vm = viewModel()
        client.stubJson("m=search", searchResults("sess-new"))
        advanceUntilIdle()

        vm.startRelink(vm.uiState.value.entries.first())
        advanceUntilIdle()

        val dialog = vm.uiState.value.relink
        assertEquals(
            "shared search result",
            listOf("sess-new"),
            dialog?.candidates?.map { it.session },
        )
        assertEquals("single candidate preselected (108.7)", 0, dialog?.selectedIndex)
        // 108.5: the search row metadata arrives with the session.
        assertEquals("TV", dialog?.candidates?.first()?.type)
        assertEquals(2024, dialog?.candidates?.first()?.year)
    }

    @Test
    fun `multiple candidates wait for an explicit selection before confirm`() = runTest {
        givenVirtualMain()
        val vm = viewModel()
        client.stubJson("m=search", searchResults("sess-a", "sess-b"))
        advanceUntilIdle()
        val before = rowsSnapshot()

        vm.startRelink(vm.uiState.value.entries.first())
        advanceUntilIdle()
        assertEquals("nothing preselected", -1, vm.uiState.value.relink?.selectedIndex)

        // 108.8: Confirm with no selection is a no-op — dialog stays, no write.
        vm.confirmRelink()
        advanceUntilIdle()
        assertNotNull("still open", vm.uiState.value.relink)
        assertEquals("no write happened", before, rowsSnapshot())

        vm.selectRelinkCandidate(1)
        assertEquals("the selection round-trips", 1, vm.uiState.value.relink?.selectedIndex)

        // Out-of-range selection is ignored (state stays coherent).
        vm.selectRelinkCandidate(9)
        assertEquals(1, vm.uiState.value.relink?.selectedIndex)
    }

    // -----------------------------------------------------------------------
    // 108.9 / 108.12 / 108.13 — confirm: persist + standard rescan
    // -----------------------------------------------------------------------

    @Test
    fun `confirm persists only the chosen session and runs the standard rescan`() = runTest {
        givenVirtualMain()
        val vm = viewModel()
        client.stubJson("m=search", searchResults("sess-new"))
        client.stubJson("m=release", livePage())
        advanceUntilIdle()
        // History set BEFORE the relink — it must survive (108.9).
        repository.setDownloadedEpisodes("sess-old", listOf(1.0, 2.0))
        val original = vm.uiState.value.entries.first()

        vm.startRelink(original)
        advanceUntilIdle()
        vm.confirmRelink()
        advanceUntilIdle()

        // 108.9: re-keyed — session/host/url only; title + prefs + history kept.
        assertNull("the old row is gone", dao.rows["sess-old"])
        val migrated = dao.rows["sess-new"]
        assertNotNull("the entry lives under the new session", migrated)
        assertEquals("Test Anime", migrated!!.title)
        assertEquals(original.quality, migrated.quality)
        assertEquals(original.autoRetry, migrated.autoRetry)
        assertEquals(original.host, migrated.host)
        assertEquals(
            "download history survives the manual relink",
            listOf(1.0, 2.0),
            repository.downloadedEpisodes("sess-new"),
        )
        // 108.12/108.13: dialog closed, standard check ran on the new key, the
        // result map followed it (old key dropped), and UP_TO_DATE proves the
        // rescan really executed (never touched the resolver — history covers
        // both episodes).
        assertNull(vm.uiState.value.relink)
        assertFalse(
            "the old key's result was dropped with the row",
            vm.uiState.value.results.containsKey("sess-old"),
        )
        val result = vm.uiState.value.results["sess-new"]
        assertNotNull("the rescan result landed under the new key", result)
        assertEquals(WatchlistCheckStatus.UP_TO_DATE, result!!.status)
        assertTrue(
            "the rescan really ran (last_checked persisted on the new key)",
            dao.rows.getValue("sess-new").lastCheckedAt > 0L,
        )
    }

    @Test
    fun `cancel dismisses with zero writes`() = runTest {
        givenVirtualMain()
        val vm = viewModel()
        client.stubJson("m=search", searchResults("sess-new"))
        advanceUntilIdle()
        val before = rowsSnapshot()

        vm.startRelink(vm.uiState.value.entries.first())
        advanceUntilIdle()
        vm.dismissRelink()
        advanceUntilIdle()

        assertNull("the dialog closed", vm.uiState.value.relink)
        assertEquals("Cancel wrote nothing (108.9)", before, rowsSnapshot())
        assertTrue(
            "no scan traffic was issued by opening+cancelling",
            client.requestedUrls.none { it.contains("m=release") },
        )
    }

    // -----------------------------------------------------------------------
    // AQR-040 — a stale search result never lands in a newer dialog
    // -----------------------------------------------------------------------

    @Test
    fun `a stale search result never overwrites a newer dialog`() = runTest {
        givenVirtualMain()
        val vm = viewModel()
        // A second entry so the stale dialog's replacement is a DISTINCT entry.
        dao.upsert(
            entry().copy(
                sessionId = "sess-first",
                animeSession = "sess-first",
                url = "https://animepahe.com/anime/sess-first",
                title = "First Anime",
            ),
        )
        dao.upsert(
            entry().copy(
                sessionId = "sess-second",
                animeSession = "sess-second",
                url = "https://animepahe.com/anime/sess-second",
                title = "Second Anime",
            ),
        )
        // Ordered responses: the ungated second dialog consumes index 0; the
        // gated stale search consumes index 1 when the test releases it.
        client.stubJson("m=search", searchResults("sess-second-new", title = "Second Anime"))
        client.stubJson("m=search", searchResults("sess-stale", title = "First Anime"))
        val staleSearch = client.gate("q=First")
        advanceUntilIdle()

        val first = vm.uiState.value.entries.first { it.title == "First Anime" }
        val second = vm.uiState.value.entries.first { it.title == "Second Anime" }
        val before = rowsSnapshot()

        vm.startRelink(first)      // dialog #1 opens — its search queued
        vm.dismissRelink()         // Cancel while loading is allowed
        vm.startRelink(second)     // dialog #2 opens — its search queued behind #1
        advanceUntilIdle()         // #2's search completes; #1's HOLDS on the gate

        assertEquals(
            "the newer dialog shows its own result before the stale one lands",
            listOf("sess-second-new"),
            vm.uiState.value.relink?.candidates?.map { it.session },
        )

        staleSearch.complete(Unit)
        advanceUntilIdle()         // the stale 'First Anime' search completes now

        val dialog = vm.uiState.value.relink
        assertNotNull("the newer dialog is still open", dialog)
        assertEquals(
            "it still belongs to the second entry",
            "sess-second",
            dialog!!.entry.animeSession,
        )
        assertEquals(
            "the stale rows must never replace the newer dialog's candidates",
            listOf("sess-second-new"),
            dialog.candidates.map { it.session },
        )
        assertEquals("its honest single-candidate preselection stays (108.7)", 0, dialog.selectedIndex)
        assertNull("no message from the stale entry", dialog.message)
        assertEquals("searching writes nothing (108.9)", before, rowsSnapshot())
        assertTrue(
            "both searches really ran (the first was held, then released)",
            client.requestedUrls.any { it.contains("q=First") } &&
                client.requestedUrls.any { it.contains("q=Second") },
        )
    }
}
