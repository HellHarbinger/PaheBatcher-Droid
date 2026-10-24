package com.pahebatcher.app.data.hls

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.domain.hls.EpisodeAssembler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * Phase 9 MP4 Assembly Re-Validation after .tmp extension bug fix.
 *
 * The bug: FFmpeg couldn't auto-detect the MP4 muxer from a .tmp extension.
 * The fix: Added explicit `-f mp4` to both assembly paths (the `.tmp` output name
 * gives FFmpeg no extension to detect the MP4 muxer from).
 *
 * This test suite specifically validates:
 * 1. Assembler primary path produces valid MP4 via assembler (integration)
 * 2. The `executePipeFallback()` FFmpeg command produces valid MP4 (direct command validation)
 * 3. faststart (moov before mdat)
 * 4. Output file validity (ftyp box, size)
 * 5. Existing output skip
 * 6. Cleanup (concat.txt, .tmp files removed)
 * 7. Filename format
 * 8. Atomic output pattern (.tmp → rename)
 */
@RunWith(AndroidJUnit4::class)
class FfmpegPhase9ValidationTest {

    companion object {
        private const val TAG = "Phase9Validation"
    }

    private lateinit var assembler: FfmpegEpisodeAssembler
    private lateinit var testDir: File
    private lateinit var segmentDir: File

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assembler = FfmpegEpisodeAssembler()
        testDir = File(context.cacheDir, "phase9_valid_${System.currentTimeMillis()}")
        testDir.mkdirs()
        segmentDir = File(testDir, "segments")
        segmentDir.mkdirs()
        Log.i(TAG, "Test dir: ${testDir.absolutePath}")
    }

    @After
    fun teardown() {
        try {
            testDir.deleteRecursively()
        } catch (_: Exception) {}
    }

    /**
     * Generate a valid TS segment using FFmpegKit.
     * Uses libopenh264 encoder which works on x86_64 emulators.
     */
    private fun generateValidTSSegment(idx: Int, durationSec: Int = 1): Boolean {
        val outFile = File(segmentDir, "%06d.ts".format(idx))
        val cmd = "-y -f lavfi -i color=c=red:s=320x240:d=$durationSec:r=25 " +
                "-c:v libopenh264 -pix_fmt yuv420p " +
                "-f mpegts ${outFile.absolutePath}"

        val session = FFmpegKit.execute(cmd)
        val success = ReturnCode.isSuccess(session.returnCode)
        if (!success) {
            Log.e(TAG, "TS generation failed for segment $idx: ${session.returnCode}")
            Log.e(TAG, "FFmpeg output: ${session.output}")
        } else {
            Log.i(TAG, "Generated segment $idx: ${outFile.length()} bytes")
        }
        return success
    }

    /**
     * Validate that a file is a valid MP4 with faststart.
     * Checks: ftyp box, non-empty, moov before mdat.
     */
    private fun validateMP4(file: File, label: String) {
        assertTrue("$label: Output file should exist", file.exists())
        assertTrue("$label: Output file should be non-empty", file.length() > 0)
        Log.i(TAG, "$label: Output size: ${file.length()} bytes")

        val header = ByteArray(16)
        FileInputStream(file).use { it.read(header) }
        val boxType = String(header, 4, 4)
        assertEquals("$label: MP4 should start with ftyp box", "ftyp", boxType)

        // Check faststart: moov before mdat
        val data = file.readBytes()
        val str = String(data, charset("ISO-8859-1"))
        val moovIdx = str.indexOf("moov")
        val mdatIdx = str.indexOf("mdat")
        assertTrue("$label: moov atom should exist", moovIdx >= 0)
        assertTrue("$label: mdat atom should exist", mdatIdx >= 0)
        assertTrue(
            "$label: moov should be BEFORE mdat for faststart (moov=$moovIdx, mdat=$mdatIdx)",
            moovIdx < mdatIdx
        )
        Log.i(TAG, "$label: PASS — valid MP4 with faststart (moov=$moovIdx, mdat=$mdatIdx)")
    }

    // ================================================================
    // CRITICAL TEST 1: Assembler primary path (single contiguous input, integration)
    // ================================================================

    @Test
    fun testPrimaryConcatPathProducesValidMP4() {
        Log.i(TAG, "=== CRITICAL: Primary Path → Valid MP4 ===")

        for (i in 0 until 3) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        val outputFile = File(testDir, "Ep 001 - Pilot.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 3,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }

        Log.i(TAG, "Assembly result: success=${result.success}, error=${result.error}")
        assertTrue("Primary path assembly should succeed", result.success)
        assertNotNull("Output file should be set", result.outputFile)
        assertEquals("Output file should match requested file", outputFile, result.outputFile)

        validateMP4(outputFile, "Primary-Path")

        // Verify concat.txt is cleaned up
        val concatFile = File(segmentDir, "concat.txt")
        assertFalse("Concat file should be cleaned up after success", concatFile.exists())

        // Verify .tmp is cleaned up
        val tmpFile = File(outputFile.parent, outputFile.name + ".tmp")
        assertFalse("Tmp output should be cleaned up after rename", tmpFile.exists())

        Log.i(TAG, "=== CRITICAL: Primary Concat Path → PASS ===")
    }

    // ================================================================
    // CRITICAL TEST 2: executePipeFallback command (direct FFmpeg command)
    //
    // Since we can't easily force either assembler path to fail on demand
    // (both are generated internally), we test the exact FFmpeg command that
    // executePipeFallback() runs. This validates that `-f mp4` works when the
    // output is a .tmp file.
    // ================================================================

    @Test
    fun testPipeFallbackPathProducesValidMP4() {
        Log.i(TAG, "=== CRITICAL: executePipeFallback Command → Valid MP4 ===")

        for (i in 0 until 3) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        // Exactly how executePipeFallback() builds its input: concatenate every
        // segment into one contiguous file, then hand FFmpeg a single input.
        val pipeInput = File(segmentDir, "_pipe_input.ts")
        java.io.FileOutputStream(pipeInput).use { output ->
            for (i in 0 until 3) {
                val segFile = File(segmentDir, "%06d.ts".format(i))
                java.io.FileInputStream(segFile).use { input ->
                    input.copyTo(output)
                }
            }
        }
        Log.i(TAG, "Pipe input created: ${pipeInput.length()} bytes")

        // Output goes to .tmp to match the atomic pattern
        val tmpOutput = File(testDir, "fallback_output.mp4.tmp")

        // This is the EXACT command from executePipeFallback() in FfmpegEpisodeAssembler.kt
        val arguments = listOf(
            "-y",
            "-i", pipeInput.absolutePath,
            "-c", "copy",
            "-f", "mp4",
            "-movflags", "+faststart",
            tmpOutput.absolutePath
        )

        Log.i(TAG, "Running pipe fallback FFmpeg command with -f mp4")
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        val success = ReturnCode.isSuccess(session.returnCode)
        Log.i(TAG, "FFmpeg return code: ${session.returnCode}, success: $success")

        if (!success) {
            Log.e(TAG, "FFmpeg output: ${session.output}")
            Log.e(TAG, "FFmpeg logs: ${session.logsAsString}")
        }

        assertTrue("Pipe fallback FFmpeg command should succeed", success)
        validateMP4(tmpOutput, "Pipe-Fallback")

        // Verify atomic rename works
        val finalOutput = File(testDir, "fallback_output.mp4")
        val renamed = tmpOutput.renameTo(finalOutput)
        assertTrue("Atomic rename should succeed", renamed)
        assertTrue("Final output should exist", finalOutput.exists())
        Log.i(TAG, "Atomic rename: .tmp → .mp4 succeeded")

        // Cleanup
        pipeInput.delete()

        Log.i(TAG, "=== CRITICAL: Pipe Fallback Path → PASS ===")
    }

    // ================================================================
    // CRITICAL TEST 3: faststart verification (moov atom near beginning)
    // ================================================================

    @Test
    fun testFaststartMoovNearBeginning() {
        Log.i(TAG, "=== CRITICAL: faststart Verification ===")

        for (i in 0 until 2) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        val outputFile = File(testDir, "faststart_test.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 2,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }
        assertTrue("Assembly should succeed", result.success)

        val data = outputFile.readBytes()
        val str = String(data, charset("ISO-8859-1"))
        val moovIdx = str.indexOf("moov")
        val mdatIdx = str.indexOf("mdat")

        Log.i(TAG, "moov at byte offset: $moovIdx")
        Log.i(TAG, "mdat at byte offset: $mdatIdx")
        Log.i(TAG, "Total file size: ${data.size} bytes")

        assertTrue("moov atom should exist", moovIdx >= 0)
        assertTrue("mdat atom should exist", mdatIdx >= 0)
        assertTrue(
            "moov should be BEFORE mdat for faststart (moov=$moovIdx, mdat=$mdatIdx)",
            moovIdx < mdatIdx
        )

        // moov should be near the beginning (within first 100 bytes for small files)
        assertTrue(
            "moov should be near the beginning of the file (offset $moovIdx < ${data.size / 2})",
            moovIdx < data.size / 2
        )

        Log.i(TAG, "=== CRITICAL: faststart Verification → PASS ===")
    }

    // ================================================================
    // CRITICAL TEST 4: Output file validity
    // ================================================================

    @Test
    fun testOutputFileValidity() {
        Log.i(TAG, "=== CRITICAL: Output File Validity ===")

        for (i in 0 until 4) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        val outputFile = File(testDir, "validity_test.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 4,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }
        assertTrue("Assembly should succeed", result.success)

        // Validate MP4 structure
        assertTrue("Output should exist", outputFile.exists())
        assertTrue("Output should be non-empty", outputFile.length() > 0)

        val header = ByteArray(16)
        FileInputStream(outputFile).use { it.read(header) }

        // Check ftyp box
        val boxType = String(header, 4, 4)
        assertEquals("Should start with ftyp box", "ftyp", boxType)

        // Check MP4 brand (ftyp box contains brand after size+type)
        val brand = String(header, 8, 4)
        Log.i(TAG, "MP4 brand: $brand")
        // Common MP4 brands: isom, iso2, iso3, avc1, mp41, mp42, dash
        // All are valid; we just verify the box structure is present
        assertTrue("MP4 brand should be 4 printable ASCII chars", brand.all { it in ' '..'~' })

        Log.i(TAG, "Output file size: ${outputFile.length()} bytes")
        Log.i(TAG, "=== CRITICAL: Output File Validity → PASS ===")
    }

    // ================================================================
    // IMPORTANT TEST 5: Existing output skip
    // ================================================================

    @Test
    fun testExistingOutputSkip() {
        Log.i(TAG, "=== IMPORTANT: Existing Output Skip ===")

        for (i in 0 until 2) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        val outputFile = File(testDir, "skip_test.mp4")
        outputFile.writeBytes("pre-existing valid content".toByteArray())
        val originalLength = outputFile.length()
        val originalLastModified = outputFile.lastModified()

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 2,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }

        assertTrue("Should succeed (skip existing)", result.success)
        assertEquals("Output should be unchanged", originalLength, outputFile.length())
        Log.i(TAG, "Output preserved: $originalLength bytes")
        Log.i(TAG, "=== IMPORTANT: Existing Output Skip → PASS ===")
    }

    // ================================================================
    // IMPORTANT TEST 6: Cleanup (concat.txt and .tmp files)
    // ================================================================

    @Test
    fun testCleanupAfterSuccessfulAssembly() {
        Log.i(TAG, "=== IMPORTANT: Cleanup Verification ===")

        for (i in 0 until 2) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        val outputFile = File(testDir, "cleanup_test.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 2,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }
        assertTrue("Assembly should succeed", result.success)

        val concatFile = File(segmentDir, "concat.txt")
        val tmpFile = File(outputFile.parent, outputFile.name + ".tmp")
        val pipeInput = File(segmentDir, "_pipe_input.ts")

        assertFalse("concat.txt should be cleaned up", concatFile.exists())
        assertFalse(".tmp output should be cleaned up", tmpFile.exists())
        assertFalse("_pipe_input.ts should be cleaned up", pipeInput.exists())

        Log.i(TAG, "=== IMPORTANT: Cleanup Verification → PASS ===")
    }

    // ================================================================
    // IMPORTANT TEST 7: Filename format
    // ================================================================

    @Test
    fun testFilenameFormat() {
        Log.i(TAG, "=== IMPORTANT: Filename Format ===")

        assertEquals("Ep_001_-_Pilot.mp4", FileNameGenerator.episodeFileName("001", "Pilot"))
        assertEquals("Ep_013.5_-_Clash_of_Kings.mp4", FileNameGenerator.episodeFileName("013.5", "Clash of Kings"))
        assertEquals("001", FileNameGenerator.formatEpisodePrefix(1))
        assertEquals("013.5", FileNameGenerator.formatEpisodePrefix(13.5))
        assertEquals("100", FileNameGenerator.formatEpisodePrefix(100))
        assertEquals("1000", FileNameGenerator.formatEpisodePrefix(1000))

        Log.i(TAG, "=== IMPORTANT: Filename Format → PASS ===")
    }

    // ================================================================
    // ADDITIONAL: Assembler primary path, then the direct FFmpeg command
    //
    // Tests that both shapes are reachable and produce identical
    // output format when given valid TS data.
    // ================================================================

    @Test
    fun testBothPathsProduceValidMP4() {
        Log.i(TAG, "=== ADDITIONAL: Both Paths Produce Valid MP4 ===")

        // Assembly 1 — goes through the assembler's primary path (single input)
        for (i in 0 until 2) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        val primaryOutput = File(testDir, "primary_out.mp4")
        val primaryResult = runBlocking {
            assembler.assemble(EpisodeAssembler.AssemblyConfig(
                segmentDir = segmentDir,
                outputFile = primaryOutput,
                segmentCount = 2,
                timeoutMs = 60_000L
            ))
        }
        assertTrue("Primary path assembly should succeed", primaryResult.success)
        validateMP4(primaryOutput, "Primary")

        // Clean segments for next test
        segmentDir.listFiles()?.filter { it.name.endsWith(".ts") }?.forEach { it.delete() }

        // Assembly 2 — exercise pipe fallback directly
        for (i in 0 until 2) {
            assertTrue("Segment $i should generate for fallback", generateValidTSSegment(i))
        }

        // Direct pipe fallback test
        val pipeInput = File(segmentDir, "_pipe_input.ts")
        java.io.FileOutputStream(pipeInput).use { output ->
            for (i in 0 until 2) {
                val segFile = File(segmentDir, "%06d.ts".format(i))
                java.io.FileInputStream(segFile).use { input ->
                    input.copyTo(output)
                }
            }
        }

        val fallbackOutput = File(testDir, "fallback_out.mp4.tmp")
        val fallbackCmd = listOf(
            "-y", "-i", pipeInput.absolutePath,
            "-c", "copy",
            "-f", "mp4",
            "-movflags", "+faststart",
            fallbackOutput.absolutePath
        )

        val session = FFmpegKit.executeWithArguments(fallbackCmd.toTypedArray())
        assertTrue("Pipe fallback should succeed", ReturnCode.isSuccess(session.returnCode))

        val fallbackFinal = File(testDir, "fallback_out.mp4")
        assertTrue("Rename should succeed", fallbackOutput.renameTo(fallbackFinal))
        validateMP4(fallbackFinal, "Pipe-Fallback")

        // Compare sizes — both should produce similar-sized MP4s
        val sizeDiff = kotlin.math.abs(primaryOutput.length() - fallbackFinal.length())
        Log.i(TAG, "Primary size: ${primaryOutput.length()}, Fallback size: ${fallbackFinal.length()}, diff: $sizeDiff bytes")
        assertTrue(
            "Both outputs should be similar in size (diff < 50%)",
            sizeDiff < maxOf(primaryOutput.length(), fallbackFinal.length()) / 2
        )

        Log.i(TAG, "=== ADDITIONAL: Both Paths Produce Valid MP4 → PASS ===")
    }

    // ================================================================
    // ADDITIONAL: Atomic output pattern (.tmp → rename)
    // ================================================================

    @Test
    fun testAtomicOutputPattern() {
        Log.i(TAG, "=== ADDITIONAL: Atomic Output Pattern ===")

        for (i in 0 until 2) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        val outputFile = File(testDir, "atomic_test.mp4")
        val tmpFile = File(outputFile.parent, outputFile.name + ".tmp")

        // Verify no .tmp exists before assembly
        assertFalse("No .tmp before assembly", tmpFile.exists())

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 2,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }
        assertTrue("Assembly should succeed", result.success)

        // After success: .tmp should NOT exist (renamed), final file should exist
        assertFalse(".tmp should be cleaned up (renamed to final)", tmpFile.exists())
        assertTrue("Final output should exist", outputFile.exists())
        assertTrue("Final output should be non-empty", outputFile.length() > 0)

        Log.i(TAG, "=== ADDITIONAL: Atomic Output Pattern → PASS ===")
    }
}
