# PaheBatcher Android — Testing & Release Engineering Handbook

**Last verified: 2026-10-18**

Scope: how this native Android application is built, tested, verified on device, and prepared
for release. Self-contained; every claim below is grounded in this repository's build files,
test trees, scripts or recorded runs. Where something could not be re-verified today, it says so.

Companion documents: `ANDROID_ARCHITECTURE.md`, `ANDROID_DATA_AND_STATE.md`,
`ANDROID_USER_FLOWS.md`, `ANDROID_AI_HANDOFF.md`.

---

## 1. Toolchain

| Component | Version | Evidence |
|---|---|---|
| JDK (Gradle toolchain) | 25 | `pahebatcher/build.gradle.kts:49` — `kotlin { jvmToolchain(25) }` |
| Gradle | 9.8.2 (wrapper) | `gradle/wrapper/gradle-wrapper.properties:4` — `gradle-9.8.2-bin.zip` |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml:2` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml:3` |
| KSP | 2.3.12 | `gradle/libs.versions.toml:87` |
| compileSdk | 37 | `pahebatcher/build.gradle.kts:10` |
| targetSdk | 37 | `pahebatcher/build.gradle.kts:15` |
| minSdk | 32 | `pahebatcher/build.gradle.kts:14` |
| applicationId / namespace | `com.pahebatcher.app` | `pahebatcher/build.gradle.kts:9` |
| versionCode / versionName | 1 / 0.1.0 | `pahebatcher/build.gradle.kts:16-17` |
| Instrumentation runner | `androidx.test.runner.AndroidJUnitRunner` | `pahebatcher/build.gradle.kts:19` |

Key library lines (from `gradle/libs.versions.toml`): Compose BOM 2026.08.00, Room 2.8.5,
DataStore 1.2.1, Coroutines 1.11.0, OkHttp 4.12.0, FFmpegKit (maintained fork) 8.1.7,
WorkManager 2.11.2, Media3 1.11.1, Navigation-Compose 2.10.1.

Build the project only through the wrapper (`gradlew.bat` on Windows) so the Gradle version
is whatever the wrapper pins. Do not upgrade any of the above without following
`AGENTS.md` §17 (toolchain compatibility verification) and recording the outcome.

---

## 2. Build commands

All commands are run from the repository root on Windows:

| Purpose | Command |
|---|---|
| Debug APK | `.\gradlew.bat assembleDebug` |
| Instrumented test APK (compile only) | `.\gradlew.bat assembleDebugAndroidTest` |
| JVM unit tests (all) | `.\gradlew.bat testDebugUnitTest` |
| Lint | `.\gradlew.bat lintDebug` |

Outputs:

- Debug APKs - one per CPU ABI since the 2026-10-24 ABI-split round:
  `pahebatcher/build/outputs/apk/debug/pahebatcher-arm64-v8a-debug.apk` and
  `pahebatcher/build/outputs/apk/debug/pahebatcher-x86_64-debug.apk`; install the one matching the
  target device (`adb shell getprop ro.product.cpu.abi`, usually `arm64-v8a`).
  (Historical single-file record, as built on 2026-10-18: `app-debug.apk`
  119,242,126 bytes; SHA-256
  `71AA1A2043DD249C31BE84EFEB18B4F65F1BA50D52F736CF74337DF105CEE0F7` - recompute per release,
  this value identifies only the artifact built that day)
- Instrumented APK: built under `pahebatcher/build/outputs/apk/androidTest/debug/` by
  `assembleDebugAndroidTest` (compile-only gate in this repository - not built on
  every round)
- Unit-test reports: `pahebatcher/build/reports/tests/testDebugUnitTest/`
- Lint report: `pahebatcher/build/reports/lint-results-debug.html`

**Never** run `connectedDebugAndroidTest` / `connectedCheck` on a device that holds user
data — see §5.4 for why.

---

## 3. Test locations

### 3.1 JVM unit tests — `pahebatcher/src/test/java/com/pahebatcher/app/`

110 Kotlin files (109 test suites plus one shared fake) as of 2026-10-18. The last recorded
full run — a forced rerun (`--rerun-tasks`, defeating the up-to-date cache), 2026-10-18,
recorded in DECISIONS.md §157 — is **109 suites / 1,189 tests / 0 failures /
0 errors**. [**SUPERSEDED 2026-10-22**: the current suite is **114 Kotlin test files**, and
the latest recorded full forced rerun — 2026-10-21, the closure-gap remediation round — is
**114 suites / 1,236 tests / 0 failures / 0 errors / 0 skipped** (`--rerun-tasks`, recorded
in ROADMAP.md:2194; 1,237 − 1 = AQR-102's deliberately deleted vacuous test). The 2026-10-18
figures above stand as the dated record for that round.] [**CURRENT 2026-10-24 (pre-push
audit round)**: **115 Kotlin test files**; fresh runs on 2026-10-24, both exit 0 — debug
**115 suites / 1,248 tests / 0 failures / 0 errors / 0 skipped** and release
**114 suites / 1,243 tests / 0 failures / 0 errors / 0 skipped** (`testDebugUnitTest` +
`testReleaseUnitTest`; the release suite is enabled by
`android.onlyEnableUnitTestForTheTestedBuildType=false` in `gradle.properties`). The
2026-10-21 figures above stand as the dated record for that round.] Run with
`.\gradlew.bat testDebugUnitTest`. Coverage areas include:

- domain logic — `domain/util/` (range parsing, paging), `domain/hls/` (playlist parsing,
  AES), `domain/download/` (failure categories, resolve-ahead gate), `domain/session/`
  (Cloudflare detection, refresh signal), `domain/watchlist/`
- data layer — `data/stream/` (Kwik resolver, JS packer, quality/audio selectors),
  `data/hls/` (segment store, concurrency limiter, assembler, decryptor),
  `data/repository/`, `data/download/`, `data/export/`, `data/playback/`, `data/local/`
- UI state — `ui/details/`, `ui/home/`, `ui/player/`, `ui/settings/`, `ui/sessions/`,
  `ui/sessionprompt/`, `notification/`, `worker/`, `logging/`

### 3.2 Instrumented tests — `pahebatcher/src/androidTest/java/com/pahebatcher/app/`

106 Kotlin files (suites + support fixtures) as of 2026-10-18, run on a real device via
direct `am instrument` (§5.2). Notable suites:

- `data/download/` — `OutputDestinationProbeGateTest` (destination-aware submission gate),
  `ReplaceFileOutputSafetyTest`, `ReplaceFileDuplicateProtectionTest`,
  `ReplaceFileConfirmationTest`, `DuplicateEnqueueTest`, `ResumeTest`,
  `ProcessDeathRecoveryTest`, `RealProcessDeathSeedTest`, `RealProcessDeathPausedDeviceTest`,
  `DownloadStateReconcilerTest`, `ConcurrencyTest`, `CancellationTest`,
  `WifiOnlyConstraintTest`, `UniqueWorkTest`
- `data/export/` — `MediaStoreExportTest` (**23 tests**, verified 23/23 on 2026-10-18),
  `SafExporterTest`, `ExportDestinationRouterTest`, `OutputTreeRepositoryTest`
- `data/session/` — `WebViewJsonExtractionTest`
- `data/local/` — `StateTransitionTest`, `MigrationTest`, `ResolvedPendingCountTest`
- `data/hls/` — `FullDownloadFlowTest`, `FfmpegAssemblyDeviceTest`,
  `FfmpegAssemblyValidDataTest`, `FfmpegPhase9ValidationTest`
- `data/stream/` — `StreamHlsHeaderTest`, `Phase17DeviceStreamingVerificationTest`,
  `Phase17StreamingSiblingNavigationDeviceTest`
- `data/repository/` — `SessionRepositoryRoomTest`, `SessionActionsTest`,
  `WatchlistRepositoryRoomTest`
- `worker/` — `WorkerExecutionTest`, `PauseResumeDeviceTest`, `AutoResumeConnectivityTest`,
  `DownloadWorkerStateGuardTest`, `WatchlistCheckWorkerTest`, `ForegroundWorkerTest`
- `notification/` — `NotificationTest`, `NotificationPermissionTest`,
  `NotificationInteractionTest`, `BatchFailureNotificationTest`
- `ui/` — `AnimeDetailsScreenTest` (46 tests, DECISIONS.md:7292), `NavigationTest`,
  `SessionsScreenTest`, `SessionsNavigationTest`, `SettingsContentTest`,
  `SettingsPersistenceTest`, `ThemePersistenceTest`, `WatchlistScreenTest`,
  `OfflineBannerTest`, `SearchImeTest`, `TouchTargetAndFocusTest`,
  `sessionprompt/SessionRefreshPromptDialogTest`, `player/…`, `downloads/…`
- `di/StreamResolverProductionWiringTest` — production wiring (not fakes) is asserted
  on device

---

## 4. Adding a regression test

Rules (from `AGENTS.md` §8, exercised by every slice in this repo):

1. **Test the behavior, not the implementation.** Pin the observable contract (state
   transition, persisted row, file on disk, notification content) so refactors that keep
   behavior stay green.
2. **Follow an existing pattern** for the layer you are testing — do not invent a new
   harness. Each type has a canonical exemplar:
   - *Pure JVM behavior* — `domain/util/EpisodePagingTest.kt`: small, deterministic,
     table-style cases over a pure function; no Android types.
   - *JVM pinned contract for a UI-adjacent calculation* —
     `ui/details/AnimeDetailsPageBarBudgetTest.kt` (DECISIONS.md:7261): pins
     `pageNumberButtonBudget` as a deterministic function of width/font-scale, because the
     wide-window branch is not reachable through Compose on the ~461dp test device.
   - *Instrumented gate/state machine* —
     `data/download/OutputDestinationProbeGateTest.kt` (KDoc at lines 26–41): injects a fake
     `EpisodeExporter` as `destinationProbe` and asserts the GATE's decision deterministically,
     while the real MediaStore query stays covered by the export suites. Note the pattern:
     fake the boundary, assert the decision, never fake the thing under test.
   - *Instrumented Compose UI* — `ui/AnimeDetailsScreenTest.kt` on
     `createAndroidComposeRule<MainActivity>` with production wiring; asserts user-visible
     text/semantics only.
3. **Make the failure mode honest.** If a scenario depends on live-service health, say so in
   the test's KDoc and structure it so a healthy environment cannot silently pass a broken
   feature (see §14.1).
4. Run the focused test (§5), then the full JVM suite, then `lintDebug`, per
   `AGENTS.md` §7.

---

## 5. Running tests on a device

### 5.1 Install form (safe upgrade, preserves data)

```
adb -s <serial> install -r --user <userId> <apk>
```

`-r` upgrades in place; `--user <userId>` scopes the install to the verified user. This
preserves app data. Never omit `-s` when more than one device is attached.

Install the two APKs separately when instrumenting (use the debug split matching
the device ABI):

```
adb -s <serial> install -r --user <userId> pahebatcher\build\outputs\apk\debug\pahebatcher-arm64-v8a-debug.apk
adb -s <serial> install -r --user <userId> pahebatcher\build\outputs\apk\androidTest\debug\pahebatcher-debug-androidTest.apk
```

The instrumented package is `com.pahebatcher.app.test` (applicationId + `.test`).

### 5.2 Direct instrumentation (the approved method)

```
adb -s <serial> shell am instrument -w -e class <FQCN> com.pahebatcher.app.test/androidx.test.runner.AndroidJUnitRunner
```

- **ONE CLASS PER INVOCATION.** A comma-separated `-e class A,B` list honors only the FIRST
  class on this toolchain — silently under-running the rest. Loop over classes instead.
- A `#method` suffix works for a single method (`-e class com.x.YTest#method`), still one
  class per invocation.
- Quote the `-e` values when running through a shell that touches `#`.

### 5.3 Evidence format

Quote the raw instrumentation tail verbatim in any report: `OK (N tests)` or
`Tests run: N, Failures: …` plus the `INSTRUMENTATION_CODE: -1` line. Never paraphrase a
count you did not see.

### 5.4 CRITICAL — do not use the Gradle connected task on a data-bearing device

`connectedDebugAndroidTest` **uninstalls both APKs (app + test) after every run** and
therefore destroys that user's app data (Room database, settings, staged downloads). This
was pinned in a controlled before/after experiment (LIMITATIONS.md:454) and sharpened to the
task boundary (LIMITATIONS.md:456): manual `install -r` followed by direct
`am instrument` leaves the package installed the whole time. Direct instrumentation does
NOT wipe anything. Use Gradle connected runs only on throwaway emulators/devices whose data
may be destroyed.

---

## 6. Lint & static analysis

- `.\gradlew.bat lintDebug` — target is **0 errors**. Pre-existing warnings are tolerated
  only when recorded (e.g. the two `AnimeDetailsScreen.kt` warnings tracked at
  ROADMAP.md:1896).
- Do not suppress findings to turn a build green (`AGENTS.md` §7).

---

## 7. Device enumeration & verification procedure

The app has been verified on a physical arm64 device whose adb serial is `REDACTED-SERIAL`
(REDACTED-MODEL). **A serial is not a user id.** The Android-dev secondary user's id must be
rediscovered on every session — never hardcode it:

1. `adb devices` — enumerate; act only on entries whose state is exactly `device`.
   `unauthorized`/`offline` devices are reported separately, never assumed usable.
2. `adb -s <serial> shell pm list users` — read the user list; find the entry labelled
   **"Android Dev"** and take its numeric id (e.g. `UserInfo{<id>:Android Dev:…}`).
   Verify the LABEL matches "Android Dev" before proceeding; if the label is absent or
   ambiguous, stop and ask — do not guess an id.
3. `adb -s <serial> shell pm list packages --user <id> | findstr com.pahebatcher.app` —
   confirm `com.pahebatcher.app` is installed for that user; for instrumented runs also
   confirm `com.pahebatcher.app.test`.
4. `adb -s <serial> shell dumpsys package com.pahebatcher.app | findstr /C:"firstInstallTime" /C:"lastUpdateTime"`
   — `lastUpdateTime` advancing after an install is the definitive "the new APK is the one
   running" evidence.
5. Run every subsequent command with an explicit `--user <id>` / `--user` argument where
   the subcommand accepts one (`pm`, `am`, `cmd package`), and with `adb -s <serial>`.

**The Owner user (user 0) must never be targeted** by install, instrumentation, storage
probing or cleanup commands. The app is verified under the Android Dev secondary user only.

---

## 8. Safe upgrade & data-preservation rules

- Install with `-r --user <id>` (§5.1): upgrade in place, data preserved.
- Never `adb uninstall`, never `pm clear`, never `pm clear-data`, never
  `cmd package clear-data` against `com.pahebatcher.app` or `com.pahebatcher.app.test`
  without explicit authorization (§12).
- **Multi-user storage caveat:** plain `adb shell ls /sdcard/...` resolves the *Owner*
  user's shared storage, NOT the Dev user's. To observe the Dev user's exported media,
  query MediaStore scoped to that user:

  ```
  adb -s <serial> shell content query --user <id> --uri content://media/external/video/media --projection _id:_display_name:_size:is_pending:owner_package_name
  ```

  Filter the result rows by `owner_package_name: com.pahebatcher.app` and by the expected
  display name. Do not "fix" a missing file by looking in Owner's `/sdcard` — that is a
  different user's view.
- Preserve `.investigation/` evidence captures; do not delete device logs or fixtures that
  other documents cite (the citation checker verifies capture identity).

---

## 9. End-to-end manual checklist (device journey, 24 steps)

The live release journey performed on 2026-10-18 (device `REDACTED-SERIAL`, Android Dev user)
covers, in order:

1. Cold launch (fresh process → Home renders, no crash).
2. Navigation across the bottom tabs and pushed routes; back stack behaves.
3. Live search against the real service returns real results.
4. Session refresh path (healthy session: operations proceed without a prompt).
5. Anime details opens for a real title; title/episode list load.
6. Pagination on a title with >100 episodes (page bar, page jumps, ellipsis behavior).
7. Range selection including rejection cases (invalid range → honest message, no queue).
8. Audio selection (per-episode audio context applied to the requested episode).
9. Individual episode download completes end-to-end.
10. Batch download of a small selection completes.
11. Progress notification appears and carries the correct language label.
12. Completion notification appears on completion.
13. Failure path: airplane mode mid-download → failure notification/UI carries an honest
    reason (no fake progress, no swallowed error).
14. Downloads screen reflects rows, states and audio labels.
15. Already-downloaded detection: re-queuing a completed episode is a no-op.
16. Re-download after cache removal: temp cache cleared → destination probe suppresses the
    destructive re-download (row restored/handled without losing the exported file).
17. Existing-output behavior: an exported file present with the row gone is handled by the
    destination-aware gate.
18. Multi-audio export identity: two audio variants of one episode coexist under distinct
    names.
19. MediaStore metadata: exported rows are visible via the §8 `content query` with correct
    name/size/`is_pending=0`.
20. Playback: exported file plays in the in-app player; streamed playback reaches READY.
21. Force-stop survival: `am force-stop` mid-download → relaunch reconciles state honestly
    (resume/re-queue/demote per the persisted row).
22. Session recovery: state reconstructed after process death.
23. Navigation restoration: tab/back-stack restoration after recreation.
24. Watchlist + settings: add a watchlist URL, open settings, flip a setting, restart,
    setting persisted.

Each step must be recorded with a classification: **VERIFIED**, **NOT VERIFIED** (attempted,
failed), **NOT RUN** (not attempted — say why), or **NOT APPLICABLE**.

---

## 10. Citation checker

`scripts/check-doc-citations.ps1` mechanically validates the repository's documentation
anchors (table integrity, `FILE:NNNN` citation resolution, required content, frozen-evidence
identity). Run it after ANY documentation edit:

```
powershell -ExecutionPolicy Bypass -File scripts\check-doc-citations.ps1
```

It must report `PASS` (exit 0). **Status as of 2026-10-18 (latest run this session):** the
script exits 1 with ONE pre-existing failure, which is not in this document set:

- `DECISIONS.md:3754` cites `AnimeDetailsViewModel.kt:362`, which is blank

[**SUPERSEDED 2026-10-22**: that single pre-existing failure no longer reproduces.
`DECISIONS.md:3754` now cites `AnimeDetailsViewModel.kt:1444` (a non-blank KDoc comment
line, verified directly), and the checker reports **PASS (exit 0)** — re-run after the
2026-10-21 documentation edits (recorded in ROADMAP.md:2197-2198) and again on 2026-10-22
after the reconciliation edits in this round. The `:362`-is-blank record above stands as
the dated 2026-10-18 state.]

An earlier same-day run additionally reported four mixed-line-endings failures in the
scanned status documents; those no longer reproduce — as of this session those four files
are uniformly CRLF (recounted directly: 7,277 / 3,273 / 1,934 / 1,222 CRLF lines, equal to
their total LF counts). This document and `ANDROID_FEATURE_CATALOG.md` are not in the
checker's scanned file set
(the script scans five primary status documents — `AGENTS.md`, `DECISIONS.md`,
`LIMITATIONS.md`, `ROADMAP.md` and one further document declared in its `$DocFiles` block —
plus `PHASE11_HANDOFF.md`: scripts/check-doc-citations.ps1:90-100), so these two files
cannot cause or mask those findings. Remediation of that finding belongs to the owners
of those documents and must not be attempted by editing them from this workstream.

---

## 11. Evidence & reporting requirements

Every verification claim in any report must carry:

1. **Raw output quotes** — the literal `OK (N tests)` / `Tests run: …` tail, or the literal
   `BUILD SUCCESSFUL` / `lintDebug` summary. No paraphrased counts.
2. **APK identity** — SHA-256 of the exact APK installed
   (`Get-FileHash <apk> -Algorithm SHA256`) plus the §7 `lastUpdateTime` evidence.
3. **Device identity** — adb serial, user id used (`--user <id>`), and the date.
4. **Logs as evidence** — keep `adb logcat` captures for download/session journeys in
   `.investigation/`; never log cookies, authorization headers, session tokens or stream
   URLs (AGENTS §10) — the app's own `logging/LogUrlSanitizer.kt` enforces this in-process,
   and manual captures must respect the same rule.
5. **Honest classification** — scenarios not actually executed are reported as
   `NOT VERIFIED` / `NOT RUN` / `NOT APPLICABLE`. Never upgrade "implemented" to "verified".

---

## 12. Destructive commands — never without explicit authorization

- `adb uninstall …` (either package)
- `adb shell pm clear …` / `pm clear-data` / `cmd package clear-data`
- Wiping directories under `/sdcard` (any user's view) as a shortcut
- Resetting the Room database (`drop`, deleting `databases/`, clearing app data)
- Deleting MediaStore entries as a shortcut to make a test "pass"
- Any command targeting the **Owner** user (no `--user`, or `--user 0`)
- Deliberately provoking repeated live Cloudflare challenges (rate-limit risk; use the
  app's existing cached session and the quiet-refresh design instead)

If a verification step seems to require one of these, stop, report the blocker, and ask
(AGENTS.md §16).

---

## 13. Release-readiness criteria

A debug build may be called release-ready only when ALL of the following hold, each with
evidence per §11:

1. Full JVM suite green (`testDebugUnitTest --rerun-tasks`, 109 suites / 1,189 tests, 0 failures/0 errors).
2. `lintDebug` — 0 errors.
3. `assembleDebug` — BUILD SUCCESSFUL; APK SHA-256 recorded.
4. Instrumented suites green via **direct `am instrument`** (§5.2) on the Android Dev user —
   one class per invocation, `OK (N tests)` quoted per class.
5. The 24-step journey (§9) executed and every step classified.
6. Documentation reconciled (feature catalog statuses match the evidence actually collected).
7. `scripts/check-doc-citations.ps1` passing, or its then-current failures explicitly listed
   as pre-existing, outside this document set, with owners named (see §10).
8. APK deployed per `AGENTS.md` §20 to every `device`-state target with `--user <id>`, and
   installation verified on each.

---

## 14. Known environment-dependent tests & limitations

### 14.1 SessionRefreshPromptDialogTest — live-service dependent by design

`ui/sessionprompt/SessionRefreshPromptDialogTest.kt` (`aSignalRequestShowsTheRootPromptAndNotNowDismissesIt`,
lines 40–68) raises a real `SessionRefreshSignal` on the real `MainActivity` and then waits
up to 15 s for the dialog. The production ViewModel makes **one quiet background refresh
before ever prompting** (`SessionRefreshViewModel.kt:43-51`): if that quiet refresh SUCCEEDS
against the live service — which is exactly what happens on a healthy session — no dialog is
shown **by design**, and the test's wait times out. Therefore this test's outcome depends on
live-service/session health at run time; a timeout on a healthy session is an environment
artifact, not necessarily a product defect, and must be reported with that context (and
re-run when the session is genuinely stale). The seam logic itself (latch, coalescing,
dismiss/refresh outcomes) is deterministically pinned by the JVM
`SessionRefreshViewModelTest`.

### 14.2 Device input & display quirks (device `REDACTED-SERIAL`)

- `input tap` is **rejected** on this device; use
  `input motionevent DOWN <x> <y>` + `input motionevent UP <x> <y>` (learned during the
  199-test connected run, DECISIONS.md:2054).
- Before any UI run, wake and unlock explicitly:
  `adb shell input keyevent KEYCODE_WAKEUP` then `adb shell wm dismiss-keyguard`; a
  dozing/keyguard-up device produced 54 false UI-launch failures once
  (DECISIONS.md:2054).
- Observe notifications via `adb shell dumpsys notification --noredact` (the non-redacted
  form is required to read extras such as `navigate_to`).
- The device's window is ~461dp wide; the responsive "wide window fits every page" pager
  branch is not reachable through Compose on it and is covered by the JVM budget test
  instead (DECISIONS.md:7304-7307).

### 14.3 MediaStoreExportTest — flake history, currently clean

`data/export/MediaStoreExportTest.kt` (23 tests) had one historical fixture/timing flake in
`legacyUnsuffixedFile_neverDeletedOrRenamed`. On 2026-10-18 the suite ran **clean 23/23**.
If this case ever fails again, treat it as a fixture/timing investigation (MediaStore index
propagation), not as evidence of a product regression, until proven otherwise — and re-run
before reporting.

### 14.4 Other standing environment notes

- `worker/ForegroundWorkerTest` has a long-documented notification-baseline sensitivity on
  this device (see LIMITATIONS.md:1857) — known, pre-existing, not a release blocker by
  itself; always re-run it in isolation before classifying.
- Some validation suites hit the live service (`data/remote/validation/`); their outcome
  tracks upstream availability and must be classified with the service state of the day.
- The Gradle connected-task uninstall behavior (§5.4) has an undetermined in-toolchain
  mechanism but a deterministic observed boundary; it is a test-environment item, never
  counted as a product defect.
- Two structural device-run artifacts root-caused during Phase 14 (shade-overlay failure
  signature; `POST_NOTIFICATIONS` user-10 baseline drift) are documented in §14.5 — read
  both before classifying device-suite failures.

### 14.5 Shade-overlay failure signature & `POST_NOTIFICATIONS` baseline drift (Phase 14 device procedure)

Two structural, independently re-created device-run artifacts, root-caused during the
Phase 14 final-sources verification (2026-10-03/04). The full transcripts, dumpsys
captures, and the mechanism re-creation are kept as the historical evidence record under
`.investigation/phase14/` (`README-shade-overlay.txt`,
`shade-observed-state-dumpsys.txt`); the operational knowledge is mirrored here so it
stays with the testing procedure.

- **Notification-shade overlay — environmental failure signature.** An expanded
  notification shade sitting over the app produces exactly three symptoms in one run:
  `NavigationTest.navigatingAwayLeavesNoImeWindowVisible`
  (`NavigationTest.kt:215`) and `NavigationTest.systemBackClosesTheKeyboardBeforeAnyNavigation`
  (`NavigationTest.kt:229`) both fail with a 5000 ms `ComposeTimeoutException` at their
  FIRST `waitUntil { isImeVisible }` (the shade window holds input focus, so the IME
  focus request never happens), and
  `ThemePersistenceTest.lightPersistedLaunchSettlesToTheLightScheme`
  (`ThemePersistenceTest.kt:151`) fails with `AssertionError — last median luma=48`
  (`ThemePersistenceTest.kt:213`; the screenshot samples the dark shade background, and
  the identical luma value across runs is the linkage). Diagnosis: check
  `dumpsys window` for `mCurrentFocus=... NotificationShade` — a uiautomator dump then
  contains only SystemUI nodes. Fix: `adb shell cmd statusbar collapse` (focus returns
  to `com.pahebatcher.pahebatcher/.MainActivity`), then re-run the affected classes standalone;
  the same 3 tests pass on the same APK and app data minutes apart, environment the only
  variable. These three are timing/focus-sensitive by construction — a failure matching
  this signature is an environment artifact, not a product defect, and must be reported
  with the shade state disclosed.
- **`POST_NOTIFICATIONS` user-10 baseline drift — structural, not a flake.** The
  instrumentation suite grants user 10 the `POST_NOTIFICATIONS` permission mid-run
  (suite position ~47, via `FirstEnqueuePromptTest.ensureRealPermissionGranted` —
  `FirstEnqueuePromptTest.kt:343`, the `--user`-targeted grant documented in the
  `Phase10TestSupport.grantNotificationPermission` KDoc), and Android offers no revoke
  without force-stopping the run — so every completed device-suite run LEAVES
  user-10 = GRANTED behind. On the next run, the three
  `FirstEnqueuePromptTest.a_denied_*` tests (`FirstEnqueuePromptTest.kt:113`, `:162`,
  `:207`) throw `AssumptionViolatedException — baseline is GRANTED; run from a fresh
  install`. Before each device run, measure the baseline with
  `dumpsys notification` per user and restore it — fresh-install baseline is
  user0 = true (granted via `pm grant --user 0`) / user10 = false; if user 10 is
  GRANTED, the restore path is reinstalling both packages (manual `adb -s <serial>
  install -r` for app and test APKs — the mechanism in §5.4, per AGENTS.md §20) and
  re-granting user 0 only. Never classify these three assumption violations as failures — they
  are the precondition guard working.
