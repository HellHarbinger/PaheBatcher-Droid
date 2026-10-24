package com.pahebatcher.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * PHASE11_ARCHITECTURE_V2 §5 — complete Material 3 color role mapping (F-09).
 *
 * Every role is set explicitly in both schemes; no M3 template (blue) defaults may leak
 * into the identity (§5.1 rule 1). On-colors are validated against WCAG 2.1 contrast by
 * the §36-8 programmatic test (`ColorContrastTest`): >= 4.5:1 for normal text pairs,
 * >= 3:1 for large text / UI boundaries (icons, progress, outlines against surfaces).
 *
 * §5.4 corrected values (verified): dark outline #6672AC (4.38:1 vs surface),
 * dark inversePrimary #6250D4 (4.92:1 vs inverseSurface).
 */

// ---------------------------------------------------------------------------
// Dark scheme (default) — outer-space palette (§5.2)
// ---------------------------------------------------------------------------

// Brand accent
val DarkPrimary = Color(0xFF8B7CF6) // nebula violet
val DarkOnPrimary = Color(0xFF150F2E) // deep space ink — 5.54:1
val DarkPrimaryContainer = Color(0xFF2A2266) // deep indigo container
val DarkOnPrimaryContainer = Color(0xFFE9E4FF) // pale violet — 11.19:1

// Supporting accent
val DarkSecondary = Color(0xFF4FD6E0) // restrained cyan
val DarkOnSecondary = Color(0xFF04252A) // cyan-black — 9.23:1
val DarkSecondaryContainer = Color(0xFF103840) // teal container
val DarkOnSecondaryContainer = Color(0xFFC8F4FA) // pale cyan

// Sparse highlight (retry/attention)
val DarkTertiary = Color(0xFFFFB65C) // restrained amber
val DarkOnTertiary = Color(0xFF3A2405) // brown-black
val DarkTertiaryContainer = Color(0xFF4A3210) // amber-brown container
val DarkOnTertiaryContainer = Color(0xFFFFE6C4) // pale amber

// Root background / surface
val DarkBackground = Color(0xFF05070D) // void
val DarkOnBackground = Color(0xFFE8ECF8) // starlight
val DarkSurface = Color(0xFF05070D) // void
val DarkOnSurface = Color(0xFFE8ECF8) // starlight

// Raised panel
val DarkSurfaceVariant = Color(0xFF121A31)
val DarkOnSurfaceVariant = Color(0xFF97A0BE) // muted starlight — 6.64:1

// Surface container ladder (orbital depth)
val DarkSurfaceContainerLowest = Color(0xFF03050A)
val DarkSurfaceContainerLow = Color(0xFF0B1020)
val DarkSurfaceContainer = Color(0xFF0F1528)
val DarkSurfaceContainerHigh = Color(0xFF161F3A)
val DarkSurfaceContainerHighest = Color(0xFF1D284A)

// Borders / hairlines
val DarkOutline = Color(0xFF6672AC) // corrected §5.4 — 4.38:1 vs surface, 3.14:1 vs highest container
val DarkOutlineVariant = Color(0xFF1A2138) // decorative hairline

// Inverse (snackbar)
val DarkInverseSurface = Color(0xFFE8ECF8)
val DarkInverseOnSurface = Color(0xFF0B1020)
val DarkInversePrimary = Color(0xFF6250D4) // corrected §5.4 — 4.92:1 vs inverseSurface

// Explicit standard Material error reds — NOT brand-derived (§5.1 rule 4)
val DarkError = Color(0xFFFF6E6E)
val DarkOnError = Color(0xFF410000)
val DarkErrorContainer = Color(0xFF5C1515)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// ---------------------------------------------------------------------------
// Light scheme (moonlight) (§5.3)
// ---------------------------------------------------------------------------

val LightPrimary = Color(0xFF5646D6) // deep violet — white text 6.47:1
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE6E1FF)
val LightOnPrimaryContainer = Color(0xFF1E1666)

val LightSecondary = Color(0xFF0F6E7A) // deep cyan
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFCCEEF3)
val LightOnSecondaryContainer = Color(0xFF06333A)

val LightTertiary = Color(0xFF8A5200) // deep amber
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFE3BE)
val LightOnTertiaryContainer = Color(0xFF2C1800)

val LightBackground = Color(0xFFF6F7FC) // paper
val LightOnBackground = Color(0xFF111527) // ink
val LightSurface = Color(0xFFF6F7FC)
val LightOnSurface = Color(0xFF111527)

val LightSurfaceVariant = Color(0xFFE9ECF7)
val LightOnSurfaceVariant = Color(0xFF464E6B) // slate

// White -> light panel ladder (§5.3)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF7F8FD)
val LightSurfaceContainer = Color(0xFFF1F3FA)
val LightSurfaceContainerHigh = Color(0xFFECEEF8)
val LightSurfaceContainerHighest = Color(0xFFE9ECF7)

val LightOutline = Color(0xFF767DA0) // 3.77:1 vs surface
val LightOutlineVariant = Color(0xFFC6CADD)

val LightInverseSurface = Color(0xFF111527)
val LightInverseOnSurface = Color(0xFFF6F7FC)
val LightInversePrimary = Color(0xFFB8AEFF) // 9.08:1 vs inverseSurface

// Standard M3 error — not brand-derived (§5.1 rule 4)
val LightError = Color(0xFFB3261E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFF9DEDC)
val LightOnErrorContainer = Color(0xFF410E0B)
