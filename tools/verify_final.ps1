# DualMessenger acceptance test (run BEFORE flash = baseline, AFTER flash+reboot = verify)
# Usage: .\verify_final.ps1
$PKG = 'com.evolution.dualmessenger'
$fail = 0
function Check([string]$name, [bool]$ok, [string]$detail='') {
  if ($ok) { Write-Host "[PASS] $name $detail" } else { Write-Host "[FAIL] $name $detail"; $script:fail++ }
}
Write-Host '=== 1) package installed ==='
$p = adb shell pm list packages --user 0 2>&1 | Out-String
Check 'installed' ($p -match [regex]::Escape($PKG))
Write-Host '=== 2) location is priv-app ==='
$path = adb shell pm path $PKG 2>&1 | Out-String
Write-Host $path.Trim()
Check 'priv-app' ($path -match 'priv-app')
Write-Host '=== 3) privileged grants ==='
$d = adb shell dumpsys package $PKG 2>&1 | Out-String
foreach ($perm in @('MANAGE_USERS','CREATE_USERS','INSTALL_PACKAGES','DELETE_PACKAGES','INTERACT_ACROSS_USERS_FULL','MANAGE_PROFILE_AND_DEVICE_OWNERS','PACKAGE_USAGE_STATS')) {
  Check $perm ($d -match [regex]::Escape($perm) + ': granted=true')
}
Write-Host '=== 4) launch MainActivity (no crash) ==='
adb shell am force-stop $PKG 2>&1 | Out-Null
Start-Sleep -Seconds 1
$l = try { adb shell am start -n "$PKG/.MainActivity" 2>&1 | Out-String } catch { "$_" }
Write-Host $l.Trim()
Start-Sleep -Seconds 3
$crash = adb shell dumpsys activity activities 2>&1 | Out-String
Check 'no crash dialog' ($crash -notmatch 'Application Error.*dualmessenger')
Write-Host '=== 5) users ==='
Write-Host (adb shell pm list users 2>&1 | Out-String).Trim()
Write-Host ''
if ($fail -eq 0) { Write-Host 'ALL CHECKS PASSED' } else { Write-Host "$fail CHECK(S) FAILED" }
