package com.clonix.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Legacy entry kept for compatibility only (system-Settings deep link
 * action com.clonix.app.OPEN + TestHookReceiver hooks). The product
 * surface is HomeActivity; MainActivity forwards there and exits.
 */
public class MainActivity extends Activity {
    /** Orphan-clone cache lives on the old class; keep the hook working. */
    static void invalidateOrphanCache() { /* no-op: legacy list removed */ }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }
}
