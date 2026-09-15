package com.clonix.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dual-mode facade over privileged operations.
 *  - DIRECT: hidden Java APIs via reflection (ROM/userdebug builds).
 *  - ROOT: proven `pm`/`am`/`cmd` shell commands via su (user builds).
 * Mode is picked by ShellEngine; every method falls back automatically.
 */
public final class SysApi {
    private static final String TAG = "Clonix";

    /** Local copy of hidden PackageManager.INSTALL_SUCCEEDED (=1). */
    public static final int INSTALL_SUCCEEDED = 1;

    /** Snapshot of a user: works in both modes (handle via Parcel trick). */
    public static final class User {
        public final int id;
        public final String name;
        public final UserHandle handle;
        User(int id, String name, UserHandle handle) {
            this.id = id; this.name = name; this.handle = handle;
        }
    }

    private SysApi() {}

    // ---------- UserHandle without hidden APIs ----------

    /**
     * Build a UserHandle for any id. Tries hidden of()/ctor first (ROM),
     * then the public Parcelable.Creator path (works on locked user builds:
     * CREATOR.createFromParcel is public; the hidden ctor runs as framework).
     */
    public static UserHandle userHandleOf(int userId) throws Exception {
        try {
            Method m = UserHandle.class.getMethod("of", int.class);
            return (UserHandle) m.invoke(null, userId);
        } catch (Throwable ignore) { }
        try {
            Constructor<UserHandle> c = UserHandle.class.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            return c.newInstance(userId);
        } catch (Throwable ignore) { }
        Parcel p = Parcel.obtain();
        try {
            p.writeInt(userId);
            p.setDataPosition(0);
            Parcelable.Creator<UserHandle> creator = UserHandle.CREATOR;
            return creator.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    public static int getIdentifier(UserHandle uh) {
        try {
            Method m = UserHandle.class.getMethod("getIdentifier");
            Object r = m.invoke(uh);
            return (Integer) r;
        } catch (Throwable t) {
            throw new RuntimeException("getIdentifier reflect failed", t);
        }
    }

    // ---------- users ----------

    private static User wrapReflect(Object uinfo) throws Exception {
        Class<?> cls = uinfo.getClass();
        int id = cls.getField("id").getInt(uinfo);
        String name = (String) cls.getField("name").get(uinfo);
        UserHandle handle;
        try {
            Method m = cls.getMethod("getUserHandle");
            handle = (UserHandle) m.invoke(uinfo);
        } catch (Throwable t) {
            handle = userHandleOf(id);
        }
        return new User(id, name, handle);
    }

    public static List<User> getUsers(UserManager um) throws Exception {
        // DIRECT first.
        try {
            Method m = UserManager.class.getMethod("getUsers");
            Object raw = m.invoke(um);
            List<User> out = new ArrayList<>();
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    try { out.add(wrapReflect(o)); } catch (Throwable ignore) { }
                }
            }
            return out;
        } catch (Throwable directFail) {
            // ROOT fallback: parse `pm list users`.
            List<ShellEngine.ShellUser> shell = ShellEngine.listUsers();
            List<User> out = new ArrayList<>();
            for (ShellEngine.ShellUser s : shell) {
                try { out.add(new User(s.id, s.name, userHandleOf(s.id))); }
                catch (Throwable ignore) { }
            }
            if (out.isEmpty()) throw new Exception("no users via shell", directFail);
            return out;
        }
    }

    public static User getUserInfo(UserManager um, int userId) throws Exception {
        try {
            Method m = UserManager.class.getMethod("getUserInfo", int.class);
            Object r = m.invoke(um, userId);
            if (r == null) throw new Exception("no such user " + userId);
            return wrapReflect(r);
        } catch (NoSuchMethodException ns) {
            Method m = UserManager.class.getMethod("getUserInfo", UserHandle.class);
            Object r = m.invoke(um, userHandleOf(userId));
            if (r == null) throw new Exception("no such user " + userId);
            return wrapReflect(r);
        } catch (Exception e) {
            // ROOT fallback: find in shell list.
            for (User u : getUsers(um)) if (u.id == userId) return u;
            throw e;
        }
    }

    public static boolean isManagedProfile(UserManager um, int userId) {
        try {
            Method m = UserManager.class.getMethod("isManagedProfile", int.class);
            Object r = m.invoke(um, userId);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false; // callers additionally name-match; safe default
        }
    }

    // ---------- DevicePolicyManager ----------

    public static int createAndManageUser(DevicePolicyManager dpm, ComponentName admin,
                                          String name, ComponentName profileOwner,
                                          PersistableBundle extras, int flags) throws Exception {
        Method m = DevicePolicyManager.class.getMethod("createAndManageUser",
                ComponentName.class, String.class, ComponentName.class,
                PersistableBundle.class, int.class);
        Object r = m.invoke(dpm, admin, name, profileOwner, extras, flags);
        return (Integer) r;
    }

    // ---------- PackageManager ----------

    /** Returns INSTALL_SUCCEEDED on success, else throws. */
    public static int installExistingPackageAsUser(PackageManager pm, String pkg, int userId)
            throws Exception {
        try {
            Exception last = null;
            for (Method m : PackageManager.class.getMethods()) {
                if (!m.getName().equals("installExistingPackageAsUser")) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    Object r = null;
                    if (p.length == 2 && p[0] == String.class && p[1] == int.class) {
                        r = m.invoke(pm, pkg, userId);
                    } else if (p.length == 4) {
                        r = m.invoke(pm, pkg, userId, 0, 0);
                    } else {
                        continue;
                    }
                    if (r instanceof Integer) {
                        int code = (Integer) r;
                        if (code == INSTALL_SUCCEEDED) return code;
                        last = new Exception("installExisting=" + code);
                    } else if (r instanceof Boolean && (Boolean) r) {
                        return INSTALL_SUCCEEDED;
                    }
                } catch (Exception e) { last = e; }
            }
            if (last != null) throw last;
            throw new NoSuchMethodException("installExistingPackageAsUser");
        } catch (Throwable directFail) {
            ShellEngine.installExisting(pkg, userId); // ROOT fallback (proven cmd)
            return INSTALL_SUCCEEDED;
        }
    }

    /**
     * @return details on DIRECT path; null when install only verified via
     * shell (ROOT path has no PackageInfo object to return).
     * @throws PackageManager.NameNotFoundException when not installed.
     */
    public static PackageInfo getPackageInfoAsUser(PackageManager pm, String pkg, int userId)
            throws Exception {
        try {
            for (Method m : PackageManager.class.getMethods()) {
                if (!m.getName().equals("getPackageInfoAsUser")) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 3 && p[0] == String.class && p[2] == int.class) {
                        if (p[1] == int.class) return (PackageInfo) m.invoke(pm, pkg, 0, userId);
                        Object flags = p[1].getMethod("of", long.class).invoke(null, 0L);
                        return (PackageInfo) m.invoke(pm, pkg, flags, userId);
                    }
                } catch (Exception e) {
                    if (e.getCause() instanceof PackageManager.NameNotFoundException) throw (Exception) e.getCause();
                }
            }
            throw new NoSuchMethodException("getPackageInfoAsUser");
        } catch (Throwable directFail) {
            if (ShellEngine.isInstalled(pkg, userId)) return null;
            throw new PackageManager.NameNotFoundException(pkg);
        }
    }

    // ---------- Context ----------

    /**
     * Cross-user start. DIRECT hidden API first; ROOT fallback runs the
     * proven `am start --user` command (needs explicit component).
     */
    public static void startActivityAsUser(Context c, Intent i, int userId) throws Exception {
        try {
            Method m = Context.class.getMethod("startActivityAsUser",
                    Intent.class, UserHandle.class);
            m.invoke(c, i, userHandleOf(userId));
            return;
        } catch (Throwable directFail) {
            if (i.getComponent() == null) throw new Exception("need explicit component", directFail);
            ShellEngine.startActivityAsUser(i.getComponent().flattenToShortString(), userId);
        }
    }

    public static List<User> safeGetUsers(Context c) {
        try {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            return getUsers(um);
        } catch (Throwable t) {
            Log.e(TAG, "getUsers failed", t);
            return Collections.emptyList();
        }
    }

    /**
     * Launchable package names installed for userId.
     * DIRECT: public LauncherApps API (ROM builds with cross-user access).
     * ROOT fallback: `pm list packages --user` filtered to launcher apps.
     */
    public static List<String> getLaunchablePackages(Context c, int userId) {
        // DIRECT first (no su needed on capable builds).
        try {
            android.content.pm.LauncherApps la =
                (android.content.pm.LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            UserHandle uh = userHandleOf(userId);
            List<String> out = new ArrayList<>();
            for (android.content.pm.LauncherActivityInfo ai : la.getActivityList(null, uh)) {
                String pkg = ai.getApplicationInfo().packageName;
                if (!out.contains(pkg)) out.add(pkg);
            }
            return out;
        } catch (Throwable t) {
            Log.i(TAG, "LauncherApps per-user failed, ROOT fallback", t);
        }
        // ROOT fallback: all packages, caller filters via isCloneable().
        try {
            return ShellEngine.listPackages(userId);
        } catch (Throwable t) {
            Log.w(TAG, "listPackages failed u" + userId, t);
            return Collections.emptyList();
        }
    }

    /** Field read helper for hidden UserInfo-shaped objects (ROM paths). */
    static int idOf(Object uinfo) throws Exception {
        Field f = uinfo.getClass().getField("id");
        return f.getInt(uinfo);
    }
}
