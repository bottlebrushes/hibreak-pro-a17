#!/usr/bin/env bash
# Apply rom/patches/<tier>/<project>/*.patch to an Evolution X cnb tree, tiers in name order.
# Project dirs use the TrebleDroid naming (platform_frameworks_base -> frameworks/base).
# Usage: rom/apply-patches.sh <tree> [--keep-going]
#   --keep-going: on a failed patch, record it in <tree>/patch-failures.txt, skip it and continue.
set -euo pipefail
shopt -s nullglob
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree=$(cd -- "$1" && pwd)
keep=${2:-}
fail_log="$tree/patch-failures.txt"
: >"$fail_log"

path_of() {
  local p
  p="$(tr _ / <<<"$1" | sed -e 's;^platform/;;')"
  case "$p" in
    build) p=build/make ;;
    system/fs/fs/mgr) p=system/fs/fs_mgr ;;
    testing) p=platform_testing ;;
    treble/app) p=treble_app ;;
    vendor/hardware/overlay) p=vendor/hardware_overlay ;;
    vendor/partner/gms) p=vendor/partner_gms ;;
  esac
  echo "$p"
}

applied=0 failed=0
for tier in "$here"/patches/*/; do
  echo "== $(basename "$tier")"
  for project in "$tier"*/; do
    project=$(basename "$project")
    dir="$tree/$(path_of "$project")"
    [ -d "$dir" ] || { echo "missing project dir $dir" >&2; exit 1; }
    for patch in "$tier$project"/*.patch; do
      if git -C "$dir" am -q --3way --committer-date-is-author-date "$patch" >/dev/null 2>&1; then
        applied=$((applied + 1))
      else
        git -C "$dir" am --abort >/dev/null 2>&1 || true
        failed=$((failed + 1))
        echo "FAIL $(basename "$tier")/$project/$(basename "$patch")" | tee -a "$fail_log"
        [ "$keep" = --keep-going ] || exit 1
      fi
    done
  done
done
echo "applied=$applied failed=$failed (failures listed in $fail_log)"
[ "$failed" -eq 0 ]
