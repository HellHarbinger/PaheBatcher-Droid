package com.pahebatcher.app.ui

import com.pahebatcher.app.data.local.dao.ScanCacheDao
import com.pahebatcher.app.data.local.entity.ScanCacheEntity
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Device-side test doubles shared by the batch
 * lifecycle instrumentation tests (T2/T3). Test-only (AGENTS §5): the
 * androidTest source set cannot see src/test's fakes, so these live here.
 *
 * All fakes are REAL implementations of the production interfaces with
 * scripted behavior; nothing in production references this file.
 */

/** Canned scan listing — the same 2-numbers/3-variants shape as the JVM suite. */
internal object BatchDeviceFixtures {

    fun dualAudioPage(): String = """
        {"data": [
            {"episode": 1, "session": "ep1-jpn", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 1, "session": "ep1-eng", "title": "Beginnings", "fansub": "DubsPlease", "audio": "eng"},
            {"episode": 2, "session": "ep2-jpn", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Test Anime"}}
    """.trimIndent()
}

/** Serves the fixture listing for every JSON request — no network in tests. */
internal class ScriptedHttpClient : HttpClient {
    override suspend fun fetchJson(url: String): String? = BatchDeviceFixtures.dualAudioPage()

    override suspend fun fetchHtml(url: String): String? = null
}

/** In-memory scan cache (the interface is tiny; production cache never hit). */
internal class EmptyScanCacheDao : ScanCacheDao {
    override suspend fun getBySession(session: String): ScanCacheEntity? = null

    override fun getAll(): Flow<List<ScanCacheEntity>> = flowOf(emptyList())

    override suspend fun upsert(cache: ScanCacheEntity) = Unit

    override suspend fun deleteExpired(expiryTime: Long) = Unit

    override suspend fun delete(session: String) = Unit

    override suspend fun deleteAll() = Unit

    override suspend fun count(): Int = 0
}

/**
 * Resolution scripted per variant page:
 *
 * - ep1 resolves instantly (the submitted number);
 * - ep2 WAITS while [holdEp2] is true — a release-able hold, so tests can
 *   (a) observe a deterministic partial submission and (b) release the tail
 *   later to prove a leaked coroutine would be visible.
 */
internal class ScriptedResolver : StreamResolver {
    val calls = mutableListOf<String>()

    @Volatile
    var holdEp2: Boolean = true

    override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
        calls += playUrl
        if (playUrl.contains("ep2")) {
            while (holdEp2) delay(200L)
        }
        return StreamInfo(
            url = "https://cdn.example.invalid/${calls.size}.m3u8",
            userAgent = "Mozilla/5.0 (test)",
            referer = playUrl,
        )
    }
}

/** Records submissions and marks numbers handled — production skip semantics. */
internal class RecordingQueue : EpisodeDownloadQueue {
    val submissions = mutableListOf<ResolvedEpisodeSubmission>()

    override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
        submissions += submission
        return EnqueueOutcome.Accepted
    }

    override suspend fun isEpisodeAlreadyHandled(
        animeSession: String,
        episode: EpisodeInfo,
    ): Boolean = submissions.any { it.episode.number == episode.number }

    override suspend fun resolvedAheadCount(): Int = 0
}

/** Polls a condition on the test thread — real time, generous deadline. */
internal fun awaitCondition(
    what: String,
    timeoutMs: Long = 15_000L,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(50L)
    }
    if (!condition()) throw AssertionError("Timed out waiting for $what")
}
