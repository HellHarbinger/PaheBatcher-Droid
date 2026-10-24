package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.remote.parser.AnimePaheParser
import com.pahebatcher.app.domain.model.AnimeInfo
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

/**
 * Phase 11 §14/§35 search outcome — distinguishes the two empty situations the
 * Home screen must render differently:
 *
 * - [Results] with a non-empty list: real parsed rows to display.
 * - [Results] with an empty list: at least one host RESPONDED but nothing
 *   matched → honest "No results found." (no error/retry implied).
 * - [Unreachable]: every host failed (no successful response) → error branch
 *   with a Retry affordance bound to the real failing call (§35).
 */
sealed interface SearchOutcome {
    data class Results(val results: List<Map<String, Any>>) : SearchOutcome
    data object Unreachable : SearchOutcome
}

/**
 * Repository for AnimePahe search and anime discovery.
 * Ported from Python extract/scanner.py AnimePaheScanner.
 *
 * Responsibilities:
 * - Search anime by query
 * - Fetch anime title from HTML
 * - Discover SUB/DUB variants
 */
class AnimeRepository(
    private val httpClient: HttpClient
) {
    companion object {
        /** Hosts to try in order for search resilience */
        val SEARCH_HOSTS = listOf("animepahe.com", "animepahe.pw", "animepahe.org")

        /** Current working host after successful request */
        @Volatile
        var currentHost: String = "animepahe.com"
            private set
    }

    /**
     * Phase 11 §14 search entry point returning an explicit [SearchOutcome].
     *
     * Same multi-host fallback order as [search], but instead of collapsing both
     * "all hosts failed" and "reached, zero matches" into `emptyList()`, it
     * reports whether any host actually responded:
     *
     * - a successful response with parsed rows → [SearchOutcome.Results] (rows)
     * - a successful response with zero rows → [SearchOutcome.Results] (empty)
     * - no host responded at all → [SearchOutcome.Unreachable]
     *
     * [CancellationException] is rethrown before the generic catch so a cancelled
     * (superseded) query can never fall into an error branch (§14 single in-flight).
     */
    suspend fun searchWithOutcome(host: String, query: String): SearchOutcome {
        val hosts = (listOf(host) + SEARCH_HOSTS).distinct()
        var sawResponse = false

        for (h in hosts) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "https://$h/api?m=search&q=$encodedQuery"

                val jsonData = httpClient.fetchJson(url) ?: continue
                sawResponse = true
                val results = AnimePaheParser.parseSearchResults(jsonData)

                if (results.isNotEmpty()) {
                    currentHost = h
                    return SearchOutcome.Results(results)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Continue to next host on failure
                continue
            }
        }

        return if (sawResponse) {
            SearchOutcome.Results(emptyList())
        } else {
            SearchOutcome.Unreachable
        }
    }

    /**
     * Search for anime by query string.
     * Ported from Python AnimePaheScanner.search().
     *
     * Tries multiple hosts in order for resilience.
     * Returns empty list if all hosts fail.
     *
     * @param host Primary host to try first
     * @param query Search query
     * @return List of search result dictionaries
     */
    suspend fun search(host: String, query: String): List<Map<String, Any>> {
        // Build ordered list of hosts to try (deduplicated)
        val hosts = listOf(host) + SEARCH_HOSTS.filter { it != host }

        for (h in hosts.distinct()) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "https://$h/api?m=search&q=$encodedQuery"

                val jsonData = httpClient.fetchJson(url) ?: continue
                val results = AnimePaheParser.parseSearchResults(jsonData)

                if (results.isNotEmpty()) {
                    currentHost = h
                    return results
                }
            } catch (e: CancellationException) {
                // DATA-004: a cancelled search must abort, not keep iterating hosts.
                throw e
            } catch (e: Exception) {
                // Continue to next host on failure
                continue
            }
        }

        return emptyList()
    }

    /**
     * Fetch anime title from HTML page.
     * Ported from Python AnimePaheScanner._fetch_title().
     *
     * @param host AnimePahe domain
     * @param session Anime session UUID
     * @return Extracted title or "Unknown Anime"
     */
    suspend fun fetchTitle(host: String, session: String): String {
        return try {
            val html = httpClient.fetchHtml("https://$host/anime/$session") ?: return "Unknown Anime"
            AnimePaheParser.extractTitle(html)
        } catch (e: CancellationException) {
            // DATA-004: propagate cancellation rather than reporting "Unknown Anime".
            throw e
        } catch (e: Exception) {
            "Unknown Anime"
        }
    }

    /**
     * Result of variant discovery, including the API title.
     */
    data class DiscoveryResult(
        val sessions: List<String>,
        val title: String
    )

    /**
     * Discover all related sessions (SUB/DUB variants) for an anime.
     * Ported from Python AnimePaheScanner.discover_all_sessions().
     *
     * Searches by title and matches normalized titles to find related sessions.
     * Also returns the API title for use as fallback.
     *
     * @param host AnimePahe domain
     * @param session Initial anime session UUID
     * @return DiscoveryResult with session list and API title
     */
    suspend fun discoverVariantsWithTitle(host: String, session: String): DiscoveryResult {
        // Fetch initial episode page to get title
        val initialData = fetchEpisodePage(host, session, 1)
        if (initialData == null) {
            return DiscoveryResult(listOf(session), "")
        }

        val title = AnimePaheParser.extractAnimeTitle(initialData)
        if (title.isBlank()) {
            return DiscoveryResult(listOf(session), "")
        }

        // Search for related sessions by title
        val searchResults = search(host, title)
        val sessions = mutableSetOf(session)

        val normalizedTitle = AnimePaheParser.normalizeTitle(title)

        for (result in searchResults) {
            val resultSession = result["session"] as? String ?: continue
            val resultTitle = result["title"] as? String ?: continue

            if (AnimePaheParser.normalizeTitle(resultTitle) == normalizedTitle) {
                sessions.add(resultSession)
            }
        }

        return DiscoveryResult(sessions.toList(), title)
    }

    /**
     * Discover all related sessions (SUB/DUB variants) for an anime.
     * Ported from Python AnimePaheScanner.discover_all_sessions().
     *
     * Searches by title and matches normalized titles to find related sessions.
     *
     * @param host AnimePahe domain
     * @param session Initial anime session UUID
     * @return List of related session UUIDs (including the original)
     */
    suspend fun discoverVariants(host: String, session: String): List<String> {
        return discoverVariantsWithTitle(host, session).sessions
    }

    /**
     * Fetch episode page from API.
     * Ported from Python AnimePaheScanner._fetch_page().
     *
     * @param host AnimePahe domain
     * @param session Anime session UUID
     * @param page Page number (1-based)
     * @return Raw JSON string or null on failure
     */
    suspend fun fetchEpisodePage(host: String, session: String, page: Int): String? {
        // Primary endpoint
        val primaryUrl = "https://$host/api?m=release&id=$session&sort=episode_asc&page=$page"

        val primaryData = httpClient.fetchJson(primaryUrl)
        if (primaryData != null) {
            return primaryData
        }

        // Fallback endpoint
        val fallbackUrl = "https://$host/api/$session/releases?sort=episode_asc&page=$page"
        return httpClient.fetchJson(fallbackUrl)
    }
}