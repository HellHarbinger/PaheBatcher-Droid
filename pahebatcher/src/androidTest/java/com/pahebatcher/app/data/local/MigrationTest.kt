package com.pahebatcher.app.data.local

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Migration test for Room database v1 → v2 (Phase 10), v2 → v3 (Phase 11) and
 * v3 → v4 (`Replace file`, DECISIONS 152).
 * Phase 10 architecture v10 §1, §9; PHASE11_ARCHITECTURE_V2 §29 / §36-14.
 *
 * Verifies:
 * - v1 database creation with representative data
 * - Migration v1 → v2 succeeds
 * - Existing v1 data survives migration
 * - segments table is dropped
 * - New v2 columns exist and are accessible
 * - Fresh v2 database creation works
 * - Migration v2 → v3 succeeds (outputUri + playback_position, §29)
 * - Existing v2 data survives v2 → v3 unchanged
 * - Fresh v3 database creation works (exported 3.json)
 * - Migration v3 → v4 succeeds (replaceRequested + replacePriorUri, DECISIONS 152)
 * - Existing v3 data survives v3 → v4 unchanged; new columns read as no-replacement
 * - Fresh v4 database creation works (exported 4.json)
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    companion object {
        private const val TEST_DB_NAME = "migration-test"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PaheDatabase::class.java
    )

    @Test
    fun migrate1To2_preservesData() {
        // 1. Create v1 database
        var db = helper.createDatabase(TEST_DB_NAME, 1).apply {
            // Insert representative v1 data
            execSQL("""
                INSERT INTO scan_cache (session, title, host, totalEpisodes, episodesJson, cachedAt, cacheTtlMinutes)
                VALUES ('test-session', 'Test Anime', 'animepahe.com', 12, '[]', 1000000, 60)
            """)
            execSQL("""
                INSERT INTO download_sessions (sessionId, title, animeSession, host, quality, audioLang, outputDir, cacheDir, createdAt, updatedAt, state)
                VALUES ('sess-1', 'Test Session', 'anime-123', 'animepahe.com', 2, 'sub', '/downloads', '/cache', 1000000, 1000000, 'QUEUED')
            """)
            execSQL("""
                INSERT INTO episode_downloads (episodeId, sessionId, episodeNumber, episodeSession, title, fansub, audio, playUrl, totalSegments, completedSegments, downloadedBytes, state, speed, etaSeconds, retryCount, createdAt, updatedAt)
                VALUES ('ep-1', 'sess-1', 1.0, 'ep-sess-1', 'Episode 1', 'Fansub', 'sub', 'https://example.com/play', 100, 50, 500000, 'DOWNLOADING', 0, 0, 0, 1000000, 1000000)
            """)
            // Insert into segments table (v1 only)
            execSQL("""
                INSERT INTO segments (segmentId, episodeId, segmentIndex, url, sizeBytes, state, createdAt, updatedAt)
                VALUES ('seg-1', 'ep-1', 0, 'https://example.com/seg0.ts', 100000, 'COMPLETED', 1000000, 1000000)
            """)
            close()
        }

        // 2. Migrate v1 → v2
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 2, true, MIGRATION_1_2
        )

        // 3. Verify scan_cache data survived
        val cursor = db.query("SELECT * FROM scan_cache WHERE session = 'test-session'")
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("Test Anime", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        cursor.close()

        // 4. Verify download_sessions data survived
        val cursor2 = db.query("SELECT * FROM download_sessions WHERE sessionId = 'sess-1'")
        assertEquals(1, cursor2.count)
        cursor2.moveToFirst()
        assertEquals("Test Session", cursor2.getString(cursor2.getColumnIndexOrThrow("title")))
        cursor2.close()

        // 5. Verify episode_downloads data survived
        val cursor3 = db.query("SELECT * FROM episode_downloads WHERE episodeId = 'ep-1'")
        assertEquals(1, cursor3.count)
        cursor3.moveToFirst()
        assertEquals("Episode 1", cursor3.getString(cursor3.getColumnIndexOrThrow("title")))
        assertEquals(100, cursor3.getInt(cursor3.getColumnIndexOrThrow("totalSegments")))
        assertEquals(50, cursor3.getInt(cursor3.getColumnIndexOrThrow("completedSegments")))
        cursor3.close()

        // 6. Verify new v2 columns exist and are accessible
        val cursor4 = db.query("SELECT m3u8Url, userAgent, referer, segmentDir FROM episode_downloads WHERE episodeId = 'ep-1'")
        assertEquals(1, cursor4.count)
        cursor4.moveToFirst()
        assertNull(cursor4.getString(0)) // m3u8Url is NULL for v1 data
        assertNull(cursor4.getString(1)) // userAgent is NULL for v1 data
        assertNull(cursor4.getString(2)) // referer is NULL for v1 data
        assertNull(cursor4.getString(3)) // segmentDir is NULL for v1 data
        cursor4.close()

        // 7. Verify segments table was dropped
        try {
            db.query("SELECT * FROM segments")
            fail("segments table should not exist after migration")
        } catch (e: Exception) {
            // Expected — table was dropped
        }
    }

    @Test
    fun migrate1To2_segmentsTableDropped() {
        // Create v1 database with segments
        var db = helper.createDatabase(TEST_DB_NAME, 1).apply {
            execSQL("""
                INSERT INTO segments (segmentId, episodeId, segmentIndex, url, sizeBytes, state, createdAt, updatedAt)
                VALUES ('seg-1', 'ep-1', 0, 'https://example.com/seg0.ts', 100000, 'COMPLETED', 1000000, 1000000)
            """)
            close()
        }

        // Migrate
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME, 2, true, MIGRATION_1_2
        )

        // Verify segments table is gone
        try {
            db.query("SELECT * FROM segments")
            fail("segments table should not exist after migration")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun freshInstall_createsV2Schema() {
        // Create fresh v2 database directly (no migration needed)
        val db = helper.createDatabase(TEST_DB_NAME, 2)

        // Verify all v2 tables exist
        val tables = mutableListOf<String>()
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        while (cursor.moveToNext()) {
            tables.add(cursor.getString(0))
        }
        cursor.close()

        assertTrue("scan_cache table should exist", tables.contains("scan_cache"))
        assertTrue("download_sessions table should exist", tables.contains("download_sessions"))
        assertTrue("episode_downloads table should exist", tables.contains("episode_downloads"))
        assertTrue("watchlist table should exist", tables.contains("watchlist"))

        // Verify segments table does NOT exist in fresh v2
        assertFalse("segments table should NOT exist in fresh v2", tables.contains("segments"))

        // Verify new v2 columns exist
        val columns = mutableListOf<String>()
        val colCursor = db.query("PRAGMA table_info(episode_downloads)")
        while (colCursor.moveToNext()) {
            columns.add(colCursor.getString(colCursor.getColumnIndexOrThrow("name")))
        }
        colCursor.close()

        assertTrue("m3u8Url column should exist", columns.contains("m3u8Url"))
        assertTrue("userAgent column should exist", columns.contains("userAgent"))
        assertTrue("referer column should exist", columns.contains("referer"))
        assertTrue("segmentDir column should exist", columns.contains("segmentDir"))
    }

    // -----------------------------------------------------------------------
    // Room v2 → v3 migration
    // -----------------------------------------------------------------------

    @Test
    fun migrate2To3_preservesData_addsOutputUriAndPositionTable() {
        // 1. Create a v2 database with representative data
        var db = helper.createDatabase(TEST_DB_NAME, 2).apply {
            execSQL("""
                INSERT INTO download_sessions (sessionId, title, animeSession, host, quality, audioLang, outputDir, cacheDir, createdAt, updatedAt, state)
                VALUES ('sess-v2', 'V2 Session', 'anime-v2', 'animepahe.com', 2, 'sub', '/downloads', '/cache', 1000000, 1000000, 'QUEUED')
            """)
            execSQL("""
                INSERT INTO episode_downloads (episodeId, sessionId, episodeNumber, episodeSession, title, fansub, audio, playUrl, totalSegments, completedSegments, downloadedBytes, state, speed, etaSeconds, retryCount, errorMessage, outputPath, createdAt, updatedAt, m3u8Url, userAgent, referer, segmentDir)
                VALUES ('ep-v2', 'sess-v2', 1.0, 'ep-sess-v2', 'V2 Episode', 'Fansub', 'sub', 'https://example.com/play', 100, 50, 500000, 'COMPLETED', 0, 0, 0, NULL, '/files/downloads/ep-v2/out.mp4', 1000000, 1000000, 'https://cdn/m.m3u8', 'UA', 'https://ref', '/files/downloads/ep-v2')
            """)
            close()
        }

        // 2. Migrate v2 → v3 (validated against the exported 3.json schema)
        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 3, true, MIGRATION_2_3)

        // 3. Existing v2 data survives unchanged
        val cursor = db.query("SELECT title, totalSegments, completedSegments, outputPath, segmentDir FROM episode_downloads WHERE episodeId = 'ep-v2'")
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("V2 Episode", cursor.getString(0))
        assertEquals(100, cursor.getInt(1))
        assertEquals(50, cursor.getInt(2))
        assertEquals("/files/downloads/ep-v2/out.mp4", cursor.getString(3))
        assertEquals("/files/downloads/ep-v2", cursor.getString(4))
        cursor.close()

        // 4. New outputUri column exists and is NULL for pre-export v2 rows (§29)
        val uriCursor = db.query("SELECT outputUri FROM episode_downloads WHERE episodeId = 'ep-v2'")
        assertEquals(1, uriCursor.count)
        uriCursor.moveToFirst()
        assertNull("outputUri must be NULL for pre-export rows", uriCursor.getString(0))
        uriCursor.close()

        // 5. New playback_position table exists with the exact schema
        val tables = mutableListOf<String>()
        val tableCursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        while (tableCursor.moveToNext()) tables.add(tableCursor.getString(0))
        tableCursor.close()
        assertTrue("playback_position table should exist after v3", tables.contains("playback_position"))

        // 6. playback_position is writable/readable post-migration (§29/§29.1)
        db.execSQL(
            "INSERT INTO playback_position (episodeId, positionMs, durationMs, updatedAt) VALUES ('ep-v2', 12345, 60000, 1000000)"
        )
        val posCursor = db.query("SELECT positionMs, durationMs FROM playback_position WHERE episodeId = 'ep-v2'")
        assertEquals(1, posCursor.count)
        posCursor.moveToFirst()
        assertEquals(12345L, posCursor.getLong(0))
        assertEquals(60000L, posCursor.getLong(1))
        posCursor.close()
    }

    @Test
    fun freshInstall_createsV3Schema() {
        val db = helper.createDatabase(TEST_DB_NAME, 3)

        val tables = mutableListOf<String>()
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        while (cursor.moveToNext()) tables.add(cursor.getString(0))
        cursor.close()

        assertTrue("episode_downloads should exist", tables.contains("episode_downloads"))
        assertTrue("playback_position should exist in fresh v3", tables.contains("playback_position"))
        assertFalse("segments table should NOT exist in fresh v3", tables.contains("segments"))

        val columns = mutableListOf<String>()
        val colCursor = db.query("PRAGMA table_info(episode_downloads)")
        while (colCursor.moveToNext()) {
            columns.add(colCursor.getString(colCursor.getColumnIndexOrThrow("name")))
        }
        colCursor.close()
        assertTrue("outputUri column should exist in v3", columns.contains("outputUri"))
        assertTrue("outputPath column must be KEPT (not dropped) in v3", columns.contains("outputPath"))

        // playback_position has no FK to episode_downloads (§29.1 deliberate)
        val fkCursor = db.query("PRAGMA foreign_key_list(playback_position)")
        assertEquals("playback_position must have no FK in v3", 0, fkCursor.count)
        fkCursor.close()
    }

    // -----------------------------------------------------------------------
    // `Replace file` (DECISIONS 152): Room v3 → v4 migration
    // -----------------------------------------------------------------------

    @Test
    fun migrate3To4_preservesData_addsReplacementColumns() {
        // 1. Create a v3 database with representative data
        var db = helper.createDatabase(TEST_DB_NAME, 3).apply {
            execSQL("""
                INSERT INTO download_sessions (sessionId, title, animeSession, host, quality, audioLang, outputDir, cacheDir, createdAt, updatedAt, state)
                VALUES ('sess-v3', 'V3 Session', 'anime-v3', 'animepahe.com', 2, 'sub', '/downloads', '/cache', 1000000, 1000000, 'QUEUED')
            """)
            execSQL("""
                INSERT INTO episode_downloads (episodeId, sessionId, episodeNumber, episodeSession, title, fansub, audio, playUrl, totalSegments, completedSegments, downloadedBytes, state, speed, etaSeconds, retryCount, errorMessage, outputPath, outputUri, createdAt, updatedAt, m3u8Url, userAgent, referer, segmentDir)
                VALUES ('ep-v3', 'sess-v3', 1.0, 'ep-sess-v3', 'V3 Episode', 'Fansub', 'sub', 'https://example.com/play', 100, 50, 500000, 'COMPLETED', 0, 0, 0, NULL, '/files/downloads/ep-v3/out.mp4', 'content://media/external/images/media/42', 1000000, 1000000, 'https://cdn/m.m3u8', 'UA', 'https://ref', '/files/downloads/ep-v3')
            """)
            close()
        }

        // 2. Migrate v3 → v4 (validated against the exported 4.json schema — the
        //    check Room's runtime migrator does NOT perform)
        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 4, true, MIGRATION_3_4)

        // 3. Existing v3 data survives unchanged
        val cursor = db.query("SELECT title, outputPath, outputUri FROM episode_downloads WHERE episodeId = 'ep-v3'")
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("V3 Episode", cursor.getString(0))
        assertEquals("/files/downloads/ep-v3/out.mp4", cursor.getString(1))
        assertEquals("content://media/external/images/media/42", cursor.getString(2))
        cursor.close()

        // 4. The v4 replacement columns exist and read as "no replacement" for
        //    every pre-upgrade row: replaceRequested defaults to 0 (NOT NULL),
        //    replacePriorUri is NULL.
        val repCursor = db.query("SELECT replaceRequested, replacePriorUri FROM episode_downloads WHERE episodeId = 'ep-v3'")
        assertEquals(1, repCursor.count)
        repCursor.moveToFirst()
        assertEquals("replaceRequested must default to 0 for pre-upgrade rows", 0, repCursor.getInt(0))
        assertNull("replacePriorUri must be NULL for pre-upgrade rows", repCursor.getString(1))
        repCursor.close()

        // 5. The new columns are writable post-migration (the replace flow's own path)
        db.execSQL("UPDATE episode_downloads SET replaceRequested = 1, replacePriorUri = 'content://media/old/7' WHERE episodeId = 'ep-v3'")
        val writeCursor = db.query("SELECT replaceRequested, replacePriorUri FROM episode_downloads WHERE episodeId = 'ep-v3'")
        assertEquals(1, writeCursor.count)
        writeCursor.moveToFirst()
        assertEquals(1, writeCursor.getInt(0))
        assertEquals("content://media/old/7", writeCursor.getString(1))
        writeCursor.close()
    }

    @Test
    fun freshInstall_createsV4Schema() {
        val db = helper.createDatabase(TEST_DB_NAME, 4)

        val tables = mutableListOf<String>()
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        while (cursor.moveToNext()) tables.add(cursor.getString(0))
        cursor.close()

        assertTrue("episode_downloads should exist", tables.contains("episode_downloads"))
        assertTrue("playback_position should exist in fresh v4", tables.contains("playback_position"))

        val columns = mutableListOf<String>()
        val colCursor = db.query("PRAGMA table_info(episode_downloads)")
        while (colCursor.moveToNext()) {
            columns.add(colCursor.getString(colCursor.getColumnIndexOrThrow("name")))
        }
        colCursor.close()
        // Every v3 column is KEPT (additive migration only)
        assertTrue("outputUri column must be kept in v4", columns.contains("outputUri"))
        assertTrue("outputPath column must be kept in v4", columns.contains("outputPath"))
        // The v4 columns exist
        assertTrue("replaceRequested column should exist in v4", columns.contains("replaceRequested"))
        assertTrue("replacePriorUri column should exist in v4", columns.contains("replacePriorUri"))

        // replaceRequested is NOT NULL with DEFAULT 0 (matches the entity)
        val notNullCursor = db.query("PRAGMA table_info(episode_downloads)")
        while (notNullCursor.moveToNext()) {
            if (notNullCursor.getString(notNullCursor.getColumnIndexOrThrow("name")) == "replaceRequested") {
                assertEquals("replaceRequested must be NOT NULL", 1, notNullCursor.getInt(notNullCursor.getColumnIndexOrThrow("notnull")))
                assertEquals("replaceRequested must default to 0", "0", notNullCursor.getString(notNullCursor.getColumnIndexOrThrow("dflt_value")))
            }
        }
        notNullCursor.close()
    }

    // -----------------------------------------------------------------------
    // Pause/resume run-ownership race fix: Room v4 → v5 migration
    // -----------------------------------------------------------------------

    @Test
    fun migrate4To5_preservesData_addsRunIdColumn() {
        // 1. Create a v4 database with representative data — including a LIVE
        //    `DOWNLOADING` row, the exact pre-upgrade row shape the race
        //    concerned (a v4 database can never carry a run token).
        var db = helper.createDatabase(TEST_DB_NAME, 4).apply {
            execSQL("""
                INSERT INTO download_sessions (sessionId, title, animeSession, host, quality, audioLang, outputDir, cacheDir, createdAt, updatedAt, state)
                VALUES ('sess-v4', 'V4 Session', 'anime-v4', 'animepahe.com', 2, 'sub', '/downloads', '/cache', 1000000, 1000000, 'QUEUED')
            """)
            execSQL("""
                INSERT INTO episode_downloads (episodeId, sessionId, episodeNumber, episodeSession, title, fansub, audio, playUrl, totalSegments, completedSegments, downloadedBytes, state, speed, etaSeconds, retryCount, outputPath, outputUri, createdAt, updatedAt, m3u8Url, userAgent, referer, segmentDir, replaceRequested, replacePriorUri)
                VALUES ('ep-v4', 'sess-v4', 1.0, 'ep-sess-v4', 'V4 Episode', 'Fansub', 'sub', 'https://example.com/play', 100, 50, 500000, 'DOWNLOADING', 0, 0, 0, NULL, NULL, 1000000, 1000000, 'https://cdn/m.m3u8', 'UA', 'https://ref', '/files/downloads/ep-v4', 0, NULL)
            """)
            close()
        }

        // 2. Migrate v4 → v5 (validated against the exported 5.json schema —
        //    the check Room's runtime migrator does NOT perform)
        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 5, true, MIGRATION_4_5)

        // 3. Existing v4 data survives unchanged
        val cursor = db.query("SELECT title, state, completedSegments, outputPath, segmentDir FROM episode_downloads WHERE episodeId = 'ep-v4'")
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("V4 Episode", cursor.getString(0))
        assertEquals("DOWNLOADING", cursor.getString(1))
        assertEquals(50, cursor.getInt(2))
        assertNull(cursor.getString(3))
        assertEquals("/files/downloads/ep-v4", cursor.getString(4))
        cursor.close()

        // 4. The v5 run-ownership column exists and reads NULL ("owned by
        //    nobody") for every pre-upgrade row — exactly the true state of a
        //    v4 database, which had no run tokens at all.
        val runCursor = db.query("SELECT downloadRunId FROM episode_downloads WHERE episodeId = 'ep-v4'")
        assertEquals(1, runCursor.count)
        runCursor.moveToFirst()
        assertNull("downloadRunId must be NULL for pre-upgrade rows", runCursor.getString(0))
        runCursor.close()

        // 5. The new column is writable post-migration (the Worker claim's own
        //    path stamps a token in the claim UPDATE).
        db.execSQL("UPDATE episode_downloads SET downloadRunId = 'run-after-upgrade' WHERE episodeId = 'ep-v4'")
        val writeCursor = db.query("SELECT downloadRunId FROM episode_downloads WHERE episodeId = 'ep-v4'")
        assertEquals(1, writeCursor.count)
        writeCursor.moveToFirst()
        assertEquals("run-after-upgrade", writeCursor.getString(0))
        writeCursor.close()
    }

    @Test
    fun freshInstall_createsV5Schema() {
        val db = helper.createDatabase(TEST_DB_NAME, 5)

        val tables = mutableListOf<String>()
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        while (cursor.moveToNext()) tables.add(cursor.getString(0))
        cursor.close()

        assertTrue("episode_downloads should exist", tables.contains("episode_downloads"))
        assertTrue("playback_position should exist in fresh v5", tables.contains("playback_position"))

        val columns = mutableListOf<String>()
        val colCursor = db.query("PRAGMA table_info(episode_downloads)")
        while (colCursor.moveToNext()) {
            columns.add(colCursor.getString(colCursor.getColumnIndexOrThrow("name")))
        }
        colCursor.close()
        // Every earlier column is KEPT (additive migration only)
        assertTrue("outputUri column must be kept in v5", columns.contains("outputUri"))
        assertTrue("replaceRequested column must be kept in v5", columns.contains("replaceRequested"))
        assertTrue("replacePriorUri column must be kept in v5", columns.contains("replacePriorUri"))
        // The v5 run-ownership column exists and is NULLABLE (matches the entity)
        assertTrue("downloadRunId column should exist in v5", columns.contains("downloadRunId"))
        val runColCursor = db.query("PRAGMA table_info(episode_downloads)")
        while (runColCursor.moveToNext()) {
            if (runColCursor.getString(runColCursor.getColumnIndexOrThrow("name")) == "downloadRunId") {
                assertEquals("downloadRunId must be NULLABLE", 0, runColCursor.getInt(runColCursor.getColumnIndexOrThrow("notnull")))
            }
        }
        runColCursor.close()
    }
}
