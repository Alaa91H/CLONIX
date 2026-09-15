package com.clonix.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Transparent trampoline so a Home shortcut opens ONLY its clone. */
public class LauncherTrampoline extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        final String pkg = i == null ? null : i.getStringExtra("extra_pkg");
        final int userId = i == null ? -1 : i.getIntExtra("extra_userId", -1);
        // Immediate feedback so the tap never feels dead.
        try {
            android.widget.Toast.makeText(this, R.string.opening_clone,
                android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) { }
        // Launch off-main-thread (user start + resolve may take seconds).
        // Dead clones (user removed externally) unpin themselves with feedback.
        new Thread(() -> {
            try {
                if (pkg != null && userId >= 0) {
                    boolean alive = true;
                    try { alive = CloneEngine.isInstalledAsUser(getApplicationContext(), pkg, userId); }
                    catch (Throwable t) { alive = true; }
                    if (!alive) {
                        try { ShortcutHelper.unpin(getApplicationContext(), pkg, userId); }
                        catch (Throwable ignore) { }
                        runOnUiThread(() -> android.widget.Toast.makeText(
                            LauncherTrampoline.this,
                            R.string.clone_gone, android.widget.Toast.LENGTH_LONG).show());
                    } else {
                        CloneEngine.launchClone(getApplicationContext(), pkg, userId);
                    }
                }
            } catch (Throwable ignore) {}
            runOnUiThread(() -> {
                // Leave NO task trace: only the clone stays open.
                try { finishAndRemoveTask(); }
                catch (Throwable t) { finish(); }
            });
        }).start();
    }

    public static Intent shortcutIntent(String pkg, int userId) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setClassName("com.clonix.app",
            "com.clonix.app.LauncherTrampoline");
        i.putExtra("extra_pkg", pkg);
        i.putExtra("extra_userId", userId);
        return i;
    }
}
