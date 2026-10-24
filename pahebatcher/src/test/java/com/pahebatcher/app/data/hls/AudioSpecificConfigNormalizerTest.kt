package com.pahebatcher.app.data.hls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Regression tests for [AudioSpecificConfigNormalizer].
 *
 * The defect these cover: the animepahe packager labels every ADTS audio frame
 * as AAC Main, so FFmpeg assembles an MP4 whose `esds` AudioSpecificConfig
 * declares audioObjectType = 1. Android's platform AAC decoder
 * (`c2.android.aac.decoder`) rejects that configuration
 * (`aacDecoder_ConfigRaw decoderErr = 0x2003`) and Media3 playback fails, even
 * though the payload is HE-AACv2 (LC core + SBR + PS).
 */
class AudioSpecificConfigNormalizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---------------------------------------------------------------- helpers

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = 8 + payload.size
        out.write(size ushr 24)
        out.write(size ushr 16)
        out.write(size ushr 8)
        out.write(size)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** 4-byte (long-form) descriptor length, as FFmpeg writes it: 0x80 0x80 0x80 <len>. */
    private fun extendedLengthBytes(length: Int): ByteArray = byteArrayOf(
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), (length and 0xFF).toByte()
    )

    private fun handlerBox(handler: String): ByteArray {
        val payload = ByteArray(20)
        val h = handler.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(h, 0, payload, 8, 4)
        return box("hdlr", payload)
    }

    /** `05 <len> <asc>` DescriptorSpecificInfo wrapped in DecoderConfig + ES descriptors. */
    private fun esdsPayload(
        asc: ByteArray,
        objectTypeIndication: Int = 0x40,
        extendedLength: Boolean = false,
    ): ByteArray {
        val dsi = ByteArrayOutputStream()
        dsi.write(0x05)
        if (extendedLength) {
            dsi.write(extendedLengthBytes(asc.size))
        } else {
            dsi.write(asc.size)
        }
        dsi.write(asc)

        val decoderConfig = ByteArrayOutputStream()
        decoderConfig.write(0x04)
        val dcdLength = 13 + dsi.size()
        if (extendedLength) {
            decoderConfig.write(extendedLengthBytes(dcdLength))
        } else {
            decoderConfig.write(dcdLength)
        }
        decoderConfig.write(objectTypeIndication) // objectTypeIndication
        decoderConfig.write(0x15)                 // streamType (audio stream)
        decoderConfig.write(0); decoderConfig.write(0); decoderConfig.write(0) // bufferSizeDB
        repeat(4) { decoderConfig.write(0) }       // maxBitRate
        repeat(4) { decoderConfig.write(0) }       // avgBitRate
        decoderConfig.write(dsi.toByteArray())

        val es = ByteArrayOutputStream()
        es.write(0x03)
        val slConfig = byteArrayOf(0x06, 0x01, 0x02)
        val esLength = 3 + decoderConfig.size() + slConfig.size
        if (extendedLength) {
            es.write(extendedLengthBytes(esLength))
        } else {
            es.write(esLength)
        }
        es.write(0); es.write(2)  // ES_ID
        es.write(0)               // flags
        es.write(decoderConfig.toByteArray())
        es.write(slConfig)

        val payload = ByteArrayOutputStream()
        payload.write(0); payload.write(0); payload.write(0); payload.write(0) // FullBox
        payload.write(es.toByteArray())
        return payload.toByteArray()
    }

    /**
     * Minimal but structurally faithful MP4: ftyp / moov(mvhd + video trak +
     * audio trak) / mdat.
     */
    private fun buildMp4(
        asc: ByteArray,
        objectTypeIndication: Int = 0x40,
        extendedLength: Boolean = false,
        audioHandler: String = "soun",
    ): ByteArray {
        val esds = box("esds", esdsPayload(asc, objectTypeIndication, extendedLength))
        val mp4aPayload = concat(ByteArray(8), ByteArray(20), esds)
        val sampleEntry = box("mp4a", mp4aPayload)
        val stsd = box("stsd", concat(ByteArray(8), sampleEntry))
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        val audioMdia = box("mdia", concat(handlerBox(audioHandler), minf))
        val audioTrak = box("trak", audioMdia)

        val videoMdia = box("mdia", handlerBox("vide"))
        val videoTrak = box("trak", videoMdia)

        val mvhd = box("mvhd", ByteArray(100))
        val moov = box("moov", concat(mvhd, videoTrak, audioTrak))

        val ftyp = box("ftyp", "isom".toByteArray() + byteArrayOf(0, 0, 2, 0) +
            "isomiso2".toByteArray())
        val mdat = box("mdat", ByteArray(128))

        return concat(ftyp, moov, mdat)
    }

    private fun write(name: String, bytes: ByteArray): File {
        val f = File(tempFolder.root, name)
        f.writeBytes(bytes)
        return f
    }

    /** Returns the AudioSpecificConfig bytes of the audio trak, or null. */
    private fun readAsc(file: File): ByteArray? {
        val data = file.readBytes()
        var i = 0
        while (i + 8 <= data.size) {
            if (String(data, i + 4, 4, Charsets.ISO_8859_1) == "esds") {
                // scan this box for the DecoderSpecificInfo tag 05
                var p = i + 8
                val end = (i + readSize(data, i)).coerceAtMost(data.size)
                while (p + 6 < end) {
                    if (data[p] == 0x05.toByte() && data[p + 1] == 0x80.toByte()) {
                        return data.copyOfRange(p + 5, p + 7)
                    }
                    if (data[p] == 0x05.toByte() && data[p + 1].toInt() in 1..63) {
                        val len = data[p + 1].toInt() and 0xFF
                        return data.copyOfRange(p + 2, p + 2 + len)
                    }
                    p++
                }
            }
            i++
        }
        return null
    }

    private fun readSize(data: ByteArray, at: Int): Int {
        return ((data[at].toInt() and 0xFF) shl 24) or
            ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or
            (data[at + 3].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `esds declaring AAC Main is corrected to AAC-LC`() {
        val file = write("main.mp4", buildMp4(byteArrayOf(0x0B, 0x88.toByte())))

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.CORRECTED, result.action)
        assertArrayEquals(byteArrayOf(0x13, 0x88.toByte()), readAsc(file))
    }

    @Test
    fun `only the single ASC byte changes - mdat and every other byte are untouched`() {
        val before = buildMp4(byteArrayOf(0x0B, 0x88.toByte()))
        val file = write("main.mp4", before)

        AudioSpecificConfigNormalizer.normalize(file)

        val after = file.readBytes()
        assertEquals(before.size, after.size)
        val diffs = before.indices.filter { before[it] != after[it] }
        assertEquals("exactly one byte must change, changed at $diffs", 1, diffs.size)
        assertEquals(0x0B, before[diffs[0]].toInt() and 0xFF)
        assertEquals(0x13, after[diffs[0]].toInt() and 0xFF)
    }

    @Test
    fun `esds declaring AAC-LC is left unchanged`() {
        val original = buildMp4(byteArrayOf(0x13, 0x88.toByte()))
        val file = write("lc.mp4", original)

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.NOT_NEEDED, result.action)
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `extended descriptor lengths used by FFmpeg are handled`() {
        val file = write("ext.mp4", buildMp4(byteArrayOf(0x0B, 0x88.toByte()), extendedLength = true))

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.CORRECTED, result.action)
        assertArrayEquals(byteArrayOf(0x13, 0x88.toByte()), readAsc(file))
    }

    @Test
    fun `objectTypeIndication other than MPEG-4 Audio is not rewritten`() {
        val file = write("mpeg2.mp4", buildMp4(
            asc = byteArrayOf(0x0B, 0x88.toByte()),
            objectTypeIndication = 0x66, // MPEG-2 AAC Main
        ))

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.SKIPPED, result.action)
        assertArrayEquals(byteArrayOf(0x0B, 0x88.toByte()), readAsc(file))
    }

    @Test
    fun `audio object type using the escape code is not rewritten`() {
        // AOT escape (31) followed by 6 extra bits -> 32 + 1 = AAC-LD-ish, not Main
        val file = write("escape.mp4", buildMp4(byteArrayOf(0xF8.toByte(), 0x48)))

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.NOT_NEEDED, result.action)
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0x48), readAsc(file))
    }

    @Test
    fun `non audio trak is ignored and the audio trak is still normalized`() {
        val file = write("ordered.mp4", buildMp4(byteArrayOf(0x0B, 0x88.toByte())))

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.CORRECTED, result.action)
        assertArrayEquals(byteArrayOf(0x13, 0x88.toByte()), readAsc(file))
    }

    @Test
    fun `file without moov is skipped and left byte identical`() {
        val original = concat(
            box("ftyp", "isom".toByteArray()),
            box("mdat", ByteArray(64)),
        )
        val file = write("nomoov.mp4", original)

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.SKIPPED, result.action)
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `missing or empty file is skipped instead of throwing`() {
        val missing = File(tempFolder.root, "absent.mp4")
        assertEquals(
            AudioSpecificConfigNormalizer.Action.SKIPPED,
            AudioSpecificConfigNormalizer.normalize(missing).action
        )

        val empty = write("empty.mp4", ByteArray(0))
        assertEquals(
            AudioSpecificConfigNormalizer.Action.SKIPPED,
            AudioSpecificConfigNormalizer.normalize(empty).action
        )
    }

    @Test
    fun `handler type is read from the audio trak not from a similarly shaped trak`() {
        // audio trak carries a non-audio handler -> nothing to normalize
        val file = write("notaudio.mp4", buildMp4(
            asc = byteArrayOf(0x0B, 0x88.toByte()),
            audioHandler = "sbtl",
        ))

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.SKIPPED, result.action)
        assertArrayEquals(byteArrayOf(0x0B, 0x88.toByte()), readAsc(file))
    }

    @Test
    fun `moov placed after mdat is still found`() {
        val mp4 = buildMp4(byteArrayOf(0x0B, 0x88.toByte()))
        // rebuild with moov after mdat: ftyp + mdat + moov
        val ftypSize = readSize(mp4, 0)
        val ftyp = mp4.copyOfRange(0, ftypSize)
        val moovStart = ftypSize
        val moovSize = readSize(mp4, moovStart)
        val moov = mp4.copyOfRange(moovStart, moovStart + moovSize)
        val mdat = mp4.copyOfRange(moovStart + moovSize, mp4.size)
        val file = write("slowstart.mp4", concat(ftyp, mdat, moov))

        val result = AudioSpecificConfigNormalizer.normalize(file)

        assertEquals(AudioSpecificConfigNormalizer.Action.CORRECTED, result.action)
        assertTrue(readAsc(file).contentEquals(byteArrayOf(0x13, 0x88.toByte())))
    }
}
