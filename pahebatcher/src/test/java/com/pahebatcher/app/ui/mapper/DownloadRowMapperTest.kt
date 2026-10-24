package com.pahebatcher.app.ui.mapper

import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §36-1 — State-mapper table test: all 8 engine states × 5 actions (40 assertions)
 * proving the §17.1 action-availability matrix, including the
 * "UI exposes no control outside the matrix" invariant. The Pause/Resume columns
 * are the final SPEC §20/§25 gap: `PAUSED` is a real engine state and its row
 * offers exactly Resume + Cancel.
 *
 * §36-2 — Speed/ETA/totalSegments contract (F-01), extended by SPEC §25: zeros
 * → null (no `0 B/s` / `0:00` producible), nonzero renders while the row is
 * DOWNLOADING (a persisted rate on any other state is stale and must not
 * surface), downloadedBytes flows through verbatim, persisted totalSegments == 0
 * → no percentage, completedSegments flows through, percentage is a pure
 * function of the row.
 */
class DownloadRowMapperTest {

    // -----------------------------------------------------------------------
    // Fixtures — one row per engine state
    // -----------------------------------------------------------------------

    private fun row(
        state: String,
        speed: Long = 0,
        etaSeconds: Long = 0,
        totalSegments: Int = 0,
        completedSegments: Int = 0,
        retryCount: Int = 0,
        errorMessage: String? = null,
        downloadedBytes: Long = 0,
    ) = EpisodeDownloadEntity(
        episodeId = "ep-1",
        sessionId = "sess-1",
        episodeNumber = 3.0f,
        episodeSession = "ep-sess-1",
        title = "Mapper Episode",
        fansub = "Fansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        totalSegments = totalSegments,
        completedSegments = completedSegments,
        downloadedBytes = downloadedBytes,
        state = state,
        speed = speed,
        etaSeconds = etaSeconds,
        retryCount = retryCount,
        errorMessage = errorMessage,
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private val allStates = listOf(
        EpisodeDownloadEntity.STATE_QUEUED,
        EpisodeDownloadEntity.STATE_RESOLVING,
        EpisodeDownloadEntity.STATE_DOWNLOADING,
        EpisodeDownloadEntity.STATE_ASSEMBLING,
        EpisodeDownloadEntity.STATE_COMPLETED,
        EpisodeDownloadEntity.STATE_FAILED,
        EpisodeDownloadEntity.STATE_CANCELLED,
        EpisodeDownloadEntity.STATE_PAUSED,
    )

    // -----------------------------------------------------------------------
    // §36-1 — 8 states × 5 actions = 40 assertions
    // -----------------------------------------------------------------------

    @Test
    fun actionMatrix_queued_pauseAndCancelOnly() {
        val actions = DownloadRowMapper.actionsFor(EpisodeDownloadEntity.STATE_QUEUED)
        assertTrue("QUEUED: Cancel must be available", actions.cancel)          // 1
        assertFalse("QUEUED: Retry must NOT be available", actions.retry)       // 2
        assertFalse("QUEUED: Play must NOT be available", actions.play)         // 3
        assertTrue("QUEUED: Pause must be available (SPEC §20)", actions.pause) // 4
        assertFalse("QUEUED: Resume must NOT be available (it is already queued)", actions.resume) // 5
    }

    @Test
    fun actionMatrix_resolving_pauseAndCancelOnly() {
        val actions = DownloadRowMapper.actionsFor(EpisodeDownloadEntity.STATE_RESOLVING)
        assertTrue("RESOLVING: Cancel must be available", actions.cancel)       // 6
        assertFalse("RESOLVING: Retry must NOT be available", actions.retry)    // 7
        assertFalse("RESOLVING: Play must NOT be available", actions.play)      // 8
        assertTrue("RESOLVING: Pause must be available (SPEC §20)", actions.pause) // 9
        assertFalse("RESOLVING: Resume must NOT be available", actions.resume)  // 10
    }

    @Test
    fun actionMatrix_downloading_pauseAndCancelOnly() {
        val actions = DownloadRowMapper.actionsFor(EpisodeDownloadEntity.STATE_DOWNLOADING)
        assertTrue("DOWNLOADING: Cancel must be available", actions.cancel)     // 11
        assertFalse("DOWNLOADING: Retry must NOT be available", actions.retry)  // 12
        assertFalse("DOWNLOADING: Play must NOT be available", actions.play)    // 13
        assertTrue("DOWNLOADING: Pause must be available (SPEC §20)", actions.pause) // 14
        assertFalse("DOWNLOADING: Resume must NOT be available", actions.resume) // 15
    }

    @Test
    fun actionMatrix_assembling_cancelOnly_noPauseDuringMux() {
        val actions = DownloadRowMapper.actionsFor(EpisodeDownloadEntity.STATE_ASSEMBLING)
        assertTrue("ASSEMBLING: Cancel must be available", actions.cancel)      // 16
        assertFalse("ASSEMBLING: Retry must NOT be available", actions.retry)   // 17
        assertFalse("ASSEMBLING: Play must NOT be available", actions.play)     // 18
        assertFalse(
            "ASSEMBLING: Pause must NOT be offered — muxing cannot stop halfway " +
                "without risking a partial output",
            actions.pause,
        )                                                                       // 19
        assertFalse("ASSEMBLING: Resume must NOT be available", actions.resume) // 20
    }

    @Test
    fun actionMatrix_completed_playOnly() {
        val actions = DownloadRowMapper.actionsFor(EpisodeDownloadEntity.STATE_COMPLETED)
        assertFalse("COMPLETED: Cancel must NOT be available (would break §29 invariant)", actions.cancel) // 21
        assertFalse("COMPLETED: Retry must NOT be available (no re-download concept in v1)", actions.retry) // 22
        assertTrue("COMPLETED: Play must be available", actions.play)           // 23
        assertFalse("COMPLETED: Pause must NOT be available (terminal)", actions.pause)   // 24
        assertFalse("COMPLETED: Resume must NOT be available (terminal)", actions.resume) // 25
    }

    @Test
    fun actionMatrix_failed_retryOnly() {
        val actions = DownloadRowMapper.actionsFor(EpisodeDownloadEntity.STATE_FAILED)
        assertFalse("FAILED: Cancel must NOT be available (terminal)", actions.cancel)  // 26
        assertTrue("FAILED: Retry must be available", actions.retry)                    // 27
        assertFalse("FAILED: Play must NOT be available", actions.play)                 // 28
        assertFalse("FAILED: Pause must NOT be available (terminal)", actions.pause)     // 29
        assertFalse("FAILED: Resume must NOT be available — Retry is the entry point", actions.resume) // 30
    }

    @Test
    fun actionMatrix_cancelled_retryOnly() {
        val actions = DownloadRowMapper.actionsFor(EpisodeDownloadEntity.STATE_CANCELLED)
        assertFalse("CANCELLED: Cancel must NOT be available (terminal)", actions.cancel) // 31
        assertTrue("CANCELLED: Retry must be available", actions.retry)                   // 32
        assertFalse("CANCELLED: Play must NOT be available", actions.play)                // 33
        assertFalse("CANCELLED: Pause must NOT be available (terminal)", actions.pause)    // 34
        assertFalse("CANCELLED: Resume must NOT be available", actions.resume)            // 35
    }

    @Test
    fun actionMatrix_paused_resumeAndCancelOnly() {
        // SPEC §20/§25 — the whole point of the state: it can be resumed back
        // into work, and it can be discarded. It must NOT offer Retry (that
        // would re-resolve a row whose stream may still be valid) nor Pause
        // (pausing twice is meaningless).
        val actions = DownloadRowMapper.actionsFor(EpisodeDownloadEntity.STATE_PAUSED)
        assertTrue("PAUSED: Resume must be available (SPEC §20)", actions.resume)  // 36
        assertTrue("PAUSED: Cancel must be available (discard the pause)", actions.cancel) // 37
        assertFalse("PAUSED: Pause must NOT be available (already paused)", actions.pause) // 38
        assertFalse("PAUSED: Retry must NOT be available", actions.retry)           // 39
        assertFalse("PAUSED: Play must NOT be available", actions.play)             // 40
    }

    @Test
    fun uiExposesNoControlOutsideMatrix_invariant() {
        // Every action flag the row model carries is listed here, and each state
        // must enable EXACTLY its matrix cells — no more (a control whose
        // repository operation is invalid, §17.1) and no fewer (a SPEC §20 action
        // that silently disappeared).
        allStates.forEach { state ->
            val actions = DownloadRowMapper.actionsFor(state)
            val enabled = listOfNotNull(
                "cancel".takeIf { actions.cancel },
                "retry".takeIf { actions.retry },
                "play".takeIf { actions.play },
                "pause".takeIf { actions.pause },
                "resume".takeIf { actions.resume },
            )
            val expected = when (state) {
                EpisodeDownloadEntity.STATE_QUEUED,
                EpisodeDownloadEntity.STATE_RESOLVING,
                EpisodeDownloadEntity.STATE_DOWNLOADING,
                -> listOf("cancel", "pause")

                EpisodeDownloadEntity.STATE_ASSEMBLING -> listOf("cancel")
                EpisodeDownloadEntity.STATE_COMPLETED -> listOf("play")
                EpisodeDownloadEntity.STATE_FAILED,
                EpisodeDownloadEntity.STATE_CANCELLED,
                -> listOf("retry")

                EpisodeDownloadEntity.STATE_PAUSED -> listOf("cancel", "resume")

                else -> error("unreachable")
            }
            assertEquals("Exactly the matrix actions for $state", expected, enabled)
        }
    }

    @Test
    fun unknownEngineState_throwsInsteadOfGuessing() {
        // PAUSED is a REAL state now (it is asserted above); a state the engine
        // cannot produce must still fail loudly instead of mapping to something.
        try {
            DownloadRowMapper.actionsFor("SUSPENDED")
            throw AssertionError("Unknown state must not silently map to an action set")
        } catch (expected: IllegalArgumentException) {
            // expected — no invented state behavior (§17)
        }
        try {
            DownloadRowMapper.toUserConcept("SUSPENDED")
            throw AssertionError("Unknown state must not silently map to a concept")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // -----------------------------------------------------------------------
    // §17 concept mapping — seven user concepts, RESOLVING folds into queued
    // -----------------------------------------------------------------------

    @Test
    fun conceptMapping_sevenUserConcepts_resolvingFoldsIntoQueued() {
        assertEquals(DownloadUserConcept.QUEUED, DownloadRowMapper.toUserConcept(EpisodeDownloadEntity.STATE_QUEUED))
        assertEquals(
            "RESOLVING must fold into the queued concept (§17)",
            DownloadUserConcept.QUEUED,
            DownloadRowMapper.toUserConcept(EpisodeDownloadEntity.STATE_RESOLVING),
        )
        assertEquals(DownloadUserConcept.DOWNLOADING, DownloadRowMapper.toUserConcept(EpisodeDownloadEntity.STATE_DOWNLOADING))
        assertEquals(DownloadUserConcept.ASSEMBLING, DownloadRowMapper.toUserConcept(EpisodeDownloadEntity.STATE_ASSEMBLING))
        assertEquals(DownloadUserConcept.COMPLETED, DownloadRowMapper.toUserConcept(EpisodeDownloadEntity.STATE_COMPLETED))
        assertEquals(DownloadUserConcept.FAILED, DownloadRowMapper.toUserConcept(EpisodeDownloadEntity.STATE_FAILED))
        assertEquals(DownloadUserConcept.CANCELLED, DownloadRowMapper.toUserConcept(EpisodeDownloadEntity.STATE_CANCELLED))
        assertEquals(
            "SPEC §25 lists paused among the states the dashboard must show",
            DownloadUserConcept.PAUSED,
            DownloadRowMapper.toUserConcept(EpisodeDownloadEntity.STATE_PAUSED),
        )
        assertEquals(
            "The vocabulary is exactly seven concepts — one per engine state, " +
                "with RESOLVING folded into QUEUED",
            7,
            DownloadUserConcept.entries.size,
        )
    }

    @Test
    fun conceptMapping_resolvingCarriesPreparingLinkSubLabel_only() {
        assertEquals("preparing link", DownloadRowMapper.subLabelFor(EpisodeDownloadEntity.STATE_RESOLVING))
        allStates.filter { it != EpisodeDownloadEntity.STATE_RESOLVING }.forEach { state ->
            assertNull("Only RESOLVING carries a sub-label (state=$state)", DownloadRowMapper.subLabelFor(state))
        }
    }

    // -----------------------------------------------------------------------
    // §36-2 — speed/ETA/totalSegments contract (F-01)
    // -----------------------------------------------------------------------

    @Test
    fun speedEta_zeroOrUnavailable_mapsToNull_noZeroStringsProducible() {
        // speed = 0 / eta = 0 — no measurement has happened yet (SPEC §25 says
        // 0 means "unknown", never "measured as exactly zero")
        val zeroRow = DownloadRowMapper.map(row(state = EpisodeDownloadEntity.STATE_DOWNLOADING))
        assertNull("speed=0 must map to null (never a rendered 0 B/s)", zeroRow.speedBps)
        assertNull("eta=0 must map to null (never a rendered 0:00)", zeroRow.etaSeconds)
        // Null-handling is compile-enforced: no formatter can receive a value.
        // TEST-010: two `x == null || x > 0` lines that followed only restated
        // the assertNull checks above — strictly weaker duplicates, deleted.

        // Nonzero values flow through formatted (real metrics when they exist)
        val fastRow = DownloadRowMapper.map(
            row(state = EpisodeDownloadEntity.STATE_DOWNLOADING, speed = 1_500_000, etaSeconds = 42)
        )
        assertEquals(1_500_000L, fastRow.speedBps)
        assertEquals(42L, fastRow.etaSeconds)
    }

    @Test
    fun percentage_totalSegmentsZero_returnsNull_noFabricatedDenominator() {
        // (4) persisted totalSegments = 0 (unknown) → safely handled, no crash
        val unknown = row(state = EpisodeDownloadEntity.STATE_DOWNLOADING, completedSegments = 7, totalSegments = 0)
        assertNull(DownloadRowMapper.progressPercentOrNull(unknown))
        assertNull(DownloadRowMapper.map(unknown).progressPercent)
        assertNull("totalSegments=0 must expose a null denominator", DownloadRowMapper.map(unknown).totalSegments)
        // "0%" would falsely imply a known total at zero — it must be impossible.
        assertTrue(DownloadRowMapper.map(unknown).progressPercent == null)
    }

    @Test
    fun percentage_isPureFunctionOfPersistedRow() {
        // (5) no fabricated denominator — percentage derives only from the row
        val persisted = row(
            state = EpisodeDownloadEntity.STATE_DOWNLOADING,
            completedSegments = 25,
            totalSegments = 100,
        )
        val percentA = DownloadRowMapper.progressPercentOrNull(persisted)
        val percentB = DownloadRowMapper.progressPercentOrNull(persisted.copy())
        assertEquals("Same Room row → same percentage", percentA, percentB)
        assertEquals(25, percentA)

        // View-state tampering cannot invent a different denominator: the mapper
        // takes the entity only.
        assertEquals(25, DownloadRowMapper.map(persisted).progressPercent)
    }

    @Test
    fun percentage_completedSegmentsFlowsThroughUnchanged() {
        // (2)/(3) completedSegments is authoritative and flows through unchanged
        val persisted = row(
            state = EpisodeDownloadEntity.STATE_DOWNLOADING,
            completedSegments = 60,
            totalSegments = 200,
        )
        val ui = DownloadRowMapper.map(persisted)
        assertEquals(60, ui.completedSegments)
        assertEquals(200, ui.totalSegments)
        assertEquals(30, ui.progressPercent) // 60/200 both read back from the row
    }

    @Test
    fun map_allSevenStates_producesRowWithoutThrowing() {
        allStates.forEach { state ->
            val ui = DownloadRowMapper.map(row(state = state))
            assertEquals(state, DownloadRowMapper.toUserConcept(state), ui.concept)
        }
    }

    @Test
    fun errorMessage_exposedOnlyForFailedRows() {
        val failed = DownloadRowMapper.map(
            row(state = EpisodeDownloadEntity.STATE_FAILED, errorMessage = "HTTP 404")
        )
        assertEquals("HTTP 404", failed.errorMessage)
        val cancelled = DownloadRowMapper.map(
            row(state = EpisodeDownloadEntity.STATE_CANCELLED, errorMessage = "stale message")
        )
        assertNull("errorMessage renders only on FAILED rows (§16 row content)", cancelled.errorMessage)
    }

    @Test
    fun retryCount_exposedVerbatim() {
        val ui = DownloadRowMapper.map(row(state = EpisodeDownloadEntity.STATE_FAILED, retryCount = 2))
        assertEquals(2, ui.retryCount)
    }

    // -----------------------------------------------------------------------
    // SPEC §25 — downloaded bytes, and the stale-rate guard
    // -----------------------------------------------------------------------

    @Test
    fun downloadedBytes_exposedVerbatimFromThePersistedRow() {
        val active = DownloadRowMapper.map(
            row(
                state = EpisodeDownloadEntity.STATE_DOWNLOADING,
                completedSegments = 12,
                totalSegments = 40,
                downloadedBytes = 48_234_496L,
            ),
        )
        assertEquals("the byte total the engine really wrote flows through", 48_234_496L, active.downloadedBytes)

        // A row that has written nothing reports 0 — the SCREEN renders nothing
        // for it, but the model itself never invents or nulls real state.
        assertEquals(0L, DownloadRowMapper.map(row(state = EpisodeDownloadEntity.STATE_QUEUED)).downloadedBytes)
    }

    @Test
    fun downloadedBytes_renderedInTheFormatsTheScreenUses() {
        // The row renders through StringUtils.fmtBytes, so the mapper must hand
        // it a plain non-negative Long — no formatting lives in the model.
        val persisted = row(state = EpisodeDownloadEntity.STATE_COMPLETED, downloadedBytes = 1_048_576L)
        assertEquals(1_048_576L, DownloadRowMapper.map(persisted).downloadedBytes)
        assertEquals("1.0 MB", com.pahebatcher.app.domain.util.StringUtils.fmtBytes(1_048_576L))
    }

    @Test
    fun rate_isDisplayedOnlyWhileTheRowIsReallyDownloading() {
        // A rate persisted by an interrupted run describes no current transfer —
        // guard 2 of 2 (guard 1 is the claim-time reset in the Worker).
        val staleRateStates = listOf(
            EpisodeDownloadEntity.STATE_QUEUED,
            EpisodeDownloadEntity.STATE_RESOLVING,
            EpisodeDownloadEntity.STATE_ASSEMBLING,
            EpisodeDownloadEntity.STATE_COMPLETED,
            EpisodeDownloadEntity.STATE_FAILED,
            EpisodeDownloadEntity.STATE_CANCELLED,
        )
        staleRateStates.forEach { state ->
            val ui = DownloadRowMapper.map(
                row(state = state, speed = 1_500_000, etaSeconds = 42),
            )
            assertNull("stale speed must not surface for $state", ui.speedBps)
            assertNull("stale ETA must not surface for $state", ui.etaSeconds)
        }

        // …while a real active download still shows both.
        val active = DownloadRowMapper.map(
            row(state = EpisodeDownloadEntity.STATE_DOWNLOADING, speed = 1_500_000, etaSeconds = 42),
        )
        assertEquals(1_500_000L, active.speedBps)
        assertEquals(42L, active.etaSeconds)
    }

    // -----------------------------------------------------------------------
    // Slice B (DECISIONS 146) — the persisted audio label on a Downloads row
    // -----------------------------------------------------------------------

    @Test
    fun audioLabelFor_namesTheTwoKnownLanguages_and_echoes_every_other_code() {
        assertEquals("Japanese", DownloadRowMapper.audioLabelFor("jpn"))
        assertEquals("English", DownloadRowMapper.audioLabelFor("eng"))
        // SPEC §22 is an OPEN set: an unrecognised code is echoed as itself,
        // never mapped onto a guessed language and never collapsed to SUB/DUB.
        assertEquals("KOR", DownloadRowMapper.audioLabelFor("kor"))
        assertEquals("SPA", DownloadRowMapper.audioLabelFor("spa"))
        assertEquals("HIN", DownloadRowMapper.audioLabelFor("hin"))
    }

    @Test
    fun audioLabelFor_normalises_case_and_padding_before_deciding() {
        assertEquals("Japanese", DownloadRowMapper.audioLabelFor("JPN"))
        assertEquals("Japanese", DownloadRowMapper.audioLabelFor("  jpn  "))
        assertEquals("English", DownloadRowMapper.audioLabelFor("Eng"))
        assertEquals("KOR", DownloadRowMapper.audioLabelFor(" kor "))
    }

    @Test
    fun audioLabelFor_never_invents_a_label_for_missing_or_blank_audio() {
        assertNull(DownloadRowMapper.audioLabelFor(null))
        assertNull(DownloadRowMapper.audioLabelFor(""))
        assertNull(DownloadRowMapper.audioLabelFor("   "))
    }

    @Test
    fun aRowShowsTheAudioItWasDownloadedWith_ratherThanAnyScreenSelection() {
        val completed = EpisodeDownloadEntity.STATE_COMPLETED
        val jpn = DownloadRowMapper.map(row(state = completed).copy(audio = "jpn"))
        val eng = DownloadRowMapper.map(row(state = completed).copy(audio = "eng"))
        val kor = DownloadRowMapper.map(row(state = completed).copy(audio = "kor"))
        val blank = DownloadRowMapper.map(row(state = completed).copy(audio = ""))

        // The SUB and DUB downloads of one episode are two distinct rows and
        // must stay tellable apart, from each row's own persisted value.
        assertEquals("jpn", jpn.audio)
        assertEquals("Japanese", jpn.audioLabel)
        assertEquals("eng", eng.audio)
        assertEquals("English", eng.audioLabel)
        assertEquals("kor", kor.audio)
        assertEquals("KOR", kor.audioLabel)
        // A legacy row with no audio shows no label at all — never a default.
        assertEquals("", blank.audio)
        assertNull(blank.audioLabel)
    }

    // -----------------------------------------------------------------------
    // A2.1 Decision 9 (UI-010): per-row isolation in mapAll
    // -----------------------------------------------------------------------

    @Test
    fun `one unmappable row never blanks its siblings`() {
        val unknown = row(state = "SOME_FUTURE_STATE").copy(episodeId = "ep-bad")
        val good1 = row(state = EpisodeDownloadEntity.STATE_DOWNLOADING).copy(episodeId = "ep-good-1")
        val good2 = row(state = EpisodeDownloadEntity.STATE_COMPLETED).copy(episodeId = "ep-good-2")

        val mapped = DownloadRowMapper.mapAll(listOf(good1, unknown, good2))

        assertEquals(
            "every mappable row must survive",
            listOf("ep-good-1", "ep-good-2"),
            mapped.rows.map { it.episodeId },
        )
        assertEquals(DownloadUserConcept.DOWNLOADING, mapped.rows[0].concept)
        assertEquals(DownloadUserConcept.COMPLETED, mapped.rows[1].concept)
        assertTrue(
            "the failure must be surfaced, not swallowed",
            mapped.loadError!!.contains("SOME_FUTURE_STATE"),
        )
    }

    @Test
    fun `a clean emission carries no load error`() {
        val rows = listOf(
            row(state = EpisodeDownloadEntity.STATE_QUEUED).copy(episodeId = "ep-1"),
            row(state = EpisodeDownloadEntity.STATE_PAUSED).copy(episodeId = "ep-2"),
        )

        val mapped = DownloadRowMapper.mapAll(rows)

        assertEquals(2, mapped.rows.size)
        assertNull("nothing failed, so nothing may be reported", mapped.loadError)
    }

    @Test
    fun `a failure message that contains a URL never reaches loadError`() {
        // The unknown state IS a URL — an exception message may quote one (a
        // corrupt row's value), and AGENTS §10 forbids surfacing it.
        val hostile = row(state = "https://evil.example/seg.ts").copy(episodeId = "ep-hostile")
        val good = row(state = EpisodeDownloadEntity.STATE_QUEUED).copy(episodeId = "ep-good")

        val mapped = DownloadRowMapper.mapAll(listOf(good, hostile))

        assertEquals("the sibling still renders", listOf("ep-good"), mapped.rows.map { it.episodeId })
        val loadError = mapped.loadError!!
        assertFalse("no URL may reach the UI (was: $loadError)", "://" in loadError)
        assertTrue(
            "the exception TYPE stands in for the hostile message",
            "IllegalArgumentException" in loadError,
        )
    }
}
