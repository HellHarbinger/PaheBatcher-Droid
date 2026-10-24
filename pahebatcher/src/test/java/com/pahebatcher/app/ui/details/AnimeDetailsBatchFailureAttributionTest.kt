package com.pahebatcher.app.ui.details

import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import com.pahebatcher.app.data.repository.FakeScanCacheDao
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.data.stream.AudioSelector
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.FailureCategory
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.ResolutionFailureCode
import com.pahebatcher.app.domain.stream.StreamResolutionException
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.notification.DownloadNotifyPrefs
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice C — per-episode failure REASONS and the batch-failure notification.
 *
 * The gap this suite pins closed: the batch already knew which episode failed,
 * which audio it had asked for, and why — but published only a COUNT, so the
 * reason was constructed and then thrown away. What is asserted here, all
 * through the production chain the screen uses:
 *
 * 1. every reported failure carries the episode number AND the audio that was
 *    REQUESTED (SPEC §22 no-substitution: the report must name what was asked
 *    for, never another audio);
 * 2. a category is decided by TYPED ORIGIN at the moment of failure, and a
 *    plain exception whose message merely LOOKS like an audio rejection still
 *    classifies [FailureCategory.UNKNOWN] — never an invented cause;
 * 3. the count and the rendered list can never disagree;
 * 4. things that are not failures (already-handled, enqueue refusals) are never
 *    reported as failures;
 * 5. the ONE batch-failure notification is posted only for a run that really
 *    finished with failures, is suppressed by the `notify_failure` preference,
 *    and is never posted by a cancellation, a capacity abort, or a run whose
 *    early failure recovered on a later pass.
 *
 * No network: the scan runs through [FakeHttpClient]; the resolver and queue
 * are test-only doubles (AGENTS §5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailsBatchFailureAttributionTest {

    private val posted = mutableListOf<PostedBatch>()

    /**
     * Post-audio slice — what the ViewModel hands the notifier: the two counts,
     * the series title, and the typed-reason breakdown.
     */
    private data class PostedBatch(
        val failed: Int,
        val total: Int,
        val title: String,
        val reasonCounts: Map<String, Int>,
    )

    private fun newViewModel(
        client: HttpClient,
        resolver: StreamResolver,
        queue: EpisodeDownloadQueue,
        config: AppContext,
        notifyFailure: Boolean,
    ): AnimeDetailsViewModel {
        val episodeRepository = EpisodeRepository(
            animeRepository = AnimeRepository(client),
            scanCacheRepository = ScanCacheRepository(FakeScanCacheDao()),
        )
        return AnimeDetailsViewModel(
            episodeRepository = episodeRepository,
            streamResolver = resolver,
            downloadQueue = queue,
            configProvider = { config },
            notifyFailurePrefs = { DownloadNotifyPrefs(failure = notifyFailure) },
            batchFailureNotifier = { failed, total, animeTitle, reasonCounts ->
                posted += PostedBatch(failed, total, animeTitle, reasonCounts)
            },
        )
    }

    /**
     * Two numbers: episode 1 is dual-audio, episode 2 is Japanese-only — the
     * shape that lets one run show an attributed audio failure next to a
     * healthy episode.
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

    private suspend fun kotlinx.coroutines.test.TestScope.loadedViewModel(
        client: HttpClient = liveClient(),
        resolver: StreamResolver = RecordingResolver(),
        queue: RecordingQueue = RecordingQueue(),
        config: AppContext = AppContext.defaults().copy(autoRetry = 0),
        notifyFailure: Boolean = true,
    ): AnimeDetailsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = newViewModel(client, resolver, queue, config, notifyFailure)
        vm.load("sess-attribution")
        testScheduler.advanceUntilIdle()
        return vm
    }

    /** An episode number the release listing never listed. */
    private fun phantomEpisode(number: Float, audio: String) = EpisodeInfo(
        number = number,
        session = "phantom-$number",
        title = "Absent",
        fansub = "None",
        audio = audio,
        playUrl = "https://animesuge.to/play/sess-attribution/phantom-$number",
    )

    // -----------------------------------------------------------------------
    // 1. The missing-number branch — never an audio claim
    // -----------------------------------------------------------------------

    @Test
    fun `a number absent from the listing is EPISODE_NOT_IN_LISTING and never probes the resolver`() = runTest {
        val resolver = RecordingResolver()
        val queue = RecordingQueue()
        val vm = loadedViewModel(resolver = resolver, queue = queue)
        vm.setSelectedAudio("eng")
        vm.setBatchMode(true)
        vm.toggleBatchEpisode(phantomEpisode(3f, "eng"))

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val failure = vm.uiState.value.batchFailures.single()
        assertEquals(3f, failure.number)
        assertEquals(
            "the resolver was never consulted, so this can never be an audio rejection",
            FailureCategory.EPISODE_NOT_IN_LISTING,
            failure.category,
        )
        assertEquals("the REQUESTED audio is still named for the user", "eng", failure.audio)
        assertEquals(
            "the reason is number-centric, not audio-centric",
            "No variant available for this episode.",
            failure.reason,
        )
        assertTrue("no request at all — the missing NUMBER is not a probe", resolver.calls.isEmpty())
        assertTrue("nothing was queued", queue.submissions.isEmpty())
        assertEquals(1, vm.uiState.value.batchFailed)
        assertEquals(
            "the count and the list always agree",
            vm.uiState.value.batchFailed,
            vm.uiState.value.batchFailures.size,
        )
    }

    // -----------------------------------------------------------------------
    // 2. Resolution-stage attribution — typed origin only
    // -----------------------------------------------------------------------

    @Test
    fun `an unavailable requested audio is AUDIO_UNAVAILABLE from the resolver's typed evidence`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl ->
                if (playUrl.contains("ep2")) {
                    // Exactly the construction StreamResolverImpl makes when the
                    // page offered entries but none matched the requested audio.
                    StreamResolutionException(
                        AudioSelector.unavailableMessage("eng"),
                        code = ResolutionFailureCode.AUDIO_UNAVAILABLE,
                    )
                } else {
                    null
                }
            }
        }
        val vm = loadedViewModel(resolver = resolver)
        vm.setSelectedAudio("eng")
        vm.setBatchMode(true)
        vm.toggleBatchEpisode(phantomEpisode(2f, "eng"))

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val failure = vm.uiState.value.batchFailures.single()
        assertEquals(2f, failure.number)
        assertEquals(FailureCategory.AUDIO_UNAVAILABLE, failure.category)
        assertEquals("eng", failure.audio)
        assertTrue("this branch really did consult the resolver", resolver.calls.isNotEmpty())
    }

    @Test
    fun `a page that cannot be fetched is STREAM_RESOLUTION_FAILED, not an audio claim`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl ->
                if (playUrl.contains("ep2")) {
                    StreamResolutionException(
                        "Failed to fetch episode page via WebView session.",
                        code = ResolutionFailureCode.PAGE_FETCH_FAILED,
                    )
                } else {
                    null
                }
            }
        }
        val vm = loadedViewModel(resolver = resolver)
        vm.setBatchMode(true)
        vm.toggleBatchEpisode(phantomEpisode(2f, "jpn"))

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val failure = vm.uiState.value.batchFailures.single()
        assertEquals(FailureCategory.STREAM_RESOLUTION_FAILED, failure.category)
        assertEquals(
            "a page-fetch failure says nothing about any audio",
            "jpn",
            failure.audio,
        )
    }

    @Test
    fun `a plain exception wearing an audio message still classifies UNKNOWN`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl ->
                if (playUrl.contains("ep2")) {
                    // The exact sentence a REAL audio rejection would carry, but
                    // with no typed origin. Reading text back would mis-report
                    // this as "the audio was unavailable" — the typed-only rule
                    // must refuse.
                    IllegalStateException(AudioSelector.unavailableMessage("eng"))
                } else {
                    null
                }
            }
        }
        val vm = loadedViewModel(resolver = resolver)
        vm.setSelectedAudio("eng")
        vm.setBatchMode(true)
        vm.toggleBatchEpisode(phantomEpisode(2f, "eng"))

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val failure = vm.uiState.value.batchFailures.single()
        assertEquals(
            "message text is never consulted",
            FailureCategory.UNKNOWN,
            failure.category,
        )
        assertEquals(
            "…and `eng` really WAS what was requested, so the sentence is plausible — it is still not evidence",
            "eng",
            failure.audio,
        )
    }

    // -----------------------------------------------------------------------
    // 3. Identity and agreement across a mixed batch
    // -----------------------------------------------------------------------

    @Test
    fun `a mixed batch reports counters and per-episode reasons that agree exactly`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl -> if (playUrl.contains("ep2")) IllegalStateException("kwik link expired") else null }
        }
        val queue = RecordingQueue()
        val vm = loadedViewModel(resolver = resolver, queue = queue)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the healthy episode was queued", 1, state.batchQueued)
        assertEquals(1, state.batchFailed)
        assertEquals(2, state.batchTotal)
        assertEquals(
            "the list enumerates exactly what the count claims",
            state.batchFailed,
            state.batchFailures.size,
        )
        val failure = state.batchFailures.single()
        assertEquals("the identity of the failed episode", 2f, failure.number)
        assertEquals("the audio that was REQUESTED", "jpn", failure.audio)
        assertEquals(FailureCategory.UNKNOWN, failure.category)
        assertEquals("kwik link expired", failure.reason)
        assertNull("a per-episode failure is not a batch-level error", state.batchError)
    }

    // -----------------------------------------------------------------------
    // 4. Things that are not failures never become failures
    // -----------------------------------------------------------------------

    @Test
    fun `an episode the pipeline already handles is never reported as a failure`() = runTest {
        val queue = RecordingQueue().apply { handledNumbers = mutableSetOf(1f) }
        val vm = loadedViewModel(queue = queue)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.batchSkipped)
        assertEquals("a skip is not a failure", 0, vm.uiState.value.batchFailed)
        assertTrue(vm.uiState.value.batchFailures.isEmpty())
    }

    @Test
    fun `an enqueue refusal is never reported as a failure`() = runTest {
        val queue = RecordingQueue().apply { outcomeFor = { EnqueueOutcome.Refused } }
        val vm = loadedViewModel(queue = queue)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(2, vm.uiState.value.batchSkipped)
        assertEquals("a refusal is not a resolution failure", 0, vm.uiState.value.batchFailed)
        assertTrue(vm.uiState.value.batchFailures.isEmpty())
        assertTrue("a refusal posts no failure notification", posted.isEmpty())
    }

    @Test
    fun `a cancelled batch claims no per-episode failure and posts no notification`() = runTest {
        val resolver = object : StreamResolver {
            override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
                delay(10L * 60L * 1000L) // resolution never returns on its own
                return StreamInfo(url = "https://cdn.example.invalid/late.m3u8", userAgent = "UA", referer = playUrl)
            }
        }
        val vm = loadedViewModel(resolver = resolver)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceTimeBy(1_000L)
        vm.cancelBatch()
        testScheduler.advanceUntilIdle()

        assertTrue(
            "a run that never completed makes no per-episode claim",
            vm.uiState.value.batchFailures.isEmpty(),
        )
        assertTrue("and posts nothing", posted.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 5. The ONE notification
    // -----------------------------------------------------------------------

    @Test
    fun `a finished batch with failures posts exactly one notification naming the counts and the series`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl -> if (playUrl.contains("ep2")) IllegalStateException("gone") else null }
        }
        val vm = loadedViewModel(resolver = resolver)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "one notification per batch run, never one per episode or per pass",
            1,
            posted.size,
        )
        val (failed, total, title, _) = posted.single()
        assertEquals(1, failed)
        assertEquals(2, total)
        assertEquals("Test Anime", title)
        assertEquals(
            "an UNATTRIBUTABLE failure still names no reason — no invented cause",
            emptyMap<String, Int>(),
            posted.single().reasonCounts,
        )
    }

    @Test
    fun `the batch notification carries the typed failure reasons as a count breakdown`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl ->
                if (playUrl.contains("ep2")) {
                    StreamResolutionException(
                        AudioSelector.unavailableMessage("eng"),
                        code = ResolutionFailureCode.AUDIO_UNAVAILABLE,
                    )
                } else {
                    null
                }
            }
        }
        val vm = loadedViewModel(resolver = resolver)
        vm.setSelectedAudio("eng")
        // Two numbers, BOTH requested as eng: episode 1's play page really
        // offers eng (it resolves), episode 2's rejects it — so the run fails
        // exactly once for exactly one typed reason.
        vm.setBatchMode(true)
        vm.toggleBatchEpisode(phantomEpisode(2f, "eng"))

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.batchFailed)
        assertEquals(
            "one notification per run, carrying the typed reason for the run's failure",
            mapOf("Requested audio not available" to 1),
            posted.single().reasonCounts,
        )
        assertEquals(
            "and the in-app list still carries the richer per-episode reason",
            AudioSelector.unavailableMessage("eng"),
            vm.uiState.value.batchFailures.single().reason,
        )
    }

    @Test
    fun `the notify_failure preference suppresses the batch notification`() = runTest {
        val resolver = RecordingResolver().apply {
            failFor = { playUrl -> if (playUrl.contains("ep2")) IllegalStateException("gone") else null }
        }
        val vm = loadedViewModel(resolver = resolver, notifyFailure = false)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals("the run still reports its result in the app", 1, vm.uiState.value.batchFailed)
        assertTrue("but nothing is posted", posted.isEmpty())
    }

    @Test
    fun `a batch that posts nothing when nothing failed`() = runTest {
        val vm = loadedViewModel()
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(2, vm.uiState.value.batchQueued)
        assertTrue(vm.uiState.value.batchFailures.isEmpty())
        assertTrue("a clean run announces nothing", posted.isEmpty())
    }

    @Test
    fun `an early failure that recovers on a later pass is neither listed nor announced`() = runTest {
        val resolver = RecordingResolver()
        // Fails episode 2 EXACTLY ONCE — pass 1 — then heals. A counter rather
        // than a clock, so the assertion cannot depend on scheduler timing.
        var remainingFailures = 1
        resolver.failFor = { playUrl ->
            if (remainingFailures > 0 && playUrl.contains("ep2")) {
                remainingFailures--
                IllegalStateException("transient")
            } else {
                null
            }
        }
        val vm = loadedViewModel(
            resolver = resolver,
            // auto_retry = 1 → two passes, so the second one can recover.
            config = AppContext.defaults().copy(autoRetry = 1),
        )
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals("the second pass repaired the episode", 2, vm.uiState.value.batchQueued)
        assertEquals(0, vm.uiState.value.batchFailed)
        assertTrue(
            "a failure the run repaired is not reported as a failure",
            vm.uiState.value.batchFailures.isEmpty(),
        )
        assertTrue("…and never announced", posted.isEmpty())
    }

    @Test
    fun `a capacity abort posts nothing because those numbers were never attempted`() = runTest {
        val queue = RecordingQueue().apply { stagedAhead = 50 }
        val vm = loadedViewModel(
            queue = queue,
            config = AppContext.defaults().copy(resolveAhead = 0, maxParallel = 1, autoRetry = 0),
        )
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals("nothing was resolved", 0, queue.submissions.size)
        assertTrue(
            "numbers that were never attempted are never called failures",
            vm.uiState.value.batchFailures.isEmpty(),
        )
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `a fresh batch never inherits the previous run's failures`() = runTest {
        val resolver = RecordingResolver()
        var broken = true
        resolver.failFor = { playUrl ->
            if (broken && playUrl.contains("ep2")) IllegalStateException("gone") else null
        }
        val vm = loadedViewModel(resolver = resolver)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.batchFailures.size)

        broken = false
        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals("the second run queued everything", 2, vm.uiState.value.batchQueued)
        assertTrue(
            "a stale reason never survives into a new run",
            vm.uiState.value.batchFailures.isEmpty(),
        )
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

        /** The verdict the fake hands back, scripted per episode number. */
        var outcomeFor: (Float) -> EnqueueOutcome = { EnqueueOutcome.Accepted }

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            return outcomeFor(submission.episode.number)
        }

        override suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo): Boolean =
            episode.number in handledNumbers

        override suspend fun resolvedAheadCount(): Int = stagedAhead
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
}
