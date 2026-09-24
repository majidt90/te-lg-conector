# Download

**`MediaBridge-1.0.0.apk`** — the installable application, built from the sources
in this repository. Android 8.0 (API 26) or newer.

| | |
|---|---|
| File size | 279 060 bytes (273 KiB) |
| SHA-256 | `03a18ddf25e9c32e1bb182346317985e495815ce295cf72a9e28dd1ed78aef91` |
| Version | 1.0.0 (versionCode 1), `com.lgmediabridge` |
| Git tag | `v1.0.0` (pins exactly these bytes) |
| Signature | APK Signature Scheme v2 + v3 |
| Signing key | Android debug key (`CN=Android Debug, O=Android, C=US`) |

## Install

**From the phone's browser — direct link:**

```
https://raw.githubusercontent.com/majidt90/te-lg-conector/main/download/MediaBridge-1.0.0.apk
```

or pinned to the released version, which never moves:

```
https://raw.githubusercontent.com/majidt90/te-lg-conector/v1.0.0/download/MediaBridge-1.0.0.apk
```

Open the downloaded file from the notification or from Files, and allow
installation from that source when Android asks.

**From the repository page:** open [`download/MediaBridge-1.0.0.apk`](MediaBridge-1.0.0.apk)
and use the *Download* button. The file is on `main`, so it is there whether you
browse the repository or clone it.

**Confirmed on `main`:** blob `f09f39a9`, 279 060 bytes, SHA-256 as above, and
`apksigner` reports the v2 and v3 schemes valid on the committed bytes.

**From the release page:** [release v1.0.0](https://github.com/majidt90/te-lg-conector/releases/tag/v1.0.0)
carries the whole project at this version as a source archive, with this APK inside
it at `download/MediaBridge-1.0.0.apk`.

**From a computer, over a cable** (Android platform-tools):

```bash
adb install -r MediaBridge-1.0.0.apk
```

If an earlier copy is installed and Android refuses with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, that copy was signed with a different key —
uninstall it first: `adb uninstall com.lgmediabridge`.

Verify the download before installing:

```bash
sha256sum MediaBridge-1.0.0.apk
# 03a18ddf25e9c32e1bb182346317985e495815ce295cf72a9e28dd1ed78aef91
```

## About the signing key

This build is signed with the standard Android **debug** key, which is what makes
it installable by hand without a Play account. It is fine for sideloading and for
using the app; it is not what you would publish to Google Play — for that, build a
release signed with your own keystore (`tools/README.md`, step 2). Anyone can
produce a build with the same public debug key, so treat this file as *convenient*,
not as *verifiably ours* — the SHA-256 above is the identity to check.

## First run

1. Open MediaBridge and grant the media permission (Android 13+ asks for
   *Photos and videos* / *Music and audio*; older versions ask for *Files*).
2. Start sharing. The phone must be on the same Wi-Fi network as the TV.
3. On the LG TV open **Home ▸ Media / SmartShare ▸ Devices**, and pick the phone.
4. Browse and play. Playback goes straight from the phone to the TV; the phone
   screen can be off. **Diagnostics** in the app shows the SSDP, request and
   streaming counters if the TV does not see the phone.

## Rebuilding it yourself

The committed APK is a convenience; the pipeline that produced it is in the
repository and needs no Gradle:

```bash
tools/bootstrap-toolchain.sh                      # once, fetches the build tools
export ANDROID_TC=$HOME/.android-toolchain
python3 tools/build.py --variant release \
    --keystore $HOME/.android-toolchain/tools/debug.keystore \
    --ks-pass android --key-alias androiddebugkey --key-pass android
python3 tools/verify.py                           # 457 checks, no device needed
```

See `tools/README.md` for the whole pipeline and `README.md` for the app itself.
