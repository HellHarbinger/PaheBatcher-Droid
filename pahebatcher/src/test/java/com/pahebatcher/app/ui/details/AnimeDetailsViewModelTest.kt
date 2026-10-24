package com.pahebatcher.app.ui.details

import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import com.pahebatcher.app.data.repository.FakeScanCacheDao
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolutionException
import com.pahebatcher.app.domain.stream.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 11.5E-2 — anime details ViewModel over the EXISTING production chain
 * (AnimeDetailsViewModel → EpisodeRepository → ScanCacheRepository → parser):
 *
 *  - [AnimeDetailsViewModel.load] scans the session it was given — asserted
 *    through the episode-API request the repository itself issues;
 *  - a successful scan yields the success state with the REAL parsed episodes
 *    and the repository-resolved title, and is written to the scan cache;
 *  - a completed scan with zero episodes yields the EMPTY state (never an error);
 *  - a failing scan yields the ERROR state carrying the repository's failure
 *    (never an empty list dressed up as success);
 *  - [AnimeDetailsViewModel.retry] re-runs the SAME scan for the SAME session.
 *
 * 11.5E-3 additions (same class, SAME repository chain): episode selection →
 * stream resolution through the injected [StreamResolver] boundary —
 * the selected episode is recorded in the state, the resolution is entered
 * before it completes, a success publishes EXACTLY the StreamInfo the resolver
 * returned, a failure surfaces the resolver's own message (never a fake success),
 * [AnimeDetailsViewModel.retryResolution] re-runs the real call, and a selection
 * that is superseded by another one can never overwrite the newer result.
 *
 * No network: every repository collaborator is the project's existing test fake
 * (FakeHttpClient / ScriptedHttpClient / FakeScanCacheDao); the resolver is the
 * test-only [FakeStreamResolver] below — production binds the real
 * `StreamResolverImpl` (verified by the production-wiring instrumentation test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailsViewModelTest {

    private lateinit var scanCacheDao: FakeScanCacheDao

    /** The queue the ViewModel under test was built with — asserted below. */
    private lateinit var downloadQueue: FakeEpisodeDownloadQueue

    private fun newViewModel(
        client: HttpClient,
        streamResolver: StreamResolver = FakeStreamResolver(),
        downloadQueue: FakeEpisodeDownloadQueue = FakeEpisodeDownloadQueue(),
        // persisted settings. Defaults to AppContext.defaults() — the
        // same default production falls back to — so existing tests keep binding
        // to the Python defaults unless a test injects its own snapshot.
        configProvider: suspend () -> com.pahebatcher.app.domain.model.AppContext = {
            com.pahebatcher.app.domain.model.AppContext.defaults()
        },
    ): AnimeDetailsViewModel {
        this.downloadQueue = downloadQueue
        scanCacheDao = FakeScanCacheDao()
        val episodeRepository = EpisodeRepository(
            animeRepository = AnimeRepository(client),
            scanCacheRepository = ScanCacheRepository(scanCacheDao),
        )
        return AnimeDetailsViewModel(
            episodeRepository = episodeRepository,
            streamResolver = streamResolver,
            downloadQueue = downloadQueue,
            configProvider = configProvider,
        )
    }

    private fun episodesPage(
        title: String = "Test Anime",
        startEpisode: Int = 1,
    ): String {
        val first = startEpisode
        val second = startEpisode + 1
        return """
        {"data": [
            {"episode": $first, "session": "ep$first", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": $second, "session": "ep$second", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "$title"}}
    """.trimIndent()
    }

    private fun emptyPage(): String =
        """{"data": [], "anime": {"title": "Empty Anime"}, "last_page": 1}"""

    /** Share runTest's virtual clock with Dispatchers.Main (viewModelScope). */
    private fun kotlinx.coroutines.test.TestScope.givenVirtualMain() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Scan is invoked with the selected session
    // -----------------------------------------------------------------------

    @Test
    fun `load scans the selected session through the episode repository`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val vm = newViewModel(client)

        vm.load("sess-selected")
        testScheduler.advanceUntilIdle()

        assertEquals("sess-selected", vm.uiState.value.session)
        assertTrue(
            "the repository must request the SELECTED session's episode API page " +
                "(actual: ${client.requestedUrls})",
            client.requestedUrls.any { it.contains("m=release") && it.contains("id=sess-selected") },
        )
        // The scan went through ScanCacheRepository too (cache written after scan).
        assertEquals(1, scanCacheDao.count())
    }

    // -----------------------------------------------------------------------
    // Success / empty / error branches
    // -----------------------------------------------------------------------

    @Test
    fun `a successful scan produces the success state with the real episodes`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val vm = newViewModel(client)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("scan finished", state.loading)
        assertNull("no error on success", state.error)
        assertEquals("Test Anime", state.title)
        assertEquals(listOf(1f, 2f), state.episodes.map { it.number })
        assertEquals("Beginnings", state.episodes.first().title)
    }

    @Test
    fun `a completed scan with no episodes produces the empty state, not an error`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", emptyPage()) }
        val vm = newViewModel(client)

        vm.load("sess-empty")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("scan finished", state.loading)
        assertNull("an answered scan with zero episodes is NOT an error", state.error)
        assertTrue("explicit empty state", state.episodes.isEmpty())
        assertEquals("Empty Anime", state.title)
        assertEquals("sess-empty", state.session)
    }

    @Test
    fun `a failing scan produces the error state carrying the real failure`() = runTest {
        givenVirtualMain()
        val vm = newViewModel(ScriptedHttpClient { throw IOException("network down") })

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("failure is not a loading state", state.loading)
        assertNotNull("failure must surface an explicit error", state.error)
        assertEquals("network down", state.error)
        assertTrue("a failure never renders a fake episode list", state.episodes.isEmpty())
    }

    @Test
    fun `an incomplete scan surfaces the error state instead of a truncated episode list`() = runTest {
        givenVirtualMain()
        // discovery → page 1 of 2 → the second page never arrives, so the scan
        // ends with required pages missing (`extract/scanner.py:267-279`).
        val client = FakeHttpClient().apply {
            stubJson(
                "m=release",
                """{"data": [], "anime": {"title": "Test Anime"}, "last_page": 2}""",
            )
            stubJson(
                "m=release",
                """
                {"data": [
                    {"episode": 1, "session": "ep1", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"}
                ], "last_page": 2, "anime": {"title": "Test Anime"}}
                """.trimIndent(),
            )
            stubJson("m=release", null)
        }
        val vm = newViewModel(client)

        vm.load("sess-cut-short")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("scan finished", state.loading)
        assertNotNull("a scan that left pages behind is a failure", state.error)
        assertEquals(AnimeDetailsViewModel.ERROR_SCAN_FAILED, state.error)
        assertTrue(
            "a truncated listing must never render as the episode inventory",
            state.episodes.isEmpty(),
        )
        assertEquals("and it must not have been cached", 0, scanCacheDao.count())
    }

    @Test
    fun `retry re-runs the same scan for the same session after a failure`() = runTest {
        givenVirtualMain()
        var failing = true
        val client = ScriptedHttpClient { _ ->
            if (failing) throw IOException("network down") else episodesPage()
        }
        val vm = newViewModel(client)

        vm.load("sess-retry")
        testScheduler.advanceUntilIdle()
        assertEquals("network down", vm.uiState.value.error)

        failing = false
        vm.retry()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("the retry is bound to the same real scan", state.error)
        assertEquals("sess-retry", state.session)
        assertEquals(2, state.episodes.size)
    }

    // -----------------------------------------------------------------------
    // Re-seed / supersede / blank-session guards
    // -----------------------------------------------------------------------

    @Test
    fun `reloading the already-loaded session keeps the state and does not rescan`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val vm = newViewModel(client)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val rendered = vm.uiState.value
        val requestsAfterFirstScan = client.requestedUrls.size
        assertFalse(rendered.loading)

        // What a recreated route's LaunchedEffect does (rotation, theme change…).
        vm.load("sess-1")
        testScheduler.advanceUntilIdle()

        assertEquals("state must survive the re-seed", rendered, vm.uiState.value)
        assertEquals(
            "no second repository call for the loaded session",
            requestsAfterFirstScan,
            client.requestedUrls.size,
        )
    }

    @Test
    fun `reloading an already-empty result keeps the empty state`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", emptyPage()) }
        val vm = newViewModel(client)

        vm.load("sess-empty")
        testScheduler.advanceUntilIdle()
        val empty = vm.uiState.value
        val requestsAfterFirstScan = client.requestedUrls.size
        assertNull(empty.error)

        // A recreated entry must never "upgrade" an answered empty scan into an
        // error, nor issue a second scan for it.
        vm.load("sess-empty")
        testScheduler.advanceUntilIdle()

        assertEquals("empty must survive the re-seed", empty, vm.uiState.value)
        assertNull("still not an error", vm.uiState.value.error)
        assertEquals("no second repository call", requestsAfterFirstScan, client.requestedUrls.size)
    }

    @Test
    fun `a failed scan is re-run when the same session is loaded again`() = runTest {
        givenVirtualMain()
        var failing = true
        val client = ScriptedHttpClient { _ ->
            if (failing) throw IOException("network down") else episodesPage()
        }
        val vm = newViewModel(client)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        assertEquals("network down", vm.uiState.value.error)
        val requestsAfterFailure = client.requestedUrls.size

        failing = false
        // The route's LaunchedEffect re-invocation after a failure (NOT retry())
        // is deliberately NOT guarded — the entry must self-heal.
        vm.load("sess-1")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("a re-created entry retries the real call", state.error)
        assertFalse("scan finished", state.loading)
        assertEquals(listOf(1f, 2f), state.episodes.map { it.number })
        assertTrue(
            "the failed scan really was re-issued",
            client.requestedUrls.size > requestsAfterFailure,
        )
    }

    @Test
    fun `a superseded scan never lands its result over the newer session`() = runTest {
        givenVirtualMain()
        val client = ScriptedHttpClient { url ->
            if (url.contains("sessA")) {
                // Long enough to outlive the virtual run if it were NOT cancelled.
                delay(30_000)
                episodesPage(title = "Anime A", startEpisode = 1)
            } else {
                episodesPage(title = "Anime B", startEpisode = 50)
            }
        }
        val vm = newViewModel(client)

        vm.load("sessA")
        testScheduler.runCurrent()
        vm.load("sessB")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("sessB", state.session)
        assertNull("the superseded scan must not surface an error", state.error)
        assertEquals(
            "the newer session's episodes (A would have produced 1, 2)",
            listOf(50f, 51f),
            state.episodes.map { it.number },
        )
    }

    @Test
    fun `a blank session fails fast without touching the repository`() = runTest {
        givenVirtualMain()
        val client = ScriptedHttpClient { throw AssertionError("repository must not be called") }
        val vm = newViewModel(client)

        vm.load("")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AnimeDetailsViewModel.ERROR_SCAN_FAILED, state.error)
        assertFalse("not left loading", state.loading)
        assertTrue("a failure never fabricates episodes", state.episodes.isEmpty())
        assertTrue("no repository call", client.requestedUrls.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Loading branch
    // -----------------------------------------------------------------------

    @Test
    fun `loading is exposed while the scan is in flight`() = runTest {
        givenVirtualMain()
        val client = ScriptedHttpClient { url ->
            delay(10_000)
            if (url.contains("m=search")) null else episodesPage()
        }
        val vm = newViewModel(client)

        vm.load("sess-1")
        testScheduler.runCurrent()
        assertTrue("the pending scan is visible as loading", vm.uiState.value.loading)

        testScheduler.advanceUntilIdle()
        val state = vm.uiState.value
        assertFalse("loading cleared once the scan settled", state.loading)
        assertEquals(2, state.episodes.size)
    }

    // -----------------------------------------------------------------------
    // Post-audio slice — observed scan progress through the ViewModel
    // -----------------------------------------------------------------------

    /** Page 1 of 2 — two rows, and the SERVER declares a second page. */
    private val pagedPageOne = """
        {"data": [
            {"episode": 1, "session": "ep1", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 2, "session": "ep2", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 2, "anime": {"title": "Paged Anime"}}
    """.trimIndent()

    /** Page 2 of 2 — one more row, same server-declared total. */
    private val pagedPageTwo = """
        {"data": [
            {"episode": 3, "session": "ep3", "title": "Third", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 2, "anime": {"title": "Paged Anime"}}
    """.trimIndent()

    @Test
    fun `scan progress is visible while the scan runs and clears when it settles`() = runTest {
        givenVirtualMain()
        // Every listing request answers except the SECOND page, which hangs —
        // so the in-flight state (progress included) can actually be observed.
        val client = ScriptedHttpClient { url ->
            when {
                url.contains("m=search") -> null
                url.contains("page=2") -> {
                    delay(10_000)
                    pagedPageTwo
                }
                else -> pagedPageOne
            }
        }
        val vm = newViewModel(client)

        vm.load("sess-paged")
        testScheduler.runCurrent()

        val inFlight = vm.uiState.value
        assertTrue("the scan is still running", inFlight.loading)
        val progress = inFlight.scanProgress
        assertNotNull("observed progress is published while the scan runs", progress)
        assertEquals(
            "and it is a REAL observation: one session, one page read, two declared",
            "Scanning episodes · session 1 of 1 · page 1 of 2",
            progress!!.describe(),
        )

        testScheduler.advanceUntilIdle()

        val settled = vm.uiState.value
        assertFalse(settled.loading)
        assertNull("no error from a scan that completed", settled.error)
        assertNull(
            "a finished screen has no progress left to claim",
            settled.scanProgress,
        )
        assertEquals(listOf(1f, 2f, 3f), settled.episodes.map { it.number })
    }

    @Test
    fun `a scan that fails after reading some pages leaves no progress line behind`() = runTest {
        givenVirtualMain()
        // Page 1 is read (so progress WAS reported), then the next page dies.
        val client = ScriptedHttpClient { url ->
            when {
                url.contains("m=search") -> null
                url.contains("page=2") -> throw IllegalStateException("connection reset")
                else -> pagedPageOne
            }
        }
        val vm = newViewModel(client)

        vm.load("sess-paged")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("connection reset", state.error)
        assertFalse("not left loading", state.loading)
        assertNull(
            "the failure rebuilt the whole state — no dangling progress from the pages that did load",
            state.scanProgress,
        )
        assertTrue("a failed scan never fabricates an episode list", state.episodes.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 11.5E-3 — episode selection → stream resolution
    // -----------------------------------------------------------------------

    @Test
    fun `selecting an episode records that exact episode and resolves its own play page`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver()
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episode = vm.uiState.value.episodes.first()
        assertNull("idle: nothing is selected until the user selects", vm.uiState.value.selectedEpisode)

        vm.selectEpisode(episode)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the selection is the REAL scanned episode, not a copy or a lookup key",
            episode,
            state.selectedEpisode,
        )
        assertEquals(1, resolver.calls.size)
        assertEquals(episode.playUrl, resolver.calls[0].playUrl)
        assertEquals(episode.audio, resolver.calls[0].audio)
        assertEquals(
            "the default settings snapshot resolves at the Python default quality " +
                "(configProvider defaults to AppContext.defaults())",
            1080,
            resolver.calls[0].quality,
        )
    }

    // -----------------------------------------------------------------------
    // persisted settings reach the resolution and the scan
    // -----------------------------------------------------------------------

    @Test
    fun `resolution requests the persisted quality and the queued download records it`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver()
        val vm = newViewModel(
            client,
            resolver,
            configProvider = { com.pahebatcher.app.domain.model.AppContext.defaults().copy(quality = 720) },
        )

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the resolver must be asked for the USER's persisted quality, not a constant",
            720,
            resolver.calls.single().quality,
        )

        // The hand-off records the quality THIS stream was really produced with.
        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()
        assertEquals(1, downloadQueue.submissions.size)
        assertEquals(
            "the queued download carries the persisted quality it resolved at",
            720,
            downloadQueue.submissions.single().quality,
        )
    }

    @Test
    fun `scan consumes the persisted cache ttl`() = runTest {
        givenVirtualMain()
        // cache_ttl = 0 disables the scan cache (EpisodeRepository.scan contract),
        // so "no cache row written" is the observable proof the value arrived.
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val vm = newViewModel(
            client,
            configProvider = { com.pahebatcher.app.domain.model.AppContext.defaults().copy(cacheTtl = 0) },
        )

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()

        assertEquals("episodes still scan", 2, vm.uiState.value.episodes.size)
        assertEquals(
            "cache_ttl 0 must reach EpisodeRepository.scan (cache disabled)",
            0,
            scanCacheDao.count(),
        )

        // The default snapshot writes the cache, proving the difference is the
        // injected setting and not a broken cache path.
        val caching = newViewModel(client)
        caching.load("sess-cached")
        testScheduler.advanceUntilIdle()
        assertEquals(1, scanCacheDao.count())
    }

    @Test
    fun `a settings read failure falls back to the python defaults instead of failing`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver()
        val vm = newViewModel(
            client,
            resolver,
            configProvider = { throw IllegalStateException("settings store unavailable") },
        )

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        assertEquals(
            "a settings failure must never fail the scan (fallback cache TTL 60)",
            2,
            vm.uiState.value.episodes.size,
        )
        assertEquals("the fallback snapshot still writes the cache", 1, scanCacheDao.count())

        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        assertEquals(
            "fallback quality = RESOLUTION_QUALITY (Python default 1080)",
            AnimeDetailsViewModel.RESOLUTION_QUALITY,
            resolver.calls.single().quality,
        )
        assertNull("a settings failure is never surfaced as a resolution error", vm.uiState.value.resolutionError)
    }

    @Test
    fun `a settings cancellation is rethrown and never becomes a resolution error`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver()
        // The scan reads `cache_ttl` first, so it must succeed here; the
        // cancellation happens on the RESOLUTION's quality read — the exact path
        // this test pins (a cancelled settings read must rethrow out of the
        // resolution coroutine instead of being dressed up as an error).
        var settingsReads = 0
        val vm = newViewModel(
            client,
            resolver,
            configProvider = {
                settingsReads++
                if (settingsReads == 1) {
                    com.pahebatcher.app.domain.model.AppContext.defaults()
                } else {
                    throw kotlinx.coroutines.CancellationException("superseded")
                }
            },
        )

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        assertTrue(
            "precondition: the scan completed (it owns the first settings read)",
            vm.uiState.value.episodes.isNotEmpty(),
        )
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        assertTrue(
            "the cancelled settings read must stop the resolution before the resolver",
            resolver.calls.isEmpty(),
        )
        assertNull(
            "a CancellationException must be rethrown, never dressed up as a user-visible error",
            vm.uiState.value.resolutionError,
        )
    }

    @Test
    fun `selecting an episode enters the resolving state before resolution completes`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver { _ ->
            delay(30_000)
            FakeStreamResolver.streamOf("https://cdn.example.invalid/slow.m3u8")
        }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episode = vm.uiState.value.episodes.first()

        vm.selectEpisode(episode)
        testScheduler.runCurrent()

        val pending = vm.uiState.value
        assertEquals(episode, pending.selectedEpisode)
        assertTrue("resolution is visible as in-flight", pending.resolving)
        assertNull("no result may appear before the resolver answers", pending.resolvedStream)
        assertNull("no failure may appear before the resolver answers", pending.resolutionError)

        testScheduler.advanceUntilIdle()
        val settled = vm.uiState.value
        assertFalse("resolving clears when the resolver settles", settled.resolving)
        assertNotNull("…and the resolver's real result lands", settled.resolvedStream)
    }

    @Test
    fun `a successful resolution yields exactly the stream the resolver returned`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val expected = StreamInfo(
            url = "https://cdn.example.invalid/uwu.m3u8?token=abc123",
            userAgent = "Mozilla/5.0 (test)",
            referer = "https://animepahe.com/play/sess-1/ep1",
            title = "Test Anime Episode 1 - AnimePahe",
            audio = "jpn",
            fansub = "SubsPlease",
        )
        val resolver = FakeStreamResolver { _ -> expected }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the state carries the resolver's result verbatim", expected, state.resolvedStream)
        assertEquals(expected.url, state.resolvedStream?.url)
        assertFalse("a success is not a loading state", state.resolving)
        assertNull("a success carries no failure", state.resolutionError)
        assertNull("a success leaves the scan alone", state.error)
    }

    @Test
    fun `a resolution failure surfaces the real failure and never claims success`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver { _ ->
            throw StreamResolutionException("Could not resolve Kwik URL: https://kwik.si/e/abc")
        }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the resolver's own failure is what the UI shows",
            "Could not resolve Kwik URL: https://kwik.si/e/abc",
            state.resolutionError,
        )
        assertNull("a failure must never be dressed up as a resolved stream", state.resolvedStream)
        assertFalse("a settled failure is not still resolving", state.resolving)
        assertFalse("a resolution failure is not a scan failure", state.loading)
        assertNull(state.error)
    }

    @Test
    fun `a blank resolution failure message falls back to the honest copy`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver { _ -> throw StreamResolutionException("   ") }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        assertEquals(
            AnimeDetailsViewModel.ERROR_RESOLUTION_FAILED,
            vm.uiState.value.resolutionError,
        )
        assertNull(vm.uiState.value.resolvedStream)
    }

    @Test
    fun `retry re-runs the same resolution for the same selected episode after a failure`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        var failing = true
        val resolver = FakeStreamResolver { _ ->
            if (failing) throw StreamResolutionException("network down")
            FakeStreamResolver.streamOf("https://cdn.example.invalid/recovered.m3u8")
        }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episode = vm.uiState.value.episodes.first()
        vm.selectEpisode(episode)
        testScheduler.advanceUntilIdle()
        assertEquals("network down", vm.uiState.value.resolutionError)

        failing = false
        vm.retryResolution()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the retry targets the SAME selected episode", episode, state.selectedEpisode)
        assertEquals("https://cdn.example.invalid/recovered.m3u8", state.resolvedStream?.url)
        assertNull("the retry is bound to the same real resolution", state.resolutionError)
        assertEquals(2, resolver.calls.size)
        assertEquals(episode.playUrl, resolver.calls[1].playUrl)
        // The scan was never repeated by a resolution retry.
        assertEquals(1, scanCacheDao.count())
    }

    @Test
    fun `selecting another episode supersedes the in-flight resolution so no stale result lands`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val completed = mutableListOf<String>()
        val resolver = FakeStreamResolver { call ->
            if (call.playUrl.contains("ep1")) {
                // Outlives the virtual run if the superseded job were NOT cancelled.
                delay(30_000)
                completed += call.playUrl
                FakeStreamResolver.streamOf("https://cdn.example.invalid/stale.m3u8")
            } else {
                completed += call.playUrl
                FakeStreamResolver.streamOf("https://cdn.example.invalid/fresh.m3u8")
            }
        }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episodes = vm.uiState.value.episodes
        assertEquals(2, episodes.size)

        vm.selectEpisode(episodes[0])
        testScheduler.runCurrent()          // the first resolution is now in flight
        vm.selectEpisode(episodes[1])
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the NEWER selection wins", episodes[1], state.selectedEpisode)
        assertEquals(
            "the superseded resolution must never overwrite the newer result",
            "https://cdn.example.invalid/fresh.m3u8",
            state.resolvedStream?.url,
        )
        assertFalse("stale results must not be pending either", state.resolving)
        assertNull("a superseded failure must not surface as an error", state.resolutionError)
        assertFalse(
            "the superseded job must be cancelled, not merely ignored",
            completed.any { it.contains("ep1") },
        )
        assertEquals(2, resolver.calls.size)
    }

    @Test
    fun `re-seeding the loaded session keeps the selection and does not rescan`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val vm = newViewModel(client, FakeStreamResolver())

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()
        val rendered = vm.uiState.value
        val requestsAfterResolution = client.requestedUrls.size
        assertNotNull(rendered.selectedEpisode)
        assertNotNull(rendered.resolvedStream)

        // What a recreated route's LaunchedEffect does (rotation, theme change…).
        vm.load("sess-1")
        testScheduler.advanceUntilIdle()

        assertEquals("selection + result must survive the re-seed", rendered, vm.uiState.value)
        assertEquals(
            "no second repository call for the loaded session",
            requestsAfterResolution,
            client.requestedUrls.size,
        )
    }

    @Test
    fun `re-tapping the active selection does not repeat the resolution`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver { _ ->
            delay(10_000)
            FakeStreamResolver.streamOf("https://cdn.example.invalid/tapped.m3u8")
        }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episodes = vm.uiState.value.episodes

        // While it is still pending, a second tap on the SAME row must not start a
        // second Cloudflare/Kwik round-trip (AGENTS §10).
        vm.selectEpisode(episodes[0])
        testScheduler.runCurrent()
        assertTrue("resolution is pending", vm.uiState.value.resolving)
        assertEquals(1, resolver.calls.size)
        vm.selectEpisode(episodes[0])
        testScheduler.runCurrent()
        assertEquals("no duplicate in-flight resolution", 1, resolver.calls.size)

        testScheduler.advanceUntilIdle()
        val resolved = vm.uiState.value.resolvedStream
        assertNotNull("the pending resolution settled", resolved)

        // …and once resolved, re-tapping keeps the result instead of re-fetching.
        vm.selectEpisode(episodes[0])
        testScheduler.advanceUntilIdle()
        assertEquals("no repeat of a completed resolution", 1, resolver.calls.size)
        assertEquals(resolved, vm.uiState.value.resolvedStream)

        // The guard is scoped to the ACTIVE selection: another episode still resolves.
        vm.selectEpisode(episodes[1])
        testScheduler.advanceUntilIdle()
        assertEquals(2, resolver.calls.size)
        assertEquals(episodes[1], vm.uiState.value.selectedEpisode)
    }

    @Test
    fun `re-seeding while a resolution is in flight keeps it running`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver { _ ->
            delay(10_000)
            FakeStreamResolver.streamOf("https://cdn.example.invalid/inflight.m3u8")
        }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episode = vm.uiState.value.episodes.first()
        vm.selectEpisode(episode)
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.resolving)
        val requestsWhilePending = client.requestedUrls.size

        // What a recreated route's LaunchedEffect does while the resolver is busy.
        vm.load("sess-1")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the selection survived the re-seed", episode, state.selectedEpisode)
        assertEquals(
            "the in-flight resolution survived the re-seed",
            "https://cdn.example.invalid/inflight.m3u8",
            state.resolvedStream?.url,
        )
        assertFalse("and it settled", state.resolving)
        assertNull(state.resolutionError)
        assertEquals("no second scan", requestsWhilePending, client.requestedUrls.size)
    }

    @Test
    fun `retrying a resolution with no selection is a no-op`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver()
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val idle = vm.uiState.value
        assertNull("nothing selected yet", idle.selectedEpisode)

        vm.retryResolution()
        testScheduler.advanceUntilIdle()

        assertEquals("nothing to retry, so the resolver is never called", 0, resolver.calls.size)
        assertEquals("and the state stays idle", idle, vm.uiState.value)
    }

    // -----------------------------------------------------------------------
    // Resolved stream → download queue (11.5E-4)
    // -----------------------------------------------------------------------

    @Test
    fun `download hands the resolved stream and the scan context to the queue`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply {
            stubJson("m=release", episodesPage(title = "Spy x Family"))
        }
        val expected = StreamInfo(
            url = "https://cdn.example.invalid/uwu.m3u8?token=abc123",
            userAgent = "Mozilla/5.0 (test)",
            referer = "https://kwik.si/e/abc123",
            title = "Spy x Family Episode 1 - AnimePahe",
            audio = "jpn",
            fansub = "SubsPlease",
        )
        val vm = newViewModel(client, FakeStreamResolver { _ -> expected })

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episode = vm.uiState.value.episodes.first()
        vm.selectEpisode(episode)
        testScheduler.advanceUntilIdle()

        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()

        assertEquals("exactly one hand-off", 1, downloadQueue.submissions.size)
        val submission = downloadQueue.submissions.single()
        assertEquals("the anime session the scan ran for", "sess-1", submission.animeSession)
        assertEquals("the title the scan resolved", "Spy x Family", submission.animeTitle)
        assertEquals(
            "the host the scan used — never a hard-coded one",
            AnimeRepository.currentHost,
            submission.host,
        )
        assertEquals(
            "the contract quality (11.5E-3)",
            AnimeDetailsViewModel.RESOLUTION_QUALITY,
            submission.quality,
        )
        assertEquals("the exact scanned episode", episode, submission.episode)
        assertEquals(
            "the stream verbatim — including the URL/UA/Referer the worker needs",
            expected,
            submission.stream,
        )
        assertEquals(expected.referer, submission.stream.referer)

        val state = vm.uiState.value
        assertTrue("the queue accepted it", state.enqueued)
        assertFalse("…and is no longer queueing", state.enqueuing)
        assertNull(state.enqueueError)
        assertNull("queueing never disturbs the resolution", state.resolutionError)
        assertEquals(expected, state.resolvedStream)
    }

    @Test
    fun `download is impossible before a stream is actually resolved`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val resolver = FakeStreamResolver { _ ->
            throw StreamResolutionException("Could not resolve Kwik URL: https://kwik.si/e/abc")
        }
        val vm = newViewModel(client, resolver)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "nothing resolved → nothing may be submitted to the pipeline",
            0,
            downloadQueue.submissions.size,
        )
        assertFalse(vm.uiState.value.enqueued)
        assertFalse(vm.uiState.value.enqueuing)
        assertNull(vm.uiState.value.enqueueError)
    }

    @Test
    fun `a queue failure is reported honestly and the same call can be retried`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val queue = FakeEpisodeDownloadQueue().apply {
            failNextCall = IllegalStateException("database is locked")
        }
        val vm = newViewModel(client, downloadQueue = queue)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()

        val failed = vm.uiState.value
        assertEquals("the real failure, never a fake success", "database is locked", failed.enqueueError)
        assertFalse("a failed hand-off is not queued", failed.enqueued)
        assertFalse(failed.enqueuing)
        assertEquals(0, downloadQueue.submissions.size)

        // The same control retries the SAME call (it stays bound to the resolved stream).
        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()

        val retried = vm.uiState.value
        assertNull("the retry cleared the old failure", retried.enqueueError)
        assertTrue("…and this time it really queued", retried.enqueued)
        assertEquals(1, downloadQueue.submissions.size)
    }

    @Test
    fun `a second tap while queueing does not submit twice`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val vm = newViewModel(client)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        vm.downloadResolvedEpisode()
        val pending = vm.uiState.value
        assertTrue(
            "queueing is published before the coroutine runs, so the guard holds",
            pending.enqueuing,
        )
        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()

        assertEquals("one episode, one submission", 1, downloadQueue.submissions.size)
        assertTrue(vm.uiState.value.enqueued)
    }

    @Test
    fun `queueing state belongs to the selection and resets for another episode`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val vm = newViewModel(client)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episodes = vm.uiState.value.episodes
        vm.selectEpisode(episodes[0])
        testScheduler.advanceUntilIdle()
        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.enqueued)

        // Another episode starts its own resolution: the queued flag of the
        // PREVIOUS selection must not claim this one is queued.
        vm.selectEpisode(episodes[1])

        val state = vm.uiState.value
        assertFalse("the new selection is not queued yet", state.enqueued)
        assertFalse("…nor queueing", state.enqueuing)
        assertNull(state.enqueueError)
    }

    // ── Slice B (DECISIONS 146) — the repository's verdict is what gets shown ─

    @Test
    fun `a suppressed hand-off reports the repository's verdict instead of a success`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val queue = FakeEpisodeDownloadQueue().apply {
            nextOutcome = EnqueueOutcome.AlreadyDownloaded
        }
        val vm = newViewModel(client, downloadQueue = queue)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the caller reports exactly what the pipeline said",
            EnqueueOutcome.AlreadyDownloaded,
            state.enqueueOutcome,
        )
        assertFalse("an already-downloaded episode is NOT a fresh enqueue", state.enqueued)
        assertNull("…and it did not fail either", state.enqueueError)
        assertFalse(state.enqueuing)
    }

    @Test
    fun `enqueued is true only for the accepted outcome`() {
        listOf(
            EnqueueOutcome.Accepted to true,
            EnqueueOutcome.AlreadyActive to false,
            EnqueueOutcome.AlreadyDownloaded to false,
            EnqueueOutcome.OutputMissing to false,
            EnqueueOutcome.AlreadyPaused to false,
            EnqueueOutcome.Refused to false,
        ).forEach { (outcome, expected) ->
            assertEquals(
                "only $outcome may count as a fresh enqueue",
                expected,
                AnimeDetailsUiState(enqueueOutcome = outcome).enqueued,
            )
        }
        assertFalse(
            "no hand-off at all is not a fresh enqueue either",
            AnimeDetailsUiState().enqueued,
        )
    }

    @Test
    fun `reselecting an episode never inherits the previous verdict`() = runTest {
        givenVirtualMain()
        val client = FakeHttpClient().apply { stubJson("m=release", episodesPage()) }
        val queue = FakeEpisodeDownloadQueue().apply {
            nextOutcome = EnqueueOutcome.AlreadyPaused
        }
        val vm = newViewModel(client, downloadQueue = queue)

        vm.load("sess-1")
        testScheduler.advanceUntilIdle()
        val episodes = vm.uiState.value.episodes
        vm.selectEpisode(episodes[0])
        testScheduler.advanceUntilIdle()
        vm.downloadResolvedEpisode()
        testScheduler.advanceUntilIdle()

        assertEquals(EnqueueOutcome.AlreadyPaused, vm.uiState.value.enqueueOutcome)

        // Review finding H-3 bug (b): the NEXT selection must not inherit this
        // verdict, or its Download control disappears for an episode nothing
        // has ever been done to.
        vm.selectEpisode(episodes[1])

        val state = vm.uiState.value
        assertNull("the new selection carries no verdict yet", state.enqueueOutcome)
        assertFalse("…so its Download control stays offered", state.enqueued)
    }

    @Test
    fun `each outcome has its own message and none promises an action the app lacks`() {
        val messages = listOf(
            EnqueueOutcome.Accepted to "In the download queue",
            EnqueueOutcome.AlreadyActive to "Already in the download queue",
            EnqueueOutcome.AlreadyDownloaded to
                "Already downloaded — delete the existing file to download it again",
            EnqueueOutcome.OutputMissing to "Previous download is missing — starting it again",
            EnqueueOutcome.AlreadyPaused to "Paused — resume it from Downloads",
            EnqueueOutcome.Refused to
                "Not queued — the download was cancelled, failed or removed; try again",
        )
        messages.forEach { (outcome, expected) ->
            assertEquals("the wording for $outcome", expected, enqueueNotice(outcome))
        }
        assertEquals(
            "the six verdicts must stay distinguishable",
            messages.size,
            messages.map { it.second }.distinct().size,
        )
        // F1: no message may promise work that did not happen. `Refused` means
        // NO Worker exists, so it must never render the success string.
        assertNotEquals(
            "a refused hand-off must never read as a successful enqueue",
            enqueueNotice(EnqueueOutcome.Refused),
            enqueueNotice(EnqueueOutcome.Accepted),
        )
        assertFalse(
            "a refused hand-off must not mark the episode enqueued",
            AnimeDetailsUiState(enqueueOutcome = EnqueueOutcome.Refused).enqueued,
        )
    }

    /** Per-URL scripted client (same seam as HomeViewModelTest's). */
    private class ScriptedHttpClient(
        private val respond: suspend (url: String) -> String?,
    ) : HttpClient {
        val requestedUrls = mutableListOf<String>()

        override suspend fun fetchJson(url: String): String? {
            requestedUrls.add(url)
            return respond(url)
        }

        override suspend fun fetchHtml(url: String): String? {
            requestedUrls.add(url)
            return respond(url)
        }
    }
}

/**
 * Test-only [StreamResolver] (AGENTS §5 — mocks live in tests only).
 *
 * Production binds the real `StreamResolverImpl` through `AppContainer`; that
 * production graph is asserted by the instrumentation wiring test, never by this
 * fake. Every call is recorded so the tests can prove WHICH episode play page,
 * quality and audio the ViewModel actually requested.
 */
private class FakeStreamResolver(
    private val respond: suspend (call: Call) -> StreamInfo = { defaultStream() },
) : StreamResolver {

    data class Call(val playUrl: String, val quality: Int, val audio: String)

    val calls = mutableListOf<Call>()

    override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
        val call = Call(playUrl = playUrl, quality = quality, audio = audio)
        calls += call
        return respond(call)
    }

    companion object {
        fun streamOf(url: String) = StreamInfo(
            url = url,
            userAgent = "Mozilla/5.0 (test)",
            referer = "https://animepahe.com/play/sess-1/ep1",
        )

        private fun defaultStream() = streamOf("https://cdn.example.invalid/default.m3u8")
    }
}

/**
 * Test-only [EpisodeDownloadQueue] (AGENTS §5 — mocks live in tests only).
 *
 * Production binds `DownloadRepository` through `AppContainer`; the real
 * repository contract (session row → episode row → stream info → worker) is
 * asserted by the instrumentation hand-off test, never here. This fake records
 * what the ViewModel handed over and can be made to fail the NEXT call once, so
 * the honest-failure branch is reachable.
 */
private class FakeEpisodeDownloadQueue : EpisodeDownloadQueue {

    val submissions = mutableListOf<ResolvedEpisodeSubmission>()

    /**
     * Slice B: the verdict the next hand-off reports.
     *
     * Defaults to `Accepted` so every pre-existing test keeps exercising the
     * success path it was written for; a test that wants to see what the screen
     * does for a SUPPRESSED request sets this first.
     */
    var nextOutcome: EnqueueOutcome = EnqueueOutcome.Accepted

    /** When set, the next call throws it (and clears itself). */
    var failNextCall: Throwable? = null

    override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
        val failure = failNextCall
        if (failure != null) {
            failNextCall = null
            throw failure
        }
        submissions += submission
        return nextOutcome
    }
}
