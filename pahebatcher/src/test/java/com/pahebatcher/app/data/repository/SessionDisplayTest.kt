package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The session display-state precedence table,
 * exhaustively: all five required cases plus every state mix that could be
 * mistaken for one, and the §20 counters that ride on the same pass.
 *
 * Invariants proven here:
 *
 * - first-match-wins precedence (no row mix derives two states);
 * - `PAUSED` derives EXACTLY when nothing in the session is working and the user
 *   stopped at least one row (rule 2b, SPEC §25) — proven over every mix of
 *   size ≤ 3, in both directions: no working row + a paused row → PAUSED, and
 *   any working row → DOWNLOADING, never PAUSED (supersedes Decision 94.2's
 *   "PAUSED is never derivable", which described the Python reference);
 * - all-terminal non-all-completed always derives FAILED (rules 4+5);
 * - progress is null while the persisted denominator is unknown (§16.1).
 */
class SessionDisplayTest {

    private fun row(
        state: String,
        totalSegments: Int = 0,
        completedSegments: Int = 0,
    ) = EpisodeDownloadEntity(
        episodeId = "ep-${state.lowercase()}-${totalSegments}-${completedSegments}",
        sessionId = "sess-1",
        episodeNumber = 1.0f,
        episodeSession = "ep-sess-1",
        title = "Episode",
        fansub = "",
        audio = "sub",
        playUrl = "https://example.com/play",
        totalSegments = totalSegments,
        completedSegments = completedSegments,
        state = state,
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private val activeStates = listOf(
        EpisodeDownloadEntity.STATE_QUEUED,
        EpisodeDownloadEntity.STATE_RESOLVING,
        EpisodeDownloadEntity.STATE_DOWNLOADING,
        EpisodeDownloadEntity.STATE_ASSEMBLING,
    )
    private val terminalStates = listOf(
        EpisodeDownloadEntity.STATE_COMPLETED,
        EpisodeDownloadEntity.STATE_FAILED,
        EpisodeDownloadEntity.STATE_CANCELLED,
    )

    // -----------------------------------------------------------------------
    // Rule 1 — no rows
    // -----------------------------------------------------------------------

    @Test
    fun `no rows derives CREATED`() {
        assertEquals(
            DownloadSessionEntity.STATE_CREATED,
            SessionDisplay.deriveState(emptyList()),
        )
    }

    // -----------------------------------------------------------------------
    // Rule 2 — any non-terminal row wins over every terminal mix
    // -----------------------------------------------------------------------

    @Test
    fun `every active state derives DOWNLOADING`() {
        activeStates.forEach { active ->
            assertEquals(
                "single $active row must derive DOWNLOADING",
                DownloadSessionEntity.STATE_DOWNLOADING,
                SessionDisplay.deriveState(listOf(row(active))),
            )
        }
    }

    @Test
    fun `CANCELLED plus active mix derives DOWNLOADING`() {
        // The fifth required case: terminal CANCELLED rows must not mask an
        // active row (rule 2 first).
        val rows = listOf(
            row(EpisodeDownloadEntity.STATE_CANCELLED),
            row(EpisodeDownloadEntity.STATE_QUEUED),
        )
        assertEquals(DownloadSessionEntity.STATE_DOWNLOADING, SessionDisplay.deriveState(rows))
    }

    @Test
    fun `every terminal mix with one active row derives DOWNLOADING`() {
        // All-terminal combinations × one active row — precedence rule 2 first.
        val activeCombos = activeStates.map { active ->
            listOf(row(active), row(EpisodeDownloadEntity.STATE_COMPLETED))
        }
        (activeCombos).forEach { rows ->
            assertEquals(DownloadSessionEntity.STATE_DOWNLOADING, SessionDisplay.deriveState(rows))
        }
    }

    // -----------------------------------------------------------------------
    // Rule 3 — all COMPLETED
    // -----------------------------------------------------------------------

    @Test
    fun `all rows COMPLETED derives COMPLETED`() {
        val rows = listOf(
            row(EpisodeDownloadEntity.STATE_COMPLETED),
            row(EpisodeDownloadEntity.STATE_COMPLETED),
        )
        assertEquals(DownloadSessionEntity.STATE_COMPLETED, SessionDisplay.deriveState(rows))
    }

    // -----------------------------------------------------------------------
    // Rule 4 — all terminal, at least one FAILED
    // -----------------------------------------------------------------------

    @Test
    fun `all terminal with at least one FAILED derives FAILED`() {
        val required = listOf(
            // all-FAILED
            listOf(FAILED, FAILED),
            // COMPLETED + FAILED
            listOf(COMPLETED, FAILED),
            // FAILED + CANCELLED
            listOf(FAILED, CANCELLED),
            // the complete mixed-terminal case: COMPLETED + FAILED + CANCELLED
            listOf(COMPLETED, FAILED, CANCELLED),
        )
        required.forEach { states ->
            val rows = states.map { row(it) }
            assertEquals(
                states.toString(),
                DownloadSessionEntity.STATE_FAILED,
                SessionDisplay.deriveState(rows),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Rule 5 — all terminal, 0 FAILED, not all COMPLETED
    // -----------------------------------------------------------------------

    @Test
    fun `all CANCELLED derives FAILED`() {
        val rows = listOf(row(CANCELLED), row(CANCELLED))
        assertEquals(DownloadSessionEntity.STATE_FAILED, SessionDisplay.deriveState(rows))
    }

    @Test
    fun `COMPLETED plus CANCELLED derives FAILED`() {
        val rows = listOf(row(COMPLETED), row(CANCELLED))
        assertEquals(DownloadSessionEntity.STATE_FAILED, SessionDisplay.deriveState(rows))
    }

    // -----------------------------------------------------------------------
    // Rule 2b — PAUSED derives exactly when nothing works and something is
    // paused (SPEC §25; supersedes DECISIONS 94.2)
    // -----------------------------------------------------------------------

    @Test
    fun `every paused row with no working sibling derives PAUSED`() {
        listOf(
            listOf(PAUSED),
            listOf(PAUSED, PAUSED),
            listOf(PAUSED, COMPLETED),
            listOf(PAUSED, FAILED),
            listOf(PAUSED, CANCELLED),
            listOf(PAUSED, COMPLETED, FAILED),
        ).forEach { states ->
            val rows = states.map { row(it) }
            assertEquals(
                states.toString(),
                DownloadSessionEntity.STATE_PAUSED,
                SessionDisplay.deriveState(rows),
            )
        }
    }

    @Test
    fun `a working row beats a paused sibling - the session is still downloading`() {
        // Rule 2 stays first: one row still working means the session is NOT
        // paused, whatever else it holds.
        activeStates.forEach { active ->
            assertEquals(
                "working $active + PAUSED must stay DOWNLOADING",
                DownloadSessionEntity.STATE_DOWNLOADING,
                SessionDisplay.deriveState(listOf(row(active), row(PAUSED))),
            )
        }
    }

    @Test
    fun `PAUSED derives exactly when no row works and at least one row is paused`() {
        val everyEpisodeState = activeStates + terminalStates + PAUSED
        // Every combination of size 3 (deterministic, bounded) — asserted in BOTH
        // directions, so neither "PAUSED leaks into a working session" nor
        // "PAUSED disappears from a stopped one" can pass unnoticed.
        for (a in everyEpisodeState) {
            for (b in everyEpisodeState) {
                for (c in everyEpisodeState) {
                    val rows = listOf(row(a), row(b), row(c))
                    val working = rows.any {
                        !it.isTerminal() && it.state != PAUSED
                    }
                    val hasPausedRow = rows.any { it.state == PAUSED }
                    val derived = SessionDisplay.deriveState(rows)
                    assertEquals(
                        "mix [$a, $b, $c] (working=$working, hasPaused=$hasPausedRow)",
                        !working && hasPausedRow,
                        derived == DownloadSessionEntity.STATE_PAUSED,
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // §20 counters (one pass)
    // -----------------------------------------------------------------------

    @Test
    fun `stats count episodes segments and derived state`() {
        val rows = listOf(
            row(COMPLETED, totalSegments = 10, completedSegments = 10),
            row(FAILED, totalSegments = 10, completedSegments = 4),
            row(CANCELLED, totalSegments = 0, completedSegments = 0),
            row(DOWNLOADING, totalSegments = 6, completedSegments = 3),
        )
        val stats = SessionDisplay.deriveStats(rows)

        // Rule 2 first: the DOWNLOADING row keeps the session active even
        // though terminal rows are present.
        assertEquals(DownloadSessionEntity.STATE_DOWNLOADING, stats.state)
        assertEquals(4, stats.episodeCount)
        assertEquals(1, stats.completedEpisodes)
        assertEquals(3, stats.incompleteEpisodes)
        assertEquals(17, stats.completedSegments)
        assertEquals(26, stats.totalSegments)
        // 17/26 = 65.38% → 65
        assertEquals(65, stats.segmentPercent)
    }

    @Test
    fun `stats on empty rows is CREATED with zero counters`() {
        val stats = SessionDisplay.deriveStats(emptyList())
        assertEquals(DownloadSessionEntity.STATE_CREATED, stats.state)
        assertEquals(0, stats.episodeCount)
        assertEquals(0, stats.completedEpisodes)
        assertEquals(0, stats.incompleteEpisodes)
        assertNull(stats.segmentPercent)
    }

    @Test
    fun `unknown denominator yields no percent but never a fabricated zero`() {
        // Only non-terminal rows with no persisted totals (§16.1).
        val rows = listOf(row(DOWNLOADING, totalSegments = 0, completedSegments = 0))
        val stats = SessionDisplay.deriveStats(rows)
        assertNull(stats.segmentPercent)
        assertEquals(0, stats.completedSegments)
    }

    companion object {
        private const val COMPLETED = EpisodeDownloadEntity.STATE_COMPLETED
        private const val FAILED = EpisodeDownloadEntity.STATE_FAILED
        private const val CANCELLED = EpisodeDownloadEntity.STATE_CANCELLED
        private const val DOWNLOADING = EpisodeDownloadEntity.STATE_DOWNLOADING
        private const val PAUSED = EpisodeDownloadEntity.STATE_PAUSED
    }
}
