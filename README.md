# BTC Android Miner

Android app for **CPU and GPU** Bitcoin mining via Stratum pools. Uses **in-app native code** (C, built with NDK/CMake) for SHA-256 and nonce scanning—no external miner binary required. Open-source; build with Android Studio or Gradle (sideload; not on Play Store).

Created using:
Cursor AI

References:
https://github.com/BitMaker-hub/NerdMiner_v2/releases



<img src="https://github.com/VirtusVerbis/BTC_Anrdoid_Miner/blob/main/Screenshot.png" width="250" height="500">


## Features

- **Stratum v1** pool support (TCP and TLS)
- Configurable pool URL/port, username/password, wallet and worker name
- **CPU and GPU** mining: configurable CPU cores and intensity (1–100%), GPU workgroups (0 = off) and GPU intensity
- **WiFi only** and **mine only when charging** options
- **Battery temp** throttling: throttle above configured max, resume when 10% below; **auto-tuning** option keeps temp in a target band; **hard stop** at 43°C with notification
- **Hashrate target (H/s):** optional cap—when current hashrate (CPU+GPU) exceeds target, mining is throttled; leave empty for no cap
- **Partial Wake Lock** and **alarm wake interval (0–60 s):** keep mining active when screen is off; interval 0 = hold wake lock for the session, 1–60 = repeating alarm-driven wake lock
- **Mining thread priority (0 to -20):** slider for CPU priority when screen is off (0 = default, -20 = highest)
- **Battery optimization** in Config: "Request Don't optimize" (system dialog) and "Open battery optimization settings" so you can whitelist the app from battery optimization
- **Certificate pinning** for the mining pool (TLS); **encrypted config** (credentials stored with EncryptedSharedPreferences and Android Keystore)
- Dashboard: hash rate, mining timer (DD:HH:MM:SS), nonces, accepted/rejected/identified shares, battery temp, hashrate chart; red/white indicators when throttle is active
- Runs as a **foreground service** so mining continues in the background until you stop it (notification permission required on Android 13+)

### Security (OWASP MASVS)

The app is aligned with [OWASP Mobile Application Security Verification Standard (MASVS)](https://mas.owasp.org/MASVS/) where applicable.

- **Passed / implemented:** Secure storage of sensitive data (M2.1; EncryptedSharedPreferences + Keystore); secure communication (M3.1; TLS for Stratum); certificate pinning for pool (M3.5); no sensitive data in logs (M2.2); input validation/sanitization for config strings.
- **Not covered / out of scope:** User authentication (no login); biometrics; root/jailbreak detection; code obfuscation; anti-tampering.

### Screen-off mining

If hashrate drops when the screen is off, use **Config → Battery optimization** to request "Don't optimize" (dialog) or open battery settings and set the app to **Unrestricted** / **Don’t optimize** / **Allow background**. You can also enable **Partial Wake Lock** and optionally the **alarm wake interval** to improve screen-off behavior. As a fallback, in **Settings → Apps → BTC Miner → Battery**, set to **Unrestricted** / **Don't optimize**.

## Build and run

The first build runs CMake/NDK and requires **Vulkan SDK** (or `glslc`) to be installed so the GPU compute shader compiles; see [Requirements](#requirements) below.

1. **Clone**: `git clone <your-repo-url>` then `cd "BTC Android Miner"`.
2. **Open in Android Studio**: File → Open → select the `BTC Android Miner` folder.
3. **Sync**: Let Android Studio sync Gradle (it will use the included wrapper and JDK). The first build will run CMake/NDK to compile the native miner library.
4. **Run on device**: Connect your phone (USB debugging enabled) and click Run, or build an APK:
   - **Debug APK**: Build → Build Bundle(s) / APK(s) → Build APK(s). Install the APK from `app/build/outputs/apk/debug/app-debug.apk`.
   - **From command line** (with Java and Android SDK installed): `.\gradlew.bat assembleDebug` (Windows) or `./gradlew assembleDebug` (macOS/Linux). The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Requirements

- Android Studio (recommended) or JDK 17+ and Android SDK for command-line build
- **Android NDK** (e.g. 25.1 via Android Studio SDK Manager or as specified in `app/build.gradle.kts`)
- minSdk 24, targetSdk 34
- Device or emulator for testing
- Permissions used by the app include notification (Android 13+), `WAKE_LOCK`, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (for the in-Config battery exemption request)

## License

App code license to be set. The native miner (C) is part of this repository.
