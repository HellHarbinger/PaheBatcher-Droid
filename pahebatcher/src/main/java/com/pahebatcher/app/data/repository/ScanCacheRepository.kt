package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.dao.ScanCacheDao
import com.pahebatcher.app.data.local.entity.ScanCacheEntity
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.AudioProbeResult
import com.pahebatcher.app.domain.model.AudioProbeState
import com.pahebatcher.app.domain.model.EpisodeInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Repository for scan cache operations.
 * Ported from Python scanner.py cache behavior.
 *
 * Uses Room database instead of file-based JSON cache.
 * Preserves Python's TTL-based cache expiration logic.
 */
class ScanCacheRepository(
    private val scanCacheDao: ScanCacheDao
) {
    /** JSON instance for serialization */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Get cached scan result by session UUID.
     * Returns null if not found or expired.
     *
     * ONLY a COMPLETE listing answers here. A row written by the lazy details
     * path (see [saveLazyListing]) holds the pages that path really read, so
     * serving it to a caller that needs a whole inventory would be exactly the
     * "partial cached as complete" mistake — those callers re-scan instead.
     *
     * @param session Anime session UUID
     * @param cacheTtlMinutes Cache TTL in minutes
     * @return AnimeInfo from cache or null
     */
    suspend fun getCachedScan(session: String, cacheTtlMinutes: Int): AnimeInfo? =
        readListing(session, cacheTtlMinutes)?.takeIf { !it.lazy }?.anime

    /**
     * Either listing kind for [session] — complete or lazy — as long as it is
     * still fresh. This is what the anime details screen opens with: a
     * complete row answers with everything (zero requests), a lazy row answers
     * with exactly the pages it stored, and an absent/expired row answers
     * null so the screen fetches page 1 again.
     *
     * TTL, expiry-delete and the "unparseable payload is dropped" rule are the
     * SAME ones [getCachedScan] applies.
     */
    suspend fun getListing(session: String, cacheTtlMinutes: Int): ListingRead? {
        val cached = readListing(session, cacheTtlMinutes) ?: return null
        return ListingRead(anime = cached.anime, pages = cached.pages)
    }

    /**
     * What the lazy open path gets back from the cache: the listing plus the
     * rows it stored PER PAGE.
     *
     * [pages] is empty for a COMPLETE row (every page is recoverable by
     * slicing [ListingRead.anime]) and holds the stored page index → rows for
     * a LAZY row, where the flat union alone could not say which rows belonged
     * to which page.
     */
    data class ListingRead(val anime: AnimeInfo, val pages: Map<Int, List<EpisodeInfo>>)

    /** One cached row as the cache itself reads it. */
    private data class CachedListing(
        val anime: AnimeInfo,
        val lazy: Boolean,
        val pages: Map<Int, List<EpisodeInfo>>,
    )

    /**
     * Persist a LAZY listing: only the pages that path really read, tagged so
     * [getCachedScan] refuses it.
     *
     * Never overwrites a COMPLETE row — a complete row already holds every
     * page, so replacing it with a subset would lose data.
     *
     * @return `true` when a row was written
     */
    suspend fun saveLazyListing(
        anime: AnimeInfo,
        cacheTtlMinutes: Int,
        pages: Map<Int, List<EpisodeInfo>> = emptyMap(),
    ): Boolean {
        if (cacheTtlMinutes <= 0) return false
        val existing = scanCacheDao.getBySession(anime.session)
        if (existing != null && !isLazyPayload(existing.episodesJson)) return false
        scanCacheDao.upsert(
            ScanCacheEntity(
                session = anime.session,
                title = anime.title,
                host = anime.host,
                totalEpisodes = anime.total,
                episodesJson = serializePayload(anime, lazyListing = true, pages = pages),
                cachedAt = System.currentTimeMillis(),
                cacheTtlMinutes = cacheTtlMinutes,
            ),
        )
        return true
    }

    private suspend fun readListing(session: String, cacheTtlMinutes: Int): CachedListing? {
        if (cacheTtlMinutes <= 0) return null

        val entity = scanCacheDao.getBySession(session) ?: return null

        // DATA-008: the entity owns the expiry rule (isExpired — Python's cache
        // TTL logic); this file's private re-computation of the same arithmetic
        // could drift from it.
        if (entity.isExpired()) {
            // Cache expired, delete it
            scanCacheDao.delete(session)
            return null
        }

        return try {
            val payload = deserializePayload(entity.episodesJson)
            CachedListing(
                anime = deserializeAnimeInfo(entity, payload),
                lazy = payload.lazy,
                pages = payload.pages,
            )
        } catch (e: Exception) {
            // Invalid cache data, delete it
            scanCacheDao.delete(session)
            null
        }
    }

    /**
     * Whether a payload was written by the LAZY path (only the pages it read)
     * rather than by a scan that read every page. Absent marker = complete,
     * which keeps every row written before this slice readable as before.
     */
    private fun isLazyPayload(raw: String): Boolean = try {
        val root = json.parseToJsonElement(raw)
        (root as? JsonObject)?.get("listing")
            ?.let { (it as? JsonPrimitive)?.contentOrNull } == "lazy"
    } catch (e: Exception) {
        false
    }

    /**
     * The cached title for a session, REGARDLESS of TTL — the Android
     * equivalent of Python `_recover_title_from_cache` (`watchlist.py:39-56`),
     * which globs `*_<session>/_scan_cache.json` and reads the stored title when
     * the live page now reports a 404.
     *
     * Read-only: unlike [getCachedScan] this never expires or deletes the entry,
     * because a dead link is exactly when the stale cache is still useful.
     * An unusable title ("Unknown Anime" / 404 markers / blank) is returned as-is
     * — the CALLER decides whether it is usable, matching the reference.
     */
    suspend fun cachedTitle(session: String): String? =
        scanCacheDao.getBySession(session)?.title?.takeIf { it.isNotBlank() }

    /**
     * Save scan result to cache.
     *
     * @param anime AnimeInfo to cache
     * @param cacheTtlMinutes Cache TTL in minutes
     */
    suspend fun saveScanCache(anime: AnimeInfo, cacheTtlMinutes: Int) {
        val entity = ScanCacheEntity(
            session = anime.session,
            title = anime.title,
            host = anime.host,
            totalEpisodes = anime.total,
            episodesJson = serializePayload(anime, lazyListing = false),
            cachedAt = System.currentTimeMillis(),
            cacheTtlMinutes = cacheTtlMinutes
        )
        scanCacheDao.upsert(entity)
    }

    /**
     * DECISIONS 144.5 — persist ONE probe result into the EXISTING cache row's
     * payload by read-modify-upsert.
     *
     * Everything except `episodesJson` is copied verbatim from the stored row:
     * `cachedAt` and `cacheTtlMinutes` are untouched, so a probe never resets,
     * extends or shortens scan freshness or the TTL, and no new persistence
     * subsystem (or Room migration) is introduced.
     *
     * @return `true` only when a row really existed and was rewritten; with no
     *   row (cache disabled, or the entry expired between scan and probe) the
     *   probe result is simply not persisted and the next load re-probes.
     */
    suspend fun saveAudioProbe(session: String, probe: AudioProbeResult): Boolean {
        val entity = scanCacheDao.getBySession(session) ?: return false
        val payload = try {
            rewriteProbe(entity.episodesJson, probe)
        } catch (e: Exception) {
            // A payload we cannot rewrite is left exactly as it is; the caller
            // still gets the in-memory result for this load.
            return false
        }
        scanCacheDao.upsert(entity.copy(episodesJson = payload))
        return true
    }

    /**
     * DECISIONS 144.5 — the persisted probe result for [session] REGARDLESS of
     * TTL (the same read-only shape as [cachedTitle]).
     *
     * Reading never expires or rewrites the row: a probe must not extend scan
     * freshness, and it must not shorten it either. `null` for a missing row,
     * an unparseable payload, or a payload that has never been probed.
     */
    suspend fun persistedAudioProbe(session: String): AudioProbeResult? =
        scanCacheDao.getBySession(session)?.let { entity ->
            try {
                deserializePayload(entity.episodesJson).probe
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Delete expired cache entries.
     *
     * @param cacheTtlMinutes Default TTL in minutes
     */
    suspend fun deleteExpired(cacheTtlMinutes: Int) {
        val expiryTime = System.currentTimeMillis() - (cacheTtlMinutes * 60L * 1000L)
        scanCacheDao.deleteExpired(expiryTime)
    }

    /**
     * Clear all cache entries.
     */
    suspend fun clearAll() {
        scanCacheDao.deleteAll()
    }

    /**
     * Serialize the whole scan payload: a wrapper OBJECT carrying the episodes,
     * the probe targets and (when a probe really ran) its result.
     *
     * The legacy top-level ARRAY shape is still READ (see [deserializePayload]);
     * nothing else in the app reads this column, so no migration is needed.
     */
    private fun serializePayload(
        anime: AnimeInfo,
        lazyListing: Boolean,
        pages: Map<Int, List<EpisodeInfo>> = emptyMap(),
    ): String {
        val payload = mutableMapOf<String, JsonElement>(
            "episodes" to episodesToJson(anime.episodes),
            "probe_targets" to JsonArray(anime.audioProbeTargets.map { JsonPrimitive(it) }),
        )
        anime.audioProbe?.let { payload["audio_probe"] = probeToJson(it) }
        // Lazy-loading slice: the source's own paginator numbers, read exactly
        // as the response reported them. Absent metadata is simply NOT written,
        // so an older payload and a payload whose response carried no `total`
        // both read back as null — never as 0 and never as a local guess.
        anime.apiTotal?.let { payload["api_total"] = JsonPrimitive(it) }
        anime.perPage?.let { payload["per_page"] = JsonPrimitive(it) }
        anime.apiLastPage?.let { payload["last_page"] = JsonPrimitive(it) }
        if (anime.variantSessions.isNotEmpty()) {
            payload["variant_sessions"] = JsonArray(anime.variantSessions.map { JsonPrimitive(it) })
        }
        // Which pages these rows really are. A lazy row carries the marker that
        // keeps it out of [getCachedScan], so a partial listing can never be
        // read back as a whole inventory — and it stores the rows PER PAGE, so
        // reopening after process death can answer page k without guessing which
        // rows of the flat union belonged to it.
        if (lazyListing) payload["listing"] = JsonPrimitive("lazy")
        if (anime.loadedPages.isNotEmpty()) {
            payload["loaded_pages"] = JsonArray(
                anime.loadedPages.sorted().map { JsonPrimitive(it) },
            )
        }
        if (lazyListing && pages.isNotEmpty()) {
            payload["pages"] = JsonObject(
                pages.entries
                    .sortedBy { it.key }
                    .associate { (index, rows) -> index.toString() to episodesToJson(rows) },
            )
        }
        return JsonObject(payload).toString()
    }

    /** One audio probe result — audio STRINGS only, never HTML or a stream URL. */
    private fun probeToJson(probe: AudioProbeResult): JsonObject = JsonObject(
        mapOf(
            "state" to JsonPrimitive(probe.state.name),
            "audios" to JsonArray(probe.audios.map { JsonPrimitive(it) }),
            "targets" to JsonPrimitive(probe.targets),
            "fetched" to JsonPrimitive(probe.fetched),
        )
    )

    private fun probeFromJson(obj: JsonObject): AudioProbeResult {
        val state = obj["state"]?.jsonPrimitive?.contentOrNull
            ?.let { name -> AudioProbeState.entries.firstOrNull { it.name == name } }
            ?: AudioProbeState.NOT_PROBED
        val audios = (obj["audios"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }
        return AudioProbeResult(
            state = state,
            audios = audios,
            targets = obj["targets"]?.jsonPrimitive?.intOrNull ?: 0,
            fetched = obj["fetched"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /** The columns the payload carries, in one parsed value. */
    private data class ScanPayload(
        val episodes: List<EpisodeInfo>,
        val probeTargets: List<String>,
        val probe: AudioProbeResult?,
        val apiTotal: Int?,
        val perPage: Int?,
        val lastPage: Int?,
        val variantSessions: List<String>,
        val loadedPages: List<Int>,
        val pages: Map<Int, List<EpisodeInfo>>,
        val lazy: Boolean,
    )

    /**
     * Parse the payload — wrapper OBJECT (current) or top-level ARRAY (legacy,
     * read unchanged as episodes with no targets and no probe result →
     * `NOT_PROBED`).
     *
     * The paginator keys are OPTIONAL: a payload written before this slice, or
     * one whose response carried no `total`, yields `null` for each — never 0
     * and never a locally derived figure.
     */
    private fun deserializePayload(raw: String): ScanPayload {
        val root = json.parseToJsonElement(raw)
        if (root is JsonArray) {
            return ScanPayload(
                episodes = episodesFrom(root),
                probeTargets = emptyList(),
                probe = null,
                apiTotal = null,
                perPage = null,
                lastPage = null,
                variantSessions = emptyList(),
                loadedPages = emptyList(),
                pages = emptyMap(),
                lazy = false,
            )
        }
        val obj = root as? JsonObject ?: error("scan cache payload is neither array nor object")
        val episodes = episodesFrom(
            obj["episodes"] as? JsonArray ?: error("scan cache payload has no episodes array")
        )
        val targets = (obj["probe_targets"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }.filter { it.isNotBlank() }
        val probe = (obj["audio_probe"] as? JsonObject)?.let { probeFromJson(it) }
        return ScanPayload(
            episodes = episodes,
            probeTargets = targets,
            probe = probe,
            apiTotal = (obj["api_total"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            perPage = (obj["per_page"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            lastPage = (obj["last_page"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            variantSessions = (obj["variant_sessions"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }.filter { it.isNotBlank() },
            loadedPages = (obj["loaded_pages"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            }.filter { it > 0 },
            // Per-page rows of a LAZY row — the flat `episodes` array stays as
            // the union for every legacy reader, this is what lets a recreated
            // process answer page k directly instead of guessing.
            pages = (obj["pages"] as? JsonObject)?.let { pageObject ->
                pageObject.entries.mapNotNull { (index, value) ->
                    val pageIndex = index.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                    val rows = value as? JsonArray ?: return@mapNotNull null
                    pageIndex to episodesFrom(rows)
                }.toMap()
            } ?: emptyMap(),
            // Absent marker = complete, which keeps every row written before
            // this slice readable exactly as it was.
            lazy = (obj["listing"] as? JsonPrimitive)?.contentOrNull == "lazy",
        )
    }

    /**
     * Rewrite ONLY the `audio_probe` member of an existing payload, preserving
     * `episodes` and `probe_targets` byte-for-byte (DECISIONS 144.5).
     */
    private fun rewriteProbe(raw: String, probe: AudioProbeResult): String {
        val root = json.parseToJsonElement(raw)
        val obj = when (root) {
            is JsonObject -> root
            is JsonArray -> JsonObject(mapOf("episodes" to root))
            else -> error("scan cache payload is neither array nor object")
        }
        if ("episodes" !in obj) error("scan cache payload has no episodes array")
        return JsonObject(
            obj.toMutableMap().apply { put("audio_probe", probeToJson(probe)) }
        ).toString()
    }

    private fun episodesFrom(array: JsonArray): List<EpisodeInfo> =
        array.map { element ->
            val obj = element.jsonObject
            EpisodeInfo(
                number = obj["number"]?.jsonPrimitive?.double?.toFloat() ?: 0f,
                session = obj["session"]?.jsonPrimitive?.contentOrNull ?: "",
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                fansub = obj["fansub"]?.jsonPrimitive?.contentOrNull ?: "",
                audio = obj["audio"]?.jsonPrimitive?.contentOrNull ?: "jpn",
                playUrl = obj["play_url"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }

    /** Serialize episodes list to JSON using kotlinx.serialization. */
    private fun episodesToJson(episodes: List<EpisodeInfo>): JsonArray = JsonArray(
        episodes.map { ep ->
            JsonObject(
                mapOf(
                    "number" to JsonPrimitive(ep.number.toDouble()),
                    "session" to JsonPrimitive(ep.session),
                    "title" to JsonPrimitive(ep.title),
                    "fansub" to JsonPrimitive(ep.fansub),
                    "audio" to JsonPrimitive(ep.audio),
                    "play_url" to JsonPrimitive(ep.playUrl)
                )
            )
        }
    )

    /**
     * Deserialize ScanCacheEntity to AnimeInfo — episodes, probe targets,
     * the persisted probe result (or `NOT_PROBED` for a legacy payload) and
     * the paginator metadata this slice added.
     *
     * @param lazy a LAZY row only ever holds the pages it read, so its page
     *   set is the one that was stored; a COMPLETE row holds everything, so
     *   with no explicit list its pages are every page the metadata reports
     *   (or none at all when the metadata is absent — which keeps paging off
     *   rather than guessing).
     */
    private fun deserializeAnimeInfo(entity: ScanCacheEntity, payload: ScanPayload): AnimeInfo =
        AnimeInfo(
            session = entity.session,
            title = entity.title,
            host = entity.host,
            total = entity.totalEpisodes,
            episodes = payload.episodes,
            audioProbeTargets = payload.probeTargets,
            audioProbe = payload.probe,
            // Lazy-loading slice: carried through unchanged, or left null when
            // the payload never had them (legacy row / unknown total).
            apiTotal = payload.apiTotal,
            perPage = payload.perPage,
            apiLastPage = payload.lastPage,
            variantSessions = payload.variantSessions,
            loadedPages = when {
                payload.lazy -> payload.loadedPages.toSet().ifEmpty { payload.pages.keys.toSet() }
                payload.loadedPages.isNotEmpty() -> payload.loadedPages.toSet()
                else -> payload.lastPage?.let { (1..it).toSet() } ?: emptySet()
            },
        )
}
