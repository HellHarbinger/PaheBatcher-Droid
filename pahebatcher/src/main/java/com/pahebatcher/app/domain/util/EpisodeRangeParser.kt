package com.pahebatcher.app.domain.util

import com.pahebatcher.app.domain.model.EpisodeInfo
import java.util.regex.Pattern

/**
 * Parser for episode range strings.
 * Ported from Python ui/prompts.py _parse_ep_range().
 *
 * Supports:
 * - Simple ranges: "1-12"
 * - Comma-separated: "1,4,7"
 * - Open-ended ranges: "13-"
 * - Mixed: "1-6,10,13-"
 * - Fractional episodes: "1.5"
 */
object EpisodeRangeParser {

    // NET-005: compiled once per process instead of per call — and the three
    // Patterns used to be re-compiled for EVERY token inside parseRange's loop.
    private val TOKEN_SPLIT = Regex("[,\\s]+")
    private val RANGE_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)-(\\d+(?:\\.\\d+)?)$")
    private val OPEN_END_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)-$")
    private val SINGLE_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)$")

    /**
     * Parse episode range string and return matching episode numbers.
     * Ported from Python _parse_ep_range().
     *
     * @param raw Raw range string from user input
     * @param allEpisodes Available episodes for validation
     * @return Sorted list of matching episode numbers
     */
    fun parseRange(raw: String, allEpisodes: List<EpisodeInfo>): List<Float> {
        val availableNumbers = allEpisodes.map { it.number }.distinct().sorted().toSet()
        val result = mutableSetOf<Float>()

        if (raw.isBlank()) {
            return emptyList()
        }

        // Split by comma or whitespace
        val tokens = raw.split(TOKEN_SPLIT).filter { it.isNotBlank() }

        for (token in tokens) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue

            // Pattern 1: Range "1-12" or "1.5-2.5"
            val rangeMatcher = RANGE_PATTERN.matcher(trimmed)
            if (rangeMatcher.matches()) {
                val lo = rangeMatcher.group(1)!!.toFloat()
                val hi = rangeMatcher.group(2)!!.toFloat()
                result.addAll(availableNumbers.filter { it in lo..hi })
                continue
            }

            // Pattern 2: Open-ended range "13-" or "1.5-"
            val openEndMatcher = OPEN_END_PATTERN.matcher(trimmed)
            if (openEndMatcher.matches()) {
                val lo = openEndMatcher.group(1)!!.toFloat()
                result.addAll(availableNumbers.filter { it >= lo })
                continue
            }

            // Pattern 3: Single number "5" or "1.5"
            val singleMatcher = SINGLE_PATTERN.matcher(trimmed)
            if (singleMatcher.matches()) {
                result.add(singleMatcher.group(1)!!.toFloat())
                continue
            }
        }

        return result.sorted()
    }

    /**
     * Non-interactive episode selection.
     * Ported from Python noninteractive_episodes().
     *
     * @param episodes Available episodes
     * @param mode Selection mode: "all", "latest", "range"
     * @param rangeStr Range string for "range" mode
     * @param latestN Number of latest episodes for "latest" mode
     * @return Selected episodes
     */
    fun selectEpisodes(
        episodes: List<EpisodeInfo>,
        mode: String,
        rangeStr: String = "",
        latestN: Int = 1
    ): List<EpisodeInfo> {
        val uniqueEpisodes = episodes
            .distinctBy { it.number }
            .sortedBy { it.number }

        return when (mode.lowercase()) {
            "all" -> uniqueEpisodes

            "latest" -> {
                val n = latestN.coerceIn(1, uniqueEpisodes.size)
                uniqueEpisodes.takeLast(n)
            }

            "range" -> {
                val epsByNumber = episodes.associateBy { it.number }
                val matchedNumbers = parseRange(rangeStr, uniqueEpisodes)
                matchedNumbers.mapNotNull { epsByNumber[it] }
            }

            else -> emptyList()
        }
    }
}