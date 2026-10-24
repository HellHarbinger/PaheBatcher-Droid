# PHASE_A_FINDING_INVENTORY.md — every Phase A finding, once, with its A2.1 disposition

**Purpose.** This is the Phase A2.1 deliverable that freezes the complete finding ledger: every
finding ID issued by the six area audits (NET/PB/DATA/DL/UI/TEST), the whole-codebase review
(AQR series), and the A2 final-review dispositions (FR series) — each exactly once, with the
status it holds after A2.1 and the evidence for that status.

**Scope note (what this file is NOT).** This is an inventory and disposition record only. It
is not a re-audit, not a plan, and explicitly **not Prompt B** — no Phase B scope, design, or
task is derived here. No finding text is paraphrased into new claims; each row names the
original defect in short form and points at where its resolution lives.

**Method.** Statuses below were established from: the original audit texts (Temp
`pb_a2_audit\*.txt`), the DECISIONS.md fix ledger (entries 158–160 for Phase A/A2/A2.1, §161 for the
2026-10-20 closure round, §162 for the PB-004/UI-008 round, and §163 for the 2026-10-21
closure-gap remediation round — covering this ledger's newest rows), the A2.1 implementation batches A–I with their executed test runs, and
direct source inspection during A2.1. Where a status says "device pending", the code compiles
against the device suite but the instrumentation run belongs to a deploy round, not to this
session — that distinction is preserved rather than blurred. As of the 2026-10-20 closure round no
current row carries `FIXED — COMPILED, DEVICE TEST PENDING` (its only former member, PB-004,
moved after its live device run); the definition is retained above for historical rows.

## Status vocabulary (9 statuses)

| Status | Meaning |
|---|---|
| **FIXED — VERIFIED** | Fixed; JVM tests executed green this round or at landing (or the fix is logic fully exercised by an executed suite). |
| **FIXED — COMPILED, DEVICE TEST PENDING** | Fixed; the dedicated verification is an instrumentation test that compiles but has not yet executed on a device. |
| **FIXED — SOURCE-VERIFIED** | Fixed by construction (doc/DI/comment-level change); verified by source inspection and compilation, no dedicated test exists or is warranted. |
| **BY DESIGN — NOT A DEFECT** | Re-verified as deliberate, documented behavior; closed without code change. |
| **DUPLICATE — RECONCILED** | Same defect as another issued ID; tracked to the canonical ID's fix. |
| **INFORMATIONAL — ACCEPTED** | Informational finding; recorded, no action required. |
| **DEFERRED — USER DECISION REQUIRED** | Open product/UX decision; not touched without the user's direction. |
| **DEFERRED — DOCUMENTED** | Deferred with written justification on the record (DECISIONS.md). |
| **FOLLOW-UP TASK — RECORDED** | Explicit out-of-scope task text recorded for a later round (build-config/release scope). |

Never-issued ID gaps in the AQR space are recorded in the register at the end, outside the
status taxonomy (they are ID-space bookkeeping, not findings).

---

## 1. Area findings — NET (15)

| ID | Finding (short) | Status | Basis |
|---|---|---|---|
| NET-001 | `catch (e: Exception)` swallows `CancellationException` across the session transport (High) | FIXED — VERIFIED | Phase A (DECISIONS 158.2/158.3); JVM CE-regression tests from A2 (AQR-017/051); re-verified in A2.1 Batch A suites. |
| NET-002 | Session validity/freshness checks unreachable in production | DEFERRED — USER DECISION REQUIRED | Wiring the check changes behavior (extra IO + proactive invalidation); re-listed in 159.5, unchanged in A2.1. |
| NET-003 | `decodeJsonString` mis-orders backslash unescaping, duplicates the correct decoder | FIXED — VERIFIED | Phase A (158.3): delegates to the shared decoder. |
| NET-004 | Any failed page fetch retries and store-wide-wipes the Cloudflare session | FIXED — VERIFIED | **A2.1 Decision 2 (Batch A):** narrow invalidation — only a page result whose HTTP status is 401/403 invalidates; `AndroidWebViewRequestBridgeRecoveryTest` 7/7 green (session-invalid, timeout, other-HTTP, cancellation, recovery cases). |
| NET-005 | Repeated `Regex` compilation on parser/resolver hot paths | FIXED — VERIFIED | Phase A + **A2.1 Batch B** residual hoists (`EpisodeRangeParser`, `HlsPlaylistParser`); focused run 118 tests / 9 suites green. |
| NET-006 | `release()`/`releaseSession()` dead in production and race an in-flight establishment | DEFERRED — USER DECISION REQUIRED | Dead-code removal vs guard is a lifecycle decision; 159.5 deferral stands. |
| NET-007 | Dead code: `extractPageContent`, `extractKwikUrls`, unused import | FIXED — VERIFIED | Phase A (158.3) + A2 Batch 1 (unused import). |
| NET-008 | No-op persisted-session read on every non-forced establishment | FIXED — VERIFIED | Phase A (158.3): dead read removed. |
| NET-009 | `LogUrlSanitizer` coverage gaps on URL-bearing log/error paths | FIXED — VERIFIED | A2 Batch 1; extended in A2.1 Batch A (bridge diagnostics, AQR-025). |
| NET-010 | `cloudflareChallengeCompleted` reported `true` unconditionally | FIXED — VERIFIED | Phase A (158.3): real outcome threaded through. |
| NET-011 | One malformed `year` discards the entire search result list | FIXED — VERIFIED | Phase A (158.3): per-field tolerant parsing. |
| NET-012 | Menu regex can truncate the resolution menu at a nested `</div>` | DEFERRED — USER DECISION REQUIRED | Needs validation against a real reference page first; 159.5 deferral stands. |
| NET-013 | `CookieManager.getCookie` (blocking IPC) on the main looper | FIXED — VERIFIED | A2 Batch 1 (off-main read). |
| NET-014 | Debug-only validation spikes remain in the production source set | FIXED — VERIFIED | **A2.1 Decision 3 (Batch C):** variant source-set split — 3 validators + `ValidationScreen` moved to `src/debug`, no-op `ValidationOverlayEntry` twin in `src/release`, runtime `BuildConfig.DEBUG` gate kept; **no build-config change**; `ValidationSpikeTest` 6/6 green from `testDebug`, `compileReleaseUnitTestKotlin` SUCCESS (release tree carries zero spike classes). |
| NET-015 | Minor duplication: `loggableHostPath` vs `LogUrlSanitizer.url` | FIXED — VERIFIED | A2 Batch 1 (`StreamResolverImpl`). |

## 2. Area findings — PB (10)

| ID | Finding (short) | Status | Basis |
|---|---|---|---|
| PB-001 | Batch-failure notification shares PendingIntent request code `-9002` with the media session activity (High) | FIXED — VERIFIED | Phase A (158.2): dedicated `BATCH_CONTENT_REQUEST_CODE`; A2.1 Batch B added the per-episode namespace mask (AQR-054) with `DownloadNotificationTextTest` 9/9 green. |
| PB-002 | `onGetSession` throws instead of returning `null` when no session exists | FIXED — VERIFIED | Phase A (158.3); A2.1 re-verified the rejection path still returns `null` (never throws) at both media3 gates. |
| PB-003 | Media-session start/stop fire-and-forget: stop can overtake start | FIXED — VERIFIED | Phase A (158.3): synchronous calling-thread calls; A2.1 re-verified by compile + `MediaSessionReleaseOrderTest` shape. |
| PB-004 | `startForegroundService` with no guaranteed `startForeground` inside the 5-second FGS contract | FIXED — VERIFIED | **A2.1 Decision 7 (Batch G)** guarded the no-player/build-failure exits (`PaheMediaSessionService.exitWithoutSession()` — `startForeground` placeholder id `-9004` → `STOP_FOREGROUND_REMOVE` → `stopSelf`, session branch left media3-owned). **Live verification 2026-10-20, device REDACTED-SERIAL, `MediaSessionServiceForegroundExitTest`: case 1 PASSED** — the guarded no-player exit satisfies the real system window (test survives >8 s, placeholder never lingers, `stopService` returns false, no exception); **case 2 FAILED** — the session path raised the real `android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground()` for the call at `MediaSessionHost.kt:107` (`onPlayerCreated`), because an empty player never posts a media notification, so media3 never promotes within the window (historical record retained; pre-fix XML `archive/CLOSURE-2026-10-20-MediaSessionServiceForegroundExitTest.xml`, 2 tests F=1). **FIXED 2026-10-21 (authorized PB-004 round, no service redesign):** `MediaSessionHost.startService` now issues a plain `Context.startService()` — no platform FGS contract is created at player creation, so the window cannot be missed; media3 1.11.1 owns promotion end-to-end (traced from the official androidx/media tag `1.11.1` sources: `MediaNotificationManager.shouldRunInForeground` = `playWhenReady` && READY/BUFFERING, empty timeline ⇒ `shouldShowNotification` false ⇒ `removeNotification()` — the pre-fix contract was unsatisfiable on the session path; at promotion media3 re-issues its own foreground-start self-intent). Both call sites are user-initiated from the foreground, where plain starts are legal; refused starts stay defined by the existing try/catch; `PaheMediaSessionService.exitWithoutSession()`'s placeholder contract exit is retained for externally-arriving foreground starts (service changes were KDoc-only). **Device evidence REDACTED-SERIAL:** `archive/PB004-2026-10-20-MediaSessionServiceForegroundExitTest.xml` 3/0/0 (idle session path held across the >8 s window, active-playback promotion with the real MP4, external foreground-start exit), `PB004-2026-10-20-{MediaSessionLifecycleTest 1/0/0, MediaSessionReleaseOrderTest 1/0/0, PlayerLifecycleTest 8/0/0, SafPlaybackDeviceTest 2/0/0}`, and the post-review final chain `archive/FINAL-2026-10-21-*` (14 classes, 55 tests, 0F/0E). Review AQR-071..077 (verdict READY AFTER FIXES, 0 Critical/0 High): documentation, KDoc, and notification-clear findings applied; three informational edges recorded (rows below). |
| PB-005 | Two `DownloadNotificationManager` instances despite a "same object" contract | FIXED — SOURCE-VERIFIED | **A2.1 Batch H:** `PaheBatcherApplication` now exposes the container's single instance (`appContainer.downloadNotificationManager`); the second construction deleted. |
| PB-006 | Dead code in the two playback files (unused imports, pass-through `onBind`, unread intent extra) | FIXED — VERIFIED | Phase A (158.3) + A2 Batch 3 (`EXTRA_EPISODE_ID`, AQR-031). |
| PB-007 | `KEY_NOTIFICATION_PERMISSION_GRANTED` written but never read; inconsistent threading | FIXED — SOURCE-VERIFIED | A2 corrected the KDoc; **A2.1 Batch H** fixed the residual misleading phrase ("always refreshed opportunistically") in `NotificationPermissionRepository`; **A2.1 Decision 8:** key deletion declined — the persisted key is preserved. |
| PB-008 | Mutable, publicly shared session state with split ownership | DEFERRED — DOCUMENTED | Owner-method funneling touches the player lifecycle; written justification in DECISIONS 159.5. |
| PB-009 | `MediaSessionLifecycleTest`'s "media notification" heuristic mislabels `-9002` | FIXED — SOURCE-VERIFIED | **A2.1 Batch B:** comment-only correction at `MediaSessionLifecycleTest` (heuristic scope documented; assertions untouched — no coverage changed). |
| PB-010 | Import ordering deviates in two notification files | FIXED — VERIFIED | A2 Batch 3. |

## 3. Area findings — DATA (15)

| ID | Finding (short) | Status | Basis |
|---|---|---|---|
| DATA-001 | Watchlist `relink()`/`addOrUpdate()` multi-step writes with no transaction | FIXED — VERIFIED | Phase A (158.2, Room migration + transactional DAO path). |
| DATA-002 | Blocking filesystem I/O on the main thread | FIXED — VERIFIED | Phase A (158.2). |
| DATA-003 | `EpisodeRepository.openedPages` unbounded, mutated on a read path | FIXED — VERIFIED | A2 Batch 2: LRU cap `MAX_CACHED_SESSIONS = 8` + `EpisodeRepositoryLazyListingTest` (9 tests; FR-02 attributes the counting). |
| DATA-004 | `AnimeRepository.search()`/`fetchTitle()` swallow `CancellationException` | FIXED — VERIFIED | Phase A (158.3). |
| DATA-005 | `SessionRepository.upsert()` create branch races | FIXED — VERIFIED | Phase A (158.3). |
| DATA-006 | App-wide per-host limiter built from a constant, not persisted `hls_workers` | FIXED — VERIFIED | **A2.1 Decision 6 (Batch F):** `HlsHostConcurrencyLimiter(limitProvider)` — persisted `hls_workers` read fresh per host's first sighting, clamped to the settings band (8..32) with a floor of 1 and fallback 24 on read failure; settings-change semantics = new hosts get the current value, tracked hosts keep creation-time capacity (documented); `HlsHostConcurrencyLimiterTest` 12/12 green (5 new Decision-6 cases: fresh-read, tracked-host stability, floor, fallback, cancellation propagation). |
| DATA-007 | Dead Room `TypeConverter` pairs | FIXED — VERIFIED | A2 Batch 2 (deleted after schema byte-identity check). |
| DATA-008 | Duplicated/contradictory cache-TTL logic; unused `deleteExpired` | FIXED — VERIFIED | A2 Batch 2 (`ScanCacheRepository` delegation). |
| DATA-009 | Derived DataStore flows lack `distinctUntilChanged()` | FIXED — VERIFIED | Phase A (158.3). |
| DATA-010 | Duplicated `DownloadWorkerFactory` composition | FIXED — VERIFIED | Phase A (158.3: one `workerFactory` lazy at both WorkManager compositions). |
| DATA-011 | `SessionRepository.summaries()` N+1 count queries + unused API surface | DEFERRED — DOCUMENTED | Query-shape change + API removal needs a dedicated pass; written justification in DECISIONS 159.5. |
| DATA-012 | A new `OkHttpHlsClient` (and connection pool) per `DownloadWorker` | FIXED — SOURCE-VERIFIED | **A2.1 Decision 5 (Batch E):** one app-scoped `hlsClient` + `aesKeyCache` in `AppContainer`, injected through `DownloadWorkerFactory` (auth stays per-request; OkHttp + `ConcurrentHashMap` thread-safe); compiled across debug/androidTest. |
| DATA-013 | Avoidable per-call allocations in hot loops | FIXED — VERIFIED | Phase A (158.3). |
| DATA-014 | `removeBySession` returns a tautological boolean | FIXED — VERIFIED | Phase A (158.3). |
| DATA-015 | Unused imports | FIXED — VERIFIED | Phase A (158.3). |

## 4. Area findings — DL (11)

| ID | Finding (short) | Status | Basis |
|---|---|---|---|
| DL-001 | Master-playlist path drops the `onAssemblyStart` hook | FIXED — VERIFIED | Phase A (158.2, hook forwarded + test extended). |
| DL-002 | Master-playlist variant playlist fetched twice; `variantContent` dead | FIXED — VERIFIED | Phase A removed the dead value; **A2.1 re-verified** the retained double-fetch is the documented classification pre-flight (`EpisodeDownloader.kt` — "Pre-flight the variant so an expired/unreachable variant is classified…" comment at the fetch site): the first fetch classifies EXPIRED vs TRANSPORT before the recursive re-fetch, so the behavior is intentional and documented, not dead code. |
| DL-003 | `HlsClient.getText/getBytes` per-request `timeoutMs` silently ignored | FIXED — VERIFIED | Phase A (158.3, derived per-call timeout client). |
| DL-004 | Segment fan-out uses a detached `CoroutineScope` | FIXED — VERIFIED | Phase A (158.3, `supervisorScope` children). |
| DL-005 | Reconciler's COMPLETED branch unconditionally deletes segments, defeating `keepTemp` | FIXED — VERIFIED | **A2.1 Decision 4 (Batch D):** `keepTempProvider` on `DownloadStateReconciler` gates the segment sweep (read fresh per row); `DownloadWorker.commitReplacementOutput` staging deletion gated the same way; wiring via `PaheBatcherApplication.keepTempProvider` (`currentAppContext().keepTemp`); `EpisodeDownloaderTest` 30/30 green (completion legs cover keepTemp true/false); 3 new `DownloadStateReconcilerTest` device cases (keepTemp on/off, per-pass flip) EXECUTED on device 2026-10-20 — the class ran 21/21 green via `connectedDebugAndroidTest`. **Closure round 2026-10-20 (keepTemp audit GAP-1..5 + staging ON-leg, tests only, production unchanged):** 7 new tests + 1 strengthened existing test (8 test items), all green — JVM `EpisodeDownloaderTest` **34/34** (`keepTemp false removes completed segments after successful assembly and keeps the output`; `assembly failure preserves completed segments and reports no output`; `transient failure keeps committed segments and the retry reuses them`; `cancellation during segment download keeps committed segments and never cleans up`); device `DownloadStateReconcilerTest` **23/23** (+`reconcile_assembling_withOutput_repairsToCompleted_withoutTouchingSegments_thenSweepHonoursKeepTemp`, +`reconcile_assembling_withoutOutput_reenqueuesAndKeepsSegments`, and `reconcile_completed_withMissingOutput_marksFailed` strengthened with segment-survival asserts); device `ReplaceFileOutputSafetyTest` **10/10** (+`a_successful_replacement_with_keepTemp_true_retains_staging_and_still_commits_one_valid_output`);
all three device result XMLs are retained in `app/build/outputs/androidTest-results/connected/archive/CLOSURE-2026-10-20-*.xml`
(the reconciler 23/23 and replacement 10/10 runs re-executed 17:38 after the closure review's
AQR-064 evidence-retention finding). |
| DL-006 | `triggerNextDeferredEpisode` blocking `ListenableFuture.get()` | FIXED — VERIFIED | Phase A (158.3) + A2 Batch 2 (shared `WorkManagerAwait`, AQR-006). |
| DL-007 | A new `OkHttpHlsClient` (fresh `OkHttpClient`) and `AesKeyCache` per Worker | FIXED — SOURCE-VERIFIED | **A2.1 Decision 5 (Batch E)** — with DATA-012, single shared client + key cache from `AppContainer` (DI hand-off compiles debug/androidTest; no dedicated factory test exists). |
| DL-008 | `executePipeFallback` swallows `CancellationException` | FIXED — VERIFIED | Phase A (158.3). |
| DL-009 | `doneIndices()` directory walk on the per-segment hot path | FIXED — VERIFIED | Phase A (158.3). |
| DL-010 | `writeSeg` rename fallback deletes the final `.ts` before retrying | FIXED — VERIFIED | Phase A (158.3). |
| DL-011 | Documented limitations in this subsystem (recorded, not defects) | BY DESIGN — NOT A DEFECT | Re-verified: entries correspond to LIMITATIONS.md records (e.g., `setForeground` silent-downgrade). |

## 5. Area findings — UI (12)

| ID | Finding (short) | Status | Basis |
|---|---|---|---|
| UI-001 | `runBlocking` in `PlayerViewModel.onCleared()` blocks main thread with a Room write | FIXED — VERIFIED | Phase A (158.3). |
| UI-002 | Stray `@Composable` turned a pure formatter into a composable | FIXED — VERIFIED | Phase A (158.3). |
| UI-003 | Notification-permission `LaunchedEffect(Unit)` can re-launch across config change | FIXED — VERIFIED | Phase A (158.3). |
| UI-004 | Whole-number episode formatting duplicated in six places, two different predicates | FIXED — VERIFIED | **A2.1 Batch I:** `StringUtils.episodeNumber` is the one renderer (round-trip predicate — the `% 1f` variant silently broke past Long precision); six sites delegate (`EpisodeInfo.epStr`, `compactEpRange`, `AnimeDetailsViewModel.episodeStr`, `AnimeDetailsScreen.renderEpisodeNumber`, `DownloadsScreen.episodeLabel`, `PlayerViewModel.titleFor`); `StringUtilsTest` 28/28 green (3 new table tests incl. the huge-value guard). |
| UI-005 | `MainActivity` collects with `collectAsState` instead of lifecycle-aware collector | FIXED — VERIFIED | Phase A (158.3). |
| UI-006 | `runCatching` in `SessionsViewModel` swallows `CancellationException` | FIXED — VERIFIED | Phase A (158.3) + A2 (AQR-002); re-verified in A2.1 Batch A (SessionPrompt suite 3/3). |
| UI-007 | `WatchlistViewModel` repository calls have no failure path | FIXED — VERIFIED | Phase A (158.3) + A2 (AQR-008). |
| UI-008 | Player seek `Slider` driven by the 500 ms ticker while drag seeks live | FIXED — VERIFIED | Not reproduced on device; the fix is instrumentation-only UI work — deferred per this round's scope decision. **Closure round 2026-10-20:** re-verified — no instrumentation target exists for this finding (repo-wide search of `androidTest`: no test exercises `onValueChangeFinished`, drag suppression, or ticker-vs-drag; existing slider tests cover only size/target/`SetProgress` — never drag-vs-ticker), and the drag-hold fix itself remains unimplemented, so UI-008 stays OPEN pending the user decision (historical record retained). **FIXED 2026-10-21 (authorized UI-008 round):** contract derived from DECISION 98 (commit-on-finish slider posture: the dragged value held in local UI state until `onValueChangeFinished`) and this row's recorded fix shape — not invented. `PlayerScreen.progressSlider` now holds `dragFraction` locally (the ticker-derived value is suppressed for the slider during the gesture), seeks exactly ONCE at `onValueChangeFinished` (duration read from the player at commit time, `>0` guarded, player null-guarded), then clears unconditionally; interrupted gestures (controls hide → slot leaves composition, or player released) drop the held value without seeking [superseded 2026-10-21 — AQR-091: that clause was untested; the closure-gap round narrowed the production KDoc to the source-established contract and added a pointer-cancel device test pinning it — a cancelled drag ends at the same single finish point: one null-guarded commit when a player is present, none when null (rows AQR-091/AQR-118, DECISIONS 163)]; post-commit the ticker resumes (≤1 tick of pre-seek value possible, documented). New device suite `PlayerSeekSliderDragTest` 3/0/0 (`archive/UI008-2026-10-21-PlayerSeekSliderDragTest.xml`): real down/move/hold/up gestures plus the accessibility `SetProgress` route, asserting via `playerProbe` (authoritative VM-owned ExoPlayer state), the VM ticker's own `uiState.positionMs`, and a literal `Player.Listener` seek counter (0 during the gesture, exactly 1 after release, still 1 after further ticks — no late duplicates). Regressions: `UI008-2026-10-20-{TouchTargetAndFocusTest 5/0/0, PlayerLifecycleTest 8/0/0, MediaSessionLifecycleTest 1/0/0}` (size/target/`SetProgress` preserved) plus the final chain `archive/FINAL-2026-10-21-*` (14 classes, 55 tests, 0F/0E). Review AQR-078..083 (verdict READY): SetProgress coverage + seek-count/test-hygiene strengthens applied; shared-slider refactor (AQR-079) deferred with rationale; hardware keyboard/D-pad adjustment remains unverified (not drivable from instrumentation) — recorded. |
| UI-009 | Static `SimpleDateFormat` shared by the Console | FIXED — VERIFIED | Phase A (158.3). |
| UI-010 | Mapper throws inside the Downloads UI flow, outside any error path | FIXED — VERIFIED | **A2.1 Decision 9 (Batch I):** `DownloadRowMapper.mapAll` — per-row isolation; `CancellationException` rethrown, valid rows kept, sanitized note via `DownloadsUiState.loadError` (rendered with the established banner pattern; `"://" !in message` rule); `DownloadRowMapperTest` 29/29 green (3 new: siblings survive, clean emission silent, hostile-URL message replaced by exception type). |
| UI-011 | Stale screen KDoc contradicts the implemented Phase 17 stream action | FIXED — VERIFIED | Phase A (158.3). |
| UI-012 | Deliberate, already-documented items (recorded, not defects) | INFORMATIONAL — ACCEPTED | Re-verified as repository-convention records. |

## 6. Area findings — TEST (14)

| ID | Finding (short) | Status | Basis |
|---|---|---|---|
| TEST-001 | `MigrationTest` never exercises the v3→v4 upgrade | FIXED — VERIFIED | Phase A (158.3): migration tests added (also closed AQR-020's evidence gap). |
| TEST-002 | `ProgressThrottlingTest` stale-write assertion can never fail | FIXED — VERIFIED | Phase A (158.3). |
| TEST-003 | WorkManager unique-work name duplicated as raw literal in 28 device-test files | DEFERRED — DOCUMENTED | Test-tree-wide helper refactor; written justification in DECISIONS 159.5. |
| TEST-004 | Test helper hardcodes the notification channel id (vacuous assertion) | FIXED — VERIFIED | Phase A (158.3). |
| TEST-005 | Export-location wording hardcoded in 4 places across 3 test files | DEFERRED — DOCUMENTED | Needs the production string owner as a friend-module call; written justification in DECISIONS 159.5. |
| TEST-006 | Four unused JVM test dependencies; `androidx.test:core` in the wrong source set | DEFERRED — USER DECISION REQUIRED | Any dependency/source-set change is build-config scope, excluded from this round by standing constraint. |
| TEST-007 | Dual-audio scan-page fixture copy-pasted into 6 test files | DEFERRED — DOCUMENTED | Test-tree-wide fixture consolidation; written justification in DECISIONS 159.5. |
| TEST-008 | Two dead helpers in `Phase11UiTestSupport` | FIXED — VERIFIED | A2 (159.3): deleted (zero callers re-confirmed). |
| TEST-009 | Three duplicated polling helpers with divergent failure semantics | DEFERRED — DOCUMENTED | Test-tree-wide helper consolidation; written justification in DECISIONS 159.5. |
| TEST-010 | Assertions that can never fail (enum `assertNotNull`s, conditional assertions) | FIXED — VERIFIED | A2 (159.3): five sites replaced with strictly stronger falsifiable checks. |
| TEST-011 | Scheduler test asserts constraints via `Constraints.toString()` | FIXED — VERIFIED | A2 (159.3): value-equality assertion against a baseline `Constraints`. |
| TEST-012 | `gradle.properties` pins machine-specific JDK/SDK paths | FIXED - VERIFIED | **A2.1 Decision 10** executed 2026-10-24 (privacy-release-prep Stage 2): removed `org.gradle.java.home` + `android.sdk.dir`; SDK now from gitignored `local.properties`, JDK from `JAVA_HOME`; `jvmToolchain(25)` kept. Verified with clean `assembleDebug` + `testDebugUnitTest` (1,248/0/0) on the primary machine. The "second machine" clause of Decision 10 remains open (no second machine available to this round). |
| TEST-013 | `values-night/` resources byte-identical to `values/` | BY DESIGN — NOT A DEFECT | Closed this round: the duplication is the deliberate dark-first-frame policy pinned by `ThemePersistenceTest`; no change. |
| TEST-014 | `proguard-rules.pro` inert while release minification is disabled | FOLLOW-UP TASK — RECORDED | **A2.1 Decision 11:** task text recorded — a real R8 release configuration (enable minification for release, validate the shrinking-sensitive surfaces, run the gated suite against the release build) as a separate release-scope task. Not executed this round. |

## 7. Whole-codebase review findings — AQR (123 issued)

Original issued set: AQR-001..024, 030..033, 040..046, 050..052 (38). A2.1 issued two new IDs (AQR-025, AQR-054) and its
final review issued nine more (AQR-055..063); the 2026-10-20 closure review issued seven more (AQR-064..070);
the 2026-10-21 PB-004 implementation review issued seven more (AQR-071..077) and the UI-008 implementation
review issued six more, recorded as AQR-078..083 (its reviewer labels 075..080 were renumbered on recording
to avoid colliding with the PB-004 batch); the 2026-10-21 Prompt 1 closure audit issued 48 more
(AQR-084..131: the 28 adjudicated findings AQR-084..111, the coordinator's three corrections AQR-112..114,
six authoritative-but-uncovered findings AQR-115..120, and eleven GLM-supplementary findings
source-verified before entry AQR-121..131); and the independent review gate for that round issued
six documentation-integrity findings AQR-132..137, all fixed in place before closure; all are
fixed or dispositioned in the round they were found.

| ID | Finding (short) | Status | Basis |
|---|---|---|---|
| AQR-001 | `performPageFetch` CancellationException arm unreachable | FIXED — VERIFIED | A2 (159): catch-order fix. |
| AQR-002 | `SessionsViewModel` `runCatching` swallows CE (delete/clear-cache) | FIXED — VERIFIED | A2 (159); re-verified A2.1 Batch A. |
| AQR-003 | `FfmpegEpisodeAssembler` blocking body off the IO dispatcher | FIXED — VERIFIED | A2 (159): `withContext(Dispatchers.IO)`. |
| AQR-004 | CE arms on reconciler loop + app startup block | FIXED — VERIFIED | A2 (159). |
| AQR-005 | `OkHttpHlsClient.timeoutClients` companion cache vs per-instance base | FIXED — VERIFIED | A2 (159): instance-level cache; A2.1 Batch E shares the instance process-wide. |
| AQR-006 | Two copies of the `suspendCancellableCoroutine` WorkManager bridge | FIXED — VERIFIED | A2 Batch 2: shared `WorkManagerAwait`. |
| AQR-007 | `JsPacker.encodeBaseN` `StackOverflowError` on crafted payload | FIXED — VERIFIED | A2 (159): base-range guard + 3 tests. |
| AQR-008 | `WatchlistViewModel` checker calls without a failure path | FIXED — VERIFIED | A2 (159). |
| AQR-009 | CE arms missing at four sites (settings/details/app-vm) | FIXED — VERIFIED | A2 (159). |
| AQR-010 | Exported `MediaSessionService` accepts every controller | FIXED — VERIFIED | A2 (159.2, user-directed Option 1 policy); verified at both media3 rejection gates with a falsifiable 8-case suite. |
| AQR-011 | Verification/coverage gaps around the CE contract (informational) | INFORMATIONAL — ACCEPTED | Recorded; the concrete gaps were later closed by AQR-017/051 tests. |
| AQR-012 | `allowBackup="false"` plus backup rule files (informational) | INFORMATIONAL — ACCEPTED | Redundant attributes, no security impact at minSdk 32; recorded. |
| AQR-013 | UI force-refresh is a third WebView entry point not taking `webViewPageLock` | FIXED — VERIFIED | A2 Batch 1: lock-wrap on the force-refresh path. |
| AQR-014 | A 401/403 starts two independent recovery runs (quiet refresh + bridge retry) | FIXED — VERIFIED | **A2.1 Decision 1 (Batch A):** `SessionRefreshSignal.withStatusReportsSuppressed` makes the bridge the single recovery owner around `fetchExclusively`; user prompts NOT suppressed; `AndroidWebViewRequestBridgeRecoveryTest` 7/7 + SessionPrompt 3/3 + SessionRefreshSignal 4/4 green. |
| AQR-015 | Two comments still claim the bridge/session swallow CE | FIXED — VERIFIED | A2 Batch 1. |
| AQR-016 | `SessionPersistence` write-only abstraction + dead release API | DEFERRED — DOCUMENTED | Interface surgery needs a dedicated pass; written justification in DECISIONS 159.5. |
| AQR-017 | No regression tests for the three NET-001 cancellation rethrows | FIXED — VERIFIED | A2 (159.3): JVM cancellation tests landed (`AndroidWebViewRequestBridgeCancellationTest`). |
| AQR-018 | `JsPacker.unpack` iterates a remote-supplied `count` unbounded | FIXED — VERIFIED | A2 Batch 1: absurd-count clamp + 2 tests. |
| AQR-019 | Residual `catch (Exception)` arms can still swallow cancellation | FIXED — VERIFIED | A2 Batch 1: CE arms added at the three named sites. |
| AQR-020 | `MigrationTest` v3→v4 evidence gap (androidTest assets) | FIXED — VERIFIED | Migration tests (TEST-001) + A2 Batch 2 `4.json` asset (AQR-050). |
| AQR-021 | Raw `${streamInfo.url}` logged in `EpisodeDownloader` | FIXED — VERIFIED | A2 Batch 2: `LogUrlSanitizer.url`. |
| AQR-022 | `HlsHostConcurrencyLimiter` `getOrPut` non-canonical semaphore race | FIXED — VERIFIED | A2 Batch 2; A2.1 Batch F re-verified the single-retained-instance guarantee under the new suspend provider (`putIfAbsent` discard-unused-loser, KDoc'd). |
| AQR-023 | Assembler deletes target before the second rename attempt | FIXED — VERIFIED | A2 Batch 2: atomic-rename order. |
| AQR-024 | Force-refresh without lock (same defect family as AQR-013, different reviewer round) | DUPLICATE — RECONCILED | Reconciled to AQR-013; fixed by the same lock-wrap. |
| AQR-030 | `MediaSessionHost` teardown not owner-qualified (latent) | DEFERRED — DOCUMENTED | Touches the player lifecycle; written justification in DECISIONS 159.5. |
| AQR-031 | Dead `EXTRA_EPISODE_ID` payload written, never read | FIXED — VERIFIED | A2 Batch 3: removed. |
| AQR-032 | MediaSession failure paths not exception-safe (release-once risk) | FIXED — VERIFIED | A2 Batch 3: orphaned-session release, best-effort, cannot mask the original failure. |
| AQR-033 | PB-010 identifier used for two unrelated defects in the record | FIXED — SOURCE-VERIFIED | A2 (159.4): record hygiene applied to DECISIONS entry 158. |
| AQR-040 | Relink completion handler validates only `state.relink` | FIXED — VERIFIED | A2 Batch 4: guarded completion path. |
| AQR-041 | Settings ViewModel unguarded launches | FIXED — VERIFIED | A2 Batch 4: `projectSafely` at three flows. |
| AQR-042 | `PlayerViewModel` persistence writes unguarded | FIXED — VERIFIED | A2 Batch 4: `saveQuietly` at all three write sites. |
| AQR-043 | Dead `dismissMessage()` | FIXED — VERIFIED | A2 Batch 4: deleted (zero references; wiring is AQR-046's decision, not taken). |
| AQR-044 | Unused import in `MainActivity` | FIXED — VERIFIED | A2 Batch 4. |
| AQR-045 | Real cache-clear failures report "nothing cleared" wording | DEFERRED — USER DECISION REQUIRED | Wording is a product call; confirmed unchanged in A2.1. |
| AQR-046 | `startRelink` maps any exception to the "unreachable" message | DEFERRED — USER DECISION REQUIRED | Error-taxonomy wording is a product call; confirmed unchanged in A2.1. |
| AQR-050 | `4.json` schema missing from androidTest assets (High) | FIXED — VERIFIED | A2 Batch 2: asset added; migration tests run against it. |
| AQR-051 | Add JVM regression tests for the two feasible CE contracts | FIXED — VERIFIED | A2 (159.3): landed as the (a)/(b)-labelled cancellation tests. |
| AQR-052 | Mojibake in test KDoc (cosmetic) | FIXED — VERIFIED | A2 (159.3): both sites fixed; whole test tree re-grepped. *Correction 2026-10-21 (AQR-089): that re-grep's pattern missed 11 further mojibake sites — 5 in `PaheBatcherApplication.kt`, 5 in `SettingsPersistenceTest`, 1 in `SessionRepositoryTest`; all 11 fixed in the closure-gap round and the whole `app/src` tree re-verified clean.* |
| **AQR-025** *(issued by A2.1)* | Bridge diagnostics surfaced `e.message` unsanitized (URL-bearing) | FIXED — VERIFIED | **A2.1 Batch A:** `LogUrlSanitizer.message` on the bridge error path (`AndroidWebViewRequestBridge`); pinned by `AndroidWebViewRequestBridgeRecoveryTest`. |
| **AQR-054** *(issued by A2.1)* | Per-episode PendingIntent request codes derived from raw `episodeId.hashCode()` (collision-capable, incl. the `Int.MIN_VALUE` pathology) | FIXED — VERIFIED | **A2.1 Batch B:** `requestCodeFor` masks to the non-negative range in `DownloadNotificationManager`'s companion; `DownloadNotificationTextTest` 9/9 green including the `"polygenelubricants"` `Int.MIN_VALUE` sample and a reserved-negative-code guard. |
| **AQR-055** *(issued by the A2.1 final review)* | Inventory marked DATA-012/DL-007 `FIXED — VERIFIED` on compile-only evidence | FIXED — VERIFIED | Both rows reclassified `FIXED — SOURCE-VERIFIED` (matches the legend: the DI hand-off has no executed test); totals reconciliation updated; citation checker PASS. |
| **AQR-056** *(issued by the A2.1 final review)* | Inventory Method cited a DECISIONS.md A2.1 fix ledger that did not exist | FIXED — VERIFIED | **DECISIONS §160** added (A2.1 scope, Decisions 1–11, verification numbers); Method now cites entries 158–160; citation checker PASS. |
| **AQR-057** *(issued by the A2.1 final review)* | `MainActivity` PendingIntent KDoc mislabelled `-9001` as the episode-download code (it is the watchlist ID/request code) | FIXED — SOURCE-VERIFIED | Comment corrected at the media-session request-code site; numeric behavior unchanged (`MediaSessionLifecycleTest` pins `-9001..-9003`). |
| **AQR-058** *(issued by the A2.1 final review)* | Suppression counter's `webViewPageLock` precondition was convention only | FIXED — SOURCE-VERIFIED | Field/helper KDoc now states the lock precondition and bridge-only call contract; mechanism unchanged. |
| **AQR-059** *(issued by the A2.1 final review)* | No test pins suppression-counter balance (exception/nesting restore) | FIXED — VERIFIED | 3 new `SessionRefreshSignalTest` cases (silent-in-window, exception restores, nested restore) green in the full JVM rerun. |
| **AQR-060** *(issued by the A2.1 final review)* | `"navigate_to"` extras-key literal duplicated `MainActivity.EXTRA_NAVIGATE_TO` at 3 intent sites | FIXED — VERIFIED | The 3 sites now use the constant — a compile-time-identical `const val` reference (same `"navigate_to"` value); full JVM suite green. |
| **AQR-061** *(issued by the A2.1 final review)* | AQR-054 guard assertion was unreachable (`code >= 0` already excludes the reserved negative codes) | FIXED — VERIFIED | Guard restated as the real invariant (masked codes non-negative, therefore disjoint from the reserved negative namespace); suite green. |
| **AQR-062** *(issued by the A2.1 final review)* | `hostLimiter` is dead on the production path (the shared client embeds the same limiter) | FIXED — SOURCE-VERIFIED | Factory parameter KDoc marks it test-composition-fallback-only; behavior identical. |
| **AQR-063** *(issued by the A2.1 final review)* | `mapAll` URL sanitization is scheme-based (`"://" !in message`); a bare host/path message would pass | INFORMATIONAL — ACCEPTED | No verified exception shape produces such a message; broadening would change user-visible text (product decision) — recorded as no-action. |
| **AQR-064** *(issued by the closure review)* | The green device results for `DownloadStateReconcilerTest` (23/23) and `ReplaceFileOutputSafetyTest` (10/10) had no retained XML — one `connectedDebugAndroidTest` run overwrites the prior result file | FIXED — VERIFIED | **Closure round:** both classes re-executed one-per-invocation on device REDACTED-SERIAL at 17:38 (23/23 and 10/10, 0F/0E), with the PB-004 XML archived first; all three result files retained as `connected/archive/CLOSURE-2026-10-20-*.xml`. |
| **AQR-065** *(issued by the closure review)* | "8 regression tests" wording did not reconcile with the +7 test-method delta (the 8th item is a strengthened existing test) | FIXED — VERIFIED | **Closure round:** wording corrected to "7 new tests + 1 strengthened existing test (8 test items)" in the DL-005 basis, DECISIONS 161.1, and the ROADMAP closure entry; §161.6's JVM-count note corrected to the four new JVM tests. |
| **AQR-066** *(issued by the closure review)* | Method/vocabulary still described the emptied `FIXED — COMPILED, DEVICE TEST PENDING` status as if rows carried it | FIXED — VERIFIED | **Closure round:** the Method now states that no current row carries the status (PB-004 moved after its live run) and that the definition is retained for historical rows; no row edits. |
| **AQR-067** *(issued by the closure review)* | Two reconciler tests cleaned their fixture directory outside `finally`, so a failed assertion would leave app-private fixture files behind | FIXED — VERIFIED | **Closure round:** both test bodies wrapped in `try/finally` (test-only edit); the class was re-executed 23/23 green afterwards with the XML archived. |
| **AQR-068** *(issued by the closure review)* | UI-008 basis understated existing slider coverage as "thumb progress/size" only | FIXED — VERIFIED | **Closure round:** characterisation corrected to "size/target/`SetProgress` — never drag-vs-ticker" in the inventory and DECISIONS; the conclusion (no drag/ticker target exists) and the deferral are unchanged. |
| **AQR-069** *(issued by the closure review)* | `commitReplacementOutput` deletes `staged.parentFile` after a successful rename; a hypothetical `outputPath` inside `replace/` would make the target the staged file, so that delete would remove the just-committed export | DEFERRED — DOCUMENTED | Unreachable by construction: `EpisodeDownloader` writes top-level names, `computeExpectedOutput` scans top-level `.mp4` only, and `finishReplacement` records the resolved target — no current writer produces such a path (**no live data-loss path**). The defensive guard (`target.parentFile != staged.parentFile`) is a production change requiring separate authorization; deferred with this justification, no action this round. |
| **AQR-070** *(issued by the closure review)* | The new transient-failure JVM test spends ~7.5 s in real production retry backoff, dominating its class runtime | INFORMATIONAL — ACCEPTED | Correctness unaffected; exhausting the real backoff IS the behavior under test. A faster run would need a production delay seam (out of scope). Accepted as suite cost — no action. |
| **AQR-071** *(issued by the PB-004 implementation review)* | Status documents still described PB-004 as unfixed/deferred after the fix (`LIMITATIONS.md` §51, `ROADMAP.md` open bullet, this inventory's PB-004 row) | FIXED — VERIFIED | **Implementation round 2026-10-21:** this row, the ROADMAP bullets (with a dedicated round section), and LIMITATIONS §51 all reconciled to the verified fix with archive citations; citation checker re-run after the edits. |
| **AQR-072** *(issued by the PB-004 implementation review)* | `MediaSessionHost.kt` class KDoc still said the service is started "with the episodeId"/"encoded in the intent" — stale after the fix | FIXED — VERIFIED | KDoc now states the service starts for the episode recorded on the host and that the start intent carries no extras (documentation-only edit in the changed production file; behavior unchanged, PB-004 suite re-run green afterwards in `FINAL-2026-10-21-*`). |
| **AQR-073** *(issued by the PB-004 implementation review)* | `hasMediaNotification()` could be satisfied — or its post-release wait stalled — by leftover own-package notifications from another suite (non-negative ids) | FIXED — VERIFIED | `@Before` now calls `NotificationManagerCompat.from(context).cancelAll()` before any assertion, so only the media3 notification this test drives can satisfy or stall the check; class re-run 3/0/0 after the edit (`FINAL-2026-10-21-MediaSessionServiceForegroundExitTest.xml`). |
| **AQR-074** *(issued by the PB-004 implementation review)* | KDoc "Foreground promotion belongs entirely to media3" was unqualified — it is true on the session path only (external foreground starts still reach `exitWithoutSession`) | FIXED — VERIFIED | Wording qualified to "on the session path" in the same KDoc edit as AQR-072. |
| **AQR-075** *(issued by the PB-004 implementation review)* | If a background context ever issued the plain `startService()` and it were refused, the catch would leave no foreground service and no signal (silent degradation) | INFORMATIONAL — ACCEPTED | Latent edge only: both call sites are user-initiated from the foreground (`PlayerViewModel.ensurePlayer`); recorded in DECISIONS 162 so a future background call site must re-examine it. No behavior change this round. |
| **AQR-076** *(issued by the PB-004 implementation review)* | The intermediate PB-004 failure was not artifact-retained (only narrated) | INFORMATIONAL — ACCEPTED | The failure is documented as not artifact-retained; the pre-fix result XML that does exist (`CLOSURE-2026-10-20-*.xml`, 2 tests F=1) is cited as the pre-fix record, and every post-fix claim cites a retained green XML. |
| **AQR-077** *(issued by the PB-004 implementation review)* | Strengthened case 1 (external foreground start with no player) can only pass on the guard branch — a vacuous pass on a broken hook | INFORMATIONAL — ACCEPTED | Accepted: the failure modes are covered by case 2 (idle session path, held across the window) and case 3 (real promotion), which carry the contract weight; recorded as a coverage note, no test change. |
| **AQR-078** *(issued by the UI-008 implementation review; reviewer label "AQR-075" renumbered — 075..077 were already issued by the PB-004 review)* | Commit path reachable only via `onValueChangeFinished`; the accessibility `SetProgress` route and keyboard/D-pad adjustment were untested against the new contract | FIXED — VERIFIED | New device test `accessibilitySetProgressCommitsTheSeekAndReleasesTheHeldValue` (3/0/0 suite) proves the `SetProgress` route lands the same commit seek and releases the held value; hardware keyboard/D-pad adjustment is not drivable from Compose instrumentation and material3 sources are not in the local cache — recorded as an unverified edge in the test KDoc instead of a claim. |
| **AQR-079** *(issued by the UI-008 implementation review; reviewer label "AQR-076" renumbered)* | Player slider, settings sliders, and other progress nodes commit their gestures in divergent shapes — no shared slider component | DEFERRED — DOCUMENTED | Extraction is a refactor explicitly outside this round's narrow scope; recorded here and in DECISIONS 162 as a candidate follow-up (Decision 98 remains the posture; settings sliders already commit-on-finish). |
| **AQR-080** *(issued by the UI-008 implementation review; reviewer label "AQR-077" renumbered)* | "Exactly one seek" was verified only positionally (count-level proxy), not by an observable seek count | FIXED — VERIFIED | The test now attaches a real `Player.Listener` seek-discontinuity counter to the VM-owned player: 0 while the finger is down, exactly 1 after release, still 1 after several further ticks (no late duplicates) — literal count evidence alongside the positional assertions. |
| **AQR-081** *(issued by the UI-008 implementation review; reviewer label "AQR-078" renumbered)* | Test hygiene: pre-release bound was symmetric instead of directional, probe access used bare `!!`, and a failed mid-gesture assertion could leave a pointer down | FIXED — VERIFIED | Directional bound (`position < duration/4`), `assertNotNull` with messages, and `try/finally` releasing the pointer (cleanup-only `runCatching`) applied; suite re-run 3/0/0 afterwards. |
| **AQR-082** *(issued by the UI-008 implementation review; reviewer label "AQR-079" renumbered)* | Inventory/ROADMAP/DECISIONS still recorded the drag-hold fix as "unimplemented, deferred user decision" | FIXED — VERIFIED | The UI-008 row, ROADMAP bullets/round section, DECISIONS 162, and this totals block all carry the implemented-and-verified status; historical deferral texts are retained and marked as history. |
| **AQR-083** *(issued by the UI-008 implementation review; reviewer label "AQR-080" renumbered)* | After release the slider can show the pre-seek ticker value for up to one 500 ms tick before the ticker catches up with the seeked position | INFORMATIONAL — ACCEPTED | Bounded, documented in the slider slot KDoc, and an inherent property of the recorded fix shape (clear at `onValueChangeFinished`); eliminating it would require holding committed state across ticks — a contract change, not a defect. No action. |
| **AQR-084** *(issued by the Prompt 1 closure audit; adjudicated)* | Raw AES-key URL written to the debug log at three `AesKeyCache` sites (`:75,:94,:102`) instead of via `LogUrlSanitizer` (NET-009 residual; Low) | DEFERRED — DOCUMENTED | Adjudicated Low (Record + follow-up code); production code was frozen this round (blocker/test scope only); code follow-up recorded in DECISIONS 163.5. |
| **AQR-085** *(issued by the Prompt 1 closure audit; adjudicated)* | Filename formatting uses the JVM default locale at 5 production sites (`FileNameGenerator.formatEpisodePrefix`, `SegmentFileStore`, `ConcatFileGenerator`, `FfmpegEpisodeAssembler`) → `Ep_0135_…` in comma-decimal locales and non-ASCII segment names in Arabic-Indic locales, where Python produces `Ep_013.5_…` (Medium) | DEFERRED — DOCUMENTED | Adjudicated Medium (upgraded from Low after the exporters' leaf-name path was traced); absorbs DS-B-002 locale half, B-002, and the dead `StringUtils.epPrefix` sub-item (the Locale.ROOT-correct body the fix should adopt); recorded fix = `Locale.ROOT` at all five sites in one change + delete dead `epPrefix`; code follow-up — DECISIONS 163.5. |
| **AQR-086** *(issued by the Prompt 1 closure audit; adjudicated)* | Blocking `Call.execute()` in `OkHttpHlsClient.executeText/executeBytes` is not cancellation-aware: after pause/stop the host permit + worker thread stay held until the call returns (up to `callTimeout` 30 s / `readTimeout` 60 s) (Medium) | DEFERRED — DOCUMENTED | Adjudicated Medium (code path verified; runtime impact not device-measured); behavior-preserving fix recorded = `Call.cancel()` registered in `invokeOnCancellation` (or `suspendCancellableCoroutine` + `enqueue`), response closed in `finally`; code follow-up — DECISIONS 163.5. |
| **AQR-087** *(issued by the Prompt 1 closure audit; adjudicated)* | `HttpClient` interface KDoc still claims a "Phase 4 stub implementation … will be implemented with OkHttp in later phases" that does not exist (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; 4-way reviewer convergence (E-004, GLM-B-011, GLM-E-001 absorbed); doc follow-up — DECISIONS 163.5. |
| **AQR-088** *(issued by the Prompt 1 closure audit; adjudicated)* | `AesKeyCache` KDoc claims "LRU eviction when full" but the implementation evicts the oldest insertion timestamp (`get` never refreshes), and the `size >= maxSize` check-then-insert is not atomic (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; absorbs B-008/GLM-B-002; doc + tiny code follow-up — DECISIONS 163.5. |
| **AQR-089** *(issued by the Prompt 1 closure audit; adjudicated)* | 11 mojibake sites remain, falsifying AQR-052's "whole test tree re-grepped" claim (Low) | FIXED — VERIFIED | **Closure-gap round:** 11 sites fixed — `PaheBatcherApplication.kt` (5), `SettingsPersistenceTest.kt` (5), `SessionRepositoryTest.kt` (1); corrected count vs the adjudicated 9 (`SettingsPersistenceTest.kt:142` and `SessionRepositoryTest.kt:175` were missed by the audit's pattern); whole `app/src` tree re-greps clean (ripgrep no-match); compile + full JVM 114/1236 + device runs executed after the edit; AQR-052's claim superseded in place (DECISIONS 159.3 + this row); `PARITY.md:674` doc-mojibake cluster recorded as follow-up (outside the application/test-file scope); absorbs B-004. DECISIONS 163.1.a. |
| **AQR-090** *(issued by the Prompt 1 closure audit; adjudicated)* | `AnimeDetailsScreen.compactSelectedNumbers` re-implements `StringUtils.compactEpRange` for a `Set<Float>` with its own range logic (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; absorbs B-001; code follow-up — DECISIONS 163.5. |
| **AQR-091** *(issued by the Prompt 1 closure audit; adjudicated)* | "Interrupted gestures never seek" asserted in four documents but no test removed/unhooked the slider mid-drag and the code had no explicit cancel handling (Medium) | FIXED — VERIFIED | **Closure-gap round (blocker closed):** production KDoc narrowed at `PlayerScreen.kt:307-310` (line-neutral) and the NEW device test `pointerCancelMidDrag_endsAtTheSingleFinishPointAndTheSliderRecovers` pins the observed contract — a pointer-cancel commits exactly ONE seek at the held value (material3 fires `onValueChangeFinished` on cancel), the held value clears there, and the next full drag commits exactly one more (`PlayerSeekSliderDragTest` 4/4 on device REDACTED-SERIAL, archived `CLOSURE2-2026-10-21-PlayerSeekSliderDragTest.xml`); the "never seek" clause superseded in place in DECISIONS 162, the ROADMAP round section, and the UI-008 row; absorbs C-001. DECISIONS 163.1.b/163.2.8/163.4.1. |
| **AQR-092** *(issued by the Prompt 1 closure audit; adjudicated)* | Navigation-target strings are unshared magic literals (`AppNavHost.kt:109,125,134-135` vs `DownloadNotificationManager.kt:52`, `WatchlistNotificationManager.kt:59`, `MainActivity.kt:236`); `player:`/`replace:` targets have no intent-navigation test (Medium) | DEFERRED — DOCUMENTED | Adjudicated Medium; absorbs B-005, GLM-B-001; recorded as a Prompt-2 candidate (shared `NAV_TARGET_*` constants + two intent-navigation cases) — DECISIONS 163.5. |
| **AQR-093** *(issued by the Prompt 1 closure audit; adjudicated)* | `copyFile`/`deleteLocal` duplicated between `SafExporter` and `MediaStoreExporter`, and the "only local-MP4 deletion anywhere in production" KDoc is false on its own evidence (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; absorbs B-003; code/doc follow-up — DECISIONS 163.5. |
| **AQR-094** *(issued by the Prompt 1 closure audit; adjudicated)* | Two different `(n)`-suffix detection regexes describe the same uniquified-name scheme (`SafExportNames.kt:80` vs `MediaStoreExporter.kt:692`) (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; absorbs B-006; code follow-up — DECISIONS 163.5. |
| **AQR-095** *(issued by the Prompt 1 closure audit; adjudicated)* | `AUDIO_JAPANESE`/`AUDIO_ENGLISH` constants owned in two layers (`EpisodeInfo.kt:61-62` vs `AudioSelector.kt:15,18`) — a persisted vocabulary duplicated (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; absorbs B-007; code follow-up — DECISIONS 163.5. |
| **AQR-096** *(issued by the Prompt 1 closure audit; adjudicated)* | Identical percent formula duplicated with different null-vs-0 semantics at a zero denominator (`EpisodeDownloadEntity.kt:209-212` vs `SessionDisplay.kt:73-78`) (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; absorbs B-009; code follow-up — DECISIONS 163.5. |
| **AQR-097** *(issued by the Prompt 1 closure audit; adjudicated)* | 4 unused `assertNotNull` imports (only occurrence in each file is the import) (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; absorbs B-010 (GLM+DS confirmed); cleanup follow-up — DECISIONS 163.5. |
| **AQR-098** *(issued by the Prompt 1 closure audit; adjudicated)* | Stacked duplicate KDoc block and an always-true `pages[it] != null` filter on `Map<Int, List<EpisodeInfo>>` (`EpisodeRepository.kt:688-698`) (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; absorbs B-011; code follow-up — DECISIONS 163.5. |
| **AQR-099** *(issued by the Prompt 1 closure audit; adjudicated)* | `reconcile_emptyDatabase_doesNothing` contained no assertion (Low) | FIXED — VERIFIED | **Closure-gap round:** the test now asserts the empty premise (0 rows) first, then both effects — 0 rows after the pass and a WorkManager work-id pre/post diff via `WorkQuery` all-states (repo idiom, `TriggerNextDeferredEpisodeTest`) — falsifiable in both directions; device `DownloadStateReconcilerTest` 23/23 archived `CLOSURE2-2026-10-21-DownloadStateReconcilerTest.xml`; absorbs C-003. DECISIONS 163.2.1. |
| **AQR-100** *(issued by the Prompt 1 closure audit; adjudicated)* | `tempFile.delete()` sat after the assertions, not in `finally` → leaks the fixed-name fixture into `filesDir` on failure (Low) | FIXED — VERIFIED | **Closure-gap round:** fixture deleted in `finally` (assertions untouched); device 23/23 archived; absorbs C-004. DECISIONS 163.2.2. |
| **AQR-101** *(issued by the Prompt 1 closure audit; adjudicated)* | Comment said "the same call `MediaSessionHost.startService` makes in production" while calling `startForegroundService` (production issues a plain `startService`, `MediaSessionHost.kt:144`) (Low) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** the comment at `MediaSessionServiceForegroundExitTest.kt:111` now states the truth — the test drives an EXTERNAL foreground start (media-button/intent recreation) while production's own host issues a plain `startService()`; suite re-ran 3/3 on device after the edit; absorbs C-005. DECISIONS 163.2.3. |
| **AQR-102** *(issued by the Prompt 1 closure audit; adjudicated)* | Three vacuous tests (`assertNotNull(validator)` only) plus `assertTrue(!x)` style in `ValidationSpikeTest` (Low) | FIXED — VERIFIED | **Closure-gap round:** 6 → 5 tests — the three vacuous `assertNotNull(validator)`-only probes removed and two real tests added (6 − 3 + 2 = 5): a real cookie round trip (import → `getStoredCookies` key `"session (animepahe.com)"` + exact `getCookieNamesByDomain` map) and a Kwik domain-rotation fail-fast (`resolveWithDomainRotation` on a non-kwik URL → `!success` + "Not a valid Kwik URL" + "Original TLD failed, trying domain rotation" diagnostics; deterministic and offline); `assertTrue(!x)` → `assertFalse`; JVM green in the fresh 114/1236 run; absorbs C-007. Arithmetic corrected per AQR-135. DECISIONS 163.2.4. |
| **AQR-103** *(issued by the Prompt 1 closure audit; adjudicated)* | ASSEMBLING re-enqueue test asserted WorkManager work presence only (Low; the adjudicator downgraded C-002's Medium — the per-run-unique episode id prevents a stale-history false pass, but presence alone did not prove *this reconcile* enqueued) | FIXED — VERIFIED | **Closure-gap round:** the test now asserts the run-unique work name has no pre-existing history and that the enqueued work is PENDING (`!state.isFinished`, repo idiom), with the `finally` unified on `uniqueName`; device 23/23 archived; absorbs C-002 (downgraded). DECISIONS 163.2.5. |
| **AQR-104** *(issued by the Prompt 1 closure audit; adjudicated)* | `ROADMAP.md` heading still said "PARTIALLY CLOSED, VERIFIED GAPS REMAIN" although its own 2026-10-21 bullets closed both gaps (Low) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** heading corrected to CLOSED 2026-10-21; absorbs D-001. DECISIONS 163.1.c. |
| **AQR-105** *(issued by the Prompt 1 closure audit; adjudicated)* | LIMITATIONS §51 heading said RESOLVED but the body was present-tense pre-fix prose, and the `MediaSessionHost.kt:107` pointer was stale (start is `:144`) (Low) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** §51 rewritten as explicit `[pre-fix record]` past tense (what failed, why, live evidence, impact at that time) above the retained RESOLUTION bullet; pointers annotated (pre-fix `:89`/`:55`, current `:144`/`:73`); absorbs D-005. DECISIONS 163.1.d. |
| **AQR-106** *(issued by the Prompt 1 closure audit; adjudicated)* | All cited device evidence lives under `app/build/` (delete-on-clean) (Low) | INFORMATIONAL — ACCEPTED | Adjudicated record-only: the archive convention is repository-wide and creating a durable artifact store is out of this round's scope; the risk is disclosed in the ledger and §163 rather than silently accepted; absorbs D-003. DECISIONS 163.5. |
| **AQR-107** *(issued by the Prompt 1 closure audit; adjudicated)* | `ACCESS_NETWORK_STATE` is used (WorkManager constraints) but not declared by the app; it exists only via library manifest merge (Low) | DEFERRED — DOCUMENTED | Adjudicated Low; an explicit declaration is a manifest (production) edit outside this round's blocker/test scope; recorded as follow-up — DECISIONS 163.5. |
| **AQR-108** *(issued by the Prompt 1 closure audit; adjudicated)* | `gradle.properties` `android.sdk.dir=.` is an inert, machine-specific property (not a recognized key) (Low) | FIXED - VERIFIED | Closed via TEST-012, executed 2026-10-24 (privacy-release-prep Stage 2): the `android.sdk.dir` line (and `org.gradle.java.home`) were removed from `gradle.properties`; SDK resolution now comes from gitignored `local.properties`, JDK from `JAVA_HOME`. Build re-verified (`assembleDebug` + `testDebugUnitTest` 1,248/0/0). Absorbed E-002. |
| **AQR-109** *(issued by the Prompt 1 closure audit; adjudicated)* | Inventory pointer said "the UI-008 row (section 4)"; UI-008 is in section 5 (Info) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** the supersession note now points at section 5; absorbs GLM-D-001 (coordinator-verified). DECISIONS 163.1.e. |
| **AQR-110** *(issued by the Prompt 1 closure audit; adjudicated)* | "Prompt B, Phase 14, and Phase 15 were NOT started" lacked "in this round" → read as never started (Info) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** the two then-unscoped occurrences (DECISIONS §161 + §162 scope paragraphs) now read "were NOT started in this round"; §160's third instance ("Prompt B was NOT started") was scoped and the "160.x + 162" attribution corrected at the review gate (AQR-132); absorbs E-003. DECISIONS 163.1.f + 163.6.1. |
| **AQR-111** *(issued by the Prompt 1 closure audit; adjudicated)* | The proposed `FINAL-001` finding token is absent repo-wide (verdict text itself is recorded elsewhere) (Info) | INFORMATIONAL — ACCEPTED | Adjudicated record-only with the adjudicator's partial refutation of D-002 (the verdicts ARE recorded at DECISIONS §161/§162 and ROADMAP); only the token is missing and inventing one now would not add traceability; no action. |
| **AQR-112** *(issued by the closure coordinator — adjudicator correction; re-verified)* | Conditional notification assertion with `assertTrue(msg, false)` fallback in `ReplaceFileOutputSafetyTest` (Low; the adjudicator's "no repository trace" claim refuted by direct read) | FIXED — VERIFIED | **Closure-gap round:** the notification sub-check now skips via `Assume.assumeTrue` (the repo's documented convention, `BatchFailureNotificationTest`) when the platform cannot demonstrate a post — assertions run unconditionally when it can (`assertNotNull` + content-text); cleanup in `finally`; `assertTrue(msg,false)` eliminated. Device run: 10 tests = 9 pass + 1 documented assumption-skip (platform cannot post today; runner exit 0 / BUILD SUCCESSFUL; AGP renders assumptions as `<failure>` elements — tooling quirk documented in §163.4.2); absorbs C-006. DECISIONS 163.2.6/163.4.2. |
| **AQR-113** *(issued by the closure coordinator — adjudicator correction; re-verified)* | `notificationIdFor` copy-pastes `requestCodeFor`'s mask (`DownloadNotificationManager.kt:248` vs `:486`) (Low; the adjudicator had mis-absorbed it into AQR-088's absorbs column) | DEFERRED — DOCUMENTED | Coordinator re-verified both sites; deduplication is a production code change outside this round's scope; absorbs DS-B-008, GLM-B-002; code follow-up — DECISIONS 163.5. |
| **AQR-114** *(issued by the closure coordinator — adjudicator correction; re-verified)* | Inventory Method cited the DECISIONS fix ledger as "entries 158–160" while the newest rows rest on §161/§162 (Low) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** the Method now cites §161 (closure round), §162 (PB-004/UI-008 round), and §163 (this round) for the newest rows; absorbs D-004 (coordinator re-verified). DECISIONS 163.1.g. |
| **AQR-115** *(issued by the Prompt 1 closure audit — authoritative-uncovered)* | Import ordering / inline-FQN inconsistencies in 6 files: `PlayerScreen.kt:63` out-of-order import + double blank line, `MainActivity.kt:23-26` ordering + inline `PendingIntent`/`Text` FQNs, `PlayerViewModel.kt:34-41` non-alphabetical coroutine imports, `DownloadWorkerFactory.kt:47,76` + `DownloadWorker.kt:64,70` inline FQNs, `AppNavHost.kt:107` inline `LaunchedEffect`, `PaheBatcherApplication.kt:5` block ordering (Low; reviewer B's B-012, dropped by the adjudication table) | DEFERRED — DOCUMENTED | Style-only (no correctness impact; no ktlint/Detekt configured — compile is the guard); mechanical re-sort recorded as code follow-up; entered here so no authoritative finding is silently dropped. DECISIONS 163.5. |
| **AQR-116** *(issued by the Prompt 1 closure audit — authoritative-uncovered)* | New copies of the documented TEST-009 helper family: `waitUntil` in `MediaSessionServiceForegroundExitTest.kt:96-103` (a 4th deadline+`Thread.sleep(100)` polling implementation vs `Phase11UiTestSupport.awaitCondition`), and `awaitControlsReady`/`vmPlayer`/`vmTickerPositionMs` copies in `PlayerSeekSliderDragTest` (the local `vmPlayer()` returns the player from ANY back-stack entry while `playerSnapshot` refuses non-player routes — the copies are not equivalent) (Low; reviewer B's B-013 + GLM-B-012/013, dropped by the adjudication table) | DEFERRED — DOCUMENTED | Test-only consolidation recorded against the existing TEST-009 deferral (shared `awaitControlsReady`/`playerViewModel` helpers in `Phase11UiTestSupport`, delete the local copies); entered so the NEW instances (added 2026-10-21) are tracked, not skipped. DECISIONS 163.5. |
| **AQR-117** *(issued by the Prompt 1 closure audit — authoritative-uncovered)* | Retained JVM artifact is the 18:46:58Z closure run, not §161's narrated "17:13" run — totals corroborated but the timestamp attribution was implicit; UTC-vs-local timestamps in result XMLs undisclosed (Info; reviewer C's C-008 + GLM-D-004, dropped by the adjudication table) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** §161's totals line carries an artifact-supersession marker ([artifacts superseded on-disk by the 18:46:58Z closure re-run, same totals]); §163 Task D names the fresh timestamps, the class order, and the UTC-vs-local disclosure; absorbs C-008 + GLM-D-004. DECISIONS 163.4. |
| **AQR-118** *(issued by the Prompt 1 closure audit — authoritative-uncovered)* | Stale auto-hide comments: the `PlayerSeekSliderDragTest` helper KDoc and the production slider KDoc claimed controls auto-hide (no auto-hide exists) (Info; reviewer C's C-010, dropped by the adjudication table) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** the helper KDoc rewritten line-neutrally ("no auto-hide timer exists — the tap is a visibility toggle"), the production KDoc via the AQR-091 narrowing, and the class KDoc now says "All four tests"; two FURTHER same-class stale comments (`PlayerLifecycleTest.kt:460`, `TouchTargetAndFocusTest.kt:203`) found by coordinator grep are recorded here as follow-up — outside the finding's cited scope, deliberately untouched; absorbs C-010. DECISIONS 163.2.7. |
| **AQR-119** *(issued by the Prompt 1 closure audit — authoritative-uncovered)* | Three drifted AQR-010 gate pointers: `PaheMediaSessionService.kt:98` cited for "onGetSession returns null" (actual `return null` `:104`), `:167` for `onConnect` (actual `:159`), `MediaSessionControllerPolicy.kt:41` is the object line not the predicate (`:52`) (Info; reviewer D's D-006, dropped by the adjudication table) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** all three re-aimed in DECISIONS 159.2 and PARITY:1267 (annotated with what each line now holds); the finding's checker note stands — `scripts/check-doc-citations.ps1` covers existence, not right-content, so this class of drift needs review passes; absorbs D-006. DECISIONS 163.1. |
| **AQR-120** *(issued by the Prompt 1 closure audit — authoritative-uncovered)* | Fix-date attribution split: source KDoc says "PB-004 (fixed 2026-10-20)", PB004 XMLs stamp 2026-10-20T17:46Z, while the row/section say FIXED 2026-10-21 — coherent (device run before local midnight) but nothing stated it (Info; reviewer D's D-007, dropped by the adjudication table) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** §162's heading now carries the clause (device run 2026-10-20 23:16 local = 17:46Z; record dated after local midnight); absorbs D-007. DECISIONS 163.1/§162 heading. |
| **AQR-121** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | Settings-read fallback guard triplicated — `configuredQuality`/`configuredCacheTtlMinutes` at `AnimeDetailsViewModel.kt:863-883`/`:898-909` plus a `AppViewModelFactory.kt:214-222` copy — while `configuredOrDefault` exists (GLM-B-003) | DEFERRED — DOCUMENTED | Source-verified this round (all three sites read); coordinator severity Low (duplication/hygiene — no behavioral divergence found, unlike AQR-085's locale split); code follow-up — DECISIONS 163.5. |
| **AQR-122** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | `EpisodeDownloader` cookie-context block duplicated (`:417-423` vs `:811-816`) (Low; GLM-B-005) | DEFERRED — DOCUMENTED | Source-verified this round; code follow-up — DECISIONS 163.5. |
| **AQR-123** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | `EpisodeInfo.cacheDirectoryName` re-implements `StringUtils.epPrefix` + the whole-number predicate (the dead Locale.ROOT-correct body) (Low; GLM-B-006) | DEFERRED — DOCUMENTED | Source-verified this round; related to AQR-085's fix scope (adopt-and-dedupe rather than a separate fix); code follow-up — DECISIONS 163.5. |
| **AQR-124** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | Byte-humanizing rendered 3× (`StringUtils.fmtBytes`, `StorageSpace.formatBytes`, `DownloadRowMapper.formatSpeed`) with different unit ceilings (Low; GLM-B-008) | DEFERRED — DOCUMENTED | Source-verified this round; code follow-up — DECISIONS 163.5. |
| **AQR-125** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | `AppLog`/`LogBuffer` double-build at `AppLog.kt:89-103` (Info; GLM-B-015) | DEFERRED — DOCUMENTED | Source-verified this round; small code follow-up recorded. |
| **AQR-126** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | `PlayerViewModel.refreshStreamSiblings` silent catch at `:755-770` (Info; GLM-B-014) | INFORMATIONAL — ACCEPTED | Source-verified this round: the swallow is deliberate and KDoc'd (sibling refresh is best-effort; failure surfaces via the caller's path), mirroring the accepted pattern recorded with UI-007; no action. |
| **AQR-127** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | Hardcoded UI strings with no string-resource layer (Info; GLM-B-016) | INFORMATIONAL — ACCEPTED | Source-verified this round: trivially true (no localization infrastructure exists), but no localization phase is in SPEC scope at this stage; recorded by-scope, no action. |
| **AQR-128** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | PARITY:874 manifest row is Phase-10-era and does not name the Phase-14 mediaPlayback additions (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) (Info; GLM-E-003) | DEFERRED — DOCUMENTED | Source-verified this round: the row is incomplete-but-not-contradictory; extending it is new doc content beyond this round's authorized fixes (blockers/test-quality/cross-refs); doc-row extension recorded as follow-up. |
| **AQR-129** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | PB-004 compact figure ordering implicit in the records (Info; GLM-D-003) | FIXED — SOURCE-VERIFIED | **Closure-gap round:** §163 Task D.1 states the device class order explicitly by artifact timestamp (reconciler 09:10:36Z → foreground-exit 09:12:37Z → replacement 09:13:14Z → slider 09:19:50Z), so the archived XML sequence is reproducible from the record; the earlier D.4 citation (lintDebug) was corrected at the review gate (AQR-133). DECISIONS 163.4.1 + 163.6.2. |
| **AQR-130** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | Test-library versions untraceable to a decision record (Info; GLM-E-002) | INFORMATIONAL — ACCEPTED | Source-verified this round: covered by the existing TEST-006 deferral (test-dependency changes are build-config scope, excluded by standing constraint); recorded so the provenance gap has a canonical ID. |
| **AQR-131** *(issued by the Prompt 1 closure audit — GLM-supplementary, source-verified)* | §159.5's present-tense deferral lists read as current status (Info; GLM-D-005) | INFORMATIONAL — ACCEPTED | Source-verified this round: §159.5 is a dated section documenting decisions as taken then; GLM-D itself checked-and-passed it; no action. |
| **AQR-132** *(issued by the independent review gate — Prompt 1 closure round)* | AQR-110 recorded FIXED although §160's scope sentence ("Prompt B was NOT started") was still unscoped; the §163.1.6 / ROADMAP / row attribution said "160.x + 162" when the scoped pair was §161 + §162; the new closing note repeated the unscoped pattern (Medium) | FIXED — SOURCE-VERIFIED | **Review-gate round:** §160 scoped ("... in this round"), the three attributions corrected (DECISIONS 163.1.6 bracket, ROADMAP review-gate bullet, this row), and the closing reconciliation paragraph scoped; citation checker re-run PASS. DECISIONS 163.6.1. |
| **AQR-133** *(issued by the independent review gate — Prompt 1 closure round)* | AQR-129's row cited "§163 Task D.4" (= `lintDebug`) for the device class order the row claimed was explicit (Low) | FIXED — SOURCE-VERIFIED | **Review-gate round:** §163 Task D.1 now states the order explicitly by artifact timestamp (09:10:36Z / 09:12:37Z / 09:13:14Z / 09:19:50Z) and the AQR-129 row re-points to D.1 (+ D.2 for the replacement class). DECISIONS 163.6.2. |
| **AQR-134** *(issued by the independent review gate — Prompt 1 closure round)* | ROADMAP said the never-issued register was "extended" while this file and DECISIONS §163 say it stays at 14 (Low) | FIXED — SOURCE-VERIFIED | **Review-gate round:** ROADMAP now reads "re-checked, unchanged at 14"; §9 carries the no-new-gaps note. DECISIONS 163.6.3. |
| **AQR-135** *(issued by the independent review gate — Prompt 1 closure round)* | The 6 → 5 `ValidationSpikeTest` arithmetic in DECISIONS §163.2.4 / ROADMAP / the AQR-102 row said "two vacuous probes replaced", which cannot yield 5 (Info) | FIXED — SOURCE-VERIFIED | **Review-gate round:** all three records corrected to the accurate account — three `assertNotNull(validator)`-only probes removed, two real tests added (6 − 3 + 2 = 5), matching the finding's own "three vacuous tests" text and the surviving 5-test file (JVM artifact `tests="5"`). DECISIONS 163.6.4. |
| **AQR-136** *(issued by the independent review gate — Prompt 1 closure round)* | The slider class's "run twice" claim had only one surviving artifact (the second run overwrote the first in the live output directory) (Info) | FIXED — SOURCE-VERIFIED | **Review-gate round:** §163 Task D.1 and ROADMAP now state that only the post-narrowing run's XML was archived; going-forward rule recorded (archive each required run under a distinct name). AQR-106's record-only class. DECISIONS 163.6.5. |
| **AQR-137** *(issued by the independent review gate — re-verification pass)* | DECISIONS §163 Task C and ROADMAP's Task C bullet still printed the pre-gate "200 rows = 77 area + 117 AQR + 6 FR" as an unqualified "ledger totals" figure inside records that now report 206/123 (Low) | FIXED — SOURCE-VERIFIED | **Review-gate re-verification:** both sites now read "totals at Task C: 200 rows … (+6 review-gate rows AQR-132..137 → final 206/123)"; grep confirms no other live `200 rows` / `117 AQR` site remains; citation checker re-run PASS. DECISIONS 163.6.6. |

## 8. A2 final-review dispositions — FR (6)

| ID | Finding (short) | Status | Basis |
|---|---|---|---|
| FR-01 | `awaitUniqueWorkInfos` future listener has no `invokeOnCancellation` removal | DEFERRED — DOCUMENTED | Byte-for-byte extraction of two pre-existing copies; no A2 regression; optional maintenance with a focused test run first (DECISIONS 159-era record). |
| FR-02 | "+4 suites / +19 tests" delta bookkeeping | FIXED — SOURCE-VERIFIED | Totals verified exactly; the 19th test positively identified (DATA-003 LRU test); bookkeeping closed. |
| FR-03 | Media3 javap evidence directory outside the reviewer's sandbox | INFORMATIONAL — ACCEPTED | Trust boundary disclosed; docs claim nothing beyond static evidence. |
| FR-04 | APK mtimes/sizes not exposed to reviewer tools | INFORMATIONAL — ACCEPTED | Existence verified; shell evidence recorded at the time. |
| FR-05 | `busySessionId` stays set after a mid-action CancellationException | DEFERRED — USER DECISION REQUIRED | Only production CE source today is teardown (harmless); a semantics change needs product confirmation first. |
| FR-06 | No VCS history in this workspace | INFORMATIONAL — ACCEPTED | Per-site weakening-risk checks performed instead; none weakened. |

## 9. Never-issued AQR ID register (14)

These IDs were never issued to any finding in any round. Recorded so the ID space is
auditable and the gaps cannot later be mistaken for lost findings:

| Range | IDs | Note |
|---|---|---|
| AQR-026..029 | 4 IDs | Skipped between the original review's last Medium block and the next issued ID. |
| AQR-034..039 | 6 IDs | Skipped between the playback block and the UI/settings block. |
| AQR-047..049 | 3 IDs | Skipped between the wording block and the schema-asset finding. |
| AQR-053 | 1 ID | Skipped between the last original finding (AQR-052) and A2.1's request-code finding, which landed as AQR-054 (the code comment at `DownloadNotificationManager` carries that label; AQR-053 remains a gap). |

AQR-025 is NOT in this register — it was issued by A2.1 (bridge diagnostics) and is listed in
section 7.

Closure-round check 2026-10-21: the closure audit's findings were issued as a contiguous block
AQR-084..131 (48 IDs, all present in section 7) and the independent review gate's six findings
as AQR-132..137 (all present in section 7), so the register remains at **14** — no new gaps
were created and none of the registered gaps were consumed.

## 10. Totals reconciliation

| Class | IDs | Count |
|---|---|---|
| Area findings | NET 15 + PB 10 + DATA 15 + DL 11 + UI 12 + TEST 14 | 77 |
| AQR findings issued | 38 original + 2 by A2.1 (025, 054) + 9 by the A2.1 final review (055..063) + 7 by the closure review (064..070) + 7 by the PB-004 implementation review (071..077) + 6 by the UI-008 implementation review (078..083) + 48 by the Prompt 1 closure audit (084..131) + 6 by its independent review gate (132..137) | 123 |
| FR dispositions | FR-01..06 | 6 |
| **Total inventory rows** | | **206** |
| Never-issued AQR IDs (register, section 9) | | 14 |

By status:

| Status | Count |
|---|---|
| FIXED — VERIFIED | 114 (107 + AQR-089, 091, 099, 100, 102, 103, 112) |
| FIXED — SOURCE-VERIFIED | 27 (PB-005, PB-007, PB-009, AQR-033, FR-02, DATA-012, DL-007, AQR-057, 058, 062 + AQR-101, 104, 105, 109, 110, 114, 117, 118, 119, 120, 129 + AQR-132, 133, 134, 135, 136, 137) |
| BY DESIGN — NOT A DEFECT | 2 (DL-011, TEST-013) |
| DUPLICATE — RECONCILED | 1 (AQR-024) |
| INFORMATIONAL — ACCEPTED | 18 (UI-012, AQR-011, 012, FR-03, FR-04, FR-06, AQR-063, 070, 075, 076, 077, 083 + AQR-106, 111, 126, 127, 130, 131) |
| DEFERRED — USER DECISION REQUIRED | 7 (NET-002, NET-006, NET-012, TEST-006, AQR-045, AQR-046, FR-05) |
| DEFERRED — DOCUMENTED | 34 (PB-008, DATA-011, TEST-003, 005, 007, 009, AQR-016, 030, FR-01, AQR-069, 079 + AQR-084..088, 090, 092..098, 107, 113, 115, 116, 121..125, 128) |
| FOLLOW-UP TASK — RECORDED | 3 (TEST-012, TEST-014, AQR-108) |
| **Sum** | **206** ✓ (matches row count) |

## 11. Follow-up task texts (recorded, not executed)

- **TEST-012 (Decision 10).** De-localize `gradle.properties`: remove the machine-specific
  JDK/SDK path pins (duplicating `local.properties`), keep `jvmToolchain(25)`, and verify with
  a clean `assembleDebug` + `testDebugUnitTest` on a second machine/CI before relying on the
  change. Build-config scope — excluded from A2.1 by standing constraint.
- **TEST-014 (Decision 11).** Introduce a real R8 release configuration: enable release
  minification, validate the shrinking-sensitive surfaces (Room, media3, serialization), and
  run the gated suite against the release build. Release scope — a separate task from A2.1.
- **UI-008 (deferred).** Instrumentation-only seek-slider work: hold a local drag value that
  suppresses the ticker-derived value until `onValueChangeFinished`. Requires a device test
  round.
  *Superseded 2026-10-21: executed and device-verified in the authorized PB-004/UI-008
  implementation round — see the UI-008 row (section 5), the AQR-078..083 dispositions
  (section 7), and the reconciliation note below.*

---

*Inventory frozen at the end of Phase A2.1. Prompt B has NOT been started; no Phase B scope,
design, or tasking is implied by any entry in this file.*

*Closure-round reconciliation 2026-10-20 (post keepTemp tests, PB-004 live run, UI-008 re-check):
frozen at Task E with 132 rows (77 area + 49 AQR + 6 FR; status sum 92 + 10 + 2 + 1 + 7 + 9 + 9 +
2 = 132; PB-004 moved from "FIXED — COMPILED, DEVICE TEST PENDING" — a now-empty status — to
"DEFERRED — USER DECISION REQUIRED" after its live device run failed one of two cases; the PB-004
live failure is recorded, not resolved). The independent closure review then issued AQR-064..070;
its five fixable findings were corrected (evidence re-run + XMLs archived, wording reconciled,
test-fixture `finally` hygiene) and its two informational items recorded, giving the final ledger:
**139 rows (77 area + 56 AQR + 6 FR), status sum 97 + 10 + 2 + 1 + 8 + 9 + 10 + 2 = 139, no
duplicate IDs.** This file is the **frozen baseline for subsequent work**; freezing authorizes no
further implementation.*

*PB-004/UI-008 implementation-round reconciliation 2026-10-21 (both tasks authorized, implemented,
device-verified on REDACTED-SERIAL, and independently reviewed): PB-004 and UI-008 moved from
"DEFERRED — USER DECISION REQUIRED" to "FIXED — VERIFIED" on retained evidence — PB-004
(`archive/PB004-2026-10-20-*.xml` 3/1/1/8/2 tests 0F/0E + `archive/FINAL-2026-10-21-*` 14 classes
55 tests 0F/0E; pre-fix failure retained as `CLOSURE-2026-10-20-*.xml`), UI-008
(`archive/UI008-2026-10-21-PlayerSeekSliderDragTest.xml` 3/0/0 + `UI008-2026-10-20-*` regressions
5/8/1 all 0F/0E); full JVM 114 suites / 1237 tests / 0F / 0E / 0 skipped; lint 0 errors, 39
warnings, 1 hint; debug APK 118,307,654 bytes deployed (lastUpdateTime 2026-10-21 00:18:21).
The PB-004 review issued AQR-071..077 (verdict READY AFTER FIXES — all fixable findings applied);
the UI-008 review issued six findings whose reviewer-assigned IDs (075..080) collided with the
PB-004 batch and were renumbered **AQR-078..083** on recording (verdict READY — four fixes
applied, one deferred with rationale, two informational). Ledger at that point: **152 rows
(77 area + 69 AQR + 6 FR), status sum 107 + 10 + 2 + 1 + 12 + 7 + 11 + 2 = 152.**
Prompt B has NOT been started.*

*Closure-gap remediation reconciliation 2026-10-21 (Prompt 1: seven closure blockers —
AQR-089/091/104/105/109/110 plus the inventory ingestion — six test-quality findings —
AQR-099/100/101/102/103/112 — and full ingestion of the closure audit): all 48 audit findings
AQR-084..131 are now recorded in section 7 (31 canonical adjudicated rows + 6
authoritative-but-uncovered rows + 11 GLM-supplementary rows), every blocker and test-quality
finding carries its fix status with evidence (DECISIONS 163.1..163.4; fresh device artifacts
`app/build/outputs/androidTest-results/connected/archive/CLOSURE2-2026-10-21-<Class>.xml`;
JVM `--rerun-tasks` 114 suites / 1236 tests / 0F/0E/0Sk; lint 0E/39W/1H; APK 118,307,654 bytes,
installed on REDACTED-SERIAL with `lastUpdateTime 2026-10-21 14:50:01`), and the totals are recomputed:
**206 rows (77 area + 123 AQR + 6 FR), status sum 114 + 27 + 2 + 1 + 18 + 7 + 34 + 3 = 206, no
duplicate IDs; the never-issued register stays at 14** (AQR-084..137 is a contiguous issued
block - no new gaps). The round's independent review gate (DECISIONS 163.6) then issued six
documentation-integrity findings AQR-132..137, each fixed in place and recorded in section 7;
its non-blocking observations are recorded in DECISIONS 163.6, not acted on. Deferred/record-only
rows (AQR-084..088, 090, 092..098, 106..108, 111, 113, 115, 116, 121..128, 130, 131) are recorded
decisions, not hidden work; Prompt-2 candidates are listed in DECISIONS 163.5. Prompt 2 /
Phase 14 / Phase 15 were NOT started at any point through this closure round - the round
changed no production behavior (comment/KDoc corrections only) and no
build/dependency/toolchain configuration.*
