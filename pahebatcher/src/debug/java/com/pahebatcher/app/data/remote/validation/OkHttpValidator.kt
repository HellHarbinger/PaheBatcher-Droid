package com.pahebatcher.app.data.remote.validation

import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Validation spike for OkHttp-based HTTP client.
 *
 * Purpose: Validate that OkHttp can:
 * 1. Make GET requests to AnimePahe
 * 2. Handle redirects
 * 3. Accept cookies
 * 4. Set User-Agent and Referer
 * 5. Transfer cookies from WebView
 *
 * This is a validation tool, NOT production code.
 */
class OkHttpValidator {

    companion object {
        private const val TAG = "OkHttpValidator"
        private const val ANIMEPAHE_BASE = "https://animepahe.com"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    /**
     * Result of HTTP request validation.
     */
    data class HttpRequestResult(
        val success: Boolean,
        val statusCode: Int,
        val headers: Map<String, String>,
        val cookies: Map<String, String>,
        val bodyLength: Int,
        val bodySnippet: String,
        val finalUrl: String,
        val redirectCount: Int,
        val diagnosticMessages: List<String>,
        // Diagnostic fields for 403 analysis
        val contentType: String? = null,
        val serverHeader: String? = null,
        val cfRay: String? = null,
        val cfCacheStatus: String? = null,
        val protocol: String? = null,
        val isCloudflareResponse: Boolean = false,
        val isAnimePaheResponse: Boolean = false,
        val sanitizedBodySnippet: String = ""
    )

    /**
     * Cookie jar for storing cookies.
     */
    private class InMemoryCookieJar : CookieJar {
        private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            cookieStore.getOrPut(host) { mutableListOf() }.apply {
                removeAll { existing -> cookies.any { it.name == existing.name } }
                addAll(cookies)
            }
            AppLog.d(TAG, "Saved ${cookies.size} cookies for $host")
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val cookies = cookieStore[host] ?: emptyList()
            AppLog.d(TAG, "Loading ${cookies.size} cookies for $host: ${cookies.map { it.name }}")
            return cookies
        }

        fun getAllCookies(): Map<String, String> {
            val allCookies = mutableMapOf<String, String>()
            cookieStore.forEach { (domain, cookies) ->
                cookies.forEach { cookie ->
                    allCookies["${cookie.name} ($domain)"] = cookie.value
                }
            }
            return allCookies
        }

        fun setCookies(cookies: Map<String, String>, domain: String) {
            cookies.forEach { (name, value) ->
                val cookie = Cookie.Builder()
                    .domain(domain)
                    .path("/")
                    .name(name)
                    .value(value)
                    .build()
                cookieStore.getOrPut(domain) { mutableListOf() }.add(cookie)
                AppLog.d(TAG, "Imported cookie '$name' for domain '$domain'")
            }
        }

        fun getCookieNames(): Map<String, List<String>> {
            return cookieStore.mapValues { (_, cookies) -> cookies.map { it.name } }
        }
    }

    private val cookieJar = InMemoryCookieJar()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val builder = originalRequest.newBuilder()
            
            // Only set default User-Agent if not already provided
            if (originalRequest.header("User-Agent") == null) {
                builder.header("User-Agent", DEFAULT_USER_AGENT)
            }
            
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Connection", "keep-alive")
                .header("Accept-Encoding", "gzip, deflate, br")
            
            chain.proceed(builder.build())
        }
        .build()

    /**
     * Test basic GET request to AnimePahe.
     */
    suspend fun testGetRequest(
        url: String = ANIMEPAHE_BASE,
        referer: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
        userAgent: String? = null
    ): HttpRequestResult = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        var redirectCount = 0

        try {
            diagnostics.add("=== OkHttp Request Diagnostics ===")
            diagnostics.add("Making GET request to: $url")

            val requestBuilder = Request.Builder()
                .url(url)
                .get()

            referer?.let {
                requestBuilder.header("Referer", it)
                diagnostics.add("Set Referer: $it")
            }

            additionalHeaders.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val request = requestBuilder.build()
            
            // Log safe request metadata (no credentials)
            diagnostics.add("--- Request ---")
            diagnostics.add("URL: ${request.url}")
            diagnostics.add("Method: ${request.method}")
            diagnostics.add("User-Agent: ${request.header("User-Agent") ?: "(none)"}")
            diagnostics.add("Referer: ${request.header("Referer") ?: "(none)"}")
            diagnostics.add("Accept: ${request.header("Accept") ?: "(none)"}")
            
            // Count cookies being sent
            val requestCookies = cookieJar.loadForRequest(request.url)
            diagnostics.add("Cookies sent: ${requestCookies.size} names=${requestCookies.map { it.name }}")

            val response = client.newCall(request).execute()
            redirectCount = response.priorRequests.size - 1

            val body = response.body?.string() ?: ""
            
            // Safe header extraction (redacted)
            diagnostics.add("--- Response ---")
            diagnostics.add("Status: ${response.code}")
            diagnostics.add("Protocol: ${response.protocol}")
            diagnostics.add("Final URL: ${response.request.url}")
            diagnostics.add("Redirect count: $redirectCount")
            
            // Extract safe response headers
            val contentType = response.header("Content-Type")
            val server = response.header("Server")
            val cfRay = response.header("CF-Ray")
            val cfCacheStatus = response.header("CF-Cache-Status")
            val cfConnectingIp = response.header("CF-Connecting-IP")
            
            diagnostics.add("Content-Type: $contentType")
            diagnostics.add("Server: $server")
            diagnostics.add("CF-Ray: $cfRay")
            diagnostics.add("CF-Cache-Status: $cfCacheStatus")
            diagnostics.add("CF-Connecting-IP: ${cfConnectingIp?.take(3)}...")  // Redacted
            
            // Log all response headers (safe ones only)
            diagnostics.add("--- All Response Headers ---")
            response.headers.forEach { (name, value) ->
                // Skip sensitive headers
                val lowerName = name.lowercase()
                if (lowerName !in listOf("set-cookie", "cookie", "authorization", 
                    "proxy-authorization", "x-auth-token", "x-access-token")) {
                    // Redact potentially sensitive values
                    val safeValue = when {
                        lowerName.contains("token") || lowerName.contains("session") -> "[REDACTED]"
                        lowerName.contains("auth") -> "[REDACTED]"
                        else -> value
                    }
                    diagnostics.add("  $name: $safeValue")
                }
            }
            
            // Analyze response body for Cloudflare/origin markers
            diagnostics.add("--- Body Analysis ---")
            diagnostics.add("Body length: ${body.length} bytes")
            
            // Sanitize body for safe display (remove tokens, cookies, credentials)
            val sanitizedBody = sanitizeBody(body)
            val bodySnippet = if (body.length > 500) body.substring(0, 500) + "..." else body
            val sanitizedSnippet = if (sanitizedBody.length > 500) sanitizedBody.substring(0, 500) + "..." else sanitizedBody
            
            // Detect Cloudflare response
            val isCloudflare = body.contains("cloudflare", ignoreCase = true) ||
                    body.contains("cf-error", ignoreCase = true) ||
                    body.contains("Attention Required", ignoreCase = true) ||
                    body.contains("Access denied", ignoreCase = true) ||
                    body.contains("Just a moment", ignoreCase = true) ||
                    cfRay != null
            
            // Detect AnimePahe response
            val isAnimePahe = body.contains("animepahe", ignoreCase = true) ||
                    body.contains("<title>", ignoreCase = true)
            
            diagnostics.add("Is Cloudflare response: $isCloudflare")
            diagnostics.add("Is AnimePahe response: $isAnimePahe")
            diagnostics.add("Contains 'cloudflare': ${body.contains("cloudflare", ignoreCase = true)}")
            diagnostics.add("Contains 'cf-error': ${body.contains("cf-error", ignoreCase = true)}")
            diagnostics.add("Contains 'animepahe': ${body.contains("animepahe", ignoreCase = true)}")
            diagnostics.add("Contains 'Attention Required': ${body.contains("Attention Required", ignoreCase = true)}")
            diagnostics.add("Contains 'Access denied': ${body.contains("Access denied", ignoreCase = true)}")
            diagnostics.add("Contains 'challenge': ${body.contains("challenge", ignoreCase = true)}")
            
            // First 200 chars of body (sanitized)
            diagnostics.add("--- Sanitized Body (first 200 chars) ---")
            diagnostics.add(sanitizedBody.take(200))
            
            // Extract cookies from response
            val responseCookies = cookieJar.getAllCookies()
            diagnostics.add("--- Cookies After Request ---")
            diagnostics.add("Total cookies stored: ${responseCookies.size}")
            diagnostics.add("Cookie domains: ${cookieJar.getCookieNames().keys}")

            HttpRequestResult(
                success = response.isSuccessful,
                statusCode = response.code,
                headers = response.headers.toMap(),
                cookies = responseCookies,
                bodyLength = body.length,
                bodySnippet = bodySnippet,
                finalUrl = response.request.url.toString(),
                redirectCount = redirectCount,
                diagnosticMessages = diagnostics,
                contentType = contentType,
                serverHeader = server,
                cfRay = cfRay,
                cfCacheStatus = cfCacheStatus,
                protocol = response.protocol.toString(),
                isCloudflareResponse = isCloudflare,
                isAnimePaheResponse = isAnimePahe,
                sanitizedBodySnippet = sanitizedSnippet
            )

        } catch (e: Exception) {
            AppLog.e(TAG, "Request failed", e)
            diagnostics.add("Exception: ${e.message}")
            HttpRequestResult(
                success = false,
                statusCode = -1,
                headers = emptyMap(),
                cookies = emptyMap(),
                bodyLength = 0,
                bodySnippet = "",
                finalUrl = url,
                redirectCount = 0,
                diagnosticMessages = diagnostics
            )
        }
    }
    
    /**
     * Sanitize response body for safe logging.
     * Removes tokens, cookies, credentials, and sensitive data.
     */
    private fun sanitizeBody(body: String): String {
        var sanitized = body
        
        // Remove potential cookie values
        sanitized = sanitized.replace(Regex("(?i)cookie[\"\\s]*[=:][\"\\s]*[^\"\\s;,]+"), "[COOKIE_REMOVED]")
        
        // Remove potential token values
        sanitized = sanitized.replace(Regex("(?i)token[\"\\s]*[=:][\"\\s]*[^\"\\s;,]+"), "[TOKEN_REMOVED]")
        
        // Remove potential session values
        sanitized = sanitized.replace(Regex("(?i)session[\"\\s]*[=:][\"\\s]*[^\"\\s;,]+"), "[SESSION_REMOVED]")
        
        // Remove potential authorization headers
        sanitized = sanitized.replace(Regex("(?i)authorization[\"\\s]*[=:][\"\\s]*[^\"\\s;,]+"), "[AUTH_REMOVED]")
        
        // Remove potential cf_clearance values
        sanitized = sanitized.replace(Regex("(?i)cf_clearance[\"\\s]*[=:][\"\\s]*[^\"\\s;,]+"), "[CF_CLEARANCE_REMOVED]")
        
        return sanitized
    }

    /**
     * Test API request (search endpoint).
     */
    suspend fun testSearchRequest(query: String): HttpRequestResult {
        val url = "$ANIMEPAHE_BASE/api?m=search&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return testGetRequest(url = url, referer = ANIMEPAHE_BASE)
    }

    /**
     * Test episode page request.
     */
    suspend fun testEpisodePage(sessionId: String): HttpRequestResult {
        val url = "$ANIMEPAHE_BASE/api?m=release&id=$sessionId&sort=episode_asc&page=1"
        return testGetRequest(url = url, referer = "$ANIMEPAHE_BASE/anime/$sessionId")
    }

    /**
     * Import cookies from WebView.
     */
    fun importCookies(cookies: Map<String, String>, domain: String) {
        cookieJar.setCookies(cookies, domain)
        AppLog.d(TAG, "Imported ${cookies.size} cookies for $domain")
    }

    /**
     * Get all stored cookies.
     */
    fun getStoredCookies(): Map<String, String> {
        return cookieJar.getAllCookies()
    }

    /**
     * Get cookie names grouped by domain for diagnostics.
     */
    fun getCookieNamesByDomain(): Map<String, List<String>> {
        return cookieJar.getCookieNames()
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
