package com.pahebatcher.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AQR-138 — the `player:` notification target's EXPLICIT playback mode.
 *
 * A media notification cannot say whether its episode is playing from a local
 * download or from a direct HLS stream, so the target carries the mode itself.
 * These tests pin the whole contract of [PlayerTarget]:
 *
 *  - both modes encode and parse back unchanged (round trip);
 *  - the pre-AQR-138 `player:<episodeId>` form still means DOWNLOAD
 *    (DECISIONS 118.2 compatibility);
 *  - blank ids, blank modes and unknown mode tokens are REJECTED, never
 *    silently reinterpreted as a mode the target did not name;
 *  - targets outside the `player:` prefix are not this parser's to read.
 *
 * Pure JVM — the parser is a plain string contract with no Android dependency,
 * so the invalid-input half of the AQR-138 gate is exercised on every `test`
 * run, not only on a device.
 */
class PlayerTargetTest {

    @Test
    fun `an explicit stream target parses to STREAM`() {
        val target = PlayerTarget.parse("player:anime-session_13_jpn:stream")

        assertEquals(PlayerTarget("anime-session_13_jpn", PlayerMode.STREAM), target)
        assertEquals(PlayerMode.STREAM, target?.mode)
    }

    @Test
    fun `an explicit download target parses to DOWNLOAD`() {
        val target = PlayerTarget.parse("player:anime-session_13_jpn:download")

        assertEquals(PlayerTarget("anime-session_13_jpn", PlayerMode.DOWNLOAD), target)
        assertEquals(PlayerMode.DOWNLOAD, target?.mode)
    }

    @Test
    fun `the pre-AQR-138 bare form still resolves to DOWNLOAD`() {
        val target = PlayerTarget.parse("player:anime-session_13_jpn")

        assertEquals(PlayerTarget("anime-session_13_jpn", PlayerMode.DOWNLOAD), target)
    }

    @Test
    fun `both modes round trip through encode and parse`() {
        for (mode in PlayerMode.entries) {
            val encoded = PlayerTarget.encode("anime-session_1_jpn", mode)

            assertEquals(PlayerTarget("anime-session_1_jpn", mode), PlayerTarget.parse(encoded))
            assertEquals(mode, PlayerTarget.parse(encoded)?.mode)
        }
    }

    @Test
    fun `the two modes encode to two distinct targets`() {
        assertNotEquals(
            PlayerTarget.encode("anime-session_1_jpn", PlayerMode.DOWNLOAD),
            PlayerTarget.encode("anime-session_1_jpn", PlayerMode.STREAM),
        )
    }

    @Test
    fun `the encoding always names a mode explicitly`() {
        for (mode in PlayerMode.entries) {
            assertEquals(
                "player:anime-session_1_jpn:${if (mode == PlayerMode.STREAM) "stream" else "download"}",
                PlayerTarget.encode("anime-session_1_jpn", mode),
            )
        }
    }

    @Test
    fun `an unsupported mode token is rejected rather than guessed`() {
        assertNull(PlayerTarget.parse("player:anime-session_1_jpn:loud"))
        assertNull(PlayerTarget.parse("player:anime-session_1_jpn:STREAM")) // case-sensitive
        assertNull(PlayerTarget.parse("player:anime-session_1_jpn:Streaming"))
        assertNull(PlayerTarget.parse("player:anime-session_1_jpn:1"))
    }

    @Test
    fun `a trailing separator is not a mode`() {
        assertNull(PlayerTarget.parse("player:anime-session_1_jpn:"))
    }

    @Test
    fun `a blank episode id is rejected in every shape`() {
        assertNull(PlayerTarget.parse("player:"))
        assertNull(PlayerTarget.parse("player:   "))
        assertNull(PlayerTarget.parse("player::stream"))
        assertNull(PlayerTarget.parse("player::download"))
        assertNull(PlayerTarget.parse("player::"))
    }

    @Test
    fun `targets outside the player prefix are not read as player targets`() {
        assertNull(PlayerTarget.parse("downloads"))
        assertNull(PlayerTarget.parse("watchlist"))
        assertNull(PlayerTarget.parse("replace:anime-session_1_jpn"))
        assertNull(PlayerTarget.parse("not-a-known-target"))
        assertNull(PlayerTarget.parse(""))
    }

    @Test
    fun `ids containing underscores round trip unchanged`() {
        // Episode ids are `{session}_{epStr}_{audio}` — they contain `_` (and
        // test ids contain `-`), never `:`, so the LAST separator always
        // separates the id from the mode token.
        val id = "p11-ep-123456789-1"

        assertEquals(
            PlayerTarget(id, PlayerMode.DOWNLOAD),
            PlayerTarget.parse(PlayerTarget.encode(id, PlayerMode.DOWNLOAD)),
        )
        assertEquals(
            PlayerTarget(id, PlayerMode.STREAM),
            PlayerTarget.parse(PlayerTarget.encode(id, PlayerMode.STREAM)),
        )
        assertEquals(
            "player:p11-ep-123456789-1:stream",
            PlayerTarget.encode(id, PlayerMode.STREAM),
        )
    }

    @Test
    fun `the prefix is the documented §19 player prefix`() {
        assertEquals("player:", PlayerTarget.PREFIX)
    }
}
