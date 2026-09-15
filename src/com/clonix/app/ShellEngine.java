package com.clonix.app;

import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Privilege engine with three tiers (auto-detected, per-call fallback):
 *  1) DIRECT: hidden Java APIs via reflection. Works on ROM/userdebug builds
 *     where this app is platform-signed or hidden-API is permissive.
 *  2) ROOT: the same `pm`/`am`/`cmd` shell commands proven on-device, run via
 *     `su -c` (KernelSU/Magisk grant). Works on locked user builds where
 *     hidden-API reflection is enforced.
 *  3) NONE: show the setup dialog (grant root / use ROM build).
 *
 * No extra dependencies, no platform signature required.
 * Shell parsing uses only outputs verified on-device during development.
 */
public final class ShellEngine {
    private static final String TAG = "Clonix";

    public enum Mode { DIRECT, ROOT, NONE }

    private static volatile Mode cached = null;

    private ShellEngine() {}

    /** Probe once per process; per-call code falls back on Throwable anyway. */
    public static synchronized Mode mode(Context c) {
        if (cached != null) return cached;
        // DIRECT probe: harmless read-only reflection call.
        try {
            Class<?> umc = Class.forName("android.os.UserManager");
            Object um = c.getSystemService(Context.USER_SERVICE);
            umc.getMethod("getUsers").invoke(um);
            cached = Mode.DIRECT;
            Log.i(TAG, "engine=DIRECT");
            return cached;
        } catch (Throwable t) {
            Log.i(TAG, "DIRECT unavailable: " + t);
        }
        // ROOT probe: su -c id must print uid=0 (root grant persists).
        try {
            ExecResult r = execRoot(new String[]{"id"}, 8000);
            if (r.ok && r.out.contains("uid=0")) {
                cached = Mode.ROOT;
                Log.i(TAG, "engine=ROOT");
                return cached;
            }
        } catch (Throwable t) {
            Log.i(TAG, "ROOT unavailable: " + t);
        }
        cached = Mode.NONE;
        Log.i(TAG, "engine=NONE");
        return cached;
    }

    public static void invalidate() { cached = null; }

    // ---------------- raw exec ----------------

    public static final class ExecResult {
        public final boolean ok; // exit 0
        public final String out; // stdout+stderr merged
        public ExecResult(boolean ok, String out) { this.ok = ok; this.out = out; }
    }

    /** Cap shell output to avoid OOM on huge dumpsys outputs. */
    private static final int MAX_OUT_BYTES = 256 * 1024;

    private static ExecResult run(String[] cmd, long timeoutMs) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        boolean done = false;
        try {
            done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            try { p.destroyForcibly(); } catch (Throwable ignore) { }
            Thread.currentThread().interrupt();
            return new ExecResult(false, "interrupted");
        }
        String out = readAllCapped(p.getInputStream(), done ? -1 : 0);
        if (!done) {
            try { p.destroyForcibly(); } catch (Throwable ignore) { }
            // Drain briefly after kill so we still return the error tail.
            try { out = readAllCapped(p.getInputStream(), 300); } catch (Throwable ignore) { }
        }
        int exit = -1;
        try { exit = done ? p.exitValue() : -1; } catch (Throwable ignore) { }
        try { p.getInputStream().close(); } catch (Throwable ignore) { }
        try { p.getOutputStream().close(); } catch (Throwable ignore) { }
        return new ExecResult(done && exit == 0, out);
    }

    private static String readAll(InputStream in) {
        return readAllCapped(in, -1);
    }

    private static String readAllCapped(InputStream in, long extraWaitMs) {
        try {
            if (extraWaitMs > 0) { try { Thread.sleep(extraWaitMs); } catch (Throwable ignore) { } }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            // available() guard prevents indefinite block after timeout-kill.
            while (total < MAX_OUT_BYTES && (n = in.read(buf, 0,
                    Math.min(buf.length, MAX_OUT_BYTES - total))) > 0) {
                b.write(buf, 0, n);
                total += n;
                if (in.available() == 0) break;
            }
            return b.toString("UTF-8");
        } catch (Throwable t) { return ""; }
        finally { try { in.close(); } catch (Throwable ignore) { } }
    }

    /** Run a shell command as root: su -c "<joined>". */
    public static ExecResult execRoot(String[] shellCmd, long timeoutMs) throws Exception {
        return run(new String[]{"su", "-c", joinShell(shellCmd)}, timeoutMs);
    }

    private static String joinShell(String[] shellCmd) {
        StringBuilder sb = new StringBuilder();
        for (String s : shellCmd) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('\'').append(s.replace("'", "'\\''")).append('\'');
        }
        return sb.toString();
    }

    /**
     * Global mount namespace (`su -mm`, KernelSU/KSU-Next): the app's own
     * namespace can LACK per-user mounts created later (e.g. /data/user/24
     * for CloneSpace), so all direct filesystem access (backup/restore/rm/ls)
     * runs here. Binder calls (pm/am/cmd/dumpsys) are namespace-independent
     * and stay on plain su.
     * Portability: `-mm` is KSU-specific. On Magisk/APatch the flag fails,
     * the probe below returns false, and everything transparently falls back
     * to plain su (correct wherever plain su already sees all users).
     * Probed once: -mm showing MORE /data/user entries than plain su.
     */
    private static volatile Boolean globalNs = null;

    public static boolean hasGlobalNs() {
        Boolean hit = globalNs;
        if (hit != null) return hit;
        boolean v = false;
        try {
            ExecResult plain = run(new String[]{"su", "-c", "'ls' '/data/user'"}, 10000);
            ExecResult mm = run(new String[]{"su", "-mm", "-c", "'ls' '/data/user'"}, 10000);
            int pc = plain.ok ? countLines(plain.out) : -1;
            v = mm.ok && countLines(mm.out) > pc;
        } catch (Throwable ignore) { v = false; }
        globalNs = v;
        Log.i(TAG, "globalNs=" + v);
        return v;
    }

    private static int countLines(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        return s.trim().split("\n").length;
    }

    /** execRoot, but in the GLOBAL mount namespace when available. */
    public static ExecResult execRootGlobal(String[] shellCmd, long timeoutMs) throws Exception {
        if (hasGlobalNs()) {
            try {
                // NOTE: pass sh scripts UNWRAPPED (`su -mm -c '<script>'`).
                // The nested form (`su -mm -c 'sh' '-c' '<script>'`) silently
                // drops -mm on this KSU build and runs blind (verified on-device).
                if (shellCmd.length == 3 && "sh".equals(shellCmd[0])
                        && "-c".equals(shellCmd[1])) {
                    return run(new String[]{"su", "-mm", "-c", shellCmd[2]}, timeoutMs);
                }
                return run(new String[]{"su", "-mm", "-c", joinShell(shellCmd)}, timeoutMs);
            } catch (Throwable t) {
                Log.w(TAG, "-mm failed, plain fallback", t);
            }
        }
        return execRoot(shellCmd, timeoutMs);
    }

    private static ExecResult suGlobal(String... shellCmd) throws Exception {
        return execRootGlobal(shellCmd, 30000);
    }

    public static ExecResult su(String... shellCmd) throws Exception {
        return execRoot(shellCmd, 30000);
    }

    // ---------------- pm/am/cmd wrappers (all outputs verified on-device) ----------------

    private static final Pattern USER_LINE =
            Pattern.compile("UserInfo\\{(\\d+):([^:]*):([0-9a-fA-F]+)\\}");

    public static final class ShellUser {
        public final int id;
        public final String name;
        ShellUser(int id, String name) { this.id = id; this.name = name; }
    }

    /** pm list users, cached 8s to avoid spawning su on every tap. */
    private static volatile List<ShellUser> usersCache = null;
    private static volatile long usersCacheAt = 0;
    private static final long USERS_TTL_MS = 8000;

    /** pm list users -> [(id,name)]. Throws on failure. */
    public static List<ShellUser> listUsers() throws Exception {
        List<ShellUser> hit = usersCache;
        if (hit != null && System.currentTimeMillis() - usersCacheAt < USERS_TTL_MS) {
            return new ArrayList<>(hit);
        }
        ExecResult r = su("pm", "list", "users");
        if (!r.ok) throw new Exception("pm list users failed: " + r.out);
        List<ShellUser> out = new ArrayList<>();
        Matcher m = USER_LINE.matcher(r.out);
        while (m.find()) out.add(new ShellUser(Integer.parseInt(m.group(1)), m.group(2)));
        usersCache = out;
        usersCacheAt = System.currentTimeMillis();
        return new ArrayList<>(out);
    }

    /** Drop the users cache after create/remove operations. */
    public static void dropUsersCache() {
        usersCache = null;
        usersCacheAt = 0;
    }

    private static final Pattern CREATED_ID = Pattern.compile("created user id (\\d+)");

    private static int parseCreatedId(String out) throws Exception {
        Matcher m = CREATED_ID.matcher(out);
        if (m.find()) return Integer.parseInt(m.group(1));
        throw new Exception("no user id in: " + out);
    }

    public static int createUser(String name) throws Exception {
        ExecResult r = su("pm", "create-user", name);
        if (!r.ok) throw new Exception(r.out);
        dropUsersCache();
        return parseCreatedId(r.out);
    }

    public static int createCloneProfile(String name) throws Exception {
        ExecResult r = su("pm", "create-user", "--user-type",
                "android.os.usertype.profile.CLONE", "--profileOf", "0", name);
        if (!r.ok) throw new Exception(r.out);
        dropUsersCache();
        return parseCreatedId(r.out);
    }

    public static int createManagedProfile(String name) throws Exception {
        ExecResult r = su("pm", "create-user", "--profileOf", "0", "--managed", name);
        if (!r.ok) throw new Exception(r.out);
        dropUsersCache();
        return parseCreatedId(r.out);
    }

    public static void removeUser(int userId) throws Exception {
        ExecResult r = su("pm", "remove-user", String.valueOf(userId));
        dropUsersCache();
        pkgsCache.remove(userId);
        userStartedAt.remove(userId);
        if (!r.ok && !r.out.contains("removed user")) throw new Exception(r.out);
    }

    public static void installExisting(String pkg, int userId) throws Exception {
        ExecResult r = su("pm", "install-existing", "--user", String.valueOf(userId), pkg);
        if (!r.ok || !r.out.contains("installed for user")) throw new Exception(r.out);
        invalidatePackages(userId);
        invalidateResolve(pkg, userId);
        invalidateSuspended(pkg, userId);
    }

    public static void uninstall(String pkg, int userId) throws Exception {
        ExecResult r = su("pm", "uninstall", "--user", String.valueOf(userId), pkg);
        if (!r.ok) throw new Exception(r.out);
        invalidatePackages(userId);
        invalidateResolve(pkg, userId);
        invalidateSuspended(pkg, userId);
    }

    public static boolean isInstalled(String pkg, int userId) {
        try {
            ExecResult r = su("pm", "path", "--user", String.valueOf(userId), pkg);
            return r.ok && r.out.contains("package:");
        } catch (Throwable t) { return false; }
    }

    public static void startUser(int userId) throws Exception {
        // NOTE: plain form; some builds reject `am start-user -f`.
        ExecResult r = su("am", "start-user", String.valueOf(userId));
        if (!r.ok) throw new Exception(r.out);
        userStartedAt.put(userId, System.currentTimeMillis());
    }

    // ---------------- lightweight TTL caches (cut su pressure) ----------------

    private static final class CacheEntry<T> {
        final T value;
        final long at;
        CacheEntry(T v, long t) { value = v; at = t; }
    }

    private static final long PKGS_TTL_MS = 15000;
    private static final long RESOLVE_TTL_MS = 60000;
    private static final long SUSPEND_TTL_MS = 5000;
    private static final long ROOT_TTL_MS = 30000;

    private static final java.util.concurrent.ConcurrentHashMap<Integer, CacheEntry<List<String>>> pkgsCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, CacheEntry<String>> resolveCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, CacheEntry<Boolean>> suspendCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile CacheEntry<Boolean> rootCache = null;
    /** Last successful `am start-user` per user: skip re-start within 30s. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> userStartedAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean fresh(long at, long ttl) {
        return System.currentTimeMillis() - at < ttl;
    }

    public static void invalidatePackages(int userId) { pkgsCache.remove(userId); }
    public static void invalidateAllPackages() { pkgsCache.clear(); }
    public static void invalidateResolve(String pkg, int userId) {
        if (pkg == null) return;
        resolveCache.remove(pkg + "#" + userId);
    }
    public static void invalidateSuspended(String pkg, int userId) {
        if (pkg == null) return;
        suspendCache.remove("S#" + pkg + "#" + userId);
        suspendCache.remove("A#" + pkg + "#" + userId);
    }

    /** Resolve MAIN/LAUNCHER component for pkg in user; null if none. Cached 60s. */
    public static String resolveLauncher(String pkg, int userId) {
        if (pkg == null || userId < 0) return null;
        String key = pkg + "#" + userId;
        CacheEntry<String> hit = resolveCache.get(key);
        if (hit != null && fresh(hit.at, RESOLVE_TTL_MS)) return hit.value;
        String found = null;
        try {
            ExecResult r = su("cmd", "package", "resolve-activity",
                    "--user", String.valueOf(userId), "--brief",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER", pkg);
            if (r.ok) {
                for (String line : r.out.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("priority=")
                            || line.startsWith("No activity")) continue;
                    if (line.contains("/")) { found = line; break; } // pkg/.Activity
                }
            }
        } catch (Throwable t) { found = null; }
        // Cache only positive hits + hard negatives from an OK shell call;
        // transient failures stay uncached so retry is immediate.
        if (found != null) resolveCache.put(key, new CacheEntry<>(found, System.currentTimeMillis()));
        return found;
    }

    public static void startActivityAsUser(String component, int userId) throws Exception {
        // FLAG_ACTIVITY_NEW_TASK like launcher taps: clone gets its own task,
        // never mixed with the manager app's task.
        ExecResult r = su("am", "start", "--user", String.valueOf(userId),
                "-f", "0x10000000", "-n", component);
        // NOTE: `am start` prints "Starting:" even on its way to an error line,
        // so success requires the absence of "Error".
        if ((!r.ok && !r.out.contains("Starting:")) || r.out.contains("Error")) {
            throw new Exception(r.out);
        }
    }

    private static final Pattern RUNNING_LINE =
            Pattern.compile("UserInfo\\{(\\d+):[^}]*\\}\\s+running");

    /**
     * Wait until `pm list users` shows the user as running (profile unlocked).
     * Backoff polling (750ms -> 1500ms) to cut su pressure vs fixed 500ms.
     */
    public static boolean waitForUserRunning(int userId, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        long sleep = 750;
        while (System.currentTimeMillis() < end) {
            if (Thread.currentThread().isInterrupted()) return false;
            try {
                ExecResult r = su("pm", "list", "users");
                if (r.ok) {
                    Matcher m = RUNNING_LINE.matcher(r.out);
                    while (m.find()) {
                        if (Integer.parseInt(m.group(1)) == userId) return true;
                    }
                }
            } catch (Throwable ignore) { }
            try { Thread.sleep(sleep); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Throwable ignore) { }
            if (sleep < 1500) sleep = 1500; // backoff after first poll
        }
        return false;
    }

    /**
     * Skip redundant `am start-user` when we started this user < 30s ago.
     * Returns true if a start was actually issued.
     */
    public static boolean startUserIfNeeded(int userId) throws Exception {
        Long last = userStartedAt.get(userId);
        if (last != null && System.currentTimeMillis() - last < 30000) return false;
        startUser(userId);
        userStartedAt.put(userId, System.currentTimeMillis());
        return true;
    }

    /** Split "pkg/.Cls" or "pkg/pkg.Cls" into [pkg, class]. */
    public static String[] splitComponent(String flattened) {
        int slash = flattened.indexOf('/');
        String pkg = flattened.substring(0, slash);
        String cls = flattened.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        return new String[]{pkg, cls};
    }

    /**
     * Clear ALL data of pkg in user (official `pm clear --user`, shell-safe).
     * Only the target user's data is touched.
     */
    public static void clearApp(String pkg, int userId) throws Exception {
        ExecResult r = su("pm", "clear", "--user", String.valueOf(userId), pkg);
        if (!r.ok || !(r.out.contains("Success") || r.out.contains("cleared"))) {
            throw new Exception(r.out);
        }
    }

    private static final java.util.regex.Pattern SAFE_PKG =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    /**
     * Clear cache (+code_cache) of pkg in user via root rm.
     * STRICT path guard: only /data/user/<id>/<pkg>/{cache,code_cache}/*,
     * anything else refuses. Non-destructive (temp files regenerate).
     */
    public static void rmCache(String pkg, int userId) throws Exception {
        if (userId < 0 || !SAFE_PKG.matcher(pkg).matches()) {
            throw new SecurityException("refused unsafe path: " + pkg + "/" + userId);
        }
        String base = "/data/user/" + userId + "/" + pkg;
        ExecResult r = suGlobal("sh", "-c",
            "rm -rf '" + base + "/cache/'* '" + base + "/code_cache/'* && echo CLEARED");
        if (!r.ok || !r.out.contains("CLEARED")) throw new Exception(r.out);
    }

    /** Resolve App-Details Settings component for pkg in user (may be null). Cached 60s. */
    public static String resolveAppDetails(String pkg, int userId) {
        if (pkg == null || userId < 0) return null;
        String key = "A#" + pkg + "#" + userId;
        CacheEntry<String> hit = resolveCache.get(key);
        if (hit != null && fresh(hit.at, RESOLVE_TTL_MS)) return hit.value;
        String found = null;
        try {
            ExecResult r = su("cmd", "package", "resolve-activity",
                    "--user", String.valueOf(userId), "--brief",
                    "-a", "android.settings.APPLICATION_DETAILS_SETTINGS",
                    "-d", "package:" + pkg);
            if (r.ok) {
                for (String line : r.out.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("priority=")
                            || line.startsWith("No activity")) continue;
                    if (line.contains("/")) { found = line; break; }
                }
            }
        } catch (Throwable t) { found = null; }
        if (found != null) resolveCache.put(key, new CacheEntry<>(found, System.currentTimeMillis()));
        return found;
    }

    /** Best-effort: is `su` grant present (cached 30s, short probe)? */
    public static boolean hasRoot() {
        CacheEntry<Boolean> hit = rootCache;
        if (hit != null && fresh(hit.at, ROOT_TTL_MS)) return hit.value;
        boolean v = false;
        try {
            ExecResult r = execRoot(new String[]{"id"}, 5000);
            v = r.ok && r.out.contains("uid=0");
        } catch (Throwable t) { v = false; }
        rootCache = new CacheEntry<>(v, System.currentTimeMillis());
        return v;
    }

    // ---------------- freeze (suspend) ----------------
    // Suspended clones: grey icon, hidden notifications, stopped activities,
    // data fully intact. Same primitive Shelter/Hail use. Root/shell only.

    /** Suspend pkg in user (freeze). Verified output: "new suspended state: true". */
    public static void suspend(String pkg, int userId) throws Exception {
        ExecResult r = su("pm", "suspend", "--user", String.valueOf(userId), pkg);
        if (!r.ok || !r.out.contains("true")) throw new Exception(r.out);
        suspendCache.put("S#" + pkg + "#" + userId,
                new CacheEntry<>(true, System.currentTimeMillis()));
    }

    public static void unsuspend(String pkg, int userId) throws Exception {
        ExecResult r = su("pm", "unsuspend", "--user", String.valueOf(userId), pkg);
        if (!r.ok) throw new Exception(r.out);
        // Unsuspend can be vetoed by an external suspender: re-read truth.
        // Optimistically mark false, but verify via dumpsys when asked next time
        // only if this call claimed success AND output confirms false state.
        boolean confirmedFalse = r.out.contains("false");
        if (confirmedFalse) {
            suspendCache.put("S#" + pkg + "#" + userId,
                    new CacheEntry<>(false, System.currentTimeMillis()));
        } else {
            suspendCache.remove("S#" + pkg + "#" + userId);
        }
    }

    public static void forceStop(String pkg, int userId) throws Exception {
        ExecResult r = su("am", "force-stop", "--user", String.valueOf(userId), pkg);
        if (!r.ok) throw new Exception(r.out);
    }

    /**
     * Suspended state per user, read from `dumpsys package` ("User <id>: ...
     * suspended=true"). Cached 5s: dumpsys output is large and spawning su
     * per row-scroll is the #1 jank source. Mutations invalidate the entry.
     */
    public static boolean isSuspended(String pkg, int userId) {
        try {
            if (userId < 0 || !SAFE_PKG.matcher(pkg).matches()) return false;
            String key = "S#" + pkg + "#" + userId;
            CacheEntry<Boolean> hit = suspendCache.get(key);
            if (hit != null && fresh(hit.at, SUSPEND_TTL_MS)) return hit.value;
            ExecResult r = su("dumpsys", "package", pkg);
            boolean v = false;
            if (r.ok) {
                boolean inSection = false;
                for (String rawLine : r.out.split("\n")) {
                    String line = rawLine.trim();
                    if (line.startsWith("User ")) {
                        inSection = line.startsWith("User " + userId + ":")
                            || line.startsWith("User " + userId + " ");
                        if (inSection && line.contains("suspended=true")) { v = true; break; }
                        continue;
                    }
                    if (inSection) {
                        if (line.contains("suspended=true")) { v = true; break; }
                        if (line.startsWith("User ") || line.startsWith("Queries:")
                                || line.startsWith("Dexopt state:")) {
                            inSection = false;
                        }
                    }
                }
            }
            // Cache only when dumpsys succeeded; failures stay uncached for fast retry.
            if (r.ok) suspendCache.put(key, new CacheEntry<>(v, System.currentTimeMillis()));
            return v;
        } catch (Throwable t) { return false; }
    }

    /**
     * Bulk suspended check with ONE dumpsys per package for N users.
     * Used by manage-dialog/storage lists instead of N separate dumpsys calls.
     */
    public static java.util.Map<Integer, Boolean> isSuspendedBulk(String pkg,
            java.util.Collection<Integer> userIds) {
        java.util.Map<Integer, Boolean> out = new java.util.HashMap<>();
        if (pkg == null || userIds == null || userIds.isEmpty()) return out;
        List<Integer> missing = new ArrayList<>();
        for (int uid : userIds) {
            CacheEntry<Boolean> hit = suspendCache.get("S#" + pkg + "#" + uid);
            if (hit != null && fresh(hit.at, SUSPEND_TTL_MS)) out.put(uid, hit.value);
            else missing.add(uid);
        }
        if (missing.isEmpty() || !SAFE_PKG.matcher(pkg).matches()) return out;
        try {
            ExecResult r = su("dumpsys", "package", pkg);
            if (!r.ok) return out;
            long now = System.currentTimeMillis();
            java.util.Map<Integer, Boolean> parsed = new java.util.HashMap<>();
            for (int uid : missing) parsed.put(uid, false);
            int curUser = -1;
            for (String rawLine : r.out.split("\n")) {
                String line = rawLine.trim();
                if (line.startsWith("User ")) {
                    curUser = -1;
                    for (int uid : missing) {
                        if (line.startsWith("User " + uid + ":") || line.startsWith("User " + uid + " ")) {
                            curUser = uid;
                            if (line.contains("suspended=true")) parsed.put(uid, true);
                            break;
                        }
                    }
                    continue;
                }
                if (curUser >= 0 && line.contains("suspended=true")) parsed.put(curUser, true);
                if (line.startsWith("Queries:") || line.startsWith("Dexopt state:")) curUser = -1;
            }
            for (java.util.Map.Entry<Integer, Boolean> e : parsed.entrySet()) {
                out.put(e.getKey(), e.getValue());
                suspendCache.put("S#" + pkg + "#" + e.getKey(),
                        new CacheEntry<>(e.getValue(), now));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /** All package names installed for user. Cached 15s to cut su pressure. */
    public static List<String> listPackages(int userId) throws Exception {
        CacheEntry<List<String>> hit = pkgsCache.get(userId);
        if (hit != null && fresh(hit.at, PKGS_TTL_MS)) return new ArrayList<>(hit.value);
        ExecResult r = su("pm", "list", "packages", "--user", String.valueOf(userId));
        if (!r.ok) throw new Exception(r.out);
        List<String> out = new ArrayList<>();
        for (String line : r.out.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) {
                String pkg = line.substring("package:".length()).trim();
                if (!pkg.isEmpty()) out.add(pkg);
            }
        }
        pkgsCache.put(userId, new CacheEntry<>(new ArrayList<>(out), System.currentTimeMillis()));
        return out;
    }

    /**
     * Bulk variant: one cached `pm list packages` per user for M users.
     * Failures yield empty lists (callers treat as no orphans, never crash).
     */
    public static java.util.Map<Integer, List<String>> listPackagesBulk(
            java.util.Collection<Integer> userIds) {
        java.util.Map<Integer, List<String>> out = new java.util.HashMap<>();
        if (userIds == null) return out;
        for (int uid : userIds) {
            try { out.put(uid, listPackages(uid)); }
            catch (Throwable t) { out.put(uid, new ArrayList<String>()); }
        }
        return out;
    }

    // ---------------- backup / restore (root tar.gz, private dir) ----------------
    // Private root-only dir: no storage permissions, no internet, nothing
    // leaves the device. One archive per clone holds BOTH credential-encrypted
    // (/data/user) and device-encrypted (/data/user_de) data when present.

    /** Primary archive store (root-owned, survives uninstalls of clones). */
    public static final String BACKUP_DIR = "/data/misc/clonix/backups";
    /** Pre-rebrand location; migrated once to BACKUP_DIR at startup. */
    public static final String LEGACY_BACKUP_DIR = "/data/misc/clonepilot/backups";
    private static final long BACKUP_TIMEOUT_MS = 10 * 60 * 1000L;

    public static final class Backup {
        public final String path;
        public final String name;
        public final long size;
        public final boolean enc;
        Backup(String p, long s) {
            this(p, s, p != null && p.endsWith(".enc"));
        }
        Backup(String p, long s, boolean e) {
            path = p; size = s; enc = e;
            int slash = p.lastIndexOf('/');
            name = slash >= 0 ? p.substring(slash + 1) : p;
        }
    }

    private static void guardBackupArgs(String pkg, int userId) throws Exception {
        if (userId < 0 || pkg == null || !SAFE_PKG.matcher(pkg).matches()) {
            throw new SecurityException("refused unsafe backup path: " + pkg + "/" + userId);
        }
    }

    private static void guardBackupPath(String path) throws Exception {
        // Encrypted archives append .enc after the compression extension.
        String core = (path != null && path.endsWith(".enc"))
            ? path.substring(0, path.length() - 4) : path;
        if (path == null || path.contains("..")
                || path.contains("'") || path.contains("\"") || path.contains(" ")
                || !(core.endsWith(".tar.gz") || core.endsWith(".tar.xz")
                    || core.endsWith(".tar.zst"))) {
            throw new SecurityException("refused unsafe backup file: " + path);
        }
        boolean internal = path.startsWith(BACKUP_DIR + "/");
        boolean external = path.startsWith("/storage/") && path.contains("/CLONIX/");
        // App-private encrypted store (app-writable, USB-visible via MTP).
        boolean encStore = path.startsWith("/storage/")
            && path.contains("/Android/data/com.clonix.app/files/backups/");
        if (!internal && !external && !encStore) {
            throw new SecurityException("refused backup dir: " + path);
        }
    }

    private static final String[] ARCH_EXTS = {".tar.zst", ".tar.xz", ".tar.gz"};

    private static String backupStamp() {
        try {
            return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date());
        } catch (Throwable t) { return String.valueOf(System.currentTimeMillis()); }
    }

    private static final java.util.regex.Pattern STAMP_PAT =
            java.util.regex.Pattern.compile("(\\d{8})_(\\d{6})");

    /**
     * Archive timestamp parsed from the file NAME (no I/O): 0 when absent.
     * Powers "outdated" badges + pretty dates without extra su calls.
     */
    public static long backupTimeOf(String name) {
        try {
            java.util.regex.Matcher m = STAMP_PAT.matcher(
                name == null ? "" : name);
            if (!m.find()) return 0;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss", java.util.Locale.US);
            f.setLenient(false);
            java.util.Date d = f.parse(m.group(1) + "_" + m.group(2));
            return d != null ? d.getTime() : 0;
        } catch (Throwable ignore) { return 0; }
    }

    /** Localized medium date+time for an archive name (falls back to name). */
    public static String prettyBackupDate(android.content.Context c, String name) {
        try {
            long t = backupTimeOf(name);
            if (t <= 0) return name;
            return android.text.format.DateFormat.getMediumDateFormat(c)
                .format(new java.util.Date(t)) + " "
                + android.text.format.DateFormat.getTimeFormat(c)
                    .format(new java.util.Date(t));
        } catch (Throwable ignore) { return name; }
    }

    /**
     * Full data backup of ONE clone into BACKUP_DIR.
     * @return absolute archive path. Throws on failure/empty data.
     * Must run off the main thread (large apps take minutes).
     */
    public static String backupClone(String pkg, int userId) throws Exception {
        guardBackupArgs(pkg, userId);
        String file = BACKUP_DIR + "/" + pkg + "_u" + userId + "_" + backupStamp() + ".tar.gz";
        String script = "set -u;"
            + " D='" + BACKUP_DIR + "';"
            + " F='" + file + "';"
            + " P='" + pkg + "'; U='" + userId + "';"
            + " mkdir -p \"$D\" || { echo MKDIR_FAIL; exit 2; };"
            + " ARGS='';"
            + " [ -e \"/data/user/$U/$P\" ] && ARGS=\"$ARGS user/$U/$P\";"
            + " [ -e \"/data/user_de/$U/$P\" ] && ARGS=\"$ARGS user_de/$U/$P\";"
            + " [ -z \"$ARGS\" ] && { echo -n EMPTY:users=; ls /data/user 2>/dev/null | tr '\\n' ','; exit 3; };"
            + " tar -czpf \"$F\" -C /data $ARGS || { echo TAR_FAIL; rm -f \"$F\"; exit 4; };"
            + " echo BACKUP_OK:\"$F\"";
        ExecResult r = execRootGlobal(new String[]{"sh", "-c", script}, BACKUP_TIMEOUT_MS);
        if (!r.ok || !r.out.contains("BACKUP_OK:")) throw new Exception(r.out.trim());
        invalidatePackages(userId);
        return file;
    }

    /** Newest-first backups for one package (empty list when none). */
    public static List<Backup> listBackups(String pkg) {
        List<Backup> out = new ArrayList<>();
        try {
            if (pkg == null || !SAFE_PKG.matcher(pkg).matches()) return out;
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "ls -l '" + BACKUP_DIR + "'/" + pkg + "_u*.tar.* 2>/dev/null"},
                15000);
            if (!r.ok) return out;
            for (String line : r.out.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("total")) continue;
                String[] parts = line.split("\\s+");
                // GNU: perms links owner group <size> mon day time name (9+)
                // toybox: perms links owner group <size> yyyy-mm-dd hh:mm name (8)
                if (parts.length < 7) continue;
                String raw = parts[parts.length - 1];
                // ls echoes the full path when given one: reduce to basename.
                String name = raw.substring(raw.lastIndexOf('/') + 1);
                if (!isArchName(name) || name.contains("/")
                        || !name.startsWith(pkg + "_u")) continue;
                long size = -1;
                try { size = Long.parseLong(parts[4]); } catch (Throwable ignore) { }
                out.add(new Backup(BACKUP_DIR + "/" + name, size));
            }
            Collections.sort(out, (a, b) -> b.path.compareTo(a.path)); // newest first (name has timestamp)
        } catch (Throwable ignore) { }
        return out;
    }

    public static void deleteBackup(String path) throws Exception {
        guardBackupPath(path);
        ExecResult r = suGlobal("rm", "-f", path);
        if (!r.ok) throw new Exception(r.out);
    }

    /**
     * Restore archive INTO an existing clone's data dirs. Force-stops the app
     * first, re-applies SELinux labels via restorecon, and refreshes caches.
     * The clone record must already exist (restore targets a live copy).
     */
    public static void restoreClone(String pkg, int userId, String path) throws Exception {
        guardBackupArgs(pkg, userId);
        guardBackupPath(path);
        String decomp;
        try { decomp = decompFor(path); }
        catch (Throwable t) { decomp = "gzip -d -c"; } // legacy .tar.gz
        String script = "set -u;"
            + " P='" + pkg + "'; U='" + userId + "'; F='" + path + "';"
            + " [ -f \"$F\" ] || { echo NO_FILE; exit 2; };"
            + " am force-stop --user \"$U\" \"$P\" 2>/dev/null;"
            + " " + decomp + " \"$F\" 2>/dev/null | tar -xpf - -C /data"
            + " || { echo TAR_FAIL; exit 3; };"
            + " if command -v restorecon >/dev/null 2>&1; then"
            + "   restorecon -R \"/data/user/$U/$P\" 2>/dev/null;"
            + "   [ -e \"/data/user_de/$U/$P\" ] && restorecon -R \"/data/user_de/$U/$P\" 2>/dev/null;"
            + " fi;"
            + " echo RESTORE_OK";
        ExecResult r = execRootGlobal(new String[]{"sh", "-c", script}, BACKUP_TIMEOUT_MS);
        if (!r.ok || !r.out.contains("RESTORE_OK")) throw new Exception(r.out.trim());
        invalidatePackages(userId);
        invalidateSuspended(pkg, userId);
        invalidateResolve(pkg, userId);
    }

    private static boolean isArchName(String name) {
        if (name == null) return false;
        String base = name.endsWith(".enc")
            ? name.substring(0, name.length() - 4) : name;
        for (String e : ARCH_EXTS) if (base.endsWith(e)) return true;
        return false;
    }

    /** Archive path is app-writable directly (no su needed for R/W). */
    static boolean isDirectPath(String path) {
        return path != null && !path.startsWith("/data/")
            && !path.startsWith("/mnt/") && !path.startsWith("/mnt_");
    }

    // ---------------- backup engine v2: parts, meta, specials, volumes ----------------
    // Parts model (pro format): data/cache, runtime perms (pm grant),
    // appops modes (cmd appops set), SSAID (settings_ssaid.xml + reboot),
    // and system-wide specials (SMS / call-log / Wi-Fi) as separate archives.

    public static final class Comp {
        public final String name;   // zstd | xz | gzip
        public final String ext;    // .tar.zst | .tar.xz | .tar.gz
        public final String compCmd;   // "zstd -19 -o" style prefix (file appended)
        public final String decompCmd; // "zstd -d -c" style prefix (file appended)
        Comp(String n, String e, String c, String d) {
            name = n; ext = e; compCmd = c; decompCmd = d;
        }
    }

    private static volatile Comp compCache = null;
    private static volatile Boolean ignoreFailedReadCache = null;
    /** User override: null/"auto" = strongest verified; "gzip" = force gzip. */
    private static volatile String compOverride = null;

    public static void setCompOverride(String v) {
        compOverride = ("gzip".equals(v) || "zstd".equals(v)) ? v : null;
        compCache = null;
    }

    public static void dropCompCache() { compCache = null; }

    /**
     * `tar --ignore-failed-read`: live apps delete files mid-archive; without
     * it one vanished file aborts the whole tar (and the masked pipe used to
     * ship a corrupt ~29-byte "archive"). Probed once; omitted when absent.
     */
    static String tarOpts() {
        Boolean hit = ignoreFailedReadCache;
        if (hit == null) {
            boolean v = false;
            try {
                ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                    "tar --ignore-failed-read --help >/dev/null 2>&1"
                    + " && echo TAROPT_OK"}, 30000);
                v = r.ok && r.out.contains("TAROPT_OK");
            } catch (Throwable ignore) { v = false; }
            ignoreFailedReadCache = v;
            Log.i(TAG, "tar ignore-failed-read=" + v);
            hit = v;
        }
        return hit ? " --ignore-failed-read" : "";
    }

    /** Prefix making tar pipelines fail loudly (exit = first failure). */
    private static final String PIPESTRICT = "set -o pipefail 2>/dev/null || true;";

    /**
     * Strongest compressor that REALLY works, verified once by a piped
     * roundtrip (presence alone lies: this ROM ships a zstd whose
     * compression path errors out while decompression works).
     * Order: zstd -19 > xz -9 > gzip -9 (always present).
     * "Strongest by default" = strongest verified on-device.
     */
    public static Comp detectComp() {
        Comp hit = compCache;
        if (hit != null) return hit;
        String ov = compOverride;
        Comp[][] cands = {
            {new Comp("zstd -19", ".tar.zst", "zstd -19 -c", "zstd -d -c")},
            {new Comp("xz -9", ".tar.xz", "xz -9 -T0 -c", "xz -d -c")},
            {new Comp("gzip -9", ".tar.gz", "gzip -9 -c", "gzip -d -c")},
        };
        Comp c = cands[2][0];
        // Forced gzip (max compatibility with PC tools like 7-Zip).
        if ("gzip".equals(ov)) {
            compCache = c;
            Log.i(TAG, "backup comp=gzip (forced)");
            return c;
        }
        try {
            for (Comp[] slot : cands) {
                Comp cand = slot[0];
                // Forced zstd: accept only if verified (else fall through).
                if ("zstd".equals(ov) && !cand.ext.equals(".tar.zst")) continue;
                try {
                    ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                        "echo ROUNDTRIP123 | " + cand.compCmd
                        + " 2>/dev/null | " + cand.decompCmd + " 2>/dev/null"},
                        60000);
                    if (r.ok && r.out.trim().equals("ROUNDTRIP123")) {
                        c = cand;
                        break;
                    }
                } catch (Throwable ignore) { }
            }
        } catch (Throwable ignore) { }
        if ("zstd".equals(ov) && !c.ext.equals(".tar.zst")) {
            Log.i(TAG, "forced zstd unavailable, auto fallback");
        }
        compCache = c;
        Log.i(TAG, "backup comp=" + c.name + c.ext);
        return c;
    }
    // NOTE: compCmd reads stdin and writes stdout; callers MUST redirect
    // (`... | $compCmd > "file"`). decompCmd takes the file as its arg.

    public static final class Volume {
        public final String label;
        public final String path;   // backup root for this volume
        public final long freeBytes;
        public final boolean internal;
        Volume(String l, String p, long f, boolean i) {
            label = l; path = p; freeBytes = f; internal = i;
        }
    }

    /**
     * Internal private dir + every mounted portable volume (SD/OTG) from
     * `sm list-volumes` (public:UUID mounted UUID -> /storage/UUID/CLONIX).
     */
    public static List<Volume> volumes() {
        List<Volume> out = new ArrayList<>();
        out.add(new Volume("Internal", BACKUP_DIR, freeBytes(BACKUP_DIR), true));
        try {
            ExecResult r = suGlobal("sm", "list-volumes");
            if (!r.ok) return out;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "public:([^\\s]+)\\s+mounted\\s+([^\\s]+)").matcher(r.out);
            while (m.find()) {
                String uuid = m.group(2);
                if (uuid == null || uuid.equals("null") || uuid.contains("..")) continue;
                String path = "/storage/" + uuid + "/CLONIX";
                out.add(new Volume("SD/USB (" + uuid + ")", path,
                    freeBytes(path), false));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    private static long freeBytes(String path) {
        try {
            ExecResult r = suGlobal("sh", "-c",
                "mkdir -p '" + path + "' 2>/dev/null; df -k '" + path
                + "' 2>/dev/null | tail -1");
            if (!r.ok) return -1;
            String[] p = r.out.trim().split("\\s+");
            // Filesystem 1K-blocks Used Available Use% Mounted
            if (p.length >= 4) return Long.parseLong(p[3]) * 1024L;
        } catch (Throwable ignore) { }
        return -1;
    }

    /** Resolve + create the destination dir for a dest pref. */
    public static String resolveDestDir(String destPref) throws Exception {
        String dir = BACKUP_DIR;
        if (destPref != null && destPref.startsWith("/storage/")
                && destPref.contains("/CLONIX") && !destPref.contains("..")
                && !destPref.contains("'") && !destPref.contains(" ")) {
            dir = destPref;
        }
        migrateLegacyDir();
        ExecResult r = execRootGlobal(new String[]{"sh", "-c",
            "mkdir -p '" + dir + "' && echo MKDIR_OK"}, 30000);
        if (!r.ok || !r.out.contains("MKDIR_OK")) throw new Exception(r.out.trim());
        return dir;
    }

    /** One-time move of pre-rebrand archives into the new store (idempotent). */
    private static void migrateLegacyDir() {
        try {
            execRootGlobal(new String[]{"sh", "-c",
                "if [ -d " + LEGACY_BACKUP_DIR + " ] && [ ! -d " + BACKUP_DIR
                + " ]; then mkdir -p /data/misc/clonix && mv " + LEGACY_BACKUP_DIR
                + " " + BACKUP_DIR + "; fi; true"}, 30000);
        } catch (Throwable ignore) { }
    }

    // ---------------- meta sidecar (line-based, shell-safe) ----------------

    public static final class Meta {
        public String comp = "gzip";
        public String parts = "";
        public String enc = "";   // "" or "aes256gcm"
        public String ver = "";
        public String vcode = "";
        public String label = "";
        public final List<String> perms = new ArrayList<>();
        public final java.util.Map<String, String> appops = new java.util.HashMap<>();
        public String ssaidLine = null;
        public boolean hasPart(String p) {
            return parts != null && (";" + parts + ";").contains(";" + p + ";");
        }
    }

    public static String metaPathFor(String archive) {
        boolean enc = archive != null && archive.endsWith(".enc");
        String base = enc ? archive.substring(0, archive.length() - 4) : archive;
        for (String e : ARCH_EXTS) {
            if (base.endsWith(e)) {
                String m = base.substring(0, base.length() - e.length()) + ".meta";
                return enc ? m + ".enc" : m;
            }
        }
        return archive + (enc ? ".meta.enc" : ".meta");
    }

    public static String shaPathFor(String archive) {
        return metaPathFor(archive).replace(".meta", ".sha256")
            .replace(".sha256.enc", ".sha256");
    }

    private static String b64(String s) {
        try {
            return android.util.Base64.encodeToString(s.getBytes("UTF-8"),
                android.util.Base64.NO_WRAP);
        } catch (Throwable t) { return ""; }
    }

    public static void writeMeta(String archive, Meta m) throws Exception {
        StringBuilder sb = new StringBuilder("# clonepilot-meta v2\n");
        sb.append("comp=").append(m.comp).append('\n');
        sb.append("enc=").append(m.enc == null ? "" : m.enc).append('\n');
        sb.append("ver=").append(m.ver == null ? "" : m.ver).append('\n');
        sb.append("vcode=").append(m.vcode == null ? "" : m.vcode).append('\n');
        sb.append("label=").append(m.label == null ? "" : m.label).append('\n');
        sb.append("parts=").append(m.parts == null ? "" : m.parts).append('\n');
        for (String p : m.perms) sb.append("perm=").append(p).append('\n');
        for (java.util.Map.Entry<String, String> e : m.appops.entrySet()) {
            sb.append("appop=").append(e.getKey()).append(':')
                .append(e.getValue()).append('\n');
        }
        if (m.ssaidLine != null) sb.append("ssaid=").append(m.ssaidLine).append('\n');
        String b = b64(sb.toString());
        if (b.isEmpty()) throw new Exception("meta encode failed");
        ExecResult r = execRootGlobal(new String[]{"sh", "-c",
            "echo '" + b + "' | base64 -d > '" + metaPathFor(archive)
            + "' && echo META_OK"}, 30000);
        if (!r.ok || !r.out.contains("META_OK")) throw new Exception(r.out.trim());
    }

    public static Meta readMeta(String archive) {
        Meta m = new Meta();
        try {
            guardBackupPath(archive);
            // Encrypted metas are unreadable without the session password.
            if (archive.endsWith(".enc")) return m;
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "cat '" + metaPathFor(archive) + "' 2>/dev/null"}, 30000);
            if (!r.ok) return m;
            parseMetaText(r.out, m);
        } catch (Throwable ignore) { }
        return m;
    }

    /** Decrypt + parse a .meta.enc sidecar (app-readable paths). */
    public static Meta readMetaDecrypted(String archive, char[] pw) throws Exception {
        Meta m = new Meta();
        guardBackupPath(archive);
        java.io.File mf = new java.io.File(metaPathFor(archive));
        java.io.FileInputStream fis = new java.io.FileInputStream(mf);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            try {
                CryptoVault.decryptStream(fis, bos, pw);
            } catch (Exception e) {
                // Backward compat: early builds wrote plaintext at .meta.enc
                // when no SSAID was captured. Accept it (still password-gated
                // by the caller asking for one).
                if (!String.valueOf(e.getMessage()).contains("not an encrypted")) {
                    throw e;
                }
                try { fis.close(); } catch (Throwable ignore) { }
                java.io.FileInputStream fis2 = new java.io.FileInputStream(mf);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis2.read(buf)) > 0) bos.write(buf, 0, n);
                } finally {
                    try { fis2.close(); } catch (Throwable ignore) { }
                }
            }
        } finally {
            try { fis.close(); } catch (Throwable ignore) { }
        }
        parseMetaText(bos.toString("UTF-8"), m);
        return m;
    }

    private static void parseMetaText(String text, Meta m) {
        try {
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.startsWith("comp=")) m.comp = line.substring(5);
                else if (line.startsWith("enc=")) m.enc = line.substring(4);
                else if (line.startsWith("ver=")) m.ver = line.substring(4);
                else if (line.startsWith("vcode=")) m.vcode = line.substring(6);
                else if (line.startsWith("label=")) m.label = line.substring(6);
                else if (line.startsWith("parts=")) m.parts = line.substring(6);
                else if (line.startsWith("perm=")) m.perms.add(line.substring(5));
                else if (line.startsWith("appop=")) {
                    String v = line.substring(6);
                    int c = v.indexOf(':');
                    if (c > 0) m.appops.put(v.substring(0, c), v.substring(c + 1));
                } else if (line.startsWith("ssaid=")) m.ssaidLine = line.substring(6);
            }
        } catch (Throwable ignore) { }
    }

    // ---------------- per-part collectors (binder paths, no SELinux wall) ----------------

    private static final java.util.regex.Pattern USER_HEAD =
            java.util.regex.Pattern.compile("^\\s*User (\\d+)[: ]");
    private static final java.util.regex.Pattern PERM_LINE =
            java.util.regex.Pattern.compile("^\\s*([\\w.]+): granted=(true|false)");

    private static volatile java.util.Set<String> dangerousCache = null;

    /**
     * Dangerous (runtime-grantable) permissions from `pm list permissions -g -d`.
     * dumpsys also reports granted install-time/signature perms, which `pm grant`
     * would reject — filter to this set so restores report 0 false failures.
     */
    public static java.util.Set<String> dangerousPerms() {
        java.util.Set<String> hit = dangerousCache;
        if (hit != null) return hit;
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            ExecResult r = su("pm", "list", "permissions", "-g", "-d");
            if (r.ok) {
                for (String raw : r.out.split("\n")) {
                    String t = raw.trim();
                    if (t.startsWith("permission:")) {
                        String p = t.substring(11).trim().split("\\s+")[0];
                        if (!p.isEmpty()) out.add(p);
                    }
                }
            }
        } catch (Throwable ignore) { }
        dangerousCache = out;
        return out;
    }

    /** Granted runtime permissions of pkg IN userId, via dumpsys (no file access). */
    public static List<String> grantedPerms(String pkg, int userId) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> dangerous = dangerousPerms();
        try {
            ExecResult r = su("dumpsys", "package", pkg);
            if (!r.ok) return out;
            int curUser = -1;
            boolean inPerms = false;
            for (String raw : r.out.split("\n")) {
                java.util.regex.Matcher uh = USER_HEAD.matcher(raw);
                if (uh.find()) {
                    try { curUser = Integer.parseInt(uh.group(1)); }
                    catch (Throwable ignore) { curUser = -1; }
                    inPerms = false;
                    continue;
                }
                String t = raw.trim();
                if (t.equals("runtime permissions:")) {
                    inPerms = (curUser == userId);
                    continue;
                }
                if (inPerms) {
                    java.util.regex.Matcher pm = PERM_LINE.matcher(raw);
                    if (pm.find()) {
                        if (curUser == userId && "true".equals(pm.group(2))
                                && (dangerous.isEmpty()
                                    || dangerous.contains(pm.group(1)))) {
                            out.add(pm.group(1));
                        }
                    } else if (!t.isEmpty()
                            && !t.startsWith("flags=") && !t.startsWith("[")) {
                        inPerms = false; // next section
                    }
                }
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /** Non-default appops modes of pkg in user, via `cmd appops get` (binder). */
    public static java.util.Map<String, String> appOpsModes(String pkg, int userId) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try {
            ExecResult r = su("cmd", "appops", "get", "--user",
                String.valueOf(userId), pkg);
            if (!r.ok) return out;
            for (String raw : r.out.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("Uid mode:")) line = line.substring(9).trim();
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String op = line.substring(0, c).trim();
                // Modes carry metadata: "allow; time=...; duration=..." — first token only.
                String mode = line.substring(c + 1).split(";")[0].trim()
                    .split("\\s+")[0];
                if (!op.matches("[A-Z_0-9]+")) continue;
                if (mode.equals("default") || mode.isEmpty()) continue;
                if (!mode.equals("allow") && !mode.equals("ignore")
                        && !mode.equals("deny") && !mode.equals("foreground")) continue;
                out.put(op, mode);
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /**
     * Raw settings_ssaid.xml entry line for pkg in user (or null).
     * File path needs system_data_file read: best-effort, null when denied.
     */
    public static String ssaidEntry(String pkg, int userId) {
        try {
            if (pkg == null || !SAFE_PKG.matcher(pkg).matches() || userId < 0) return null;
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "cat '/data/system/users/" + userId + "/settings_ssaid.xml' 2>/dev/null"},
                15000);
            if (!r.ok) return null;
            for (String raw : r.out.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("<setting") && line.contains("package=\"" + pkg + "\"")) {
                    return line;
                }
            }
        } catch (Throwable ignore) { }
        return null;
    }

    // ---------------- full backup / restore ----------------

    public static final class PartResult {
        public final String part;
        public final boolean ok;
        public final String detail;
        PartResult(String p, boolean o, String d) { part = p; ok = o; detail = d; }
    }

    public static final class FullResult {
        public String archive;
        public String comp;
        public boolean enc;
        /** 0 = full, N = incremental level, -2 = unchanged (no archive). */
        public int level;
        public final List<PartResult> parts = new ArrayList<>();
    }

    // ---------------- root <-> Java streaming (for crypto + verify) ----------------
    // stdout of `su -mm` scripts is BINARY (tar stream): never merge stderr
    // into it. stderr is drained on a side thread into a capped buffer.

    private static final int PIPE_BUF = 65536;
    private static final int ERR_CAP = 65536;

    private static String[] globalArgv(String[] shellCmd) {
        if (shellCmd.length == 3 && "sh".equals(shellCmd[0])
                && "-c".equals(shellCmd[1])) {
            return new String[]{"su", "-mm", "-c", shellCmd[2]};
        }
        return new String[]{"su", "-mm", "-c", joinShell(shellCmd)};
    }

    /**
     * Run in global ns, pump stdout bytes into sink (tar stream compatible).
     * Returns exit status + stderr tail. Must run off the main thread.
     */
    public static ExecResult execGlobalStreaming(String[] shellCmd,
            java.io.OutputStream sink, long timeoutMs) {
        final StringBuilder err = new StringBuilder();
        Process p = null;
        try {
            if (!hasGlobalNs()) return new ExecResult(false, "no global ns");
            p = new ProcessBuilder(globalArgv(shellCmd)).start();
            final Process fp = p;
            final java.io.InputStream serr = fp.getErrorStream();
            Thread drainer = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int total = 0, n;
                    while ((n = serr.read(buf)) > 0 && total < ERR_CAP) {
                        int w = Math.min(n, ERR_CAP - total);
                        err.append(new String(buf, 0, w, "UTF-8"));
                        total += w;
                    }
                } catch (Throwable ignore) { }
                finally { try { serr.close(); } catch (Throwable ignore) { } }
            });
            drainer.setDaemon(true);
            drainer.start();
            byte[] buf = new byte[PIPE_BUF];
            java.io.InputStream sout = fp.getInputStream();
            int n;
            while ((n = sout.read(buf)) > 0) sink.write(buf, 0, n);
            sink.flush();
            boolean done = fp.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            try { drainer.join(3000); } catch (Throwable ignore) { }
            if (!done) {
                try { fp.destroyForcibly(); } catch (Throwable ignore) { }
                return new ExecResult(false, "timeout " + err);
            }
            try { sout.close(); } catch (Throwable ignore) { }
            return new ExecResult(fp.exitValue() == 0, err.toString());
        } catch (Throwable t) {
            return new ExecResult(false, String.valueOf(t.getMessage()));
        } finally {
            if (p != null) { try { p.getOutputStream().close(); } catch (Throwable ignore) { } }
        }
    }

    /**
     * Run in global ns with src piped to the process stdin
     * (decrypt -> decomp -> tar -x flows). Stdout/stderr capped strings.
     */
    public static ExecResult execGlobalWithStdin(String[] shellCmd,
            final java.io.InputStream src, long timeoutMs) {
        Process p = null;
        try {
            if (!hasGlobalNs()) return new ExecResult(false, "no global ns");
            p = new ProcessBuilder(globalArgv(shellCmd)).start();
            final Process fp = p;
            final java.io.OutputStream pin = fp.getOutputStream();
            Thread feeder = new Thread(() -> {
                try {
                    byte[] buf = new byte[PIPE_BUF];
                    int n;
                    while ((n = src.read(buf)) > 0) pin.write(buf, 0, n);
                    pin.flush();
                } catch (Throwable ignore) { }
                finally { try { pin.close(); } catch (Throwable ignore) { } }
            });
            feeder.setDaemon(true);
            feeder.start();
            String out = readAllCapped(fp.getInputStream(), -1);
            String err = readAllCapped(fp.getErrorStream(), -1);
            boolean done = fp.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            try { feeder.join(5000); } catch (Throwable ignore) { }
            if (!done) {
                try { fp.destroyForcibly(); } catch (Throwable ignore) { }
                return new ExecResult(false, "timeout " + err);
            }
            return new ExecResult(fp.exitValue() == 0, (out + err).trim());
        } catch (Throwable t) {
            return new ExecResult(false, String.valueOf(t.getMessage()));
        } finally {
            if (p != null) { try { p.destroyForcibly(); } catch (Throwable ignore) { } }
        }
    }

    private static final long FULL_TIMEOUT_MS = 15 * 60 * 1000L;

    /**
     * Full per-clone backup with selectable parts into destDir.
     * Must run off the main thread. Data goes through the strongest
     * compressor present (zstd -19 > xz -9 > gzip -9).
     */
    public static FullResult backupFull(String pkg, int userId, BackupJob opts,
            String destDir) throws Exception {
        return backupFull(pkg, userId, opts, destDir, "", "", "");
    }

    public static FullResult backupFull(String pkg, int userId, BackupJob opts,
            String destDir, String ver, String vcode, String label) throws Exception {
        guardBackupArgs(pkg, userId);
        if (opts == null) throw new Exception("no options");
        Comp comp = detectComp();
        String file = destDir + "/" + pkg + "_u" + userId + "_" + backupStamp() + comp.ext;
        FullResult res = new FullResult();
        res.archive = file;
        res.comp = comp.name;
        StringBuilder partsSb = new StringBuilder();
        Meta meta = new Meta();
        meta.comp = comp.name;
        meta.ver = ver != null ? ver : "";
        meta.vcode = vcode != null ? vcode : "";
        meta.label = label != null ? label : "";

        if (opts.data) {
            StringBuilder ex = new StringBuilder();
            if (!opts.cache) {
                ex.append(" --exclude=*/cache --exclude=*/code_cache");
            }
            String script = "set -u;" + PIPESTRICT
                + " F='" + file + "'; P='" + pkg + "'; U='" + userId + "';"
                + " ARGS='';"
                + " [ -e \"/data/user/$U/$P\" ] && ARGS=\"$ARGS user/$U/$P\";"
                + " [ -e \"/data/user_de/$U/$P\" ] && ARGS=\"$ARGS user_de/$U/$P\";"
                + " [ -z \"$ARGS\" ] && { echo -n EMPTY:users=; ls /data/user 2>/dev/null | tr '\\n' ','; exit 3; };"
                + " ERR=\"${F}.tarerr\";"
                + " tar -cf -" + tarOpts() + " -C /data" + ex + " $ARGS 2>\"$ERR\""
                + " | " + comp.compCmd + " > \"$F\""
                + " || { echo -n TAR_FAIL:; head -c 300 \"$ERR\" 2>/dev/null;"
                + " rm -f \"$F\" \"$ERR\"; exit 4; };"
                + " rm -f \"$ERR\";"
                + " [ -s \"$F\" ] || { echo EMPTY_FILE; rm -f \"$F\"; exit 5; };"
                + " echo DATA_OK";
            ExecResult r = execRootGlobal(new String[]{"sh", "-c", script}, FULL_TIMEOUT_MS);
            boolean ok = r.ok && r.out.contains("DATA_OK");
            res.parts.add(new PartResult("data", ok,
                ok ? comp.name + (opts.cache ? "+cache" : "") : r.out.trim()));
            if (!ok) throw new Exception(r.out.trim());
            if (partsSb.length() > 0) partsSb.append(';');
            partsSb.append("data");
        }
        if (opts.perms) {
            List<String> granted = grantedPerms(pkg, userId);
            meta.perms.addAll(granted);
            res.parts.add(new PartResult("perms", true, granted.size() + " grants"));
            if (partsSb.length() > 0) partsSb.append(';');
            partsSb.append("perms");
        }
        if (opts.appops) {
            java.util.Map<String, String> modes = appOpsModes(pkg, userId);
            meta.appops.putAll(modes);
            res.parts.add(new PartResult("appops", true, modes.size() + " modes"));
            if (partsSb.length() > 0) partsSb.append(';');
            partsSb.append("appops");
        }
        if (opts.ssaid) {
            String line = ssaidEntry(pkg, userId);
            boolean ok = line != null;
            if (ok) {
                meta.ssaidLine = line;
                if (partsSb.length() > 0) partsSb.append(';');
                partsSb.append("ssaid");
            }
            res.parts.add(new PartResult("ssaid", ok,
                ok ? "reboot to apply" : "unreadable (SELinux?)"));
        }
        meta.parts = partsSb.toString();
        writeMeta(file, meta);
        writeShaSidecar(file);
        invalidatePackages(userId);
        return res;
    }

    /** Best-effort .sha256 sidecar (hash of the finished archive). */
    public static void writeShaSidecar(String archive) {
        try {
            guardBackupPath(archive);
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "sha256sum '" + archive + "' 2>/dev/null | cut -d' ' -f1"}, 120000);
            String h = r.ok ? r.out.trim().split("\\s+")[0] : "";
            if (h.matches("[0-9a-f]{64}")) {
                execRootGlobal(new String[]{"sh", "-c",
                    "echo '" + h + "' > '" + shaPathFor(archive) + "'"}, 30000);
            }
        } catch (Throwable ignore) { }
    }

    public static String readShaSidecar(String archive) {
        try {
            guardBackupPath(archive);
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "cat '" + shaPathFor(archive) + "' 2>/dev/null"}, 30000);
            if (r.ok) {
                String h = r.out.trim().split("\\s+")[0];
                if (h.matches("[0-9a-f]{64}")) return h;
            }
        } catch (Throwable ignore) { }
        return null;
    }

    public static final class RestoreResult {
        public boolean rebootNeeded = false;
        public final List<String> failures = new ArrayList<>();
        public final List<PartResult> parts = new ArrayList<>();
    }

    private static String decompFor(String archive) throws Exception {
        if (archive != null && archive.endsWith(".enc")) {
            throw new Exception("encrypted archive: password required");
        }
        if (archive.endsWith(".tar.zst")) return "zstd -d -c";
        if (archive.endsWith(".tar.xz")) return "xz -d -c";
        if (archive.endsWith(".tar.gz")) return "gzip -d -c";
        throw new Exception("unknown archive type");
    }

    /**
     * Restore selected parts from archive into an existing clone.
     * sel.data=false skips data entirely; sel.privateData=false restores
     * everything EXCEPT shared_prefs + databases (media/files kept).
     */
    public static RestoreResult restoreFull(String pkg, int userId, String archive,
            BackupJob sel) throws Exception {
        guardBackupArgs(pkg, userId);
        guardBackupPath(archive);
        if (sel == null) throw new Exception("no options");
        RestoreResult res = new RestoreResult();
        Meta meta = readMeta(archive);
        String decomp = decompFor(archive);

        if (sel.data) {
            String script = "set -u;"
                + " P='" + pkg + "'; U='" + userId + "'; F='" + archive + "';"
                + " [ -f \"$F\" ] || { echo NO_FILE; exit 2; };"
                + " am force-stop --user \"$U\" \"$P\" 2>/dev/null;"
                + " " + decomp + " \"$F\" 2>/dev/null | tar -xpf - -C /data"
                + " || { echo TAR_FAIL; exit 3; };"
                + " if command -v restorecon >/dev/null 2>&1; then"
                + "   restorecon -R \"/data/user/$U/$P\" 2>/dev/null;"
                + "   [ -e \"/data/user_de/$U/$P\" ] && restorecon -R \"/data/user_de/$U/$P\" 2>/dev/null;"
                + " fi;"
                + " echo DATA_OK";
            ExecResult r = execRootGlobal(new String[]{"sh", "-c", script}, FULL_TIMEOUT_MS);
            boolean ok = r.ok && r.out.contains("DATA_OK");
            res.parts.add(new PartResult("data", ok, ok ? "" : r.out.trim()));
            if (!ok) { res.failures.add("data: " + r.out.trim()); }
            else if (!sel.privateData) {
                // Optional scope: drop login-state dirs, keep media/files/cache.
                ExecResult rm = execRootGlobal(new String[]{"sh", "-c",
                    "P='" + pkg + "'; U='" + userId + "';"
                    + " for B in \"/data/user/$U/$P\" \"/data/user_de/$U/$P\"; do"
                    + " [ -d \"$B\" ] || continue;"
                    + " rm -rf \"$B/shared_prefs\" \"$B/databases\" 2>/dev/null;"
                    + " done; echo PRIV_OK"}, 60000);
                res.parts.add(new PartResult("private-skip", rm.ok, ""));
            }
        }
        applyMetaParts(pkg, userId, meta, sel, res);
        invalidatePackages(userId);
        invalidateSuspended(pkg, userId);
        invalidateResolve(pkg, userId);
        return res;
    }

    /**
     * Replace (or append) the package's entry in settings_ssaid.xml,
     * preserving file owner/mode. System applies it on reboot.
     */
    private static boolean injectSsaid(String pkg, int userId, String line) {
        try {
            if (line == null || !line.contains("package=\"" + pkg + "\"")) return false;
            String xml = "/data/system/users/" + userId + "/settings_ssaid.xml";
            String b = b64(line);
            if (b.isEmpty()) return false;
            String script = "set -u;"
                + " X='" + xml + "';"
                + " [ -f \"$X\" ] || exit 2;"
                + " O=$(stat -c '%u:%g' \"$X\" 2>/dev/null); M=$(stat -c '%a' \"$X\" 2>/dev/null);"
                + " echo '" + b + "' | base64 -d > /data/local/tmp/cp_ssaid.tmp || exit 3;"
                + " grep -v 'package=\"" + pkg + "\"' \"$X\" > /data/local/tmp/cp_ssaid.new || exit 4;"
                + " { grep -v '</settings>' /data/local/tmp/cp_ssaid.new;"
                + "   cat /data/local/tmp/cp_ssaid.tmp;"
                + "   echo '</settings>'; } > \"$X\" || exit 5;"
                + " [ -n \"$O\" ] && chown \"$O\" \"$X\" 2>/dev/null;"
                + " [ -n \"$M\" ] && chmod \"$M\" \"$X\" 2>/dev/null;"
                + " rm -f /data/local/tmp/cp_ssaid.tmp /data/local/tmp/cp_ssaid.new;"
                + " echo SSAID_OK";
            ExecResult r = execRootGlobal(new String[]{"sh", "-c", script}, 60000);
            return r.ok && r.out.contains("SSAID_OK");
        } catch (Throwable ignore) { return false; }
    }

    // ---------------- incremental backups (file-level, rsync quick-check) ----------------
    // Snapshot: `<dest>/<pkg>_u<uid>.snap`, lines `relpath|size|mtimeNs` sorted.
    // Incr archive: `<pkg>_u<uid>_<ts>.incr<N>.tar.<ext>` (+.enc), meta level=N.
    // Restore = newest full + newer incrs in order. Retention is chain-aware.

    public static String snapPath(String destDir, String pkg, int userId) {
        return destDir + "/" + pkg + "_u" + userId + ".snap";
    }

    private static final java.util.regex.Pattern INCR_PAT =
            java.util.regex.Pattern.compile("\\.incr(\\d+)\\.");

    /** Incremental level parsed from name: 0 = full/legacy, N = incr. */
    public static int incrLevelOf(String name) {
        try {
            java.util.regex.Matcher m = INCR_PAT.matcher(name == null ? "" : name);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable ignore) { }
        return 0;
    }

    /** Chain for one clone, OLDEST-first (base full, then incrs): for restore. */
    public static List<Backup> chainFor(String destDir, String pkg, int userId) {
        List<Backup> all = new ArrayList<>();
        try {
            for (Backup b : listFullBackups(destDir, null)) {
                String n = b.path.substring(b.path.lastIndexOf('/') + 1);
                if (!n.startsWith(pkg + "_u" + userId + "_")) continue;
                all.add(b);
            }
            // Newest full at/under which incrs belong: newest full overall.
            Backup base = null;
            for (Backup b : all) {
                String n = b.path.substring(b.path.lastIndexOf('/') + 1);
                if (incrLevelOf(n) == 0 && (base == null
                        || b.path.compareTo(base.path) > 0)) {
                    base = b;
                }
            }
            List<Backup> chain = new ArrayList<>();
            if (base != null) {
                chain.add(base);
                List<Backup> incrs = new ArrayList<>();
                for (Backup b : all) {
                    String n = b.path.substring(b.path.lastIndexOf('/') + 1);
                    if (incrLevelOf(n) > 0 && b.path.compareTo(base.path) > 0) {
                        incrs.add(b);
                    }
                }
                Collections.sort(incrs, (a, b2) -> a.path.compareTo(b2.path));
                chain.addAll(incrs);
            }
            return chain;
        } catch (Throwable ignore) { return new ArrayList<Backup>(); }
    }

    /**
     * Incremental data backup: only files whose size|mtime differ from the
     * snapshot (rsync quick-check, no hashing = fast on big games).
     * Throws "unchanged" when nothing differs (callers report skip, not fail).
     */
    public static FullResult backupIncr(String pkg, int userId, BackupJob opts,
            String destDir, String ver, String vcode, String label) throws Exception {
        guardBackupArgs(pkg, userId);
        if (opts == null || !opts.data) throw new Exception("data part required");
        Comp comp = detectComp();
        String snap = snapPath(destDir, pkg, userId);
        // Next level = existing incrs + 1 (per clone, across bases is fine:
        // restore orders by name, base resets the chain).
        int level = 1;
        try {
            for (Backup b : listFullBackups(destDir, pkg)) {
                String n = b.path.substring(b.path.lastIndexOf('/') + 1);
                if (n.startsWith(pkg + "_u" + userId + "_")) {
                    level = Math.max(level, incrLevelOf(n) + 1);
                }
            }
        } catch (Throwable ignore) { }
        String file = destDir + "/" + pkg + "_u" + userId + "_" + backupStamp()
            + ".incr" + level + comp.ext;
        StringBuilder ex = new StringBuilder();
        if (!opts.cache) ex.append(" --exclude=*/cache --exclude=*/code_cache");
        String script = "set -u;"
            + " P='" + pkg + "'; U='" + userId + "';"
            + " D='" + destDir + "'; F='" + file + "'; S='" + snap + "';"
            + " NEW=\"$D/.snap.$$.new\"; DIFF=\"$D/.snap.$$.diff\"; LIST=\"$D/.snap.$$.list\";"
            + " { [ -e \"/data/user/$U/$P\" ] && find \"/data/user/$U/$P\""
            + " -printf \"user/$U/$P/%P|%s|%T@\\n\" 2>/dev/null;"
            + " [ -e \"/data/user_de/$U/$P\" ] && find \"/data/user_de/$U/$P\""
            + " -printf \"user_de/$U/$P/%P|%s|%T@\\n\" 2>/dev/null; }"
            + " | sort > \"$NEW\";"
            + " [ -s \"$NEW\" ] || { echo EMPTY; rm -f \"$NEW\"; exit 3; };"
            + " if [ -f \"$S\" ]; then"
            + " comm -13 \"$S\" \"$NEW\" | cut -d'|' -f1 > \"$LIST\";"
            + " else cp \"$NEW\" \"$LIST\";"
            + " fi;"
            + " N=$(wc -l < \"$LIST\" | tr -d ' ');"
            + " [ \"$N\" -gt 0 ] || { cp \"$NEW\" \"$S\"; rm -f \"$NEW\" \"$LIST\";"
            + " echo UNCHANGED_DIFF:$(wc -l < \"$NEW\" 2>/dev/null | tr -d ' '):$(test -f \"$S\" && echo hasSnap || echo noSnap); exit 7; };"
            + " LIVE=\"$D/.snap.$$.live\";"
            + " while IFS= read -r f; do"
            + " [ -e \"/data/$f\" ] && printf '%s\\n' \"$f\";"
            + " done < \"$LIST\" > \"$LIVE\";"
            + " N=$(wc -l < \"$LIVE\" | tr -d ' ');"
            + " [ \"$N\" -gt 0 ] || { cp \"$NEW\" \"$S\"; rm -f \"$NEW\" \"$LIST\" \"$LIVE\";"
            + " echo UNCHANGED_LIVE; exit 7; };"
            + " ERR=\"$D/.snap.$$.err\";"
            + " tar -cf -" + tarOpts() + " -C /data" + ex + " -T \"$LIVE\" 2>\"$ERR\""
            + " | " + comp.compCmd + " > \"$F\""
            + " || { echo -n TAR_FAIL:; head -c 300 \"$ERR\" 2>/dev/null;"
            + " rm -f \"$F\" \"$NEW\" \"$LIST\" \"$LIVE\" \"$ERR\"; exit 4; };"
            + " rm -f \"$ERR\";"
            + " [ -s \"$F\" ] || { echo EMPTY_FILE; rm -f \"$F\" \"$NEW\" \"$LIST\" \"$LIVE\"; exit 5; };"
            + " " + comp.decompCmd + " \"$F\" 2>/dev/null | tar -tf - >/dev/null"
            + " || { echo TAR_VERIFY_FAIL; rm -f \"$F\" \"$NEW\" \"$LIST\" \"$LIVE\"; exit 6; };"
            + " DEL=$(comm -23 \"$S\" \"$NEW\" 2>/dev/null | wc -l | tr -d ' ');"
            + " cp \"$NEW\" \"$S\"; rm -f \"$NEW\" \"$LIST\" \"$LIVE\";"
            + " echo \"INCR_OK:$N:$DEL\"";
        ExecResult r = execRootGlobal(new String[]{"sh", "-c", script}, FULL_TIMEOUT_MS);
        if (!r.ok || !r.out.contains("INCR_OK:")) {
            if (r.out.contains("UNCHANGED")) throw new Exception("unchanged");
            throw new Exception(r.out.trim());
        }
        String[] seg = r.out.trim().split("INCR_OK:")[1].split(":");
        String filesN = seg.length > 0 ? seg[0].trim() : "?";
        String delN = seg.length > 1 ? seg[1].trim() : "0";
        FullResult res = new FullResult();
        res.archive = file;
        res.comp = comp.name;
        res.level = level;
        res.parts.add(new PartResult("data-incr", true,
            comp.name + " " + filesN + " files"
            + ("0".equals(delN) ? "" : ", " + delN + " deleted")));
        Meta meta = new Meta();
        meta.comp = comp.name;
        meta.ver = ver != null ? ver : "";
        meta.vcode = vcode != null ? vcode : "";
        meta.label = label != null ? label : "";
        meta.parts = "data";
        writeMeta(file, meta);
        writeShaSidecar(file);
        invalidatePackages(userId);
        return res;
    }

    /** Encrypted incremental (same diff, AES-GCM streaming output). */
    public static FullResult backupIncrEnc(String pkg, int userId, BackupJob opts,
            java.io.File destFile, char[] pw, String ver, String vcode,
            String label, int level) throws Exception {
        guardBackupArgs(pkg, userId);
        if (opts == null || !opts.data) throw new Exception("data part required");
        CryptoVault.checkPassword(pw);
        Comp comp = detectComp();
        StringBuilder ex = new StringBuilder();
        if (!opts.cache) ex.append(" --exclude=*/cache --exclude=*/code_cache");
        String snap = snapPath(destFile.getParent(), pkg, userId);
        if (level <= 0) level = 1;
        final int lvl = level;
        String script = "set -u;" + PIPESTRICT
            + " P='" + pkg + "'; U='" + userId + "';"
            + " D='" + destFile.getParent() + "'; S='" + snap + "';"
            + " NEW=\"$D/.snap.$$.new\"; DIFF=\"$D/.snap.$$.diff\"; LIST=\"$D/.snap.$$.list\";"
            + " { [ -e \"/data/user/$U/$P\" ] && find \"/data/user/$U/$P\""
            + " -printf \"user/$U/$P/%P|%s|%T@\\n\" 2>/dev/null;"
            + " [ -e \"/data/user_de/$U/$P\" ] && find \"/data/user_de/$U/$P\""
            + " -printf \"user_de/$U/$P/%P|%s|%T@\\n\" 2>/dev/null; }"
            + " | sort > \"$NEW\";"
            + " [ -s \"$NEW\" ] || { echo EMPTY; rm -f \"$NEW\"; exit 3; };"
            + " if [ -f \"$S\" ]; then"
            + " comm -13 \"$S\" \"$NEW\" | cut -d'|' -f1 > \"$LIST\";"
            + " else cp \"$NEW\" \"$LIST\";"
            + " fi;"
            + " N=$(wc -l < \"$LIST\" | tr -d ' ');"
            + " [ \"$N\" -gt 0 ] || { cp \"$NEW\" \"$S\"; rm -f \"$NEW\" \"$LIST\";"
            + " echo UNCHANGED_DIFF:$(wc -l < \"$NEW\" 2>/dev/null | tr -d ' '):$(test -f \"$S\" && echo hasSnap || echo noSnap); exit 7; };"
            + " LIVE=\"$D/.snap.$$.live\";"
            + " while IFS= read -r f; do"
            + " [ -e \"/data/$f\" ] && printf '%s\\n' \"$f\";"
            + " done < \"$LIST\" > \"$LIVE\";"
            + " N=$(wc -l < \"$LIVE\" | tr -d ' ');"
            + " [ \"$N\" -gt 0 ] || { cp \"$NEW\" \"$S\"; rm -f \"$NEW\" \"$LIST\" \"$LIVE\";"
            + " echo UNCHANGED_LIVE; exit 7; };"
            + " tar -cf -" + tarOpts() + " -C /data" + ex + " -T \"$LIVE\" 2>/dev/null"
            + " | " + comp.compCmd + "; EC=$?;"
            + " DEL=$(comm -23 \"$S\" \"$NEW\" 2>/dev/null | wc -l | tr -d ' ');"
            + " cp \"$NEW\" \"$S\"; rm -f \"$NEW\" \"$LIST\" \"$LIVE\";"
            + " [ $EC -eq 0 ] || { echo TAR_FAIL; exit 4; };"
            + " echo \"INCR_OK:$N:$DEL\" >&2";
        // NOTE: INCR_OK goes to stderr (stdout is the tar stream).
        java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile);
        final Exception[] encErr = {null};
        final String[] trailer = {""};
        final java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
        final java.io.PipedInputStream pis =
            new java.io.PipedInputStream(pos, PIPE_BUF);
        Thread encThread = new Thread(() -> {
            try {
                CryptoVault.encryptStream(pis, fos, pw);
            } catch (Throwable t) {
                encErr[0] = t instanceof Exception ? (Exception) t
                    : new Exception(t);
            } finally {
                try { fos.flush(); } catch (Throwable ignore) { }
            }
        });
        encThread.setDaemon(true);
        encThread.start();
        ExecResult r = execGlobalStreaming(new String[]{"sh", "-c", script},
            pos, FULL_TIMEOUT_MS);
        try { pos.close(); } catch (Throwable ignore) { }
        try { encThread.join(60000); } catch (Throwable ignore) { }
        try { fos.getFD().sync(); } catch (Throwable ignore) { }
        try { fos.close(); } catch (Throwable ignore) { }
        trailer[0] = r.out;
        boolean ok = r.ok && encErr[0] == null && destFile.length() > 0
            && trailer[0].contains("INCR_OK:");
        if (!ok) {
            try { destFile.delete(); } catch (Throwable ignore) { }
            if (trailer[0].contains("UNCHANGED")) throw new Exception("unchanged");
            String why = encErr[0] != null ? String.valueOf(encErr[0].getMessage())
                : trailer[0];
            throw new Exception(why == null || why.isEmpty() ? "empty" : why.trim());
        }
        String[] seg = trailer[0].split("INCR_OK:")[1].split(":");
        FullResult res = new FullResult();
        res.archive = destFile.getAbsolutePath();
        res.comp = comp.name;
        res.enc = true;
        res.level = lvl;
        res.parts.add(new PartResult("data-incr", true,
            comp.name + "+aes256gcm " + seg[0].trim() + " files"));
        Meta meta = new Meta();
        meta.comp = comp.name;
        meta.enc = "aes256gcm";
        meta.ver = ver != null ? ver : "";
        meta.vcode = vcode != null ? vcode : "";
        meta.label = label != null ? label : "";
        meta.parts = "data";
        StringBuilder sb = new StringBuilder("# clonepilot-meta v2\n");
        sb.append("comp=").append(meta.comp).append('\n');
        sb.append("enc=").append(meta.enc).append('\n');
        sb.append("ver=").append(meta.ver).append('\n');
        sb.append("vcode=").append(meta.vcode).append('\n');
        sb.append("label=").append(meta.label).append('\n');
        sb.append("parts=").append(meta.parts).append('\n');
        sb.append("level=").append(lvl).append('\n');
        java.io.File mf = new java.io.File(metaPathFor(res.archive));
        java.io.FileOutputStream mfos = new java.io.FileOutputStream(mf);
        try {
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(
                sb.toString().getBytes("UTF-8"));
            CryptoVault.encryptStream(bis, mfos, pw);
        } finally {
            try { mfos.close(); } catch (Throwable ignore) { }
        }
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(destFile);
            String h;
            try { h = CryptoVault.sha256Hex(fis); }
            finally { try { fis.close(); } catch (Throwable ignore) { } }
            java.io.FileOutputStream sos =
                new java.io.FileOutputStream(shaPathFor(res.archive));
            try { sos.write((h + "\n").getBytes("UTF-8")); }
            finally { try { sos.close(); } catch (Throwable ignore) { } }
        } catch (Throwable ignore) { }
        invalidatePackages(userId);
        return res;
    }

    /**
     * Data-only restore of ONE incremental archive (applied in chain order
     * by the caller). Encrypted incrs decrypt via session password.
     */
    public static void restoreIncrData(String pkg, int userId, String archive,
            char[] pw) throws Exception {
        guardBackupArgs(pkg, userId);
        guardBackupPath(archive);
        boolean enc = archive.endsWith(".enc");
        if (enc) CryptoVault.checkPassword(pw);
        Comp comp = detectComp();
        if (enc) {
            java.io.FileInputStream fis = new java.io.FileInputStream(archive);
            final java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
            final java.io.PipedInputStream pis =
                new java.io.PipedInputStream(pos, PIPE_BUF);
            final Exception[] decErr = {null};
            Thread decThread = new Thread(() -> {
                try {
                    CryptoVault.decryptStream(fis, pos, pw);
                } catch (Throwable t) {
                    decErr[0] = t instanceof Exception ? (Exception) t
                        : new Exception(t);
                } finally {
                    try { pos.close(); } catch (Throwable ignore) { }
                    try { fis.close(); } catch (Throwable ignore) { }
                }
            });
            decThread.setDaemon(true);
            decThread.start();
            String script = "set -u; P='" + pkg + "'; U='" + userId + "';"
                + " am force-stop --user \"$U\" \"$P\" 2>/dev/null;"
                + " " + comp.decompCmd + " 2>/dev/null | tar -xpf - -C /data"
                + " || exit 3;"
                + " if command -v restorecon >/dev/null 2>&1; then"
                + "   restorecon -R \"/data/user/$U/$P\" 2>/dev/null;"
                + "   [ -e \"/data/user_de/$U/$P\" ] && restorecon -R \"/data/user_de/$U/$P\" 2>/dev/null;"
                + " fi; echo INCR_OK";
            ExecResult res = execGlobalWithStdin(new String[]{"sh", "-c", script},
                pis, FULL_TIMEOUT_MS);
            try { decThread.join(60000); } catch (Throwable ignore) { }
            if (decErr[0] != null) throw decErr[0];
            if (!res.ok || !res.out.contains("INCR_OK")) {
                throw new Exception(res.out.trim());
            }
        } else {
            String decomp = decompFor(archive);
            String script = "set -u;"
                + " P='" + pkg + "'; U='" + userId + "'; F='" + archive + "';"
                + " [ -f \"$F\" ] || { echo NO_FILE; exit 2; };"
                + " am force-stop --user \"$U\" \"$P\" 2>/dev/null;"
                + " " + decomp + " \"$F\" 2>/dev/null | tar -xpf - -C /data"
                + " || { echo TAR_FAIL; exit 3; };"
                + " if command -v restorecon >/dev/null 2>&1; then"
                + "   restorecon -R \"/data/user/$U/$P\" 2>/dev/null;"
                + "   [ -e \"/data/user_de/$U/$P\" ] && restorecon -R \"/data/user_de/$U/$P\" 2>/dev/null;"
                + " fi; echo INCR_OK";
            ExecResult res = execRootGlobal(new String[]{"sh", "-c", script},
                FULL_TIMEOUT_MS);
            if (!res.ok || !res.out.contains("INCR_OK")) {
                throw new Exception(res.out.trim());
            }
        }
        invalidatePackages(userId);
        invalidateSuspended(pkg, userId);
        invalidateResolve(pkg, userId);
    }

    // ---------------- system-wide specials (SMS / call-log / Wi-Fi) ----------------

    public static final String[] SPECIAL_KINDS =
        {"sms", "calllog", "wifi", "wallpaper"};

    private static String specialPaths(String kind) {
        if ("sms".equals(kind)) {
            return "user_de/0/com.android.providers.telephony"
                + " user/0/com.android.providers.telephony";
        }
        if ("calllog".equals(kind)) {
            return "user/0/com.android.providers.contacts"
                + " user_de/0/com.android.providers.contacts";
        }
        if ("wifi".equals(kind)) {
            return "misc/apexdata/com.android.wifi/WifiConfigStore.xml"
                + " misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml"
                + " misc/apexdata/com.android.wifi/WifiConfigStoreNetworkSuggestions.xml"
                + " misc/wifi/WifiConfigStore.xml"
                + " misc/wifi/WifiConfigStoreSoftAp.xml";
        }
        if ("wallpaper".equals(kind)) {
            // System wallpaper + lock screen (system users 0..1) +
            // wallpaper service metadata.
            return "user/0/com.android.systemui/files/wallpaper"
                + " user/0/com.android.systemui/files/wallpaper_info.xml"
                + " misc/wallpaper"
                + " system/users/0/wallpaper_info.xml"
                + " system/users/10/wallpaper_info.xml";
        }
        return "";
    }

    /** Backup one special archive into destDir. Returns archive path. */
    public static String backupSpecial(String kind, String destDir) throws Exception {
        String rel = specialPaths(kind);
        if (rel.isEmpty()) throw new Exception("unknown special");
        Comp comp = detectComp();
        String file = destDir + "/special_" + kind + "_" + backupStamp() + comp.ext;
        StringBuilder guard = new StringBuilder();
        for (String p : rel.split(" ")) {
            guard.append(" [ -e \"/data/").append(p).append("\" ]"
                + " && ARGS=\"$ARGS ").append(p).append("\";");
        }
        String script = "set -u;" + PIPESTRICT + " F='" + file + "'; ARGS='';" + guard
            + " [ -z \"$ARGS\" ] && { echo EMPTY_SPECIAL; exit 3; };"
            + " ERR=\"${F}.tarerr\";"
            + " tar -cf -" + tarOpts() + " -C /data $ARGS 2>\"$ERR\""
            + " | " + comp.compCmd + " > \"$F\""
            + " || { echo -n TAR_FAIL:; head -c 300 \"$ERR\" 2>/dev/null;"
            + " rm -f \"$F\" \"$ERR\"; exit 4; };"
            + " rm -f \"$ERR\";"
            + " [ -s \"$F\" ] || { echo EMPTY_FILE; rm -f \"$F\"; exit 5; }; echo SPECIAL_OK";
        ExecResult r = execRootGlobal(new String[]{"sh", "-c", script}, FULL_TIMEOUT_MS);
        if (!r.ok || !r.out.contains("SPECIAL_OK")) throw new Exception(r.out.trim());
        return file;
    }

    /** Restore one special archive. Returns true when a reboot is advised. */
    public static boolean restoreSpecial(String kind, String archive) throws Exception {
        guardBackupPath(archive);
        if (specialPaths(kind).isEmpty()) throw new Exception("unknown special");
        String decomp = decompFor(archive);
        String post = "";
        if ("sms".equals(kind)) {
            post = " am force-stop --user 0 com.android.providers.telephony 2>/dev/null;";
        } else if ("calllog".equals(kind)) {
            post = " am force-stop --user 0 com.android.providers.contacts 2>/dev/null;";
        } else if ("wifi".equals(kind)) {
            post = " svc wifi disable 2>/dev/null; sleep 1; svc wifi enable 2>/dev/null;";
        } else if ("wallpaper".equals(kind)) {
            // Restart SystemUI so it re-reads the wallpaper files.
            post = " pkill -f com.android.systemui 2>/dev/null;";
        }
        String script = "set -u; F='" + archive + "';"
            + " [ -f \"$F\" ] || { echo NO_FILE; exit 2; };"
            + " " + decomp + " \"$F\" 2>/dev/null | tar -xpf - -C /data"
            + " || { echo TAR_FAIL; exit 3; };"
            + " if command -v restorecon >/dev/null 2>&1; then"
            + "   restorecon -R /data/misc/apexdata/com.android.wifi /data/misc/wifi"
            + "     /data/misc/wallpaper"
            + "     /data/user/0/com.android.providers.contacts"
            + "     /data/user_de/0/com.android.providers.telephony"
            + "     /data/user/0/com.android.systemui 2>/dev/null;"
            + " fi;" + post + " echo SPECIAL_OK";
        ExecResult r = execRootGlobal(new String[]{"sh", "-c", script}, FULL_TIMEOUT_MS);
        if (!r.ok || !r.out.contains("SPECIAL_OK")) throw new Exception(r.out.trim());
        return true; // reboot advised for provider/wifi caches (documented in UI)
    }

    /**
     * Bonus copy of the base APK (+splits) next to data archives, for
     * bare-metal restores. Best-effort: returns apk path or null.
     */
    public static String backupApk(String pkg, String destDir) {
        try {
            if (pkg == null || !SAFE_PKG.matcher(pkg).matches() || destDir == null) {
                return null;
            }
            ExecResult paths = su("pm", "path", pkg);
            if (!rOk(paths)) return null;
            List<String> apks = new ArrayList<>();
            for (String line : paths.out.split("\n")) {
                line = line.trim();
                if (line.startsWith("package:")) {
                    String p = line.substring(8).trim();
                    if (!p.isEmpty() && !p.contains("'") && !p.contains(" ")) apks.add(p);
                }
            }
            if (apks.isEmpty()) return null;
            StringBuilder cp = new StringBuilder();
            int i = 0;
            for (String src : apks) {
                String dst = destDir + "/" + pkg + (i == 0 ? "_base.apk"
                    : "_split" + i + ".apk");
                cp.append(" cp '").append(src).append("' '").append(dst).append("';");
                i++;
            }
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                cp + " echo APK_OK"}, 120000);
            if (r.ok && r.out.contains("APK_OK")) {
                return destDir + "/" + pkg + "_base.apk";
            }
        } catch (Throwable ignore) { }
        return null;
    }

    private static boolean rOk(ExecResult r) { return r != null && r.ok; }

    // ---------------- encrypted backups (AES-256-GCM, session password) ----------------
    // Encrypted archives ALWAYS live in app-writable dirs (Java writes the
    // ciphertext): <app-external>/backups. Listing merges both locations.

    public static java.io.File encDir(android.content.Context c) {
        java.io.File d = new java.io.File(c.getExternalFilesDir("backups"), "");
        try { d.mkdirs(); } catch (Throwable ignore) { }
        return d;
    }

    /**
     * Encrypted full backup: root tar|comp stdout -> Java AES-GCM -> file.
     * destFile must be app-writable (use encDir). Meta goes to meta.enc.
     */
    public static FullResult backupFullEnc(String pkg, int userId, BackupJob opts,
            java.io.File destFile, char[] pw, String ver, String vcode, String label)
            throws Exception {
        guardBackupArgs(pkg, userId);
        if (opts == null || !opts.data) throw new Exception("data part required");
        CryptoVault.checkPassword(pw);
        Comp comp = detectComp();
        FullResult res = new FullResult();
        res.archive = destFile.getAbsolutePath();
        res.comp = comp.name;
        res.enc = true;
        StringBuilder ex = new StringBuilder();
        if (!opts.cache) ex.append(" --exclude=*/cache --exclude=*/code_cache");
        String script = "set -u;" + PIPESTRICT + " P='" + pkg + "'; U='" + userId + "';"
            + " ARGS='';"
            + " [ -e \"/data/user/$U/$P\" ] && ARGS=\"$ARGS user/$U/$P\";"
            + " [ -e \"/data/user_de/$U/$P\" ] && ARGS=\"$ARGS user_de/$U/$P\";"
            + " [ -z \"$ARGS\" ] && exit 3;"
            + " tar -cf -" + tarOpts() + " -C /data" + ex + " $ARGS 2>/dev/null | " + comp.compCmd;
        java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile);
        final boolean[] pumpOk = {true};
        // Encrypt via piped streams on a helper thread:
        // root tar|comp stdout -> PipedInputStream -> AES-GCM -> file.
        try {
            final java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
            final java.io.PipedInputStream pis =
                new java.io.PipedInputStream(pos, PIPE_BUF);
            final Exception[] encErr = {null};
            Thread encThread = new Thread(() -> {
                try {
                    CryptoVault.encryptStream(pis, fos, pw);
                } catch (Throwable t) {
                    encErr[0] = t instanceof Exception ? (Exception) t
                        : new Exception(t);
                } finally {
                    try { fos.flush(); } catch (Throwable ignore) { }
                }
            });
            encThread.setDaemon(true);
            encThread.start();
            ExecResult r = execGlobalStreaming(new String[]{"sh", "-c", script},
                pos, FULL_TIMEOUT_MS);
            try { pos.close(); } catch (Throwable ignore) { }
            try { encThread.join(60000); } catch (Throwable ignore) { }
            try { fos.getFD().sync(); } catch (Throwable ignore) { }
            try { fos.close(); } catch (Throwable ignore) { }
            pumpOk[0] = r.ok && encErr[0] == null && destFile.length() > 0;
            if (!pumpOk[0]) {
                try { destFile.delete(); } catch (Throwable ignore) { }
                String why = encErr[0] != null ? String.valueOf(encErr[0].getMessage())
                    : r.out;
                throw new Exception(why == null || why.isEmpty() ? "empty" : why.trim());
            }
            res.parts.add(new PartResult("data", true, comp.name + "+aes256gcm"));
        } catch (Exception e) {
            try { fos.close(); } catch (Throwable ignore) { }
            try { destFile.delete(); } catch (Throwable ignore) { }
            throw e;
        }
        Meta meta = new Meta();
        meta.comp = comp.name;
        meta.enc = "aes256gcm";
        meta.ver = ver != null ? ver : "";
        meta.vcode = vcode != null ? vcode : "";
        meta.label = label != null ? label : "";
        StringBuilder partsSb = new StringBuilder("data");
        if (opts.perms) {
            List<String> granted = grantedPerms(pkg, userId);
            meta.perms.addAll(granted);
            res.parts.add(new PartResult("perms", true, granted.size() + " grants"));
            partsSb.append(";perms");
        }
        if (opts.appops) {
            java.util.Map<String, String> modes = appOpsModes(pkg, userId);
            meta.appops.putAll(modes);
            res.parts.add(new PartResult("appops", true, modes.size() + " modes"));
            partsSb.append(";appops");
        }
        if (opts.ssaid) {
            String line = ssaidEntry(pkg, userId);
            if (line != null) {
                meta.ssaidLine = line;
                partsSb.append(";ssaid");
                res.parts.add(new PartResult("ssaid", true, "reboot to apply"));
            } else {
                res.parts.add(new PartResult("ssaid", false, "unreadable (SELinux?)"));
            }
        }
        meta.parts = partsSb.toString();
        // Encrypted backups ALWAYS encrypt the meta too: a plaintext file at
        // a .meta.enc path breaks restore/verify decryption (fixed bug).
        {
            java.io.File mf = new java.io.File(metaPathFor(res.archive));
            java.io.FileOutputStream mfos = new java.io.FileOutputStream(mf);
            try {
                StringBuilder sb = new StringBuilder("# clonepilot-meta v2\n");
                sb.append("comp=").append(meta.comp).append('\n');
                sb.append("enc=").append(meta.enc).append('\n');
                sb.append("ver=").append(meta.ver).append('\n');
                sb.append("vcode=").append(meta.vcode).append('\n');
                sb.append("label=").append(meta.label).append('\n');
                sb.append("parts=").append(meta.parts).append('\n');
                for (String p : meta.perms) sb.append("perm=").append(p).append('\n');
                for (java.util.Map.Entry<String, String> e : meta.appops.entrySet()) {
                    sb.append("appop=").append(e.getKey()).append(':')
                        .append(e.getValue()).append('\n');
                }
                if (meta.ssaidLine != null) {
                    sb.append("ssaid=").append(meta.ssaidLine).append('\n');
                }
                java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(
                    sb.toString().getBytes("UTF-8"));
                CryptoVault.encryptStream(bis, mfos, pw);
            } finally {
                try { mfos.close(); } catch (Throwable ignore) { }
            }
        }
        // SHA-256 of ciphertext (verifiable without password).
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(destFile);
            String h;
            try { h = CryptoVault.sha256Hex(fis); }
            finally { try { fis.close(); } catch (Throwable ignore) { } }
            java.io.FileOutputStream sos =
                new java.io.FileOutputStream(shaPathFor(res.archive));
            try { sos.write((h + "\n").getBytes("UTF-8")); }
            finally { try { sos.close(); } catch (Throwable ignore) { } }
        } catch (Throwable ignore) { }
        invalidatePackages(userId);
        return res;
    }

    /**
     * Encrypted restore: file -> Java AES-GCM -> root decomp|tar -x stdin.
     * Meta parts (perms/appops/ssaid) applied from the decrypted meta.
     */
    public static RestoreResult restoreFullEnc(String pkg, int userId, String archive,
            BackupJob sel, char[] pw) throws Exception {
        guardBackupArgs(pkg, userId);
        if (sel == null) throw new Exception("no options");
        CryptoVault.checkPassword(pw);
        Meta meta = readMetaDecrypted(archive, pw);
        RestoreResult res = new RestoreResult();
        Comp comp = detectComp();
        if (sel.data) {
            String script = "set -u; P='" + pkg + "'; U='" + userId + "';"
                + " am force-stop --user \"$U\" \"$P\" 2>/dev/null;"
                + " " + comp.decompCmd + " 2>/dev/null | tar -xpf - -C /data"
                + " || exit 3;"
                + " if command -v restorecon >/dev/null 2>&1; then"
                + "   restorecon -R \"/data/user/$U/$P\" 2>/dev/null;"
                + "   [ -e \"/data/user_de/$U/$P\" ] && restorecon -R \"/data/user_de/$U/$P\" 2>/dev/null;"
                + " fi;"
                + " echo DATA_OK";
            java.io.FileInputStream fis = new java.io.FileInputStream(archive);
            final java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
            final java.io.PipedInputStream pis =
                new java.io.PipedInputStream(pos, PIPE_BUF);
            final Exception[] decErr = {null};
            Thread decThread = new Thread(() -> {
                try {
                    CryptoVault.decryptStream(fis, pos, pw);
                } catch (Throwable t) {
                    decErr[0] = t instanceof Exception ? (Exception) t
                        : new Exception(t);
                } finally {
                    try { pos.close(); } catch (Throwable ignore) { }
                    try { fis.close(); } catch (Throwable ignore) { }
                }
            });
            decThread.setDaemon(true);
            decThread.start();
            ExecResult r = execGlobalWithStdin(new String[]{"sh", "-c", script},
                pis, FULL_TIMEOUT_MS);
            try { decThread.join(60000); } catch (Throwable ignore) { }
            boolean ok = r.ok && r.out.contains("DATA_OK") && decErr[0] == null;
            res.parts.add(new PartResult("data", ok,
                ok ? "" : (decErr[0] != null ? String.valueOf(decErr[0].getMessage())
                    : r.out.trim())));
            if (!ok) {
                res.failures.add("data: " + (decErr[0] != null
                    ? decErr[0].getMessage() : r.out.trim()));
            } else if (!sel.privateData) {
                execRootGlobal(new String[]{"sh", "-c",
                    "P='" + pkg + "'; U='" + userId + "';"
                    + " for B in \"/data/user/$U/$P\" \"/data/user_de/$U/$P\"; do"
                    + " [ -d \"$B\" ] || continue;"
                    + " rm -rf \"$B/shared_prefs\" \"$B/databases\" 2>/dev/null;"
                    + " done; echo PRIV_OK"}, 60000);
                res.parts.add(new PartResult("private-skip", true, ""));
            }
        }
        applyMetaParts(pkg, userId, meta, sel, res);
        invalidatePackages(userId);
        invalidateSuspended(pkg, userId);
        invalidateResolve(pkg, userId);
        return res;
    }

    /** Shared meta-parts applier (perms/appops/ssaid) for plain + enc restores. */
    static void applyMetaParts(String pkg, int userId, Meta meta, BackupJob sel,
            RestoreResult res) {
        if (sel.perms && meta.hasPart("perms")) {
            int okN = 0;
            int skipped = 0;
            java.util.Set<String> grantable = dangerousPerms();
            for (String perm : meta.perms) {
                if (!grantable.isEmpty() && !grantable.contains(perm)) {
                    skipped++;
                    continue;
                }
                try {
                    ExecResult r = su("pm", "grant", "--user",
                        String.valueOf(userId), pkg, perm);
                    if (r.ok) okN++;
                    else res.failures.add(perm + ": " + r.out.trim());
                } catch (Throwable t) { res.failures.add(perm + ": " + t.getMessage()); }
            }
            String detail = okN + "/" + meta.perms.size()
                + (skipped > 0 ? " (skipped " + skipped + " install-time)" : "");
            res.parts.add(new PartResult("perms", res.failures.isEmpty(), detail));
        }
        if (sel.appops && meta.hasPart("appops")) {
            int okN = 0;
            for (java.util.Map.Entry<String, String> e : meta.appops.entrySet()) {
                String mode = e.getValue();
                if ("foreground".equals(mode)) mode = "allow";
                if (!mode.equals("allow") && !mode.equals("ignore")
                        && !mode.equals("deny") && !mode.equals("default")) {
                    continue;
                }
                try {
                    ExecResult r = su("cmd", "appops", "set", "--user",
                        String.valueOf(userId), pkg, e.getKey(), mode);
                    if (r.ok) okN++;
                    else res.failures.add(e.getKey() + ": " + r.out.trim());
                } catch (Throwable t) { res.failures.add(e.getKey() + ": " + t.getMessage()); }
            }
            res.parts.add(new PartResult("appops", true, okN + "/" + meta.appops.size()));
        }
        if (sel.ssaid && meta.ssaidLine != null) {
            boolean ok = injectSsaid(pkg, userId, meta.ssaidLine);
            res.parts.add(new PartResult("ssaid", ok,
                ok ? "reboot to apply" : "write denied"));
            if (ok) res.rebootNeeded = true;
            else res.failures.add("ssaid: write denied");
        }
    }

    // ---------------- verify / rename / delete / export / import ----------------

    public static final class VerifyResult {
        public final boolean ok;
        public final String detail;
        VerifyResult(boolean o, String d) { ok = o; detail = d; }
    }

    private static String readSidecarText(String sidecar) {
        // Direct Java read first (encrypted store is app-readable). NOTE:
        // canRead() may lie (DAC allows, SELinux denies open), so its
        // failure must FALL THROUGH to the su path, never abort it.
        try {
            java.io.File f = new java.io.File(sidecar);
            if (f.canRead()) {
                try {
                    byte[] b = new byte[(int) Math.min(f.length(), 256)];
                    java.io.FileInputStream fis = new java.io.FileInputStream(f);
                    try {
                        int n = fis.read(b);
                        if (n > 0) return new String(b, 0, n, "UTF-8").trim()
                            .split("\\s+")[0];
                    } finally {
                        try { fis.close(); } catch (Throwable ignore) { }
                    }
                } catch (Throwable ignore) { /* fall through to su */ }
            }
        } catch (Throwable ignore) { }
        try {
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "cat '" + sidecar + "' 2>/dev/null"}, 30000);
            if (r.ok) return r.out.trim().split("\\s+")[0];
        } catch (Throwable ignore) { }
        return null;
    }

    /**
     * Integrity verification: SHA-256 sidecar match + full tar listing
     * (decrypting first when encrypted — also validates the password).
     */
    public static VerifyResult verifyArchive(String archive, char[] pw) {
        try {
            guardBackupPath(archive);
            boolean enc = archive.endsWith(".enc");
            if (enc) CryptoVault.checkPassword(pw);
            // 1) hash match (Java digest while streaming; no extra pass).
            String expect = readSidecarText(shaPathFor(archive));
            Comp comp = detectComp();
            if (enc) {
                java.io.FileInputStream fis = new java.io.FileInputStream(archive);
                final java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
                final java.io.PipedInputStream pis =
                    new java.io.PipedInputStream(pos, PIPE_BUF);
                final Exception[] decErr = {null};
                Thread decThread = new Thread(() -> {
                    try {
                        CryptoVault.decryptStream(fis, pos, pw);
                    } catch (Throwable t) {
                        decErr[0] = t instanceof Exception ? (Exception) t
                            : new Exception(t);
                    } finally {
                        try { pos.close(); } catch (Throwable ignore) { }
                        try { fis.close(); } catch (Throwable ignore) { }
                    }
                });
                decThread.setDaemon(true);
                decThread.start();
                ExecResult r = execGlobalWithStdin(new String[]{"sh", "-c",
                    comp.decompCmd + " 2>/dev/null | tar -tf - > /dev/null && echo TAR_OK"},
                    pis, FULL_TIMEOUT_MS);
                try { decThread.join(60000); } catch (Throwable ignore) { }
                if (decErr[0] != null) {
                    return new VerifyResult(false, String.valueOf(
                        decErr[0].getMessage()));
                }
                if (!r.ok || !r.out.contains("TAR_OK")) {
                    return new VerifyResult(false, "tar list failed");
                }
                if (expect != null) {
                    String actual = shaFileJava(archive);
                    if (actual != null && !actual.equalsIgnoreCase(expect)) {
                        return new VerifyResult(false, "sha256 mismatch");
                    }
                }
                return new VerifyResult(true, "enc+tar OK");
            }
            // Plain archives.
            if (expect != null) {
                ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                    "sha256sum '" + archive + "' 2>/dev/null | cut -d' ' -f1"},
                    120000);
                String actual = r.ok ? r.out.trim().split("\\s+")[0] : null;
                if (actual == null || !actual.equalsIgnoreCase(expect)) {
                    return new VerifyResult(false, "sha256 mismatch");
                }
            }
            String decomp = decompFor(archive);
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                decomp + " '" + archive + "' 2>/dev/null | tar -tf - > /dev/null"
                + " && echo TAR_OK"}, FULL_TIMEOUT_MS);
            if (r.ok && r.out.contains("TAR_OK")) {
                return new VerifyResult(true,
                    expect != null ? "sha256+tar OK" : "tar OK (no sha)");
            }
            return new VerifyResult(false, "tar list failed");
        } catch (Throwable t) {
            return new VerifyResult(false, String.valueOf(t.getMessage()));
        }
    }

    private static String shaFileJava(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            try { return CryptoVault.sha256Hex(fis); }
            finally { try { fis.close(); } catch (Throwable ignore) { } }
        } catch (Throwable ignore) { return null; }
    }

    /**
     * Rename an archive (keeps extension + sidecars). newBase must be a plain
     * name without slashes/spaces/quotes. Returns the new archive path.
     */
    public static String renameBackup(String archive, String newBase) throws Exception {
        guardBackupPath(archive);
        if (newBase == null || !newBase.matches("[A-Za-z0-9_][A-Za-z0-9_.\\-]*")) {
            throw new Exception("bad name");
        }
        String dir = archive.substring(0, archive.lastIndexOf('/') + 1);
        String oldName = archive.substring(archive.lastIndexOf('/') + 1);
        String encSuffix = oldName.endsWith(".enc") ? ".enc" : "";
        String core = encSuffix.isEmpty() ? oldName
            : oldName.substring(0, oldName.length() - 4);
        String ext = "";
        for (String e : ARCH_EXTS) {
            if (core.endsWith(e)) { ext = e; break; }
        }
        if (ext.isEmpty()) throw new Exception("unknown type");
        String newArch = dir + newBase + ext + encSuffix;
        String[][] pairs = {
            {archive, newArch},
            {metaPathFor(archive), metaPathFor(newArch)},
            {shaPathFor(archive), shaPathFor(newArch)},
        };
        if (isDirectPath(archive)) {
            for (String[] pr : pairs) {
                java.io.File s = new java.io.File(pr[0]);
                if (!s.exists()) continue;
                if (!s.renameTo(new java.io.File(pr[1]))) {
                    throw new Exception("rename failed");
                }
            }
        } else {
            StringBuilder mv = new StringBuilder();
            for (String[] pr : pairs) {
                mv.append(" [ -e '").append(pr[0]).append("' ] && mv '")
                    .append(pr[0]).append("' '").append(pr[1]).append("';");
            }
            mv.append(" echo MV_OK");
            ExecResult r = execRootGlobal(new String[]{"sh", "-c", mv.toString()}, 60000);
            if (!r.ok || !r.out.contains("MV_OK")) throw new Exception(r.out.trim());
            if (!new java.io.File(newArch).exists()) {
                // root-only path: verify via su test instead of Java stat.
                ExecResult t = execRootGlobal(new String[]{"sh", "-c",
                    "[ -f '" + newArch + "' ] && echo EXISTS"}, 30000);
                if (!t.ok || !t.out.contains("EXISTS")) {
                    throw new Exception("rename failed");
                }
            }
        }
        return newArch;
    }

    /** Delete archive + all sidecars (meta/sha/apk-note ignored if absent). */
    public static void deleteArchive(String archive) throws Exception {
        guardBackupPath(archive);
        String meta = metaPathFor(archive);
        String sha = shaPathFor(archive);
        if (isDirectPath(archive)) {
            for (String p : new String[]{archive, meta, sha}) {
                try { new java.io.File(p).delete(); } catch (Throwable ignore) { }
            }
            return;
        }
        ExecResult r = execRootGlobal(new String[]{"sh", "-c",
            "rm -f '" + archive + "' '" + meta + "' '" + sha + "' && echo RM_OK"},
            60000);
        if (!r.ok || !r.out.contains("RM_OK")) throw new Exception(r.out.trim());
    }

    /**
     * Undo-able delete: move archive + sidecars into a .trash subdir next
     * to it (same filesystem = instant). Trash is never listed: listings
     * only glob top-level *.tar.* so .trash/ stays invisible to retention,
     * verify and counts. Returns the trash archive path for untrash.
     */
    public static String trashArchive(String archive) throws Exception {
        guardBackupPath(archive);
        String name = archive.substring(archive.lastIndexOf('/') + 1);
        String parent = archive.substring(0, archive.length() - name.length() - 1);
        String trash = parent + "/.trash";
        String meta = metaPathFor(archive);
        String sha = shaPathFor(archive);
        String verified = meta.replace(".meta", ".verified")
            .replace(".verified.enc", ".verified");
        if (isDirectPath(archive)) {
            try { new java.io.File(trash).mkdirs(); } catch (Throwable ignore) { }
            for (String p : new String[]{archive, meta, sha, verified}) {
                try {
                    java.io.File f = new java.io.File(p);
                    if (f.exists()) {
                        f.renameTo(new java.io.File(trash + "/" + f.getName()));
                    }
                } catch (Throwable ignore) { }
            }
            return trash + "/" + name;
        }
        ExecResult r = execRootGlobal(new String[]{"sh", "-c",
            "mkdir -p '" + trash + "' || exit 2;"
            + " mv '" + archive + "' '" + trash + "/' || exit 3;"
            + " for s in '" + meta + "' '" + sha + "' '" + verified + "'; do"
            + " [ -f \"$s\" ] && mv \"$s\" '" + trash + "/'; done;"
            + " echo TRASH_OK"}, 60000);
        if (!r.ok || !r.out.contains("TRASH_OK")) throw new Exception(r.out.trim());
        return trash + "/" + name;
    }

    /** Restore a trashed archive (from trashArchive) to its parent dir. */
    public static void untrashArchive(String trashPath) throws Exception {
        if (trashPath == null) throw new Exception("no trash path");
        String name = trashPath.substring(trashPath.lastIndexOf('/') + 1);
        String trash = trashPath.substring(0,
            trashPath.length() - name.length() - 1);
        if (!trash.endsWith("/.trash")) {
            throw new SecurityException("not trash: " + trashPath);
        }
        String parent = trash.substring(0, trash.length() - "/.trash".length());
        String dest = parent + "/" + name;
        guardBackupPath(dest);
        if (isDirectPath(dest)) {
            for (String n : sidecarNames(name)) {
                try {
                    java.io.File f = new java.io.File(trash + "/" + n);
                    if (f.exists()) {
                        f.renameTo(new java.io.File(parent + "/" + n));
                    }
                } catch (Throwable ignore) { }
            }
            return;
        }
        StringBuilder mv = new StringBuilder();
        for (String n : sidecarNames(name)) {
            mv.append(" [ -f '").append(trash).append("/").append(n)
                .append("' ] && mv '").append(trash).append("/").append(n)
                .append("' '").append(parent).append("/';");
        }
        ExecResult r = execRootGlobal(new String[]{"sh", "-c",
            mv.toString() + " echo UNTRASH_OK"}, 60000);
        if (!r.ok || !r.out.contains("UNTRASH_OK")) {
            throw new Exception(r.out.trim());
        }
    }

    private static String[] sidecarNames(String archName) {
        String base = archName.endsWith(".enc")
            ? archName.substring(0, archName.length() - 4) : archName;
        for (String e : ARCH_EXTS) {
            if (base.endsWith(e)) {
                base = base.substring(0, base.length() - e.length());
                break;
            }
        }
        boolean enc = archName.endsWith(".enc");
        return new String[]{archName, base + ".meta" + (enc ? ".enc" : ""),
            base + ".sha256",
            base + ".verified"};
    }

    /** Purge trash entries older than keepDays in every known dest. Best-effort. */
    public static void purgeTrash(String destDir, int keepDays) {
        try {
            if (destDir == null) return;
            if (new java.io.File(destDir + "/.trash").exists()
                    || destDir.startsWith("/storage/")) {
                try {
                    java.io.File t = new java.io.File(destDir + "/.trash");
                    java.io.File[] fs = t.listFiles();
                    if (fs != null) {
                        long cutoff = System.currentTimeMillis()
                            - (long) keepDays * 86400000L;
                        for (java.io.File f : fs) {
                            try {
                                if (f.lastModified() < cutoff) f.delete();
                            } catch (Throwable ignore) { }
                        }
                    }
                } catch (Throwable ignore) { }
                return;
            }
            execRootGlobal(new String[]{"sh", "-c",
                "find '" + destDir + "/.trash' -type f -mtime +"
                + Math.max(0, keepDays)
                + " -delete 2>/dev/null; echo PURGE_OK"}, 60000);
        } catch (Throwable ignore) { }
    }

    // ---------------- USB export / import (MTP-visible staging) ----------------

    public static final String EXPORT_DIR = "/sdcard/CLONIX-export";

    /** Copy archives + sidecars to the MTP-visible export dir. Returns count. */
    public static int exportArchives(List<String> archives) {
        int n = 0;
        if (archives == null) return 0;
        for (String a : archives) {
            try {
                guardBackupPath(a);
                final String f = a;
                ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                    "mkdir -p '" + EXPORT_DIR + "' || exit 2;"
                    + " cp '" + f + "' '" + EXPORT_DIR + "/' || exit 3;"
                    + " for s in '" + metaPathFor(f) + "' '" + shaPathFor(f) + "'; do"
                    + " [ -f \"$s\" ] && cp \"$s\" '" + EXPORT_DIR + "/'; done;"
                    + " echo EXP_OK"}, 300000);
                if (r.ok && r.out.contains("EXP_OK")) n++;
            } catch (Throwable ignore) { }
        }
        return n;
    }

    /** Archives staged in the export dir (for import / PC copy). */
    public static List<Backup> listExport() {
        List<Backup> out = new ArrayList<>();
        try {
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "ls -l '" + EXPORT_DIR + "'/*.tar.* 2>/dev/null"}, 30000);
            if (!r.ok) return out;
            for (String line : r.out.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("total")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 7) continue;
                String raw = parts[parts.length - 1];
                String name = raw.substring(raw.lastIndexOf('/') + 1);
                if (!isArchName(name)) continue;
                long size = -1;
                try { size = Long.parseLong(parts[4]); } catch (Throwable ignore) { }
                out.add(new Backup(EXPORT_DIR + "/" + name, size));
            }
            Collections.sort(out, (a, b) -> b.path.compareTo(a.path));
        } catch (Throwable ignore) { }
        return out;
    }

    /** Copy staged archives (+sidecars) into destDir. Returns count. */
    public static int importArchives(List<String> staged, String destDir) {
        int n = 0;
        if (staged == null) return 0;
        try {
            resolveDestDir(destDir);
        } catch (Throwable t) { return 0; }
        for (String a : staged) {
            try {
                if (a == null || !a.startsWith(EXPORT_DIR + "/")) continue;
                String name = a.substring(a.lastIndexOf('/') + 1);
                if (!isArchName(name) || name.contains("'")) continue;
                final String src = a;
                ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                    " cp '" + src + "' '" + destDir + "/' || exit 3;"
                    + " for s in '" + metaPathFor(src) + "' '" + shaPathFor(src) + "'; do"
                    + " [ -f \"$s\" ] && cp \"$s\" '" + destDir + "/'; done;"
                    + " echo IMP_OK"}, 300000);
                if (r.ok && r.out.contains("IMP_OK")) n++;
            } catch (Throwable ignore) { }
        }
        return n;
    }

    // ---------------- retention + invalid cleanup (Neo-style) ----------------

    /**
     * Chain-aware retention: keep the newest `keep` CHAINS matching prefix
     * (a full + its newer incrementals = one chain; specials = solo chains).
     * Deleting an old full also deletes its orphaned incrementals.
     * keep<=0 = unlimited. Returns archives deleted.
     */
    public static int enforceRetention(String destDir, String prefix, int keep) {
        int n = 0;
        try {
            if (keep <= 0 || prefix == null || destDir == null) return 0;
            List<Backup> mine = new ArrayList<>();
            for (Backup b : listFullBackups(destDir, null)) {
                String name = b.path.substring(b.path.lastIndexOf('/') + 1);
                if (name.startsWith(prefix + "_")) mine.add(b);
            }
            // Group: fulls anchor chains; incrs attach to nearest older full.
            List<Backup> fulls = new ArrayList<>();
            List<Backup> incrs = new ArrayList<>();
            for (Backup b : mine) {
                String name = b.path.substring(b.path.lastIndexOf('/') + 1);
                if (name.startsWith("special_") || incrLevelOf(name) == 0) {
                    fulls.add(b);
                } else {
                    incrs.add(b);
                }
            }
            // Newest-first ordering within groups.
            Collections.sort(fulls, (a, b) -> b.path.compareTo(a.path));
            // Chains beyond keep: delete full + its incrs (older than next kept).
            for (int i = keep; i < fulls.size(); i++) {
                Backup cut = fulls.get(i);
                try { deleteArchive(cut.path); n++; }
                catch (Throwable ignore) { }
                String nextKept = (i > 0) ? fulls.get(i - 1).path : null;
                for (Backup in : new ArrayList<>(incrs)) {
                    boolean olderThanCut = in.path.compareTo(cut.path) > 0;
                    boolean newerThanNext = nextKept == null
                        || in.path.compareTo(nextKept) < 0;
                    // Incr belongs to cut's chain iff cut < incr < nextKept.
                    // (Names sort chronologically: base_full < incr < next_full.)
                    if (olderThanCut && (i == 0 || newerThanNext)) {
                        try { deleteArchive(in.path); n++; }
                        catch (Throwable ignore) { }
                        incrs.remove(in);
                    }
                }
            }
            // Orphan incrs older than every full (base deleted manually): drop.
            if (!fulls.isEmpty()) {
                String oldestKept = fulls.get(Math.min(keep, fulls.size()) - 1).path;
                for (Backup in : new ArrayList<>(incrs)) {
                    if (in.path.compareTo(oldestKept) < 0) {
                        try { deleteArchive(in.path); n++; }
                        catch (Throwable ignore) { }
                    }
                }
            }
        } catch (Throwable ignore) { }
        return n;
    }

    public static final class InvalidScan {
        public final List<String> invalid = new ArrayList<>();
        public int checked;
    }

    /**
     * Find damaged archives: 0-byte files or failed `tar -t`
     * (password-gated .enc need the session password; otherwise reported
     * as locked-unchecked, never deleted).
     */
    public static InvalidScan findInvalid(String destDir, char[] pw) {
        InvalidScan scan = new InvalidScan();
        try {
            List<Backup> all = listFullBackups(destDir, null);
            scan.checked = all.size();
            for (Backup b : all) {
                boolean bad = false;
                if (b.size == 0) {
                    bad = true;
                } else if (b.enc) {
                    if (pw != null) {
                        VerifyResult vr = verifyArchive(b.path, pw);
                        bad = !vr.ok;
                    }
                } else {
                    VerifyResult vr = verifyArchive(b.path, null);
                    bad = !vr.ok;
                }
                if (bad) scan.invalid.add(b.path);
            }
        } catch (Throwable ignore) { }
        return scan;
    }

    /** Delete archives + sidecars. Returns number deleted. */
    public static int deleteArchives(List<String> paths) {
        int n = 0;
        if (paths == null) return 0;
        for (String p : paths) {
            try { deleteArchive(p); n++; }
            catch (Throwable ignore) { }
        }
        return n;
    }

    // ---------------- enable / disable (base app) ----------------

    /** pm disable-user / enable for the OWNER user (base app switch). */
    public static void setBaseEnabled(String pkg, boolean enable) throws Exception {
        if (pkg == null || !SAFE_PKG.matcher(pkg).matches()) {
            throw new SecurityException("refused: " + pkg);
        }
        ExecResult r = su("pm", enable ? "enable" : "disable-user",
            "--user", "0", pkg);
        if (!r.ok) throw new Exception(r.out.trim());
    }

    public static boolean isBaseEnabled(android.content.Context c, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager()
                .getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (Throwable t) { return true; }
    }

    // ---------------- move staged backups between destinations ----------------

    /**
     * Move archives + sidecars from one dest dir to another (destination
     * switch with existing backups). Returns moved archive count.
     */
    public static int moveBackups(String fromDir, String toDir) {
        int n = 0;
        try {
            if (fromDir == null || toDir == null || fromDir.equals(toDir)) return 0;
            resolveDestDir(toDir);
            int before = listFullBackups(toDir, null).size();
            execRootGlobal(new String[]{"sh", "-c",
                "for f in '" + fromDir + "'/*.tar.* '" + fromDir + "'/*.meta"
                + " '" + fromDir + "'/*.meta.enc '" + fromDir + "'/*.sha256"
                + " '" + fromDir + "'/*.verified '" + fromDir + "'/*.apk; do"
                + " [ -e \"$f\" ] || continue;"
                + " mv \"$f\" '" + toDir + "/';"
                + " done; echo MV_DONE"}, 600000);
            n = Math.max(0, listFullBackups(toDir, null).size() - before);
        } catch (Throwable ignore) { }
        return n;
    }

    // ---------------- pre-backup checks (space / running / stopped user) ----------------

    public static final class Preflight {
        public boolean userRunning = true;
        public boolean appRunning = false;
        public long estBytes = -1;
        public long freeBytes = -1;
        public boolean destWritable = false;
    }

    /**
     * Pre-backup health: is the clone's user started, is the app running
     * (live data risk), estimated data size, destination space/writability.
     * All best-effort (-1/assume-ok); UI decides block vs warn.
     */
    public static Preflight preflight(String pkg, int userId, String destDir) {
        Preflight p = new Preflight();
        try {
            if (userId == 0) {
                p.userRunning = true;
            } else {
                try {
                    ExecResult r = su("pm", "list", "users");
                    if (r.ok) {
                        boolean seen = false, running = false;
                        java.util.regex.Matcher m = RUNNING_LINE.matcher(r.out);
                        while (m.find()) {
                            try {
                                if (Integer.parseInt(m.group(1)) == userId) {
                                    running = true;
                                }
                            } catch (Throwable ignore) { }
                        }
                        // "seen" = user listed at all (running or not).
                        seen = r.out.contains("{" + userId + ":")
                            || r.out.contains(" " + userId + ":");
                        p.userRunning = running || !seen;
                    }
                } catch (Throwable ignore) { }
            }
        } catch (Throwable ignore) { }
        try {
            if (pkg != null && SAFE_PKG.matcher(pkg).matches()) {
                ExecResult r = su("sh", "-c", "pidof '" + pkg + "' 2>/dev/null");
                p.appRunning = r.ok && !r.out.trim().isEmpty();
            }
        } catch (Throwable ignore) { }
        try {
            if (pkg != null && SAFE_PKG.matcher(pkg).matches() && userId >= 0) {
                ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                    "du -sb '/data/user/" + userId + "/" + pkg + "'"
                    + " '/data/user_de/" + userId + "/" + pkg + "'"
                    + " 2>/dev/null | awk '{s+=$1} END {print s+0}'"},
                    120000);
                if (r.ok) p.estBytes = Long.parseLong(r.out.trim().split("\\s+")[0]);
            }
        } catch (Throwable ignore) { }
        try {
            if (destDir != null) {
                ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                    "mkdir -p '" + destDir + "' 2>/dev/null;"
                    + " touch '" + destDir + "/.cpw' 2>/dev/null &&"
                    + " { rm -f '" + destDir + "/.cpw'; echo W_OK; };"
                    + " df -k '" + destDir + "' 2>/dev/null | tail -1"},
                    60000);
                if (r.ok) {
                    p.destWritable = r.out.contains("W_OK");
                    for (String line : r.out.split("\n")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 4 && parts[0].startsWith("/dev")) {
                            try {
                                p.freeBytes = Long.parseLong(parts[3]) * 1024L;
                            } catch (Throwable ignore) { }
                        }
                    }
                }
            }
        } catch (Throwable ignore) { }
        return p;
    }

    /** Disk usage of a backup dir (bytes, -1 unknown). */
    public static long dirUsage(String dir) {
        try {
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "du -sb '" + dir + "' 2>/dev/null | cut -f1"}, 60000);
            if (r.ok) return Long.parseLong(r.out.trim().split("\\s+")[0]);
        } catch (Throwable ignore) { }
        return -1;
    }

    /** All full + special archives in destDir, newest first (name sort). */
    public static List<Backup> listFullBackups(String destDir, String pkgOrNull) {
        List<Backup> out = new ArrayList<>();
        try {
            if (destDir == null) return out;
            String pat = (pkgOrNull == null) ? "*.tar.*"
                : pkgOrNull.startsWith("special") ? pkgOrNull + "_*.tar.*"
                : pkgOrNull + "_u*.tar.*";
            if (pkgOrNull != null
                    && (pkgOrNull.contains("..") || pkgOrNull.contains("'")
                        || pkgOrNull.contains(" ") || pkgOrNull.startsWith("special"))) {
                // fall through to specials listing below instead
            }
            ExecResult r = execRootGlobal(new String[]{"sh", "-c",
                "ls -l '" + destDir + "'/" + pat + " 2>/dev/null"}, 30000);
            if (r.ok) {
                for (String line : r.out.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("total")) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length < 7) continue;
                    String raw = parts[parts.length - 1];
                    String name = raw.substring(raw.lastIndexOf('/') + 1);
                    if (!isArchName(name)) continue;
                    long size = -1;
                    try { size = Long.parseLong(parts[4]); }
                    catch (Throwable ignore) { }
                    out.add(new Backup(destDir + "/" + name, size));
                }
            }
            if (pkgOrNull == null || pkgOrNull.startsWith("special")) {
                ExecResult r2 = execRootGlobal(new String[]{"sh", "-c",
                    "ls -l '" + destDir + "'/special_*.tar.* 2>/dev/null"}, 30000);
                if (r2.ok) {
                    for (String line : r2.out.split("\n")) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("total")) continue;
                        String[] parts = line.split("\\s+");
                        if (parts.length < 7) continue;
                        String raw = parts[parts.length - 1];
                        String name = raw.substring(raw.lastIndexOf('/') + 1);
                        if (!isArchName(name) || !name.startsWith("special_")) continue;
                        if (pkgOrNull != null && !name.startsWith(pkgOrNull)) continue;
                        long size = -1;
                        try { size = Long.parseLong(parts[4]); }
                        catch (Throwable ignore) { }
                        out.add(new Backup(destDir + "/" + name, size));
                    }
                }
            }
            Collections.sort(out, (a, b) -> b.path.compareTo(a.path));
        } catch (Throwable ignore) { }
        return out;
    }

    /** Unused import guard: keep UserHandle referenced for callers. */
    @SuppressWarnings("unused")
    private static UserHandle unused(UserHandle u) { return u; }
}
