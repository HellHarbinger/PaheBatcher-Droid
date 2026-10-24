package com.pahebatcher.app.data.export

import android.net.Uri
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.flow.first

/**
 * The export-destination selector every export
 * goes through — SAF tree when configured AND validated, MediaStore default
 * `Movies/PaheBatcher/` otherwise.
 *
 * Selection rules:
 *  - no tree configured → MediaStore default (92.2);
 *  - tree configured but [OutputTreeState.Unusable] (revoked/missing/moved,
 *    92.3/103.4) → MediaStore default for future exports; the Settings state
 *    surfaces the reason, the download pipeline is never blocked (92.3);
 *  - `output_tree_rename_ok = false` (104.4) → straight to the MediaStore
 *    default without re-probing the provider;
 *  - SAF attempt returning [SafExportResult.RenameUnsupported] → this export
 *    runs through the MediaStore default in the same call (104.4);
 *  - every other SAF outcome is returned as-is.
 *
 * The sweep is always the MediaStore `IS_PENDING` sweep: the SAF path has no
 * pending state — its `.pb.part` cleanup runs at export start (104.3).
 */
class ExportDestinationRouter(
    private val outputTrees: OutputTreeRepository,
    private val config: AppConfig,
    private val safExporter: SafExporter,
    private val mediaStoreExporter: MediaStoreExporter,
) : EpisodeExporter {

    companion object {
        private const val TAG = "ExportDestinationRouter"
    }

    override suspend fun export(episode: EpisodeDownloadEntity): ExportOutcome {
        val treeUri = config.outputTreeUri.first()
        if (!treeUri.isNullOrBlank()) {
            val renameOk = config.outputTreeRenameOk.first()
            if (renameOk) {
                when (val state = outputTrees.state()) {
                    is OutputTreeState.Valid -> {
                        when (val result = safExporter.export(episode, state.treeUri)) {
                            is SafExportResult.Completed -> return result.outcome
                            is SafExportResult.RenameUnsupported -> {
                                // 104.4: the flag is already persisted false; fall
                                // through so THIS export still completes via X1–X6.
                            }
                        }
                    }
                    is OutputTreeState.Unusable -> {
                        AppLog.w(
                            TAG,
                            "Output tree unusable (${state.reason}) — MediaStore default",
                        )
                    }
                    is OutputTreeState.NotConfigured -> Unit // unreachable (treeUri set)
                }
            }
        }
        return mediaStoreExporter.export(episode)
    }

    override suspend fun sweepStalePending(olderThanEpochMs: Long): Int =
        mediaStoreExporter.sweepStalePending(olderThanEpochMs)

    /**
     * §3.1 (DECISIONS 157): the destination probe mirrors [export]'s routing.
     *
     * Only the MediaStore default is probed — the destination the reported
     * re-download defect lives in. A configured-and-valid SAF tree is deliberately
     * NOT probed (walking a user-owned SAF tree is outside this gate): returning
     * `null` there keeps today's behaviour exactly — the submission gate admits
     * and the reconciler demotes — so a SAF user sees no change, while the common
     * MediaStore destination becomes destination-aware. The deliberate asymmetry:
     * a `RenameUnsupported` SAF export falls through to MediaStore writes, yet a
     * probe during an ACTIVE SAF tree still returns `null` without consulting
     * MediaStore — a conservative (admit-only) divergence, never a false match.
     */
    override suspend fun destinationOutput(
        episodeNumber: Float,
        title: String,
        audio: String,
        animeTitle: String?,
        sessionId: String,
    ): Uri? {
        val treeUri = config.outputTreeUri.first()
        val safActive = !treeUri.isNullOrBlank() &&
            config.outputTreeRenameOk.first() &&
            outputTrees.state() is OutputTreeState.Valid
        if (safActive) return null
        return mediaStoreExporter.destinationOutput(episodeNumber, title, audio, animeTitle, sessionId)
    }
}
