package com.clonepilot.app;

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
            try { CloneManager.startAllClonesInBackground(c); }
            catch (Throwable t) { Log.w("ClonePilot", "boot start failed", t); }
        }
    }
}
