package com.clonix.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage per clone, grouped by original app with per-app total.
 * Flat item = one clone (data+cache = real extra, shared code excluded).
 */
public class StorageActivity extends Activity {
    private CloneStore db;
    private PackageManager pm;
    private RecyclerView list;
    private StorageAdapter adapter;

    static class Row {
        String pkg;
        String label;
        Drawable icon;
        int userId;
        int slotIndex;
        String nickname;
        CloneUsage.Usage usage = new CloneUsage.Usage();
    }

    static class Group {
        String pkg;
        String baseLabel;
        Drawable baseIcon;
        List<Row> clones = new ArrayList<>();
        long totalExtra;
    }

    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_storage);
        db = new CloneStore(this);
        pm = getPackageManager();
        MaterialToolbar bar = findViewById(R.id.toolbar);
        bar.setTitle(R.string.storage_title);
        bar.setNavigationOnClickListener(v -> finish());
        // Backup-destination picker (consolidated from the old Dashboard).
        bar.inflateMenu(R.menu.storage_menu);
        bar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.m_dest) { showDestDialog(); return true; }
            return false;
        });
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StorageAdapter(this);
        list.setAdapter(adapter);
        reload();
    }

    @Override protected void onResume() { super.onResume(); reload(); }

    private void reload() {
        LinearProgressIndicator progress = findViewById(R.id.progress);
        if (progress != null) progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            // Phase 1: instant paint from cache (zero blocking) so the list
            // appears immediately; phase 2 fills uncached entries via batch.
            List<Group> fast = loadGroups(true);
            runOnUiThread(() -> {
                adapter.setGroups(fast);
                findViewById(R.id.empty).setVisibility(
                    countClones(fast) == 0 ? View.VISIBLE : View.GONE);
                updateSummary(fast);
            });
            List<Group> full = loadGroups(false);
            runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                adapter.setGroups(full);
                findViewById(R.id.empty).setVisibility(
                    countClones(full) == 0 ? View.VISIBLE : View.GONE);
                updateSummary(full);
                checkUsagePermissionHint(full);
            });
        }).start();
    }

    /** Volume picker + move-existing-archives prompt (from old Dashboard). */
    private void showDestDialog() {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<ShellEngine.Volume> vols = CloneEngine.backupVolumes();
            final ShellEngine.Comp comp = ShellEngine.detectComp();
            String curTmp = ShellEngine.BACKUP_DIR;
            try { curTmp = ShellEngine.resolveDestDir(BackupJob.dest(this)); }
            catch (Throwable ignore) { }
            final String cur = curTmp;
            runOnUiThread(() -> {
                String[] names = new String[vols.size()];
                int sel = 0;
                for (int i = 0; i < vols.size(); i++) {
                    ShellEngine.Volume vol = vols.get(i);
                    String free = vol.freeBytes >= 0
                        ? CloneUsage.formatSize(this, vol.freeBytes) : "?";
                    String info = vol.internal ? ShellEngine.BACKUP_DIR : vol.path;
                    names[i] = vol.label + "\n" + info + "  \u2022  " + free;
                    String vp = vol.internal ? ShellEngine.BACKUP_DIR : vol.path;
                    if (vp.equals(cur)) sel = i;
                }
                final int selF = sel;
                new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.backup_dest) + "  \u2022  " + comp.name)
                    .setSingleChoiceItems(names, selF, null)
                    .setPositiveButton(R.string.confirm, (d, w) -> {
                        androidx.appcompat.app.AlertDialog ad =
                            (androidx.appcompat.app.AlertDialog) d;
                        int which = ad.getListView().getCheckedItemPosition();
                        if (which >= 0 && which < vols.size()) {
                            applyDest(vols.get(which), cur);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    private void applyDest(final ShellEngine.Volume vol, final String curDir) {
        final String next = vol.internal ? BackupJob.DEST_INTERNAL : vol.path;
        String nextDir = vol.internal ? ShellEngine.BACKUP_DIR : vol.path;
        if (nextDir.equals(curDir)) {
            BackupJob.setDest(this, next);
            return;
        }
        new Thread(() -> {
            int have = 0;
            try { have = ShellEngine.listFullBackups(curDir, null).size(); }
            catch (Throwable ignore) { }
            final int haveF = have;
            final String nextDirF = nextDir;
            runOnUiThread(() -> {
                if (haveF == 0) {
                    BackupJob.setDest(this, next);
                    return;
                }
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.backup_dest)
                    .setMessage(getString(R.string.move_backups, haveF))
                    .setPositiveButton(R.string.move_yes, (d, w) -> {
                        Toast.makeText(this, R.string.moving, Toast.LENGTH_SHORT)
                            .show();
                        new Thread(() -> {
                            final int moved = ShellEngine.moveBackups(curDir, nextDirF);
                            runOnUiThread(() -> {
                                BackupJob.setDest(this, next);
                                Toast.makeText(this,
                                    getString(R.string.moved, moved),
                                    Toast.LENGTH_LONG).show();
                            });
                        }).start();
                    })
                    .setNeutralButton(R.string.move_no, (d, w) ->
                        BackupJob.setDest(this, next))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    private static int countClones(List<Group> groups) {
        int n = 0;
        for (Group g : groups) n += g.clones.size();
        return n;
    }

    private void updateSummary(List<Group> groups) {
        long totalExtra = 0;
        int count = 0;
        for (Group g : groups) { totalExtra += g.totalExtra; count += g.clones.size(); }
        TextView summary = findViewById(R.id.summary);
        summary.setText(getString(R.string.storage_summary,
            count, CloneUsage.formatSize(this, totalExtra)));
    }

    private void checkUsagePermissionHint(List<Group> groups) {
        boolean anyDenied = false;
        outer: for (Group g : groups) for (Row r : g.clones)
            if (!r.usage.available) { anyDenied = true; break outer; }
        if (anyDenied && !hasUsageAccess()) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.usage_access_title)
                .setMessage(R.string.usage_access_msg)
                .setPositiveButton(R.string.open_settings, (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                    catch (Throwable ignore) {}
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        }
    }

    private boolean hasUsageAccess() {
        try {
            android.app.AppOpsManager aom = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return true; }
    }

    private List<Group> loadGroups(boolean cacheOnly) {
        List<CloneStore.Clone> all = db.listAll();
        java.util.Map<String, CloneUsage.Usage> batch = null;
        if (!cacheOnly && !all.isEmpty()) {
            // Single batch pass populates the 30s cache for every clone.
            batch = CloneUsage.queryBatch(this, all);
        }
        Map<String, Group> map = new LinkedHashMap<>();
        for (CloneStore.Clone cl : all) {
            Group g = map.get(cl.pkg);
            if (g == null) {
                g = new Group();
                g.pkg = cl.pkg;
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(cl.pkg, 0);
                    g.baseLabel = String.valueOf(pm.getApplicationLabel(ai));
                    try { g.baseIcon = pm.getApplicationIcon(ai); } catch (Throwable ignore) {}
                } catch (Throwable t) { g.baseLabel = cl.pkg; }
                map.put(cl.pkg, g);
            }
            Row r = new Row();
            r.pkg = cl.pkg; r.userId = cl.userId; r.slotIndex = cl.slotIndex; r.nickname = cl.nickname;
            r.label = (cl.nickname != null && !cl.nickname.isEmpty())
                ? cl.nickname : (g.baseLabel + " " + cl.slotIndex);
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(cl.pkg, 0);
                Drawable ic = pm.getApplicationIcon(ai);
                r.icon = BadgeRenderer.badgeForClone(this, ic, cl);
            } catch (Throwable t) { r.icon = g.baseIcon; }
            if (batch != null) {
                CloneUsage.Usage u = batch.get(cl.pkg + "#" + cl.userId);
                r.usage = u != null ? u : new CloneUsage.Usage();
            } else if (cacheOnly) {
                CloneUsage.Usage u = CloneUsage.peek(cl.pkg, cl.userId);
                r.usage = u != null ? u : new CloneUsage.Usage();
            } else {
                r.usage = CloneUsage.queryPackage(this, cl.pkg, cl.userId);
            }
            g.clones.add(r);
        }
        List<Group> groups = new ArrayList<>(map.values());
        for (Group g : groups) {
            Collections.sort(g.clones, (a, c) -> Long.compare(c.usage.extraBytes, a.usage.extraBytes));
            long sum = 0;
            for (Row r : g.clones) sum += r.usage.extraBytes;
            g.totalExtra = sum;
        }
        Collections.sort(groups, (a, c) -> Long.compare(c.totalExtra, a.totalExtra));
        return groups;
    }

    class StorageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_GROUP = 0;
        private static final int TYPE_ROW = 1;
        private final LayoutInflater inf;
        private final List<Object> items = new ArrayList<>();
        private long maxGroup = 1;
        private long maxExtra = 1;
        StorageAdapter(Context c) { inf = LayoutInflater.from(c); }

        void setGroups(List<Group> groups) {
            items.clear();
            maxGroup = 1; maxExtra = 1;
            for (Group g : groups) maxGroup = Math.max(maxGroup, g.totalExtra);
            for (Group g : groups) for (Row r : g.clones) maxExtra = Math.max(maxExtra, r.usage.extraBytes);
            for (Group g : groups) {
                items.add(g);
                items.addAll(g.clones);
            }
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return items.size(); }
        @Override public int getItemViewType(int p) { return items.get(p) instanceof Group ? TYPE_GROUP : TYPE_ROW; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == TYPE_GROUP) return new GroupHolder(inf.inflate(R.layout.item_storage_group, parent, false));
            return new RowHolder(inf.inflate(R.layout.item_storage, parent, false));
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            if (getItemViewType(pos) == TYPE_GROUP) bindGroup((GroupHolder) h, (Group) items.get(pos));
            else bindRow((RowHolder) h, (Row) items.get(pos));
        }

        class GroupHolder extends RecyclerView.ViewHolder {
            final ImageView icon; final TextView name, detail;
            final LinearProgressIndicator bar;
            GroupHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
                bar = v.findViewById(R.id.bar);
            }
        }

        class RowHolder extends RecyclerView.ViewHolder {
            final ImageView icon; final TextView name, detail;
            final LinearProgressIndicator bar;
            final MaterialButton clearCache, clearData, backup, del;
            RowHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
                bar = v.findViewById(R.id.bar);
                clearCache = v.findViewById(R.id.btn_clear_cache);
                clearData = v.findViewById(R.id.btn_clear_data);
                backup = v.findViewById(R.id.btn_backup);
                del = v.findViewById(R.id.btn_delete);
            }
        }

        private void bindGroup(GroupHolder h, Group g) {
            if (g.baseIcon != null) h.icon.setImageDrawable(g.baseIcon);
            h.name.setText(g.baseLabel);
            h.detail.setText(getString(R.string.group_total, g.clones.size(),
                CloneUsage.formatSize(StorageActivity.this, g.totalExtra)));
            h.bar.setProgressCompat((int) (100 * g.totalExtra / maxGroup), false);
        }

        private void bindRow(RowHolder h, Row r) {
            if (r.icon != null) h.icon.setImageDrawable(r.icon);
            h.name.setText(r.label);
            if (r.usage.available) {
                h.detail.setText(getString(R.string.storage_row,
                    CloneUsage.formatSize(StorageActivity.this, r.usage.extraBytes),
                    CloneUsage.formatSize(StorageActivity.this, r.usage.dataBytes),
                    CloneUsage.formatSize(StorageActivity.this, r.usage.cacheBytes),
                    r.userId));
                h.bar.setProgressCompat((int) (100 * r.usage.extraBytes / maxExtra), false);
            } else {
                h.detail.setText(getString(R.string.storage_unavailable, r.userId));
                h.bar.setProgressCompat(0, false);
            }
            h.clearCache.setOnClickListener(v -> new Thread(() -> {
                boolean ok = CloneUsage.clearCacheAsUser(StorageActivity.this, r.pkg, r.userId);
                runOnUiThread(() -> {
                    if (ok) { Toast.makeText(StorageActivity.this, R.string.cache_cleared, Toast.LENGTH_SHORT).show(); reload(); }
                    else openAppInfo(r.pkg, r.userId);
                });
            }).start());
            h.clearData.setOnClickListener(v -> new MaterialAlertDialogBuilder(StorageActivity.this)
                .setTitle(r.label)
                .setMessage(R.string.confirm_clear_data)
                .setPositiveButton(R.string.clear_data, (d, w) -> new Thread(() -> {
                    boolean ok = CloneUsage.clearDataAsUser(StorageActivity.this, r.pkg, r.userId);
                    runOnUiThread(() -> {
                        if (ok) { Toast.makeText(StorageActivity.this, R.string.data_cleared, Toast.LENGTH_SHORT).show(); reload(); }
                        else openAppInfo(r.pkg, r.userId);
                    });
                }).start())
                .setNegativeButton(R.string.cancel, null)
                .show());
            h.backup.setOnClickListener(v -> {
                Toast.makeText(StorageActivity.this, R.string.backing_up,
                    Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try {
                        String path = CloneEngine.backupClone(
                            StorageActivity.this, r.pkg, r.userId);
                        String size = path;
                        try {
                            for (ShellEngine.Backup bk
                                    : CloneEngine.listBackups(r.pkg)) {
                                if (bk.path.equals(path) && bk.size >= 0) {
                                    size = CloneUsage.formatSize(
                                        StorageActivity.this, bk.size);
                                    break;
                                }
                            }
                        } catch (Throwable ignore) { }
                        final String done =
                            getString(R.string.backup_done, size);
                        runOnUiThread(() -> Toast.makeText(StorageActivity.this,
                            done, Toast.LENGTH_LONG).show());
                    } catch (Throwable t) {
                        runOnUiThread(() -> Toast.makeText(StorageActivity.this,
                            getString(R.string.backup_failed) + ": " + t.getMessage(),
                            Toast.LENGTH_LONG).show());
                    }
                }).start();
            });
            h.del.setOnClickListener(v -> new MaterialAlertDialogBuilder(StorageActivity.this)
                .setTitle(r.label)
                .setMessage(R.string.delete)
                .setPositiveButton(R.string.delete, (d, w) -> new Thread(() -> {
                    CloneEngine.deleteClone(StorageActivity.this, db, r.pkg, r.userId);
                    runOnUiThread(() -> { Toast.makeText(StorageActivity.this, R.string.clone_deleted, Toast.LENGTH_SHORT).show(); reload(); });
                }).start())
                .setNegativeButton(R.string.cancel, null)
                .show());
        }
    }

    /**
     * Fallback: open App-Info FOR THE CLONE'S user (never the owner's),
     * so manual clear/cache there affects the right copy.
     */
    private void openAppInfo(String pkg, int userId) {
        openAppInfoStatic(this, pkg, userId);
    }

    static void openAppInfoStatic(Context c, String pkg, int userId) {
        try {
            String comp = ShellEngine.resolveAppDetails(pkg, userId);
            if (comp != null) {
                String[] pc = ShellEngine.splitComponent(comp);
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
                i.setClassName(pc[0], pc[1]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                SysApi.startActivityAsUser(c, i, userId);
                return;
            }
        } catch (Throwable ignore) { }
        try {
            Intent fb = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg));
            fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(fb);
        } catch (Throwable ignore) {}
    }
}
