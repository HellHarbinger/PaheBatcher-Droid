package com.pahebatcher.app.ui.sessions

import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure Sessions row builder.
 *
 * Proves:
 *
 * - every SPEC §20 display field is populated from the persisted rows;
 * - the 106 terminal-only gates flip exactly with the rows (delete and
 *   delete-temporary-cache disabled while ANY row is non-terminal, with the
 *   reason string present — the state is shown, never hidden);
 * - Cancel/Retry counts target exactly the row sets the DECISIONS 94 table
 *   names (non-terminal for Cancel; FAILED/CANCELLED for Retry);
 * - Resume targets the anime session UUID (DECISIONS 94 — navigation, not a
 *   state flip);
 * - an empty session derives CREATED with delete enabled (nothing to guard).
 */
class SessionRowBuildTest {

    private fun episode(
        state: String,
        id: String = "ep-$state",
        totalSegments: Int = 0,
        completedSegments: Int = 0,
    ) = EpisodeDownloadEntity(
        episodeId = id,
        sessionId = "sess-1",
        episodeNumber = 1.0f,
        episodeSession = "ep-sess-1",
        title = "Episode",
        fansub = "",
        audio = "sub",
        playUrl = "https://example.com/play",
        totalSegments = totalSegments,
        completedSegments = completedSegments,
        state = state,
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private val session = DownloadSessionEntity(
        sessionId = "sess-1",
        title = "Some Anime",
        animeSession = "anime-uuid-42",
        host = "animepahe.si",
        quality = 1080,
        audioLang = "sub",
        outputDir = "/storage/out",
        cacheDir = "/data/cache",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        state = DownloadSessionEntity.STATE_CREATED,
    )

    @Test
    fun `all SPEC 20 fields derive from the persisted rows`() {
        val rows = listOf(
            episode(EpisodeDownloadEntity.STATE_COMPLETED, id = "a", totalSegments = 10, completedSegments = 10),
            episode(EpisodeDownloadEntity.STATE_FAILED, id = "b", totalSegments = 10, completedSegments = 5),
            episode(EpisodeDownloadEntity.STATE_CANCELLED, id = "c", totalSegments = 4, completedSegments = 1),
        )
        val row = buildSessionRow(session, rows, cacheBytes = 123_456L)

        assertEquals("sess-1", row.sessionId)
        assertEquals("anime-uuid-42", row.resumeSession) // Resume target
        assertEquals("Some Anime", row.title)
        assertEquals(
            "https://animepahe.si/anime/anime-uuid-42",
            row.url,
        ) // SessionRepository.resumeUrlOf
        assertEquals("1080p", row.qualityLabel)
        assertEquals("sub", row.audioLang)
        assertEquals(3, row.episodeCount)
        assertEquals(1, row.completedEpisodes)
        assertEquals(2, row.incompleteEpisodes)
        assertEquals(16, row.completedSegments)
        assertEquals(24, row.totalSegments)
        assertEquals(66, row.segmentPercent) // 16/24 = 66.6 → 66
        assertEquals(123_456L, row.cacheBytes)
        // All-terminal, ≥1 FAILED → FAILED (rule 4).
        assertEquals(DownloadSessionEntity.STATE_FAILED, row.displayState)
    }

    @Test
    fun `terminal gates enabled only when every row is terminal`() {
        val terminalRows = listOf(
            episode(EpisodeDownloadEntity.STATE_COMPLETED, id = "a"),
            episode(EpisodeDownloadEntity.STATE_CANCELLED, id = "b"),
        )
        val open = buildSessionRow(session, terminalRows, 0L)
        assertTrue(open.deleteEnabled)
        assertNull(open.deleteDisabledReason)
        assertTrue(open.tempCacheEnabled)
        assertNull(open.tempCacheDisabledReason)

        val withActive = terminalRows + episode(EpisodeDownloadEntity.STATE_DOWNLOADING, id = "c")
        val blocked = buildSessionRow(session, withActive, 0L)
        assertFalse(blocked.deleteEnabled)
        assertNotNull(blocked.deleteDisabledReason)
        assertFalse(blocked.tempCacheEnabled)
        assertNotNull(blocked.tempCacheDisabledReason)
        // The disabled state carries its honest reason (DECISIONS 106).
        assertTrue(blocked.deleteDisabledReason!!.contains("Cancel first"))
    }

    @Test
    fun `cancel targets non-terminal rows and retry targets FAILED and CANCELLED`() {
        val rows = listOf(
            episode(EpisodeDownloadEntity.STATE_COMPLETED, id = "a"),
            episode(EpisodeDownloadEntity.STATE_FAILED, id = "b"),
            episode(EpisodeDownloadEntity.STATE_CANCELLED, id = "c"),
            episode(EpisodeDownloadEntity.STATE_QUEUED, id = "d"),
            episode(EpisodeDownloadEntity.STATE_ASSEMBLING, id = "e"),
        )
        val row = buildSessionRow(session, rows, 0L)

        // Cancel: QUEUED + ASSEMBLING (the two non-terminal rows).
        assertEquals(2, row.cancelCount)
        // Retry: FAILED + CANCELLED only — never COMPLETED (DECISIONS 94).
        assertEquals(2, row.retryCount)
    }

    @Test
    fun `cancel and retry are unavailable when nothing matches`() {
        val rows = listOf(episode(EpisodeDownloadEntity.STATE_COMPLETED, id = "a"))
        val row = buildSessionRow(session, rows, 0L)
        assertEquals(0, row.cancelCount)
        assertEquals(0, row.retryCount)
        assertTrue(row.deleteEnabled) // all terminal → deletable
    }

    @Test
    fun `pause counts only pausable rows and resume counts only paused rows`() {
        val rows = listOf(
            episode(EpisodeDownloadEntity.STATE_COMPLETED, id = "a"),
            episode(EpisodeDownloadEntity.STATE_FAILED, id = "b"),
            episode(EpisodeDownloadEntity.STATE_QUEUED, id = "c"),
            episode(EpisodeDownloadEntity.STATE_RESOLVING, id = "d"),
            episode(EpisodeDownloadEntity.STATE_DOWNLOADING, id = "e"),
            // ASSEMBLING is non-terminal but NOT pausable — muxing cannot stop.
            episode(EpisodeDownloadEntity.STATE_ASSEMBLING, id = "f"),
            episode(EpisodeDownloadEntity.STATE_PAUSED, id = "g"),
        )
        val row = buildSessionRow(session, rows, 0L)

        // Pause: QUEUED + RESOLVING + DOWNLOADING (PAUSED itself excluded).
        assertEquals(3, row.pauseCount)
        // Resume: the single PAUSED row — nothing else can be resumed.
        assertEquals(1, row.resumeCount)
    }

    @Test
    fun `pause and resume counts are zero for a session that never paused`() {
        val rows = listOf(
            episode(EpisodeDownloadEntity.STATE_COMPLETED, id = "a"),
            episode(EpisodeDownloadEntity.STATE_CANCELLED, id = "b"),
        )
        val row = buildSessionRow(session, rows, 0L)
        assertEquals(0, row.pauseCount)
        assertEquals(0, row.resumeCount)
    }

    @Test
    fun `empty session is CREATED with delete enabled and no action counts`() {
        val row = buildSessionRow(session, emptyList(), 0L)
        assertEquals(DownloadSessionEntity.STATE_CREATED, row.displayState)
        assertEquals(0, row.episodeCount)
        assertTrue(row.deleteEnabled)
        assertEquals(0, row.cancelCount)
        assertEquals(0, row.retryCount)
        assertNull(row.segmentPercent)
    }

    @Test
    fun `segment percent stays null while the denominator is unknown`() {
        val rows = listOf(
            episode(EpisodeDownloadEntity.STATE_DOWNLOADING, id = "a", totalSegments = 0),
        )
        val row = buildSessionRow(session, rows, 0L)
        assertNull(row.segmentPercent)
        assertEquals(DownloadSessionEntity.STATE_DOWNLOADING, row.displayState)
    }
}
