package com.pahebatcher.app.ui.mapper

import androidx.compose.runtime.Immutable
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.stream.AudioSelector
import com.pahebatcher.app.logging.AppLog
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Pure state mappers for the Downloads dashboard (PHASE11_ARCHITECTURE_V2 §13,
 * §16.1, §17). Engine entity → user-concept row is a pure, table-tested function
 * living outside composables so §36 tests exercise it directly.
 *
 * Contract summary (F-01/F-04, Decision 59, extended by the final SPEC §25 gap):
 * - Seven user concepts; RESOLVING folds into "queued" with a "preparing link"
 *   sub-label (§17) — the engine's 8 states never leak into UI vocabulary.
 *   `PAUSED` is the seventh concept and exists because SPEC §25 lists `paused`
 *   among the states the dashboard must show (supersedes Decision 59's "six
 *   concepts", which was written when no pause could be authored).
 * - Action availability is EXACTLY the §17.1 matrix below (8 states × 5 actions)
 *   — the UI never exposes a control whose repository operation is invalid.
 *   `Pause`/`Resume` were added by SPEC §20/§25 and supersede Decision 94.2's
 *   "no pause control exists" (which described Python, where none does).
 * - `downloadedBytes` is the byte total the engine really wrote (SPEC §25).
 * - `speed`/`etaSeconds` are REAL persisted measurements, but only while the row
 *   is DOWNLOADING: a terminal/paused row is never mid-transfer, so whatever
 *   rate it last held is stale by definition (stale-metric guard 2 of 2 — the
 *   claim CAS that clears the rate in `EpisodeDownloadDao.updateStateForResume`
 *   is 1 of 2). They map
 *   to null whenever unavailable, so a composable *cannot* print `0 B/s` or
 *   `0:00` (compile-enforced null handling, §16.1).
 * - Percentage derives ONLY from the persisted Room row; persisted
 *   `totalSegments == 0` (unknown) → NULL (no fabricated denominator, §16.1).
 */

/** The seven user-facing concepts (§16 vocabulary — mandatory). */
enum class DownloadUserConcept {
    QUEUED,
    DOWNLOADING,
    ASSEMBLING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED,
}

/**
 * Action availability for one row (§17.1 normative matrix).
 *
 * Five actions exist: the original `cancel`/`retry`/`play` plus `pause` and
 * `resume` (SPEC §20 actions, SPEC §25 `paused` state). Every flag is stated —
 * the UI may only render a control whose repository operation is valid.
 */
@Immutable
data class DownloadRowActions(
    val cancel: Boolean,
    val retry: Boolean,
    val play: Boolean,
    val pause: Boolean,
    val resume: Boolean,
)

/** Immutable dashboard row (§13 UiState modeling). */
@Immutable
data class DownloadRowUi(
    val episodeId: String,
    val title: String,
    val episodeNumber: Float,
    /** One of the seven user concepts — RESOLVING maps to [DownloadUserConcept.QUEUED]. */
    val concept: DownloadUserConcept,
    /** Sub-label shown with the concept ("preparing link" for engine RESOLVING, §17). */
    val subLabel: String?,
    val retryCount: Int,
    /** Present only for FAILED rows (§16 row content). */
    val errorMessage: String?,
    /**
     * SPEC §25 downloaded bytes — what the engine has really written to disk
     * (resume base + this run's atomic segment writes). 0 before any byte was
     * counted; the row renders nothing for 0 rather than a fabricated `0 B`.
     */
    val downloadedBytes: Long,
    /** null = unavailable, or the row is not DOWNLOADING (never a stale rate). */
    val speedBps: Long?,
    /** null = unavailable, or the row is not DOWNLOADING (never a stale rate). */
    val etaSeconds: Long?,
    val completedSegments: Int,
    /** null when the persisted denominator is 0/unknown (§16.1). */
    val totalSegments: Int?,
    /** null when the persisted denominator is 0/unknown — never fabricated (§16.1). */
    val progressPercent: Int?,
    /** Exactly the §17.1 matrix — no control outside it may be rendered. */
    val actions: DownloadRowActions,
    /**
     * The row's PERSISTED audio value, verbatim — `jpn`, `eng`, `kor`, … (SPEC
     * §22's open set).
     *
     * Slice B (DECISIONS 146): this is part of the row's IDENTITY
     * (`episodeId = <session>_<ep>_<audio>`), so the SUB and DUB downloads of
     * one episode are two distinct rows and must be tellable apart on screen.
     * Never read from any global selection — a row shows what it was downloaded
     * with, which may differ from what the details screen currently selects.
     */
    val audio: String,
) {
    /**
     * The compact secondary label rendered beside the episode number, or `null`
     * when the row carries no audio value to label (blank/legacy rows).
     *
     * Derived rather than stored: one field decides, so a hand-built row can
     * never show a label its `audio` contradicts (the same lesson as
     * `AnimeDetailsUiState.enqueued`).
     */
    val audioLabel: String?
        get() = DownloadRowMapper.audioLabelFor(audio)
}

object DownloadRowMapper {

    private const val TAG = "DownloadRowMapper"

    /** Engine RESOLVING sub-label (§17 — folds into queued, never its own concept). */
    const val RESOLVING_SUB_LABEL = "preparing link"

    /** Engine state → user concept (§17 table). Unknown states fail loudly (no silent guess). */
    fun toUserConcept(state: String): DownloadUserConcept = when (state) {
        EpisodeDownloadEntity.STATE_QUEUED,
        EpisodeDownloadEntity.STATE_RESOLVING,
        -> DownloadUserConcept.QUEUED

        EpisodeDownloadEntity.STATE_DOWNLOADING -> DownloadUserConcept.DOWNLOADING
        EpisodeDownloadEntity.STATE_ASSEMBLING -> DownloadUserConcept.ASSEMBLING
        EpisodeDownloadEntity.STATE_COMPLETED -> DownloadUserConcept.COMPLETED
        EpisodeDownloadEntity.STATE_FAILED -> DownloadUserConcept.FAILED
        EpisodeDownloadEntity.STATE_CANCELLED -> DownloadUserConcept.CANCELLED
        EpisodeDownloadEntity.STATE_PAUSED -> DownloadUserConcept.PAUSED
        else -> throw IllegalArgumentException("Unknown engine state: $state")
    }

    /** Sub-label for the concept row (non-null only for RESOLVING, §17). */
    fun subLabelFor(state: String): String? =
        if (state == EpisodeDownloadEntity.STATE_RESOLVING) RESOLVING_SUB_LABEL else null

    /**
     * Slice B (DECISIONS 146) — the honest audio label for a Downloads row.
     *
     * The rule has to satisfy two things at once that pull in opposite
     * directions:
     *
     * * **SPEC §22 is an OPEN set.** `jpn`/`eng` are not privileged over
     *   `kor`/`hin`/`spa`, so the label must never reduce every value to a
     *   SUB/DUB boolean or drop a value it does not recognise.
     * * **Never invent a language.** Rendering `kor` as "Korean" would be a
     *   guess about what AnimePahe's `data-audio` meant for that release.
     *
     * So: the two values with a reliable, unambiguous English name are rendered
     * as that name (`jpn` → "Japanese", `eng` → "English"), and every other
     * non-blank value is echoed as its own uppercased code (`kor` → "KOR") —
     * exactly what the player already does through
     * `StringUtils.audioBadge`, and exactly what the details selector shows.
     * Echoing a code is not inventing a language, and it keeps the rows of one
     * episode distinguishable, which is the whole point of the label.
     *
     * Blank or missing audio (a legacy row, or one written before audio was
     * persisted) yields `null` and the row renders no label at all — the
     * `"" → ""` convention `StringUtils.audioBadge` already uses. `null` is
     * never "jpn", and no default is ever substituted.
     */
    fun audioLabelFor(audio: String?): String? = when (audio?.trim()?.lowercase()) {
        null, "" -> null
        AudioSelector.AUDIO_JAPANESE -> "Japanese"
        AudioSelector.AUDIO_ENGLISH -> "English"
        else -> audio.trim().uppercase(Locale.US)
    }

    /**
     * §17.1 normative action matrix (8 engine states × 5 actions):
     *
     * | state                    | Pause | Resume | Cancel | Retry | Play |
     * |--------------------------|:-----:|:------:|:------:|:-----:|:----:|
     * | QUEUED / RESOLVING /     |  ✅   |   ❌   |   ✅   |  ❌   |  ❌  |
     * | DOWNLOADING              |       |        |        |       |      |
     * | ASSEMBLING               |  ❌   |   ❌   |   ✅   |  ❌   |  ❌  |
     * | PAUSED                   |  ❌   |   ✅   |   ✅   |  ❌   |  ❌  |
     * | COMPLETED                |  ❌   |   ❌   |   ❌   |  ❌   |  ✅  |
     * | FAILED / CANCELLED       |  ❌   |   ❌   |   ❌   |  ✅   |  ❌  |
     *
     * Notes on why each cell is what it is:
     *
     * - `Pause` covers exactly `EpisodeDownloadEntity.PAUSABLE_STATES`.
     *   `ASSEMBLING` is excluded because muxing cannot be interrupted halfway
     *   without risking a partial MP4 being mistaken for an output on a later
     *   resume — the row finishes on its own instead of pretending to pause.
     * - `Resume` exists ONLY for `PAUSED` (SPEC §20): it is
     *   `DownloadRepository.resume()`, a `PAUSED → QUEUED` claim (or a
     *   re-resolution when the row owns no stream). A `QUEUED` row is not
     *   offered Resume — it is already queued and its Worker owns it; offering a
     *   second entry point would double-enqueue.
     * - `Cancel` on `PAUSED` is valid: `DownloadRepository.cancel()` writes
     *   CANCELLED for ANY non-terminal row, so it really discards the pause
     *   (final-audit M1: the write is terminal-guarded, never blind — a
     *   `COMPLETED`/`FAILED`/`CANCELLED` row is refused, and this matrix never
     *   offers Cancel for those states anyway).
     *
     * Stricter than the repository on purpose (§17.1 notes): `reEnqueue()`
     * accepts terminal states
     * more loosely than the UI offers (11.5E-7: FAILED/CANCELLED re-resolve;
     * COMPLETED only when its output is gone — the matrix never offers Retry
     * for COMPLETED at all) — the matrix is the UI contract.
     */
    fun actionsFor(state: String): DownloadRowActions = when (state) {
        EpisodeDownloadEntity.STATE_QUEUED,
        EpisodeDownloadEntity.STATE_RESOLVING,
        EpisodeDownloadEntity.STATE_DOWNLOADING,
        -> DownloadRowActions(
            cancel = true,
            retry = false,
            play = false,
            pause = true,
            resume = false,
        )

        EpisodeDownloadEntity.STATE_ASSEMBLING ->
            DownloadRowActions(cancel = true, retry = false, play = false, pause = false, resume = false)

        EpisodeDownloadEntity.STATE_PAUSED ->
            DownloadRowActions(cancel = true, retry = false, play = false, pause = false, resume = true)

        EpisodeDownloadEntity.STATE_COMPLETED ->
            DownloadRowActions(cancel = false, retry = false, play = true, pause = false, resume = false)

        EpisodeDownloadEntity.STATE_FAILED,
        EpisodeDownloadEntity.STATE_CANCELLED,
        -> DownloadRowActions(cancel = false, retry = true, play = false, pause = false, resume = false)

        else -> throw IllegalArgumentException("Unknown engine state: $state")
    }

    /**
     * §16.1 speed rule: display only if > 0, otherwise null (never `0 B/s`).
     */
    fun speedOrNull(speed: Long): Long? = if (speed > 0) speed else null

    /**
     * §16.1 ETA rule: display only if > 0, otherwise null (never `0:00`).
     */
    fun etaOrNull(etaSeconds: Long): Long? = if (etaSeconds > 0) etaSeconds else null

    /**
     * §16.1 display formatter for speed: `B/s`, `KB/s`, or `MB/s` (base 1024).
     * Only non-positive/null values are rejected upstream by [speedOrNull], so a
     * rendered value can never be the forbidden `0 B/s`.
     */
    fun formatSpeed(speedBps: Long?): String? {
        val bps = speedOrNull(speedBps ?: return null) ?: return null
        return when {
            bps >= BYTES_PER_MB -> String.format(Locale.US, "%.1f MB/s", bps / BYTES_PER_MB_DOUBLE)
            bps >= BYTES_PER_KB -> String.format(Locale.US, "%.1f KB/s", bps / BYTES_PER_KB_DOUBLE)
            else -> "$bps B/s"
        }
    }

    /**
     * §16.1 display formatter for ETA: `m:ss` under an hour, `h:mm:ss` beyond.
     * [etaOrNull] already rejects 0/negative, so `0:00` can never be produced.
     */
    fun formatEta(seconds: Long?): String? {
        val total = etaOrNull(seconds ?: return null) ?: return null
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    private const val BYTES_PER_KB = 1024L
    private const val BYTES_PER_MB = 1024L * 1024L
    private const val BYTES_PER_KB_DOUBLE = 1024.0
    private const val BYTES_PER_MB_DOUBLE = 1024.0 * 1024.0

    /**
     * §16.1 percentage rule: a pure function of the persisted Room row using the
     * persisted `totalSegments` as denominator. `0` means unknown → null
     * (a rendered "0%" would falsely imply a known total at zero). Never
     * fabricated, never carried in view state.
     */
    fun progressPercentOrNull(entity: EpisodeDownloadEntity): Int? =
        if (entity.totalSegments > 0) entity.progressPercent() else null

    /** Map a persisted row to the immutable dashboard row (§13). */
    fun map(entity: EpisodeDownloadEntity): DownloadRowUi = DownloadRowUi(
        episodeId = entity.episodeId,
        title = entity.title,
        episodeNumber = entity.episodeNumber,
        concept = toUserConcept(entity.state),
        subLabel = subLabelFor(entity.state),
        retryCount = entity.retryCount,
        errorMessage = if (entity.state == EpisodeDownloadEntity.STATE_FAILED) entity.errorMessage else null,
        downloadedBytes = entity.downloadedBytes.coerceAtLeast(0L),
        // SPEC §25 stale-metric guard: only a row that is really transferring may
        // show a rate. Anything else (queued, assembling, terminal, paused) would
        // be advertising a measurement that no longer describes it — its persisted
        // numbers are simply not surfaced here.
        speedBps = if (entity.state == EpisodeDownloadEntity.STATE_DOWNLOADING) {
            speedOrNull(entity.speed)
        } else {
            null
        },
        etaSeconds = if (entity.state == EpisodeDownloadEntity.STATE_DOWNLOADING) {
            etaOrNull(entity.etaSeconds)
        } else {
            null
        },
        completedSegments = entity.completedSegments,
        totalSegments = if (entity.totalSegments > 0) entity.totalSegments else null,
        progressPercent = progressPercentOrNull(entity),
        actions = actionsFor(entity.state),
        // Slice B: carried verbatim from the row, never from a screen-level
        // selection — a download shows the audio it was ACTUALLY made with.
        audio = entity.audio,
    )

    /**
     * The result of mapping a whole Room emission (A2.1 Decision 9, UI-010).
     *
     * @property rows every row that mapped successfully — one unmappable row
     *   never blanks the dashboard; its siblings stay visible.
     * @property loadError a SANITIZED human-readable note (never carrying a
     *   URL — AGENTS §10), or null when every row mapped.
     */
    data class MappedRows(
        val rows: List<DownloadRowUi>,
        val loadError: String?,
    )

    /**
     * A2.1 Decision 9 (UI-010): map a whole Room emission with per-row
     * isolation.
     *
     * Before this, `entities.map(::map)` threw out of the flow mapping the
     * moment ONE row carried an engine state this build does not know (a row
     * written by a newer version, or a corrupt value): the whole emission
     * died, the dashboard sat on its loading placeholder, and the ViewModel's
     * `stateIn` kept the stale initial state. Now:
     *
     *  - a failing row is logged with its episodeId and skipped;
     *  - its siblings still render ([MappedRows.rows]);
     *  - the user gets ONE sanitized note ([MappedRows.loadError]) through the
     *    same `"://" !in message` rule the ViewModel's action-failure channel
     *    uses — an exception message carrying a URL is replaced by the
     *    exception type (AGENTS §10: no session/stream location reaches the UI);
     *  - a [CancellationException] is NEVER converted into a load error — it
     *    is structural (scope teardown) and rethrows unchanged.
     */
    fun mapAll(entities: List<EpisodeDownloadEntity>): MappedRows {
        val rows = ArrayList<DownloadRowUi>(entities.size)
        var loadError: String? = null
        for (entity in entities) {
            try {
                rows.add(map(entity))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Row mapping failed for ${entity.episodeId}", e)
                val detail = e.message
                    ?.takeIf { it.isNotBlank() && "://" !in it }
                    ?: e.javaClass.simpleName
                loadError = "Some episodes could not be shown: $detail"
            }
        }
        return MappedRows(rows = rows, loadError = loadError)
    }
}
