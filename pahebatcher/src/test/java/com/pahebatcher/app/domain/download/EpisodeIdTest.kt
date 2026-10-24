package com.pahebatcher.app.domain.download

import com.pahebatcher.app.domain.model.EpisodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 17 §10 — [EpisodeId] must reproduce the episode download key EXACTLY.
 *
 * The format `{animeSession}_{epStr}_{audio}` predates Phase 17 (it has been
 * `DownloadRepository.episodeDownloadId` since Phase 10 and is the key Room
 * primary keys, playlists and position persistence are all built on), so the
 * highest-value assertion here is a literal: the value built today must be the
 * same string it has always been, or every existing download row would stop
 * being found.
 */
class EpisodeIdTest {

    private fun episode(
        number: Float = 1f,
        audio: String = EpisodeInfo.AUDIO_JAPANESE,
        title: String = "A Title",
    ) = EpisodeInfo(
        number = number,
        session = "ep-session-0000",
        title = title,
        fansub = "SubsPlease",
        audio = audio,
        playUrl = "https://animepahe.example/play/anime-sess/ep-session-0000",
    )

    @Test
    fun `build reproduces the historical download id byte for byte`() {
        assertEquals(
            "5f2b7a10-1234-4abc-9def-001122334455_1_jpn",
            EpisodeId.build("5f2b7a10-1234-4abc-9def-001122334455", episode()),
        )
    }

    @Test
    fun `build keeps the decimal episode string the reference groups by`() {
        assertEquals(
            "sess_13.5_eng",
            EpisodeId.build("sess", episode(number = 13.5f, audio = EpisodeInfo.AUDIO_ENGLISH)),
        )
    }

    @Test
    fun `sub and dub of one episode are two different ids`() {
        val animeSession = "anime-sess"
        val sub = EpisodeId.build(animeSession, episode(audio = EpisodeInfo.AUDIO_JAPANESE))
        val dub = EpisodeId.build(animeSession, episode(audio = EpisodeInfo.AUDIO_ENGLISH))

        assertNotEquals(sub, dub)
        assertEquals("anime-sess_1_jpn", sub)
        assertEquals("anime-sess_1_eng", dub)
    }

    @Test
    fun `parse round trips every id build produces`() {
        val episodes = listOf(
            episode(number = 1f, audio = EpisodeInfo.AUDIO_JAPANESE),
            episode(number = 13.5f, audio = EpisodeInfo.AUDIO_ENGLISH),
            episode(number = 100f, audio = EpisodeInfo.AUDIO_JAPANESE),
        )

        for (ep in episodes) {
            val id = EpisodeId.build("anime-session", ep)
            val parts = EpisodeId.parse(id)

            assertEquals("id should parse: $id", true, parts != null)
            assertEquals("anime-session", parts!!.animeSession)
            assertEquals(ep.epStr, parts.episodeString)
            assertEquals(ep.audio, parts.audio)
        }
    }

    @Test
    fun `parse rejects an id that does not have the exact three-part shape`() {
        val malformed = listOf(
            "",                                    // empty
            "anime-session_1",                     // too few parts
            "anime-session_1_jpn_extra",           // too many parts
            "anime-session_notanumber_jpn",        // episode is not a number
            "anime-session_1_",                    // blank audio
            "_1_jpn",                              // blank session
            "anime session_1_jpn",                 // session carries a space
            "anime-session__jpn",                  // empty episode part
        )

        for (id in malformed) {
            assertNull("should reject: <$id>", EpisodeId.parse(id))
        }
    }

    @Test
    fun `parse accepts every well shaped open-set audio code`() {
        assertEquals(
            EpisodeInfo.AUDIO_JAPANESE,
            EpisodeId.parse("sess_4_jpn")?.audio,
        )
        assertEquals(
            EpisodeInfo.AUDIO_ENGLISH,
            EpisodeId.parse("sess_4_eng")?.audio,
        )
        // INVERTED gate (SPEC §22): the audio component used to be restricted to
        // the closed {jpn, eng} pair, which rejected any other source code —
        // and with it every STREAM route for that episode. Any well-shaped
        // lowercase code now routes.
        assertEquals("kor", EpisodeId.parse("sess_4_kor")?.audio)
        assertEquals("english", EpisodeId.parse("sess_4_english")?.audio)
        assertEquals("007", EpisodeId.parse("sess_4_007")?.audio)
    }

    @Test
    fun `parse still rejects audio components that are not route safe`() {
        // The gate stays CLOSED in SHAPE even though it is open in vocabulary:
        // nothing with punctuation, uppercase, whitespace or an over-long token
        // may reach a network scan target.
        val rejected = listOf(
            "sess_4_JPN",                       // uppercase
            "sess_4_pt-br",                     // punctuation
            "sess_4_jpn. ",                     // trailing punctuation + space
            "sess_4_" + "a".repeat(17),         // over 16 characters
            "sess_4_ jpn",                      // leading space
        )

        for (id in rejected) {
            assertNull("should reject: <$id>", EpisodeId.parse(id))
        }
    }

    @Test
    fun `an arbitrary audio code builds and round trips`() {
        val id = EpisodeId.build("sess", episode(audio = "kor"))

        assertEquals("sess_1_kor", id)
        assertEquals("kor", EpisodeId.parse(id)?.audio)
    }

    @Test
    fun `an episode whose play url is blank still builds a well formed id`() {
        // The shape gate must not depend on how usable the episode is — the
        // STREAM branch decides that separately, after parsing.
        val id = EpisodeId.build("sess", episode().copy(playUrl = ""))
        val parts = EpisodeId.parse(id)

        assertEquals("sess_1_jpn", id)
        assertEquals(true, parts != null)
        assertEquals("sess", parts!!.animeSession)
        assertEquals("1", parts.episodeString)
        assertEquals(EpisodeInfo.AUDIO_JAPANESE, parts.audio)
    }
}
