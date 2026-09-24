# MediaBridge — share this phone's media with an LG webOS TV

MediaBridge turns an Android phone into a **DLNA/UPnP media server** so an LG television
(developed and tuned for the **LG 55NANO86VPA**, webOS **6.5.3-47**) can browse and play the phone's
videos, photos and music over ordinary Wi‑Fi — with the TV pulling the file directly from the phone.

No TV-side app, no account, no cloud, no internet exposure: everything happens on the local network.

```
Phone                                                     LG TV
┌──────────────────────────────┐   SSDP discovery         ┌───────────────────────────┐
│ MediaBridge                  │  ◄──────────────►        │ Photos & Videos / Music   │
│  • UPnP MediaServer (DMS)    │   SOAP Browse/Search     │  (built-in DLNA client)   │
│  • HTTP media endpoint       │  ◄──────────────►        │                           │
│  • optional AVTransport ctrl │   HTTP Range / seek      │                           │
└──────────────────────────────┘  ◄────────────────────── └───────────────────────────┘
```

## What it does

* **Becomes visible to the TV** — advertises a standard UPnP `MediaServer:1` over SSDP and answers the
  TV's `M-SEARCH`, so the phone shows up in the TV's device list the same way a NAS or PC does.
* **Serves the real files** — videos, photos and music are streamed straight out of MediaStore with
  full HTTP `Range` and DLNA `TimeSeekRange` support, which is what makes the TV's seek bar work.
* **Plays what the TV can play, converts only what it cannot** — H.264/HEVC/MKV/MP4/JPEG/PNG/MP3/FLAC
  and friends are sent untouched; HEIC photos are converted to JPEG, and audio formats outside the
  documented webOS list (Opus, ALAC, AMR, …) are converted to AAC on demand and cached.
* **Survives real life** — screen off, app in the background, Wi‑Fi drops and IP changes, TV
  restarts, long 4K files, several TVs at once.
* **Keeps you informed** — a live home screen (sharing state, connected TV, library counts), a
  streaming-activity screen with per-transfer throughput, and a diagnostics screen with the raw log.
* **Modern UI** — light and dark themes, onboarding, empty/loading/error states everywhere,
  accessibility labels, restrained animations, English-only strings.

## Requirements

* Android 8.0 (API 26) or newer. Optimised for Android 13/14 permission and background rules.
* Phone and TV on the same Wi‑Fi network (same band strongly recommended; 5 GHz for 1080p+).
* No third-party libraries: the app is framework-only Java, ~270 KB installed.

## Install

A signed, installable build is produced by the build pipeline:

```bash
ls dist/MediaBridge-debug.apk          # shipped debug build (APK Signature Scheme v2 + v3)
adb install -r dist/MediaBridge-debug.apk
```

Or build it yourself — see below.

## Using it

1. Open MediaBridge → onboarding walks through the two permissions (media access and the
   notification that keeps sharing alive) and checks your Wi‑Fi.
2. Press **Start sharing**. The home screen shows the address the TV will connect to,
   e.g. `http://192.168.1.42:8200`.
3. On the TV: open **Photos & Videos** (or **Music**) → choose the device/source list → select
   **MediaBridge**.
4. Browse. Playback happens directly between the phone and the TV; the phone screen can be off.
5. **Devices** tab: find other TVs/renderers on the network, connect to one, and (where the TV
   exposes `AVTransport`) start driving playback and volume from the phone.
6. **Streaming activity** (header icon): live transfer list with throughput, progress and a stop-all
   action. **Diagnostics** (bug icon): network, SSDP and request counters, test results and the
   shareable log.

If the TV does not list the phone, the diagnostics screen and the in-app hint answer the usual
causes: guest network / client isolation, multicast filtering by the router, phone on a VPN, or the
TV being on a different band or VLAN.

## Building

### Android Studio / Gradle (normal development)

Open the repository in Android Studio, or use a Gradle installation of your own:

```bash
gradle :app:assembleDebug
```

The project is a standard AGP 8.5 / Gradle build (`settings.gradle.kts`, `build.gradle.kts`,
`app/build.gradle.kts`), Java 11, no dependencies, `android.useAndroidX=false`.
The Gradle wrapper scripts are intentionally not committed (they are a binary blob); Android Studio
generates them on first open, or run `gradle wrapper --gradle-version 8.7` once.

### Gradle-free pipeline (used for the shipped APK)

For headless environments, `tools/build.py` performs the whole pipeline with the Android SDK build
tools, the Eclipse compiler and `d8`/`apksigner` — the same sources, no Gradle, no Maven:

```bash
ANDROID_TC=$HOME/.android-toolchain python3 tools/build.py --variant debug
#  → dist/MediaBridge-debug.apk
```

Useful flags: `--variant release`, `--out dist`, `--clean`, and for release builds
`--keystore/--ks-pass/--key-alias/--key-pass`.

### Verifying the part that talks to the TV

The wire format is covered by a device-free test suite: it compiles the same
protocol classes the APK uses (DIDL-Lite, SCPDs, SOAP, SSDP, range maths, the HTTP
framing) on a plain JVM and runs ~220 assertions, including a real HTTP exchange
over a loopback socket.

```bash
python3 tools/verify.py            # add --verbose to see every check
```

`tools/bootstrap-toolchain.sh` reproduces the toolchain directory (`android.jar`, `aapt2`, `d8`,
`apksigner`, `ecj`, a debug keystore) from packages that are reachable without Google's Maven
mirrors; provenance is noted in the script.

## How it is built

```
app/src/main/java/com/lgmediabridge/
  core/       logging, JSON, XML, formatting and shared thread pools
  net/        HTTP/1.1 server + request/response/ranges, LAN state watcher
  dlna/       SSDP responder, device/SCPD description, ContentDirectory, DIDL-Lite
  catalog/    MediaStore index (videos/photos/audio), folders, search, thumbnails
  compat/     per-file "will this TV play it?" engine from the documented webOS tables
  transcode/  photo → JPEG, audio → AAC (MediaCodec), both cached and bounded
  stream/     live transfer registry and metrics
  server/     the media server runtime: routes, SOAP, GENA, streaming
  control/    TV discovery, UPnP AVTransport/RenderingControl, remembered devices
  service/    foreground service (+ boot and notification receivers)
  ui/         onboarding, home, library, devices, settings, sessions, diagnostics
  res/        light/dark themes, strings (English only), drawables, layouts
```

The research behind these decisions — what the TV documents, what community evidence shows, what is
uncertain, and which standards are used — is in **[docs/RESEARCH.md](docs/RESEARCH.md)**.
The device test checklist that maps requirements to checks on a real TV is in
**[docs/TESTING.md](docs/TESTING.md)**.

## Privacy and security

* The server binds the phone's **local Wi‑Fi address only** and serves only media that MediaStore
  exposes to the app; it is not reachable from the internet and there is no cloud component.
* Optional **trusted devices only** mode restricts transfers to addresses you approve; the app
  remembers the devices that have actually used the library so the switch is easy to turn on later.
* Any webOS pairing key is stored **encrypted with a non-exportable Android Keystore key** and is
  excluded from backups; pressing *Forget* deletes it.
* No analytics, no telemetry, no network access beyond the LAN.

## Licence

See repository metadata.
