package com.pahebatcher.app.ui.details

import com.pahebatcher.app.data.remote.HttpClient
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.FakeHttpClient
import com.pahebatcher.app.data.repository.FakeScanCacheDao
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Option C audio selection (SPEC §22 "Audio selection (per episode)",
 * DECISIONS 143 / 144) — the screen-level audio context, the one-row-per-number
 * list, the explicit per-episode overrides and their lifetimes, all in
 * [AnimeDetailsViewModel] plus the shared pure derivations it uses
 * ([initialAudioContext] / [effectiveAudio] / [visibleEpisodeRows]).
 *
 * Covered here (the mandated state/model + batch audio matrix):
 *
 * 1–3.   initial context (jpn-if-any-else-first-deterministic) and OPEN-set
 *        audio values — no hard-coded pair, no invented context, no
 *        "original" label anywhere in state;
 * 4–5.   the rendered rows are ONE row per episode NUMBER, always carrying
 *        that number's requested audio — the listing's own row when the
 *        listing has it, otherwise the number's play page carrying the
 *        request as intent (DECISIONS 144.4/144.6). No number ever hides
 *        because release-listing metadata lacks an audio, and no variant of
 *        another audio becomes a second top-level row;
 * 6–10.  a selector change is ONE atomic transition: overrides cleared and
 *        EVERY selected number KEPT, with no deselection and no notice
 *        (DECISIONS 144.6 supersedes 143.2's deselect-and-report);
 * 11–16. overrides: effective audio = `override ?: context`, driving BOTH
 *        single resolution and the batch mapping, reset only by choosing the
 *        context audio, surviving batch-mode transitions and Clear;
 * 17–18. a new load resets the whole audio state atomically — no stale
 *        override can leak into the next title's execution;
 * 19–24. bulk actions use the current context and never create overrides; the
 *        execution mapping is derived at batch start from numbers + context +
 *        overrides, and the exact requested audio reaches the resolver;
 * 25.    a requested audio the LISTING does not offer still reaches the
 *        resolver with the requested audio on the number's own play page —
 *        availability is decided there, never by release-listing metadata
 *        (DECISIONS 144.4). A play page that genuinely lacks it fails
 *        explicitly, without substitution, in
 *        `AnimeDetailsAudioOptionDiscoveryTest`.
 *
 * Everything runs through the SAME production chain the screen uses (ViewModel
 * → EpisodeRepository → parser → resolver → queue) with test-only doubles
 * (AGENTS §5 — mocks live in tests only).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailsAudioSelectionTest {

    // -----------------------------------------------------------------------
    // Fixtures — every shape is a real scan JSON served through FakeHttpClient
    // -----------------------------------------------------------------------

    /** ep1 SUB+DUB, ep2 SUB only — the canonical dual-audio listing. */
    private fun dualAudioPage(): String = """
        {"data": [
            {"episode": 1, "session": "ep1-jpn", "title": "Beginnings", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 1, "session": "ep1-eng", "title": "Beginnings", "fansub": "DubsPlease", "audio": "eng"},
            {"episode": 2, "session": "ep2-jpn", "title": "Next Step", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Dual Audio"}}
    """.trimIndent()

    /** ep1 SUB+DUB, ep2/ep3 SUB only — jpn→eng DROPS two numbers at once. */
    private fun oneDubPage(): String = """
        {"data": [
            {"episode": 1, "session": "o1-jpn", "title": "E1", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 1, "session": "o1-eng", "title": "E1", "fansub": "DubsPlease", "audio": "eng"},
            {"episode": 2, "session": "o2-jpn", "title": "E2", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 3, "session": "o3-jpn", "title": "E3", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "One Dub"}}
    """.trimIndent()

    /** ep1/ep2 SUB+DUB, ep3 SUB only — its eng row is a CARRIER (DECISIONS 144.4). */
    private fun wideDubPage(): String = """
        {"data": [
            {"episode": 1, "session": "w1-jpn", "title": "E1", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 1, "session": "w1-eng", "title": "E1", "fansub": "DubsPlease", "audio": "eng"},
            {"episode": 2, "session": "w2-jpn", "title": "E2", "fansub": "SubsPlease", "audio": "jpn"},
            {"episode": 2, "session": "w2-eng", "title": "E2", "fansub": "DubsPlease", "audio": "eng"},
            {"episode": 3, "session": "w3-jpn", "title": "E3", "fansub": "SubsPlease", "audio": "jpn"}
        ], "last_page": 1, "anime": {"title": "Wide Dub"}}
    """.trimIndent()

    /**
     * No `jpn`, no `eng` anywhere: three open-set values, one per number, in
     * deterministic scan order (ascending episode number) — proves nothing is
     * hard-coded to the SUB/DUB pair and the initial fallback is the first
     * value the listing exposes.
     */
    private fun openSetPage(): String = """
        {"data": [
            {"episode": 1, "session": "k1-hin", "title": "E1", "fansub": "GroupA", "audio": "hin"},
            {"episode": 2, "session": "k2-ger", "title": "E2", "fansub": "GroupB", "audio": "ger"},
            {"episode": 3, "session": "k3-spa", "title": "E3", "fansub": "GroupC", "audio": "spa"}
        ], "last_page": 1, "anime": {"title": "Open Set"}}
    """.trimIndent()

    // -----------------------------------------------------------------------
    // Harness — the production chain, test-only doubles at its edges
    // -----------------------------------------------------------------------

    private fun clientFor(vararg pages: Pair<String, String>): FakeHttpClient =
        FakeHttpClient().apply { pages.forEach { (session, page) -> stubJson("id=$session", page) } }

    private fun newViewModel(
        client: HttpClient,
        resolver: StreamResolver = RecordingResolver(),
        queue: EpisodeDownloadQueue = RecordingQueue(),
    ): AnimeDetailsViewModel {
        val episodeRepository = EpisodeRepository(
            animeRepository = AnimeRepository(client),
            scanCacheRepository = ScanCacheRepository(FakeScanCacheDao()),
        )
        return AnimeDetailsViewModel(
            episodeRepository = episodeRepository,
            streamResolver = resolver,
            downloadQueue = queue,
            configProvider = { AppContext.defaults() },
        )
    }

    private fun clientForPage(page: String): FakeHttpClient =
        FakeHttpClient().apply { stubJson("m=release", page) }

    /** Load one fixture scan so every test starts from a real listing. */
    private suspend fun kotlinx.coroutines.test.TestScope.loaded(
        page: String,
        session: String = "sess-audio",
        client: HttpClient = clientForPage(page),
        resolver: StreamResolver = RecordingResolver(),
        queue: RecordingQueue = RecordingQueue(),
    ): AnimeDetailsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = newViewModel(client, resolver, queue)
        vm.load(session)
        testScheduler.advanceUntilIdle()
        assertTrue(
            "the fixture scan really produced episodes",
            vm.uiState.value.episodes.isNotEmpty(),
        )
        return vm
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // 1–3: initial context + open-set audio values
    // -----------------------------------------------------------------------

    @Test
    fun `the initial context is jpn when the listing exposes it`() = runTest {
        val vm = loaded(dualAudioPage())

        assertEquals(
            "jpn-if-any is the deterministic starting context (SPEC §22)",
            "jpn",
            vm.uiState.value.selectedAudio,
        )
        val rows = visibleEpisodeRows(vm.uiState.value)
        assertEquals("both numbers are available in jpn", setOf(1f, 2f), rows.map { it.number }.toSet())
        assertTrue("…and every rendered row really is the jpn variant", rows.all { it.audio == "jpn" })
    }

    @Test
    fun `the initial context falls back to the first audio in scan order when jpn is absent`() = runTest {
        val vm = loaded(openSetPage())

        assertEquals(
            "no jpn exists → the first value in the repository's scan order",
            "hin",
            vm.uiState.value.selectedAudio,
        )
        assertEquals(
            "…which is exactly the listing's first-exposed open-set order",
            listOf("hin", "ger", "spa"),
            availableAudios(vm.uiState.value.episodes),
        )
    }

    @Test
    fun `audio values are open set — any listing value becomes a context, an unknown one is refused`() = runTest {
        val vm = loaded(openSetPage())

        assertTrue(
            "the fixture really contains no SUB/DUB pair at all",
            vm.uiState.value.episodes.none { it.audio == "jpn" || it.audio == "eng" },
        )
        vm.setSelectedAudio("ger")
        assertEquals("an arbitrary listing value is selectable", "ger", vm.uiState.value.selectedAudio)
        val rows = visibleEpisodeRows(vm.uiState.value)
        assertEquals(
            "one row per NUMBER — none hides because only number 2 lists ger",
            setOf(1f, 2f, 3f),
            rows.map { it.number }.toSet(),
        )
        assertEquals("ger", rows.first { it.number == 2f }.audio)
        assertTrue(
            "…and the numbers without a ger listing row still show the REQUESTED audio",
            rows.filter { it.number != 2f }.all { it.audio == "ger" },
        )

        // A context the option set never exposed is refused — the screen can
        // only offer real values, and the ViewModel never invents one either.
        vm.setSelectedAudio("eng")
        assertEquals("a value the option set lacks is never adopted", "ger", vm.uiState.value.selectedAudio)
    }

    // -----------------------------------------------------------------------
    // 4–5: one row per NUMBER, always carrying the requested audio
    // -----------------------------------------------------------------------

    @Test
    fun `the rendered list is one row per episode number carrying the requested audio`() = runTest {
        val vm = loaded(dualAudioPage())
        assertEquals("3 variants exist in the scan", 3, vm.uiState.value.episodes.size)

        val inJpn = visibleEpisodeRows(vm.uiState.value)
        assertEquals(
            "jpn context: three variants collapse into two NUMBER rows",
            2,
            inJpn.size,
        )
        assertEquals(setOf(1f, 2f), inJpn.map { it.number }.toSet())
        assertTrue("…each carrying the jpn row the listing already has", inJpn.all { it.audio == "jpn" })

        vm.setSelectedAudio("eng")
        val inEng = visibleEpisodeRows(vm.uiState.value)
        assertEquals(
            "eng context: BOTH numbers stay visible (DECISIONS 144.6)",
            setOf(1f, 2f),
            inEng.map { it.number }.toSet(),
        )
        assertEquals(
            "number 1 has a listing eng row and uses it",
            "ep1-eng",
            inEng.first { it.number == 1f }.session,
        )
        assertEquals(
            "number 2 has no eng listing row: its OWN play page carries the eng request",
            "ep2-jpn",
            inEng.first { it.number == 2f }.session,
        )
        assertTrue("…and every row shows the requested audio", inEng.all { it.audio == "eng" })
    }

    @Test
    fun `a number whose listing lacks the current audio is still rendered with that audio`() = runTest {
        val vm = loaded(dualAudioPage())
        vm.setSelectedAudio("eng")

        val rows = visibleEpisodeRows(vm.uiState.value)
        assertEquals(
            "episode 2 exists in the scan — release-listing audio never hides it (DECISIONS 144)",
            setOf(1f, 2f),
            rows.map { it.number }.toSet(),
        )
        val ep2 = rows.first { it.number == 2f }
        assertEquals("eng", ep2.audio)
        assertEquals(
            "…shown as its OWN play page asking for eng, never as the jpn variant",
            "ep2-jpn",
            ep2.session,
        )
    }

    // -----------------------------------------------------------------------
    // 6–10: the selector change is ONE coherent transition
    // -----------------------------------------------------------------------

    @Test
    fun `changing the selector clears every explicit override`() = runTest {
        val vm = loaded(dualAudioPage())
        vm.setEpisodeAudio(1f, "eng")
        assertEquals(mapOf(1f to "eng"), vm.uiState.value.explicitAudioOverrides)

        vm.setSelectedAudio("eng")

        assertEquals(
            "a context change drops ALL overrides (143.5(a))",
            emptyMap<Float, String>(),
            vm.uiState.value.explicitAudioOverrides,
        )
        assertEquals("…and adopts the new context in the same write", "eng", vm.uiState.value.selectedAudio)
    }

    @Test
    fun `a selector change keeps EVERY selected number and clears all overrides`() = runTest {
        val vm = loaded(oneDubPage())
        vm.setBatchMode(true)
        vm.setEpisodeAudio(2f, "eng")
        vm.selectAllEpisodes()
        assertEquals(setOf(1f, 2f, 3f), vm.uiState.value.selectedNumbers)

        vm.setSelectedAudio("eng")

        assertEquals(
            "DECISIONS 144.6: nothing is deselected — availability is decided at resolve",
            setOf(1f, 2f, 3f),
            vm.uiState.value.selectedNumbers,
        )
        assertEquals(
            "…a context change still drops ALL overrides (143.5(a), unchanged)",
            emptyMap<Float, String>(),
            vm.uiState.value.explicitAudioOverrides,
        )
        assertEquals("…and adopts the new context in the same write", "eng", vm.uiState.value.selectedAudio)
    }

    @Test
    fun `the numbers kept across a context change still execute with that context's audio`() = runTest {
        val resolver = RecordingResolver()
        val queue = RecordingQueue()
        val vm = loaded(oneDubPage(), resolver = resolver, queue = queue)
        vm.setBatchMode(true)
        vm.selectAllEpisodes()
        vm.setSelectedAudio("eng")

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "episodes 2 and 3 were never dropped by the context change",
            3,
            queue.submissions.size,
        )
        val byNumber = queue.submissions.associateBy { it.episode.number }
        assertTrue(byNumber.getValue(1f).episode.playUrl.contains("o1-eng"))
        assertTrue(
            "…and 2/3 ask their OWN play page for eng — no listing-only gate",
            byNumber.getValue(2f).episode.playUrl.contains("o2-jpn"),
        )
        assertTrue(byNumber.getValue(3f).episode.playUrl.contains("o3-jpn"))
        assertTrue(
            "the resolver received exactly the requested audio on every page",
            resolver.calls.isNotEmpty() && resolver.calls.all { it.audio == "eng" },
        )
        assertNull("no batch-level error", vm.uiState.value.batchError)
    }

    @Test
    fun `every context change in a row keeps the selection intact`() = runTest {
        val vm = loaded(oneDubPage())
        vm.setBatchMode(true)
        vm.selectAllEpisodes()

        vm.setSelectedAudio("eng")
        assertEquals(setOf(1f, 2f, 3f), vm.uiState.value.selectedNumbers)
        vm.setSelectedAudio("jpn")
        assertEquals("switching back changes nothing either", setOf(1f, 2f, 3f), vm.uiState.value.selectedNumbers)
        vm.setSelectedAudio("eng")
        assertEquals("and again", setOf(1f, 2f, 3f), vm.uiState.value.selectedNumbers)
    }

    @Test
    fun `a single selected number survives a context change even when its listing row is another audio`() = runTest {
        val vm = loaded(oneDubPage())
        vm.setBatchMode(true)
        vm.toggleBatchEpisode(vm.uiState.value.episodes.first { it.number == 2f })
        assertEquals(setOf(2f), vm.uiState.value.selectedNumbers)

        vm.setSelectedAudio("eng")

        assertEquals(
            "episode 2 has no eng listing row — it is kept, not deselected",
            setOf(2f),
            vm.uiState.value.selectedNumbers,
        )
        val rows = visibleEpisodeRows(vm.uiState.value)
        assertTrue(
            "one row per NUMBER — the kept number is never hidden",
            rows.map { it.number }.containsAll(setOf(1f, 2f, 3f)),
        )
        assertEquals(
            "…and its row renders the REQUESTED audio",
            "eng",
            rows.first { it.number == 2f }.audio,
        )
    }

    @Test
    fun `a context change never resurrects a selection the user cleared`() = runTest {
        val vm = loaded(oneDubPage())
        vm.setBatchMode(true)
        vm.selectAllEpisodes()
        vm.setSelectedAudio("eng")
        assertEquals(setOf(1f, 2f, 3f), vm.uiState.value.selectedNumbers)

        vm.clearBatchSelection()
        assertEquals("Clear still drops the selection (12C)", emptySet<Float>(), vm.uiState.value.selectedNumbers)

        vm.setSelectedAudio("jpn")
        assertEquals(
            "a later context change does NOT restore it",
            emptySet<Float>(),
            vm.uiState.value.selectedNumbers,
        )
    }

    // -----------------------------------------------------------------------
    // 11–16: overrides — effective audio and lifetime
    // -----------------------------------------------------------------------

    @Test
    fun `an explicit override changes the number's effective audio`() = runTest {
        val vm = loaded(dualAudioPage())
        assertEquals("no override → the context decides", "jpn", effectiveAudio(vm.uiState.value, 1f))

        vm.setEpisodeAudio(1f, "eng")

        assertEquals("override wins", "eng", effectiveAudio(vm.uiState.value, 1f))
        assertEquals(
            "an override is scoped to ITS number only",
            "jpn",
            effectiveAudio(vm.uiState.value, 2f),
        )
    }

    @Test
    fun `an override drives single-episode resolution to the chosen variant`() = runTest {
        val resolver = RecordingResolver()
        val vm = loaded(dualAudioPage(), resolver = resolver)

        // Baseline: no override → the context's own variant resolves.
        vm.selectEpisode(vm.uiState.value.episodes.first { it.number == 1f && it.audio == "jpn" })
        testScheduler.advanceUntilIdle()
        assertEquals("jpn", resolver.calls.single().audio)

        // With an override the SAME event resolves the overridden variant —
        // the VM derives effective audio itself; it does not trust whichever
        // row instance was handed in.
        vm.setEpisodeAudio(1f, "eng")
        vm.selectEpisode(vm.uiState.value.episodes.first { it.number == 1f && it.audio == "jpn" })
        testScheduler.advanceUntilIdle()

        assertEquals("second resolution", 2, resolver.calls.size)
        assertEquals(
            "the requested audio is the override",
            "eng",
            resolver.calls.last().audio,
        )
        assertTrue(
            "…and the play page asked for is the eng variant's own page",
            resolver.calls.last().playUrl.contains("ep1-eng"),
        )
        assertEquals(
            "the stored selection IS the effective variant",
            "eng",
            vm.uiState.value.selectedEpisode?.audio,
        )
        assertNull("the resolution succeeded", vm.uiState.value.resolutionError)
    }

    @Test
    fun `an override is part of the batch execution mapping`() = runTest {
        val queue = RecordingQueue()
        val vm = loaded(dualAudioPage(), queue = queue)
        vm.setBatchMode(true)
        vm.setEpisodeAudio(1f, "eng")
        vm.selectAllEpisodes()
        assertEquals(setOf(1f, 2f), vm.uiState.value.selectedNumbers)

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val byNumber = queue.submissions.associateBy { it.episode.number }
        assertEquals(2, queue.submissions.size)
        assertTrue(
            "episode 1 runs its override's play page",
            byNumber.getValue(1f).episode.playUrl.contains("ep1-eng"),
        )
        assertEquals("eng", byNumber.getValue(1f).episode.audio)
        assertEquals(
            "episode 2 has no override — the context runs",
            "jpn",
            byNumber.getValue(2f).episode.audio,
        )
    }

    @Test
    fun `choosing the current context audio removes that override`() = runTest {
        val vm = loaded(dualAudioPage())
        vm.setEpisodeAudio(1f, "eng")
        assertEquals(mapOf(1f to "eng"), vm.uiState.value.explicitAudioOverrides)

        vm.setEpisodeAudio(1f, "jpn") // the context value = an explicit reset

        assertEquals(
            "selecting the context audio clears exactly that override (143.5(b))",
            emptyMap<Float, String>(),
            vm.uiState.value.explicitAudioOverrides,
        )
        assertEquals("jpn", effectiveAudio(vm.uiState.value, 1f))
    }

    @Test
    fun `overrides survive leaving and re-entering batch mode`() = runTest {
        val vm = loaded(dualAudioPage())
        vm.setBatchMode(true)
        vm.setEpisodeAudio(1f, "eng")

        vm.setBatchMode(false)
        assertEquals(
            "the selection is discarded on exit (12C) …",
            emptySet<Float>(),
            vm.uiState.value.selectedNumbers,
        )
        assertEquals(
            "…but explicit audio overrides are NOT (143.5)",
            mapOf(1f to "eng"),
            vm.uiState.value.explicitAudioOverrides,
        )

        vm.setBatchMode(true)
        assertEquals(
            "re-entry keeps them too — overrides are row state, not mode state",
            mapOf(1f to "eng"),
            vm.uiState.value.explicitAudioOverrides,
        )
    }

    @Test
    fun `clearBatchSelection does not erase valid audio overrides`() = runTest {
        val vm = loaded(dualAudioPage())
        vm.setBatchMode(true)
        vm.selectAllEpisodes()
        vm.setEpisodeAudio(1f, "eng")

        vm.clearBatchSelection()

        assertEquals("the selection is gone", emptySet<Float>(), vm.uiState.value.selectedNumbers)
        assertEquals(
            "…the override is still valid row state (143.5)",
            mapOf(1f to "eng"),
            vm.uiState.value.explicitAudioOverrides,
        )
        assertEquals(
            "…and still decides that number's effective audio",
            "eng",
            effectiveAudio(vm.uiState.value, 1f),
        )
    }

    // -----------------------------------------------------------------------
    // 17–18: load/session replacement resets the audio state atomically
    // -----------------------------------------------------------------------

    @Test
    fun `loading a new session atomically resets the whole audio state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = clientFor("sess-alpha" to dualAudioPage(), "sess-beta" to openSetPage())
        val vm = newViewModel(client)
        vm.load("sess-alpha")
        testScheduler.advanceUntilIdle()

        vm.setBatchMode(true)
        vm.setEpisodeAudio(1f, "eng")
        vm.selectAllEpisodes()
        assertEquals(mapOf(1f to "eng"), vm.uiState.value.explicitAudioOverrides)

        vm.load("sess-beta")
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the new listing really loaded", 3, state.episodes.size)
        assertEquals(
            "the context is recomputed for the NEW listing",
            "hin",
            state.selectedAudio,
        )
        assertEquals(
            "no override leaks from the previous title",
            emptyMap<Float, String>(),
            state.explicitAudioOverrides,
        )
        assertEquals("no selection leaks either", emptySet<Float>(), state.selectedNumbers)
        assertEquals(
            "the option set is rebuilt for the NEW title (DECISIONS 144)",
            availableAudios(state.episodes),
            state.audioOptions,
        )
        assertFalse("batch mode does not leak across titles", state.batchMode)
    }

    @Test
    fun `no stale override from the previous title survives into execution`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val resolver = RecordingResolver()
        val queue = RecordingQueue()
        val client = clientFor("sess-alpha" to dualAudioPage(), "sess-beta" to openSetPage())
        val vm = newViewModel(client, resolver, queue)
        vm.load("sess-alpha")
        testScheduler.advanceUntilIdle()
        vm.setEpisodeAudio(1f, "eng") // the override the NEXT title must never see

        vm.load("sess-beta")
        testScheduler.advanceUntilIdle()
        vm.setBatchMode(true)
        vm.selectAllEpisodes()
        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the new title's context selects ITS OWN numbers — every one of them",
            setOf(1f, 2f, 3f),
            vm.uiState.value.selectedNumbers,
        )
        assertEquals(3, queue.submissions.size)
        assertTrue(
            "every requested audio is the new title's context (never the stale 'eng')",
            queue.submissions.all { it.episode.audio == "hin" },
        )
        assertTrue(
            "…number 1 on its own play page…",
            queue.submissions.any { it.episode.playUrl.contains("k1-hin") },
        )
        assertTrue(
            "…and 2/3 carry hin onto their own play pages (DECISIONS 144.4)",
            queue.submissions.any { it.episode.playUrl.contains("k2-ger") } &&
                queue.submissions.any { it.episode.playUrl.contains("k3-spa") },
        )
        assertTrue(
            "the stale eng override never reached the pipeline",
            resolver.calls.none { it.audio == "eng" },
        )
    }

    // -----------------------------------------------------------------------
    // 19–24: bulk actions use the context; the mapping is derived at start
    // -----------------------------------------------------------------------

    @Test
    fun `select-all selects every number and assigns the current context to each`() = runTest {
        val vm = loaded(wideDubPage())
        vm.setSelectedAudio("eng")
        vm.setBatchMode(true)

        vm.selectAllEpisodes()

        assertEquals(
            "every NUMBER is selectable — no listing-only gate (DECISIONS 144.6)",
            setOf(1f, 2f, 3f),
            vm.uiState.value.selectedNumbers,
        )
        assertEquals("the context itself is untouched by the bulk action", "eng", vm.uiState.value.selectedAudio)
        assertEquals(
            "…and no override was invented for any number",
            emptyMap<Float, String>(),
            vm.uiState.value.explicitAudioOverrides,
        )
    }

    @Test
    fun `a range expression is evaluated against every catalogue number`() = runTest {
        val vm = loaded(wideDubPage())
        vm.setSelectedAudio("eng")
        vm.setBatchMode(true)

        vm.selectEpisodeRange("9-9")
        assertEquals(
            "no such episode anywhere — an honest no-match, not a substitution",
            emptySet<Float>(),
            vm.uiState.value.selectedNumbers,
        )
        assertEquals(
            AnimeDetailsViewModel.NO_EPISODES_MATCHED,
            vm.uiState.value.batchError,
        )

        vm.selectEpisodeRange("3-3")
        assertEquals(
            "episode 3 exists — its eng row is a carrier, but the NUMBER still matches (144.6)",
            setOf(3f),
            vm.uiState.value.selectedNumbers,
        )
        assertNull("a matching range is not an error", vm.uiState.value.batchError)

        vm.selectEpisodeRange("1-2")
        assertEquals(setOf(1f, 2f), vm.uiState.value.selectedNumbers)
        assertNull("a matching range is not an error", vm.uiState.value.batchError)
        assertEquals(
            "still no overrides from a bulk action",
            emptyMap<Float, String>(),
            vm.uiState.value.explicitAudioOverrides,
        )
    }

    @Test
    fun `latest N counts every catalogue number in the current context`() = runTest {
        val queue = RecordingQueue()
        val vm = loaded(wideDubPage(), queue = queue)
        vm.setSelectedAudio("eng")
        vm.setBatchMode(true)

        vm.selectLatestEpisodes(1)

        assertEquals(
            "the newest NUMBER is 3 — release-listing audio never shrinks the range (144.6)",
            setOf(3f),
            vm.uiState.value.selectedNumbers,
        )

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(1, queue.submissions.size)
        assertEquals(
            "…and it runs the current context's audio",
            "eng",
            queue.submissions.single().episode.audio,
        )
        assertTrue(
            "on its OWN play page — the number's real row, carrying the request",
            queue.submissions.single().episode.playUrl.contains("w3-jpn"),
        )
    }

    @Test
    fun `the execution mapping is derived at batch start from context and overrides`() = runTest {
        val queue = RecordingQueue()
        val vm = loaded(wideDubPage(), queue = queue)
        vm.setSelectedAudio("eng")
        vm.setBatchMode(true)
        vm.setEpisodeAudio(1f, "jpn") // explicit override wins over the context
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        val byNumber = queue.submissions.associateBy { it.episode.number }
        assertEquals(3, queue.submissions.size)
        assertEquals(
            "override wins for episode 1",
            "jpn",
            byNumber.getValue(1f).episode.audio,
        )
        assertTrue(byNumber.getValue(1f).episode.playUrl.contains("w1-jpn"))
        assertEquals(
            "the context runs for episode 2",
            "eng",
            byNumber.getValue(2f).episode.audio,
        )
        assertTrue(byNumber.getValue(2f).episode.playUrl.contains("w2-eng"))
        assertEquals(
            "episode 3 has no eng listing row — the context still runs on its OWN page (144.4)",
            "eng",
            byNumber.getValue(3f).episode.audio,
        )
        assertTrue(byNumber.getValue(3f).episode.playUrl.contains("w3-jpn"))
        assertEquals(
            "the derived inputs are untouched by running",
            mapOf(1f to "jpn"),
            vm.uiState.value.explicitAudioOverrides,
        )
        assertEquals("eng", vm.uiState.value.selectedAudio)
    }

    @Test
    fun `the exact requested audio reaches every layer of the batch pipeline`() = runTest {
        val resolver = RecordingResolver()
        val queue = RecordingQueue()
        val vm = loaded(dualAudioPage(), resolver = resolver, queue = queue)
        vm.setBatchMode(true)
        vm.setEpisodeAudio(1f, "eng")
        vm.selectAllEpisodes()

        vm.startBatchDownload()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "the resolver received exactly the derived audios, in number order",
            listOf("eng", "jpn"),
            resolver.calls.map { it.audio },
        )
        assertTrue(
            "…and the eng request used the eng play page",
            resolver.calls[0].playUrl.contains("ep1-eng"),
        )
        assertEquals(
            "every queued submission carries the same audio that was resolved",
            resolver.calls.map { it.audio },
            queue.submissions.map { it.episode.audio },
        )
        assertTrue(
            "the batch finished without a batch-level error",
            vm.uiState.value.batchError == null && !vm.uiState.value.batchRunning,
        )
    }

    @Test
    fun `a single episode requesting an audio the listing lacks still reaches the resolver with it`() = runTest {
        val resolver = RecordingResolver()
        val vm = loaded(dualAudioPage(), resolver = resolver)
        // An override pointing at audio the CURRENT listing does not carry —
        // reachable from the public event surface because the control offers
        // the DISCOVERED option set, which release-listing metadata cannot
        // veto (DECISIONS 144.4).
        vm.setEpisodeAudio(1f, "kor")

        vm.selectEpisode(vm.uiState.value.episodes.first { it.number == 1f })
        testScheduler.advanceUntilIdle()

        assertNull(
            "the LISTING can no longer veto a request — it never proved the play page (144.4)",
            vm.uiState.value.resolutionError,
        )
        assertEquals("the resolver was asked exactly once", 1, resolver.calls.size)
        assertEquals(
            "…with the REQUESTED audio — never another",
            "kor",
            resolver.calls.single().audio,
        )
        assertTrue(
            "…on episode 1's OWN play page",
            resolver.calls.single().playUrl ==
                vm.uiState.value.episodes.first { it.number == 1f }.playUrl,
        )
        assertEquals(
            "a real stream came back carrying the same requested audio",
            "kor",
            vm.uiState.value.resolvedStream?.audio,
        )
        assertFalse("…and nothing is left resolving", vm.uiState.value.resolving)
        // The play page itself is the authority: a page that genuinely lacks
        // the audio still fails without substitution — covered end-to-end with
        // the REAL resolver in AnimeDetailsAudioOptionDiscoveryTest.
    }

    // -----------------------------------------------------------------------
    // Test doubles (AGENTS §5 — mocks live in tests only)
    // -----------------------------------------------------------------------

    private class RecordingResolver : StreamResolver {
        data class Call(val playUrl: String, val quality: Int, val audio: String)

        val calls = mutableListOf<Call>()

        override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
            calls += Call(playUrl = playUrl, quality = quality, audio = audio)
            return StreamInfo(
                url = "https://cdn.example.invalid/${calls.size}.m3u8",
                userAgent = "Mozilla/5.0 (test)",
                referer = playUrl,
                // The REQUESTED audio is what production stamps on the stream
                // (StreamResolverImpl does the same) — a fake that dropped it
                // could hide a substitution.
                audio = audio,
            )
        }
    }

    private class RecordingQueue : EpisodeDownloadQueue {
        val submissions = mutableListOf<ResolvedEpisodeSubmission>()

        override suspend fun queueResolvedEpisode(submission: ResolvedEpisodeSubmission): EnqueueOutcome {
            submissions += submission
            return EnqueueOutcome.Accepted
        }

        override suspend fun isEpisodeAlreadyHandled(animeSession: String, episode: EpisodeInfo): Boolean = false

        override suspend fun resolvedAheadCount(): Int = 0
    }
}
