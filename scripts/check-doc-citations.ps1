#Requires -Version 5.1
<#
.SYNOPSIS
    Documentation citation / integrity checker for the PaheBatcher Android repo.

.DESCRIPTION
    Created in Phase 11.5, background-review round 6, whose finding was that after
    five rounds of a "citations point at the wrong place" defect, no mechanical
    citation check existed: pointers and counts were being re-verified by hand each
    round, and re-failing each round.  Round 7 extended it after mutation testing
    showed which historic defects still slipped through.

    Each check corresponds to a defect a reviewer actually found by hand:

      1. table rows whose unescaped-pipe count differs from their header, which
         silently drops cells when rendered          (round 1 R3-1, round 3 R3-5)
         - plus: a header row with no delimiter row after it, and a delimiter
           row with no header above it, both of which stop the whole table from
           being pipe-checked at all                 (round 7 finding 5)
         - the comparison is against the HEADER row's pipe count, not the
           delimiter row's                           (round 7 finding 13)
      2. an explicit FILE:NNNN citation whose target is missing, BLANK or a table
         delimiter row, including every number of a FILE:NNNN-NNNN range or a
         FILE:NN1,NN2 list                            (round 5 R5-1, round 6 R6-1,
                                                       round 7 findings 1, 9)
         - plus source-file citations (any *.kt/*.sh/*.py/*.xml/*.kts/... :NNNN),
           which is where round 7 findings 2 and 3 lived
      3. a known-false claim left live with no disclosure context within one line
         (round 4 R4-1, round 6 R6-3 - the deleted notification builders)
      4. required content that must stay present so a sentence cannot be "fixed"
         by deleting it
      5. a review section whose table row count disagrees BOTH with the "FAIL on N
         findings" sentence in that section AND with the expected row count in
         $ExpectedRows, which is what actually covers round 1, whose section has
         no "FAIL on N" sentence at all
         (round 3 "20 vs 19", round 6 R6-4 "7 of 9" vs 6, round 7 finding 4)
         - plus a required set of load-bearing citations that must still exist,
           because deleting a citation leaves nothing to resolve
           (round 7 finding 6)
      6. ROADMAP's Phase 11.5 exit criterion under-reporting how many review
         rounds exist, or under-counting "all N correction rounds are
         documentation-only"                         (round 4 R4-4, round 6 R6-2)
      7. the frozen capture's size / line count / SHA-256 against the file, AND
         every figure of that shape written in the five documents against the
         file                                        (round 7 finding 7)

       8. a CURRENT-STATUS line (PARITY.md, LIMITATIONS.md) that still says
          streamed next/previous is absent, without the inline supersession
          marker - plus the marker itself having been deleted
          (2026-10-06 closure item 5, the PARITY.md:687 contradiction)

    The markdown table TOTAL is deliberately printed, never asserted: it is
    self-referential - appending a round's own table changes it - so a figure typed
    into prose is wrong the moment the next round lands (71 -> 72 -> 73 -> 74 ...).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\check-doc-citations.ps1

.NOTES
    Exit code 0 = every check passed, 1 = at least one failure.
    This script touches no file under app/ and is not part of the Android build.

    Still NOT covered, deliberately (see DECISIONS 78 "Known limitations"):
      * whether a citation points at the RIGHT content - only that it resolves,
        except for the few phrases pinned in $Required / $ProseFigures
      * bare ':NNNN' references, which are ambiguous between a document line and a
        frozen-logcat line (:546 is a ROADMAP line, :8182 is a logcat line)
      * markdown rendering
      * the markdown table TOTAL, because it is self-referential
#>

[CmdletBinding()]
param(
    # Skip the 78 MB frozen-logcat hash / line-count re-verification.
    [switch]$SkipEvidence,
    # Suppress the informational (non-failure) lines; failures always print.
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$script:Fails = New-Object System.Collections.Generic.List[string]
$script:Infos = New-Object System.Collections.Generic.List[string]
function Add-Fail([string]$m) { [void]$script:Fails.Add($m) }
function Add-Info([string]$m) { [void]$script:Infos.Add($m) }

$Root = Split-Path -Parent $PSScriptRoot

# The five primary documents: table integrity, backticks and citations all checked.
$DocFiles = [ordered]@{
    'AGENTS'      = Join-Path $Root 'AGENTS.md'
    'DECISIONS'   = Join-Path $Root 'DECISIONS.md'
    'LIMITATIONS' = Join-Path $Root 'LIMITATIONS.md'
    'ROADMAP'     = Join-Path $Root 'ROADMAP.md'
    'PARITY'      = Join-Path $Root 'PARITY.md'
}
# Cited but not table-checked (hand-written notes, not review tables).
# PHASE11_HANDOFF.md was archived 2026-10-22 to archive/phase-reports/ (provenance:
# archive/phase-reports/ARCHIVE_INDEX.md); it stays a scanned citation TARGET at its
# archived path so PHASE11_HANDOFF:NNNN citations keep resolving and validating.
$ExtraCiteFiles = @{
    'PHASE11_HANDOFF' = Join-Path $Root 'archive\phase-reports\PHASE11_HANDOFF.md'
}

$Lines = @{}
foreach ($k in @($DocFiles.Keys)) {
    $p = $DocFiles[$k]
    if (-not (Test-Path -LiteralPath $p)) { Add-Fail "missing document: $k ($p)"; continue }
    $Lines[$k] = [System.IO.File]::ReadAllLines($p)
}
foreach ($k in @($ExtraCiteFiles.Keys)) {
    $p = $ExtraCiteFiles[$k]
    if (Test-Path -LiteralPath $p) { $Lines[$k] = [System.IO.File]::ReadAllLines($p) }
}

# ===========================================================================
# 1. Markdown table integrity.
#    A row whose unescaped-pipe count differs from the HEADER's is parsed as the
#    wrong number of cells; when it is larger, trailing cells are dropped and the
#    Disposition column silently disappears.  That is how a review record can
#    "record" a fix it never made.
# ===========================================================================
$tableTotal = 0
foreach ($k in @($DocFiles.Keys)) {
    if (-not $Lines.ContainsKey($k)) { continue }
    $text = $Lines[$k]

    # Pass A: locate delimiter rows and the header that governs each one.
    $isDelim = @{}
    $hdrFor = @{}
    for ($i = 0; $i -lt $text.Count; $i++) {
        if ($text[$i] -notmatch '^\s*\|\s*:?-{2,}') { continue }
        $isDelim[$i] = $true
        $tableTotal++
        if ($i -gt 0 -and $text[$i - 1] -match '^\s*\|') {
            $hc = ([regex]::Matches($text[$i - 1], '(?<!\\)\|')).Count
            $hdrFor[$i] = $hc
            $dc = ([regex]::Matches($text[$i], '(?<!\\)\|')).Count
            if ($dc -ne $hc) {
                Add-Fail ("{0}:{1}: delimiter row has {2} unescaped pipes but its header row has {3}" -f $k, ($i + 1), $dc, $hc)
            }
        }
        else {
            $hdrFor[$i] = -1
            Add-Fail ("{0}:{1}: table delimiter row with no header row above it" -f $k, ($i + 1))
        }
    }

    # Pass B: walk the rows.
    $hdr = -1
    for ($i = 0; $i -lt $text.Count; $i++) {
        $l = $text[$i]
        if ($isDelim.ContainsKey($i)) { $hdr = $hdrFor[$i]; continue }
        if ($l -notmatch '^\s*\|') { $hdr = -1; continue }
        $n = ([regex]::Matches($l, '(?<!\\)\|')).Count
        if ($hdr -lt 0) {
            # A pipe line with no active table above it is a header row (or a
            # stray pipe line).  Either way it is only part of a table if the
            # NEXT line is also a pipe line - normally the delimiter row.  When
            # it is not, that table never starts, so every data row beneath it
            # silently escapes pipe checking; that is exactly the mutation that
            # round 6's checker could not see (round 7 finding 5).  Deliberately
            # NOT conditioned on the preceding line being blank: the first table
            # in LIMITATIONS.md is preceded by a heading, which is why an earlier
            # version of this check slipped.
            if (-not ($i + 1 -lt $text.Count -and $text[$i + 1] -match '^\s*\|')) {
                Add-Fail ("{0}:{1}: table header row is not followed by a delimiter row - the whole table below it escapes pipe checking" -f $k, ($i + 1))
            }
            continue
        }
        if ($n -ne $hdr) {
            Add-Fail ("{0}:{1}: table row has {2} unescaped pipes vs a {3}-pipe header row - cells are dropped when rendered" -f $k, ($i + 1), $n, $hdr)
        }
        # An unescaped pipe inside a code span is the specific way round-1 R3-1
        # and round-3 R3-5 broke their rows.
        $parts = $l -split '`'
        for ($j = 1; $j -lt $parts.Count; $j += 2) {
            if ($parts[$j] -match '(?<!\\)\|') {
                Add-Fail ("{0}:{1}: unescaped pipe inside a code span in a table row" -f $k, ($i + 1))
            }
        }
    }
}
foreach ($k in @($DocFiles.Keys)) {
    if (-not $Lines.ContainsKey($k)) { continue }
    $raw = [System.IO.File]::ReadAllText($DocFiles[$k])
    $bt = ([regex]::Matches($raw, '`')).Count
    if ($bt % 2 -ne 0) { Add-Fail ("{0}: odd backtick count ({1}) - inline code spans will bleed" -f $k, $bt) }
    # Round 7 finding 14: DECISIONS.md was a mix of CRLF and LF, which mis-indexes
    # lines for any tool that splits on a single convention.
    $crlf = ([regex]::Matches($raw, "`r`n")).Count
    if ($crlf -gt 0 -and $crlf -ne ([regex]::Matches($raw, "`n")).Count) {
        Add-Fail ("{0}: mixed line endings - {1} CRLF of {2} LF total" -f $k, $crlf, ([regex]::Matches($raw, "`n")).Count)
    }
}
Add-Info ("markdown tables counted across 5 files: {0} (printed, not asserted - the total changes whenever a round appends its own table)" -f $tableTotal)

# ===========================================================================
# 2. Citation resolution - documents AND source files, with ranges.
#    Bare ':NNNN' is deliberately NOT resolved: in these documents it is ambiguous
#    between a doc line and a frozen-logcat line (:546 is a ROADMAP line, :8182 is
#    a logcat line, and both are cited in the same paragraphs).
# ===========================================================================
$docNames = 'AGENTS|DECISIONS|LIMITATIONS|ROADMAP|PARITY|PHASE11_HANDOFF'
# Capture FILE:NNNN, FILE:NNNN-NNNN and FILE:NNNN,NNNN - all numbers validated.
# NOTE the group around {0}: without it the alternation would bind loose and match
# a bare document name with no colon at all.
$docCiteRe = ("(?<![\w.])(?:{0})(?:\.md)?:(?<n>\d{{1,6}})(?!\.\d)(?<rest>[0-9,{{\-}}]*)" -f $docNames)
$srcExt = 'kt|kts|java|sh|py|xml|json|gradle|md|txt'
# The target may be written as a bare leaf or as a repo-relative path, so capture
# the leaf separately for lookup while keeping the whole thing for reporting.
$srcCiteRe = ("(?<![\w.\-/])(?<f>(?:[\w.\-]+[\\/])*(?<leaf>[\w.\-]+\.(?:{0}))):(?<n>\d{{1,6}})(?!\.\d)(?<rest>[0-9,{{\-}}]*)" -f $srcExt)

function Get-NumList([string]$first, [string]$rest) {
    $out = New-Object System.Collections.Generic.List[int]
    [void]$out.Add([int]$first)
    if ($rest) { foreach ($piece in ($rest -split '[^0-9]+')) { if ($piece -ne '') { [void]$out.Add([int]$piece) } } }
    return $out
}

function Test-TargetLine([string]$where, [string[]]$target, [string]$label, [int]$tn) {
    if ($tn -lt 1 -or $tn -gt $target.Count) {
        Add-Fail ("{0} cites {1} -> that line does not exist ({1} has {2} lines)" -f $where, $label, $target.Count)
        return
    }
    $tl = $target[$tn - 1]
    if ($tl.Trim() -eq '') { Add-Fail ("{0} cites {1} -> target line is BLANK" -f $where, $label) }
    elseif ($tl -match '^\s*\|\s*:?-{2,}') { Add-Fail ("{0} cites {1} -> target is a table delimiter row, not content" -f $where, $label) }
}

$checked = @{}
$occurrences = @{}
$citeCount = 0
foreach ($k in @($DocFiles.Keys)) {
    if (-not $Lines.ContainsKey($k)) { continue }
    $text = $Lines[$k]
    for ($i = 0; $i -lt $text.Count; $i++) {
        $where = "{0}:{1}" -f $k, ($i + 1)
        foreach ($m in [regex]::Matches($text[$i], $docCiteRe)) {
            $tf = ($m.Value -split ':')[0] -replace '\.md$', ''
            $nums = Get-NumList $m.Groups['n'].Value $m.Groups['rest'].Value
            $key = ($nums | ForEach-Object { "$tf`:$($_)" }) -join ','
            if (-not $occurrences.ContainsKey($key)) { $occurrences[$key] = 0 }
            $occurrences[$key]++
            if ($checked.ContainsKey($key)) { continue }
            $checked[$key] = $true
            if (-not $Lines.ContainsKey($tf)) {
                Add-Fail ("{0} cites {1} but that document is not present" -f $where, $key)
                continue
            }
            $citeCount++
            foreach ($tn in $nums) { Test-TargetLine $where $Lines[$tf] $("$tf`:$tn") $tn }
        }
    }
}
Add-Info ("document-to-document FILE:NNNN citations resolved: {0} distinct, {1} occurrences" -f $citeCount, ($occurrences.Values | Measure-Object -Sum).Sum)

# --- source-file citations (round 7 findings 2, 3) -------------------------
# Build a leaf-name -> path index once, excluding build output.
# The Python reference repository is indexed too: PARITY/DECISIONS cite
# downloader.py / kwik.py / http.py / store.py / main.py from it, and a citation
# into an external reference is still a citation that can rot.
$SearchRoots = @($Root)
$PythonRef = $env:PB_PYTHON_REF
if ($PythonRef -and (Test-Path -LiteralPath $PythonRef)) { $SearchRoots += $PythonRef }
else { Add-Info 'Python reference repository not configured (set PB_PYTHON_REF to its local checkout) - its citations will be reported as unresolvable' }
$srcIndex = @{}
$skipDirNames = @('build', '.gradle', '.git', '.idea', 'kaptStubs', '.kotlin', '__pycache__', 'node_modules', 'venv', '.venv', 'site-packages', 'Lib', 'Scripts')
$stack = New-Object System.Collections.Generic.Stack[string]
foreach ($sr in $SearchRoots) { $stack.Push($sr) }
while ($stack.Count -gt 0) {
    $dir = $stack.Pop()
    try { $dirs = [System.IO.Directory]::GetDirectories($dir) } catch { $dirs = @() }
    foreach ($dd in $dirs) {
        if ($skipDirNames -contains [System.IO.Path]::GetFileName($dd)) { continue }
        $stack.Push($dd)
    }
    try { $files = [System.IO.Directory]::GetFiles($dir) } catch { $files = @() }
    foreach ($f in $files) {
        $leaf = [System.IO.Path]::GetFileName($f)
        if ($srcIndex.ContainsKey($leaf)) { $srcIndex[$leaf] = $srcIndex[$leaf] + '|' + $f }
        else { $srcIndex[$leaf] = $f }
    }
}
$srcLineCache = @{}
$srcChecked = @{}
$srcCount = 0
$srcMissing = 0
foreach ($k in @($DocFiles.Keys)) {
    if (-not $Lines.ContainsKey($k)) { continue }
    $text = $Lines[$k]
    for ($i = 0; $i -lt $text.Count; $i++) {
        $where = "{0}:{1}" -f $k, ($i + 1)
        foreach ($m in [regex]::Matches($text[$i], $srcCiteRe)) {
            $ref = $m.Groups['f'].Value
            $leaf = $m.Groups['leaf'].Value
            $nums = Get-NumList $m.Groups['n'].Value $m.Groups['rest'].Value
            $key = (($nums | ForEach-Object { "$ref`:$($_)" }) -join ',')
            if ($srcChecked.ContainsKey($key)) { continue }
            $srcChecked[$key] = $true
            if (-not $srcIndex.ContainsKey($leaf)) {
                # Not every DOC-extension citation is a file (e.g. a prose name);
                # only fail when the extension is a real source/build extension.
                if ($leaf -match '\.(kt|kts|java|sh|py|xml|gradle)$') {
                    $srcMissing++
                    Add-Fail ("{0} cites {1} -> no file with that name exists in the repo (excluding build output)" -f $where, $key)
                }
                continue
            }
            $srcCount++
            $paths = $srcIndex[$leaf] -split '\|'
            if ($paths.Count -gt 1) {
                Add-Fail ("{0} cites {1} -> ambiguous, {2} files share that name: {3}" -f $where, $key, $paths.Count, ($paths -join '; '))
                continue
            }
            $tp = $paths[0]
            if (-not $srcLineCache.ContainsKey($tp)) { $srcLineCache[$tp] = [System.IO.File]::ReadAllLines($tp) }
            foreach ($tn in $nums) {
                Test-TargetLine $where $srcLineCache[$tp] ($leaf + ':' + $tn) $tn
            }
        }
    }
}
Add-Info ("source-file FILE:NNNN citations resolved: {0} distinct (unresolvable file names: {1})" -f $srcCount, $srcMissing)

# --- citations that must still exist (round 7 finding 6) ------------------
# Removing a citation leaves nothing for the resolver to find, so deletion was
# invisible.  These are the load-bearing pointers rounds 1-7 established.
$RequiredCitations = @(
    'DECISIONS:2292'
    'DECISIONS:2164'
    'DECISIONS:2227'
    'DECISIONS:2318'
    'DECISIONS:2327'
    'ROADMAP:563'
    'PARITY:335'
    'LIMITATIONS:1164'
)
$allDocText = ''
foreach ($k in @($DocFiles.Keys)) { if ($Lines.ContainsKey($k)) { $allDocText += ($Lines[$k] -join "`n") + "`n" } }
foreach ($rc in $RequiredCitations) {
    if (-not $occurrences.ContainsKey($rc) -and $allDocText.IndexOf($rc) -lt 0) {
        Add-Fail ("required citation {0} has disappeared from all five documents - a pointer cannot be audited if it is simply gone" -f $rc)
    }
    elseif ($occurrences.ContainsKey($rc)) {
        Add-Info ("required citation {0}: {1} occurrence(s)" -f $rc, $occurrences[$rc])
    }
}

# ===========================================================================
# 3. Known-false claims must never appear as a LIVE claim.
#    A banned phrase is allowed only when a disclosure marker appears within one
#    line of it (prose wraps), i.e. the sentence is quoting the old defect rather
#    than asserting it.  Without this, a fix that removes the claim from one
#    document leaves it live in another - which is what round 6 R6-3 found.
# ===========================================================================
$Disclosure = 'round-\d|R\d-\d|finding|previously|originally|stale|historical|superseded|miscount|over-claim|overclaim|was |said |read |quoted|false|never |deleted|not |present tense|which is|defect|as observed|since |dated|inventory|record'
$BadClaims = @(
    @{ P = 'exist test-only'; Why = 'completion/error notification builders were deleted as dead code in 11.5 item (c)' },
    @{ P = 'builders exist in `DownloadNotificationManager`'; Why = 'same deletion - no such symbol exists in app/src' },
    @{ P = 'has a corrected baseline'; Why = 'R3-7: the 114,869,141 B baseline was never corrected, and must not be' },
    @{ P = 'APK baseline refreshed'; Why = 'R3-7: no refresh ever happened' },
    @{ P = 'All 11 findings are corrected above'; Why = 'round 3 over-claim - round 4 showed R3-7 and R3-11 were incomplete' },
    @{ P = 'Confirmed all 20 round-1 findings'; Why = 'round 3 R3-6 over-claim - the round-1 table has 19 rows' },
    @{ P = 'Both records rewritten'; Why = 'R4-1 over-claim - five sites, two rewritten' },
    @{ P = 'labelled as such in both places'; Why = 'R4-3 miscount - the dumpsys observation appears in three places' },
    @{ P = 'now line ~2881'; Why = 'R4-6: a line number that had already drifted' },
    @{ P = 'same content and the same two'; Why = 'R4-7: content cannot be equal while bytes are not' },
    @{ P = '71 markdown tables'; Why = 'R5-4: miscount' },
    @{ P = '72 markdown tables'; Why = 'R6-7: miscount' },
    @{ P = 'holes 218/238'; Why = 'R5-5: order reversed relative to LIMITATIONS and DECISIONS' },
    @{ P = 'DECISIONS 2164/2227'; Why = 'R4-8: 2227 belongs to Decision 76, not Decision 75' },
    @{ P = 'Connected (`device`)'; Why = 'R4-2: adb devices returns nothing; device is offline' },
    @{ P = 'exactly one serial in state'; Why = 'R4-2/R5-6: dated observation, must carry a qualifier' }
)
$liveBad = 0
foreach ($bc in $BadClaims) {
    $hits = 0
    foreach ($k in @($DocFiles.Keys)) {
        if (-not $Lines.ContainsKey($k)) { continue }
        $text = $Lines[$k]
        for ($i = 0; $i -lt $text.Count; $i++) {
            if ($text[$i] -notmatch [regex]::Escape($bc.P)) { continue }
            $hits++
            $lo = [Math]::Max(0, $i - 1)
            $hi = [Math]::Min($text.Count - 1, $i + 1)
            $window = ($text[$lo..$hi] -join ' ')
            if ($window -notmatch $Disclosure) {
                $liveBad++
                Add-Fail ("{0}:{1}: live claim ""{2}"" with no disclosure context - {3}" -f $k, ($i + 1), $bc.P, $bc.Why)
            }
        }
    }
    if ($hits -eq 0) { Add-Info ("banned phrase ""{0}"" is absent everywhere" -f $bc.P) }
    else { Add-Info ("banned phrase ""{0}"" appears {1} time(s), all disclosed" -f $bc.P, $hits) }
}

# ===========================================================================
# 4. Things that must be present, asserted as content so they cannot be
#    "fixed" by deleting the sentence that was wrong.
# ===========================================================================
$Required = @(
    @{ F = '.investigation/media-final/remeasure.sh'; P = 'same structure and the same two'; Why = 'R4-7 contract wording' },
    @{ F = 'PARITY.md';                                P = 'holes 238/218';                 Why = 'R5-5 grid-hole order' },
    @{ F = 'ROADMAP.md';                               P = 'all seven correction rounds are documentation-only'; Why = 'R6-2 round tally, carried forward through round 7' },
    @{ F = 'ROADMAP.md';                               P = 'has failed review seven times';  Why = 'R6-2 failure tally, carried forward through round 7' },
    @{ F = 'ROADMAP.md';                               P = 'builders were **deleted as dead code in 11.5 item (c)**'; Why = 'R6-3 index row' },
    @{ F = 'LIMITATIONS.md';                           P = 'were deleted as dead code in Phase 11.5 item (c)'; Why = 'R6-3 LIMITATIONS row' },
    @{ F = 'DECISIONS.md';                             P = '6 of the 9 round-4 findings fully correct'; Why = 'R4 findings actually flagged: R4-1, R4-7, R4-8' },
    @{ F = 'DECISIONS.md';                             P = 'scripts/check-doc-citations.ps1'; Why = 'the missing test must be referenced by the record that needed it' },
    @{ F = 'ROADMAP.md';                               P = '**A sixth** review then returned'; Why = 'round-6 narrative present in the exit criterion' },
    @{ F = 'PARITY.md';                                P = 'streamed next/previous, a multi-episode stream playlist (rows below)'; Why = 'the Phase 17 history sentence must be RETAINED when superseded, not erased (closure item 5)' },
    @{ F = 'PARITY.md';                                P = 'CORRECTED 2026-10-06 - the sentence above is Phase 17 history, not current state'; Why = 'the PARITY.md:687 contradiction must keep its inline correction (closure item 5)' },
    @{ F = 'PARITY.md';                                P = '.investigation/phase17p7';      Why = 'the archived external process-death evidence pointer must stay citable (closure 2026-10-07)' },
    @{ F = 'PARITY.md';                                P = 'LogUrlSanitizer.diagnostic';   Why = 'the centralized throwable/exception-text sanitization must be named where the closure is claimed (closure 2026-10-07)' }
)
foreach ($r in $Required) {
    $p = $r.F
    if ($p -notlike '*:*' -and $p -notlike '*/*' -and $p -notlike '*\*') { $p = Join-Path $Root $p }
    if ($p -match '\.sh$') { $p = Join-Path $Root $r.F }
    if (-not (Test-Path -LiteralPath $p)) { Add-Fail "required file not found: $($r.F)"; continue }
    $raw = [System.IO.File]::ReadAllText($p)
    if ($raw -notmatch [regex]::Escape($r.P)) { Add-Fail ("{0}: expected to contain ""{1}"" - {2}" -f $r.F, $r.P, $r.Why) }
}

# ===========================================================================
# 5. Review-section row counts: against the section's own "FAIL on N" sentence
#    AND against $ExpectedRows, so a section with no such sentence (round 1) is
#    still asserted, and so deleting the sentence cannot degrade the section to
#    info-only.
# ===========================================================================
# Keyed by section order (0-based).  Appending a review round REQUIRES adding its
# count here - that is deliberate: it forces the new round's table to be counted
# by hand once, mechanically thereafter.
$ExpectedRows = @{
    0 = 19   # round 1  (C-1, M-1..M-5, m-1..m-11, n-1, n-2) - no "FAIL on N" sentence
    1 = 10   # round 2
    2 = 11   # round 3
    3 = 9    # round 4
    4 = 7    # round 5
    5 = 11   # round 6
    6 = 14   # round 7 - the round that mutation-tested this map
}
$dec = $Lines['DECISIONS']
if ($dec) {
    $heads = @()
    for ($i = 0; $i -lt $dec.Count; $i++) { if ($dec[$i] -match '^### Background-review round') { $heads += $i } }
    Add-Info ("background-review sections in DECISIONS.md: {0}" -f $heads.Count)
    if ($ExpectedRows.Count -ne $heads.Count) {
        Add-Fail ("there are {0} review sections but {1} entries in `$ExpectedRows - a new round was added without its row count being pinned, so nothing asserts it" -f $heads.Count, $ExpectedRows.Count)
    }
    for ($h = 0; $h -lt $heads.Count; $h++) {
        $start = $heads[$h]
        $end = if ($h -lt $heads.Count - 1) { $heads[$h + 1] } else { $dec.Count }
        $rowStart = -1
        for ($i = $start + 1; $i -lt $end; $i++) { if ($dec[$i] -match '^\s*\|') { $rowStart = $i; break } }
        if ($rowStart -lt 0) { Add-Fail ("{0}: review section has no findings table" -f $dec[$start]); continue }
        $data = 0
        for ($i = $rowStart + 2; $i -lt $end; $i++) { if ($dec[$i] -match '^\s*\|') { $data++ } else { break } }

        if ($ExpectedRows.ContainsKey($h)) {
            $exp = $ExpectedRows[$h]
            if ($data -ne $exp) {
                Add-Fail ("{0}: table has {2} data rows but the expected count pinned in `$ExpectedRows is {1}" -f $dec[$start], $exp, $data)
            }
            else { Add-Info ("  {0} : {1} rows = expected {1}" -f $dec[$start], $data) }
        }
        else { Add-Info ("  {0} : {1} rows (no expected count pinned)" -f $dec[$start], $data) }

        $sec = ($dec[$start..($end - 1)] -join "`n")
        $m = [regex]::Match($sec, 'FAIL.{0,20}?on\s+\*{0,2}(\d+)')
        if (-not $m.Success) { continue }
        $declared = [int]$m.Groups[1].Value
        if ($declared -ne $data) {
            Add-Fail ("{0}: table has {1} data rows but the section declares FAIL on {2} findings" -f $dec[$start], $data, $declared)
        }
    }

    # 5b. ROADMAP's exit criterion must narrate as many rounds as exist.
    $Ordinals = @{
        first = 1; second = 2; third = 3; fourth = 4; fifth = 5; sixth = 6
        seventh = 7; eighth = 8; ninth = 9; tenth = 10; eleventh = 11; twelfth = 12
        # Cardinal forms: the prose says "failed review six times" / "all six
        # correction rounds", which capture "six", not "sixth".
        one = 1; two = 2; three = 3; four = 4; five = 5; six = 6
        seven = 7; eight = 8; nine = 9; ten = 10; eleven = 11; twelve = 12
    }
    $rm = $DocFiles['ROADMAP']
    if ($rm -and (Test-Path -LiteralPath $rm)) {
        $rmLines = [System.IO.File]::ReadAllLines($rm)
        $exitLine = $null
        foreach ($l in $rmLines) {
            if ($l -match 'Each scope item verified by its own bounded gate') { $exitLine = $l; break }
        }
        if ($null -eq $exitLine) {
            Add-Fail 'ROADMAP: Phase 11.5 exit-criterion line not found (heading text changed?)'
        }
        else {
            $maxOrd = 0
            foreach ($mm in [regex]::Matches($exitLine, '\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth)\b')) {
                $v = $Ordinals[$mm.Groups[1].Value]
                if ($v -gt $maxOrd) { $maxOrd = $v }
            }
            if ($maxOrd -ne $heads.Count) {
                Add-Fail ("ROADMAP exit criterion narrates at most round {0}, but DECISIONS.md contains {1} review sections - the gate under-reports its own review history" -f $maxOrd, $heads.Count)
            }
            else { Add-Info ("ROADMAP exit criterion narrates {0} review rounds = {1} sections in DECISIONS" -f $maxOrd, $heads.Count) }

            $mFail = [regex]::Match($exitLine, 'failed review (\w+) times')
            if ($mFail.Success) {
                $v = $Ordinals[$mFail.Groups[1].Value]
                if ($v -ne $heads.Count) { Add-Fail ("ROADMAP says ""failed review {0} times"" but {1} review sections exist" -f $mFail.Groups[1].Value, $heads.Count) }
            }
            else { Add-Fail 'ROADMAP exit criterion no longer contains "failed review N times"' }

            $mAll = [regex]::Match($exitLine, 'all (\w+) correction rounds are documentation-only')
            if ($mAll.Success) {
                $v = $Ordinals[$mAll.Groups[1].Value]
                if ($v -ne $heads.Count) { Add-Fail ("ROADMAP says ""all {0} correction rounds"" but {1} review sections exist" -f $mAll.Groups[1].Value, $heads.Count) }
            }
            else { Add-Fail 'ROADMAP exit criterion no longer contains "all N correction rounds are documentation-only"' }
        }
    }
}

# ===========================================================================
# 6. Evidence identity of the frozen capture, AND of every figure of the same
#    shape written in the documents (round 7 finding 7: guarding the artifact but
#    not the sentence about the artifact is the exact defect class this script
#    exists for).
# ===========================================================================
if (-not $SkipEvidence) {
    $lg = Join-Path $Root '.investigation\media-final\logcat_device_run.txt'
    $actualBytes = 0; $actualLines = 0; $actualBlank = 0; $actualSha = ''
    if (-not (Test-Path -LiteralPath $lg)) {
        Add-Fail 'frozen logcat capture missing (.investigation/media-final/logcat_device_run.txt)'
    }
    else {
        $fi = Get-Item -LiteralPath $lg
        $actualBytes = $fi.Length
        if ($actualBytes -ne 78399694) { Add-Fail ("frozen logcat is {0} bytes, record says 78,399,694" -f $actualBytes) }
        $actualSha = (Get-FileHash -LiteralPath $lg -Algorithm SHA256).Hash
        if ($actualSha -ne '8AF292E0DA62F3D6572D875E0F10A1C00DDC467F390A1AADF097FC02F12814D0') {
            Add-Fail "frozen logcat SHA-256 is $actualSha, record says 8AF292E0...14D0"
        }
        # 255,063 total lines / 254,931 non-blank - the round-3 R3-3 distinction.
        $all = [System.IO.File]::ReadAllLines($lg)
        $actualLines = $all.Count
        foreach ($l in $all) { if ($l.Trim().Length -eq 0) { $actualBlank++ } }
        if ($actualLines -ne 255063) { Add-Fail ("frozen logcat has {0} lines, record says 255,063" -f $actualLines) }
        if (($actualLines - $actualBlank) -ne 254931) { Add-Fail ("frozen logcat has {0} non-blank lines, record says 254,931" -f ($actualLines - $actualBlank)) }
        Add-Info ("frozen logcat: {0} bytes, {1} lines ({2} non-blank, {3} blank), SHA-256 {4}" -f $actualBytes, $actualLines, ($actualLines - $actualBlank), $actualBlank, $actualSha.Substring(0, 16))
    }

    # 6b. Every occurrence of these numeric shapes in the documents must equal the
    #     real value.  Mutation "one byte of the figure changed in prose" now fails.
    #     The Good values are DERIVED from the artifacts, not typed, so a legitimate
    #     re-measure moves the target rather than leaving a stale constant behind.
    #     Invariant culture: '{0:N0}' under an en-IN locale would render 7,83,99,694.
    $ic = [System.Globalization.CultureInfo]::InvariantCulture
    $goodBytes  = [string]::Format($ic, '{0:N0}', $actualBytes)
    $goodLines  = [string]::Format($ic, '{0:N0}', $actualLines)
    $goodNonBlk = [string]::Format($ic, '{0:N0}', ($actualLines - $actualBlank))
    $ProseFigures = @(
        @{ Name = 'logcat byte size';  Re = '78,399,\d{3}';       Good = $goodBytes },
        @{ Name = 'logcat line count'; Re = '255,0\d\d';          Good = $goodLines },
        @{ Name = 'logcat non-blank';  Re = '254,9\d\d';          Good = $goodNonBlk },
        @{ Name = 'debug APK size';    Re = '114,43\d,\d{3}';     Good = '114,436,686' },
        @{ Name = 'Phase-11 APK baseline'; Re = '114,86\d,\d{3}'; Good = '114,869,141' },
        @{ Name = 'APK delta percentage'; Re = '0\.37\d %'; Good = '0.376 %' }
        # JVM totals deliberately NOT checked: historic phase totals (523, 510, ...)
        # legitimately appear in old decision rows, so a live-figure pattern cannot
        # be told apart from a historical one here.
    )
    foreach ($pf in $ProseFigures) {
        $bad = 0
        foreach ($k in @($DocFiles.Keys)) {
            if (-not $Lines.ContainsKey($k)) { continue }
            for ($i = 0; $i -lt $Lines[$k].Count; $i++) {
                foreach ($mm in [regex]::Matches($Lines[$k][$i], $pf.Re)) {
                    if ($mm.Value -ne $pf.Good) {
                        $bad++
                        Add-Fail ("{0}:{1}: figure for {2} reads ""{3}"", the artifacts say ""{4}""" -f $k, ($i + 1), $pf.Name, $mm.Value, $pf.Good)
                    }
                }
            }
        }
        if ($bad -eq 0) { Add-Info ("prose figure {0}: every occurrence matches {1}" -f $pf.Name, $pf.Good) }
    }
}

# ===========================================================================
# 7. A current-status document may not assert that streamed next/previous is
#    absent once DECISIONS 130 implemented it.
#
#    Closure item 5, the defect the 2026-10-05 audit found at PARITY.md:687:
#    the Phase 17 playback row kept saying "Still absent ... streamed
#    next/previous" while the rows directly below it said IMPLEMENTED, and
#    nothing mechanical caught the contradiction. The historical sentence is
#    deliberately NOT erased (DECISIONS 130 item 8 keeps phase records as
#    history); instead every such line must carry the inline correction marker,
#    and the marker itself must exist somewhere in the status documents - so
#    neither the correction nor its subject can quietly drift apart again.
#
#    ROADMAP.md is excluded on purpose: DECISIONS 130 item 8 records its
#    Phase 17 out-of-scope line as a historical phase record that stays as-is,
#    with the supersession carried by the later phase section.
# ===========================================================================
$CorrectionMarker = 'CORRECTED 2026-10-06'
$ContradictionRe = '(streamed next/previous|next/previous while streaming|stream next|stream previous)'
$AbsenceRe = '(?i)\b(absent|not implemented|never implemented|never built|out of scope|not built|not claimed)\b'
$contradictions = 0
$correctionMarkers = 0
$contradictionClaimLines = 0
foreach ($k in @('PARITY', 'LIMITATIONS')) {
    if (-not $Lines.ContainsKey($k)) { continue }
    for ($i = 0; $i -lt $Lines[$k].Count; $i++) {
        $l = $Lines[$k][$i]
        if ($l -match [regex]::Escape($CorrectionMarker)) { $correctionMarkers++ }
        if ($l -notmatch $ContradictionRe) { continue }
        if ($l -notmatch $AbsenceRe) { continue }
        $contradictionClaimLines++
        if ($l -match [regex]::Escape($CorrectionMarker)) { continue }
        $contradictions++
        Add-Fail ("{0}:{1}: a CURRENT-STATUS line still says streamed next/previous is absent - correct it or mark it superseded inline" -f $k, ($i + 1))
    }
}
if ($contradictions -eq 0) {
    Add-Info ("no current-status line claims streamed next/previous is absent without its supersession marker (PARITY + LIMITATIONS scanned, {0} such claims)" -f $contradictionClaimLines)
}
if ($correctionMarkers -eq 0) {
    Add-Fail ("the '{0}' supersession marker is gone from PARITY.md/LIMITATIONS.md - the Phase 17 streamed next/previous sentence must keep its correction, not have it deleted" -f $CorrectionMarker)
}
else {
    Add-Info ("supersession marker '{0}' present on {1} line(s)" -f $CorrectionMarker, $correctionMarkers)
}

# ===========================================================================
# Report
# ===========================================================================
if (-not $Quiet) {
    foreach ($i in $script:Infos) { Write-Host "  ok   $i" -ForegroundColor DarkGray }
}
if ($script:Fails.Count -eq 0) {
    Write-Host "`nPASS - all documentation citation checks passed." -ForegroundColor Green
    exit 0
}
Write-Host "`nFAIL - $($script:Fails.Count) problem(s):" -ForegroundColor Red
foreach ($f in $script:Fails) { Write-Host "  $f" -ForegroundColor Red }
exit 1
