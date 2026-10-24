package com.pahebatcher.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.dao.PlaybackPositionDao
import com.pahebatcher.app.data.local.dao.ScanCacheDao
import com.pahebatcher.app.data.local.dao.WatchlistDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.local.entity.PlaybackPositionEntity
import com.pahebatcher.app.data.local.entity.ScanCacheEntity
import com.pahebatcher.app.data.local.entity.WatchlistEntity

/**
 * Room database for PaheBatcher.
 * Single source of truth for persistent state.
 * Survives process death, configuration changes, and app restarts.
 *
 * Notable in this version:
 * - Version bumped to 2
 * - SegmentEntity removed (dead infrastructure)
 * - fallbackToDestructiveMigration removed
 * - exportSchema = true
 * - MIGRATION_1_2 added
 *
 * Version 3 migration:
 * - Version bumped to 3 (additive)
 * - EpisodeDownloadEntity.outputUri added (MediaStore export URI)
 * - PlaybackPositionEntity added (playback position retention)
 * - MIGRATION_2_3 added; no destructive fallback
 *
 * `Replace file` (DECISIONS 152):
 * - Version bumped to 4 (additive)
 * - EpisodeDownloadEntity.replaceRequested / replacePriorUri added
 * - MIGRATION_3_4 added; no destructive fallback
 *
 * Pause/resume run-ownership race fix:
 * - Version bumped to 5 (additive)
 * - EpisodeDownloadEntity.downloadRunId added (the persisted run-ownership
 *   token stamped by the Worker's entry claim and required by every
 *   run-owned guarded write)
 * - MIGRATION_4_5 added; no destructive fallback
 */
@Database(
    entities = [
        ScanCacheEntity::class,
        DownloadSessionEntity::class,
        EpisodeDownloadEntity::class,
        WatchlistEntity::class,
        PlaybackPositionEntity::class
        // SegmentEntity::class REMOVED — dead infrastructure (Phase 10)
    ],
    version = 5,
    exportSchema = true
)
// DATA-007: @TypeConverters removed — all four converter pairs (List<Float>,
// List<String>, Map<String, String>, List<Map<String, String>>) were DEAD:
// no entity column uses those types, so the exported schema (identityHash
// included) never depended on them. Room keeps compiling and the schema is
// byte-identical without the registration.
abstract class PaheDatabase : RoomDatabase() {

    abstract fun scanCacheDao(): ScanCacheDao
    abstract fun downloadSessionDao(): DownloadSessionDao
    abstract fun episodeDownloadDao(): EpisodeDownloadDao
    // abstract fun segmentDao(): SegmentDao  // REMOVED — dead infrastructure (Phase 10)
    abstract fun watchlistDao(): WatchlistDao
    abstract fun playbackPositionDao(): PlaybackPositionDao

    companion object {
        private const val DATABASE_NAME = "pahebatcher.db"

        @Volatile
        private var INSTANCE: PaheDatabase? = null

        fun getInstance(context: Context): PaheDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createDatabase(context).also { INSTANCE = it }
            }
        }

        private fun createDatabase(context: Context): PaheDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PaheDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // fallbackToDestructiveMigration REMOVED — must use explicit migrations
                .build()
        }

        /**
         * For testing: create in-memory database.
         */
        fun getInMemoryDatabase(context: Context): PaheDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PaheDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}

/**
 * Migration from version 1 to version 2.
 *
 * Changes in this migration:
 * - Drop unused segments table (SegmentEntity was never written in production)
 * - Add new columns to episode_downloads for background download support
 *
 * Safety: DROP TABLE IF EXISTS handles existing v1 databases where the table exists.
 * Fresh installs create v2 schema directly (no migration needed).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Drop unused SegmentEntity/SegmentDao table (never written in production)
        db.execSQL("DROP TABLE IF EXISTS segments")

        // 2. Add new columns to episode_downloads for background download support
        // All new columns are nullable — safe for existing v1 data
        db.execSQL("ALTER TABLE episode_downloads ADD COLUMN m3u8Url TEXT")
        db.execSQL("ALTER TABLE episode_downloads ADD COLUMN userAgent TEXT")
        db.execSQL("ALTER TABLE episode_downloads ADD COLUMN referer TEXT")
        db.execSQL("ALTER TABLE episode_downloads ADD COLUMN segmentDir TEXT")
    }
}

/**
 * Migration from version 2 to version 3 (PHASE11_ARCHITECTURE_V2 §29).
 *
 * Trivially additive — no data is dropped or rewritten:
 * - `episode_downloads.outputUri TEXT NULL` (MediaStore export URI, §27/§30)
 * - new `playback_position` table (PlaybackPositionEntity, §29/§29.1)
 *
 * `outputPath` is kept (not dropped) as the local/pre-export source of truth and
 * reconciler fallback — no destructive migration.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Add the nullable outputUri column to episode_downloads
        db.execSQL("ALTER TABLE episode_downloads ADD COLUMN outputUri TEXT")

        // 2. Create the playback_position table (exact Room schema)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playback_position` (" +
                "`episodeId` TEXT NOT NULL, " +
                "`positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`episodeId`))"
        )
    }
}

/**
 * Migration from version 3 to version 4 (`Replace file`, DECISIONS 152).
 *
 * Trivially additive — no row is dropped, rewritten or reinterpreted:
 * - `episode_downloads.replaceRequested INTEGER NOT NULL DEFAULT 0` — the
 *   persisted "a confirmed replacement is in flight" intent.
 * - `episode_downloads.replacePriorUri TEXT NULL` — the export this
 *   replacement must retire once the new output is verified.
 *
 * `outputPath` and `outputUri` are untouched: every existing row keeps its
 * completed-output reference (§29) exactly as it was, and the new columns read
 * as ordinary/no-replacement for all of them. No destructive migration.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE episode_downloads ADD COLUMN replaceRequested INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL("ALTER TABLE episode_downloads ADD COLUMN replacePriorUri TEXT")
    }
}

/**
 * Migration from version 4 to version 5 (pause/resume run-ownership race fix).
 *
 * Trivially additive — no row is dropped, rewritten or reinterpreted:
 * - `episode_downloads.downloadRunId TEXT NULL` — the persisted identity of the
 *   download run that owns the row, stamped atomically by the Worker's entry
 *   claim and required by every run-owned guarded write.
 *
 * Every pre-upgrade row reads `downloadRunId = NULL` ("owned by nobody"), which
 * is exactly true: a v4 database cannot have a live run under the v5 guard, and
 * the first v5 Worker claim (or reconciler re-enqueue followed by a claim)
 * stamps a real token before any run-owned write is attempted. User and
 * recovery writes never read the column, so no existing behavior changes.
 * No destructive migration.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE episode_downloads ADD COLUMN downloadRunId TEXT")
    }
}
