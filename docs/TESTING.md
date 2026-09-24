# Device test checklist

Requirements from the brief, mapped to observable checks on a real phone + LG 55NANO86VPA.
Everything here is testable without a debugger: the app's **Diagnostics** screen shows the same
counters the server logs, and `Share log` exports the whole session.

Legend: **S** = setup, **D** = discovery, **P** = playback, **R** = reliability, **A** = Android
lifecycle, **U** = UI.

## 1. Setup and permissions

| # | Check | Expected |
|---|---|---|
| S1 | Fresh install, first launch | Onboarding appears; the four steps explain purpose, permissions, network and readiness |
| S2 | Grant media access on Android 13/14 | Library counters populate; Diagnostics shows indexed counts |
| S3 | Deny media access | Home shows the permission banner with a *Grant* action; the TV lists the server but browses an empty tree, never an error |
| S4 | Android 14 "selected photos only" | Banner explains partial access; selected items are browsable, others are absent, nothing crashes |
| S5 | Notification permission denied | Sharing still runs; the notification is absent; Diagnostics flags it |
| S6 | Toggle *Light* / *Dark* / *System* in Settings | Whole app (including dialogs and the bottom sheet) switches immediately and survives restart |

## 2. Discovery

| # | Check | Expected |
|---|---|---|
| D1 | Press *Start sharing* on the home screen | Hero card turns green; address `http://<ip>:8200` shown; Diagnostics: `SSDP listening` |
| D2 | TV → Photos & Videos → device list | **MediaBridge** (or the renamed server) appears within a few seconds |
| D3 | Rename the server in Settings, restart sharing | TV list shows the new name (the TV may need its device list re-opened) |
| D4 | Devices tab → *Scan for TVs* | The LG TV appears with model name, address and capability chips |
| D5 | *Add by address* with the TV's IP | The TV is added without a full scan |
| D6 | Two TVs on the network | Both appear; the server serves both simultaneously |
| D7 | Turn the TV off and on again | The server re-announces; the TV finds it again without touching the phone |
| D8 | Enable client isolation on the router | TV no longer sees the phone; the in-app hint and Diagnostics explain why (expected failure) |

## 3. Browsing and playback

| # | Check | Expected |
|---|---|---|
| P1 | Browse Videos/Photos/Music on the TV | Titles, thumbnails (photos), folders/albums/artists containers as configured |
| P2 | Play an H.264 MP4 (phone video) | Starts quickly, direct play; Sessions screen shows one transfer with a healthy rate |
| P3 | Play an HEVC 4K MKV | Direct play; seek bar usable |
| P4 | Seek in a long video | TV jumps; Sessions screen shows an extra **range request**; playback continues |
| P5 | Play a large file (> 2 GB) | Streams without copying; progress reflects bytes sent |
| P6 | Play a photo album in sequence | Each photo appears; HEIC photos are converted once then instant on revisit |
| P7 | Play an unsupported audio file (Opus/ALAC) with conversion ON | Sessions shows `converting`, then playback starts; second play starts immediately (cached) |
| P8 | Turn audio conversion OFF and play the same file | Clear refusal, never a broken half-play |
| P9 | Hide-unsupported enabled | Files the TV cannot play are absent from the TV tree but still listed in the app with the reason |
| P10 | Play on TV from the app (Devices tab → Connect, then Library → Play) | TV starts the file; home screen shows transport controls and position |
| P11 | Pause / resume / stop / seek / volume from the app | Each action is reflected by the TV (only when the TV advertises AVTransport/RenderingControl) |

## 4. Reliability

| # | Check | Expected |
|---|---|---|
| R1 | Lock the phone for 20 minutes during playback | Playback continues; notification stays; no data-batching stalls |
| R2 | Turn Wi‑Fi off for 10 s and on again | Server rebinds to the (possibly new) address and re-announces; TV resumes after re-selecting the source |
| R3 | Change the phone's IP (leave and rejoin the network) | Diagnostics shows the new address; SSDP re-announces; no manual restart needed |
| R4 | Restart the phone with *Start on boot* on | Sharing resumes; if the OS refuses the boot start, the app says so instead of crashing |
| R5 | Kill the app from recents while sharing | `stopWithTask=false` keeps the server alive; the notification remains |
| R6 | Two TVs playing different files | Both transfers appear in Sessions with separate rates |
| R7 | Stop all streams | Transfers end immediately; sessions list empties |
| R8 | Battery optimisation ON, long 1080p playback | Diagnostics shows "optimised by the system"; app offers the exemption path |
| R9 | 30-minute soak with the TV idle on a browse screen | Memory and battery stay flat; log shows no repeated reconnects |

## 5. UI quality

| # | Check | Expected |
|---|---|---|
| U1 | Every screen rotates and restores | Tab selection survives; no duplicated listeners or leaked polling |
| U2 | TalkBack on | Tab labels, buttons, thumbnails and status dots have content descriptions |
| U3 | Empty library / no devices / never streamed | Each shows a specific empty state with the next action, never a blank screen |
| U4 | Fast scrolling a 5 000-item library | Smooth, thumbnails fill in without blocking; no ANR |
| U5 | Airplane mode while browsing | Error states with retry, no crash |
| U6 | All user-visible text | English only, no placeholder or developer-facing text |

## What is verified without a device

`python3 tools/verify.py` runs a 397-assertion JVM suite over the protocol layer
(the exact XML between phone and TV, the DLNA tokens, the range/time-seek maths,
the media URL scheme, the codec decision tables, and a live HTTP exchange). It exists because a regression in any of those shows up on the TV
as "the phone never appears", "the list is empty" or "seeking is greyed out".
The suite also hardened the XML layer after review: element lookup matched by
prefix, so asking for `<Result>` could return the digits of `<ResultCode>`
followed by the rest of the document, and a file name containing an unpaired
surrogate (a truncated emoji) or a control byte could make a whole DIDL page
unparseable - which the TV shows as an empty list. Both are now covered.

It has found seven real defects so far, two of them severity-1, plus a size-honesty
problem: a converted photo or audio track was listed and counted under the
*original* file's size, so the progress bar could never reach 100% and the session
list showed bytes that were never transferred (a converted resource now reports
its own length, and an unknown one shows what has actually been sent):

* **every video would have been hidden from the TV.** Android stores the *container*
  MIME (`video/mp4`) for ordinary phone videos, and the compatibility check only
  knew codec MIME types (`video/avc`), so it judged them unsupported - with
  "hide unsupported" on, the video library would have been empty.
* **the transfer history could never show anything.** A finished transfer was
  marked finished and then removed from the registry, while the history method
  looked for finished transfers *inside* that same registry - so the sessions
  screen's history was permanently empty. Completed transfers now live in their
  own bounded list (newest first, cap 20), which also keeps "is anything playing
  right now" honest: the wake lock and the notification drop the moment playback
  ends.
* **Ogg Vorbis was refused** although webOS documents it as playable, because the
  documented list had `audio/vorbis` but MediaStore reports `audio/ogg`.
* the client label showed the percent-encoded DLNA device name (`%5bLG%5dwebOS1-TV`)
  instead of the TV model, and was never decoded;
* a multi-range request (`bytes=0-99,200-299`) was answered with a single part
  pretending to be the whole answer;
* clock-style time seeks (`TimeSeekRange.dlna.org: npt=0:02:30-`) were rejected,
  which turned a TV seek into a full-file fetch;
* `resolution` was written `3840×2160` with a typographic multiplication sign
  instead of the ASCII `x` renderers parse.

Everything from section 3 onwards still requires the actual phone and TV.

## Log evidence to look for

When something fails, Diagnostics should contain the corresponding entry:

* `Ssdp` — `M-SEARCH from <tv-ip> for urn:schemas-upnp-org:device:MediaServer:1` (the TV is searching)
  and `sent ssdp:alive …` (the phone is announcing).
* `Server` — `SOAP Browse from Linux/3.0.13 UPnP/1.0 LGE_DLNA_SDK/…` (the TV is browsing and the app
  recorded its user agent), `streaming <file> to <TV>`, `refused <file>: UNSUPPORTED`.
* `ServerService` — `sharing started on <ip>:<port>`, `network changed: …`, `rebinding to <ip>`.
* `PhotoTranscoder` / `AudioTranscoder` — conversion timings and cache file sizes.
* `TvDiscovery` — `found <TV name> at <ip> (LG webOS, playback control)`.
