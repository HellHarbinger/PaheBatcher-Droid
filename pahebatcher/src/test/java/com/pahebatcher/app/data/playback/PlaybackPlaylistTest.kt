package com.pahebatcher.app.data.playback

import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §36 unit tests for PlaybackPlaylist (PHASE11_ARCHITECTURE_V2 §23/§24.2):
 * COMPLETED-only, source-required, episodeNumber-ascending ordering — the exact
 * playlist Next/Previous walks in the player.
 */
class PlaybackPlaylistTest {

    private fun row(
        episodeId: String,
        episodeNumber: Float,
        state: String = EpisodeDownloadEntity.STATE_COMPLETED,
        outputPath: String? = "/files/$episodeId.mp4",
        audio: String = "sub",
    ): EpisodeDownloadEntity = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = "s-1",
        episodeNumber = episodeNumber,
        episodeSession = "eps-$episodeId",
        title = "Test Anime",
        fansub = "fansub",
        audio = audio,
        playUrl = "https://animepahe.local/play/$episodeId",
        state = state,
        outputPath = outputPath,
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    @Test
    fun `only COMPLETED rows are playable`() {
        val rows = listOf(
            row("e1", 1f, state = EpisodeDownloadEntity.STATE_COMPLETED),
            row("e2", 2f, state = EpisodeDownloadEntity.STATE_DOWNLOADING),
            row("e3", 3f, state = EpisodeDownloadEntity.STATE_FAILED),
            row("e4", 4f, state = EpisodeDownloadEntity.STATE_CANCELLED),
            row("e5", 5f, state = EpisodeDownloadEntity.STATE_QUEUED),
        )
        assertEquals(
            "player never includes in-flight/failed/cancelled siblings",
            listOf("e1"),
            PlaybackPlaylist.completedPlayable(rows).map { it.episodeId },
        )
    }

    @Test
    fun `rows without a resolvable source are excluded`() {
        val rows = listOf(
            row("e1", 1f, outputPath = "/files/e1.mp4"),
            row("e2", 2f, outputPath = null), // completed but sourceless
        )
        assertEquals(
            listOf("e1"),
            PlaybackPlaylist.completedPlayable(rows).map { it.episodeId },
        )
    }

    @Test
    fun `rows are ordered by episodeNumber ascending including floats`() {
        val rows = listOf(
            row("e10", 10f),
            row("e2", 2f),
            row("e5_5", 5.5f),
            row("e1", 1f),
        )
        assertEquals(
            listOf("e1", "e2", "e5_5", "e10"),
            PlaybackPlaylist.completedPlayable(rows).map { it.episodeId },
        )
    }

    @Test
    fun `empty input yields empty playlist`() {
        assertTrue(PlaybackPlaylist.completedPlayable(emptyList()).isEmpty())
    }

    @Test
    fun `source preference flows through playlist filtering`() {
        val rows = listOf(
            row("e1", 1f, outputPath = null).copy(
                outputUri = "content://media/external/video/1",
            ),
        )
        assertEquals(
            listOf("e1"),
            PlaybackPlaylist.completedPlayable(rows).map { it.episodeId },
        )
    }

    // -----------------------------------------------------------------------
    // Seed-audio narrowing — audio-variant slice (SPEC §15/§22): a local
    // playlist seeded by an episode row contains only that row's own audio.
    // -----------------------------------------------------------------------

    @Test
    fun `the seed audio narrows the playlist to the seed's own audio`() {
        val rows = listOf(
            row("e1-jpn", 1f, audio = "jpn"),
            row("e1-eng", 1f, audio = "eng"),
            row("e2-jpn", 2f, audio = "jpn"),
            row("e2-eng", 2f, audio = "eng"),
        )

        assertEquals(
            "a SUB seed walks only SUB rows, in episode order",
            listOf("e1-jpn", "e2-jpn"),
            PlaybackPlaylist.completedPlayable(rows, "jpn").map { it.episodeId },
        )
        assertEquals(
            "a DUB seed walks only DUB rows",
            listOf("e1-eng", "e2-eng"),
            PlaybackPlaylist.completedPlayable(rows, "eng").map { it.episodeId },
        )
    }

    @Test
    fun `the seed filter applies the same COMPLETED and source rules first`() {
        val rows = listOf(
            row("e1-jpn", 1f, audio = "jpn"),
            row("e2-jpn", 2f, audio = "jpn", state = EpisodeDownloadEntity.STATE_DOWNLOADING),
            row("e3-jpn", 3f, audio = "jpn", outputPath = null),
            row("e1-eng", 1f, audio = "eng"),
        )

        assertEquals(
            listOf("e1-jpn"),
            PlaybackPlaylist.completedPlayable(rows, "jpn").map { it.episodeId },
        )
    }

    @Test
    fun `a seed audio no row carries yields an empty playlist, never other rows`() {
        val rows = listOf(
            row("e1-jpn", 1f, audio = "jpn"),
            row("e1-eng", 1f, audio = "eng"),
        )

        assertTrue(PlaybackPlaylist.completedPlayable(rows, "kor").isEmpty())
        assertTrue(PlaybackPlaylist.completedPlayable(rows, "").isEmpty())
    }

    @Test
    fun `the single-argument playlist keeps every audio (download screen behavior unchanged)`() {
        val rows = listOf(
            row("e1-jpn", 1f, audio = "jpn"),
            row("e1-eng", 1f, audio = "eng"),
        )

        assertEquals(
            listOf("e1-jpn", "e1-eng"),
            PlaybackPlaylist.completedPlayable(rows).map { it.episodeId },
        )
    }
}
