package com.pahebatcher.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lazy-loading slice — the pure paging / range / batch policy.
 *
 * Every rule in [EpisodePaging] is decided with no I/O, so this suite pins the
 * NUMBERS the ViewModel, the repository and the non-UI batch callers all share:
 *
 * 1. the initially eligible window is `1..min(total, 100)`, and an unknown
 *    total is never invented (and never confused with a real zero);
 * 2. an ordinal maps to a page by POSITION in the API's `episode_asc`
 *    ordering — never by `episode number / per_page`;
 * 3. a range may cover at most 100 episodes; 101+ is refused, never truncated;
 * 4. range rejections are ordered and mutually distinguishable;
 * 5. the batch ceiling counts UNIQUE episodes, so overlapping page responses
 *    cannot inflate or deflate it.
 */
class EpisodePagingTest {

    // -----------------------------------------------------------------------
    // 1. The initially eligible window
    // -----------------------------------------------------------------------

    @Test
    fun `the eligible window caps at 100 while an unknown total is never invented`() {
        assertEquals("a 1000-episode title starts at 1..100", 100, EpisodePaging.eligibleEnd(1000))
        assertEquals("exactly 100 episodes is still 100", 100, EpisodePaging.eligibleEnd(100))
        assertEquals("97 episodes stays 97", 97, EpisodePaging.eligibleEnd(97))
        assertEquals(
            "an empty title reports ZERO episodes",
            0,
            EpisodePaging.eligibleEnd(0),
        )
        assertNull(
            "an UNKNOWN total stays unknown — it is never coerced to 0, to 100 " +
                "or to any other guessed figure",
            EpisodePaging.eligibleEnd(null),
        )
    }

    // -----------------------------------------------------------------------
    // 2. Ordinal → page, and the page size itself
    // -----------------------------------------------------------------------

    @Test
    fun `an ordinal maps to a page by position in the ordering, never by episode number`() {
        assertEquals(1, EpisodePaging.pageForOrdinal(1, 30))
        assertEquals("the last row of a window is still page 1", 1, EpisodePaging.pageForOrdinal(30, 30))
        assertEquals(2, EpisodePaging.pageForOrdinal(31, 30))
        assertEquals(3, EpisodePaging.pageForOrdinal(61, 30))
        assertEquals(3, EpisodePaging.pageForOrdinal(90, 30))
        assertEquals(4, EpisodePaging.pageForOrdinal(91, 30))
        assertEquals(9, EpisodePaging.pageForOrdinal(250, 30))
        assertEquals("a malformed page size never escapes page 1", 1, EpisodePaging.pageForOrdinal(61, 0))

        // Requirement B: the server's 30-row window decides the page count for
        // every title at or under 100 episodes — a title of exactly 100 pages
        // the same way one of 97 does (ceil division, never a special case).
        assertEquals(4, EpisodePaging.lastPageFor(100, 30))
        assertEquals(
            "97 and 100 episodes page IDENTICALLY — the 30-row window decides, " +
                "so a full-100 title gets no special case",
            4,
            EpisodePaging.lastPageFor(97, 30),
        )
        assertEquals(2, EpisodePaging.lastPageFor(60, 30))
        assertEquals(3, EpisodePaging.lastPageFor(61, 30))
        assertEquals(1, EpisodePaging.lastPageFor(30, 30))
        assertEquals(1, EpisodePaging.lastPageFor(0, 30))
        assertEquals(30, EpisodePaging.lastPageFor(876, 30))
        assertEquals(30, EpisodePaging.defaultPageSize())
    }

    @Test
    fun `only the pages a range really spans are ever produced for it`() {
        assertEquals("1..100 is four 30-row windows", 1..4, EpisodePaging.pagesForRange(1, 100, 30))
        assertEquals("61..90 sits inside ONE window", 3..3, EpisodePaging.pagesForRange(61, 90, 30))
        assertEquals("31..100 crosses three windows", 2..4, EpisodePaging.pagesForRange(31, 100, 30))
        assertEquals(
            "a range far down the listing resolves from the WINDOW, " +
                "not from the rows already in memory",
            11..12,
            EpisodePaging.pagesForRange(301, 360, 30),
        )
    }

    // -----------------------------------------------------------------------
    // 3. The 100-episode range ceiling
    // -----------------------------------------------------------------------

    @Test
    fun `a range may cover at most 100 episodes and 101 is refused rather than truncated`() {
        assertNull(EpisodePaging.validateRange(1, 100, 1000))
        assertNull("a range starting down the listing is fine", EpisodePaging.validateRange(61, 160, 1000))

        val tooLarge = EpisodePaging.validateRange(1, 101, 1000)
        assertTrue(
            "101 episodes must be refused outright",
            tooLarge is EpisodePaging.RangeRejection.TooLarge,
        )
        assertEquals("and it says how many it covers", 101, (tooLarge as EpisodePaging.RangeRejection.TooLarge).size)

        assertTrue(
            "a range spanning the WHOLE series is the same refusal, not a clamp",
            EpisodePaging.validateRange(1, 250, 250) is EpisodePaging.RangeRejection.TooLarge,
        )
    }

    // -----------------------------------------------------------------------
    // 4. Rejection order and the unknown / zero distinction
    // -----------------------------------------------------------------------

    @Test
    fun `range rejections are ordered and unknown total never masquerades as zero`() {
        assertTrue(EpisodePaging.validateRange(5, 4, 10) is EpisodePaging.RangeRejection.Reversed)
        assertTrue(EpisodePaging.validateRange(0, 5, 10) is EpisodePaging.RangeRejection.Empty)
        assertTrue(EpisodePaging.validateRange(-3, 5, 10) is EpisodePaging.RangeRejection.Empty)

        val outOfBounds = EpisodePaging.validateRange(95, 150, 100)
        assertTrue(
            "inside the size ceiling but past the last episode → bounds",
            outOfBounds is EpisodePaging.RangeRejection.OutOfBounds,
        )
        assertEquals(100, (outOfBounds as EpisodePaging.RangeRejection.OutOfBounds).total)

        val sizeBeforeBounds = EpisodePaging.validateRange(1, 101, 100)
        assertTrue(
            "size is reported before bounds: a 101-episode request is TooLarge " +
                "whatever the total is",
            sizeBeforeBounds is EpisodePaging.RangeRejection.TooLarge,
        )

        assertTrue(
            "an unknown total refuses the range instead of guessing or clamping",
            EpisodePaging.validateRange(1, 50, null) is EpisodePaging.RangeRejection.UnknownTotal,
        )

        val zero = EpisodePaging.validateRange(1, 50, 0)
        assertTrue("a REAL zero is a different outcome", zero is EpisodePaging.RangeRejection.OutOfBounds)
        assertEquals(0, (zero as EpisodePaging.RangeRejection.OutOfBounds).total)
        assertEquals(
            "and it reads as an empty anime, not as an unknown one",
            "This anime has no episodes to select.",
            zero.message,
        )
    }

    @Test
    fun `a range past the metadata-proven ordinal bound is refused even when total overstates it`() {
        // total claims 250 rows but per_page x last_page proves only 120 can
        // ever be fetched — the bound CAPS the check, so a tail reaching past
        // 120 is refused up front instead of being accepted and under-selected.
        val capped = EpisodePaging.validateRange(101, 150, 250, available = 120)
        assertTrue(
            "the tail reaches ordinals the source's own metadata proves unfetchable",
            capped is EpisodePaging.RangeRejection.OutOfBounds,
        )
        assertEquals(
            "reported against the real bound, never against the overstated total",
            120,
            (capped as EpisodePaging.RangeRejection.OutOfBounds).total,
        )
        assertEquals(
            "so the message names the bound the user can actually reach",
            "That range reaches past the last episode (120).",
            capped.message,
        )

        assertNull(
            "a range ending exactly at the bound stays valid",
            EpisodePaging.validateRange(101, 120, 250, available = 120),
        )

        assertNull(
            "an available bound at or above the total never tightens the check",
            EpisodePaging.validateRange(61, 160, 250, available = 270),
        )

        assertNull(
            "no bound supplied keeps the plain-total behaviour exactly",
            EpisodePaging.validateRange(61, 160, 250),
        )

        assertTrue(
            "an unknown total still short-circuits before any bound is consulted",
            EpisodePaging.validateRange(1, 50, null, available = 120) is EpisodePaging.RangeRejection.UnknownTotal,
        )

        assertTrue(
            "the bound is consulted only AFTER the size ceiling, so order is unchanged",
            EpisodePaging.validateRange(1, 101, 250, available = 120) is EpisodePaging.RangeRejection.TooLarge,
        )
    }

    // -----------------------------------------------------------------------
    // 5. The batch execution ceiling
    // -----------------------------------------------------------------------

    @Test
    fun `the batch ceiling counts unique episodes so duplicated page rows cannot game it`() {
        assertNull("exactly 100 unique episodes is inside the ceiling", EpisodePaging.validateBatch((1..100).map { it.toFloat() }))

        val tooMany = EpisodePaging.validateBatch((1..101).map { it.toFloat() })
        assertTrue("101 unique episodes is refused", tooMany is EpisodePaging.BatchRejection.TooMany)
        assertEquals(
            "and the message counts the UNIQUE episodes it saw",
            101,
            (tooMany as EpisodePaging.BatchRejection.TooMany).unique,
        )

        // 240 rows carrying 80 distinct numbers — exactly what overlapping page
        // responses produce. It is accepted as 80, never as 240.
        val overlapping = mutableListOf<Float>()
        repeat(3) { overlapping += (1..80).map { it.toFloat() } }
        assertNull("duplicate numbers collapse before counting", EpisodePaging.validateBatch(overlapping))

        assertTrue(
            "an empty selection is its own refusal",
            EpisodePaging.validateBatch(emptyList()) is EpisodePaging.BatchRejection.Empty,
        )
    }

    // -----------------------------------------------------------------------
    // 6. The APP page — a different number from the API's HTTP window
    // -----------------------------------------------------------------------

    @Test
    fun `the app page size follows the verified total and never invents one`() {
        assertEquals("over 100 episodes the page IS the eligible 100", 100, EpisodePaging.appPageSize(250, 30))
        assertEquals("101 still pages at 100", 100, EpisodePaging.appPageSize(101, 30))
        assertEquals(
            "exactly 100 follows the AT-OR-BELOW-100 rule — 30 per page, no special case",
            30,
            EpisodePaging.appPageSize(100, 30),
        )
        assertEquals("97 pages at 30", 30, EpisodePaging.appPageSize(97, 30))
        assertEquals(
            "a REAL zero is still a number, so the ≤ 100 rule applies to it",
            30,
            EpisodePaging.appPageSize(0, 30),
        )

        assertEquals(
            "an UNKNOWN total falls back to the server's own window — lazy " +
                "(one app page per API page), so nothing is fetched eagerly and " +
                "nothing is guessed",
            30,
            EpisodePaging.appPageSize(null, 30),
        )
        assertNull(
            "with no usable API window there is no ordinal model at all, so " +
                "paging is simply off",
            EpisodePaging.appPageSize(250, null),
        )
        assertNull("a zero window is not a window", EpisodePaging.appPageSize(250, 0))
    }

    @Test
    fun `an app page is built from exactly the API windows its ordinals span`() {
        // The whole mapping, for an app page of 100 over the server's 30-row
        // window: FOUR windows, sharing the boundary with the next page.
        assertEquals("app page 1 = windows 1..4", 1..4, EpisodePaging.apiPagesForAppPage(1, 100, 30))
        assertEquals("app page 2 = windows 4..7 (4 is shared, so it is free)", 4..7, EpisodePaging.apiPagesForAppPage(2, 100, 30))
        assertEquals("app page 3 = windows 7..10", 7..10, EpisodePaging.apiPagesForAppPage(3, 100, 30))
        assertEquals("a 30-episode page is ONE window", 1..1, EpisodePaging.apiPagesForAppPage(1, 30, 30))
        assertEquals("ordinal 91 lives on window 4 alone", 4..4, EpisodePaging.apiPagesForAppPage(4, 30, 30))

        assertEquals("app page 1 holds ordinals 1..100", 1..100, EpisodePaging.appPageOrdinalRange(1, 100))
        assertEquals("app page 2 holds 101..200", 101..200, EpisodePaging.appPageOrdinalRange(2, 100))
        assertEquals("a final short page still names its whole range", 91..120, EpisodePaging.appPageOrdinalRange(4, 30))
    }

    @Test
    fun `the app page count is a metadata bound, never a guessed total`() {
        assertEquals("250 episodes are three pages of 100", 3, EpisodePaging.appPageCount(250, 30, 9))
        assertEquals("100 episodes are four pages of 30", 4, EpisodePaging.appPageCount(100, 30, 4))
        assertEquals(
            "with no total the count is per_page × last_page laid out in " +
                "pages — a bound the source stated, not a total it did not",
            9,
            EpisodePaging.appPageCount(null, 30, 9),
        )
        assertEquals("a real zero is one (empty) page", 1, EpisodePaging.appPageCount(0, 30, 1))
        assertNull(
            "an unknown total with no window cannot be paged at all",
            EpisodePaging.appPageCount(250, null, 9),
        )
        assertEquals(
            "malformed metadata (a total of 250 over two 30-row windows) can " +
                "only ever expose 60 ordinals, so it is ONE page — an ordinal " +
                "that cannot be fetched is never addressed",
            1,
            EpisodePaging.appPageCount(250, 30, 2),
        )
    }

    @Test
    fun `a page-scoped batch refuses any episode that is not on that page`() {
        val page = (1..30).map { it.toFloat() }.toSet()
        assertNull("the page's own episodes are exactly the batch", EpisodePaging.validateBatch(page, page))
        assertEquals(
            "a repeated reference to a page number is not an outsider",
            null,
            EpisodePaging.validateBatch(page.toList() + 7f + 7f, page),
        )

        val outside = EpisodePaging.validateBatch(page + 31f, page)
        assertTrue(
            "one episode from another page is refused outright, never trimmed",
            outside is EpisodePaging.BatchRejection.OutsidePage,
        )
        assertEquals("and it counts the offenders", 1, (outside as EpisodePaging.BatchRejection.OutsidePage).offenders)

        assertTrue(
            "an empty batch is refused before the scope is even considered",
            EpisodePaging.validateBatch(emptyList(), page) is EpisodePaging.BatchRejection.Empty,
        )
        assertEquals(
            "a full page of 100 is at the ceiling and is accepted",
            null,
            EpisodePaging.validateBatch((1..100).map { it.toFloat() }, (1..100).map { it.toFloat() }.toSet()),
        )
        val tooMany = EpisodePaging.validateBatch(
            (1..150).map { it.toFloat() },
            (1..150).map { it.toFloat() }.toSet(),
        )
        assertTrue(
            "a page-sized list over the ceiling is still refused by the ceiling",
            tooMany is EpisodePaging.BatchRejection.TooMany,
        )
        assertNull(
            "the watchlist's scope-free call is untouched by all of this",
            EpisodePaging.validateBatch((1..100).map { it.toFloat() }, null),
        )
    }

    @Test
    fun `the pager renders first last and a window of APP page numbers`() {
        // The device acceptance case: One Piece's 1,180 episodes over 100-episode
        // app pages is 12 pages. The pager must show numbered buttons, not just
        // a text indicator, and the numbers must be APP pages.
        assertEquals(
            "on page 1 the window is the spec's own example — the first " +
                "pages plus the last page, never twelve cramped buttons",
            listOf(1, 2, 3, 12),
            EpisodePaging.pageNumberWindow(page = 1, lastPage = 12),
        )
        assertEquals(
            "in the middle the window straddles the current page",
            listOf(1, 5, 6, 12),
            EpisodePaging.pageNumberWindow(page = 6, lastPage = 12),
        )
        assertEquals(
            "on the last page the window ends at the last page",
            listOf(1, 10, 11, 12),
            EpisodePaging.pageNumberWindow(page = 12, lastPage = 12),
        )
        assertTrue(
            "the current page is always among the rendered numbers",
            EpisodePaging.pageNumberWindow(page = 7, lastPage = 12).contains(7),
        )
        assertEquals(
            "an out-of-range page is CLAMPED to the real last page, never invented",
            EpisodePaging.pageNumberWindow(page = 12, lastPage = 12),
            EpisodePaging.pageNumberWindow(page = 99, lastPage = 12),
        )
        assertEquals(
            "a page below 1 clamps to the first page",
            EpisodePaging.pageNumberWindow(page = 1, lastPage = 12),
            EpisodePaging.pageNumberWindow(page = -4, lastPage = 12),
        )
        assertEquals(
            "a title that fits the budget lists every page, no ellipsis gaps",
            listOf(1, 2, 3, 4),
            EpisodePaging.pageNumberWindow(page = 2, lastPage = 4),
        )
        assertEquals(
            "a single-page title is that one page",
            listOf(1),
            EpisodePaging.pageNumberWindow(page = 1, lastPage = 1),
        )
        assertTrue(
            "a listing with no pages renders no page numbers at all",
            EpisodePaging.pageNumberWindow(page = 1, lastPage = 0).isEmpty(),
        )
        assertEquals(
            "the list is ascending and duplicate-free even when the window " +
                "would overlap the first or last page",
            listOf(1, 2, 3, 9),
            EpisodePaging.pageNumberWindow(page = 3, lastPage = 9),
        )
    }
}
