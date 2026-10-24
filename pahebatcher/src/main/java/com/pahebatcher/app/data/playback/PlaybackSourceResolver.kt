package com.pahebatcher.app.data.playback

import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity

/**
 * Pure playback-source resolution (PHASE11_ARCHITECTURE_V2 §25).
 *
 * The player plays local media only:
 *  - `outputUri` (MediaStore `content://` copy in `Movies/PaheBatcher/`, §27) is
 *    PREFERRED once present — it survives local-file export cleanup;
 *  - `outputPath` (the assembled MP4 under `filesDir/downloads/`, Phase 9/10) is
 *    the pre-export fallback and the v1 primary source verified today;
 *  - a row with neither has nothing to play → `null` (never a fabricated URL,
 *    AGENTS §5/§10).
 *
 * Returns a plain `String` (never `android.net.Uri`) so the rule is a pure,
 * directly JVM-testable function (§36 unit-test surface).
 */
object PlaybackSourceResolver {

    /** Best playable source for [episode], or null when the row has no local media. */
    fun sourceFor(episode: EpisodeDownloadEntity): String? =
        episode.outputUri?.takeIf { it.isNotBlank() }
            ?: episode.outputPath?.takeIf { it.isNotBlank() }
}
