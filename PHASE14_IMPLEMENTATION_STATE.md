# Phase 14 Implementation State

Phase: 14
Status: COMPLETE

Current milestone: **M8 (exit gate) — CLOSED 2026-10-04. All round-3 findings fixed; tester round-3 PASS
(411/0/0/0); final-sources re-verification complete (JVM 70/719/0/0/0 PASS, lint 0F/0E/34W/1H PASS, device
411 tests / 3 failures root-caused to a device notification-shade overlay with the same 3 tests green standalone
after shade collapse); **reviewer round 4 = VERDICT: APPROVED** (4 advisories R4-1..R4-4 dispositioned, evidence
persisted under `.investigation/phase14/`); §20 reinstall + baseline restore verified; ROADMAP gate lines updated;
citation checker EXIT=0. Final report delivered; §12 below**
Completed milestones: M0, M1, M2, M3, M4, M5, M6, M7, §17 rows 419/420/421/422/423 (all device-verified), Final
verification round 1 (JVM + lint + builds + full device suite + AGENTS §20 install), Documentation reconciliation +
citation check EXIT=0, Tester gate round 2 = PASS (2026-10-03), Reviewer round-2 code fixes (H-1 seed baseline + verify
block, M-1 active trigger round, L-1..L-4), affected-class device re-run OK (10 tests), **H-1 extended operator
protocol executed 2026-10-04 01:27 (record → force-stop mid-run → relaunch → verifyAttemptCount OK (1 test))**, §15 +
§34.11 + row 421 doc updates, citation check EXIT=0, **Tester gate round 3 = PASS (2026-10-04)** (independent:
builds EXIT=0, JVM 70/719/0/0/0, lint 0F/0E/34W/1H, device 411/0/0/0 over 72 suites incl. round-2 methods,
§20 installs both users, citation EXIT=0), **Reviewer round-3 fix set: NEW-1 (tooling section relocated to
TOOLING_SETUP.md → citation checker EXIT=0), NEW-2 (PHASE14_PLAN:387 + DownloadWorker.kt:691-692 retry-increment
wording), NEW-3 (H-1 protocol re-run 13:42 with persisted capture under `.investigation/phase14/`), NEW-4 + L-1
(state-file wording)**, **Reviewer round 4 = VERDICT: APPROVED (2026-10-04)** with advisories R4-1..R4-4
dispositioned (evidence persisted in `.investigation/phase14/`, DECISIONS:4992 + ROADMAP:794/823/833/914 updated
in place, static-audit wording corrected, citation EXIT=0 re-confirmed), **final-sources re-verification
JVM PASS / lint PASS / device 411-3-with-environmental-root-cause + standalone 3/3 green**, §20 reinstall +
baseline restore re-verified
Partially completed milestones: none — M8 closed
Current files being modified: none (all work complete; deliverables = source/test/doc changes recorded above,
evidence artifacts under `.investigation/phase14/`, this state file, ROADMAP/DECISIONS annotations)
Final-sources re-verification records (all device-suite rounds COMPLETE):
  - Round 1 of the final-sources
    verification (single Gradle invocation `testDebugUnitTest lintAnalyzeDebug lintReportDebug
    connectedDebugAndroidTest`, finished 14:06, BUILD FAILED): **JVM EXECUTED-PASSED 70 suites / 719 tests /
    0 / 0 / 0** (XML at `build/test-results/testDebugUnitTest`); **lint EXECUTED-PASSED 0 Fatal / 0 Error /
    34 Warning / 1 Hint** (`lintReportDebug` re-run standalone 14:22, BUILD SUCCESSFUL); **device suite
    410 tests / 2 failures — environmental, not a regression**: (a) the Gradle log ends with
    `adb.exe: device 'REDACTED-SERIAL' not found` during the post-run uninstall step (adb transport dropped), the last
    suite (WorkerExecutionTest) recorded an EMPTY `<failure>` at 3.444 s (too fast for its 20 s/120 s waits) and
    its second `@Test` (`worker_missing_episode_fails_without_crash`) never appeared in the XML — explaining
    410 vs the source's 411 `@Test` count; (b) both failed tests **pass when re-run standalone on the same
    APK**: `WorkerExecutionTest` `OK (2 tests)` 27.97 s and `SettingsOutputLocationTest` `OK (7 tests)`
    13.058 s; (c) no crash-buffer entries; (d) the only source delta vs the tester's green 411/0/0/0 run is
    4 lines of KDoc comment text. Re-run (round 2) launched to replace the truncated result — see below.
  - Re-run (round 2 of final-sources verification, finished 14:43, BUILD FAILED): **device 411 tests / 7 failures /
    72 suites** — full test count restored, but (a) **3 of the 7 are `AssumptionViolatedException` in
    `FirstEnqueuePromptTest.a_denied_*` ("baseline is GRANTED … run from a fresh install") — a baseline drift that
    is STRUCTURAL: the suite itself grants user-10 POST_NOTIFICATIONS at suite position ~47 via
    `FirstEnqueuePromptTest.ensureRealPermissionGranted` (the `--user`-targeted grant documented in the
    `Phase10TestSupport.grantNotificationPermission` KDoc, required by every worker-package posting assertion), and
    Android gives no way back — revoking would force-stop the instrumentation run — so every completed suite run
    LEAVES user-10 = GRANTED behind. Round 1's completion did so (my two standalone confirmation runs after
    round 1 also call `grantNotificationPermission()`), and unlike the tester's round-3 session — which explicitly
    restored the baseline after its run — the drift was not restored before round 2 started; (b) the other 4 are
    timing-sensitive UI assertions (`NavigationTest` IME `waitUntil` 5000 ms ×2, `ThemePersistenceTest` luma
    capture, `MediaSessionLifecycleTest` notification-removal) that passed in round 1 and both tester runs.
    Post-run AGP cleanup **successfully uninstalled both packages** (the same uninstall that failed with
    "device not found" in round 1 — tester already disclosed this AGP behavior as re-covered by §20).
    Recovery executed: both APKs reinstalled (§20, Success ×2, 2 packages present), fresh-install permission
    state measured (both users `granted=false`, `notLaunched=true`), user-0 restored with `pm grant` →
    **baseline back to user0=true / user10=false**, then round 3 of the device suite launched.
  - Re-run (round 3 of final-sources verification, finished 2026-10-04 15:08:59, BUILD FAILED 19m43s, exit 1):
    **device 411 tests / 3 failures / 72 suites** (XML `TEST-REDACTED-MODEL - 16.xml` [reused path — run-stamped snapshot:
    `.investigation/phase14/device-final-sources-round3-411-3.xml`], gradle log + exit code persisted in
    `.investigation/phase14/device-rerun2-*.log/.txt`): `NavigationTest.
    systemBackClosesTheKeyboardBeforeAnyNavigation` (ComposeTimeoutException 5000 ms at NavigationTest.kt:234 —
    the FIRST `waitUntil { isImeVisible }`), `NavigationTest.navigatingAwayLeavesNoImeWindowVisible` (:221 — same
    first wait), `ThemePersistenceTest.lightPersistedLaunchSettlesToTheLightScheme` (AssertionError "last median
    luma=48"). Round-2's baseline-drift failures are GONE (`FirstEnqueuePromptTest.a_denied_*` ×3 and
    `MediaSessionLifecycleTest` green) — the pre-run baseline restoration was effective. Post-run AGP cleanup
    uninstalled both packages; recovery executed: both APKs `install -r` → Success ×2 (2 packages present),
    fresh baseline measured (both users false) → `pm grant --user 0` → **user0=true / user10=false verified**.
    **Root cause — DEVICE ENVIRONMENT (notification-shade overlay), not code:** the identical 3 failures reproduced
    standalone at 15:12-15:16 on the same APK and app data (NavigationTest 8 tests → the same 2 failures with the
    same 5000 ms timeouts; ThemePersistenceTest 7 tests → the same `luma=48`), the other 13 tests passing.
    Direct inspection at 15:18-15:21 then found `mCurrentFocus=Window{... u0 NotificationShade}` — **the
    notification shade was expanded over the app** (uiautomator dump contained only SystemUI nodes;
    `isKeyguardShowing=false`; animations 1.0; Gboard enabled; battery saver off; screen Awake + stay-on while
    charging; live session observation — transcript + a 15:40 mechanism re-creation persisted in
    `.investigation/phase14/README-shade-overlay.txt` + `shade-observed-state-dumpsys.txt`, R4-1). The overlay
    explains the full signature: the two Navigation failures are the IME-never-opens wait
    (the shade window holds input focus, so the focus request for the IME never happens), the theme screenshot
    samples the dark shade background (median luma=48 — byte-identical across round 2, round 3, and the 15:12
    standalone run, i.e. the same captured dark frame), while dark-expecting assertions
    (`launchWithAbsentKeysSettlesToDark`, `values-night` resource checks) and view-level semantics interactions
    (the other 408 suite tests, and the 13 green class-mates at 15:12) are unaffected. After
    `cmd statusbar collapse` (focus returned to `com.pahebatcher.app/.MainActivity`), the SAME 3 tests re-run
    standalone at 15:27 → **`OK (3 tests)` 4.823 s** (transcript persisted: `.investigation/phase14/README-shade-overlay.txt`, R4-1) — same APK, same data, minutes apart, environment the only
    variable. Caveats disclosed: logcat does not record panel-expand events and the overlay was not directly
    observed during rounds 2/3 themselves (the identical luma=48 frame across all three runs is the linkage);
    no source or test was edited; per the close-out instruction no further full-suite runs were launched.
    Baseline re-measured after the 15:27 run (these classes grant nothing): user0=true / user10=false — intact.
Gate results (round 1, over the §17 slice):
  - **TESTER GATE — PASS (2026-10-03):** independent run — `assembleDebug assembleDebugAndroidTest` BUILD SUCCESSFUL (EXIT=0,
    APKs newer than sources); `testDebugUnitTest --rerun` XML-parsed **70 suites / 719 tests / 0 / 0 / 0 skipped**;
    `lintAnalyzeDebug`+`lintReportDebug` XML **Fatal 0 / Error 0 / Warning 34 / Hint 1**; required 6 Phase 14 device classes
    `OK (15 tests)` EXIT=0 in 292.9 s (rows 419/421/423/422 + NotificationInteractionTest 3/3 + MediaSessionLifecycleTest 1/1);
    supplementary batch 55 run / 54 passed with 1 failure classified **environmental** (`WatchlistCheckWorkerTest` notification
    assertion outside its documented POST_NOTIFICATIONS precondition — `NotificationTest.kt:300-309`), proven green when run
    with its grantor class `OK (15 tests)`; all §20 spot-checks pass (bound 7, transport-gated park, 4-case matrix, 3 sites,
    **zero `addAction`**, single `ExoPlayer.Builder`, no TODO/FIXME, no Phase 15/16 leakage, real consumers, 4 channels,
    media3-session 1.11.1); citation checker re-run by tester **EXIT=0**; docs re-inspected first-hand.
    Tester disclosures: it installed both APKs for users 0/10 per AGENTS §20 (app was not installed at its gate start);
    a transient `pm grant --user 10` probe was run during diagnosis and the baseline was RESTORED (final dump shows
    `POST_NOTIFICATIONS: granted=false` for user 10). No source or test edited; no force-stop; no data clear.
    Tester could-not-verify items: the full 411-test suite (implemented separately — my XML-parsed run above), reviewer
    approval (out of tester scope), exhaustive denied-permission post probing (covered by NotificationPermissionTest).
Gate results (round 2 — reviewer re-review of round-1 documentation, 2026-10-04): **FAIL on H-1 + M-1**; L-1..L-4 advisory.
  - **H-1 (must fix):** §15 claimed the death-interrupted `runAttemptCount` increment was "asserted in an
    implementation-time device test" but no such assertion existed (suite cannot force-stop itself, LIMITATIONS §20).
    Resolution chosen: reviewer option (a) — extend the 11.5E-6 operator protocol with an explicit post-relaunch
    `runAttemptCount` capture/assertion and cite it in §15/§34.11 (NOT a waiver).
  - **M-1 (must fix):** `CancellationTest`'s resurrection round parked the worker, cancelled, then asserted via
    `Thread.sleep(2_000)` — passive wait, a late `finally` trigger would not be observed. Fixed with an ACTIVE
    trigger round (second newer QUEUED+m3u8 probe episode → its worker parks → the probe worker's `finally` trigger
    fires → assert CANCELLED row's WorkInfo ID-set unchanged + row still CANCELLED + all finished).
  - **L-1..L-4 (advisory):** L-1 `HANGING_URL` KDoc mechanism wording (CLEARTEXT fast-fail + in-run retry pacing,
    attributed inline as review L-1) + `CancellationTest` class KDoc reference completed; L-2
    `assertEquals(1, info.runAttemptCount)` precision; L-3 run-scoped IDs (`ep-pdr-attempt-$runStamp`,
    `ep-cancel-test-$runStamp`, `sess-cancel-test-$runStamp`, `triggerProbeId`); L-4 PHASE14_PLAN row 428 annotation.
  - **Affected-class device re-run — EXECUTED-PASSED:** `CancellationTest`, `ProcessDeathRecoveryTest`,
    `AutoResumeConnectivityTest`, `RealProcessDeathSeedTest`, `RealProcessDeathCleanupTest` → `OK (10 tests)`,
    `INSTRUMENTATION_CODE: -1`, 0 failures (androidTest APK rebuilt + `install -r` → Success first).
  - **H-1 extended operator protocol — EXECUTED-PASSED 2026-10-04 (device REDACTED-SERIAL):** seed (`-e leaveSeed true`)
    ran `ep-real-pd` with baseline `runAttemptCount == 1` recorded (PID 18134, request
    `1465b048-d249-4716-b447-1eef55f21cfd`); operator `am force-stop com.pahebatcher.app` mid-run
    (~01:27:39, process dead — `pidof` empty); `am start` relaunch (PID 18195, reconciler "Found 1 episodes",
    01:27:41); `RealProcessDeathCleanupTest -e verifyAttemptCount true` (PID 18271) asserted different PID,
    same request re-run with advanced count inside the bound → `OK (1 test)` 01:28:03 (TestRunner/logcat
    01:27:33–01:28:03, `Starting download work for ep-real-pd` observed twice: 01:27:33 seed run +
    01:28:03 rescheduled run). Protocol artifacts cleaned (record file deleted, row/session deleted).
  - **Doc updates — EXECUTED:** PHASE14_PLAN §15:387 (asserted-not-assumed + executed evidence), row 421 (in-suite
    vs protocol split), LIMITATIONS §34.11:2198-2208 (misleading "full force-stop cycle remains covered" sentence
    replaced by the extended-protocol description + executed evidence). `check-doc-citations.ps1` → **EXIT=0**.
Gate results (round 3 — reviewer re-review of round-2 documentation + fixes, 2026-10-04): **FAIL on NEW-1**;
H-1, M-1 and L-1..L-4 verified fixed; NEW-2/NEW-3/NEW-4 advisory. All findings dispositioned as follows:
  - **NEW-1 (must fix):** DECISIONS.md contained an out-of-scope "OpenCode Local Tooling Setup" section (1 CRLF line
    among 5303 LF) — not Phase-14 ledger material. **FIXED by relocation, not deletion:** the section moved verbatim
    to a new root file `TOOLING_SETUP.md` (root .md files are outside the citation checker's DocFiles set);
    DECISIONS.md is now 5303 pure-LF lines; `scripts/relocate-tooling-section.ps1` records the one-shot operation;
    `check-doc-citations.ps1` re-run → **EXIT=0** (verified twice by implementer + once by tester task 6).
  - **NEW-2 (advisory):** the retry-increment mechanism wording ("`Result.retry()` … `runAttemptCount` increments"
    read as increment-at-return) corrected in both places: PHASE14_PLAN:387 now states the ENQUEUED→RUNNING increment
    with the WorkInfo-N vs WorkerParameters-(N-1) two-view explanation (citing `DownloadWorker.kt:101`), and
    `DownloadWorker.kt:691-692` KDoc now says the increment happens when the retry run next starts, not at the
    `retry()` return (mechanism verified against WorkManager 2.11.2 in reviewer round 3). **FIXED.**
  - **NEW-3 (advisory):** no persisted H-1 protocol evidence artifact existed (original 01:27 logcat rotated out of
    the buffer). **FIXED:** the operator protocol was re-run 2026-10-04 13:42 with full capture into
    `.investigation/phase14/` (`README.txt` timeline, `h1-protocol-seed-instrument.txt` showing the real
    `Process crashed` death signal, `h1-protocol-cleanup-instrument.txt` `OK (1 test)`, `h1-protocol-logcat.txt`
    1956 lines with `Starting download work for ep-real-pd` at 13:42:02 [pre-kill PID 32223] and 13:42:32
    [rescheduled run], force-stop + WorkManager `ForceStopRunnable` reschedule evidence). **FIXED.**
  - **NEW-4 (advisory):** state-file wording "the CANCELLED row's `finally` trigger" corrected to "the probe
    worker's `finally` trigger" (the trigger fires from the parked probe worker, not from the CANCELLED row).
    **FIXED.**
  - **L-1 wording (advisory):** state-file "L-1 `HANGING_URL` KDoc caller list" overstated what exists — no caller
    list is in that KDoc; corrected to describe the actual content (mechanism wording: CLEARTEXT fast-fail + in-run
    retry pacing, attributed inline as review L-1). **FIXED.**
  - **H-1 protocol re-run note:** the first capture attempt (13:32) failed its record-file poll — the device's
    current Android user is 10 (work profile) and `adb shell run-as` runs in user 0 context, which cannot read
    `/data/user/10` app files (Android multi-user restriction). The v2 capture script therefore syncs on the
    logcat worker-start line instead; the seed's leftover state from attempt 1 was cleaned with a plain
    `RealProcessDeathCleanupTest` run (`OK (1 test)`) before the v2 run.
Gate results (tester round 3 — independent re-run after reviewer round-2 fixes, 2026-10-04): **PASS.**
  - Builds `assembleDebug assembleDebugAndroidTest` BUILD SUCCESSFUL (EXIT=0, 22 s up-to-date); JVM
    `testDebugUnitTest --rerun` XML-parsed **70 suites / 719 tests / 0 failures / 0 errors / 0 skipped**;
    lint XML **0 Fatal / 0 Error / 34 Warning / 1 Hint**; full device suite attempt 3
    `connectedDebugAndroidTest` BUILD SUCCESSFUL 15 m 34 s → XML `TEST-REDACTED-MODEL - 16.xml` **411 tests / 0 / 0 / 0** (path
    reused per connected run — at close-out this path holds the final-sources 411/3 round; R4-2)
    over 72 suites including the round-2 methods (`cancel_whileParkedInAutoResumeBackoff_…` 56.042 s;
    `ProcessDeathRecoveryTest` 85.053 s). Attempts 1 (adb transport glitch, 171-test partial) and 2 (server
    restart killed the run) disclosed by the tester and superseded by attempt 3. §20: both APKs `install -r`
    → Success, users 0+10, lastUpdateTime 13:04:53/54. Citation checker re-run → **EXIT=0**. Doc spot-checks
    (§15:387, row 421, §34.11:2194-2208, row 428) pass; static checks: 0 TODO/FIXME, 1 `ExoPlayer.Builder`,
    4 enqueue sites, 4 channels, 0 Phase 15/16 leakage, 0 `addAction`.
  - Tester disclosures: POST_NOTIFICATIONS restored to user 0 = true / user 10 = false baseline after a
    transient `pm revoke` misfire on user 0 (immediately reversed); AGP post-test uninstall re-covered by
    the §20 reinstall. Note: this round-3 PASS predates the comment-only `DownloadWorker.kt:691-692` KDoc
    fix (4 lines of comment text) — the background final-sources re-verification covers that delta.
Gate results (round 4 — final reviewer re-review of the round-3 fixes + final-verification record, 2026-10-04):
  **VERDICT: APPROVED** (0 must-fix, 4 advisory). The reviewer independently verified NEW-1 (DECISIONS.md 5303
  pure-LF, tooling content only in TOOLING_SETUP.md, checker DocFiles set confirmed, re-ran checker → EXIT=0),
  NEW-2 (disassembled `work-runtime-2.11.2.aar`: `WorkerWrapper.runWorker` reads `WorkSpec.runAttemptCount`
  BEFORE `trySetRunning()` increments it — the two-view wording at PHASE14_PLAN:387 + `DownloadWorker.kt:691-694`
  is technically correct), NEW-3 (H-1 artifacts in `.investigation/phase14/` match the §34.11/state claims:
  README timeline, seed `Process crashed`, cleanup `OK (1 test)`, 1956-line logcat, `-e verifyAttemptCount true`
  proven in the capture script), NEW-4 + L-1 (state-file wording), re-parsed all three verification XMLs
  (719/0/0/0; 0F/0E/34W/1H; 411/3 with all five §17 rows green), reproduced the static audit, confirmed the state
  file does NOT claim 411/0 for final sources, and found no regression. Advisory findings dispositioned in this
  close-out:
  - **R4-1 (evidence persistence):** `.investigation/phase14/README-shade-overlay.txt` created (full episode
    timeline with RAW vs TRANSCRIPT provenance labels), `shade-observed-state-dumpsys.txt` (15:40 mechanism
    re-creation: swipe-down → `mCurrentFocus=...NotificationShade` + 134 SystemUI nodes / 0 app nodes → swipe-up
    → focus restored), and `device-final-sources-round3-411-3.xml` (run-stamped snapshot of the 411/3 XML).
    The original 15:18 dump and the 15:27 shell .out were not capturable (overwritten by later dumps / cleaned
    by the shell runtime) and are labeled TRANSCRIPT in the README; the round-3 record now carries the same
    labels (R4-1).
  - **R4-2 (green XMLs overwritten):** `DECISIONS.md:4992` reworded in place — counts dated as the 2026-10-03
    run + path-reuse/overwrite annotation naming the close-out snapshot; state-file tester-round-3 and
    2026-10-03-suite XML references annotated likewise; line counts unchanged (checker re-run below).
  - **R4-3 (ROADMAP gate narration):** lines 794 / 823 / 833 / 914 updated in place — full gate history
    (round-1 APPROVED → rounds 2-3 FAIL → fixes → round-4 APPROVED; tester round-3 PASS) and the verification
    counts now distinguish the 2026-10-03 + tester-round-3 411/0 runs from the final-sources 411/3
    environmental result (411/0 is never written as the final-sources device number).
  - **R4-4 (static-audit wording):** reworded to "zero WebView/Kwik *code* references — the only mentions are the
    two KDoc boundary statements `DownloadWorker.kt:38,:43`".
Last successful verification (round 1):
  - **DOCUMENTATION RECONCILIATION + CITATION CHECK — EXECUTED-PASSED 2026-10-03:** `scripts/check-doc-citations.ps1`
    **PASS, EXIT=0** after all edits (baseline this session was EXIT=1 with 4 drift failures). Applied:
    *DECISIONS.md* — nine Phase 14 decisions appended (113 auto-resume+taxonomy+PHASE10 §6 amendment, 114 retry/backoff+PHASE10
    §14 supersession, 115 constraint matrix, 116 channels, 117 IDs, 118 tap-only actions, 119 watchlist aggregation + SPEC §18
    scope ruling, 120 MediaSession + AGENTS §17 PASS, 121 check-frequency), Decision 95.D citation re-pointed (:44/:47 →
    :36/:55) + supersession marker, R7-6 row re-pointed (`LIMITATIONS:1164`-`1165` historical + `LIMITATIONS:1168-1169`
    current); *PHASE10_ARCHITECTURE_V10.md* — §14 gated-supersession annotation + §6 candidate-skip amendment annotation
    (annotations only, no rewrite; zero citations into that file so no shifts); *LIMITATIONS.md* — `:1074` citation fixed
    (`DownloadWorker.kt:238-246` → `:244-264`), line 321 builders SUPERSEDED 2026-10-03 marker, `:1713-1715` interval-setting
    supersession (inexactness sentence at `:1711-1712` kept), §34.5 ownership ledger → SHIPPED, §34.6 auto-resume → shipped,
    new **§34.11** (P14-L1..L4 + fixture-origin sever deviation + row-421 simulated-death deviation + reboot gate unchanged),
    DECISIONS:3159 citation re-pointed (`LIMITATIONS:1164-1165` → `:1168-1169`); *ROADMAP.md* — Phase 14 Exit Criteria header
    → ALL verified with final counts, auto-resume exit item `[ ]` → `[x]` with all five §17 rows named, phase-index row 914
    "14. Notifications" PARTIAL → ✅ COMPLETE (R6-3 required phrase preserved inside it); *PARITY.md* — row 604 status → ✅,
    "Open gate" → "Gate closed 2026-10-03", "To reach ✅" → SATISFIED, evidence extended (411-test run + rows 419-423),
    `WatchlistCheckScheduler.kt:55-57` → `:50-54`, row 603 SPEC §18 example-coverage scope note appended in-cell,
    ledger lines 741/766 supersession markers, line 834 builders superseded marker. Conventions preserved: PARITY 911/911
    CRLF single-line edits; DECISIONS/LIMITATIONS/ROADMAP/PHASE10 all-LF; line-count shifts only where uncited.
  - **FULL DEVICE SUITE — EXECUTED-PASSED 2026-10-03 22:50:** `.gradlew connectedDebugAndroidTest` BUILD SUCCESSFUL, 22m 24s, EXIT=0;
    XML `app/build/outputs/androidTest-results/connected/debug/TEST-REDACTED-MODEL - 16.xml` parsed directly: **411 tests / 0 failures /
    0 errors / 0 skipped** (path reused per run — this file was overwritten by later rounds; at close-out it holds the
    final-sources 411/3 result, run-stamped snapshot at `.investigation/phase14/device-final-sources-round3-411-3.xml`,
    R4-2). All five §17 new methods verified present *in that XML* and green:
    `AutoResumeConnectivityTest.severedTransport_midDownloadParksThenAutoCompletesWithZeroRedownload` (row 419),
    `AutoResumeConnectivityTest.firstTransportFailure_parksQueuedAndKeepsRequestEnqueued` (row 420),
    `ProcessDeathRecoveryTest.rescheduledRequest_runAttemptCount_isPersistedIncrementedAndResetOnlyWhenRecreated` (row 421),
    `CancellationTest.cancel_whileParkedInAutoResumeBackoff_isTerminalAndNotResurrected` (row 422),
    `TriggerNextDeferredEpisodeTest.deferredLiveness_parkedAutoResumeRowDoesNotBlockLaterEpisode` (row 423).
  - **JVM SUITE — EXECUTED-PASSED 2026-10-03:** `testDebugUnitTest` XML parsed: **70 suites / 719 tests / 0 failures /
    0 errors / 0 skipped** (no `<skipped>` nodes anywhere — the historical "70 skipped" figure was a misread).
  - **LINT — EXECUTED-PASSED:** `lintDebug` XML parsed: **0 Error / 0 Fatal / 34 Warning / 35 issues total** (1 informational).
  - **BUILDS + DEPLOY — EXECUTED-PASSED:** `assembleDebug assembleDebugAndroidTest` BUILD SUCCESSFUL (EXIT=0);
    `adb devices` → one `device`-state serial `REDACTED-SERIAL`; both APKs `install -r` → Success (AGENTS §20).
  - §17 row 421 single-class re-run: `ProcessDeathRecoveryTest` `OK (4 tests)` 86.007 s (pre-suite).
  - §17 rows 419/420/422/423 class runs: `OK (7 tests)` 200.698 s + `AutoResumeConnectivityTest` `OK (2 tests)` 85.488 s (pre-suite).
  - **Reviewer gate — APPROVED (first round)** covered M0–M7 + M1 fix; a **re-review of the §17 test slice is RUNNING** (background).
  - **Tester gate — PASS 12/12 (first round)** predates the §17 slice; a **re-run is RUNNING** (background).
Last failed verification:
  - row 421 first attempt — EXECUTED-FAILED (step-3 poll caught a RUNNING worker → cancel persisted terminal CANCELLED →
    reconciler found 0 episodes). Fixed by sampling only the parked state + asserting row stays QUEUED; re-run green.
  - `scripts/check-doc-citations.ps1` baseline this session — **EXIT=1, 4 problems** (line-number drift, NOT content errors):
    `DECISIONS:4330`→`DownloadNotificationManager.kt:44` blank; `DECISIONS:4331`→`:47` blank;
    `LIMITATIONS:1074`→`DownloadWorker.kt:238` blank (handler now `:244-264`); `PARITY:604`→`WatchlistCheckScheduler.kt:55` blank
    (statement now `:54`). Repair planned during doc reconciliation; checker must end EXIT=0.
  - Earlier: 5× suspend-call compile errors in androidTest (fixed via `isParkedForAutoResume`/`rowIs`); one transient
    `assembleDebugAndroidTest` failure with no diagnostics (re-run OK); M6/M7 failures (MediaSession.ControllerInfo,
    MissingSuperCall, UnsafeOptInUsageError, stale androidTest APK, 5-vs-4 channels, FGS timeout) — all fixed earlier.
Known failures: none open
Known limitations:
  - L2 (pre-existing): suite notification permission depends on FirstEnqueuePromptTest order
  - media3 `UnstableApi` `@OptIn` quirk (harmless warning)
  - Reviewer L1–L4 (LOW): dispositioned acceptable
  - **§17 row 421 / LIMITATIONS §20:** true force-stop cannot be staged from inside an instrumentation run (the suite runs
    in the app process). In-suite: REAL WorkManager request + real transport failure + real retry park + real
    WorkManager reschedule with advanced `runAttemptCount` + real reconciler recreation; only the kill step uses the class's
    documented simulated-death technique. → documented in LIMITATIONS §34.11; the real force-stop death-interrupted increment
    is asserted by the extended 11.5E-6 operator protocol (executed 2026-10-04, PASSED — see round-2 gate results).
  - **Connectivity sever applied at fixture origin** (loopback HTTP server, debug-only cleartext for 127.0.0.1/localhost),
    not at the device radio (API 36, no shell/root from instrumentation); production path unmodified.
    → recorded in DECISIONS (Decision 121 context) + LIMITATIONS §34.11.
  - P14-L1..L4 (plan §21) as designed.
Static cleanup audit (this session): no TODO/FIXME in `app/src`; exactly one `ExoPlayer.Builder` (AppViewModelFactory:101);
WorkManager enqueues = 3 unique episode sites + 1 unique periodic site (no duplicate scheduling); DownloadWorker has zero
WebView/Kwik *code* references — the only mentions are the two KDoc boundary statements `DownloadWorker.kt:38,:43` (R4-4);
4 app channels in `NotificationChannels`; no fake/placeholder code.
Doc conventions verified: DECISIONS/LIMITATIONS/ROADMAP/PHASE10 = LF; PARITY = all-CRLF (911/911).
Exact action just completed: reviewer round 4 returned **VERDICT: APPROVED** (0 must-fix; advisories R4-1..R4-4);
R4-1 evidence persisted under `.investigation/phase14/` (README-shade-overlay.txt with RAW/TRANSCRIPT provenance,
shade-observed-state-dumpsys.txt mechanism re-creation, device-final-sources-round3-411-3.xml XML snapshot), R4-2
DECISIONS:4992 + state-file XML-path annotations applied in place, R4-3 ROADMAP:794/823/833/914 gate history
narrated in place, R4-4 static-audit wording corrected; citation checker re-run after the DECISIONS/ROADMAP edits
→ **EXIT=0**.
Exact next action: deliver the final Phase 14 report in §15 format (Files Changed, Implementation, Tests, Build,
Reviewer Gate, Tester Gate, Documentation, Phase 14 Status, Limitations, Phase 15) ending with
`Phase 15 was NOT implemented.` — then STOP (shutdown command removed from the close-out list per user instruction).
Last checkpoint: 2026-10-04 — Phase 14 M8 CLOSED. Reviewer round 4 APPROVED, tester round 3 PASS, final-sources
re-verification complete (JVM 719/0/0/0 PASS, lint 0F/0E/34W/1H PASS, device 411/3 with environmental root cause +
standalone 3/3 green), §20 + baseline verified, citation EXIT=0.

---

## §12 — Final state (close-out template)

Status: COMPLETE

- Phase: 14
- Closed: 2026-10-04
- Reviewer gate: **APPROVED** (round 4; history: round 1 APPROVED → rounds 2-3 FAIL → fixes → round 4 APPROVED;
  4 round-4 advisories R4-1..R4-4 dispositioned with persisted evidence)
- Tester gate: **PASS** (round 3: builds EXIT=0, JVM 70/719/0/0/0, lint 0F/0E/34W/1H, device 411/0/0/0 over 72
  suites, §20 installs both users, citation EXIT=0 — 2026-10-04)
- Final-sources re-verification: JVM **70 suites / 719 / 0 / 0 / 0** EXECUTED-PASSED; lint **0 Fatal / 0 Error /
  34 Warning / 1 Hint** EXECUTED-PASSED; device **411 tests / 3 failures** EXECUTED-FAILED-then-root-caused —
  all 3 failures are device-environment (notification-shade overlay; evidence:
  `.investigation/phase14/README-shade-overlay.txt`), the same 3 tests EXECUTED-PASSED standalone (`OK (3 tests)`)
  after shade collapse, all five §17 rows green in the round-3 XML; builds + §20 install EXECUTED-PASSED;
  citation check EXIT=0 EXECUTED-PASSED
- Deployment: both APKs installed to `REDACTED-SERIAL` (`install -r` Success ×2, 2 packages), POST_NOTIFICATIONS baseline
  user0=true / user10=false verified
- Known limitations: documented in LIMITATIONS §34.11 + the Known limitations list above (permission-baseline
  structural grant, simulated in-suite death step, fixture-origin sever, P14-L1..L4)
- Phase 15: not started (out of scope for this close-out)
