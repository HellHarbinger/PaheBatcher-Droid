package com.pahebatcher.app.domain.session

import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.channels.BufferOverflow
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SPEC §6 items 9–10 (final-audit H1): "Detect expired/invalid Cloudflare
 * cookies. **Prompt the user to refresh the WebView session instead of
 * silently failing.**"
 *
 * The detection sites live where the session actually fails:
 *
 *  - [com.pahebatcher.app.data.session.AndroidWebViewRequestBridge] — the
 *    session is not ready (before a fetch, or after its automatic
 *    re-establishment already failed);
 *  - [com.pahebatcher.app.data.session.AndroidCloudflareSession.fetchPage] —
 *    the session is not ready, or the REAL main-frame status is 401/403
 *    (the classic expired/invalid `cf_clearance` answers; a Cloudflare
 *    challenge that COMPLETES reloads to 2xx, so its 403 never reaches the
 *    result site);
 *  - any other caller that observes one of those conditions.
 *
 * A2.1 Decision 1 (AQR-014): a bridge fetch owns its own recovery (the
 * retry loop invalidates + force re-establishes on a refused 401/403), so
 * while [com.pahebatcher.app.data.session.AndroidWebViewRequestBridge] runs,
 * [withStatusReportsSuppressed] silences [reportMainFrameStatus] and the SAME
 * failure can no longer start a second, parallel recovery run through the
 * quiet-refresh path. [requestUserPrompt] is deliberately NOT suppressed: the
 * bridge's two non-recoverable prompts ("Session not ready", "Session refresh
 * failed") must still reach the user.
 *
 * Those sites are deep in the data layer and must never talk to the UI
 * directly (AGENTS §4): they raise THIS signal instead, and the single
 * root-level prompt surface (MainActivity → SessionRefreshViewModel) decides
 * what the user sees and when it is dismissed.
 *
 * Semantics:
 *
 *  - **No replay**: a request emitted while no UI is collecting (process
 *    backgrounded, prompt already dismissed earlier) is dropped — a prompt is
 *    only meaningful while someone can act on it, and the NEXT failing
 *    operation raises the signal again.
 *  - **Bounded, latest-wins**: prompt requests carry no payload beyond a
 *    URL-free reason string (AGENTS §10), so a burst collapses to the newest.
 *  - Reasons are enum-state names / HTTP codes / fixed strings — never a URL,
 *    cookie, or token; `requestUserPrompt` logs the reason verbatim and the
 *    reason therefore must already be sanitized at the call site.
 *
 * Test doubles live in tests only; this object is a plain process-wide bus
 * (the same composition style as [AppLog]), never a source of fake state.
 */
object SessionRefreshSignal {

    private const val TAG = "SessionRefreshSignal"

    private val _requests = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Prompt requests raised by the detection sites; observed by the root UI. */
    val requests: SharedFlow<String> = _requests.asSharedFlow()

    /**
     * A2.1 Decision 1 (AQR-014): suppression depth for [reportMainFrameStatus],
     * held for exactly one bridge fetch. Every page operation shares the ONE
     * `pageLock` (Phase 12D), so this window covers precisely bridge-origin
     * fetches; direct provider callers (KwikResolver, audio probe, scan) never
     * run inside it. [com.pahebatcher.app.data.session.AndroidWebViewRequestBridge]
     * is the only legitimate caller of [withStatusReportsSuppressed].
     *
     * PRECONDITION (AQR-058): [withStatusReportsSuppressed] may only be entered
     * while holding the AppContainer `webViewPageLock` — the lock is what makes
     * the counter correct, because every page operation serialises on it
     * (bridge, `StreamResolverImpl`, `AudioOptionRepository`, `KwikResolver`).
     * A caller that fetched OUTSIDE the lock could have its genuine 401/403
     * swallowed for the duration of an unrelated bridge fetch — a lost user
     * prompt with no log. The helper stays process-global and convention-gated
     * on purpose (no scope-token redesign in the A2.1 freeze).
     */
    @PublishedApi
    internal val statusReportSuppression = AtomicInteger(0)

    /**
     * Run [block] with main-frame status reports silenced — the bridge's own
     * retry loop is the single recovery owner inside this window (A2.1
     * Decision 1). Always balanced via `finally`, including on cancellation.
     */
    inline fun <T> withStatusReportsSuppressed(block: () -> T): T {
        statusReportSuppression.incrementAndGet()
        try {
            return block()
        } finally {
            statusReportSuppression.decrementAndGet()
        }
    }

    /**
     * Raise the SPEC §6.10 prompt request.
     *
     * @param reason short, URL-free reason (session state name, `HTTP 401`,
     *   or a fixed label) — logged verbatim, so call sites must never pass a
     *   URL, cookie, or token (AGENTS §10).
     */
    fun requestUserPrompt(reason: String) {
        AppLog.w(TAG, "Session refresh prompt requested: $reason")
        _requests.tryEmit(reason)
    }

    /**
     * SPEC §6.9 ("detect expired/invalid Cloudflare cookies") from a REAL
     * main-frame status: 401/403 on a finished page load means the session
     * credentials were refused, so prompt (item 10); every other status is not
     * a session signal and must stay silent (a 404 page or a 500 outage is not
     * an expired session).
     */
    fun reportMainFrameStatus(statusCode: Int) {
        if (statusReportSuppression.get() > 0) {
            // A2.1 Decision 1: the bridge owns recovery for this failure — its
            // retry loop (or the "Session refresh failed" prompt) reports it.
            return
        }
        if (statusCode == 401 || statusCode == 403) {
            requestUserPrompt("HTTP $statusCode")
        }
    }
}
