# Installing Android 17 on the HiBreak Pro (B651)

Read the [README](../README.md) (requirements, risks) first. Every command runs on your computer, in the folder where
you put this repository's `scripts/` and the downloads. Commands are for Linux and macOS; on Windows type the
`adb`/`fastboot` lines from the scripts by hand.

What gets changed on the phone, all on slot **a**:

| Partition | Change |
|---|---|
| `vbmeta_a` | Your stock `vbmeta.img`, flashed with verity and verification disabled (needed for any GSI) |
| `system_a` | Replaced by the Android 17 image (about 5.7 GB; fastbootd resizes the logical partition) |
| `metadata`, `userdata` | Erased / formatted: **all data is gone** |
| everything else | Untouched: `boot`, `vendor_boot`, `dtbo`, `vendor`, `product`, `system_ext`, modem, `lk`, `preloader`, `nvram`, `nvdata`, `protect*`, `proinfo` |

`product_a` and `system_ext_a` stay in the `super` partition but are not used by Android 17 (the image carries its own).

## 1. Back up

Everything on the phone is erased twice (bootloader unlock and install). Before anything else:

1. Let your apps back up: Google account sync (contacts, calendar, photos), WhatsApp/Signal/… chat backups,
   authenticator apps (move the codes first!), SMS (an SMS backup app), anything with local data.
2. With USB debugging on (Settings > About > tap *Build number* 7 times, then Developer options > USB debugging):
   ```sh
   scripts/backup.sh backup-$(date +%Y%m%d)
   ```
   It copies shared storage (`/sdcard`: photos, downloads, documents), the app list, and the phone's firmware
   properties. `key-props.txt` names your stock firmware (`ro.build.versionCode`, `ro.build.display.id`).
3. Write down your IMEI (dial `*#06#`) and keep it somewhere other than the phone.
4. **Get your stock firmware images now**, while the phone still runs stock: see
   [ROLLBACK.md, "Get the stock images first"](ROLLBACK.md#get-the-stock-images-first). You need `vbmeta.img` for
   step 4 and `system.img` to go back.

## 2. Unlock the bootloader (erases the phone)

1. Developer options > **OEM unlocking**: on (needs an internet connection on some firmware).
2. ```sh
   adb reboot bootloader
   fastboot getvar product        # k6877v1_64_k419
   fastboot getvar current-slot   # a
   fastboot flashing unlock       # confirm on the phone with the volume and power keys
   ```
   Older bootloaders use `fastboot oem unlock`. The phone wipes itself and shows an "orange state" warning at every
   boot from now on. `fastboot getvar unlocked` then says `yes`.
3. If `current-slot` says `b`: stop here and open an issue. The scripts and these steps are written and tested for
   slot a only, and switching slots by hand can leave the phone booting an incomplete slot.

## 3. Download and unpack the image

From the [release page](https://github.com/bottlebrushes/hibreak-pro-a17/releases), download into one folder:

- `hibreak-pro-a17-<version>-gapps.img.xz` (or `-vanilla`), or all of its `.partNN` pieces if it was split;
- `SHA256SUMS`.

```sh
scripts/unpack.sh hibreak-pro-a17-<version>-gapps.img.xz
```

This checks every download against `SHA256SUMS`, joins parts (if any), decompresses to
`hibreak-pro-a17-<version>-gapps.img` and checks that too. Manual equivalent:

```sh
sha256sum -c SHA256SUMS --ignore-missing            # macOS: shasum -a 256 -c SHA256SUMS --ignore-missing
cat hibreak-pro-a17-<version>-gapps.img.xz.part* > hibreak-pro-a17-<version>-gapps.img.xz   # only if split
xz -dk hibreak-pro-a17-<version>-gapps.img.xz
```

## 4. Install

Leave the phone booted with USB debugging on (the script reboots it to the bootloader), or put it in bootloader mode
yourself with `adb reboot bootloader`. First a dry run, which only prints the commands:

```sh
scripts/install.sh --image hibreak-pro-a17-<version>-gapps.img --vbmeta stock/vbmeta.img
```

Then for real:

```sh
scripts/install.sh --image hibreak-pro-a17-<version>-gapps.img --vbmeta stock/vbmeta.img --execute
```

The script checks the board (`k6877…`), that the bootloader is unlocked and that slot a is active, then runs:

```sh
fastboot --disable-verity --disable-verification flash vbmeta_a stock/vbmeta.img   # bootloader fastboot
fastboot reboot fastboot                                                           # -> fastbootd
fastboot flash system hibreak-pro-a17-<version>-gapps.img                          # resizes system_a
fastboot reboot bootloader
fastboot erase metadata
fastboot format:f2fs userdata
fastboot reboot
```

Notes:

- `fastboot flash system` must run in **fastbootd** (`fastboot getvar is-userspace` = `yes`); the bootloader's own
  fastboot cannot resize logical partitions. The wipe must run in the **bootloader** fastboot: this phone's fastbootd
  cannot see `userdata`.
- If fastbootd says there is not enough space in `super`, a stock update left copies on slot b. Remove them in
  fastbootd and flash again: `fastboot delete-logical-partition system_b` (and `system_ext_b`, `product_b`,
  `vendor_b`; `fastboot getvar all` lists them). Never delete `*_a` partitions.
- Use a USB port directly on the computer, and a data cable. A 5.7 GB flash takes a few minutes.
- Phone already on a GSI with verification off (e.g. an earlier release): `--skip-vbmeta` instead of `--vbmeta`.

## 5. First boot

- The first boot takes 3–6 minutes (the e-ink panel may stay on the boot animation for a while). Don't interrupt it.
- The setup wizard follows. On the GApps variant sign in to Google there or later.
- The E-ink Shade and the HiBreak controller start after the first unlock (they are not "encryption aware"); until
  then the stock Android lock screen shows. That is normal, also after every reboot.
- Settings > **E-ink display** holds the refresh modes, front light and button settings; the shade's own settings
  are in the shade (gear icon).

## Updating to a newer release

Your data can stay when going from one release of this image to the next (same Android version): flash only
`system` from fastbootd, no wipe:

```sh
adb reboot fastboot
fastboot flash system hibreak-pro-a17-<new-version>-gapps.img
fastboot reboot
```

Don't switch between the GApps and vanilla variants without a wipe. Going back to an older release may need a wipe.
