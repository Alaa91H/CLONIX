package com.clonix.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * Archive manager: every backup file with size + app version, rename,
 * delete (swipe, optional via Settings), verify, restore, export/import
 * via USB. Tap = restore, long-press = menu, swipe = delete.
 */
public class ArchivesActivity extends Activity {
    static class Row {
        ShellEngine.Backup bk;
        String pkg = "";
        int userId = -1;
        String specialKind = "";
        String ver = "";
        String label = "";
        Drawable icon;
        boolean verified;
    }

    private CloneStore db;
    private ArchiveAdapter adapter;
    private String filterPkg = null;
    private String query = "";
    private int sortMode = 0; // 0=newest 1=oldest 2=largest 3=name

    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_archives);
        NavHelper.setup(this, R.id.nav_sync);
        db = new CloneStore(this);
        try {
            filterPkg = getIntent() == null ? null
                : getIntent().getStringExtra("filterPkg");
        } catch (Throwable ignore) { }
        MaterialToolbar bar = findViewById(R.id.toolbar);
        bar.setTitle(R.string.archives_title);
        bar.setNavigationOnClickListener(v -> finish());
        bar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.m_auto_verify) {
                showAutoVerifyDialog();
                return true;
            }
            if (item.getItemId() == R.id.m_migrate) {
                showMigrateSource();
                return true;
            }
            if (item.getItemId() == R.id.m_scan_invalid) {
                Maintenance.scanInvalid(this);
                return true;
            }
            if (item.getItemId() == R.id.m_apply_retention) {
                Maintenance.applyRetentionNow(this);
                return true;
            }
            if (item.getItemId() == R.id.m_verify_all) { verifyAll(); return true; }
            if (item.getItemId() == R.id.m_export_all) { exportAll(); return true; }
            if (item.getItemId() == R.id.m_import) { importPicker(); return true; }
            if (item.getItemId() == R.id.m_sort_new) {
                item.setChecked(true); sortMode = 0; adapter.applyView(); return true;
            }
            if (item.getItemId() == R.id.m_sort_old) {
                item.setChecked(true); sortMode = 1; adapter.applyView(); return true;
            }
            if (item.getItemId() == R.id.m_sort_big) {
                item.setChecked(true); sortMode = 2; adapter.applyView(); return true;
            }
            if (item.getItemId() == R.id.m_sort_name) {
                item.setChecked(true); sortMode = 3; adapter.applyView(); return true;
            }
            return false;
        });
        com.google.android.material.textfield.TextInputEditText search =
            findViewById(R.id.search);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s == null ? "" : s.toString().trim();
                adapter.applyView();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ArchiveAdapter(this);
        list.setAdapter(adapter);
        // Empty-state CTA: jump to the backup center to create one.
        try {
            findViewById(R.id.empty_cta).setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(this,
                        BackupCenterActivity.class));
                } catch (Throwable ignore) { }
            });
        } catch (Throwable ignore) { }
        ItemTouchHelper swipe = new ItemTouchHelper(
            new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.START | ItemTouchHelper.END) {
                @Override public boolean onMove(RecyclerView r,
                        RecyclerView.ViewHolder a, RecyclerView.ViewHolder c) {
                    return false;
                }
                @Override public boolean isItemViewSwipeEnabled() {
                    return Prefs.swipeActions(ArchivesActivity.this);
                }
                @Override public void onSwiped(RecyclerView.ViewHolder h, int dir) {
                    int pos = h.getAdapterPosition();
                    Row row = adapter.rowAt(pos);
                    adapter.notifyItemChanged(pos); // snap back pending confirm
                    if (row != null) confirmDelete(row);
                }
            });
        swipe.attachToRecyclerView(list);
        reload();
        try {
            if (getIntent() != null
                    && getIntent().getBooleanExtra("migrateNow", false)) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        try { showMigrateSource(); } catch (Throwable ignore) { }
                    }, 600);
            }
        } catch (Throwable ignore) { }
    }

    @Override protected void onResume() {
        super.onResume();
        try { NavHelper.refreshBadges(this); } catch (Throwable ignore) { }
        reload();
    }

    private void setBusy(boolean busy, String s) {
        LinearProgressIndicator p = findViewById(R.id.progress);
        if (p != null) p.setVisibility(busy ? View.VISIBLE : View.GONE);
        TextView st = findViewById(R.id.status);
        if (st != null) {
            st.setVisibility(s != null ? View.VISIBLE : View.GONE);
            if (s != null) st.setText(s);
        }
    }

    public void reload() {
        setBusy(true, getString(R.string.loading));
        new Thread(() -> {
            // Age out trashed archives (>7d). Trash is never listed.
            try {
                ShellEngine.purgeTrash(
                    ShellEngine.resolveDestDir(BackupJob.dest(this)), 7);
                ShellEngine.purgeTrash(ShellEngine.BACKUP_DIR, 7);
            } catch (Throwable ignore) { }
            List<Row> rows = loadRows();
            runOnUiThread(() -> {
                setBusy(false, null);
                try {
                    BackupJob.setLastCounts(this, BackupJob.lastClones(this),
                        rows.size());
                } catch (Throwable ignore) { }
                adapter.setRows(rows);
                findViewById(R.id.empty).setVisibility(
                    rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
            // Enrichment pass: versions + verified flags (extra su calls).
            boolean changed = false;
            for (Row r : rows) {
                try {
                    ShellEngine.Meta m = r.bk.enc
                        ? metaIfUnlocked(r.bk.path) : ShellEngine.readMeta(r.bk.path);
                    if (m != null && (m.ver != null && !m.ver.isEmpty()
                            || m.label != null && !m.label.isEmpty())) {
                        StringBuilder v = new StringBuilder();
                        if (m.label != null && !m.label.isEmpty()) {
                            v.append(m.label);
                        }
                        if (m.ver != null && !m.ver.isEmpty()) {
                            if (v.length() > 0) v.append(" • ");
                            v.append("v").append(m.ver);
                            if (m.vcode != null && !m.vcode.isEmpty()
                                    && !m.vcode.equals("0")) {
                                v.append(" (").append(m.vcode).append(")");
                            }
                        }
                        if (v.length() > 0) { r.ver = v.toString(); changed = true; }
                    }
                    if (hasVerifiedSidecar(r.bk.path)) { r.verified = true; changed = true; }
                } catch (Throwable ignore) { }
            }
            if (changed) {
                runOnUiThread(() -> adapter.notifyDataSetChanged());
            }
        }).start();
    }

    private ShellEngine.Meta metaIfUnlocked(String archive) {
        try {
            if (!CryptoVault.hasSessionPassword()) return null;
            return ShellEngine.readMetaDecrypted(archive,
                CryptoVault.sessionPassword());
        } catch (Throwable ignore) { return null; }
    }

    private boolean hasVerifiedSidecar(String archive) {
        try {
            String v = ShellEngine.metaPathFor(archive).replace(".meta", ".verified")
                .replace(".verified.enc", ".verified");
            if (new java.io.File(v).exists()) return true;
            ShellEngine.ExecResult r = ShellEngine.execRootGlobal(
                new String[]{"sh", "-c", "[ -f '" + v + "' ] && echo YES"}, 15000);
            return r.ok && r.out.contains("YES");
        } catch (Throwable ignore) { return false; }
    }

    private List<Row> loadRows() {
        List<Row> out = new ArrayList<>();
        List<ShellEngine.Backup> all = CloneEngine.listFullBackups(this, null);
        try {
            for (ShellEngine.Backup b : ShellEngine.listBackups("__none__")) {
                boolean dup = false;
                for (ShellEngine.Backup x : all) {
                    if (x.path.equals(b.path)) { dup = true; break; }
                }
                if (!dup) all.add(b);
            }
        } catch (Throwable ignore) { }
        for (ShellEngine.Backup bk : all) {
            String n = bk.path.substring(bk.path.lastIndexOf('/') + 1);
            if (filterPkg != null && !filterPkg.isEmpty()
                    && !n.startsWith(filterPkg + "_")) continue;
            Row r = new Row();
            r.bk = bk;
            if (n.startsWith("special_")) {
                for (String k : ShellEngine.SPECIAL_KINDS) {
                    if (n.startsWith("special_" + k + "_")) r.specialKind = k;
                }
            } else {
                int u = n.indexOf("_u");
                if (u > 0) {
                    r.pkg = n.substring(0, u);
                    try {
                        String rest = n.substring(u + 2);
                        int us = rest.indexOf('_');
                        r.userId = Integer.parseInt(
                            us > 0 ? rest.substring(0, us) : rest);
                    } catch (Throwable ignore) { r.userId = -1; }
                }
            }
            if (!r.pkg.isEmpty()) {
                try {
                    r.icon = getPackageManager().getApplicationIcon(r.pkg);
                } catch (Throwable ignore) { }
            }
            out.add(r);
        }
        return out;
    }

    // ---------------- actions ----------------

    private void confirmDelete(final Row row) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(Bidi.isolate(row.bk.name))
            .setMessage(R.string.delete_archive_trash)
            .setPositiveButton(R.string.delete, (d, w) -> new Thread(() -> {
                try {
                    final String trashPath =
                        ShellEngine.trashArchive(row.bk.path);
                    runOnUiThread(() -> {
                        reload();
                        // Undo: genuinely recoverable via .trash — M3 pattern.
                        try {
                            com.google.android.material.snackbar.Snackbar
                                .make(findViewById(android.R.id.content),
                                    R.string.archive_trashed,
                                    com.google.android.material.snackbar
                                        .Snackbar.LENGTH_LONG)
                                .setAction(R.string.undo, v -> new Thread(() -> {
                                    try {
                                        ShellEngine.untrashArchive(trashPath);
                                        runOnUiThread(() -> {
                                            Toast.makeText(this,
                                                R.string.trash_restored,
                                                Toast.LENGTH_SHORT).show();
                                            reload();
                                        });
                                    } catch (Throwable t) {
                                        runOnUiThread(() -> Toast.makeText(this,
                                            R.string.trash_gone,
                                            Toast.LENGTH_LONG).show());
                                    }
                                }).start())
                                .show();
                        } catch (Throwable t) {
                            Toast.makeText(this, R.string.archive_trashed,
                                Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this,
                        String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show());
                }
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void renameDialog(final Row row) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        TextInputEditText input = v.findViewById(R.id.rename);
        String n = row.bk.name;
        int dot = n.indexOf(".tar.");
        input.setText(dot > 0 ? n.substring(0, dot) : n);
        input.setHint(R.string.rename_hint);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String s = input.getText() == null ? ""
                    : input.getText().toString().trim();
                new Thread(() -> {
                    try {
                        ShellEngine.renameBackup(row.bk.path, s);
                        runOnUiThread(() -> {
                            Toast.makeText(this, R.string.saved,
                                Toast.LENGTH_SHORT).show();
                            reload();
                        });
                    } catch (Throwable t) {
                        runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.rename_failed) + ": " + t.getMessage(),
                            Toast.LENGTH_LONG).show());
                    }
                }).start();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void verifyOne(final Row row) {
        if (row.bk.enc && !CryptoVault.hasSessionPassword()) {
            askPassword(() -> verifyOne(row));
            return;
        }
        Toast.makeText(this, R.string.verifying, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            char[] pw = row.bk.enc ? CryptoVault.sessionPassword() : null;
            final ShellEngine.VerifyResult vr =
                ShellEngine.verifyArchive(row.bk.path, pw);
            // markVerified touches views via reload(): must run on UI thread.
            runOnUiThread(() -> {
                if (vr.ok) markVerified(row.bk.path);
            });
            runOnUiThread(() -> Toast.makeText(this,
                vr.ok ? getString(R.string.verify_ok)
                    : getString(R.string.verify_fail) + ": " + vr.detail,
                Toast.LENGTH_LONG).show());
        }).start();
    }

    private void markVerified(String archive) {
        VerifyScheduler.markVerified(archive);
        reload();
    }

    private void verifyAll() {
        new Thread(() -> {
            List<Row> rows = loadRows();
            int ok = 0, total = 0;
            for (Row r : rows) {
                if (r.bk.enc && !CryptoVault.hasSessionPassword()) continue;
                total++;
                updateStatus(getString(R.string.verifying) + " " + r.bk.name);
                char[] pw = r.bk.enc ? CryptoVault.sessionPassword() : null;
                ShellEngine.VerifyResult vr =
                    ShellEngine.verifyArchive(r.bk.path, pw);
                if (vr.ok) { ok++; markVerifiedSilent(r.bk.path); }
            }
            final int okF = ok, totalF = total;
            runOnUiThread(() -> {
                setBusy(false, null);
                String msg = getString(R.string.verify_summary, okF, totalF);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                if (okF == totalF) Notify.ok(this, getString(R.string.verify), msg);
                else Notify.fail(this, getString(R.string.verify), msg);
                reload();
            });
        }).start();
        setBusy(true, getString(R.string.verifying));
    }

    private void markVerifiedSilent(String archive) {
        VerifyScheduler.markVerified(archive);
    }

    private void showAutoVerifyDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_schedule, null);
        com.google.android.material.materialswitch.MaterialSwitch on =
            v.findViewById(R.id.sched_on);
        com.google.android.material.materialswitch.MaterialSwitch chg =
            v.findViewById(R.id.sched_charging);
        com.google.android.material.materialswitch.MaterialSwitch idle =
            v.findViewById(R.id.sched_idle);
        com.google.android.material.textfield.TextInputEditText hours =
            v.findViewById(R.id.sched_hours);
        on.setChecked(VerifyScheduler.enabled(this));
        chg.setChecked(VerifyScheduler.requireCharging(this));
        idle.setVisibility(View.GONE);
        hours.setText(String.valueOf(VerifyScheduler.intervalHours(this)));
        String last = VerifyScheduler.lastRun(this);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.auto_verify)
            .setMessage(getString(R.string.auto_verify_sum)
                + (last.isEmpty() ? "" : "\n\n" + getString(
                    R.string.auto_verify_last, last)))
            .setView(v)
            .setPositiveButton(R.string.save_default, (d, w) -> {
                int h = 168;
                try {
                    h = Integer.parseInt(hours.getText() == null ? "168"
                        : hours.getText().toString().trim());
                } catch (Throwable ignore) { }
                VerifyScheduler.save(this, on.isChecked(), h, chg.isChecked());
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // ---------------- migration import (other backup apps) ----------------

    private void showMigrateSource() {
        final String[] roots = {
            "/sdcard/NeoBackup",
            "/sdcard/Clonix",
            getString(R.string.migrate_custom),
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.migrate_title)
            .setMessage(R.string.migrate_sum)
            .setItems(roots, (d, which) -> {
                if (which == 2) {
                    askCustomRoot();
                    return;
                }
                scanMigrateRoot(roots[which]);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void askCustomRoot() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        TextInputEditText input = v.findViewById(R.id.rename);
        input.setHint("/sdcard/MyOldBackups");
        input.setText("/sdcard/");
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.migrate_title)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String s = input.getText() == null ? ""
                    : input.getText().toString().trim();
                if (!s.isEmpty()) scanMigrateRoot(s);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void scanMigrateRoot(final String root) {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<MigrationImporter.MigApp> apps =
                MigrationImporter.scan(root);
            runOnUiThread(() -> {
                if (apps.isEmpty()) {
                    Toast.makeText(this, R.string.migrate_empty,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                String[] names = new String[apps.size()];
                final boolean[] sel = new boolean[apps.size()];
                for (int i = 0; i < apps.size(); i++) {
                    MigrationImporter.MigApp a = apps.get(i);
                    sel[i] = true;
                    String info = a.pkg;
                    if (!a.ver.isEmpty()) info += " v" + a.ver;
                    info += " • " + a.archives.size() + " • " + a.type;
                    names[i] = info;
                }
                new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.migrate_pick, apps.size()))
                    .setMultiChoiceItems(names, sel, (dd, which, c) ->
                        sel[which] = c)
                    .setPositiveButton(R.string.confirm, (dd, ww) -> {
                        List<MigrationImporter.MigApp> picked = new ArrayList<>();
                        for (int i = 0; i < apps.size(); i++) {
                            if (sel[i]) picked.add(apps.get(i));
                        }
                        if (!picked.isEmpty()) migrateTargets(picked);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    private int migOk = 0;
    private final List<String> migFails = new ArrayList<>();

    /** Per-app target choice, processed sequentially with live progress. */
    private void migrateTargets(final List<MigrationImporter.MigApp> picked) {
        migOk = 0;
        migFails.clear();
        migrateNext(picked, 0);
    }

    private void migrateNext(final List<MigrationImporter.MigApp> picked,
            final int idx) {
        if (idx >= picked.size()) {
            showMigrateSummary();
            return;
        }
        final MigrationImporter.MigApp a = picked.get(idx);
        List<String> opts = new ArrayList<>();
        final List<Integer> uids = new ArrayList<>();
        try {
            getPackageManager().getApplicationInfo(a.pkg, 0);
            opts.add(getString(R.string.migrate_owner));
            uids.add(0);
        } catch (Throwable ignore) { }
        for (CloneStore.Clone cl : db.listForPkg(a.pkg)) {
            String t = (cl.nickname != null && !cl.nickname.isEmpty()
                ? cl.nickname : (a.pkg + " " + cl.slotIndex))
                + " (u" + cl.userId + ")";
            opts.add(t);
            uids.add(cl.userId);
        }
        opts.add(getString(R.string.migrate_new_clone));
        String title = a.pkg + (a.ver.isEmpty() ? "" : " v" + a.ver)
            + " (" + (idx + 1) + "/" + picked.size() + ")";
        new MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(R.string.migrate_target)
            .setItems(opts.toArray(new String[0]), (d, which) -> {
                if (which < uids.size()) {
                    runOneMigration(picked, idx, uids.get(which));
                } else {
                    // New clone, then import into it.
                    Toast.makeText(this, R.string.migrate_clone,
                        Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int uid = CloneEngine.cloneToNextSlot(
                            this, db, a.pkg, "", true);
                        if (uid < 0) {
                            runOnUiThread(() -> {
                                migFails.add(a.pkg + ": clone failed");
                                migrateNext(picked, idx + 1);
                            });
                            return;
                        }
                        runOneMigration(picked, idx, uid);
                    }).start();
                }
            })
            .setNegativeButton(R.string.cancel, (d, w) ->
                migrateNext(picked, idx + 1))
            .show();
    }

    private void runOneMigration(final List<MigrationImporter.MigApp> picked,
            final int idx, final int uid) {
        final MigrationImporter.MigApp a = picked.get(idx);
        setBusy(true, getString(R.string.migrating) + " " + a.pkg);
        new Thread(() -> {
            try {
                // Biggest data archive wins (APKs already skipped).
                ShellEngine.Backup best = null;
                for (ShellEngine.Backup b : a.archives) {
                    if (best == null || b.size > best.size) best = b;
                }
                if (best == null) throw new Exception("no archive");
                List<String> members =
                    MigrationImporter.previewMembers(best.path, 40);
                MigrationImporter.Plan plan =
                    MigrationImporter.plan(a.pkg, members);
                if (plan.kind == MigrationImporter.Plan.Kind.UNSUPPORTED) {
                    throw new Exception(
                        getString(R.string.migrate_unsupported)
                        + " " + plan.detail);
                }
                if (uid == 0) {
                    try {
                        getPackageManager().getApplicationInfo(a.pkg, 0);
                    } catch (Throwable t) {
                        throw new Exception("not installed");
                    }
                }
                MigrationImporter.importArchive(a.pkg, uid, best.path, plan);
                migOk++;
            } catch (Throwable t) {
                migFails.add(a.pkg + ": " + t.getMessage());
            }
            runOnUiThread(() -> migrateNext(picked, idx + 1));
        }).start();
    }

    private void showMigrateSummary() {
        setBusy(false, null);
        StringBuilder sb = new StringBuilder(getString(R.string.migrated, migOk));
        if (!migFails.isEmpty()) {
            sb.append("\n");
            for (int i = 0; i < Math.min(4, migFails.size()); i++) {
                sb.append("• ").append(migFails.get(i)).append("\n");
            }
        }
        sb.append("\n").append(getString(R.string.migrate_perms_note));
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.migrate_title)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.got_it, (d, w) -> reload())
            .show();
        if (migFails.isEmpty()) {
            Notify.ok(this, getString(R.string.migrate_title),
                getString(R.string.migrated, migOk));
        } else {
            Notify.fail(this, getString(R.string.migrate_title), sb.toString());
        }
    }

    private void updateStatus(final String s) {
        runOnUiThread(() -> {
            TextView st = findViewById(R.id.status);
            if (st != null) { st.setVisibility(View.VISIBLE); st.setText(s); }
        });
    }

    private void detailsDialog(Row row) {
        new Thread(() -> {
            ShellEngine.Meta m = row.bk.enc ? metaIfUnlocked(row.bk.path)
                : ShellEngine.readMeta(row.bk.path);
            final StringBuilder sb = new StringBuilder();
            sb.append(row.bk.path).append("\n\n");
            String size = row.bk.size >= 0
                ? CloneUsage.formatSize(this, row.bk.size) : "?";
            sb.append(getString(R.string.detail_size, size)).append("\n");
            if (m != null) {
                if (m.label != null && !m.label.isEmpty()) {
                    sb.append(getString(R.string.detail_app, m.label)).append("\n");
                }
                if (m.ver != null && !m.ver.isEmpty()) {
                    sb.append(getString(R.string.detail_ver, m.ver, m.vcode))
                        .append("\n");
                }
                sb.append(getString(R.string.detail_comp,
                    m.comp == null ? "?" : m.comp)).append("\n");
                sb.append(getString(R.string.detail_enc,
                    (m.enc != null && !m.enc.isEmpty()) ? m.enc
                        : getString(R.string.detail_no))).append("\n");
                sb.append(getString(R.string.detail_parts,
                    m.parts == null || m.parts.isEmpty() ? "?" : m.parts))
                    .append("\n");
                if (!m.perms.isEmpty()) {
                    sb.append(getString(R.string.detail_perms, m.perms.size()))
                        .append("\n");
                }
                if (!m.appops.isEmpty()) {
                    sb.append(getString(R.string.detail_appops, m.appops.size()))
                        .append("\n");
                }
            }
            runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                .setTitle(row.bk.name)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.got_it, null)
                .show());
        }).start();
    }

    /** manage sheet: header + icon rows, destructive red. */
    private void longPressMenu(final Row row) {
        final com.google.android.material.bottomsheet.BottomSheetDialog sheet =
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, pad);
        // Header: icon + isolated name.
        android.widget.LinearLayout head =
            new android.widget.LinearLayout(this);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView hic = new ImageView(this);
        try {
            if (row.icon != null) hic.setImageDrawable(row.icon);
            else hic.setImageResource(android.R.drawable.ic_menu_save);
        } catch (Throwable ignore) { }
        int isz = (int) (40 * getResources().getDisplayMetrics().density);
        head.addView(hic, new android.widget.LinearLayout.LayoutParams(isz, isz));
        TextView hname = new TextView(this);
        try {
            hname.setTextAppearance(
                com.google.android.material.R.style
                    .TextAppearance_Material3_TitleSmall);
        } catch (Throwable ignore) { }
        hname.setMaxLines(2);
        hname.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams hlp =
            new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        hlp.setMarginStart(pad / 2);
        hname.setText(Bidi.isolate(row.bk.name));
        head.addView(hname, hlp);
        root.addView(head);
        sheetRow(root, R.drawable.ic_nav_archives_outline,
            getString(R.string.restore), false, v -> {
                try { sheet.dismiss(); } catch (Throwable ignore) { }
                restoreRow(row);
            });
        sheetRow(root, R.drawable.ic_set_shield, getString(R.string.verify),
            false, v -> {
                try { sheet.dismiss(); } catch (Throwable ignore) { }
                verifyOne(row);
            });
        sheetRow(root, R.drawable.ic_set_edit, getString(R.string.rename),
            false, v -> {
                try { sheet.dismiss(); } catch (Throwable ignore) { }
                renameDialog(row);
            });
        sheetRow(root, R.drawable.ic_set_info,
            getString(R.string.detail_title), false, v -> {
                try { sheet.dismiss(); } catch (Throwable ignore) { }
                detailsDialog(row);
            });
        sheetRow(root, R.drawable.ic_set_export,
            getString(R.string.export_one), false, v -> {
                try { sheet.dismiss(); } catch (Throwable ignore) { }
                exportSome(
                    java.util.Collections.singletonList(row.bk.path));
            });
        sheetRow(root, R.drawable.ic_set_delete, getString(R.string.delete),
            true, v -> {
                try { sheet.dismiss(); } catch (Throwable ignore) { }
                confirmDelete(row);
            });
        sheet.setContentView(root);
        try { sheet.show(); } catch (Throwable ignore) { }
    }

    private void sheetRow(android.widget.LinearLayout root, int icon,
            String text, boolean destructive,
            View.OnClickListener onTap) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(
            (int) (56 * getResources().getDisplayMetrics().density));
        try {
            row.setBackgroundResource(
                android.R.attr.selectableItemBackground);
            android.content.res.TypedArray ta = obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
            try {
                row.setBackgroundResource(ta.getResourceId(0, 0));
            } catch (Throwable ignore) { }
            try { ta.recycle(); } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
        row.setFocusable(true);
        row.setClickable(true);
        ImageView iv = new ImageView(this);
        try { iv.setImageResource(icon); } catch (Throwable ignore) { }
        iv.setImportantForAccessibility(
            ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int isz = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams ilp =
            new android.widget.LinearLayout.LayoutParams(isz, isz);
        int m = (int) (12 * getResources().getDisplayMetrics().density);
        ilp.setMarginStart(m);
        ilp.setMarginEnd(m);
        row.addView(iv, ilp);
        TextView tv = new TextView(this);
        try {
            tv.setTextAppearance(
                com.google.android.material.R.style
                    .TextAppearance_Material3_BodyLarge);
        } catch (Throwable ignore) { }
        if (destructive) {
            try {
                tv.setTextColor(getColor(R.color.error));
            } catch (Throwable ignore) { }
        }
        tv.setText(text);
        row.addView(tv, new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        row.setOnClickListener(onTap);
        root.addView(row);
    }

    private void restoreRow(final Row row) {
        // Unified interactive wizard (preview -> confirm -> execute).
        if (!row.specialKind.isEmpty()) {
            RestoreWizard.startSpecial(this, row.specialKind, row.bk.name);
            return;
        }
        if (row.pkg.isEmpty() || row.userId < 0) {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String title = row.ver != null && !row.ver.isEmpty() ? row.ver : row.bk.name;
        RestoreWizard.startWithArchive(this, db, row.pkg, row.userId,
            title, row.bk.path, () -> reload());
    }

    private void showRebootNote() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reboot_needed)
            .setMessage(R.string.reboot_needed_sum)
            .setPositiveButton(R.string.reboot_now, (d, w) -> new Thread(() -> {
                try { ShellEngine.su("reboot"); }
                catch (Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this,
                        String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show());
                }
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // ---------------- password ----------------

    private void askPassword(final Runnable then) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        TextInputEditText input = v.findViewById(R.id.rename);
        input.setHint(R.string.pw_hint);
        try {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } catch (Throwable ignore) { }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pw_title)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String s = input.getText() == null ? ""
                    : input.getText().toString();
                if (s.length() < CryptoVault.MIN_PW) {
                    Toast.makeText(this, R.string.pw_short, Toast.LENGTH_LONG).show();
                    return;
                }
                CryptoVault.setSessionPassword(s.toCharArray());
                s = "";
                try { input.setText(""); } catch (Throwable ignore) { }
                if (then != null) then.run();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // ---------------- export / import ----------------

    private void exportAll() {
        new Thread(() -> {
            List<String> paths = new ArrayList<>();
            for (Row r : loadRows()) paths.add(r.bk.path);
            updateStatus(getString(R.string.exporting));
            final int n = ShellEngine.exportArchives(paths);
            runOnUiThread(() -> {
                setBusy(false, null);
                Toast.makeText(this, getString(R.string.exported, n)
                    + "\n" + ShellEngine.EXPORT_DIR, Toast.LENGTH_LONG).show();
            });
        }).start();
        setBusy(true, getString(R.string.exporting));
    }

    private void exportSome(final List<String> paths) {
        new Thread(() -> {
            final int n = ShellEngine.exportArchives(paths);
            runOnUiThread(() -> Toast.makeText(this, getString(R.string.exported, n)
                + "\n" + ShellEngine.EXPORT_DIR, Toast.LENGTH_LONG).show());
        }).start();
        Toast.makeText(this, R.string.exporting, Toast.LENGTH_SHORT).show();
    }

    private void importPicker() {
        new Thread(() -> {
            final List<ShellEngine.Backup> staged = ShellEngine.listExport();
            runOnUiThread(() -> {
                if (staged.isEmpty()) {
                    Toast.makeText(this, R.string.import_empty, Toast.LENGTH_LONG)
                        .show();
                    return;
                }
                String[] names = new String[staged.size()];
                final boolean[] sel = new boolean[staged.size()];
                for (int i = 0; i < staged.size(); i++) {
                    sel[i] = true;
                    names[i] = staged.get(i).name;
                }
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.import_usb)
                    .setMultiChoiceItems(names, sel, (d, which, c) -> sel[which] = c)
                    .setPositiveButton(R.string.confirm, (d, w) -> {
                        List<String> paths = new ArrayList<>();
                        for (int i = 0; i < staged.size(); i++) {
                            if (sel[i]) paths.add(staged.get(i).path);
                        }
                        runImport(paths);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    private void runImport(final List<String> paths) {
        setBusy(true, getString(R.string.importing));
        new Thread(() -> {
            String dest;
            try {
                dest = ShellEngine.resolveDestDir(BackupJob.dest(this));
            } catch (Throwable t) { dest = ShellEngine.BACKUP_DIR; }
            final int n = ShellEngine.importArchives(paths, dest);
            runOnUiThread(() -> {
                setBusy(false, null);
                Toast.makeText(this, getString(R.string.imported, n),
                    Toast.LENGTH_LONG).show();
                reload();
            });
        }).start();
    }

    // ---------------- adapter ----------------

    class ArchiveAdapter extends RecyclerView.Adapter<ArchiveAdapter.Holder> {
        private final LayoutInflater inf;
        private List<Row> base = new ArrayList<>();
        private List<Row> rows = new ArrayList<>();
        ArchiveAdapter(Context c) { inf = LayoutInflater.from(c); }
        void setRows(List<Row> r) {
            base = r != null ? r : new ArrayList<Row>();
            applyView();
        }
        void applyView() {
            List<Row> next = new ArrayList<>();
            for (Row r : base) {
                if (!query.isEmpty()
                        && !r.bk.name.toLowerCase().contains(query.toLowerCase())) {
                    continue;
                }
                next.add(r);
            }
            if (sortMode == 1) {
                java.util.Collections.sort(next,
                    (a, b) -> a.bk.path.compareTo(b.bk.path));
            } else if (sortMode == 2) {
                java.util.Collections.sort(next,
                    (a, b) -> Long.compare(b.bk.size, a.bk.size));
            } else if (sortMode == 3) {
                java.util.Collections.sort(next,
                    (a, b) -> a.bk.name.compareToIgnoreCase(b.bk.name));
            } else {
                java.util.Collections.sort(next,
                    (a, b) -> b.bk.path.compareTo(a.bk.path));
            }
            rows = next;
            notifyDataSetChanged();
            try {
                findViewById(R.id.empty).setVisibility(
                    rows.isEmpty() ? View.VISIBLE : View.GONE);
            } catch (Throwable ignore) { }
            long total = 0;
            for (Row r : rows) if (r.bk.size > 0) total += r.bk.size;
            final int n = rows.size();
            final long totalF = total;
            try {
                TextView st = findViewById(R.id.status);
                st.setVisibility(View.VISIBLE);
                st.setText(getString(R.string.archives_summary, n,
                    CloneUsage.formatSize(ArchivesActivity.this, totalF)));
            } catch (Throwable ignore) { }
        }
        Row rowAt(int pos) {
            return (pos >= 0 && pos < rows.size()) ? rows.get(pos) : null;
        }
        @Override public int getItemCount() { return rows.size(); }
        @Override public Holder onCreateViewHolder(ViewGroup p, int t) {
            return new Holder(inf.inflate(R.layout.item_archive, p, false));
        }
        @Override public void onBindViewHolder(Holder h, int pos) {
            final Row r = rows.get(pos);
            if (r.icon != null) h.icon.setImageDrawable(r.icon);
            else h.icon.setImageResource(android.R.drawable.ic_menu_save);
            h.name.setText(Bidi.isolate(r.bk.name));
            String size = r.bk.size >= 0
                ? CloneUsage.formatSize(ArchivesActivity.this, r.bk.size)
                : "?";
            StringBuilder d = new StringBuilder(Bidi.isolate(size));
            String when = ShellEngine.prettyBackupDate(ArchivesActivity.this, r.bk.name);
            if (!when.equals(r.bk.name)) d.append(" • ").append(when);
            if (r.ver != null && !r.ver.isEmpty()) d.append(" • ").append(r.ver);
            if (r.bk.enc) d.append(" • ").append(getString(R.string.locked));
            h.detail.setText(d.toString());
            List<String> badges = new ArrayList<>();
            if (r.verified) badges.add(getString(R.string.verified));
            if (r.bk.enc) badges.add(getString(R.string.encrypted));
            if (!r.specialKind.isEmpty()) badges.add(r.specialKind);
            if (badges.isEmpty()) h.badge.setVisibility(View.GONE);
            else {
                h.badge.setVisibility(View.VISIBLE);
                StringBuilder bb = new StringBuilder();
                for (int i = 0; i < badges.size(); i++) {
                    if (i > 0) bb.append(" • ");
                    bb.append(badges.get(i));
                }
                h.badge.setText(bb.toString());
            }
            h.restore.setOnClickListener(v -> restoreRow(r));
            h.itemView.setOnLongClickListener(v -> { longPressMenu(r); return true; });
        }
        class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, detail, badge;
            final MaterialButton restore;
            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
                badge = v.findViewById(R.id.badge);
                restore = v.findViewById(R.id.btn_restore);
            }
        }
    }
}
