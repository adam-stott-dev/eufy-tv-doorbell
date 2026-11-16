# Third-party software

The app's APK includes the following software. Each keeps its own licence.

| Component | Used for | Licence |
|---|---|---|
| [eufy-security-client](https://github.com/bropat/eufy-security-client) and its npm dependencies | Talking to Eufy and the HomeBase | MIT (dependencies: see their licence notes, kept next to the bundled bridge as `main.js.LEGAL.txt`) |
| [Node.js](https://github.com/nodejs/node) runtime, via [node-runtime / node-bridge](https://central.sonatype.com/artifact/io.github.fellow99/node-runtime) | Running the Eufy bridge on the TV | Node.js licence (MIT and others, see Node's LICENSE) |
| [ZXing](https://github.com/zxing/zxing) core | Drawing the sign-in QR code | Apache-2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) (AppCompat, Preference, Core) | App screens and settings | Apache-2.0 |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | App code | Apache-2.0 |

Eufy is a trademark of Anker Innovations. This project is not affiliated with Eufy or Anker.
