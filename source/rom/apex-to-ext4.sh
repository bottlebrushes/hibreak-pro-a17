#!/usr/bin/env bash
# Re-pack a prebuilt APEX with an ext4 payload (same contents).
# The HiBreak 4.19 kernel has no EROFS, so EROFS-payload APEXes (e.g. Google's GmsCore APEX
# com.google.android.gmssystem) never activate. The payload is re-signed with an AVB test key and
# that key is bundled as apex_pubkey (apexd trusts pre-installed APEXes by the key they carry);
# the container is re-signed with the platform test key.
# Usage: apex-to-ext4.sh <in.apex> <out.apex> [host-bin-dir]
set -euo pipefail
in=$(realpath "$1"); out=$(realpath -m "$2")
B=${3:-/data/android17/out/host/linux-x86/bin}
top=$(cd "$B/../../../.." && pwd)
key=$top/system/apex/apexer/testdata/com.android.example.apex.pem
cert_pk8=$top/build/make/target/product/security/platform.pk8
cert_pem=$top/build/make/target/product/security/platform.x509.pem
export PATH=$B:$PATH
w=$(mktemp -d); trap 'rm -rf "$w"' EXIT

# Already ext4? Nothing to do.
if python3 - "$in" <<'EOF'
import sys, zipfile
d = zipfile.ZipFile(sys.argv[1]).open('apex_payload.img').read(2048)
sys.exit(0 if d[1080:1082] == b'\x53\xef' else 1)
EOF
then cp "$in" "$out"; echo "already ext4: $(basename "$in")"; exit 0; fi

unzip -q "$in" -d "$w/c"
deapexer --debugfs_path "$B/debugfs_static" --fsckerofs_path "$B/fsck.erofs" extract "$in" "$w/root"
rm -f "$w/root/apex_manifest.pb" "$w/root/apex_manifest.json"  # apexer adds the manifest itself
printf '(/.*)?\tu:object_r:system_file:s0\n' > "$w/file_contexts"
( cd "$w/root" && find . -mindepth 1 | sed 's|^\./||' | sort | while read -r p; do
    if [[ -d "$p" ]]; then echo "$p 0 2000 0755"; else echo "$p 1000 1000 0644"; fi
  done; echo "/ 0 2000 0755"; echo "apex_manifest.pb 1000 1000 0644"; echo "apex_manifest.json 1000 1000 0644" ) > "$w/fs_config"
avbtool extract_public_key --key "$key" --output "$w/pubkey"
apexer --force --payload_only --payload_type image --payload_fs_type ext4 \
  --manifest "$w/c/apex_manifest.pb" --file_contexts "$w/file_contexts" --canned_fs_config "$w/fs_config" \
  --key "$key" --pubkey "$w/pubkey" --apexer_tool_path "$B" --do_not_check_keyname \
  "$w/root" "$w/apex_payload.img"
cp "$w/apex_payload.img" "$w/c/apex_payload.img"; cp "$w/pubkey" "$w/c/apex_pubkey"
rm -rf "$w/c/META-INF"
# apexd mounts the payload straight from the container: store every entry uncompressed.
( cd "$w/c" && python3 -c "import os,sys,zipfile
z=zipfile.ZipFile(sys.argv[1],'w',zipfile.ZIP_STORED)
for r,_,fs in os.walk('.'):
    for f in sorted(fs): p=os.path.join(r,f); z.write(p,os.path.relpath(p,'.'))
z.close()" "$w/u.zip" )
zipalign -f -p 4096 "$w/u.zip" "$w/a.zip"
apksigner sign --key "$cert_pk8" --cert "$cert_pem" --min-sdk-version 29 --out "$out" "$w/a.zip"
echo "ext4 APEX: $out"
