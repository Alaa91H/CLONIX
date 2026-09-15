package com.clonix.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-app page: sizes (owner + clones), full/selective backup & restore,
 * clone management (open/freeze/backup/delete), blacklist, categories,
 * clear data/cache, system app-info, APK/APKs sharing.
 */
public class AppDetailActivity extends Activity {
    private String pkg;
    private CloneStore db;
    private PackageManager pm;

    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        pkg = getIntent() == null ? null : getIntent().getStringExtra("pkg");
        if (pkg == null) { finish(); return; }
        setContentView(R.layout.activity_appdetail);
        db = new CloneStore(this);
        pm = getPackageManager();
        MaterialToolbar bar = findViewById(R.id.toolbar);
        bar.setSubtitle(R.string.detail_subtitle);
        bar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.btn_backup).setOnClickListener(v -> backupBase());
        findViewById(R.id.btn_backups).setOnClickListener(v -> {
            Intent i = new Intent(this, ArchivesActivity.class);
            i.putExtra("filterPkg", pkg);
            startActivity(i);
        });
        findViewById(R.id.btn_clone).setOnClickListener(v -> cloneNew());
        findViewById(R.id.btn_launch).setOnClickListener(v -> launchBase());
        findViewById(R.id.btn_uninstall).setOnClickListener(v -> uninstallBase());
        findViewById(R.id.btn_menu).setOnClickListener(v -> showOverflow());
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private void busy(boolean on) {
        LinearProgressIndicator p = findViewById(R.id.progress);
        if (p != null) p.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void reload() {
        busy(true);
        new Thread(() -> {
            String label = pkg, ver = "";
            Drawable icon = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = String.valueOf(pm.getApplicationLabel(ai));
                try { icon = pm.getApplicationIcon(ai); } catch (Throwable ignore) { }
                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    if (pi.versionName != null) ver = "v" + pi.versionName;
                } catch (Throwable ignore) { }
            } catch (Throwable ignore) { }
            CloneUsage.Usage base = CloneUsage.queryPackage(this, pkg, 0);
            List<CloneStore.Clone> clones = db.listForPkg(pkg);
            Collections.sort(clones, (a, x) -> Integer.compare(a.slotIndex, x.slotIndex));
            java.util.Map<String, CloneUsage.Usage> umap =
                CloneUsage.queryBatch(this, clones);
            final String labelF = label, verF = ver;
            final Drawable iconF = icon;
            final CloneUsage.Usage baseF = base;
            runOnUiThread(() -> {
                busy(false);
                render(labelF, verF, iconF, baseF, clones, umap);
            });
        }).start();
    }

    private void render(String label, String ver, Drawable icon,
            CloneUsage.Usage base, List<CloneStore.Clone> clones,
            java.util.Map<String, CloneUsage.Usage> umap) {
        ((TextView) findViewById(R.id.name)).setText(label);
        ((TextView) findViewById(R.id.pkg)).setText(Bidi.isolate(pkg));
        ((TextView) findViewById(R.id.ver)).setText(Bidi.isolate(ver));
        try {
            MaterialToolbar bar = findViewById(R.id.toolbar);
            bar.setTitle(label);
        } catch (Throwable ignore) { }
        if (icon != null) ((ImageView) findViewById(R.id.icon)).setImageDrawable(icon);
        // Header: installed line + APK/Data stat chips.
        try {
            String date = "";
            try {
                long first = pm.getPackageInfo(pkg, 0).firstInstallTime;
                date = android.text.format.DateFormat.getDateFormat(this)
                    .format(new java.util.Date(first));
            } catch (Throwable ignore) { }
            TextView inst = findViewById(R.id.installed);
            if (base != null && base.available) {
                inst.setText(getString(R.string.detail_installed, date,
                    CloneUsage.formatSize(this, base.totalBytes),
                    CloneUsage.formatSize(this, base.cacheBytes)));
                ((TextView) findViewById(R.id.stat_apk)).setText(
                    CloneUsage.formatSize(this, base.appBytes));
                ((TextView) findViewById(R.id.stat_data)).setText(
                    CloneUsage.formatSize(this, base.dataBytes));
            } else {
                inst.setText(R.string.storage_unavailable_short);
            }
        } catch (Throwable ignore) { }
        renderDeviceBackups();
        LinearLayout box = findViewById(R.id.clones);
        box.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        if (clones.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(R.string.no_clones);
            t.setTextAppearance(android.R.attr.textAppearanceSmall);
            box.addView(t);
        }
        for (CloneStore.Clone cl : clones) {
            View v = inf.inflate(R.layout.item_detail_clone, box, false);
            ImageView ic = v.findViewById(R.id.icon);
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                ic.setImageDrawable(BadgeRenderer.badgeForClone(
                    this, pm.getApplicationIcon(ai), cl));
            } catch (Throwable ignore) { }
            String title = (cl.nickname != null && !cl.nickname.isEmpty())
                ? cl.nickname : (label + " " + cl.slotIndex);
            ((TextView) v.findViewById(R.id.name)).setText(title);
            CloneUsage.Usage u = umap.get(cl.pkg + "#" + cl.userId);
            String d = "u" + cl.userId;
            if (u != null && u.available) {
                d += " • " + CloneUsage.formatSize(this, u.extraBytes);
            }
            ((TextView) v.findViewById(R.id.detail)).setText(d);
            v.findViewById(R.id.btn_open).setOnClickListener(x -> openClone(cl));
            MaterialButton fr = v.findViewById(R.id.btn_freeze);
            fr.setOnClickListener(x -> toggleFreeze(cl, fr));
            new Thread(() -> {
                boolean frozen = false;
                try { frozen = CloneEngine.isFrozen(this, cl.pkg, cl.userId); }
                catch (Throwable ignore) { }
                final boolean f = frozen;
                runOnUiThread(() -> {
                    try { fr.setText(f ? R.string.unfreeze : R.string.freeze); }
                    catch (Throwable ignore) { }
                });
            }).start();
            v.findViewById(R.id.btn_backup).setOnClickListener(x -> backupClone(cl));
            v.findViewById(R.id.btn_delete).setOnClickListener(x -> deleteClone(cl));
            // Long-press a clone card: rename / home-screen shortcut (from old list).
            v.setOnLongClickListener(x -> {
                showCloneActions(cl, title);
                return true;
            });
            box.addView(v);
        }
    }

    /** Rename / shortcut / open actions for one clone (long-press card). */
    private void showCloneActions(CloneStore.Clone cl, String title) {
        androidx.appcompat.widget.PopupMenu menu =
            new androidx.appcompat.widget.PopupMenu(this,
                findViewById(R.id.btn_menu));
        menu.getMenu().add(0, 1, 0, getString(R.string.rename));
        menu.getMenu().add(0, 2, 0, getString(R.string.create_shortcut));
        menu.getMenu().add(0, 3, 0, getString(R.string.badge_settings));
        menu.getMenu().add(0, 4, 0, getString(R.string.open));
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) { askNickname(cl); return true; }
            if (item.getItemId() == 2) {
                Drawable base = null;
                try { base = pm.getApplicationIcon(pkg); } catch (Throwable ig) { }
                ShortcutHelper.pin(this, title, base, cl);
                Toast.makeText(this, R.string.create_shortcut,
                    Toast.LENGTH_SHORT).show();
                return true;
            }
            if (item.getItemId() == 3) { BadgeEditor.show(this, null, cl); return true; }
            if (item.getItemId() == 4) { openClone(cl); return true; }
            return false;
        });
        menu.show();
    }

    private void askNickname(CloneStore.Clone cl) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        TextInputEditText input = v.findViewById(R.id.rename);
        input.setText(cl.nickname == null ? "" : cl.nickname);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> new Thread(() -> {
                String nn = input.getText() == null ? ""
                    : input.getText().toString().trim();
                new CloneStore(this).updateNickname(cl.pkg, cl.userId, nn);
                runOnUiThread(this::reload);
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /** ⋮ card menu: secondary actions with live state in titles. */
    private void showOverflow() {
        try {
            View anchor = findViewById(R.id.btn_menu);
            androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(this, anchor);
            boolean enabled = ShellEngine.isBaseEnabled(this, pkg);
            boolean blocked = BackupJob.isBlacklisted(this, pkg);
            String cat = BackupJob.category(this, pkg);
            menu.getMenu().add(0, 1, 0,
                getString(enabled ? R.string.disable_app : R.string.enable_app));
            menu.getMenu().add(0, 2, 0, getString(R.string.app_info_btn));
            menu.getMenu().add(0, 3, 0, getString(R.string.share_apk));
            menu.getMenu().add(0, 4, 0, cat.isEmpty()
                ? getString(R.string.category_title)
                : getString(R.string.category_show, cat));
            menu.getMenu().add(0, 5, 0, getString(
                blocked ? R.string.blacklist_on : R.string.blacklist_off));
            menu.getMenu().add(0, 6, 0, getString(R.string.clear_data));
            menu.getMenu().add(0, 7, 0, getString(R.string.clear_cache));
            // Orphan adoption: re-adopt untracked clone users of this app.
            int orphans = countOrphans();
            if (orphans > 0) {
                menu.getMenu().add(0, 8, 0,
                    getString(R.string.orphan_found, orphans));
            }
            menu.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) toggleDisable();
                else if (id == 2) StorageActivity.openAppInfoStatic(
                    this, pkg, 0);
                else if (id == 3) shareApks();
                else if (id == 4) editCategory();
                else if (id == 5) toggleBlacklist();
                else if (id == 6) confirmClearData();
                else if (id == 7) clearCacheBase();
                else if (id == 8) adoptOrphans();
                return true;
            });
            menu.show();
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_SHORT).show();
        }
    }

    /** "Device backups": newest 3 archives inline, tap = restore. */
    private void renderDeviceBackups() {
        new Thread(() -> {
            List<ShellEngine.Backup> all = new ArrayList<>();
            try {
                for (ShellEngine.Backup b :
                        CloneEngine.listFullBackups(this, pkg)) {
                    String n = b.path.substring(b.path.lastIndexOf('/') + 1);
                    if (!n.startsWith("special_")) all.add(b);
                }
            } catch (Throwable ignore) { }
            Collections.sort(all,
                (a, b) -> b.path.compareTo(a.path));
            final List<ShellEngine.Backup> top =
                all.subList(0, Math.min(3, all.size()));
            final List<ShellEngine.Backup> topF = new ArrayList<>(top);
            runOnUiThread(() -> {
                try {
                    LinearLayout list = findViewById(R.id.device_list);
                    View empty = findViewById(R.id.device_empty);
                    list.removeAllViews();
                    empty.setVisibility(
                        topF.isEmpty() ? View.VISIBLE : View.GONE);
                    LayoutInflater inf = LayoutInflater.from(this);
                    for (ShellEngine.Backup bk : topF) {
                        View v = inf.inflate(R.layout.item_archive_mini,
                            list, false);
                        String n = bk.name;
                        ((TextView) v.findViewById(R.id.name))
                            .setText(Bidi.isolate(n));
                        String size = bk.size >= 0 ? CloneUsage
                            .formatSize(this, bk.size) : "?";
                        String when =
                            ShellEngine.prettyBackupDate(this, n);
                        ((TextView) v.findViewById(R.id.detail)).setText(
                            (bk.enc ? "🔒 " : "") + Bidi.isolate(size)
                            + (when.equals(n) ? ""
                                : " • " + Bidi.isolate(when)));
                        v.setOnClickListener(x -> ArchivesSheet
                            .restoreDirect(this, bk));
                        list.addView(v);
                    }
                } catch (Throwable ignore) { }
            });
        }).start();
    }

    private void launchBase() {
        try {
            Intent li = pm.getLaunchIntentForPackage(pkg);
            if (li == null) {
                Toast.makeText(this, R.string.not_cloneable,
                    Toast.LENGTH_SHORT).show();
                return;
            }
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(li);
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void uninstallBase() {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + pkg));
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- base actions ----------------

    private void backupBase() {
        Preflight.checkOne(this, pkg, 0, forceStopped -> runBackupBase());
    }

    private void runBackupBase() {
        final BackupJob opts = BackupJob.defaults(this);
        if (opts.enc && !CryptoVault.hasSessionPassword()) {
            Toast.makeText(this, R.string.pw_unlocked_sum, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.backing_up, Toast.LENGTH_SHORT).show();
        busy(true);
        final String title = getString(R.string.backup) + ": " + pkg;
        OpsQueue.post(this, title, app -> {
            ShellEngine.FullResult r;
            if (opts.enc) {
                r = CloneEngine.backupAutoEnc(app, pkg, 0, opts.clone(),
                    CryptoVault.sessionPassword());
            } else {
                r = CloneEngine.backupAuto(app, pkg, 0, opts.clone());
            }
            final boolean unchanged = r != null && r.level == -2;
            Notify.ok(app, title, app.getString(unchanged
                ? R.string.skipped_unchanged : R.string.backup_done_simple, 1));
            runOnUiThread(() -> {
                busy(false);
                Toast.makeText(AppDetailActivity.this,
                    unchanged ? getString(R.string.skipped_unchanged, 1)
                        : getString(R.string.backup_done_simple),
                    Toast.LENGTH_LONG).show();
                reload();
            });
        });
    }

    private void cloneNew() {
        if (db.listForPkg(pkg).size() >= CloneEngine.MAX_CLONES_PER_APP) {
            Toast.makeText(this,
                getString(R.string.max_reached, CloneEngine.MAX_CLONES_PER_APP),
                Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int uid = CloneEngine.cloneToNextSlot(this, db, pkg, "", true);
            EngineLog.i(this, "clone", pkg + " -> u" + uid);
            runOnUiThread(() -> {
                if (uid >= 0) {
                    Toast.makeText(this, R.string.clone_created_simple,
                        Toast.LENGTH_LONG).show();
                    reload();
                    CloneEngine.launchClone(this, pkg, uid);
                } else {
                    Toast.makeText(this, R.string.not_cloneable, Toast.LENGTH_LONG)
                        .show();
                }
            });
        }).start();
    }

    private void toggleDisable() {
        boolean enabled = ShellEngine.isBaseEnabled(this, pkg);
        new MaterialAlertDialogBuilder(this)
            .setTitle(pkg)
            .setMessage(enabled ? R.string.disable_sum : R.string.enable_sum)
            .setPositiveButton(R.string.confirm, (d, w) -> new Thread(() -> {
                try {
                    ShellEngine.setBaseEnabled(pkg, !enabled);
                    runOnUiThread(() -> {
                        Toast.makeText(this,
                            !enabled ? R.string.enabled : R.string.disabled,
                            Toast.LENGTH_SHORT).show();
                        reload();
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this,
                        String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show());
                }
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void confirmClearData() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data)
            .setMessage(R.string.confirm_clear_data_base)
            .setPositiveButton(R.string.clear_data, (d, w) -> new Thread(() -> {
                boolean ok = CloneUsage.clearDataAsUser(this, pkg, 0);
                runOnUiThread(() -> {
                    Toast.makeText(this,
                        ok ? R.string.data_cleared : R.string.restore_failed,
                        Toast.LENGTH_LONG).show();
                    reload();
                });
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void clearCacheBase() {
        new Thread(() -> {
            boolean ok = CloneUsage.clearCacheAsUser(this, pkg, 0);
            runOnUiThread(() -> {
                Toast.makeText(this,
                    ok ? R.string.cache_cleared : R.string.restore_failed,
                    Toast.LENGTH_SHORT).show();
                reload();
            });
        }).start();
    }

    /** Untracked clone users of this app (installed in our users, not in DB). */
    private int countOrphans() {
        java.util.Set<Integer> tracked = new java.util.HashSet<>();
        CloneStore db = new CloneStore(this);
        try {
            for (CloneStore.Clone cl : db.listForPkg(pkg)) tracked.add(cl.userId);
        } catch (Throwable ignore) { }
        try { db.close(); } catch (Throwable ignore) { }
        int n = 0;
        try {
            for (SysApi.User u : SysApi.safeGetUsers(this)) {
                if (u.id == 0 || !CloneEngine.isOursName(u.name)) continue;
                if (tracked.contains(u.id)) continue;
                for (String p : SysApi.getLaunchablePackages(this, u.id)) {
                    if (p.equals(pkg)) { n++; break; }
                }
            }
        } catch (Throwable ignore) { }
        return n;
    }

    /** Register orphan users of this app into the DB so they are manageable. */
    private void adoptOrphans() {
        Toast.makeText(this, "\u2026", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            CloneStore db = new CloneStore(this);
            java.util.Set<Integer> tracked = new java.util.HashSet<>();
            try { for (CloneStore.Clone c : db.listForPkg(pkg)) tracked.add(c.userId); }
            catch (Throwable ignore) { }
            int n = 0;
            try {
                for (SysApi.User u : SysApi.safeGetUsers(this)) {
                    if (u.id == 0 || !CloneEngine.isOursName(u.name)) continue;
                    if (tracked.contains(u.id)) continue;
                    boolean has = false;
                    for (String p : SysApi.getLaunchablePackages(this, u.id)) {
                        if (p.equals(pkg)) { has = true; break; }
                    }
                    if (!has) continue;
                    CloneStore.Clone cl = new CloneStore.Clone();
                    cl.pkg = pkg;
                    cl.userId = u.id;
                    cl.slotIndex = db.nextSlotIndex(pkg);
                    cl.nickname = "";
                    cl.separateContacts = true;
                    try { db.add(cl); n++; } catch (Throwable ignore) { }
                }
            } catch (Throwable ignore) { }
            try { db.close(); } catch (Throwable ignore) { }
            final int done = n;
            runOnUiThread(() -> {
                Toast.makeText(this, getString(R.string.adopted, done),
                    Toast.LENGTH_LONG).show();
                reload();
            });
        }).start();
    }

    private void toggleBlacklist() {
        boolean now = !BackupJob.isBlacklisted(this, pkg);
        BackupJob.setBlacklisted(this, pkg, now);
        Toast.makeText(this, now ? R.string.blacklisted : R.string.unblacklisted,
            Toast.LENGTH_SHORT).show();
    }

    private void editCategory() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        TextInputEditText input = v.findViewById(R.id.rename);
        input.setText(BackupJob.category(this, pkg));
        input.setHint(R.string.category_hint);
        List<String> cats = BackupJob.allCategories(this);
        String[] names = cats.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category_title)
            .setView(v)
            .setItems(names.length > 0 ? names : null, (d, which) -> {
                BackupJob.setCategory(this, pkg, cats.get(which));
            })
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String s = input.getText() == null ? ""
                    : input.getText().toString().trim();
                BackupJob.setCategory(this, pkg, s);
            })
            .setNeutralButton(R.string.clear, (d, w) -> {
                BackupJob.setCategory(this, pkg, "");
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /** Share base APK + splits via the system sharesheet (Quick Share ready). */
    private void shareApks() {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                ShellEngine.ExecResult r = ShellEngine.su("pm", "path", pkg);
                List<String> paths = new ArrayList<>();
                if (r.ok) {
                    for (String line : r.out.split("\n")) {
                        line = line.trim();
                        if (line.startsWith("package:")) {
                            String p = line.substring(8).trim();
                            if (!p.isEmpty()) paths.add(p);
                        }
                    }
                }
                if (paths.isEmpty()) throw new Exception("no apk");
                List<java.io.File> staged = new ArrayList<>();
                // pm path may point at root-only dirs: stage via su cp.
                for (int i = 0; i < paths.size(); i++) {
                    String dst = getCacheDir() + "/share/"
                        + pkg + (i == 0 ? "_base.apk" : "_split" + i + ".apk");
                    ShellEngine.ExecResult cp = ShellEngine.execRootGlobal(
                        new String[]{"sh", "-c",
                            "mkdir -p '" + getCacheDir() + "/share' && cp '"
                            + paths.get(i) + "' '" + dst + "'"
                            + " && chmod 644 '" + dst + "' && echo CP_OK"},
                        120000);
                    if (cp.ok && cp.out.contains("CP_OK")) {
                        staged.add(new java.io.File(dst));
                    }
                }
                final List<java.io.File> stagedF = staged;
                runOnUiThread(() -> ShareHelper.shareFiles(this, stagedF,
                    "application/vnd.android.package-archive", pkg));
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.share_failed) + ": " + t.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------------- clone actions ----------------

    private void openClone(CloneStore.Clone cl) {
        Toast.makeText(this, R.string.opening_clone, Toast.LENGTH_SHORT).show();
        new Thread(() -> CloneEngine.launchClone(this, cl.pkg, cl.userId)).start();
    }

    private void toggleFreeze(final CloneStore.Clone cl, final MaterialButton btn) {
        new Thread(() -> {
            try {
                boolean frozen = CloneEngine.isFrozen(this, cl.pkg, cl.userId);
                final boolean now = frozen
                    ? CloneEngine.unfreezeAndVerify(this, cl.pkg, cl.userId)
                    : CloneEngine.freezeAndVerify(this, cl.pkg, cl.userId);
                runOnUiThread(() -> {
                    try { btn.setText(now ? R.string.unfreeze : R.string.freeze); }
                    catch (Throwable ignore) { }
                    Toast.makeText(this, now ? R.string.frozen : R.string.unfrozen,
                        Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                    String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void backupClone(CloneStore.Clone cl) {
        Preflight.checkOne(this, cl.pkg, cl.userId,
            forceStopped -> runBackupClone(cl));
    }

    private void runBackupClone(CloneStore.Clone cl) {
        final BackupJob opts = BackupJob.defaults(this);
        if (opts.enc && !CryptoVault.hasSessionPassword()) {
            Toast.makeText(this, R.string.pw_unlocked_sum, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.backing_up, Toast.LENGTH_SHORT).show();
        busy(true);
        final String title = getString(R.string.backup) + ": " + cl.pkg;
        OpsQueue.post(this, title, app -> {
            ShellEngine.FullResult r;
            if (opts.enc) {
                r = CloneEngine.backupAutoEnc(app, cl.pkg, cl.userId,
                    opts.clone(), CryptoVault.sessionPassword());
            } else {
                r = CloneEngine.backupAuto(app, cl.pkg, cl.userId, opts.clone());
            }
            final boolean unchanged = r != null && r.level == -2;
            Notify.ok(app, title, app.getString(unchanged
                ? R.string.skipped_unchanged : R.string.backup_done_simple, 1));
            runOnUiThread(() -> {
                busy(false);
                Toast.makeText(AppDetailActivity.this,
                    unchanged ? getString(R.string.skipped_unchanged, 1)
                        : getString(R.string.backup_done_simple),
                    Toast.LENGTH_LONG).show();
                reload();
            });
        });
    }

    private void deleteClone(final CloneStore.Clone cl) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manage_delete_btn)
            .setMessage(R.string.delete)
            .setPositiveButton(R.string.delete, (d, w) -> new Thread(() -> {
                CloneEngine.deleteClone(this, db, cl.pkg, cl.userId);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.clone_deleted,
                        Toast.LENGTH_SHORT).show();
                    reload();
                });
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    public static void open(Context c, String pkg) {
        try {
            Intent i = new Intent(c, AppDetailActivity.class);
            i.putExtra("pkg", pkg);
            if (!(c instanceof Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            c.startActivity(i);
        } catch (Throwable ignore) { }
    }
}
