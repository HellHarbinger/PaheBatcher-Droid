package com.pahebatcher.app.ui.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §36-1/§36-2 — Speed/ETA display formatters (PHASE11_ARCHITECTURE_V2 §16.1):
 *
 * - `null` / `0` / negative → `null` so a composable can NEVER print the
 *   forbidden `0 B/s` or `0:00`.
 * - Nonzero values format as `B/s` | `KB/s` | `MB/s` (base 1024) and
 *   `m:ss` | `h:mm:ss` (Locale.US, zero-padded seconds/minutes).
 */
class DownloadRowFormatTest {

    // -----------------------------------------------------------------------
    // Rejection rules — the forbidden renders are not producible
    // -----------------------------------------------------------------------

    @Test
    fun `zero null and negative speeds format to null`() {
        assertNull(DownloadRowMapper.formatSpeed(null))
        assertNull(DownloadRowMapper.formatSpeed(0L))
        assertNull(DownloadRowMapper.formatSpeed(-1L))
        assertNull(DownloadRowMapper.formatSpeed(-1_000_000L))
    }

    @Test
    fun `zero null and negative etas format to null`() {
        assertNull(DownloadRowMapper.formatEta(null))
        assertNull(DownloadRowMapper.formatEta(0L))
        assertNull(DownloadRowMapper.formatEta(-1L))
        assertNull(DownloadRowMapper.formatEta(-3_600L))
    }

    @Test
    fun `the forbidden zero speed and zero eta strings are never produced`() {
        val speeds = listOf(null, 0L, -1L, 1L, 1023L, 1024L, 1_048_576L)
        val etas = listOf(null, 0L, -1L, 1L, 59L, 60L, 3_599L, 3_600L)

        speeds.forEach { value ->
            val out = DownloadRowMapper.formatSpeed(value)
            if (out != null) assertNotEquals("0 B/s", out)
        }
        etas.forEach { value ->
            val out = DownloadRowMapper.formatEta(value)
            if (out != null) assertNotEquals("0:00", out)
        }
    }

    // -----------------------------------------------------------------------
    // Speed formatting — base-1024 units, Locale.US
    // -----------------------------------------------------------------------

    @Test
    fun `small speeds render as B per second`() {
        assertEquals("512 B/s", DownloadRowMapper.formatSpeed(512L))
        assertEquals("1 B/s", DownloadRowMapper.formatSpeed(1L))
        assertEquals("1023 B/s", DownloadRowMapper.formatSpeed(1023L))
    }

    @Test
    fun `kilobyte range speeds render with one decimal KB per second`() {
        assertEquals("1.0 KB/s", DownloadRowMapper.formatSpeed(1024L))
        assertEquals("1.5 KB/s", DownloadRowMapper.formatSpeed(1536L))
        assertEquals("1023.5 KB/s", DownloadRowMapper.formatSpeed(1023L * 1024L + 512L))
    }

    @Test
    fun `megabyte range speeds render with one decimal MB per second`() {
        assertEquals("1.0 MB/s", DownloadRowMapper.formatSpeed(1024L * 1024L))
        assertEquals("2.5 MB/s", DownloadRowMapper.formatSpeed((2.5 * 1024 * 1024).toLong()))
    }

    // -----------------------------------------------------------------------
    // ETA formatting — m:ss under an hour, h:mm:ss beyond
    // -----------------------------------------------------------------------

    @Test
    fun `etas under an hour render as m colon ss with zero padding`() {
        assertEquals("0:59", DownloadRowMapper.formatEta(59L))
        assertEquals("1:05", DownloadRowMapper.formatEta(65L))
        assertEquals("9:59", DownloadRowMapper.formatEta(599L))
        assertEquals("59:59", DownloadRowMapper.formatEta(3599L))
    }

    @Test
    fun `etas at or beyond an hour render as h colon mm colon ss`() {
        assertEquals("1:00:00", DownloadRowMapper.formatEta(3600L))
        assertEquals("1:01:01", DownloadRowMapper.formatEta(3661L))
        assertEquals("25:00:00", DownloadRowMapper.formatEta(25L * 3600L))
        assertEquals("100:00:59", DownloadRowMapper.formatEta(100L * 3600L + 59L))
    }
}
