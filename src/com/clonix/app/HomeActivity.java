package com.clonix.app;

import android.app.Activity;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.UUID;

/**
 * Home tab: OVERVIEW card (internal storage + root status + category
 * chips), then APPS / MESSAGES / CALL LOGS / WALLPAPER quick actions,
 * matching the reference distribution.
 */
public class HomeActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_home);
        NavHelper.setup(this, R.id.nav_home);

        findViewById(R.id.ov_internal_row).setOnClickListener(v ->
            startActivity(new Intent(this, StorageActivity.class)));

        findViewById(R.id.chip_apps).setOnClickListener(v -> openApps());
        findViewById(R.id.chip_messages).setOnClickListener(v ->
            quickSpecial("sms"));
        findViewById(R.id.chip_calllogs).setOnClickListener(v ->
            quickSpecial("calllog"));
        findViewById(R.id.chip_folders).setOnClickListener(v ->
            startActivity(new Intent(this, StorageActivity.class)));
        findViewById(R.id.chip_wallpapers).setOnClickListener(v ->
            specialBackup("wallpaper", getString(R.string.ov_wallpapers)));
        findViewById(R.id.chip_wifi).setOnClickListener(v ->
            quickSpecial("wifi"));

        findViewById(R.id.qa_apps_backup).setOnClickListener(v -> openApps());
        findViewById(R.id.qa_apps_restore).setOnClickListener(v -> openApps());

        findViewById(R.id.qa_msg_backup).setOnClickListener(v ->
            specialBackup("sms", getString(R.string.ov_messages)));
        findViewById(R.id.qa_msg_restore).setOnClickListener(v ->
            RestoreWizard.startSpecial(this, "sms", getString(R.string.ov_messages)));

        findViewById(R.id.qa_calls_backup).setOnClickListener(v ->
            specialBackup("calllog", getString(R.string.ov_calllogs)));
        findViewById(R.id.qa_calls_restore).setOnClickListener(v ->
            RestoreWizard.startSpecial(this, "calllog", getString(R.string.ov_calllogs)));

        findViewById(R.id.qa_wifi_backup).setOnClickListener(v ->
            specialBackup("wifi", getString(R.string.ov_wifi)));
        findViewById(R.id.qa_wifi_restore).setOnClickListener(v ->
            RestoreWizard.startSpecial(this, "wifi", getString(R.string.ov_wifi)));

        findViewById(R.id.qa_wall_backup).setOnClickListener(v ->
            specialBackup("wallpaper", getString(R.string.ov_wallpapers)));
        findViewById(R.id.qa_wall_restore).setOnClickListener(v ->
            RestoreWizard.startSpecial(this, "wallpaper",
                getString(R.string.ov_wallpapers)));

        findViewById(R.id.qa_more).setOnClickListener(v ->
            startActivity(new Intent(this, BackupCenterActivity.class)));
        findViewById(R.id.msg_more).setOnClickListener(v ->
            startActivity(new Intent(this, BackupCenterActivity.class)));
        findViewById(R.id.calls_more).setOnClickListener(v ->
            startActivity(new Intent(this, BackupCenterActivity.class)));
    }

    private void openApps() {
        try {
            startActivity(new Intent(this, AppsActivity.class));
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void quickSpecial(String kind) {
        specialBackup(kind,
            "sms".equals(kind) ? getString(R.string.ov_messages)
            : "calllog".equals(kind) ? getString(R.string.ov_calllogs)
            : getString(R.string.ov_wifi));
    }

    @Override protected void onResume() {
        super.onResume();
        // Android 13+ gate: without POST_NOTIFICATIONS the user never sees
        // backup/restore completion results. Ask once per install (the
        // system only allows one prompt; afterwards it's a no-op).
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                android.Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
        try { NavHelper.refreshBadges(this); } catch (Throwable ignore) { }
        reload();
    }

    private void reload() {
        new Thread(() -> {
            long total = -1, free = -1;
            try {
                StorageStatsManager ssm = (StorageStatsManager)
                    getSystemService(Context.STORAGE_STATS_SERVICE);
                UUID uuid = StorageManager.UUID_DEFAULT;
                total = ssm.getTotalBytes(uuid);
                free = ssm.getFreeBytes(uuid);
            } catch (Throwable ignore) { }
            ShellEngine.Mode mode;
            try { mode = ShellEngine.mode(this); }
            catch (Throwable t) { mode = ShellEngine.Mode.NONE; }
            int archives = 0;
            try {
                archives = CloneEngine.listFullBackups(this, null).size();
            } catch (Throwable ignore) { }
            final long totalF = total, freeF = free;
            final ShellEngine.Mode modeF = mode;
            final int archivesF = archives;
            runOnUiThread(() -> render(totalF, freeF, modeF, archivesF));
        }).start();
    }

    private void render(long total, long free, ShellEngine.Mode mode,
            int archives) {
        TextView freeTv = findViewById(R.id.ov_free);
        LinearProgressIndicator bar = findViewById(R.id.ov_bar);
        TextView used = findViewById(R.id.ov_used);
        if (total > 0 && free >= 0) {
            freeTv.setText(getString(R.string.ov_free_of,
                CloneUsage.formatSize(this, free),
                CloneUsage.formatSize(this, total)));
            int pct = (int) (100L * (total - free) / total);
            try { bar.setProgressCompat(pct, false); } catch (Throwable ignore) { }
            used.setText(getString(R.string.ov_used, pct));
        } else {
            freeTv.setText(R.string.dash_internal_na);
            used.setText("");
        }
        TextView root = findViewById(R.id.ov_root);
        TextView rootSub = findViewById(R.id.ov_root_sub);
        if (mode == ShellEngine.Mode.ROOT) {
            root.setText(R.string.ov_granted);
            try { root.setTextColor(0xFF2E7D32); } catch (Throwable ignore) { }
            rootSub.setText("KernelSU / Magisk");
        } else if (mode == ShellEngine.Mode.DIRECT) {
            root.setText(R.string.ov_granted);
            try { root.setTextColor(0xFF2E7D32); } catch (Throwable ignore) { }
            rootSub.setText(getString(R.string.engine_direct_short));
        } else {
            root.setText(R.string.ov_not_granted);
            try { root.setTextColor(0xFFB3261E); } catch (Throwable ignore) { }
            rootSub.setText(getString(R.string.engine_none_short));
        }
        TextView counts = findViewById(R.id.ov_counts);
        try {
            counts.setText(getString(R.string.apps_count_fmt, CountCache.apps(this))
                + " • " + getString(R.string.archives_count_short, archives));
        } catch (Throwable ignore) { }
        setChip(R.id.chip_apps, R.string.ov_apps, R.drawable.ic_sb_apps);
        setChip(R.id.chip_messages, R.string.ov_messages, R.drawable.ic_sb_sms);
        setChip(R.id.chip_calllogs, R.string.ov_calllogs, R.drawable.ic_sb_calls);
        setChip(R.id.chip_folders, R.string.ov_folders, R.drawable.ic_nav_archives_outline);
        setChip(R.id.chip_wallpapers, R.string.ov_wallpapers, R.drawable.ic_set_palette);
        setChip(R.id.chip_wifi, R.string.ov_wifi, R.drawable.ic_sb_wifi);
    }

    private void setChip(int id, int labelRes, int iconRes) {
        try {
            TextView tv = findViewById(id);
            tv.setText(labelRes);
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
            try {
                tv.setCompoundDrawablePadding((int) (6 * getResources()
                    .getDisplayMetrics().density));
            } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
    }

    private void specialBackup(final String kind, final String title) {
        Toast.makeText(this, R.string.backing_up, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final String path = CloneEngine.backupSpecial(this, kind);
                runOnUiThread(() -> {
                    Toast.makeText(this,
                        getString(R.string.backup_done, path),
                        Toast.LENGTH_LONG).show();
                    Notify.ok(this, title,
                        getString(R.string.backup_done, path));
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.backup_failed)
                        + ": " + t.getMessage(), Toast.LENGTH_LONG).show();
                    Notify.fail(this, title, String.valueOf(t.getMessage()));
                });
            }
        }).start();
    }
}
