package com.clonepilot.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Home-screen shortcuts for clones. IDs are deterministic
 * ("clone_<pkg>_<userId>") so delete/refresh can target them exactly.
 * Icons use adaptive bitmaps: plain bitmaps make launchers wrap the icon
 * in a white adaptive shape (white-frame bug).
 */
public final class CloneShortcuts {
    private static final String TAG = "ClonePilot";
    private static final String PREFIX = "clone_";

    private CloneShortcuts() {}

    public static String idFor(String pkg, int userId) {
        return PREFIX + pkg + "_" + userId;
    }

    private static ShortcutManager sm(Context c) {
        try {
            return c.getSystemService(ShortcutManager.class);
        } catch (Throwable t) { return null; }
    }

    private static ShortcutInfo build(Context c, String title, Drawable baseIcon,
                                      CloneDatabase.Clone cl) {
        Bitmap bmp = DualBadgeUtil.shortcutBitmapForClone(c, baseIcon, cl);
        if (bmp == null) bmp = DualBadgeUtil.shortcutBitmap(
            c, baseIcon, cl.slotIndex, BadgeSettings.STYLE_NONE, 0, false,
            BadgeSettings.POS_BR);
        return new ShortcutInfo.Builder(c, idFor(cl.pkg, cl.userId))
            .setShortLabel(title)
            .setIcon(Icon.createWithAdaptiveBitmap(bmp))
            .setIntent(CloneLauncherTrampoline.shortcutIntent(cl.pkg, cl.userId))
            .build();
    }

    /** Ask the launcher to pin (system shows one confirmation tap). */
    public static void pin(Context c, String title, Drawable baseIcon,
                           CloneDatabase.Clone cl) {
        try {
            ShortcutManager s = sm(c);
            if (s == null) return;
            if (!s.isRequestPinShortcutSupported()) return;
            s.requestPinShortcut(build(c, title, baseIcon, cl), null);
        } catch (Throwable t) {
            Log.w(TAG, "pin failed", t);
        }
    }

    /** Refresh title/icon of an already-pinned shortcut (badge/rename changes). */
    public static void refresh(Context c, String title, Drawable baseIcon,
                               CloneDatabase.Clone cl) {
        try {
            ShortcutManager s = sm(c);
            if (s == null) return;
            List<ShortcutInfo> pinned;
            try { pinned = s.getPinnedShortcuts(); }
            catch (Throwable t) { return; }
            if (pinned == null) return;
            String id = idFor(cl.pkg, cl.userId);
            for (ShortcutInfo si : pinned) {
                if (id.equals(si.getId())) {
                    List<ShortcutInfo> one = new ArrayList<>(1);
                    one.add(build(c, title, baseIcon, cl));
                    // updateShortcuts() also updates PINNED shortcuts with same id.
                    try { s.updateShortcuts(one); }
                    catch (Throwable t) { Log.w(TAG, "update pinned failed", t); }
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed", t);
        }
    }

    /** Remove this clone's home shortcut(s) from the launcher. */
    public static void unpin(Context c, String pkg, int userId) {
        try {
            ShortcutManager s = sm(c);
            if (s == null) return;
            List<String> ids = new ArrayList<>(1);
            ids.add(idFor(pkg, userId));
            try { s.disableShortcuts(ids, c.getString(R.string.clone_deleted)); }
            catch (Throwable t) { Log.w(TAG, "disable pinned failed", t); }
        } catch (Throwable t) {
            Log.w(TAG, "unpin failed", t);
        }
    }

    /** Best-effort launch of the pin flow from a non-UI context. */
    public static void pinFromBackground(Context c, String title, Drawable baseIcon,
                                         CloneDatabase.Clone cl) {
        try { pin(c, title, baseIcon, cl); }
        catch (Throwable t) { Log.w(TAG, "bg pin failed", t); }
    }

    @SuppressWarnings("unused")
    private static Intent unused(Intent i) { return i; }
}
