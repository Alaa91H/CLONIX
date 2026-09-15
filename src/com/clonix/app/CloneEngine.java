package com.clonix.app;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Core cloner. One extra copy per app minimum, up to N copies.
 * NO work profile by design for slot 1.
 * - Slot 1 = CLONE profile ("CloneSpace"): the OS-native clone container.
 *   Hidden from launcher, deleted with parent, no Work tab, no briefcase.
 * - Slot 2..N are hidden secondary users ("Clone_2"...), started in background.
 * - Managed profile ("CloneWork") is only a legacy fallback.
 * - Same APK shared, only /data/user/<id>/ differs -> minimal storage/RAM.
 * Privileges come from either platform signature (ROM build) or root
 * (user builds) via SysApi/ShellEngine dual paths.
 */
public class CloneEngine {
    private static final String TAG = "Clonix";
    public static final String PROFILE_NAME = "CloneWork"; // legacy managed fallback name
    public static final String CLONE_PROFILE_NAME = "CloneSpace"; // slot 1, no work tab
    public static final String USER_TYPE_CLONE = "android.os.usertype.profile.CLONE";
    public static final String SECONDARY_PREFIX = "Clone";
    /** Local copy: DevicePolicyManager.SKIP_SETUP_WIZARD is hidden from public SDK (=1). */
    private static final int SKIP_SETUP_WIZARD = 0x0001;
    public static final int MAX_CLONES_PER_APP = 8;
    public static final int MAX_TOTAL_SLOTS = 8;

    public static ComponentName admin(Context c) {
        return new ComponentName(c, DeviceAdminReceiver.class);
    }

    // ---------- user helpers ----------

    public static List<UserHandle> allCloneUsers(Context c) {
        List<UserHandle> out = new ArrayList<>();
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        try {
            for (SysApi.User ui : SysApi.getUsers(um)) {
                if (ui.id == 0) continue;
                boolean isOurs = isOursName(ui.name);
                try {
                    if (SysApi.isManagedProfile(um, ui.id)) {
                        // Only treat ours as clone; ignore real Work profile.
                        if (isOurs) out.add(ui.handle);
                    } else if (isOurs) {
                        out.add(ui.handle);
                    }
                } catch (Throwable t) {
                    if (isOurs) out.add(ui.handle);
                }
            }
        } catch (Throwable t) { Log.e(TAG, "getUsers failed", t); }
        return out;
    }

    /** Ours by container name (our containers only, never foreign work profiles). */
    public static boolean isOursName(String name) {
        return name != null && (name.equals(PROFILE_NAME)
                || name.equals(CLONE_PROFILE_NAME)
                || name.startsWith(SECONDARY_PREFIX));
    }

    public static int managedProfileUserId(Context c) {
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        for (UserHandle h : allCloneUsers(c)) {
            try {
                int id = SysApi.getIdentifier(h);
                if (SysApi.isManagedProfile(um, id)) return id;
            } catch (Throwable ignore) {}
        }
        return -1;
    }

    /** Slot 1 user: CLONE profile preferred (no Work tab), managed only as fallback. */
    public static int primaryCloneUserId(Context c) {
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        for (UserHandle h : allCloneUsers(c)) {
            try {
                SysApi.User ui = SysApi.getUserInfo(um, SysApi.getIdentifier(h));
                if (ui != null && CLONE_PROFILE_NAME.equals(ui.name)) return ui.id;
            } catch (Throwable ignore) {}
        }
        return managedProfileUserId(c);
    }

    /**
     * Create CLONE profile silently (System app, no DeviceAdmin provisioning UI,
     * no Work tab). Falls back to legacy managed profile, then to shell cmds.
     */
    public static int ensureCloneProfile(Context c) {
        int existing = primaryCloneUserId(c);
        if (existing >= 0) return existing;
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        // UserManager.createProfile(String, String userType, Set<String>) - SystemApi
        try {
            Method m = UserManager.class.getMethod("createProfile",
                    String.class, String.class, java.util.Set.class);
            Object uinfo = m.invoke(um, CLONE_PROFILE_NAME, USER_TYPE_CLONE,
                    java.util.Collections.emptySet());
            int id = SysApi.idOf(uinfo);
            Log.i(TAG, "created clone profile=" + id);
            startUserInBackground(id);
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "createProfile(CLONE) failed, ROOT fallback: pm create-user", t);
        }
        try {
            int id = ShellEngine.createCloneProfile(CLONE_PROFILE_NAME);
            Log.i(TAG, "created clone profile=" + id + " (ROOT)");
            startUserInBackground(id);
            return id;
        } catch (Throwable t2) {
            Log.w(TAG, "clone ROOT fallback failed, trying managed", t2);
        }
        return ensureManagedProfile(c);
    }

    /** Create managed profile silently (System app with MANAGE_PROFILE_AND_DEVICE_OWNERS). */
    public static int ensureManagedProfile(Context c) {
        int existing = managedProfileUserId(c);
        if (existing >= 0) return existing;
        int userId = -1;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName a = admin(c);
            // Hidden API via SysApi (compiles on public SDK too).
            userId = SysApi.createAndManageUser(dpm, a, PROFILE_NAME, a, null,
                    SKIP_SETUP_WIZARD);
            Log.i(TAG, "created managed profile=" + userId);
            try { dpm.setProfileName(a, PROFILE_NAME); } catch (Throwable ignore) {}
            // Hide cross-profile contacts by default.
            try { dpm.setCrossProfileContactsSearchDisabled(a, true); } catch (Throwable ignore) {}
        } catch (Throwable t) {
            Log.w(TAG, "createAndManageUser failed, ROOT fallback: pm create-user", t);
            try {
                userId = ShellEngine.createManagedProfile(PROFILE_NAME);
                Log.i(TAG, "created managed profile=" + userId + " (ROOT)");
            } catch (Throwable t2) {
                Log.e(TAG, "ensureManagedProfile failed (need privapp perms or root).", t2);
                return -1;
            }
        }
        startUserInBackground(userId);
        return userId;
    }

    /** Create a hidden secondary user slot for clone number n. Returns userId or -1. */
    public static int createSecondarySlot(Context c, int slotIndex) {
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        String name = SECONDARY_PREFIX + "_" + slotIndex;
        // Avoid duplicates
        for (UserHandle h : allCloneUsers(c)) {
            try {
                SysApi.User ui = SysApi.getUserInfo(um, SysApi.getIdentifier(h));
                if (ui != null && name.equals(ui.name)) return ui.id;
            } catch (Throwable ignore) {}
        }
        // Try modern API first, then legacy, then ROOT shell (proven cmds).
        int userId = -1;
        userId = tryCreateUserReflect(um, name);
        if (userId < 0) {
            try {
                userId = ShellEngine.createUser(name);
                Log.i(TAG, "created secondary slot " + name + " -> " + userId + " (ROOT)");
            } catch (Throwable t) {
                Log.e(TAG, "createUser failed (need privapp perms or root)", t);
            }
        }
        if (userId >= 0) startUserInBackground(userId);
        Log.i(TAG, "created secondary slot " + name + " -> " + userId);
        return userId;
    }

    private static int tryCreateUserReflect(UserManager um, String name) {
        // 1) UserManager.createUser(String name, String userType, int flags)
        try {
            Method m = UserManager.class.getMethod("createUser",
                    String.class, String.class, int.class);
            // USER_TYPE_FULL_SECONDARY = "android.os.usertype.full.SECONDARY"
            Object uinfo = m.invoke(um, name, "android.os.usertype.full.SECONDARY", 0);
            return SysApi.idOf(uinfo);
        } catch (Throwable ignore) {}
        // 2) Legacy createUser(String, int)
        try {
            Method m = UserManager.class.getMethod("createUser", String.class, int.class);
            Object uinfo = m.invoke(um, name, 0);
            return SysApi.idOf(uinfo);
        } catch (Throwable e) { Log.e(TAG, "createUser reflect failed", e); }
        return -1;
    }

    /** Keep clone users alive so notifications/services work without switching. */
    public static void startUserInBackground(int userId) {
        // ActivityManager.getService() is hidden: reach it via reflection so
        // public-SDK builds compile too.
        try {
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            Method getService = amClass.getMethod("getService");
            Object iam = getService.invoke(null);
            for (Method m : iam.getClass().getMethods()) {
                if (m.getName().equals("startUserInBackground")
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == int.class) {
                    m.invoke(iam, userId);
                    Log.i(TAG, "startUserInBackground ok " + userId);
                    return;
                }
            }
            // Newer: startUserInBackgroundWithListener(int, IProgressListener)
            for (Method m : iam.getClass().getMethods()) {
                if (m.getName().equals("startUserInBackgroundWithListener")) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == int.class) {
                        m.invoke(iam, userId, null);
                        return;
                    }
                }
            }
        } catch (Throwable t) { Log.w(TAG, "startUserInBackground reflect failed: " + t); }
        // ROOT fallback: proven `am start-user` (plain form; some builds reject -f).
        try {
            ShellEngine.startUser(userId);
        } catch (Throwable ignore) {}
    }

    public static void startAllClonesInBackground(Context c) {
        for (SysApi.User u : SysApi.safeGetUsers(c)) {
            if (u.id == 0) continue;
            if (!isOursName(u.name)) continue;
            try { startUserInBackground(u.id); }
            catch (Throwable ignore) {}
        }
    }

    // ---------- package ops (same APK, no resign) ----------

    /** Returns true if pm accepted the package for that user. */
    public static boolean installExistingAsUser(Context c, String pkg, int userId) {
        PackageManager pm = c.getPackageManager();
        // Hidden API via SysApi (compiles on public SDK too; ROOT fallback inside).
        try {
            int res = SysApi.installExistingPackageAsUser(pm, pkg, userId);
            Log.i(TAG, "installExisting " + pkg + " u" + userId + " -> " + res);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "installExisting failed, verifying state", t);
        }
        // Verify: is it installed now (e.g. was already installed)?
        return isInstalledAsUser(c, pkg, userId);
    }

    public static boolean uninstallAsUser(Context c, String pkg, int userId) {
        PackageManager pm = c.getPackageManager();
        try {
            // API 28+: uninstallExistingPackageAsUser
            Method m = PackageManager.class.getMethod("uninstallExistingPackageAsUser",
                    String.class, int.class);
            Object r = m.invoke(pm, pkg, userId);
            if (r instanceof Boolean) return (Boolean) r;
            return true;
        } catch (NoSuchMethodException ns) {
            // Fallback: deletePackageAsUser
            try {
                for (Method m : PackageManager.class.getMethods()) {
                    if (m.getName().equals("deletePackageAsUser")) {
                        // deletePackageAsUser(String, IPackageDeleteObserver, int flags, int userId)
                        m.invoke(pm, pkg, null, 0, userId);
                        return true;
                    }
                }
            } catch (Throwable t) { Log.e(TAG, "deletePackageAsUser failed", t); }
        } catch (Throwable t) { Log.e(TAG, "uninstall failed", t); }
        // ROOT fallback: proven `pm uninstall --user` cmd.
        try {
            ShellEngine.uninstall(pkg, userId);
            return true;
        } catch (Throwable t) { Log.e(TAG, "ROOT uninstall failed", t); }
        return !isInstalledAsUser(c, pkg, userId);
    }

    public static boolean isInstalledAsUser(Context c, String pkg, int userId) {
        try {
            PackageManager pm = c.getPackageManager();
            // Hidden API via SysApi; throws NameNotFoundException when absent.
            SysApi.getPackageInfoAsUser(pm, pkg, userId);
            return true;
        } catch (Throwable t) { return false; }
    }

    /**
     * Bulk installed-map for ONE user: one cached `pm list packages` instead
     * of N `pm path` calls. For orphan scans and storage validation.
     */
    public static java.util.Set<String> installedSetForUser(Context c, int userId) {
        try {
            // DIRECT per-package probes are cheap on ROM builds; shell list is
            // cheaper on ROOT builds. Prefer the cached shell list: 1 su total.
            return new java.util.HashSet<>(ShellEngine.listPackages(userId));
        } catch (Throwable t) {
            return new java.util.HashSet<>();
        }
    }

    // ---------- high-level: N clones ----------

    /**
     * Clone pkg into next free slot.
     * @return userId used, or -1 on failure (e.g. max reached).
     */
    public static int cloneToNextSlot(Context c, CloneStore db, String pkg,
                                      String nickname, boolean separateContacts) {
        List<CloneStore.Clone> existing = db.listForPkg(pkg);
        if (existing.size() >= MAX_CLONES_PER_APP) return -1;
        int slotIndex = db.nextSlotIndex(pkg);

        int userId = -1;
        if (slotIndex == 1) {
            userId = ensureCloneProfile(c); // CLONE type: no Work tab (managed fallback inside)
            if (userId < 0) userId = createSecondarySlot(c, slotIndex);
        } else {
            // Reuse an existing empty secondary slot if possible, else create new
            userId = findEmptySecondarySlot(c, db);
            if (userId < 0) userId = createSecondarySlot(c, slotIndex);
        }
        if (userId < 0) return -1;

        if (!installExistingAsUser(c, pkg, userId)) return -1;

        // Separate-contacts: enforced for managed fallback profile.
        // CLONE profile shares media/contacts with parent by platform design;
        // secondary users are isolated by default; nothing to do for those.
        try {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            if (SysApi.isManagedProfile(um, userId)) {
                DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
                dpm.setCrossProfileContactsSearchDisabled(admin(c), separateContacts);
            }
        } catch (Throwable ignore) {}

        CloneStore.Clone cl = new CloneStore.Clone();
        cl.pkg = pkg; cl.userId = userId; cl.slotIndex = slotIndex;
        cl.nickname = nickname; cl.separateContacts = separateContacts;
        db.add(cl);
        return userId;
    }

    /**
     * Single user-enumeration snapshot of OUR containers (one su at most via
     * ShellEngine cache). All slot/user decisions should derive from this
     * list instead of calling safeGetUsers/allCloneUsers repeatedly.
     */
    static List<SysApi.User> ourUsersSnapshot(Context c) {
        List<SysApi.User> out = new ArrayList<>();
        UserManager um = null;
        try { um = (UserManager) c.getSystemService(Context.USER_SERVICE); } catch (Throwable ignore) { }
        for (SysApi.User u : SysApi.safeGetUsers(c)) {
            if (u.id == 0 || !isOursName(u.name)) continue;
            out.add(u);
        }
        // Annotate managed-profile ids once so callers skip extra isManagedProfile probes.
        return out;
    }

    private static boolean isSlotOneReserved(Context c, SysApi.User u) {
        if (CLONE_PROFILE_NAME.equals(u.name)) return true;
        try {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            if (um != null && SysApi.isManagedProfile(um, u.id)) return true;
        } catch (Throwable ignore) { }
        return false;
    }

    private static int findEmptySecondarySlot(Context c, CloneStore db) {
        // A secondary slot with zero clones recorded can be reused.
        // ONE user enumeration total (was: safeGetUsers + allCloneUsers = 2x su).
        List<SysApi.User> ours = ourUsersSnapshot(c);
        List<Integer> used = new ArrayList<>();
        for (CloneStore.Clone cl : db.listAll()) used.add(cl.userId);
        for (SysApi.User u : ours) {
            if (isSlotOneReserved(c, u)) continue; // slot 1 reserved
            if (!used.contains(u.id)) return u.id;
        }
        // Cap total users to avoid filling /data
        if (ours.size() >= MAX_TOTAL_SLOTS) return -1;
        return -1;
    }

    /**
     * Freeze a clone: suspend (grey icon, hidden notifications, stopped
     * activities) + force-stop running processes. Data fully intact.
     * Unfreeze with unfreeze(). Root required (user-scoped suspend has no
     * public API; ROM builds can add SUSPEND_APPS later).
     */
    public static void freeze(Context c, String pkg, int userId) throws Exception {
        ShellEngine.suspend(pkg, userId);
        try { ShellEngine.forceStop(pkg, userId); }
        catch (Throwable t) { Log.w(TAG, "force-stop failed", t); }
    }

    public static void unfreeze(Context c, String pkg, int userId) throws Exception {
        ShellEngine.unsuspend(pkg, userId);
    }

    /**
     * Verified variants: return the TRUE post-op frozen state so UI can
     * distinguish our success from an external suspender (adb/another app)
     * holding the package frozen. Single dumpsys thanks to suspend cache.
     */
    public static boolean freezeAndVerify(Context c, String pkg, int userId) throws Exception {
        freeze(c, pkg, userId);
        ShellEngine.invalidateSuspended(pkg, userId);
        return ShellEngine.isSuspended(pkg, userId);
    }

    public static boolean unfreezeAndVerify(Context c, String pkg, int userId) throws Exception {
        unfreeze(c, pkg, userId);
        ShellEngine.invalidateSuspended(pkg, userId);
        return ShellEngine.isSuspended(pkg, userId);
    }

    /** Batch freeze for N clones: returns per-clone verified state. */
    public static java.util.Map<String, Boolean> freezeAll(Context c,
            java.util.List<CloneStore.Clone> clones) {
        java.util.Map<String, Boolean> out = new java.util.HashMap<>();
        if (clones == null) return out;
        for (CloneStore.Clone cl : clones) {
            if (cl == null) continue;
            try { out.put(cl.pkg + "#" + cl.userId, freezeAndVerify(c, cl.pkg, cl.userId)); }
            catch (Throwable t) { out.put(cl.pkg + "#" + cl.userId, null); }
        }
        return out;
    }

    /** Frozen state is per-user; only the root XML check is user-scoped. */
    public static boolean isFrozen(Context c, String pkg, int userId) {
        return ShellEngine.isSuspended(pkg, userId);
    }

    public static void deleteClone(Context c, CloneStore db, String pkg, int userId) {        // Remove its home shortcut(s) first so no dead icons remain.
        try { ShortcutHelper.unpin(c, pkg, userId); } catch (Throwable ignore) { }
        uninstallAsUser(c, pkg, userId);
        db.remove(pkg, userId);
        // Drop stale caches so lists/storage/freeze states refresh instantly.
        try { CloneUsage.invalidate(pkg, userId); } catch (Throwable ignore) { }
        try { ShellEngine.invalidateSuspended(pkg, userId); } catch (Throwable ignore) { }
        try { ShellEngine.invalidateResolve(pkg, userId); } catch (Throwable ignore) { }
        try { ShellEngine.invalidatePackages(userId); } catch (Throwable ignore) { }
        // If secondary user now empty, remove the whole user to free /data/user/<id>
        maybeRemoveEmptySecondaryUser(c, db, userId);
    }

    private static void maybeRemoveEmptySecondaryUser(Context c, CloneStore db, int userId) {
        if (userId == 0) return;
        try {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            if (SysApi.isManagedProfile(um, userId)) return; // keep managed profile for reuse
            SysApi.User ui = SysApi.getUserInfo(um, userId);
            if (ui != null && CLONE_PROFILE_NAME.equals(ui.name)) return; // keep clone profile
        } catch (Throwable ignore) {}
        boolean stillUsed = false;
        for (CloneStore.Clone cl : db.listAll()) if (cl.userId == userId) { stillUsed = true; break; }
        if (!stillUsed) {
            boolean removed = false;
            try {
                UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
                // removeUser(UserHandle) via public API where available
                try {
                    Method m = UserManager.class.getMethod("removeUser", UserHandle.class);
                    m.invoke(um, SysApi.userHandleOf(userId));
                } catch (NoSuchMethodException ns) {
                    Method m = UserManager.class.getMethod("removeUser", int.class);
                    m.invoke(um, userId);
                }
                removed = true;
            } catch (Throwable t) { Log.w(TAG, "removeUser reflect failed: " + t); }
            if (!removed) {
                // ROOT fallback: proven `pm remove-user` cmd.
                try {
                    ShellEngine.removeUser(userId);
                    removed = true;
                } catch (Throwable t) { Log.w(TAG, "ROOT removeUser failed " + userId, t); }
            }
            if (removed) {
                db.removeAllForUser(userId);
                Log.i(TAG, "removed empty secondary user " + userId);
            }
        }
    }

    /**
     * One-tap wake: a frozen clone (manual, external, or auto-freeze) is
     * transparently unfrozen before launch, so Home shortcuts and sheet taps
     * never land on the system "suspended" dialog. Best-effort, silent.
     */
    public static void wakeIfFrozen(Context c, String pkg, int userId) {
        try {
            if (ShellEngine.isSuspended(pkg, userId)) ShellEngine.unsuspend(pkg, userId);
        } catch (Throwable ignore) { }
    }

    /**
     * Screen-off sweep for auto-freeze: suspend + force-stop every tracked
     * clone that is still installed. One bulk dumpsys per package keeps su
     * pressure flat. Returns number frozen.
     */
    public static int freezeAllTracked(Context c, CloneStore db) {
        int n = 0;
        try {
            java.util.Map<String, List<CloneStore.Clone>> byPkg = new java.util.HashMap<>();
            for (CloneStore.Clone cl : db.listAll()) {
                List<CloneStore.Clone> l = byPkg.get(cl.pkg);
                if (l == null) { l = new ArrayList<>(); byPkg.put(cl.pkg, l); }
                l.add(cl);
            }
            for (java.util.Map.Entry<String, List<CloneStore.Clone>> e : byPkg.entrySet()) {
                List<Integer> ids = new ArrayList<>();
                for (CloneStore.Clone cl : e.getValue()) ids.add(cl.userId);
                java.util.Map<Integer, Boolean> state =
                        ShellEngine.isSuspendedBulk(e.getKey(), ids);
                for (CloneStore.Clone cl : e.getValue()) {
                    Boolean frozen = state.get(cl.userId);
                    if (frozen != null && frozen) continue; // already frozen
                    try {
                        freeze(c, cl.pkg, cl.userId);
                        n++;
                    } catch (Throwable ignore) { }
                }
            }
        } catch (Throwable t) { Log.w(TAG, "auto-freeze sweep failed", t); }
        return n;
    }

    // ---------- backup / restore (per-clone data archives) ----------

    /** Full data backup of one clone. Returns archive path. Off-main-thread. */
    public static String backupClone(Context c, String pkg, int userId) throws Exception {
        String path = ShellEngine.backupClone(pkg, userId);
        try {
            ShellEngine.enforceRetention(ShellEngine.BACKUP_DIR, pkg,
                Prefs.maxPerApp(c));
        } catch (Throwable ignore) { }
        try { CloneUsage.invalidate(pkg, userId); } catch (Throwable ignore) { }
        return path;
    }

    public static List<ShellEngine.Backup> listBackups(String pkg) {
        return ShellEngine.listBackups(pkg);
    }

    // ---------- backup center engine (parts, batch, specials) ----------

    /** Full backup honoring BackupJob parts into the chosen destination. */
    public static ShellEngine.FullResult backupFull(Context c, String pkg, int userId,
            BackupJob opts) throws Exception {
        // Silent auto-fix: a stopped profile has unmounted data dirs.
        try {
            if (userId > 0) ShellEngine.startUserIfNeeded(userId);
        } catch (Throwable ignore) { }
        String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
        String ver = "", vcode = "", label = "";
        try {
            android.content.pm.PackageInfo pi =
                c.getPackageManager().getPackageInfo(pkg, 0);
            if (pi.versionName != null) ver = pi.versionName;
            vcode = String.valueOf(pi.getLongVersionCode());
            CharSequence lb = c.getPackageManager().getApplicationLabel(pi.applicationInfo);
            if (lb != null) label = lb.toString();
        } catch (Throwable ignore) { }
        ShellEngine.FullResult r = ShellEngine.backupFull(
            pkg, userId, opts, dest, ver, vcode, label);
        try {
            ShellEngine.enforceRetention(dest, pkg, Prefs.maxPerApp(c));
        } catch (Throwable ignore) { }
        try { BackupJob.touchLastBackup(c); } catch (Throwable ignore) { }
        try { CloneUsage.invalidate(pkg, userId); } catch (Throwable ignore) { }
        EngineLog.i(c, "backup", pkg + " u" + userId
            + " -> " + (r != null && r.level == -2 ? "skipped(unchanged)"
            : String.valueOf(r != null ? r.archive : "?")));
        return r;
    }

    /**
     * Mode-aware backup: full, or incremental when a fresh-enough base full
     * exists (BackupJob.incr + base newer than incrDays). First backup of
     * anything is always a full. "unchanged" short-circuits as a clean skip.
     */
    public static ShellEngine.FullResult backupAuto(Context c, String pkg, int userId,
            BackupJob opts) throws Exception {
        if (opts != null && opts.incr && opts.data) {
            String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
            ShellEngine.Backup base = newestFull(c, dest, pkg, userId);
            if (base != null) {
                long ageMs = System.currentTimeMillis()
                    - ShellEngine.backupTimeOf(nameOf(base.path));
                long maxAge = (long) BackupJob.incrDays(c) * 24L * 60L * 60L * 1000L;
                if (ageMs < maxAge) {
                    try {
                        return backupIncr(c, pkg, userId, opts, dest, base);
                    } catch (Exception e) {
                        if ("unchanged".equals(e.getMessage())) {
                            ShellEngine.FullResult skip = new ShellEngine.FullResult();
                            skip.archive = null;
                            skip.comp = ShellEngine.detectComp().name;
                            skip.level = -2;
                            skip.parts.add(new ShellEngine.PartResult(
                                "data-incr", true, "unchanged"));
                            return skip;
                        }
                        throw e;
                    }
                }
            }
        }
        return backupFull(c, pkg, userId, opts);
    }

    private static String nameOf(String path) {
        int s = path.lastIndexOf('/');
        return s >= 0 ? path.substring(s + 1) : path;
    }

    /** Newest FULL (level 0) archive for a clone in a dest dir, or null. */
    static ShellEngine.Backup newestFull(Context c, String destDir,
            String pkg, int userId) {
        ShellEngine.Backup best = null;
        try {
            for (ShellEngine.Backup b : ShellEngine.listFullBackups(destDir, pkg)) {
                String n = nameOf(b.path);
                if (!n.startsWith(pkg + "_u" + userId + "_")) continue;
                if (ShellEngine.incrLevelOf(n) != 0) continue;
                if (best == null || b.path.compareTo(best.path) > 0) best = b;
            }
        } catch (Throwable ignore) { }
        return best;
    }

    private static ShellEngine.FullResult backupIncr(Context c, String pkg, int userId,
            BackupJob opts, String dest, ShellEngine.Backup base) throws Exception {
        String ver = "", vcode = "", label = "";
        try {
            android.content.pm.PackageInfo pi =
                c.getPackageManager().getPackageInfo(pkg, 0);
            if (pi.versionName != null) ver = pi.versionName;
            vcode = String.valueOf(pi.getLongVersionCode());
            CharSequence lb = c.getPackageManager().getApplicationLabel(pi.applicationInfo);
            if (lb != null) label = lb.toString();
        } catch (Throwable ignore) { }
        ShellEngine.FullResult r = ShellEngine.backupIncr(
            pkg, userId, opts, dest, ver, vcode, label);
        try {
            ShellEngine.enforceRetention(dest, pkg, Prefs.maxPerApp(c));
        } catch (Throwable ignore) { }
        try { BackupJob.touchLastBackup(c); } catch (Throwable ignore) { }
        try { CloneUsage.invalidate(pkg, userId); } catch (Throwable ignore) { }
        return r;
    }

    /** Encrypted mode-aware backup (full or incremental into the enc store). */
    public static ShellEngine.FullResult backupAutoEnc(Context c, String pkg, int userId,
            BackupJob opts, char[] pw) throws Exception {
        if (opts != null && opts.incr && opts.data) {
            java.io.File encDir = ShellEngine.encDir(c);
            ShellEngine.Backup base = newestFullEnc(c, pkg, userId);
            if (base != null) {
                long ageMs = System.currentTimeMillis()
                    - ShellEngine.backupTimeOf(nameOf(base.path));
                long maxAge = (long) BackupJob.incrDays(c) * 24L * 60L * 60L * 1000L;
                if (ageMs < maxAge) {
                    int level = 1;
                    try {
                        for (ShellEngine.Backup b : CloneEngine.listFullBackups(c, pkg)) {
                            String n = nameOf(b.path);
                            if (n.startsWith(pkg + "_u" + userId + "_")) {
                                level = Math.max(level, ShellEngine.incrLevelOf(n) + 1);
                            }
                        }
                    } catch (Throwable ignore) { }
                    String ver = "", vcode = "", label = "";
                    try {
                        android.content.pm.PackageInfo pi =
                            c.getPackageManager().getPackageInfo(pkg, 0);
                        if (pi.versionName != null) ver = pi.versionName;
                        vcode = String.valueOf(pi.getLongVersionCode());
                        CharSequence lb = c.getPackageManager()
                            .getApplicationLabel(pi.applicationInfo);
                        if (lb != null) label = lb.toString();
                    } catch (Throwable ignore) { }
                    String ext = ShellEngine.detectComp().ext;
                    java.io.File dest = new java.io.File(encDir,
                        pkg + "_u" + userId + "_" + encStamp() + ".incr" + level
                        + ext + ".enc");
                    try {
                        ShellEngine.FullResult r = ShellEngine.backupIncrEnc(
                            pkg, userId, opts, dest, pw, ver, vcode, label, level);
                        try {
                            ShellEngine.enforceRetention(encDir.getAbsolutePath(), pkg,
                                Prefs.maxPerApp(c));
                        } catch (Throwable ignore) { }
                        try { BackupJob.touchLastBackup(c); } catch (Throwable ignore) { }
                        return r;
                    } catch (Exception e) {
                        if ("unchanged".equals(e.getMessage())) {
                            ShellEngine.FullResult skip = new ShellEngine.FullResult();
                            skip.archive = null;
                            skip.comp = ShellEngine.detectComp().name;
                            skip.level = -2;
                            skip.parts.add(new ShellEngine.PartResult(
                                "data-incr", true, "unchanged"));
                            return skip;
                        }
                        throw e;
                    }
                }
            }
        }
        return backupFullEnc(c, pkg, userId, opts, pw);
    }

    private static ShellEngine.Backup newestFullEnc(Context c, String pkg, int userId) {
        ShellEngine.Backup best = null;
        try {
            for (ShellEngine.Backup b : CloneEngine.listFullBackups(c, pkg)) {
                if (!b.enc) continue;
                String n = nameOf(b.path);
                if (!n.startsWith(pkg + "_u" + userId + "_")) continue;
                if (ShellEngine.incrLevelOf(n) != 0) continue;
                if (best == null || b.path.compareTo(best.path) > 0) best = b;
            }
        } catch (Throwable ignore) { }
        return best;
    }

    private static String encStamp() {
        try {
            return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        } catch (Throwable t) { return String.valueOf(System.currentTimeMillis()); }
    }

    /**
     * Restore a full chain (base full with selected parts, then incrementals
     * data-only in order). Specials/legacy singles restore as one step.
     */
    public static ShellEngine.RestoreResult restoreChain(Context c, CloneStore db,
            String pkg, int userId, List<ShellEngine.Backup> chain, BackupJob sel)
            throws Exception {
        if (chain == null || chain.isEmpty()) throw new Exception("empty chain");
        ShellEngine.RestoreResult res = restoreFull(
            c, db, pkg, userId, chain.get(0).path, sel);
        if (sel != null && sel.data) {
            for (int i = 1; i < chain.size(); i++) {
                String ap = chain.get(i).path;
                try {
                    char[] pw = ap.endsWith(".enc")
                        ? CryptoVault.sessionPassword() : null;
                    if (ap.endsWith(".enc") && pw == null) {
                        res.failures.add(nameOf(ap) + ": password required");
                        continue;
                    }
                    ShellEngine.restoreIncrData(pkg, userId, ap, pw);
                    res.parts.add(new ShellEngine.PartResult(
                        "incr-" + i, true, nameOf(ap)));
                } catch (Throwable t) {
                    res.failures.add(nameOf(ap) + ": " + t.getMessage());
                }
            }
        }
        return res;
    }

    /** Chain for a clone across the current dest + enc store, base-first. */
    public static List<ShellEngine.Backup> chainForClone(Context c,
            String pkg, int userId) {
        List<ShellEngine.Backup> out = new ArrayList<>();
        try {
            String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
            out.addAll(ShellEngine.chainFor(dest, pkg, userId));
        } catch (Throwable ignore) { }
        try {
            // Encrypted chains live apart; merge by timestamp order.
            List<ShellEngine.Backup> encChain = new ArrayList<>();
            for (ShellEngine.Backup b : CloneEngine.listFullBackups(c, pkg)) {
                if (!b.enc) continue;
                String n = nameOf(b.path);
                if (n.startsWith(pkg + "_u" + userId + "_")) encChain.add(b);
            }
            if (!encChain.isEmpty()) {
                Collections.sort(encChain, (a, b) -> a.path.compareTo(b.path));
                // Keep the newest lineage only (enc full + its incrs).
                ShellEngine.Backup encBase = null;
                for (int i = encChain.size() - 1; i >= 0; i--) {
                    String n = nameOf(encChain.get(i).path);
                    if (ShellEngine.incrLevelOf(n) == 0) {
                        encBase = encChain.get(i);
                        break;
                    }
                }
                if (encBase != null) {
                    List<ShellEngine.Backup> one = new ArrayList<>();
                    one.add(encBase);
                    for (ShellEngine.Backup b : encChain) {
                        String n = nameOf(b.path);
                        if (ShellEngine.incrLevelOf(n) > 0
                                && b.path.compareTo(encBase.path) > 0) {
                            one.add(b);
                        }
                    }
                    // Prefer whichever lineage is newer overall.
                    if (!out.isEmpty()) {
                        String plainTip = out.get(out.size() - 1).path;
                        String encTip = one.get(one.size() - 1).path;
                        long pt = ShellEngine.backupTimeOf(nameOf(plainTip));
                        long et = ShellEngine.backupTimeOf(nameOf(encTip));
                        if (et > pt) {
                            out.clear();
                            out.addAll(one);
                        }
                    } else {
                        out.addAll(one);
                    }
                }
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /** System-wide special backup with retention. Returns archive path. */
    public static String backupSpecial(Context c, String kind) throws Exception {
        String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
        String path = ShellEngine.backupSpecial(kind, dest);
        try {
            ShellEngine.enforceRetention(dest, "special_" + kind,
                Prefs.maxPerApp(c));
        } catch (Throwable ignore) { }
        return path;
    }

    /** Encrypted full backup into the app-private encrypted store. */
    public static ShellEngine.FullResult backupFullEnc(Context c, String pkg, int userId,
            BackupJob opts, char[] pw) throws Exception {
        try {
            if (userId > 0) ShellEngine.startUserIfNeeded(userId);
        } catch (Throwable ignore) { }
        String ver = "", vcode = "", label = "";
        try {
            android.content.pm.PackageInfo pi =
                c.getPackageManager().getPackageInfo(pkg, 0);
            if (pi.versionName != null) ver = pi.versionName;
            vcode = String.valueOf(pi.getLongVersionCode());
            CharSequence lb = c.getPackageManager().getApplicationLabel(pi.applicationInfo);
            if (lb != null) label = lb.toString();
        } catch (Throwable ignore) { }
        ShellEngine.Comp comp = ShellEngine.detectComp();
        java.io.File dest = new java.io.File(ShellEngine.encDir(c),
            pkg + "_u" + userId + "_" + backupStamp() + comp.ext + ".enc");
        ShellEngine.FullResult r = ShellEngine.backupFullEnc(
            pkg, userId, opts, dest, pw, ver, vcode, label);
        try {
            ShellEngine.enforceRetention(dest.getAbsolutePath(), pkg,
                Prefs.maxPerApp(c));
        } catch (Throwable ignore) { }
        try { BackupJob.touchLastBackup(c); } catch (Throwable ignore) { }
        try { CloneUsage.invalidate(pkg, userId); } catch (Throwable ignore) { }
        return r;
    }

    private static String backupStamp() {
        try {
            return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        } catch (Throwable t) { return String.valueOf(System.currentTimeMillis()); }
    }

    /** Restore selected parts from a full archive into a tracked clone. */
    public static ShellEngine.RestoreResult restoreFull(Context c, CloneStore db,
            String pkg, int userId, String archive, BackupJob sel) throws Exception {
        boolean tracked = false;
        for (CloneStore.Clone cl : db.listForPkg(pkg)) {
            if (cl.userId == userId) { tracked = true; break; }
        }
        // Owner base (u0) is restorable without a clone record.
        if (!tracked && userId != 0) throw new Exception("clone not tracked");
        if (!installExistingAsUser(c, pkg, userId)) throw new Exception("not installed");
        ShellEngine.RestoreResult r;
        if (archive != null && archive.endsWith(".enc")) {
            char[] pw = CryptoVault.sessionPassword();
            if (pw == null) throw new Exception("password required");
            r = ShellEngine.restoreFullEnc(pkg, userId, archive, sel, pw);
        } else {
            r = ShellEngine.restoreFull(pkg, userId, archive, sel);
        }
        try { CloneUsage.invalidate(pkg, userId); } catch (Throwable ignore) { }
        EngineLog.i(c, "restore", pkg + " u" + userId
            + " failures=" + (r != null ? r.failures.size() : -1));
        return r;
    }

    public static List<ShellEngine.Backup> listFullBackups(Context c, String pkgOrNull) {
        List<ShellEngine.Backup> out = new ArrayList<>();
        try {
            String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
            out.addAll(ShellEngine.listFullBackups(dest, pkgOrNull));
        } catch (Throwable t) {
            Log.w(TAG, "listFullBackups failed", t);
        }
        // Encrypted store is app-readable: merge without su.
        try {
            java.io.File[] files = ShellEngine.encDir(c).listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String n = f.getName();
                    if (!n.endsWith(".tar.gz.enc") && !n.endsWith(".tar.xz.enc")
                            && !n.endsWith(".tar.zst.enc")) continue;
                    if (pkgOrNull != null && !pkgOrNull.startsWith("special")
                            && !n.startsWith(pkgOrNull + "_u")) continue;
                    if (pkgOrNull != null && pkgOrNull.startsWith("special")
                            && !n.startsWith(pkgOrNull)) continue;
                    out.add(new ShellEngine.Backup(f.getAbsolutePath(), f.length()));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "enc list failed", t);
        }
        Collections.sort(out, (a, b) -> b.path.compareTo(a.path));
        return out;
    }

    /** Newest full archive for pkg+user, or null. */
    public static ShellEngine.Backup newestBackup(Context c, String pkg, int userId) {
        for (ShellEngine.Backup b : listFullBackups(c, pkg)) {
            String n = b.path.substring(b.path.lastIndexOf('/') + 1);
            if (n.startsWith(pkg + "_u" + userId + "_")) return b;
        }
        // Legacy single engine archives live in the internal dir too.
        for (ShellEngine.Backup b : ShellEngine.listBackups(pkg)) {
            String n = b.path.substring(b.path.lastIndexOf('/') + 1);
            if (n.startsWith(pkg + "_u" + userId + "_")) return b;
        }
        return null;
    }

    public static List<ShellEngine.Volume> backupVolumes() {
        return ShellEngine.volumes();
    }

    /**
     * Restore archive into an EXISTING tracked clone (record must exist:
     * restores never resurrect deleted copies). Off-main-thread.
     */
    public static void restoreClone(Context c, CloneStore db,
            String pkg, int userId, String path) throws Exception {
        boolean tracked = false;
        for (CloneStore.Clone cl : db.listForPkg(pkg)) {
            if (cl.userId == userId) { tracked = true; break; }
        }
        if (!tracked) throw new Exception("clone not tracked");
        if (!installExistingAsUser(c, pkg, userId)) throw new Exception("not installed");
        ShellEngine.restoreClone(pkg, userId, path);
        try { CloneUsage.invalidate(pkg, userId); } catch (Throwable ignore) { }
    }

    public static boolean launchClone(Context c, String pkg, int userId) {
        // One-tap wake first: frozen copies open directly, no system dialog.
        wakeIfFrozen(c, pkg, userId);
        // ROOT/fast path first: start (skipped if started <30s ago), WAIT only
        // when we actually started, resolve (cached), `am start`.
        try {
            boolean started = false;
            try { started = ShellEngine.startUserIfNeeded(userId); }
            catch (Throwable t) { startUserInBackground(userId); started = true; }
            if (started) ShellEngine.waitForUserRunning(userId, 8000);
            String comp = ShellEngine.resolveLauncher(pkg, userId);
            if (comp == null) {
                // One retry: profiles sometimes need a moment after unlock.
                // Bypass the 60s resolve cache so the retry really re-queries.
                ShellEngine.invalidateResolve(pkg, userId);
                try { Thread.sleep(1200); } catch (Throwable ignore) { }
                comp = ShellEngine.resolveLauncher(pkg, userId);
            }
            if (comp != null) {
                ShellEngine.startActivityAsUser(comp, userId);
                return true;
            }
        } catch (Throwable t) { Log.w(TAG, "ROOT launch failed, trying LauncherApps", t); }
        try {
            startUserInBackground(userId);
            LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            UserHandle uh = SysApi.userHandleOf(userId);
            for (android.content.pm.LauncherActivityInfo ai : la.getActivityList(pkg, uh)) {
                Bundle opts = null;
                la.startMainActivity(ai.getComponentName(), uh, null, opts);
                return true;
            }
            // Fallback: generic MAIN
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                SysApi.startActivityAsUser(c, i, userId);
                return true;
            }
        } catch (Throwable t) { Log.e(TAG, "launch failed " + pkg + " u" + userId, t); }
        return false;
    }

    // ---------- filtering (only supported apps are listed) ----------

    /**
     * Optional system-app visibility (Settings switch, default OFF).
     * Only apps WITH a launcher entry qualify; a hard denylist of core
     * packages (systemui/phone/shell/…) always stays hidden, and
     * FLAG_SINGLE_USER apps can never run as clones.
     */
    public static boolean isSystemVisible(Context c, ApplicationInfo ai) {
        try {
            if (ai == null || !Prefs.showSystem(c)) return false;
            String pkg = ai.packageName;
            if (pkg.equals("android")
                    || pkg.startsWith("com.android.systemui")
                    || pkg.equals("com.android.shell")
                    || pkg.equals("com.android.phone")
                    || pkg.equals(c.getPackageName())) return false;
            final int FLAG_SINGLE_USER = 1 << 30;
            if ((ai.flags & FLAG_SINGLE_USER) != 0) return false;
            Intent main = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            return !c.getPackageManager().queryIntentActivities(main, 0).isEmpty();
        } catch (Throwable t) { return false; }
    }

    public static boolean isCloneable(Context c, ApplicationInfo ai) {
        if (ai == null) return false;
        String pkg = ai.packageName;
        if (pkg.equals(c.getPackageName())) return false;
        if (pkg.equals("android") || pkg.startsWith("com.android.systemui")) return false;
        // FLAG_SINGLE_USER apps cannot run as clones
        final int FLAG_SINGLE_USER = 1 << 30;
        if ((ai.flags & FLAG_SINGLE_USER) != 0) return false;
        // Skip persistent system internals, allow updated system apps
        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
            if (!pkg.startsWith("com.google.android.apps.messaging")
                    && !pkg.startsWith("com.facebook")
                    && !pkg.startsWith("com.whatsapp")
                    && !pkg.startsWith("org.telegram")) return false;
        }
        // Must have launcher entry for user 0
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            if (c.getPackageManager().queryIntentActivities(main, 0).isEmpty()) return false;
        } catch (Throwable t) { return false; }
        return true;
    }
}
