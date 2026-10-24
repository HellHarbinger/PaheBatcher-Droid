package com.pahebatcher.app.data.download

import android.content.ContentResolver
import androidx.core.net.toUri
import com.pahebatcher.app.logging.AppLog
import java.io.File

/**
 * The ONE completed-output validity rule (§29), shared by every consumer.
 *
 * Slice B (review finding M-1): this rule used to live in TWO places with TWO
 * different answers for the same question. `DownloadStateReconciler` resolved a
 * persisted `outputUri` through the injected [ContentResolver]
 * (`DownloadStateReconciler.kt:329-369`), while
 * `DownloadRepository.outputPresent` trusted any non-blank URI outright
 * (`DownloadRepository.kt:353-360`). The two could therefore disagree about one
 * row: the repository told the user "already downloaded" at submission time and
 * the reconciler demoted that same row to `FAILED` ("file missing") at the next
 * startup. One predicate, one rule.
 *
 * The rule, verbatim from the reconciler (so reconciliation behavior is
 * byte-for-byte unchanged):
 *
 * ```
 * present = (outputUri present ∧ resolver present ∧ URI resolves ∧ size > 0)
 *         ∨ (otherwise            ∧ outputPath present ∧ file exists ∧ size > 0)
 * ```
 *
 * **The no-resolver fallback is deliberate and is the conservative one.** With
 * no [ContentResolver] injected (host-constructed tests; production always wires
 * one through `AppContainer`), a `content://` reference CANNOT be verified — so
 * it is never treated as proof that the file exists. The row falls back to the
 * `outputPath` rule, exactly as reconciliation already documented. Choosing the
 * optimistic `true` here instead would let a test-only construction claim
 * "already downloaded" for a URI nobody checked, which is precisely the
 * dishonesty Slice B removes.
 *
 * Nothing here deletes, renames or migrates anything: it only answers
 * "does a real, non-empty output exist?".
 *
 * Threading: [uriResolves] performs Binder IPC (`ContentResolver.query`,
 * `openFileDescriptor`). Callers that run on the main dispatcher must wrap the
 * call — `DownloadRepository.outputPresent` does, in `Dispatchers.IO`, because
 * that seam is called from the ViewModel's scope.
 */
class EpisodeOutputVerifier(
    private val contentResolver: ContentResolver? = null,
) {

    companion object {
        private const val TAG = "EpisodeOutputVerifier"
    }

    /**
     * §29 COMPLETED invariant — is the output this row names really there?
     *
     * @param outputUri persisted MediaStore/SAF `content://` reference, or null
     *   for a pre-export row.
     * @param outputPath persisted app-private file path, or null.
     */
    fun isPresent(outputUri: String?, outputPath: String?): Boolean {
        if (!outputUri.isNullOrEmpty() && contentResolver != null) {
            return uriResolves(outputUri)
        }
        if (outputPath.isNullOrEmpty()) return false
        val output = File(outputPath)
        return output.exists() && output.length() > 0L
    }

    /**
     * Resolve a persisted `content://` URI through the injected resolver:
     * query succeeds and reported size > 0, falling back to an openable file
     * descriptor stat size when the `SIZE` column is unavailable.
     *
     * Any resolver failure (deleted by the user, `SecurityException`, parse
     * error) counts as "does not resolve" — an unresolvable export is a missing
     * output, never a crash and never a silent success.
     */
    private fun uriResolves(uriString: String): Boolean {
        val resolver = contentResolver ?: return false
        return try {
            val uri = uriString.toUri()
            var size = -1L
            resolver
                .query(uri, arrayOf(android.provider.MediaStore.MediaColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        size = cursor.getLong(0)
                    }
                }
            if (size < 0) {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = android.system.Os.fstat(pfd.fileDescriptor).st_size
                }
            }
            size > 0L
        } catch (e: Exception) {
            AppLog.w(TAG, "outputUri does not resolve: ${e.message}")
            false
        }
    }
}
