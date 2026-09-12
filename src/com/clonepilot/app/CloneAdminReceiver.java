package com.clonepilot.app;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** DeviceAdmin for profile slots. No policies enforced. */
public class CloneAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        // Silent provisioning done. Nothing extra needed.
    }
}
