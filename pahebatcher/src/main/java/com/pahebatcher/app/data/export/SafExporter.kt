package com.pahebatcher.app.data.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.hls.FileNameGenerator
import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Result of one SAF export attempt (Phase 13, DECISIONS 104).
 *
 * [Completed] carries the ordinary [ExportOutcome]; [RenameUnsupported] is the
 * 104.4 rename-less signal — the SAF call created and cleaned its own part, and
 * the destination router runs this export through the MediaStore default.
 */
sealed interface SafExportResult {
    data class Completed(val outcome: ExportOutcome) : SafExportResult
    data object RenameUnsupported : SafExportResult
}

/**
 * SAF export of a completed episode MP4 into the picked tree (DECISIONS 92/104).
 *
 * Pipeline (92.1): private staging stays untouched — this class only performs
 * `local MP4 → app-owned .pb.part child → rename → byte verify → Room X4 →
 * local X6`. The local source is deleted ONLY after the destination child has
 * been byte-verified AND `outputUri` persisted (92.4, X6 rule unchanged).
 *
 * Contracts implemented here:
 *  - 104.1 naming: `<final-display-name>.pb.part` inside the per-anime
 *    directory under the tree root (Gate A). Legacy root-level `.pb.part`
 *    residue from pre-Gate-A crashes is still finalized/discarded in place at
 *    export start — never moved.
 *  - 104.2 ownership: children ending in `.pb.part` are app-owned — the sole
 *    deletion carve-out; every other child is never touched.
 *  - 104.3 cleanup: exactly two triggers — children queries at export start
 *    (tree root + anime directory; stale parts finalized/discarded against
 *    the surviving local source) and best-effort deletion of THIS call's part
 *    on its failure path. No startup tree walk.
 *  - 104.4 rename-less providers: `UnsupportedOperationException` from
 *    `DocumentsContract.renameDocument` (verified: rethrown as-is for
 *    targetSdk ≥ O) → own-part deleted, `output_tree_rename_ok` persisted
 *    false, [SafExportResult.RenameUnsupported] returned for MediaStore
 *    fallback. A naked direct final-name copy never happens.
 *  - 104.5 crash matrix: verification state is never persisted; every recovery
 *    compares against the surviving local source.
 *  - 104.6 collisions: children queries over the anime directory, NFC +
 *    case-insensitive whole-name key ([SafExportNames.key]), reuse-by-size
 *    else first-free ` (n)` child. Slice A: the lookup name is THIS episode's
 *    audio-specific export name ([ExportFileNames.exportedName]) and — when no
 *    such child matches — the pre-Slice-A legacy alias
 *    ([ExportFileNames.legacyName]); a candidate is adopted only when it is
 *    size-verified AND no other Room row references it. Pre-Gate-A root-level
 *    finals go through the same lookup — never deleted, renamed or moved.
 *  - 92.5: existing final children are never overwritten or deleted. This is
 *    intentionally NOT the MediaStore contract: MediaStore replaces an exact-name
 *    size-mismatched row (§36-7 one-row invariant), SAF never overwrites and
 *    uniquifies instead (Decision 92.5/104.6).
 *
 * Serialization: the shared [ExportOperationLock.mutex] — SAF and MediaStore
 * exports never interleave for any episode (§30.4 bound).
 */
class SafExporter(
    private val contentResolver: ContentResolver,
    private val episodeDao: EpisodeDownloadDao,
    private val config: AppConfig,
    private val sessionDao: DownloadSessionDao,
) {

    companion object {
        private const val TAG = "SafExporter"

        /** Bounded copy buffer — never load the episode into memory (AGENTS §12). */
        private const val BUFFER_SIZE = 64 * 1024

        /** `.pb.part` never carries a media extension → invisible to galleries. */
        private const val PART_MIME = "application/octet-stream"

        private val operationLock = ExportOperationLock.mutex

        /** One child row of a queried tree document (root OR anime directory). */
        internal data class ChildDoc(
            val docId: String,
            val name: String,
            val size: Long,
            /** MIME — `vnd.android.document/directory` marks the anime directory. */
            val mimeType: String?,
        ) {
            val isDir: Boolean
                get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }
    }

    /**
     * `Replace file` (DECISIONS 152) — paired retirement for this destination,
     * shared with [MediaStoreExporter] so both halves of a replacement follow
     * one rule regardless of which storage provider holds the old file.
     */
    private val priorOutputRetirement = PriorOutputRetirement(contentResolver, episodeDao)

    /**
     * Export [episode]'s local MP4 into [treeUri]'s per-anime directory —
     * `<sanitized anime title>/` created beneath the tree root on first use
     * (Gate A; Python `os.path.join(output, sanitize(title))` parity).
     * The caller (destination router) has already validated the tree (92.3).
     */
    suspend fun export(episode: EpisodeDownloadEntity, treeUri: String): SafExportResult =
        operationLock.withLock {
            withContext(Dispatchers.IO) {
                val result = exportLocked(episode, Uri.parse(treeUri))
                // `Replace file` (DECISIONS 152): the prior export is retired
                // HERE — inside the same lock, and only once exportLocked has
                // returned a COMMITTED outcome. `RenameUnsupported` has produced
                // no final file yet, so it never reaches the retirement, and a
                // `Failed` never does either: an unsuccessful replacement can
                // therefore never remove the file it was replacing.
                if (result is SafExportResult.Completed) {
                    priorOutputRetirement.retire(episode, result.outcome)
                }
                result
            }
        }

    // ------------------------------------------------------------------
    // Implementation (always inside operationLock + Dispatchers.IO)
    // ------------------------------------------------------------------

    private suspend fun exportLocked(
        episode: EpisodeDownloadEntity,
        treeUri: Uri,
    ): SafExportResult {
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return SafExportResult.Completed(ExportOutcome.Failed("not a tree URI"))
        }

        // --- Local source validity (never export from a missing file) ---
        val localPath = episode.outputPath
        val local = localPath?.takeIf { it.isNotBlank() }?.let(::File)
        val localValid = local != null && local.exists() && local.length() > 0L

        // --- §30.3 persisted-URI fast path (any content:// — MediaStore or SAF) ---
        //
        // `Replace file` (DECISIONS 152): while a replacement is in flight this
        // reference is the export being REPLACED, not an already-committed
        // result of this run. Taking the fast path for it would either discard
        // the freshly downloaded output (`AlreadyExported` + `deleteLocal` when
        // the sizes happen to match) or let a size-mismatched fall-through reach
        // the old item before the new copy exists. A replacement always stages a
        // fresh document instead.
        val persistedUri = episode.outputUri
        val persistedIsPrior = persistedUri != null && persistedUri == episode.replacePriorUri
        if (!persistedUri.isNullOrEmpty() && !persistedIsPrior) {
            val persistedSize = queryUriSize(persistedUri)
            if (persistedSize > 0L) {
                if (!localValid) {
                    // Fully exported before: URI resolves, local already cleaned (X6).
                    return SafExportResult.Completed(ExportOutcome.AlreadyExported(persistedUri))
                }
                if (persistedSize == local.length()) {
                    // Size-verified duplicate (sizes only): finish any interrupted
                    // bookkeeping — §30.3 rows 1/4 equivalent (kill-after-X4,
                    // kill-after-X5).
                    deleteLocal(local, episode.episodeId)
                    return SafExportResult.Completed(ExportOutcome.AlreadyExported(persistedUri))
                }
                // Size mismatch: a stale reference superseded by a re-download —
                // fall through and replace it with a verified fresh copy.
            }
            // Dangling URI: fall through. If no local source survived (the usual
            // case after X6), the honest Failed below stands; §29/103 recovery
            // stays with reconciliation + explicit Retry.
        }

        if (local == null || !local.exists() || local.length() <= 0L) {
            return SafExportResult.Completed(ExportOutcome.Failed("local source missing or empty"))
        }
        val source = local
        val sourceBytes = source.length()

        // --- Slice A: the EXPORT leaf name carries the audio discriminator, so
        // two audio variants of one episode can never share a destination name
        // (pure function of this row's own metadata — export order cannot
        // affect it). `legacyName` is the pre-Slice-A unsuffixed name existing
        // files were created with; it is only ever REUSED, never deleted. ---
        val prefix = FileNameGenerator.formatEpisodePrefix(episode.episodeNumber)
        val expectedName = ExportFileNames.exportedName(prefix, episode.title, episode.audio)
        val legacyName = ExportFileNames.legacyName(prefix, episode.title)

        // --- Gate A: destination = <selected tree>/<sanitized anime title>/
        // (Python main.py:288-289 parity); title read from the session row. ---
        val dirName = OutputPathNames.directoryName(
            sessionDao.getById(episode.sessionId)?.title,
            episode.sessionId,
        )

        // --- 104.3: the tree-root children query — locates the anime directory
        // and feeds the legacy root-level stale-part pass below. ---
        val rootChildren = try {
            queryChildren(treeUri, rootDocId)
        } catch (e: SecurityException) {
            return SafExportResult.Completed(ExportOutcome.Failed("tree access denied: ${e.message}"))
        } catch (e: Exception) {
            return SafExportResult.Completed(ExportOutcome.Failed("children query failed: ${e.message}"))
        }

        // Anime directory THIS call created (if absent): removed again when it
        // is still empty, so a failed export, a rename-less fallback or a
        // legacy-root reuse never leaves an empty folder behind (104.3 hygiene).
        // Pre-existing directories are never deleted here.
        var createdDirDocId: String? = null
        return try {
            val existingDir = rootChildren.firstOrNull {
                it.isDir && isAnimeDirName(it.name, dirName)
            }
            val animeDocId: String
            if (existingDir != null) {
                animeDocId = existingDir.docId
            } else {
                val created = try {
                    DocumentsContract.createDocument(
                        contentResolver,
                        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        dirName,
                    )
                } catch (e: Exception) {
                    return SafExportResult.Completed(
                        ExportOutcome.Failed("anime directory create failed: ${e.message}"),
                    )
                } ?: return SafExportResult.Completed(
                    ExportOutcome.Failed("anime directory create returned null"),
                )
                animeDocId = DocumentsContract.getDocumentId(created)
                createdDirDocId = animeDocId
            }

            val animeChildren = try {
                queryChildren(treeUri, animeDocId)
            } catch (e: Exception) {
                return SafExportResult.Completed(
                    ExportOutcome.Failed("anime directory query failed: ${e.message}"),
                )
            }
            val animeFinals =
                animeChildren.filter { !it.isDir && !SafExportNames.isPart(it.name) }
            val rootFinals =
                rootChildren.filter { !it.isDir && !SafExportNames.isPart(it.name) }

            // --- 104.3 export-start stale-part pass (104.5 rows 1-3), over BOTH
            // generations of crash residue: parts inside the anime directory and
            // legacy root-level parts from pre-Gate-A exports. Each part is
            // finalized/discarded IN ITS OWN parent — never moved.
            //
            // Slice A: `isOurs` now compares against the AUDIO-SPECIFIC name, so
            // a part whose target is a legacy unsuffixed name (or another audio
            // variant's name) is never finalized as this episode's bytes — the
            // cross-audio finalization hazard that colliding names caused before
            // Slice A. Such a part is an app-owned temporary (104.2), discarded:
            // losing it is free, because X6 only runs after a rename, so the
            // episode's own local source always survives to re-copy. ---
            val staleParts = animeChildren.filter { SafExportNames.isPart(it.name) } +
                rootChildren.filter { SafExportNames.isPart(it.name) }
            for (part in staleParts) {
                val intended = SafExportNames.partTarget(part.name)
                if (!SafExportNames.isOurs(intended, expectedName)) {
                    // Not this episode+audio's temporary — app-owned by definition
                    // (104.2), never a user file. Deleting loses nothing: a
                    // surviving local source re-copies on its own export; X6 only
                    // runs after a rename, so no completed export leaves a part.
                    deleteChild(treeUri, part.docId)
                    continue
                }
                if (part.size == sourceBytes) {
                    // Complete part from a crashed export (104.5 rows 2/3):
                    // finalize it instead of copying again.
                    val siblings =
                        if (part.docId.startsWith("$animeDocId/")) animeFinals else rootFinals
                    return finalizePart(
                        episode, treeUri, part, intended, source, sourceBytes, siblings,
                    )
                }
                // Partial part (104.5 row 1): byte count differs → discard, copy fresh.
                deleteChild(treeUri, part.docId)
            }

            // --- 104.6 collision lookup over FINAL children (reuse-by-size, 92.5),
            // plus the Slice A legacy alias for pre-Slice-A unsuffixed exports.
            // Finals are NEVER deleted, renamed or overwritten here (92.5 /
            // SPEC §13), and a child another Room row already points at is never
            // adopted either — two episodes must never share one destination
            // file. Order: this episode's audio-specific name first (anime dir,
            // then the pre-Gate-A root), then the legacy alias (anime dir, then
            // root). Verification is by SIZE (never a byte-level comparison). ---
            val reuseNames =
                if (legacyName == expectedName) listOf(expectedName)
                else listOf(expectedName, legacyName)
            for (name in reuseNames) {
                for (child in animeFinals + rootFinals) {
                    if (SafExportNames.key(child.name) != SafExportNames.key(name)) continue
                    if (child.size != sourceBytes) continue
                    val candidateUri = childUri(treeUri, child.docId)
                    if (isReserved(candidateUri.toString(), episode)) continue
                    // Size-verified existing child (104.5 row 4): reuse, never overwrite.
                    episodeDao.updateOutputUri(episode.episodeId, candidateUri.toString())
                    deleteLocal(source, episode.episodeId)
                    return SafExportResult.Completed(ExportOutcome.AlreadyExported(candidateUri.toString()))
                }
            }

            // --- 104.6: differing byte count → first free ` (n)` name (anime dir) ---
            val targetName = SafExportNames.uniquify(expectedName, animeFinals.map { it.name })
            val partName = targetName + SafExportNames.PART_SUFFIX

            // --- 104.1: create the app-owned part child INSIDE the anime directory ---
            val createdUri = try {
                DocumentsContract.createDocument(
                    contentResolver,
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, animeDocId),
                    PART_MIME,
                    partName,
                )
            } catch (e: Exception) {
                return SafExportResult.Completed(
                    ExportOutcome.Failed("part create failed: ${e.message}"),
                )
            } ?: return SafExportResult.Completed(
                ExportOutcome.Failed("part create returned null"),
            )
            val partDocId = DocumentsContract.getDocumentId(createdUri)
            val partUri = childUri(treeUri, partDocId)
            try {
                // Bounded stream copy + byte verification (never whole-file, AGENTS §12)
                val copied = copyFile(source, partUri)
                if (copied != sourceBytes) {
                    throw IOException("copied $copied bytes != source $sourceBytes")
                }
                val stored = queryDocSize(treeUri, partDocId)
                if (stored != sourceBytes) {
                    throw IOException("provider reports $stored bytes != source $sourceBytes")
                }

                // Rename to the final name — capability discovered HERE (104.4).
                val finalUri = try {
                    DocumentsContract.renameDocument(contentResolver, partUri, targetName)
                } catch (unsupported: UnsupportedOperationException) {
                    return handleRenameUnsupported(episode, treeUri, partDocId)
                } ?: throw IOException("rename returned no document")

                // Verify the renamed child (104.3 order: rename → verify → persist → X6).
                val finalDocId = DocumentsContract.getDocumentId(finalUri)
                val finalSize = queryDocSize(treeUri, finalDocId)
                if (finalSize != sourceBytes) {
                    // Our own child from this call's part, never persisted to Room —
                    // removing it cannot touch a user file (92.5).
                    deleteChild(treeUri, finalDocId)
                    throw IOException("post-rename verify failed: $finalSize != $sourceBytes")
                }

                // X4 → X6: Room knows the URI strictly before the local delete.
                episodeDao.updateOutputUri(episode.episodeId, finalUri.toString())
                deleteLocal(source, episode.episodeId)
                SafExportResult.Completed(ExportOutcome.Exported(finalUri.toString()))
            } catch (e: CancellationException) {
                // Crash-equivalent: the part stays for the next export-start pass
                // (104.5 rows 1-3). Local source untouched.
                throw e
            } catch (e: Exception) {
                // 104.3 failure path: best-effort delete of THIS call's own part
                // (a no-op if it was already renamed — then the complete child is
                // reused by size on the next attempt, 104.5 row 4).
                deleteChild(treeUri, partDocId)
                AppLog.w(TAG, "SAF export failed for ${episode.episodeId} (local kept): ${e.message}")
                SafExportResult.Completed(ExportOutcome.Failed(e.message ?: "SAF export failed"))
            }
        } finally {
            createdDirDocId?.let { cleanupEmptyCreatedDir(treeUri, it) }
        }
    }

    /**
     * True when a Room row OTHER than [episodeId] already points at [outputUri]
     * (Slice A cross-reference guard: while this holds the file is neither
     * adopted nor deleted — sharing it would make two episodes own one file).
     * One bounded query per size-verified candidate; exports are rare.
     */
    private suspend fun referencedByOtherRow(outputUri: String, episodeId: String): Boolean =
        episodeDao.otherReferenceAudios(outputUri, episodeId).isNotEmpty()

    /**
     * `Replace file` (DECISIONS 152) — may THIS run adopt or remove [uri]?
     *
     * Extends the Slice A cross-reference guard with the export a confirmed
     * replacement was launched to replace. `otherReferenceAudios` excludes the
     * calling row by design, and the prior normally IS that row's own
     * `outputUri`, so without this clause a size-identical prior would be
     * adopted as if the replacement had already succeeded. With a null
     * `replacePriorUri` — every ordinary row — this is exactly the original
     * guard.
     */
    private suspend fun isReserved(uri: String, episode: EpisodeDownloadEntity): Boolean =
        uri == episode.replacePriorUri || referencedByOtherRow(uri, episode.episodeId)

    /**
     * 104.5 rows 2/3: a complete stale `.pb.part` whose SIZE equals the
     * surviving local source is finalized — rename to the intended final name
     * (collision rule applied), verify, persist, X6 — instead of re-copying.
     */
    private suspend fun finalizePart(
        episode: EpisodeDownloadEntity,
        treeUri: Uri,
        part: ChildDoc,
        intended: String,
        source: File,
        sourceBytes: Long,
        children: List<ChildDoc>,
    ): SafExportResult {
        val finalChildren = children.filter { !SafExportNames.isPart(it.name) }
        val sameKey = finalChildren.filter {
            SafExportNames.key(it.name) == SafExportNames.key(intended)
        }

        // A size-verified final child already exists (104.5 row 4 with a
        // leftover part): reuse it and drop the redundant part — unless another
        // Room row already owns it (Slice A cross-reference guard, then uniquify
        // below instead of sharing the file).
        sameKey.firstOrNull {
            it.size == sourceBytes &&
                !isReserved(childUri(treeUri, it.docId).toString(), episode)
        }?.let { identical ->
            deleteChild(treeUri, part.docId)
            val uri = childUri(treeUri, identical.docId)
            episodeDao.updateOutputUri(episode.episodeId, uri.toString())
            deleteLocal(source, episode.episodeId)
            return SafExportResult.Completed(ExportOutcome.AlreadyExported(uri.toString()))
        }

        // Same key with differing bytes → uniquify; no key match → intended itself.
        val targetName = SafExportNames.uniquify(intended, finalChildren.map { it.name })
        val partUri = childUri(treeUri, part.docId)
        return try {
            val finalUri = try {
                DocumentsContract.renameDocument(contentResolver, partUri, targetName)
            } catch (unsupported: UnsupportedOperationException) {
                return handleRenameUnsupported(episode, treeUri, part.docId)
            } ?: throw IOException("rename returned no document")

            val finalDocId = DocumentsContract.getDocumentId(finalUri)
            val finalSize = queryDocSize(treeUri, finalDocId)
            if (finalSize != sourceBytes) {
                deleteChild(treeUri, finalDocId)
                throw IOException("post-rename verify failed: $finalSize != $sourceBytes")
            }
            episodeDao.updateOutputUri(episode.episodeId, finalUri.toString())
            deleteLocal(source, episode.episodeId)
            SafExportResult.Completed(ExportOutcome.Exported(finalUri.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deleteChild(treeUri, part.docId)
            AppLog.w(TAG, "SAF finalize failed for ${episode.episodeId} (local kept): ${e.message}")
            SafExportResult.Completed(ExportOutcome.Failed(e.message ?: "SAF finalize failed"))
        }
    }

    /**
     * 104.4: the provider refused `renameDocument` — delete THIS call's own
     * part, persist `output_tree_rename_ok = false`, and let the destination
     * router run the export through the MediaStore default (existing X1–X6).
     */
    private suspend fun handleRenameUnsupported(
        episode: EpisodeDownloadEntity,
        treeUri: Uri,
        partDocId: String,
    ): SafExportResult {
        deleteChild(treeUri, partDocId)
        config.updateOutputTreeRenameOk(false)
        AppLog.i(
            TAG,
            "Provider refused rename for ${episode.episodeId} — " +
                "tree marked unsupported, MediaStore default applies (104.4)",
        )
        return SafExportResult.RenameUnsupported
    }

    // ------------------------------------------------------------------
    // SAF primitives (blocking — always inside Dispatchers.IO)
    // ------------------------------------------------------------------

    /**
     * All children of the tree document [parentDocId] (root or anime
     * directory) — two such queries per export (104.3).
     */
    private fun queryChildren(treeUri: Uri, parentDocId: String): List<ChildDoc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val rows = mutableListOf<ChildDoc>()
        val cursor = contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("children query returned no cursor")
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mimeIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: continue
                val size = if (it.isNull(sizeIdx)) -1L else it.getLong(sizeIdx)
                rows += ChildDoc(
                    it.getString(idIdx),
                    name,
                    size,
                    if (it.isNull(mimeIdx)) null else it.getString(mimeIdx),
                )
            }
        }
        return rows
    }

    /**
     * Gate A directory match: exact (NFC + case-insensitive — the 104.6 key),
     * or the provider-uniquified `<name> (n)` form a strict provider may have
     * produced when a FILE already occupied the title's name at the root.
     */
    private fun isAnimeDirName(childName: String, dirName: String): Boolean {
        val key = SafExportNames.key(childName)
        val base = SafExportNames.key(dirName)
        if (key == base) return true
        if (!key.startsWith("$base (") || !key.endsWith(")")) return false
        return key.removePrefix("$base (").dropLast(1).all { it.isDigit() }
    }

    /**
     * Gate A: remove the anime directory THIS call created when it is still
     * empty after the export ended (failure, rename-less fallback, or a
     * legacy-root reuse/finalize). Directories that received a file — or that
     * already existed — are never touched.
     */
    private fun cleanupEmptyCreatedDir(treeUri: Uri, docId: String) {
        try {
            if (queryChildren(treeUri, docId).isEmpty()) {
                DocumentsContract.deleteDocument(contentResolver, childUri(treeUri, docId))
            }
        } catch (e: Exception) {
            AppLog.d(TAG, "Empty anime-directory cleanup skipped: ${e.message}")
        }
    }

    /** Tree-scoped document URI for [docId]. */
    private fun childUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** Size of one tree document; -1 when it does not resolve. */
    private fun queryDocSize(treeUri: Uri, docId: String): Long =
        queryUriSize(childUri(treeUri, docId).toString())

    /**
     * Byte size behind any `content://` URI (SAF child or MediaStore row):
     * SIZE column first, fd-stat fallback, -1 when unresolvable — same
     * resolution rules as the §29 validator.
     */
    private fun queryUriSize(uriString: String): Long {
        return try {
            val uri = Uri.parse(uriString)
            var size = -1L
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
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
            size
        } catch (e: Exception) {
            AppLog.d(TAG, "Size lookup failed for $uriString: ${e.message}")
            -1L
        }
    }

    /** Stream-copy [source] into [target] with a bounded buffer; bytes written. */
    private fun copyFile(source: File, target: Uri): Long {
        val output = contentResolver.openOutputStream(target, "w")
            ?: throw IOException("openOutputStream returned null")
        var copied = 0L
        try {
            source.inputStream().use { input ->
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

    /** Best-effort delete of an app-owned child (`.pb.part` or our own failed rename). */
    private fun deleteChild(treeUri: Uri, docId: String) {
        try {
            DocumentsContract.deleteDocument(contentResolver, childUri(treeUri, docId))
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not delete document $docId: ${e.message}")
        }
    }

    /**
     * X6: the only local-MP4 deletion on the SAF path — strictly after
     * byte-verified Room persistence (92.4, X6 rule unchanged from §30).
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
}
