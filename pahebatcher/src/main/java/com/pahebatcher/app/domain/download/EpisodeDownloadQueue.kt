package com.pahebatcher.app.domain.download

import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo

/**
 * One ALREADY-RESOLVED episode handed to the download pipeline (11.5E-4).
 *
 * Produced by the anime details entry after the
 * [com.pahebatcher.app.domain.stream.StreamResolver] (11.5E-3) returned a real
 * [StreamInfo]: everything the persistence layer needs to create
 * the parent session row, the episode row and the worker input — and nothing the
 * ViewModel would have to know about Room, WorkManager or directories.
 *
 * @param animeSession Anime session UUID (the parent download session).
 * @param animeTitle Anime title as the scan reported it.
 * @param host AnimePahe host the scan used.
 * @param quality Quality the stream was resolved with.
 * @param episode The selected episode, exactly as the scan produced it.
 * @param stream The resolved stream: playlist URL plus the headers the HLS
 *   engine must send (User-Agent and the Kwik page referrer).
 */
data class ResolvedEpisodeSubmission(
    val animeSession: String,
    val animeTitle: String,
    val host: String,
    val quality: Int,
    val episode: EpisodeInfo,
    val stream: StreamInfo,
)

/**
 * Domain-level entry into the EXISTING download pipeline (Phase 10).
 *
 * This is the seam the details ViewModel depends on (AGENTS §4 — the ViewModel
 * never sees Room, WorkManager or a directory), and the reason the hand-off can
 * be tested on the JVM: the production implementation is
 * `DownloadRepository.queueResolvedEpisode`, which performs the exact contract
 * the Phase 10 orchestration already owns — parent session row → episode row →
 * persisted stream info → unique `DownloadWorker` work.
 *
 * There is exactly ONE implementation in production. A second one may exist in
 * tests only.
 */
interface EpisodeDownloadQueue {

    /**
     * Queue one resolved episode: create/refresh its download session, persist
     * the episode row and its stream information, and enqueue the worker.
     *
     * Idempotent for an episode that is already queued or running — the pipeline
     * owns duplicate suppression, so a repeated call never starts a second
     * download of the same episode.
     *
     * **Slice B: the decision is reported, never assumed.** Every suppressed or
     * redirected path used to `return` silently while every caller still
     * reported success, so the user was told "added to the download queue" for
     * an episode the pipeline refused to touch. The returned
     * [EnqueueOutcome] states exactly what happened; callers must map it rather
     * than infer it. `Accepted` is only returned when the Worker hand-off
     * really completed.
     *
     * @return the outcome of THIS call — see [EnqueueOutcome] for the exact
     *   row-state → outcome mapping.
     * @throws Exception if persistence or work enqueueing genuinely fails; the
     *   caller reports that failure rather than pretending the episode is
     *   queued. A non-throwing return is therefore the only source of an
     *   [EnqueueOutcome].
     */
    suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome

    /**
     * Does this episode already have a live/finished download row
     * whose output is still present?
     *
     * The batch and watchlist resolvers call this BEFORE resolving a stream, so
     * an episode that is queued, running or completed is never re-resolved
     * (Python `_find_existing` + the `shouldSuppressResubmission` skip — an
     * already-finished episode costs no Cloudflare/Kwik round-trip, AGENTS §10).
     *
     * The default is the honest "no knowledge" answer for a queue that does not
     * track rows: `false` means "go ahead and resolve", which is always safe —
     * [queueResolvedEpisode] would suppress the duplicate anyway. The production
     * implementation (`DownloadRepository`) overrides it with the real row state.
     */
    suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo): Boolean = false

    /**
     * Does this episode's OUTPUT actually exist (exported URI or a
     * non-empty local file) — Python's `tmp_orch._find_existing(ep) is not None`
     * (`downloader.py`, used by `watchlist.py:1015-1019`)?
     *
     * [isEpisodeAlreadyHandled] alone is NOT enough for that decision: it is also
     * `true` for an in-flight row (`RESOLVING`/`DOWNLOADING`/`ASSEMBLING` or a
     * queued stream) that has produced no file yet. Only a real output may enter
     * an entry's deleted-skip history — otherwise a download that later FAILED or
     * was CANCELLED would be treated as deliberately deleted and never retried
     * (review finding H-1).
     *
     * The default is the conservative `false`: a queue with no output knowledge
     * must never claim a file exists, so it simply does not backfill.
     */
    suspend fun isEpisodeOutputPresent(animeSession: String, episode: EpisodeInfo): Boolean = false

    /**
     * How many episodes are RESOLVED but not yet started by a Worker
     * — the Android equivalent of Python's staging queue length, whose capacity
     * is `resolve_ahead + max_parallel` (`downloader.py:205-208`).
     *
     * The batch resolver awaits free capacity against this count before resolving
     * the next episode, so `resolve_ahead` really bounds how far stream
     * resolution runs ahead of downloading (Python's only consumption point for
     * that key).
     *
     * The default is `0` ("nothing staged"), which never blocks a caller — safe
     * for any queue that does not implement the count; the production
     * implementation (`DownloadRepository`) reports the real Room count.
     */
    suspend fun resolvedAheadCount(): Int = 0
}
