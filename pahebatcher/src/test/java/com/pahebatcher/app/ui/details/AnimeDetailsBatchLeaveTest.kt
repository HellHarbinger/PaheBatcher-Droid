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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leave-a-running-batch contract, state level.
 *
 * The decision specifies six tests (T1–T6); this file implements the
 * ViewModel-level verifiable core of **T1, T4, T5 and T6**:
 *
 * - **T1**: navigating away mid-batch opens the confirmation warning with
 *   submitted-vs-pending counts; confirming cancels the submission loop and
 *   the already-submitted episode keeps its queue submission; staying keeps
 *   the batch running untouched.
 * - **T4**: after a partial submission the counters are honest — the dropped
 *   number appears ONLY as pending-at-warning time and is never reported as
 *   failed or skipped.
 * - **T5**: re-running the same selection after a partial run enqueues
 *   nothing twice (handled-before-resolve, production semantics).
 * - **T6**: the confirmation path releases the loop without a second
 *   submission — no resolver call and no queue submission happens after
 *   confirm, even across the former hang window; repeated cancellation is a
 *   no-op.
 *
 * T2 (activity recreation) and T3 (process death → persistence + resume) are
 * device-level and live in the androidTest counterparts of this contract.
 *
 * No network: the scan runs through [FakeHttpClient]; the resolver and queue
 * are test-only doubles (AGENTS §5). The queue marks numbers handled on
 * submission, mirroring production `isEpisodeAlreadyHandled` reading the
 * persisted row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailsBatchLeaveTest {

    // -----------------------------------------------------------------------
    // Harness (same shape as AnimeDetailsViewModelBatchTest, own fakes)
    // -----------------------------------------------------------------------

    /** Resolution behavior scripted per variant play page. */
    private class ScriptedResolver : StreamResolver {
        val calls = mutableListOf<String>()
        /** While true, the ep2 resolution never returns on its own. */
        var hangEp2: Boolean = true
        /** When set, ep2 resolves only after this virtual-time delay. */
        var slowEp2Ms: Long = 0L
        var failEp2: Boolean = false

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            calls += playUrl
            if (playUrl.contains("ep2")) {
                if (failEp2) throw IllegalStateException("kwik link expired")
                if (hangEp2) delay(10L * 60L * 1000L) // never returns on its own
                else if (slowEp2Ms > 0) delay(slowEp2Ms)
            }
            return StreamInfo(
                url = "https://cdn.example.invalid/${calls.size}.m3u8",
                userAgent = "Mozilla/5.0 (test)",
                referer = playUrl,
            )
        }
    }

    /**
     * Submission target that PERSISTS handledness like the production queue
     * does (the row's existence is what `isEpisodeAlreadyHandled` reads) —
     * T5's "nothing twice" runs against this.
     */
    private class RowPersistingQueue : EpisodeDownloadQueue {
        val submissions = mutableListOf<ResolvedEpisodeSubmission>()
        val handledNumbers: MutableSet<Float> = mutableSetOf()

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            handledNumbers += submission.episode.number
            return EnqueueOutcome.Accepted
        }

        override suspend fun isEpisodeAlreadyHandled(
            animeSession: String,
            episode: EpisodeInfo,
        ): Boolean = episode.number in handledNumbers

        override suspend fun resolvedAheadCount(): Int = 0
    }

    private fun dualAudioPage(): String = """
        {"data": [
            {"episode": 1, "session": "ep1-jpn", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 1, "session": "ep1-eng", "title": "Beginnings", "fansub": "DubsPlease", "audio": "eng"},
            {"episode": 2, "session": "ep2-jpn", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Test Anime"}}
    """.trimIndent()

    private fun liveClient(): FakeHttpClient =
        FakeHttpClient().apply { stubJson("m=release", dualAudioPage()) }

    private fun newViewModel(
        client: HttpClient,
        resolver: StreamResolver,
        queue: RowPersistingQueue,
        config: AppContext = AppContext.defaults(),
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
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.loadedViewModel(
        resolver: StreamResolver,
        queue: RowPersistingQueue,
    ): AnimeDetailsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = newViewModel(liveClient(), resolver, queue)
        vm.load("sess-batch")
        testScheduler.advanceUntilIdle()
        assertEquals(3, vm.uiState.value.episodes.size)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()
        return vm
    }

    /**
     * Start a batch and advance only INTO the second (hanging) resolution:
     * episode 1 is fully submitted, episode 2 is in flight — the canonical
     * "partial submission" moment.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.partialRun(
        vm: AnimeDetailsViewModel,
    ) {
        vm.startBatchDownload()
        testScheduler.advanceTimeBy(1_000L)
        val state = vm.uiState.value
        assertTrue("the batch is running", state.batchRunning)
        assertEquals("one number submitted", 1, state.batchQueued)
        assertEquals("the other number in flight", 2, state.batchTotal)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // T1 — warning, counts, confirm, stay
    // -----------------------------------------------------------------------

    @Test
    fun `T1 navigating away mid-batch opens the warning with submitted and pending counts`() =
        runTest {
            val resolver = ScriptedResolver()
            val queue = RowPersistingQueue()
            val vm = loadedViewModel(resolver, queue)
            partialRun(vm)

            vm.requestLeaveBatchWarning()

            val state = vm.uiState.value
            assertTrue("the warning is open", state.leaveBatchWarning)
            assertTrue("the batch is STILL running while the warning shows", state.batchRunning)
            assertEquals("submitted = handed to the pipeline", 1, state.leaveBatchSubmitted)
            assertEquals("pending = never concluded = what leaving drops", 1, state.leaveBatchPending)
        }

    @Test
    fun `T1 confirming leaves with the submitted row kept and the loop cancelled`() = runTest {
        val resolver = ScriptedResolver()
        val queue = RowPersistingQueue()
        val vm = loadedViewModel(resolver, queue)
        partialRun(vm)
        vm.requestLeaveBatchWarning()

        vm.confirmLeaveBatch()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("the loop is released", state.batchRunning)
        assertTrue("the stopped state renders", state.batchFinished)
        assertFalse("the warning closes on confirm", state.leaveBatchWarning)
        assertEquals("the submitted episode keeps its submission", 1, queue.submissions.size)
        assertEquals(1f, queue.submissions.single().episode.number)
        assertEquals("the counter still describes the loop's own work", 1, state.batchQueued)
    }

    @Test
    fun `T1 staying closes the warning and the batch keeps running`() = runTest {
        val resolver = ScriptedResolver()
        val queue = RowPersistingQueue()
        val vm = loadedViewModel(resolver, queue)
        partialRun(vm)
        vm.requestLeaveBatchWarning()

        vm.dismissLeaveBatchWarning()

        val state = vm.uiState.value
        assertFalse("the warning is gone", state.leaveBatchWarning)
        assertTrue("the batch was never touched", state.batchRunning)
        assertEquals(1, state.batchQueued)

        // …and leaving later still works (the warning can be reopened).
        vm.requestLeaveBatchWarning()
        assertTrue(vm.uiState.value.leaveBatchWarning)
    }

    @Test
    fun `T1 requesting the warning when no batch runs is a no-op`() = runTest {
        val vm = loadedViewModel(ScriptedResolver(), RowPersistingQueue())
        vm.requestLeaveBatchWarning()

        assertFalse("nothing to confirm without a running batch", vm.uiState.value.leaveBatchWarning)
        assertEquals(0, vm.uiState.value.leaveBatchSubmitted)
        assertEquals(0, vm.uiState.value.leaveBatchPending)
    }

    @Test
    fun `T1 the warning closes itself when the batch finishes on its own`() = runTest {
        val resolver = ScriptedResolver().apply { hangEp2 = false; slowEp2Ms = 5_000L }
        val queue = RowPersistingQueue()
        val vm = loadedViewModel(resolver, queue)
        vm.startBatchDownload()
        testScheduler.advanceTimeBy(1_000L)
        assertEquals(1, vm.uiState.value.batchQueued) // ep1 done, ep2 slow

        vm.requestLeaveBatchWarning()
        assertTrue(vm.uiState.value.leaveBatchWarning)

        testScheduler.advanceUntilIdle() // ep2 completes → publishBatchDone
        val state = vm.uiState.value
        assertFalse("batch over → nothing pending → no confirmation needed", state.leaveBatchWarning)
        assertFalse(state.batchRunning)
        assertEquals(2, state.batchQueued)
    }

    // -----------------------------------------------------------------------
    // T4 — honest counters after a partial submission
    // -----------------------------------------------------------------------

    @Test
    fun `T4 partial submission reports honest counters with no phantom failures`() = runTest {
        val resolver = ScriptedResolver()
        val queue = RowPersistingQueue()
        val vm = loadedViewModel(resolver, queue)
        partialRun(vm)

        // Before leaving: the in-flight number is not failed, not skipped.
        assertEquals(0, vm.uiState.value.batchFailed)
        assertEquals(0, vm.uiState.value.batchSkipped)

        vm.requestLeaveBatchWarning()
        assertEquals("the dropped number is reported ONLY as pending", 1, vm.uiState.value.leaveBatchPending)
        vm.confirmLeaveBatch()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("submitted", 1, state.batchQueued)
        assertEquals("the dropped number is NOT a phantom failure", 0, state.batchFailed)
        assertEquals("…nor a phantom skip", 0, state.batchSkipped)
        assertEquals(2, state.batchTotal)
        assertNull("a leave is not a batch-level error", state.batchError)
    }

    // -----------------------------------------------------------------------
    // T5 — idempotent re-run after the partial run
    // -----------------------------------------------------------------------

    @Test
    fun `T5 re-running after a partial run enqueues nothing twice`() = runTest {
        val resolver = ScriptedResolver()
        val queue = RowPersistingQueue()
        val vm = loadedViewModel(resolver, queue)
        partialRun(vm)
        vm.requestLeaveBatchWarning()
        vm.confirmLeaveBatch()
        testScheduler.advanceUntilIdle()
        assertEquals(1, queue.submissions.size)

        // The recovery path: same selection, fresh run (the hang is over).
        resolver.hangEp2 = false
        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "episode 1 is never queued twice",
            1,
            queue.submissions.count { it.episode.number == 1f },
        )
        assertEquals(
            "episode 2 is queued exactly once — by the re-run",
            1,
            queue.submissions.count { it.episode.number == 2f },
        )
        assertEquals(
            "the handled episode is never resolved again",
            1,
            resolver.calls.count { it.contains("ep1-") },
        )
        val state = vm.uiState.value
        assertEquals(1, state.batchQueued)
        assertEquals(1, state.batchSkipped)
        assertEquals(0, state.batchFailed)
        assertTrue(state.batchFinished)
    }

    // -----------------------------------------------------------------------
    // T6 — confirmation releases the loop without a second submission
    // -----------------------------------------------------------------------

    @Test
    fun `T6 confirm releases the loop with no later submission and repeat cancel is a no-op`() =
        runTest {
            val resolver = ScriptedResolver()
            val queue = RowPersistingQueue()
            val vm = loadedViewModel(resolver, queue)
            partialRun(vm)
            vm.requestLeaveBatchWarning()

            val callsAtConfirm = resolver.calls.size
            val submissionsAtConfirm = queue.submissions.size
            assertEquals(1, submissionsAtConfirm)

            vm.confirmLeaveBatch()
            testScheduler.advanceUntilIdle()
            // Cross the former hang window: a leaked coroutine would resolve
            // and queue right here.
            testScheduler.advanceTimeBy(10L * 60L * 1000L)

            assertEquals("no further resolution", callsAtConfirm, resolver.calls.size)
            assertEquals("no second submission", submissionsAtConfirm, queue.submissions.size)

            // Repeating the cancellation changes nothing (idempotent).
            val before = vm.uiState.value
            vm.cancelBatch()
            assertEquals(before, vm.uiState.value)
        }
}
