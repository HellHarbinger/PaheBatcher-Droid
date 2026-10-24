package com.pahebatcher.app.data.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.BuildConfig
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.hls.FileNameGenerator
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.testdocs.PBTestDocumentsProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [ExportDestinationRouter] selection rules
 * with the REAL providers and REAL exporters — SAF tree when configured AND
 * validated, the MediaStore `Movies/<anime title>/` default otherwise (Gate A
 * per-anime directory), and the rename-less same-call fallback.
 *
 * No MediaStore row may exist for a SAF-routed export, and no SAF child may
 * exist for a MediaStore-routed export — the tests assert both directions.
 */
@RunWith(AndroidJUnit4::class)
class ExportDestinationRouterTest {

    private lateinit var context: Context
    private lateinit var database: PaheDatabase
    private lateinit var config: AppConfig
    private lateinit var outputTrees: OutputTreeRepository
    private lateinit var router: ExportDestinationRouter

    private val tokens = mutableListOf<String>()
    private val localFiles = mutableListOf<File>()

    private val renameTree: Uri
        get() = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_RENAME)

    private val noRenameTree: Uri
        get() = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_NORENAME)

    private val testSession = DownloadSessionEntity(
        sessionId = "sess-router",
        title = "Router Session",
        animeSession = "anime-router",
        host = "animepahe.com",
        quality = 2,
        audioLang = "sub",
        outputDir = "/downloads",
        cacheDir = "/cache",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        state = "QUEUED",
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        config = AppConfig(context)
        outputTrees = OutputTreeRepository(context.contentResolver, config)
        runBlocking {
            database.downloadSessionDao().insertIfAbsent(testSession)
            outputTrees.clearOutputTree()
        }
        router = ExportDestinationRouter(
            outputTrees = outputTrees,
            config = config,
            safExporter = SafExporter(
                contentResolver = context.contentResolver,
                episodeDao = database.episodeDownloadDao(),
                config = config,
                sessionDao = database.downloadSessionDao(),
            ),
            mediaStoreExporter = MediaStoreExporter(
                context.contentResolver,
                database.episodeDownloadDao(),
                database.downloadSessionDao(),
            ),
        )
    }

    @After
    fun teardown() {
        // Configuration + SAF hygiene.
        runBlocking {
            try {
                outputTrees.clearOutputTree()
            } catch (_: Exception) {
            }
            config.updateOutputTreeRenameOk(true)
        }
        SafTestSupport.releasePersistableGrant(context, renameTree)
        SafTestSupport.releasePersistableGrant(context, noRenameTree)
        SafTestSupport.clearTreeChildren(context, renameTree)
        SafTestSupport.clearTreeChildren(context, noRenameTree)
        // MediaStore hygiene (Bundle + MATCH_INCLUDE so pending rows are seen;
        // LIKE covers legacy flat + per-anime directories — Gate A).
        if (tokens.isNotEmpty()) {
            val args = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? " +
                        "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(OutputPathNames.MOVIES_PREFIX + "%", BuildConfig.APPLICATION_ID),
                )
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", MediaStore.MediaColumns.DISPLAY_NAME),
                args, null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("_id")
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val doomed = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    if (tokens.any { name.contains(it) }) doomed += cursor.getLong(idIdx)
                }
                doomed.forEach { id ->
                    context.contentResolver.delete(
                        ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id,
                        ),
                        null, null,
                    )
                }
            }
        }
        for (file in localFiles) file.delete()
        database.close()
    }

    // ------------------------------------------------------------------
    // 92.2 selection rules → MediaStore default
    // ------------------------------------------------------------------

    @Test
    fun noTreeConfigured_routesToMediaStore() = runBlocking {
        val bytes = ByteArray(30_000) { (it % 251).toByte() }
        val (episode, _) = fixture(bytes)

        val outcome = router.export(episode)

        assertMediaStoreExported(outcome, stemFor(episode), bytes.size.toLong())
        assertTrue("SAF tree must stay untouched", children(renameTree).isEmpty())
        assertFalse("X6: local removed on the MediaStore path", File(episode.outputPath!!).exists())
    }

    @Test
    fun configuredWithoutGrant_routesToMediaStore() = runBlocking {
        val bytes = ByteArray(30_000) { (it % 251).toByte() }
        val (episode, _) = fixture(bytes)
        // Configured key, NO grant → 92.3/103.4: MediaStore default, pipeline
        // never blocked.
        config.updateOutputTree(renameTree.toString())

        val outcome = router.export(episode)

        assertMediaStoreExported(outcome, stemFor(episode), bytes.size.toLong())
        assertTrue("unusable tree must not be written to", children(renameTree).isEmpty())
    }

    @Test
    fun renameOkFalse_routesToMediaStoreWithoutSafAttempt() = runBlocking {
        val bytes = ByteArray(30_000) { (it % 251).toByte() }
        val (episode, _) = fixture(bytes)
        SafTestSupport.establishPersistableGrant(context, renameTree)
        val state = outputTrees.setOutputTree(renameTree.toString())
        assertTrue("fixture must be Valid, got $state", state is OutputTreeState.Valid)
        config.updateOutputTreeRenameOk(false)

        val outcome = router.export(episode)

        assertMediaStoreExported(outcome, stemFor(episode), bytes.size.toLong())
        assertTrue(
            "flag false → no SAF attempt at all (104.4 short-circuit)",
            children(renameTree).isEmpty(),
        )
        assertFalse(
            "flag must remain persisted false",
            config.outputTreeRenameOk.first(),
        )
    }

    // ------------------------------------------------------------------
    // Valid tree → SAF export (92.2 positive branch)
    // ------------------------------------------------------------------

    @Test
    fun validTree_routesToSafExport() = runBlocking {
        val bytes = ByteArray(50_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)
        SafTestSupport.establishPersistableGrant(context, renameTree)
        val state = outputTrees.setOutputTree(renameTree.toString())
        assertTrue("fixture must be Valid, got $state", state is OutputTreeState.Valid)

        val outcome = router.export(episode)

        assertTrue("router must return the SAF outcome, got $outcome",
            outcome is ExportOutcome.Exported)
        val uri = (outcome as ExportOutcome.Exported).uri
        assertTrue(
            "SAF destination expected, got $uri",
            uri.startsWith("content://${PBTestDocumentsProvider.AUTHORITY}/"),
        )
        assertEquals("SAF child byte-verified", bytes.size.toLong(), docSize(uri))
        assertEquals("X4: outputUri persisted", uri,
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri)
        assertFalse("X6: local removed", local.exists())
        assertTrue(
            "SAF-routed export must create NO MediaStore row",
            mediaRowsFor(stemFor(episode)).isEmpty(),
        )
        assertTrue("no .pb.part residue", children(renameTree)
            .none { SafExportNames.isPart(it) })
    }

    // ------------------------------------------------------------------
    // 104.4 rename-less tree → same-call MediaStore fallback + flag
    // ------------------------------------------------------------------

    @Test
    fun renameLessTree_fallsBackToMediaStoreInSameCall() = runBlocking {
        val bytes = ByteArray(36_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)
        SafTestSupport.establishPersistableGrant(context, noRenameTree)
        val state = outputTrees.setOutputTree(noRenameTree.toString())
        assertTrue("fixture must be Valid, got $state", state is OutputTreeState.Valid)

        val outcome = router.export(episode)

        // The SAME call completed through the MediaStore default (104.4).
        assertMediaStoreExported(outcome, stemFor(episode), bytes.size.toLong())
        assertFalse("X6 ran via the fallback", local.exists())
        assertFalse(
            "rename-less discovery must persist output_tree_rename_ok=false",
            config.outputTreeRenameOk.first(),
        )
        assertTrue(
            "SAF attempt left no residue (part cleaned by 104.4)",
            children(noRenameTree).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // §3.1 (DECISIONS 157): destinationOutput mirrors the export routing
    // ------------------------------------------------------------------

    @Test
    fun destinationOutput_noTreeConfigured_delegatesToMediaStoreProbe() = runBlocking {
        val bytes = ByteArray(30_000) { (it % 251).toByte() }
        val (episode, _) = fixture(bytes)

        assertNull(
            "nothing exported yet → no destination",
            router.destinationOutput(
                episode.episodeNumber, episode.title, episode.audio, null, episode.sessionId,
            ),
        )

        val outcome = router.export(episode)
        assertTrue("fixture must export, got $outcome", outcome is ExportOutcome.Exported)

        val probed = router.destinationOutput(
            episode.episodeNumber, episode.title, episode.audio, null, episode.sessionId,
        )
        assertEquals(
            "the router probe surfaces the MediaStore row the export created",
            (outcome as ExportOutcome.Exported).uri,
            probed.toString(),
        )
    }

    @Test
    fun destinationOutput_validSafTree_returnsNull_andLeavesTheTreeUntouched() = runBlocking {
        val bytes = ByteArray(50_000) { (it % 251).toByte() }
        val (episode, _) = fixture(bytes)
        SafTestSupport.establishPersistableGrant(context, renameTree)
        val state = outputTrees.setOutputTree(renameTree.toString())
        assertTrue("fixture must be Valid, got $state", state is OutputTreeState.Valid)

        val outcome = router.export(episode)
        assertTrue("fixture must export via SAF, got $outcome", outcome is ExportOutcome.Exported)
        val childCountBefore = children(renameTree).size
        assertTrue("the SAF export landed a child", childCountBefore > 0)

        assertNull(
            "an active SAF tree is deliberately NOT probed — conservative null " +
                "(the submission gate admits; the reconciler demotes — unchanged)",
            router.destinationOutput(
                episode.episodeNumber, episode.title, episode.audio, null, episode.sessionId,
            ),
        )
        assertEquals(
            "the probe is read-only: nothing created, renamed or deleted in the user's tree",
            childCountBefore, children(renameTree).size,
        )
    }

    @Test
    fun destinationOutput_renameOkFalse_routesToTheMediaStoreProbe() = runBlocking {
        val bytes = ByteArray(30_000) { (it % 251).toByte() }
        val (episode, _) = fixture(bytes)
        SafTestSupport.establishPersistableGrant(context, renameTree)
        val state = outputTrees.setOutputTree(renameTree.toString())
        assertTrue("fixture must be Valid, got $state", state is OutputTreeState.Valid)
        config.updateOutputTreeRenameOk(false)

        val outcome = router.export(episode)
        assertMediaStoreExported(outcome, stemFor(episode), bytes.size.toLong())

        val probed = router.destinationOutput(
            episode.episodeNumber, episode.title, episode.audio, null, episode.sessionId,
        )
        assertEquals(
            "the 104.4 flag routes the PROBE to MediaStore exactly like the export",
            (outcome as ExportOutcome.Exported).uri,
            probed.toString(),
        )
        assertTrue("no SAF attempt by the probe either", children(renameTree).isEmpty())
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private data class MediaRow(val name: String, val size: Long, val pending: Boolean)

    private fun assertMediaStoreExported(
        outcome: ExportOutcome,
        stem: String,
        expectedSize: Long,
    ) {
        assertTrue("expected Exported via MediaStore, got $outcome",
            outcome is ExportOutcome.Exported)
        val uri = (outcome as ExportOutcome.Exported).uri
        assertTrue("MediaStore destination expected, got $uri",
            uri.startsWith("content://media/"))
        val rows = mediaRowsFor(stem)
        assertEquals(
            "exactly one visible MediaStore row, found ${rows.map { it.name }}",
            1, rows.size,
        )
        assertFalse("row must be visible", rows[0].pending)
        assertEquals("row byte-complete", expectedSize, rows[0].size)
    }

    private fun mediaRowsFor(stem: String): List<MediaRow> {
        val uniquified = Regex("^" + java.util.regex.Pattern.quote(stem) + " \\(\\d+\\)\\.mp4$")
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? " +
                    "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(OutputPathNames.MOVIES_PREFIX + "%", BuildConfig.APPLICATION_ID),
            )
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val rows = mutableListOf<MediaRow>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING,
            ),
            args, null,
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pendingIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                if (name == "$stem.mp4" || uniquified.matches(name)) {
                    rows += MediaRow(
                        name = name,
                        size = if (cursor.isNull(sizeIdx)) -1L else cursor.getLong(sizeIdx),
                        pending = !cursor.isNull(pendingIdx) && cursor.getInt(pendingIdx) == 1,
                    )
                }
            }
        }
        return rows
    }

    private fun fixture(bytes: ByteArray): Pair<EpisodeDownloadEntity, File> {
        val token = "pbxr${System.nanoTime()}".also { tokens += it }
        val title = "Router Test $token"
        val local = File(context.filesDir, "pbx_router_${System.nanoTime()}.mp4")
        local.writeBytes(bytes)
        localFiles += local
        val episode = EpisodeDownloadEntity(
            episodeId = "pbx-router-${System.nanoTime()}",
            sessionId = testSession.sessionId,
            episodeNumber = 1.0f,
            episodeSession = "es-router",
            title = title,
            fansub = "TestFansub",
            audio = "sub",
            playUrl = "https://example.com/play",
            totalSegments = 1,
            completedSegments = 1,
            state = EpisodeDownloadEntity.STATE_COMPLETED,
            outputPath = local.absolutePath,
            outputUri = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            segmentDir = null,
        )
        runBlocking { database.episodeDownloadDao().upsert(episode) }
        return episode to local
    }

    private fun stemFor(episode: EpisodeDownloadEntity): String =
        ExportFileNames.exportedName(
            FileNameGenerator.formatEpisodePrefix(episode.episodeNumber),
            episode.title,
            episode.audio,
        ).removeSuffix(".mp4")

    private fun children(tree: Uri): List<String> {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            android.provider.DocumentsContract.getTreeDocumentId(tree),
        )
        return context.contentResolver.query(
            childrenUri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIdx)) }
        } ?: emptyList()
    }

    private fun docSize(uriString: String): Long {
        context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )!!.use { cursor ->
            assertTrue("document must resolve", cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }
}
