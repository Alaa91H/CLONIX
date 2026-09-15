package com.clonix.app;

import android.app.Activity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Storage preflight warning: estimates total bytes for the pending targets
 * (ShellEngine.preflight du) against free space on the destination volume
 * and shows ONE confirm dialog before starting. Called from the two
 * Preflight flows so every backup/restore path is covered once.
 */
public final class StorageGuard {
    private StorageGuard() {}

    /** 30% headroom over the raw estimate (archive overhead + temp). */
    static final float HEADROOM = 1.3f;

    public interface Go { void onGo(); }

    /**
     * Async: sums per-target estimates, then either proceeds silently or
     * shows a clear warning dialog. Hard blocks (unwritable dest) are
     * already handled by Preflight.blockReason upstream.
     */
    public static void check(final Activity a, java.util.List<Preflight.Target> targets,
            final boolean isRestore, final Go go) {
        new Thread(() -> {
            String dest = ShellEngine.BACKUP_DIR;
            try {
                dest = ShellEngine.resolveDestDir(BackupJob.dest(a));
            } catch (Throwable ignore) { }
            final String destF = dest;
            long need = 0, free = -1;
            try {
                if (isRestore) {
                    // Restores unpack the archive back to /data: budget the
                    // biggest single archive (not the sum — restores are per-app).
                    for (Preflight.Target t : targets) {
                        ShellEngine.Backup b =
                            CloneEngine.newestBackup(a, t.pkg, t.userId);
                        if (b != null && b.size > need) need = b.size;
                    }
                    need = (long) (need * HEADROOM);
                    ShellEngine.Preflight pf =
                        ShellEngine.preflight(targets.isEmpty() ? null
                            : targets.get(0).pkg,
                            targets.isEmpty() ? 0 : targets.get(0).userId, dest);
                    free = pf.freeBytes;
                } else {
                    long est = 0;
                    for (Preflight.Target t : targets) {
                        ShellEngine.Preflight pf =
                            ShellEngine.preflight(t.pkg, t.userId, dest);
                        if (pf.estBytes > 0) est += pf.estBytes;
                        if (pf.freeBytes > free) free = pf.freeBytes;
                    }
                    need = (long) (est * HEADROOM);
                }
            } catch (Throwable ignore) { }
            final long needF = need, freeF = free;
            if (needF <= 0 || freeF < 0 || freeF >= needF) {
                a.runOnUiThread(go::onGo);
                return;
            }
            a.runOnUiThread(() -> new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.storage_warning)
                .setMessage(a.getString(R.string.storage_warning_sum,
                    CloneUsage.formatSize(a, needF),
                    CloneUsage.formatSize(a, freeF),
                    Bidi.isolate(destF)))
                .setPositiveButton(R.string.continue_anyway,
                    (d, w) -> go.onGo())
                .setNegativeButton(R.string.cancel, null)
                .show());
        }).start();
    }
}
