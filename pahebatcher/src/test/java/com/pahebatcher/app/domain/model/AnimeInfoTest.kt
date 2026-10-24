package com.pahebatcher.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeInfoTest {

    private fun createTestAnimeInfo(): AnimeInfo {
        val episodes = listOf(
            EpisodeInfo(1.0f, "ep1", "Episode 1", "SubGroup", "jpn", "url1"),
            EpisodeInfo(1.0f, "ep2", "Episode 1", "DubGroup", "eng", "url2"),
            EpisodeInfo(2.0f, "ep3", "Episode 2", "SubGroup", "jpn", "url3"),
            EpisodeInfo(2.0f, "ep4", "Episode 2", "DubGroup", "eng", "url4"),
            EpisodeInfo(3.0f, "ep5", "Episode 3", "SubGroup", "jpn", "url5")
        )
        return AnimeInfo(
            session = "anime-session",
            title = "Test Anime",
            host = "animepahe.com",
            total = 3,
            episodes = episodes,
            hasSession = true
        )
    }

    @Test
    fun `getVariant returns correct episode`() {
        val anime = createTestAnimeInfo()
        val variant = anime.getVariant(1.0f, "jpn")
        assertNotNull(variant)
        assertEquals("ep1", variant?.session)
    }

    @Test
    fun `getVariant returns null for missing episode`() {
        val anime = createTestAnimeInfo()
        val variant = anime.getVariant(4.0f, "jpn")
        assertNull(variant)
    }

    @Test
    fun `getAllVariants returns both SUB and DUB`() {
        val anime = createTestAnimeInfo()
        val variants = anime.getAllVariants(1.0f)
        assertEquals(2, variants.size)
        // sorted by audio, "eng" < "jpn" alphabetically
        assertEquals("eng", variants[0].audio)
        assertEquals("jpn", variants[1].audio)
    }

    @Test
    fun `getUniqueEpisodeNumbers returns sorted distinct numbers`() {
        val anime = createTestAnimeInfo()
        val numbers = anime.getUniqueEpisodeNumbers()
        assertEquals(3, numbers.size)
        assertEquals(1.0f, numbers[0])
        assertEquals(2.0f, numbers[1])
        assertEquals(3.0f, numbers[2])
    }

    @Test
    fun `hasDualAudio returns true for dual audio`() {
        val anime = createTestAnimeInfo()
        assertTrue(anime.hasDualAudio())
    }

    @Test
    fun `getEpisodesGroupedByNumber groups correctly`() {
        val anime = createTestAnimeInfo()
        val grouped = anime.getEpisodesGroupedByNumber()
        assertEquals(3, grouped.size)
        assertEquals(2, grouped[1.0f]?.size)
        assertEquals(2, grouped[2.0f]?.size)
        assertEquals(1, grouped[3.0f]?.size)
    }

    // -----------------------------------------------------------------------
    // defaultVariant — SPEC §22's neutral bulk default (audio-variant slice)
    // -----------------------------------------------------------------------

    @Test
    fun `defaultVariant picks jpn when the number offers it, even though eng sorts first`() {
        val anime = createTestAnimeInfo()

        val variant = anime.defaultVariant(1.0f)

        assertNotNull(variant)
        assertEquals("jpn", variant?.audio)
        assertEquals("ep1", variant?.session)
        // getAllVariants(1) is sorted by audio (eng < jpn), so this proves the
        // default is the SPEC rule (jpn-if-present), NOT `variants.first()`.
    }

    @Test
    fun `defaultVariant falls back to the first available variant when jpn is absent`() {
        val anime = AnimeInfo(
            session = "s",
            title = "T",
            host = "h",
            total = 2,
            episodes = listOf(
                EpisodeInfo(1.0f, "e-jpn", "Ep", "G", "jpn", "u1"),
                EpisodeInfo(1.0f, "e-kor", "Ep", "G", "kor", "u2"),
                EpisodeInfo(2.0f, "e-eng", "Ep", "G", "eng", "u3"),
                EpisodeInfo(2.0f, "e-kor2", "Ep", "G", "kor", "u4"),
            ),
            hasSession = true,
        )

        // Number 1 offers jpn → jpn wins over the alphabetically-first kor.
        assertEquals("jpn", anime.defaultVariant(1.0f)?.audio)
        // Number 2 has no jpn → first available variant in getAllVariants order
        // (deterministic: sorted by audio, so "eng" — never a coin flip).
        assertEquals("eng", anime.defaultVariant(2.0f)?.audio)
        assertEquals("e-eng", anime.defaultVariant(2.0f)?.session)
    }

    @Test
    fun `defaultVariant of a number with no variants is null, never another number's variant`() {
        val anime = createTestAnimeInfo()

        assertNull(anime.defaultVariant(4.0f))
        assertNull(anime.defaultVariant(0f))
    }
}