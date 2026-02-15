# BTC Android Miner

Android app for CPU-based Bitcoin mining (Stratum pool). Open-source; install via Android Studio or sideload (no Play Store).

This project uses **cgminer** (Option B) as the mining engine: the app runs the cgminer binary as a subprocess and parses its output for hashrate and shares. You must **build the cgminer binary** for Android and place it in the app assets before mining will work.

## Cgminer binary (required for mining)

Place the cgminer executable at:

- `app/src/main/assets/cgminer/arm64-v8a/cgminer`
- `app/src/main/assets/cgminer/armeabi-v7a/cgminer` (optional, for 32-bit ARM)

**How to build cgminer on PC with the Android NDK:** see **[docs/cgminer-build.md](docs/cgminer-build.md)** for step-by-step instructions (install NDK, create toolchain, clone cgminer, configure, make, copy into assets). Without the binary, the app will show "cgminer binary not found" when you start mining.

## Build and run

1. **Open in Android Studio**: File → Open → select the `BTC Android Miner` folder.
2. **Sync**: Let Android Studio sync Gradle (it will use the included wrapper and JDK).
3. **Run on device**: Connect your phone (USB debugging enabled) and click Run, or build an APK:
   - **Debug APK**: Build → Build Bundle(s) / APK(s) → Build APK(s). Install the APK from `app/build/outputs/apk/debug/`.
   - **From command line** (with Java and Android SDK installed): `.\gradlew.bat assembleDebug` (Windows) or `./gradlew assembleDebug` (macOS/Linux).

## Requirements

- Android Studio (recommended) or JDK 17+ and Android SDK for command-line build
- minSdk 24, targetSdk 34
- Device or emulator for testing

## License

App code: to be set. Mining core will use cgminer (GPL-3.0).
