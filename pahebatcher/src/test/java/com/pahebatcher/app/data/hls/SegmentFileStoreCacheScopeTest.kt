package com.pahebatcher.app.data.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The cache-size/clear scope is exactly the
 * temporary artifact set (`*.ts`, `*.tmp`, `concat.txt`). The assembled
 * `*.mp4` — the file the export path consumes — is never counted and never
 * cleared; SAF/MediaStore outputs live outside this directory entirely.
 * [SegmentFileStore.temporaryBytes] and the cache-clear action share
 * [SegmentFileStore.isTemporaryArtifact], so the number displayed is the
 * number the action frees.
 */
class SegmentFileStoreCacheScopeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `isTemporaryArtifact covers exactly ts tmp and concat`() {
        assertTrue(SegmentFileStore.isTemporaryArtifact("000001.ts"))
        assertTrue(SegmentFileStore.isTemporaryArtifact("000999.ts"))
        assertTrue(SegmentFileStore.isTemporaryArtifact("000001.tmp"))
        assertTrue(SegmentFileStore.isTemporaryArtifact("concat.txt"))

        assertFalse(SegmentFileStore.isTemporaryArtifact("Episode 01.mp4"))
        assertFalse(SegmentFileStore.isTemporaryArtifact("000001.ts.gz"))
        assertFalse(SegmentFileStore.isTemporaryArtifact("concat.txt.bak"))
        assertFalse(SegmentFileStore.isTemporaryArtifact("subs.ass"))
        assertFalse(SegmentFileStore.isTemporaryArtifact("Episode 01.mp4.pb.part"))
        assertFalse(SegmentFileStore.isTemporaryArtifact(""))
    }

    @Test
    fun `temporaryBytes counts only temporary artifacts`() {
        val dir = tempFolder.newFolder("segments")
        fun write(name: String, bytes: Int) =
            java.io.File(dir, name).writeBytes(ByteArray(bytes) { it.toByte() })

        write("000001.ts", 10)
        write("000002.ts", 20)
        write("000003.tmp", 5)
        write("concat.txt", 7)
        // Must never be counted (Decision 107):
        write("Episode 01.mp4", 1_000)
        write("notes.txt", 50)
        write("Episode 02.mp4.pb.part", 60)

        val store = SegmentFileStore(dir)
        assertEquals(
            "only *.ts + *.tmp + concat.txt bytes are cache",
            10L + 20L + 5L + 7L,
            store.temporaryBytes(),
        )
    }

    @Test
    fun `temporaryBytes is zero for an empty or mp4-only directory`() {
        val empty = tempFolder.newFolder("empty")
        assertEquals(0L, SegmentFileStore(empty).temporaryBytes())

        val assembled = tempFolder.newFolder("assembled")
        java.io.File(assembled, "Episode 01.mp4").writeBytes(ByteArray(4_096))
        assertEquals(
            "an assembled-only directory frees nothing",
            0L,
            SegmentFileStore(assembled).temporaryBytes(),
        )
    }
}
