# PROJECT_REFERENCE.md — Orientation and Consolidated Reference

> **Role:** single, concise, maintained orientation reference for the PaheBatcher Android repository.
> **Not a specification, decision log, roadmap, parity record, or limitations record.** Where a
> protected document is authoritative, this file **points to it instead of duplicating it**.
> Created 2026-10-22 by a documentation-consolidation review of the historical phase documents.

**Authority hierarchy (binding, from `AGENTS.md`):**
`AGENTS.md` > `SPEC.md` > current source + passing tests (test evidence ≠ device evidence) >
`DECISIONS.md` > `ROADMAP.md` > `PARITY.md` > `LIMITATIONS.md` > architecture/phase reports
(historical unless designated current).

---

## 1. Project purpose and current status

- **Purpose:** native Android implementation of PaheBatcher reproducing the Python reference
  (`https://github.com/smolfiddle/pahebatcher`) behavior with Android-native mechanisms, per `SPEC.md`.
  Three sources of truth and the conflict-resolution rule: `SPEC.md:13-42`.
- **Canonical sources:** purpose/entry handoff → `ANDROID_AI_HANDOFF.md`; product requirements →
  `SPEC.md`; process rules (binding) → `AGENTS.md`.
- **Current status:** all implementation phases through the 2026-10-21 closure rounds are recorded
  COMPLETE/CLOSED in `ROADMAP.md` (see §10). The application builds, the JVM test gate, lint gate,
  and citation gate pass as of the last recorded round (`ROADMAP.md:2194-2206`).
- **Not 100% parity:** `PARITY.md` is authoritative for verified parity and intentional deviations;
  open gaps are listed in §4 and §8 below.

## 2. Current architecture and component ownership

**Canonical source:** `ANDROID_ARCHITECTURE.md` (layer diagram at `:44-73`, package ownership table
at `:116-143`). Data/schema ownership: `ANDROID_DATA_AND_STATE.md`.

- Single Gradle module `app`, package `com.pahebatcher.app`; source sets `main` (146 .kt),
  `test` (114), `androidTest` (110), `debug` (7, validation spikes), `release` (1).
- **Layering is UI → ViewModel → domain/use-case role → Repository → domain implementation →
  platform engine**, as required by `AGENTS.md` §4. The use-case role is embodied by domain classes
  (e.g. `domain/watchlist/WatchlistChecker.kt:135` — the one shared use case for the Watchlist
  screen and `WatchlistCheckWorker`), not a separate `usecase/` package.
- **Manual DI (no Hilt/Koin):** `di/AppContainer.kt:82`, ViewModels via `di/AppViewModelFactory.kt:32`,
  worker deps via `worker/DownloadWorkerFactory.kt:38`; owned by `PaheBatcherApplication`
  (implements `WorkManager.Configuration.Provider`).
- **Networking:** `HttpClient` interface (`data/remote/HttpClient.kt:10`); production transport is
  `data/session/WebViewHttpClient.kt:30` through the WebView page lock (Cloudflare surface); OkHttp
  is reserved for non-Cloudflare HLS/CDN (`data/hls/OkHttpHlsClient.kt`, one app-scoped client).
- **Session:** `data/session/AndroidCloudflareSession.kt` (single non-window WebView, states
  NOT_STARTED→ESTABLISHING→ESTABLISHED with EXPIRED/FAILED); `AndroidSessionPersistence` stores
  metadata only — no raw cookies are persisted.
- **Database:** Room `data/local/PaheDatabase.kt:43-54` — **version 4**, `exportSchema = true`,
  5 entities, 5 DAOs, migrations 1→2→3→4, no destructive fallback.
- **Download engine:** `worker/DownloadWorker.kt:59` (CoroutineWorker, FGS `dataSync`, CAS entry
  claim) drives `data/hls/EpisodeDownloader.kt:49` (bounded semaphore, consumes already-resolved
  `StreamInfo`); foreground orchestration + admission gate in `data/download/DownloadRepository.kt`.
- **Playback:** Media3 1.11.1; `ui/player/PlayerViewModel.kt` owns the single ExoPlayer;
  `playback/PaheMediaSessionService.kt:51` + `playback/MediaSessionHost.kt` provide the media
  session/notification path.
- **Scheduling:** `worker/WatchlistCheckScheduler.kt:36` (periodic, ≥15 min, CONNECTED) +
  `worker/WatchlistCheckWorker.kt:85`.

## 3. Main production data and execution flows

**Canonical source:** `ANDROID_ARCHITECTURE.md` (flows) and `ANDROID_USER_FLOWS.md` (20 flows).

- **Search:** `HomeViewModel` (350 ms debounce) → `AnimeRepository.search()` → `WebViewHttpClient`
  → WebView bridge → Cloudflare session.
- **Scan/parse:** `EpisodeRepository` (cache-first; forced fresh for watchlist/stream) +
  `ScanCacheRepository` (Room TTL cache) + `AnimePaheParser`; lazy paging via
  `domain/util/EpisodePaging.kt` (100-episode app pages over 30-row server windows, DECISIONS 153).
- **Stream resolution:** `StreamResolverImpl` — play page → `PlayPageParser` → `QualitySelector` +
  `AudioSelector` (exact open-set audio, no substitution) → `KwikResolver`/`JsPacker` →
  `M3u8Extractor` → `StreamInfo`; serialized by the shared WebView page lock.
- **HLS download:** `DownloadRepository` persists `StreamInfo` + QUEUED row → unique Work
  `episode_download_<id>` with `ExistingWorkPolicy.KEEP`, enqueued from exactly 3 sites →
  `DownloadWorker` (maxParallel gate → CAS claim → progress notification) → `EpisodeDownloader`
  (playlist → bounded segment fetch → per-segment AES-128 → atomic `.tmp`→`.ts` writes).
- **MP4 assembly + export:** `FfmpegEpisodeAssembler` (FFmpegKit remux; single contiguous MPEG-TS
  primary, concat demuxer fallback — PARITY.md:311-312) → `EpisodeOutputVerifier` (byte-verified) →
  `ExportDestinationRouter` → `MediaStoreExporter` (Movies/…, IS_PENDING ordering) or `SafExporter`
  (part-file → rename).
- **Playback:** local (outputUri/outputPath) or STREAM mode (`StreamEpisodeReconstructor` → JIT
  resolve → `HlsPlaybackSourceFactory`).
- **Startup reconciliation:** once per process (`AtomicBoolean`), `DownloadStateReconciler`
  normalizes/re-enqueues non-terminal rows.

## 4. Current feature availability and known gaps

**Canonical source:** `ANDROID_FEATURE_CATALOG.md` (22 features, evidence tiers at `:12-24`,
self-dated 2026-10-18) and `PARITY.md`.

**Available** (catalog, with tiered evidence): startup/home, search, anime details, lazy pagination,
range/batch selection, per-episode audio selection, individual + batch download, stream-resolution
sheet, progress/resume (incl. PAUSED), assembly + output verification, MediaStore export, SAF custom
folder, Replace file, duplicate/destination-probe suppression, sessions screen, notifications
(progress/complete/failure/batch-failure), session refresh prompt, in-app player, watchlist,
settings (18 rows), process-death reconciliation.

**Evidence tiers — do not conflate:**
- "VERIFIED (JVM tests)" = unit-test evidence only.
- "VERIFIED (instrumented, device REDACTED-SERIAL)" / "live device journey" = physical-device evidence,
  recorded in `ANDROID_FEATURE_CATALOG.md` and `ANDROID_TESTING_AND_RELEASE.md:171-232`.
- Emulator cannot exercise site flows (Cloudflare blocks it — PARITY.md:996).

**Known gaps (current):**
- In-player audio/quality track switching NOT implemented; multi-track playback not claimed or
  tested (PARITY.md:1006-1007).
- Live dual-audio same-episode sample not stageable upstream (PARITY.md:1008; LIMITATIONS §35.5/§41).
- Audio-option discovery probe and Slice C failure reasons are test-verified only, not verified
  against live AnimePahe (PARITY.md:999, :1010-1011; LIMITATIONS §43.4).
- LIMITATIONS §24 residuals (expired-stream recovery, findings 2–3/R3–R6) open (PARITY.md:1015).
- Open product decision: no delete/force-redownload affordance for a COMPLETED row whose only
  output is app-private (`ROADMAP.md:1718`).
- 7 inventory findings `DEFERRED — USER DECISION REQUIRED` (NET-002, NET-006, NET-012, TEST-006,
  AQR-045, AQR-046, FR-05) — `PHASE_A_FINDING_INVENTORY.md:341`.
- Record-only follow-ups TEST-012 (de-localize gradle JDK/SDK pins) and TEST-014 (R8 release
  minification) — `ROADMAP.md:2115-2116`.

## 5. Important architectural invariants

**Canonical sources:** `ANDROID_ARCHITECTURE.md:613-643` (8 constraints), `ANDROID_DATA_AND_STATE.md:357-390`
(12 test-pinned invariants), plus `AGENTS.md` rules 4–13 and 17. Summary (pointers, not copies):

1. Cloudflare-protected requests only via the WebView session; OkHttp never bypasses it.
2. Stream URLs are inputs, never identity — `beginRetry` clears `m3u8Url`/`userAgent`/`referer`;
   `playUrl` is the durable source; credentials are never authoritative.
3. Room is the only authoritative download state; every state write is a compare-and-set.
4. UI performs no HTTP/HLS/Room/WorkManager/Cloudflare work (AGENTS §4).
5. Exports are atomic and idempotent (pending→verify→visible; part-file→rename); user media is
   never deleted on assumption; local MP4 never removed before byte-verified persistence.
6. Bounded concurrency and memory (per-host semaphores, 64 KiB buffers); incomplete segments never
   count as completed; segment writes atomic.
7. Cancellation is not failure; PAUSED survives cancellation + reconciliation.
8. No fake data/progress/notifications/TODO in production code (AGENTS §5).
9. Long-running work runs under WorkManager/foreground services with persistent state (AGENTS §13).
10. Never log cookies, authorization, tokens, or credentials (AGENTS §10); dependencies are upgraded
    only with AGENTS §17 compatibility verification.

## 6. Current testing and release workflow

**Canonical source:** `ANDROID_TESTING_AND_RELEASE.md` (self-dated 2026-10-18) + `AGENTS.md` §7/§8/§20.
Where counts of record are needed, cite `ROADMAP.md` (latest round) rather than the handbook snapshot.

- **Toolchain (verified against build files):** JDK 25, Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20,
  KSP 2.3.12, compileSdk/targetSdk 37, minSdk 32 (`gradle/libs.versions.toml`, `pahebatcher/build.gradle.kts:10-17,49`).
- **JVM gate:** `.\gradlew.bat testDebugUnitTest` (`--rerun-tasks` for counts of record). Latest
  counts of record: **114 suites / 1,236 tests / 0 failures / 0 errors / 0 skipped**
  (`ROADMAP.md:2194`, 2026-10-21).
- **Lint gate:** `.\gradlew.bat lintDebug` — 0 errors target; latest 0 errors / 39 warnings / 1 hint
  (`ROADMAP.md:2195`).
- **APKs (per CPU ABI since the 2026-10-24 ABI-split round):** `.\gradlew.bat assembleDebug`
  → `pahebatcher/build/outputs/apk/debug/pahebatcher-arm64-v8a-debug.apk` and `pahebatcher-x86_64-debug.apk`
  (install the split matching the device; historical single-file records above/below
  predate the split).
- **Citation gate:** `powershell -ExecutionPolicy Bypass -File scripts\check-doc-citations.ps1`
  must PASS/exit 0; it scans AGENTS/DECISIONS/LIMITATIONS/ROADMAP/PARITY plus `PHASE11_HANDOFF.md`
  as a cite-only member (`scripts/check-doc-citations.ps1:90-103`; the handoff member now
  resolves at its archived path `archive/phase-reports/PHASE11_HANDOFF.md`, repointed 2026-10-22).
- **Device runs:** NEVER `connectedDebugAndroidTest` on a data-bearing device (it uninstalls both
  APKs and destroys data — `ANDROID_TESTING_AND_RELEASE.md:189-197`). Use `adb -s <serial> install -r
  --user <id>` and direct `am instrument -w -e class <FQCN>`, one class per invocation; never target
  Owner user 0. Physical device of record: serial `REDACTED-SERIAL`, Android Dev secondary user.
- **Deployment (AGENTS §20):** install the latest debug APK to every `device`-state serial with
  `adb -s <serial> install -r`; verify installation; never uninstall or reset app data.
- **Evidence rules:** raw output quotes, APK SHA-256 + `lastUpdateTime`, device serial + user id,
  sanitized logs (`ANDROID_TESTING_AND_RELEASE.md:328-340`).

## 7. Verified Python parity and intentional Android adaptations

**Canonical source:** `PARITY.md` (legend `:3-9`: ✅ parity · 🔄 android implementation · 📱 extension ·
⚠️ required difference · ❌ blocked). Python archaeology: `REPOSITORY_ARCHAEOLOGY.md` (reconciled to
reference 3.7.0). Headline adaptations (pointers only):

- WebView session replaces FlareSolverr/curl_cffi; real WebView UA instead of a hard-coded Chrome UA
  (PARITY.md:478-524; SPEC §6).
- OkHttp restricted to non-Cloudflare HLS/CDN endpoints (PARITY.md:106).
- SAF tree export replaces POSIX `output_dir`; MediaStore `Movies/<title>/` default (PARITY.md:1015).
- WorkManager CoroutineWorker + `setForeground` FGS replaces the endless terminal process
  (LIMITATIONS.md:184-189).
- Media3/ExoPlayer + `MediaSessionService` replaces MPV + local HLS proxy (proxy deliberately not
  built — PARITY.md:690, :1035).
- Per-episode open-set audio selection replaces Python's closed `jpn/eng` fallback (DECISIONS 142/144);
  audio resolved at resolve-time, not in-player (PARITY.md:688).
- `episodeId = {animeSession}_{epStr}_{audio}`; audio-suffixed export leaf names (DECISIONS 145).
- Android-only settings with no Python equivalent: Wi-Fi-only, auto-resume, per-type notification
  prefs, Console, theme/dynamic color (PARITY.md:1015); no CLI — batch panel/Latest N replace
  `--latest`/`--range`.
- Batch retry is a deliberate `ANDROID_REQUIRED_DIFFERENCE` (LIMITATIONS.md:204).

## 8. Current unresolved limitations and external blockers

**Canonical source:** `LIMITATIONS.md` — 53 numbered sections. Do not duplicate text here; the
currently open/conditional areas (by section) are:

- §1–§2B Cloudflare/TLS/Kwik domain infrastructure (external, permanent).
- §3 FFmpeg muxing (+66.5 MB APK cost), §4 background execution, §5 storage access, §6 process death.
- §13 instrumentation package-vanishing, §16 validation screen in release source, §17 poster source
  unverified, §18 no cross-store atomicity, §20 instrumentation cannot force-stop the app,
  §22 player placement-guard heal-window residual (LOW).
- §24 download retry residuals (finding 1 resolved; residuals open), §25 deferred session findings.
- §30/§31/§32 residual Android-vs-Python differences and deferred settings bounds.
- §34.11 Phase 14 accepted limitations (P14-L1..L4), §37 remaining streaming limitations,
  §38 genuinely open SPEC gaps, §41–§49 audio/export/replace-file conditional residuals,
  §52 residual edges from the PB-004/UI-008 round.
- RESOLVED sections retained for history: §15, §21, §23, §26, §28, §29, §36 (streaming — resolved in
  Phase 17 per `LIMITATIONS.md:2343`).
- External blockers: emulator blocked by Cloudflare for site flows; no live dual-audio upstream
  sample; 7 deferred findings require user decision (§4 above).

## 9. Relevant decisions (pointers)

**Canonical source:** `DECISIONS.md` — 8,058 lines, Decisions **1–163** (2026-09-21 → 2026-10-21).
This file intentionally contains **no decision content**. Orientation map only:

| Span | Subject |
|---|---|
| 1–10 | analysis-first approach, architecture mapping, toolchain (Phases 1–2) |
| 11–26 | FFmpegKit choice, WebView/Cloudflare, storage, OkHttp (Phases 3–5) |
| 27–40 | session boundaries, Kwik, HLS engine, Phase 10 corrective cycles |
| 41–69 | Phase 11: theme, Media3, navigation, UI-state contracts, player lifecycle, gates |
| 70–91 | Phase 11.5/12: episode selection, retry re-resolution, settings, background check worker |
| 92–112 | Phase 13 architecture review remediation (storage, submission, sessions, N-1..N-11) |
| 113–121 | Phase 14: auto-resume, retry/backoff, notifications, MediaSession, check frequency |
| 122–141 | Phase 17 streaming, SPEC gap closure (gaps 1–5), release logging, export directory |
| 142–156 | audio source slices A–C, per-audio export identity, paging, replace file, UI reviews |
| 157–163 | Prompt 3 release gate; Phase A/A2/A2.1 audits; closure rounds (2026-10-18 → 10-21) |

Supersessions are recorded inline in `DECISIONS.md` (e.g. 94.2 supersedes the Sessions pause note;
114 supersedes PHASE10 §14; 143/144 supersede parts of 142).

## 10. Current phase status and next approved work

**Canonical source:** `ROADMAP.md` (this section summarizes only; never treat phase reports as status).

- **Phases 1–17.7:** COMPLETE (Phase 5 "IMPLEMENTED — Audit Fixed", Phase 6 "IMPLEMENTED + CORRECTED").
  **Phase 16 is recorded ⛔ INCOMPLETE** (`ROADMAP.md:875`) — its documentation deliverables were
  done but its exit criterion (streaming) was closed by Phase 17; ROADMAP deliberately keeps the
  INCOMPLETE status. Do not report Phase 16 as complete.
- **Phase 11.5, 12A–12D, 13, 14, 15:** COMPLETE (13: 2026-10-02; 14: 2026-10-03/04; 15: 2026-10-04).
- **Closure rounds:** Prompt 3 final release gate (COMPLETE, `:1938`), Phase A (`:1969`), Phase A2
  (`:1996`), Phase A2.1 + closure round (CLOSED 2026-10-21, `:2058`), PB-004 + UI-008 round
  (COMPLETE/VERIFIED, `:2118`), **Closure-gap remediation round — Prompt 1 (CLOSED, `:2148-2206`,
  the last ROADMAP entry)**.
- **Next approved work:** no new implementation phase is approved after Prompt 1. Open items are
  record-only follow-ups TEST-012/TEST-014, the 7 deferred user-decision findings (§4), the open
  product decision at `ROADMAP.md:1718`, and Phase 16's formally open status.
- **Known status gaps:** (a) [**RESOLVED 2026-10-22**] the scope-template line saying "Phase 14 /
  Phase 15 were NOT started in this round" at `ROADMAP.md:2199` / `DECISIONS.md:7927` /
  `DECISIONS.md:7727` has been clarified in place with dated bracketed markers (round-scope
  statement only — Phase 14 and Phase 15 were already COMPLETE per `ROADMAP.md:794`/:839); the
  same 2026-10-22 reconciliation round also superseded in place the stale Room schema-version
  claim (`PARITY.md`, now cites v4 at `PaheDatabase.kt:52`), the stale "no runtime prompt"
  claims (`LIMITATIONS.md` §4/§9 — the prompt ships, `AnimeDetailsViewModel.kt:1607`), the
  stale test counts and citation-checker status (`ANDROID_TESTING_AND_RELEASE.md` §3/§13),
  the stale Phase 16 streaming annotation (`ROADMAP.md:533`), and the dead
  `scripts\opencode-via-mubeng.ps1` pointer (`TOOLING_SETUP.md:15`, verified absent from the
  repository); PARITY.md:674's doc-mojibake follow-up (recorded at `ROADMAP.md:2155`) was
  mechanically decoded. (b) A comment-only documentation-decoupling pass
  performed 2026-10-22 (verified: JVM gate 114/1,236/0F/0E/0Sk; lint 0 errors; independent audit
  verdict "VERIFIED WITH LIMITATIONS") is not yet reflected in ROADMAP or DECISIONS.

## 11. Provenance: verified current behavior vs historical evidence

- **Verified current behavior** = claims in §1–§7 confirmed against the current source tree on
  2026-10-22 (file:line citations above) and against the passing JVM suite. Device claims appear
  only where a physical-device evidence record exists in `ANDROID_FEATURE_CATALOG.md` /
  `ANDROID_TESTING_AND_RELEASE.md` (device `REDACTED-SERIAL`); unit tests are never presented as device
  evidence.
- **Historical evidence** = phase reports and superseded architecture drafts. Their dispositions
  (partially executed 2026-10-22, see below):
  - **Authoritative architecture records (retain):** `PHASE10_ARCHITECTURE_V10.md`
    (`ROADMAP.md:463`, ~16 live citations, "annotation-not-rewrite" protocol per `PHASE14_PLAN.md:454`);
    `PHASE11_ARCHITECTURE_V2.md` (`ROADMAP.md:521` "authoritative architecture", ~70 source/test
    citations). Stale status labels inside both are historical annotations, not current behavior —
    current behavior is in `ANDROID_ARCHITECTURE.md`/`ANDROID_DATA_AND_STATE.md`.
  - **Archived 2026-10-22 to `archive/phase-reports/`** (byte-for-byte moves, provenance in
    `archive/phase-reports/ARCHIVE_INDEX.md`): `PHASE11_ARCHITECTURE_V1.md` (self-labeled SUPERSEDED),
    `PHASE10_ARCHITECTURE_V8.md` / `V9.md` (self-labeled SUPERSEDED banners), `PHASE3_REPORT.md`,
    `PHASE5_VALIDATION.md`, `PHASE8_AUDIT_REPORT.md` (unreferenced early-phase reports; several
    claims contradicted by current source). The one live `:NNNN` citation into an archived file
    (`DECISIONS.md:4984` → `PHASE11_ARCHITECTURE_V1.md:340`) still resolves through the checker's
    recursive leaf index; no protected-document citation was changed.
  - **Archived 2026-10-22 (second pass, sequential-maintenance round):** `PHASE11_HANDOFF.md`
    (self-labeled HISTORICAL — SUPERSEDED interruption record) → `archive/phase-reports/`.
    The citation checker still validates the baseline chain: its `$ExtraCiteFiles` entry was
    repointed to the archived path (`scripts/check-doc-citations.ps1:102`), so
    `PHASE11_HANDOFF:63` citations from `ROADMAP.md:584` / `DECISIONS.md:2292` / `:3090`
    resolve unchanged, and the 114,869,141-byte figure pin continues to scan the five root
    primary documents (`scripts/check-doc-citations.ps1:564-590`).
  - **Retained in root:** `PHASE14_PLAN.md` + `PHASE14_IMPLEMENTATION_STATE.md` (Phase 14
    COMPLETE; still citation-maintained — live `:NNNN` citations from this file and
    `ANDROID_TESTING_AND_RELEASE.md` — and the latter supplies the operational knowledge
    mirrored in `ANDROID_TESTING_AND_RELEASE.md` §14.5).
  - `PHASE_A_FINDING_INVENTORY.md` is **current** (206 rows, reconciled through DECISIONS §163 /
    2026-10-21 round) — it is a live ledger, not a historical report.
- **APK-size delta reconciled (2026-10-22, pre-push verification):** the on-disk debug APK
  differs from the `ROADMAP.md:2196` baseline by exactly **+286,264 bytes** — baseline
  118,307,654 bytes (SHA-256
  `EF88B63ACD4EC807E8C33336FAD33500FFFAF76D341F3CA17E105529B15E2CBE`) vs comparison
  (`app/build/outputs/apk/debug/app-debug.apk`) 118,593,918 bytes (SHA-256
  `496560DE7E3DBD35ADFA56A80D052A0064FFBEDA51F301B25756414C7FE3A79C`). Both artifacts have
  350 ZIP entries with identical entry names; all 330 non-DEX entries are byte-identical
  (340 of 350 entries are byte-identical overall — the 10 content-different entries are all
  DEX: classes3/4/8/9/10/12/14/15/16/17.dex). The measured delta is fully accounted for by
  −4 bytes in compressed payload and +286,268 bytes in inter-entry ZIP padding
  (−4 + 286,268 = +286,264): the APK archive layout and redistributed alignment padding
  account for the observed size difference. The ten content-different DEX entries are
  consistent with the source-only comment/KDoc pass of 2026-10-22, but the exact byte
  contribution of each comment edit was not established. Evidence copy of the baseline
  retained outside the repository at
  `<TEMP>\opencode\apk-baseline-1021\base-ondevice.apk`
  (recovered read-only from device REDACTED-SERIAL); canonical baseline figure at `ROADMAP.md:2196`.
- **KDoc casing residual (resolved):** of the audited lowercase paragraph-starts from the
  2026-10-22 comment pass, 12 were corrected; 5 remain intentionally (verified API-name
  casing, not defects): `StorageSpace.kt:35`, `AppContainer.kt:67`,
  `DownloadRowMapper.kt:88`/`:90`, `WatchlistCheckWorker.kt:45`.

---

*Protected documents (`AGENTS.md`, `SPEC.md`, `DECISIONS.md`, `ROADMAP.md`, `PARITY.md`,
`LIMITATIONS.md`, `ANDROID_*.md`, `REPOSITORY_ARCHAEOLOGY.md`, `TOOLING_SETUP.md`) were read only
during the creation of this file. Conflicts found in them are documented for user decision, not
applied.*
*Update 2026-10-22 (documentation-reconciliation round): the seven previously documented conflicts
were re-verified against source and corrected in place in their owning protected documents with
dated bracketed supersession markers — see §10(a). No file was deleted, renamed, or archived;
production source, tests, Gradle, and manifests were untouched.*
