package com.pahebatcher.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamInfoTest {

    @Test
    fun `requiresAuth returns true when cookies present`() {
        val stream = StreamInfo(
            url = "https://example.com/stream.m3u8",
            cookies = listOf(mapOf("name" to "cookie1", "value" to "value1")),
            userAgent = "Mozilla/5.0",
            referer = "https://example.com"
        )
        assertTrue(stream.requiresAuth())
    }

    @Test
    fun `requiresAuth returns false when no cookies`() {
        val stream = StreamInfo(
            url = "https://example.com/stream.m3u8",
            cookies = emptyList(),
            userAgent = "Mozilla/5.0",
            referer = "https://example.com"
        )
        assertFalse(stream.requiresAuth())
    }

    @Test
    fun `toHeaders includes User-Agent and Referer`() {
        val stream = StreamInfo(
            url = "https://example.com/stream.m3u8",
            cookies = emptyList(),
            userAgent = "Mozilla/5.0",
            referer = "https://example.com"
        )
        val headers = stream.toHeaders()
        assertEquals("Mozilla/5.0", headers["User-Agent"])
        assertEquals("https://example.com", headers["Referer"])
    }

    @Test
    fun `toHeaders includes Cookie when cookies present`() {
        val stream = StreamInfo(
            url = "https://example.com/stream.m3u8",
            cookies = listOf(mapOf("name" to "cf_clearance", "value" to "abc123")),
            userAgent = "Mozilla/5.0",
            referer = "https://example.com"
        )
        val headers = stream.toHeaders()
        assertEquals("cf_clearance=abc123", headers["Cookie"])
    }

    @Test
    fun `cookieStr formats multiple cookies correctly`() {
        val stream = StreamInfo(
            url = "https://example.com/stream.m3u8",
            cookies = listOf(
                mapOf("name" to "cookie1", "value" to "value1"),
                mapOf("name" to "cookie2", "value" to "value2")
            ),
            userAgent = "Mozilla/5.0",
            referer = "https://example.com"
        )
        assertEquals("cookie1=value1; cookie2=value2", stream.cookieStr)
    }

    @Test
    fun `cookieStr empty when no cookies`() {
        val stream = StreamInfo(
            url = "https://example.com/stream.m3u8",
            cookies = emptyList(),
            userAgent = "Mozilla/5.0",
            referer = "https://example.com"
        )
        assertEquals("", stream.cookieStr)
    }
}