package com.clonix.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Screen-off sweep trigger for auto-freeze.
 * Dynamic-only (ACTION_SCREEN_OFF is not deliverable to manifest receivers):
 * registered by AutoFreezeService while the master toggle is ON.
 */
public class ScreenFreezeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_SCREEN_OFF.equals(i.getAction())) return;
        final Context app = c.getApplicationContext();
        if (!Prefs.autoFreeze(app)) return;
        final BroadcastReceiver.PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                CloneStore db = new CloneStore(app);
                int n = CloneEngine.freezeAllTracked(app, db);
                try { db.close(); } catch (Throwable ignore) { }
                Log.i("Clonix", "auto-freeze sweep: " + n);
            } catch (Throwable t) {
                Log.w("Clonix", "auto-freeze failed", t);
            } finally {
                try { pr.finish(); } catch (Throwable ignore) { }
            }
        }).start();
    }
}
