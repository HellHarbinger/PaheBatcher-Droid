package com.pahebatcher.app.domain.download

import com.pahebatcher.app.data.hls.DownloadErrorClass
import com.pahebatcher.app.domain.stream.ResolutionFailureCode

/**
 * Slice C — the user-facing failure category, decided **at the construction
 * site by ORIGIN, never by message text**.
 *
 * This is the same principle [DownloadErrorClass] already encodes for the
 * segment-download stage, extended so the categories a user is shown can be
 * attributed honestly to the episode and audio that produced them. It exists
 * because the app previously knew *that* an episode failed and often *why*,
 * but the answer was discarded before it could reach the user: the batch loop
 * recorded only a count (`AnimeDetailsUiState.batchFailed`), and a Worker's
 * typed `errorClass` died with the run that produced it.
 *
 * ## What this enum is NOT
 *
 * It is deliberately NOT a message-text matcher. `AudioSelector.unavailableMessage`
 * produces the same sentence for two genuinely different causes:
 *
 *  * a play page that exists but offers no exact match for the requested audio —
 *    real evidence, produced at `StreamResolverImpl` where `entries.nonEmpty()`;
 *  * an episode NUMBER that is absent from the listing entirely, where the
 *    resolver is **never called**.
 *
 * Only the first may become [AUDIO_UNAVAILABLE]. Classifying from the sentence
 * would mis-report the second, so the category travels on a typed code
 * ([com.pahebatcher.app.domain.stream.ResolutionFailureCode]) and the message is
 * never consulted.
 *
 * ## Value set is bounded by what can be rendered
 *
 * Every constant has a [label] a screen or notification can show, except
 * [UNKNOWN] whose label is `null` because inventing a reason would be exactly
 * the dishonesty this slice removes. Cancellation and the reconciler's
 * "file missing" demotion are NOT here: they have no rendering site today, and
 * unused values would be speculative machinery.
 */
enum class FailureCategory {
    /** The play page offered no exact match for the requested audio (resolver evidence). */
    AUDIO_UNAVAILABLE,

    /** The episode NUMBER does not exist in this listing — the resolver was never consulted. */
    EPISODE_NOT_IN_LISTING,

    /** The play page could not be fetched, parsed, or its Kwik link resolved. */
    STREAM_RESOLUTION_FAILED,

    /** A segment download failed for a transport reason. */
    SEGMENT_DOWNLOAD_FAILED,

    /** The resolved stream links expired before the run could consume them. */
    STREAM_EXPIRED,

    /** The server had no content to serve for the request. */
    NO_CONTENT,

    /** The device ran out of storage. */
    DISK_FULL,

    /** The run was interrupted by a recoverable in-run condition. */
    IN_RUN_RETRYABLE,

    /** FFmpeg muxing of the downloaded segments failed. */
    ASSEMBLY_FAILED,

    /**
     * The failure carries no typed origin (an exception escaping the Worker, or
     * a result whose class could not be read). Shown as no category at all —
     * never as a guess.
     */
    UNKNOWN,
    ;

    /**
     * The short user-facing reason, or `null` when this category must not be
     * labelled. [UNKNOWN] returns `null` on purpose.
     */
    val label: String?
        get() = when (this) {
            AUDIO_UNAVAILABLE -> "Requested audio not available"
            EPISODE_NOT_IN_LISTING -> "Episode not in this listing"
            STREAM_RESOLUTION_FAILED -> "Stream resolution failed"
            SEGMENT_DOWNLOAD_FAILED -> "Segment download failed"
            STREAM_EXPIRED -> "Stream link expired"
            NO_CONTENT -> "No downloadable content"
            DISK_FULL -> "Not enough storage space"
            IN_RUN_RETRYABLE -> "Download interrupted"
            ASSEMBLY_FAILED -> "Assembly failed"
            UNKNOWN -> null
        }

    companion object {

        /**
         * The typed download-stage class this category is derived from. Every
         * branch is total; [DownloadErrorClass.NONE] cannot reach a failure
         * branch, and maps to [UNKNOWN] rather than asserting a reason the code
         * cannot support.
         */
        fun fromDownloadErrorClass(errorClass: DownloadErrorClass): FailureCategory =
            when (errorClass) {
                DownloadErrorClass.TRANSPORT -> SEGMENT_DOWNLOAD_FAILED
                DownloadErrorClass.EXPIRED -> STREAM_EXPIRED
                DownloadErrorClass.NO_CONTENT -> NO_CONTENT
                DownloadErrorClass.DISK_FULL -> DISK_FULL
                DownloadErrorClass.ASSEMBLY -> ASSEMBLY_FAILED
                DownloadErrorClass.IN_RUN_RETRYABLE -> IN_RUN_RETRYABLE
                DownloadErrorClass.NONE -> UNKNOWN
            }

        /**
         * The resolution-stage origin this category is derived from. Only the
         * evidence-backed [ResolutionFailureCode.AUDIO_UNAVAILABLE] becomes
         * [AUDIO_UNAVAILABLE]; every other resolution origin is honestly
         * [STREAM_RESOLUTION_FAILED], including [ResolutionFailureCode.UNKNOWN]
         * (a failure with no known origin is still a resolution failure — that
         * much is observable — but never a more specific claim).
         */
        fun fromResolutionCode(code: ResolutionFailureCode): FailureCategory =
            when (code) {
                ResolutionFailureCode.AUDIO_UNAVAILABLE -> AUDIO_UNAVAILABLE
                ResolutionFailureCode.PAGE_FETCH_FAILED,
                ResolutionFailureCode.NO_KWIK_LINK,
                ResolutionFailureCode.KWIK_RESOLUTION_FAILED,
                ResolutionFailureCode.UNKNOWN,
                -> STREAM_RESOLUTION_FAILED
            }
    }
}
