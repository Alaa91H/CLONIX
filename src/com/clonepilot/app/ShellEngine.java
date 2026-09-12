package com.clonepilot.app;

import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
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
    private static final String TAG = "ClonePilot";

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

    private static ExecResult run(String[] cmd, long timeoutMs) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        String out = readAll(p.getInputStream());
        if (!done) { try { p.destroyForcibly(); } catch (Throwable ignore) { } }
        return new ExecResult(done && p.exitValue() == 0, out);
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (Throwable t) { return ""; }
        finally { try { in.close(); } catch (Throwable ignore) { } }
    }

    /** Run a shell command as root: su -c "<joined>". */
    public static ExecResult execRoot(String[] shellCmd, long timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String s : shellCmd) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('\'').append(s.replace("'", "'\\''")).append('\'');
        }
        return run(new String[]{"su", "-c", sb.toString()}, timeoutMs);
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

    /** pm list users -> [(id,name)]. Throws on failure. */
    public static List<ShellUser> listUsers() throws Exception {
        ExecResult r = su("pm", "list", "users");
        if (!r.ok) throw new Exception("pm list users failed: " + r.out);
        List<ShellUser> out = new ArrayList<>();
        Matcher m = USER_LINE.matcher(r.out);
        while (m.find()) out.add(new ShellUser(Integer.parseInt(m.group(1)), m.group(2)));
        return out;
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
        return parseCreatedId(r.out);
    }

    public static int createCloneProfile(String name) throws Exception {
        ExecResult r = su("pm", "create-user", "--user-type",
                "android.os.usertype.profile.CLONE", "--profileOf", "0", name);
        if (!r.ok) throw new Exception(r.out);
        return parseCreatedId(r.out);
    }

    public static int createManagedProfile(String name) throws Exception {
        ExecResult r = su("pm", "create-user", "--profileOf", "0", "--managed", name);
        if (!r.ok) throw new Exception(r.out);
        return parseCreatedId(r.out);
    }

    public static void removeUser(int userId) throws Exception {
        ExecResult r = su("pm", "remove-user", String.valueOf(userId));
        if (!r.ok && !r.out.contains("removed user")) throw new Exception(r.out);
    }

    public static void installExisting(String pkg, int userId) throws Exception {
        ExecResult r = su("pm", "install-existing", "--user", String.valueOf(userId), pkg);
        if (!r.ok || !r.out.contains("installed for user")) throw new Exception(r.out);
    }

    public static void uninstall(String pkg, int userId) throws Exception {
        ExecResult r = su("pm", "uninstall", "--user", String.valueOf(userId), pkg);
        if (!r.ok) throw new Exception(r.out);
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
    }

    /** Resolve MAIN/LAUNCHER component for pkg in user; null if none. */
    public static String resolveLauncher(String pkg, int userId) {
        try {
            ExecResult r = su("cmd", "package", "resolve-activity",
                    "--user", String.valueOf(userId), "--brief",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER", pkg);
            if (!r.ok) return null;
            for (String line : r.out.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("priority=")
                        || line.startsWith("No activity")) continue;
                if (line.contains("/")) return line; // pkg/.Activity
            }
            return null;
        } catch (Throwable t) { return null; }
    }

    public static void startActivityAsUser(String component, int userId) throws Exception {
        ExecResult r = su("am", "start", "--user", String.valueOf(userId), "-n", component);
        // NOTE: `am start` prints "Starting:" even on its way to an error line,
        // so success requires the absence of "Error".
        if ((!r.ok && !r.out.contains("Starting:")) || r.out.contains("Error")) {
            throw new Exception(r.out);
        }
    }

    private static final Pattern RUNNING_LINE =
            Pattern.compile("UserInfo\\{(\\d+):[^}]*\\}\\s+running");

    /** Wait until `pm list users` shows the user as running (profile unlocked). */
    public static boolean waitForUserRunning(int userId, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                ExecResult r = su("pm", "list", "users");
                if (r.ok) {
                    Matcher m = RUNNING_LINE.matcher(r.out);
                    while (m.find()) {
                        if (Integer.parseInt(m.group(1)) == userId) return true;
                    }
                }
            } catch (Throwable ignore) { }
            try { Thread.sleep(500); } catch (Throwable ignore) { }
        }
        return false;
    }

    /** Split "pkg/.Cls" or "pkg/pkg.Cls" into [pkg, class]. */
    public static String[] splitComponent(String flattened) {
        int slash = flattened.indexOf('/');
        String pkg = flattened.substring(0, slash);
        String cls = flattened.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        return new String[]{pkg, cls};
    }

    /** Best-effort: is `su` grant present (non-blocking short probe)? */
    public static boolean hasRoot() {
        try {
            ExecResult r = execRoot(new String[]{"id"}, 5000);
            return r.ok && r.out.contains("uid=0");
        } catch (Throwable t) { return false; }
    }

    /** Unused import guard: keep UserHandle referenced for callers. */
    @SuppressWarnings("unused")
    private static UserHandle unused(UserHandle u) { return u; }
}
