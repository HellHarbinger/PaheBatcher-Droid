package com.pahebatcher.app.data.remote.parser

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for PlayPageParser — play page HTML parsing.
 *
 * Uses realistic fixtures derived from Python reference behavior.
 * Tests resolution button parsing, DUB detection, fansub extraction,
 * and fallback paths.
 */
class PlayPageParserTest {

    // ── Resolution Menu Parsing ──────────────────────────────────────────

    @Test
    fun `parseResolutionButtons extracts entries from resolutionMenu div`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc123" data-resolution="1080">1080p</button>
                <button data-src="https://kwik.si/e/def456" data-resolution="720">720p</button>
                <button data-src="https://kwik.si/e/ghi789" data-resolution="360">360p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(3, entries.size)
        assertEquals(1080, entries[0].resolution)
        assertEquals("https://kwik.si/e/abc123", entries[0].kwikUrl)
        // No `data-audio`, no dub signal → audio is UNKNOWN (""), never a
        // forced "jpn" (SPEC §22 — no invented labels).
        assertEquals("", entries[0].audio)
        assertEquals(720, entries[1].resolution)
        assertEquals(360, entries[2].resolution)
    }

    @Test
    fun `parseResolutionButtons reads the raw data-audio value`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="1080" data-audio="eng">1080p</button>
                <button data-src="https://kwik.si/e/def" data-resolution="1080">1080p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(2, entries.size)
        assertEquals("eng", entries[0].audio)
        // The second button carries no audio signal at all — unknown, not jpn.
        assertEquals("", entries[1].audio)
    }

    @Test
    fun `parseResolutionButtons preserves arbitrary open-set audio values`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="1080" data-audio="kor">1080p</button>
                <button data-src="https://kwik.si/e/def" data-resolution="720" data-audio="CHI">720p</button>
                <button data-src="https://kwik.si/e/ghi" data-resolution="360" data-audio=" jpn ">360p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(3, entries.size)
        assertEquals("kor", entries[0].audio)
        // Normalised to lowercase so exact matching can compare verbatim later.
        assertEquals("chi", entries[1].audio)
        // Trimmed — surrounding whitespace is markup noise, not part of the code.
        assertEquals("jpn", entries[2].audio)
    }

    @Test
    fun `parseResolutionButtons data-audio wins over class and text heuristics`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="1080" data-audio="jpn" class="btn eng-dub">1080p DUB</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        // The source's own attribute is authoritative; the class/text signals
        // are only consulted when `data-audio` is absent.
        assertEquals("jpn", entries[0].audio)
    }

    @Test
    fun `parseResolutionButtons never labels an entry eng from an unrelated attribute value`() {
        // The former broad `"eng" in attrs` check false-positived on the
        // data-src slug itself (kwik.py:208 — the attribute the URL lives in).
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/Engineering720" data-resolution="720" data-fansub="EngSub">720p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        // No data-audio and no SCOPED signal → unknown; the slug/fansub
        // substring must not fabricate an audio label.
        assertEquals("", entries[0].audio)
    }

    @Test
    fun `parseResolutionButtons detects DUB via class containing eng`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="720" class="btn eng-dub">720p DUB</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        assertEquals("eng", entries[0].audio)
    }

    @Test
    fun `parseResolutionButtons detects DUB via text containing dub`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="720">720p DUB</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        assertEquals("eng", entries[0].audio)
    }

    @Test
    fun `parseResolutionButtons extracts fansub from data-fansub`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="1080" data-fansub="SubsPlease">1080p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        assertEquals("SubsPlease", entries[0].fansub)
    }

    @Test
    fun `parseResolutionButtons extracts fansub from text before middle dot`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="1080">SubsPlease · 1080p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        assertEquals("SubsPlease", entries[0].fansub)
    }

    @Test
    fun `parseResolutionButtons fansub fallback is the whole text when there is no separator`() {
        // Python: fansub = fansub_m.group(1) if fansub_m else text.split("·")[0].strip()
        // With no "·" the split head IS the whole text — never "".
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="1080">SubsPlease</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        assertEquals("SubsPlease", entries[0].fansub)
    }

    @Test
    fun `parseResolutionButtons requires the text resolution to start the text like Python re_match`() {
        // Python: re.match(r"(\d+)\s*p", text, re.I) — anchored at the start of
        // the button text, so "XviD HD 1080p" does not resolve there. The two
        // words also keep the menu-less fallback regex (which tolerates exactly
        // one leading word) from matching, so an unanchored match is the ONLY
        // way this could yield an entry.
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc">XviD HD 1080p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertTrue("Python would not select this entry either", entries.isEmpty())
    }

    @Test
    fun `parseResolutionButtons falls back to regex when no resolutionMenu`() {
        val html = """
            <a href="https://kwik.si/e/abc123" data-src="https://kwik.si/e/abc123">Watch 1080p</a>
            <a href="https://kwik.si/e/def456" data-src="https://kwik.si/e/def456">Watch 720p</a>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(2, entries.size)
        assertEquals(1080, entries[0].resolution)
        assertEquals(720, entries[1].resolution)
        // Menu-less audio comes from the URL-label heuristic: these slugs carry
        // no audio signal, so the label stays UNKNOWN instead of a forced "jpn".
        assertEquals("", entries[0].audio)
        assertEquals("", entries[1].audio)
    }

    @Test
    fun `menu-less entries are labelled by the URL heuristic`() {
        val html = """
            <a href="https://kwik.si/e/eng1080" data-src="https://kwik.si/e/eng1080">Watch 1080p</a>
            <a href="https://kwik.si/e/sub720" data-src="https://kwik.si/e/sub720">Watch 720p</a>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(2, entries.size)
        assertEquals("eng", entries[0].audio)
        assertEquals("jpn", entries[1].audio)
    }

    @Test
    fun `parseResolutionButtons returns empty list for empty HTML`() {
        val entries = PlayPageParser.parseResolutionButtons("")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parseResolutionButtons returns empty list when no kwik URLs`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://other.com/video" data-resolution="1080">1080p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parseResolutionButtons handles resolution from text when no data-resolution`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc">720p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        assertEquals(720, entries[0].resolution)
    }

    @Test
    fun `parseResolutionButtons skips entries without resolution`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc">No resolution info</button>
                <button data-src="https://kwik.si/e/def" data-resolution="720">720p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(1, entries.size)
        assertEquals(720, entries[0].resolution)
    }

    @Test
    fun `parseResolutionButtons handles multiple kwik domains`() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.si/e/abc" data-resolution="1080">1080p</button>
                <button data-src="https://kwik.cx/e/def" data-resolution="720">720p</button>
                <button data-src="https://kwik.gg/e/ghi" data-resolution="360">360p</button>
            </div>
        """.trimIndent()

        val entries = PlayPageParser.parseResolutionButtons(html)

        assertEquals(3, entries.size)
        assertTrue(entries[0].kwikUrl.contains("kwik.si"))
        assertTrue(entries[1].kwikUrl.contains("kwik.cx"))
        assertTrue(entries[2].kwikUrl.contains("kwik.gg"))
    }

    // ── Title Extraction ─────────────────────────────────────────────────

    @Test
    fun `extractTitle extracts title from title tag`() {
        val html = "<html><head><title>Watch Spy x Family Episode 1 - AnimePahe</title></head></html>"
        assertEquals("Watch Spy x Family Episode 1 - AnimePahe", PlayPageParser.extractTitle(html))
    }

    @Test
    fun `extractTitle returns empty string when no title tag`() {
        assertEquals("", PlayPageParser.extractTitle("<html><body>No title</body></html>"))
    }
}
