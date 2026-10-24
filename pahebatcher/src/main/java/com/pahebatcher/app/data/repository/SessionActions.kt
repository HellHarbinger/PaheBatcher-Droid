package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.export.ExportOutcome
import com.pahebatcher.app.data.hls.SegmentFileStore
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** What [SessionActions.prepareSessionDelete] found and did (DECISIONS 94/106). */
data class SessionDeletePlan(
    val sessionId: String,
    /** Rows whose local MP4 export succeeded (or was already exported). */
    val exportedCount: Int,
    /**
     * Display names of local MP4s still present AFTER export-first — these are
     * exactly the files the confirmation must name explicitly before they may
     * be deleted (SPEC §20: never delete completed user media silently).
     */
    val unexportedNames: List<String>,
    /** Temporary bytes the delete will also remove (DECISIONS 107). */
    val temporaryBytes: Long,
)

/** Result of [SessionActions.prepareSessionDelete] — refusal is never silent. */
sealed interface SessionDeletePreparation {
    data class Ready(val plan: SessionDeletePlan) : SessionDeletePreparation
    data class Refused(val reason: String) : SessionDeletePreparation
}

/**
 * The aggregate session actions behind the
 * Sessions screen — Cancel, Retry, Delete session, Delete temporary cache and
 * Clear all cache — implemented over the EXISTING per-row pipeline and the
 * EXISTING export surface. The UI calls only this; it never touches Room,
 * the filesystem or the exporter itself (AGENTS §4).
 *
 * Contracts honored here:
 *
 *  - **Cancel** aggregates [cancelRow] (production: `DownloadRepository.cancel`)
 *    over the session's non-terminal rows only — rows become CANCELLED, their
 *    unique work is cancelled, segments are retained (94).
 *  - **Retry** aggregates [retryRow] (production: `DownloadRepository.reEnqueue`)
 *    over FAILED/CANCELLED rows — per-row eligibility stays the repository's
 *    own guard (94).
 *  - **Pause** aggregates [pauseRow] (production: `DownloadRepository.pause`) over
 *    the rows in `EpisodeDownloadEntity.PAUSABLE_STATES`, and **Resume** aggregates
 *    [resumeRow] (production: `DownloadRepository.resume`) over `PAUSED` rows.
 *    Both are the final SPEC §20 gap: DECISIONS 94.2 ("the session menu has no
 *    Pause") described the Python reference, where none exists — SPEC requires
 *    one, so the Android session manager has a real pause/resume pair whose
 *    counts are re-derived from the same persisted rows the buttons gate on.
 *  - **Delete session** is terminal-only (106.1), export-first: every local
 *    MP4 is offered to the existing byte-verified exporter before deletion;
 *    whatever remains locally is returned as explicit names for the
 *    confirmation. Removal order is filesystem-first (segment directories,
 *    then confirmed local MP4s), then the Room session row — the cascade
 *    deletes the episode rows (106.3), so a crash mid-delete repeats safely.
 *    Exported MediaStore/SAF media is never referenced by this path (92.5).
 *  - **Delete temporary cache** removes only the temporary artifact set via
 *    [SegmentFileStore.cleanup] (output-preserving) and is refused while the
 *    session has any non-terminal episode (94).
 *  - **Clear all cache** is refused while ANY episode anywhere is
 *    non-terminal (106.2); it never touches Room rows (documented divergence
 *    from Python's `rmtree` of the whole cache — LIMITATIONS §34.3).
 *  - **Cache size** sums only `*.ts`/`*.tmp`/`concat.txt` under each session's
 *    episodes' segment directories — the same set the clear actions remove
 *    (107.1/107.5); `*.mp4` and MediaStore/SAF media are never counted.
 */
class SessionActions(
    private val sessionRepository: SessionRepository,
    private val episodeDao: EpisodeDownloadDao,
    /** Production: `DownloadRepository::cancel`. */
    private val cancelRow: suspend (String) -> Unit,
    /** Production: `DownloadRepository::reEnqueue`. */
    private val retryRow: suspend (String) -> Unit,
    /** Production: `DownloadRepository::pause` — true only when it really paused the row. */
    private val pauseRow: suspend (String) -> Boolean,
    /** Production: `DownloadRepository::resume` — true only when the row really left PAUSED. */
    private val resumeRow: suspend (String) -> Boolean,
    private val exporter: EpisodeExporter,
) {

    /** All session rows, newest first (the screen's list source). */
    fun observeSessions(): Flow<List<DownloadSessionEntity>> = sessionRepository.observe()

    /** Every episode row (stats + gates derive from the same emission). */
    fun observeEpisodes(): Flow<List<EpisodeDownloadEntity>> = episodeDao.observeAllOrdered()

    /** Episode rows of one session. */
    suspend fun episodes(sessionId: String): List<EpisodeDownloadEntity> =
        episodeDao.getBySessionId(sessionId).first()

    // ------------------------------------------------------------------
    // Cancel / Retry (94)
    // ------------------------------------------------------------------

    /**
     * Cancel every non-terminal row of [sessionId].
     *
     * @return how many rows were cancelled (0 when the session is already
     *   fully terminal — a no-op, never an error).
     */
    suspend fun cancelSession(sessionId: String): Int {
        val targets = episodes(sessionId).filterNot { it.isTerminal() }
        targets.forEach { cancelRow(it.episodeId) }
        return targets.size
    }

    /**
     * Re-enqueue every FAILED/CANCELLED row of [sessionId] through the
     * existing resolve→queue pipeline (per-row eligibility rules unchanged).
     *
     * @return how many rows were handed to the pipeline.
     */
    suspend fun retrySession(sessionId: String): Int {
        val targets = episodes(sessionId).filter {
            it.state == EpisodeDownloadEntity.STATE_FAILED ||
                it.state == EpisodeDownloadEntity.STATE_CANCELLED
        }
        targets.forEach { retryRow(it.episodeId) }
        return targets.size
    }

    /**
     * SPEC §20 **Pause** — pause every row of [sessionId] that can be paused
     * (`EpisodeDownloadEntity.PAUSABLE_STATES`: QUEUED/RESOLVING/DOWNLOADING).
     *
     * Rows the repository refuses (it re-derives the predicate and CAS-claims the
     * state, so a row that finished in the meantime is left alone) are NOT
     * counted: the returned number is the truth of what happened, not a request
     * count. `ASSEMBLING` and terminal rows are skipped by the predicate itself —
     * muxing cannot be paused halfway without risking a partial output.
     *
     * @return how many rows really entered `PAUSED`.
     */
    suspend fun pauseSession(sessionId: String): Int {
        val targets = episodes(sessionId).filter { it.isPausable() }
        var paused = 0
        for (target in targets) {
            if (pauseRow(target.episodeId)) paused++
        }
        return paused
    }

    /**
     * SPEC §20 **Resume** — leave `PAUSED` for every paused row of [sessionId]
     * (the counterpart of [pauseSession]; DECISIONS 94's Resume still *navigates*
     * and is a different control). Each row goes back through
     * `DownloadRepository.resume`: `PAUSED → QUEUED` with the existing unique
     * Worker when a stream is persisted, otherwise a re-resolution.
     *
     * @return how many rows really left `PAUSED`.
     */
    suspend fun resumeSession(sessionId: String): Int {
        val targets = episodes(sessionId).filter {
            it.state == EpisodeDownloadEntity.STATE_PAUSED
        }
        var resumed = 0
        for (target in targets) {
            if (resumeRow(target.episodeId)) resumed++
        }
        return resumed
    }

    // ------------------------------------------------------------------
    // Gates (106)
    // ------------------------------------------------------------------

    /** True when every row of the session is COMPLETED/FAILED/CANCELLED. */
    suspend fun isSessionAllTerminal(sessionId: String): Boolean =
        episodes(sessionId).all { it.isTerminal() }

    /** True when ANY episode row anywhere is non-terminal (the 106.2 gate). */
    suspend fun hasNonTerminalAnywhere(): Boolean =
        episodeDao.observeAllOrdered().first().any { !it.isTerminal() }

    // ------------------------------------------------------------------
    // Cache size (107)
    // ------------------------------------------------------------------

    /**
     * Temporary bytes per session — one pass over the Room-provided
     * [EpisodeDownloadEntity.segmentDir] paths. Duplicate directories are
     * counted once; rows without a segment directory contribute nothing.
     */
    suspend fun cacheSizesBySession(): Map<String, Long> = withContext(Dispatchers.IO) {
        val rows = episodeDao.observeAllOrdered().first()
        rows
            .filter { !it.segmentDir.isNullOrBlank() }
            .groupBy({ it.sessionId }, { it.segmentDir!! })
            .mapValues { (_, dirs) -> dirs.distinct().sumOf { temporaryBytesIn(File(it)) } }
    }

    /** Sum of temporary artifacts in [dir] — never MP4s (107.2). */
    private fun temporaryBytesIn(dir: File): Long =
        dir.listFiles()
            ?.filter { it.isFile && SegmentFileStore.isTemporaryArtifact(it.name) }
            ?.sumOf { it.length() }
            ?: 0L

    // ------------------------------------------------------------------
    // Delete session (94 / 106.1 / 106.3)
    // ------------------------------------------------------------------

    /**
     * Prepare a session deletion: enforce the terminal-only gate (106.1),
     * run export-first over every local MP4 through the byte-verified
     * [exporter], and report what still remains locally.
     *
     * Nothing is deleted here — the returned names are for the explicit
     * confirmation SPEC §20 requires.
     */
    suspend fun prepareSessionDelete(sessionId: String): SessionDeletePreparation = withContext(Dispatchers.IO) {
        val rows = episodes(sessionId)
        val active = rows.count { !it.isTerminal() }
        if (active > 0) {
            return@withContext SessionDeletePreparation.Refused("Cancel first — $active download(s) in progress")
        }

        var exported = 0
        for (row in rows) {
            val local = row.outputPath?.takeIf { it.isNotBlank() }?.let(::File)
            if (local == null || !local.exists()) continue
            when (exporter.export(row)) {
                is ExportOutcome.Exported,
                is ExportOutcome.AlreadyExported,
                -> exported++
                is ExportOutcome.Failed -> Unit // kept locally → named below
            }
        }

        SessionDeletePreparation.Ready(
            SessionDeletePlan(
                sessionId = sessionId,
                exportedCount = exported,
                unexportedNames = localMediaNames(rows),
                temporaryBytes = temporaryBytesOf(rows),
            ),
        )
    }

    /**
     * Execute a confirmed session deletion (106.3 order): terminal gate
     * re-checked → segment directories → confirmed local MP4s → session row
     * (cascade). Repeatable: after a crash the filesystem step no-ops and the
     * cascade completes.
     *
     * @param deleteUnexportedLocalMedia must be true when (and only when) the
     *   confirmation explicitly named the still-local MP4s; false refuses while
     *   such files exist (SPEC §20).
     * @return false when the gate refused (non-terminal rows, or unexported
     *   media without confirmation); true when the deletion completed (also
     *   when repeating an already-completed deletion — a safe no-op).
     */
    suspend fun deleteSession(sessionId: String, deleteUnexportedLocalMedia: Boolean): Boolean = withContext(Dispatchers.IO) {
        val rows = episodes(sessionId)
        if (rows.any { !it.isTerminal() }) return@withContext false
        if (!deleteUnexportedLocalMedia && localMediaNames(rows).isNotEmpty()) return@withContext false

        // Filesystem first (106.3): directories before the Room row, so a
        // crash mid-delete leaves a repeatable state, never orphan files.
        for (row in rows) {
            row.segmentDir?.takeIf { it.isNotBlank() }?.let { File(it).deleteRecursively() }
            row.outputPath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        }
        sessionRepository.remove(sessionId)
        true
    }

    // ------------------------------------------------------------------
    // Temporary cache (94 / 106.2 / 107.5)
    // ------------------------------------------------------------------

    /**
     * Remove the temporary artifact set of ONE session's segment directories
     * (assembled MP4s are preserved by [SegmentFileStore.cleanup]).
     *
     * @return false when the session has any non-terminal episode (deleting
     *   segments mid-flight would silently break resume); true when the
     *   cleanup ran (or there was nothing to remove).
     */
    suspend fun deleteTemporaryCache(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isSessionAllTerminal(sessionId)) return@withContext false
        episodes(sessionId)
            .mapNotNull { it.segmentDir?.takeIf { s -> s.isNotBlank() } }
            .distinct()
            .forEach { SegmentFileStore(File(it)).cleanup() }
        true
    }

    /**
     * Global temp-only cleanup across every episode's segment directory.
     * Room session/episode rows are retained (documented divergence, §34.3).
     *
     * @return false while ANY row anywhere is non-terminal (106.2); true when
     *   the cleanup ran.
     */
    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        if (hasNonTerminalAnywhere()) return@withContext false
        episodeDao.observeAllOrdered().first()
            .mapNotNull { it.segmentDir?.takeIf { s -> s.isNotBlank() } }
            .distinct()
            .forEach { SegmentFileStore(File(it)).cleanup() }
        true
    }

    // ------------------------------------------------------------------
    // Local-media inventory (SPEC §20 confirmation naming)
    // ------------------------------------------------------------------

    /**
     * Display names of MP4s that still exist locally for [rows]: the row's
     * `outputPath` file if present, plus any `*.mp4` inside its segment
     * directory (the assembled output lives there until export X6). Exported
     * MediaStore/SAF destinations are never queried here.
     */
    private fun localMediaNames(rows: List<EpisodeDownloadEntity>): List<String> {
        val names = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        for (row in rows) {
            row.outputPath?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.isFile && it.name.endsWith(".mp4", ignoreCase = true) && it.exists() }
                ?.let { names += it.name }
            row.segmentDir?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".mp4", ignoreCase = true) }
                ?.forEach { names += it.name }
        }
        return names.toList()
    }

    private fun temporaryBytesOf(rows: List<EpisodeDownloadEntity>): Long =
        rows.mapNotNull { it.segmentDir?.takeIf { s -> s.isNotBlank() } }
            .distinct()
            .sumOf { temporaryBytesIn(File(it)) }
}
