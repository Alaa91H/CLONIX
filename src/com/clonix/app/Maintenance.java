package com.clonix.app;

import android.app.Activity;
import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintenance flows surfaced in UI:
 * - scan for damaged archives (ShellEngine.findInvalid) with review+delete;
 * - apply retention now (ShellEngine.enforceRetention across all apps).
 * Both run on the engine lane, log via EngineLog, report via dialog+Notify.
 */
public final class Maintenance {
    private Maintenance() {}

    /** Scan every archive, then list damaged ones with a delete action. */
    public static void scanInvalid(final Activity a) {
        final Context app = a.getApplicationContext();
        final String title = a.getString(R.string.maint_scan_invalid);
        OpsQueue.post(a, title, ctx -> {
            updateStatus(a, a.getString(R.string.scan_running));
            String dest;
            try {
                dest = ShellEngine.resolveDestDir(BackupJob.dest(a));
            } catch (Throwable t) { dest = ShellEngine.BACKUP_DIR; }
            char[] pw = CryptoVault.hasSessionPassword()
                ? CryptoVault.sessionPassword() : null;
            ShellEngine.InvalidScan scan = ShellEngine.findInvalid(dest, pw);
            final int checked = scan.checked;
            final List<String> bad = new ArrayList<>(scan.invalid);
            EngineLog.i(app, "scan", checked + " checked, " + bad.size() + " bad");
            a.runOnUiThread(() -> {
                setBusy(a, false);
                showScanResult(a, bad, checked);
            });
        });
        setBusy(a, true);
    }

    private static void showScanResult(final Activity a,
            final List<String> bad, final int checked) {
        if (bad.isEmpty()) {
            new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.maint_scan_invalid)
                .setMessage(a.getString(R.string.scan_result_ok, checked))
                .setPositiveButton(R.string.got_it, null)
                .show();
            Notify.ok(a, a.getString(R.string.maint_scan_invalid),
                a.getString(R.string.scan_result_ok, checked));
            return;
        }
        StringBuilder sb = new StringBuilder()
            .append(a.getString(R.string.scan_result_bad, bad.size(), checked))
            .append('\n');
        for (int i = 0; i < bad.size() && i < 6; i++) {
            String n = bad.get(i).substring(bad.get(i).lastIndexOf('/') + 1);
            sb.append("• ").append(Bidi.isolate(n)).append('\n');
        }
        if (bad.size() > 6) sb.append("…\n");
        new MaterialAlertDialogBuilder(a)
            .setTitle(R.string.maint_scan_invalid)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.scan_delete, (d, w) -> {
                final Context app = a.getApplicationContext();
                OpsQueue.post(a, a.getString(R.string.scan_delete), ctx -> {
                    int n = ShellEngine.deleteArchives(bad);
                    EngineLog.i(app, "scan", "deleted " + n);
                    a.runOnUiThread(() -> {
                        setBusy(a, false);
                        String msg = a.getString(R.string.scan_deleted, n);
                        toast(a, msg);
                        Notify.ok(a, a.getString(R.string.maint_scan_invalid), msg);
                        notifyArchivesReload(a);
                    });
                });
                setBusy(a, true);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /** enforceRetention over every tracked package + specials, report count. */
    public static void applyRetentionNow(final Activity a) {
        final Context app = a.getApplicationContext();
        final String title = a.getString(R.string.maint_apply_retention);
        final int keep = Prefs.maxPerApp(a);
        if (keep <= 0) {
            new MaterialAlertDialogBuilder(a)
                .setTitle(title)
                .setMessage(R.string.maint_retention_unlimited)
                .setPositiveButton(R.string.got_it, null)
                .show();
            return;
        }
        OpsQueue.post(a, title, ctx -> {
            String dest;
            try {
                dest = ShellEngine.resolveDestDir(BackupJob.dest(a));
            } catch (Throwable t) { dest = ShellEngine.BACKUP_DIR; }
            // Collect distinct prefixes (pkg_u<uid> + special_) from the dir.
            java.util.Set<String> prefixes = new java.util.HashSet<>();
            try {
                for (ShellEngine.Backup b : ShellEngine.listFullBackups(dest, null)) {
                    String n = b.path.substring(b.path.lastIndexOf('/') + 1);
                    int u = n.indexOf("_u");
                    if (n.startsWith("special_")) {
                        int sp = n.indexOf('_', "special_".length());
                        prefixes.add(sp > 0 ? n.substring(0, sp) : n);
                    } else if (u > 0) {
                        prefixes.add(n.substring(0, u));
                    }
                }
            } catch (Throwable ignore) { }
            int removed = 0;
            for (String pfx : prefixes) {
                try {
                    removed += ShellEngine.enforceRetention(dest, pfx, keep);
                } catch (Throwable ignore) { }
            }
            final int removedF = removed;
            EngineLog.i(app, "retention", removedF + " removed, keep=" + keep);
            a.runOnUiThread(() -> {
                setBusy(a, false);
                String msg = a.getString(R.string.retention_applied, removedF);
                toast(a, msg);
                Notify.ok(a, title, msg);
                notifyArchivesReload(a);
            });
        });
        setBusy(a, true);
    }

    /** Archives screen listens for dataset changes; nudge via status text. */
    private static void notifyArchivesReload(Activity a) {
        try {
            if (a instanceof ArchivesActivity) {
                ((ArchivesActivity) a).reload();
            }
        } catch (Throwable ignore) { }
    }

    private static void setBusy(Activity a, boolean busy) {
        try {
            android.view.View p = a.findViewById(R.id.progress);
            if (p != null) {
                p.setVisibility(busy ? android.view.View.VISIBLE
                    : android.view.View.GONE);
            }
        } catch (Throwable ignore) { }
    }

    private static void updateStatus(Activity a, String s) {
        try {
            android.widget.TextView st =
                (android.widget.TextView) a.findViewById(R.id.status);
            if (st != null) {
                st.setVisibility(android.view.View.VISIBLE);
                st.setText(s);
            }
        } catch (Throwable ignore) { }
    }

    /** Tiny shim so this file needs no android.widget.Toast import noise. */
    private static void toast(final Activity a, final String msg) {
        android.widget.Toast.makeText(a, msg,
            android.widget.Toast.LENGTH_LONG).show();
    }
}
