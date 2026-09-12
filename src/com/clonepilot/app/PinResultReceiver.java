package com.clonepilot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Pin confirmation callback (requestPinShortcut result IntentSender).
 * System fires it ONLY on success; silence means dismissed/failed.
 * Unexported: only the system can trigger it via our own PendingIntent.
 */
public class PinResultReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.clonepilot.app.PIN_RESULT";

    @Override public void onReceive(Context c, Intent i) {
        try {
            Toast.makeText(c.getApplicationContext(),
                R.string.shortcut_pinned, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w("ClonePilot", "pin toast failed", t);
        }
    }
}
