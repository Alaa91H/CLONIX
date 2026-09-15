package com.clonix.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LOCAL APPS: full-apps list with search + sort/filter sheets,
 * batch actions (backup/restore/delete backups/export/enable/uninstall),
 * and a per-app action bottom sheet (Disable/Uninstall/Force stop/Clear data
 * + Favorites/Backup-Restore/Labels/Blacklist/Battery), all backed by the
 * existing CloneEngine/BackupJob/RestoreWizard engine.
 */
public class AppsActivity extends Activity {

    static class Row {
        String pkg;
        String label;
        Drawable icon;
        boolean isSystem;
        boolean installed = true;
        int backupCount;
        long newestBackupAt;
        long appUpdatedAt;
        int cloneCount;
        boolean blacklisted;
        String category = "";
    }

    // sort modes
    static final int SORT_NAME = 0, SORT_BACKUP_DATE = 1, SORT_BACKUP_SIZE = 2,
        SORT_APP_SIZE = 3, SORT_COPIES = 4;
    // filter values: -1 all / 0 no / 1 yes
    int fAppType = -1;      // -1 both, 0 user, 1 system
    int fBackup = -1;       // -1 all, 0 none, 1 has
    int fClones = -1;       // -1 all, 0 not cloned, 1 cloned
    int fInstalled = -1;    // -1 all, 0 not installed, 1 installed
    int fEnabled = -1;      // -1 all, 0 disabled, 1 enabled
    int sortMode = SORT_NAME;
    boolean filtersActive = false;
    String query = "";
    /** Selection mode: pkg set + per-row checked flag, icon tap toggles. */
    boolean selMode = false;
    final java.util.HashSet<String> selected = new java.util.HashSet<>();

    private PackageManager pm;
    private CloneStore db;
    private AppAdapter adapter;
    private TextView title;
    private TextView subtitle;

    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_apps);
        pm = getPackageManager();
        db = new CloneStore(this);
        adapter = new AppAdapter();

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        title = findViewById(R.id.apps_title);
        subtitle = findViewById(R.id.apps_sub);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_filter).setOnClickListener(v -> showFilterSheet());
        findViewById(R.id.btn_listsort).setOnClickListener(v -> showSortSheet());
        findViewById(R.id.btn_batch).setOnClickListener(v -> showBatchSheet());
        findViewById(R.id.btn_sel_all).setOnClickListener(v -> {
            for (Row r : adapter.rows) selected.add(r.pkg);
            updateSelBar();
            adapter.notifyDataSetChanged();
        });
        findViewById(R.id.btn_sel_done).setOnClickListener(v -> exitSelMode());
        try {
            EditText s = findViewById(R.id.search);
            s.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int x, int d) {}
                @Override public void onTextChanged(CharSequence c, int a, int x, int d) {
                    query = c == null ? "" : c.toString().trim();
                    adapter.apply();
                }
                @Override public void afterTextChanged(Editable e) {}
            });
        } catch (Throwable ignore) { }
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        LinearProgressIndicator p = findViewById(R.id.progress);
        if (p != null) p.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<Row> rows = loadRows();
            runOnUiThread(() -> {
                if (p != null) p.setVisibility(View.GONE);
                adapter.setAll(rows);
                updateHeader();
            });
        }).start();
    }

    private void updateHeader() {
        int n = adapter.visibleCount();
        title.setText(getString(R.string.apps_count_fmt, n)
            + (filtersActive ? " " + getString(R.string.filters_active) : ""));
        subtitle.setText(R.string.local_apps);
        adapter.notifyDataSetChanged();
    }

    private void updateSelBar() {
        View bar = findViewById(R.id.sel_bar);
        TextView cnt = findViewById(R.id.sel_count);
        bar.setVisibility(selMode ? View.VISIBLE : View.GONE);
        cnt.setText(getString(R.string.sel_count_fmt, selected.size()));
    }

    private void enterSelMode(String firstPkg) {
        selMode = true;
        selected.clear();
        selected.add(firstPkg);
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void exitSelMode() {
        selMode = false;
        selected.clear();
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private List<Row> loadRows() {
        Map<String, Row> map = new LinkedHashMap<>();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                try {
                    ApplicationInfo ai =
                        pm.getApplicationInfo(ri.activityInfo.packageName, 0);
                    if (map.containsKey(ai.packageName)) continue;
                    Row r = new Row();
                    r.pkg = ai.packageName;
                    r.label = String.valueOf(pm.getApplicationLabel(ai));
                    try { r.icon = pm.getApplicationIcon(ai); } catch (Throwable ignore) { }
                    r.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    try {
                        r.appUpdatedAt = pm.getPackageInfo(r.pkg, 0).lastUpdateTime;
                    } catch (Throwable ignore) { }
                    map.put(r.pkg, r);
                } catch (Throwable ignore) { }
            }
        } catch (Throwable ignore) { }
        // tracked clones (also brings non-launcher tracked apps in)
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> newest = new HashMap<>();
        try {
            for (ShellEngine.Backup bk : CloneEngine.listFullBackups(this, null)) {
                String n = bk.path.substring(bk.path.lastIndexOf('/') + 1);
                if (n.startsWith("special_")) continue;
                int u = n.indexOf("_u");
                if (u > 0) {
                    String p = n.substring(0, u);
                    counts.merge(p, 1, Integer::sum);
                    long t = ShellEngine.backupTimeOf(n);
                    Long prev = newest.get(p);
                    if (prev == null || t > prev) newest.put(p, t);
                }
            }
        } catch (Throwable ignore) { }
        try {
            for (CloneStore.Clone cl : db.listAll()) {
                Row r = map.get(cl.pkg);
                if (r == null) {
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(cl.pkg, 0);
                        r = new Row();
                        r.pkg = cl.pkg;
                        r.label = String.valueOf(pm.getApplicationLabel(ai));
                        try { r.icon = pm.getApplicationIcon(ai); } catch (Throwable ignore) { }
                        r.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        map.put(cl.pkg, r);
                    } catch (Throwable t) { continue; }
                }
                r.cloneCount++;
            }
        } catch (Throwable ignore) { }
        List<Row> rows = new ArrayList<>(map.values());
        for (Row r : rows) {
            Integer c = counts.get(r.pkg);
            r.backupCount = c == null ? 0 : c;
            Long nt = newest.get(r.pkg);
            r.newestBackupAt = nt == null ? 0 : nt;
            r.blacklisted = BackupJob.isBlacklisted(this, r.pkg);
            r.category = BackupJob.category(this, r.pkg);
            try {
                r.installed = (pm.getPackageInfo(r.pkg, 0) != null);
            } catch (Throwable t) { r.installed = false; }
        }
        return rows;
    }

    // ---------------- sheets ----------------

    private void showSortSheet() {
        List<int[]> opts = new ArrayList<>();
        String[] labels = {
            getString(R.string.sort_name),
            getString(R.string.sort_backup_date),
            getString(R.string.sort_backup_size),
            getString(R.string.sort_app_size),
            getString(R.string.sort_count)};
        int[] modes = {SORT_NAME, SORT_BACKUP_DATE, SORT_BACKUP_SIZE,
            SORT_APP_SIZE, SORT_COPIES};
        int sel = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == sortMode) sel = i;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(labels, sel, null)
            .setPositiveButton(R.string.apply_options, (d, w) -> {
                androidx.appcompat.app.AlertDialog ad =
                    (androidx.appcompat.app.AlertDialog) d;
                int which = ad.getListView().getCheckedItemPosition();
                if (which >= 0 && which < modes.length) {
                    sortMode = modes[which];
                    adapter.apply();
                    updateHeader();
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showFilterSheet() {
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_filter, null);
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(v);
        final FilterViewHolder h = new FilterViewHolder(v);
        h.bind(this);
        h.wire(this);
        v.findViewById(R.id.apply).setOnClickListener(x -> {
            h.save(this);
            filtersActive = fAppType != -1 || fBackup != -1 || fClones != -1
                || fInstalled != -1 || fEnabled != -1;
            sheet.dismiss();
            adapter.apply();
            updateHeader();
        });
        v.findViewById(R.id.reset).setOnClickListener(x -> {
            fAppType = -1; fBackup = -1; fClones = -1;
            fInstalled = -1; fEnabled = -1;
            h.bind(this);
            filtersActive = false;
            adapter.apply();
            updateHeader();
            Toast.makeText(this, R.string.reset_filters, Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.close).setOnClickListener(x -> sheet.dismiss());
        sheet.show();
        try {
            sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            sheet.getBehavior().setSkipCollapsed(true);
        } catch (Throwable ignore) { }
    }

    static class FilterViewHolder {
        final Map<String, List<TextView>> groups = new HashMap<>();
        View v;

        FilterViewHolder(View v) {
            this.v = v;
            groups.put("type", new ArrayList<>());
            groups.put("backup", new ArrayList<>());
            groups.put("clones", new ArrayList<>());
            groups.put("install", new ArrayList<>());
            groups.put("enabled", new ArrayList<>());
            int[] typeIds = {R.id.t_all, R.id.t_user, R.id.t_system};
            for (int id : typeIds) groups.get("type").add(v.findViewById(id));
            int[] backupIds = {R.id.b_all, R.id.b_yes, R.id.b_no};
            for (int id : backupIds) groups.get("backup").add(v.findViewById(id));
            int[] cloneIds = {R.id.c_all, R.id.c_yes, R.id.c_no};
            for (int id : cloneIds) groups.get("clones").add(v.findViewById(id));
            int[] instIds = {R.id.i_all, R.id.i_yes, R.id.i_no};
            for (int id : instIds) groups.get("install").add(v.findViewById(id));
            int[] enIds = {R.id.e_all, R.id.e_yes, R.id.e_no};
            for (int id : enIds) groups.get("enabled").add(v.findViewById(id));
        }

        /** Wire click listeners so each pill toggle actually selects. */
        void wire(final AppsActivity a) {
            for (final String g : groups.keySet()) {
                List<TextView> l = groups.get(g);
                for (int i = 0; i < l.size(); i++) {
                    final int idx = i;
                    TextView t = l.get(i);
                    if (t == null) continue;
                    t.setClickable(true);
                    t.setOnClickListener(x -> {
                        List<TextView> gl = groups.get(g);
                        for (TextView o : gl) {
                            if (o != null) o.setSelected(false);
                        }
                        t.setSelected(true);
                        style(g, idx - 1, 1);
                    });
                }
            }
        }

        void bind(AppsActivity a) {
            style("type", a.fAppType, 2);
            style("backup", a.fBackup, 1);
            style("clones", a.fClones, 1);
            style("install", a.fInstalled, 1);
            style("enabled", a.fEnabled, 1);
        }

        void save(AppsActivity a) {
            a.fAppType = value("type");
            a.fBackup = value("backup");
            a.fClones = value("clones");
            a.fInstalled = value("install");
            a.fEnabled = value("enabled");
        }

        private int value(String g) {
            List<TextView> l = groups.get(g);
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).isSelected()) return i - 1;
            }
            return -1;
        }

        private void style(String g, int cur, int yesIndex) {
            List<TextView> l = groups.get(g);
            for (int i = 0; i < l.size(); i++) {
                TextView t = l.get(i);
                boolean on = (i - 1) == cur;
                t.setSelected(on);
                t.setBackgroundResource(on
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip_toggle);
                try {
                    t.setTextColor(on
                        ? t.getContext().getColor(R.color.onPrimary)
                        : t.getContext().getColor(R.color.onSurfaceVariant));
                } catch (Throwable ignore) { }
            }
        }
    }

    // ---------------- batch actions ----------------

    /** BATCH ACTIONS sheet: backup / restore / delete backups /
     *  export list / enable-disable / uninstall, applied to all apps
     *  currently matching the active filters. */
    private void showBatchSheet() {
        List<Row> targets = new ArrayList<>();
        if (selMode && !selected.isEmpty()) {
            for (Row r : adapter.rows) if (selected.contains(r.pkg)) targets.add(r);
        } else {
            targets.addAll(adapter.rows);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_do, Toast.LENGTH_SHORT).show();
            return;
        }
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_batch, null);
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(v);
        ((TextView) v.findViewById(R.id.batch_count)).setText(
            getString(R.string.sel_count_fmt, targets.size()));
        v.findViewById(R.id.ba_backup).setOnClickListener(x -> {
            sheet.dismiss();
            batchBackup(targets);
        });
        v.findViewById(R.id.ba_restore).setOnClickListener(x -> {
            sheet.dismiss();
            batchRestore(targets);
        });
        v.findViewById(R.id.ba_delete).setOnClickListener(x -> {
            sheet.dismiss();
            batchDeleteBackups(targets);
        });
        v.findViewById(R.id.ba_export).setOnClickListener(x -> {
            sheet.dismiss();
            exportList(targets);
        });
        v.findViewById(R.id.ba_enable_disable).setOnClickListener(x -> {
            sheet.dismiss();
            batchEnableDisable(targets);
        });
        v.findViewById(R.id.ba_uninstall).setOnClickListener(x -> {
            sheet.dismiss();
            batchUninstall(targets);
        });
        sheet.show();
        try {
            sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            sheet.getBehavior().setSkipCollapsed(true);
        } catch (Throwable ignore) { }
    }

    private void batchBackup(List<Row> targets) {
        final BackupJob opts = BackupJob.defaults(this);
        if (opts.enc && !CryptoVault.hasSessionPassword()) {
            RestoreWizard.askPassword(this, () -> batchBackup(targets));
            return;
        }
        final List<Preflight.Target> pts = new ArrayList<>();
        for (Row r : targets) pts.add(new Preflight.Target(r.pkg, 0, r.label));
        Preflight.checkBatch(this, pts, go -> {
            final LinearProgressIndicator p = findViewById(R.id.progress);
            runOnUiThread(() -> { if (p != null) p.setVisibility(View.VISIBLE); });
            final Context app = getApplicationContext();
            final String title = getString(R.string.local_apps);
            new Thread(() -> {
                int ok = 0, fails = 0;
                // Serialized engine lane + wakelock + progress notif.
                synchronized (OpsQueue.LOCK) {
                    android.os.PowerManager.WakeLock wl = null;
                    try {
                        android.os.PowerManager pm = (android.os.PowerManager)
                            app.getSystemService(Context.POWER_SERVICE);
                        if (pm != null) {
                            wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                                "Clonix:batch");
                            wl.setReferenceCounted(false);
                            wl.acquire(30 * 60 * 1000L);
                        }
                    } catch (Throwable ignore) { }
                    try {
                        Notify.batchProgress(app, title, 0, go.size());
                        for (int i = 0; i < go.size(); i++) {
                            Preflight.Target t = go.get(i);
                            Notify.batchProgress(app, title, i, go.size());
                            try {
                                ShellEngine.FullResult res;
                                if (opts.enc) {
                                    res = CloneEngine.backupAutoEnc(app, t.pkg, 0,
                                        opts.clone(), CryptoVault.sessionPassword());
                                } else {
                                    res = CloneEngine.backupAuto(app, t.pkg, 0,
                                        opts.clone());
                                }
                                if (res != null && res.level == -2) { /* unchanged */ }
                                else ok++;
                            } catch (Throwable tt) { fails++; }
                            Notify.batchProgress(app, title, i + 1, go.size());
                        }
                    } finally {
                        try { if (wl != null) wl.release(); }
                        catch (Throwable ignore) { }
                        Notify.doneProgress(app, Notify.ID_PROG);
                    }
                }
                EngineLog.i(app, "batch-backup",
                    ok + " ok, " + fails + " failed");
                final int okF = ok, failsF = fails;
                runOnUiThread(() -> {
                    if (p != null) p.setVisibility(View.GONE);
                    String msg = getString(R.string.backup_summary, okF);
                    if (failsF > 0) {
                        msg += "\n" + getString(R.string.batch_failed_fmt, failsF);
                    }
                    new com.google.android.material.dialog
                        .MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.backup)
                        .setMessage(msg)
                        .setPositiveButton(R.string.got_it, null)
                        .show();
                    reload();
                });
            }).start();
        });
    }

    private void batchRestore(List<Row> targets) {
        List<Preflight.Target> have = new ArrayList<>();
        for (Row r : targets) {
            if (r.backupCount > 0) {
                have.add(new Preflight.Target(r.pkg, 0, r.label));
            }
        }
        if (have.isEmpty()) {
            Toast.makeText(this, R.string.no_backups, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qa_restore_apps_title)
            .setMessage(getString(R.string.sel_count_fmt, have.size()) + "\n\n"
                + getString(R.string.backup_confirm_overwrite))
            .setPositiveButton(R.string.restore, (d, w) -> {
                final LinearProgressIndicator p = findViewById(R.id.progress);
                runOnUiThread(() -> { if (p != null) p.setVisibility(View.VISIBLE); });
                CloneStore cdb = new CloneStore(this);
                new Thread(() -> {
                    int ok = 0, fails = 0;
                    for (Preflight.Target t : have) {
                        try {
                            ShellEngine.Backup newest =
                                CloneEngine.newestBackup(this, t.pkg, 0);
                            if (newest == null) { fails++; continue; }
                            ShellEngine.RestoreResult rr = CloneEngine.restoreFull(
                                this, cdb, t.pkg, 0, newest.path,
                                BackupJob.defaults(this).clone());
                            if (rr.failures.isEmpty()) ok++; else fails++;
                        } catch (Throwable tt) { fails++; }
                    }
                    final int okF = ok, failsF = fails;
                    runOnUiThread(() -> {
                        if (p != null) p.setVisibility(View.GONE);
                        Toast.makeText(this,
                            getString(R.string.batch_done_fmt, okF)
                                + (failsF > 0 ? " • "
                                    + getString(R.string.batch_failed_fmt, failsF)
                                    : ""),
                            Toast.LENGTH_LONG).show();
                        reload();
                    });
                }).start();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void batchDeleteBackups(List<Row> targets) {
        List<Row> have = new ArrayList<>();
        for (Row r : targets) if (r.backupCount > 0) have.add(r);
        if (have.isEmpty()) {
            Toast.makeText(this, R.string.no_backups, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ba_delete_local)
            .setMessage(getString(R.string.sel_count_fmt, have.size()))
            .setPositiveButton(R.string.delete, (d, w) -> {
                new Thread(() -> {
                    int n = 0;
                    for (Row r : have) {
                        try {
                            for (ShellEngine.Backup bk :
                                    CloneEngine.listFullBackups(this, r.pkg)) {
                                String nm = bk.path.substring(
                                    bk.path.lastIndexOf('/') + 1);
                                if (nm.startsWith("special_")) continue;
                                if (nm.contains(r.pkg + "_u")) {
                                    ShellEngine.deleteArchive(bk.path);
                                    n++;
                                }
                            }
                        } catch (Throwable ignore) { }
                    }
                    final int nF = n;
                    runOnUiThread(() -> {
                        Toast.makeText(this,
                            getString(R.string.batch_done_fmt, nF),
                            Toast.LENGTH_LONG).show();
                        reload();
                    });
                }).start();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void exportList(List<Row> targets) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Row r : targets) {
                sb.append(r.pkg).append("\t").append(r.label)
                    .append("\t").append(r.backupCount).append("\n");
            }
            java.io.File dir = new java.io.File(
                getExternalFilesDir(null), "export");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, "apps_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date())
                + ".txt");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
            Toast.makeText(this, getString(R.string.export_done) + ": "
                + out.getPath(), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void batchEnableDisable(List<Row> targets) {
        String[] opts = {getString(R.string.act_disable),
            getString(R.string.act_enable)};
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ba_enable_disable)
            .setItems(opts, (d, which) -> new Thread(() -> {
                boolean disable = which == 0;
                int ok = 0;
                for (Row r : targets) {
                    try {
                        ShellEngine.setBaseEnabled(r.pkg, !disable);
                        ok++;
                    } catch (Throwable ignore) { }
                }
                final int okF = ok;
                runOnUiThread(() -> {
                    Toast.makeText(this,
                        getString(R.string.batch_done_fmt, okF),
                        Toast.LENGTH_LONG).show();
                    reload();
                });
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void batchUninstall(List<Row> targets) {
        List<Row> user = new ArrayList<>();
        for (Row r : targets) if (!r.isSystem) user.add(r);
        if (user.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_do, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Row r : user) sb.append(r.label).append("\n");
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ba_uninstall)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.act_uninstall, (d, w) -> {
                for (Row r : user) {
                    try {
                        startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + r.pkg)));
                    } catch (Throwable ignore) { }
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // ---------------- per-app action sheet ----------------

    private void showAppSheet(final Row r) {
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_app_actions, null);
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(v);
        ImageView icon = v.findViewById(R.id.app_icon);
        if (r.icon != null) icon.setImageDrawable(r.icon);
        ((TextView) v.findViewById(R.id.app_pkg)).setText(Bidi.isolate(r.pkg));
        ((TextView) v.findViewById(R.id.app_name)).setText(r.label);

        boolean enabled = true;
        try { enabled = ShellEngine.isBaseEnabled(this, r.pkg); }
        catch (Throwable ignore) { }
        ((TextView) v.findViewById(R.id.btn_disable)).setText(
            enabled ? R.string.act_disable : R.string.act_enable);
        ((TextView) v.findViewById(R.id.btn_uninstall)).setText(R.string.act_uninstall);
        ((TextView) v.findViewById(R.id.btn_force_stop)).setText(R.string.act_force_stop);
        ((TextView) v.findViewById(R.id.btn_clear_data)).setText(R.string.act_clear_data);

        v.findViewById(R.id.btn_disable).setOnClickListener(x -> {
            sheet.dismiss();
            toggleEnable(r);
        });
        v.findViewById(R.id.btn_uninstall).setOnClickListener(x -> {
            sheet.dismiss();
            uninstallApp(r);
        });
        v.findViewById(R.id.btn_force_stop).setOnClickListener(x -> {
            sheet.dismiss();
            forceStop(r);
        });
        v.findViewById(R.id.btn_clear_data).setOnClickListener(x -> {
            sheet.dismiss();
            confirmClearData(r);
        });
        v.findViewById(R.id.act_create_copy).setOnClickListener(x -> {
            sheet.dismiss();
            createCopy(r);
        });
        v.findViewById(R.id.act_manage_copies).setOnClickListener(x -> {
            sheet.dismiss();
            AppDetailActivity.open(this, r.pkg);
        });
        v.findViewById(R.id.act_favorites).setOnClickListener(x -> {
            sheet.dismiss();
            Toast.makeText(this, r.label, Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.act_backup_restore).setOnClickListener(x -> {
            sheet.dismiss();
            AppDetailActivity.open(this, r.pkg);
        });
        v.findViewById(R.id.act_labels).setOnClickListener(x -> {
            sheet.dismiss();
            editCategory(r);
        });
        v.findViewById(R.id.act_blacklist).setOnClickListener(x -> {
            sheet.dismiss();
            boolean now = !BackupJob.isBlacklisted(this, r.pkg);
            BackupJob.setBlacklisted(this, r.pkg, now);
            Toast.makeText(this, now ? R.string.blacklisted
                : R.string.unblacklisted, Toast.LENGTH_SHORT).show();
            reload();
        });
        v.findViewById(R.id.act_battery).setOnClickListener(x -> {
            sheet.dismiss();
            try {
                startActivity(new Intent(
                    "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"));
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(
                        android.provider.Settings.ACTION_SETTINGS));
                } catch (Throwable ignore) { }
            }
        });
        sheet.show();
    }

    /** Create one new copy (clone) of the app, then open its detail page. */
    private void createCopy(final Row r) {
        CloneStore cdb = new CloneStore(this);
        if (cdb.listForPkg(r.pkg).size() >= CloneEngine.MAX_CLONES_PER_APP) {
            Toast.makeText(this,
                getString(R.string.max_reached, CloneEngine.MAX_CLONES_PER_APP),
                Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int uid = CloneEngine.cloneToNextSlot(this, cdb, r.pkg, "", true);
            runOnUiThread(() -> {
                if (uid >= 0) {
                    Toast.makeText(this, R.string.clone_created_simple,
                        Toast.LENGTH_LONG).show();
                    reload();
                    AppDetailActivity.open(this, r.pkg);
                } else {
                    Toast.makeText(this, R.string.not_cloneable, Toast.LENGTH_LONG)
                        .show();
                }
            });
        }).start();
    }

    private void toggleEnable(Row r) {
        try {
            boolean enabled = ShellEngine.isBaseEnabled(this, r.pkg);
            ShellEngine.setBaseEnabled(r.pkg, !enabled);
            Toast.makeText(this, enabled ? R.string.disabled
                : R.string.enabled, Toast.LENGTH_SHORT).show();
            reload();
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void uninstallApp(Row r) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + r.pkg)));
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void forceStop(Row r) {
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                ShellEngine.forceStop(r.pkg, 0);
                runOnUiThread(() -> Toast.makeText(this,
                    R.string.act_force_stop, Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                    String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void confirmClearData(final Row r) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data)
            .setMessage(R.string.confirm_clear_data)
            .setPositiveButton(R.string.clear_data, (d, w) -> new Thread(() -> {
                boolean ok = CloneUsage.clearDataAsUser(this, r.pkg, 0);
                runOnUiThread(() -> {
                    Toast.makeText(this, ok ? R.string.data_cleared
                        : R.string.restore_failed, Toast.LENGTH_LONG).show();
                    reload();
                });
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void editCategory(final Row r) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        com.google.android.material.textfield.TextInputEditText input =
            v.findViewById(R.id.rename);
        input.setText(BackupJob.category(this, r.pkg));
        input.setHint(R.string.category_hint);
        List<String> cats = BackupJob.allCategories(this);
        String[] names = cats.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category_title)
            .setView(v)
            .setItems(names.length > 0 ? names : null, (d, which) -> {
                BackupJob.setCategory(this, r.pkg, cats.get(which));
                reload();
            })
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String s = input.getText() == null ? ""
                    : input.getText().toString().trim();
                BackupJob.setCategory(this, r.pkg, s);
                reload();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // ---------------- adapter ----------------

    class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {
        private List<Row> all = new ArrayList<>();
        private List<Row> rows = new ArrayList<>();
        private final LayoutInflater inf =
            LayoutInflater.from(AppsActivity.this);

        void setAll(List<Row> l) {
            all = sort(l);
            apply();
        }

        List<Row> sort(List<Row> in) {
            List<Row> out = new ArrayList<>(in);
            Comparator<Row> c;
            switch (sortMode) {
                case SORT_BACKUP_DATE:
                    c = (a, b) -> Long.compare(b.newestBackupAt, a.newestBackupAt);
                    break;
                case SORT_COPIES:
                    c = (a, b) -> Integer.compare(b.cloneCount, a.cloneCount);
                    break;
                default:
                    c = (a, b) -> a.label.compareToIgnoreCase(b.label);
            }
            Collections.sort(out, c);
            return out;
        }

        void apply() {
            List<Row> next = new ArrayList<>();
            String q = query.toLowerCase(Locale.ROOT);
            for (Row r : all) {
                if (!q.isEmpty()
                        && !r.label.toLowerCase(Locale.ROOT).contains(q)
                        && !r.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                if (fAppType == 0 && r.isSystem) continue;
                if (fAppType == 1 && !r.isSystem) continue;
                if (fBackup == 0 && r.backupCount > 0) continue;
                if (fBackup == 1 && r.backupCount == 0) continue;
                if (fClones == 0 && r.cloneCount > 0) continue;
                if (fClones == 1 && r.cloneCount == 0) continue;
                if (fInstalled == 1 && !r.installed) continue;
                if (fInstalled == 0 && r.installed) continue;
                if (fEnabled == 1 || fEnabled == 0) {
                    boolean en = true;
                    try { en = ShellEngine.isBaseEnabled(AppsActivity.this, r.pkg); }
                    catch (Throwable ignore) { }
                    if (fEnabled == 1 && !en) continue;
                    if (fEnabled == 0 && en) continue;
                }
                next.add(r);
            }
            rows = next;
            notifyDataSetChanged();
            if (selMode) updateSelBar();
            try {
                findViewById(R.id.empty).setVisibility(
                    rows.isEmpty() ? View.VISIBLE : View.GONE);
            } catch (Throwable ignore) { }
        }

        int visibleCount() { return rows.size(); }

        @Override public int getItemCount() { return rows.size(); }
        @Override public Holder onCreateViewHolder(ViewGroup p, int t) {
            return new Holder(inf.inflate(R.layout.item_app_row, p, false));
        }
        @Override public void onBindViewHolder(Holder h, int pos) {
            final Row r = rows.get(pos);
            if (r.icon != null) h.icon.setImageDrawable(r.icon);
            else h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            h.pkg.setText(Bidi.isolate(r.pkg));
            h.name.setText(r.label);
            String d = r.backupCount > 0
                ? getString(R.string.backup_count_short, r.backupCount)
                : getString(R.string.detail_no_backup);
            if (r.cloneCount > 0) {
                d += " • " + getString(R.string.clones_count, r.cloneCount);
            }
            h.detail.setText(d);
            boolean sel = selected.contains(r.pkg);
            h.check.setVisibility(selMode ? View.VISIBLE : View.GONE);
            h.check.setChecked(sel);
            h.itemView.setAlpha(selMode && !sel ? 0.86f : 1f);
            h.more.setOnClickListener(x -> showAppSheet(r));
            // Selection mode: icon (and row) toggles selection; otherwise opens detail.
            h.iconBox.setOnClickListener(x -> {
                if (!selMode) { enterSelMode(r.pkg); return; }
                if (selected.contains(r.pkg)) selected.remove(r.pkg);
                else selected.add(r.pkg);
                if (selected.isEmpty()) exitSelMode();
                else { updateSelBar(); }
                notifyDataSetChanged();
            });
            h.itemView.setOnClickListener(x -> {
                if (selMode) {
                    if (selected.contains(r.pkg)) selected.remove(r.pkg);
                    else selected.add(r.pkg);
                    if (selected.isEmpty()) exitSelMode();
                    else { updateSelBar(); }
                    notifyDataSetChanged();
                } else {
                    AppDetailActivity.open(AppsActivity.this, r.pkg);
                }
            });
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView pkg, name, detail;
            final View more, iconBox;
            final com.google.android.material.checkbox.MaterialCheckBox check;
            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                pkg = v.findViewById(R.id.pkg);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
                more = v.findViewById(R.id.more);
                iconBox = v.findViewById(R.id.icon_box);
                check = v.findViewById(R.id.check);
            }
        }
    }
}
