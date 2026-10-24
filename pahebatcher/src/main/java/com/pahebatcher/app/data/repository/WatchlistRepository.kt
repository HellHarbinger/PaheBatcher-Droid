package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.dao.WatchlistDao
import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.data.remote.parser.AnimePaheParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Result of [WatchlistRepository.addOrUpdate]: whether the entry was newly
 * inserted, plus the entry AS STORED (with Python's preservation rules applied).
 */
data class WatchlistAddResult(
    val isNew: Boolean,
    val entry: WatchlistEntity,
)

/**
 * Phase 12A backend/data foundation — watchlist persistence over Room, the
 * Android equivalent of Python `watchlist.json` + `WatchlistManager`
 * (`watchlist.py` 3.6.0).
 *
 * Behavioral parity (each method names the Python function it mirrors):
 *
 * - [addOrUpdate] = `WatchlistManager.add_entry`: an existing entry with the
 *   same session keeps its `added_at`, its downloaded history, and its real
 *   title (Python only replaces the title when the incoming one is the
 *   session-UUID placeholder); a new session is appended. `lastChecked` is NOT
 *   preserved — Python rebuilds the entry with `last_checked=None` too.
 * - [find] = `WatchlistManager.find_entry`: identifier resolution in Python's
 *   exact order — 1-based numeric index → URL parsed for its session UUID →
 *   exact session/URL match → case-insensitive substring over title/URL/session.
 * - [remove] = `WatchlistManager.remove_entry`: resolve via [find], then delete.
 * - [decodeDownloaded] = `WatchlistEntry.from_dict`'s `downloaded` parsing:
 *   corrupted JSON yields an empty list and non-numeric entries are skipped,
 *   never an exception.
 * - [observe]/[getAll] replace `WatchlistManager.load` (which returned `[]`
 *   for a missing/corrupt file — Room simply holds no rows).
 *
 * The table already exists in schema v3; this class adds NO schema, migration,
 * or write path outside the existing DAO. UI consumption arrives with the
 * watchlist screen phase.
 */
class WatchlistRepository(
    private val watchlistDao: WatchlistDao,
) {

    /** All entries, newest first (the DAO's `addedAt DESC` order). */
    fun observe(): Flow<List<WatchlistEntity>> = watchlistDao.getAll()

    /** One entry by its primary key (the anime session UUID). */
    suspend fun get(sessionId: String): WatchlistEntity? =
        watchlistDao.getBySessionId(sessionId)

    suspend fun count(): Int = watchlistDao.count()

    suspend fun exists(sessionId: String): Boolean =
        watchlistDao.getBySessionId(sessionId) != null

    /**
     * Add or update an entry — Python `WatchlistManager.add_entry`.
     *
     * @return `isNew = true` when the session was not on the watchlist yet;
     *   `entry` is always the stored row after the preservation rules applied.
     */
    suspend fun addOrUpdate(entry: WatchlistEntity): WatchlistAddResult {
        val existing = watchlistDao.getBySessionId(entry.sessionId)
            ?: run {
                watchlistDao.upsert(entry)
                return WatchlistAddResult(isNew = true, entry = entry)
            }

        // Python add_entry(): keep added_at, downloaded history and the real title.
        val title = if (existing.title.isNotBlank() &&
            existing.title != existing.sessionId &&
            entry.title == entry.sessionId
        ) {
            existing.title
        } else {
            entry.title
        }
        val downloaded = if (isDownloadedEmpty(entry.downloadedEpisodesJson) &&
            !isDownloadedEmpty(existing.downloadedEpisodesJson)
        ) {
            existing.downloadedEpisodesJson
        } else {
            entry.downloadedEpisodesJson
        }
        val merged = entry.copy(
            title = title,
            addedAt = existing.addedAt,
            downloadedEpisodesJson = downloaded,
        )
        watchlistDao.upsert(merged)
        return WatchlistAddResult(isNew = false, entry = merged)
    }

    /**
     * Phase 12C relink — Python `_relink_entry_via_search`
     * (`watchlist.py:97-118`): only `session`, `host` and `url` change.
     *
     * On Android the anime session IS the primary key, so the entry has to move:
     * the old row is deleted and the SAME entry (addedAt, real title, downloaded
     * history, per-entry preferences — `watchlist.py:109-116`) is written under
     * the new key. History and preferences are never touched by a relink.
     *
     * @return the stored entry under its new session.
     */
    suspend fun relink(
        entry: WatchlistEntity,
        newSession: String,
        newHost: String,
    ): WatchlistEntity {
        val target = entry.copy(
            sessionId = newSession,
            animeSession = newSession,
            host = newHost,
            url = "https://$newHost/anime/$newSession",
        )
        // DATA-001: one transaction — the delete + reinsert must be all-or-nothing,
        // or an interruption between them permanently loses the watchlist entry.
        watchlistDao.relink(entry.sessionId, target)
        return target
    }

    /**
     * Resolve [identifier] against the stored entries — Python
     * `WatchlistManager.find_entry`, in the SAME precedence order.
     *
     * Ordering parity (reviewer H2): Python's `watchlist.json` is a JSON list
     * appended in time order, so its 1-based index `1` means the FIRST APPENDED
     * (oldest) entry and every match loop scans oldest-first
     * (`watchlist.py:196-232`). Room returns `addedAt DESC` for display, so
     * resolution runs against an insertion-order (`addedAt ASC`) projection and
     * the returned [IndexedValue.index] is the position in THAT order — the
     * same meaning Python's index has. [observe] keeps its newest-first display
     * order.
     *
     * @return the entry with its insertion-order index, or `null` when nothing
     *   matches.
     */
    suspend fun find(identifier: String): IndexedValue<WatchlistEntity>? {
        val ident = identifier.trim()
        val all = watchlistDao.getAll().first()
        if (all.isEmpty()) return null
        // Insertion order = oldest first = Python's file/list order. (Ties on
        // addedAt keep the DAO's relative order — addedAt is preserved on
        // update, so ties are not the normal case.)
        val entries = all.sortedBy { it.addedAt }

        // 1. numeric 1-based index
        if (ident.toIntOrNull() != null && ident.all { it.isDigit() }) {
            val idx = ident.toInt() - 1
            if (idx in entries.indices) return IndexedValue(idx, entries[idx])
        }

        // 2. URL → session UUID
        val urlSession = runCatching { AnimePaheParser.parseAnimeUrl(ident).second }.getOrNull()
        if (urlSession != null) {
            entries.forEachIndexed { i, e ->
                if (e.animeSession == urlSession || e.sessionId == urlSession) {
                    return IndexedValue(i, e)
                }
            }
        }

        // 3. exact session UUID or URL
        entries.forEachIndexed { i, e ->
            if (e.sessionId == ident || e.url == ident) return IndexedValue(i, e)
        }

        // 4. case-insensitive substring over title / URL / session
        val low = ident.lowercase()
        entries.forEachIndexed { i, e ->
            if (low in e.title.lowercase() ||
                low in e.url.lowercase() ||
                low in e.sessionId.lowercase()
            ) {
                return IndexedValue(i, e)
            }
        }
        return null
    }

    /** Remove the entry resolved by [identifier] — Python `remove_entry`. */
    suspend fun remove(identifier: String): WatchlistEntity? {
        val found = find(identifier) ?: return null
        watchlistDao.delete(found.value.sessionId)
        return found.value
    }

    /** Remove a specific entry by session UUID. */
    suspend fun removeBySession(sessionId: String): Boolean {
        val existing = watchlistDao.getBySessionId(sessionId) ?: return false
        watchlistDao.delete(sessionId)
        // DATA-014: `existing` was fetched BY this key, so the row that was
        // removed is necessarily the one requested — the check is vacuous.
        return true
    }

    /** Python: record `last_checked` after a watchlist check. */
    suspend fun markChecked(
        sessionId: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        watchlistDao.updateLastChecked(sessionId, timestamp)
    }

    /**
     * Replace the downloaded-episode history (Python `entry.downloaded`).
     * Empty history is stored as the `[]` default, exactly like `to_dict`.
     */
    suspend fun setDownloadedEpisodes(sessionId: String, episodes: List<Double>) {
        watchlistDao.updateDownloadedEpisodes(sessionId, encodeDownloaded(episodes))
    }

    /** Decode a row's downloaded history; `null` when the row does not exist. */
    suspend fun downloadedEpisodes(sessionId: String): List<Double>? =
        watchlistDao.getBySessionId(sessionId)
            ?.let { decodeDownloaded(it.downloadedEpisodesJson) }

    /** Python `WatchlistEntry.is_stale` (cache TTL driven). */
    suspend fun isStale(sessionId: String, cacheTtlMinutes: Int): Boolean? =
        watchlistDao.getBySessionId(sessionId)?.isStale(cacheTtlMinutes)

    /** Delete a single entry by primary key (no identifier resolution). */
    suspend fun delete(sessionId: String) = watchlistDao.delete(sessionId)

    /** Delete every entry (watchlist reset). */
    suspend fun deleteAll() = watchlistDao.deleteAll()

    companion object {

        /** JSON codec for the downloaded-episode history (never persisted elsewhere). */
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Encode episode numbers — Python `json.dumps([e.downloaded ...])`.
         * Doubles keep Python's float history semantics (e.g. `13.5` specials).
         */
        fun encodeDownloaded(episodes: List<Double>): String =
            Json.encodeToString(
                JsonArray.serializer(),
                JsonArray(episodes.map { JsonPrimitive(it) }),
            )

        /**
         * Decode episode numbers — Python `WatchlistEntry.from_dict`:
         * corrupted/unparseable JSON → empty list; non-numeric elements are
         * SKIPPED individually, never thrown. Numeric strings count, mirroring
         * Python's `float(v)` (which accepts `str` and `float` and raises for
         * anything else).
         */
        fun decodeDownloaded(raw: String): List<Double> = try {
            if (raw.isBlank()) emptyList()
            else json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                // Python `float(v)`: parses numeric primitives and numeric strings,
                // and raises (here: skipped) for everything else.
                (element as? JsonPrimitive)?.content?.toDoubleOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }

        /** True when the stored history holds no episode (Python `not downloaded`). */
        fun isDownloadedEmpty(json: String): Boolean = decodeDownloaded(json).isEmpty()
    }
}
