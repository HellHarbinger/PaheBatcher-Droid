package com.pahebatcher.app.data.remote.parser

import com.pahebatcher.app.domain.model.ResolutionEntry

/**
 * Parser for AnimePahe play pages.
 *
 * Extracts resolution buttons, Kwik URLs, audio tracks, and fansub information
 * from the play page HTML.
 *
 * Ported from Python kwik.py _parse_resolution_buttons(), with the audio
 * component generalized to the approved open-set model: the reference reduces
 * `data-audio` to an `is_dub` boolean (kwik.py:201-210) and the resolver later
 * re-derives a closed `jpn`/`eng` from it; this port preserves the source's
 * raw audio string on every entry (SPEC §22 "Audio selection (per episode)").
 */
object PlayPageParser {

    /**
     * Raw `data-audio="…"` attribute value — the source's own audio identity,
     * captured as a string (open-set) rather than reduced to a boolean.
     */
    private val DATA_AUDIO = Regex(
        """data-audio\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    // NET-005: compiled ONCE. Every pattern below is a compile-time constant that
    // was previously recompiled on each call — parseResolutionMenuEntries alone
    // recompiled three of them PER BUTTON, on the per-play-page hot path.
    private val MENU_PATTERN = Regex(
        """<div[^>]+id=["']resolutionMenu["'][^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val BUTTON_PATTERN = Regex(
        """<button\b([^>]*?)>(.*?)</button>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val DATA_SRC_PATTERN = Regex(
        """data-src=["']([^"']+kwik\.[^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val FALLBACK_PATTERN = Regex(
        """(?:href|data-src)=["']([^"']*kwik\.[^"']+)["'][^>]*>\s*(?:\S+\s+)?(\d+)p""",
        RegexOption.IGNORE_CASE
    )
    private val TITLE_PATTERN = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val DATA_RESOLUTION_PATTERN =
        Regex("""data-resolution=["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
    private val TEXT_RESOLUTION_PATTERN = Regex("""^(\d+)\s*p""", RegexOption.IGNORE_CASE)
    private val CLASS_ENG_PATTERN =
        Regex("""class\s*=\s*["'][^"']*eng[^"']*["']""", RegexOption.IGNORE_CASE)
    private val DATA_FANSUB_PATTERN = Regex("""data-fansub=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * Parse resolution entries from play page HTML.
     *
     * Menu first, then the no-menu regex — exactly Python's structure:
     * `_parse_resolution_buttons()` runs first (kwik.py 176-211) and, only when
     * it yields nothing, `extract_stream()` fills its `quality_map` from the
     * fallback regex (kwik.py 241-247).
     *
     * @param html Play page HTML content
     * @return List of ResolutionEntry parsed from the page
     */
    fun parseResolutionButtons(html: String): List<ResolutionEntry> {
        val menuEntries = parseResolutionMenuEntries(html)
        if (menuEntries.isNotEmpty()) return menuEntries
        return parseFallbackEntries(html)
    }

    /**
     * Entries from the `#resolutionMenu` div ONLY (Python `_parse_resolution_buttons`,
     * kwik.py 176-211 — an absent menu returns an empty list, nothing else).
     *
     * Audio on these entries is the source's own label (see [extractAudio]);
     * the menu-less branch labels through the URL heuristic instead, and BOTH
     * then pass the same exact-audio selection rule.
     */
    fun parseResolutionMenuEntries(html: String): List<ResolutionEntry> {
        val entries = mutableListOf<ResolutionEntry>()
        val menuMatch = MENU_PATTERN.find(html)
        if (menuMatch != null) {
            val menuHtml = menuMatch.groupValues[1]
            for (btnMatch in BUTTON_PATTERN.findAll(menuHtml)) {
                val attrs = btnMatch.groupValues[1]
                val text = btnMatch.groupValues[2].trim()

                // Extract kwik URL from data-src
                val srcMatch = DATA_SRC_PATTERN.find(attrs) ?: continue
                val kwikUrl = srcMatch.groupValues[1]

                // Extract resolution
                val res = extractResolution(attrs, text) ?: continue

                // Extract the source's own audio label (open-set string)
                val audio = extractAudio(attrs, text)

                // Extract fansub
                val fansub = extractFansub(attrs, text)

                entries.add(ResolutionEntry(res, kwikUrl, audio, fansub))
            }
        }
        return entries
    }

    /**
     * Fallback extraction for pages without usable menu entries.
     * Regex identical to Python kwik.py 241-244 (`(?:href|data-src)=...(\d+)p`).
     *
     * Audio here comes from the approved URL-label heuristic: the reference
     * reads `eng`/`dub` out of the URL (kwik.py:246) and maps "not dub" to
     * `jpn`; the open-set port keeps that heuristic as the label SOURCE but
     * never invents a label — a URL carrying no audio signal yields `""`
     * (unknown), which the exact-audio filter in [com.pahebatcher.app.data.stream.QualitySelector]
     * can then only match when the caller genuinely asked for an unknown audio
     * (it never does), producing an explicit failure instead of a forced label
     * (SPEC §22: never silently substitute requested audio).
     */
    fun parseFallbackEntries(html: String): List<ResolutionEntry> {
        val entries = mutableListOf<ResolutionEntry>()
        for (match in FALLBACK_PATTERN.findAll(html)) {
            val url = match.groupValues[1]
            val quality = match.groupValues[2].toIntOrNull() ?: continue
            entries.add(ResolutionEntry(quality, url, audioLabelFromUrl(url), ""))
        }
        return entries
    }

    /**
     * The URL-label heuristic of the menu-less branch (kwik.py:246, generalized
     * to a symmetric label instead of the reference's closed `is_dub → jpn|eng`
     * mapping): a `eng`/`dub` signal in the URL labels `eng`, a `jpn`/`sub`
     * signal labels `jpn`, and a URL with no signal stays `""` (unknown).
     */
    private fun audioLabelFromUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            "eng" in lower || "dub" in lower -> "eng"
            "jpn" in lower || "sub" in lower -> "jpn"
            else -> ""
        }
    }

    /**
     * Extract the page title from play page HTML.
     *
     * @param html Play page HTML content
     * @return Page title or empty string
     */
    fun extractTitle(html: String): String {
        return TITLE_PATTERN.find(html)?.groupValues?.get(1)?.trim() ?: ""
    }

    /**
     * Extract resolution from button attributes or text.
     * Matches Python: data-resolution attribute, then (\d+)\s*p from text.
     */
    private fun extractResolution(attrs: String, text: String): Int? {
        // Try data-resolution attribute first
        DATA_RESOLUTION_PATTERN.find(attrs)?.let {
            return it.groupValues[1].toIntOrNull()
        }

        // Fall back to text pattern: "720p", "1080p", etc.
        // Python: re.match(r"(\d+)\s*p", text, re.I) — ANCHORED at the start of
        // the button text, so "XviD 1080p" does NOT resolve here (Python: None).
        TEXT_RESOLUTION_PATTERN.find(text)?.let {
            return it.groupValues[1].toIntOrNull()
        }

        return null
    }

    /**
     * The source's audio label for ONE menu button, in the approved order
     * (SPEC §22 / architecture review §6):
     *
     * 1. the raw `data-audio` attribute — authoritative and open-set: any
     *    value the source exposes (`jpn`, `eng`, `kor`, …) is preserved
     *    verbatim (lowercased/trimmed so it can be matched exactly later);
     * 2. when there is no `data-audio`, Python's remaining SCOPED signals —
     *    a class value containing `eng`, or button text containing `dub`
     *    (kwik.py:201-210 minus its broad `"eng" in attrs` substring, which
     *    false-positives on unrelated attribute values such as the `data-src`
     *    URL slug);
     * 3. otherwise `""` — unknown. Never a forced `jpn`: with no source audio
     *    signal the exact filter downstream fails explicitly instead of
     *    inventing a label (approved §7: "unknown source audio → force JPN"
     *    is forbidden).
     */
    private fun extractAudio(attrs: String, text: String): String {
        DATA_AUDIO.find(attrs)?.let {
            return it.groupValues[1].trim().lowercase()
        }

        // Python's class-scoped signal — scoped to the class attribute only.
        if (CLASS_ENG_PATTERN.containsMatchIn(attrs)) return "eng"

        // Python's text signal: a "dub" label in the button text (kwik.py maps
        // is_dub → "eng" when it builds the StreamInfo).
        if ("dub" in text.lowercase()) return "eng"

        return ""
    }

    /**
     * Extract fansub group name.
     * Matches Python: `data-fansub` attribute, else `text.split("·")[0].strip()`.
     * Python's split-head is the WHOLE text when the separator is absent — it
     * never returns an empty string — so this does the same.
     */
    private fun extractFansub(attrs: String, text: String): String {
        DATA_FANSUB_PATTERN.find(attrs)?.let {
            return it.groupValues[1]
        }

        // Fallback: the head of the "·"-separated button text (Python semantics).
        return text.split("\u00b7")[0].trim()
    }
}
