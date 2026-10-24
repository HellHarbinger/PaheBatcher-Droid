package com.pahebatcher.app.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §15 "subtitles where available" — the derivation that decides what the
 * subtitle control shows, what it can select, and whether it shows at all.
 *
 * Two layers are pinned here:
 *
 * 1. **the pure rules** ([deriveSubtitleState]) — labels, de-duplication,
 *    selection, dropping unsupported renditions, "no text track → no state";
 * 2. **the Media3 bridge** ([subtitleTracksOf]) — built from real `Tracks` /
 *    `TrackGroup` / `Format` objects (a deterministic Media3 fixture, no
 *    network, no player), so the mapping from a live `Player.currentTracks`
 *    to those rules is verified rather than assumed.
 *
 * Playback itself is verified on the device by
 * `SubtitleFixturePlaybackDeviceTest`; this file proves the logic the UI
 * and the ViewModel run.
 */
class SubtitleStateDerivationTest {

    // ---------------------------------------------------------------- layer 1

    @Test
    fun no_text_track_yields_no_state_and_no_control() {
        val state = deriveSubtitleState(emptyList())

        assertFalse("a media without subtitles must expose no control", state.available)
        assertEquals(emptyList<SubtitleOption>(), state.options)
        assertEquals(-1, state.selectedIndex)
        assertEquals(PlayerSubtitleState.NONE, state)
    }

    @Test
    fun every_unsupported_rendition_yields_no_state() {
        val state = deriveSubtitleState(
            listOf(
                track(groupIndex = 0, trackIndex = 0, selected = true, supported = false),
                track(groupIndex = 0, trackIndex = 1, selected = false, supported = false),
            ),
        )

        assertFalse("offering an unplayable rendition would break playback", state.available)
        assertEquals(-1, state.selectedIndex)
    }

    @Test
    fun the_manifest_label_wins_over_the_language() {
        val state = deriveSubtitleState(
            listOf(track(groupIndex = 0, trackIndex = 0, label = "English (SDH)", language = "en")),
        )

        assertEquals(listOf("English (SDH)"), state.options.map { it.label })
    }

    @Test
    fun a_blank_label_falls_back_to_the_language() {
        val state = deriveSubtitleState(
            listOf(track(groupIndex = 0, trackIndex = 0, label = "  ", language = "ja")),
        )

        assertEquals(listOf("ja"), state.options.map { it.label })
    }

    @Test
    fun aTextTrackTheMediaNeverNamedIsNotOffered() {
        // An MPEG-TS decoder always exposes a CEA-608 caption output the manifest
        // never published — instrumented on the device, a subtitle-free stream
        // still reports one text track, id-less and name-less. It renders
        // nothing and has no name to show, so it must never become a control
        // (AGENTS §5).
        val unnamed = listOf(track(groupIndex = 1, trackIndex = 0, label = null, language = null))

        assertEquals(PlayerSubtitleState.NONE, deriveSubtitleState(unnamed))
        assertEquals(
            "blank counts as unnamed too",
            PlayerSubtitleState.NONE,
            deriveSubtitleState(
                listOf(track(groupIndex = 1, trackIndex = 0, label = "   ", language = "")),
            ),
        )

        // …and when the media DID publish a rendition, the unnamed placeholder
        // neither takes a slot nor shifts the group/track identity of that
        // rendition.
        assertEquals(
            listOf(SubtitleOption(2, 0, "English")),
            deriveSubtitleState(
                unnamed + listOf(track(groupIndex = 2, trackIndex = 0, label = "English")),
            ).options,
        )
    }

    @Test
    fun duplicate_labels_stay_addressable_and_keep_manifest_order() {
        val tracks = listOf(
            track(groupIndex = 2, trackIndex = 0, label = "English"),
            track(groupIndex = 3, trackIndex = 0, label = "English"),
            track(groupIndex = 4, trackIndex = 0, label = "English"),
            track(groupIndex = 5, trackIndex = 0, label = "日本語"),
        )
        val state = deriveSubtitleState(tracks)

        assertEquals(
            listOf("English", "English (2)", "English (3)", "日本語"),
            state.options.map { it.label },
        )
        // Re-deriving the same input must produce the same labels — that is what
        // lets a recorded choice be re-found after an episode switch.
        assertEquals("re-derivation must be stable", state, deriveSubtitleState(tracks))
    }

    @Test
    fun group_and_track_indices_are_preserved_verbatim() {
        val state = deriveSubtitleState(
            listOf(
                track(groupIndex = 7, trackIndex = 3, label = "English"),
                track(groupIndex = 9, trackIndex = 1, label = "Français"),
            ),
        )

        assertEquals(
            listOf(SubtitleOption(7, 3, "English"), SubtitleOption(9, 1, "Français")),
            state.options,
        )
    }

    @Test
    fun selection_follows_the_rendered_track() {
        val selected = deriveSubtitleState(
            listOf(
                track(groupIndex = 0, trackIndex = 0, label = "English", selected = false),
                track(groupIndex = 0, trackIndex = 1, label = "日本語", selected = true),
            ),
        )
        val none = deriveSubtitleState(
            listOf(
                track(groupIndex = 0, trackIndex = 0, label = "English", selected = false),
                track(groupIndex = 0, trackIndex = 1, label = "日本語", selected = false),
            ),
        )

        assertEquals(1, selected.selectedIndex)
        assertEquals(-1, none.selectedIndex)
        assertTrue("availability does not imply a selection", none.available)
    }

    @Test
    fun an_unsupported_rendition_cannot_be_the_selection() {
        val state = deriveSubtitleState(
            listOf(
                track(groupIndex = 0, trackIndex = 0, label = "English", selected = true, supported = false),
                track(groupIndex = 0, trackIndex = 1, label = "Français", selected = false, supported = true),
            ),
        )

        // The unsupported track is dropped, so the indices are computed over the
        // offerable list: Français becomes index 0 and nothing is selected.
        assertEquals(listOf("Français"), state.options.map { it.label })
        assertEquals(-1, state.selectedIndex)
    }

    // ---------------------------------------------------------------- layer 2

    @Test
    fun a_video_group_is_never_a_subtitle_and_indices_are_not_renumbered() {
        val tracks = Tracks(
            listOf(
                videoGroup(format(id = "v0", mime = MimeTypes.VIDEO_H264, label = "1080p")),
                textGroup(
                    format(id = "s0", mime = MimeTypes.TEXT_VTT, label = "English", language = "en"),
                    supported = intArrayOf(C.FORMAT_HANDLED),
                    selected = booleanArrayOf(true),
                ),
            ),
        )

        val bridged = subtitleTracksOf(tracks)

        assertEquals("only the text group may be offered", 1, bridged.size)
        // index 1 in Player.currentTracks.groups — renumbering to 0 would build a
        // TrackSelectionOverride against the wrong group later.
        assertEquals(1, bridged.single().groupIndex)
        assertEquals(0, bridged.single().trackIndex)
        assertEquals("English", bridged.single().label)
        assertEquals("en", bridged.single().language)
        assertTrue(bridged.single().selected)
        assertTrue(bridged.single().supported)
    }

    @Test
    fun a_media_without_any_text_group_has_no_options_at_all() {
        val tracks = Tracks(
            listOf(
                videoGroup(format(id = "v0", mime = MimeTypes.VIDEO_H264, label = "1080p")),
                videoGroup(format(id = "v1", mime = MimeTypes.VIDEO_H264, label = "720p")),
            ),
        )

        val state = deriveSubtitleState(subtitleTracksOf(tracks))

        assertEquals(PlayerSubtitleState.NONE, state)
        assertFalse(state.available)
    }

    @Test
    fun an_unsupported_rendition_never_reaches_the_menu() {
        val tracks = Tracks(
            listOf(
                textGroup(
                    format(id = "s0", mime = MimeTypes.TEXT_VTT, label = "English"),
                    supported = intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
                    selected = booleanArrayOf(false),
                ),
            ),
        )

        val bridged = subtitleTracksOf(tracks)

        assertTrue(
            "an unplayable rendition is dropped at the bridge, before any menu can see it",
            bridged.isEmpty(),
        )
        assertFalse(
            "an unplayable rendition must not be offered",
            deriveSubtitleState(bridged).available,
        )
    }

    @Test
    fun the_real_fixture_path_reaches_the_menu_and_a_selection() {
        val tracks = Tracks(
            listOf(
                videoGroup(format(id = "v0", mime = MimeTypes.VIDEO_H264)),
                textGroup(
                    format(id = "s0", mime = MimeTypes.TEXT_VTT, label = "English", language = "en"),
                    supported = intArrayOf(C.FORMAT_HANDLED),
                    selected = booleanArrayOf(true),
                ),
                textGroup(
                    format(id = "s1", mime = MimeTypes.TEXT_VTT, label = "English", language = "en"),
                    supported = intArrayOf(C.FORMAT_HANDLED),
                    selected = booleanArrayOf(false),
                ),
            ),
        )

        val state = deriveSubtitleState(subtitleTracksOf(tracks))

        assertTrue(state.available)
        assertEquals(listOf("English", "English (2)"), state.options.map { it.label })
        // group 1 track 0 (selected) and group 2 track 0 (not selected)
        assertEquals(listOf(1, 2), state.options.map { it.groupIndex })
        assertEquals(0, state.selectedIndex)
    }

    // -------------------------------------------------------------- fixtures

    private fun track(
        groupIndex: Int,
        trackIndex: Int,
        label: String? = "English",
        language: String? = "en",
        selected: Boolean = false,
        supported: Boolean = true,
    ) = SubtitleTrack(
        groupIndex = groupIndex,
        trackIndex = trackIndex,
        selected = selected,
        language = language,
        label = label,
        supported = supported,
    )

    private fun format(
        id: String,
        mime: String,
        label: String? = null,
        language: String? = null,
    ): Format =
        Format.Builder()
            .setId(id)
            .setSampleMimeType(mime)
            .setLabel(label)
            .setLanguage(language)
            .build()

    private fun videoGroup(format: Format): Tracks.Group =
        Tracks.Group(
            TrackGroup(format.id.orEmpty(), format),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )

    private fun textGroup(
        format: Format,
        supported: IntArray,
        selected: BooleanArray,
    ): Tracks.Group =
        Tracks.Group(
            TrackGroup(format.id.orEmpty(), format),
            false,
            supported,
            selected,
        )
}
