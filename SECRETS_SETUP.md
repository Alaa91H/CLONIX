# GitHub Actions signing secrets

The APK on GitHub Actions is signed with the same stable key as local builds,
so CI artifacts install over existing installs without uninstall/reinstall.

## One-time setup (repo owner only)

The keystore file (`keystore/clonix-release.jks`) is **not** in the repo —
keep a private backup of it. If it is lost, the signing identity is lost and
future updates require uninstall + reinstall.

Add four secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Base64 of `keystore/clonix-release.jks` (one single line — see below) |
| `KEYSTORE_PASSWORD` | the keystore/store password |
| `KEY_ALIAS` | `clonix` |
| `KEY_PASSWORD` | the key password |

Produce the `KEYSTORE_BASE64` value locally:

```bash
base64 -w0 keystore/clonix-release.jks | clip   # Windows Git Bash (clips to clipboard)
```

or `base64 keystore/clonix-release.jks` and paste the output manually.

## How the build picks the key

`gradle-build/app/build.gradle` signing priority:

1. CI env (`KEYSTORE_BASE64` + `KEYSTORE_PASSWORD`) — GitHub Actions
2. `gradle-build/keystore.properties` (gitignored) — local maintainer builds
3. No keystore available → debug-signed fallback so forks still get an APK
