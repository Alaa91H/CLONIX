#!/usr/bin/env bash
# Quick rebuild of ClonePilot after editing sources under /mnt/d/EvoX17
# Usage: bash build_dualmessenger.sh [WORKDIR]   (default: $HOME/evox17)
set -e
WORKDIR="${1:-$HOME/evox17}"
DEVICE="marble"
cd "$WORKDIR"
WIN_SRC="/mnt/d/EvoX17/packages/apps/DualMessenger"
if [ -d "$WIN_SRC" ]; then
  rm -rf packages/apps/DualMessenger
  cp -r "$WIN_SRC" packages/apps/DualMessenger
fi
source build/envsetup.sh
COMBO="$(lunch 2>/dev/null | grep -o "lineage_${DEVICE}-[a-z0-9]*-userdebug" | head -n1 || true)"
if [ -z "$COMBO" ]; then COMBO="lineage_${DEVICE}-userdebug"; fi
lunch "$COMBO"
m ClonePilot
echo "--- push to device (userdebug, adb root) ---"
echo "adb root && adb remount && \\"
echo "  adb push \$OUT/system/priv-app/ClonePilot/ClonePilot.apk /system/priv-app/ClonePilot/ && \\"
echo "  adb push packages/apps/DualMessenger/etc/privapp-permissions-com.clonepilot.app.xml /system/etc/permissions/ && \\"
echo "  adb reboot"
