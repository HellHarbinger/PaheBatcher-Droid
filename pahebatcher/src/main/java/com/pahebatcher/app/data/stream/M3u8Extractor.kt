package com.pahebatcher.app.data.stream

/**
 * Extract M3U8 URLs from HTML/JavaScript content.
 *
 * Ported from Python kwik.py _extract_m3u8().
 * Handles multiple extraction strategies:
 * 1. Direct URL match in HTML
 * 2. Script unpacking (eval -> unicode escape -> JsPacker)
 * 3. Source tag fallback
 *
 * Python reference (kwik.py lines 47-72):
 * ```python
 * def _extract_m3u8(html: str) -> str | None:
 *     m = re.search(r"(https?://[^\s'\"\\>]+(?:uwu\.m3u8|\.m3u8)[^\s'\"\\>]*)", html)
 *     if m:
 *         return m.group(1).replace("\\/", "/").rstrip("\\")
 *
 *     for script in sorted(
 *         re.findall(r"<script[^>]*>([\s\S]*?)</script>", html, re.IGNORECASE),
 *         key=len, reverse=True,
 *     ):
 *         cur = script
 *         for _ in range(6):
 *             inner = re.search(r'eval\s*\(\s*"((?:[^"\\]|\\.)*)"\s*\)', cur)
 *             if inner:
 *                 cur = inner.group(1).encode().decode("unicode_escape", errors="ignore")
 *                 continue
 *             unpacked = JsPacker.unpack(cur)
 *             if unpacked != cur:
 *                 cur = unpacked
 *                 continue
 *             break
 *         m = re.search(r"(https?://[^\s'\"\\>]+(?:uwu\.m3u8|\.m3u8)[^\s'\"\\>]*)", cur)
 *         if m:
 *             return m.group(1).replace("\\/", "/").rstrip("\\")
 *
 *     m = re.search(r'<source[^>]+src=["\']([^"\']+\.m3u8[^"\']*)["\']', html, re.IGNORECASE)
 *     return m.group(1) if m else None
 * ```
 */
object M3u8Extractor {

    // NET-005: compiled ONCE. The direct/M3U8 pattern is shared by extractM3u8
    // and unpackScript; the eval and script/source patterns are constant too.
    // All were previously recompiled per call — up to twice per script per round
    // on the Kwik resolution path.
    private val M3U8_URL_PATTERN = Regex(
        """(https?://[^\s'"\\>]+(?:uwu\.m3u8|\.m3u8)[^\s'"\\>]*)"""
    )
    private val SCRIPT_PATTERN = Regex(
        """<script[^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE
    )
    private val SOURCE_PATTERN = Regex(
        """<source[^>]+src=["']([^"']+\.m3u8[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val EVAL_PATTERN = Regex(
        """eval\s*\(\s*"((?:[^"\\]|\\.)*)"\s*\)"""
    )

    /**
     * Extract M3U8 URL from HTML or JavaScript content.
     *
     * @param html HTML content (play page or Kwik page)
     * @return M3U8 URL or null if not found
     */
    fun extractM3u8(html: String): String? {
        // Strategy 1: Direct URL match
        M3U8_URL_PATTERN.find(html)?.let {
            return cleanUrl(it.groupValues[1])
        }

        // Strategy 2: Script unpacking
        val scripts = SCRIPT_PATTERN.findAll(html)
            .map { it.groupValues[1] }
            .sortedByDescending { it.length }

        for (script in scripts) {
            val result = unpackScript(script)
            if (result != null) {
                return result
            }
        }

        // Strategy 3: Source tag fallback
        SOURCE_PATTERN.find(html)?.let {
            return it.groupValues[1]
        }

        return null
    }

    /**
     * Try to extract M3U8 URL from a script block.
     * Applies up to 6 rounds of eval unpacking and JsPacker unpacking.
     */
    private fun unpackScript(script: String): String? {
        var current = script

        for (round in 0 until 6) {
            // Try eval unpacking (unicode escape)
            val evalMatch = EVAL_PATTERN.find(current)
            if (evalMatch != null) {
                current = decodeUnicodeEscape(evalMatch.groupValues[1])
                continue
            }

            // Try JsPacker unpacking
            val unpacked = JsPacker.unpack(current)
            if (unpacked != current) {
                current = unpacked
                continue
            }

            // Nothing changed — stop
            break
        }

        // Search for M3U8 URL in unpacked content
        M3U8_URL_PATTERN.find(current)?.let {
            return cleanUrl(it.groupValues[1])
        }

        return null
    }

    /**
     * Decode JavaScript escape sequences the way Python does inside
     * `_extract_m3u8()`: `inner.group(1).encode().decode("unicode_escape")`
     * applied to an `eval("...")` payload before it is searched for the M3U8 URL.
     *
     * Recognised (matching Python's `unicode_escape`):
     * - `\xHH`, `\uHHHH`, `\UHHHHHHHH` character escapes
     * - `\n \r \t \f \v \b \a \\ \' \"` and octal `\0`-`\377`
     * - a backslash before a real newline (line continuation is dropped)
     * - any other escape is kept literally (`\d` stays `\d`) — Python leaves
     *   unknown escapes untouched.
     *
     * Two deliberate, safe deviations from the Python decoder:
     * - `\/` is unescaped here instead of on the matched URL (Python's
     *   `cleanUrl`); this only widens what the m3u8 pattern can match and never
     *   changes a URL Python would already have matched.
     * - the input is a decoded String rather than utf-8 bytes re-decoded as
     *   latin-1, so a non-ASCII payload is preserved instead of mangled.
     *
     * @return the decoded payload
     */
    private fun decodeUnicodeEscape(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '\\' || i + 1 >= input.length) {
                out.append(c)
                i++
                continue
            }

            val next = input[i + 1]
            // Extension over Python: unescape "\/" immediately (Python does it on
            // the matched URL in cleanUrl). Only widens what the m3u8 pattern can
            // match; it never changes a URL Python would already have matched.
            if (next == '/') {
                out.append('/')
                i += 2
                continue
            }

            when (next) {
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'v' -> { out.append('\u000B'); i += 2 }
                'b' -> { out.append('\u0008'); i += 2 }
                'a' -> { out.append('\u0007'); i += 2 }
                '\\', '\'', '"' -> { out.append(next); i += 2 }
                '\n' -> i += 2 // line continuation: both characters are dropped
                'x' -> i = appendHexEscape(input, i, 2, out)
                'u' -> i = appendHexEscape(input, i, 4, out)
                'U' -> i = appendHexEscape(input, i, 8, out)
                in '0'..'7' -> i = appendOctalEscape(input, i, out)
                // Unknown escapes stay literal, exactly like Python's unicode_escape.
                else -> { out.append('\\').append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /**
     * Append the character of a `\x` / `\u` / `\U` escape starting at [start].
     * Returns the index just after the consumed escape; when the digits are
     * missing or not hex the backslash is kept (unknown-escape rule).
     */
    private fun appendHexEscape(
        input: String,
        start: Int,
        digits: Int,
        out: StringBuilder,
    ): Int {
        val from = start + 2
        if (from + digits > input.length) {
            out.append('\\').append(input[start + 1])
            return start + 2
        }
        val hex = input.substring(from, from + digits)
        val value = hex.toIntOrNull(16)
        if (value == null) {
            out.append('\\').append(input[start + 1])
            return start + 2
        }
        if (digits == 8) {
            if (Character.isValidCodePoint(value)) out.appendCodePoint(value)
        } else {
            out.append(value.toChar())
        }
        return from + digits
    }

    /**
     * Append the character of an octal escape (1-3 digits, clamped to a byte,
     * exactly like Python's `unicode_escape`) starting at [start].
     * Returns the index just after it.
     */
    private fun appendOctalEscape(input: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        var value = 0
        var count = 0
        val firstDigit = input[start + 1]
        while (i < input.length && count < 3 && input[i] in '0'..'7') {
            // Only a first digit of 0-3 admits the full three-digit form.
            if (count == 2 && firstDigit > '3') break
            value = value * 8 + (input[i] - '0')
            i++
            count++
        }
        if (count == 0) {
            out.append('\\').append(firstDigit)
            return start + 2
        }
        out.append((value and 0xFF).toChar())
        return i
    }

    /**
     * Clean a URL extracted from HTML/JS.
     * Removes escaped forward slashes and trailing backslashes.
     */
    private fun cleanUrl(url: String): String {
        return url.replace("\\/", "/").trimEnd('\\')
    }
}
