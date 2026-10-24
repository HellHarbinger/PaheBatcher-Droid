package com.pahebatcher.app.ui.player

import com.pahebatcher.app.domain.stream.StreamResolutionRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17 §16/§20-F — the bounded, user-driven stream retry policy.
 *
 * Two properties are load-bearing:
 *
 * * **nothing retries by itself** — reading the budget never advances it, so the
 *   only way `attempts` moves is an explicit user-driven `recordAttempt()`;
 * * **a permanent resolution failure stops** — the reference's "No Kwik link" /
 *   "No anime UUID" markers are reported as-is instead of burning the budget.
 *
 * Plus the two behaviours that make a retry usable rather than hostile: the
 * budget is capped (never an unbounded loop) and the viewer's position survives
 * a source swap.
 */
class StreamRetryPolicyTest {

    @Test
    fun `the budget is bounded and never exceeded`() {
        val policy = StreamRetryState()

        repeat(StreamRetryState.DEFAULT_MAX_ATTEMPTS) {
            assertTrue("attempt $it should fit", policy.attemptAllowed())
            policy.recordAttempt()
        }

        assertFalse("budget must be spent", policy.attemptAllowed())
        assertFalse("the gate the player consults must refuse", policy.retryAllowed(null))

        // Further taps can never push it past the cap.
        policy.recordAttempt()
        policy.recordAttempt()
        assertEquals(StreamRetryState.DEFAULT_MAX_ATTEMPTS, policy.attempts)
    }

    @Test
    fun `reading the policy never advances it on its own`() {
        val policy = StreamRetryState()

        repeat(50) {
            assertTrue(policy.attemptAllowed())
            policy.retryAllowed(null)
            policy.isPermanentFailure(null)
        }

        assertEquals("no automatic retry loop may exist", 0, policy.attempts)
    }

    @Test
    fun `a successful re-resolution restores the full budget`() {
        val policy = StreamRetryState()

        repeat(StreamRetryState.DEFAULT_MAX_ATTEMPTS) { policy.recordAttempt() }
        assertFalse(policy.attemptAllowed())

        policy.reset()

        assertEquals(0, policy.attempts)
        assertTrue(policy.attemptAllowed())
    }

    @Test
    fun `the reference's permanent resolution failures are never retried`() {
        val policy = StreamRetryState()

        assertTrue(
            policy.isPermanentFailure("No Kwik link found on episode page"),
        )
        assertTrue(
            policy.isPermanentFailure("No anime UUID in URL. Expected: https://animepahe.pw/anime/<uuid>. Got: x"),
        )
        assertFalse(
            "a transient failure must stay inside the bounded budget",
            policy.isPermanentFailure("Could not resolve Kwik URL: https://kwik.example/x"),
        )
        assertFalse(policy.isPermanentFailure(null))
        assertFalse(policy.isPermanentFailure(""))

        // A permanent verdict blocks even though the budget is untouched.
        assertFalse(policy.retryAllowed("No Kwik link found on episode page"))
        assertEquals(0, policy.attempts)
    }

    @Test
    fun `the permanent markers are the ones the reference resolver really emits`() {
        // Guards against the policy silently going stale if the resolver's copy
        // ever changes: these are the two strings the production resolver throws.
        assertEquals("No Kwik link", StreamResolutionRetry.PERMANENT_ERROR)
        assertEquals("No anime UUID", StreamResolutionRetry.PERMANENT_ERROR_NO_UUID)
        assertTrue(
            "No Kwik link found on episode page".contains(StreamResolutionRetry.PERMANENT_ERROR),
        )
    }

    @Test
    fun `the viewer's position is retained across a source swap and cleared on a fresh entry`() {
        val policy = StreamRetryState()

        policy.retainPosition(47_500L)
        assertEquals(47_500L, policy.retainedPositionMs)

        // A retry keeps it…
        policy.retainPosition(48_000L)
        assertEquals(48_000L, policy.retainedPositionMs)

        // …but a fresh stream entry forgets it, so a later load starts clean.
        policy.clearRetainedPosition()
        assertEquals(0L, policy.retainedPositionMs)
    }

    @Test
    fun `a negative retained position is coerced to the start of the media`() {
        val policy = StreamRetryState()

        policy.retainPosition(-1L)

        assertEquals(0L, policy.retainedPositionMs)
    }

    @Test
    fun `reset does not forget the retained position`() {
        // Budget and position are independent: resetting the budget after a
        // successful re-resolution must not drop the viewer back to zero.
        val policy = StreamRetryState()
        policy.retainPosition(12_000L)
        repeat(StreamRetryState.DEFAULT_MAX_ATTEMPTS) { policy.recordAttempt() }

        policy.reset()

        assertEquals(0, policy.attempts)
        assertEquals(12_000L, policy.retainedPositionMs)
    }

    @Test
    fun `a tighter budget is honoured exactly as configured`() {
        val policy = StreamRetryState(maxAttempts = 1)

        assertTrue(policy.attemptAllowed())
        policy.recordAttempt()
        assertFalse(policy.attemptAllowed())
        assertEquals(1, policy.attempts)
    }
}
