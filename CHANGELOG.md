# Changelog

All notable changes to **CLONIX** are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [2.2.0] — 2026-09-15

### Added
- **Auto-backup on install/update** (opt-in, Settings → Diagnostics & tools) —
  when a tracked app is installed or updated, CLONIX now archives it
  automatically. Engineered for Android 14+: the package broadcast only
  schedules an expedited `JobScheduler` job; the backup itself runs on the
  shared engine lane with the usual force-stop → snapshot → report flow,
  honoring the per-app blacklist.
- **Home-screen widget** — scheduled-backup state, last-backup age
  (never/min/hour/day), a one-tap *Back up now* action routed through the
  operation queue, and a refresh button. Updates are event-driven (after
  every operation) plus a battery-cheap 3-hour safety period — no alarms,
  no polling.
- **Settings export/import** — one JSON file captures every preference
  store (settings, backup options incl. blacklist, schedules, badge
  style) so a new device or fresh install restores the whole
  configuration in one tap. Export via the sharesheet; import via the
  system document picker with count confirmation. Session passwords and
  backup data are deliberately excluded.
- **15 new locales** for the new surfaces: de, es, fr, it, ja, ko, ru,
  tr, vi, zh‑CN, id, ms, th, hi, pt (on top of en/ar) — widget texts,
  auto-backup and export/import strings fully translated.

### Improved
- Widget and operation-queue integration: every queued op now refreshes
  the widget on completion, so the last-backup age stays honest without
  any scheduled work.
- Settings import re-syncs schedulers (backup jobs, auto-freeze) so
  imported preferences take effect immediately.

### Compatibility
- Same stable signing key — installs directly over v2.1/v2.0.
- The auto-backup receiver is `exported=false` and ignores its own
  package; both package broadcasts are system-protected, so no new
  permission was required.

## [2.1.0] — 2026-09-15

### Added
- **Restore integrity gate** — every archive is verified (SHA‑256 sidecar +
  tar listing, decrypted in place for encrypted archives) *before* any
  device data is touched; a corrupted archive now aborts with a clear
  explanation instead of half-restoring.
- **Battery-optimization exemption** (Settings → System) — one tap requests
  the Android "unrestricted battery" exemption so scheduled backup jobs run
  on time instead of being deferred for hours in Doze; live status summary
  and deep link to the system screen.
- **Material You toggle** (Settings → Appearance) — dynamic wallpaper-based
  color can now be turned off for the fixed brand palette; applies on next
  app restart.
- **Notification permission gate** — on Android 13+, CLONIX asks for
  `POST_NOTIFICATIONS` on first launch so backup/restore results are never
  silently dropped.
- Professional English changelog (this file) and a signed CI release
  pipeline documented in `SECRETS_SETUP.md`.

### Improved
- Scheduled backups confirmed to use persisted `JobScheduler` with
  charging/idle constraints — reliable across reboots, compliant with
  modern background-execution policy (no exact-alarm hacks).
- Restore wizard UI: verification progress now shown as "Verifying…" before
  the restore step begins.

### Compatibility
- APK signature unchanged (stable release key, `CN=CLONIX`) — installs
  directly over v2.0 without uninstall.
- Data compatibility with pre-rebrand `clonepilot` archives and directories
  is preserved (one-time migration).

## [2.0.0] — 2026-09-14

### Added
- **CLONIX identity** — new package (`com.clonix.app`), app name, neutral
  class names, and a Double‑C adaptive launcher icon (balanced glyph,
  gradient background, monochrome variant) with matching quick-settings
  tile icon.
- **Unified app structure** — Home as the single entry point; legacy
  duplicate screens (old main list, dashboard) retired; Backup Center
  consolidated into Archives; Settings regrouped into five clear sections
  with live glanceable summaries.
- **Multi-select in Apps** — tap an app icon to enter selection mode:
  per-row checkboxes, a select‑all bar with live count, and batch actions
  over the selection.
- **Maintenance toolkit** (Settings → Diagnostics) — invalid-archive scan
  with review-and-delete, and apply-retention-now, both running through
  the operation queue with engine logging.
- **Storage preflight** — free-space check on the target volume before
  backup/restore/batch operations, with a clear warning dialog.
- **Determinate batch progress** — x/y of N in the ongoing notification
  and in-app batch status, plus a completion summary.
- **Operation queue** (`OpsQueue`) with wake-lock around long operations
  so multi-app runs survive screen-off, and an in-app engine log viewer
  with share/clear.
- **Per-app schedules with day/hour units** and unified backup management.

### Changed
- Retention, verification scheduling, and freeze behavior moved onto
  persisted system jobs; scheduled runs force-stop clones first for
  consistent snapshots.
- Engine path neutralized to `/data/misc/clonix` with automatic migration
  from the legacy location on first run.

### Fixed
- Filter sheet toggles not applying in the Apps list.
- Launcher glyph optically off-center (corrected in all icon variants).
- Batch sheets ignoring the active selection; stale engine-log labels.

[Unreleased]: see repository commits.
