# Clonix - تجربة adb بدون بناء روم (Windows PowerShell)
# يحاكي بالضبط ما تفعله CloneManager.java: نفس الـAPK + userId مخفي مختلف
# الاستخدام:
#   .\dual_test.ps1 -Action list
#   .\dual_test.ps1 -Action clone -Pkg com.whatsapp -Slot 1
#   .\dual_test.ps1 -Action clone -Pkg com.whatsapp -Slot 2
#   .\dual_test.ps1 -Action launch -Pkg com.whatsapp -Slot 1
#   .\dual_test.ps1 -Action storage -Pkg com.whatsapp
#   .\dual_test.ps1 -Action unclone -Pkg com.whatsapp -Slot 1
#   .\dual_test.ps1 -Action cleanup
param(
  [ValidateSet('check','list','clone','launch','storage','unclone','cleanup')]
  [string]$Action = 'list',
  [string]$Pkg = '',
  [int]$Slot = 1
)

$ErrorActionPreference = 'Stop'

function Run-Adb([string]$Args) {
  $out = & adb $Args.Split(' ') 2>&1 | Out-String
  return $out.Trim()
}
function Shell([string]$Cmd) {
  $out = & adb shell $Cmd 2>&1 | Out-String
  return $out.Trim()
}

function Get-Users {
  Write-Host '--- pm list users ---'
  Write-Host (Shell 'pm list users')
  Write-Host '--- dumpsys user (short) ---'
  Write-Host (Shell 'dumpsys user | findstr /C:"UserInfo"')
}

function Get-OrCreate-Slot([int]$slot) {
  # Slot 1 = CLONE profile CloneSpace (no Work tab), Slot 2+ = secondary Clone_N
  $users = Shell 'pm list users'
  if ($slot -eq 1 -and ($users -match 'CloneSpace')) {
    $id = ([regex]::Match($users, '\{\s*(\d+):CloneSpace').Groups[1].Value)
    if ($id) { Write-Host "Slot1 exists -> user $id"; return [int]$id }
  }
  $name = if ($slot -eq 1) { 'CloneSpace' } else { "Clone_$slot" }
  if ($users -match [regex]::Escape($name)) {
    $id = ([regex]::Match($users, '\{\s*(\d+):' + [regex]::Escape($name)).Groups[1].Value)
    if ($id) { Write-Host "$name exists -> user $id"; return [int]$id }
  }
  Write-Host "Creating $name ..."
  if ($slot -eq 1) {
    $r = Shell 'pm create-user --user-type android.os.usertype.profile.CLONE --profileOf 0 CloneSpace'
    Write-Host $r
  } else {
    $r = Shell "pm create-user $name"
    Write-Host $r
  }
  $users2 = Shell 'pm list users'
  Write-Host $users2
  $m = [regex]::Match($users2, '\{\s*(\d+):' + [regex]::Escape($name))
  if ($m.Success) { return [int]$m.Groups[1].Value }
  # fallback: newest user id
  $ids = [regex]::Matches($users2, '\{\s*(\d+):') | ForEach-Object { [int]$_.Groups[1].Value } | Sort-Object -Descending
  return $ids[0]
}

switch ($Action) {
  'check' {
    Write-Host (Run-Adb 'version')
    Write-Host (Run-Adb 'devices -l')
    Write-Host (Shell 'getprop ro.build.version.release')
    Write-Host (Shell 'whoami')
  }
  'list' { Get-Users }
  'clone' {
    if (-not $Pkg) { throw 'حدد -Pkg مثل com.whatsapp' }
    $uid = Get-OrCreate-Slot $Slot
    Write-Host "install-existing $Pkg -> user $uid ..."
    Write-Host (Shell "pm install-existing --user $uid $Pkg")
    Write-Host (Shell "pm list packages --user $uid | findstr $Pkg")
    Write-Host (Shell "am start-user $uid")
    Write-Host 'OK. افتح النسخة من الأمر launch أو من درج التطبيقات (Work tab إن ظهر).'
  }
  'launch' {
    if (-not $Pkg) { throw 'حدد -Pkg' }
    $uid = Get-OrCreate-Slot $Slot
    Write-Host (Shell "am start-user $uid")
    # monkey يفتح الـLAUNCHER الرئيسي للنسخة داخل يوزرها
    Write-Host (Shell "am start --user $uid -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $Pkg")
  }
  'storage' {
    if (-not $Pkg) { throw 'حدد -Pkg' }
    Write-Host '--- pm path (يجب نفس المسار = نفس الـAPK مشترك) ---'
    Write-Host (Shell "pm path --user 0 $Pkg")
    $users = Shell 'pm list users'
    $ids = [regex]::Matches($users, '\{\s*(\d+):') | ForEach-Object { $_.Groups[1].Value }
    foreach ($id in $ids) {
      Write-Host "--- user $id ---"
      Write-Host (Shell "pm path --user $id $Pkg")
    }
    Write-Host '--- data dirs (الزيادة الحقيقية فقط) ---'
    Write-Host (Shell "du -sh /data/user/0/$Pkg /data/user/1*\/$Pkg 2>/dev/null; ls -d /data/user/*/$Pkg 2>/dev/null")
  }
  'unclone' {
    if (-not $Pkg) { throw 'حدد -Pkg' }
    $uid = Get-OrCreate-Slot $Slot
    Write-Host (Shell "pm uninstall --user $uid $Pkg")
    Write-Host (Shell "pm list packages --user $uid | findstr $Pkg")
    Write-Host 'تم. لو كان Clone_N فارغاً تماماً احذف اليوزر: pm remove-user <id>'
  }
  'cleanup' {
    Get-Users
    Write-Host 'احذف يدوياً بعد التأكد من الـid:'
    Write-Host '  adb shell pm remove-user <id>'
    Write-Host 'مثال: adb shell pm remove-user 11'
  }
}
