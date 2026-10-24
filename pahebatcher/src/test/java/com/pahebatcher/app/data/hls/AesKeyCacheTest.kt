package com.pahebatcher.app.data.hls

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for AesKeyCache — TTL-based key caching.
 *
 * Ported from Python cache.py TTLCache.
 */
class AesKeyCacheTest {

    private lateinit var cache: AesKeyCache

    @Before
    fun setup() {
        cache = AesKeyCache(ttlMs = 1000L, maxSize = 5) // 1 second TTL for testing
    }

    // ── Basic Operations ──────────────────────────────────────────────

    @Test
    fun `get returns null for missing key`() {
        assertNull(cache.get("https://missing.key"))
    }

    @Test
    fun `set and get work correctly`() {
        val keyData = ByteArray(16) { 0x42 }
        cache.set("https://key.url", keyData)

        val cached = cache.get("https://key.url")
        assertNotNull(cached)
        assertArrayEquals(keyData, cached)
    }

    @Test
    fun `size returns correct count`() {
        assertEquals(0, cache.size())

        cache.set("https://key1.url", ByteArray(16))
        assertEquals(1, cache.size())

        cache.set("https://key2.url", ByteArray(16))
        assertEquals(2, cache.size())
    }

    // ── TTL Expiration ────────────────────────────────────────────────

    @Test
    fun `get returns null after TTL expiration`() {
        cache.set("https://key.url", ByteArray(16))

        // Key should be available immediately
        assertNotNull(cache.get("https://key.url"))

        // Wait for TTL to expire
        Thread.sleep(1100)

        // Key should be expired
        assertNull(cache.get("https://key.url"))
    }

    @Test
    fun `expired key is removed from cache`() {
        cache.set("https://key.url", ByteArray(16))
        assertEquals(1, cache.size())

        Thread.sleep(1100)

        // Accessing expired key should remove it
        cache.get("https://key.url")
        assertEquals(0, cache.size())
    }

    // ── LRU Eviction ──────────────────────────────────────────────────

    // ── Clear ─────────────────────────────────────────────────────────

    @Test
    fun `clear removes all entries`() {
        cache.set("https://key1.url", ByteArray(16))
        cache.set("https://key2.url", ByteArray(16))
        assertEquals(2, cache.size())

        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("https://key1.url"))
    }

    // ── Thread Safety ─────────────────────────────────────────────────

    @Test
    fun `concurrent set and get operations are safe`() {
        // Use a cache with sufficient capacity to avoid eviction during this test
        val concurrentCache = AesKeyCache(ttlMs = 60_000L, maxSize = 20)
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(20)

        // Concurrent writes
        for (i in 1..10) {
            executor.submit {
                concurrentCache.set("https://key$i.url", ByteArray(16) { i.toByte() })
                latch.countDown()
            }
        }

        // Concurrent reads
        for (i in 1..10) {
            executor.submit {
                concurrentCache.get("https://key$i.url")
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        // All keys should be present (no eviction since we're under capacity)
        assertEquals(10, concurrentCache.size())
    }

    // ── Max-Size Eviction ────────────────────────────────────────────

    @Test
    fun `cache evicts oldest entry when max size exceeded`() {
        // cache is configured with maxSize=5 in @Before
        // Insert 5 entries to fill cache
        for (i in 1..5) {
            cache.set("https://key$i.url", ByteArray(16) { i.toByte() })
            Thread.sleep(5) // Ensure different timestamps for LRU ordering
        }
        assertEquals(5, cache.size())

        // Insert a 6th entry — should evict the oldest (key1)
        cache.set("https://key6.url", ByteArray(16) { 6.toByte() })
        assertEquals(5, cache.size())

        // key1 should be evicted (oldest)
        assertNull(cache.get("https://key1.url"))
        // key6 should be present (newest)
        assertNotNull(cache.get("https://key6.url"))
        // key5 should still be present
        assertNotNull(cache.get("https://key5.url"))
    }

    @Test
    fun `concurrent access does not corrupt cache state`() {
        // TEST-010: capacity 20 for 15 keys — NOTHING can be evicted, so the
        // corruption check below is unconditional. (The old version used the
        // shared maxSize=5 fixture and wrapped the check in
        // `if (cached != null)`, which skipped exactly when key15 had been
        // evicted — i.e. it could never fail.)
        val unEvictable = AesKeyCache(ttlMs = 60_000L, maxSize = 20)
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(30)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()

        // Mix of concurrent writes and reads
        for (i in 1..15) {
            executor.submit {
                try {
                    unEvictable.set("https://key$i.url", ByteArray(16) { i.toByte() })
                } catch (e: Exception) {
                    errors.add("write key$i: ${e.message}")
                }
                latch.countDown()
            }
            executor.submit {
                try {
                    unEvictable.get("https://key$i.url")
                } catch (e: Exception) {
                    errors.add("read key$i: ${e.message}")
                }
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        // No exceptions should have been thrown
        assertTrue("No exceptions during concurrent access, but got: $errors", errors.isEmpty())
        // Capacity 20 ≥ 15 keys: every write must be retained (falsifiable —
        // a lost write or a spurious eviction fails here).
        assertEquals("all 15 keys retained under concurrency", 15, unEvictable.size())
        // Verify no corruption: the LAST written key must be present and intact.
        val testKey = "https://key15.url"
        val cached = unEvictable.get(testKey)
        assertNotNull("a non-evictable key must survive the concurrent run", cached)
        assertEquals(16, cached!!.size)
    }

    // ── Key Data Integrity ────────────────────────────────────────────

    @Test
    fun `cached key data is preserved correctly`() {
        val keyData = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )

        cache.set("https://key.url", keyData)
        val cached = cache.get("https://key.url")

        assertNotNull(cached)
        assertArrayEquals(keyData, cached)
    }
}
