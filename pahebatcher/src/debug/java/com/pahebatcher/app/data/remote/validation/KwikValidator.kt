package com.pahebatcher.app.data.remote.validation

import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Validation spike for Kwik URL resolution.
 *
 * Purpose: Determine whether OkHttp can:
 * 1. Follow Kwik redirects
 * 2. Extract M3U8 URLs from response
 * 3. Handle TLS without curl-cffi impersonation
 * 4. Determine if WebView is required for Kwik resolution
 *
 * This is a validation tool, NOT production code.
 */
class KwikValidator {

    companion object {
        private const val TAG = "KwikValidator"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val KWIK_TLDS = listOf("cx", "gg", "si", "me", "net", "in", "cc")
        private val M3U8_PATTERN = Pattern.compile("(https?://[^\\s'\"\\\\>]+(?:uwu\\.m3u8|\\.m3u8)[^\\s'\"\\\\>]*)")
        private val KWIK_URL_PATTERN = Pattern.compile("kwik\\.(?:si|cx|pw|gg|me|net|to|in|cc)")
    }

    /**
     * Result of Kwik resolution attempt.
     */
    data class KwikResult(
        val success: Boolean,
        val m3u8Url: String?,
        val statusCode: Int,
        val headers: Map<String, String>,
        val cookies: Map<String, String>,
        val bodyLength: Int,
        val bodySnippet: String,
        val finalUrl: String,
        val redirectCount: Int,
        val tlsFingerprintBlocked: Boolean,
        val diagnosticMessages: List<String>
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Referer", "https://animepahe.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            chain.proceed(request)
        }
        .build()

    /**
     * Attempt to resolve a Kwik URL.
     *
     * @param kwikUrl The Kwik URL to resolve
     * @param cookies Optional cookies to include
     * @return KwikResult with resolution attempt details
     */
    suspend fun resolveKwik(
        kwikUrl: String,
        cookies: Map<String, String> = emptyMap()
    ): KwikResult = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        var redirectCount = 0

        try {
            diagnostics.add("Attempting to resolve Kwik URL: $kwikUrl")

            // Check if this is a valid Kwik URL
            if (!KWIK_URL_PATTERN.matcher(kwikUrl).find()) {
                diagnostics.add("Not a valid Kwik URL")
                return@withContext KwikResult(
                    success = false,
                    m3u8Url = null,
                    statusCode = -1,
                    headers = emptyMap(),
                    cookies = emptyMap(),
                    bodyLength = 0,
                    bodySnippet = "",
                    finalUrl = kwikUrl,
                    redirectCount = 0,
                    tlsFingerprintBlocked = false,
                    diagnosticMessages = diagnostics
                )
            }

            // Build request
            val requestBuilder = Request.Builder()
                .url(kwikUrl)
                .get()

            // Add cookies
            if (cookies.isNotEmpty()) {
                val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                requestBuilder.header("Cookie", cookieHeader)
                diagnostics.add("Added ${cookies.size} cookies")
            }

            val request = requestBuilder.build()
            diagnostics.add("Request headers: ${request.headers}")

            val response = client.newCall(request).execute()
            redirectCount = response.priorRequests.size - 1

            val body = response.body?.string() ?: ""
            val bodySnippet = if (body.length > 1000) body.substring(0, 1000) + "..." else body

            diagnostics.add("Response code: ${response.code}")
            diagnostics.add("Final URL: ${response.request.url}")
            diagnostics.add("Response headers: ${response.headers}")
            diagnostics.add("Body length: ${body.length}")

            // Check for TLS blocking indicators
            val tlsBlocked = response.code == 403 ||
                    body.lowercase().contains("forbidden") ||
                    body.lowercase().contains("access denied") ||
                    body.lowercase().contains("security check")

            if (tlsBlocked) {
                diagnostics.add("TLS/HTTP fingerprint blocking detected")
            }

            // Try to extract M3U8 URL
            val m3u8Url = extractM3u8Url(body)
            if (m3u8Url != null) {
                diagnostics.add("Found M3U8 URL: $m3u8Url")
            } else {
                diagnostics.add("No M3U8 URL found in response")
                // Log script content for analysis
                if (body.contains("eval")) {
                    diagnostics.add("Response contains eval() - likely JS packed content")
                }
                if (body.contains("script")) {
                    diagnostics.add("Response contains script tags")
                }
            }

            // Extract cookies from response
            val responseCookies = mutableMapOf<String, String>()
            response.headers("Set-Cookie").forEach { cookieHeader ->
                val parts = cookieHeader.split(";").first().split("=", limit = 2)
                if (parts.size == 2) {
                    responseCookies[parts[0].trim()] = parts[1].trim()
                }
            }

            KwikResult(
                success = m3u8Url != null,
                m3u8Url = m3u8Url,
                statusCode = response.code,
                headers = response.headers.toMap(),
                cookies = responseCookies,
                bodyLength = body.length,
                bodySnippet = bodySnippet,
                finalUrl = response.request.url.toString(),
                redirectCount = redirectCount,
                tlsFingerprintBlocked = tlsBlocked,
                diagnosticMessages = diagnostics
            )

        } catch (e: Exception) {
            AppLog.e(TAG, "Kwik resolution failed", e)
            diagnostics.add("Exception: ${e.message}")
            KwikResult(
                success = false,
                m3u8Url = null,
                statusCode = -1,
                headers = emptyMap(),
                cookies = emptyMap(),
                bodyLength = 0,
                bodySnippet = "",
                finalUrl = kwikUrl,
                redirectCount = 0,
                tlsFingerprintBlocked = false,
                diagnosticMessages = diagnostics
            )
        }
    }

    /**
     * Attempt domain rotation for Kwik URL.
     */
    suspend fun resolveWithDomainRotation(
        kwikUrl: String,
        cookies: Map<String, String> = emptyMap()
    ): KwikResult {
        val currentTld = extractTld(kwikUrl)
        val diagnostics = mutableListOf<String>()

        // Try original URL first
        val originalResult = resolveKwik(kwikUrl, cookies)
        if (originalResult.success) {
            return originalResult
        }

        diagnostics.addAll(originalResult.diagnosticMessages)
        diagnostics.add("Original TLD failed, trying domain rotation")

        // Try alternative TLDs
        for (tld in KWIK_TLDS) {
            if (tld == currentTld) continue

            val altUrl = swapTld(kwikUrl, tld)
            diagnostics.add("Trying alternative TLD: $altUrl")

            val result = resolveKwik(altUrl, cookies)
            if (result.success) {
                return result.copy(
                    diagnosticMessages = diagnostics + result.diagnosticMessages
                )
            }
            diagnostics.addAll(result.diagnosticMessages)
        }

        return KwikResult(
            success = false,
            m3u8Url = null,
            statusCode = originalResult.statusCode,
            headers = originalResult.headers,
            cookies = originalResult.cookies,
            bodyLength = originalResult.bodyLength,
            bodySnippet = originalResult.bodySnippet,
            finalUrl = kwikUrl,
            redirectCount = originalResult.redirectCount,
            tlsFingerprintBlocked = originalResult.tlsFingerprintBlocked,
            diagnosticMessages = diagnostics
        )
    }

    /**
     * Extract M3U8 URL from HTML response.
     */
    private fun extractM3u8Url(html: String): String? {
        // Direct M3U8 URL pattern
        val matcher = M3U8_PATTERN.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)?.replace("\\/", "/")?.replace("\\", "")
        }

        // Check for source tags
        val sourcePattern = Pattern.compile("<source[^>]+src=[\"']([^\"']+\\.m3u8[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val sourceMatcher = sourcePattern.matcher(html)
        if (sourceMatcher.find()) {
            return sourceMatcher.group(1)
        }

        return null
    }

    /**
     * Extract TLD from URL.
     */
    private fun extractTld(url: String): String {
        val host = try {
            java.net.URL(url).host
        } catch (e: Exception) {
            return ""
        }
        val parts = host.split(".")
        return if (parts.isNotEmpty()) parts.last() else ""
    }

    /**
     * Swap TLD in URL.
     */
    private fun swapTld(url: String, newTld: String): String {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return url
            val parts = host.split(".")
            if (parts.size >= 2) {
                val newParts = parts.toMutableList()
                newParts[newParts.size - 1] = newTld
                val newHost = newParts.joinToString(".")
                java.net.URI(uri.scheme, newHost, uri.path, uri.query, uri.fragment).toString()
            } else {
                url
            }
        } catch (e: Exception) {
            url
        }
    }
}

/**
 * Extension to get prior requests from OkHttp response.
 */
private val okhttp3.Response.priorRequests: List<Request>
    get() {
        // Simplified - in production would track redirects properly
        return listOf(this.request)
    }
