package com.pahebatcher.app.domain.stream

import com.pahebatcher.app.domain.model.StreamInfo

/**
 * Domain interface for stream resolution.
 *
 * Resolves an AnimePahe play page URL to a StreamInfo containing
 * the M3U8 URL and required headers for HLS playback/download.
 *
 * Ported from Python kwik.py extract_stream() behavior.
 */
interface StreamResolver {

    /**
     * Resolve a play page URL to stream information.
     *
     * This operation:
     * 1. Fetches the play page HTML via the WebView session
     * 2. Parses resolution buttons from the page
     * 3. Selects the best resolution matching the requested quality
     * 4. Selects the audio track (SUB/DUB) matching the preference
     * 5. Resolves the Kwik URL to extract the M3U8 URL
     * 6. Returns StreamInfo with M3U8 URL and headers
     *
     * @param playUrl The AnimePahe play page URL
     * @param quality Requested quality in pixels (e.g., 1080, 720, 360)
     * @param audio Audio preference: "jpn" for SUB, "eng" for DUB
     * @return StreamInfo with M3U8 URL and headers, or null on failure
     * @throws StreamResolutionException if resolution fails
     */
    suspend fun resolve(
        playUrl: String,
        quality: Int = 1080,
        audio: String = "jpn"
    ): StreamInfo
}

/**
 * Slice C — the ORIGIN of a stream-resolution failure.
 *
 * Added because `StreamResolutionException` carried only a message, so a
 * consumer had to match on sentence text to learn what happened — the exact
 * anti-pattern Phase 14 removed from the download stage with
 * `DownloadErrorClass`. This code is decided at the throw site, by origin, and
 * travels with the exception alongside an UNCHANGED message.
 *
 * ## The message must stay byte-identical
 *
 * `StreamResolutionRetry.PERMANENT_ERROR` / `PERMANENT_ERROR_NO_UUID` and
 * `StreamRetryPolicy` decide permanent-vs-transient by matching that message
 * ("No Kwik link", "No anime UUID"), and those strings are pinned by tests.
 * Changing a message here would silently break the permanent-error rule, so
 * this code is **strictly additive**: the message text is untouched.
 *
 * The values mirror the three real throw sites in `StreamResolverImpl`; they
 * do not attempt a finer split than the code can honestly make (for example a
 * page fetch cannot distinguish "no network" from "Cloudflare challenged us",
 * so both are [PAGE_FETCH_FAILED] rather than two invented causes).
 */
enum class ResolutionFailureCode {
    /** The play page could not be fetched (network OR session/Cloudflare — not separable). */
    PAGE_FETCH_FAILED,

    /** The page parsed to zero resolution entries. */
    NO_KWIK_LINK,

    /**
     * The page offered entries but none matched the requested audio exactly —
     * the ONLY site with genuine evidence that the requested audio is absent.
     */
    AUDIO_UNAVAILABLE,

    /** A Kwik page was found but yielded no M3U8. */
    KWIK_RESOLUTION_FAILED,

    /** A failure that did not originate at a site that assigns a code. */
    UNKNOWN,
}

/**
 * Exception thrown when stream resolution fails.
 *
 * Slice C: [code] is a NEW optional parameter defaulting to
 * [ResolutionFailureCode.UNKNOWN], so every pre-existing construction compiles
 * unchanged and reports honestly that it carries no origin. Message and cause
 * semantics are untouched.
 */
class StreamResolutionException(
    message: String,
    cause: Throwable? = null,
    val code: ResolutionFailureCode = ResolutionFailureCode.UNKNOWN,
) : Exception(message, cause)
