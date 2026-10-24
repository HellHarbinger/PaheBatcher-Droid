package com.pahebatcher.app.data.hls

/**
 * The ONE typed error seam.
 *
 * Before this enum a [EpisodeDownloader.DownloadResult] carried only
 * `error: String`, and the in-run episode loop re-matched that text
 * (`error.contains("HTTP 403")` / `"No segments"` / …) while `DownloadWorker`
 * had no type at all to base a decision on. Every failure now carries the class
 * it was CONSTRUCTED with, decided at the construction site by ORIGIN — never
 * by message text — and both consumers read only this field.
 *
 * Consumed by exactly two sites (§14):
 *
 * 1. the in-run episode loop — [EXPIRED], [NO_CONTENT], [ASSEMBLY] and
 *    [DISK_FULL] are permanent and end the episode retry budget immediately;
 * 2. `DownloadWorker` — only [TRANSPORT] may ever become `Result.retry()`
 *    (§10/§15). Worker-originating failures (no stream info, lost CAS, an
 *    exception escaping the worker) never travel through this enum.
 *
 * `DownloadResult.error: String` is unchanged: the persisted `errorMessage`
 * and the Downloads-screen text stay byte-for-byte the same, so this field is
 * purely additive. Cancellation keeps its separate typed path
 * ([EpisodeDownloader.DownloadResult.wasCancelled]) and is never a
 * [DownloadErrorClass] decision.
 *
 * The string set the in-run loop used to test is reproduced here exactly, so
 * every existing outcome is unchanged:
 *
 * | Class | Was matched by |
 * |---|---|
 * | [NO_CONTENT] | `"No segments"`, `"Master playlist has no variants"` |
 * | [EXPIRED] | `"HTTP 403"`, `"HTTP 404"` |
 * | [ASSEMBLY] | `assemblyError != null` |
 * | [DISK_FULL] | (new, final-audit H2) local ENOSPC / pre-flight free-space refusal |
 * | [TRANSPORT], [IN_RUN_RETRYABLE] | everything else (retried in-run) |
 */
enum class DownloadErrorClass {

    /** Success — no error; [EpisodeDownloader.DownloadResult.success] is `true`. */
    NONE,

    /**
     * Network-origin failure: any non-403/404 HTTP status (400/401/429/5xx)
     * exhausted after the in-run5 HTTP attempts, plus timeout/DNS/connection
     * failures (`OkHttpHlsClient` converts every network exception to
     * `NetworkError`, so the classes below are never produced by a socket
     * problem). Retried in-run AND the only class eligible for auto-resume.
     */
    TRANSPORT,

    /** HTTP 403/404 — stream/key/segment gone. Permanent inside a run. */
    EXPIRED,

    /**
     * `"No segments in playlist"` / `"Master playlist has no variants"` —
     * the parsed playlist has nothing to download. Permanent inside a run.
     */
    NO_CONTENT,

    /** `assemblyError != null` — deterministic FFmpeg failure. Permanent. */
    ASSEMBLY,

    /**
     * Final-audit H2 — **not enough free storage** (SPEC §26 `DiskFull`,
     * SPEC §13 "insufficient storage"). Produced ONLY by local storage
     * origins, never by a network reply:
     *
     * * a segment write that ran out of space (the OS reports ENOSPC through
     *   [java.io.IOException], matched once by [StorageSpace.isNoSpaceLeft] at
     *   the construction site);
     * * the pre-flight free-space gate, which refuses to start when the
     *   volume cannot hold the estimated remaining segments.
     *
     * Permanent inside a run: waiting does not give the disk space back, so
     * the episode retry budget must not burn attempts (and this class is
     * never auto-resume eligible — `DownloadWorker.autoResumeEligible` only
     * ever accepts [TRANSPORT]).
     */
    DISK_FULL,

    /**
     * A local or unexpected in-run failure: playlist parse, missing key,
     * decryption, file I/O. The episode retry budget may retry it, but it must
     * never be mistaken for a connectivity problem, so it is never
     * auto-resume eligible.
     */
    IN_RUN_RETRYABLE,
}
