# Play Policy Findings — PaheBatcher Android (`com.pahebatcher.app`)

**Skill:** `play-policy-insights` (installed, not reinstalled)
**Audit runs:** this file records the **initial** run and, below, the **final** re-run comparison.
**Initial scratch dir:** `.scratch/play_policy_insights_d1c24f36-3b8f-4049-9786-ad9a4949b887` (repository-relative)
**Scan date:** 2026-10-24 · **Target SDK:** 37 · **Package:** `com.pahebatcher.app`

> **Scope of this document.** This is an internal remediation ledger for an automated
> static audit. It does **not** claim, guarantee or predict Google Play Store approval.
> The scanner's own disclaimer applies: informational/advisory only, the Google Play
> Review team has final authority.

---

## A. How the audit was run (evidence)

Execution mode: **Mode A (Delegation)** — the environment provides general-purpose
sub-agents, so the skill's delegation protocol was used (batches ≤ 3 concurrent),
with every worker/critic output validated as parseable JSON on disk before advancing.

| Step | Command / action | Result |
|---|---|---|
| Init | `python3 <skill>/scripts/orchestrator.py init <repo_root>/app` | **exit 0**; `.scratch/play_policy_insights_d1c24f36-…` created |
| Activated goals | from init JSON | `data_safety_part_1..4`, `permissions_and_apis`, `user_account` (**6**) |
| Workers (6) | delegated, each wrote `worker_<goal>.json` | all **6 VALID JSON** |
| Aggregate | `python3 <skill>/scripts/orchestrator.py aggregate <temp_dir>` | **exit 0**; `critic_chunks: 1` |
| Critic (1) | delegated, wrote `critic_output_1.json` | **VALID JSON** |
| Report | `python3 <skill>/scripts/generate_report.py <temp_dir>` | **exit 0**; `compliance_report.md` |

**Environment note (not a finding):** the host has no Windows Python and WSL cannot
start (`HCS_E_SERVICE_NOT_AVAILABLE`), so a **portable embeddable Python 3.13.17** was
placed in a temporary directory outside the repository (exact path withheld — no
personal paths are recorded in this repository) to run the skill's own unmodified scripts. The orchestrator's third-party
imports are stdlib-only, so the real automated audit ran unmodified. No result was
substituted by manual auditing.

**Worker finding totals:** 13 findings — 1 CRITICAL, 1 IMPORTANT, 11 SUGGESTION.
**Overall scanner status after critic:** 🟢 Compliant (2 advisory suggestions surfaced).

---

## B. Initial findings and classification (Stage 3)

Classification key:
**Group A** = safe to fix automatically · **Group B** = needs human approval ·
**Group C** = false positive / not applicable (evidence kept) · **Group D** = unresolved.

---

### F-01 — `*/*` pattern matches read as file/document access
- **Severity:** 🔵 SUGGESTION · **Domain:** Data Safety and Privacy
- **Path:** `app/src/debug/java/com/pahebatcher/app/data/remote/validation/KwikValidator.kt:58`, `…/OkHttpValidator.kt:133`
- **Classification:** **Group C (not applicable — heuristic false positive).**
- **Evidence:** the `*/*` is the static HTTP `Accept` request header
  (`text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8`). Both
  validators GET kwik/animepahe HTML and parse M3U8 URLs locally; no user file path or
  document content is ever sent. The scanner itself recorded this as
  "Data Safety Compliant (Local-Only)".
- **Remediation decision:** no change (nothing to fix).
- **Files changed:** none · **Verification:** scanner's own local-only verdict + code paths cited.
- **Final status:** **not applicable — kept visible.**

### F-02 — `uid` pattern matches read as user IDs
- **Severity:** 🔵 SUGGESTION · **Domain:** Data Safety and Privacy
- **Path:** `app/src/main/java/com/pahebatcher/app/data/parser/AnimePaheParser.kt:80-85`; `__cfduid` literal in `app/src/debug/…/WebViewSessionValidator.kt:167`; `HomeScreen.kt` substring-in-`guidance`.
- **Classification:** **Group C (not applicable).** The `uid` hits are: a Cloudflare
  per-device cookie *name*, an **anime content** UUID (identifies a title, not a
  person), and the substring `uid` inside the word "guidance". No account/login flow exists.
- **Remediation decision:** no change. · **Files changed:** none.
- **Final status:** **not applicable — kept visible.**

### F-03 — Web Browsing History may need a Data Safety declaration
- **Severity:** 🟡 IMPORTANT → **downgraded to 🔵 SUGGESTION (critic: `MANUAL_REVIEW`)** · **Domain:** Data Safety and Privacy
- **Path (cited):** `app/src/debug/java/com/pahebatcher/app/data/remote/validation/WebViewSessionValidator.kt:143`, `app/src/debug/…/ui/validation/ValidationScreen.kt:109-124`, gated at `app/src/main/java/com/pahebatcher/app/MainActivity.kt:168,176`.
- **Classification:** **Group B (needs human approval — privacy/Data Safety declaration).**
- **Evidence (independently re-verified by me):** the originally cited path is
  **debug-only and never ships** — `MainActivity.kt:168` is
  `if (BuildConfig.DEBUG && showValidation)`, `MainActivity.kt:176` guards the setter
  with `if (BuildConfig.DEBUG)`, the spikes live only in `src/debug`, and `src/release`
  ships a documented no-op `ValidationOverlayEntry`. However, the **shipped** app does
  the equivalent navigation in `src/main`: `AndroidCloudflareSession.kt:366`
  (`wv.loadUrl(ANIMEPAHE_BASE_URL)`) and `AndroidWebViewRequestBridge.kt` (request URLs
  carrying session cookies/User-Agent). Whether that must be declared as "Web Browsing
  History" depends on the **Play Console Data Safety form and privacy policy**, which
  cannot be decided from the repository.
- **Remediation decision:** **no code change** — the debug exclusion the critic asked
  for is already in place; the remaining question is a declaration decision for the user.
- **Files changed:** none · **Verification:** source reads cited above; critic verdict `MANUAL_REVIEW`.
- **Final status:** **OPEN — awaits user decision** (see §D, Q1).

### F-04 — "Silent Background Transfer" (quiet session refresh) flagged as prominent-disclosure violation
- **Severity:** 🔴 CRITICAL → **PRUNED by critic** · **Domain:** Data Safety and Privacy (prominent-disclosure policy)
- **Path (cited):** `app/src/main/java/com/pahebatcher/app/ui/sessionprompt/SessionRefreshViewModel.kt:126-151`, `app/src/main/java/com/pahebatcher/app/di/AppContainer.kt:217-220`, `app/src/main/java/com/pahebatcher/app/data/session/AndroidCloudflareSession.kt:366`.
- **Classification:** **Group C (false positive — with evidence).**
- **Evidence (independently re-verified by me):** the behavior is the **SPEC-mandated**
  session-recovery design, not covert collection: `SPEC.md:386` requirement *“10. Prompt
  the user to refresh the WebView session instead of silently failing.”* and
  `SessionRefreshViewModel.kt:43-47` KDoc *“**One quiet attempt, then prompt** (SPEC
  6.10 refinement): the first recovery requirement … runs [forceRefresh] silently; a
  success shows NOTHING, a failure raises this dialog.”* The traffic goes only to the
  app's own content service (URL + session cookie + User-Agent) during user-servicing
  request paths; prominent-disclosure rules cover personal/sensitive data outside
  reasonable expectation. Debug-only OkHttp replay never ships (see F-03 evidence).
- **Remediation decision:** **no change** — accepting the critic's `PRUNED` verdict.
- **Files changed:** none · **Verification:** critic `PRUNED` + my SPEC/code reads.
- **Final status:** **not applicable — pruned, evidence retained.**

### F-05 … F-09 — "Data Safety Compliant (Local-Only)" statements
- **Severity:** 🔵 SUGGESTION (positive compliance verdicts, not defects) · **Domain:** Data Safety and Privacy
- **Paths:**
  - F-05 `record`/`audio` hits → `app/src/main/java/com/pahebatcher/app/data/local/…` state-of-record KDoc; no `RECORD_AUDIO` permission in `app/src/main/AndroidManifest.xml`.
  - F-06 `WorkInfo` read before re-enqueue → `app/src/main/java/com/pahebatcher/app/data/download/DownloadStateReconciler.kt:336-337`.
  - F-07 `WorkInfo` state for deferred scheduling → `app/src/main/java/com/pahebatcher/app/worker/DownloadWorker.kt:1310-1352`.
  - F-08 video export / playback → `app/src/main/java/com/pahebatcher/app/data/export/MediaStoreExporter.kt:473-548`, `…/playback/PaheMediaSessionService.kt`.
  - F-09 `playlist`/`track`/`race` keyword hits → `EpisodeDownloader.kt`, `HlsHostConcurrencyLimiter.kt`, `AppLog.kt` (`stack trace`).
- **Classification:** **Group C (not applicable — verified local-only, no transmission).** No
  network sink exists for these paths (no analytics/crash SDK in `gradle/libs.versions.toml`; `AppLog` is logcat + in-memory buffer only).
- **Remediation decision:** no change. · **Files changed:** none.
- **Final status:** **not applicable — kept visible.**

### F-10 — Play Console declaration required for the **dataSync** foreground service
- **Severity:** 🔵 SUGGESTION · **Domain:** Permissions and APIs (Foreground Services)
- **Path:** `app/src/main/AndroidManifest.xml:8-9,34-38`; `app/src/main/java/com/pahebatcher/app/worker/DownloadWorker.kt:642-647`.
- **Classification:** **Group B (needs human action — Play Console declaration, not a code fix).**
- **Evidence:** manifest declares `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`
  and `androidx.work.impl.foreground.SystemForegroundService` with
  `foregroundServiceType="dataSync"`; `DownloadWorker.kt:642-647` builds real
  `ForegroundInfo(… DATA_SYNC)` + `setForeground()` with a genuine progress
  notification. The declaration is correct in-code; the missing piece is the mandatory
  **Play Console App-content FGS declaration** (target SDK 37), which only the user can submit.
- **Remediation decision:** **no manifest change** (already correct and required by the
  working download pipeline — removing it would break production).
- **Files changed:** none · **Verification:** manifest + worker source reads; scanner verdict.
- **Final status:** **OPEN — Play Console action for user** (see §D, Q2).

### F-11 — Play Console declaration required for the **mediaPlayback** foreground service
- **Severity:** 🔵 SUGGESTION · **Domain:** Permissions and APIs (Foreground Services)
- **Path:** `app/src/main/AndroidManifest.xml:11,44-52`; `app/src/main/java/com/pahebatcher/app/playback/PaheMediaSessionService.kt:248-257`.
- **Classification:** **Group B (needs human action — Play Console declaration).**
- **Evidence:** manifest declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and
  `.playback.PaheMediaSessionService` with `foregroundServiceType="mediaPlayback"` +
  the media3 `MediaSessionService` intent-filter; `PaheMediaSessionService.kt:248-257`
  calls `startForeground(… MEDIA_PLAYBACK)`; media3's `DefaultMediaNotificationProvider`
  owns the persistent notification. No `specialUse` type, so no property justification needed.
- **Remediation decision:** **no manifest change.** · **Files changed:** none.
- **Final status:** **OPEN — Play Console action for user** (see §D, Q2).

### Group D (unresolved/ambiguous)
- **None.** No finding was left ambiguous by the audit.

---

## C. Group A fixes (safe, automatic)

**None identified.** Every one of the 13 findings resolved into Group B (3 human
actions) or Group C (10 not-applicable/pruned, evidence retained). Specifically:

- The only *code-adjacent* recommendation (keep the debug `ValidationScreen` out of
  release) was verified to **already be satisfied** — `MainActivity.kt:168` +
  `src/release` no-op twin + spikes confined to `src/debug`.
- The two foreground-service findings are **not** manifest defects: both
  `foregroundServiceType` attributes and their permissions are present and required by
  working production features. Removing or "fixing" them would break downloads or media
  playback, so they are not Group A.
- The one CRITICAL was pruned with evidence; the one IMPORTANT was downgraded to a
  declaration question that is inherently a user decision.

**Files changed by Stage 3: none** (no production, test, config or doc file required a
policy-driven edit).

---

## D. Questions requiring the user's decision (Group B)

- **Q1 (F-03) — Data Safety declaration:** Does the Play Console Data Safety form (and
  the app's privacy policy, if any) declare **Web Browsing History** collected for *App
  Functionality* to cover the production Cloudflare session flow
  (`AndroidCloudflareSession.kt:366`, `AndroidWebViewRequestBridge.kt`)? The user must
  decide/confirm; no in-repo action is possible or safe.
- **Q2 (F-10, F-11) — Play Console FGS declarations:** Submit the **dataSync** and
  **mediaPlayback** foreground-service declarations (App content section, with
  functional descriptions and user-impact video) before any internal-testing upload.
  Only the user can do this in Play Console.
- **Blocking impact:** none of Q1/Q2 blocks a **repository push**; they block a
  **Play Console upload/submission** until the user completes them.

---

## E. Final comparison (after re-run — Stage 4)

**Re-run scratch dir (preserved alongside the initial run):**
`.scratch/play_policy_insights_58ddc260-9750-415a-817a-bff0a4c363ae` (repository-relative)
Re-run pipeline: init **exit 0** → **same 6 activated goals** → 6 workers **all VALID
JSON** → aggregate **exit 0** (`critic_chunks: 1`) → critic **VALID JSON** →
`generate_report.py` **exit 0**. **No goal could not be executed; no required output was
missing.**

### Fixed and verified
- **None (0).** No Group A fix existed to make — Stage 3 required no source change —
  so there is nothing that could regress. **Files changed by the policy work: none.**

### Findings by run (honest variance note)
The static scanner inputs are deterministic, but the **worker and critic steps are
LLM-executed**, so finding *counting and packaging* differ between runs while the
*substantive conclusions converge*. The initial run's findings are **preserved** in its
own scratch dir; nothing was deleted.

| Item | Initial run (13 findings) | Final run (12 findings) | Net classification |
|---|---|---|---|
| Overall status | 🟢 Compliant | 🟢 Compliant | unchanged |
| "Silent Background Transfer" (quiet refresh) | 🔴 CRITICAL → critic **PRUNED** | **not re-raised** by the worker | **Group C** — not applicable (SPEC 6.10 evidence, §B F-04) |
| Web Browsing History (WebView URL to animepahe) | 🟡 IMPORTANT → critic **MANUAL_REVIEW** | 🟡 IMPORTANT → critic **PRUNED** (user-initiated, transient, not retained → exempt) | **Group C in final run**; optional declaration note kept in §D Q1 |
| Foreground-service declarations (dataSync + mediaPlayback) | 2 separate 🔵 SUGGESTIONs | 1 merged 🔵 SUGGESTION (both types, same evidence + a POST_NOTIFICATIONS runtime-request reminder) | **Group B** — same human action, packaging differs |
| "Data Safety Compliant (Local-Only)" statements | 9 🔵 | 10 🔵 | **Group C** — not applicable |
| User Account and Identity | 0 findings | 0 findings | no findings both runs |

### By required category
- **Findings fixed and verified:** **0** — no in-repo defect was found to fix; the one
  code-adjacent ask (keep the debug validation screen out of release) was verified to
  **already hold** (`MainActivity.kt:168` `BuildConfig.DEBUG` gate + `src/release` no-op
  `ValidationOverlayEntry` + spikes confined to `src/debug`).
- **Findings judged not applicable with evidence:** **10 (final run)** / **10 (initial)**
  — all local-only pattern-match false positives plus the pruned disclosure claim; the
  supporting code paths are cited in §B (F-01, F-02, F-04…F-09).
- **Findings still unresolved (code):** **0** — no CRITICAL/IMPORTANT survives in the
  final run; no remediation of the codebase is outstanding.
- **Findings requiring user approval:** **1 primary + 1 optional** — **Q2** (mandatory
  Play Console FGS declarations for `dataSync` + `mediaPlayback`, which no in-repo change
  can satisfy) and **Q1** (optional Data Safety confirmation for the production WebView
  session flow; the final critic judged it exempt, so the user may simply confirm no
  declaration is needed). Both block **Play Console submission only**, not a repository push.
- **Goals that could not be executed:** **none.**

### What a clean audit does and does not mean
Both runs returned **🟢 Compliant** with zero code-level violations. This is evidence
for the analyzed policy domains only (data safety, user account, permissions/APIs); per
the tool's own disclaimer it is **not** a guarantee of Play Store approval, and the two
Play Console declaration tasks (Q1/Q2) remain outstanding human actions before any
internal-testing upload.

---

## F. Release-prep remediation package (privacy-first public release preparation, Stage 8, 2026-10-24)

This section consolidates the actionable output of sections B-E into one owner-facing
package, re-verified against the **current** tree (post per-ABI split, post
signing skeleton). **No code, manifest or behavior change was made or is
recommended**: the audit found zero code-level violations, and changing the
download/player/session paths to silence a policy scanner is explicitly out of
scope. Everything below is evidence plus a Play Console action list.

### F.1 Foreground-service evidence (re-verified 2026-10-24)

Verified in the merged **release** manifest produced by the current build
(`app/build/intermediates/merged_manifests/release/.../AndroidManifest.xml`)
and in source:

| FGS type | Manifest component | Code evidence | Play declaration needed |
|---|---|---|---|
| `dataSync` | `androidx.work.impl.foreground.SystemForegroundService` (`foregroundServiceType="dataSync"`, not exported) | `DownloadWorker.kt:642-647` - real `ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` + `setForeground()` with a genuine progress notification | **Yes** - App content > Foreground services declaration |
| `mediaPlayback` | `com.pahebatcher.app.playback.PaheMediaSessionService` (`foregroundServiceType="mediaPlayback"`, exported for media3) | `PaheMediaSessionService.kt:248-256` - `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` on the no-session exit path; media3 owns the normal promotion | **Yes** - App content > Foreground services declaration |

Permissions present in the merged release manifest (complete list):
`INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`. No `specialUse`
FGS type is declared, so no property-justification form applies.

### F.2 Data Safety inventory (facts from the repository, for the Play form)

| Data category (Play taxonomy) | Collected? | Processed? | Transmitted? | Where / retention | Notes for the declaration |
|---|---|---|---|---|---|
| App activity (user-initiated network requests to the content site/CDN) | No | Yes | Yes - user-initiated only | not retained | Search/resolve/download requests; no history leaves the device |
| Web browsing session (WebView loads the site for Cloudflare session recovery) | No | Yes | Yes - transient | cookies held in-memory/WebView jar only | Final critic judged exempt (user-initiated, not retained); optional confirmation per Q1 |
| Files and media (episodes the user downloads/exports) | No | Yes | No (written locally) | MediaStore `Movies/` or user-picked folder | user-requested output only |
| Device IDs / analytics / crash logs | **No** | No | **No** | `AppLog` = logcat + in-memory buffer (500 entries), release drops DEBUG/INFO | no analytics/crash SDK exists in `gradle/libs.versions.toml` (re-verified this round) |
| Account / personal identifiers | **No** | No | No | none - no login exists | scanner: User Account and Identity - 0 findings in both runs |
| Notifications | No | Yes | No | local progress/watchlist notifications | `POST_NOTIFICATIONS` requested at runtime |
| Backup | disabled (`allowBackup=false`) | - | - | - | no cloud backup of app data |

### F.3 Owner action checklist (Play Console - only the maintainer can do these)

1. **Foreground services declarations** (mandatory at target SDK 37): submit the
   `dataSync` and `mediaPlayback` declarations in Play Console > App content >
   Foreground services, using the functional descriptions and evidence in F.1.
2. **Data Safety form**: fill it from the F.2 inventory; decide the optional
   Web Browsing History confirmation (Q1 - the final critic judged it exempt).
3. **Privacy policy URL**: required by Play for any declaration; none exists in
   this repository - the maintainer must host one.
4. **POST_NOTIFICATIONS**: already requested at runtime (no action beyond the
   standard Play notification-permission review).
5. None of these blocks a **repository push**; they block a **Play Console
   upload/submission** only.

### F.4 Prohibitions honored this round

No frozen search/Cloudflare/JSON parsing path was modified. No download or
player behavior was changed to silence policy. No finding required removing
historical evidence; sections B-E are preserved as written.

