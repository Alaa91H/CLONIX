package com.clonepilot.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Transparent trampoline so a Home shortcut can open pkg in the right clone user. */
public class CloneLauncherTrampoline extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        final String pkg = i == null ? null : i.getStringExtra("extra_pkg");
        final int userId = i == null ? -1 : i.getIntExtra("extra_userId", -1);
        // Launch off-main-thread (user start + resolve may take seconds).
        new Thread(() -> {
            try {
                if (pkg != null && userId >= 0) {
                    CloneManager.launchClone(getApplicationContext(), pkg, userId);
                }
            } catch (Throwable ignore) {}
            runOnUiThread(this::finish);
        }).start();
    }

    public static Intent shortcutIntent(String pkg, int userId) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setClassName("com.clonepilot.app",
            "com.clonepilot.app.CloneLauncherTrampoline");
        i.putExtra("extra_pkg", pkg);
        i.putExtra("extra_userId", userId);
        return i;
    }
}
