package com.pahebatcher.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeInfoTest {

    @Test
    fun `episode number string representation for whole number`() {
        val episode = EpisodeInfo(
            number = 1.0f,
            session = "test-session",
            title = "Test Episode",
            fansub = "TestSub",
            audio = "jpn",
            playUrl = "https://example.com/play/1/2"
        )
        assertEquals("1", episode.epStr)
    }

    @Test
    fun `episode number string representation for fractional number`() {
        val episode = EpisodeInfo(
            number = 13.5f,
            session = "test-session",
            title = "Test Episode",
            fansub = "TestSub",
            audio = "jpn",
            playUrl = "https://example.com/play/1/2"
        )
        assertEquals("13.5", episode.epStr)
    }

    @Test
    fun `cache directory name for Japanese audio`() {
        val episode = EpisodeInfo(
            number = 1.0f,
            session = "test-session",
            title = "Test Episode",
            fansub = "TestSub",
            audio = "jpn",
            playUrl = "https://example.com/play/1/2"
        )
        assertEquals("Ep_001_JPN", episode.cacheDirectoryName())
    }

    @Test
    fun `cache directory name for English audio`() {
        val episode = EpisodeInfo(
            number = 13.0f,
            session = "test-session",
            title = "Test Episode",
            fansub = "TestSub",
            audio = "eng",
            playUrl = "https://example.com/play/1/2"
        )
        assertEquals("Ep_013_ENG", episode.cacheDirectoryName())
    }

    @Test
    fun `cache directory name for fractional episode`() {
        val episode = EpisodeInfo(
            number = 13.5f,
            session = "test-session",
            title = "Test Episode",
            fansub = "TestSub",
            audio = "jpn",
            playUrl = "https://example.com/play/1/2"
        )
        assertEquals("Ep_013.5_JPN", episode.cacheDirectoryName())
    }

    @Test
    fun `audio constants are correct`() {
        assertEquals("jpn", EpisodeInfo.AUDIO_JAPANESE)
        assertEquals("eng", EpisodeInfo.AUDIO_ENGLISH)
    }

    @Test
    fun `episode data class equals`() {
        val ep1 = EpisodeInfo(1.0f, "s1", "Title", "Sub", "jpn", "url1")
        val ep2 = EpisodeInfo(1.0f, "s1", "Title", "Sub", "jpn", "url1")
        assertEquals(ep1, ep2)
    }

    @Test
    fun `episode data class not equals`() {
        val ep1 = EpisodeInfo(1.0f, "s1", "Title", "Sub", "jpn", "url1")
        val ep2 = EpisodeInfo(1.0f, "s1", "Title", "Sub", "eng", "url1")
        assertTrue(ep1 != ep2)
    }
}