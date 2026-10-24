package com.pahebatcher.app.data.export

import com.pahebatcher.app.domain.util.StringUtils
import java.util.Locale

/**
 * Gate A: destination DIRECTORY naming for exported episodes.
 *
 * Python parity (main.py:288-289, watchlist.py:968-971): an exported episode
 * lives at `os.path.join(output, sanitize(anime.title)) / fname` — i.e.
 * `<output>/<sanitized anime title>/<episode file>`. The episode FILE name
 * stays with [com.pahebatcher.app.data.hls.FileNameGenerator]; this object
 * owns only the directory component.
 *
 * The plain Python sanitizer is not a complete cross-platform path-component
 * validator, so AFTER [StringUtils.sanitize] the DIRECTORY component is
 * hardened (directory-only — episode filename sanitization is untouched):
 *  - `/` and `\` path separators and control characters can never remain;
 *  - the component can never be `.` or `..` (path traversal): leading and
 *    trailing dots/spaces are trimmed (Windows rejects trailing dot/space
 *    names, and a leading dot hides the folder from Android file explorers);
 *  - Windows reserved device names (`CON`, `COM1`, …) get an `_` prefix,
 *    matched on the first name segment (`NUL.txt` shape included);
 *  - an empty result falls back deterministically to the anime SESSION id
 *    (stable per anime, filesystem-safe by construction — the same invariant
 *    `EpisodeId.build` documents), then to [LAST_RESORT_FALLBACK].
 *
 * Ordinary internal periods are PRESERVED (`Dr. Stone` → `Dr._Stone`, exactly
 * Python's `sanitize` output: space → `_`, `.` kept). Every returned value is
 * a single path segment: no `/` or `\` can appear in it.
 */
object OutputPathNames {

    /** Relative MediaStore prefix every destination directory lives under. */
    const val MOVIES_PREFIX = "Movies/"

    /**
     * Pre-Gate-A flat destination. Legacy exports stay valid and playable
     * there, and stale pending rows in it stay recoverable; NEW exports never
     * write here.
     */
    const val LEGACY_RELATIVE_PATH = "Movies/PaheBatcher/"

    /**
     * Last-resort component when neither the title nor the session id yields a
     * usable name. Unreachable for persisted rows (session ids are table keys),
     * kept only so the path component can never be empty.
     */
    private const val LAST_RESORT_FALLBACK = "PaheBatcher"

    /** Windows reserved device names, matched case-insensitively. */
    private val WINDOWS_RESERVED: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") }.toSet()

    private val UNDERSCORE_RUN = Regex("_+")

    /**
     * `Movies/<sanitized title>/` — the exact `RELATIVE_PATH` shape MediaStore
     * stores (trailing slash included).
     */
    fun mediaStoreRelativePath(animeTitle: String?, sessionId: String): String =
        MOVIES_PREFIX + directoryName(animeTitle, sessionId) + "/"

    /**
     * The single safe directory component for [animeTitle].
     * Fallback chain: title → session id → [LAST_RESORT_FALLBACK].
     * Deterministic: equal input always yields equal output.
     */
    fun directoryName(animeTitle: String?, sessionId: String): String {
        harden(StringUtils.sanitize(animeTitle.orEmpty()))?.let { return it }
        harden(StringUtils.sanitize(sessionId))?.let { return it }
        return LAST_RESORT_FALLBACK
    }

    /** Sanitized → hardened component, or null when nothing usable remains. */
    private fun harden(sanitized: String): String? {
        if (sanitized.isEmpty()) return null
        // Defense in depth: separators and control characters become `_`
        // (the sanitize keep-set already excludes them).
        val separated = buildString(sanitized.length) {
            for (ch in sanitized) {
                append(
                    when {
                        ch == '/' || ch == '\\' -> '_'
                        ch.isISOControl() -> '_'
                        else -> ch
                    },
                )
            }
        }
        val trimmed = separated
            .replace(UNDERSCORE_RUN, "_")
            // Leading/trailing whitespace and dots are unsafe for a path
            // component — trimming also turns `.`/`..` into empty.
            .trim { it.isWhitespace() || it == '\u00A0' || it == '.' }
        if (trimmed.isEmpty()) return null
        if (trimmed.substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_RESERVED) {
            return "_$trimmed"
        }
        return trimmed
    }
}
