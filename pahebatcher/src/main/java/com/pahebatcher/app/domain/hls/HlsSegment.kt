package com.pahebatcher.app.domain.hls

/**
 * Represents a single segment in an HLS media playlist.
 *
 * Ported from Python m3u8.py parse_m3u8() segment dict.
 * Each segment contains the URL and optional encryption information.
 *
 * @param url Resolved absolute URL for the segment
 * @param keyUrl URL to fetch the AES-128 encryption key, or null if unencrypted
 * @param iv 16-byte initialization vector for AES-128 CBC decryption, or null if unencrypted
 * @param index Segment index (0-based, derived from media sequence + position)
 */
data class HlsSegment(
    val url: String,
    val keyUrl: String? = null,
    val iv: ByteArray? = null,
    val index: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HlsSegment) return false
        return url == other.url && keyUrl == other.keyUrl &&
            iv.contentEquals(other.iv) && index == other.index
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (keyUrl?.hashCode() ?: 0)
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        result = 31 * result + index
        return result
    }
}
