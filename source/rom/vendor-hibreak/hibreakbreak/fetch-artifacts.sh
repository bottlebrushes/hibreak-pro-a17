#!/usr/bin/env bash
# Fetch the binary pieces of HiBreakBreak Pro 0.1.6 at a pinned release digest.
set -euo pipefail

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
archive="$here/.cache/hibreakbreak-pro-0.1.6-patcher.zip"
url=https://github.com/jaronsjarons/hibreakbreak-pro/releases/download/v0.1.6/hibreakbreak-pro-0.1.6-patcher.zip
sha256=d10e380e4d8cbd0023821c823607f6a1cfaaa844737816c70f581985775ea639
mkdir -p "$(dirname "$archive")" "$here/prebuilts"
if [[ ! -f "$archive" ]] || [[ "$(sha256sum "$archive" | awk '{print $1}')" != "$sha256" ]]; then
    tmp="$archive.tmp.$$"
    trap 'rm -f "$tmp"' EXIT
    curl --fail --location --retry 3 --output "$tmp" "$url"
    [[ "$(sha256sum "$tmp" | awk '{print $1}')" == "$sha256" ]] || {
        printf 'error: checksum mismatch for %s\n' "$url" >&2
        exit 1
    }
    mv "$tmp" "$archive"
    trap - EXIT
fi

prefix=hibreakbreak-pro-0.1.6-patcher/payload/
for name in \
    HiBreakProbe.apk \
    bootanimation.zip \
    hibreak_bootanimation.rc \
    initial-package-stopped-states-refresh-prince.xml; do
    unzip -p "$archive" "$prefix$name" > "$here/prebuilts/$name"
done
mkdir -p "$here/built-in-defaults/res/drawable-nodpi"
unzip -p "$archive" "${prefix}RefreshPrinceDefaults.apk" \
    > "$here/.cache/RefreshPrinceDefaults.apk"
unzip -p "$here/.cache/RefreshPrinceDefaults.apk" 'res/drawable-nodpi-v4/default_wallpaper.png' \
    > "$here/built-in-defaults/res/drawable-nodpi/default_wallpaper.png"
chmod 0644 "$here/prebuilts"/* "$here/built-in-defaults/res/drawable-nodpi/default_wallpaper.png"
printf 'HiBreakBreak Pro 0.1.6 binary payload verified: %s\n' "$sha256"
# the sleep dream is rebuilt from source with our patches (replaces the upstream HiBreakProbe.apk above)
"$here/build-sleep-images.sh"
