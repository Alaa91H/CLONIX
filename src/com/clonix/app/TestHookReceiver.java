package com.clonix.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.util.Log;

/**
 * Headless E2E test hook (NO UI taps needed).
 * Trigger: adb shell am broadcast -a com.clonix.app.TEST \
 *   -n com.clonix.app/.TestHookReceiver \
 *   --es op clone --es pkg org.telegram.messenger --es token <token>
 * Token: adb shell run-as com.clonix.app cat files/test_token
 * Ops: clone | launch | delete | status (+ diag). Results go to logcat
 * and to the broadcast resultData.
 *
 * SECURITY: token from app-private file (shell via run-as) is decisive;
 * sender-uid attribution is a secondary check. Safe to ship.
 */
public class TestHookReceiver extends BroadcastReceiver {
    private static final String TAG = "Clonix";
    public static final String ACTION = "com.clonix.app.TEST";

    /**
     * True sender uid. Binder.getCallingUid() inside onReceive returns
     * system_server, NOT the sender — must use getSendingUid() (API 34+)
     * via reflection to stay compatible with minSdk 29.
     */
    private int sendingUid() {
        try {
            java.lang.reflect.Method m = getClass().getMethod("getSendingUid");
            Object r = m.invoke(this);
            return (Integer) r;
        } catch (Throwable t) {
            return Binder.getCallingUid();
        }
    }

    @Override public void onReceive(Context c, Intent i) {
        int caller = sendingUid();
        int binder = Binder.getCallingUid();
        int self = -1;
        try {
            self = c.getPackageManager().getPackageUid(c.getPackageName(), 0);
        } catch (Throwable ignore) { }
        // Diag mode: expose attribution values in resultData (harmless).
        if (i != null && i.getBooleanExtra("diag", false)) {
            setResultData("binder=" + binder + ",sending=" + caller + ",self=" + self);
            return;
        }
        if (caller != 0 && caller != 2000 && caller != self) {
            Log.w(TAG, "TEST rejected for uid " + caller);
            setResultData("rejected:uid=" + caller);
            return;
        }
        // Decisive gate: token from app-private file (shell reads via run-as).
        if (i == null || !TestToken.check(c, i.getStringExtra("token"))) {
            Log.w(TAG, "TEST rejected (bad token), attrib uid=" + caller);
            setResultData("rejected:bad-token");
            return;
        }
        Log.i(TAG, "TEST authorized attrib=" + caller);
        if (i == null || !ACTION.equals(i.getAction())) return;
        // Heavy su/pm work runs off-main-thread via goAsync (no ANR, result kept).
        final BroadcastReceiver.PendingResult pr = goAsync();
        final Context app = c.getApplicationContext();
        final Intent intent = i;
        new Thread(() -> {
            String res;
            try {
                res = runOp(app, intent);
            } catch (Throwable t) {
                res = "error:" + t;
                Log.e(TAG, "TEST failed", t);
            }
            Log.i(TAG, "TEST done -> " + res);
            pr.setResultData(res);
            pr.finish();
        }).start();
    }

    private String runOp(Context c, Intent i) {
        CloneStore db = new CloneStore(c);
        String op = i.getStringExtra("op");
        String pkg = i.getStringExtra("pkg");
        int userId = i.getIntExtra("userId", -1);
        if ("status".equals(op)) {
            return "engine=" + ShellEngine.mode(c)
                + " clones=" + db.listAll().size();
        } else if ("clone".equals(op) && pkg != null) {
            String nick = i.getStringExtra("nickname");
            boolean sep = i.getBooleanExtra("separate", true);
            int uid = CloneEngine.cloneToNextSlot(c, db, pkg,
                nick == null ? "" : nick, sep);
            return uid >= 0 ? "cloned:" + pkg + ":u" + uid : "clone:FAILED";
        } else if ("launch".equals(op) && pkg != null && userId >= 0) {
            boolean ok = CloneEngine.launchClone(c, pkg, userId);
            return (ok ? "launched:" : "launch FAILED:") + pkg + ":u" + userId;
            } else if ("delete".equals(op) && pkg != null && userId >= 0) {
                CloneEngine.deleteClone(c, db, pkg, userId);
                return "deleted:" + pkg + ":u" + userId;
            } else if ("adopt".equals(op) && pkg != null) {
                int n = 0;
                for (SysApi.User u : SysApi.safeGetUsers(c)) {
                    if (u.id == 0 || !CloneEngine.isOursName(u.name)) continue;
                    boolean tracked = false;
                    for (CloneStore.Clone cl : db.listForPkg(pkg)) {
                        if (cl.userId == u.id) { tracked = true; break; }
                    }
                    if (tracked) continue;
                    boolean installed = false;
                    try {
                        android.content.pm.PackageManager pm = c.getPackageManager();
                        SysApi.getPackageInfoAsUser(pm, pkg, u.id);
                        installed = true;
                    } catch (Throwable ignore) { }
                    if (!installed) continue;
                    CloneStore.Clone cl = new CloneStore.Clone();
                    cl.pkg = pkg; cl.userId = u.id;
                    cl.slotIndex = db.nextSlotIndex(pkg);
                    cl.nickname = "";
                    cl.separateContacts = true;
                    try { db.add(cl); n++; } catch (Throwable ignore) { }
                }
                return "adopted:" + pkg + ":" + n;
            } else if ("clearcache".equals(op) && pkg != null && userId >= 0) {
                boolean ok = CloneUsage.clearCacheAsUser(c, pkg, userId);
                return (ok ? "cache-cleared:" : "cache FAILED:") + pkg + ":u" + userId;
            } else if ("freeze".equals(op) && pkg != null && userId >= 0) {
                try {
                    CloneEngine.freeze(c, pkg, userId);
                    return "frozen:" + pkg + ":u" + userId;
                } catch (Throwable t) {
                    return "freeze FAILED:" + t.getMessage();
                }
            } else if ("unfreeze".equals(op) && pkg != null && userId >= 0) {
                try {
                    CloneEngine.unfreeze(c, pkg, userId);
                    boolean still = CloneEngine.isFrozen(c, pkg, userId);
                    return (still ? "still-frozen-external:" : "unfrozen:")
                        + pkg + ":u" + userId;
                } catch (Throwable t) {
                    return "unfreeze FAILED:" + t.getMessage();
                }
            } else if ("frozen".equals(op) && pkg != null && userId >= 0) {
                return (CloneEngine.isFrozen(c, pkg, userId) ? "frozen:" : "active:")
                    + pkg + ":u" + userId;
            } else if ("cleardata".equals(op) && pkg != null && userId >= 0) {
                boolean ok = CloneUsage.clearDataAsUser(c, pkg, userId);
                return (ok ? "data-cleared:" : "data FAILED:") + pkg + ":u" + userId;
            } else if ("backup".equals(op) && pkg != null && userId >= 0) {
                try {
                    String path = CloneEngine.backupClone(c, pkg, userId);
                    return "backed-up:" + path;
                } catch (Throwable t) {
                    return "backup FAILED:" + t.getMessage();
                }
            } else if ("listbackups".equals(op) && pkg != null) {
                java.util.List<ShellEngine.Backup> all = CloneEngine.listBackups(pkg);
                StringBuilder sb = new StringBuilder("backups:" + all.size());
                for (ShellEngine.Backup bk : all) sb.append(" [").append(bk.path)
                    .append(" ").append(bk.size).append("]");
                return sb.toString();
            } else if ("restore".equals(op) && pkg != null && userId >= 0) {
                try {
                    String path = i.getStringExtra("path");
                    if (path == null) {
                        java.util.List<ShellEngine.Backup> all =
                            CloneEngine.listBackups(pkg);
                        if (all.isEmpty()) return "restore FAILED:no-backups";
                        path = all.get(0).path; // newest
                    }
                    CloneEngine.restoreClone(c, db, pkg, userId, path);
                    return "restored:" + pkg + ":u" + userId + ":" + path;
                } catch (Throwable t) {
                    return "restore FAILED:" + t.getMessage();
                }
            } else if ("nsview".equals(op)) {
                // No-root namespace probe: what does the APP process itself see?
                StringBuilder sb = new StringBuilder("ns:");
                try {
                    String[] l = new java.io.File("/data/user").list();
                    sb.append("user=[");
                    if (l != null) for (String s : l) sb.append(s).append(",");
                    sb.append("] u24exists=")
                        .append(new java.io.File("/data/user/24").exists());
                    sb.append(" u24tg=")
                        .append(new java.io.File(
                            "/data/user/24/org.telegram.messenger").exists());
                } catch (Throwable t) { sb.append("ERR:").append(t.getMessage()); }
                try {
                    ShellEngine.ExecResult r = ShellEngine.execRoot(
                        new String[]{"ls", "/data/user"}, 15000);
                    sb.append(" suroot_ok=").append(r.ok)
                        .append(" out=[").append(r.out.trim().replace("\n", ","))
                        .append("]");
                } catch (Throwable t) { sb.append(" suERR:").append(t.getMessage()); }
                try {
                    ShellEngine.ExecResult r = ShellEngine.execRootGlobal(
                        new String[]{"ls", "/data/user"}, 15000);
                    sb.append(" mm_ok=").append(r.ok)
                        .append(" mmout=[").append(r.out.trim().replace("\n", ","))
                        .append("]");
                } catch (Throwable t) { sb.append(" mmERR:").append(t.getMessage()); }
                try {
                    int uid = i.getIntExtra("userId", 10);
                    String dpkg = i.getStringExtra("pkg");
                    if (dpkg == null) dpkg = "org.telegram.messenger";
                    ShellEngine.ExecResult r = ShellEngine.execRootGlobal(
                        new String[]{"sh", "-c", "ls -d /data/user/" + uid + "/" + dpkg
                            + " 2>&1; echo ---; ls /data/user/" + uid + " 2>&1 | head -3;"
                            + " echo ---; stat -c '%a %u:%g' /data/user/" + uid + " 2>&1"},
                        15000);
                    sb.append(" deep_ok=").append(r.ok).append(" deep=[")
                        .append(r.out.trim().replace("\n", "|")).append("]");
                } catch (Throwable t) { sb.append(" deepERR:").append(t.getMessage()); }
                return sb.toString();
            } else if ("backupfull".equals(op) && pkg != null && userId >= 0) {
                try {
                    BackupJob j = BackupJob.defaults(c);
                    j.data = i.getBooleanExtra("data", true);
                    j.cache = i.getBooleanExtra("cache", false);
                    j.privateData = i.getBooleanExtra("priv", true);
                    j.perms = i.getBooleanExtra("perms", true);
                    j.appops = i.getBooleanExtra("appops", true);
                    j.ssaid = i.getBooleanExtra("ssaid", false);
                    ShellEngine.FullResult r = CloneEngine.backupFull(c, pkg, userId, j);
                    StringBuilder sb = new StringBuilder("full-ok:" + r.archive
                        + " comp=" + r.comp);
                    for (ShellEngine.PartResult p : r.parts) {
                        sb.append(" [").append(p.part).append("=")
                            .append(p.ok ? "ok" : "FAIL:" + p.detail).append("]");
                    }
                    return sb.toString();
                } catch (Throwable t) {
                    return "backupfull FAILED:" + t.getMessage();
                }
            } else if ("restorefull".equals(op) && pkg != null && userId >= 0) {
                try {
                    String path = i.getStringExtra("path");
                    if (path == null) {
                        ShellEngine.Backup nb = CloneEngine.newestBackup(c, pkg, userId);
                        if (nb == null) return "restorefull FAILED:no-backups";
                        path = nb.path;
                    }
                    BackupJob sel = BackupJob.empty();
                    sel.data = i.getBooleanExtra("data", true);
                    sel.privateData = i.getBooleanExtra("priv", true);
                    sel.perms = i.getBooleanExtra("perms", true);
                    sel.appops = i.getBooleanExtra("appops", true);
                    sel.ssaid = i.getBooleanExtra("ssaid", false);
                    ShellEngine.RestoreResult r = CloneEngine.restoreFull(
                        c, db, pkg, userId, path, sel);
                    return "restorefull-ok:" + path + " reboot=" + r.rebootNeeded
                        + " fails=" + r.failures.size();
                } catch (Throwable t) {
                    return "restorefull FAILED:" + t.getMessage();
                }
            } else if ("special".equals(op)) {
                try {
                    String kind = i.getStringExtra("kind");
                    String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
                    if (i.getBooleanExtra("restore", false)) {
                        String path = i.getStringExtra("path");
                        if (path == null) {
                            java.util.List<ShellEngine.Backup> all =
                                ShellEngine.listFullBackups(dest, "special_" + kind);
                            if (all.isEmpty()) return "special FAILED:no-backups";
                            path = all.get(0).path;
                        }
                        boolean reboot = ShellEngine.restoreSpecial(kind, path);
                        return "special-restored:" + path + " reboot=" + reboot;
                    }
                    String p = ShellEngine.backupSpecial(kind, dest);
                    return "special-ok:" + p;
                } catch (Throwable t) {
                    return "special FAILED:" + t.getMessage();
                }
            } else if ("verify".equals(op)) {
                try {
                    String path = i.getStringExtra("path");
                    if (path == null) {
                        ShellEngine.Backup nb = userId >= 0
                            ? CloneEngine.newestBackup(c, pkg, userId) : null;
                        if (nb == null) {
                            java.util.List<ShellEngine.Backup> all =
                                CloneEngine.listFullBackups(c, pkg);
                            if (all.isEmpty()) return "verify FAILED:no-backups";
                            path = all.get(0).path;
                        } else path = nb.path;
                    }
                    char[] pw = null;
                    String pwS = i.getStringExtra("pw");
                    if (pwS != null) pw = pwS.toCharArray();
                    else if (CryptoVault.hasSessionPassword()) {
                        pw = CryptoVault.sessionPassword();
                    }
                    ShellEngine.VerifyResult vr =
                        ShellEngine.verifyArchive(path, pw);
                    return (vr.ok ? "verify-ok:" : "verify-FAIL:")
                        + path + " " + vr.detail;
                } catch (Throwable t) {
                    return "verify FAILED:" + t.getMessage();
                }
            } else if ("encbackup".equals(op) && pkg != null && userId >= 0) {
                try {
                    String pwS = i.getStringExtra("pw");
                    if (pwS == null) return "encbackup FAILED:no-pw";
                    BackupJob j = BackupJob.defaults(c);
                    ShellEngine.FullResult r = CloneEngine.backupFullEnc(
                        c, pkg, userId, j, pwS.toCharArray());
                    return "enc-ok:" + r.archive;
                } catch (Throwable t) {
                    return "encbackup FAILED:" + t.getMessage();
                }
            } else if ("encrestore".equals(op) && pkg != null && userId >= 0) {
                try {
                    String pwS = i.getStringExtra("pw");
                    if (pwS == null) return "encrestore FAILED:no-pw";
                    CryptoVault.setSessionPassword(pwS.toCharArray());
                    BackupJob sel = BackupJob.empty();
                    sel.data = true; sel.perms = true; sel.appops = true;
                    ShellEngine.Backup nb =
                        CloneEngine.newestBackup(c, pkg, userId);
                    if (nb == null) return "encrestore FAILED:no-backups";
                    ShellEngine.RestoreResult r = CloneEngine.restoreFull(
                        c, db, pkg, userId, nb.path, sel);
                    return "encrestored:" + nb.path + " fails=" + r.failures.size();
                } catch (Throwable t) {
                    return "encrestore FAILED:" + t.getMessage();
                }
            } else if ("rename".equals(op)) {
                try {
                    String path = i.getStringExtra("path");
                    String name = i.getStringExtra("name");
                    if (path == null) {
                        java.util.List<ShellEngine.Backup> all =
                            CloneEngine.listFullBackups(c, pkg);
                        if (all.isEmpty()) return "rename FAILED:no-backups";
                        path = all.get(0).path;
                    }
                    if (name == null) name = "renamed_test";
                    String np = ShellEngine.renameBackup(path, name);
                    return "renamed:" + np;
                } catch (Throwable t) {
                    return "rename FAILED:" + t.getMessage();
                }
            } else if ("delarch".equals(op)) {
                try {
                    String path = i.getStringExtra("path");
                    if (path == null) {
                        java.util.List<ShellEngine.Backup> all =
                            CloneEngine.listFullBackups(c, pkg);
                        if (all.isEmpty()) return "delarch FAILED:no-backups";
                        path = all.get(0).path;
                    }
                    ShellEngine.deleteArchive(path);
                    return "deleted-arch:" + path;
                } catch (Throwable t) {
                    return "delarch FAILED:" + t.getMessage();
                }
            } else if ("cleaninvalid".equals(op)) {
                try {
                    String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
                    char[] pw = CryptoVault.hasSessionPassword()
                        ? CryptoVault.sessionPassword() : null;
                    ShellEngine.InvalidScan scan =
                        ShellEngine.findInvalid(dest, pw);
                    int n = ShellEngine.deleteArchives(scan.invalid);
                    return "cleaned:" + n + "/" + scan.invalid.size()
                        + " checked=" + scan.checked;
                } catch (Throwable t) {
                    return "cleaninvalid FAILED:" + t.getMessage();
                }
            } else if ("disable".equals(op) && pkg != null) {
                try {
                    ShellEngine.setBaseEnabled(pkg, false);
                    return "disabled:" + pkg;
                } catch (Throwable t) {
                    return "disable FAILED:" + t.getMessage();
                }
            } else if ("enable".equals(op) && pkg != null) {
                try {
                    ShellEngine.setBaseEnabled(pkg, true);
                    return "enabled:" + pkg;
                } catch (Throwable t) {
                    return "enable FAILED:" + t.getMessage();
                }
            } else if ("retention".equals(op)) {
                try {
                    if (i.hasExtra("max")) {
                        Prefs.setMaxPerApp(c, i.getIntExtra("max", 5));
                    }
                    String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
                    int keep = Prefs.maxPerApp(c);
                    int n = pkg != null
                        ? ShellEngine.enforceRetention(dest, pkg, keep) : 0;
                    return "retention:keep=" + keep + " deleted=" + n;
                } catch (Throwable t) {
                    return "retention FAILED:" + t.getMessage();
                }
            } else if ("preflight".equals(op) && pkg != null && userId >= 0) {
                try {
                    String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
                    ShellEngine.Preflight p =
                        ShellEngine.preflight(pkg, userId, dest);
                    return "preflight:userRunning=" + p.userRunning
                        + " appRunning=" + p.appRunning + " est=" + p.estBytes
                        + " free=" + p.freeBytes + " writable=" + p.destWritable;
                } catch (Throwable t) {
                    return "preflight FAILED:" + t.getMessage();
                }
            } else if ("autobk".equals(op) && pkg != null && userId >= 0) {
                try {
                    BackupJob j = BackupJob.defaults(c);
                    j.incr = true;
                    ShellEngine.FullResult r =
                        CloneEngine.backupAuto(c, pkg, userId, j);
                    return "autobk:level=" + r.level + " arch=" + r.archive;
                } catch (Throwable t) {
                    return "autobk FAILED:" + t.getMessage();
                }
            } else if ("chain".equals(op) && pkg != null && userId >= 0) {
                try {
                    String dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
                    java.util.List<ShellEngine.Backup> ch =
                        ShellEngine.chainFor(dest, pkg, userId);
                    StringBuilder sb = new StringBuilder("chain:" + ch.size());
                    for (ShellEngine.Backup b : ch) {
                        sb.append(" [").append(
                            b.path.substring(b.path.lastIndexOf('/') + 1))
                            .append("]");
                    }
                    return sb.toString();
                } catch (Throwable t) {
                    return "chain FAILED:" + t.getMessage();
                }
            } else if ("restorechain".equals(op) && pkg != null && userId >= 0) {
                try {
                    java.util.List<ShellEngine.Backup> ch =
                        CloneEngine.chainForClone(c, pkg, userId);
                    if (ch.isEmpty()) return "restorechain FAILED:no-chain";
                    BackupJob sel = BackupJob.defaults(c);
                    ShellEngine.RestoreResult r = CloneEngine.restoreChain(
                        c, db, pkg, userId, ch, sel);
                    return "chain-ok:" + ch.size() + " fails=" + r.failures.size();
                } catch (Throwable t) {
                    return "restorechain FAILED:" + t.getMessage();
                }
            } else if ("neoscan".equals(op)) {
                try {
                    String root = i.getStringExtra("root");
                    if (root == null) root = "/sdcard/NeoBackup";
                    java.util.List<MigrationImporter.MigApp> apps =
                        MigrationImporter.scan(root);
                    StringBuilder sb = new StringBuilder(
                        "neoscan:" + apps.size());
                    for (MigrationImporter.MigApp a : apps) {
                        sb.append(" [").append(a.pkg).append(" ")
                            .append(a.type).append(" n=")
                            .append(a.archives.size()).append("]");
                    }
                    return sb.toString();
                } catch (Throwable t) {
                    return "neoscan FAILED:" + t.getMessage();
                }
            } else if ("neoimport".equals(op) && pkg != null && userId >= 0) {
                try {
                    String root = i.getStringExtra("root");
                    if (root == null) root = "/sdcard/NeoBackup";
                    java.util.List<MigrationImporter.MigApp> apps =
                        MigrationImporter.scan(root);
                    MigrationImporter.MigApp hit = null;
                    for (MigrationImporter.MigApp a : apps) {
                        if (a.pkg.equals(pkg)) { hit = a; break; }
                    }
                    if (hit == null) return "neoimport FAILED:not-found";
                    ShellEngine.Backup best = null;
                    for (ShellEngine.Backup b : hit.archives) {
                        if (best == null || b.size > best.size) best = b;
                    }
                    if (best == null) return "neoimport FAILED:no-archive";
                    java.util.List<String> members =
                        MigrationImporter.previewMembers(best.path, 40);
                    MigrationImporter.Plan plan =
                        MigrationImporter.plan(pkg, members);
                    MigrationImporter.importArchive(pkg, userId, best.path, plan);
                    return "neoimport-ok:" + pkg + ":u" + userId + ":"
                        + plan.detail;
                } catch (Throwable t) {
                    return "neoimport FAILED:" + t.getMessage();
                }
            } else if ("verifyall".equals(op)) {
                return VerifyScheduler.runOnce(c);
            } else if ("schednow".equals(op)) {
                return BackupScheduler.runOnce(c);
            } else if ("export".equals(op)) {
                java.util.List<ShellEngine.Backup> all =
                    CloneEngine.listFullBackups(c, pkg);
                java.util.List<String> paths = new java.util.ArrayList<>();
                for (ShellEngine.Backup b : all) paths.add(b.path);
                int n = ShellEngine.exportArchives(paths);
                return "exported:" + n;
            } else if ("importusb".equals(op)) {
                java.util.List<ShellEngine.Backup> staged = ShellEngine.listExport();
                java.util.List<String> paths = new java.util.ArrayList<>();
                for (ShellEngine.Backup b : staged) paths.add(b.path);
                String dest;
                try {
                    dest = ShellEngine.resolveDestDir(BackupJob.dest(c));
                } catch (Throwable t) { dest = ShellEngine.BACKUP_DIR; }
                int n = ShellEngine.importArchives(paths, dest);
                return "imported:" + n;
            } else if ("volumes".equals(op)) {
                StringBuilder sb = new StringBuilder("comp="
                    + ShellEngine.detectComp().name);
                for (ShellEngine.Volume v : CloneEngine.backupVolumes()) {
                    sb.append(" [").append(v.label).append(" ").append(v.path)
                        .append(" free=").append(v.freeBytes).append("]");
                }
                return sb.toString();
            } else if ("sweep".equals(op)) {
                int n = CloneEngine.freezeAllTracked(c, db);
                return "sweep-frozen:" + n;
        } else {
            return "usage: op=clone|launch|delete|status|adopt|clearcache|cleardata|"
                + "freeze|unfreeze|frozen|backup|listbackups|restore|sweep|nsview|"
                + "backupfull|restorefull|special|volumes|verify|encbackup|"
                + "encrestore|schednow|export|importusb|rename|delarch|"
                + "cleaninvalid|disable|enable|retention|autobk|chain|"
                + "verifyall|neoscan|neoimport pkg=... userId=..";
        }
    }
}
