package com.pahebatcher.app.worker

import com.pahebatcher.app.data.hls.DownloadErrorClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage of
 * `DownloadWorker.shouldAutoResume`, the ONE pure eligibility decision.
 *
 * The decision is deliberately free of Android: given a typed class, the
 * persisted setting and WorkManager's `runAttemptCount`, it answers "may this
 * failure park the row and return `Result.retry()`?" without touching Room,
 * WorkManager or the DataStore. What each of those three sources reports is
 * verified on the device (`AutoResumeConnectivityTest`, the retry-bound device
 * fixture); what the DECISION does with them is verified here.
 *
 * Bound contract asserted explicitly (§15, reviewer M1): `runAttemptCount` is
 * **0 on the first run**, so counts 0..6 are runs 1..7 and remain eligible,
 * while count 7 (the 8th run) whose failure is still TRANSPORT takes the
 * terminal path. Nothing else — not the setting, not the class — may widen that.
 */
class AutoResumeEligibilityTest {

    @Test
    fun `the shipped bound is 7 attempts`() {
        assertEquals(
            "§15: runs 1-7 retry, the 8th run (count 7) is terminal",
            7,
            DownloadWorker.AUTO_RESUME_MAX_RETRIES,
        )
    }

    // ── The happy path across the whole budget ───────────────────────

    @Test
    fun `the first transport failure with the setting ON is eligible`() {
        assertTrue(
            DownloadWorker.shouldAutoResume(
                errorClass = DownloadErrorClass.TRANSPORT,
                enabled = true,
                runAttemptCount = 0,
            ),
        )
    }

    @Test
    fun `the last eligible transport failure is attempt 7`() {
        assertTrue(
            "count 6 is run 7 — still inside the budget",
            DownloadWorker.shouldAutoResume(
                errorClass = DownloadErrorClass.TRANSPORT,
                enabled = true,
                runAttemptCount = DownloadWorker.AUTO_RESUME_MAX_RETRIES - 1,
            ),
        )
    }

    @Test
    fun `attempt 8 takes the terminal path`() {
        assertFalse(
            "count 7 is run 8 — the bound, so the existing terminal path runs",
            DownloadWorker.shouldAutoResume(
                errorClass = DownloadErrorClass.TRANSPORT,
                enabled = true,
                runAttemptCount = DownloadWorker.AUTO_RESUME_MAX_RETRIES,
            ),
        )
    }

    @Test
    fun `a count past the bound never becomes eligible again`() {
        assertFalse(
            DownloadWorker.shouldAutoResume(
                errorClass = DownloadErrorClass.TRANSPORT,
                enabled = true,
                runAttemptCount = DownloadWorker.AUTO_RESUME_MAX_RETRIES + 1,
            ),
        )
    }

    // ── The setting gate (default OFF) ───────────────────────────────

    @Test
    fun `the setting OFF keeps every transport failure terminal`() {
        // The shipped default — upgrading must not change anyone's behavior
        // (DECISIONS 105.5), so this is the path every existing user takes.
        for (attempt in 0..DownloadWorker.AUTO_RESUME_MAX_RETRIES) {
            assertFalse(
                "attempt $attempt must be terminal while auto-resume is OFF",
                DownloadWorker.shouldAutoResume(
                    errorClass = DownloadErrorClass.TRANSPORT,
                    enabled = false,
                    runAttemptCount = attempt,
                ),
            )
        }
    }

    @Test
    fun `the setting OFF wins over every other input`() {
        assertFalse(
            DownloadWorker.shouldAutoResume(
                errorClass = DownloadErrorClass.EXPIRED,
                enabled = false,
                runAttemptCount = 0,
            ),
        )
    }

    // ── The class gate (§14: typed field only) ───────────────────────

    @Test
    fun `only TRANSPORT is eligible when the setting is ON`() {
        val nonTransport = listOf(
            DownloadErrorClass.NONE,
            DownloadErrorClass.EXPIRED,
            DownloadErrorClass.NO_CONTENT,
            DownloadErrorClass.ASSEMBLY,
            DownloadErrorClass.IN_RUN_RETRYABLE,
        )
        for (errorClass in nonTransport) {
            assertFalse(
                "$errorClass is deterministic or local and must stay terminal",
                DownloadWorker.shouldAutoResume(
                    errorClass = errorClass,
                    enabled = true,
                    runAttemptCount = 0,
                ),
            )
        }
        assertTrue(
            DownloadWorker.shouldAutoResume(
                errorClass = DownloadErrorClass.TRANSPORT,
                enabled = true,
                runAttemptCount = 0,
            ),
        )
    }

    @Test
    fun `success is never auto-resume eligible`() {
        assertFalse(
            DownloadWorker.shouldAutoResume(
                errorClass = DownloadErrorClass.NONE,
                enabled = true,
                runAttemptCount = 0,
            ),
        )
    }

    // ── Inputs never combine into an off-by-one ──────────────────────

    @Test
    fun `every combination at the boundary is decided by the bound alone`() {
        // ON + TRANSPORT at 6 → true, at 7 → false: the bound is the ONLY
        // thing that flips the answer, not the class or the setting.
        assertEquals(
            DownloadWorker.shouldAutoResume(DownloadErrorClass.TRANSPORT, true, 6),
            !DownloadWorker.shouldAutoResume(DownloadErrorClass.TRANSPORT, true, 7),
        )
        // A negative count (never reported by WorkManager, but reachable from a
        // caller) is inside the budget rather than crashing on comparison.
        assertTrue(DownloadWorker.shouldAutoResume(DownloadErrorClass.TRANSPORT, true, -1))
    }
}
