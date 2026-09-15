package com.clonix.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Backup "parts" model (pro parts model): every archive records which parts it
 * holds, and every restore lets the user pick parts. Defaults persist in
 * app-private prefs; per-run dialogs edit a throwaway copy.
 *
 * Parts: data (+cache optionally), private-data scope, runtime permissions,
 * appops modes, SSAID (needs reboot), and the system-wide specials
 * (SMS / call-log / Wi-Fi) as separate archives.
 */
public final class BackupJob implements Cloneable {
    public boolean data = true;
    public boolean cache = false;
    /** false = skip shared_prefs + databases on restore (media/files kept). */
    public boolean privateData = true;
    public boolean perms = true;
    public boolean appops = true;
    /** SSAID backup/restore edits settings_ssaid.xml; system applies on reboot. */
    public boolean ssaid = false;
    /** Encrypt NEW backups with the session password (AES-256-GCM). */
    public boolean enc = false;
    /**
     * Incremental mode (default ON): after a full base exists, back up only
     * files changed since (rsync quick-check semantics: size+mtime).
     * A fresh full is forced when no base exists or the base is older
     * than incrDays. First backup of anything is always a full.
     */
    public boolean incr = true;

    private BackupJob() {}

    /** Blank selection (all false) for restore-part dialogs. */
    public static BackupJob empty() { return new BackupJob(); }

    public static BackupJob defaults(Context c) {
        BackupJob j = new BackupJob();
        try {
            SharedPreferences p = opts(c);
            j.data = p.getBoolean("o_data", true);
            j.cache = p.getBoolean("o_cache", false);
            j.privateData = p.getBoolean("o_priv", true);
            j.perms = p.getBoolean("o_perms", true);
            j.appops = p.getBoolean("o_appops", true);
            j.ssaid = p.getBoolean("o_ssaid", false);
            j.enc = p.getBoolean("o_enc", false);
            j.incr = p.getBoolean("o_incr", true);
        } catch (Throwable ignore) { }
        return j;
    }

    public void saveAsDefaults(Context c) {
        try {
            opts(c).edit()
                .putBoolean("o_data", data)
                .putBoolean("o_cache", cache)
                .putBoolean("o_priv", privateData)
                .putBoolean("o_perms", perms)
                .putBoolean("o_appops", appops)
                .putBoolean("o_ssaid", ssaid)
                .putBoolean("o_enc", enc)
                .putBoolean("o_incr", incr)
                .apply();
        } catch (Throwable ignore) { }
    }

    private static SharedPreferences opts(Context c) {
        return c.getSharedPreferences("backup_opts", Context.MODE_PRIVATE);
    }

    /**
     * Last-known counts for instant bottom-bar badges (zero I/O):
     * refreshed by list screens on every reload.
     */
    public static int lastClones(Context c) {
        try { return opts(c).getInt("n_clones", 0); }
        catch (Throwable t) { return 0; }
    }

    public static int lastArchives(Context c) {
        try { return opts(c).getInt("n_arch", 0); }
        catch (Throwable t) { return 0; }
    }

    public static void setLastCounts(Context c, int clones, int arch) {
        try {
            opts(c).edit().putInt("n_clones", Math.max(0, clones))
                .putInt("n_arch", Math.max(0, arch)).apply();
        } catch (Throwable ignore) { }
    }

    /** Last successful backup completion (any item), millis. 0 = never. */
    public static long lastBackupTime(Context c) {
        try { return opts(c).getLong("last_backup", 0); }
        catch (Throwable t) { return 0; }
    }

    public static void touchLastBackup(Context c) {
        try { opts(c).edit().putLong("last_backup", System.currentTimeMillis())
            .apply(); } catch (Throwable ignore) { }
    }

    /** Last schedule run summary for status cards. */
    public static String lastSchedule(Context c) {
        try {
            String s = opts(c).getString("last_sched", null);
            return s != null ? s : "";
        } catch (Throwable t) { return ""; }
    }

    public static void setLastSchedule(Context c, String s) {
        try { opts(c).edit().putString("last_sched", s).apply(); }
        catch (Throwable ignore) { }
    }

    @Override public BackupJob clone() {
        try { return (BackupJob) super.clone(); }
        catch (Throwable t) {
            BackupJob j = new BackupJob();
            j.data = data; j.cache = cache; j.privateData = privateData;
            j.perms = perms; j.appops = appops; j.ssaid = ssaid; j.enc = enc;
            j.incr = incr;
            return j;
        }
    }

    /**
     * Compressor override: "auto" (strongest verified: zstd>xz>gzip),
     * "gzip" (max PC-tool compatibility), or "zstd" (only if verified,
     * else falls back to auto with a note). A user-supplied working zstd
     * anywhere in root PATH is picked up automatically.
     */
    public static String compPref(android.content.Context c) {
        try {
            String v = opts(c).getString("comp", "auto");
            if ("gzip".equals(v) || "zstd".equals(v)) return v;
        } catch (Throwable ignore) { }
        return "auto";
    }

    public static void setCompPref(android.content.Context c, String v) {
        String norm = "gzip".equals(v) || "zstd".equals(v) ? v : "auto";
        try {
            opts(c).edit().putString("comp", norm).apply();
        } catch (Throwable ignore) { }
        try { ShellEngine.setCompOverride(norm); } catch (Throwable ignore) { }
    }

    public static void syncCompOverride(android.content.Context c) {
        try { ShellEngine.setCompOverride(compPref(c)); }
        catch (Throwable ignore) { }
    }

    /** Full-cycle days for auto-incremental (base older than this = new full). */
    public static int incrDays(android.content.Context c) {
        try {
            return Math.max(1, opts(c).getInt("incr_days", 7));
        } catch (Throwable t) { return 7; }
    }

    public static void setIncrDays(android.content.Context c, int d) {
        try { opts(c).edit().putInt("incr_days", Math.max(1, d)).apply(); }
        catch (Throwable ignore) { }
    }

    // ---------------- destination ----------------

    public static final String DEST_INTERNAL = "internal";

    /** "internal" or a /storage/UUID path. Validated at use time. */
    public static String dest(Context c) {
        try {
            String d = c.getSharedPreferences("backup_opts", Context.MODE_PRIVATE)
                .getString("dest", DEST_INTERNAL);
            return d != null ? d : DEST_INTERNAL;
        } catch (Throwable t) { return DEST_INTERNAL; }
    }

    public static void setDest(Context c, String d) {
        try {
            opts(c).edit().putString("dest",
                d != null ? d : DEST_INTERNAL).apply();
        } catch (Throwable ignore) { }
    }

    // ---------------- blacklist (skip in batch) ----------------

    public static Set<String> blacklist(Context c) {
        try {
            return new HashSet<>(opts(c).getStringSet("blacklist",
                Collections.<String>emptySet()));
        } catch (Throwable t) { return new HashSet<String>(); }
    }

    public static boolean isBlacklisted(Context c, String pkg) {
        return pkg != null && blacklist(c).contains(pkg);
    }

    public static void setBlacklisted(Context c, String pkg, boolean black) {
        if (pkg == null) return;
        try {
            Set<String> s = blacklist(c);
            if (black) s.add(pkg); else s.remove(pkg);
            opts(c).edit().putStringSet("blacklist", s).apply();
        } catch (Throwable ignore) { }
    }

    // ---------------- categories (labels) ----------------

    private static Map<String, String> catMap(Context c) {
        Map<String, String> out = new HashMap<>();
        try {
            String raw = opts(c).getString("cats", "");
            if (raw == null) return out;
            for (String e : raw.split(";")) {
                int eq = e.indexOf('=');
                if (eq > 0) out.put(e.substring(0, eq), e.substring(eq + 1));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    public static String category(Context c, String pkg) {
        if (pkg == null) return "";
        String v = catMap(c).get(pkg);
        return v != null ? v : "";
    }

    public static void setCategory(Context c, String pkg, String label) {
        if (pkg == null) return;
        try {
            Map<String, String> m = catMap(c);
            if (label == null || label.trim().isEmpty()) m.remove(pkg);
            else m.put(pkg, label.trim());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            opts(c).edit().putString("cats", sb.toString()).apply();
        } catch (Throwable ignore) { }
    }

    /** Distinct labels in use, newest usage order undefined — sorted. */
    public static List<String> allCategories(Context c) {
        Set<String> s = new TreeSet<>();
        for (String v : catMap(c).values()) if (!v.isEmpty()) s.add(v);
        return new ArrayList<>(s);
    }
}
