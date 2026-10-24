package com.pahebatcher.app.ui

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.export.OutputPathNames
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.support.playerProbe
import com.pahebatcher.app.ui.navigation.PlayerRoute
import com.pahebatcher.app.ui.navigation.navigateToPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Gate A Device Test 4 — legacy flat compatibility on a REAL file at the
 * pre-Gate-A flat path `Movies/PaheBatcher/`.
 *
 * Production never writes this path anymore: `OutputPathNames.LEGACY_RELATIVE_PATH`
 * is documented as "NEW exports never write here", and `MediaStoreExporter` only
 * queries it in order to ADOPT a byte-identical pre-Gate-A file. No device
 * running the current build can therefore hold a naturally produced legacy
 * export, so the precondition this suite originally stated — "a file this device
 * previously exported" — is environmentally unsatisfiable by construction and
 * could never hold (it failed with `got 0` on every full run). The contract is
 * PATH-based instead: a real-sized file at the legacy path must still resolve
 * and play through production, and nothing may migrate it.
 *
 * When this device holds no such file, the test seeds one itself with FFmpegKit
 * (see [ensureLegacyFixture]) — synthesized, but genuinely playable and over
 * 1 MB, so every assertion below still exercises the real production playback
 * path rather than a stub. The fixture is always referenced BY ITS OWN URI; the
 * test never picks an arbitrary row at this path, because a sibling suite writes
 * 2048-byte pending fixtures there which would satisfy an existence check while
 * failing the real-export check.
 *
 * Verifies, through the production application only:
 *  1. the legacy MediaStore row still resolves (same URI, same
 *     `RELATIVE_PATH = Movies/PaheBatcher/`, same byte size);
 *  2. the Room episode row that references it still points at the same URI —
 *     Gate A must never migrate, rename or delete legacy exports;
 *  3. the file plays through the PRODUCTION playback path
 *     (PlayerRoute → PlaybackSourceResolver → the persisted `content://`
 *     URI) — the "still resolves and plays" compatibility contract;
 *  4. playback itself must not migrate the row (post-conditions re-check
 *     path, size and the Room reference).
 *
 * Before any assertion it logs a full Room dump (sessions + episodes) so the
 * captured Logcat also documents the device's pre-existing download state.
 *
 * State handling: a natural pre-Gate-A Room reference is preferred when one
 * points at a genuine export; otherwise a COMPLETED row is seeded over the
 * fixture's own URI — the established `SafPlaybackDeviceTest.seedSafEpisode`
 * pattern — so playback, path and no-mutation contracts always run against a
 * real file. A seeded session and a seeded MediaStore row are removed again in
 * tearDown; natural device rows are never touched.
 *
 * This is a playback/no-migration test, NOT an adoption test: legacy ADOPTION
 * (a byte-identical re-download re-adopting a pre-Gate-A file) is covered by
 * `MediaStoreExportTest` and by `GateASpecialTitleDeviceTest`'s `expectedDir`
 * leg.
 */
@RunWith(AndroidJUnit4::class)
class LegacyFlatPlaybackDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = PaheDatabase.getInstance(context)
    }

    /** A session this run seeded over the real legacy URI (cleaned up). */
    private var seededSessionId: String? = null

    /** The MediaStore row THIS run created as the legacy fixture (cleaned up). */
    private var seededMediaUri: Uri? = null

    /**
     * Byte floor below which a row at the legacy path is a test fixture rather
     * than an export. Mirrors the assertion below — a real export is never
     * small, and a sibling suite deliberately writes 2048-byte pending rows at
     * exactly this path.
     */
    private val minExportBytes = 1_000_000L

    @After
    fun tearDown() {
        // Remove ONLY what this run created as playback scaffolding; natural
        // device rows are never touched. Both cleanups run on every exit path.
        seededSessionId?.let { id ->
            runBlocking { Phase10TestSupport.deleteSession(db, id) }
        }
        seededMediaUri?.let { uri ->
            val removed = runCatching { context.contentResolver.delete(uri, null, null) }
                .getOrDefault(-1)
            Log.i(TAG, "ADB_LEGACY teardown removed=$removed uri=$uri")
        }
    }

    /** The app's own `Movies/PaheBatcher/` rows (MATCH_INCLUDE pending). */
    private data class MediaRow(val uri: Uri, val name: String, val size: Long, val path: String)

    private fun legacyRows(): List<MediaRow> {
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? " +
                    "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(OutputPathNames.LEGACY_RELATIVE_PATH, context.packageName),
            )
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val rows = mutableListOf<MediaRow>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                "_id",
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            args, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                rows += MediaRow(
                    uri = Uri.parse("content://media/external/video/media/$id"),
                    name = cursor.getString(1),
                    size = cursor.getLong(2),
                    path = cursor.getString(3),
                )
            }
        }
        return rows
    }

    private fun rowByUri(uri: Uri): MediaRow? =
        legacyRows().firstOrNull { it.uri.toString() == uri.toString() }

    /**
     * A real, genuinely playable legacy flat export — seeding one when this
     * device holds none.
     *
     * Production has not written `Movies/PaheBatcher/` since Gate A introduced
     * per-anime directories, and this device holds no export at all, so the
     * precondition the suite originally stated cannot be met naturally. Rather
     * than pretend one exists, the fixture is established here.
     *
     * It is synthesized with FFmpegKit, the mechanism `Phase11UiTestSupport.ensureTestMp4`
     * already uses, but over a MOVING source with an all-intra GOP. The solid
     * `color` source `ensureTestMp4` uses compresses to ~44 kB here — libopenh264
     * refuses to spend bits on an unchanging frame, and its rate control ignores
     * `-b:v` (verified on this device: `-b:v 500k` still produced 44002 bytes).
     * `testsrc` changes every frame and `-g 1` makes every frame a keyframe, so
     * the size is bounded below by the frame count rather than by the encoder's
     * opinion of how interesting the content is. A keyframe every frame is also
     * the strongest possible answer to the mid-file freeze libopenh264 causes
     * when it emits a single keyframe (documented in full at `ensureTestMp4`).
     *
     * The returned row is always one this run can identify by URI, so the test
     * never has to pick an arbitrary row at the legacy path.
     */
    private fun ensureLegacyFixture(): MediaRow {
        legacyRows().firstOrNull { it.size > minExportBytes }?.let { existing ->
            Log.i(TAG, "ADB_LEGACY reusing a real legacy export ${existing.uri} ${existing.size}")
            return existing
        }

        val token = "pblegacy${System.nanoTime()}"
        val out = File(context.cacheDir, "$token.mp4")
        val command = "-y -f lavfi -i testsrc=size=320x240:rate=25:duration=30 " +
            "-c:v libopenh264 -g 1 -pix_fmt yuv420p -f mp4 ${out.absolutePath}"
        val session = FFmpegKit.execute(command)
        assertTrue(
            "the legacy fixture must really encode (rc=${session.returnCode})",
            ReturnCode.isSuccess(session.returnCode),
        )
        assertTrue(
            "the legacy fixture must be a real export, not a stub — was " +
                "${out.length()} bytes (command: $command)",
            out.length() > minExportBytes,
        )

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, OutputPathNames.LEGACY_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        )
        assertNotNull("seeding the legacy flat fixture must succeed", uri)
        context.contentResolver.openOutputStream(uri!!, "w")!!.use { sink ->
            out.inputStream().use { it.copyTo(sink) }
        }
        // Publish it: a genuine legacy export is a visible row, not a pending one.
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null,
        )
        out.delete()
        seededMediaUri = uri

        val seeded = rowByUri(uri)
        assertNotNull(
            "the seeded fixture must be queryable at ${OutputPathNames.LEGACY_RELATIVE_PATH}",
            seeded,
        )
        Log.i(
            TAG,
            "ADB_LEGACY seeded fixture ${seeded!!.uri} ${seeded.path}${seeded.name} ${seeded.size}",
        )
        return seeded
    }

    /** Top-of-stack check used by the playback verification. */
    private fun topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<PlayerRoute>(activity) }
    }

    @Test
    fun legacyFlatExport_stillResolvesAndPlaysThroughProductionPlayback() {
        // 0. Diagnostic dump first — visible in Logcat even if a later
        //    assertion fails (pre-existing state documentation).
        val sessions = runBlocking { db.downloadSessionDao().getAll().first() }
        val episodes = runBlocking { db.episodeDownloadDao().observeAllOrdered().first() }
        Log.i(
            TAG,
            "ADB_DUMP sessions=" + sessions.joinToString(" ; ") {
                "${it.sessionId}|title=${it.title}|state=${it.state}"
            },
        )
        Log.i(
            TAG,
            "ADB_DUMP episodes=" + episodes.joinToString(" ; ") {
                "${it.episodeId}|state=${it.state}|out=${it.outputUri}"
            },
        )

        // 1. A REAL pre-Gate-A legacy flat file exists on this device. Production
        //    no longer writes this path, so the fixture is established here when
        //    absent (see the class KDoc). It is used BELOW BY ITS OWN URI — never
        //    "the first row at this path" — because a sibling suite writes
        //    2048-byte pending fixtures there, and taking an arbitrary one would
        //    satisfy this existence check while failing the real-export check.
        val fixture = ensureLegacyFixture()
        val legacy = legacyRows()
        Log.i(TAG, "ADB_LEGACY rows=" + legacy.joinToString(" ; ") { "${it.uri} ${it.path}${it.name} ${it.size}" })
        assertTrue(
            "this device must hold at least one real legacy flat export " +
                "(got ${legacy.size})",
            legacy.size >= 1,
        )

        // 2. A Room episode row referencing one of them is the production
        //    playback entry point. A NATURAL pre-Gate-A reference is preferred,
        //    but only when it points at a genuine export; otherwise a COMPLETED
        //    row is seeded over this run's own fixture URI (class KDoc — no fake
        //    file, no fake URI, playback stays production).
        val realLegacy = legacy.filter { it.size > minExportBytes }
        assertTrue(
            "at least one row at the legacy path must be a real export of " +
                "$minExportBytes+ bytes (sizes: ${legacy.map { it.size }})",
            realLegacy.isNotEmpty(),
        )
        val natural = episodes.firstOrNull { ep ->
            ep.outputUri != null && realLegacy.any { it.uri.toString() == ep.outputUri }
        }
        val referenced: EpisodeDownloadEntity = natural ?: run {
            val sessionId = "legacyflat-${System.nanoTime()}"
            val episodeId = "legacyflat-ep-${System.nanoTime()}"
            Log.i(
                TAG,
                "ADB_LEGACY no natural Room reference — seeding a completed row " +
                    "over this run's fixture URI",
            )
            val seeded = EpisodeDownloadEntity(
                episodeId = episodeId,
                sessionId = sessionId,
                episodeNumber = 1f,
                episodeSession = "es-$episodeId",
                title = "Legacy Flat Playback",
                fansub = "test",
                audio = "sub",
                playUrl = "https://animepahe.local/play/$episodeId",
                totalSegments = 1,
                completedSegments = 1,
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = null,
                outputUri = fixture.uri.toString(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                segmentDir = null,
            )
            runBlocking {
                Phase10TestSupport.insertSession(db, sessionId)
                db.episodeDownloadDao().upsert(seeded)
            }
            seededSessionId = sessionId
            seeded
        }
        val legacyRow = realLegacy.first { it.uri.toString() == referenced.outputUri }
        Log.i(TAG, "ADB_LEGACY referenced episodeId=${referenced.episodeId} state=${referenced.state}")
        assertTrue(
            "the legacy file must be a real video export, was ${legacyRow.size} bytes",
            legacyRow.size > 1_000_000L,
        )
        assertEquals(
            "the referenced row really lives in the legacy flat directory",
            OutputPathNames.LEGACY_RELATIVE_PATH, legacyRow.path,
        )

        // 3. Production playback of the legacy export.
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigateToPlayer(referenced.episodeId)
        }
        assertTrue(
            "player route must open for the legacy episode",
            awaitCondition(20_000) { topRouteIs() },
        )
        val loaded = awaitCondition(30_000) {
            (composeRule.playerProbe()?.durationMs ?: 0L) > 0L
        }
        assertTrue(
            "the legacy flat file must load as real media " +
                "(probe=${composeRule.playerProbe()})",
            loaded,
        )
        val playing = awaitCondition(30_000) {
            composeRule.playerProbe()?.isPlaying == true
        }
        assertTrue(
            "the legacy flat file must play through the production player " +
                "(probe=${composeRule.playerProbe()})",
            playing,
        )
        Log.i(TAG, "ADB_LEGACY_PLAYBACK verified episodeId=${referenced.episodeId}")

        // 4. Post-conditions: playback resolved the legacy file IN PLACE —
        //    path, bytes and the Room reference are all untouched.
        val after = rowByUri(legacyRow.uri)
        assertNotNull("the legacy row must survive playback", after)
        assertEquals("legacy path never migrated", OutputPathNames.LEGACY_RELATIVE_PATH, after!!.path)
        assertEquals("legacy bytes never altered", legacyRow.size, after.size)
        val roomAfter = runBlocking {
            db.episodeDownloadDao().getById(referenced.episodeId)
        }
        assertEquals(
            "Room outputUri never rewritten by playback",
            referenced.outputUri, roomAfter!!.outputUri,
        )
    }

    private companion object {
        const val TAG = "LegacyFlatPlaybackDeviceTest"
    }
}
