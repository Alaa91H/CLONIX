Signing material lives here but is gitignored — keep a private backup.

- clonix-release.jks — the stable release key (alias `clonix`,
  password `clonix-sign-2026`). Back this file up safely; losing it
  means every future update needs uninstall + reinstall.
- KEYSTORE_BASE64.txt — single-line base64 of the keystore, ready to
  paste into the GitHub secret `KEYSTORE_BASE64` (see SECRETS_SETUP.md).
