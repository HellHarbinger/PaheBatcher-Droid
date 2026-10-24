# Tooling Setup Decisions

> Relocated from DECISIONS.md (the PaheBatcher architectural decision ledger,
> AGENTS.md section 14) on 2026-10-04: this entry documents OpenCode/mubeng
> tooling configuration, not PaheBatcher architecture.
## OpenCode Local Tooling Setup

### Decision: All free models allowed + mubeng proxy routing

**Date**: 2026-10-04
**Status**: Completed
**Context**: Project-local OpenCode config should not restrict any model (all zero-cost models from every connected provider remain selectable), and outbound traffic should route through the mubeng IP rotator.
**Decision**:
- opencode.jsonc: no enabled_providers/disabled_providers, no provider whitelist/lacklist, and an explicit experimental.policies allow for provider.use: *.
- Proxy applied via environment because opencode config has no general env field: launch via scripts\opencode-via-mubeng.ps1, which starts mubeng (<mubeng-dir>\mubeng.exe, v0.23.0, -a localhost:8089 -f <mubeng-dir>\proxies.txt -r 1 -m random) and sets HTTP(S)_PROXY/ALL_PROXY plus NO_PROXY for localhost. [**NOTE 2026-10-22**: this launch wrapper is **not present in the repository** — `scripts/` contains only `check-doc-citations.ps1`, `h1-protocol-capture.ps1` and `relocate-tooling-section.ps1`, and `opencode-via-mubeng.ps1` exists nowhere in the repository tree nor at the `<mubeng-dir>` location referenced above (both verified by direct filesystem search on 2026-10-22). Treat the script path as machine-local external tooling that is not a repository deliverable; this entry is preserved as the dated 2026-10-04 decision record.]
- proxies.txt is gitignored (contains credentials); template in proxies.example.txt.
**Impact**: All free models selectable; OpenCode server + tools traffic exits via the rotating proxy. mubeng itself exposes only an HTTP proxy (SOCKS upstreams are used client-side, the server speaks HTTP), so HTTPS URLs go through it as HTTP CONNECT proxying.
