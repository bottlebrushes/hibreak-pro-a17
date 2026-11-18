# Building the image

Everything the release images are built from, apart from the upstream Android trees (fetched with `repo`) and
Bigme's waveform file (taken from stock firmware).

| Path | What |
|---|---|
| `rom/manifests/hibreak-a17.xml` | Local manifest: Treble layer (RestlessOS / cawilliamson A17 forks) and HiBreakBreak Pro `e03e678` on top of Evolution X `cnb` |
| `rom/manifests/pinned-20261111.xml` | The full tree pinned to the revisions the release was built from (`repo manifest -r`) |
| `rom/patches/<tier>/<project>/*.patch` | Applied in tier order by `rom/apply-patches.sh` (project names use TrebleDroid naming, `platform_frameworks_base` = `frameworks/base`) |
| `rom/patches/10-trebledroid`, `25-trebledroid-ported` | TrebleDroid patches (RestlessOS `android-17.0`), some rebased onto Evolution X |
| `rom/patches/20-gsi-oldkernel`, `35-restless-legacy` | Old-kernel (4.19) support: BPF loader, cgroups, SELinux xperms, storage, Wi-Fi scan, … |
| `rom/patches/30-hibreak` | HiBreak Pro changes: XRZ e-ink composition and damage, GC16 clear, waveform key, Bigme vendor SELinux policy, MicroArray fingerprint stack layout, SystemUI looks, HiBreakBreak controller changes and defaults, sleep-dream lock page |
| `rom/patches/40-release` | Public-release settings: adb requires authorisation (`ro.adb.secure=1`) and USB debugging is off by default (TrebleDroid ships both the other way) |
| `rom/evo-hibreak.mk` | Product makefile added to every `treble_*` product (ext4 APEXes, no AVF, Evolution X config, HiBreak modules) |
| `rom/vendor-hibreak` | Synced to `vendor/hibreak/`: HiBreakBreak payload (overlays, defaults, sleep dream), E-ink Shade module, Loop B&W |
| `shade/` | E-ink Shade app source (built by Soong as a platform-signed privileged app) |
| `loop-bw/` | Black-and-white Loop Habit Tracker: patch over the F-Droid 2.3.2 APK plus the habits content provider; `apply.sh` rebuilds `rom/vendor-hibreak/mods/prebuilt/loop-bw.apk` |
| `rom/build.sh` | Stages the shade, syncs `vendor-hibreak`, builds TrebleApp, repacks EROFS GMS APEXes as ext4, `lunch` + `m systemimage` |

## Steps

Linux x86-64, about 64 GB RAM (Soong analysis alone peaks near 66 GB), 16+ cores, ~350 GB disk.

```sh
mkdir evox && cd evox
repo init -u https://github.com/Evolution-X/manifest -b cnb --git-lfs
mkdir -p .repo/local_manifests && cp ../source/rom/manifests/hibreak-a17.xml .repo/local_manifests/
# exact revisions of the release: cp ../source/rom/manifests/pinned-20261111.xml .repo/manifests/pinned.xml && repo init -m pinned.xml
repo sync -c -j8 --no-tags
../source/rom/apply-patches.sh "$PWD"                 # git am of every patch; --keep-going lists failures

# Bigme's waveform file from a stock system image (ext4) or a running stock phone (/system/eink_key):
mkdir -p ~/hibreak-blobs && debugfs -R "dump /system/eink_key $HOME/hibreak-blobs/eink_key" stock-system.img
sha256sum ~/hibreak-blobs/eink_key                    # 69804441c70e866d81e0a80dfb762a993076c1325e41aee1d604925c20f977a1

# HiBreakBreak Pro binary payload (boot animation, defaults wallpaper) + the patched sleep dream.
# The dream build needs host tools from a first build (out/host/linux-x86/bin/d8, aapt2, apksigner): run it after
# one `rom/build.sh` pass, then build again.
ANDROID_BUILD_TOP=$PWD ../source/rom/vendor-hibreak/hibreakbreak/fetch-artifacts.sh

# GApps variant
JOBS=16 ../source/rom/build.sh "$PWD" treble_arm64_bgN cp2a
# Vanilla variant (no Google apps)
WITH_GMS=false JOBS=16 ../source/rom/build.sh "$PWD" treble_arm64_bvN cp2a
```

Output: `out/target/product/tdgsi_arm64_ab/system.img` (ext4, ~5.7 GB). Release builds are this file unchanged,
compressed with `xz`.

Notes:

- `loop-bw/apply.sh` and `rom/apex-to-ext4.sh` default to the original build machine's tree path; set
  `ANDROID_TREE=<tree>` / pass the tree's `out/host/linux-x86/bin` explicitly.
- Builds are signed with the AOSP test keys in `build/make/target/product/security` (the platform key is shared by
  the controller, the shade and the sleep dream, which run as or talk to system).
- The black-and-white icon pack used on the developer's phone is not part of the public build (its icons are
  derived from Bigme's stock launcher theme).
