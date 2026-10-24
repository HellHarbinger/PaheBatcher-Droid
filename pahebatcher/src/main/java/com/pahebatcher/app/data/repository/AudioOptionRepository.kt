package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.remote.parser.PlayPageParser
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.AudioProbeResult
import com.pahebatcher.app.domain.model.AudioProbeState
import com.pahebatcher.app.domain.session.CloudflareSessionProvider
import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded play-page audio-option discovery (SPEC §22 "Audio selection (per
 * episode)", DECISIONS 144.2/144.3/144.5).
 *
 * The release listing only ever serves the site's default language per
 * episode, so it cannot be the source of the screen's option set. This
 * repository samples a FIXED, representative set of play pages — the targets
 * [AnimeInfo.audioProbeTargets] captured during the existing scan — and unions
 * the `data-audio` strings they expose.
 *
 * What it deliberately does NOT do:
 *
 * * no per-episode expansion — the target set is `merged sessions + 1` at most
 *   (DECISIONS 144.3: representative, not exhaustive; escalating to one request
 *   per episode number needs a new approval);
 * * no in-probe retries — a failed target stays failed for this attempt;
 * * no second HTTP stack, no new cache subsystem — one [Mutex]-serialised pass
 *   through the SAME session-aware page path the resolver uses, persisted in
 *   the EXISTING scan-cache row's JSON payload;
 * * no persistence of play-page HTML or stream URLs — audio strings only;
 * * no freshness change — [ScanCacheRepository.saveAudioProbe] rewrites only the
 *   probe member and copies `cachedAt`/`cacheTtlMinutes` verbatim.
 */
class AudioOptionRepository(
    private val sessionProvider: CloudflareSessionProvider,
    private val pageLock: Mutex,
    private val scanCacheRepository: ScanCacheRepository,
) {
    companion object {
        /** Pacing between two probe fetches (DECISIONS 144.2). */
        const val PACING_MS = 400L

        /** Per-request cap handed to the session-aware page path (DECISIONS 144.2). */
        const val REQUEST_TIMEOUT_MS = 30_000L

        private const val TAG = "AudioOptionProbe"
    }

    /**
     * Ensure [anime] carries an audio-option probe result, doing NO network
     * work when one is already completed.
     *
     * Rules (DECISIONS 144.5):
     *
     * * a COMPLETED cached result (`OK_VALUES` / `OK_EMPTY`) is returned as-is —
     *   zero requests, for the life of the scan-cache row;
     * * `PARTIAL` / `FAILED` re-probe — at most once per call, and the ViewModel
     *   calls this exactly once per user-driven load, so a load never probes
     *   twice;
     * * a row with no targets (a legacy payload, or a scan of a title whose rows
     *   carry no `play_url`) is never probed: it returns `NOT_PROBED` with zero
     *   requests rather than inventing a result;
     * * a probe that cannot be persisted (cache disabled or the row expired in
     *   between) still returns this attempt's real result — the next load simply
     *   probes again.
     */
    suspend fun ensureProbed(anime: AnimeInfo): AudioProbeResult {
        val cached = anime.audioProbe
        if (cached != null && cached.isCompleted) {
            return cached
        }
        val targets = anime.audioProbeTargets
        if (targets.isEmpty()) {
            return AudioProbeResult(state = AudioProbeState.NOT_PROBED)
        }
        return probe(anime.session, targets)
    }

    /**
     * One sequential probe pass: at most `targets.size` page requests, 400 ms
     * apart, through [pageLock] (the ONE lock the HTTP bridge and the resolver
     * already hold — page operations never interleave), 30 s per request.
     *
     * The union of `data-audio` values is first-seen, deduplicated and open-set.
     * Nothing else is retained: the HTML is dropped at the end of the iteration.
     */
    private suspend fun probe(session: String, targets: List<String>): AudioProbeResult {
        val audios = LinkedHashSet<String>()
        var fetched = 0

        pageLock.withLock {
            // DECISIONS 144.5 — two loads can overlap (a re-seed while the first
            // probe still holds the page lock). The first one to finish persists
            // its result, so the waiter re-reads the row instead of issuing the
            // same requests a second time: one probe pass per completion, never
            // one per waiting caller.
            val completedMeanwhile = scanCacheRepository.persistedAudioProbe(session)
            if (completedMeanwhile != null && completedMeanwhile.isCompleted) {
                AppLog.i(
                    TAG,
                    "Probe reused the completed result persisted by a concurrent load — " +
                        "0 additional request(s).",
                )
                return completedMeanwhile
            }

            for (index in targets.indices) {
                val page = sessionProvider.fetchPage(
                    url = targets[index],
                    waitForCloudflare = true,
                    timeoutMs = REQUEST_TIMEOUT_MS,
                )
                val html = if (page.success) page.html else null
                if (html != null) {
                    fetched++
                    for (entry in PlayPageParser.parseResolutionButtons(html)) {
                        if (entry.audio.isNotBlank()) audios += entry.audio
                    }
                }
                if (index < targets.lastIndex) delay(PACING_MS)
            }
        }

        val state = when {
            fetched == 0 -> AudioProbeState.FAILED
            fetched < targets.size -> AudioProbeState.PARTIAL
            audios.isEmpty() -> AudioProbeState.OK_EMPTY
            else -> AudioProbeState.OK_VALUES
        }
        val result = AudioProbeResult(
            state = state,
            audios = audios.toList(),
            targets = targets.size,
            fetched = fetched,
        )
        val persisted = scanCacheRepository.saveAudioProbe(session, result)
        // Counts and state only — never a URL, cookie or page body (AGENTS §10).
        AppLog.i(
            TAG,
            "Probe ${result.state} — ${result.fetched}/${result.targets} pages, " +
                "${result.audios.size} audio value(s), persisted=$persisted",
        )
        return result
    }
}
