package com.pahebatcher.app.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The §29 completed-output rule that decides the reported redownload behaviour.
 *
 * [EpisodeOutputVerifier.isPresent] is the ONE predicate behind both
 * `DownloadRepository.suppressedOutcome` (the single-episode guard) and
 * `DownloadRepository.isEpisodeAlreadyHandled` (the batch/watchlist pre-check),
 * and it is what the startup reconciler reads too — so pinning it here pins the
 * decision for every entry point at once.
 *
 * The reported symptom was: exported file still present, segment cache deleted,
 * re-request produces no new file. These tests prove that outcome is the RULE
 * and not corruption:
 *
 *  - a real, non-empty output is reported present → the submission is suppressed
 *    (`AlreadyDownloaded`, no bytes move);
 *  - deleting the SEGMENT CACHE alone never invalidates that export, because the
 *    rule never consults the cache;
 *  - deleting the OUTPUT is what makes the rule report it missing, which is the
 *    only thing that lets the legitimate re-queue run.
 *
 * Constructed with `contentResolver = null`, which is the documented
 * host-constructed fallback: a `content://` reference nobody can resolve is
 * never treated as proof. Production always injects the real resolver
 * (`AppContainer`), and the URI branch itself is covered on-device by
 * `QueueResolvedEpisodeGuardTest` / `DownloadStateReconcilerTest`.
 */
class EpisodeOutputVerifierRuleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val verifier = EpisodeOutputVerifier(contentResolver = null)

    private fun file(name: String, bytes: Int): File {
        val f = tmp.newFile(name)
        if (bytes > 0) f.writeBytes(ByteArray(bytes) { 0x42 })
        return f
    }

    // -----------------------------------------------------------------------
    // Case (a)/(b): a valid export stays recognised as completed
    // -----------------------------------------------------------------------

    @Test
    fun `a real non-empty output is recognised as already complete`() {
        val output = file("ok.mp4", 4096)
        assertTrue(
            "a valid export must be reported present so the submission is suppressed",
            verifier.isPresent(outputUri = null, outputPath = output.absolutePath),
        )
    }

    @Test
    fun `deleting the segment cache alone never invalidates a valid export`() {
        // The exact reported scenario: assembled + exported, then the cache went.
        val episodeDir = tmp.newFolder("episode_1_jpn")
        val export = File(episodeDir, "Ep_001.mp4")
        export.writeBytes(ByteArray(2048) { 0x7F })
        val segments = List(3) { i -> File(episodeDir, "seg_%03d.ts".format(i)) }
        segments.forEach { it.writeBytes(ByteArray(188) { 0x47 }) }

        assertTrue(
            "present before the cache is removed",
            verifier.isPresent(null, export.absolutePath),
        )

        segments.forEach { assertTrue("fixture cleanup", it.delete()) }

        assertTrue(
            "the exported file is untouched, so the cache removal must NOT " +
                "invalidate it — this is what makes a re-request a no-op",
            verifier.isPresent(null, export.absolutePath),
        )
        assertFalse(
            "the cache really is gone",
            segments.any { it.exists() },
        )
    }

    @Test
    fun `the rule never looks at a sibling cache directory`() {
        val episodeDir = tmp.newFolder("episode_2_jpn")
        val export = File(episodeDir, "Ep_002.mp4")
        export.writeBytes(ByteArray(1024) { 0x11 })

        // With no cache directory at all the export still counts.
        assertTrue(verifier.isPresent(null, export.absolutePath))

        // And an unrelated missing cache path changes nothing, because the
        // predicate accepts only the URI and the output path.
        assertTrue(verifier.isPresent(null, export.absolutePath))
    }

    // -----------------------------------------------------------------------
    // Case (c): DB says completed but the output is gone
    // -----------------------------------------------------------------------

    @Test
    fun `a deleted output is reported missing so the recovery re-queue can run`() {
        val gone = File(tmp.root, "never_written.mp4")
        assertFalse(
            "a missing output must fall through to a real re-download",
            verifier.isPresent(outputUri = null, outputPath = gone.absolutePath),
        )
    }

    @Test
    fun `a zero byte output is incomplete and never counts as complete`() {
        val empty = file("empty.mp4", 0)
        assertTrue("fixture exists", empty.exists())
        assertEqualsZeroLength(empty)

        assertFalse(
            "size > 0 is part of the rule — an empty file is not an output",
            verifier.isPresent(null, empty.absolutePath),
        )
    }

    private fun assertEqualsZeroLength(f: File) {
        assertTrue("expected a zero-length fixture", f.length() == 0L)
    }

    @Test
    fun `a directory where a file was expected is not an output`() {
        val dir = tmp.newFolder("not_a_file.mp4")
        assertFalse(
            "a path that resolves to a directory is not a playable output",
            verifier.isPresent(null, dir.absolutePath),
        )
    }

    // -----------------------------------------------------------------------
    // Case (d): MediaStore row referenced but unverifiable
    // -----------------------------------------------------------------------

    @Test
    fun `an unresolvable content uri without a resolver is never treated as proof`() {
        assertFalse(
            "a URI nobody can resolve must not be believed",
            verifier.isPresent(
                outputUri = "content://media/external/video/media/1746",
                outputPath = null,
            ),
        )
    }

    @Test
    fun `an unverifiable uri falls back to the output path rule`() {
        val present = file("fallback.mp4", 512)
        val missing = File(tmp.root, "absent.mp4")

        assertTrue(
            "with a real local file the row is still verified present",
            verifier.isPresent("content://media/external/video/media/1", present.absolutePath),
        )
        assertFalse(
            "with no local file either, the row is reported missing",
            verifier.isPresent("content://media/external/video/media/1", missing.absolutePath),
        )
    }

    @Test
    fun `a blank uri is ignored rather than trusted`() {
        val present = file("blank_uri.mp4", 64)
        assertTrue(verifier.isPresent("", present.absolutePath))
        assertTrue(verifier.isPresent("   ", present.absolutePath))
        assertFalse(verifier.isPresent("   ", null))
    }

    // -----------------------------------------------------------------------
    // Case (e): nothing recorded at all
    // -----------------------------------------------------------------------

    @Test
    fun `a row with neither uri nor path is not an output`() {
        assertFalse(verifier.isPresent(null, null))
        assertFalse(verifier.isPresent("", ""))
    }

    // -----------------------------------------------------------------------
    // The rule itself, byte for byte
    // -----------------------------------------------------------------------

    @Test
    fun `the rule is exactly exists and size greater than zero`() {
        // Python's `downloader.py:60` rule, quoted in EpisodeOutputVerifier's KDoc.
        for (size in listOf(0, 1, 15, 188, 4096)) {
            val f = file("rule_$size.mp4", size)
            val expected = size > 0
            assertEqualsSize(expected, f)
        }
    }

    private fun assertEqualsSize(expected: Boolean, f: File) {
        val actual = verifier.isPresent(null, f.absolutePath)
        if (expected) {
            assertTrue("size=${f.length()} must count as present", actual)
        } else {
            assertFalse("size=${f.length()} must not count as present", actual)
        }
    }

    // -----------------------------------------------------------------------
    // The reported end-to-end pair, expressed at the rule level
    // -----------------------------------------------------------------------

    @Test
    fun `re-request while the export survives and after it is deleted flip exactly once`() {
        val output = file("flip.mp4", 8192)

        // 1. Export present, cache already cleaned → the guard suppresses.
        assertTrue(
            "first re-request is a no-op",
            verifier.isPresent(null, output.absolutePath),
        )

        // 2. Deleting the export is the ONLY thing that changes the verdict.
        assertTrue("fixture delete", output.delete())
        assertFalse(
            "after the user deletes it, the next request really re-downloads",
            verifier.isPresent(null, output.absolutePath),
        )
    }
}
