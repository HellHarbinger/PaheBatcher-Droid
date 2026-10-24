package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity

/**
 * Pure derivations for the Sessions
 * screen — the display state precedence table and the §20 progress counters,
 * both computed ONLY from the persisted episode rows (no fake state, no
 * schema change).
 *
 * Display-state precedence (DECISIONS 109, first match wins). The final SPEC gap
 * closure split old rule 2 and inserted rule 2b, superseding Decision 94.2's
 * "PAUSED is never derived" — SPEC §25 lists `paused` among the states the
 * session must be able to show:
 *
 *  1. no episode rows → [DownloadSessionEntity.STATE_CREATED];
 *  2. any WORKING row (QUEUED/RESOLVING/DOWNLOADING/ASSEMBLING) →
 *     [DownloadSessionEntity.STATE_DOWNLOADING] — a session that is downloading
 *     never reads as paused, whatever its other rows say (the old "any
 *     non-terminal row" rule, with `PAUSED` carved out);
 *  2b. no working row but ≥1 `PAUSED` row → [DownloadSessionEntity.STATE_PAUSED]
 *     — every row is either stopped-by-the-user or terminal, so the session IS
 *     paused. Derived from real rows, never a display literal (Python prints a
 *     literal `Paused` for every session, `sessions.py:75` — Android does not);
 *  3. all rows COMPLETED → [DownloadSessionEntity.STATE_COMPLETED];
 *  4. all rows terminal with ≥1 FAILED → [DownloadSessionEntity.STATE_FAILED];
 *  5. all rows terminal, 0 FAILED, not all COMPLETED (all CANCELLED;
 *     COMPLETED+CANCELLED) → [DownloadSessionEntity.STATE_FAILED] — the
 *     five-state vocabulary has no CANCELLED session state, and FAILED means
 *     "terminal and not fully complete" here (row-level states remain the
 *     truthful detail).
 *
 * Rules 3–5 are unreachable when a row is `PAUSED` (rule 2b catches it first),
 * because `PAUSED` is non-terminal by [EpisodeDownloadEntity.isTerminal].
 */
object SessionDisplay {

    /** Derive the display-only session state per DECISIONS 109 (rules 2/2b — see KDoc). */
    fun deriveState(rows: List<EpisodeDownloadEntity>): String {
        if (rows.isEmpty()) return DownloadSessionEntity.STATE_CREATED
        // Rule 2 — working rows beat everything, including a paused sibling row.
        val working = rows.any {
            !it.isTerminal() && it.state != EpisodeDownloadEntity.STATE_PAUSED
        }
        if (working) return DownloadSessionEntity.STATE_DOWNLOADING
        // Rule 2b — nothing is working and the user stopped at least one row.
        if (rows.any { it.state == EpisodeDownloadEntity.STATE_PAUSED }) {
            return DownloadSessionEntity.STATE_PAUSED
        }
        if (rows.all { it.state == EpisodeDownloadEntity.STATE_COMPLETED }) {
            return DownloadSessionEntity.STATE_COMPLETED
        }
        return DownloadSessionEntity.STATE_FAILED
    }

    /**
     * SPEC §20 counters for one session row: episode counts, segment progress
     * and the derived state in one pass over the rows.
     *
     * `totalSegments == 0` means "no persisted denominator yet" (§16.1) — the
     * UI must show no fabricated percentage for it.
     */
    data class Stats(
        val state: String,
        val episodeCount: Int,
        val completedEpisodes: Int,
        val incompleteEpisodes: Int,
        val completedSegments: Int,
        val totalSegments: Int,
    ) {
        /** Segment progress percent, or null while the denominator is unknown. */
        val segmentPercent: Int?
            get() = if (totalSegments > 0) {
                ((completedSegments.toFloat() / totalSegments) * 100).toInt().coerceIn(0, 100)
            } else {
                null
            }
    }

    fun deriveStats(rows: List<EpisodeDownloadEntity>): Stats {
        val completed = rows.count { it.state == EpisodeDownloadEntity.STATE_COMPLETED }
        return Stats(
            state = deriveState(rows),
            episodeCount = rows.size,
            completedEpisodes = completed,
            incompleteEpisodes = rows.size - completed,
            completedSegments = rows.sumOf { it.completedSegments },
            totalSegments = rows.sumOf { it.totalSegments },
        )
    }
}
