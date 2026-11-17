# No Start-Stop

Android app for Topway head units (TS18 and similar) that turns the PSA engine stop-start feature off through the Raise CAN box, the same way the built-in "Original Car" settings app does.

Built for a Peugeot/Citroën Jumpy (2015–23) with CAN box setting `02: Raise` / `96: PSA Peugeot Citroen`. It has not been confirmed on a real head unit yet: see [Status](#status).

![Stop-start on, service controllable](docs/state-on.png)

![Stop-start off, service permanently closed, apply at boot on](docs/state-off.png)

The screenshots are from an emulator at 1920x720 with a simulated CAN box. On a device without the Topway framework the app shows the state as unknown instead of guessing:

![CAN box unreachable](docs/unreachable.png)

## What it does

- **Engine stop-start**: shows whether stop-start is on or off and toggles it. The car resets this at every ignition cycle.
- **Stop-start service**: shows whether the service is controllable or permanently closed and toggles it.
- **Apply at boot**: off by default. When on, the app turns stop-start off and closes the service once after the head unit boots, then stops.
- **Log**: the commands sent and any errors.

## Install from a USB pendrive

1. Build the APK (see [Build](#build)) or take `build/nostartstop.apk` from someone who has.
2. Copy `nostartstop.apk` to a FAT32 or exFAT pendrive.
3. Plug the pendrive into the head unit's USB port.
4. Open the head unit's **APK Installer** app (or its file manager), pick the USB drive and tap `nostartstop.apk`.
5. If Android asks, allow installing from unknown sources for that app, then tap **Install**.
6. Open **No Start-Stop** once from the app list. Android does not deliver the boot event to an app that has never been opened.
7. Turn **Apply at boot** on if you want it applied automatically.

To update, install the new APK over the old one the same way. An APK built on a different computer is signed with a different key, so uninstall the old version first.

## Install with adb

```sh
adb install -r build/nostartstop.apk
adb shell am start -n com.furgo.nostartstop/.MainActivity
adb logcat -s NoStartStop
```

## Build

Needs a JDK (17 works) and the Android SDK with `build-tools/34.0.0` and `platforms/android-34`. No gradle.

```sh
sh build.sh
```

The result is `build/nostartstop.apk`, signed with the local debug key in `~/.android/debug.keystore` (created on first build). That is fine for sideloading onto your own head unit; it is not a release signing setup.

## How it works

The head unit's MCU relays CAN box frames through the framework class `android.tw.john.TWUtil`. `stubs/` holds a compile-only stub of it; the real class comes from the device.

| Action | Call |
|---|---|
| Query settings status | `write(1281, 143, 2, {56, 0})` |
| Set stop-start | `write(1281, 128, 2, {13, value})`, bit 0 = stop-start enabled, bit 7 = service permanently closed |
| Status frame | message `what = 1281`, `arg1 = 56`; `byte[5]` bit 6 = stop-start enabled, `byte[8]` bit 6 = permanently closed |

These were read from the decompiled vendor app `com.tw.car` (`PsaRaiseSettingsActivity`).

## Status

- Builds, and the UI runs on an emulator against a simulated CAN box.
- Not yet run on a real head unit, so the frames above are unconfirmed on a vehicle.

## Troubleshooting

- **Log shows `TWUtil unavailable`**: Android is blocking the vendor class for a non-system app. With adb: `adb shell settings put global hidden_api_policy 1`.
- **State stays UNKNOWN but the CAN box is live**: the status frame layout differs on your car. The buttons still send; the log shows what was sent.
- **Apply at boot does nothing**: open the app once after installing. Many head units also sleep on ignition-off instead of rebooting, and the app only acts on a real boot.
