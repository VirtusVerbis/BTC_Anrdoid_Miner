# BTC Android Miner

Android app for CPU-based Bitcoin mining via Stratum pools. Uses **in-app native code** (C, built with NDK/CMake) for SHA-256 and nonce scanning—no external miner binary required. Open-source; build with Android Studio or Gradle (sideload; not on Play Store).

Created using:
Cursor AI

References:
https://github.com/BitMaker-hub/NerdMiner_v2/releases



<img src="https://github.com/VirtusVerbis/BTC_Anrdoid_Miner/blob/main/Screenshot.png" width="250" height="500">


## Features

- **Stratum v1** pool support (TCP and TLS)
- Configurable pool URL/port, username/password, wallet and worker name
- **WiFi only** and **mine only when charging** options
- **Battery temp** and **hashrate** throttling with red/white visual indicators when active
- Configurable **cores** and **CPU intensity** (1–100%)
- Dashboard: hash rate, mining timer (DD:HH:MM:SS), nonces, accepted/rejected/identified shares, battery temp, hashrate chart
- Runs as a **foreground service** so mining continues in the background until you stop it (notification permission required on Android 13+)

## Build and run

1. **Open in Android Studio**: File → Open → select the `BTC Android Miner` folder.
2. **Sync**: Let Android Studio sync Gradle (it will use the included wrapper and JDK). The first build will run CMake/NDK to compile the native miner library—no extra steps needed.
3. **Run on device**: Connect your phone (USB debugging enabled) and click Run, or build an APK:
   - **Debug APK**: Build → Build Bundle(s) / APK(s) → Build APK(s). Install the APK from `app/build/outputs/apk/debug/`.
   - **From command line** (with Java and Android SDK installed): `.\gradlew.bat assembleDebug` (Windows) or `./gradlew assembleDebug` (macOS/Linux).

## Requirements

- Android Studio (recommended) or JDK 17+ and Android SDK for command-line build
- **Android NDK** (e.g. 25.1 via Android Studio SDK Manager or as specified in `app/build.gradle.kts`)
- minSdk 24, targetSdk 34
- Device or emulator for testing

## License

App code license to be set. The native miner (C) is part of this repository.
