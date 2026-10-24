package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MaxParallel soft-limit (concurrency) behavior.
 * Phase 10 architecture v10 §4, §16 (ConcurrencyTest).
 *
 * The architecture defines this as a SOFT/best-effort concurrency limit
 * (active-count check before DOWNLOADING transition) — NOT a strict
 * distributed semaphore and NOT equivalent to the Python threading.Semaphore.
 *
 * EMULATOR-VERIFIED with real Workers:
 * - countActive counts ONLY DOWNLOADING and ASSEMBLING rows
 * - When active >= maxParallel, the Worker DEFERS (returns success without
 *   transitioning the episode to DOWNLOADING)
 * - Multiple Workers enqueued at the limit all defer — no uncontrolled
 *   concurrent starts
 * - When active drops below the limit, a re-enqueued Worker proceeds
 */
@RunWith(AndroidJUnit4::class)
class ConcurrencyTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-conc-test"
    // the production default is now Python's `max_parallel` = 2, and the
    // Worker reads the PERSISTED setting once per run — so setup pins that value
    // (below) instead of assuming it, and teardown restores it.
    private val dummyIds = listOf("ep-conc-active-1", "ep-conc-active-2")
    private val targetA = "ep-conc-target-a"
    private val targetB = "ep-conc-target-b"
    private val config = DownloadConfig() // production defaults: maxParallel = 2 (Python)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
        // Known precondition: the Worker's concurrency gate reads this persisted
        // value (Phase 12A config wiring), so the test states it explicitly.
        runBlocking { com.pahebatcher.app.data.config.AppConfig(context).updateMaxParallel(config.maxParallel) }
    }

    @After
    fun teardown() {
        (dummyIds + targetA + targetB).forEach {
            workManager.cancelUniqueWork("episode_download_$it")
        }
        Phase10TestSupport.deleteSession(db, sessionId)
        // Restore the Python default for suites that depend on it.
        runBlocking { com.pahebatcher.app.data.config.AppConfig(context).updateMaxParallel(2) }
    }

    private fun seedEpisode(id: String, state: String, withStreamInfo: Boolean) = EpisodeDownloadEntity(
        episodeId = id,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$id",
        title = "Concurrency Episode $id",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = state,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        m3u8Url = if (withStreamInfo) Phase10TestSupport.FAST_FAIL_URL else null,
        userAgent = if (withStreamInfo) "TestUA" else null,
        referer = if (withStreamInfo) "https://example.com" else null,
        segmentDir = Phase10TestSupport.segmentDirFor(context, id)
    )

    @Test
    fun countActive_includes_only_downloading_and_assembling() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(seedEpisode(dummyIds[0], EpisodeDownloadEntity.STATE_DOWNLOADING, false))
            dao.upsert(seedEpisode(dummyIds[1], EpisodeDownloadEntity.STATE_ASSEMBLING, false))
            dao.upsert(seedEpisode(targetA, EpisodeDownloadEntity.STATE_QUEUED, false))
            dao.upsert(seedEpisode(targetB, EpisodeDownloadEntity.STATE_COMPLETED, false))

            // QUEUED / COMPLETED / RESOLVING / FAILED / CANCELLED must NOT count
            val count = dao.countActive()
            assertEquals(
                "Only DOWNLOADING and ASSEMBLING are active",
                2,
                count
            )
        }
    }

    @Test
    fun workers_defer_at_maxParallel_and_proceed_when_below_limit() {
        runBlocking {
            val dao = db.episodeDownloadDao()

            // Fill all maxParallel slots with active (DOWNLOADING) episodes —
            // no Workers attached; these are active-count rows only
            dummyIds.forEach { dao.upsert(seedEpisode(it, EpisodeDownloadEntity.STATE_DOWNLOADING, false)) }
            assertEquals(config.maxParallel, dao.countActive())

            // Seed two fully-resolved QUEUED targets
            dao.upsert(seedEpisode(targetA, EpisodeDownloadEntity.STATE_QUEUED, withStreamInfo = true))
            dao.upsert(seedEpisode(targetB, EpisodeDownloadEntity.STATE_QUEUED, withStreamInfo = true))

            // Enqueue BOTH workers while at the limit (multiple Workers)
            repository.enqueueWorker(targetA)
            repository.enqueueWorker(targetB)

            // Both Workers run and DEFER — work succeeds, no DOWNLOADING transition
            val bothFinished = Phase10TestSupport.awaitUntil(60_000) {
                val a = workManager.getWorkInfosForUniqueWork("episode_download_$targetA").get()
                val b = workManager.getWorkInfosForUniqueWork("episode_download_$targetB").get()
                a.isNotEmpty() && b.isNotEmpty() &&
                    a.all { it.state.isFinished } && b.all { it.state.isFinished }
            }
            assertTrue("Both deferred Workers must finish", bothFinished)

            // Deferred = Result.success WITHOUT state change (still QUEUED)
            assertEquals(
                "Deferred episode must remain QUEUED",
                EpisodeDownloadEntity.STATE_QUEUED,
                dao.getById(targetA)?.state
            )
            assertEquals(
                "Deferred episode must remain QUEUED",
                EpisodeDownloadEntity.STATE_QUEUED,
                dao.getById(targetB)?.state
            )
            // No uncontrolled concurrent starts: active count unchanged at the limit
            assertEquals(config.maxParallel, dao.countActive())

            // Free the slots → re-enqueue target A → Worker now PROCEEDS
            dummyIds.forEach { dao.updateState(it, EpisodeDownloadEntity.STATE_FAILED) }
            assertEquals(0, dao.countActive())

            repository.enqueueWorker(targetA)
            val progressed = Phase10TestSupport.awaitUntil(60_000) {
                val state = kotlinx.coroutines.runBlocking { dao.getById(targetA)?.state }
                state == EpisodeDownloadEntity.STATE_DOWNLOADING ||
                    state == EpisodeDownloadEntity.STATE_FAILED
            }
            assertTrue(
                "Worker must proceed when below maxParallel (state: ${dao.getById(targetA)?.state})",
                progressed
            )
        }
    }
}
