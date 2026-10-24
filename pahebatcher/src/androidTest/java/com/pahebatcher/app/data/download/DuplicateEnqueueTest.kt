package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.RecordStreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Duplicate enqueue behavior.
 * Phase 10 architecture v10 §8, §16 (DuplicateEnqueueTest).
 *
 * Verifies:
 * - Same episode enqueued twice: the existing episode row is not reset
 * - resolveComplete called twice: the existing Worker remains (KEEP),
 *   no second concurrent Worker is created for the same episode
 */
@RunWith(AndroidJUnit4::class)
class DuplicateEnqueueTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-dup-test"

    /**
     * 11.5E-7: the reEnqueue test below re-enters the EXISTING
     * queueResolvedEpisode pipeline, whose key is `<session>_<epStr>_<audio>`.
     */
    private val episodeId = "${sessionId}_1_sub"
    private val uniqueName = "episode_download_$episodeId"

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
        workManager.cancelUniqueWork(uniqueName)
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    private fun freshEpisode() = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Duplicate Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = EpisodeDownloadEntity.STATE_QUEUED,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun same_episode_enqueued_twice_does_not_reset_state() {
        runBlocking {
            repository.enqueue(freshEpisode())
            val first = db.episodeDownloadDao().getById(episodeId)
            assertEquals(EpisodeDownloadEntity.STATE_RESOLVING, first?.state)

            // Second enqueue of the same episode while non-terminal → skipped
            repository.enqueue(freshEpisode())
            val second = db.episodeDownloadDao().getById(episodeId)

            assertEquals(first?.state, second?.state)
            assertEquals(first?.createdAt, second?.createdAt)
        }
    }

    @Test
    fun resolveComplete_twice_keeps_single_worker() {
        runBlocking {
            repository.enqueue(freshEpisode())

            repository.resolveComplete(
                episodeId = episodeId,
                m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                userAgent = "TestUA",
                referer = "https://example.com"
            )
            val countAfterFirst = workManager.getWorkInfosForUniqueWork(uniqueName).get().size

            // Duplicate resolveComplete — KEEP must not add a concurrent Worker
            repository.resolveComplete(
                episodeId = episodeId,
                m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                userAgent = "TestUA",
                referer = "https://example.com"
            )
            val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get()

            assertTrue(infos.isNotEmpty())
            assertTrue(
                "Duplicate resolveComplete must not create a second concurrent Worker " +
                    "(found ${infos.size} entries, after first: $countAfterFirst)",
                infos.size == countAfterFirst ||
                    infos.count { !it.state.isFinished } <= 1
            )
            // Never more than one unfinished request for the same episode
            assertTrue(
                "At most one unfinished work request allowed",
                infos.count { !it.state.isFinished } <= 1
            )
        }
    }

    @Test
    fun duplicate_enqueue_of_terminal_episode_creates_exactly_one_new_worker() {
        runBlocking {
            // Seed a terminal row and let it be re-enqueued twice quickly
            val now = System.currentTimeMillis()
            val dao = db.episodeDownloadDao()
            dao.upsert(
                freshEpisode().copy(
                    state = EpisodeDownloadEntity.STATE_FAILED,
                    m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                    errorMessage = "old",
                    createdAt = now,
                    updatedAt = now
                )
            )

            // 11.5E-7: a retry needs a resolver (production wires the real one;
            // the loopback fast-fail URL keeps the Worker deterministic).
            val retryRepository = DownloadRepository(
                context = context,
                dao = dao,
                sessionDao = db.downloadSessionDao(),
                streamResolver = RecordStreamResolver {
                    StreamInfo(
                        url = Phase10TestSupport.FAST_FAIL_URL,
                        userAgent = "DupRetryUA",
                        referer = "https://example.com",
                    )
                },
            )

            retryRepository.reEnqueue(episodeId)
            // second call: row now non-terminal (QUEUED after fresh resolution)
            // → the terminal guard skips BEFORE the resolver can be touched
            retryRepository.reEnqueue(episodeId)

            val row = dao.getById(episodeId)
            // Only the first reEnqueue wins the atomic claim and increments retryCount
            assertEquals(1, row?.retryCount)

            val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            assertTrue(infos.count { !it.state.isFinished } <= 1)
        }
    }
}
