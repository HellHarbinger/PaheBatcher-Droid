# PaheBatcher Android — AI/Developer Handoff

**Last verified: 2026-10-18** (source inspection + full JVM suite + live device journey on a
physical Android device under a secondary user).

This is the primary entry point for a new developer or AI model. It is entirely self-contained
and describes only the Android application. Read it first, then open the focused document your
question needs (section 3 below).

---

## 1. What PaheBatcher Android is

A production-grade native Android client (Kotlin, Jetpack Compose, package `com.pahebatcher.app`)
for the animepahe streaming site. It lets a user:

- search animepahe by title and open an anime's episode catalogue;
- browse episodes with lazy pagination (pages of 100, fetched on demand);
- select ranges of episodes (bounded to 100 per submission) or individual episodes;
- pick an audio variant per episode (e.g. `jpn`, `eng`) — each episode+audio pair is a distinct
  download identity;
- download episodes via HLS (bounded parallel segment fetch → per-segment retry → AES-128
  decryption → remux assembly → output verification → export to the shared MediaStore
  `Movies/<anime title>/` directory, or to a user-chosen SAF folder);
- track downloads with a persistent, honest UI (per-episode state, segments, size, percentage,
  failure reason, retry);
- play completed downloads in an integrated Media3 player;
- receive progress / completion / failure notifications (with the audio language labelled);
- maintain a watchlist of anime URLs with background new-episode checks.

All network access to animepahe goes through OkHttp with cookies and User-Agent obtained from a
headless WebView session that owns Cloudflare clearance. The app never fakes clearance, never
hard-codes a User-Agent, and never persists expiring stream URLs as authoritative state.

## 2. Current verified capabilities

Verification tiers used throughout the documentation:

- **VERIFIED (JVM)** — covered by host-side unit tests (109 suites / 1,189 tests, all green
  2026-10-18).
- **VERIFIED (instrumented)** — covered by on-device instrumented suites run via direct
  `am instrument` (not the Gradle connected task).
- **VERIFIED (device)** — observed end-to-end on a physical device (`REDACTED-SERIAL`, secondary user)
  during the 2026-10-18 release-gate journey (cold launch → search → details → pagination →
  range selection → single + batch download → notifications → failure → re-download policy →
  multi-audio export → MediaStore metadata → playback → force-stop survival → navigation
  restoration → watchlist/settings).

Highlights with live device evidence from that journey:

- downloads complete end-to-end (147-segment 1080p episodes in ~36 s) and export visible,
  non-pending MediaStore rows with audio-suffixed names under `Movies/<title>/`;
- duplicate/already-downloaded suppression works on both paths — row present (`outcome=
  AlreadyDownloaded`) and row absent but destination survives (`destination survives` probe);
- the documented re-download policy is **explicit rejection**: a surviving export suppresses a
  re-download; `Sessions → Delete` removes cache and app-local files but never touches exports;
  `Replace file` is the one explicit overwrite path (old file kept until the new one is verified,
  then retired);
- notifications carry the audio language (`… · Japanese — 42%`, `Download completed … · English`,
  failure `… · Segment download failed`);
- a deterministic network failure mid-download surfaces an honest row state
  (`failed — M3U8 fetch failed after 5 attempts — Retry`) plus a failure notification;
- process death (force-stop) + cold start reconstructs every row from Room;
- session refresh recovers silently from a stale Cloudflare cookie (logcat:
  `Cleared stale cf_clearance before forced re-establishment` → `Session established
  successfully` → `Session recovered by the background refresh`) with no prompt shown when the
  quiet refresh succeeds.

Not everything is device-verified; the catalog marks every feature's real evidence tier. Never
upgrade a claim beyond its tier.

## 3. Canonical documents — which document answers which question

| Question | Document |
|---|---|
| What are the layers, packages, DI, session/HLS/export architecture? | `ANDROID_ARCHITECTURE.md` |
| What are the flows, step by step, UI → persistence → notification? | `ANDROID_USER_FLOWS.md` |
| What does Room store, what is the state machine, what survives process death? | `ANDROID_DATA_AND_STATE.md` |
| How do I build, test, and test safely on a physical device? | `ANDROID_TESTING_AND_RELEASE.md` |
| What is feature X, where does it live, what is its verification status? | `ANDROID_FEATURE_CATALOG.md` |
| How do I keep docs honest (citations, evidence tiers)? | `ANDROID_TESTING_AND_RELEASE.md` § evidence & citation rules |

Project-management and audit documents (secondary to the six above; they track history, not
day-to-day answers):

- `ROADMAP.md` — phase-by-phase completion state and remaining work;
- `DECISIONS.md` — numbered architectural decisions with reasons and evidence;
- `LIMITATIONS.md` — genuine platform/Android limitations and unresolved defects;
- `PARITY.md` — historical audit record (Python → Android). Kept for audit integrity only; it is
  **not** required to understand or maintain the app.

## 4. Repository structure

```
<repo-root>\
├── pahebatcher\src\main\java\com\pahebatcher\app\
│   ├── MainActivity.kt                 # single-activity Compose host
│   ├── PaheBatcherApplication.kt       # manual DI composition root
│   ├── di\AppContainer.kt              # all long-lived dependencies (no Hilt)
│   ├── data\
│   │   ├── download\                   # DownloadRepository (admission gate), EnqueueOutcome
│   │   ├── hls\                        # playlist parse, SegmentFileStore, AES-128, assembly
│   │   ├── export\                     # EpisodeExporter, MediaStoreExporter, SafExporter,
│   │   │                               # ExportDestinationRouter, ExportFileNames, FileNameGenerator
│   │   ├── session\                    # AndroidCloudflareSession (WebView owns Cloudflare)
│   │   ├── local\                      # PaheDatabase v4 (Room), entities, DAOs, reconciler
│   │   ├── repository\                 # SessionActions, EpisodeRepository, WatchlistChecker
│   │   ├── playback\                   # PlaybackSourceResolver, position tracking
│   │   ├── stream\                     # StreamResolver (quality/audio variant resolution)
│   │   └── settings\                   # user preferences storage
│   ├── domain\
│   │   ├── download\                   # ReplacementOutcome and domain models
│   │   ├── session\                    # SessionRegistry (per-anime session identity)
│   │   └── util\                       # EpisodePaging (page/window/range arithmetic)
│   ├── ui\                             # Compose screens: home, details, downloads, sessions,
│   │                                   # sessionprompt, watchlist, settings, player, navigation
│   ├── notification\                   # DownloadNotificationManager, DownloadRowMapper
│   └── worker\                         # DownloadWorker (WorkManager), WatchlistCheckWorker
├── pahebatcher\src\test\                       # JVM unit tests (~109 suites)
├── pahebatcher\src\androidTest\                # on-device instrumented suites
├── scripts\check-doc-citations.ps1     # documentation citation validator
└── (docs listed in section 3)
```

Build facts (verify in `pahebatcher\build.gradle.kts` before relying on numbers): package
`com.pahebatcher.app`, minSdk 32, compileSdk/targetSdk 37, JDK 25, versionName `0.1.0`.

## 5. How to trace a user-visible feature

Take any UI action and follow this seam (example: tapping an episode row's `Download`):

1. **UI** — `ui/details/AnimeDetailsScreen.kt` renders the episode list; a row tap resolves the
   stream first (sheet) then calls the ViewModel action with `episode.audio` as the chosen
   variant.
2. **ViewModel** — `ui/details/AnimeDetailsViewModel.kt` assembles the submission (session id,
   episode number, audio, resolved stream, quality) and calls the repository.
3. **Repository** — `data/download/DownloadRepository.queueResolvedEpisode` is THE admission
   gate: duplicate suppression (existing healthy row), destination probe (does a valid export
   already exist?), then it creates/refreshes the session row and episode row and registers the
   Worker. It returns an `EnqueueOutcome` the UI maps to a message.
4. **Worker** — `worker/DownloadWorker.kt` runs the download (foreground, persistent
   notification) via `data/hls/EpisodeDownloader` (segments → decrypt → assemble → verify).
5. **Export** — `data/export/*` writes the finished file to MediaStore (two-phase pending →
   visible) or SAF, naming it `Ep_00N_-_<title>_[<audio>].mp4` under `Movies/<anime title>/`.
6. **Persistence** — every state change is written to Room first; the UI observes Room (or a
   flow derived from it) and is never authoritative.
7. **Notification** — `notification/DownloadNotificationManager` maps the row via
   `DownloadRowMapper` (which supplies the human audio label, e.g. `Japanese`).

For session/network behavior the analogous seam is: any repository call that hits the network →
`AndroidCloudflareSession` (cookies/UA from the WebView) → OkHttp. Cloudflare failures raise a
`SessionRefreshSignal` consumed by `ui/sessionprompt/SessionRefreshViewModel` (one quiet
attempt, then a user prompt only if that fails).

## 6. Core invariants (must not be broken)

1. **Room is the single source of truth for download state.** The UI may cache but never
   decide; state must survive process death.
2. **No fake anything.** No placeholder URLs, no invented progress, no fake notifications, no
   fake Cloudflare success, no hardcoded User-Agent.
3. **Every write is atomic.** Segment files and exports are written temp → verify → commit;
   an incomplete segment is never counted complete; a visible MediaStore row always has
   `is_pending=0` and real bytes.
4. **Exactly one visible export per episode+audio identity** after any export or replacement
   (MediaStore may uniquify the display name to `… (n).mp4`; the app tolerates that in its
   name matching).
5. **Episode identity is `<sessionId>_<episodeNumber>_<audio>`.** `…_2_jpn` and `…_2_eng` are
   different episodes. Never collapse audio variants.
6. **Bounded concurrency everywhere.** Episodes ≤ max parallel (settings, 1–6); segments per
   episode ≤ HLS workers (default 24). No unbounded coroutines/threads; no whole-episode-in-
   memory buffering.
7. **Exports are user data; cache is disposable.** Deleting a session deletes cache and
   app-local files only — never exports (the UI says so verbatim).
8. **The re-download policy is explicit rejection.** A surviving valid export suppresses a
   re-download with an honest message; the user's two explicit paths are `Replace file`
   (overwrite) or deleting the existing file.
9. **Secrets and tokens are never logged.** No cookies, no authorization headers, no stream
   URLs in logs or documentation.
10. **Cloudflare belongs to the WebView.** The native HTTP stack must not attempt to solve or
    bypass challenges; on a forced re-establishment the stale `cf_clearance` cookie is cleared
    first.
11. **Expiring URLs are never authoritative state.** Stream/playlist URLs are re-resolved on
    use; persisted pointers are the stable `outputUri`/identity fields only.
12. **Cancellation and failure are honest states.** `FAILED` rows persist a human reason and a
    Retry affordance; partial segment cache is kept for resume when applicable.

## 7. Build and test commands

Windows PowerShell (note: PowerShell 5.1 has no `&&`; use separate lines or `;`):

```powershell
cd "<repo-root>"
.\gradlew.bat assembleDebug                 # debug APK -> pahebatcher\build\outputs\apk\debug\
.\gradlew.bat testDebugUnitTest             # full JVM suite (add --rerun-tasks to force)
.\gradlew.bat lintDebug                     # must stay 0 errors
.\gradlew.bat assembleDebugAndroidTest      # instrumented test APK
```

Instrumented execution on a device (never via `connectedDebugAndroidTest` — it uninstalls and
wipes; see `ANDROID_TESTING_AND_RELEASE.md`):

```powershell
adb -s <serial> install -r --user <user> pahebatcher-arm64-v8a-debug.apk
adb -s <serial> install -r --user <user> pahebatcher-debug-androidTest.apk
adb -s <serial> shell am instrument -w -e class com.pahebatcher.app.data.export.MediaStoreExportTest `
    com.pahebatcher.app.test/androidx.test.runner.AndroidJUnitRunner
```

Run one instrumented class per invocation (comma-separated lists honor only the first class).
`#methodName` suffixes work for a single test method.

Documentation validation after ANY doc edit:

```powershell
powershell -File scripts\check-doc-citations.ps1
```

## 8. Safe physical-device testing (secondary user)

The development device has two users: the Owner (user 0, holds personal data — **never touch**)
and a secondary "Android Dev" user where the app is installed for testing.

Procedure:

1. `adb devices` — proceed only with a `device`-state serial; address it as `-s <serial>`
   always.
2. `adb shell pm list users` — locate the secondary user by label; record its id. Never hardcode
   the id in docs or scripts.
3. All operations must be scoped: `am start --user <id> …`, `pm … --user <id>`,
   `install -r --user <id>`, `content query --user <id> …`.
4. Storage caveat: adb shell file commands see the **Owner's** `/sdcard`; the secondary user's
   MediaStore must be queried through `content` with `--user <id>`.
5. Never: `uninstall`, `pm clear`, `-k` data wipes, deleting MediaStore rows as a shortcut,
   targeting user 0, or repeatedly triggering live Cloudflare challenges (each one is a real
   network event).
6. UI automation on this device: `input tap` is rejected — use
   `input motionevent DOWN x y` + `input motionevent UP x y` (~150–300 ms hold); wake with
   `KEYCODE_WAKEUP` then `wm dismiss-keyguard`; use `uiautomator dump` + `pull` for evidence.
7. Notification permission for the test user must be granted before observing notifications
   (`pm grant … android.permission.POST_NOTIFICATIONS`) and may be revoked afterwards to
   restore the prior state.

Full checklist and evidence rules: `ANDROID_TESTING_AND_RELEASE.md`.

## 9. Documentation and citation maintenance

- Every `file:line` citation in the four audit docs (`DECISIONS.md`, `ROADMAP.md`,
  `LIMITATIONS.md`, `PARITY.md`) must point at a real location. The repo ships a validator;
  run it after every doc edit and fix what it reports. Citations must remain valid after
  subsequent code edits — when line numbers drift, repoint the citation (do not delete the
  history around it).
- `DECISIONS.md` is append-mostly: new numbered decisions; historical entries are not
  rewritten, only corrected via dated addenda when they are factually superseded.
- `PARITY.md` is strictly additive: dated, labeled entries only. Never rewrite, reorder,
  downgrade or upgrade an existing row's evidence tier.
- Evidence language: "VERIFIED" requires a recorded artifact (test output, logcat line, UI
  dump). Everything else is "IMPLEMENTED", "NOT VERIFIED", "NOT RUN", or "NOT APPLICABLE".

## 10. Distinguishing verified behavior from assumptions

- A claim marked VERIFIED (device) has a timestamped journey/logcat/UI-dump artifact.
- A claim marked VERIFIED (JVM) is pinned by a test in `pahebatcher/src/test`.
- "Implemented" means the code exists and compiles; it does not mean the behavior was observed
  end-to-end. Check `ANDROID_FEATURE_CATALOG.md` for the per-feature tier before asserting a
  capability in any report.
- When a test's outcome depends on live service health (e.g. the session-refresh prompt dialog
  test, which only appears when the quiet background refresh fails), the honest status is
  NOT RUN/skipped on a healthy session — the prompt being suppressed **is** the designed
  behavior. Do not "fix" such a test by forcing failures against the live service.

## 11. Known limitations and unresolved defects

Authoritative list: `LIMITATIONS.md`. Highest-impact items for daily work:

- **SAF destination probe is conservative**: under a user-selected SAF folder the destination
  probe returns false (admit), so the "already downloaded via destination" suppression only
  covers the MediaStore route. `Replace file` remains safe in both routes.
- **MediaStore ` (n)` uniquification**: after `Replace file` (which keeps the old file until
  the new one is verified) the new row may be displayed as `… (1).mp4`. This is accepted
  (§30.2-tolerated) behavior, not data loss; the old row is retired.
- **Uninstall-orphaned exports**: uninstalling the app clears `OWNER_PACKAGE_NAME` on its
  MediaStore rows; after reinstall, prior exports are invisible to the app's owner-scoped
  queries, so re-downloading produces a uniquified second file instead of suppression. The
  owner scoping is deliberate (a foreign file must never suppress a download).
- **`Latest N` on a large paged title** selects the newest among loaded rows, not the whole
  catalogue (paging-architecture behavior, pre-existing).
- **Session-refresh prompt dialog test** depends on live service failure; on a healthy session
  the prompt is suppressed by design and the dialog test cannot be exercised.

## 12. Completed phases and remaining roadmap

All application phases through the current release gate are complete; see `ROADMAP.md` for the
authoritative phase table and the remaining checkboxes (each is a known, documented limitation
or an explicitly deferred item — no unfinished phase work hides there). The current release
gate (final device-verified journey, notification labels, session recovery, pagination/batch
bounds, re-download policy) is recorded in `ROADMAP.md` and `DECISIONS.md` 157.

When continuing development:

1. Read the relevant focused document from section 3.
2. Reproduce current behavior first (tests or device) before changing it.
3. Add or extend a regression test for every behavior change.
4. Build (`assembleDebug`), run the affected suites, then the full JVM suite before finishing.
5. Update the four audit docs (append-only rules in section 9) and re-run the citation checker.
6. Never trade a smaller verified change for a larger unverified one.
