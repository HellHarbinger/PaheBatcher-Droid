package com.pahebatcher.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate A: the DIRECTORY component every export destination derives from —
 * Python `sanitize(anime.title)` parity plus the directory-only path-safety
 * hardening (OutputPathNames KDoc). The session id `sess-fixed` is the
 * deterministic fallback seed used across cases.
 */
class OutputPathNamesTest {

    private val session = "sess-fixed"

    private fun dir(title: String): String =
        OutputPathNames.directoryName(title, session)

    private fun assertSafeComponent(name: String) {
        assertFalse("no '/' in $name", name.contains('/'))
        assertFalse("no '\\' in $name", name.contains('\\'))
        assertFalse("no control chars in $name", name.any { it.isISOControl() })
        assertFalse("'.' is never a component: $name", name == ".")
        assertFalse("'..' is never a component: $name", name == "..")
        assertTrue("non-empty component", name.isNotEmpty())
        assertFalse("no leading dot: $name", name.startsWith('.'))
        assertFalse("no trailing dot: $name", name.endsWith('.'))
        assertFalse("no trailing space: $name", name.endsWith(' '))
    }

    // ------------------------------------------------------------------
    // Python sanitizer semantics (ordinary titles)
    // ------------------------------------------------------------------

    @Test
    fun ordinaryAsciiTitle_spacesBecomeUnderscores() {
        assertEquals("Spy_x_Family", dir("Spy x Family"))
        assertSafeComponent(dir("Spy x Family"))
    }

    @Test
    fun punctuationIsRemoved_hyphensSurvive() {
        assertEquals("The_Worlds_Strongest_Rearguard", dir("The World's Strongest Rearguard"))
        assertEquals("Re-Zero", dir("Re-Zero"))
        assertSafeComponent(dir("The World's Strongest Rearguard"))
    }

    @Test
    fun internalPeriodIsPreserved_notRewritten() {
        // Python `Dr. Stone` → `Dr._Stone` — the period is NOT removed, only
        // the space becomes '_'. Gate A explicitly forbids "Dr_Stone".
        assertEquals("Dr._Stone", dir("Dr. Stone"))
        assertSafeComponent(dir("Dr. Stone"))
    }

    @Test
    fun underscoreRunsCollapseAndSurroundingWhitespaceGoes() {
        assertEquals("My_Anime", dir("  My   Anime  "))
    }

    // ------------------------------------------------------------------
    // Unicode (Python str-pattern parity)
    // ------------------------------------------------------------------

    @Test
    fun cjkTitleSurvivesWhole() {
        assertEquals("葬送のフリーレン", dir("葬送のフリーレン"))
        assertSafeComponent(dir("葬送のフリーレン"))
    }

    @Test
    fun accentedTitleSurvives() {
        assertEquals("Cœur_Greatest", dir("Cœur Greatest"))
    }

    @Test
    fun arabicTitleSurvivesWhole() {
        assertEquals("التفاحة", dir("التفاحة"))
        assertSafeComponent(dir("التفاحة"))
    }

    // ------------------------------------------------------------------
    // Directory-only hardening
    // ------------------------------------------------------------------

    @Test
    fun onlyRemovableTitle_fallsBackToSessionId() {
        // The session id keeps its hyphen (Python keep-set includes '-').
        assertEquals("sess-fixed", dir("!!! ???"))
        assertSafeComponent(dir("!!! ???"))
    }

    @Test
    fun dotAndDotDot_neverBecomeTheComponent() {
        assertEquals("sess-fixed", dir("."))
        assertEquals("sess-fixed", dir(".."))
    }

    @Test
    fun leadingAndTrailingDotsAreTrimmed() {
        // Leading dot would hide the folder from Android file explorers
        // (MediaStore ignores dot-directories); trailing dot/space is invalid
        // on Windows path semantics.
        assertEquals("hackSIGN", dir(".hack//SIGN"))
        assertEquals("Title", dir("Title..."))
    }

    @Test
    fun pathSeparatorsCanNeverSurvive() {
        assertSafeComponent(dir("a/b"))
        assertSafeComponent(dir("a\\b"))
        assertSafeComponent(dir("../../etc/passwd"))
        assertEquals("etcpasswd", dir("../../etc/passwd"))
    }

    @Test
    fun windowsReservedDeviceNamesGetGuarded() {
        assertEquals("_CON", dir("CON"))
        assertEquals("_con", dir("con"))
        assertEquals("_com1", dir("com1"))
        assertEquals("_LPT9", dir("LPT9"))
        assertEquals("_NUL.txt", dir("NUL.txt"))
        // Not reserved: multi-digit and embedded forms stay untouched.
        assertEquals("COM10", dir("COM10"))
        assertEquals("console_Room", dir("console Room"))
        assertSafeComponent(dir("CON"))
    }

    @Test
    fun titleWithEmbeddedControlCharactersBecomesSingleUnderscores() {
        // NUL is dropped by the sanitizer (not in the keep-set); tab/newline
        // survive sanitize as `\s` and are converted by the directory hardening.
        val name = dir("Bad\u0000Title\tHere\nNow")
        assertSafeComponent(name)
        assertEquals("BadTitle_Here_Now", name)
    }

    @Test
    fun outputIsDeterministic() {
        val title = "The World's Strongest Rearguard"
        assertEquals(dir(title), dir(title))
        assertEquals(dir(""), dir(""))
    }

    // ------------------------------------------------------------------
    // mediaStoreRelativePath shape
    // ------------------------------------------------------------------

    @Test
    fun mediaStoreRelativePathIsMoviesSlashTitleSlash() {
        assertEquals("Movies/Dr._Stone/", OutputPathNames.mediaStoreRelativePath("Dr. Stone", session))
        assertEquals(
            "Movies/Export_Test_Session/",
            OutputPathNames.mediaStoreRelativePath("Export Test Session", "sess-1"),
        )
    }

    @Test
    fun legacyFlatPathIsUnchanged() {
        assertEquals("Movies/PaheBatcher/", OutputPathNames.LEGACY_RELATIVE_PATH)
        assertEquals("Movies/", OutputPathNames.MOVIES_PREFIX)
    }

    @Test
    fun missingTitleUsesSessionFallbackChain() {
        // Null title (session row absent) → session id; blank title too.
        assertEquals("sess-fixed", OutputPathNames.directoryName(null, session))
        assertEquals("sess-fixed", OutputPathNames.directoryName("", session))
        // Even a session id that sanitizes to nothing yields a non-empty,
        // deterministic component.
        assertEquals(
            "PaheBatcher",
            OutputPathNames.directoryName("!!!", "///"),
        )
    }
}
