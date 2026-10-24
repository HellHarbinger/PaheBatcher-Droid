package com.pahebatcher.app.ui.details

import com.pahebatcher.app.data.network.NetworkMonitor
import com.pahebatcher.app.data.network.NetworkStatus
import com.pahebatcher.app.data.network.UnknownNetworkMonitor
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.AudioOptionRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import com.pahebatcher.app.data.repository.FakeScanCacheDao
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.data.stream.AudioSelector
import com.pahebatcher.app.data.stream.FakeKwikResolver
import com.pahebatcher.app.data.stream.StreamResolverImpl
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.AudioProbeState
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.WebViewRequestBridge
import com.pahebatcher.app.domain.stream.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * Bounded audio-option discovery wired end-to-end (SPEC §22, DECISIONS 144).
 *
 * Unlike [AnimeDetailsAudioSelectionTest] — which drives the ViewModel with a
 * recording resolver double — this file runs the probe AND the resolution
 * through the REAL [StreamResolverImpl], REAL [PlayPageParser] and REAL
 * [ScanCacheRepository], so the two mandated outcome cases are proven against
 * real parsing:
 *
 * * an audio the RELEASE LISTING lacks but the PLAY PAGE carries proceeds to
 *   resolution and returns that exact audio (DECISIONS 144.4);
 * * an audio the play page genuinely lacks fails with the explicit message
 *   naming it, without ever asking Kwik for a substitute (SPEC §22).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailsAudioOptionDiscoveryTest {

    // -----------------------------------------------------------------------
    // The discovered option set is what the screen offers — not the listing
    // -----------------------------------------------------------------------

    @Test
    fun `the option set comes from the probe and is wider than the release listing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness()
        val vm = h.newViewModel()

        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the probe saw jpn AND eng on the sampled play pages",
            listOf("jpn", "eng"),
            state.audioOptions,
        )
        assertEquals(
            "…while the release listing only ever reports jpn for both numbers",
            listOf("jpn"),
            availableAudios(state.episodes),
        )
        assertEquals(AudioProbeState.OK_VALUES, state.audioProbeState)
        assertEquals(
            "the deterministic starting context still prefers jpn when it is offered",
            "jpn",
            state.selectedAudio,
        )
        assertEquals(
            "a second user-driven load reuses the COMPLETED probe — no extra request",
            2,
            h.provider.requests.size,
        )

        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()
        assertEquals(
            "repeat load: still exactly one pass (target count = 2)",
            2,
            h.provider.requests.size,
        )
    }

    @Test
    fun `the initial context is drawn from the discovered set even when the listing lacks it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness(
            // Both play pages advertise eng+kor and nothing else — the release
            // listing says "jpn" for every row, which is not an option at all.
            ep1 = menu("eng", "kor"),
            ep2 = menu("eng", "kor"),
        )
        val vm = h.newViewModel()

        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("eng", "kor"), state.audioOptions)
        assertEquals(
            "jpn-if-any-else-FIRST of the discovered set (DECISIONS 144)",
            "eng",
            state.selectedAudio,
        )
        assertEquals(
            "the rows render that context on every number",
            setOf(1f, 2f),
            visibleEpisodeRows(state).map { it.number }.toSet(),
        )
        assertTrue(visibleEpisodeRows(state).all { it.audio == "eng" })
    }

    @Test
    fun `a failed probe falls back to the release listing instead of an empty option set`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness(ep1 = null, ep2 = null) // every play page fails
        val vm = h.newViewModel()

        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "FAILED is reported honestly…",
            AudioProbeState.FAILED,
            state.audioProbeState,
        )
        assertEquals(
            "…and the option set is the LISTING fallback, never empty",
            listOf("jpn"),
            state.audioOptions,
        )
        assertEquals("jpn", state.selectedAudio)
        assertEquals(
            "a failed probe is retried exactly once per user-driven load (2 targets)",
            2,
            h.provider.requests.size,
        )
    }

    @Test
    fun `a partial probe keeps only what the successful pages offered`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness(ep1 = menu("jpn", "eng"), ep2 = null)
        val vm = h.newViewModel()

        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            AudioProbeState.PARTIAL,
            state.audioProbeState,
        )
        assertEquals(
            "only the pages that answered contribute; the failure never blanks the set",
            listOf("jpn", "eng"),
            state.audioOptions,
        )
        assertEquals(2, h.provider.requests.size)
    }

    @Test
    fun `a second ViewModel over the same cache costs zero probe requests`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness()
        h.newViewModel().let { vm ->
            vm.load("sess-probe")
            testScheduler.advanceUntilIdle()
        }
        assertEquals(2, h.provider.requests.size)

        // A fresh ViewModel (process death / navigation back) reads the SAME
        // Room row through the SAME repository.
        val second = h.newViewModel()
        second.load("sess-probe")
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the COMPLETED cached state costs zero requests",
            2,
            h.provider.requests.size,
        )
        assertEquals(listOf("jpn", "eng"), second.uiState.value.audioOptions)
        assertEquals(
            AudioProbeState.OK_VALUES,
            second.uiState.value.audioProbeState,
        )
    }

    // -----------------------------------------------------------------------
    // The two mandated resolution outcomes, through the REAL resolver
    // -----------------------------------------------------------------------

    @Test
    fun `an audio the listing lacks but the play page carries proceeds to resolution`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness()
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        vm.setSelectedAudio("eng")
        val row = visibleEpisodeRows(vm.uiState.value).first { it.number == 1f }
        assertEquals(
            "number 1's listing row is jpn — the carrier carries the request instead",
            "jpn",
            vm.uiState.value.episodes.first { it.number == 1f }.audio,
        )
        assertEquals("eng", row.audio)

        vm.selectEpisode(row)
        testScheduler.advanceUntilIdle()

        assertNull(
            "the listing could not veto it — the PLAY PAGE is the authority (144.4)",
            vm.uiState.value.resolutionError,
        )
        assertEquals("eng", vm.uiState.value.resolvedStream?.audio)
        assertNotNull(vm.uiState.value.resolvedStream)
        assertEquals(
            "Kwik was asked for the eng page, not the jpn one",
            "https://kwik.si/e/eng1",
            h.kwik.lastResolvedUrl,
        )
        assertFalse(vm.uiState.value.resolving)
    }

    @Test
    fun `an audio the play page genuinely lacks fails explicitly and never substitutes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness() // ep2's play page carries jpn only
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        vm.setSelectedAudio("eng")
        val row = visibleEpisodeRows(vm.uiState.value).first { it.number == 2f }
        assertEquals("eng", row.audio)

        vm.selectEpisode(row)
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the failure names the REQUESTED audio",
            AudioSelector.unavailableMessage("eng"),
            vm.uiState.value.resolutionError,
        )
        assertNull("no stream is fabricated", vm.uiState.value.resolvedStream)
        assertFalse(vm.uiState.value.resolving)
        assertNull(
            "the substitute was never fetched — Kwik was not asked at all",
            h.kwik.lastResolvedUrl,
        )
        assertTrue(
            "the resolver did fetch the page it was pointed at",
            h.provider.requests.contains(row.playUrl),
        )
    }

    @Test
    fun `a number that does not exist in the listing keeps its unchanged failure path`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness()
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        val before = h.provider.requests.size
        vm.selectEpisode(
            EpisodeInfo(
                number = 99f,
                session = "ghost",
                title = "Ghost",
                fansub = "SubsPlease",
                audio = "jpn",
                playUrl = "https://animepahe.com/play/sess-probe/ghost",
            ),
        )
        testScheduler.advanceUntilIdle()

        assertEquals(
            AudioSelector.unavailableMessage("jpn"),
            vm.uiState.value.resolutionError,
        )
        assertNull(vm.uiState.value.resolvedStream)
        assertEquals(
            "no request at all — the missing NUMBER is not a probe, not a fetch",
            before,
            h.provider.requests.size,
        )
        assertNull(h.kwik.lastResolvedUrl)
    }

    // -----------------------------------------------------------------------
    // Post-audio slice — the targeted re-probe from an ALREADY-LOADED screen
    //
    // `retry()` cannot do any of this: it calls `load()`, whose guard returns
    // immediately for a session that is already on screen. Everything below
    // therefore exercises `retryAudioProbe()`, and asserts the two properties
    // that make it safe — one bounded pass per explicit tap, and no state
    // damage to the screen the tap came from.
    // -----------------------------------------------------------------------

    /** The play-page URLs the 2-target probe samples for this fixture. */
    private val targetEp1 = "https://animepahe.com/play/sess-probe/ep1"
    private val targetEp2 = "https://animepahe.com/play/sess-probe/ep2"

    @Test
    fun `a retry from an already-loaded screen runs exactly one bounded pass and publishes it`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // Every play page fails → FAILED, options are the listing fallback.
            val h = dualAudioHarness(ep1 = null, ep2 = null)
            val vm = h.newViewModel()

            vm.load("sess-probe")
            testScheduler.advanceUntilIdle()

            val afterLoad = vm.uiState.value
            assertEquals(AudioProbeState.FAILED, afterLoad.audioProbeState)
            val requestsAfterLoad = h.provider.requests.size
            assertEquals(
                "the FAILED pass already cost exactly one request per target",
                2,
                requestsAfterLoad,
            )

            // The pages answer now — the SAME two targets the scan selected.
            h.provider.pages[targetEp1] = menu("jpn", "eng")
            h.provider.pages[targetEp2] = menu("jpn")

            vm.retryAudioProbe()
            // Started, but not finished: the provider suspends mid-request.
            testScheduler.runCurrent()
            assertTrue(
                "an already-loaded screen must say that network work is in flight",
                vm.uiState.value.audioProbing,
            )
            testScheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse("the flag clears on its own completion", state.audioProbing)
            assertEquals(AudioProbeState.OK_VALUES, state.audioProbeState)
            assertEquals(listOf("jpn", "eng"), state.audioOptions)
            assertEquals(
                "exactly ONE more pass: the same 2 targets, never per-episode",
                requestsAfterLoad + 2,
                h.provider.requests.size,
            )
            assertEquals(
                "the loaded screen survives the retry — title unchanged",
                afterLoad.title,
                state.title,
            )
            assertEquals(
                "episodes unchanged",
                afterLoad.episodes.map { it.number },
                state.episodes.map { it.number },
            )
            assertEquals(
                "a context the new set still offers is KEPT",
                afterLoad.selectedAudio,
                state.selectedAudio,
            )
            assertNull("no error is invented by a successful retry", state.error)
            assertFalse(state.loading)
        }

    @Test
    fun `a retry tap while one is already running is swallowed instead of stacked`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness(ep1 = null, ep2 = null)
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()
        val requestsAfterLoad = h.provider.requests.size

        h.provider.pages[targetEp1] = menu("jpn", "eng")
        h.provider.pages[targetEp2] = menu("jpn")

        vm.retryAudioProbe()
        testScheduler.runCurrent() // the first pass is now in flight
        vm.retryAudioProbe() // double tap / a callback that fires again
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the second tap cost NOTHING — one pass at a time",
            requestsAfterLoad + 2,
            h.provider.requests.size,
        )
        assertEquals(
            "…and the pass that did run really completed",
            AudioProbeState.OK_VALUES,
            vm.uiState.value.audioProbeState,
        )
        assertFalse(vm.uiState.value.audioProbing)
    }

    @Test
    fun `retrying a completed probe costs zero requests`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness() // completes on load
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        assertEquals(AudioProbeState.OK_VALUES, vm.uiState.value.audioProbeState)
        assertEquals(2, h.provider.requests.size)

        vm.retryAudioProbe()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "a COMPLETED result is refused up front — a button that claims to \"check again\" must not",
            2,
            h.provider.requests.size,
        )
        assertFalse(vm.uiState.value.audioProbing)
        assertEquals(AudioProbeState.OK_VALUES, vm.uiState.value.audioProbeState)
    }

    @Test
    fun `a retry that fails again keeps the degraded state honest`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness(ep1 = null, ep2 = null)
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()
        val requestsAfterLoad = h.provider.requests.size
        assertEquals(listOf("jpn"), vm.uiState.value.audioOptions)

        vm.retryAudioProbe()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "it really tried again — one more bounded pass",
            requestsAfterLoad + 2,
            h.provider.requests.size,
        )
        assertEquals(
            "and a second failure is still reported as a failure, not a result",
            AudioProbeState.FAILED,
            state.audioProbeState,
        )
        assertEquals(
            "the fallback stays the LISTING, never presented as probe output",
            listOf("jpn"),
            state.audioOptions,
        )
        assertFalse(state.audioProbing)
        assertNull("a failed retry is not a screen error", state.error)
    }

    @Test
    fun `a retry rewrites only the probe member so cache freshness survives`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness(ep1 = null, ep2 = null)
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        val rowBefore = h.dao.getBySession("sess-probe")
        assertNotNull("the scan persisted a cache row", rowBefore)

        h.provider.pages[targetEp1] = menu("jpn", "eng")
        h.provider.pages[targetEp2] = menu("jpn")
        vm.retryAudioProbe()
        testScheduler.advanceUntilIdle()
        assertEquals(AudioProbeState.OK_VALUES, vm.uiState.value.audioProbeState)

        val rowAfter = h.dao.getBySession("sess-probe")
        assertNotNull(rowAfter)
        assertEquals(
            "a retry must not refresh scan freshness",
            rowBefore!!.cachedAt,
            rowAfter!!.cachedAt,
        )
        assertEquals(
            "…and must not touch the stored TTL either",
            rowBefore.cacheTtlMinutes,
            rowAfter.cacheTtlMinutes,
        )

        val requestsAfterRetry = h.provider.requests.size

        // The completed result really landed: a SECOND ViewModel over the same
        // row reuses it without a single page request.
        val second = h.newViewModel()
        second.load("sess-probe")
        testScheduler.advanceUntilIdle()
        assertEquals(
            "the retried pass was persisted, so the next screen costs zero requests",
            requestsAfterRetry,
            h.provider.requests.size,
        )
        assertEquals(AudioProbeState.OK_VALUES, second.uiState.value.audioProbeState)
        assertEquals(listOf("jpn", "eng"), second.uiState.value.audioOptions)
    }

    @Test
    fun `a title with no probe targets never starts a retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness(releaseJson = noRowsJson)
        val vm = h.newViewModel()

        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("nothing to render", state.episodes.isEmpty())
        assertEquals(
            "no targets → no probe result, stated honestly",
            AudioProbeState.NOT_PROBED,
            state.audioProbeState,
        )
        assertNull("a genuinely empty listing is not an error", state.error)
        val requestsAfterLoad = h.provider.requests.size

        vm.retryAudioProbe()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "nothing to probe, so nothing is probed — no invented result",
            requestsAfterLoad,
            h.provider.requests.size,
        )
        assertFalse(vm.uiState.value.audioProbing)
        assertEquals(AudioProbeState.NOT_PROBED, vm.uiState.value.audioProbeState)
    }

    @Test
    fun `retry before anything has loaded does nothing at all`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness()
        val vm = h.newViewModel()

        vm.retryAudioProbe()
        testScheduler.advanceUntilIdle()

        assertEquals(0, h.provider.requests.size)
        assertFalse(vm.uiState.value.audioProbing)
        assertTrue("the screen is untouched — still waiting for its scan", vm.uiState.value.loading)
    }

    @Test
    fun `retry is refused while a batch is running because its mapping is frozen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = dualAudioHarness(ep1 = null, ep2 = null)
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()
        assertEquals(AudioProbeState.FAILED, vm.uiState.value.audioProbeState)

        // The pages are healthy again, so ONLY the running-batch guard can be
        // what keeps the state at FAILED.
        h.provider.pages[targetEp1] = menu("jpn", "eng")
        h.provider.pages[targetEp2] = menu("jpn")

        vm.setBatchMode(true)
        vm.toggleBatchEpisode(vm.uiState.value.episodes.first())
        vm.startBatchDownload()
        testScheduler.runCurrent()
        assertTrue("the batch really is running", vm.uiState.value.batchRunning)

        vm.retryAudioProbe()
        testScheduler.advanceUntilIdle()

        assertFalse("the batch finished", vm.uiState.value.batchRunning)
        assertEquals(
            "the option set was NOT rewritten under a running batch (DECISIONS 143)",
            AudioProbeState.FAILED,
            vm.uiState.value.audioProbeState,
        )
        assertFalse(vm.uiState.value.audioProbing)
    }

    @Test
    fun `retry is refused while offline instead of spending a doomed request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val monitor = FakeNetworkMonitor()
        val h = dualAudioHarness(ep1 = null, ep2 = null)
        val vm = h.newViewModel(networkMonitor = monitor)
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()

        monitor.status.value = NetworkStatus.OFFLINE
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.offline)

        val requestsBefore = h.provider.requests.size
        vm.retryAudioProbe()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "zero traffic while the banner is up (DECISIONS 97.4)",
            requestsBefore,
            h.provider.requests.size,
        )
        assertFalse(vm.uiState.value.audioProbing)
        assertEquals(
            "and the state is left exactly as it was, not faked into a result",
            AudioProbeState.FAILED,
            vm.uiState.value.audioProbeState,
        )
    }

    @Test
    fun `a retry that narrows the set drops a context it no longer offers`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // PARTIAL: only the first page answers, so the set is [jpn, eng].
        val h = dualAudioHarness(ep1 = menu("jpn", "eng"), ep2 = null)
        val vm = h.newViewModel()
        vm.load("sess-probe")
        testScheduler.advanceUntilIdle()
        assertEquals(AudioProbeState.PARTIAL, vm.uiState.value.audioProbeState)
        assertEquals(listOf("jpn", "eng"), vm.uiState.value.audioOptions)

        vm.setSelectedAudio("eng")
        assertEquals("eng", vm.uiState.value.selectedAudio)

        // Both sampled pages now answer — with jpn only.
        h.provider.pages[targetEp1] = menu("jpn")
        h.provider.pages[targetEp2] = menu("jpn")
        vm.retryAudioProbe()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AudioProbeState.OK_VALUES, state.audioProbeState)
        assertEquals(listOf("jpn"), state.audioOptions)
        assertEquals(
            "a context the new set no longer offers must not be left dangling",
            "jpn",
            state.selectedAudio,
        )
        assertFalse(state.audioProbing)
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** Scriptable connectivity: the offline refusal test drives it explicitly. */
    private class FakeNetworkMonitor : NetworkMonitor {
        val status = MutableStateFlow(NetworkStatus.UNKNOWN)
        override val networkStatus: Flow<NetworkStatus> = status
    }

    /** A listing that exists but carries no episodes → no probe targets at all. */
    private val noRowsJson = """
        {"data": [], "last_page": 1, "anime": {"title": "No Rows"}}
    """.trimIndent()

    private fun menu(vararg audios: String): String = buildString {
        append("<html><head><title>Probe</title></head><body>")
        append("""<div id="resolutionMenu">""")
        audios.forEachIndexed { index, audio ->
            append(
                """<button data-src="https://kwik.si/e/$audio$index" """ +
                    """data-resolution="1080" data-audio="$audio">1080p</button>""",
            )
        }
        append("</div></body></html>")
    }

    /**
     * Two numbers in ONE release session, so the target set is
     * `[ep1 play page, ep2 play page]` (minimum + opened maximum).
     */
    private val scanJson = """
        {"data": [
            {"episode": 1, "session": "ep1", "title": "E1", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 2, "session": "ep2", "title": "E2", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Probe Dual"}}
    """.trimIndent()

    private class Harness(
        val provider: RecordingPlayPageProvider,
        val kwik: FakeKwikResolver,
        val cache: ScanCacheRepository,
        val dao: FakeScanCacheDao,
        val episodeRepository: EpisodeRepository,
        val pageLock: Mutex,
        val queue: RecordingQueue,
    ) {
        fun newViewModel(
            networkMonitor: NetworkMonitor = UnknownNetworkMonitor,
        ): AnimeDetailsViewModel = AnimeDetailsViewModel(
            episodeRepository = episodeRepository,
            streamResolver = StreamResolverImpl(provider, kwik, pageLock),
            downloadQueue = queue,
            configProvider = { AppContext.defaults() },
            audioOptionRepository = AudioOptionRepository(provider, pageLock, cache),
            networkMonitor = networkMonitor,
        )
    }

    private fun dualAudioHarness(
        ep1: String? = menu("jpn", "eng"),
        ep2: String? = menu("jpn"),
        releaseJson: String = "",
    ): Harness {
        val client = FakeHttpClient().apply { stubJson("m=release", releaseJson.ifEmpty { scanJson }) }
        val dao = FakeScanCacheDao()
        val cache = ScanCacheRepository(dao)
        val provider = RecordingPlayPageProvider().apply {
            pages["https://animepahe.com/play/sess-probe/ep1"] = ep1
            pages["https://animepahe.com/play/sess-probe/ep2"] = ep2
        }
        val pageLock = Mutex()
        return Harness(
            provider = provider,
            kwik = FakeKwikResolver().apply { m3u8Url = "https://cdn.example.invalid/uwu.m3u8" },
            cache = cache,
            dao = dao,
            episodeRepository = EpisodeRepository(
                animeRepository = AnimeRepository(client),
                scanCacheRepository = cache,
            ),
            pageLock = pageLock,
            queue = RecordingQueue(),
        )
    }

    private class RecordingQueue : EpisodeDownloadQueue {
        val submissions = mutableListOf<ResolvedEpisodeSubmission>()

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            return EnqueueOutcome.Accepted
        }

        override suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo): Boolean = false

        override suspend fun resolvedAheadCount(): Int = 0
    }

    /** Answers the probe and the resolver from one per-URL page table. */
    private class RecordingPlayPageProvider : CloudflareSessionProvider {
        val pages = mutableMapOf<String, String?>()
        val requests = mutableListOf<String>()

        override fun currentState(): CloudflareSessionProvider.SessionState =
            CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun getSessionInfo(): CloudflareSessionProvider.SessionInfo =
            CloudflareSessionProvider.SessionInfo(
                state = CloudflareSessionProvider.SessionState.ESTABLISHED,
                userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36",
                cloudflareCompleted = true,
                finalUrl = "https://animepahe.com",
                isReady = true,
                lastValidatedAt = System.currentTimeMillis(),
                cookieCount = 5,
                activeHost = "animepahe.com",
            )

        override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo =
            getSessionInfo()

        override suspend fun isSessionValid(): Boolean = true

        override fun invalidateSession() {}

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>,
        ): CloudflareSessionProvider.PageResult {
            requests += url
            delay(1)
            val html = pages[url]
            return if (html == null) {
                CloudflareSessionProvider.PageResult(
                    success = false,
                    html = null,
                    json = null,
                    finalUrl = "",
                    statusCode = 0,
                    error = "Connection failed",
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList(),
                )
            } else {
                CloudflareSessionProvider.PageResult(
                    success = true,
                    html = html,
                    json = null,
                    finalUrl = url,
                    statusCode = 200,
                    error = null,
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList(),
                )
            }
        }

        override fun releaseSession() {}
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
}
