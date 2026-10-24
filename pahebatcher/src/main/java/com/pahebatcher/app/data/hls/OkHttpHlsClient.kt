package com.pahebatcher.app.data.hls

import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogUrlSanitizer
import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * OkHttp implementation of HlsClient for HLS downloads.
 *
 * Uses OkHttp (already a project dependency) for HTTP requests.
 * This client is for CDN/HLS endpoints, not for Cloudflare-protected AnimePahe requests.
 *
 * Returns typed HlsTextResult/HlsBytesResult
 * with HTTP status codes for error classification.
 *
 * OkHttp behavior:
 * - Follows redirects by default (up to 20)
 * - Does NOT follow redirects across HTTP/HTTPS by default
 * - Connection pooling with keep-alive
 * - DNS caching (300s TTL)
 *
 * Per-host concurrency (Python parity): the blocking `execute()` calls are wrapped in
 * [HlsHostConcurrencyLimiter] when one is supplied, reproducing the process-wide
 * `limit_per_host` aiohttp enforces through Python's single shared session. The slot is
 * held across the body read, not just the response headers, so "in-flight" means the
 * same thing here as it does in the reference.
 *
 * Logging: every URL in a WARN/ERROR line goes through [LogUrlSanitizer], so
 * the log says which host + path failed while the expiring query string (the
 * actual authorisation) is never emitted — AGENTS §10, AppLog §M7.
 */
class OkHttpHlsClient(
    private val hostLimiter: HlsHostConcurrencyLimiter? = null,
) : HlsClient {

    companion object {
        private const val TAG = "OkHttpHlsClient"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * DL-003 — the callers pass a small, fixed set of budgets (see
     * `EpisodeDownloader`: AES-key 10s, playlist/segment 30s). Deriving a client
     * per call is cheap (`newBuilder()` shares the pool/dispatcher) but not free,
     * so each distinct non-zero budget is derived ONCE and reused. Keyed by the
     * millisecond value; the set stays tiny and bounded by the call sites.
     *
     * Instance-level (AQR-005), not a companion cache: `newBuilder()` derives
     * from THIS instance's base client, so a static cache would silently share
     * whichever instance seeded the entry — cross-instance pool sharing plus
     * process-lifetime retention of that instance's pool. Every derived client
     * must descend from the instance that will use it.
     */
    private val timeoutClients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    override suspend fun getText(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long
    ): HlsTextResult {
        val limiter = hostLimiter ?: return executeText(url, context, timeoutMs)
        return limiter.withHostSlot(url) { executeText(url, context, timeoutMs) }
    }

    private suspend fun executeText(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long
    ): HlsTextResult = withContext(Dispatchers.IO) {
        try {
            val request = buildRequest(url, context)
            val response = clientFor(timeoutMs).newCall(request).execute()

            if (!response.isSuccessful) {
                val code = response.code
                AppLog.w(TAG, "GET text failed: $code for ${LogUrlSanitizer.url(url)}")
                response.close()
                return@withContext HlsTextResult.HttpError(code, url)
            }

            val body = response.body?.string()
            response.close()
            body?.let { HlsTextResult.Success(it) }
                ?: HlsTextResult.NetworkError("Empty response body", url)
        } catch (e: SocketTimeoutException) {
            val where = LogUrlSanitizer.url(url)
            AppLog.e(TAG, "GET text timeout for $where: ${LogUrlSanitizer.message(e.message)}")
            HlsTextResult.NetworkError("Timeout: ${e.message}", url)
        } catch (e: UnknownHostException) {
            val where = LogUrlSanitizer.url(url)
            AppLog.e(TAG, "GET text DNS failure for $where: ${LogUrlSanitizer.message(e.message)}")
            HlsTextResult.NetworkError("DNS failure: ${e.message}", url)
        } catch (e: Exception) {
            val where = LogUrlSanitizer.url(url)
            AppLog.e(TAG, "GET text error for $where: ${LogUrlSanitizer.message(e.message)}")
            HlsTextResult.NetworkError("Network error: ${e.message}", url)
        }
    }

    override suspend fun getBytes(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long
    ): HlsBytesResult {
        val limiter = hostLimiter ?: return executeBytes(url, context, timeoutMs)
        return limiter.withHostSlot(url) { executeBytes(url, context, timeoutMs) }
    }

    private suspend fun executeBytes(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long
    ): HlsBytesResult = withContext(Dispatchers.IO) {
        try {
            val request = buildRequest(url, context)
            val response = clientFor(timeoutMs).newCall(request).execute()

            if (!response.isSuccessful) {
                val code = response.code
                AppLog.w(TAG, "GET bytes failed: $code for ${LogUrlSanitizer.url(url)}")
                response.close()
                return@withContext HlsBytesResult.HttpError(code, url)
            }

            val bytes = response.body?.bytes()
            response.close()
            bytes?.let { HlsBytesResult.Success(it) }
                ?: HlsBytesResult.NetworkError("Empty response body", url)
        } catch (e: SocketTimeoutException) {
            val where = LogUrlSanitizer.url(url)
            AppLog.e(TAG, "GET bytes timeout for $where: ${LogUrlSanitizer.message(e.message)}")
            HlsBytesResult.NetworkError("Timeout: ${e.message}", url)
        } catch (e: UnknownHostException) {
            val where = LogUrlSanitizer.url(url)
            AppLog.e(TAG, "GET bytes DNS failure for $where: ${LogUrlSanitizer.message(e.message)}")
            HlsBytesResult.NetworkError("DNS failure: ${e.message}", url)
        } catch (e: Exception) {
            val where = LogUrlSanitizer.url(url)
            AppLog.e(TAG, "GET bytes error for $where: ${LogUrlSanitizer.message(e.message)}")
            HlsBytesResult.NetworkError("Network error: ${e.message}", url)
        }
    }

    /**
     * DL-003 fix — honor the per-request [timeoutMs] budget the callers already pass.
     *
     * The class-level client keeps the default 15s connect / 60s read / 60s write, but a
     * caller-capped request (e.g. a 10s AES-key fetch) must not be able to hang for the
     * full 60s. `newBuilder()` is OkHttp's documented, cheap way to derive a client for a
     * per-request override: it SHARES the connection pool and dispatcher of [client], so
     * this does not create a second pool — it only overlays `callTimeout` (the ceiling for
     * the whole call: DNS + connect + write + read).
     */
    private fun clientFor(timeoutMs: Long): OkHttpClient =
        if (timeoutMs > 0) {
            timeoutClients.computeIfAbsent(timeoutMs) {
                client.newBuilder().callTimeout(it, TimeUnit.MILLISECONDS).build()
            }
        } else {
            client
        }

    private fun buildRequest(
        url: String,
        context: HlsClient.RequestContext
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", context.userAgent)
            .header("Referer", context.referer)

        // Add cookies if present (Constraint 1: do not assume cookies are never needed)
        if (context.cookies.isNotEmpty()) {
            val cookieStr = context.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            builder.header("Cookie", cookieStr)
        }

        return builder.get().build()
    }
}
