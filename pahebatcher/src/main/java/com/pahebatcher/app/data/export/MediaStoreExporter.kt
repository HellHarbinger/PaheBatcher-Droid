package com.pahebatcher.app.data.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.BuildConfig
import com.pahebatcher.app.data.hls.FileNameGenerator
import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

/**
 * Outcome of one idempotent export attempt (PHASE11_ARCHITECTURE_V2 §30.1).
 */
sealed interface ExportOutcome {

    /** A fresh verified copy was created, persisted to Room, made visible, local removed. */
    data class Exported(val uri: String) : ExportOutcome

    /**
     * A previously created (or resumed) row **size-verified** against the local
     * source — the reuse check compares sizes only, never bytes; Room now
     * records it for this episode.
     */
    data class AlreadyExported(val uri: String) : ExportOutcome

    /** Export did not complete. The local MP4 is always kept (§30.4); retried next startup. */
    data class Failed(val reason: String) : ExportOutcome
}

/**
 * Idempotent MediaStore export of a completed episode MP4 (PHASE11_ARCHITECTURE_V2
 * §27/§30 — Decision 54, F-06 corrected ordering):
 *
 *   deterministic lookup (§30.2) → insert IS_PENDING=1 → stream-copy (bounded 64 KiB
 *   buffer, never whole-file in memory — AGENTS §12) → byte-count verify →
 *   **persist `outputUri` in Room (X4) → clear IS_PENDING (X5)** → delete local (X6).
 *
 * Normative guarantees:
 *  - At every instant either the MediaStore row is invisible (`IS_PENDING=1`) or Room
 *    already knows its URI — no visible-but-unknown file is possible (§30.1 step 8).
 *  - The ONLY deletion of the local MP4 is step X6, strictly after byte-verified
 *    persistence (§27 design decision 7, §30.4: "never delete an unverified local copy").
 *  - Any intermediate failure deletes the still-pending row and keeps the local file —
 *    the episode stays valid under the §29 invariant and the export is retried by
 *    startup reconciliation.
 *  - Deterministic reuse (§30.2): before any insert, existing rows in the
 *    episode's own `Movies/<sanitized anime title>/` directory (Gate A)
 *    owned by our package whose display name equals THIS episode's
 *    audio-specific export name (`ExportFileNames.exportedName`, or its
 *    MediaStore-uniquified ` (n)` form) are size-verified and reused instead of
 *    duplicated — covering kill-during-copy (invalid pending → replace) and
 *    kill-after-verification (valid pending → resume at verification). The
 *    replacement (delete) branch applies ONLY to an exact audio-specific name,
 *    so a row of a different audio variant — which carries a different name —
 *    can never be reached (Slice A). Size-verified rows in the legacy flat
 *    `Movies/PaheBatcher/` directory and legacy unsuffixed rows anywhere are
 *    recognized too — adopted when size-verified and unreferenced, NEVER
 *    deleted or migrated. Verification here is by SIZE (never a byte-level
 *    comparison).
 *
 * `ContentResolver` and the DAO arrive via constructor injection (§30.1) — no static
 * or scattered context lookups. Cross-store atomicity with Room is NOT possible;
 * this design is best-effort idempotent, recorded as a genuine limitation
 * (LIMITATIONS, §37-8).
 *
 * Concurrency (§30.4, Phase 13): ALL export and sweep operations across ALL
 * destinations (MediaStore AND SAF) and ALL instances are serialized by the one
 * process-wide [ExportOperationLock.mutex] — the worker-success path and startup
 * reconciliation can never interleave two exports for the same episode, whichever
 * destination the router picks (bounded, sequential; AGENTS §12).
 */
class MediaStoreExporter(
    private val contentResolver: ContentResolver,
    private val episodeDao: EpisodeDownloadDao,
    private val sessionDao: DownloadSessionDao,
) : EpisodeExporter {

    companion object {
        private const val TAG = "MediaStoreExporter"

        private const val MIME_TYPE = "video/mp4"

        /** Bounded copy buffer — never load the episode into memory (AGENTS §12). */
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * Process-wide serialization of export/sweep critical sections — the
         * shared lock of [ExportOperationLock] (Phase 13: MediaStore and SAF
         * exports serialize against each other, not just within this class).
         */
        private val operationLock = ExportOperationLock.mutex

        /** One row of interest inside our export directory. */
        internal data class StoreRow(
            val uri: Uri,
            val displayName: String,
            val size: Long,
            val isPending: Boolean,
            /** MediaStore DATE_ADDED, seconds since epoch; <= 0 when unknown. */
            val dateAddedSec: Long,
        )
    }

    /**
     * `Replace file` (DECISIONS 152) — the paired retirement for
     * [PriorOutputRetirement]. Shares this class's resolver and episode table
     * so both halves of a replacement agree on what a reference means.
     */
    private val priorOutputRetirement = PriorOutputRetirement(contentResolver, episodeDao)

    // ------------------------------------------------------------------
    // §30.1 normative export sequence
    // ------------------------------------------------------------------

    /**
     * Export [episode]'s local MP4 into `Movies/<sanitized anime title>/`
     * idempotently (Gate A: the directory derives from the parent
     * [DownloadSessionEntity]'s `title`; the legacy flat `Movies/PaheBatcher/`
     * stays valid for existing files and stale-pending recovery).
     *
     * Precondition: the episode has a valid local output (`outputPath` exists, size > 0)
     * — OR its persisted `outputUri` already resolves (then the call only finishes/
     * verifies bookkeeping). A missing local source without a resolving URI is an
     * honest [ExportOutcome.Failed], never a fabricated success (AGENTS §5).
     */
    override suspend fun export(episode: EpisodeDownloadEntity): ExportOutcome =
        operationLock.withLock {
            withContext(Dispatchers.IO) {
                val outcome = exportLocked(episode)
                // `Replace file` (DECISIONS 152): the retirement runs HERE, inside
                // the same lock and only AFTER exportLocked resolved — so the old
                // export is removed exactly when the new output is already
                // committed and verified, never before. A failed export never
                // reaches this as a success, so a replacement that did not work
                // can never destroy the file it was replacing.
                priorOutputRetirement.retire(episode, outcome)
                outcome
            }
        }

    /**
     * Delete stale `IS_PENDING=1` rows from a previous run (§28:
     * "sweeps its own stale pending rows … older than the current run").
     *
     * Scope (Gate A): every directory WE own under `Movies/` — the legacy flat
     * `Movies/PaheBatcher/` AND every per-anime directory — selected by the
     * unchanged `OWNER_PACKAGE_NAME` filter, so another application's files can
     * never be touched.
     *
     * @param olderThanEpochMs rows with `DATE_ADDED` strictly older than this are
     *   eligible. Production passes the reconciliation (process-start) instant, so
     *   pending rows created by the CURRENT process are never swept mid-copy while
     *   rows orphaned by a crashed previous process are. Rows whose URI is already
     *   recorded in Room are never swept (defensive — reuse comes first, §30.3).
     * @return number of rows deleted.
     */
    override suspend fun sweepStalePending(olderThanEpochMs: Long): Int =
        operationLock.withLock {
            withContext(Dispatchers.IO) {
                val referenced = episodeDao.getAllOutputUris().toSet()
                var swept = 0
                for (row in queryRowsInOurMoviesTree()) {
                    if (!row.isPending) continue
                    if (row.uri.toString() in referenced) continue
                    if (row.dateAddedSec <= 0) continue // unknown age → leave it alone
                    if (row.dateAddedSec * 1000L >= olderThanEpochMs) continue
                    try {
                        contentResolver.delete(row.uri, null, null)
                        swept++
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Failed to sweep stale pending row: ${e.message}")
                    }
                }
                swept
            }
        }

    // ------------------------------------------------------------------
    // Implementation (always inside operationLock + Dispatchers.IO)
    // ------------------------------------------------------------------

    /**
     * §3.1 (DECISIONS 157): the MediaStore destination probe — a live, visible,
     * app-owned export already present under this episode's per-anime directory
     * with its exact audio-suffixed name, returned as the URI consumers need.
     *
     * Deliberately NARROW so it can only ever match the episode's OWN file:
     * the leaf name carries the audio discriminator, the directory is this
     * episode's, the row must be non-pending (a visible file, not crash residue),
     * and non-empty. [queryRowsInDir] already filters to [BuildConfig]'s owner, so
     * another app's same-name file is invisible here and can never match. A
     * MediaStore-uniquified `… (n)` name still matches ([nameMatches], §30.2).
     *
     * Self-dispatches to [Dispatchers.IO]: this is blocking Binder IPC and the
     * method is a public interface seam — every sibling ([export],
     * [sweepStalePending]) guarantees its own IO context, so this probe does
     * too (idempotent when the caller is already on IO).
     */
    override suspend fun destinationOutput(
        episodeNumber: Float,
        title: String,
        audio: String,
        animeTitle: String?,
        sessionId: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val prefix = FileNameGenerator.formatEpisodePrefix(episodeNumber)
        val expectedName = ExportFileNames.exportedName(prefix, title, audio)
        // The directory chain mirrors `exportLocked` EXACTLY (session-row title
        // first), with the caller's `animeTitle` as the pre-create fallback a
        // row-absent submission supplies — so probe and export can never probe/
        // write different directories, and callers without a SessionDao (the
        // startup reconciler) resolve the same directory as everyone else.
        val relativePath = OutputPathNames.mediaStoreRelativePath(
            sessionDao.getById(sessionId)?.title ?: animeTitle,
            sessionId,
        )
        queryRowsInDir(relativePath)
            .firstOrNull { row ->
                nameMatches(row.displayName, expectedName) && !row.isPending && row.size > 0L
            }?.uri
    }

    private suspend fun exportLocked(episode: EpisodeDownloadEntity): ExportOutcome {
        // --- Local source validity (never export from a missing file) ---
        val localPath = episode.outputPath
        val local = localPath?.takeIf { it.isNotBlank() }?.let(::File)
        val localValid = local != null && local.exists() && local.length() > 0L

        // --- Persisted-URI fast path (§30.3 rows 1 + 4: prefer it; finish X5/X6) ---
        //
        // `Replace file` (DECISIONS 152): during a replacement `outputUri` is the
        // export being REPLACED, so this fast path must not be taken for it. Both
        // of its exits are destructive to the replacement: `AlreadyExported` +
        // `deleteLocal` would discard the freshly downloaded output because the
        // sizes happen to match, and a size-mismatched fall-through would reach
        // the exact-name branch and delete the old row BEFORE the new copy has
        // been verified. Skipping it sends a replacement down the ordinary
        // stage → verify → persist → retire sequence instead.
        val persistedUri = episode.outputUri
        val persistedIsPrior = persistedUri != null && persistedUri == episode.replacePriorUri
        if (!persistedUri.isNullOrEmpty() && !persistedIsPrior) {
            val row = queryByUri(persistedUri)
            if (row != null && row.size > 0) {
                if (!localValid) {
                    // Fully exported before: URI resolves, local already cleaned (X6 ran).
                    if (row.isPending) clearPending(Uri.parse(persistedUri))
                    return ExportOutcome.AlreadyExported(persistedUri)
                }
                if (row.size == local.length()) {
                    // Size-verified duplicate (sizes only): finish any incomplete bookkeeping.
                    if (row.isPending) clearPending(Uri.parse(persistedUri)) // kill-after-X4
                    deleteLocal(local, episode.episodeId) // kill-after-X5
                    return ExportOutcome.AlreadyExported(persistedUri)
                }
                // Size mismatch (a stale reference superseded by a re-download —
                // note `updateCompleted` sets state+outputPath only and does NOT
                // clear outputUri, so this branch is the one that retires the old
                // URI — 11.5E-6 verification): fall through and replace it with a
                // verified fresh copy below.
            }
            // URI does not resolve (user deleted): fall through to a fresh export,
            // which overwrites the stale reference on success (§29 retry affordance).
        }

        if (!localValid) {
            return ExportOutcome.Failed("local source missing or empty")
        }

        // --- Slice A: the EXPORT leaf name carries the audio discriminator, so
        // two audio variants of one episode can never share a destination name.
        // Pure function of the row's own metadata → export order cannot affect
        // it. `legacyName` is the pre-Slice-A unsuffixed name every existing
        // file was created with; it is only ever REUSED, never deleted. ---
        val prefix = FileNameGenerator.formatEpisodePrefix(episode.episodeNumber)
        val expectedName = ExportFileNames.exportedName(prefix, episode.title, episode.audio)
        val legacyName = ExportFileNames.legacyName(prefix, episode.title)

        // --- Gate A: destination = Movies/<sanitized anime title>/ (Python
        // main.py:288-289 parity); title read from the parent session row. ---
        val relativePath = OutputPathNames.mediaStoreRelativePath(
            sessionDao.getById(episode.sessionId)?.title,
            episode.sessionId,
        )

        val animeRows = queryRowsInDir(relativePath)

        // --- §30.2 deterministic lookup BEFORE any insert (audio-specific name) ---
        val candidates = animeRows
            .filter { nameMatches(it.displayName, expectedName) }
            .sortedBy { if (it.displayName == expectedName) 0 else 1 } // exact name first
        for (candidate in candidates) {
            // Cross-reference guard (Slice A): a file another Room row points at
            // is never adopted and never deleted — replacing or sharing it would
            // damage that other episode's output.
            val referenced = isReserved(candidate.uri.toString(), episode)
            if (!referenced && candidate.size > 0 && candidate.size == local.length()) {
                // Reuse: size-verified against the local source.
                episodeDao.updateOutputUri(episode.episodeId, candidate.uri.toString())
                if (candidate.isPending) clearPending(candidate.uri) // resume after kill-after-X4
                deleteLocal(local, episode.episodeId)
                return ExportOutcome.AlreadyExported(candidate.uri.toString())
            }
            if (referenced) continue
            when {
                // EXACT audio-specific name + size mismatch = this episode's own
                // crash residue (kill-during-copy partial) or a superseded copy
                // of the SAME audio — the §36-7 one-row replacement, narrowed by
                // Slice A to the exact name so another audio variant's file (a
                // different name) can never be reached by this branch.
                candidate.displayName == expectedName -> deleteRow(candidate.uri)
                // Invisible ` (n)` crash residue: removed here rather than
                // waiting for the next startup sweep. A VISIBLE ` (n)` row is a
                // file a user can see — left untouched (92.5 / SPEC §13).
                candidate.isPending -> deleteRow(candidate.uri)
            }
        }

        // --- Slice A legacy alias (pre-Slice-A unsuffixed exports) ---
        // Every legacy candidate is size-verified AND must be unreferenced by
        // any other row before it is adopted. Legacy files are never deleted,
        // renamed or migrated. ---
        val legacyFlat = queryRowsInDir(OutputPathNames.LEGACY_RELATIVE_PATH)
        if (legacyName == expectedName) {
            // Degenerate blank-audio row: the export name IS the legacy name, so
            // the pre-Gate-A flat home below is the whole alias surface.
            for (legacy in legacyFlat.filter { nameMatches(it.displayName, expectedName) }) {
                adoptLegacy(legacy, local, episode)?.let { return it }
            }
        } else {
            // Legacy-shaped rows in the anime directory: a visible file is an
            // export a user can see (leave it), while a PENDING row is invisible
            // app-owned crash residue of a pre-Slice-A copy attempt and is
            // removed here — the same §28 rationale as above.
            for (legacy in animeRows.filter { nameMatches(it.displayName, legacyName) }) {
                adoptLegacy(legacy, local, episode)?.let { return it }
                if (legacy.isPending && !isReserved(legacy.uri.toString(), episode)) {
                    deleteRow(legacy.uri)
                }
            }
            // Pre-Gate-A flat home (Movies/PaheBatcher/): reused when size-verified
            // and unreferenced, otherwise left for its owner — never deleted,
            // renamed or migrated (stale PENDING legacy rows are covered by
            // sweepStalePending above).
            for (legacy in legacyFlat.filter {
                nameMatches(it.displayName, legacyName) || nameMatches(it.displayName, expectedName)
            }) {
                adoptLegacy(legacy, local, episode)?.let { return it }
            }
        }

        // --- Fresh insert: IS_PENDING = 1 (invisible for the whole write window) ---
        val insertUri = insertPending(expectedName, relativePath)
            ?: return ExportOutcome.Failed("MediaStore insert failed")

        return try {
            val expected = local.length()
            val copied = copyFile(local, insertUri)
            if (copied != expected) {
                throw IOException("copied $copied bytes != local $expected bytes")
            }
            val stored = querySize(insertUri)
            if (stored != expected) {
                throw IOException("MediaStore reports $stored bytes != local $expected bytes")
            }
            // X4 → X5 → X6 — the corrected F-06 order. The URI is persisted BEFORE
            // the row becomes visible, so no visible-but-unknown file can exist.
            episodeDao.updateOutputUri(episode.episodeId, insertUri.toString())
            makeVisible(insertUri)
            deleteLocal(local, episode.episodeId)
            ExportOutcome.Exported(insertUri.toString())
        } catch (e: CancellationException) {
            // Worker cancelled mid-copy: the pending row stays invisible and is
            // reused-or-swept on the next run (§30.3). Local file untouched.
            throw e
        } catch (e: Exception) {
            // Insufficient storage, short copy, vanished row, query failure → delete
            // the (still pending) row, keep the local file (§30.4). Never visible.
            try {
                deleteRow(insertUri)
            } catch (cleanup: Exception) {
                AppLog.w(TAG, "Failed to remove pending row after export failure: ${cleanup.message}")
            }
            AppLog.w(TAG, "Export failed for ${episode.episodeId} (local kept): ${e.message}")
            ExportOutcome.Failed(e.message ?: "export failed")
        }
    }

    // ------------------------------------------------------------------
    // Slice A: legacy alias + cross-reference guard
    // ------------------------------------------------------------------

    /**
     * True when a Room row OTHER than [episodeId] already points at [outputUri].
     *
     * While this holds the file is never adopted and never deleted: a URI two
     * rows reference belongs to whichever episode produced it, so replacing or
     * sharing it would damage the other row (Slice A cross-reference guard).
     * One bounded query per candidate — the episode table is small and exports
     * are rare.
     */
    private suspend fun referencedByOtherRow(outputUri: String, episodeId: String): Boolean =
        episodeDao.otherReferenceAudios(outputUri, episodeId).isNotEmpty()

    /**
     * True when [uri] may be neither adopted nor deleted by THIS episode's
     * run.
     *
     * Two independent reasons, both meaning "not this run's to touch":
     *
     * * another Room row points at it — the Slice A cross-reference guard;
     * * it is the export a `Replace file` run was launched to replace
     *   (DECISIONS 152). This second clause is what makes the old item survive
     *   the whole staging window: `otherReferenceAudios` deliberately EXCLUDES
     *   the calling row, and the prior usually IS this row's own `outputUri`,
     *   so without it the candidate loop would be free to adopt it (size match)
     *   or delete it (exact name) before the new copy had been verified at all.
     *
     * Null `replacePriorUri` — every ordinary row — reduces this to the
     * original guard, so behaviour is unchanged outside a replacement.
     */
    private suspend fun isReserved(uri: String, episode: EpisodeDownloadEntity): Boolean =
        uri == episode.replacePriorUri || referencedByOtherRow(uri, episode.episodeId)

    /**
     * Slice A legacy adoption: adopt [legacy] as THIS episode's export only when
     * it is size-verified against the local source AND no other row references
     * it. Returns the [ExportOutcome.AlreadyExported] outcome when adopted,
     * `null` when the caller must keep looking — a size-mismatched or
     * referenced legacy file is left completely untouched (never deleted,
     * renamed or migrated).
     */
    private suspend fun adoptLegacy(
        legacy: StoreRow,
        local: File,
        episode: EpisodeDownloadEntity,
    ): ExportOutcome.AlreadyExported? {
        if (legacy.size <= 0L || legacy.size != local.length()) return null
        if (isReserved(legacy.uri.toString(), episode)) return null
        episodeDao.updateOutputUri(episode.episodeId, legacy.uri.toString())
        if (legacy.isPending) clearPending(legacy.uri) // legacy kill-after-X4
        deleteLocal(local, episode.episodeId)
        return ExportOutcome.AlreadyExported(legacy.uri.toString())
    }

    // ------------------------------------------------------------------
    // MediaStore primitives (blocking — call inside Dispatchers.IO)
    // ------------------------------------------------------------------

    /**
     * All rows we own whose `RELATIVE_PATH` is exactly [relativePath] —
     * the episode's per-anime directory, or the legacy flat directory —
     * **including `IS_PENDING=1` rows**.
     *
     * The legacy `(selection, selectionArgs)` query overload silently excludes
     * pending rows (verified empirically on API 36: identical selection returns
     * only the visible row, while the Bundle form with [MediaStore.QUERY_ARG_MATCH_PENDING]
     * = [MediaStore.MATCH_INCLUDE] returns pending rows too). §30.2 reuse/replace and
     * the §30.4 sweep MUST see pending rows — that is precisely the crash residue
     * they exist to recover.
     */
    private fun queryRowsInDir(relativePath: String): List<StoreRow> = queryRows(
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? " +
            "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
        arrayOf(relativePath, BuildConfig.APPLICATION_ID),
    )

    /**
     * All rows we own ANYWHERE under `Movies/` (Gate A): the legacy flat
     * directory plus every per-anime directory, one bounded query — never a
     * full MediaStore scan, and never another application's rows (the
     * `OWNER_PACKAGE_NAME` filter is unchanged).
     */
    private fun queryRowsInOurMoviesTree(): List<StoreRow> = queryRows(
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? " +
            "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
        arrayOf(OutputPathNames.MOVIES_PREFIX + "%", BuildConfig.APPLICATION_ID),
    )

    /** Shared Bundle query: [selection] + [selectionArgs], pending rows included. */
    private fun queryRows(selection: String, selectionArgs: Array<String>): List<StoreRow> {
        val projection = arrayOf(
            "_id",
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            // Pending rows are invisible to the legacy query overload; §30.2/§30.4
            // depend on seeing them.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val rows = mutableListOf<StoreRow>()
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            args,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pendingIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                var size = if (cursor.isNull(sizeIdx)) -1L else cursor.getLong(sizeIdx)
                val rowUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idIdx),
                )
                if (size <= 0L) {
                    // Pending rows report `_size = null` until made visible
                    // (verified on API 36); §30.2's size-compare needs the real
                    // size, so stat the fd directly (same fallback as queryByUri).
                    try {
                        contentResolver.openFileDescriptor(rowUri, "r")?.use { pfd ->
                            size = android.system.Os.fstat(pfd.fileDescriptor).st_size
                        }
                    } catch (e: Exception) {
                        AppLog.d(TAG, "Size stat failed for $rowUri: ${e.message}")
                    }
                }
                rows += StoreRow(
                    uri = rowUri,
                    displayName = name,
                    size = size,
                    isPending = !cursor.isNull(pendingIdx) && cursor.getInt(pendingIdx) == 1,
                    dateAddedSec = if (cursor.isNull(addedIdx)) -1L else cursor.getLong(addedIdx),
                )
            }
        }
        return rows
    }

    /** Single-row lookup by URI: size + pending flag; null when it does not resolve. */
    private fun queryByUri(uriString: String): StoreRow? {
        val uri = Uri.parse(uriString)
        return try {
            var size = -1L
            var pending = false
            var name: String? = null
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.IS_PENDING,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    val pendingIdx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    pending = pendingIdx >= 0 && !cursor.isNull(pendingIdx) &&
                        cursor.getInt(pendingIdx) == 1
                }
            }
            if (size < 0) {
                // SIZE column unavailable → fall back to an fd stat size.
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = android.system.Os.fstat(pfd.fileDescriptor).st_size
                }
            }
            if (size <= 0L) return null
            StoreRow(uri, name ?: "", size, pending, -1L)
        } catch (e: Exception) {
            AppLog.d(TAG, "outputUri does not resolve: ${e.message}")
            null
        }
    }

    private fun querySize(uri: Uri): Long {
        var size = -1L
        contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0)
        }
        if (size < 0) {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                size = android.system.Os.fstat(pfd.fileDescriptor).st_size
            }
        }
        return size
    }

    private fun insertPending(displayName: String, relativePath: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    /** Stream-copy [local] into [uri] with a bounded buffer; returns bytes written. */
    private fun copyFile(local: File, uri: Uri): Long {
        val output = contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("MediaStore openOutputStream returned null")
        var copied = 0L
        try {
            local.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    copied += read
                }
            }
            output.flush()
        } finally {
            try {
                output.close()
            } catch (e: Exception) {
                AppLog.w(TAG, "Closing export stream failed: ${e.message}")
            }
        }
        return copied
    }

    private fun makeVisible(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        val updated = contentResolver.update(uri, values, null, null)
        if (updated <= 0) throw IOException("row vanished before it could be made visible")
    }

    private fun clearPending(uri: Uri) {
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to clear IS_PENDING: ${e.message}")
        }
    }

    private fun deleteRow(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to delete row $uri: ${e.message}")
        }
    }

    /**
     * X6: the only local-MP4 deletion anywhere in production — strictly after
     * byte-verified Room persistence and visibility (§27 decision 7, §30.1 step 7).
     */
    private fun deleteLocal(local: File, episodeId: String) {
        try {
            if (local.exists() && !local.delete()) {
                AppLog.w(TAG, "Could not remove local duplicate for $episodeId")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Local duplicate cleanup failed for $episodeId: ${e.message}")
        }
    }

    /**
     * §30.2 name match: exact `FileNameGenerator` output, or the MediaStore
     * uniquified form `stem (n).mp4` (MediaStore appends ` (n)` on collisions).
     */
    private fun nameMatches(displayName: String, expected: String): Boolean {
        if (displayName == expected) return true
        if (!expected.endsWith(".mp4")) return false
        val stem = expected.removeSuffix(".mp4")
        val uniquified = Regex("^" + Pattern.quote(stem) + " \\(\\d+\\)\\.mp4$")
        return uniquified.matches(displayName)
    }
}
