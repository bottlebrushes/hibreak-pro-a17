#!/usr/bin/env bash
# Copy what adb can read off the phone before the install wipes it. Read-only for the phone.
#   scripts/backup.sh OUT_DIR
# Saves: shared storage (/sdcard: photos, downloads, documents, …), the list of installed apps, the phone's
# build/firmware properties and partition list (needed to pick the right stock firmware for a rollback).
# NOT saved (adb cannot read them without root): app data, SMS, call log, Wi-Fi passwords, accounts.
# Back those up in the apps themselves / your Google account first (docs/INSTALL.md, step 1).
set -euo pipefail
[[ $# == 1 ]] || { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
out=$1
command -v adb >/dev/null || { echo "backup: adb not found" >&2; exit 1; }
adb get-state </dev/null | grep -q device || { echo "backup: no authorised adb device" >&2; exit 1; }
mkdir -p "$out"
adb shell getprop >"$out/getprop.txt"
for p in ro.build.versionCode ro.build.display.id ro.build.fingerprint ro.vendor.build.fingerprint \
         ro.product.board ro.boot.slot_suffix ro.boot.verifiedbootstate ro.boot.flash.locked; do
  printf '%s = %s\n' "$p" "$(adb shell getprop "$p" </dev/null | tr -d '\r')"
done | tee "$out/key-props.txt"
adb shell ls -l /dev/block/by-name/ >"$out/by-name.txt" 2>&1 || true
adb shell pm list packages -3 >"$out/user-apps.txt"
echo "pulling /sdcard (this can take a while)…"
adb pull /sdcard/ "$out/sdcard"
echo "Backup in $out. Keep key-props.txt: ro.build.versionCode and ro.build.display.id name your stock firmware."
