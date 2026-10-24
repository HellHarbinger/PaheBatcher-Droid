package com.pahebatcher.app.data.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Final-audit H2 — the pure storage helpers (SPEC §13 "insufficient storage",
 * SPEC §26 `DiskFull`).
 *
 * Everything here is deterministic and Android-free, so the rules the download
 * pipeline depends on are proven on the JVM:
 *
 * * ENOSPC recognition, including through a wrapped cause chain and for the
 *   plain-string reason FFmpeg reports;
 * * the pre-flight estimate and its "an unknown figure never refuses" rule;
 * * the human-readable wording SPEC §26 asks for.
 */
class StorageSpaceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── ENOSPC recognition ───────────────────────────────────────────

    @Test
    fun `an IOException carrying the ENOSPC text is a storage failure`() {
        assertTrue(StorageSpace.isNoSpaceLeft(IOException("No space left on device")))
    }

    @Test
    fun `the errno form is recognised too`() {
        assertTrue(StorageSpace.isNoSpaceLeft(IOException("write failed: ENOSPC")))
    }

    @Test
    fun `a wrapped out-of-space failure is recognised through the cause chain`() {
        val root = IOException("No space left on device")
        val wrapped = RuntimeException("copy failed", IOException("flush failed", root))

        assertTrue(StorageSpace.isNoSpaceLeft(wrapped))
    }

    @Test
    fun `an unrelated failure is not a storage failure`() {
        assertFalse(StorageSpace.isNoSpaceLeft(IOException("Connection reset by peer")))
        assertFalse(StorageSpace.isNoSpaceLeft(IllegalStateException()))
        assertFalse(StorageSpace.isNoSpaceLeft(IOException(null as String?)))
    }

    @Test
    fun `a blank reason is not a storage failure`() {
        assertFalse(StorageSpace.isNoSpaceLeft(null as String?))
        assertFalse(StorageSpace.isNoSpaceLeft("   "))
        assertFalse(StorageSpace.isNoSpaceLeft("concat demuxer mismatch"))
    }

    @Test
    fun `an FFmpeg-style reason is recognised as a storage failure`() {
        assertTrue(StorageSpace.isNoSpaceLeft("av_interleaving_write: No space left on device"))
    }

    @Test
    fun `a self-referential cause chain terminates instead of spinning`() {
        val first = IOException("first")
        val second = IOException("second")
        runCatching { first.initCause(second) }
        runCatching { second.initCause(first) }

        // Either direction of the cycle must answer, not hang.
        assertFalse(StorageSpace.isNoSpaceLeft(first))
        assertTrue(StorageSpace.isNoSpaceLeft(IOException("boom", IOException("No space left on device"))))
    }

    // ── Pre-flight estimate / gate ───────────────────────────────────

    @Test
    fun `the estimate averages the completed segments over the pending ones`() {
        // 4 completed segments totalling 400 bytes → 100 bytes each → 3 left.
        assertEquals(300L, StorageSpace.estimatedRemainingBytes(4, 400L, 3))
    }

    @Test
    fun `there is no estimate without a completed sample`() {
        assertEquals(0L, StorageSpace.estimatedRemainingBytes(0, 0L, 5))
        assertEquals(0L, StorageSpace.estimatedRemainingBytes(2, 0L, 5))
        assertEquals(0L, StorageSpace.estimatedRemainingBytes(2, 100L, 0))
    }

    @Test
    fun `only a genuine shortfall refuses`() {
        assertTrue(StorageSpace.isInsufficient(availableBytes = 10L, requiredBytes = 11L))
        assertFalse("exactly enough is enough", StorageSpace.isInsufficient(11L, 11L))
        assertFalse("spare room is fine", StorageSpace.isInsufficient(100L, 11L))
        assertFalse("an unknown free figure never refuses", StorageSpace.isInsufficient(0L, 11L))
        assertFalse("an unknown estimate never refuses", StorageSpace.isInsufficient(10L, 0L))
    }

    // ── Measurement / wording ────────────────────────────────────────

    @Test
    fun `available bytes are read from the real volume`() {
        val folder = tempFolder.newFolder("volume")

        assertTrue("File.usableSpace must report a real figure", StorageSpace.availableBytes(folder) > 0L)
    }

    @Test
    fun `the description states both numbers in human units`() {
        val description = StorageSpace.describe(
            availableBytes = 2L * 1024 * 1024,
            requiredBytes = 45L * 1024 * 1024,
        )

        assertTrue(description, description.contains("2.0 MB"))
        assertTrue(description, description.contains("45.0 MB"))
        assertTrue(description, description.contains("free"))
    }
}
