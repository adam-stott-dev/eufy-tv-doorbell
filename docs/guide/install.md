---
title: Install
summary: Put the app on your TV, let it show over other apps, and keep it fast after standby.
order: 1
---

# Install

## What you need

- An Android TV or Google TV box running Android 8.0 or later. The app is built for 64-bit ARM (arm64-v8a), which covers the NVIDIA SHIELD TV, Chromecast with Google TV and most current boxes.
- A Eufy doorbell or camera that works in the Eufy Security app. It has been tested with a Video Doorbell E340 on a HomeBase S380.
- A computer with [adb](https://developer.android.com/tools/releases/platform-tools) for two one-off commands. You can install the app without a computer, but letting it show on top of other apps needs adb once.

## 1. Turn on developer options on the TV

1. On the TV, open **Settings > Device Preferences > About** and select **Build** seven times.
2. Go back to **Device Preferences > Developer options** and turn on **USB debugging** (on a SHIELD, also **Network debugging**).
3. Find the TV's IP address in **Settings > Network & Internet**.

## 2. Install the APK

### From a computer (adb)

Download the latest `eufy-tv-doorbell-*.apk` from [the latest release](https://github.com/adam-stott-dev/eufy-tv-doorbell/releases/latest), then:

```
adb connect <TV IP address>
adb install -r eufy-tv-doorbell-*.apk
```

The first time, the TV asks you to allow the computer. Tick **Always allow** and select **OK**.

### On the TV (Downloader)

1. Install **Downloader** from the Play Store on the TV and allow it to install unknown apps when asked.
2. In Downloader, enter `adam-stott-dev.github.io/eufy-tv-doorbell/download`. It always fetches the latest version.
3. Install the APK when Downloader offers.

## 3. Let it show over other apps

Android TV has no settings screen for this permission, so run this once from a computer:

```
adb shell appops set io.github.adamstottdev.eufytv SYSTEM_ALERT_WINDOW allow
```

Don't skip this. Without it, Android usually stops the pop-up from appearing over other apps, and the app reminds you on screen until it's done.

## 4. Keep it fast after standby (optional)

When the TV has been asleep, Android can delay background apps. This lets doorbell events through straight away:

```
adb shell dumpsys deviceidle whitelist +io.github.adamstottdev.eufytv
```

## 5. Let it put the TV back to sleep (optional)

If a pop-up wakes the TV, the app can put it back to sleep afterwards. Android only allows this through an accessibility service, which you turn on once. It only puts the TV to sleep and reads nothing on screen.

On the TV: **Settings > Device Preferences > Accessibility > Eufy TV Doorbell sleep**, turn it on. Or from a computer, if you use no other accessibility services:

```
adb shell settings put secure enabled_accessibility_services io.github.adamstottdev.eufytv/.service.SleepService
adb shell settings put secure accessibility_enabled 1
```

## 6. Open the app and sign in

Open **Eufy TV Doorbell** from the apps row, select **Eufy account** and sign in. See [Sign in](sign-in.md). Then select **Check and test > Show a test pop-up** to check it works.

The app starts by itself when the TV turns on, so you only need to open it again to change settings.

## Updating

New versions appear on this website and on [GitHub Releases](https://github.com/adam-stott-dev/eufy-tv-doorbell/releases). Install over the top with `adb install -r` or Downloader. Your sign-in and settings are kept.

## Uninstalling

```
adb uninstall io.github.adamstottdev.eufytv
```

Or on the TV: **Settings > Apps > Eufy TV Doorbell > Uninstall**.
