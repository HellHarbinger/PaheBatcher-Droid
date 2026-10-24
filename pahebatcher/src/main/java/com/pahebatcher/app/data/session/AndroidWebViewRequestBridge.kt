package com.pahebatcher.app.data.session

import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogUrlSanitizer
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.session.SessionRefreshSignal
import com.pahebatcher.app.domain.session.WebViewRequestBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android implementation of WebViewRequestBridge.
 *
 * Uses AndroidCloudflareSession to perform all Cloudflare-protected AnimePahe
 * operations through the WebView session.
 *
 * Architecture:
 * - All Cloudflare-protected requests go through this bridge
 * - The bridge ensures a valid session before each request
 * - Handles session refresh on expiry
 * - Isolates WebView-specific code from domain layer
 *
 * Thread safety:
 * - Session management is handled by the underlying CloudflareSessionProvider
 * - This class delegates to the provider for all WebView operations
 * - Page operations are mutually exclusive through `pageLock` (Phase 12D), because
 *   the underlying transport is ONE shared WebView whose capture client is swapped
 *   per request
 *
 * Retry behavior (A2.1 Decisions 1 + 2):
 * - Every failed page fetch retries, bounded by config.maxRetries (default: 1)
 * - The session is invalidated + re-established ONLY when the real main-frame
 *   status refused it (401/403 — the cf_clearance-declined answer); transient
 *   failures (timeout, 5xx, empty body, transport exceptions) retry against the
 *   SAME still-valid session instead of wiping the store-wide cookie jar
 * - The bridge is the SINGLE recovery owner for fetches it runs: main-frame
 *   status reports are suppressed for the duration, so a refused status can
 *   never start a second, parallel recovery through the quiet-refresh path
 *   (AQR-014); its own "Session not ready"/"Session refresh failed" prompts
 *   remain — those are cases the bridge cannot self-recover
 * - Does NOT retry on success
 */
class AndroidWebViewRequestBridge(
    private val sessionProvider: CloudflareSessionProvider,
    /**
     * Mutual exclusion over the ONE shared WebView.
     *
     * `AndroidCloudflareSession.performPageFetch` swaps the capture WebViewClient
     * on a single shared WebView and then restores it, so two page operations that
     * overlap cross-capture content and interleave the client restoration.
     * `StreamResolverImpl` already serialised resolutions against each other for
     * exactly that reason (11.5E-7); the HTTP entry point did not, because the
     * details screen used to be the only caller.
     *
     * Phase 12D adds a second, independent caller — the background
     * `WatchlistCheckWorker` running the periodic `wl check` — so EVERY page
     * operation now takes this lock. Production passes one shared instance to both
     * this bridge and `StreamResolverImpl` (`AppContainer.webViewPageLock`), which
     * makes "one page operation at a time" hold across HTTP and stream resolution.
     *
     * The default keeps single-caller constructions (tests, legacy call sites) on
     * their own lock — a composition that must not interleave has to pass the
     * shared one.
     */
    private val pageLock: Mutex = Mutex()
) : WebViewRequestBridge {

    companion object {
        private const val TAG = "WebViewBridge"
    }

    override suspend fun fetch(config: WebViewRequestBridge.RequestConfig): WebViewRequestBridge.BridgeResult =
        pageLock.withLock { fetchExclusively(config) }

    /**
     * Body of [fetch], running with the WebView page lock already held.
     *
     * Nothing below this point may take [pageLock] again (kotlinx Mutex is not
     * re-entrant): session establishment is guarded by the session's own
     * `establishMutex`, never by this lock.
     *
     * A2.1 Decision 1 (AQR-014): the whole body runs under
     * [SessionRefreshSignal.withStatusReportsSuppressed] — the retry loop below
     * is the SINGLE recovery owner for every fetch this bridge starts, so one
     * refused main-frame status can never start a second, parallel recovery
     * run through the quiet-refresh path.
     */
    private suspend fun fetchExclusively(
        config: WebViewRequestBridge.RequestConfig
    ): WebViewRequestBridge.BridgeResult = SessionRefreshSignal.withStatusReportsSuppressed {
        val diagnostics = mutableListOf<String>()
        var sessionRefreshed = false

        // Ensure we have a valid session
        var sessionInfo = sessionProvider.ensureSession()
        if (!sessionInfo.isReady) {
            // SPEC §6.10 (final-audit H1): the automatic establishment could not
            // produce a usable session — PROMPT the user instead of failing
            // silently. The reason is a session-state name (URL-free, AGENTS §10).
            SessionRefreshSignal.requestUserPrompt("Session not ready: ${sessionInfo.state}")
            diagnostics.add("Session not ready: ${sessionInfo.state}")
            return WebViewRequestBridge.BridgeResult(
                success = false,
                content = null,
                finalUrl = "",
                error = "Session not ready: ${sessionInfo.state}",
                cloudflareHandled = false,
                sessionRefreshed = false,
                diagnostics = diagnostics
            )
        }

        // Attempt the request with retry. A2.1 Decision 2 (narrow invalidation):
        // ONLY a refused real main-frame status (401/403 — the cf_clearance-declined
        // answer; evidence in SessionRefreshSignal.reportMainFrameStatus) marks the
        // session as invalid and earns the store-wide wipe + re-establishment.
        // Every other failure retries against the SAME still-valid session.
        var lastError: String? = null
        var lastFetchRefusedSession = false
        for (attempt in 0..config.maxRetries) {
            if (attempt > 0) {
                diagnostics.add("Retry attempt $attempt")
                if (lastFetchRefusedSession) {
                    sessionProvider.invalidateSession()
                    sessionInfo = sessionProvider.establishSession(forceRefresh = true)
                    sessionRefreshed = true

                    if (!sessionInfo.isReady) {
                        // SPEC §6.10 (final-audit H1): the bridge's OWN automatic
                        // refresh already ran and still failed — this is exactly the
                        // "instead of silently failing" case that must prompt.
                        SessionRefreshSignal.requestUserPrompt(
                            "Session refresh failed: ${sessionInfo.state}",
                        )
                        diagnostics.add("Session refresh failed: ${sessionInfo.state}")
                        return WebViewRequestBridge.BridgeResult(
                            success = false,
                            content = null,
                            finalUrl = "",
                            error = "Session refresh failed: ${sessionInfo.state}",
                            cloudflareHandled = true,
                            sessionRefreshed = true,
                            diagnostics = diagnostics
                        )
                    }
                } else {
                    diagnostics.add(
                        "Transient failure — retrying without disturbing the session",
                    )
                }
            }

            try {
                val pageResult = sessionProvider.fetchPage(
                    url = config.url,
                    waitForCloudflare = config.waitForCloudflare,
                    timeoutMs = config.timeoutMs,
                    contentType = config.contentType
                )

                if (pageResult.success) {
                    return WebViewRequestBridge.BridgeResult(
                        success = true,
                        content = pageResult.html ?: pageResult.json,
                        finalUrl = pageResult.finalUrl,
                        error = null,
                        cloudflareHandled = pageResult.cloudflareChallengeCompleted,
                        sessionRefreshed = sessionRefreshed,
                        diagnostics = diagnostics + pageResult.diagnostics
                    )
                }

                lastError = pageResult.error
                // A2.1 Decision 2: the REAL main-frame status is the only
                // evidence of an invalid session. 401/403 on a finished load =
                // cf_clearance refused; timeouts (status 0), 5xx outages, empty
                // bodies and transport faults keep their valid session.
                lastFetchRefusedSession =
                    pageResult.statusCode == 401 || pageResult.statusCode == 403
                diagnostics.addAll(pageResult.diagnostics)

                // H3: retry on ANY failed page fetch (the loop decides whether
                // the session is re-established above); non-session failures
                // such as "Page load timed out", 5xx, or an empty body simply
                // get another attempt. We only skip retry if this is the last
                // attempt (loop will exit).
                diagnostics.add("Page fetch failed: ${pageResult.error}, will retry if attempts remain")
                continue

            } catch (e: CancellationException) {
                // NET-001: a cancelled scan/watchlist/download must STOP here, not be
                // converted into a normal failure that drives the retry loop below
                // (which would invalidate + re-establish the ONE shared WebView session
                // and raise a spurious user prompt for work the caller abandoned).
                AppLog.d(TAG, "Request cancelled by caller")
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Request failed", e)
                lastError = e.message
                // A2.1 Decision 2: a transport fault is no evidence of an
                // invalid session — retry without the store-wide wipe.
                lastFetchRefusedSession = false
                // AQR-025: diagnostics follow the AGENTS §10 URL-free rule like
                // every other transport diagnostic (BridgeResult.diagnostics has
                // no production sink today; sanitized defensively).
                diagnostics.add("Exception: ${LogUrlSanitizer.message(e.message)}")
            }
        }

        return WebViewRequestBridge.BridgeResult(
            success = false,
            content = null,
            finalUrl = "",
            error = lastError ?: "Unknown error after ${config.maxRetries + 1} attempts",
            cloudflareHandled = false,
            sessionRefreshed = sessionRefreshed,
            diagnostics = diagnostics
        )
    }

    override fun getSessionState(): CloudflareSessionProvider.SessionState {
        return sessionProvider.currentState()
    }

    override fun release() {
        sessionProvider.releaseSession()
    }
}
