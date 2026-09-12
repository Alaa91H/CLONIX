package com.clonepilot.app;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.Log;
import java.io.IOException;
import java.util.UUID;

/**
 * Storage per clone via StorageStatsManager (public API).
 * Same APK is shared, so appBytes is ~same for all clones and counted once
 * on disk — we show it for clarity but total-extra = data+cache only.
 * Requires PACKAGE_USAGE_STATS (privileged auto-grant, or user grant via Settings).
 */
public class CloneStorageHelper {
    private static final String TAG = "ClonePilot";

    public static class Usage {
        public long appBytes;   // code (shared APK, not extra)
        public long dataBytes;  // /data/user/<id>/pkg without cache
        public long cacheBytes;
        public long totalBytes; // app+data+cache as reported
        public long extraBytes; // real extra cost of this clone = data+cache
        public boolean available;
    }

    public static UUID storageUuid(Context c) {
        try {
            c.getSystemService(Context.STORAGE_SERVICE);
            return StorageManager.UUID_DEFAULT;
        } catch (Throwable t) {
            return StorageManager.UUID_DEFAULT;
        }
    }

    public static Usage queryPackage(Context c, String pkg, int userId) {
        Usage u = new Usage();
        try {
            StorageStatsManager ssm = (StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
            UUID uuid = storageUuid(c);
            UserHandle uh;
            try {
                uh = SysApi.userHandleOf(userId);
            } catch (Throwable t) {
                u.available = false;
                return u;
            }
            StorageStats st = ssm.queryStatsForPackage(uuid, pkg, uh);
            u.appBytes = st.getAppBytes();
            u.dataBytes = st.getDataBytes() - st.getCacheBytes();
            if (u.dataBytes < 0) u.dataBytes = 0;
            u.cacheBytes = st.getCacheBytes();
            u.totalBytes = st.getAppBytes() + st.getDataBytes();
            u.extraBytes = st.getDataBytes(); // data+cache = real extra per clone
            u.available = true;
        } catch (PackageManager.NameNotFoundException nnf) {
            Log.w(TAG, "not installed " + pkg + " u" + userId);
            u.available = false;
        } catch (IOException ioe) {
            Log.w(TAG, "query failed " + pkg + " u" + userId + ": " + ioe.getMessage());
            u.available = false;
        } catch (SecurityException se) {
            Log.e(TAG, "usage access denied, open Settings>Apps>Special>Usage access", se);
            u.available = false;
        } catch (Throwable t) {
            Log.e(TAG, "query failed", t);
            u.available = false;
        }
        return u;
    }

    /** Best-effort clear cache for one clone. Falls back to App-Info screen. */
    public static boolean clearCacheAsUser(Context c, String pkg, int userId) {
        PackageManager pm = c.getPackageManager();
        // Hidden API: deleteApplicationCacheFilesAsUser(String, int, IPackageDataObserver)
        try {
            for (java.lang.reflect.Method m : PackageManager.class.getMethods()) {
                if (m.getName().equals("deleteApplicationCacheFilesAsUser")) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[1] == int.class) {
                        m.invoke(pm, pkg, userId, null);
                        return true;
                    }
                }
            }
        } catch (Throwable t) { Log.w(TAG, "clearCache reflect failed", t); }
        return false;
    }

    /**
     * Clear all data of one clone (factory-reset that clone only).
     * Clone stays installed, data in /data/user/<id>/pkg is wiped.
     * Tries hidden cross-user APIs, system uid only.
     */
    public static boolean clearDataAsUser(Context c, String pkg, int userId) {
        // 1) IActivityManager.clearApplicationUserData(String, IPackageDataObserver, int userId)
        try {
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            java.lang.reflect.Method getService = amClass.getMethod("getService");
            Object iam = getService.invoke(null);
            for (java.lang.reflect.Method m : iam.getClass().getMethods()) {
                if (m.getName().equals("clearApplicationUserData")) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0] == String.class && p[2] == int.class) {
                        m.invoke(iam, pkg, null, userId);
                        return true;
                    }
                }
            }
        } catch (Throwable t) { Log.w(TAG, "AM.clearData reflect failed", t); }
        // 2) PackageManager.clearApplicationUserData(String, IPackageDataObserver, int userId)
        try {
            PackageManager pm = c.getPackageManager();
            for (java.lang.reflect.Method m : PackageManager.class.getMethods()) {
                if (m.getName().equals("clearApplicationUserData")) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0] == String.class && p[2] == int.class) {
                        m.invoke(pm, pkg, null, userId);
                        return true;
                    }
                }
            }
        } catch (Throwable t) { Log.w(TAG, "PM.clearData reflect failed", t); }
        return false;
    }

    public static String formatSize(Context c, long bytes) {
        return android.text.format.Formatter.formatFileSize(c, bytes);
    }
}
