package com.pahebatcher.app.logging

import java.net.URI
import java.net.URISyntaxException

/**
 * Sanitizes URLs before they can reach a log sink (AGENTS §10, AppLog §M7:
 * "prefer the host + path prefix over a full signed URL").
 *
 * Why this exists: expiring stream/segment URLs carry their authorisation in the
 * QUERY string (`?token=…`, `?expires=…`, `?AWSAccessKeyId=…`), and a URL can
 * also carry userinfo (`scheme://user:pass@host/…`) plus a fragment. Logcat is
 * world-readable through `adb` and the diagnostics Console renders buffered
 * lines ON SCREEN, so a full URL must never be emitted at WARN/ERROR — the
 * failure still has to say WHERE it happened, which is what [url] keeps.
 *
 * Guarantees, each covered by `LogUrlSanitizerTest`:
 *
 * * only `host[:port]` + `path` survive — query string, fragment and userinfo
 *   are dropped **before** the value is parsed, so nothing after `?` or `#` can
 *   survive even if the parser would have accepted it;
 * * a path longer than [MAX_PATH_CHARS] is truncated, because a path may carry
 *   a token of its own;
 * * a value that cannot be parsed is replaced by [UNPARSEABLE] and the original
 *   text is NOT interpolated anywhere — malformed input fails safe instead of
 *   leaking through the error path;
 * * [message] additionally rewrites URLs embedded in free text (an exception
 *   message can quote the failing URL), so wrapping the whole formatted string
 *   closes that channel too;
 * * [diagnostic] goes one step further for parser/exception detail on
 *   WARN/ERROR paths: after [message], any surviving `?query`/`#fragment` run
 *   is stripped, because a MALFORMED URL (what a URI parser complains about)
 *   stops the scheme match at the illegal character and a scheme-less relative
 *   reference never matches at all.
 *
 * The result is intentionally still useful for diagnosis: host and path are
 * exactly what identifies which CDN object failed, while every part that can
 * expire or authenticate is gone.
 */
object LogUrlSanitizer {

    /** Rendered instead of a blank/null argument (the value was never a URL). */
    const val NO_URL = "[no-url]"

    /** Rendered instead of an unparseable URL — the original is never echoed. */
    const val UNPARSEABLE = "[unparseable-url]"

    /**
     * Upper bound for the retained path. Paths are kept because they identify
     * the object, but they are bounded too: a signed path (`/sig/<token>/…`)
     * would otherwise be logged in full.
     */
    const val MAX_PATH_CHARS = 160

    /** Any `http(s)` URL inside free text (exception messages, HTML snippets). */
    private val SCHEME_URL = Regex("""https?://[^\s"'<>]+""")

    /**
     * A `?query` run (the `?` plus everything up to the next whitespace) —
     * stripped by [diagnostic] from text a parser is complaining about, because
     * a malformed URL's tail is not covered by [SCHEME_URL].
     */
    private val QUERY_RUN = Regex("""\?\S+""")

    /** A `#fragment` run — stripped by [diagnostic] for the same reason. */
    private val FRAGMENT_RUN = Regex("""#\S+""")

    /**
     * Sanitize ONE url to `host[:port]/path`.
     *
     * @param raw the URL as it would have been logged; may be null/blank.
     * @return host + port + bounded path, or [NO_URL] / [UNPARSEABLE].
     */
    fun url(raw: String?): String {
        if (raw.isNullOrBlank()) return NO_URL
        // Cut FIRST: query and fragment are dropped before parsing, so they can
        // never be re-introduced by a parser that tolerates them.
        val cut = raw.substringBefore('?').substringBefore('#')
        return try {
            val uri = URI(cut)
            val host = uri.host
            // `URI.host` is null for relative/opaque values; `userInfo` is a
            // separate component and is deliberately never read.
            if (host.isNullOrEmpty()) UNPARSEABLE else buildString {
                append(host)
                if (uri.port != -1) {
                    append(':')
                    append(uri.port)
                }
                val path = uri.path.orEmpty()
                append(
                    if (path.length > MAX_PATH_CHARS) {
                        path.substring(0, MAX_PATH_CHARS) + "…"
                    } else {
                        path
                    },
                )
            }
        } catch (_: URISyntaxException) {
            UNPARSEABLE
        } catch (_: IllegalArgumentException) {
            // URI(String) also rejects a handful of malformed authorities.
            UNPARSEABLE
        }
    }

    /**
     * Sanitize FREE TEXT: every `http(s)://…` run inside [text] is replaced by
     * its [url] form, so an exception message (or an HTML snippet) that quotes a
     * full URL cannot leak it. Non-URL content is returned unchanged.
     *
     * A null input renders exactly like Kotlin's `${…}` template did before, so
     * wrapping an existing message changes nothing except the URLs inside it.
     */
    fun message(text: String?): String {
        if (text == null) return "null"
        if (!text.contains("://")) return text
        return SCHEME_URL.replace(text) { match ->
            val raw = match.value
            // Sanitize the URL proper, then put back whatever punctuation the
            // sentence had after it, so wrapping a message never edits prose.
            val trimmed = trimTrailingPunctuation(raw)
            url(trimmed) + raw.substring(trimmed.length)
        }
    }

    /**
     * Sanitize DIAGNOSTIC text: [message] first, then strip any remaining
     * `?query` / `#fragment` runs.
     *
     * Why [message] alone is not enough here: a MALFORMED URL — exactly what a
     * URI parser is complaining about — stops the [SCHEME_URL] match at the
     * illegal character, leaving the tail (which still carries `?token=…`)
     * outside the match, and text with no `://` at all (a relative reference
     * like `seg 0.ts?token=…`) never enters the replacement at all. Fail-safe
     * beats prose fidelity on an error path: a query run is stripped even when
     * the surrounding sentence was mangled, while a lone `?` or `#` followed by
     * whitespace (prose) is left alone.
     *
     * Used for parser/exception detail on WARN/ERROR paths — playlist URI
     * resolution, resolution-failure messages, and rendered stack traces —
     * wherever the input is diagnostic text rather than a URL-shaped value.
     */
    fun diagnostic(text: String?): String {
        val sanitized = message(text)
        if (sanitized.indexOf('?') < 0 && sanitized.indexOf('#') < 0) return sanitized
        return QUERY_RUN.replace(sanitized, "").replace(FRAGMENT_RUN, "")
    }

    /**
     * Prose delimiters that follow a URL in a sentence (`… for https://h/p?x=1:
     * read timed out`) are not part of the URL; excluding them from the parse
     * keeps the surrounding message intact after the replacement.
     */
    private fun trimTrailingPunctuation(value: String): String =
        value.trimEnd(',', ';', ':', '.', ')', ']', '}', '"', '\'')
}
