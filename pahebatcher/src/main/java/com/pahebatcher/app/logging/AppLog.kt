package com.pahebatcher.app.logging

import android.util.Log
import androidx.compose.runtime.Immutable
import com.pahebatcher.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Severity of a buffered log entry — mirrors the [android.util.Log] levels the
 * application actually uses.
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * One application log record as shown by the diagnostics Console.
 *
 * @param sequence Monotonic per-buffer sequence number (ordering tie-breaker
 *   when two entries share a timestamp).
 * @param timestampMs Wall-clock time the record was emitted.
 * @param level Severity.
 * @param tag Source tag (the same tag Logcat receives).
 * @param message Human-readable message (already truncated by [LogBuffer]).
 * @param throwable Sanitized rendered stack trace, or `null` when no exception
 *   was attached. Rendered with [Throwable.stackTraceToString] (pure Kotlin, so
 *   it behaves identically on the JVM and on device), then passed through
 *   [LogUrlSanitizer.diagnostic] so a URL-bearing exception message or cause
 *   chain cannot bypass call-site sanitization, and truncated by
 *   [LogBuffer.MAX_THROWABLE_CHARS] like the message.
 */
@Immutable
data class LogEntry(
    val sequence: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null,
)

/**
 * Bounded in-memory ring buffer of recent application log records (Phase 12A
 * diagnostics Console).
 *
 * Deliberate limits (DECISIONS: bounded in-memory log retention):
 * - fixed [capacity] — oldest entries are evicted, so retention is O(capacity)
 *   regardless of how much the application logs;
 * - [MAX_MESSAGE_CHARS] truncation per message AND per rendered [LogEntry.throwable]
 *   (a stack trace can be arbitrarily long, so both fields are bounded — total
 *   retention is O(capacity × (MAX_MESSAGE_CHARS + MAX_THROWABLE_CHARS)));
 * - no disk/Room persistence at all: the Console is a diagnostic surface, not a
 *   log database, and process death legitimately clears it.
 *
 * Thread-safety: appends can originate from any dispatcher (Workers, WebView
 * callbacks, UI). Mutations are synchronized and the published snapshot is an
 * immutable List, so collectors never observe a torn state.
 */
class LogBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    private val lock = Any()
    private val ring = ArrayDeque<LogEntry>(capacity)
    private var nextSequence = 0L

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Immutable snapshot of the retained entries, oldest first. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /**
     * Append one record, evicting the oldest entry when at capacity.
     *
     * @return the stored entry (its message may be truncated to
     *   [MAX_MESSAGE_CHARS]).
     */
    fun append(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): LogEntry {
        val stored = LogEntry(
            sequence = 0L,
            timestampMs = timestampMs,
            level = level,
            tag = tag,
            message = truncate(message, MAX_MESSAGE_CHARS),
            throwable = throwable?.let { truncate(it, MAX_THROWABLE_CHARS) },
        )
        synchronized(lock) {
            val sequenced = stored.copy(sequence = nextSequence++)
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(sequenced)
            _entries.value = ring.toList()
            return sequenced
        }
    }

    /** Remove every retained entry (the Console's clear action). */
    fun clear() {
        synchronized(lock) {
            ring.clear()
            _entries.value = emptyList()
        }
    }

    /** Current number of retained entries (never exceeds [capacity]). */
    fun size(): Int = synchronized(lock) { ring.size }

    /** Truncate to [limit] chars, marking the cut so truncation is visible. */
    private fun truncate(value: String, limit: Int): String =
        if (value.length > limit) value.substring(0, limit) + "…(truncated)" else value

    companion object {
        /**
         * Default retention: 500 entries. A diagnostics surface only needs the
         * recent past; 500 short records is a small fixed cost and bounds the
         * Console list so recomposition stays cheap.
         */
        const val DEFAULT_CAPACITY = 500

        /** Per-message truncation bound (characters). */
        const val MAX_MESSAGE_CHARS = 4000

        /**
         * Per-stack-trace truncation bound (characters). Rendered traces are
         * bounded with the same discipline as messages so an exception with a
         * deep/looping cause chain cannot pin unbounded memory.
         */
        const val MAX_THROWABLE_CHARS = 4000
    }
}

/**
 * Shared application logging facade (Phase 12A).
 *
 * Every production call site logs through this object instead of calling
 * [android.util.Log] directly, so one line of code feeds BOTH sinks:
 *
 *   application code → [AppLog] → Logcat (always) + bounded in-memory
 *   [LogBuffer] (only while `Show Console` is ON; rendered by the optional
 *   diagnostics Console)
 *
 * Invariants:
 * - Diagnostic (DEBUG/INFO) output is GATED by [isDiagnosticsLoggingEnabled],
 *   which defaults to `BuildConfig.DEBUG` (SPEC §33: "have debug logging
 *   disabled/restricted in release"). WARN and ERROR are never suppressed, so
 *   failure reporting is identical in debug and release. The gate is applied
 *   ONCE here, before either sink, so a suppressed diagnostic line reaches
 *   neither Logcat nor the Console buffer.
 * - Disabling the Console never disables normal logging: Logcat output is
 *   otherwise unconditional.
 * - Buffering is GATED by the persisted `Show Console` setting (DECISIONS 86:
 *   "every line routes to Logcat always and to `LogBuffer` only when the buffer
 *   is enabled"). While the setting is OFF, new lines are not retained at all —
 *   turning it off drops future lines AND the buffer's retention — and turning
 *   it back on starts a fresh buffer. The gate is driven in production by
 *   `PaheBatcherApplication`, which collects `AppConfig.showConsole`.
 * - The buffer is bounded ([LogBuffer.DEFAULT_CAPACITY]); enabling or opening
 *   the Console does not change how much is retained, and the Console is never
 *   a dependency of any download/background component — a download behaves
 *   identically whether the Console is shown or hidden.
 * - Nothing here inspects or transforms message CONTENT: call sites remain
 *   responsible for never logging cookies, tokens, authorization headers or
 *   other secrets (AGENTS §10). New Phase 12A call sites log setting NAMES and
 *   non-sensitive values only.
 *
 * Log-content rule for call sites (Phase 12A review M7): because buffered lines
 * are rendered ON SCREEN by the Console (and retained in RAM until Clear or
 * process death) — not only in Logcat — new call sites must log
 *
 * - cookie NAMES only, never values; never tokens, authorization headers or
 *   User-Agents (AGENTS §10);
 * - expiring stream/segment URLs only when needed for diagnosis; prefer the
 *   host + path prefix over a full signed URL;
 * - nothing at all from a page URL that carries a session/query token.
 *
 * The buffer is memory-only and cleared on process restart — it is never
 * persisted to Room or to disk.
 */
object AppLog {

    /** The process-wide bounded buffer backing the diagnostics Console. */
    val buffer: LogBuffer = LogBuffer()

    /**
     * SPEC §33 ("have debug logging disabled/restricted in release") gate for
     * the two DIAGNOSTIC levels ([LogLevel.DEBUG] and [LogLevel.INFO]).
     *
     * `true` (debug builds) ⇒ every level is emitted.
     * `false` (release builds) ⇒ DEBUG/INFO are dropped before they reach
     * Logcat or the Console buffer, while WARN/ERROR keep their existing
     * semantics. The default is the compile-time `BuildConfig.DEBUG`, so no
     * call site has to know which variant it is running in, and the ~242
     * existing call sites are unchanged.
     *
     * Written by tests through [setDiagnosticLoggingEnabled]; read on every log
     * call, so the flag is volatile.
     */
    @Volatile
    private var diagnosticLoggingEnabled: Boolean = BuildConfig.DEBUG

    /** Whether DEBUG/INFO lines are currently emitted (release: false). */
    val isDiagnosticLoggingEnabled: Boolean
        get() = diagnosticLoggingEnabled

    /**
     * Test hook for the release-build branch: the JVM unit-test variant is
     * compiled with `BuildConfig.DEBUG = true`, so a deterministic test of the
     * release policy has to be able to move the gate (same pattern as
     * [setBufferingEnabled]). Production never calls this.
     */
    fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        diagnosticLoggingEnabled = enabled
    }

    /**
     * The ONE policy check: WARN/ERROR always emit; DEBUG/INFO only while the
     * diagnostic gate is open. Consulted before either sink, so a suppressed
     * line can never reach Logcat or the buffer indirectly.
     */
    private fun emit(level: LogLevel): Boolean =
        level == LogLevel.WARN || level == LogLevel.ERROR || diagnosticLoggingEnabled

    /**
     * `Show Console` OFF means lines are NOT retained.
     * Written by the Application's `showConsole` collector (and by tests through
     * [setBufferingEnabled]); read on every log call, so the flag must be volatile.
     */
    @Volatile
    private var bufferingEnabled: Boolean = false

    /**
     * Serializes the gate itself: the flag write + retention clear in
     * [setBufferingEnabled] and the enabled-check + append in [appendIfEnabled],
     * so a disable can never interleave with an in-flight append (reviewer M1).
     */
    private val gateLock = Any()

    /** Whether new lines are currently retained in [buffer] (`Show Console` ON). */
    val isBufferingEnabled: Boolean
        get() = bufferingEnabled

    /**
     * Turn retention on or off. Disabling drops future lines AND clears what was
     * retained (DECISIONS 86: "Turning `Show Console` off drops future lines and
     * the buffer's retention"). Logcat is unaffected either way.
     *
     * The flag write and the clear run in ONE [gateLock] section, and every
     * gated append takes the same lock — so once this returns with `false`, no
     * in-flight append can still land in the buffer afterwards (the disabled
     * buffer really retains nothing, even under concurrent logging).
     */
    fun setBufferingEnabled(enabled: Boolean) {
        synchronized(gateLock) {
            if (bufferingEnabled == enabled) return
            bufferingEnabled = enabled
            if (!enabled) buffer.clear()
        }
    }

    /**
     * Gated append: the enabled check, the append and a concurrent
     * [setBufferingEnabled] disable+clear are serialized by [gateLock].
     */
    private fun appendIfEnabled(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: String? = null,
    ) {
        synchronized(gateLock) {
            if (bufferingEnabled) buffer.append(level, tag, message, throwable)
        }
    }

    fun d(tag: String, message: String) {
        if (!emit(LogLevel.DEBUG)) return
        Log.d(tag, message)
        appendIfEnabled(LogLevel.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        if (!emit(LogLevel.INFO)) return
        Log.i(tag, message)
        appendIfEnabled(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        if (!emit(LogLevel.WARN)) return
        Log.w(tag, message)
        appendIfEnabled(LogLevel.WARN, tag, message)
    }

    fun e(tag: String, message: String) {
        if (!emit(LogLevel.ERROR)) return
        Log.e(tag, message)
        appendIfEnabled(LogLevel.ERROR, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (!emit(LogLevel.ERROR)) return
        // ONE sanitized rendering feeds BOTH sinks, so Logcat (world-readable
        // through adb) can never see a fuller trace than the Console does.
        val rendered = renderThrowable(throwable)
        Log.e(tag, "$message\n$rendered")
        appendIfEnabled(LogLevel.ERROR, tag, message, rendered)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (!emit(LogLevel.WARN)) return
        val rendered = renderThrowable(throwable)
        Log.w(tag, "$message\n$rendered")
        appendIfEnabled(LogLevel.WARN, tag, message, rendered)
    }

    /**
     * Pure-Kotlin stack-trace rendering (JVM-testable, never returns null),
     * then URL-sanitized: `stackTraceToString` reproduces the throwable's own
     * `toString()` line AND every `Caused by:` message verbatim, so an
     * exception whose message (or whose cause's message, or whose frame
     * metadata) quotes a complete request URL would otherwise bypass the
     * call-site sanitization and reach release-emitted WARN/ERROR logs raw.
     * Exception type, message text and frames are preserved — only URLs inside
     * the rendering are rewritten to host+path (AGENTS §10, DECISIONS 136).
     */
    private fun renderThrowable(throwable: Throwable): String =
        LogUrlSanitizer.diagnostic(
            runCatching { throwable.stackTraceToString() }
                .getOrElse { throwable.toString() },
        )
}
