package com.clonix.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Queue an auto-backup whenever a tracked app is installed or updated
 * (opt-in, blacklists respected). Kept deliberately thin: the real work
 * runs in AutoBackupJob — Android 14+ forbids heavy work or foreground
 * services straight out of package broadcasts.
 */
public class PackageAddedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        boolean added = Intent.ACTION_PACKAGE_ADDED.equals(a);
        boolean replaced = Intent.ACTION_PACKAGE_REPLACED.equals(a);
        if (!added && !replaced) return;
        if (intent.getData() == null
                || !"package".equals(intent.getData().getScheme())) return;
        String pkg = intent.getData().getSchemeSpecificPart();
        if (pkg == null || pkg.equals(c.getPackageName())) return;
        if (!Prefs.autoBackupOnInstall(c)) return;
        Prefs.setPendingAutoPkg(c, pkg);
        AutoBackupJob.scheduleFor(c, pkg);
    }
}
