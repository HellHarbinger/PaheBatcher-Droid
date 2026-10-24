package com.pahebatcher.app.data.stream

import com.pahebatcher.app.data.local.dao.ScanCacheDao
import com.pahebatcher.app.data.local.entity.ScanCacheEntity
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.EpisodeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17 §12/§20-C — the STREAM reconstruction ladder.
 *
 * Rung order is the contract: Room wins outright, the scan cache answers WITHOUT
 * the network, and the network scan is reached only when both persisted sources
 * miss. The TTL behaviour is asserted against the REAL `ScanCacheRepository`
 * (fake DAO, real serialiser, real expiry arithmetic) rather than a hand-rolled
 * stand-in, so "an expired cache is skipped" means the production check skipped
 * it — including the repository's own delete-on-expiry.
 */
class StreamReconstructionTest {

    private val animeSession = "5f2b7a10-1234-4abc-9def-001122334455"
    private val episodeId = "${animeSession}_1_jpn"

    private fun episode(
        number: Float = 1f,
        audio: String = EpisodeInfo.AUDIO_JAPANESE,
        playUrl: String,
    ) = EpisodeInfo(
        number = number,
        session = "ep-session-0000",
        title = "A Title",
        fansub = "SubsPlease",
        audio = audio,
        playUrl = playUrl,
    )

    private val parts = EpisodeId.parse(episodeId)!!

    /** Records which rung was reached, so "short-circuited" is observable. */
    private class Recorder(
        private val room: EpisodeInfo? = null,
        private val cached: EpisodeInfo? = null,
        private val network: EpisodeInfo? = null,
    ) {
        var roomCalls = 0
        var cacheCalls = 0
        var networkCalls = 0

        val reconstructor = StreamEpisodeReconstructor(
            roomEpisode = { _ -> roomCalls++; room },
            cachedEpisode = { _, _, _ -> cacheCalls++; cached },
            scannedEpisodes = { _ -> networkCalls++; network?.let { listOf(it) } ?: emptyList() },
        )
    }

    // ---------------------------------------------------------------- rung 1

    @Test
    fun `a Room row wins and neither the cache nor the network is consulted`() = runBlocking {
        val fromRoom = episode(playUrl = "https://animepahe.example/play/from-room")
        val recorder = Recorder(
            room = fromRoom,
            cached = episode(playUrl = "https://animepahe.example/play/from-cache"),
            network = episode(playUrl = "https://animepahe.example/play/from-network"),
        )

        val result = recorder.reconstructor.reconstruct(episodeId, parts)

        assertEquals(StreamEpisodeReconstructor.Result.Recovered(fromRoom), result)
        assertEquals(1, recorder.roomCalls)
        assertEquals(0, recorder.cacheCalls)
        assertEquals(0, recorder.networkCalls)
    }

    @Test
    fun `the scan cache answers without any network scan when Room has no row`() = runBlocking {
        val fromCache = episode(playUrl = "https://animepahe.example/play/from-cache")
        val recorder = Recorder(
            room = null,
            cached = fromCache,
            network = episode(playUrl = "https://animepahe.example/play/from-network"),
        )

        val result = recorder.reconstructor.reconstruct(episodeId, parts)

        assertEquals(StreamEpisodeReconstructor.Result.Recovered(fromCache), result)
        assertEquals(1, recorder.cacheCalls)
        assertEquals("network must not run when the cache answered", 0, recorder.networkCalls)
    }

    @Test
    fun `the network scan is the last resort and matches on episode string and audio`() = runBlocking {
        val wanted = episode(playUrl = "https://animepahe.example/play/wanted")
        val distractors = listOf(
            episode(number = 2f, playUrl = "https://animepahe.example/play/ep2"),
            episode(
                number = 1f,
                audio = EpisodeInfo.AUDIO_ENGLISH,
                playUrl = "https://animepahe.example/play/ep1-eng",
            ),
        )
        var scanned: List<EpisodeInfo> = emptyList()
        val reconstructor = StreamEpisodeReconstructor(
            roomEpisode = { null },
            cachedEpisode = { _, _, _ -> null },
            scannedEpisodes = { session ->
                assertEquals(animeSession, session)
                scanned = distractors + wanted
                scanned
            },
        )

        val result = reconstructor.reconstruct(episodeId, parts)

        assertEquals(StreamEpisodeReconstructor.Result.Recovered(wanted), result)
        assertEquals(3, scanned.size)
    }

    @Test
    fun `nothing recoverable is an honest failure with no invented url`() = runBlocking {
        val reconstructor = StreamEpisodeReconstructor(
            roomEpisode = { null },
            cachedEpisode = { _, _, _ -> null },
            scannedEpisodes = { emptyList() },
        )

        val result = reconstructor.reconstruct(episodeId, parts)

        assertEquals(StreamEpisodeReconstructor.Result.Unrecoverable, result)
    }

    // ------------------------------------------------- real cache TTL checks

    /** Minimal in-memory DAO: records upserts so the test can age an entry. */
    private class FakeScanCacheDao : ScanCacheDao {
        var stored: ScanCacheEntity? = null
        var deletedSessions = 0

        override suspend fun getBySession(session: String): ScanCacheEntity? =
            stored?.takeIf { it.session == session }

        override fun getAll(): Flow<List<ScanCacheEntity>> = flowOf(listOfNotNull(stored))

        override suspend fun upsert(cache: ScanCacheEntity) {
            stored = cache
        }

        override suspend fun deleteExpired(expiryTime: Long) {
            if (stored != null && stored!!.cachedAt < expiryTime) stored = null
        }

        override suspend fun delete(session: String) {
            if (stored?.session == session) {
                stored = null
                deletedSessions++
            }
        }

        override suspend fun deleteAll() {
            stored = null
        }

        override suspend fun count(): Int = if (stored == null) 0 else 1
    }

    private fun ladderOver(cacheRepository: ScanCacheRepository, ttl: Int = 60) =
        StreamEpisodeReconstructor(
            roomEpisode = { null },
            cachedEpisode = { session, episodeString, audio ->
                if (ttl <= 0) {
                    null
                } else {
                    cacheRepository.getCachedScan(session, ttl)
                        ?.episodes
                        ?.firstOrNull { it.epStr == episodeString && it.audio == audio }
                }
            },
            scannedEpisodes = { emptyList() },
        )

    @Test
    fun `an unexpired scan cache answers the reconstruction with no network`() = runBlocking {
        val dao = FakeScanCacheDao()
        val cacheRepository = ScanCacheRepository(dao)
        val cachedEpisode = episode(playUrl = "https://animepahe.example/play/from-cache")
        cacheRepository.saveScanCache(
            AnimeInfo(session = animeSession, title = "Title", host = "animepahe.example", episodes = listOf(cachedEpisode)),
            cacheTtlMinutes = 60,
        )

        val result = ladderOver(cacheRepository).reconstruct(episodeId, parts)

        assertEquals(StreamEpisodeReconstructor.Result.Recovered(cachedEpisode), result)
        assertEquals(0, dao.deletedSessions)
    }

    @Test
    fun `an expired scan cache is skipped and deleted exactly like production does`() = runBlocking {
        val dao = FakeScanCacheDao()
        val cacheRepository = ScanCacheRepository(dao)
        cacheRepository.saveScanCache(
            AnimeInfo(
                session = animeSession,
                title = "Title",
                host = "animepahe.example",
                episodes = listOf(episode(playUrl = "https://animepahe.example/play/from-cache")),
            ),
            cacheTtlMinutes = 60,
        )
        // Age the entry well past its 60-minute TTL using the repository's own
        // stored timestamp — the expiry arithmetic under test is the real one.
        dao.stored = dao.stored!!.copy(cachedAt = System.currentTimeMillis() - 3 * 60L * 60_000L)

        val result = ladderOver(cacheRepository).reconstruct(episodeId, parts)

        assertEquals(
            "an expired cache must fall through as unrecoverable (network rung returns nothing here)",
            StreamEpisodeReconstructor.Result.Unrecoverable,
            result,
        )
        assertEquals("the repository deletes what it expires", 1, dao.deletedSessions)
        assertNull(dao.stored)
    }

    @Test
    fun `a disabled cache ttl never reads the cache at all`() = runBlocking {
        val dao = FakeScanCacheDao()
        val cacheRepository = ScanCacheRepository(dao)
        cacheRepository.saveScanCache(
            AnimeInfo(
                session = animeSession,
                title = "Title",
                host = "animepahe.example",
                episodes = listOf(episode(playUrl = "https://animepahe.example/play/from-cache")),
            ),
            cacheTtlMinutes = 60,
        )

        val result = ladderOver(cacheRepository, ttl = 0).reconstruct(episodeId, parts)

        assertEquals(StreamEpisodeReconstructor.Result.Unrecoverable, result)
        assertTrue("cache must not be read when caching is off", dao.deletedSessions == 0)
        assertNotNull(dao.stored)
    }
}
