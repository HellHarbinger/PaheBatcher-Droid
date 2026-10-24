package com.pahebatcher.app.data.hls

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for FileNameGenerator — episode output filename generation.
 *
 * Ported from Python downloader.py episode output naming.
 *
 * Python behavior (downloader.py lines 56-58):
 * ```python
 * prefix = ep_prefix(ep.ep_str)
 * fname = sanitize(f"Ep {prefix} - {title}") or f"ep_{ep.ep_str}"
 * out = outdir / f"{fname}.mp4"
 * ```
 *
 * sanitize() (utils.py:12) is applied to the FULL string — prefix, separator
 * and title together — so every space becomes an underscore and runs of
 * underscores collapse. Phase 11.5E-5 corrected episodeFileName() to match
 * that contract (PARITY §10.2); expectations below are Python-identical.
 */
class FileNameGeneratorTest {

    @Test
    fun `episodeFileName generates correct format`() {
        // Python: sanitize("Ep 001 - Episode Title") → all spaces → underscores
        assertEquals("Ep_001_-_Episode_Title.mp4",
            FileNameGenerator.episodeFileName("001", "Episode Title"))
    }

    @Test
    fun `episodeFileName handles long titles`() {
        assertEquals("Ep_012_-_My_Very_Long_Episode_Title.mp4",
            FileNameGenerator.episodeFileName("012", "My Very Long Episode Title"))
    }

    @Test
    fun `episodeFileName handles special characters in title`() {
        // Sanitize removes colons, parentheses, and replaces spaces
        assertEquals("Ep_001_-_Episode_Part_1_Special.mp4",
            FileNameGenerator.episodeFileName("001", "Episode: Part 1 (Special)"))
    }

    @Test
    fun `episodeFileName handles empty title`() {
        // Python: sanitize("Ep 001 - ") → strip → "Ep_001_-"
        assertEquals("Ep_001_-.mp4",
            FileNameGenerator.episodeFileName("001", ""))
    }

    @Test
    fun `episodeFileName sanitizes filesystem-unsafe characters`() {
        // Colon is illegal in filenames on most filesystems
        assertEquals("Ep_001_-_Episode_The_Beginning.mp4",
            FileNameGenerator.episodeFileName("001", "Episode: The Beginning"))
    }

    @Test
    fun `episodeFileName sanitizes multiple special characters`() {
        // "Ep 1 / Strange <Name>" → "/" and "<>" removed (double space collapses)
        assertEquals("Ep_001_-_Ep_1_Strange_Name.mp4",
            FileNameGenerator.episodeFileName("001", "Ep 1 / Strange <Name>"))
    }

    @Test
    fun `episodeFileName sanitizes the prefix separator like Python`() {
        // Python applies sanitize() to f"Ep {prefix} - {title}" as a whole,
        // so the prefix spaces become underscores too (downloader.py:57).
        assertEquals("Ep_001_-_Pilot.mp4",
            FileNameGenerator.episodeFileName("001", "Pilot"))
        assertEquals("Ep_013.5_-_Clash_of_Kings.mp4",
            FileNameGenerator.episodeFileName("013.5", "Clash of Kings"))
    }

    // ── formatEpisodePrefix ──────────────────────────────────────────

    @Test
    fun `formatEpisodePrefix formats integers correctly`() {
        assertEquals("001", FileNameGenerator.formatEpisodePrefix(1))
        assertEquals("012", FileNameGenerator.formatEpisodePrefix(12))
        assertEquals("100", FileNameGenerator.formatEpisodePrefix(100))
    }

    @Test
    fun `formatEpisodePrefix formats zero correctly`() {
        assertEquals("000", FileNameGenerator.formatEpisodePrefix(0))
    }

    @Test
    fun `formatEpisodePrefix formats large integers correctly`() {
        assertEquals("999", FileNameGenerator.formatEpisodePrefix(999))
    }

    @Test
    fun `formatEpisodePrefix formats doubles with decimal correctly`() {
        assertEquals("001.5", FileNameGenerator.formatEpisodePrefix(1.5))
        assertEquals("013.5", FileNameGenerator.formatEpisodePrefix(13.5))
    }

    @Test
    fun `formatEpisodePrefix formats whole doubles as integers`() {
        assertEquals("005", FileNameGenerator.formatEpisodePrefix(5.0))
        assertEquals("010", FileNameGenerator.formatEpisodePrefix(10.0))
    }

    @Test
    fun `formatEpisodePrefix formats floats with decimal correctly`() {
        assertEquals("001.5", FileNameGenerator.formatEpisodePrefix(1.5f))
        assertEquals("013.5", FileNameGenerator.formatEpisodePrefix(13.5f))
    }

    @Test
    fun `formatEpisodePrefix formats whole floats as integers`() {
        assertEquals("005", FileNameGenerator.formatEpisodePrefix(5.0f))
        assertEquals("010", FileNameGenerator.formatEpisodePrefix(10.0f))
    }

    @Test
    fun `formatEpisodePrefix formats long correctly`() {
        assertEquals("001", FileNameGenerator.formatEpisodePrefix(1L))
        assertEquals("100", FileNameGenerator.formatEpisodePrefix(100L))
    }

    // ── Different episodes produce different filenames ────────────────

    @Test
    fun `different episodes produce different filenames`() {
        val ep1 = FileNameGenerator.episodeFileName(
            FileNameGenerator.formatEpisodePrefix(1), "First Episode")
        val ep2 = FileNameGenerator.episodeFileName(
            FileNameGenerator.formatEpisodePrefix(2), "Second Episode")

        assertNotEquals("Different episodes should produce different filenames", ep1, ep2)
        assertEquals("Ep_001_-_First_Episode.mp4", ep1)
        assertEquals("Ep_002_-_Second_Episode.mp4", ep2)
    }

    @Test
    fun `half episode produces different filename from integer episode`() {
        val intEp = FileNameGenerator.episodeFileName(
            FileNameGenerator.formatEpisodePrefix(13), "Episode")
        val halfEp = FileNameGenerator.episodeFileName(
            FileNameGenerator.formatEpisodePrefix(13.5), "Episode")

        assertNotEquals("Half and integer episodes should produce different filenames", intEp, halfEp)
        assertEquals("Ep_013_-_Episode.mp4", intEp)
        assertEquals("Ep_013.5_-_Episode.mp4", halfEp)
    }
}
