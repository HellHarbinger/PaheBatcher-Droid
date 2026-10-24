package com.pahebatcher.app.data.hls

import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.model.StreamInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SPEC §25 — byte accounting in [EpisodeDownloader].
 *
 * The progress callback now carries `(completed, total, downloadedBytes)`, where
 * `downloadedBytes` is the running total of DECRYPTED segment bytes that really
 * reached disk. These tests pin the four properties that make that number
 * trustworthy on a real device:
 *
 * 1. it equals what was written (the callback reports actual sizes);
 * 2. segments from a previous run establish the resume base exactly once;
 * 3. a retried fetch, or a later resumed run, never double counts;
 * 4. `.tmp` partial writes and the muxed output never count.
 *
 * Fixtures only — no network (AGENTS §5: fakes live in tests).
 */
class EpisodeDownloaderByteAccountingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeHlsClient: FakeHlsClient
    private lateinit var segmentStore: SegmentFileStore

    private val seg0Bytes = "segment-zero-payload".toByteArray()   // 20 bytes
    private val seg1Bytes = "segment-one-payload-longer".toByteArray() // 27 bytes

    private val streamInfo = StreamInfo(
        url = "https://cdn.example.com/playlist.m3u8",
        userAgent = "TestAgent",
        referer = "https://animepahe.com/",
    )

    @Before
    fun setup() {
        fakeHlsClient = FakeHlsClient()
        segmentStore = SegmentFileStore(tempFolder.newFolder("segments"))
        fakeHlsClient.textResponses[PLAYLIST] = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()
        fakeHlsClient.byteResponses[SEG0] = seg0Bytes
        fakeHlsClient.byteResponses[SEG1] = seg1Bytes
    }

    private fun downloader(client: HlsClient = fakeHlsClient) = EpisodeDownloader(
        hlsClient = client,
        keyCache = AesKeyCache(),
        segmentStore = segmentStore,
        hlsWorkers = 2,
        autoRetry = 0,
    )

    /**
     * Records every (completed, total, bytes) the production callback emitted.
     *
     * Thread-safe on purpose: the segment workers run concurrently, so two
     * callbacks can be appended at the same time — and, for the same reason, the
     * ORDER they arrive in is not part of what is being asserted here.
     */
    private class ProgressLog {
        val entries: MutableList<Triple<Int, Int, Long>> =
            java.util.concurrent.CopyOnWriteArrayList()
        val bytes: List<Long> get() = entries.map { it.third }
    }

    @Test
    fun `the callback reports the real bytes every segment wrote`() = runTest {
        val log = ProgressLog()

        val result = downloader().download(
            streamInfo,
            onProgress = { completed, total, downloadedBytes ->
                log.entries += Triple(completed, total, downloadedBytes)
            },
        )

        assertTrue("download succeeds", result.success)
        assertEquals(2, result.completedSegments)
        assertEquals("one callback per segment write", 2, log.entries.size)
        // Two segment workers run concurrently: which segment finishes first is a
        // scheduling detail (the first total is 20 or 26 bytes accordingly), so
        // the ORDER and the identity of the first value are not asserted — the
        // byte arithmetic is.
        val totalBytes = seg0Bytes.size.toLong() + seg1Bytes.size.toLong()
        assertEquals(
            "the largest reported total is exactly the bytes on disk",
            segmentStore.completedSegmentBytes(),
            log.bytes.max(),
        )
        assertEquals(
            "the largest reported total is exactly one write per segment",
            totalBytes,
            log.bytes.max(),
        )
        assertTrue(
            "every reported total is a real cumulative byte count " +
                "(after one write or after both) and never exceeds what is on disk",
            log.bytes.all {
                (it == totalBytes || it == seg0Bytes.size.toLong() || it == seg1Bytes.size.toLong()) &&
                    it <= segmentStore.completedSegmentBytes()
            },
        )
    }

    @Test
    fun `segments already on disk establish the resume base exactly once`() = runTest {
        // A previous run really wrote segment 0 and was interrupted.
        segmentStore.writeSeg(0, seg0Bytes)
        val base = segmentStore.completedSegmentBytes()
        assertEquals(seg0Bytes.size.toLong(), base)

        val log = ProgressLog()
        val result = downloader().download(
            streamInfo,
            onProgress = { _, _, downloadedBytes -> log.entries += Triple(0, 0, downloadedBytes) },
        )

        assertTrue(result.success)
        assertEquals("only the pending segment is written", 1, log.entries.size)
        assertEquals(
            "the pre-existing segment counts toward the total, exactly once",
            base + seg1Bytes.size.toLong(),
            log.entries.single().third,
        )
        assertEquals(
            "2 completed segments (1 reused + 1 new)",
            2,
            result.completedSegments,
        )
    }

    @Test
    fun `a resumed run after a failed attempt never double counts bytes`() = runTest {
        // Attempt 1: segment 1 is gone from the origin (404 → permanent, no retry).
        fakeHlsClient.httpErrorFor(SEG1, 404)
        val firstAttempt = ProgressLog()
        val failed = downloader().download(
            streamInfo,
            onProgress = { _, _, downloadedBytes ->
                firstAttempt.entries += Triple(0, 0, downloadedBytes)
            },
        )

        assertTrue("the run really failed", !failed.success)
        assertEquals("segment 0 still landed", 1, failed.completedSegments)
        assertEquals(
            "only the segment that was written contributes",
            seg0Bytes.size.toLong(),
            firstAttempt.entries.last().third,
        )

        // Attempt 2 (a later Worker run): a healthy origin, the SAME segment dir.
        val healthyClient = FakeHlsClient().apply {
            textResponses[PLAYLIST] = fakeHlsClient.textResponses.getValue(PLAYLIST)
            byteResponses[SEG0] = seg0Bytes
            byteResponses[SEG1] = seg1Bytes
        }
        val secondAttempt = ProgressLog()
        val resumed = downloader(healthyClient).download(
            streamInfo,
            onProgress = { _, _, downloadedBytes ->
                secondAttempt.entries += Triple(0, 0, downloadedBytes)
            },
        )

        assertTrue("the resumed run succeeds", resumed.success)
        assertEquals(2, resumed.completedSegments)
        assertEquals(
            "the reused segment is not re-downloaded, so only one write is reported",
            1,
            secondAttempt.entries.size,
        )
        assertEquals(
            "segment 0 (from run 1) + segment 1 (this run), counted once each",
            seg0Bytes.size.toLong() + seg1Bytes.size.toLong(),
            secondAttempt.entries.single().third,
        )
        assertTrue(
            "the resumed total starts at (never below) the resume base",
            secondAttempt.entries.all { it.third >= seg0Bytes.size.toLong() },
        )
    }

    @Test
    fun `a retried fetch counts its bytes exactly once`() = runTest {
        // The first HTTP attempt for segment 1 fails; the transport-level retry
        // inside fetchSegmentWithRetry succeeds, so the segment is written once.
        fakeHlsClient.failOnceFor(SEG1)
        val log = ProgressLog()

        val result = downloader().download(
            streamInfo,
            onProgress = { _, _, downloadedBytes -> log.entries += Triple(0, 0, downloadedBytes) },
        )

        assertTrue("the retry recovered", result.success)
        assertEquals("exactly one write per segment, retry included", 2, log.entries.size)
        // Delivery order between the two segment workers is not asserted (it is a
        // scheduling detail); the byte total they produced is.
        assertEquals(
            seg0Bytes.size.toLong() + seg1Bytes.size.toLong(),
            log.bytes.max(),
        )
        assertEquals(
            "the retry never wrote a second copy of the segment",
            segmentStore.completedSegmentBytes(),
            log.bytes.max(),
        )
    }

    @Test
    fun `partial writes and the muxed output are never counted as downloaded bytes`() = runTest {
        val log = ProgressLog()
        val result = downloader().download(
            streamInfo,
            onProgress = { _, _, downloadedBytes -> log.entries += Triple(0, 0, downloadedBytes) },
        )
        assertTrue(result.success)
        val counted = log.bytes.max()

        // A `.tmp` (interrupted write) and a muxed `.mp4` appear next to the
        // segments — neither is downloaded segment data.
        segmentStore.getDirectory().resolve("000007.tmp").writeBytes(ByteArray(9_999_999))
        segmentStore.getDirectory().resolve("Ep 001 - Pilot.mp4").writeBytes(ByteArray(9_999_999))

        assertEquals(
            "neither the partial write nor the muxed output adds a single byte",
            counted,
            segmentStore.completedSegmentBytes(),
        )
        assertEquals(
            seg0Bytes.size.toLong() + seg1Bytes.size.toLong(),
            segmentStore.completedSegmentBytes(),
        )
    }

    private companion object {
        const val PLAYLIST = "https://cdn.example.com/playlist.m3u8"
        const val SEG0 = "https://cdn.example.com/seg0.ts"
        const val SEG1 = "https://cdn.example.com/seg1.ts"
    }
}
