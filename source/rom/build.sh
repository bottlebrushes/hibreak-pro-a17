#!/usr/bin/env bash
# Build the HiBreak Pro Android 17 GSI (Evolution X cnb + Treble layer) system image.
# Usage: rom/build.sh <tree> [product] [release]
#   tree: Evolution X cnb checkout with .repo/local_manifests/hibreak-a17.xml, synced, rom/apply-patches.sh applied.
#   product defaults to treble_arm64_bgN (GApps, no su), release to cp2a.
# Output: <tree>/out/target/product/tdgsi_arm64_ab/system.img (ext4).
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree=$(cd -- "$1" && pwd)
product=${2:-treble_arm64_bgN}
release=${3:-cp2a}
cd "$tree"

waveform=${HIBREAK_BLOBS:-$HOME/hibreak-blobs}/eink_key
if [[ ! -f "$waveform" ]]; then
  printf 'error: missing XRZ waveform key %s (extract /system/eink_key from a stock Bigme system image, see source/README.md)\n' "$waveform" >&2
  exit 1
fi
if [[ "$(sha256sum "$waveform" | awk '{print $1}')" != 69804441c70e866d81e0a80dfb762a993076c1325e41aee1d604925c20f977a1 ]]; then
  printf 'error: XRZ waveform key checksum mismatch for %s\n' "$waveform" >&2
  exit 1
fi
mkdir -p device/phh/treble/bigme-display
cp "$waveform" device/phh/treble/bigme-display/eink_key

mkdir -p device/hibreak
cp "$here/evo-hibreak.mk" device/hibreak/evo-hibreak.mk
# EinkShade is compiled by Soong from the checked-in source tree. Stage the
# source under vendor-hibreak before the vendor rsync (which uses --delete).
rm -rf "$here/vendor-hibreak/mods/shade-src"
mkdir -p "$here/vendor-hibreak/mods/shade-src"
rsync -a --delete "$here/../shade/src/" "$here/vendor-hibreak/mods/shade-src/src/"
rsync -a --delete "$here/../shade/res/" "$here/vendor-hibreak/mods/shade-src/res/"
cp "$here/../shade/AndroidManifest.xml" "$here/vendor-hibreak/mods/shade-src/AndroidManifest.xml"
rsync -a --delete "$here/vendor-hibreak/" vendor/hibreak/
# Rebuild TrebleApp when its sources (patched by 30-hibreak/treble_app) are newer than the APK, and ship that
# build: the image installs vendor/hardware_overlay/TrebleApp/app.apk, a stale upstream prebuilt otherwise.
if [ ! -f treble_app/TrebleApp.apk ] || [ -n "$(find treble_app/app/src -newer treble_app/TrebleApp.apk -print -quit)" ]; then
  (cd treble_app && bash build.sh release)
fi
cp treble_app/TrebleApp.apk vendor/hardware_overlay/TrebleApp/app.apk
# Prebuilt APEXes with EROFS payloads can't mount on the 4.19 kernel (no EROFS). Google's GmsCore ships as one
# (com.google.android.gmssystem); re-pack them as ext4 in place. Needs host tools from a previous build;
# offline-check.sh's apex-fs row catches a skipped conversion.
if [ -x out/host/linux-x86/bin/apexer ]; then
  for a in vendor/gms/apex/*.apex; do "$here/apex-to-ext4.sh" "$a" "$a.ext4" && mv "$a.ext4" "$a"; done
else
  echo "warning: host APEX tools not built yet; EROFS prebuilt APEXes left as-is" >&2
fi

export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache CCACHE_DIR=${CCACHE_DIR:-$tree/../ccache}
ccache -M 50G >/dev/null
# AOSP's aosp_system_image (build/make/target/product/gsi) lists apps Evolution X removes (Calendar, ...). It isn't
# built for treble_* products, but Soong analysis rejects its missing deps unless they're allowed.
export ALLOW_MISSING_DEPENDENCIES=true
# TrebleDroid links vendor HAL client libs into the framework; see 30-hibreak/platform_build_soong.
export TREBLE_ALLOW_NON_GENERIC_SYSTEM_IMAGE=true
set +u
source build/envsetup.sh
lunch "$product-$release-userdebug"
set -u
# JOBS caps parallelism: on a machine shared with other work a full -j$(nproc) ran it out of memory.
jobs=${JOBS:-$(nproc)}
# Stage 1: compile only the modules our patches touch, so a broken patch fails in minutes, not after hours.
# -k 0 keeps going after a failure, so one run lists every broken module. Skip with PRECHECK=0.
if [ "${PRECHECK:-1}" = 1 ]; then
  m -k 0 -j"$jobs" $(grep -vE '^\s*(#|$)' "$here/precheck-modules.txt")
fi
# Stage 2: the full image; -k 0 again collects all remaining errors in one pass.
m -k 0 systemimage -j"$jobs"
img=out/target/product/tdgsi_arm64_ab/system.img
ls -l "$img"
