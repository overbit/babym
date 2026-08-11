# Baby Monitor

A two-phone baby monitor that uses **no mobile data and no internet**. The phones form their
own Wi-Fi Direct group and talk to each other over a plain TCP socket inside it. There is no
server, no account, and nothing leaves the two devices.

Install the same APK on both phones and pick a role on first screen:

- **Camera unit** — points at the cot. Creates the Wi-Fi Direct group and streams video + sound.
- **Viewer** — the phone you keep with you. Finds the camera unit, joins it, and plays the stream.

## How it works

```
camera phone                                     viewer phone
────────────                                     ────────────
Camera2 ──► MediaCodec H.264 encoder                   MediaCodec H.264 decoder ──► SurfaceView
              (surface input, no copies)                        ▲
                     │                                          │
AudioRecord ──► 16 kHz mono PCM                          AudioTrack + level meter
                     │                                          │
                     └──► StreamServer :8988 ══ Wi-Fi Direct ══► StreamClient
                              ◄────────── torch / flip camera ──────────
```

**Group formation.** The camera unit calls `createGroup()` to become an *autonomous group
owner*. That makes its address deterministic, so the viewer never has to discover an IP: after
joining, Android hands it `WifiP2pInfo.groupOwnerAddress` and it connects straight to port 8988.
The viewer always joins with `groupOwnerIntent = 0` so it never contests ownership.

**Wire format.** One TCP connection carries everything. Each message is
`type(1) + flags(4) + presentationTimeUs(8) + length(4)` followed by the payload
(`net/Protocol.kt`). Video frames carry the encoder's `BufferInfo.flags` so the decoder can
find key frames; the SPS/PPS config packet is cached and replayed to each viewer that connects,
along with a forced key frame, so reconnecting shows a picture within a frame or two rather
than waiting for the next 2-second GOP boundary.

**Video is 1280×720 at 20 fps, ~2 Mbit/s** — a nearest-supported size is chosen from the
intersection of both cameras' capabilities so flipping the camera doesn't have to rebuild the
encoder. The camera writes directly into the encoder's input surface, so frames never pass
through the Java heap.

**Rotation** is sent as a hint rather than baked in. The camera encodes in sensor orientation
and tells the viewer how far to turn the picture; the viewer rotates the `SurfaceView` itself.
This avoids an OpenGL pass on the camera phone, which is the one that has to last all night.

**Audio is raw 16 kHz mono PCM**, not compressed. That is ~256 kbit/s, irrelevant next to the
video on a direct link, and it removes both an encoder and a decoder from the path. The viewer
computes loudness from the same PCM it plays, which drives the level meter and the noise alert.

**Staying alive.** The camera unit runs a foreground service (`camera|microphone` type) holding
a partial wake lock, so the stream survives the screen going off. Its on-screen preview is a
second, detachable capture target: when the screen goes away, only the preview target goes with
it. The viewer holds `FLAG_KEEP_SCREEN_ON` and its Dim button drops the backlight to ~1% with a
black overlay, so it can sit on a bedside table without lighting the room — audio and the noise
alert keep running underneath.

## Features

- Live video and sound from the camera phone
- Torch and front/back camera flip, controlled remotely from the viewer
- Sound level meter with an adjustable noise threshold; sustained noise vibrates the viewer
- Mute, dim-screen mode, automatic reconnect if the link drops

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 26). Install
`app/build/outputs/apk/debug/app-debug.apk` on both phones.

## Using it

1. Turn Wi-Fi **on** on both phones (they need not be joined to any network — Wi-Fi Direct only
   needs the radio). Mobile data can be off.
2. On the camera phone: pick *Use this phone as the camera* → **Start monitoring**. Grant camera,
   microphone and nearby-devices permissions.
3. On the viewer: pick *Use this phone to watch*, wait for the camera phone to appear in the
   list, and tap it. Accept the Wi-Fi Direct invitation prompt that Android shows on the camera
   phone.
4. Lay the camera phone down where it can see the cot, plugged into a charger. Encoding video
   all night is a heavy load — expect it to get warm and to drain quickly off-charge.

## Known limitations

- **Range** is ordinary Wi-Fi range — roughly one floor of a house, less through concrete.
- **One viewer at a time.** A second viewer connecting replaces the first, which is what makes
  reconnection after a dropout work cleanly.
- **Wi-Fi Direct pairing is the flaky part**, not the streaming. If discovery hangs, toggling
  Wi-Fi off and on on both phones resets the P2P stack; that is an OS-level quirk, not something
  the app can paper over.
- **No encryption above the link layer.** Wi-Fi Direct itself is WPA2-protected, which is the
  only thing standing between the stream and someone in radio range.
- **The rotation hint is computed once** when streaming starts. Turn the camera phone to the
  orientation you'll leave it in *before* pressing Start.
- If the viewer phone becomes the group owner (it shouldn't, but Wi-Fi Direct can surprise you),
  the app says so and asks you to restart the camera phone first.
