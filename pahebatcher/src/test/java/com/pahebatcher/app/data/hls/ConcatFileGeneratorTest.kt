package com.pahebatcher.app.data.hls

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for ConcatFileGenerator — FFmpeg concat demuxer input generation.
 *
 * Ported from Python store.py SegmentStore.assemble() concat file generation.
 *
 * Note: Segment validation is now the caller's responsibility (FfmpegEpisodeAssembler).
 * ConcatFileGenerator only generates the concat file content.
 */
class ConcatFileGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var segmentDir: File
    private lateinit var segmentStore: SegmentFileStore

    @Before
    fun setup() {
        segmentDir = tempFolder.newFolder("segments")
        segmentStore = SegmentFileStore(segmentDir)
    }

    @Test
    fun `generate creates concat file with correct format`() {
        // Create segments
        segmentStore.writeSeg(0, "segment 0".toByteArray())
        segmentStore.writeSeg(1, "segment 1".toByteArray())
        segmentStore.writeSeg(2, "segment 2".toByteArray())

        val concatFile = ConcatFileGenerator.generate(segmentDir, listOf(0, 1, 2))

        assertNotNull("Concat file should be created", concatFile)
        assertTrue("Concat file should exist", concatFile!!.exists())

        val content = concatFile.readText()
        val lines = content.lines()

        assertEquals("Should have 3 lines", 3, lines.size)
        assertTrue("Line 0 should reference seg 0", lines[0].contains("file '") && lines[0].contains("000000.ts"))
        assertTrue("Line 1 should reference seg 1", lines[1].contains("file '") && lines[1].contains("000001.ts"))
        assertTrue("Line 2 should reference seg 2", lines[2].contains("file '") && lines[2].contains("000002.ts"))
    }

    @Test
    fun `generate uses absolute paths`() {
        segmentStore.writeSeg(0, "segment 0".toByteArray())

        val concatFile = ConcatFileGenerator.generate(segmentDir, listOf(0))

        assertNotNull(concatFile)
        val content = concatFile!!.readText().trim()
        assertTrue("Path should be absolute", content.contains(segmentDir.absolutePath))
    }

    @Test
    fun `generate works with single segment`() {
        segmentStore.writeSeg(0, "segment 0".toByteArray())

        val concatFile = ConcatFileGenerator.generate(segmentDir, listOf(0))

        assertNotNull(concatFile)
        val content = concatFile!!.readText().trim()
        assertTrue("Should contain single file reference", content.startsWith("file '"))
        assertTrue("Should end with .ts'", content.endsWith(".ts'"))
    }

    @Test
    fun `generate produces correct segment ordering`() {
        // Create segments
        for (i in 0..4) {
            segmentStore.writeSeg(i, "segment $i".toByteArray())
        }

        val concatFile = ConcatFileGenerator.generate(segmentDir, (0 until 5).toList())

        assertNotNull(concatFile)
        val content = concatFile!!.readText()
        val lines = content.lines()

        assertEquals(5, lines.size)

        // Verify ordering is sequential: 000000, 000001, 000002, 000003, 000004
        for (i in 0..4) {
            assertTrue("Line $i should reference segment $i",
                lines[i].contains("%06d.ts".format(i)))
        }
    }

    @Test
    fun `generate lists media-sequence indices in playlist order`() {
        // Real AnimePahe/Kwik playlists start at #EXT-X-MEDIA-SEQUENCE:1, so the
        // store holds 000001.ts..000003.ts (LIMITATIONS §26 / Phase 11.5E-5).
        for (i in 1..3) {
            segmentStore.writeSeg(i, "segment $i".toByteArray())
        }

        val concatFile = ConcatFileGenerator.generate(segmentDir, listOf(1, 2, 3))

        assertNotNull(concatFile)
        val lines = concatFile!!.readText().lines()

        assertEquals("Should have 3 lines", 3, lines.size)
        // Compare only the file-name tail (paths use '\' on the JVM test host)
        fun fileNameOf(line: String) = line.substringAfterLast('\\').substringAfterLast('/')
            .removeSuffix("'")
        assertEquals("Line 0 should reference 000001.ts", "000001.ts", fileNameOf(lines[0]))
        assertEquals("Line 1 should reference 000002.ts", "000002.ts", fileNameOf(lines[1]))
        assertEquals("Line 2 should reference 000003.ts", "000003.ts", fileNameOf(lines[2]))
        // No 0-based file may be referenced
        assertTrue("Must not reference 000000.ts", lines.none { it.contains("000000.ts") })
    }

    @Test
    fun `generate preserves the caller order verbatim`() {
        // Concatenation order is playlist order — not a re-sort of the indices
        val concatFile = ConcatFileGenerator.generate(segmentDir, listOf(7, 3, 5))

        assertNotNull(concatFile)
        val lines = concatFile!!.readText().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("000007.ts"))
        assertTrue(lines[1].contains("000003.ts"))
        assertTrue(lines[2].contains("000005.ts"))
    }

    @Test
    fun `generate with empty index list writes an empty concat file`() {
        val concatFile = ConcatFileGenerator.generate(segmentDir, emptyList())

        assertNotNull(concatFile)
        assertEquals("Empty list should produce empty content", "", concatFile!!.readText())
    }

    @Test
    fun `cleanup removes concat file`() {
        segmentStore.writeSeg(0, "segment 0".toByteArray())
        ConcatFileGenerator.generate(segmentDir, listOf(0))

        val concatFile = File(segmentDir, "concat.txt")
        assertTrue("Concat file should exist before cleanup", concatFile.exists())

        ConcatFileGenerator.cleanup(segmentDir)

        assertFalse("Concat file should be removed after cleanup", concatFile.exists())
    }

    @Test
    fun `cleanup is safe when no concat file exists`() {
        // Should not throw
        ConcatFileGenerator.cleanup(segmentDir)
    }
}
