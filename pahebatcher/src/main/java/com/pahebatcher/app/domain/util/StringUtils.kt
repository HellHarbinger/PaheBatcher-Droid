package com.pahebatcher.app.domain.util

import com.pahebatcher.app.domain.model.EpisodeInfo

/**
 * Utility functions for string formatting and sanitization.
 * Ported from Python utils.py.
 */
object StringUtils {

    /**
     * utils.py's `[^\w\s\-.]` keep-set with Python's default (Unicode)
     * `str` semantics: `\w` = Unicode letters + numbers + underscore, spelled
     * as explicit categories `\p{L}\p{N}_` so every non-ASCII title character
     * survives instead of being deleted (ASCII-only `\w` was the Gate A
     * finding — a CJK/Arabic title used to sanitize to "").
     *
     * `Pattern.UNICODE_CHARACTER_CLASS` is deliberately NOT used: Android's
     * `java.util.regex.Pattern` rejects it at runtime with
     * `IllegalArgumentException("UNICODE_CHARACTER_CLASS flag not supported")`
     * (verified on device REDACTED-SERIAL). The category form behaves identically on
     * the JVM and on Android and matches Python's `\w` even more closely
     * (Python excludes combining marks, which the Java flag would include).
     * ASCII behavior is byte-identical to the original pattern.
     */
    private val UNSAFE_CHARS = Regex("[^\\p{L}\\p{N}_\\s\\-.]")

    /** DATA-013: compiled once (like [UNSAFE_CHARS]), not per [sanitize] call. */
    private val UNDERSCORE_RUN = Regex("_+")

    /**
     * Sanitize a name for use as a directory/file name.
     * Removes special characters, replaces spaces with underscores.
     * Ported from utils.py sanitize().
     */
    fun sanitize(name: String): String {
        val safe = name.replace(UNSAFE_CHARS, "").trim().replace(" ", "_")
        return safe.replace(UNDERSCORE_RUN, "_")
    }

    /**
     * Format episode number prefix for file naming.
     * Whole numbers: 3-digit zero-padded (e.g., "001")
     * Fractional numbers: 5-character with 1 decimal (e.g., "013.5")
     * Ported from utils.py ep_prefix().
     */
    fun epPrefix(epNum: String): String {
        return try {
            val locale = java.util.Locale.ROOT
            if (epNum.contains(".")) {
                String.format(locale, "%05.1f", epNum.toFloat())
            } else {
                String.format(locale, "%03d", epNum.toInt())
            }
        } catch (e: NumberFormatException) {
            epNum
        }
    }

    /**
     * Format bytes into human-readable string.
     * Ported from utils.py fmt_bytes().
     */
    fun fmtBytes(n: Long): String {
        var size = n.toDouble()
        val locale = java.util.Locale.ROOT
        val units = listOf("B", "KB", "MB", "GB")
        for (unit in units) {
            if (size < 1024) {
                return String.format(locale, "%.1f %s", size, unit)
            }
            size /= 1024
        }
        return String.format(locale, "%.1f TB", size)
    }

    /**
     * A2.1 (UI-004): THE episode-number renderer — a whole number without the
     * trailing `.0` ("13"), a fractional/special with its decimal ("13.5").
     *
     * One predicate decides, so every surface renders an identical number for
     * the same row (this body was previously duplicated — with two DIFFERENT
     * predicates — across `EpisodeInfo.epStr`, the details screen/ViewModel,
     * the Downloads row label and the player title). The check is the
     * `toLong()` round-trip, NOT `% 1f == 0f`: beyond Long's float precision
     * the modulo still reports "whole" while `toLong()` saturates, which would
     * print Long.MAX_VALUE garbage — the round-trip naturally falls back to
     * the raw float string there.
     */
    fun episodeNumber(number: Float): String =
        if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()

    /**
     * Create compact episode range string from list of episodes.
     * E.g., [1,2,3,5,7,8,9] -> "1-3, 5, 7-9"
     * Ported from utils.py compact_ep_range().
     */
    fun compactEpRange(episodes: List<EpisodeInfo>): String {
        if (episodes.isEmpty()) return "none"

        val nums = episodes.map { it.number }.distinct().sorted()

        if (nums.size == 1) {
            return episodeNumber(nums[0])
        }

        // Build ranges
        data class Range(val start: Float, val end: Float)

        val ranges = mutableListOf<Range>()
        var s = nums[0]
        var p = nums[0]

        for (n in nums.drop(1)) {
            if (n == p + 1) {
                p = n
            } else {
                ranges.add(Range(s, p))
                s = n
                p = n
            }
        }
        ranges.add(Range(s, p))

        // Format ranges — A2.1 (UI-004): the shared renderer decides each
        // bound's text, so a range endpoint can never disagree with a
        // single-episode label.
        val parts = ranges.map { (a, b) ->
            if (a == b) {
                episodeNumber(a)
            } else {
                "${episodeNumber(a)}–${episodeNumber(b)}"
            }
        }

        return parts.joinToString(", ")
    }

    /**
     * Audio badge for display — open-set generalisation of `utils.py audio_badge()`:
     * `jpn → SUB` and `eng → DUB` exactly like the reference, while any other
     * source audio code renders its OWN value uppercased (`kor → KOR`) instead
     * of being forced into the SUB/DUB pair (SPEC §22). A blank value has no
     * badge (`""`) — never a default `DUB`/`SUB` invention.
     */
    fun audioBadge(audio: String): String = when (audio.lowercase()) {
        "jpn" -> "SUB"
        "eng" -> "DUB"
        "" -> ""
        else -> audio.uppercase()
    }
}