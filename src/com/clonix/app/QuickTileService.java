package com.clonix.app;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

/**
 * Quick Settings tile: "Wake clones" — manually restarts all background
 * clone users (same engine as BootReceiver/keep-alive). Add it from the
 * QS edit panel. Icon must be white-on-alpha (system-tinted).
 */
public class QuickTileService extends TileService {
    @Override public void onClick() {
        boolean locked = false;
        try { locked = isLocked(); } catch (Throwable ignore) { }
        if (locked) {
            try { unlockAndRun(this::wake); } catch (Throwable t) { wake(); }
            return;
        }
        wake();
    }

    private void wake() {
        try {
            Tile t = getQsTile();
            if (t != null) {
                t.setState(Tile.STATE_ACTIVE);
                t.updateTile();
            }
        } catch (Throwable ignore) { }
        new Thread(() -> {
            int n = 0;
            try {
                for (SysApi.User u : SysApi.safeGetUsers(this)) {
                    if (u.id == 0 || !CloneEngine.isOursName(u.name)) continue;
                    try { CloneEngine.startUserInBackground(u.id); n++; }
                    catch (Throwable ignore) { }
                }
            } catch (Throwable t) {
                Log.w("Clonix", "tile wake failed", t);
            }
            final int done = n;
            try {
                Tile t = getQsTile();
                if (t != null) {
                    t.setState(Tile.STATE_INACTIVE);
                    t.updateTile();
                }
            } catch (Throwable ignore) { }
            Log.i("Clonix", "tile woke " + done + " users");
        }).start();
        try {
            Toast.makeText(this, R.string.tile_waking, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) { }
    }

    @Override public void onStartListening() {
        try {
            Tile t = getQsTile();
            if (t == null) return;
            t.setLabel(getString(R.string.tile_label));
            if (Build.VERSION.SDK_INT >= 29) {
                try { t.setSubtitle(getString(R.string.tile_sub)); }
                catch (Throwable ignore) { }
            }
            t.setState(Tile.STATE_INACTIVE);
            t.updateTile();
        } catch (Throwable ignore) { }
    }
}
