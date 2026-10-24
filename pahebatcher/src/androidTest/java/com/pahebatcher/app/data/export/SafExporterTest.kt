package com.pahebatcher.app.data.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [SafExporter] mechanics against the REAL test
 * DocumentsProvider — same-UID SAF operations, byte-verified export (X4/X6
 * order), the 104.6 collision rule, the 104.5 `.pb.part` crash-recovery rows,
 * the 104.4 rename-less signal, and the 104.3 failure cleanup.
 *
 * Gate A: every export lands in `<selected tree>/<sanitized anime title>/`
 * (Python `sanitize(title)` parity) — fixtures and assertions are directory-
 * aware, legacy ROOT-level crash residue is finalized in place, and existing
 * root-level user files are never deleted, renamed or moved.
 *
 * No grants are needed: the test provider lives in this package's debug
 * source set, so every DocumentsContract call is a same-UID call — the same
 * contract an in-package provider would have on a real device.
 */
@RunWith(AndroidJUnit4::class)
class SafExporterTest {

    private lateinit var context: Context
    private lateinit var database: PaheDatabase
    private lateinit var config: AppConfig
    private lateinit var exporter: SafExporter

    private val renameTree: Uri
        get() = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_RENAME)

    private val noRenameTree: Uri
        get() = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_NORENAME)

    private val localFiles = mutableListOf<File>()

    private val testSession = DownloadSessionEntity(
        sessionId = "sess-saf-exporter",
        title = "SAF Exporter Session",
        animeSession = "anime-saf-exporter",
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
        runBlocking {
            database.downloadSessionDao().insertIfAbsent(testSession)
            OutputTreeRepository(context.contentResolver, config).clearOutputTree()
        }
        exporter = SafExporter(
            contentResolver = context.contentResolver,
            episodeDao = database.episodeDownloadDao(),
            config = config,
            sessionDao = database.downloadSessionDao(),
        )
    }

    @After
    fun teardown() {
        PBTestDocumentsProvider.failNextWrite = false
        SafTestSupport.clearTreeChildren(context, renameTree)
        SafTestSupport.clearTreeChildren(context, noRenameTree)
        runBlocking {
            try {
                OutputTreeRepository(context.contentResolver, config).clearOutputTree()
            } catch (_: Exception) {
            }
            config.updateOutputTreeRenameOk(true)
        }
        for (file in localFiles) file.delete()
        database.close()
    }

    // ------------------------------------------------------------------
    // Same-UID SAF contract (DECISIONS 92.8)
    // ------------------------------------------------------------------

    @Test
    fun sameUidSafOperations_workWithoutGrant() {
        val rootDocId = DocumentsContract.getTreeDocumentId(renameTree)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(renameTree, rootDocId)

        val resolved = context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { it.moveToFirst() } ?: false
        assertTrue("same-UID tree-document query must work without a grant", resolved)

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(renameTree, rootDocId)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            childrenUri,
            "application/octet-stream",
            "probe_roundtrip.pb.part",
        )
        assertNotNull("same-UID createDocument must work without a grant", created)
        val renamed = DocumentsContract.renameDocument(
            context.contentResolver,
            created!!,
            "probe_roundtrip.mp4",
        )
        assertNotNull("same-UID renameDocument must work without a grant", renamed)
        assertTrue(
            "same-UID deleteDocument must work without a grant",
            DocumentsContract.deleteDocument(context.contentResolver, created),
        )
    }

    // ------------------------------------------------------------------
    // Happy path: byte-verified export with X4-before-X6 (92.8/104.3)
    // ------------------------------------------------------------------

    @Test
    fun endToEndExport_persistsUriAndDeletesLocal() = runBlocking {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)

        val persisted = database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri
        assertNotNull("outputUri persisted (X4)", persisted)
        assertEquals("destination child byte-verified", bytes.size.toLong(), docSize(persisted!!))
        assertTrue("X6: local removed after verified export", !local.exists())
        assertTrue(
            "no .pb.part residue in root",
            children(renameTree).none { SafExportNames.isPart(it.name) },
        )

        // Gate A structure: `<root>/<sanitized anime title>/<episode file>`.
        val rootChildren = children(renameTree)
        assertEquals(
            "exactly the per-anime directory at the root, found ${rootChildren.map { it.name }}",
            1, rootChildren.size,
        )
        assertEquals(animeDirName, rootChildren.single().name)
        assertTrue("root child must be a directory", rootChildren.single().isDir)
        val animeChildren = childrenOf(renameTree, rootChildren.single().docId)
        assertEquals(
            "exactly the exported file inside the anime directory",
            1, animeChildren.size,
        )
        val finalChild = animeChildren.single()
        assertEquals("final file name unchanged", expectedNameFor(episode), finalChild.name)
        assertFalse("no part residue inside the anime directory",
            animeChildren.any { SafExportNames.isPart(it.name) })
        val persistedDocId = DocumentsContract.getDocumentId(Uri.parse(persisted))
        assertTrue(
            "outputUri must point at the nested file " +
                "($persistedDocId under ${rootChildren.single().docId}/)",
            persistedDocId.startsWith(rootChildren.single().docId + "/"),
        )
        assertTrue(
            "final child must carry the source bytes",
            readChildBytes(Uri.parse(persisted)).contentEquals(bytes),
        )
    }

    @Test
    fun existingRootUserFile_isNeverDeletedOrMoved() = runBlocking {
        val bytes = ByteArray(48_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)

        // A user's own file sitting at the tree root (not ours, not part-shaped).
        val userFile = createChild(renameTree, "My Own Video.mp4")
        writeChildBytes(userFile, ByteArray(1_234) { (it % 97).toByte() })
        val userSize = docSize(userFile.toString())

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)

        val rootChildren = children(renameTree)
        val userChild = rootChildren.singleOrNull { it.name == "My Own Video.mp4" }
        assertNotNull("the user's root file must remain at the root", userChild)
        assertEquals("user file byte count untouched", userSize, userChild!!.size)
        assertTrue(
            "user file bytes untouched",
            readChildBytes(childUri(renameTree, userChild.docId))
                .contentEquals(ByteArray(1_234) { (it % 97).toByte() }),
        )
        assertTrue(
            "export landed in the anime directory beside it",
            rootChildren.any { it.isDir && it.name == animeDirName },
        )
        assertFalse("X6 still runs", local.exists())
    }

    // ------------------------------------------------------------------
    // 104.6 collision rule over final children
    // ------------------------------------------------------------------

    @Test
    fun collisionExistingDifferentSize_createsUniquifiedCopy() = runBlocking {
        val bytes = ByteArray(64_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)
        val expectedName = expectedNameFor(episode)

        // A same-named user file with DIFFERENT bytes must never be touched —
        // planted where the exporter looks: inside the anime directory.
        val animeDir = ensureAnimeDir(renameTree)
        val preExisting = createChildIn(renameTree, animeDir, expectedName)
        writeChildBytes(preExisting, ByteArray(1_000) { (it % 127).toByte() })
        val preExistingSize = docSize(preExisting.toString())
        assertTrue("fixture sizes must differ", preExistingSize != bytes.size.toLong())

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)

        val secondName = uniquifiedSecond(expectedName)
        val secondChild = childrenOf(renameTree, animeDir).singleOrNull { it.name == secondName }
        assertNotNull("expected uniquified child $secondName", secondChild)
        assertTrue(
            "uniquified copy must carry the source bytes",
            readChildBytes(childUri(renameTree, secondChild!!.docId)).contentEquals(bytes),
        )
        assertEquals(
            "pre-existing user file untouched",
            preExistingSize,
            docSize(preExisting.toString()),
        )
        assertEquals(
            "outputUri must point at the uniquified child",
            childUri(renameTree, secondChild.docId).toString(),
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri,
        )
        assertFalse("X6: local removed", local.exists())
        assertTrue(
            "no .pb.part residue anywhere",
            children(renameTree).none { SafExportNames.isPart(it.name) } &&
                childrenOf(renameTree, animeDir).none { SafExportNames.isPart(it.name) },
        )
    }

    @Test
    fun collisionExistingSameSize_reusesWithoutCopy() = runBlocking {
        val bytes = ByteArray(48_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)
        val expectedName = expectedNameFor(episode)

        // Same name AND same byte count → 104.6 reuse-by-size (never overwrite),
        // inside the anime directory where the exporter looks first.
        val animeDir = ensureAnimeDir(renameTree)
        val preExisting = createChildIn(renameTree, animeDir, expectedName)
        writeChildBytes(preExisting, bytes)
        assertEquals(bytes.size.toLong(), docSize(preExisting.toString()))

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected AlreadyExported, got $outcome", outcome is ExportOutcome.AlreadyExported)

        assertEquals(
            "outputUri must be the existing child",
            childUri(renameTree, DocumentsContract.getDocumentId(preExisting)).toString(),
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri,
        )
        assertFalse("X6 still runs on reuse", local.exists())
        val names = childrenOf(renameTree, animeDir).map { it.name }
        assertEquals(
            "no second copy and no part may exist: $names",
            1, names.size,
        )
        assertEquals(
            "root holds exactly the anime directory: ${children(renameTree).map { it.name }}",
            1, children(renameTree).size,
        )
    }

    // ------------------------------------------------------------------
    // 104.5 crash-recovery rows over `.pb.part` temporaries
    // ------------------------------------------------------------------

    @Test
    fun completeStaleRootPart_isFinalizedInPlaceNotRecopied() = runBlocking {
        val bytes = ByteArray(72_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)
        val expectedName = expectedNameFor(episode)

        // Complete part from a PRE-Gate-A crashed export at the tree ROOT:
        // SAME byte count as the source but XOR-different content — if the
        // exporter re-copied instead of finalizing, the final bytes would match
        // the source, not the part. Finalized IN PLACE (never migrated).
        val partContent = ByteArray(bytes.size) { ((it % 251) xor 0x5A).toByte() }
        assertFalse("detection vehicle must differ from the source", partContent.contentEquals(bytes))
        val partDoc = createChild(renameTree, expectedName + SafExportNames.PART_SUFFIX)
        writeChildBytes(partDoc, partContent)

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Exported from finalize, got $outcome", outcome is ExportOutcome.Exported)

        val finalChild = children(renameTree).singleOrNull { it.name == expectedName }
        assertNotNull("finalized child must exist at its legacy location", finalChild)
        assertEquals(bytes.size.toLong(), finalChild!!.size)
        assertTrue(
            "the part's bytes must survive (finalized, NOT re-copied)",
            readChildBytes(childUri(renameTree, finalChild.docId)).contentEquals(partContent),
        )
        assertTrue("no part residue", children(renameTree).none { SafExportNames.isPart(it.name) })
        assertEquals(
            "the unused anime directory was cleaned up after root finalize: " +
                "${children(renameTree).map { it.name }}",
            1, children(renameTree).size,
        )
        assertFalse("X6 after finalize", local.exists())
        assertEquals(
            "outputUri persisted by finalize",
            childUri(renameTree, finalChild.docId).toString(),
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri,
        )
    }

    @Test
    fun completeStalePartInAnimeDir_isFinalizedInPlace() = runBlocking {
        val bytes = ByteArray(72_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)
        val expectedName = expectedNameFor(episode)

        // Complete part from a crashed export INSIDE the anime directory
        // (post-Gate-A generation): finalized there, not re-copied.
        val animeDir = ensureAnimeDir(renameTree)
        val partContent = ByteArray(bytes.size) { ((it % 251) xor 0x33).toByte() }
        val partDoc = createChildIn(renameTree, animeDir, expectedName + SafExportNames.PART_SUFFIX)
        writeChildBytes(partDoc, partContent)

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Exported from finalize, got $outcome", outcome is ExportOutcome.Exported)

        val finalChild = childrenOf(renameTree, animeDir)
            .singleOrNull { it.name == expectedName }
        assertNotNull("finalized child must exist inside the anime directory", finalChild)
        assertEquals(bytes.size.toLong(), finalChild!!.size)
        assertTrue(
            "the part's bytes must survive (finalized, NOT re-copied)",
            readChildBytes(childUri(renameTree, finalChild.docId)).contentEquals(partContent),
        )
        assertFalse("X6 after finalize", local.exists())
        assertEquals(
            "outputUri points at the in-directory finalize",
            childUri(renameTree, finalChild.docId).toString(),
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri,
        )
    }

    @Test
    fun partialStalePart_deletedAndFreshCopyExports() = runBlocking {
        val bytes = ByteArray(72_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)
        val expectedName = expectedNameFor(episode)

        // Partial part at the root: byte count differs from the source →
        // discarded (104.5 row 1); the fresh copy lands in the anime directory.
        val partDoc = createChild(renameTree, expectedName + SafExportNames.PART_SUFFIX)
        writeChildBytes(partDoc, bytes.copyOf(bytes.size / 3))

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)

        val animeDirId = animeDirIdOrNull(renameTree)
        assertNotNull("anime directory must exist", animeDirId)
        val finalChild = childrenOf(renameTree, animeDirId!!)
            .singleOrNull { it.name == expectedName }
        assertNotNull("fresh copy must exist inside the anime directory", finalChild)
        assertEquals(bytes.size.toLong(), finalChild!!.size)
        assertTrue("partial part replaced, no residue", children(renameTree)
            .none { SafExportNames.isPart(it.name) })
        assertFalse("X6 after fresh copy", local.exists())
    }

    @Test
    fun foreignStalePart_deletedAndExportProceeds() = runBlocking {
        val bytes = ByteArray(40_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)
        val expectedName = expectedNameFor(episode)

        // Another episode's temporary — app-owned, safe to discard (104.2/104.5).
        createChild(renameTree, "Some Other Episode 99.mp4" + SafExportNames.PART_SUFFIX)

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Exported, got $outcome", outcome is ExportOutcome.Exported)

        assertTrue("foreign part deleted", children(renameTree)
            .none { SafExportNames.isPart(it.name) })
        val animeDirId = animeDirIdOrNull(renameTree)
        assertNotNull("anime directory must exist", animeDirId)
        assertNotNull("final export exists in the anime directory",
            childrenOf(renameTree, animeDirId!!).singleOrNull { it.name == expectedName })
        assertFalse("X6 after export", local.exists())
    }

    // ------------------------------------------------------------------
    // 104.4 rename-less provider → RenameUnsupported, no residue (104.3)
    // ------------------------------------------------------------------

    @Test
    fun renameUnsupported_cleansPartPersistsFlagAndKeepsLocal() = runBlocking {
        val bytes = ByteArray(32_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)

        val result = exporter.export(episode, noRenameTree.toString())

        assertTrue(
            "rename-less tree must signal RenameUnsupported, got $result",
            result is SafExportResult.RenameUnsupported,
        )
        assertTrue(
            "local source kept — MediaStore fallback will run X1–X6",
            local.exists(),
        )
        assertNull(
            "X4 never ran (no destination persisted)",
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri,
        )
        assertTrue(
            "output_tree_rename_ok persisted false (104.4)",
            !config.outputTreeRenameOk.first(),
        )
        assertTrue(
            "SAF path left no residue (part deleted)",
            children(noRenameTree).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 104.3 failure path: own part deleted, local kept (92.5/X6 rule)
    // ------------------------------------------------------------------

    @Test
    fun injectedWriteFailure_keepsLocalAndCleansPart() = runBlocking {
        val bytes = ByteArray(24_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes)

        PBTestDocumentsProvider.failNextWrite = true
        val result = exporter.export(episode, renameTree.toString())
        PBTestDocumentsProvider.failNextWrite = false

        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Failed, got $outcome", outcome is ExportOutcome.Failed)
        assertTrue(
            "local source MUST survive a failed export (X6 invariant)",
            local.exists(),
        )
        assertNull(
            "no destination persisted on failure",
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri,
        )
        assertTrue(
            "own part cleaned up on failure",
            children(renameTree).none { SafExportNames.isPart(it.name) },
        )
        assertEquals(
            "the anime directory created by this failed call was cleaned up " +
                "(empty-dir rule): ${children(renameTree).map { it.name }}",
            0, children(renameTree).size,
        )
    }

    // ------------------------------------------------------------------
    // Slice A — per-audio export identity
    // ------------------------------------------------------------------

    @Test
    fun crossAudioStalePart_isNeverFinalizedAsThisVariantsBytes() = runBlocking {
        val bytes = ByteArray(72_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes, audio = "eng")
        val expectedName = expectedNameFor(episode)
        val legacyName = legacyNameFor(episode)

        // A complete `.pb.part` left by another audio variant's crashed export,
        // carrying the PRE-Slice-A name this exporter used to look for. Same
        // byte count as this episode's source, XOR-different content: before
        // Slice A the stale-part pass treated it as ours and finalized it,
        // handing this variant the OTHER variant's bytes silently.
        val partContent = ByteArray(bytes.size) { ((it % 251) xor 0x5A).toByte() }
        assertFalse("detection vehicle must differ from the source", partContent.contentEquals(bytes))
        val partDoc = createChild(renameTree, legacyName + SafExportNames.PART_SUFFIX)
        writeChildBytes(partDoc, partContent)

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected Exported from a fresh copy, got $outcome", outcome is ExportOutcome.Exported)

        val animeDirId = animeDirIdOrNull(renameTree)
        assertNotNull("anime directory must exist", animeDirId)
        val finalChild = childrenOf(renameTree, animeDirId!!).singleOrNull { it.name == expectedName }
        assertNotNull("the audio-specific final must exist", finalChild)
        assertTrue(
            "the final must carry THIS variant's bytes, never the stale part's",
            readChildBytes(childUri(renameTree, finalChild!!.docId)).contentEquals(bytes),
        )
        assertFalse(
            "no legacy-named final may appear anywhere",
            children(renameTree).any { it.name == legacyName } ||
                childrenOf(renameTree, animeDirId).any { it.name == legacyName },
        )
        assertTrue(
            "the foreign-audio part is discarded (app-owned temporary), no residue",
            children(renameTree).none { SafExportNames.isPart(it.name) },
        )
        assertFalse("X6 still runs", local.exists())
        assertEquals(
            "outputUri points at the audio-specific final",
            childUri(renameTree, finalChild.docId).toString(),
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri,
        )
    }

    @Test
    fun dualAudio_variantsCoexistInOneAnimeDirectory() = runBlocking {
        val title = "SAF Dual ${System.nanoTime()}"
        val jpnBytes = ByteArray(50_000) { (it % 251).toByte() }
        val engBytes = ByteArray(60_000) { (it % 197).toByte() }
        val (jpn, jpnLocal) = fixture(jpnBytes, audio = "jpn", title = title)
        val (eng, engLocal) = fixture(engBytes, audio = "eng", title = title)

        val jpnResult = exporter.export(jpn, renameTree.toString())
        assertTrue(
            "jpn export, got $jpnResult",
            (jpnResult as SafExportResult.Completed).outcome is ExportOutcome.Exported,
        )
        val engResult = exporter.export(eng, renameTree.toString())
        assertTrue(
            "eng export, got $engResult",
            (engResult as SafExportResult.Completed).outcome is ExportOutcome.Exported,
        )

        val animeDirId = animeDirIdOrNull(renameTree)!!
        val animeChildren = childrenOf(renameTree, animeDirId)
        assertEquals(
            "exactly one file per audio variant: ${animeChildren.map { it.name }}",
            setOf(expectedNameFor(jpn), expectedNameFor(eng)),
            animeChildren.map { it.name }.toSet(),
        )
        val jpnChild = animeChildren.single { it.name == expectedNameFor(jpn) }
        val engChild = animeChildren.single { it.name == expectedNameFor(eng) }
        assertTrue(readChildBytes(childUri(renameTree, jpnChild.docId)).contentEquals(jpnBytes))
        assertTrue(readChildBytes(childUri(renameTree, engChild.docId)).contentEquals(engBytes))
        assertNotEquals(
            "distinct destinations",
            database.episodeDownloadDao().getById(jpn.episodeId)!!.outputUri,
            database.episodeDownloadDao().getById(eng.episodeId)!!.outputUri,
        )
        assertTrue(animeChildren.none { SafExportNames.isPart(it.name) })
        assertFalse("X6 for both variants", jpnLocal.exists() || engLocal.exists())
    }

    @Test
    fun legacyRootFile_sizeVerifiedAndUnreferenced_isAdopted() = runBlocking {
        val bytes = ByteArray(40_000) { (it % 251).toByte() }
        val (episode, local) = fixture(bytes, audio = "eng")

        // A pre-Slice-A export at the tree root, unreferenced in Room: same
        // byte count → adopted in place instead of writing a second copy.
        val legacyDoc = createChild(renameTree, legacyNameFor(episode))
        writeChildBytes(legacyDoc, bytes)

        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected AlreadyExported, got $outcome", outcome is ExportOutcome.AlreadyExported)

        assertEquals(
            "the size-verified legacy file is adopted",
            childUri(renameTree, DocumentsContract.getDocumentId(legacyDoc)).toString(),
            database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri,
        )
        assertEquals(
            "no second copy anywhere: ${children(renameTree).map { it.name }}",
            setOf(legacyNameFor(episode)),
            children(renameTree).filter { !it.isDir }.map { it.name }.toSet(),
        )
        assertFalse("X6 still runs on adoption", local.exists())
    }

    @Test
    fun legacyRootFile_referencedByAnotherAudioVariant_isNotAdopted() = runBlocking {
        val bytes = ByteArray(40_000) { (it % 251).toByte() }
        val title = "SAF Alias ${System.nanoTime()}"

        // A pre-Slice-A export at the tree root that ANOTHER audio variant owns.
        val (owner, _) = fixture(bytes, audio = "jpn", title = title)
        val legacyDoc = createChild(renameTree, legacyNameFor(owner))
        writeChildBytes(legacyDoc, bytes)
        val legacyUri = childUri(renameTree, DocumentsContract.getDocumentId(legacyDoc)).toString()
        database.episodeDownloadDao().updateOutputUri(owner.episodeId, legacyUri)

        val (episode, local) = fixture(bytes, audio = "eng", title = title)
        val result = exporter.export(episode, renameTree.toString())
        val outcome = (result as SafExportResult.Completed).outcome
        assertTrue("expected a fresh Exported, got $outcome", outcome is ExportOutcome.Exported)

        val persisted = database.episodeDownloadDao().getById(episode.episodeId)!!.outputUri
        assertNotEquals(
            "a file another row references must never be adopted",
            legacyUri, persisted,
        )
        assertEquals(
            "the other variant's reference is untouched",
            legacyUri,
            database.episodeDownloadDao().getById(owner.episodeId)!!.outputUri,
        )
        assertTrue(
            "the legacy file is never deleted",
            children(renameTree).any { it.name == legacyNameFor(owner) },
        )
        assertFalse("X6 still runs", local.exists())
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private data class ChildInfo(val name: String, val docId: String, val size: Long, val isDir: Boolean)

    /**
     * Gate A destination directory name for [testSession] — the exact string
     * the production exporter derives from the session title.
     */
    private val animeDirName: String
        get() = OutputPathNames.directoryName(testSession.title, testSession.sessionId)

    private fun fixture(
        bytes: ByteArray,
        audio: String = "sub",
        title: String = "SAF Export ${System.nanoTime()}",
    ): Pair<EpisodeDownloadEntity, File> {
        val local = File(context.filesDir, "pbx_saf_${System.nanoTime()}.mp4")
        local.writeBytes(bytes)
        localFiles += local
        val episode = EpisodeDownloadEntity(
            episodeId = "pbx-saf-${System.nanoTime()}",
            sessionId = testSession.sessionId,
            episodeNumber = 1.0f,
            episodeSession = "es-saf-exporter",
            title = title,
            fansub = "TestFansub",
            audio = audio,
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

    /** The Slice A destination name — the audio discriminator comes from the row. */
    private fun expectedNameFor(episode: EpisodeDownloadEntity): String =
        ExportFileNames.exportedName(
            FileNameGenerator.formatEpisodePrefix(episode.episodeNumber),
            episode.title,
            episode.audio,
        )

    /** Pre-Slice-A unsuffixed name — the legacy alias every old export used. */
    private fun legacyNameFor(episode: EpisodeDownloadEntity): String =
        ExportFileNames.legacyName(
            FileNameGenerator.formatEpisodePrefix(episode.episodeNumber),
            episode.title,
        )

    /** Direct ` (2)` construction — independent of production uniquify. */
    private fun uniquifiedSecond(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name (2)" else
            "${name.substring(0, dot)} (2)${name.substring(dot)}"
    }

    /** Every child of [tree]'s ROOT document (dirs included). */
    private fun children(tree: Uri): List<ChildInfo> =
        childrenOf(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Every child of the tree document [docId] (root or nested). */
    private fun childrenOf(tree: Uri, docId: String): List<ChildInfo> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        return context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            val nameIdx = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ChildInfo(
                            name = cursor.getString(nameIdx),
                            docId = cursor.getString(idIdx),
                            size = if (cursor.isNull(sizeIdx)) -1L else cursor.getLong(sizeIdx),
                            isDir = !cursor.isNull(mimeIdx) &&
                                cursor.getString(mimeIdx) ==
                                DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    /** The existing anime directory's document id, or null when absent. */
    private fun animeDirIdOrNull(tree: Uri): String? =
        children(tree).firstOrNull { it.isDir && it.name == animeDirName }?.docId

    /**
     * Fixture-side find-or-create of the anime directory (mirrors the
     * production first-export creation so fixtures can plant files inside it).
     */
    private fun ensureAnimeDir(tree: Uri): String {
        animeDirIdOrNull(tree)?.let { return it }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree),
            ),
            DocumentsContract.Document.MIME_TYPE_DIR,
            animeDirName,
        )
        assertNotNull("anime directory fixture", created)
        return DocumentsContract.getDocumentId(created!!)
    }

    private fun childUri(tree: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun docSize(uriString: String): Long {
        val uri = Uri.parse(uriString)
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null,
        )!!.use { cursor ->
            assertTrue("document must resolve", cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    /** Create a file child [name] under ROOT (legacy/pre-Gate-A residue shape). */
    private fun createChild(tree: Uri, name: String): Uri =
        createChildIn(tree, DocumentsContract.getTreeDocumentId(tree), name)

    /** Create a file child [name] under the tree document [parentDocId]. */
    private fun createChildIn(tree: Uri, parentDocId: String, name: String): Uri {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            childrenUri,
            "application/octet-stream",
            name,
        )
        assertNotNull("createChild($name)", created)
        return created!!
    }

    private fun writeChildBytes(docUri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(docUri, "w")!!.use { it.write(bytes) }
    }

    private fun readChildBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
}
