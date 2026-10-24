package com.pahebatcher.app.data.hls

import com.pahebatcher.app.domain.hls.EpisodeAssembler
import com.pahebatcher.app.domain.model.StreamInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **every construction site → expected
 * [DownloadErrorClass]**, decided by ORIGIN and never by message text.
 *
 * The suite drives the real `EpisodeDownloader` through the shared [FakeHlsClient]
 * (extended in `EpisodeDownloaderTest` with an HTTP-status knob and a
 * local-exception knob, both empty by default so every pre-existing test is
 * untouched) and asserts the class on the returned `DownloadResult`.
 *
 * **Sites this drives:** success (`NONE`), empty playlist (`NO_CONTENT`),
 * playlist HTTP 403 (`EXPIRED`), playlist HTTP 401 exhausted and a network
 * failure (`TRANSPORT`), variant HTTP 403 (`EXPIRED`), AES-key HTTP 403
 * (`EXPIRED`) and an exhausted AES-key fetch (`TRANSPORT`), failed assembly
 * (`ASSEMBLY`), an exception escaping `downloadOnce` into `download()`'s catch
 * (`IN_RUN_RETRYABLE`), plus all three aggregation priority rules.
 *
 * **Sites this deliberately does NOT drive** (verified against
 * `HlsPlaylistParser.parse`, not assumed): `"Failed to parse M3U8"` and
 * `"Master playlist has no variants"` are unreachable through the real parser —
 * `parse` has no `throw` (it uses `toIntOrNull() ?: 0` and skips malformed
 * lines) and returns `isMaster = true` only when `variants.isNotEmpty()`. Both
 * remain typed `IN_RUN_RETRYABLE` / `NO_CONTENT` respectively as defensive
 * construction-site contracts; no test is written that would need a parser
 * double, because a double would only prove the double.
 *
 * Every test runs with `autoRetry = 0` (a single episode attempt) unless the
 * assertion is specifically about the in-run HTTP budget, so the asserted class
 * is the one the construction site produced rather than the residue of a retry.
 */
class DownloadErrorClassMappingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var client: FakeHlsClient
    private lateinit var segmentStore: SegmentFileStore

    private val playlistUrl = "https://cdn.example.com/playlist.m3u8"
    private val masterUrl = "https://cdn.example.com/master.m3u8"
    private val variantUrl = "https://cdn.example.com/high.m3u8"
    private val keyUrl = "https://key.url/key.bin"
    private val seg0Url = "https://cdn.example.com/seg0.ts"
    private val seg1Url = "https://cdn.example.com/seg1.ts"

    private val mediaPlaylistTwoSegments = """
        #EXTM3U
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:10.0,
        seg0.ts
        #EXTINF:10.0,
        seg1.ts
    """.trimIndent()

    private val masterPlaylist = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=2000000
        high.m3u8
    """.trimIndent()

    private val aesKey = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
    )

    @Before
    fun setup() {
        client = FakeHlsClient()
        segmentStore = SegmentFileStore(tempFolder.newFolder("segments"))
    }

    private fun downloader(
        autoRetry: Int = 0,
        assembler: EpisodeAssembler? = null,
        store: SegmentFileStore = segmentStore,
        availableBytes: (java.io.File) -> Long = StorageSpace::availableBytes,
    ) = EpisodeDownloader(
        hlsClient = client,
        keyCache = AesKeyCache(),
        segmentStore = store,
        assembler = assembler,
        autoRetry = autoRetry,
        hlsWorkers = 4,
        availableBytes = availableBytes,
    )

    private fun stream(url: String = playlistUrl) = StreamInfo(
        url = url,
        userAgent = "TestAgent",
        referer = "https://animepahe.com/",
    )

    /** 16 bytes whose last byte is 0x5A — not a valid PKCS#7 padding value. */
    /**
     * Ciphertext neither implementation can decrypt: it is not a multiple of the
     * 16-byte AES block, so pycryptodome raises `ValueError` and Android's `Cipher`
     * raises `IllegalBlockSizeException`.
     *
     * NOTE: a block-aligned payload of arbitrary bytes is deliberately NOT used here.
     * Python's `cipher.decrypt()` never inspects padding, so a 16-byte block of 0x5A
     * decrypts successfully there; only the old `PKCS5Padding` build rejected it.
     * Anything this returns must fail in the reference too.
     */
    private fun undecryptableBytes() = ByteArray(20) { 0x5A }

    // ── Success / content / status ───────────────────────────────────

    @Test
    fun `a successful download carries NONE`() = runTest {
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        client.byteResponses[seg0Url] = "data".toByteArray()
        client.byteResponses[seg1Url] = "data".toByteArray()

        val result = downloader().download(stream())

        assertTrue("the download itself must succeed", result.success)
        assertEquals(DownloadErrorClass.NONE, result.errorClass)
    }

    @Test
    fun `an empty playlist is NO_CONTENT and ends the in-run budget immediately`() = runTest {
        client.textResponses[playlistUrl] = "#EXTM3U\n"

        val result = downloader(autoRetry = 2).download(stream())

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.NO_CONTENT, result.errorClass)
        // The persisted message text is unchanged (§14: `error` stays a String).
        assertTrue(result.error!!.contains("No segments"))
        assertEquals(
            "a permanent class must stop the episode budget on the first attempt",
            1,
            client.callCountFor(playlistUrl),
        )
    }

    @Test
    fun `an HTTP 403 playlist is EXPIRED and ends the in-run budget immediately`() = runTest {
        client.httpErrorFor(playlistUrl, 403)

        val result = downloader(autoRetry = 2).download(stream())

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.EXPIRED, result.errorClass)
        assertEquals(1, client.callCountFor(playlistUrl))
    }

    @Test
    fun `an HTTP 404 variant is EXPIRED`() = runTest {
        client.textResponses[masterUrl] = masterPlaylist
        client.httpErrorFor(variantUrl, 404)

        val result = downloader(autoRetry = 2).download(stream(masterUrl))

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.EXPIRED, result.errorClass)
    }

    @Test
    fun `an HTTP 403 AES key is EXPIRED`() = runTest {
        client.textResponses[playlistUrl] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="$keyUrl"
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        client.httpErrorFor(keyUrl, 403)

        val result = downloader(autoRetry = 2).download(stream())

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.EXPIRED, result.errorClass)
    }

    // ── Transport ────────────────────────────────────────────────────

    @Test
    fun `an HTTP 401 playlist exhausts the in-run budget and stays TRANSPORT`() = runTest {
        client.httpErrorFor(playlistUrl, 401)

        val result = downloader(autoRetry = 0).download(stream())

        assertFalse(result.success)
        assertEquals(
            "401 is neither 403/404 nor a socket failure — it is a status the " +
                "in-run budget exhausts, exactly like a 5xx",
            DownloadErrorClass.TRANSPORT,
            result.errorClass,
        )
        // 1 episode attempt (autoRetry = 0) × the 5 in-run HTTP attempts.
        assertEquals(5, client.callCountFor(playlistUrl))
    }

    @Test
    fun `a network failure is TRANSPORT`() = runTest {
        client.failAlwaysFor(playlistUrl)

        val result = downloader(autoRetry = 0).download(stream())

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.TRANSPORT, result.errorClass)
        assertEquals(5, client.callCountFor(playlistUrl))
    }

    @Test
    fun `an exhausted AES key fetch is TRANSPORT`() = runTest {
        client.textResponses[playlistUrl] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="$keyUrl"
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        val result = downloader(autoRetry = 0).download(stream())

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.TRANSPORT, result.errorClass)
        assertEquals(5, client.callCountFor(keyUrl))
    }

    // ── Assembly / local ─────────────────────────────────────────────

    @Test
    fun `a failed assembly is ASSEMBLY`() = runTest {
        client.textResponses[playlistUrl] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        client.byteResponses[seg0Url] = "data".toByteArray()
        val assembler = FakeAssembler(success = false, error = "FFmpeg concat failed")

        val result = downloader(autoRetry = 2, assembler = assembler).download(
            stream(),
            episodeNumber = 1,
            episodeTitle = "Mapping",
        )

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.ASSEMBLY, result.errorClass)
        assertEquals(
            "a permanent class must stop the episode budget on the first attempt",
            1,
            assembler.callCount,
        )
    }

    @Test
    fun `a local exception escaping downloadOnce is IN_RUN_RETRYABLE`() = runTest {
        // The plan's edge case: `OkHttpHlsClient` converts every network
        // exception to `NetworkError`, so what reaches this catch is local or
        // unexpected — never a connectivity problem.
        client.throwFor(playlistUrl)

        val result = downloader(autoRetry = 0).download(stream())

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.IN_RUN_RETRYABLE, result.errorClass)
        assertEquals(1, client.callCountFor(playlistUrl))
    }

    // ── Aggregation priority rules (§14) ─────────────────────────────

    @Test
    fun `aggregation rule 1 - one expired segment makes the whole failed run EXPIRED`() = runTest {
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        // seg0 → 403 (EXPIRED); seg1 unconfigured → NetworkError (TRANSPORT).
        client.httpErrorFor(seg0Url, 403)

        val result = downloader(autoRetry = 0).download(stream())

        assertFalse(result.success)
        assertEquals(2, result.failedSegments)
        assertEquals(DownloadErrorClass.EXPIRED, result.errorClass)
    }

    @Test
    fun `aggregation rule 2 - a run whose segments all failed transiently is TRANSPORT`() = runTest {
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        // Neither segment is configured → NetworkError for both.

        val result = downloader(autoRetry = 0).download(stream())

        assertFalse(result.success)
        assertEquals(2, result.failedSegments)
        assertEquals(DownloadErrorClass.TRANSPORT, result.errorClass)
    }

    @Test
    fun `aggregation rule 3 - a decryption failure is IN_RUN_RETRYABLE`() = runTest {
        client.textResponses[playlistUrl] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="$keyUrl"
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()
        client.byteResponses[keyUrl] = aesKey
        client.byteResponses[seg0Url] = undecryptableBytes()

        val result = downloader(autoRetry = 0).download(stream())

        assertFalse(result.success)
        assertEquals(1, result.failedSegments)
        assertEquals(DownloadErrorClass.IN_RUN_RETRYABLE, result.errorClass)
    }

    @Test
    fun `aggregation rule 3 - a mixed run containing a local failure is IN_RUN_RETRYABLE not TRANSPORT`() = runTest {
        client.textResponses[playlistUrl] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="$keyUrl"
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
        """.trimIndent()
        client.byteResponses[keyUrl] = aesKey
        // seg0 unconfigured → NetworkError (TRANSPORT); seg1 present but not
        // valid ciphertext → decryption failure (local). Not all-TRANSPORT, and
        // nothing expired — rule 3 wins.
        client.byteResponses[seg1Url] = undecryptableBytes()

        val result = downloader(autoRetry = 0).download(stream())

        assertFalse(result.success)
        assertEquals(2, result.failedSegments)
        assertEquals(DownloadErrorClass.IN_RUN_RETRYABLE, result.errorClass)
    }

    // ── Storage (final-audit H2: SPEC §13 "insufficient storage", §26 `DiskFull`) ──

    /**
     * A store whose write fails exactly the way a full volume fails.
     *
     * No test can fill a healthy volume, and the OS reports `ENOSPC` as TEXT on
     * the [java.io.IOException] — so this double supplies that text at the only
     * production call site that reads it ([EpisodeDownloader]'s segment catch).
     * [failingIndices] `null` fails every write; a set fails only those indices,
     * which is what the aggregation-priority case needs.
     */
    private class OutOfSpaceSegmentStore(
        directory: java.io.File,
        private val failingIndices: Set<Int>? = null,
    ) : SegmentFileStore(directory) {
        override fun writeSeg(idx: Int, data: ByteArray) {
            if (failingIndices == null || idx in failingIndices) {
                throw java.io.IOException("No space left on device")
            }
            super.writeSeg(idx, data)
        }
    }

    @Test
    fun `a segment write that runs out of space is DISK_FULL and ends the budget immediately`() = runTest {
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        client.byteResponses[seg0Url] = "data".toByteArray()
        client.byteResponses[seg1Url] = "data".toByteArray()
        val store = OutOfSpaceSegmentStore(tempFolder.newFolder("out-of-space"))

        // autoRetry = 2 proves permanence: the budget is available and NOT spent.
        val result = downloader(autoRetry = 2, store = store).download(stream())

        assertFalse(result.success)
        assertEquals(2, result.failedSegments)
        assertEquals(DownloadErrorClass.DISK_FULL, result.errorClass)
        assertTrue(
            "the user-facing message must say storage, not 'exception'",
            result.error?.contains("not enough storage space", ignoreCase = true) == true,
        )
        assertEquals(
            "a permanent class must not re-fetch the playlist",
            1,
            client.callCountFor(playlistUrl),
        )
    }

    @Test
    fun `the storage pre-flight refuses a resumable run before any segment is fetched`() = runTest {
        val store = SegmentFileStore(tempFolder.newFolder("pre-flight"))
        // One segment already on disk (16 bytes) = the estimate's only sample.
        store.writeSeg(0, "0123456789abcdef".toByteArray())
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        client.byteResponses[seg0Url] = "data".toByteArray()
        client.byteResponses[seg1Url] = "data".toByteArray()

        // 1 byte free < the 16 bytes the remaining segment needs.
        val result = downloader(autoRetry = 2, store = store, availableBytes = { 1L }).download(stream())

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.DISK_FULL, result.errorClass)
        assertEquals("the already-completed segment stays completed", 1, result.completedSegments)
        assertTrue(
            "the message must state the numbers (SPEC §26 human-readable)",
            result.error?.contains("Not enough storage space") == true,
        )
        assertTrue("the playlist was read", client.requestedUrls.contains(playlistUrl))
        assertFalse(
            "NO segment may be fetched when the gate refuses",
            client.requestedUrls.contains(seg0Url) || client.requestedUrls.contains(seg1Url),
        )
        assertTrue("nothing may be written", store.doneIndices().size == 1)
    }

    @Test
    fun `the storage pre-flight never refuses when the free-space figure is unknown`() = runTest {
        val store = SegmentFileStore(tempFolder.newFolder("pre-flight-unknown"))
        store.writeSeg(0, "0123456789abcdef".toByteArray())
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        client.byteResponses[seg0Url] = "data".toByteArray()
        client.byteResponses[seg1Url] = "data".toByteArray()

        // 0 = File.usableSpace reports nothing; an unknown figure must not block.
        val result = downloader(autoRetry = 0, store = store, availableBytes = { 0L }).download(stream())

        assertTrue("an unknown measurement must never refuse the run", result.success)
        assertEquals(DownloadErrorClass.NONE, result.errorClass)
    }

    @Test
    fun `an assembly that ran out of space is DISK_FULL not ASSEMBLY`() = runTest {
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        client.byteResponses[seg0Url] = "data".toByteArray()
        client.byteResponses[seg1Url] = "data".toByteArray()
        val assembler = FakeAssembler(success = false, error = "av_interleaving_write: No space left on device")

        val result = downloader(autoRetry = 2, assembler = assembler).download(stream())

        assertFalse(result.success)
        assertNotNull("the assembly reason still travels for diagnostics", result.assemblyError)
        assertEquals(DownloadErrorClass.DISK_FULL, result.errorClass)
        assertTrue(
            "the user-facing message must say storage",
            result.error?.contains("Not enough storage space") == true,
        )
    }

    @Test
    fun `an assembly failure without an out-of-space reason stays ASSEMBLY`() = runTest {
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        client.byteResponses[seg0Url] = "data".toByteArray()
        client.byteResponses[seg1Url] = "data".toByteArray()
        val assembler = FakeAssembler(success = false, error = "concat demuxer mismatch")

        val result = downloader(autoRetry = 2, assembler = assembler).download(stream())

        assertFalse(result.success)
        assertEquals(DownloadErrorClass.ASSEMBLY, result.errorClass)
    }

    @Test
    fun `aggregation rule 0 - an out-of-space segment outranks an expired one`() = runTest {
        client.textResponses[playlistUrl] = mediaPlaylistTwoSegments
        // seg0 downloads fine but its WRITE runs out of space (DISK_FULL);
        // seg1 is refused with 403 (EXPIRED). Storage wins: fixing the HTTP
        // status would still leave the volume full.
        client.byteResponses[seg0Url] = "data".toByteArray()
        client.httpErrorFor(seg1Url, 403)
        val store = OutOfSpaceSegmentStore(tempFolder.newFolder("mixed"), failingIndices = setOf(0))

        val result = downloader(autoRetry = 0, store = store).download(stream())

        assertFalse(result.success)
        assertEquals(2, result.failedSegments)
        assertEquals(DownloadErrorClass.DISK_FULL, result.errorClass)
    }
}
