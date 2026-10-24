package com.pahebatcher.app.data.hls

import com.pahebatcher.app.domain.util.StringUtils

/**
 * Generates output filenames for assembled MP4 episodes.
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
 * sanitize() is applied to the FULL `Ep {prefix} - {title}` string
 * (utils.py:12 — keeps [\\w\\s\\-.], strips, spaces → underscores, collapses
 * runs of underscores), so the prefix and separator are sanitized together
 * with the title. Corrected in Phase 11.5E-5: this function previously
 * sanitized the title only, producing `Ep 001 - Title_Here.mp4` instead of
 * Python's `Ep_001_-_Title_Here.mp4` (PARITY §10.2).
 *
 * The Python `or f"ep_{ep.ep_str}"` fallback is unreachable and not ported:
 * sanitize() preserves the letters of "Ep ", so it can never return "".
 *
 * Examples (Python-identical):
 * - prefix "001", title "Episode Title" → "Ep_001_-_Episode_Title.mp4"
 * - prefix "013.5", title "Clash of Kings" → "Ep_013.5_-_Clash_of_Kings.mp4"
 * - title with colon/parentheses → removed, spaces collapsed to "_"
 */
object FileNameGenerator {

    /**
     * Generate episode MP4 filename.
     *
     * Sanitizes the whole `Ep {prefix} - {title}` string exactly like Python
     * (`downloader.py:57`), removing filesystem-unsafe characters
     * (/, \, :, *, ?, ", <, >, |) and replacing spaces with underscores.
     *
     * @param prefix Episode number prefix (e.g., "001", "013.5")
     * @param title Episode title (sanitized together with the prefix)
     * @return Filename like "Ep_001_-_Episode_Title.mp4"
     */
    fun episodeFileName(prefix: String, title: String): String {
        return StringUtils.sanitize("Ep $prefix - $title") + ".mp4"
    }

    /**
     * Format episode number prefix matching Python semantics.
     *
     * Python behavior (downloader.py lines 57-58):
     * - Integer: f"{num:03d}" → "001", "012", "100"
     * - Float: f"{num:05.1f}" → "001.5", "013.5"
     *
     * Integer episodes are always formatted as 3-digit zero-padded.
     * Non-integer episodes (float) are formatted as 5-char with 1 decimal.
     *
     * @param num Episode number (Int or Double/Float)
     * @return Formatted prefix string
     */
    fun formatEpisodePrefix(num: Number): String {
        return when (num) {
            is Int -> "%03d".format(num)
            is Long -> "%03d".format(num.toInt())
            is Double -> {
                if (num % 1.0 == 0.0 && num >= Int.MIN_VALUE && num <= Int.MAX_VALUE) {
                    // Whole number as double (e.g., 5.0) → integer format
                    "%03d".format(num.toInt())
                } else {
                    "%05.1f".format(num)
                }
            }
            is Float -> {
                if (num % 1.0f == 0.0f && num >= Int.MIN_VALUE.toFloat() && num <= Int.MAX_VALUE.toFloat()) {
                    "%03d".format(num.toInt())
                } else {
                    "%05.1f".format(num.toDouble())
                }
            }
            else -> num.toString()
        }
    }
}
