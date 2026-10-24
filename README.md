# PaheBatcher for Android

A native Android application that reproduces the behavior of the desktop
[PaheBatcher](https://github.com/smolfiddle/pahebatcher) reference implementation:
search an anime library, resolve episode streams, batch-download episodes as HLS
segments, assemble them into MP4 files, export them to your device storage, and play
them back — with a modern, Android-native UI.

**Current status:** version **0.1.0** (versionCode 1) · `minSdk 32` (Android 12L+),
`targetSdk 37` · **pre-release — not published on Google Play or anywhere else yet.**
This repository is being prepared for its first human-reviewed push. A pre-push audit,
a Play-policy scan and a release-artifact inspection were run on 2026-10-24; their
evidence lives in [`PRE_PUSH_CHECKLIST.md`](PRE_PUSH_CHECKLIST.md) and
[`PLAY_POLICY_FINDINGS.md`](PLAY_POLICY_FINDINGS.md).

> **No affiliation, no Play approval.** This project is an independent reimplementation
> of a third-party desktop tool. Nothing here claims Google Play policy compliance or
> store approval; the policy scan in this repository is advisory only.

---

## Acknowledgments

A huge thank-you to [SmolFiddle](https://github.com/smolfiddle) and the original
[PaheBatcher project](https://github.com/smolfiddle/pahebatcher). Their work and
guidance helped this Android project at multiple stages of development, and their
contributions were especially significant to the design of its logo. I'm deeply
grateful for their help and for the foundation and inspiration they provided. The
logo will be updated in a future revision.

---

## Table of contents

- [Project status](#project-status)
- [Acknowledgments](#acknowledgments)
- [Features](#features)
- [Documentation map](#documentation-map)
- [Installing and using the release APK](#installing-and-using-the-release-apk)
- [Building from source](#building-from-source)
- [Architecture](#architecture)
- [How the main flows work](#how-the-main-flows-work)
- [Debug vs release builds](#debug-vs-release-builds)
- [GitHub Actions artifacts](#github-actions-artifacts)
- [Known limitations](#known-limitations)
- [Testing](#testing)
- [Security and privacy](#security-and-privacy)
- [Contributing and issue reports](#contributing-and-issue-reports)
- [License](#license)

---

## Project status

| Area | State |
|---|---|
| Core loop (search → details → resolve → download → assemble → export → play) | **Implemented and device-verified** — see [`ANDROID_FEATURE_CATALOG.md`](ANDROID_FEATURE_CATALOG.md) for the per-feature verification tiers |
| Direct HLS streaming playback (Phase 17) | **Implemented and device-verified** |
| Watchlist, background episode checks, notifications | **Implemented** |
| Settings surface (appearance, download engine, storage, notifications, diagnostics) | **Implemented** |
| Google Play listing | **Unavailable** — the app is not published; the policy scan returns HTTP 404 for the package, as expected |
| Play Console declarations (Data Safety, foreground-service types) | **Externally blocked** — can only be completed by the maintainer in Play Console; see [`PLAY_POLICY_FINDINGS.md`](PLAY_POLICY_FINDINGS.md) |
| CI-signed release artifacts | **Configured** — merged PRs build signed, per-ABI release APKs + AAB in CI; signing uses the maintainer's release identity stored as repository secrets, and every artifact is signature- and ABI-verified before upload (see [GitHub Actions artifacts](#github-actions-artifacts)) |
| Open-source license | **GPL-3.0-or-later** since 2026-10-24 - see [License](#license) |

Per-phase completion history (phases 1–17.7 plus later audit/closure rounds) is
recorded in [`ROADMAP.md`](ROADMAP.md); feature-by-feature Python↔Android parity is in
[`PARITY.md`](PARITY.md).

---

## Features

**Verified as reachable in the shipped UI** (every claim below corresponds to a
registered navigation destination or a wired control in
`pahebatcher/src/main/java/com/pahebatcher/app/ui/navigation/AppNavHost.kt`):

- **Search** — debounced search field with IME search action, offline banner,
  results list.
- **Anime details** — paged/lazy episode list, per-episode audio selector,
  stream-resolution sheet, single and **batch downloads** (whole page, all, or a
  selected range).
- **Downloads tab** — queue with live progress, retry, pause/resume, sessions
  screen, play button for completed episodes.
- **Playback** — local playback of downloaded MP4s *and* direct HLS streaming,
  with Media3 media session, media notification, next/previous, subtitles.
- **Watchlist** — add titles, background checks for new episodes, aggregate
  notification.
- **Settings** — theme/dynamic color, quality (360/720/1080), parallel-download
  and HLS-segment worker limits, scan-cache TTL, keep-temp-files, auto-retry,
  resolve-ahead, Wi-Fi-only, auto-resume, output folder (SAF), notification
  toggles, background-check frequency, diagnostics console.
- **Session handling** — WebView/Cloudflare session recovery with an explicit
  "refresh session" prompt when a quiet retry fails.

**Partial or limited** (details in [`LIMITATIONS.md`](LIMITATIONS.md)):

- In-player audio/quality switching is **not** implemented (switch from the
  details screen instead).
- Some Android-vs-desktop differences remain documented as intentional
  (`ANDROID_REQUIRED_DIFFERENCE` entries in [`PARITY.md`](PARITY.md)).

**Not provided:** no account system, no ads, no analytics, no in-app updater, no
Play Store distribution.

---

## Documentation map

| Document | What it holds |
|---|---|
| [`SPEC.md`](SPEC.md) | Product & engineering specification — the source of truth for required behavior |
| [`ROADMAP.md`](ROADMAP.md) | Phase-by-phase completion history and exit-criteria evidence |
| [`PARITY.md`](PARITY.md) | Feature-by-feature Python → Android parity checklist with verification status |
| [`DECISIONS.md`](DECISIONS.md) | Numbered architectural decision log with rationale |
| [`LIMITATIONS.md`](LIMITATIONS.md) | Platform limitations, Android workarounds, residuals (53 numbered entries) |
| [`ANDROID_ARCHITECTURE.md`](ANDROID_ARCHITECTURE.md) | Layered architecture, packages, constraints |
| [`ANDROID_USER_FLOWS.md`](ANDROID_USER_FLOWS.md) | End-to-end user flows as implemented |
| [`ANDROID_FEATURE_CATALOG.md`](ANDROID_FEATURE_CATALOG.md) | Feature inventory with verification tiers (JVM / instrumented / live device) |
| [`ANDROID_TESTING_AND_RELEASE.md`](ANDROID_TESTING_AND_RELEASE.md) | Test locations, commands, release rules |
| [`PLAY_POLICY_FINDINGS.md`](PLAY_POLICY_FINDINGS.md) | Play-policy scan ledger (advisory, not approval) |
| [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) | Third-party dependency inventory with verified licenses |
| [`PRE_PUSH_CHECKLIST.md`](PRE_PUSH_CHECKLIST.md) | Pre-push audit: per-item status and evidence |

---

## Installing and using the release APK

> **Where do I get an installable APK?** Merged pull requests produce **signed,
> installable** release artifacts through the workflow (see
> [GitHub Actions artifacts](#github-actions-artifacts)). Download the artifact zip
> from that run and pick the split matching your device's ABI. Each APK is signed with
> the maintainer's release identity and is signature- and ABI-verified before upload,
> so it installs directly with `adb`.

### Choose the APK that matches your device's CPU (ABI)

Release and debug builds are split **per CPU ABI** (`splits.abi` in
`pahebatcher/build.gradle.kts`), so each APK carries native code for exactly one ABI:

| APK file | CPU ABI (download selection) | Typical devices |
|---|---|---|
| `pahebatcher-arm64-v8a-release.apk` | `arm64-v8a` - 64-bit ARM | Essentially every Android phone/tablet sold since ~2017 |
| `pahebatcher-x86_64-release.apk` | `x86_64` | Default Android emulators on x86_64 hosts; a few Chromebooks |

**32-bit devices (`armeabi-v7a`, `x86`) are not supported.** The media engine
(FFmpegKit 8.1.7) ships native libraries for the two ABIs above only; the 32-bit
stub directories a universal APK used to carry contained no FFmpeg code and would
have crashed at runtime. The three compatibility stages, stated precisely:

- **Download selection** - you pick the one APK matching your device's ABI
  (`adb shell getprop ro.product.cpu.abi` prints it, e.g. `arm64-v8a`).
- **Install-time** - Android compares the device ABI against the APK's packaged
  ABIs; a mismatch fails with `INSTALL_FAILED_NO_MATCHING_ABIS`, so a wrong download
  cannot be installed by accident.
- **Runtime** - each per-ABI APK contains the complete FFmpegKit native set for its
  ABI (12 libraries), so native loads cannot fail with `UnsatisfiedLinkError` on a
  supported device.

1. **Verify** the file if a SHA-256 is provided:
   `certutil -hashfile <the-apk> SHA256` (Windows) or
   `sha256sum <the-apk>` (Linux/macOS).
2. **Install** with the Android SDK platform tools:
   `adb install -r <the-apk>`
   - The device must **not** already have a *differently signed* build of
     `com.pahebatcher.app` installed (debug and release builds use different signing
     identities, and Android refuses signature upgrades). Uninstalling the other build
     **erases that build's app data** - only do it deliberately.
3. **First run:** grant the notification permission when prompted if you want
   download/watchlist notifications. No account or login exists.
4. **Use it:**
   1. Type a title on **Home** (or press the keyboard's search key) and tap a result.
   2. On the details screen, pick an episode — the app resolves its stream and opens
      a sheet with **Download** / **Stream**.
   3. **Download** queues the episode; watch progress on the **Downloads** tab. A
      foreground notification shows progress while it runs.
   4. When an episode completes, tap **Play** on the Downloads tab, or **Stream**
      for direct playback without downloading.
   5. **Settings** (gear icon on Home) controls quality, parallelism, storage
      location, and notification toggles.

Downloads are written to the app-chosen folder (default: MediaStore `Movies/`, or a
folder you pick via the system folder picker) as `.mp4` files.

---

## Building from source

**Prerequisites** (all verified against this repository):

| Component | Version | Where it is declared |
|---|---|---|
| JDK | 25 | `JAVA_HOME` environment variable, `pahebatcher/build.gradle.kts` (`jvmToolchain(25)`) |
| Gradle | 9.8.2 (wrapper — do not use a system Gradle) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml` |
| Android SDK | compileSdk/targetSdk 37, build-tools 36.0.0 | `pahebatcher/build.gradle.kts` |
| Android device/emulator | API 32+ | `pahebatcher/build.gradle.kts` (`minSdk`) |

**Commands** (Windows shown; on Linux/macOS use `./gradlew`):

```powershell
.\gradlew.bat assembleDebug                 # per-ABI debug APKs -> pahebatcher/build/outputs/apk/debug/app-<abi>-debug.apk
.\gradlew.bat testDebugUnitTest             # JVM unit tests (debug variant)
.\gradlew.bat testReleaseUnitTest           # JVM unit tests (release variant)
.\gradlew.bat lintDebug                     # lint (target: 0 errors)
.\gradlew.bat assembleDebugAndroidTest      # instrumented test APK (compile only)
.\gradlew.bat assembleRelease bundleRelease # per-ABI release APKs (unsigned unless PB_* signing env vars are set) + AAB
```

> **Never** run `connectedDebugAndroidTest`/`connectedCheck` against a device that
> holds real app data: Gradle uninstalls both APKs afterwards and destroys that user's
> data. Full rules: [`ANDROID_TESTING_AND_RELEASE.md`](ANDROID_TESTING_AND_RELEASE.md)
> §5.4.

---

## Architecture

Single-Activity Jetpack Compose app with a strict layering
(full detail: [`ANDROID_ARCHITECTURE.md`](ANDROID_ARCHITECTURE.md)):

```
UI (Compose screens + navigation)         ui/, MainActivity
        |  state flows only
ViewModel (UI state, user intents)        ui/*ViewModel
        |  suspend calls
Use cases / repositories                  data/repository/, data/download/
        |  domain types
Domain logic (pure Kotlin)                domain/
        |  ports
Implementations                           data/local (Room), data/remote (OkHttp/WebView),
                                          data/hls (HLS engine), worker/ (WorkManager),
                                          playback/ (Media3), notification/
```

- **Composition root / DI:** `di/AppContainer.kt` (manual, no DI framework).
- **Persistence:** Room (episode/download/watchlist state) + DataStore (settings);
  WorkManager owns background download execution.
- **Networking:** OkHttp for HTTP; a WebView-backed session layer handles the
  Cloudflare/site session and is the only component that loads the source site.
- **Concurrency:** bounded throughout (HLS segment workers and parallel episode
  downloads are semaphore/limiter-controlled; no unbounded coroutines).
- **Background:** WorkManager `DownloadWorker` runs as a `dataSync` foreground
  service with a real progress notification; playback uses a Media3
  `MediaSessionService` (`mediaPlayback` type).

---

## How the main flows work

### Search

```
[Home search field] --IME Search / debounce 350 ms--> HomeViewModel
        |                                              |  (viewModelScope, cancels stale query)
        |                                              v
        |                                   search repository -> HTTP
        |                                              |
        v                                              v
   results list  <----------- SearchUiState (StateFlow) ------ collectAsStateWithLifecycle
```

### Search result interaction

```
[tap result row] --> navigate(AnimeDetailsRoute(session))
                          |
                          v
                AnimeDetailsViewModel.load(session)
                          |   EpisodeRepository: paged episode list + audio options
                          v
                [Details screen: episode rows, audio selector, batch controls]
                          |
        +-----------------+------------------+
        | tap episode (resolve)             | batch: page / all / selected
        v                                   v
  selectEpisode()                    enqueue downloads directly
```

### Resolution

```
selectEpisode(episode)
      |
      v
resolve stream chain:  play page  ->  Kwik  ->  M3U8 playlist
      |                   (rotation-safe URL handling, JS unpacking, quality/audio selection)
      v
resolution sheet:  "Resolving..."  ->  [Download] [Retry download] [Stream]
      |
      +--> Download  -> DownloadRepository.queueResolvedEpisode(...)   (see below)
      +--> Stream    -> navigate(PlayerRoute(episodeId, mode=STREAM))
```

### Download, persistence and assembly

```
DownloadRepository.queueResolvedEpisode
      |   Room row: QUEUED -> RESOLVING (CAS updates, segment folder staged)
      v
WorkManager.enqueueUniqueWork("download")
      |
      v
DownloadWorker (foreground service, type=dataSync, progress notification)
      |   claims the row with a run-token (crash-safe ownership)
      v
EpisodeDownloader  -- HlsPlaylistParser --> segment list
      |   bounded concurrency (HLS workers), AES-128 decryption
      v
SegmentFileStore   -- atomic per-segment writes (partial != completed)
      |
      v
FfmpegEpisodeAssembler (FFmpegKit)  --> single .mp4 (remux, no re-encode)
      |
      v
EpisodeExporter  -- ExportDestinationRouter --> MediaStore (Movies/) or SAF folder
      |
      v
Room state -> DOWNLOADING -> COMPLETED / FAILED  + notification (complete/failure)
```

State lives in Room, so the queue **survives process death, reboot and network
interruption**: `DownloadStateReconciler` re-checks WorkManager `WorkInfo` against
database rows on startup and repairs drift. An optional Wi-Fi-only constraint and
auto-resume setting gate re-execution.

### Export

```
ExportOutcome <- EpisodeExporter.export()
      |-- MediaStoreExporter : inserts into MediaStore (Movies/), RELATIVE_PATH honored
      `-- SafExporter        : writes into the user-picked folder (SAF tree)
            (router picks the destination from settings/permissions)
```

### Playback

```
PlayerRoute(episodeId, mode)
      |
      +-- mode=DOWNLOAD : load local completed MP4s -> ExoPlayer playlist
      `-- mode=STREAM   : reconstruct play URL -> direct HLS -> ExoPlayer
      |
      v
PlayerViewModel  owns ONE ExoPlayer per screen entry + Media3 MediaSession
      |
      v
PaheMediaSessionService (mediaPlayback FGS)
      `-- media3 owns the media notification, session controls and FGS promotion;
          foreign media controllers are rejected by MediaSessionControllerPolicy
```

Notification taps deep-link back into the player
(`player:<episodeId>:<mode>` is parsed by `PlayerTarget`).

### Settings and notifications

```
SettingsScreen  --> DataStore preferences --> consumed by
        |                                     - DownloadWorker / EpisodeDownloader (quality, workers, retry)
        |                                     - WifiOnlyConstraint / auto-resume
        |                                     - WatchlistNotificationManager / background checks
        `-- diagnostics console (ConsoleRoute) reads the bounded in-app log buffer

Notification channels:
  download_progress   (foreground-service progress, while downloading)
  download_complete   (episode ready)
  download_failure    (retryable/permanent failure)
  watchlist_new_episodes (aggregate "new episode" digest)
  + media3's own playback notification (from PaheMediaSessionService)
```

---

## Debug vs release builds

| | Debug | Release |
|---|---|---|
| Signing | Android debug key | **Maintainer's release identity**, applied via the env-gated `signingConfigs.release` block (`PB_*` variables, fail-closed - see [`signing.env.example`](signing.env.example); no key material in the repo). For merged PRs, CI sets these from repository secrets, so **release builds are signed** |
| Validation overlay + spike validators (`KwikValidator`, `OkHttpValidator`, `WebViewSessionValidator`) | present in `pahebatcher/src/debug/` | **absent** — release compiles a documented no-op seam (`pahebatcher/src/release/…/ValidationOverlayEntry.kt`) |
| Test-only components (`PBGrantProbeActivity`, `PBTestDocumentsProvider`) | present (debug manifest only) | **absent** |
| `android:networkSecurityConfig` (cleartext for `127.0.0.1` test fixture) | yes (debug manifest) | **absent** |
| Debuggable | yes | **no** (verified in the packaged release APK) |
| Validation screen reachable? | yes — `BuildConfig.DEBUG` gates the overlay in `MainActivity` | **never** — both the call site and the setter are `BuildConfig.DEBUG`-gated, and the release seam is a no-op |
| Diagnostic logcat logging | on | off (`AppLog` diagnostics default to `BuildConfig.DEBUG`; failure logs still work) |

The debug build additionally exposes the validation screen from the Home screen
(long-press); that path cannot exist in release. Both variants share the same
application id `com.pahebatcher.app`, so **debug and release are mutually exclusive
installs** (different certificates).

---

## GitHub Actions artifacts

Workflow: [`.github/workflows/release-artifacts.yml`](.github/workflows/release-artifacts.yml)

| Aspect | Behavior |
|---|---|
| Trigger | `pull_request` **closed** targeting `main` — runs **only if the PR was merged**; a closed-unmerged PR produces nothing. Deliberately **no** `push` and **no** `workflow_dispatch` trigger: signed artifacts come only from a merged PR. |
| Commit built | the PR's merge commit (`merge_commit_sha`), i.e. the merged base-branch commit |
| Verification gates | three independent jobs — `testDebugUnitTest`, `testReleaseUnitTest`, `lintDebug` — must all pass before the build job runs; each can be re-run in isolation from the *Actions* UI |
| Build tasks | `assembleRelease` + `bundleRelease` |
| Signing | **signed** — the build job reconstructs the maintainer's keystore from four repository secrets (`PB_KEYSTORE_BASE64`, `PB_KEYSTORE_PASSWORD`, `PB_KEY_ALIAS`, `PB_KEY_PASSWORD`) and fails closed if any is missing or invalid. The keystore lives only in runner temp and is deleted in an unconditional cleanup step. Only the build job sees secrets; verification jobs never do. |
| Post-build verification | `apksigner` (APK signature validity, identical signer certificate across all outputs, per-ABI native-library purity) + `jarsigner` (AAB) + SHA-256 checksums; Gradle success alone is never trusted |
| Artifact name | `PaheBatcher-release-v<version>-code<N>-<short-sha>` |
| Contents | per-ABI **signed** APKs (arm64-v8a, x86_64), signed AAB, `SHA256SUMS.txt`, `build-metadata.json` (commit, version, toolchain, run URL) |
| Retention | 90 days (explicit) |
| Permissions | `contents: read` only |
| Not done | no GitHub Release is created, nothing is uploaded to Google Play |

**Obtaining artifacts:** merge a pull request into `main`, then download the artifact
zip from that run's summary page in the *Actions* tab (*Release artifacts (merged PR)*).
The zip contains per-ABI **signed** APKs plus the signed AAB, their SHA-256 checksums
and build metadata. Pick the APK matching your device's ABI (see
[Installing and using the release APK](#installing-and-using-the-release-apk)), verify
its SHA-256 against `SHA256SUMS.txt`, and install it with `adb install -r`. Signing is
fail-closed and env-gated (`signingConfigs.release` driven by the `PB_*` variables —
see [`signing.env.example`](signing.env.example)); the four signing secrets are
configured once by the maintainer as repository secrets and never committed. The
keystore is reconstructed only inside the build job and deleted in an unconditional
cleanup step, so no key material is retained.

---

## Known limitations

The full, numbered list with workarounds and evidence is
[`LIMITATIONS.md`](LIMITATIONS.md) (53 entries). Highlights:

- **Cloudflare / site session** — session handling runs in a WebView; it is fragile
  by nature, and some findings are explicitly deferred (`LIMITATIONS.md` §1, §25).
- **TLS fingerprinting** — native HTTP cannot perfectly mimic a browser; the WebView
  session layer exists to compensate (§2).
- **In-player audio/quality switching** — not implemented; switch from the details
  screen (§31, catalog notes).
- **32-bit devices** — the release APK packages the FFmpeg native libraries for
  `arm64-v8a` and `x86_64` only; on `armeabi-v7a`/`x86` devices in-app assembly may
  fail (verified from the packaged APK's `lib/` contents).
- **Background execution & quotas** — Android may throttle long-running foreground
  work; observed FGS quota timeouts are recorded (§4).
- **Storage semantics** — MediaStore and the app database are not atomic together;
  crash between them can leave a repaired-but-not-identical state (§18).
- **Posters** — poster image source is unverified against the desktop tool (§17).
- **Process death** — handled via reconciliation, but recovery paths are inherently
  best-effort (§6).
- **Touch targets** — some controls are 40dp instead of the 48dp guideline (§19).

---

## Testing

Evidence scope for the **2026-10-24 pre-push round** (details in
[`PRE_PUSH_CHECKLIST.md`](PRE_PUSH_CHECKLIST.md)):

| Layer | Result | When |
|---|---|---|
| JVM unit tests, debug variant | **115 suites / 1,248 tests / 0 failures / 0 errors / 0 skipped** | 2026-10-24 (fresh run) |
| JVM unit tests, release variant | **114 suites / 1,243 tests / 0 failures / 0 errors / 0 skipped** | 2026-10-24 (fresh run) |
| Android lint (debug) | **0 errors**, 41 warnings + 1 note (all pre-existing, none in changed files) | 2026-10-24 (fresh run) |
| Instrumented / on-device | **not re-run in this round**; last recorded device rounds: 300 tests passing across six runs (2026-10-23) | see [`ROADMAP.md`](ROADMAP.md) / [`PARITY.md`](PARITY.md) |
| Release APK on device | **not tested in this round** — blocked by the debug/release signature conflict (documented, needs approval) | see `PRE_PUSH_CHECKLIST.md` |

Longer-term evidence lives in [`ANDROID_FEATURE_CATALOG.md`](ANDROID_FEATURE_CATALOG.md):
**24 features verified by instrumented device runs**, **16 by JVM tests**,
**18 by live device journeys**, plus dated historical runs in `ROADMAP.md`.

Test rules of the project: never weaken a test to get a pass, every non-trivial
subsystem keeps tests, and process-interrupt/resume behavior is explicitly tested.

---

## Security and privacy

- **Permissions declared by the app:** `INTERNET`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. The merged release manifest additionally
  carries `ACCESS_NETWORK_STATE`, `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` (declared
  by the bundled libraries: WorkManager/Media3) — verified against the packaged APK.
- **No data collection:** no analytics, crash, advertising or tracking SDK exists in
  `gradle/libs.versions.toml`. Nothing is sent anywhere except the source site the
  app operates on, to fetch the content you asked for.
- **No account, no identity:** there is no login and no personal-data collection —
  the policy scan's "User Account and Identity" goal returned zero findings.
- **Backups disabled:** `android:allowBackup="false"` (verified in the packaged
  release manifest).
- **Logging:** diagnostic logging is compiled out of release by default; cookies,
  tokens and credentials must never be logged (project rule in `AGENTS.md` §10).
- **Secrets:** no keystores, passwords or tokens are committed; the release signing
  identity lives outside the repository and is never read by CI.
- **Policy scan:** [`PLAY_POLICY_FINDINGS.md`](PLAY_POLICY_FINDINGS.md) records two
  runs of the `play-policy-insights` audit (both 🟢 compliant, advisory only) and the
  two Play Console declarations only the maintainer can submit. **It does not
  guarantee Play Store approval.**

- **Privacy and personal-data redaction:** before this repository's first
  publication, device identifiers (test-device serial/model), machine-local
  absolute paths and account names were replaced with neutral placeholders
  (`REDACTED-SERIAL`, `REDACTED-MODEL`, `<repo-root>`, `<TEMP>`,
  `<USERPROFILE>`, `<python-ref-X.Y.Z>`, `<mubeng-dir>`) across all documents,
  tests and scripts; helper scripts read such values from environment
  variables instead. Historical evidence records were rewritten in place with
  these placeholders - their substance (what was tested and how) is unchanged.

---

## Contributing and issue reports

There is no separate contributing guide yet; the de-facto project policy (documented
in [`AGENTS.md`](AGENTS.md)) is:

- Read `SPEC.md` first — it, not the desktop code, defines required behavior for new
  work; the Python repository is the behavioral reference.
- Make the **smallest complete, verified increment**: build, run the relevant tests,
  and record evidence. Never weaken tests, never ship placeholders or fake data.
- Keep the frozen search/IME/Cloudflare JSON path untouched unless a proven defect
  requires a stop-and-report first.
- Update the documentation you affect (`PARITY.md`, `DECISIONS.md`, `LIMITATIONS.md`,
  `ROADMAP.md` as appropriate).

**Issues:** open an issue describing (1) exact steps to reproduce, (2) expected vs
actual behavior, (3) build variant (debug/release) and app version, and (4) device
Android version. Never attach keystores, credentials, cookies, session data or other
personal information.

---

## License

**GPL-3.0-or-later.** PaheBatcher for Android, Copyright (C) 2026 HellHarbinger,
is licensed under the GNU General Public License version 3 or (at your option)
any later version. The complete license text is in [`LICENSE`](LICENSE)
(notice header + the unmodified official FSF text); third-party components keep
their own licenses, inventoried in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

In short: you are free to run, study, share and modify this software, and
derivative works must also be distributed under GPL-3.0-or-later. There is
**no warranty**; see the license text for the full terms.
