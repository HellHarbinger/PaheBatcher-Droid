package com.pahebatcher.app.data.export

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * State of the persisted SAF output destination (DECISIONS 92.3).
 */
@Immutable
sealed interface OutputTreeState {

    /** No tree configured → exports use the MediaStore default (92.2). */
    data object NotConfigured : OutputTreeState

    /**
     * Grant present AND the tree document itself re-queried successfully.
     * Only this state routes exports through SAF — and only while
     * [renameOk] holds (DECISIONS 104.4): a rename-less provider keeps the
     * grant and the tree intact, yet every export is routed to the MediaStore
     * default, so the Settings state must say so instead of claiming the
     * folder receives exports.
     */
    data class Valid(
        val treeUri: String,
        /** `output_tree_rename_ok` (104.4); default true = fresh pick / unset. */
        val renameOk: Boolean = true,
    ) : OutputTreeState

    /**
     * A tree URI is configured but unusable (permission revoked, tree moved/
     * deleted, provider gone, malformed URI) — exports fall back to the
     * MediaStore default and Settings surfaces the reason (92.3, 103.4).
     */
    data class Unusable(val treeUri: String, val reason: String) : OutputTreeState
}

/**
 * Persistence + validation of the picked SAF
 * output tree.
 *
 * Contract:
 *  - [setOutputTree] takes the persistable grant for the picker-returned URI
 *    (READ|WRITE) and persists it on the EXISTING DataStore; selecting a tree
 *    resets `output_tree_rename_ok` (104.4).
 *  - [validate] re-checks BOTH the persisted grant AND the tree document
 *    itself on every call — a persisted grant proves only the grant, never
 *    that the folder still exists (103.4). Either failure → [OutputTreeState.Unusable].
 *    A successful validation also carries `output_tree_rename_ok` (104.4) so
 *    the reported Valid state cannot contradict where exports really go.
 *  - Validation is process-cached (one restart-validation per process, 92.3);
 *    [validate] forces a fresh check and is what startup / Settings use.
 *  - Nothing here ever blocks or fails a download (92.3): the router treats
 *    every non-[OutputTreeState.Valid] state as "MediaStore default".
 */
class OutputTreeRepository(
    private val contentResolver: ContentResolver,
    private val config: AppConfig,
) {

    companion object {
        private const val TAG = "OutputTreeRepository"
    }

    /** Last validated state for this process; null until first validation. */
    @Volatile
    private var cached: OutputTreeState? = null

    /**
     * Cached state: validate on first use, then reuse until [validate],
     * [setOutputTree] or [clearOutputTree] refresh it.
     */
    suspend fun state(): OutputTreeState = cached ?: validate()

    /** Force a fresh grant + tree-document validation (92.3). */
    suspend fun validate(): OutputTreeState {
        val treeUri = config.outputTreeUri.first()
        if (treeUri.isNullOrBlank()) {
            val state = OutputTreeState.NotConfigured
            cached = state
            return state
        }
        // DECISIONS 104.4: `output_tree_rename_ok` has two consumers — the
        // export-destination selector (ExportDestinationRouter) and THIS
        // projection, which is what the Settings output-folder state renders.
        // Read fresh on every validation so a rename-less discovery lands in
        // the reported state on the next refresh.
        val renameOk = config.outputTreeRenameOk.first()
        val state = withContext(Dispatchers.IO) { validateTree(treeUri, renameOk) }
        cached = state
        if (state is OutputTreeState.Unusable) {
            AppLog.w(TAG, "Output tree unusable: ${state.reason}")
        }
        return state
    }

    /**
     * Persist the picker-returned tree: take the persistable grant first
     * (the offer is only valid while the picker result is fresh — 92.3), then
     * persist, then immediately validate so the caller sees the real state.
     */
    suspend fun setOutputTree(treeUri: String): OutputTreeState =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(treeUri)
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            config.updateOutputTree(treeUri) // resets rename-ok (104.4)
            val renameOk = config.outputTreeRenameOk.first()
            AppLog.i(TAG, "Output tree configured")
            validateTree(treeUri, renameOk)
        }.also { cached = it }

    /**
     * Clear the destination: release the persisted grant (we no longer want
     * it) and drop the key → exports return to the MediaStore default.
     */
    suspend fun clearOutputTree() {
        val current = config.outputTreeUri.first()
        if (current != null) {
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(current),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                AppLog.d(TAG, "Release of output-tree grant skipped: ${e.message}")
            }
        }
        config.updateOutputTree(null)
        cached = OutputTreeState.NotConfigured
    }

    // ------------------------------------------------------------------
    // Validation (blocking — always inside Dispatchers.IO)
    // ------------------------------------------------------------------

    /**
     * Check 1: the persisted grant for BOTH read and write (export needs
     * write). Check 2: the tree DOCUMENT must still resolve — a persisted
     * grant can outlive a moved/deleted folder (103.4). [renameOk] is passed
     * in (DataStore read — not a provider probe) and carried on the Valid
     * state so the reported destination never contradicts the router (104.4).
     */
    private fun validateTree(treeUri: String, renameOk: Boolean): OutputTreeState {
        val uri = Uri.parse(treeUri)

        val granted = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri.toString() == treeUri &&
                permission.isReadPermission &&
                permission.isWritePermission
        }
        if (!granted) {
            return OutputTreeState.Unusable(treeUri, "folder permission was revoked")
        }

        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            val exists = contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() } == true
            if (exists) {
                OutputTreeState.Valid(treeUri, renameOk)
            } else {
                OutputTreeState.Unusable(treeUri, "folder no longer exists")
            }
        } catch (e: IllegalArgumentException) {
            OutputTreeState.Unusable(treeUri, "not a folder URI")
        } catch (e: SecurityException) {
            OutputTreeState.Unusable(treeUri, "folder access denied")
        } catch (e: Exception) {
            OutputTreeState.Unusable(treeUri, "folder inaccessible: ${e.message}")
        }
    }
}
