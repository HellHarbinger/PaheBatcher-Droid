package com.pahebatcher.app.domain.watchlist

import com.pahebatcher.app.data.local.entity.WatchlistEntity
import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import com.pahebatcher.app.data.repository.FakeScanCacheDao
import com.pahebatcher.app.data.repository.FakeWatchlistDao
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.data.repository.WatchlistRepository
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AnimeInfo
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * `run_watchlist_check` (`watchlist.py:685-1118`) as the
 * [WatchlistChecker] use case, exercised through the SAME production chain the
 * Watchlist screen uses (EpisodeRepository scan → parser, repositories, resolver,
 * download queue).
 *
 * Covered here, one test per reference rule:
 *
 * | behavior | reference |
 * |---|---|
 * | new episodes are detected and auto-downloaded with the ENTRY's prefs | `watchlist.py:1049-1056` |
 * | an episode that was deleted on purpose is skipped, never re-downloaded | `watchlist.py:1021-1023` |
 * | output already present counts as downloaded and backfills the history | `watchlist.py:1015-1019` |
 * | missing download storage resets the skip history (everything pending) | `watchlist.py:987-996` |
 * | dead link + exactly one matching session → relink and rescan | `watchlist.py:844-881` |
 * | dead link + no matching session / two candidates / unusable title | `watchlist.py:882-935` |
 * | title recovered from the stored scan cache when the page is 404 | `watchlist.py:39-56` |
 * | a scan failure is isolated, and `last_checked` is still persisted | `watchlist.py:779-780` |
 * | one entry failing never stops the run over the remaining entries | `watchlist.py:773-782` |
 *
 * No network (AGENTS §5): the scan runs through [FakeHttpClient], and the
 * resolver/queue are test-only doubles.
 */
class WatchlistCheckerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var watchlistDao: FakeWatchlistDao
    private lateinit var watchlistRepository: WatchlistRepository
    private lateinit var scanCacheRepository: ScanCacheRepository
    private lateinit var queue: RecordingQueue
    private lateinit var resolver: RecordingResolver
    private lateinit var downloadRoot: File

    private fun newChecker(client: HttpClient, root: File = tempFolder.root): WatchlistChecker {
        watchlistDao = FakeWatchlistDao()
        watchlistRepository = WatchlistRepository(watchlistDao)
        scanCacheRepository = ScanCacheRepository(FakeScanCacheDao())
        queue = RecordingQueue()
        resolver = RecordingResolver()
        downloadRoot = root
        val animeRepository = AnimeRepository(client)
        return WatchlistChecker(
            episodeRepository = EpisodeRepository(
                animeRepository = animeRepository,
                scanCacheRepository = scanCacheRepository,
            ),
            animeRepository = animeRepository,
            scanCacheRepository = scanCacheRepository,
            watchlistRepository = watchlistRepository,
            streamResolver = resolver,
            downloadQueue = queue,
            downloadRoot = { downloadRoot },
            configProvider = { AppContext.defaults() },
        )
    }

    private fun entry(
        session: String = "sess-old",
        title: String = "Test Anime",
        quality: Int = 720,
        audioLang: String = "jpn",
        maxParallel: Int = 2,
        autoRetry: Int = 0,
    ) = WatchlistEntity(
        sessionId = session,
        url = "https://animepahe.com/anime/$session",
        animeSession = session,
        host = "animepahe.com",
        title = title,
        quality = quality,
        audioLang = audioLang,
        outputDir = "./downloads",
        maxParallel = maxParallel,
        hlsWorkers = 24,
        keepTemp = false,
        autoRetry = autoRetry,
        addedAt = 1_000L,
        lastCheckedAt = 0L,
        downloadedEpisodesJson = "[]",
    )

    /** Live listing: episode 1 SUB + DUB, episode 2 SUB. */
    private fun livePage(title: String = "Test Anime"): String = """
        {"data": [
            {"episode": 1, "session": "ep1-jpn", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 1, "session": "ep1-eng", "title": "Beginnings", "fansub": "DubsPlease", "audio": "eng"},
            {"episode": 2, "session": "ep2-jpn", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "$title"}}
    """.trimIndent()

    /**
     * One page carrying 150 DISTINCT episodes and no usable paginator — a
     * title whose pending set is far past the batch ceiling, so the ceiling has
     * to be enforced by EXECUTION rather than by what the screen happened to
     * load.
     */
    private fun longPage(title: String = "Test Anime"): String {
        val rows = (1..150).joinToString(",\n") { number ->
            """{"episode": $number, "session": "ep$number-jpn", "title": "Episode $number",
                "fansub": "SubsPlease", "audio": "jpn"}"""
        }
        return """{"data": [$rows], "last_page": 1, "anime": {"title": "$title"}}"""
    }

    private fun searchResults(vararg sessions: String, title: String = "Test Anime"): String = sessions
        .joinToString(prefix = "{\"data\": [", postfix = "]}") { session ->
            """{"session": "$session", "title": "$title", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"}"""
        }

    /**
     * A scan that reports "Unknown Anime" with no episodes: the episode API
     * answers nothing for BOTH the discovery call and the scan loop, so this
     * client leaves `m=release` unstubbed.
     */
    private fun deadClient(): FakeHttpClient = FakeHttpClient()

    /** A live scan: the first two `m=release` answers are empty (dead check), then live. */
    private fun relinkableClient(): FakeHttpClient = FakeHttpClient().apply {
        stubJson("m=release", null)
        stubJson("m=release", null)
        stubJson("m=release", livePage())
    }

    private fun liveClient(): FakeHttpClient =
        FakeHttpClient().apply { stubJson("m=release", livePage()) }

    // -----------------------------------------------------------------------
    // New episodes / freshness
    // -----------------------------------------------------------------------

    @Test
    fun `new episodes are detected and queued with the entry's own preferences`() = runTest {
        val checker = newChecker(liveClient())
        val stored = entry(quality = 720, audioLang = "jpn")
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.NEW_EPISODES, result.status)
        assertEquals(2, result.episodesTotal)
        assertEquals(2, result.newEpisodes)
        assertEquals(2, result.queued)
        assertEquals(0, result.skippedDeleted)
        assertFalse(result.relinked)

        assertEquals(2, queue.submissions.size)
        assertTrue(
            "the ENTRY's quality (720) is used, not the global default",
            resolver.calls.all { it.quality == 720 },
        )
        assertTrue(queue.submissions.all { it.animeSession == "sess-old" })
        assertTrue(queue.submissions.all { it.quality == 720 })

        val after = watchlistDao.rows.getValue("sess-old")
        assertTrue("last_checked is persisted", after.lastCheckedAt > 0L)
    }

    // -----------------------------------------------------------------------
    // Slice B closure (review finding F3): outcome → queued/skipped split
    // -----------------------------------------------------------------------

    /**
     * F3: every verdict the pipeline can return must land in the right bucket,
     * AND the summary sentence must never claim queued work it did not do.
     *
     * `queued` counts only [EnqueueOutcome.Accepted] and
     * [EnqueueOutcome.OutputMissing] — a Worker really was handed the episode —
     * and everything else is `skippedExisting`. Before this slice the fake
     * always returned `Accepted`, so this split (the point of Decision 146.2)
     * was pinned for exactly one of its six inputs.
     *
     * One full check per verdict, because each run needs a fresh entry: the
     * first run already records the episodes it saw.
     */
    @Test
    fun `every pipeline outcome is counted honestly and never overstates the summary`() = runTest {
        val cases = listOf(
            EnqueueOutcome.Accepted to true,
            EnqueueOutcome.OutputMissing to true,
            EnqueueOutcome.AlreadyActive to false,
            EnqueueOutcome.AlreadyDownloaded to false,
            EnqueueOutcome.AlreadyPaused to false,
            EnqueueOutcome.Refused to false,
        )

        cases.forEach { (outcome, countsAsQueued) ->
            val checker = newChecker(liveClient())
            queue.outcomeFor = { outcome }
            val stored = entry()
            watchlistRepository.addOrUpdate(stored)

            val result = checker.check(stored)

            assertEquals(
                "$outcome: the discovered pending count does not depend on the verdict",
                2,
                result.newEpisodes,
            )
            assertEquals(
                "$outcome: only a real hand-off counts as queued",
                if (countsAsQueued) 2 else 0,
                result.queued,
            )
            assertEquals(
                "$outcome: everything the pipeline declined is 'not newly queued'",
                if (countsAsQueued) 0 else 2,
                result.skippedExisting,
            )
            assertEquals(
                "$outcome: the summary states exactly the split that was counted",
                if (countsAsQueued) {
                    "2 new episode(s) queued."
                } else {
                    "No new downloads — 2 already downloading, downloaded, paused or not started."
                },
                result.message,
            )
        }
    }

    @Test
    fun `an episode deleted on purpose is skipped and never re-downloaded`() = runTest {
        val checker = newChecker(liveClient())
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)
        // History says episode 1 was downloaded before; its output is gone
        // (user deleted it) and the download storage still exists.
        watchlistRepository.setDownloadedEpisodes("sess-old", listOf(1.0))

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.NEW_EPISODES, result.status)
        assertEquals(1, result.skippedDeleted)
        assertEquals("only the new episode is pending", 1, result.newEpisodes)
        assertEquals(1, queue.submissions.size)
        assertEquals(
            "the deleted episode is not resolved again",
            2f,
            queue.submissions.single().episode.number,
        )
        assertTrue(
            "the history is kept (so it stays deleted)",
            watchlistRepository.downloadedEpisodes("sess-old")!!.contains(1.0),
        )
    }

    @Test
    fun `output already present counts as downloaded and backfills the history`() = runTest {
        val checker = newChecker(liveClient())
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)
        // Handled AND really on disk — the two conditions Python's `_find_existing`
        // (watchlist.py:1015-1019) needs before it backfills the history.
        queue.handledNumbers = mutableSetOf(1f, 2f)
        queue.outputNumbers = mutableSetOf(1f, 2f)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.UP_TO_DATE, result.status)
        assertEquals(0, result.newEpisodes)
        assertEquals(0, result.queued)
        assertEquals(0, result.skippedDeleted)
        assertTrue("nothing was resolved", resolver.calls.isEmpty())

        val history = watchlistRepository.downloadedEpisodes("sess-old")!!
        assertTrue(history.contains(1.0))
        assertTrue(history.contains(2.0))
    }

    @Test
    fun `an in-flight download is skipped but never enters the deleted-skip history`() = runTest {
        val checker = newChecker(liveClient())
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)
        // Episode 1 is RESOLVING/DOWNLOADING: handled, but no output exists yet.
        queue.handledNumbers = mutableSetOf(1f)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.NEW_EPISODES, result.status)
        assertEquals("only episode 2 is pending", 1, result.newEpisodes)
        assertEquals(1, queue.submissions.size)
        assertEquals(2f, queue.submissions.single().episode.number)
        assertTrue(
            "recording an in-flight row as downloaded would make a later FAILED " +
                "retry look deliberately deleted and never run again (review H-1)",
            watchlistRepository.downloadedEpisodes("sess-old")!!.isEmpty(),
        )
    }

    @Test
    fun `an exhausted capacity bound is reported as FAILED, never as a healthy status`() = runTest {
        val checker = newChecker(liveClient())
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)
        // The staging depth never drops, so the gate's bounded wait expires.
        queue.stagedDepth = Int.MAX_VALUE

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.FAILED, result.status)
        assertTrue("message: ${result.message}", result.message!!.contains("capacity"))
        assertTrue(
            "last_checked is persisted even when the run could not finish",
            watchlistDao.rows.getValue("sess-old").lastCheckedAt > 0L,
        )
        assertTrue("nothing was resolved", resolver.calls.isEmpty())
        assertTrue(queue.submissions.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Lazy-loading slice — the NON-UI batch execution boundary
    // -----------------------------------------------------------------------

    /**
     * The 100-episode ceiling has to hold where there is no screen, no
     * selection state and no range field anywhere in the call chain: the
     * watchlist auto-downloader. It must refuse the WHOLE batch with an
     * explicit outcome — never quietly enqueue the first 100 and drop the rest,
     * which would look like a healthy run that silently skipped half the anime.
     */
    @Test
    fun `more than 100 unique pending episodes is refused as a whole and reported as a failure`() = runTest {
        val checker = newChecker(FakeHttpClient().apply { stubJson("m=release", longPage()) })
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.FAILED, result.status)
        assertTrue(
            "the refusal reaches the user verbatim: ${result.message}",
            result.message!!.contains("at most 100"),
        )
        assertEquals("all 150 really were new episodes", 150, result.episodesTotal)
        assertEquals(150, result.newEpisodes)
        assertEquals("…and not ONE of them was queued", 0, result.queued)
        assertTrue(
            "the boundary is reached before any stream is resolved",
            resolver.calls.isEmpty(),
        )
        assertTrue(queue.submissions.isEmpty())
        assertTrue(
            "the check is still recorded — the entry is not stuck un-checked",
            watchlistDao.rows.getValue("sess-old").lastCheckedAt > 0L,
        )
    }

    /**
     * The other side of the same boundary: exactly 100 unique episodes is
     * inside the ceiling, so the batch runs rather than being refused.
     */
    @Test
    fun `exactly 100 unique pending episodes passes the batch boundary`() = runTest {
        val checker = newChecker(liveClient())
        val anime = AnimeInfo(
            session = "sess-old",
            title = "Test Anime",
            host = "animepahe.com",
            episodes = (1..100).map { number ->
                EpisodeInfo(
                    number.toFloat(),
                    "ep$number-jpn",
                    "Episode $number",
                    "SubsPlease",
                    "jpn",
                    "https://animepahe.com/play/$number/jpn",
                )
            },
        )

        val outcome = checker.autoDownload(entry(), anime, anime.episodes)

        assertNull("100 unique episodes is inside the ceiling", outcome.rejection)
        assertEquals(
            "and the batch really ran over all of them",
            100,
            outcome.queued + outcome.skippedExisting + outcome.failed,
        )
        assertEquals("…queueing every one", 100, outcome.queued)
        assertEquals("with no capacity stall", false, outcome.capacityTimedOut)
    }

    @Test
    fun `an unreachable search is reported as FAILED, never as no session found`() = runTest {
        // No m=search stub at all: no host answers, which the search seam reports
        // as `Unreachable` — strictly different from a successful empty result
        // (that case is the "no session found" LINK_DEAD test above).
        val checker = newChecker(deadClient())
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.FAILED, result.status)
        assertTrue("message: ${result.message}", result.message!!.contains("Search unreachable"))
        assertFalse(result.relinked)
        assertTrue(
            "last_checked is persisted even when the search could not run",
            watchlistDao.rows.getValue("sess-old").lastCheckedAt > 0L,
        )
        assertTrue(queue.submissions.isEmpty())
    }

    @Test
    fun `missing download storage resets the skip history so everything is pending again`() = runTest {
        val missingRoot = File(tempFolder.root, "gone")
        assertFalse(missingRoot.exists())
        val checker = newChecker(liveClient(), root = missingRoot)
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)
        watchlistRepository.setDownloadedEpisodes("sess-old", listOf(1.0, 2.0))

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.NEW_EPISODES, result.status)
        assertEquals("every episode is pending again", 2, result.newEpisodes)
        assertEquals(2, queue.submissions.size)
        assertEquals(0, result.skippedDeleted)
        assertTrue(
            "the skip history was cleared",
            watchlistRepository.downloadedEpisodes("sess-old")!!.isEmpty(),
        )
    }

    @Test
    fun `a concrete audio the listing lacks still reaches the resolver with the requested audio`() = runTest {
        val checker = newChecker(liveClient())
        val stored = entry(audioLang = "eng")
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        // DECISIONS 144.4: release-listing audio can never prove a PLAY PAGE
        // lacks an audio, so the pre-resolve listing-only skip is GONE where
        // the number exists. The number's own row carries the REQUESTED audio
        // as intent to the resolver, which decides — no substitution on the
        // way in, and an audio that really is missing fails the attempt.
        assertEquals(WatchlistCheckStatus.NEW_EPISODES, result.status)
        val byNumber = queue.submissions.associateBy { it.episode.number }
        assertTrue(
            "episode 1 downloads the DUB the entry pinned",
            byNumber.getValue(1f).episode.playUrl.contains("ep1-eng"),
        )
        assertEquals("eng", byNumber.getValue(1f).episode.audio)
        val carrier = byNumber.getValue(2f)
        assertEquals(
            "episode 2 (no eng row in the listing) resolves as eng — never as jpn",
            "eng",
            carrier.episode.audio,
        )
        assertTrue(
            "and it asks the number's OWN play page",
            carrier.episode.playUrl.contains("ep2-jpn"),
        )
        assertEquals(
            "both numbers are pending now",
            setOf(1f, 2f),
            queue.submissions.map { it.episode.number }.toSet(),
        )
        assertTrue(
            "the RESOLVER was asked for the requested audio on that page: ${resolver.calls}",
            resolver.calls.any {
                it.audio == "eng" && it.playUrl.contains("ep2-jpn")
            },
        )
        assertEquals(2, result.queued)
        assertEquals("both numbers count as new", 2, result.newEpisodes)
        assertFalse(
            "no listing-only skip note remains: ${result.message}",
            result.message!!.contains("skipped"),
        )
    }

    @Test
    fun `the any sentinel resolves each number through the deterministic default variant`() = runTest {
        val checker = newChecker(liveClient())
        val stored = entry(audioLang = WatchlistEntity.AUDIO_ANY)
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.NEW_EPISODES, result.status)
        assertEquals(2, result.queued)
        assertEquals(
            "AUDIO_ANY leaves no number behind",
            setOf(1f, 2f),
            queue.submissions.map { it.episode.number }.toSet(),
        )
        val byNumber = queue.submissions.associateBy { it.episode.number }
        assertEquals(
            "episode 1 has a jpn variant, so the default is jpn",
            "jpn",
            byNumber.getValue(1f).episode.audio,
        )
        assertTrue(byNumber.getValue(1f).episode.playUrl.contains("ep1-jpn"))
        assertEquals(
            "episode 2 only has jpn",
            "jpn",
            byNumber.getValue(2f).episode.audio,
        )
    }

    @Test
    fun `a legacy jpn entry keeps downloading exactly the jpn variants`() = runTest {
        val checker = newChecker(liveClient())
        val stored = entry(audioLang = "jpn")
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(2, result.queued)
        assertTrue(
            "the eng variant of episode 1 is NOT chosen for a jpn entry",
            queue.submissions.none { it.episode.playUrl.contains("ep1-eng") },
        )
        assertTrue(queue.submissions.all { it.episode.audio == "jpn" })
    }

    // -----------------------------------------------------------------------
    // Dead link recovery
    // -----------------------------------------------------------------------

    @Test
    fun `a dead link with exactly one matching session is relinked and rescanned`() = runTest {
        val client = relinkableClient().apply {
            stubJson("m=search", searchResults("sess-new"))
        }
        val checker = newChecker(client)
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)
        watchlistRepository.setDownloadedEpisodes("sess-old", listOf(1.0))

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.NEW_EPISODES, result.status)
        assertTrue("the entry was migrated", result.relinked)
        assertEquals("the live rescan found the second episode pending", 1, result.newEpisodes)

        assertFalse("the dead session row is gone", watchlistDao.rows.containsKey("sess-old"))
        val migrated = watchlistDao.rows["sess-new"]
        assertNotNull("the entry lives under the new session", migrated)
        assertEquals(
            "the original added_at is preserved",
            1_000L,
            migrated!!.addedAt,
        )
        assertEquals(
            "download history survives the relink",
            listOf(1.0),
            watchlistRepository.downloadedEpisodes("sess-new")!!.sorted(),
        )
        assertEquals("prefs survive the relink", 720, migrated.quality)
        assertTrue("last_checked is persisted on the migrated entry", migrated.lastCheckedAt > 0L)
        assertTrue(queue.submissions.all { it.animeSession == "sess-new" })
    }

    @Test
    fun `a dead link with no matching session is reported without touching the entry`() = runTest {
        val client = deadClient().apply { stubJson("m=search", """{"data": []}""") }
        val checker = newChecker(client)
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.LINK_DEAD, result.status)
        assertFalse(result.relinked)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("no session found"))
        assertTrue("the entry keeps its session", watchlistDao.rows.containsKey("sess-old"))
        assertTrue(watchlistDao.rows.getValue("sess-old").lastCheckedAt > 0L)
        assertTrue("nothing was resolved", queue.submissions.isEmpty())
    }

    @Test
    fun `two exact-title candidates are never auto-migrated`() = runTest {
        val client = deadClient().apply {
            stubJson("m=search", searchResults("sess-new-1", "sess-new-2"))
        }
        val checker = newChecker(client)
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.AMBIGUOUS, result.status)
        assertFalse(result.relinked)
        assertTrue(result.message!!.contains("not auto-migrating"))
        assertEquals("only the original entry exists", 1, watchlistDao.rows.size)
        assertTrue(watchlistDao.rows.containsKey("sess-old"))
        assertTrue(watchlistDao.rows.getValue("sess-old").lastCheckedAt > 0L)
    }

    @Test
    fun `a dead link with an unusable title and no cache is an honest unknown-title failure`() = runTest {
        val checker = newChecker(deadClient())
        // Placeholder title (the session UUID) + real history → recoverable, but
        // there is no cached title to recover from.
        val stored = entry(title = "sess-old")
        watchlistRepository.addOrUpdate(stored)
        watchlistRepository.setDownloadedEpisodes("sess-old", listOf(1.0))

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.UNKNOWN_TITLE, result.status)
        assertFalse(result.relinked)
        assertTrue(result.message!!.contains("title unknown"))
        assertTrue(watchlistDao.rows.containsKey("sess-old"))
        assertTrue(watchlistDao.rows.getValue("sess-old").lastCheckedAt > 0L)
        assertTrue(queue.submissions.isEmpty())
    }

    @Test
    fun `the stored scan cache supplies the title the dead link needs`() = runTest {
        val client = relinkableClient().apply {
            stubJson("m=search", searchResults("sess-new", title = "Cached Anime"))
        }
        val checker = newChecker(client)
        // Python consults the scan cache ONLY when the stored title is 404-style
        // (`watchlist.py:68-72`), so a 404 title is what makes recovery possible.
        val stored = entry(title = "404 Not Found - oops")
        watchlistRepository.addOrUpdate(stored)
        watchlistRepository.setDownloadedEpisodes("sess-old", listOf(1.0))
        // Cached title from an earlier scan — exactly what Python globs for.
        scanCacheRepository.saveScanCache(
            AnimeInfo(
                session = "sess-old",
                title = "Cached Anime",
                host = "animepahe.com",
                episodes = emptyList(),
            ),
            cacheTtlMinutes = 60,
        )

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.NEW_EPISODES, result.status)
        assertTrue(
            "recovery ran from the cache (the placeholder title could not have matched)",
            result.relinked,
        )
        assertTrue(watchlistDao.rows.containsKey("sess-new"))
    }

    @Test
    fun `a migrated session that is still dead keeps the new link and reports the error`() = runTest {
        // The rescan after the relink ALSO returns nothing: only two nulls, and
        // every later `m=release` answer is missing too.
        val client = FakeHttpClient().apply {
            stubJson("m=search", searchResults("sess-new"))
        }
        val checker = newChecker(client)
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)
        watchlistRepository.setDownloadedEpisodes("sess-old", listOf(1.0))

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.LINK_DEAD, result.status)
        assertTrue("the migration itself happened", result.relinked)
        assertTrue(result.message!!.contains("still dead"))
        assertFalse("the old row was replaced", watchlistDao.rows.containsKey("sess-old"))
        assertTrue(watchlistDao.rows.containsKey("sess-new"))
        assertTrue(queue.submissions.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Failure isolation
    // -----------------------------------------------------------------------

    @Test
    fun `a scan failure is isolated and still persists last_checked`() = runTest {
        val checker = newChecker(ThrowingClient("network down"))
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(WatchlistCheckStatus.FAILED, result.status)
        assertEquals("network down", result.message)
        assertTrue(watchlistDao.rows.getValue("sess-old").lastCheckedAt > 0L)
        assertTrue(queue.submissions.isEmpty())
    }

    @Test
    fun `an incomplete scan is reported as a failed check, never as an up-to-date one`() = runTest {
        // discovery → page 1 of 2 → the second page never arrives, so the scan
        // collects only part of the listing (`extract/scanner.py:267-279`).
        val client = FakeHttpClient().apply {
            stubJson(
                "m=release",
                """{"data": [], "anime": {"title": "Test Anime"}, "last_page": 2}""",
            )
            stubJson(
                "m=release",
                """
                {"data": [{"episode": 1, "session": "ep1-jpn", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"}], "last_page": 2, "anime": {"title": "Test Anime"}}
                """.trimIndent(),
            )
            stubJson("m=release", null)
        }
        val checker = newChecker(client)
        val stored = entry()
        watchlistRepository.addOrUpdate(stored)

        val result = checker.check(stored)

        assertEquals(
            "a truncated listing is not a healthy check",
            WatchlistCheckStatus.FAILED,
            result.status,
        )
        assertTrue(result.message!!.contains("ended early"))
        assertTrue(
            "no episode of a partial listing may be queued",
            queue.submissions.isEmpty(),
        )
        assertTrue(
            "the pass is still recorded",
            watchlistDao.rows.getValue("sess-old").lastCheckedAt > 0L,
        )
    }

    @Test
    fun `one broken entry never stops the run over the healthy ones`() = runTest {
        val client = object : HttpClient {
            val delegate = liveClient()

            override suspend fun fetchJson(url: String): String? {
                if (url.contains("bad-sess")) throw IOException("session is gone")
                return delegate.fetchJson(url)
            }

            override suspend fun fetchHtml(url: String): String? {
                if (url.contains("bad-sess")) throw IOException("session is gone")
                return delegate.fetchHtml(url)
            }
        }
        val checker = newChecker(client)
        val broken = entry(session = "bad-sess", title = "Broken Anime")
        val healthy = entry(session = "good-sess", title = "Test Anime")
        watchlistRepository.addOrUpdate(broken)
        watchlistRepository.addOrUpdate(healthy)

        val results = checker.checkAll()

        assertEquals(2, results.size)
        val bySession = results.associateBy { it.sessionId }
        assertEquals(WatchlistCheckStatus.FAILED, bySession.getValue("bad-sess").status)
        assertEquals(WatchlistCheckStatus.NEW_EPISODES, bySession.getValue("good-sess").status)
        assertEquals(
            "only the healthy entry produced downloads",
            2,
            queue.submissions.size,
        )
        assertTrue(queue.submissions.all { it.animeSession == "good-sess" })
        assertTrue(
            "both entries were checked (last_checked persisted on each)",
            watchlistDao.rows.getValue("bad-sess").lastCheckedAt > 0L &&
                watchlistDao.rows.getValue("good-sess").lastCheckedAt > 0L,
        )
    }

    // -----------------------------------------------------------------------
    // Manual relink (DECISIONS 108.4/108.13/108.14)
    // -----------------------------------------------------------------------

    @Test
    fun `checkRelinked keeps a still-dead user link and never searches again`() = runTest {
        // The entry was ALREADY re-keyed by a user-confirmed manual relink and
        // its new page is dead. A search stub exists that WOULD offer another
        // candidate — the manual rescan must not take it (DECISIONS 108.14:
        // keep the user's link, report honestly, never relink twice).
        val client = deadClient().apply { stubJson("m=search", searchResults("sess-also-new")) }
        val checker = newChecker(client)
        val original = entry()
        watchlistRepository.addOrUpdate(original)
        val stored = watchlistRepository.relink(original, "sess-manual", original.host)
        // History travels with the relink — without it the AUTOMATIC path would
        // refuse to search at all, so skipping the search below is really proven.
        watchlistRepository.setDownloadedEpisodes("sess-manual", listOf(1.0))

        val result = checker.checkRelinked(stored)

        assertEquals(WatchlistCheckStatus.LINK_DEAD, result.status)
        assertTrue("the manual migration is reported as a relink", result.relinked)
        assertEquals(
            "the automatic path's exact still-dead wording (108.14)",
            "Migrated session for '${stored.title}' is still dead — the new link was kept.",
            result.message,
        )
        assertEquals("the result follows the new key", "sess-manual", result.sessionId)
        assertNotNull("the user's link was kept", watchlistDao.rows["sess-manual"])
        assertFalse(
            "no second migration happened",
            watchlistDao.rows.containsKey("sess-also-new"),
        )
        assertTrue(
            "last_checked was persisted on the new key",
            watchlistDao.rows.getValue("sess-manual").lastCheckedAt > 0L,
        )
        assertTrue(
            "the rescan never re-searched (108.14)",
            client.requestedUrls.none { it.contains("m=search") },
        )
    }

    @Test
    fun `findCandidateRows is the one shared search carrying the search row metadata`() = runTest {
        val client = FakeHttpClient().apply {
            stubJson(
                "m=search",
                """
                {"data": [
                    {"session": "sess-new", "title": "Test Anime", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"},
                    {"session": "sess-new", "title": "Test Anime", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"},
                    {"session": "sess-old", "title": "Test Anime", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"},
                    {"session": "sess-other", "title": "Different Show", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"},
                    {"session": "", "title": "Test Anime", "type": "TV", "year": 2024, "episodes": 12, "score": "8.5"},
                    {"session": "sess-nodata", "title": "Test Anime", "type": "", "year": 0, "episodes": 0, "score": ""}
                ]}
                """.trimIndent(),
            )
        }
        val checker = newChecker(client)
        val stored = entry()

        val rows = checker.findCandidateRows(stored, stored.title)

        assertNotNull(rows)
        assertEquals(
            "current session excluded, exact normalized title only, duplicates collapsed",
            listOf("sess-new", "sess-nodata"),
            rows!!.map { it.session },
        )
        val first = rows[0]
        assertEquals("Test Anime", first.title)
        assertEquals("TV", first.type)
        assertEquals(2024, first.year)
        assertEquals(12, first.episodes)
        assertEquals("8.5", first.score)
        // 108.5 whitelist: empty parser defaults map to null — nothing fabricated.
        assertNull(rows[1].type)
        assertNull(rows[1].year)
        assertNull(rows[1].episodes)
        assertNull(rows[1].score)
    }

    @Test
    fun `findCandidateRows reports an unreachable search as null not as no candidates`() =
        runTest {
            val checker = newChecker(deadClient()) // no m=search stub → no host answers

            assertNull(checker.findCandidateRows(entry(), entry().title))
        }

    // -----------------------------------------------------------------------
    // Test doubles (AGENTS §5 — mocks live in tests only)
    // -----------------------------------------------------------------------

    /** Client whose requests fail — the scan surface of a network error. */
    private class ThrowingClient(private val message: String) : HttpClient {
        override suspend fun fetchJson(url: String): String? = throw IOException(message)

        override suspend fun fetchHtml(url: String): String? = throw IOException(message)
    }

    private class RecordingResolver : StreamResolver {
        data class Call(val playUrl: String, val quality: Int, val audio: String)

        val calls = mutableListOf<Call>()

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            calls += Call(playUrl = playUrl, quality = quality, audio = audio)
            return StreamInfo(
                url = "https://cdn.example.invalid/${calls.size}.m3u8",
                userAgent = "Mozilla/5.0 (test)",
                referer = playUrl,
            )
        }
    }

    private class RecordingQueue : EpisodeDownloadQueue {
        val submissions = mutableListOf<ResolvedEpisodeSubmission>()
        var handledNumbers: MutableSet<Float> = mutableSetOf()
        var outputNumbers: MutableSet<Float> = mutableSetOf()

        /** Staging depth the resolve-ahead gate reads (Int.MAX_VALUE never drains). */
        var stagedDepth: Int = 0

        /**
         * Slice B closure (review finding F3) — the verdict the fake hands back,
         * scripted per episode number.
         *
         * It defaults to [EnqueueOutcome.Accepted] so every pre-existing test
         * keeps asserting the happy path unchanged; a test that wants to pin the
         * outcome→counter split sets this instead of needing six separate fakes.
         */
        var outcomeFor: (Float) -> EnqueueOutcome = { EnqueueOutcome.Accepted }

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            return outcomeFor(submission.episode.number)
        }

        override suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo): Boolean =
            episode.number in handledNumbers

        override suspend fun isEpisodeOutputPresent(animeSession: String, episode: EpisodeInfo): Boolean =
            episode.number in outputNumbers

        override suspend fun resolvedAheadCount(): Int = stagedDepth
    }
}
