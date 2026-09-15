package com.clonix.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground host for the screen-off auto-freeze watcher.
 * A foreground service is the only reliable way to receive SCREEN_OFF
 * while the UI process is dead: manifest receivers never get it and a
 * dynamically-registered receiver dies with its process.
 * Footprint: zero wakeups, zero polling — one broadcast per screen-off.
 */
public class AutoFreezeService extends Service {
    private static final String TAG = "Clonix";
    private static final String CH = "autofreeze";
    private static final int NID = 4301;

    private final BroadcastReceiver screenOff = new ScreenFreezeReceiver();

    public static void sync(Context c) {
        try {
            Intent i = new Intent(c, AutoFreezeService.class);
            if (Prefs.autoFreeze(c)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    c.startForegroundService(i);
                } else {
                    c.startService(i);
                }
            } else {
                c.stopService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "autofreeze sync failed", t);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                try {
                    nm.createNotificationChannel(new NotificationChannel(
                            CH, getString(R.string.settings_autofreeze),
                            NotificationManager.IMPORTANCE_MIN));
                } catch (Throwable ignore) { }
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, CH);
            } else {
                b = new Notification.Builder(this);
            }
            try { b.setSmallIcon(R.drawable.ic_tile); } catch (Throwable ignore) { }
            try { b.setContentTitle(getString(R.string.autofreeze_service)); } catch (Throwable ignore) { }
            try { b.setOngoing(true); } catch (Throwable ignore) { }
            Notification n;
            try { n = b.build(); }
            catch (Throwable t) { n = new Notification(); }
            startForeground(NID, n);
        } catch (Throwable t) {
            Log.w(TAG, "autofreeze foreground failed", t);
            stopSelf();
            return;
        }
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOff, f);
            }
        } catch (Throwable t) {
            Log.w(TAG, "screen-off register failed", t);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        // Master toggle may have flipped while we were dead: obey it.
        if (!Prefs.autoFreeze(this)) stopSelf();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(screenOff); } catch (Throwable ignore) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
