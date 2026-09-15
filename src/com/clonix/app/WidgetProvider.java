package com.clonix.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * CLONIX home-screen widget: scheduled-backup state, last-backup age,
 * one-tap backup-now and refresh. Backup action routes through OpsQueue
 * (engine lane) so it never races a running op.
 */
public class WidgetProvider extends AppWidgetProvider {
    public static final String ACTION_BACKUP = "com.clonix.app.WIDGET_BACKUP";
    public static final String ACTION_REFRESH = "com.clonix.app.WIDGET_REFRESH";

    @Override public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        try {
            RemoteViews v = WidgetUpdater.build(c);
            mgr.updateAppWidget(ids, v);
        } catch (Throwable ignore) { }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        String a = i == null ? null : i.getAction();
        if (ACTION_BACKUP.equals(a)) {
            try {
                OpsQueue.post(c, c.getString(R.string.w_run_now), app -> {
                    BackupScheduler.runOnce(app);
                });
                Toast.makeText(c, R.string.w_run_started, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(c, String.valueOf(t.getMessage()),
                    Toast.LENGTH_SHORT).show();
            }
        }
        refresh(c);
    }

    static void refresh(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(
                new ComponentName(c, WidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews v = WidgetUpdater.build(c);
            mgr.updateAppWidget(ids, v);
        } catch (Throwable ignore) { }
    }
}
