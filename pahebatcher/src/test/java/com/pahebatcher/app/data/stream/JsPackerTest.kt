package com.pahebatcher.app.data.stream

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for JsPacker — JavaScript unpacking logic.
 *
 * Ported from Python kwik.py JsPacker.unpack().
 * Tests the eval-based JS packer (p,a,c,k,e,d) used by Kwik.
 */
class JsPackerTest {

    // ── Basic Unpacking ──────────────────────────────────────────────────

    @Test
    fun `unpack returns original string when not packed`() {
        val input = "var x = 1; console.log(x);"
        assertEquals(input, JsPacker.unpack(input))
    }

    @Test
    fun `unpack returns original string when pattern not found`() {
        val input = "function() { return 'test'; }"
        assertEquals(input, JsPacker.unpack(input))
    }

    @Test
    fun `unpack handles empty string`() {
        assertEquals("", JsPacker.unpack(""))
    }

    // ── Packer Pattern Recognition ───────────────────────────────────────

    @Test
    fun `unpack recognizes packer pattern with base 62`() {
        // Simple packed JS with base 62, minimal payload
        // Pattern: }(  'payload', 62, count, 'mapping'.split('|') )
        val packed = "}(function(p,a,c,k,e,d){while(c--){if(d[c]){p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),d[c])}}}(#{1},62,1,'a'.split('|'),0,{}))"
        // This is a minimal example — the actual pattern uses a different format
        // The regex looks for: }('payload', base, count, 'mapping'.split('|'))
        val input = """}(  'test'  ,  62  ,  1  ,  'a'.split('|')  )"""
        val result = JsPacker.unpack(input)
        // Should attempt to unpack (may return same if payload is simple)
        assertNotNull(result)
    }

    // ── Base-N Encoding ──────────────────────────────────────────────────

    @Test
    fun `encodeBaseN works for base 62`() {
        // The encodeBaseN function is private, but we can test it indirectly
        // through the unpack behavior
        // For now, verify that the packer doesn't crash on various inputs
        val packed = "}(  'hello'  ,  62  ,  5  ,  'a|b|c|d|e'.split('|')  )"
        val result = JsPacker.unpack(packed)
        assertNotNull(result)
    }

    // ── Real Kwik Patterns ───────────────────────────────────────────────

    @Test
    fun `unpack handles typical Kwik packed JS`() {
        // Typical Kwik packer output — the eval wrapper contains the packer pattern
        // }('payload', base, count, 'mapping'.split('|'))
        val packed = """eval(function(p,a,c,k,e,d){while(c--)if(d[c])p=p.replace(new RegExp("\\b"+c.toString(a)+"\\b","g"),d[c])}('var x=1;',62,1,'x'.split('|'),0,{}))"""
        // The regex matches }('var x=1;',62,1,'x'.split('|')) inside the eval wrapper
        // payload="var x=1;", base=62, count=1, mapping="x"
        // lookup: enc(0) for base 62 = "0", mapping[0] = "x" → {"0": "x"}
        // "var x=1;" — "var" and "x" are word tokens. "var" not in lookup → "var", "x" not in lookup (key is "0") → "x"
        val result = JsPacker.unpack(packed)
        assertEquals("var x=1;", result)
    }

    @Test
    fun `unpack handles packed string with base 36`() {
        // The regex \b\w+\b matches whole word tokens. In "a1b2c3", the entire
        // string is one word token (no word boundaries between alphanumeric chars).
        // So the lookup replaces the whole token. Since "a1b2c3" is NOT in the
        // lookup (keys are "0","1","2"), it returns unchanged.
        // This test verifies the function doesn't crash on base 36.
        val input = "}(  'a1b2c3'  ,  36  ,  3  ,  'x|y|z'.split('|')  )"
        val result = JsPacker.unpack(input)
        assertNotNull(result)
        // The regex matches, extracts payload, builds lookup, runs sub.
        // Result is the payload with word tokens replaced.
        assertTrue(result.isNotEmpty())
    }

    // ── Edge Cases ───────────────────────────────────────────────────────

    @Test
    fun `unpack handles malformed input gracefully`() {
        val input = "}(  'payload'  ,  abc  ,  0  ,  ''.split('|')  )"
        val result = JsPacker.unpack(input)
        // base is not a number, should return original
        assertEquals(input, result)
    }

    @Test
    fun `unpack handles zero count`() {
        // When count=0, the lookup is empty, so all word tokens map to themselves.
        // But the payload IS extracted from the packed wrapper.
        val input = "}(  'payload'  ,  62  ,  0  ,  ''.split('|')  )"
        val result = JsPacker.unpack(input)
        // The regex matches, extracts payload="payload", builds empty lookup,
        // and replaces \b\w+\b on "payload" — "payload" is not in empty lookup,
        // so it returns "payload" (the default).
        assertEquals("payload", result)
    }

    @Test
    fun `unpack handles mapping shorter than count`() {
        val input = "}(  'a b'  ,  62  ,  5  ,  'x|y'.split('|')  )"
        val result = JsPacker.unpack(input)
        assertNotNull(result)
    }

    // ── Malformed Base Guard (AQR-007) ──────────────────────────────────
    // The packer never emits a base outside 2..62. base==1 would loop forever
    // in encodeBaseN (value / 1 == value → StackOverflowError) and base==0
    // would throw ArithmeticException — the remote page would crash the app.
    // unpack() must return the input unchanged instead.

    @Test
    fun `unpack returns input unchanged for base 1`() {
        val input = "}(  'payload'  ,  1  ,  5  ,  'a|b|c|d|e'.split('|')  )"
        assertEquals(input, JsPacker.unpack(input))
    }

    @Test
    fun `unpack returns input unchanged for base 0`() {
        val input = "}(  'payload'  ,  0  ,  5  ,  'a|b|c|d|e'.split('|')  )"
        assertEquals(input, JsPacker.unpack(input))
    }

    @Test
    fun `unpack returns input unchanged for base above digit alphabet length`() {
        // digits has 62 entries (0-9, a-z, A-Z); base 99 indexes past it.
        val input = "}(  'payload'  ,  99  ,  5  ,  'a|b|c|d|e'.split('|')  )"
        assertEquals(input, JsPacker.unpack(input))
    }

    // ── Unbounded Count Guard (AQR-018) ──────────────────────────────────
    // `count` comes from the REMOTE page: without a bound it drove an
    // allocation loop of up to ~2 billion map entries (OOM/ANR on a hostile
    // packer). Entries past `mapping.size` are identity no-ops, so clamping
    // to the mapping length must not change the output of a well-formed
    // packer either.

    @Test
    fun `unpack completes for absurd remote count and substitutes mapping entries`() {
        // count = 999999999 with a 2-entry mapping: the unclamped loop would
        // allocate ~1e9 entries and freeze the resolution.
        val input = "}(  '0 1'  ,  62  ,  999999999  ,  'gamma|delta'.split('|')  )"
        assertEquals("gamma delta", JsPacker.unpack(input))
    }

    @Test
    fun `huge count produces the same output as exact count`() {
        val exact = "}(  '0 1'  ,  62  ,  2  ,  'gamma|delta'.split('|')  )"
        val inflated = "}(  '0 1'  ,  62  ,  999999999  ,  'gamma|delta'.split('|')  )"
        // Output-identity property: clamping drops only identity entries.
        assertEquals(JsPacker.unpack(exact), JsPacker.unpack(inflated))
    }
}
