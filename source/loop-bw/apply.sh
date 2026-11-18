#!/bin/bash
set -euo pipefail

# Re-create the black & white Loop Habit Tracker 2.3.2 build.  The patched
# APK exposes content://org.isoron.uhabits.eink/habits to E-ink Shade.
#
# The Android tree is used only for host tools and android.jar; override
# ANDROID_TREE for another checkout.  With no input paths the pinned F-Droid
# APK and apktool 2.10.0 are downloaded from their canonical URLs.
# usage: apply.sh [loop-2.3.2-fdroid.apk] [apktool.jar] [keystore storepass [output.apk]]

HERE=$(cd -- "$(dirname -- "$0")" && pwd)
ANDROID_TREE=${ANDROID_TREE:-/data/android17}
ANDROID_JAR=${ANDROID_JAR:-$ANDROID_TREE/prebuilts/sdk/35/public/android.jar}
ZIPALIGN=${ZIPALIGN:-$ANDROID_TREE/out/host/linux-x86/bin/zipalign}
APKSIGNER=${APKSIGNER:-$ANDROID_TREE/out/host/linux-x86/bin/apksigner}
D8=${D8:-$ANDROID_TREE/out/host/linux-x86/bin/d8}
D8_JAVA_HOME=${D8_JAVA_HOME:-$ANDROID_TREE/prebuilts/jdk/jdk21/linux-x86}
OUT=${5:-$HERE/../rom/vendor-hibreak/mods/prebuilt/loop-bw.apk}

LOOP_URL=https://f-droid.org/repo/org.isoron.uhabits_20302.apk
LOOP_SHA256=8a702f85afe83385d5fb61cbcf40536411376f64bbb1712910d4ed00182b8303
APKTOOL_URL=https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar
APKTOOL_SHA256=c0350abbab5314248dfe2ee0c907def4edd14f6faef1f5d372d3d4abd28f0431

W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
download_dir=$W/download
mkdir -p "$download_dir"

verify_sha256() {
    local path=$1 expected=$2 actual
    actual=$(sha256sum "$path" | cut -d' ' -f1)
    [[ $actual == "$expected" ]] || {
        echo "sha256 mismatch for $path: expected $expected, got $actual" >&2
        exit 1
    }
}

APK=${1:-$download_dir/org.isoron.uhabits_20302.apk}
if [[ $# -lt 1 ]]; then
    curl --fail --location --retry 3 --output "$APK" "$LOOP_URL"
fi
verify_sha256 "$APK" "$LOOP_SHA256"

APKTOOL=${2:-$download_dir/apktool_2.10.0.jar}
if [[ $# -lt 2 ]]; then
    curl --fail --location --retry 3 --output "$APKTOOL" "$APKTOOL_URL"
fi
verify_sha256 "$APKTOOL" "$APKTOOL_SHA256"

java -jar "$APKTOOL" d -f -o "$W/src" "$APK"
(cd "$W/src" && patch -p1 < "$HERE/patch/loop-bw.patch")
find "$W/src" -name '*.orig' -delete

mkdir -p "$W/provider-classes" "$W/dex"
javac -source 8 -target 8 -classpath "$ANDROID_JAR" \
    -d "$W/provider-classes" \
    "$HERE/provider-src/org/isoron/uhabits/eink/HabitsProvider.java"
jar --create --file "$W/provider.jar" -C "$W/provider-classes" .
PATH="$D8_JAVA_HOME/bin:$PATH" "$D8" --min-api 35 --output "$W/dex" "$W/provider.jar"
next_dex=2
while [[ -e "$W/src/classes${next_dex}.dex" ]]; do
    next_dex=$((next_dex + 1))
done
cp "$W/dex/classes.dex" "$W/src/classes${next_dex}.dex"

java -jar "$APKTOOL" b -o "$W/unsigned.apk" "$W/src"
"$ZIPALIGN" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
if [[ $# -ge 4 ]]; then
    PATH="$D8_JAVA_HOME/bin:$PATH" "$APKSIGNER" sign --ks "$3" --ks-pass "pass:$4" \
        --out "$OUT" "$W/aligned.apk"
else
    cp "$W/aligned.apk" "$OUT"
fi
echo "built $OUT (unsigned unless keystore and storepass were supplied)"
