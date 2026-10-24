package com.pahebatcher.app.data.hls

import com.pahebatcher.app.logging.AppLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Corrects an assembled MP4 whose `esds` AudioSpecificConfig declares
 * **AAC Main (audioObjectType = 1)**.
 *
 * ## Why this is needed
 *
 * The animepahe packager emits every MPEG-TS audio frame with an ADTS header
 * whose `profile` field is `0` (AAC Main). FFmpeg faithfully propagates that
 * declaration into the `esds` `DecoderSpecificInfo` of the assembled MP4, so the
 * container ends up claiming `mp4a.40.1` / AudioObjectType 1.
 *
 * That declaration contradicts the payload itself:
 *
 *  * Every audio frame carries SBR extension data and Parametric Stereo data.
 *    SBR and PS are defined only over an **AAC-LC** core (ISO/IEC 14496-3,
 *    HE-AAC / HE-AACv2); there is no HE-AAC "Main" profile.
 *  * A reference decode of the complete episode (FFmpeg 8.0.1, `-v warning
 *    -xerror`) completes with **zero** warnings/errors when the ASC is corrected
 *    to AAC-LC, and emits one `Parametric Stereo signaled to be not-present but
 *    was found in the bitstream` warning per audio frame (30581) when the
 *    declared profile is left as AAC Main.
 *  * Android's platform AAC decoder (`c2.android.aac.decoder`, FDK-based) is the
 *    only AAC decoder exposed on the test device. It rejects the configuration
 *    outright: `aacDecoder_ConfigRaw decoderErr = 0x2003`, then
 *    `Codec reported err 0xe/14`, which fails Media3 playback with
 *    `Failed to decode media samples` and `mp4a.40.1` in the reported format.
 *
 * ## What it does — and deliberately does not do
 *
 * It rewrites **one byte** of container metadata (the 5-bit AOT inside the
 * AudioSpecificConfig) from `1` (AAC Main) to `2` (AAC-LC).
 *
 *  * No audio frame is touched — `mdat` stays byte-identical.
 *  * No re-encode, no transcode, no codec change: the stream is still the same
 *    AAC bitstream, stream copy is preserved end-to-end.
 *  * Sampling frequency index, channel configuration, `objectTypeIndication`,
 *    bitrate descriptors and every other box are left untouched.
 *
 * It is intentionally narrow: it only fires when `objectTypeIndication` is
 * `0x40` (MPEG-4 Audio) **and** the ASC's AOT is exactly `1`. Everything else
 * is reported as [Action.NOT_NEEDED], and any structural surprise is reported
 * as [Action.SKIPPED] without failing assembly — a non-normalized file is still
 * a valid MP4, it is just not decodable by Android's platform decoder.
 *
 * The Python reference build propagates the packager's AAC-Main declaration
 * unchanged, so this correction is a deliberate divergence from
 * Python output.
 */
object AudioSpecificConfigNormalizer {

    private const val TAG = "AscNormalizer"

    /** MPEG-4 Audio `objectTypeIndication` inside the DecoderConfigDescriptor. */
    private const val OTI_MPEG4_AUDIO = 0x40

    /** ISO/IEC 14496-3 audio object types. */
    private const val AOT_AAC_MAIN = 1
    private const val AOT_AAC_LC = 2
    private const val AOT_ESCAPE = 31

    /** Hard cap on the `esds` box we are willing to buffer (it is tiny in practice). */
    private const val MAX_ESDS_BYTES = 64 * 1024

    enum class Action {
        /** The ASC declared AAC Main and was rewritten to AAC-LC. */
        CORRECTED,
        /** The ASC does not declare AAC Main — nothing to do. */
        NOT_NEEDED,
        /** The file structure could not be navigated; the file is left untouched. */
        SKIPPED,
    }

    data class Result(val action: Action, val detail: String)

    /**
     * Normalizes the AudioSpecificConfig of [file] in place.
     *
     * Must be called on the assembled temporary output, before the atomic
     * rename. Never throws: a file that cannot be navigated is returned
     * unchanged with [Action.SKIPPED].
     */
    fun normalize(file: File): Result {
        if (!file.isFile || file.length() < 16) {
            return Result(Action.SKIPPED, "file missing or too small")
        }
        return try {
            RandomAccessFile(file, "rw").use { raf -> normalize(raf, file.length()) }
        } catch (e: Exception) {
            AppLog.w(TAG, "AudioSpecificConfig normalization skipped: ${e.message}")
            Result(Action.SKIPPED, "error: ${e.message}")
        }
    }

    private fun normalize(raf: RandomAccessFile, fileLength: Long): Result {
        val moov = walkTopLevel(raf, fileLength, "moov")
            ?: return Result(Action.SKIPPED, "moov not found")

        var trakOffset = moov.contentStart
        while (true) {
            val trak = childBox(raf, trakOffset, moov.end) ?: break
            if (trak.name == "trak") {
                val result = normalizeTrak(raf, trak)
                if (result != null) return result
            }
            trakOffset = trak.end
        }
        return Result(Action.SKIPPED, "no normalizable audio esds found")
    }

    /** Returns a result when this trak owns the audio `esds`, else null to try the next trak. */
    private fun normalizeTrak(raf: RandomAccessFile, trak: Box): Result? {
        val mdia = firstChild(raf, trak.contentStart, trak.end, "mdia") ?: return null
        val handlerType = handlerType(raf, mdia) ?: return null
        if (handlerType != "soun") return null

        val minf = firstChild(raf, mdia.contentStart, mdia.end, "minf") ?: return null
        val stbl = firstChild(raf, minf.contentStart, minf.end, "stbl") ?: return null
        val stsd = firstChild(raf, stbl.contentStart, stbl.end, "stsd") ?: return null

        // stsd is a FullBox: version/flags (4) + entry_count (4).
        val entriesStart = stsd.contentStart + 8
        if (entriesStart >= stsd.end) return null
        val sampleEntry = childBox(raf, entriesStart, stsd.end) ?: return null

        // SampleEntry: reserved(6) + data_reference_index(2);
        // AudioSampleEntry adds reserved(8) + channelcount(2) + samplesize(2)
        // + pre_defined(2) + reserved(2) + samplerate(4) = 20.
        val esdsStart = sampleEntry.contentStart + 8 + 20
        val esds = firstChild(raf, esdsStart, sampleEntry.end, "esds")
            ?: findBoxBySignature(raf, esdsStart, sampleEntry.end, "esds")
            ?: return null

        val ascOffset = locateAudioSpecificConfig(raf, esds)
            ?: return Result(Action.SKIPPED, "esds structure not understood")

        raf.seek(ascOffset)
        val first = raf.readUnsignedByte()
        val aot = (first ushr 3) and 0x1F
        if (aot == AOT_ESCAPE) {
            return Result(Action.NOT_NEEDED, "audioObjectType uses the escape code")
        }
        if (aot != AOT_AAC_MAIN) {
            return Result(Action.NOT_NEEDED, "audioObjectType=$aot")
        }

        val corrected = (first and 0x07) or (AOT_AAC_LC shl 3)
        raf.seek(ascOffset)
        raf.write(corrected)
        AppLog.i(
            TAG,
            "Corrected AudioSpecificConfig at offset $ascOffset: audioObjectType " +
                "$AOT_AAC_MAIN (AAC Main) -> $AOT_AAC_LC (AAC-LC)"
        )
        return Result(Action.CORRECTED, "audioObjectType $AOT_AAC_MAIN -> $AOT_AAC_LC")
    }

    /**
     * Returns the absolute file offset of the AudioSpecificConfig byte,
     * or null when the `esds` chain is not the expected MPEG-4 shape.
     */
    private fun locateAudioSpecificConfig(raf: RandomAccessFile, esds: Box): Long? {
        val payloadOffset = esds.contentStart + 4 // FullBox version/flags
        val available = esds.end - payloadOffset
        if (available < 8 || available > MAX_ESDS_BYTES) return null

        raf.seek(payloadOffset)
        val buf = ByteArray(available.toInt())
        raf.readFully(buf)

        var p = 0

        fun u8(): Int {
            if (p >= buf.size) throw IOException("esds truncated")
            return buf[p++].toInt() and 0xFF
        }

        fun descLen(): Int {
            var len = 0
            repeat(4) {
                val c = u8()
                len = (len shl 7) or (c and 0x7F)
                if (c and 0x80 == 0) return len
            }
            return len
        }

        if (u8() != 0x03) return null // ES_Descriptor
        val esLen = descLen()
        val esEnd = p + esLen
        if (esEnd > buf.size) return null

        p += 2 // ES_ID
        val flags = u8()
        if (flags and 0x80 != 0) p += u8() // URL flags
        if (flags and 0x40 != 0) p += 2     // OCR_ES_Id

        while (p < esEnd) {
            val tag = u8()
            val len = descLen()
            val end = p + len
            if (end > esEnd) return null
            if (tag == 0x04) { // DecoderConfigDescriptor
                if (len < 13) return null
                val objectTypeIndication = buf[p].toInt() and 0xFF
                p += 13 // oti + streamType + bufferSizeDB + maxBitRate + avgBitRate
                while (p < end) {
                    val innerTag = u8()
                    val innerLen = descLen()
                    val innerEnd = p + innerLen
                    if (innerEnd > end) return null
                    if (innerTag == 0x05) { // DecoderSpecificInfo
                        if (innerLen < 1) return null
                        if (objectTypeIndication != OTI_MPEG4_AUDIO) return null
                        return payloadOffset + p
                    }
                    p = innerEnd
                }
            } else {
                p = end
            }
        }
        return null
    }

    private fun handlerType(raf: RandomAccessFile, mdia: Box): String? {
        val hdlr = firstChild(raf, mdia.contentStart, mdia.end, "hdlr") ?: return null
        // FullBox(4) + pre_defined(4) → handler_type (4)
        val at = hdlr.contentStart + 8
        if (at + 4 > hdlr.end) return null
        raf.seek(at)
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, 0, 4, Charsets.ISO_8859_1)
    }

    private fun walkTopLevel(raf: RandomAccessFile, fileLength: Long, name: String): Box? {
        var offset = 0L
        while (offset + 8 <= fileLength) {
            val box = childBox(raf, offset, fileLength) ?: return null
            if (box.name == name) return box
            if (box.end <= offset) return null
            offset = box.end
        }
        return null
    }

    private fun firstChild(raf: RandomAccessFile, from: Long, limit: Long, name: String): Box? {
        var offset = from
        while (offset + 8 <= limit) {
            val box = childBox(raf, offset, limit) ?: return null
            if (box.name == name) return box
            if (box.end <= offset) return null
            offset = box.end
        }
        return null
    }

    /**
     * Fallback used when a sample entry's child chain does not start at the
     * expected offset: scan for the literal 4cc inside [limit].
     */
    private fun findBoxBySignature(
        raf: RandomAccessFile,
        from: Long,
        limit: Long,
        type: String
    ): Box? {
        val span = (limit - from).coerceAtMost(4096L)
        if (span < 8) return null
        raf.seek(from)
        val buf = ByteArray(span.toInt())
        raf.readFully(buf)
        val needle = type.toByteArray(Charsets.ISO_8859_1)
        var i = 0
        while (i + 8 <= buf.size) {
            if (buf[i + 4] == needle[0] && buf[i + 5] == needle[1] &&
                buf[i + 6] == needle[2] && buf[i + 7] == needle[3]
            ) {
                val size = ((buf[i].toInt() and 0xFF) shl 24) or
                    ((buf[i + 1].toInt() and 0xFF) shl 16) or
                    ((buf[i + 2].toInt() and 0xFF) shl 8) or
                    (buf[i + 3].toInt() and 0xFF)
                if (size >= 12 && from + i + size <= limit) {
                    return Box(type, from + i, size.toLong(), 8)
                }
            }
            i++
        }
        return null
    }

    private fun childBox(raf: RandomAccessFile, offset: Long, limit: Long): Box? {
        if (offset < 0 || offset + 8 > limit) return null
        raf.seek(offset)
        val head = ByteArray(8)
        raf.readFully(head)
        var size = ((head[0].toLong() and 0xFF) shl 24) or
            ((head[1].toLong() and 0xFF) shl 16) or
            ((head[2].toLong() and 0xFF) shl 8) or
            (head[3].toLong() and 0xFF)
        var header = 8
        if (size == 1L) {
            if (offset + 16 > limit) return null
            val wide = ByteArray(8)
            raf.readFully(wide)
            var wideSize = 0L
            for (b in wide) wideSize = (wideSize shl 8) or (b.toLong() and 0xFF)
            size = wideSize
            header = 16
        } else if (size == 0L) {
            size = limit - offset
        }
        if (size < header) return null
        if (offset + size > limit) return null
        val name = String(head, 4, 4, Charsets.ISO_8859_1)
        return Box(name, offset, size, header)
    }

    private data class Box(val name: String, val offset: Long, val size: Long, val header: Int) {
        val contentStart: Long get() = offset + header
        val end: Long get() = offset + size
    }
}
