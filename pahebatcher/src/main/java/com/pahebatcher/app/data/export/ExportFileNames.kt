package com.pahebatcher.app.data.export

import com.pahebatcher.app.data.hls.FileNameGenerator
import com.pahebatcher.app.domain.util.StringUtils

/**
 * Export-layer leaf-name rules (Slice A — per-audio export identity).
 *
 * The DEFECT this object exists to fix: before Slice A the EXPORTED leaf name
 * was the plain [FileNameGenerator] output (`Ep_001_-_Title.mp4`) for every
 * audio variant of an episode, while the Room identity
 * (`EpisodeId` = `{session}_{ep}_{audio}`) and the private segment directory
 * were already audio-specific. Two audio variants of one episode therefore
 * exported to the SAME destination name, and the MediaStore exporter's
 * exactly-one-row rule (§36-7) deleted the other variant's visible file —
 * SPEC §13 "Do not delete user media accidentally" was violated.
 *
 * Contract (deliberately minimal and export-layer ONLY):
 *
 *  - [exportedName] is the destination leaf name for every NEW export. It is a
 *    pure function of (episode number prefix, episode title, episode audio) —
 *    it reads no other state, so **export order can never affect the name**.
 *    For production-shaped audio codes (`^[a-z0-9]{1,16}$`, the shape gate
 *    [com.pahebatcher.app.domain.download.EpisodeId] enforces) the sanitizer
 *    below is the identity, so two different audio values always yield two
 *    different names. Values OUTSIDE that gate can collapse under
 *    sanitization (`j[p]n` → `jpn`) — impossible for a production row, and the
 *    reason the guarantee is stated for gated input only.
 *  - The audio token is sanitized with the same keep-set the rest of the
 *    naming uses (`StringUtils.sanitize` — letters/digits/underscore/whitespace/
 *    `-`/`.` survive; `[`, `]`, `/`, `:` … are stripped) and is then wrapped in
 *    literal `[` `]`. Production audio values are open-set lowercase codes
 *    (`^[a-z0-9]{1,16}$` per [com.pahebatcher.app.domain.download.EpisodeId]),
 *    so the token carries no punctuation of its own.
 *  - A blank (or punctuation-only) audio value yields the legacy name unchanged
 *    rather than a dangling `[]` suffix. Production rows cannot reach that
 *    branch — `AnimePaheParser` defaults a missing audio to `jpn` — but the
 *    fallback keeps the function total and deterministic.
 *  - [legacyName] is exactly today's Python-parity
 *    [FileNameGenerator.episodeFileName] output: the name every export created
 *    BEFORE Slice A. Both exporters use it as the LEGACY ALIAS lookup name so
 *    an existing unsuffixed file can be size-verified and reused (never
 *    deleted, renamed or migrated).
 *
 * What this object deliberately does NOT do: it never touches
 * [FileNameGenerator] (local staging/assembly/resume identity is unchanged —
 * the private per-episodeId directory already isolates audio variants) and it
 * performs no sanitization of its own beyond delegating to the shared
 * sanitizer. Verification of an existing destination file is SIZE-based
 * everywhere it is reused; no byte-identity claim is made anywhere.
 */
object ExportFileNames {

    /**
     * The pre-Slice-A (Python-parity) leaf name — the legacy alias every
     * exporter must still recognize.
     */
    fun legacyName(prefix: String, title: String): String =
        FileNameGenerator.episodeFileName(prefix, title)

    /**
     * Deterministic audio-specific destination leaf name for a NEW export.
     *
     * Example: `exportedName("001", "Title", "jpn")` →
     * `Ep_001_-_Title_[jpn].mp4`.
     *
     * @param prefix episode number prefix ([FileNameGenerator.formatEpisodePrefix])
     * @param title episode title
     * @param audio the episode row's own audio value (open-set)
     */
    fun exportedName(prefix: String, title: String, audio: String): String {
        val base = legacyName(prefix, title)
        val token = StringUtils.sanitize(audio)
        if (token.isEmpty()) return base
        return base.removeSuffix(".mp4") + "_[$token].mp4"
    }
}
