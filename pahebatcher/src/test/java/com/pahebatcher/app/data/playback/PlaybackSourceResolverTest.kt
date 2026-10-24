package com.pahebatcher.app.data.playback

import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §36 unit tests for PlaybackSourceResolver (PHASE11_ARCHITECTURE_V2 §25):
 * outputUri preferred → outputPath fallback → null when neither exists.
 * Blank values must fall through exactly like null (never a blank URI).
 */
class PlaybackSourceResolverTest {

    private fun episode(
        outputUri: String? = null,
        outputPath: String? = null,
    ): EpisodeDownloadEntity = EpisodeDownloadEntity(
        episodeId = "ep-1",
        sessionId = "s-1",
        episodeNumber = 1f,
        episodeSession = "eps-1",
        title = "Test Anime",
        fansub = "fansub",
        audio = "sub",
        playUrl = "https://animepahe.local/play/1",
        state = EpisodeDownloadEntity.STATE_COMPLETED,
        outputPath = outputPath,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        outputUri = outputUri,
    )

    @Test
    fun `outputUri is preferred over outputPath`() {
        val row = episode(
            outputUri = "content://media/external/video/42",
            outputPath = "/data/app/files/downloads/ep1.mp4",
        )
        assertEquals(
            "MediaStore URI must win once present (survives local cleanup)",
            "content://media/external/video/42",
            PlaybackSourceResolver.sourceFor(row),
        )
    }

    @Test
    fun `outputPath is used when outputUri is absent`() {
        val row = episode(outputPath = "/data/app/files/downloads/ep1.mp4")
        assertEquals(
            "/data/app/files/downloads/ep1.mp4",
            PlaybackSourceResolver.sourceFor(row),
        )
    }

    @Test
    fun `blank outputUri falls through to outputPath`() {
        val row = episode(outputUri = "   ", outputPath = "/data/app/files/downloads/ep1.mp4")
        assertEquals(
            "blank URI must not mask a real path",
            "/data/app/files/downloads/ep1.mp4",
            PlaybackSourceResolver.sourceFor(row),
        )
    }

    @Test
    fun `row with neither source resolves to null`() {
        assertNull("no fabricated source (AGENTS §5)", PlaybackSourceResolver.sourceFor(episode()))
    }

    @Test
    fun `row with only blank sources resolves to null`() {
        val row = episode(outputUri = "", outputPath = " ")
        assertNull(PlaybackSourceResolver.sourceFor(row))
    }
}
