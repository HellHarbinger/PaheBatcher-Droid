package com.pahebatcher.app.notification

import com.pahebatcher.app.domain.download.FailureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-audio slice — the TEXT both failure notifications render, on the JVM.
 *
 * These assertions run against the very functions the Android builders call
 * ([DownloadNotificationManager.failureText],
 * [DownloadNotificationManager.batchSummaryText]), so notification wording is
 * pinned without needing a device: what a user reads is decided here, and the
 * device suites separately prove the notifications themselves post, stack and
 * navigate as before.
 *
 * Two properties are under test:
 *
 * 1. **only what the code can prove** — an audio LABEL from the persisted code
 *    and a TYPED category label; [FailureCategory.UNKNOWN] renders no category
 *    at all, so an unattributable failure never grows an invented cause;
 * 2. **nothing that can leak** — no raw error, no URL, no token ever reaches
 *    either line (AGENTS §10).
 */
class DownloadNotificationTextTest {

    // -----------------------------------------------------------------------
    // Per-episode failure line
    // -----------------------------------------------------------------------

    @Test
    fun `the failure line names the episode, the audio language and the typed reason`() {
        assertEquals(
            "Episode 05 · English · Stream resolution failed",
            DownloadNotificationManager.failureText(
                episodeTitle = "Episode 05",
                audio = "eng",
                category = FailureCategory.STREAM_RESOLUTION_FAILED,
            ),
        )
        assertEquals(
            "Episode 05 · Japanese · Assembly failed",
            DownloadNotificationManager.failureText(
                episodeTitle = "Episode 05",
                audio = "jpn",
                category = FailureCategory.ASSEMBLY_FAILED,
            ),
        )
    }

    @Test
    fun `an unknown failure stays generic instead of growing an invented cause`() {
        assertEquals(
            "no category, no guess — the audio is still real",
            "Episode 05 · English",
            DownloadNotificationManager.failureText(
                episodeTitle = "Episode 05",
                audio = "eng",
                category = FailureCategory.UNKNOWN,
            ),
        )
        assertEquals(
            "with nothing provable the line is exactly the pre-Slice-C text",
            "Episode 05",
            DownloadNotificationManager.failureText(
                episodeTitle = "Episode 05",
                audio = null,
                category = null,
            ),
        )
    }

    @Test
    fun `every category label renders and none of them is empty or URL-shaped`() {
        for (category in FailureCategory.entries) {
            val text = DownloadNotificationManager.failureText("Ep", "jpn", category)
            if (category == FailureCategory.UNKNOWN) {
                assertEquals("Ep · Japanese", text)
            } else {
                assertTrue("$category must carry a label", text.contains("·"))
            }
            assertFalse(
                "$category must never render a transport detail",
                text.contains("http", ignoreCase = true) ||
                    text.contains("cookie", ignoreCase = true) ||
                    text.contains("authorization", ignoreCase = true),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Batch summary line
    // -----------------------------------------------------------------------

    @Test
    fun `without an attributable reason the summary is the unchanged counts and series line`() {
        assertEquals(
            "3 of 12 episodes failed — Test Anime",
            DownloadNotificationManager.batchSummaryText(3, 12, "Test Anime"),
        )
        assertEquals(
            "an EMPTY breakdown changes nothing — unknown failures name no reason",
            "3 of 12 episodes failed — Test Anime",
            DownloadNotificationManager.batchSummaryText(3, 12, "Test Anime", emptyMap()),
        )
        assertEquals(
            "`episodes` belongs to the total, so a single failure needs no branch",
            "1 of 12 episodes failed — Test Anime",
            DownloadNotificationManager.batchSummaryText(1, 12, "Test Anime"),
        )
    }

    @Test
    fun `the reason breakdown is ordered by count then label and shows multiplicity`() {
        assertEquals(
            "Segment download failed ×2, Assembly failed",
            DownloadNotificationManager.reasonSummaryText(
                mapOf("Assembly failed" to 1, "Segment download failed" to 2),
            ),
        )
        assertEquals(
            "the same run always renders the same line whatever the map order was",
            DownloadNotificationManager.reasonSummaryText(
                linkedMapOf("Assembly failed" to 1, "Segment download failed" to 2),
            ),
            DownloadNotificationManager.reasonSummaryText(
                linkedMapOf("Segment download failed" to 2, "Assembly failed" to 1),
            ),
        )
    }

    @Test
    fun `the breakdown is capped with an explicit remainder rather than a silent truncation`() {
        val four = mapOf(
            "Assembly failed" to 1,
            "Disk full" to 1,
            "No downloadable content" to 1,
            "Segment download failed" to 1,
        )
        assertEquals(
            "exactly three labels, then the honest remainder",
            "Assembly failed, Disk full, No downloadable content +1 more",
            DownloadNotificationManager.reasonSummaryText(four),
        )
        assertEquals(
            "under the cap nothing is hidden",
            "Disk full ×3",
            DownloadNotificationManager.reasonSummaryText(mapOf("Disk full" to 3)),
        )
    }

    @Test
    fun `blank or zero reason entries are dropped rather than rendered as nothing`() {
        assertEquals(
            "a labelless or countless entry is not a reason",
            "",
            DownloadNotificationManager.reasonSummaryText(
                mapOf("" to 5, "Assembly failed" to 0),
            ),
        )
    }

    @Test
    fun `the summary carries the reasons after the counts and series`() {
        assertEquals(
            "3 of 12 episodes failed — Test Anime · Segment download failed ×2, Assembly failed",
            DownloadNotificationManager.batchSummaryText(
                3,
                12,
                "Test Anime",
                mapOf("Segment download failed" to 2, "Assembly failed" to 1),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // A2.1 (AQR-054): PendingIntent request-code namespace
    // -----------------------------------------------------------------------

    @Test
    fun `an episode request code never lands on a reserved negative code`() {
        // A raw episodeId.hashCode() CAN be negative ("polygenelubricants" is the
        // classic Int.MIN_VALUE hash), and the reserved codes are all negative —
        // so the masked code is the only thing keeping a per-episode PendingIntent
        // from colliding with the watchlist (-9001), batch-failure (-9002) or
        // batch-content (-9003) namespaces.
        val reserved = setOf(-9001, -9002, -9003)
        val samples = listOf("polygenelubricants", "ep-notif-abc", "ep", "0", "-1", "")
        for (id in samples) {
            val code = DownloadNotificationManager.requestCodeFor(id)
            // The mask's contract is NON-NEGATIVITY; every reserved namespace
            // code is negative (-9001 watchlist, -9002 media-session activity /
            // batch-failure id, -9003 batch-content), so non-negativity IS the
            // disjointness guarantee. Assert both halves of that implication —
            // the second one fails if a reserved code is ever added non-negative
            // (which would silently invalidate the first).
            assertTrue("request code for \"$id\" must be non-negative (was $code)", code >= 0)
            assertTrue(
                "reserved namespace codes must stay negative, or non-negativity stops implying disjointness",
                reserved.all { it < 0 },
            )
            assertEquals(
                "the request code is stable for a given episode id",
                code,
                DownloadNotificationManager.requestCodeFor(id),
            )
        }
    }
}
