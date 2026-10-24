package com.pahebatcher.app.data.hls

import com.pahebatcher.app.logging.AppLog
import java.util.concurrent.ConcurrentHashMap

/**
 * TTL-based cache for AES-128 encryption keys.
 *
 * Ported from Python cache.py TTLCache and downloader.py key caching.
 *
 * Python reference (cache.py lines 8-28):
 * ```python
 * class TTLCache:
 *     def __init__(self, ttl: float = 120.0, max_size: int = 512) -> None:
 *         self._data: dict[str, tuple[float, Any]] = {}
 *         self._ttl = ttl
 *         self._max = max_size
 *
 *     async def get(self, key: str) -> Any:
 *         async with self._lock:
 *             if entry := self._data.get(key):
 *                 if time.monotonic() - entry[0] < self._ttl:
 *                     return entry[1]
 *                 del self._data[key]
 *         return None
 * ```
 *
 * Python reference (downloader.py lines 43, 84-93):
 * ```python
 * self._aes_key_cache = aes_key_cache or TTLCache(ttl=3600.0, max_size=128)
 * # ...
 * key_map: dict[str, bytes] = {}
 * unique_keys: set[str] = {str(s["key_url"]) for s in segments if s["key_url"]}
 * for kurl in unique_keys:
 *     cached_any = await self._aes_key_cache.get(kurl)
 *     if isinstance(cached_any, bytes):
 *         key_map[kurl] = cached_any
 *     else:
 *         key_data = await self.http.get(kurl, hdrs)
 *         key_map[kurl] = key_data
 *         await self._aes_key_cache.set(kurl, key_data)
 * ```
 *
 * Key characteristics:
 * - 1-hour TTL (3600 seconds)
 * - Max 128 keys
 * - LRU eviction when full
 * - Thread-safe (ConcurrentHashMap)
 */
class AesKeyCache(
    private val ttlMs: Long = 3600_000L,
    private val maxSize: Int = 128
) {

    companion object {
        private const val TAG = "AesKeyCache"
    }

    private data class CacheEntry(
        val data: ByteArray,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Get a cached key by URL.
     * Returns null if not cached or expired.
     */
    fun get(keyUrl: String): ByteArray? {
        val entry = cache[keyUrl] ?: return null

        if (System.currentTimeMillis() - entry.timestamp >= ttlMs) {
            cache.remove(keyUrl)
            AppLog.d(TAG, "Key expired: $keyUrl")
            return null
        }

        return entry.data
    }

    /**
     * Cache a key by URL.
     * Evicts oldest entry if cache is full.
     */
    fun set(keyUrl: String, keyData: ByteArray) {
        // Evict oldest if at capacity
        if (cache.size >= maxSize) {
            val oldestKey = cache.entries
                .minByOrNull { it.value.timestamp }
                ?.key
            if (oldestKey != null) {
                cache.remove(oldestKey)
                AppLog.d(TAG, "Evicted oldest key: $oldestKey")
            }
        }

        cache[keyUrl] = CacheEntry(
            data = keyData,
            timestamp = System.currentTimeMillis()
        )
        AppLog.d(TAG, "Cached key: $keyUrl (${keyData.size} bytes)")
    }

    /**
     * Clear all cached keys.
     */
    fun clear() {
        cache.clear()
        AppLog.d(TAG, "Key cache cleared")
    }

    /**
     * Get the number of cached keys.
     */
    fun size(): Int = cache.size
}
