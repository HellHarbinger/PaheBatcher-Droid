package com.pahebatcher.buildlogic

/*
 * Pure version-comparison and Guava flavor-decision logic for the security
 * pins applied by pahebatcher/build.gradle.kts. Kept in buildSrc so it is
 * unit-testable (PbDependencyVersionsTest); both the buildscript block and
 * the project-level resolution strategy call into it.
 *
 * Version forms this repository actually deals with:
 *   1.86, 2.0.6.1, 0.9.6, 3.18.0, 4.5.14   - plain numeric pin versions
 *   33.7.2, 34.0.0                         - plain numeric Guava base versions
 *   33.7.2-android, 33.7.2-jre             - Guava flavor variants
 *   33.7.2-rc1                             - prerelease of a pin's base
 *
 * The comparison is deterministic and never lexicographically compares the
 * version as a whole (1.10 > 1.9, not String.compareTo order). Qualifier
 * ordering follows the Maven convention: prereleases rank below the final
 * release, the final release below `sp`, and non-standard suffixes (the
 * Guava -android/-jre flavors) above the final release so a flavor variant
 * of the pin's base is never mistaken for a version below the pin.
 *
 * The package is deliberately NOT named `...build`: .gitignore ignores every
 * directory called `build/` at any depth, which would silently swallow the
 * package directory.
 */

private const val RANK_ALPHA = 1
private const val RANK_BETA = 2
private const val RANK_MILESTONE = 3
private const val RANK_RC = 4
private const val RANK_SNAPSHOT = 5
private const val RANK_FINAL = 6
private const val RANK_SP = 7
private const val RANK_UNKNOWN = 8

private class Qualifier(val rank: Int, val suffix: Int, val raw: String)

/** The implicit qualifier of a version that carries no suffix (a final release). */
private val FINAL_QUALIFIER = Qualifier(RANK_FINAL, 0, "")

private fun parseQualifier(raw: String): Qualifier {
    val q = raw.lowercase()
    val family = q.takeWhile { it.isLetter() }
    val suffix = q.dropWhile { it.isLetter() }.toIntOrNull() ?: 0
    val rank = when (family) {
        "a", "alpha" -> RANK_ALPHA
        "b", "beta" -> RANK_BETA
        "m", "milestone" -> RANK_MILESTONE
        // "cr" is treated as an alias of "rc"; both are release candidates.
        "rc", "cr" -> RANK_RC
        "snapshot", "snap" -> RANK_SNAPSHOT
        "sp" -> RANK_SP
        else -> RANK_UNKNOWN
    }
    return Qualifier(rank, suffix, q)
}

private fun compareQualifiers(a: Qualifier, b: Qualifier): Int {
    if (a.rank != b.rank) return a.rank.compareTo(b.rank)
    // Same family: numeric suffix decides (rc1 < rc2). Unknown suffixes
    // (android vs jre) tie-break lexicographically, matching Maven.
    if (a.rank != RANK_UNKNOWN && a.suffix != b.suffix) {
        return a.suffix.compareTo(b.suffix)
    }
    return a.raw.compareTo(b.raw)
}

private class ParsedVersion(val numerics: List<Long>, val qualifiers: List<Qualifier>)

private fun parseVersion(version: String): ParsedVersion {
    val numerics = ArrayList<Long>()
    val qualifiers = ArrayList<Qualifier>()
    for (token in version.split('.', '-')) {
        if (token.isEmpty()) continue
        if (token.all { it.isDigit() }) {
            numerics.add(token.toLongOrNull() ?: 0L)
        } else {
            qualifiers.add(parseQualifier(token))
        }
    }
    return ParsedVersion(numerics, qualifiers)
}

/**
 * Deterministic version comparison: numeric components first (padded with
 * zeros on the shorter side), then qualifiers (missing side = final release).
 * Returns a negative/zero/positive integer as [left] is less than/equal to/
 * greater than [right].
 */
fun pbCompareVersions(left: String, right: String): Int {
    val l = parseVersion(left)
    val r = parseVersion(right)
    for (i in 0 until maxOf(l.numerics.size, r.numerics.size)) {
        val lv = l.numerics.getOrElse(i) { 0L }
        val rv = r.numerics.getOrElse(i) { 0L }
        if (lv != rv) return lv.compareTo(rv)
    }
    for (i in 0 until maxOf(l.qualifiers.size, r.qualifiers.size)) {
        val lq = l.qualifiers.getOrElse(i) { FINAL_QUALIFIER }
        val rq = r.qualifiers.getOrElse(i) { FINAL_QUALIFIER }
        val c = compareQualifiers(lq, rq)
        if (c != 0) return c
    }
    return 0
}

/**
 * Minimum-pin gate: true when [actual] is at least [required]. A prerelease
 * of the required version (e.g. `33.7.2-rc1` vs `33.7.2`) is BELOW it, so it
 * is not considered satisfied and the pin raises it.
 */
fun pbVersionAtLeast(actual: String, required: String): Boolean =
    pbCompareVersions(actual, required) >= 0

/**
 * Flavor selection for Guava: JVM tooling classpaths (buildscript classpath,
 * androidLintTool, ksp processor paths) resolve the `-jre` flavor, every other
 * configuration resolves `-android`, so the two can never collide in one graph.
 */
fun pbGuavaFlavor(configurationName: String): String =
    if (configurationName == "classpath" ||
        configurationName == "androidLintTool" ||
        configurationName.contains("ksp", ignoreCase = true)
    ) {
        "jre"
    } else {
        "android"
    }

/**
 * Resolution target for a single Guava request. Guarantees:
 *
 * 1. [pin] is the MINIMUM acceptable base version: a request below it is
 *    raised to the pin with [flavor] applied.
 * 2. A requested base NEWER than the pin is always preserved - applying the
 *    flavor never swaps the base back to the pin (no downgrade), even when
 *    the request carries the opposite flavor suffix.
 * 3. A request already using [flavor] at/above the pin is returned unchanged.
 * 4. Prereleases are ordered against the final release (33.7.2-rc1 < 33.7.2),
 *    so a prerelease of the pin is raised to the pinned release.
 *
 * The returned string is the exact version this configuration should resolve.
 */
fun pbGuavaTargetVersion(requested: String, pin: String, flavor: String): String {
    val requestedFlavor = when {
        requested.endsWith("-jre") -> "jre"
        requested.endsWith("-android") -> "android"
        else -> null
    }
    val requestedBase = requestedFlavor?.let { requested.dropLast(it.length + 1) }
        ?: requested
    return when {
        pbCompareVersions(requested, pin) < 0 -> "$pin-$flavor"
        requestedFlavor == flavor -> requested
        else -> "$requestedBase-$flavor"
    }
}
