package com.pahebatcher.app.ui.player

import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.model.EpisodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §15 (episode navigation / next / previous) — the pure ordering rule a
 * STREAM entry navigates by, on the JVM (AGENTS §8).
 *
 * Everything the player does with a sibling list starts here: the index it
 * computes decides which episode "next" and "previous" point at, so the order
 * has to be the episode order the viewer sees, never the order a scan returned.
 */
class StreamSiblingIndexTest {

    private val session = "anime-session-1"

    private fun episode(
        number: Float,
        audio: String = EpisodeInfo.AUDIO_JAPANESE,
        title: String = "Episode $number",
    ) = EpisodeInfo(
        number = number,
        session = "ep-session-$number",
        title = title,
        fansub = "SubsPlease",
        audio = audio,
        playUrl = "https://animepahe.example.invalid/play/$session/ep$number",
    )

    @Test
    fun siblingsAreOrderedByEpisodeNumberNotByScanOrderOrStringSort() {
        // Deliberately shuffled, and containing numbers whose string sort lies
        // ("10" < "2") plus a fractional episode that must sit between 13 and 14.
        val episodes = listOf(
            episode(10f),
            episode(13.5f),
            episode(2f),
            episode(1f),
            episode(13f),
            episode(3f),
        )

        val ids = streamSiblingIds(session, episodes, EpisodeInfo.AUDIO_JAPANESE)

        assertEquals(
            listOf(
                "${session}_1_jpn",
                "${session}_2_jpn",
                "${session}_3_jpn",
                "${session}_10_jpn",
                "${session}_13_jpn",
                "${session}_13.5_jpn",
            ),
            ids,
        )
    }

    @Test
    fun onlyTheAudioVariantBeingStreamedIsNavigated() {
        // A SUB viewer must never "next" into the DUB of the same number: that
        // is a different recording of a different list.
        val episodes = listOf(
            episode(1f, audio = EpisodeInfo.AUDIO_JAPANESE),
            episode(2f, audio = EpisodeInfo.AUDIO_JAPANESE),
            episode(1f, audio = EpisodeInfo.AUDIO_ENGLISH),
            episode(2f, audio = EpisodeInfo.AUDIO_ENGLISH),
        )

        val sub = streamSiblingIds(session, episodes, EpisodeInfo.AUDIO_JAPANESE)
        val dub = streamSiblingIds(session, episodes, EpisodeInfo.AUDIO_ENGLISH)

        assertEquals(listOf("${session}_1_jpn", "${session}_2_jpn"), sub)
        assertEquals(listOf("${session}_1_eng", "${session}_2_eng"), dub)
    }

    @Test
    fun everySiblingIdIsTheIdTheRestOfThePlayerAlreadyUnderstands() {
        val episodes = listOf(episode(1f), episode(13.5f))

        val ids = streamSiblingIds(session, episodes, EpisodeInfo.AUDIO_JAPANESE)

        ids.forEach { id ->
            val parts = EpisodeId.parse(id)
            assertTrue("'$id' must pass the shape gate the ladder enforces", parts != null)
            assertEquals(session, parts!!.animeSession)
        }
        assertEquals("${session}_13.5_jpn", ids[1])
    }

    @Test
    fun aScanThatReportsTheSameEpisodeTwiceDoesNotOfferItTwice() {
        val episodes = listOf(episode(1f), episode(1f), episode(2f))

        val ids = streamSiblingIds(session, episodes, EpisodeInfo.AUDIO_JAPANESE)

        assertEquals(listOf("${session}_1_jpn", "${session}_2_jpn"), ids)
    }

    @Test
    fun anEmptyOrMismatchedListYieldsNoNavigationRatherThanAGuess() {
        assertEquals(
            emptyList<String>(),
            streamSiblingIds(session, emptyList(), EpisodeInfo.AUDIO_JAPANESE),
        )
        assertEquals(
            "a session scanned only in DUB offers no SUB navigation",
            emptyList<String>(),
            streamSiblingIds(session, listOf(episode(1f, audio = EpisodeInfo.AUDIO_ENGLISH)), EpisodeInfo.AUDIO_JAPANESE),
        )
    }
}
