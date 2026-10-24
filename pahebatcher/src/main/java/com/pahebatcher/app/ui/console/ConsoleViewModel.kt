package com.pahebatcher.app.ui.console

import androidx.lifecycle.ViewModel
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 12A diagnostics Console ViewModel.
 *
 * A thin, read-only projection of the process-wide bounded [AppLog.buffer]:
 *
 * - [entries] is the buffer's own StateFlow — the Console observes, it never
 *   produces, stores, or persists logs itself (no Room, no file, no second
 *   buffer), so retention stays exactly [com.pahebatcher.app.logging.LogBuffer.DEFAULT_CAPACITY]
 *   entries regardless of whether the Console is open;
 * - [clear] empties that same buffer (the Console's only mutation, matching the
 *   "process-local, cleared on restart" retention contract).
 *
 * The Console is NOT a dependency of any download/background component: nothing
 * outside this ViewModel reads it, and a download behaves identically with the
 * Console open, closed, or never enabled.
 */
class ConsoleViewModel : ViewModel() {

    /** Retained log records, oldest first (bounded, in-memory only). */
    val entries: StateFlow<List<LogEntry>> = AppLog.buffer.entries

    /** Remove every retained record. */
    fun clear() {
        AppLog.buffer.clear()
    }
}
