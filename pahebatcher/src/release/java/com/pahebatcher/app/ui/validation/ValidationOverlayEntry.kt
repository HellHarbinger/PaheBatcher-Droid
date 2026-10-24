package com.pahebatcher.app.ui.validation

import androidx.compose.runtime.Composable

/**
 * Release variant of the validation-overlay seam (A2.1 Decision 3 / NET-014).
 *
 * The validation screen and its three spike validators exist only in
 * `src/debug`, so release builds compile this no-op twin instead: MainActivity's
 * always-compiled setContent tree resolves without ever naming a spike class,
 * and the `BuildConfig.DEBUG` gate above the call makes this body unreachable
 * in shipped builds in any case. Nothing here is dead-but-shipped code — the
 * spike implementation itself is absent from the release artifact.
 */
@Suppress("UNUSED_PARAMETER")
internal object ValidationOverlayEntry {

    @Composable
    fun Content(onBack: () -> Unit) {
        // Intentionally empty — see KDoc.
    }
}
