package com.clonix.app;

import android.app.Activity;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-backup checks UI: destination writable + enough space (BLOCK),
 * app running (WARN with force-stop option). Silent auto-fixes
 * (starting a stopped user) already happen inside CloneEngine.
 */
public final class Preflight {
    private Preflight() {}

    public static final class Target {
        public final String pkg;
        public final int userId;
        public final String label;
        public Target(String p, int u, String l) {
            pkg = p; userId = u; label = l != null ? l : p;
        }
    }

    public interface OneCb {
        void onGo(boolean forceStopped);
    }

    /** Single-item flow with dialogs. Cancel = no call. */
    public static void checkOne(final Activity a, final String pkg, final int userId,
            final OneCb cb) {
        Toast.makeText(a, R.string.preflight_check, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String dest;
            try {
                dest = ShellEngine.resolveDestDir(BackupJob.dest(a));
            } catch (Throwable t) { dest = ShellEngine.BACKUP_DIR; }
            final ShellEngine.Preflight p =
                ShellEngine.preflight(pkg, userId, dest);
            a.runOnUiThread(() -> {
                String block = blockReason(a, p);
                if (block != null) {
                    new MaterialAlertDialogBuilder(a)
                        .setTitle(R.string.preflight_blocked)
                        .setMessage(block)
                        .setPositiveButton(R.string.got_it, null)
                        .show();
                    return;
                }
                if (p.appRunning) {
                    final ShellEngine.Preflight pf = p;
                    new MaterialAlertDialogBuilder(a)
                        .setTitle(R.string.preflight_running)
                        .setMessage(a.getString(R.string.preflight_running_sum, pkg))
                        .setPositiveButton(R.string.preflight_continue,
                            (d, w) -> afterRunning(a, pkg, userId, false, cb))
                        .setNeutralButton(R.string.preflight_stop_go, (d, w) -> {
                            new Thread(() -> {
                                try {
                                    ShellEngine.forceStop(pkg, userId);
                                } catch (Throwable ignore) { }
                                a.runOnUiThread(() ->
                                    afterRunning(a, pkg, userId, true, cb));
                            }).start();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                    return;
                }
                afterRunning(a, pkg, userId, false, cb);
            });
        }).start();
    }

    /** Shared tail of checkOne: storage warning, then go. */
    private static void afterRunning(final Activity a, final String pkg,
            final int userId, final boolean forceStopped, final OneCb cb) {
        java.util.List<Target> one = new ArrayList<>();
        one.add(new Target(pkg, userId, pkg));
        StorageGuard.check(a, one, false, () -> cb.onGo(forceStopped));
    }

    /** Batch flow: one summary dialog for all warnings/blocks. */
    public interface BatchCb {
        void onGo(List<Target> go);
    }

    public static void checkBatch(final Activity a, final List<Target> targets,
            final BatchCb cb) {
        if (targets == null || targets.isEmpty()) {
            Toast.makeText(a, R.string.select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(a, R.string.preflight_check, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String dest;
            try {
                dest = ShellEngine.resolveDestDir(BackupJob.dest(a));
            } catch (Throwable t) { dest = ShellEngine.BACKUP_DIR; }
            final List<Target> go = new ArrayList<>();
            final List<String> blocked = new ArrayList<>();
            final List<String> running = new ArrayList<>();
            long totalEst = 0;
            for (Target t : targets) {
                ShellEngine.Preflight p =
                    ShellEngine.preflight(t.pkg, t.userId, dest);
                String block = blockReason(a, p);
                if (block != null) {
                    blocked.add(t.label + ": " + block);
                    continue;
                }
                if (p.appRunning) running.add(t.label);
                if (p.estBytes > 0) totalEst += p.estBytes;
                go.add(t);
            }
            final List<Target> goF = go;
            final List<String> blockedF = blocked;
            final List<String> runningF = running;
            a.runOnUiThread(() -> {
                if (goF.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : blockedF) sb.append("• ").append(s).append("\n");
                    new MaterialAlertDialogBuilder(a)
                        .setTitle(R.string.preflight_blocked)
                        .setMessage(sb.toString())
                        .setPositiveButton(R.string.got_it, null)
                        .show();
                    return;
                }
                if (blockedF.isEmpty() && runningF.isEmpty()) {
                    cb.onGo(goF);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                if (!runningF.isEmpty()) {
                    sb.append(a.getString(R.string.preflight_batch_running,
                        runningF.size())).append("\n");
                }
                if (!blockedF.isEmpty()) {
                    sb.append(a.getString(R.string.preflight_batch_skipped,
                        blockedF.size())).append("\n");
                    for (String s : blockedF) {
                        sb.append("• ").append(s).append("\n");
                    }
                }
                MaterialAlertDialogBuilder bld = new MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.preflight_title)
                    .setMessage(sb.toString())
                    .setPositiveButton(R.string.preflight_continue,
                        (d, w) -> afterBatch(a, goF, cb))
                    .setNegativeButton(R.string.cancel, null);
                if (!runningF.isEmpty()) {
                    bld.setNeutralButton(R.string.preflight_stop_go, (d, w) -> {
                        Toast.makeText(a, R.string.preflight_check,
                            Toast.LENGTH_SHORT).show();
                        new Thread(() -> {
                            for (Target t : goF) {
                                try { ShellEngine.forceStop(t.pkg, t.userId); }
                                catch (Throwable ignore) { }
                            }
                            a.runOnUiThread(() -> afterBatch(a, goF, cb));
                        }).start();
                    });
                }
                bld.show();
            });
        }).start();
    }

    /** Shared tail of checkBatch: storage warning, then go. */
    private static void afterBatch(final Activity a, final List<Target> go,
            final BatchCb cb) {
        StorageGuard.check(a, go, false, () -> cb.onGo(go));
    }

    /** Null = go; message = hard block (space / unwritable). */
    static String blockReason(Activity a, ShellEngine.Preflight p) {
        if (!p.destWritable) return a.getString(R.string.preflight_nowrite);
        if (p.estBytes > 0 && p.freeBytes >= 0
                && (long) (p.estBytes * 1.3) > p.freeBytes) {
            return a.getString(R.string.preflight_nospace,
                CloneUsage.formatSize(a, p.estBytes),
                CloneUsage.formatSize(a, p.freeBytes));
        }
        return null;
    }
}
