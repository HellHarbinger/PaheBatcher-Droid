package com.pahebatcher.app.domain.model

/**
 * Post-audio slice — the OBSERVED progress of one `EpisodeRepository.scan`.
 *
 * A scan paginates a listing API that reports its own page totals, across a
 * set of merged episode sessions the scan discovers up front. Until now none of
 * that reached the screen, which could only show an indeterminate spinner —
 * honest, but blind: the user had no way to tell a scan that is progressing
 * from one that is stuck.
 *
 * ## What each field is allowed to mean
 *
 * * [sessionIndex] — the 1-based position of the session being fetched, in the
 *   order the scan discovered it.
 * * [sessionCount] — how many sessions that discovery produced. Known before
 *   any episode page is fetched, because variant discovery runs first.
 * * [page] — how many pages of the CURRENT session have actually been fetched
 *   and parsed. `0` means "this session has started, no page of it yet".
 * * [pagesInSession] — the SERVER's own total-page count for this session,
 *   read from the pagination metadata of a response the scan already has in
 *   hand. `null` until that response arrives — this is never guessed, never
 *   interpolated from a local window size and never estimated.
 *
 * ## What it deliberately does NOT do
 *
 * * no fabricated totals — if the server has not said how many pages exist,
 *   [pagesInSession] stays `null` and the renderer omits the denominator;
 * * no extra requests — every number here comes from work the scan was already
 *   doing; progress costs nothing (AGENTS §10);
 * * no claim on a cache hit — a scan served from cache emits NOTHING, because
 *   no page was fetched and no progress happened.
 *
 * Pure data: no coroutines, no clock, no Android types, so it is trivially
 * testable and safe to pass across the repository → ViewModel boundary.
 */
data class ScanProgress(
    /** 1-based position of the session currently being fetched. */
    val sessionIndex: Int,
    /** How many merged sessions this scan will walk; `0` only if discovery found none. */
    val sessionCount: Int,
    /** Pages of the current session fetched so far; `0` = none yet. */
    val page: Int,
    /** The server-reported page total for this session, or `null` if not yet known. */
    val pagesInSession: Int? = null,
) {
    /**
     * The one-line, user-facing rendering: `Scanning episodes · session 1 of 2
     * · page 3 of 7`.
     *
     * Every clause is conditional on a value that really exists — no session
     * part without a session count, no denominator without a server-reported
     * total — so the string can never promise a scope the scan did not report.
     */
    fun describe(): String {
        val session = if (sessionCount > 0) {
            "session $sessionIndex of $sessionCount"
        } else {
            "session $sessionIndex"
        }
        val pagePart = when {
            page <= 0 -> null
            pagesInSession != null && pagesInSession > 0 -> "page $page of $pagesInSession"
            else -> "page $page"
        }
        return listOf("Scanning episodes", session, pagePart)
            .filterNotNull()
            .joinToString(" · ")
    }
}
