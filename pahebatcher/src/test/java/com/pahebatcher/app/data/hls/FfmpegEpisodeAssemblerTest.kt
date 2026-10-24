package com.pahebatcher.app.data.hls

import com.pahebatcher.app.domain.hls.EpisodeAssembler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for FfmpegEpisodeAssembler — FFmpegKit-based MP4 assembly.
 *
 * Tests cover:
 * - ABI detection (returns false on JVM)
 * - Existing output skip (platform-independent, runs before the ABI gate)
 * - Missing segment detection (platform-independent)
 * - Segment index contract: playlist/media-sequence indices vs 0-based range
 * - Cleanup behavior
 * - Error handling
 *
 * Note: Real FFmpeg execution tests require an Android device with FFmpegKit support.
 * JVM unit tests verify the assembly pipeline logic, not actual FFmpeg execution.
 * Device-based validation is performed by @tester.
 */
class FfmpegEpisodeAssemblerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var assembler: FfmpegEpisodeAssembler
    private lateinit var segmentDir: File
    private lateinit var segmentStore: SegmentFileStore

    @Before
    fun setup() {
        assembler = FfmpegEpisodeAssembler()
        segmentDir = tempFolder.newFolder("segments")
        segmentStore = SegmentFileStore(segmentDir)
    }

    @Test
    fun `isSupported returns false on JVM`() {
        // Build.SUPPORTED_ABIS is null in JVM unit tests
        assertFalse("isSupported should return false on JVM", assembler.isSupported())
    }

    @Test
    fun `assemble returns ABI error when not supported`() = runTest {
        val outputFile = File(tempFolder.root, "output.mp4")

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 0
        )

        val result = assembler.assemble(config)

        assertFalse("Assembly should fail on JVM", result.success)
        assertTrue("Error should mention ABI", result.error?.contains("ABI") == true ||
                result.error?.contains("not available") == true)
    }

    @Test
    fun `cleanup removes concat file after failed assembly`() = runTest {
        // Create segments
        createMinimalTSSegment(0)

        val outputFile = File(tempFolder.root, "output.mp4")

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 1,
            timeoutMs = 30_000L
        )

        assembler.assemble(config)

        val concatFile = File(segmentDir, "concat.txt")
        assertFalse("Concat file should be cleaned up", concatFile.exists())
    }

    @Test
    fun `cleanup removes tmp output on failure`() = runTest {
        // Create segments
        createMinimalTSSegment(0)

        val outputFile = File(tempFolder.root, "output.mp4")

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 1,
            timeoutMs = 30_000L
        )

        assembler.assemble(config)

        val tmpOutput = File(tempFolder.root, "output.mp4.tmp")
        assertFalse("Tmp output should be cleaned up", tmpOutput.exists())
    }

    @Test
    fun `assembly failure is reported with clear error`() = runTest {
        // On JVM, assembly always fails due to ABI check
        // This verifies the error reporting path
        createMinimalTSSegment(0)

        val outputFile = File(tempFolder.root, "output.mp4")

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 1
        )

        val result = assembler.assemble(config)

        assertFalse(result.success)
        assertNotNull(result.error)
        assertFalse("Assembly error should not be success", result.success)
    }

    @Test
    fun `existing zero-byte output is not treated as complete`() = runTest {
        val outputFile = File(tempFolder.root, "output.mp4")
        outputFile.createNewFile() // 0 bytes

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 0
        )

        // Zero-byte output must not trigger the skip — the assembler proceeds
        // (platform-independent checks first, then the ABI gate on JVM).
        val result = assembler.assemble(config)

        assertFalse("Zero-byte output should not be skipped", result.success)
    }

    // ── Phase 11.5E-5: segment index contract ─────────────────────────
    // Real AnimePahe/Kwik playlists start at #EXT-X-MEDIA-SEQUENCE:1, so the
    // store holds 000001.ts..000146.ts while a 0-based pre-check looks for
    // 000000.ts — the production "Missing 1 segments before assembly" defect
    // (LIMITATIONS §26). The assembler must honour the caller's playlist
    // indices instead of assuming range(0, segmentCount).

    @Test
    fun `assemble accepts media-sequence indices when every segment file exists`() = runTest {
        // Files exactly as written by EpisodeDownloader for MEDIA-SEQUENCE:1
        for (i in 1..3) createMinimalTSSegment(i)

        val outputFile = File(tempFolder.root, "output.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 3,
            segmentIndices = listOf(1, 2, 3)
        )

        val result = assembler.assemble(config)

        // Pre-check passes on JVM → the only failure left is the ABI gate.
        assertFalse("Should not be a success on JVM (no FFmpeg)", result.success)
        assertTrue(
            "Error should be the ABI gate, not a missing-segment error: ${result.error}",
            result.error?.contains("ABI") == true || result.error?.contains("not available") == true
        )
        assertFalse(
            "No false 'Missing N segments' error may be reported",
            result.error?.contains("Missing") == true
        )
    }

    @Test
    fun `assemble reports exactly one missing segment for a media-sequence set`() = runTest {
        // 000001.ts and 000003.ts present, 000002.ts absent — the pre-check must
        // count missing files against the playlist indices, not range(0, n)
        // (which would report a missing 000000.ts for this very directory).
        createMinimalTSSegment(1)
        createMinimalTSSegment(3)

        val outputFile = File(tempFolder.root, "output.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 3,
            segmentIndices = listOf(1, 2, 3)
        )

        val result = assembler.assemble(config)

        assertFalse(result.success)
        assertEquals(
            "Should report exactly the one genuinely missing segment",
            "Missing 1 segments before assembly",
            result.error
        )
    }

    @Test
    fun `assemble does not look for a zero-based file that the playlist never had`() = runTest {
        // The exact production state: every playlist segment present, but the
        // directory has no 000000.ts because the CDN playlist starts at 1.
        for (i in 1..2) createMinimalTSSegment(i)
        assertFalse("Precondition: no 000000.ts in a media-sequence store",
            segmentStore.hasSeg(0))

        val outputFile = File(tempFolder.root, "output.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 2,
            segmentIndices = listOf(1, 2)
        )

        val result = assembler.assemble(config)

        assertFalse(
            "Must not fail with a missing-segment error when all playlist files exist",
            result.error?.contains("Missing") == true
        )
    }

    @Test
    fun `assemble still detects missing segments for a zero-based store`() = runTest {
        createMinimalTSSegment(0)
        createMinimalTSSegment(1)
        // segmentCount 3, default indices [0, 1, 2] → 000002.ts missing

        val outputFile = File(tempFolder.root, "output.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 3
        )

        val result = assembler.assemble(config)

        assertFalse(result.success)
        assertEquals("Missing 1 segments before assembly", result.error)
    }

    @Test
    fun `assemble rejects a config whose index list does not match segmentCount`() = runTest {
        for (i in 1..2) createMinimalTSSegment(i)

        val outputFile = File(tempFolder.root, "output.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 3,
            segmentIndices = listOf(1, 2) // inconsistent on purpose
        )

        val result = assembler.assemble(config)

        assertFalse("Inconsistent config must fail, not silently assemble", result.success)
        assertTrue("Error should describe the mismatch: ${result.error}",
            result.error?.contains("mismatch") == true)
    }

    @Test
    fun `assemble skips when a non-empty output already exists even without segments`() = runTest {
        // Output-first ordering: after a successful assembly the segments are
        // cleaned up, and a re-run must still succeed (Python downloader.py:60).
        val outputFile = File(tempFolder.root, "already_done.mp4")
        outputFile.writeText("existing mp4 bytes")

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 3,
            segmentIndices = listOf(1, 2, 3)
        )

        val result = assembler.assemble(config)

        assertTrue("Existing non-empty output should skip assembly", result.success)
        assertEquals(outputFile, result.outputFile)
        assertFalse("Skip must not fabricate an error", result.error != null)
    }

    /**
     * Create a minimal file that looks like a TS segment.
     * Real TS segments have 188-byte packets, but for testing
     * we just need files that exist.
     */
    private fun createMinimalTSSegment(idx: Int) {
        val data = ByteArray(188)
        data[0] = 0x47.toByte() // TS sync byte
        segmentStore.writeSeg(idx, data)
    }
}
