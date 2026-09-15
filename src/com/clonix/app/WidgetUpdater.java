package com.clonix.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Builds/refreshes the home-screen widget's RemoteViews. Updates are
 * event-driven (after every op, notification tap, manual refresh) plus a
 * battery-cheap 3-hour safety period — no alarm-based ticking.
 */
public final class WidgetUpdater {
    private WidgetUpdater() {}

    public static void updateAll(Context c) {
        try {
            WidgetProvider.refresh(c);
        } catch (Throwable ignore) { }
    }

    public static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(),
            R.layout.widget_backup);
        long last = BackupJob.lastBackupTime(c);
        String when;
        if (last <= 0) {
            when = c.getString(R.string.w_last_never);
        } else {
            long mins = (System.currentTimeMillis() - last) / 60000L;
            if (mins < 1) when = c.getString(R.string.w_last_now);
            else if (mins < 60) when = c.getString(R.string.w_last_min, (int) mins);
            else if (mins < 1440) when = c.getString(R.string.w_last_hour, (int)(mins / 60));
            else when = c.getString(R.string.w_last_day, (int)(mins / 1440));
        }
        boolean on = BackupScheduler.enabled(c);
        v.setTextViewText(R.id.w_status,
            c.getString(on ? R.string.w_sched_on : R.string.w_sched_off));
        v.setTextViewText(R.id.w_last, when);
        v.setOnClickPendingIntent(R.id.w_btn_backup,
            PendingIntent.getBroadcast(c, 10,
                new Intent(c, WidgetProvider.class)
                    .setAction(WidgetProvider.ACTION_BACKUP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        v.setOnClickPendingIntent(R.id.w_btn_refresh,
            PendingIntent.getBroadcast(c, 11,
                new Intent(c, WidgetProvider.class)
                    .setAction(WidgetProvider.ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent open = c.getPackageManager().getLaunchIntentForPackage(
            c.getPackageName());
        if (open == null) open = new Intent(c, HomeActivity.class);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 12,
            open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
