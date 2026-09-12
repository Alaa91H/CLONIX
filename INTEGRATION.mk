# DualMessenger ROM integration (Evolution-X cnb)
# Add to vendor/evolution/config/common.mk or device/<codename>/device.mk:

# PRODUCT_PACKAGES += DualMessenger

# Permissions allowlist (privapp):
# PRODUCT_COPY_FILES += \
#     packages/apps/DualMessenger/etc/privapp-permissions-com.evolution.dualmessenger.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-com.evolution.dualmessenger.xml

# Optional overlays to hide Work tab traces and allow more slots:
# - config_multiuserMaximumUsers = 8 (default 1-4, needed for N clones)
# - Keep config_enableMultiUserUI=false so EvoClone users stay hidden like Samsung
#   (users still visible via `pm list users` for debugging)
#
# vendor/evolution/overlay/common/frameworks/base/core/res/res/values/config.xml:
#
# <resources>
#   <integer name="config_multiuserMaximumUsers">8</integer>
#   <bool name="config_enableMultiUserUI">false</bool>
# </resources>
#
# Build:
#   source build/envsetup.sh
#   lunch lineage_<device>-userdebug   # cnb branch
#   m DualMessenger
#   adb root && adb remount
#   adb push $OUT/system/priv-app/DualMessenger/DualMessenger.apk /system/priv-app/DualMessenger/
#   adb push packages/apps/DualMessenger/etc/privapp-permissions-com.evolution.dualmessenger.xml /system/etc/permissions/
#   adb reboot
#
# Test without full ROM build (userdebug, rooted):
#   adb shell pm list users
#   adb shell pm create-user --profileOf 0 --managed DualMessenger
#   adb shell pm install-existing --user 11 com.whatsapp
#   adb shell am start --user 11 -n com.whatsapp/.MainActivity
#
# Storage check (proof of minimal usage):
#   adb shell du -sh /data/app/*whatsapp* /data/user/0/com.whatsapp /data/user/11/com.whatsapp
#   # APK shared, only /data/user/11 differs.
#
# Optional framework patch for PERFECT N-clone notifications:
# By default Slot1 (managed) has native notifications, Slots 2..N are background
# secondary users (notifications work while user started, reboot handled by BootReceiver).
# If you want all N slots to behave exactly like Slot1, allow multiple managed profiles:
#   frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java
#   Remove/relax the `hasManagedProfile()` single-profile check to allow names
#   DualMessenger/EvoClone_*. 5 lines. Not required for MVP.
