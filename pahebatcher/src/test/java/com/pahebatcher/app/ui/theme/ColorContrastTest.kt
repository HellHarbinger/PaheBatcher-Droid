package com.pahebatcher.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE11_ARCHITECTURE_V2 §36-8 — programmatic WCAG 2.1 contrast validation over all
 * §5 mandated color pairs. Failure fails the test.
 *
 * Thresholds (§5.1 rule 2):
 *  - >= 4.5:1 for normal-text (container, onContainer) and (background, onBackground) pairs
 *  - >= 3:1 for large text / UI boundaries (outline against surfaces and container roles)
 *
 * Includes the §5.4 corrected values: dark outline #6672AC (>= 3:1 vs surface and every
 * surfaceContainer* role, min 3.14:1 vs surfaceContainerHighest) and dark inversePrimary
 * #6250D4 (>= 4.5:1 vs inverseSurface).
 */
class ColorContrastTest {

    // -----------------------------------------------------------------------
    // WCAG 2.1 relative luminance / contrast math
    // -----------------------------------------------------------------------

    private fun channelLuminance(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    // Compose Color stores floats in [0,1]; compute from rounded byte channels to
    // avoid float rounding drift in the contrast math.
    private fun luminanceOf(color: Color): Double {
        val r = Math.round(color.red * 255f).toInt()
        val g = Math.round(color.green * 255f).toInt()
        val b = Math.round(color.blue * 255f).toInt()
        return 0.2126 * channelLuminance(r) +
            0.7152 * channelLuminance(g) +
            0.0722 * channelLuminance(b)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val l1 = luminanceOf(foreground)
        val l2 = luminanceOf(background)
        val (lighter, darker) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertMinContrast(name: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrast(fg, bg)
        assertTrue(
            "$name contrast ${"%.2f".format(ratio)}:1 < required ${min}:1",
            ratio >= min - 0.005 // tolerance for float rounding of stated ratios
        )
    }

    // -----------------------------------------------------------------------
    // §5.2 dark scheme pairs
    // -----------------------------------------------------------------------

    @Test
    fun darkScheme_textPairs_meet45() {
        assertMinContrast("dark onPrimary/primary", DarkOnPrimary, DarkPrimary, 4.5)
        assertMinContrast(
            "dark onPrimaryContainer/primaryContainer",
            DarkOnPrimaryContainer, DarkPrimaryContainer, 4.5
        )
        assertMinContrast("dark onSecondary/secondary", DarkOnSecondary, DarkSecondary, 4.5)
        assertMinContrast(
            "dark onSecondaryContainer/secondaryContainer",
            DarkOnSecondaryContainer, DarkSecondaryContainer, 4.5
        )
        assertMinContrast("dark onTertiary/tertiary", DarkOnTertiary, DarkTertiary, 4.5)
        assertMinContrast(
            "dark onTertiaryContainer/tertiaryContainer",
            DarkOnTertiaryContainer, DarkTertiaryContainer, 4.5
        )
        assertMinContrast("dark onBackground/background", DarkOnBackground, DarkBackground, 4.5)
        assertMinContrast("dark onSurface/surface", DarkOnSurface, DarkSurface, 4.5)
        assertMinContrast(
            "dark onSurfaceVariant/surfaceVariant",
            DarkOnSurfaceVariant, DarkSurfaceVariant, 4.5
        )
        assertMinContrast(
            "dark inverseOnSurface/inverseSurface",
            DarkInverseOnSurface, DarkInverseSurface, 4.5
        )
        assertMinContrast("dark onError/error", DarkOnError, DarkError, 4.5)
        assertMinContrast(
            "dark onErrorContainer/errorContainer",
            DarkOnErrorContainer, DarkErrorContainer, 4.5
        )
    }

    @Test
    fun darkScheme_uiBoundaries_meet31() {
        // §5.4 corrected outline #6672AC: 4.38:1 vs surface #05070D (verified claim).
        assertMinContrast("dark outline/surface", DarkOutline, DarkSurface, 3.0)
        assertMinContrast("dark outline/background", DarkOutline, DarkBackground, 3.0)
        // >= 3:1 against every surfaceContainer* role where borders can appear
        // (min 3.14:1 vs surfaceContainerHighest — §5.4 verified claim).
        assertMinContrast(
            "dark outline/surfaceContainerLowest",
            DarkOutline, DarkSurfaceContainerLowest, 3.0
        )
        assertMinContrast("dark outline/surfaceContainerLow", DarkOutline, DarkSurfaceContainerLow, 3.0)
        assertMinContrast("dark outline/surfaceContainer", DarkOutline, DarkSurfaceContainer, 3.0)
        assertMinContrast(
            "dark outline/surfaceContainerHigh",
            DarkOutline, DarkSurfaceContainerHigh, 3.0
        )
        assertMinContrast(
            "dark outline/surfaceContainerHighest",
            DarkOutline, DarkSurfaceContainerHighest, 3.0
        )
        assertMinContrast("dark outline/surfaceVariant", DarkOutline, DarkSurfaceVariant, 3.0)
    }

    @Test
    fun darkScheme_inversePrimary_meets45() {
        // §5.4 corrected inversePrimary #6250D4: 4.92:1 vs inverseSurface (verified claim).
        assertMinContrast(
            "dark inversePrimary/inverseSurface",
            DarkInversePrimary, DarkInverseSurface, 4.5
        )
    }

    // -----------------------------------------------------------------------
    // §5.3 light scheme pairs
    // -----------------------------------------------------------------------

    @Test
    fun lightScheme_textPairs_meet45() {
        assertMinContrast("light onPrimary/primary", LightOnPrimary, LightPrimary, 4.5)
        assertMinContrast(
            "light onPrimaryContainer/primaryContainer",
            LightOnPrimaryContainer, LightPrimaryContainer, 4.5
        )
        assertMinContrast("light onSecondary/secondary", LightOnSecondary, LightSecondary, 4.5)
        assertMinContrast(
            "light onSecondaryContainer/secondaryContainer",
            LightOnSecondaryContainer, LightSecondaryContainer, 4.5
        )
        assertMinContrast("light onTertiary/tertiary", LightOnTertiary, LightTertiary, 4.5)
        assertMinContrast(
            "light onTertiaryContainer/tertiaryContainer",
            LightOnTertiaryContainer, LightTertiaryContainer, 4.5
        )
        assertMinContrast("light onBackground/background", LightOnBackground, LightBackground, 4.5)
        assertMinContrast("light onSurface/surface", LightOnSurface, LightSurface, 4.5)
        assertMinContrast(
            "light onSurfaceVariant/surfaceVariant",
            LightOnSurfaceVariant, LightSurfaceVariant, 4.5
        )
        assertMinContrast(
            "light inverseOnSurface/inverseSurface",
            LightInverseOnSurface, LightInverseSurface, 4.5
        )
        assertMinContrast("light inversePrimary/inverseSurface", LightInversePrimary, LightInverseSurface, 4.5)
        assertMinContrast("light onError/error", LightOnError, LightError, 4.5)
        assertMinContrast(
            "light onErrorContainer/errorContainer",
            LightOnErrorContainer, LightErrorContainer, 4.5
        )
    }

    @Test
    fun lightScheme_uiBoundaries_meet31() {
        // Light outline #767DA0: 3.77:1 vs surface #F6F7FC (verified claim).
        assertMinContrast("light outline/surface", LightOutline, LightSurface, 3.0)
        assertMinContrast("light outline/background", LightOutline, LightBackground, 3.0)
        // Borders against the light container ladder (white -> #E9ECF7).
        assertMinContrast(
            "light outline/surfaceContainerLowest",
            LightOutline, LightSurfaceContainerLowest, 3.0
        )
        assertMinContrast("light outline/surfaceContainerLow", LightOutline, LightSurfaceContainerLow, 3.0)
        assertMinContrast("light outline/surfaceContainer", LightOutline, LightSurfaceContainer, 3.0)
        assertMinContrast(
            "light outline/surfaceContainerHigh",
            LightOutline, LightSurfaceContainerHigh, 3.0
        )
        assertMinContrast(
            "light outline/surfaceContainerHighest",
            LightOutline, LightSurfaceContainerHighest, 3.0
        )
        assertMinContrast("light outline/surfaceVariant", LightOutline, LightSurfaceVariant, 3.0)
    }

    // -----------------------------------------------------------------------
    // §5.4 spot-verified corrected claims (exact stated ratios hold)
    // -----------------------------------------------------------------------

    @Test
    fun section54_correctedValues_holdStatedRatios() {
        // dark outline: stated 4.38:1 vs surface
        val outlineRatio = contrast(DarkOutline, DarkSurface)
        assertTrue("dark outline ratio $outlineRatio != ~4.38", outlineRatio in 4.30..4.46)
        // dark inversePrimary: stated 4.92:1 vs inverseSurface
        val inverseRatio = contrast(DarkInversePrimary, DarkInverseSurface)
        assertTrue("dark inversePrimary ratio $inverseRatio != ~4.92", inverseRatio in 4.85..4.99)
    }
}
