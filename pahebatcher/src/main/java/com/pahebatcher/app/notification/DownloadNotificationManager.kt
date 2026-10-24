package com.pahebatcher.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.R
import com.pahebatcher.app.domain.download.FailureCategory
import com.pahebatcher.app.ui.mapper.DownloadRowMapper

/**
 * Builds and posts the episode lifecycle notifications.
 * Phase 10 architecture v10 §7, §10 (extended by Phase 14, PHASE14_PLAN §7/§8).
 *
 * Responsibilities:
 * - Create the app's notification channels (delegated to [NotificationChannels])
 * - Progress notifications (foreground/queued) on [CHANNEL_ID_DOWNLOADS]
 * - Terminal notifications (completion/failure) on their own channels
 * - Update / cancel notifications by stable id
 * - Handle notification tap → open app at the `navigate_to` destination
 *
 * Content rule (PHASE14_PLAN §8, extended by Slice C and this slice): terminal
 * notifications carry a title plus only what the code can PROVE — the episode
 * title, the audio label persisted on the row, and the typed failure category;
 * the batch summary adds an aggregate reason breakdown. The raw error text is
 * never rendered, because it may embed a stream URL (AGENTS §10). The detailed
 * per-episode error stays in the in-app Downloads screen.
 *
 * Interaction rule (PHASE14_PLAN §7): tap-to-open only. The ONE exception is
 * the completion notification's `Replace file` action (DECISIONS 152), which is
 * a ROUTE and not a command: it opens the in-app confirmation for exactly the
 * episode the notification belongs to and performs no download, deletion or
 * overwrite itself.
 */
class DownloadNotificationManager(private val context: Context) {

    companion object {
        /**
         * Progress channel id — unchanged since Phase 10 so existing installs
         * keep the user's customized importance (importance is sticky).
         */
        const val CHANNEL_ID_DOWNLOADS = NotificationChannels.DOWNLOAD_PROGRESS

        /** Group key for EVERY episode notification (PHASE14_PLAN §8). */
        const val GROUP = "pahebatcher.downloads"

        /** `navigate_to` extra value that opens the Downloads tab (§7). */
        private const val TARGET_DOWNLOADS = "downloads"

        /**
         * `Replace file` (DECISIONS 152) — the EXACT label the completion
         * notification's action carries, in this exact casing.
         *
         * A named constant (and an asserted one) so the notification, the
         * in-app row affordance and the documentation can never drift into
         * "Replace", "Re-download" or "Replace existing".
         */
        const val ACTION_REPLACE_FILE_LABEL = "Replace file"

        /**
         * `Replace file` (DECISIONS 152) — the closing clause a FAILURE
         * notification carries only when the failed run was a confirmed
         * replacement whose previous export survived.
         *
         * Without it the user reads "Download failed" for an episode the
         * Downloads screen still shows as completed: two different stories
         * about one row. It names no error and quotes no text (AGENTS §10) —
         * only what the code can prove: the replacement did not happen and the
         * file that was already there is still there.
         */
        const val REPLACEMENT_KEPT_EXISTING_SUFFIX = " (replacement failed, existing file kept)"

        /**
         * Slice C — the ONE aggregated batch-failure notification id.
         *
         * Fixed and NEGATIVE like every terminal id, so each batch run
         * REPLACES the previous summary instead of stacking a new one, and
         * distinct from the watchlist summary (`-9001`) and from every
         * per-episode id (positive for progress, `… or Int.MIN_VALUE` for
         * terminal).
         */
        const val BATCH_FAILURE_NOTIFICATION_ID = -9002

        /**
         * PB-001 fix — the PendingIntent REQUEST CODE for the batch-failure tap.
         *
         * A PendingIntent's identity is (creator package, request code,
         * `Intent.filterEquals`), and `filterEquals` IGNORES extras. The media
         * session activity ([MainActivity.createSessionActivityIntent]) also builds an
         * `Intent(context, MainActivity)` with extras only, and used the SAME `-9002`
         * request code — so both recorded the SAME PendingIntent and `FLAG_UPDATE_CURRENT`
         * let whichever was built last overwrite the other's `navigate_to` target
         * (Downloads vs `player:<episodeId>`). Giving the batch tap its own code keeps
         * the two destinations distinct. The NOTIFICATION id stays `-9002` (that is a
         * separate id space and must keep replacing the previous summary).
         */
        const val BATCH_CONTENT_REQUEST_CODE = -9003

        /**
         * Post-audio slice — how many distinct failure reasons a batch summary
         * will name before it collapses the rest into a count.
         *
         * A notification line is read at a glance: three covers the usual
         * "everything failed the same way" case in full, and anything beyond
         * that is noise the in-app batch list already enumerates.
         */
        const val REASON_SUMMARY_LIMIT = 3

        /**
         * Post-audio slice — the one line of content a failure notification
         * carries.
         *
         * Episode title first (unchanged), then only attributes the code can
         * prove: the audio persisted on the row, and the category the failure
         * was typed as. Either may be absent. `category?.label` is `null` for
         * [FailureCategory.UNKNOWN], so an unattributable failure renders NO
         * category rather than an invented one — and with both attributes
         * absent this is byte-identical to the pre-Slice-C text, so a failure
         * the app cannot attribute still says exactly what it said before.
         *
         * The raw error is never rendered: it may quote a stream URL (AGENTS
         * §10), and the detailed reason belongs to the Downloads screen. Both
         * optional parts are therefore drawn from a language CODE and a typed
         * category — never from exception text.
         */
        internal fun failureText(
            episodeTitle: String,
            audio: String?,
            category: FailureCategory?,
        ): String {
            val audioLabel = DownloadRowMapper.audioLabelFor(audio)
            val categoryLabel = category?.label
            return listOfNotNull(episodeTitle, audioLabel, categoryLabel).joinToString(" · ")
        }

        /**
         * Post-audio notification slice — the episode line every PROGRESS and
         * COMPLETION notification renders.
         *
         * Episode title first (unchanged position), then the audio label
         * persisted on the row. The label comes from
         * [DownloadRowMapper.audioLabelFor] — the SAME helper the Downloads
         * screen uses — so a notification and the row it belongs to always say
         * the identical words for a given language code, and it is never
         * inferred from a filename, a URL or transient UI state.
         *
         * When [audio] is null or blank the label is ABSENT, not invented:
         * `audioLabelFor` returns null and `listOfNotNull` drops the segment,
         * so a row with genuinely unknown metadata renders exactly the text it
         * rendered before audio was threaded through (no dangling ` · `).
         *
         * Safe by construction: only a language name or a language code ever
         * enters this line (AGENTS §10 — no URL, cookie or token).
         */
        internal fun episodeLine(episodeTitle: String, audio: String?): String =
            listOfNotNull(episodeTitle, DownloadRowMapper.audioLabelFor(audio))
                .joinToString(" · ")

        /**
         * Post-audio notification slice — the content line of a progress
         * notification.
         *
         * [progress] >= 0 appends the percentage exactly as before
         * (`<line> — 42%`); a negative progress means the real progress is not
         * known yet, so only the episode line is shown (indeterminate and
         * queued branches). With [audio] absent this is byte-identical to the
         * pre-audio text.
         */
        internal fun progressText(episodeTitle: String, audio: String?, progress: Int): String {
            val base = episodeLine(episodeTitle, audio)
            return if (progress >= 0) "$base — $progress%" else base
        }

        /**
         * Post-audio notification slice — the content line of a completion
         * notification: the episode line alone, since a finished download has
         * no percentage to report.
         */
        internal fun completionText(episodeTitle: String, audio: String?): String =
            episodeLine(episodeTitle, audio)

        /**
         * Post-audio slice — `"3 of 12 episodes failed — <title>"`, optionally
         * followed by ` · <reason breakdown>`.
         *
         * `episodes` is always plural because it belongs to the TOTAL ("of 12"),
         * not to the failure count — "1 of 12 episodes failed" reads correctly
         * without a second grammar branch that could be wrong.
         *
         * @param reasonCounts label → count, derived by the caller from
         *   [FailureCategory.label] only. Empty means every failure was
         *   [FailureCategory.UNKNOWN] — and then the summary is EXACTLY the
         *   counts-plus-title line it has always been, because a summary that
         *   cannot name a reason must not invent one.
         */
        internal fun batchSummaryText(
            failed: Int,
            total: Int,
            animeTitle: String,
            reasonCounts: Map<String, Int> = emptyMap(),
        ): String {
            val base = "$failed of $total episodes failed — $animeTitle"
            val reasons = reasonSummaryText(reasonCounts)
            return if (reasons.isEmpty()) base else "$base · $reasons"
        }

        /**
         * Post-audio slice — the concise `A ×2, B` breakdown of a batch's
         * failure reasons.
         *
         * Deterministic (count descending, then label ascending) so the same
         * run always renders the same line regardless of map ordering, capped
         * at [REASON_SUMMARY_LIMIT] with an explicit `+N more` rather than a
         * silent truncation. Each label is a [FailureCategory.label] — typed,
         * URL-free prose — so nothing here can leak a stream URL, a token or an
         * exception message (AGENTS §10).
         */
        internal fun reasonSummaryText(reasonCounts: Map<String, Int>): String {
            val ordered = reasonCounts.entries
                .filter { it.key.isNotBlank() && it.value > 0 }
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { it.key },
                )
            if (ordered.isEmpty()) return ""
            val shown = ordered.take(REASON_SUMMARY_LIMIT)
            val text = shown.joinToString(", ") { (label, count) ->
                if (count > 1) "$label ×$count" else label
            }
            val hidden = ordered.size - shown.size
            return if (hidden > 0) "$text +$hidden more" else text
        }

        /**
         * A2.1 (AQR-054): the per-episode PendingIntent request code — the id's
         * hash masked NON-NEGATIVE (the same `0x7FFFFFFF` mask that keeps
         * [DownloadNotificationManager.notificationIdFor] positive), so no
         * episode id can ever land on one of the app's reserved NEGATIVE codes
         * (request codes -9001/-9002/-9003, notification ids -9001/-9002) and
         * cross-namespace-collide with them: `filterEquals` ignores extras, so
         * the request code is part of PendingIntent identity. Stable per
         * episode, exactly as the raw hash was.
         */
        internal fun requestCodeFor(episodeId: String): Int = episodeId.hashCode() and 0x7FFFFFFF
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Create ALL app notification channels.
     * Idempotent — safe to call multiple times (PHASE14_PLAN §8).
     *
     * Unconditional — minSdk 32 > O (26).
     * delegates the four-channel creation to [NotificationChannels].
     */
    fun createNotificationChannel() {
        NotificationChannels.ensure(notificationManager)
    }

    /**
     * Build a progress notification for an episode.
     * @param episodeId Episode ID
     * @param episodeTitle Episode title
     * @param audio Audio language persisted on the row (`"jpn"`/`"eng"`), or
     *   null when unknown — rendered by [progressText] as an absent label
     *   rather than a guessed one
     * @param progress Current progress (0-100), or -1 for indeterminate
     * @param isActive Whether the download is currently active
     */
    fun buildProgressNotification(
        episodeId: String,
        episodeTitle: String,
        audio: String? = null,
        progress: Int = -1,
        isActive: Boolean = true,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setSilent(true) // No sound — an active download never alerts
            .setContentIntent(contentIntent(episodeId))
            .setAutoCancel(false) // Don't cancel on tap — user might want to see progress
            .setOngoing(true) // Can't swipe to dismiss
            .setGroup(GROUP)

        if (isActive) {
            if (progress >= 0) {
                builder
                    .setContentTitle("Downloading")
                    .setContentText(progressText(episodeTitle, audio, progress))
                    .setProgress(100, progress, false)
            } else {
                builder
                    .setContentTitle("Downloading")
                    .setContentText(progressText(episodeTitle, audio, -1))
                    .setProgress(0, 0, true) // Indeterminate
            }
        } else {
            builder
                .setContentTitle("Download Queued")
                .setContentText(progressText(episodeTitle, audio, -1))
                .setProgress(0, 0, true) // Indeterminate
        }

        return builder.build()
    }

    /**
     * Terminal SUCCESS notification.
     *
     * Channel [NotificationChannels.DOWNLOAD_COMPLETE] (`DEFAULT` — this is a
     * discrete event the user wants to know about), app-owned icon, group
     * [GROUP], `setAutoCancel(true)` (tap dismisses), destination
     * `navigate_to="downloads"` — deliberately the SAME request code and extras
     * as the progress notification of the same episode (§7), and posted only
     * after `cancelNotification(progressId)` so the two are never both visible.
     *
     * Post-audio slice: the content line carries the persisted audio label via
     * [completionText], so a completed episode reads the same language it was
     * queued as (`Episode 1 · Japanese`). Absent metadata renders the title
     * alone — unchanged from before.
     *
     * @param audio Audio language persisted on the row, or null when unknown
     */
    fun buildCompletionNotification(
        episodeId: String,
        episodeTitle: String,
        audio: String? = null,
    ): Notification =
        NotificationCompat.Builder(context, NotificationChannels.DOWNLOAD_COMPLETE)
            .setSmallIcon(R.drawable.ic_stat_download_done)
            .setContentTitle("Download completed")
            .setContentText(completionText(episodeTitle, audio))
            .setGroup(GROUP)
            .setContentIntent(contentIntent(episodeId))
            // `Replace file` (DECISIONS 152): this action ROUTES and nothing
            // else — it carries no download, no path and no state, so tapping
            // it can never enqueue work or touch an export. Everything
            // destructive happens later, behind the in-app confirmation.
            .addAction(
                R.drawable.ic_stat_download_done,
                ACTION_REPLACE_FILE_LABEL,
                replaceActionIntent(episodeId),
            )
            .setAutoCancel(true)
            .build()

    /**
     * Terminal FAILURE notification.
     *
     * Channel [NotificationChannels.DOWNLOAD_FAILURE] (`DEFAULT`); every other
     * rule matches [buildCompletionNotification]. The failure MESSAGE is never
     * rendered here — only the title, the audio label and the typed category
     * (see [failureText]).
     */
    fun buildFailureNotification(
        episodeId: String,
        episodeTitle: String,
        audio: String? = null,
        category: FailureCategory? = null,
        /**
         * `Replace file` (DECISIONS 152) — set only when the row behind this
         * failure went back to `COMPLETED` because a CONFIRMED replacement
         * produced nothing while its previous export survived. The clause then
         * keeps notification and screen telling the same story about one row.
         */
        replacementKeptExisting: Boolean = false,
    ): Notification =
        NotificationCompat.Builder(context, NotificationChannels.DOWNLOAD_FAILURE)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle("Download failed")
            .setContentText(
                failureText(episodeTitle, audio, category) +
                    (if (replacementKeptExisting) REPLACEMENT_KEPT_EXISTING_SUFFIX else ""),
            )
            .setGroup(GROUP)
            .setContentIntent(contentIntent(episodeId))
            .setAutoCancel(true)
            .build()

    /**
     * Slice C — the ONE aggregated batch-failure notification.
     *
     * Everything an episode failure notification does, it does too: the same
     * `DOWNLOAD_FAILURE` channel (a fifth channel would change the shipped
     * channel set), the same [GROUP] so it stacks under downloads, and the same
     * `navigate_to="downloads"` tap destination.
     *
     * Post-audio slice — it now also names the failure REASONS in aggregate:
     * `reasonCounts` is a label→count breakdown of the TYPED categories, capped
     * at [REASON_SUMMARY_LIMIT], so a run that failed three times the same way
     * says so in the one line the user actually reads. What it still never
     * carries is a PER-EPISODE reason or any raw error text — the detailed
     * per-episode reasons belong to the screen the user was just on, and a raw
     * error may quote a stream URL (AGENTS §10).
     *
     * Built as pure construction so tests can assert on the object before any
     * posting decision is made.
     *
     * @see reasonSummaryText for how the breakdown is ordered, capped and kept
     *   free of anything the code cannot prove.
     */
    fun buildBatchFailureNotification(
        failed: Int,
        total: Int,
        animeTitle: String,
        reasonCounts: Map<String, Int> = emptyMap(),
    ): Notification =
        NotificationCompat.Builder(context, NotificationChannels.DOWNLOAD_FAILURE)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle("Batch download failed")
            .setContentText(batchSummaryText(failed, total, animeTitle, reasonCounts))
            .setGroup(GROUP)
            .setContentIntent(batchContentIntent())
            .setAutoCancel(true)
            .build()

    /**
     * Slice C — ensure the channels and post, REPLACING any previous batch
     * summary (fixed [BATCH_FAILURE_NOTIFICATION_ID]).
     *
     * The caller owns every decision about whether to post at all — the
     * `notify_failure` preference, the "did anything actually fail" gate, and
     * the capacity-abort and cancellation exclusions all live in the ViewModel.
     * This method only builds and posts.
     */
    fun postBatchFailureNotification(
        failed: Int,
        total: Int,
        animeTitle: String,
        reasonCounts: Map<String, Int> = emptyMap(),
    ) {
        NotificationChannels.ensure(notificationManager)
        notificationManager.notify(
            BATCH_FAILURE_NOTIFICATION_ID,
            buildBatchFailureNotification(failed, total, animeTitle, reasonCounts),
        )
    }

    /**
     * Slice C — the tap-to-open intent for the aggregated batch notification.
     *
     * The SAME destination and flags as [contentIntent]; the request code is the
     * dedicated [BATCH_CONTENT_REQUEST_CODE] (PB-001) — FIXED (like the batch
     * notification id) rather than a per-episode hash, so the summary can never
     * inherit or clobber an episode's intent, AND never collide with the media
     * session activity's own request code.
     */
    private fun batchContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, TARGET_DOWNLOADS)
        }
        return PendingIntent.getActivity(
            context,
            BATCH_CONTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Update an existing notification with new progress.
     * Also the single post entry point for terminal notifications.
     */
    fun updateNotification(notificationId: Int, notification: Notification) {
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Cancel a notification.
     */
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /**
     * Generate a stable notification ID from episode ID.
     * Unchanged Phase 10 contract: stable and POSITIVE (asserted by
     * `NotificationTest`).
     */
    fun notificationIdFor(episodeId: String): Int {
        return episodeId.hashCode() and 0x7FFFFFFF // Ensure positive
    }

    /**
     * The terminal id for an episode =
     * `notificationIdFor(episodeId) or 0x80000000` — always NEGATIVE.
     *
     * The terminal notification replaces the progress notification of the SAME
     * episode (`cancelNotification(progressId)` immediately followed by
     * `updateNotification(terminalId)` — sequential in one coroutine), so they
     * are never both visible. A distinct negative id makes the terminal
     * notification immune to any WorkManager foreground-notification teardown
     * (that can only remove the id it posted) while staying collision-free
     * against every progress id (all progress ids are positive).
     */
    fun terminalIdFor(episodeId: String): Int {
        return notificationIdFor(episodeId) or Int.MIN_VALUE
    }

    /**
     * The single tap-to-open destination for every episode notification (§7).
     *
     * Progress, completion and failure deliberately share ONE request code
     * ([requestCodeFor]) with the IDENTICAL `"downloads"` extras, so
     * `FLAG_UPDATE_CURRENT` can never cross-contaminate two different
     * destinations (they are the same destination anyway).
     */
    private fun contentIntent(episodeId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, TARGET_DOWNLOADS)
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(episodeId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * `Replace file` (DECISIONS 152) — the PendingIntent behind the action.
     *
     * Four deliberate properties:
     *
     * * **It only ROUTES.** The intent carries exactly one extra: the §19
     *   single-string target `replace:<episodeId>`. No stream URL, cookie,
     *   token or local path ever enters it (AGENTS §10), and nothing is
     *   enqueued, deleted or overwritten from the notification — the in-app
     *   dialog is the only place a replacement can be confirmed.
     * * **`FLAG_IMMUTABLE`**, so the action's payload can never be rewritten
     *   after it is posted.
     * * **A distinct `data` URI.** PendingIntent identity is the request code
     *   plus the intent's action/data/type/class/categories — EXTRAs are NOT
     *   part of it. A replace intent sharing [contentIntent]'s request code
     *   would therefore BE that intent and open plain Downloads instead of the
     *   confirmation. `pahebatcher://replace/<episodeId>` (built with
     *   `appendPath`, so the identifier is percent-encoded whatever characters
     *   it holds) makes replace and content intents never filter-equal, while
     *   two different episodes are never equal to each other either.
     * * **The stable validated episode id only** — the row the notification was
     *   built for, never a title, so a renamed episode can still be routed and
     *   two same-titled episodes can never be confused.
     */
    private fun replaceActionIntent(episodeId: String): PendingIntent {
        val data = Uri.Builder()
            .scheme("pahebatcher")
            .authority("replace")
            .appendPath(episodeId)
            .build()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            this.data = data
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.replaceTarget(episodeId))
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(episodeId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
