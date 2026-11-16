---
title: Troubleshooting
summary: On-screen messages, testing from a computer, and common fixes.
order: 4
---

# Troubleshooting

## Messages on screen

When something stops the pop-ups working, a red message appears on screen and repeats until it's fixed (every 30 minutes by default, set in **Advanced**).

| Message | What to do |
|---|---|
| Eufy signed you out | Open the app and select **Eufy account** to sign in again. |
| Eufy needs you to sign in again | Open the app and finish signing in, usually with a code Eufy emailed you. |
| Can't connect to Eufy | Check the TV's internet connection. The app keeps trying by itself. |
| Isn't receiving doorbell events | Usually clears by itself. If it doesn't, sign out and in again. |
| Isn't allowed to show over other apps | Run the `appops` command in [Install](install.md#3-let-it-show-over-other-apps). |

## The pop-up is late or missing after the TV has been asleep

- Run the battery command in [Install](install.md#4-keep-it-fast-after-standby-optional).
- Check **Advanced > Stay connected while the TV sleeps** is on.
- On a SHIELD, **Settings > Device Preferences > Power > Sleep** settings can also matter.

## No motion pop-ups

- Check **Motion detected** and **Person detected** are on in **When to pop up**, and that the camera is chosen in **Camera that pops up**.
- In the Eufy Security app, check motion detection and notifications are on for the camera. The TV only hears about events Eufy sends.
- **Wait between motion pop-ups** may be holding back a repeat.

## The video doesn't start

- The TV and the HomeBase must be on the same network for local video. If they aren't, turn off **Keep video on my home network**.
- Select **Check and test > Show a test pop-up** to see your camera live without waiting for an event.

## Testing from a computer

Show the test pop-up (your camera, live) without touching the TV, optionally after a delay so you can start the screensaver or a video first:

```
adb shell am broadcast -a io.github.adamstottdev.eufytv.TEST_POPUP -n io.github.adamstottdev.eufytv/.service.TestTriggerReceiver --el delay_ms 0
```

The app keeps a short log of doorbell events, connection changes and how long each pop-up took:

```
adb shell cat /sdcard/Android/data/io.github.adamstottdev.eufytv/files/events.log
```

Save the TV's recent log to a file, to look through or attach to an issue:

```
adb logcat -d > eufy-tv-log.txt
```

## Still stuck?

[Open an issue on GitHub](https://github.com/adam-stott-dev/eufy-tv-doorbell/issues) with your TV box, camera model, app version and what you see. Never post your Eufy password or verification codes, and remove your email address from any log you attach.
