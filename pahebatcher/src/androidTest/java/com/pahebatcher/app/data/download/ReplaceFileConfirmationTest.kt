package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.download.ReplacementOutcome
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.RecordStreamResolver
import com.pahebatcher.app.support.RetryTestStream
import com.pahebatcher.app.ui.downloads.DownloadsViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Replace file` (DECISIONS 152) — confirmation and routing, cases 1–6.
 *
 * The confirmation is the ONLY request allowed past a valid completed export,
 * so this suite pins what it may and may not do:
 *
 * 1. a valid completed export + confirmation ⇒ the row is CLAIMED
 *    (`replaceRequested = 1`, `replacePriorUri` recorded) and the export it is
 *    replacing is left referenced and intact;
 * 2. a repeated confirmation is idempotent — never a second run;
 * 3. a row that is not `COMPLETED` is refused, untouched;
 * 4. a `COMPLETED` row whose export is GONE is refused, untouched — the
 *    ordinary redownload owns that case, and no destructive dialog may be
 *    offered for it;
 * 5. a stale action for an episode that no longer exists creates nothing;
 * 6. the ViewModel gate mirrors those rules before any dialog is armed.
 *
 * Everything runs against the REAL Room singleton and REAL files in the
 * episode's own directory. The only double is the stream resolver.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceFileConfirmationTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-replace-confirm-$runStamp"

    /** `episodeDownloadId()` = `<animeSession>_<epStr>_<audio>` (whole numbers). */
    private fun episodeId(number: Int) = "${sessionId}_${number}_${EpisodeInfo.AUDIO_JAPANESE}"

    /** A real, non-empty app-private export sitting where the row says it is. */
    private fun seedExport(id: String, marker: String): File {
        val dir = File(Phase10TestSupport.segmentDirFor(context, id))
        val file = File(dir, "Ep_001_-_Replaceable.mp4")
        file.writeText(marker)
        return file
    }

    private fun completedEpisode(id: String, number: Int, outputUri: String?) =
        EpisodeDownloadEntity(
            episodeId = id,
            sessionId = sessionId,
            episodeNumber = number.toFloat(),
            episodeSession = "eps-replace-$number",
            title = "Replaceable Episode $number",
            fansub = "ReplaceFansub",
            audio = EpisodeInfo.AUDIO_JAPANESE,
            playUrl = "https://animepahe.com/play/$sessionId/eps-replace-$number",
            state = EpisodeDownloadEntity.STATE_COMPLETED,
            retryCount = 0,
            errorMessage = null,
            outputPath = File(Phase10TestSupport.segmentDirFor(context, id), "Ep_001_-_Replaceable.mp4").absolutePath,
            outputUri = outputUri,
            createdAt = runStamp,
            updatedAt = runStamp,
            segmentDir = Phase10TestSupport.segmentDirFor(context, id),
        )

    private fun repository(resolver: RecordStreamResolver = RecordStreamResolver { RetryTestStream.streamInfo() }) =
        DownloadRepository(
            context = context,
            dao = db.episodeDownloadDao(),
            sessionDao = db.downloadSessionDao(),
            streamResolver = resolver,
        )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        (1..16).forEach { workManager.cancelUniqueWork("episode_download_${episodeId(it)}") }
        (1..16).forEach { File(Phase10TestSupport.segmentDirFor(context, episodeId(it))).deleteRecursively() }
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    // ── 1. A confirmation claims the row and keeps the export referenced ────

    @Test
    fun confirmation_claims_the_row_and_records_the_export_it_replaces() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(1)
        val priorUri = "content://media/external/file/900001"
        seedExport(id, "export-marker-$runStamp")
        dao.upsert(completedEpisode(id, 1, priorUri))

        val outcome = repository().confirmReplacement(id)

        assertEquals("a valid completed export must be replaceable", ReplacementOutcome.Started, outcome)
        val after = dao.getById(id)
        assertNotNull("the row must still exist", after)
        assertEquals(
            "the replacement intent must be persisted so it survives process death",
            true,
            after!!.replaceRequested,
        )
        assertEquals(
            "the export being replaced must be remembered so it can be retired later",
            priorUri,
            after.replacePriorUri,
        )
        assertEquals(
            "the export's output path must stay referenced — nothing is deleted before the new file exists",
            completedEpisode(id, 1, priorUri).outputPath,
            after.outputPath,
        )
        assertTrue(
            "the row must be in a working state owned by the new run",
            after.state in listOf(
                EpisodeDownloadEntity.STATE_QUEUED,
                EpisodeDownloadEntity.STATE_RESOLVING,
                EpisodeDownloadEntity.STATE_DOWNLOADING,
            ),
        )
        assertEquals("exactly one claim may count", 1, after.retryCount)
    }

    // ── 2. A repeated confirmation is idempotent ────────────────────────────

    @Test
    fun a_second_confirmation_is_a_no_op_never_a_second_run() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(2)
        seedExport(id, "export-marker-$runStamp")
        dao.upsert(completedEpisode(id, 2, null))

        val first = repository().confirmReplacement(id)
        val afterFirst = dao.getById(id)
        assertEquals(ReplacementOutcome.Started, first)

        val second = repository().confirmReplacement(id)

        assertEquals(
            "a repeat confirmation must report the in-flight run, never start another",
            ReplacementOutcome.AlreadyInFlight,
            second,
        )
        val afterSecond = dao.getById(id)
        assertEquals(
            "the retry budget must prove only ONE claim ever happened",
            afterFirst!!.retryCount,
            afterSecond!!.retryCount,
        )
        assertEquals(
            "the persisted intent must be unchanged by the repeat",
            afterFirst.replaceRequested,
            afterSecond.replaceRequested,
        )
        assertEquals(afterFirst.state, afterSecond.state)
    }

    // ── 3. Not COMPLETED ⇒ refused, untouched ───────────────────────────────

    @Test
    fun a_row_that_is_not_completed_is_refused_and_untouched() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(3)
        val seeded = completedEpisode(id, 3, null)
            .copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING, retryCount = 4)
        dao.upsert(seeded)

        val outcome = repository().confirmReplacement(id)

        assertTrue("an in-flight row must be refused", outcome is ReplacementOutcome.Unavailable)
        assertEquals(
            "the row must be byte-identical after a refused confirmation",
            seeded,
            dao.getById(id),
        )
    }

    // ── 4. Export gone ⇒ refused, untouched, resolver never called ──────────

    @Test
    fun a_completed_row_with_no_export_is_refused_and_never_resolves() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(4)
        // outputPath names a file that does NOT exist and outputUri is null —
        // §29 says there is nothing to replace, so nothing may be offered.
        val seeded = completedEpisode(id, 4, null)
        File(seeded.outputPath!!).delete()
        dao.upsert(seeded)
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }

        val outcome = repository(resolver).confirmReplacement(id)

        assertTrue("a vanished export must be refused", outcome is ReplacementOutcome.Unavailable)
        assertEquals(
            "no resolution may ever be attempted for a replacement that cannot exist",
            0,
            resolver.calls.size,
        )
        assertEquals("the row must be untouched", seeded, dao.getById(id))
    }

    // ── 5. Stale action for a vanished episode ──────────────────────────────

    @Test
    fun a_stale_action_for_an_unknown_episode_creates_nothing() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = "${sessionId}_99_${EpisodeInfo.AUDIO_JAPANESE}"

        val outcome = repository().confirmReplacement(id)

        assertTrue("an unknown episode must be refused", outcome is ReplacementOutcome.Unavailable)
        assertNull("no row may be invented for a stale action", dao.getById(id))
    }

    // ── 6. The ViewModel gate arms the dialog only for a real export ────────

    @Test
    fun the_view_model_gates_the_dialog_on_a_real_export() = runBlocking {
        val dao = db.episodeDownloadDao()
        val goodId = episodeId(5)
        val badId = episodeId(6)
        seedExport(goodId, "export-marker-$runStamp")
        dao.upsert(completedEpisode(goodId, 5, null))
        val withoutExport = completedEpisode(badId, 6, null)
        File(withoutExport.outputPath!!).delete()
        dao.upsert(withoutExport)

        val viewModel = DownloadsViewModel(repository())

        // A replacement that cannot exist must never arm a destructive dialog.
        viewModel.requestReplacement(badId)
        assertTrue(
            "a row without an export must not open the confirmation",
            Phase10TestSupport.awaitUntil(5_000) { viewModel.actionError.value != null },
        )
        assertNull(
            "the dialog target must stay empty when there is nothing to replace",
            viewModel.replacementTarget.value,
        )
        viewModel.dismissActionError()

        // A valid export arms exactly that episode's dialog.
        viewModel.requestReplacement(goodId)
        assertTrue(
            "a valid export must arm the confirmation",
            Phase10TestSupport.awaitUntil(5_000) { viewModel.replacementTarget.value == goodId },
        )
        assertNull("arming the dialog must report no error", viewModel.actionError.value)
        assertEquals(
            "arming the dialog must change nothing on disk or in Room",
            EpisodeDownloadEntity.STATE_COMPLETED,
            dao.getById(goodId)!!.state,
        )

        // Dismissal is a pure state clear — the export survives untouched.
        val before = dao.getById(goodId)
        viewModel.dismissReplacement()
        assertNull(viewModel.replacementTarget.value)
        assertEquals("dismissing must not touch the row", before, dao.getById(goodId))
        assertTrue(File(before!!.outputPath!!).exists())
    }
}
