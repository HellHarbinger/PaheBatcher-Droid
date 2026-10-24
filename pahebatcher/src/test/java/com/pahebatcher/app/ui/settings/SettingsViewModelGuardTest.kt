package com.pahebatcher.app.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * AQR-041 — the [projectSafely] projection guard.
 *
 * Every guarded Settings refresh point (notification permission projection,
 * latch write + re-projection, output-location validation) routes through
 * this helper, so these three cases are the contract the ViewModel relies on:
 * success passes through, a real failure degrades to the PREVIOUS projection
 * instead of crashing `viewModelScope`, and cancellation keeps propagating
 * (NET-001 contract — a teardown is not a projection fault).
 */
class SettingsViewModelGuardTest {

    @Test
    fun `a successful projection returns its value untouched`() = runBlocking {
        val result = projectSafely(
            fallback = "previous",
            tag = "GuardTest",
            what = "projection",
        ) { "fresh" }

        assertEquals("fresh", result)
    }

    @Test
    fun `a failing projection returns the fallback instead of throwing`() = runBlocking {
        // The AQR-041 crash: DataStore IOException escaping an unguarded
        // viewModelScope launch on Settings open/resume. With the guard the
        // caller keeps its previous state and no exception escapes.
        val result = projectSafely(
            fallback = "previous",
            tag = "GuardTest",
            what = "projection",
        ) { throw java.io.IOException("prefs file unreadable") }

        assertEquals("previous", result)
    }

    @Test
    fun `cancellation propagates instead of being converted to a fallback`() {
        try {
            runBlocking {
                projectSafely(
                    fallback = "previous",
                    tag = "GuardTest",
                    what = "projection",
                ) { throw CancellationException("scope torn down") }
                fail("cancellation must propagate out of projectSafely")
            }
            fail("cancellation must propagate out of the calling coroutine")
        } catch (e: CancellationException) {
            // expected: the guard never swallows teardown
        }
    }

    @Test
    fun `a non-Exception error is not swallowed`() = runBlocking {
        // Only Exception-class failures degrade to the fallback; programming
        // errors (Errors) must keep surfacing.
        try {
            projectSafely<Unit>(
                fallback = Unit,
                tag = "GuardTest",
                what = "projection",
            ) { throw OutOfMemoryError("synthetic") }
            fail("Errors must not be converted into a silent fallback")
        } catch (e: OutOfMemoryError) {
            // expected
        }
    }
}
