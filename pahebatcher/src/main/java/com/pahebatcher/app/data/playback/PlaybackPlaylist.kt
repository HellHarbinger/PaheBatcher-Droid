package com.pahebatcher.app.data.playback

import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity

/**
 * Pure playlist construction for the in-ViewModel player playlist
 * (PHASE11_ARCHITECTURE_V2 §23/§24.2 — Python parity: `stream.py` playlist `n/p`).
 *
 * Rules (all table-testable, no I/O):
 *  - only `COMPLETED` rows are playable — in-flight/failed/cancelled siblings are
 *    excluded (the player never points at partial segments);
 *  - rows without a resolvable source ([PlaybackSourceResolver]) are excluded —
 *    there is no honest MediaItem to build for them;
 *  - ordered by `episodeNumber` ascending, so Next/Previous walk episodes in
 *    release order exactly like the reference playlist.
 */
object PlaybackPlaylist {

    /** Playable playlist for a session's rows: completed + source + episode order. */
    fun completedPlayable(rows: List<EpisodeDownloadEntity>): List<EpisodeDownloadEntity> =
        rows
            .filter { it.state == EpisodeDownloadEntity.STATE_COMPLETED }
            .filter { PlaybackSourceResolver.sourceFor(it) != null }
            .sortedBy { it.episodeNumber }

    /**
     * Same rules, narrowed to ONE audio variant: a local playlist is seeded by
     * an episode row and must contain only rows of the SEED's audio
     * (audio-variant slice, SPEC §15 — "next"/"previous" never crosses from a
     * SUB episode into the DUB recording of the same number, and a mixed-audio
     * download history cannot drag the player onto a different track).
     *
     * Rows whose [EpisodeDownloadEntity.audio] differs from [seedAudio] are
     * dropped; everything else follows [completedPlayable] exactly. Download
     * mode has no per-row audio choice (each row was created from one specific
     * variant), so this filters what already exists and never changes a row.
     */
    fun completedPlayable(
        rows: List<EpisodeDownloadEntity>,
        seedAudio: String,
    ): List<EpisodeDownloadEntity> = completedPlayable(rows).filter { it.audio == seedAudio }
}
