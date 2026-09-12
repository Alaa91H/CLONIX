package com.clonepilot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * If the original app is uninstalled, its clones lose their purpose.
 * We clean DB entries so empty secondary users get reclaimed.
 */
public class PackageCleanupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String action = intent.getAction();
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        if (replacing) return;
        if (!Intent.ACTION_PACKAGE_REMOVED.equals(action)
                && !Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) return;
        Uri data = intent.getData();
        if (!"package".equals(data.getScheme())) return;
        String pkg = data.getSchemeSpecificPart();
        if (pkg == null) return;
        try {
            // Only cleanup when removed for owner (our receiver runs as owner/system)
            CloneDatabase db = new CloneDatabase(c);
            if (!db.listForPkg(pkg).isEmpty()) {
                db.removeAllForPkg(pkg);
            }
        } catch (Throwable ignore) {}
    }
}
