package com.pahebatcher.app.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.testdocs.PBGrantProbeActivity

/**
 * Phase 13 SAF test support (DECISIONS 92.8 test surface): establishes and
 * releases REAL persistable grants, the same grant state the system document
 * picker produces, without driving UI automation.
 *
 * Mechanism (probe-verified on the connected device):
 *  1. An intent carrying FLAG_GRANT_{READ,WRITE,PERSISTABLE}_URI_PERMISSION is
 *     dispatched from a foreground activity to [PBGrantProbeActivity].
 *  2. Intent-delivered URI grants are OWNED by the receiving ActivityRecord
 *     and revoked when that record is destroyed, so the receiver stays alive
 *     (`keepAlive` extra) until [establishPersistableGrant] has taken the
 *     grant.
 *  3. `takePersistableUriPermission` then persists the grant uid-scoped,
 *     independent of the receiver's later finish — exactly the picker flow
 *     (Settings takes the grant while its receiving activity is alive).
 *
 * Negative findings kept out of production: `Context.grantUriPermission`
 * creates only non-persistable grants, and the shell has no source access to
 * tree URIs — neither can produce a takeable grant.
 */
object SafTestSupport {

    private const val TAG = "SafTestSupport"
    private const val TAKE_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Establish a persistable READ|WRITE grant for [tree] and fail loudly if
     * the platform refuses (a silent failure would make every downstream
     * assertion vacuous).
     */
    fun establishPersistableGrant(context: Context, tree: Uri) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1_500) // receiver dispatch requires a foreground caller
            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(activity, PBGrantProbeActivity::class.java)
                        .setData(tree)
                        .putExtra(PBGrantProbeActivity.EXTRA_KEEP_ALIVE, true)
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                        ),
                )
            }
            val deadline = System.currentTimeMillis() + 10_000
            var takeError: String? = "not attempted"
            while (System.currentTimeMillis() < deadline) {
                takeError = try {
                    context.contentResolver.takePersistableUriPermission(tree, TAKE_FLAGS)
                    null
                } catch (e: Exception) {
                    e.toString()
                }
                if (takeError == null) return
                Thread.sleep(250)
            }
            throw AssertionError(
                "$TAG: takePersistable for $tree failed while receiver alive: $takeError",
            )
        }
    }

    /** Best-effort release of a previously taken grant (test hygiene). */
    fun releasePersistableGrant(context: Context, tree: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(tree, TAKE_FLAGS)
        } catch (_: Exception) {
            // Not held — nothing to release.
        }
    }

    /** True when this uid currently holds a READ|WRITE persisted grant. */
    fun hasPersistableGrant(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }

    /** Test hygiene: delete every child under [tree]'s root (never roots). */
    fun clearTreeChildren(context: Context, tree: Uri) {
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                )
                while (cursor.moveToNext()) {
                    try {
                        DocumentsContract.deleteDocument(
                            context.contentResolver,
                            DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idx)),
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
