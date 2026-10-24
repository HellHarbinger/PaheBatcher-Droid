package com.pahebatcher.app.data.hls

import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Global-per-host bound on simultaneous in-flight HLS requests.
 *
 * ## Why this exists (Python parity)
 *
 * Python builds exactly ONE aiohttp session for the whole process and hands it a
 * connector capped per host:
 *
 * ```python
 * # http.py:63-65
 * connector = aiohttp.TCPConnector(limit=0, limit_per_host=self._hls_workers, ...)
 * ```
 *
 * Because that session is shared, `limit_per_host` (default 24) is a **process-wide**
 * ceiling on concurrent requests to one host, no matter how many episode workers run -
 * all of them funnel through it.
 *
 * Android had no equivalent: `DownloadWorkerFactory` built a fresh `OkHttpHlsClient`
 * per Worker, so with `max_parallel = N` and `hls_workers = H` the app could put
 * `N * H` requests in flight against a single CDN host (up to 144 at the settings
 * maximum). The bound the reference enforces simply did not exist.
 *
 * ## Why not OkHttp's `Dispatcher`
 *
 * OkHttp's `maxRequestsPerHost` only gates calls submitted with `enqueue()`. The HLS
 * client issues blocking `Call.execute()` calls, which bypass the dispatcher's limits
 * entirely, so tuning that knob would silently have done nothing. A coroutine
 * [Semaphore] keyed by host is the correct Android-native mechanism here.
 *
 * ## Concurrency contract
 *
 * - One [Semaphore] per distinct host, sized by [limitProvider] the first time that
 *   host is seen (A2.1 Decision 6 — see below), so unrelated hosts never contend
 *   with each other - Python's `limit_per_host` is per host, not global.
 * - The permit covers the whole request INCLUDING the body read, matching aiohttp,
 *   where a connection stays busy until the response is fully consumed.
 * - `acquire()` is called OUTSIDE the `try`. A cancelled acquire holds no permit, so
 *   there is nothing to release and `finally` is never reached. That is what makes the
 *   limiter safe around cancellable network work without leaking slots.
 * - A failing [block] releases its permit via `finally`, so one bad segment can never
 *   starve the host.
 *
 * ## Live ceiling (A2.1 Decision 6)
 *
 * Python reads `hls_workers` from its config once per session; Android's persisted
 * setting can CHANGE while the process lives, so the ceiling is consulted through
 * [limitProvider] every time a host is seen for the first time:
 *
 * - a host first seen AFTER a settings change gets the CURRENT value;
 * - an already-tracked host keeps the capacity its semaphore was created with —
 *   resizing a live semaphore would strand permits held by in-flight requests, so
 *   the defined settings-change semantics are "applies per host from its first
 *   sighting after the change";
 * - the value is floored at 1 (no misconfiguration can freeze a host behind a
 *   zero-permit semaphore) and a FAILED read falls back to the shipped default
 *   (24) rather than to something unsafe.
 *
 * The map holds one small object per distinct CDN host actually seen in playlists, so
 * its size is bounded by the service, not by user input.
 */
class HlsHostConcurrencyLimiter(
    private val limitProvider: suspend () -> Int,
) {

    companion object {
        private const val TAG = "HlsHostConcurrencyLimiter"

        /**
         * A2.1 Decision 6: what a FAILED live read falls back to — the shipped
         * `hls_workers` default (`AppContext.defaults()`), never 0: a
         * zero-permit semaphore would deadlock every request to that host.
         */
        internal const val FALLBACK_LIMIT_PER_HOST = 24
    }

    /**
     * Fixed-ceiling constructor — the pre-A2.1 shape, kept so every existing
     * host and the whole JVM suite behave exactly as before.
     */
    constructor(limitPerHost: Int) : this({ limitPerHost })

    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    /**
     * The ceiling a host seen for the first time gets right now: the provider's
     * value, floored at 1 (Decision 6). Already-tracked hosts keep their
     * creation-time capacity — see the class KDoc.
     */
    suspend fun limitPerHost(): Int = currentLimit()

    private suspend fun currentLimit(): Int = try {
        limitProvider().coerceAtLeast(1)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "hls_workers read failed (${e.message}) - falling back to $FALLBACK_LIMIT_PER_HOST")
        FALLBACK_LIMIT_PER_HOST
    }

    /** Hostnames currently tracked. Never blocks and never creates an entry. */
    fun trackedHosts(): Set<String> = semaphores.keys.toSet()

    /**
     * Run [block] while holding one of this host's [limitPerHost] request slots.
     *
     * A URL whose host cannot be parsed is passed through uncapped: it is already
     * malformed, the underlying client will reject it, and there is no host to
     * contend on.
     */
    suspend fun <T> withHostSlot(url: String, block: suspend () -> T): T {
        val host = hostOf(url)
        if (host == null) {
            AppLog.w(TAG, "Unparseable host - running request uncapped")
            return block()
        }

        // AQR-022 (guarantee unchanged, mechanism adapted for Decision 6's
        // suspend live-read, which computeIfAbsent's plain-function mapping
        // cannot host): at most ONE semaphore per host is ever RETAINED. The
        // candidate is built first and installed with putIfAbsent; when a
        // racing candidate already won, the loser is discarded UNUSED — never
        // acquired, never released — so permits can still never reach a
        // non-canonical instance.
        val semaphore = semaphores[host] ?: run {
            val candidate = Semaphore(currentLimit())
            semaphores.putIfAbsent(host, candidate) ?: candidate
        }
        // Outside the try: a cancelled acquire owns no permit, so `finally` must not run.
        semaphore.acquire()
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }
}
