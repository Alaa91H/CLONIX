package com.clonix.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * Theme mode (system / light / dark / AMOLED black). Must be applied BEFORE
 * super.onCreate() so inflation picks the right theme. Dynamic Colors
 * (Material You) still overlay on top via ClonixApp.
 */
public final class ThemeHelper {
    private ThemeHelper() {}

    public static void apply(Activity a) {
        try {
            String m = Prefs.themeMode(a);
            if ("light".equals(m)) a.setTheme(R.style.Theme_Clonix_Light);
            else if ("dark".equals(m)) a.setTheme(R.style.Theme_Clonix_Dark);
            else if ("amoled".equals(m)) a.setTheme(R.style.Theme_Clonix_Amoled);
            // "system": manifest DayNight theme follows the system automatically.
        } catch (Throwable ignore) { }
    }

    public static void applyBeforeCreate(Activity a, Bundle b) {
        apply(a);
    }
}
