<div align="center">

# CLONIX

### Independent Android app cloning with isolated profiles, separate app data, and ROM/root integration

<img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
<img src="https://img.shields.io/badge/Integration-AOSP%20%7C%20Root-2563EB?style=for-the-badge" alt="AOSP or Root" />
<img src="https://img.shields.io/badge/Language-Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java" />

</div>

---

## Overview

**CLONIX** is an Android app-cloning project designed to run multiple isolated copies of the same installed application while keeping each clone's data separate.

Instead of duplicating APK files, CLONIX reuses the installed package across isolated Android users/profiles. It can be integrated directly into a custom ROM with platform privileges or used from a rooted device through the included root-module workflow.

## Key Features

- **Multiple isolated app copies** with separate application data.
- **Shared APK, isolated state** to avoid unnecessary package duplication.
- **Numbered clone badges** based on the original application icon.
- **Per-clone storage view** for easier management.
- **Custom clone nicknames** stored alongside clone configuration.
- **Home-screen shortcuts** through a launcher trampoline without requiring a launcher patch.
- **Optional Launcher3/Trebuchet integration** for deeper ROM integration.
- **System Settings integration** through a dedicated intent entry point.
- **ROM and root deployment paths**, including KernelSU/Magisk-style module support.

## How It Works

| Slot | Isolation strategy |
| --- | --- |
| Copy 1 | Hidden clone-profile container (`CloneSpace`) |
| Copies 2–8 | Hidden secondary Android users (`Clone_2`, …) |

CLONIX uses Android's multi-user/profile capabilities to keep clone data isolated while launching the existing package in the selected clone context.

## Main Components

| Component | Purpose |
| --- | --- |
| `CloneManager.java` | Creates clone slots, installs existing packages for the target user, and launches clones |
| `CloneDatabase.java` | Stores package-to-clone mappings and custom nicknames |
| `MainActivity.java` | Main Material-style management interface |
| `DualBadgeUtil.java` / `BadgeSettings.java` | Generates numbered clone badges |
| `CloneLauncherTrampoline.java` | Provides launcher shortcuts without a launcher patch |
| `StorageActivity.java` | Displays storage grouped by original app and clone |
| `settings_integration/` | Optional system Settings entry point |
| `launcher_patch/` | Optional Launcher3/Trebuchet drawer integration |
| `ksu-module/` | Root installation path for supported root managers |
| `tools/` | ADB verification and ROM sync/build helpers |

## ROM Integration

Add CLONIX to your product configuration:

```make
PRODUCT_PACKAGES += Clonix
```

See `INTEGRATION.mk` for the repository-specific integration details.

## Standalone / Root Usage

For a standalone installation, build the APK and grant the required privileged/root capabilities, or use the included root-module workflow where appropriate.

> CLONIX relies on Android capabilities that ordinary third-party applications do not receive by default. Platform-signature integration or root access is therefore required for its full feature set.

## Project Scope

CLONIX is intended for **custom-ROM development, rooted-device experimentation, and controlled app-isolation workflows**. Behavior can vary by Android version, vendor framework changes, launcher implementation, and user/profile restrictions.

---

<div align="center">

**Built for Android users and ROM developers who want app cloning without giving every clone the same data.**

</div>
