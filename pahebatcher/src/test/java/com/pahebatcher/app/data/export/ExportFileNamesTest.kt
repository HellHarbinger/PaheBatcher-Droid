package com.pahebatcher.app.data.export

import com.pahebatcher.app.data.hls.FileNameGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice A (per-audio export identity) — the §36 unit surface for the export
 * leaf name. These rules are what stop two audio variants of one episode from
 * ever exporting to the same destination name.
 *
 * Local staging (`FileNameGenerator`, the private segment directory and the
 * assembled MP4 inside it) is deliberately OUT of scope here and must stay
 * Python-identical — [ExportFileNames.legacyName] pins that it does.
 */
class ExportFileNamesTest {

    // ------------------------------------------------------------------
    // The audio discriminator
    // ------------------------------------------------------------------

    @Test
    fun `exportedName embeds the audio discriminator before the extension`() {
        assertEquals(
            "Ep_001_-_Title_[jpn].mp4",
            ExportFileNames.exportedName("001", "Title", "jpn"),
        )
        assertEquals(
            "Ep_013.5_-_Clash_of_Kings_[eng].mp4",
            ExportFileNames.exportedName("013.5", "Clash of Kings", "eng"),
        )
    }

    @Test
    fun `different audio values never share a name`() {
        val audios = listOf("jpn", "eng", "sub", "jpn2", "engsdh")
        val names = audios.map { ExportFileNames.exportedName("001", "Title", it) }
        assertEquals(
            "one distinct name per audio variant",
            audios.size,
            names.toSet().size,
        )
        for (audio in audios) {
            assertNotEquals(
                "an audio variant must not fall back to the shared legacy name",
                ExportFileNames.legacyName("001", "Title"),
                ExportFileNames.exportedName("001", "Title", audio),
            )
        }
    }

    @Test
    fun `the name is a pure function of its arguments — order cannot affect it`() {
        // Exporting jpn first and then eng, or eng first and then jpn, must ask
        // for the identical destination names: nothing else is read.
        val jpnFirst = ExportFileNames.exportedName("001", "Title", "jpn")
        val engSecond = ExportFileNames.exportedName("001", "Title", "eng")
        val engFirst = ExportFileNames.exportedName("001", "Title", "eng")
        val jpnSecond = ExportFileNames.exportedName("001", "Title", "jpn")

        assertEquals(jpnFirst, jpnSecond)
        assertEquals(engSecond, engFirst)
        assertNotEquals(jpnFirst, engFirst)
        // Unrelated calls in between change nothing.
        assertEquals(jpnFirst, ExportFileNames.exportedName("001", "Title", "jpn"))
    }

    @Test
    fun `a different episode number or title cannot be confused with another`() {
        assertNotEquals(
            ExportFileNames.exportedName("001", "Title", "jpn"),
            ExportFileNames.exportedName("002", "Title", "jpn"),
        )
        assertNotEquals(
            ExportFileNames.exportedName("001", "Title", "jpn"),
            ExportFileNames.exportedName("001", "Other", "jpn"),
        )
    }

    // ------------------------------------------------------------------
    // Legacy name = today's Python-parity name (the alias target)
    // ------------------------------------------------------------------

    @Test
    fun `legacyName is exactly the FileNameGenerator output every old export used`() {
        assertEquals(FileNameGenerator.episodeFileName("001", "Title"), ExportFileNames.legacyName("001", "Title"))
        assertEquals(
            FileNameGenerator.episodeFileName("013.5", "Clash of Kings"),
            ExportFileNames.legacyName("013.5", "Clash of Kings"),
        )
        assertEquals("Ep_001_-_Title.mp4", ExportFileNames.legacyName("001", "Title"))
        assertEquals("Ep_013.5_-_Clash_of_Kings.mp4", ExportFileNames.legacyName("013.5", "Clash of Kings"))
    }

    @Test
    fun `the suffix never alters the sanitized stem the local assembler uses`() {
        val stem = FileNameGenerator.episodeFileName("001", "Title").removeSuffix(".mp4")
        assertEquals(
            "${stem}_[jpn].mp4",
            ExportFileNames.exportedName("001", "Title", "jpn"),
        )
    }

    // ------------------------------------------------------------------
    // Sanitization
    // ------------------------------------------------------------------

    @Test
    fun `unsafe audio characters are stripped so the bracket structure stays unambiguous`() {
        // The bracket wrap is applied AFTER sanitizing, and sanitize() removes
        // every character outside [letters, digits, underscore, whitespace, -, .]
        // before collapsing spaces and underscore runs — so `j p/n\eng` becomes
        // `j_pneng` (no underscore is invented for the stripped separators).
        assertEquals(
            ExportFileNames.exportedName("001", "Title", "jpn"),
            ExportFileNames.exportedName("001", "Title", "j[p]n"),
        )
        assertEquals(
            "Ep_001_-_Title_[j_pneng].mp4",
            ExportFileNames.exportedName("001", "Title", "j p/n\\eng"),
        )
        assertEquals(
            "Ep_001_-_Title_[eng_sd_h].mp4",
            ExportFileNames.exportedName("001", "Title", "eng  sd   h"),
        )
    }

    @Test
    fun `production audio codes survive sanitization unchanged`() {
        // The `EpisodeId` shape gate (^[a-z0-9]{1,16}$) is what makes sanitize an
        // identity for audio, so every distinct production code yields a
        // distinct token. Values OUTSIDE the gate can collapse instead — see
        // `unsafe audio characters are stripped …`.
        for (code in listOf("jpn", "eng", "sub", "jpn2", "engsdh", "a1", "0")) {
            assertEquals(
                "sanitize must be the identity for a gated audio code ($code)",
                "Ep_001_-_Title_[$code].mp4",
                ExportFileNames.exportedName("001", "Title", code),
            )
        }
    }

    @Test
    fun `non-ASCII titles keep their characters and gain the suffix`() {
        assertEquals(
            "Ep_001_-_Caf\u00e9_[jpn].mp4",
            ExportFileNames.exportedName("001", "Caf\u00e9", "jpn"),
        )
    }

    @Test
    fun `blank or punctuation-only audio falls back to the legacy name`() {
        assertEquals(
            ExportFileNames.legacyName("001", "Title"),
            ExportFileNames.exportedName("001", "Title", ""),
        )
        assertEquals(
            ExportFileNames.legacyName("001", "Title"),
            ExportFileNames.exportedName("001", "Title", "///"),
        )
        assertEquals("Ep_001_-_Title.mp4", ExportFileNames.exportedName("001", "Title", ""))
    }

    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------

    @Test
    fun `the result always keeps a single mp4 extension`() {
        val name = ExportFileNames.exportedName("001", "Title.1080p", "jpn")
        assertTrue(name.endsWith(".mp4"))
        assertEquals(1, name.split(".mp4").size - 1)
        assertFalse(name.contains(".."))
    }

    @Test
    fun `a suffixed name is never a prefix-extension of the legacy name`() {
        // Guards the alias matching: MediaStore ` (n)` and SAF NFC rules treat
        // names by whole string, so the two must simply be different strings.
        val legacy = ExportFileNames.legacyName("001", "Title")
        val suffixed = ExportFileNames.exportedName("001", "Title", "jpn")
        assertNotEquals(legacy, suffixed)
        assertFalse(suffixed.startsWith("$legacy."))
    }
}
