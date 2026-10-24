package com.pahebatcher.app.data.session

import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogUrlSanitizer
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.domain.session.WebViewRequestBridge

/**
 * WebView-based implementation of HttpClient.
 *
 * This bridges the existing HttpClient interface with the new WebView session architecture.
 * All AnimePahe API requests (search, episodes, play pages) are routed through the
 * WebView session to handle Cloudflare protection.
 *
 * Architecture:
 * - Implements [HttpClient] for the Cloudflare-protected API surface
 * - Uses WebViewRequestBridge for all requests
 * - Maintains compatibility with existing AnimeRepository and EpisodeRepository
 *
 * Thread safety:
 * - Delegates to WebViewRequestBridge which handles session management
 * - Page operations are serialised by the bridge's shared WebView page lock
 *   (Phase 12D): the transport is ONE WebView, so requests never run concurrently
 *   even though each request is self-contained
 *
 * Important:
 * - This is NOT suitable for non-Cloudflare endpoints (HLS segments, AES keys)
 * - For those, use OkHttp directly
 */
class WebViewHttpClient(
    private val requestBridge: WebViewRequestBridge
) : HttpClient {

    companion object {
        private const val TAG = "WebViewHttpClient"
    }

    override suspend fun fetchJson(url: String): String? {
        // NET-009: host + path only — an API URL carries its query string.
        AppLog.d(TAG, "Fetching JSON: ${LogUrlSanitizer.url(url)}")

        val result = requestBridge.fetch(
            WebViewRequestBridge.RequestConfig(
                url = url,
                contentType = WebViewRequestBridge.ContentType.JSON,
                timeoutMs = 30_000L
            )
        )

        return if (result.success && result.content != null) {
            result.content
        } else {
            // NET-009: result.error may quote the failing URL.
            AppLog.w(TAG, "JSON fetch failed: ${LogUrlSanitizer.message(result.error)}")
            null
        }
    }

    override suspend fun fetchHtml(url: String): String? {
        // NET-009: host + path only — an API URL carries its query string.
        AppLog.d(TAG, "Fetching HTML: ${LogUrlSanitizer.url(url)}")

        val result = requestBridge.fetch(
            WebViewRequestBridge.RequestConfig(
                url = url,
                contentType = WebViewRequestBridge.ContentType.HTML,
                timeoutMs = 30_000L
            )
        )

        return if (result.success && result.content != null) {
            result.content
        } else {
            // NET-009: result.error may quote the failing URL.
            AppLog.w(TAG, "HTML fetch failed: ${LogUrlSanitizer.message(result.error)}")
            null
        }
    }
}
