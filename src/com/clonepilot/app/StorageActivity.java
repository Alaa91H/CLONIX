package com.clonepilot.app;

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
    private CloneDatabase db;
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
        CloneStorageHelper.Usage usage = new CloneStorageHelper.Usage();
    }

    static class Group {
        String pkg;
        String baseLabel;
        Drawable baseIcon;
        List<Row> clones = new ArrayList<>();
        long totalExtra;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_storage);
        db = new CloneDatabase(this);
        pm = getPackageManager();
        MaterialToolbar bar = findViewById(R.id.toolbar);
        bar.setTitle(R.string.storage_title);
        bar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StorageAdapter(this);
        list.setAdapter(adapter);
        reload();
    }

    @Override protected void onResume() { super.onResume(); reload(); }

    private void reload() {
        new Thread(() -> {
            List<Group> groups = loadGroups();
            long totalExtra = 0;
            int count = 0;
            for (Group g : groups) { totalExtra += g.totalExtra; count += g.clones.size(); }
            final long totalF = totalExtra;
            final int countF = count;
            runOnUiThread(() -> {
                adapter.setGroups(groups);
                findViewById(R.id.empty).setVisibility(countF == 0 ? View.VISIBLE : View.GONE);
                TextView summary = findViewById(R.id.summary);
                summary.setText(getString(R.string.storage_summary,
                    countF, CloneStorageHelper.formatSize(this, totalF)));
                checkUsagePermissionHint(groups);
            });
        }).start();
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

    private List<Group> loadGroups() {
        Map<String, Group> map = new LinkedHashMap<>();
        for (CloneDatabase.Clone cl : db.listAll()) {
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
                r.icon = DualBadgeUtil.badgeForClone(this, ic, cl);
            } catch (Throwable t) { r.icon = g.baseIcon; }
            r.usage = CloneStorageHelper.queryPackage(this, cl.pkg, cl.userId);
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
            final MaterialButton clearCache, clearData, del;
            RowHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
                bar = v.findViewById(R.id.bar);
                clearCache = v.findViewById(R.id.btn_clear_cache);
                clearData = v.findViewById(R.id.btn_clear_data);
                del = v.findViewById(R.id.btn_delete);
            }
        }

        private void bindGroup(GroupHolder h, Group g) {
            if (g.baseIcon != null) h.icon.setImageDrawable(g.baseIcon);
            h.name.setText(g.baseLabel);
            h.detail.setText(getString(R.string.group_total, g.clones.size(),
                CloneStorageHelper.formatSize(StorageActivity.this, g.totalExtra)));
            h.bar.setProgressCompat((int) (100 * g.totalExtra / maxGroup), false);
        }

        private void bindRow(RowHolder h, Row r) {
            if (r.icon != null) h.icon.setImageDrawable(r.icon);
            h.name.setText(r.label);
            if (r.usage.available) {
                h.detail.setText(getString(R.string.storage_row,
                    CloneStorageHelper.formatSize(StorageActivity.this, r.usage.extraBytes),
                    CloneStorageHelper.formatSize(StorageActivity.this, r.usage.dataBytes),
                    CloneStorageHelper.formatSize(StorageActivity.this, r.usage.cacheBytes),
                    r.userId));
                h.bar.setProgressCompat((int) (100 * r.usage.extraBytes / maxExtra), false);
            } else {
                h.detail.setText(getString(R.string.storage_unavailable, r.userId));
                h.bar.setProgressCompat(0, false);
            }
            h.clearCache.setOnClickListener(v -> new Thread(() -> {
                boolean ok = CloneStorageHelper.clearCacheAsUser(StorageActivity.this, r.pkg, r.userId);
                runOnUiThread(() -> {
                    if (ok) { Toast.makeText(StorageActivity.this, R.string.cache_cleared, Toast.LENGTH_SHORT).show(); reload(); }
                    else openAppInfo(r.pkg, r.userId);
                });
            }).start());
            h.clearData.setOnClickListener(v -> new MaterialAlertDialogBuilder(StorageActivity.this)
                .setTitle(r.label)
                .setMessage(R.string.confirm_clear_data)
                .setPositiveButton(R.string.clear_data, (d, w) -> new Thread(() -> {
                    boolean ok = CloneStorageHelper.clearDataAsUser(StorageActivity.this, r.pkg, r.userId);
                    runOnUiThread(() -> {
                        if (ok) { Toast.makeText(StorageActivity.this, R.string.data_cleared, Toast.LENGTH_SHORT).show(); reload(); }
                        else openAppInfo(r.pkg, r.userId);
                    });
                }).start())
                .setNegativeButton(R.string.cancel, null)
                .show());
            h.del.setOnClickListener(v -> new MaterialAlertDialogBuilder(StorageActivity.this)
                .setTitle(r.label)
                .setMessage(R.string.delete)
                .setPositiveButton(R.string.delete, (d, w) -> new Thread(() -> {
                    CloneManager.deleteClone(StorageActivity.this, db, r.pkg, r.userId);
                    runOnUiThread(() -> { Toast.makeText(StorageActivity.this, R.string.clone_deleted, Toast.LENGTH_SHORT).show(); reload(); });
                }).start())
                .setNegativeButton(R.string.cancel, null)
                .show());
        }
    }

    private void openAppInfo(String pkg, int userId) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("extra_user_id", userId);
            SysApi.startActivityAsUser(this, i, userId);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
            } catch (Throwable ignore) {}
        }
    }
}
