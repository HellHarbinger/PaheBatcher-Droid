package com.pahebatcher.app.data.hls

import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.domain.model.StreamInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for EpisodeDownloader — HLS segment download orchestration.
 *
 * Ported from Python downloader.py EpisodeDownloader tests.
 * Uses fixture-based testing (no real network required).
 */
class EpisodeDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeHlsClient: FakeHlsClient
    private lateinit var keyCache: AesKeyCache
    private lateinit var segmentStore: SegmentFileStore
    private lateinit var downloader: EpisodeDownloader

    @Before
    fun setup() {
        fakeHlsClient = FakeHlsClient()
        keyCache = AesKeyCache()
        val storeDir = tempFolder.newFolder("segments")
        segmentStore = SegmentFileStore(storeDir)
        downloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = keyCache,
            segmentStore = segmentStore,
            hlsWorkers = 4 // Lower for tests
        )
    }

    // ── Simple Unencrypted Download ───────────────────────────────────

    @Test
    fun `download unencrypted segments succeeds`() = runTest {
        // Set up M3U8 response
        val m3u8Content = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content

        // Set up segment responses
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "segment0data".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "segment1data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue("Download should succeed", result.success)
        assertEquals(2, result.totalSegments)
        assertEquals(2, result.completedSegments)
        assertEquals(0, result.failedSegments)

        // Verify files exist
        assertTrue(segmentStore.hasSeg(0))
        assertTrue(segmentStore.hasSeg(1))
    }

    // ── Master Playlist ───────────────────────────────────────────────

    @Test
    fun `download follows last variant from master playlist`() = runTest {
        // Master playlist with 3 variants
        val masterContent = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=500000
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            mid.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            high.m3u8
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/master.m3u8"] = masterContent

        // Media playlist for the LAST variant (Constraint 6: Python parity)
        val mediaContent = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/high.m3u8"] = mediaContent
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/master.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue(result.success)
        assertEquals(1, result.totalSegments)

        // Verify the last variant was fetched
        assertTrue("Should have fetched high.m3u8 (last variant)",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/high.m3u8"))
    }

    @Test
    fun `download selects last variant not highest bandwidth`() = runTest {
        // Variants in ascending order — last is lowest bandwidth
        val masterContent = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000
            low.m3u8
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/master.m3u8"] = masterContent
        fakeHlsClient.textResponses["https://cdn.example.com/low.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/master.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue(result.success)
        // Should have fetched low.m3u8 (last variant), not high.m3u8
        assertTrue("Should fetch last variant (low.m3u8)",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/low.m3u8"))
        assertFalse("Should NOT fetch high.m3u8",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/high.m3u8"))
    }

    // ── Encrypted Segments ────────────────────────────────────────────

    @Test
    fun `download decrypts AES-128 encrypted segments`() = runTest {
        val key = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )
        val iv = ByteArray(16) { 0 }

        // Encrypt test data with real HLS-style PKCS#7 padding (RFC 8216 5.2).
        val plaintext = "encrypted segment data!".toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)

        // Python decrypts RAW and keeps the pad bytes on disk (`downloader.py`
        // `cipher.decrypt(raw)` -> `store.write_seg(idx, raw)`), so the segment file
        // must contain the padded plaintext, not the trimmed one.
        val padLen = 16 - (plaintext.size % 16)
        val paddedPlaintext = plaintext + ByteArray(padLen) { padLen.toByte() }

        // M3U8 with encryption
        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url/key.bin"
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = ciphertext
        fakeHlsClient.byteResponses["https://key.url/key.bin"] = key

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue("Download should succeed", result.success)

        // Verify decrypted content
        val decryptedFile = segmentStore.segPath(0)
        val onDisk = decryptedFile.readBytes()
        assertArrayEquals("Segment bytes must match Python's raw CBC output (pad retained)",
            paddedPlaintext, onDisk)
        assertEquals("Byte total must match ciphertext length, as Python's dash.seg_done counts",
            ciphertext.size, onDisk.size)
    }

    @Test
    fun `encrypted segment whose last block is not valid PKCS7 is still downloaded`() = runTest {
        // End-to-end regression for the raw-CBC parity fix. A ciphertext that
        // decrypts to a non-PKCS#7 final block is something Python downloads
        // successfully (`downloader.py` `cipher.decrypt(raw)` never inspects padding).
        // The pre-fix `PKCS5Padding` build threw BadPaddingException here and marked
        // the segment failed, rejecting an input the reference supports.
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { 0 }

        // 32 bytes, final byte 0x00 -> not a legal PKCS#7 pad value.
        val plaintext = ByteArray(32) { 'A'.code.toByte() }.also { it[31] = 0x00 }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url/key.bin"
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = ciphertext
        fakeHlsClient.byteResponses["https://key.url/key.bin"] = key

        val result = downloader.download(
            StreamInfo(
                url = "https://cdn.example.com/playlist.m3u8",
                userAgent = "TestAgent",
                referer = "https://animepahe.com/"
            )
        )

        assertTrue("Non-PKCS7 ciphertext must download, not be rejected", result.success)
        assertEquals(1, result.completedSegments)
        assertArrayEquals(
            "Raw CBC output must be byte-identical to Python's",
            plaintext,
            segmentStore.segPath(0).readBytes()
        )
    }

    // ── Segment Resume ────────────────────────────────────────────────

    @Test
    fun `download skips already completed segments`() = runTest {
        // Pre-write a completed segment
        segmentStore.writeSeg(0, "already done".toByteArray())

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "new data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue(result.success)
        assertEquals(2, result.totalSegments)
        assertEquals(2, result.completedSegments) // Both complete (0 pre-existing, 1 new)

        // seg0 should still have original data (not re-downloaded)
        assertArrayEquals("already done".toByteArray(), segmentStore.segPath(0).readBytes())
    }

    // ── Failure Handling ──────────────────────────────────────────────

    @Test
    fun `download returns failure when M3U8 fetch fails`() = runTest {
        // Don't set up any responses — all requests will return null

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertFalse("Download should fail", result.success)
        assertNotNull("Error message should be set", result.error)
    }

    @Test
    fun `download returns failure when no segments found`() = runTest {
        val m3u8Content = "#EXTM3U\n" // Empty playlist

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertFalse("Download should fail", result.success)
        assertTrue(result.error!!.contains("No segments"))
    }

    // ── Request Context ───────────────────────────────────────────────

    @Test
    fun `download passes correct headers to requests`() = runTest {
        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            cookies = listOf(mapOf("name" to "token", "value" to "abc123")),
            userAgent = "MyAgent/1.0",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue(result.success)

        // Verify the request context was passed correctly
        val lastContext = fakeHlsClient.lastRequestContext
        assertNotNull(lastContext)
        assertEquals("MyAgent/1.0", lastContext!!.userAgent)
        assertEquals("https://animepahe.com/", lastContext.referer)
        assertEquals("abc123", lastContext.cookies["token"])
    }

    // ── Episode-Level Retry ────────────────────────────────────────────

    @Test
    fun `episode retry succeeds on second attempt after transient failure`() = runTest {
        // autoRetry=2 → 3 total attempts
        val retryDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            autoRetry = 2
        )

        // First request fails, second succeeds
        fakeHlsClient.failOnceFor("https://cdn.example.com/playlist.m3u8")

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = retryDownloader.download(streamInfo)

        assertTrue("Download should succeed on retry", result.success)
        // Should have been called twice (first fails, second succeeds)
        assertEquals("M3U8 should be fetched twice", 2,
            fakeHlsClient.callCountFor("https://cdn.example.com/playlist.m3u8"))
    }

    @Test
    fun `episode retry does not retry permanent errors`() = runTest {
        val retryDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            autoRetry = 2
        )

        // Empty playlist = "No segments" → permanent error
        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = "#EXTM3U\n"

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = retryDownloader.download(streamInfo)

        assertFalse("Download should fail", result.success)
        assertTrue(result.error!!.contains("No segments"))
        // Should only be called once (no retry for permanent errors)
        assertEquals("M3U8 should be fetched once (no retry)", 1,
            fakeHlsClient.callCountFor("https://cdn.example.com/playlist.m3u8"))
    }

    @Test
    fun `episode retry gives up after max attempts exhausted`() = runTest {
        val retryDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            autoRetry = 1  // 2 total attempts
        )

        // Always fail
        fakeHlsClient.failAlwaysFor("https://cdn.example.com/playlist.m3u8")

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = retryDownloader.download(streamInfo)

        assertFalse("Download should fail", result.success)
        // autoRetry=1 → 2 episode attempts × 5 HTTP retries each = 10 calls
        assertEquals("M3U8 should be fetched 10 times (2 episode attempts × 5 HTTP retries)", 10,
            fakeHlsClient.callCountFor("https://cdn.example.com/playlist.m3u8"))
    }

    // ── Phase 8: Crash Recovery / Resume Tests ────────────────────────

    /**
     * Test A — Partial segment resume:
     * Pre-write some completed .ts segments, run download, verify only missing segments
     * are downloaded and completed segments are preserved.
     */
    @Test
    fun `resume downloads only missing segments when some already exist`() = runTest {
        // Pre-write segments 0 and 2 as completed
        segmentStore.writeSeg(0, "segment 0 original".toByteArray())
        segmentStore.writeSeg(2, "segment 2 original".toByteArray())

        // M3U8 has 4 segments (0, 1, 2, 3)
        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
            #EXTINF:10.0,
            seg3.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "segment 1 new".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg3.ts"] = "segment 3 new".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue("Download should succeed", result.success)
        assertEquals(4, result.totalSegments)
        assertEquals(4, result.completedSegments)
        assertEquals(0, result.failedSegments)

        // Verify pre-existing segments were NOT re-downloaded (original data preserved)
        assertArrayEquals("segment 0 original".toByteArray(), segmentStore.segPath(0).readBytes())
        assertArrayEquals("segment 2 original".toByteArray(), segmentStore.segPath(2).readBytes())

        // Verify new segments were downloaded
        assertArrayEquals("segment 1 new".toByteArray(), segmentStore.segPath(1).readBytes())
        assertArrayEquals("segment 3 new".toByteArray(), segmentStore.segPath(3).readBytes())

        // Verify only seg1 and seg3 were actually fetched from the network
        assertFalse("seg0 should NOT be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg0.ts"))
        assertTrue("seg1 should be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg1.ts"))
        assertFalse("seg2 should NOT be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg2.ts"))
        assertTrue("seg3 should be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg3.ts"))
    }

    /**
     * Test B — Stale .tmp recovery:
     * Pre-write completed .ts files AND stale .tmp files, run download, verify:
     * - stale .tmp files are cleaned
     * - completed .ts files remain
     * - missing segments are downloaded
     */
    @Test
    fun `cleanupOrphans removes stale tmp files and preserves completed ts files`() = runTest {
        // Pre-write completed segments
        segmentStore.writeSeg(0, "segment 0 done".toByteArray())
        segmentStore.writeSeg(1, "segment 1 done".toByteArray())

        // Manually create stale .tmp files (simulating interrupted writes from a prior session)
        val segDir = segmentStore.segPath(0).parentFile
        File(segDir, "000002.tmp").writeBytes("interrupted data".toByteArray())
        File(segDir, "000003.tmp").writeBytes("more interrupted data".toByteArray())

        // Verify stale .tmp files exist before download
        assertTrue("Stale .tmp 000002.tmp should exist before download",
            File(segDir, "000002.tmp").exists())
        assertTrue("Stale .tmp 000003.tmp should exist before download",
            File(segDir, "000003.tmp").exists())

        // M3U8 has 4 segments
        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
            #EXTINF:10.0,
            seg3.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg2.ts"] = "segment 2 new".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg3.ts"] = "segment 3 new".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue("Download should succeed", result.success)
        assertEquals(4, result.totalSegments)
        assertEquals(4, result.completedSegments)

        // Verify stale .tmp files were cleaned up
        assertFalse("Stale .tmp 000002.tmp should be cleaned",
            File(segDir, "000002.tmp").exists())
        assertFalse("Stale .tmp 000003.tmp should be cleaned",
            File(segDir, "000003.tmp").exists())

        // Verify completed .ts files were preserved (original data)
        assertArrayEquals("segment 0 done".toByteArray(), segmentStore.segPath(0).readBytes())
        assertArrayEquals("segment 1 done".toByteArray(), segmentStore.segPath(1).readBytes())

        // Verify new segments were downloaded
        assertArrayEquals("segment 2 new".toByteArray(), segmentStore.segPath(2).readBytes())
        assertArrayEquals("segment 3 new".toByteArray(), segmentStore.segPath(3).readBytes())
    }

    /**
     * Test C — Interrupted-download simulation:
     * Simulate the state left by a process-death/interrupted download:
     * - Some segments completed (.ts)
     * - One segment partially written (.tmp only, never renamed)
     * - Some segments never started
     *
     * Then restart the download and verify it resumes correctly:
     * - Completed segments are preserved
     * - Stale .tmp files are cleaned
     * - Missing segments are downloaded
     * - No false duplicate downloads
     */
    @Test
    fun `resume from interrupted download state completes successfully`() = runTest {
        // --- Simulate interrupted download state ---
        // Segments 0 and 1 completed successfully
        segmentStore.writeSeg(0, "completed segment 0".toByteArray())
        segmentStore.writeSeg(1, "completed segment 1".toByteArray())

        // Segment 2 was mid-write when process died (only .tmp exists)
        val segDir = segmentStore.segPath(0).parentFile
        File(segDir, "000002.tmp").writeBytes("partial incomplete data".toByteArray())

        // Segment 3 was never started

        // Verify the interrupted state
        assertEquals("Should have 2 completed segments", 2, segmentStore.doneIndices().size)
        assertTrue("Segment 0 should be complete", segmentStore.hasSeg(0))
        assertTrue("Segment 1 should be complete", segmentStore.hasSeg(1))
        assertFalse("Segment 2 should NOT be complete (only .tmp)", segmentStore.hasSeg(2))
        assertFalse("Segment 3 should NOT exist", segmentStore.hasSeg(3))
        assertTrue("Stale .tmp for seg2 should exist",
            File(segDir, "000002.tmp").exists())

        // --- Restart download (simulating app restart after process death) ---
        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
            #EXTINF:10.0,
            seg3.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg2.ts"] = "resumed segment 2".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg3.ts"] = "resumed segment 3".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        // --- Verify recovery ---
        assertTrue("Download should succeed after resume", result.success)
        assertEquals(4, result.totalSegments)
        assertEquals(4, result.completedSegments)
        assertEquals(0, result.failedSegments)

        // Completed segments preserved (not re-downloaded)
        assertArrayEquals("completed segment 0".toByteArray(), segmentStore.segPath(0).readBytes())
        assertArrayEquals("completed segment 1".toByteArray(), segmentStore.segPath(1).readBytes())

        // Stale .tmp cleaned up
        assertFalse("Stale .tmp for seg2 should be cleaned",
            File(segDir, "000002.tmp").exists())

        // Missing segments downloaded
        assertArrayEquals("resumed segment 2".toByteArray(), segmentStore.segPath(2).readBytes())
        assertArrayEquals("resumed segment 3".toByteArray(), segmentStore.segPath(3).readBytes())

        // Only seg2 and seg3 fetched from network (seg0 and seg1 were already done)
        assertFalse("seg0 should NOT be re-fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg0.ts"))
        assertFalse("seg1 should NOT be re-fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg1.ts"))
        assertTrue("seg2 should be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg2.ts"))
        assertTrue("seg3 should be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg3.ts"))
    }

    /**
     * Test C2 — All segments previously completed (full resume):
     * Every segment already exists as .ts. Download should succeed immediately
     * with zero network requests for segments.
     */
    @Test
    fun `full resume skips all segments when all are already complete`() = runTest {
        // Pre-write all segments
        segmentStore.writeSeg(0, "seg 0".toByteArray())
        segmentStore.writeSeg(1, "seg 1".toByteArray())
        segmentStore.writeSeg(2, "seg 2".toByteArray())

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        // No byte responses needed — nothing should be fetched

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(streamInfo)

        assertTrue("Download should succeed (all segments already done)", result.success)
        assertEquals(3, result.totalSegments)
        assertEquals(3, result.completedSegments)
        assertEquals(0, result.failedSegments)

        // No segment data should have been fetched
        assertFalse("seg0 should NOT be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg0.ts"))
        assertFalse("seg1 should NOT be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg1.ts"))
        assertFalse("seg2 should NOT be fetched",
            fakeHlsClient.requestedUrls.contains("https://cdn.example.com/seg2.ts"))

        // Original data preserved
        assertArrayEquals("seg 0".toByteArray(), segmentStore.segPath(0).readBytes())
        assertArrayEquals("seg 1".toByteArray(), segmentStore.segPath(1).readBytes())
        assertArrayEquals("seg 2".toByteArray(), segmentStore.segPath(2).readBytes())
    }

    /**
     * Test C3 — Cleanup does not affect unrelated files:
     * Orphan cleanup should only remove .tmp files, not .ts files or other file types.
     */
    @Test
    fun `cleanupOrphans only removes tmp files not ts or other files`() = runTest {
        val segDir = segmentStore.segPath(0).parentFile

        // Create completed .ts file
        segmentStore.writeSeg(0, "completed".toByteArray())

        // Create stale .tmp file
        File(segDir, "000001.tmp").writeBytes("orphan".toByteArray())

        // Create other file types that should NOT be touched
        File(segDir, "session.json").writeBytes("{}".toByteArray())
        File(segDir, "metadata.txt").writeBytes("info".toByteArray())

        // Run download (which calls cleanupOrphans internally)
        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        // seg0 already exists, nothing to download

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        downloader.download(streamInfo)

        // .tmp file should be cleaned
        assertFalse("Orphan .tmp should be removed", File(segDir, "000001.tmp").exists())

        // .ts file should be preserved
        assertTrue("Completed .ts should be preserved", segmentStore.hasSeg(0))

        // Other files should be preserved
        assertTrue("session.json should be preserved", File(segDir, "session.json").exists())
        assertTrue("metadata.txt should be preserved", File(segDir, "metadata.txt").exists())
    }

    // ── Phase 9: Assembly Integration Tests ────────────────────────────

    @Test
    fun `download with assembler triggers assembly after segment download`() = runTest {
        val fakeAssembler = FakeAssembler(success = true)
        val assemblerDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            hlsWorkers = 4
        )

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "seg1data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = assemblerDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Pilot")

        assertTrue("Download should succeed", result.success)
        assertTrue("Assembly should have been triggered", fakeAssembler.assembleCalled)
        assertTrue("Should be marked as assembled", result.assembled)
        assertNotNull("Output file should be set", result.outputFile)
        // cleanup runs after assembly (keepTemp=false)
        // — the output file must SURVIVE it, exist, and be non-empty.
        val output = result.outputFile!!
        assertTrue("Output file must exist after download + cleanup", output.exists())
        assertTrue("Output file must be non-empty after download + cleanup", output.length() > 0)
        // Segment artifacts are gone, but the output directory is not
        assertTrue(
            "Segment directory must survive while it holds the output",
            output.parentFile?.exists() == true
        )
    }

    // ── A2.1 closure (keepTemp audit GAP-1): the keepTemp=FALSE cleanup leg ──

    @Test
    fun `keepTemp false removes completed segments after successful assembly and keeps the output`() = runTest {
        val cleanupAssembler = FakeAssembler(success = true)
        val cleanupDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = cleanupAssembler,
            hlsWorkers = 4,
            keepTemp = false
        )

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "seg1data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = cleanupDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Pilot")

        assertTrue("Download should succeed", result.success)
        assertTrue("Assembly should have been triggered", cleanupAssembler.assembleCalled)

        // The verified output SURVIVES the cleanup — cleanup only ever removes
        // non-.mp4 children (SegmentFileStore.cleanup preserves the output).
        val output = result.outputFile!!
        assertTrue("Output must exist after the keepTemp=false cleanup", output.exists())
        assertTrue("Output must be non-empty after the keepTemp=false cleanup", output.length() > 0)
        assertTrue(
            "The output directory must survive while it holds the output",
            output.parentFile?.exists() == true
        )

        // ...and the completed segments are really GONE — observable filesystem
        // state, pinning the production cleanup call itself (audit GAP-1).
        assertFalse("000000.ts must be removed after verified assembly", segmentStore.hasSeg(0))
        assertFalse("000001.ts must be removed after verified assembly", segmentStore.hasSeg(1))
        assertEquals(
            "no .ts may remain in the directory after the keepTemp=false cleanup",
            0,
            segmentStore.doneIndices().size
        )
    }

    // ── A2.1 closure (keepTemp audit GAP-3): assembly failure preserves data ──

    @Test
    fun `assembly failure preserves completed segments and reports no output`() = runTest {
        val failingAssembler = FakeAssembler(success = false, error = "FFmpeg concat failed")
        val failingDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = failingAssembler,
            autoRetry = 2,
            hlsWorkers = 4
        )

        // A sentinel the cleanup pass WOULD delete (SegmentFileStore.cleanup
        // removes every non-.mp4 child): its survival proves no cleanup ran.
        File(segmentStore.getDirectory(), "metadata.txt").writeText("session metadata")

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = failingDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Pilot")

        assertFalse("Assembly failure must not report success", result.success)
        assertEquals(
            "Assembly failures are the permanent ASSEMBLY class",
            DownloadErrorClass.ASSEMBLY,
            result.errorClass
        )
        assertNotNull("the assembly error must be reported", result.assemblyError)
        assertFalse("a failed run must not report assembled", result.assembled)
        assertNull("a failed run must not report an output file", result.outputFile)
        assertEquals(
            "a permanent assembly error is entered exactly once — never retried",
            1,
            failingAssembler.callCount
        )

        // The completed segment is retained for retry/resume...
        assertTrue("000000.ts must survive the assembly failure", segmentStore.hasSeg(0))
        // ...no output was created...
        assertFalse(
            "no output may exist after the failed assembly",
            File(segmentStore.getDirectory(), "Ep 001 - Pilot.mp4").exists()
        )
        // ...and no cleanup pass removed anything.
        assertTrue(
            "cleanup must not run on the failure path",
            File(segmentStore.getDirectory(), "metadata.txt").exists()
        )
    }

    // ── A2.1 closure (keepTemp audit GAP-4a): failure after a committed segment ──

    @Test
    fun `transient failure keeps committed segments and the retry reuses them`() = runTest {
        // autoRetry = 0 → ONE attempt per download() call, so phase 1 ends in a
        // real failure with one segment already committed to disk.
        val resumeDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            hlsWorkers = 4,
            autoRetry = 0
        )

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "seg1data".toByteArray()
        // Permanent for phase 1: the in-run fetch retry (RETRY_ATTEMPTS = 5)
        // must really exhaust itself before the attempt fails.
        fakeHlsClient.failAlwaysFor("https://cdn.example.com/seg1.ts")

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val phase1 = resumeDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Pilot")

        assertFalse("phase 1 must fail on the exhausted transient segment error", phase1.success)
        assertEquals("the on-disk committed count is the honest completed count", 1, phase1.completedSegments)
        assertFalse("a failed run must not assemble", phase1.assembled)
        assertTrue("000000.ts must survive the failed attempt", segmentStore.hasSeg(0))
        assertFalse("the failed segment was never committed", segmentStore.hasSeg(1))

        // Phase 2 — the RETRY of the same flow against a fresh transport: seg0
        // must be REUSED from disk (its absence from the new client's requests
        // is the reuse proof), seg1 fetched exactly once and completed.
        val resumeClient = FakeHlsClient()
        resumeClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        resumeClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()
        resumeClient.byteResponses["https://cdn.example.com/seg1.ts"] = "seg1data".toByteArray()
        val retryDownloader = EpisodeDownloader(
            hlsClient = resumeClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            hlsWorkers = 4
        )

        val phase2 = retryDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Pilot")

        assertTrue("the retry must succeed", phase2.success)
        assertEquals("all segments complete after the retry", 2, phase2.completedSegments)
        assertEquals(
            "000000.ts must be REUSED from disk — the retry never refetches it",
            0,
            resumeClient.callCountFor("https://cdn.example.com/seg0.ts")
        )
        assertEquals(
            "000001.ts is fetched exactly once on the retry",
            1,
            resumeClient.callCountFor("https://cdn.example.com/seg1.ts")
        )
        assertTrue("the reused segment must be on disk", segmentStore.hasSeg(0))
        assertTrue("the retried segment must be on disk", segmentStore.hasSeg(1))
    }

    // ── Final-audit H4: the SPEC §25 "remuxing" (ASSEMBLING) hook ─────

    /**
     * The hook is the ONE seam through which the Worker persists
     * `STATE_ASSEMBLING`, so its contract is strict: exactly once per real mux,
     * always BEFORE FFmpeg starts, and never on a run that does not mux.
     */
    @Test
    fun `assembly start hook fires exactly once, before the mux`() = runTest {
        val fakeAssembler = FakeAssembler(success = true)
        val assemblerDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            hlsWorkers = 4,
        )
        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "seg1data".toByteArray()

        var hookCalls = 0
        var assemblerAlreadyRunning = false
        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/",
        )

        val result = assemblerDownloader.download(
            streamInfo,
            episodeNumber = 1,
            episodeTitle = "Pilot",
            onAssemblyStart = {
                hookCalls++
                assemblerAlreadyRunning = fakeAssembler.assembleCalled
            },
        )

        assertTrue("Download should succeed", result.success)
        assertEquals("exactly one ASSEMBLING write per real mux", 1, hookCalls)
        assertFalse("the state must be persisted BEFORE the mux starts", assemblerAlreadyRunning)
        assertTrue(fakeAssembler.assembleCalled)
    }

    @Test
    fun `assembly start hook never fires when there is no assembler`() = runTest {
        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()

        var hookCalls = 0
        val result = downloader.download(
            StreamInfo(
                url = "https://cdn.example.com/playlist.m3u8",
                userAgent = "TestAgent",
                referer = "https://animepahe.com/",
            ),
            onAssemblyStart = { hookCalls++ },
        )

        assertTrue(result.success)
        assertEquals("no mux → no ASSEMBLING state", 0, hookCalls)
    }

    @Test
    fun `master playlist download fires the assembly start hook`() = runTest {
        // DL-001 regression: the master-playlist branch used to recurse into
        // downloadOnce WITHOUT forwarding onAssemblyStart, so a master-playlist
        // episode never persisted STATE_ASSEMBLING — its reconciler recovery branch
        // was bypassed and the Downloads screen never showed "assembling".
        val fakeAssembler = FakeAssembler(success = true)
        val assemblerDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            hlsWorkers = 4,
        )
        val masterUrl = "https://cdn.example.com/master.m3u8"
        val variantUrl = "https://cdn.example.com/variant.m3u8"
        fakeHlsClient.textResponses[masterUrl] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
            variant.m3u8
        """.trimIndent()
        fakeHlsClient.textResponses[variantUrl] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()

        var hookCalls = 0
        var assemblerAlreadyRunning = false
        val result = assemblerDownloader.download(
            StreamInfo(
                url = masterUrl,
                userAgent = "TestAgent",
                referer = "https://animepahe.com/",
            ),
            episodeNumber = 1,
            episodeTitle = "Pilot",
            onAssemblyStart = {
                hookCalls++
                assemblerAlreadyRunning = fakeAssembler.assembleCalled
            },
        )

        assertTrue("Master-playlist download should succeed", result.success)
        assertEquals("the master path must fire ASSEMBLING exactly once", 1, hookCalls)
        assertFalse("ASSEMBLING must be persisted BEFORE the mux starts", assemblerAlreadyRunning)
        assertTrue(fakeAssembler.assembleCalled)
    }

    @Test
    fun `assembly start hook never fires when an existing output short-circuits`() = runTest {
        val fakeAssembler = FakeAssembler(success = true)
        val assemblerDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            hlsWorkers = 4,
        )
        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()
        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/",
        )

        // First run: real mux, output created.
        assertTrue(assemblerDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Pilot").success)

        // Second run: the existing non-empty output short-circuits BEFORE any
        // playlist fetch — no mux happens, so no ASSEMBLING state may be claimed.
        var hookCalls = 0
        val rerun = assemblerDownloader.download(
            streamInfo,
            episodeNumber = 1,
            episodeTitle = "Pilot",
            onAssemblyStart = { hookCalls++ },
        )

        assertTrue(rerun.success)
        assertTrue("the rerun must short-circuit on the existing output", rerun.reusedExistingOutput)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `download with assembler generates correct filename`() = runTest {
        val fakeAssembler = FakeAssembler(success = true)
        val assemblerDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            hlsWorkers = 4
        )

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        assemblerDownloader.download(streamInfo, episodeNumber = 13, episodeTitle = "Clash of Kings")

        assertTrue(fakeAssembler.assembleCalled)
        val config = fakeAssembler.lastConfig!!
        assertTrue("Output filename should contain episode prefix",
            config.outputFile.name.contains("013"))
        assertTrue("Output filename should contain sanitized title",
            config.outputFile.name.contains("Clash_of_Kings"))
        assertTrue("Output filename should end with .mp4",
            config.outputFile.name.endsWith(".mp4"))
    }

    // ── Phase 11.5E-5: media-sequence (1-based) segment identity ───────
    // Real AnimePahe/Kwik playlists start at #EXT-X-MEDIA-SEQUENCE:1, so the
    // store holds 000001.ts..000146.ts. Every pre-existing fixture used
    // MEDIA-SEQUENCE:0, which is exactly why the assembly boundary defect
    // (LIMITATIONS §26) was invisible to the suite.

    @Test
    fun `media sequence playlist writes sequence-keyed files and passes those indices to the assembler`() = runTest {
        val fakeAssembler = FakeAssembler(success = true)
        val assemblerDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            hlsWorkers = 4,
            keepTemp = true
        )

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:1
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data0".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "data1".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg2.ts"] = "data2".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = assemblerDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Pilot")

        assertTrue("Download should succeed", result.success)
        assertEquals("All playlist segments downloaded", 3, result.completedSegments)

        // Phase 8 identity: files are keyed by EXT-X-MEDIA-SEQUENCE, never by
        // enumerate() position — persisted names must stay untouched.
        assertTrue("000001.ts must exist", segmentStore.hasSeg(1))
        assertTrue("000002.ts must exist", segmentStore.hasSeg(2))
        assertTrue("000003.ts must exist", segmentStore.hasSeg(3))
        assertFalse("No positional 000000.ts is written for a sequence-1 playlist",
            segmentStore.hasSeg(0))

        // Assembly boundary contract: the downloader hands the assembler the
        // playlist's own file indices in playlist order.
        assertTrue(fakeAssembler.assembleCalled)
        val config = fakeAssembler.lastConfig!!
        assertEquals("segmentCount is the playlist size", 3, config.segmentCount)
        assertEquals(
            "Assembler must receive the playlist's file indices in playlist order",
            listOf(1, 2, 3),
            config.segmentIndices
        )
    }

    @Test
    fun `second run on a media sequence playlist refetches no segments and assembles the same files`() = runTest {
        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:1
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data0".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "data1".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg2.ts"] = "data2".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        // Run 1: segments only (no assembler) — the interrupted/failed-assembly state
        val storeDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = null,
            hlsWorkers = 4
        )
        val first = storeDownloader.download(streamInfo)
        assertTrue("First run should succeed", first.success)
        assertEquals(3, first.completedSegments)
        assertTrue(segmentStore.hasSeg(1) && segmentStore.hasSeg(2) && segmentStore.hasSeg(3))

        // Run 2: resume with an assembler — completes from the existing files
        val fakeAssembler = FakeAssembler(success = true)
        val assemblingDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            hlsWorkers = 4,
            keepTemp = true
        )
        val second = assemblingDownloader.download(streamInfo)

        assertTrue("Resume run should succeed", second.success)
        assertEquals("Resume must see all 3 completed segments", 3, second.completedSegments)
        assertEquals(
            "Segment 0 must not be refetched on resume",
            1, fakeHlsClient.callCountFor("https://cdn.example.com/seg0.ts")
        )
        assertEquals(
            "Segment 1 must not be refetched on resume",
            1, fakeHlsClient.callCountFor("https://cdn.example.com/seg1.ts")
        )
        assertEquals(
            "Segment 2 must not be refetched on resume",
            1, fakeHlsClient.callCountFor("https://cdn.example.com/seg2.ts")
        )

        // Assembled from the sequence-keyed files (000001..000003), not range(0, n)
        assertTrue(fakeAssembler.assembleCalled)
        assertEquals(listOf(1, 2, 3), fakeAssembler.lastConfig!!.segmentIndices)
        assertTrue("keepTemp=true must preserve the segments after assembly",
            segmentStore.hasSeg(1) && segmentStore.hasSeg(2) && segmentStore.hasSeg(3))
    }

    @Test
    fun `download classifies assembly failure as permanent error`() = runTest {
        val fakeAssembler = FakeAssembler(success = false, error = "FFmpeg concat failed")
        val retryDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            autoRetry = 2,
            hlsWorkers = 4
        )

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = retryDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Test")

        assertFalse("Download should fail", result.success)
        assertNotNull("Error should be set", result.error)
        assertTrue("Error should mention assembly", result.error!!.contains("Assembly"))
        // Assembly failure is permanent — should NOT retry (called only once)
        assertEquals("Assembly should be called exactly once (no retry for permanent error)",
            1, fakeAssembler.callCount)
    }

    @Test
    fun `download without assembler returns success without assembly`() = runTest {
        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        // No assembler provided
        val result = downloader.download(streamInfo)

        assertTrue("Download should succeed", result.success)
        assertFalse("Should NOT be marked as assembled", result.assembled)
        assertNull("Output file should be null", result.outputFile)
    }

    // ── Cancellation at the download → assembly boundary (Phase 11.5E-5) ──────────

    @Test
    fun `cancellation during assembly reports wasCancelled and keeps the segment files`() = runTest {
        // Deterministic: the fake signals entry into assemble() and then suspends on a
        // gate, so the cancellation always lands while assembly is in progress — no
        // timing race with FFmpeg or the scheduler.
        val assembleStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val fakeAssembler = FakeAssembler(
            success = true,
            assembleStarted = assembleStarted,
            awaitGate = gate
        )
        val cancellingDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = segmentStore,
            assembler = fakeAssembler,
            hlsWorkers = 4
        )

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:1
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
            #EXTINF:10.0,
            seg3.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "data1".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg2.ts"] = "data2".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg3.ts"] = "data3".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        var result: EpisodeDownloader.DownloadResult? = null
        val job = launch { result = cancellingDownloader.download(streamInfo) }

        // Wait until the download is suspended *inside* assembly, then cancel it.
        assembleStarted.await()
        job.cancelAndJoin()

        assertNotNull("download() must return a result on cancellation", result)
        val r = result!!
        assertTrue("Cancellation must be reported through wasCancelled", r.wasCancelled)
        assertFalse("A cancelled run is not a success", r.success)
        assertTrue("Assembly must have been entered before cancellation", fakeAssembler.assembleCalled)
        assertEquals("Assembly must be entered exactly once", 1, fakeAssembler.callCount)

        // Phase 8 / cache integrity: cancellation must not delete or corrupt segments.
        assertTrue("000001.ts must survive the cancellation", segmentStore.hasSeg(1))
        assertTrue("000002.ts must survive the cancellation", segmentStore.hasSeg(2))
        assertTrue("000003.ts must survive the cancellation", segmentStore.hasSeg(3))
        val leftovers = segmentStore.getDirectory().listFiles().orEmpty()
        assertTrue("No .tmp partial writes may remain: ${leftovers.joinToString { it.name }}",
            leftovers.none { it.name.endsWith(".tmp") })
    }

    // ── A2.1 closure (keepTemp audit GAP-4b): cancel DURING segment download ──

    @Test
    fun `cancellation during segment download keeps committed segments and never cleans up`() = runTest {
        // Deterministic landing, no sleeps and no races: seg1's fetch suspends
        // on a gate, and a store subclass signals AFTER seg0's atomic write
        // lands — so the cancellation always fires with exactly seg0 committed
        // and seg1 still in flight.
        val write0 = CompletableDeferred<Unit>()
        val signalStore = object : SegmentFileStore(segmentStore.getDirectory()) {
            override fun writeSeg(idx: Int, data: ByteArray) {
                super.writeSeg(idx, data)
                if (idx == 0) write0.complete(Unit)
            }
        }
        val fetch1Started = CompletableDeferred<Unit>()
        val fetch1Gate = CompletableDeferred<Unit>()
        fakeHlsClient.gateFetch("https://cdn.example.com/seg1.ts", fetch1Started, fetch1Gate)

        // A sentinel the success-path cleanup WOULD delete: its survival proves
        // no cleanup ran on the cancelled run.
        File(signalStore.getDirectory(), "metadata.txt").writeText("session metadata")

        val cancellingAssembler = FakeAssembler(success = true)
        val cancellingDownloader = EpisodeDownloader(
            hlsClient = fakeHlsClient,
            keyCache = AesKeyCache(ttlMs = 60_000L, maxSize = 10),
            segmentStore = signalStore,
            assembler = cancellingAssembler,
            hlsWorkers = 2
        )

        val m3u8Content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()

        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "seg0data".toByteArray()
        fakeHlsClient.byteResponses["https://cdn.example.com/seg1.ts"] = "seg1data".toByteArray()

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        var result: EpisodeDownloader.DownloadResult? = null
        val job = launch { result = cancellingDownloader.download(streamInfo, episodeNumber = 1, episodeTitle = "Pilot") }

        // Wait until seg1 is IN FLIGHT and seg0 is COMMITTED on disk, then cancel.
        fetch1Started.await()
        write0.await()
        assertTrue("seg0 must be committed before the cancellation", signalStore.hasSeg(0))
        job.cancelAndJoin()

        assertNotNull("download() must return a result on cancellation", result)
        val r = result!!
        assertTrue("Cancellation must be reported through wasCancelled", r.wasCancelled)
        assertFalse("A cancelled run is not a success", r.success)
        assertFalse(
            "assembly must never be reached when a segment fetch is cancelled",
            cancellingAssembler.assembleCalled
        )

        // Completed segments survive; the in-flight one never became a .ts.
        assertTrue("000000.ts must survive the cancellation", signalStore.hasSeg(0))
        assertFalse("000001.ts must not exist — its fetch was cancelled", signalStore.hasSeg(1))
        val leftovers = signalStore.getDirectory().listFiles().orEmpty()
        assertTrue(
            "No atomic-write .tmp partial may remain: ${leftovers.joinToString { it.name }}",
            leftovers.none { it.name.endsWith(".tmp") }
        )
        // The success-path cleanup never ran: the non-media sentinel survives
        // (SegmentFileStore.cleanup would have removed every non-.mp4 child).
        assertTrue(
            "cleanup must not run on the cancelled run",
            File(signalStore.getDirectory(), "metadata.txt").exists()
        )
    }

    // ── 11.5E-6: existing-output short-circuit (Python downloader.py:60-62) ──

    @Test
    fun `existing non-empty output short-circuits before any network request`() = runTest {
        val m3u8Content = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "segment0data".toByteArray()

        val episodeNumber = 3
        val episodeTitle = "Mission Report"
        val expectedOutput = File(
            segmentStore.getDirectory(),
            FileNameGenerator.episodeFileName(
                FileNameGenerator.formatEpisodePrefix(episodeNumber),
                episodeTitle,
            ),
        )
        expectedOutput.writeBytes("already-assembled-mp4".toByteArray())

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(
            streamInfo,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
        )

        assertTrue("An existing non-empty output completes the episode", result.success)
        assertTrue("the output is the assembled file", result.assembled)
        assertEquals(expectedOutput, result.outputFile)
        assertTrue(
            "the run must be flagged as a reuse so the Worker does not overwrite persisted progress",
            result.reusedExistingOutput,
        )
        assertEquals("no segment work ran", 0, result.completedSegments)
        assertTrue(
            "zero network I/O — Python checks out.exists() BEFORE fetch_m3u8 (downloader.py:60-62)",
            fakeHlsClient.requestedUrls.isEmpty(),
        )
    }

    @Test
    fun `empty existing output is ignored and the download runs normally`() = runTest {
        val m3u8Content = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        fakeHlsClient.textResponses["https://cdn.example.com/playlist.m3u8"] = m3u8Content
        fakeHlsClient.byteResponses["https://cdn.example.com/seg0.ts"] = "segment0data".toByteArray()

        val episodeNumber = 5
        val episodeTitle = "Half Episode"
        // A zero-byte leftover must never satisfy Python's `st_size > 0` rule.
        val leftover = File(
            segmentStore.getDirectory(),
            FileNameGenerator.episodeFileName(
                FileNameGenerator.formatEpisodePrefix(episodeNumber),
                episodeTitle,
            ),
        )
        assertTrue(leftover.createNewFile())
        assertEquals(0L, leftover.length())

        val streamInfo = StreamInfo(
            url = "https://cdn.example.com/playlist.m3u8",
            userAgent = "TestAgent",
            referer = "https://animepahe.com/"
        )

        val result = downloader.download(
            streamInfo,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
        )

        assertTrue("a zero-byte output does not suppress the download", result.success)
        assertFalse(result.reusedExistingOutput)
        assertEquals(1, result.completedSegments)
        assertTrue(segmentStore.hasSeg(0))
        assertTrue("the manifest was really fetched", fakeHlsClient.requestedUrls.isNotEmpty())
    }
}

/**
 * Fake EpisodeAssembler for testing.
 */
class FakeAssembler(
    private val success: Boolean = true,
    private val error: String? = null,
    /**
     * Completed when [assemble] is entered, before any gate is awaited.
     * Used by the cancellation test to deterministically land a cancellation *inside*
     * assembly rather than racing the coroutine scheduler.
     */
    private val assembleStarted: CompletableDeferred<Unit>? = null,
    /**
     * When set, [assemble] suspends here until the test cancels the
     * caller or releases the gate. Null (default) keeps every existing test's behavior.
     */
    private val awaitGate: CompletableDeferred<Unit>? = null
) : com.pahebatcher.app.domain.hls.EpisodeAssembler {

    var assembleCalled = false
    var callCount = 0
    var lastConfig: com.pahebatcher.app.domain.hls.EpisodeAssembler.AssemblyConfig? = null

    override suspend fun assemble(config: com.pahebatcher.app.domain.hls.EpisodeAssembler.AssemblyConfig):
            com.pahebatcher.app.domain.hls.EpisodeAssembler.AssemblyResult {
        assembleCalled = true
        callCount++
        lastConfig = config
        assembleStarted?.complete(Unit)
        awaitGate?.await()

        return if (success) {
            // Create a fake output file to simulate successful assembly
            config.outputFile.writeBytes("fake mp4 content".toByteArray())
            com.pahebatcher.app.domain.hls.EpisodeAssembler.AssemblyResult(
                success = true,
                outputFile = config.outputFile
            )
        } else {
            com.pahebatcher.app.domain.hls.EpisodeAssembler.AssemblyResult(
                success = false,
                error = error ?: "Assembly failed"
            )
        }
    }
}

/**
 * Fake HlsClient for testing.
 */
class FakeHlsClient : HlsClient {
    val textResponses = mutableMapOf<String, String>()
    val byteResponses = mutableMapOf<String, ByteArray>()
    // EpisodeDownloader fetches segments on Dispatchers.IO with
    // hlsWorkers>1 (EpisodeDownloader.kt:357), so these books are mutated from
    // multiple real threads. Plain ArrayList/HashMap lose updates under that
    // concurrency (flaky "segN should be fetched" assertions) — synchronize them.
    // No assertion semantics change: only the recording becomes reliable.
    val requestedUrls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    var lastRequestContext: HlsClient.RequestContext? = null

    // Failure simulation
    private val failOnceUrls: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val failAlwaysUrls: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val callCounts: MutableMap<String, Int> = java.util.Collections.synchronizedMap(mutableMapOf())

    fun failOnceFor(url: String) { failOnceUrls.add(url) }
    fun failAlwaysFor(url: String) { failAlwaysUrls.add(url) }
    fun callCountFor(url: String): Int = callCounts[url] ?: 0

    // HTTP-status and local-exception simulation.
    // Both default EMPTY, so every pre-existing test behaves byte-for-byte as
    // before — they exist so `DownloadErrorClassMappingTest` can drive the
    // construction sites a plain NetworkError never reaches (403/404 → EXPIRED,
    // 401 exhausted → TRANSPORT, an exception escaping the transport → the
    // `download()` catch → IN_RUN_RETRYABLE).
    private val httpErrorCodes: MutableMap<String, Int> =
        java.util.Collections.synchronizedMap(mutableMapOf())
    private val throwingUrls: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    /** Serve [code] as the HTTP status for [url] instead of a body. */
    fun httpErrorFor(url: String, code: Int) { httpErrorCodes[url] = code }

    /**
     * Throw from the transport for [url]. Stands in for the local/unexpected
     * exception the plan routes to `download()`'s catch — `OkHttpHlsClient`
     * converts every real network exception to `NetworkError`, so a socket
     * problem can never be produced this way.
     */
    fun throwFor(url: String) { throwingUrls.add(url) }

    // ── A2.1 closure (keepTemp audit): deterministic cancellation landing ──
    // INSIDE a segment fetch. Default-off: no gate is registered unless a test
    // calls [gateFetch], so every pre-existing test behaves byte-for-byte as
    // before.
    private class FetchGate(
        val started: CompletableDeferred<Unit>,
        val gate: CompletableDeferred<Unit>,
    )
    private val fetchGates: MutableMap<String, FetchGate> =
        java.util.Collections.synchronizedMap(mutableMapOf())

    /**
     * Suspend the next fetch of [url] until [gate] completes; [started] fires
     * when the fetch has arrived at the gate.
     */
    fun gateFetch(url: String, started: CompletableDeferred<Unit>, gate: CompletableDeferred<Unit>) {
        fetchGates[url] = FetchGate(started, gate)
    }

    private fun shouldFail(url: String): Boolean {
        if (failAlwaysUrls.contains(url)) return true
        if (failOnceUrls.contains(url)) {
            failOnceUrls.remove(url)
            return true
        }
        return false
    }

    override suspend fun getText(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long
    ): HlsTextResult {
        requestedUrls.add(url)
        lastRequestContext = context
        callCounts[url] = (callCounts[url] ?: 0) + 1
        if (throwingUrls.contains(url)) throw IllegalStateException("local failure for $url")
        httpErrorCodes[url]?.let { return HlsTextResult.HttpError(it, url) }
        if (shouldFail(url)) return HlsTextResult.NetworkError("Fake failure", url)
        val content = textResponses[url] ?: return HlsTextResult.NetworkError("No response configured", url)
        return HlsTextResult.Success(content)
    }

    override suspend fun getBytes(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long
    ): HlsBytesResult {
        requestedUrls.add(url)
        lastRequestContext = context
        callCounts[url] = (callCounts[url] ?: 0) + 1
        fetchGates[url]?.let {
            it.started.complete(Unit)
            it.gate.await()
        }
        if (throwingUrls.contains(url)) throw IllegalStateException("local failure for $url")
        httpErrorCodes[url]?.let { return HlsBytesResult.HttpError(it, url) }
        if (shouldFail(url)) return HlsBytesResult.NetworkError("Fake failure", url)
        val bytes = byteResponses[url] ?: return HlsBytesResult.NetworkError("No response configured", url)
        return HlsBytesResult.Success(bytes)
    }
}
