package com.pahebatcher.app.data.hls

import com.pahebatcher.app.logging.AppLog
import java.io.File

/**
 * File-based storage for HLS segments with atomic writes.
 *
 * Ported from Python store.py SegmentStore.
 * Ensures segment files are never partially written.
 *
 * Python reference (store.py lines 31-43):
 * ```python
 * def seg_path(self, idx: int) -> Path:
 *     return self.dir / f"{idx:06d}.ts"
 *
 * def done_indices(self) -> set[int]:
 *     return {int(p.stem) for p in self.dir.glob("??????.ts")}
 *
 * def write_seg(self, idx: int, data: bytes) -> None:
 *     tmp = self.seg_path(idx).with_suffix(".tmp")
 *     tmp.write_bytes(data)
 *     tmp.rename(self.seg_path(idx))
 * ```
 *
 * Constraint 10: Atomic segment storage
 * - Write to .tmp file first
 * - Rename to .ts only after successful complete write
 * - .ts file must never be treated as complete merely because it exists
 * - Interrupted write leaves .tmp (not considered completed)
 *
 * Final-audit H2: `open` + [writeSeg] `open` exist ONLY so tests can supply a
 * store whose write fails the way a full volume fails (the OS reports ENOSPC as
 * text, which no test can provoke on a healthy volume). Production constructs
 * exactly this class; no behavior changed.
 */
open class SegmentFileStore(
    private val directory: File
) {

    companion object {
        private const val TAG = "SegmentFileStore"

        /**
         * The cache-size/temporary-cache set is
         * exactly `*.ts`, `*.tmp` and `concat.txt` — the assembled `*.mp4` and
         * everything exported out of this directory (SAF/MediaStore) are never
         * counted and never removed by cache clearing. This predicate is the
         * single definition both the size display and the clear action use, so
         * the number shown is the number that action frees.
         */
        fun isTemporaryArtifact(name: String): Boolean =
            name.endsWith(".ts") || name.endsWith(".tmp") || name == "concat.txt"
    }

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    /**
     * Get the segment directory path.
     */
    fun getDirectory(): File = directory

    /**
     * Get the path for a segment file.
     * Format: {idx:06d}.ts
     */
    fun segPath(idx: Int): File {
        return File(directory, "%06d.ts".format(idx))
    }

    /**
     * Check if a completed segment file exists.
     * Only considers .ts files (not .tmp files).
     */
    fun hasSeg(idx: Int): Boolean {
        return segPath(idx).exists()
    }

    /**
     * Get the set of completed segment indices.
     * Only considers .ts files (not .tmp files).
     *
     * Python: done_indices() globs "??????.ts"
     */
    fun doneIndices(): Set<Int> {
        return directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".ts") && it.name.length == 9 }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Atomically write a segment file.
     *
     * 1. Write data to .tmp file
     * 2. Rename .tmp to .ts
     *
     * This ensures:
     * - A .ts file is only present if the write completed successfully
     * - Interrupted writes leave .tmp files (not counted as completed)
     * - No partial .ts files exist
     */
    open fun writeSeg(idx: Int, data: ByteArray) {
        val finalFile = segPath(idx)
        val tmpFile = File(directory, "%06d.tmp".format(idx))

        try {
            // Step 1: Write to temporary file
            tmpFile.writeBytes(data)

            // Step 2: Atomic rename to final file
            val renamed = tmpFile.renameTo(finalFile)
            if (!renamed) {
                // Fallback: retry the rename (covers a transient destination race).
                // DL-010: do NOT delete a pre-existing finalFile first — .tmp and .ts
                // live in the same directory, so this rename can never be cross-device;
                // deleting first could destroy a VALID completed segment if the retry
                // then fails. A same-directory renameTo succeeds when the destination
                // is absent, so clear it ONLY if the first retry did not take.
                val renamed2 = tmpFile.renameTo(finalFile)
                if (!renamed2) {
                    finalFile.delete()
                    val renamed3 = tmpFile.renameTo(finalFile)
                    if (!renamed3) {
                        AppLog.e(TAG, "Failed to rename segment $idx from .tmp to .ts")
                        tmpFile.delete()
                        throw RuntimeException("Failed to write segment $idx: rename failed")
                    }
                }
            }

            AppLog.d(TAG, "Wrote segment $idx: ${data.size} bytes")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to write segment $idx: ${e.message}")
            tmpFile.delete()
            throw e
        }
    }

    /**
     * Get the number of completed segments.
     */
    fun countCompleted(): Int {
        return doneIndices().size
    }

    /**
     * Gap 2 (SPEC §25) — byte total of the COMPLETED `.ts` segments only.
     *
     * Exactly [doneIndices] summed with `length()`, so:
     * * `.tmp` partial writes never count (they are not completed segments);
     * * the assembled `*.mp4` never counts (it is not segment data);
     * * an interrupted write that left no `.ts` contributes nothing.
     *
     * This is a directory walk, so callers use it ONCE per download attempt to
     * establish the resume base (already-downloaded segments) instead of
     * re-scanning on every progress callback.
     */
    fun completedSegmentBytes(): Long {
        val indices = doneIndices()
        if (indices.isEmpty()) return 0L
        return indices.sumOf { segPath(it).length() }
    }

    /**
     * Clean up segment artifacts after assembly, preserving assembled outputs.
     *
     * The final MP4 lives INSIDE the segment
     * directory (architecture §2: `File(segmentStore.getDirectory(), name)`),
     * so cleanup must NEVER delete it. Only non-output artifacts are removed:
     * .ts segments, .tmp partial writes, concat.txt, etc. If no *.mp4 output
     * is present, the directory itself is removed (no reason to keep an empty
     * staging directory — matches the pre-corrective behavior when there is
     * nothing to preserve).
     *
     * This makes "cleanup after successful assembly" safe to run at any time:
     * an interrupted run can repeat it, and startup reconciliation can run it
     * without ever destroying a COMPLETED episode's output.
     */
    fun cleanup() {
        try {
            val children = directory.listFiles()
            if (children == null) {
                return
            }
            var hasOutput = false
            for (child in children) {
                if (child.isFile && child.name.endsWith(".mp4")) {
                    // Assembled output lives alongside segments — never delete it
                    hasOutput = true
                    continue
                }
                child.deleteRecursively()
            }
            if (!hasOutput) {
                directory.delete()
            }
            AppLog.d(
                TAG,
                "Cleaned up segment artifacts in: ${directory.absolutePath} (output preserved: $hasOutput)"
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to cleanup segment directory: ${e.message}")
        }
    }

    /**
     * Clean up orphaned .tmp files (from interrupted writes).
     */
    fun cleanupOrphans() {
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tmp") }
            ?.forEach { tmpFile ->
                AppLog.d(TAG, "Cleaning up orphaned temp file: ${tmpFile.name}")
                tmpFile.delete()
            }
    }

    /**
     * Byte size of the temporary artifact set only
     * (`*.ts`, `*.tmp`, `concat.txt`). Assembled `*.mp4` output is excluded —
     * exported media lives outside this directory anyway. Never touches
     * SAF/MediaStore (this is a pure `java.io.File` walk of app-private
     * staging).
     */
    fun temporaryBytes(): Long {
        val children = directory.listFiles() ?: return 0L
        return children
            .filter { it.isFile && isTemporaryArtifact(it.name) }
            .sumOf { it.length() }
    }
}
