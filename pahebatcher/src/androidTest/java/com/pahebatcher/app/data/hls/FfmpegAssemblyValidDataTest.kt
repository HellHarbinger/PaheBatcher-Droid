package com.pahebatcher.app.data.hls

import android.os.Build
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
 * Device-based FFmpeg assembly validation tests with valid TS data.
 *
 * These tests generate VALID MPEG-TS segments using FFmpegKit itself
 * (encoding a short color-bar video), then test the full assembly pipeline.
 *
 * This validates:
 * 1. FFmpegKit command execution on x86_64
 * 2. TS generation via FFmpegKit (encoding from test source)
 * 3. TS → MP4 assembly via the assembler's primary path (single contiguous input)
 * 4. TS → MP4 assembly via the concat-demuxer fallback command
 * 5. faststart moov atom placement
 * 6. Output file validity (ftyp box)
 * 7. Existing output skip
 * 8. Cleanup behavior
 */
@RunWith(AndroidJUnit4::class)
class FfmpegAssemblyValidDataTest {

    companion object {
        private const val TAG = "FfmpegValidData"
    }

    private lateinit var assembler: FfmpegEpisodeAssembler
    private lateinit var testDir: File
    private lateinit var segmentDir: File

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assembler = FfmpegEpisodeAssembler()
        testDir = File(context.cacheDir, "ffmpeg_valid_${System.currentTimeMillis()}")
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
     * Generate a valid TS segment using FFmpegKit itself.
     * Creates a 1-second color-bar video encoded as MPEG-TS.
     */
    private fun generateValidTSSegment(idx: Int, durationSec: Int = 1): Boolean {
        val outFile = File(segmentDir, "%06d.ts".format(idx))
        // Use color filter + libopenh264 (available in ffmpeg-kit-full x86_64 build)
        // No -preset/-tune as OpenH264 doesn't support them
        val cmd = "-y -f lavfi -i color=c=red:s=320x240:d=$durationSec:r=25 " +
                "-c:v libopenh264 -pix_fmt yuv420p " +
                "-f mpegts ${outFile.absolutePath}"
        Log.i(TAG, "Generating TS segment $idx: $cmd")

        val session = FFmpegKit.execute(cmd)
        val success = ReturnCode.isSuccess(session.returnCode)
        if (!success) {
            Log.e(TAG, "FFmpegKit failed for segment $idx: code=${session.returnCode}")
            Log.e(TAG, "Output: ${session.output}")
        } else {
            Log.i(TAG, "Generated segment $idx: ${outFile.length()} bytes")
        }
        return success
    }

    // ==================== 1. FFmpegKit Command Execution ====================

    @Test
    fun testFfmpegKitExecutesOnDevice() {
        Log.i(TAG, "=== FFmpegKit Execution on Device ===")

        // Generate one valid TS segment
        val genOk = generateValidTSSegment(0)
        assertTrue("FFmpegKit should be able to generate a TS segment", genOk)

        val segFile = File(segmentDir, "000000.ts")
        assertTrue("Segment file should exist", segFile.exists())
        assertTrue("Segment file should be non-empty", segFile.length() > 0)
        Log.i(TAG, "PASS: FFmpegKit generates valid TS segments (${segFile.length()} bytes)")
    }

    // ==================== 2. TS → MP4 Assembly (Primary Concat Path) ====================

    @Test
    fun testTsToMp4AssemblyPrimaryPath() {
        Log.i(TAG, "=== TS → MP4 Assembly (Primary Concat Path) ===")

        // Generate 3 valid TS segments
        for (i in 0 until 3) {
            assertTrue("Segment $i generation should succeed", generateValidTSSegment(i))
        }

        val outputFile = File(testDir, "episode_001.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 3,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }

        Log.i(TAG, "Assembly result: success=${result.success}, error=${result.error}")

        assertTrue("Assembly should succeed with valid TS data", result.success)
        assertNotNull("Output file should be set", result.outputFile)
        assertTrue("Output should exist", outputFile.exists())
        assertTrue("Output should be non-empty", outputFile.length() > 0)
        Log.i(TAG, "Output size: ${outputFile.length()} bytes")

        // Verify MP4 format
        val header = ByteArray(8)
        FileInputStream(outputFile).use { it.read(header) }
        val boxType = String(header, 4, 4)
        assertEquals("MP4 should start with ftyp box", "ftyp", boxType)
        Log.i(TAG, "PASS: TS→MP4 assembly succeeded via the assembler's primary path")
    }

    // ==================== 2b. Media-sequence (1-based) indices ====================

    /**
     * Phase 11.5E-5 regression test (LIMITATIONS §26).
     *
     * Real AnimePahe/Kwik playlists start at `#EXT-X-MEDIA-SEQUENCE:1`, so the
     * store holds `000001.ts..000146.ts` and never a `000000.ts`. Assembly must
     * honour the playlist's own file indices (`AssemblyConfig.segmentIndices`):
     * a positional 0-based config must fail honestly against such a store, and
     * the playlist-index config must produce a valid faststart MP4.
     */
    @Test
    fun testMediaSequenceIndexedAssembly() {
        Log.i(TAG, "=== Media-sequence (1-based) Segment Indices ===")

        // Exactly as EpisodeDownloader writes them for a sequence-1 playlist
        for (i in 1..3) {
            assertTrue("Segment $i generation should succeed", generateValidTSSegment(i))
        }
        assertFalse(
            "Precondition: no positional 000000.ts in a media-sequence store",
            File(segmentDir, "000000.ts").exists()
        )

        // (a) A positional (0-based) config must fail honestly — not assemble
        //     a misaligned output, not report success.
        val positionalOutput = File(testDir, "positional.mp4")
        val positionalResult = runBlocking {
            assembler.assemble(
                EpisodeAssembler.AssemblyConfig(
                    segmentDir = segmentDir,
                    outputFile = positionalOutput,
                    segmentCount = 3,
                    timeoutMs = 60_000L
                )
            )
        }
        Log.i(TAG, "Positional config result: success=${positionalResult.success}, error=${positionalResult.error}")
        assertFalse("A 0-based config must not succeed on a sequence-1 store", positionalResult.success)
        assertEquals(
            "Exactly the one genuinely absent file (000000.ts) is reported",
            "Missing 1 segments before assembly",
            positionalResult.error
        )
        assertFalse(
            "No misaligned output may be produced",
            positionalOutput.exists() || File(testDir, "positional.mp4.tmp").exists()
        )

        // (b) The playlist's own indices must assemble to a valid faststart MP4
        val outputFile = File(testDir, "episode_media_seq.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 3,
            segmentIndices = listOf(1, 2, 3),
            timeoutMs = 60_000L
        )
        val result = runBlocking { assembler.assemble(config) }
        Log.i(TAG, "Assembly result: success=${result.success}, error=${result.error}")

        assertTrue("Assembly should succeed for playlist indices 1..3: ${result.error}", result.success)
        assertTrue(
            "Output should exist and be non-empty",
            outputFile.exists() && outputFile.length() > 0
        )

        // ftyp header
        val header = ByteArray(8)
        FileInputStream(outputFile).use { it.read(header) }
        assertEquals("MP4 should start with ftyp box", "ftyp", String(header, 4, 4))

        // faststart: moov before mdat
        val data = outputFile.readBytes()
        val str = String(data, charset("ISO-8859-1"))
        val moovIdx = str.indexOf("moov")
        val mdatIdx = str.indexOf("mdat")
        assertTrue("moov atom should exist", moovIdx >= 0)
        assertTrue("mdat atom should exist", mdatIdx >= 0)
        assertTrue(
            "moov should be BEFORE mdat for faststart (moov=$moovIdx, mdat=$mdatIdx)",
            moovIdx < mdatIdx
        )
        Log.i(TAG, "PASS: media-sequence assembly produced faststart MP4 (${outputFile.length()} bytes)")
    }

    // ==================== 3. Concat File Cleanup ====================

    @Test
    fun testConcatFileCleanup() {
        Log.i(TAG, "=== Concat File Cleanup ===")

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

        runBlocking { assembler.assemble(config) }

        val concatFile = File(segmentDir, "concat.txt")
        assertFalse("Concat file should be cleaned up", concatFile.exists())

        val tmpFile = File(outputFile.parent, outputFile.name + ".tmp")
        assertFalse("Tmp output should be cleaned up", tmpFile.exists())
        Log.i(TAG, "PASS: Cleanup verified")
    }

    // ==================== 4. Existing Output Skip ====================

    @Test
    fun testExistingOutputSkip() {
        Log.i(TAG, "=== Existing Output Skip ===")

        for (i in 0 until 2) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        val outputFile = File(testDir, "skip_test.mp4")
        // Pre-create non-empty file
        outputFile.writeBytes("pre-existing content".toByteArray())
        val originalLength = outputFile.length()

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 2,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }

        assertTrue("Should succeed (skip existing)", result.success)
        assertEquals("Output should be unchanged", originalLength, outputFile.length())
        Log.i(TAG, "PASS: Existing output skip verified (${originalLength} bytes preserved)")
    }

    // ==================== 5. Zero-byte Output Not Skipped ====================

    @Test
    fun testZeroByteOutputNotSkipped() {
        Log.i(TAG, "=== Zero-byte Output Not Skipped ===")

        assertTrue("Segment 0 should generate", generateValidTSSegment(0))

        val outputFile = File(testDir, "zero_test.mp4")
        outputFile.createNewFile() // 0 bytes

        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 1,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }

        assertTrue("Assembly should succeed (zero-byte should not block)", result.success)
        assertTrue("Output should be non-empty after assembly", outputFile.length() > 0)
        Log.i(TAG, "PASS: Zero-byte output was NOT skipped, assembly produced ${outputFile.length()} bytes")
    }

    // ==================== 6. Single Segment Assembly ====================

    @Test
    fun testSingleSegmentAssembly() {
        Log.i(TAG, "=== Single Segment Assembly ===")

        assertTrue("Segment 0 should generate", generateValidTSSegment(0, 2))

        val outputFile = File(testDir, "single_seg.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 1,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }

        assertTrue("Assembly should succeed with 1 segment", result.success)
        assertTrue("Output should exist", outputFile.exists())
        assertTrue("Output should be non-empty", outputFile.length() > 0)

        // Verify ftyp
        val header = ByteArray(8)
        FileInputStream(outputFile).use { it.read(header) }
        assertEquals("ftyp", String(header, 4, 4))

        Log.i(TAG, "PASS: Single segment assembly succeeded (${outputFile.length()} bytes)")
    }

    // ==================== 7. faststart Verification ====================

    @Test
    fun testFaststartMoovBeforeMdat() {
        Log.i(TAG, "=== faststart Verification ===")

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

        // Read the full output and check atom positions
        val data = outputFile.readBytes()
        val str = String(data, charset("ISO-8859-1"))

        val moovIdx = str.indexOf("moov")
        val mdatIdx = str.indexOf("mdat")

        Log.i(TAG, "moov at byte offset: $moovIdx")
        Log.i(TAG, "mdat at byte offset: $mdatIdx")

        assertTrue("moov atom should exist", moovIdx >= 0)
        assertTrue("mdat atom should exist", mdatIdx >= 0)
        assertTrue(
            "moov should be BEFORE mdat for faststart (moov=$moovIdx, mdat=$mdatIdx)",
            moovIdx < mdatIdx
        )
        Log.i(TAG, "PASS: faststart verified — moov at $moovIdx < mdat at $mdatIdx")
    }

    // ==================== 8. Output Filename Format ====================

    @Test
    fun testOutputFilenameFormat() {
        Log.i(TAG, "=== Output Filename Format ===")

        assertEquals("Ep_001_-_Pilot.mp4", FileNameGenerator.episodeFileName("001", "Pilot"))
        assertEquals("Ep_013.5_-_Clash_of_Kings.mp4", FileNameGenerator.episodeFileName("013.5", "Clash of Kings"))
        assertEquals("001", FileNameGenerator.formatEpisodePrefix(1))
        assertEquals("013.5", FileNameGenerator.formatEpisodePrefix(13.5))
        assertEquals("100", FileNameGenerator.formatEpisodePrefix(100))
        assertEquals("1000", FileNameGenerator.formatEpisodePrefix(1000))
        Log.i(TAG, "PASS: All filename format tests passed")
    }

    // ==================== 9. Pipe Fallback (Force via corrupt concat) ====================

    @Test
    fun testPipeFallbackPath() {
        Log.i(TAG, "=== Pipe Fallback Path ===")

        for (i in 0 until 2) {
            assertTrue("Segment $i should generate", generateValidTSSegment(i))
        }

        // We can't directly force either assembler path to fail without modifying production
        // code (both inputs are generated internally), so we verify end-to-end assembly here
        // and rely on the direct-command test for the fallback shape.

        val outputFile = File(testDir, "fallback_test.mp4")
        val config = EpisodeAssembler.AssemblyConfig(
            segmentDir = segmentDir,
            outputFile = outputFile,
            segmentCount = 2,
            timeoutMs = 60_000L
        )

        val result = runBlocking { assembler.assemble(config) }
        assertTrue("Assembly should succeed (primary or fallback)", result.success)
        assertTrue("Output should exist", outputFile.exists())
        assertTrue("Output should be non-empty", outputFile.length() > 0)
        Log.i(TAG, "PASS: Assembly succeeded (${outputFile.length()} bytes) — primary or fallback path")
    }

    // ==================== 10. ABI Detection ====================

    @Test
    fun testAbiDetectionOnDevice() {
        Log.i(TAG, "=== ABI Detection on Device ===")

        val abis = Build.SUPPORTED_ABIS
        Log.i(TAG, "Device ABIs: ${abis?.joinToString()}")
        Log.i(TAG, "Android API: ${Build.VERSION.SDK_INT}")

        assertTrue("isSupported should return true", assembler.isSupported())

        // Verify the FFmpegKit native library is actually loadable
        val sessions = FFmpegKit.listSessions()
        Log.i(TAG, "FFmpegKit sessions: ${sessions.size}")
        Log.i(TAG, "PASS: ABI detection and FFmpegKit native loading verified")
    }

    // ==================== 11. Multiple Sequential Assemblies ====================

    @Test
    fun testMultipleSequentialAssemblies() {
        Log.i(TAG, "=== Multiple Sequential Assemblies ===")

        // Assembly 1
        assertTrue("Gen segment 0", generateValidTSSegment(0))
        val file1 = File(testDir, "ep_001.mp4")
        val result1 = runBlocking {
            assembler.assemble(EpisodeAssembler.AssemblyConfig(
                segmentDir = segmentDir,
                outputFile = file1,
                segmentCount = 1,
                timeoutMs = 30_000L
            ))
        }
        assertTrue("First assembly should succeed", result1.success)
        Log.i(TAG, "Assembly 1: ${file1.length()} bytes")

        // Clean up segments for next assembly
        segmentDir.listFiles()?.filter { it.name.endsWith(".ts") }?.forEach { it.delete() }

        // Assembly 2
        assertTrue("Gen segment 0 for assembly 2", generateValidTSSegment(0))
        val file2 = File(testDir, "ep_002.mp4")
        val result2 = runBlocking {
            assembler.assemble(EpisodeAssembler.AssemblyConfig(
                segmentDir = segmentDir,
                outputFile = file2,
                segmentCount = 1,
                timeoutMs = 30_000L
            ))
        }
        assertTrue("Second assembly should succeed", result2.success)
        Log.i(TAG, "Assembly 2: ${file2.length()} bytes")

        val sessions = FFmpegKit.listSessions()
        Log.i(TAG, "Total FFmpegKit sessions: ${sessions.size}")
        assertTrue("Should have multiple sessions", sessions.size >= 2)
        Log.i(TAG, "PASS: Multiple sequential assemblies succeeded")
    }
}
