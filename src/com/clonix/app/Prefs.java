package com.clonix.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Global switches (toolbar Settings screen). Defaults preserve current behavior. */
public final class Prefs {
    private static final String FILE = "settings";

    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Auto-request home shortcut right after a clone (system confirm tap). */
    public static boolean autoPin(Context c) {
        try { return p(c).getBoolean("auto_pin", true); }
        catch (Throwable t) { return true; }
    }

    public static void setAutoPin(Context c, boolean b) {
        try { p(c).edit().putBoolean("auto_pin", b).apply(); }
        catch (Throwable ignore) { }
    }

    /** Periodic keep-alive for background clone users (notifications). */
    public static boolean keepAlive(Context c) {
        try { return p(c).getBoolean("keepalive", true); }
        catch (Throwable t) { return true; }
    }

    public static void setKeepAlive(Context c, boolean b) {
        try { p(c).edit().putBoolean("keepalive", b).apply(); }
        catch (Throwable ignore) { }
    }

    /**
     * Auto-freeze: suspend + force-stop ALL tracked clones on screen-off.
     * Opening any copy transparently unfreezes it (one-tap wake).
     * Default OFF: freezing stops notifications until the copy is opened.
     */
    public static boolean autoFreeze(Context c) {
        try { return p(c).getBoolean("auto_freeze", false); }
        catch (Throwable t) { return false; }
    }

    public static void setAutoFreeze(Context c, boolean b) {
        try { p(c).edit().putBoolean("auto_freeze", b).apply(); }
        catch (Throwable ignore) { }
    }

    /**
     * Show system apps (with launcher entries) in the clone list.
     * Default OFF: system apps often misbehave as clones (shared UIDs,
     * single-user flags). A hard denylist (systemui/phone/shell/…) always stays
     * hidden regardless of this switch.
     */
    public static boolean showSystem(Context c) {
        try { return p(c).getBoolean("show_system", false); }
        catch (Throwable t) { return false; }
    }

    public static void setShowSystem(Context c, boolean b) {
        try { p(c).edit().putBoolean("show_system", b).apply(); }
        catch (Throwable ignore) { }
    }

    /** Swipe-to-delete + long-press menus in archive lists. */
    public static boolean swipeActions(Context c) {
        try { return p(c).getBoolean("swipe_actions", true); }
        catch (Throwable t) { return true; }
    }

    public static void setSwipeActions(Context c, boolean b) {
        try { p(c).edit().putBoolean("swipe_actions", b).apply(); }
        catch (Throwable ignore) { }
    }

    /**
     * Max kept archives per app (newest N). 0 = unlimited. Enforced right
     * after every backup (Neo-style retention).
     */
    public static int maxPerApp(Context c) {
        try { return Math.max(0, p(c).getInt("max_per_app", 5)); }
        catch (Throwable t) { return 5; }
    }

    public static void setMaxPerApp(Context c, int n) {
        try { p(c).edit().putInt("max_per_app", Math.max(0, n)).apply(); }
        catch (Throwable ignore) { }
    }

    /** Result sounds (success vs failure tones). Default ON. */
    public static boolean sounds(Context c) {
        try { return p(c).getBoolean("sounds", true); }
        catch (Throwable t) { return true; }
    }

    public static void setSounds(Context c, boolean b) {
        try { p(c).edit().putBoolean("sounds", b).apply(); }
        catch (Throwable ignore) { }
    }

    /** First-run value intro (Backup center). Shown once, dismissable. */
    public static boolean firstRun(Context c) {
        try { return p(c).getBoolean("first_run", true); }
        catch (Throwable t) { return true; }
    }

    public static void clearFirstRun(Context c) {
        try { p(c).edit().putBoolean("first_run", false).apply(); }
        catch (Throwable ignore) { }
    }

    /** Theme: system | light | dark | amoled. Default follows system. */
    public static String themeMode(Context c) {
        try {
            String m = p(c).getString("theme_mode", "system");
            if ("light".equals(m) || "dark".equals(m) || "amoled".equals(m)) {
                return m;
            }
        } catch (Throwable ignore) { }
        return "system";
    }

    public static void setThemeMode(Context c, String m) {
        try { p(c).edit().putString("theme_mode", m).apply(); }
        catch (Throwable ignore) { }
    }

    /** Material You dynamic color (Android 12+), default on. */
    public static boolean dynamicColor(Context c) {
        try { return p(c).getBoolean("dynamic_color", true); }
        catch (Throwable t) { return true; }
    }

    public static void setDynamicColor(Context c, boolean b) {
        try { p(c).edit().putBoolean("dynamic_color", b).apply(); }
        catch (Throwable ignore) { }
    }

    /** Auto-backup a tracked app right after it is installed/updated. */
    public static boolean autoBackupOnInstall(Context c) {
        try { return p(c).getBoolean("auto_backup_install", false); }
        catch (Throwable t) { return false; }
    }

    public static void setAutoBackupOnInstall(Context c, boolean b) {
        try { p(c).edit().putBoolean("auto_backup_install", b).apply(); }
        catch (Throwable ignore) { }
    }

    /** Package waiting for its auto-backup run (receiver -> job handoff). */
    public static String pendingAutoPkg(Context c) {
        try { return p(c).getString("pending_auto_pkg", null); }
        catch (Throwable t) { return null; }
    }

    public static void setPendingAutoPkg(Context c, String pkg) {
        try { p(c).edit().putString("pending_auto_pkg", pkg).apply(); }
        catch (Throwable ignore) { }
    }

    public static void clearPendingAutoPkg(Context c) {
        try { p(c).edit().remove("pending_auto_pkg").apply(); }
        catch (Throwable ignore) { }
    }
}
