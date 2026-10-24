package com.pahebatcher.app.ui.sessionprompt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.di.AppContainer
import com.pahebatcher.app.di.AppViewModelFactory
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.WebViewRequestBridge
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Final-audit H1 (SPEC §6.10) — the ROOT prompt surface on the real
 * [MainActivity]:
 *
 *  - a [SessionRefreshSignal] request raised by the data layer's detection
 *    sites shows the `Refresh session` dialog above whatever route is open;
 *  - `Not now` dismisses it (the ViewModel seam logic itself is pinned by the
 *    JVM `SessionRefreshViewModelTest`, including the refresh outcomes);
 *  - [AppViewModelFactory] really can build the prompt ViewModel from the
 *    production [com.pahebatcher.app.di.AppContainer] — the wiring the dialog
 *    depends on, without touching the network (the refresh action itself runs
 *    only on a user tap).
 *
 * The quiet-attempt OUTCOME is controlled by a test-double session provider so
 * the prompt is shown deterministically on a networked device too: SPEC §6.10's
 * "one quiet attempt, then prompt" latch means the first signal triggers a real
 * background refresh, and a SUCCESSFUL one deliberately raises no prompt. Only
 * that refresh outcome is stubbed — the signal bus, the ViewModel, the AlertDialog
 * surface and the `Not now` dismissal are all real production code. WHEN to prompt
 * is pinned separately by the JVM `SessionRefreshViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class SessionRefreshPromptDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var container: AppContainer? = null
    private var originalSessionDelegate: Any? = null

    /**
     * Make the root prompt's quiet background refresh FAIL deterministically.
     *
     * SPEC §6.10's "one quiet attempt, then prompt" latch means the FIRST
     * [com.pahebatcher.app.domain.session.SessionRefreshSignal] request triggers a
     * real, networked `AppContainer.forceRefreshWebViewSession()`. On a device that
     * can reach AnimePahe that attempt SUCCEEDS, and a successful quiet attempt
     * deliberately raises NO prompt — so a signal-driven test of the dialog surface
     * would never see it. Swapping the session provider for a failing double (the
     * same injectable-seam idea the JVM test uses) turns the scenario into the one
     * the dialog exists for: recovery fails, so the user-facing prompt is shown.
     * Restored in [restoreSessionProvider].
     */
    @Before
    fun installDeterministicQuietFailure() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as PaheBatcherApplication
        val container = app.appContainer
        this.container = container
        val field = AppContainer::class.java.getDeclaredField("cloudflareSession\$delegate")
        field.isAccessible = true
        originalSessionDelegate = field.get(container)
        field.set(container, lazyOf(FailingSessionProvider()))
    }

    /** Put the real session provider back — the container is process-wide. */
    @After
    fun restoreSessionProvider() {
        val container = container ?: return
        val field = AppContainer::class.java.getDeclaredField("cloudflareSession\$delegate")
        field.isAccessible = true
        field.set(container, originalSessionDelegate)
        this.container = null
        originalSessionDelegate = null
    }

    @Test
    fun aSignalRequestShowsTheRootPromptAndNotNowDismissesIt() {
        composeRule.waitForIdle()

        // Pre-condition: no prompt on a healthy startup.
        composeRule.onNodeWithText("Refresh now").assertDoesNotExist()

        // Raise the signal until the root ViewModel's collector is subscribed
        // (the bus has no replay by design, so a single early emission could be
        // raised before the Activity finished composing).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            com.pahebatcher.app.domain.session.SessionRefreshSignal
                .requestUserPrompt("Session not ready: FAILED")
            composeRule.onAllNodesWithText("Refresh now").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Refresh session").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").assertIsDisplayed()
        composeRule.onNodeWithText(
            "no longer valid",
            substring = true,
        ).assertIsDisplayed()

        // Dismissal hides the prompt entirely.
        composeRule.onNodeWithText("Not now").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Refresh session").assertDoesNotExist()
        composeRule.onNodeWithText("Not now").assertDoesNotExist()
    }

    @Test
    fun theProductionFactoryBuildsThePromptViewModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PaheBatcherApplication
        val factory = AppViewModelFactory(app.appContainer)

        val viewModel = factory.create(SessionRefreshViewModel::class.java)

        assertNotNull("the factory must know the prompt ViewModel", viewModel)
        // Fresh state: the dialog exists only after a real signal request.
        assertTrue(!viewModel.state.value.visible)
    }

    /**
     * A [CloudflareSessionProvider] whose bootstrap always FAILS — the session a
     * device that cannot complete the AnimePahe/Cloudflare handshake ends up in.
     * Only [establishSession] matters here (it decides the quiet attempt's outcome);
     * the rest are inert because this test never fetches a page.
     */
    private class FailingSessionProvider : CloudflareSessionProvider {
        override fun currentState(): CloudflareSessionProvider.SessionState =
            CloudflareSessionProvider.SessionState.FAILED

        override fun getSessionInfo(): CloudflareSessionProvider.SessionInfo = failedInfo()

        override suspend fun establishSession(
            forceRefresh: Boolean,
        ): CloudflareSessionProvider.SessionInfo = failedInfo()

        override suspend fun isSessionValid(): Boolean = false

        override fun invalidateSession() = Unit

        override suspend fun fetchPage(
            url: String,
            waitForCloudflare: Boolean,
            timeoutMs: Long,
            contentType: WebViewRequestBridge.ContentType,
            extraHeaders: Map<String, String>,
        ): CloudflareSessionProvider.PageResult = failedPage()

        override fun releaseSession() = Unit

        private fun failedInfo() = CloudflareSessionProvider.SessionInfo(
            state = CloudflareSessionProvider.SessionState.FAILED,
            userAgent = "test-double",
            cloudflareCompleted = false,
            finalUrl = "",
            isReady = false,
            lastValidatedAt = 0L,
            cookieCount = 0,
            activeHost = "animepahe.local",
        )

        private fun failedPage() = CloudflareSessionProvider.PageResult(
            success = false,
            html = null,
            json = null,
            finalUrl = "",
            statusCode = 0,
            error = "test double: no page fetch in this scenario",
            cloudflareChallengeDetected = false,
            cloudflareChallengeCompleted = false,
            diagnostics = emptyList(),
        )
    }
}
