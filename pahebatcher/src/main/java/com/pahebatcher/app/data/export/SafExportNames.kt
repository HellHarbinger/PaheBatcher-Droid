package com.pahebatcher.app.data.export

import java.text.Normalizer

/**
 * Pure display-name rules for the SAF export path (DECISIONS 104.1/104.6).
 *
 * Everything here is a side-effect-free function over strings so the
 * collision/ownership contract is directly JVM-testable (§36 unit-test
 * surface); [SafExporter] performs no name arithmetic of its own.
 *
 * Contract:
 *  - [PART_SUFFIX] is the application marker for app-owned export temporaries
 *    (104.1) — the single carve-out from "never delete user files" (104.2).
 *  - Comparison key = Unicode NFC-normalized display name, case-insensitive
 *    over the WHOLE name including the extension (104.6) — conservative for
 *    case-insensitive providers, stable on case-sensitive ones.
 *  - Collision resolution = reuse-by-size, else the first free
 *    `<base> (n).<ext>` with n starting at 2 (104.6); generated `(n)` names
 *    follow the same rule on later exports (they are just names in the
 *    existing set).
 */
object SafExportNames {

    /** Literal application-owned export temporary suffix (104.1). */
    const val PART_SUFFIX = ".pb.part"

    /** Whether [name] is an app-owned export temporary (104.2 ownership). */
    fun isPart(name: String): Boolean = name.endsWith(PART_SUFFIX)

    /** Strip the application suffix, yielding the intended final name (104.3). */
    fun partTarget(name: String): String = name.removeSuffix(PART_SUFFIX)

    /**
     * NFC-normalized, whole-name case-insensitive comparison key (104.6).
     * `String.lowercase()` is locale-independent (Locale.ROOT semantics), so
     * the key is stable across device locales.
     */
    fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase()

    /**
     * First free `<base> (n).<ext>` with n ≥ 2, or [requested] itself when its
     * key is unused (104.6). `existing` is the set of final-name children in
     * the tree; `.pb.part` names are excluded by the caller (different suffix,
     * never participate).
     */
    fun uniquify(requested: String, existing: Collection<String>): String {
        val taken = existing.mapTo(mutableSetOf()) { key(it) }
        if (key(requested) !in taken) return requested
        val dot = requested.lastIndexOf('.')
        val hasExt = dot > 0
        val base = if (hasExt) requested.substring(0, dot) else requested
        val ext = if (hasExt) requested.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (key(candidate) !in taken) return candidate
            n++
        }
    }

    /**
     * Whether the intended final name of a stale `.pb.part` (its suffix
     * stripped) belongs to THIS export's episode: exact key match, or the
     * uniquified `<base> (n).<ext>` form of the expected name (104.3/104.5).
     *
     * Episode display names never contain parentheses (FileNameGenerator's
     * sanitize strips them), so a `(n)` suffix can only have been generated
     * by the collision rule — no ambiguity with user files.
     */
    fun isOurs(strippedName: String, expectedName: String): Boolean {
        if (key(strippedName) == key(expectedName)) return true
        val matcher = UNIQUE_SUFFIX.matchEntire(strippedName) ?: return false
        val rebuilt = matcher.groupValues[1] + matcher.groupValues[2]
        return key(rebuilt) == key(expectedName)
    }

    /** `<base> (n)<ext>` decomposition: group 1 = base, group 2 = extension. */
    private val UNIQUE_SUFFIX = Regex("^(.+) \\(\\d+\\)(\\.[^.]+)$")
}
