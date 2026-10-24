package com.pahebatcher.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.export.ExportDestinationRouter
import com.pahebatcher.app.data.export.ExportOutcome
import com.pahebatcher.app.data.export.MediaStoreExporter
import com.pahebatcher.app.data.export.OutputTreeRepository
import com.pahebatcher.app.data.export.OutputTreeState
import com.pahebatcher.app.data.export.SafExporter
import com.pahebatcher.app.data.export.SafTestSupport
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
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
 * [SessionActions] against REAL Room
 * (in-memory, FK cascade on) and REAL files:
 *
 * - Cancel/Retry aggregate over exactly the row sets the 94 table names;
 * - the 106.1 terminal-only gate refuses delete preparation AND deletion;
 * - export-first: successful exports leave nothing to name (the exporters
 *   perform X6 inside `export()`), failed exports are named explicitly for
 *   the confirmation, and unexported media is never deleted without the
 *   explicit flag (SPEC §20);
 * - the 106.3 filesystem-first order with a repeatable/crash-safe cascade;
 * - 94/106.2 temporary-cache gates (per session, and globally for clear-all)
 *   with `*.mp4` preserved by the output-preserving cleanup;
 * - 107 temporary-only cache sizing — the number shown equals the bytes the
 *   clear actions free.
 *
 * The cancel/retry targets and the exporter are test-only doubles (AGENTS §5).
 */
@RunWith(AndroidJUnit4::class)
class SessionActionsTest {

    private lateinit var context: Context
    private lateinit var database: PaheDatabase
    private lateinit var sessionDao: DownloadSessionDao
    private lateinit var episodeDao: EpisodeDownloadDao
    private lateinit var root: File

    private val cancelled = mutableListOf<String>()
    private val retried = mutableListOf<String>()
    private val paused = mutableListOf<String>()
    private val resumed = mutableListOf<String>()
    private val exporter = FakeExporter()

    private lateinit var actions: SessionActions

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private class FakeExporter : EpisodeExporter {
        val attempts = mutableListOf<String>()
        var succeed = true

        override suspend fun export(episode: EpisodeDownloadEntity): ExportOutcome {
            attempts += episode.episodeId
            if (!succeed) return ExportOutcome.Failed("scripted failure")
            // Production X6 lives inside export() (SafExporter/MediaStoreExporter):
            // the local source goes away only after byte-verified persistence.
            episode.outputPath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            return ExportOutcome.Exported("content://fake/${episode.episodeId}")
        }

        override suspend fun sweepStalePending(olderThanEpochMs: Long): Int = 0
    }

    private fun session(id: String) = DownloadSessionEntity(
        sessionId = id,
        title = "Session $id",
        animeSession = "anime-$id",
        host = "animepahe.com",
        quality = 1080,
        audioLang = "sub",
        outputDir = "/downloads",
        cacheDir = "/cache",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        state = DownloadSessionEntity.STATE_CREATED,
    )

    private fun episode(
        id: String,
        sessionId: String,
        state: String,
        segmentDir: String? = null,
        outputPath: String? = null,
    ) = EpisodeDownloadEntity(
        episodeId = id,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "ep-sess-$id",
        title = "Episode $id",
        fansub = "Fansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = state,
        segmentDir = segmentDir,
        outputPath = outputPath,
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private fun dir(name: String): File =
        File(root, name).apply { mkdirs() }

    private fun file(parent: File, name: String, bytes: Int): File =
        File(parent, name).also { it.writeBytes(ByteArray(bytes) { 1 }) }

    /** Seed the temp artifacts (a .ts segment, a .tmp fragment, a concat list) and return their byte total. */
    private fun seedTemps(d: File, ts: Int = 100, tmp: Int = 50, concat: Int = 10): Long {
        file(d, "seg0.ts", ts)
        file(d, "seg1.tmp", tmp)
        file(d, "concat.txt", concat)
        return (ts + tmp + concat).toLong()
    }

    private fun insertSessionWith(vararg episodes: EpisodeDownloadEntity) = runBlocking {
        // One parent row per DISTINCT session id — episode_downloads.sessionId
        // is a foreign key, so every session referenced must exist first.
        episodes.map { it.sessionId }.distinct().forEach { sessionDao.insertIfAbsent(session(it)) }
        episodes.forEach { episodeDao.upsert(it) }
    }

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = database.downloadSessionDao()
        episodeDao = database.episodeDownloadDao()
        root = File(context.cacheDir, "sacts-${System.nanoTime()}").apply { mkdirs() }
        actions = SessionActions(
            sessionRepository = SessionRepository(sessionDao, episodeDao),
            episodeDao = episodeDao,
            cancelRow = { cancelled += it },
            retryRow = { retried += it },
            // Doubles record the call and report success — the real CAS/WorkManager
            // behaviour lives in DownloadRepository and is tested there.
            pauseRow = { paused += it; true },
            resumeRow = { resumed += it; true },
            exporter = exporter,
        )
    }

    @After
    fun teardown() {
        root.deleteRecursively()
        database.close()
    }

    // -----------------------------------------------------------------------
    // Cancel / Retry (DECISIONS 94)
    // -----------------------------------------------------------------------

    @Test
    fun cancelSession_cancels_only_nonTerminal_rows() {
        insertSessionWith(
            episode("a1", "sA", EpisodeDownloadEntity.STATE_COMPLETED),
            episode("a2", "sA", EpisodeDownloadEntity.STATE_QUEUED),
            episode("a3", "sA", EpisodeDownloadEntity.STATE_DOWNLOADING),
            episode("a4", "sA", EpisodeDownloadEntity.STATE_CANCELLED),
        )
        runBlocking {
            assertEquals(2, actions.cancelSession("sA"))
        }
        assertEquals(listOf("a2", "a3"), cancelled.sorted())
        assertEquals("terminal rows are untouched", emptyList<String>(), cancelled.filter { it == "a1" })
    }

    @Test
    fun retrySession_targets_only_failed_and_cancelled_rows() {
        insertSessionWith(
            episode("b1", "sB", EpisodeDownloadEntity.STATE_COMPLETED),
            episode("b2", "sB", EpisodeDownloadEntity.STATE_FAILED),
            episode("b3", "sB", EpisodeDownloadEntity.STATE_CANCELLED),
            episode("b4", "sB", EpisodeDownloadEntity.STATE_QUEUED),
        )
        runBlocking {
            assertEquals(2, actions.retrySession("sB"))
        }
        assertEquals(listOf("b2", "b3"), retried.sorted())
    }

    // -----------------------------------------------------------------------
    // Pause / Resume session (SPEC §20 final gap closure)
    // -----------------------------------------------------------------------

    @Test
    fun pauseSession_targets_only_pausable_rows() {
        insertSessionWith(
            episode("p1", "sP", EpisodeDownloadEntity.STATE_COMPLETED),
            episode("p2", "sP", EpisodeDownloadEntity.STATE_QUEUED),
            episode("p3", "sP", EpisodeDownloadEntity.STATE_DOWNLOADING),
            // ASSEMBLING: muxing cannot stop halfway without risking a partial output.
            episode("p4", "sP", EpisodeDownloadEntity.STATE_ASSEMBLING),
            // Already paused: pausing twice is not a state change.
            episode("p5", "sP", EpisodeDownloadEntity.STATE_PAUSED),
        )
        runBlocking {
            assertEquals(2, actions.pauseSession("sP"))
        }
        assertEquals(listOf("p2", "p3"), paused.sorted())
        assertTrue(
            "terminal, assembling and already-paused rows are never offered",
            paused.none { it in setOf("p1", "p4", "p5") },
        )
    }

    @Test
    fun resumeSession_targets_only_paused_rows() {
        insertSessionWith(
            episode("r1", "sR", EpisodeDownloadEntity.STATE_COMPLETED),
            episode("r2", "sR", EpisodeDownloadEntity.STATE_PAUSED),
            episode("r3", "sR", EpisodeDownloadEntity.STATE_PAUSED),
            episode("r4", "sR", EpisodeDownloadEntity.STATE_QUEUED),
        )
        runBlocking {
            assertEquals(2, actions.resumeSession("sR"))
        }
        assertEquals(listOf("r2", "r3"), resumed.sorted())
        assertTrue("a queued row is already queued — never \"resumed\"", resumed.none { it == "r4" })
    }

    @Test
    fun pauseAndResume_reports_what_happened_not_what_was_requested() {
        // The per-row seam can refuse (the row finished underneath the caller).
        // The aggregate count must report the truth — a session-level message
        // that says "Paused 2 download(s)" when nothing paused would be a lie.
        insertSessionWith(
            episode("x1", "sX", EpisodeDownloadEntity.STATE_QUEUED),
            episode("x2", "sX", EpisodeDownloadEntity.STATE_QUEUED),
            episode("x3", "sX", EpisodeDownloadEntity.STATE_PAUSED),
        )
        val refusing = SessionActions(
            sessionRepository = SessionRepository(sessionDao, episodeDao),
            episodeDao = episodeDao,
            cancelRow = { },
            retryRow = { },
            pauseRow = { false },
            resumeRow = { false },
            exporter = exporter,
        )
        runBlocking {
            assertEquals(0, refusing.pauseSession("sX"))
            assertEquals(0, refusing.resumeSession("sX"))
        }
        assertEquals("nothing was claimed, so nothing may be reported", emptyList<String>(), paused)
        assertEquals(emptyList<String>(), resumed)
    }

    // -----------------------------------------------------------------------
    // Delete session (DECISIONS 106.1 / 106.3, SPEC §20)
    // -----------------------------------------------------------------------

    @Test
    fun prepareSessionDelete_refuses_while_any_row_is_non_terminal() {
        insertSessionWith(
            episode("c1", "sC", EpisodeDownloadEntity.STATE_COMPLETED),
            episode("c2", "sC", EpisodeDownloadEntity.STATE_DOWNLOADING),
        )
        runBlocking {
            val result = actions.prepareSessionDelete("sC")
            assertTrue(result is SessionDeletePreparation.Refused)
            val reason = (result as SessionDeletePreparation.Refused).reason
            assertTrue("the refusal names the blocking count: $reason", reason.contains("1"))
            assertTrue(actions.prepareSessionDelete("sC") is SessionDeletePreparation.Refused)
        }
    }

    @Test
    fun prepareSessionDelete_export_first_success_leaves_nothing_to_name() {
        val d = dir("C_ep1")
        val local = file(d, "Anime-C-001.mp4", 999)
        val tempBytes = seedTemps(d)
        insertSessionWith(
            episode("c1", "sC", EpisodeDownloadEntity.STATE_COMPLETED, d.absolutePath, local.absolutePath),
        )
        runBlocking {
            val result = actions.prepareSessionDelete("sC")
            assertTrue(result is SessionDeletePreparation.Ready)
            val plan = (result as SessionDeletePreparation.Ready).plan
            assertEquals(1, plan.exportedCount)
            assertEquals(
                "the successful export already removed the local source (X6)",
                emptyList<String>(),
                plan.unexportedNames,
            )
            assertEquals(tempBytes, plan.temporaryBytes)
            assertFalse(local.exists())
        }
        assertEquals(listOf("c1"), exporter.attempts)
    }

    @Test
    fun prepareSessionDelete_names_the_local_mp4_that_survived_a_failed_export() {
        val d = dir("D_ep1")
        val local = file(d, "Anime-D-001.mp4", 999)
        seedTemps(d)
        exporter.succeed = false
        insertSessionWith(
            episode("d1", "sD", EpisodeDownloadEntity.STATE_COMPLETED, d.absolutePath, local.absolutePath),
        )
        runBlocking {
            val result = actions.prepareSessionDelete("sD") as SessionDeletePreparation.Ready
            assertEquals(0, result.plan.exportedCount)
            assertEquals(listOf("Anime-D-001.mp4"), result.plan.unexportedNames)
            assertTrue("the local media survives the failed export", local.exists())
        }
        exporter.succeed = true
    }

    @Test
    fun deleteSession_refuses_while_a_row_is_non_terminal() {
        val d = dir("E_ep1")
        seedTemps(d)
        insertSessionWith(episode("e1", "sE", EpisodeDownloadEntity.STATE_QUEUED, d.absolutePath))
        runBlocking {
            assertFalse(actions.deleteSession("sE", deleteUnexportedLocalMedia = true))
            assertTrue(actions.isSessionAllTerminal("sE").not())
            assertTrue("segments untouched", File(d, "seg0.ts").exists())
            assertTrue("rows untouched", episodeDao.getById("e1") != null)
        }
    }

    @Test
    fun deleteSession_refuses_unexported_media_without_the_explicit_confirmation() {
        val d = dir("F_ep1")
        val local = file(d, "Anime-F-001.mp4", 500)
        seedTemps(d)
        exporter.succeed = false // export-first runs, but the copy fails
        insertSessionWith(
            episode("f1", "sF", EpisodeDownloadEntity.STATE_COMPLETED, d.absolutePath, local.absolutePath),
        )
        runBlocking {
            val ready = actions.prepareSessionDelete("sF") as SessionDeletePreparation.Ready
            assertEquals(listOf("Anime-F-001.mp4"), ready.plan.unexportedNames)

            assertFalse(
                "no confirmation → the named media must survive",
                actions.deleteSession("sF", deleteUnexportedLocalMedia = false),
            )
            assertTrue(local.exists())
            assertTrue(File(d, "seg0.ts").exists())
            assertEquals(1, sessionDao.count())

            // With the confirmation the delete completes: filesystem first, then cascade.
            assertTrue(actions.deleteSession("sF", deleteUnexportedLocalMedia = true))
            assertFalse(local.exists())
            assertFalse(d.exists())
            assertNull(sessionDao.getById("sF"))
            assertTrue(episodeDao.getBySessionId("sF").first().isEmpty())
        }
        exporter.succeed = true
    }

    @Test
    fun deleteSession_is_filesystem_first_then_cascade_and_repeats_safely() {
        val d = dir("G_ep1")
        val tempBytes = seedTemps(d)
        insertSessionWith(
            episode("g1", "sG", EpisodeDownloadEntity.STATE_CANCELLED, d.absolutePath),
            episode("g2", "sG", EpisodeDownloadEntity.STATE_FAILED, d.absolutePath),
        )
        assertTrue(tempBytes > 0)

        runBlocking {
            assertTrue(actions.deleteSession("sG", deleteUnexportedLocalMedia = false))

            // Cascade: session row AND its episode rows are gone.
            assertNull(sessionDao.getById("sG"))
            assertTrue(episodeDao.getBySessionId("sG").first().isEmpty())
            assertFalse("segment directory removed", d.exists())

            // Idempotent repeat (the crash-mid-delete recovery, 106.3): a second
            // confirmed delete is a safe no-op over an already-empty state.
            assertTrue(actions.deleteSession("sG", deleteUnexportedLocalMedia = false))
        }
    }

    @Test
    fun deleteSession_repeats_after_a_crash_between_filesystem_and_row_deletion() {
        val d = dir("H_ep1")
        seedTemps(d)
        insertSessionWith(episode("h1", "sH", EpisodeDownloadEntity.STATE_FAILED, d.absolutePath))

        // Simulate the crash: filesystem step finished, Room row still present.
        d.deleteRecursively()

        runBlocking {
            assertTrue(
                "the repeat completes the cascade over missing directories",
                actions.deleteSession("sH", deleteUnexportedLocalMedia = false),
            )
            assertNull(sessionDao.getById("sH"))
            assertTrue(episodeDao.getBySessionId("sH").first().isEmpty())
        }
    }

    @Test
    fun deleteSession_removes_a_confirmed_local_mp4_that_lives_outside_its_segment_dir() {
        val d = dir("I_ep1")
        seedTemps(d)
        val outside = file(root, "I-output.mp4", 400)
        exporter.succeed = false // the export-first attempt fails → file must be named
        insertSessionWith(
            episode("i1", "sI", EpisodeDownloadEntity.STATE_COMPLETED, d.absolutePath, outside.absolutePath),
        )
        runBlocking {
            val ready = actions.prepareSessionDelete("sI") as SessionDeletePreparation.Ready
            assertEquals(listOf("I-output.mp4"), ready.plan.unexportedNames)

            assertTrue(actions.deleteSession("sI", deleteUnexportedLocalMedia = true))
            assertFalse("the confirmed file is gone", outside.exists())
            assertFalse(d.exists())
        }
        exporter.succeed = true
    }

    // -----------------------------------------------------------------------
    // DECISIONS 92.8 — session-delete never touches exported SAF children
    // -----------------------------------------------------------------------

    @Test
    fun deleteSession_neverTouchesARealExportedSafChild() = runBlocking {
        // The REAL export path (production router → production SafExporter →
        // real provider child, byte-verified X4 → local X6), then the
        // confirmed session delete. The exported child is user media living
        // outside the app's files: SessionActions only ever deletes File
        // paths, so the child must survive byte-for-byte. (The FakeExporter
        // double above cannot prove this — it fabricates its content:// URI.)
        val config = AppConfig(context)
        val outputTrees = OutputTreeRepository(context.contentResolver, config)
        val router = ExportDestinationRouter(
            outputTrees = outputTrees,
            config = config,
            safExporter = SafExporter(
                context.contentResolver, episodeDao, config, sessionDao,
            ),
            mediaStoreExporter = MediaStoreExporter(
                context.contentResolver, episodeDao, sessionDao,
            ),
        )
        val realActions = SessionActions(
            sessionRepository = SessionRepository(sessionDao, episodeDao),
            episodeDao = episodeDao,
            cancelRow = { cancelled += it },
            retryRow = { retried += it },
            pauseRow = { paused += it; true },
            resumeRow = { resumed += it; true },
            exporter = router,
        )
        val tree = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_RENAME)
        try {
            // No leakage from other suites + the root dir must exist for the
            // tree-document validation the router performs.
            val treeRoot =
                File(context.getExternalFilesDir("pb_testdocs"), PBTestDocumentsProvider.ROOT_RENAME)
            if (!treeRoot.exists()) treeRoot.mkdirs()
            SafTestSupport.clearTreeChildren(context, tree)

            val d = dir("SAF_ep1")
            val local = file(d, "Anime-SAF-001.mp4", 64_000)
            insertSessionWith(
                episode(
                    "saf1",
                    "sSAF",
                    EpisodeDownloadEntity.STATE_COMPLETED,
                    d.absolutePath,
                    local.absolutePath,
                ),
            )
            // Full production pick: a REAL persistable grant + the repository's
            // setOutputTree — the same sequence SettingsOutputLocationTest
            // verifies. Without the grant, validate() reports Unusable and the
            // router correctly falls back to MediaStore (92.3), which is NOT
            // the SAF-routing this invariant is about.
            SafTestSupport.establishPersistableGrant(context, tree)
            val picked = outputTrees.setOutputTree(tree.toString())
            assertTrue(
                "precondition: the pick validates as a usable tree (state=$picked)",
                picked is OutputTreeState.Valid,
            )

            // 1. Seed the REAL exported child through the PRODUCTION router —
            //    exactly what the export-first step of deleteSession runs —
            //    with a self-diagnosing outcome so a routing/copy problem can
            //    never masquerade as a delete problem.
            val outcome = router.export(episodeDao.getById("saf1")!!)
            assertTrue(
                "the real SAF export must succeed — outcome=$outcome",
                outcome is ExportOutcome.Exported,
            )
            assertNotNull(
                "X4 persisted the child URI on the row",
                episodeDao.getById("saf1")!!.outputUri,
            )
            // Gate A structure: exactly the per-anime directory at the root,
            // with the exported file inside it.
            val rootBefore = safChildren(context.contentResolver, tree)
            assertEquals(
                "exactly the per-anime directory at the tree root before the delete",
                1,
                rootBefore.size,
            )
            val dirNameBefore = rootBefore.single().first
            assertEquals(
                "the child is really in the tree before the delete",
                1,
                safChildrenOfDoc(
                    context.contentResolver, tree,
                    "${PBTestDocumentsProvider.ROOT_RENAME}/$dirNameBefore",
                ).size,
            )

            // 2. The confirmed session delete.
            assertTrue(
                "the confirmed delete completes",
                realActions.deleteSession("sSAF", deleteUnexportedLocalMedia = true),
            )

            assertNull("session row cascaded", sessionDao.getById("sSAF"))
            assertTrue(episodeDao.getBySessionId("sSAF").first().isEmpty())
            assertFalse("X6 removed the local source at export", local.exists())
            assertFalse("segment dir removed", d.exists())

            val rootChildren = safChildren(context.contentResolver, tree)
            assertEquals("exactly the per-anime directory remains", 1, rootChildren.size)
            val dirName = rootChildren.single().first
            val children = safChildrenOfDoc(
                context.contentResolver, tree,
                "${PBTestDocumentsProvider.ROOT_RENAME}/$dirName",
            )
            assertEquals("exactly the one real exported child", 1, children.size)
            val (childName, childSize) = children.single()
            assertEquals("the child's byte count is untouched", 64_000L, childSize)
            val childUri = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                "${PBTestDocumentsProvider.ROOT_RENAME}/$dirName/$childName",
            )
            val bytes = context.contentResolver.openInputStream(childUri)!!.use { it.readBytes() }
            assertTrue(
                "the exported child's BYTES are untouched by the session delete",
                bytes.contentEquals(ByteArray(64_000) { 1 }),
            )
        } finally {
            runBlocking {
                outputTrees.clearOutputTree()
                config.updateOutputTreeRenameOk(true)
            }
            SafTestSupport.clearTreeChildren(context, tree)
        }
    }

    /** (display name, byte size) of every child under [tree]'s root document. */
    private fun safChildren(resolver: ContentResolver, tree: Uri): List<Pair<String, Long>> =
        safChildrenOfDoc(resolver, tree, DocumentsContract.getTreeDocumentId(tree))

    /** (display name, byte size) of every child of the tree document [docId]. */
    private fun safChildrenOfDoc(
        resolver: ContentResolver,
        tree: Uri,
        docId: String,
    ): List<Pair<String, Long>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        return resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0) to cursor.getLong(1))
                }
            }
        } ?: emptyList()
    }

    // -----------------------------------------------------------------------
    // Temporary cache (DECISIONS 94 / 106.2 / 107.5)
    // -----------------------------------------------------------------------

    @Test
    fun deleteTemporaryCache_is_refused_while_the_session_is_active_then_keeps_mp4() {
        val d = dir("J_ep1")
        val tempBytes = seedTemps(d)
        val local = file(d, "Anime-J-001.mp4", 777)
        insertSessionWith(
            episode("j1", "sJ", EpisodeDownloadEntity.STATE_DOWNLOADING, d.absolutePath, local.absolutePath),
        )

        runBlocking {
            assertFalse(
                "deleting segments mid-flight would silently break resume",
                actions.deleteTemporaryCache("sJ"),
            )
            assertEquals(tempBytes, actions.cacheSizesBySession()["sJ"])

            // The session becomes terminal → cleanup runs, output preserved.
            episodeDao.updateState("j1", EpisodeDownloadEntity.STATE_FAILED, 5_000L)
            assertTrue(actions.deleteTemporaryCache("sJ"))
        }

        assertFalse("temp artifacts removed", File(d, "seg0.ts").exists())
        assertFalse(File(d, "seg1.tmp").exists())
        assertFalse(File(d, "concat.txt").exists())
        assertTrue("the assembled MP4 is never touched", local.exists())
        assertTrue("the directory survives (it still holds output)", d.exists())
        runBlocking {
            assertTrue("Room rows are kept", episodeDao.getById("j1") != null)
            assertEquals(
                "the size now equals what was freed",
                0L,
                actions.cacheSizesBySession()["sJ"],
            )
        }
    }

    @Test
    fun clearAllCache_is_blocked_by_any_active_session_then_cleans_only_temporaries() {
        val dA = dir("K_a")
        seedTemps(dA, ts = 111, tmp = 22, concat = 7)
        val localA = file(dA, "Anime-K-001.mp4", 5_000)
        val dB = dir("K_b")
        seedTemps(dB, ts = 30, tmp = 5, concat = 5)
        insertSessionWith(
            episode("k1", "sKA", EpisodeDownloadEntity.STATE_COMPLETED, dA.absolutePath, localA.absolutePath),
            episode("k2", "sKB", EpisodeDownloadEntity.STATE_QUEUED, dB.absolutePath),
        )

        runBlocking {
            assertFalse(
                "ONE active row anywhere blocks the global clear (106.2)",
                actions.clearAllCache(),
            )
            assertTrue("nothing was deleted while blocked", File(dA, "seg0.ts").exists())

            episodeDao.updateState("k2", EpisodeDownloadEntity.STATE_CANCELLED, 5_000L)
            assertTrue(actions.clearAllCache())
        }

        assertFalse(File(dA, "seg0.ts").exists())
        assertFalse(File(dB, "seg0.ts").exists())
        assertTrue("output preserved in every session", localA.exists())
        runBlocking {
            assertTrue("Room rows kept (documented divergence §34.3)", episodeDao.getById("k1") != null)
            assertTrue(episodeDao.getById("k2") != null)
            assertEquals("both session rows survive", 2, sessionDao.count())
            assertEquals(
                0L,
                actions.cacheSizesBySession().values.sum(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Cache size (DECISIONS 107)
    // -----------------------------------------------------------------------

    @Test
    fun cacheSizes_count_only_temporary_artifacts_per_session() {
        val d1 = dir("L_ep1")
        val bytes1 = seedTemps(d1, ts = 100, tmp = 50, concat = 10) // 160
        file(d1, "Anime-L-001.mp4", 9_999) // never counted (107.2)
        val d2 = dir("L_ep2")
        val bytes2 = seedTemps(d2, ts = 7, tmp = 2, concat = 1) // 10
        // A duplicate path must be counted once (dedupe).
        insertSessionWith(
            episode("l1", "sL", EpisodeDownloadEntity.STATE_COMPLETED, d1.absolutePath),
            episode("l2", "sL", EpisodeDownloadEntity.STATE_FAILED, d2.absolutePath),
            episode("l3", "sL", EpisodeDownloadEntity.STATE_CANCELLED, d2.absolutePath),
        )

        runBlocking {
            val sizes = actions.cacheSizesBySession()
            assertEquals(bytes1 + bytes2, sizes["sL"])
            assertEquals(bytes1 + bytes2, sizes.values.sum())
        }
    }

    @Test
    fun cacheSizes_ignore_sessions_without_segment_directories() {
        insertSessionWith(
            episode("m1", "sM", EpisodeDownloadEntity.STATE_QUEUED, segmentDir = null),
        )
        runBlocking {
            val sizes = actions.cacheSizesBySession()
            // A session without any segment directory contributes nothing —
            // absent or explicitly zero are the same contract (the UI reads
            // `sizes[sessionId] ?: 0L`).
            assertEquals(0L, sizes["sM"] ?: 0L)
            assertTrue(sizes.values.sum() == 0L)
        }
    }
}
