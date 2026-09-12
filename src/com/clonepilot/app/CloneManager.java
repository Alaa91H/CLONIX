package com.clonepilot.app;

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
public class CloneManager {
    private static final String TAG = "ClonePilot";
    public static final String PROFILE_NAME = "CloneWork"; // legacy managed fallback name
    public static final String CLONE_PROFILE_NAME = "CloneSpace"; // slot 1, no work tab
    public static final String USER_TYPE_CLONE = "android.os.usertype.profile.CLONE";
    public static final String SECONDARY_PREFIX = "Clone";
    /** Local copy: DevicePolicyManager.SKIP_SETUP_WIZARD is hidden from public SDK (=1). */
    private static final int SKIP_SETUP_WIZARD = 0x0001;
    public static final int MAX_CLONES_PER_APP = 8;
    public static final int MAX_TOTAL_SLOTS = 8;

    public static ComponentName admin(Context c) {
        return new ComponentName(c, CloneAdminReceiver.class);
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

    // ---------- high-level: N clones ----------

    /**
     * Clone pkg into next free slot.
     * @return userId used, or -1 on failure (e.g. max reached).
     */
    public static int cloneToNextSlot(Context c, CloneDatabase db, String pkg,
                                      String nickname, boolean separateContacts) {
        List<CloneDatabase.Clone> existing = db.listForPkg(pkg);
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

        CloneDatabase.Clone cl = new CloneDatabase.Clone();
        cl.pkg = pkg; cl.userId = userId; cl.slotIndex = slotIndex;
        cl.nickname = nickname; cl.separateContacts = separateContacts;
        db.add(cl);
        return userId;
    }

    private static int findEmptySecondarySlot(Context c, CloneDatabase db) {
        // A secondary slot with zero clones recorded can be reused
        List<Integer> used = new ArrayList<>();
        for (CloneDatabase.Clone cl : db.listAll()) used.add(cl.userId);
        for (SysApi.User u : SysApi.safeGetUsers(c)) {
            int id = u.id;
            if (id == 0) continue;
            if (!isOursName(u.name)) continue;
            try {
                UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
                if (SysApi.isManagedProfile(um, id)) continue; // slot 1 (managed fallback) reserved
                if (CLONE_PROFILE_NAME.equals(u.name)) continue; // slot 1 reserved
            } catch (Throwable ignore) {}
            if (!used.contains(id)) return id;
        }
        // Cap total users to avoid filling /data
        if (allCloneUsers(c).size() >= MAX_TOTAL_SLOTS) return -1;
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

    /** Frozen state is per-user; only the root XML check is user-scoped. */
    public static boolean isFrozen(Context c, String pkg, int userId) {
        return ShellEngine.isSuspended(pkg, userId);
    }

    public static void deleteClone(Context c, CloneDatabase db, String pkg, int userId) {        // Remove its home shortcut(s) first so no dead icons remain.
        try { CloneShortcuts.unpin(c, pkg, userId); } catch (Throwable ignore) { }
        uninstallAsUser(c, pkg, userId);
        db.remove(pkg, userId);
        // If secondary user now empty, remove the whole user to free /data/user/<id>
        maybeRemoveEmptySecondaryUser(c, db, userId);
    }

    private static void maybeRemoveEmptySecondaryUser(Context c, CloneDatabase db, int userId) {
        if (userId == 0) return;
        try {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            if (SysApi.isManagedProfile(um, userId)) return; // keep managed profile for reuse
            SysApi.User ui = SysApi.getUserInfo(um, userId);
            if (ui != null && CLONE_PROFILE_NAME.equals(ui.name)) return; // keep clone profile
        } catch (Throwable ignore) {}
        boolean stillUsed = false;
        for (CloneDatabase.Clone cl : db.listAll()) if (cl.userId == userId) { stillUsed = true; break; }
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

    public static boolean launchClone(Context c, String pkg, int userId) {
        // ROOT/fast path first: start, WAIT for unlock, resolve, `am start`.
        try {
            startUserInBackground(userId);
            ShellEngine.waitForUserRunning(userId, 10000);
            String comp = ShellEngine.resolveLauncher(pkg, userId);
            if (comp == null) {
                // One retry: profiles sometimes need a moment after unlock.
                try { Thread.sleep(1500); } catch (Throwable ignore) { }
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
