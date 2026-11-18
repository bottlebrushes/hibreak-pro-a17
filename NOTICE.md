# Notices and third-party components

Our own work in this repository (the patches we wrote in `source/rom/patches/30-hibreak` and
`source/rom/patches/35-restless-legacy` where marked as ours, `source/shade`, `source/rom/vendor-hibreak`, the build
and install scripts, and the docs) is licensed under the Apache License 2.0 (`LICENSE`).

The release images are built from, and contain, the following parts that are **not ours** and keep their own
licences and terms:

| Part | Where it comes from | Licence / terms |
|---|---|---|
| Android Open Source Project | <https://source.android.com> (`android-17.0.0_r1`) | Apache-2.0 and others, per project (`/system/etc/NOTICE.xml.gz` in the image) |
| LineageOS, Evolution X | <https://github.com/LineageOS>, <https://github.com/Evolution-X> (`cnb`) | Apache-2.0 and others, per project |
| TrebleDroid / phh treble patches, RestlessOS Android 17 Treble layer | <https://github.com/TrebleDroid>, <https://github.com/cawilliamson/treble_restlessos> | Apache-2.0 / per project; patches in `source/rom/patches/10-*`, `20-*`, `25-*`, `35-*` keep their authors |
| HiBreakBreak Pro 0.1.6 (controller sources, sleep dream, overlays, boot animation) | <https://github.com/jaronsjarons/hibreakbreak-pro> | Apache-2.0; its `controller/` is MIT (damianmqr, vbbot's HibreakProPatchingService) with AOSP code under Apache-2.0 |
| Loop Habit Tracker 2.3.2 (black-and-white build) | <https://github.com/iSoron/uhabits>, F-Droid build `org.isoron.uhabits_20302` | **GPL-3.0**. Our changes: `source/loop-bw` (patch, provider source and the script that rebuilds the APK from the F-Droid release) |
| Google Mobile Services and Google apps (**GApps variant only**) | Evolution X's `vendor/gms` | **Proprietary, Google's terms.** Not licensed by us. Shipped as Evolution X and most custom ROMs ship it; the vanilla variant leaves it out |
| Bigme XRZ e-ink waveform file `/system/eink_key` | Copied unmodified from Bigme's publicly distributed stock HiBreak Pro firmware (sha256 `69804441c70e866d81e0a80dfb762a993076c1325e41aee1d604925c20f977a1`) | **Bigme's, proprietary.** Not ours; included because the stock panel driver needs it on a non-stock system. It is not in `source/` |
| Lock-screen paintings | Downloaded at run time from the Art Institute of Chicago API (public-domain works only) | Public domain (CC0 data) |

What is **not** included anywhere here: Bigme's kernel, vendor, boot or modem images (they stay on your phone),
Bigme's apps, or any decoded vendor app.

"Bigme" and "HiBreak" are Bigme's names; "Android" is Google's. They are used only to describe what this is for.
This project is not affiliated with or endorsed by Bigme, Google, LineageOS, Evolution X or TrebleDroid.
