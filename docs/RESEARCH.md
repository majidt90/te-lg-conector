# Research report — sharing an Android phone's media with an LG 55NANO86VPA (webOS 6.5.3-47)

Target device: **LG 55NANO86VPA**, webOS **6.5.3-47 "kisscurl-koli"** (2021 NanoCell 4K).
Deliverable 2 (the Android application) is built strictly on the findings below.

## 0. How to read the evidence in this document

Every non-obvious claim is labelled:

| Label | Meaning |
|---|---|
| **[DOC]** | Stated in the manufacturer's own documentation (LG webOS developer site, LG e-guide, LG support pages, Android developer documentation, UPnP/DLNA specifications). |
| **[VER]** | Independently reproduced by real users and published (community bug reports, forum posts with packet captures, open-source implementations). Behaviour is real, but could vary by firmware or region. |
| **[UNC]** | Not documented and not reliably reproduced. The app must not depend on it; where it cannot be avoided, it is guarded by a runtime check. |

This distinction matters because the LG feature set mixes a fully standard half (DLNA/UPnP) with a
proprietary half (the ThinQ / second-screen channel), and only the first half may be used as a
protocol contract.

---

## 1. What the television actually supports

### 1.1 The TV is a DLNA player, not a DLNA server [DOC]

LG's own e-guide describes DLNA playback on webOS TVs: the TV can browse and play photos, video and
music from a **media server** on the same network (`Deep Link/Photos & Videos`, and the older
*SmartShare* entry point in newer firmware is reached from the input/home source list).
Two consequences are documented and change the whole design:

* **The TV is the client (DMR/browser)** — it pulls files from a server. It does not push files to
  the phone, and it exposes no DLNA "content directory" of its own that a phone could browse.
* A phone can only appear in the TV's list if the phone runs a **Digital Media Server (DMS)**.

The TV's roles in the UPnP AV model **[DOC]**:

| Role | Meaning | Who performs it here |
|---|---|---|
| DMS — Digital Media Server | Owns the content, advertises itself with SSDP, answers ContentDirectory browse requests, serves the bytes over HTTP | **the phone** |
| DMR — Digital Media Renderer | Receives a URI and renders it (**not** necessarily browsable by a controller) | the TV |
| DMC — Digital Media Controller | Finds a renderer and pushes a URI to it | optional, implemented on the phone via `AVTransport` |

### 1.2 Documented AV capabilities of the webOS 6.0 platform [DOC]

From LG's official *Video & Audio 6.0* specification page (webOS 6.0 is the platform generation of
this set; the site also publishes a 6.5 page with the same model-year content):

**Video containers and codecs**

| Container | Video codecs listed | Audio codecs listed |
|---|---|---|
| `.mp4`, `.m4v`, `.mov` | H.264/AVC, MPEG‑4, HEVC, AV1 | Dolby Digital, Dolby Digital+, AAC, MP3, AC‑4, MPEG‑H |
| `.mkv` | MPEG‑2, MPEG‑4, H.264, VP8, VP9, HEVC, AV1 | Dolby Digital, DD+, AAC, PCM, DTS, MP1‑3 |
| `.ts`, `.trp`, `.tp`, `.mts` | H.264, HEVC | Dolby Digital, DD+, AAC, AC‑4 |
| `.avi` | Xvid, H.264, MJPEG, MPEG‑4 | MP1‑3, Dolby Digital, LPCM, ADPCM |
| `.asf`, `.wmv` | VC‑1 | WMA |
| `.mpg`, `.mpeg`, `.dat`, `.vob` | MPEG‑1, MPEG‑2 | MP1‑3 |
| `.3gp`, `.3g2` | H.264, MPEG‑4 | AAC, AMR |

**Bitrate ceilings** (documented): Full HD H.264/HEVC ≤ 40 Mbps. On 4K models: H.264 3840×2160@30P
L5.1 ≤ 50 Mbps, HEVC 4K@60P ≤ 60 Mbps, VP9 4K ≤ 50 Mbps, AV1 4K ≤ 50 Mbps, 8K AV1/HEVC ≤ 100 Mbps.
4K video is additionally documented as restricted to `.mkv`/`.mp4`/`.ts` containers with
H.264/HEVC video and Dolby Digital/DD+/AAC audio.

**Audio-only**: MP3 (32–320 kbps, 16–48 kHz), WAV/PCM (8–96 kHz), OGG Vorbis, WMA (v7+), FLAC
(≤ 96 kHz, 2 channels).

**Images**: JPEG, PNG, GIF, BMP, WebP. (HEIC/HEIF is *not* in the list, even though the phone's
camera produces it by default — this single fact drives the photo path in the app.)

**Explicit exclusions [DOC]**: GMC and Qpel are not supported; "AAC Main profile is not supported"
(LC is fine).

**Subtitles and audio tracks [DOC]** — LG's e-guide is explicit: over DLNA the TV does **not**
support internal (embedded) subtitles and does **not** support switching between multiple embedded
audio tracks; only a separate sidecar subtitle file is usable, and DLNA's own subtitle rules
(VTT in DLNA media-server implementations) apply. The app therefore never promises track selection
and never advertises internal-subtitle support.

**Caveat [UNC]**: AV1 support is reported inconsistently (LG support articles describe AV1 as
8K-specific for this generation while the NanoCell sheet lists a 4K AV1 decoder). The app treats AV1
as *probably* supported but never assumes it: verdicts come from the documented container/codec
tables, and a genuinely failed playback falls back to the "unsupported" verdict.

### 1.3 Practical DLNA behaviour of LG TVs [VER]

* Real LG TVs from webOS 3.0 onwards identify themselves in SOAP requests with a
  `User-Agent: Linux/… UPnP/1.0 LGE_DLNA_SDK/1.6.0 [TV][LG]<model>/<firmware> DLNADOC/1.50` and send
  `Browse` with `BrowseFlag=BrowseDirectChildren`, `ObjectID`, `StartingIndex`, `RequestedCount`,
  `SortCriteria` — a capture from a 32LB5800 is published in the Emby community, and the same shape
  is observed on newer sets.
* **Seeking is a server feature, not a TV feature**: a documented user report shows a modern LG set
  (55UM7600) greying out the seek bar unless the server advertises random access, i.e. unless the
  browse response carries `DLNA.ORG_OP=01` and the HTTP endpoint honours `Range`.
* LG's own troubleshooting pages describe DLNA over 1080p as best on 5 GHz or wired Ethernet, and
  note that many TVs connected to one server can degrade playback — relevant to the "multiple
  devices" requirement: the phone must be able to serve several TVs, but concurrent 4K streams on a
  single Wi-Fi radio are the bottleneck, not the software.

### 1.4 What the TV does *not* offer [DOC/VER]

* No SMB/CIFS client for media playback — network folders are not a supported path for photo/video
  playback on these sets; UPnP/DLNA is the supported mechanism. (This closes off the "just share a
  folder" approach.)
* No public, documented HTTP API on the TV for "play this file from your phone".
* The documented web app platform (`webOSTV.js`, Luna services) runs **on** the TV, not from a
  phone; it cannot be used by an Android app to push media.

---

## 2. How LG ThinQ achieves it, and why it is not the path to follow

LG ThinQ is a closed, proprietary application. What is publicly known:

* It pairs over the **webOS second-screen service (SSAP)** on TCP 3000 (unencrypted) or 3001
  (TLS, self-signed), using a JSON-over-WebSocket handshake: `register` → the TV shows an
  on-screen prompt → the phone stores the returned `client-key` and re-uses it for silent
  reconnects [VER — the protocol is reverse-engineered by several open-source projects].
* Commands such as `ssap://media.controls/play|pause|stop|rewind|fastForward`,
  `ssap://audio/setVolume`, `ssap://system.launcher/open`, `ssap://media.viewer/close` are known from
  those projects [VER], but they are **not** a documented public API and several of them are broken
  on newer firmware: `system.launcher/getAppState` returns 403 and
  `com.webos.media/getForegroundAppInfo` returns 404 on webOS 6.0 [VER].
* Crucially, ThinQ/SSAP is a **control channel**, not a media channel: there is no documented
  SSAP method that says "serve this file from my phone and play it on the TV". The TV-side
  `luna-send` media APIs that do exist (`com.webos.media/controlPlayback`,
  `com.webos.service.audio/setVolume`) are only reachable from processes **on the TV**, not from a
  phone [DOC].

**Conclusion**: SSAP is used by this app *only* as an optional extra (identify an LG set during
discovery, remember a pairing if the user chooses it) and is never required for streaming. The media
path is DLNA, which the TV documents.

---

## 3. How BubbleUPnP achieves it, and what is worth reproducing

BubbleUPnP is the reference Android DLNA client, and its documented architecture is instructive:

* The app itself is a **Control Point + Renderer + local Media Server**: it advertises a DLNA server
  on the phone, browses media (local and remote), and controls renderers through UPnP AVTransport
  [DOC — BubbleUPnP's own documentation].
* Its optional desktop **BubbleUPnP Server** adds OpenHome renderers, media proxying and
  transcoding (audio → WAV, video → H.264 + MP3 in MPEG‑TS) for renderers that cannot handle the
  original file.
* For *external* renderers (like an LG TV) the phone-side server simply serves the original bytes
  with `Range` support; transcoding is only applied when the renderer demonstrably cannot play.

**What this app reproduces**: an embedded DMS on the phone + optional AVTransport control, plus
**targeted** transcoding — never blanket transcoding (`"prefer direct playback"` is a hard
requirement, and blanket conversion of every file would also break seeking and burn battery).

**What this app does not reproduce**: OpenHome renderers, cloud relay, or a desktop component.
Neither is needed for a phone→TV setup on one Wi-Fi network.

---

## 4. The standards stack that is actually appropriate

| Layer | Standard | Use in this app |
|---|---|---|
| Discovery | SSDP (UPnP 1.0/1.1) over UDP multicast 239.255.255.250:1900 | Announce the phone as a MediaServer; answer `M-SEARCH` (`ssdp:all`, `upnp:rootdevice`, MediaServer device/service types) and send periodic `NOTIFY ssdp:alive` + `ssdp:byebye` |
| Description | UPnP device description XML | `deviceType = urn:schemas-upnp-org:device:MediaServer:1`, `dlna:X_DLNADOC = DMS-1.50`, service list, SCPDs |
| Content listing | UPnP AV **ContentDirectory:1** over SOAP | `Browse` (DirectChildren/Metadata), `Search`, `GetSortCapabilities`, `GetSearchCapabilities`, `GetSystemUpdateID` |
| Capability negotiation | UPnP AV **ConnectionManager:1** | `GetProtocolInfo` (the `http-get:*:<mime>:<DLNA tokens>` list the TV probes before browsing) |
| Transfer | HTTP 1.1 with `Range` (+ DLNA `TimeSeekRange.dlna.org`) | Byte-exact seeking, resume, and the TV's own buffering behaviour |
| Metadata | DIDL‑Lite (MPEG‑21 DIDL subset) in the SOAP `Result` | `object.item.videoItem.movie`, `object.item.audioItem.musicTrack`, `object.item.imageItem.photo`, `res` with `protocolInfo`, `size`, `duration`, `resolution`, `albumArtURI` |
| Events | GENA `SUBSCRIBE`/`NOTIFY` | `SystemUpdateID` changes so a TV that caches the browse tree refreshes after new media appears |
| Control | UPnP AV **AVTransport:1** and **RenderingControl:1** | `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetTransportInfo`, `GetPositionInfo`, `GetMediaInfo`, `GetVolume`, `SetVolume`, `SetMute` — **only** when the TV's own description advertises those services |
| DLNA hints | `DLNA.ORG_OP`, `DLNA.ORG_CI`, `DLNA.ORG_FLAGS`, `DLNA.ORG_PN`, `contentFeatures.dlna.org`, `transferMode.dlna.org` | The tokens LG's player inspects to decide whether random access (seeking) is available |

DLNA-specific decisions taken from the guidelines as implemented by widely used servers
(Emby/Jellyfin/MediaMonkey documentation):

* `DLNA.ORG_OP=01` — the first digit is "TimeSeekRange supported", the second is "Range
  supported"; `01` is the combination LG sets honour for seeking.
* `DLNA.ORG_CI=1` when the served bytes are a conversion of the original file, `0` for direct play.
* `DLNA.ORG_FLAGS` is 8 hex digits followed by 24 reserved zeros; streaming + background-transfer
  flags are set.
* `DLNA.ORG_PN` is only emitted for profiles that are genuinely matched (JPEG_TN/JPEG_LRG/PNG_LRG/
  GIF_LRG, MP3, AAC_ISO_320, AC3, FLAC, LPCM). No video profile name is claimed: a wrong `PN` is
  worse than none because the renderer skips its own capability checks.

---

## 5. Android platform constraints that shaped the implementation

| Constraint | Source | Consequence in the app |
|---|---|---|
| Scoped storage; Android 13+ granular media permissions (`READ_MEDIA_IMAGES/VIDEO/AUDIO`), Android 14 "selected photos only" (`READ_MEDIA_VISUAL_USER_SELECTED`) | [DOC] Android developer docs | Library reads **MediaStore only**; no `MANAGE_EXTERNAL_STORAGE`, no direct path assumptions; partial grants are reported honestly in the UI instead of pretending to be complete |
| Multicast is not received while the screen is off / on many devices at all unless a `MulticastLock` is held | [DOC] `WifiManager.MulticastLock` | Discovery and the SSDP responder hold a multicast lock for their lifetime |
| Foreground services must declare a type on Android 14+, and `POST_NOTIFICATIONS` is required for the ongoing notification to be visible | [DOC] | `MediaServerService` runs as `dataSync` with a typed `startForeground` call and asks for notification permission during onboarding |
| Background execution limits / Doze / battery optimisation can freeze or batch a long transfer | [DOC] | Partial `WakeLock` + optional `WifiLock`, plus a visible "exempt from battery optimisation" path in Settings and Diagnostics |
| Cleartext HTTP is blocked by default from Android 9 | [DOC] | `android:usesCleartextTraffic="true"` — required because DLNA media URLs are plain `http://` on the LAN |
| Android 15 restricts launching `dataSync` foreground services from `BOOT_COMPLETED` for apps targeting SDK 35+ | [DOC] | The app targets SDK 34 and treats a boot-start failure as non-fatal (the user can start sharing from the app) — documented in the code |
| `ConnectivityManager.NetworkCallback` is the reliable way to observe IP changes | [DOC] | Network changes rebind the HTTP server and re-announce SSDP without losing settings |

---

## 6. Media compatibility strategy

Compatibility is decided per file, in three possible outcomes, and the app always prefers the first:

1. **DIRECT** — the container and every stream inside it are in the documented list.
   The file is served byte-for-byte, with `Range`/`TimeSeekRange` and a real `Content-Length`.
   This is the path for essentially all phone-shot video (H.264/HEVC in MP4/MOV/MKV) and JPEG/PNG.
2. **PHOTO_CONVERT / AUDIO_CONVERT** — the file is outside the documented list but can be converted
   **on the phone** before the TV sees it:
   * photos: HEIC/HEIF/AVIF/oversized JPEG → decoded with `BitmapFactory`, EXIF rotation baked in,
     re-encoded to JPEG (≤ 2560 px long edge), cached on disk;
   * audio: Opus/ALAC/AMR/DTS/… → decoded with `MediaCodec` and re-encoded to AAC in an MP4
     container, cached on disk (a *file*, not a live stream, because a live ADTS stream could not be
     seeked).
   Conversion is on-demand, never for the whole library, and is disabled by the user if unwanted.
3. **UNSUPPORTED** — cannot be converted (or conversion is switched off) and is not in the
   documented list. The TV never receives a URL that would fail mid-playback: the browse tree can
   hide these files (default) and the details sheet explains exactly which stream is the problem.

**A container is not a codec.** Android's MediaStore reports the *container* MIME
(`video/mp4`, `video/x-matroska`, `video/webm`, `video/mp2t`, `video/x-msvideo`) for
ordinary phone videos; the codec MIME (`video/avc`, `video/hevc`) is only available
by opening the file. A container MIME therefore means "codec unknown", and the file
is offered rather than hidden — only a codec MIME can fail the codec list. Hiding a
file on a guess would empty the television's library of the very videos it plays
best, which is what the verification suite caught during development (see
`docs/TESTING.md`).

Video conversion is deliberately **not** implemented. Honest rationale:

* A general H.264 re-encode (with the accompanying audio) is what BubbleUPnP Server does on a
  desktop, and it is what `ffmpeg` does; neither is available to an Android app that must stay
  dependency-free, and a `MediaCodec` video transcode would be slow, would lose quality and would
  break seeking on the TV.
* On this TV generation the realistic failure cases are narrow (DTS/TrueHD audio in an MKV, VC‑1 in
  a WMV, AV1 depending on region). Rather than silently re-encoding everything, the app detects them
  precisely, refuses them cleanly, and says why — which is the behaviour the user can act on.

---

## 7. Chosen architecture (and the alternatives that were rejected)

```
Android phone                                             LG TV (webOS)
┌───────────────────────────────────────────────┐         ┌──────────────────────────┐
│ MediaBridge                                   │  SSDP   │ Photos & Videos / Music  │
│  • SsdpServer      ← M-SEARCH / NOTIFY ───────┼────────►│  (DLNA client)           │
│  • HttpServer  ──── /rootDesc.xml, SCPDs ─────┼────────►│                          │
│  • ContentDirectory ── SOAP Browse/Search ────┼────────►│                          │
│  • ConnectionManager ── GetProtocolInfo ──────┼────────►│                          │
│  • /media/<id>/<name>.<ext>  (Range, seek) ───┼◄────────┼── GET with byte ranges   │
│  • Transcoders (photo → JPEG, audio → AAC)    │         │                          │
│  • GENA event SUBSCRIBE → SystemUpdateID      │         │                          │
│                                               │         │                          │
│  (optional control point)                     │         │                          │
│  • TvDiscovery ── SSDP M-SEARCH for renderers ┼────────►│                          │
│  • TvControl ──── AVTransport/RenderingControl┼────────►│ (only if advertised)     │
└───────────────────────────────────────────────┘         └──────────────────────────┘
```

**Rejected alternatives**

| Approach | Why not |
|---|---|
| SSAP/ThinQ-only ("remote control the TV's player") | Undocumented, version-fragile, and it is not a media transfer channel: there is no documented way to make the TV read a local phone file over it. |
| SMB / shared folder | Not a supported playback path on LG TVs; UPnP/DLNA is. |
| Cloud upload / relay | Violates "never expose phone files to the public internet" and adds latency and cost. |
| Chromecast / Google Cast protocol | Not supported by LG webOS TVs. |
| Screen mirroring (Miracast) | Mirrors the screen, cannot browse the library, burns battery, and does not satisfy "media is streamed directly". |
| Blanket transcoding of the whole library | Breaks direct play, seeks, battery life and quality; explicitly rejected by the requirements. |
| Live transcoding of a whole file into a progressive stream | The TV could play it, but it could never be seeked, which the requirements rule out. |

---

## 8. Known limitations and how the app handles them

| Limitation | Reality | Handling in the app |
|---|---|---|
| Internal subtitles over DLNA | Not supported by LG's DLNA client [DOC] | Never advertised; documentation states sidecar WebVTT is the only path |
| Multiple embedded audio tracks | Not switchable over DLNA [DOC] | Not promised; a file whose *default* track is unsupported is reported with that exact reason |
| DTS/TrueHD audio | Not in the webOS 6.0 list; historically removed on some model years [DOC/VER] | Detected → reported; audio conversion covers the salvageable cases (not DTS-in-video, which needs video conversion) |
| AV1 | Contradictory sources [UNC] | Never asserted in the UI; verdicts follow the documented tables |
| Router multicast filtering / client isolation / guest networks | Very common in practice [VER] | Unicast M-SEARCH to the default gateway in parallel with multicast; a diagnostics screen explains the one-line fixes; the TV's own search can also be triggered manually |
| Phone VPN active | Hides the LAN interface [VER] | Surface-detected flow: no local address → explicit message rather than a mysterious "TV cannot see phone" |
| Multiple concurrent TVs | Supported technically; the Wi-Fi radio is the limit [VER] | Server serves any number of clients; the sessions screen shows per-client throughput |
| LG photo player may request full-size JPEGs | Observed pattern [VER] | Photos are served as-is when supported; oversized images are converted once, cached, and reused for thumbnails |
| Battery cost of a long-running server | Unavoidable while sharing | Sharing is user-controlled, stoppable from the notification, and diagnostics reports battery-optimisation state |

---

## 9. Verification status of this delivery

* **Verified by execution in this environment**: the app compiles from source, dexes, packages,
  aligns and signs into an installable APK by two independent paths (Gradle files are provided for
  ordinary Android Studio use; a Gradle-free pipeline under `tools/` produces the APK that is
  shipped in `dist/`), with `apksigner` confirming APK Signature Scheme v2 + v3 and the packaged
  manifest reporting the expected permissions, minimum SDK and launcher component.
* **Not verifiable here (no TV, no Android device, no network)**: real playback on the target
  television. `docs/TESTING.md` contains the device checklist that maps each requirement to an
  observable check, including the exact SOAP request/response pairs to look for in the diagnostics
  log when something fails.
* **Uncertain behaviour in the wild** is called out in the app itself, in the compatibility sheet
  and the diagnostics screen, rather than being hidden behind a success message.
