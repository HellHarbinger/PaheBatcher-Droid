package com.pahebatcher.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Post-audio slice — the RENDERING of observed scan progress.
 *
 * The point of these assertions is what the line refuses to say: no session
 * part without a session count, no page part before a page was fetched, and
 * never a denominator that did not come from the server.
 */
class ScanProgressTest {

    @Test
    fun `a full observation renders session and page with the server total`() {
        assertEquals(
            "Scanning episodes · session 2 of 3 · page 4 of 7",
            ScanProgress(
                sessionIndex = 2,
                sessionCount = 3,
                page = 4,
                pagesInSession = 7,
            ).describe(),
        )
    }

    @Test
    fun `a session that has not fetched a page yet claims no page at all`() {
        assertEquals(
            "the denominator is missing only because no page has been read",
            "Scanning episodes · session 1 of 1",
            ScanProgress(sessionIndex = 1, sessionCount = 1, page = 0).describe(),
        )
    }

    @Test
    fun `an unknown page total omits the denominator instead of guessing one`() {
        assertEquals(
            "a page without a server-declared total still reports what was read",
            "Scanning episodes · session 1 of 2 · page 3",
            ScanProgress(
                sessionIndex = 1,
                sessionCount = 2,
                page = 3,
                pagesInSession = null,
            ).describe(),
        )
    }

    @Test
    fun `every value round-trips through the data class unchanged`() {
        val progress = ScanProgress(
            sessionIndex = 1,
            sessionCount = 4,
            page = 2,
            pagesInSession = 9,
        )
        assertEquals(progress, progress.copy())
        assertEquals(9, progress.pagesInSession)
        assertEquals(2, progress.page)
        assertEquals(4, progress.sessionCount)
        assertEquals(1, progress.sessionIndex)
    }
}
