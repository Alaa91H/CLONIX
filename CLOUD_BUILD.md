# Final path (no ROM rebuild): cloud APK + KSU Next priv-app module

Proven on your marble: `MANAGE_USERS: granted=true` to a NON-platform
priv-app (GMS) — so this app gets full powers via allowlist, no platform
signature needed.

## Step 1 — cloud build (10 min, no SDK on PC)
1. Create a GitHub repo; upload the CONTENTS of `packages/apps/DualMessenger`
   as the repo root (so `.github/workflows/build.yml` is at top level).
2. Open the repo Actions tab -> run "Build DualMessenger APK"
   (auto-runs on push too).
3. Download artifact `DualMessenger-apk` -> file `DualMessenger.apk`.

## Step 2 — pack the KSU module
1. Copy `DualMessenger.apk` into:
   `ksu-module/system/priv-app/DualMessenger/DualMessenger.apk`
   (delete `PUT_APK_HERE.txt` or leave it, harmless).
2. Zip the CONTENTS of `ksu-module/` (module.prop at zip root):
   select all inside -> Send to ZIP -> `DualMessenger-KSU.zip`.

## Step 3 — flash in KSU Next + reboot
1. KSU Next Manager -> Modules -> Install from storage -> pick the zip.
2. Reboot (mandatory: priv-app + allowlist apply at boot).

## Step 4 — verify powers + use
```bash
adb shell dumpsys package com.evolution.dualmessenger | findstr "MANAGE_USERS INSTALL_PACKAGES"
# expect: granted=true lines
```
Open "Dual Messenger" from drawer:
- Clone any app -> N copies, numbered customizable badge.
- Home shortcuts: long-press a clone -> add to Home (works on Pixel Launcher).
- Storage screen per clone; Settings entry via intent (see settings_integration/).

## Notes / limits (honest)
- Slot 1 uses CLONE profile (no Work tab). If your only need is ONE copy
  per app, the native Settings > Cloned Apps page already does it.
- Secondary slots (2..N) auto-start in background via BootReceiver;
  EvoX userdebug allows the hidden-API reflection this app uses.
- Updating the app = reflash module with new APK (data/DB preserved:
  allowBackup=false but DB lives in /data/user/0, untouched by update).
- Uninstall = remove module in KSU Next + reboot, then optionally
  `pm remove-user <cloneId>` for leftover clone users.
