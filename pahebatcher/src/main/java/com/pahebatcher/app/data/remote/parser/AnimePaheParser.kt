package com.pahebatcher.app.data.remote.parser

import com.pahebatcher.app.domain.model.EpisodeInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.regex.Pattern

/**
 * Parser for AnimePahe API responses and HTML pages.
 * Ported from Python extract/scanner.py parsing logic.
 *
 * Uses kotlinx.serialization.json for JSON parsing (works in unit tests and on device).
 */
object AnimePaheParser {

    /** JSON instance for parsing */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * UUID pattern for validating anime/episode session IDs.
     * Ported from Python _UUID_RE.
     */
    private val UUID_PATTERN = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        Pattern.CASE_INSENSITIVE
    )

    // NET-005: compiled ONCE. The previous code called Pattern.compile/Regex(...)
    // inside these methods, recompiling on every call to the parser hot path.
    private val H1_TITLE_PATTERN =
        Pattern.compile("<h1[^>]*>\\s*(.*?)\\s*</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val TITLE_TAG_PATTERN = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val TITLE_SUFFIX_REGEX = Regex("\\s*[|·].*$")
    private val WATCH_PREFIX_REGEX = Regex("^Watch\\s+", RegexOption.IGNORE_CASE)
    private val NON_WORD_OR_SPACE_REGEX = Regex("[^\\w\\s]")
    private val SPACE_RUN_REGEX = Regex("\\s+")

    /**
     * Parse anime URL to extract host and session UUID.
     * Ported from Python parse_anime_url().
     *
     * @param url Full anime URL like https://animepahe.pw/anime/<uuid>
     * @return Pair of (host, session UUID)
     * @throws IllegalArgumentException if URL is invalid
     */
    fun parseAnimeUrl(url: String): Pair<String, String> {
        // Validate scheme
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("Unsupported scheme in URL: $url")
        }

        // Validate domain
        if (!url.contains("animepahe")) {
            throw IllegalArgumentException("Not an AnimePahe URL: $url")
        }

        // Extract host
        val hostStart = url.indexOf("://") + 3
        val hostEnd = url.indexOf("/", hostStart)
        val host = if (hostEnd > 0) url.substring(hostStart, hostEnd) else url.substring(hostStart)

        // Extract UUID from path
        val pathStart = url.indexOf("/", hostStart) + 1
        val path = if (pathStart < url.length) url.substring(pathStart) else ""

        val matcher = UUID_PATTERN.matcher(path)
        if (!matcher.find()) {
            throw IllegalArgumentException(
                "No anime UUID in URL. Expected: https://animepahe.pw/anime/<uuid>. Got: $url"
            )
        }

        val uuid = matcher.group(0) ?: throw IllegalArgumentException("Failed to extract UUID")
        return host to uuid
    }

    /**
     * Parse search results from API response JSON.
     * Ported from Python AnimePaheScanner.search().
     *
     * @param jsonData Raw JSON response from search API
     * @return List of search result dictionaries
     */
    fun parseSearchResults(jsonData: String): List<Map<String, Any>> {
        return try {
            val jsonObject = json.parseToJsonElement(jsonData).jsonObject
            val dataArray = jsonObject["data"]?.jsonArray ?: return emptyList()

            val results = mutableListOf<Map<String, Any>>()
            for (element in dataArray) {
                val item = element.jsonObject
                val result = mapOf(
                    "session" to (item["session"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "title" to (item["title"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "type" to (item["type"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "year" to (item["year"]?.jsonPrimitive?.intOrNull ?: 0),
                    "episodes" to (item["episodes"]?.jsonPrimitive?.intOrNull ?: 0),
                    "score" to (item["score"]?.jsonPrimitive?.contentOrNull ?: "")
                )
                results.add(result)
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Parse episode page from API response JSON.
     * Ported from Python AnimePaheScanner._parse_episode_page().
     *
     * @param jsonData Raw JSON response from episode API
     * @param host AnimePahe domain
     * @param session Anime session UUID
     * @return List of EpisodeInfo objects
     */
    fun parseEpisodePage(jsonData: String, host: String, session: String): List<EpisodeInfo> {
        return try {
            val jsonObject = json.parseToJsonElement(jsonData).jsonObject
            val dataArray = jsonObject["data"]?.jsonArray ?: return emptyList()

            val episodes = mutableListOf<EpisodeInfo>()
            for (element in dataArray) {
                val item = element.jsonObject
                val epSession = item["session"]?.jsonPrimitive?.contentOrNull ?: ""

                // Skip episodes with empty session
                if (epSession.isBlank()) continue

                // Parse audio with detection logic
                var audio = (item["audio"]?.jsonPrimitive?.contentOrNull ?: "jpn").trim().lowercase()
                val title = (item["title"]?.jsonPrimitive?.contentOrNull ?: "").trim()

                // Title-based audio detection (matches Python logic)
                if (audio == "jpn" && title.lowercase().contains("dub")) {
                    audio = "eng"
                } else if (audio == "eng" && title.lowercase().contains("sub")) {
                    audio = "jpn"
                }

                val epNumber = try {
                    item["episode"]?.jsonPrimitive?.double?.toFloat() ?: 0f
                } catch (e: Exception) {
                    0f
                }
                val fansub = (item["fansub"]?.jsonPrimitive?.contentOrNull ?: "").trim()
                val displayTitle = if (title == "?") "" else title

                episodes.add(
                    EpisodeInfo(
                        number = epNumber,
                        session = epSession,
                        title = displayTitle,
                        fansub = fansub,
                        audio = audio,
                        playUrl = "https://$host/play/$session/$epSession"
                    )
                )
            }
            episodes
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Extract title from anime page HTML.
     * Ported from Python AnimePaheScanner._fetch_title().
     *
     * @param html Raw HTML content from anime page
     * @return Extracted title or "Unknown Anime"
     */
    fun extractTitle(html: String): String {
        // Try h1 tag first
        val h1Matcher = H1_TITLE_PATTERN.matcher(html)
        if (h1Matcher.find()) {
            var title = h1Matcher.group(1)?.let {
                it.replace(HTML_TAG_REGEX, "").trim()
            } ?: ""

            if (title.length > 4) {
                val half = title.length / 2
                // Check for doubled titles (common on AnimePahe)
                if (title.substring(0, half) == title.substring(half)) {
                    title = title.substring(0, half).trim()
                }
            }

            if (title.isNotBlank()) {
                return title
            }
        }

        // Try title tag as fallback
        val titleMatcher = TITLE_TAG_PATTERN.matcher(html)
        if (titleMatcher.find()) {
            var title = titleMatcher.group(1)?.trim() ?: ""
            // Remove common suffixes
            title = title.replace(TITLE_SUFFIX_REGEX, "").trim()
            title = title.replace(WATCH_PREFIX_REGEX, "").trim()
            if (title.isNotBlank()) {
                return title
            }
        }

        return "Unknown Anime"
    }

    /**
     * Extract anime title from API response for variant discovery.
     * Ported from Python AnimePaheScanner.discover_all_sessions().
     *
     * @param jsonData Raw JSON response from episode page API
     * @return Anime title or empty string
     */
    fun extractAnimeTitle(jsonData: String): String {
        return try {
            val jsonObject = json.parseToJsonElement(jsonData).jsonObject
            val animeData = jsonObject["anime"]?.jsonObject ?: return ""
            animeData["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Normalize title for comparison (variant discovery).
     * Ported from Python _norm() function.
     *
     * @param title Raw title string
     * @return Normalized title for comparison
     */
    fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(NON_WORD_OR_SPACE_REGEX, "")
            .replace(SPACE_RUN_REGEX, " ")
            .trim()
    }

    /**
     * Get the last page number from episode API response.
     * Used for pagination during scanning.
     *
     * @param jsonData Raw JSON response
     * @return Last page number (defaults to 1)
     */
    fun getLastPage(jsonData: String): Int {
        return try {
            val jsonObject = json.parseToJsonElement(jsonData).jsonObject
            jsonObject["last_page"]?.jsonPrimitive?.int ?: 1
        } catch (e: Exception) {
            1
        }
    }

    /**
     * The paginator metadata ONE release-page response carries, read as
     * OPT-IN values so "the field was absent/malformed" stays distinguishable
     * from "the field said 0".
     *
     * `total = 0` therefore means the source really reports zero episodes,
     * while `total = null` means no usable `total` was present — the two are
     * never collapsed (SPEC/product rule: never invent a total).
     *
     * @param total the API `total`, or `null` when absent or not a
     *   non-negative integer
     * @param perPage the API `per_page`; when the field itself is absent but
     *   the response proves a full page (`current_page < last_page`), the row
     *   count of this page is used — still an OBSERVED figure, never a guess
     * @param currentPage the API `current_page` (1-based)
     * @param lastPage the API `last_page`
     */
    data class ReleasePageMeta(
        val total: Int?,
        val perPage: Int?,
        val currentPage: Int?,
        val lastPage: Int?,
    )

    /**
     * Read [ReleasePageMeta] from one release-page response.
     *
     * Never throws and never invents: an unreadable body, a non-object root
     * or a field of the wrong type all yield `null` for that field. Page
     * numbers are taken as the API reports them (1-based — `current_page`
     * starts at 1 on this endpoint), so callers must not re-base them.
     *
     * @param jsonData whatever the transport returned for this page
     */
    fun parsePageMeta(jsonData: String): ReleasePageMeta {
        var total: Int? = null
        var perPage: Int? = null
        var currentPage: Int? = null
        var lastPage: Int? = null
        var rowCount: Int? = null
        try {
            val root = json.parseToJsonElement(jsonData)
            if (root is JsonObject) {
                total = root.intOrNullAt("total")?.takeIf { it >= 0 }
                perPage = root.intOrNullAt("per_page")?.takeIf { it > 0 }
                currentPage = root.intOrNullAt("current_page")?.takeIf { it > 0 }
                lastPage = root.intOrNullAt("last_page")?.takeIf { it > 0 }
                rowCount = (root["data"] as? JsonArray)?.size
            }
        } catch (e: Exception) {
            // A body nobody can read simply carries no metadata.
        }
        // A page that is provably not the last one has been filled to the
        // server's window, so its row count IS the page size.
        val derivedPerPage = perPage
            ?: rowCount?.takeIf { size -> size > 0 && currentPage != null && lastPage != null &&
                lastPage > currentPage }
        return ReleasePageMeta(
            total = total,
            perPage = derivedPerPage,
            currentPage = currentPage,
            lastPage = lastPage,
        )
    }

    private fun JsonObject.intOrNullAt(key: String): Int? = try {
        this[key]?.jsonPrimitive?.intOrNull
    } catch (e: Exception) {
        null
    }

    /**
     * Classify ONE listing response for pagination — the three outcomes
     * `extract/scanner.py:267-287` distinguishes. See [EpisodePageKind].
     *
     * The classification reads the RESPONSE itself rather than the parsed row
     * list, because a payload nobody can read and a payload whose rows are all
     * dropped by the blank-session filter both reduce to "zero episodes" while
     * only one of them ends the session.
     *
     * @param jsonData whatever the transport returned for this page
     * @return the page's kind; an unreadable payload classifies as
     *   [EpisodePageKind.UNREADABLE] instead of throwing
     */
    fun classifyEpisodePage(jsonData: String): EpisodePageKind {
        return try {
            val root = json.parseToJsonElement(jsonData)
            if (root !is JsonObject) {
                EpisodePageKind.UNREADABLE
            } else {
                when (val data = root["data"]) {
                    // Absent or `null`: Python's `data.get("data")` is falsy,
                    // so the session genuinely has nothing more to give.
                    null, is JsonNull -> EpisodePageKind.END_OF_PAGINATION
                    is JsonArray ->
                        if (data.isEmpty()) {
                            EpisodePageKind.END_OF_PAGINATION
                        } else {
                            EpisodePageKind.ROWS
                        }
                    // A `data` that is not an array is not a listing page. The
                    // reference would raise while iterating it (and therefore
                    // cache nothing either); classifying it UNREADABLE keeps the
                    // same guarantee — it can never become a cached "complete"
                    // scan — without crashing the scan.
                    else -> EpisodePageKind.UNREADABLE
                }
            }
        } catch (e: Exception) {
            EpisodePageKind.UNREADABLE
        }
    }
}

/**
 * How one episode-listing response bears on pagination — the three outcomes
 * `extract/scanner.py:267-287` distinguishes.
 */
enum class EpisodePageKind {
    /**
     * `data` carries at least one row: keep paginating
     * (`extract/scanner.py:282-287`). Rows dropped by the blank-session filter
     * still count as data here, exactly as Python's `_parse_episode_page`
     * (`extract/scanner.py:112-113`) dropping them never ends a session early.
     */
    ROWS,

    /**
     * `data` is absent, `null` or an empty array: the source has no further
     * releases for this session, so the scan is COMPLETE — possibly with zero
     * episodes (`extract/scanner.py:280-281`, which leaves `completed_all`
     * `True`).
     */
    END_OF_PAGINATION,

    /**
     * The response is not a page that can be read (unparsable body, a
     * non-object root, or a `data` that is not an array). This is the state
     * Python reaches when `solver.fetch_json` (`solver.py:170-215`) returns
     * `None`: `completed_all` goes `False`, the episodes past this page are
     * missing, and the scan must not be cached.
     */
    UNREADABLE,
}
