# Building cgminer for Android on PC using the NDK

This guide walks you through building the cgminer binary on your PC with the Android NDK, then placing it in the app so Option B (ProcessBuilder) mining works.

---

## Prerequisites

- **PC**: Windows (use WSL2 for Linux-style build), Linux, or macOS.
- **Android NDK**: Installed via Android Studio (SDK Manager → SDK Tools → NDK) or [standalone download](https://developer.android.com/ndk/downloads). Note the path, e.g. `C:\Users\<you>\AppData\Local\Android\Sdk\ndk\<version>` or `$ANDROID_HOME/ndk/<version>`.
- **Build tools**: On Linux/macOS/WSL: `autoconf`, `automake`, `libtool`, `make`, `pkg-config`. Optionally `libcurl`, `libjansson` (or use cgminer’s bundled/static options if the project supports it).

---

## Step 1: Install the NDK

1. Open **Android Studio** → **Settings** (or **File** → **Settings**) → **Appearance & Behavior** → **System Settings** → **Android SDK**.
2. Open the **SDK Tools** tab, check **NDK (Side by side)**, apply and wait for install.
3. Note the path shown (e.g. `C:\Users\...\Android\Sdk\ndk\27.x.x`). Set `ANDROID_NDK_HOME` to this path (or `NDK_HOME`) so scripts can find it.

$env:NDK_HOME

---

## Step 2: Create a standalone toolchain (per ABI)

The NDK includes a script to create a standalone toolchain so you can run `configure` and `make` as with a normal cross-compile.

**On Windows (PowerShell or CMD):** use the NDK’s `make_standalone_toolchain.py`; Python 3 must be on PATH.

**arm64-v8a (most modern phones):**

```bash
# Linux/macOS/WSL (bash)
export NDK=/path/to/your/ndk   # e.g. $ANDROID_HOME/ndk/27.0.12077973
python3 $NDK/build/tools/make_standalone_toolchain.py \
  --arch arm64 \
  --api 24 \
  --install-dir $HOME/android-toolchain-arm64
```

**Windows (adjust paths):**

```powershell
$NDK = "C:\Users\YourName\AppData\Local\Android\Sdk\ndk\27.0.12077973"
python "$NDK\build\tools\make_standalone_toolchain.py" --arch arm64 --api 24 --install-dir "$env:USERPROFILE\android-toolchain-arm64"
```

**armeabi-v7a (32-bit ARM, optional):**

```bash
python3 $NDK/build/tools/make_standalone_toolchain.py \
  --arch arm \
  --api 24 \
  --install-dir $HOME/android-toolchain-arm
```

---

## Step 3: Clone cgminer source

Use a directory **outside** your Android app (e.g. `~/src` or `C:\src`).

```bash
git clone https://github.com/hfjdkedf/cgminer-android.git
cd cgminer-android
```

Or upstream:

```bash
git clone https://github.com/ckolivas/cgminer.git
cd cgminer
```

---

## Step 4: Install build dependencies (Linux/WSL/macOS)

cgminer often needs **libcurl** and **libjansson**. Install with your package manager, e.g.:

- **Ubuntu/Debian:** `sudo apt install build-essential libtool autoconf automake libcurl4-openssl-dev libjansson-dev pkg-config`
- **macOS:** `brew install automake libtool curl jansson pkg-config`

For **Windows** without WSL: building autotools projects is harder; using **WSL2** and the steps above is the simplest.

---

## Step 5: Configure for Android (arm64-v8a)

From the cgminer source directory, set `PATH` to the **standalone toolchain** you created, then run `autoreconf` (or `./autogen.sh`) and `configure` with the Android target.

**Using the arm64 standalone toolchain:**

```bash
export PATH=$HOME/android-toolchain-arm64/bin:$PATH
./autoreconf -i
# or, if the project has it: ./autogen.sh
```


Then configure. Example for a **CPU-only, headless** build (adjust to match the repo’s options):

```bash
./configure \
  --host=aarch64-linux-android \
  --enable-cpumining \
  --without-curses \
  --disable-opencl \
  --disable-adl \
  --enable-static
```



Note: `configure` is usually a **bash** script. In plain Windows PowerShell it often won’t run (e.g. “cannot execute” or similar). The doc recommends using **WSL2** for the build: open a WSL terminal, `cd` to your cgminer directory (e.g. `/mnt/d/cgminer`), then use the **bash** version of the command with `\` and `export PATH=...`. So if the command still fails after fixing the syntax, run the whole configure/build process inside WSL.


# Fix line endings
sed -i 's/\r$//' config.sub config.guess

# Ensure correct shebang (they usually use #!/bin/sh or #!/bin/bash)
sed -i '1s|.*|#!/bin/sh|' config.sub config.guess

# Make them executable
chmod +x config.sub config.guess




- If the project uses different flags (e.g. `--disable-adl` might not exist), check its README or `./configure --help`.
- If you get “cannot find curl/jansson”, you may need to point to Android-built libs or use `--enable-static` and the project’s bundled deps; cgminer-android’s README may have Android-specific notes.

**For armeabi-v7a:** use the arm toolchain and:

```bash
export PATH=$HOME/android-toolchain-arm/bin:$PATH
./configure --host=armv7a-linux-androideabi --enable-cpumining --without-curses --disable-opencl --disable-adl --enable-static
```

---

## Step 6: Build

```bash
make -j4
```

1. make -j4 (build time, on your PC)
-j4 only affects how many compile jobs run in parallel on the machine where you run make (your PC or WSL). It does not depend on the phone.
More cores on the PC → you can use a higher -j (e.g. -j8) to speed up the build.
Fewer cores → use a lower -j (e.g. -j2) so the PC doesn’t overload.
So “use a lower number if you have fewer cores” refers to your build machine’s cores, not the phone’s.

Fix: convert those scripts to Unix line endings
From the cgminer-android directory (/mnt/d/cgminer/cgminer-android), run:

sed -i 's/\r$//' depcomp compile install-sh missing

To clean every script in the tree that might be used during the build:

find . -type f \( -name '*.sh' -o -name 'configure' -o -name 'depcomp' -o -name 'compile' -o -name 'install-sh' -o -name 'missing' -o -name 'config.guess' -o -name 'config.sub' \) -exec sed -i 's/\r$//' {} \;

Then run the build again:

make -j4


The resulting executable is usually named **cgminer** in the top-level directory.

---

## Step 7: Copy the binary into the app

Copy the built **cgminer** into your BTC Android Miner project:

- **arm64 build** →  
  `d:\BTC Android Miner\app\src\main\assets\cgminer\arm64-v8a\cgminer`

- **armv7 build** →  
  `d:\BTC Android Miner\app\src\main\assets\cgminer\armeabi-v7a\cgminer`

Create the directory if it doesn’t exist. The file **must** be named **cgminer** (no extension).

**Example (Windows):**

```powershell
Copy-Item .\cgminer "d:\BTC Android Miner\app\src\main\assets\cgminer\arm64-v8a\cgminer"
```

**Example (Linux/macOS):**

```bash
cp cgminer "/path/to/BTC Android Miner/app/src/main/assets/cgminer/arm64-v8a/cgminer"
```

---

## Step 8: Rebuild the app and run

In Android Studio, sync and build the app (Build → Make Project or Run). When you tap “Start mining”, the app will extract the binary from assets and run it. If the binary is missing, you’ll see an error like “cgminer binary not found for this device”.

---

## When cgminer is updated

1. `git pull` in the cgminer source directory.
2. Re-run **Step 5** (configure) and **Step 6** (make) for each ABI you support.
3. Copy the new **cgminer** into the same `assets/cgminer/<abi>/` paths (Step 7).
4. Rebuild the Android app.

No changes are needed inside Android Studio’s Gradle or app code; only the binary in assets is replaced.
