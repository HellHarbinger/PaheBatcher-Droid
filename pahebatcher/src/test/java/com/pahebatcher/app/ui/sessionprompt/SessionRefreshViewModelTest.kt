package com.pahebatcher.app.ui.sessionprompt

import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.SessionRefreshSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §6.10 (final-audit H1) — the root prompt ViewModel, under the
 * **one quiet attempt, then prompt** rule.
 *
 * Covered here:
 *
 *  - a valid session / a quiet attempt that SUCCEEDS shows no prompt at all,
 *    so a user whose background refresh keeps working is never interrupted;
 *  - a quiet attempt that FAILS raises the prompt, and every later requirement
 *    raises it again IMMEDIATELY instead of quietly retrying a method that just
 *    failed (no silent retry loop, no hidden persistent failure);
 *  - concurrent requests during one quiet attempt share a SINGLE refresh and
 *    can never produce a second prompt;
 *  - the refresh action runs the REAL force-refresh seam and HIDES the prompt
 *    only on a ready session;
 *  - a refused or throwing refresh keeps the prompt open with an honest,
 *    URL-free reason (SPEC §26 — a failed refresh is never swallowed);
 *  - a double tap starts exactly one refresh;
 *  - `Not now` hides the prompt, and a later signal raises it again;
 *  - the latch lives on this Activity-scoped ViewModel, so repeated signal
 *    delivery (the JVM proxy for recomposition) never resets it.
 *
 * No network, no WebView: `forceRefresh` is the injected seam (production
 * binds [com.pahebatcher.app.di.AppContainer.forceRefreshWebViewSession]
 * through [com.pahebatcher.app.di.AppViewModelFactory]).
 *
 * Device-only coverage NOT run here: real Activity recreation
 * (`SessionRefreshPromptDialogTest`) and a live 401/403 — neither is safe to
 * provoke on the production account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRefreshViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Share runTest's virtual clock with Dispatchers.Main (viewModelScope dispatcher). */
    private fun kotlinx.coroutines.test.TestScope.givenVirtualMain() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    }

    private fun readyInfo() = CloudflareSessionProvider.SessionInfo(
        state = CloudflareSessionProvider.SessionState.ESTABLISHED,
        userAgent = "TestUA",
        cloudflareCompleted = true,
        finalUrl = "",
        isReady = true,
        lastValidatedAt = 0L,
        cookieCount = 0,
        activeHost = "animepahe.local",
    )

    private fun failedInfo() = CloudflareSessionProvider.SessionInfo(
        state = CloudflareSessionProvider.SessionState.FAILED,
        userAgent = "TestUA",
        cloudflareCompleted = false,
        finalUrl = "",
        isReady = false,
        lastValidatedAt = 0L,
        cookieCount = 0,
        activeHost = "animepahe.local",
    )

    // -----------------------------------------------------------------------
    // 1–3: the quiet attempt decides whether the user is interrupted at all
    // -----------------------------------------------------------------------

    @Test
    fun `a quiet attempt that succeeds shows no prompt`() = runTest {
        givenVirtualMain()
        var calls = 0
        val viewModel = SessionRefreshViewModel {
            calls++
            readyInfo()
        }
        runCurrent()
        assertFalse("nothing to recover yet", viewModel.state.value.visible)

        SessionRefreshSignal.requestUserPrompt("Session not ready: FAILED")
        runCurrent()

        assertEquals("exactly one background attempt", 1, calls)
        assertFalse(
            "a successful background refresh must NOT interrupt the user",
            viewModel.state.value.visible,
        )
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `a quiet attempt that fails raises the prompt`() = runTest {
        givenVirtualMain()
        var calls = 0
        val viewModel = SessionRefreshViewModel {
            calls++
            failedInfo()
        }
        runCurrent()

        SessionRefreshSignal.requestUserPrompt("Session not ready: FAILED")
        runCurrent()

        assertEquals(1, calls)
        assertTrue(viewModel.state.value.visible)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `a later recovery after a failed quiet attempt prompts without trying again`() = runTest {
        givenVirtualMain()
        var calls = 0
        val viewModel = SessionRefreshViewModel {
            calls++
            failedInfo()
        }
        runCurrent()

        SessionRefreshSignal.requestUserPrompt("HTTP 403")
        runCurrent()
        assertEquals("the first attempt is the quiet one", 1, calls)
        assertTrue(viewModel.state.value.visible)

        // Required behaviour: the NEXT requirement surfaces the UI at once
        // instead of silently retrying a method that has already failed.
        SessionRefreshSignal.requestUserPrompt("HTTP 401")
        runCurrent()

        assertEquals(
            "no second silent attempt once one has failed",
            1,
            calls,
        )
        assertTrue("the prompt stays up for the next requirement", viewModel.state.value.visible)
    }

    @Test
    fun `repeated signal delivery never resets the first attempt latch`() = runTest {
        givenVirtualMain()
        var calls = 0
        val viewModel = SessionRefreshViewModel {
            calls++
            failedInfo()
        }
        runCurrent()

        SessionRefreshSignal.requestUserPrompt("HTTP 403")
        runCurrent()
        assertEquals(1, calls)

        // The state is read by every recomposition; reading it must not reset
        // anything, and neither must a burst of further signals.
        repeat(5) { viewModel.state.value; SessionRefreshSignal.requestUserPrompt("HTTP 403") }
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            "the Activity-scoped latch survives recomposition-style re-entry",
            1,
            calls,
        )
    }

    // -----------------------------------------------------------------------
    // 6: concurrency — one refresh for the whole burst, never two prompts
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent requests during one quiet attempt share a single refresh`() = runTest {
        givenVirtualMain()
        var calls = 0
        val viewModel = SessionRefreshViewModel {
            calls++
            delay(100)
            failedInfo()
        }
        runCurrent()

        SessionRefreshSignal.requestUserPrompt("HTTP 403")
        SessionRefreshSignal.requestUserPrompt("HTTP 403")
        SessionRefreshSignal.requestUserPrompt("Session not ready: FAILED")
        runCurrent()

        assertEquals("one attempt in flight", 1, calls)

        advanceUntilIdle()

        assertEquals("the burst must not start a second refresh", 1, calls)
        assertTrue(viewModel.state.value.visible)
        assertEquals(
            "the shared prompt state is a single boolean, never two dialogs",
            1,
            viewModel.state.value.let { if (it.visible) 1 else 0 },
        )
    }

    // -----------------------------------------------------------------------
    // 4–5: the user-triggered refresh
    // -----------------------------------------------------------------------

    @Test
    fun `a successful refresh hides the prompt and clears the latch`() = runTest {
        givenVirtualMain()
        var calls = 0
        val viewModel = SessionRefreshViewModel {
            calls++
            if (calls == 1) failedInfo() else readyInfo()
        }
        runCurrent()

        SessionRefreshSignal.requestUserPrompt("HTTP 403")
        runCurrent()
        assertTrue("the quiet attempt failed, so the prompt is up", viewModel.state.value.visible)
        assertEquals(1, calls)

        viewModel.refresh()
        runCurrent()

        assertEquals(SessionRefreshPromptState(), viewModel.state.value)
        assertEquals("one quiet attempt plus the user's", 2, calls)

        // The latch was cleared by the user's successful recovery, so a LATER
        // staleness earns its own quiet attempt instead of jumping to the dialog.
        SessionRefreshSignal.requestUserPrompt("HTTP 403")
        runCurrent()
        assertEquals(3, calls)
        assertFalse(
            "a quiet success after recovery still interrupts nobody",
            viewModel.state.value.visible,
        )
    }

    @Test
    fun `a refused refresh keeps the prompt open with an honest reason`() = runTest {
        givenVirtualMain()
        val viewModel = SessionRefreshViewModel { failedInfo() }
        runCurrent()
        SessionRefreshSignal.requestUserPrompt("Session not ready: FAILED")
        runCurrent()

        viewModel.refresh()
        runCurrent()

        val state = viewModel.state.value
        assertTrue("the prompt must stay open after a refused refresh", state.visible)
        assertFalse("the refreshing latch must be released — never an infinite spinner", state.refreshing)
        assertEquals("Refresh failed: FAILED", state.error)
    }

    @Test
    fun `a throwing refresh surfaces a URL-free reason`() = runTest {
        givenVirtualMain()
        val viewModel = SessionRefreshViewModel {
            throw IllegalStateException("bootstrap failed for https://animepahe.com/?token=abc")
        }
        runCurrent()
        SessionRefreshSignal.requestUserPrompt("Session not ready: FAILED")
        runCurrent()

        viewModel.refresh()
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.visible)
        assertFalse("the spinner must be released", state.refreshing)
        assertEquals(
            "AGENTS §10: a URL-bearing message must fall back to the type",
            "Refresh failed: IllegalStateException",
            state.error,
        )
    }

    @Test
    fun `a throwing quiet attempt still surfaces the prompt instead of hiding the failure`() = runTest {
        givenVirtualMain()
        val viewModel = SessionRefreshViewModel {
            throw IllegalStateException("bootstrap failed for https://animepahe.com/?token=abc")
        }
        runCurrent()

        SessionRefreshSignal.requestUserPrompt("Session not ready: FAILED")
        runCurrent()

        val state = viewModel.state.value
        assertTrue("an exception must never suppress the prompt", state.visible)
        assertNull("the prompt opens clean; the reason appears if the user's own attempt fails", state.error)
    }

    @Test
    fun `a double tap starts exactly one refresh`() = runTest {
        givenVirtualMain()
        var calls = 0
        val viewModel = SessionRefreshViewModel {
            calls++
            if (calls == 1) {
                failedInfo() // the quiet attempt fails fast and raises the prompt
            } else {
                delay(100)
                readyInfo()
            }
        }
        runCurrent()
        SessionRefreshSignal.requestUserPrompt("HTTP 401")
        runCurrent()
        assertEquals(1, calls)
        assertTrue(viewModel.state.value.visible)

        viewModel.refresh()
        runCurrent() // the first refresh enters the latch + the delay
        viewModel.refresh() // second tap while refreshing
        advanceUntilIdle()

        assertEquals(
            "one quiet attempt plus exactly ONE user refresh despite the double tap",
            2,
            calls,
        )
    }

    // -----------------------------------------------------------------------
    // Behaviour 5, negative direction: a failed user retry must NOT clear the
    // latch. Only a SUCCESSFUL recovery may, so a still-broken session never
    // silently restarts quiet attempts behind a prompt the user is already
    // looking at.
    // -----------------------------------------------------------------------

    @Test
    fun `a failed user refresh keeps the latch so the next signal never quietly retries`() = runTest {
        givenVirtualMain()
        var calls = 0
        val viewModel = SessionRefreshViewModel {
            calls++
            failedInfo()
        }
        runCurrent()

        SessionRefreshSignal.requestUserPrompt("HTTP 403")
        runCurrent()
        assertEquals("the one quiet attempt", 1, calls)
        assertTrue(viewModel.state.value.visible)

        // The user's own refresh also fails: the honest reason stays and the
        // spinner is released (SPEC §26 — a failed refresh is never swallowed).
        viewModel.refresh()
        runCurrent()
        assertEquals("quiet attempt + the user's", 2, calls)
        assertFalse(viewModel.state.value.refreshing)
        assertEquals("Refresh failed: FAILED", viewModel.state.value.error)

        // Nothing but a successful recovery may clear the latch, so this signal
        // must surface the prompt WITHOUT starting a third quiet attempt.
        SessionRefreshSignal.requestUserPrompt("HTTP 403")
        runCurrent()

        assertEquals(
            "a failed user retry must not clear the latch and quietly restart",
            2,
            calls,
        )
        assertTrue(viewModel.state.value.visible)
    }

    // -----------------------------------------------------------------------
    // Dismissal
    // -----------------------------------------------------------------------

    @Test
    fun `dismiss hides the prompt and a later signal raises it again`() = runTest {
        givenVirtualMain()
        val viewModel = SessionRefreshViewModel { failedInfo() }
        runCurrent()
        SessionRefreshSignal.requestUserPrompt("Session not ready: FAILED")
        runCurrent()
        assertTrue(viewModel.state.value.visible)

        viewModel.dismiss()
        runCurrent()
        assertFalse(viewModel.state.value.visible)

        // The latch is still set, so this surfaces immediately — a persistent
        // session failure is never hidden just because the user said "Not now".
        SessionRefreshSignal.requestUserPrompt("Session refresh failed: FAILED")
        runCurrent()
        assertTrue(viewModel.state.value.visible)
    }
}
