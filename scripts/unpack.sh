#!/usr/bin/env bash
# Check and unpack a downloaded release image.
#   scripts/unpack.sh hibreak-pro-a17-<version>-<variant>.img.xz
# If the image came in parts (….img.xz.part00, .part01, …), give the first part or the .img.xz name; the parts are
# joined in order. Every file is checked against SHA256SUMS (from the same release, in the same directory), then the
# image is decompressed to ….img and its own .img.sha256 is checked as well.
set -euo pipefail
fail() { echo "unpack: $*" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
[[ $# == 1 ]] || { sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
command -v xz >/dev/null || fail "xz not found (Linux: package xz-utils; macOS: brew install xz)"
xzf=${1%.part[0-9][0-9]}
dir=$(cd "$(dirname "$xzf")" && pwd)
base=$(basename "$xzf")
cd "$dir"
[[ -f SHA256SUMS ]] || fail "SHA256SUMS from the release is missing in $dir"

check() {
  local want
  want=$(awk -v f="$1" '$2 == f || $2 == "*"f {print $1}' SHA256SUMS)
  [[ -n "$want" ]] || fail "$1 is not listed in SHA256SUMS"
  [[ "$(sha256 "$1")" == "$want" ]] || fail "$1: checksum mismatch (download it again)"
  echo "OK  $1"
}

parts=("$base".part[0-9][0-9])
if [[ -f "${parts[0]}" ]]; then
  for p in "${parts[@]}"; do check "$p"; done
  echo "joining ${#parts[@]} parts -> $base"
  cat "${parts[@]}" >"$base"
else
  [[ -f "$base" ]] || fail "neither $base nor $base.part00 found in $dir"
fi
check "$base"
img=${base%.xz}
echo "decompressing -> $img (3.4–5.7 GB)"
xz -d -k -f -T0 "$base"
check "$img"
sha256 "$img" | sed "s|\$|  $img|" >"$img.sha256"
echo "ready: $dir/$img"
