package com.pahebatcher.app.data.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException

/**
 * `Replace file` (DECISIONS 152) — the second half of a replacement: retire the
 * export that was replaced, once — and only once — the NEW output is committed.
 *
 * Why this exists as its own type:
 *
 * * **Ordering is the safety property.** The exporters stage the new copy
 *   first, verify it, persist its URI and make it visible. Only then is the
 *   previous item removed. Deleting it earlier would open a window in which
 *   the user has no file at all, which is exactly what "preserve the old export
 *   until the new one is verified" forbids.
 * * **It is storage-provider agnostic.** The prior reference may be a
 *   MediaStore URI or a SAF tree document — whichever destination the episode
 *   was exported to when it was confirmed. Both are retired through their own
 *   native deletion primitive; neither is ever guessed at from a path.
 * * **It never guesses which file is which.** The reference to remove is the
 *   persisted `replacePriorUri`, not "the row whose name happens to match".
 *   `outputUri` may already have been switched to the NEW item (that is the
 *   normal commit order), and a process killed in that window must still be
 *   able to find — and remove — the file it was replacing.
 *
 * Failure behaviour is deliberately conservative: a retirement that does not
 * succeed keeps `replacePriorUri`, so the next export pass (the Worker's, or
 * startup reconciliation's) tries again. Leaving a second file behind is a
 * recoverable blemish; deleting the only surviving copy is not.
 */
class PriorOutputRetirement(
    private val contentResolver: ContentResolver,
    private val episodeDao: EpisodeDownloadDao,
) {

    companion object {
        private const val TAG = "PriorOutputRetirement"
    }

    /**
     * Remove episode [episode]'s replaced export if [outcome] proves the new
     * output is committed.
     *
     * Rules, in order:
     *
     *  1. no recorded prior → nothing to do (app-private prior: the Worker's
     *     rename already replaced it in place);
     *  2. [ExportOutcome.Failed] → the new output is NOT committed, so the old
     *     export stays and the reference stays with it;
     *  3. the outcome's URI IS the prior (or there is none) → nothing new is
     *     referenced yet, so removing the prior would leave the row pointing at
     *     a file that no longer exists;
     *  4. another Room row still references the prior → that episode's output,
     *     never this one's to delete (the same cross-reference guard the
     *     exporters apply before adopting a candidate);
     *  5. otherwise delete natively and clear the reference — or, on failure,
     *     keep the reference so a later pass retries.
     *
     * Idempotent: a second call after a successful retirement sees
     * `replacePriorUri == null` and does nothing.
     *
     * @return true when the prior reference is now cleared.
     */
    suspend fun retire(episode: EpisodeDownloadEntity, outcome: ExportOutcome): Boolean {
        val prior = episode.replacePriorUri ?: return true
        if (outcome is ExportOutcome.Failed) return false
        val current = when (outcome) {
            is ExportOutcome.Exported -> outcome.uri
            is ExportOutcome.AlreadyExported -> outcome.uri
            is ExportOutcome.Failed -> return false
        }
        if (current.isEmpty() || current == prior) return false
        if (episodeDao.otherReferenceAudios(prior, episode.episodeId).isNotEmpty()) {
            AppLog.w(
                TAG,
                "Prior output for ${episode.episodeId} is still referenced by another row — left in place",
            )
            return false
        }

        val deleted = try {
            deleteStored(prior.toUri())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not retire the prior export for ${episode.episodeId}: ${e.message}")
            false
        }
        if (!deleted) return false
        episodeDao.clearReplacePriorUri(episode.episodeId)
        AppLog.i(TAG, "Retired the prior export for ${episode.episodeId} after the replacement committed")
        return true
    }

    /**
     * Delete one stored item through the primitive its own provider requires:
     * a MediaStore row through `ContentResolver.delete`, a SAF tree document
     * through `DocumentsContract.deleteDocument`. A MediaStore delete that
     * reports `0` rows is treated as already retired (the goal state), while
     * anything else failing keeps the reference so the attempt can repeat.
     */
    private fun deleteStored(uri: Uri): Boolean {
        val authority = uri.authority ?: ""
        val isMediaStore = authority == MediaStore.AUTHORITY || authority.startsWith("media")
        return if (isMediaStore) {
            val rows = contentResolver.delete(uri, null, null)
            // 0 rows means the item is already absent — retired is retired,
            // whatever removed it first.
            rows != 0
        } else {
            try {
                DocumentsContract.deleteDocument(contentResolver, uri)
            } catch (e: FileNotFoundException) {
                // SAF reports a vanished document as not-found: the file this
                // reference pointed at is gone, which is the goal state.
                AppLog.d(TAG, "Prior SAF document already absent")
                true
            }
        }
    }
}
