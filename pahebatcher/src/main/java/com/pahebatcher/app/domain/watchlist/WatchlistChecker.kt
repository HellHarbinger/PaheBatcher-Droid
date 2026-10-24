package com.pahebatcher.app.domain.watchlist

import androidx.compose.runtime.Immutable
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.data.repository.SearchOutcome
import com.pahebatcher.app.data.repository.WatchlistRepository
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolveAheadGate
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.variantOrCarrier
import com.pahebatcher.app.domain.stream.StreamResolutionRetry
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.domain.util.EpisodePaging
import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * NET-005: compiled once per FILE instead of twice per `normalizeTitle` call —
 * the normalization runs once per watchlist entry on every check.
 */
private val TITLE_STRIP_NON_ALNUM = Regex("[^a-z0-9\\s]")
private val TITLE_COLLAPSE_WHITESPACE = Regex("\\s+")

/**
 * Outcome of ONE watchlist entry check — the Android counterpart of the rows
 * Python prints per entry (`watchlist.py:1088-1113`: title, new, done, failed,
 * error) plus its relink/deleted states.
 */
data class WatchlistCheckResult(
    /** Anime session the check ran under (the entry's key AFTER a relink). */
    val sessionId: String,
    /** Title as it stands after the check (refreshed by a live scan). */
    val title: String,
    val status: WatchlistCheckStatus,
    /** Distinct episode numbers the live listing reported. */
    val episodesTotal: Int = 0,
    /** Episodes that were neither on disk nor covered by the skip history. */
    val newEpisodes: Int = 0,
    /** New episodes actually handed to the download pipeline. */
    val queued: Int = 0,
    /**
     * Slice B (DECISIONS 146): pending episodes the pipeline did NOT start a
     * Worker for ([EnqueueOutcome]).
     *
     * Two different things land here, and the counter deliberately does not
     * tell them apart — the summary wording is phrased so it is honest about
     * both:
     *
     *  * **already covered** — `AlreadyActive`, `AlreadyDownloaded`,
     *    `AlreadyPaused`: queued, resolving, downloading, assembling, paused or
     *    already on disk, so nothing is owed;
     *  * **declined outright** — [EnqueueOutcome.Refused]: cancelled, failed or
     *    removed around the submission, so nobody owns the episode and no
     *    Worker exists. Calling that "already covered" would be false, which is
     *    why the summary says "not started" rather than a reason it cannot
     *    know.
     *
     * Counted apart from [queued] because neither is a new download, and apart
     * from [skippedDeleted] because nothing was deleted to cause them. Before
     * this slice they were folded into [queued], so a check that queued nothing
     * still reported "N new episode(s) queued".
     */
    val skippedExisting: Int = 0,
    /** Episodes the user deleted on purpose and that were therefore NOT re-downloaded. */
    val skippedDeleted: Int = 0,
    /** True when the dead link was replaced by exactly one matching session. */
    val relinked: Boolean = false,
    /** Honest detail text; `null` when there is nothing to report. */
    val message: String? = null,
)

/** See [WatchlistCheckResult]. */
enum class WatchlistCheckStatus {
    /** Scan succeeded and nothing new appeared. */
    UP_TO_DATE,
    /** Scan succeeded and new episodes were found (some may have failed to queue). */
    NEW_EPISODES,
    /** The session is dead and recovery found no single replacement. */
    LINK_DEAD,
    /** The session is dead and the stored/cached title is unusable for a search. */
    UNKNOWN_TITLE,
    /** The session is dead and more than one session matches the title exactly. */
    AMBIGUOUS,
    /** The scan itself failed (network/session error) — the entry is isolated. */
    FAILED,
}

/**
 * `run_watchlist_check` (`watchlist.py:685-1118`) as a use case.
 *
 * What it reproduces, with the reference line that defines the behavior:
 *
 * | behavior | Python |
 * |---|---|
 * | forced-fresh scan per entry (`cache_ttl = 0`) | `watchlist.py:732, 770-772` |
 * | dead link = 404/`Unknown Anime`/empty listing | `watchlist.py:797-801` |
 * | recovery only when the stored title is usable or history exists | `watchlist.py:802-812` |
 * | title recovered from the stored scan cache | `watchlist.py:39-56` |
 * | candidates = search, exact normalized title, current session excluded | `watchlist.py:59-94` |
 * | 1 candidate → relink (session/host/url only) + rescan, still-dead is an error | `watchlist.py:844-881` |
 * | 0 candidates / unusable title / 2+ candidates → distinct honest failures | `watchlist.py:882-935` |
 * | variant = STRICT `get_variant(num, audio)` — `any` ⇒ `defaultVariant`, a concrete value ⇒ that exact variant, unavailable ⇒ skip + recorded reason (diverges from `get_variant(num, audio_lang)` else first variant, `watchlist.py:1000-1008`) | `watchlist.py:1000-1008` |
 * | output present → counts as downloaded, backfilled into history | `watchlist.py:1015-1019` |
 * | folder exists + number in history → intentionally deleted → skipped | `watchlist.py:1021-1023` |
 * | folder gone + non-empty history → history reset, everything pending | `watchlist.py:987-996` |
 * | new episodes are auto-downloaded with the entry's own prefs | `watchlist.py:1049-1056` |
 * | history + `last_checked` persisted even when the check fails | `watchlist.py:779-780, 1081` |
 * | per-entry isolation: one entry failing never stops the run | `watchlist.py:773-782` |
 *
 * Android storage mapping: Python keeps one folder
 * per series, so "the output exists" is a file test there. Android's output
 * leaves `filesDir` (MediaStore export, §29), so [EpisodeDownloadQueue.isEpisodeAlreadyHandled]
 * is the "output exists (locally or exported) or is being produced" test, and the
 * per-series folder maps to the app download root [downloadRoot].
 *
 * Execution context: the scan goes through the WebView-backed HttpClient
 * (Cloudflare), which is **application-scoped** — `AndroidCloudflareSession`
 * builds `WebView(applicationContext)` on the main looper and never attaches it
 * to a window. Since Phase 12D (DECISIONS 91) the same use case therefore runs
 * from BOTH the UI (`WatchlistViewModel.checkNow`, Python's one-shot `wl check`
 * which the host schedules) and the background `WatchlistCheckWorker`
 * (Android's cron — `WatchlistCheckScheduler`), and both share this single
 * implementation. A Worker may use that transport; it may still never reach an
 * Activity, a UI lifecycle or a UI callback.
 */
class WatchlistChecker(
    private val episodeRepository: EpisodeRepository,
    private val animeRepository: AnimeRepository,
    private val scanCacheRepository: ScanCacheRepository,
    private val watchlistRepository: WatchlistRepository,
    private val streamResolver: StreamResolver,
    private val downloadQueue: EpisodeDownloadQueue,
    /** The app download root — Python's per-series output folder equivalent. */
    private val downloadRoot: () -> File,
    /**
     * Settings used by the check: `resolve_ahead` (global, like Python
     * `watchlist.py:731`). Per-entry prefs (quality/audio/max_parallel/…) come
     * from the entry itself, as in the reference.
     */
    private val configProvider: suspend () -> AppContext = { AppContext.defaults() },
) {

    /**
     * Check ONE entry. Never throws for a per-entry problem: every failure path
     * persists `last_checked` and returns an honest [WatchlistCheckResult].
     */
    suspend fun check(entry: WatchlistEntity): WatchlistCheckResult =
        checkEntry(entry, allowAutoRelink = true)

    /**
     * The standard check run immediately
     * after a USER-confirmed manual relink. Identical to [check] except the
     * dead-link branch: the new link was chosen by the user, so a dead page
     * after the migration is KEPT and reported (the automatic path's own
     * post-relink wording, 108.14) instead of triggering a second automatic
     * migration — never a silent repeated relink. A healthy page runs through
     * the normal title refresh / episode detection / queueing, so detection and
     * status refresh happen at once (108.13).
     */
    suspend fun checkRelinked(entry: WatchlistEntity): WatchlistCheckResult =
        checkEntry(entry, allowAutoRelink = false)

    private suspend fun checkEntry(
        entry: WatchlistEntity,
        allowAutoRelink: Boolean,
    ): WatchlistCheckResult {
        // Room is the source of truth (AGENTS §9): re-read the row so a caller's
        // stale snapshot can never overwrite a newer title/history on relink.
        var current = watchlistRepository.get(entry.sessionId) ?: entry
        var relinked = false
        var history = watchlistRepository.downloadedEpisodes(current.sessionId)
            ?.toMutableSet() ?: mutableSetOf()

        return try {
            var anime = scan(current)

            if (isDead(anime)) {
                if (!allowAutoRelink) {
                    // DECISIONS 108.14: the user chose this link manually —
                    // keep it and report exactly the automatic path's
                    // still-dead wording; never auto-migrate a user-confirmed
                    // session a second time.
                    return migratedStillDeadFailure(current)
                }
                if (!isDeadCandidate(current, history)) {
                    markChecked(current)
                    return failure(
                        current, WatchlistCheckStatus.LINK_DEAD,
                        "Link dead for '${current.title}' — no usable title or history to recover from.",
                    )
                }

                val searchTitle = recoverTitle(current)
                    ?: run {
                        markChecked(current)
                        return failure(
                            current, WatchlistCheckStatus.UNKNOWN_TITLE,
                            "Link dead for '${current.title}' — title unknown. Re-add it with the anime's URL.",
                        )
                    }

                val candidates = findCandidates(current, searchTitle)
                    ?: run {
                        // Search reached no host at all — an honest failure, never
                        // reported as "no session found" (an unreachable site is
                        // not proof that the anime is gone). Wording shared with
                        // the manual Relink dialog (DECISIONS 108.6).
                        markChecked(current)
                        return failure(
                            current, WatchlistCheckStatus.FAILED,
                            SEARCH_UNREACHABLE_MESSAGE,
                        )
                    }
                if (candidates.isEmpty()) {
                    markChecked(current)
                    return failure(
                        current, WatchlistCheckStatus.LINK_DEAD,
                        // Shared verbatim with the manual Relink dialog (108.6).
                        noSessionFoundMessage(current.title),
                    )
                }
                if (candidates.size > 1) {
                    markChecked(current)
                    return failure(
                        current, WatchlistCheckStatus.AMBIGUOUS,
                        "Link dead for '${current.title}' — ${candidates.size} candidates match that title, " +
                            "not auto-migrating.",
                    )
                }

                // Exactly one candidate → relink (history/prefs untouched) and
                // rescan; a migrated session that is still dead stays relinked
                // and reports the reference's error (`watchlist.py:880-881`).
                current = watchlistRepository.relink(current, candidates.first(), current.host)
                relinked = true
                anime = scan(current)
                if (isDead(anime)) {
                    return migratedStillDeadFailure(current)
                }
            }

            // An incomplete scan carries no episode inventory to trust: Python
            // warns and never caches one (`extract/scanner.py:295-302`), so this
            // pass must not be reported as a healthy check. Reporting
            // UP_TO_DATE - or auto-downloading - from a truncated listing would
            // present the partial scan as a completed one.
            if (!anime.completed) {
                markChecked(current)
                return failure(
                    current, WatchlistCheckStatus.FAILED,
                    "Scan for '${current.title}' ended early - some episode pages did not load. Check again.",
                )
            }

            // Title refresh: only a usable live title replaces the stored one.
            if (isUsableTitle(anime.title, current.animeSession) && anime.title != current.title) {
                current = watchlistRepository.addOrUpdate(current.copy(title = anime.title)).entry
            }

            // Whole-folder deletion = coherent "forget" signal (`watchlist.py:987-996`).
            val folderExists = downloadRoot().exists()
            if (!folderExists && history.isNotEmpty()) {
                history.clear()
                watchlistRepository.setDownloadedEpisodes(current.sessionId, emptyList())
                AppLog.d(TAG, "Download storage missing — reset skip history for ${current.animeSession}")
            }

            val numbers = anime.episodes.map { it.number }.distinct().sorted()
            val pending = mutableListOf<EpisodeInfo>()
            var skippedDeleted = 0
            var backfilled = false

            for (number in numbers) {
                // SPEC §22 / DECISIONS 144.4 — STRICT variant choice, no
                // substitution, and the listing-only SKIP is gone wherever the
                // number exists:
                //  * `any` (every entry added since the audio-variant slice)
                //    → the deterministic default variant (`jpn` when present,
                //    else the first available); unchanged (144.4).
                //  * a concrete value (legacy rows keep `jpn`/`eng`, and any
                //    stored open-set string is honored as written) → that exact
                //    variant; when the LISTING has no row for it but the number
                //    exists, the number's own row carries the REQUESTED audio
                //    as intent onto its real play page ([variantOrCarrier]) and
                //    the RESOLVER decides whether that page offers it. Release
                //    listing audio could never prove a play page absent, so the
                //    old pre-resolve skip (which silently dropped a reachable
                //    number) is superseded: an audio that really is missing now
                //    fails the ATTEMPT and is reported through the failed
                //    counter instead.
                //  * no variants at all → the number vanished (unchanged).
                val requestedAudio = current.audioLang
                val variant = if (requestedAudio == WatchlistEntity.AUDIO_ANY) {
                    anime.defaultVariant(number)
                } else {
                    variantOrCarrier(anime.episodes, number, requestedAudio)
                }
                if (variant == null) {
                    // The number itself is gone from the listing — unchanged.
                    continue
                }

                if (downloadQueue.isEpisodeAlreadyHandled(current.animeSession, variant)) {
                    // Python only backfills from a file that is REALLY on disk
                    // (`tmp_orch._find_existing`, watchlist.py:1015-1019). An
                    // in-flight row (RESOLVING/DOWNLOADING/ASSEMBLING or a queued
                    // stream) has no output yet, so recording it here would poison
                    // the deleted-skip history below: if that download then FAILED
                    // or was CANCELLED, `folderExists && in history` would treat it
                    // as deliberately deleted and never retry it.
                    if (downloadQueue.isEpisodeOutputPresent(current.animeSession, variant)) {
                        if (history.add(number.toDouble())) backfilled = true
                    }
                    continue
                }
                if (folderExists && number.toDouble() in history) {
                    // Deliberately deleted → never re-downloaded.
                    skippedDeleted++
                    continue
                }
                pending += variant
            }
            if (backfilled) {
                watchlistRepository.setDownloadedEpisodes(current.sessionId, history.toList())
            }

            val queuedResult = autoDownload(current, anime, pending)

            markChecked(current)
            WatchlistCheckResult(
                sessionId = current.animeSession,
                title = current.title,
                status = when {
                    // The execution boundary refused the WHOLE batch (>100
                    // unique episodes): this run queued nothing, so reporting
                    // `NEW_EPISODES` (a healthy status) would hide it.
                    queuedResult.rejection != null -> WatchlistCheckStatus.FAILED
                    // An expired capacity wait means this run did NOT finish, so
                    // reporting `NEW_EPISODES` (a healthy status) would hide it —
                    // the message below carries the real reason.
                    queuedResult.capacityTimedOut -> WatchlistCheckStatus.FAILED
                    pending.isEmpty() -> WatchlistCheckStatus.UP_TO_DATE
                    else -> WatchlistCheckStatus.NEW_EPISODES
                },
                episodesTotal = numbers.size,
                newEpisodes = pending.size,
                queued = queuedResult.queued,
                skippedExisting = queuedResult.skippedExisting,
                skippedDeleted = skippedDeleted,
                relinked = relinked,
                message = queuedResult.rejection ?: summaryMessage(
                    episodeTotal = numbers.size,
                    pending = pending.size,
                    queued = queuedResult.queued,
                    skippedExisting = queuedResult.skippedExisting,
                    failed = queuedResult.failed,
                    skippedDeleted = skippedDeleted,
                    capacityTimedOut = queuedResult.capacityTimedOut,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Scan/parse failure: isolated to THIS entry, history untouched.
            AppLog.e(TAG, "Watchlist check failed for ${current.animeSession}: ${e.message}")
            markChecked(current)
            failure(
                current, WatchlistCheckStatus.FAILED,
                e.message?.takeIf { it.isNotBlank() } ?: "Check failed.",
            )
        }
    }

    /** Check every entry, sequentially and isolated — `watchlist.py:761-765`. */
    suspend fun checkAll(): List<WatchlistCheckResult> {
        val entries = watchlistRepository.observe().first()
        return entries.map { entry -> check(entry) }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Forced-fresh scan — `cache_ttl_watch = 0` (`watchlist.py:732`). */
    private suspend fun scan(entry: WatchlistEntity): AnimeInfo =
        episodeRepository.scan(entry.host, entry.animeSession, FRESH_TTL_MINUTES)

    /**
     * Resolve and queue the pending episodes with the entry's own preferences.
     *
     * `internal` so the batch ceiling can be exercised WITHOUT going through
     * the UI — this is the non-UI execution boundary, and it enforces the same
     * rule the details screen does: more than 100 UNIQUE episodes is refused as
     * a whole, never quietly cut down to the first 100.
     *
     * A refusal performs ZERO resolutions and ZERO queue submissions and says
     * so through [AutoDownloadOutcome.rejection], which the caller surfaces as
     * an explicit failure instead of a healthy "new episodes" status.
     */
    internal suspend fun autoDownload(
        entry: WatchlistEntity,
        anime: AnimeInfo,
        pending: List<EpisodeInfo>,
    ): AutoDownloadOutcome {
        if (pending.isEmpty()) return AutoDownloadOutcome(0, 0, 0, false)

        // The batch execution boundary, reached from the watchlist path — i.e.
        // with no screen and no selection state anywhere in the call chain.
        val rejection = EpisodePaging.validateBatch(pending.map { it.number })
        if (rejection != null) {
            AppLog.w(TAG, "Auto-download refused: ${rejection.message}")
            return AutoDownloadOutcome(
                queued = 0,
                skippedExisting = 0,
                // Nothing "exhausted its attempts": the batch as a whole was
                // refused, which [rejection] states explicitly.
                failed = 0,
                capacityTimedOut = false,
                rejection = rejection.message,
            )
        }

        val resolveAhead = resolveAheadCapacity()
        val capacity = resolveAhead + entry.maxParallel
        val maxAttempts = entry.autoRetry + 1
        var queued = 0
        var skippedExisting = 0
        var failed = 0
        var capacityTimedOut = false
        var index = 0

        while (index < pending.size) {
            val variant = pending[index]
            if (!ResolveAheadGate.awaitCapacity(downloadQueue, capacity)) {
                // Bounded: report the rest honestly instead of waiting forever.
                failed += pending.size - index
                capacityTimedOut = true
                break
            }
            try {
                val stream = StreamResolutionRetry.resolveWithRetry(
                    resolver = streamResolver,
                    episode = variant,
                    quality = entry.quality,
                    maxAttempts = maxAttempts,
                )
                val outcome = downloadQueue.queueResolvedEpisode(
                    ResolvedEpisodeSubmission(
                        animeSession = entry.animeSession,
                        animeTitle = anime.title,
                        host = entry.host,
                        quality = entry.quality,
                        episode = variant,
                        stream = stream,
                    ),
                )
                // Slice B: the pipeline decides, this accounting only reports it.
                // `Accepted` and `OutputMissing` both mean a Worker was handed
                // this episode (the second is the legitimate re-download of an
                // export the user deleted), so both are "queued". Everything
                // else means no new download started, and inflating `queued`
                // with it would claim work the app never performed.
                if (outcome == EnqueueOutcome.Accepted ||
                    outcome == EnqueueOutcome.OutputMissing
                ) {
                    queued++
                } else {
                    skippedExisting++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Isolated: one episode failing never stops the remaining ones.
                AppLog.e(TAG, "Failed to queue Ep ${variant.epStr}: ${e.message}")
                failed++
            }
            index++
        }
        return AutoDownloadOutcome(queued, skippedExisting, failed, capacityTimedOut)
    }

    internal data class AutoDownloadOutcome(
        val queued: Int,
        /** Slice B: pending episodes the pipeline already covered. */
        val skippedExisting: Int,
        val failed: Int,
        val capacityTimedOut: Boolean,
        /**
         * Non-null only when the WHOLE batch was refused at the execution
         * boundary (more than 100 unique episodes) — in which case nothing was
         * resolved, nothing was queued, and the caller must report a failure
         * rather than a healthy run.
         */
        val rejection: String? = null,
    )

    private suspend fun resolveAheadCapacity(): Int = try {
        configProvider().resolveAhead
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e(TAG, "Settings read failed; using default resolve_ahead: ${e.message}")
        DEFAULT_RESOLVE_AHEAD
    }

    /** Python: `anime_is_dead = title == "Unknown Anime" or 404-title or no episodes`. */
    private fun isDead(anime: AnimeInfo): Boolean =
        anime.title == UNKNOWN_TITLE || is404Title(anime.title) || anime.episodes.isEmpty()

    /** Python `_is_404_title` (`watchlist.py:34-36`). */
    private fun is404Title(title: String): Boolean {
        val low = title.lowercase()
        return low.contains("404") || low.contains("oops") || low.contains("not found")
    }

    /** Python `is_dead_candidate` (`watchlist.py:802-812`). */
    private fun isDeadCandidate(entry: WatchlistEntity, history: Set<Double>): Boolean =
        isUsableTitle(entry.title, entry.animeSession) || history.isNotEmpty()

    /** A title a search can actually use (Python's unknown-title rule, `watchlist.py:73-79`). */
    private fun isUsableTitle(title: String, session: String): Boolean =
        title.isNotBlank() && title != session && title != UNKNOWN_TITLE && !is404Title(title)

    /**
     * Which title the dead-link search uses — the reference's exact precedence:
     *
     * ```python
     * search_title = title                                   # watchlist.py:68
     * if _is_404_title(search_title):                        # watchlist.py:69-72
     *     recovered = _recover_title_from_cache(...)
     *     if recovered: search_title = recovered
     * if search_title unsearchable: return "unknown_title"   # watchlist.py:73-79
     * ```
     *
     * The **stored title always wins** and the scan cache is consulted ONLY when
     * that title is 404-style. (This used to be reversed, so a stale cached title
     * could shadow a perfectly good stored one — review finding M-1.)
     */
    private suspend fun recoverTitle(entry: WatchlistEntity): String? =
        if (is404Title(entry.title)) {
            scanCacheRepository.cachedTitle(entry.animeSession)
                ?.takeIf { isUsableTitle(it, entry.animeSession) }
        } else {
            entry.title.takeIf { isUsableTitle(it, entry.animeSession) }
        }

    /**
     * The SHARED candidate core (DECISIONS 108.4): `_find_single_new_session`
     * (`watchlist.py:59-94`) — host-rotating search, current session excluded,
     * exact NORMALIZED title match. The automatic relink path maps this to
     * sessions ([findCandidates]); the manual Relink dialog presents the SAME
     * rows with the metadata the existing search row carries (108.5), so the
     * app holds exactly ONE search+filter implementation.
     *
     * @return candidate rows (0 / 1 / many), or `null` when no host could be
     *   reached — the caller reports that as an unreachable search, not as "no
     *   session found" (`AnimeRepository.searchWithOutcome`).
     */
    suspend fun findCandidateRows(entry: WatchlistEntity, title: String): List<RelinkCandidate>? {
        val rows = when (val outcome = animeRepository.searchWithOutcome(entry.host, title)) {
            is SearchOutcome.Results -> outcome.results
            SearchOutcome.Unreachable -> return null
        }
        val wanted = normalizeTitle(title)
        return rows.mapNotNull { row ->
            val session = row["session"] as? String
            val candidateTitle = row["title"] as? String
            if (session.isNullOrBlank() || candidateTitle.isNullOrBlank()) return@mapNotNull null
            if (session == entry.animeSession) return@mapNotNull null
            if (normalizeTitle(candidateTitle) != wanted) return@mapNotNull null
            RelinkCandidate(
                session = session,
                title = candidateTitle,
                // Same whitelist as the existing search row: blank/0 renders as
                // nothing (SearchResultMapper's contract — mirrored here because
                // domain code must not import ui.mapper, AGENTS §4).
                type = (row["type"] as? String)?.takeIf { it.isNotBlank() },
                year = (row["year"] as? Int)?.takeIf { it > 0 },
                episodes = (row["episodes"] as? Int)?.takeIf { it > 0 },
                score = (row["score"] as? String)?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.session }
    }

    /**
     * The automatic path's session-only view of [findCandidateRows]
     * (DECISIONS 108.4 — shared, never a second search implementation).
     *
     * @return candidate sessions (0 / 1 / many), or `null` when unreachable.
     */
    private suspend fun findCandidates(entry: WatchlistEntity, title: String): List<String>? =
        findCandidateRows(entry, title)?.map { it.session }

    /** Python `_normalize_title` (`watchlist.py:30-31`): punctuation stripped, whitespace collapsed. */
    private fun normalizeTitle(title: String): String =
        title.lowercase()
            .replace(TITLE_STRIP_NON_ALNUM, "")
            .replace(TITLE_COLLAPSE_WHITESPACE, " ")
            .trim()

    private suspend fun markChecked(entry: WatchlistEntity) =
        watchlistRepository.markChecked(entry.sessionId)

    /**
     * The shared "migrated link is dead, the link was KEPT" outcome (the
     * reference's `watchlist.py:880-881`): used by the automatic path after its
     * own relink AND by the manual relink rescan (DECISIONS 108.14 points at
     * exactly this branch) — one wording, one behavior, one place.
     */
    private suspend fun migratedStillDeadFailure(
        current: WatchlistEntity,
    ): WatchlistCheckResult {
        markChecked(current)
        return failure(
            current,
            WatchlistCheckStatus.LINK_DEAD,
            "Migrated session for '${current.title}' is still dead — the new link was kept.",
            relinked = true,
        )
    }

    private fun failure(
        entry: WatchlistEntity,
        status: WatchlistCheckStatus,
        message: String,
        relinked: Boolean = false,
    ) = WatchlistCheckResult(
        sessionId = entry.animeSession,
        title = entry.title,
        status = status,
        relinked = relinked,
        message = message,
    )

    private fun summaryMessage(
        episodeTotal: Int,
        pending: Int,
        queued: Int,
        skippedExisting: Int,
        failed: Int,
        skippedDeleted: Int,
        capacityTimedOut: Boolean,
    ): String? {
        // DECISIONS 144.4: the audio-skip note is GONE with the listing-only
        // skip — an audio the play page does not carry fails the ATTEMPT and is
        // reported through `failed` above, so it is never hidden behind a green
        // message either.
        //
        // Slice B: when the pipeline refused an episode because it already
        // covers it, the run says so instead of reporting "0 new episode(s)
        // queued". The wording never calls such an episode "failed" (it is not)
        // and never claims it was queued. It also never names one specific
        // reason: `skippedExisting` absorbs every refusal — already
        // downloading, already downloaded, paused-by-user, and (review finding
        // F1) a hand-off refused by a concurrent cancel/failure/removal — so
        // the message lists them rather than asserting any single one. "not
        // started" is the honest superset for the last of those: no Worker was
        // ever registered for it, so "already downloading" would be a lie.
        return when {
            capacityTimedOut ->
                "Stopped waiting for download capacity after $queued queued — run the check again to retry."
            failed > 0 && queued > 0 ->
                "$failed of $pending failed to queue after $queued queued — run the check again to retry."
            failed > 0 ->
                "$failed of $pending failed to queue — run the check again to retry."
            queued > 0 && skippedExisting > 0 ->
                "$queued new episode(s) queued; $skippedExisting already downloading, downloaded, paused or not started."
            queued > 0 ->
                "$queued new episode(s) queued."
            skippedExisting > 0 ->
                "No new downloads — $skippedExisting already downloading, downloaded, paused or not started."
            pending > 0 ->
                "0 new episode(s) queued."

            skippedDeleted > 0 ->
                "Up to date — $episodeTotal episodes, $skippedDeleted skipped (deleted)."

            else -> "Up to date — $episodeTotal episodes."
        }
    }

    companion object {
        private const val TAG = "WatchlistCheck"

        /** Python forces a fresh scan for every watchlist check (`cache_ttl_watch = 0`). */
        const val FRESH_TTL_MINUTES = 0

        const val UNKNOWN_TITLE = "Unknown Anime"

        /** Python default (`config_manager.py:22`) used when settings cannot be read. */
        const val DEFAULT_RESOLVE_AHEAD = 999
    }
}

/**
 * One candidate replacement session from the SHARED search (DECISIONS 108.4):
 * `session` is the relink key; the remaining fields are exactly what the
 * existing search row carries (`AnimePaheParser.parseSearchResults`' whitelist,
 * rendered by `SearchResultRow`), so the manual Relink dialog shows real
 * metadata (108.5) and never invents a field.
 */
@Immutable
data class RelinkCandidate(
    val session: String,
    val title: String,
    /** `null` when the parser found nothing — never a fabricated value. */
    val type: String? = null,
    val year: Int? = null,
    val episodes: Int? = null,
    val score: String? = null,
)

/**
 * The "no session found" wording — ONE source for
 * the automatic dead-link report ([WatchlistChecker.check]) and the manual
 * Relink dialog, so both show the exact same honest sentence.
 */
internal fun noSessionFoundMessage(title: String): String =
    "Link dead for '$title' — no session found. Re-add it with the anime's URL."

/**
 * The unreachable-search wording — one source for
 * both paths. An unreachable site is never dressed up as "no session found",
 * because reachability says nothing about whether the anime exists.
 */
internal const val SEARCH_UNREACHABLE_MESSAGE: String =
    "Search unreachable — could not look up a replacement session."
