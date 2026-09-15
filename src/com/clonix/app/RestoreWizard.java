package com.clonix.app;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified interactive restore (Bareos-style): archive pick -> parts &
 * preview (target, versions, downgrade warning, reboot notes) -> confirm
 * -> execute with progress -> result. Single flow for Archives, Backup
 * Center and detail pages; specials included.
 */
public final class RestoreWizard {
    private RestoreWizard() {}

    /** Full flow: archive picker + parts + confirm + execute. */
    public static void startForClone(final Activity a, final CloneStore db,
            final String pkg, final int userId, final String title,
            final Runnable onDone) {
        new Thread(() -> {
            final List<ShellEngine.Backup> all = new ArrayList<>();
            for (ShellEngine.Backup bk : CloneEngine.listFullBackups(a, pkg)) {
                String n = bk.path.substring(bk.path.lastIndexOf('/') + 1);
                if (n.startsWith(pkg + "_u" + userId + "_")) all.add(bk);
            }
            for (ShellEngine.Backup bk : ShellEngine.listBackups(pkg)) {
                String n = bk.path.substring(bk.path.lastIndexOf('/') + 1);
                if (!n.startsWith(pkg + "_u" + userId + "_")) continue;
                boolean dup = false;
                for (ShellEngine.Backup x : all) {
                    if (x.path.equals(bk.path)) { dup = true; break; }
                }
                if (!dup) all.add(bk);
            }
            a.runOnUiThread(() -> {
                if (all.isEmpty()) {
                    Toast.makeText(a, R.string.no_backups, Toast.LENGTH_LONG).show();
                    return;
                }
                // Chain entry first: full + incrementals as one state.
                final List<ShellEngine.Backup> chain =
                    CloneEngine.chainForClone(a, pkg, userId);
                final boolean hasChain = chain.size() > 1;
                String[] names = new String[all.size() + (hasChain ? 1 : 0)];
                int off = 0;
                if (hasChain) {
                    long total = 0;
                    for (ShellEngine.Backup b : chain) {
                        if (b.size > 0) total += b.size;
                    }
                    names[0] = "★ " + a.getString(R.string.wiz_chain,
                        chain.size() - 1,
                        CloneUsage.formatSize(a, total));
                    off = 1;
                }
                for (int i = 0; i < all.size(); i++) {
                    ShellEngine.Backup bk = all.get(i);
                    String s = bk.size >= 0
                        ? CloneUsage.formatSize(a, bk.size) : "?";
                    String when = ShellEngine.prettyBackupDate(a, bk.name);
                    String lvl = "";
                    try {
                        int lv = ShellEngine.incrLevelOf(bk.name);
                        if (lv > 0) {
                            lvl = " [+" + lv + "]";
                        }
                    } catch (Throwable ignore) { }
                    names[i + off] = (bk.enc ? "🔒 " : "") + when + lvl + "  •  " + s;
                }
                final List<ShellEngine.Backup> allF = all;
                final int offF = off;
                new MaterialAlertDialogBuilder(a)
                    .setTitle(a.getString(R.string.wiz_step1) + ": "
                        + (title != null ? title : pkg))
                    .setItems(names, (d, which) -> {
                        if (hasChain && which == 0) {
                            restoreChainFlow(a, db, pkg, userId, title, chain, onDone);
                            return;
                        }
                        startWithArchive(a, db, pkg, userId, title,
                            allF.get(which - offF).path, onDone);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    /** Parts + preview + confirm + execute for a known archive. */
    public static void startWithArchive(final Activity a, final CloneStore db,
            final String pkg, final int userId, final String title,
            final String archive, final Runnable onDone) {
        if (archive != null && archive.endsWith(".enc")
                && !CryptoVault.hasSessionPassword()) {
            askPassword(a, () ->
                startWithArchive(a, db, pkg, userId, title, archive, onDone));
            return;
        }
        new Thread(() -> {
            final ShellEngine.Meta meta = archive != null && archive.endsWith(".enc")
                ? readEncMetaQuiet(archive) : ShellEngine.readMeta(archive);
            // Current app version for the downgrade warning.
            String curVer = "", curCode = "";
            try {
                android.content.pm.PackageInfo pi = a.getPackageManager()
                    .getPackageInfo(pkg, 0);
                if (pi.versionName != null) curVer = pi.versionName;
                curCode = String.valueOf(pi.getLongVersionCode());
            } catch (Throwable ignore) { }
            final String curVerF = curVer, curCodeF = curCode;
            a.runOnUiThread(() ->
                showParts(a, db, pkg, userId, title, archive, meta,
                    curVerF, curCodeF, onDone));
        }).start();
    }

    private static ShellEngine.Meta readEncMetaQuiet(String archive) {
        try {
            return ShellEngine.readMetaDecrypted(archive,
                CryptoVault.sessionPassword());
        } catch (Throwable t) {
            return new ShellEngine.Meta();
        }
    }

    private static void showParts(final Activity a, final CloneStore db,
            final String pkg, final int userId, final String title,
            final String archive, final ShellEngine.Meta meta,
            final String curVer, final String curCode, final Runnable onDone) {
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_backup_opts, null);
        // Wizard stepper: determinate 2/3 bar atop the parts list.
        try {
            android.widget.LinearLayout inner =
                (android.widget.LinearLayout)
                    ((android.widget.ScrollView) v).getChildAt(0);
            com.google.android.material.progressindicator.LinearProgressIndicator
                stepBar =
                new com.google.android.material.progressindicator
                    .LinearProgressIndicator(a);
            try { stepBar.setProgressCompat(66, false); } catch (Throwable ig) { }
            inner.addView(stepBar, 0, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        } catch (Throwable ignore) { }
        MaterialSwitch d = v.findViewById(R.id.opt_data);
        MaterialSwitch ch = v.findViewById(R.id.opt_cache);
        MaterialSwitch pr = v.findViewById(R.id.opt_priv);
        MaterialSwitch pe = v.findViewById(R.id.opt_perms);
        MaterialSwitch ao = v.findViewById(R.id.opt_appops);
        MaterialSwitch ss = v.findViewById(R.id.opt_ssaid);
        ch.setVisibility(View.GONE);
        BackupJob def = BackupJob.defaults(a);
        d.setChecked(true);
        pr.setChecked(def.privateData);
        boolean hasPerms = meta != null && meta.hasPart("perms");
        boolean hasAppops = meta != null && meta.hasPart("appops");
        boolean hasSsaid = meta != null && meta.ssaidLine != null;
        pe.setChecked(def.perms && hasPerms);
        pe.setEnabled(hasPerms);
        ao.setChecked(def.appops && hasAppops);
        ao.setEnabled(hasAppops);
        ss.setChecked(def.ssaid && hasSsaid);
        ss.setEnabled(hasSsaid);
        StringBuilder preview = new StringBuilder();
        preview.append(a.getString(R.string.wiz_target, pkg, userId)).append("\n");
        if (archive != null) {
            String n = archive.substring(archive.lastIndexOf('/') + 1);
            preview.append(ShellEngine.prettyBackupDate(a, n)).append("\n");
        }
        if (meta != null) {
            if (meta.label != null && !meta.label.isEmpty()) {
                preview.append(meta.label);
                if (meta.ver != null && !meta.ver.isEmpty()) {
                    preview.append(" v").append(meta.ver);
                }
                preview.append("\n");
            }
            // Downgrade warning: current app newer than the backup.
            try {
                if (!curCode.isEmpty() && meta.vcode != null && !meta.vcode.isEmpty()
                        && Long.parseLong(curCode) > Long.parseLong(meta.vcode)) {
                    preview.append(a.getString(R.string.wiz_downgrade,
                        curVer.isEmpty() ? curCode : curVer,
                        meta.ver.isEmpty() ? meta.vcode : meta.ver)).append("\n");
                }
            } catch (Throwable ignore) { }
            if (meta.ssaidLine != null && ss.isEnabled()) {
                preview.append(a.getString(R.string.reboot_needed_sum)).append("\n");
            }
        }
        new MaterialAlertDialogBuilder(a)
            .setTitle(a.getString(R.string.wiz_step2) + ": "
                + (title != null ? title : pkg))
            .setMessage(preview.toString() + "\n"
                + a.getString(R.string.backup_confirm_overwrite))
            .setView(v)
            .setPositiveButton(R.string.restore, (dd, ww) -> {
                BackupJob sel = BackupJob.empty();
                sel.data = d.isChecked();
                sel.privateData = pr.isChecked();
                sel.perms = pe.isChecked();
                sel.appops = ao.isChecked();
                sel.ssaid = ss.isChecked();
                if (!sel.data && !sel.perms && !sel.appops && !sel.ssaid) {
                    Toast.makeText(a, R.string.select_first, Toast.LENGTH_SHORT)
                        .show();
                    return;
                }
                execute(a, db, pkg, userId, archive, sel, onDone);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private static void execute(final Activity a, final CloneStore db,
            final String pkg, final int userId, final String archive,
            final BackupJob sel, final Runnable onDone) {
        // M3: non-trapping progress — user may background it, Notify on done.
        final ProgressTask.Handle prog = ProgressTask.show(a,
            a.getString(R.string.wiz_step3), a.getString(R.string.restoring),
            null);
        new Thread(() -> {
            ShellEngine.RestoreResult r = null;
            Throwable err = null;
            try {
                r = CloneEngine.restoreFull(a, db, pkg, userId, archive, sel);
            } catch (Throwable t) { err = t; }
            final ShellEngine.RestoreResult rf = r;
            final Throwable errF = err;
            a.runOnUiThread(() -> {
                try { prog.dismiss(); } catch (Throwable ignore) { }
                if (errF != null) {
                    Toast.makeText(a, a.getString(R.string.restore_failed) + ": "
                        + errF.getMessage(), Toast.LENGTH_LONG).show();
                    Notify.fail(a, a.getString(R.string.restore),
                        String.valueOf(errF.getMessage()));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (ShellEngine.PartResult p : rf.parts) {
                    sb.append(p.ok ? "✓ " : "✗ ").append(p.part);
                    if (p.detail != null && !p.detail.isEmpty()) {
                        sb.append(" (").append(p.detail).append(")");
                    }
                    sb.append("\n");
                }
                if (!rf.failures.isEmpty()) {
                    sb.append("\n");
                    int n = 0;
                    for (String f : rf.failures) {
                        if (n++ >= 4) { sb.append("…"); break; }
                        sb.append("• ").append(f).append("\n");
                    }
                }
                Toast.makeText(a, R.string.restore_done, Toast.LENGTH_LONG).show();
                if (rf.failures.isEmpty()) {
                    Notify.ok(a, a.getString(R.string.restore),
                        a.getString(R.string.restore_done));
                } else {
                    Notify.fail(a, a.getString(R.string.restore), sb.toString());
                }
                final boolean reboot = rf.rebootNeeded;
                new MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.wiz_done)
                    .setMessage(sb.toString()
                        + (reboot ? "\n" + a.getString(R.string.reboot_needed_sum)
                            : ""))
                    .setPositiveButton(reboot ? R.string.reboot_now : R.string.got_it,
                        (d, w) -> {
                            if (reboot) {
                                new Thread(() -> {
                                    try { ShellEngine.su("reboot"); }
                                    catch (Throwable ignore) { }
                                }).start();
                            }
                            if (onDone != null) onDone.run();
                        })
                    .show();
            });
        }).start();
    }

    /** Chain flow: base full with parts, then incrementals data-only. */
    private static void restoreChainFlow(final Activity a, final CloneStore db,
            final String pkg, final int userId, final String title,
            final List<ShellEngine.Backup> chain, final Runnable onDone) {
        ShellEngine.Backup base = chain.get(0);
        if (base.path.endsWith(".enc") && !CryptoVault.hasSessionPassword()) {
            askPassword(a, () ->
                restoreChainFlow(a, db, pkg, userId, title, chain, onDone));
            return;
        }
        BackupJob sel = BackupJob.defaults(a);
        sel.data = true;
        final ProgressTask.Handle prog = ProgressTask.show(a,
            a.getString(R.string.wiz_step3), a.getString(R.string.restoring),
            null);
        try {
            prog.setStatus(a.getString(R.string.wiz_chain, chain.size() - 1, ""));
        } catch (Throwable ignore) { }
        new Thread(() -> {
            ShellEngine.RestoreResult r = null;
            Throwable err = null;
            try {
                r = CloneEngine.restoreChain(a, db, pkg, userId, chain, sel);
            } catch (Throwable t) { err = t; }
            final ShellEngine.RestoreResult rf = r;
            final Throwable errF = err;
            a.runOnUiThread(() -> {
                try { prog.dismiss(); } catch (Throwable ignore) { }
                if (errF != null) {
                    Toast.makeText(a, a.getString(R.string.restore_failed) + ": "
                        + errF.getMessage(), Toast.LENGTH_LONG).show();
                    Notify.fail(a, a.getString(R.string.restore),
                        String.valueOf(errF.getMessage()));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(a.getString(R.string.wiz_chain_done, chain.size()))
                    .append("\n");
                for (ShellEngine.PartResult p : rf.parts) {
                    sb.append(p.ok ? "✓ " : "✗ ").append(p.part).append("\n");
                }
                if (!rf.failures.isEmpty()) {
                    sb.append(rf.failures.get(0)).append("\n");
                }
                Toast.makeText(a, R.string.restore_done, Toast.LENGTH_LONG).show();
                if (rf.failures.isEmpty()) {
                    Notify.ok(a, a.getString(R.string.restore),
                        a.getString(R.string.restore_done));
                } else {
                    Notify.fail(a, a.getString(R.string.restore), sb.toString());
                }
                final boolean reboot = rf.rebootNeeded;
                new MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.wiz_done)
                    .setMessage(sb.toString()
                        + (reboot ? "\n" + a.getString(R.string.reboot_needed_sum)
                            : ""))
                    .setPositiveButton(reboot ? R.string.reboot_now : R.string.got_it,
                        (d, w) -> {
                            if (reboot) {
                                new Thread(() -> {
                                    try { ShellEngine.su("reboot"); }
                                    catch (Throwable ignore) { }
                                }).start();
                            }
                            if (onDone != null) onDone.run();
                        })
                    .show();
            });
        }).start();
    }

    /** Specials: picker + confirm + execute + reboot note. */
    public static void startSpecial(final Activity a, final String kind,
            final String title) {
        new Thread(() -> {
            String dest;
            try { dest = ShellEngine.resolveDestDir(BackupJob.dest(a)); }
            catch (Throwable t) { dest = ShellEngine.BACKUP_DIR; }
            List<ShellEngine.Backup> all =
                ShellEngine.listFullBackups(dest, "special_" + kind);
            final List<ShellEngine.Backup> list = all;
            a.runOnUiThread(() -> {
                if (list.isEmpty()) {
                    Toast.makeText(a, R.string.no_backups, Toast.LENGTH_LONG).show();
                    return;
                }
                String[] names = new String[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    ShellEngine.Backup bk = list.get(i);
                    String s = bk.size >= 0
                        ? CloneUsage.formatSize(a, bk.size) : "?";
                    names[i] = ShellEngine.prettyBackupDate(a, bk.name)
                        + "  •  " + s;
                }
                new MaterialAlertDialogBuilder(a)
                    .setTitle(a.getString(R.string.wiz_step1) + ": " + title)
                    .setItems(names, (d, which) -> {
                        final ShellEngine.Backup bk = list.get(which);
                        new MaterialAlertDialogBuilder(a)
                            .setTitle(title)
                            .setMessage(a.getString(
                                R.string.special_restore_sum))
                            .setPositiveButton(R.string.restore, (dd, ww) -> {
                                Toast.makeText(a, R.string.restoring,
                                    Toast.LENGTH_SHORT).show();
                                new Thread(() -> {
                                    try {
                                        ShellEngine.restoreSpecial(kind, bk.path);
                                        a.runOnUiThread(() -> {
                                            Toast.makeText(a, R.string.restore_done,
                                                Toast.LENGTH_LONG).show();
                                            Notify.ok(a, title, a.getString(
                                                R.string.restore_done));
                                            showRebootNote(a);
                                        });
                                    } catch (Throwable t) {
                                        a.runOnUiThread(() -> {
                                            Toast.makeText(a,
                                                a.getString(R.string.restore_failed)
                                                    + ": " + t.getMessage(),
                                                Toast.LENGTH_LONG).show();
                                            Notify.fail(a, title,
                                                String.valueOf(t.getMessage()));
                                        });
                                    }
                                }).start();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    static void showRebootNote(Activity a) {
        new MaterialAlertDialogBuilder(a)
            .setTitle(R.string.reboot_needed)
            .setMessage(R.string.reboot_needed_sum)
            .setPositiveButton(R.string.reboot_now, (d, w) -> new Thread(() -> {
                try { ShellEngine.su("reboot"); }
                catch (Throwable t) {
                    a.runOnUiThread(() -> Toast.makeText(a,
                        String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show());
                }
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    static void askPassword(final Activity a, final Runnable then) {
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_rename, null);
        TextInputEditText input = v.findViewById(R.id.rename);
        input.setHint(R.string.pw_hint);
        try {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } catch (Throwable ignore) { }
        new MaterialAlertDialogBuilder(a)
            .setTitle(R.string.pw_title)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String s = input.getText() == null ? ""
                    : input.getText().toString();
                if (s.length() < CryptoVault.MIN_PW) {
                    Toast.makeText(a, R.string.pw_short, Toast.LENGTH_LONG).show();
                    return;
                }
                CryptoVault.setSessionPassword(s.toCharArray());
                try { input.setText(""); } catch (Throwable ignore) { }
                if (then != null) then.run();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }
}
