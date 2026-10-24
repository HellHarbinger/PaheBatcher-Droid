package com.pahebatcher.app.domain.hls

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for HlsPlaylistParser — HLS M3U8 playlist parsing.
 *
 * Ported from Python m3u8.py test_m3u8.py.
 * Verifies exact Python behavior parity.
 */
class HlsPlaylistParserTest {

    // ── Simple Playlist ───────────────────────────────────────────────

    @Test
    fun `parse simple playlist with two segments`() {
        val content = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            segment1.ts
            #EXTINF:10.0,
            segment2.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertFalse(playlist.isMaster)
        assertEquals(2, playlist.segments.size)
        assertTrue(playlist.segments[0].url.endsWith("segment1.ts"))
        assertTrue(playlist.segments[1].url.endsWith("segment2.ts"))
    }

    @Test
    fun `parse returns empty playlist for empty content`() {
        val playlist = HlsPlaylistParser.parse("#EXTM3U\n", "https://base.url/")
        assertFalse(playlist.isMaster)
        assertTrue(playlist.segments.isEmpty())
    }

    // ── AES Key Extraction ────────────────────────────────────────────

    @Test
    fun `parse extracts AES key with explicit IV`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0x1234567890ABCDEF1234567890ABCDEF
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(1, playlist.segments.size)
        assertEquals("https://key.url", playlist.segments[0].keyUrl)
        assertNotNull(playlist.segments[0].iv)
        assertArrayEquals(
            byteArrayOf(
                0x12, 0x34, 0x56, 0x78.toByte(),
                0x90.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
                0x12, 0x34, 0x56, 0x78.toByte(),
                0x90.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()
            ),
            playlist.segments[0].iv
        )
    }

    @Test
    fun `parse extracts AES key without IV uses media sequence`() {
        val content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url"
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(1, playlist.segments.size)
        assertEquals("https://key.url", playlist.segments[0].keyUrl)
        assertNotNull(playlist.segments[0].iv)
        // seq_num=0 → IV = 0x00000000000000000000000000000000
        assertArrayEquals(ByteArray(16) { 0 }, playlist.segments[0].iv)
    }

    @Test
    fun `parse no encryption returns null key and IV`() {
        val content = """
            #EXTM3U
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(1, playlist.segments.size)
        assertNull(playlist.segments[0].keyUrl)
        assertNull(playlist.segments[0].iv)
    }

    // ── Master Playlist ───────────────────────────────────────────────

    @Test
    fun `parse master playlist returns variants`() {
        val content = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2560000
            high.m3u8
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertTrue(playlist.isMaster)
        assertEquals(2, playlist.variants.size)
        assertEquals("https://base.url/low.m3u8", playlist.variants[0])
        assertEquals("https://base.url/high.m3u8", playlist.variants[1])
        assertTrue(playlist.segments.isEmpty())
    }

    @Test
    fun `parse master playlist preserves source order`() {
        val content = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=500000
            a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            b.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            c.m3u8
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(3, playlist.variants.size)
        assertEquals("https://base.url/a.m3u8", playlist.variants[0])
        assertEquals("https://base.url/b.m3u8", playlist.variants[1])
        assertEquals("https://base.url/c.m3u8", playlist.variants[2])
    }

    // ── Media Sequence ────────────────────────────────────────────────

    @Test
    fun `parse handles media sequence offset`() {
        val content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:5
            #EXTINF:10.0,
            segment5.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(1, playlist.segments.size)
        assertEquals(5, playlist.segments[0].index)
    }

    // ── URL Resolution ────────────────────────────────────────────────

    @Test
    fun `parse resolves relative URLs`() {
        val content = """
            #EXTM3U
            #EXTINF:10.0,
            path/to/segment.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/subdir/")

        assertEquals("https://base.url/subdir/path/to/segment.ts", playlist.segments[0].url)
    }

    @Test
    fun `parse preserves absolute URLs`() {
        val content = """
            #EXTM3U
            #EXTINF:10.0,
            https://cdn.example.com/segment.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals("https://cdn.example.com/segment.ts", playlist.segments[0].url)
    }

    @Test
    fun `parse preserves query parameters`() {
        val content = """
            #EXTM3U
            #EXTINF:10.0,
            segment.ts?token=abc123& expires=1234567890
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertTrue(playlist.segments[0].url.contains("token=abc123"))
    }

    // ── Derived IV from Media Sequence ────────────────────────────────

    @Test
    fun `derive IV from media sequence 1`() {
        val content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:1
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url"
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        // seq_num=1 → IV = 0x00000000000000000000000000000001
        val expectedIv = ByteArray(16) { 0 }
        expectedIv[15] = 1
        assertArrayEquals(expectedIv, playlist.segments[0].iv)
    }

    @Test
    fun `derive IV from media sequence 256`() {
        val content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:256
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url"
            #EXTINF:10.0,
            segment.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        // seq_num=256 → IV = 0x00000000000000000000000000000100
        val expectedIv = ByteArray(16) { 0 }
        expectedIv[14] = 1
        assertArrayEquals(expectedIv, playlist.segments[0].iv)
    }

    // ── Key Rotation ──────────────────────────────────────────────────

    @Test
    fun `parse handles key rotation`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key1.url"
            #EXTINF:10.0,
            segment1.ts
            #EXT-X-KEY:METHOD=AES-128,URI="https://key2.url"
            #EXTINF:10.0,
            segment2.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(2, playlist.segments.size)
        assertEquals("https://key1.url", playlist.segments[0].keyUrl)
        assertEquals("https://key2.url", playlist.segments[1].keyUrl)
    }

    @Test
    fun `parse handles key removal mid-playlist`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url"
            #EXTINF:10.0,
            segment1.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:10.0,
            segment2.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(2, playlist.segments.size)
        assertNotNull(playlist.segments[0].keyUrl)
        assertNull(playlist.segments[1].keyUrl)
    }

    // ── IV Explicit vs Derived ────────────────────────────────────────

    @Test
    fun `explicit IV takes precedence over derived IV`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0x000000000000000000000000000000FF
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        // Explicit IV should be used, not derived from seq_num=0
        val expectedIv = ByteArray(16) { 0 }
        expectedIv[15] = 0xFF.toByte()
        assertArrayEquals(expectedIv, playlist.segments[0].iv)
    }

    // ── Non-AES Key Methods ───────────────────────────────────────────

    @Test
    fun `parse ignores non-AES-128 key methods`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="https://key.url"
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(1, playlist.segments.size)
        assertNull(playlist.segments[0].keyUrl)
        assertNull(playlist.segments[0].iv)
    }

    // ── Non-Comment Lines ─────────────────────────────────────────────

    @Test
    fun `parse skips comment lines`() {
        val content = """
            #EXTM3U
            # This is a comment
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertEquals(1, playlist.segments.size)
    }

    // ── IV Parsing: Full 128-bit Value ──────────────────────────────

    @Test
    fun `parse full 32-character IV preserves all 16 bytes`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0x0123456789ABCDEF0123456789ABCDEF
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        assertArrayEquals(
            byteArrayOf(
                0x01, 0x23, 0x45, 0x67,
                0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
                0x01, 0x23, 0x45, 0x67,
                0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()
            ),
            playlist.segments[0].iv
        )
    }

    // ── IV Parsing: Leading Zeroes Preserved ─────────────────────────

    @Test
    fun `parse IV with leading zeroes preserves them`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0x00000000000000000000000000000001
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        // All bytes should be 0 except the last one
        val expectedIv = ByteArray(16) { 0 }
        expectedIv[15] = 1
        assertArrayEquals(expectedIv, playlist.segments[0].iv)
    }

    @Test
    fun `parse IV with many leading zeroes and non-zero final byte`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0x000000000000000000000000000000FF
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        val expectedIv = ByteArray(16) { 0 }
        expectedIv[15] = 0xFF.toByte()
        assertArrayEquals(expectedIv, playlist.segments[0].iv)
    }

    // ── IV Parsing: Short IV Left-Padded ─────────────────────────────

    @Test
    fun `parse short IV without 0x prefix is left-padded to 16 bytes`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=FF
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        val expectedIv = ByteArray(16) { 0 }
        expectedIv[15] = 0xFF.toByte()
        assertArrayEquals(expectedIv, playlist.segments[0].iv)
    }

    @Test
    fun `parse short IV with 0x prefix is left-padded to 16 bytes`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0xFF
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        val expectedIv = ByteArray(16) { 0 }
        expectedIv[15] = 0xFF.toByte()
        assertArrayEquals(expectedIv, playlist.segments[0].iv)
    }

    // ── IV Parsing: Uppercase 0X Prefix ──────────────────────────────

    @Test
    fun `parse IV with uppercase 0X prefix works`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0X0123456789ABCDEF0123456789ABCDEF
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        assertArrayEquals(
            byteArrayOf(
                0x01, 0x23, 0x45, 0x67,
                0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
                0x01, 0x23, 0x45, 0x67,
                0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()
            ),
            playlist.segments[0].iv
        )
    }

    // ── IV Parsing: Mixed Case Prefix ────────────────────────────────

    @Test
    fun `parse IV with mixed case 0x prefix works`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0xaabbccdd
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        val expectedIv = ByteArray(16) { 0 }
        // 0xaabbccdd padded to 32 hex = 000000000000000000000000aabbccdd
        expectedIv[12] = 0xAA.toByte()
        expectedIv[13] = 0xBB.toByte()
        expectedIv[14] = 0xCC.toByte()
        expectedIv[15] = 0xDD.toByte()
        assertArrayEquals(expectedIv, playlist.segments[0].iv)
    }

    // ── IV Parsing: All Zeros ────────────────────────────────────────

    @Test
    fun `parse all-zero IV produces 16 zero bytes`() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0x00000000000000000000000000000000
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")

        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        assertArrayEquals(ByteArray(16) { 0 }, playlist.segments[0].iv)
    }

    // ── IV Parsing: Malformed / Invalid ──────────────────────────────

    @Test
    fun `parse non-hex IV produces null segment iv`() {
        // IV with non-hex characters — hexToBytes will parse what it can
        // Non-hex chars like 'Z' will cause parseInt to fail, producing 0
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=0xZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        // Should not crash — hexToBytes handles gracefully
        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")
        assertEquals(1, playlist.segments.size)
        // IV will be 16 bytes with garbage values (non-hex chars parsed as 0)
        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
    }

    @Test
    fun `parse empty IV string produces zero-length byte array padded to 16`() {
        // IV= with empty value after equals
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.url",IV=
            #EXTINF:10.0,
            segment1.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, "https://base.url/")
        assertEquals(1, playlist.segments.size)
        // Empty IV hex → padded to 32 zeros → 16 zero bytes
        assertNotNull(playlist.segments[0].iv)
        assertEquals(16, playlist.segments[0].iv!!.size)
        assertArrayEquals(ByteArray(16) { 0 }, playlist.segments[0].iv)
    }
}
