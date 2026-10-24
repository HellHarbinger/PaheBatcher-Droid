package com.pahebatcher.app.domain.util

/**
 * Pure policy for API-driven episode pagination, bounded episode-range
 * selection and the 100-episode batch ceiling.
 *
 * No I/O and no Android types: every rule here is decided from the paginator
 * metadata the release API already returns (`total`, `per_page`, `last_page`)
 * plus the caller's request, so the ViewModel, the repository and the non-UI
 * batch callers all enforce exactly the same numbers.
 *
 * The API contract this is built on (observed on `api?m=release&id=…&sort=episode_asc&page=N`):
 *
 * * pages are **1-based** — `current_page` starts at 1;
 * * `per_page` is a fixed server-side window (30 by default) and the app does
 *   not send its own page size, so an API page is the FETCH unit — never
 *   automatically the visible one;
 * * a page's rows are ordered `episode_asc`, so ordinal position `p`
 *   (1-based, within one variant session's listing) lives on page
 *   `ceil(p / per_page)` — and ONLY that mapping is used to turn a requested
 *   ordinal range into API pages, never `episode number / 30`;
 * * `total` counts the rows of that session's listing.
 *
 * The APP page sits ON TOP of that window and is deliberately a different
 * number ([appPageSize]): the server still answers 30 rows per request, while
 * the screen shows 100 episodes per page for a series over 100 episodes and
 * 30 for one of 100 or fewer. One app page is therefore BUILT from the API
 * pages its ordinals span ([apiPagesForAppPage]) — an app page of 100 over a
 * window of 30 is four API pages, and consecutive app pages share the
 * boundary page, which is exactly why an already-open API page never costs a
 * second request.
 *
 * "Episode" in the range/batch rules below therefore means one ordinal
 * position of the API's stable `episode_asc` ordering — the mapping from an
 * ordinal to a stable episode id is simply "read the rows of the pages the
 * range covers", which is why a range never needs the whole listing in memory.
 */
object EpisodePaging {

    /** The hard ceiling on UNIQUE episodes one batch may enqueue. */
    const val MAX_BATCH_EPISODES = 100

    /** The server's default window; also the default visible page size. */
    const val DEFAULT_PAGE_SIZE = 30

    /**
     * The initial ELIGIBLE range's end for a known total: episodes
     * `1..min(total, 100)`. Over 100 the extra episodes stay reachable only
     * through an explicit, separately validated range — "select all" never
     * silently means the whole anime.
     */
    const val MAX_INITIAL_EPISODES = 100

    /**
     * Bound on how many API pages one listing keeps in memory at once
     * (LRU). A page holds at most one server window of rows, so this is a hard
     * ceiling on the in-memory episode list of a very large anime — the
     * accumulator is rebuilt from the retained pages only. The most recently
     * loaded pages (i.e. the range the user just requested) are always the
     * retained ones.
     */
    const val MAX_CACHED_PAGES = 16

    /** Why a requested episode range cannot be used. */
    sealed interface RangeRejection {
        val message: String

        /** The API response carried no usable `total` — never invented. */
        object UnknownTotal : RangeRejection {
            override val message: String = "The episode count is unknown, so that range can't be validated."
        }

        /** The range names no episode (a non-positive bound). */
        object Empty : RangeRejection {
            override val message: String = "That range contains no episodes."
        }

        /** `end < start`. */
        object Reversed : RangeRejection {
            override val message: String = "The range is reversed — the end is before the start."
        }

        /** The range spans more than [MAX_BATCH_EPISODES] episodes. */
        data class TooLarge(val size: Int) : RangeRejection {
            override val message: String =
                "A range may cover at most $MAX_BATCH_EPISODES episodes (this one covers $size)."
        }

        /** The range reaches past the API's `total`. `total = 0` = zero episodes. */
        data class OutOfBounds(val total: Int) : RangeRejection {
            override val message: String =
                if (total <= 0) "This anime has no episodes to select."
                else "That range reaches past the last episode ($total)."
        }
    }

    /** Why a batch execution refuses to start. */
    sealed interface BatchRejection {
        val message: String

        /** Nothing was selected. */
        object Empty : BatchRejection {
            override val message: String = "No episodes were selected."
        }

        /** More than [MAX_BATCH_EPISODES] UNIQUE episodes were supplied. */
        data class TooMany(val unique: Int) : BatchRejection {
            override val message: String =
                "A batch may contain at most $MAX_BATCH_EPISODES episodes (this one has $unique)."
        }

        /**
         * The batch referenced episodes that are NOT on the page it claims to
         * be scoped to — the execution-level guard behind "download THIS page".
         * [offenders] counts the distinct out-of-page episodes it saw; NOTHING
         * is queued, so a cross-page list can never be quietly trimmed into a
         * page batch.
         */
        data class OutsidePage(val offenders: Int) : BatchRejection {
            override val message: String =
                "That page only has its own episodes ($offenders of them aren't on it)."
        }
    }

    /**
     * The end of the initially eligible range, or `null` when the total is
     * unknown. `min(total, 100)` — a total of exactly 100 still yields 100.
     *
     * `null` (unknown) is deliberately NOT coerced to 0 or to some guessed
     * figure: callers must treat "unknown total" differently from
     * "zero episodes".
     */
    fun eligibleEnd(total: Int?): Int? =
        total?.let { if (it <= 0) 0 else minOf(it, MAX_INITIAL_EPISODES) }

    /**
     * The APP page size — how many episodes ONE visible page shows — decided
     * from the VERIFIED total, never from the API's HTTP window:
     *
     * * `total > 100` → [MAX_INITIAL_EPISODES] (100): a long series shows the
     *   first 100 episodes on one page and reaches the rest by paging or by a
     *   range. The number is deliberately the same as the initially ELIGIBLE
     *   window, so one app page is exactly one batchable window;
     * * `total <= 100` → [DEFAULT_PAGE_SIZE] (30): a short series is small
     *   enough that the server's own window already is the page. A title of
     *   exactly 100 follows this rule like any other at-or-below-100 title;
     * * `total == null` → [apiPerPage]: the conservative fallback. No total is
     *   invented, the unknown state stays exposed to the caller, and paging
     *   stays lazy — one app page per API window, the smallest page the app can
     *   build without guessing;
     * * no usable [apiPerPage] at all → `null`: there is no ordinal model, so
     *   paging is off and the loaded listing renders as one undivided result.
     *
     * A [total] is never coerced to zero here: a real `0` still yields the
     * 30-row rule (and an empty listing), which is a different answer from
     * "unknown".
     */
    fun appPageSize(total: Int?, apiPerPage: Int?): Int? {
        val api = apiPerPage ?: return null
        if (api <= 0) return null
        return when {
            total == null -> api
            total > MAX_INITIAL_EPISODES -> MAX_INITIAL_EPISODES
            else -> DEFAULT_PAGE_SIZE
        }
    }

    /**
     * The ordinals the listing REALLY has, as a bound neither invented nor
     * overstated: `total` when the source supplied one, capped by
     * `per_page × last_page` when the two together prove fewer rows can exist
     * (malformed metadata is never allowed to address ordinals that cannot be
     * fetched), and `per_page × last_page` alone when `total` is absent — an
     * upper bound the source itself stated, never a claim about the total.
     *
     * `null` when neither is usable: an unknown total stays unknown and is
     * never coerced to 0.
     */
    fun ordinalCountBound(total: Int?, apiPerPage: Int?, apiLastPage: Int?): Int? {
        val per = apiPerPage?.takeIf { it > 0 }
        val last = apiLastPage?.takeIf { it > 0 }
        val fromPages = if (per != null && last != null) per * last else null
        return when {
            total != null && fromPages != null -> minOf(total, fromPages)
            total != null -> total
            else -> fromPages
        }
    }

    /**
     * How many APP pages the listing has; at least 1, or `null` when it cannot
     * be decided (no usable API window). With an unknown total the count is
     * the metadata BOUND laid out in app pages — an upper bound, so the last
     * app page may legitimately hold fewer rows than its size.
     */
    fun appPageCount(total: Int?, apiPerPage: Int?, apiLastPage: Int?): Int? {
        val appPer = appPageSize(total, apiPerPage) ?: return null
        val bound = ordinalCountBound(total, apiPerPage, apiLastPage) ?: return null
        return if (bound <= 0) 1 else (bound + appPer - 1) / appPer
    }

    /**
     * The ordinals app page [appPage] covers — `[(p-1)·size+1, p·size]`,
     * possibly reaching past the last ordinal (a final short page simply holds
     * fewer rows; the range is never clamped into inventing any).
     */
    fun appPageOrdinalRange(appPage: Int, appPageSize: Int): IntRange {
        val size = if (appPageSize <= 0) DEFAULT_PAGE_SIZE else appPageSize
        val page = if (appPage < 1) 1 else appPage
        val first = (page - 1) * size + 1
        return first until (first + size)
    }

    /**
     * The API pages app page [appPage] is BUILT from — the server windows its
     * ordinal range touches, and the ONLY pages that may be fetched to show
     * it. This is the whole app-page/API-page mapping: an app page of 100 over
     * a window of 30 spans `1..4`, the next spans `4..7` (the boundary page is
     * shared, so it is requested once), and the caller clamps the top of the
     * range to the source's own `last_page` — a short final page never
     * addresses a window that does not exist.
     */
    fun apiPagesForAppPage(appPage: Int, appPageSize: Int, apiPerPage: Int): IntRange =
        appPageOrdinalRange(appPage, appPageSize)
            .let { pagesForRange(it.first, it.last, apiPerPage) }

    /**
     * The APP page numbers a pager should render for page [page] of [lastPage]
     * — always the first and last page plus a small window grown alternately
     * around the current one, so a 12-page title shows `1 2 3 … 12` on page 1,
     * `1 … 6 7 … 12` in the middle and `1 … 10 11 12` at the end, instead of
     * twelve cramped buttons.
     *
     * [maxButtons] caps how many NUMBERS are produced (the first and last page
     * are part of that budget); the caller renders an ellipsis wherever two
     * returned numbers are not adjacent, and a window can contain at most two
     * such gaps (one after page 1, one before the last page), so the row holds
     * at most `maxButtons + 2` items. The default of 4 is measured on a
     * phone-width row shared with Prev and Next: the ellipses are thin text but
     * each numbered button is wide, so a budget of 5 pushed the trailing number
     * (often the LAST page) past the row's edge, where it was clipped and could
     * not be tapped, while a budget of 4 keeps even the two-gap case inside the
     * row.
     *
     * The current page is always included, the result is ascending and
     * duplicate-free, and an out-of-range [page] is CLAMPED to [lastPage]
     * rather than inventing a page. A [lastPage] of zero or less yields an
     * empty list (no page exists), and a [lastPage] within the budget lists
     * every page.
     */
    fun pageNumberWindow(page: Int, lastPage: Int, maxButtons: Int = 4): List<Int> {
        if (lastPage < 1) return emptyList()
        if (lastPage <= maxButtons) return (1..lastPage).toList()
        val current = page.coerceIn(1, lastPage)
        val numbers = sortedSetOf(1, lastPage, current)
        var lower = current - 1
        var upper = current + 1
        while (numbers.size < maxButtons && (lower >= 2 || upper <= lastPage - 1)) {
            if (lower >= 2 && numbers.size < maxButtons) {
                numbers += lower
                lower--
            }
            if (upper <= lastPage - 1 && numbers.size < maxButtons) {
                numbers += upper
                upper++
            }
        }
        return numbers.toList()
    }

    /** The default visible page size (the server's own window). */
    fun defaultPageSize(): Int = DEFAULT_PAGE_SIZE

    /** Page count for a known total and page size; at least 1. */
    fun lastPageFor(total: Int, perPage: Int): Int =
        if (perPage <= 0 || total <= 0) 1 else (total + perPage - 1) / perPage

    /** The 1-based page holding ordinal [ordinal]; ordinals below 1 map to page 1. */
    fun pageForOrdinal(ordinal: Int, perPage: Int): Int =
        if (perPage <= 0 || ordinal <= 1) 1 else ((ordinal - 1) / perPage) + 1

    /**
     * Every page a `[start, end]` ordinal range touches — inclusive, and it
     * may cross page boundaries. Only these pages are ever fetched for the
     * range.
     */
    fun pagesForRange(start: Int, end: Int, perPage: Int): IntRange {
        if (perPage <= 0) return 1..1
        val lo = pageForOrdinal(start, perPage)
        val hi = pageForOrdinal(if (end < start) start else end, perPage)
        return lo..hi
    }

    /**
     * Validate a requested ordinal range.
     *
     * Order matters and is part of the contract: reversed is reported before
     * size (a backwards 1-episode-wide request is still reversed), size before
     * bounds (a 101-episode request is "too large" whatever the total is), and
     * bounds last. A `null` [total] returns [RangeRejection.UnknownTotal] —
     * the range is neither accepted nor silently clamped, and it is never
     * confused with a `total` of 0, which reports
     * [RangeRejection.OutOfBounds] instead.
     *
     * [available] is the ORDINAL COUNT BOUND the source's own metadata proves
     * (`per_page × last_page`, see [ordinalCountBound]), supplied by callers
     * that have it. When present it CAPS the bound used by the final check:
     * a range whose tail reaches past ordinals that can actually be fetched is
     * refused up front as [RangeRejection.OutOfBounds] — reported against the
     * real bound, never against a `total` that overstates what exists — so a
     * straddling range can neither be accepted and silently under-selected nor
     * claim a `total`-based bound it could never reach. It never WIDENS the
     * check (the bound is [total] capped by [available]), and `null` — the
     * default — keeps the plain-[total] behaviour exactly, so callers without
     * usable metadata are unaffected. An unknown [total] still short-circuits
     * to [RangeRejection.UnknownTotal] before [available] is consulted, so a
     * missing total is never invented from page metadata alone.
     */
    fun validateRange(start: Int, end: Int, total: Int?, available: Int? = null): RangeRejection? {
        if (total == null) return RangeRejection.UnknownTotal
        if (start <= 0 || end <= 0) return RangeRejection.Empty
        if (end < start) return RangeRejection.Reversed
        val size = end - start + 1
        if (size > MAX_BATCH_EPISODES) return RangeRejection.TooLarge(size)
        val bound = available?.let { minOf(it, total) } ?: total
        if (end > bound) return RangeRejection.OutOfBounds(bound)
        return null
    }

    /**
     * The batch execution boundary: validate the UNIQUE episodes a batch would
     * enqueue. Duplicate numbers — including duplicates produced by overlapping
     * page responses and audio variants of one number — are collapsed before
     * counting, so a request for 150 rows carrying 80 distinct episodes is
     * accepted as 80, while 101 distinct episodes is refused.
     *
     * @param pageScope when present, the episodes of the ONE page the batch     *   claims to be scoped to: any supplied number outside it is refused as
     *   [BatchRejection.OutsidePage] before the size check, so a page-scoped
     *   batch can neither widen itself to other pages nor slip past the
     *   ceiling. A `null` scope (a selection batch, the watchlist auto-download)
     *   keeps the previous, scope-free behaviour exactly.
     */
    fun validateBatch(numbers: Collection<Float>, pageScope: Set<Float>? = null): BatchRejection? {
        val unique = numbers.toSet()
        if (unique.isEmpty()) return BatchRejection.Empty
        if (pageScope != null) {
            val outside = unique.count { it !in pageScope }
            if (outside > 0) return BatchRejection.OutsidePage(outside)
        }
        if (unique.size > MAX_BATCH_EPISODES) return BatchRejection.TooMany(unique.size)
        return null
    }
}
