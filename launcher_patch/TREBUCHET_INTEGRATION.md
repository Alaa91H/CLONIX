# Trebuchet/Launcher3 integration — DualMessenger clones in the MAIN drawer
Target: `packages/apps/Launcher3` in your `cnb` tree (Trebuchet or any
Launcher3-based default launcher, incl. Lawnchair source builds).
NOT for Pixel/NexusLauncher (closed source) — for it use DualMessenger
pinned shortcuts instead (already implemented, needs the app built).

Result (Samsung look):
- Clone apps (DualClone profile, slot 1) listed in the MAIN drawer with a
  numbered bottom-corner badge — no Work tab, no briefcase.
- If your only managed profile is ours, the Work tab is hidden entirely.
- Slots 2..N (secondary users) are NOT handled here: launchers lack
  MANAGE_USERS for them; DualMessenger shortcuts cover those.

Files (same folder as this guide):
- `CloneAppSource.java` -> copy to `src/com/evolution/launcherclone/CloneAppSource.java`
- `CloneBadge.java`      -> copy to `src/com/evolution/launcherclone/CloneBadge.java`

## Step 1 — add the two files
```bash
cd packages/apps/Launcher3
mkdir -p src/com/evolution/launcherclone
cp <DualMessenger>/launcher_patch/CloneAppSource.java src/com/evolution/launcherclone/
cp <DualMessenger>/launcher_patch/CloneBadge.java      src/com/evolution/launcherclone/
```

## Step 2 — merge clones into the all-apps list
Find where your Launcher3 version loads LauncherApps activities into the
drawer model (grep anchor):
```bash
grep -rn "getActivityList" src/com/android/launcher3/ | head
```
Typically `LoaderTask` / `AllAppsList` / `AlphabeticalAppsList` region that
iterates `LauncherActivityInfo`. Right after that loop, insert:
```java
// DualMessenger: clones in main drawer (Samsung-style, no Work tab).
for (com.evolution.launcherclone.CloneAppSource.CloneTarget t :
        com.evolution.launcherclone.CloneAppSource.load(mContext)) {
    // addItem signature varies by version; adapt:
    // allAppsList.add(new AppInfo(t.component, t.label, t.user, ...));
    // Mark the item so Step 3 can badge it, e.g. store t.slot in AppInfo:
    // appInfo.runtimeStatusFlags / custom field `cloneSlot = t.slot;`
}
```
Notes:
- `load()` needs no extra permission for profiles; called on the loader
  background thread (it does binder calls).
- Re-query on `LauncherApps` callbacks (`onPackageAdded/Removed`,
  `ACTION_MANAGED_PROFILE_AVAILABLE/UNAVAILABLE`) so toggles apply live.

## Step 3 — badge the clone icons
Where the drawer row binds its icon (grep anchor):
```bash
grep -rn "setIcon\|getBadgedIcon\|FastBitmapDrawable" \
  src/com/android/launcher3/BubbleTextView.java | head
```
Wrap the icon for marked items:
```java
// DualMessenger: numbered bottom-corner badge instead of work briefcase.
if (itemInfo.cloneSlot > 0) {
    icon = com.evolution.launcherclone.CloneBadge.apply(
            getContext(), icon, itemInfo.cloneSlot);
}
```
`itemInfo.cloneSlot` is the field you added in Step 2 (default 0 = normal app).
To change color/style ROM-side, edit `CloneBadge.DEFAULT_COLOR` /
`STYLE_NUMBER_ONLY` (per-user badge prefs live in DualMessenger and apply
to its pinned shortcuts).

## Step 4 — hide the Work tab when only ours exists
Find the work-tab gate (grep anchor, name varies by version):
```bash
grep -rn "isManagedProfile\|isWorkProfile\|WORK_PROFILE\|hasWorkProfile" \
  src/com/android/launcher3/ | head -20
```
At the boolean that decides "show work tab", add:
```java
showWorkTab = showWorkTab
    && !com.evolution.launcherclone.CloneAppSource.onlyOursManagedProfiles(context);
```
`onlyOursManagedProfiles()` returns false whenever a REAL enterprise work
profile exists (or on any error), so default behavior is preserved and the
tab only disappears for our clone setup.

## Step 5 — build + verify on marble
```bash
m Launcher3
# set Trebuchet as default on device, then:
adb shell pm list users                 # expect DualClone (u12)
adb shell pm list packages --user 12    # expect org.telegram.messenger
# open drawer: Telegram appears in MAIN list with numbered badge, no Work tab
```
If the drawer lacks the clone on your Launcher3 version, the grep anchors
moved: re-grep Step 2's anchor and relocate the same 6-line block — the
helper APIs (`CloneAppSource.load`) are version-independent.

## Scope reminder
- Slot 1 (CLONE profile)  -> this patch (main drawer icon + badge).
- Slots 2..N (secondary)  -> DualMessenger pinned shortcuts (system uid).
- Managed fallback (u10)  -> Work tab hidden by Step 4; also launchable
  from shortcuts. Prefer CLONE (already the default in CloneManager).
