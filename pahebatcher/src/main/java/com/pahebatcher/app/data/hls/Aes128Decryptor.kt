package com.pahebatcher.app.data.hls

import com.pahebatcher.app.logging.AppLog
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128 CBC decryption for HLS segments.
 *
 * Ported from Python downloader.py lines 101-103:
 * ```python
 * from Cryptodome.Cipher import AES
 * cipher = AES.new(key_map[seg["key_url"]], AES.MODE_CBC, iv=seg["iv"])
 * raw = cipher.decrypt(raw)
 * ```
 *
 * Constraint 9: Preserve Python behavior:
 * - AES-128 CBC mode
 * - 16-byte key
 * - 16-byte IV (explicit or media-sequence-derived)
 * - **no unpadding step** - see below.
 *
 * ## Why `NoPadding` and not `PKCS5Padding`
 *
 * Python's `cipher.decrypt()` is a RAW CBC decryption: the ciphertext length and
 * the plaintext length are always equal, and the call **cannot** fail on padding.
 * Until this slice Android asked the JCE provider to strip PKCS#5/#7 instead, which
 * diverged from the reference in two ways:
 *
 * 1. **Output length.** Android dropped the trailing 1-16 pad bytes that Python keeps,
 *    so the byte total reported to the dashboard (`downloader.py:105`
 *    `dash.seg_done(key, len(raw))`) and the bytes written to disk disagreed with the
 *    reference for every single encrypted segment.
 * 2. **Accepted input.** `doFinal` throws `BadPaddingException` whenever the last block
 *    does not decode as PKCS#7 - a ciphertext Python decrypts happily. Android turned
 *    that into `null`, i.e. it *failed a segment the reference succeeds*, which is the
 *    opposite of the required direction ("do not reject valid inputs Python supports").
 *
 * `AES/CBC/NoPadding` reproduces the reference exactly: identical output bytes for every
 * input Python accepts, identical length, and failure only on input that is not a
 * positive multiple of the 16-byte block - which is precisely when pycryptodome raises
 * `ValueError` too. Downstream assembly is unaffected: `FfmpegEpisodeAssembler` checks
 * segment COUNT and non-zero size only, never a per-segment byte length or 188-byte
 * MPEG-TS alignment, and ffmpeg discards the trailing pad bytes just as it does for
 * Python's output today.
 *
 * The empty-ciphertext guard below is deliberately NOT relaxed: a zero-byte segment is
 * corrupt, not "unpadded", and AGENTS 12 requires that incomplete segments never be
 * considered completed.
 */
object Aes128Decryptor {

    private const val TAG = "Aes128Decryptor"
    private const val KEY_SIZE = 16
    private const val IV_SIZE = 16

    /**
     * Decrypt an AES-128 CBC encrypted segment.
     *
     * @param encrypted The encrypted segment data
     * @param key The 16-byte AES key
     * @param iv The 16-byte initialization vector
     * @return Decrypted data, or null on failure
     */
    fun decrypt(encrypted: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        // Validate inputs
        if (encrypted.isEmpty()) {
            AppLog.w(TAG, "Empty ciphertext rejected")
            return null
        }
        if (key.size != KEY_SIZE) {
            AppLog.e(TAG, "Invalid key size: ${key.size} (expected $KEY_SIZE)")
            return null
        }
        if (iv.size != IV_SIZE) {
            AppLog.e(TAG, "Invalid IV size: ${iv.size} (expected $IV_SIZE)")
            return null
        }

        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            // Raw CBC, no unpadding - byte-identical to Python's `cipher.decrypt`.
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            AppLog.e(TAG, "Decryption failed: ${e.message}")
            null
        }
    }

    /**
     * Validate that a key is exactly 16 bytes.
     */
    fun isValidKey(key: ByteArray): Boolean = key.size == KEY_SIZE

    /**
     * Validate that an IV is exactly 16 bytes.
     */
    fun isValidIv(iv: ByteArray): Boolean = iv.size == IV_SIZE
}
