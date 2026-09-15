package com.clonix.app;

import android.app.Activity;
import android.content.Context;
import android.os.PowerManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backup center v2: base app + clones by default, selectable parts,
 * blacklist, categories, batch backup/restore, destination choice
 * (internal / SD / OTG), and system-wide specials (SMS / call-log / Wi-Fi).
 */
public class BackupCenterActivity extends Activity {
    static class Item {
        static final int SPECIAL = 0, APP = 1, CLONE = 2;
        int type;
        String kind;          // SPECIAL kind
        String pkg;           // APP/CLONE
        String label;
        Drawable icon;
        int userId = -1;      // CLONE user, APP base = 0
        String nickname;
        int slotIndex;
        int backupCount;
        long newestBackupAt;
        long appUpdatedAt;
        boolean blacklisted;
        String category = "";
    }

    private CloneStore db;
    private PackageManager pm;
    private BackupAdapter adapter;
    private final Set<String> checked = new HashSet<>();
    private String query = "";
    /** status filter: 0=all 1=no backup 2=outdated 3=blocked. */
    private int filterMode = 0;
    private String baseBackupTxt = "";
    private String baseRestoreTxt = "";

    static String key(Item it) {
        if (it.type == Item.SPECIAL) return "special:" + it.kind;
        return it.pkg + "#" + it.userId;
    }

    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_backup);
        NavHelper.setup(this, R.id.nav_sync);
        db = new CloneStore(this);
        pm = getPackageManager();
        MaterialToolbar bar = findViewById(R.id.toolbar);
        bar.setTitle(R.string.backup_center);
        bar.setNavigationOnClickListener(v -> finish());
        bar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.m_archives) {
                startActivity(new Intent(this, ArchivesActivity.class));
                return true;
            }
            if (item.getItemId() == R.id.m_select_all) {
                selectAll(true);
                return true;
            }
            if (item.getItemId() == R.id.m_clear_sel) {
                selectAll(false);
                return true;
            }
            if (item.getItemId() == R.id.m_schedule) {
                showScheduleDialog();
                return true;
            }
            if (item.getItemId() == R.id.m_password) {
                showPasswordDialog();
                return true;
            }
            if (item.getItemId() == R.id.m_comp) {
                showCompDialog();
                return true;
            }
            if (item.getItemId() == R.id.m_migrate) {
                startActivity(new Intent(this, ArchivesActivity.class)
                    .putExtra("migrateNow", true));
                return true;
            }
            return false;
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BackupAdapter(this);
        list.setAdapter(adapter);
        TextInputEditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s == null ? "" : s.toString().trim();
                adapter.applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        findViewById(R.id.btn_batch_backup).setOnClickListener(v -> batchBackup());
        findViewById(R.id.btn_batch_restore).setOnClickListener(v -> batchRestore());
        findViewById(R.id.btn_opts).setOnClickListener(v -> showOptsDialog());
        findViewById(R.id.btn_dest).setOnClickListener(v -> showDestDialog());
        try {
            android.widget.Button bb = findViewById(R.id.btn_batch_backup);
            android.widget.Button br = findViewById(R.id.btn_batch_restore);
            if (bb != null) baseBackupTxt = String.valueOf(bb.getText());
            if (br != null) baseRestoreTxt = String.valueOf(br.getText());
        } catch (Throwable ignore) { }
        try {
            com.google.android.material.chip.ChipGroup chips =
                findViewById(R.id.chips);
            chips.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.contains(R.id.chip_nobackup)) filterMode = 1;
                else if (checkedIds.contains(R.id.chip_stale)) filterMode = 2;
                else if (checkedIds.contains(R.id.chip_blocked)) filterMode = 3;
                else filterMode = 0;
                adapter.applyFilter();
            });
        } catch (Throwable ignore) { }
        // Empty-state CTA: select everything, then run the normal batch flow.
        try {
            findViewById(R.id.empty_cta).setOnClickListener(v -> {
                selectAll(true);
                batchBackup();
            });
        } catch (Throwable ignore) { }
        if (Prefs.firstRun(this)) showFirstRun();
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        try { NavHelper.refreshBadges(this); } catch (Throwable ignore) { }
        reload();
    }

    /** One-time value intro: what the center does, skippable, never again. */
    private void showFirstRun() {
        try {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.first_title)
                .setMessage(R.string.first_msg)
                .setPositiveButton(R.string.got_it, (d, w) -> {
                    try { Prefs.clearFirstRun(this); } catch (Throwable ignore) { }
                })
                .setOnDismissListener(d -> {
                    try { Prefs.clearFirstRun(this); } catch (Throwable ignore) { }
                })
                .show();
        } catch (Throwable ignore) {
            try { Prefs.clearFirstRun(this); } catch (Throwable ig) { }
        }
    }

    private void setBusy(boolean busy, String s) {
        LinearProgressIndicator p = findViewById(R.id.progress);
        if (p != null) p.setVisibility(busy ? View.VISIBLE : View.GONE);
        TextView st = findViewById(R.id.status);
        if (st != null) {
            st.setVisibility((busy && s != null) || (!busy && s != null)
                ? View.VISIBLE : View.GONE);
            if (s != null) st.setText(s);
        }
    }

    private void reload() {
        setBusy(true, getString(R.string.loading));
        new Thread(() -> {
            List<Item> items = loadItems();
            runOnUiThread(() -> {
                setBusy(false, null);
                adapter.setItems(items);
                findViewById(R.id.empty).setVisibility(
                    items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private List<Item> loadItems() {
        List<Item> out = new ArrayList<>();
        // Specials first.
        List<ShellEngine.Backup> specials = CloneEngine.listFullBackups(this, "special");
        Map<String, Integer> specialCount = new HashMap<>();
        for (ShellEngine.Backup bk : specials) {
            String n = bk.path.substring(bk.path.lastIndexOf('/') + 1);
            for (String k : ShellEngine.SPECIAL_KINDS) {
                if (n.startsWith("special_" + k + "_")) {
                    Integer c = specialCount.get(k);
                    specialCount.put(k, (c == null ? 0 : c) + 1);
                }
            }
        }
        String[] kinds = ShellEngine.SPECIAL_KINDS;
        int[] names = {R.string.special_sms, R.string.special_calllog,
            R.string.special_wifi, R.string.special_wallpaper};
        int[] sums = {R.string.special_sms_sum, R.string.special_calllog_sum,
            R.string.special_wifi_sum, R.string.special_wallpaper_sum};
        for (int i = 0; i < kinds.length; i++) {
            Item it = new Item();
            it.type = Item.SPECIAL;
            it.kind = kinds[i];
            it.label = getString(names[i]);
            it.nickname = getString(sums[i]);
            Integer c = specialCount.get(kinds[i]);
            it.backupCount = c == null ? 0 : c;
            out.add(it);
        }
        // Apps: launcher universe (same as main list) + tracked clones.
        Map<String, Item> apps = new LinkedHashMap<>();
        Map<String, List<CloneStore.Clone>> clonesByPkg = new HashMap<>();
        for (CloneStore.Clone cl : db.listAll()) {
            List<CloneStore.Clone> l = clonesByPkg.get(cl.pkg);
            if (l == null) { l = new ArrayList<>(); clonesByPkg.put(cl.pkg, l); }
            l.add(cl);
        }
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(ri.activityInfo.packageName, 0);
                    if (!CloneEngine.isCloneable(this, ai)
                            && !CloneEngine.isSystemVisible(this, ai)) continue;
                    if (apps.containsKey(ai.packageName)) continue;
                    Item it = new Item();
                    it.type = Item.APP;
                    it.pkg = ai.packageName;
                    it.label = String.valueOf(pm.getApplicationLabel(ai));
                    try { it.icon = pm.getApplicationIcon(ai); } catch (Throwable ignore) { }
                    apps.put(ai.packageName, it);
                } catch (Throwable ignore) { }
            }
        } catch (Throwable ignore) { }
        for (String pkg : clonesByPkg.keySet()) {
            if (!apps.containsKey(pkg)) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    Item it = new Item();
                    it.type = Item.APP;
                    it.pkg = pkg;
                    it.label = String.valueOf(pm.getApplicationLabel(ai));
                    try { it.icon = pm.getApplicationIcon(ai); } catch (Throwable ignore) { }
                    apps.put(pkg, it);
                } catch (Throwable ignore) { }
            }
        }
        // Backup counts + newest timestamps per package (one listing total).
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> newest = new HashMap<>();
        try {
            // One listing total (all extensions incl. legacy .tar.gz), parsed here.
            for (ShellEngine.Backup bk : CloneEngine.listFullBackups(this, null)) {
                String n = bk.path.substring(bk.path.lastIndexOf('/') + 1);
                if (n.startsWith("special_")) continue;
                int u = n.indexOf("_u");
                if (u > 0) {
                    String p = n.substring(0, u);
                    Integer c = counts.get(p);
                    counts.put(p, (c == null ? 0 : c) + 1);
                    long t = ShellEngine.backupTimeOf(n);
                    Long prev = newest.get(p);
                    if (prev == null || t > prev) newest.put(p, t);
                }
            }
        } catch (Throwable ignore) { }
        for (Item app : apps.values()) {
            app.blacklisted = BackupJob.isBlacklisted(this, app.pkg);
            app.category = BackupJob.category(this, app.pkg);
            Integer c = counts.get(app.pkg);
            app.backupCount = c == null ? 0 : c;
            Long nt = newest.get(app.pkg);
            app.newestBackupAt = nt == null ? 0 : nt;
            // Stale = app updated after its newest backup (Neo-style).
            try {
                app.appUpdatedAt = pm.getPackageInfo(app.pkg, 0).lastUpdateTime;
            } catch (Throwable ignore) { app.appUpdatedAt = 0; }
            out.add(app);
            List<CloneStore.Clone> cls = clonesByPkg.get(app.pkg);
            if (cls != null) {
                Collections.sort(cls, (a, x) -> Integer.compare(a.slotIndex, x.slotIndex));
                for (CloneStore.Clone cl : cls) {
                    Item it = new Item();
                    it.type = Item.CLONE;
                    it.pkg = cl.pkg;
                    it.userId = cl.userId;
                    it.slotIndex = cl.slotIndex;
                    it.nickname = (cl.nickname != null && !cl.nickname.isEmpty())
                        ? cl.nickname : (app.label + " " + cl.slotIndex);
                    it.label = it.nickname;
                    it.icon = app.icon;
                    try {
                        it.icon = BadgeRenderer.badgeForClone(this, app.icon, cl);
                    } catch (Throwable ignore) { }
                    out.add(it);
                }
            }
            // Base (owner) item right after the app header when clones exist.
            if (cls != null && !cls.isEmpty()) {
                Item base = new Item();
                base.type = Item.CLONE;
                base.pkg = app.pkg;
                base.userId = 0;
                base.slotIndex = 0;
                base.nickname = getString(R.string.backup_base_item);
                base.label = base.nickname;
                base.icon = app.icon;
                out.add(out.size() - cls.size(), base);
            }
        }
        return out;
    }

    // ---------------- batch ----------------

    private List<Item> checkedItems() {
        List<Item> res = new ArrayList<>();
        for (Item it : adapter.base) {
            if (!checked.contains(key(it))) continue;
            if (it.type == Item.APP) continue; // headers select children only
            if (it.type == Item.CLONE && BackupJob.isBlacklisted(this, it.pkg)) continue;
            res.add(it);
        }
        return res;
    }

    private void selectAll(boolean all) {
        checked.clear();
        if (all) {
            for (Item it : adapter.base) {
                if (it.type == Item.CLONE
                        && !BackupJob.isBlacklisted(this, it.pkg)) {
                    checked.add(key(it));
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateSelCount();
    }

    /** batch bar: buttons carry the live selection count. */
    private void updateSelCount() {
        try {
            int n = checkedItems().size();
            android.widget.Button bb = findViewById(R.id.btn_batch_backup);
            android.widget.Button br = findViewById(R.id.btn_batch_restore);
            if (bb != null) {
                bb.setText(n > 0 ? baseBackupTxt + " (" + n + ")"
                    : baseBackupTxt);
            }
            if (br != null) {
                br.setText(n > 0 ? baseRestoreTxt + " (" + n + ")"
                    : baseRestoreTxt);
            }
        } catch (Throwable ignore) { }
    }

    private void batchBackup() {
        List<Item> sel = checkedItems();
        List<Item> targets = new ArrayList<>();
        for (Item it : sel) {
            if (it.type == Item.CLONE) targets.add(it);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        final BackupJob opts = BackupJob.defaults(this);
        if (opts.enc && !CryptoVault.hasSessionPassword()) {
            askPassword(() -> batchBackup());
            return;
        }
        List<Preflight.Target> pts = new ArrayList<>();
        for (Item it : targets) pts.add(new Preflight.Target(it.pkg, it.userId, it.label));
        final List<Item> targetsF = targets;
        Preflight.checkBatch(this, pts, go -> runBatchBackup(targetsF, go));
    }

    private void runBatchBackup(List<Item> targets, List<Preflight.Target> go) {
        final BackupJob opts = BackupJob.defaults(this);
        List<Item> items = new ArrayList<>();
        for (Item it : targets) {
            for (Preflight.Target t : go) {
                if (t.pkg.equals(it.pkg) && t.userId == it.userId) {
                    items.add(it);
                    break;
                }
            }
        }
        final List<Item> itemsF = items;
        setBusy(true, getString(R.string.backing_up));
        final Context app = getApplicationContext();
        final String title = getString(R.string.backup_center);
        new Thread(() -> {
            int ok = 0;
            List<String> fails = new ArrayList<>();
            char[] pw = opts.enc ? CryptoVault.sessionPassword() : null;
            int skipped = 0;
            // Serialized engine lane + wakelock: screen-off can't stall the batch.
            synchronized (OpsQueue.LOCK) {
                PowerManager.WakeLock wl = null;
                try {
                    PowerManager pm = (PowerManager)
                        app.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "Clonix:batch");
                        wl.setReferenceCounted(false);
                        wl.acquire(30 * 60 * 1000L);
                    }
                } catch (Throwable ignore) { }
                try {
                    Notify.batchProgress(app, title, 0, itemsF.size());
                    for (int i = 0; i < itemsF.size(); i++) {
                        Item it = itemsF.get(i);
                        // Determinate counter: x/y in status + notification bar.
                        updateStatus(getString(R.string.backing_up_count,
                            it.label, i + 1, itemsF.size()));
                        Notify.batchProgress(app, title, i, itemsF.size());
                        try {
                            ShellEngine.FullResult r;
                            if (opts.enc) {
                                r = CloneEngine.backupAutoEnc(
                                    app, it.pkg, it.userId, opts.clone(), pw);
                            } else {
                                r = CloneEngine.backupAuto(
                                    app, it.pkg, it.userId, opts.clone());
                            }
                            if (r != null && r.level == -2) skipped++;
                            else ok++;
                        } catch (Throwable t) {
                            fails.add(it.label + ": " + t.getMessage());
                        }
                        Notify.batchProgress(app, title, i + 1, itemsF.size());
                    }
                } finally {
                    try {
                        if (wl != null) wl.release();
                    } catch (Throwable ignore) { }
                    Notify.doneProgress(app, Notify.ID_PROG);
                }
            }
            if (skipped > 0) {
                fails.add(0, getString(R.string.skipped_unchanged, skipped));
            }
            final int okF = ok;
            final List<String> failsF = fails;
            runOnUiThread(() -> {
                setBusy(false, null);
                showSummary(true, okF, failsF);
                reload();
            });
            EngineLog.i(app, "batch-backup", okF + " ok, " + failsF.size() + " failed");
        }).start();
    }

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
                try { input.setText(""); } catch (Throwable ignore) { }
                if (then != null) then.run();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showCompDialog() {
        String cur = BackupJob.compPref(this);
        String[] names = {
            getString(R.string.comp_auto),
            getString(R.string.comp_gzip),
            getString(R.string.comp_zstd)};
        String[] vals = {"auto", "gzip", "zstd"};
        int sel = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].equals(cur)) sel = i;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.comp_title)
            .setSingleChoiceItems(names, sel, null)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                androidx.appcompat.app.AlertDialog ad =
                    (androidx.appcompat.app.AlertDialog) d;
                int which = ad.getListView().getCheckedItemPosition();
                if (which >= 0 && which < vals.length) {
                    BackupJob.setCompPref(this, vals[which]);
                    Toast.makeText(this,
                        getString(R.string.detail_comp,
                            ShellEngine.detectComp().name),
                        Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showPasswordDialog() {
        boolean has = CryptoVault.hasSessionPassword();
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pw_title)
            .setMessage(has ? R.string.pw_locked_sum : R.string.pw_unlocked_sum)
            .setPositiveButton(has ? R.string.pw_lock : R.string.pw_set,
                (d, w) -> {
                    if (has) {
                        CryptoVault.clearSessionPassword();
                        Toast.makeText(this, R.string.pw_locked,
                            Toast.LENGTH_SHORT).show();
                    } else {
                        askPassword(null);
                    }
                })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showScheduleDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_schedule, null);
        MaterialSwitch on = v.findViewById(R.id.sched_on);
        MaterialSwitch chg = v.findViewById(R.id.sched_charging);
        MaterialSwitch idle = v.findViewById(R.id.sched_idle);
        TextInputEditText hours = v.findViewById(R.id.sched_hours);
        final TextView uh = v.findViewById(R.id.sched_unit_h);
        final TextView ud = v.findViewById(R.id.sched_unit_d);
        final String[] unit = {BackupScheduler.intervalUnit(this)};
        on.setChecked(BackupScheduler.enabled(this));
        chg.setChecked(BackupScheduler.requireCharging(this));
        idle.setChecked(BackupScheduler.requireIdle(this));
        hours.setText(String.valueOf(BackupScheduler.intervalValue(this)));
        Runnable restyle = () -> {
            boolean d = "d".equals(unit[0]);
            uh.setSelected(!d);
            ud.setSelected(d);
            uh.setBackgroundResource(d ? R.drawable.bg_chip_toggle
                : R.drawable.bg_chip_selected);
            ud.setBackgroundResource(d ? R.drawable.bg_chip_selected
                : R.drawable.bg_chip_toggle);
            try {
                int onC = uh.getContext().getColor(R.color.onPrimary);
                int offC = uh.getContext().getColor(R.color.onSurfaceVariant);
                uh.setTextColor(d ? offC : onC);
                ud.setTextColor(d ? onC : offC);
            } catch (Throwable ignore) { }
        };
        restyle.run();
        uh.setOnClickListener(x -> { unit[0] = "h"; restyle.run(); });
        ud.setOnClickListener(x -> { unit[0] = "d"; restyle.run(); });
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.schedule_title)
            .setMessage(R.string.schedule_sum)
            .setView(v)
            .setPositiveButton(R.string.save_default, (d, w) -> {
                int val = 24;
                try {
                    val = Integer.parseInt(hours.getText() == null ? "24"
                        : hours.getText().toString().trim());
                } catch (Throwable ignore) { }
                if (val < 1) val = 1;
                if ("d".equals(unit[0]) && val > 31) val = 31;
                if ("h".equals(unit[0]) && val > 720) val = 720;
                BackupScheduler.save(this, on.isChecked(), val, unit[0],
                    chg.isChecked(), idle.isChecked());
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(R.string.schedule_run_now, (d, w) -> runScheduleNow())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void runScheduleNow() {
        setBusy(true, getString(R.string.backing_up));
        new Thread(() -> {
            final String res = BackupScheduler.runOnce(this);
            runOnUiThread(() -> {
                setBusy(false, null);
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.schedule_title)
                    .setMessage(res)
                    .setPositiveButton(R.string.got_it, null)
                    .show();
                reload();
            });
        }).start();
    }

    private void batchRestore() {
        List<Item> sel = checkedItems();
        List<Item> targets = new ArrayList<>();
        for (Item it : sel) {
            if (it.type == Item.CLONE) targets.add(it);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        BackupJob opts = BackupJob.defaults(this);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_selected)
            .setMessage(getString(R.string.backup_confirm_overwrite))
            .setPositiveButton(R.string.restore, (d, w) -> runBatchRestore(targets, opts))
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void runBatchRestore(List<Item> targets, BackupJob opts) {
        setBusy(true, getString(R.string.restoring));
        new Thread(() -> {
            int ok = 0;
            boolean reboot = false;
            List<String> fails = new ArrayList<>();
            for (Item it : targets) {
                updateStatus(getString(R.string.restoring) + " " + it.label);
                try {
                    ShellEngine.Backup newest = CloneEngine.newestBackup(
                        this, it.pkg, it.userId);
                    if (newest == null) throw new Exception(
                        getString(R.string.no_backups));
                    ShellEngine.RestoreResult r = CloneEngine.restoreFull(
                        this, db, it.pkg, it.userId, newest.path, opts.clone());
                    if (!r.failures.isEmpty()) {
                        fails.add(it.label + ": " + r.failures.get(0));
                    } else ok++;
                    if (r.rebootNeeded) reboot = true;
                } catch (Throwable t) {
                    fails.add(it.label + ": " + t.getMessage());
                }
            }
            final int okF = ok;
            final boolean rebootF = reboot;
            final List<String> failsF = fails;
            runOnUiThread(() -> {
                setBusy(false, null);
                showSummary(false, okF, failsF);
                if (rebootF) showRebootNote();
                reload();
            });
        }).start();
    }

    private void updateStatus(final String s) {
        runOnUiThread(() -> {
            TextView st = findViewById(R.id.status);
            if (st != null) { st.setVisibility(View.VISIBLE); st.setText(s); }
        });
    }

    private void showSummary(boolean backup, int ok, List<String> fails) {
        StringBuilder sb = new StringBuilder();
        sb.append(backup ? getString(R.string.backup_summary, ok)
            : getString(R.string.restore_summary, ok));
        if (!fails.isEmpty()) {
            sb.append("\n\n");
            int n = 0;
            for (String f : fails) {
                if (n++ >= 5) { sb.append("…"); break; }
                sb.append("• ").append(f).append("\n");
            }
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(backup ? R.string.backup : R.string.restore)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.got_it, null)
            .show();
        if (fails.isEmpty()) {
            Notify.ok(this, getString(backup ? R.string.backup : R.string.restore),
                sb.toString());
        } else {
            Notify.fail(this, getString(backup ? R.string.backup : R.string.restore),
                sb.toString());
        }
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

    // ---------------- options + destination ----------------

    private void showOptsDialog() {
        final BackupJob tmp = BackupJob.defaults(this);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_backup_opts, null);
        MaterialSwitch d = v.findViewById(R.id.opt_data);
        MaterialSwitch ch = v.findViewById(R.id.opt_cache);
        MaterialSwitch pr = v.findViewById(R.id.opt_priv);
        MaterialSwitch pe = v.findViewById(R.id.opt_perms);
        MaterialSwitch ao = v.findViewById(R.id.opt_appops);
        MaterialSwitch ss = v.findViewById(R.id.opt_ssaid);
        MaterialSwitch en = v.findViewById(R.id.opt_enc);
        MaterialSwitch ic = v.findViewById(R.id.opt_incr);
        d.setChecked(tmp.data); ch.setChecked(tmp.cache); pr.setChecked(tmp.privateData);
        pe.setChecked(tmp.perms); ao.setChecked(tmp.appops); ss.setChecked(tmp.ssaid);
        en.setChecked(tmp.enc);
        ic.setChecked(tmp.incr);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_opts)
            .setView(v)
            .setPositiveButton(R.string.save_default, (x, y) -> {
                tmp.data = d.isChecked(); tmp.cache = ch.isChecked();
                tmp.privateData = pr.isChecked(); tmp.perms = pe.isChecked();
                tmp.appops = ao.isChecked(); tmp.ssaid = ss.isChecked();
                tmp.enc = en.isChecked();
                tmp.incr = ic.isChecked();
                tmp.saveAsDefaults(this);
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showDestDialog() {
        setBusy(true, getString(R.string.loading));
        new Thread(() -> {
            final List<ShellEngine.Volume> vols = CloneEngine.backupVolumes();
            final ShellEngine.Comp comp = ShellEngine.detectComp();
            runOnUiThread(() -> {
                setBusy(false, null);
                String[] names = new String[vols.size()];
                int sel = 0;
                String cur = BackupJob.dest(this);
                for (int i = 0; i < vols.size(); i++) {
                    ShellEngine.Volume vol = vols.get(i);
                    String free = vol.freeBytes >= 0
                        ? CloneUsage.formatSize(this, vol.freeBytes) : "?";
                    names[i] = vol.label + "\n" + vol.path + "  •  " + free;
                    if ((vol.internal && BackupJob.DEST_INTERNAL.equals(cur))
                            || (!vol.internal && vol.path.equals(cur))) sel = i;
                }
                final int selF = sel;
                new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.backup_dest) + "  •  " + comp.name)
                    .setSingleChoiceItems(names, selF, null)
                    .setPositiveButton(R.string.confirm, (d, w) -> {
                        androidx.appcompat.app.AlertDialog ad =
                            (androidx.appcompat.app.AlertDialog) d;
                        int which = ad.getListView().getCheckedItemPosition();
                        if (which >= 0 && which < vols.size()) {
                            ShellEngine.Volume vol = vols.get(which);
                            BackupJob.setDest(this,
                                vol.internal ? BackupJob.DEST_INTERNAL : vol.path);
                            reload();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    // ---------------- per-item actions ----------------

    private void toggleBlacklist(Item app) {
        boolean now = !BackupJob.isBlacklisted(this, app.pkg);
        BackupJob.setBlacklisted(this, app.pkg, now);
        Toast.makeText(this, now ? R.string.blacklisted : R.string.unblacklisted,
            Toast.LENGTH_SHORT).show();
        reload();
    }

    private void editCategory(final Item app) {
        final TextInputEditText[] input = new TextInputEditText[1];
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        input[0] = v.findViewById(R.id.rename);
        input[0].setText(BackupJob.category(this, app.pkg));
        input[0].setHint(R.string.category_hint);
        List<String> cats = BackupJob.allCategories(this);
        String[] names = cats.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category_title)
            .setView(v)
            .setItems(names.length > 0 ? names : null, (d, which) -> {
                BackupJob.setCategory(this, app.pkg, cats.get(which));
                reload();
            })
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String s = input[0].getText() == null ? ""
                    : input[0].getText().toString().trim();
                BackupJob.setCategory(this, app.pkg, s);
                reload();
            })
            .setNeutralButton(R.string.clear, (d, w) -> {
                BackupJob.setCategory(this, app.pkg, "");
                reload();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void specialBackup(Item it) {
        Toast.makeText(this, R.string.backing_up, Toast.LENGTH_SHORT).show();
        setBusy(true, getString(R.string.backing_up) + " " + it.label);
        new Thread(() -> {
            try {
                final String path = CloneEngine.backupSpecial(this, it.kind);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    Toast.makeText(this, getString(R.string.backup_done, path),
                        Toast.LENGTH_LONG).show();
                    reload();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    Toast.makeText(this, getString(R.string.backup_failed) + ": "
                        + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void specialRestore(final Item it) {
        // Unified interactive wizard (picker -> confirm -> execute).
        RestoreWizard.startSpecial(this, it.kind, it.label);
    }

    private void cloneBackup(Item it) {
        final BackupJob opts = BackupJob.defaults(this);
        if (opts.enc && !CryptoVault.hasSessionPassword()) {
            askPassword(() -> cloneBackup(it));
            return;
        }
        Preflight.checkOne(this, it.pkg, it.userId, forceStopped -> runCloneBackup(it));
    }

    private void runCloneBackup(Item it) {
        final BackupJob opts = BackupJob.defaults(this);
        Toast.makeText(this, R.string.backing_up, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                ShellEngine.FullResult r;
                if (opts.enc) {
                    r = CloneEngine.backupFullEnc(this, it.pkg, it.userId,
                        opts.clone(), CryptoVault.sessionPassword());
                } else {
                    r = CloneEngine.backupFull(
                        this, it.pkg, it.userId, opts.clone());
                }
                // Bonus: APK copy next to the archive for bare-metal restores.
                String apkNote = "";
                try {
                    String apk = ShellEngine.backupApk(it.pkg,
                        r.archive.substring(0, r.archive.lastIndexOf('/')));
                    if (apk != null) apkNote = " + APK";
                } catch (Throwable ignore) { }
                final String done = getString(R.string.backup_done, r.comp + apkNote);
                runOnUiThread(() -> {
                    Toast.makeText(this, done, Toast.LENGTH_LONG).show();
                    reload();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.backup_failed) + ": " + t.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void cloneRestorePicker(final Item it) {
        // Unified interactive wizard (preview -> confirm -> execute).
        RestoreWizard.startForClone(this, db, it.pkg, it.userId, it.label,
            () -> reload());
    }

    /** Outdated backup: app updated after its newest archive. */
    static boolean isStale(Item it) {
        return it.backupCount > 0 && it.appUpdatedAt > 0
            && it.newestBackupAt > 0 && it.appUpdatedAt > it.newestBackupAt;
    }

    // ---------------- adapter ----------------

    class BackupAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int T_SPECIAL = 0, T_APP = 1, T_CLONE = 2;
        private final LayoutInflater inf;
        private List<Item> base = new ArrayList<>();
        private List<Item> rows = new ArrayList<>();
        BackupAdapter(Context c) { inf = LayoutInflater.from(c); }
        void setItems(List<Item> items) {
            base = items;
            applyFilter();
        }
        void applyFilter() {
            List<Item> next = new ArrayList<>();
            Set<String> visiblePkgs = new HashSet<>();
            for (Item it : base) {
                if (it.type == Item.SPECIAL) {
                    if (filterMode == 0
                            && (query.isEmpty() || it.label.contains(query))) {
                        next.add(it);
                    }
                } else if (it.type == Item.APP) {
                    if (!query.isEmpty() && !it.label.contains(query)
                            && !it.pkg.contains(query)) {
                        continue;
                    }
                    if (filterMode == 1 && it.backupCount > 0) continue;
                    if (filterMode == 2 && !isStale(it)) continue;
                    if (filterMode == 3 && !it.blacklisted) continue;
                    next.add(it);
                    visiblePkgs.add(it.pkg);
                }
            }
            for (Item it : base) {
                if (it.type == Item.CLONE && visiblePkgs.contains(it.pkg)) next.add(it);
            }
            rows = next;
            notifyDataSetChanged();
            updateSelCount();
            try {
                findViewById(R.id.empty).setVisibility(
                    rows.isEmpty() ? View.VISIBLE : View.GONE);
            } catch (Throwable ignore) { }
        }
        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int p) {
            Item it = rows.get(p);
            return it.type == Item.SPECIAL ? T_SPECIAL
                : it.type == Item.APP ? T_APP : T_CLONE;
        }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int t) {
            if (t == T_SPECIAL) return new SpecialHolder(
                inf.inflate(R.layout.item_backup_special, parent, false));
            if (t == T_APP) return new AppHolder(
                inf.inflate(R.layout.item_backup_app, parent, false));
            return new CloneHolder(
                inf.inflate(R.layout.item_backup_clone, parent, false));
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            Item it = rows.get(pos);
            if (h instanceof SpecialHolder) bindSpecial((SpecialHolder) h, it);
            else if (h instanceof AppHolder) bindApp((AppHolder) h, it);
            else bindClone((CloneHolder) h, it);
        }
        class SpecialHolder extends RecyclerView.ViewHolder {
            final TextView name, detail;
            final MaterialButton backup, restore;
            SpecialHolder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
                backup = v.findViewById(R.id.btn_backup);
                restore = v.findViewById(R.id.btn_restore);
            }
        }
        class AppHolder extends RecyclerView.ViewHolder {
            final CheckBox check;
            final ImageView icon;
            final TextView name, detail, cat;
            final MaterialButton backup, star;
            AppHolder(View v) {
                super(v);
                check = v.findViewById(R.id.check);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
                cat = v.findViewById(R.id.cat);
                backup = v.findViewById(R.id.btn_backup);
                star = v.findViewById(R.id.btn_star);
            }
        }
        class CloneHolder extends RecyclerView.ViewHolder {
            final CheckBox check;
            final ImageView icon;
            final TextView name, detail;
            final MaterialButton restore;
            CloneHolder(View v) {
                super(v);
                check = v.findViewById(R.id.check);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
                restore = v.findViewById(R.id.btn_restore);
            }
        }
        private void bindSpecial(SpecialHolder h, final Item it) {
            h.name.setText(it.label);
            h.detail.setText(it.nickname + " • "
                + getString(R.string.backup_count, it.backupCount));
            h.backup.setOnClickListener(v -> specialBackup(it));
            h.restore.setOnClickListener(v -> specialRestore(it));
        }
        private void bindApp(AppHolder h, final Item it) {
            if (it.icon != null) h.icon.setImageDrawable(it.icon);
            h.name.setText(it.label);
            String d = it.pkg + " • " + getString(R.string.backup_count, it.backupCount);
            if (it.blacklisted) d += " • " + getString(R.string.blacklisted);
            if (isStale(it)) d += " • " + getString(R.string.stale);
            h.detail.setText(d);
            if (it.category != null && !it.category.isEmpty()) {
                h.cat.setVisibility(View.VISIBLE);
                h.cat.setText("#" + it.category);
            } else h.cat.setVisibility(View.GONE);
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(checked.contains(key(it)));
            h.check.setOnCheckedChangeListener((b, c) -> toggleApp(it, c));
            h.backup.setOnClickListener(v -> {
                // App-level backup = base (owner) data + APK copy.
                Item base = new Item();
                base.type = Item.CLONE;
                base.pkg = it.pkg;
                base.userId = 0;
                base.label = it.label;
                cloneBackup(base);
            });
            h.star.setText(it.blacklisted ? R.string.blacklist_on : R.string.blacklist_off);
            h.star.setOnClickListener(v -> toggleBlacklist(it));
            h.itemView.setOnClickListener(v ->
                AppDetailActivity.open(BackupCenterActivity.this, it.pkg));
            h.itemView.setOnLongClickListener(v -> { editCategory(it); return true; });
        }
        private void toggleApp(Item app, boolean c) {
            for (Item it : base) {
                if (it.type == Item.CLONE && it.pkg.equals(app.pkg)) {
                    if (c) checked.add(key(it));
                    else checked.remove(key(it));
                }
            }
            if (c) checked.add(key(app));
            else checked.remove(key(app));
            notifyDataSetChanged();
            updateSelCount();
        }
        private void bindClone(CloneHolder h, final Item it) {
            if (it.icon != null) h.icon.setImageDrawable(it.icon);
            h.name.setText(it.label);
            h.detail.setText(it.pkg + " • u" + it.userId + " • "
                + getString(R.string.backup_count, countFor(it)));
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(checked.contains(key(it)));
            h.check.setOnCheckedChangeListener((b, c) -> {
                if (c) checked.add(key(it));
                else checked.remove(key(it));
                updateSelCount();
            });
            h.restore.setOnClickListener(v -> cloneRestorePicker(it));
        }
        private int countFor(Item it) {
            // counts shown on the app header; clones share it — recompute cheaply
            for (Item a : base) {
                if (a.type == Item.APP && a.pkg.equals(it.pkg)) return a.backupCount;
            }
            return 0;
        }
    }
}
