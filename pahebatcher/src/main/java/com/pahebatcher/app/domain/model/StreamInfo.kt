package com.pahebatcher.app.domain.model

/**
 * Represents resolved stream information for an episode.
 *
 * Ported from Python models.py StreamInfo dataclass.
 * Contains the M3U8 URL and required headers for HLS download.
 */
data class StreamInfo(
    /** M3U8 playlist URL */
    val url: String,

    /** Cookie list matching Python's cookies: list[dict[str, str]] */
    val cookies: List<Map<String, String>> = emptyList(),

    /** User-Agent header for requests */
    val userAgent: String,

    /** Referer header for requests */
    val referer: String,

    /** Optional title */
    val title: String = "",

    /**
     * Audio variant this stream was RESOLVED as — the source's own open-set
     * value carried through unchanged (`jpn`, `eng`, `kor`, …).
     *
     * [com.pahebatcher.app.data.stream.StreamResolverImpl] always writes the
     * exact-match entry's audio here (never a default and never a
     * re-derivation). The empty default exists only for reconstruction paths
     * that rebuild a StreamInfo for headers/URL without any resolution (e.g.
     * the download worker) — such an instance never participates in audio
     * selection or display, and `""` says "unknown" rather than inventing
     * `jpn` (SPEC §22 — no forced labels).
     */
    val audio: String = "",

    /** Fansub group name */
    val fansub: String = ""
) {
    /**
     * Check if this stream requires authentication.
     */
    fun requiresAuth(): Boolean {
        return cookies.isNotEmpty()
    }

    /**
     * Get headers map for HTTP requests.
     */
    fun toHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (userAgent.isNotBlank()) {
            headers["User-Agent"] = userAgent
        }
        if (referer.isNotBlank()) {
            headers["Referer"] = referer
        }
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookieStr
        }
        return headers
    }

    /**
     * Cookie string formatted as semicolon-delimited name=value pairs.
     * Matches Python's cookie_str property.
     */
    val cookieStr: String
        get() = if (cookies.isEmpty()) {
            ""
        } else {
            cookies.joinToString("; ") { "${it["name"]}=${it["value"]}" }
        }
}