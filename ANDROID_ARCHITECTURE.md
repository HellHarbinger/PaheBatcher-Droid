# PaheBatcher Android — Application Architecture

**Last verified: 2026-10-18 (source inspection)**

Package: `com.pahebatcher.app` — root: `pahebatcher/src/main/java/com/pahebatcher/app/`

This document describes the runtime architecture of the native Android client as it
exists in the current source tree. Every class, constant and behavior named here was
read from the files listed. Companion document:
[`ANDROID_DATA_AND_STATE.md`](ANDROID_DATA_AND_STATE.md) (persistence, state machine,
identity rules, invariants).

Companion handoff documents:
[`ANDROID_USER_FLOWS.md`](ANDROID_USER_FLOWS.md) ·
[`ANDROID_TESTING_AND_RELEASE.md`](ANDROID_TESTING_AND_RELEASE.md) ·
[`ANDROID_FEATURE_CATALOG.md`](ANDROID_FEATURE_CATALOG.md) ·
[`ANDROID_AI_HANDOFF.md`](ANDROID_AI_HANDOFF.md)

---

## 1. Application purpose

PaheBatcher is a single-Activity native Android client for the animepahe streaming
site. End-to-end it can:

- **Search** the catalog (`AnimeRepository` over the WebView-backed HTTP client).
- **Browse episodes with lazy pagination** (`EpisodeRepository` + `EpisodePaging`,
  backed by a Room scan cache with a configurable TTL).
- **Download episodes via HLS**: playlist fetch → bounded parallel segment fetch →
  per-segment AES-128-CBC decryption → atomic segment writes → FFmpeg remux
  assembly (`FfmpegEpisodeAssembler`) → output verification
  (`EpisodeOutputVerifier`) → MediaStore or SAF export.
- **Play downloads** in an in-app Media3/ExoPlayer player with a `MediaSession`
  (`PaheMediaSessionService`), or stream an episode just-in-time
  (`PlayerMode.STREAM`).
- **Watchlist**: periodically scan tracked anime for new episodes
  (`WatchlistChecker` driven by `WatchlistCheckWorker`) and notify.
- **Notify**: foreground download progress, completion, failure (with typed
  reason), and aggregated new-episode announcements — through four notification
  channels.

---

## 2. Layered architecture

```
UI (Compose screens, ui/*)
   └─ observes immutable UiState (StateFlow) + invokes lambdas
        ↓
ViewModel (ui/*/…ViewModel.kt)          ← viewModelScope for UI work
   └─ never touches HTTP / HLS / Room / WorkManager details directly
        ↓
Repository / data layer (data/*)       ← repositoryScope-style app scopes, Dispatchers.IO
   └─ DownloadRepository, EpisodeRepository, ScanCacheRepository, SessionActions …
        ↓
Domain (domain/*)                      ← pure Kotlin, JVM-testable
   └─ EpisodeId, EnqueueOutcome, HlsPlaylistParser, EpisodePaging, WatchlistChecker, …
        ↓
Platform (Android)
   OkHttp (HLS/CDN) · WebView session (Cloudflare) · WorkManager · Room ·
   MediaStore/SAF · Media3 · DataStore · NotificationManager
```

Enforced rules visible in the code:

- Composables receive state + lambdas only; they never build HTTP clients, never
  resolve streams, never touch the database (`AppContainer` KDoc: "composables
  never see it").
- ViewModels obtain collaborators through `AppViewModelFactory`, never through a
  framework or static lookup.
- UI state is never authoritative for downloads — Room is (see
  `ANDROID_DATA_AND_STATE.md`).

---

## 3. Composition root and dependency injection

**Manual DI — no Hilt/Koin** (deliberate: `AppContainer` KDoc states an extra DI
framework would add KSP processing, runtime and APK surface for no gain).

| Component | File | Role |
|---|---|---|
| `PaheBatcherApplication` | `PaheBatcherApplication.kt` | Owns `AppContainer`; implements `WorkManager.Configuration.Provider`; explicitly calls `WorkManager.initialize()` with a custom `DownloadWorkerFactory` before any ContentProvider can trigger WorkManager; registers the periodic watchlist check from `onCreate` (never from an Activity); runs startup reconciliation exactly once per process (`AtomicBoolean reconciliationRun`). |
| `AppContainer` | `di/AppContainer.kt` | Lazy composition of every production object: Room singleton, DataStore `AppConfig`, notification manager, network monitor, `MediaSessionHost`, exporters, repositories, session chain, resolvers, `WatchlistChecker`. |
| `AppViewModelFactory` | `di/AppViewModelFactory.kt` | A `when`-branch `ViewModelProvider.Factory` constructing `HomeViewModel`, `DownloadsViewModel`, `SettingsViewModel`, `ConsoleViewModel`, `AnimeDetailsViewModel`, `WatchlistViewModel`, `SessionsViewModel`, `PlayerViewModel` (and, via `MainActivity`, the `SessionRefreshViewModel`); throws `IllegalArgumentException` for unknown classes. |

**Application-scoped coroutine scope** (`PaheBatcherApplication`):
`CoroutineScope(SupervisorJob() + Dispatchers.IO)` — used for the log-buffer
collector, watchlist schedule collection and startup reconciliation only.

**Session chain composition order** (`AppContainer` KDoc, verified):

```
AndroidSessionPersistence → AndroidCloudflareSession → AndroidWebViewRequestBridge
   → WebViewHttpClient → AnimeRepository
```

One `Mutex` (`AppContainer.webViewPageLock`) is shared by the HTTP bridge, the
stream resolver and the audio-option probe: the underlying transport is ONE
`WebView(applicationContext)` whose capture client is swapped per request, so
exactly one page operation may run at a time process-wide.

`AppContainer` also owns **the one app-scoped HLS host limiter**
(`HlsHostConcurrencyLimiter(config.hlsWorkers)`), handed to every worker-factory
composition so all concurrent downloads share one per-host ceiling.

**Fresh-read providers.** `PaheBatcherApplication` supplies suspend lambdas that
read DataStore *at the moment of use* — Wi-Fi-only (`appConfig.wifiOnly.first()`),
auto-resume, terminal-notification prefs, watchlist-notification pref — through
`DownloadWorkerFactory`, at both WorkManager composition sites, and into
`DownloadRepository`/`DownloadStateReconciler`. A settings flip therefore shapes
the very next enqueue/notification/failure decision, never the next app start.

---

## 4. Package structure

| Package | Responsibility |
|---|---|
| `data/config` | `AppConfig` — the single DataStore (`pahebatcher_settings`) for all persisted settings. |
| `data/download` | `DownloadRepository` (foreground orchestration + admission gate), `DownloadStateReconciler` (startup recovery), `DownloadConfig`/`DownloadConfigSource`, `EpisodeOutputVerifier`, `WifiOnlyConstraint.kt` (`applyDownloadWorkPolicy`, the one network-constraint rule). |
| `data/export` | `EpisodeExporter` interface, `ExportDestinationRouter`, `MediaStoreExporter`, `SafExporter`, `OutputPathNames`, `ExportFileNames`, `SafExportNames`, `PriorOutputRetirement`, `OutputTreeRepository`. |
| `data/hls` | `EpisodeDownloader`, `SegmentFileStore`, `Aes128Decryptor`, `AesKeyCache`, `FfmpegEpisodeAssembler`, `OkHttpHlsClient`, `HlsHostConcurrencyLimiter`, `FileNameGenerator`, `StorageSpace`, `DownloadErrorClass`, `ConcatFileGenerator`, `AudioSpecificConfigNormalizer`. |
| `data/local` | Room: `PaheDatabase` (+ migrations), `dao/` (5 DAOs), `entity/` (5 entities), `Converters`. |
| `data/network` | `NetworkMonitor` interface + `AndroidNetworkMonitor` (default-network callback → `NetworkStatus`). |
| `data/playback` | `PlaybackSourceResolver` (pure source rule), `PlaybackPositionRepository`, `PlaybackPlaylist`. |
| `data/remote` | `HttpClient` interface, `WebViewHttpClient`, `parser/AnimePaheParser`, `parser/PlayPageParser`, `validation/` (Kwik/OkHttp/WebView validators). |
| `data/repository` | `AnimeRepository` (search), `EpisodeRepository` (scan/pagination), `ScanCacheRepository`, `SessionRepository`, `SessionActions` (aggregate session operations), `SessionDisplay`, `WatchlistRepository`, `AudioOptionRepository`. |
| `data/session` | `AndroidCloudflareSession` (WebView session owner), `AndroidWebViewRequestBridge`, `AndroidSessionPersistence`, `WebViewHttpClient`. |
| `data/stream` | `StreamResolverImpl` (play page → Kwik → M3U8), `KwikResolver`, `M3u8Extractor`, `JsPacker`, `AudioSelector`, `QualitySelector`, `HlsPlaybackSourceFactory`, `StreamEpisodeReconstructor`. |
| `di` | `AppContainer`, `AppViewModelFactory`. |
| `domain/download` | `EpisodeId`, `EpisodeDownloadQueue` (+ `ResolvedEpisodeSubmission`), `EnqueueOutcome`, `FailureCategory`, `ReplacementOutcome`, `ResolveAheadGate`. |
| `domain/hls` | `HlsClient`, `HlsPlaylistParser`, `HlsPlaylist`, `HlsSegment`, `HlsKeyInfo`, `EpisodeAssembler`, `HlsTextResult`, `HlsBytesResult`. |
| `domain/model` | `AnimeInfo`, `EpisodeInfo`, `StreamInfo`, `AppContext`, `ThemeSettings`, `ScanProgress`, `ResolutionEntry`, `AudioProbe`. |
| `domain/session` | `CloudflareSessionProvider`, `CloudflareDetector`, `SessionRefreshSignal`, `SessionPersistence`, `WebViewRequestBridge`. |
| `domain/stream` | `StreamResolver` interface, `StreamResolutionRetry`. |
| `domain/util` | `EpisodePaging` (batch validation/staging), `EpisodeRangeParser`, `StringUtils`. |
| `domain/watchlist` | `WatchlistChecker` — the new-episode detection + auto-download use case. |
| `logging` | `AppLog` (buffered in-memory log gated by the `Show Console` setting), `LogUrlSanitizer`. |
| `notification` | `DownloadNotificationManager`, `NotificationChannels`, `DownloadNotifyPrefs`, `NotificationPermissionRepository`, `WatchlistNotificationManager`. |
| `playback` | `MediaSessionHost`, `PaheMediaSessionService` (`mediaPlayback` foreground service). |
| `ui/*` | Compose screens + ViewModels: `home`, `details`, `downloads`, `sessions`, `sessionprompt`, `watchlist`, `settings`, `player`, `console`, `validation` (debug-only), `navigation`, `mapper`, `theme`, `common`. |
| `worker` | `DownloadWorker`, `DownloadWorkerFactory`, `ProgressTracker`, `WatchlistCheckScheduler`, `WatchlistCheckWorker`. |

---

## 5. Single-Activity shell and navigation

`MainActivity` (`MainActivity.kt`) is the only Activity:

- Edge-to-edge theming derived from the selected theme (`PaheBatcherTheme`,
  `shouldUseDarkColors`), not the system setting.
- One `AppNavHost` (`ui/navigation/AppNavHost.kt`), `startDestination = HomeRoute`.
- **Notification routing targets**: notifications attach the string extra `navigate_to` (`EXTRA_NAVIGATE_TO`); the Activity
  consumes it **once** (the extra is removed from the Intent so recreation cannot re-navigate; warm taps arrive via
  `onNewIntent`); unknown values navigate nowhere. Targets are string-extra values routed by `AppNavHost`, not manifest
  deep links: `downloads` → `DownloadsRoute` (download completion/failure/progress notifications); `watchlist` →
  `WatchlistRoute` (watchlist updates); `player:<episodeId>:<mode>` → `PlayerRoute(episodeId, mode)` (MediaSession
  tap; request code `-9002` via `createSessionActivityIntent`; the mode is EXPLICIT — `stream` for a streaming
  session, `download` for a local one — because the episode id alone cannot distinguish the two, and both post the
  same media notification; parsed/encoded by `PlayerTarget`, so an already-top same episode stays a no-op that keeps
  its running mode, and a blank id or an unknown mode token is consumed without navigation. The pre-AQR-138 bare
  `player:<episodeId>` form is retained and reads as `PlayerMode.DOWNLOAD` per DECISIONS 118.2, but production no
  longer emits it for the media session). `replace:<episodeId>` (`NAV_TARGET_REPLACE_PREFIX`) → the Downloads tab
  **at its root** (`navigateToDownloadsForReplacement`, the tab rule with `restoreState=false`) with that episode's
  confirmation request armed — a saved nested in-tab stack is never restored on this path, so the confirmation is
  presented on the visible Downloads screen instead of being deferred (AQR-140; LIMITATIONS §53.2).
- **Root session-refresh prompt**: rendered above every route from
  `SessionRefreshViewModel.state` (`MainActivity` "Final-audit H1" comment).

**Type-safe routes** (`ui/navigation/AppRoutes.kt`, `kotlinx.serialization`):

| Route | Argument(s) | Notes |
|---|---|---|
| `HomeRoute` | — | Bottom tab; start destination. |
| `DownloadsRoute` | — | Bottom tab. |
| `AnimeDetailsRoute` | `session: String` (anime session UUID) | Pushed from a Home search row; ID-only — full state re-read from Room/repositories. |
| `SessionsRoute` | — | Pushed from the Downloads app bar. |
| `WatchlistRoute` | — | Pushed from the Home app bar. |
| `SettingsRoute` | — | Pushed from Home; never a bottom tab. |
| `ConsoleRoute` | — | Pushed from Settings only when `Show Console` is ON. |
| `PlayerRoute` | `episodeId: String`, `mode: PlayerMode = DOWNLOAD` | `PlayerMode.DOWNLOAD` plays local exports; `PlayerMode.STREAM` resolves just-in-time HLS streaming. Replace-in-place via `navigateToPlayer` (`popUpTo<PlayerRoute>{inclusive=false}` + `launchSingleTop`). The notification path passes `mode` EXPLICITLY from the target's `PlayerTarget` (AQR-138); the `DOWNLOAD` default only serves the in-app callers whose semantics are already DOWNLOAD (Downloads `Play`, tests). |

Tab switches use `navigateToTab`: `popUpTo(HomeRoute){saveState=true}` +
`launchSingleTop` + `restoreState`. The ONE exception is the `replace:` target's
`navigateToDownloadsForReplacement`, which is the same rule with `restoreState=false`
so the tab is entered at its ROOT (AQR-140).

---

## 6. UI state conventions

- Every ViewModel exposes an immutable `@Immutable`/`data class` UiState through a
  `StateFlow` (e.g. `DownloadsUiState` from `DownloadsViewModel.uiState`, mapped
  from `DownloadRepository.observeAll()` via `DownloadRowMapper`).
- Room is the single source of truth; screens observe ordered flows and never poll.
- **Error surfaces**: failed rows carry the persisted `errorMessage` text and a
  Retry action; the action matrix per engine state is normative in
  `DownloadRowMapper.actionsFor` (Pause on QUEUED/RESOLVING/DOWNLOADING, Resume
  only on PAUSED, Retry only on FAILED/CANCELLED, Play only on COMPLETED, Cancel
  on all non-terminal states).
- **Session-refresh prompt** (one quiet attempt, then prompt):
  data-layer detection sites raise `SessionRefreshSignal.requestUserPrompt(reason)`
  (a `MutableSharedFlow`, **no replay**, bounded, DROP_OLDEST; reasons are URL-free
  strings). `SessionRefreshViewModel` makes ONE silent
  `AppContainer.forceRefreshWebViewSession()` attempt; success shows nothing, a
  failed attempt sets a latch so every later failure surfaces the AlertDialog
  immediately. A user-confirmed successful refresh clears the latch.

---

## 7. Network and session subsystem

**Two distinct transports:**

1. **Cloudflare-protected animepahe requests** — WebView only.
   `AndroidCloudflareSession` (`data/session/AndroidCloudflareSession.kt`) owns a
   single non-window-attached `WebView(applicationContext)` running on the main
   looper. Session states: `NOT_STARTED → ESTABLISHING → ESTABLISHED`, with
   `EXPIRED` and `FAILED` branches (`CloudflareSessionProvider.SessionState`).
   Readiness requires the `cf_clearance` cookie to be present.
   Native HTTP (OkHttp/curl-style TLS tricks) is never used against the site
   because Cloudflare binds `cf_clearance` to the TLS fingerprint that solved the
   challenge (class KDoc).
2. **HLS/CDN downloads and playback transport** — OkHttp
   (`OkHttpHlsClient`: 15 s connect / 60 s read+write timeouts, redirects on;
   `HlsPlaybackSourceFactory` for streaming). These endpoints are not
   Cloudflare-protected; the resolver's result carries the headers to replay.

**`establishSession(forceRefresh)`** — on `forceRefresh = true` the session first
runs `clearStaleClearanceCookie()`: a store-wide `removeAllCookies` reset (main-looper
API; the durability flush on IO), so no stale cookie of ANY storage form can be
mistaken for success by the presence-only `cf_clearance` checks — a WebView-created
cookie was proven to match no `setCookie` deletion tuple (DECISIONS 157.3). The fresh
bootstrap re-establishes every cookie the flow needs. A non-forced re-establishment
never clears anything.

**Recovery flow**: `AndroidWebViewRequestBridge.fetch` establishes the session
before each request, retries bounded (config `maxRetries`, default 1), and both it
and `AndroidCloudflareSession.fetchPage` raise `SessionRefreshSignal` when the
session is not ready. `fetchPage` also reports real main-frame 401/403 statuses
(`SessionRefreshSignal.reportMainFrameStatus`) — a completed challenge reloads to
2xx, so only genuine credential refusals prompt.

**Stream resolution** (`StreamResolverImpl`, `data/stream/`): play-page fetch via
the session → quality/audio selection (`QualitySelector`, `AudioSelector`) → Kwik
page (`KwikResolver`, with `JsPacker` for packed JS) → M3U8 extraction
(`M3u8Extractor`) → `StreamInfo` (url, userAgent, referer, cookies). Resolution is
serialized by the shared `webViewPageLock`. `StreamResolutionRetry.resolveWithRetry`
provides bounded attempt loops for batch/watchlist paths.

---

## 8. Download pipeline

### 8.1 Admission gate — `DownloadRepository.queueResolvedEpisode`

The single entry (`EpisodeDownloadQueue` interface; production implementation
`DownloadRepository`). Takes an already-resolved `ResolvedEpisodeSubmission` and
performs: parent session row (`ensureSession`, `INSERT OR IGNORE` — never
`INSERT OR REPLACE`, which would fire the FK cascade and delete episode rows) →
episode row created **straight into `RESOLVING`** → fresh stream persisted
(`saveStreamInfo`: m3u8Url/userAgent/referer/segmentDir) → CAS
`RESOLVING → QUEUED` (`resolveComplete`) → unique WorkManager hand-off
(`enqueueUniqueWork("episode_download_<episodeId>", KEEP)`).

The gate is **row-aware and destination-aware**: before any mutation it classifies
the existing row via `suppressedOutcome` and probes the export destination via
`EpisodeExporter.destinationOutput` (`Uri?` — a surviving MediaStore export with no
Room row is the same no-op as a healthy COMPLETED row; the reconciler consults the
same probe before demoting, re-pointing a severed `outputUri` instead — DECISIONS
157.1). The result is reported as an
`EnqueueOutcome` — `Accepted` is returned only when the Worker hand-off CAS really
won:

| Row state observed | Outcome |
|---|---|
| no row (created / won creation claim) | `Accepted` |
| `FAILED` / `CANCELLED` | `Accepted` (legitimate re-queue) |
| `COMPLETED`, output verified present (or destination survives) | `AlreadyDownloaded` |
| `COMPLETED`, output missing | `OutputMissing` (re-downloads) |
| `PAUSED` | `AlreadyPaused` |
| `DOWNLOADING` / `ASSEMBLING` / `RESOLVING` / `QUEUED` with stream | `AlreadyActive` |
| `QUEUED` WITHOUT stream (reconciler zombie) | `Accepted` (this call repairs it) |
| hand-off CAS lost; row now terminal or gone | `Refused` |

### 8.2 Worker — `worker/DownloadWorker.kt`

- `CoroutineWorker`; input data key `episode_id`.
- **Entry CAS** `claimEntryState`: QUEUED/DOWNLOADING/ASSEMBLING → DOWNLOADING
  (stale workers exit without touching the row). A `maxParallel` gate
  (`dao.countActive()`, states DOWNLOADING|ASSEMBLING) runs *before* the claim —
  over-capacity runs return `Result.success()` leaving the row QUEUED for a later
  trigger.
- **Foreground**: `setForeground(ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC))`
  on the `download_progress` channel; manifest declares
  `FOREGROUND_SERVICE_DATA_SYNC`.
- **Run config**, refreshed once per run from `AppConfigDownloadConfigSource`
  (logged as `Run config: maxParallel=… hlsWorkers=… keepTemp=… episodeRetry=…`):
  defaults `maxParallel=2` (1–6), `hlsWorkers=24` (8–32), `keepTemp=false`,
  `episodeRetryCount=2` (raw; attempts = autoRetry+1), `progressThrottleMs=5000`.
- **Progress**: one bounded flush coroutine (`ProgressTracker`) persists to Room at
  most every `progressThrottleMs`; notification updates throttled in-callback;
  `totalSegments` written once at manifest resolution; the entry claim clears
  `speed`/`etaSeconds` (stale-rate guard).
- **Remux state**: the `onAssemblyStart` hook CASes `DOWNLOADING → ASSEMBLING`
  immediately before FFmpeg runs; every later guarded write expects ASSEMBLING if
  that claim landed, else DOWNLOADING.
- **Success**: CAS to COMPLETED (`updateCompletedIfState`), then idempotent export
  via the injected `EpisodeExporter` (export failure never demotes the row —
  reconciliation retries), then the completion notification (gated fresh by the
  `notify_completion` preference, only when the CAS landed).
- **Failure**: `reportTerminalFailure` — CAS-guarded `FAILED` + persisted reason,
  typed `FailureCategory` (`FailureCategory.fromDownloadErrorClass`) in the
  failure notification. A confirmed `Replace file` that produced nothing
  verifiable instead restores COMPLETED (existing export kept).
- **Auto-resume** (setting `auto_resume`, default OFF): only a `TRANSPORT`-class
  failure with `runAttemptCount < AUTO_RESUME_MAX_RETRIES (7)` parks the row
  (`DOWNLOADING → QUEUED`) and returns `Result.retry()` with exponential backoff
  (30 s base). All other classes (EXPIRED, NO_CONTENT, ASSEMBLY, DISK_FULL,
  IN_RUN_RETRYABLE) are terminal.
- **Deferred trigger**: `doWork()`'s `finally` (every path, under
  `NonCancellable`) calls `triggerNextDeferredEpisode` — oldest-first scan of up
  to `TRIGGER_CANDIDATE_CAP = 50` QUEUED-with-stream rows, enqueueing the first
  with no live unique work while `countActive() < maxParallel`.
- **The Worker never resolves streams**: it is forbidden to call
  `StreamResolver`/WebView (class KDoc). Retry always re-resolves in the
  foreground via `DownloadRepository.reEnqueue` (fresh `StreamResolver.resolve`
  from the durable `playUrl`).

### 8.3 Network constraints — `data/download/WifiOnlyConstraint.kt`

The one rule (`applyDownloadWorkPolicy`, used by all three WorkRequest build
sites: repository enqueue, worker trigger, reconciler re-enqueue):

| Wi-Fi-only | Auto-resume | Constraint |
|---|---|---|
| OFF | OFF | none |
| OFF | ON | `NetworkType.CONNECTED` |
| ON | OFF | `NetworkType.UNMETERED` |
| ON | ON | `NetworkType.UNMETERED` (dominates) |

Plus `BackoffPolicy.EXPONENTIAL, 30 s`. Already-enqueued requests keep their
constraints (`ExistingWorkPolicy.KEEP`).

---

## 9. HLS engine (`data/hls/`)

Pipeline inside `EpisodeDownloader.download`:

1. **Existing-output short-circuit**: a non-empty `Ep_<prefix>_-_Title.mp4` in the
   segment directory returns success with `reusedExistingOutput = true` (no
   playlist fetch).
2. **Playlist fetch** — `fetchM3u8WithRetry`: 5 attempts, 500 ms exponential base;
   HTTP 403/404 are permanent (`DownloadErrorClass.EXPIRED`, "stream expired");
   others transient (`TRANSPORT`).
3. **Master playlist** → last variant is fetched and parsing recurses.
4. **AES-128 keys** — distinct `keyUrl`s fetched (16-byte validation) with
   `AesKeyCache` (process-wide, thread-safe); key fetch has its own 5-attempt
   retry; wrong-size keys are re-fetched, never accepted.
5. **Resume accounting** — `segmentStore.doneIndices()` (`.ts` files only) splits
   the playlist into completed/pending; the byte resume base is
   `completedSegmentBytes()` (one directory walk; `.tmp` partials never count).
6. **Storage pre-flight** — `StorageSpace.estimatedRemainingBytes` vs
   `availableBytes`; an estimated shortfall refuses before spending bandwidth
   (`DISK_FULL`). A real `ENOSPC` during any write is classified `DISK_FULL`
   (`StorageSpace.isNoSpaceLeft`).
7. **Concurrent segment download** — `Semaphore(hlsWorkers)` over a
   `SupervisorJob() + Dispatchers.IO` scope; per-segment retry
   (5 attempts / 500 ms base); failed segments never cancel siblings; a
   process-wide `HlsHostConcurrencyLimiter` (`Semaphore` per host, sized
   `hlsWorkers`) bounds per-host requests across ALL workers.
8. **Decryption** — `Aes128Decryptor`: AES-128-CBC **NoPadding** (raw CBC; an
   empty ciphertext is rejected — corrupt, not "unpadded"). Segment IVs come from
   the parsed playlist (`HlsSegment.iv`, explicit or media-sequence-derived).
9. **Atomic write** — `SegmentFileStore.writeSeg`: write `%06d.tmp`, then rename to
   `%06d.ts` (with delete-and-retry fallback); an interrupted write leaves only a
   `.tmp`, never a partial `.ts`. Orphan `.tmp`s are cleaned at attempt start.
10. **Error aggregation** — by typed origin priority: DISK_FULL > EXPIRED >
    all-TRANSPORT > IN_RUN_RETRYABLE. In-run episode retry
    (`autoRetry+1` attempts, 1.5 s exponential base) applies only to
    TRANSPORT/IN_RUN_RETRYABLE; permanent classes end the budget immediately.
11. **Assembly** — `FfmpegEpisodeAssembler` (see §10), invoked with the playlist's
    own segment indices (files are keyed by `EXT-X-MEDIA-SEQUENCE`).
12. **Cleanup** — on success with `keepTemp=false`, `segmentStore.cleanup()`
    removes segments/temps/`concat.txt` but **never** a `.mp4` in the directory.
    (Device-verified 2026-10-18: cache reads 0.0 B after a completed episode; a
    failed episode retains its partial segments — e.g. 3.3 MB — for resume.)

`OkHttpHlsClient` sends the persisted User-Agent/Referer/cookies per request; all
WARN/ERROR URL logging passes through `LogUrlSanitizer` (query strings — the
actual authorization — are never logged).

---

## 10. Assembly / remux (`FfmpegEpisodeAssembler`)

- Check order: existing non-empty output → skip; config integrity; segment
  existence for the exact `segmentIndices`; ABI availability gate; then run.
- **Primary path**: all segments appended in playlist order into one contiguous
  MPEG-TS temp file, then `ffmpeg -y -i <temp> -c copy -f mp4 -movflags +faststart
  <out>.tmp` → atomic rename to the final name. (FFmpegKit does not expose stdin
  piping; the temp file stands in for `pipe:0`.)
- **Fallback path**: concat demuxer (`concat.txt`, `-f concat -safe 0`), retained
  for inputs the single-input path cannot open — deliberately not the default
  because the demuxer's duration-anchored offsets accumulate per-boundary
  timeline stretch (measured +8.5 s over 143 segments; the single-input path
  reproduces a gap-free timeline).
- Output is written to `<name>.mp4.tmp` and renamed only on success; `.tmp` and
  `concat.txt` are always cleaned.
- FFmpeg failures are deterministic (`ASSEMBLY`, or `DISK_FULL` when the message
  reports no space) — never retried in-run, never mistaken for connectivity.

---

## 11. Export subsystem (`data/export/`)

`EpisodeExporter` is the single export surface (Worker success path, startup
reconciliation, destination probe): `export(episode)`, `sweepStalePending`,
`destinationOutput` (`Uri?`). All export/sweep operations across both destinations and
all instances serialize on the one process-wide `ExportOperationLock.mutex`.

**`ExportDestinationRouter`** picks the destination per export:
SAF tree when `output_tree_uri` is set AND `output_tree_rename_ok` AND the tree
validates (`OutputTreeRepository.state()`); otherwise the MediaStore default. A
`RenameUnsupported` SAF result persists the flag and completes the same export
via MediaStore. The stale-pending sweep is always the MediaStore sweep.

**`MediaStoreExporter`** — normative sequence:
deterministic lookup (§30.2) → insert `IS_PENDING=1` → stream-copy (bounded
64 KiB buffer) → byte-count verify → persist `outputUri` in Room (X4) → clear
`IS_PENDING` (X5) → delete the local MP4 (X6). Guarantees: at every instant the row
is invisible or Room already knows its URI; the local file is deleted only after
byte-verified persistence; any intermediate failure deletes the pending row and
keeps the local file.

- Destination: `Movies/<sanitized anime title>/` (`OutputPathNames`; the title
  derives from the parent `DownloadSessionEntity.title`; hardening: no path
  separators, no `.`/`..`, Windows reserved names prefixed with `_`, deterministic
  fallback chain title → sessionId → `"PaheBatcher"`). The legacy flat
  `Movies/PaheBatcher/` remains valid for existing files but receives no new
  exports.
- Leaf name: `ExportFileNames.exportedName(prefix, title, audio)` →
  `Ep_001_-_Title_[jpn].mp4` (audio-suffixed; the unsuffixed
  `ExportFileNames.legacyName` is a reuse-only alias, never deleted).
- §30.2 lookup tolerates MediaStore ` (n)` uniquification via
  `nameMatches` (exact name or `stem (n).mp4` regex). Reuse is **size-verified**,
  never byte-compared, and a candidate referenced by another Room row is never
  adopted or deleted (cross-reference guard via `otherReferenceAudios`). An
  EXACT-name size-mismatched row is this episode's crash residue/superseded copy
  and is deleted (the one-row replacement); a visible ` (n)` row is left untouched.
- Queries always filter `OWNER_PACKAGE_NAME = BuildConfig.APPLICATION_ID` and use
  the Bundle form with `QUERY_ARG_MATCH_PENDING` so pending rows are visible to
  reuse/sweep. The §28 sweep removes pending rows older than the reconciliation
  instant unless referenced in Room.

**`SafExporter`** — `local MP4 → <final-name>.pb.part child → rename → byte verify
→ Room X4 → local X6`. `.pb.part` children are app-owned (the only deletion
carve-out), MIME `application/octet-stream` (invisible to galleries); cleanup runs
at export start only. Existing finals are never overwritten — SAF uniquifies with
` (n)` (deliberately different from MediaStore's exact-name replacement).
Rename-less providers persist `output_tree_rename_ok=false` and fall back to
MediaStore.

**`PriorOutputRetirement`** (Replace file): runs inside the same export lock, only
after the new output is committed and verified; deletes the persisted
`replacePriorUri` through its own provider primitive (MediaStore
`ContentResolver.delete` / SAF `DocumentsContract.deleteDocument`); never guesses
by name; keeps the reference on failure so a later pass retries.

---

## 12. Playback

- `PlaybackSourceResolver` (pure, JVM-testable): `outputUri` (MediaStore/SAF
  `content://`) preferred, else `outputPath` (app-private MP4), else nothing to
  play — never a fabricated URL.
- `PlayerViewModel` (nav-entry-scoped via `AppViewModelFactory`, constructed with
  `AppContainer.context`) builds a single ExoPlayer; local entries become
  `MediaItem`s from the resolver's strings; `PlayerMode.STREAM` reconstructs the
  episode's `playUrl`, resolves just-in-time and builds an OkHttp-backed
  `HlsMediaSource` via the app-scoped `HlsPlaybackSourceFactory` (headers from the
  resolver's `StreamInfo`).
- In-player next/previous uses the sibling COMPLETED rows
  (`DownloadRepository.getCompletedPlaylist` → `PlaybackPlaylist`), switching
  `MediaItem`s in place (never stacked players).
- **MediaSession**: `MediaSessionHost` (app-scoped) starts/stops
  `PaheMediaSessionService` (manifest `foregroundServiceType="mediaPlayback"`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission); the session activity is the
  `player:<episodeId>:<mode>` PendingIntent (`MainActivity.createSessionActivityIntent`,
  AQR-138). The host records the route seed's playback mode alongside the episode id
  (`MediaSessionHost.onPlayerCreated`), so a media notification posted for a STREAM
  session re-opens STREAM and one posted for a DOWNLOAD session re-opens DOWNLOAD;
  `ensureSession` refuses to build a session whose playback mode is not recorded rather
  than emitting a target that would silently downgrade to DOWNLOAD.
- Playback positions persist per-episode in Room
  (`PlaybackPositionRepository` / `playback_position`, no TTL in v1).

---

## 13. Watchlist

- `WatchlistChecker` (`domain/watchlist/`) is the ONE use case shared by the
  Watchlist screen's "Check now" and the background `WatchlistCheckWorker` (both
  through the app-scoped instance in `AppContainer`). `checkAll()` walks every
  entry sequentially with per-entry isolation; each check forces a fresh scan and
  persists `lastCheckedAt`. New episodes are auto-queued through the same
  `queueResolvedEpisode` pipeline, bounded by `ResolveAheadGate` capacity
  (`resolveAhead + entry.maxParallel`) and `StreamResolutionRetry`
  (`autoRetry + 1` attempts); >100 unique episodes are refused as a whole
  (`EpisodePaging.validateBatch`). Per-entry audio preference: `"any"` (default
  for new entries) resolves each number's deterministic default variant; a
  concrete code (`jpn`, `eng`, …) is used strictly.
- `WatchlistCheckScheduler` — the one periodic request
  (`watchlist_periodic_check`, `ExistingPeriodicWorkPolicy.UPDATE`,
  `NetworkType.CONNECTED`), registered from `PaheBatcherApplication.onCreate`
  via `watchlistCheckIntervalMinutes.distinctUntilChanged()`. Interval options
  15/30/60/240/720 minutes (default 15; all ≥ WorkManager's minimum).
- `WatchlistCheckWorker` — orchestration only (`checker.checkAll()` → WorkManager
  result): finished runs are `Result.success()` (per-entry failures are results,
  not worker failures), an escaped infrastructure exception is `Result.retry()`,
  cancellation rethrows, `Result.failure()` is never used. A run that detected
  episodes posts the single aggregated `WatchlistNotificationManager` notification
  (gated fresh by `notify_watchlist`).

---

## 14. Notifications and permissions

**Channels** (`NotificationChannels`): `download_progress` (IMPORTANCE_LOW,
silent), `download_complete`, `download_failure`, `watchlist_new_episodes` (all
DEFAULT). All notifications share group `pahebatcher.downloads`.

**Text patterns** (`DownloadNotificationManager`):
- Progress: title `"Downloading"`, content `"<episodeTitle> · <audioLabel> — NN%"`
  (`progressText`; no `%` suffix when progress is unknown/indeterminate). Audio
  labels (`DownloadRowMapper.audioLabelFor`): `jpn → "Japanese"`,
  `eng → "English"`, any other code uppercased; absent audio renders no label.
- Completion: the episode line alone; failure: reason-bearing text with the typed
  category, plus the explicit suffix `" (replacement failed, existing file kept)"`
  when a failed replacement kept its export.
- Batch failure summary: `"<failed> of <total> episodes failed — <anime title>"`,
  optionally `· <reason breakdown>` (`A ×2, B`, top 3 + `+N more`, deterministic
  ordering; `REASON_SUMMARY_LIMIT = 3`). Batch failure id `-9002`.
- Per-episode ids derive from the episodeId (`notificationIdFor` /
  `terminalIdFor`); content intents use the `navigate_to` contract (§5). A
  **Replace file** action (`ACTION_REPLACE_FILE_LABEL`) opens the `replace:`
  confirmation.

**Permissions** (`AndroidManifest.xml`): `INTERNET`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
`POST_NOTIFICATIONS`. There is **no broad storage permission** — exports go
through MediaStore (scoped) and SAF (user-picked tree). `POST_NOTIFICATIONS` is a
runtime request managed by `NotificationPermissionRepository` (asked-once latch +
last grant state persisted in DataStore).

---

## 15. Settings surface (`ui/settings/SettingsScreen.kt` + `SettingsViewModel`)

All rows persist through `AppConfig` (single DataStore `pahebatcher_settings`):

| Row | Key | Values / default |
|---|---|---|
| Theme | `theme_mode`, `dynamic_color` | system/dark/light (default dark), dynamic color (default OFF) |
| Quality | `quality` | 360 / 720 / 1080, default 1080 |
| Max parallel downloads | `max_parallel` | 1–6, default 2 |
| HLS workers | `hls_workers` | 8–32, default 24 |
| Scan cache TTL | `cache_ttl` | minutes, default 60 |
| Auto-retry | `auto_retry` | 0–2, default 2 |
| Keep temp files | `keep_temp` | default OFF |
| Wi-Fi-only downloads | `wifi_only` | default OFF (enqueue-time `UNMETERED` constraint) |
| Auto-resume downloads | `auto_resume` | default OFF (TRANSPORT-only `Result.retry()`, ≤7 runs) |
| Terminal notifications | `notify_completion`, `notify_failure` | default ON (read fresh at post time) |
| New watchlist episodes | `notify_watchlist` | default ON |
| Watchlist check frequency | `watchlist_check_interval_minutes` | 15/30/60/240/720 min, default 15 |
| Output tree (SAF) | `output_tree_uri`, `output_tree_rename_ok` | user-picked tree; **fallback semantics**: unusable/revoked/unsupported tree → MediaStore default for future exports, download pipeline never blocked; surfaced in Settings |
| Show Console | `show_console` | default OFF; gates the in-memory `AppLog` buffer and the Console route |

A worker run reads its configuration **once at run start** — a settings change
applies to the next run; enqueue-time settings (Wi-Fi-only, auto-resume,
notification prefs) are read fresh at each decision point.

---

## 16. Concurrency model

| Scope | Owner | Used for |
|---|---|---|
| `viewModelScope` | each ViewModel | UI orchestration, repository calls, prompt handling |
| `applicationScope` (`SupervisorJob + Dispatchers.IO`) | `PaheBatcherApplication` | settings collectors, watchlist schedule, startup reconciliation |
| WorkManager coroutine scope | `DownloadWorker` / `WatchlistCheckWorker` | background work (survives process death) |
| `CoroutineScope(SupervisorJob() + Dispatchers.IO)` + `Semaphore(hlsWorkers)` | `EpisodeDownloader.downloadOnce` | bounded segment fan-out; cancelled in `finally` |

- `Dispatchers.IO` wraps every blocking boundary (Room→verifier Binder IPC,
  MediaStore/SAF queries, cookie clearing).
- **Bounded everywhere**: `maxParallel` episodes (Worker gate), `hlsWorkers`
  segments per episode (semaphore), one per-host ceiling across the process
  (`HlsHostConcurrencyLimiter`), one page operation at a time
  (`webViewPageLock`), sequential exports (`ExportOperationLock`), candidate-capped
  deferred trigger (50).
- **Structured cancellation**: `CancellationException` always propagates (never
  converted to failure); terminal Room writes at teardown run under
  `NonCancellable` with CAS guards so a newer user state always wins.

---

## 17. Build configuration (`pahebatcher/build.gradle.kts`)

| Property | Value |
|---|---|
| applicationId / namespace | `com.pahebatcher.app` |
| compileSdk / targetSdk | 37 |
| minSdk | 32 |
| versionCode / versionName | 1 / `0.1.0` |
| JVM toolchain | 25 (`jvmToolchain(25)`); compileOptions Java 17 |
| Build types | `debug` (default) + `release` (`isMinifyEnabled = false`) |
| Room | `exportSchema = true`, schemas in `pahebatcher/schemas/` |
| Key libraries | Compose (BOM, Material3, material-icons-core), Room (KSP), DataStore Preferences, kotlinx-serialization-json, OkHttp, ffmpeg-kit-full (+ smartexception-java runtime), WorkManager, Media3 (exoplayer, exoplayer-hls, datasource-okhttp, session, ui-compose), Navigation Compose, Lifecycle ViewModel/Runtime Compose |

`BuildConfig.DEBUG` gates the `ValidationScreen` overlay — release builds can
never reach it.

---

## 18. Architectural constraints and why they exist

1. **Cloudflare is handled only by the WebView.** Cloudflare binds `cf_clearance`
   to the TLS fingerprint that solved the challenge, so native HTTP cannot pass
   the challenge; `AndroidCloudflareSession` is the only session owner and all
   site traffic flows through `AndroidWebViewRequestBridge` under one page lock.
2. **Stream URLs are inputs, not identity.** `m3u8Url`/`userAgent`/`referer` are
   persisted only to hand the resolved stream to the Worker; the durable
   re-resolution source is `playUrl`, and every retry (`beginRetry`) clears the
   stale stream columns before a fresh resolution. Nothing keyed on an expiring URL.
3. **Room is the only authoritative download state.** Every UI row derives from
   Room flows; WorkManager state is consulted only to decide re-enqueue; the
   reconciler restores non-terminal work after process death.
4. **Every state write is a compare-and-set.** Claim/transition DAO statements
   (`updateStateForResume`, `claimTerminalRequeue`, `beginRetry`,
   `updateErrorIfState`, `cancelIfNotTerminal`, …) guard the expected prior state,
   so concurrent actors can never stamp over a newer user or pipeline decision.
5. **Exporters are atomic and idempotent.** Pending → verify → visible ordering
   (MediaStore) and part-file → rename ordering (SAF) mean no visible-but-unknown
   or partial files; the local MP4 is the source of truth until byte-verified
   persistence completes; reconciliation retries without state flips.
6. **Never delete user media on assumption.** Exact-name replacement deletes only
   this episode's own audio-suffixed crash residue; cross-referenced rows and
   other apps' files (`OWNER_PACKAGE_NAME` filter) are untouchable; a failed
   replacement keeps its export and restores COMPLETED.
7. **Bounded concurrency and memory.** 64 KiB copy buffers, ~94 KB segment size
   hint × hlsWorkers, per-host semaphores, capped trigger scans, sequential
   watchlist checks — no unbounded coroutines, no whole-file buffering anywhere.
8. **Cancellation is not failure.** A user cancel persists CANCELLED (terminal,
   CAS-guarded) before the work is cancelled, propagates as cancellation, and a
   paused row is a durable user intent the reconciler never touches.

---

*See [`ANDROID_DATA_AND_STATE.md`](ANDROID_DATA_AND_STATE.md) for entities,
the full episode state machine, reconciliation rules, identity/naming rules and
the invariants tests must preserve.*
