package com.pahebatcher.app.domain.util

import com.pahebatcher.app.domain.model.EpisodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringUtilsTest {

    @Test
    fun `sanitize removes special characters`() {
        assertEquals("Spy_x_Family", StringUtils.sanitize("Spy x Family!"))
    }

    @Test
    fun `sanitize replaces spaces with underscores`() {
        assertEquals("My_Anime_Title", StringUtils.sanitize("My Anime Title"))
    }

    @Test
    fun `sanitize collapses multiple underscores`() {
        assertEquals("My_Anime", StringUtils.sanitize("My   Anime"))
    }

    @Test
    fun `sanitize preserves internal periods and hyphens like Python`() {
        // Python `Dr. Stone` → `Dr._Stone`: '.' is in the keep-set, only the
        // space becomes '_'. Hyphens likewise survive.
        assertEquals("Dr._Stone", StringUtils.sanitize("Dr. Stone"))
        assertEquals("Re-Zero_One", StringUtils.sanitize("Re-Zero One"))
    }

    @Test
    fun `sanitize removes apostrophes and other punctuation`() {
        assertEquals(
            "The_Worlds_Strongest_Rearguard",
            StringUtils.sanitize("The World's Strongest Rearguard"),
        )
        assertEquals("Spy_x_Family", StringUtils.sanitize("Spy x Family!"))
    }

    @Test
    fun `sanitize keeps Unicode word characters (Python str-pattern parity)`() {
        // Regression: the pattern used to be ASCII-only `\w`, so every
        // non-ASCII title sanitized to "" (Gate A finding).
        assertEquals("葬送のフリーレン", StringUtils.sanitize("葬送のフリーレン"))
        assertEquals("君の名は", StringUtils.sanitize("君の名は"))
        assertEquals("Cœur", StringUtils.sanitize("Cœur"))
        assertEquals("التفاحة", StringUtils.sanitize("التفاحة"))
    }

    @Test
    fun `sanitize strips Unicode letters only when they are not word characters`() {
        // Chinese/Japanese/Korean letters ARE Python `\w` — kept; the emoji
        // (symbol) is not.
        assertEquals("葬送フリーレン", StringUtils.sanitize("葬送😊フリーレン"))
    }

    @Test
    fun `sanitize drops path separators entirely`() {
        assertEquals("Fatestay_night", StringUtils.sanitize("Fate/stay night"))
        assertEquals("ab", StringUtils.sanitize("a\\b"))
        assertTrue(StringUtils.sanitize("a/b\\c:d").none { it == '/' || it == '\\' })
    }

    @Test
    fun `sanitize trims surrounding whitespace`() {
        assertEquals("Title", StringUtils.sanitize("   Title   "))
    }

    @Test
    fun `sanitize of only-removable characters is empty`() {
        assertEquals("", StringUtils.sanitize("!!! ???"))
        assertEquals("", StringUtils.sanitize("   "))
        // Dots are in Python's keep-set — sanitize keeps them; trimming the
        // DIRECTORY component is OutputPathNames' job, not sanitize's.
        assertEquals("...", StringUtils.sanitize("..."))
    }

    @Test
    fun `epPrefix for whole number`() {
        assertEquals("001", StringUtils.epPrefix("1"))
    }

    @Test
    fun `epPrefix for fractional number`() {
        assertEquals("013.5", StringUtils.epPrefix("13.5"))
    }

    @Test
    fun `epPrefix for invalid number`() {
        assertEquals("abc", StringUtils.epPrefix("abc"))
    }

    @Test
    fun `fmtBytes for bytes`() {
        assertEquals("100.0 B", StringUtils.fmtBytes(100))
    }

    @Test
    fun `fmtBytes for kilobytes`() {
        assertEquals("1.0 KB", StringUtils.fmtBytes(1024))
    }

    @Test
    fun `fmtBytes for megabytes`() {
        assertEquals("1.0 MB", StringUtils.fmtBytes(1024 * 1024))
    }

    @Test
    fun `fmtBytes for gigabytes`() {
        assertEquals("1.0 GB", StringUtils.fmtBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `compactEpRange for empty list`() {
        assertEquals("none", StringUtils.compactEpRange(emptyList()))
    }

    @Test
    fun `compactEpRange for single episode`() {
        val episodes = listOf(EpisodeInfo(1.0f, "s1", "Title", "Sub", "jpn", "url"))
        assertEquals("1", StringUtils.compactEpRange(episodes))
    }

    @Test
    fun `compactEpRange for sequential episodes`() {
        val episodes = listOf(
            EpisodeInfo(1.0f, "s1", "Title", "Sub", "jpn", "url"),
            EpisodeInfo(2.0f, "s2", "Title", "Sub", "jpn", "url"),
            EpisodeInfo(3.0f, "s3", "Title", "Sub", "jpn", "url")
        )
        assertEquals("1–3", StringUtils.compactEpRange(episodes))
    }

    @Test
    fun `compactEpRange for mixed episodes`() {
        val episodes = listOf(
            EpisodeInfo(1.0f, "s1", "Title", "Sub", "jpn", "url"),
            EpisodeInfo(2.0f, "s2", "Title", "Sub", "jpn", "url"),
            EpisodeInfo(3.0f, "s3", "Title", "Sub", "jpn", "url"),
            EpisodeInfo(5.0f, "s5", "Title", "Sub", "jpn", "url"),
            EpisodeInfo(7.0f, "s7", "Title", "Sub", "jpn", "url"),
            EpisodeInfo(8.0f, "s8", "Title", "Sub", "jpn", "url"),
            EpisodeInfo(9.0f, "s9", "Title", "Sub", "jpn", "url")
        )
        assertEquals("1–3, 5, 7–9", StringUtils.compactEpRange(episodes))
    }

    @Test
    fun `audioBadge returns SUB for Japanese`() {
        assertEquals("SUB", StringUtils.audioBadge("jpn"))
    }

    @Test
    fun `audioBadge returns DUB for English`() {
        assertEquals("DUB", StringUtils.audioBadge("eng"))
    }

    @Test
    fun `audioBadge shows any other source code as itself, uppercased`() {
        // Open-set generalisation (SPEC §22): a non-jpn/eng code must not be
        // mislabelled SUB or DUB.
        assertEquals("KOR", StringUtils.audioBadge("kor"))
        assertEquals("CHI", StringUtils.audioBadge("chi"))
    }

    @Test
    fun `audioBadge never invents a label for a blank audio`() {
        // The old closed mapping returned "DUB" for anything that was not jpn,
        // including a blank value.
        assertEquals("", StringUtils.audioBadge(""))
    }

    // -----------------------------------------------------------------------
    // A2.1 (UI-004): THE episode-number renderer
    // -----------------------------------------------------------------------

    @Test
    fun `episodeNumber renders whole numbers without the trailing zero`() {
        val table = mapOf(
            1.0f to "1",
            13.0f to "13",
            0.0f to "0",
            (-3.0f) to "-3",
            100.0f to "100",
        )
        for ((number, expected) in table) {
            assertEquals("whole $number", expected, StringUtils.episodeNumber(number))
        }
    }

    @Test
    fun `episodeNumber keeps every fractional value's decimal`() {
        val table = mapOf(
            13.5f to "13.5",
            0.5f to "0.5",
            2.25f to "2.25",
            (-1.25f) to "-1.25",
        )
        for ((number, expected) in table) {
            assertEquals("fractional $number", expected, StringUtils.episodeNumber(number))
        }
    }

    @Test
    fun `episodeNumber never prints saturated Long garbage for huge values`() {
        // Beyond Long's float precision the modulo predicate still said
        // "whole" while toLong() saturated — the round-trip must fall back to
        // the raw float string instead of Long.MAX_VALUE noise.
        val huge = 1.0e20f
        val rendered = StringUtils.episodeNumber(huge)
        assertTrue(
            "the raw float string must survive, not saturated Long garbage (was: $rendered)",
            rendered != java.lang.Long.MAX_VALUE.toString() && rendered.contains("E"),
        )
    }
}