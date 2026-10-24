package com.pahebatcher.app.data.download

import android.content.ContentResolver
import android.content.Context
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogUrlSanitizer
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.playback.PlaybackPlaylist
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.download.ReplacementOutcome
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.worker.DownloadWorker
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Repository for foreground download orchestration.
 *
 *
 * Responsibilities:
 * - Enqueue/cancel Workers
 * - Manage episode state transitions
 * - Persist stream info after resolution
 * - User-triggered re-enqueue
 */
class DownloadRepository(
    private val context: Context,
    private val dao: EpisodeDownloadDao,
    private val sessionDao: DownloadSessionDao,
    /**
     * 11.5E-7 (§24.1): the foreground stream resolver used by [reEnqueue] to run
     * a FRESH resolution for a retryable episode — the Worker may never resolve
     * (`DownloadWorker` KDoc), so retry must re-enter the resolution path from
     * here, exactly as Python re-resolves every attempt from the persistent
     * `play_url` (`downloader.py:233-275`).
     *
     * Nullable with the SAME precedent as [DownloadStateReconciler]'s optional
     * `contentResolver`/`exporter`: host-constructed tests keep compiling,
     * production (AppContainer) always wires the real `StreamResolverImpl` over
     * the app-scoped WebView session. Without a resolver [reEnqueue] is a logged
     * no-op — it never mutates state or fakes a resolution.
     */
    private val streamResolver: StreamResolver? = null,
    /**
     * The CURRENT `Wi-Fi-only downloads` setting,
     * read fresh inside every [enqueueWorker] call — a suspend read of the
     * persisted DataStore value in production (`{ appConfig.wifiOnly.first() }`
     * via AppContainer). Default OFF (the setting's own default) so every
     * existing host-constructed test enqueues exactly as before. A non-suspend
     * caller can never forget the read: [enqueueWorker] itself is suspend and
     * performs it.
     */
    private val wifiOnlyProvider: suspend () -> Boolean = { false },
    // the CURRENT `Auto-resume downloads` setting,
    // read fresh at enqueue time. Default OFF = the setting's own shipped
    // default, so existing call sites keep today's behavior.
    private val autoResumeProvider: suspend () -> Boolean = { false },
    /**
     * Slice B (DECISIONS 146): constructor-injected resolver used to VERIFY a
     * persisted `outputUri` before any caller is told the episode is already
     * downloaded — the same §30.1 precedent as
     * [DownloadStateReconciler]'s optional `contentResolver`.
     *
     * APPENDED LAST on purpose: 20+ host-constructed tests build this class
     * positionally (`DownloadRepository(context, dao, sessionDao)` and, for the
     * retry tests, a positional `streamResolver`), so inserting it earlier
     * would break every one of them for no reason (review finding M-3).
     *
     * Nullable with the same documented meaning as the reconciler's: `null`
     * (every existing host-constructed test) falls back to the `outputPath`
     * rule, because a `content://` reference nobody can resolve is never proof
     * that a file exists. Production always passes the real resolver through
     * `AppContainer`, so the optimistic "a non-blank URI must be fine" rule this
     * replaces is gone entirely.
     */
    private val contentResolver: ContentResolver? = null,
    /**
     * §3.1 (DECISIONS 157): the destination probe that makes the submission gate
     * DESTINATION-aware, not only row-aware — the configured exporter's
     * [EpisodeExporter.destinationOutput].
     *
     * APPENDED LAST on purpose, exactly like [contentResolver]: 20+ host-constructed
     * tests build this class positionally, so inserting earlier would break them for
     * no reason. `null` (every existing host-constructed test) disables the probe and
     * keeps today's row-only behaviour; production (AppContainer) always wires the
     * real router, so a surviving export is never silently re-downloaded over.
     */
    private val destinationProbe: EpisodeExporter? = null,
) : EpisodeDownloadQueue {

    companion object {
        private const val TAG = "DownloadRepository"
    }

    /**
     * Slice B (DECISIONS 146): the ONE §29 output-presence rule, shared with
     * [DownloadStateReconciler] so the two consumers can never contradict each
     * other about the same row (review finding M-1).
     */
    private val outputVerifier = EpisodeOutputVerifier(contentResolver)

    /**
     * Create (or refresh) the parent download session row for [sessionId].
     *
     * `episode_downloads.sessionId` is a FOREIGN KEY to `download_sessions` and
     * Room opens the connection with `PRAGMA foreign_keys = ON`, so this row must
     * exist before the first [enqueue] of any of its episodes. Until this phase no
     * production code inserted it — the download tables were only ever seeded by
     * tests — so this is the step that makes the details entry's episode
     * hand-off physically possible.
     *
     * One row per anime session (the Android equivalent of Python's per-anime
     * session metadata file), created on first download and thereafter REFRESHED
     * while preserving the original `createdAt` and the existing `state` rather
     * than silently resetting a live session back to
     * [DownloadSessionEntity.STATE_CREATED].
     *
     * The refresh deliberately avoids [DownloadSessionDao.upsert]: that is
     * `INSERT OR REPLACE`, which deletes the conflicting parent row before
     * re-inserting it, and that delete fires `ON DELETE CASCADE` on
     * `episode_downloads`. Queueing a second episode through it would erase the
     * row of an episode that is already downloading — so an existing row is
     * updated in place instead ([DownloadSessionDao.update]).
     *
     * [DownloadSessionEntity.outputDir] and [DownloadSessionEntity.cacheDir] both
     * name the real app-private directory this repository writes into —
     * `filesDir/downloads/<episodeId>` per [enqueue]; a distinct MediaStore output
     * location only exists once media export does.
     *
     * @param sessionId Anime session UUID; also the episode rows' foreign key.
     * @param title Anime title as the scan reported it.
     * @param host AnimePahe host the scan used.
     * @param quality Quality the stream resolution was requested with.
     * @param audioLang Audio track of the episode being queued (`jpn`/`eng`).
     */
    private suspend fun ensureSession(
        sessionId: String,
        title: String,
        host: String,
        quality: Int,
        audioLang: String,
    ) {
        val now = System.currentTimeMillis()
        val existing = sessionDao.getById(sessionId)
        val downloadsRoot = File(context.filesDir, "downloads").absolutePath
        val session = DownloadSessionEntity(
            sessionId = sessionId,
            title = title,
            animeSession = sessionId,
            host = host,
            quality = quality,
            audioLang = audioLang,
            outputDir = downloadsRoot,
            cacheDir = downloadsRoot,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            state = existing?.state ?: DownloadSessionEntity.STATE_CREATED,
        )
        if (existing == null) {
            // Slice B (review finding M-8): INSERT OR IGNORE, never REPLACE.
            // Two concurrent first-ever submissions for one anime both read
            // `existing == null`; a second `upsert` (INSERT OR REPLACE) would
            // DELETE this parent row and fire ON DELETE CASCADE on the episode
            // row the other caller just inserted. Exactly one caller creates the
            // row; every other caller observes -1 and refreshes in place below.
            if (sessionDao.insertIfAbsent(session) == -1L) {
                sessionDao.update(session)
            }
        } else {
            // In place — never REPLACE a live parent row (see the KDoc above).
            sessionDao.update(session)
        }
        AppLog.d(
            TAG,
            "Download session $sessionId " + (if (existing == null) "created" else "refreshed")
        )
    }

    /**
     * Stable id of ONE episode's download row: `<anime session>_<episode>_<audio>`.
     *
     * Unique per (anime, episode number, audio) — the same key Python groups its
     * segment directories by (`session` + `Ep_<num>_<AUDIO>`) — so a second call
     * for the same episode finds the existing row instead of duplicating it, and
     * the SUB and DUB track of one episode stay separate downloads. Contains only
     * characters that survive a filesystem path.
     *
     * The format itself now lives in [EpisodeId.build] so the streaming
     * navigation path derives the IDENTICAL key without duplicating the string
     * pattern. This method is a pure delegation — same inputs, same output.
     */
    private fun episodeDownloadId(animeSession: String, episode: EpisodeInfo): String =
        EpisodeId.build(animeSession, episode)

    /**
     * The one entry into this pipeline (11.5E-4) — see [EpisodeDownloadQueue].
     *
     * Three operations in the order the Phase 10 contract requires:
     *
     * 1. [ensureSession] — the parent `download_sessions` row (Room runs with
     *    `PRAGMA foreign_keys = ON`, so the episode row would otherwise violate
     *    its foreign key);
     * 2. [enqueue] — the episode row (`QUEUED` → `RESOLVING`) plus its segment
     *    directory;
     * 3. [resolveComplete] — the real `m3u8Url` / User-Agent / Referer the
     *    resolver produced, then the unique `DownloadWorker` work.
     *
     * Nothing here resolves, fetches or parses: the stream was already resolved
     * in the WebView session, so this is persistence + work hand-off only.
     * Repeating it for an episode that is already queued never starts a second
     * download of the same episode.
     *
     * **Slice B (DECISIONS 146) — the decision is REPORTED, not assumed.**
     *
     * This method used to `return` silently for four whole classes of request
     * (`DOWNLOADING`, `ASSEMBLING`, `PAUSED`, `COMPLETED`-with-output) while
     * every caller still reported success, so the user was told "In the download
     * queue" for an episode the pipeline had refused to touch. It now returns
     * the [EnqueueOutcome] the row-state mapping in that type's KDoc defines.
     *
     * The MUTATION behavior is unchanged from the 11.5E-6 contract, including
     * deliberately:
     *
     * * `DOWNLOADING`/`ASSEMBLING` rows are left untouched — flipping a live row
     *   back to `QUEUED` would understate `countActive()` and let the soft
     *   `maxParallel` gate admit an extra episode while a Worker owns it.
     * * `COMPLETED` rows with a present output are left untouched — a fresh
     *   `REPLACE` would erase `outputPath`/`outputUri`/`retryCount` (§17.1:
     *   "would clobber a verified output"; Python likewise skips an episode
     *   whose output already exists — `downloader.py:60-62` `run()`,
     *   `154-165`/`224-230` `_find_existing`).
     * * `QUEUED`/`RESOLVING` rows are NOT suppressed: [resolveComplete] is the
     *   recovery path that repopulates a stream-info-less row (the details-screen
     *   escape from a zombie row, LIMITATIONS §24 finding 2) and the only way a
     *   `reEnqueue` claim (which CASes into `RESOLVING` first) can hand off.
     * * `FAILED`/`CANCELLED` rows (and `COMPLETED` rows whose output is gone)
     *   proceed exactly as before — the legitimate re-queue cases.
     *
     * Two honesty guarantees this slice adds on top of that (review findings
     * H-1 and H-2):
     *
     * * A `QUEUED` row WITHOUT a stream is reported [EnqueueOutcome.Accepted],
     *   not `AlreadyActive`: nothing would ever download it until this call
     *   repopulates the stream and registers the Worker, so calling it "already
     *   active" would understate work the app just did.
     * * [EnqueueOutcome.Accepted] is only returned when [resolveComplete]'s CAS
     *   really won. A Cancel, Pause or reconciler move landing between the row
     *   write and the hand-off is reported as the state it produced, never as
     *   success.
     *
     * @return what THIS call did — see [EnqueueOutcome].
     * @throws Exception if persistence or work enqueueing genuinely fails.
     */
    override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
        val episode = submission.episode
        val episodeId = episodeDownloadId(submission.animeSession, episode)

        // Classify BEFORE any mutation: a suppressed submission must cost
        // neither a session write (below) nor a resolver round-trip. The same
        // check runs again on the lost-creation-claim path, where `existing` was
        // null but the winner's row now exists.
        val existing = dao.getById(episodeId)
        if (existing != null) {
            suppressedOutcome(existing)?.let {
                AppLog.d(TAG, "Suppressed re-submission of $episodeId (outcome=$it)")
                return it
            }
        }

        // §3.1 (DECISIONS 157): the DESTINATION is part of the episode's identity
        // too. A re-submission with NO row but a surviving export (the app-private
        // state was destroyed while the MediaStore/SAF file outlived it) is the
        // same "already downloaded" no-op as a COMPLETED row with a present output.
        // Without this the re-download runs, the exporter adopts the surviving file
        // and the freshly assembled bytes are discarded — a COMPLETED row whose new
        // output was committed nowhere. Probed BEFORE any mutation, so a suppressed
        // submission still costs neither a session write nor a resolver round-trip.
        if (existing == null &&
            destinationSurvives(
                episodeNumber = episode.number,
                title = downloadTitle(episode, submission.stream),
                audio = episode.audio,
                sessionId = submission.animeSession,
                animeTitleFallback = submission.animeTitle,
            )
        ) {
            AppLog.d(TAG, "Suppressed re-submission of $episodeId (destination survives)")
            return EnqueueOutcome.AlreadyDownloaded
        }

        ensureSession(
            sessionId = submission.animeSession,
            title = submission.animeTitle,
            host = submission.host,
            quality = submission.quality,
            audioLang = episode.audio,
        )

        val entity = EpisodeDownloadEntity(
            episodeId = episodeId,
            sessionId = submission.animeSession,
            episodeNumber = episode.number,
            episodeSession = episode.session,
            title = downloadTitle(episode, submission.stream),
            fansub = episode.fansub,
            audio = episode.audio,
            playUrl = episode.playUrl,
            state = EpisodeDownloadEntity.STATE_QUEUED,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        // Classify and claim, in one flow: an observed row is classified first,
        // and a lost creation claim re-enters the SAME classification as the row
        // the winner actually created (review finding M-7) — never a flat
        // "already active", which would misreport a terminal row.
        val observed = when {
            existing != null -> existing

            createRow(entity) -> null

            else -> dao.getById(episodeId) ?: run {
                // The winner's row vanished again in the window: retry the claim
                // once, then stop rather than loop. What is reported when even
                // that fails is DERIVED, not blanket (DECISIONS 146.5: "`AlreadyActive`
                // is never returned as a blanket answer to an unknown race") —
                // that value promises a row the pipeline owns and will deliver,
                // and here there provably is none. `outcomeOf` re-reads the row:
                // still absent → `Refused` (nothing was queued, nobody owns it),
                // present again → its own real state. The F1 correction at
                // [outcomeOf] made the same change for the terminal-claim path.
                if (createRow(entity)) null else return outcomeOf(dao.getById(episodeId))
            }
        }

        val outcomeBeforeHandoff = if (observed == null) {
            EnqueueOutcome.Accepted
        } else {
            suppressedOutcome(observed)?.let {
                AppLog.d(TAG, "Suppressed re-submission of $episodeId (outcome=$it)")
                return it
            }
            // An existing row. A TERMINAL one is claimed atomically against the
            // exact state this call classified it in (F2/F7): a refusal means a
            // concurrent submitter, Cancel or retry owns the episode now, so THIS
            // submission queued nothing and derives the truth rather than claiming
            // an `Accepted` it did not earn. A non-terminal row keeps its exact
            // 11.5E-6 no-op — `reEnqueue`'s pre-claim and the stream-less QUEUED
            // zombie repair (H-1) both rely on proceeding straight through it.
            val terminalState = if (observed.isTerminal()) observed.state else null
            if (!enqueue(entity, expectedTerminalState = terminalState)) {
                return outcomeOf(dao.getById(episodeId))
            }
            existingOutcome(observed)
        }

        val handedOff = resolveComplete(
            episodeId = episodeId,
            m3u8Url = submission.stream.url,
            userAgent = submission.stream.userAgent,
            referer = submission.stream.referer,
        )
        if (!handedOff) {
            // H-2: the CAS lost to a concurrent Cancel/Pause/reconciler move, so
            // NO Worker was enqueued. Reporting `Accepted` here is exactly the
            // lie this slice removes — derive the truth from the row.
            val current = dao.getById(episodeId)
            val outcome = outcomeOf(current)
            AppLog.w(TAG, "Hand-off of $episodeId was refused (row is now ${current?.state}); reporting $outcome")
            return outcome
        }
        return outcomeBeforeHandoff
    }

    /**
     * Slice B (review finding D): create a brand-new row ATOMICALLY.
     *
     * [EpisodeDownloadDao.insertIfAbsent] is `INSERT OR IGNORE`, so of any number
     * of concurrent first submissions for one episode+audio exactly one inserts;
     * every other caller gets `-1` back and must re-classify instead of claiming
     * a fresh enqueue.
     *
     * **The row is inserted straight into `RESOLVING`**, not into `QUEUED` and
     * then moved. That ordering is what makes the outcome honest under
     * concurrency: a loser may only ever observe `RESOLVING` (the winner is mid
     * claim) or `QUEUED` **with** a persisted stream ([resolveComplete] writes
     * the stream BEFORE its `RESOLVING → QUEUED` CAS, so `QUEUED` implies a
     * stream). Without this, there is a window in which the winner's row is
     * `QUEUED` with no stream yet — indistinguishable, to a concurrent reader,
     * from the reconciler's stream-less zombie — and the loser would report
     * `Accepted` for a download another submission was already starting.
     *
     * The end state is unchanged: [resolveComplete]'s primary CAS is
     * `RESOLVING → QUEUED`, so the row still lands on `QUEUED` with its Worker
     * registered, and an app killed in between leaves a `RESOLVING` row that
     * the reconciler already knows how to recover.
     *
     * @return true only when THIS call created the row.
     */
    private suspend fun createRow(entity: EpisodeDownloadEntity): Boolean {
        val episodeId = entity.episodeId
        val rowId = dao.insertIfAbsent(entity.copy(state = EpisodeDownloadEntity.STATE_RESOLVING))
        if (rowId == -1L) return false
        val segmentDir = File(context.filesDir, "downloads/$episodeId")
        if (!segmentDir.exists()) segmentDir.mkdirs()
        AppLog.d(TAG, "Enqueued episode $episodeId for resolution")
        return true
    }

    /**
     * Slice B: what an EXISTING row means for this submission — the same
     * mutation behavior as the 11.5E-6 guard, now carrying a verdict.
     *
     * Only reached for rows the [suppressedOutcome] guard let through, so every
     * branch here describes work that REALLY happens:
     *
     * * `FAILED`/`CANCELLED` → [EnqueueOutcome.Accepted]: a terminal row is
     *   legitimately re-queued, so this IS a fresh enqueue.
     * * `COMPLETED` whose output vanished → [EnqueueOutcome.OutputMissing] (the
     *   row really is being re-downloaded).
     * * `QUEUED` without a stream → [EnqueueOutcome.Accepted] (review finding
     *   H-1: this call is what makes it download at all).
     * * `QUEUED` WITH a stream and `RESOLVING` → [EnqueueOutcome.AlreadyActive]:
     *   a Worker may already own it; this call only refreshes the stream and
     *   re-asserts the KEEP request, which is not new work worth claiming.
     */
    private suspend fun existingOutcome(existing: EpisodeDownloadEntity): EnqueueOutcome =
        when (existing.state) {
            EpisodeDownloadEntity.STATE_FAILED,
            EpisodeDownloadEntity.STATE_CANCELLED,
            -> EnqueueOutcome.Accepted

            EpisodeDownloadEntity.STATE_COMPLETED -> EnqueueOutcome.OutputMissing

            EpisodeDownloadEntity.STATE_QUEUED ->
                if (existing.m3u8Url.isNullOrBlank()) {
                    EnqueueOutcome.Accepted
                } else {
                    EnqueueOutcome.AlreadyActive
                }

            else -> EnqueueOutcome.AlreadyActive
        }

    /**
     * Slice B (review finding H-2), corrected by review finding F1: the outcome
     * for a row whose hand-off was REFUSED — the CAS in [resolveComplete] lost to
     * a concurrent state change, so no Worker exists for this submission.
     *
     * Every branch is derived from the persisted row, never from what the caller
     * hoped for. A row concurrently moved to `FAILED`/`CANCELLED` — or deleted
     * outright — reports [EnqueueOutcome.Refused], the only value that describes
     * it honestly: nothing was queued, and nobody owns the episode. Before F1
     * this branch returned [EnqueueOutcome.AlreadyActive], whose own contract
     * promises a row "the pipeline owns", so a cancelled episode was announced as
     * "Already in the download queue" while holding no Worker at all.
     *
     * DECISIONS 146.3 and LIMITATIONS 45.1 recorded that imprecision as
     * deliberate on the grounds that no test could produce it;
     * `EnqueueOutcomeMappingTest.ClaimThenCancelDao` produces it deterministically.
     *
     * What this path guarantees is unchanged, and stronger than before: it can
     * never report a fresh enqueue, and it now never claims coverage it does not
     * provide.
     */
    private suspend fun outcomeOf(row: EpisodeDownloadEntity?): EnqueueOutcome = when {
        row == null -> EnqueueOutcome.Refused

        row.state == EpisodeDownloadEntity.STATE_COMPLETED ->
            if (outputPresent(row)) {
                EnqueueOutcome.AlreadyDownloaded
            } else {
                EnqueueOutcome.OutputMissing
            }

        row.state == EpisodeDownloadEntity.STATE_PAUSED -> EnqueueOutcome.AlreadyPaused

        row.state == EpisodeDownloadEntity.STATE_FAILED ||
            row.state == EpisodeDownloadEntity.STATE_CANCELLED
            -> EnqueueOutcome.Refused

        else -> EnqueueOutcome.AlreadyActive
    }

    /**
     * `EpisodeDownloadQueue.isEpisodeAlreadyHandled`: is this episode
     * already covered by the pipeline, so the caller must NOT resolve it again?
     *
     * The reference's `EpisodeDownloader.run` only skips an episode whose output
     * file exists (`downloader.py:60-62`), and its `_find_existing`
     * (`downloader.py:154-165`) is the same file test. Android's equivalent of
     * "the episode is on disk" is broader because output leaves the app
     * directory: it is present when the §29 rule confirms it — a non-empty local
     * MP4 on disk, or a persisted `outputUri` that the injected `ContentResolver`
     * really resolves (Slice B unified this with `DownloadStateReconciler`'s rule,
     * so a reference nobody can check is no longer taken on trust). On top of that, a row that is
     * already RESOLVING/RUNNING, or QUEUED with a persisted stream, is an episode
     * the pipeline will deliver on its own — re-resolving it would burn a
     * foreground WebView round-trip for nothing (AGENTS §10).
     *
     * Deliberately NOT covered: `FAILED`/`CANCELLED` rows (a retry must be able to
     * re-run them) and `QUEUED` rows that never received a stream (they are
     * waiting for exactly this foreground resolution) — those are the cases a
     * batch or a watchlist check is allowed to repair.
     *
     * `PAUSED` IS covered (SPEC §20): pausing is a user intent, and a batch or a
     * watchlist check must not silently start work the user stopped — re-resolving
     * one would also burn a foreground WebView round-trip for a row that cannot be
     * queued anyway (`resolveComplete`'s `RESOLVING/QUEUED → QUEUED` CAS refuses it).
     */
    override suspend fun isEpisodeAlreadyHandled(
        animeSession: String,
        episode: EpisodeInfo,
    ): Boolean {
        val row = dao.getById(episodeDownloadId(animeSession, episode)) ?: return false
        return when (row.state) {
            EpisodeDownloadEntity.STATE_RESOLVING,
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            EpisodeDownloadEntity.STATE_ASSEMBLING,
            -> true

            EpisodeDownloadEntity.STATE_PAUSED -> true
            EpisodeDownloadEntity.STATE_QUEUED -> !row.m3u8Url.isNullOrBlank()
            EpisodeDownloadEntity.STATE_COMPLETED -> outputPresent(row)
            else -> false
        }
    }

    /**
     * Does this episode's OUTPUT really exist?
     *
     * [isEpisodeAlreadyHandled] also returns `true` for rows that are still
     * working (`RESOLVING`/`DOWNLOADING`/`ASSEMBLING`) or queued with a stream —
     * those have produced no file yet, so they must not be written into a
     * watchlist entry's deleted-skip history. The reference backfills only from
     * `tmp_orch._find_existing(ep)` (`watchlist.py:1015-1019`), i.e. from a file
     * on disk, which is exactly this method: `COMPLETED` **and** [outputPresent].
     */
    override suspend fun isEpisodeOutputPresent(
        animeSession: String,
        episode: EpisodeInfo,
    ): Boolean {
        val row = dao.getById(episodeDownloadId(animeSession, episode)) ?: return false
        if (row.state != EpisodeDownloadEntity.STATE_COMPLETED) return false
        return outputPresent(row)
    }

    /**
     * `EpisodeDownloadQueue.resolvedAheadCount`: the real staging
     * depth the batch resolver throttles against — resolved-but-not-started
     * episodes, i.e. Python's `download_queue` depth.
     */
    override suspend fun resolvedAheadCount(): Int = dao.countResolvedPending()

    /**
     * 11.5E-6 → Slice B: whether an existing row makes this re-submission a
     * no-op, expressed as the outcome the caller must be told.
     *
     * `null` means "proceed" — `QUEUED`/`RESOLVING` recovery, `FAILED`/
     * `CANCELLED` re-queue, and `COMPLETED` whose output vanished (Python
     * re-downloads when the file is gone, `downloader.py:60-62`'s converse).
     * Every non-null value is a state the pipeline refuses to touch, and the
     * reason it refuses is exactly the value returned.
     *
     * The classification is unchanged from the 11.5E-6 boolean guard; what
     * changed is that the caller no longer has to guess which branch it hit
     * (DECISIONS 146).
     */
    private suspend fun suppressedOutcome(existing: EpisodeDownloadEntity): EnqueueOutcome? =
        when (existing.state) {
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            EpisodeDownloadEntity.STATE_ASSEMBLING -> EnqueueOutcome.AlreadyActive

            // The user stopped it — a re-submission must not start it again;
            // only [resume] may.
            EpisodeDownloadEntity.STATE_PAUSED -> EnqueueOutcome.AlreadyPaused

            // Present output → nothing to do. Gone ROW output → a surviving
            // DESTINATION is still this episode's export (§3.1: the gate is
            // destination-aware, not only row-aware), so it is the same no-op;
            // only when neither the row's output nor a destination survives does
            // the legitimate re-queue below run (Python re-downloads a missing
            // file).
            EpisodeDownloadEntity.STATE_COMPLETED ->
                if (outputPresent(existing)) {
                    EnqueueOutcome.AlreadyDownloaded
                } else if (destinationSurvives(
                        episodeNumber = existing.episodeNumber,
                        title = existing.title,
                        audio = existing.audio,
                        sessionId = existing.sessionId,
                        animeTitleFallback = null,
                    )
                ) {
                    EnqueueOutcome.AlreadyDownloaded
                } else {
                    null
                }

            else -> null
        }

    /**
     * Slice B (DECISIONS 146): does the row still reference a real output?
     *
     * **A non-blank `outputUri` is no longer proof by itself.** The previous
     * implementation trusted any persisted URI outright while the startup
     * reconciler independently resolved the very same column through
     * `ContentResolver` — so one row could be reported "already downloaded" at
     * submission time and demoted to `FAILED` ("file missing") at the next
     * launch. Both now share [EpisodeOutputVerifier], which applies the §29 rule
     * (`downloader.py:60`'s exact `exists() && size > 0`) and resolves the URI
     * through the injected resolver before believing it.
     *
     * Nothing is deleted, renamed or migrated here — this only answers
     * "is a real, non-empty output there?".
     *
     * Room reads are suspend-dispatched, and the verifier performs Binder IPC
     * plus a file probe, so the whole check runs on IO: this seam is called
     * straight from the ViewModel's main scope.
     */
    private suspend fun outputPresent(episode: EpisodeDownloadEntity): Boolean {
        val outputUri = episode.outputUri
        val outputPath = episode.outputPath
        return withContext(Dispatchers.IO) {
            outputVerifier.isPresent(outputUri = outputUri, outputPath = outputPath)
        }
    }

    /**
     * §3.1 (DECISIONS 157): is a live export already present at this episode's
     * DESTINATION, independent of what the row references?
     *
     * The counterpart to [outputPresent]: that reads the row's own pointers, this
     * asks the configured exporter whether a real file already sits at the exact
     * destination this episode would be written to. Together they make the
     * submission gate destination-aware, so a re-download over a surviving export
     * is the same "already downloaded" no-op whether the row is healthy, its
     * pointers are severed, or the row is gone entirely (app-private state
     * destroyed) — instead of adopting the old file and discarding the fresh bytes.
     *
     * [animeTitleFallback] supplies the title when the session row is not (yet)
     * resolvable — a first-time row-absent submission probes with the title the
     * caller already holds. `null` probe (host-constructed tests) or an unprobed
     * destination returns `false`, the previous admit behaviour. The MediaStore
     * probe self-dispatches to IO internally; this seam additionally contains
     * ANY probe failure (see below).
     */
    private suspend fun destinationSurvives(
        episodeNumber: Float,
        title: String,
        audio: String,
        sessionId: String,
        animeTitleFallback: String?,
    ): Boolean {
        val probe = destinationProbe ?: return false
        val animeTitle = sessionDao.getById(sessionId)?.title ?: animeTitleFallback
        return try {
            withContext(Dispatchers.IO) {
                probe.destinationOutput(
                    episodeNumber = episodeNumber,
                    title = title,
                    audio = audio,
                    animeTitle = animeTitle,
                    sessionId = sessionId,
                )
            } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // §3.1 (DECISIONS 157): a probe that cannot answer must only ever
            // UNDER-suppress — an exotic provider failure is treated as "no
            // destination detected" so it can never block a legitimate
            // submission (the interface's conservative contract; review
            // finding 2).
            AppLog.w(TAG, "Destination probe errored; admitting submission: ${e.message}")
            false
        }
    }

    /**
     * Display/file title persisted for a queued episode.
     *
     * Python `downloader.py:47`: `title = ep.title or info.title or f"Episode {ep.ep_str}"`
     * — the episode title wins, the RESOLVED stream title is the fallback, and only
     * then the positional label. AnimePahe episodes frequently carry no per-episode
     * title (an empty `title` in the release API), and Python additionally backfills
     * it from the resolved stream (`downloader.py:278-279`), so the resolved title
     * must not be dropped here: it is what feeds the `Ep {n} - {title}` filename, the
     * Downloads row and the player label.
     */
    private fun downloadTitle(episode: EpisodeInfo, stream: StreamInfo): String =
        episode.title.ifBlank {
            stream.title.ifBlank { "Episode ${episode.epStr}" }
        }

    /**
     * Enqueue a download for an episode.
     *
     * Flow:
     * 1. Create/persist episode state (RESOLVING)
     * 2. Resolve through StreamResolverImpl/WebView (called by caller)
     * 3. Persist m3u8Url/userAgent/referer/segmentDir
     * 4. Transition to QUEUED (Worker-ready state)
     * 5. Enqueue unique WorkManager work
     *
     * Note: steps 1-4 are split because resolution requires foreground WebView.
     * The caller (ViewModel/UI, or — for a 11.5E-7 retry — [reEnqueue] itself)
     * handles resolution, then calls resolveComplete().
     *
     * Slice B closure (review findings F2/F7): this used to be a blind
     * read → `upsert` → `updateState` triple, the ONLY unguarded write in an
     * otherwise compare-and-set codebase. Every path below is now a single
     * atomic statement:
     *
     * * **No row** → `INSERT OR IGNORE` straight into `RESOLVING`, so exactly
     *   one caller creates it and a loser writes nothing. The intermediate
     *   `QUEUED` state never exists (DECISIONS 146.5's reasoning, applied here).
     * * **Row present and not terminal** → untouched: the pipeline owns it.
     *   This is also what a pre-claimed row looks like — `reEnqueue`'s
     *   `beginRetry` has already moved it to `RESOLVING`, and the stream-less
     *   `QUEUED` zombie repair (review finding H-1) relies on it too — so
     *   `state`, `createdAt`, `retryCount` and progress all survive, exactly as
     *   `DuplicateEnqueueTest` and `DownloadRepositoryTest` pin.
     * * **Row present and terminal** → [EpisodeDownloadDao.claimTerminalRequeue]
     *   resets the submission's columns AND moves the row to `RESOLVING` in ONE
     *   guarded UPDATE. Because there is no window between "was terminal" and
     *   "is ours", a Cancel cannot land in between (F7a), a concurrent
     *   `beginRetry` is never overwritten back to `retryCount = 0` (F7b), and
     *   only one of any number of concurrent submitters can win (F2).
     *
     * @param expectedTerminalState the terminal state THE CALLER itself observed
     *   the row in, when the caller classified it before acting. Passing it makes
     *   the claim a true compare-and-set on that exact state: a row that moved
     *   meanwhile (another submitter claimed it, or the user cancelled it) is
     *   REFUSED rather than re-queued. `null` — the form direct callers use —
     *   means "classify from the current row".
     * @return true when this call created the row, claimed the terminal
     *   re-queue, or legitimately found the pipeline already owning it; false
     *   when it owns NOTHING, in which case the caller must re-derive the outcome
     *   and must not report work it did not start.
     */
    suspend fun enqueue(
        episode: EpisodeDownloadEntity,
        expectedTerminalState: String? = null,
    ): Boolean {
        val episodeId = episode.episodeId
        val existing = dao.getById(episodeId)

        if (existing == null) {
            val rowId = dao.insertIfAbsent(
                episode.copy(state = EpisodeDownloadEntity.STATE_RESOLVING),
            )
            if (rowId == -1L) {
                // The row reappeared between the read and the insert: another
                // caller owns it, so this one claims nothing.
                AppLog.d(TAG, "Episode $episodeId reappeared before it could be created")
                return false
            }
            AppLog.d(TAG, "Enqueued episode $episodeId for resolution")
            return true
        }

        if (expectedTerminalState != null && existing.state != expectedTerminalState) {
            AppLog.d(
                TAG,
                "Claim of $episodeId refused: caller saw $expectedTerminalState, row is now ${existing.state}",
            )
            return false
        }

        if (!existing.isTerminal()) {
            AppLog.d(TAG, "Episode $episodeId already in state ${existing.state}, skipping")
            return true
        }

        val claimed = dao.claimTerminalRequeue(
            episodeId = episodeId,
            expectedState = existing.state,
            sessionId = episode.sessionId,
            episodeNumber = episode.episodeNumber,
            episodeSession = episode.episodeSession,
            title = episode.title,
            fansub = episode.fansub,
            audio = episode.audio,
            playUrl = episode.playUrl,
            createdAt = episode.createdAt,
        )
        if (claimed == 0) {
            AppLog.d(TAG, "Claim of $episodeId lost (row left ${existing.state} first)")
            return false
        }

        val segmentDir = File(context.filesDir, "downloads/$episodeId")
        if (!segmentDir.exists()) segmentDir.mkdirs()

        AppLog.d(TAG, "Enqueued episode $episodeId for resolution")
        return true
    }

    /**
     * Called after stream resolution completes.
     * Persists stream info and enqueues Worker.
     *
     *
     *
     * 11.5E-7 (S-1): the `→ QUEUED` transition is now a CAS over exactly the
     * two states the pipeline legitimately owns here — `RESOLVING` (fresh
     * queue path) and `QUEUED` (stream-info-less zombie recovery, §24 finding
     * 2) — and the Worker is enqueued only when that CAS won. `cancel()`'s
     * CANCELLED intent is durable (Phase 10 corrective), so a Cancel landing
     * between resolution and this hand-off can no longer be flipped back to
     * `QUEUED` with a Worker appended after it. Stream info is still persisted
     * first (harmless on a terminal row; the next retry clears it).
     * Slice B (review finding H-2): the CAS verdict is now RETURNED instead of
     * being logged and dropped. Before this, a Cancel, Pause or reconciler move
     * landing in that window produced a normal return while no Worker existed —
     * and every caller faithfully told the user "In the download queue".
     * `queueResolvedEpisode` derives the honest outcome from the row when this
     * is `false`, so `EnqueueOutcome.Accepted` is never reported for a hand-off
     * that did not happen.
     *
     * @return true only when the CAS won and the unique Worker was enqueued.
     */
    suspend fun resolveComplete(
        episodeId: String,
        m3u8Url: String,
        userAgent: String,
        referer: String
    ): Boolean {
        val segmentDir = File(context.filesDir, "downloads/$episodeId")

        // Persist stream info atomically
        dao.saveStreamInfo(
            episodeId = episodeId,
            m3u8Url = m3u8Url,
            userAgent = userAgent,
            referer = referer,
            segmentDir = segmentDir.absolutePath
        )

        // Transition to QUEUED (Worker-ready state) — CAS, not blind write.
        var queued = dao.updateStateForResume(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_RESOLVING,
            newState = EpisodeDownloadEntity.STATE_QUEUED,
        ) > 0
        if (!queued) {
            queued = dao.updateStateForResume(
                episodeId = episodeId,
                expectedState = EpisodeDownloadEntity.STATE_QUEUED,
                newState = EpisodeDownloadEntity.STATE_QUEUED,
            ) > 0
        }
        if (!queued) {
            AppLog.w(TAG, "resolveComplete: $episodeId is no longer queueable — stream persisted, Worker NOT enqueued")
            return false
        }

        // Enqueue Worker
        enqueueWorker(episodeId)

        AppLog.d(TAG, "Resolution complete for $episodeId, Worker enqueued")
        return true
    }

    /**
     * Ordered observe-all stream for the Phase 11 §16 dashboard.
     * Room remains the single source of truth (AGENTS §9); the dashboard never
     * polls — this Flow pushes every row change through the pure mapper.
     */
    fun observeAll(): Flow<List<EpisodeDownloadEntity>> = dao.observeAllOrdered()

    /**
     * Single persisted row lookup for the player entry seed (PHASE11_ARCHITECTURE_V2 §23).
     * Room stays the source of truth — the player never trusts route state beyond the ID.
     */
    suspend fun getEpisode(episodeId: String): EpisodeDownloadEntity? = dao.getById(episodeId)

    /**
     * Playable playlist for in-ViewModel next/previous switching (§23/§24.2):
     * sibling `COMPLETED` rows of [sessionId] that resolve to a local/exported
     * source, ordered by episode number — the pure rule lives in
     * [PlaybackPlaylist] so §36 unit tests exercise it directly.
     */
    suspend fun getCompletedPlaylist(sessionId: String): List<EpisodeDownloadEntity> =
        PlaybackPlaylist.completedPlayable(dao.getBySessionId(sessionId).first())

    /**
     * Cancel a download.
     *
     * Persists CANCELLED in Room from the caller's
     * (non-cancelled) coroutine scope, THEN cancels the Worker. A cancelled
     * Worker coroutine cannot reliably write to Room itself, so the durable
     * cancellation intent must be recorded here. CANCELLED is terminal, so
     * reconciliation will not resurrect the episode.
     *
     * Final-audit M1: the write is TERMINAL-GUARDED ([EpisodeDownloadDao.cancelIfNotTerminal])
     * instead of blind. Every non-terminal row still cancels exactly as before —
     * including `PAUSED`, so `Cancel` really discards a pause (§17.1 matrix) —
     * but a Cancel that races the Worker's own completion write can no longer
     * stamp `CANCELLED` over a `COMPLETED` row (or bury a `FAILED` row's reason).
     * The Worker is cancelled in all cases: if the guard refused, the work is
     * already finished or owned by nobody, and stopping it is a harmless no-op.
     *
     * @return true only when this call really stopped the run — by writing
     *   `CANCELLED`, or (for a confirmed `Replace file` whose export survived)
     *   by restoring the row to the `COMPLETED` state it held before the
     *   confirmation. False when a newer state already won.
     */
    suspend fun cancel(episodeId: String): Boolean {
        // `Replace file` (DECISIONS 152): a confirmed replacement is an OVERLAY
        // on a completed export, not a new download — cancelling it must leave
        // the existing export AND the COMPLETED state that describes it
        // untouched. So this case restores the pre-confirmation state instead of
        // writing CANCELLED, and only when the previous output really survived
        // (a §29-verified rule, never an assumption). An ordinary row takes the
        // unchanged CANCELLED path below.
        val row = dao.getById(episodeId)
        if (row != null && row.replaceRequested && outputPresent(row)) {
            val restored = dao.restoreCompletedForUncommittedReplacement(
                episodeId = episodeId,
                expectedState = row.state,
            ) > 0
            if (restored) {
                AppLog.d(TAG, "Cancel of $episodeId aborted the replacement — existing export kept, row restored to COMPLETED")
                cancelWorker(episodeId)
                return true
            }
            // The row moved underneath us; fall through so a concurrent winner
            // is never overwritten by a second restore attempt.
        }
        val cancelled = dao.cancelIfNotTerminal(episodeId) > 0
        if (!cancelled) {
            AppLog.d(TAG, "Cancel of $episodeId did not write CANCELLED — terminal row or no row")
        }
        // A replacement whose export is already gone ends as an ordinary
        // CANCELLED row: the in-flight intent must not outlive the run, or a
        // later Worker would stage into `replace/` for a run nobody confirmed.
        if (row != null && row.replaceRequested) {
            dao.clearReplacementIntent(episodeId)
        }
        cancelWorker(episodeId)
        return cancelled
    }

    /**
     * SPEC §20 **Pause** / SPEC §25 `paused`.
     *
     * The order is the whole contract:
     *
     *  1. the `PAUSED` intent is CAS-persisted FIRST, so it is already the row's
     *     state of record before anything is asked to stop;
     *  2. only then is the unique work cancelled.
     *
     * That ordering is what makes the pause durable. WorkManager cancellation is
     * asynchronous, and the worker's own cleanup writes `DOWNLOADING → CANCELLED`
     * under `NonCancellable` — by then this row is already `PAUSED`, so that CAS
     * loses and the user's intent survives instead of being overwritten by the
     * very cancellation it caused. The same CAS also clears `speed`/`etaSeconds`
     * (the claim-transition contract), so a paused row never advertises a rate.
     *
     * Losing the CAS means a newer state won the race (the episode completed, was
     * cancelled, or was already paused): nothing is touched, no work is cancelled,
     * and this returns `false` rather than pretending a pause happened.
     *
     * @return true only when THIS call really paused the row.
     */
    suspend fun pause(episodeId: String): Boolean {
        val episode = dao.getById(episodeId)
        if (episode == null || !episode.isPausable()) {
            AppLog.d(TAG, "Pause of $episodeId ignored: state=${episode?.state}")
            return false
        }

        val paused = dao.updateStateForResume(
            episodeId = episodeId,
            expectedState = episode.state,
            newState = EpisodeDownloadEntity.STATE_PAUSED,
        ) > 0
        if (!paused) {
            AppLog.d(TAG, "Pause of $episodeId lost the state claim — newer state kept")
            return false
        }

        cancelWorker(episodeId)
        AppLog.d(TAG, "Paused $episodeId (intent persisted, unique work cancelled)")
        return true
    }

    /**
     * The counterpart of [pause] — SPEC §20 **Resume**.
     *
     * Two honest paths, chosen by what the row actually holds:
     *
     *  - a persisted stream (`m3u8Url`) → CAS `PAUSED → QUEUED` and hand the row
     *    back to the EXISTING unique Worker. Nothing is re-resolved: the stream
     *    is still valid persisted state, and `ExistingWorkPolicy.KEEP` keeps this
     *    idempotent under a double tap.
     *  - no persisted stream (paused while `RESOLVING`, or a reconciler-created
     *    stream-less `QUEUED` row) → enqueueing a Worker here could only produce
     *    a "No stream info available" failure, so the EXISTING
     *    resolve → queue pipeline runs instead (`reEnqueue`, which now accepts
     *    `PAUSED` as its entry state and re-resolves from the persisted `playUrl`).
     *
     * @return true only when the row really left `PAUSED`.
     */
    suspend fun resume(episodeId: String): Boolean {
        val episode = dao.getById(episodeId) ?: return false
        if (episode.state != EpisodeDownloadEntity.STATE_PAUSED) return false

        if (!episode.m3u8Url.isNullOrBlank()) {
            val queued = dao.updateStateForResume(
                episodeId = episodeId,
                expectedState = EpisodeDownloadEntity.STATE_PAUSED,
                newState = EpisodeDownloadEntity.STATE_QUEUED,
            ) > 0
            if (!queued) {
                AppLog.d(TAG, "Resume of $episodeId lost the state claim — newer state kept")
                return false
            }
            enqueueWorker(episodeId)
            AppLog.d(TAG, "Resumed $episodeId (PAUSED → QUEUED, Worker enqueued)")
            return true
        }

        // No stream to hand a Worker: re-resolve through the existing pipeline,
        // then verify by re-reading — a refused claim leaves the row PAUSED and
        // must be reported as "not resumed", never as success.
        AppLog.d(TAG, "Resuming $episodeId has no persisted stream — re-resolving")
        reEnqueue(episodeId)
        return dao.getById(episodeId)?.state != EpisodeDownloadEntity.STATE_PAUSED
    }

    /**
     * Re-enter the EXISTING resolve → queue → WorkManager pipeline for a
     * terminal episode (§24.1, fixed in 11.5E-7).
     *
     * **The defect this replaced:** the Phase 10 implementation did
     * `updateState(QUEUED)` + `incrementRetryCount` (both since superseded; the
     * latter deleted as dead code in Phase 11.5 item (c)) + `clearStreamInfo()` +
     * `enqueueWorker()` directly, so every Downloads-screen Retry produced a
     * Worker with no `m3u8Url` — and the Worker is forbidden to resolve
     * (`DownloadWorker` KDoc), so it deterministically failed with
     * "No stream info available" without any network involved. Python
     * re-resolves on every attempt (`downloader.py:233-275` calls
     * `extract_stream()` fresh from the persistent `ep.play_url`).
     *
     * **Contract now:**
     *
     * 1. Eligibility: a terminal row — `FAILED`/`CANCELLED` always; `COMPLETED`
     *    only when its output is gone (the 11.5E-6 [outputPresent] rule, Python
     *    `downloader.py:60-62`), so a valid output never re-downloads.
     *    Non-terminal rows (`QUEUED`/`RESOLVING`/`DOWNLOADING`/`ASSEMBLING`)
     *    return untouched — no duplicate work is ever created from here.
     * 2. Identity must round-trip BEFORE any mutation: [queueResolvedEpisode]
     *    re-derives the key as `<session>_<epStr>_<audio>`, so a row whose
     *    persisted `episodeId` does not match its own columns aborts untouched
     *    (logged) instead of stranding itself in `RESOLVING` and creating a
     *    phantom row under the derived key. Production rows always conform
     *    (the key is derived on creation) — this refuses hand-seeded/legacy
     *    rows only.
     * 3. `playUrl`, episode identity, audio, progress, `segmentDir` and
     *    `outputPath` survive: the atomic claim [EpisodeDownloadDao.beginRetry]
     *    (the row's OWN terminal state → `RESOLVING`, `retryCount + 1`) clears
     *    ONLY the stale resolved credentials (`m3u8Url`/`userAgent`/`referer`),
     *    the stale `errorMessage`, and the stale `outputUri` (a dead export URI
     *    would otherwise demote the FRESH download at the next §29 validation).
     *    A second rapid Retry loses the claim and returns before touching the
     *    resolver, so a double-tap cannot duplicate resolution or WorkManager
     *    work (unique name + `ExistingWorkPolicy.KEEP` remain the second layer).
     * 4. A FRESH [StreamResolver.resolve] runs (foreground WebView session —
     *    serialized process-wide by `StreamResolverImpl`'s single-flight
     *    mutex, AGENTS §11); on success the result is handed to the EXISTING
     *    [queueResolvedEpisode] path — its guard sees `RESOLVING` (allowed),
     *    [enqueue] early-returns on the non-terminal row (identity, progress,
     *    `retryCount` and any stale `outputPath` are NOT clobbered), and
     *    [resolveComplete] persists the fresh stream, CASes `QUEUED` and
     *    enqueues the unique Worker. No second pipeline exists.
     * 5. Failures never lie: a resolution error writes `FAILED` + message only
     *    while the row is STILL `RESOLVING` (CAS via
     *    [EpisodeDownloadDao.updateErrorIfState] — a Cancel that landed first
     *    wins and is not overwritten); cancellation anywhere after the claim
     *    restores the original terminal state under `NonCancellable` (CAS) —
     *    never a stuck `RESOLVING` row; a result that arrives after the user
     *    cancelled is discarded before any stream persistence or work
     *    hand-off; and a `QUEUED`-without-stream row (reconciler collision)
     *    is restored to its retryable terminal state rather than abandoned.
     * 6. Completed `.ts` segments are untouched (nothing here writes to the
     *    segment directory) — the Worker's downloader resumes from the
     *    persisted files (Phase 8).
     *
     * @return nothing; eligibility losers are logged no-ops. Requires a wired
     *   [streamResolver] (production always has one) — without it the call
     *   mutates NOTHING.
     * @param replacement `true` only for a user-CONFIRMED `Replace file`
     *   (DECISIONS 152) that was already classified by [confirmReplacement].
     *   It changes exactly two things and everything else is shared:
     *   (a) the `COMPLETED && outputPresent` suppression is skipped — the
     *   caller verified a valid export exists and explicitly asked to replace
     *   it; (b) the claim is [EpisodeDownloadDao.beginReplacement], which
     *   PERSISTS `replaceRequested`/`replacePriorUri` and KEEPS
     *   `outputUri`/`outputPath` (the export being replaced must stay
     *   referenced and intact) instead of [EpisodeDownloadDao.beginRetry],
     *   which nulls them. Every failure/teardown path below then restores
     *   `COMPLETED` + clears the intent instead of writing `FAILED`.
     *
     * Final SPEC gap closure (SPEC §20 Resume): `PAUSED` is accepted as an entry
     * state alongside the terminal ones, because [resume] has to re-resolve a
     * paused row that owns no persisted stream. The claim is still a CAS on the
     * observed `PAUSED` ([EpisodeDownloadDao.beginRetry]), so a row that changed
     * underneath is refused rather than forced; and a refusal mid-way restores
     * `PAUSED` (the `QUEUED`-without-stream branch writes `priorState` back), so a
     * failed resume never leaves the row stuck in `RESOLVING`.
     */
    suspend fun reEnqueue(episodeId: String, replacement: Boolean = false) {
        val episode = dao.getById(episodeId) ?: return
        if (!episode.isTerminal() && episode.state != EpisodeDownloadEntity.STATE_PAUSED) {
            AppLog.d(TAG, "Cannot re-enqueue $episodeId: state=${episode.state} is neither terminal nor paused")
            return
        }
        if (!replacement && episode.state == EpisodeDownloadEntity.STATE_COMPLETED && outputPresent(episode)) {
            AppLog.d(TAG, "Suppressed re-enqueue of $episodeId (completed output still present)")
            return
        }
        val resolver = streamResolver
        if (resolver == null) {
            AppLog.e(TAG, "Cannot re-enqueue $episodeId: no StreamResolver wired")
            return
        }
        val session = sessionDao.getById(episode.sessionId)
        if (session == null) {
            // FK cascade should make this unreachable; refuse rather than
            // resolve with unknown quality/host.
            AppLog.e(TAG, "Cannot re-enqueue $episodeId: session ${episode.sessionId} missing")
            return
        }

        // The row's identity must round-trip through the EXISTING pipeline:
        // queueResolvedEpisode re-derives the key from these very columns, so
        // a non-conforming id would strand THIS row in RESOLVING and create a
        // phantom row under the derived key. Refuse before any mutation.
        val reconstructed = EpisodeInfo(
            number = episode.episodeNumber,
            session = episode.episodeSession,
            title = episode.title,
            fansub = episode.fansub,
            audio = episode.audio,
            playUrl = episode.playUrl,
        )
        val expectedKey = episodeDownloadId(episode.sessionId, reconstructed)
        if (expectedKey != episodeId) {
            AppLog.e(TAG, "Cannot re-enqueue $episodeId: key does not match its own episode columns (expected $expectedKey)")
            return
        }

        val priorState = episode.state

        if (episode.playUrl.isBlank()) {
            // Without a play page there is nothing to resolve — fail honestly
            // and visibly (FAILED shows the reason) instead of faking progress.
            // 11.5E item (e), reviewer M-6 (LIMITATIONS §27.2): this is now the
            // FIRST write in the function and it runs only after the session
            // lookup and the identity round-trip above (contract #2: round-trip
            // BEFORE any mutation), so a row that fails either check is left
            // completely untouched. It is also CAS-guarded on the very terminal
            // state eligibility was decided on: a concurrent winner that already
            // claimed the row out of it is never forced to FAILED.
            AppLog.e(TAG, "Cannot re-enqueue $episodeId: persisted playUrl is blank")
            // A confirmed replacement never reaches this: `confirmReplacement`
            // refuses before it claims anything, so the COMPLETED row (and the
            // export it describes) is never converted into FAILED on its behalf.
            if (replacement) return
            dao.updateErrorIfState(
                episodeId = episodeId,
                expectedState = priorState,
                errorMessage = "Missing play page URL",
            )
            return
        }

        // Atomic claim + claim-time cleanup in ONE guarded UPDATE: exactly one
        // Retry can win from this row's OWN terminal state (a Cancel landing
        // first also defeats the claim), and the stale stream / error / export
        // references die with the claim. playUrl, segmentDir, progress and
        // outputPath are untouched.
        //
        // `Replace file` (DECISIONS 152) claims through the other guarded
        // UPDATE instead: identical CAS shape, but it PERSISTS the replacement
        // intent and KEEPS outputUri/outputPath, because this re-queue is the
        // one that must not disturb the export it is about to replace. Both
        // claims carry `replaceRequested = 0`, so a repeated confirmation can
        // never start a second run.
        val claimed = if (replacement) {
            dao.beginReplacement(episodeId, priorState, episode.outputUri)
        } else {
            dao.beginRetry(episodeId, priorState)
        }
        if (claimed == 0) {
            AppLog.d(
                TAG,
                if (replacement) {
                    "Replacement of $episodeId lost the state claim (a replacement or a state change won)"
                } else {
                    "Re-enqueue of $episodeId lost the state claim (another retry or a state change won)"
                },
            )
            return
        }

        try {
            val fresh = resolver.resolve(
                playUrl = episode.playUrl,
                quality = session.quality,
                audio = episode.audio,
            )

            // Cancel-during-resolution / reconciler-collision guard: the fresh
            // result may only enter the pipeline while WE still own the row.
            val current = dao.getById(episodeId)
            when {
                current?.state == EpisodeDownloadEntity.STATE_RESOLVING -> {
                    // proceed to the hand-off below
                }

                current?.state == EpisodeDownloadEntity.STATE_QUEUED &&
                    current.m3u8Url == null -> {
                    // The startup reconciler flipped our in-flight row to a
                    // stream-less QUEUED zombie — restore the retryable
                    // terminal state instead of silently abandoning the retry.
                    if (replacement) {
                        restoreUncommittedReplacement(episodeId, EpisodeDownloadEntity.STATE_QUEUED)
                    } else {
                        dao.updateStateForResume(
                            episodeId = episodeId,
                            expectedState = EpisodeDownloadEntity.STATE_QUEUED,
                            newState = priorState,
                        )
                    }
                    AppLog.w(TAG, "Aborted re-enqueue of $episodeId: reconciler moved it to QUEUED without a stream; restored $priorState")
                    return
                }

                else -> {
                    // The user's CANCELLED intent (or any newer state) wins —
                    // discard BEFORE any persistence or work hand-off, so no
                    // download is ever queued after cancellation.
                    AppLog.d(TAG, "Discarding re-resolution of $episodeId: state=${current?.state} is no longer RESOLVING")
                    return
                }
            }

            // The EXISTING pipeline, unchanged: guard (RESOLVING → allowed) →
            // ensureSession → enqueue (early-returns on the non-terminal row) →
            // resolveComplete (fresh stream + CAS QUEUED + unique Worker KEEP).
            //
            // Slice B: the returned outcome is deliberately NOT used to decide
            // anything here — [reEnqueue]'s truthfulness contract stays the
            // state re-read the caller performs ([resume] checks the row itself
            // rather than trusting this log), and this path already verified
            // ownership above. It only keeps the log honest.
            val outcome = queueResolvedEpisode(
                ResolvedEpisodeSubmission(
                    animeSession = episode.sessionId,
                    animeTitle = session.title,
                    host = session.host,
                    quality = session.quality,
                    episode = reconstructed,
                    stream = fresh,
                ),
            )
            AppLog.d(TAG, "Re-enqueued $episodeId (from $priorState) via fresh re-resolution: $outcome")
        } catch (e: CancellationException) {
            // Scope teardown anywhere after the claim: never leave a misleading
            // RESOLVING row (the startup reconciler would turn it into a
            // stream-less QUEUED zombie). CAS restore — a user Cancel that
            // landed first stays CANCELLED.
            if (replacement) {
                restoreUncommittedReplacement(episodeId, EpisodeDownloadEntity.STATE_RESOLVING)
            } else {
                restorePriorTerminalState(episodeId, priorState)
            }
            throw e
        } catch (e: Exception) {
            // Defence in depth: the session layer propagates CancellationException
            // now (NET-001/AQR-019), so this branch is only reached for real
            // failures — but a downstream layer converting cancellation into a
            // failure must still be caught here, because a RESOLVING row left
            // behind would become a stream-less QUEUED zombie.
            if (!coroutineContext.isActive) {
                if (replacement) {
                    restoreUncommittedReplacement(episodeId, EpisodeDownloadEntity.STATE_RESOLVING)
                } else {
                    restorePriorTerminalState(episodeId, priorState)
                }
                coroutineContext.ensureActive()
            }
            if (replacement) {
                // A replacement that never reached the Worker ends where it
                // began: COMPLETED, export untouched, intent cleared. The
                // attempt is reported through `confirmReplacement`'s re-read,
                // so a row whose export survived is never stamped FAILED.
                restoreUncommittedReplacement(episodeId, EpisodeDownloadEntity.STATE_RESOLVING)
                AppLog.e(
                    TAG,
                    "Replacement re-resolution failed for $episodeId: ${LogUrlSanitizer.diagnostic(e.message)}",
                )
                return
            }
            // Honest, retryable failure — CAS-guarded so a Cancel that landed
            // first is never converted into FAILED. playUrl stays persisted and
            // the cleared (stale) stream is never treated as valid.
            val written = dao.updateErrorIfState(
                episodeId = episodeId,
                expectedState = EpisodeDownloadEntity.STATE_RESOLVING,
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Stream resolution failed",
            )
            if (written == 1) {
                // Release-emitted ERROR: any exception from the resolution
                // flow may quote a complete stream/Kwik URL in its message
                // (the throw site at StreamResolverImpl is sanitized, but this
                // line guards EVERY exception type reaching this catch), so it
                // goes through `diagnostic` before the sink (DECISIONS 136).
                AppLog.e(TAG, "Re-resolution failed for $episodeId: ${LogUrlSanitizer.diagnostic(e.message)}")
            } else {
                AppLog.d(TAG, "Re-enqueue of $episodeId error after the row left RESOLVING (state=${dao.getById(episodeId)?.state}); newer state kept — ${e.message}")
            }
            return
        }
    }

    /**
     * 11.5E-7: CAS-restore of the pre-retry terminal state after a teardown
     * cancellation. `NonCancellable` because the coroutine is already cancelled
     * — this write MUST still land; the CAS means it only lands while the row
     * is still `RESOLVING` (a concurrent user Cancel is never overwritten).
     */
    private suspend fun restorePriorTerminalState(episodeId: String, priorState: String) {
        withContext(NonCancellable) {
            dao.updateStateForResume(
                episodeId = episodeId,
                expectedState = EpisodeDownloadEntity.STATE_RESOLVING,
                newState = priorState,
            )
        }
    }

    /**
     * `Replace file` (DECISIONS 152): undo a claimed replacement that never
     * produced a verified new output, for the state [expectedState] this
     * attempt currently owns.
     *
     * The export the replacement was going to replace was never touched, so
     * the row goes back to `COMPLETED` — the truth — and BOTH replacement
     * columns die with the attempt in the same guarded UPDATE. `NonCancellable`
     * because the caller is usually unwinding a cancellation: this write must
     * still land, and the CAS means a concurrent user Cancel or a newer state
     * is never stamped over.
     *
     * When the CAS loses (the row already left the state this attempt owned)
     * and no other replacement run owns it, the intent is cleared on its own so
     * a `replaceRequested` flag can never outlive the run that set it.
     */
    private suspend fun restoreUncommittedReplacement(episodeId: String, expectedState: String) {
        withContext(NonCancellable) {
            val restored = dao.restoreCompletedForUncommittedReplacement(
                episodeId = episodeId,
                expectedState = expectedState,
            )
            if (restored == 0) {
                dao.clearReplacementIntent(episodeId)
            }
        }
    }

    /**
     * `Replace file` (DECISIONS 152) — is there a valid export for the dialog
     * to offer to replace?
     *
     * The UI must never present a destructive confirmation for something that
     * is not there, so this is the gate that runs BEFORE the dialog is shown,
     * and it applies exactly the same §29 rule [confirmReplacement] re-applies
     * at confirmation time. It is a read only: nothing is claimed, nothing is
     * written, and a stale notification action simply gets `false`.
     */
    suspend fun isReplacementAvailable(episodeId: String): Boolean {
        val row = dao.getById(episodeId) ?: return false
        if (row.replaceRequested) return false
        if (row.state != EpisodeDownloadEntity.STATE_COMPLETED) return false
        return outputPresent(row)
    }

    /**
     * `Replace file` (DECISIONS 152) — the ONE entry point that may start a
     * download over a valid completed export.
     *
     * Called only after the user has read and confirmed the dialog; this
     * function itself performs no UI and never deletes anything. Its whole job
     * is to classify the request honestly and then claim the row through
     * [reEnqueue]'s guarded replacement claim.
     *
     * Ordering is deliberate:
     *
     * 1. classification runs BEFORE any write, so a stale action (the episode
     *    is gone, is no longer `COMPLETED`, or its output no longer verifies)
     *    returns [ReplacementOutcome.Unavailable] with the row byte-identical;
     * 2. a missing/invalid output is [ReplacementOutcome.Unavailable] as well —
     *    §6: an ordinary redownload owns that case, and no destructive
     *    confirmation may be shown for it;
     * 3. only then does [reEnqueue] claim the row with `replaceRequested = 1`,
     *    which is what makes a repeated confirmation lose (idempotency) and
     *    what survives process death;
     * 4. the outcome is RE-DERIVED from the persisted row rather than trusted
     *    from the attempt — if re-resolution never handed off, the attempt has
     *    already restored `COMPLETED` and this reports [Unavailable] instead
     *    of a `Started` that never happened.
     */
    suspend fun confirmReplacement(episodeId: String): ReplacementOutcome {
        val row = dao.getById(episodeId)
            ?: return ReplacementOutcome.Unavailable("download not found")
        if (row.replaceRequested) {
            // Idempotent repeat: a second notification tap or a second
            // confirmation is a no-op, never a competing run.
            return ReplacementOutcome.AlreadyInFlight
        }
        if (row.state != EpisodeDownloadEntity.STATE_COMPLETED) {
            return ReplacementOutcome.Unavailable("download is not completed")
        }
        if (!outputPresent(row)) {
            return ReplacementOutcome.Unavailable("there is no exported file to replace")
        }
        if (row.playUrl.isBlank()) {
            return ReplacementOutcome.Unavailable("the episode's play page is no longer recorded")
        }
        if (streamResolver == null) {
            AppLog.e(TAG, "Cannot replace $episodeId: no StreamResolver wired")
            return ReplacementOutcome.Refused
        }

        reEnqueue(episodeId, replacement = true)

        val after = dao.getById(episodeId) ?: return ReplacementOutcome.Refused
        return when {
            after.replaceRequested && after.state != EpisodeDownloadEntity.STATE_COMPLETED ->
                ReplacementOutcome.Started

            after.replaceRequested ->
                ReplacementOutcome.AlreadyInFlight

            else ->
                ReplacementOutcome.Unavailable("the replacement could not be started")
        }
    }

    /**
     * Enqueue a Worker for an episode.
     * Uses ExistingWorkPolicy.KEEP — only blocks if work is unfinished.
     *
     * Phase 13 (DECISIONS 105) + Phase 14 (PHASE14_PLAN §9): suspends to read
     * the CURRENT Wi-Fi-only and Auto-resume settings, so the WorkRequest
     * built here always reflects the persisted values at enqueue time (105.4).
     * `KEEP` still never replaces existing work (105.2): an already-enqueued
     * request keeps its original constraints.
     */
    suspend fun enqueueWorker(episodeId: String) {
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("episode_id" to episodeId))
            .apply { applyDownloadWorkPolicy(wifiOnlyProvider(), autoResumeProvider()) }
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "episode_download_$episodeId",
                ExistingWorkPolicy.KEEP,
                workRequest
            )

        AppLog.d(TAG, "Enqueued Worker for $episodeId")
    }

    /**
     * Cancel a Worker for an episode.
     */
    fun cancelWorker(episodeId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork("episode_download_$episodeId")

        AppLog.d(TAG, "Cancelled Worker for $episodeId")
    }
}
