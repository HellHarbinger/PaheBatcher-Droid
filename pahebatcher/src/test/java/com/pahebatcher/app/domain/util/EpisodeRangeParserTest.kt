package com.pahebatcher.app.domain.util

import com.pahebatcher.app.domain.model.EpisodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeRangeParserTest {

    private fun createTestEpisodes(vararg numbers: Float): List<EpisodeInfo> {
        return numbers.map { num ->
            EpisodeInfo(
                number = num,
                session = "s$num",
                title = "Episode $num",
                fansub = "Fansub",
                audio = "jpn",
                playUrl = "url$num"
            )
        }
    }

    // ========== parseRange tests ==========

    @Test
    fun `parseRange handles simple range`() {
        val episodes = createTestEpisodes(1f, 2f, 3f, 4f)
        val result = EpisodeRangeParser.parseRange("1-2", episodes)
        assertEquals(listOf(1.0f, 2.0f), result)
    }

    @Test
    fun `parseRange handles open-ended range`() {
        val episodes = createTestEpisodes(1f, 2f, 3f, 4f)
        val result = EpisodeRangeParser.parseRange("3-", episodes)
        assertEquals(listOf(3.0f, 4.0f), result)
    }

    @Test
    fun `parseRange handles comma-separated values`() {
        val episodes = createTestEpisodes(1f, 2f, 3f, 4f)
        val result = EpisodeRangeParser.parseRange("1,4", episodes)
        assertEquals(listOf(1.0f, 4.0f), result)
    }

    @Test
    fun `parseRange handles mixed ranges and values`() {
        val episodes = createTestEpisodes(1f, 2f, 3f, 4f, 5f, 6f)
        val result = EpisodeRangeParser.parseRange("1-2,5", episodes)
        assertEquals(listOf(1.0f, 2.0f, 5.0f), result)
    }

    @Test
    fun `parseRange handles fractional episodes`() {
        val episodes = createTestEpisodes(1f, 1.5f, 2f)
        val result = EpisodeRangeParser.parseRange("1.5", episodes)
        assertEquals(listOf(1.5f), result)
    }

    @Test
    fun `parseRange returns empty list for empty input`() {
        val result = EpisodeRangeParser.parseRange("", emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseRange returns empty list for blank input`() {
        val result = EpisodeRangeParser.parseRange("   ", emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseRange handles nonexistent episode number`() {
        val episodes = createTestEpisodes(1f, 2f, 3f)
        val result = EpisodeRangeParser.parseRange("10", episodes)
        assertEquals(listOf(10.0f), result)
    }

    @Test
    fun `parseRange handles multiple ranges`() {
        val episodes = createTestEpisodes(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        val result = EpisodeRangeParser.parseRange("1-3,7-9", episodes)
        assertEquals(listOf(1.0f, 2.0f, 3.0f, 7.0f, 8.0f, 9.0f), result)
    }

    @Test
    fun `parseRange handles whitespace separated values`() {
        val episodes = createTestEpisodes(1f, 2f, 3f, 4f)
        val result = EpisodeRangeParser.parseRange("1 3 4", episodes)
        assertEquals(listOf(1.0f, 3.0f, 4.0f), result)
    }

    // ========== selectEpisodes tests ==========

    @Test
    fun `selectEpisodes with all mode returns all episodes`() {
        val episodes = createTestEpisodes(1f, 2f, 3f)
        val result = EpisodeRangeParser.selectEpisodes(episodes, "all")
        assertEquals(3, result.size)
    }

    @Test
    fun `selectEpisodes with latest mode returns last N episodes`() {
        val episodes = createTestEpisodes(1f, 2f, 3f, 4f, 5f)
        val result = EpisodeRangeParser.selectEpisodes(episodes, "latest", latestN = 2)
        assertEquals(2, result.size)
        assertEquals(4.0f, result[0].number)
        assertEquals(5.0f, result[1].number)
    }

    @Test
    fun `selectEpisodes with range mode returns matching episodes`() {
        val episodes = createTestEpisodes(1f, 2f, 3f, 4f)
        val result = EpisodeRangeParser.selectEpisodes(episodes, "range", rangeStr = "1-3")
        assertEquals(3, result.size)
    }

    @Test
    fun `selectEpisodes with unknown mode returns empty list`() {
        val episodes = createTestEpisodes(1f, 2f, 3f)
        val result = EpisodeRangeParser.selectEpisodes(episodes, "unknown")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `selectEpisodes with latest mode clamps to available episodes`() {
        val episodes = createTestEpisodes(1f, 2f, 3f)
        val result = EpisodeRangeParser.selectEpisodes(episodes, "latest", latestN = 10)
        assertEquals(3, result.size)
    }

    @Test
    fun `selectEpisodes deduplicates by episode number`() {
        val episodes = listOf(
            EpisodeInfo(1f, "s1", "Ep 1", "Sub", "jpn", "url1"),
            EpisodeInfo(1f, "s2", "Ep 1", "Dub", "eng", "url2"),
            EpisodeInfo(2f, "s3", "Ep 2", "Sub", "jpn", "url3")
        )
        val result = EpisodeRangeParser.selectEpisodes(episodes, "all")
        assertEquals(2, result.size)
    }
}