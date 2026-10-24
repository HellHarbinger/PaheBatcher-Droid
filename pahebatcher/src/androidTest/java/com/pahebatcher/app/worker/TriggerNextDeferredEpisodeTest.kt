package com.pahebatcher.app.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Failure-path deferred-episode trigger test (Phase 10 architecture v10 §16,
 * TriggerNextDeferredEpisodeTest).
 *
 * the pre-corrective DownloadWorker only invoked
 * triggerNextDeferredEpisode() on the SUCCESS path. An episode that FAILED
 * (or was CANCELLED / died with an exception) left every queued episode
 * permanently stuck in QUEUED — nothing would ever start them until a manual
 * re-enqueue or process restart.
 *
 * This test pins the failure path end to end against the REAL application
 * WorkManager (custom DownloadWorkerFactory), the REAL Room database, and
 * the REAL DownloadWorker:
 *
 *   seed A (QUEUED + resolved stream, fast-failing URL)
 *   seed B (QUEUED + resolved stream, oldest createdAt, never enqueued here)
 *   enqueue ONLY A
 *   → A's Worker runs, fails, persists FAILED
 *   → the every-completion trigger (try/finally, NonCancellable) must
 *     pick B via getNextQueuedWithM3u8() and enqueue episode_download_<B>
 *
 * Without F-C2, B stays QUEUED forever and this test fails.
 *
 * Setup drains other sessions' non-terminal rows so this test fully controls
 * both the trigger's queue ordering (getNextQueuedWithM3u8 ORDER BY
 * createdAt ASC) and countActive() gating (drained leftovers would both
 * steal the pick and block the enqueue once activeCount >= maxParallel).
 *
 * All IDs/timestamps are run-scoped so unique Work names and queue ordering
 * never collide with rows or work left behind by a previous (aborted) run.
 * External CDN/Kwik/Cloudflare access is deliberately NOT involved: the
 * fast-failing loopback URL exercises real retry/failure semantics locally.
 */
@RunWith(AndroidJUnit4::class)
class TriggerNextDeferredEpisodeTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-trigger-$runStamp"
    private val episodeA = "ep-trigger-a-$runStamp"
    private val episodeB = "ep-trigger-b-$runStamp"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)

        runBlocking {
            val dao = db.episodeDownloadDao()
            // Drain OTHER sessions' non-terminal rows: a leftover
            // QUEUED+m3u8 row with an older createdAt would be picked by
            // getNextQueuedWithM3u8 instead of B, and leftover
            // DOWNLOADING/ASSEMBLING rows would push countActive() to
            // maxParallel so the trigger would defer instead of enqueueing.
            val leftovers = dao.getEpisodesForReconciliation().filter {
                it.sessionId != sessionId &&
                    it.state != EpisodeDownloadEntity.STATE_COMPLETED &&
                    it.state != EpisodeDownloadEntity.STATE_FAILED &&
                    it.state != EpisodeDownloadEntity.STATE_CANCELLED
            }
            for (leftover in leftovers) {
                dao.delete(leftover.episodeId)
            }
        }

        // Cross-test quiescence (full-suite race guard, F-G1): a prior suite
        // test may have cancelled its DownloadWorker asynchronously; that
        // worker's F-C2 finally-trigger can otherwise fire AFTER this test
        // seeds B and enqueue B before the baseline assertion below. The
        // drain above already removed every other session's non-terminal
        // rows, so any still-in-flight stray trigger is now a no-op — but it
        // must COMPLETE before we seed, which requires waiting until no
        // WorkInfo is in a non-terminal state (doWork, including its
        // NonCancellable finally-trigger, has returned for all work).
        //
        // the app now schedules a PERMANENT periodic
        // watchlist check (`WatchlistCheckScheduler`), which is ENQUEUED
        // between every run and would therefore make "no unfinished work"
        // permanently false. It is cancelled for the duration of this test —
        // it is not the stray trigger this guard is about — and re-registered
        // in teardown so the rest of the suite (and the device) keeps its
        // schedule.
        workManager.cancelUniqueWork(WatchlistCheckScheduler.UNIQUE_WORK_NAME)
        val activeWorkQuery = WorkQuery.Builder.fromStates(
            listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
        ).build()
        val quiescent = Phase10TestSupport.awaitUntil(120_000) {
            workManager.getWorkInfos(activeWorkQuery).get().isEmpty()
        }
        assertTrue(
            "WorkManager must have no unfinished work before seeding deferred episodes",
            quiescent
        )
    }

    @After
    fun teardown() {
        workManager.cancelUniqueWork("episode_download_$episodeA")
        workManager.cancelUniqueWork("episode_download_$episodeB")
        // the Wi-Fi-only variant flips the persisted
        // setting — ALWAYS restore the shipped default so no later suite (or
        // the device) inherits it.
        runBlocking { AppConfig(context).updateWifiOnly(false) }
        // Phase 14 (PHASE14_PLAN §17 row "deferred liveness under parking"):
        // the auto-resume variant flips the persisted setting for the same
        // reason — restore the shipped default (OFF) unconditionally.
        runBlocking { AppConfig(context).updateAutoResume(false) }
        // Restore the application's periodic watchlist check cancelled above.
        WatchlistCheckScheduler.schedule(workManager)
        Phase10TestSupport.deleteSession(db, sessionId)
        // Remove seeded (never-written) segment dirs — FAST_FAIL_URL never
        // downloads a byte, but segmentDirFor() created the directories.
        File(context.filesDir, "downloads/$episodeA").deleteRecursively()
        File(context.filesDir, "downloads/$episodeB").deleteRecursively()
    }

    @Test
    fun failurePath_triggersNextDeferredEpisode() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val now = System.currentTimeMillis()

            // B is the deferred candidate: QUEUED + resolved stream,
            // oldest createdAt in the queue.
            dao.upsert(
                seededEpisode(
                    episodeId = episodeB,
                    createdAt = now - 5_000
                )
            )
            // A will run first and fail.
            dao.upsert(
                seededEpisode(
                    episodeId = episodeA,
                    createdAt = now - 4_000
                )
            )

            // Baseline: nothing has enqueued B yet (fresh unique work name).
            assertTrue(
                "B must not be enqueued before A fails",
                workManager.getWorkInfosForUniqueWork("episode_download_$episodeB")
                    .get()
                    .isEmpty()
            )

            // Enqueue ONLY A — B must get picked up by the trigger.
            repository.enqueueWorker(episodeA)

            // A's Worker must run and finish (fast-failing loopback URL,
            // real HTTP + episode-level retry backoff).
            val aFinished = Phase10TestSupport.awaitUntil(120_000) {
                val infos = workManager.getWorkInfosForUniqueWork("episode_download_$episodeA").get()
                infos.isNotEmpty() && infos.all { it.state.isFinished }
            }
            assertTrue("A's Worker must finish", aFinished)
            assertEquals(
                "A must persist FAILED after exhausting retries",
                EpisodeDownloadEntity.STATE_FAILED,
                dao.getById(episodeA)?.state
            )

            // F-C2: the every-completion trigger (try/finally) must enqueue B.
            val bEnqueued = Phase10TestSupport.awaitUntil(30_000) {
                workManager.getWorkInfosForUniqueWork("episode_download_$episodeB")
                    .get()
                    .isNotEmpty()
            }
            assertTrue(
                "Failure path must enqueue the next deferred episode (B)",
                bEnqueued
            )
        }
    }

    /**
     * The deferred-episode trigger builds
     * a NEW WorkRequest and therefore reads the CURRENT `Wi-Fi-only downloads`
     * setting through the production `DownloadWorkerFactory` wiring — the same
     * end-to-end flow as above, but with the PERSISTED setting ON, so B's
     * constraints must be `NetworkType.UNMETERED`.
     */
    @Test
    fun deferredTrigger_attachesTheCurrentWifiOnlyConstraint() {
        runBlocking {
            // The persisted setting — read at trigger time by the production
            // factory's provider, NOT from the worker run's config snapshot.
            AppConfig(context).updateWifiOnly(true)

            val dao = db.episodeDownloadDao()
            val now = System.currentTimeMillis()
            dao.upsert(seededEpisode(episodeId = episodeB, createdAt = now - 5_000))
            dao.upsert(seededEpisode(episodeId = episodeA, createdAt = now - 4_000))

            repository.enqueueWorker(episodeA)

            val aFinished = Phase10TestSupport.awaitUntil(120_000) {
                val infos = workManager.getWorkInfosForUniqueWork("episode_download_$episodeA").get()
                infos.isNotEmpty() && infos.all { it.state.isFinished }
            }
            assertTrue("A's Worker must finish", aFinished)

            val bEnqueued = Phase10TestSupport.awaitUntil(30_000) {
                workManager.getWorkInfosForUniqueWork("episode_download_$episodeB").get()
                    .isNotEmpty()
            }
            assertTrue("the trigger must enqueue B", bEnqueued)

            val bConstraints = workManager.getWorkInfosForUniqueWork("episode_download_$episodeB")
                .get()
                .single()
                .constraints
            assertEquals(
                "the deferred-episode enqueue must read the CURRENT Wi-Fi-only setting",
                NetworkType.UNMETERED,
                bConstraints.requiredNetworkType,
            )
        }
    }

    /**
     * Phase 14 (PHASE14_PLAN §17, "deferred liveness under parking").
     *
     * Row A is the OLDEST queued row and its request parks in auto-resume
     * backoff (`Result.retry()` → request ENQUEUED, row back in QUEUED). Row B
     * is queued behind it. The every-completion trigger must skip A — its
     * unique work exists and is unfinished (the §10/§13 candidate-skip
     * amendment) — and enqueue B, while A's parked request stays untouched.
     *
     * Without the amendment A would KEEP-no-op every trigger round and B would
     * sit QUEUED until A's backoff exhausted (reviewer H1).
     */
    @Test
    fun deferredLiveness_parkedAutoResumeRowDoesNotBlockLaterEpisode() {
        runBlocking {
            // The persisted setting, read at the transport-failure decision by
            // the production factory's autoResumeProvider (PHASE14_PLAN §10/§18).
            AppConfig(context).updateAutoResume(true)

            val dao = db.episodeDownloadDao()
            val now = System.currentTimeMillis()
            // A is OLDEST — the trigger's candidate order (createdAt ASC) reaches
            // it first and must skip it; B is the later row that must start.
            dao.upsert(seededEpisode(episodeId = episodeA, createdAt = now - 5_000))
            dao.upsert(seededEpisode(episodeId = episodeB, createdAt = now - 4_000))

            repository.enqueueWorker(episodeA)

            // A's run: FAST_FAIL_URL → TRANSPORT → auto-resume park → the row is
            // back in QUEUED with its request parked in backoff (never FAILED).
            val parked = Phase10TestSupport.awaitUntil(120_000) {
                Phase10TestSupport.isParkedForAutoResume(db, workManager, episodeA)
            }
            assertTrue("A must park back in QUEUED with its request ENQUEUED", parked)
            assertNotEquals(
                "a transport failure with auto-resume ON must not be terminal",
                EpisodeDownloadEntity.STATE_FAILED,
                dao.getById(episodeA)?.state,
            )
            val parkedA = workManager.getWorkInfosForUniqueWork("episode_download_$episodeA")
                .get()
                .single()
            assertTrue(
                "the parked request must still hold auto-resume budget " +
                    "(attempt ${parkedA.runAttemptCount} of ${DownloadWorker.AUTO_RESUME_MAX_RETRIES})",
                parkedA.runAttemptCount < DownloadWorker.AUTO_RESUME_MAX_RETRIES,
            )

            // The every-completion trigger (try/finally) must pick B even though
            // A — the OLDEST candidate — is parked with live work.
            val bEnqueued = Phase10TestSupport.awaitUntil(30_000) {
                workManager.getWorkInfosForUniqueWork("episode_download_$episodeB").get()
                    .isNotEmpty()
            }
            assertTrue(
                "a parked row must not block the next deferred episode (B)",
                bEnqueued,
            )

            // A's parked request is untouched by that trigger round.
            val afterA = workManager.getWorkInfosForUniqueWork("episode_download_$episodeA")
                .get()
                .single()
            assertEquals("A keeps its ONE parked request (no duplicate)", parkedA.id, afterA.id)
            assertEquals("A's request is still parked", WorkInfo.State.ENQUEUED, afterA.state)
            assertEquals(
                "A's attempt count is unchanged by the trigger round",
                parkedA.runAttemptCount,
                afterA.runAttemptCount,
            )
            assertEquals(
                "A is still parked in QUEUED",
                EpisodeDownloadEntity.STATE_QUEUED,
                dao.getById(episodeA)?.state,
            )
        }
    }

    /** QUEUED episode with a resolved (fast-failing) stream, never enqueued by this test. */
    private fun seededEpisode(episodeId: String, createdAt: Long) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Trigger Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = EpisodeDownloadEntity.STATE_QUEUED,
        createdAt = createdAt,
        updatedAt = createdAt,
        m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
        userAgent = "TriggerTestAgent",
        referer = "https://example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId)
    )
}
