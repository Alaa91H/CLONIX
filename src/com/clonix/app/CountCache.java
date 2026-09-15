package com.clonix.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.util.List;

/** Process-lifetime cache of the installed-apps count (Home glance). */
public final class CountCache {
    private CountCache() {}

    private static volatile int cached = -1;
    private static volatile long at = 0;

    public static int apps(Context c) {
        long now = System.currentTimeMillis();
        if (cached >= 0 && now - at < 30000) return cached;
        int n = 0;
        try {
            Intent main = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris =
                c.getPackageManager().queryIntentActivities(main, 0);
            java.util.HashSet<String> pkgs = new java.util.HashSet<>();
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo != null) pkgs.add(ri.activityInfo.packageName);
            }
            n = pkgs.size();
        } catch (Throwable ignore) { }
        cached = n;
        at = now;
        return n;
    }
}
