package com.pahebatcher.app.testdocs

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.pahebatcher.app.BuildConfig
import java.io.File
import java.io.FileNotFoundException

/**
 * Phase 13 SAF test infrastructure (DECISIONS 92.8/104.7) — debug builds only.
 *
 * A REAL [DocumentsProvider] over a real device directory, implementing the
 * standard SAF contract so the PRODUCTION [com.pahebatcher.app.data.export.SafExporter]
 * runs against genuine `DocumentsContract` semantics (create/rename/delete/
 * query/open) instead of a mock:
 *
 *  - root [ROOT_RENAME] supports rename (rename-capable provider path);
 *  - root [ROOT_NORENAME] throws [UnsupportedOperationException] from
 *    `renameDocument` (the 104.4 rename-less fallback path);
 *  - document IDs are `<rootId>` / `<rootId>/<name>` … `<rootId>/<dir>/<name>`
 *    (Gate A: exports nest one per-anime directory level beneath the root).
 *
 * No production code references [AUTHORITY]; the provider only exists in debug
 * manifests (`src/debug/AndroidManifest.xml`) and never ships in release.
 */
class PBTestDocumentsProvider : DocumentsProvider() {

    companion object {
        val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".testdocs"

        /** Rename-capable test tree. */
        const val ROOT_RENAME = "pb_rename_root"

        /** Rename-less test tree — `renameDocument` throws UnsupportedOperationException. */
        const val ROOT_NORENAME = "pb_norename_root"

        /** Tree URI for [rootId] — what a picker would have returned (92.3). */
        fun treeUri(rootId: String): Uri =
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootId)

        /**
         * One-shot fault injection: when true, the next `openDocument` call
         * with a write mode throws (consumed on first use). Set by tests to
         * exercise the export failure path; never touched by production code.
         */
        @Volatile
        var failNextWrite: Boolean = false

        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_LAST_MODIFIED,
        )

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
        )
    }

    private fun rootDir(rootId: String): File {
        val ctx = requireNotNull(context) { "provider not attached" }
        val base = ctx.getExternalFilesDir("pb_testdocs") ?: ctx.filesDir
        return File(base, rootId)
    }

    private fun rootOf(documentId: String): String = documentId.substringBefore('/')

    private fun fileFor(documentId: String): File {
        val root = rootDir(rootOf(documentId))
        val rest = documentId.substringAfter('/', "")
        return if (rest.isEmpty()) root else File(root, rest)
    }

    /** Child document ID under [parentId] — `<parentId>/<name>` (nesting-aware). */
    private fun childDocumentId(parentId: String, file: File): String = "$parentId/${file.name}"

    private fun mimeFor(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        else -> MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun flagsFor(file: File, documentId: String): Int {
        var flags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (rootOf(documentId) != ROOT_NORENAME) {
            // Per-document rename flag: absent on the rename-less root.
            flags = flags or Document.FLAG_SUPPORTS_RENAME
        }
        return flags
    }

    private fun includeFile(cursor: MatrixCursor, file: File, documentId: String) {
        val row = cursor.newRow()
        for (column in cursor.columnNames) {
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> row.add(column, documentId)
                Document.COLUMN_DISPLAY_NAME ->
                    row.add(column, if (file.name.isEmpty()) "/" else file.name)
                Document.COLUMN_MIME_TYPE -> row.add(column, mimeFor(file))
                Document.COLUMN_SIZE -> row.add(column, if (file.isFile) file.length() else 0L)
                Document.COLUMN_FLAGS -> row.add(column, flagsFor(file, documentId))
                Document.COLUMN_LAST_MODIFIED -> row.add(column, file.lastModified())
            }
        }
    }

    override fun onCreate(): Boolean = true

    /**
     * The base implementation returns false; SAF's `enforceTree` uses this to
     * authorize every tree-scoped URI, so the provider must declare its
     * parent-child relationship (flat: `<root>` → `<root>/<name>`).
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == documentId || documentId.startsWith("$parentDocumentId/")

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        for ((rootId, title) in listOf(
            ROOT_RENAME to "PB Test Storage",
            ROOT_NORENAME to "PB No-Rename Storage",
        )) {
            val row = cursor.newRow()
            for (column in cursor.columnNames) {
                when (column) {
                    Root.COLUMN_ROOT_ID -> row.add(column, rootId)
                    Root.COLUMN_DOCUMENT_ID -> row.add(column, rootId)
                    Root.COLUMN_TITLE -> row.add(column, title)
                    Root.COLUMN_SUMMARY -> row.add(column, "Phase 13 SAF test root")
                    Root.COLUMN_ICON -> row.add(column, com.pahebatcher.app.R.mipmap.ic_launcher)
                    Root.COLUMN_FLAGS -> row.add(
                        column,
                        Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH,
                    )
                }
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val file = fileFor(documentId)
        if (file.exists()) {
            includeFile(cursor, file, documentId)
        } else {
            throw FileNotFoundException("No document $documentId")
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val parent = fileFor(parentDocumentId)
        parent.mkdirs()
        parent.listFiles()?.sortedBy { it.name }?.forEach {
            includeFile(cursor, it, childDocumentId(parentDocumentId, it))
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        // Test-only fault injection (DECISIONS 92.8): one-shot write failure
        // so the production export failure path (part cleanup + local kept)
        // is exercised against a REAL provider instead of a mock.
        if (failNextWrite && mode.contains('w')) {
            failNextWrite = false
            throw FileNotFoundException("Injected write failure for $documentId")
        }
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("No document $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String?,
        displayName: String?,
    ): String {
        require(!displayName.isNullOrEmpty()) { "displayName required" }
        require('/' !in displayName && '\\' !in displayName) { "invalid display name" }
        val parent = fileFor(parentDocumentId)
        parent.mkdirs()
        val target = File(parent, displayName)
        // Strict (no silent uniquify): a name collision means the caller's
        // collision resolution was wrong and must surface loudly in tests.
        if (target.exists()) throw FileNotFoundException("Document exists: $displayName")
        if (mimeType == Document.MIME_TYPE_DIR) {
            // Gate A: the per-anime directory beneath the selected tree root.
            if (!target.mkdirs()) throw FileNotFoundException("Could not create $displayName")
            return childDocumentId(parentDocumentId, target)
        }
        if (!target.createNewFile()) throw FileNotFoundException("Could not create $displayName")
        return childDocumentId(parentDocumentId, target)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (documentId == rootOf(documentId)) {
            // Roots are never deletable (mirrors provider behaviour).
            throw FileNotFoundException("Cannot delete root $documentId")
        }
        // Directories (Gate A) recurse; ordinary files delete as before.
        if (file.isDirectory) {
            file.walkBottomUp().forEach { it.delete() }
            if (file.exists()) throw FileNotFoundException("Could not delete $documentId")
            return
        }
        if (file.exists() && !file.delete()) {
            throw FileNotFoundException("Could not delete $documentId")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        // 104.4 rename-less path: this root refuses rename outright.
        if (rootOf(documentId) == ROOT_NORENAME) {
            throw UnsupportedOperationException("renameDocument not supported on $ROOT_NORENAME")
        }
        require('/' !in displayName) { "invalid display name" }
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("No document $documentId")
        val target = File(file.parentFile, displayName)
        if (target.exists()) throw FileNotFoundException("Document exists: $displayName")
        if (!file.renameTo(target)) throw FileNotFoundException("Rename failed: $displayName")
        return childDocumentId(documentId.substringBeforeLast('/', documentId), target)
    }
}
