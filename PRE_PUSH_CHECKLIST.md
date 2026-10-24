# PRE_PUSH_CHECKLIST.md — Pre-push audit (2026-10-24)

**Round:** 9-stage pre-push audit · Play-policy remediation · README · release
re-verification · GitHub Actions configuration.

**Method note:** this repository is **not a Git repository** (no `.git`, no history),
so **no Git diff was or could be reviewed**. File inventories below are built from
timestamps, SHA-256 hashes, direct source reads and command output. No Git command
was run at any point in this audit; nothing was pushed, published, released or
uploaded.

**Status legend:** `PASS` · `FAIL` · `BLOCKED` · `NEEDS HUMAN ATTENTION`.

**Overall gate: `NEEDS HUMAN ATTENTION`** — no FAIL and no critical policy/signing/
release/regression blocker was found, but 6 items below require a human decision
before this repository is made public (see [Items requiring your attention](#items-requiring-your-attention)).

---

## Summary

| ID | Item | Status | Blocks push recommendation |
|---|---|---|---|
| P-01 | AQR-138 / AQR-140 prior remediation | PASS | No |
| P-02 | Files changed in this round | PASS | No |
| P-03 | JVM unit tests (fresh, both variants) + flake/one-shot records | PASS | No |
| P-04 | Instrumented test results (existing, not re-run) | PASS (scope-limited) | No |
| P-05 | Build results (debug, release verification, lint-vital) | PASS | No |
| P-06 | Lint / static analysis | PASS | No |
| P-07 | Documentation citation checker | PASS | No |
| P-08 | Known limitations and regressions | PASS (no regression) | No |
| P-09 | Debug/release separation (source + packaged artifact) | PASS | No |
| P-10 | Signing configuration and secret hygiene | NEEDS HUMAN ATTENTION | No (human rebuilds signed artifacts) |
| P-11 | Public repository hygiene | NEEDS HUMAN ATTENTION | No (resolve before making the repo public) |
| P-12 | Existing release APK/AAB inspection and source consistency | PASS | No |
| P-13 | Signed release rebuild with existing identity | BLOCKED | No (expected stop: signing secret unavailable) |
| P-14 | Play Policy Insights findings and remediation | PASS (advisory) | No (2 Play Console actions remain, external) |
| P-15 | GitHub Actions security and artifact retention | PASS | No |
| P-16 | Release-device testing | BLOCKED | No (requires approval: signature conflict) |
| P-17 | Documentation accuracy fixes made this round | PASS | No |
| P-18 | Observations needing a product decision | NEEDS HUMAN ATTENTION | No |

---

## P-01 — AQR-138 / AQR-140 (prior remediation round)

- **Status:** PASS
- **Repository path:** `.investigation/aqr-138-140-remediation/` (private evidence —
  deliberately **not published**; excluded from Git by `.gitignore` in this round),
  plus the production fix in `app/src/main/java/com/pahebatcher/app/ui/navigation/AppNavHost.kt`.
- **Evidence:** the round closed with Gates A–G recorded in
  `.investigation/aqr-138-140-remediation/gate-evidence.md` (9 files present, verified
  this round): Gate A 42/42, Gate B 1,248 tests / 115 classes / 0 failures, Gate C
  evidenced with documented flake + one-shot records, Gate E citation-checker PASS,
  Gate F bundle manifest hash comparison script + recorded hash, Gate G pass. The
  round fixed 1 production file and 4 fixture repairs (5 root causes), verdict
  "READY FOR PUSH REVIEW".
- **Risk / impact:** if this evidence were lost or the fix reverted, the prior review
  verdict would be void.
- **Required next action:** none — keep `.investigation/` out of any published
  repository (now enforced by `.gitignore`).
- **Blocks push recommendation:** No.

## P-02 — Files changed in this round

- **Status:** PASS
- **Repository paths:**

  | File | Kind of change |
  |---|---|
  | `README.md` | **new** — public landing page (Stage 5) |
  | `PRE_PUSH_CHECKLIST.md` | **new** — this document (Stage 1) |
  | `PLAY_POLICY_FINDINGS.md` | **new** — policy audit ledger (Stages 2–4); later sanitized of local absolute paths |
  | `.github/workflows/release-artifacts.yml` | **new** — merged-PR artifact workflow (Stage 8) |
  | `.gitignore` | **edited** — added `/.investigation` and `/.scratch` (public hygiene) |
  | `ANDROID_TESTING_AND_RELEASE.md` | **edited ×2** — Gradle wrapper row corrected 9.7.1 → 9.8.2; JVM test counts updated with this round's fresh runs |
  | `PLAY_POLICY_FINDINGS.md` (edits) | 4 sanitizations: local absolute paths → repository-relative / withheld |

- **Evidence:** **zero production (`app/src/main`), zero test (`app/src/test`,
  `app/src/androidTest`) and zero Gradle build-script files were modified in this
  round** — the newest file under `app/src` is dated 2026-10-23 19:41, older than the
  2026-10-24 09:06 release build (verified by direct timestamp comparison). Stage 3
  found no safe code fix (Group A = none), so no source change was warranted.
- **Risk / impact:** documentation-only changes cannot alter runtime behavior; the
  workflow is inert until the repository is on GitHub.
- **Required next action:** review the new/edited files as part of the push review.
- **Blocks push recommendation:** No.

## P-03 — JVM unit tests (fresh runs) + flake/one-shot records

- **Status:** PASS
- **Repository path:** `app/src/test/` (115 Kotlin files); results in
  `app/build/test-results/testDebugUnitTest/` and `.../testReleaseUnitTest/`;
  records in `ANDROID_TESTING_AND_RELEASE.md` §3.1, §14.3.
- **Evidence (this round, both exit 0):**
  - `.\gradlew.bat testDebugUnitTest lintDebug` → **BUILD SUCCESSFUL**, JUnit XML
    parsed: **115 suites / 1,248 tests / 0 failures / 0 errors / 0 skipped**.
  - `.\gradlew.bat testReleaseUnitTest` → **BUILD SUCCESSFUL**, parsed:
    **114 suites / 1,243 tests / 0 failures / 0 errors / 0 skipped** (release-variant
    suite exists because `gradle.properties` sets
    `android.onlyEnableUnitTestForTheTestedBuildType=false`).
  - Both match/exceed the documented baseline (114 suites / 1,236 tests, 2026-10-21);
    the +12/+7 growth is explained by test files added 2026-10-23 (e.g.
    `SessionRefreshPromptDialogTest.kt`, 19:41) — **not** by this round.
  - **Documented flakes / one-shot tests:** `ANDROID_TESTING_AND_RELEASE.md:429`
    (MediaStoreExportTest historical flake, "currently clean"),
    `ANDROID_TESTING_AND_RELEASE.md:478` (`POST_NOTIFICATIONS` user-10 baseline drift
    = structural, not a flake), `ROADMAP.md:867` ("No flaky tests — MET WITH
    DOCUMENTED RESIDUALS", three diagnosed test-side residuals), one-shot seed/cleanup
    protocol documented in `scripts/h1-protocol-capture.ps1` header.
- **Risk / impact:** none identified — no failures, no suppressed tests.
- **Required next action:** none.
- **Blocks push recommendation:** No.

## P-04 — Instrumented test results (existing; not re-run this round)

- **Status:** PASS (scope-limited — explicitly not re-executed)
- **Repository path:** `app/src/androidTest/` (106 Kotlin files per
  `ANDROID_TESTING_AND_RELEASE.md` §3.2); verification tiers in
  `ANDROID_FEATURE_CATALOG.md`.
- **Evidence:** this round did **not** re-run on-device instrumentation. Existing
  recorded results: catalog marks **24 instrumented-verified**, **16 JVM-verified**,
  **18 live-device-journey** features; latest recorded device rounds = **300 tests
  passing across six runs (2026-10-23)** (`ROADMAP.md:2228`, `PARITY.md:1290`).
  Device used for those runs: physical device, API 36 (serial redacted).
- **Risk / impact:** device-side behavior is evidenced historically, not freshly; no
  source changed in this round, so historical evidence remains applicable.
- **Required next action:** none for push; re-run device suites after any production
  change (per `ANDROID_TESTING_AND_RELEASE.md` §5 rules).
- **Blocks push recommendation:** No.

## P-05 — Build results

- **Status:** PASS
- **Repository paths:** `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`
- **Evidence (all exit 0, this round):**
  - `.\gradlew.bat testDebugUnitTest lintDebug` → BUILD SUCCESSFUL.
  - `.\gradlew.bat assembleRelease bundleRelease` → **BUILD SUCCESSFUL** (release
    variant compiles, `lintVitalRelease` passed as part of the build). Because the
    repository has no `signingConfigs`, this verification build produced
    `app-release-unsigned.apk` (107,039,030 B, SHA-256
    `735AE543E3785BFFBD445A478AC48E681BA21181011B3B5909BFD352062E57D0`) and an unsigned
    AAB (44,342,801 B, SHA-256
    `A2CD9772FC7AB160B5967CE2DF02144ABAE707323F2C30F2A4454C246C95FAEE`;
    `jarsigner -verify` → "jar is unsigned", `apksigner verify` exit 1 — as expected).
  - **Artifact restoration:** the signed artifacts that the verification build
    replaced in `app/build/outputs/` were restored byte-identically afterwards from
    the untouched copies in `app/release/`; post-restore hashes equal the pre-build
    baseline (verified True/True). The unsigned verification APK is retained outside
    the repository (temp backup) as evidence.
  - Existing debug APK: `app/build/outputs/apk/debug/app-debug.apk` (118,095,854 B,
    2026-10-24 09:07) — not rebuilt this round (no source change).
- **Risk / impact:** none — build is reproducible and green; unsigned outputs are
  clearly named and never installable-by-accident as a signed release.
- **Required next action:** none.
- **Blocks push recommendation:** No.

## P-06 — Lint / static analysis

- **Status:** PASS
- **Repository path:** reports in `app/build/reports/lint-results-debug.html` / `.sarif`
- **Evidence:** `lintDebug` exit 0 (BUILD SUCCESSFUL). Parsed: **0 errors**, HTML
  summary **41 warnings** + 1 note = 42 SARIF results — identical to the documented
  baseline (41 warnings + 1 note); no warning is in a file changed this round (no
  source files were changed). No detekt/ktlint/spotless is configured — `lintDebug` is
  the static-analysis gate (`ANDROID_TESTING_AND_RELEASE.md` § lint target: 0 errors).
- **Risk / impact:** none.
- **Required next action:** none.
- **Blocks push recommendation:** No.

## P-07 — Documentation citation checker

- **Status:** PASS
- **Repository path:** `scripts/check-doc-citations.ps1`
- **Evidence:** `powershell -ExecutionPolicy Bypass -File scripts\check-doc-citations.ps1`
  → exit 0, "ALL CHECKS PASSED" (run 2026-10-24 after all documentation edits in this
  round, including `README.md` and this file).
- **Risk / impact:** citation drift in docs is caught mechanically; a failure would
  mean broken `FILE:NNNN` pointers.
- **Required next action:** none.
- **Blocks push recommendation:** No.

## P-08 — Known limitations and regressions

- **Status:** PASS (no regression found)
- **Repository path:** `LIMITATIONS.md` (53 numbered entries), `PARITY.md`,
  `ROADMAP.md`
- **Evidence:** no production code changed in this round → no behavioral regression
  is possible from this round's edits; fresh tests equal/exceed baseline and lint
  matches baseline (P-03, P-06). Known limitations remain documented (Cloudflare
  session fragility §1/§25, TLS fingerprinting §2, background quotas §4, storage
  atomicity §18, touch targets §19, no in-player audio/quality switch, poster source
  unverified §17, plus §30–§53 residuals). Two *new* observations from this audit are
  recorded in P-18 rather than silently added to `LIMITATIONS.md`.
- **Risk / impact:** limitations are disclosed, not hidden.
- **Required next action:** review P-18 observations.
- **Blocks push recommendation:** No.

## P-09 — Debug/release separation

- **Status:** PASS
- **Repository paths:** `app/src/debug/`, `app/src/release/`, `app/src/main/`,
  packaged artifact `app/build/outputs/apk/release/app-release.apk`
- **Evidence (verified against the packaged release APK itself, not just source):**
  - `aapt2 dump xmltree AndroidManifest.xml`: **no `android:debuggable`**, no
    `testOnly`, no `networkSecurityConfig`, no `usesCleartextTraffic`, and
    `allowBackup=false`.
  - Dex scan of all 4 dex files (`apkanalyzer dex packages`, 302,865 listing lines):
    **ABSENT** — `ValidationScreen`, `KwikValidator`, `OkHttpValidator`,
    `WebViewSessionValidator`, `PBGrantProbe`, `PBTestDocumentsProvider`,
    `ValidationSpikeTest`. **PRESENT** — only the documented release no-op
    `ValidationOverlayEntry` (`app/src/release/.../ValidationOverlayEntry.kt`).
  - `MainActivity.kt:168` `if (BuildConfig.DEBUG && showValidation)` and
    `MainActivity.kt:176` `if (BuildConfig.DEBUG)` — release can never reach the
    validation overlay (source read).
  - APK entry list (335 entries): **no** `proxies.txt`, `local.properties`,
    keystore, `.investigation`, `.scratch`, `.github` or credential-named files;
    assets are only baseline profiles.
  - Release logging: `AppLog.kt` diagnostic (DEBUG/INFO) output gated by
    `BuildConfig.DEBUG` (SPEC 33) — gate read in source.
- **Risk / impact:** none found — debug tooling is present only in debug builds and
  the debug tools were **not** removed (constraint honored).
- **Required next action:** none.
- **Blocks push recommendation:** No.

## P-10 — Signing configuration and secret hygiene

- **Status:** NEEDS HUMAN ATTENTION
- **Repository paths:** `app/build.gradle.kts` (no `signingConfigs`),
  `.gitignore` (`/app/release` ignored), `DECISIONS.md:2676`,
  `LIMITATIONS.md:2598` (both state release is unsigned)
- **Evidence:**
  - The existing release APK **is signed**: `apksigner verify --print-certs` exit 0 —
    `Signer #1 certificate DN: CN=Hell Harbinger` (SHA-256 digest withheld from docs;
    reproduce locally with the same command), APK Signature Scheme v2, v1 disabled
    (`signing-config-versions.json`: enableV1=false, enableV2=true). The AAB verifies
    with `jarsigner -verify` → "jar verified" (self-signed, no timestamp — normal).
  - The signing key material exists **outside** the repository (a PKCS#12 file in the
    user profile, 2,582 B, written minutes before the 2026-10-24 09:06 build — path
    withheld; it is personal/secret material). **No password, alias or environment
    variable for it exists anywhere in this repository, in `~/.gradle`, or in the
    process environment** (searched: gradle files, `.properties`, env var names).
  - No `keystore.properties`/`key.properties` exists; no secret was created, printed,
    replaced or committed by this audit.
  - Doc/artifact inconsistency: `DECISIONS.md:2676` and `LIMITATIONS.md:2598` say a
    release APK is unsigned — true of the *build configuration*, but the artifacts
    produced 2026-10-24 are signed out-of-band (P-12).
- **Risk / impact:** (a) nobody but the maintainer can rebuild a *signed* release
  artifact — this audit therefore stopped the signed build step as instructed
  (P-13); (b) CI can only ever produce unsigned artifacts until a human decides
  otherwise (P-15); (c) two doc lines are stale relative to the signed artifacts.
- **Required next action (human):**
  1. Rebuild the signed release APK/AAB yourself using your existing signing identity
     (nothing in this audit changed source, so the rebuild is build-equivalent).
  2. Decide whether to add a `signingConfigs.release` block (reads secrets from
     environment/properties — a signing-configuration change requiring your approval)
     and whether CI may consume repository secrets; until then CI stays unsigned by
     design.
  3. Optionally update `DECISIONS.md:2676` / `LIMITATIONS.md:2598` when (2) is decided.
- **Blocks push recommendation:** No — the artifacts that exist are validly signed
  with the preserved identity, and secret hygiene is correct.

## P-11 — Public repository hygiene

- **Status:** NEEDS HUMAN ATTENTION
- **Repository paths:** `.gitignore`, `README.md`, root `*.md` (11 files), absence of
  `LICENSE`
- **Evidence:**
  - `.gitignore` now excludes `/.investigation` (577 files, ~2 GB of private
    evidence), `/.scratch` (both play-policy audit runs — preserved locally, never
    published), `proxies.txt` (credential pool), `/app/release`, `/app/build`,
    `local.properties`.
  - `README.md` exists, contains **no** local absolute paths, usernames, device
    serials, credentials, badges, invented screenshots or license claims (all links
    verified against real repository paths; no screenshots exist anywhere in the
    repository, so none are referenced).
  - **Device identifier exposure in historical docs:** the device serial appears
    **308 times across 11 root documents** (`ANDROID_FEATURE_CATALOG.md` 23,
    `DECISIONS.md` 93, `ROADMAP.md` 93, `LIMITATIONS.md` 45, `PARITY.md` 35, others 19)
    and the device model appears **27 times** (`ANDROID_TESTING_AND_RELEASE.md` 1,
    `DECISIONS.md` 9, `LIMITATIONS.md` 4, `PARITY.md` 5, `ROADMAP.md` 5,
    `PHASE14_IMPLEMENTATION_STATE.md` 3). These are historical test-evidence records
    written across many earlier rounds — **this audit did not rewrite them**
    (out of scope; rewriting evidence records is a judgment call, not a safe fix).
  - Local absolute paths (`D:\...`) appear in `DECISIONS.md` (historical decision
    records) and `ANDROID_AI_HANDOFF.md:95,192`. No `C:\Users\...` path and no
    signing-cert digest appears in any root document.
    *(Correction 2026-10-24, privacy-first release-prep round: the "No `C:\Users\...`
    path" claim above was **inaccurate as written** — three such paths existed in
    `DECISIONS.md` (this audit missed them), one each in `PROJECT_REFERENCE.md`,
    `ROADMAP.md` and the archived `PHASE11_HANDOFF.md`. Device serial/model and
    machine-local paths were redacted repo-wide in that later round.)*
  - **No `LICENSE` file exists** (recursive search). No `CONTRIBUTING`,
    `CODE_OF_CONDUCT`, issue or PR templates exist.
- **Risk / impact:** if the repository is pushed as-is, device identifiers and local
    paths become public; the absence of a license means no reuse rights are granted
    (stated honestly in `README.md`).
- **Required next action (human):**
  1. Decide: redact the device serial/model from the 11 documents (mechanical
     replacement, recommended before making the repo public) **or** explicitly accept
     publishing them.
  2. Decide whether to add a `LICENSE` before the repository goes public.
  3. Decide whether to keep publishing `DECISIONS.md`/`ANDROID_AI_HANDOFF.md` with
     local absolute paths, or sanitize them too.
- **Blocks push recommendation:** No for a reviewed push; **resolve before the
  repository is made public**.

## P-12 — Existing release APK/AAB: inspection and source consistency

- **Status:** PASS
- **Repository paths:**
  - `app/build/outputs/apk/release/app-release.apk`
  - `app/build/outputs/bundle/release/app-release.aab`
  - `app/release/app-release.apk` / `app/release/app-release.aab` (byte-identical
    copies; `/app/release` is gitignored)
- **Evidence (recorded 2026-10-24):**

  | Property | APK | AAB |
  |---|---|---|
  | SHA-256 | `F63A9FBC6A06D4717F7A33C651056FA318AC84724C31D364D96DEBF217526D80` | `E91D1095DC95BCC4D11FEA019B9469F7E4041173AC3A6932486CFB0CE22F9DB7` |
  | Size | 107,051,318 B | 44,361,681 B |
  | Built | 2026-10-24 09:06:55 | 2026-10-24 09:12:47 |
  | Application ID | `com.pahebatcher.app` | same (from `output-metadata.json`) |
  | Version | versionCode 1 / versionName 0.1.0 | same |
  | SDK | minSdk 32, targetSdk/compileSdk 37, platformBuildVersion 17 | same |
  | Signature | `apksigner verify` **exit 0**, `CN=Hell Harbinger`, v2 scheme | `jarsigner -verify` **"jar verified"** (self-signed, no timestamp) |
  | Debuggable | **no** (packaged manifest) | n/a (bundle manifest mirrors it) |
  | Permissions (merged, packaged) | INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, FOREGROUND_SERVICE_MEDIA_PLAYBACK, ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, + library-generated DYNAMIC_RECEIVER_NOT_EXPORTED | same |
  | Components (packaged) | MainActivity (launcher), PaheBatcherApplication, `SystemForegroundService` (FGS type `dataSync`=0x1), `PaheMediaSessionService` (FGS type `mediaPlayback`=0x2), WorkManager receivers/services, androidx startup providers, media3 `BluetoothValidationActivity` | same |
  | Native libs | 4 ABIs declared; FFmpeg set present in **arm64-v8a and x86_64 only** (see P-18) | n/a |
  | Diagnostic/test markers | none found (P-09) | none found |
  | Dependencies | OkHttp 4.12, Media3 1.11.1, Room 2.8.5, WorkManager 2.11.2, FFmpegKit 8.1.7 (from `gradle/libs.versions.toml`; `DebugProbesKt.bin` is the standard kotlinx-coroutines artifact) | same |

  **Source consistency:** the newest file under `app/src` (2026-10-23 19:41) and the
  newest Gradle file are both **older** than the APK (2026-10-24 09:06) → the existing
  artifacts are build-consistent with the current source and build configuration. The
  two `app/release/` copies are byte-identical to the `build/outputs` originals
  (hash equality verified True/True before and after this round's verification build).
- **Risk / impact:** none — artifacts are valid, current and reproducible.
- **Required next action:** recreate them yourself after review (P-10), since this
  audit cannot produce a signed rebuild.
- **Blocks push recommendation:** No.

## P-13 — Signed release rebuild with the existing identity

- **Status:** BLOCKED
- **Repository path:** `app/build.gradle.kts` (absence of `signingConfigs`); key
  material outside the repository (path withheld).
- **Evidence:** the rebuild requirement was attempted per Stage 6. The repository has
  **no `signingConfigs` block, no `keystore.properties`, no password environment
  variable and no reference to the keystore anywhere** (searched all Gradle files,
  `*.properties`, script files and environment variable names). Per the audit's
  safety rules the signed build step was **stopped rather than substituted**: no key
  was generated, no replacement identity was created, no password was guessed, and
  the existing signing identity was left untouched. A green **unsigned** verification
  build is recorded in P-05 as build evidence.
- **Risk / impact:** the freshly reviewed source has not yet been turned into a
  signed artifact; until you rebuild, the signed artifacts on disk are the
  2026-10-24 09:06/09:12 ones (which are build-equivalent to current source, P-12).
- **Required next action (human):** provide/apply your existing signing secret and
  run the documented release build yourself; then re-hash the outputs (or ask for the
  rebuild to be re-verified).
- **Blocks push recommendation:** No — this is the anticipated stop condition, and
  you stated you will recreate the release APK/AAB after remediation.

## P-14 — Play Policy Insights findings and remediation

- **Status:** PASS (advisory only — **not** a claim of Play Store approval)
- **Repository paths:** `PLAY_POLICY_FINDINGS.md`; preserved audit runs in
  `.scratch/play_policy_insights_d1c24f36-…` (initial) and
  `.scratch/play_policy_insights_58ddc260-…` (final) — local-only, gitignored.
- **Evidence:**
  - Both runs executed the installed skill unmodified (portable Python 3.13.17 used
    only as the interpreter; all imports are stdlib): init exit 0 → **same 6 goals**
    (`data_safety_part_1..4`, `permissions_and_apis`, `user_account`) → 6 workers all
    valid JSON → aggregate exit 0 (`critic_chunks: 1`) → critic valid JSON →
    `generate_report.py` exit 0.
  - **Initial:** 13 worker findings (1 CRITICAL pruned by critic, 1 IMPORTANT
    downgraded to MANUAL_REVIEW, 10 local-only statements) → 🟢 Compliant.
  - **Final:** 12 worker findings (no CRITICAL re-raised; FGS findings merged to 1;
    Web Browsing History pruned by the critic) → 🟢 Compliant. Play Store lookup
    returns HTTP 404 as expected (app not published).
  - **Remediation:** Group A (safe code fixes) = **0** — the one code-adjacent ask
    (keep the debug validation screen out of release) was verified to already hold
    (P-09). Group B = 2 human actions: **Q1** optional Data Safety confirmation for
    the production WebView session flow, **Q2** mandatory Play Console declarations
    for the `dataSync` + `mediaPlayback` foreground services. Group C = 10 findings
    not applicable with evidence. Group D = 0. **No required goal failed.**
  - Variance between runs is LLM worker/critic packaging variance and is disclosed in
    `PLAY_POLICY_FINDINGS.md` §E.
- **Risk / impact:** Q1/Q2 block **Play Console submission only**, never a repository
  push. The audit does not guarantee approval (tool disclaimer + our own).
- **Required next action (human, external):** complete Q1/Q2 in Play Console before
  any internal-testing upload.
- **Blocks push recommendation:** No.

## P-15 — GitHub Actions security and artifact retention

- **Status:** PASS
- **Repository path:** `.github/workflows/release-artifacts.yml` (the only workflow)
- **Evidence:**
  - **Validation:** `actionlint 1.7.12` → **exit 0, zero findings**. All action
    versions verified to exist at their referenced tags before use
    (`actions/checkout@v7`, `actions/setup-java@v6`, `actions/upload-artifact@v7`,
    `gradle/actions@v6` incl. `wrapper-validation`, `android-actions/setup-android@v4`
    — latest stable majors as of 2026-10-24; inputs verified against each action's
    `action.yml`/README).
  - **Merge gate:** job-level `if: github.event_name == 'workflow_dispatch' ||
    github.event.pull_request.merged == true` → a PR closed **without** merging
    produces no artifact; a merged PR builds
    `github.event.pull_request.merge_commit_sha` (the merged base-branch commit).
  - **Verification gates upload:** `testDebugUnitTest` + `testReleaseUnitTest` +
    `lintDebug` run before `assembleRelease`/`bundleRelease`, which run before the
    upload step (any failure stops the job).
  - **Artifact:** name
    `PaheBatcher-release-unsigned-v<version>-code<N>-<sha12>` (variant + version +
    commit); contents limited to `dist/` (APK, AAB, `SHA256SUMS.txt`,
    `build-metadata.json` with commit/run URL/toolchain) — **never** `.investigation/`,
    `.scratch/`, keystores or local config; `if-no-files-found: error`;
    **`retention-days: 90`** (explicit).
  - **Permissions:** top-level `permissions: contents: read` (least privilege);
    `persist-credentials: false` on checkout; no `pull_request_target`; Gradle wrapper
    validated by `gradle/actions/wrapper-validation`.
  - **Secrets:** none exist in the workflow; a report-only step records that CI
    artifacts are **unsigned** and deliberately ignores any future keystore secret
    (adding signing requires a human-approved `signingConfigs` change first).
  - **Not present, by design:** no GitHub Release creation, no Play Console upload,
    no push/merge triggers.
  - **Safe validation plan (no PR was pushed or closed):** after the repository is on
    GitHub, either (a) run the workflow manually via *Actions → Release artifacts
    (merged PR) → Run workflow* (`workflow_dispatch` exists for exactly this), or
    (b) merge a real PR and confirm one artifact run exists for it; then check the
    artifact name, `SHA256SUMS.txt`, `build-metadata.json`, the 90-day retention
    badge, and that a closed-unmerged test PR produces **no** run artifact.
- **Risk / impact:** until validated on GitHub, the workflow is lint-verified but not
  execution-verified (disclosed here rather than claimed).
- **Required next action:** execute the safe validation plan after the first push.
- **Blocks push recommendation:** No.

## P-16 — Release-device testing

- **Status:** BLOCKED
- **Repository paths:** packaged `app/build/outputs/apk/release/app-release.apk`;
  installed package `com.pahebatcher.app` on the test device (serial redacted,
  API 36).
- **Evidence:**
  - Device connected and inspected **before** any install attempt (no install was
    attempted, no data touched): installed package = **debug build**
    (`pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]`, versionName 0.1.0,
    versionCode 1, last updated 2026-10-23).
  - Release and debug share `com.pahebatcher.app` but use **incompatible signing
    certificates** (debug: system `CN=Android Debug` keystore; release:
    `CN=Hell Harbinger`). Android refuses signature upgrades, so installing the
    release APK requires uninstalling the debug app — which **destroys its data** and
    is prohibited without your explicit approval. The unsigned CI/verification APK
    cannot be installed at all (no certificates).
  - Therefore the Stage 7 on-device matrix could not be executed. Status per test:
    cold launch, startup/navigation, search+IME, Cloudflare/JSON path, result
    interaction, details/episode selection, resolution, download+Worker, progress/
    notifications, export/playback, restart/persistence, release permissions →
    **NOT RUN** (all of them). Debug-variant equivalents of these flows are covered
    by the historical device evidence in P-04.
  - Release/debug isolation was instead verified statically against the packaged
    release artifact (P-09), which is the portion of Stage 7 that does not require a
    device.
- **Risk / impact:** release-variant behavior on a device is untested in this round;
  mitigated by (a) identical source to the device-tested debug build, (b) 1,243
  release-variant unit tests, (c) `lintVitalRelease`, (d) packaged-artifact isolation
  checks.
- **Required next action (human):** choose one: (1) approve uninstalling the debug
  install (data loss) so the signed release can be installed and tested, (2) approve
  an isolated setup (secondary user profile / throwaway emulator) for the release
  test, or (3) accept this gap for the push review and test after your own rebuild.
- **Blocks push recommendation:** No — this is the anticipated signature-conflict
  outcome, reported instead of worked around.

## P-17 — Documentation accuracy fixes made this round

- **Status:** PASS
- **Repository paths:** `ANDROID_TESTING_AND_RELEASE.md` (rows edited),
  `PLAY_POLICY_FINDINGS.md` (sanitized), `.gitignore`
- **Evidence:**
  1. Toolchain table said Gradle **9.7.1** with evidence pointer
     `gradle-wrapper.properties:3`; the wrapper actually resolves
     **9.8.2** at line 4 (the wrapper was bumped 2026-10-23 01:53). Row corrected to
     `9.8.2 (wrapper) | gradle/wrapper/gradle-wrapper.properties:4 | gradle-9.8.2-bin.zip`.
     (Historical 9.7.1 statements in `DECISIONS.md` were left untouched — they are
     dated decision records, not current-state claims.)
  2. JVM test-count record updated with this round's fresh, parsed results
     (debug 115/1,248/0; release 114/1,243/0), marked `CURRENT 2026-10-24`.
  3. `PLAY_POLICY_FINDINGS.md` scrubbed of local absolute paths (now
     repository-relative or withheld) so no username/personal path is published.
  4. `.gitignore` now prevents publication of `.investigation/` and `.scratch/`.
- **Risk / impact:** low; all edits are factual corrections with reproducible
  evidence.
- **Required next action:** none.
- **Blocks push recommendation:** No.

## P-18 — Observations needing a product decision

- **Status:** NEEDS HUMAN ATTENTION
- **Repository paths:** `app/build.gradle.kts` (packaging), `gradle.properties` /
  `DECISIONS.md` (wrapper bump), `DECISIONS.md:2676`, `LIMITATIONS.md:2598`
- **Evidence:**
  1. **32-bit ABI gap:** the release APK's `lib/` contains the FFmpeg/FFmpegKit
     libraries for **arm64-v8a and x86_64 only** (armeabi-v7a and x86 carry only
     `libandroidx.graphics.path.so` + `libdatastore_shared_counter.so`). On a
     32-bit-only device the download-assembly step (FFmpeg) would likely fail at
     runtime. No `ndk.abiFilters` filter exists in `app/build.gradle.kts`.
  2. **Undocumented wrapper bump:** Gradle wrapper moved 9.7.1 → 9.8.2
     (2026-10-23 01:53) with no `DECISIONS.md` entry; builds are green on 9.8.2
     (this round) but the change is unrecorded.
  3. **Stale "unsigned" statements:** `DECISIONS.md:2676` and `LIMITATIONS.md:2598`
     describe release artifacts as unsigned while the 2026-10-24 artifacts are signed
     out-of-band (true of the *build config*, stale for the *artifacts*).
- **Risk / impact:** (1) possible runtime failure class on 32-bit devices; (2) audit
  trail gap; (3) reader confusion about signing state.
- **Required next action (human):** (1) decide whether to restrict packaging to
  arm64-v8a/x86_64 (e.g. `ndk.abiFilters`) or document 32-bit as unsupported; (2)
  optionally record the wrapper bump in `DECISIONS.md`; (3) optionally refresh the two
  stale lines when the signing story is settled (P-10).
- **Blocks push recommendation:** No.

---

## Items requiring your attention

| # | Exact path(s) | Concrete recommended action |
|---|---|---|
| 1 | `app/build.gradle.kts` (+ signing key material outside the repo) | Rebuild the signed release APK/AAB yourself with your existing identity; decide whether to add a `signingConfigs.release` block reading secrets from the environment (approval required — nothing was added or guessed by this audit) |
| 2 | `ANDROID_FEATURE_CATALOG.md`, `ROADMAP.md`, `PARITY.md`, `DECISIONS.md`, `LIMITATIONS.md`, `ANDROID_TESTING_AND_RELEASE.md`, `PHASE14_IMPLEMENTATION_STATE.md`, `PHASE14_PLAN.md`, `PHASE_A_FINDING_INVENTORY.md`, `PROJECT_REFERENCE.md`, `ANDROID_AI_HANDOFF.md` | Redact the device serial (308 occurrences) and model (27 occurrences) before making the repository public — **or** explicitly accept publishing them |
| 3 | repository root | Add a `LICENSE` (or accept that no license is granted — `README.md` currently states the no-license status honestly) |
| 4 | device with the debug install (serial redacted) | Approve one of: uninstall debug (data loss) / isolated test profile / accept the untested-release gap for this review (P-16) |
| 5 | `.scratch/play_policy_insights_*` + `PLAY_POLICY_FINDINGS.md` §D | Complete the two Play Console actions: **Q2** FGS declarations (`dataSync`, `mediaPlayback`) — mandatory before any Play upload; **Q1** optional Data Safety confirmation for the WebView session flow |
| 6 | `app/build.gradle.kts`, `DECISIONS.md:2676`, `LIMITATIONS.md:2598` | Decide on the 32-bit ABI packaging question and refresh the two stale "unsigned" doc lines (P-18) |

---

## Overall gate

**`NEEDS HUMAN ATTENTION`** — not PASS, because items 1–6 above are unresolved
human decisions. It is **not FAIL**: every executable check in this audit passed
(fresh tests, lint, builds, artifact signature verification, packaged-artifact
isolation checks, citation checker, actionlint, two green policy-audit runs), no
critical policy finding remains, no signing defect exists in the artifacts, no
release build failed, and no unexplained regression was found. The two `BLOCKED`
items (P-13 signed rebuild, P-16 release-device test) are the audit's anticipated
stop-and-report conditions, both awaiting your input — not defects hidden from the
review.
