package com.pahebatcher.app.domain.model

/**
 * Theme mode selection (Phase 11, PHASE11_ARCHITECTURE_V2 §4/§6).
 *
 * Stored in the existing DataStore as `KEY_THEME_MODE` with the [storageKey] values
 * `system | dark | light`. The default when the key is absent is [DARK] (§6: fresh
 * install is dark with zero reads/writes required).
 */
enum class ThemeMode(val storageKey: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        /** Maps a persisted raw value to a [ThemeMode]; absent/unknown values fall back to [DARK] (§6). */
        fun fromStorageKey(raw: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == raw } ?: DARK
    }
}

/**
 * Immutable theme settings consumed by [com.pahebatcher.app.ui.theme.PaheBatcherTheme]
 * (PHASE11_ARCHITECTURE_V2 §4–§7).
 *
 * @property themeMode default [ThemeMode.DARK] when the preference key is absent (§6).
 * @property.dynamicColor default `false` (§7 — Dynamic color OFF by default).
 */
data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
) {
    companion object {
        /** Code defaults: dark + dynamic color off (§6/§7). */
        val DEFAULT = ThemeSettings()

        /**
         * Pure mapping from persisted raw preference values to [ThemeSettings].
         * Absent keys resolve to the §6/§7 code defaults — never to a second store.
         */
        fun from(themeModeRaw: String?, dynamicColorRaw: Boolean?): ThemeSettings =
            ThemeSettings(
                themeMode = ThemeMode.fromStorageKey(themeModeRaw),
                dynamicColor = dynamicColorRaw ?: false,
            )
    }
}
