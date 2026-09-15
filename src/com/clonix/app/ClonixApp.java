package com.clonix.app;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

/** Applies Material You dynamic colors on supported devices (fallback: baseline theme). */
public class ClonixApp extends Application {
    private static volatile Application sInstance;

    /** App-wide context for static helpers (safe: process keeps one instance). */
    public static Application context() {
        return sInstance;
    }

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        applyDynamicColors(this);
        try { BackupJob.syncCompOverride(this); } catch (Throwable ignore) { }
    }

    /** Honor the Settings toggle: Material You overlay only when enabled. */
    public static void applyDynamicColors(Application app) {
        try {
            if (Prefs.dynamicColor(app)
                    && DynamicColors.isDynamicColorAvailable()) {
                DynamicColors.applyToActivitiesIfAvailable(app);
            }
        } catch (Throwable ignore) { }
    }
}
