package com.pahebatcher.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AQR-010 — the MediaSession controller gate (Option 1: own package + trusted).
 *
 * These pin the ALLOWED and REJECTED controller sets that
 * [PaheMediaSessionService] applies at `onGetSession` and `onConnect`.
 * The predicate is deliberately package-name-exact and null-conservative:
 * nothing but "our own package" or a platform-trusted controller passes.
 */
class MediaSessionControllerPolicyTest {

    private val own = "com.pahebatcher.app"

    // ── Allowed controllers ──────────────────────────────────────────────

    @Test
    fun `our own package is allowed even when the platform does not mark it trusted`() {
        assertTrue(
            "the app's own media3 controllers must always connect",
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = own,
                controllerPackage = own,
                trusted = false,
            ),
        )
    }

    @Test
    fun `a platform-trusted foreign controller is allowed`() {
        // SystemUI (notification/lock screen/quick settings), system_server
        // (media-key dispatch), MEDIA_CONTENT_CONTROL / notification-listener
        // holders — all arrive with isTrusted = true and a foreign package.
        assertTrue(
            "system surfaces rely on the trusted arm",
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = own,
                controllerPackage = "com.android.systemui",
                trusted = true,
            ),
        )
    }

    @Test
    fun `a trusted controller passes even when its package is unknown`() {
        // Trust verdict is the stronger signal; an unknown package with a
        // positive platform trust decision is not someone we can refuse.
        assertTrue(
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = own,
                controllerPackage = null,
                trusted = true,
            ),
        )
    }

    // ── Rejected controllers ─────────────────────────────────────────────

    @Test
    fun `an untrusted foreign controller is rejected`() {
        // The attack path AQR-010 closed: any third-party app connecting a
        // MediaController and reading MediaMetadata (HLS URLs) from it.
        assertFalse(
            "foreign untrusted apps must never reach the session",
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = own,
                controllerPackage = "com.attacker.app",
                trusted = false,
            ),
        )
    }

    @Test
    fun `a package-name prefix of ours is rejected, not matched`() {
        // Exact match only: "com.pahebatcher.app" must never accept
        // "com.pahebatcher.app.evil" — a prefix rule would defeat the gate.
        assertFalse(
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = own,
                controllerPackage = "$own.evil",
                trusted = false,
            ),
        )
    }

    @Test
    fun `a foreign package that merely contains ours as a substring is rejected`() {
        assertFalse(
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = own,
                controllerPackage = "evil.$own.attacker",
                trusted = false,
            ),
        )
    }

    @Test
    fun `an unknown controller package is rejected`() {
        assertFalse(
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = own,
                controllerPackage = null,
                trusted = false,
            ),
        )
    }

    @Test
    fun `a missing own package never becomes a wildcard`() {
        // Defensive: if our own package cannot be determined, NO untrusted
        // controller may pass — null must not compare equal to null.
        assertFalse(
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = null,
                controllerPackage = null,
                trusted = false,
            ),
        )
        assertFalse(
            MediaSessionControllerPolicy.isControllerAllowed(
                ownPackage = "",
                controllerPackage = "",
                trusted = false,
            ),
        )
    }
}
