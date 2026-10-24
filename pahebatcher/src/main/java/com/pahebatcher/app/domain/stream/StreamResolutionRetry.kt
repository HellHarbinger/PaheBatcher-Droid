package com.pahebatcher.app.domain.stream

import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Resolution retry shared by the batch loop and the watchlist check.
 *
 * Port of the reference resolver retry budget (`downloader.py:233-275`):
 *
 * * `max_attempts = auto_retry + 1` attempts (default 2 + 1 = 3);
 * * backoff `1.5 * 2**attempt` seconds between attempts (`downloader.py:275`);
 *   the reference uses `2 * 2**attempt` only in its *timeout* branch
 *   (`downloader.py:260`);
 * * the permanent condition is NOT retried — the reference breaks on
 *   `"No Kwik link" in msg or "No anime UUID" in msg` (`downloader.py:263`).
 *   Both markers exist on Android too: [PERMANENT_ERROR] comes from
 *   `StreamResolverImpl` ("No Kwik link found on episode page") and
 *   [PERMANENT_ERROR_NO_UUID] from `AnimePaheParser` ("No anime UUID in URL…"),
 *   so a parse that cannot succeed stops immediately instead of burning
 *   `auto_retry + 1` attempts;
 * * a cancellation is never swallowed — the caller owns the coroutine.
 *
 * **Documented difference:** the reference wraps each attempt in
 * `asyncio.wait_for(..., timeout=120.0)` (`downloader.py:237-243`) and retries a
 * timeout. Android has no per-attempt resolution timeout of its own — an attempt
 * ends when the underlying request fails (the platform's HTTP/WebView timeouts)
 * or the coroutine is cancelled - so there is no 120 s budget to reproduce, and
 * none is emulated here.
 *
 * This is used ONLY where the reference has an explicit retry loop (batch and
 * watchlist resolution). The single-episode path keeps its existing
 * try-once-and-report behavior - this object does
 * not change it.
 */
object StreamResolutionRetry {

    /** Marker of a resolver failure the reference never retries. */
    const val PERMANENT_ERROR = "No Kwik link"

    /** Second permanent marker (`downloader.py:263`) — `AnimePaheParser`'s no-UUID parse error. */
    const val PERMANENT_ERROR_NO_UUID = "No anime UUID"

    /** `1.5 * 2**attempt` — Python's non-timeout resolver backoff base. */
    const val BASE_DELAY_MS = 1500L

    /**
     * Resolve [episode]'s stream, retrying transient failures.
     *
     * @param maxAttempts total attempts (use `auto_retry + 1`, Python's rule).
     * @throws CancellationException untouched.
     * @throws Exception the last failure (or a permanent one) — the caller
     *   records it against THIS episode only; siblings are unaffected.
     */
    suspend fun resolveWithRetry(
        resolver: StreamResolver,
        episode: EpisodeInfo,
        quality: Int,
        maxAttempts: Int,
    ): StreamInfo {
        var attempt = 0
        while (true) {
            try {
                return resolver.resolve(
                    playUrl = episode.playUrl,
                    quality = quality,
                    audio = episode.audio,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                val permanent = message.contains(PERMANENT_ERROR) ||
                    message.contains(PERMANENT_ERROR_NO_UUID)
                if (permanent || attempt >= maxAttempts - 1) {
                    throw e
                }
                delay(BASE_DELAY_MS * (1L shl attempt))
                attempt++
            }
        }
    }
}
