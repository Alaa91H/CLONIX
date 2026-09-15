package com.clonix.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restart background clone users after reboot so clones keep receiving messages. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? "" : i.getAction();
        // NOTE: Intent.ACTION_USER_STARTED is hidden from public SDK: literal.
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || "android.intent.action.USER_STARTED".equals(a)) {
            final BroadcastReceiver.PendingResult pr = goAsync();
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                try {
                    KeepAliveJob.schedule(app);
                    CloneEngine.startAllClonesInBackground(app);
                    // Re-arm screen-off watcher after reboot when enabled.
                    try { AutoFreezeService.sync(app); } catch (Throwable ignore) { }
                    // Re-arm scheduled backups (persisted job re-registers anyway).
                    try { BackupScheduler.sync(app); } catch (Throwable ignore) { }
                }
                catch (Throwable t) { Log.w("Clonix", "boot start failed", t); }
                finally { try { pr.finish(); } catch (Throwable ignore) { } }
            }).start();
        }
    }
}
