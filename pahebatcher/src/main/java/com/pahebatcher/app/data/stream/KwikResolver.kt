package com.pahebatcher.app.data.stream

import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogUrlSanitizer
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import kotlinx.coroutines.CancellationException

/**
 * The result of resolving one Kwik page: the extracted playlist plus the KWIK
 * PAGE URL that produced it.
 *
 * The source URL is not decoration — Python `_resolve_kwik()` returns it as
 * `StreamInfo.referer`, and the Kwik CDN rejects playlist/segment requests whose
 * referrer is not a Kwik page (measured 403 vs 200 in 11.5E-4). After a domain
 * rotation the winning page is NOT the one the caller asked for, so the resolver
 * — the only component that knows which attempt succeeded — reports it.
 */
data class KwikResolution(
    /** Extracted M3U8 URL (the same value Python puts in `StreamInfo.url`). */
    val m3u8Url: String,
    /** The Kwik page URL (primary or rotated) that returned [m3u8Url]. */
    val sourceUrl: String,
)

/**
 * Resolves Kwik URLs to M3U8 stream URLs.
 *
 * Ported from Python kwik.py _resolve_kwik().
 * Uses the WebView session (CloudflareSessionProvider) instead of
 * curl_cffi/FlareSolverr (Decision 29).
 *
 * Python reference fallback chain:
 * 1. curl_cffi direct (TLS impersonation) — Android: WebView primary
 * 2. curl_cffi + domain rotation — Android: WebView + domain rotation
 * 3. FlareSolverr — Android: WebView (equivalent)
 * 4. FlareSolverr + domain rotation — Android: WebView + domain rotation
 *
 * Android fallback chain:
 * 1. WebView fetch of primary Kwik URL
 * 2. WebView fetch with domain rotation (KWIK_TLDS)
 *
 * Every Kwik fetch carries the referrer Python sends in `_HEADERS` (11.5E-4):
 * the Kwik WAF answers 403 to the same request without it. The User-Agent stays
 * the WebView's own (SPEC §6 — no hard-coded browser identity).
 *
 * Logging: every URL that reaches a log line goes through
 * [LogUrlSanitizer] (host + path only), so neither a Kwik page URL nor an
 * extracted expiring playlist URL can be emitted in full — AGENTS §10.
 */
open class KwikResolver(
    private val sessionProvider: CloudflareSessionProvider
) {
    companion object {
        private const val TAG = "KwikResolver"

        /** Kwik TLDs for domain rotation (from Python kwik.py KWIK_TLDS) */
        val KWIK_TLDS = listOf("cx", "gg", "si", "me", "net", "in", "cc")

        /**
         * Referrer Python sends with every Kwik request — Python
         * `extract/kwik.py::_HEADERS["Referer"]`.
         *
         * Verified against the live host in 11.5E-4: a Kwik page fetched with a
         * browser User-Agent and this referrer returns HTTP 200, the identical
         * request without it returns HTTP 403 (Cloudflare WAF block).
         */
        const val KWIK_REFERER = "https://animepahe.com/"

        /** The header set Python attaches to Kwik requests (referrer only). */
        val KWIK_REQUEST_HEADERS: Map<String, String> = mapOf("Referer" to KWIK_REFERER)
    }

    /**
     * Resolve a Kwik URL to an M3U8 URL.
     *
     * @param kwikUrl The Kwik URL to resolve
     * @return The playlist plus the Kwik page it came from, or null if resolution failed
     */
    open suspend fun resolve(kwikUrl: String): KwikResolution? {
        // Strategy 1: Direct WebView fetch
        val directResult = fetchViaWebView(kwikUrl)
        if (directResult != null) {
            AppLog.d(TAG, "Resolved Kwik URL directly: ${LogUrlSanitizer.url(kwikUrl)}")
            return KwikResolution(directResult, kwikUrl)
        }

        // Strategy 2: Domain rotation
        val currentTld = extractCurrentTld(kwikUrl)
        for (tld in KWIK_TLDS) {
            if (tld == currentTld) continue
            val altUrl = swapDomain(kwikUrl, tld)
            val altResult = fetchViaWebView(altUrl)
            if (altResult != null) {
                AppLog.d(TAG, "Resolved Kwik URL via domain rotation ($tld): ${LogUrlSanitizer.url(altUrl)}")
                return KwikResolution(altResult, altUrl)
            }
        }

        AppLog.w(TAG, "Failed to resolve Kwik URL: ${LogUrlSanitizer.url(kwikUrl)}")
        return null
    }

    /**
     * Fetch a Kwik URL via WebView and extract the M3U8 URL.
     */
    private suspend fun fetchViaWebView(url: String): String? {
        return try {
            AppLog.d(TAG, "Fetching Kwik page: ${LogUrlSanitizer.url(url)}")
            val result = sessionProvider.fetchPage(
                url = url,
                waitForCloudflare = true,
                timeoutMs = 30_000L,
                // 11.5E-4 parity fix: Python `_HEADERS` sends this referrer on every
                // Kwik request; without it the WAF blocks the page (measured 403).
                extraHeaders = KWIK_REQUEST_HEADERS
            )

            AppLog.d(TAG, "Kwik page fetch success=${result.success}, htmlLen=${result.html?.length ?: 0}")
            AppLog.d(TAG, "Kwik page diagnostics: ${LogUrlSanitizer.message(result.diagnostics.toString())}")

            if (!result.success || result.html == null) {
                AppLog.w(TAG, "Kwik page fetch failed: ${LogUrlSanitizer.message(result.error)}")
                return null
            }

            val m3u8 = M3u8Extractor.extractM3u8(result.html)
            AppLog.d(TAG, "M3U8 extraction result: ${LogUrlSanitizer.url(m3u8)}")

            if (m3u8 == null) {
                AppLog.w(TAG, "M3U8 extraction failed. HTML snippet (first 3000 chars):")
                AppLog.w(TAG, LogUrlSanitizer.message(result.html.take(3000)))
            }

            m3u8
        } catch (e: CancellationException) {
            // NET-001: a cancelled resolution must not be reported as a failed fetch
            // (returning null here would let the caller keep rotating through up to
            // six domain attempts after the caller abandoned the work).
            throw e
        } catch (e: Exception) {
            val where = LogUrlSanitizer.url(url)
            // `diagnostic`, not `message`: a URI/WebView failure message can
            // embed a malformed URL whose query tail sits outside any scheme
            // match, and the attached `e` is rendered through the same
            // sanitizer centrally in AppLog.renderThrowable before either sink.
            AppLog.e(TAG, "WebView fetch failed for $where: ${LogUrlSanitizer.diagnostic(e.message)}", e)
            null
        }
    }

    /**
     * Extract the current TLD from a Kwik URL.
     * Matches Python: current_tld = urlparse(url).netloc.rsplit(".", 1)[-1]
     */
    private fun extractCurrentTld(url: String): String {
        return try {
            val host = url.removePrefix("https://").removePrefix("http://")
                .substringBefore("/").substringBefore(":")
            host.substringAfterLast(".")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Swap the TLD of a Kwik URL.
     * Matches Python: `_swap_kwik_domain()` — which re-emits only
     * `scheme://netloc + path`, i.e. the query string and fragment are DROPPED.
     * Python: `return f"{p.scheme}://{'.'.join(parts)}{p.path}"`.
     */
    private fun swapDomain(url: String, newTld: String): String {
        return try {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val scheme = url.substring(0, schemeEnd)
            val rest = url.substring(schemeEnd + 3)

            val pathStart = rest.indexOf("/")
            val host = if (pathStart > 0) rest.substring(0, pathStart) else rest
            val pathAndSuffix = if (pathStart > 0) rest.substring(pathStart) else ""
            val suffixStart = pathAndSuffix.indexOfFirst { it == '?' || it == '#' }
            val path = if (suffixStart >= 0) pathAndSuffix.substring(0, suffixStart) else pathAndSuffix

            val parts = host.split(".").toMutableList()
            if (parts.size >= 2) {
                parts[parts.size - 1] = newTld
            }
            "$scheme://${parts.joinToString(".")}$path"
        } catch (e: Exception) {
            url
        }
    }
}
