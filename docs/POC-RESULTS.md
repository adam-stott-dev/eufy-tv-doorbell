# Proof of concept results

Run on 15 Nov 2026 on an NVIDIA SHIELD TV Pro (2019, Android 11) in Termux 0.118.3, Node.js 24.22.3, eufy-security-client 4.1.0, local-network-only P2P.

| Check | Result |
|---|---|
| Cloud login | OK, about 9 s |
| P2P connection to HomeBase S380 (T8030) | OK, local network, under 0.5 s after login |
| Devices found | Video Doorbell E340 (T8214, type 94), Garage camera (T8441, type 45) |
| Push notifications | Connected about 15 s after login |
| Ring event | Received (`device rings`) |
| Motion event | Not seen yet |
| Livestream start | First video data 0.77 s after the request |
| Video | H.264 High profile, level 5.0, 1600×2200 (portrait), 15 fps, Annex-B; SPS/PPS/IDR repeat about every 2 s |
| Audio | AAC-LC, 16 kHz, ADTS |
| Load (idle) | 4.6% of one core, 91 MB RSS |
| Load (streaming) | 0.8% of one core, 95 MB RSS |

## What this means for the app

- The core of the plan works on this hardware: login, ring events and the local livestream through the HomeBase S380.
- The stream starts in under a second, so the pop-up can show live video almost immediately; keeping the P2P link warm is worth it.
- The video is raw Annex-B H.264 that MediaCodec / Media3 can decode directly; no ffmpeg needed. The pop-up layout must handle a portrait 1600×2200 picture.
- Memory (~95 MB) and CPU (a few % of one core) are acceptable for an always-on service, so embedding a Node.js runtime is viable.

## Still to check

- Motion / person events.
- Delay between pressing the doorbell and the event arriving.
- Behaviour after the SHIELD has been asleep with the TV off.
- Upstream has ended development (legacy Eufy API being shut down; a Mega-based successor is in development). See the update in [PLAN.md](PLAN.md).
