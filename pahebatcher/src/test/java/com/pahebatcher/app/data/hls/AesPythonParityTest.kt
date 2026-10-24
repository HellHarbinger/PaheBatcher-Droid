package com.pahebatcher.app.data.hls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-for-byte parity between the Android decryptor and the PYTHON REFERENCE.
 *
 * The expected values in `src/test/resources/aes_parity_reference.tsv` are not
 * hand-written: they were produced by EXECUTING the reference expression
 *
 *     D:/pahebatcher-3.7.0/src/pahebatcher/downloader.py:101-103
 *         from Cryptodome.Cipher import AES
 *         cipher = AES.new(key_map[...], AES.MODE_CBC, iv=seg["iv"])
 *         raw = cipher.decrypt(raw)
 *
 * under WSL Ubuntu 26.04 with the reference repo's own venv
 * (`<python-ref-3.7.0>/venv/bin/python3`, Python 3.14.4,
 * pycryptodomex 3.24.0). Python is the reference; this test asserts that
 * Android reproduces those exact bytes.
 *
 * Regenerate with `aes_parity.py` (see the header comments of the TSV).
 */
class AesPythonParityTest {

    private companion object {
        const val RESOURCE = "aes_parity_reference.tsv"

        fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        fun toHex(b: ByteArray): String =
            b.joinToString("") { "%02x".format(it) }

        fun loadRows(): List<List<String>> {
            val stream = AesPythonParityTest::class.java.classLoader
                ?.getResourceAsStream(RESOURCE)
                ?: error("missing test resource $RESOURCE - regenerate it with aes_parity.py")
            return stream.bufferedReader().use { reader ->
                reader.readLines()
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { line -> line.split("\t") }
            }
        }
    }

    // ── Provenance ─────────────────────────────────────────────────────

    @Test
    fun `the parity vectors carry the executed reference provenance`() {
        val stream = AesPythonParityTest::class.java.classLoader
            ?.getResourceAsStream(RESOURCE)
            ?: error("missing test resource $RESOURCE - regenerate it with aes_parity.py")
        val header = stream.bufferedReader().use { r -> r.readLines().take(2).joinToString("\n") }

        assertTrue("must record the Python version: $header", header.contains("python=3.14.4"))
        assertTrue(
            "must record pycryptodomex: $header",
            header.contains("pycryptodomex=3.24.0"),
        )
        assertTrue(
            "must name the reference file it executed: $header",
            header.contains("downloader.py:101-103"),
        )
    }

    @Test
    fun `the vector table is not empty`() {
        val rows = loadRows()
        assertTrue("expected the reference table to have rows, got ${rows.size}", rows.size >= 10)
    }

    // ── Success rows: Android must reproduce Python's exact bytes ──────

    @Test
    fun `every python success case is reproduced byte for byte by android`() {
        val rows = loadRows().filter { it[1] == "ok" && it[6].isEmpty() }
        assertTrue("expected success rows", rows.isNotEmpty())

        for (r in rows) {
            val (name, key, iv, ct, expected) = listOf(r[0], r[2], r[3], r[4], r[5])

            val actual = Aes128Decryptor.decrypt(hex(ct), hex(key), hex(iv))

            assertNotNull("[$name] Android must decrypt what Python decrypted", actual)
            assertArrayEquals(
                "[$name] Android bytes must equal the bytes the Python reference produced",
                hex(expected),
                actual,
            )
            // downloader.py:105  self.dash.seg_done(key, len(raw)) - raw CBC
            // cannot change length, so the byte accounting must agree too.
            assertEquals(
                "[$name] raw CBC must preserve length (Python counts len(raw))",
                ct.length / 2,
                actual!!.size,
            )
        }
    }

    // ── Failure rows: Python raised, Android must refuse too ───────────

    @Test
    fun `every python failure case is also rejected by android`() {
        val rows = loadRows().filter { it[1] == "error" }
        assertTrue("expected failure rows", rows.isNotEmpty())

        for (r in rows) {
            val (name, key, iv, ct) = listOf(r[0], r[2], r[3], r[4])
            val actual = Aes128Decryptor.decrypt(hex(ct), hex(key), hex(iv))
            assertNull("[$name] Python raised, so Android must refuse the same input", actual)
        }
    }

    @Test
    fun `the four python failure rows cover block length key length and iv length`() {
        val names = loadRows().filter { it[1] == "error" }.map { it[0] }.toSet()
        assertEquals(
            setOf(
                "invalid_block_length_20",
                "invalid_block_length_15",
                "short_key_8_bytes",
                "short_iv_8_bytes",
            ),
            names,
        )
    }

    // ── The ONE documented divergence ──────────────────────────────────

    @Test
    fun `empty ciphertext divergence is explicitly documented not silently equalized`() {
        val row = loadRows().first { it[0] == "empty_ciphertext" }
        val pythonStatus = row[1]
        val pythonOutput = row[5]
        val divergence = row[6]

        // Observed by RUNNING Python: 0 is a multiple of 16, so the reference
        // has no empty-input guard and returns b"".
        assertEquals("Python must have succeeded on empty input", "ok", pythonStatus)
        assertEquals("Python returns zero bytes", "", pythonOutput)

        assertEquals(
            "this case must stay flagged as a known divergence",
            "android_stricter_rejects_empty_python_returns_empty",
            divergence,
        )

        // Android deliberately rejects it: a zero-byte segment is corrupt rather
        // than unpadded, and AGENTS 12 forbids treating an incomplete segment as
        // completed. This is the single documented place Android is stricter.
        assertNull(
            "Android must keep rejecting empty ciphertext (deliberate corruption guard)",
            Aes128Decryptor.decrypt(ByteArray(0), hex("000102030405060708090a0b0c0d0e0f"), ByteArray(16)),
        )
    }

    // ── Required coverage named in the phase brief ─────────────────────

    @Test
    fun `a published AES-CBC vector is included and matches`() {
        val row = loadRows().first { it[0] == "nist_sp800_38a_f21_two_blocks" }
        assertEquals("ok", row[1])
        assertEquals(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51",
            row[5],
        )
    }

    @Test
    fun `padding-shaped plaintext is retained rather than stripped`() {
        val retained = listOf(
            "pkcs7_pad_byte_01_retained",
            "pkcs7_lookalike_4x04",
            "pkcs7_full_block_16x10",
        )
        for (name in retained) {
            val row = loadRows().first { it[0] == name }
            assertEquals("[$name] Python must succeed", "ok", row[1])
            assertEquals(
                "[$name] Python keeps the pad bytes",
                row[7].toInt(),
                row[5].length / 2,
            )

            val actual = Aes128Decryptor.decrypt(hex(row[4]), hex(row[2]), hex(row[3]))
            assertNotNull("[$name] must decrypt", actual)
            assertEquals(
                "[$name] Android must retain the pad bytes too",
                row[7].toInt(),
                actual!!.size,
            )
        }
    }

    @Test
    fun `non padding-looking plaintext is accepted by both`() {
        val row = loadRows().first { it[0] == "non_pkcs7_final_byte_00" }
        assertEquals("ok", row[1])
        val actual = Aes128Decryptor.decrypt(hex(row[4]), hex(row[2]), hex(row[3]))
        assertNotNull(actual)
        assertEquals(16, actual!!.size)
        assertEquals(0x00, actual[15].toInt() and 0xFF)
    }

    @Test
    fun `a wrong key yields identical garbage on both sides`() {
        // Raw CBC has no integrity check, so BOTH sides must "succeed" and agree
        // on the same wrong bytes rather than one of them failing.
        val row = loadRows().first { it[0] == "wrong_key_yields_identical_garbage" }
        assertEquals("ok", row[1])

        val actual = Aes128Decryptor.decrypt(hex(row[4]), hex(row[2]), hex(row[3]))
        assertNotNull("raw CBC cannot detect a wrong key", actual)
        assertArrayEquals(
            "both implementations must produce the same wrong plaintext",
            hex(row[5]),
            actual,
        )
    }

    @Test
    fun `segment sized payload keeps byte accounting aligned with python`() {
        val row = loadRows().first { it[0] == "segment_sized_320_bytes" }
        assertEquals(320, row[7].toInt())

        val actual = Aes128Decryptor.decrypt(hex(row[4]), hex(row[2]), hex(row[3]))
        assertNotNull(actual)
        assertEquals(320, actual!!.size)
        assertArrayEquals(hex(row[5]), actual)
    }

    @Test
    fun `every success row satisfies the length invariant`() {
        val rows = loadRows().filter { it[1] == "ok" && it[6].isEmpty() }
        for (r in rows) {
            val ctLen = r[7].toInt()
            val outLen = r[8].toInt()
            assertEquals("python must preserve length for ${r[0]}", ctLen, outLen)

            val actual = Aes128Decryptor.decrypt(hex(r[4]), hex(r[2]), hex(r[3]))
            assertNotNull("android must decrypt ${r[0]}", actual)
            assertEquals("android length must match python for ${r[0]}", ctLen, actual!!.size)
        }
    }
}
