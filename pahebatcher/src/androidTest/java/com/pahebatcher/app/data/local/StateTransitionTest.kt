package com.pahebatcher.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * State transition tests for EpisodeDownloadDao.
 *
 *
 * Tests all valid state transitions and verifies:
 * - QUEUED → RESOLVING
 * - RESOLVING → QUEUED (reconciliation)
 * - QUEUED → DOWNLOADING (after persisted stream info)
 * - DOWNLOADING → ASSEMBLING
 * - ASSEMBLING → COMPLETED
 * - active → FAILED
 * - active/nonterminal → CANCELLED
 * - updateError does NOT increment retryCount
 * - updateStateForResume only transitions when expected state matches
 * - beginRetry (11.5E-7) claims a terminal row exactly once, atomically
 * - updateErrorIfState (11.5E-7) refuses to clobber a newer state
 * - run-ownership guards (pause/resume race fix): the claim stamps
 *   `downloadRunId`, stale-run writes lose atomically, current-run and
 *   user/recovery writes are unaffected
 */
@RunWith(AndroidJUnit4::class)
class StateTransitionTest {

    private lateinit var database: PaheDatabase
    private lateinit var dao: EpisodeDownloadDao
    private lateinit var sessionDao: DownloadSessionDao

    private val testSession = DownloadSessionEntity(
        sessionId = "sess-test-1",
        title = "Test Session",
        animeSession = "anime-123",
        host = "animepahe.com",
        quality = 2,
        audioLang = "sub",
        outputDir = "/downloads",
        cacheDir = "/cache",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        state = "QUEUED"
    )

    private val testEpisode = EpisodeDownloadEntity(
        episodeId = "ep-test-1",
        sessionId = "sess-test-1",
        episodeNumber = 1.0f,
        episodeSession = "ep-sess-1",
        title = "Test Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.episodeDownloadDao()
        sessionDao = database.downloadSessionDao()
        // Insert parent session for FK constraint
        runBlocking { sessionDao.insertIfAbsent(testSession) }
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun queued_to_resolving() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_QUEUED))
        dao.updateState("ep-test-1", EpisodeDownloadEntity.STATE_RESOLVING)
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_RESOLVING, episode?.state)
    }

    @Test
    fun resolving_to_queued_on_reconciliation() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_RESOLVING))
        dao.updateState("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED)
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, episode?.state)
    }

    @Test
    fun queued_to_downloading_after_stream_info() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_QUEUED))
        dao.updateState("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING)
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_DOWNLOADING, episode?.state)
    }

    @Test
    fun downloading_to_assembling() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING))
        dao.updateState("ep-test-1", EpisodeDownloadEntity.STATE_ASSEMBLING)
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_ASSEMBLING, episode?.state)
    }

    @Test
    fun assembling_to_completed() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_ASSEMBLING))
        dao.updateCompleted("ep-test-1", "/downloads/ep-test-1/output.mp4")
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, episode?.state)
        assertEquals("/downloads/ep-test-1/output.mp4", episode?.outputPath)
    }

    @Test
    fun active_to_failed() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING))
        dao.updateError("ep-test-1", "Download failed")
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_FAILED, episode?.state)
        assertEquals("Download failed", episode?.errorMessage)
    }

    @Test
    fun active_to_cancelled() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING))
        dao.updateState("ep-test-1", EpisodeDownloadEntity.STATE_CANCELLED)
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, episode?.state)
    }

    @Test
    fun updateError_does_not_increment_retryCount() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING, retryCount = 3))
        dao.updateError("ep-test-1", "Error occurred")
        val episode = dao.getById("ep-test-1")
        assertEquals(3, episode?.retryCount) // Should NOT change
        assertEquals(EpisodeDownloadEntity.STATE_FAILED, episode?.state)
    }

    @Test
    fun updateStateForResume_only_when_expected_matches() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING))

        // Should succeed — current state is DOWNLOADING. 11.5E-7: the returned
        // row count IS the retry claim contract (1 = claimed, 0 = lost).
        val claimed = dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_QUEUED)
        assertEquals(1, claimed)
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, episode?.state)
    }

    @Test
    fun updateStateForResume_does_not_transition_when_expected_mismatches() = runBlocking {
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_COMPLETED))

        // Should NOT transition — current state is COMPLETED, not DOWNLOADING.
        // 11.5E-7: a losing claim must report 0 so reEnqueue() can back off.
        val claimed = dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_QUEUED)
        assertEquals(0, claimed)
        val episode = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, episode?.state)
    }

    @Test
    fun beginRetry_claims_a_terminal_row_exactly_once() = runBlocking {
        // 11.5E-7: the atomic retry claim + claim-time cleanup in ONE UPDATE.
        dao.upsert(
            testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_FAILED,
                retryCount = 2,
                m3u8Url = "https://example.com/stale.m3u8",
                userAgent = "StaleUA",
                referer = "https://stale.example.com",
                errorMessage = "old reason",
                outputUri = "content://media/external/video/media/1",
                segmentDir = "/seg/dir",
            ),
        )

        val claimed = dao.beginRetry("ep-test-1", EpisodeDownloadEntity.STATE_FAILED)
        assertEquals(1, claimed)
        val row = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_RESOLVING, row?.state)
        assertEquals(3, row?.retryCount)
        // Stale resolved credentials, stale reason and stale export URI die
        // with the claim…
        assertNull(row?.m3u8Url)
        assertNull(row?.userAgent)
        assertNull(row?.referer)
        assertNull(row?.errorMessage)
        assertNull(row?.outputUri)
        // …while the retry inputs survive untouched.
        assertEquals("https://example.com/play", row?.playUrl)
        assertEquals("/seg/dir", row?.segmentDir)

        // A second claim from the ORIGINAL terminal state must lose: no second
        // increment, no state change.
        val lost = dao.beginRetry("ep-test-1", EpisodeDownloadEntity.STATE_FAILED)
        assertEquals(0, lost)
        val after = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_RESOLVING, after?.state)
        assertEquals(3, after?.retryCount)
    }

    @Test
    fun updateErrorIfState_writes_only_when_expected_matches() = runBlocking {
        // 11.5E-7: a resolution failure may only write while the row is still
        // RESOLVING — a Cancel (or any newer state) that landed first wins.
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_RESOLVING))

        val written = dao.updateErrorIfState(
            "ep-test-1",
            EpisodeDownloadEntity.STATE_RESOLVING,
            "resolve blew up",
        )
        assertEquals(1, written)
        val failed = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_FAILED, failed?.state)
        assertEquals("resolve blew up", failed?.errorMessage)

        // Late failure racing a user Cancel: the guarded write must refuse.
        dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_CANCELLED))
        val refused = dao.updateErrorIfState(
            "ep-test-1",
            EpisodeDownloadEntity.STATE_RESOLVING,
            "late failure",
        )
        assertEquals(0, refused)
        val cancelled = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, cancelled?.state)
        assertNull(cancelled?.errorMessage)
    }

    @Test
    fun canResume_returns_true_for_failed_and_queued() {
        assertTrue(EpisodeDownloadEntity.STATE_FAILED in listOf(EpisodeDownloadEntity.STATE_FAILED, EpisodeDownloadEntity.STATE_QUEUED))
        assertTrue(EpisodeDownloadEntity.STATE_QUEUED in listOf(EpisodeDownloadEntity.STATE_FAILED, EpisodeDownloadEntity.STATE_QUEUED))
        assertFalse(EpisodeDownloadEntity.STATE_COMPLETED in listOf(EpisodeDownloadEntity.STATE_FAILED, EpisodeDownloadEntity.STATE_QUEUED))
        assertFalse(EpisodeDownloadEntity.STATE_DOWNLOADING in listOf(EpisodeDownloadEntity.STATE_FAILED, EpisodeDownloadEntity.STATE_QUEUED))
        assertFalse(EpisodeDownloadEntity.STATE_CANCELLED in listOf(EpisodeDownloadEntity.STATE_FAILED, EpisodeDownloadEntity.STATE_QUEUED))
    }

    @Test
    fun countActive_counts_only_downloading_and_assembling() = runBlocking {
        dao.upsert(testEpisode.copy(episodeId = "ep-1", state = EpisodeDownloadEntity.STATE_DOWNLOADING))
        dao.upsert(testEpisode.copy(episodeId = "ep-2", state = EpisodeDownloadEntity.STATE_ASSEMBLING))
        dao.upsert(testEpisode.copy(episodeId = "ep-3", state = EpisodeDownloadEntity.STATE_QUEUED))
        dao.upsert(testEpisode.copy(episodeId = "ep-4", state = EpisodeDownloadEntity.STATE_COMPLETED))
        dao.upsert(testEpisode.copy(episodeId = "ep-5", state = EpisodeDownloadEntity.STATE_FAILED))

        val activeCount = dao.countActive()
        assertEquals(2, activeCount) // Only DOWNLOADING + ASSEMBLING
    }

    @Test
    fun clearStreamInfo_nullifies_m3u8_userAgent_referer() = runBlocking {
        dao.upsert(testEpisode.copy(
            state = EpisodeDownloadEntity.STATE_RESOLVING,
            m3u8Url = "https://example.com/playlist.m3u8",
            userAgent = "Mozilla/5.0",
            referer = "https://example.com"
        ))
        dao.clearStreamInfo("ep-test-1")
        val episode = dao.getById("ep-test-1")
        assertNull(episode?.m3u8Url)
        assertNull(episode?.userAgent)
        assertNull(episode?.referer)
    }

    @Test
    fun getNextQueuedWithM3u8_returns_correct_episode() = runBlocking {
        // Episode without m3u8Url — should NOT be returned
        dao.upsert(testEpisode.copy(episodeId = "ep-1", state = EpisodeDownloadEntity.STATE_QUEUED, m3u8Url = null))

        // Episode with m3u8Url — should be returned
        dao.upsert(testEpisode.copy(episodeId = "ep-2", state = EpisodeDownloadEntity.STATE_QUEUED, m3u8Url = "https://example.com/playlist.m3u8"))

        val next = dao.getNextQueuedWithM3u8()
        assertNotNull(next)
        assertEquals("ep-2", next?.episodeId)
    }

    @Test
    fun getTotalEpisodeCount_returns_correct_count() = runBlocking {
        assertEquals(0, dao.getTotalEpisodeCount())

        dao.upsert(testEpisode.copy(episodeId = "ep-1"))
        assertEquals(1, dao.getTotalEpisodeCount())

        dao.upsert(testEpisode.copy(episodeId = "ep-2"))
        assertEquals(2, dao.getTotalEpisodeCount())
    }

    @Test
    fun getEpisodesForReconciliation_includes_completed() = runBlocking {
        dao.upsert(testEpisode.copy(episodeId = "ep-1", state = EpisodeDownloadEntity.STATE_COMPLETED))
        dao.upsert(testEpisode.copy(episodeId = "ep-2", state = EpisodeDownloadEntity.STATE_FAILED))
        dao.upsert(testEpisode.copy(episodeId = "ep-3", state = EpisodeDownloadEntity.STATE_CANCELLED))
        dao.upsert(testEpisode.copy(episodeId = "ep-4", state = EpisodeDownloadEntity.STATE_QUEUED))

        val episodes = dao.getEpisodesForReconciliation()
        // Should include COMPLETED and QUEUED, but NOT FAILED or CANCELLED
        assertEquals(2, episodes.size)
        assertTrue(episodes.any { it.episodeId == "ep-1" }) // COMPLETED
        assertTrue(episodes.any { it.episodeId == "ep-4" }) // QUEUED
        assertFalse(episodes.any { it.episodeId == "ep-2" }) // FAILED excluded
        assertFalse(episodes.any { it.episodeId == "ep-3" }) // CANCELLED excluded
    }

    // -----------------------------------------------------------------------
    // Run-ownership guards (pause/resume run-ownership race fix, v4→v5).
    //
    // These are the deterministic, sleep-free DAO-level proofs of the exact
    // interleaving the 2026-10-22 device failures reproduced: a stale run's
    // cancellation cleanup landing AFTER a pause + resume let the NEW run
    // re-claim the row, and the old run's state-only CAS then stole it.
    // -----------------------------------------------------------------------

    private fun queuedRow() = testEpisode.copy(state = EpisodeDownloadEntity.STATE_QUEUED)

    @Test
    fun claimEntryRunWithRunId_stampsStateAndTokenAtomically() = runBlocking {
        dao.upsert(queuedRow())

        val claimed = dao.claimEntryRunWithRunId(
            "ep-test-1",
            EpisodeDownloadEntity.STATE_QUEUED,
            runId = "run-A",
        )
        assertEquals(1, claimed)
        val row = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_DOWNLOADING, row?.state)
        assertEquals("the claim must stamp the run token in the SAME statement", "run-A", row?.downloadRunId)

        // A losing claim (state moved underneath) writes NOTHING — not even a
        // token.
        dao.updateState("ep-test-1", EpisodeDownloadEntity.STATE_CANCELLED)
        val lost = dao.claimEntryRunWithRunId(
            "ep-test-1",
            EpisodeDownloadEntity.STATE_QUEUED,
            runId = "run-B",
        )
        assertEquals(0, lost)
        assertEquals("run-A", dao.getById("ep-test-1")?.downloadRunId)
    }

    @Test
    fun stale_run_cancellation_cannot_change_a_newer_runs_downloading() = runBlocking {
        // THE race, deterministic: Run A claims, the user pauses, the user
        // resumes, Run B re-claims — then Run A's drained cancellation cleanup
        // tries to persist CANCELLED. It must lose; B's row must be untouched.
        dao.upsert(queuedRow())
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-A"))

        // repository.pause() — the user-intent write (state-only CAS).
        assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_PAUSED))
        // repository.resume() — state-only CAS back to QUEUED.
        assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_PAUSED, EpisodeDownloadEntity.STATE_QUEUED))
        // Run B's entry claim re-stamps ownership.
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-B"))

        // Run A's stale cancellation write: state matches (DOWNLOADING) but the
        // ownership token no longer does — the ONE atomic statement must refuse.
        val stale = dao.updateStateIfOwned(
            "ep-test-1",
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            EpisodeDownloadEntity.STATE_CANCELLED,
            runId = "run-A",
        )
        assertEquals("the stale run's write must be refused", 0, stale)

        val row = dao.getById("ep-test-1")
        assertEquals("Run B's DOWNLOADING must survive", EpisodeDownloadEntity.STATE_DOWNLOADING, row?.state)
        assertEquals("Run B must still own the row", "run-B", row?.downloadRunId)
    }

    @Test
    fun current_runs_cancellation_still_persists_cancelled() = runBlocking {
        dao.upsert(queuedRow())
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-A"))

        // The SAME write the stale run was refused above, issued by the run
        // that still owns the row, must land — cancellation semantics intact.
        val landed = dao.updateStateIfOwned(
            "ep-test-1",
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            EpisodeDownloadEntity.STATE_CANCELLED,
            runId = "run-A",
        )
        assertEquals(1, landed)
        assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, dao.getById("ep-test-1")?.state)
    }

    @Test
    fun user_cancel_still_persists_cancelled_on_an_owned_row() = runBlocking {
        // The user is a NEWER actor than any run: cancelIfNotTerminal (the
        // repository's Cancel path) must keep working unchanged on a row that
        // carries a run token, and must not be blocked by it.
        dao.upsert(queuedRow())
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-A"))

        val cancelled = dao.cancelIfNotTerminal("ep-test-1")
        assertEquals(1, cancelled)
        assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, dao.getById("ep-test-1")?.state)

        // And the now-stale run's own write still loses (state guard), so a
        // user's CANCELLED can never be resurrected or overwritten by the run.
        val stale = dao.updateStateIfOwned(
            "ep-test-1",
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            EpisodeDownloadEntity.STATE_CANCELLED,
            runId = "run-A",
        )
        assertEquals(0, stale)
    }

    @Test
    fun stale_run_error_completion_park_and_assembly_writes_are_refused() = runBlocking {
        // After Run B re-claims, EVERY run-owned write from Run A must lose —
        // not just the cancellation: the failure write, the completion (and
        // therefore its export), the auto-resume park and the remuxing claim.
        dao.upsert(queuedRow())
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-A"))
        assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_PAUSED))
        assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_PAUSED, EpisodeDownloadEntity.STATE_QUEUED))
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-B"))

        assertEquals(0, dao.updateErrorIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, "stale boom", runId = "run-A"))
        assertEquals(0, dao.updateCompletedIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, "/stale/out.mp4", runId = "run-A"))
        assertEquals(0, dao.updateStateIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_QUEUED, runId = "run-A"))
        assertEquals(0, dao.updateStateIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_ASSEMBLING, runId = "run-A"))

        val row = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_DOWNLOADING, row?.state)
        assertEquals("run-B", row?.downloadRunId)
        assertNull("no stale error may be recorded", row?.errorMessage)
        assertNull("no stale completion may be recorded", row?.outputPath)
    }

    @Test
    fun current_run_completes_through_assembly_with_its_own_token() = runBlocking {
        dao.upsert(queuedRow())
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-B"))

        assertEquals(1, dao.updateStateIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_ASSEMBLING, runId = "run-B"))
        assertEquals(
            1,
            dao.updateCompletedIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_ASSEMBLING, "/downloads/out.mp4", runId = "run-B"),
        )
        val row = dao.getById("ep-test-1")
        assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row?.state)
        assertEquals("/downloads/out.mp4", row?.outputPath)
        assertEquals("the completing run's token must persist", "run-B", row?.downloadRunId)

        // A stale completion arriving after the newer run already finished MUST
        // never rewrite the finished row's output.
        val stale = dao.updateCompletedIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_COMPLETED, "/stale/out.mp4", runId = "run-A")
        assertEquals(0, stale)
        assertEquals("/downloads/out.mp4", dao.getById("ep-test-1")?.outputPath)
    }

    @Test
    fun repeated_pause_resume_never_permits_a_stale_terminal_write() = runBlocking {
        // The 2026-10-22 failure was a single pause + resume. This loop proves
        // the invariant holds for ANY number of cycles: at every cycle the
        // previous run's terminal write loses and the current run's row stays.
        dao.upsert(queuedRow())

        var currentRun = "run-0"
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = currentRun))

        for (cycle in 1..3) {
            val previousRun = currentRun
            currentRun = "run-$cycle"

            assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_PAUSED))
            assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_PAUSED, EpisodeDownloadEntity.STATE_QUEUED))
            assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = currentRun))

            // The run from BEFORE this cycle tries every terminal write.
            assertEquals(0, dao.updateStateIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_CANCELLED, runId = previousRun))
            assertEquals(0, dao.updateErrorIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, "stale", runId = previousRun))
            assertEquals(0, dao.updateCompletedIfOwned("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, "/stale.mp4", runId = previousRun))

            val row = dao.getById("ep-test-1")
            assertEquals("cycle $cycle: the CURRENT run's row must survive", EpisodeDownloadEntity.STATE_DOWNLOADING, row?.state)
            assertEquals("cycle $cycle: the CURRENT run must own the row", currentRun, row?.downloadRunId)
            assertNull(row?.errorMessage)
        }
    }

    @Test
    fun user_and_recovery_writes_keep_working_on_a_token_carrying_row() = runBlocking {
        // The token must never block the actors that are newer than any run:
        // pause, resume, the reconciler's re-enqueue CAS and the retry claim.
        dao.upsert(queuedRow())
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-A"))

        // pause
        assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_PAUSED))
        // resume
        assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_PAUSED, EpisodeDownloadEntity.STATE_QUEUED))
        // a fresh Worker claims again (restart/resume re-claim)
        assertEquals(1, dao.claimEntryRunWithRunId("ep-test-1", EpisodeDownloadEntity.STATE_QUEUED, runId = "run-B"))
        // the reconciler's recovery CAS (DOWNLOADING → QUEUED) is unguarded by design
        assertEquals(1, dao.updateStateForResume("ep-test-1", EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_QUEUED))
        // a terminal row can still be re-queued by the user's Retry
        dao.updateState("ep-test-1", EpisodeDownloadEntity.STATE_FAILED)
        assertEquals(1, dao.beginRetry("ep-test-1", EpisodeDownloadEntity.STATE_FAILED))
        assertEquals(EpisodeDownloadEntity.STATE_RESOLVING, dao.getById("ep-test-1")?.state)
    }
}
