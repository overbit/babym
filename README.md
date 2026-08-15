# babym

A peer-to-peer baby monitor for Android. Install it on two devices, point one at
the cot, watch from the other. Video and audio travel directly between the two
phones over Wi-Fi Direct — no router, no internet connection, no cloud, no
account, no mobile data.

[![CI](https://github.com/overbit/babym/actions/workflows/ci.yml/badge.svg)](https://github.com/overbit/babym/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/overbit/babym)](https://github.com/overbit/babym/releases/latest)

<!-- TODO: add a screen recording or two screenshots here — camera view and viewer
     view side by side. This is the single highest-value addition to this README. -->

## Why this exists

Commercial baby monitors either use a proprietary radio you can't inspect, or
they stream your nursery through a vendor's servers. This does neither. The two
devices form a direct Wi-Fi Direct link and talk only to each other.

## What it does

- One device runs in **camera mode**: it captures video and audio and streams
  them to the paired device.
- The other runs in **viewer mode**: it displays the live video and plays the
  audio.
- Audio is **one-way only** (camera → viewer). There is no talk-back.
- The viewer can switch the **camera phone's flashlight** on and off over the
  same direct link, to check on the cot without walking in. The camera phone
  reports back whether its camera has a flash and whether the light is lit, and
  the light switches off by itself when the camera phone changes camera or the
  stream ends.

## What it does not do

Being explicit about this, because a baby monitor you trust is one whose limits
you know:

- **No recording.** Nothing is written to disk; the stream is live only.
- **No remote access.** Both devices must be within Wi-Fi Direct range of each
  other (broadly, same-room to same-house, depending on hardware and walls).
- **No talk-back**, no lullabies, no motion or cry detection, no notifications.
- **Not a medical or safety device.** It will not alert you if it fails. Do not
  use it as your only means of supervising an infant.

## Requirements

- Two Android devices running **Android 13 (API 33) or later**
- Both devices must support Wi-Fi Direct (Wi-Fi P2P) — most do
- No SIM, data plan, or Wi-Fi network needed on either device

## Install

Download the latest APK from the
[Releases page](https://github.com/overbit/babym/releases/latest) and sideload it
on both devices.

**Verify what you're installing.** Every release includes a `SHA256SUMS` file.
Before installing:

```sh
sha256sum -c SHA256SUMS
```

Release APKs are signed with a stable key. Its SHA-256 certificate fingerprint is
published in each release's notes and is identical across all releases — if it
ever changes, that is worth asking about before you install. You can check the
APK you downloaded with:

```sh
apksigner verify --print-certs babym-<version>.apk
```

## Permissions, and why each is needed

| Permission | Why |
| --- | --- |
| `CAMERA` | Capture video in camera mode |
| `RECORD_AUDIO` | Capture audio in camera mode |
| `NEARBY_WIFI_DEVICES` | Discover and connect to the paired device over Wi-Fi Direct (Android 13+) |
| `ACCESS_FINE_LOCATION` | Required by Android for Wi-Fi peer discovery on some devices. Location is never read, stored, or transmitted. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` / `_MICROPHONE` | Keep the stream running while the screen is off |

<!-- TODO: reconcile this table against the actual AndroidManifest.xml and delete
     any row that isn't really requested. An over-declared permission table is
     worse than none. -->

`INTERNET` is not requested.

## Privacy

- No analytics, no telemetry, no crash reporting SDK, no third-party trackers.
- No data leaves the direct link between the two paired devices.
- Nothing is persisted beyond local pairing preferences on the device itself.

## Build from source

```sh
git clone https://github.com/overbit/babym.git
cd babym
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK. The debug APK lands in
`app/build/outputs/apk/debug/`.

<!-- TODO: confirm the module name and output path, and note any local.properties
     or SDK version requirements beyond the defaults. -->

## Contributing

PRs are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). This is a
single-maintainer project, so please open an issue before starting anything
large.

## Security

Found a vulnerability? Please don't open a public issue — see
[SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE).
