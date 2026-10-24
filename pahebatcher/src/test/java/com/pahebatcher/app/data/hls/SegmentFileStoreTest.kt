package com.pahebatcher.app.data.hls

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for SegmentFileStore — atomic segment file writes.
 *
 * Ported from Python store.py TestSegmentStore.
 * Constraint 10: Atomic segment storage.
 */
class SegmentFileStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: SegmentFileStore

    @Before
    fun setup() {
        val dir = tempFolder.newFolder("segments")
        store = SegmentFileStore(dir)
    }

    // ── Normal Atomic Write ───────────────────────────────────────────

    @Test
    fun `writeSeg creates completed ts file`() {
        val data = "test segment data".toByteArray()

        store.writeSeg(0, data)

        assertTrue(store.hasSeg(0))
        val file = store.segPath(0)
        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".ts"))
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun `writeSeg uses correct naming format`() {
        store.writeSeg(0, byteArrayOf(1))
        store.writeSeg(1, byteArrayOf(2))
        store.writeSeg(999, byteArrayOf(3))

        assertEquals("000000.ts", store.segPath(0).name)
        assertEquals("000001.ts", store.segPath(1).name)
        assertEquals("000999.ts", store.segPath(999).name)
    }

    @Test
    fun `writeSeg overwrites existing file`() {
        store.writeSeg(0, "first".toByteArray())
        store.writeSeg(0, "second".toByteArray())

        assertArrayEquals("second".toByteArray(), store.segPath(0).readBytes())
    }

    // ── Interrupted/Partial Temporary File ────────────────────────────

    @Test
    fun `writeSeg does not leave tmp file on success`() {
        store.writeSeg(0, "data".toByteArray())

        val tmpFile = File(store.segPath(0).parent, "000000.tmp")
        assertFalse("TMP file should not exist after successful write", tmpFile.exists())
    }

    @Test
    fun `hasSeg returns false for index without file`() {
        assertFalse(store.hasSeg(0))
        assertFalse(store.hasSeg(99))
    }

    // ── Done Indices ──────────────────────────────────────────────────

    @Test
    fun `doneIndices returns correct set`() {
        store.writeSeg(0, "data".toByteArray())
        store.writeSeg(3, "data".toByteArray())
        store.writeSeg(7, "data".toByteArray())

        val indices = store.doneIndices()
        assertEquals(setOf(0, 3, 7), indices)
    }

    @Test
    fun `doneIndices returns empty set for empty directory`() {
        assertTrue(store.doneIndices().isEmpty())
    }

    @Test
    fun `doneIndices only counts ts files`() {
        // Write a segment
        store.writeSeg(0, "data".toByteArray())

        // Manually create a .tmp file (simulating interrupted write)
        val tmpFile = File(store.segPath(0).parent, "000001.tmp")
        tmpFile.writeBytes("partial".toByteArray())

        // doneIndices should only include the .ts file
        assertEquals(setOf(0), store.doneIndices())
    }

    // ── Count Completed ───────────────────────────────────────────────

    @Test
    fun `countCompleted returns correct count`() {
        assertEquals(0, store.countCompleted())

        store.writeSeg(0, "data".toByteArray())
        assertEquals(1, store.countCompleted())

        store.writeSeg(1, "data".toByteArray())
        assertEquals(2, store.countCompleted())
    }

    // ── Concurrent Writes ─────────────────────────────────────────────

    @Test
    fun `concurrent writes to different indices succeed`() {
        val threads = (0 until 10).map { idx ->
            Thread {
                store.writeSeg(idx, "segment $idx".toByteArray())
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(10, store.countCompleted())
        for (i in 0 until 9) {
            assertTrue(store.hasSeg(i))
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    @Test
    fun `cleanup removes directory`() {
        store.writeSeg(0, "data".toByteArray())
        assertTrue(store.segPath(0).parentFile.exists())

        store.cleanup()
        assertFalse(store.segPath(0).parentFile.exists())
    }

    @Test
    fun `cleanup preserves mp4 output alongside segments`() {
        // the assembled MP4 lives INSIDE the
        // segment directory — cleanup must remove only segment artifacts.
        store.writeSeg(0, "data".toByteArray())
        store.writeSeg(1, "data2".toByteArray())
        val orphanTmp = File(store.getDirectory(), "000002.tmp")
        orphanTmp.writeBytes("partial".toByteArray())
        val concat = File(store.getDirectory(), "concat.txt")
        concat.writeBytes("file '000000.ts'".toByteArray())
        val output = File(store.getDirectory(), "Ep 001 - Test Episode.mp4")
        output.writeBytes("mp4-bytes".toByteArray())

        store.cleanup()

        // Segment artifacts removed
        assertFalse(store.hasSeg(0))
        assertFalse(store.hasSeg(1))
        assertFalse(orphanTmp.exists())
        assertFalse(concat.exists())
        // Assembled output and its directory survive
        assertTrue("MP4 output must survive cleanup", output.exists())
        assertEquals("mp4-bytes", output.readBytes().decodeToString())
        assertTrue("Directory must survive while it holds the output", store.getDirectory().exists())
    }

    // ── Orphan Cleanup ────────────────────────────────────────────────

    @Test
    fun `cleanupOrphans removes tmp files but keeps ts files`() {
        // Create a completed segment
        store.writeSeg(0, "completed".toByteArray())

        // Manually create orphaned .tmp files
        val dir = store.segPath(0).parentFile
        File(dir, "000001.tmp").writeBytes("orphan1".toByteArray())
        File(dir, "000002.tmp").writeBytes("orphan2".toByteArray())

        store.cleanupOrphans()

        // .ts file should still exist
        assertTrue(store.hasSeg(0))
        // .tmp files should be removed
        assertFalse(File(dir, "000001.tmp").exists())
        assertFalse(File(dir, "000002.tmp").exists())
    }

    // ── Large Segment ─────────────────────────────────────────────────

    @Test
    fun `writeSeg handles large segment data`() {
        // Simulate a large segment (96 KB — typical HLS segment size)
        val largeData = ByteArray(188 * 512) { (it % 256).toByte() }

        store.writeSeg(0, largeData)

        assertTrue(store.hasSeg(0))
        assertArrayEquals(largeData, store.segPath(0).readBytes())
    }
}
