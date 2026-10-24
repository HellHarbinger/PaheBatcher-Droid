package com.pahebatcher.app.data.hls

import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogUrlSanitizer
import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsPlaylistParser
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.domain.hls.EpisodeAssembler
import com.pahebatcher.app.domain.model.StreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Downloads HLS segments for a single episode.
 *
 * Ported from Python downloader.py EpisodeDownloader.run().
 *
 * Python behavior (downloader.py lines 45-143):
 * 1. Fetch M3U8 playlist via HTTP GET
 * 2. Parse segments (handle master playlist → media playlist)
 * 3. Fetch AES keys (with caching)
 * 4. Download segments concurrently (bounded by semaphore)
 * 5. Decrypt if encrypted
 * 6. Write atomically (.tmp → .ts)
 * 7. Assemble (Phase 9)
 * 8. Cleanup (Phase 9)
 *
 * Constraint 7: Phase 7 consumes an already-resolved StreamInfo.
 * Does NOT re-run AnimePahe → play page → Kwik → M3U8 resolution.
 *
 * Constraint 12: Concurrency
 * - Bounded by Semaphore(hlsWorkers)
 * - Failed segments do not cancel siblings
 * - DB updates are safe (not in Phase 7 scope)
 * - Key cache is thread-safe
 * - Memory usage bounded by concurrency * segment size
 *
 * Typed HlsTextResult/HlsBytesResult for error classification.
 * wasCancelled field for cancellation detection.
 * Output path uses segmentStore.getDirectory() (not parentFile).
 */
class EpisodeDownloader(
    private val hlsClient: HlsClient,
    private val keyCache: AesKeyCache,
    private val segmentStore: SegmentFileStore,
    private val assembler: EpisodeAssembler? = null,
    private val hlsWorkers: Int = 24,
    private val autoRetry: Int = 2,
    private val keepTemp: Boolean = false,
    /**
     * Final-audit H2 — free bytes on the volume holding the segment
     * directory, defaulted to [StorageSpace.availableBytes] (`File.usableSpace`).
     * Injected as a function so the pre-flight gate is exercisable in JVM tests
     * without filling a real disk; production never passes anything else.
     */
    private val availableBytes: (File) -> Long = StorageSpace::availableBytes,
) {

    companion object {
        private const val TAG = "EpisodeDownloader"

        // Python retry constants (config.py)
        // HTTP-level: 5 attempts, 0.5s base delay
        private const val RETRY_ATTEMPTS = 5
        private const val RETRY_BASE_DELAY_MS = 500L

        // Python episode-level: auto_retry + 1 attempts, 1.5s base delay
        // (downloader.py lines 65, 142: 1.5 * (2**attempt))
        private const val EPISODE_RETRY_BASE_DELAY_MS = 1500L

        // Python segment size hint (config.py)
        // _SEG_HINT_BYTES = 188 * 512 = 96,256 bytes (~94 KB)
        // With 24 workers: max memory = 24 * ~100KB = ~2.4 MB (acceptable)
        private const val SEGMENT_SIZE_HINT_BYTES = 188 * 512
    }

    /**
     * Result of an episode download attempt.
     *
     * Added wasCancelled field.
     */
    data class DownloadResult(
        val success: Boolean,
        val totalSegments: Int = 0,
        val completedSegments: Int = 0,
        val failedSegments: Int = 0,
        val error: String? = null,
        val assembled: Boolean = false,
        val outputFile: File? = null,
        val assemblyError: String? = null,
        val wasCancelled: Boolean = false,
        /**
         * 11.5E-6: this run short-circuited on an existing, non-empty output
         * (Python `downloader.py:60-62` "already exists" skip) — no playlist was
         * fetched and no segment was touched, so the Worker must not force a
         * final progress write over the row's persisted counts.
         */
        val reusedExistingOutput: Boolean = false,
        /**
         * The typed class this failure was
         * CONSTRUCTED with — set at every construction site by origin, never
         * read back from [error] text. Read by the in-run episode retry
         * decision below and by `DownloadWorker`'s auto-resume eligibility.
         * [DownloadErrorClass.NONE] on every success.
         */
        val errorClass: DownloadErrorClass = DownloadErrorClass.NONE
    )

    /**
     * Internal fetch result distinguishing permanent vs transient errors.
     *
     * Permanent errors (403/404) should NOT be retried.
     */
    private sealed class FetchResult {
        data class Success(val content: String) : FetchResult()
        data class PermanentError(val message: String) : FetchResult()
        data class TransientError(val message: String) : FetchResult()
    }

    private sealed class FetchBytesResult {
        data class Success(val bytes: ByteArray) : FetchBytesResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Success) return false
                return bytes.contentEquals(other.bytes)
            }
            override fun hashCode(): Int = bytes.contentHashCode()
        }
        data class PermanentError(val message: String) : FetchBytesResult()
        data class TransientError(val message: String) : FetchBytesResult()
    }

    /**
     * Download all segments for an episode, then assemble into MP4.
     *
     * Includes episode-level retry matching Python downloader.py behavior:
     * - auto_retry + 1 total attempts (default: 3)
     * - Exponential backoff: 1.5 * (2^attempt) seconds
     * - Permanent errors ("No segments", "Assembly failed", HTTP 403/404) are NOT retried
     * - Non-permanent errors trigger retry
     *
     * @param streamInfo Resolved stream information (M3U8 URL + headers)
     * @param episodeNumber Episode number (Int or Double for half-episodes)
     * @param episodeTitle Episode title for output filename
     * @param onProgress Optional progress callback
     *   (completedSegments, totalSegments, downloadedBytes) — `downloadedBytes`
     *   is the running total of successfully written DECRYPTED segment bytes
     *   (SPEC §25): the bytes already on disk at attempt start plus every
     *   segment whose atomic `.tmp → .ts` write has since succeeded.
     * @param onManifestResolved Optional callback invoked once when the media
     *   playlist resolves the segment count (PHASE11_ARCHITECTURE_V2 §16.2:
     *   one-time totalSegments persistence at manifest resolution — invoked
     *   before/independently of segment processing, never per-segment). Invoked
     *   again only if a retry re-parses a playlist (write-on-change guard lives
     *   in the DAO). Callback failures are logged, never propagated.
     * @param onAssemblyStart Final-audit H4 — invoked ONCE, immediately before
     *   the FFmpeg mux runs, so the caller can persist the SPEC §25 **remuxing**
     *   state (`STATE_ASSEMBLING`) on the row it owns. Never invoked on a run
     *   that skips assembly (existing-output short-circuit) or that never
     *   reaches Step 7. A failed state write is logged, never propagated — the
     *   mux itself must not fail because a status row could not be updated.
     * @return DownloadResult
     */
    suspend fun download(
        streamInfo: StreamInfo,
        episodeNumber: Number = 0,
        episodeTitle: String = "Episode",
        onProgress: ((Int, Int, Long) -> Unit)? = null,
        onManifestResolved: (suspend (Int) -> Unit)? = null,
        onAssemblyStart: (suspend () -> Unit)? = null
    ): DownloadResult {
        // AQR-021: stream URLs are expiring CDN authorisation — host + path only
        // (AGENTS §10, the Phase 17 §8 logging fix restated for this call site).
        AppLog.d(TAG, "Starting download: ${LogUrlSanitizer.url(streamInfo.url)}")

        // Python downloader.py:60-62 — an existing, non-empty output short-circuits
        // BEFORE any playlist fetch (the "(already exists)" skip). Without it, a
        // re-queued episode refetches the manifest and — when a previous run had
        // already cleaned its segments — the entire episode, only to skip assembly
        // at Step 7 below. The expected name is computed from the exact inputs
        // Step 7 uses, so both checks always agree.
        val existingOutput = File(
            segmentStore.getDirectory(),
            FileNameGenerator.episodeFileName(
                FileNameGenerator.formatEpisodePrefix(episodeNumber),
                episodeTitle,
            ),
        )
        if (existingOutput.exists() && existingOutput.length() > 0L) {
            AppLog.d(TAG, "Output already exists (${existingOutput.name}) — download skipped")
            val alreadyDone = segmentStore.doneIndices().size
            return DownloadResult(
                success = true,
                totalSegments = alreadyDone,
                completedSegments = alreadyDone,
                failedSegments = 0,
                assembled = true,
                outputFile = existingOutput,
                reusedExistingOutput = true,
            )
        }

        val maxAttempts = autoRetry + 1 // Python: auto_retry + 1 (default 3)

        for (attempt in 0 until maxAttempts) {
            try {
                val result = downloadOnce(streamInfo, episodeNumber, episodeTitle, onProgress, onManifestResolved, onAssemblyStart)
                if (result.success) return result

                // permanence reads the TYPED class
                // the failure was constructed with — the string matching that
                // used to live here (`error.contains("HTTP 403")` …) is deleted.
                // EXPIRED / NO_CONTENT / ASSEMBLY / DISK_FULL end the episode
                // budget right away; TRANSPORT and IN_RUN_RETRYABLE are retried
                // in-run while attempts remain. Identical outcomes at every construction
                // site, and an arbitrary exception message can no longer be
                // misread as permanent by containing "HTTP 403".
                val error = result.error ?: ""
                val isPermanent = when (result.errorClass) {
                    DownloadErrorClass.EXPIRED,
                    DownloadErrorClass.NO_CONTENT,
                    DownloadErrorClass.ASSEMBLY,
                    // Final-audit H2: the disk does not empty itself while we
                    // wait — retrying would only re-download into the same
                    // full volume and burn the episode budget.
                    DownloadErrorClass.DISK_FULL,
                    -> true
                    else -> false
                }
                if (isPermanent) {
                    AppLog.e(TAG, "Permanent error (${result.errorClass}), not retrying: $error")
                    return result
                }

                // Non-permanent failure — retry if attempts remain
                if (attempt < maxAttempts - 1) {
                    val delay = EPISODE_RETRY_BASE_DELAY_MS * (1L shl attempt)
                    AppLog.w(TAG, "Episode download failed (attempt ${attempt + 1}/$maxAttempts): $error — retrying in ${delay}ms")
                    kotlinx.coroutines.delay(delay)
                } else {
                    AppLog.e(TAG, "Episode download failed after $maxAttempts attempts: $error")
                    return result
                }
            } catch (e: CancellationException) {
                AppLog.d(TAG, "Download cancelled")
                return DownloadResult(
                    success = false,
                    wasCancelled = true,
                    error = "Download cancelled"
                )
            } catch (e: Exception) {
                // Unexpected exception — retry if attempts remain
                if (attempt < maxAttempts - 1) {
                    val delay = EPISODE_RETRY_BASE_DELAY_MS * (1L shl attempt)
                    AppLog.w(TAG, "Episode download exception (attempt ${attempt + 1}/$maxAttempts): ${e.message} — retrying in ${delay}ms")
                    kotlinx.coroutines.delay(delay)
                } else {
                    AppLog.e(TAG, "Episode download failed after $maxAttempts attempts: ${e.message}")
                    return DownloadResult(
                        success = false,
                        error = e.message,
                        // a local/unexpected exception escaping
                        // `downloadOnce` is never a connectivity problem —
                        // `OkHttpHlsClient` already converts every network
                        // exception to `NetworkError` before it can get here.
                        errorClass = DownloadErrorClass.IN_RUN_RETRYABLE,
                    )
                }
            }
        }

        // Should not reach here, but safety fallback
        return DownloadResult(
            success = false,
            error = "Download failed after all attempts",
            // never a transport failure — this line is only
            // reachable through a path that already returned its own class.
            errorClass = DownloadErrorClass.IN_RUN_RETRYABLE,
        )
    }

    /**
     * Single download attempt (no episode-level retry).
     *
     * Handles: M3U8 fetch → parse → key fetch → concurrent segment download → decrypt → write → assemble
     *
     * @param streamInfo Resolved stream information
     * @param episodeNumber Episode number for output filename
     * @param episodeTitle Episode title for output filename
     * @param onProgress Optional progress callback (completed, total, bytesDone)
     * @param onManifestResolved Optional manifest-resolution callback (§16.2)
     * @param onAssemblyStart Final-audit H4 — persisted-ASSEMBLING hook (see [download])
     * @return DownloadResult
     */
    private suspend fun downloadOnce(
        streamInfo: StreamInfo,
        episodeNumber: Number,
        episodeTitle: String,
        onProgress: ((Int, Int, Long) -> Unit)?,
        onManifestResolved: (suspend (Int) -> Unit)? = null,
        onAssemblyStart: (suspend () -> Unit)? = null
    ): DownloadResult {
        // Step 0: Orphan cleanup (Phase 8 — cleanup stale .tmp files from prior crashes)
        segmentStore.cleanupOrphans()

        // Step 1: Fetch M3U8 playlist
        val m3u8Result = fetchM3u8WithRetry(streamInfo.url, streamInfo)
        val m3u8Content = when (m3u8Result) {
            is FetchResult.Success -> m3u8Result.content
            is FetchResult.PermanentError -> {
                return DownloadResult(
                    success = false,
                    error = m3u8Result.message,
                    // §14: `fetchM3u8WithRetry` only returns PermanentError for 403/404.
                    errorClass = DownloadErrorClass.EXPIRED,
                )
            }
            is FetchResult.TransientError -> {
                return DownloadResult(
                    success = false,
                    error = m3u8Result.message,
                    // §14: non-403/404 status exhausted, or a network failure.
                    errorClass = DownloadErrorClass.TRANSPORT,
                )
            }
        }

        // Step 2: Parse playlist
        val playlist = try {
            HlsPlaylistParser.parse(m3u8Content, streamInfo.url)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to parse M3U8: ${e.message}")
            return DownloadResult(
                success = false,
                error = "Failed to parse M3U8: ${e.message}",
                // §14: local parse failure — retryable in-run, never transport.
                errorClass = DownloadErrorClass.IN_RUN_RETRYABLE,
            )
        }

        if (playlist.isMaster) {
            // Master playlist — fetch the last variant (Python parity: Constraint 6)
            val lastVariant = playlist.variants.lastOrNull()
                ?: return DownloadResult(
                    success = false,
                    error = "Master playlist has no variants",
                    // §14: nothing to download, not a network problem.
                    errorClass = DownloadErrorClass.NO_CONTENT,
                )

            // AQR-021: the variant URL is the same expiring CDN authorisation —
            // host + path only (AGENTS §10).
            AppLog.d(TAG, "Master playlist detected, fetching last variant: ${LogUrlSanitizer.url(lastVariant)}")
            // Pre-flight the variant so an expired/unreachable variant is classified
            // (EXPIRED vs TRANSPORT) BEFORE the recursive download below re-fetches it.
            // The content itself is not forwarded: downloadOnce re-resolves the variant
            // URL through the same retrying fetch path.
            when (val variantResult = fetchM3u8WithRetry(lastVariant, streamInfo)) {
                is FetchResult.Success -> Unit
                is FetchResult.PermanentError -> {
                    return DownloadResult(
                        success = false,
                        error = variantResult.message,
                        errorClass = DownloadErrorClass.EXPIRED,
                    )
                }
                is FetchResult.TransientError -> {
                    return DownloadResult(
                        success = false,
                        error = variantResult.message,
                        errorClass = DownloadErrorClass.TRANSPORT,
                    )
                }
            }

            // DL-001: forward onAssemblyStart — the master branch must fire the same
            // STATE_ASSEMBLING persistence hook the media path does, so a process death
            // during mux is recoverable via the reconciler's ASSEMBLING branch and the
            // Downloads screen can show the assembling state for master-playlist episodes.
            return downloadOnce(
                streamInfo.copy(url = lastVariant),
                episodeNumber, episodeTitle, onProgress, onManifestResolved, onAssemblyStart,
            )
        }

        if (playlist.segments.isEmpty()) {
            return DownloadResult(
                success = false,
                error = "No segments in playlist",
                // §14: nothing to download, not a network problem.
                errorClass = DownloadErrorClass.NO_CONTENT,
            )
        }

        // Step 2b: Manifest resolved — the segment total is now known.
        // one-time persistence callback at manifest resolution,
        // BEFORE segment processing. Not routed through ProgressTracker.flush()
        // (that path stays completedSegments-only with its throttle unchanged).
        // Best-effort: a persistence failure must never abort the download.
        if (onManifestResolved != null) {
            try {
                onManifestResolved.invoke(playlist.segments.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "onManifestResolved callback failed (best-effort): ${e.message}")
            }
        }

        // Step 3: Collect unique AES-128 keys across all segments
        val cookieMap = streamInfo.cookies.associate { it["name"]!! to it["value"]!! }
        val keyContext = HlsClient.RequestContext(
            userAgent = streamInfo.userAgent,
            referer = streamInfo.referer,
            cookies = cookieMap
        )

        val keyMap = mutableMapOf<String, ByteArray>()
        val uniqueKeyUrls = playlist.segments
            .mapNotNull { it.keyUrl }
            .distinct()

        for (keyUrl in uniqueKeyUrls) {
            val cached = keyCache.get(keyUrl)
            if (cached != null) {
                keyMap[keyUrl] = cached
                continue
            }

            val keyResult = fetchKeyWithRetry(keyUrl, keyContext)
            when (keyResult) {
                is FetchBytesResult.Success -> {
                    keyCache.set(keyUrl, keyResult.bytes)
                    keyMap[keyUrl] = keyResult.bytes
                }
                is FetchBytesResult.PermanentError -> {
                    AppLog.e(TAG, "AES key permanent error: ${keyResult.message}")
                    return DownloadResult(
                        success = false,
                        error = keyResult.message,
                        errorClass = DownloadErrorClass.EXPIRED,
                    )
                }
                is FetchBytesResult.TransientError -> {
                    AppLog.e(TAG, "AES key transient error: ${keyResult.message}")
                    return DownloadResult(
                        success = false,
                        error = keyResult.message,
                        errorClass = DownloadErrorClass.TRANSPORT,
                    )
                }
            }
        }

        // Step 4: Check completed segments (resume support)
        val completedIndices = segmentStore.doneIndices()
        val pendingSegments = playlist.segments.filter { it.index !in completedIndices }

        // Gap 2 (SPEC §25): byte accounting. The resume base is ONE directory
        // walk (`.ts` files only — `.tmp` partials and the muxed output never
        // count), taken BEFORE this attempt writes anything, so segments already
        // on disk contribute exactly once and nothing below can double-count:
        // each success adds its decrypted size only after writeSeg() returned,
        // and a failed/retried segment adds nothing until it really lands.
        val downloadedBytes = java.util.concurrent.atomic.AtomicLong(
            segmentStore.completedSegmentBytes()
        )

        AppLog.d(TAG, "Playlist: ${playlist.segments.size} segments, ${completedIndices.size} completed, ${pendingSegments.size} pending")

        // Final-audit H2 — SPEC §13 "Handle: insufficient storage": a run that
        // ALREADY has segments on disk can estimate what is left, so refuse here
        // instead of spending bandwidth on segments the volume cannot hold. A
        // fresh run has no sample (estimate 0 → not gated) and an unknown
        // free-space figure never refuses; in both cases every write below is
        // still the backstop, because [StorageSpace.isNoSpaceLeft] turns a real
        // ENOSPC into the typed [DownloadErrorClass.DISK_FULL].
        val requiredBytes = StorageSpace.estimatedRemainingBytes(
            completedCount = completedIndices.size,
            completedBytes = downloadedBytes.get(),
            pendingCount = pendingSegments.size,
        )
        val freeBytes = availableBytes(segmentStore.getDirectory())
        if (StorageSpace.isInsufficient(freeBytes, requiredBytes)) {
            val message = "Not enough storage space: " +
                StorageSpace.describe(freeBytes, requiredBytes) +
                " for ${pendingSegments.size} remaining segment(s)"
            AppLog.e(TAG, "Storage pre-flight refused episode $episodeNumber: $message")
            return DownloadResult(
                success = false,
                totalSegments = playlist.segments.size,
                completedSegments = completedIndices.size,
                failedSegments = 0,
                error = message,
                errorClass = DownloadErrorClass.DISK_FULL,
            )
        }

        // Step 5: Download pending segments concurrently
        val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
        // DL-009: a seeded counter instead of calling doneIndices() (a full
        // directory listing) after EVERY segment write — that made the segment
        // loop O(N²) in directory entries. It is exactly equivalent: every index
        // here is the playlist's media-sequence number (unique), pendingSegments
        // already excludes what is on disk, and each pending index is written at
        // most once in this run, so seed + one increment per successful write
        // equals doneIndices().size at every point it was read.
        val completedCount = java.util.concurrent.atomic.AtomicInteger(completedIndices.size)
        val errors = mutableListOf<String>()
        // ONE class per recorded failure, appended inside the
        // same lock as its message, so the aggregation below can apply its
        // priority rule by ORIGIN instead of re-reading message text.
        val errorClasses = mutableListOf<DownloadErrorClass>()

        if (pendingSegments.isNotEmpty()) {
            val semaphore = Semaphore(hlsWorkers)

            // DL-004: supervisorScope instead of a DETACHED CoroutineScope. The
            // segment jobs are now children of this suspend call, so (a) a cancelled
            // download cancels them automatically and — the part the detached scope
            // got wrong — (b) this block does not return until every child has
            // actually stopped. The old `finally { scope.cancel() }` returned
            // immediately (cancel() is a request, not a join), letting downloadOnce
            // unwind and any episode-level retry start a SECOND attempt while
            // in-flight blocking OkHttp/file work from this one was still writing
            // the same segment files (AGENTS §12/§13). Supervisor semantics are
            // unchanged: one failed segment never cancels its siblings.
            supervisorScope {
                val jobs = pendingSegments.map { segment ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                ensureActive()

                                // Fetch segment data
                                val segResult = fetchSegmentWithRetry(
                                    segment.url, keyContext, segment.index
                                )

                                val data = when (segResult) {
                                    is FetchBytesResult.Success -> segResult.bytes
                                    is FetchBytesResult.PermanentError -> {
                                        failedCount.incrementAndGet()
                                        synchronized(errors) {
                                            errors.add("Segment ${segment.index}: ${segResult.message}")
                                            errorClasses.add(DownloadErrorClass.EXPIRED)
                                        }
                                        AppLog.e(TAG, "Segment ${segment.index} permanent error: ${segResult.message}")
                                        return@launch
                                    }
                                    is FetchBytesResult.TransientError -> {
                                        failedCount.incrementAndGet()
                                        synchronized(errors) {
                                            errors.add("Segment ${segment.index}: ${segResult.message}")
                                            errorClasses.add(DownloadErrorClass.TRANSPORT)
                                        }
                                        AppLog.e(TAG, "Segment ${segment.index} transient error: ${segResult.message}")
                                        return@launch
                                    }
                                }

                                // Decrypt if encrypted
                                val decryptedData = if (segment.keyUrl != null && segment.iv != null) {
                                    val key = keyMap[segment.keyUrl]
                                    if (key == null) {
                                        failedCount.incrementAndGet()
                                        synchronized(errors) {
                                            errors.add("Segment ${segment.index} missing key")
                                            errorClasses.add(DownloadErrorClass.IN_RUN_RETRYABLE)
                                        }
                                        AppLog.e(TAG, "Segment ${segment.index} missing key for decryption")
                                        return@launch
                                    }
                                    val decrypted = Aes128Decryptor.decrypt(data, key, segment.iv)
                                    if (decrypted == null) {
                                        failedCount.incrementAndGet()
                                        synchronized(errors) {
                                            errors.add("Segment ${segment.index} decryption failed")
                                            errorClasses.add(DownloadErrorClass.IN_RUN_RETRYABLE)
                                        }
                                        AppLog.e(TAG, "Segment ${segment.index} decryption failed")
                                        return@launch
                                    }
                                    decrypted
                                } else {
                                    data
                                }

                                // Write atomically (.tmp → .ts)
                                segmentStore.writeSeg(segment.index, decryptedData)

                                // Gap 2: the decrypted bytes are counted ONLY now
                                // that the atomic write succeeded — a segment that
                                // failed, was retried, or is still sitting in a
                                // `.tmp` has contributed nothing.
                                val bytesDone = downloadedBytes
                                    .addAndGet(decryptedData.size.toLong())

                                // Report progress (DL-009: the seeded counter, not
                                // a fresh directory listing — see its declaration).
                                // Incremented strictly AFTER writeSeg() returned, so
                                // it counts only segments that really exist on disk.
                                val completed = completedCount.incrementAndGet()
                                onProgress?.invoke(completed, playlist.segments.size, bytesDone)

                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failedCount.incrementAndGet()
                                // Final-audit H2: a write (or a wrapped write) that
                                // ran out of space is [DownloadErrorClass.DISK_FULL],
                                // decided ONCE here at the construction site — the
                                // message is only rendered for the user, never read
                                // back by any other component (Phase 14 §14).
                                val diskFull = StorageSpace.isNoSpaceLeft(e)
                                synchronized(errors) {
                                    errors.add(
                                        if (diskFull) {
                                            "Segment ${segment.index}: not enough storage space"
                                        } else {
                                            "Segment download exception: ${e.message}"
                                        }
                                    )
                                    errorClasses.add(
                                        if (diskFull) DownloadErrorClass.DISK_FULL
                                        else DownloadErrorClass.IN_RUN_RETRYABLE
                                    )
                                }
                                if (diskFull) {
                                    AppLog.e(TAG, "Segment ${segment.index} hit an out-of-space condition")
                                } else {
                                    AppLog.e(TAG, "Segment download exception: ${e.message}")
                                }
                            }
                        }
                    }
                }

                // Wait for all jobs to complete
                jobs.forEach { it.join() }
            }
        }

        // Step 6: Check results
        val success = failedCount.get() == 0
        val finalCompleted = segmentStore.doneIndices().size

        AppLog.d(TAG, "Download complete: success=$success, total=${playlist.segments.size}, completed=$finalCompleted, failed=${failedCount.get()}")

        if (!success) {
            // Phase 14 (§14) aggregation priority, by ORIGIN not message text:
            //   1. any segment that ran out of space (final-audit H2) → DISK_FULL,
            //      because storage is a hard local blocker: fixing a transport or
            //      HTTP problem would still leave the volume full;
            //   2. any failed segment with a permanent HTTP failure → EXPIRED;
            //   3. else every failure transient → TRANSPORT;
            //   4. else a mixed run contains a content/local problem →
            //      IN_RUN_RETRYABLE.
            // Read after `jobs.forEach { join() }`, so no lock is required:
            // every append happens inside `synchronized(errors)` before the
            // job it belongs to completes.
            val aggregatedClass = when {
                errorClasses.any { it == DownloadErrorClass.DISK_FULL } ->
                    DownloadErrorClass.DISK_FULL
                errorClasses.any { it == DownloadErrorClass.EXPIRED } ->
                    DownloadErrorClass.EXPIRED
                errorClasses.isNotEmpty() && errorClasses.all { it == DownloadErrorClass.TRANSPORT } ->
                    DownloadErrorClass.TRANSPORT
                else ->
                    DownloadErrorClass.IN_RUN_RETRYABLE
            }
            return DownloadResult(
                success = false,
                totalSegments = playlist.segments.size,
                completedSegments = finalCompleted,
                failedSegments = failedCount.get(),
                error = if (errors.isNotEmpty()) errors.joinToString("; ") else null,
                errorClass = aggregatedClass,
            )
        }

        // Step 7: Assemble MP4 (Phase 9)
        if (assembler != null) {
            AppLog.d(TAG, "Starting MP4 assembly for ${playlist.segments.size} segments")

            // Generate output filename using FileNameGenerator (Python: Ep {prefix} - {title}.mp4)
            val prefix = FileNameGenerator.formatEpisodePrefix(episodeNumber)
            val outputFileName = FileNameGenerator.episodeFileName(prefix, episodeTitle)
            // Output alongside segments directory (not parentFile)
            val outputFile = File(segmentStore.getDirectory(), outputFileName)

            // Check if output already exists (Python: skip if out.exists() and st_size > 0)
            if (outputFile.exists() && outputFile.length() > 0) {
                AppLog.d(TAG, "Output file already exists and is non-empty, skipping assembly")
                return DownloadResult(
                    success = true,
                    totalSegments = playlist.segments.size,
                    completedSegments = finalCompleted,
                    failedSegments = 0,
                    assembled = true,
                    outputFile = outputFile
                )
            }

            val assemblyConfig = EpisodeAssembler.AssemblyConfig(
                segmentDir = segmentStore.getDirectory(),
                outputFile = outputFile,
                segmentCount = playlist.segments.size,
                keepTemp = keepTemp,
                // Assembly boundary translation (LIMITATIONS §26): files are keyed by
                // HlsSegment.index (EXT-X-MEDIA-SEQUENCE), so hand the assembler the
                // playlist's own indices in playlist order instead of range(0, n).
                // Persisted segment names (Phase 8 identity) stay untouched.
                segmentIndices = playlist.segments.map { it.index }
            )

            // Final-audit H4 — SPEC §25 "remuxing": the row becomes ASSEMBLING
            // HERE, the one moment the state is true, so the dashboard shows a
            // real muxing state and startup reconciliation gains the ASSEMBLING
            // branch that was previously dead (no production writer existed).
            // The hook is best-effort: a state row that cannot be written must
            // never stop the mux (see [download]'s @param contract).
            try {
                onAssemblyStart?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "onAssemblyStart hook failed (best-effort): ${e.message}")
            }

            val assemblyResult = assembler.assemble(assemblyConfig)

            if (assemblyResult.success) {
                AppLog.d(TAG, "Assembly succeeded: ${assemblyResult.outputFile?.absolutePath}")

                // Cleanup segments if not keeping temp
                if (!keepTemp) {
                    segmentStore.cleanup()
                    AppLog.d(TAG, "Cleaned up segment directory")
                }

                return DownloadResult(
                    success = true,
                    totalSegments = playlist.segments.size,
                    completedSegments = finalCompleted,
                    failedSegments = 0,
                    assembled = true,
                    outputFile = assemblyResult.outputFile
                )
            } else {
                AppLog.e(TAG, "Assembly failed: ${assemblyResult.error}")

                // Assembly failure is permanent — FFmpeg concat/pipeline errors are deterministic.
                // Final-audit H2: muxing needs a second full copy of the episode
                // (the segments are still on disk), so a full volume is a real and
                // likely mux failure — report it as DISK_FULL, not as a generic
                // muxing error. FFmpeg's reason is a plain string (no exception
                // carries the errno), so this construction site is the one place
                // that string is read.
                val outOfSpace = StorageSpace.isNoSpaceLeft(assemblyResult.error)
                return DownloadResult(
                    success = false,
                    totalSegments = playlist.segments.size,
                    completedSegments = finalCompleted,
                    failedSegments = 0,
                    error = if (outOfSpace) {
                        "Not enough storage space to write the MP4: ${assemblyResult.error}"
                    } else {
                        "Assembly failed: ${assemblyResult.error}"
                    },
                    assemblyError = assemblyResult.error,
                    // §14: deterministic FFmpeg failure — permanent, and never
                    // mistaken for a connectivity problem.
                    errorClass = if (outOfSpace) DownloadErrorClass.DISK_FULL else DownloadErrorClass.ASSEMBLY,
                )
            }
        }

        // No assembler provided — just return download success
        return DownloadResult(
            success = true,
            totalSegments = playlist.segments.size,
            completedSegments = finalCompleted,
            failedSegments = 0
        )
    }

    /**
     * Fetch M3U8 content with retry.
     *
     * Distinguishes permanent (403/404) from transient (5xx, network) errors.
     * Permanent errors are NOT retried.
     *
     * Python retry behavior (http.py lines 89-108):
     * - RETRY_ATTEMPTS = 5
     * - RETRY_BASE_DELAY = 0.5 seconds
     * - Exponential backoff: 0.5 * (2 ** attempt)
     * - Retries on: TimeoutError, ClientError, OSError
     */
    private suspend fun fetchM3u8WithRetry(
        url: String,
        streamInfo: StreamInfo
    ): FetchResult {
        val cookieMap = streamInfo.cookies.associate { it["name"]!! to it["value"]!! }
        val context = HlsClient.RequestContext(
            userAgent = streamInfo.userAgent,
            referer = streamInfo.referer,
            cookies = cookieMap
        )

        for (attempt in 0 until RETRY_ATTEMPTS) {
            when (val result = hlsClient.getText(url, context, timeoutMs = 30_000L)) {
                is HlsTextResult.Success -> return FetchResult.Success(result.content)
                is HlsTextResult.HttpError -> {
                    // 403/404 are permanent — stream expired
                    if (result.code == 403 || result.code == 404) {
                        return FetchResult.PermanentError("M3U8 HTTP ${result.code} — stream expired")
                    }
                    // 5xx — transient, retry
                    AppLog.w(TAG, "M3U8 HTTP ${result.code} (attempt ${attempt + 1}/$RETRY_ATTEMPTS)")
                }
                is HlsTextResult.NetworkError -> {
                    // Transient — retry
                    AppLog.w(TAG, "M3U8 network error: ${result.message} (attempt ${attempt + 1}/$RETRY_ATTEMPTS)")
                }
            }

            if (attempt < RETRY_ATTEMPTS - 1) {
                val delay = RETRY_BASE_DELAY_MS * (1L shl attempt)
                kotlinx.coroutines.delay(delay)
            }
        }
        return FetchResult.TransientError("M3U8 fetch failed after $RETRY_ATTEMPTS attempts")
    }

    /**
     * Fetch AES-128 key with retry.
     *
     * Distinguishes permanent from transient errors.
     */
    private suspend fun fetchKeyWithRetry(
        keyUrl: String,
        context: HlsClient.RequestContext
    ): FetchBytesResult {
        for (attempt in 0 until RETRY_ATTEMPTS) {
            when (val result = hlsClient.getBytes(keyUrl, context, timeoutMs = 10_000L)) {
                is HlsBytesResult.Success -> {
                    if (result.bytes.size == 16) {
                        return FetchBytesResult.Success(result.bytes)
                    }
                    AppLog.w(TAG, "AES key invalid size: ${result.bytes.size} (attempt ${attempt + 1}/$RETRY_ATTEMPTS)")
                }
                is HlsBytesResult.HttpError -> {
                    if (result.code == 403 || result.code == 404) {
                        return FetchBytesResult.PermanentError("AES key HTTP ${result.code} — key unavailable")
                    }
                    AppLog.w(TAG, "AES key HTTP ${result.code} (attempt ${attempt + 1}/$RETRY_ATTEMPTS)")
                }
                is HlsBytesResult.NetworkError -> {
                    AppLog.w(TAG, "AES key network error: ${result.message} (attempt ${attempt + 1}/$RETRY_ATTEMPTS)")
                }
            }

            if (attempt < RETRY_ATTEMPTS - 1) {
                val delay = RETRY_BASE_DELAY_MS * (1L shl attempt)
                kotlinx.coroutines.delay(delay)
            }
        }
        return FetchBytesResult.TransientError("AES key fetch failed after $RETRY_ATTEMPTS attempts")
    }

    /**
     * Fetch a single segment with retry.
     *
     * Distinguishes permanent from transient errors.
     */
    private suspend fun fetchSegmentWithRetry(
        url: String,
        context: HlsClient.RequestContext,
        segIndex: Int
    ): FetchBytesResult {
        for (attempt in 0 until RETRY_ATTEMPTS) {
            when (val result = hlsClient.getBytes(url, context, timeoutMs = 30_000L)) {
                is HlsBytesResult.Success -> return FetchBytesResult.Success(result.bytes)
                is HlsBytesResult.HttpError -> {
                    if (result.code == 403 || result.code == 404) {
                        return FetchBytesResult.PermanentError("Segment $segIndex HTTP ${result.code} — segment expired")
                    }
                    AppLog.w(TAG, "Segment $segIndex HTTP ${result.code} (attempt ${attempt + 1}/$RETRY_ATTEMPTS)")
                }
                is HlsBytesResult.NetworkError -> {
                    AppLog.w(TAG, "Segment $segIndex network error: ${result.message} (attempt ${attempt + 1}/$RETRY_ATTEMPTS)")
                }
            }

            if (attempt < RETRY_ATTEMPTS - 1) {
                val delay = RETRY_BASE_DELAY_MS * (1L shl attempt)
                kotlinx.coroutines.delay(delay)
            }
        }
        return FetchBytesResult.TransientError("Segment $segIndex fetch failed after $RETRY_ATTEMPTS attempts")
    }
}
