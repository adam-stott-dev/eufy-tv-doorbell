# Proof of concept on the SHIELD (Termux)

Before building the app, this checks on the real hardware that we can:

1. log in to Eufy with a secondary account,
2. receive ring and motion events, and how quickly they arrive,
3. open a livestream from the doorbell through the HomeBase, how long it takes to start, and what codec and resolution it uses,
4. keep all of this running without loading the SHIELD too much.

The script uses [eufy-security-client](https://github.com/bropat/eufy-security-client) directly, which is the same library the app will embed, so the result carries over. Termux is only a test harness here. The finished app will be a single APK with nothing else to install.

## 0. Before you start

- **Secondary Eufy account.** Create a new Eufy account (a different email), then in the Eufy app on your phone share the doorbell (and the garage camera, if you like) with it, giving it full/admin access if offered. Accept the invitation once by logging in to the Eufy app with the new account, then log back in to your main account. Using your main account here could log your phone out.
- **adb over the network.** On the SHIELD: Settings → Device Preferences → About → click *Build* 7 times, then Settings → Device Preferences → Developer options → turn on *Network debugging*. Note the SHIELD's IP address (Settings → Network & Internet).
- **On Windows:** [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (adb) on your PATH, and the built-in OpenSSH client (`ssh` works in a Command Prompt on Windows 10/11).
- **Termux APK:** download the latest `termux-app_*_universal.apk` from [github.com/termux/termux-app/releases](https://github.com/termux/termux-app/releases) and save it as `poc\termux.apk`. Don't use the Play Store build, it is out of date.

## 1. Set up the SHIELD (one command)

Open a Command Prompt in the repo's `poc` folder and run, with your SHIELD's IP:

```bat
windows\setup-shield.bat 192.168.1.50
```

Accept the "Allow network debugging?" prompt on the TV the first time. The script installs Termux, copies the files over, creates an SSH key for your laptop if you don't have one, opens Termux on the TV and types the setup command into it. Watch the TV until it prints **Done** (a few minutes).

<details>
<summary>The same steps by hand</summary>

```bat
adb connect 192.168.1.50:5555
adb install -r termux.apk
adb shell pm grant com.termux android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.termux android.permission.WRITE_EXTERNAL_STORAGE
adb shell mkdir -p /sdcard/Download/eufy-poc/out
adb push eufy-poc.js package.json bootstrap.sh /sdcard/Download/eufy-poc/
ssh-keygen -t ed25519
adb push %USERPROFILE%\.ssh\id_ed25519.pub /sdcard/Download/eufy-poc/id_ed25519.pub
adb shell am start -n com.termux/.app.TermuxActivity
REM wait ~30 s for Termux's first-run setup, then:
adb shell input text "bash%s/sdcard/Download/eufy-poc/bootstrap.sh"
adb shell input keyevent 66
```
</details>

## 2. Run it from your laptop

```bat
ssh -p 8022 192.168.1.50
cd eufy-poc
node eufy-poc.js
```

The first run asks for the secondary account's email, password and country (GB). They are saved only in Termux's private storage on the SHIELD (`~/.eufy-poc/config.json`). If Eufy emails a verification code or asks for a captcha, the script prompts for it; a captcha image is saved where you can fetch it with `adb pull /sdcard/Download/eufy-poc/out/captcha.png`.

## 3. Tests to run

| # | Do this | Look for in the output |
|---|---|---|
| 1 | Start the script | `CLOUD LOGIN OK`, `PUSH CONNECTED`, and the doorbell and HomeBase in the device list |
| 2 | Type `live` | `LIVESTREAM STARTED after Ns`, `FIRST VIDEO DATA after Ns`, codec and resolution |
| 3 | Press the doorbell | `EVENT RING` (compare the time with when you pressed it) |
| 4 | Walk in front of it | `EVENT MOTION` / `EVENT PERSON` |
| 5 | Quit (`quit`), restart with `node eufy-poc.js --auto` and ring | a livestream starts by itself after the ring |
| 6 | Leave it running 30+ minutes | `LOAD` lines every minute: CPU % and memory |
| 7 | Turn the TV off (SHIELD goes to sleep), wait 10 minutes, ring | whether `EVENT RING` still arrives and how late |

Recordings, logs and an `.mp4` version of each stream are written to `/sdcard/Download/eufy-poc/out`. Copy them to your laptop with:

```bat
adb pull /sdcard/Download/eufy-poc/out
```

Then send back the `poc-*.log` files (they contain no password) and say whether the `.mp4` plays.

## Options

```
node eufy-poc.js --seconds 30     record 30 s per stream (default 15)
node eufy-poc.js --auto           start a stream on ring/motion/person
node eufy-poc.js --allow-remote   allow Eufy's relay if a local P2P link fails
node eufy-poc.js --debug          verbose library logs
```

By default the stream is forced onto the local network. If `live` fails, try once with `--allow-remote` and note whether that works; it tells us whether the problem is local discovery.

## Cleaning up

`adb uninstall com.termux` removes Termux, the saved login and everything installed in it. Remove the share from the secondary account in the Eufy app if you stop testing.
