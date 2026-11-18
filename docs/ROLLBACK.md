# Going back to Bigme's stock firmware

The install changes only `vbmeta_a`, `system_a` and the data partitions (see [INSTALL.md](INSTALL.md)). Going back
means writing Bigme's own `system` (plus, to be thorough, the other dynamic partitions and boot images) and the stock
`vbmeta` back, then wiping data. You need the stock images for that, so get them **before** you install.

## Get the stock images first

Bigme doesn't publish factory images, but its update server hands out a **full** OTA package (all partitions) for
your phone's firmware line. [marek-vybiral/bigme-ota-client](https://github.com/marek-vybiral/bigme-ota-client)
asks for it; it was written for and tested on the black-and-white HiBreak Pro (`B651 EEA_M2`).

1. On stock, install every pending **System update** first, so the package you download is the exact version your
   phone runs. Check: `adb shell getprop ro.build.versionName` (e.g. `3.1.6`) and
   `adb shell getprop ro.build.versionCode` (your line, e.g. `B651 EEA_M2`).
2. With USB debugging on (the tool reads your serial number, line and version over adb; send only your own
   phone's serial):
   ```sh
   git clone https://github.com/marek-vybiral/bigme-ota-client && cd bigme-ota-client
   python3 -m pip install cryptography payload-dumper
   python3 bigme_ota.py --full --download ~/hibreak-stock
   ```
   It prints the package (`…-OTA-FULL-<md5>.zip`) and checks its MD5. If the offered `osVersion` is newer than your
   phone's version, install that update on the phone first and repeat.
3. Extract the partition images from the package's `payload.bin`:
   ```sh
   cd ~/hibreak-stock
   unzip -o B651_*-OTA-FULL-*.zip payload.bin
   payload_dumper --out stock payload.bin      # writes stock/system.img, stock/vbmeta.img, …
   ls stock/                                   # system system_ext product vendor boot dtbo vbmeta vbmeta_system vbmeta_vendor …
   ```
   (`payload_dumper --help` shows the options of your version; any payload.bin extractor that checks hashes, such as
   `payload-dumper-go`, works too.)
4. Keep `stock/` and the zip. `stock/vbmeta.img` is what `scripts/install.sh --vbmeta` needs.

The package also holds bootloader-stage firmware (`lk`, `preloader`, `tee`, `md1img`, …). This ROM never changes
those, so going back doesn't need them; leave them alone.

## Roll back

From Android 17 with USB debugging on, or from fastboot:

```sh
scripts/rollback-to-stock.sh --stock ~/hibreak-stock/stock             # dry run
scripts/rollback-to-stock.sh --stock ~/hibreak-stock/stock --execute
```

The script:

1. in **fastbootd**: `fastboot flash system|system_ext|product|vendor <image>` for each image present in the folder;
2. in the **bootloader**: `fastboot flash dtbo_a`, `boot_a`, then `vbmeta_system_a`, `vbmeta_vendor_a` and
   `vbmeta_a` last (stock images, verification back on);
3. `fastboot erase metadata`, `fastboot format:f2fs userdata` (stock can't read Android 17's data), `fastboot reboot`.

Stock boots to its setup screen after a few minutes. The bootloader stays **unlocked**: the orange-state warning
remains. Re-locking (`fastboot flashing lock`) is only safe when every partition is exactly stock; it wipes the phone
again, and if anything isn't stock the phone may refuse to boot. This guide doesn't cover it.

## If it doesn't boot

- **Boot loop / back in fastboot after flashing**: the phone is still reachable with `fastboot`. Run the rollback
  again with the full image set, or re-run `scripts/install.sh`.
- **`fastboot flash system` fails with "not enough space"**: `fastboot delete-logical-partition system_b` (and the
  other `*_b` names `fastboot getvar all` shows) in fastbootd, then flash again.
- **`Writing 'dtbo_a' FAILED (remote: 'No such file or directory')`**: you are in fastbootd; physical partitions
  (`boot`, `dtbo`, `vbmeta*`) need `fastboot reboot bootloader` first. Dynamic ones (`system`, `system_ext`,
  `product`, `vendor`) need fastbootd (`fastboot reboot fastboot`).
- **Never** flash `nvram`, `nvdata`, `nvcfg`, `protect1`, `protect2`, `proinfo`, `otp`, `sec1`, `seccfg`, and never
  use "Format all + download" in SP Flash Tool: the IMEI, MAC addresses and radio calibration live there.
