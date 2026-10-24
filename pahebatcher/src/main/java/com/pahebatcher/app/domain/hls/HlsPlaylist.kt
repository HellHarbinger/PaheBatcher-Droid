package com.pahebatcher.app.domain.hls

/**
 * Represents a parsed HLS playlist.
 *
 * Ported from Python m3u8.py parse_m3u8() and resolve_m3u8().
 *
 * For master playlists: variants contains the list of variant URLs (in source order).
 * For media playlists: segments contains the list of segments.
 *
 * @param isMaster Whether this is a master playlist (contains #EXT-X-STREAM-INF)
 * @param variants Variant playlist URLs (in source order, for master playlists)
 * @param segments Parsed segments (for media playlists)
 */
data class HlsPlaylist(
    val isMaster: Boolean,
    val variants: List<String> = emptyList(),
    val segments: List<HlsSegment> = emptyList()
) {
    companion object {
        /**
         * Create an empty playlist (no segments, no variants).
         */
        fun empty(): HlsPlaylist = HlsPlaylist(isMaster = false)
    }
}
