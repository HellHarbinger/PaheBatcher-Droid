package com.pahebatcher.app.notification

/**
 * The two episode-terminal
 * notification preferences.
 *
 * The flags gate **posting** only, never sound/importance (importance is fixed
 * at channel creation and lives in system Settings — PHASE14_PLAN §21 P14-L4).
 * Consumers read them FRESH at the moment they post (DECISIONS 105.4 read-fresh
 * contract), so a Settings change applies to the next notification rather than
 * to an in-flight run.
 *
 * The defaults are `true`/`true`: they are the DataStore defaults
 * (`notify_completion` / `notify_failure`, both ON), so a composition that has
 * not been wired to `AppConfig` yet behaves exactly like the shipped default.
 *
 * The watchlist flag (`notify_watchlist`) is deliberately NOT part of this
 * value: it is read by `WatchlistCheckWorker`, not by the episode Worker
 * (PHASE14_PLAN §18 supplies it through the same factory chain).
 */
data class DownloadNotifyPrefs(
    val completion: Boolean = true,
    val failure: Boolean = true,
)
