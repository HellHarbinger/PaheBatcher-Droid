# H-1 extended operator protocol WITH evidence capture (review round-3 finding NEW-3).
# v2: sync on logcat ("Starting download work for ep-real-pd") instead of run-as
#     (device current user is 10; shell run-as runs in user 0 context and cannot
#     read /data/user/10 app files - Android multi-user restriction).
# Sequence: seed(-e leaveSeed true) -> sync on worker-start log line + settle
#     -> capture pre-kill pid -> force-stop (REAL death, instrumentation dies)
#     -> capture seed instrument failure output -> relaunch -> capture post-relaunch
#     -> cleanup -e verifyAttemptCount true (asserts record + different pid + same
#     request + advanced runAttemptCount) -> dump full logcat buffer.
# Expected: seed instrument shows a process-died failure; cleanup "OK (1 test)".
$ErrorActionPreference = "Continue"
$ser = $env:PB_DEVICE_SERIAL
if (-not $ser) { throw 'PB_DEVICE_SERIAL is not set - export the target device adb serial first' }
$pkg = "com.pahebatcher.app"
$runner = "com.pahebatcher.app.test/androidx.test.runner.AndroidJUnitRunner"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) '.investigation\phase14'
New-Item -ItemType Directory -Force $out | Out-Null
function Note($m) { "$(Get-Date -Format 'HH:mm:ss') $m" | Out-File "$out\README.txt" -Append }

Remove-Item "$out\README.txt" -ErrorAction SilentlyContinue
Note "protocol v2 start (device $ser, current user $((adb -s $ser shell am get-current-user).Trim()))"

adb -s $ser logcat -c

# 1. Seed in a background job: seeds QUEUED row, enqueues Worker, waits RUNNING,
#    asserts baseline runAttemptCount==1, writes record, grace-sleeps 30s.
$seedJob = Start-Job -ScriptBlock {
    param($ser, $runner)
    adb -s $ser shell am instrument -w -e class com.pahebatcher.app.data.download.RealProcessDeathSeedTest -e leaveSeed true $runner 2>&1 | Out-String
} -ArgumentList $ser, $runner

# 2. Sync on the worker-start log line (appears just before the baseline assert
#    + record write; the 30s grace sleep follows).
$synced = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 1
    $d = (adb -s $ser logcat -d 2>&1 | Out-String)
    if ($d -match "Starting download work for ep-real-pd") { $synced = $true; break }
}
if (-not $synced) {
    Note "FAILED: worker-start log line never appeared"
    adb -s $ser logcat -d 2>&1 | Out-File "$out\h1-protocol-logcat.txt"
    Stop-Job $seedJob -ErrorAction SilentlyContinue; Remove-Job $seedJob -Force -ErrorAction SilentlyContinue
    adb -s $ser shell am instrument -w -e class com.pahebatcher.app.data.download.RealProcessDeathCleanupTest $runner 2>&1 | Out-String | Out-File "$out\h1-protocol-cleanup-instrument.txt"
    exit 1
}
Start-Sleep -Seconds 5   # record file written within this window (post-assert)
Note "worker-start log line seen; +5s settle for record write"

# 3. Pre-kill PID evidence (dumpsys/pidof span user 10; run-as does not).
$prePid = (adb -s $ser shell pidof $pkg 2>&1 | Out-String).Trim()
Note "pre-kill pid: '$prePid'"
if (-not $prePid) {
    Note "FAILED: no app process visible before force-stop"
    Stop-Job $seedJob -ErrorAction SilentlyContinue; Remove-Job $seedJob -Force -ErrorAction SilentlyContinue
    adb -s $ser shell am instrument -w -e class com.pahebatcher.app.data.download.RealProcessDeathCleanupTest $runner 2>&1 | Out-String | Out-File "$out\h1-protocol-cleanup-instrument.txt"
    exit 1
}

# 4. REAL process death mid-run (instrumentation inside the 30s grace sleep).
adb -s $ser shell am force-stop $pkg
Start-Sleep -Seconds 2
$postKill = (adb -s $ser shell pidof $pkg 2>&1 | Out-String).Trim()
Note "force-stop issued; pidof after kill: '$postKill' (empty = process really dead)"

# 5. Collect the seed instrument result (expected: process-died failure signal).
$done = Wait-Job $seedJob -Timeout 45
if ($done) { $seedOut = (Receive-Job $seedJob -Wait | Out-String) }
else { $seedOut = "(seed instrument output still pending after 45s; command hung after force-stop)`n"; Stop-Job $seedJob }
Remove-Job $seedJob -Force -ErrorAction SilentlyContinue
$seedOut | Out-File "$out\h1-protocol-seed-instrument.txt"
Note "seed instrument output collected ($($seedOut.Length) chars)"

# 6. Relaunch: WorkManager force-stop reschedule + reconciler recover the work.
adb -s $ser shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 6
$relPid = (adb -s $ser shell pidof $pkg 2>&1 | Out-String).Trim()
Note "relaunched; post-relaunch pid: '$relPid' (must differ from pre-kill '$prePid')"

# 7. Verify + cleanup: different pid, same request, advanced runAttemptCount
#    (asserted against the record file the seed wrote inside the app sandbox).
$clean = (adb -s $ser shell am instrument -w -e class com.pahebatcher.app.data.download.RealProcessDeathCleanupTest -e verifyAttemptCount true $runner 2>&1 | Out-String)
$clean | Out-File "$out\h1-protocol-cleanup-instrument.txt"

# 8. Dump the logcat buffer captured across the whole protocol.
adb -s $ser logcat -d -v time 2>&1 | Out-File "$out\h1-protocol-logcat.txt"

$verdict = if ($clean -match "OK \(1 test\)") { "PASS" } else { "FAIL" }
Note "cleanup finished; verdict=$verdict"
Note "protocol v2 end"
"VERDICT=$verdict"
