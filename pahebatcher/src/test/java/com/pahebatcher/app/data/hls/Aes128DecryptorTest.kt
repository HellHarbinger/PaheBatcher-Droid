package com.pahebatcher.app.data.hls

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for Aes128Decryptor — AES-128 CBC decryption.
 *
 * Constraint 9: Known-answer cryptographic tests.
 * Includes:
 * - Known key + IV + ciphertext → known plaintext
 * - Derived IV from media sequence
 * - Explicit IV
 * - Incorrect key failure
 * - Malformed key length failure
 */
class Aes128DecryptorTest {

    // ── Known-Answer Tests ────────────────────────────────────────────

    /**
     * Encrypt with RAW CBC (no padding) - the exact inverse of what Python's
     * `AES.new(key, AES.MODE_CBC, iv=iv).decrypt(...)` performs - so these fixtures
     * are reference-compatible ciphertext rather than self-generated Android padding.
     */
    private fun encryptRaw(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `decrypt round-trips a raw-CBC payload and preserves its length`() {
        // Key: 0x000102030405060708090A0B0C0D0E0F
        // IV:  0x00000000000000000000000000000000
        val key = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )
        val iv = ByteArray(16) { 0 }

        val plaintext = "Hello AES-128!!".toByteArray(Charsets.UTF_8).copyOf(16)
        val ciphertext = encryptRaw(plaintext, key, iv)

        val decrypted = Aes128Decryptor.decrypt(ciphertext, key, iv)

        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
        // Python's `cipher.decrypt()` cannot change the length - that is the property
        // the dashboard's `dash.seg_done(key, len(raw))` byte accounting relies on.
        assertEquals("Raw CBC must preserve length", ciphertext.size, decrypted!!.size)
    }

    @Test
    fun `NIST SP800-38A AES-128-CBC known answer decrypts correctly`() {
        // Published NIST SP800-38A F.2.1 vector - two blocks, so CBC chaining across
        // the block boundary is exercised, not just a single ECB-equivalent block.
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val expectedPlain = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
        val ciphertext = hex("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2")

        val decrypted = Aes128Decryptor.decrypt(ciphertext, key, iv)

        assertNotNull(decrypted)
        assertArrayEquals(expectedPlain, decrypted)
    }

    @Test
    fun `payload that is not valid PKCS7 still decrypts`() {
        // REGRESSION: this is the case the old `AES/CBC/PKCS5Padding` build rejected.
        // A final byte of 0x00 is not a legal PKCS#7 pad value (1..16), so `doFinal`
        // threw BadPaddingException and the segment was marked failed - even though
        // Python's raw `cipher.decrypt()` returns these bytes happily. Android must
        // not reject an input the reference supports.
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { 0 }

        val plaintext = ByteArray(16) { 'A'.code.toByte() }.also { it[15] = 0x00 }
        val ciphertext = encryptRaw(plaintext, key, iv)

        val decrypted = Aes128Decryptor.decrypt(ciphertext, key, iv)

        assertNotNull("Non-PKCS7 payload must decrypt, not be rejected", decrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `valid PKCS7 padding is NOT stripped`() {
        // Python keeps the pad bytes on disk; Android must too, so both write the same
        // segment bytes and report the same byte total.
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { 0 }

        val unpadded = "Hello AES-128!!".toByteArray(Charsets.UTF_8) // 15 bytes
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(unpadded) // 16 bytes, one full pad block

        val decrypted = Aes128Decryptor.decrypt(ciphertext, key, iv)

        assertNotNull(decrypted)
        assertEquals("Pad bytes must be retained for Python parity", ciphertext.size, decrypted!!.size)
    }

    @Test
    fun `truncated ciphertext that is not block aligned returns null`() {
        // Both implementations reject this: pycryptodome raises ValueError on
        // non-aligned data, Android's Cipher raises IllegalBlockSizeException.
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { 0 }

        val result = Aes128Decryptor.decrypt(ByteArray(20) { it.toByte() }, key, iv)

        assertNull("Non-block-aligned ciphertext must fail", result)
    }

    @Test
    fun `multi-block payload decrypts across the CBC chain`() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { 0x5A }

        val plaintext = ByteArray(48) { i -> (i * 7).toByte() }
        val ciphertext = encryptRaw(plaintext, key, iv)

        val decrypted = Aes128Decryptor.decrypt(ciphertext, key, iv)

        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
        assertEquals(48, decrypted!!.size)
    }

    @Test
    fun `decrypt with different key fails`() {
        val correctKey = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )
        val wrongKey = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFC.toByte(),
            0xFB.toByte(), 0xFA.toByte(), 0xF9.toByte(), 0xF8.toByte(),
            0xF7.toByte(), 0xF6.toByte(), 0xF5.toByte(), 0xF4.toByte(),
            0xF3.toByte(), 0xF2.toByte(), 0xF1.toByte(), 0xF0.toByte()
        )
        val iv = ByteArray(16) { 0 }

        // Encrypt with correct key
        val plaintext = "Test data 16byte".toByteArray(Charsets.UTF_8)
        val encCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        encCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(correctKey, "AES"), IvParameterSpec(iv))
        val ciphertext = encCipher.doFinal(plaintext)

        // Decrypt with wrong key — should fail (bad padding or wrong data)
        val decrypted = Aes128Decryptor.decrypt(ciphertext, wrongKey, iv)
        // Either null (decryption error) or different bytes
        if (decrypted != null) {
            assertFalse("Decrypted with wrong key should not match original",
                plaintext.contentEquals(decrypted))
        }
    }

    @Test
    fun `decrypt with incorrect key length returns null`() {
        val shortKey = ByteArray(8) { it.toByte() }
        val iv = ByteArray(16) { 0 }
        val data = ByteArray(32) { it.toByte() }

        val result = Aes128Decryptor.decrypt(data, shortKey, iv)
        assertNull("Should return null for short key", result)
    }

    @Test
    fun `decrypt with incorrect IV length returns null`() {
        val key = ByteArray(16) { it.toByte() }
        val shortIv = ByteArray(8) { it.toByte() }
        val data = ByteArray(32) { it.toByte() }

        val result = Aes128Decryptor.decrypt(data, key, shortIv)
        assertNull("Should return null for short IV", result)
    }

    // ── Derived IV from Media Sequence ────────────────────────────────

    @Test
    fun `decrypt with derived IV from media sequence 0`() {
        val key = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )
        // Derived IV from seq_num=0: all zeros
        val iv = ByteArray(16) { 0 }

        val plaintext = "Derived IV test!".toByteArray(Charsets.UTF_8) // 16 B, block-aligned
        val ciphertext = encryptRaw(plaintext, key, iv)

        val decrypted = Aes128Decryptor.decrypt(ciphertext, key, iv)
        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt with derived IV from media sequence 1`() {
        val key = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )
        // Derived IV from seq_num=1: 0x00000000000000000000000000000001
        val iv = ByteArray(16) { 0 }
        iv[15] = 1

        val plaintext = "Seq num 1 test!!".toByteArray(Charsets.UTF_8) // 16 B, block-aligned
        val ciphertext = encryptRaw(plaintext, key, iv)

        val decrypted = Aes128Decryptor.decrypt(ciphertext, key, iv)
        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt with explicit IV differs from derived IV`() {
        val key = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )
        val explicitIv = ByteArray(16) { 0x42 }
        val derivedIv = ByteArray(16) { 0 }

        val plaintext = "Explicit IV test".toByteArray(Charsets.UTF_8) // 16 B, block-aligned

        // Encrypt with explicit IV
        val ciphertext = encryptRaw(plaintext, key, explicitIv)

        // Decrypt with explicit IV — should succeed
        val decrypted = Aes128Decryptor.decrypt(ciphertext, key, explicitIv)
        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)

        // Decrypt with derived IV — should produce different result
        val wrongDecrypted = Aes128Decryptor.decrypt(ciphertext, key, derivedIv)
        if (wrongDecrypted != null) {
            assertFalse("Explicit IV decrypt should not match derived IV",
                plaintext.contentEquals(wrongDecrypted))
        }
    }

    // ── Validation Helpers ────────────────────────────────────────────

    @Test
    fun `isValidKey returns true for 16-byte key`() {
        assertTrue(Aes128Decryptor.isValidKey(ByteArray(16)))
    }

    @Test
    fun `isValidKey returns false for wrong size`() {
        assertFalse(Aes128Decryptor.isValidKey(ByteArray(8)))
        assertFalse(Aes128Decryptor.isValidKey(ByteArray(32)))
        assertFalse(Aes128Decryptor.isValidKey(ByteArray(0)))
    }

    @Test
    fun `isValidIv returns true for 16-byte IV`() {
        assertTrue(Aes128Decryptor.isValidIv(ByteArray(16)))
    }

    @Test
    fun `isValidIv returns false for wrong size`() {
        assertFalse(Aes128Decryptor.isValidIv(ByteArray(8)))
        assertFalse(Aes128Decryptor.isValidIv(ByteArray(32)))
    }

    // ── Empty Data ────────────────────────────────────────────────────

    @Test
    fun `decrypt handles empty ciphertext`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { 0 }

        // Empty ciphertext is invalid for AES-CBC (minimum 16 bytes for padding)
        val result = Aes128Decryptor.decrypt(ByteArray(0), key, iv)
        assertNull("Empty ciphertext should fail", result)
    }
}
