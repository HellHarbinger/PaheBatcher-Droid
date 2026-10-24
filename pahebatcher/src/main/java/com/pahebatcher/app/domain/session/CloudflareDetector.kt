package com.pahebatcher.app.domain.session

/**
 * Utility for detecting Cloudflare challenge responses in HTTP responses.
 *
 * Used by the native HTTP layer (OkHttp) to determine if a response has been
 * intercepted by Cloudflare rather than being a legitimate AnimePahe response.
 *
 * This prevents the application from silently treating a Cloudflare challenge page
 * as an AnimePahe response, which would cause downstream parsing failures.
 */
object CloudflareDetector {

    /**
     * Result of Cloudflare detection analysis.
     */
    data class DetectionResult(
        /** Whether this response appears to be a Cloudflare challenge */
        val isCloudflareChallenge: Boolean,
        /** Whether this response appears to be from Cloudflare at all */
        val isCloudflareResponse: Boolean,
        /** Which indicators triggered the detection */
        val indicators: List<String>,
        /** Whether the response body is likely a Cloudflare challenge page */
        val bodyIsChallenge: Boolean
    )

    /**
     * Known Cloudflare challenge indicators in response headers.
     */
    private val CHALLENGE_HEADERS = setOf(
        "cf-mitigated",
        "cf-ray",
        "cf-cache-status",
        "cf-connecting-ip"
    )

    /**
     * Header values that specifically indicate a Cloudflare challenge.
     */
    private val CHALLENGE_HEADER_VALUES = mapOf(
        "cf-mitigated" to listOf("challenge", "block")
    )

    /**
     * Patterns in response body that indicate a Cloudflare challenge page.
     *
     * Note: The bare string "Cloudflare" is intentionally excluded. It is too broad
     * and would cause false positives when legitimate content mentions Cloudflare
     * (e.g., in a footer, attribution, or CDN reference). The other patterns are
     * specific enough to identify actual challenge pages.
     */
    private val CHALLENGE_BODY_PATTERNS = listOf(
        "Just a moment",
        "Checking your browser",
        "cf-browser-verification",
        "challenge-platform",
        "cf_chl_opt",
        "turnstile",
        "ray ID"
    )

    /**
     * AnimePahe-specific indicators that we've reached the actual site.
     */
    private val ANIMEPAHE_INDICATORS = listOf(
        "animepahe",
        "<title>animepahe",
        "\"animepahe\""
    )

    /**
     * Analyze HTTP response headers and body to detect Cloudflare challenge.
     *
     * @param statusCode HTTP status code
     * @param headers Response headers (case-insensitive keys)
     * @param body Response body (first 10KB analyzed)
     * @return DetectionResult indicating whether this is a Cloudflare challenge
     */
    fun detect(
        statusCode: Int,
        headers: Map<String, String>,
        body: String? = null
    ): DetectionResult {
        val indicators = mutableListOf<String>()
        var isCloudflareResponse = false
        var isCloudflareChallenge = false
        var bodyIsChallenge = false

        // Normalize header keys to lowercase
        val normalizedHeaders = headers.mapKeys { it.key.lowercase() }

        // Check for Cloudflare challenge headers
        for ((header, triggerValues) in CHALLENGE_HEADER_VALUES) {
            val value = normalizedHeaders[header]?.lowercase() ?: continue
            if (triggerValues.any { value.contains(it) }) {
                indicators.add("Header '$header' = '${normalizedHeaders[header]}' (challenge indicator)")
                isCloudflareChallenge = true
                isCloudflareResponse = true
            }
        }

        // Check for Cloudflare presence headers
        for (header in CHALLENGE_HEADERS) {
            if (normalizedHeaders.containsKey(header)) {
                isCloudflareResponse = true
                if (!indicators.any { it.contains(header) }) {
                    indicators.add("Header '$header' present (Cloudflare response)")
                }
            }
        }

        // Check Server header
        val server = normalizedHeaders["server"]?.lowercase() ?: ""
        if (server.contains("cloudflare")) {
            isCloudflareResponse = true
            indicators.add("Server: cloudflare")
        }

        // Check status code
        if (statusCode == 403 && isCloudflareResponse) {
            isCloudflareChallenge = true
            indicators.add("HTTP 403 with Cloudflare headers")
        }

        // Check body for challenge patterns (limit to first 10KB)
        if (body != null) {
            val bodySnippet = body.take(10_240).lowercase()
            for (pattern in CHALLENGE_BODY_PATTERNS) {
                if (bodySnippet.contains(pattern.lowercase())) {
                    bodyIsChallenge = true
                    indicators.add("Body contains '$pattern'")
                    break
                }
            }

            // Check if body is actual AnimePahe content
            for (indicator in ANIMEPAHE_INDICATORS) {
                if (bodySnippet.contains(indicator.lowercase())) {
                    indicators.add("Body contains AnimePahe indicator: '$indicator'")
                    break
                }
            }
        }

        // Final determination
        if (isCloudflareChallenge && bodyIsChallenge) {
            indicators.add("CONCLUSION: Response is a Cloudflare challenge page")
        } else if (isCloudflareChallenge) {
            indicators.add("CONCLUSION: Response has Cloudflare challenge headers")
        } else if (isCloudflareResponse) {
            indicators.add("CONCLUSION: Response is from Cloudflare (not necessarily a challenge)")
        } else {
            indicators.add("CONCLUSION: No Cloudflare challenge detected")
        }

        return DetectionResult(
            isCloudflareChallenge = isCloudflareChallenge,
            isCloudflareResponse = isCloudflareResponse,
            indicators = indicators,
            bodyIsChallenge = bodyIsChallenge
        )
    }

    /**
     * Quick check: is this HTTP response a Cloudflare challenge that would
     * prevent native HTTP from reaching AnimePahe?
     *
     * Use this in OkHttp response handlers to detect blocked requests.
     */
    fun isBlockedByCloudflare(
        statusCode: Int,
        headers: Map<String, String>,
        body: String? = null
    ): Boolean {
        return detect(statusCode, headers, body).isCloudflareChallenge
    }
}
