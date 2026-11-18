#!/usr/bin/env bash
# Install the HiBreak Pro Android 17 system image on a Bigme HiBreak Pro (B651) with an unlocked bootloader.
#
#   scripts/install.sh --image system.img --vbmeta vbmeta.img            # dry run: prints every command
#   scripts/install.sh --image system.img --vbmeta vbmeta.img --execute  # does it
#
# What it changes (slot a, the slot the phone boots):
#   1. bootloader fastboot: vbmeta_a re-flashed from YOUR stock vbmeta.img with verity and verification disabled
#      (skip with --skip-vbmeta when the phone already runs a GSI with verification off);
#   2. fastbootd:           system_a replaced by the Android 17 image (fastbootd resizes the logical partition);
#   3. bootloader fastboot: metadata erased and userdata formatted (f2fs) -> ALL DATA ON THE PHONE IS ERASED.
# Not touched: boot, vendor, vendor_boot, dtbo, product, system_ext, modem, nvram/nvdata/protect*/proinfo, preloader, lk.
#
# Start with the phone booted to Android with USB debugging authorised, or already in bootloader fastboot.
# Needs adb and fastboot (Android platform-tools) on PATH. Works on Linux and macOS.
set -euo pipefail

image="" vbmeta="" execute=0 skip_vbmeta=0 force=0
usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
  echo "options: --image FILE  --vbmeta FILE | --skip-vbmeta  [--force] [--execute]"
}
while (($#)); do
  case "$1" in
    --image) image=${2:?}; shift 2 ;;
    --vbmeta) vbmeta=${2:?}; shift 2 ;;
    --skip-vbmeta) skip_vbmeta=1; shift ;;
    --force) force=1; shift ;;
    --execute) execute=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

fail() { echo "install: $*" >&2; exit 1; }
run() { echo "+ $*"; ((execute)) || return 0; "$@" </dev/null || fail "command failed: $*"; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
wait_fastboot() { for _ in $(seq 1 60); do fastboot devices </dev/null | grep -q . && return 0; sleep 2; done; fail "no fastboot device"; }
getvar() { fastboot getvar "$1" </dev/null 2>&1 | sed -n "s/^$1: *//p" | head -1 | tr -d '\r'; }

command -v adb >/dev/null || fail "adb not found (install Android platform-tools)"
command -v fastboot >/dev/null || fail "fastboot not found (install Android platform-tools)"
[[ -n "$image" ]] || { usage >&2; exit 2; }
[[ -f "$image" ]] || fail "missing image: $image"
case "$image" in *.xz|*.part*) fail "$image is still compressed/split: run scripts/unpack.sh first" ;; esac
((skip_vbmeta)) || [[ -f "$vbmeta" ]] || fail "give --vbmeta (stock vbmeta.img from your Bigme firmware) or --skip-vbmeta"
if [[ -f "$image.sha256" ]]; then
  [[ "$(sha256 "$image")" == "$(cut -d' ' -f1 "$image.sha256")" ]] || fail "image checksum mismatch ($image.sha256)"
  echo "image checksum OK"
else
  echo "warning: no $image.sha256 next to the image; checksum not verified" >&2
fi
size=$(wc -c <"$image" | tr -d ' ')
echo "image: $image ($size bytes)"
((execute)) || echo "DRY RUN: nothing is sent to the phone. Add --execute to install."

# Into bootloader fastboot.
if ((execute)); then
  if ! fastboot devices </dev/null | grep -q .; then
    adb get-state </dev/null 2>/dev/null | grep -q device || fail "phone not found by adb or fastboot"
    [[ "$(adb shell getprop ro.board.platform </dev/null | tr -d '\r')" == mt6877 ]] || ((force)) || fail "not an MT6877 phone (use --force to override)"
  fi
fi
fastboot devices </dev/null 2>/dev/null | grep -q . || run adb reboot bootloader
if ((execute)); then
  wait_fastboot
  [[ "$(getvar is-userspace)" != yes ]] || run fastboot reboot bootloader
  wait_fastboot
  product=$(getvar product); unlocked=$(getvar unlocked); slot=$(getvar current-slot)
  echo "fastboot: product=$product unlocked=$unlocked current-slot=$slot"
  [[ "$unlocked" == yes ]] || fail "bootloader is locked: unlock it first (docs/INSTALL.md, step 2)"
  [[ "$slot" == a ]] || ((force)) || fail "phone boots slot '$slot'; this script and the tested setup use slot a (--force to override)"
  [[ "$product" == k6877* ]] || ((force)) || fail "fastboot product '$product' is not the HiBreak Pro board k6877v1_64_k419 (--force to override)"
fi

((skip_vbmeta)) || run fastboot --disable-verity --disable-verification flash vbmeta_a "$vbmeta"

run fastboot reboot fastboot
if ((execute)); then
  wait_fastboot
  [[ "$(getvar is-userspace)" == yes ]] || fail "not in fastbootd (userspace fastboot)"
fi
run fastboot flash system "$image"

# fastbootd on this phone cannot see userdata: wipe from the bootloader. Android 17 cannot use the old data.
run fastboot reboot bootloader
((execute)) && wait_fastboot
run fastboot erase metadata
run fastboot format:f2fs userdata
run fastboot reboot
if ((execute)); then echo "Done. The first boot takes several minutes and shows the setup wizard. Don't interrupt it."
else echo "Dry run finished; nothing was changed."; fi
