package com.pahebatcher.app.data.playback

import com.pahebatcher.app.data.local.dao.PlaybackPositionDao
import com.pahebatcher.app.data.local.entity.PlaybackPositionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §36 unit tests for PlaybackPositionRepository (§29 upsert + read + negative
 * coercion + indefinite retention: no delete path exists on the DAO).
 */
class PlaybackPositionRepositoryTest {

    /** In-memory fake DAO implementing exactly the production interface. */
    private class FakeDao : PlaybackPositionDao {
        val rows = linkedMapOf<String, PlaybackPositionEntity>()

        override suspend fun getByEpisodeId(episodeId: String): PlaybackPositionEntity? =
            rows[episodeId]

        override suspend fun upsert(position: PlaybackPositionEntity) {
            rows[position.episodeId] = position
        }
    }

    @Test
    fun `get returns null for an episode that never played`() = runBlocking {
        val repo = PlaybackPositionRepository(FakeDao())
        assertNull(repo.getPosition("ep-unknown"))
    }

    @Test
    fun `save then get round-trips the position`() = runBlocking {
        val dao = FakeDao()
        val repo = PlaybackPositionRepository(dao)

        repo.save("ep-1", positionMs = 8_000L, durationMs = 60_000L)

        assertEquals(8_000L, repo.getPosition("ep-1"))
        assertEquals(60_000L, dao.rows["ep-1"]?.durationMs)
    }

    @Test
    fun `save overwrites an existing position (upsert, one row per episode)`() = runBlocking {
        val dao = FakeDao()
        val repo = PlaybackPositionRepository(dao)

        repo.save("ep-1", positionMs = 5_000L, durationMs = 60_000L)
        repo.save("ep-1", positionMs = 12_000L, durationMs = 60_000L)

        assertEquals(1, dao.rows.size)
        assertEquals(12_000L, repo.getPosition("ep-1"))
    }

    @Test
    fun `negative positions and durations are coerced to zero`() = runBlocking {
        val dao = FakeDao()
        val repo = PlaybackPositionRepository(dao)

        repo.save("ep-1", positionMs = -1L, durationMs = -999L)

        assertEquals(0L, repo.getPosition("ep-1"))
        assertEquals(0L, dao.rows["ep-1"]?.durationMs)
    }

    @Test
    fun `positions are retained indefinitely per episode id`() = runBlocking {
        val dao = FakeDao()
        val repo = PlaybackPositionRepository(dao)

        repo.save("ep-1", positionMs = 1_000L, durationMs = 60_000L)
        repo.save("ep-2", positionMs = 2_000L, durationMs = 60_000L)

        // No TTL: both rows remain readable (§29.1 indefinite retention).
        assertEquals(1_000L, repo.getPosition("ep-1"))
        assertEquals(2_000L, repo.getPosition("ep-2"))
        assertTrue(dao.rows.values.all { it.updatedAt > 0L })
    }
}
