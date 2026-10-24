package com.pahebatcher.app.domain.hls

import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogUrlSanitizer
import java.net.URI

/**
 * Parser for HLS M3U8 playlists.
 *
 * Ported from Python m3u8.py parse_m3u8() and resolve_m3u8().
 * Handles both master playlists and media playlists.
 *
 * Python reference (m3u8.py lines 13-61):
 * ```python
 * def parse_m3u8(content: str, base_url: str) -> list[dict[str, Any]]:
 *     lines = content.splitlines()
 *     if "#EXT-X-STREAM-INF" in content:
 *         variants = [urljoin(base_url, lines[i + 1].strip())
 *                     for i, line in enumerate(lines)
 *                     if line.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines)]
 *         if variants:
 *             return []  # caller must re-fetch the variant
 *     segments = []
 *     key_url = None; key_iv = None; seq_num = 0
 *     for line in lines:
 *         line = line.strip()
 *         if not line: continue
 *         if line.startswith("#EXT-X-MEDIA-SEQUENCE:"):
 *             seq_num = int(line.split(":", 1)[1])
 *         elif line.startswith("#EXT-X-KEY:"):
 *             # parse key attributes
 *         elif not line.startswith("#"):
 *             iv = key_iv or (seq_num.to_bytes(16, "big") if key_url else None)
 *             segments.append({"url": urljoin(base_url, line), "key_url": key_url, "iv": iv})
 *             seq_num += 1
 *     return segments
 * ```
 *
 * HLS tags handled:
 * - #EXTM3U (playlist header, informational)
 * - #EXT-X-STREAM-INF (master playlist variant detection)
 * - #EXT-X-MEDIA-SEQUENCE (starting sequence number)
 * - #EXT-X-KEY (AES-128 encryption: METHOD, URI, IV)
 * - #EXT-X-ENDLIST (playlist complete marker)
 * - #EXTINF (segment duration, informational)
 *
 * Tags NOT handled (verified: Python does not use them):
 * - #EXT-X-DISCONTINUITY (not present in Python codebase)
 * - #EXT-X-BYTERANGE (not present in Python codebase)
 * - #EXT-X-TARGETDURATION (informational only)
 */
object HlsPlaylistParser {

    // NET-005: compiled once — this used to build a fresh Regex for every
    // EXT-X-KEY line parsed.
    private val KEY_ATTRIBUTE_PATTERN = Regex("""([^,="]+)=(?:"([^"]*)"|([^,]*))""")

    private const val TAG = "HlsPlaylistParser"

    /**
     * Parse an M3U8 playlist content.
     *
     * If the playlist is a master playlist (contains #EXT-X-STREAM-INF),
     * returns a playlist with variant URLs but empty segments.
     * The caller must then fetch the selected variant and parse it.
     *
     * @param content Raw M3U8 text content
     * @param baseUrl Base URL for resolving relative URLs
     * @return Parsed HlsPlaylist
     */
    fun parse(content: String, baseUrl: String): HlsPlaylist {
        val lines = content.lines()

        // Check for master playlist
        if (content.contains("#EXT-X-STREAM-INF")) {
            val variants = mutableListOf<String>()
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.size) {
                    val variantLine = lines[i + 1].trim()
                    if (variantLine.isNotEmpty() && !variantLine.startsWith("#")) {
                        variants.add(resolveUrl(baseUrl, variantLine))
                    }
                }
            }
            if (variants.isNotEmpty()) {
                return HlsPlaylist(isMaster = true, variants = variants)
            }
        }

        // Parse media playlist
        val segments = mutableListOf<HlsSegment>()
        var keyUrl: String? = null
        var keyIv: ByteArray? = null
        var seqNum = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    seqNum = trimmed.substringAfter(":").toIntOrNull() ?: 0
                }
                trimmed.startsWith("#EXT-X-KEY:") -> {
                    val keyInfo = parseKeyAttribute(trimmed, baseUrl)
                    keyUrl = keyInfo?.url
                    keyIv = keyInfo?.iv
                }
                !trimmed.startsWith("#") -> {
                    val iv = keyIv ?: (if (keyUrl != null) deriveIvFromSequence(seqNum) else null)
                    segments.add(
                        HlsSegment(
                            url = resolveUrl(baseUrl, trimmed),
                            keyUrl = keyUrl,
                            iv = iv,
                            index = seqNum
                        )
                    )
                    seqNum++
                }
            }
        }

        return HlsPlaylist(isMaster = false, segments = segments)
    }

    /**
     * Parse #EXT-X-KEY attribute string.
     *
     * Python reference (m3u8.py lines 37-51):
     * ```python
     * elif line.startswith("#EXT-X-KEY:"):
     *     kv = {k: vq or vr
     *            for k, vq, vr in re.findall(r'([^,="]+)=(?:"([^"]+)"|([^,]+))', line[11:])}
     *     if kv.get("METHOD") == "AES-128":
     *         if "URI" in kv:
     *             key_url = urljoin(base_url, kv["URI"])
     *         if iv_hex := kv.get("IV"):
     *             h = iv_hex.lstrip("0xX").lstrip("0X")
     *             key_iv = bytes.fromhex(("0" + h) if len(h) % 2 else h)
     *         else:
     *             key_iv = None
     *     else:
     *         key_url = key_iv = None
     * ```
     */
    private fun parseKeyAttribute(line: String, baseUrl: String): HlsKeyInfo? {
        val attrs = line.substringAfter(":")
        val kv = mutableMapOf<String, String>()

        // Parse comma-separated key=value pairs
        // Values can be quoted: URI="https://..." or unquoted: METHOD=AES-128
        for (match in KEY_ATTRIBUTE_PATTERN.findAll(attrs)) {
            val key = match.groupValues[1].trim()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            kv[key] = value
        }

        val method = kv["METHOD"] ?: return null
        if (method != "AES-128") return null

        val keyUrl = kv["URI"]?.let { resolveUrl(baseUrl, it) } ?: return null
        val ivHex = kv["IV"]

        val iv = if (ivHex != null) {
            // HLS spec: IV is a 128-bit value (16 bytes / 32 hex chars).
            // Remove only the optional 0x/0X prefix. Do NOT strip leading zeros.
            // Pad shorter hex values on the left to 32 hex characters.
            //
            // Python lstrip("0xX") has a bug where it strips leading zeros from the
            // hex value itself. Android fixes this per HLS spec and AES requirements.
            val hex = if (ivHex.startsWith("0x", ignoreCase = true)) ivHex.substring(2) else ivHex
            val padded = hex.padStart(32, '0')
            hexToBytes(padded)
        } else {
            null
        }

        return HlsKeyInfo(url = keyUrl, iv = iv)
    }

    /**
     * Derive IV from media sequence number.
     *
     * Python reference (m3u8.py line 53):
     * ```python
     * iv = key_iv or (seq_num.to_bytes(16, "big") if key_url else None)
     * ```
     */
    private fun deriveIvFromSequence(seqNum: Int): ByteArray {
        val iv = ByteArray(16)
        var value = seqNum.toLong()
        for (i in 15 downTo 0) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    /**
     * Resolve a URL against a base URL.
     * Handles both relative and absolute URLs.
     * Preserves query parameters.
     */
    private fun resolveUrl(baseUrl: String, relative: String): String {
        // If already absolute, return as-is
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }

        return try {
            val baseUri = URI(baseUrl)
            val resolved = baseUri.resolve(relative)
            resolved.toString()
        } catch (e: Exception) {
            // WARN is un-gated in release, so neither operand may carry the
            // full stream URL: `baseUrl` is the expiring CDN playlist URL (its
            // query is its authorisation), `relative` may quote one too, and a
            // URI parser's own message embeds the input it choked on — which is
            // why the parser detail goes through `diagnostic`, not `message`
            // (a malformed URL's tail is outside any scheme match). Host+path
            // survive, so the line still says WHICH object failed.
            AppLog.w(
                TAG,
                "URL resolution failed for baseUrl=${LogUrlSanitizer.url(baseUrl)}, " +
                    "relative=${LogUrlSanitizer.diagnostic(relative)}: " +
                    LogUrlSanitizer.diagnostic(e.message),
            )
            // Fallback: simple string concatenation
            if (baseUrl.endsWith("/")) {
                "$baseUrl$relative"
            } else {
                "$baseUrl/$relative"
            }
        }
    }

    /**
     * Convert hex string to byte array.
     * Each two hex characters = one byte.
     * Returns empty array for empty input.
     * Non-hex characters are treated as 0.
     */
    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hexByte = hex.substring(i * 2, i * 2 + 2)
            bytes[i] = try {
                hexByte.toInt(16).toByte()
            } catch (e: NumberFormatException) {
                0
            }
        }
        return bytes
    }
}
