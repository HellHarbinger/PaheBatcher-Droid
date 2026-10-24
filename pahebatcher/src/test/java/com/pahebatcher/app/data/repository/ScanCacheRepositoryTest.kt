package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.dao.ScanCacheDao
import com.pahebatcher.app.data.local.entity.ScanCacheEntity
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.EpisodeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Fake ScanCacheDao for testing that stores entries in memory.
 */
class FakeScanCacheDao : ScanCacheDao {
    private val entries = mutableMapOf<String, ScanCacheEntity>()

    override suspend fun getBySession(session: String): ScanCacheEntity? {
        return entries[session]
    }

    override fun getAll(): kotlinx.coroutines.flow.Flow<List<ScanCacheEntity>> {
        return kotlinx.coroutines.flow.flowOf(entries.values.toList())
    }

    override suspend fun upsert(cache: ScanCacheEntity) {
        entries[cache.session] = cache
    }

    override suspend fun deleteExpired(expiryTime: Long) {
        entries.values.removeAll { it.cachedAt < expiryTime }
    }

    override suspend fun delete(session: String) {
        entries.remove(session)
    }

    override suspend fun deleteAll() {
        entries.clear()
    }

    override suspend fun count(): Int {
        return entries.size
    }
}

class ScanCacheRepositoryTest {

    private lateinit var scanCacheDao: FakeScanCacheDao
    private lateinit var repository: ScanCacheRepository

    @Before
    fun setup() {
        scanCacheDao = FakeScanCacheDao()
        repository = ScanCacheRepository(scanCacheDao)
    }

    @Test
    fun `getCachedScan returns null when cache disabled`() = runBlocking {
        val result = repository.getCachedScan("session1", cacheTtlMinutes = 0)
        assertNull(result)
    }

    @Test
    fun `getCachedScan returns null when no cache exists`() = runBlocking {
        val result = repository.getCachedScan("session1", cacheTtlMinutes = 60)
        assertNull(result)
    }

    @Test
    fun `getCachedScan returns null when cache expired`() = runBlocking {
        val entity = ScanCacheEntity(
            session = "session1",
            title = "Test Anime",
            host = "animepahe.com",
            totalEpisodes = 12,
            episodesJson = "[]",
            cachedAt = System.currentTimeMillis() - (7200 * 1000L), // 2 hours ago
            cacheTtlMinutes = 60
        )
        scanCacheDao.upsert(entity)

        val result = repository.getCachedScan("session1", cacheTtlMinutes = 60)
        assertNull(result)
    }

    @Test
    fun `getCachedScan returns AnimeInfo when cache is valid`() = runBlocking {
        val episodesJson = """
            [{"number":1.0,"session":"ep1","title":"Episode 1","fansub":"Sub","audio":"jpn","play_url":"url1"}]
        """.trimIndent()

        val entity = ScanCacheEntity(
            session = "session1",
            title = "Test Anime",
            host = "animepahe.com",
            totalEpisodes = 1,
            episodesJson = episodesJson,
            cachedAt = System.currentTimeMillis(),
            cacheTtlMinutes = 60
        )
        scanCacheDao.upsert(entity)

        val result = repository.getCachedScan("session1", cacheTtlMinutes = 60)
        assertNotNull(result)
        assertEquals("session1", result?.session)
        assertEquals("Test Anime", result?.title)
        assertEquals(1, result?.episodes?.size)
    }

    @Test
    fun `saveScanCache saves entity correctly`() = runBlocking {
        val anime = AnimeInfo(
            session = "session1",
            title = "Test Anime",
            host = "animepahe.com",
            total = 1,
            episodes = listOf(
                EpisodeInfo(1f, "ep1", "Ep 1", "Sub", "jpn", "url1")
            )
        )

        repository.saveScanCache(anime, cacheTtlMinutes = 60)

        assertEquals(1, scanCacheDao.count())
        val saved = scanCacheDao.getBySession("session1")
        assertNotNull(saved)
        assertEquals("Test Anime", saved?.title)
    }

    @Test
    fun `deleteExpired deletes old entries`() = runBlocking {
        // Add an old entry
        val oldEntity = ScanCacheEntity(
            session = "old",
            title = "Old Anime",
            host = "animepahe.com",
            totalEpisodes = 1,
            episodesJson = "[]",
            cachedAt = System.currentTimeMillis() - (7200 * 1000L), // 2 hours ago
            cacheTtlMinutes = 60
        )
        scanCacheDao.upsert(oldEntity)

        // Add a fresh entry
        val freshEntity = ScanCacheEntity(
            session = "fresh",
            title = "Fresh Anime",
            host = "animepahe.com",
            totalEpisodes = 1,
            episodesJson = "[]",
            cachedAt = System.currentTimeMillis(),
            cacheTtlMinutes = 60
        )
        scanCacheDao.upsert(freshEntity)

        assertEquals(2, scanCacheDao.count())

        // Delete entries older than 1 hour
        val expiryTime = System.currentTimeMillis() - (60 * 60L * 1000L)
        scanCacheDao.deleteExpired(expiryTime)

        assertEquals(1, scanCacheDao.count())
        assertNull(scanCacheDao.getBySession("old"))
        assertNotNull(scanCacheDao.getBySession("fresh"))
    }

    @Test
    fun `clearAll deletes all entries`() = runBlocking {
        scanCacheDao.upsert(ScanCacheEntity("s1", "A", "h", 1, "[]", System.currentTimeMillis(), 60))
        scanCacheDao.upsert(ScanCacheEntity("s2", "B", "h", 1, "[]", System.currentTimeMillis(), 60))

        assertEquals(2, scanCacheDao.count())

        repository.clearAll()

        assertEquals(0, scanCacheDao.count())
    }

    @Test
    fun `getCachedScan with invalid JSON returns null`() = runBlocking {
        val entity = ScanCacheEntity(
            session = "session1",
            title = "Test Anime",
            host = "animepahe.com",
            totalEpisodes = 1,
            episodesJson = "invalid json",
            cachedAt = System.currentTimeMillis(),
            cacheTtlMinutes = 60
        )
        scanCacheDao.upsert(entity)

        val result = repository.getCachedScan("session1", cacheTtlMinutes = 60)
        assertNull(result)
    }
}
