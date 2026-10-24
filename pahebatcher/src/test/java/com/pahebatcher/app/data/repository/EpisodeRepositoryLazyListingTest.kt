package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.util.EpisodePaging
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Lazy-loading slice — [EpisodeRepository.openListing] / [EpisodeRepository.loadListingPage]
 * / [EpisodeRepository.rowsForPage] against a deterministic fake release API.
 *
 * The fixture is a 250-episode title the server pages 30 rows at a time, so it
 * really is 9 pages long. What is asserted here is the FETCHING and the state
 * it produces:
 *
 * 1. opening reads the paginator header and page 1 — never the series;
 * 2. loading a page reads only that page, and a page already in memory costs
 *    zero requests;
 * 3. a page that cannot be read is never cached and a retry re-reads it;
 * 4. rows are deduplicated by `(number, audio)`, never by title;
 * 5. the lazy cache row is refused as a complete inventory, and a fresh
 *    process reopens it without a single request;
 * 6. [EpisodeRepository.scan] still reads every page — the full-inventory path
 *    is untouched;
 * 7. a scan that could not finish is never cached.
 *
 * No network (AGENTS §5): every response is a stub keyed on the page number.
 */
class EpisodeRepositoryLazyListingTest {

    private companion object {
        const val HOST = "animepahe.com"
        const val SESSION = "sess-paged"
        const val TOTAL = 250
        const val PER_PAGE = 30
        const val LAST_PAGE = 9
    }

    private lateinit var httpClient: FakeHttpClient
    private lateinit var cacheDao: FakeScanCacheDao
    private lateinit var cacheRepository: ScanCacheRepository
    private lateinit var repository: EpisodeRepository

    @Before
    fun setup() {
        httpClient = FakeHttpClient()
        cacheDao = FakeScanCacheDao()
        cacheRepository = ScanCacheRepository(cacheDao)
        repository = newRepository(httpClient, cacheRepository)
    }

    private fun newRepository(client: HttpClient, cache: ScanCacheRepository) =
        EpisodeRepository(
            animeRepository = AnimeRepository(client),
            scanCacheRepository = cache,
        )

    // -----------------------------------------------------------------------
    // Fixture — one real window of the server's own 30-row page
    // -----------------------------------------------------------------------

    /**
     * The primary release endpoint, addressed by page so a stub for page 3 can
     * never answer page 30 (`&sort=episode_asc&page=3` is not a prefix of
     * `…page=31` because the fixture never goes past page 9).
     */
    private fun pagePattern(page: Int) = "&sort=episode_asc&page=$page"

    private fun releasePage(
        page: Int,
        total: Int = TOTAL,
        lastPage: Int = LAST_PAGE,
        duplicateOrdinal: Boolean = false,
    ): String {
        val firstOrdinal = (page - 1) * PER_PAGE + 1
        val endExclusive = minOf(firstOrdinal + PER_PAGE, total + 1)
        val rows = (firstOrdinal until endExclusive).joinToString(",\n") { ordinal -> row(ordinal) }
        val extras = if (duplicateOrdinal && page == 3) {
            // (a) SAME number and audio under a DIFFERENT title → must collapse;
            // (b) a DIFFERENT number carrying the SAME title → must survive.
            // Together they pin "dedupe by (number, audio), never by title".
            ",\n" + row(61, title = "A Completely Different Title") +
                ",\n" + row(61.5f, title = "Episode 62")
        } else {
            ""
        }
        return """
            {"data": [
            $rows$extras
            ],
            "total": $total, "per_page": $PER_PAGE, "current_page": $page,
            "last_page": $lastPage, "anime": {"title": "Paged Anime"}}
        """.trimIndent()
    }

    /**
     * One release row. The title defaults to the ordinal's own label (`Episode
     * 61`, never `Episode 61.0`) so the dedupe test can compare it literally.
     */
    private fun row(ordinal: Number, title: String? = null): String {
        val n = ordinal.toFloat()
        val label = title ?: "Episode " + if (n == n.toInt().toFloat()) n.toInt() else n
        return """
            {"episode": $n, "session": "ep-$n-jpn", "title": "$label",
             "fansub": "SubsPlease", "audio": "jpn"}
        """.trimIndent()
    }

    /** The pages really asked for over the PRIMARY endpoint, as 1-based indexes. */
    private fun requestedPages(): Set<Int> = httpClient.requestedUrls
        .filter { it.contains("m=release") }
        .map { it.substringAfter("page=").toInt() }
        .toSet()

    private fun stub(vararg pages: Int) = pages.forEach {
        httpClient.stubJson(pagePattern(it), releasePage(it))
    }

    // -----------------------------------------------------------------------
    // 1. Opening the listing
    // -----------------------------------------------------------------------

    @Test
    fun `opening a long listing reads the paginator and the first page, never the series`() = runBlocking {
        // Only page 1 is needed; page 9 is stubbed because the open also reads
        // the OPENED session's last page for the audio-probe target set
        // (DECISIONS 144.2) — one bounded request, never page 2..8.
        stub(1, LAST_PAGE)

        val anime = repository.openListing(HOST, SESSION, cacheTtlMinutes = 60)

        assertEquals("the API's own total, parsed not guessed", TOTAL, anime.apiTotal)
        assertEquals("the API's own window", PER_PAGE, anime.perPage)
        assertEquals("the API's own page count", LAST_PAGE, anime.apiLastPage)
        assertTrue(anime.completed)
        assertEquals("page 1 is the whole listing this call exposes", setOf(1), anime.loadedPages)
        assertEquals(PER_PAGE, anime.episodes.size)

        val pages = requestedPages()
        assertEquals(
            "opening costs page 1 plus the probe page — a 9-page title never " +
                "has its middle pages fetched because the screen opened",
            setOf(1, LAST_PAGE),
            pages,
        )
        assertTrue((2 until LAST_PAGE).none { it in pages })
    }

    // -----------------------------------------------------------------------
    // 2. Loading one more page
    // -----------------------------------------------------------------------

    @Test
    fun `loading a page reads only that page and an already-open page is free`() = runBlocking {
        stub(1, 2, LAST_PAGE)
        val anime = repository.openListing(HOST, SESSION, cacheTtlMinutes = 60)
        val before = requestedPages()

        val opened = repository.loadListingPage(HOST, anime, page = 2, cacheTtlMinutes = 60)

        assertEquals("exactly one new page was read", before + 2, requestedPages())
        val rows = repository.rowsForPage(opened, 2)
        assertNotNull("the window exists once its page was read", rows)
        assertEquals(PER_PAGE, rows!!.size)
        assertEquals("the window's FIRST ordinal, not a memory offset", 31f, rows.first().number)
        assertEquals("the window's LAST ordinal", 60f, rows.last().number)

        // Ask for the same page again: served from memory, zero requests.
        repository.loadListingPage(HOST, opened, page = 2, cacheTtlMinutes = 60)
        assertEquals("a page already open costs nothing", before + 2, requestedPages())

        // A page that was never opened reports an empty window, not another
        // page's rows and not a fabricated one.
        assertEquals(emptyList<com.pahebatcher.app.domain.model.EpisodeInfo>(), repository.rowsForPage(opened, 5))
        assertNull("an out-of-range page is not a window at all", repository.rowsForPage(opened, LAST_PAGE + 1))
    }

    @Test
    fun `a page that cannot be read is never cached and a retry re-reads it for real`() = runBlocking {
        stub(1, LAST_PAGE)
        // First attempt: the primary endpoint answers nothing (and the fallback
        // endpoint carries no stub, so it answers nothing too). Second attempt
        // gets a real page.
        httpClient.stubJson(pagePattern(3), null)
        httpClient.stubJson(pagePattern(3), releasePage(3))

        val anime = repository.openListing(HOST, SESSION, cacheTtlMinutes = 60)

        try {
            repository.loadListingPage(HOST, anime, page = 3, cacheTtlMinutes = 60)
            fail("an unreadable page must fail loudly, never come back short")
        } catch (e: EpisodeRepository.PageLoadException) {
            assertEquals("the failure is reported against the page that failed", 3, e.page)
        }

        assertTrue(
            "the failed page never becomes a cached window",
            repository.rowsForPage(anime, 3).orEmpty().isEmpty(),
        )
        val cached = cacheRepository.getListing(SESSION, 60)
        assertNotNull(cached)
        assertTrue("page 1 is cached", cached!!.pages.containsKey(1))
        assertFalse("page 3 is not", cached.pages.containsKey(3))

        val retried = repository.loadListingPage(HOST, anime, page = 3, cacheTtlMinutes = 60)
        val rows = repository.rowsForPage(retried, 3)
        assertNotNull("the retry really re-read it", rows)
        assertEquals(PER_PAGE, rows!!.size)
        assertEquals(61f, rows.first().number)
    }

    // -----------------------------------------------------------------------
    // 3. Deduplication across pages
    // -----------------------------------------------------------------------

    @Test
    fun `rows are deduplicated by number and audio, never by title`() = runBlocking {
        stub(1, LAST_PAGE)
        httpClient.stubJson(pagePattern(3), releasePage(3, duplicateOrdinal = true))
        val anime = repository.openListing(HOST, SESSION, cacheTtlMinutes = 60)

        val opened = repository.loadListingPage(HOST, anime, page = 3, cacheTtlMinutes = 60)
        val rows = repository.rowsForPage(opened, 3)!!

        assertEquals(
            "32 rows in (30 real + one duplicate number + one extra number) " +
                "come out as 31 — the duplicate collapsed even though its title " +
                "differed, so title could not be the key",
            PER_PAGE + 1,
            rows.size,
        )
        assertEquals(
            "exactly one row for that number",
            1,
            rows.count { it.number == 61f },
        )
        assertEquals(
            "first-wins: the duplicate never overwrites the row already held",
            "Episode 61",
            rows.first { it.number == 61f }.title,
        )
        assertTrue(
            "a different number carrying another number's title survives — " +
                "two episodes are never merged because their titles match",
            rows.any { it.number == 61.5f && it.title == "Episode 62" },
        )
    }

    // -----------------------------------------------------------------------
    // 4. Cache round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `a lazy row is refused as a complete inventory and a fresh process reopens it with zero requests`() = runBlocking {
        stub(1, 2, LAST_PAGE)
        val anime = repository.openListing(HOST, SESSION, cacheTtlMinutes = 60)
        repository.loadListingPage(HOST, anime, page = 2, cacheTtlMinutes = 60)

        assertNotNull("the listing really was persisted", cacheDao.getBySession(SESSION))
        assertNull(
            "but a partial listing can NEVER be handed to the callers that want " +
                "a whole inventory (they re-scan instead)",
            cacheRepository.getCachedScan(SESSION, 60),
        )
        val listing = cacheRepository.getListing(SESSION, 60)
        assertNotNull(listing)
        assertEquals(
            "the lazy row remembers WHICH pages it holds, so page 2 is not " +
                "re-derived from a flat union",
            setOf(1, 2),
            listing!!.pages.keys,
        )
        assertEquals("and it keeps the source's own numbers", TOTAL, listing.anime.apiTotal)

        // A brand-new repository — the process-restart case.
        val restartClient = FakeHttpClient()
        val restarted = newRepository(restartClient, cacheRepository)
        val reopened = restarted.openListing(HOST, SESSION, cacheTtlMinutes = 60)

        assertTrue(
            "reopening a fresh lazy cache costs ZERO release requests",
            restartClient.requestedUrls.none { it.contains("m=release") },
        )
        assertEquals(TOTAL, reopened.apiTotal)
        assertEquals(PER_PAGE, reopened.perPage)
        assertEquals("page 2 is already in memory after the restart", setOf(1, 2), reopened.loadedPages)
        val rows = restarted.rowsForPage(reopened, 2)
        assertNotNull(rows)
        assertEquals(PER_PAGE, rows!!.size)
        assertEquals(31f, rows.first().number)
        assertTrue(
            "and an un-opened page is honestly empty rather than invented",
            restarted.rowsForPage(reopened, 4).orEmpty().isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // 4b. The in-memory bound
    // -----------------------------------------------------------------------

    @Test
    fun `the in-memory page cache stays bounded however many pages are opened`() = runBlocking {
        // An 18-page title: opening pages 1..17 in turn is 17 opened pages for a
        // cache that holds 16.
        val wideTotal = PER_PAGE * 18
        (18 downTo 1).forEach { page ->
            httpClient.stubJson(pagePattern(page), releasePage(page, total = wideTotal, lastPage = 18))
        }

        var opened = repository.openListing(HOST, SESSION, cacheTtlMinutes = 60)
        for (page in 2..17) {
            opened = repository.loadListingPage(HOST, opened, page = page, cacheTtlMinutes = 60)
        }

        assertTrue(
            "the rebuilt listing can never exceed the page cache: at most " +
                "${EpisodePaging.MAX_CACHED_PAGES} windows of $PER_PAGE rows, not a 540-episode series",
            opened.episodes.size <= EpisodePaging.MAX_CACHED_PAGES * PER_PAGE,
        )
        assertEquals(
            "exactly the bound is held once it is reached",
            EpisodePaging.MAX_CACHED_PAGES,
            opened.loadedPages.size,
        )
        assertTrue(
            "and it is the RECENT pages that are retained",
            repository.rowsForPage(opened, 17).orEmpty().isNotEmpty(),
        )
        assertTrue(
            "while the oldest opened page has been dropped",
            repository.rowsForPage(opened, 1).orEmpty().isEmpty(),
        )

        // Dropping is not losing: the page is fetched again for real, never
        // served from memory and never reported as present.
        val before = httpClient.requestedUrls.count { it.contains("m=release") }
        val reread = repository.loadListingPage(HOST, opened, page = 1, cacheTtlMinutes = 60)
        assertEquals(
            "the evicted page costs one real request",
            before + 1,
            httpClient.requestedUrls.count { it.contains("m=release") },
        )
        assertEquals(PER_PAGE, repository.rowsForPage(reread, 1)!!.size)
    }

    @Test
    fun `many opened sessions are bounded and only the least-recently-used one drops`() = runBlocking {
        // DATA-003: the OUTER cache (one entry per anime session) is bounded to
        // MAX_CACHED_SESSIONS — opening one more must evict exactly the
        // least-recently-used session, never a recent one and never two.
        stub(1, LAST_PAGE)
        val sessions = (1..(EpisodeRepository.MAX_CACHED_SESSIONS + 1)).map { "sess-lru-$it" }
        val opened = sessions.map { session ->
            repository.openListing(HOST, session, cacheTtlMinutes = 60)
        }

        assertTrue(
            "the session the user just opened keeps its window",
            repository.rowsForPage(opened.last(), 1).orEmpty().isNotEmpty(),
        )
        assertTrue(
            "the second-oldest session still fits the bound",
            repository.rowsForPage(opened[1], 1).orEmpty().isNotEmpty(),
        )
        assertEquals(
            "exactly ONE session — the least-recently-used — was evicted, and " +
                "its window reads as never opened (the refetch path), never as stale rows",
            emptyList<EpisodeInfo>(),
            repository.rowsForPage(opened.first(), 1),
        )
    }

    // -----------------------------------------------------------------------
    // 5. The full-inventory path is untouched
    // -----------------------------------------------------------------------

    @Test
    fun `scan still reads every page because the callers that need an inventory still exist`() = runBlocking {
        stub(*(1..LAST_PAGE).toList().toIntArray())

        val result = repository.scan(HOST, SESSION, cacheTtlMinutes = 60)

        assertTrue(result.completed)
        assertEquals("every episode of the series", TOTAL, result.episodes.size)
        assertEquals(
            "the full path really paginated — pages 2..8 are read here and " +
                "only here",
            (1..LAST_PAGE).toSet(),
            requestedPages(),
        )
        assertEquals(1, cacheDao.count())
    }

    @Test
    fun `a scan that could not finish is never cached as a complete listing`() = runBlocking {
        stub(1, 2, 3, 4)
        httpClient.stubJson(pagePattern(5), null)

        val result = repository.scan(HOST, SESSION, cacheTtlMinutes = 60)

        assertFalse("episodes past page 5 are missing, so the scan is incomplete", result.completed)
        assertEquals(120, result.episodes.size)
        assertEquals(
            "the scan stopped at the page it could not read",
            setOf(1, 2, 3, 4, 5),
            requestedPages(),
        )
        assertEquals("and nothing was cached", 0, cacheDao.count())
        assertNull(cacheRepository.getCachedScan(SESSION, 60))
    }
}
