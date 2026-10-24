package com.pahebatcher.app.domain.hls

import java.io.File

/**
 * Interface for assembling HLS TS segments into MP4.
 *
 * Ported from Python store.py SegmentStore.assemble().
 *
 * Python behavior (store.py lines 47-82):
 * 1. Check all segments exist
 * 2. Create concat.txt with file paths
 * 3. Run ffmpeg with concat demuxer
 * 4. If fails, fall back to pipe mode
 * 5. Clean up concat.txt on success
 * 6. Return success/failure
 *
 * Android implementation order (corrective): the single contiguous MPEG-TS input
 * runs FIRST and the concat demuxer is the fallback — deliberately the reverse of
 * Python, because the concat demuxer anchors each following file at the previous
 * file's reported duration and stretches the timeline at every segment boundary.
 * The measured cost of that stretch (142 video + 142 audio gaps, +8.5 s) and the
 * A/B evidence are recorded in [FfmpegEpisodeAssembler].
 *
 * Python primary   : ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -movflags +faststart {out}
 * Android primary  : ffmpeg -y -i {singleContiguousInput} -c copy -f mp4 -movflags +faststart {out}
 * Android fallback : ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -f mp4 -movflags +faststart {out}
 *
 * Constraint: Assembly must produce a valid, playable MP4.
 * Constraint: Assembly must not re-encode (use -c copy).
 * Constraint: Output must use +faststart for streaming.
 * Constraint: Timeout is 600 seconds (Python default).
 * Constraint: An AudioSpecificConfig declaring a profile Android's platform AAC
 * decoder rejects must be corrected in container metadata before the output is
 * renamed into place (AudioSpecificConfigNormalizer); the media payload is never
 * rewritten.
 */
interface EpisodeAssembler {

    /**
     * Configuration for a single assembly operation.
     *
     * Segment identity contract:
     *
     * The assembler does NOT assume a fixed index base. The authoritative list
     * of segment file names to concatenate is [segmentIndices], supplied in
     * **playlist order** by the caller that parsed the playlist.
     *
     * - Python (`downloader.py:77`, `store.py:47`) names files by the segment's
     *   `enumerate()` position, so `range(n)` is a correct default there and
     *   here for zero-based stores.
     * - Android's `SegmentFileStore` is keyed by `HlsSegment.index` (the
     *   playlist's `#EXT-X-MEDIA-SEQUENCE` value, which may start at 1), so the
     *   download pipeline passes `playlist.segments.map { it.index }`.
     *
     * This translation happens at the assembly boundary only — persisted
     * segment file names are never rewritten to satisfy an index assumption.
     * A base mismatch here would concatenate the wrong or missing segment files.
     *
     * @param segmentDir Directory containing completed .ts segment files
     * @param outputFile Target MP4 output file
     * @param segmentCount Expected number of segments (must equal [segmentIndices].size)
     * @param timeoutMs Assembly timeout in milliseconds (default: 600s matching Python)
     * @param keepTemp Whether to keep .ts segments after successful assembly
     * @param segmentIndices Ordered file index of each segment in playlist order;
     *                       each value is formatted as `%06d.ts` in [segmentDir]
     */
    data class AssemblyConfig(
        val segmentDir: File,
        val outputFile: File,
        val segmentCount: Int,
        val timeoutMs: Long = 600_000L,
        val keepTemp: Boolean = false,
        val segmentIndices: List<Int> = (0 until segmentCount).toList()
    )

    /**
     * Result of an assembly operation.
     *
     * @param success Whether assembly completed successfully
     * @param outputFile The final output file (null if assembly failed)
     * @param error Error message if assembly failed
     * @param timedOut Whether assembly timed out
     * @param cancelled Whether assembly was cancelled
     * @param ffmpegReturnCode FFmpeg return code (for debugging)
     */
    data class AssemblyResult(
        val success: Boolean,
        val outputFile: File? = null,
        val error: String? = null,
        val timedOut: Boolean = false,
        val cancelled: Boolean = false,
        val ffmpegReturnCode: Int? = null
    )

    /**
     * Assemble TS segments into MP4.
     *
     * The implementation must:
     * 1. Verify all expected segments exist
     * 2. Assemble through ONE contiguous input first (primary), generating a
     *    concat input file only for the fallback run when that fails
     * 3. Normalize the result's AudioSpecificConfig when it declares a profile
     *    Android's platform decoder rejects (never rewrites media payload)
     * 4. Produce atomic output (write to .tmp, rename on success)
     * 5. Clean up temporary files (concat.txt, contiguous input, .tmp output)
     * 6. Return success/failure with appropriate error details
     *
     * @param config Assembly configuration
     * @return AssemblyResult with success/failure details
     */
    suspend fun assemble(config: AssemblyConfig): AssemblyResult
}
