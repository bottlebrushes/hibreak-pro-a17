# Known issues

## Test status of this release

- **GApps variant**: the same build configuration has been the developer's daily phone since 2026-11-14 (installed
  on slot a with a Magisk-patched stock boot image). It passed the on-phone checks: e-ink composer and refresh
  modes, the controller, the shade (no root), sleep picture, PIN lock, Wi-Fi, Bluetooth, camera, sensors, vibration,
  fingerprint, sleep/wake, Google services, 0 crashes. The release image itself was built from the published source
  and checked offline (filesystem, SELinux policy, init scripts, VINTF, linker, APEX payloads); it was not flashed
  again before publishing. It differs from the developer's image only in: adb needs authorisation and USB debugging
  is off by default (the developer image is debuggable with a preset adb key), and a personal icon pack is left out.
- **Vanilla variant**: built from the same source without Google apps. It has **not been booted on a phone**. Expect
  it to work like the GApps variant, but treat it as untested.
- **Stock (un-rooted) boot image**: every test boot used Bigme's stock boot image patched by Magisk. Magisk doesn't
  change how Android starts, so an unpatched stock boot is expected to behave the same, but that exact combination
  hasn't been run.

## Display and lock screen

- **One stock lock-screen frame when locking.** Locking can briefly (~50 ms) draw Android's own dozing lock screen
  before the E-ink Shade lock page covers it; on e-ink this can leave a faint ghost until the next refresh. Removing
  it needs a SystemUI change that isn't in this release.
- **Before the first unlock after a reboot**, the stock Android lock screen and PIN pad show. The shade, its lock
  screen and the HiBreak controller are not "encryption aware" and start after the first unlock. Refresh modes and
  button actions are also inactive until then.
- If the stock PIN pad ever appears instead of the shade's, entering the PIN there works normally.
- Landscape: the shade panel and lock screen are portrait only.

## Google and payments

- **Google Wallet tap-to-pay, some banking apps**: Play Integrity reports an unlocked bootloader and a custom ROM,
  so apps that need "device" or "strong" integrity refuse to run or to add cards. There is no hardware-backed key
  attestation for a custom system on this phone. The ROM ships no keybox or integrity spoofing.
- First sign-in on the GApps variant: Play services may need a few minutes after setup to finish its first updates.

## Fingerprint

- Works after a fix in this ROM (the MicroArray sensor's driver reads past its buffers; Android 16+ guards thread
  stacks, which broke it). Enrol after setup in Settings > Security. The sensor driver runs a calibration during
  the first ~10 minutes after each boot.
- Fingerprint **unlock** has not been tested together with the shade's own PIN pad (the lock screen of this
  release); PIN unlock is tested. If a fingerprint doesn't unlock from the shade's lock page, use the PIN.

## Other

- **adb**: USB debugging is off by default and every computer has to be authorised on the phone. The build is a
  `userdebug` build with `ro.debuggable=0`, so `adb root` is not available; Magisk on the boot image works as on stock.
- The Bigme hardware overlay (power profile, VoLTE / tethering flags from Bigme's EEA firmware) only applies when the
  vendor fingerprint is `Bigme/HiBreak_EEA…`; other firmware lines use Android's defaults for those settings.
- Default button mapping (changeable in Settings > E-ink display): top button long press = play/pause, double press
  = next track; anti-ghosting on.
- The sleep picture is a screen-saver (dream) shown while the phone sleeps; it is not drawn when the phone is powered
  off.
- No OTA updater: new versions are new images on the release page (see INSTALL.md, "Updating").
