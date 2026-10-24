package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 11.5E-4 — the anime details entry hands ONE resolved episode to the REAL
 * download pipeline (no fakes on the persistence side):
 *
 * `AnimeDetailsViewModel.downloadResolvedEpisode` →
 * `DownloadRepository.queueResolvedEpisode` → parent session row → episode row →
 * persisted stream info → unique `DownloadWorker` work.
 *
 * The scan and the resolution use test-only collaborators (AGENTS §5 — mocks
 * never become production code); everything from the Room write onwards is the
 * production implementation, which is what proves:
 *
 *  - the foreign key is satisfied (the `download_sessions` row is created first
 *    — Room runs `PRAGMA foreign_keys = ON`, and no production code inserted
 *    that row before this phase);
 *  - the worker input is the RESOLVED stream: playlist URL, User-Agent and the
 *    KWIK page referrer (the Kwik CDN answers HTTP 403 for any other referrer);
 *  - exactly ONE episode is queued — no extra rows, no extra work.
 */
@RunWith(AndroidJUnit4::class)
class DetailsHandoffTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager
    private lateinit var repository: DownloadRepository

    /** Unique per run: the scan cache and the download rows are real Room data. */
    private val sessionId = "handoff-${System.nanoTime()}"
    private val episodeId = "${sessionId}_1_jpn"

    private val stream = StreamInfo(
        url = "https://cdn.example.invalid/uwu.m3u8?token=handoff",
        userAgent = "Mozilla/5.0 (test)",
        referer = "https://kwik.si/e/handoff",
        title = "Handoff Anime Episode 1 - AnimePahe",
        audio = "jpn",
        fansub = "SubsPlease",
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
        repository = DownloadRepository(
            context,
            db.episodeDownloadDao(),
            db.downloadSessionDao(),
        )
    }

    @After
    fun teardown() {
        workManager.cancelUniqueWork("episode_download_$episodeId")
        runBlocking {
            // Block until that cancellation has settled: this test deliberately
            // hands a real row to the real worker, and a worker still running when
            // the next suite starts is exactly the cross-test race Phase 10 had to
            // fix (F-G1). Then cascade-remove the rows (ON DELETE CASCADE).
            withTimeoutOrNull(15_000) {
                while (workManager.getWorkInfosForUniqueWork("episode_download_$episodeId")
                        .get().any { !it.state.isFinished }
                ) {
                    delay(100)
                }
            }
            db.downloadSessionDao().delete(sessionId)
        }
    }

    /** Room access outside a coroutine (the assertions run on the test thread). */
    private fun sessionRow(id: String = sessionId) = runBlocking { db.downloadSessionDao().getById(id) }

    private fun episodeRow(id: String) = runBlocking { db.episodeDownloadDao().getById(id) }

    @Test
    fun oneResolvedEpisodeReachesTheRealQueueWithTheRealWorkerInput() {
        val vm = AnimeDetailsViewModel(
            episodeRepository = EpisodeRepository(
                animeRepository = AnimeRepository(EpisodesJsonClient()),
                scanCacheRepository = ScanCacheRepository(db.scanCacheDao()),
            ),
            streamResolver = FixedStreamResolver(stream),
            downloadQueue = repository,
        )

        vm.load(sessionId)
        assertTrue(
            "the scan must return the real episodes",
            awaitCondition(15_000) { vm.uiState.value.episodes.isNotEmpty() },
        )
        assertNull(vm.uiState.value.error)

        vm.selectEpisode(vm.uiState.value.episodes.first())
        assertTrue(
            "the resolution must settle",
            awaitCondition(15_000) {
                val state = vm.uiState.value
                state.resolvedStream != null || state.resolutionError != null
            },
        )
        assertEquals("resolution failed: ${vm.uiState.value.resolutionError}", stream, vm.uiState.value.resolvedStream)

        vm.downloadResolvedEpisode()
        assertTrue(
            "the hand-off must settle (error: ${vm.uiState.value.enqueueError})",
            awaitCondition(15_000) {
                val state = vm.uiState.value
                state.enqueued || state.enqueueError != null
            },
        )
        assertTrue("queueing state must have cleared", awaitCondition(5_000) { !vm.uiState.value.enqueuing })
        assertNull(vm.uiState.value.enqueueError)
        assertTrue(vm.uiState.value.enqueued)

        // ── Parent session row (the foreign key the episode row needs) ──────
        val session = sessionRow()
        assertNotNull("the parent download_sessions row must exist", session)
        assertEquals("Handoff Anime", session!!.title)
        assertEquals(sessionId, session.animeSession)
        assertEquals("the resolved quality (11.5E-3 contract)", 1080, session.quality)
        assertEquals("jpn", session.audioLang)
        assertTrue("a real directory, never empty", session.outputDir.isNotBlank())
        assertTrue(session.cacheDir.isNotBlank())

        // ── Episode row: identity + state + the WORKER's input ───────────────
        val row = episodeRow(episodeId)
        assertNotNull("the episode row must exist under the documented id", row)
        assertEquals(sessionId, row!!.sessionId)
        assertEquals(1.0f, row.episodeNumber, 0.0f)
        assertEquals("ep1", row.episodeSession)
        assertEquals("jpn", row.audio)
        assertEquals("the EPISODE title, not the anime's", "Beginnings", row.title)
        assertTrue("the play page is kept for a later re-resolution", row.playUrl.isNotBlank())
        assertEquals(
            "the worker input is the RESOLVED playlist",
            stream.url,
            row.m3u8Url,
        )
        assertEquals("…with the resolver's User-Agent", stream.userAgent, row.userAgent)
        assertEquals(
            "…and the KWIK page as referrer (the CDN 403s anything else)",
            stream.referer,
            row.referer,
        )
        // The hand-off leaves the row WORKER-owned. It is `QUEUED` until WorkManager
        // picks the unique work up and whatever the running worker writes afterwards —
        // it must never still be `RESOLVING`, which would mean nobody will run it.
        // (This test's playlist URL is not resolvable, so a fast `FAILED` is a legal
        // terminal state here; the assertions above are the durable ones.)
        assertTrue(
            "worker-owned state, was ${row.state}",
            row.state in setOf(
                EpisodeDownloadEntity.STATE_QUEUED,
                EpisodeDownloadEntity.STATE_DOWNLOADING,
                EpisodeDownloadEntity.STATE_ASSEMBLING,
                EpisodeDownloadEntity.STATE_FAILED,
            ),
        )

        // ── Exactly one episode, exactly one unit of work ────────────────────
        assertNull(
            "no second episode may be queued by a single hand-off",
            episodeRow("${sessionId}_2_jpn"),
        )
        val work = workManager.getWorkInfosForUniqueWork("episode_download_$episodeId").get()
        assertEquals("exactly one unit of work for this episode", 1, work.size)
    }

    @Test
    fun queueingIsNotPossibleWithoutAResolvedStream() {
        val vm = AnimeDetailsViewModel(
            episodeRepository = EpisodeRepository(
                animeRepository = AnimeRepository(EpisodesJsonClient()),
                scanCacheRepository = ScanCacheRepository(db.scanCacheDao()),
            ),
            streamResolver = FixedStreamResolver(stream),
            downloadQueue = repository,
        )

        vm.load(sessionId)
        assertTrue(awaitCondition(15_000) { vm.uiState.value.episodes.isNotEmpty() })
        assertNull("a scan alone must not open a download session", sessionRow())

        // Nothing selected → nothing resolved → nothing may be handed over.
        // The call returns before any coroutine starts, so a DB check right here
        // is deterministic (a row without stream info would be a zombie the
        // worker could never process).
        vm.downloadResolvedEpisode()

        assertNull(episodeRow(episodeId))
        assertNull(sessionRow())
        assertFalse(vm.uiState.value.enqueued)
        assertFalse(vm.uiState.value.enqueuing)
        assertNull(vm.uiState.value.enqueueError)
    }
}

/** Test-only episode API: returns a two-episode page regardless of the URL. */
private class EpisodesJsonClient : HttpClient {
    override suspend fun fetchJson(url: String): String = """
        {"data": [
            {"episode": 1, "session": "ep1", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 2, "session": "ep2", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Handoff Anime"}}
    """.trimIndent()

    override suspend fun fetchHtml(url: String): String? = null
}

/** Test-only resolver: hands back one fixed, already-resolved stream. */
private class FixedStreamResolver(private val stream: StreamInfo) : StreamResolver {
    override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo = stream
}
