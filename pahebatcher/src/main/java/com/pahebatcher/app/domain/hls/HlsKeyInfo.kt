package com.pahebatcher.app.domain.hls

/**
 * Represents AES-128 encryption key information from an HLS playlist.
 *
 * Ported from Python m3u8.py parse_m3u8() key handling.
 *
 * @param url URL to fetch the 16-byte key
 * @param iv Optional explicit IV (16 bytes). If null, IV is derived from media sequence.
 */
data class HlsKeyInfo(
    val url: String,
    val iv: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HlsKeyInfo) return false
        return url == other.url && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        return result
    }
}
