package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.remote.parser.AnimePaheParser
import com.pahebatcher.app.data.remote.parser.EpisodePageKind
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.ScanProgress
import com.pahebatcher.app.domain.util.EpisodePaging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Repository for episode scanning and discovery.
 * Ported from Python extract/scanner.py AnimePaheScanner.scan().
 *
 * Responsibilities:
 * - Scan episodes from API with pagination
 * - Deduplicate episodes by (number, audio) tuple
 * - Handle cache lookup and saving
 * - Support variant discovery (SUB/DUB)
 */
class EpisodeRepository(
    private val animeRepository: AnimeRepository,
    private val scanCacheRepository: ScanCacheRepository
) {
    companion object {
        /** Delay between API requests (matches Python REQUEST_DELAY) */
        const val REQUEST_DELAY_MS = 400L

        /**
         * DATA-003 — how many anime sessions may hold open pages in memory at
         * once. One session holds up to [EpisodePaging.MAX_CACHED_PAGES] pages
         * of rows; the least-recently-used session beyond this bound is
         * evicted, which is lossless: every committed page is also persisted
         * through `saveLazyListing`, so an evicted session reopens from its
         * lazy row. `internal` so the eviction contract is pinned by a unit
         * test.
         */
        internal const val MAX_CACHED_SESSIONS = 8

        /**
         * DECISIONS 144.2 — the bounded probe-target set for ONE scan, captured
         * from data the scan already produced (zero extra listing requests):
         *
         * * the LOWEST-numbered row of every merged session, in discovery order;
         * * plus the HIGHEST-numbered row of the OPENED session (index 0),
         *   appended LAST when it is not already one of the minimums.
         *
         * Deduplication is by `play_url`, so a single-episode opened session
         * yields exactly `N` targets (its maximum is its minimum) and every
         * other case yields `N + 1`, where `N` = merged sessions. Rows without a
         * `play_url` cannot be probed and are skipped, which only ever lowers
         * the bound.
         *
         * Determinism: [episodes] is the scan's own `(number, audio)` order, so
         * the minimum of a session is its first row in that order and two runs
         * over the same listing produce byte-identical target lists.
         *
         * Rows are joined to a variant session through their own `play_url`
         * (see [variantSession]), because `EpisodeInfo.session` is the
         * per-episode session — a different ID space from the sessions the
         * scan iterates.
         *
         * @param sessions merged episode sessions in DISCOVERY order; index 0 is
         *   the session the title page opened.
         * @param episodes the scan's sorted, deduplicated episode rows.
         */
        fun audioProbeTargets(sessions: List<String>, episodes: List<EpisodeInfo>): List<String> {
            val bySession = episodes
                .filter { it.playUrl.isNotBlank() }
                .groupBy { variantSession(it.playUrl) }
            val ordered = LinkedHashSet<String>()

            for (session in sessions) {
                val rows = bySession[session] ?: continue
                ordered += rows.minByOrNull { it.number }?.playUrl ?: continue
            }

            val opened = sessions.firstOrNull()
            if (opened != null) {
                val maximum = bySession[opened]?.maxByOrNull { it.number } ?: return ordered.toList()
                // "opened-maximum last": appended only when it is a NEW target,
                // which is exactly the `N` (not `N + 1`) single-episode case.
                if (!ordered.contains(maximum.playUrl)) {
                    ordered += maximum.playUrl
                }
            }
            return ordered.toList()
        }

        /**
         * The MERGED (variant) session a row belongs to, read from the row's own
         * `play_url` — `https://host/play/<variant>/<episode>`.
         *
         * [EpisodeInfo.session] is the per-EPISODE session, a different ID space
         * from the variant sessions the scan iterates, so it cannot be the join
         * key here. A `play_url` that does not carry that shape groups under `""`,
         * matches no variant session, and is therefore simply not probed — the
         * bound only ever shrinks.
         */
        private fun variantSession(playUrl: String): String =
            playUrl.substringAfter("/play/", "").substringBefore("/")
    }

    /**
     * Scan episodes for an anime.
     * Ported from Python AnimePaheScanner.scan().
     *
     * Flow:
     * 1. Check cache first (if TTL > 0)
     * 2. Discover all variants (SUB/DUB sessions)
     * 3. Fetch title from HTML
     * 4. Paginate through episode API for each session
     * 5. Deduplicate by (number, audio) tuple
     * 6. Save to cache — only when every required page was actually read
     *
     * Completion (`extract/scanner.py:252,267-290`): a page that is fetched and
     * readable but carries no `data` rows, or a page whose own `last_page` is
     * reached, ENDS the session successfully; a page that cannot be fetched or
     * read leaves required pages behind, and such a scan is returned but never
     * persisted.
     *
     * @param host AnimePahe domain
     * @param session Anime session UUID
     * @param cacheTtlMinutes Cache TTL in minutes (0 to disable)
     * @param onProgress Post-audio slice — receives each OBSERVED step of a
     *   real (non-cached) scan. Pure reporting: it is called for work already
     *   being done, never adds a request, and a cache hit calls it zero times
     *   because no page was fetched. Default: no-op, so every existing caller
     *   behaves exactly as before.
     * @return AnimeInfo with the episodes this scan really read, carrying
     *   [AnimeInfo.completed] `false` when pagination ended before every
     *   required page had been read (`extract/scanner.py:252,267-270`). Such a
     *   result is returned — like the reference — but it is never cached
     *   (`extract/scanner.py:295-302`), so callers must not treat it as an
     *   episode inventory.
     */
    suspend fun scan(
        host: String,
        session: String,
        cacheTtlMinutes: Int = 60,
        onProgress: (ScanProgress) -> Unit = {},
    ): AnimeInfo {
        // 1. Check cache first
        if (cacheTtlMinutes > 0) {
            val cached = scanCacheRepository.getCachedScan(session, cacheTtlMinutes)
            if (cached != null) {
                return cached
            }
        }

        // 2. Discover all variants (also extracts API title)
        val discoveryResult = animeRepository.discoverVariantsWithTitle(host, session)
        val allSessions = discoveryResult.sessions
        val apiTitle = discoveryResult.title

        // 3. Fetch title — prefer HTML title, fall back to API title
        val htmlTitle = animeRepository.fetchTitle(host, session)
        val title = if (htmlTitle != "Unknown Anime") htmlTitle else apiTitle.ifBlank { "Unknown Anime" }

        // 4. Create initial AnimeInfo
        var anime = AnimeInfo(
            session = session,
            title = title,
            host = host
        )

        // 5. Scan episodes from all sessions
        val uniqueEpisodes = mutableMapOf<Pair<Float, String>, EpisodeInfo>()

        // `extract/scanner.py:252` — completed_all: True until a required page
        // is left behind, and only a True value may reach the cache write.
        var completedAllPages = true

        // Lazy-loading slice — the FIRST readable page's paginator metadata,
        // carried through to [AnimeInfo] and the scan cache so a later load can
        // page without re-deriving (or inventing) the source's own numbers.
        var pageMeta: AnimePaheParser.ReleasePageMeta? = null

        allSessions.forEachIndexed { index, s ->
            val sessionNumber = index + 1
            val sessionTotal = allSessions.size
            // Post-audio slice: the session is about to be walked, and NO page
            // of it has been fetched — `page = 0` says exactly that instead of
            // claiming a page the scan has not read.
            onProgress(
                ScanProgress(
                    sessionIndex = sessionNumber,
                    sessionCount = sessionTotal,
                    page = 0,
                ),
            )
            var page = 1
            while (true) {
                val jsonData = animeRepository.fetchEpisodePage(host, s, page)
                if (jsonData == null) {
                    // Defence in depth: the WebView bridge now propagates
                    // CancellationException (NET-001), so a superseded scan
                    // normally dies before this point — re-check the job anyway
                    // before calling a null page a failed page, so teardown can
                    // never come back as a partial result.
                    currentCoroutineContext().ensureActive()
                    // `extract/scanner.py:267-279` — episodes past this page are
                    // missing, so this scan is incomplete and must not be cached.
                    completedAllPages = false
                    break
                }

                val pageKind = AnimePaheParser.classifyEpisodePage(jsonData)
                if (pageKind == EpisodePageKind.END_OF_PAGINATION) {
                    // `extract/scanner.py:280-281` — the source really has no
                    // further releases for this session: a COMPLETE, possibly
                    // empty, scan.
                    break
                }
                if (pageKind == EpisodePageKind.UNREADABLE) {
                    // `extract/scanner.py:267-270` — the response is not a page
                    // that can be read, so required pages are missing.
                    completedAllPages = false
                    break
                }

                val episodes = AnimePaheParser.parseEpisodePage(jsonData, host, s)

                if (pageMeta == null) {
                    pageMeta = AnimePaheParser.parsePageMeta(jsonData)
                }

                // Deduplicate by (number, audio) tuple
                for (ep in episodes) {
                    val key = ep.number to ep.audio
                    if (key !in uniqueEpisodes) {
                        uniqueEpisodes[key] = ep
                    }
                }

                // Check if we've reached the last page
                val lastPage = AnimePaheParser.getLastPage(jsonData)

                // Post-audio slice: THIS page is now fetched and parsed, and
                // `lastPage` is the server's own total from the very response
                // in hand — observed counts only, never a local guess. Emitted
                // before the break so the final page still reports itself.
                onProgress(
                    ScanProgress(
                        sessionIndex = sessionNumber,
                        sessionCount = sessionTotal,
                        page = page,
                        pagesInSession = lastPage,
                    ),
                )

                if (page >= lastPage) break
                page++

                // Delay between pages to avoid rate limiting
                delay(REQUEST_DELAY_MS)
            }
        }

        // 6. Build final AnimeInfo
        val sortedEpisodes = uniqueEpisodes.values.sortedWith(
            compareBy<EpisodeInfo> { it.number }
                .thenBy { it.audio }
        )

        anime = anime.copy(
            episodes = sortedEpisodes,
            total = sortedEpisodes.map { it.number }.distinct().size,
            // DECISIONS 144.2: the probe targets are derived from THIS scan and
            // persisted with the cache payload, so a cache hit re-probes exactly
            // the same deduplicated sessions. A fresh scan always starts the
            // probe over (the old result described the old episode set).
            audioProbeTargets = audioProbeTargets(allSessions, sortedEpisodes),
            audioProbe = null,
            // `extract/scanner.py:252,267-270`: the caller receives the
            // episodes really read, plus whether they are the whole listing.
            completed = completedAllPages,
            // Lazy-loading slice: the source's OWN paginator numbers as read,
            // plus the sessions this listing was merged from. Absent metadata
            // stays null — it is never substituted with a local guess.
            apiTotal = pageMeta?.total,
            perPage = pageMeta?.perPage,
            apiLastPage = pageMeta?.lastPage,
            variantSessions = allSessions,
        )

        // 7. Save to cache — ONLY a scan that read every required page may become
        // the cached truth (`extract/scanner.py:295-302`: a partial scan is never
        // cached, the user is told to re-run). An incomplete refresh therefore
        // neither overwrites a stored scan nor hands itself a fresh `cachedAt`,
        // and a legitimate empty page still caches as a completed scan.
        if (cacheTtlMinutes > 0 && completedAllPages) {
            scanCacheRepository.saveScanCache(anime, cacheTtlMinutes)
        }

        return anime
    }

    // ------------------------------------------------------------------
    // Lazy listing — open one page, fetch the rest only when asked
    // ------------------------------------------------------------------

    /**
     * ONE page could not be fetched or read.
     *
     * Page-scoped by construction: the pages already held stay usable and the
     * failure is reported against the page the caller asked for, instead of
     * being folded into [AnimeInfo.completed] (which would paint the whole
     * listing as broken) or silently returning a shorter page.
     *
     * @param page the 1-based page that failed
     */
    class PageLoadException(val page: Int, message: String) : IllegalStateException(message)

    /**
     * The pages deliberately opened for one anime, insertion-ordered and
     * bounded by [EpisodePaging.MAX_CACHED_PAGES] so a very large anime cannot
     * grow an unbounded in-memory episode list.
     *
     * Only ADDING a page evicts, and a page is appended last — so the range the
     * user just requested is always the retained one and the OLDEST opened page
     * is what is dropped. A dropped page is fetched again for real on the next
     * visit; it is never served from memory and never reported as complete when
     * it was not.
     *
     * DATA-003: the OUTER map (one entry per anime session ever opened in this
     * process) is now
     *
     * * bounded to [MAX_CACHED_SESSIONS] sessions with least-recently-used
     *   eviction — an entry holds up to [EpisodePaging.MAX_CACHED_PAGES] pages
     *   of rows, so one entry per session accumulated without limit for as long
     *   as the process lived; an evicted session loses nothing durable because
     *   every committed page is also persisted through `saveLazyListing` and
     *   reopens from that lazy row on the next visit; and
     * * guarded by [pagesLock] — `rowsForPage` reads from the UI thread while
     *   `loadListingPage` commits a page from a background coroutine, and an
     *   unsynchronized `HashMap` could tear or throw
     *   `ConcurrentModificationException` between the two.
     *
     * Every read/write below runs under [pagesLock] and NEVER holds it across a
     * suspension or network call.
     */
    private val pagesLock = Any()

    /**
     * Access-ordered so a `get` refreshes recency: the session the user is
     * looking at stays, the least-recently-used one is evicted first.
     * All access must hold [pagesLock].
     */
    private val openedPages =
        LinkedHashMap<String, MutableMap<Int, List<EpisodeInfo>>>(16, 0.75f, true)

    /**
     * Register one page's rows for [animeSession], evicting the oldest opened
     * pages past the per-session bound (and the least-recently-used session
     * past [MAX_CACHED_SESSIONS]). Holds [pagesLock] for the map work only.
     */
    private fun rememberPage(animeSession: String, page: Int, rows: List<EpisodeInfo>) {
        synchronized(pagesLock) {
            val pages = openedPages.getOrPut(animeSession) { LinkedHashMap() }
            pages[page] = rows
            while (pages.size > EpisodePaging.MAX_CACHED_PAGES) {
                val eldest = pages.keys.firstOrNull() ?: break
                pages.remove(eldest)
            }
            trimSessionsLocked()
        }
    }

    /** Evict least-recently-used sessions past the bound. Call under [pagesLock]. */
    private fun trimSessionsLocked() {
        while (openedPages.size > MAX_CACHED_SESSIONS) {
            val eldest = openedPages.keys.firstOrNull() ?: break
            openedPages.remove(eldest)
        }
    }

    /** A point-in-time copy of one session's open pages, or `null` if it holds none. */
    private fun openPagesSnapshot(animeSession: String): Map<Int, List<EpisodeInfo>>? =
        synchronized(pagesLock) { openedPages[animeSession]?.toMap() }

    /** True when [AnimeInfo.episodes] really holds EVERY page of the listing. */
    private fun AnimeInfo.holdsEveryPage(): Boolean {
        val last = apiLastPage ?: return false
        return loadedPages.size == last
    }

    /**
     * Open a listing LAZILY: read the paginator header and the FIRST page of
     * every variant session, and nothing else.
     *
     * [scan] stays unchanged for the callers that genuinely need a whole
     * inventory (the stream episode list, the watchlist, the reconstruction
     * ladder). This is the path the anime details screen uses, and it differs
     * in exactly three ways:
     *
     * 1. pages past the first are NOT fetched — a 1000-episode title costs a
     *    handful of requests instead of dozens, and no page beyond the first
     *    is ever read "because the screen opened";
     * 2. the OPENED session's last page IS read, but only so
     *    [audioProbeTargets] stays byte-for-byte what a full scan selects
     *    (DECISIONS 144.2 — the lowest row of every session, which page 1
     *    already holds, plus the opened session's highest row). Its rows are
     *    deliberately NOT part of the returned listing, so a later page load
     *    still has to ask for them like any other page;
     * 3. the scan cache is written only when page 1 WAS the whole listing, so
     *    a partial listing can never be read back as a complete one.
     *
     * ONE exception, and it is deliberate: when the source declares
     * `last_page > 1` but supplies no usable `per_page`, this call has no way
     * to ask for page 2 — so instead of handing back page 1 as if it were the
     * whole anime, it runs [scan] (the full inventory path) and returns that,
     * with [scan]'s own complete/partial semantics and nothing of the partial
     * open left in the cache. A listing that cannot be paged is never rendered
     * as a truncated one.
     *
     * A page 1 that cannot be fetched or read sets
     * [AnimeInfo.completed] `false` — the listing this call exposes is then
     * untrustworthy and the caller's existing failure branch handles it.
     * Losing only the extra probe row never does that: the listing itself is
     * intact and the bounded probe simply has one fewer sample.
     *
     * @param host AnimePahe domain
     * @param session Anime session UUID
     * @param cacheTtlMinutes Cache TTL in minutes (0 disables both read and write)
     * @param onProgress reports each OBSERVED step of a real (non-cached) open
     * @return an [AnimeInfo] whose [AnimeInfo.episodes] are page 1's rows,
     *   [AnimeInfo.loadedPages] is `{1}`, and whose `apiTotal` / `perPage` /
     *   `apiLastPage` carry the source's own numbers when it supplied them —
     *   or the FULL listing from [scan] when the source declared more pages
     *   than it described
     */
    suspend fun openListing(
        host: String,
        session: String,
        cacheTtlMinutes: Int = 60,
        onProgress: (ScanProgress) -> Unit = {},
    ): AnimeInfo {
        // 1. ANY fresh listing answers with zero requests — a complete row
        //    holds every page, a lazy row holds exactly the pages it stored.
        if (cacheTtlMinutes > 0) {
            scanCacheRepository.getListing(session, cacheTtlMinutes)?.let { cached ->
                // A lazy row's flat union cannot say which row belonged to
                // which page, so the per-page rows go straight into the
                // in-memory page cache and paging resumes where it left off.
                if (cached.pages.isNotEmpty()) {
                    cached.pages.forEach { (index, rows) -> rememberPage(session, index, rows) }
                }
                return cached.anime
            }
        }

        // 2. Variant discovery (this itself reads page 1 of the opened session)
        //    and the title — the same two steps [scan] starts with.
        val discovery = animeRepository.discoverVariantsWithTitle(host, session)
        val sessions = discovery.sessions
        val apiTitle = discovery.title
        val htmlTitle = animeRepository.fetchTitle(host, session)
        val title = if (htmlTitle != "Unknown Anime") htmlTitle else apiTitle.ifBlank { "Unknown Anime" }

        onProgress(ScanProgress(sessionIndex = 1, sessionCount = sessions.size, page = 0))

        val opened = sessions.first()
        val firstPageRows = LinkedHashMap<Pair<Float, String>, EpisodeInfo>()
        var meta: AnimePaheParser.ReleasePageMeta? = null
        var readable = true

        // 3. FIRST page of every session — the minimum that makes the first
        //    visible page correct across SUB/DUB, never more.
        for (s in sessions) {
            val json = animeRepository.fetchEpisodePage(host, s, 1)
            if (json == null) {
                // Same cancellation guard as [scan]: a superseded open must die
                // as CancellationException, not come back as a partial listing.
                currentCoroutineContext().ensureActive()
                readable = false
                continue
            }
            if (AnimePaheParser.classifyEpisodePage(json) == EpisodePageKind.UNREADABLE) {
                readable = false
                continue
            }
            if (s == opened) meta = AnimePaheParser.parsePageMeta(json)
            for (ep in AnimePaheParser.parseEpisodePage(json, host, s)) {
                // First-wins in discovery order — the rule [scan] applies.
                firstPageRows.putIfAbsent(ep.number to ep.audio, ep)
            }
        }

        val lastPage = meta?.lastPage ?: 1
        val perPage = meta?.perPage

        // 4. The source declares MORE pages than it can describe: with no usable
        //    window there is no way to ask for page 2, so page 1 would be
        //    rendered as the whole anime — exactly the truncated inventory the
        //    reference refuses to show (`extract/scanner.py:267-279,295-302`).
        //    Hand this open to the FULL inventory path instead, which carries
        //    the existing complete/partial semantics and caches only a listing
        //    it really finished. Nothing of the partial open above is cached
        //    (step 6 runs after this branch), so no caller can ever read it back
        //    as complete, and the last-page probe below is skipped because a
        //    full scan reads every page anyway. Rare by construction: the
        //    release API ships `per_page`, so this is a response that omitted or
        //    malformed it — never a case to guess a window size for.
        if (readable && lastPage > 1 && perPage == null) {
            return scan(host, session, cacheTtlMinutes, onProgress)
        }

        // 5. OBSERVED progress for this lazy path: every session's first page is
        //    now really read, and the denominator is the server's own page total
        //    from a response already in hand. `page = 0` above said "started,
        //    nothing read yet"; claiming anything beyond this costs no request
        //    (AGENTS §10) and no total is ever estimated.
        if (readable) {
            onProgress(
                ScanProgress(
                    sessionIndex = sessions.size,
                    sessionCount = sessions.size,
                    page = 1,
                    pagesInSession = meta?.lastPage,
                ),
            )
        }

        // 6. The opened session's LAST page, for the probe target set ONLY.
        val probeRows = ArrayList<EpisodeInfo>(firstPageRows.values)
        if (lastPage > 1) {
            val json = animeRepository.fetchEpisodePage(host, opened, lastPage)
            if (json != null && AnimePaheParser.classifyEpisodePage(json) != EpisodePageKind.UNREADABLE) {
                probeRows += AnimePaheParser.parseEpisodePage(json, host, opened)
            }
        }

        val pageOneRows = firstPageRows.values
            .sortedWith(compareBy({ it.number }, { it.audio }))

        // Page 1 IS the listing this call exposes, so it is registered as an
        // opened page — otherwise a later [rowsForPage] would report page 1 as
        // never opened and the screen would render an empty window.
        if (readable) rememberPage(session, 1, pageOneRows)

        val anime = AnimeInfo(
            session = session,
            title = title,
            host = host,
            episodes = pageOneRows,
            audioProbeTargets = audioProbeTargets(sessions, probeRows),
            completed = readable,
            apiTotal = meta?.total,
            perPage = meta?.perPage,
            apiLastPage = meta?.lastPage,
            variantSessions = sessions,
            loadedPages = if (readable) setOf(1) else emptySet(),
        )

        // 7. Persist what was really read, and only that: a listing that turned
        //    out to be the whole thing is stored COMPLETE (so every other caller
        //    may use it), a multi-page listing is stored LAZY (so no other caller
        //    can ever mistake page 1 for an inventory). A listing whose page 1
        //    was unreadable is stored nowhere.
        if (cacheTtlMinutes > 0 && readable) {
            if (lastPage <= 1) {
                scanCacheRepository.saveScanCache(anime, cacheTtlMinutes)
            } else {
                scanCacheRepository.saveLazyListing(anime, cacheTtlMinutes, mapOf(1 to pageOneRows))
            }
        }
        return anime
    }

    /**
     * Fetch ONE more page of an already-opened listing and return the listing
     * rebuilt from the pages still held.
     *
     * Fetches [page] from EVERY variant session (a window has to be correct in
     * both SUB and DUB), deduplicates by `(number, audio)` first-wins in
     * discovery order, and pages are addressed by the API's own 1-based index.
     *
     * A page that is already in memory is served from memory with **zero**
     * requests; a page whose fetch fails throws [PageLoadException] and is
     * **never** written to the page cache, so a failed page can never become a
     * completed cache entry and a retry re-reads it for real.
     *
     * @param host AnimePahe domain
     * @param anime the listing returned by [openListing] (or a previous
     *   [loadListingPage]) — supplies the session, the variant sessions and the
     *   paginator metadata
     * @param page 1-based page index, must be inside `1..apiLastPage`
     * @throws PageLoadException when any required variant page could not be read
     */
    suspend fun loadListingPage(
        host: String,
        anime: AnimeInfo,
        page: Int,
        cacheTtlMinutes: Int = 60,
    ): AnimeInfo {
        val lastPage = anime.apiLastPage ?: return anime
        if (page < 1 || page > lastPage) return anime
        if (anime.holdsEveryPage()) return anime

        val session = anime.session
        // DATA-003: a locked, non-mutating snapshot — the early "already open"
        // answer (the parity-sensitive containsKey serving rule) is unchanged,
        // it just no longer creates an empty entry as a side effect.
        val openSoFar = openPagesSnapshot(session)
        if (openSoFar != null && openSoFar.containsKey(page)) return anime.withRetainedPages(openSoFar)

        val sessions = anime.variantSessions.ifEmpty { listOf(anime.session) }
        val rows = LinkedHashMap<Pair<Float, String>, EpisodeInfo>()
        var failures = 0

        sessions.forEachIndexed { index, s ->
            if (index > 0) delay(REQUEST_DELAY_MS)
            val json = animeRepository.fetchEpisodePage(host, s, page)
            if (json == null) {
                currentCoroutineContext().ensureActive()
                failures++
                return@forEachIndexed
            }
            if (AnimePaheParser.classifyEpisodePage(json) == EpisodePageKind.UNREADABLE) {
                failures++
                return@forEachIndexed
            }
            for (ep in AnimePaheParser.parseEpisodePage(json, host, s)) {
                rows.putIfAbsent(ep.number to ep.audio, ep)
            }
        }

        if (failures > 0) {
            // NOTHING of this page reaches the cache: a failed/partial page is
            // not a complete page, and the next attempt must fetch it again.
            throw PageLoadException(page, "Episode page $page could not be read.")
        }

        // DATA-003: commit + rebuild + persist against ONE locked snapshot, so
        // the rebuilt listing and the cache row describe the same committed
        // state even if another coroutine commits or evicts around us.
        val committed = synchronized(pagesLock) {
            val pages = openedPages.getOrPut(anime.session) { LinkedHashMap() }
            pages[page] = rows.values.sortedWith(compareBy({ it.number }, { it.audio }))
            while (pages.size > EpisodePaging.MAX_CACHED_PAGES) {
                val eldest = pages.keys.firstOrNull() ?: break
                pages.remove(eldest)
            }
            trimSessionsLocked()
            pages.toMap()
        }
        val opened = anime.withRetainedPages(committed)
        // Persist the pages really read (never a page that failed, never a
        // listing already stored complete — [saveLazyListing] refuses both),
        // so a recreated process resumes from them instead of re-fetching.
        scanCacheRepository.saveLazyListing(opened, cacheTtlMinutes, committed)
        return opened
    }

    /**
     * The rows belonging to ONE page of [anime], without any I/O.
     *
     * * paging metadata absent (`apiLastPage`/`perPage` unknown) → `null`: the
     *   caller shows the whole loaded listing as one undivided result, which is
     *   exactly the pre-lazy behaviour and what an unknown-total response means;
     * * the listing holds every page (a complete cache read, or every page
     *   visited) → sliced straight from [AnimeInfo.episodes], no cache needed;
     * * otherwise → the rows held for that page, or `emptyList()` when it has
     *   not been opened yet.
     */
    fun rowsForPage(anime: AnimeInfo, page: Int): List<EpisodeInfo>? {
        val perPage = anime.perPage ?: return null
        val lastPage = anime.apiLastPage ?: return null
        if (page < 1 || page > lastPage) return null
        if (perPage <= 0) return null

        if (anime.holdsEveryPage()) {
            val numbers = anime.episodes.map { it.number }.distinct().sorted()
            val window = numbers.drop((page - 1) * perPage).take(perPage)
            if (window.isEmpty()) return emptyList()
            val wanted = window.toSet()
            return anime.episodes.filter { it.number in wanted }
                .sortedWith(compareBy({ it.number }, { it.audio }))
        }
        // DATA-003: non-mutating locked read — never creates an entry, never
        // races a background commit's eviction.
        return synchronized(pagesLock) { openedPages[anime.session]?.get(page) }.orEmpty()
    }

    /**
     * Rebuild a listing from the pages still retained for it.
     *
     * The rebuilt [AnimeInfo.episodes] is exactly the union of the retained
     * pages, so what the screen resolves against and what the page cache holds
     * can never drift apart — and because the page cache is bounded, this list
     * is bounded with it.
     */
    /** Rebuild a listing from the pages still retained for it. */
    private fun AnimeInfo.withRetainedPages(pages: Map<Int, List<EpisodeInfo>>): AnimeInfo {
        val loaded = pages.keys.filter { pages[it] != null }.sorted()
        val rows = loaded
            .flatMap { pages.getValue(it) }
            .distinctBy { it.number to it.audio }
            .sortedWith(compareBy({ it.number }, { it.audio }))
        return copy(episodes = rows, loadedPages = loaded.toSet())
    }
}