package com.pahebatcher.app.domain.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final-audit H1 (SPEC §6 items 9–10): the domain session-refresh signal.
 *
 * Pins the DETECTION RULE and the delivery semantics:
 *
 *  - a real main-frame status of 401/403 (expired/invalid `cf_clearance`)
 *    raises the prompt; every other status stays silent (a 404 page or a 500
 *    outage is not an expired session);
 *  - the reason travels URL-free (AGENTS §10);
 *  - no replay: a request raised while nothing observes the signal is dropped
 *    — a prompt is only meaningful while a surface can show it, and the next
 *    failing operation raises again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRefreshSignalTest {

    @Test
    fun `only 401 and 403 main-frame statuses raise the prompt`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        SessionRefreshSignal.reportMainFrameStatus(401)
        SessionRefreshSignal.reportMainFrameStatus(403)
        runCurrent()

        assertEquals(listOf("HTTP 401", "HTTP 403"), received)
        collector.cancel()
    }

    @Test
    fun `non-session statuses never raise the prompt`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        listOf(0, 200, 204, 301, 404, 429, 500, 503).forEach {
            SessionRefreshSignal.reportMainFrameStatus(it)
        }
        runCurrent()

        assertTrue("no prompt for non-session statuses, got: $received", received.isEmpty())
        collector.cancel()
    }

    @Test
    fun `a direct request carries its reason through`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        SessionRefreshSignal.requestUserPrompt("Session not ready: FAILED")
        runCurrent()

        assertEquals(listOf("Session not ready: FAILED"), received)
        collector.cancel()
    }

    @Test
    fun `requests carry no replay - raised while unobserved they are dropped`() = runTest {
        // Raised BEFORE any collector exists (app backgrounded, prompt already
        // dismissed): subscribing afterwards must NOT resurrect it.
        SessionRefreshSignal.requestUserPrompt("Session refresh failed: FAILED")
        advanceUntilIdle()

        val late = withTimeoutOrNull(500) { SessionRefreshSignal.requests.first() }
        assertNull("a stale prompt must not be replayed to a late subscriber", late)
    }

    // -----------------------------------------------------------------------
    // A2.1 final review (AQR-059): suppression-counter balance
    // -----------------------------------------------------------------------

    @Test
    fun `reports inside a suppression window stay silent and lifting it restores prompting`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        SessionRefreshSignal.withStatusReportsSuppressed {
            SessionRefreshSignal.reportMainFrameStatus(401)
            SessionRefreshSignal.reportMainFrameStatus(403)
        }
        runCurrent()
        assertTrue("suppressed reports must stay silent, got: $received", received.isEmpty())

        SessionRefreshSignal.reportMainFrameStatus(401)
        runCurrent()
        assertEquals(listOf("HTTP 401"), received)
        collector.cancel()
    }

    @Test
    fun `an exception thrown inside the suppression window still restores prompting`() = runTest {
        // The counter is balanced in `finally`; a lost finally would silence
        // every future 401/403 prompt BY DESIGN (silence is the failure mode),
        // so the throwing path needs its own pin.
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        val boom = runCatching {
            SessionRefreshSignal.withStatusReportsSuppressed<String> {
                throw IllegalStateException("bridge fetch exploded")
            }
        }.exceptionOrNull()
        assertTrue("the block's exception must propagate, got: $boom", boom is IllegalStateException)

        SessionRefreshSignal.reportMainFrameStatus(403)
        runCurrent()
        assertEquals("reporting must be live again after the throw", listOf("HTTP 403"), received)
        collector.cancel()
    }

    @Test
    fun `nested suppression windows lift only after the outermost exit`() = runTest {
        val received = mutableListOf<String>()
        val collector = launch { SessionRefreshSignal.requests.collect { received.add(it) } }
        runCurrent()

        SessionRefreshSignal.withStatusReportsSuppressed {
            SessionRefreshSignal.withStatusReportsSuppressed {
                SessionRefreshSignal.reportMainFrameStatus(401)
            }
            // The inner exit must NOT lift the window: the outer bridge fetch
            // is still in flight.
            SessionRefreshSignal.reportMainFrameStatus(401)
        }
        runCurrent()
        assertTrue("still suppressed after the inner block, got: $received", received.isEmpty())

        SessionRefreshSignal.reportMainFrameStatus(401)
        runCurrent()
        assertEquals("prompting resumes only after the outermost exit", listOf("HTTP 401"), received)
        collector.cancel()
    }
}
