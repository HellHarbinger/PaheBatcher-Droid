package com.pahebatcher.app.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Process death drops pending while
 * submitted rows persist; re-running the selection enqueues nothing twice.
 *
 * Honest scope: an instrumentation process cannot kill ITSELF mid-test, so
 * the observable half of process death is reproduced exactly — the batch's
 * ViewModel is destroyed ([ViewModelStore.clear] runs `onCleared`, which
 * cancels `viewModelScope` the same way process teardown does) while every
 * PERSISTED thing (Room rows) stays. The submitted half's reconstruction by
 * WorkManager + the startup reconciler is covered by the existing
 * `ProcessDeathRecoveryTest` / `DownloadStateReconcilerTest` suites; this
 * test proves the batch-side contract on top of that:
 *
 * 1. 1 of 2 numbers is submitted (real Room row, QUEUED), the other is held
 *    mid-resolution;
 * 2. ViewModel death: the pending number is dropped — after death its
 *    resolution is RELEASED, and a leaked coroutine would be caught by the
 *    "no row for the pending number" assertion below;
 * 3. the submitted row survives verbatim (state QUEUED — exactly the state
 *    the startup reconciler re-enqueues from);
 * 4. a FRESH ViewModel over the same database re-runs the same selection:
 *    the handled episode is skipped before any resolution (row read), no
 *    duplicate row exists, and only the pending number is newly queued.
 *
 * The queue writes REAL Room rows (the production persistence shape) but
 * does not enqueue WorkManager work — WorkManager/resume behavior belongs to
 * the suites cited above, not to this test.
 */
@RunWith(AndroidJUnit4::class)
class BatchPersistenceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var database: PaheDatabase
    private lateinit var episodeDao: EpisodeDownloadDao
    private lateinit var sessionDao: DownloadSessionDao
    private lateinit var resolver: ScriptedResolver

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * Submission target with production persistence semantics: parent session
     * row + episode row in REAL Room, and `isEpisodeAlreadyHandled` reading
     * that row — the same seam `DownloadRepository` implements (minus the
     * WorkManager half, which the cited suites own).
     */
    private inner class RoomBackedQueue : EpisodeDownloadQueue {

        private fun keyOf(animeSession: String, episode: EpisodeInfo): String =
            "${animeSession}_${episode.number}_${episode.audio}"

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            val now = System.currentTimeMillis()
            // Production `ensureSession` semantics (DownloadRepository.kt): a
            // parent row is INSERTed only when absent and refreshed in place
            // otherwise — `upsert` is INSERT OR REPLACE, which would delete the
            // conflicting parent and fire ON DELETE CASCADE on its episode rows
            // (the exact hazard the DAO KDoc documents).
            val existing = sessionDao.getById(submission.animeSession)
            val session = DownloadSessionEntity(
                sessionId = submission.animeSession,
                title = submission.animeTitle,
                animeSession = submission.animeSession,
                host = submission.host,
                quality = submission.quality,
                audioLang = "sub",
                outputDir = "/downloads",
                cacheDir = "/cache",
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                state = existing?.state ?: DownloadSessionEntity.STATE_CREATED,
            )
            if (existing == null) {
                sessionDao.insertIfAbsent(session)
            } else {
                sessionDao.update(session)
            }
            episodeDao.upsert(
                EpisodeDownloadEntity(
                    episodeId = keyOf(submission.animeSession, submission.episode),
                    sessionId = submission.animeSession,
                    episodeNumber = submission.episode.number,
                    episodeSession = submission.episode.session,
                    title = submission.episode.title,
                    fansub = submission.episode.fansub,
                    audio = submission.episode.audio,
                    playUrl = submission.episode.playUrl,
                    state = EpisodeDownloadEntity.STATE_QUEUED,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return EnqueueOutcome.Accepted
        }

        override suspend fun isEpisodeAlreadyHandled(
            animeSession: String,
            episode: EpisodeInfo,
        ): Boolean = episodeDao.getById(keyOf(animeSession, episode)) != null
    }

    private fun newViewModel(queue: EpisodeDownloadQueue): AnimeDetailsViewModel =
        AnimeDetailsViewModel(
            episodeRepository = EpisodeRepository(
                animeRepository = AnimeRepository(ScriptedHttpClient()),
                scanCacheRepository = ScanCacheRepository(EmptyScanCacheDao()),
            ),
            streamResolver = resolver,
            downloadQueue = queue,
            configProvider = { AppContext.defaults() },
        )

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        episodeDao = database.episodeDownloadDao()
        sessionDao = database.downloadSessionDao()
        resolver = ScriptedResolver()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun processDeathDropsPendingWhileSubmittedRowsPersist_andRerunEnqueuesNothingTwice() {
        val queue = RoomBackedQueue()

        // --- run ONE: partial submission, then ViewModel death -------------
        val store = ViewModelStore()
        val vm1 = ViewModelProvider(store, factoryFor(queue))
            .get(BATCH_KEY, AnimeDetailsViewModel::class.java)

        runBlocking { vm1.load(SESSION) }
        awaitCondition("the fixture scan produced the listing") {
            vm1.uiState.value.episodes.size == 3
        }
        vm1.setBatchMode(true)
        vm1.selectAllEpisodes()
        vm1.startBatchDownload()
        awaitCondition("the first number is submitted (real Room row)") {
            runBlocking { episodeDao.getBySessionId(SESSION).first() }.size == 1
        }

        // ViewModel death = what process teardown does to the batch holder:
        // the scope (and the loop) dies; Room does not.
        store.clear()

        // Release the held resolution: a coroutine that SURVIVED death would
        // now resolve and insert a second row — the assertion below catches it.
        resolver.holdEp2 = false
        Thread.sleep(500L)

        val afterDeath = runBlocking { episodeDao.getBySessionId(SESSION).first() }
        assertEquals("exactly the submitted row survives", 1, afterDeath.size)
        val submitted = afterDeath.single()
        assertEquals("ep1 is the submitted number", 1f, submitted.episodeNumber)
        assertEquals(
            "persisted in the state the startup reconciler re-enqueues from",
            EpisodeDownloadEntity.STATE_QUEUED,
            submitted.state,
        )
        assertNull(
            "the pending number was dropped — no row, no leak",
            runBlocking { episodeDao.getById("${SESSION}_2.0_jpn") },
        )

        // --- run TWO: fresh ViewModel over the same database --------------
        resolver.holdEp2 = true // deterministic partial shape again
        val vm2 = ViewModelProvider(ViewModelStore(), factoryFor(RoomBackedQueue()))
            .get(BATCH_KEY, AnimeDetailsViewModel::class.java)
        runBlocking { vm2.load(SESSION) }
        awaitCondition("the fresh ViewModel sees the listing") {
            vm2.uiState.value.episodes.size == 3
        }
        vm2.setBatchMode(true)
        vm2.selectAllEpisodes()
        vm2.startBatchDownload()
        resolver.holdEp2 = false
        awaitCondition("the re-run finishes") {
            val s = vm2.uiState.value
            !s.batchRunning && s.batchFinished
        }

        val final = vm2.uiState.value
        assertEquals(
            "the handled episode was skipped before any resolution",
            1,
            final.batchSkipped,
        )
        assertEquals(1, final.batchQueued) // only the previously-pending number
        assertEquals(0, final.batchFailed)

        val rows = runBlocking { episodeDao.getBySessionId(SESSION).first() }
        assertEquals(
            "re-running enqueued nothing twice — one row per number",
            2,
            rows.size,
        )
        assertEquals(
            "the original submitted row was never duplicated",
            1,
            rows.count { it.episodeNumber == 1f },
        )
        assertEquals(1, rows.count { it.episodeNumber == 2f })
    }

    private fun factoryFor(queue: EpisodeDownloadQueue) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AnimeDetailsViewModel::class.java))
            return newViewModel(queue) as T
        }
    }

    private companion object {
        const val BATCH_KEY = "batch-t3"
        const val SESSION = "sess-batch"
    }
}
