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
 * Unique-work KEEP semantics.
 * Phase 10 architecture v10 §6, §16 (UniqueWorkTest).
 *
 * Verifies against the REAL application WorkManager:
 * - Duplicate enqueue while work is unfinished → KEEP holds (single work chain)
 * - Enqueue after existing work is terminal → new work is inserted
 *
 * Uses a hanging stream URL so the Worker stays RUNNING long enough to make
 * "while running" deterministic. The URL is an unroutable TEST-NET-3 address —
 * this exercises the real Worker lifecycle, not CDN/Kwik behavior.
 */
@RunWith(AndroidJUnit4::class)
class UniqueWorkTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-unique-test"
    private val episodeId = "ep-unique-test"
    private val uniqueName = "episode_download_$episodeId"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        workManager.cancelUniqueWork(uniqueName)
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    private suspend fun seedHangingEpisode() {
        val now = System.currentTimeMillis()
        db.episodeDownloadDao().upsert(
            EpisodeDownloadEntity(
                episodeId = episodeId,
                sessionId = sessionId,
                episodeNumber = 1.0f,
                episodeSession = "eps-$episodeId",
                title = "Unique Work Episode",
                fansub = "TestFansub",
                audio = "sub",
                playUrl = "https://example.com/play",
                state = EpisodeDownloadEntity.STATE_QUEUED,
                createdAt = now,
                updatedAt = now,
                m3u8Url = Phase10TestSupport.HANGING_URL,
                userAgent = "TestUA",
                referer = "https://example.com",
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId)
            )
        )
    }

    @Test
    fun duplicate_enqueue_while_running_keeps_single_work_chain() {
        runBlocking {
            seedHangingEpisode()

            // Triple enqueue while nothing has run yet
            repository.enqueueWorker(episodeId)
            repository.enqueueWorker(episodeId)
            repository.enqueueWorker(episodeId)

            val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            assertEquals("KEEP must not create duplicate work entries", 1, infos.size)

            // Worker actually starts (real WorkerFactory instantiation)
            val started = Phase10TestSupport.awaitUntil(15_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }
            assertTrue("Worker must start RUNNING", started)

            // More duplicate enqueues while RUNNING — still one chain, still not replaced
            repository.enqueueWorker(episodeId)
            repository.enqueueWorker(episodeId)
            val infosWhileRunning = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            assertEquals(
                "KEEP while RUNNING must not enqueue additional work",
                1,
                infosWhileRunning.size
            )
            assertTrue(infosWhileRunning[0].state == WorkInfo.State.RUNNING)
        }
    }

    @Test
    fun enqueue_after_terminal_work_inserts_new_work() {
        runBlocking {
            seedHangingEpisode()

            repository.enqueueWorker(episodeId)
            val running = Phase10TestSupport.awaitUntil(15_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }
            assertTrue(running)

            // Force the existing chain to a terminal state (CANCELLED)
            workManager.cancelUniqueWork(uniqueName).result.get()
            val terminal = Phase10TestSupport.awaitUntil(10_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .all { it.state.isFinished }
            }
            assertTrue(terminal)
            val terminalCount = workManager.getWorkInfosForUniqueWork(uniqueName).get().size

            // KEEP inserts when the existing work is terminal (§6)
            repository.enqueueWorker(episodeId)
            val after = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            assertTrue(
                "New work must be inserted after terminal state",
                after.size > terminalCount || after.any { !it.state.isFinished }
            )
            assertTrue(
                "A non-finished work request must exist after re-enqueue",
                after.any { !it.state.isFinished }
            )
        }
    }
}
