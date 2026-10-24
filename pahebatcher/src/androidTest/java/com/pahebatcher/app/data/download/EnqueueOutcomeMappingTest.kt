package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Slice B (DECISIONS 146) — [DownloadRepository.queueResolvedEpisode] reports
 * what it REALLY did.
 *
 * Before this slice the seam returned `Unit` and every caller wrote
 * "In the download queue" on its behalf, so a request the pipeline refused
 * (already downloading, paused, already downloaded) was reported as a fresh
 * enqueue. This class pins the mapping from persisted row state to verdict —
 * the contract [EnqueueOutcome]'s KDoc states — plus the two write-safety
 * properties that make the verdicts mean anything:
 *
 *  * a duplicate submission must not clobber the winner's row, and
 *  * concurrent FIRST submissions may create the row exactly once.
 *
 * Runs against the REAL Room singleton and the REAL application WorkManager,
 * because a Worker's unique-work chain is part of what "accepted" means here.
 */
@RunWith(AndroidJUnit4::class)
class EnqueueOutcomeMappingTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-outcome-test"

    /** Run-unique suffix so this class's cache files never collide across runs. */
    private val runStamp = System.currentTimeMillis()

    /**
     * The single test here that seeds its OWN parent session (instead of the
     * class-wide `sessionId` that [setup] creates) records it here so
     * [teardown] can remove it — its episode child cascades away with it.
     */
    private var cascadeSessionId: String? = null

    /** `episodeDownloadId()` = `${animeSession}_${epStr}_${audio}` (whole numbers). */
    private fun episodeId(number: Int) = "${sessionId}_${number}_${EpisodeInfo.AUDIO_JAPANESE}"

    private fun baseEpisode(number: Int, state: String?) = EpisodeDownloadEntity(
        episodeId = episodeId(number),
        sessionId = sessionId,
        episodeNumber = number.toFloat(),
        episodeSession = "eps-outcome-$number",
        title = "Outcome Episode $number",
        fansub = "TestFansub",
        audio = EpisodeInfo.AUDIO_JAPANESE,
        playUrl = "https://animepahe.com/play/$sessionId/eps-outcome-$number",
        state = state ?: EpisodeDownloadEntity.STATE_QUEUED,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    /** The episode identity [submission] uses, so tests can ask the same question of the queue. */
    private fun episodeInfo(number: Int) = EpisodeInfo(
        number = number.toFloat(),
        session = "eps-outcome-$number",
        title = "",
        fansub = "",
        audio = EpisodeInfo.AUDIO_JAPANESE,
        playUrl = "https://animepahe.com/play/$sessionId/eps-outcome-$number",
    )

    private fun submission(number: Int) = ResolvedEpisodeSubmission(
        animeSession = sessionId,
        animeTitle = "Outcome Anime",
        host = "animepahe.com",
        quality = 1080,
        episode = episodeInfo(number),
        stream = StreamInfo(
            url = "https://cdn.example.com/fresh-$number.m3u8",
            userAgent = "FreshUA",
            referer = "https://kwik.cx/e/abc",
            title = "Outcome Anime Ep. $number :: animepahe",
        ),
    )

    private fun workerCount(number: Int) =
        workManager.getWorkInfosForUniqueWork("episode_download_${episodeId(number)}").get().size

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        (1..64).forEach { workManager.cancelUniqueWork("episode_download_${episodeId(it)}") }
        cascadeSessionId?.let { Phase10TestSupport.deleteSession(db, it) }
        cascadeSessionId = null
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    // -----------------------------------------------------------------------
    // The outcome table
    // -----------------------------------------------------------------------

    @Test
    fun the_reported_outcome_matches_the_persisted_row_state() = runBlocking {
        val dao = db.episodeDownloadDao()

        // A. No row at all → the submission really starts a download.
        assertEquals(EnqueueOutcome.Accepted, repository.queueResolvedEpisode(submission(1)))
        val created = dao.getById(episodeId(1))
        assertEquals(
            "the new row ends QUEUED with its Worker registered",
            EpisodeDownloadEntity.STATE_QUEUED,
            created?.state,
        )
        assertEquals("https://cdn.example.com/fresh-1.m3u8", created?.m3u8Url)

        // B. A LIVE Worker owns the row → never re-queued, never reported fresh.
        dao.upsert(baseEpisode(2, EpisodeDownloadEntity.STATE_DOWNLOADING))
        assertEquals(EnqueueOutcome.AlreadyActive, repository.queueResolvedEpisode(submission(2)))
        assertEquals(
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            dao.getById(episodeId(2))?.state,
        )

        // C. Assembly is still live work too.
        dao.upsert(baseEpisode(3, EpisodeDownloadEntity.STATE_ASSEMBLING))
        assertEquals(EnqueueOutcome.AlreadyActive, repository.queueResolvedEpisode(submission(3)))

        // D. The user paused it — only resume() may start it again.
        dao.upsert(baseEpisode(4, EpisodeDownloadEntity.STATE_PAUSED))
        assertEquals(EnqueueOutcome.AlreadyPaused, repository.queueResolvedEpisode(submission(4)))
        assertEquals(
            "a pause is the user's intent and must survive a re-submission",
            EpisodeDownloadEntity.STATE_PAUSED,
            dao.getById(episodeId(4))?.state,
        )

        // E. A COMPLETED row whose output is really there → nothing to download.
        val present = File(context.cacheDir, "outcome-present-$runStamp.mp4")
        present.writeBytes("already-assembled-mp4".toByteArray())
        try {
            dao.upsert(
                baseEpisode(5, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                    outputPath = present.absolutePath,
                    outputUri = null,
                ),
            )
            assertEquals(EnqueueOutcome.AlreadyDownloaded, repository.queueResolvedEpisode(submission(5)))
            assertEquals(
                EpisodeDownloadEntity.STATE_COMPLETED,
                dao.getById(episodeId(5))?.state,
            )
        } finally {
            present.delete()
        }

        // F. A COMPLETED row whose output is gone → the honest re-download.
        val gone = File(context.cacheDir, "outcome-gone-$runStamp.mp4")
        gone.delete()
        dao.upsert(
            baseEpisode(6, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                outputPath = gone.absolutePath,
                outputUri = null,
            ),
        )
        assertEquals(EnqueueOutcome.OutputMissing, repository.queueResolvedEpisode(submission(6)))
        assertEquals(
            EpisodeDownloadEntity.STATE_QUEUED,
            dao.getById(episodeId(6))?.state,
        )

        // G. Terminal rows are legitimately re-queued.
        dao.upsert(baseEpisode(7, EpisodeDownloadEntity.STATE_FAILED))
        assertEquals(EnqueueOutcome.Accepted, repository.queueResolvedEpisode(submission(7)))
        dao.upsert(baseEpisode(8, EpisodeDownloadEntity.STATE_CANCELLED))
        assertEquals(EnqueueOutcome.Accepted, repository.queueResolvedEpisode(submission(8)))
    }

    @Test
    fun a_queued_row_without_a_stream_is_accepted_because_this_call_makes_it_download() = runBlocking {
        val dao = db.episodeDownloadDao()
        // The zombie of LIMITATIONS §24 finding 2: QUEUED, but no stream info, so
        // nothing can ever run it. The details-screen re-submission is its
        // escape route — reporting "already in the queue" here would tell the
        // user work is happening while the row stays permanently stuck.
        dao.upsert(
            baseEpisode(20, EpisodeDownloadEntity.STATE_QUEUED).copy(
                m3u8Url = null,
                userAgent = null,
                referer = null,
            ),
        )

        val outcome = repository.queueResolvedEpisode(submission(20))

        assertEquals(EnqueueOutcome.Accepted, outcome)
        val row = dao.getById(episodeId(20))
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
        assertEquals("https://cdn.example.com/fresh-20.m3u8", row?.m3u8Url)
        assertEquals("FreshUA", row?.userAgent)
    }

    @Test
    fun a_queued_row_that_already_has_a_stream_reports_already_active() = runBlocking {
        val dao = db.episodeDownloadDao()
        dao.upsert(
            baseEpisode(21, EpisodeDownloadEntity.STATE_QUEUED).copy(
                m3u8Url = "https://cdn.example.com/already-staged.m3u8",
                userAgent = "StagedUA",
                referer = "https://kwik.cx/e/staged",
            ),
        )

        assertEquals(EnqueueOutcome.AlreadyActive, repository.queueResolvedEpisode(submission(21)))
    }

    // -----------------------------------------------------------------------
    // The write-safety properties that make those verdicts true
    // -----------------------------------------------------------------------

    @Test
    fun insertIfAbsent_creates_once_and_never_overwrites_the_winner() = runBlocking {
        val dao = db.episodeDownloadDao()
        val first = baseEpisode(30, EpisodeDownloadEntity.STATE_QUEUED).copy(title = "the winner")

        assertTrue("the first claim must create the row", dao.insertIfAbsent(first) > 0)

        val rival = first.copy(
            title = "a rival submission",
            state = EpisodeDownloadEntity.STATE_DOWNLOADING,
            retryCount = 9,
        )
        assertEquals(
            "the losing claim must be told it lost",
            -1L,
            dao.insertIfAbsent(rival),
        )

        val row = dao.getById(episodeId(30))
        assertEquals("the winner's row survives intact", "the winner", row?.title)
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
        assertEquals("…and its retry budget is untouched", 0, row?.retryCount)
    }

    @Test
    fun insertIfAbsent_on_a_session_must_not_cascade_away_an_existing_episode_row() = runBlocking {
        // The reason ensureSession stopped using `upsert`: that is
        // INSERT OR REPLACE, and REPLACE deletes the conflicting parent before
        // re-inserting it — which fires ON DELETE CASCADE on episode_downloads.
        // Queueing a second episode of an already-downloading anime would
        // therefore have destroyed the row of the episode already in flight.
        //
        // Fixture note: this test seeds its OWN parent session rather than the
        // class-wide `sessionId` that `setup()` already created. Using the
        // shared id would make the first `insertIfAbsent` below itself a
        // duplicate, so it would lose to the `@Before` row and the assertions
        // would pin the seed's title instead of the winner's.
        val sessionDao = db.downloadSessionDao()
        val dao = db.episodeDownloadDao()
        val ownSession = "sess-outcome-cascade-$runStamp"
        cascadeSessionId = ownSession
        val first = DownloadSessionEntity(
            sessionId = ownSession,
            title = "the original session",
            animeSession = "anime-outcome",
            host = "animepahe.com",
            quality = 1080,
            audioLang = EpisodeInfo.AUDIO_JAPANESE,
            outputDir = "${context.filesDir}/downloads",
            cacheDir = "${context.cacheDir}",
            createdAt = 1L,
            updatedAt = 1L,
            state = DownloadSessionEntity.STATE_CREATED,
        )
        sessionDao.insertIfAbsent(first)
        // The in-flight episode's FK MUST point at `ownSession`, otherwise it
        // is not a child of the session under test and could never be cascaded
        // away — which would make the survival assertion below vacuous.
        val ownEpisodeId = "${ownSession}_31_${EpisodeInfo.AUDIO_JAPANESE}"
        dao.upsert(
            baseEpisode(31, EpisodeDownloadEntity.STATE_DOWNLOADING).copy(
                sessionId = ownSession,
                episodeId = ownEpisodeId,
            ),
        )

        val ignored = sessionDao.insertIfAbsent(first.copy(title = "a refreshed title", updatedAt = 2L))

        assertEquals(-1L, ignored)
        assertNotNull(
            "the live episode row must survive a duplicate session submission",
            dao.getById(ownEpisodeId),
        )
        assertEquals(
            "and the parent row keeps the winner's own values",
            "the original session",
            sessionDao.getById(ownSession)?.title,
        )
    }

    @Test
    fun concurrent_first_submissions_create_exactly_one_row_and_one_verdict_of_fresh_work() = runBlocking {
        val number = 40
        val outcomes = (1..6).map {
            async { repository.queueResolvedEpisode(submission(number)) }
        }.awaitAll()

        assertEquals(
            "exactly ONE submission may claim a fresh enqueue — the rest must not " +
                "double-report work they did not start",
            1,
            outcomes.count { it == EnqueueOutcome.Accepted },
        )
        assertTrue(
            "the losing submissions must report an already-covered episode, never a fresh enqueue",
            outcomes.all { it == EnqueueOutcome.Accepted || it == EnqueueOutcome.AlreadyActive },
        )
        assertTrue(
            "the winner's Worker is the only one — unique work is never duplicated",
            workerCount(number) <= 1,
        )
    }

    // -----------------------------------------------------------------------
    // Review finding H-2: a hand-off the CAS refused
    // -----------------------------------------------------------------------

    @Test
    fun a_hand_off_refused_by_the_cas_never_reports_a_fresh_enqueue() = runBlocking {
        val number = 50
        val dao = db.episodeDownloadDao()
        val repositoryWithCancelInWindow = DownloadRepository(
            context,
            ClaimThenCancelDao(dao, episodeId(number)),
            db.downloadSessionDao(),
        )

        val outcome = repositoryWithCancelInWindow.queueResolvedEpisode(submission(number))

        assertEquals(
            "a Cancel that beat the hand-off must be reported as the refusal it was: " +
                "not a fresh enqueue, and not an episode the pipeline owns and will " +
                "deliver on its own (review finding F1)",
            EnqueueOutcome.Refused,
            outcome,
        )
        assertEquals(
            "the Cancel's intent stays durable — nothing flips it back",
            EpisodeDownloadEntity.STATE_CANCELLED,
            dao.getById(episodeId(number))?.state,
        )
        assertEquals(
            "and no Worker may be appended after a Cancel",
            0,
            workerCount(number),
        )
    }

    // -----------------------------------------------------------------------
    // Slice B closure: F2/F7 — the terminal-row claim
    // -----------------------------------------------------------------------

    /**
     * F2: exactly ONE of any number of concurrent submissions over a
     * PRE-EXISTING terminal row may report a fresh enqueue.
     *
     * The suite already pinned this only for a row that does not exist yet
     * (`concurrent_first_submissions_...`, whose losers necessarily observe
     * `RESOLVING` or a `QUEUED`-with-stream row). This is the case that was
     * never covered: the row is ALREADY `FAILED` when every racer starts, so
     * each one reads `FAILED`, and before this slice each blind-wrote it and
     * then won a hand-off CAS of its own — every racer reported `Accepted` for
     * a single download. The guarded claim makes it a real compare-and-set.
     */
    @Test
    fun concurrent_submissions_over_a_pre_existing_terminal_row_claim_it_exactly_once() = runBlocking {
        val number = 60
        val dao = db.episodeDownloadDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            baseEpisode(number, EpisodeDownloadEntity.STATE_FAILED).copy(
                errorMessage = "previous failure",
                createdAt = now,
                updatedAt = now,
            ),
        )

        val outcomes = (1..6).map {
            async { repository.queueResolvedEpisode(submission(number)) }
        }.awaitAll()

        assertEquals(
            "exactly ONE submission may claim the terminal row — the rest must not " +
                "double-report work only the winner performed",
            1,
            outcomes.count { it == EnqueueOutcome.Accepted },
        )
        assertTrue(
            "a loser may only report a state somebody else really owns, never a " +
                "fresh enqueue it did not earn (got $outcomes)",
            outcomes.all {
                it == EnqueueOutcome.Accepted ||
                    it == EnqueueOutcome.AlreadyActive ||
                    it == EnqueueOutcome.Refused
            },
        )
        assertTrue(
            "the unique Work chain still bounds this to one Worker " +
                "(found ${workerCount(number)})",
            workerCount(number) <= 1,
        )
    }

    /**
     * F7a: a Cancel landing after the claim must NEVER be resurrected.
     *
     * Before this slice `enqueue()` blind-wrote `state = QUEUED` and then
     * blind-wrote `state = RESOLVING` as two separate statements. `cancel()`
     * accepts `QUEUED` (`cancelIfNotTerminal`), so a Cancel landing between
     * them was forced straight back to `RESOLVING` by the second write — and
     * `resolveComplete`'s CAS then succeeded, registering a Worker for an
     * episode the user had just cancelled. The claim writes `RESOLVING` once,
     * atomically, so there is no `QUEUED` window left to land in.
     */
    @Test
    fun a_cancel_landing_after_the_terminal_claim_is_never_resurrected() = runBlocking {
        val number = 61
        val dao = db.episodeDownloadDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            baseEpisode(number, EpisodeDownloadEntity.STATE_FAILED).copy(
                createdAt = now,
                updatedAt = now,
            ),
        )
        val repositoryWithCancelAfterClaim = DownloadRepository(
            context,
            CancelAfterClaimDao(dao, episodeId(number)),
            db.downloadSessionDao(),
        )

        val outcome = repositoryWithCancelAfterClaim.queueResolvedEpisode(submission(number))

        assertEquals(
            "the hand-off lost to the Cancel, so this submission queued nothing",
            EnqueueOutcome.Refused,
            outcome,
        )
        assertEquals(
            "the user's Cancel must survive — never forced back to RESOLVING (F7a)",
            EpisodeDownloadEntity.STATE_CANCELLED,
            dao.getById(episodeId(number))?.state,
        )
        assertEquals(
            "and no Worker may be appended after a Cancel",
            0,
            workerCount(number),
        )
    }

    /**
     * F7b: a Retry that claimed the row first must keep its claim.
     *
     * `beginRetry` claims `FAILED → RESOLVING` with `retryCount + 1` and its
     * claim-time cleanup. Before this slice the concurrent submission's blind
     * `upsert` overwrote the whole row with a freshly built entity carrying
     * `retryCount = 0`, silently discarding the Retry's claim and its counter.
     * The submission must now LOSE (it classified the row as `FAILED` and it is
     * no longer `FAILED`) and leave the Retry's state untouched.
     */
    @Test
    fun a_retry_claiming_first_is_never_reset_by_a_concurrent_submission() = runBlocking {
        val number = 62
        val dao = db.episodeDownloadDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            baseEpisode(number, EpisodeDownloadEntity.STATE_FAILED).copy(
                errorMessage = "old failure",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val repositoryWithRetryRacing = DownloadRepository(
            context,
            RetryRacingDao(dao, episodeId(number)),
            db.downloadSessionDao(),
        )

        val outcome = repositoryWithRetryRacing.queueResolvedEpisode(submission(number))
        val row = dao.getById(episodeId(number))

        assertEquals(
            "the submission lost the claim to the concurrent Retry, so it queued nothing",
            EnqueueOutcome.AlreadyActive,
            outcome,
        )
        assertEquals(
            "the Retry's claim survives — the blind upsert used to reset this to 0 (F7b)",
            1,
            row?.retryCount,
        )
        assertEquals(
            "the Retry still owns the row it claimed",
            EpisodeDownloadEntity.STATE_RESOLVING,
            row?.state,
        )
        assertEquals(
            "and the losing submission registered no Worker of its own",
            0,
            workerCount(number),
        )
    }

    /**
     * Post-audio slice — the lost-creation-claim branch when the winner's row
     * is GONE rather than merely different.
     *
     * `insertIfAbsent` loses twice and `getById` finds nothing either side of
     * it, so there provably is no row: no Worker owns this episode, nothing is
     * downloading, and the submission queued nothing. Reporting
     * [EnqueueOutcome.AlreadyActive] here (the pre-fix behaviour) would promise
     * "the pipeline owns it" for an episode the pipeline does not have — the
     * exact blanket answer DECISIONS 146.5 forbids, and the same lie the F1
     * correction removed from the terminal-claim path. The honest verdict is
     * derived from what is really there: no row → [EnqueueOutcome.Refused].
     */
    @Test
    fun a_submission_whose_row_vanished_twice_reports_what_is_actually_there() = runBlocking {
        // Episode 9: no other test in this class touches it, and the tearDown
        // range (1..64) covers it — a number another test already queued would
        // leave a cancelled WorkInfo behind and be counted here.
        val number = 9
        val dao = db.episodeDownloadDao()
        val vanished = DownloadRepository(context, VanishedRowDao(dao), db.downloadSessionDao())

        val outcome = vanished.queueResolvedEpisode(submission(number))

        assertEquals(
            "nothing exists, so nothing is active and nothing was queued",
            EnqueueOutcome.Refused,
            outcome,
        )
        assertNull(
            "the losing claim created no row of its own",
            dao.getById(episodeId(number)),
        )
        assertEquals(
            "and it registered no Worker — which is what makes `AlreadyActive` a lie here",
            0,
            workerCount(number),
        )
    }

    /**
     * Post-audio slice — the lost-creation-claim branch when the winner's row
     * is a COMPLETED row.
     *
     * This is LIMITATIONS R6's exact scenario: the submission loses its insert
     * claim, re-reads and finds a row the pipeline really did finish. The
     * pre-fix `AlreadyActive` reported "the pipeline owns it" — which on the
     * details screen rendered as hand-off success ("In the download queue")
     * while the row stayed COMPLETED and no Worker was ever registered. The
     * verdict is now derived from the row itself, so the completed state is
     * what the user is told.
     */
    @Test
    fun a_lost_creation_claim_against_a_completed_row_reports_that_row_state() = runBlocking {
        val number = 11 // untouched by every other test in this class
        val dao = db.episodeDownloadDao()
        val output = File(context.cacheDir, "outcome-r6-$runStamp.mp4")
        output.writeBytes("already-assembled-mp4".toByteArray())
        dao.upsert(
            baseEpisode(number, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                outputPath = output.absolutePath,
                outputUri = null,
            ),
        )
        val repository = DownloadRepository(
            context,
            LostClaimWithRealRowDao(dao, episodeId(number)),
            db.downloadSessionDao(),
        )

        val outcome = repository.queueResolvedEpisode(submission(number))

        assertEquals(
            "the completed row really is there, so that is what is reported",
            EnqueueOutcome.AlreadyDownloaded,
            outcome,
        )
        assertEquals(
            "and the row it found is untouched by the losing claim",
            EpisodeDownloadEntity.STATE_COMPLETED,
            dao.getById(episodeId(number))?.state,
        )
        assertEquals(
            "no Worker was registered for an episode the pipeline already finished",
            0,
            workerCount(number),
        )
    }

    /**
     * A delegating double that makes the creation claim lose BOTH attempts while
     * the REAL row stays in place — the difference from [VanishedRowDao], whose
     * re-read finds nothing. Every other member forwards to the REAL DAO, so the
     * derived verdict is produced by production code over a production row.
     */
    private class LostClaimWithRealRowDao(
        private val realDao: EpisodeDownloadDao,
        private val targetEpisodeId: String,
    ) : EpisodeDownloadDao by realDao {
        override suspend fun insertIfAbsent(episode: EpisodeDownloadEntity): Long =
            if (episode.episodeId == targetEpisodeId) -1L else realDao.insertIfAbsent(episode)
    }

    /**
     * A delegating double that makes the creation claim lose BOTH attempts with
     * no row to re-read afterwards — the window [queueResolvedEpisode] guards
     * with its single retry. Everything else forwards verbatim to the REAL DAO,
     * so no other part of the production path is bypassed.
     */
    private class VanishedRowDao(
        private val realDao: EpisodeDownloadDao,
    ) : EpisodeDownloadDao by realDao {
        override suspend fun insertIfAbsent(episode: EpisodeDownloadEntity): Long = -1L

        override suspend fun getById(episodeId: String): EpisodeDownloadEntity? = null
    }

    /**
     * A delegating double that lands a Cancel immediately AFTER the terminal
     * claim has won — the exact F7a window, which production code has no hook
     * for. Delegation keeps every other member verbatim on the REAL DAO, so
     * nothing about the production path is bypassed.
     */
    private class CancelAfterClaimDao(
        private val realDao: EpisodeDownloadDao,
        private val targetEpisodeId: String,
    ) : EpisodeDownloadDao by realDao {
        override suspend fun claimTerminalRequeue(
            episodeId: String, expectedState: String, sessionId: String,
            episodeNumber: Float, episodeSession: String, title: String,
            fansub: String, audio: String, playUrl: String, createdAt: Long, timestamp: Long,
        ): Int {
            val won = realDao.claimTerminalRequeue(
                episodeId, expectedState, sessionId, episodeNumber, episodeSession,
                title, fansub, audio, playUrl, createdAt, timestamp,
            )
            if (won > 0 && episodeId == targetEpisodeId) {
                realDao.updateState(targetEpisodeId, EpisodeDownloadEntity.STATE_CANCELLED)
            }
            return won
        }
    }

    /**
     * A delegating double that lets a concurrent Retry win the row first: the
     * target's `beginRetry` runs immediately BEFORE this submission's claim, so
     * the claim finds `RESOLVING` where the caller saw `FAILED` and refuses.
     */
    private class RetryRacingDao(
        private val realDao: EpisodeDownloadDao,
        private val targetEpisodeId: String,
    ) : EpisodeDownloadDao by realDao {
        private var armed = true

        override suspend fun claimTerminalRequeue(
            episodeId: String, expectedState: String, sessionId: String,
            episodeNumber: Float, episodeSession: String, title: String,
            fansub: String, audio: String, playUrl: String, createdAt: Long, timestamp: Long,
        ): Int {
            if (armed && episodeId == targetEpisodeId) {
                armed = false
                realDao.beginRetry(targetEpisodeId, expectedState)
            }
            return realDao.claimTerminalRequeue(
                episodeId, expectedState, sessionId, episodeNumber, episodeSession,
                title, fansub, audio, playUrl, createdAt, timestamp,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Review finding M-4: the watchlist's notion of "already handled"
    // -----------------------------------------------------------------------

    @Test
    fun a_completed_row_whose_output_is_gone_is_no_longer_reported_as_already_handled() = runBlocking {
        val dao = db.episodeDownloadDao()
        val gone = File(context.cacheDir, "outcome-vanished-$runStamp.mp4")
        gone.delete()
        val present = File(context.cacheDir, "outcome-present-handled-$runStamp.mp4")
        present.writeBytes("already-assembled-mp4".toByteArray())
        try {
            // Positive control first: a COMPLETED row with a real, non-empty
            // local file IS handled, so the assertion below is a live predicate
            // rather than one that could pass for the wrong reason.
            dao.upsert(
                baseEpisode(51, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                    outputPath = present.absolutePath,
                    outputUri = null,
                ),
            )
            assertTrue(
                "a real output is what makes a finished episode handled",
                repository.isEpisodeAlreadyHandled(sessionId, episodeInfo(51)),
            )

            // The same shape with nothing behind it: the app-private file is
            // gone and the only surviving evidence is a reference this
            // repository has no resolver to check. Before Slice B any non-blank
            // URI counted as proof, so a watchlist check would keep skipping
            // this episode for ever although nothing was on disk any more —
            // Python re-downloads when its file is gone, and now so do we.
            dao.upsert(
                baseEpisode(52, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                    outputPath = gone.absolutePath,
                    outputUri = "content://media/external/video/media/999999999",
                ),
            )
            assertFalse(
                "an unverifiable export is not a download the user still has",
                repository.isEpisodeAlreadyHandled(sessionId, episodeInfo(52)),
            )
            assertFalse(
                repository.isEpisodeOutputPresent(sessionId, episodeInfo(52)),
            )
        } finally {
            present.delete()
        }
    }

    /**
     * A delegating [EpisodeDownloadDao] that lets a test land a Cancel in the
     * window between a row claim and its hand-off.
     *
     * Neither production code nor Room offers a hook for that window, so the
     * double sits at the persistence boundary instead: every member forwards
     * verbatim to the REAL DAO, and only the FIRST successful `insertIfAbsent`
     * is followed by a flip to `CANCELLED` — exactly what `cancel()` would do a
     * moment later. Nothing about the production path is bypassed.
     */
    private class ClaimThenCancelDao(
        private val realDao: EpisodeDownloadDao,
        private val targetEpisodeId: String,
    ) : EpisodeDownloadDao {
        private var armed = true

        override suspend fun insertIfAbsent(episode: EpisodeDownloadEntity): Long {
            val rowId = realDao.insertIfAbsent(episode)
            if (armed && rowId > 0L && episode.episodeId == targetEpisodeId) {
                armed = false
                realDao.updateState(targetEpisodeId, EpisodeDownloadEntity.STATE_CANCELLED)
            }
            return rowId
        }

        override suspend fun claimTerminalRequeue(
            episodeId: String, expectedState: String, sessionId: String,
            episodeNumber: Float, episodeSession: String, title: String,
            fansub: String, audio: String, playUrl: String, createdAt: Long, timestamp: Long,
        ) = realDao.claimTerminalRequeue(
            episodeId, expectedState, sessionId, episodeNumber, episodeSession,
            title, fansub, audio, playUrl, createdAt, timestamp,
        )

        override suspend fun repairOutputUriIfState(
            episodeId: String, expectedState: String, outputUri: String, timestamp: Long,
        ): Int = realDao.repairOutputUriIfState(episodeId, expectedState, outputUri, timestamp)

        override suspend fun getById(episodeId: String) = realDao.getById(episodeId)
        override fun getBySessionId(sessionId: String) = realDao.getBySessionId(sessionId)
        override fun getByState(state: String) = realDao.getByState(state)
        override suspend fun upsert(episode: EpisodeDownloadEntity) = realDao.upsert(episode)
        override suspend fun updateProgress(episodeId: String, completedSegments: Int, downloadedBytes: Long, speed: Long, etaSeconds: Long, timestamp: Long) =
            realDao.updateProgress(episodeId, completedSegments, downloadedBytes, speed, etaSeconds, timestamp)

        override suspend fun updateState(episodeId: String, state: String, timestamp: Long) =
            realDao.updateState(episodeId, state, timestamp)

        override suspend fun updateError(episodeId: String, errorMessage: String, timestamp: Long) =
            realDao.updateError(episodeId, errorMessage, timestamp)

        override suspend fun updateCompleted(episodeId: String, outputPath: String, timestamp: Long) =
            realDao.updateCompleted(episodeId, outputPath, timestamp)

        override suspend fun updateCompletedIfState(episodeId: String, expectedState: String, outputPath: String, timestamp: Long): Int =
            realDao.updateCompletedIfState(episodeId, expectedState, outputPath, timestamp)

        override suspend fun delete(episodeId: String) = realDao.delete(episodeId)
        override suspend fun countActive(): Int = realDao.countActive()
        override suspend fun getTotalEpisodeCount(): Int = realDao.getTotalEpisodeCount()
        override suspend fun countBySession(sessionId: String): Int = realDao.countBySession(sessionId)
        override suspend fun countResolvedPending(): Int = realDao.countResolvedPending()
        override suspend fun clearStreamInfo(episodeId: String, timestamp: Long) =
            realDao.clearStreamInfo(episodeId, timestamp)

        override suspend fun updateStateForResume(episodeId: String, expectedState: String, newState: String, timestamp: Long): Int =
            realDao.updateStateForResume(episodeId, expectedState, newState, timestamp)

        override suspend fun beginRetry(episodeId: String, expectedState: String, timestamp: Long): Int =
            realDao.beginRetry(episodeId, expectedState, timestamp)

        override suspend fun updateErrorIfState(episodeId: String, expectedState: String, errorMessage: String, timestamp: Long): Int =
            realDao.updateErrorIfState(episodeId, expectedState, errorMessage, timestamp)

        override suspend fun cancelIfNotTerminal(episodeId: String, timestamp: Long): Int =
            realDao.cancelIfNotTerminal(episodeId, timestamp)

        override suspend fun getNextQueuedWithM3u8(): EpisodeDownloadEntity? = realDao.getNextQueuedWithM3u8()
        override suspend fun getQueuedWithM3u8(): List<EpisodeDownloadEntity> = realDao.getQueuedWithM3u8()
        override suspend fun getEpisodesForReconciliation(): List<EpisodeDownloadEntity> = realDao.getEpisodesForReconciliation()
        override suspend fun saveStreamInfo(episodeId: String, m3u8Url: String, userAgent: String, referer: String, segmentDir: String, timestamp: Long) =
            realDao.saveStreamInfo(episodeId, m3u8Url, userAgent, referer, segmentDir, timestamp)

        override suspend fun updateTotalSegments(episodeId: String, total: Int, timestamp: Long) =
            realDao.updateTotalSegments(episodeId, total, timestamp)

        override suspend fun updateOutputUri(episodeId: String, outputUri: String?, timestamp: Long) =
            realDao.updateOutputUri(episodeId, outputUri, timestamp)

        override fun observeAllOrdered(): Flow<List<EpisodeDownloadEntity>> = realDao.observeAllOrdered()
        override suspend fun getAllOutputUris(): List<String> = realDao.getAllOutputUris()
        override suspend fun otherReferenceAudios(outputUri: String, episodeId: String): List<String> =
            realDao.otherReferenceAudios(outputUri, episodeId)

        // `Replace file` (DECISIONS 152) — the five replacement queries this
        // double never triggers; forwarded verbatim so the double stays a pure
        // observation seam and the REAL DAO remains the only writer.
        override suspend fun beginReplacement(episodeId: String, expectedState: String, replacePriorUri: String?, timestamp: Long): Int =
            realDao.beginReplacement(episodeId, expectedState, replacePriorUri, timestamp)

        override suspend fun finishReplacement(episodeId: String, expectedState: String, outputPath: String, timestamp: Long): Int =
            realDao.finishReplacement(episodeId, expectedState, outputPath, timestamp)

        override suspend fun restoreCompletedForUncommittedReplacement(episodeId: String, expectedState: String, timestamp: Long): Int =
            realDao.restoreCompletedForUncommittedReplacement(episodeId, expectedState, timestamp)

        override suspend fun clearReplacementIntent(episodeId: String, timestamp: Long): Int =
            realDao.clearReplacementIntent(episodeId, timestamp)

        override suspend fun clearReplacePriorUri(episodeId: String, timestamp: Long) =
            realDao.clearReplacePriorUri(episodeId, timestamp)

        // Run-ownership guards (pause/resume race fix) — Worker-only writers;
        // forwarded verbatim for the same reason as the replacement queries
        // above: the double stays a pure observation seam and the REAL DAO
        // remains the only writer.
        override suspend fun claimEntryRunWithRunId(episodeId: String, expectedState: String, runId: String, timestamp: Long): Int =
            realDao.claimEntryRunWithRunId(episodeId, expectedState, runId, timestamp)

        override suspend fun updateStateIfOwned(episodeId: String, expectedState: String, newState: String, runId: String, timestamp: Long): Int =
            realDao.updateStateIfOwned(episodeId, expectedState, newState, runId, timestamp)

        override suspend fun updateErrorIfOwned(episodeId: String, expectedState: String, errorMessage: String, runId: String, timestamp: Long): Int =
            realDao.updateErrorIfOwned(episodeId, expectedState, errorMessage, runId, timestamp)

        override suspend fun updateCompletedIfOwned(episodeId: String, expectedState: String, outputPath: String, runId: String, timestamp: Long): Int =
            realDao.updateCompletedIfOwned(episodeId, expectedState, outputPath, runId, timestamp)

        override suspend fun finishReplacementIfOwned(episodeId: String, expectedState: String, outputPath: String, runId: String, timestamp: Long): Int =
            realDao.finishReplacementIfOwned(episodeId, expectedState, outputPath, runId, timestamp)

        override suspend fun restoreCompletedForUncommittedReplacementIfOwned(episodeId: String, expectedState: String, runId: String, timestamp: Long): Int =
            realDao.restoreCompletedForUncommittedReplacementIfOwned(episodeId, expectedState, runId, timestamp)
    }
}
