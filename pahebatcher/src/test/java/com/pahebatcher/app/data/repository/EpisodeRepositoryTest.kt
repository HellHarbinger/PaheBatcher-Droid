package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.entity.ScanCacheEntity
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EpisodeRepositoryTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var animeRepository: AnimeRepository
    private lateinit var scanCacheDao: FakeScanCacheDao
    private lateinit var scanCacheRepository: ScanCacheRepository
    private lateinit var episodeRepository: EpisodeRepository

    @Before
    fun setup() {
        httpClient = FakeHttpClient()
        animeRepository = AnimeRepository(httpClient)
        scanCacheDao = FakeScanCacheDao()
        scanCacheRepository = ScanCacheRepository(scanCacheDao)
        episodeRepository = EpisodeRepository(animeRepository, scanCacheRepository)
    }

    @Test
    fun `scan returns cached result when available`() = runBlocking {
        val cachedAnime = AnimeInfo(
            session = "session1",
            title = "Cached Anime",
            host = "animepahe.com",
            total = 12,
            episodes = listOf(
                EpisodeInfo(1f, "ep1", "Ep 1", "", "jpn", "url1")
            )
        )
        scanCacheRepository.saveScanCache(cachedAnime, cacheTtlMinutes = 60)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
        assertEquals("Cached Anime", result.title)
    }

    @Test
    fun `scan fetches from API when cache empty`() = runBlocking {
        val pageData = """{"data": [], "anime": {"title": "Test Anime"}, "last_page": 1}"""
        httpClient.stubJson("/api?m=release", pageData)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
        assertEquals("Test Anime", result.title)
    }

    @Test
    fun `scan deduplicates episodes by number and audio`() = runBlocking {
        val pageData = """{"data": [
            {"episode": 1, "session": "ep1", "title": "Ep 1", "fansub": "", "audio": "jpn"},
            {"episode": 1, "session": "ep2", "title": "Ep 1", "fansub": "", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Test"}}"""
        httpClient.stubJson("/api?m=release", pageData)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
        assertEquals(1, result.episodes.size)
    }

    @Test
    fun `scan preserves both SUB and DUB variants`() = runBlocking {
        val pageData = """{"data": [
            {"episode": 1, "session": "ep1", "title": "Ep 1", "fansub": "", "audio": "jpn"},
            {"episode": 1, "session": "ep2", "title": "Ep 1 DUB", "fansub": "", "audio": "eng"}
        ], "last_page": 1, "anime": {"title": "Test"}}"""
        httpClient.stubJson("/api?m=release", pageData)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
        assertEquals(2, result.episodes.size)
        assertTrue(result.hasDualAudio())
    }

    @Test
    fun `scan sorts episodes by number then audio`() = runBlocking {
        // Note: "eng" < "jpn" alphabetically, so DUB comes before SUB in sorted order
        val pageData = """{"data": [
            {"episode": 2, "session": "ep2", "title": "Ep 2", "fansub": "", "audio": "jpn"},
            {"episode": 1, "session": "ep1", "title": "Ep 1", "fansub": "", "audio": "jpn"},
            {"episode": 2, "session": "ep3", "title": "Ep 2 DUB", "fansub": "", "audio": "eng"},
            {"episode": 1, "session": "ep4", "title": "Ep 1 DUB", "fansub": "", "audio": "eng"}
        ], "last_page": 1, "anime": {"title": "Test"}}"""
        httpClient.stubJson("/api?m=release", pageData)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
        assertEquals(4, result.episodes.size)
        // Sorted by (number, audio) — "eng" < "jpn" alphabetically
        assertEquals(1.0f, result.episodes[0].number)
        assertEquals("eng", result.episodes[0].audio)
        assertEquals(1.0f, result.episodes[1].number)
        assertEquals("jpn", result.episodes[1].audio)
        assertEquals(2.0f, result.episodes[2].number)
        assertEquals("eng", result.episodes[2].audio)
        assertEquals(2.0f, result.episodes[3].number)
        assertEquals("jpn", result.episodes[3].audio)
    }

    @Test
    fun `scan saves to cache after successful scan`() = runBlocking {
        val pageData = """{"data": [], "anime": {"title": "Test Anime"}, "last_page": 1}"""
        httpClient.stubJson("/api?m=release", pageData)

        episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)

        assertEquals(1, scanCacheDao.count())
        val cached = scanCacheDao.getBySession("session1")
        assertEquals("Test Anime", cached?.title)
    }

    @Test
    fun `scan handles multiple pages`() = runBlocking {
        // First stub consumed by discoverVariants (for title extraction)
        val discoveryPage = """{"data": [], "anime": {"title": "Test"}, "last_page": 2}"""
        // Second stub: scan page 1
        val scanPage1 = """{"data": [{"episode": 1, "session": "ep1", "title": "Ep 1", "fansub": "", "audio": "jpn"}], "last_page": 2}"""
        // Third stub: scan page 2
        val scanPage2 = """{"data": [{"episode": 2, "session": "ep2", "title": "Ep 2", "fansub": "", "audio": "jpn"}], "last_page": 2}"""

        httpClient.stubJson("/api?m=release", discoveryPage)
        httpClient.stubJson("/api?m=release", scanPage1)
        httpClient.stubJson("/api?m=release", scanPage2)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
        assertEquals(2, result.episodes.size)
    }

    @Test
    fun `scan with cache disabled skips cache lookup`() = runBlocking {
        val cachedAnime = AnimeInfo(
            session = "session1",
            title = "Cached Anime",
            host = "animepahe.com",
            total = 1
        )
        scanCacheRepository.saveScanCache(cachedAnime, cacheTtlMinutes = 60)

        val pageData = """{"data": [], "anime": {"title": "Fresh Anime"}, "last_page": 1}"""
        httpClient.stubJson("/api?m=release", pageData)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 0)
        assertEquals("Fresh Anime", result.title)
    }

    @Test
    fun `scan handles empty episode page`() = runBlocking {
        val pageData = """{"data": [], "anime": {"title": "Test Anime"}, "last_page": 1}"""
        httpClient.stubJson("/api?m=release", pageData)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
        assertEquals(0, result.episodes.size)
    }

    // -----------------------------------------------------------------------
    // Post-audio slice — OBSERVED scan progress (DECISIONS: honest progress)
    //
    // The listing reports its own `last_page`; every number below is either
    // something the scan actually fetched or a total the SERVER declared.
    // -----------------------------------------------------------------------

    /** discovery page → scan page 1 (of 2) → scan page 2 (of 2). */
    private fun stubTwoPageJourney() {
        val discovery = """{"data": [], "anime": {"title": "Test"}, "last_page": 2}"""
        val scanPage1 = """{"data": [{"episode": 1, "session": "ep1", "title": "Ep 1", "fansub": "", "audio": "jpn"}], "last_page": 2}"""
        val scanPage2 = """{"data": [{"episode": 2, "session": "ep2", "title": "Ep 2", "fansub": "", "audio": "jpn"}], "last_page": 2}"""
        httpClient.stubJson("/api?m=release", discovery)
        httpClient.stubJson("/api?m=release", scanPage1)
        httpClient.stubJson("/api?m=release", scanPage2)
    }

    @Test
    fun `scan reports each observed page against the server's own page total`() = runBlocking {
        stubTwoPageJourney()
        val progress = mutableListOf<ScanProgress>()

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60) {
            progress += it
        }

        assertEquals(
            "session start (no page claimed) + one tick per page really fetched",
            listOf(
                ScanProgress(sessionIndex = 1, sessionCount = 1, page = 0),
                ScanProgress(sessionIndex = 1, sessionCount = 1, page = 1, pagesInSession = 2),
                ScanProgress(sessionIndex = 1, sessionCount = 1, page = 2, pagesInSession = 2),
            ),
            progress,
        )
        assertEquals(2, result.episodes.size)
        assertEquals(
            "the rendered line names a real page and a server-declared denominator",
            "Scanning episodes · session 1 of 1 · page 2 of 2",
            progress.last().describe(),
        )
    }

    @Test
    fun `a cache hit reports no progress because no page was fetched`() = runBlocking {
        scanCacheRepository.saveScanCache(
            AnimeInfo(
                session = "session1",
                title = "Cached Anime",
                host = "animepahe.com",
                total = 1,
                episodes = listOf(EpisodeInfo(1f, "ep1", "Ep 1", "", "jpn", "url1")),
            ),
            cacheTtlMinutes = 60,
        )
        val progress = mutableListOf<ScanProgress>()

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60) {
            progress += it
        }

        assertTrue(
            "nothing was fetched, so no progress may be claimed",
            progress.isEmpty(),
        )
        assertEquals("Cached Anime", result.title)
    }

    @Test
    fun `a scan that reads no page at all claims no page`() = runBlocking {
        // No stubs at all: discovery finds nothing and the loop reads null.
        val progress = mutableListOf<ScanProgress>()

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60) {
            progress += it
        }

        assertEquals(
            "only the session start — never a page the server never gave us",
            listOf(ScanProgress(sessionIndex = 1, sessionCount = 1, page = 0)),
            progress,
        )
        assertEquals("only the session start ever renders", "Scanning episodes · session 1 of 1", progress.first().describe())
        assertEquals(0, result.episodes.size)
    }

    @Test
    fun `a cancelled scan stops reporting progress instead of leaking later ticks`() = runBlocking {
        stubTwoPageJourney()
        val progress = mutableListOf<ScanProgress>()
        var job: Job? = null

        job = launch {
            episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60) { p ->
                progress += p
                // Stop the scan the moment it reports a real page — a
                // superseding load, a destroyed screen, a revoked scope.
                if (p.page > 0) job?.cancel()
            }
        }
        job.join()

        assertTrue("the scan really ended", job.isCancelled)
        assertEquals(
            "session start + the one page it reported, and not a tick more",
            2,
            progress.size,
        )
        assertEquals(
            "the inter-page fetch never happened — only [session start, page 1]",
            listOf(0, 1),
            progress.map { it.page },
        )
        assertEquals(
            "the last thing the user saw is still true",
            ScanProgress(sessionIndex = 1, sessionCount = 1, page = 1, pagesInSession = 2),
            progress.last(),
        )
    }

    // -----------------------------------------------------------------------
    // Scan-completion integrity (`extract/scanner.py:252,267-290,295-302`)
    //
    // A scan that left required pages behind still RETURNS the episodes it read
    // (exactly like the reference), but it is flagged incomplete and can never
    // become - or overwrite - a cached "complete" episode inventory.
    // -----------------------------------------------------------------------

    /** The variant-discovery request (always the first `m=release` call). */
    private fun discoveryPage(lastPage: Int = 1): String =
        """{"data": [], "anime": {"title": "Test Anime"}, "last_page": $lastPage}"""

    /** A readable listing page carrying one episode. */
    private fun episodePage(number: Int, lastPage: Int): String =
        """{"data": [{"episode": $number, "session": "ep$number", "title": "Ep $number", "fansub": "", "audio": "jpn"}], "last_page": $lastPage}"""

    /** discovery → page 1 (of [lastPage]) → an answer that never comes. */
    private fun stubJourneyCutShort(lastPage: Int) {
        httpClient.stubJson("/api?m=release", discoveryPage(lastPage))
        httpClient.stubJson("/api?m=release", episodePage(1, lastPage))
        httpClient.stubJson("/api?m=release", null)
    }

    @Test
    fun `a complete multi-page scan is cached and the next scan reads it back`() = runBlocking {
        httpClient.stubJson("/api?m=release", discoveryPage(lastPage = 2))
        httpClient.stubJson("/api?m=release", episodePage(1, lastPage = 2))
        httpClient.stubJson("/api?m=release", episodePage(2, lastPage = 2))

        val first = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)

        assertTrue("every required page was read", first.completed)
        assertEquals(listOf(1f, 2f), first.episodes.map { it.number })
        assertEquals("a completed scan is cached", 1, scanCacheDao.count())

        val requestsAfterFirstScan = httpClient.requestedUrls.size
        val second = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)

        assertEquals(
            "the cached result is served without another listing request",
            requestsAfterFirstScan,
            httpClient.requestedUrls.size,
        )
        assertTrue(second.completed)
        assertEquals(listOf(1f, 2f), second.episodes.map { it.number })
    }

    @Test
    fun `a scan whose later page cannot be fetched is returned but never cached`() = runBlocking {
        stubJourneyCutShort(lastPage = 3)

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)

        assertFalse("pages are missing, so this is not a completed scan", result.completed)
        assertEquals("the episodes really read are still returned", 1, result.episodes.size)
        assertEquals("a partial scan is never persisted", 0, scanCacheDao.count())
    }

    @Test
    fun `a scan whose later page cannot be parsed is returned but never cached`() = runBlocking {
        httpClient.stubJson("/api?m=release", discoveryPage(lastPage = 2))
        httpClient.stubJson("/api?m=release", episodePage(1, lastPage = 2))
        httpClient.stubJson("/api?m=release", "<html><body>502 Bad Gateway</body></html>")

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)

        assertFalse("an unreadable page leaves required pages behind", result.completed)
        assertEquals(1, result.episodes.size)
        assertEquals("an unreadable page never produces a cached scan", 0, scanCacheDao.count())
    }

    @Test
    fun `an incomplete refresh leaves a previously cached scan untouched`() = runBlocking {
        val prior = AnimeInfo(
            session = "session1",
            title = "Cached Anime",
            host = "animepahe.com",
            total = 1,
            episodes = listOf(EpisodeInfo(1f, "ep1", "Ep 1", "", "jpn", "url1")),
        )
        scanCacheRepository.saveScanCache(prior, cacheTtlMinutes = 60)
        val stored = scanCacheDao.getBySession("session1")!!

        stubJourneyCutShort(lastPage = 2)
        // cache_ttl = 0 is the force-fresh path (WatchlistChecker uses it): the
        // stored row is bypassed for the read, and the failed refresh must not
        // rewrite it either.
        val refreshed = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 0)

        assertFalse(refreshed.completed)
        val after = scanCacheDao.getBySession("session1")!!
        assertEquals("the stored scan survives", "Cached Anime", after.title)
        assertEquals("its freshness is not reset", stored.cachedAt, after.cachedAt)
        assertEquals(
            "its payload is not overwritten by the partial scan",
            stored.episodesJson,
            after.episodesJson,
        )
    }

    @Test
    fun `a failed refresh does not resurrect an expired cache entry as fresh`() = runBlocking {
        scanCacheDao.upsert(
            ScanCacheEntity(
                session = "session1",
                title = "Expired Anime",
                host = "animepahe.com",
                totalEpisodes = 1,
                episodesJson = "[]",
                cachedAt = System.currentTimeMillis() - (7200 * 1000L),
                cacheTtlMinutes = 60,
            ),
        )

        stubJourneyCutShort(lastPage = 2)
        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)

        assertFalse(result.completed)
        assertNull(
            "the expired row was dropped by the read; the failed refresh must " +
                "not hand itself a new freshness timestamp",
            scanCacheDao.getBySession("session1"),
        )
    }

    @Test
    fun `the scan after an incomplete one fetches again instead of reading a partial cache`() = runBlocking {
        stubJourneyCutShort(lastPage = 2)

        val incomplete = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
        assertFalse(incomplete.completed)
        assertEquals(0, scanCacheDao.count())
        val requestsAfterFailure = httpClient.requestedUrls.size

        // The source answers again on the next attempt.
        httpClient.stubJson("/api?m=release", episodePage(2, lastPage = 1))
        val retried = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)

        assertTrue(
            "the retry went back to the source instead of reading a partial cache",
            httpClient.requestedUrls.size > requestsAfterFailure,
        )
        assertTrue(retried.completed)
        assertEquals(listOf(2f), retried.episodes.map { it.number })
        assertEquals(1, scanCacheDao.count())
    }

    @Test
    fun `a completed scan with no episodes is still cached as a completed result`() = runBlocking {
        httpClient.stubJson(
            "/api?m=release",
            """{"data": [], "anime": {"title": "Empty Anime"}, "last_page": 1}""",
        )

        val result = episodeRepository.scan("animepahe.com", "session1", cacheTtlMinutes = 60)

        assertTrue("an answered page with no releases is a COMPLETE scan", result.completed)
        assertTrue(result.episodes.isEmpty())
        assertEquals("and it caches exactly like any other completed scan", 1, scanCacheDao.count())
    }

    @Test
    fun `a cancelled scan is not converted into a partial result and writes no cache`() = runBlocking {
        // The production transport answers a CANCELLED request with an ordinary
        // failure (AndroidWebViewRequestBridge catches every Exception, including
        // CancellationException), so the repository is handed `null` rather than
        // an exception.
        val cancellingClient = object : HttpClient {
            override suspend fun fetchJson(url: String): String? = try {
                delay(Long.MAX_VALUE) // the cancellation lands here
                """{"data": [], "last_page": 1}"""
            } catch (e: CancellationException) {
                null
            }

            override suspend fun fetchHtml(url: String): String? = null
        }
        val repo = EpisodeRepository(AnimeRepository(cancellingClient), scanCacheRepository)

        var returned: AnimeInfo? = null
        var reportedCancellation = false
        var job: Job? = null
        job = launch {
            try {
                returned = repo.scan("animepahe.com", "session1", cacheTtlMinutes = 60)
            } catch (e: CancellationException) {
                reportedCancellation = true
                throw e
            }
        }
        delay(50) // the scan is now suspended inside its first page request
        job?.cancel()
        job.join()

        assertTrue("the scan really ended by cancellation", job.isCancelled)
        assertTrue(
            "cancellation was not swallowed into a partial result",
            reportedCancellation,
        )
        assertNull(returned)
        assertEquals("a cancelled scan writes no cache entry", 0, scanCacheDao.count())
    }
}
