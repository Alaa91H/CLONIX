package com.clonepilot.app;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

/** Applies Material You dynamic colors on supported devices (fallback: baseline theme). */
public class CloneApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try { DynamicColors.applyToActivitiesIfAvailable(this); }
        catch (Throwable ignore) { }
    }
}
