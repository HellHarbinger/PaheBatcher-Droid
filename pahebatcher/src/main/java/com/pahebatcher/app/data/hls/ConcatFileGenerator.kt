package com.pahebatcher.app.data.hls

import com.pahebatcher.app.logging.AppLog
import java.io.File

/**
 * Generates FFmpeg concat demuxer input files.
 *
 * Ported from Python store.py SegmentStore.assemble() concat file generation.
 *
 * Python format (store.py lines 56-63):
 * ```python
 * lst = self.dir / "concat.txt"
 * lst.write_text("\n".join(f"file '{self.seg_path(i)}'" for i in range(n)))
 * ```
 *
 * Android: the segment file indices come from the parsed playlist (playlist
 * order), because SegmentFileStore is keyed by `HlsSegment.index`
 * (`#EXT-X-MEDIA-SEQUENCE`), not necessarily by `enumerate()` position.
 * The generated line order IS the concatenation order, so the caller must
 * pass the indices in playlist order. For a zero-based store this is exactly
 * Python's `range(n)`.
 *
 * FFmpeg concat demuxer format:
 * ```
 * file '/absolute/path/to/000000.ts'
 * file '/absolute/path/to/000001.ts'
 * ```
 *
 * Key requirements:
 * - Each line: file '{absolute_path}'
 * - Single quotes around paths
 * - One file per line
 * - No header, no trailer
 * - Absolute paths required
 *
 * Responsibility: This class ONLY generates the concat file content.
 * Segment existence validation is the caller's responsibility
 * (FfmpegEpisodeAssembler.assemble() validates before calling).
 */
object ConcatFileGenerator {

    private const val TAG = "ConcatFileGenerator"
    private const val CONCAT_FILENAME = "concat.txt"

    /**
     * Generate concat demuxer input file.
     *
     * Assumes all segments exist — caller must validate beforehand.
     *
     * @param segmentDir Directory containing .ts segment files
     * @param segmentIndices Segment file indices in playlist (concatenation) order
     * @return The concat.txt File, or null if generation fails
     */
    fun generate(segmentDir: File, segmentIndices: List<Int>): File? {
        return try {
            val concatFile = File(segmentDir, CONCAT_FILENAME)

            // Generate concat file content — one line per index, in list order
            val lines = segmentIndices.joinToString("\n") { idx ->
                val segFile = File(segmentDir, "%06d.ts".format(idx))
                "file '${segFile.absolutePath}'"
            }

            concatFile.writeText(lines)
            AppLog.d(TAG, "Generated concat file with ${segmentIndices.size} segments")
            concatFile
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to generate concat file: ${e.message}")
            null
        }
    }

    /**
     * Clean up concat file.
     */
    fun cleanup(segmentDir: File) {
        val concatFile = File(segmentDir, CONCAT_FILENAME)
        if (concatFile.exists()) {
            concatFile.delete()
            AppLog.d(TAG, "Cleaned up concat file")
        }
    }
}
