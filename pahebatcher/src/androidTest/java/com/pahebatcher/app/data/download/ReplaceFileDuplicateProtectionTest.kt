package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.ReplacementOutcome
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.RecordStreamResolver
import com.pahebatcher.app.support.RetryTestStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Replace file` (DECISIONS 152) — duplicate protection, cases 7–10.
 *
 * The confirmation is an EXCEPTION to the rule that a valid completed export
 * suppresses further work, and an exception only proves something if the rule
 * itself still holds. This suite pins the ordinary behaviour byte-for-byte
 * around the new path:
 *
 * 7. an ordinary submission of a `COMPLETED` row with a valid export is still
 *    suppressed — no Worker, no second copy;
 * 8. an ordinary [DownloadRepository.reEnqueue] of the same row is still
 *    suppressed, and the resolver is never even consulted;
 * 9. the §29 completed-output rule ([DownloadRepository.isEpisodeOutputPresent])
 *    is unchanged: present ⇒ true, gone ⇒ false, whatever the replacement
 *    columns say;
 * 10. an ordinary submission racing an in-flight replacement neither creates a
 *     second run nor disturbs the replacement's persisted intent.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceFileDuplicateProtectionTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-replace-dup-$runStamp"

    private fun episodeId(number: Int) = "${sessionId}_${number}_${EpisodeInfo.AUDIO_JAPANESE}"

    private fun completedEpisode(id: String, number: Int, outputUri: String? = null) =
        EpisodeDownloadEntity(
            episodeId = id,
            sessionId = sessionId,
            episodeNumber = number.toFloat(),
            episodeSession = "eps-dup-$number",
            title = "Duplicate Episode $number",
            fansub = "DuplicateFansub",
            audio = EpisodeInfo.AUDIO_JAPANESE,
            playUrl = "https://animepahe.com/play/$sessionId/eps-dup-$number",
            state = EpisodeDownloadEntity.STATE_COMPLETED,
            retryCount = 0,
            errorMessage = null,
            outputPath = File(Phase10TestSupport.segmentDirFor(context, id), "Ep_001_-_Duplicate.mp4").absolutePath,
            outputUri = outputUri,
            createdAt = runStamp,
            updatedAt = runStamp,
            segmentDir = Phase10TestSupport.segmentDirFor(context, id),
        )

    /** A real non-empty export where the row says it is. */
    private fun seedExport(id: String, marker: String = "OLD-EXPORT-$id") {
        File(Phase10TestSupport.segmentDirFor(context, id), "Ep_001_-_Duplicate.mp4")
            .writeText(marker)
    }

    private fun episodeInfo(number: Int) = EpisodeInfo(
        number = number.toFloat(),
        session = "eps-dup-$number",
        title = "Duplicate Episode $number",
        fansub = "DuplicateFansub",
        audio = EpisodeInfo.AUDIO_JAPANESE,
        playUrl = "https://animepahe.com/play/$sessionId/eps-dup-$number",
    )

    private fun submission(number: Int) = ResolvedEpisodeSubmission(
        animeSession = sessionId,
        animeTitle = "Duplicate Anime",
        host = "animepahe.com",
        quality = 1080,
        episode = episodeInfo(number),
        stream = StreamInfo(
            url = "https://cdn.example.com/dup-$number.m3u8",
            userAgent = "DupUA",
            referer = "https://kwik.cx/e/dup",
            title = "Duplicate Anime Ep. $number :: animepahe",
        ),
    )

    private fun repository(resolver: RecordStreamResolver) = DownloadRepository(
        context = context,
        dao = db.episodeDownloadDao(),
        sessionDao = db.downloadSessionDao(),
        streamResolver = resolver,
    )

    private fun workerCount(number: Int) =
        workManager.getWorkInfosForUniqueWork("episode_download_${episodeId(number)}").get().size

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

    // ── 7. Ordinary submission of a valid completed export is still suppressed

    @Test
    fun an_ordinary_submission_of_a_valid_completed_export_is_still_suppressed() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(1)
        seedExport(id)
        dao.upsert(completedEpisode(id, 1))

        val outcome = repository(RecordStreamResolver { RetryTestStream.streamInfo() })
            .queueResolvedEpisode(submission(1))

        assertEquals(
            "the ordinary suppression must be unchanged by the replacement feature",
            EnqueueOutcome.AlreadyDownloaded,
            outcome,
        )
        assertEquals(
            "no Worker may be enqueued for a download that already exists",
            0,
            workerCount(1),
        )
        assertEquals(
            "the row must be byte-identical after a suppressed submission",
            completedEpisode(id, 1),
            dao.getById(id),
        )
        assertTrue("the export must survive", File(dao.getById(id)!!.outputPath!!).exists())
    }

    // ── 8. Ordinary re-enqueue of the same row is still suppressed ──────────

    @Test
    fun an_ordinary_re_enqueue_of_a_valid_completed_export_is_still_suppressed() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(2)
        seedExport(id)
        dao.upsert(completedEpisode(id, 2))
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }

        repository(resolver).reEnqueue(id)

        assertEquals(
            "an ordinary Retry must never re-resolve a completed, exported episode",
            0,
            resolver.calls.size,
        )
        assertEquals(
            "the row must be byte-identical after a suppressed re-enqueue",
            completedEpisode(id, 2),
            dao.getById(id),
        )
        assertEquals(0, workerCount(2))
    }

    // ── 9. The §29 completed-output rule is unchanged ───────────────────────

    @Test
    fun the_completed_output_rule_is_unchanged_around_replacement() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(3)
        seedExport(id)
        val repo = repository(RecordStreamResolver { RetryTestStream.streamInfo() })

        dao.upsert(completedEpisode(id, 3))
        assertTrue(
            "a completed row with a real export must still count as output present",
            repo.isEpisodeOutputPresent(sessionId, episodeInfo(3)),
        )
        assertTrue(
            "the replacement gate must agree with the §29 rule for the same row",
            repo.isReplacementAvailable(id),
        )

        // The export disappears: the rule must flip, and the replacement gate
        // with it — never a dialog offering to replace nothing.
        File(dao.getById(id)!!.outputPath!!).delete()
        assertEquals(
            "a completed row whose export is gone must not count as output present",
            false,
            repo.isEpisodeOutputPresent(sessionId, episodeInfo(3)),
        )
        assertEquals(
            "a replacement must be refused for an export that is no longer there",
            false,
            repo.isReplacementAvailable(id),
        )
    }

    // ── 10. An ordinary submission racing an in-flight replacement ──────────

    @Test
    fun an_ordinary_submission_racing_a_replacement_never_creates_a_second_run() = runBlocking {
        val dao = db.episodeDownloadDao()
        val id = episodeId(4)
        seedExport(id)
        val priorUri = "content://media/external/file/900004"
        dao.upsert(completedEpisode(id, 4, priorUri))

        // Arm a real replacement claim (what `Confirm replacement` persists).
        assertEquals(
            ReplacementOutcome.Started,
            repository(RecordStreamResolver { RetryTestStream.streamInfo() }).confirmReplacement(id),
        )
        val during = dao.getById(id)!!
        assertTrue("the replacement intent must be armed", during.replaceRequested)

        // An ordinary submission now arrives for the same episode.
        val enqueued = repository(RecordStreamResolver { RetryTestStream.streamInfo() })
            .enqueue(completedEpisode(id, 4, priorUri))

        assertTrue(
            "an ordinary submission must be a no-op for a row a run already owns",
            enqueued,
        )
        val after = dao.getById(id)
        assertEquals(
            "the replacement's state must not be disturbed by the ordinary submission",
            during.state,
            after!!.state,
        )
        assertEquals(
            "the replacement intent must survive an ordinary submission",
            during.replaceRequested,
            after.replaceRequested,
        )
        assertEquals(
            "the export being replaced must survive an ordinary submission",
            during.replacePriorUri,
            after.replacePriorUri,
        )
        assertEquals(
            "the export must still be referenced",
            during.outputPath,
            after.outputPath,
        )
        assertTrue("the export file must still be there", File(after.outputPath!!).exists())
    }
}
