# Eufy TV Doorbell

Pop up your Eufy doorbell's live video on Android TV when someone rings or motion is detected.

A lightweight background app for Android TV. When your Eufy doorbell rings, or a Eufy camera sees motion, the live view appears on top of whatever is on screen (including the screensaver), then hides itself after a while. No extra hardware: everything runs on the TV box.

> **Status: early.** Tested on an NVIDIA SHIELD TV with a Eufy Video Doorbell E340 and HomeBase S380. See the [plan](docs/PLAN.md) and the [proof of concept results](docs/POC-RESULTS.md).
>
> Needs a 64-bit ARM TV box (arm64-v8a), such as the SHIELD TV, Chromecast with Google TV or most current boxes.

## Features

- Pop-up on ring, motion or person detection, per camera.
- Picture-in-picture (size and corner of your choice) or full screen, set separately for when the TV is on and when the pop-up wakes it (full screen by default).
- Shows over apps, the home screen and the screensaver; can wake the TV, and put it back to sleep afterwards if the pop-up woke it.
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
   Don't skip this: without it, Android usually stops the pop-up appearing over other apps.
4. Optional, for faster pop-ups after the TV has been asleep:
   ```
   adb shell dumpsys deviceidle whitelist +io.github.adamstottdev.eufytv
   ```
5. Optional, so the TV goes back to sleep after a pop-up that woke it (select **Allow putting the TV to sleep** in the app's settings and turn it on, or run):
   ```
   adb shell settings put secure enabled_accessibility_services io.github.adamstottdev.eufytv/.service.SleepService
   adb shell settings put secure accessibility_enabled 1
   ```
   This turns on a small accessibility service that only puts the TV to sleep; it reads nothing on screen. If you already use other accessibility services, add it to the list (separated by `:`) instead of replacing it, or turn it on in **Settings > Accessibility**.
6. Open **Eufy TV Doorbell** from the apps row, choose **Eufy account** and sign in: scan the QR code with your phone and enter your details there (it's one-time, home network only, and encrypted on the phone), or type them on the TV. Then choose your settings.

If anything stops the pop-ups working later (Eufy signs you out, a verification code is needed, the connection drops for more than a few minutes), a red message appears on screen and repeats until it's fixed. You can change how often in **Advanced**.

## Testing from a computer

Show the test pop-up (your chosen camera, live) without touching the TV, optionally after a delay so you can start the screensaver or a video first:

```
adb shell am broadcast -a io.github.adamstottdev.eufytv.TEST_POPUP -n io.github.adamstottdev.eufytv/.service.TestTriggerReceiver --el delay_ms 0
```

For another camera, add `--es live <camera serial>`:

```
adb shell am broadcast -a io.github.adamstottdev.eufytv.TEST_POPUP -n io.github.adamstottdev.eufytv/.service.TestTriggerReceiver --es live <camera serial>
```

## How it works

Eufy has no official API. The app embeds the open-source [eufy-security-client](https://github.com/bropat/eufy-security-client) library, running on a Node.js runtime built into the APK ([aosp-node-runtime](https://github.com/fellow99/aosp-node-runtime)), to sign in, receive ring and motion events, and open the livestream from your HomeBase. The video and sound are decoded natively on the TV. The connection code is in [`bridge/`](bridge/).

Your Eufy password stays on the TV, encrypted with a key held by the Android Keystore, and is only sent to Eufy.

Use a **secondary Eufy account** with your cameras shared to it. Signing in with your main account elsewhere can log your phone app out.

## Not affiliated

This project is not affiliated with or endorsed by Eufy or Anker. Eufy is a trademark of Anker Innovations.
