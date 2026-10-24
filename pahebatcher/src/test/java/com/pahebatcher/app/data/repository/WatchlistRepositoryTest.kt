package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.dao.WatchlistDao
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory [WatchlistDao] — the same fake-DAO pattern as ScanCacheRepositoryTest
 * (Room itself is covered by the schema/instrumentation suites).
 */
class FakeWatchlistDao : WatchlistDao {
    val rows = LinkedHashMap<String, WatchlistEntity>()

    override suspend fun getBySessionId(sessionId: String): WatchlistEntity? = rows[sessionId]

    override fun getAll(): Flow<List<WatchlistEntity>> = flowOf(rows.values.sortedByDescending { it.addedAt })

    override suspend fun upsert(entry: WatchlistEntity) {
        rows[entry.sessionId] = entry
    }

    override suspend fun updateLastChecked(sessionId: String, timestamp: Long) {
        rows[sessionId]?.let { rows[sessionId] = it.copy(lastCheckedAt = timestamp) }
    }

    override suspend fun updateDownloadedEpisodes(sessionId: String, downloadedJson: String) {
        rows[sessionId]?.let { rows[sessionId] = it.copy(downloadedEpisodesJson = downloadedJson) }
    }

    override suspend fun delete(sessionId: String) {
        rows.remove(sessionId)
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun count(): Int = rows.size
}

/**
 * Watchlist persistence semantics ported from Python
 * `WatchlistManager` / `WatchlistEntry` (`watchlist.py` 3.6.0).
 */
class WatchlistRepositoryTest {

    private lateinit var dao: FakeWatchlistDao
    private lateinit var repository: WatchlistRepository

    private val addedAtOld = 1_000L

    @Before
    fun setup() {
        dao = FakeWatchlistDao()
        repository = WatchlistRepository(dao)
    }

    private fun entry(
        sessionId: String = "sess-1",
        title: String = "Anime Title",
        url: String = "https://animepahe.pw/anime/$sessionId",
        addedAt: Long = addedAtOld,
        lastCheckedAt: Long = 0L,
        downloadedJson: String = "[]",
        quality: Int = 1080,
        audioLang: String = "jpn",
    ) = WatchlistEntity(
        sessionId = sessionId,
        url = url,
        animeSession = sessionId,
        host = "animepahe.pw",
        title = title,
        quality = quality,
        audioLang = audioLang,
        outputDir = "./downloads",
        maxParallel = 2,
        hlsWorkers = 24,
        keepTemp = false,
        autoRetry = 2,
        addedAt = addedAt,
        lastCheckedAt = lastCheckedAt,
        downloadedEpisodesJson = downloadedJson,
    )

    @Test
    fun `addOrUpdate inserts a new entry as new`() = runBlocking {
        val result = repository.addOrUpdate(entry())

        assertTrue(result.isNew)
        assertEquals(1, repository.count())
        assertEquals("Anime Title", repository.get("sess-1")?.title)
    }

    @Test
    fun `addOrUpdate keeps addedAt downloaded history and real title on update`() = runBlocking {
        repository.addOrUpdate(entry(addedAt = addedAtOld, downloadedJson = "[1.0,2.0]"))
        dao.updateLastChecked("sess-1", 5_000L)

        // Caller supplies a fresh entry: placeholder title, no history, new addedAt.
        val updated = entry(title = "sess-1", addedAt = 9_999L, downloadedJson = "[]", quality = 720)
        val result = repository.addOrUpdate(updated)

        assertFalse("existing session must not be reported as new", result.isNew)
        val stored = repository.get("sess-1")!!
        assertEquals("added_at is preserved", addedAtOld, stored.addedAt)
        assertEquals("downloaded history is preserved", "[1.0,2.0]", stored.downloadedEpisodesJson)
        assertEquals("real title beats the session-UUID placeholder", "Anime Title", stored.title)
        assertEquals("caller-supplied settings still apply", 720, stored.quality)
    }

    @Test
    fun `addOrUpdate keeps a caller title that is not the placeholder`() = runBlocking {
        repository.addOrUpdate(entry(title = "Old Title"))
        val result = repository.addOrUpdate(entry(title = "Renamed Title"))

        assertFalse(result.isNew)
        assertEquals("Renamed Title", repository.get("sess-1")?.title)
    }

    @Test
    fun `addOrUpdate lets an explicit downloaded list replace the old one`() = runBlocking {
        repository.addOrUpdate(entry(downloadedJson = "[1.0]"))
        val result = repository.addOrUpdate(entry(downloadedJson = "[1.0,2.0]"))

        assertFalse(result.isNew)
        assertEquals("[1.0,2.0]", repository.get("sess-1")?.downloadedEpisodesJson)
    }

    @Test
    fun `find resolves identifiers in python precedence order`() = runBlocking {
        repository.addOrUpdate(entry(sessionId = "sess-a", title = "Alpha Anime"))
        repository.addOrUpdate(entry(sessionId = "sess-b", title = "Beta Show"))

        // 1. 1-based numeric index over INSERTION order (oldest first = Python's
        // file order), not the newest-first display order.
        val byIndex = repository.find("1")
        assertNotNull(byIndex)
        assertEquals("Python index 1 = first appended = oldest entry", "sess-a", byIndex!!.value.sessionId)
        assertEquals(0, byIndex.index)

        // 2. full URL → its session UUID
        val byUrl = repository.find("https://animepahe.pw/anime/sess-b")
        assertNotNull(byUrl)
        assertEquals("sess-b", byUrl!!.value.sessionId)

        // 3. direct session UUID
        val bySession = repository.find("sess-a")
        assertNotNull(bySession)
        assertEquals("sess-a", bySession!!.value.sessionId)

        // 4. case-insensitive title substring
        val byTitle = repository.find("beta show")
        assertNotNull(byTitle)
        assertEquals("sess-b", byTitle!!.value.sessionId)

        assertNull("unknown identifier resolves to nothing", repository.find("nothing-matches"))
    }

    @Test
    fun `numeric index resolves against insertion order even when display order differs`() = runBlocking {
        // Distinct addedAt values: the newest entry is FIRST in the display list
        // (observe = addedAt DESC) but Python's index 1 is the OLDEST entry.
        repository.addOrUpdate(entry(sessionId = "old-one", title = "Oldest", addedAt = 1_000L))
        repository.addOrUpdate(entry(sessionId = "new-one", title = "Newest", addedAt = 9_000L))

        val display = repository.observe().first()
        assertEquals(
            "precondition: display list is newest-first",
            listOf("new-one", "old-one"),
            display.map { it.sessionId },
        )

        val byIndex = repository.find("1")
        assertEquals(
            "Python `entries[0]` is the first-appended (oldest) entry — not the newest",
            "old-one",
            byIndex!!.value.sessionId,
        )
        assertEquals("index is expressed in insertion order", 0, byIndex.index)

        // remove resolves through find: index 1 must delete the OLDEST entry.
        val removed = repository.remove("1")
        assertEquals("old-one", removed!!.sessionId)
        assertEquals("new-one", repository.observe().first().single().sessionId)

        // The survivor is now the FIRST (and only) entry in insertion order.
        assertEquals("new-one", repository.find("1")!!.value.sessionId)
        assertEquals(0, repository.find("1")!!.index)
        assertNull("index beyond the list resolves to nothing", repository.find("2"))
    }

    @Test
    fun `find returns null on an empty watchlist`() = runBlocking {
        assertNull(repository.find("1"))
        assertNull(repository.find("sess-1"))
    }

    @Test
    fun `remove deletes the resolved entry`() = runBlocking {
        repository.addOrUpdate(entry(sessionId = "sess-a", title = "Alpha"))
        repository.addOrUpdate(entry(sessionId = "sess-b", title = "Beta"))

        val removed = repository.remove("alpha")

        assertNotNull(removed)
        assertEquals("sess-a", removed!!.sessionId)
        assertEquals(1, repository.count())
        assertNull(repository.get("sess-a"))
        assertNotNull(repository.get("sess-b"))
    }

    @Test
    fun `remove of an unknown identifier deletes nothing`() = runBlocking {
        repository.addOrUpdate(entry())
        assertNull(repository.remove("nope"))
        assertEquals(1, repository.count())
    }

    @Test
    fun `markChecked records the timestamp used for staleness`() = runBlocking {
        repository.addOrUpdate(entry())
        // lastCheckedAt 0 → never checked → stale for any TTL.
        assertTrue("never-checked entry is stale", repository.isStale("sess-1", 60)!!)

        repository.markChecked("sess-1", timestamp = System.currentTimeMillis())
        assertFalse("just checked, within the 60 min TTL", repository.isStale("sess-1", 60)!!)

        // An entry last checked 3 hours ago is stale for a 60-minute TTL.
        dao.updateLastChecked("sess-1", System.currentTimeMillis() - (3 * 60 * 60 * 1000L))
        assertTrue(repository.isStale("sess-1", 60)!!)
        // TTL 0 → nothing is ever fresh (Python `cache_ttl = 0` disables caching).
        assertTrue(repository.isStale("sess-1", 0)!!)
    }

    @Test
    fun `setDownloadedEpisodes round-trips through storage`() = runBlocking {
        repository.addOrUpdate(entry())
        repository.setDownloadedEpisodes("sess-1", listOf(1.0, 2.0, 13.5))

        assertEquals(listOf(1.0, 2.0, 13.5), repository.downloadedEpisodes("sess-1"))
        assertEquals("[1.0,2.0,13.5]", repository.get("sess-1")?.downloadedEpisodesJson)
    }

    @Test
    fun `corrupted downloaded json decodes to an empty list not an exception`() {
        // Python `WatchlistEntry.from_dict` on invalid JSON: empty history.
        assertEquals(emptyList<Double>(), WatchlistRepository.decodeDownloaded("{not json"))
        assertEquals(emptyList<Double>(), WatchlistRepository.decodeDownloaded(""))
        // Non-numeric members are skipped individually, valid ones survive
        // (Python `float(v)` accepts numeric strings too).
        assertEquals(listOf(2.0, 3.0), WatchlistRepository.decodeDownloaded("[\"x\",2.0,true,\"3\"]"))
        assertEquals(true, WatchlistRepository.isDownloadedEmpty("[]"))
        assertEquals(false, WatchlistRepository.isDownloadedEmpty("[1.0]"))
    }

    @Test
    fun `deleteAll clears the watchlist`() = runBlocking {
        repository.addOrUpdate(entry(sessionId = "a"))
        repository.addOrUpdate(entry(sessionId = "b"))
        repository.deleteAll()
        assertEquals(0, repository.count())
    }
}

