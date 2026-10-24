# PaheBatcher Android — Feature Catalog

**Last verified: 2026-10-18**

A catalog of every user-visible feature of the native Android application, with the eleven
required fields per feature. Self-contained; every claim is grounded in this repository's
sources, tests or recorded runs.

Companion documents: `ANDROID_ARCHITECTURE.md`, `ANDROID_DATA_AND_STATE.md`,
`ANDROID_USER_FLOWS.md`, `ANDROID_AI_HANDOFF.md`, `ANDROID_TESTING_AND_RELEASE.md`.

## Verification tiers used in this catalog

- **VERIFIED (JVM tests)** — deterministic suite under `pahebatcher/src/test` pins the behavior.
- **VERIFIED (instrumented, device REDACTED-SERIAL user-level test run)** — suite under
  `pahebatcher/src/androidTest` executed via direct `am instrument` on physical device `REDACTED-SERIAL`,
  Android Dev secondary user (see `ANDROID_TESTING_AND_RELEASE.md` §5/§7 for the procedure;
  the user id is rediscovered per session, never hardcoded).
- **VERIFIED (live device journey 2026-10-18)** — the manual 24-step end-to-end journey on
  the real service (`ANDROID_TESTING_AND_RELEASE.md` §9) exercised this feature and it
  behaved as specified.
- **IMPLEMENTED, tests-verified only** — production code is implemented and wired into the
  UI, but no live end-to-end evidence exists; only the tiers above (or none) apply.
- **TEST-ONLY / DEFERRED** — called out explicitly where relevant.

---

## 1. App startup & home

- **Purpose:** cold-start the app into a usable Home surface with tabs, app bar and restored state.
- **Entry point:** launcher icon → `MainActivity` (single-activity).
- **Production UI reachability:** always — the root of the navigation graph.
- **Implementation components:** `MainActivity`, `PaheBatcherApplication` (container +
  scheduler registration), `AppNavHost`, `HomeScreen`/`HomeViewModel`, `AppViewModelFactory`.
- **State & persistence:** tab/back-stack state via Navigation-Compose saveable state;
  settings from DataStore; download/session state from Room — startup reconstructs from
  persistence, never from UI memory.
- **Expected behavior:** dark theme by default (fresh-install default), Home renders with a
  first-use composition when no search has run; offline banner driven by `NetworkMonitor`.
- **Failure behavior:** honest error surfaces with Retry; no fake content; startup never
  crashes on missing session.
- **Verification status:** VERIFIED (live device journey 2026-10-18, steps 1–2) +
  VERIFIED (instrumented, device REDACTED-SERIAL user-level test run) — `NavigationTest`,
  `HomeFirstUseSemanticsTest`, `OfflineBannerTest`.
- **Known limitations:** none beyond documented per-screen items below.
- **Related tests:** `ui/NavigationTest.kt`, `ui/HomeFirstUseSemanticsTest.kt`,
  `ui/OfflineBannerTest.kt`, `ui/IntentNavigationTest.kt`, JVM `ui/home/HomeViewModelTest.kt`.
- **Source files:** `pahebatcher/src/main/java/com/pahebatcher/app/MainActivity.kt`,
  `PaheBatcherApplication.kt`, `ui/navigation/AppNavHost.kt`, `ui/home/HomeScreen.kt`.

## 2. Search

- **Purpose:** live anime search against the real service from the Home screen.
- **Entry point:** search field on Home; result rows push Anime Details.
- **Production UI reachability:** always (Home tab).
- **Implementation components:** `HomeViewModel` (350 ms debounce — HomeViewModel.kt:50,
  cancellation-safe in-flight job), `AnimeRepository.search` → `SearchOutcome`,
  `SearchResultMapper`, `OfflineBanner` guard.
- **State & persistence:** query/results held in the ViewModel (UI state only);
  no search results are persisted.
- **Expected behavior:** debounced typing → real results only; blank-query first use shows
  neither results nor error; results carry title/poster/synopsis from the real parse.
- **Failure behavior:** network/parse failure → honest error message + working Retry;
  offline state blocks execution and shows the banner (never a false offline claim).
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 3) +
  VERIFIED (JVM tests) + VERIFIED (instrumented, device REDACTED-SERIAL user-level test run,
  `SearchImeTest`).
- **Known limitations:** search is single-request; no infinite scroll (out of scope).
- **Related tests:** JVM `ui/home/HomeViewModelTest.kt`, `data/repository/AnimeRepositoryTest.kt`,
  `data/repository/AnimeSearchOutcomeTest.kt`; instrumented `ui/SearchImeTest.kt`.
- **Source files:** `ui/home/HomeViewModel.kt`, `ui/home/HomeScreen.kt`,
  `data/repository/AnimeRepository.kt`, `ui/mapper/SearchResultMapper.kt`.

## 3. Anime details

- **Purpose:** show a title's session, synopsis and the episode list with download/stream actions.
- **Entry point:** tapping a search result → `AnimeDetailsRoute(session)`.
- **Production UI reachability:** always (pushed from Home).
- **Implementation components:** `AnimeDetailsScreen`/`AnimeDetailsViewModel`,
  `EpisodeRepository` (cache-first scan), `AudioOptionRepository` (bounded play-page probe
  populating the audio option set), offline guard.
- **State & persistence:** route carries only the session id; title/episodes re-read from
  Room/scan-cache (lossless across process death); probe state persists inside the existing
  scan-cache row (no new table).
- **Expected behavior:** details load from cache when fresh, from network when stale/absent;
  episode list renders with number, title, per-number audio and downloaded markers.
- **Failure behavior:** load failure → honest per-surface error with Retry; a failed page
  never blanks a good page list.
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 5) +
  VERIFIED (instrumented, device REDACTED-SERIAL user-level test run — `AnimeDetailsScreenTest`
  46/46, DECISIONS.md:7292).
- **Known limitations:** the option-set probe chain runs in production but has only been
  exercised through tests, not a live manual probe session.
- **Related tests:** JVM `ui/details/AnimeDetailsViewModelTest.kt`,
  `data/repository/AudioOptionProbeTest.kt`; instrumented `ui/AnimeDetailsScreenTest.kt`,
  `ui/AnimeDetailsDestinationTest.kt`.
- **Source files:** `ui/details/AnimeDetailsScreen.kt`, `ui/details/AnimeDetailsViewModel.kt`,
  `data/repository/EpisodeRepository.kt`, `data/repository/AudioOptionRepository.kt`.

## 4. Lazy pagination & page bar

- **Purpose:** navigate arbitrarily large catalogues (100s of episodes) without fetching
  every page; jump to any page.
- **Entry point:** page bar (Prev/numbers/ellipsis/Next) and the "Go to page" field on
  Anime Details.
- **Production UI reachability:** always on Anime Details once more than one page exists.
- **Implementation components:** `EpisodePaging` (app-page ↔ server-window math),
  `AnimeDetailsViewModel` page cache + `loadPage`, `AnimeDetailsScreen.PageBar` with
  `pageNumberButtonBudget` (width- and font-scale-aware, floored at 4 —
  DECISIONS.md:7261-7268).
- **State & persistence:** loaded pages cached in the ViewModel; currentPage is saveable UI
  state; catalogue totals come from the scan row.
- **Expected behavior:** pages load only when opened or when a range needs them; page size
  is `min(total, 100)` for >100-episode titles, else the server's 30-row page
  (AnimeDetailsViewModel.kt:581); the "Go to page" field appears exactly when the number
  strip shows an ellipsis, out-of-range input is rejected without navigating.
- **Failure behavior:** a page fetch failure shows a scoped error + retry for that page;
  the rest of the strip stays usable.
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 6 — >100-episode
  title) + VERIFIED (JVM tests — `EpisodePagingTest` 11, `AnimeDetailsViewModelPagingTest` 19,
  `AnimeDetailsPageBarBudgetTest` 6) + VERIFIED (instrumented, device REDACTED-SERIAL user-level
  test run — 5 pager cases inside `AnimeDetailsScreenTest`, DECISIONS.md:7292-7295).
- **Known limitations:** the "wide window fits every page → hide the field" branch needs a
  window wider than the ~461dp test device; it is pinned as a deterministic JVM function,
  not through Compose (DECISIONS.md:7304-7307).
- **Related tests:** as above, plus JVM `ui/details/AnimeDetailsViewModelPagingTest.kt`.
- **Source files:** `domain/util/EpisodePaging.kt`, `ui/details/AnimeDetailsViewModel.kt`,
  `ui/details/AnimeDetailsScreen.kt`.

## 5. Episode range & batch selection panel

- **Purpose:** select episodes by tap, ordinal range (From/To), "Select all", page download,
  or "Latest N"; then queue the selection as a batch.
- **Entry point:** "Download" on details → batch mode panel (list's first item).
- **Production UI reachability:** always on Anime Details (batch mode toggle).
- **Implementation components:** `AnimeDetailsScreen` batch panel
  (From/To + Latest fields — AnimeDetailsScreen.kt:1211-1218), `EpisodeRangeParser`,
  `AnimeDetailsViewModel` selection state, `startBatchDownload`, CTA de-dup logic
  (`selectionIsExactlyThePage` hides the redundant CTA — DECISIONS.md:7278-7285).
- **State & persistence:** selection is saveable UI state; queued episodes are persisted as
  Room rows at enqueue time.
- **Expected behavior:** selection is capped at **100** episodes (`1..min(total,100)` —
  AnimeDetailsViewModel.kt:1771-1772); range expressions that match nothing get the honest
  "no episodes matched" message; "Latest N" selects the newest N numbers through the same
  parser (`selectLatestEpisodes`, AnimeDetailsViewModel.kt:1854); paging while the panel is
  open re-centers on it.
- **Failure behavior:** a range whose pages fail to load surfaces a batch-scoped error and
  never blanks the page list; rejections never enqueue anything.
- **Verification status:** VERIFIED (live device journey 2026-10-18, steps 7 & 10 — range
  selection incl. rejections, and a batch) + VERIFIED (JVM tests —
  `EpisodeRangeParserTest`, `AnimeDetailsViewModelBatchTest`, `AnimeDetailsBatchLeaveTest`,
  `AnimeDetailsBatchFailureAttributionTest`) + VERIFIED (instrumented, device REDACTED-SERIAL
  user-level test run — `AnimeDetailsBatchScreenTest` 24/24, DECISIONS.md:7295).
- **Known limitations:** "Latest N" on a paged title selects the newest among LOADED rows,
  not the whole catalogue — a pre-existing paging-architecture behavior, deliberately
  unchanged (ROADMAP.md:1933-1934). Executing a full 100-episode page batch against the
  live service is unit-verified only (it would start ~100 real downloads).
- **Related tests:** JVM `domain/util/EpisodeRangeParserTest.kt`,
  `ui/details/AnimeDetailsViewModelBatchTest.kt`; instrumented
  `ui/AnimeDetailsBatchScreenTest.kt`.
- **Source files:** `domain/util/EpisodeRangeParser.kt`, `ui/details/AnimeDetailsScreen.kt`,
  `ui/details/AnimeDetailsViewModel.kt`.

## 6. Audio selection

- **Purpose:** choose the audio variant (e.g. the release's `jpn`/`eng`-style `data-audio`
  strings) per episode / per batch, at resolve time.
- **Entry point:** audio selector on the Anime Details batch panel; per-number overrides;
  single-tap rows toggle only the number (the effective variant is `override ?: context`).
- **Production UI reachability:** always on Anime Details; options come from the bounded
  play-page probe union.
- **Implementation components:** `AudioSelector`, `AudioOptionRepository` (probe),
  `AnimeDetailsViewModel.effectiveVariantOf` / `getAllVariants` override gate,
  `EpisodeInfo.audio` persisted per row.
- **State & persistence:** the chosen audio is persisted on each `EpisodeDownloadEntity`
  row at enqueue and drives notifications, export naming and playback identity.
- **Expected behavior:** audio is fixed per episode at resolve time — exact match, no
  substitution; there is NO global audio preference (AppConfig.kt:371-372); a batch derives
  one context plus explicit per-number overrides.
- **Failure behavior:** a requested audio absent from the probe set is rejected honestly
  (listing-only false rejections were removed; the probe is the source of truth).
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 8) +
  VERIFIED (JVM tests — `AudioSelectorTest`, `AnimeDetailsAudioSelectionTest`,
  `AnimeDetailsAudioOptionDiscoveryTest`) + VERIFIED (instrumented, device REDACTED-SERIAL
  user-level test run — `AnimeDetailsAudioScreenTest`, `AnimeDetailsAudioRecreationTest`,
  `DownloadsRowAudioLabelTest`).
- **Known limitations:** in-player audio switching is NOT implemented (audio is decided
  before resolution); upstream publishes at most one audio release per episode for the
  titles exercised live.
- **Related tests:** as above, plus JVM `data/repository/AudioOptionProbeTest.kt`.
- **Source files:** `data/stream/AudioSelector.kt`, `data/repository/AudioOptionRepository.kt`,
  `ui/details/AnimeDetailsViewModel.kt`.

## 7. Individual episode download

- **Purpose:** download one episode end-to-end: resolve → segment download → assemble → export.
- **Entry point:** episode row download action (details) / batch member rows.
- **Production UI reachability:** always (Anime Details; also via watchlist auto-queue).
- **Implementation components:** `DownloadRepository` (resolve → persist stream → enqueue
  unique WorkManager work), `StreamResolverImpl`/`KwikResolver`/`M3u8Extractor`,
  `DownloadWorker`, `EpisodeDownloader`, `FfmpegEpisodeAssembler`, `ExportDestinationRouter`.
- **State & persistence:** `EpisodeDownloadEntity` row in Room (state/progress/error/
  stream info/output pointers); unique work name `episode_download_<episodeId>`.
- **Expected behavior:** row walks QUEUED → RESOLVING → DOWNLOADING → ASSEMBLING →
  COMPLETED with export; the pipeline never re-resolves inside the Worker.
- **Failure behavior:** typed `FailureCategory` persisted; honest error, Retry affordance;
  auto-retry only for transport-class failures when the opt-in setting is ON.
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 9) +
  VERIFIED (JVM tests) + VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `DownloadRepositoryTest`, `FullDownloadFlowTest`, `WorkerExecutionTest`,
  `EnqueueOutcomeMappingTest`).
- **Known limitations:** none structural; expiring stream URLs are never persisted
  indefinitely (re-resolution on retry).
- **Related tests:** as above, plus instrumented `data/download/DetailsHandoffTest.kt`.
- **Source files:** `data/download/DownloadRepository.kt`, `worker/DownloadWorker.kt`,
  `data/hls/EpisodeDownloader.kt`, `data/stream/StreamResolverImpl.kt`.

## 8. Batch download

- **Purpose:** queue many episodes at once with bounded parallelism and deferral.
- **Entry point:** "Download selected" / "Download this page" / "Select all" then download.
- **Production UI reachability:** always (batch panel, §5).
- **Implementation components:** per-episode pipeline (§7) plus the max-parallel gate
  inside the worker claim (`DownloadWorker`), deferral (QUEUED rows triggered one-by-one on
  every completion path), `EpisodeDownloadQueue` outcomes (`queued · skipped · failed`).
- **State & persistence:** one Room row + one unique work per episode; batches persist
  across restarts (`BatchPersistenceTest`, `BatchRecreationTest`).
- **Expected behavior:** up to `maxParallel` (1–6, AppContext.kt:75-76) episodes download
  concurrently; the rest defer and self-trigger; summary counts are honest (skipped
  includes already-downloaded/refused).
- **Failure behavior:** one failed member never cancels siblings; every completion path
  triggers the next deferred episode (DownloadWorker.kt:42-44); batch-level errors are
  attributed per episode.
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 10 — small batch)
  + VERIFIED (JVM tests) + VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `BatchDownloadDeviceTest`, `BatchPersistenceTest`, `BatchRecreationTest`,
  `TriggerNextDeferredEpisodeTest`, `ConcurrencyTest`).
- **Known limitations:** the full 100-episode page batch has never been executed against
  the live service (would start ~100 real downloads); its queue contents are unit-verified.
- **Related tests:** as above.
- **Source files:** `worker/DownloadWorker.kt`, `domain/download/EpisodeDownloadQueue.kt`,
  `data/download/DownloadRepository.kt`.

## 9. Stream resolution sheet (resolution status surface)

- **Purpose:** surface resolution progress/status and errors for the episode the user
  tapped, with a Retry, instead of a silent stall.
- **Entry point:** tapping an episode for download/stream → `ResolutionPanel` card on
  Anime Details.
- **Production UI reachability:** always, transiently during resolution.
- **Implementation components:** `AnimeDetailsScreen.ResolutionPanel`/`ResolutionCard`,
  `AnimeDetailsViewModel.startResolution`/`onRetryResolution`, `StreamResolverImpl`
  (play-page → Kwik → M3U8), `StreamResolutionRetry` (bounded retry budget),
  `di/StreamResolverProductionWiringTest`-asserted production wiring.
- **State & persistence:** resolving/resolved/error flags in the UI state; resolved
  `StreamInfo` is persisted on the row (never logged; URLs sanitized).
- **Expected behavior:** resolving → resolved (download/stream proceeds) with bounded
  retries; the sheet never invents a stream.
- **Failure behavior:** resolutionError shows an honest reason + Retry; raw error text that
  may embed a URL is never rendered.
- **Verification status:** VERIFIED (JVM tests — `StreamResolverImplTest`, `KwikResolverTest`,
  `M3u8ExtractorTest`, `JsPackerTest`, `QualitySelectorTest`, `StreamResolutionRetryTest`)
  + VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `StreamResolutionValidationTest`, `KwikDirectUrlTest`, `AnimeDetailsStreamActionTest`
  (JVM), `StreamResolverProductionWiringTest`). The resolution pipeline itself ran live as
  a prerequisite of the live download/stream journey steps.
- **Known limitations:** the dedicated sheet UI is not separately live-tested; live
  evidence covers the pipeline it renders.
- **Related tests:** as above.
- **Source files:** `ui/details/AnimeDetailsScreen.kt` (ResolutionPanel),
  `data/stream/StreamResolverImpl.kt`, `domain/stream/StreamResolutionRetry.kt`.

## 10. Download progress & resume

- **Purpose:** show honest per-episode progress and survive interruption (network loss,
  pause/resume, process death).
- **Entry point:** Downloads screen rows; progress notification; Sessions screen
  Pause/Resume/Cancel/Retry.
- **Production UI reachability:** always (Downloads tab).
- **Implementation components:** `ProgressTracker` (throttled writes), `ProgressMetrics`,
  `DownloadsViewModel`/`DownloadRowMapper`, `DownloadRepository.pause/resume/cancel/reEnqueue`,
  `AutoResumeEligibility` (opt-in), `WifiOnlyConstraint`.
- **State & persistence:** all progress/state in Room (`EpisodeDownloadEntity`), throttled
  to avoid DB churn; PAUSED is a deliberate user state never auto-resumed
  (DownloadStateReconciler.kt:45-47).
- **Expected behavior:** percentage/segment counters update while downloading; pause stops
  at a safe boundary and keeps segments; resume re-enters the queue with persisted stream
  info or a fresh resolution; interrupted segments are never treated as complete
  (`.tmp → .ts` atomic writes — EpisodeDownloader.kt:30-44).
- **Failure behavior:** transport failure → auto-resume path only when enabled and within
  the attempt budget; otherwise a terminal FAILED with reason; airplane mode mid-run
  produces an honest failure.
- **Verification status:** VERIFIED (live device journey 2026-10-18, steps 11/13 —
  progress notification, airplane-mode failure) + VERIFIED (JVM tests —
  `worker/ProgressThrottlingTest`, `worker/ProgressMetricsTest`, `worker/AutoResumeEligibilityTest`)
  + VERIFIED (instrumented, device REDACTED-SERIAL user-level test run — `ResumeTest`,
  `PauseResumeDeviceTest`, `TotalSegmentsPersistenceTest`, `AutoResumeConnectivityTest`,
  `DownloadMetricsDeviceTest`, `WifiOnlyConstraintTest`).
- **Known limitations:** segment-level resume granularity is per-file (a partial `.ts` is
  re-fetched whole); WorkManager backoff timing is platform-controlled.
- **Related tests:** as above.
- **Source files:** `worker/ProgressTracker.kt`, `data/download/DownloadRepository.kt`,
  `data/hls/SegmentFileStore.kt`, `ui/downloads/DownloadsViewModel.kt`.

## 11. Assembly & output verification

- **Purpose:** mux downloaded segments into a faststart MP4 and verify the output is real
  before anything is deleted or exported.
- **Entry point:** automatic stage of every download (no user surface beyond status).
- **Production UI reachability:** implicit (ASSEMBLING state on rows/notifications).
- **Implementation components:** `FfmpegEpisodeAssembler` (FFmpegKit), `ConcatFileGenerator`,
  `EpisodeOutputVerifier` (the ONE completed-output rule: URI-resolves-and-size>0, else
  path-exists-and-size>0 — EpisodeOutputVerifier.kt:24-27), `AudioSpecificConfigNormalizer`.
- **State & persistence:** ASSEMBLING state persisted; output path/URI recorded on the row
  only after verification.
- **Expected behavior:** assembly produces a playable MP4; only a verified non-empty output
  can mark a row COMPLETED.
- **Failure behavior:** failed/short output → FAILED with reason, segments retained for
  retry; nothing user-visible is deleted on a failed verification.
- **Verification status:** VERIFIED (JVM tests — `FfmpegEpisodeAssemblerTest`,
  `EpisodeOutputVerifierRuleTest`, `ConcatFileGeneratorTest`, `AudioSpecificConfigNormalizerTest`)
  + VERIFIED (instrumented, device REDACTED-SERIAL user-level test run — `FfmpegAssemblyDeviceTest`
  (real FFmpegKit on arm64), `FfmpegAssemblyValidDataTest`, `FullDownloadFlowTest`).
- **Known limitations:** cross-store (private storage → MediaStore/SAF) atomicity is
  best-effort by platform constraint; the verifier compares sizes, not bytes.
- **Related tests:** as above.
- **Source files:** `data/hls/FfmpegEpisodeAssembler.kt`,
  `data/download/EpisodeOutputVerifier.kt`, `data/hls/ConcatFileGenerator.kt`.

## 12. MediaStore export

- **Purpose:** publish assembled MP4s into shared storage (`Movies/<sanitized-anime-title>/`)
  idempotently and safely.
- **Entry point:** automatic post-assembly step; also re-run by reconciliation and
  session-delete export-first.
- **Production UI reachability:** implicit; results visible in the device gallery/file
  apps and the Downloads/Players screens.
- **Implementation components:** `MediaStoreExporter` (`IS_PENDING` insert → stream copy →
  size verify → make visible → Room persist → local delete; `ExportOutcome` at
  MediaStoreExporter.kt:26-39), `PriorOutputRetirement`, `ExportFileNames`,
  `OutputPathNames`, `sweepStalePending`.
- **State & persistence:** `outputUri`/`outputPath` on the episode row; the completed
  invariant is outputUri-aware (DownloadStateReconciler.kt:26-34).
- **Expected behavior:** export is idempotent (re-export of a size-matching row is
  `AlreadyExported`, never a duplicate); per-anime directories; legacy flat
  `Movies/PaheBatcher/` files are never deleted or renamed; dual-audio variants coexist
  under distinct audio-specific names.
- **Failure behavior:** export failure keeps the local MP4 and retries at next startup;
  pending crash residue is replaced in the same run; a visible user file is never deleted
  to make room.
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 19 — MediaStore
  metadata via user-scoped `content query`) +
  VERIFIED (instrumented, device REDACTED-SERIAL user-level test run — `MediaStoreExportTest`
  **23/23 on 2026-10-18** (clean run; one historical fixture/timing flake in
  `legacyUnsuffixedFile_neverDeletedOrRenamed`, see
  `ANDROID_TESTING_AND_RELEASE.md` §14.3), plus `MigrationTest`, `OutputTreeRepositoryTest`).
- **Known limitations:** Unicode-named exports were never exercised on the live device
  (upstream surfaced only Latin titles; sanitizer behavior is JVM-pinned —
  LIMITATIONS.md:2634); cross-store atomicity remains best-effort.
- **Related tests:** JVM `data/export/ExportFileNamesTest.kt`, `OutputPathNamesTest.kt`;
  instrumented `data/export/MediaStoreExportTest.kt`, `data/local/MigrationTest.kt`.
- **Source files:** `data/export/MediaStoreExporter.kt`, `data/export/PriorOutputRetirement.kt`,
  `data/export/ExportFileNames.kt`, `data/export/OutputPathNames.kt`.

## 13. SAF custom folder

- **Purpose:** let the user pick a custom output tree (SAF) instead of the MediaStore default.
- **Entry point:** Settings → output location picker.
- **Production UI reachability:** always (Settings).
- **Implementation components:** `SafExporter` (app-owned `<name>.pb.part` child →
  byte-verify → rename-or-fallback; collision = reuse-by-size else uniquified `name (n)`),
  `ExportDestinationRouter` (routes SAF-when-valid else MediaStore; rename-less providers
  persist `output_tree_rename_ok=false` and fall back — ExportDestinationRouter.kt:38-64),
  `OutputTreeRepository` (validation states), `SettingsOutputLocationTest` surface.
- **State & persistence:** `output_tree_uri` + `output_tree_rename_ok` in DataStore
  (AppConfig.kt:121-122); persistable URI permission held.
- **Expected behavior:** a valid tree receives exports under `<tree>/<anime-title>/`;
  revoked/moved/unusable trees fall back to MediaStore for future exports and surface the
  reason in Settings; the pipeline is never blocked by a bad tree.
- **Failure behavior:** rename-unsupported provider → same-run MediaStore fallback;
  `.pb.part` residue cleaned at export start; user files are never overwritten (collision →
  reused-by-size or uniquified copy).
- **Verification status:** VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `SafExporterTest` 9/9, `ExportDestinationRouterTest` 5/5, `OutputTreeRepositoryTest` 8/8,
  `SettingsOutputLocationTest` 7/7, JVM `SafExportNamesTest` 16/16, `SafPlaybackDeviceTest` 2/2).
  NOT exercised in the 2026-10-18 live journey.
- **Known limitations:** no SAF atomic-rename claim anywhere; **the destination probe does
  not probe a configured SAF tree — it returns `false` there, keeping today's admit
  behavior** (ExportDestinationRouter.kt:69-91), so the already-downloaded destination
  awareness (§15) is MediaStore-only.
- **Related tests:** as above.
- **Source files:** `data/export/SafExporter.kt`, `data/export/ExportDestinationRouter.kt`,
  `data/export/OutputTreeRepository.kt`, `data/export/SafExportNames.kt`.

## 14. Replace file

- **Purpose:** deliberate, confirmed re-download of an episode that has a verified completed
  export (the ONE path allowed past a valid completed output).
- **Entry point:** completion notification action "Replace file" (routes to the in-app
  confirmation — DownloadNotificationManager.kt:62) or the Downloads row affordance.
- **Production UI reachability:** always for COMPLETED rows with verified output.
- **Implementation components:** `DownloadRepository.confirmReplacement` (guarded UPDATE
  claim with `replaceRequested`), `ReplacementOutcome` (Started / AlreadyInFlight /
  Unavailable / Refused — ReplacementOutcome.kt:14-21), prior-output retirement in the
  exporter.
- **State & persistence:** `replaceRequested` flag persisted on the row; the claim is
  CAS-guarded and idempotent.
- **Expected behavior:** confirm → ordinary resolve→queue→Worker pipeline; prior export is
  retired only by the exporter's narrow rules (exact audio-specific name + size mismatch,
  or app-owned crash residue); replacement failure keeps the existing file and the failure
  notification says so (` (replacement failed, existing file kept)` —
  DownloadNotificationManager.kt:75).
- **Failure behavior:** stale action (row vanished/output gone) → Unavailable, NO
  destructive dialog; concurrent double-confirm → AlreadyInFlight (one run).
- **Verification status:** VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `ReplaceFileConfirmationTest`, `ReplaceFileOutputSafetyTest`,
  `ReplaceFileDuplicateProtectionTest`, plus `NotificationInteractionTest` for the
  notification action). NOT exercised in the 2026-10-18 live journey.
- **Known limitations:** after a MediaStore prior is retired, the replacement item may hold
  a uniquified ` (1)` display name; no post-retire rename is attempted — accepted,
  cosmetic-only behavior (LIMITATIONS.md:3213, DECISIONS.md:7035).
- **Related tests:** as above, JVM `domain/download/` outcome tests.
- **Source files:** `data/download/DownloadRepository.kt`,
  `domain/download/ReplacementOutcome.kt`, `notification/DownloadNotificationManager.kt`,
  `data/export/PriorOutputRetirement.kt`.

## 15. Already-downloaded / duplicate suppression (incl. destination probe)

- **Purpose:** re-queuing an episode that is already downloaded (or whose exported file
  still survives in the destination) is a no-op, never a destructive re-download.
- **Entry point:** automatic gate inside `DownloadRepository` submission.
- **Production UI reachability:** implicit (queue summaries report `skipped`).
- **Implementation components:** `DownloadRepository` submission gate consulting the
  destination probe (`EpisodeExporter.destinationPresent`, DownloadRepository.kt:679),
  `EpisodeOutputVerifier` (single validity rule), `EnqueueOutcome` (AlreadyDownloaded /
  AlreadyActive / AlreadyPaused / Refused), `ExportDestinationRouter.destinationPresent`
  (MediaStore-only probe).
- **State & persistence:** row state + verified output pointers in Room; the probe
  re-checks the destination (MediaStore) when rows are gone/severed.
- **Expected behavior:** `queued 0 · skipped N · failed 0` for a re-queued completed range;
  a re-download over a surviving export with severed row state is the SAME no-op
  (OutputDestinationProbeGateTest.kt:26-41).
- **Failure behavior:** the gate fails OPEN (admits) when it cannot prove presence — never
  silently drops a genuinely missing download.
- **Verification status:** VERIFIED (live device journey 2026-10-18, steps 15–17 —
  already-downloaded detection, re-download after cache removal, existing-output behavior)
  + VERIFIED (JVM tests) + VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `OutputDestinationProbeGateTest`, `DuplicateEnqueueTest`, `DownloadStateReconcilerTest`).
- **Known limitations:** the probe deliberately returns `false` when a configured-and-valid
  SAF tree is active (SAF route unprobed → admit), so destination-awareness is MediaStore-
  only (ExportDestinationRouter.kt:69-91).
- **Related tests:** as above.
- **Source files:** `data/download/DownloadRepository.kt`,
  `data/export/ExportDestinationRouter.kt`, `data/download/EpisodeOutputVerifier.kt`.

## 16. Sessions screen (session manager)

- **Purpose:** manage download sessions: per-session Cancel/Retry/Pause/Resume, Delete
  session, Delete temporary cache, Clear all cache, cache-size display.
- **Entry point:** Downloads top app bar → `SessionsRoute`.
- **Production UI reachability:** always (pushed route).
- **Implementation components:** `SessionsScreen`/`SessionsViewModel`, `SessionActions`
  (all aggregate actions over the existing per-row pipeline — SessionActions.kt:33-72),
  `SessionDisplay`/`SessionRepository`, `SegmentFileStore.cleanup`.
- **State & persistence:** Room `download_sessions` + `episode_downloads`; the screen
  re-reads Room (nothing derived travels in navigation).
- **Expected behavior:** Delete session is terminal-only and export-first (local MP4s are
  offered to the byte-verified exporter; remaining local names are listed for explicit
  confirmation — SessionActions.kt:217-244); removal order is filesystem-first then Room
  (crash-repeatable); Delete temporary cache removes only temp artifacts and preserves
  MP4s; cache size counts only temp artifacts; Clear all cache is refused while ANY row
  anywhere is non-terminal.
- **Failure behavior:** refusals are explicit ("Cancel first — N download(s) in progress");
  no silent deletion of completed user media (SPEC §20 contract encoded at
  SessionActions.kt:249-262).
- **Verification status:** VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `SessionsScreenTest`, `SessionsNavigationTest`, `SessionActionsTest`,
  `SessionRepositoryRoomTest`; JVM `ui/sessions/SessionRowBuildTest.kt`,
  `data/repository/SessionDisplayTest.kt`). NOT a separate step of the 2026-10-18 journey.
- **Known limitations:** Clear-all-cache never touches Room rows (documented divergence in
  LIMITATIONS); exported MediaStore/SAF media is never referenced by session deletion.
- **Related tests:** as above.
- **Source files:** `ui/sessions/SessionsScreen.kt`, `ui/sessions/SessionsViewModel.kt`,
  `data/repository/SessionActions.kt`, `data/repository/SessionDisplay.kt`.

## 17. Download notifications (progress / complete / failure / batch-failure)

- **Purpose:** keep the user informed of download lifecycle outside the app, with the
  correct language label and honest content.
- **Entry point:** system notification shade; tap-to-open deep-links into the app.
- **Production UI reachability:** always while downloads run (foreground service
  notification is a platform requirement).
- **Implementation components:** `DownloadNotificationManager` (channels, group,
  navigate_to deep link), `NotificationChannels`, `DownloadNotifyPrefs`,
  `NotificationPermissionRepository` (asked-once latch), `WatchlistNotificationManager`
  (§20), `BatchFailureNotificationTest`-covered aggregated batch failure.
- **State & persistence:** notification-permission latch + granted state and the three
  posting toggles (completion/failure/watchlist, default ON) in DataStore (AppConfig.kt:78-80);
  the progress notification is never gated by a preference.
- **Expected behavior:** progress notification updates per episode with the persisted audio
  label (`episodeLine`/`progressText`); completion notification on COMPLETED; failure
  notification carries only provable content — episode title, audio label, typed failure
  category; batch summary adds an aggregate reason breakdown and can never invent a
  language; tap opens the app at the Downloads destination; the completion notification's
  `Replace file` action is a ROUTE, not a command (DownloadNotificationManager.kt:33-37).
- **Failure behavior:** posting without permission is silently skipped (no crash); raw
  error text is never rendered (may embed a URL); a failed replacement keeps the existing
  file AND says so in the notification.
- **Verification status:** VERIFIED (live device journey 2026-10-18, steps 11–13 — progress
  notification with language label, completion notification, airplane-mode failure reason)
  + VERIFIED (instrumented, device REDACTED-SERIAL user-level test run — `NotificationTest`,
  `NotificationPermissionTest`, `NotificationInteractionTest`, `BatchFailureNotificationTest`;
  JVM `notification/DownloadNotificationTextTest.kt`,
  `notification/DownloadNotificationAudioLabelTest.kt`).
- **Known limitations:** the aggregated BATCH-failure notification is instrumented-verified
  only (the live journey produced single-episode failure); observe via
  `dumpsys notification --noredact` (redacted form hides extras).
- **Related tests:** as above.
- **Source files:** `notification/DownloadNotificationManager.kt`,
  `notification/NotificationChannels.kt`, `notification/NotificationPermissionRepository.kt`,
  `notification/DownloadNotifyPrefs.kt`.

## 18. Session refresh prompt

- **Purpose:** when the WebView-backed session goes bad, recover via one quiet background
  refresh, and only then prompt the user — never silently fail.
- **Entry point:** root-level dialog above whatever route is open
  ("Refresh session" / "Not now").
- **Production UI reachability:** whenever `SessionRefreshSignal` fires while the app is on
  screen.
- **Implementation components:** `SessionRefreshSignal` (no-replay SharedFlow bus),
  `SessionRefreshViewModel` (quiet-attempt latch + coalescing guard —
  SessionRefreshViewModel.kt:43-51, 86-96), `AndroidCloudflareSession`/`CloudflareDetector`
  (detection sites), `AppContainer.forceRefreshWebViewSession`.
- **State & persistence:** prompt state is Activity-scoped and deliberately NOT persisted
  (a session prompt is only meaningful on screen); the silent-attempt latch survives
  configuration change.
- **Expected behavior:** first recovery requirement after staleness → ONE quiet refresh; a
  success shows NOTHING; a failure shows the dialog; every later requirement shows the
  dialog immediately; the user's "Refresh session" runs the real force-refresh; success
  clears the latch; "Not now" dismisses (a later failure re-raises).
- **Failure behavior:** a refused/throwing refresh keeps the dialog open with an honest
  reason (URL-free); a failed refresh is never swallowed.
- **Verification status:** VERIFIED (JVM tests — `SessionRefreshViewModelTest`,
  `SessionRefreshSignalTest`, `AndroidWebViewRequestBridgeSessionPromptTest`) +
  VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `SessionRefreshPromptDialogTest`, subject to the environment dependence below).
  The healthy-session journey step (step 4: operations proceed without a prompt) is
  VERIFIED (live device journey 2026-10-18).
- **Known limitations:** on a device with a healthy session the instrumented signal test's
  dialog may never appear because the quiet refresh SUCCEEDS BY DESIGN — see
  `ANDROID_TESTING_AND_RELEASE.md` §14.1; outcome depends on live-service health.
- **Related tests:** as above.
- **Source files:** `ui/sessionprompt/SessionRefreshViewModel.kt`,
  `domain/session/SessionRefreshSignal.kt`, `domain/session/CloudflareDetector.kt`,
  `data/session/AndroidCloudflareSession.kt`.

## 19. In-app player

- **Purpose:** play downloaded episodes (local file / MediaStore URI) and stream episodes
  directly (HLS), with subtitle rendering and next/previous episode navigation.
- **Entry point:** Downloads `Play` (DOWNLOAD mode); stream entry from details
  (`PlayerRoute(episodeId, mode = STREAM)`); MediaSession notification tap.
- **Production UI reachability:** always (single player destination, replace-in-place —
  AppRoutes.kt:58, 76-79, 112-120).
- **Implementation components:** `PlayerScreen`/`PlayerViewModel`, Media3 ExoPlayer +
  HLS + OkHttp datasource, `HlsPlaybackSourceFactory`, `StreamEpisodeReconstructor`
  (Room → cache → network ladder, just-in-time resolve, bounded retry),
  `streamSiblingIds` next/previous navigation, `PlayerSubtitles`, `MediaSessionHost` /
  `PaheMediaSessionService`, `PlaybackPositionRepository`, `PlaybackPlaylist`.
- **State & persistence:** playback positions in Room; the route carries ONLY episodeId +
  mode (no URLs/headers); streaming reads Room and never writes it.
- **Expected behavior:** DOWNLOAD mode plays the exported content; STREAM mode resolves
  just-in-time, attaches request headers on the socket, reaches READY with a
  playlist-parsed duration; next/previous swaps sources in-place on the SAME player,
  same-audio-variant bounded by the session's real list; unreadable list disables both
  skip controls rather than pretending.
- **Failure behavior:** bounded retry then an honest player error; a failed resolve never
  fabricates a stream; subtitles fall back gracefully when fixtures are absent.
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 20 — local
  playback and streamed playback) + VERIFIED (JVM tests —
  `ui/player/StreamModeDeterminationTest`, `StreamSiblingIndexTest`, `StreamRetryPolicyTest`,
  `StreamResolveInputsTest`, `SubtitleStateDerivationTest`, `data/playback/*`) +
  VERIFIED (instrumented, device REDACTED-SERIAL user-level test run —
  `Phase17DeviceStreamingVerificationTest`, `Phase17StreamingSiblingNavigationDeviceTest`,
  `StreamPlaybackLifecycleTest`, `StreamEpisodeNavigationUiTest`, `PlayerLifecycleTest`,
  `PlayerSubtitleControlTest`, `SafPlaybackDeviceTest`, `MediaSessionLifecycleTest`,
  `MediaSessionReleaseOrderTest`).
- **Known limitations:** in-player quality/audio switching is NOT implemented (audio is
  fixed at resolve time); MediaSession carries the seed episode id only (documented
  route-seed behavior).
- **Related tests:** as above.
- **Source files:** `ui/player/PlayerScreen.kt`, `ui/player/PlayerViewModel.kt`,
  `data/stream/HlsPlaybackSourceFactory.kt`, `data/stream/StreamEpisodeReconstructor.kt`,
  `playback/MediaSessionHost.kt`, `playback/PaheMediaSessionService.kt`.

## 20. Watchlist

- **Purpose:** follow anime URLs; periodically check for new episodes and auto-queue them;
  notify once per check run.
- **Entry point:** Home top app bar → `WatchlistRoute`; "Anime URL" field + "Add"; "Check
  now"; automatic background checks.
- **Production UI reachability:** always (pushed route).
- **Implementation components:** `WatchlistScreen`/`WatchlistViewModel`, `WatchlistRepository`,
  `WatchlistChecker` (per-entry scan → queued/skipped/not-started/deleted/relink honesty —
  WatchlistChecker.kt:31-70), `WatchlistCheckWorker` + `WatchlistCheckScheduler`
  (WorkManager periodic, 15-min default, NetworkType.CONNECTED, registered from
  `PaheBatcherApplication.onCreate`), `WatchlistNotificationManager` (ONE aggregated
  notification per run, tap-to-open watchlist — WatchlistNotificationManager.kt:16-24).
- **State & persistence:** `watchlist` Room rows (deleted-entry skip history, relink
  state); interval and the "New watchlist episodes" toggle in DataStore
  (options 15/30/60/240/720 min — AppConfig.kt:96,103).
- **Expected behavior:** Add validates the URL offline and persists the entry; background
  check scans each entry, queues genuinely-new episodes through the normal pipeline
  (skipping deleted/skipped/covered ones), refreshes titles, relinks dead sessions when
  exactly one match exists; exactly one count-summary notification per background run
  (foreground "Check now" never posts).
- **Failure behavior:** per-entry errors are honest and never crash the worker; queued
  counters distinguish "queued" from "not started"; notification content is a count
  summary only — never a URL or session value.
- **Verification status:** screen + add-URL: VERIFIED (live device journey 2026-10-18,
  step 24) + VERIFIED (instrumented, `WatchlistScreenTest`). **Background check triggering
  a new-episode notification: IMPLEMENTED, tests-verified only** — VERIFIED (JVM tests —
  `WatchlistCheckerTest`, `WatchlistCheckSchedulerTest`, `ui/WatchlistRelinkTest`) +
  VERIFIED (instrumented, `WatchlistCheckWorkerTest`); NO live evidence that a periodic
  fire produced the notification on the journey device.
- **Known limitations:** WorkManager periodic firing is inexact by platform design and the
  interval only re-registers per the offered set; live periodic-fire evidence is pending.
- **Related tests:** as above, plus instrumented `data/repository/WatchlistRepositoryRoomTest.kt`.
- **Source files:** `ui/watchlist/WatchlistScreen.kt`, `ui/watchlist/WatchlistViewModel.kt`,
  `domain/watchlist/WatchlistChecker.kt`, `worker/WatchlistCheckWorker.kt`,
  `worker/WatchlistCheckScheduler.kt`, `notification/WatchlistNotificationManager.kt`.

## 21. Settings

- **Purpose:** persist user preferences: theme, dynamic color, quality, max parallel
  downloads, HLS workers, scan/cache TTL, output folder/tree, Wi-Fi-only, auto-resume,
  notification toggles, watchlist interval, console visibility.
- **Entry point:** Home top app bar → `SettingsRoute` (also reachable per deep link).
- **Production UI reachability:** always (pushed route); Console entry only when its toggle
  is ON.
- **Implementation components:** `SettingsScreen`/`SettingsViewModel`, `AppConfig`
  (single DataStore `pahebatcher_settings`), `Theme.kt` (theme + dynamic color apply),
  SAF picker wiring for the output tree.
- **State & persistence:** everything above in DataStore; absent keys resolve to code
  defaults (quality 1080, maxParallel within 1–6 — AppContext.kt:75-76, hlsWorkers within
  8–32 — AppContext.kt:79-80, cache TTL, notify toggles ON, watchlist interval 15 min).
- **Expected behavior:** values persist across restart and take effect per the documented
  read-fresh-at-use contract (worker start, enqueue-time constraints, posting-time toggles);
  reset-to-defaults restores code defaults.
- **Failure behavior:** invalid inputs are clamped/validated at write time (quality
  360/720/1080 — AppConfig.kt:361-369; interval validated against the offered set —
  AppConfig.kt:339-345); an unusable output tree surfaces its reason without blocking
  downloads.
- **Verification status:** VERIFIED (live device journey 2026-10-18, step 24 — flip a
  setting, restart, persisted) + VERIFIED (JVM tests — `ThemeSettingsTest`,
  `SliderGestureTest`, `OutputTreeLabelTest`, `OutputLocationDescriptionTest`) +
  VERIFIED (instrumented, device REDACTED-SERIAL user-level test run — `SettingsContentTest`,
  `SettingsPersistenceTest`, `ThemePersistenceTest`, `SettingsOutputLocationTest`).
- **Known limitations:** no global audio preference exists by design (per-episode audio);
  a legacy inert DataStore byte from an older key may remain, unread.
- **Related tests:** as above.
- **Source files:** `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`,
  `data/config/AppConfig.kt`, `ui/theme/Theme.kt`, `domain/model/AppContext.kt`.

## 22. Process-death reconciliation

- **Purpose:** after process death, force-stop, crash or network interruption, rebuild an
  honest download state from persistence — no stuck rows, no phantom progress.
- **Entry point:** automatic on app startup (`DownloadStateReconciler`) and at worker
  boundaries (CAS state guards).
- **Production UI reachability:** implicit — the Downloads/Sessions screens simply show
  correct states after relaunch.
- **Implementation components:** `DownloadStateReconciler` (per-state rules, outputUri-aware
  COMPLETED invariant — DownloadStateReconciler.kt:36-54, idempotent export retry, orphan
  segment cleanup), `DownloadWorker.claimEntryState` CAS entry, `updateErrorIfState`
  guards, `EpisodeOutputVerifier` (shared validity rule).
- **State & persistence:** Room is the single source of truth; nothing depends on the UI
  process being alive; PAUSED is never treated as stale (DownloadStateReconciler.kt:45-47).
- **Expected behavior:** QUEUED-with-stream → re-enqueued; RESOLVING → QUEUED; DOWNLOADING
  with a dead worker → re-enqueued; ASSEMBLING → output checked; COMPLETED → invariant
  verified, export retried if needed, orphaned segments cleaned; FAILED/CANCELLED terminal;
  a COMPLETED row whose exported file the user deleted demotes to FAILED ("file missing")
  with Retry.
- **Failure behavior:** demotion happens only after a fresh re-read; export outcomes are
  logged but never flip state; reconciliation is idempotent and crash-repeatable.
- **Verification status:** VERIFIED (live device journey 2026-10-18, steps 21–22 —
  force-stop mid-download → relaunch reconciles; session recovery) +
  VERIFIED (JVM tests — `DownloadConfigTest`, `EpisodeDownloadEntityTest`) +
  VERIFIED (instrumented, device REDACTED-SERIAL user-level test run — `ProcessDeathRecoveryTest`,
  `RealProcessDeathSeedTest`, `RealProcessDeathPausedDeviceTest`,
  `DownloadStateReconcilerTest`, `DownloadWorkerStateGuardTest`, `StateTransitionTest`).
- **Known limitations:** continuation after device reboot relies on WorkManager's own
  retry/enqueue semantics plus this reconciler; there is no OS-level boot receiver beyond
  what WorkManager provides.
- **Related tests:** as above.
- **Source files:** `data/download/DownloadStateReconciler.kt`,
  `data/download/EpisodeOutputVerifier.kt`, `worker/DownloadWorker.kt`.

---

## Cross-feature notes

- **Implemented-and-wired vs test-only vs deferred:** every feature above is implemented and
  wired into production UI. Items marked TEST-ONLY concern *exercising* a production path
  (e.g. the audio option probe chain, the 100-episode page batch, the SAF live flow, the
  watchlist background notification), not missing functionality. Genuinely deferred:
  in-player audio/quality switching (§6, §19), SAF destination probing (§13, §15).
- **Accepted cosmetic behavior:** MediaStore may hold a uniquified ` (1)` display name
  after a Replace-file retirement (§14).
- **Persistence rule of thumb (all features):** UI state is never authoritative; Room +
  DataStore reconstruct everything after process death (see §22).
