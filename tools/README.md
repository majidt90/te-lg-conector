# Build tools

Three small scripts build and verify MediaBridge without Gradle. The app has zero
third-party dependencies (pure framework APIs), so the whole Android pipeline can
be driven directly with the SDK build tools, the Eclipse compiler and a JRE.

| Script | What it does |
|---|---|
| `bootstrap-toolchain.sh` | Fetches a complete, working toolchain into `~/.android-toolchain` |
| `build.py` | Compiles, dexes, packages, aligns and signs the APK |
| `verify.py` | Compiles and runs the JVM test suite for the TV-facing protocol code |

Android Studio / Gradle remains the normal path for day-to-day development
(`gradle :app:assembleDebug`); both paths compile exactly the same sources.

## 1. Get a toolchain

```bash
tools/bootstrap-toolchain.sh                 # → ~/.android-toolchain
tools/bootstrap-toolchain.sh /opt/android-tc # or anywhere else
```

Prerequisites: `python3`, `pip3` and `npm` with network access. The script puts
these files in place:

```
$TC/java/bin/java         JRE (Temurin, via the pypi package jdk4py)
$TC/tools/aapt2           resource compiler/linker (Android build-tools)
$TC/tools/android.jar     SDK platform, API 34
$TC/tools/d8.jar          dexer
$TC/tools/apksigner.jar   signer
$TC/tools/ecj-*.jar       Eclipse compiler (runs on a JRE; no javac needed)
$TC/tools/debug.keystore  Android debug key, for local installs
```

**Provenance.** Because `dl.google.com` and Maven Central are unreachable in some
locked-down environments, the script takes the build-tools payload from npm
packages that redistribute the *unmodified* official binaries
(`@drxiaozhi/minapk` carries build-tools 34.0.0 — `android.jar`, `d8.jar`,
`apksigner.jar`, `ecj`, `debug.keystore`; `aaptjs3` carries `aapt2` for
linux-x64). Verified byte-for-byte against build-tools 34.0.0 before use. If you
already have a JDK and the Android SDK, point `--toolchain` at your own directory
instead and skip this step.

## 2. Build the APK

```bash
export ANDROID_TC=$HOME/.android-toolchain        # or pass --toolchain

python3 tools/build.py --variant debug            # → dist/MediaBridge-debug.apk
python3 tools/build.py --variant release \
    --keystore ~/my-release.keystore --ks-pass '***' \
    --key-alias mykey --key-pass '***'            # → dist/MediaBridge-release.apk
```

Flags: `--variant debug|release`, `--out <dir>` (default `dist/`), `--clean`,
`--toolchain <dir>`, and the signing options above. A release build refuses to run
without an explicit keystore — it will not silently ship something signed with the
debug key. To try the release build locally, the debug keystore from the
toolchain can be used:

```bash
python3 tools/build.py --variant release \
    --keystore $HOME/.android-toolchain/tools/debug.keystore \
    --ks-pass android --key-alias androiddebugkey --key-pass android
```

Pipeline stages, in order: `aapt2 compile` → `aapt2 link` → `ecj` (Java 11,
`-source/-target 11`) → `d8` (dex, min-api 26) → repack with 4-byte-aligned
`resources.arsc` → `apksigner` (v2 + v3). Failures print the last part of the
failing tool's output; for a full log, run the failing command by hand.

Install the result:

```bash
adb install -r dist/MediaBridge-debug.apk
```

## 3. Verify the protocol code

```bash
python3 tools/verify.py                # 457 checks across 17 areas, ~6 s
python3 tools/verify.py --verbose      # print every check
```

The harness compiles the TV-facing classes (DIDL-Lite, SOAP, SSDP, SCPDs, range
maths, the HTTP framing, the codec tables, the conversion caches) against a
trimmed platform jar and runs them on a plain JVM — no emulator, no device. It
needs the same toolchain as `build.py`. `tools/verify/stubs/` holds the two
Android classes those files touch at runtime (`android.util.Log`, `LruCache`).

## Layout

```
tools/bootstrap-toolchain.sh   toolchain fetcher
tools/build.py                 Gradle-free build (aapt2 → ecj → d8 → apksigner)
tools/verify.py                compiles + runs the JVM suite
tools/verify/MediaBridgeTests.java   the suite itself
tools/verify/stubs/            android.util stubs for the harness
.build/                        scratch: manifest, res.zip, base.apk, gen/R.java, verify/
dist/                          built APKs (git-ignored)
```
