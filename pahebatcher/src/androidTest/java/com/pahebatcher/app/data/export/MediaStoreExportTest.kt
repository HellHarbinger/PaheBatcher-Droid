package com.pahebatcher.app.data.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.pahebatcher.app.BuildConfig
import com.pahebatcher.app.data.download.DownloadStateReconciler
import com.pahebatcher.app.data.hls.FileNameGenerator
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Slice 6 storage + process-kill matrix tests (PHASE11_ARCHITECTURE_V2):
 *
 *  - §36-7  export process-kill matrix: each of the five documented kill
 *    conditions (before insert / during copy / after verification / after Room
 *    URI persistence / after `IS_PENDING = 0`) is reconstructed as the exact
 *    persisted state a killed process would leave behind, then recovered via
 *    the PRODUCTION retry path (`DownloadStateReconciler.reconcile()`), and
 *    asserted to yield EXACTLY ONE visible row per episode in its per-anime
 *    `Movies/<sanitized title>/` directory (duplicate prevention, F-06 /
 *    Decision 54, Gate A).
 *  - §36-17 storage: export happy path visible via MediaStore query with the
 *    Gate A RELATIVE_PATH, write failure keeps the local file (EACCES stand-in
 *    for ENOSPC — the same catch branch as §30.4's insufficient-storage row;
 *    genuine ENOSPC cannot be provoked portably on an emulator and is recorded
 *    as such in LIMITATIONS), orphaned pending sweep (legacy flat + per-anime
 *    directories), byte-count mismatch retry, legacy flat recognition, and
 *    the unrelated-file / ownership protection of the sweep.
 *  - Slice A (per-audio export identity): the audio-specific destination name,
 *    dual-audio coexistence in both export orders, same-audio replacement,
 *    crash-residue recovery under the new name, size-verified legacy alias
 *    adoption/refusal, and the guarantee that reconciling one audio variant
 *    never deletes or renames another variant's or a legacy file.
 *
 * Every MediaStore row this class creates carries a run-unique token in its
 * display name and is deleted in [teardown]; local fixtures are likewise
 * removed. In-memory Room keeps the production startup reconciler (which
 * exports against the REAL database) completely isolated from these rows.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreExportTest {

    private companion object {
        /** > 64 KiB copy buffer → multi-buffer streaming is genuinely exercised. */
        const val FULL_BYTES = 200_000
        const val HALF_BYTES = 100_000
    }

    private lateinit var context: Context
    private lateinit var database: PaheDatabase
    private lateinit var dao: EpisodeDownloadDao
    private lateinit var contentResolver: ContentResolver
    private lateinit var exporter: MediaStoreExporter

    /** Run-unique tokens embedded in every display name → [teardown] can find them. */
    private val tokens = mutableSetOf<String>()
    private val localFiles = mutableListOf<File>()

    private val testSession = DownloadSessionEntity(
        sessionId = "sess-export-1",
        title = "Export Test Session",
        animeSession = "anime-export",
        host = "animepahe.com",
        quality = 2,
        audioLang = "sub",
        outputDir = "/downloads",
        cacheDir = "/cache",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        state = "QUEUED",
    )

    /**
     * Gate A destination for every episode of [testSession] — the exact
     * `Movies/<sanitized title>/` string the production exporter derives.
     */
    private val animeDir: String
        get() = OutputPathNames.mediaStoreRelativePath(testSession.title, testSession.sessionId)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.episodeDownloadDao()
        contentResolver = context.contentResolver
        exporter = MediaStoreExporter(
            contentResolver,
            dao,
            database.downloadSessionDao(),
        )
        runBlocking { database.downloadSessionDao().insertIfAbsent(testSession) }
    }

    @After
    fun teardown() {
        if (tokens.isNotEmpty()) {
            for (row in rowsInMoviesTree()) {
                if (tokens.any { row.name.contains(it) }) {
                    contentResolver.delete(row.uri, null, null)
                }
            }
        }
        for (file in localFiles) {
            file.setReadable(true, false)
            file.delete()
        }
        database.close()
    }

    // ------------------------------------------------------------------
    // §36-7 process-kill matrix — each state reconstructed, then retried
    // through the production reconciler
    // ------------------------------------------------------------------

    @Test
    fun killBeforeInsert_retryProducesExactlyOneVisibleRow() {
        runBlocking {
            val title = newTitle()
            val local = localFile("before_insert", FULL_BYTES)
            val stem = stemFor(title)
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            assertTrue("precondition: no MediaStore row yet", rowsMatching(stem).isEmpty())

            makeReconciler().reconcile()

            val row = dao.getById(entity.episodeId)!!
            assertEquals(
                "export retry must not demote a valid COMPLETED row",
                EpisodeDownloadEntity.STATE_COMPLETED, row.state,
            )
            assertNotNull("outputUri persisted after retry", row.outputUri)
            assertEquals("exported bytes match local source", FULL_BYTES.toLong(), uriSize(row.outputUri))
            assertExactlyOneVisibleRow(stem, FULL_BYTES.toLong())
            assertFalse("local removed only after verified export (X6)", local.exists())
        }
    }

    @Test
    fun killDuringCopy_partialPendingRow_replacedByOneCompleteRow() {
        runBlocking {
            val title = newTitle()
            val local = localFile("during_copy", FULL_BYTES)
            val stem = stemFor(title)
            // Kill-during-copy residue: pending row holding a PARTIAL write.
            // Byte-count mismatch (§36-17) is this exact persisted condition —
            // §30.2 must drop it and produce one complete replacement.
            val partial = insertPendingRow(expectedNameFor(title), animeDir, pattern(HALF_BYTES))
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            makeReconciler().reconcile()

            val row = dao.getById(entity.episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
            assertNotNull("outputUri persisted after retry", row.outputUri)
            assertEquals("replacement row is byte-complete", FULL_BYTES.toLong(), uriSize(row.outputUri))
            assertExactlyOneVisibleRow(stem, FULL_BYTES.toLong())
            assertNull("partial pending row must be gone", rowByUri(partial))
            assertFalse(local.exists())
        }
    }

    @Test
    fun killAfterVerification_pendingFullRow_reusedWithoutDuplicate() {
        runBlocking {
            val title = newTitle()
            val local = localFile("after_verification", FULL_BYTES)
            val stem = stemFor(title)
            // Kill-after-verification residue: copy fully written and verified,
            // Room URI persistence (X4) never happened → pending row holds the
            // full byte count while outputUri is still NULL.
            val pending = insertPendingRow(expectedNameFor(title), animeDir, pattern(FULL_BYTES))
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            makeReconciler().reconcile()

            val row = dao.getById(entity.episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
            assertEquals("existing row reused, not duplicated", pending.toString(), row.outputUri)
            assertExactlyOneVisibleRow(stem, FULL_BYTES.toLong())
            assertFalse("reused row made visible and local removed (X5→X6)", local.exists())
        }
    }

    @Test
    fun killAfterRoomPersist_pendingClearedAndLocalRemoved() {
        runBlocking {
            val title = newTitle()
            val local = localFile("after_room_persist", FULL_BYTES)
            val stem = stemFor(title)
            // Kill-after-Room-persist (X4 done, X5 not): outputUri recorded in
            // Room while the MediaStore row is STILL invisible.
            val pending = insertPendingRow(expectedNameFor(title), animeDir, pattern(FULL_BYTES))
            val entity = completedEntity(
                title, outputPath = local.absolutePath, outputUri = pending.toString(),
            )
            dao.upsert(entity)

            makeReconciler().reconcile()

            val row = dao.getById(entity.episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
            assertEquals("Room URI unchanged", pending.toString(), row.outputUri)
            assertExactlyOneVisibleRow(stem, FULL_BYTES.toLong())
            assertFalse("pending cleared then local removed by recovery", local.exists())
        }
    }

    @Test
    fun killAfterPendingCleared_visibleRowLocalDuplicateRemoved() {
        runBlocking {
            val title = newTitle()
            val local = localFile("after_pending_cleared", FULL_BYTES)
            val stem = stemFor(title)
            // Kill-after-IS_PENDING=0 (X5 done, X6 not): row already visible,
            // Room knows the URI, and the local duplicate still survives.
            val visible = insertPendingRow(expectedNameFor(title), animeDir, pattern(FULL_BYTES))
            makeVisible(visible)
            val entity = completedEntity(
                title, outputPath = local.absolutePath, outputUri = visible.toString(),
            )
            dao.upsert(entity)

            makeReconciler().reconcile()

            val row = dao.getById(entity.episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
            assertEquals("outputUri unchanged", visible.toString(), row.outputUri)
            assertExactlyOneVisibleRow(stem, FULL_BYTES.toLong())
            assertFalse("local duplicate cleaned by recovery", local.exists())
        }
    }

    // ------------------------------------------------------------------
    // §36-17 storage behaviors
    // ------------------------------------------------------------------

    @Test
    fun exportHappyPath_visibleRowUriPersistedLocalRemoved_idempotent() {
        runBlocking {
            val title = newTitle()
            val local = localFile("happy", FULL_BYTES)
            val stem = stemFor(title)
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            val outcome = exporter.export(entity)
            assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)
            assertExactlyOneVisibleRow(stem, FULL_BYTES.toLong())
            assertEquals(
                (outcome as ExportOutcome.Exported).uri,
                dao.getById(entity.episodeId)!!.outputUri,
            )
            assertFalse("X6: local removed after verified persistence", local.exists())
            // Gate A: the row landed in the per-anime directory, never flat.
            assertEquals(
                "new exports go to the sanitized per-anime directory",
                animeDir,
                uriRelativePath(outcome.uri),
            )

            // Idempotency: a second export attempt must not create a duplicate.
            val again = exporter.export(dao.getById(entity.episodeId)!!)
            assertTrue("expected AlreadyExported, got $again", again is ExportOutcome.AlreadyExported)
            assertExactlyOneVisibleRow(stem, FULL_BYTES.toLong())
        }
    }

    @Test
    fun exportWriteFailure_keepsLocalFileAndLeavesNoRows() {
        runBlocking {
            val title = newTitle()
            val local = localFile("write_fail", FULL_BYTES)
            val stem = stemFor(title)
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            // Revoking read access provokes a REAL EACCES at stream-copy time —
            // the identical catch branch ENOSPC hits (§30.4: write fails →
            // pending row removed, local kept, never delete an unverified copy).
            assertTrue("precondition: revoke read permission", local.setReadable(false, false))

            val outcome = exporter.export(entity)
            assertTrue("expected Failed, got $outcome", outcome is ExportOutcome.Failed)
            assertTrue("local file kept on export failure", local.exists())
            assertNull("no URI persisted on failure", dao.getById(entity.episodeId)!!.outputUri)
            assertTrue(
                "failed attempt must leave no invisible garbage row",
                rowsMatching(stem).isEmpty(),
            )

            local.setReadable(true, false)
        }
    }

    @Test
    fun sweep_removesOrphanedPendingRow_keepsRoomReferencedRow() {
        runBlocking {
            // Orphan in the NEW per-anime directory: pending row from a
            // "previous process", never referenced in Room.
            val orphanToken = registerToken("sweeporphan")
            val orphan = insertPendingRow("pbx_$orphanToken.mp4", animeDir, pattern(1024))

            // Orphan in the LEGACY flat directory: pre-Gate-A crash residue —
            // the sweep covers it with the same ownership rules (Gate A §7).
            val legacyToken = registerToken("sweeplegacy")
            val legacyOrphan = insertPendingRow(
                "pbx_$legacyToken.mp4", OutputPathNames.LEGACY_RELATIVE_PATH, pattern(1024),
            )

            // Referenced: pending row whose URI IS recorded in Room — the sweep
            // must never delete it (§30.4 defensive rule), regardless of age.
            val refToken = registerToken("sweepref")
            val referenced = insertPendingRow("pbx_$refToken.mp4", animeDir, pattern(1024))
            dao.upsert(
                completedEntity(newTitle(), outputPath = null, outputUri = referenced.toString()),
            )

            // Explicit future cutoff → every row qualifies by age; only the
            // Room-reference rule can protect one.
            val swept = exporter.sweepStalePending(System.currentTimeMillis() + 60_000)
            assertTrue("at least the two orphans are swept (got $swept)", swept >= 2)
            assertNull("orphaned pending row removed from the anime directory",
                rowByUri(orphan))
            assertNull("legacy flat orphaned pending row also swept",
                rowByUri(legacyOrphan))
            assertNotNull("Room-referenced pending row kept", rowByUri(referenced))
        }
    }

    // ------------------------------------------------------------------
    // Gate A — per-anime destination + legacy flat compatibility
    // ------------------------------------------------------------------

    @Test
    fun legacyFlatExport_isRecognized_viaPersistedUri_fastPath() {
        runBlocking {
            val title = newTitle()
            val local = localFile("legacy_uri", FULL_BYTES)
            val legacyStem = legacyStemFor(title)
            // A pre-Gate-A completed export: visible UNSUFFIXED row in
            // Movies/PaheBatcher/, Room already references it, local duplicate
            // survives.
            val legacy = insertPendingRow(
                legacyNameFor(title), OutputPathNames.LEGACY_RELATIVE_PATH,
                pattern(FULL_BYTES),
            )
            makeVisible(legacy)
            val entity = completedEntity(
                title, outputPath = local.absolutePath, outputUri = legacy.toString(),
            )
            dao.upsert(entity)

            makeReconciler().reconcile()

            val row = dao.getById(entity.episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
            assertEquals(
                "the legacy URI stays authoritative (never migrated)",
                legacy.toString(), row.outputUri,
            )
            assertEquals(
                OutputPathNames.LEGACY_RELATIVE_PATH,
                uriRelativePath(row.outputUri),
            )
            assertExactlyOneVisibleRow(legacyStem, FULL_BYTES.toLong())
            assertFalse("local duplicate cleaned by recovery", local.exists())
        }
    }

    @Test
    fun legacyFlatExport_sizeVerified_isReusedWithoutSecondCopy() {
        runBlocking {
            val title = newTitle()
            val local = localFile("legacy_reuse", FULL_BYTES)
            val legacyStem = legacyStemFor(title)
            // Legacy UNSUFFIXED file exists and is size-verified, but outputUri
            // does NOT reference it (dangling/lost URI): export must adopt the
            // legacy row instead of writing a second copy into the anime
            // directory.
            val legacy = insertPendingRow(
                legacyNameFor(title), OutputPathNames.LEGACY_RELATIVE_PATH,
                pattern(FULL_BYTES),
            )
            makeVisible(legacy)
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            val outcome = exporter.export(entity)

            assertTrue("expected AlreadyExported, got $outcome", outcome is ExportOutcome.AlreadyExported)
            assertEquals(
                "the legacy row is adopted, not duplicated",
                legacy.toString(), (outcome as ExportOutcome.AlreadyExported).uri,
            )
            assertNotNull("legacy row kept in place", rowByUri(legacy))
            assertEquals(
                OutputPathNames.LEGACY_RELATIVE_PATH,
                uriRelativePath(dao.getById(entity.episodeId)!!.outputUri),
            )
            assertExactlyOneVisibleRow(legacyStem, FULL_BYTES.toLong())
            assertTrue(
                "no audio-suffixed copy may be created when the alias is adopted",
                rowsMatching(stemFor(title)).isEmpty(),
            )
            assertFalse("local removed after adopting the size-verified legacy row", local.exists())
        }
    }

    @Test
    fun sweep_neverTouchesFilesOutsideMoviesOrOtherAppsRows() {
        runBlocking {
            // 0. Positive control: our own stale pending orphan under Movies/ —
            //    must be swept, proving the sweep's Movies scope + ownership
            //    filter is live before the two negative assertions below.
            val controlToken = registerToken("sweepcontrol")
            val control = insertPendingRow(
                "pbx_$controlToken.mp4", animeDir, pattern(1024),
            )

            // 1. Our own row OUTSIDE `Movies/` (Pictures/) — structurally
            //    excluded by the sweep's directory scope, even pending+old.
            val outsideToken = registerToken("sweeppictures")
            val outsideValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "pbx_$outsideToken.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val outside = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, outsideValues,
            )
            assertNotNull("Pictures fixture insert must succeed", outside)
            contentResolver.openOutputStream(outside!!, "w")!!.use { it.write(pattern(512)) }

            // 2. Rows owned by OTHER packages under Movies/ — snapshot before/after.
            val foreignBefore = foreignOwnedMoviesRows()

            val swept = exporter.sweepStalePending(System.currentTimeMillis() + 60_000)
            assertTrue("the positive control in Movies is swept (got $swept)", swept >= 1)
            assertNull("positive control row removed from the anime directory", rowByUri(control))

            assertNotNull(
                "a non-Movies row must never be swept",
                rowByUri(outside),
            )
            val foreignAfter = foreignOwnedMoviesRows()
            assertEquals(
                "other apps' Movies rows must survive untouched",
                foreignBefore, foreignAfter,
            )
        }
    }

    /**
     * IDs of `Movies/` rows owned by a package OTHER than ours (MATCH_INCLUDE,
     * so pending rows count) — the ownership filter's observable surface. On a
     * device with no other video apps this set may be empty (then the check is
     * vacuous by construction).
     */
    private fun foreignOwnedMoviesRows(): List<String> {
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? " +
                    "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} != ?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(OutputPathNames.MOVIES_PREFIX + "%", BuildConfig.APPLICATION_ID),
            )
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val ids = mutableListOf<String>()
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                "_id",
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            ),
            args,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            while (cursor.moveToNext()) {
                ids += cursor.getString(idIdx)
            }
        }
        return ids.sorted()
    }

    // ------------------------------------------------------------------
    // Slice A — per-audio export identity
    // ------------------------------------------------------------------

    @Test
    fun dualAudio_jpnThenEng_bothVariantsRemain() {
        runBlocking {
            val title = newTitle()
            val jpnLocal = localFile("jpn_first", FULL_BYTES)
            val engLocal = localFile("eng_second", HALF_BYTES)
            val jpn = completedEntity(title, outputPath = jpnLocal.absolutePath, audio = "jpn")
            val eng = completedEntity(title, outputPath = engLocal.absolutePath, audio = "eng")
            dao.upsert(jpn)
            dao.upsert(eng)

            val jpnOutcome = exporter.export(dao.getById(jpn.episodeId)!!)
            assertTrue("expected Exported for jpn, got $jpnOutcome", jpnOutcome is ExportOutcome.Exported)
            val engOutcome = exporter.export(dao.getById(eng.episodeId)!!)
            assertTrue("expected Exported for eng, got $engOutcome", engOutcome is ExportOutcome.Exported)

            assertNotEquals(
                "the two variants must never share a destination name",
                stemFor(title, "jpn"), stemFor(title, "eng"),
            )
            assertExactlyOneVisibleRow(stemFor(title, "jpn"), FULL_BYTES.toLong())
            assertExactlyOneVisibleRow(stemFor(title, "eng"), HALF_BYTES.toLong())
            // The second export must leave the first variant fully intact.
            val jpnUri = Uri.parse((jpnOutcome as ExportOutcome.Exported).uri)
            assertNotNull("jpn row survives the eng export", rowByUri(jpnUri))
            assertEquals(
                "jpn destination bytes untouched",
                FULL_BYTES.toLong(), uriSize(dao.getById(jpn.episodeId)!!.outputUri),
            )
            assertNotEquals(
                "two distinct destinations",
                dao.getById(jpn.episodeId)!!.outputUri,
                dao.getById(eng.episodeId)!!.outputUri,
            )
            assertFalse("X6 for both variants", jpnLocal.exists() || engLocal.exists())
        }
    }

    @Test
    fun dualAudio_engThenJpn_bothVariantsRemain() {
        runBlocking {
            val title = newTitle()
            val jpnLocal = localFile("jpn_second", FULL_BYTES)
            val engLocal = localFile("eng_first", HALF_BYTES)
            val jpn = completedEntity(title, outputPath = jpnLocal.absolutePath, audio = "jpn")
            val eng = completedEntity(title, outputPath = engLocal.absolutePath, audio = "eng")
            dao.upsert(jpn)
            dao.upsert(eng)

            // Opposite export order: the names are metadata-derived, so the
            // result must be identical to the jpn-first test.
            val engOutcome = exporter.export(dao.getById(eng.episodeId)!!)
            assertTrue("expected Exported for eng, got $engOutcome", engOutcome is ExportOutcome.Exported)
            val jpnOutcome = exporter.export(dao.getById(jpn.episodeId)!!)
            assertTrue("expected Exported for jpn, got $jpnOutcome", jpnOutcome is ExportOutcome.Exported)

            assertExactlyOneVisibleRow(stemFor(title, "jpn"), FULL_BYTES.toLong())
            assertExactlyOneVisibleRow(stemFor(title, "eng"), HALF_BYTES.toLong())
            assertEquals(
                "eng destination bytes untouched by the later jpn export",
                HALF_BYTES.toLong(), uriSize(dao.getById(eng.episodeId)!!.outputUri),
            )
            assertNotEquals(
                "two distinct destinations",
                dao.getById(jpn.episodeId)!!.outputUri,
                dao.getById(eng.episodeId)!!.outputUri,
            )
        }
    }

    @Test
    fun sameAudioReexport_replacesSizeMismatchedExactNameRow() {
        runBlocking {
            val title = newTitle()
            val local = localFile("same_audio_replace", FULL_BYTES)
            // A superseded copy of the SAME audio: exact audio-specific name,
            // different byte count (re-download). §36-7 one-row replacement.
            val superseded = insertPendingRow(expectedNameFor(title), animeDir, pattern(HALF_BYTES))
            makeVisible(superseded)
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            val outcome = exporter.export(entity)

            assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)
            assertNull("the superseded same-audio row is replaced", rowByUri(superseded))
            assertExactlyOneVisibleRow(stemFor(title), FULL_BYTES.toLong())
            assertEquals(
                FULL_BYTES.toLong(), uriSize(dao.getById(entity.episodeId)!!.outputUri),
            )
        }
    }

    @Test
    fun sizeMismatchedExactNameRow_neverDeletesAnotherAudiosFile() {
        runBlocking {
            val title = newTitle()
            // Variant A (jpn) already exported and visible.
            val jpnLocal = localFile("isolation_jpn", FULL_BYTES)
            val jpn = completedEntity(title, outputPath = jpnLocal.absolutePath, audio = "jpn")
            dao.upsert(jpn)
            val jpnOutcome = exporter.export(dao.getById(jpn.episodeId)!!)
            assertTrue(jpnOutcome is ExportOutcome.Exported)
            val jpnUri = Uri.parse((jpnOutcome as ExportOutcome.Exported).uri)
            val jpnSize = uriSize(jpnUri.toString())

            // Variant B (eng) has crash residue under ITS OWN exact name.
            val engLocal = localFile("isolation_eng", HALF_BYTES)
            val partial = insertPendingRow(
                expectedNameFor(title, "eng"), animeDir, pattern(10_000),
            )
            val eng = completedEntity(title, outputPath = engLocal.absolutePath, audio = "eng")
            dao.upsert(eng)

            val engOutcome = exporter.export(dao.getById(eng.episodeId)!!)
            assertTrue("expected Exported for eng, got $engOutcome", engOutcome is ExportOutcome.Exported)

            assertNotNull("the jpn file must survive the eng replacement", rowByUri(jpnUri))
            assertEquals("jpn bytes untouched", jpnSize, uriSize(jpnUri.toString()))
            assertNull("only the eng crash residue is replaced", rowByUri(partial))
            assertExactlyOneVisibleRow(stemFor(title, "jpn"), FULL_BYTES.toLong())
            assertExactlyOneVisibleRow(stemFor(title, "eng"), HALF_BYTES.toLong())
        }
    }

    @Test
    fun legacyUnsuffixedFile_neverDeletedOrRenamed() {
        runBlocking {
            val title = newTitle()
            // A legacy unsuffixed export that belongs to SOME episode, sized
            // differently from this one — it must be left exactly as it is.
            val legacy = insertPendingRow(legacyNameFor(title), animeDir, pattern(4_096))
            makeVisible(legacy)

            val local = localFile("legacy_untouched", FULL_BYTES)
            val entity = completedEntity(title, outputPath = local.absolutePath, audio = "eng")
            dao.upsert(entity)

            val outcome = exporter.export(entity)

            assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)
            val legacyRow = rowsMatching(legacyStemFor(title)).singleOrNull()
            assertNotNull("legacy file must still exist", legacyRow)
            assertEquals("legacy file is never renamed", legacyNameFor(title), legacyRow!!.name)
            assertEquals("legacy bytes untouched", 4_096L, legacyRow.size)
            assertNotEquals(
                "the legacy file was not adopted for the other audio variant",
                legacy.toString(), dao.getById(entity.episodeId)!!.outputUri,
            )
            assertExactlyOneVisibleRow(stemFor(title, "eng"), FULL_BYTES.toLong())
        }
    }

    @Test
    fun legacyAliasAdopted_whenSizeVerifiedAndUnreferenced() {
        runBlocking {
            val title = newTitle()
            val legacy = insertPendingRow(legacyNameFor(title), animeDir, pattern(FULL_BYTES))
            makeVisible(legacy)

            val local = localFile("legacy_alias_adopt", FULL_BYTES)
            val entity = completedEntity(title, outputPath = local.absolutePath, audio = "eng")
            dao.upsert(entity)

            val outcome = exporter.export(entity)

            assertTrue("expected AlreadyExported, got $outcome", outcome is ExportOutcome.AlreadyExported)
            assertEquals(
                "the size-verified orphan legacy file is adopted",
                legacy.toString(), (outcome as ExportOutcome.AlreadyExported).uri,
            )
            assertNotNull("legacy file kept in place", rowByUri(legacy))
            assertTrue(
                "no new copy may be written when the alias is adopted",
                rowsMatching(stemFor(title, "eng")).isEmpty(),
            )
            assertFalse("local removed after adoption", local.exists())
        }
    }

    @Test
    fun legacyAliasRefused_whenAnotherAudioVariantReferencesTheUri() {
        runBlocking {
            val title = newTitle()
            val legacy = insertPendingRow(legacyNameFor(title), animeDir, pattern(FULL_BYTES))
            makeVisible(legacy)

            // Another audio variant already owns this exact file.
            val owner = completedEntity(
                title, outputPath = null, outputUri = legacy.toString(), audio = "jpn",
            )
            dao.upsert(owner)

            val local = localFile("legacy_alias_refuse", FULL_BYTES)
            val entity = completedEntity(title, outputPath = local.absolutePath, audio = "eng")
            dao.upsert(entity)

            val outcome = exporter.export(entity)

            assertTrue("expected a fresh Exported, got $outcome", outcome is ExportOutcome.Exported)
            assertNotEquals(
                "a file another row references must never be adopted",
                legacy.toString(), dao.getById(entity.episodeId)!!.outputUri,
            )
            assertEquals(
                "the other variant's reference is untouched",
                legacy.toString(), dao.getById(owner.episodeId)!!.outputUri,
            )
            assertNotNull("the legacy file is never deleted", rowByUri(legacy))
            assertExactlyOneVisibleRow(legacyStemFor(title), FULL_BYTES.toLong())
            assertExactlyOneVisibleRow(stemFor(title, "eng"), FULL_BYTES.toLong())
        }
    }

    @Test
    fun legacyAliasSizeMismatch_freshCopyAndLegacyLeftAlone() {
        runBlocking {
            val title = newTitle()
            val legacy = insertPendingRow(legacyNameFor(title), animeDir, pattern(4_096))
            makeVisible(legacy)

            val local = localFile("legacy_alias_mismatch", FULL_BYTES)
            val entity = completedEntity(title, outputPath = local.absolutePath, audio = "eng")
            dao.upsert(entity)

            val outcome = exporter.export(entity)

            assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)
            assertExactlyOneVisibleRow(legacyStemFor(title), 4_096L)
            assertExactlyOneVisibleRow(stemFor(title, "eng"), FULL_BYTES.toLong())
            assertNotNull("legacy file survives", rowByUri(legacy))
        }
    }

    @Test
    fun reconciler_dualAudioReexports_neverDamageTheOtherVariant() {
        runBlocking {
            val title = newTitle()
            // Unattended startup (§29): the reconciler exports every valid
            // COMPLETED row, including an unreferenced legacy orphan that one of
            // the two variants may adopt.
            val legacy = insertPendingRow(legacyNameFor(title), animeDir, pattern(FULL_BYTES))
            makeVisible(legacy)

            val jpnLocal = localFile("recon_jpn", FULL_BYTES)
            val engLocal = localFile("recon_eng", HALF_BYTES)
            val jpn = completedEntity(title, outputPath = jpnLocal.absolutePath, audio = "jpn")
            val eng = completedEntity(title, outputPath = engLocal.absolutePath, audio = "eng")
            dao.upsert(jpn)
            dao.upsert(eng)

            repeat(3) { makeReconciler().reconcile() }

            val jpnRow = dao.getById(jpn.episodeId)!!
            val engRow = dao.getById(eng.episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, jpnRow.state)
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, engRow.state)
            assertEquals(
                "jpn adopts the size-verified orphan legacy file",
                legacy.toString(), jpnRow.outputUri,
            )
            assertEquals(
                "the eng variant is unaffected and has its own destination",
                HALF_BYTES.toLong(), uriSize(engRow.outputUri),
            )
            assertNotEquals("two distinct destinations", jpnRow.outputUri, engRow.outputUri)
            assertNotNull("the legacy file is never deleted", rowByUri(legacy))
            assertEquals(
                "the legacy file is never renamed",
                legacyNameFor(title), rowsMatching(legacyStemFor(title)).single().name,
            )
            assertExactlyOneVisibleRow(stemFor(title, "eng"), HALF_BYTES.toLong())
            assertTrue(
                "no stray copy under the jpn name",
                rowsMatching(stemFor(title, "jpn")).isEmpty(),
            )
        }
    }

    @Test
    fun pendingUniquifiedAndLegacyCrashResidue_replacedInSameRun() {
        runBlocking {
            val title = newTitle()
            val local = localFile("uniquified_residue", FULL_BYTES)
            val stem = stemFor(title)
            // Invisible crash residue under MediaStore's uniquified ` (n)` form:
            // app-owned, unreferenced, wrong size → dropped in THIS run rather
            // than waiting for the next startup sweep.
            val uniquifiedResidue = insertPendingRow("$stem (2).mp4", animeDir, pattern(HALF_BYTES))
            // A pre-Slice-A copy attempt's residue: pending, legacy-shaped,
            // unreferenced → also removed here (a VISIBLE legacy file is never
            // touched — see legacyUnsuffixedFile_neverDeletedOrRenamed).
            val legacyResidue = insertPendingRow(legacyNameFor(title), animeDir, pattern(2_048))
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            makeReconciler().reconcile()

            assertNull("` (n)` pending residue must be gone in the same run", rowByUri(uniquifiedResidue))
            assertNull("legacy-shaped pending residue must be gone in the same run", rowByUri(legacyResidue))
            assertExactlyOneVisibleRow(stem, FULL_BYTES.toLong())
            assertNotNull("outputUri persisted", dao.getById(entity.episodeId)!!.outputUri)
            assertFalse("local removed after the verified export", local.exists())
        }
    }

    @Test
    fun legacyAliasRefused_whenSameAudioRowReferencesTheUri() {
        runBlocking {
            val title = newTitle()
            val legacy = insertPendingRow(legacyNameFor(title), animeDir, pattern(FULL_BYTES))
            makeVisible(legacy)

            // A DIFFERENT row already points at this file: two rows owning one
            // output would let either episode's deletion destroy the other's,
            // so it is refused even when the audio value matches.
            val owner = completedEntity(
                title, outputPath = null, outputUri = legacy.toString(), audio = "sub",
            )
            dao.upsert(owner)

            val local = localFile("legacy_alias_same_audio", FULL_BYTES)
            val entity = completedEntity(title, outputPath = local.absolutePath)
            dao.upsert(entity)

            val outcome = exporter.export(entity)

            assertTrue("expected a fresh Exported, got $outcome", outcome is ExportOutcome.Exported)
            assertNotEquals(
                "a referenced file must never be shared between rows",
                legacy.toString(), dao.getById(entity.episodeId)!!.outputUri,
            )
            assertEquals(
                "the referencing row keeps its URI",
                legacy.toString(), dao.getById(owner.episodeId)!!.outputUri,
            )
            assertNotNull("legacy file intact", rowByUri(legacy))
            assertExactlyOneVisibleRow(legacyStemFor(title), FULL_BYTES.toLong())
            assertExactlyOneVisibleRow(stemFor(title), FULL_BYTES.toLong())
        }
    }

    @Test
    fun sameAudioReexport_afterRedownload_retiresSelfReferencedStaleRow() {
        runBlocking {
            val title = newTitle()
            // The self-referenced size-mismatch path: the row still points at its
            // OLD export, but the re-downloaded local bytes differ, so §30.3
            // falls through and §30.2 replaces the stale reference.
            val stale = insertPendingRow(expectedNameFor(title), animeDir, pattern(HALF_BYTES))
            makeVisible(stale)
            val local = localFile("same_audio_retire", FULL_BYTES)
            val entity = completedEntity(
                title, outputPath = local.absolutePath, outputUri = stale.toString(),
            )
            dao.upsert(entity)

            val outcome = exporter.export(entity)

            assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)
            assertNull("the stale same-audio row is retired", rowByUri(stale))
            assertExactlyOneVisibleRow(stemFor(title), FULL_BYTES.toLong())
            assertNotEquals(
                "the stale reference is replaced by the fresh copy",
                stale.toString(), dao.getById(entity.episodeId)!!.outputUri,
            )
        }
    }

    // ------------------------------------------------------------------
    // Fixtures / helpers
    // ------------------------------------------------------------------

    /** Production-shaped retry path: resolver + exporter injected (§30.1). */
    private fun makeReconciler() = DownloadStateReconciler(
        episodeDao = dao,
        workManager = WorkManager.getInstance(context),
        contentResolver = contentResolver,
        exporter = MediaStoreExporter(contentResolver, dao, database.downloadSessionDao()),
    )

    private fun registerToken(prefix: String): String =
        "$prefix${System.nanoTime()}".also { tokens += it }

    private fun newTitle(): String = "Export Test ${registerToken("pbx")}"

    private fun stemFor(title: String, audio: String = "sub") =
        expectedNameFor(title, audio).removeSuffix(".mp4")

    /**
     * The Slice A destination name: the audio discriminator is part of it, so a
     * fixture row's own `audio` must be reflected (default = the fixture value).
     */
    private fun expectedNameFor(title: String, audio: String = "sub") =
        ExportFileNames.exportedName(
            FileNameGenerator.formatEpisodePrefix(1.0f),
            title,
            audio,
        )

    /** Pre-Slice-A unsuffixed name — the legacy alias every old export used. */
    private fun legacyStemFor(title: String) = legacyNameFor(title).removeSuffix(".mp4")

    private fun legacyNameFor(title: String) = ExportFileNames.legacyName(
        FileNameGenerator.formatEpisodePrefix(1.0f),
        title,
    )

    private fun pattern(bytes: Int) = ByteArray(bytes) { (it % 251).toByte() }

    private fun localFile(tag: String, bytes: Int): File =
        File(context.filesDir, "pbx_export_${System.nanoTime()}_$tag.mp4")
            .also {
                it.writeBytes(pattern(bytes))
                localFiles += it
            }

    private fun completedEntity(
        title: String,
        outputPath: String?,
        outputUri: String? = null,
        audio: String = "sub",
    ): EpisodeDownloadEntity {
        val now = System.currentTimeMillis()
        return EpisodeDownloadEntity(
            episodeId = "pbx-ep-${System.nanoTime()}",
            sessionId = testSession.sessionId,
            episodeNumber = 1.0f,
            episodeSession = "es-pbx",
            title = title,
            fansub = "TestFansub",
            audio = audio,
            playUrl = "https://example.com/play",
            totalSegments = 1,
            completedSegments = 1,
            state = EpisodeDownloadEntity.STATE_COMPLETED,
            outputPath = outputPath,
            outputUri = outputUri,
            createdAt = now,
            updatedAt = now,
            segmentDir = null,
        )
    }

    /**
     * Direct MediaStore insert of an `IS_PENDING = 1` row holding [bytes] at
     * [relativePath] — the anime directory for kill-matrix fixtures, the
     * legacy flat directory for pre-Gate-A recovery fixtures.
     */
    private fun insertPendingRow(displayName: String, relativePath: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore insert at $relativePath must succeed", uri)
        contentResolver.openOutputStream(uri!!, "w")!!.use { it.write(bytes) }
        return uri
    }

    private fun makeVisible(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        assertTrue(
            "row must be updatable to visible",
            contentResolver.update(uri, values, null, null) > 0,
        )
    }

    private data class Row(
        val uri: Uri,
        val name: String,
        val size: Long,
        val pending: Boolean,
        val relativePath: String,
    )

    /**
     * Every row WE own anywhere under `Movies/` — the legacy flat directory
     * AND every per-anime directory (Gate A), matching the production sweep
     * selection exactly. Bundle form with MATCH_INCLUDE: the legacy
     * (selection, args) overload silently excludes IS_PENDING=1 rows (verified
     * on API 36), and these assertions must count crash-residue pending rows too.
     */
    private fun rowsInMoviesTree(): List<Row> = queryRows(
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? " +
            "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
        arrayOf(OutputPathNames.MOVIES_PREFIX + "%", BuildConfig.APPLICATION_ID),
    )

    /** Shared query: [selection] + [selectionArgs], pending rows included. */
    private fun queryRows(selection: String, selectionArgs: Array<String>): List<Row> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val rows = mutableListOf<Row>()
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                "_id",
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            args,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pendingIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                rows += Row(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIdx),
                    ),
                    name = cursor.getString(nameIdx) ?: continue,
                    size = if (cursor.isNull(sizeIdx)) -1L else cursor.getLong(sizeIdx),
                    pending = !cursor.isNull(pendingIdx) && cursor.getInt(pendingIdx) == 1,
                    relativePath = if (cursor.isNull(pathIdx)) "" else cursor.getString(pathIdx),
                )
            }
        }
        return rows
    }

    /** All rows whose name is the exact stem or a MediaStore-uniquified ` (n)` form. */
    private fun rowsMatching(stem: String): List<Row> {
        val uniquified = Regex("^" + Pattern.quote(stem) + " \\(\\d+\\)\\.mp4$")
        return rowsInMoviesTree().filter { it.name == "$stem.mp4" || uniquified.matches(it.name) }
    }

    /** §36-7 duplicate prevention: exactly one row, visible, byte-complete. */
    private fun assertExactlyOneVisibleRow(stem: String, expectedSize: Long) {
        val matches = rowsMatching(stem)
        assertEquals(
            "exactly one MediaStore row may exist for $stem, found: ${matches.map { it.name }}",
            1, matches.size,
        )
        assertFalse("the row must be visible (IS_PENDING = 0)", matches[0].pending)
        assertEquals("visible row must be byte-complete", expectedSize, matches[0].size)
    }

    private fun uriSize(uriString: String?): Long {
        assertNotNull("outputUri must be present", uriString)
        val uri = Uri.parse(uriString!!)
        contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null,
        )!!.use { cursor ->
            assertTrue("outputUri row must resolve", cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    /** The `RELATIVE_PATH` of the row behind [uriString] (e.g. `Movies/Dr._Stone/`). */
    private fun uriRelativePath(uriString: String?): String {
        assertNotNull("outputUri must be present", uriString)
        val uri = Uri.parse(uriString!!)
        contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
        )!!.use { cursor ->
            assertTrue("outputUri row must resolve", cursor.moveToFirst())
            return cursor.getString(0)
        }
    }

    private fun rowByUri(uri: Uri): Uri? = try {
        contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) uri else null
        }
    } catch (e: Exception) {
        null
    }
}
