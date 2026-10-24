package com.pahebatcher.app.ui.validation

import androidx.compose.runtime.Composable

/**
 * Debug variant of the validation-overlay seam (A2.1 Decision 3 / NET-014).
 *
 * This source set is the ONLY home of the Phase-5 validation spikes
 * ([ValidationScreen] plus `data/remote/validation`'s three validators), so
 * their code — including the mixed-content/SSL-bypass probe surface — is no
 * longer compiled into release APKs. MainActivity references THIS object
 * (present in every variant); release ships the no-op twin in `src/release`,
 * and the runtime `BuildConfig.DEBUG` gate remains above it as well.
 */
internal object ValidationOverlayEntry {

    @Composable
    fun Content(onBack: () -> Unit) {
        ValidationScreen(onBack = onBack)
    }
}
