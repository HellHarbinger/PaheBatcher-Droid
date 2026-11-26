package com.pahebatcher.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the security-pin resolution decision (DECISIONS.md 167.12).
 *
 * The central invariant: selecting a Guava flavor NEVER downgrades a newer
 * requested base version, and a prerelease is never treated as equivalent to
 * its final release.
 */
class PbDependencyVersionsTest {

    private val guavaPin = "33.7.2"

    // --- pbVersionAtLeast: the minimum-pin gate -----------------------------

    @Test
    fun `a version equal to the pin satisfies the pin`() {
        assertTrue(pbVersionAtLeast("33.7.2", "33.7.2"))
        assertTrue(pbVersionAtLeast("1.86", "1.86"))
        assertTrue(pbVersionAtLeast("2.0.6.1", "2.0.6.1"))
        assertTrue(pbVersionAtLeast("0.9.6", "0.9.6"))
    }

    @Test
    fun `a version below the pin does not satisfy the pin`() {
        assertFalse(pbVersionAtLeast("33.4.0", "33.7.2"))
        assertFalse(pbVersionAtLeast("31.1", "33.7.2"))
        assertFalse(pbVersionAtLeast("1.85", "1.86"))
        assertFalse(pbVersionAtLeast("2.0.6", "2.0.6.1"))
        assertFalse(pbVersionAtLeast("0.9.5", "0.9.6"))
    }

    @Test
    fun `a version above the pin satisfies the pin`() {
        assertTrue(pbVersionAtLeast("34.0.0", "33.7.2"))
        assertTrue(pbVersionAtLeast("1.87", "1.86"))
        assertTrue(pbVersionAtLeast("4.5.15", "4.5.14"))
    }

    @Test
    fun `numeric components compare numerically not lexicographically`() {
        assertTrue(pbVersionAtLeast("1.10", "1.9"))
        assertFalse(pbVersionAtLeast("1.9", "1.10"))
        assertTrue(pbVersionAtLeast("33.10.0", "33.9.9"))
        assertFalse(pbVersionAtLeast("33.9.9", "33.10.0"))
        assertEquals(0, pbCompareVersions("1.10", "1.10.0"))
    }

    @Test
    fun `a prerelease is below its corresponding final release`() {
        // The old numeric-only comparator called these equal; it must not.
        assertFalse(pbVersionAtLeast("33.7.2-rc1", "33.7.2"))
        assertFalse(pbVersionAtLeast("33.7.2-alpha1", "33.7.2"))
        assertFalse(pbVersionAtLeast("1.86-rc2", "1.86"))
        assertTrue(pbVersionAtLeast("33.7.2", "33.7.2-rc1"))
        assertTrue(pbVersionAtLeast("33.7.2", "33.7.2-alpha1"))
    }

    @Test
    fun `prereleases of the same base order deterministically`() {
        assertTrue(pbVersionAtLeast("33.7.2-rc2", "33.7.2-rc1"))
        assertFalse(pbVersionAtLeast("33.7.2-rc1", "33.7.2-rc2"))
        assertEquals(0, pbCompareVersions("33.7.2-rc1", "33.7.2-rc1"))
        assertFalse(pbVersionAtLeast("33.7.2-beta1", "33.7.2-rc1"))
        assertTrue(pbVersionAtLeast("33.7.2-snapshot", "33.7.2-beta1"))
    }

    @Test
    fun `a flavor suffix does not push a pinned base below the pin`() {
        assertTrue(pbVersionAtLeast("33.7.2-android", "33.7.2"))
        assertTrue(pbVersionAtLeast("33.7.2-jre", "33.7.2"))
        assertTrue(pbVersionAtLeast("34.0.0-jre", "33.7.2"))
    }

    // --- pbGuavaTargetVersion: flavor + pin decision ------------------------

    @Test
    fun `requested version below the pin is raised to the pin with the selected flavor`() {
        assertEquals("33.7.2-jre", pbGuavaTargetVersion("33.4.0", guavaPin, "jre"))
        assertEquals("33.7.2-android", pbGuavaTargetVersion("33.4.0-android", guavaPin, "android"))
        assertEquals("33.7.2-jre", pbGuavaTargetVersion("31.1-jre", guavaPin, "jre"))
        assertEquals("33.7.2-android", pbGuavaTargetVersion("33.4.0-jre", guavaPin, "android"))
    }

    @Test
    fun `requested version equal to the pin takes the selected flavor`() {
        assertEquals("33.7.2-android", pbGuavaTargetVersion("33.7.2", guavaPin, "android"))
        assertEquals("33.7.2-jre", pbGuavaTargetVersion("33.7.2", guavaPin, "jre"))
    }

    @Test
    fun `a newer requested base keeps its base when the flavor is applied`() {
        assertEquals("34.0.0-android", pbGuavaTargetVersion("34.0.0", guavaPin, "android"))
        assertEquals("34.0.0-jre", pbGuavaTargetVersion("34.0.0", guavaPin, "jre"))
    }

    @Test
    fun `a newer requested base with the opposite flavor is re-flavored never downgraded`() {
        // Regression guard: the old logic forced "$pin-$flavor" whenever the
        // flavor differed, so a future request for 34.0.0-jre under the android
        // flavor would have been downgraded to 33.7.2-android.
        assertEquals("34.0.0-android", pbGuavaTargetVersion("34.0.0-jre", guavaPin, "android"))
        assertEquals("34.0.0-jre", pbGuavaTargetVersion("34.0.0-android", guavaPin, "jre"))
        assertEquals("35.1.2-android", pbGuavaTargetVersion("35.1.2-jre", guavaPin, "android"))
        assertEquals("35.1.2-jre", pbGuavaTargetVersion("35.1.2-android", guavaPin, "jre"))
    }

    @Test
    fun `a request already using the selected flavor is not rewritten`() {
        assertEquals("33.7.2-jre", pbGuavaTargetVersion("33.7.2-jre", guavaPin, "jre"))
        assertEquals("33.7.2-android", pbGuavaTargetVersion("33.7.2-android", guavaPin, "android"))
        assertEquals("34.0.0-jre", pbGuavaTargetVersion("34.0.0-jre", guavaPin, "jre"))
        assertEquals("34.0.0-android", pbGuavaTargetVersion("34.0.0-android", guavaPin, "android"))
    }

    @Test
    fun `a prerelease below the pin is raised to the pinned release with the selected flavor`() {
        assertEquals("33.7.2-android", pbGuavaTargetVersion("33.7.2-rc1", guavaPin, "android"))
        assertEquals("33.7.2-jre", pbGuavaTargetVersion("33.7.2-rc1", guavaPin, "jre"))
    }

    @Test
    fun `a prerelease above the pin keeps its base when the flavor is applied`() {
        assertEquals(
            "34.0.0-rc1-android",
            pbGuavaTargetVersion("34.0.0-rc1", guavaPin, "android"),
        )
        assertEquals(
            "34.0.0-rc1-jre",
            pbGuavaTargetVersion("34.0.0-rc1-jre", guavaPin, "jre"),
        )
    }

    // --- pbGuavaFlavor: configuration -> flavor selection -------------------

    @Test
    fun `buildscript classpath resolves the jre flavor`() {
        assertEquals("jre", pbGuavaFlavor("classpath"))
    }

    @Test
    fun `androidLintTool resolves the jre flavor`() {
        assertEquals("jre", pbGuavaFlavor("androidLintTool"))
    }

    @Test
    fun `ksp processor configurations resolve the jre flavor`() {
        assertEquals("jre", pbGuavaFlavor("ksp"))
        assertEquals("jre", pbGuavaFlavor("kspKotlin"))
        assertEquals("jre", pbGuavaFlavor("androidTestKsp"))
    }

    @Test
    fun `compile runtime lint and annotation-processor configurations resolve the android flavor`() {
        assertEquals("android", pbGuavaFlavor("compileClasspath"))
        assertEquals("android", pbGuavaFlavor("debugRuntimeClasspath"))
        assertEquals("android", pbGuavaFlavor("releaseRuntimeClasspath"))
        assertEquals("android", pbGuavaFlavor("lintChecks"))
        assertEquals("android", pbGuavaFlavor("annotationProcessor"))
    }

    // --- the invariant: flavor selection never downgrades a newer base ------

    @Test
    fun `selecting a flavor never downgrades a newer requested base`() {
        val requests = listOf(
            // at/above the pin: the requested base must survive untouched
            "34.0.0",
            "34.0.0-jre",
            "34.0.0-android",
            "35.1.2-jre",
            "33.7.2",
            "33.7.2-jre",
            "33.7.2-android",
            "34.0.0-rc1-jre",
            // below the pin: may be raised to the pin, never lowered
            "33.4.0",
            "31.1-jre",
            "33.7.2-rc1",
        )
        for (requested in requests) {
            for (flavor in listOf("jre", "android")) {
                val target = pbGuavaTargetVersion(requested, guavaPin, flavor)
                val targetBase = target.removeSuffix("-jre").removeSuffix("-android")
                val requestedBase = requested.removeSuffix("-jre").removeSuffix("-android")
                assertTrue(
                    "target $target must be at least the pin $guavaPin",
                    pbVersionAtLeast(targetBase, guavaPin),
                )
                if (pbVersionAtLeast(requested, guavaPin)) {
                    assertEquals(
                        "base of $requested must survive flavor selection ($flavor)",
                        requestedBase,
                        targetBase,
                    )
                }
            }
        }
    }
}
