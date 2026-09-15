# CLONIX ROM integration
# Add to vendor/.../config/common.mk or device/<codename>/device.mk:

# PRODUCT_PACKAGES += Clonix

# Permissions allowlist (privapp):
# PRODUCT_COPY_FILES += \
#     packages/apps/Clonix/etc/privapp-permissions-com.clonix.app.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-com.clonix.app.xml

# Optional overlays to keep clone users hidden from user switcher:
# - config_multiuserMaximumUsers = 8 (needed for N clones)
# - Keep config_enableMultiUserUI=false so Clone_* users stay hidden
#
# vendor/.../overlay/.../frameworks/base/core/res/res/values/config.xml:
#
# <resources>
#   <integer name="config_multiuserMaximumUsers">8</integer>
#   <bool name="config_enableMultiUserUI">false</bool>
# </resources>
#
# Build:
#   source build/envsetup.sh
#   lunch lineage_<device>-userdebug
#   m Clonix
#
# Standalone instead (no ROM rebuild):
#   build APK via gradle-build/ (local Gradle or GitHub Actions),
#   install normally + grant root, or flash ksu-module/ + reboot.
#
# Test without full ROM build (userdebug, rooted):
#   adb shell pm list users
#   adb shell pm create-user --user-type android.os.usertype.profile.CLONE --profileOf 0 CloneSpace
#   adb shell pm install-existing --user <id> com.whatsapp
#   adb shell am start --user <id> -n com.whatsapp/.MainActivity
#
# Storage check (proof of minimal usage):
#   adb shell du -sh /data/app/*whatsapp*  # APK shared
#   # only /data/user/<id> differs per clone.
