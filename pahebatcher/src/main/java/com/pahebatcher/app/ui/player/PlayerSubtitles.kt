package com.pahebatcher.app.ui.player

import androidx.media3.common.C
import androidx.media3.common.Tracks

/**
 * SPEC §15 "subtitles where available" — the player's text-track capability,
 * expressed as data the ViewModel derives and the screen renders.
 *
 * The derivation types below are deliberately free of Android and Media3 types
 * so the rules themselves are testable on the JVM
 * (`SubtitleStateDerivationTest`); [subtitleTracksOf] is the one bridge from
 * Media3's [Tracks], and the ViewModel is the only place that applies the
 * resulting selection back onto `Player.trackSelectionParameters`.
 *
 * Scope (bounded on purpose, per the closure brief):
 *
 * * detect the text tracks the current media really publishes;
 * * expose whether subtitles are available at all;
 * * select one, or turn them off;
 * * keep normal playback when there is no text track — and show no control.
 *
 * Deliberately NOT here: subtitle download, persistence, translation, custom
 * rendering, and any in-player quality/audio switching (still out of scope).
 */

/**
 * One text track as the player exposes it, reduced to what the player UI needs.
 *
 * @property groupIndex index of the backing track group inside
 *   `Player.currentTracks.groups` — the identity used to build a
 *   `TrackSelectionOverride`.
 * @property trackIndex index of this track inside its group.
 * @property selected whether this is the track the player currently renders.
 * @property language BCP-47 language, when the manifest published one.
 * @property label the manifest's own label/name, when present.
 * @property supported false for a rendition the decoder/manifest marked
 *   unsupported: such a track is never offered (selecting it would break
 *   playback instead of improving it).
 */
data class SubtitleTrack(
    val groupIndex: Int,
    val trackIndex: Int,
    val selected: Boolean,
    val language: String?,
    val label: String?,
    val supported: Boolean,
)

/**
 * One selectable entry of the subtitle menu.
 *
 * @property groupIndex see [SubtitleTrack.groupIndex].
 * @property trackIndex see [SubtitleTrack.trackIndex].
 * @property label the rendered name; unique within one derivation, so a menu
 *   can address it and the ViewModel can re-find it after a source change.
 */
data class SubtitleOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
)

/**
 * The subtitle section of [PlayerUiState].
 *
 * @property options every selectable text track, in manifest order; empty means
 *   "this media has no subtitles" and is what hides the control entirely.
 * @property selectedIndex index into [options] of the currently rendered track,
 *   `-1` when subtitles are off (or simply not present).
 */
data class PlayerSubtitleState(
    val options: List<SubtitleOption>,
    val selectedIndex: Int,
) {
    val available: Boolean get() = options.isNotEmpty()

    companion object {
        /** No text track at all — the state every non-subtitled source starts in. */
        val NONE = PlayerSubtitleState(emptyList(), -1)
    }
}

/**
 * Derive the subtitle section from the tracks the player reports.
 *
 * Pure by construction: same input → same output, no player, no Android, so the
 * rules below are unit-testable and cannot drift from what the UI shows.
 *
 * Rules:
 *
 * 1. unsupported renditions are dropped — never offered, never selected;
 * 2. a text track is offered only when the media DECLARED a name for it: a
 *    manifest label, a language, or both. This matters because a decoder
 *    manufactures text outputs the manifest never published — instrumented on
 *    device, a subtitle-free MPEG-TS stream still reports one text track, an
 *    unlabelled, language-less CEA-608 caption placeholder that renders
 *    nothing. Offering it would be a control that pretends to do something
 *    (AGENTS §5), so it is dropped here instead;
 * 3. an offered entry is named by the manifest label, else its language;
 * 4. duplicate labels get a ` (2)`/` (3)` suffix so every entry stays
 *    addressable, and because the suffix depends only on manifest order it is
 *    stable across re-derivations — which is what lets the ViewModel re-find the
 *    user's choice after an episode switch;
 * 5. `selectedIndex` reflects what the player is really rendering (the first
 *    selected text track of the offerable list), `-1` when nothing is.
 */
fun deriveSubtitleState(tracks: List<SubtitleTrack>): PlayerSubtitleState {
    val offerable = tracks.filter { it.supported && it.declaresName() }
    if (offerable.isEmpty()) return PlayerSubtitleState.NONE

    val counts = HashMap<String, Int>()
    val options = offerable.map { track ->
        // [declaresName] guarantees at least one of the two is usable, so this
        // base name is never blank.
        val base = track.label?.takeIf { it.isNotBlank() }
            ?: track.language.orEmpty()
        val seen = (counts[base] ?: 0) + 1
        counts[base] = seen
        SubtitleOption(
            groupIndex = track.groupIndex,
            trackIndex = track.trackIndex,
            label = if (seen == 1) base else "$base ($seen)",
        )
    }

    val selected = offerable.indexOfFirst { it.selected }
    return PlayerSubtitleState(options, selected)
}

/**
 * Whether the media declared a name for this text track — a manifest label, a
 * language, or both. Text outputs a decoder manufactures on its own (CEA-608/708
 * closed captions, an id-less, name-less placeholder) carry neither.
 */
private fun SubtitleTrack.declaresName(): Boolean =
    !label.isNullOrBlank() || !language.isNullOrBlank()

/**
 * Map Media3's [Tracks] onto the pure [SubtitleTrack] list that
 * [deriveSubtitleState] consumes.
 *
 * Only [C.TRACK_TYPE_TEXT] groups become entries — a stream with no subtitle
 * rendition simply yields an empty list, which is what hides the control — and
 * a rendition the player reports as unsupported is dropped right here, so the
 * menu can never offer something that would break playback.
 *
 * Top-level (and `internal`) rather than private to the ViewModel so the mapping
 * itself is covered by a deterministic Media3 fixture on the JVM.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun subtitleTracksOf(tracks: Tracks): List<SubtitleTrack> =
    tracks.groups.flatMapIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) {
            emptyList()
        } else {
            (0 until group.length).mapNotNull { trackIndex ->
                if (!group.isTrackSupported(trackIndex)) return@mapNotNull null
                val format = group.getTrackFormat(trackIndex)
                SubtitleTrack(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    selected = group.isTrackSelected(trackIndex),
                    language = format.language,
                    label = format.label,
                    supported = true,
                )
            }
        }
    }
