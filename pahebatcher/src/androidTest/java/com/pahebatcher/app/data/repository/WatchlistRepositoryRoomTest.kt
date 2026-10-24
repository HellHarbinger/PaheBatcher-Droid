package com.pahebatcher.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import kotlinx.coroutines.flow.first
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

/**
 * [WatchlistRepository] against the REAL Room schema (in-memory
 * database, real DAOs, real `ORDER BY` and real foreign keys), complementing
 * the JVM suite which runs over a fake DAO.
 *
 * What only real Room can prove:
 *
 * 1. add / find / remove / re-add round trips are persisted and come back on a
 *    reload (the AGENTS §9 reconstruction contract);
 * 2. Python's preservation rules (`added_at`, downloaded history, real title)
 *    hold through the schema's real `INSERT OR REPLACE` upsert;
 * 3. display order (`addedAt DESC`, the DAO's SQL) and index resolution
 *    (insertion order, the repository's `addedAt ASC` projection) behave as
 *    documented on REAL rows — the display order comes from SQLite, the index
 *    order from `WatchlistRepository.find`;
 * 4. the watchlist is INDEPENDENT of the download tables: deleting a session
 *    cascades its episodes but must never touch a watchlist row (and vice
 *    versa) — no foreign key joins them, which is exactly the Python
 *    watchlist.json / session.json separation.
 */
@RunWith(AndroidJUnit4::class)
class WatchlistRepositoryRoomTest {

    private lateinit var db: PaheDatabase
    private lateinit var repository: WatchlistRepository

    @Before
    fun setUp() {
        db = PaheDatabase.getInMemoryDatabase(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        repository = WatchlistRepository(db.watchlistDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(
        sessionId: String = SESSION_A,
        title: String = "Alpha Anime",
        addedAt: Long = 1_000L,
        lastCheckedAt: Long = 0L,
        downloadedJson: String = "[]",
    ) = WatchlistEntity(
        sessionId = sessionId,
        url = "https://animepahe.local/anime/$sessionId",
        animeSession = sessionId,
        host = "animepahe.local",
        title = title,
        quality = 1080,
        audioLang = "jpn",
        outputDir = "./downloads",
        maxParallel = 2,
        hlsWorkers = 24,
        keepTemp = false,
        autoRetry = 2,
        addedAt = addedAt,
        lastCheckedAt = lastCheckedAt,
        downloadedEpisodesJson = downloadedJson,
    )

    private fun session(sessionId: String) = DownloadSessionEntity(
        sessionId = sessionId,
        title = "Room Session",
        animeSession = "uuid-$sessionId",
        host = "animepahe.local",
        quality = 1080,
        audioLang = "jpn",
        outputDir = "./downloads",
        cacheDir = "pahe_cache",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        state = DownloadSessionEntity.STATE_CREATED,
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
    private fun reloadedRepository() = WatchlistRepository(db.watchlistDao())

    @Test
    fun addFindRemoveRoundTripSurvivesAReload() = runBlocking {
        val added = repository.addOrUpdate(entry())
        assertTrue("a brand-new session is a new entry", added.isNew)

        // Reload: a fresh repository reading the same table.
        val reloaded = reloadedRepository()
        assertTrue(reloaded.exists(SESSION_A))
        assertEquals("Alpha Anime", reloaded.get(SESSION_A)!!.title)
        assertEquals(1, reloaded.count())

        val found = reloaded.find("alpha anime")
        assertNotNull("identifier resolution works on persisted rows", found)
        assertEquals(SESSION_A, found!!.value.sessionId)

        val removed = reloaded.remove("alpha anime")
        assertEquals(SESSION_A, removed!!.sessionId)
        assertFalse(reloadedRepository().exists(SESSION_A))
        assertEquals(0, reloadedRepository().count())
    }

    @Test
    fun duplicateAddKeepsInsertionTimeHistoryAndRealTitleInRealRoom() = runBlocking {
        repository.addOrUpdate(entry(addedAt = 1_000L, downloadedJson = "[1.0,2.0]"))
        repository.markChecked(SESSION_A, timestamp = 5_000L)

        // Caller re-submits: placeholder title, empty history, new addedAt.
        val reSubmitted = entry(
            title = SESSION_A,
            addedAt = 9_999L,
            lastCheckedAt = 0L,
            downloadedJson = "[]",
        )
        val result = repository.addOrUpdate(reSubmitted)

        assertFalse("an existing session must not be reported as new", result.isNew)
        val stored = reloadedRepository().get(SESSION_A)!!
        assertEquals("added_at survives the real REPLACE upsert", 1_000L, stored.addedAt)
        assertEquals("downloaded history survives", "[1.0,2.0]", stored.downloadedEpisodesJson)
        assertEquals("the real title beats the session-UUID placeholder", "Alpha Anime", stored.title)
        assertEquals(
            "Python add_entry() rebuilds the entry with last_checked=None — parity: it resets",
            0L,
            stored.lastCheckedAt,
        )
    }

    @Test
    fun reAddAfterRemovalIsANewEntryAgain() = runBlocking {
        repository.addOrUpdate(entry(addedAt = 1_000L))
        repository.remove(SESSION_A)
        assertFalse(reloadedRepository().exists(SESSION_A))

        val reAdded = reloadedRepository().addOrUpdate(entry(addedAt = 4_000L))
        assertTrue("after removal the session is new again", reAdded.isNew)
        assertEquals(4_000L, reloadedRepository().get(SESSION_A)!!.addedAt)
    }

    @Test
    fun displayOrderIsNewestFirstWhileIndexResolutionUsesInsertionOrder() = runBlocking {
        repository.addOrUpdate(entry(sessionId = SESSION_A, title = "Oldest", addedAt = 1_000L))
        repository.addOrUpdate(entry(sessionId = SESSION_B, title = "Newest", addedAt = 9_000L))

        val display = reloadedRepository().observe().first()
        assertEquals(
            "the real ORDER BY addedAt DESC drives display",
            listOf(SESSION_B, SESSION_A),
            display.map { it.sessionId },
        )

        val byIndex = repository.find("1")
        assertEquals(
            "Python index 1 = first appended = oldest entry, even though display is newest-first",
            SESSION_A,
            byIndex!!.value.sessionId,
        )
        assertEquals(0, byIndex.index)

        // Removal resolves through the same insertion order.
        assertEquals(SESSION_A, repository.remove("1")!!.sessionId)
        assertEquals(listOf(SESSION_B), reloadedRepository().observe().first().map { it.sessionId })
        assertEquals(SESSION_B, repository.find("1")!!.value.sessionId)
        assertNull("index past the end resolves to nothing", repository.find("2"))
    }

    @Test
    fun checkedTimestampAndDownloadedHistoryPersistAcrossReload() = runBlocking {
        repository.addOrUpdate(entry())
        assertTrue("never checked = stale", repository.isStale(SESSION_A, 60)!!)

        val now = System.currentTimeMillis()
        repository.markChecked(SESSION_A, timestamp = now)
        repository.setDownloadedEpisodes(SESSION_A, listOf(1.0, 2.0, 13.5))

        val reloaded = reloadedRepository()
        assertFalse("freshly checked entry is within the TTL", reloaded.isStale(SESSION_A, 60)!!)
        assertEquals(listOf(1.0, 2.0, 13.5), reloaded.downloadedEpisodes(SESSION_A))
        assertEquals("[1.0,2.0,13.5]", reloaded.get(SESSION_A)!!.downloadedEpisodesJson)
    }

    @Test
    fun theWatchlistIsIndependentOfTheDownloadTables() = runBlocking {
        // Real foreign key in force: episodes require their session row first.
        db.downloadSessionDao().insertIfAbsent(session(SESSION_A))
        db.episodeDownloadDao().upsert(episode("ep-a1", SESSION_A))
        repository.addOrUpdate(entry(sessionId = SESSION_A))

        // 1. Deleting the session cascades its episodes (download state) but
        //    must never touch the watchlist row.
        db.downloadSessionDao().delete(SESSION_A)
        assertNull("episodes cascade with their session", db.episodeDownloadDao().getById("ep-a1"))
        assertTrue(
            "the watchlist entry survives its session's removal (Python keeps " +
                "watchlist.json entries independently of session folders)",
            repository.exists(SESSION_A),
        )

        // 2. Restoring the session + episode, then removing the watchlist entry
        //    must leave download state untouched.
        db.downloadSessionDao().insertIfAbsent(session(SESSION_A))
        db.episodeDownloadDao().upsert(episode("ep-a1", SESSION_A))
        repository.remove(SESSION_A)

        assertFalse(reloadedRepository().exists(SESSION_A))
        assertNotNull("the session row is untouched by a watchlist removal", db.downloadSessionDao().getById(SESSION_A))
        assertNotNull(
            "download state is untouched by a watchlist removal",
            db.episodeDownloadDao().getById("ep-a1"),
        )
    }

    private companion object {
        const val SESSION_A = "wl-room-a"
        const val SESSION_B = "wl-room-b"
    }
}
