# Final path (no ROM rebuild): APK + root, or KSU priv-app module

## Option A — normal install + root (simplest, recommended)
1. Build: local Gradle (`gradle-build/`) or push this folder to GitHub
   and download the `Clonix-apk` Actions artifact.
2. `adb install Clonix.apk`
3. Grant root to CLONIX in the root manager (Superuser tab).
4. Open CLONIX, clone any app, pin home shortcuts with numbered badges.

## Option B — KSU/Magisk priv-app module (persistent system powers)
1. Copy `Clonix.apk` into:
   `ksu-module/system/priv-app/CLONIX/Clonix.apk`
2. Zip the CONTENTS of `ksu-module/` -> flash in root manager -> reboot.
3. Verify:
```bash
adb shell dumpsys package com.clonix.app | findstr "MANAGE_USERS INSTALL_PACKAGES"
# expect: granted=true lines
```

## Notes / limits (honest)
- Copy 1 uses the OS clone profile; copies 2..N use background users
  (auto-restarted by BootReceiver; needs root grant to persist).
- Updating the app = reinstall APK (clone DB in /data/user/0 preserved).
- Uninstall module/APK, then optionally `pm remove-user <cloneId>`.
