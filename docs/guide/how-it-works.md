---
title: How it works
summary: What runs on the TV, where your video and password go, and the risks.
order: 5
---

# How it works

Eufy has no official API for other apps. Eufy TV Doorbell embeds the open-source [eufy-security-client](https://github.com/bropat/eufy-security-client) library, running on a small Node.js runtime inside the APK, and does the rest natively on Android.

1. **Background service.** Starts when the TV turns on and stays connected to Eufy, using a few percent of one CPU core and about 95 MB of memory on a SHIELD TV.
2. **Events.** Eufy sends ring, motion and person events to the app as push notifications, the same way it notifies your phone.
3. **Live video.** The app connects directly to your HomeBase on your home network and asks for the camera's live stream. On a SHIELD the first video arrives in under a second.
4. **Pop-up.** The H.264 video and AAC sound are decoded by the TV's own hardware and shown in a window on top of whatever is on screen, then hidden after the time you set.

## Privacy

- **Video stays at home.** By default the video goes straight from the HomeBase to the TV over your network. It only uses Eufy's relay if you turn off **Keep video on my home network**.
- **Your password stays on the TV**, encrypted by Android's secure key storage, and is only sent to Eufy.
- The app has no analytics, ads or servers of its own. This website only reads public release information from GitHub.

## Risks

- **Unofficial.** Eufy can change its systems at any time and break the app until it's updated.
- **Library support.** The eufy-security-client maintainers have [ended development](https://github.com/bropat/eufy-security-client/issues/965) because Eufy is retiring the old API it uses; a replacement built on Eufy's new system is in progress. The app keeps the Eufy connection as a separate part so it can switch over.
- **Standby.** Some TV boxes pause background apps in deep standby, which can delay the first pop-up after the TV wakes. See [Troubleshooting](troubleshooting.md).

## Source code

Everything is on [GitHub](https://github.com/adam-stott-dev/eufy-tv-doorbell). The design notes are in the [plan](../PLAN.md) and the [proof of concept results](../POC-RESULTS.md).

## Not affiliated

This project is not affiliated with or endorsed by Eufy or Anker. Eufy is a trademark of Anker Innovations.
