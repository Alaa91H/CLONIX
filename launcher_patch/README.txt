# Launcher integration - Samsung badge without breaking Trebuchet
#
# MVP works WITHOUT any Launcher patch:
# - DualMessenger creates pinned shortcuts with orange badge + number
#   via ShortcutManager + CloneLauncherTrampoline (extra_pkg/extra_userId).
# - MainActivity lists all clones with [فتح/حذف].
#
# Optional native patch (shows clones directly in drawer with badge):
#
# 1) In Launcher3, find where work-profile badge is drawn:
#    src/com/android/launcher3/LauncherAppsCompat.java or BubbleTextView.java
#    method: getBadgedIconForUser() / shouldShowBadge()
#
# 2) Add:
#
#    // DualMessenger: treat our hidden users as clones, not Work
#    private boolean isDualClone(android.os.UserHandle uh) {
#        try {
#            android.os.UserManager um = mContext.getSystemService(android.os.UserManager.class);
#            android.content.pm.UserInfo ui = um.getUserInfo(uh.getIdentifier());
#            return ui != null && (ui.name != null
#                && (ui.name.equals("DualMessenger") || ui.name.startsWith("EvoClone")));
#        } catch (Throwable t) { return false; }
#    }
#
#    // In getBadgedIcon(): if isDualClone(user) -> draw R.drawable.dual_badge_orange
#    // with slot number from DualMessenger ContentProvider/DB instead of briefcase.
#    // In shrunk drawer: do NOT create separate Work tab when profile name == "DualMessenger".
#
# 3) Copy res/drawable/dual_badge.xml from DualMessenger to Launcher3 res/drawable/dual_badge_orange.xml
#
# 4) SystemUI (notifications): in
#    frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/notification/row/ExpandableNotificationRow.java
#    method that resolves work-profile badge (getWorkProfileBadge):
#    if user is DualMessenger/EvoClone -> return dual badge, not briefcase.
#    Single if-block, no other changes.
#
# If you skip this patch, everything still works via shortcuts. Patch is UX only.
