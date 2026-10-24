package com.pahebatcher.app.ui.details

import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import com.pahebatcher.app.data.repository.FakeScanCacheDao
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.domain.util.EpisodePaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lazy-loading slice (corrected) — the details-screen boundary: APP pages
 * built from the API's 30-row windows, the page-scoped batch action, the
 * independent ordinal range selector, and the 100-episode ceiling at the batch
 * EXECUTION boundary.
 *
 * The two page sizes this suite pins, and the reason they differ:
 *
 * * the API answers 30 rows per HTTP request ([PER_PAGE]) — the FETCH unit;
 * * the app SHOWS 100 episodes per page over a 100-episode total and 30 at or
 *   below it ([EpisodePaging.appPageSize]) — the VISIBLE unit, built from the
 *   API windows its ordinals span.
 *
 * Everything runs through the production chain (ViewModel → EpisodeRepository
 * → parser) against a deterministic fake release API, and what is asserted is
 * observable: the API windows really requested, the state really published,
 * the rows really rendered and the episode identities really queued.
 *
 * | requirement | test |
 * |---|---|
 * | total > 100 → app page 100 | `opening reads one APP page …` |
 * | total ≤ 100 (and exactly 100) → 30 | `a 100-episode title pages at 30 …` |
 * | unknown total never becomes zero | `an unknown total is never invented …` |
 * | API 30 combined into app pages of 100 | `opening reads one APP page …` |
 * | final short app page | `a 100-episode title pages at 30 …` |
 * | special numbers never move an ordinal | `special episode numbers never shift …` |
 * | open never fetches the series | `opening reads one APP page …` |
 * | a page switch fetches only its windows | `goToPage moves the window …` |
 * | open windows cost nothing | `a range whose windows are already open …` |
 * | page retry after a transient failure | `a page whose window fails keeps …` |
 * | eviction really re-fetches | `an evicted window is really fetched again …` |
 * | page batch = that page only | `a page batch queues only …` |
 * | short final page queues its own rows | `the short final page queues …` |
 * | variants never inflate the count | `audio variants of one number …` |
 * | over-limit refused before queueing | `a page batch over the ceiling …` |
 * | range works without visiting the page | `an ordinal range works …` |
 * | failed range page = no false success | `a range whose window fails …` |
 * | individual download intact | `an episode on a later page …` |
 *
 * No network (AGENTS §5): every response is a stub keyed on the page number.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailsViewModelPagingTest {

    private companion object {
        const val HOST = "animepahe.com"
        const val SESSION = "sess-paged"
        const val TOTAL = 250
        const val PER_PAGE = 30
        const val LAST_PAGE = 9
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private class RecordingQueue : EpisodeDownloadQueue {
        val submissions = mutableListOf<ResolvedEpisodeSubmission>()

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            return EnqueueOutcome.Accepted
        }

        override suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo): Boolean = false

        override suspend fun resolvedAheadCount(): Int = 0
    }

    private class NoOpResolver : StreamResolver {
        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo =
            StreamInfo(
                url = "https://cdn.example.invalid/1.m3u8",
                userAgent = "Mozilla/5.0 (test)",
                referer = playUrl,
            )
    }

    private fun newViewModel(
        client: FakeHttpClient,
        queue: RecordingQueue = RecordingQueue(),
        cache: ScanCacheRepository = ScanCacheRepository(FakeScanCacheDao()),
    ): AnimeDetailsViewModel = AnimeDetailsViewModel(
        episodeRepository = EpisodeRepository(
            animeRepository = AnimeRepository(client),
            scanCacheRepository = cache,
        ),
        streamResolver = NoOpResolver(),
        downloadQueue = queue,
        configProvider = { AppContext.defaults() },
    )

    private fun pagePattern(page: Int) = "&sort=episode_asc&page=$page"

    private fun row(ordinal: Int, audio: String = "jpn", number: Number = ordinal): String =
        """{"episode": ${number.toFloat()}, "session": "ep-$ordinal-$audio",
            "title": "Episode $ordinal", "fansub": "SubsPlease", "audio": "$audio"}"""

    /**
     * One 30-row server window, addressed by the API's own 1-based page index.
     *
     * @param dualAudio the window carries BOTH a `jpn` and an `eng` row for
     *   every episode number — the shape a multi-audio title produces, and the
     *   reason the batch ceiling counts DISTINCT numbers rather than rows
     * @param numbered maps an ordinal to the episode NUMBER it carries, so a
     *   title of specials (1.5, 2.5 …) can be built without changing the
     *   ordinal model: ordinals are positions, never numbers
     */
    private fun releasePage(
        page: Int,
        total: Int = TOTAL,
        lastPage: Int = LAST_PAGE,
        dualAudio: Boolean = false,
        numbered: (Int) -> Number = { it },
    ): String {
        val firstOrdinal = (page - 1) * PER_PAGE + 1
        val endExclusive = minOf(firstOrdinal + PER_PAGE, total + 1)
        val rows = (firstOrdinal until endExclusive).joinToString(",\n") { ordinal ->
            row(ordinal, "jpn", numbered(ordinal)) +
                if (dualAudio) ",\n" + row(ordinal, "eng", numbered(ordinal)) else ""
        }
        return """
            {"data": [$rows],
            "total": $total, "per_page": $PER_PAGE, "current_page": $page,
            "last_page": $lastPage, "anime": {"title": "Paged Anime"}}
        """.trimIndent()
    }

    /**
     * Stub every window of a title, DESCENDING: `page=1` is a substring of
     * `page=17`, and the fake answers the first pattern that matches — so a
     * two-digit window must be registered before the one-digit pattern that
     * would otherwise answer for it.
     */
    private fun stubAll(
        client: FakeHttpClient,
        total: Int = TOTAL,
        lastPage: Int = LAST_PAGE,
        dualAudioPage: Int? = null,
        numbered: (Int) -> Number = { it },
    ) = (lastPage downTo 1).forEach { page ->
        client.stubJson(
            pagePattern(page),
            releasePage(page, total, lastPage, dualAudio = page == dualAudioPage, numbered = numbered),
        )
    }

    /** Every window of the fixture is answerable; which ones are REQUESTED is not. */
    private fun pagedClient(): FakeHttpClient = FakeHttpClient().apply { stubAll(this) }

    /**
     * Like [pagedClient], but each window in [failFirst] answers NOTHING on its
     * FIRST request and a real page afterwards — a transient failure.
     *
     * The failure stub has to be registered BEFORE the real one: the fake keeps
     * one response list per pattern and hands them out in order, so appending
     * the failure afterwards would leave the first request succeeding and the
     * retry failing — the exact opposite of the behaviour under test.
     */
    private fun pagedClient(failFirst: Set<Int>): FakeHttpClient = FakeHttpClient().apply {
        failFirst.forEach { page -> stubJson(pagePattern(page), null) }
        stubAll(this)
    }

    /** The API windows really asked for over the PRIMARY endpoint. */
    private fun requestedPages(client: FakeHttpClient): Set<Int> = client.requestedUrls
        .filter { it.contains("m=release") }
        .map { it.substringAfter("page=").toInt() }
        .toSet()

    /** How often ONE window was really requested (no prefix collisions). */
    private fun requestedCount(client: FakeHttpClient, page: Int): Int =
        client.requestedUrls.count { it.contains("m=release") && it.endsWith("page=$page") }

    private fun load(
        vm: AnimeDetailsViewModel,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        session: String = SESSION,
    ) {
        vm.load(session)
        scheduler.advanceUntilIdle()
    }

    /** The distinct episode numbers a queue really received, in order. */
    private fun queued(queue: RecordingQueue): List<Float> =
        queue.submissions.map { it.episode.number }

    // -----------------------------------------------------------------------
    // App page sizing — what opening publishes
    // -----------------------------------------------------------------------

    @Test
    fun `opening reads one APP page of 100 built from four API windows, never the series`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val client = pagedClient()
            val vm = newViewModel(client)

            load(vm, testScheduler)

            val state = vm.uiState.value
            assertNull("the open succeeded", state.error)
            assertEquals("the API's own total", TOTAL, state.apiTotal)
            assertEquals("the API's own HTTP window — still 30 rows per request", PER_PAGE, state.perPage)
            assertEquals("the API's own page count", LAST_PAGE, state.apiLastPage)
            assertEquals("the APP page is 100 — over 100 episodes, not the server's 30", 100, appPageSizeOf(state))
            assertEquals("and 250 episodes are therefore three APP pages", 3, appPageCountOf(state))
            assertEquals("the visible page starts at 1", 1, state.currentPage)
            assertFalse("and nothing is in flight once it settles", state.pageLoading)
            assertNull(state.pageError)

            val window = displayEpisodeEpisodes(state)
            assertEquals(
                "the window IS app page 1: one hundred episodes, built by " +
                    "combining the four 30-row windows their ordinals span",
                100,
                window.size,
            )
            assertEquals(1f, window.first().number)
            assertEquals(100f, window.last().number)

            val pages = requestedPages(client)
            assertEquals(
                "opening reads windows 1..4 (app page 1) plus the bounded " +
                    "probe window — never the middle of the series",
                setOf(1, 2, 3, 4, LAST_PAGE),
                pages,
            )
            assertTrue((5 until LAST_PAGE).none { it in pages })
        }

    @Test
    fun `a 100-episode title pages at 30 and its last page holds only what is left`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val hundredTotal = 100
        val hundredLastPage = 4
        val client = FakeHttpClient().apply { stubAll(this, total = hundredTotal, lastPage = hundredLastPage) }
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)

        load(vm, testScheduler, "sess-hundred")

        val state = vm.uiState.value
        assertEquals(
            "exactly 100 episodes follow the AT-OR-BELOW-100 rule: 30 per page",
            30,
            appPageSizeOf(state),
        )
        assertEquals("so the title is four APP pages", 4, appPageCountOf(state))
        assertEquals("app page 1 is the server's own window, so opening is cheap", 30, displayEpisodeEpisodes(state).size)
        assertEquals("opening cost page 1 plus the probe page — no app-page fill", setOf(1, hundredLastPage), requestedPages(client))

        vm.goToPage(4)
        testScheduler.advanceUntilIdle()

        val last = vm.uiState.value
        assertEquals("the last app page is the FINAL SHORT page", 4, last.currentPage)
        assertEquals(
            "100 episodes are 30 + 30 + 30 + 10 — the short page holds exactly what is left",
            (91..100).map { it.toFloat() },
            displayEpisodeEpisodes(last).map { it.number },
        )
        assertNull("and it loaded cleanly", last.pageError)

        vm.setBatchMode(true)
        vm.startPageBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the short page queues only its own ten episodes",
            (91..100).map { it.toFloat() },
            queued(queue),
        )
        assertEquals(10, vm.uiState.value.batchTotal)
    }

    // -----------------------------------------------------------------------
    // Select-all and the eligible window (unchanged contract)
    // -----------------------------------------------------------------------

    @Test
    fun `select all stops at the eligible 100 and reads only the pages that window spans`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = pagedClient()
        val vm = newViewModel(client)
        load(vm, testScheduler)

        vm.setBatchMode(true)
        vm.selectAllEpisodes()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("a legal selection reports no problem", state.batchError)
        assertEquals(
            "over a 250-episode title, select-all means the eligible 100 — " +
                "never the whole anime",
            (1..100).map { it.toFloat() }.toSet(),
            state.selectedNumbers,
        )
        assertEquals("so the batch total is the selection, not the series", 100, state.selectedNumbers.size)
        assertEquals(
            "windows 1..4 already carry that whole window, so nothing new is read",
            setOf(1, 2, 3, 4, LAST_PAGE),
            requestedPages(client),
        )
        assertFalse(state.pageLoading)
    }

    // -----------------------------------------------------------------------
    // Page navigation — APP pages
    // -----------------------------------------------------------------------

    @Test
    fun `goToPage moves the window to that APP page and reads only its API windows`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = pagedClient()
        val vm = newViewModel(client)
        load(vm, testScheduler)

        vm.goToPage(2)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the visible page follows the request", 2, state.currentPage)
        assertNull("the page loaded cleanly", state.pageError)
        assertFalse(state.pageLoading)

        val window = displayEpisodeEpisodes(state)
        assertEquals(
            "app page 2 is ordinals 101..200 — app page 1's rows are gone, " +
                "never left behind under the new page number",
            (101..200).map { it.toFloat() },
            window.map { it.number },
        )
        assertEquals(
            "only the windows app page 2 is built from (4..7, with 4 shared " +
                "with app page 1 and therefore free) were requested",
            setOf(1, 2, 3, 4, 5, 6, 7, LAST_PAGE),
            requestedPages(client),
        )
        assertEquals("window 8 was never touched", 0, requestedCount(client, 8))

        // An out-of-range APP page is refused by the ViewModel, not by a
        // disabled button: no request, no state change.
        val before = requestedPages(client)
        vm.goToPage(appPageCountOf(state)!! + 1)
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.currentPage)
        assertEquals("nothing was fetched for an impossible page", before, requestedPages(client))
    }

    @Test
    fun `a page whose window fails keeps the selected page and a retry really re-reads it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Window 6 answers nothing on the first attempt and a real page on the
        // second — a transient failure, exactly what a retry must survive.
        val client = pagedClient(failFirst = setOf(6))
        val vm = newViewModel(client)
        load(vm, testScheduler)

        vm.goToPage(2)
        testScheduler.advanceUntilIdle()

        val failed = vm.uiState.value
        assertEquals(
            "the app page the user asked for is still the selected one",
            2,
            failed.currentPage,
        )
        assertNotNull("the failure is rendered, not swallowed", failed.pageError)
        assertTrue("and no half-page is shown as if it were the page", displayEpisodeEpisodes(failed).isEmpty())

        vm.goToPage(2)
        testScheduler.advanceUntilIdle()

        val retried = vm.uiState.value
        assertNull("the retry clears the error", retried.pageError)
        assertEquals(
            "and really re-reads the window that failed — 101..200 is whole again",
            (101..200).map { it.toFloat() },
            displayEpisodeEpisodes(retried).map { it.number },
        )
    }

    @Test
    fun `an evicted window is really fetched again when its page is revisited`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // 520 episodes over 18 windows = six APP pages of 100. Walking them all
        // opens 18 windows for a cache bounded at [MAX_CACHED_PAGES], so the
        // first windows are dropped — and a revisit must cost real requests.
        val wideTotal = 520
        val wideLastPage = 18
        val client = FakeHttpClient().apply { stubAll(this, total = wideTotal, lastPage = wideLastPage) }
        val vm = newViewModel(client)
        load(vm, testScheduler, "sess-wide")

        assertEquals(6, appPageCountOf(vm.uiState.value))
        for (page in 2..6) {
            vm.goToPage(page)
            testScheduler.advanceUntilIdle()
        }
        assertEquals(
            "the whole walk opened every window of the series",
            (1..wideLastPage).toSet(),
            requestedPages(client),
        )
        val firstCount = requestedCount(client, 1)

        vm.goToPage(1)
        testScheduler.advanceUntilIdle()

        assertEquals(
            "revisiting app page 1 costs a REAL request for the window the " +
                "cache dropped — an evicted window is never reported as present",
            firstCount + 1,
            requestedCount(client, 1),
        )
        assertEquals(
            "while the windows it still held are read from memory, untouched",
            1,
            requestedCount(client, 3),
        )
        assertEquals(
            "including the boundary window of the neighbouring app page",
            1,
            requestedCount(client, 4),
        )
        assertEquals(
            "and the page really is WHOLE again: ordinals 1..100, not a " +
                "silently shortened window left over from the eviction",
            (1..100).map { it.toFloat() },
            displayEpisodeEpisodes(vm.uiState.value).map { it.number },
        )
    }

    // -----------------------------------------------------------------------
    // The independent ordinal range selector
    // -----------------------------------------------------------------------

    @Test
    fun `an ordinal range works without ever visiting the page it lives on`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = pagedClient()
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)
        load(vm, testScheduler)
        vm.setBatchMode(true)

        vm.requestEpisodeRange(101, 200)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("the range resolved", state.batchError)
        assertEquals(
            "the selection is the range's own ordinals — read from the windows " +
                "it spans, without the user ever opening app page 2",
            (101..200).map { it.toFloat() }.toSet(),
            state.selectedNumbers,
        )
        assertEquals(
            "windows 4..7 cover ordinals 101..200, and only they were asked for",
            setOf(1, 2, 3, 4, 5, 6, 7, LAST_PAGE),
            requestedPages(client),
        )
        assertEquals(
            "the visible page is untouched by a range request — still app page 1",
            1,
            state.currentPage,
        )
        assertEquals(
            "with its own rows still on screen",
            (1..100).map { it.toFloat() },
            displayEpisodeEpisodes(state).map { it.number },
        )

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "and the batch that follows queues exactly the range's episodes",
            (101..200).map { it.toFloat() },
            queued(queue),
        )
    }

    @Test
    fun `a range whose windows are already open costs no requests at all`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = pagedClient()
        val vm = newViewModel(client)
        load(vm, testScheduler)
        val before = requestedPages(client)

        // 61..90 lives entirely on window 3, which app page 1 already opened.
        vm.requestEpisodeRange(61, 90)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the selection is the range's own ordinals",
            (61..90).map { it.toFloat() }.toSet(),
            state.selectedNumbers,
        )
        assertEquals("not one new request was made", before, requestedPages(client))
        assertEquals(1, state.currentPage)
    }

    @Test
    fun `a range on a 1180-episode title resolves straight from page 1`() = runTest {
        // The device acceptance case, in the real title's shape: One Piece is
        // 1,180 episodes over THIRTY-ROW API windows — 40 of them — so app
        // page 1 is windows 1..4 and the requested range lives on window 7,
        // far past anything the open touched. The 250-episode fixture above
        // cannot reach this shape, which is exactly why the defect it hides
        // survived there.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val onePieceTotal = 1180
        val onePieceLastPage = 40
        val client = FakeHttpClient().apply {
            stubAll(this, total = onePieceTotal, lastPage = onePieceLastPage)
        }
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)
        load(vm, testScheduler, "sess-one-piece")
        vm.setBatchMode(true)

        // Still on app page 1 — the user never navigates to the range's page.
        assertEquals(1, vm.uiState.value.currentPage)

        vm.requestEpisodeRange(200, 202)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("the range resolved", state.batchError)
        assertEquals(
            "the selection is exactly ordinals 200..202, read from the one " +
                "window they live on — not from any page the user opened",
            setOf(200f, 201f, 202f),
            state.selectedNumbers,
        )
        assertEquals(
            "only the range's own window was fetched beyond the open — never " +
                "the intervening windows 5 and 6",
            setOf(1, 2, 3, 4, 7, onePieceLastPage),
            requestedPages(client),
        )
        assertEquals("the visible page is untouched", 1, state.currentPage)

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "and the batch queues exactly the three requested episodes",
            listOf(200f, 201f, 202f),
            queued(queue),
        )
    }

    @Test
    fun `a range still resolves after the process was recreated over a warm cache`() = runTest {
        // The device situation the fresh-cache fixtures above cannot express:
        // the user opened One Piece BEFORE, so a lazy listing is already
        // persisted, and the range is requested from a RECREATED ViewModel on
        // a later visit — page 7 was never read by that process.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val onePieceTotal = 1180
        val onePieceLastPage = 40
        val client = FakeHttpClient().apply {
            stubAll(this, total = onePieceTotal, lastPage = onePieceLastPage)
        }
        val cache = ScanCacheRepository(FakeScanCacheDao())

        val first = newViewModel(client, cache = cache)
        load(first, testScheduler, "sess-one-piece")
        assertEquals(
            "the first visit really persisted a lazy listing to warm the cache",
            1,
            first.uiState.value.currentPage,
        )

        // A recreated process: a NEW ViewModel over the SAME persisted cache.
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue, cache)
        load(vm, testScheduler, "sess-one-piece")
        assertEquals(1, vm.uiState.value.currentPage)
        vm.setBatchMode(true)

        vm.requestEpisodeRange(200, 202)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(
            "the range resolves from the recreated process too — a warm cache " +
                "must never turn a valid range into a failure",
            state.batchError,
        )
        assertEquals(
            "the selection is exactly the requested ordinals",
            setOf(200f, 201f, 202f),
            state.selectedNumbers,
        )

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()
        assertEquals(
            "and the batch queues exactly those three episodes",
            listOf(200f, 201f, 202f),
            queued(queue),
        )
    }

    @Test
    fun `a range whose window fails never reports a partial success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Window 6 fails once, then answers — so the same request can be
        // retried and the honest failure can be separated from a real one.
        val client = pagedClient(failFirst = setOf(6))
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)
        load(vm, testScheduler)
        vm.setBatchMode(true)

        vm.requestEpisodeRange(101, 200)
        testScheduler.advanceUntilIdle()

        val failed = vm.uiState.value
        assertNotNull("the failure is reported against the range request", failed.pageError ?: failed.batchError)
        assertTrue("nothing is selected — a partial range is never queued as the whole one", failed.selectedNumbers.isEmpty())
        assertEquals("the visible page keeps its own rows", 1, failed.currentPage)
        assertEquals(100, displayEpisodeEpisodes(failed).size)
        assertTrue("and not one episode was queued", queue.submissions.isEmpty())

        vm.requestEpisodeRange(101, 200)
        testScheduler.advanceUntilIdle()

        val retried = vm.uiState.value
        assertNull("the retry resolves cleanly", retried.batchError)
        assertEquals(
            "and the selection really is the full range this time",
            (101..200).map { it.toFloat() }.toSet(),
            retried.selectedNumbers,
        )
    }

    @Test
    fun `a 101-episode range is refused with an explicit message, fetching nothing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = pagedClient()
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)
        load(vm, testScheduler)
        vm.setBatchMode(true)
        val pagesBefore = requestedPages(client)

        vm.requestEpisodeRange(1, 101)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the refusal is the batch error the panel renders — never a silent cut",
            EpisodePaging.RangeRejection.TooLarge(101).message,
            state.batchError,
        )
        assertTrue("nothing was selected", state.selectedNumbers.isEmpty())
        assertFalse("and no window was opened for a request that was refused", state.pageLoading)
        assertEquals(pagesBefore, requestedPages(client))
        assertTrue("and nothing was queued", queue.submissions.isEmpty())
    }

    @Test
    fun `a range past the metadata-proven bound is refused instead of silently under-selecting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Malformed metadata: total claims 250 rows but per_page(30) x last_page(4)
        // proves only ordinals 1..120 can ever be fetched — ordinals 121..250 do
        // not exist, and windows past 4 are unaddressable. A range straddling 120
        // must be refused UP FRONT rather than accepted and quietly filled with
        // only the rows that happened to resolve.
        val client = FakeHttpClient().apply { stubAll(this, total = 250, lastPage = 4) }
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)
        load(vm, testScheduler)
        vm.setBatchMode(true)
        val pagesBefore = requestedPages(client)

        vm.requestEpisodeRange(101, 150)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "the refusal names the ORDINAL BOUND the metadata proves, not the overstated total",
            EpisodePaging.RangeRejection.OutOfBounds(120).message,
            state.batchError,
        )
        assertTrue(
            "nothing is selected — a range whose tail cannot be fetched is never " +
                "partially accepted",
            state.selectedNumbers.isEmpty(),
        )
        assertFalse("no window was opened for a request refused before fetching", state.pageLoading)
        assertEquals("not one extra window was asked for", pagesBefore, requestedPages(client))
        assertTrue("nothing was queued", queue.submissions.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Page-scoped batch download
    // -----------------------------------------------------------------------

    @Test
    fun `a page batch queues only the episodes of the page it names`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = pagedClient()
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)
        load(vm, testScheduler)
        vm.setBatchMode(true)

        // A selection that legitimately spans a whole app page — the point is
        // that the page action must NOT queue it.
        vm.selectAllEpisodes()
        testScheduler.advanceUntilIdle()
        assertEquals(100, vm.uiState.value.selectedNumbers.size)

        vm.goToPage(2)
        testScheduler.advanceUntilIdle()

        vm.startPageBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "exactly app page 2's episodes — not page 1's, not page 3's, and " +
                "not 'the first 100' nor 'everything loaded in memory'",
            (101..200).map { it.toFloat() },
            queued(queue),
        )
        assertEquals("the batch total names that page", 100, state.batchTotal)
        assertFalse("and the run really started", state.batchRunning)
    }

    @Test
    fun `audio variants of one number never inflate the page batch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Window 2 carries a `jpn` AND an `eng` row for every number. App page 1
        // spans windows 1..4 but is cut to ordinals 1..100, so it holds
        // 30 + 60 + 30 + 10 = 130 ROWS over 100 DISTINCT episodes. Repeated
        // references to one number must collapse before the ceiling counts them.
        val client = FakeHttpClient().apply { stubAll(this, dualAudioPage = 2) }
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)
        load(vm, testScheduler)

        val window = displayEpisodeEpisodes(vm.uiState.value)
        assertEquals(
            "the window really holds both variants of every number on window 2",
            130,
            window.size,
        )
        assertEquals("over 100 distinct episodes", 100, window.map { it.number }.distinct().size)

        vm.setBatchMode(true)
        vm.startPageBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("100 distinct episodes are inside the ceiling", state.batchError)
        assertEquals("so the batch is 100, never 130", 100, state.batchTotal)
        assertEquals("and one submission per DISTINCT episode", 100, queue.submissions.size)
        assertEquals((1..100).map { it.toFloat() }, queued(queue).distinct())
    }

    @Test
    fun `a page batch over the ceiling is refused before anything is queued`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // A legacy payload: one page of 150 episodes and NO usable paginator, so
        // the whole listing IS the one page — which is exactly why the ceiling
        // has to be enforced in EXECUTION and not only in the UI.
        val client = FakeHttpClient().apply {
            stubJson(pagePattern(1), legacyLongPage())
        }
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)

        load(vm, testScheduler, "sess-legacy")

        val loaded = vm.uiState.value
        assertNull("no total in this payload, and one is never invented", loaded.apiTotal)
        assertNull("nor is a page size", loaded.perPage)
        assertNull("so there is no paging model and the listing is undivided", loaded.pageRows)
        assertEquals(150, loaded.episodes.size)

        vm.setBatchMode(true)
        vm.startPageBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("the batch did not start", state.batchRunning)
        assertEquals(
            "the explicit, rendered outcome",
            "A batch may contain at most 100 episodes (this one has 150).",
            state.batchError,
        )
        assertTrue("not one episode was queued", queue.submissions.isEmpty())
    }

    // -----------------------------------------------------------------------
    // The batch execution boundary (selection path) — unchanged contract
    // -----------------------------------------------------------------------

    @Test
    fun `101 unique episodes are refused at batch execution, never quietly truncated to 100`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = FakeHttpClient().apply {
            stubJson(pagePattern(1), legacyLongPage())
        }
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)

        load(vm, testScheduler, "sess-legacy")

        vm.setBatchMode(true)
        vm.selectAllEpisodes()
        testScheduler.advanceUntilIdle()
        assertEquals(
            "selection itself is legal — 150 distinct episode numbers",
            150,
            vm.uiState.value.selectedNumbers.size,
        )

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("the batch did not start", state.batchRunning)
        assertEquals(
            "the explicit, rendered outcome",
            "A batch may contain at most 100 episodes (this one has 150).",
            state.batchError,
        )
        assertEquals(
            "the selection is left intact — the user is told WHY, not silently " +
                "handed a different batch",
            150,
            state.selectedNumbers.size,
        )
        assertTrue("not one episode was queued", queue.submissions.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Ordinal mapping under specials, and unknown vs zero total
    // -----------------------------------------------------------------------

    @Test
    fun `special episode numbers never shift an ordinal window`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Every episode is numbered as a special (0.5, 1.5, 2.5 …): the number
        // is NEVER the ordinal, so a window must be cut by POSITION.
        val client = FakeHttpClient().apply { stubAll(this, numbered = { it - 0.5 }) }
        val queue = RecordingQueue()
        val vm = newViewModel(client, queue)
        load(vm, testScheduler)

        val window = displayEpisodeEpisodes(vm.uiState.value)
        assertEquals("app page 1 is still 100 ORDINALS", 100, window.size)
        assertEquals("whose numbers are the specials at those positions", 0.5f, window.first().number)
        assertEquals(99.5f, window.last().number)

        vm.setBatchMode(true)
        vm.startPageBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the page batch queues those 100 specials — ordinal 1 is episode " +
                "0.5, and no page was ever derived from a number",
            (1..100).map { it.toFloat() - 0.5f },
            queued(queue),
        )

        vm.goToPage(2)
        testScheduler.advanceUntilIdle()
        assertEquals(
            "app page 2 starts at ordinal 101 = episode 100.5",
            100.5f,
            displayEpisodeEpisodes(vm.uiState.value).first().number,
        )
    }

    @Test
    fun `an unknown total is never invented while a real zero is reported as an empty anime`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        // 1. No `total` at all, but the page size and page count are real.
        val unknownClient = FakeHttpClient().apply {
            stubJson(pagePattern(1), unknownTotalPage())
            stubJson(pagePattern(LAST_PAGE), releasePage(LAST_PAGE))
        }
        val unknown = newViewModel(unknownClient)
        load(unknown, testScheduler, "sess-unknown")

        val unknownState = unknown.uiState.value
        assertNull("an absent total stays absent", unknownState.apiTotal)
        assertEquals("the window is still real (derived from a provably full page)", 30, unknownState.perPage)
        assertEquals(LAST_PAGE, unknownState.apiLastPage)
        assertEquals(
            "the conservative fallback: one app page per API window, so " +
                "lazy loading is preserved and nothing is guessed",
            30,
            appPageSizeOf(unknownState),
        )
        assertEquals(
            "and the page count is the metadata bound, rendered as unknown " +
                "total rather than as a number",
            LAST_PAGE,
            appPageCountOf(unknownState),
        )

        val pagesBefore = requestedPages(unknownClient)
        unknown.requestEpisodeRange(1, 50)
        testScheduler.advanceUntilIdle()
        assertEquals(
            "the range selector REFUSES instead of guessing a total",
            EpisodePaging.RangeRejection.UnknownTotal.message,
            unknown.uiState.value.batchError,
        )
        assertTrue("and it selected nothing", unknown.uiState.value.selectedNumbers.isEmpty())
        assertEquals("and it fetched nothing", pagesBefore, requestedPages(unknownClient))

        // 2. A source that really reports zero episodes.
        val zeroClient = FakeHttpClient().apply {
            stubJson(pagePattern(1), zeroEpisodePage())
        }
        val zero = newViewModel(zeroClient)
        load(zero, testScheduler, "sess-empty")

        val zeroState = zero.uiState.value
        assertNull("the open itself is not an error", zeroState.error)
        assertEquals("zero is a NUMBER, not an unknown", 0, zeroState.apiTotal)
        assertTrue(zeroState.episodes.isEmpty())

        zero.requestEpisodeRange(1, 1)
        testScheduler.advanceUntilIdle()
        assertEquals(
            "a real zero reads as an empty anime — the opposite of 'unknown'",
            "This anime has no episodes to select.",
            zero.uiState.value.batchError,
        )
    }

    // -----------------------------------------------------------------------
    // Regression — individual episode handling from a loaded page
    // -----------------------------------------------------------------------

    @Test
    fun `an episode on a later page is still selectable and resolvable on its own`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = pagedClient()
        val vm = newViewModel(client)
        load(vm, testScheduler)

        vm.goToPage(2)
        testScheduler.advanceUntilIdle()

        val row = displayEpisodeEpisodes(vm.uiState.value).first { it.number == 150f }
        vm.selectEpisode(row)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(
            "individual selection still works from every loaded app page",
            state.selectedEpisode,
        )
        assertEquals(150f, state.selectedEpisode?.number)
    }

    // -----------------------------------------------------------------------
    // Fixtures that are NOT the 250-episode paging title
    // -----------------------------------------------------------------------

    /** One page, 150 episodes, no `total` / `per_page` — the legacy shape. */
    private fun legacyLongPage(): String {
        val rows = (1..150).joinToString(",\n") { ordinal -> row(ordinal) }
        return """{"data": [$rows], "last_page": 1, "anime": {"title": "Legacy Long"}}"""
    }

    /** Page 1 of a 9-page title whose response carries NO `total`. */
    private fun unknownTotalPage(): String {
        val rows = (1..PER_PAGE).joinToString(",\n") { ordinal -> row(ordinal) }
        return """
            {"data": [$rows], "current_page": 1, "last_page": $LAST_PAGE,
            "anime": {"title": "Unknown Total"}}
        """.trimIndent()
    }

    /** A title the source explicitly reports as having no episodes. */
    private fun zeroEpisodePage(): String =
        """{"data": [], "total": 0, "per_page": 30, "current_page": 1, "last_page": 1,
            "anime": {"title": "Empty Anime"}}"""
}
