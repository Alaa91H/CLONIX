package com.clonix.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;

/**
 * Result sounds: success and failure fan out to two channels with DIFFERENT
 * tones (system notification vs system alarm), ON by default, optional via
 * Settings. Both channels stay user-tunable in system Settings.
 */
public final class Notify {
    private Notify() {}

    public static final String CH_OK = "ops_success";
    public static final String CH_FAIL = "ops_fail";
    public static final int ID_PROG = 4900;
    private static int nextId = 5000;

    public static boolean enabled(Context c) {
        return Prefs.sounds(c);
    }

    private static void channels(Context c) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
            NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            NotificationChannel ok = new NotificationChannel(CH_OK,
                c.getString(R.string.notif_ok), NotificationManager.IMPORTANCE_DEFAULT);
            try {
                ok.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, aa);
            } catch (Throwable ignore) { }
            NotificationChannel fail = new NotificationChannel(CH_FAIL,
                c.getString(R.string.notif_fail), NotificationManager.IMPORTANCE_HIGH);
            try {
                fail.setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, aa);
            } catch (Throwable ignore) { }
            try { nm.createNotificationChannel(ok); } catch (Throwable ignore) { }
            try { nm.createNotificationChannel(fail); } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
    }

    public static void result(Context c, boolean success, String title, String text) {
        try {
            if (!enabled(c)) return;
            NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            channels(c);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(c, success ? CH_OK : CH_FAIL);
            } else {
                b = new Notification.Builder(c);
            }
            try { b.setSmallIcon(android.R.drawable.stat_sys_download_done); }
            catch (Throwable ignore) { }
            try {
                b.setContentTitle(title);
                b.setContentText(text);
                b.setAutoCancel(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    b.setStyle(new Notification.BigTextStyle().bigText(text));
                }
            } catch (Throwable ignore) { }
            Notification n;
            try { n = b.build(); }
            catch (Throwable t) { return; }
            int id;
            synchronized (Notify.class) { id = nextId++; }
            try { nm.notify(id, n); } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
    }

    public static void ok(Context c, String title, String text) {
        result(c, true, title, text);
    }

    public static void fail(Context c, String title, String text) {
        result(c, false, title, text);
    }

    // ---------------- progress channel ----------------

    public static final String CH_PROG = "ops_progress";

    /** Ongoing operation notification: low importance, no sound, no dismiss. */
    public static void progress(Context c, String title) {
        try {
            NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            channels(c);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CH_PROG,
                    c.getString(R.string.notif_progress),
                    NotificationManager.IMPORTANCE_LOW);
                ch.setSound(null, null);
                try { nm.createNotificationChannel(ch); } catch (Throwable ignore) { }
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(c, CH_PROG);
            } else {
                b = new Notification.Builder(c);
            }
            try { b.setSmallIcon(android.R.drawable.stat_sys_download); }
            catch (Throwable ignore) { }
            try {
                b.setContentTitle(title);
                b.setContentText(c.getString(R.string.notif_working));
                b.setOngoing(true);
                b.setOnlyAlertOnce(true);
                b.setProgress(0, 0, true);
            } catch (Throwable ignore) { }
            try { nm.notify(ID_PROG, b.build()); } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
    }

    /** Clear the ongoing notification when the lane drains. */
    public static void doneProgress(Context c, int id) {
        try {
            NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(id);
        } catch (Throwable ignore) { }
    }

    /** Determinate batch progress: "x/y of N" with a real progress bar. */
    public static void batchProgress(Context c, String title, int done, int total) {
        try {
            NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            channels(c);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CH_PROG,
                    c.getString(R.string.notif_progress),
                    NotificationManager.IMPORTANCE_LOW);
                ch.setSound(null, null);
                try { nm.createNotificationChannel(ch); } catch (Throwable ignore) { }
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(c, CH_PROG);
            } else {
                b = new Notification.Builder(c);
            }
            try { b.setSmallIcon(android.R.drawable.stat_sys_download); }
            catch (Throwable ignore) { }
            try {
                b.setContentTitle(title);
                b.setContentText(c.getString(R.string.notif_batch_fmt, done, total));
                b.setOngoing(true);
                b.setOnlyAlertOnce(true);
                b.setProgress(total, done, false);
            } catch (Throwable ignore) { }
            try { nm.notify(ID_PROG, b.build()); } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
    }
}
