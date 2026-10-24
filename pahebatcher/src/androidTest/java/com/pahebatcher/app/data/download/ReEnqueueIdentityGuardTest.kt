package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.RecordStreamResolver
import com.pahebatcher.app.support.RetryTestStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 11.5 item (e) — reviewer finding M-6 (LIMITATIONS §27.2).
 *
 * `DownloadRepository.reEnqueue()` used to execute the unguarded
 * `updateError("Missing play page URL")` BEFORE the session lookup and before the
 * identity round-trip, contradicting its own contract ("Identity must round-trip
 * BEFORE any mutation") and forcing a row that a concurrent winner had already
 * claimed into FAILED.
 *
 * The contract under test:
 *
 * 1. identity/session round-trip failure ⇒ ZERO persistent mutation, even when
 *    `playUrl` is blank (the branch that used to write first);
 * 2. a row that passes every round-trip check with a blank `playUrl` still fails
 *    honestly — `FAILED` + "Missing play page URL" — without ever reaching the
 *    `beginRetry` claim (retryCount untouched), through the CAS-guarded
 *    `updateErrorIfState(priorState)` write;
 * 3. (reviewer M4) a concurrent winner that claims the row out of its terminal
 *    state between the eligibility read and that blank-`playUrl` write is never
 *    overwritten: the guarded write is refused and the winner's claim stands.
 *
 * Runs against the REAL Room database singleton; the resolver is a test double
 * (mocks in tests are permitted, never in production).
 */
@RunWith(AndroidJUnit4::class)
class ReEnqueueIdentityGuardTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-idguard-$runStamp"

    /** `episodeDownloadId()` = `<animeSession>_<epStr>_<audio>` (whole numbers). */
    private fun episodeId(number: Int) =
        "${sessionId}_${number}_${EpisodeInfo.AUDIO_JAPANESE}"

    private fun repository() = DownloadRepository(
        context,
        db.episodeDownloadDao(),
        db.downloadSessionDao(),
        // Wired so the resolver-null check never short-circuits the round-trip
        // checks this test exists to exercise.
        streamResolver = RecordStreamResolver { RetryTestStream.streamInfo() },
    )

    private fun blankPlayUrlEpisode(id: String, number: Float, state: String) = EpisodeDownloadEntity(
        episodeId = id,
        sessionId = sessionId,
        episodeNumber = number,
        episodeSession = "eps-blank-$id",
        title = "Blank Play URL Episode",
        fansub = "GuardFansub",
        audio = EpisodeInfo.AUDIO_JAPANESE,
        playUrl = "",
        state = state,
        retryCount = 2,
        errorMessage = "previous failure",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        m3u8Url = "https://cdn.example.com/stale.m3u8",
        userAgent = "StaleUA",
        referer = "https://stale.example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, id),
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    // ── Round-trip failure ⇒ zero mutation ───────────────────────────────────

    @Test
    fun identity_round_trip_failure_with_blank_play_url_writes_nothing() = runBlocking {
        val dao = db.episodeDownloadDao()
        // The persisted id does NOT match its own episode columns, so the key the
        // pipeline would re-derive differs — and playUrl is blank, so the branch
        // that used to write FAILED first is armed.
        val seeded = blankPlayUrlEpisode("bogus-$sessionId", 7.0f, EpisodeDownloadEntity.STATE_FAILED)
        dao.upsert(seeded)

        repository().reEnqueue(seeded.episodeId)

        val after = dao.getById(seeded.episodeId)
        assertNotNull("the row must still exist", after)
        assertEquals(
            "an identity round-trip failure must produce ZERO persistent mutation",
            seeded,
            after,
        )
        assertEquals(EpisodeDownloadEntity.STATE_FAILED, after!!.state)
        assertEquals("previous failure", after.errorMessage)
        assertEquals("the retry claim must never have run", 2, after.retryCount)
        assertEquals(
            "even the write timestamp must be untouched",
            seeded.updatedAt,
            after.updatedAt,
        )
    }

    // ── Valid identity + blank playUrl ⇒ honest, guarded failure ─────────────

    @Test
    fun valid_identity_with_blank_play_url_fails_honestly_without_claiming() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(3)
        val seeded = blankPlayUrlEpisode(id, 3.0f, EpisodeDownloadEntity.STATE_FAILED)
        dao.upsert(seeded)

        repository().reEnqueue(id)

        val after = dao.getById(id)
        assertNotNull(after)
        assertEquals(
            "a row that passed every round-trip check must fail honestly",
            EpisodeDownloadEntity.STATE_FAILED,
            after!!.state,
        )
        assertEquals("Missing play page URL", after.errorMessage)
        assertEquals(
            "the blank-playUrl branch must stop BEFORE the beginRetry claim",
            2,
            after.retryCount,
        )
        assertEquals(
            "this branch clears nothing else (no claim ran)",
            seeded.m3u8Url,
            after.m3u8Url,
        )
        assertEquals(seeded.playUrl, after.playUrl)
    }

    // ── Concurrent winner between eligibility and the blank-playUrl write ────

    @Test
    fun blank_play_url_write_refuses_to_overwrite_a_concurrent_winner() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(5)
        val seeded = blankPlayUrlEpisode(id, 5.0f, EpisodeDownloadEntity.STATE_FAILED)
        dao.upsert(seeded)

        // Reviewer M4: reEnqueue decides eligibility on `priorState` (FAILED) and
        // only then reaches the blank-playUrl write. The seam replays a concurrent
        // winner — another Retry's atomic `beginRetry` claim — landing in exactly
        // that window, so the guarded write must be refused.
        val racingDao = WinnerRacingDao(dao)
        DownloadRepository(
            context,
            racingDao,
            db.downloadSessionDao(),
            streamResolver = RecordStreamResolver { RetryTestStream.streamInfo() },
        ).reEnqueue(id)

        val after = dao.getById(id)
        assertNotNull("the row must still exist", after)
        assertEquals(
            "the concurrent winner's claim (FAILED → RESOLVING) must stand",
            EpisodeDownloadEntity.STATE_RESOLVING,
            after!!.state,
        )
        assertEquals(
            "exactly ONE claim may count — the winner's beginRetry (2 → 3), never a second one",
            3,
            after.retryCount,
        )
        assertNull(
            "the blank-playUrl failure write must have been REFUSED " +
                "(no 'Missing play page URL' stamped over the winner)",
            after.errorMessage,
        )
        assertEquals(
            "the winner's claim-time cleanup owns these — this call must add nothing",
            null,
            after.m3u8Url,
        )
        assertEquals(
            "playUrl is never touched by either path",
            seeded.playUrl,
            after.playUrl,
        )
    }

    /**
     * DAO seam for the M4 race: the first guarded error write is intercepted and,
     * BEFORE delegating, a concurrent winner performs its real atomic claim
     * (`beginRetry` → RESOLVING, retryCount + 1, stale stream/error cleared).
     * The subsequent `updateErrorIfState(priorState = FAILED)` then either is
     * refused (CAS — winner survives, test passes) or stamps FAILED over the
     * winner (unguarded — test fails).
     */
    private class WinnerRacingDao(
        private val delegate: EpisodeDownloadDao,
    ) : EpisodeDownloadDao by delegate {

        private var raced = false

        override suspend fun updateErrorIfState(
            episodeId: String,
            expectedState: String,
            errorMessage: String,
            timestamp: Long,
        ): Int {
            if (!raced) {
                raced = true
                delegate.beginRetry(episodeId, expectedState)
            }
            return delegate.updateErrorIfState(episodeId, expectedState, errorMessage, timestamp)
        }
    }
}
