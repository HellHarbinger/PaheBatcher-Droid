package com.pahebatcher.app.data.stream

/**
 * Decode eval-based JS packer (p,a,c,k,e,d) used by Kwik.
 *
 * Ported from Python kwik.py JsPacker.unpack().
 * This is the standard Dean Edwards / Base62 packer used by Kwik to obfuscate
 * the M3U8 URL extraction JavaScript.
 *
 * Python reference (kwik.py lines 21-44):
 * ```python
 * class JsPacker:
 *     @staticmethod
 *     def unpack(packed: str) -> str:
 *         m = re.search(
 *             r"\}\s*\(\s*'(.*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)",
 *             packed, re.DOTALL,
 *         )
 *         if not m:
 *             return packed
 *         payload, base_s, count_s, mapping_s = m.groups()
 *         base, count = int(base_s), int(count_s)
 *         mapping = mapping_s.split("|")
 *         digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
 *
 *         def enc(c: int) -> str:
 *             return digits[c] if c < base else enc(c // base) + digits[c % base]
 *
 *         lookup = {
 *             enc(i): (mapping[i] if i < len(mapping) and mapping[i] else enc(i))
 *             for i in range(count)
 *         }
 *         return re.sub(r"\b\w+\b", lambda mo: lookup.get(mo.group(0), mo.group(0)), payload)
 * ```
 */
object JsPacker {

    // NET-005: compiled ONCE. Both patterns are constant, so recompiling them on
    // every unpack() call (Kwik resolution runs this per candidate URL) was pure
    // avoidable allocation on the resolution hot path.
    private val PACKER_PATTERN = Regex(
        """\}\s*\(\s*'(.*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val WORD_PATTERN = Regex("""\b\w+\b""")

    /**
     * Unpack a packed JavaScript string.
     *
     * @param packed The packed JavaScript string
     * @return The unpacked JavaScript string, or the original if not packed
     */
    fun unpack(packed: String): String {
        // Match the packer pattern: }(  'payload', base, count, 'mapping'.split('|') )
        val match = PACKER_PATTERN.find(packed) ?: return packed

        val payload = match.groupValues[1]
        val base = match.groupValues[2].toIntOrNull() ?: return packed
        val count = match.groupValues[3].toIntOrNull() ?: return packed
        val mappingStr = match.groupValues[4]
        val mapping = mappingStr.split("|")

        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        // AQR-007: the packer never emits a base outside 2..62. base < 2 makes
        // encodeBaseN loop forever (value / 1 == value) and base > digits.length
        // throws StringIndexOutOfBounds — either way the remote page crashes the
        // app. Reject the same way the other malformed-packer guards above do:
        // return the input unchanged so the caller falls back to its other
        // decoding paths.
        if (base < 2 || base > digits.length) return packed

        // Build lookup table
        // AQR-018: `count` comes from the REMOTE page and is unbounded — a huge
        // value allocated a multi-gigabyte map and froze the resolution. Entries
        // past `mapping.size` would map each key to itself (identity), which the
        // replace below renders as no-ops anyway, so clamping to the mapping
        // length is output-identical for every well-formed packer and bounds the
        // work by the input size.
        val lookup = mutableMapOf<String, String>()
        for (i in 0 until count.coerceAtMost(mapping.size)) {
            val key = encodeBaseN(i, base, digits)
            val value = if (i < mapping.size && mapping[i].isNotEmpty()) {
                mapping[i]
            } else {
                key
            }
            lookup[key] = value
        }

        // Replace all word tokens in the payload
        return WORD_PATTERN.replace(payload) { matchResult ->
            lookup[matchResult.value] ?: matchResult.value
        }
    }

    /**
     * Encode an integer in the given base using the provided digit characters.
     * Matches Python enc() function.
     */
    private fun encodeBaseN(value: Int, base: Int, digits: String): String {
        if (value < base) {
            return digits[value].toString()
        }
        return encodeBaseN(value / base, base, digits) + digits[value % base]
    }
}
