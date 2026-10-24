# PHASE14_PLAN.md — Phase 14 Architecture (implementation-ready)

**Status: PLAN ONLY. Phase 14 status in `ROADMAP.md` remains `PENDING`. No production code, tests, Gradle, or dependency changes have been made for Phase 14. This file does not mark Phase 14 started or complete.**

*[2026-10-18 addendum] `ROADMAP.md` records Phase 14 as COMPLETE (2026-10-03) and the project has since progressed through Phases 15–17 and the Prompt 1–3 release-gate cycles; the `PENDING` wording in the status line above is the historical plan-time statement, preserved as written.]*

Date: 2026-10-02. Basis: first-hand inspection of the current repository (source, tests, docs) plus a full read-only inventory pass. Every architectural decision below is resolved from repository/specification evidence; unresolved items are marked `BLOCKING DECISION` (there are none — see §22).

---

## 1. Phase 14 scope

Authoritative sources: `ROADMAP.md:792-827` (Phase 14 deliverables + exit criteria), `DECISIONS.md` 95/96/91, `PARITY.md:603/604`, `LIMITATIONS.md:2033-2034` (§34.5), `SPEC.md` §18/§19/§23.

Required deliverables (the eight unchecked ROADMAP items):

| # | Deliverable | Doc basis |
|---|---|---|
| R1 | Completion notifications — re-create `buildCompletionNotification` + app-owned `ic_stat_download_done` vector, wire to the Worker success path | ROADMAP:799, DECISIONS 95.B |
| R2 | Failure notifications — re-create `buildFailureNotification` + `ic_stat_error` vector, wire to terminal FAILED paths | ROADMAP:800, DECISIONS 95.B |
| R3 | Watchlist new-episode notifications (aggregated per check run) | ROADMAP:801, DECISIONS 95.C, SPEC §19 |
| R4 | Notification polish — per-type channels, group keys, tap actions | ROADMAP:802, DECISIONS 95.D |
| R5 | MediaSession — `media3-session` under media3 1.11.1: media notification / quick-settings / lockscreen controls | ROADMAP:803, DECISIONS 95.E |
| R6 | Auto-resume downloads setting — `NetworkType.CONNECTED` + bounded `Result.retry()`/backoff on transport failure + connectivity-severing instrumentation test + DataStore row | ROADMAP:804, DECISIONS 96, PARITY:604 |
| R7 | Per-type notification preference rows (completion/failure/watchlist) | ROADMAP:805, DECISIONS 96 (:4363) |
| R8 | Watchlist check-frequency setting (SPEC §19) | ROADMAP:806, DECISIONS 91 |

Already shipped — **do not redo**: progress notification channel + `buildProgressNotification` (ROADMAP:797-798), `POST_NOTIFICATIONS` prompt + permission-status row (ROADMAP:807, moved to Phase 13).

## 2. Explicit non-goals

- No Phase 15 ("Full Test Suite") or Phase 16 ("Parity Audit") work of any kind.
- No action buttons on notifications (see §7 Decision 6 — SPEC §18 defines the interaction as tap-to-open).
- No "Download paused", "Session requires user action", or "Cloudflare/session expired" notifications (§2.1 below; no such states exist — fabricating them would violate AGENTS §5).
- No auto-download toggle: `WatchlistChecker.autoDownload` runs unconditionally today (`WatchlistChecker.kt:278`); adding a setting is not a Phase 14 deliverable (SPEC §23 does not list one).
- No background/lockscreen playback beyond what in-UI playback already does (PlayerScreen pauses on background — unchanged).
- No reopening of completed Phase 13 decisions (DECISIONS 92-112) and no changes to Phase 10 concurrency semantics except the §13 deferred-trigger candidate-skip amendment to PHASE10 V10 §6 (documented and annotated; no Phase 10 guarantee is weakened).
- No localization, settings wizard, `cli_set`, `cookie_string`, authentication screen (DECISIONS 96/99/102).
- No sub-15-minute watchlist checking (WorkManager minimum, SPEC §19 "respect minimum").
- No closure of the LIMITATIONS §24 R5 reboot manual gate (§21).
- No notification deep-links beyond downloads / watchlist / player destinations (§7).

### 2.1 SPEC §18 example coverage (explicit scope ruling)

| SPEC §18 example | Ruling |
|---|---|
| Download started / Episode downloading / Download progress | Already shipped (Phase 10/11 `buildProgressNotification`) — not Phase 14 work |
| Download completed | Phase 14 (R1) |
| Download failed | Phase 14 (R2) |
| New watchlist episode detected | Phase 14 (R3) |
| Download paused | **OUT OF SCOPE** — no pause state exists; Pause is intentionally unsupported (DECISIONS 94); a "paused" notification would fake state (AGENTS §5) |
| Session requires user action | **OUT OF SCOPE** — the Android session model has no user-action-required state (DECISIONS 109 derives display states only); failures surface as the in-app Retry affordance |
| Cloudflare/session expired | **OUT OF SCOPE** — session handling is automatic behind the WebView abstraction (DECISIONS 102); no expired-session user-facing state exists to reflect |

## 3. Current-state summary (verified first-hand)

- **Notifications:** one channel `download_progress` `IMPORTANCE_LOW` (`DownloadNotificationManager.kt:29-52`), progress notification with `navigate_to=downloads` deep link (`:68-84`), `notificationIdFor = episodeId.hashCode() and 0x7FFFFFFF` (`:125-127`), posted as the Worker's FGS notification (`DownloadWorker.kt:304-313`); terminal paths cancel it (`:337,:462,:516,:526,:548,:559,:232`) and the two cancellation paths cancel it at `:207`/`:497`. Completion/failure builders were deleted in 11.5(c). `createNotificationChannel()` is called at worker start (`:303`).
- **Worker results:** every failure returns `Result.failure()` — zero `Result.retry()` in DownloadWorker (production's only `Result.retry()` is `WatchlistCheckWorker.kt:94`); no `setBackoffCriteria` exists anywhere in `src/main`.
- **Error classification:** typed at the transport layer (`HlsTextResult/HlsBytesResult` = Success/HttpError(code)/NetworkError; `OkHttpHlsClient.kt:45-107`), typed again in `EpisodeDownloader` (`FetchResult`/`FetchBytesResult` = Success/PermanentError(403,404)/TransientError, `EpisodeDownloader.kt:107-124`, produced at `:584-687`), then **collapsed to `DownloadResult.error: String`** (`:83-100`) and re-matched with `error.contains("HTTP 403")`-style strings in the episode-retry loop (`:194-199`).
- **In-run retries:** HTTP-level 5 attempts × episode-level `autoRetry+1` (`auto_retry` default 2, range 0..2 — `AppContext.kt:84-85`), permanent errors stop the in-run loop (`:195-199`).
- **Enqueue sites (exactly three):** `DownloadRepository.kt:734-744`, `DownloadWorker.kt:616-627` (deferred trigger), `DownloadStateReconciler.kt:396-405` — all `enqueueUniqueWork("episode_download_$id", ExistingWorkPolicy.KEEP)` + `wifiOnlyConstraints(...)`; plus the single periodic `WatchlistCheckScheduler` (`KEEP`, 15 min, `CONNECTED`).
- **Reconciler:** DOWNLOADING branch checks WorkManager for `RUNNING || ENQUEUED` (`DownloadStateReconciler.kt:266-281`) — a work request in backoff reports `ENQUEUED`, i.e. "alive". QUEUED+m3u8 rows are re-enqueued with KEEP (`:290-295`). FAILED/CANCELLED are terminal, untouched (`:243-245`).
- **Worker state machine:** entry is CAS-claimed from QUEUED/DOWNLOADING/ASSEMBLING (`DownloadWorker.kt:271-286`); `countActive()` = DOWNLOADING|ASSEMBLING only (`EpisodeDownloadDao.kt:113-114`); deferral returns `Result.success()` with the row still QUEUED (`:157-164`); every completion path triggers the next deferred episode under `NonCancellable` (`:118-139`).
- **Watchlist:** `WatchlistCheckWorker` → `WatchlistChecker.checkAll()`; auto-download runs unconditionally per entry (`WatchlistChecker.kt:278,:334-385`, capacity-gated via `ResolveAheadGate`); foreground `checkNow()` exists (`WatchlistViewModel.kt:194`, `WatchlistScreen.kt:91-95`); result log already carries `newEpisodes` and `queued` counts (`WatchlistCheckWorker.kt:102-112`).
- **Settings:** `AppConfig` key/Flow/setter/`resetToDefaults()` pattern (`AppConfig.kt:40-78,:85-89,:170-177,:350`); `SettingsUiState` combine + setters (`SettingsViewModel.kt:55-145,:265-266`); row primitives `SwitchRow`/`RadioOption`/`SectionHeader` (`SettingsScreen.kt:363-424`); `SettingsContentTest` asserts the exact shipped row set.
- **Navigation:** typed routes `DownloadsRoute`, `WatchlistRoute`, `SessionsRoute`, `PlayerRoute(episodeId)`, `AnimeDetailsRoute` (`AppRoutes.kt`); the notification deep-link contract is `EXTRA_NAVIGATE_TO = "navigate_to"` consumed at `AppNavHost.kt:95-107` — only `"downloads"` is handled today.
- **Player:** exactly one ExoPlayer per `PlayerViewModel` (created in `ensurePlayer()` `:335`, released in `onCleared()` `:428-445`), handed to the media3 compose `Player` surface (`PlayerScreen.kt:195`); gauges `livePlayerCount`/`releasedPlayerCount` (`:456-463`); `PlayerLifecycleTest` 8 test methods; no `MediaSession` exists in `src/main`; toml has `media3 = "1.11.1"` with only `media3-exoplayer` + `media3-ui-compose-material3`.
- **Toolchain:** Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20, WorkManager 2.11.2, compileSdk/targetSdk 37, minSdk 32, JVM toolchain 25. Manifest has `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `SystemForegroundService` with `dataSync` type; no mediaPlayback entries.
- **PHASE10 V10 §14** records Phase 10's "NO Result.retry()" decision; **§13** records that process death does not need `Result.retry()`; **§10** defines the existing DownloadWorker error-classification mapping.

## 4. Final architecture (overview)

```
Foreground UI / WatchlistChecker (DECISIONS 91 amendment)
        ↓ resolve streams (WebView/Kwik) — NEVER in DownloadWorker
persist StreamInfo + QUEUED row (Room)
        ↓ DownloadRepository.enqueueWorker / deferred trigger / reconciler
WorkManager  (unique "episode_download_$id", KEEP, policy per §9/§13)
        ↓ constraints per §10 matrix; backoff per §15
DownloadWorker (maxParallel gate → CAS claim → FGS progress notification)
        ↓ EpisodeDownloader (in-run retries, typed error classes per §14)
assembly → export trigger → terminal state (CAS)
        ↓
terminal notification (completion | failure) per §7/§8 — or quiet Result.retry() park per §15
```

Ownership is single-layered (§5). The Phase 14 changes are additive: one new typed field set, one new provider pair at the three existing build sites, three new builders + four channels, three preference keys, one interval key + scheduler policy change, one new service + holder, one new Settings row group.

## 5. Component ownership

| Concern | Owner (unchanged unless noted) |
|---|---|
| Foreground/WebView/Kwik resolution | `AnimeDetailsViewModel`/`DownloadRepository` retry re-resolution (11.5E-7); `WatchlistChecker.autoDownload` for watchlist entries (DECISIONS 91 amendment). **DownloadWorker never touches WebView (KDoc invariant, `DownloadWorker.kt:41`).** |
| Persisted stream info | `DownloadRepository.resolveComplete` → Room (`saveStreamInfo`) |
| Scheduling of episode work | `DownloadRepository.enqueueWorker`, `DownloadWorker.triggerNextDeferredEpisode`, `DownloadStateReconciler.reEnqueueWorker` — the three build sites, all via the shared policy helper (§9) |
| Scheduling of watchlist checks | `WatchlistCheckScheduler`, registered/updated from `PaheBatcherApplication.onCreate` (interval Flow collection added in §12) |
| Settings (single source of truth) | `AppConfig` (DataStore); consumers read fresh at use time (DECISIONS 105.4 pattern) |
| Retry policy — in-run | `EpisodeDownloader` (HTTP 5, episode `autoRetry+1`, permanent-error stop) — unchanged |
| Retry policy — cross-run | `DownloadWorker` only (bounded `Result.retry()`, §15) |
| State machine / reconciliation | Room CAS methods + `DownloadStateReconciler` — unchanged semantics |
| Notification construction | `DownloadNotificationManager` (completion/failure/progress builders), new `WatchlistNotificationManager` (watchlist), shared `NotificationChannels` object (channel IDs/names/importance/ensure) |
| Notification posting | `DownloadWorker` (episode lifecycle), `WatchlistCheckWorker` (watchlist detection) |
| MediaSession | `MediaSessionHost` (new, app-scoped: holder + service start/stop) + `PaheMediaSessionService` (new: session creation + media3 notification) wrapping the **existing VM-owned ExoPlayer** |
| Permission prompt/status | Phase 13 components — unchanged |

New abstractions are limited to three small, justified ones: `NotificationChannels` (4 channel definitions, 2 consumers), `MediaSessionHost` (player/session/service triangle), and the `DownloadErrorClass` enum (§14).

## 6. State/flow diagrams

**Terminal success (Phase 14 adds the completion notification):**
```
CAS DOWNLOADING→COMPLETED ok?
  no  → cancel progress; Result.failure()            (no notification — a newer state won)
  yes → export (best-effort, never changes Result)
      → cancel(progressId)
      → notify(completionId = notificationIdFor or SIGN_BIT, channel download_complete)
      → Result.success()
```

**Transport failure with auto-resume ON (new path):**
```
downloader returns success=false, errorClass=TRANSPORT
  AND autoResume setting ON
  AND runAttemptCount < AUTO_RESUME_MAX_RETRIES (7 — runAttemptCount is 0 on the first run):
      → CAS DOWNLOADING→QUEUED   (row honestly "queued", frees its maxParallel slot)
      → cancel(progressId)        (no failure notification — not terminal)
      → Result.retry()            (WorkManager backoff; CONNECTED/UNMETERED gate re-checked)
later: constraint met → run starts again → claimEntryState(QUEUED→DOWNLOADING) → segments resume from doneIndices (zero re-download, ResumeTest invariant)
```

**Terminal failure (unchanged except the added notification):**
```
non-transport failure OR auto-resume OFF OR runAttemptCount ≥ AUTO_RESUME_MAX_RETRIES (7):
      → updateErrorIfState(DOWNLOADING) FAILED (CAS)
        posted ONLY if the CAS actually flipped the row
      → cancel(progressId)
      → notify(failureId = notificationIdFor or SIGN_BIT, channel download_failure)
      → Result.failure()
```

**Watchlist check (new notification at the end):**
```
WatchlistCheckWorker.doWork → checkAll() → success
  newEpisodes > 0 AND watchlist preference ON AND POST_NOTIFICATIONS granted?
      → ONE notify(watchlistId = -9001, channel watchlist_new_episodes)   (replaces previous)
  foreground checkNow() → no notification (user is looking at the result)
```

## 7. Decision 6 — Notification actions (resolved)

**Decision: tap-to-open content intents only; no action buttons.**

- SPEC §18 defines the interaction: *"Allow the user to tap a notification and return to the relevant download/session/player screen."* SPEC never mentions buttons; ROADMAP:825 "Actions work from notifications" and DECISIONS 95.D "actions" are satisfied by per-type destination intents. Action buttons (Retry, etc.) are therefore **not Phase 14 scope** — adding them would require receivers feeding the re-resolution pipeline outside SPEC's stated interaction.
- Destinations (verified routes only):
  - completion / failure / progress → `"downloads"` → `DownloadsRoute` (already implemented, `AppNavHost.kt:97-106`);
  - watchlist → `"watchlist"` → `WatchlistRoute` (new branch in the `pendingNavTarget` handler, same `EXTRA_NAVIGATE_TO` contract);
  - media → `MediaSession.setSessionActivity` → `MainActivity` with `navigate_to="player"` + episode id → `PlayerRoute(episodeId)` (new branch; falls back to the default home destination if the episode id is unavailable at PendingIntent build time — deterministic fallback, no crash).
- **Payload encoding & request codes (reviewer L6):** `MainActivity`'s navigation contract is a single string extra (`EXTRA_NAVIGATE_TO`, `MainActivity.kt:144`), so the player target is encoded inside the value itself as `"player:<episodeId>"` and parsed with take-after-first-colon — the id format `<session>_<epStr>_<audio>` (`DownloadRepository.kt:153-154`) has no colon separator, and the parse round-trips even if an id contained one. PendingIntent request codes: completion/failure **deliberately reuse** the progress code `episodeId.hashCode()` with the identical `"downloads"` extras (same code + same extras → `FLAG_UPDATE_CURRENT` cannot cross-contaminate, and the destination is identical anyway); watchlist uses `-9001` (its notification id); player uses `-9002`. No two *different* destinations share a request code; any theoretical hash collision sits in KNOWN LIMITATION P14-L1 (§21).
- **Conscious scope reading (reviewer L8):** ROADMAP:802 "notification actions" and DECISIONS 95.D "per-type channels, grouping, actions" read naturally as action *buttons*, while SPEC §18 `:758` defines the required interaction as tap-to-open. SPEC governs behavior; buttons would add a receiver pipeline SPEC never requests. This narrowing is explicit here and recorded as a DECISIONS entry at implementation (§19) — a decided interpretation, not a silently cut item.
- `setAutoCancel(true)` for completion/failure/watchlist (tap dismisses); progress keeps `setAutoCancel(false)` + `setOngoing(true)` (unchanged).
- The notification-interaction test belongs to **Phase 14's exit gate** (SPEC `:1098` lists it as an Android test; AGENTS §7/§8 couple tests to the feature they verify; Phase 15 is suite *breadth*, not this feature's proof). Evidence: `NotificationInteractionTest` (§17).

## 8. Decision 4 & 5 — Notification channels and IDs (resolved)

**Channels (final — designed now because importance is sticky after creation):**

| Channel ID | Name | Importance | Contents | Existing installs |
|---|---|---|---|---|
| `download_progress` | Download progress | `LOW` (silent, unchanged) | progress + queued FGS notification | untouched; user customizations preserved (Android ignores re-set importance on existing channels) |
| `download_complete` | Download completed | `DEFAULT` | completion notification | created on first `NotificationChannels.ensure()` call (unconditional, idempotent) |
| `download_failure` | Download failed | `DEFAULT` | failure notification | same |
| `watchlist_new_episodes` | New watchlist episodes | `DEFAULT` | watchlist detection | same |
| (media3-owned) | created by `DefaultMediaNotificationProvider` | media3-internal | media notification | not ours — media3 creates/owns it (AGENTS §17 verification at implementation) |

- `DEFAULT` = banner + sound, not peek-high: these are discrete informative events; progress stays silent `LOW` so an active download never alerts. All four app channels are created unconditionally in one idempotent `NotificationChannels.ensure()` invoked where `createNotificationChannel()` is called today (`DownloadWorker.kt:303`) and from `WatchlistCheckWorker` before posting — never gated on the preference flags (a channel must exist for its row to have meaning in system settings).
- `NotificationPermissionTest`'s "exactly ONE channel exists … until Phase 14" assertion flips to the new expected set in the same slice (test update listed in §17).
- **Preference mapping (R7):** three DataStore booleans — `notify_completion` (default **ON**), `notify_failure` (default **ON**), `notify_watchlist` (default **ON**) — gate *posting* of their type. The `download_progress` FGS notification has no preference row (it is the foreground-service requirement of running work, not a message choice). Rows: three `SwitchRow`s in the Notifications section, each with a production consumer at its post site (AGENTS §5).

**Notification IDs (final):**

| Notification | ID rule | Rationale |
|---|---|---|
| progress (per episode) | `notificationIdFor(episodeId)` = `hashCode() and 0x7FFFFFFF` — **unchanged** | existing contract; `NotificationTest` asserts stable positive IDs |
| completion / failure (per episode) | `terminalIdFor(episodeId) = notificationIdFor(episodeId) or 0x80000000` (always negative) | terminal replaces progress *of the same episode* via `cancel(progressId)` immediately followed by `notify(terminalId)` — sequential in one coroutine, so progress + terminal are never both visible; a distinct (negative) ID makes the terminal notification immune to any WorkManager foreground-notification teardown behavior (it can only remove the ID it posted) while remaining collision-free against every progress ID (all progress IDs are positive) |
| watchlist | fixed constant `-9001` | one live watchlist notification; each run replaces the previous → no stacking, no spam |
| media | media3-owned constant (untouched) | see KNOWN LIMITATION P14-L1 for the theoretical positive-range collision |

Duplicate prevention rule: every terminal path already calls `cancelNotification(progressId)`; Phase 14 appends the terminal `notify` **after** that cancel and **only when the terminal CAS write actually landed** (no notification when a newer state won). Repeated failures re-post the same terminal ID → replacement, never stacking.

**Grouping (DECISIONS 95.D):** every episode notification carries `setGroup("pahebatcher.downloads")`; the watchlist notification carries `setGroup("pahebatcher.watchlist")`. No hand-rolled summary notifications: concurrent episode notifications are bounded by `maxParallel` (default ≤ small) and per-type grouping + per-type channels already give the system the grouping key it stacks by; a custom summary would add racing state (posted/cancelled from multiple workers) for no user-visible gain. The exit criterion "grouped notifications work" is evidenced by group-key assertions + device observation (§17).

**Content rule:** failure/completion text = notification title + episode title only. Never the raw error string — error messages may embed stream URLs (AGENTS §10: never expose session material); the error detail stays in the in-app Downloads screen (persisted `errorMessage`, unchanged).

## 9. Decision 3 — Network constraint rule (resolved)

**One canonical helper replaces the ad-hoc call — same function at all three build sites:**

```kotlin
// WifiOnlyConstraint.kt (extended file, single implementation)
fun downloadNetworkConstraints(wifiOnly: Boolean, autoResume: Boolean): Constraints? =
    when {
        wifiOnly   -> Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        autoResume -> Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        else       -> null
    }

fun WorkRequest.Builder.applyDownloadWorkPolicy(wifiOnly: Boolean, autoResume: Boolean) {
    downloadNetworkConstraints(wifiOnly, autoResume)?.let { setConstraints(it) }
    setBackoffCriteria(BackoffPolicy.EXPONENTIAL, AUTO_RESUME_BACKOFF_SECONDS, TimeUnit.SECONDS) // §15
}
```

**Matrix (explicit):**

| Wi-Fi-only | Auto-resume | Attached constraint | Behavior |
|---|---|---|---|
| OFF | OFF | none | byte-for-byte today's default behavior |
| OFF | ON | `CONNECTED` | starts/retries only with any network; retry fires as soon as connectivity returns |
| ON | OFF | `UNMETERED` | exactly Phase 13's shipped behavior |
| ON | ON | `UNMETERED` | Wi-Fi constraint dominates — `UNMETERED` implies connectivity, so it subsumes `CONNECTED`; auto-resume retries are additionally gated to non-metered networks. One constraint object, no composition ambiguity |

**Site wiring (all three sites, one implementation):**

- `DownloadRepository.kt:736`, `DownloadWorker.kt:619` (deferred trigger), `DownloadStateReconciler.kt:398` each change from `.apply { wifiOnlyConstraints(wifiOnlyProvider())?.let { setConstraints(it) } }` to `.apply { applyDownloadWorkPolicy(wifiOnly, autoResume) }`, with **both** values read fresh at that moment (`wifiOnlyProvider` + new `autoResumeProvider`, both `suspend () -> Boolean` reading `appConfig.*.first()`), extending the read-fresh contract documented at `WifiOnlyConstraint.kt:15-30` (DECISIONS 105.4).
- `autoResumeProvider` is added with default `{ false }` to the six existing provider slots (`DownloadWorker.kt:76`, `DownloadRepository.kt:68`, `DownloadStateReconciler.kt:93`, `DownloadWorkerFactory.kt:51`, `PaheBatcherApplication.kt:64`, `AppContainer.kt:253` wiring) — every existing test factory and every current call site keeps compiling and keeps today's behavior until production wiring passes the real provider.
- Already-enqueued requests keep the constraints they were built with and are never rewritten when a toggle flips (contract at `WifiOnlyConstraint.kt:22-25`) — the matrix is an **enqueue-time** rule by design; the retry decision (§10) is a **run-time** rule. The documented asymmetry: a request enqueued while auto-resume was OFF carries no backoff/constraint change, and WorkManager's built-in default backoff (exponential, 30 s) is value-identical to ours anyway, so a late-enabled setting still retries with the same pacing, only without the connectivity gate until the next enqueue.
- `wifiOnlyConstraints(wifiOnly)` as a standalone entry point is removed in the same slice; `WifiOnlyConstraintTest` moves to the combined function and asserts the full 4-case matrix (§17).

## 10. Decision 1 — Auto-resume architecture (failure semantics)

**Setting:** `auto_resume` (DataStore boolean), **default OFF** (§18). Rationale: DECISIONS 96 records that today every failure ends in terminal `Result.failure()`/`FAILED`; the repository default policy for new engine behaviors (DECISIONS 105.5 precedent) is that upgrading must not change existing users' behavior — OFF keeps failure semantics byte-for-byte today's until the user opts in. SPEC §23 requires the setting to exist and actually affect the engine (`SPEC.md:946,:957`); it does not mandate a default.

**Eligibility — one pure decision, testable without Android:**

```kotlin
// DownloadWorker (companion or top-level, JVM-testable)
fun shouldAutoResume(errorClass: DownloadErrorClass, enabled: Boolean, runAttemptCount: Int): Boolean =
    // runAttemptCount is 0 on the first run; AUTO_RESUME_MAX_RETRIES = 7 ⇒ ≤8 total runs
    enabled && errorClass == DownloadErrorClass.TRANSPORT && runAttemptCount < AUTO_RESUME_MAX_RETRIES
```

**Exactly which failures may return `Result.retry()`** — and nothing else may:

| Failure origin | Eligible? |
|---|---|
| Downloader result with `errorClass == TRANSPORT` (non-403/404 HTTP status exhausted + network failures, §14) — surfaced via the `!result.success` branch (`DownloadWorker.kt:519-528`) | **YES**, subject to setting + bound |
| No stream info (`:330-339`) | no — terminal (missing persisted data) |
| `assemblyError != null` (`:509-518`) | no — terminal (deterministic FFmpeg failure) |
| Download exception escaping the downloader (`:453-464`) | no — terminal (unexpected worker-level errors stay terminal, today's behavior) |
| Completion CAS lost (`:543-549`) | no — terminal (a newer state won; retrying would fight it) |
| `wasCancelled` (`:476-508`) | no — cancellation, never a Result decision |
| Unexpected outer exception (`:213-238`) | no — terminal |

**Retry-park sequence (replaces the terminal path only when eligible):**

1. `dao.updateStateForResume(episodeId, expectedState = DOWNLOADING, newState = QUEUED)` — CAS; if it loses (user cancelled concurrently), do NOT retry; fall through to normal handling.
2. `notificationManager.cancelNotification(notificationId)` — progress notification removed; **no failure notification** (not terminal).
3. `return Result.retry()` — WorkManager moves the request to ENQUEUED-with-backoff (§15).
4. `triggerNextDeferredEpisode` still runs from the `finally` block (`:118-139`) — the parked row freed its active slot (`countActive` counts only DOWNLOADING|ASSEMBLING). Because the parked row is typically the **oldest** `QUEUED+m3u8` row and its unique work is **unfinished** (in backoff), today's single-candidate selection (`getNextQueuedWithM3u8`, oldest-first, `EpisodeDownloadDao.kt:211`) would KEEP no-op on it every round and leave later deferred rows without requests — a liveness regression the reviewer raised as **H1** (without parking, "queued row with live work" was rare; parking makes it routine). Phase 14 therefore **amends the trigger**: iterate queued+m3u8 candidates in the same `createdAt` order and enqueue the first candidate whose unique work does not exist or is not unfinished (WorkInfo `ENQUEUED`/`RUNNING`/`BLOCKED` = unfinished; `SUCCEEDED`/`FAILED`/`CANCELLED` terminal states are selectable — today's deferred-row case), with the unchanged `ExistingWorkPolicy.KEEP`; if every candidate has live work, do nothing (today's no-op). With no parked/live-work rows ahead of it, selection is identical to today's oldest-row behavior; the amendment additionally repairs the pre-existing Phase 13 corner where an `UNMETERED`-blocked oldest row caused the same delaying no-op. Later deferred rows therefore keep their immediate liveness guarantee (test in §17); the amendment is documented as a Phase 14 change to PHASE10 V10 §6 (§13, §19).

**On the next eligible run:** `claimEntryState` accepts QUEUED (`:271-286`), the maxParallel gate re-applies, and segments resume from `doneIndices` — zero re-download (ResumeTest invariant preserved; each WorkManager attempt runs the full in-run budget).

**No new error classification by string matching anywhere in this path** — the decision reads the typed field only (§14).

## 11. Decision 8 — MediaSession architecture

**Dependency:** `androidx-media3-session = 1.11.1`, resolved under the single existing `media3 = "1.11.1"` version key (only `media3-exoplayer` and `media3-ui-compose-material3` exist today). AGENTS §17 verification performed at implementation time against Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20, JDK 25, compileSdk/targetSdk 37, minSdk 32: 1.11.1 is the current stable of the line already pinned in this project, no component downgrade is required, no alpha/beta is selected. **Pass rule:** the artifact resolves and compiles under this toolchain; if verification fails, it is reported as a BLOCKER per AGENTS §17 — no substitute mechanism is invented.

**Ownership — one ExoPlayer, preserved release-once:**

```
PlayerViewModel (UNCHANGED ownership)
  ensurePlayer() :335  → creates the single ExoPlayer
  onCleared()   :428-445 → mediaSessionHost.onPlayerReleased() FIRST, then existing exo.release()
        │
        ▼
MediaSessionHost (NEW, app-scoped, small — injected via AppViewModelFactory)
  @Volatile player / episodeId / session
  onPlayerCreated(player, episodeId) → start PaheMediaSessionService
  onPlayerReleased() → session.release(); stopService()
        │
        ▼
PaheMediaSessionService (NEW, thin MediaSessionService host)
  onStartCommand/onCreate → ensureSession():
      host.player present  → MediaSession.Builder(this, player).setSessionActivity(...).build()
      host.player absent   → stopSelf()   (media-button/intent recreation after release exits immediately)
  onGetSession(controllerInfo) = host.session
  media3 DefaultMediaNotificationProvider → posts/updates the media notification + its own channel
```

- The session **wraps the existing VM-owned ExoPlayer instance**; media3 sessions do not own or create the player — no second player exists anywhere (invariant asserted by `livePlayerCount` gauges, `PlayerViewModel.kt:456-463`).
- The service is **started explicitly** by `MediaSessionHost` when a player is created (deterministic lifecycle independent of media3 controller binding — we use no `MediaController`), and **stopped explicitly** on release. Media3 owns only the notification content/posting while the service runs; transport controls route through the framework MediaSession under media3 to the same player.
- `PlayerLifecycleTest`'s 8 test methods remain the regression bar untouched: rotation preserves player+session (VM and host both survive config change), popping the player entry clears the VM → release order (session, then player) removes the media notification and stops the service, no stack deepening.
- **Session activity intent:** single-string encoded value `navigate_to="player:<episodeId>"` (§7 payload rules — `MainActivity`'s contract carries one String extra, `MainActivity.kt:144`; parse with take-after-first-colon, which round-trips the `<session>_<epStr>_<audio>` id format of `DownloadRepository.kt:153-154`) → `PlayerRoute(episodeId)` via a new `pendingNavTarget` branch; request code = dedicated constant `-9002` (§7). Empty/unparseable id falls back to `MainActivity` default routing (home) — a defined fallback, never a crash.
- **Manifest (implementation-time):** add `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`; declare the service with `android:foregroundServiceType="mediaPlayback"`, `android:exported="true"` and intent-filter `androidx.media3.session.MediaSessionService` (media3's documented service snippet). Existing `SystemForegroundService`/dataSync entries are untouched.
- **Coexistence:** media notification (media3-owned ID/channel) and download progress notification are different types and may be visible simultaneously while playing and downloading; ID-collision exposure is accepted as KNOWN LIMITATION P14-L1 (§21).
- **Lifecycle verification:** `MediaSessionLifecycleTest` (§17) asserts on-device: media notification present in `NotificationManager.activeNotifications` during playback, absent after `onCleared`, service stopped, `livePlayerCount == 1` throughout. Backgrounding continues to pause playback exactly as today (`PlayerScreen.kt:97`) — MediaSession adds no new playback behavior.

## 12. Decisions 7 & 9 — Watchlist scheduler & notification architecture

### 12.1 Check-frequency setting (Decision 9)

- **Persisted setting:** `KEY_WATCHLIST_INTERVAL_MINUTES = intPreferencesKey("watchlist_check_interval_minutes")`, **default 15** (= today's `WatchlistCheckScheduler.INTERVAL_MINUTES`; upgrading changes nothing).
- **Allowed values (final):** `15, 30, 60, 240, 720` minutes, presented as `15 minutes (default) / 30 minutes / 1 hour / 4 hours / 12 hours` (`RadioOption` group). Setter validates membership: a non-member value writes the default 15 (deterministic; the UI only offers members).
- **WorkManager minimum:** `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` = 15 minutes; every allowed value ≥ 15, satisfying SPEC §19 "Respect Android's minimum periodic-work constraints" (`SPEC.md:780`).
- **Scheduler:** `WatchlistCheckScheduler.schedule(workManager, intervalMinutes: Long = INTERVAL_MINUTES)` — the default parameter keeps every existing call site compiling unchanged (`WatchlistCheckWorkerTest.kt:212-213,:230`, `TriggerNextDeferredEpisodeTest.kt:135`, reviewer M-1) — builds `PeriodicWorkRequestBuilder<WatchlistCheckWorker>(intervalMinutes, MINUTES)` with the unchanged `CONNECTED` constraint and unique name `watchlist_periodic_check`, enqueued with `ExistingPeriodicWorkPolicy.UPDATE` (supported by WorkManager 2.11.2): a changed interval is applied to the already-registered periodic request instead of being ignored (`KEEP` would silently drop it) or cancelled-and-restarted (`CANCEL_AND_REENQUEUE` would reset the cadence anchor and can fire an immediate extra check); with no prior request, UPDATE enqueues normally. Uniqueness guarantees exactly one periodic request exists at all times; acceptance is asserted by test (single request, new interval present), never by wall-clock claims.
- **Registration:** `PaheBatcherApplication.onCreate` — the one-shot `WatchlistCheckScheduler.schedule(workManager)` call becomes `applicationScope.launch { appConfig.watchlistCheckIntervalMinutes.distinctUntilChanged().collect { minutes -> WatchlistCheckScheduler.schedule(workManager, minutes) } }` (same collector pattern as the existing `showConsole` collection, `PaheBatcherApplication.kt:79-83`). `distinctUntilChanged()` is required: the AppConfig flow maps the whole `dataStore.data` stream (`AppConfig.kt:170`), which re-emits on *any* settings write — without it, unrelated toggles would re-issue periodic UPDATEs (reviewer L4). First emission registers/reconciles with the stored value; later emissions apply interval changes only. `WatchlistCheckScheduler.describe(workManager, minutes: Long = INTERVAL_MINUTES)` reports the current interval for diagnostics (the default likewise preserves existing callers).
- **No exact execution-time claims** — periodic work remains system-batched/Doze-deferred (LIMITATIONS `:1711` sentence retained; observed ~18-minute spacing for 15-minute requests remains the honest documentation).
- Interval changes affect only the watchlist work; episode download work is a separate unique name and is untouched.

### 12.2 Watchlist notification vs auto-download (Decision 7)

Verified current behavior: `WatchlistChecker.autoDownload` runs **unconditionally** per entry (`WatchlistChecker.kt:278,:334-385`, capacity-gated), and no auto-download setting exists — so Phase 14 adds no toggle (§2 non-goal).

| Aspect | Final rule |
|---|---|
| Trigger | `WatchlistCheckWorker.doWork` **after `checkAll()` returns success**, only when `results.sumOf { it.newEpisodes } > 0` — the detection count, deliberately NOT the status-based summary: `WatchlistChecker.kt:284-291` maps `capacityTimedOut` to `FAILED` even when `pending` is non-empty, while `newEpisodes = pending.size` (`:280`) still reports the real detection (reviewer L5) |
| Aggregation | **ONE notification per check run** — title `New episodes detected`, text `N new episode(s) in M series` (M = entries with `newEpisodes > 0`) |
| Stacking | fixed ID `-9001` → each run replaces the previous; never stacks, never per-entry notifications |
| Manual `checkNow()` (foreground) | **NO notification** — the user is looking at the result; SPEC §18 "Do not create notification spam" |
| With auto-download active | The watchlist notification reports **detection** (SPEC §19); download progress/completion notifications report **transfer** on their own channels. Both may appear; ≤1 watchlist notification per run regardless of `queued` count |
| Check failure (throw) | no notification (existing failure path unchanged) |
| Errors | notification posting is best-effort: preference flag checked first, whole block in `try/catch`, logged — a posting problem never fails the worker result |
| Destination | contentIntent → `navigate_to="watchlist"` → `WatchlistRoute` (§7) |

## 13. WorkManager behavior

- **Episode work:** unique name `episode_download_<id>`, `ExistingWorkPolicy.KEEP`, one request per episode — **unchanged at all three sites**; the only change is `applyDownloadWorkPolicy` (§9) replacing the constraint-only call, plus the added `autoResumeProvider`.
- **Deferred-trigger amendment (reviewer H1; §10/§16):** `triggerNextDeferredEpisode` selects the first queued+m3u8 candidate **without live unique work** (iterate a new **query-only** list DAO method — no schema change — returning queued+m3u8 rows in `getNextQueuedWithM3u8`'s `createdAt` order; the existing single-row query at `EpisodeDownloadDao.kt:211` stays untouched; skip candidates whose WorkInfo is `ENQUEUED`/`RUNNING`/`BLOCKED`, examining at most `TRIGGER_CANDIDATE_CAP = 50` candidates per trigger so the per-trigger WorkInfo lookups are bounded), then enqueues with the unchanged `KEEP` policy; if none qualifies, no-op (today's behavior). This amends PHASE10 V10 §6 trigger semantics — annotated, not rewritten (§19) — because auto-resume (and Phase 13's `UNMETERED` corner) routinely produce queued rows with unfinished requests. It adds no enqueue path and breaks no Phase 10 guarantee (§16).
- **Worker results:** `DownloadWorker` gains exactly one conditional `Result.retry()` path (§10/§15); all other results unchanged. This is a **deliberate, gated supersession of PHASE10_ARCHITECTURE_V10 §14 ("NO Result.retry()")**: it applies only to transport-recoverable failures, only when the user opted in (default OFF), and only within a hard attempt bound. Recorded as a DECISIONS entry at implementation time; PHASE10 V10 §14 receives an annotation, not a rewrite (historical-record protocol).
- **Stale comment fixes (same slice):** `WatchlistCheckWorker.kt:62-66` states production contains no other `Result.retry()` — updated to reflect the single bounded download-retry site; and `WatchlistCheckScheduler.kt:23-31` KDoc (its `KEEP` policy rows and the "repository has no watchlist-check interval setting" claim) is rewritten to the UPDATE-policy/interval-setting reality (reviewer L-3).
- **Watchlist periodic work:** name `watchlist_periodic_check`, policy `KEEP` → `UPDATE` (§12.1), `CONNECTED` unchanged, interval from the setting.
- **No new workers, no new unique names, no WorkManager version/dependency change** (2.11.2 stays).
- **Backoff:** attached via `setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, SECONDS)` inside `applyDownloadWorkPolicy` at all three sites — inert unless `Result.retry()` is returned (§15).

## 14. Error classification (Decision 1 taxonomy)

**New typed seam — the minimal architecture required.** The classification already exists in two layers (`HlsTextResult/HlsBytesResult` with HTTP codes; `FetchResult/FetchBytesResult` Permanent/Transient) but collapses into `DownloadResult.error: String` and is re-matched by `error.contains("HTTP 403")` strings (`EpisodeDownloader.kt:194-199`). The seam: one enum field on the existing result, set at every construction site by **construction-site origin** (not message text), consumed by both the in-run loop and the worker.

```kotlin
enum class DownloadErrorClass {
    NONE,              // success
    TRANSPORT,         // network-origin: any non-403/404 HTTP status exhausted after the 5 in-run HTTP attempts (400/401/429/5xx) plus timeout/DNS/connection failures — in-run retried; AUTO-RESUME ELIGIBLE
    EXPIRED,           // HTTP 403/404 (stream/key/segment gone) — in-run PERMANENT
    NO_CONTENT,        // "No segments in playlist" / "Master playlist has no variants" — in-run PERMANENT
    ASSEMBLY,          // assemblyError != null — in-run PERMANENT
    IN_RUN_RETRYABLE,  // parse failure, decryption/missing-key, local segment-write errors, non-IO exceptions — in-run retried (today's behavior), never auto-resume
}
```

**Construction-site mapping (behavior-preserving — every row was verified against today's `isPermanent` string set):**

| Site (EpisodeDownloader) | Today | New class | In-run after | Auto-resume |
|---|---|---|---|---|
| m3u8/variant/key `PermanentError` (403/404) | permanent (substring) | `EXPIRED` | permanent | no |
| m3u8/variant/key `TransientError` after 5 HTTP attempts | retried | `TRANSPORT` | retried | **yes** |
| `No segments in playlist` / `Master playlist has no variants` | permanent | `NO_CONTENT` | permanent | no |
| AES key fetch permanent/`TransientError` | permanent/retried | `EXPIRED`/`TRANSPORT` | same | yes iff transport |
| Parse failure (`Failed to parse M3U8`) | retried | `IN_RUN_RETRYABLE` | retried | no |
| Segment aggregation (after sibling drain) | depends on substring | priority rule below | identical to today | only if pure-transport |
| `assemblyError != null` | permanent | `ASSEMBLY` | permanent | no |
| `download()` catch exhaustion (`:221-234`) | retried until attempts end | `IN_RUN_RETRYABLE` — `OkHttpHlsClient` converts every network exception to `NetworkError` (`OkHttpHlsClient.kt:45-107`), so network failures never reach this catch; what reaches it is local/unexpected (segment-store cleanup/index scans, file ops) — disk errors such as ENOSPC are not connectivity-fixable and must not be auto-resume eligible (reviewer M2) | same | no |
| Loop safety-net fallback (`:237-241`) | retried | `IN_RUN_RETRYABLE` (conservative unknown) | retried | no |

**Segment aggregation priority (deterministic):** (1) any failed segment with permanent HTTP error → `EXPIRED` — matches today's behavior, because an expired-segment message contains "HTTP 403/404" which today's substring test catches in the joined string; (2) else every failure was `TransientError` → `TRANSPORT`; (3) else (any decryption/key/write/local failure) → `IN_RUN_RETRYABLE` — matches today, where these messages match none of the four permanent strings and the episode loop retries them. Auto-resume requires **pure-transport** aggregation: a mixed run contains a content/local problem that connectivity cannot fix, so it must not loop back.

**Consumers:**
1. In-run episode loop (`:194-199`): `val isPermanent = errorClass == EXPIRED || errorClass == NO_CONTENT || errorClass == ASSEMBLY` — identical outcomes at every site, string matching deleted. Side benefit: eliminates the latent hazard where an arbitrary exception message could coincidentally contain "HTTP 403".
2. DownloadWorker `!result.success` branch: `shouldAutoResume(result.errorClass, autoResume, runAttemptCount)` (§10). Worker-originating failures never travel through this enum (§10 table).

`DownloadResult.error: String` remains unchanged (persisted `errorMessage` and UI content untouched). Cancellation keeps its separate typed path (`wasCancelled`).

## 15. Decision 2 — Retry/backoff semantics

| Parameter | Final value |
|---|---|
| Default auto-resume | **OFF** — terminal failure is today's behavior (§10) |
| Retry API | `Result.retry()` from `DownloadWorker` only, transport-only, setting-gated |
| Backoff policy | `BackoffPolicy.EXPONENTIAL`, base **30 seconds**, attached at all three enqueue sites |
| Gaps before runs 2…8 | 30 s, 1 m, 2 m, 4 m, 8 m, 16 m, 32 m (cumulative ≈ 63 min to the 8th run; WorkManager's 5-hour exponential cap is not reached) |
| Retry bound | `AUTO_RESUME_MAX_RETRIES = 7` — WorkManager `runAttemptCount` is **0 on the first run**, so retry is returned while `runAttemptCount < 7` (runs 1–7), i.e. ≤8 total runs; the 8th run (count 7) whose failure is still TRANSPORT takes the terminal path instead (reviewer M1) |
| After exhaustion | existing terminal path exactly: `updateErrorIfState` → `FAILED` with the original `result.error` message (message text unchanged; attempt count logged to AppLog only), progress cancelled, failure notification posted (§8), `Result.failure()` |
| FAILED reachability | unchanged for every non-transport failure (immediate) and for auto-resume OFF; reached after the bound for exhausted transport failures |

**Interaction with downloader counters:** untouched — per WorkManager attempt the downloader still runs `auto_retry+1` episode attempts × 5 HTTP attempts (auto_retry default 2, range 0..2). Auto-resume wraps strictly **outside** that budget; completed segments persist via `doneIndices`, so retries never re-download (zero-re-download invariant).

**Constraint-gated attempt economy:** after backoff expires, WorkManager re-evaluates constraints before running. An offline device holds the request ENQUEUED **without consuming attempts** (attempts increment only when a run executes). Therefore the 8-run budget is spent only by runs that actually executed and failed again — i.e., repeated online transport failures, not hours offline. This is what makes a small bound sufficient for the "resume when connectivity returns" promise.

**Process death and WorkManager recreation:** the request and its `runAttemptCount` are WorkManager-persisted. The increment belongs to the re-run, not the `Result.retry()` return: WorkManager increments `runAttemptCount` when the request next transitions ENQUEUED → RUNNING (verified from the WorkManager 2.11.2 bytecode, reviewer round-3 NEW-2), so `WorkInfo` during run N reads N while the `WorkerParameters` value the worker itself reads during run N is N−1 (captured before the increment — the zero-based view the bound is evaluated on, `DownloadWorker.kt:101`); either way a request whose runs keep failing transport always terminates at the bound regardless of process-death interleavings; the system-*rescheduled* (death-interrupted) increment is **asserted, not assumed**, via the extended 11.5E-6 operator protocol (reviewer round-2 finding H-1, executed 2026-10-04 on device REDACTED-SERIAL): `RealProcessDeathSeedTest` records the pre-death baseline (`runAttemptCount == 1`, PID, request id) while the worker runs, the operator force-stops the package from outside the instrumentation (a real mid-run death — the in-process staging limit of LIMITATIONS §20 applies only to the suite), `am start` relaunches, and `RealProcessDeathCleanupTest -e verifyAttemptCount true` asserts a different PID, the SAME request re-run with an advanced `runAttemptCount` still inside the bound — `OK (1 test)`, TestRunner/logcat 01:27:33–01:28:03, request `1465b048-d249-4716-b447-1eef55f21cfd` (evidence and deviation notes in LIMITATIONS §34.11). The bound itself does not depend on it. Process death itself still needs no retry (PHASE10 V10 §13 unchanged): interrupted work is re-enqueued by WorkManager regardless of this feature.

**Reconciler-created fresh requests:** a reconciler re-enqueue builds a new request with a reset attempt counter, but it fires only when the previous request no longer exists (stranded row) — transport failures never produce that condition (retry/backoff requests exist and report ENQUEUED). The budget therefore resets only after genuine work-vanish/startup conditions; each reset is bounded by the same 8-run rule (documented in §21).

**Cancellation during backoff:** the parked row is an ordinary QUEUED row — the existing cancel path writes `CANCELLED` and cancels unique work (killing the backoff request); reconciler skips CANCELLED, trigger never selects it. No special-casing.

## 16. Phase 10 concurrency/reconciliation compatibility (mandatory check)

Verified against `PHASE10_ARCHITECTURE_V10.md` (§4 maxParallel, §5 KEEP, §6 deferred liveness, §8/§11 reconciliation, §13 process death, §14 retry) and the current code:

1. **maxParallel** — the gate lives inside the worker claim (`DownloadWorker.kt:157-168`) and is untouched. A retry-parked row is QUEUED → not counted by `countActive()` (DOWNLOADING|ASSEMBLING only) → identical semantics to today's deferral, which also returns success leaving a QUEUED row. Soft-limit/best-effort character per Decision 14 preserved.
2. **WorkManager uniqueness** — no new enqueue path is introduced; auto-resume reuses the existing request via `Result.retry()`. Trigger/reconciler enqueues while a backoff request exists are KEEP no-ops (unfinished work blocks; PHASE10 §5). The deferred trigger's candidate-skip amendment (§10/§13) guarantees it never selects a row whose work is unfinished: a parked row is skipped, later rows are enqueued, and the parked row's backoff request is never duplicated or preempted.
3. **Reconciliation** — backoff requests report `ENQUEUED`, which the DOWNLOADING-branch liveness check (`DownloadStateReconciler.kt:269`) treats as alive → no interference. Parked QUEUED+m3u8 rows are re-enqueued via KEEP → state unchanged, request unchanged. FAILED/CANCELLED remain terminal, untouched.
4. **Startup re-enqueue / deferred progression / process death** — all unchanged; transport retry is failure-driven and orthogonal to death-driven recovery.
5. **Cancellation** — unchanged paths; parked rows behave as ordinary queued rows.
6. **Failure/deferred starvation** — the `finally` trigger (`:118-139`) runs on the retry-park return path too, and with the candidate-skip amendment (§10/§13) a parked row can never block later deferred rows from being enqueued: liveness for every row without live work stays immediate, subject only to the unchanged `maxParallel` gate (`DownloadWorker.kt:157-164`), which defers by design when full. (Unamended, a parked oldest row would KEEP no-op every round and delay later rows until backoff exhaustion — reviewer H1; the amendment is documented as a Phase 14 change to PHASE10 V10 §6 (§13, §19) and covered by a dedicated §17 test.)

**Conclusion: no Phase 10 incompatibility exists; Phase 10 is not reopened.** If implementation discovers a concrete incompatibility, it is reported as a blocker — not worked around (AGENTS §16).

## 17. Test architecture (Phase 14 verification — defined now, executed during implementation only)

Baselines to keep green: JVM unit (692 tests), device (383 tests), lint (0 errors), all Phase 10 suites listed below. New counts reported honestly at implementation; nothing pre-claimed here.

| Required behavior | Test (new or extended) | Kind |
|---|---|---|
| Completion notification | `NotificationTest` extended: channel `download_complete`, group key, autoCancel, app-owned icon, contentIntent → `navigate_to="downloads"` | device |
| Failure notification | `NotificationTest` extended: channel `download_failure`, posted **only** when the FAILED CAS landed; CAS-lost → no post | device |
| Permission denied | `NotificationPermissionTest` extended: post paths no-crash/no-op under denial; channel-set assertion updated to the four app channels | device |
| Grouping | `NotificationTest`: `setGroup("pahebatcher.downloads")` / `.watchlist` asserted on built notifications | device |
| Tap/action behavior | **NEW** `NotificationInteractionTest`: fire failure contentIntent → Downloads destination; watchlist contentIntent → `WatchlistRoute` (SPEC `:1098`) | device |
| Preference persistence | `SettingsPersistenceTest` extended: `notify_completion`/`notify_failure`/`notify_watchlist`/`watchlist_check_interval_minutes` round-trip + `resetToDefaults()` | device |
| Watchlist aggregation | `WatchlistCheckWorkerTest` extended: N>0 → exactly one notification (id -9001); N=0 → none; pref OFF → none; foreground `checkNow()` → none (VM-level) | device |
| Auto-resume connectivity interruption | **NEW** `AutoResumeConnectivityTest`: sever connectivity mid-download, assert the row parks QUEUED (not FAILED) and work stays ENQUEUED; restore connectivity, assert automatic completion with **zero re-download** of completed segments (PARITY `:604` criterion; §34.10 harness preconditions apply) | device |
| Retry bound | JVM: exhaustive `shouldAutoResume` tests (`runAttemptCount` 6 → retry, 7 → terminal, plus OFF/non-TRANSPORT cases); device: first-retry parks QUEUED using an unreachable-host fixture | JVM + device |
| Process-death attempt-count semantics (reviewer L-1) | in-suite: `ProcessDeathRecoveryTest` asserts request persistence, retry-park increment and recreation-reset after its documented simulated-death kill step (LIMITATIONS §20); **real death-interrupted increment**: extended 11.5E-6 operator protocol (`RealProcessDeathSeedTest` baseline record → operator force-stop mid-run → relaunch → `RealProcessDeathCleanupTest -e verifyAttemptCount true` asserts different PID, same request, advanced count inside the bound) — executed 2026-10-04, `OK (1 test)` (reviewer round-2 H-1) | device |
| Cancellation interaction | `CancellationTest` extended: cancel while parked → CANCELLED, unique work cancelled, no reconciler/trigger resurrection | device |
| Deferred liveness under parking (reviewer H1) | extends `TriggerNextDeferredEpisodeTest`/`ConcurrencyTest`: row A (oldest) parked in backoff, row B queued later → trigger enqueues B while A stays parked and its request is untouched | device |
| Network constraint combinations | `WifiOnlyConstraintTest` (androidTest) extended: full 4-case matrix of `downloadNetworkConstraints(wifiOnly, autoResume)` | device |
| Watchlist interval persistence/rescheduling | `WatchlistCheckSchedulerTest` extended: interval parameter, UPDATE policy, `CONNECTED` retained, single unique request; `WatchlistCheckWorkerTest`'s periodic-scheduling method rewritten from KEEP to UPDATE semantics (reviewer M-1); device: schedule(60) → exactly one periodic request carrying 60 | JVM + device |
| MediaSession lifecycle | **NEW** `MediaSessionLifecycleTest`: playback → media notification in `activeNotifications`, service running, same ExoPlayer (`livePlayerCount == 1`); release → notification gone, service stopped | device |
| PlayerLifecycleTest regression | all 8 existing test methods run **unchanged** | device |
| Phase 10 regression | `UniqueWorkTest`, `DuplicateEnqueueTest`, `ReEnqueueIdentityGuardTest`, `DownloadStateReconcilerTest`, `ProcessDeathRecoveryTest`, `RealProcessDeathSeedTest`, `ConcurrencyTest`, `ResumeTest`, `TriggerNextDeferredEpisodeTest`, `CancellationTest` — existing **methods** unchanged and green (the H1 liveness method is *new*, added in the row above; `TriggerNextDeferredEpisodeTest`'s `schedule()` teardown keeps compiling via the default interval parameter, reviewer L-8; review round-2 L-4 acknowledges the additive `@After` auto-resume-restoration lines in `CancellationTest`/`ProcessDeathRecoveryTest` — hygiene that prevents setting leakage across tests, no pre-existing assertion removed or weakened) | device |
| Error classification | **NEW** JVM `DownloadErrorClassMappingTest`: every construction site → expected class (incl. aggregation priority rules; edge cases: HTTP 401 → TRANSPORT, local file-op exception in `download()` catch → IN_RUN_RETRYABLE) | JVM |
| Settings row set | `SettingsContentTest` updated to the exact new row set (§18) | device |

**Existing tests updated (only where the contract genuinely extends):** `WifiOnlyConstraintTest` (combined helper + matrix), `SettingsContentTest` (new rows), `NotificationPermissionTest` (channel set), `EpisodeDownloaderTest` (fake results carry `errorClass`), `WatchlistCheckSchedulerTest` (UPDATE + interval parameter), `WatchlistCheckWorkerTest` (KEEP → UPDATE scheduling semantics only). All lifecycle behavior uses real lifecycle/instrumentation tests — no mock-only lifecycle tests. **These tests are NOT executed in this architecture task.**

## 18. Settings architecture

| Row | Section | Control | DataStore key | Default | Production consumer |
|---|---|---|---|---|---|
| Auto-resume downloads | Download engine (directly after the Wi-Fi-only row, `SettingsScreen.kt:386-399`) | Switch + description ("Retries failed downloads automatically when connectivity returns; bounded attempts.") | `auto_resume` (boolean) | OFF | §10 eligibility + §9 constraint at all three sites |
| Completion notifications | Notifications (`:417`, after the permission row) | Switch | `notify_completion` (boolean) | ON | completion post site |
| Failure notifications | Notifications | Switch | `notify_failure` (boolean) | ON | failure post site |
| New watchlist episodes | Notifications | Switch | `notify_watchlist` (boolean) | ON | `WatchlistCheckWorker` post site |
| Watchlist check frequency | **new** `SectionHeader("Background checks")` between Notifications and Diagnostics, `RadioOption` group | radios 15 / 30 / 60 / 240 / 720 minutes | `watchlist_check_interval_minutes` (int) | 15 | Application collector → scheduler UPDATE (§12.1) |

Every row follows the existing patterns exactly: `AppConfig` key + Flow + validated setter + entry in `resetToDefaults()` (`AppConfig.kt:40-78,:350`), `SettingsUiState` combine + VM setter (`SettingsViewModel.kt:55-145`), `SwitchRow`/`RadioOption`/`SectionHeader` composables (`SettingsScreen.kt:363-424`). Provider wiring for the worker-side reads follows the existing `wifiOnlyProvider` chain (`AppContainer.kt:253`, `PaheBatcherApplication.kt:64`, `DownloadWorkerFactory.kt:51`), with `DownloadNotifyPrefs(completion, failure)` read fresh at post time and the watchlist flag supplied to `WatchlistCheckWorker` through the same factory. Each row has a real consumer — SPEC §23 "Do not build settings that are visually present but non-functional" (`SPEC.md:957`). No other rows are added.

## 19. Documentation requirements after implementation (deferred — not done in this task)

- `ROADMAP.md`: Phase 14 status flip + all eight items checked, with verified evidence (test counts, lint, APK, §20 install).
- `PARITY.md`: rows 603 and 604 → ✅ with evidence; §18 example-coverage note (paused / session-action / Cloudflare-expired explicitly out of scope per §2.1); ownership-ledger lines updated.
- `DECISIONS.md`: record the nine decisions of this plan as Phase 14 decisions, including the PHASE10 §14 supersession note, the PHASE10 §6 deferred-trigger amendment, the tap-only actions ruling (§7), and the AGENTS §17 media3-session verification result.
- `LIMITATIONS.md`: supersede `:315-325` (builders re-created), `:1711-1715` (interval setting exists — keep the inexactness sentence), §34.5/§34.6 ownership lines; add any limitation from §21 that manifests during implementation.
- `PHASE10_ARCHITECTURE_V10.md` §14 (retry supersession) and §6 (deferred-trigger candidate-skip amendment): annotations only (historical protocol), not rewrites.
- Code-comment hygiene: stale `WatchlistCheckWorker.kt:62-66` comment (§13).
- `scripts/check-doc-citations.ps1` must PASS with EXIT=0; per-file conventions preserved (PARITY pure CRLF single-line edits; ROADMAP/LIMITATIONS/DECISIONS/LIMITATIONS LF; even backtick counts).

## 20. Exact Phase 14 exit criteria

1. R1–R8 all implemented and verified by the §17 tests; no TODO/FIXME/placeholder in production code.
2. JVM suite green (baseline 692 + additions, exact counts reported), device suite green (baseline 383 + additions), lint `0 errors`, `assembleDebug` SUCCESS, APK installed to every connected device per AGENTS §20.
3. PARITY `:604` "To reach ✅" satisfied literally: `NetworkType.CONNECTED` constraint + bounded `Result.retry()`/backoff on transport failure + instrumentation test that severs connectivity mid-download and asserts automatic resumption.
4. Notifications: completion/failure/watchlist observed on device under granted permission; zero posts under denied permission or OFF preference; zero posts for 0-new-episode checks; exactly one live watchlist notification (no stacking).
5. Grouping keys present on all built notifications; `NotificationInteractionTest` green for both destinations (downloads, watchlist).
6. Auto-resume: connectivity-severing test green with zero re-download; bound tests green; cancellation-during-backoff green; 4-case constraint matrix green.
7. MediaSession: `MediaSessionLifecycleTest` green; `PlayerLifecycleTest` 8/8 green **unchanged**; `livePlayerCount == 1` invariant held.
8. Watchlist: scheduler tests green (single request, UPDATE, new interval, ≥15 min); interval round-trips in `SettingsPersistenceTest`.
9. `SettingsContentTest` exact new row set green; every new row demonstrably affects its consumer.
10. Phase 10 regression suites (§17) green unchanged.
11. Documentation reconciled per §19; citation checker PASS.
12. `@reviewer` APPROVED and `@tester` PASS for Phase 14.

## 21. Known limitations (accepted or pre-existing)

- **P14-L1 — notification ID namespace:** one int space for all notification IDs. media3's fixed positive notification ID can theoretically collide with an episode's progress hash (≈2⁻³¹ per active download while a media session is active) → transient replacement, self-healing on the next progress/media update, no data impact; watchlist `-9001` has the same probability class against terminal IDs (replacement-only impact). PendingIntent request codes share the same int space (progress/terminal `episodeId.hashCode()`, watchlist `-9001`, player `-9002`, §7) — a hash collision could cross-update two intents' extras at the same probability class. Accepted: removing it would require mutating media3's provider or `notificationIdFor`'s established contract (asserted positive/stable by `NotificationTest`) for negligible gain.
- **P14-L2 — enqueue-time constraint semantics:** toggling auto-resume (or Wi-Fi-only) never rewrites already-enqueued requests (documented contract, `WifiOnlyConstraint.kt:22-25`); the connectivity gate applies to requests enqueued after the toggle.
- **P14-L3 — attempt-budget reset:** a reconciler-built fresh request resets `runAttemptCount`; reachable only when the previous request vanished (not caused by transport failures), bounded per request lifetime by the same 8-run rule.
- **P14-L4 — channel stickiness:** preference rows gate *posting*, not sound/importance; importance is fixed at channel creation and changeable only in system settings (the existing "Open settings" affordance).
- **Pre-existing:** periodic inexactness/no exact tick (LIMITATIONS `:1711`); reboot continuation gate LIMITATIONS §24 R5 remains open — Phase 14 claims connectivity-resume only, never reboot-verified continuation (PARITY `:604` criteria do not include it); no paused/session-action/Cloudflare-expired notification states (§2.1); foreground `checkNow()` never notifies (by design); §34.10 harness preconditions apply to the new device tests.

## 22. BLOCKING DECISIONS

**None.** All nine required decisions were resolved from repository/specification evidence:

1. **Auto-resume failure semantics** → §10/§14: typed `TRANSPORT`-only eligibility; worker-origin failures terminal; no string matching.
2. **Retry bound & backoff** → §15: default OFF; `Result.retry()` bounded to 7 retries (≤8 total runs, `runAttemptCount` starts at 0); exponential 30 s; exhaustion → existing FAILED path; counters/process death/reconciler interactions defined.
3. **CONNECTED vs UNMETERED** → §9: single `downloadNetworkConstraints(wifiOnly, autoResume)` used at all three sites; explicit 4-row matrix; Wi-Fi-only dominates.
4. **Notification channels** → §8: four app channels (progress LOW unchanged; complete/failure/watchlist DEFAULT), media3-owned media channel, sticky-aware creation, preference mapping defined.
5. **Notification IDs** → §8: progress unchanged (positive/stable), terminal on sign-bit negative IDs after progress cancel (never both visible), watchlist fixed `-9001`, media media3-owned; duplicate rules explicit.
6. **Notification actions** → §7: tap-to-open only, per SPEC §18's own wording; destinations verified against real routes; no invented navigation; interaction test owned by Phase 14.
7. **Watchlist notification vs auto-download** → §12.2: per-run single aggregated notification on detection; silent foreground checks; relationship with download notifications defined; no spam.
8. **MediaSession** → §11: `media3-session` 1.11.1 under AGENTS §17 verification; service-hosted session wrapping the single VM-owned ExoPlayer; release-once and PlayerLifecycleTest preserved.
9. **Watchlist check frequency** → §12.1: persisted key, default 15, allowed {15,30,60,240,720} ≥ WorkManager minimum, UPDATE policy on the single unique periodic request, Application-level Flow registration, no exact-time claims.

No human/product decision remains that the repository or SPEC cannot answer.

## 23. Explicit scope statement

**Phase 15 (Full Test Suite) and Phase 16 (Parity Audit) are OUT OF SCOPE for this plan and were not started.** This document is architecture only: no production code, tests, Gradle, or dependency files were modified; `ROADMAP.md` Phase 14 remains `PENDING`; Phase 14 implementation was NOT started.