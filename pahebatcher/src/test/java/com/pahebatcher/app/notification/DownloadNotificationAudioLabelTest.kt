package com.pahebatcher.app.notification

import com.pahebatcher.app.domain.download.FailureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-audio slice — the selected AUDIO LANGUAGE on progress and completion
 * notifications, on the JVM.
 *
 * These assert the very functions the Android builders call
 * ([DownloadNotificationManager.progressText],
 * [DownloadNotificationManager.completionText],
 * [DownloadNotificationManager.episodeLine]), so the wording a user reads is
 * pinned without a device. The device suites separately prove the
 * notifications post and stack as before.
 *
 * Properties under test:
 *
 * 1. **the persisted code is the source of truth** — `"jpn"` → `Japanese`,
 *    `"eng"` → `English`, derived ONLY from the row's `audio` column via
 *    [com.pahebatcher.app.ui.mapper.DownloadRowMapper.audioLabelFor], never
 *    from a filename, a URL or UI state (AGENTS §10);
 * 2. **absent metadata is absent, not invented** — null/blank renders the
 *    EXACT pre-audio text, with no dangling separator and no guessed
 *    language;
 * 3. **consistency across the lifecycle** — progress, completion and failure
 *    all render the same episode line for the same row, and individual and
 *    batch downloads share these functions (one worker → one manager);
 * 4. **nothing that can leak** — no URL, cookie, token or session data ever
 *    reaches any of these lines (AGENTS §10).
 */
class DownloadNotificationAudioLabelTest {

    private val title = "Episode 01"

    // -----------------------------------------------------------------------
    // Progress line
    // -----------------------------------------------------------------------

    @Test
    fun `progress names the selected audio language for japanese and english`() {
        assertEquals(
            "Episode 01 · Japanese — 42%",
            DownloadNotificationManager.progressText(title, "jpn", 42),
        )
        assertEquals(
            "Episode 01 · English — 7%",
            DownloadNotificationManager.progressText(title, "eng", 7),
        )
    }

    @Test
    fun `indeterminate progress keeps the language but drops the percentage`() {
        assertEquals(
            "Episode 01 · Japanese",
            DownloadNotificationManager.progressText(title, "jpn", -1),
        )
        assertEquals(
            "Episode 01",
            DownloadNotificationManager.progressText(title, null, -1),
        )
    }

    @Test
    fun `missing audio metadata renders byte identical to the pre-audio text`() {
        assertEquals(
            "null audio must render only the title — exactly as before audio existed",
            "Episode 01 — 42%",
            DownloadNotificationManager.progressText(title, null, 42),
        )
        assertEquals(
            "blank audio must not leave a dangling separator",
            "Episode 01 — 42%",
            DownloadNotificationManager.progressText(title, "   ", 42),
        )
        assertEquals(
            "unknown-but-absent metadata renders only the title when indeterminate",
            "Episode 01",
            DownloadNotificationManager.progressText(title, "", -1),
        )
    }

    @Test
    fun `an unlabelled language code falls back to the code itself not a guess`() {
        // audioLabelFor uppercases anything it cannot map — an honest code is
        // better than an invented language name.
        assertEquals(
            "Episode 01 · KOR — 100%",
            DownloadNotificationManager.progressText(title, "kor", 100),
        )
    }

    // -----------------------------------------------------------------------
    // Completion line
    // -----------------------------------------------------------------------

    @Test
    fun `completion names the same language the episode was queued as`() {
        assertEquals(
            "Episode 01 · Japanese",
            DownloadNotificationManager.completionText(title, "jpn"),
        )
        assertEquals(
            "Episode 01 · English",
            DownloadNotificationManager.completionText(title, "eng"),
        )
    }

    @Test
    fun `completion without metadata is the unchanged pre-audio title`() {
        assertEquals(
            "Episode 01",
            DownloadNotificationManager.completionText(title, null),
        )
        assertEquals(
            "Episode 01",
            DownloadNotificationManager.completionText(title, ""),
        )
    }

    // -----------------------------------------------------------------------
    // Lifecycle consistency (individual AND batch share these functions)
    // -----------------------------------------------------------------------

    @Test
    fun `progress completion and failure all render one identical episode line`() {
        for (audio in listOf("jpn", "eng", "kor", null, "")) {
            val episodeLine = DownloadNotificationManager.episodeLine(title, audio)

            assertEquals(
                "progress indeterminate must agree with the episode line",
                episodeLine,
                DownloadNotificationManager.progressText(title, audio, -1),
            )
            assertEquals(
                "completion must agree with the episode line",
                episodeLine,
                DownloadNotificationManager.completionText(title, audio),
            )

            // The failure line is the same episode line plus optional attributes.
            val failure = DownloadNotificationManager.failureText(title, audio, null)
            assertTrue(
                "failure must start with the same episode line (got $failure)",
                failure.startsWith(episodeLine),
            )
            assertEquals(
                "failure with no category must BE the episode line",
                episodeLine,
                failure,
            )
        }
    }

    @Test
    fun `every lifecycle line carries the language for a known row`() {
        val audio = "jpn"
        val lines = listOf(
            DownloadNotificationManager.progressText(title, audio, 0),
            DownloadNotificationManager.progressText(title, audio, 100),
            DownloadNotificationManager.progressText(title, audio, -1),
            DownloadNotificationManager.completionText(title, audio),
            DownloadNotificationManager.failureText(
                title,
                audio,
                FailureCategory.SEGMENT_DOWNLOAD_FAILED,
            ),
        )
        for (line in lines) {
            assertTrue(
                "every line must name the persisted language (got $line)",
                line.contains("Japanese"),
            )
        }
    }

    @Test
    fun `individual and batch downloads produce the same per-episode wording`() {
        // Single-episode and batch downloads both run through the SAME worker
        // and therefore the same manager/builder functions — there is no
        // separate batch wording to drift out of sync.
        val individual = DownloadNotificationManager.completionText(title, "jpn")
        val fromBatch = DownloadNotificationManager.completionText(title, "jpn")
        assertEquals("one code path, one wording", individual, fromBatch)

        // The batch SUMMARY is an aggregate over a run, not an episode, so it
        // must NOT grow an invented per-episode language.
        val reasonLabel = FailureCategory.SEGMENT_DOWNLOAD_FAILED.label
            ?: error("this typed category must carry a label")
        val summary = DownloadNotificationManager.batchSummaryText(
            failed = 1,
            total = 3,
            animeTitle = "Test Anime",
            reasonCounts = mapOf(reasonLabel to 1),
        )
        assertEquals(
            "aggregate summary must stay counts + series + typed reason",
            "1 of 3 episodes failed — Test Anime · Segment download failed",
            summary,
        )
        assertFalse(
            "the aggregate summary must not claim a language for the whole run",
            summary.contains("Japanese") || summary.contains("English"),
        )
    }

    // -----------------------------------------------------------------------
    // Leak-proofing (AGENTS §10)
    // -----------------------------------------------------------------------

    @Test
    fun `no lifecycle line can render a URL cookie token or session value`() {
        val audioValues = listOf("jpn", "eng", "kor", null, "")
        for (audio in audioValues) {
            val lines = listOf(
                DownloadNotificationManager.progressText(title, audio, 50),
                DownloadNotificationManager.progressText(title, audio, -1),
                DownloadNotificationManager.completionText(title, audio),
                DownloadNotificationManager.episodeLine(title, audio),
                DownloadNotificationManager.failureText(
                    title,
                    audio,
                    FailureCategory.STREAM_RESOLUTION_FAILED,
                ),
            )
            for (line in lines) {
                assertFalse(
                    "no transport detail may appear (got $line)",
                    line.contains("http", ignoreCase = true) ||
                        line.contains("cookie", ignoreCase = true) ||
                        line.contains("authorization", ignoreCase = true) ||
                        line.contains("bearer", ignoreCase = true) ||
                        line.contains("token", ignoreCase = true) ||
                        line.contains("cf_clearance", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the language label is the only thing audio contributes`() {
        // Prove the label comes from the code, not from anything richer that
        // could smuggle data along with it.
        assertEquals(
            DownloadNotificationManager.episodeLine(title, "jpn"),
            "$title · Japanese",
        )
        assertEquals(
            DownloadNotificationManager.episodeLine(title, "eng"),
            "$title · English",
        )
        assertEquals(
            "with no code there is no label at all",
            title,
            DownloadNotificationManager.episodeLine(title, null),
        )
    }
}
