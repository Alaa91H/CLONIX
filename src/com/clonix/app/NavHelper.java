package com.clonix.app;

import android.app.Activity;
import android.content.Intent;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Bottom navigation: Home | Archives | Schedules | Account.
 * Badges show live counts (zero I/O: cached prefs).
 * Gesture-bar safe padding applied for edge-to-edge.
 */
public final class NavHelper {
    private NavHelper() {}

    public static void setup(final Activity a, int selected) {
        try {
            final BottomNavigationView nav = a.findViewById(R.id.bottom_nav);
            if (nav == null) return;
            try {
                nav.setLabelVisibilityMode(
                    com.google.android.material.navigation.NavigationBarView
                        .LABEL_VISIBILITY_LABELED);
            } catch (Throwable ignore) { }
            // Gesture-bar safe padding (edge-to-edge).
            try {
                ViewCompat.setOnApplyWindowInsetsListener(nav, (v, insets) -> {
                    try {
                        int bottom = insets.getInsets(
                            WindowInsetsCompat.Type.navigationBars()).bottom;
                        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                            v.getPaddingRight(), Math.max(bottom,
                                (int) (8 * v.getResources()
                                    .getDisplayMetrics().density)));
                    } catch (Throwable ignore) { }
                    return insets;
                });
            } catch (Throwable ignore) { }
            try { nav.setSelectedItemId(selected); } catch (Throwable ignore) { }
            nav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == selected) return true;
                Class<?> target = null;
                if (id == R.id.nav_home) target = HomeActivity.class;
                else if (id == R.id.nav_sync) target = ArchivesActivity.class;
                else if (id == R.id.nav_schedules) target = SchedulesActivity.class;
                else if (id == R.id.nav_account) target = AccountActivity.class;
                if (target == null) return false;
                try {
                    Intent i = new Intent(a, target);
                    i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    a.startActivity(i);
                    try {
                        a.overridePendingTransition(0, 0);
                    } catch (Throwable ignore) { }
                } catch (Throwable t) { return false; }
                return true;
            });
        } catch (Throwable ignore) { }
    }

    /** Legacy 2-arg signature kept for existing screens. */
    public static void refreshBadges(Activity a,
            BottomNavigationView nav) {
        try {
            if (nav == null) return;
            int sel = -1;
            try { sel = nav.getSelectedItemId(); } catch (Throwable ignore) { }
            int clones = BackupJob.lastClones(a);
            int arch = BackupJob.lastArchives(a);
            try {
                if (clones > 0 && sel != R.id.nav_sync) {
                    nav.getOrCreateBadge(R.id.nav_sync)
                        .setNumber(Math.min(clones, 999));
                } else {
                    nav.removeBadge(R.id.nav_sync);
                }
            } catch (Throwable ignore) { }
            try {
                if (arch > 0 && sel != R.id.nav_sync) {
                    nav.getOrCreateBadge(R.id.nav_sync)
                        .setNumber(Math.min(arch, 999));
                } else {
                    nav.removeBadge(R.id.nav_sync);
                }
            } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
    }

    public static void refreshBadges(Activity a) {
        try {
            refreshBadges(a, (BottomNavigationView) a.findViewById(
                R.id.bottom_nav));
        } catch (Throwable ignore) { }
    }
}
