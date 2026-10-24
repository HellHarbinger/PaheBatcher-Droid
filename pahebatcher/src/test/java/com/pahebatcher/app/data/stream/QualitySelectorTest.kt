package com.pahebatcher.app.data.stream

import com.pahebatcher.app.domain.model.ResolutionEntry
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for QualitySelector — exact-audio + quality selection (SPEC §22).
 *
 * Ported from Python kwik.py extract_stream() quality selection, with the
 * approved audio-variant model: audio is an open-set string, the requested
 * audio is filtered EXACTLY before quality selection, and a missing audio is
 * an explicit `null` (the caller fails) instead of the reference's silent
 * `if not filtered: filtered = entries` substitution (kwik.py:230-232).
 * Source identity for de-duplication is `(audio, resolution, kwikUrl)` with
 * first-document-occurrence-wins; fansub is descriptive metadata only.
 */
class QualitySelectorTest {

    private fun entry(
        resolution: Int,
        url: String,
        audio: String,
        fansub: String = "",
    ) = ResolutionEntry(resolution, url, audio, fansub)

    // ── Basic Quality Selection ──────────────────────────────────────────

    @Test
    fun `select returns null for empty entries`() {
        assertNull(QualitySelector.select(emptyList(), 1080, "jpn"))
    }

    @Test
    fun `select returns exact match when available`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080", "jpn"),
            entry(720, "https://kwik.si/e/720", "jpn"),
            entry(360, "https://kwik.si/e/360", "jpn"),
        )

        val result = QualitySelector.select(entries, 1080, "jpn")

        assertNotNull(result)
        assertEquals(1080, result!!.resolution)
    }

    @Test
    fun `select returns lower quality when exact not available`() {
        val entries = listOf(
            entry(720, "https://kwik.si/e/720", "jpn"),
            entry(360, "https://kwik.si/e/360", "jpn"),
        )

        val result = QualitySelector.select(entries, 1080, "jpn")

        assertNotNull(result)
        assertEquals(720, result!!.resolution)
    }

    @Test
    fun `select returns lowest when all above requested`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080", "jpn"),
            entry(720, "https://kwik.si/e/720", "jpn"),
        )

        val result = QualitySelector.select(entries, 360, "jpn")

        assertNotNull(result)
        assertEquals(720, result!!.resolution)
    }

    // ── Exact Audio Filtering ────────────────────────────────────────────

    @Test
    fun `select filters exactly by jpn when jpn requested`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080-eng", "eng"),
            entry(1080, "https://kwik.si/e/1080-jpn", "jpn"),
            entry(720, "https://kwik.si/e/720-jpn", "jpn"),
        )

        val result = QualitySelector.select(entries, 1080, "jpn")

        assertNotNull(result)
        assertEquals("https://kwik.si/e/1080-jpn", result!!.kwikUrl)
        assertEquals("jpn", result.audio)
    }

    @Test
    fun `select filters exactly by eng when eng requested`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080-jpn", "jpn"),
            entry(1080, "https://kwik.si/e/1080-eng", "eng"),
            entry(720, "https://kwik.si/e/720-eng", "eng"),
        )

        val result = QualitySelector.select(entries, 1080, "eng")

        assertNotNull(result)
        assertEquals("https://kwik.si/e/1080-eng", result!!.kwikUrl)
        assertEquals("eng", result.audio)
    }

    @Test
    fun `select returns null when requested audio is unavailable — never a fallback audio`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080-jpn", "jpn"),
            entry(720, "https://kwik.si/e/720-jpn", "jpn"),
        )

        // Request eng while only jpn exists: INVERTED behavior — the reference
        // (kwik.py:230-232) re-filtered to ALL entries and silently served a
        // jpn file; the port returns null so the caller fails explicitly.
        assertNull(QualitySelector.select(entries, 1080, "eng"))
    }

    @Test
    fun `select on an all-unknown-audio page never matches a concrete request`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080", ""), // source carried no audio signal
        )

        assertNull(QualitySelector.select(entries, 1080, "jpn"))
    }

    @Test
    fun `select handles arbitrary open-set audio codes exactly`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080-jpn", "jpn"),
            entry(1080, "https://kwik.si/e/1080-kor", "kor"),
        )

        val result = QualitySelector.select(entries, 1080, "kor")

        assertNotNull(result)
        assertEquals("https://kwik.si/e/1080-kor", result!!.kwikUrl)
        assertEquals("kor", result.audio)
    }

    // ── Source De-duplication — (audio, resolution, kwikUrl), first wins ─

    @Test
    fun `identical source identity de-duplicates to the first document occurrence`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/first", "jpn"),
            entry(1080, "https://kwik.si/e/first", "jpn"), // same identity
        )

        val result = QualitySelector.select(entries, 1080, "jpn")

        assertNotNull(result)
        assertEquals("https://kwik.si/e/first", result!!.kwikUrl)
    }

    @Test
    fun `different audio at the same resolution never collapse into one another`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080-jpn", "jpn"),
            entry(1080, "https://kwik.si/e/1080-eng", "eng"),
        )

        val jpn = QualitySelector.select(entries, 1080, "jpn")
        val eng = QualitySelector.select(entries, 1080, "eng")

        assertEquals("https://kwik.si/e/1080-jpn", jpn!!.kwikUrl)
        assertEquals("https://kwik.si/e/1080-eng", eng!!.kwikUrl)
    }

    @Test
    fun `same resolution different url within one audio keeps the first occurrence`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/a", "jpn", "GroupA"),
            entry(1080, "https://kwik.si/e/b", "jpn", "GroupB"),
        )

        val result = QualitySelector.select(entries, 1080, "jpn")

        assertNotNull(result)
        assertEquals("https://kwik.si/e/a", result!!.kwikUrl)
        assertEquals("GroupA", result.fansub)
    }

    // ── Fansub Preservation ──────────────────────────────────────────────

    @Test
    fun `select preserves fansub information`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/abc", "jpn", "SubsPlease"),
        )

        val result = QualitySelector.select(entries, 1080, "jpn")

        assertNotNull(result)
        assertEquals("SubsPlease", result!!.fansub)
    }

    // ── Edge Cases ───────────────────────────────────────────────────────

    @Test
    fun `select handles single entry`() {
        val entries = listOf(
            entry(360, "https://kwik.si/e/abc", "jpn"),
        )

        val result = QualitySelector.select(entries, 1080, "jpn")

        assertNotNull(result)
        assertEquals(360, result!!.resolution)
    }

    @Test
    fun `select handles mixed resolutions and audio`() {
        val entries = listOf(
            entry(1080, "https://kwik.si/e/1080-eng", "eng"),
            entry(1080, "https://kwik.si/e/1080-jpn", "jpn"),
            entry(720, "https://kwik.si/e/720-eng", "eng"),
            entry(720, "https://kwik.si/e/720-jpn", "jpn"),
            entry(360, "https://kwik.si/e/360-jpn", "jpn"),
        )

        // Request 720p jpn
        val result = QualitySelector.select(entries, 720, "jpn")

        assertNotNull(result)
        assertEquals(720, result!!.resolution)
        assertEquals("jpn", result.audio)
    }

    @Test
    fun `quality selection applies only after the audio filter`() {
        val entries = listOf(
            // A higher jpn resolution exists only BELOW the requested quality
            // among jpn entries; the eng 1080 must be invisible to a jpn request.
            entry(1080, "https://kwik.si/e/1080-eng", "eng"),
            entry(720, "https://kwik.si/e/720-jpn", "jpn"),
            entry(360, "https://kwik.si/e/360-jpn", "jpn"),
        )

        val result = QualitySelector.select(entries, 1080, "jpn")

        assertNotNull(result)
        assertEquals(720, result!!.resolution)
        assertEquals("jpn", result.audio)
    }
}
