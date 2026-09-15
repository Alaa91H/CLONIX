#!/usr/bin/env bash
# ROM source sync + Clonix module build
# RUN INSIDE WSL2 Ubuntu (NOT Windows, NOT /mnt/d). AOSP cannot build on NTFS.
# Usage:  bash repo_sync.sh [WORKDIR]     (default: $HOME/evox17)
set -e
WORKDIR="${1:-$HOME/evox17}"
DEVICE="marble"
BRANCH="cnb"
MANIFEST="https://github.com/Evolution-X/manifest"

echo "=== 0) disk check (need 400GB+ free on ext4) ==="
df -h "$HOME" | tail -1

echo "=== 1) build dependencies (Ubuntu 22.04/24.04) ==="
sudo apt update
sudo apt install -y bc bison build-essential ccache curl flex g++-multilib gcc-multilib \
  git git-lfs gnupg gperf imagemagick lib32readline-dev lib32z1-dev libelf-dev \
  liblz4-tool libsdl1.2-dev libssl-dev libxml2 libxml2-utils lzop pngcrush \
  rsync schedtool squashfs-tools xsltproc zip zlib1g-dev python3 openjdk-17-jdk

echo "=== 2) repo tool ==="
mkdir -p ~/bin
if [ ! -f ~/bin/repo ]; then
  curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
  chmod a+x ~/bin/repo
fi
export PATH=~/bin:$PATH
git lfs install --skip-repo || true
git config --global user.name "${GIT_NAME:-evo-builder}" || true
git config --global user.email "${GIT_EMAIL:-evo-builder@localhost}" || true
git config --global color.ui false || true

echo "=== 3) init + sync (resumable, wiki flags) ==="
mkdir -p "$WORKDIR"
cd "$WORKDIR"
if [ ! -d .repo ]; then
  repo init -u "$MANIFEST" -b "$BRANCH" --git-lfs
fi
# marble is officially supported -> trees come from manifest.
# If unofficial: add .repo/local_manifests/marble.xml BEFORE sync (see wiki: lunch page).
for i in 1 2 3 4 5; do
  repo sync -c -j"$(nproc --all)" --force-sync --no-clone-bundle --no-tags && break
  echo "sync try $i failed, retry in 60s..."
  sleep 60
done

echo "=== 4) bring Clonix sources into tree ==="
WIN_SRC="/mnt/d/EvoX17/packages/apps/DualMessenger"
if [ -d "$WIN_SRC" ]; then
  rm -rf packages/apps/DualMessenger
  cp -r "$WIN_SRC" packages/apps/DualMessenger
  echo "copied from $WIN_SRC"
else
  echo "NOTE: $WIN_SRC not found. Copy your project folder to packages/apps/DualMessenger manually."
fi

echo "=== 5) env + auto lunch + build Clonix module only ==="
source build/envsetup.sh
COMBO="$(lunch 2>/dev/null | grep -o "lineage_${DEVICE}-[a-z0-9]*-userdebug" | head -n1 || true)"
if [ -z "$COMBO" ]; then COMBO="lineage_${DEVICE}-userdebug"; fi
echo "lunch $COMBO"
lunch "$COMBO"
export USE_CCACHE=1
export CCACHE_COMPRESS=1
ccache -M 50G -F 0 || true
m Clonix

echo "=== DONE: out/.../system/priv-app/Clonix/Clonix.apk ==="
