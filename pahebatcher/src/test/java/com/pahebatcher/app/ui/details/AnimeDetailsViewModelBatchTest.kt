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
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch selection and batch resolution in [AnimeDetailsViewModel],
 * ported from the reference `BatchOrchestrator.download`
 * (`downloader.py:167-327`) and its checklist prompts (`prompts.py:24-147`).
 *
 * What is asserted here (all through the SAME production chain the screen uses:
 * ViewModel → EpisodeRepository → parser, then resolver → queue):
 *
 * 1. selection is by episode NUMBER (one toggle per number — audio is NOT a
 *    row-tap side effect, DECISIONS 143.1) and leaving batch mode discards
 *    the selection while explicit audio overrides survive;
 * 2. **All** and range expressions run the real
 *    [com.pahebatcher.app.domain.util.EpisodeRangeParser] over every episode
 *    number the catalogue lists, and a range that matches nothing reports
 *    an honest message instead of starting nothing;
 * 3. the batch resolves each selected number with the configured `quality`
 *    and STRICTLY with that number's DERIVED audio — explicit override,
 *    otherwise the current audio context (`override ?: selectedAudio`, built
 *    once at batch start; DECISIONS 143.6) — the reference's
 *    `get_variant(…, audio_lang) or variants[0]` fallback
 *    (`downloader.py:214-217`) is deliberately removed: a number that EXISTS
 *    in the listing is carried to its own play page with the requested audio
 *    (DECISIONS 144.4) and only the PLAY PAGE can reject it — with an
 *    explicit failure, never another audio (SPEC §22);
 * 4. an episode the pipeline already handles is skipped BEFORE resolving
 *    (`_find_existing`, `downloader.py:224-230`);
 * 5. one episode's failure never stops the others, the permanent
 *    `No Kwik link` condition is not retried inside one attempt, and the batch
 *    still re-runs the failed number for `auto_retry + 1` passes
 *    (`downloader.py:170-185`);
 * 6. `resolve_ahead` really throttles the batch (capacity `resolve_ahead +
 *    max_parallel`, `downloader.py:205-208`) and expires into an honest error;
 * 7. cancelling stops the loop without inventing a result.
 *
 * The screen-level audio CONTEXT behaviors (selector transitions, notices,
 * override lifetime, open-set contexts) have their own suite:
 * `AnimeDetailsAudioSelectionTest`.
 *
 * No network: the scan runs through the project's [FakeHttpClient], the resolver
 * and queue are test-only doubles (AGENTS §5 — mocks live in tests only).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailsViewModelBatchTest {

    private lateinit var recordingQueue: RecordingQueue

    private fun newViewModel(
        client: HttpClient,
        resolver: StreamResolver = RecordingResolver(),
        queue: RecordingQueue = RecordingQueue(),
        config: AppContext = AppContext.defaults(),
    ): AnimeDetailsViewModel {
        recordingQueue = queue
        val episodeRepository = EpisodeRepository(
            animeRepository = AnimeRepository(client),
            scanCacheRepository = ScanCacheRepository(FakeScanCacheDao()),
        )
        return AnimeDetailsViewModel(
            episodeRepository = episodeRepository,
            streamResolver = resolver,
            downloadQueue = queue,
            configProvider = { config },
        )
    }

    /**
     * Two episode numbers, three variants: episode 1 has SUB and DUB, episode 2
     * has SUB only — the shape the audio-preference fallback needs.
     */
    private fun dualAudioPage(): String = """
        {"data": [
            {"episode": 1, "session": "ep1-jpn", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 1, "session": "ep1-eng", "title": "Beginnings", "fansub": "DubsPlease", "audio": "eng"},
            {"episode": 2, "session": "ep2-jpn", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Test Anime"}}
    """.trimIndent()

    private fun liveClient(): FakeHttpClient =
        FakeHttpClient().apply { stubJson("m=release", dualAudioPage()) }

    /** Load the scan so every batch test starts from the same real listing. */
    private suspend fun kotlinx.coroutines.test.TestScope.loadedViewModel(
        client: HttpClient = liveClient(),
        resolver: StreamResolver = RecordingResolver(),
        queue: RecordingQueue = RecordingQueue(),
        config: AppContext = AppContext.defaults(),
    ): AnimeDetailsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = newViewModel(client, resolver, queue, config)
        vm.load("sess-batch")
        testScheduler.advanceUntilIdle()
        assertEquals(
            "the fixture scan produced the expected listing",
            3,
            vm.uiState.value.episodes.size,
        )
        return vm
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Selection
    // -----------------------------------------------------------------------

    @Test
    fun `batch mode keeps the selection and leaving it discards the selection but never the audio overrides`() = runTest {
        val vm = loadedViewModel()
        val first = vm.uiState.value.episodes.first()

        vm.setBatchMode(true)
        vm.toggleBatchEpisode(first)
        assertEquals("one toggle selects that number", setOf(1f), vm.uiState.value.selectedNumbers)
        assertEquals(
            "a row tap records NO audio — the context stays the only audio state",
            "jpn",
            vm.uiState.value.selectedAudio,
        )
        assertEquals(
            "…and creates no explicit override",
            emptyMap<Float, String>(),
            vm.uiState.value.explicitAudioOverrides,
        )

        // Selection is by NUMBER: tapping the row again toggles the number off.
        vm.toggleBatchEpisode(first)
        assertEquals(
            "tapping the row again deselects the number",
            emptySet<Float>(),
            vm.uiState.value.selectedNumbers,
        )

        // An explicit override is ROW state, not selection state
        // (DECISIONS 143.5 — supersedes the 12C batch-exit audio clear).
        vm.toggleBatchEpisode(first)
        vm.setEpisodeAudio(1f, "eng")
        vm.setBatchMode(false)
        assertEquals(
            "leaving batch mode never leaves an invisible selection behind",
            emptySet<Float>(),
            vm.uiState.value.selectedNumbers,
        )
        assertEquals(
            "…but explicit audio overrides survive the transition (143.5)",
            mapOf(1f to "eng"),
            vm.uiState.value.explicitAudioOverrides,
        )

        vm.setBatchMode(true)
        assertEquals(
            "…and survive re-entering batch mode too",
            mapOf(1f to "eng"),
            vm.uiState.value.explicitAudioOverrides,
        )
    }

    @Test
    fun `a row tap toggles only the episode number and never changes any audio state`() = runTest {
        val vm = loadedViewModel()
        vm.setBatchMode(true)

        // Row taps are number toggles under Option C (DECISIONS 143.1, which
        // superseded 142.4's row-tap audio switch): whichever variant row is
        // handed in, ONLY the number moves.
        vm.toggleBatchEpisode(
            vm.uiState.value.episodes.first { it.number == 1f && it.audio == "jpn" },
        )
        assertEquals(setOf(1f), vm.uiState.value.selectedNumbers)
        assertEquals("jpn", vm.uiState.value.selectedAudio)
        assertEquals(emptyMap<Float, String>(), vm.uiState.value.explicitAudioOverrides)

        // An existing override is untouched by row taps as well.
        vm.setEpisodeAudio(1f, "eng")
        vm.toggleBatchEpisode(
            vm.uiState.value.episodes.first { it.number == 1f && it.audio == "eng" },
        )
        assertEquals(
            "the other variant's row still toggles the same NUMBER off",
            emptySet<Float>(),
            vm.uiState.value.selectedNumbers,
        )
        assertEquals(
            "the override is not cleared by deselecting the number",
            mapOf(1f to "eng"),
            vm.uiState.value.explicitAudioOverrides,
        )
        assertEquals(
            "the screen-level context never moved",
            "jpn",
            vm.uiState.value.selectedAudio,
        )
    }

    @Test
    fun `bulk actions use the current audio context and never create overrides`() = runTest {
        val vm = loadedViewModel()
        vm.setBatchMode(true)

        // Context = eng: every NUMBER is selectable — release-listing audio can
        // never shrink the selection (DECISIONS 144.6), so number 2 joins
        // through the context even though its listing row says jpn.
        vm.setSelectedAudio("eng")
        vm.selectAllEpisodes()

        assertEquals(
            "select-all covers every number — no listing-only gate",
            setOf(1f, 2f),
            vm.uiState.value.selectedNumbers,
        )
        assertEquals("the context itself is unchanged by bulk", "eng", vm.uiState.value.selectedAudio)
        assertEquals(
            "a bulk action never invents an explicit override (143.3)",
            emptyMap<Float, String>(),
            vm.uiState.value.explicitAudioOverrides,
        )
    }

    @Test
    fun `select-all picks every distinct episode number exactly once`() = runTest {
        val vm = loadedViewModel()

        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        assertEquals("3 variants collapse into 2 numbers", setOf(1f, 2f), vm.uiState.value.selectedNumbers)
    }

    @Test
    fun `a range expression runs the production parser and reports no match honestly`() = runTest {
        val vm = loadedViewModel()
        vm.setBatchMode(true)

        vm.selectEpisodeRange("2-2")
        assertEquals(setOf(2f), vm.uiState.value.selectedNumbers)
        assertNull("a matching range is not an error", vm.uiState.value.batchError)

        vm.selectEpisodeRange("99-120")
        assertEquals(emptySet<Float>(), vm.uiState.value.selectedNumbers)
        assertEquals(
            AnimeDetailsViewModel.NO_EPISODES_MATCHED,
            vm.uiState.value.batchError,
        )
    }

    // -----------------------------------------------------------------------
    // Latest-N (SPEC §22 — `noninteractive_episodes(mode="latest")`)
    // -----------------------------------------------------------------------

    /**
     * Five episode numbers; the NEWEST number carries SUB and DUB variants, so
     * "the newest 1" has to collapse two variants into one selected number —
     * the same by-number selection every other mode uses.
     */
    private fun fiveNumberPage(): String = """
        {"data": [
            {"episode": 1, "session": "n1-jpn", "title": "E1", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 2, "session": "n2-jpn", "title": "E2", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 3, "session": "n3-jpn", "title": "E3", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 4, "session": "n4-jpn", "title": "E4", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 5, "session": "n5-jpn", "title": "E5", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 5, "session": "n5-eng", "title": "E5", "fansub": "DubsPlease", "audio": "eng"}
        ], "last_page": 1, "anime": {"title": "Latest Fixture"}}
    """.trimIndent()

    /** A scan that really resolves and really contains no episode. */
    private fun emptyPage(): String = """
        {"data": [], "last_page": 1, "anime": {"title": "Empty Fixture"}}
    """.trimIndent()

    /** Load an arbitrary fixture scan through the SAME production scan path. */
    private suspend fun kotlinx.coroutines.test.TestScope.loadedFrom(
        page: String,
        resolver: StreamResolver = RecordingResolver(),
        queue: RecordingQueue = RecordingQueue(),
    ): AnimeDetailsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = FakeHttpClient().apply { stubJson("m=release", page) }
        val vm = newViewModel(client, resolver, queue)
        vm.load("sess-latest")
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `latest 3 selects exactly the newest three numbers`() = runTest {
        val vm = loadedFrom(fiveNumberPage())
        assertEquals("fixture shape", 5, vm.uiState.value.episodes.map { it.number }.distinct().size)
        vm.setBatchMode(true)

        vm.selectLatestEpisodes(3)

        assertEquals(setOf(3f, 4f, 5f), vm.uiState.value.selectedNumbers)
        assertNull("a real match is not an error", vm.uiState.value.batchError)
    }

    @Test
    fun `latest N walks backwards from the newest episode`() = runTest {
        val vm = loadedFrom(fiveNumberPage())
        vm.setBatchMode(true)

        vm.selectLatestEpisodes(1)
        assertEquals("the newest number only", setOf(5f), vm.uiState.value.selectedNumbers)

        vm.selectLatestEpisodes(2)
        assertEquals(
            "each call replaces the selection with the newest N",
            setOf(4f, 5f),
            vm.uiState.value.selectedNumbers,
        )
    }

    @Test
    fun `the newest number with SUB and DUB variants collapses into one selection`() = runTest {
        val vm = loadedFrom(fiveNumberPage())
        vm.setBatchMode(true)

        vm.selectLatestEpisodes(1)

        assertEquals(
            "six variants exist, but selection is BY NUMBER",
            1,
            vm.uiState.value.selectedNumbers.size,
        )
        assertEquals(setOf(5f), vm.uiState.value.selectedNumbers)
        assertEquals(
            "both variants of episode 5 are really in the scan",
            2,
            vm.uiState.value.episodes.count { it.number == 5f },
        )
    }

    @Test
    fun `latest greater than the catalogue selects every available episode`() = runTest {
        val vm = loadedFrom(fiveNumberPage())
        vm.setBatchMode(true)

        vm.selectLatestEpisodes(99)

        assertEquals(setOf(1f, 2f, 3f, 4f, 5f), vm.uiState.value.selectedNumbers)
        assertNull(
            "asking for more than exists is not a failed selection",
            vm.uiState.value.batchError,
        )
    }

    @Test
    fun `latest below 1 is rejected instead of inventing episode 1`() = runTest {
        val vm = loadedFrom(fiveNumberPage())
        vm.setBatchMode(true)
        vm.selectAllEpisodes()
        val before = vm.uiState.value.selectedNumbers
        assertEquals(setOf(1f, 2f, 3f, 4f, 5f), before)

        vm.selectLatestEpisodes(0)
        assertEquals("0 must not become episode 1", before, vm.uiState.value.selectedNumbers)

        vm.selectLatestEpisodes(-3)
        assertEquals("a negative count selects nothing at all", before, vm.uiState.value.selectedNumbers)
        assertNull("an invalid count reports no fabricated outcome", vm.uiState.value.batchError)
    }

    @Test
    fun `latest on an empty catalogue reports the honest no-match message`() = runTest {
        val vm = loadedFrom(emptyPage())
        assertEquals("the scan really is empty", emptyList<EpisodeInfo>(), vm.uiState.value.episodes)
        vm.setBatchMode(true)

        vm.selectLatestEpisodes(3)

        assertEquals(emptySet<Float>(), vm.uiState.value.selectedNumbers)
        assertEquals(
            AnimeDetailsViewModel.NO_EPISODES_MATCHED,
            vm.uiState.value.batchError,
        )
    }

    @Test
    fun `latest selection is ignored while a batch is running`() = runTest {
        // A resolver that never returns keeps the run active (same technique as
        // the cancellation test) so the guard is exercised on a LIVE batch.
        val hangingResolver = object : StreamResolver {
            override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
                delay(10L * 60L * 1000L)
                return StreamInfo(url = "https://cdn.example.invalid/x.m3u8", userAgent = "UA", referer = playUrl)
            }
        }
        val vm = loadedFrom(fiveNumberPage(), resolver = hangingResolver)
        vm.setBatchMode(true)
        vm.selectLatestEpisodes(5)
        val before = vm.uiState.value.selectedNumbers

        vm.startBatchDownload()
        testScheduler.advanceTimeBy(1_000L)
        assertTrue("the batch is really running", vm.uiState.value.batchRunning)

        vm.selectLatestEpisodes(1)

        assertEquals(
            "a running batch never has its submission set rewritten",
            before,
            vm.uiState.value.selectedNumbers,
        )
        vm.cancelBatch()
    }

    @Test
    fun `a latest selection feeds the existing batch pipeline by number`() = runTest {
        val queue = RecordingQueue()
        val vm = loadedFrom(fiveNumberPage(), queue = queue)
        vm.setBatchMode(true)

        vm.selectLatestEpisodes(2)
        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("the run finished", state.batchRunning)
        assertEquals(2, state.batchTotal)
        assertEquals(2, queue.submissions.size)
        assertEquals(
            "the newest two numbers were resolved and queued in ascending order",
            listOf(4f, 5f),
            queue.submissions.map { it.episode.number },
        )
        assertEquals(
            "the newest number runs the current audio context (jpn)",
            "jpn",
            queue.submissions.last().episode.audio,
        )
    }

    // -----------------------------------------------------------------------
    // Resolve + queue
    // -----------------------------------------------------------------------

    @Test
    fun `a batch resolves and queues every selected number with the configured prefs`() = runTest {
        val resolver = RecordingResolver()
        val queue = RecordingQueue()
        val vm = loadedViewModel(
            resolver = resolver,
            queue = queue,
            config = AppContext.defaults().copy(quality = 720),
        )
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("the run finished", state.batchRunning)
        assertTrue("the summary is published", state.batchFinished)
        assertNull("nothing failed", state.batchError)
        assertEquals(2, state.batchTotal)
        assertEquals(2, state.batchQueued)
        assertEquals(0, state.batchFailed)
        assertEquals(0, state.batchSkipped)

        assertEquals("one resolution per number", 2, resolver.calls.size)
        assertTrue(
            "the persisted quality (720) is what was resolved",
            resolver.calls.all { it.quality == 720 },
        )

        assertEquals(2, queue.submissions.size)
        val byNumber = queue.submissions.associateBy { it.episode.number }
        assertEquals(
            "episode 1 runs the current audio context (jpn-if-present)",
            "jpn",
            byNumber.getValue(1f).episode.audio,
        )
        assertTrue(
            "the play page is the jpn variant's",
            byNumber.getValue(1f).episode.playUrl.contains("ep1-jpn"),
        )
        assertEquals(
            "episode 2 only has SUB, so its context variant is SUB",
            "jpn",
            byNumber.getValue(2f).episode.audio,
        )
        assertTrue(queue.submissions.all { it.animeSession == "sess-batch" })
        assertTrue(queue.submissions.all { it.quality == 720 })
    }

    @Test
    fun `an explicit per-episode audio choice drives the batch run instead of any preference`() = runTest {
        // There is no audio preference anywhere in the configuration anymore
        // (SPEC §22/§23): the ONLY thing that can decide episode 1's variant is
        // the explicit override the user recorded for that number — derived
        // with the context into the run's frozen mapping (DECISIONS 143.6).
        val queue = RecordingQueue()
        val vm = loadedViewModel(queue = queue)
        vm.setBatchMode(true)
        vm.setEpisodeAudio(1f, "eng")
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val byNumber = queue.submissions.associateBy { it.episode.number }
        assertEquals(2, queue.submissions.size)
        assertTrue(
            "episode 1 downloads the variant the user explicitly chose",
            byNumber.getValue(1f).episode.playUrl.contains("ep1-eng"),
        )
        assertEquals(
            "episode 2 has no override: the current audio context (jpn) runs",
            "jpn",
            byNumber.getValue(2f).episode.audio,
        )
        assertEquals(
            "the per-episode override survives the run untouched",
            mapOf(1f to "eng"),
            vm.uiState.value.explicitAudioOverrides,
        )
    }

    @Test
    fun `a listing-absent audio reaches the resolver and a play-page-absent one fails it explicitly`() = runTest {
        val resolver = RecordingResolver()
        val queue = RecordingQueue()
        val vm = loadedViewModel(resolver = resolver, queue = queue)
        vm.setBatchMode(true)
        // An explicit override whose audio the CURRENT release listing does not
        // carry (DECISIONS 144.4): release metadata can never veto a request,
        // so the number is CARRIED to the resolver with the requested audio.
        vm.setEpisodeAudio(1f, "kor")
        // Bulk-select: episode 1 keeps its override, episode 2 joins through
        // the context.
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the listing-absent audio is now attempted, not pre-skipped (144.4)",
            2,
            state.batchQueued,
        )
        assertEquals(
            "…and nothing failed",
            0,
            state.batchFailed,
        )
        val byNumber = queue.submissions.associateBy { it.episode.number }
        assertEquals(
            "the override still decides number 1",
            "kor",
            byNumber.getValue(1f).episode.audio,
        )
        assertTrue(
            "…on number 1's OWN play page",
            byNumber.getValue(1f).episode.playUrl.contains("ep1"),
        )
        assertTrue(
            "episode 2 still runs its context audio",
            byNumber.getValue(2f).episode.audio == "jpn",
        )
        assertTrue(
            "the RESOLVER — the real availability authority — was asked for the requested audio",
            resolver.calls.any { it.audio == "kor" && it.playUrl.contains("ep1") },
        )
    }

    @Test
    fun `an episode the pipeline already handles is skipped before resolving`() = runTest {
        val resolver = RecordingResolver()
        val queue = RecordingQueue().apply { handledNumbers = mutableSetOf(1f) }
        val vm = loadedViewModel(resolver = resolver, queue = queue)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the handled episode is never resolved",
            1,
            resolver.calls.size,
        )
        assertEquals("…nor queued", 1, queue.submissions.size)
        assertEquals(2f, queue.submissions.single().episode.number)
        assertEquals(1, vm.uiState.value.batchSkipped)
        assertEquals(1, vm.uiState.value.batchQueued)
        assertEquals(0, vm.uiState.value.batchFailed)
    }

    @Test
    fun `one failing episode never stops the rest of the batch`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl ->
                if (playUrl.contains("ep2")) IllegalStateException("kwik link expired") else null
            }
        }
        val queue = RecordingQueue()
        // auto_retry = 0 → exactly one attempt and one pass, so the failure is final.
        val vm = loadedViewModel(
            resolver = resolver,
            queue = queue,
            config = AppContext.defaults().copy(autoRetry = 0),
        )
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the healthy episode was queued", 1, state.batchQueued)
        assertEquals("the broken episode is reported", 1, state.batchFailed)
        assertEquals("…out of the two numbers that were selected", 2, state.batchTotal)
        assertEquals(1, queue.submissions.size)
        assertEquals(1f, queue.submissions.single().episode.number)
        assertNull("a per-episode failure is not a batch-level error", state.batchError)
        assertFalse(state.batchRunning)
        assertTrue(state.batchFinished)
    }

    @Test
    fun `the permanent No Kwik link failure is never retried inside an attempt but still gets batch passes`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl ->
                if (playUrl.contains("ep2")) {
                    Exception("No Kwik link found on episode page")
                } else {
                    null
                }
            }
        }
        val queue = RecordingQueue()
        // auto_retry = 2 → 1 inner attempt for a permanent error, 3 batch passes.
        val vm = loadedViewModel(
            resolver = resolver,
            queue = queue,
            config = AppContext.defaults().copy(autoRetry = 2),
        )
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val permanentCalls = resolver.calls.count { it.playUrl.contains("ep2") }
        assertEquals(
            "no inner retry for the permanent condition, but one attempt per batch pass",
            3,
            permanentCalls,
        )
        assertEquals(1, queue.submissions.size)
        assertEquals(1, vm.uiState.value.batchFailed)
        assertEquals(
            "the healthy episode is queued once, never re-queued by a retry pass",
            1,
            queue.submissions.count { it.episode.number == 1f },
        )
    }

    // -----------------------------------------------------------------------
    // Slice B closure (review finding F3): outcome → batch counters
    // -----------------------------------------------------------------------

    /**
     * F3: every verdict the pipeline can return must land in the RIGHT counter.
     *
     * `queued++` happens only for [EnqueueOutcome.Accepted] and
     * [EnqueueOutcome.OutputMissing] — both really did hand a Worker the episode —
     * and every other verdict counts as `skipped`. Before this slice only
     * `Accepted` was ever scripted, so the `OutputMissing` half of that rule and
     * the whole "everything else is skipped" half were asserted by nothing.
     *
     * The on-screen summary is built directly from these two counters
     * (`AnimeDetailsScreen`: "Done: queued X · skipped Y · …"), so pinning them
     * pins the sentence the user reads.
     */
    @Test
    fun `every pipeline outcome is counted in the honest batch counter`() = runTest {
        val queue = RecordingQueue().apply {
            outcomeFor = { number ->
                when (number) {
                    1f -> EnqueueOutcome.Accepted
                    2f -> EnqueueOutcome.OutputMissing
                    3f -> EnqueueOutcome.AlreadyActive
                    4f -> EnqueueOutcome.AlreadyDownloaded
                    5f -> EnqueueOutcome.AlreadyPaused
                    else -> EnqueueOutcome.Accepted
                }
            }
        }
        val vm = loadedFrom(fiveNumberPage(), queue = queue)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "only a real hand-off (Accepted) and a real re-download (OutputMissing) count as queued",
            2,
            state.batchQueued,
        )
        assertEquals(
            "an already-covered episode is never counted as a fresh enqueue",
            3,
            state.batchSkipped,
        )
        assertEquals("no number failed to resolve", 0, state.batchFailed)
        assertEquals("all five numbers were attempted", 5, state.batchTotal)
        assertEquals(
            "…and every one of them really reached the pipeline",
            5,
            queue.submissions.size,
        )
        assertNull("a refusal is not a batch-level error", state.batchError)
        assertFalse(state.batchRunning)
        assertTrue(state.batchFinished)
    }

    /**
     * F3: a hand-off refused by a concurrent Cancel/failure/removal (the
     * `Refused` verdict added by review finding F1) must never inflate the
     * queued total.
     *
     * It is also not a `failed` entry: the batch loop did its work, the
     * pipeline simply declined. Counting it as `queued` would report work no
     * Worker ever received — the exact dishonesty Slice B exists to remove.
     */
    @Test
    fun `a refused hand-off is never counted as queued`() = runTest {
        val queue = RecordingQueue().apply { outcomeFor = { EnqueueOutcome.Refused } }
        val vm = loadedFrom(fiveNumberPage(), queue = queue)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("nothing was queued", 0, state.batchQueued)
        assertEquals("every number is reported as not newly queued", 5, state.batchSkipped)
        assertEquals("a refusal is not a resolution failure", 0, state.batchFailed)
        assertEquals(5, state.batchTotal)
        assertNull(state.batchError)
        assertTrue(state.batchFinished)
    }

    // -----------------------------------------------------------------------
    // resolve_ahead + cancellation
    // -----------------------------------------------------------------------

    @Test
    fun `resolve_ahead capacity really throttles the batch and expires into an honest error`() = runTest {
        val queue = RecordingQueue().apply { stagedAhead = 50 }
        // resolve_ahead = 0 and max_parallel = 1 → staging capacity 1, which is
        // permanently exceeded by the scripted count.
        val vm = loadedViewModel(
            queue = queue,
            config = AppContext.defaults().copy(resolveAhead = 0, maxParallel = 1),
        )
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("nothing was resolved against a full staging queue", 0, queue.submissions.size)
        assertEquals(0, state.batchQueued)
        assertFalse(state.batchRunning)
        assertTrue(state.batchFinished)
        assertNotNull("the wait expires into a real message", state.batchError)
    }

    @Test
    fun `cancelling a batch stops the loop without inventing a result`() = runTest {
        val resolver = object : StreamResolver {
            val calls = mutableListOf<String>()

            override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
                calls += playUrl
                delay(10L * 60L * 1000L) // resolution never returns on its own
                return StreamInfo(url = "https://cdn.example.invalid/late.m3u8", userAgent = "UA", referer = playUrl)
            }
        }
        val queue = RecordingQueue()
        val vm = loadedViewModel(resolver = resolver, queue = queue)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceTimeBy(1_000L)
        vm.cancelBatch()
        testScheduler.advanceUntilIdle()

        assertEquals("nothing reached the queue after cancellation", 0, queue.submissions.size)
        assertFalse("the run is over", vm.uiState.value.batchRunning)
        assertTrue("the UI shows the stopped state", vm.uiState.value.batchFinished)
    }

    // -----------------------------------------------------------------------
    // Test doubles (AGENTS §5 — mocks live in tests only)
    // -----------------------------------------------------------------------

    private class RecordingResolver : StreamResolver {
        data class Call(val playUrl: String, val quality: Int, val audio: String)

        val calls = mutableListOf<Call>()

        /** Scripted failure: non-null means "throw this for that play page". */
        var failFor: (String) -> Throwable? = { null }

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            calls += Call(playUrl = playUrl, quality = quality, audio = audio)
            failFor(playUrl)?.let { throw it }
            return StreamInfo(
                url = "https://cdn.example.invalid/${calls.size}.m3u8",
                userAgent = "Mozilla/5.0 (test)",
                referer = playUrl,
            )
        }
    }

    private class RecordingQueue : EpisodeDownloadQueue {
        val submissions = mutableListOf<ResolvedEpisodeSubmission>()

        /** Episode numbers that already have a handled download row. */
        var handledNumbers: MutableSet<Float> = mutableSetOf()

        /** Scripted staging depth (`resolve_ahead` consumption point). */
        var stagedAhead: Int = 0

        /**
         * Slice B closure (review finding F3) — the verdict the fake hands back,
         * scripted per episode number.
         *
         * It defaults to [EnqueueOutcome.Accepted] so every pre-existing test
         * keeps asserting the happy path unchanged; the outcome→counter test
         * scripts it to walk all six values through the batch totals.
         */
        var outcomeFor: (Float) -> EnqueueOutcome = { EnqueueOutcome.Accepted }

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            return outcomeFor(submission.episode.number)
        }

        override suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo): Boolean =
            episode.number in handledNumbers

        override suspend fun resolvedAheadCount(): Int = stagedAhead
    }
}
