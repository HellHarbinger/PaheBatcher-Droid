package com.pahebatcher.app.ui.details

import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import com.pahebatcher.app.data.repository.FakeScanCacheDao
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17 §10 — the Stream action's hand-off.
 *
 * The action must hand navigation EXACTLY one thing: the episode id that
 * `DownloadRepository` keys the same episode by, derived through the shared
 * [EpisodeId.build]. It must produce nothing at all until the screen really has
 * a selected episode with a resolved stream, and it must never be a URL.
 *
 * The UI half (the control exists and invokes the caller's callback) is the
 * instrumentation test `AnimeDetailsScreenTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailsStreamActionTest {

    private fun episodesPage(): String = """
        {"data": [
            {"episode": 1, "session": "ep1", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 2, "session": "ep2", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Test Anime"}}
    """.trimIndent()

    private class AlwaysSucceedingResolver : StreamResolver {
        override suspend fun resolve(playUrl: String, quality: Int, audio: String) = StreamInfo(
            url = "https://cdn.example.invalid/master.m3u8",
            userAgent = "ua",
            referer = "https://kwik.example.invalid/page",
        )
    }

    private class NoopQueue : EpisodeDownloadQueue {
        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission) = EnqueueOutcome.Accepted
    }

    private fun newViewModel(client: HttpClient): AnimeDetailsViewModel {
        val episodeRepository = EpisodeRepository(
            animeRepository = AnimeRepository(client),
            scanCacheRepository = ScanCacheRepository(FakeScanCacheDao()),
        )
        return AnimeDetailsViewModel(
            episodeRepository = episodeRepository,
            streamResolver = AlwaysSucceedingResolver(),
            downloadQueue = NoopQueue(),
            configProvider = { AppContext.defaults() },
        )
    }

    private fun kotlinx.coroutines.test.TestScope.givenVirtualMain() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `with no episode selected there is no id to navigate with`() = runTest {
        givenVirtualMain()
        val vm = newViewModel(FakeHttpClient().apply { stubJson("m=release", episodesPage()) })

        vm.load("sess-stream")
        testScheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.selectedEpisode)
        assertNull("no selection must produce no navigation argument", vm.streamEpisodeId())
    }

    @Test
    fun `a selection without a resolved stream produces no id`() = runTest {
        givenVirtualMain()
        val vm = newViewModel(FakeHttpClient().apply { stubJson("m=release", episodesPage()) })

        vm.load("sess-stream")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())

        assertNull(
            "the action must not fire before the stream resolved",
            vm.streamEpisodeId(),
        )

        testScheduler.advanceUntilIdle()
        assertNotNull(
            "once resolved the action is offered",
            vm.streamEpisodeId(),
        )
    }

    @Test
    fun `a resolved episode hands over the shared episode id and nothing else`() = runTest {
        givenVirtualMain()
        val vm = newViewModel(FakeHttpClient().apply { stubJson("m=release", episodesPage()) })

        vm.load("sess-stream")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes.first())
        testScheduler.advanceUntilIdle()

        val episode = vm.uiState.value.episodes.first()
        val id = vm.streamEpisodeId()

        assertEquals(EpisodeId.build("sess-stream", episode), id)
        assertEquals("sess-stream_1_jpn", id)
        assertTrue("the hand-off is an id, never a URL", id!!.contains("://").not())
        assertNull("the play page URL must not travel in the route", id.takeIf { it.contains("play/") })
    }

    @Test
    fun `the id follows the currently selected episode`() = runTest {
        givenVirtualMain()
        val vm = newViewModel(FakeHttpClient().apply { stubJson("m=release", episodesPage()) })

        vm.load("sess-stream")
        testScheduler.advanceUntilIdle()

        vm.selectEpisode(vm.uiState.value.episodes[0])
        testScheduler.advanceUntilIdle()
        val first = vm.streamEpisodeId()

        vm.selectEpisode(vm.uiState.value.episodes[1])
        testScheduler.advanceUntilIdle()
        val second = vm.streamEpisodeId()

        assertEquals("sess-stream_1_jpn", first)
        assertEquals("sess-stream_2_jpn", second)
    }

    @Test
    fun `the hand-off id always satisfies the shape gate the player enforces`() = runTest {
        givenVirtualMain()
        val vm = newViewModel(FakeHttpClient().apply { stubJson("m=release", episodesPage()) })

        vm.load("sess-stream")
        testScheduler.advanceUntilIdle()
        vm.selectEpisode(vm.uiState.value.episodes[1])
        testScheduler.advanceUntilIdle()

        val id = vm.streamEpisodeId()
        val parts = EpisodeId.parse(id!!)

        assertTrue("the player would reject this id", parts != null)
        assertEquals("sess-stream", parts!!.animeSession)
        assertEquals("2", parts.episodeString)
        assertEquals("jpn", parts.audio)
        assertEquals(EpisodeInfo.AUDIO_JAPANESE, vm.uiState.value.episodes[1].audio)
    }
}
