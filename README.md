# Eufy TV Doorbell

Pop up your Eufy doorbell's live video on Android TV when someone rings or motion is detected.

A lightweight background app for Android TV. When your Eufy doorbell rings, or a Eufy camera sees motion, the live view appears on top of whatever is on screen (including the screensaver), then hides itself after a while. No extra hardware: everything runs on the TV box.

> **Status: early.** We're validating the approach on an NVIDIA SHIELD TV with a Eufy Video Doorbell E340 and HomeBase S380 before building the app. See the [plan](docs/PLAN.md) and the [proof of concept](poc/README.md).

## Planned features

- Pop-up on ring, motion or person detection, per camera.
- Picture-in-picture (size and corner of your choice) or full screen.
- Shows over apps, the home screen and the screensaver; can wake the TV.
- Configurable duration, audio, quiet hours and cooldown.
- Video stays on your home network (local P2P to the HomeBase).
- One APK, published automatically on GitHub Releases. Sign in with your Eufy account in the app.

## Install

1. Download the latest `eufy-tv-doorbell-*.apk` from [Releases](https://github.com/adam-stott-dev/eufy-tv-doorbell/releases).
2. Install it on the TV: `adb install -r eufy-tv-doorbell-*.apk` (or with an app like Downloader).
3. Let it show over other apps. Android TV has no screen for this, so run once from a computer:
   ```
   adb shell appops set io.github.adamstottdev.eufytv SYSTEM_ALERT_WINDOW allow
   ```
   Without it, the pop-up opens full screen instead.
4. Optional, for faster pop-ups after the TV has been asleep:
   ```
   adb shell dumpsys deviceidle whitelist +io.github.adamstottdev.eufytv
   ```
5. Open **Eufy TV Doorbell** from the apps row, sign in, and choose your settings.

## Testing from a computer

Show the test pop-up without touching the TV (optionally after a delay, so you can start the screensaver or a video first):

```
adb shell am broadcast -a io.github.adamstottdev.eufytv.TEST_POPUP -n io.github.adamstottdev.eufytv/.service.TestTriggerReceiver --el delay_ms 0
```

To test with real video, copy a raw `.h264` clip to `/sdcard/Android/data/io.github.adamstottdev.eufytv/files/test.h264`.

## How it works

Eufy has no official API. The app embeds the open-source [eufy-security-client](https://github.com/bropat/eufy-security-client) library to sign in, receive ring and motion events, and open the livestream from your HomeBase, then decodes the video natively on the TV.

Use a **secondary Eufy account** with your cameras shared to it. Signing in with your main account elsewhere can log your phone app out.

## Not affiliated

This project is not affiliated with or endorsed by Eufy or Anker. Eufy is a trademark of Anker Innovations.
