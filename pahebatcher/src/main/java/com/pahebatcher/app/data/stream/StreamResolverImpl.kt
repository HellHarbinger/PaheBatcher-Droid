package com.pahebatcher.app.data.stream

import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogUrlSanitizer
import com.pahebatcher.app.data.remote.parser.PlayPageParser
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.domain.stream.ResolutionFailureCode
import com.pahebatcher.app.domain.stream.StreamResolutionException
import com.pahebatcher.app.domain.stream.StreamResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android implementation of StreamResolver.
 *
 * Orchestrates the complete stream resolution flow:
 * 1. Fetch play page via WebView (Cloudflare-protected)
 * 2. Parse resolution buttons (audio preserved as the source's own open-set string)
 * 3. Select the EXACT requested audio at the requested quality — no
 *    substitution, no URL grab, no forced label; a missing audio fails
 *    explicitly (SPEC §22 "Audio selection (per episode)")
 * 4. Resolve Kwik URL to M3U8
 * 5. Return StreamInfo with M3U8 URL and headers
 *
 * Ported from Python kwik.py extract_stream(), with the approved removals
 * listed at [QualitySelector].
 */
class StreamResolverImpl(
    private val sessionProvider: CloudflareSessionProvider,
    private val kwikResolver: KwikResolver,
    /**
     * Single-flight over the shared WebView (11.5E-7, AGENTS §11; shared since
     * Phase 12D / DECISIONS 91).
     *
     * `AndroidCloudflareSession.fetchPage` swaps the capture WebViewClient on
     * ONE shared WebView, so two concurrent operations could cross-capture pages
     * or interleave the client restoration. 11.5E-7 serialised resolutions
     * against each other (details screen + Downloads-screen retry); Phase 12D
     * passes the SAME lock the HTTP bridge uses (`AppContainer.webViewPageLock`),
     * so a resolution can no longer overlap a search/scan request either — the
     * background `WatchlistCheckWorker` makes such overlap systematic instead of
     * incidental. Waiters are FIFO and a cancelled waiter propagates its
     * CancellationException untouched.
     *
     * The default keeps single-caller constructions (tests) on their own lock.
     */
    private val pageLock: Mutex = Mutex()
) : StreamResolver {

    companion object {
        private const val TAG = "StreamResolver"
    }

    /**
     * Single-flight over [doResolve] — see the [pageLock] property for the full
     * reasoning (11.5E-7, extended by Phase 12D).
     */
    override suspend fun resolve(
        playUrl: String,
        quality: Int,
        audio: String
    ): StreamInfo = pageLock.withLock { doResolve(playUrl, quality, audio) }

    private suspend fun doResolve(
        playUrl: String,
        quality: Int,
        audio: String
    ): StreamInfo {
        AppLog.d(TAG, "Resolving stream: ${LogUrlSanitizer.url(playUrl)} (quality=$quality, audio=$audio)")

        // Step 1: Fetch play page via WebView
        val pageResult = sessionProvider.fetchPage(
            url = playUrl,
            waitForCloudflare = true,
            timeoutMs = 30_000L
        )

        if (!pageResult.success || pageResult.html == null) {
            throw StreamResolutionException(
                "Failed to fetch episode page via WebView session.\n" +
                "  Check network connectivity and Cloudflare session status.\n" +
                // NET-009: pageResult.error can quote the failing URL and this
                // message is logged at release-emitted ERROR AND persisted as
                // errorMessage — sanitize before it leaves the WebView layer.
                "  Error: ${LogUrlSanitizer.diagnostic(pageResult.error ?: "unknown")}",
                // Slice C: ONE code for both causes — the page fetch cannot tell
                // "no network" from "Cloudflare challenged us" (the message says
                // both), so inventing two causes would be a guess.
                code = ResolutionFailureCode.PAGE_FETCH_FAILED,
            )
        }

        val html = pageResult.html

        // Step 2: Parse resolution buttons
        val entries = PlayPageParser.parseResolutionButtons(html)

        // Step 3: EXACT audio + quality selection. Both page shapes (menu and
        // menu-less) share the one approved rule (QualitySelector): filter to
        // the requested audio as an open-set string, de-duplicate per
        // resolution, best quality ≤ requested. A missing audio match fails
        // HERE and explicitly — the reference's silent substitution
        // (`filtered = entries`, kwik.py:230-232), the "grab the first Kwik
        // URL anywhere on the page" rescue (kwik.py:254-259) and the forced
        // `jpn` label are all deliberately absent (approved §7).
        val selectedEntry = QualitySelector.select(entries, quality, audio)
            // Slice C: the two causes are split by ORIGIN, not by reading the
            // sentence back later. `entries.isEmpty()` means the page offered
            // nothing at all (no audio was ever rejected); a non-empty page with
            // no exact match is the ONLY evidence-backed "requested audio is
            // absent" this app has. Both messages are byte-identical to before.
            ?: if (entries.isEmpty()) {
                throw StreamResolutionException(
                    "No Kwik link found on episode page",
                    code = ResolutionFailureCode.NO_KWIK_LINK,
                )
            } else {
                throw StreamResolutionException(
                    AudioSelector.unavailableMessage(audio),
                    code = ResolutionFailureCode.AUDIO_UNAVAILABLE,
                )
            }

        // Step 4: Resolve Kwik URL to M3U8
        val resolution = kwikResolver.resolve(selectedEntry.kwikUrl)
            ?: throw StreamResolutionException(
                // The message travels to DownloadRepository's release-emitted
                // ERROR line AND the persisted errorMessage — keep the host +
                // path that says WHICH Kwik page failed, drop any query the
                // link carried (AGENTS §10, DECISIONS 136).
                "Could not resolve Kwik URL: ${LogUrlSanitizer.url(selectedEntry.kwikUrl)}",
                code = ResolutionFailureCode.KWIK_RESOLUTION_FAILED,
            )

        // Step 5: Extract title
        val title = PlayPageParser.extractTitle(html)

        // Step 6: The resolved audio is the SOURCE's own value for the entry
        // that was selected by exact match — never re-derived, never defaulted.
        val effectiveAudio = selectedEntry.audio
        val fansub = selectedEntry.fansub

        // the FULL playlist URL must never reach
        // Logcat or the on-screen diagnostics Console — an expiring CDN playlist
        // URL carries its authorisation in the query string (AGENTS §10:
        // "Never persist expiring stream URLs indefinitely" / never log session
        // tokens). Host + path only; query and fragment are dropped.
        // NET-015: the canonical sanitizer (LogUrlSanitizerTest-owned) replaces
        // this file's private re-implementation of the same rule.
        AppLog.d(
            TAG,
            "Stream resolved: quality=${selectedEntry.resolution}, " +
                "audio=$effectiveAudio, hostPath=${LogUrlSanitizer.url(resolution.m3u8Url)}",
        )

        return StreamInfo(
            url = resolution.m3u8Url,
            cookies = emptyList(),  // Cookies come from WebView session (Decision 32)
            userAgent = sessionProvider.getSessionInfo().userAgent,
            // 11.5E-4 parity fix: Python `_resolve_kwik()` sets `referer=url` — the
            // KWIK PAGE that produced the playlist, not the AnimePahe play page.
            // The Kwik CDN answers 403 to playlist/segment requests referred by the
            // play page (measured), so this value is what makes HLS requests legal.
            referer = resolution.sourceUrl,
            title = title,
            audio = effectiveAudio,
            fansub = fansub
        )
    }
}
