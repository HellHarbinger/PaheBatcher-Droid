package com.pahebatcher.app.data.hls

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The single AES-128-CBC encryption primitive shared by the HLS test suite.
 *
 * These tests exercise [Aes128Decryptor], which is CBC by protocol mandate
 * (RFC 8216 §5.2), so producing CBC ciphertext in test code is unavoidable:
 * there is no way to feed the decryptor valid input without a CBC encryptor.
 * Keeping exactly ONE `Cipher.getInstance("AES/CBC/...")` use site in the test
 * source set (instead of one per test file) keeps that unavoidable surface to a
 * single, documented location.
 *
 * Raw `NoPadding` encryption is the exact inverse of the production decryptor
 * and of Python's `AES.new(key, AES.MODE_CBC, iv=iv).encrypt(...)`; callers
 * that need PKCS#7-padded ciphertext build the pad block themselves (see
 * EpisodeDownloaderTest) so no padded-mode Cipher instance exists anywhere.
 */
internal object HlsTestCipher {

    /** Encrypt [plaintext] with raw AES-128-CBC (no padding, no unpadding). */
    fun encryptRaw(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }

    /** Append a PKCS#7 pad block to [plaintext] (RFC 8216 §5.2 style padding). */
    fun pkcs7Pad(data: ByteArray): ByteArray {
        val padLen = 16 - (data.size % 16)
        return data + ByteArray(padLen) { padLen.toByte() }
    }
}
