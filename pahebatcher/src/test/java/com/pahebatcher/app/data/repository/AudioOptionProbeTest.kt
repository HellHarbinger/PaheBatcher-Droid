package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.entity.ScanCacheEntity
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.AudioProbeResult
import com.pahebatcher.app.domain.model.AudioProbeState
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.WebViewRequestBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded play-page audio-option discovery (SPEC §22, DECISIONS 144.2 / 144.3 /
 * 144.5) — target selection, the request bound, the five probe states and the
 * persistence rules.
 *
 * Every assertion runs against the REAL parsing (through [AudioOptionRepository]
 * → [PlayPageParser]) and the REAL cache rewrite ([ScanCacheRepository]); the
 * only test-only double is the session-aware page provider, which stands in for
 * the WebView bridge (AGENTS §5 — mocks live in tests only).
 */
class AudioOptionProbeTest {

    // -----------------------------------------------------------------------
    // 1 — deterministic target selection + duplicate elimination (144.2)
    // -----------------------------------------------------------------------

    @Test
    fun `targets are the lowest row of every session plus the opened session's maximum, last`() {
        val sessions = listOf("sessA", "sessB")
        val episodes = listOf(
            row(1f, "sessA", "a1"),
            row(2f, "sessA", "a2"),
            row(3f, "sessA", "a3"),
            row(1f, "sessB", "b1"),
            row(2f, "sessB", "b2"),
        )

        val targets = EpisodeRepository.audioProbeTargets(sessions, episodes)

        assertEquals(
            "opened-minimum first (discovery order), other session minimums next, opened maximum LAST",
            listOf(playUrl("sessA", "a1"), playUrl("sessB", "b1"), playUrl("sessA", "a3")),
            targets,
        )
        assertEquals(
            "two runs over the same listing are byte-identical",
            targets,
            EpisodeRepository.audioProbeTargets(sessions, episodes),
        )
        assertTrue("the bound is merged sessions + 1", targets.size <= sessions.size + 1)
    }

    @Test
    fun `a single-episode opened session contributes exactly one target`() {
        val episodes = listOf(row(1f, "sessA", "a1"))

        val targets = EpisodeRepository.audioProbeTargets(listOf("sessA"), episodes)

        assertEquals(
            "the maximum IS the minimum — deduplicated, not counted twice",
            listOf(playUrl("sessA", "a1")),
            targets,
        )
    }

    @Test
    fun `rows without a play url are skipped so the bound only ever shrinks`() {
        val sessions = listOf("sessA", "sessB")
        val episodes = listOf(
            unprobeableRow(1f),
            unprobeableRow(2f),
            row(1f, "sessB", "b1"),
        )

        assertEquals(
            "sessA has nothing to probe",
            listOf(playUrl("sessB", "b1")),
            EpisodeRepository.audioProbeTargets(sessions, episodes),
        )
    }

    @Test
    fun `a listing with nothing to probe yields no targets at all`() {
        assertEquals(
            emptyList<String>(),
            EpisodeRepository.audioProbeTargets(listOf("sessA"), listOf(unprobeableRow(1f))),
        )
    }

    @Test
    fun `a play url outside the known shape is never probed`() {
        val episodes = listOf(row(1f, "sessA", "a1").let { it.copy(playUrl = "https://example.invalid/2") })

        assertEquals(
            emptyList<String>(),
            EpisodeRepository.audioProbeTargets(listOf("sessA"), episodes),
        )
    }

    // -----------------------------------------------------------------------
    // 2 — request bound, union, and the five probe states
    // -----------------------------------------------------------------------

    @Test
    fun `a single-session probe makes exactly one request and unions what it saw`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1"),
            pages = mapOf("u-a1" to buttons("jpn", "eng")),
        )

        val result = harness.repository.ensureProbed(harness.anime())

        assertEquals("bound: one session => one request", 1, harness.provider.requests.size)
        assertEquals("u-a1", harness.provider.requests.single())
        assertEquals(AudioProbeState.OK_VALUES, result.state)
        assertEquals("open-set union, first-seen order", listOf("jpn", "eng"), result.audios)
        assertEquals(1, result.targets)
        assertEquals(1, result.fetched)
        assertTrue(result.isCompleted)
    }

    @Test
    fun `a multi-session probe makes exactly one request per target and no more`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1", "u-b1", "u-a3"),
            pages = mapOf(
                "u-a1" to buttons("jpn"),
                "u-b1" to buttons("jpn", "kor"),
                "u-a3" to buttons("jpn", "eng", "kor"),
            ),
        )

        val result = harness.repository.ensureProbed(harness.anime())

        assertEquals("bound: 3 targets => 3 requests", 3, harness.provider.requests.size)
        assertEquals(listOf("u-a1", "u-b1", "u-a3"), harness.provider.requests)
        assertEquals(
            "the union is deduplicated across the sampled pages",
            listOf("jpn", "kor", "eng"),
            result.audios,
        )
        assertEquals(AudioProbeState.OK_VALUES, result.state)
        assertEquals(3, result.fetched)
    }

    @Test
    fun `every target fetched without a parseable audio is a COMPLETED empty result`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1", "u-b1"),
            pages = mapOf(
                "u-a1" to "<html><body>no menu here</body></html>",
                "u-b1" to "<html><body>nor here</body></html>",
            ),
        )

        val result = harness.repository.ensureProbed(harness.anime())

        assertEquals(AudioProbeState.OK_EMPTY, result.state)
        assertEquals(emptyList<String>(), result.audios)
        assertEquals(2, result.fetched)
        assertTrue("OK_EMPTY is COMPLETED — no network on the next load", result.isCompleted)
        assertEquals(2, harness.provider.requests.size)
    }

    @Test
    fun `a total failure is FAILED with no retries inside the probe`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1", "u-b1"),
            pages = emptyMap(), // every fetch fails
        )

        val result = harness.repository.ensureProbed(harness.anime())

        assertEquals(AudioProbeState.FAILED, result.state)
        assertEquals(emptyList<String>(), result.audios)
        assertEquals(0, result.fetched)
        assertFalse(result.isCompleted)
        assertEquals(
            "no in-probe retry — exactly one attempt per target",
            2,
            harness.provider.requests.size,
        )
    }

    @Test
    fun `a mixed pass is PARTIAL and its union covers only the successes`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1", "u-b1", "u-a3"),
            pages = mapOf(
                "u-a1" to buttons("jpn"),
                // u-b1 fails on purpose
                "u-a3" to buttons("eng"),
            ),
        )

        val result = harness.repository.ensureProbed(harness.anime())

        assertEquals(AudioProbeState.PARTIAL, result.state)
        assertEquals(listOf("jpn", "eng"), result.audios)
        assertEquals(2, result.fetched)
        assertEquals(3, result.targets)
        assertFalse(result.isCompleted)
    }

    // -----------------------------------------------------------------------
    // 3 — completed cached states cost zero network; partial/failed re-probe
    // -----------------------------------------------------------------------

    @Test
    fun `a completed cached state costs zero requests`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1", "u-b1"),
            pages = mapOf("u-a1" to buttons("jpn"), "u-b1" to buttons("eng")),
        )
        val first = harness.repository.ensureProbed(harness.anime())
        assertEquals(AudioProbeState.OK_VALUES, first.state)
        assertEquals(2, harness.provider.requests.size)

        // A later load reads the row back through the SAME cache the scan uses.
        val second = harness.repository.ensureProbed(harness.anime())

        assertEquals("the persisted result is reused verbatim", first, second)
        assertEquals(
            "zero network for a completed cached state",
            2,
            harness.provider.requests.size,
        )
    }

    @Test
    fun `a legacy payload costs zero requests and reports NOT_PROBED`() = runBlocking {
        val harness = harness(extraEntity = legacyEntity())

        val anime = harness.anime()
        assertEquals(
            "legacy rows carry no probe and no targets",
            emptyList<String>(),
            anime.audioProbeTargets,
        )
        assertNull(anime.audioProbe)
        assertEquals(listOf(1f), anime.episodes.map { it.number })

        val result = harness.repository.ensureProbed(anime)

        assertEquals(AudioProbeState.NOT_PROBED, result.state)
        assertEquals(
            "a row with no targets is never probed",
            0,
            harness.provider.requests.size,
        )
    }

    @Test
    fun `a PARTIAL state re-probes at most once per call and later completes`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1", "u-b1"),
            pages = mapOf("u-a1" to buttons("jpn")), // u-b1 still fails
        )
        val partial = harness.repository.ensureProbed(harness.anime())
        assertEquals(AudioProbeState.PARTIAL, partial.state)
        assertEquals(2, harness.provider.requests.size)

        // Next load: the persisted PARTIAL result is NOT completed, so it is
        // re-attempted — one bounded pass, never a retry loop.
        val again = harness.repository.ensureProbed(harness.anime())
        assertEquals(AudioProbeState.PARTIAL, again.state)
        assertEquals("exactly one more pass", 4, harness.provider.requests.size)

        // The second pass now sees both pages succeed.
        harness.provider.pages = mapOf(
            "u-a1" to buttons("jpn"),
            "u-b1" to buttons("eng"),
        )
        val third = harness.repository.ensureProbed(harness.anime())
        assertEquals(AudioProbeState.OK_VALUES, third.state)
        assertEquals("…and a completed result stops the probing", 6, harness.provider.requests.size)

        val fourth = harness.repository.ensureProbed(harness.anime())
        assertEquals(third, fourth)
        assertEquals("completed => no further request at all", 6, harness.provider.requests.size)
    }

    @Test
    fun `two concurrent loads share ONE probe pass`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1", "u-b1"),
            pages = mapOf("u-a1" to buttons("jpn"), "u-b1" to buttons("eng")),
        )
        val snapshot = harness.anime()

        val a = async { harness.repository.ensureProbed(snapshot) }
        val b = async { harness.repository.ensureProbed(snapshot) }
        val results = listOf(a.await(), b.await())

        assertEquals("both loads see the same completed result", results[0], results[1])
        assertEquals(
            "the waiter reuses the persisted result — 2 requests total, not 4",
            2,
            harness.provider.requests.size,
        )
    }

    // -----------------------------------------------------------------------
    // 4 — persistence: freshness untouched, audio strings only, no new shape
    // -----------------------------------------------------------------------

    @Test
    fun `saving a probe leaves cachedAt, TTL and the listing payload untouched`() = runBlocking {
        val harness = harness(
            targets = listOf("u-a1"),
            pages = mapOf("u-a1" to buttons("jpn")),
        )
        val before = harness.dao.getBySession("sess-probe")!!

        assertTrue(
            "a row exists, so the probe is written",
            harness.cache.saveAudioProbe(
                "sess-probe",
                AudioProbeResult(AudioProbeState.OK_VALUES, listOf("jpn"), 1, 1),
            ),
        )

        val after = harness.dao.getBySession("sess-probe")!!
        assertEquals(
            "a probe never resets or extends scan freshness",
            before.cachedAt,
            after.cachedAt,
        )
        assertEquals("and never changes the TTL", before.cacheTtlMinutes, after.cacheTtlMinutes)
        assertEquals("nor the title", before.title, after.title)
        assertEquals("nor the host", before.host, after.host)
        assertEquals("nor the episode count", before.totalEpisodes, after.totalEpisodes)
        assertEquals(
            "nor the probe targets",
            harness.anime().audioProbeTargets,
            harness.cache.getCachedScan("sess-probe", 60)!!.audioProbeTargets,
        )
        assertEquals(
            "the episodes are still exactly the scanned ones",
            harness.anime().episodes.map { it.number to it.playUrl },
            harness.cache.getCachedScan("sess-probe", 60)!!.episodes.map { it.number to it.playUrl },
        )
        assertTrue(after.episodesJson.contains("\"audio_probe\""))
    }

    @Test
    fun `a probe persists audio strings only — never page HTML or a stream url`() = runBlocking {
        // The real menu markup plus a body marker: only the audio STRINGS may
        // survive the round trip, never the page itself.
        val html = buttons("jpn").replace(
            """<div id="resolutionMenu">""",
            """<div id="resolutionMenu">BODY-MARKER""",
        )
        val harness = harness(
            targets = listOf("u-a1"),
            pages = mapOf("u-a1" to html),
        )

        val result = harness.repository.ensureProbed(harness.anime())
        assertEquals(AudioProbeState.OK_VALUES, result.state)

        val payload = harness.dao.getBySession("sess-probe")!!.episodesJson
        assertTrue("the discovered audio value IS stored", payload.contains("\"jpn\""))
        assertFalse("no play-page HTML", payload.contains("BODY-MARKER"))
        assertFalse("no play-page HTML", payload.contains("resolutionMenu"))
        assertFalse("no stream url", payload.contains("m3u8"))
    }

    @Test
    fun `a legacy top-level-array payload still reads and can be upgraded in place`() = runBlocking {
        val harness = harness(extraEntity = legacyEntity())

        val read = harness.anime()
        assertEquals(
            "the legacy array is still read as episodes",
            listOf(1f),
            read.episodes.map { it.number },
        )
        assertEquals(emptyList<String>(), read.audioProbeTargets)
        assertNull(read.audioProbe)

        assertTrue(
            harness.cache.saveAudioProbe(
                "sess-probe",
                AudioProbeResult(AudioProbeState.OK_VALUES, listOf("eng"), 2, 2),
            ),
        )

        val upgraded = harness.anime()
        assertEquals(
            "the episodes survive the rewrite",
            listOf(1f),
            upgraded.episodes.map { it.number },
        )
        assertEquals("ep1-jpn", upgraded.episodes.single().session)
        assertEquals(AudioProbeState.OK_VALUES, upgraded.audioProbe?.state)
        assertEquals(listOf("eng"), upgraded.audioProbe?.audios)
    }

    @Test
    fun `saving a probe with no cache row writes nothing and says so`() = runBlocking {
        val cache = ScanCacheRepository(FakeScanCacheDao())

        assertFalse(
            cache.saveAudioProbe(
                "never-scanned",
                AudioProbeResult(AudioProbeState.OK_VALUES, listOf("jpn"), 1, 1),
            ),
        )
        assertNull(cache.persistedAudioProbe("never-scanned"))
    }

    @Test
    fun `reading a persisted probe never expires or deletes the row it lives in`() = runBlocking {
        val dao = FakeScanCacheDao()
        val cache = ScanCacheRepository(dao)
        dao.upsert(
            ScanCacheEntity(
                session = "old",
                title = "Old",
                host = "animepahe.com",
                totalEpisodes = 1,
                episodesJson = """{"episodes":[${episodeJson(1f, "old", "u-old")}],""" +
                    """"probe_targets":["u-old"]}""",
                cachedAt = 1L, // 1970 — long expired
                cacheTtlMinutes = 60,
            ),
        )

        assertNull(
            "a row that was never probed reports null, NOT_PROBED by omission",
            cache.persistedAudioProbe("old"),
        )
        assertEquals("the read deleted nothing", 1, dao.count())

        dao.upsert(
            dao.getBySession("old")!!.copy(
                episodesJson = """{"episodes":[${episodeJson(1f, "old", "u-old")}],""" +
                    """"probe_targets":["u-old"],""" +
                    """"audio_probe":{"state":"OK_VALUES","audios":["jpn"],"targets":1,"fetched":1}}""",
            ),
        )
        val probe = cache.persistedAudioProbe("old")
        assertEquals(AudioProbeState.OK_VALUES, probe?.state)
        assertEquals(listOf("jpn"), probe?.audios)
        assertEquals(
            "TTL expiry was NOT re-evaluated by the read",
            1,
            dao.count(),
        )
        assertEquals(1L, dao.getBySession("old")!!.cachedAt)
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private fun row(number: Float, variant: String, episode: String) = EpisodeInfo(
        number = number,
        session = episode,
        title = "E$number",
        fansub = "SubsPlease",
        audio = "jpn",
        playUrl = playUrl(variant, episode),
    )

    /** A row the scan produced without a play page (nothing to probe). */
    private fun unprobeableRow(number: Float) = EpisodeInfo(
        number = number,
        session = "ep$number",
        title = "E$number",
        fansub = "SubsPlease",
        audio = "jpn",
        playUrl = "",
    )

    private fun playUrl(variant: String, episode: String) =
        "https://animepahe.com/play/$variant/$episode"

    /** A real resolution menu: the same shape [PlayPageParser] parses in production. */
    private fun buttons(vararg audios: String): String = buildString {
        append("<html><head><title>Probe</title></head><body>")
        append("""<div id="resolutionMenu">""")
        audios.forEachIndexed { index, audio ->
            append(
                """<button data-src="https://kwik.si/e/$audio$index" """ +
                    """data-resolution="1080" data-audio="$audio">1080p</button>""",
            )
        }
        append("</div></body></html>")
    }

    private fun episodeJson(number: Float, session: String, playUrl: String): String =
        """{"number":$number,"session":"$session","title":"E$number","fansub":"SubsPlease",""" +
            """"audio":"jpn","play_url":"$playUrl"}"""

    /** A row written by a build that predates the probe — top-level ARRAY payload. */
    private fun legacyEntity() = ScanCacheEntity(
        session = "sess-probe",
        title = "Legacy",
        host = "animepahe.com",
        totalEpisodes = 1,
        episodesJson = "[${episodeJson(1f, "ep1-jpn", "u-legacy")}]",
        cachedAt = System.currentTimeMillis(),
        cacheTtlMinutes = 60,
    )

    private class Harness(
        val provider: RecordingPageProvider,
        val dao: FakeScanCacheDao,
        val cache: ScanCacheRepository,
        val repository: AudioOptionRepository,
    ) {
        /** What a user-driven load reads: the SAME cache row the scan wrote. */
        suspend fun anime(): AnimeInfo =
            cache.getCachedScan("sess-probe", 60) ?: error("fixture row missing")
    }

    private fun harness(
        targets: List<String> = listOf("u-a1"),
        pages: Map<String, String> = emptyMap(),
        extraEntity: ScanCacheEntity? = null,
    ): Harness {
        val dao = FakeScanCacheDao()
        val cache = ScanCacheRepository(dao)
        val provider = RecordingPageProvider().apply { this.pages = pages }
        val row = extraEntity ?: ScanCacheEntity(
            session = "sess-probe",
            title = "Probe",
            host = "animepahe.com",
            totalEpisodes = 2,
            episodesJson = """{"episodes":[${episodeJson(1f, "sess-probe", targets.first())}],""" +
                """"probe_targets":${targets.joinToString(",", "[", "]") { "\"$it\"" }}}""",
            cachedAt = System.currentTimeMillis(),
            cacheTtlMinutes = 60,
        )
        runBlocking { dao.upsert(row) }
        return Harness(
            provider = provider,
            dao = dao,
            cache = cache,
            repository = AudioOptionRepository(
                sessionProvider = provider,
                pageLock = Mutex(),
                scanCacheRepository = cache,
            ),
        )
    }

    /**
     * Session-aware page double: records every URL it was asked for and answers
     * from [pages] (`null` = the fetch failed). It always suspends briefly so
     * overlapping probe attempts really interleave.
     */
    private class RecordingPageProvider : CloudflareSessionProvider {
        var pages: Map<String, String> = emptyMap()
        val requests = mutableListOf<String>()

        override fun currentState(): CloudflareSessionProvider.SessionState =
            CloudflareSessionProvider.SessionState.ESTABLISHED

        override fun getSessionInfo(): CloudflareSessionProvider.SessionInfo =
            CloudflareSessionProvider.SessionInfo(
                state = CloudflareSessionProvider.SessionState.ESTABLISHED,
                userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36",
                cloudflareCompleted = true,
                finalUrl = "https://animepahe.com",
                isReady = true,
                lastValidatedAt = System.currentTimeMillis(),
                cookieCount = 5,
                activeHost = "animepahe.com",
            )

        override suspend fun establishSession(forceRefresh: Boolean): CloudflareSessionProvider.SessionInfo =
            getSessionInfo()

        override suspend fun isSessionValid(): Boolean = true

        override fun invalidateSession() {}

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>,
        ): CloudflareSessionProvider.PageResult {
            requests += url
            delay(20)
            val html = pages[url]
            return if (html == null) {
                CloudflareSessionProvider.PageResult(
                    success = false,
                    html = null,
                    json = null,
                    finalUrl = "",
                    statusCode = 0,
                    error = "Connection failed",
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList(),
                )
            } else {
                CloudflareSessionProvider.PageResult(
                    success = true,
                    html = html,
                    json = null,
                    finalUrl = url,
                    statusCode = 200,
                    error = null,
                    cloudflareChallengeDetected = false,
                    cloudflareChallengeCompleted = false,
                    diagnostics = emptyList(),
                )
            }
        }

        override fun releaseSession() {}
    }
}
