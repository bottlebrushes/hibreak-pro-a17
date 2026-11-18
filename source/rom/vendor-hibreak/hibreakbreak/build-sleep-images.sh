#!/usr/bin/env bash
# Build the sleep dream (HiBreakProbe, public variant) from vendor/hibreakbreak-pro/sleep-images, with our patches
# (30-hibreak/vendor_hibreakbreak-pro/0006: draws E-ink Shade's lock page first), and replace the upstream binary in
# prebuilts/. Signed with the platform key: the app runs as android.uid.system. Run after fetch-artifacts.sh.
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
top=${ANDROID_BUILD_TOP:-$(cd "$here/../../.." && pwd)}
src="$top/vendor/hibreakbreak-pro/sleep-images"
bin="$top/out/host/linux-x86/bin"
jar="$top/prebuilts/sdk/35/public/android.jar"
key="$top/build/make/target/product/security/platform"
export PATH="$top/prebuilts/jdk/jdk21/linux-x86/bin:$PATH"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src" "$work/classes" "$work/dex"
for f in BirdDreamService SleepImageDeck SleepImages SleepImagesActivity; do cp "$src/$f.java" "$work/src/"; done
sed 's/PUBLIC=false/PUBLIC=true/' "$src/Variant.java" > "$work/src/Variant.java"
grep -q 'PUBLIC=true' "$work/src/Variant.java"
javac -source 8 -target 8 -Xlint:-options -nowarn -cp "$jar" -d "$work/classes" "$work/src/"*.java
"$bin/d8" --min-api 28 --lib "$jar" --output "$work/dex" "$work/classes/local/codex/hibreakprobe/"*.class
"$bin/aapt2" link -o "$work/unsigned.apk" -I "$jar" --manifest "$src/AndroidManifest-public.xml"
(cd "$work/dex" && zip -q -j ../unsigned.apk classes.dex)
"$bin/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$bin/apksigner" sign --key "$key.pk8" --cert "$key.x509.pem" --out "$here/prebuilts/HiBreakProbe.apk" "$work/aligned.apk"
chmod 0644 "$here/prebuilts/HiBreakProbe.apk"
printf 'HiBreakProbe.apk built from %s\n' "$src"
