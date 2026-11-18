#!/usr/bin/env bash
# Put Bigme's stock system back on a HiBreak Pro (B651) that runs this Android 17 image.
#
#   scripts/rollback-to-stock.sh --stock DIR            # dry run: prints every command
#   scripts/rollback-to-stock.sh --stock DIR --execute  # does it
#
# DIR holds images extracted from Bigme's stock firmware for YOUR phone (docs/ROLLBACK.md shows how):
#   required: system.img, vbmeta.img
#   optional: system_ext.img, product.img, vendor.img (fastbootd), vbmeta_system.img, vbmeta_vendor.img,
#             boot.img, dtbo.img (bootloader). Every one that is present is flashed to slot a.
# Then metadata and userdata are wiped: ALL DATA ON THE PHONE IS ERASED (Android 15/14 cannot use Android 17 data).
# The bootloader stays unlocked. Re-locking is a separate, riskier step that this script never does.
set -euo pipefail

stock="" execute=0
usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; }
while (($#)); do
  case "$1" in
    --stock) stock=${2:?}; shift 2 ;;
    --execute) execute=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
fail() { echo "rollback: $*" >&2; exit 1; }
run() { echo "+ $*"; ((execute)) || return 0; "$@" </dev/null || fail "command failed: $*"; }
wait_fastboot() { for _ in $(seq 1 60); do fastboot devices </dev/null | grep -q . && return 0; sleep 2; done; fail "no fastboot device"; }
getvar() { fastboot getvar "$1" </dev/null 2>&1 | sed -n "s/^$1: *//p" | head -1 | tr -d '\r'; }

command -v adb >/dev/null && command -v fastboot >/dev/null || fail "adb and fastboot are needed (Android platform-tools)"
[[ -d "$stock" ]] || { usage >&2; exit 2; }
for f in system vbmeta; do [[ -s "$stock/$f.img" ]] || fail "missing $stock/$f.img"; done
((execute)) || echo "DRY RUN: nothing is sent to the phone. Add --execute to roll back."

fastboot devices </dev/null 2>/dev/null | grep -q . || run adb reboot fastboot
((execute)) && wait_fastboot
if ((execute)) && [[ "$(getvar is-userspace)" != yes ]]; then run fastboot reboot fastboot; wait_fastboot; fi
if ((execute)); then
  [[ "$(getvar is-userspace)" == yes ]] || fail "not in fastbootd"
  [[ "$(getvar current-slot)" == a ]] || fail "phone is not on slot a"
fi
# Dynamic partitions: only fastbootd can write (and resize) them.
for p in system system_ext product vendor; do
  [[ -s "$stock/$p.img" ]] && run fastboot flash "$p" "$stock/$p.img"
done

# Physical partitions and vbmeta: bootloader fastboot. vbmeta goes last, without the disable flags.
run fastboot reboot bootloader
((execute)) && wait_fastboot
for p in dtbo boot; do
  [[ -s "$stock/$p.img" ]] && run fastboot flash "${p}_a" "$stock/$p.img"
done
for p in vbmeta_system vbmeta_vendor vbmeta; do
  [[ -s "$stock/$p.img" ]] && run fastboot flash "${p}_a" "$stock/$p.img"
done
run fastboot erase metadata
run fastboot format:f2fs userdata
run fastboot reboot
if ((execute)); then echo "Done. Stock Android boots to its setup screen after a few minutes."
else echo "Dry run finished; nothing was changed."; fi
