package com.clonix.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration import from other backup apps (Neo Backup / Backup).
 *
 * Neo layout (verified from upstream FAQ/TROUBLESHOOTING):
 *   <root>/<pkg>/  +  <root>/<pkg>.properties   (same level, same name)
 *   properties: packageName, packageLabel, versionName, versionCode,
 *   backupDate, hasApk, hasAppData, ... ; data archives live in the folder.
 * layout is not publicly documented: the same tolerant scan applies
 * (package-named folders with archives = GENERIC), user confirms preview.
 *
 * Data archives are NEVER trusted blindly: members are listed first and the
 * extractor maps only recognized layouts (app-relative, data/-prefixed, or
 * u0-pinned via temp-extract + copy). Anything else refuses honestly.
 */
public final class MigrationImporter {
    private MigrationImporter() {}

    public static final String TYPE_NEO = "Neo";
    public static final String TYPE_GENERIC = "Other";

    public static final class MigApp {
        public String pkg;
        public String label = "";
        public String ver = "";
        public String date = "";
        public String type = TYPE_GENERIC;
        public String dir;
        public final List<ShellEngine.Backup> archives = new ArrayList<>();
    }

    public static final class Plan {
        public enum Kind { APP_REL, DATA_STRIP1, PINNED_COPY, UNSUPPORTED }
        public Kind kind = Kind.UNSUPPORTED;
        public String detail = "";
    }

    private static final String[] APP_REL_TOPS = {"shared_prefs", "databases",
        "files", "cache", "code_cache", "app_", "lib", "no_backup"};

    /** Scan a source root (bg thread). Never writes. */
    public static List<MigApp> scan(String root) {
        List<MigApp> out = new ArrayList<>();
        try {
            if (root == null || root.contains("..") || root.contains("'")
                    || root.contains(" ")) {
                return out;
            }
            ShellEngine.ExecResult r = ShellEngine.execRootGlobal(
                new String[]{"sh", "-c",
                    "ls -1 '" + root + "' 2>/dev/null"}, 60000);
            if (!r.ok) return out;
            for (String name : r.out.split("\n")) {
                name = name.trim();
                if (name.isEmpty() || name.startsWith(".")) continue;
                if (name.endsWith(".properties")) continue; // handled with dir
                String dir = root + "/" + name;
                ShellEngine.ExecResult t = ShellEngine.execRootGlobal(
                    new String[]{"sh", "-c",
                        "[ -d '" + dir + "' ] && echo ISDIR"}, 15000);
                if (!t.ok || !t.out.contains("ISDIR")) continue;
                MigApp app = new MigApp();
                app.dir = dir;
                app.pkg = name;
                // Neo sidecar?
                Map<String, String> props = readProps(root + "/" + name + ".properties");
                if (!props.isEmpty()) {
                    app.type = TYPE_NEO;
                    if (props.containsKey("packageName")) {
                        app.pkg = props.get("packageName");
                    }
                    app.label = props.containsKey("packageLabel")
                        ? props.get("packageLabel") : "";
                    app.ver = props.containsKey("versionName")
                        ? props.get("versionName") : "";
                    app.date = props.containsKey("backupDate")
                        ? props.get("backupDate") : "";
                }
                // Data archives inside (tolerant extensions, skip APKs).
                ShellEngine.ExecResult l = ShellEngine.execRootGlobal(
                    new String[]{"sh", "-c",
                        "ls -l '" + dir + "' 2>/dev/null"}, 30000);
                if (l.ok) {
                    for (String line : l.out.split("\n")) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("total")) continue;
                        String[] parts = line.split("\\s+");
                        if (parts.length < 7) continue;
                        String fn = parts[parts.length - 1];
                        String low = fn.toLowerCase();
                        boolean arch = low.endsWith(".tar.gz") || low.endsWith(".tgz")
                            || low.endsWith(".tar.xz") || low.endsWith(".tar.zst")
                            || low.endsWith(".zip");
                        if (!arch || low.contains("apk")) continue;
                        long size = -1;
                        try { size = Long.parseLong(parts[4]); }
                        catch (Throwable ignore) { }
                        if (size == 0) continue;
                        app.archives.add(new ShellEngine.Backup(dir + "/" + fn, size));
                    }
                }
                // Package-looking dir (or Neo) with ≥1 data archive qualifies.
                boolean looksPkg = app.pkg.contains(".");
                if (!app.archives.isEmpty()
                        && (TYPE_NEO.equals(app.type) || looksPkg)) {
                    out.add(app);
                }
            }
            Collections.sort(out, (a, b) -> a.pkg.compareToIgnoreCase(b.pkg));
        } catch (Throwable ignore) { }
        return out;
    }

    private static Map<String, String> readProps(String path) {
        Map<String, String> out = new HashMap<>();
        try {
            ShellEngine.ExecResult r = ShellEngine.execRootGlobal(
                new String[]{"sh", "-c", "cat '" + path + "' 2>/dev/null"}, 30000);
            if (!r.ok) return out;
            for (String line : r.out.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int c = line.indexOf(':');
                if (c <= 0) continue;
                out.put(line.substring(0, c).trim(),
                    line.substring(c + 1).trim());
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /** First members of an archive for the preview dialog. */
    public static List<String> previewMembers(String archive, int max) {
        List<String> out = new ArrayList<>();
        try {
            String cmd;
            if (archive.toLowerCase().endsWith(".zip")) {
                cmd = "unzip -l '" + archive + "' 2>/dev/null";
            } else {
                String decomp = "gzip -d -c";
                if (archive.endsWith(".tar.zst")) decomp = "zstd -d -c";
                else if (archive.endsWith(".tar.xz")) decomp = "xz -d -c";
                cmd = decomp + " '" + archive + "' 2>/dev/null | tar -tf - 2>/dev/null";
            }
            ShellEngine.ExecResult r = ShellEngine.execRootGlobal(
                new String[]{"sh", "-c", cmd}, 120000);
            if (!r.ok) return out;
            for (String line : r.out.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // unzip -l has header/footer lines; keep plausible paths.
                if (line.startsWith("Archive:") || line.startsWith("Length")
                        || line.startsWith("----") || line.endsWith("files")) {
                    continue;
                }
                // unzip -l columns: take last token as path.
                if (cmd.startsWith("unzip")) {
                    String[] p = line.split("\\s+");
                    if (p.length == 0) continue;
                    line = p[p.length - 1];
                }
                out.add(line);
                if (out.size() >= max) break;
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /** Decide the extraction mapping from member prefixes. */
    public static Plan plan(String pkg, List<String> members) {
        Plan plan = new Plan();
        if (members == null || members.isEmpty()) {
            plan.detail = "empty archive";
            return plan;
        }
        String first = members.get(0);
        while (first.startsWith("./")) first = first.substring(2);
        while (first.startsWith("/")) first = first.substring(1);
        String top = first.contains("/") ? first.substring(0, first.indexOf('/'))
            : first;
        for (String t : APP_REL_TOPS) {
            if (top.equals(t) || top.startsWith("app_")) {
                plan.kind = Plan.Kind.APP_REL;
                plan.detail = "app-relative";
                return plan;
            }
        }
        if (top.equals("data")) {
            plan.kind = Plan.Kind.DATA_STRIP1;
            plan.detail = "data/-prefixed";
            return plan;
        }
        for (String m : members) {
            String s = m;
            while (s.startsWith("./")) s = s.substring(2);
            while (s.startsWith("/")) s = s.substring(1);
            if (s.contains("user/0/" + pkg + "/") || s.contains("data/data/" + pkg)
                    || s.equals("data/data/" + pkg)) {
                plan.kind = Plan.Kind.PINNED_COPY;
                plan.detail = "owner-pinned (copied)";
                return plan;
            }
        }
        plan.detail = "unrecognized: " + top;
        return plan;
    }

    /**
     * Execute import into target (owner u0 or clone userId, app must be
     * installed there). Permissions are NOT in Neo/archives: the user
     * grants them on first launch (stated in UI).
     */
    public static void importArchive(String pkg, int userId, String archive,
            Plan plan) throws Exception {
        if (plan == null || plan.kind == Plan.Kind.UNSUPPORTED) {
            throw new Exception("unsupported layout: "
                + (plan == null ? "?" : plan.detail));
        }
        boolean zip = archive.toLowerCase().endsWith(".zip");
        String target = "/data/user/" + userId + "/" + pkg;
        String script;
        if (plan.kind == Plan.Kind.PINNED_COPY) {
            // Temp-extract, then copy the owner-pinned subtree to the target.
            script = "set -u; P='" + pkg + "'; U='" + userId + "'; F='" + archive + "';"
                + " T=\"/data/local/tmp/cp_mig_$$\"; rm -rf \"$T\"; mkdir -p \"$T\" || exit 2;"
                + (zip
                    ? " unzip -q -o \"$F\" -d \"$T\" || { echo UNZIP_FAIL; rm -rf \"$T\"; exit 3; };"
                    : " tar -xpf \"$F\" -C \"$T\" || { echo TAR_FAIL; rm -rf \"$T\"; exit 3; };")
                + " SRC='';"
                + " for c in \"$T/data/user/0/$P\" \"$T/data/data/$P\" \"$T/user/0/$P\"; do"
                + " [ -e \"$c\" ] && SRC=\"$c\" && break; done;"
                + " [ -z \"$SRC\" ] && { echo NO_SRC; rm -rf \"$T\"; exit 4; };"
                + " am force-stop --user \"$U\" \"$P\" 2>/dev/null;"
                + " mkdir -p \"/data/user/$U/$P\" \"/data/user_de/$U/$P\";"
                + " cp -a \"$SRC/.\" \"/data/user/$U/$P/\" || { echo CP_FAIL; rm -rf \"$T\"; exit 5; };"
                + " if command -v restorecon >/dev/null 2>&1; then"
                + " restorecon -R \"/data/user/$U/$P\" 2>/dev/null; fi;"
                + " rm -rf \"$T\"; echo MIG_OK";
        } else if (zip) {
            script = "set -u; P='" + pkg + "'; U='" + userId + "'; F='" + archive + "';"
                + " am force-stop --user \"$U\" \"$P\" 2>/dev/null;"
                + " mkdir -p '" + target + "';"
                + " unzip -q -o \"$F\" -d '" + target + "' || { echo UNZIP_FAIL; exit 3; };"
                + " if command -v restorecon >/dev/null 2>&1; then"
                + " restorecon -R '" + target + "' 2>/dev/null; fi;"
                + " echo MIG_OK";
        } else {
            String decomp = "gzip -d -c";
            if (archive.endsWith(".tar.zst")) decomp = "zstd -d -c";
            else if (archive.endsWith(".tar.xz")) decomp = "xz -d -c";
            String strip = plan.kind == Plan.Kind.DATA_STRIP1
                ? " --strip-components=1" : "";
            script = "set -u; P='" + pkg + "'; U='" + userId + "'; F='" + archive + "';"
                + " am force-stop --user \"$U\" \"$P\" 2>/dev/null;"
                + " mkdir -p '" + target + "';"
                + " " + decomp + " \"$F\" 2>/dev/null | tar -xpf -" + strip
                + " -C '" + target + "' || { echo TAR_FAIL; exit 3; };"
                + " if command -v restorecon >/dev/null 2>&1; then"
                + " restorecon -R '" + target + "' 2>/dev/null; fi;"
                + " echo MIG_OK";
        }
        ShellEngine.ExecResult r = ShellEngine.execRootGlobal(
            new String[]{"sh", "-c", script}, 900000);
        if (!r.ok || !r.out.contains("MIG_OK")) {
            throw new Exception(r.out.trim().isEmpty() ? "import failed"
                : r.out.trim());
        }
        try { ShellEngine.invalidatePackages(userId); } catch (Throwable ignore) { }
    }
}
