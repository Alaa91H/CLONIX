package com.evolution.launcherclone;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drop-in helper for Launcher3-based launchers (Trebuchet, Lawnchair source).
 * Surfaces DualMessenger clones in the MAIN app drawer with slot numbers,
 * Samsung-style: no Work tab, no briefcase badge.
 *
 * Uses only stable public APIs (UserManager, LauncherApps) so it survives
 * Launcher3 version changes. The calling launcher already holds cross-profile
 * access as the active launcher; no extra permissions needed for profiles.
 * Secondary users (slots 2+) need MANAGE_USERS: if unavailable here they are
 * skipped gracefully (DualMessenger pinned shortcuts cover them instead).
 *
 * Split of duties by design:
 *  - Slot 1 (DualClone CLONE profile, a PROFILE of owner) -> this patch.
 *  - Slots 2..N (EvoClone_* secondary users) -> DualMessenger shortcuts.
 */
public final class CloneAppSource {
    private static final String TAG = "DualClonePatch";

    public static final String CLONE_PROFILE_NAME = "DualClone";
    public static final String MANAGED_FALLBACK_NAME = "DualMessenger";
    public static final String SECONDARY_PREFIX = "EvoClone";

    public static final class CloneTarget {
        public final int userId;
        public final int slot; // 1 = clone profile, 2..N = secondary
        public final ComponentName component;
        public final CharSequence label;
        public final UserHandle user;

        CloneTarget(int userId, int slot, ComponentName c, CharSequence l, UserHandle u) {
            this.userId = userId;
            this.slot = slot;
            this.component = c;
            this.label = l;
            this.user = u;
        }
    }

    private CloneAppSource() {}

    /** Name-based ownership check (avoids touching enterprise work profiles). */
    public static boolean isOurs(String userName) {
        if (userName == null) return false;
        return userName.equals(CLONE_PROFILE_NAME)
                || userName.equals(MANAGED_FALLBACK_NAME)
                || userName.startsWith(SECONDARY_PREFIX);
    }

    public static int slotFor(String userName, int fallback) {
        if (CLONE_PROFILE_NAME.equals(userName)
                || MANAGED_FALLBACK_NAME.equals(userName)) return 1;
        if (userName != null && userName.startsWith(SECONDARY_PREFIX)) {
            try {
                return Integer.parseInt(userName.substring(SECONDARY_PREFIX.length() + 1));
            } catch (Throwable ignore) { /* fall through */ }
        }
        return fallback;
    }

    /**
     * All clone users visible to the calling launcher.
     * getUserProfiles()/getProfiles() cover profiles (slot 1); getUsers()
     * additionally covers secondary users when MANAGE_USERS is granted.
     */
    public static List<UserHandle> cloneUsers(Context ctx) {
        UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        // Path 1: full list (platform-signed launchers usually granted).
        try {
            List<UserHandle> out = new ArrayList<>();
            for (android.content.pm.UserInfo ui : um.getUsers()) {
                if (ui.id == 0) continue;
                if (isOurs(ui.name)) out.add(ui.getUserHandle());
            }
            if (!out.isEmpty()) return out;
        } catch (SecurityException se) {
            Log.i(TAG, "getUsers() not permitted, falling back to profiles");
        } catch (Throwable t) {
            Log.w(TAG, "getUsers failed", t);
        }
        // Path 2: profiles only (slot 1). No special permission needed
        // for the active launcher; names resolved best-effort.
        try {
            List<UserHandle> out = new ArrayList<>();
            for (UserHandle uh : um.getUserProfiles()) {
                if (uh.getIdentifier() == 0) continue;
                try {
                    android.content.pm.UserInfo ui = um.getUserInfo(uh.getIdentifier());
                    if (ui != null && isOurs(ui.name)) out.add(uh);
                } catch (Throwable ignore) {
                    // getUserInfo needs MANAGE_USERS on some builds; without it
                    // we cannot name-match -> skip (shortcuts path covers it).
                }
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "getUserProfiles failed", t);
            return Collections.emptyList();
        }
    }

    /** Every clone launchable activity, for merging into the main drawer list. */
    public static List<CloneTarget> load(Context ctx) {
        List<CloneTarget> out = new ArrayList<>();
        UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        LauncherApps la = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        for (UserHandle uh : cloneUsers(ctx)) {
            int userId = uh.getIdentifier();
            String name = null;
            try {
                android.content.pm.UserInfo ui = um.getUserInfo(userId);
                if (ui != null) name = ui.name;
            } catch (Throwable ignore) { }
            int slot = slotFor(name, 1);
            List<LauncherActivityInfo> acts;
            try {
                acts = la.getActivityList(null, uh); // null = all packages
            } catch (Throwable t) {
                Log.w(TAG, "getActivityList u" + userId + " failed", t);
                continue;
            }
            if (acts == null) continue;
            for (LauncherActivityInfo ai : acts) {
                try {
                    out.add(new CloneTarget(userId, slot,
                            ai.getComponentName(), ai.getLabel(), uh));
                } catch (Throwable ignore) { }
            }
        }
        return out;
    }

    /**
     * For work-tab suppression: true when EVERY managed profile on device is
     * ours, so the launcher can hide the Work tab entirely (Samsung look).
     */
    public static boolean onlyOursManagedProfiles(Context ctx) {
        UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        boolean foundManaged = false;
        try {
            for (android.content.pm.UserInfo ui : um.getUsers()) {
                if (ui.id == 0) continue;
                boolean managed = false;
                try { managed = um.isManagedProfile(ui.id); } catch (Throwable ignore) { }
                if (!managed) continue;
                foundManaged = true;
                if (!isOurs(ui.name)) return false; // real enterprise work profile -> keep tab
            }
        } catch (Throwable t) {
            return false; // unknown -> keep default launcher behavior
        }
        return foundManaged;
    }
}
