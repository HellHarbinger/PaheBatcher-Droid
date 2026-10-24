# PaheBatcher Android — Data & State

**Last verified: 2026-10-18 (source inspection)**

Package: `com.pahebatcher.app` — persistence root: `pahebatcher/src/main/java/com/pahebatcher/app/data/local/`

This document describes every piece of persistent and reconstructed state in the
app: Room schema and relationships, the episode download state machine, export
identity, settings, WorkManager persistence, reconciliation, and the invariants
tests must preserve. Runtime architecture is in
[`ANDROID_ARCHITECTURE.md`](ANDROID_ARCHITECTURE.md).

Companion handoff documents:
[`ANDROID_USER_FLOWS.md`](ANDROID_USER_FLOWS.md) ·
[`ANDROID_TESTING_AND_RELEASE.md`](ANDROID_TESTING_AND_RELEASE.md) ·
[`ANDROID_FEATURE_CATALOG.md`](ANDROID_FEATURE_CATALOG.md) ·
[`ANDROID_AI_HANDOFF.md`](ANDROID_AI_HANDOFF.md)

---

## 1. Persistence overview

| Store | What lives there | Survives |
|---|---|---|
| **Room** (`pahebatcher.db`, `PaheDatabase`, **version 4**, `exportSchema = true`) | download sessions, episode downloads, watchlist, scan cache, playback positions | process death, restart, reboot |
| **WorkManager** | one `OneTimeWorkRequest` per episode (`episode_download_<episodeId>`), the periodic watchlist request (`watchlist_periodic_check`) | process death, restart |
| **DataStore** (`pahebatcher_settings`, `AppConfig`) | all settings (see §10) | process death, restart |
| **App-private filesystem** (`filesDir/downloads/<episodeId>/`) | segment cache, staged/replacement files, pre-export assembled MP4 (`outputPath`) | until cleaned by export/cleanup/delete actions |
| **MediaStore / SAF tree** | exported episode videos (`outputUri`) | app uninstall (MediaStore rows become ownerless — see §8) |

No destructive migrations exist; no `fallbackToDestructiveMigration`.

---

## 2. Room schema (`data/local/`)

### 2.1 Entities

| Entity (table) | PK | Key columns | Notes |
|---|---|---|---|
| `DownloadSessionEntity` (`download_sessions`) | `sessionId` | `title`, `animeSession`, `host`, `quality`, `audioLang`, `outputDir`, `cacheDir`, `createdAt`, `updatedAt`, `state` | States: `CREATED`, `DOWNLOADING`, `PAUSED`, `COMPLETED`, `FAILED` (companion constants). `title` is the single persisted title source for the export directory (Gate A). |
| `EpisodeDownloadEntity` (`episode_downloads`) | `episodeId` | `sessionId` (FK), `episodeNumber: Float`, `episodeSession`, `title`, `fansub`, `audio`, `playUrl`, `totalSegments`, `completedSegments`, `downloadedBytes`, `state`, `speed`, `etaSeconds`, `retryCount`, `errorMessage`, `outputPath`, `m3u8Url`, `userAgent`, `referer`, `segmentDir`, `outputUri`, `replaceRequested`, `replacePriorUri`, `createdAt`, `updatedAt` | Full state machine in §4. `playUrl` is the durable re-resolution source; `m3u8Url`/`userAgent`/`referer` are the resolved-stream hand-off (cleared by `beginRetry`). |
| `WatchlistEntity` (`watchlist`) | `sessionId` | `url`, `animeSession`, `host`, `title`, `quality`, `audioLang`, `outputDir`, `maxParallel`, `hlsWorkers`, `keepTemp`, `autoRetry`, `addedAt`, `lastCheckedAt`, `downloadedEpisodesJson` | `audioLang = "any"` (`AUDIO_ANY`) for new entries; concrete codes are used strictly. `isStale(cacheTtlMinutes)` compares `lastCheckedAt`. |
| `ScanCacheEntity` (`scan_cache`) | `session` | `title`, `host`, `totalEpisodes`, `episodesJson`, `cachedAt`, `cacheTtlMinutes` (default 60) | `isExpired()` = now > `cachedAt + ttl`. See §9. |
| `PlaybackPositionEntity` (`playback_position`) | `episodeId` | `positionMs`, `durationMs`, `updatedAt` | No FK (deliberate, additive v3 migration); no TTL, no eviction in v1. |

A `SegmentEntity` existed in an earlier schema version and was **removed** — the
segment count is derived from the filesystem (`SegmentFileStore`), never from Room.

### 2.2 Relationships & cascades

- `episode_downloads.sessionId` → `download_sessions.sessionId` with
  **`ON DELETE CASCADE`** (Room, `PRAGMA foreign_keys = ON`): deleting a session
  row deletes its episode rows (`SessionActions.prepareSessionDelete` removes
  filesystem artifacts first, then the session row).
- Because of the cascade, `DownloadSessionDao.upsert` (`INSERT OR REPLACE`) is
  deliberately **never** used for the parent row — a replace would delete and
  re-insert the parent, firing the cascade onto live episode rows.
  `DownloadRepository.ensureSession` uses `insertIfAbsent` (`INSERT OR IGNORE`)
  then in-place `update`.
- `watchlist` and `playback_position` have **no foreign keys** (independent keys).

### 2.3 The episode key — `episodeId` (`domain/download/EpisodeId.kt`)

Format: **`{animeSession}_{episodeString}_{audio}`** — exactly three `_`-separated
parts, built by `EpisodeId.build(animeSession, episode)` (the production
`DownloadRepository.episodeDownloadId` is a pure delegation to it).

Shape gate (`EpisodeId.parse` rejects anything else — fail locally, never hit the
network with an ununderstood id):

- `animeSession`: non-blank, no whitespace;
- `episodeString`: `\d+(?:\.\d+)?` — e.g. `1`, `13`, `13.5`;
- `audio`: `[a-z0-9]{1,16}` — open-set lowercase code (`jpn`, `eng`, `kor`, …).

**Uniqueness semantics**: unique per (anime, episode number, audio) — the SUB and
DUB tracks of one episode are separate rows and separate files. The id is
**immutable across retries**: `beginRetry`/`beginReplacement` mutate columns of
the existing row and never re-key it, and `reEnqueue` refuses (before any
mutation) a row whose persisted `episodeId` does not match its own columns
reconstructed through `EpisodeId.build`. (Device-verified 2026-10-18: rows for the
same episode exist as `…_2_jpn` and `…_2_eng` side by side.)

### 2.4 Episode/audio identity

- `episodeNumber` is a **Float** because half-episode specials exist in the
  catalog (`13.5`); `EpisodeInfo.epStr` emits integer or one-decimal strings and
  `FileNameGenerator.formatEpisodePrefix` renders `%03d` for whole numbers and
  `%05.1f` otherwise (`Ep_013.5_…`).
- Audio is an **open-set** lowercase code persisted per row; display labels are
  derived (`jpn → "Japanese"`, `eng → "English"`, else uppercased —
  `DownloadRowMapper.audioLabelFor`). There is no global audio setting.

---

## 3. Segment identity, naming and resume (`data/hls/SegmentFileStore.kt`)

- Segment files are named **`%06d.ts`** (`segPath(idx)`), where `idx` is the
  playlist's `EXT-X-MEDIA-SEQUENCE` index (`HlsSegment.index`) — not an assumed
  0-based range; the assembly boundary receives the playlist's own indices.
- `doneIndices()` counts only files matching exactly `??????.ts` (name length 9).
  A **`.tmp` file is never a completed segment**.
- **Resume**: at attempt start, `doneIndices()` splits the parsed playlist into
  completed/pending — the log line reads
  `Playlist: <N> segments, <C> completed, <P> pending`. The byte resume base is
  `completedSegmentBytes()` (one directory walk over completed `.ts` files only).
  (Device-verified 2026-10-18: a resumed run reported 147 total, 0 completed,
  147 pending on a fresh directory, and skipped existing segments on retries.)
- **Atomic writes**: `writeSeg` writes `%06d.tmp` then renames to `%06d.ts`
  (delete-and-retry fallback; failure deletes the `.tmp` and throws). An
  interrupted write leaves only `.tmp`, which `cleanupOrphans()` removes at the
  next attempt start. Downloaded-byte accounting counts a segment **only after**
  the atomic write succeeded.
- **Cleanup semantics**: `cleanup()` removes `.ts`/`.tmp`/`concat.txt` but never a
  `.mp4` in the directory (the assembled output lives inside the segment dir).
  `isTemporaryArtifact` defines the cache set for size display and cache clearing
  (`.ts`, `.tmp`, `concat.txt` — never the `.mp4`, never exports).
- **`keepTemp`** (default `false`): after a successful assembly the segment store
  is cleaned; a failed run keeps its completed segments for resume.
  (Device-verified 2026-10-18: completed episode → cache 0.0 B; failed episode →
  partial cache retained, e.g. 3.3 MB.)

---

## 4. Episode download state machine (`EpisodeDownloadEntity`)

States (companion constants): `QUEUED`, `RESOLVING`, `DOWNLOADING`, `ASSEMBLING`,
`COMPLETED`, `FAILED`, `CANCELLED`, `PAUSED`.
Terminal (`isTerminal()`): `COMPLETED`, `FAILED`, `CANCELLED`.
Pausable (`PAUSABLE_STATES`): `QUEUED`, `RESOLVING`, `DOWNLOADING` — never
`ASSEMBLING` (muxing cannot be interrupted safely).
Resumable (`canResume()`): `FAILED`, `QUEUED`, `PAUSED`.

**All writes are compare-and-set.** The guarded DAO statements:
`updateStateForResume(expectedState → newState)`, `updateErrorIfState`,
`updateCompletedIfState`, `cancelIfNotTerminal`, `claimTerminalRequeue`,
`beginRetry`, `beginReplacement`, `finishReplacement`,
`restoreCompletedForUncommittedReplacement`.

| From | To | Actor / statement |
|---|---|---|
| — → `RESOLVING` | row created (`insertIfAbsent`, straight into RESOLVING) | `DownloadRepository.createRow` |
| `FAILED`/`CANCELLED`/`COMPLETED`-invalid → `RESOLVING` | `claimTerminalRequeue` (resets submission columns) or `beginRetry` (retryCount+1, clears m3u8Url/userAgent/referer/errorMessage/outputUri) or `beginReplacement` (keeps outputUri/outputPath, sets replaceRequested/replacePriorUri) | submission / Retry / Replace file |
| `PAUSED` → `RESOLVING` | `beginRetry` (resume-without-stream path) | `DownloadRepository.resume` → `reEnqueue` |
| `RESOLVING` → `QUEUED` | `saveStreamInfo` then CAS; also `QUEUED → QUEUED` for zombie repair | `DownloadRepository.resolveComplete` |
| `PAUSED` → `QUEUED` | CAS + unique work re-attached | `DownloadRepository.resume` |
| `RESOLVING` → `QUEUED` | interrupted-resolution reset + `clearStreamInfo` | `DownloadStateReconciler` |
| `DOWNLOADING` → `QUEUED` | no live WorkManager work found; or auto-resume park (TRANSPORT + setting ON + attempts < 7 → `Result.retry()`) | reconciler / `DownloadWorker.parkForAutoResume` |
| `QUEUED`/`DOWNLOADING`/`ASSEMBLING` → `DOWNLOADING` | `claimEntryState` (Worker entry; also clears speed/eta) | `DownloadWorker` |
| `QUEUED`/`RESOLVING`/`DOWNLOADING` → `PAUSED` | CAS persisted **first**, then unique work cancelled | `DownloadRepository.pause` |
| `DOWNLOADING` → `ASSEMBLING` | CAS at remux start (the only production writer) | `DownloadWorker` via `onAssemblyStart` |
| `DOWNLOADING`/`ASSEMBLING` → `COMPLETED` | `updateCompletedIfState(outputPath)` / `finishReplacement` (replacement commits its file **before** this) | `DownloadWorker` success |
| `ASSEMBLING` → `COMPLETED` | expected output found on disk | `DownloadStateReconciler` |
| `COMPLETED` → (stays `COMPLETED`, `outputUri` re-pointed) | severed `outputUri` over a SURVIVING export (destination probe finds it — URI churn): `repairOutputUriIfState` CAS instead of demotion | reconciler (DECISIONS 157.1) |
| `COMPLETED` → `FAILED` ("file missing") | output fails §29 verification on a fresh re-read **and** the destination probe finds no surviving export (CAS on COMPLETED) | reconciler |
| `DOWNLOADING`/`ASSEMBLING` → `FAILED` + reason | `updateErrorIfState` (every Worker failure path; unexpected-exception guard included) | `DownloadWorker` |
| `RESOLVING` → `FAILED` + reason | `updateErrorIfState(RESOLVING)` on re-resolution failure | `DownloadRepository.reEnqueue` |
| any non-terminal → `CANCELLED` | `cancelIfNotTerminal` (terminal-guarded; clears speed/eta) | `DownloadRepository.cancel` |
| any state → terminal/other | **refused** — a newer state always wins; the caller re-derives the truth (`EnqueueOutcome`/`false`) | all CAS sites |

**Replacement overlay**: a confirmed `Replace file` sets `replaceRequested = 1`
and `replacePriorUri`; every failure/cancel path for such a row first tries
`restoreCompletedForUncommittedReplacement` (→ `COMPLETED`, export untouched) and
only writes `FAILED`/`CANCELLED` when the previous export no longer verifies
(clearing `replaceRequested` so the flag never outlives its run).

**Admission mapping** (`EnqueueOutcome`, produced only by
`queueResolvedEpisode`): see the table in ANDROID_ARCHITECTURE §8.1 — no row →
`Accepted`; terminal → `Accepted`; COMPLETED+present output (or surviving
destination) → `AlreadyDownloaded`; COMPLETED+missing output → `OutputMissing`;
`PAUSED` → `AlreadyPaused`; owned active/queued-with-stream → `AlreadyActive`;
lost hand-off with terminal/absent row → `Refused`. A suppressed submission
writes **nothing** (no session row, no resolver round-trip).

---

## 5. Reconciliation after process death (`DownloadStateReconciler`)

Runs once per process start (`PaheBatcherApplication`, `AtomicBoolean` guard),
idempotent. Candidates: `getEpisodesForReconciliation()` (all rows except
`FAILED`/`CANCELLED`). Rules per state:

| State found | Action |
|---|---|
| `COMPLETED` | Verify output under the shared §29 rule (`EpisodeOutputVerifier`: URI resolves ∧ size > 0, else outputPath exists ∧ size > 0). Valid → one idempotent export attempt (log-only outcome) + segment-artifact cleanup. Invalid → fresh re-read, then CAS to `FAILED` with reason `"file missing"`. |
| `FAILED` / `CANCELLED` | Terminal — no action. |
| `PAUSED` | Deliberate user intent — never re-enqueued, never reset, never auto-resumed. |
| `ASSEMBLING` | Expected output exists in the segment dir → `COMPLETED`; else re-enqueue the Worker (state unchanged — must stay claimable). |
| `DOWNLOADING` | Unique work still RUNNING/ENQUEUED → leave alone; otherwise CAS `DOWNLOADING → QUEUED` and re-enqueue. |
| `RESOLVING` | Interrupted resolution → `QUEUED` + `clearStreamInfo` (stream-less zombie; repaired only by a foreground submission). |
| `QUEUED` with `m3u8Url` | Resolved but never handed off → enqueue Worker. Without `m3u8Url` → awaiting foreground resolution, no action. |

Plus a **stale-pending sweep** (`sweepStalePending`) after the loop: MediaStore
`IS_PENDING=1` rows older than the process-start instant and unreferenced in Room
are deleted (orphans of a crashed previous process; this run's rows postdate the
cutoff). Re-enqueues build **new** WorkRequests with the current Wi-Fi-only /
auto-resume settings.

**Instrumented coverage** (`pahebatcher/src/androidTest/.../DownloadStateReconcilerTest.kt`,
**17 cases**, device-executed): empty database no-op; RESOLVING→QUEUED; COMPLETED
valid stays COMPLETED; output-inside-segment-dir survives + segments cleaned;
missing output → FAILED; empty path → FAILED; FAILED no-op; CANCELLED no-op;
PAUSED never restarted/reset/auto-resumed; QUEUED without m3u8 no-op; idempotency;
exported valid URI stays COMPLETED and cleans the local duplicate; user-deleted
export → FAILED "file missing"; deleted SAF export → FAILED; revoked SAF grant →
FAILED; URI without resolver falls back to the outputPath rule; granting or
switching a SAF tree never resurrects a FAILED row.

---

## 6. Output identity and overwrite

- **Expected export name**: `ExportFileNames.exportedName(prefix, title, audio)` →
  `Ep_001_-_Title_[jpn].mp4` (pure function of the row's own metadata; export
  order cannot affect it). The unsuffixed `legacyName` is a reuse-only alias —
  legacy files are size-verified-and-adopted, never deleted, renamed or migrated.
- **Destination directory**: `OutputPathNames.mediaStoreRelativePath(animeTitle,
  sessionId)` → `Movies/<sanitized title>/` with the title from the parent
  session row (fallback chain: title → sessionId → `"PaheBatcher"`).
- **MediaStore two-phase write**: deterministic name lookup → insert with
  `IS_PENDING=1` (invisible) → 64 KiB stream copy → byte-count verify → persist
  `outputUri` in Room (X4) → clear `IS_PENDING` (X5, `is_pending=0`) → delete the
  local MP4 (X6). The local file is never deleted before byte-verified
  persistence; any failure deletes the pending row and keeps the local file.
- **Exact-name stale-row replacement** (§36-7 / §30.2): an EXACT audio-suffixed
  row with a size mismatch in this episode's directory is this episode's crash
  residue or superseded copy → deleted before the fresh copy. Verification is by
  **size only**, never byte comparison. A MediaStore `stem (n).mp4` uniquified
  name is matched by `nameMatches` and treated as this episode's candidate;
  a **visible** ` (n)` row is left untouched (only pending ` (n)` residue is
  removed), and a row referenced by another Room row is never adopted or deleted.
- **Replace file flow** (keep-old-until-new-verified): `confirmReplacement`
  classifies (COMPLETED + verified output + playUrl present; idempotent repeats →
  `AlreadyInFlight`), claims via `beginReplacement` (prior output references are
  **kept**), the Worker stages everything under `<segmentDir>/replace/`, commits
  with a single same-filesystem `rename` onto the previous output's path
  (validation first; a refusal leaves the old file byte-for-byte intact), only
  then CASes the completion; the export pass then runs and
  `PriorOutputRetirement` deletes `replacePriorUri` (MediaStore delete or SAF
  `deleteDocument`, cross-reference guard, reference kept on failure for retry).
  (Device-verified 2026-10-18: the replacement left exactly one visible row with
  a byte-identical size; when MediaStore uniquified the new row it was named
  `… (1).mp4` — accepted, §30.2-tolerated behavior.)

---

## 7. Cache and TTL semantics

- **Scan cache** (`scan_cache` + `ScanCacheRepository`): keyed by anime session;
  stores the episode listing as JSON with `cachedAt` + per-row `cacheTtlMinutes`
  (default 60; configurable via Settings' "scan cache TTL" — `cache_ttl`).
  `readListing` treats `cacheTtlMinutes <= 0` as no cache; expiry is
  `now > cachedAt + ttl·60·1000`. Lazy (paginated) listings are stored through
  `saveLazyListing`; audio-probe results persist through `saveAudioProbe`
  **without** resetting `cachedAt`/TTL. `deleteExpired`/`clearAll` prune.
- **Watchlist staleness**: `WatchlistEntity.isStale(cacheTtlMinutes)` compares
  `lastCheckedAt`, but the check itself always forces a fresh scan; every finished
  entry persists its own `lastCheckedAt`, so a partial run keeps its progress.
- **AES key cache** (`AesKeyCache`): process-wide, keyed by key URL; never
  persisted.

---

## 8. MediaStore identity and metadata

- Per-anime directory `Movies/<sanitized title>/` under the shared `Movies/`
  prefix; legacy flat `Movies/PaheBatcher/` stays readable/recoverable but
  receives no new exports.
- Complete rows are `IS_PENDING = 0` when visible; MIME `video/mp4`.
- Queries filter **`OWNER_PACKAGE_NAME = BuildConfig.APPLICATION_ID`** and include
  pending rows (Bundle query with `QUERY_ARG_MATCH_PENDING`) — so: another app's
  same-name file is invisible to reuse, replacement and the destination probe and
  can never suppress a download; and rows orphaned by uninstalling the package
  (owner becomes NULL) are likewise invisible to the probe.
  (Device-verified 2026-10-18: rows orphan to owner=NULL after uninstall; the
  destination probe deliberately ignores foreign/orphaned rows.)
- Room is the index of truth for URIs: `getAllOutputUris()` protects referenced
  URIs from the sweep; `otherReferenceAudios()` implements the cross-episode
  reference guard.
- **SAF export** has no pending state in the provider: staging uses a
  `.pb.part` child (MIME `application/octet-stream`, invisible to galleries),
  renamed to the final name only after write; SAF never overwrites (uniquifies
  with ` (n)`), and rename-less providers fall back to MediaStore.

---

## 9. What survives vs. what is disposable

| Survives process death / restart | Intentionally disposable |
|---|---|
| All Room rows (sessions, episodes, watchlist, scan cache, playback positions) | Segment cache (`.ts`) and `.tmp` files after a successful assembly when `keepTemp=false` |
| WorkManager work specs (episode unique work, periodic watchlist request) — reconciler re-adopts non-terminal rows | `concat.txt` and staging `replace/` directories after commit |
| Exported files in MediaStore/SAF and their Room `outputUri` references | The local app-private MP4 after byte-verified export (X6) |
| All settings (DataStore) | In-flight resolved stream credentials after `beginRetry` (cleared before fresh resolution) |
| `replaceRequested`/`replacePriorUri` intent columns (a restarted run continues or restores correctly) | The session-refresh prompt (Activity-scoped; deliberately not persisted) |

---

## 10. Persistent settings (DataStore `pahebatcher_settings`, `AppConfig`)

| Key | Type | Default | Consumer |
|---|---|---|---|
| `quality` | Int | 1080 (valid: 360/720/1080) | stream resolution |
| `max_parallel` | Int | 2 (1–6) | Worker gate, batch staging capacity |
| `hls_workers` | Int | 24 (8–32) | per-episode segment semaphore + per-host limiter |
| `cache_ttl` | Int | 60 (min) | scan cache TTL |
| `auto_retry` | Int | 2 (0–2) | episode attempt budget (attempts = value+1) |
| `keep_temp` | Bool | false | segment retention after mux |
| `wifi_only` | Bool | false | enqueue-time `UNMETERED` constraint |
| `auto_resume` | Bool | false | TRANSPORT-only `Result.retry()` ≤ 7 runs |
| `notify_completion` / `notify_failure` / `notify_watchlist` | Bool | true | terminal + watchlist notification gates (read fresh at post time) |
| `watchlist_check_interval_minutes` | Int | 15 (options 15/30/60/240/720) | periodic work interval |
| `theme_mode` / `dynamic_color` | String/Bool | dark / false | theming |
| `notification_permission_asked_once` / `notification_permission_granted` | Bool | false / n.a. | POST_NOTIFICATIONS latch |
| `output_tree_uri` / `output_tree_rename_ok` | String/Bool | unset / true | SAF destination (fallback to MediaStore when unusable) |
| `show_console` | Bool | false | in-memory log buffer + Console route |
| `output_dir`, `cookie_string` | String | — | legacy keys; no production consumer |

---

## 11. Migrations (`PaheDatabase.kt`)

| Migration | Changes |
|---|---|
| **1 → 2** | Drops the unused `segments` table; adds nullable `m3u8Url`, `userAgent`, `referer`, `segmentDir` to `episode_downloads` (background-download support). |
| **2 → 3** | Adds nullable `outputUri` to `episode_downloads` (MediaStore export reference; `outputPath` retained as the local/pre-export source of truth); creates the `playback_position` table. |
| **3 → 4** | Adds `replaceRequested INTEGER NOT NULL DEFAULT 0` and `replacePriorUri TEXT` to `episode_downloads` (Replace-file intent + prior-export reference). Existing rows read as "no replacement". |

All additive; explicit migrations only (no destructive fallback).

---

## 12. Cancellation and failure semantics

- **CANCELLED is terminal**: `cancel()` persists it from the caller's scope
  **before** cancelling the unique work (a cancelled Worker coroutine cannot
  reliably write), via the terminal-guarded `cancelIfNotTerminal` — a Cancel racing
  a completion write can never stamp over `COMPLETED` or bury a `FAILED` reason.
  Cancel on a `PAUSED` row discards the pause.
- **PAUSED is durable intent**: written only by `pause()` (CAS first, work
  cancelled second, so the worker's own cleanup CAS loses); only `resume()`,
  Cancel, or Retry may leave it; the reconciler never touches it.
- **FAILED persists a reason string** (`errorMessage`), written only via
  CAS-guarded `updateErrorIfState` from a state the writer owns; message
  sanitization prevents URL leakage. The Downloads screen renders the reason and
  offers **Retry** (FAILED/CANCELLED rows only per the UI action matrix).
- **Worker retry vs. terminal**: only TRANSPORT + `auto_resume` ON + attempt
  budget → `Result.retry()` (row parked to QUEUED; exponential 30 s backoff).
  Everything else is a terminal FAILED with a typed category
  (`FailureCategory` from `DownloadErrorClass`: TRANSPORT, EXPIRED, NO_CONTENT,
  ASSEMBLY, DISK_FULL, IN_RUN_RETRYABLE).
- **A replacement that fails keeps its export** and restores COMPLETED; the
  notification says the replacement failed and the existing file was kept.

---

## 13. Invariants tests must preserve

1. **Exactly one visible MediaStore row per (episode + audio)** after any export,
   crash-recovery or replacement — an exact audio-suffixed name replaces only
   this episode's own stale copy; other audio variants (different names) and other
   apps' files are untouchable.
2. **An incomplete segment is never counted completed** — only `??????.ts` files
   count; `.tmp` residue never counts and is swept at the next attempt.
3. **`episodeId` is immutable across retries/replacements** — claims mutate
   columns of the existing row; a non-conforming id refuses re-enqueue before any
   mutation.
4. **A suppressed submission writes nothing** — no session row, no episode row,
   no resolver round-trip, no Worker when the gate returns a suppression outcome.
5. **The local MP4 is never deleted before byte-verified persistence** — X4 (URI
   in Room) precedes X5 (visible) precedes X6 (delete local); failures keep the
   local file as source of truth.
6. **Every state write is a CAS; a newer state always wins** — no actor can
   stamp COMPLETED/FAILED/CANCELLED over a state another actor owns.
7. **PAUSED survives the Worker's own cancellation and startup reconciliation**
   — nothing auto-resumes it; the reconciler writes nothing for it.
8. **A cancelled or failed replacement restores COMPLETED while its export
   verifies** — the row is never stamped FAILED for a file that still exists;
   `replaceRequested` never outlives its run.
9. **No visible-but-unknown MediaStore row** — at every instant the row is
   `IS_PENDING=1` or Room already records its `outputUri`.
10. **`Accepted` (EnqueueOutcome) implies a real Worker hand-off** — the CAS
    verdict is propagated; a lost hand-off reports `Refused`/the row's true state,
    never success.
11. **The destination probe can only be suppressed by this episode's own
    non-pending, non-empty, app-owned file** — foreign/orphaned/pending rows never
    suppress a download.
12. **Stream credentials are never authoritative identity** — `beginRetry` clears
    `m3u8Url`/`userAgent`/`referer` before any fresh resolution; `playUrl` is the
    only durable re-resolution source.

*See [`ANDROID_ARCHITECTURE.md`](ANDROID_ARCHITECTURE.md) for the runtime
architecture, pipeline and DI composition that produce this state.*
