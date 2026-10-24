package com.pahebatcher.app.ui.player

import com.pahebatcher.app.data.stream.StreamEpisodeReconstructor
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.ui.navigation.PlayerMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17 §20-B — the branch decision of `PlayerViewModel.load`.
 *
 * [planPlayerLoad] is the exact function the player calls before touching any
 * source, so these assertions are about production routing, not a test-only
 * model of it:
 *
 * * `STREAM` enters the streaming branch (and only a well-formed id can);
 * * `DOWNLOAD` keeps the pre-existing local behaviour and provably never runs
 *   the reconstruction ladder — which is the only place a network scan can be
 *   reached from the player, so "no completed row" in DOWNLOAD mode can never
 *   turn into a STREAM network request.
 */
class StreamModeDeterminationTest {

    private val animeSession = "5f2b7a10-1234-4abc-9def-001122334455"
    private val streamId = "${animeSession}_1_jpn"

    private fun episode(
        number: Float = 1f,
        audio: String = EpisodeInfo.AUDIO_JAPANESE,
        playUrl: String = "https://animepahe.example/play/$animeSession/ep-1",
    ) = EpisodeInfo(
        number = number,
        session = "ep-session-0000",
        title = "A Title",
        fansub = "",
        audio = audio,
        playUrl = playUrl,
    )

    /** Records exactly which rung of the ladder the player asked for. */
    private class Recorder(
        private val room: EpisodeInfo? = null,
        private val cached: EpisodeInfo? = null,
        private val network: List<EpisodeInfo> = emptyList(),
    ) {
        var roomCalls = 0
        var cacheCalls = 0
        var networkCalls = 0

        val reconstructor = StreamEpisodeReconstructor(
            roomEpisode = { _ -> roomCalls++; room },
            cachedEpisode = { _, _, _ -> cacheCalls++; cached },
            scannedEpisodes = { _ -> networkCalls++; network },
        )

        fun untouched(): Boolean = roomCalls == 0 && cacheCalls == 0 && networkCalls == 0
    }

    /** A ladder whose network rung fails the test if it is ever reached. */
    private fun networkIsForbidden() = StreamEpisodeReconstructor(
        roomEpisode = { _ -> null },
        cachedEpisode = { _, _, _ -> null },
        scannedEpisodes = { _ -> throw AssertionError("network scan reached from a non-STREAM entry") },
    )

    @Test
    fun `STREAM enters the streaming branch with the reconstructed episode`() = runBlocking {
        val target = episode()
        val recorder = Recorder(room = target)

        val plan = planPlayerLoad(PlayerMode.STREAM, streamId, recorder.reconstructor)

        assertEquals(PlayerLoadPlan.Stream(target), plan)
        assertEquals(1, recorder.roomCalls)
    }

    @Test
    fun `DOWNLOAD keeps the existing local path`() = runBlocking {
        val recorder = Recorder(room = episode())

        val plan = planPlayerLoad(PlayerMode.DOWNLOAD, streamId, recorder.reconstructor)

        assertEquals(PlayerLoadPlan.Local(streamId), plan)
        assertTrue("ladder must not run for DOWNLOAD", recorder.untouched())
    }

    @Test
    fun `a completed download still uses local playback`() = runBlocking {
        // Even when a completed row exists AND a stream would be resolvable for
        // the same id, DOWNLOAD mode must return the local plan — streaming is
        // opt-in per navigation, never a side effect of having metadata.
        val recorder = Recorder(room = episode())

        val plan = planPlayerLoad(PlayerMode.DOWNLOAD, streamId, recorder.reconstructor)

        assertTrue("expected the local plan", plan is PlayerLoadPlan.Local)
        assertTrue(recorder.untouched())
    }

    @Test
    fun `DOWNLOAD with no completed row never reaches the stream network path`() = runBlocking {
        // `networkIsForbidden` throws if the ladder's scan rung runs at all, so
        // this fails loudly if a DOWNLOAD entry could ever start a stream scan.
        val plan = planPlayerLoad(PlayerMode.DOWNLOAD, streamId, networkIsForbidden())

        assertEquals(PlayerLoadPlan.Local(streamId), plan)
    }

    @Test
    fun `a malformed STREAM id fails locally and touches no source`() = runBlocking {
        val malformed = listOf("", "not-an-episode-id", "${animeSession}_1", "a_b_c_d")

        for (id in malformed) {
            val recorder = Recorder(room = episode())

            val plan = planPlayerLoad(PlayerMode.STREAM, id, recorder.reconstructor)

            assertEquals("id: <$id>", PlayerLoadPlan.Failed(STREAM_SESSION_EXPIRED), plan)
            assertTrue("ladder must not run for <$id>", recorder.untouched())
        }
    }

    @Test
    fun `an unrecoverable STREAM entry reports the stream specific honest error`() = runBlocking {
        val plan = planPlayerLoad(PlayerMode.STREAM, streamId, Recorder().reconstructor)

        assertEquals(PlayerLoadPlan.Failed(STREAM_SESSION_EXPIRED), plan)
        assertEquals(
            "This stream session has expired. Reopen the episode list and try again.",
            (plan as PlayerLoadPlan.Failed).message,
        )
        assertFalse(
            "the download-path copy must never be shown for a stream failure",
            plan.message.contains("no longer available"),
        )
    }

    @Test
    fun `a missing ladder fails honestly instead of silently falling back to local`() = runBlocking {
        val plan = planPlayerLoad(PlayerMode.STREAM, streamId, reconstructor = null)

        assertEquals(PlayerLoadPlan.Failed(STREAM_SESSION_EXPIRED), plan)
    }
}
