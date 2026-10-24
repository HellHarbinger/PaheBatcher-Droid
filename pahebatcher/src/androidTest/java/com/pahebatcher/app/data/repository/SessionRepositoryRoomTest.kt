package com.pahebatcher.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SessionRepository] against the REAL Room schema (in-memory
 * database, real DAOs, real foreign keys), complementing the JVM suite which
 * runs over a fake DAO.
 *
 * What only real Room can prove (the JVM fake can only model it):
 *
 * 1. a FRESH repository instance over the same database reads the rows back
 *    from disk-backed state — the repositories hold no cache, which is the
 *    mechanism AGENTS §9 relies on (a new process re-creates the repository and
 *    finds the same state; the file reopen itself is covered by Room's own
 *    schema suites);
 * 2. `upsert` of an existing session does NOT fire the schema's
 *    `ON DELETE CASCADE` on `episode_downloads` (an `INSERT OR REPLACE` would),
 *    so a metadata refresh never destroys download state;
 * 3. deleting a session DOES cascade its own episodes and only its own —
 *    the Android equivalent of Python removing the session's cache folder;
 * 4. state transitions and `summaries()` episode counts are read back from
 *    disk-backed rows, not from in-process repository state.
 */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryRoomTest {

    private lateinit var db: PaheDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        db = PaheDatabase.getInMemoryDatabase(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        repository = SessionRepository(db.downloadSessionDao(), db.episodeDownloadDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session(
        sessionId: String = SESSION_A,
        title: String = "Alpha Anime",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        state: String = DownloadSessionEntity.STATE_CREATED,
    ) = DownloadSessionEntity(
        sessionId = sessionId,
        title = title,
        animeSession = "uuid-$sessionId",
        host = "animepahe.local",
        quality = 1080,
        audioLang = "jpn",
        outputDir = "./downloads",
        cacheDir = "pahe_cache",
        createdAt = createdAt,
        updatedAt = updatedAt,
        state = state,
    )

    private fun episode(episodeId: String, sessionId: String) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Episode $episodeId",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://animepahe.local/play/$sessionId/$episodeId",
        state = EpisodeDownloadEntity.STATE_COMPLETED,
        createdAt = 1_500L,
        updatedAt = 1_500L,
        segmentDir = "/data/local/tmp/segments/$episodeId",
    )

    /** A second repository over the same open database — a reload, not a cache. */
    private fun reloadedRepository() =
        SessionRepository(db.downloadSessionDao(), db.episodeDownloadDao())

    @Test
    fun sessionRowsAndSummariesSurviveAReload() = runBlocking {
        repository.upsert(session(updatedAt = 2_000L))
        db.episodeDownloadDao().upsert(episode("ep-1", SESSION_A))
        db.episodeDownloadDao().upsert(episode("ep-2", SESSION_A))

        // Reload: a fresh repository reading the same rows, exactly what a new
        // process does after the database is reopened.
        val reloaded = reloadedRepository()
        val stored = reloaded.get(SESSION_A)
        assertNotNull("the persisted session row must come back", stored)
        assertEquals("Alpha Anime", stored!!.title)
        assertEquals(DownloadSessionEntity.STATE_CREATED, stored.state)
        assertEquals(1, reloaded.count())

        val summary = reloaded.summary(SESSION_A)
        assertNotNull(summary)
        assertEquals("ep_count over REAL episode rows", 2, summary!!.episodeCount)
        assertEquals(
            "Python meta['url'] reconstruction",
            "https://animepahe.local/anime/uuid-$SESSION_A",
            summary.resumeUrl,
        )

        // Newest-first listing straight out of the real query.
        repository.upsert(session(sessionId = SESSION_B, title = "Beta Show", updatedAt = 9_000L))
        val listed = reloadedRepository().observe().first()
        assertEquals(
            "updatedAt DESC is enforced by Room, not by repository state",
            listOf(SESSION_B, SESSION_A),
            listed.map { it.sessionId },
        )
    }

    @Test
    fun metadataRefreshKeepsEveryEpisodeRowInTheRealSchema() = runBlocking {
        repository.upsert(session(createdAt = 1_000L, updatedAt = 1_000L))
        db.episodeDownloadDao().upsert(episode("ep-a1", SESSION_A))
        db.episodeDownloadDao().upsert(episode("ep-a2", SESSION_A))

        // The production refresh path: upsert() routes an existing row through
        // @Update, never through INSERT OR REPLACE (which would cascade).
        repository.upsert(session(title = "Renamed Show", createdAt = 7_777L, updatedAt = 5_000L))

        val refreshed = reloadedRepository().get(SESSION_A)!!
        assertEquals("title refresh applies", "Renamed Show", refreshed.title)
        assertEquals("createdAt is never reset", 1_000L, refreshed.createdAt)
        assertEquals(5_000L, refreshed.updatedAt)

        val episodes = db.episodeDownloadDao()
        assertNotNull("episode 1 survives the refresh", episodes.getById("ep-a1"))
        assertNotNull("episode 2 survives the refresh", episodes.getById("ep-a2"))
        assertEquals(
            "completed state and output are download state (AGENTS §9)",
            EpisodeDownloadEntity.STATE_COMPLETED,
            episodes.getById("ep-a1")!!.state,
        )
    }

    @Test
    fun deletingASessionCascadesOnlyItsOwnEpisodes() = runBlocking {
        repository.upsert(session(sessionId = SESSION_A))
        repository.upsert(session(sessionId = SESSION_B, updatedAt = 2_000L))
        db.episodeDownloadDao().upsert(episode("ep-a1", SESSION_A))
        db.episodeDownloadDao().upsert(episode("ep-a2", SESSION_A))
        db.episodeDownloadDao().upsert(episode("ep-b1", SESSION_B))

        repository.remove(SESSION_A)

        assertNull("the session row is gone", reloadedRepository().get(SESSION_A))
        assertNotNull("the other session survives", reloadedRepository().get(SESSION_B))
        val episodes = db.episodeDownloadDao()
        assertNull("its episodes cascade away", episodes.getById("ep-a1"))
        assertNull("its episodes cascade away", episodes.getById("ep-a2"))
        assertNotNull("the other session's episode is untouched", episodes.getById("ep-b1"))
        assertEquals(1, reloadedRepository().count())
    }

    @Test
    fun stateTransitionsPersistAndUnknownStatesAreRejected() = runBlocking {
        repository.upsert(session())

        repository.setState(SESSION_A, DownloadSessionEntity.STATE_DOWNLOADING)
        assertEquals(
            "the transition is read back from the database",
            DownloadSessionEntity.STATE_DOWNLOADING,
            reloadedRepository().get(SESSION_A)!!.state,
        )

        try {
            repository.setState(SESSION_A, "NOT_A_STATE")
            fail("an unknown state must be rejected, never persisted")
        } catch (expected: IllegalArgumentException) {
            assertEquals(
                "the rejected value left the persisted state untouched",
                DownloadSessionEntity.STATE_DOWNLOADING,
                reloadedRepository().get(SESSION_A)!!.state,
            )
        }

        repository.setState(SESSION_A, DownloadSessionEntity.STATE_COMPLETED)
        assertEquals(
            DownloadSessionEntity.STATE_COMPLETED,
            reloadedRepository().get(SESSION_A)!!.state,
        )
    }

    @Test
    fun removeAllClearsSessionsButNotTheWatchlist() = runBlocking {
        repository.upsert(session(sessionId = SESSION_A))
        repository.upsert(session(sessionId = SESSION_B, updatedAt = 2_000L))
        val watchlist = com.pahebatcher.app.data.repository.WatchlistRepository(db.watchlistDao())
        watchlist.addOrUpdate(
            com.pahebatcher.app.data.local.entity.WatchlistEntity(
                sessionId = SESSION_A,
                url = "https://animepahe.local/anime/$SESSION_A",
                animeSession = SESSION_A,
                host = "animepahe.local",
                title = "Alpha Anime",
                quality = 1080,
                audioLang = "jpn",
                outputDir = "./downloads",
                maxParallel = 2,
                hlsWorkers = 24,
                keepTemp = false,
                autoRetry = 2,
                addedAt = 1_000L,
                lastCheckedAt = 0L,
            ),
        )

        repository.removeAll()

        assertEquals(0, reloadedRepository().count())
        assertTrue(
            "the watchlist is a separate table — clearing sessions never empties it",
            watchlist.exists(SESSION_A),
        )
    }

    private companion object {
        const val SESSION_A = "sess-room-a"
        const val SESSION_B = "sess-room-b"
    }
}
