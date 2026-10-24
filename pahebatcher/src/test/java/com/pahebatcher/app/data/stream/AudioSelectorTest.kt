package com.pahebatcher.app.data.stream

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AudioSelector — the open-set, exact-match audio model (SPEC §22).
 *
 * The former boolean bridge (`isTargetDub`, `getAudioLanguage`,
 * `getEffectiveAudio`) is INVERTED here, not deleted: those APIs reduced the
 * source's audio to a SUB/DUB boolean and re-derived `jpn`/`eng` from it —
 * the closed model and a forced-label source this slice removes. What replaces
 * them is exact string comparison plus one honest failure message.
 */
class AudioSelectorTest {

    @Test
    fun `matches is exact — eng matches only eng`() {
        assertTrue(AudioSelector.matches("eng", "eng"))
        assertFalse(AudioSelector.matches("eng", "jpn"))
    }

    @Test
    fun `matches is exact — jpn matches only jpn`() {
        assertTrue(AudioSelector.matches("jpn", "jpn"))
        assertFalse(AudioSelector.matches("jpn", "eng"))
    }

    @Test
    fun `matches treats arbitrary open-set codes exactly like the built-ins`() {
        assertTrue(AudioSelector.matches("kor", "kor"))
        assertFalse(AudioSelector.matches("kor", "jpn"))
        assertFalse(AudioSelector.matches("jpn", "kor"))
    }

    @Test
    fun `unknown never matches an empty or missing audio`() {
        assertFalse(AudioSelector.matches("jpn", ""))
        assertFalse(AudioSelector.matches("", "jpn"))
        assertTrue(AudioSelector.matches("", ""))
    }

    @Test
    fun `unavailableMessage names the requested audio and states there was no substitution`() {
        val message = AudioSelector.unavailableMessage("kor")
        assertTrue(message.contains("kor"))
        assertTrue(message.contains("not available"))
    }

    @Test
    fun `constants match the source codes the parser emits`() {
        assertEquals("jpn", AudioSelector.AUDIO_JAPANESE)
        assertEquals("eng", AudioSelector.AUDIO_ENGLISH)
    }
}
