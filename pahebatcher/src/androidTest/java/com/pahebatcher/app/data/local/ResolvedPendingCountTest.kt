package com.pahebatcher.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `EpisodeDownloadDao.countResolvedPending()` against the REAL Room
 * schema.
 *
 * This is the count [com.pahebatcher.app.domain.download.ResolveAheadGate] reads
 * as Android's `resolve_ahead` staging depth: how many episodes are RESOLVED
 * (stream persisted) but not yet started. Only the real DAO can prove that the
 * SQL predicate — `state = 'QUEUED' AND m3u8Url IS NOT NULL` — behaves exactly
 * like Python's staging queue, which only holds entries that already carry a
 * stream (`downloader.py:205-208`).
 *
 * The JVM suite cannot prove this: its fake DAO models the count, it does not
 * run SQL.
 */
@RunWith(AndroidJUnit4::class)
class ResolvedPendingCountTest {

    private lateinit var db: PaheDatabase

    @Before
    fun setUp() {
        db = PaheDatabase.getInMemoryDatabase(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session() = DownloadSessionEntity(
        sessionId = "sess-count",
        title = "Count Anime",
        animeSession = "sess-count",
        host = "animepahe.local",
        quality = 1080,
        audioLang = "jpn",
        outputDir = "./downloads",
        cacheDir = "pahe_cache",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        state = DownloadSessionEntity.STATE_CREATED,
    )

    private fun episode(
        episodeId: String,
        state: String,
        m3u8Url: String?,
        outputPath: String? = null,
    ) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = "sess-count",
        episodeNumber = 1f,
        episodeSession = "eps-$episodeId",
        title = "Episode $episodeId",
        fansub = "TestFansub",
        audio = "jpn",
        playUrl = "https://animepahe.local/play/sess-count/$episodeId",
        state = state,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        segmentDir = "/data/local/tmp/segments/$episodeId",
        m3u8Url = m3u8Url,
        outputPath = outputPath,
    )

    @Test
    fun onlyQueuedRowsWithAPersistedStreamCountAsStaged() = runBlocking {
        db.downloadSessionDao().insertIfAbsent(session())
        val dao = db.episodeDownloadDao()

        // Staged: resolved, stream persisted, Worker not started yet.
        dao.upsert(episode("ep-staged", EpisodeDownloadEntity.STATE_QUEUED, "https://cdn/x.m3u8"))
        // Queued but still awaiting its foreground resolution — nothing staged.
        dao.upsert(episode("ep-awaiting", EpisodeDownloadEntity.STATE_QUEUED, null))
        // Running with a stream — the Worker owns it, it is no longer staging.
        dao.upsert(episode("ep-running", EpisodeDownloadEntity.STATE_DOWNLOADING, "https://cdn/y.m3u8"))
        // Finished — never staging.
        dao.upsert(
            episode(
                "ep-done",
                EpisodeDownloadEntity.STATE_COMPLETED,
                "https://cdn/z.m3u8",
                outputPath = "/storage/emulated/0/Movies/PaheBatcher/done.mp4",
            ),
        )
        // A failure that never received a stream must not block the resolver.
        dao.upsert(episode("ep-failed", EpisodeDownloadEntity.STATE_FAILED, null))

        assertEquals(
            "only the resolved-and-not-started row is staged",
            1,
            dao.countResolvedPending(),
        )
    }

    @Test
    fun stagingEmptiesAsSoonAsTheWorkerPicksTheEpisodeUp() = runBlocking {
        db.downloadSessionDao().insertIfAbsent(session())
        val dao = db.episodeDownloadDao()
        dao.upsert(episode("ep-staged", EpisodeDownloadEntity.STATE_QUEUED, "https://cdn/x.m3u8"))
        assertEquals(1, dao.countResolvedPending())

        // The Worker transitions the row to RESOLVING/RUNNING — the gate must see
        // the freed capacity through the SAME query, not through local state.
        dao.updateState(
            "ep-staged",
            EpisodeDownloadEntity.STATE_RESOLVING,
            timestamp = 2_000L,
        )

        assertEquals(0, dao.countResolvedPending())
    }
}
