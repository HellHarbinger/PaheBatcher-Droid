package com.pahebatcher.app.data.hls

import android.os.Build
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.domain.hls.EpisodeAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * FFmpegKit implementation of EpisodeAssembler.
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
 * Check order in assemble():
 * 1. Existing non-empty output → skip (Python: downloader.py:60)
 * 2. Config integrity (segmentIndices.size == segmentCount)
 * 3. Segment existence for config.segmentIndices
 * 4. ABI availability (native execution gate)
 * 5. Single contiguous MPEG-TS input → concat demuxer → atomic rename
 *
 * Assembly order (corrective):
 * The single contiguous MPEG-TS input is the PRIMARY path; the concat demuxer
 * is the FALLBACK. This is deliberately the reverse of Python store.py, and the
 * reason is measured, not stylistic:
 *
 * The concat demuxer positions every following input file at the PREVIOUS
 * file's reported format duration instead of at the previous file's actual
 * content end. For MPEG-TS HLS segments the reported duration ends on the
 * segment's video tail while the next segment's content begins on its audio
 * head, so each boundary gains ~60 ms. Those per-boundary overhangs accumulate
 * into a stretched output timeline.
 *
 * Measured on the title-matched pair (The World's Strongest Rearguard Ep. 1,
 * 143 source segments / 34046 video frames / 30582 audio frames):
 *   concat demuxer : 142 video + 142 audio gaps, +8.498 s video, +8.503 s audio
 *   single input   : 0 gaps, video total_ticks = 127800172
 *   Python reference: 0 gaps, video total_ticks = 127800172
 * i.e. only the single-input path reproduces the Python timeline. Summing the
 * measured per-boundary overhangs of the same content split into segments
 * reproduces the observed excess exactly (8.421006 s predicted vs 8.4206 s
 * measured), which is what pins the cause on the concat demuxer's
 * duration-anchored offset rather than on segment contents.
 *
 * Segment index contract (Phase 11.5E-5, LIMITATIONS §26): segment files are
 * looked up by config.segmentIndices in playlist order — NOT by an assumed
 * 0-based range. Python names files by enumerate() position so range(n) is
 * correct there; Android's SegmentFileStore is keyed by HlsSegment.index
 * (EXT-X-MEDIA-SEQUENCE, which starts at 1 for the Kwik CDN playlist), so the
 * downloader passes the playlist's indices and the translation happens here,
 * at the assembly boundary. Persisted segment file names are never rewritten.
 *
 * Primary command (single contiguous MPEG-TS input):
 * - Python fallback: ffmpeg -y -i pipe:0 -c copy -movflags +faststart {out}
 * - Android: Writes all segments to a single temp file, then runs
 *   ffmpeg -y -i {tempFile} -c copy -f mp4 -movflags +faststart {out}
 * - Reason: FFmpegKit's executeWithArgumentsAsync API does not expose stdin piping,
 *   so the temp file stands in for pipe:0. Segment bytes are appended in playlist
 *   order, so the mpegts demuxer reads one continuous timeline and no per-file
 *   timestamp anchoring occurs. This is a documented Android-specific adaptation,
 *   NOT a direct pipe parity.
 *
 * Fallback command (concat demuxer):
 * ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -f mp4 -movflags +faststart {out}
 * Retained as the secondary path (and as Python's primary command) for inputs the
 * single-input path cannot open; it is NOT used first because of the timeline
 * stretch documented above.
 *
 * Output handling:
 * - Atomic: writes to {outputFile}.tmp, renames on success
 * - Existing non-empty output is skipped (Python: out.exists() and st_size > 0)
 * - .tmp output is always cleaned up on failure (finally block)
 * - Concat file is always cleaned up (finally block)
 */
class FfmpegEpisodeAssembler : EpisodeAssembler {

    companion object {
        private const val TAG = "FfmpegEpisodeAssembler"
        private const val PIPE_TEMP_FILENAME = "_pipe_input.tmp"
    }

    /**
     * Check if FFmpegKit is supported on this device.
     *
     * FFmpegKit 8.1.7 supports arm64-v8a and x86_64.
     * Does NOT support armeabi-v7a or x86.
     */
    fun isSupported(): Boolean {
        val abis = Build.SUPPORTED_ABIS
        if (abis == null || abis.isEmpty()) return false
        return abis.any { it == "arm64-v8a" || it == "x86_64" }
    }

    /**
     * AQR-003: the whole assembly path is blocking file I/O (segment
     * verification, the full-episode byte copy, MP4 fixups). A CoroutineWorker's
     * doWork() runs on Dispatchers.Default — the CPU-bounded pool — so the
     * blocking work is moved onto Dispatchers.IO here. [executeFfmpeg] already
     * hops to IO internally; nesting on the same dispatcher is free.
     */
    override suspend fun assemble(config: EpisodeAssembler.AssemblyConfig): EpisodeAssembler.AssemblyResult =
        withContext(Dispatchers.IO) { assembleBlocking(config) }

    /** Body of [assemble]; see the override for why this runs on IO. */
    private suspend fun assembleBlocking(config: EpisodeAssembler.AssemblyConfig): EpisodeAssembler.AssemblyResult {
        // Check existing output first (Python: skip if out.exists() and st_size > 0).
        // This must precede segment validation: after a successful assembly the
        // segments may already be cleaned up, and a re-run must still succeed.
        if (config.outputFile.exists() && config.outputFile.length() > 0) {
            AppLog.d(TAG, "Output file already exists and is non-empty, skipping assembly")
            return EpisodeAssembler.AssemblyResult(
                success = true,
                outputFile = config.outputFile
            )
        }

        // Config integrity: segmentCount must describe the supplied index list.
        // A mismatch would silently assemble the wrong set of files.
        if (config.segmentIndices.size != config.segmentCount) {
            AppLog.e(
                TAG,
                "Inconsistent assembly config: ${config.segmentIndices.size} segment indices " +
                    "for expected count ${config.segmentCount}"
            )
            return EpisodeAssembler.AssemblyResult(
                success = false,
                error = "Assembly config mismatch: ${config.segmentIndices.size} segment indices " +
                    "for expected count ${config.segmentCount}"
            )
        }

        // Verify all expected segments exist before attempting assembly.
        // Existence is checked against the playlist's own file indices (e.g. a
        // media-sequence store holding 000001.ts..00146.ts), never an assumed
        // 0-based range.
        val segmentStore = SegmentFileStore(config.segmentDir)
        val missing = config.segmentIndices.filter { !segmentStore.hasSeg(it) }
        if (missing.isNotEmpty()) {
            AppLog.e(TAG, "Cannot assemble: ${missing.size} segments missing")
            return EpisodeAssembler.AssemblyResult(
                success = false,
                error = "Missing ${missing.size} segments before assembly"
            )
        }

        // Check ABI support — prevents native crash on unsupported devices.
        // Runs after the platform-independent checks above so that input errors
        // are reported as input errors on every platform.
        if (!isSupported()) {
            val abis = Build.SUPPORTED_ABIS?.joinToString() ?: "unknown"
            AppLog.e(TAG, "FFmpegKit not supported on this device (ABIs: $abis)")
            return EpisodeAssembler.AssemblyResult(
                success = false,
                error = "FFmpegKit native library not available on this device (ABI: $abis)"
            )
        }

        // Atomic output: write to .tmp, rename on success
        val tmpOutput = File(config.outputFile.parent, config.outputFile.name + ".tmp")

        try {
            // Primary: one contiguous MPEG-TS input (class doc — the concat demuxer
            // anchors each following file at the previous file's reported duration
            // and therefore inserts a ~60 ms timeline gap at every segment boundary).
            val primaryResult = executePipeFallback(config.segmentDir, config.segmentIndices, tmpOutput, config.timeoutMs)

            if (primaryResult.success) {
                if (validateOutput(tmpOutput)) {
                    normalizeAudioSpecificConfig(tmpOutput)
                    if (atomicRename(tmpOutput, config.outputFile)) {
                        AppLog.d(TAG, "Assembly succeeded via single contiguous MPEG-TS input")
                        return EpisodeAssembler.AssemblyResult(
                            success = true,
                            outputFile = config.outputFile
                        )
                    }
                    // Rename failed — fall through to cleanup
                    tmpOutput.delete()
                    return EpisodeAssembler.AssemblyResult(
                        success = false,
                        error = "Failed to rename output file"
                    )
                }
                // FFmpeg reported success but output invalid — fall through to fallback
                tmpOutput.delete()
            }

            // Single-input path failed — try the concat demuxer (Python's primary command).
            // The concat file is only needed by this fallback, so it is generated here.
            AppLog.w(TAG, "Single contiguous MPEG-TS input failed (code: ${primaryResult.returnCode}), trying concat demuxer")
            tmpOutput.delete()

            val concatFile = ConcatFileGenerator.generate(config.segmentDir, config.segmentIndices)
                ?: return EpisodeAssembler.AssemblyResult(
                    success = false,
                    error = "Failed to generate concat file"
                )

            val fallbackResult = executeConcatDemuxer(concatFile, tmpOutput, config.timeoutMs)

            if (fallbackResult.success) {
                if (validateOutput(tmpOutput)) {
                    normalizeAudioSpecificConfig(tmpOutput)
                    if (atomicRename(tmpOutput, config.outputFile)) {
                        AppLog.d(TAG, "Assembly succeeded via concat demuxer fallback")
                        return EpisodeAssembler.AssemblyResult(
                            success = true,
                            outputFile = config.outputFile
                        )
                    }
                    tmpOutput.delete()
                    return EpisodeAssembler.AssemblyResult(
                        success = false,
                        error = "Failed to rename output file"
                    )
                }
                tmpOutput.delete()
                return EpisodeAssembler.AssemblyResult(
                    success = false,
                    error = "Output file not created or empty after concat demuxer fallback"
                )
            }

            // Both methods failed
            tmpOutput.delete()
            return EpisodeAssembler.AssemblyResult(
                success = false,
                error = "FFmpeg single contiguous MPEG-TS input failed (code: ${primaryResult.returnCode}), concat demuxer fallback also failed (code: ${fallbackResult.returnCode})",
                ffmpegReturnCode = fallbackResult.returnCode
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmpOutput.delete()
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            tmpOutput.delete()
            AppLog.e(TAG, "Assembly failed: ${e.message}")
            return EpisodeAssembler.AssemblyResult(
                success = false,
                error = "Assembly failed: ${e.message}"
            )
        } finally {
            // Always clean up concat file — regardless of success/failure/cancellation
            ConcatFileGenerator.cleanup(config.segmentDir)
        }
    }

    /**
     * Normalizes the assembled MP4's AudioSpecificConfig before it is renamed
     * into place (see [AudioSpecificConfigNormalizer] for why).
     *
     * Never fails assembly: a file that cannot be navigated is simply left as
     * FFmpeg produced it, because it is still a structurally valid MP4.
     */
    private fun normalizeAudioSpecificConfig(tmpOutput: File) {
        val result = AudioSpecificConfigNormalizer.normalize(tmpOutput)
        when (result.action) {
            AudioSpecificConfigNormalizer.Action.CORRECTED ->
                AppLog.i(TAG, "AudioSpecificConfig corrected: ${result.detail}")
            AudioSpecificConfigNormalizer.Action.SKIPPED ->
                AppLog.w(TAG, "AudioSpecificConfig left unchanged: ${result.detail}")
            AudioSpecificConfigNormalizer.Action.NOT_NEEDED ->
                AppLog.d(TAG, "AudioSpecificConfig unchanged: ${result.detail}")
        }
    }

    /**
     * Execute FFmpeg with the concat demuxer (FALLBACK path).
     *
     * Command: ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -f mp4 -movflags +faststart {out}
     *
     * This is Python store.py's primary command. It is demoted to fallback here
     * because the concat demuxer anchors each input file at the previous file's
     * reported duration, which for MPEG-TS HLS segments inserts a timeline gap at
     * every segment boundary (class doc).
     *
     * Note: -f mp4 is required because the atomic output pattern writes to {output}.tmp,
     * and FFmpeg cannot auto-detect the MP4 muxer from a .tmp extension.
     */
    private suspend fun executeConcatDemuxer(
        concatFile: File,
        outputFile: File,
        timeoutMs: Long
    ): FfmpegResult {
        val arguments = listOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", concatFile.absolutePath,
            "-c", "copy",
            "-f", "mp4",
            "-movflags", "+faststart",
            outputFile.absolutePath
        )

        return executeFfmpeg(arguments, timeoutMs)
    }

    /**
     * Execute FFmpeg with a single contiguous MPEG-TS input (PRIMARY path).
     *
     * Python's corresponding form: ffmpeg -y -i pipe:0 -c copy -movflags +faststart {out}
     *
     * Android adaptation: FFmpegKit's executeWithArgumentsAsync does not expose
     * stdin piping. Instead, all segments are concatenated into a single temp file
     * and fed as input. The mpegts demuxer then reads one continuous timeline, so
     * there is no per-file timestamp anchoring and no boundary gaps — measured to
     * reproduce the Python reference timeline exactly (0 gaps,
     * video total_ticks = 127800172), whereas the concat-demuxer path measured
     * 142 gaps / +8.498 s on the same content (class doc).
     *
     * Segments are appended in exactly the order given by [segmentIndices]
     * (playlist order), so the input is the same byte sequence the concat
     * demuxer would have been given.
     *
     * The temp file is cleaned up in a finally block.
     *
     * Note: -f mp4 is required because the atomic output pattern writes to {output}.tmp,
     * and FFmpeg cannot auto-detect the MP4 muxer from a .tmp extension.
     *
     * The temp file is required because FFmpegKit's async API exposes no stdin.
     */
    private suspend fun executePipeFallback(
        segmentDir: File,
        segmentIndices: List<Int>,
        outputFile: File,
        timeoutMs: Long
    ): FfmpegResult {
        val pipeInput = File(segmentDir, PIPE_TEMP_FILENAME)
        try {
            // Concatenate all segments into a single temp file (playlist order)
            FileOutputStream(pipeInput).use { output ->
                for (idx in segmentIndices) {
                    val segFile = File(segmentDir, "%06d.ts".format(idx))
                    FileInputStream(segFile).use { input ->
                        input.copyTo(output)
                    }
                }
            }

            val arguments = listOf(
                "-y",
                "-i", pipeInput.absolutePath,
                "-c", "copy",
                "-f", "mp4",
                "-movflags", "+faststart",
                outputFile.absolutePath
            )

            return executeFfmpeg(arguments, timeoutMs)
        } catch (e: CancellationException) {
            // DL-008: cancellation must propagate, not be converted into a failure
            // that triggers the concat-demuxer fallback — that would run one more
            // FFmpeg attempt for work the caller already abandoned (AGENTS §13).
            // pipeInput.delete() still runs in the finally below.
            AppLog.d(TAG, "Assembly cancelled — not attempting pipe fallback")
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to create pipe input: ${e.message}")
            return FfmpegResult(false, -1)
        } finally {
            pipeInput.delete()
        }
    }

    /**
     * Execute FFmpeg with timeout and cancellation support.
     *
     * Cancellation behavior:
     * - FFmpegKit.cancel(sessionId) sends a signal to terminate the underlying process
     * - The async callback fires after cancellation with a non-success return code
     * - The coroutine continuation resumes with failure, propagating through the caller
     *
     * FFmpegKit cancellation limitation:
     * - FFmpegKit.cancel() sends SIGINT to the FFmpeg process
     * - The process may take a brief moment to terminate after the signal
     * - During this window, the .tmp output may still be written to
     * - The .tmp is cleaned up in the finally block of assemble(), ensuring no
     *   stale partial output survives even if cancellation is slightly delayed
     */
    private suspend fun executeFfmpeg(
        arguments: List<String>,
        timeoutMs: Long
    ): FfmpegResult {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val session = FFmpegKit.executeWithArgumentsAsync(
                        arguments.toTypedArray()
                    ) { session ->
                        if (continuation.isActive) {
                            val rc = session.returnCode
                            val returnCode = rc.toString().toIntOrNull() ?: -1
                            val success = ReturnCode.isSuccess(rc)
                            continuation.resumeWith(Result.success(FfmpegResult(success, returnCode)))
                        }
                    }

                    continuation.invokeOnCancellation {
                        session?.let {
                            FFmpegKit.cancel(it.sessionId)
                            AppLog.d(TAG, "Cancelled FFmpeg session ${it.sessionId}")
                        }
                    }
                }
            } ?: run {
                // Timeout occurred — FFmpegKit.cancel() will be called by
                // the withTimeoutOrNull cancellation mechanism on the next
                // coroutine resume point. The .tmp cleanup is handled by assemble().
                AppLog.e(TAG, "FFmpeg assembly timed out after ${timeoutMs}ms")
                FfmpegResult(false, -1, timedOut = true)
            }
        }
    }

    /**
     * Validate that output file exists and is non-empty.
     */
    private fun validateOutput(file: File): Boolean {
        return file.exists() && file.length() > 0
    }

    /**
     * Atomic rename with fallback (same pattern as SegmentFileStore).
     *
     * AQR-023 / DL-010 ordering: rename first, retry ONCE without touching the
     * destination, and only then clear a still-blocking target. The previous
     * order (delete → retry) destroyed a VALID pre-existing output the moment
     * the first rename hit a transient failure, even when no delete was needed.
     * `.tmp` and the output live in the same directory, so this rename is never
     * cross-device.
     */
    private fun atomicRename(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        // Retry first — covers a transient destination race without destroying
        // the previous good output.
        if (source.renameTo(target)) return true
        // Only a target that still blocks after the retry gets cleared.
        target.delete()
        if (source.renameTo(target)) return true
        AppLog.e(TAG, "Failed to rename ${source.name} to ${target.name}")
        return false
    }

    /**
     * Internal result type for FFmpeg execution.
     */
    private data class FfmpegResult(
        val success: Boolean,
        val returnCode: Int,
        val timedOut: Boolean = false
    )
}
