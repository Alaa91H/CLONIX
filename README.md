# CLONIX — independent app cloner

Run multiple isolated copies of any app: same APK shared, separate data,
numbered badges on the original icons, per-clone storage view.

## How it works
- Copy 1 = clone-profile container `CloneSpace` (hidden, deleted with parent).
- Copies 2..8 = hidden secondary users `Clone_2…` started in background.
- Privileges via platform signature (ROM build) or root (standalone build).

## Components
- `src/.../CloneManager.java` : create slots + installExistingAsUser + launch
- `CloneDatabase.java` : pkg -> N clones (with custom nicknames)
- `MainActivity.java` : Material3 console + search + badge settings
- `DualBadgeUtil.java` + `BadgeSettings.java` : original icon + numbered badge
- `CloneLauncherTrampoline.java` : home shortcuts without launcher patch
- `StorageActivity.java` : per-clone storage, grouped by original app
- `settings_integration/` : entry from system Settings via intent
- `launcher_patch/` : optional Trebuchet/Launcher3 drawer integration
- `ksu-module/` : root installer (priv-app + allowlist, Magisk/KernelSU)
- `tools/` : adb test/verify scripts + ROM sync/build scripts

## ROM integration
```
PRODUCT_PACKAGES += Clonix
```
See INTEGRATION.mk. Standalone: build APK (Gradle locally or GitHub
Actions) and install normally + grant root, or flash ksu-module.
