# BTC Android Miner

Android app for **CPU and GPU** Bitcoin mining via Stratum pools. Uses **in-app native code** (C, built with NDK/CMake) for SHA-256 and nonce scanning—no external miner binary required. Open-source; build with Android Studio or Gradle (sideload; not on Play Store).

Created using:
Cursor AI

References:
https://github.com/BitMaker-hub/NerdMiner_v2/releases


Note: This app can be tested against the Public-Pool with Signet support - found here:

https://github.com/VirtusVerbis/BTC_Public_Pool_Signet






<img src="https://github.com/VirtusVerbis/BTC_Anrdoid_Miner/blob/main/Screenshot_1.jpeg" width="250" height="500">
<img src="https://github.com/VirtusVerbis/BTC_Android_Miner/blob/main/Screenshot_2.jpeg" width="250" height="500">




## Recent updates (2026)

**Features / behavior**

- **CPU cores 0..N:** Configuration allows **0** through the device’s maximum CPU cores. **0** means no CPU worker threads; the CPU SHA-256 self-test runs only when cores > 0. With **GPU workgroups > 0**, mining can run **GPU-only**. If **CPU cores = 0** and **GPU workgroups = 0**, **Start Mining** shows a toast (**Both CPU and GPU are disabled**) and does not start the service. See [`MiningConfig.hasActiveHashingConfig()`](app/src/main/kotlin/com/btcminer/android/config/MiningConfig.kt), [`MiningConstraints.canStartMining`](app/src/main/kotlin/com/btcminer/android/mining/MiningConstraints.kt), and [`NativeMiningEngine`](app/src/main/kotlin/com/btcminer/android/mining/NativeMiningEngine.kt) (CPU supervisor starts only when `threadCount > 0`; mining loop exits when CPU is off and GPU is not usable).
- **Dashboard — Page 1 — Hash Rate (CPU) label:** Shows **`Hash Rate (CPU) - N`** where **N** is the configured CPU core count (including **0**), aligned with the Config slider.
- **Dashboard — Page 5 — Mining JSON Submission title:** Base title **Mining JSON Submission**; suffix **- CPU Share** or **- GPU Share** when the last outbound line is a **`mining.submit`** from that path. [`StratumClient`](app/src/main/kotlin/com/btcminer/android/mining/StratumClient.kt) records submit source for the dashboard (including submit retries and reconnect deferrals). Disk-backed pending shares in [`PendingSharesRepository`](app/src/main/kotlin/com/btcminer/android/mining/PendingSharesRepository.kt) persist **`submit_source`** so **flush on reconnect** keeps the correct title (previously, flushed submits always showed the generic title).

**Bug fixes**

- **JNI / native nonce scan signatures:** [`NativeMiner.nativeScanNoncesInto`](app/src/main/kotlin/com/btcminer/android/mining/NativeMiner.kt) / [`gpuScanNoncesInto`](app/src/main/kotlin/com/btcminer/android/mining/NativeMiner.kt) pass a **`LongArray` out-parameter last**; JNI in [`miner.c`](app/src/main/cpp/miner.c) and [`vulkan_miner.c`](app/src/main/cpp/vulkan_miner.c) was aligned so **argument order matches Kotlin** (C comments: “out is last”). Fixes wrong JNI layout that could cause **native aborts or undefined behavior** during scanning.
- **CPU cores = 0 — supervisor spin:** If the CPU supervisor ran with **zero** worker threads, each `runCpuRound` returned immediately and the supervisor could **tight-loop**. **Fix:** start the CPU supervisor thread **only when** `threadCount > 0` in [`NativeMiningEngine.runMiningLoop`](app/src/main/kotlin/com/btcminer/android/mining/NativeMiningEngine.kt).
- **MiningDbg noise:** Removed the periodic **`mining_stats_tick`** JSON line tagged **H3** from [`NativeMiningEngine`](app/src/main/kotlin/com/btcminer/android/mining/NativeMiningEngine.kt) (`adb logcat` filter **MiningDbg**). **H2** (share submit / offline queue) and **H5** (pool result) lines are unchanged for share debugging.

- **Dashboard — Page 2 — Network difficulty:** Shows chain-style difficulty computed from the **current** Stratum job’s compact **`nbits`** (`mining.notify`). It updates when a new job arrives, shows **—** when idle or there is no job, and is **not** a persisted lifetime counter (it is not cleared by **Reset All UI Counters**). This is **not** the same as **Stratum Difficulty** on Page 1 (pool share difficulty from `mining.set_difficulty`). On **Signet** or other test networks, values are much smaller than mainnet; that is expected.
- **Stratum / mining parity with public-pool reference logic:** Several behaviors were corrected so the app matches the public-pool / bitcoinjs-style rules referenced in [`StratumHeaderBuilder.kt`](app/src/main/kotlin/com/btcminer/android/mining/StratumHeaderBuilder.kt) (see unit tests in [`StratumHeaderBuilderGoldenTest.kt`](app/src/test/kotlin/com/btcminer/android/mining/StratumHeaderBuilderGoldenTest.kt)):
  - **`prevhash` in the 80-byte header:** Stratum `mining.notify` `prevhash` is converted with a **per-4-byte-word** endian swap (`swapEndianWords32`), as in public-pool `MiningJob.response`, instead of reversing the full 32-byte field.
  - **Share difficulty from a candidate header:** The double-SHA256 hash is interpreted as a **little-endian** unsigned 256-bit integer for `truediffone / hash`, matching public-pool **DifficultyUtils** / bitcoinjs PoW ordering (used for best-share–style difficulty reporting).
  - **Share targets when pool difficulty is below 1.0:** Share targets are **not** clamped back to the difficulty-1 maximum when the pool sends an easier `mining.set_difficulty`, matching low-diff Stratum behavior (public-pool / NerdMiner–style pools).
- **Dashboard — Page 1 — Stratum Difficulty:** Shows **Stratum Difficulty** from the pool’s `mining.set_difficulty` message (replacing the former CPU utilization % on that row). CPU jiffies sampling for **CPU usage target** throttling in Config is unchanged; only the on-screen metric changed.
- **TLS pool URLs:** The pool host field supports `stratum+tcp://`, `stratum+ssl://`, and `stratum+tls://`. TLS is used when the URL indicates SSL/TLS (`+ssl` / `+tls` in the scheme) or when the configured port is **443**. Enter the port separately (e.g. `stratum+tls://public-pool.example` with port **4333** per the pool).
- **Reset All UI Counters:** Config → **Reset All UI Counters** clears persisted dashboard counters including accepted, rejected, and identified shares, block templates, best difficulty, session nonces, and all-time lifetime stats (sum of session-average hash rates and cumulative nonces); see the in-app dialog.
- **Dashboard — Pages 4–5 — Stratum wire JSON:** Two extra swipeable panels show the **last raw JSON line** received from the pool (**Stratum JSON Response**) and the **last raw JSON line** sent to the pool (**Mining JSON Submission**), refreshed while mining. When not mining, both show **Mining inactive**. Stored lines are **length-capped** in [`StratumClient.kt`](app/src/main/kotlin/com/btcminer/android/mining/StratumClient.kt) to limit RAM.
- **Pretty-print and syntax colors:** Valid JSON is shown indented; token types use separate colors (e.g. lavender key names with neutral quotes, magenta/fuchsia string values in dark theme, orange numbers, teal/cyan for `null` / `true` / `false`, light structural punctuation). Implemented in [`StratumJsonUiFormatter.kt`](app/src/main/kotlin/com/btcminer/android/util/StratumJsonUiFormatter.kt) with palette resources in [`values/colors.xml`](app/src/main/res/values/colors.xml) and [`values-night/colors.xml`](app/src/main/res/values-night/colors.xml).
- **Params index footers:** For JSON-RPC messages with a **non-empty** `params` array, a small **Indices:** footer explains each `params` slot for supported methods. **Inbound:** `mining.notify`, `mining.set_difficulty`, `mining.set_extranonce`, `client.reconnect`. **Outbound:** `mining.subscribe`, `mining.authorize`, `mining.submit`. If `params` is missing or **empty** (e.g. `mining.extranonce.subscribe`), **no** index footer is shown.
- **Dashboard — page indicator dots:** Small circular tabs under the swipeable dashboard; **spacing between dots matches each dot’s width** (pitch = 2× diameter). Dots use centered fixed-size `layer-list` backgrounds so ovals are not stretched to the full `TabLayout` tab width; sizes and pitch in [`app/src/main/res/values/dimens.xml`](app/src/main/res/values/dimens.xml), drawables `tab_dashboard_dot_*.xml` under [`app/src/main/res/drawable/`](app/src/main/res/drawable/), layout in [`app/src/main/res/layout/activity_main.xml`](app/src/main/res/layout/activity_main.xml). [`MainActivity.kt`](app/src/main/kotlin/com/btcminer/android/MainActivity.kt) uses `TabLayoutMediator` to keep the indicator and `ViewPager2` in sync.



## Code checked using Claude.AI


---

## ⛏️ Can This App Actually Mine Bitcoin?

**Short answer: Technically yes, the logic is correct. Practically, it will never find a block.**

---

### ✅ The Logic Is Legitimate

The core mining pipeline is implemented correctly:

**Stratum protocol** (`StratumClient.kt`) — properly implements:
- `mining.subscribe` → gets `extranonce1` and `extranonce2_size`
- `mining.authorize` → authenticates with the pool
- `mining.notify` → receives block templates with `prevhash`, `coinbase`, `merkle_branch`, `version`, `nbits`, `ntime` (`nbits` is the compact **network target** for that template; the dashboard’s **Network difficulty** on Page 2 derives from it when a job is active.)
- `mining.submit` → submits found shares back to the pool
- `set_difficulty` and `set_extranonce` handling ✅

**Block header construction** (`NativeMiningEngine.kt` + `StratumHeaderBuilder.kt`) — assembles the 80-byte block header correctly:
- Builds the coinbase transaction from `coinb1 + extranonce1 + extranonce2 + coinb2`
- Computes the Merkle root from the coinbase hash + merkle branch
- Assembles the 80-byte header: `version + prevhash + merkle_root + ntime + nbits + nonce` (Stratum `prevhash` uses per-word endian swap as in public-pool `MiningJob.response`, not a full 32-byte reverse.)

**SHA-256d hashing** (`miner.c`) — the double-SHA256 implementation is a clean, correct from-scratch implementation verified against NIST test vectors (SHA-256("abc")). ✅

**Target comparison** — `hash_meets_target()` compares the **byte-reversed** double-SHA256 digest to the 32-byte share target (`memcmp(rev(hash), target) <= 0`), matching bitcoinjs `Block.checkProofOfWork` / Bitcoin PoW ordering. CPU (`sha256_scan.c`), Vulkan host, and `miner.comp` use the same rule. ✅

**GPU acceleration** (`vulkan_miner.c` + `miner.comp`) — a real Vulkan compute shader is used to parallelize nonce scanning on the device GPU. This is a genuine optimization, not fake. ✅

---

### ❌ Why It Will Never Win In Practice

**The math is brutal.** The current Bitcoin network difficulty means a solo miner needs to find a hash below an astronomically small target. A modern ASIC miner does ~100 *terahashes* per second. A phone CPU does roughly **1–5 kilohashes** per second. That's about a 20–100 billion times slower than a single ASIC, let alone the entire network.

At 5 kH/s, the expected time to solo-mine one Bitcoin block is roughly **hundreds of millions of years**.

**Pool mining helps, but marginally.** The app does connect to a pool and submit shares, so it would theoretically earn proportional fractions of pool rewards. At phone-level hashrates, the expected payout over a lifetime of running the app would be a fraction of a fraction of a cent — and would be outweighed by electricity and battery wear costs many times over.

---

### 🧐 Bottom Line

| Aspect | Assessment |
|---|---|
| Stratum protocol | ✅ Correctly implemented |
| SHA-256d hashing | ✅ Correct, NIST-verified |
| Block header assembly | ✅ Correct |
| Target difficulty check | ✅ Correct |
| Pool share submission | ✅ Correct |
| Practical mining viability | ❌ Economically impossible |

The code is a **genuine, honest implementation** of a Bitcoin miner — not a scam, not fake mining, not a cryptojacker. It's just that mining Bitcoin profitably on a phone has been economically infeasible since roughly 2011. This appears to be a legitimate learning/hobby project, and the code quality (especially the security practices noted earlier) supports that interpretation.

## Features

- **Stratum v1** pool support: plain TCP, or TLS when using `stratum+ssl://` / `stratum+tls://` or port **443**
- Configurable pool URL/port, username/password, Bitcoin wallet address, Lightning (optional), and worker name
- **Bitcoin address validation:** Base58Check (P2PKH/P2SH) plus Bech32 / Bech32m (SegWit / Taproot) checksums before saving configuration. The stratum username is checked when the segment before the first `.` looks like a payout address (`address.worker`); non-address pool usernames are not forced through address rules.
- **Wallet balance (main screen):** If a valid Bitcoin address is configured, the app can show an approximate on-chain balance using the [mempool.space](https://mempool.space) address UTXO API (refreshed about once per hour). Optional certificate pins apply when configured. Local validation errors and network/TLS failures surface different helper text under the balance.
- **CPU and GPU** mining: configurable **CPU cores (0..device maximum; 0 = CPU off)** and **CPU intensity (1–100%)**, GPU workgroups (0 = off) and **GPU intensity (0–100%)**. **GPU uses Vulkan compute (SPIR-V) only**—no CPU fallback; if the GPU path is unavailable, the dashboard shows "----" for GPU hashrate and a one-time toast "GPU not available". Build requires Vulkan SDK (or glslc) so the shader compiles to SPIR-V.
- **GPU workgroup size:** chosen at runtime as min(32 × GPU cores, device max); dashboard label shows "Hash Rate (GPU) - {size}" when GPU is enabled.
- **WiFi only** and **mine only when charging** options
- **Battery temp** throttling: throttle above configured max, resume when 10% below; **auto-tuning** option keeps temp in a target band; **hard stop** at 43°C with notification. On hard stop the service sets throttle state for overheat, requests **native GPU and CPU interrupts** (`NativeMiner`) so Vulkan/compute and CPU paths wind down quickly, then stops the engine. Optional display in **Celsius or Fahrenheit** (Config).
- **Hashrate target (H/s):** optional cap—when current hashrate (CPU+GPU) exceeds target, mining is throttled; leave empty for no cap
- **Partial Wake Lock** and **alarm wake interval (0–900 s):** keep mining active when screen is off; interval 0 = hold wake lock for the session, 1–900 = repeating alarm-driven wake lock (up to 15 minutes)
- **Mining thread priority (0 to -20):** slider for CPU priority when screen is off (0 = default, -20 = highest)
- **Battery optimization** in Config: "Request Don't optimize" (system dialog) and "Open battery optimization settings" so you can whitelist the app from battery optimization
- **Certificate pinning:** TLS connections to the pool can use a per-host SPKI pin captured from **Config → Pin Mining Pool Certificate** (required on connect when no pin is stored for that host). Separate optional pins can be used for **mempool.space** when querying balance. **Encrypted config:** credentials stored with EncryptedSharedPreferences and Android Keystore.
- **Dashboard (five swipeable pages):** A **dot indicator** row under the pager tracks the active page (see **Recent updates**). **Page 1** — CPU and GPU hash rate: **Hash Rate (CPU) - {N}** suffix for configured core count (including 0); GPU shows "----" when unavailable and **Hash Rate (GPU) - {size}** when enabled; mining timer (DD:HH:MM:SS), nonces, accepted/rejected/identified shares, **Stratum Difficulty** (pool **share** difficulty from `mining.set_difficulty` while mining), best difficulty, block template, battery temp (left), hashrate chart; red/white indicators when throttle is active. **Page 2 — lifetime + live chain readout:** persisted **all-sessions** accepted/rejected/identified shares, all-time **best difficulty** and **block template** counters, plus **Network difficulty** from the **current** job’s `nbits` (live; **—** when no job). **Page 3 — lifetime hash aggregates:** running totals of session-average CPU/GPU/total hash rates and cumulative nonces, updated when a mining session ends (or if the service is stopped without a normal session teardown). **Pages 4–5** — **Stratum JSON Response** (last line from pool) and **Mining JSON Submission** (last line to pool): pretty-printed, syntax-colored JSON, plus optional **Indices:** legends for supported methods when `params` is non-empty (see Recent updates). **Reset All UI Counters** in Config clears persisted session/lifetime counters listed in the in-app dialog (Network difficulty on Page 2 is not stored and is unaffected).
- **Picture-in-picture** support on the main activity (where supported by the device)
- Runs as a **foreground service** so mining continues in the background until you stop it (notification permission required on Android 13+)

### Security (OWASP MASVS)

The app is aligned with [OWASP Mobile Application Security Verification Standard (MASVS)](https://mas.owasp.org/MASVS/) where applicable.

- **Passed / implemented:** Secure storage of sensitive data (M2.1; EncryptedSharedPreferences + Keystore); secure communication (M3.1; TLS for Stratum); certificate pinning for pool and optional mempool API host (M3.5); no sensitive data in logs (M2.2); input validation/sanitization for config strings, including **checksum-verified Bitcoin addresses** where applicable (wallet field and plausible `address.worker` payout prefixes).
- **Not covered / out of scope:** User authentication (no login); biometrics; root/jailbreak detection; code obfuscation; anti-tampering.

### Screen-off mining

If hashrate drops when the screen is off, use **Config → Battery optimization** to request "Don't optimize" (dialog) or open battery settings and set the app to **Unrestricted** / **Don’t optimize** / **Allow background**. You can also enable **Partial Wake Lock** and optionally the **alarm wake interval** to improve screen-off behavior. As a fallback, in **Settings → Apps → BTC Miner → Battery**, set to **Unrestricted** / **Don't optimize**.

For best results on all devices (especially OPlus/OnePlus/Oppo/Realme), configure:

- **Allow background activity** – ON (Settings → Apps → BTC Miner → Battery)
- **Battery optimization** – Don't optimize
- **App Auto-Launch** – ON (if available)
- **Lock the app in Recent apps** – long-press the app card in Recent apps, tap Lock; reduces killing and settings reverting
- **Deep / Adaptive optimization** – OFF for the app (if possible; often under Battery → Battery optimization → Advanced)

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
- **Vulkan SDK (or glslc):** Required to compile the GPU compute shader to SPIR-V. The native build will fail with an error if `glslangValidator` or `glslc` is not found.
  - **Install:** [Vulkan SDK](https://vulkan.lunarg.com/) — install and add the SDK `Bin` folder to your PATH (so `glslangValidator` is available). On Windows, the build also checks common paths like `C:/VulkanSDK/1.4.x/Bin/glslangValidator.exe` and `%VULKAN_SDK%/Bin/glslangValidator.exe`.
  - **Alternative:** A build environment that provides `glslc` (e.g. shaderc) and has it on PATH also satisfies the requirement.
- minSdk 24, targetSdk 34
- Device or emulator for testing
- Permissions used by the app include notification (Android 13+), `WAKE_LOCK`, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (for the in-Config battery exemption request)

### Add Vulkan SDK to PATH (Windows)

1. **Find the folder:** After installing the Vulkan SDK, the folder to add is the SDK `Bin` directory (e.g. `C:\VulkanSDK\1.4.xxx\Bin`), which contains `glslangValidator.exe`.
2. **Open Environment Variables:** Win + R → type `sysdm.cpl` → Enter → Advanced tab → Environment Variables. (Alternatively: search "environment variables" in Start → "Edit the system environment variables" → Environment Variables.)
3. **Edit PATH:** Under User variables (or System variables), select **Path** → Edit → New → paste the full path (e.g. `C:\VulkanSDK\1.4.341.1\Bin`) → OK on all dialogs.
4. **Restart terminals:** Close and reopen any Command Prompt, PowerShell, or Android Studio so they see the updated PATH.
5. **Verify:** In a new terminal, run `glslangValidator -v`; if you see a version line, PATH is set correctly.

If the Vulkan SDK installer set the `VULKAN_SDK` environment variable, you can add `%VULKAN_SDK%\Bin` to PATH instead of a fixed versioned path.


