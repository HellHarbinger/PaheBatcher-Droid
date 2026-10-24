# One-shot maintenance script (2026-10-04): move the out-of-scope "OpenCode Local
# Tooling Setup" entry out of DECISIONS.md (Phase 14 citation-check must-fix NEW-1).
# Reversibility: TOOLING_SETUP.md preserves the entry verbatim (LF-normalized).
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

$raw = [System.IO.File]::ReadAllText('DECISIONS.md')
$marker = "## OpenCode Local Tooling Setup"
$idx = $raw.IndexOf($marker)
if ($idx -lt 0) { throw 'marker not found - already relocated?' }

$sectionLf = $raw.Substring($idx) -replace "`r`n", "`n"
$header = @"
# Tooling Setup Decisions

> Relocated from DECISIONS.md (the PaheBatcher architectural decision ledger,
> AGENTS.md section 14) on 2026-10-04: this entry documents OpenCode/mubeng
> tooling configuration, not PaheBatcher architecture.

"@
$newContent = $header + $sectionLf
if (-not $newContent.EndsWith("`n")) { $newContent += "`n" }
[System.IO.File]::WriteAllText('TOOLING_SETUP.md', $newContent, (New-Object System.Text.UTF8Encoding($false)))

$kept = $raw.Substring(0, $idx).TrimEnd("`r", "`n") + "`n"
[System.IO.File]::WriteAllText('DECISIONS.md', $kept, (New-Object System.Text.UTF8Encoding($false)))

$verify = [System.IO.File]::ReadAllText('DECISIONS.md')
$crlf = ([regex]::Matches($verify, "`r`n")).Count
$lastLine = (Get-Content 'DECISIONS.md')[-1]
$toolCrlf = ([regex]::Matches([System.IO.File]::ReadAllText('TOOLING_SETUP.md'), "`r`n")).Count
Write-Output "DECISIONS: crlf=$crlf lines=$((Get-Content 'DECISIONS.md').Count) lastLine=$lastLine"
Write-Output "TOOLING_SETUP: crlf=$toolCrlf lines=$((Get-Content 'TOOLING_SETUP.md').Count)"
