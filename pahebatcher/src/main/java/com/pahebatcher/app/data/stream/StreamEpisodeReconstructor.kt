package com.pahebatcher.app.data.stream

import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.model.EpisodeInfo

/**
 * The STREAM-mode reconstruction ladder that recovers the
 * episode's `playUrl` (and its identity) from persisted metadata.
 *
 * The streaming route deliberately carries ONLY `episodeId` + `PlayerMode.STREAM`
 * (never a play page URL, never a resolved `StreamInfo`), so the player has to
 * rebuild the episode it is about to stream. This class owns exactly that
 * ladder:
 *
 * 1. **Room** — the existing `episode_downloads` row for this id, if it carries a
 *    non-blank `playUrl`. Metadata reconstruction ONLY: the row's state is never
 *    inspected and never required to be `COMPLETED`, and nothing here writes to
 *    Room (AGENTS §3.1 — streaming must not create or depend on download state).
 * 2. **Scan cache** — the unexpired cached scan entry for the anime session,
 *    matched on `(epStr, audio)`. No network access: [cachedEpisode] is wired to
 *    `ScanCacheRepository.getCachedScan`, whose own TTL check already drops an
 *    expired entry and deletes it.
 * 3. **Network scan** — `EpisodeRepository.scan` for the anime session, again
 *    matched on `(epStr, audio)`. This is the ONLY step that touches the network
 *    and it is reachable exclusively from the STREAM branch of
 *    `planPlayerLoad`, which is itself only entered after [EpisodeId.parse] has
 *    accepted the id — a malformed/garbage route argument never gets here
 *    (AGENTS §10).
 * 4. **Honest failure** — [Result.Unrecoverable]; the caller renders the
 *    stream-specific message. No URL is ever invented.
 *
 * The three sources are injected as functions so the ladder itself is
 * deterministic and unit-testable without a database, a cache or a network.
 */
class StreamEpisodeReconstructor(
    /** Step 1 — Room row → episode identity + `playUrl` (never state-dependent). */
    private val roomEpisode: suspend (episodeId: String) -> EpisodeInfo?,
    /** Step 2 — unexpired scan-cache entry; MUST NOT perform network I/O. */
    private val cachedEpisode: suspend (
        animeSession: String,
        episodeString: String,
        audio: String,
    ) -> EpisodeInfo?,
    /** Step 3 — `EpisodeRepository.scan` network path; reached ONLY in STREAM mode. */
    private val scannedEpisodes: suspend (animeSession: String) -> List<EpisodeInfo>,
) {

    sealed interface Result {
        /** The episode identity + `playUrl` the player will resolve with. */
        data class Recovered(val episode: EpisodeInfo) : Result

        /** Nothing could be reconstructed — never a substitute URL, never a guess. */
        data object Unrecoverable : Result
    }

    /**
     * Run the ladder for one well-formed episode id (already shape-checked by
     * [EpisodeId.parse]).
     *
     * Order is strict: a Room hit short-circuits the cache, and a cache hit
     * short-circuits the network scan — so no network request is made when
     * persisted metadata already answers the question.
     */
    suspend fun reconstruct(
        episodeId: String,
        parts: EpisodeId.Parts,
    ): Result {
        roomEpisode(episodeId)?.let { return Result.Recovered(it) }
        cachedEpisode(parts.animeSession, parts.episodeString, parts.audio)
            ?.let { return Result.Recovered(it) }
        scannedEpisodes(parts.animeSession)
            .firstOrNull { it.epStr == parts.episodeString && it.audio == parts.audio }
            ?.let { return Result.Recovered(it) }
        return Result.Unrecoverable
    }
}
