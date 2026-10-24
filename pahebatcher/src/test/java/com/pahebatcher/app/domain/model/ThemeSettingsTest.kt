package com.pahebatcher.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * PHASE11_ARCHITECTURE_V2 §36-11 (pure mapping part):
 *  - ThemeMode default DARK on missing keys
 *  - dynamic color default OFF
 *  - storage round-trip (persisted raw value -> ThemeSettings -> persisted raw value)
 *
 * The DataStore round-trip persistence itself is covered by instrumentation
 * (`ThemePersistenceTest`) against the real preferences DataStore.
 */
class ThemeSettingsTest {

    @Test
    fun missingKeys_defaultToDarkAndDynamicColorOff() {
        val settings = ThemeSettings.from(themeModeRaw = null, dynamicColorRaw = null)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertFalse(settings.dynamicColor)
    }

    @Test
    fun codeDefaults_matchSection6And7() {
        assertEquals(ThemeMode.DARK, ThemeSettings.DEFAULT.themeMode)
        assertFalse(ThemeSettings.DEFAULT.dynamicColor)
    }

    @Test
    fun unknownThemeModeRaw_fallsBackToDark() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorageKey("blue"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorageKey(""))
    }

    @Test
    fun allThemeModes_roundTripThroughStorageKeys() {
        ThemeMode.entries.forEach { mode ->
            val restored = ThemeSettings.from(
                themeModeRaw = mode.storageKey,
                dynamicColorRaw = true,
            )
            assertEquals(mode, restored.themeMode)
            assertEquals(mode.storageKey, restored.themeMode.storageKey)
        }
        assertEquals("system", ThemeMode.SYSTEM.storageKey)
        assertEquals("dark", ThemeMode.DARK.storageKey)
        assertEquals("light", ThemeMode.LIGHT.storageKey)
    }

    @Test
    fun dynamicColor_roundTripsBothValues() {
        assertTrue(ThemeSettings.from("dark", true).dynamicColor)
        assertFalse(ThemeSettings.from("dark", false).dynamicColor)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
