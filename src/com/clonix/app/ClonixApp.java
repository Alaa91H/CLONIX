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
        try { DynamicColors.applyToActivitiesIfAvailable(this); }
        catch (Throwable ignore) { }
        try { BackupJob.syncCompOverride(this); } catch (Throwable ignore) { }
    }
}
