# Plan

## What we're building

A lightweight Android TV app that runs in the background and, when a Eufy doorbell rings or a Eufy camera detects motion, shows the live video on top of whatever is on screen (apps, the home screen and the screensaver), then hides it again.

- **One APK, nothing else to install.** Anyone can download it from the GitHub Releases page and sideload it. No Termux, no Raspberry Pi, no Home Assistant.
- **Released automatically.** Every merge to `main` builds a signed APK and publishes it as a GitHub Release.
- **Sign in inside the app.** Each user connects their own Eufy account (ideally a secondary account the cameras are shared with) on the TV. Credentials stay on the device and never appear in this repo.
- **Video stays on the home network** by default (local P2P to the HomeBase), with an opt-in fallback to Eufy's relay.
- **Everything is configurable** (see [Settings](#settings)).

First target: NVIDIA SHIELD TV Pro (2019, Android 11) with a Eufy Video Doorbell E340 on a HomeBase S380. Nothing in the design is specific to that hardware, so other Android TV boxes and Eufy cameras should work too.

## Library check (15 Nov 2026)

There is no official Eufy API. The community library is [eufy-security-client](https://github.com/bropat/eufy-security-client) (Node.js, MIT).

| Version | Released | Notes |
|---|---|---|
| eufy-security-client 4.1.1 (latest) | 1 Jul 2026 | requires Node ≥ 20 |
| eufy-security-ws 3.1.0 (latest) | 12 Sep 2026 | wraps client 4.1.0, requires Node ≥ 24 |

Hardware support in client 4.1.0:

- **Video Doorbell E340 (T8214)** is a known device type (`BATTERY_DOORBELL_PLUS_E340`, "Video Doorbell Dual (E340)"), treated as a battery doorbell. Support was added in 3.0.0, and its livestream was fixed in 3.7.1 (ECDH livestream, Feb 2026) and 3.8.0 (T8214 livestream fix, Mar 2026).
- **HomeBase S380 / HomeBase 3 (T8030)** is a known station type. The project lists it as *partially* supported, push notifications through it were fixed in 3.0.0, and a 3.7.2 change was reverted because it broke HomeBase 3 communication.
- **Ring, motion and person events** come through Eufy's push notifications (`device rings`, `device motion detected`, `device person detected`).
- **Livestream** comes over P2P from the station as raw H.264 or H.265 plus AAC audio (`startStationLivestream`), which Android can decode natively.

**Update, 15 Nov 2026: upstream development has ended.** The maintainers posted ["Development of this project has ended"](https://github.com/bropat/eufy-security-client/issues/965): the library depends on Eufy's legacy API, which Eufy is shutting down, and a new integration built around "Eufy Mega" is in development (followed on their Discord, not yet published). npm marks both packages "no longer supported" since 12 Sep 2026. Version 4.1.x already signs in through the new Mega (v6) login first and the legacy one second, and it worked in our proof of concept. Our approach: pin 4.1.1, keep the Eufy bridge behind a small interface inside the app so it can be swapped for the Mega-based successor when it ships, and watch upstream.

So the pieces we need exist, but the E340-through-HomeBase-3 livestream is recent and the HomeBase 3 support is marked partial. That is exactly what the proof of concept checks before we build on it.

## Step 1: proof of concept (now)

Run the library on the SHIELD in Termux and measure: login, ring and motion events and their delay, time to first video frame, codec and resolution, CPU and memory, and behaviour after the SHIELD sleeps. Instructions: [poc/README.md](../poc/README.md).

Go/no-go:

- Ring events arrive within about 2 seconds.
- A livestream starts within about 5 seconds and plays.
- Idle CPU is low (a few % of one core) and memory is well under 150 MB.
- Events still arrive, even if a little late, after the TV has been off for a while.

## Step 2: the app

### Architecture

```
┌──────────────────────────── APK ─────────────────────────────┐
│                                                              │
│  BridgeService (foreground service, starts at boot)          │
│    └─ embedded Node.js running eufy-security-client          │
│         ⇅ local socket (JSON events, binary video frames)    │
│  EventRouter: filters by device, event type, quiet hours,    │
│               cooldown                                       │
│    └─ PopupController                                        │
│         ├─ overlay window (TYPE_APPLICATION_OVERLAY)         │
│         │    shows over apps, home screen and screensaver    │
│         └─ fallback: full-screen activity                    │
│  Player: MediaCodec decoder for H.264/H.265 (+ AAC audio)    │
│  Settings UI (Leanback / Compose for TV)                     │
│    account sign-in, devices, triggers, look, behaviour       │
└──────────────────────────────────────────────────────────────┘
```

### The Eufy bridge inside the APK

The app needs the protocol logic from eufy-security-client (cloud login with encryption, the push-notification client, and the P2P protocol). Two ways to get it into a normal APK:

1. **Embed a Node.js runtime (recommended).** Bundle the library and a small bridge script into one JS file (esbuild) and run it with a Node binary packaged in the APK. Keeps us on the maintained upstream library, so protocol fixes arrive by bumping a version.
   - `nodejs-mobile` is the usual way to do this, but its latest release is Node 18, and the library now needs Node 20+. So we will most likely build Node 22/24 for Android with the NDK in CI (once, cached), ship it as `libnode.so` in `jniLibs` and run it as a child process from the service. Adds roughly 25–35 MB to the APK.
2. **Port to Kotlin.** Smaller and lighter, but it means re-implementing login, the push client and the P2P protocol, and following every upstream protocol change by hand. Kept as a fallback if the embedded runtime is too heavy on the SHIELD.

The PoC's CPU and memory numbers decide between these.

### Background service and standby

- Foreground service with a low-priority notification, started on `BOOT_COMPLETED` and on app update.
- Partial wake lock while connected, and a request to be excluded from battery optimisation (on the SHIELD this can also be done with `adb shell dumpsys deviceidle whitelist +<package>`; the setup screen will show how).
- Reconnect immediately on network change and on screen-on, and keep the P2P link to the HomeBase warm (configurable) so the video starts faster.
- The bridge only opens a livestream when an event arrives, so idle load is just the push connection.

### Showing over everything

- `SYSTEM_ALERT_WINDOW` overlay. Android TV has no settings screen to grant it, so the setup screen shows a one-line adb command (`adb shell appops set <package> SYSTEM_ALERT_WINDOW allow`), and checks whether it has been granted.
- On ring/motion: wake the screen if it is asleep (optional setting), dismiss the screensaver, then show the overlay.
- If the overlay permission is missing, fall back to a full-screen activity launched from the foreground service.
- Hide after the configured time, on Back, or when the event ends. Optional: keep the video up while motion continues.

### Settings

All configurable on the TV:

| Area | Settings |
|---|---|
| Account | sign in / out, country, verification code and captcha prompts, local-only vs allow relay |
| Devices | which cameras and doorbells to watch; per-device on/off (garage camera later) |
| Triggers | ring, motion, person, vehicle/pet (where the camera reports them); per device |
| Pop-up | picture-in-picture or full screen; PiP size and corner; margin; opacity; show device name and time |
| Duration | seconds on screen (default 30); extend while motion continues; Back to close |
| Audio | off / on / on for rings only; volume; duck what's playing |
| Behaviour | show over everything vs only when idle; wake the TV if off; cooldown between pop-ups; quiet hours |
| Notification sound | optional chime on ring |
| Test | "Show test pop-up" button and a live-view button per device |
| Diagnostics | connection status, last events, log export |

Settings are stored with DataStore; the password is stored encrypted with the Android Keystore.

### Build and release

- GitHub Actions: build on every PR; on merge to `main` build a release APK, sign it, and publish it as a GitHub Release with a version from the commit count (`v0.N.0`).
- Signing key in repository secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). A stable key matters: Android only updates an app in place if it is signed by the same key.
- Install: download the APK from Releases and sideload it (adb, or Downloader on the TV); later updates can be checked from inside the app.

### Milestones

1. **PoC** on the SHIELD in Termux (this step).
2. **App skeleton + CI release:** Kotlin project, foreground service, boot start, settings screen, signed APK published on merge to `main`.
3. **Embedded bridge:** Node runtime in the APK, sign-in flow (with 2FA/captcha), device list, events in the app log.
4. **Pop-up:** overlay window, MediaCodec player, auto-hide, test button.
5. **All settings**, quiet hours, cooldown, audio.
6. **Standby hardening:** wake locks, reconnects, measure first-ring delay after sleep.
7. **More devices:** garage camera, multiple cameras.

## Risks

- **Deep standby.** With the TV off, the SHIELD may pause background work, so the first event after waking could be late or lost. Mitigations above; the PoC measures it.
- **Load from a Node runtime** running all the time. Measured by the PoC; Kotlin port is the fallback.
- **Eufy shuts down its legacy API** and upstream has stopped maintaining the library. Login already uses the new API; push events or P2P may break when the legacy API goes. Mitigation: bridge behind an interface, pinned version, switch to the Mega-based successor once it is published.
- **Livestream start time.** If P2P takes too long, keep the station connection warm and show a snapshot (the event image) until video arrives.
- **Account side effects.** A secondary account avoids logging out the phone app; the sign-in screen will recommend it.
