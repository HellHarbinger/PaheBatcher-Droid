# Archive Index — Superseded Phase Reports

> **Role:** provenance record for the historical phase reports relocated from the repository
> root to `archive/phase-reports/` on **2026-10-22**. Content was moved, never edited, deleted,
> or rewritten: every file below is **byte-for-byte identical** to its original (SHA-256
> verified before and after the move). No archival banner was added to any archived file.
> Canonical documents (`AGENTS.md`, `SPEC.md`, `DECISIONS.md`, `ROADMAP.md`, `PARITY.md`,
> `LIMITATIONS.md`, `ANDROID_*.md`, `PROJECT_REFERENCE.md`, `REPOSITORY_ARCHAEOLOGY.md`,
> `TOOLING_SETUP.md`, plus the authoritative `PHASE10_ARCHITECTURE_V10.md` and
> `PHASE11_ARCHITECTURE_V2.md`) were **not** moved.

## Index

| Original path | Archived path | Reason for supersession | Authoritative replacement |
|---|---|---|---|
| `PHASE3_REPORT.md` (5,581 B) | `archive/phase-reports/PHASE3_REPORT.md` | Early-phase implementation report (2026-09-21); its completion record and every live decision were absorbed into the canonical ledger; several implementation details are contradicted by current source | Phase 3 chain-of-record in `ROADMAP.md` (phase chain at `ROADMAP.md:1159`) + `DECISIONS.md` 11–26 + `PARITY.md` §8 |
| `PHASE5_VALIDATION.md` (7,273 B) | `archive/phase-reports/PHASE5_VALIDATION.md` | Phase 5 validation-spike results (2026-09-21); superseded by the "IMPLEMENTED — Audit Fixed" phase record and the decisions it produced | `ROADMAP.md:1159` (Phase 5 record) + `DECISIONS.md` 11–26 (FFmpegKit, WebView/Cloudflare, storage, OkHttp) |
| `PHASE8_AUDIT_REPORT.md` (24,347 B) | `archive/phase-reports/PHASE8_AUDIT_REPORT.md` | Phase 8 readiness/architecture audit (2026-09-22); an unreferenced early-phase report whose remediation is recorded in the canonical ledger and several of whose claims are contradicted by current source | `ROADMAP.md:1159` (Phase 8 COMPLETE) + `DECISIONS.md` 27–40 |
| `PHASE10_ARCHITECTURE_V8.md` (56,595 B) | `archive/phase-reports/PHASE10_ARCHITECTURE_V8.md` | Self-labeled "⚠ SUPERSEDED — HISTORICAL DOCUMENT" banner inside the file; 8th architecture-review draft known to contain content corrected in later revisions | `PHASE10_ARCHITECTURE_V10.md` (approved Phase 10 architecture; states the supersession at `PHASE10_ARCHITECTURE_V10.md:3`) |
| `PHASE10_ARCHITECTURE_V9.md` (44,756 B) | `archive/phase-reports/PHASE10_ARCHITECTURE_V9.md` | Self-labeled "⚠ SUPERSEDED — HISTORICAL DOCUMENT" banner inside the file; 9th review draft, retained as review history only | `PHASE10_ARCHITECTURE_V10.md` (`PHASE10_ARCHITECTURE_V10.md:3`) |
| `PHASE11_ARCHITECTURE_V1.md` (55,948 B) | `archive/phase-reports/PHASE11_ARCHITECTURE_V1.md` | Self-labeled "⚠ SUPERSEDED BY `PHASE11_ARCHITECTURE_V2.md` (2026-09-23)" banner with an explicit false-claim list; historical draft only, never authoritative | `PHASE11_ARCHITECTURE_V2.md` (states the supersession at `PHASE11_ARCHITECTURE_V2.md:6`) |
| `PHASE11_HANDOFF.md` (12,661 B, archived 2026-10-22 in the sequential-maintenance round) | `archive/phase-reports/PHASE11_HANDOFF.md` | Self-labeled "⚠ HISTORICAL RECORD — SUPERSEDED" interruption record (captured 2026-09-25); the session it handed off was resumed and Phase 11 completed — nothing in it reflects current state. Its one live citation role is corroborating the 114,869,141-byte Phase 11 debug-APK baseline at `PHASE11_HANDOFF.md:63` | `PARITY.md` (Phase 11 Completion Status) + `ROADMAP.md` (Phase 11 / 11.5) for status; the baseline figure itself is corroborated at `ROADMAP.md:546`/`:584`, `PARITY.md:872` and `DECISIONS.md` 1626/1846/2292 |

## Citation integrity after the move

The citation checker (`scripts/check-doc-citations.ps1`) resolves `FILE:NNNN` source
citations through a recursive leaf-name index of the repository that excludes only build
output (`scripts/check-doc-citations.ps1:263-287`) — `archive/` is **not** excluded. The
single live `:NNNN` citation into an archived architecture draft, `DECISIONS.md:4984` →
`PHASE11_ARCHITECTURE_V1.md:340`, therefore still resolves (to the archived copy) and still
validates its target line; no citation in any protected document was changed. Plain prose
mentions in protected documents (`DECISIONS.md:2239`, `DECISIONS.md:2296`,
`PARITY.md:1056`, `PHASE10_ARCHITECTURE_V10.md:3`, `PHASE11_ARCHITECTURE_V2.md:6`,
`:1243`, `:1272`) are dated historical statements that remain accurate regardless of file
location and were deliberately left untouched.

`PHASE11_HANDOFF.md` is the one archived file the checker names explicitly: it is a
doc-citation TARGET (not a scanned source) via `$ExtraCiteFiles`. On 2026-10-22 that entry's
path was repointed in place (`scripts/check-doc-citations.ps1:97-103`, path line `:102`) to
`archive/phase-reports/PHASE11_HANDOFF.md`, so the three doc-citations into it
(`ROADMAP.md:584`, `DECISIONS.md:2292`, `DECISIONS.md:3090` — all citing
`PHASE11_HANDOFF:63`) keep resolving and validating exactly as before; coverage is
identical, not reduced. The 114,869,141-byte APK-baseline prose-figure pin is unaffected:
it scans only the five primary documents (`scripts/check-doc-citations.ps1:564-590`,
baseline pin at `:569`), all of which remain in the root, and the handoff was never inside
its scan set.

## Verification recipe

To confirm an archived file is unmodified relative to its original:

```powershell
Get-FileHash archive/phase-reports/<name>.md -Algorithm SHA256
```

Compare against the SHA-256 recorded at archival time (2026-10-22), listed in the table's
provenance above (sizes) and reproducible from the move-time verification: all six hashes
matched pre-move exactly (PHASE3 `3617D7B0…`, PHASE5 `242C903A…`, PHASE8 `3B327D34…`,
V8 `6144DF69…`, V9 `62A6AD93…`, V1 `83651690…`).
