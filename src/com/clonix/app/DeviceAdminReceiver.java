package com.clonix.app;


import android.content.Context;
import android.content.Intent;

/** DeviceAdmin for profile slots. No policies enforced. */
public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {
    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        // Silent provisioning done. Nothing extra needed.
    }
}
