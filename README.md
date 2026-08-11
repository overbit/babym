# Local Baby Monitor v0.5.0

A local-only Android baby monitor for two phones using Wi-Fi Direct. One phone runs **Baby Camera** and the other runs **Parent Monitor**. No account, cloud service, mobile data, or app-level pairing code is used.

## v0.5.0

This release adds two monitoring features and fixes the parent rotation disconnect:

- **Remote torch:** the Parent Monitor can turn the Baby Camera phone's rear-camera torch on or off over the existing local stream socket. The control automatically disables when the selected camera does not expose a flash/torch.
- **Noise alerts:** the Baby Camera reuses the existing microphone PCM stream to detect sustained loud sound locally. A noise event is sent to the Parent Monitor over Wi-Fi Direct, where it shows a visible alert and triggers a short alarm tone and vibration. Detection has a sustained-noise requirement and cooldown to avoid repeated alerts from brief transients.
- **Rotation-safe monitoring:** rotating the Parent Monitor no longer tears down the socket/audio session. The video decoder detaches from the old `SurfaceView` and attaches to the replacement surface while the Wi-Fi Direct and TCP session stay alive.

The existing v0.4.1 display behavior remains:

- Aspect-fit live video with no stretching.
- Status-bar, camera-cutout, and navigation-bar safe insets.
- Portrait-first modern role, discovery, baby-camera, and live-monitor screens.
- Audio mute, reconnect, and fullscreen controls.

## Noise detection defaults

Noise detection is deliberately simple and local. It computes RMS amplitude from the same 48 kHz mono PCM samples already being encoded for the parent audio stream. The default detector requires roughly 750 ms of sustained sound above the configured RMS threshold and applies a 12-second cooldown after an alert.

This is a convenience alert, not a calibrated sound-pressure meter or medical/safety device.

## Requirements

- Android 8.0 / API 26 or newer.
- Wi-Fi Direct support on both devices.
- Wi-Fi enabled on both devices.
- Location services enabled for Android's Wi-Fi Direct discovery flow.
- Camera, microphone, and nearby-device permissions granted to the Baby Camera phone.

## Use

1. Install the same build on both phones.
2. On the phone near the baby, choose **Baby Camera** and tap **Start Monitoring**.
3. On the parent phone, choose **Parent Monitor**, confirm Wi-Fi and Location show as ready, then tap **Scan Nearby Devices**.
4. Select the baby phone and accept any Android Wi-Fi Direct system prompt.
5. The app switches to the dedicated live monitor when the H.264 stream begins.
6. Use **Torch** on the parent screen to control the baby phone's torch when available.
7. Sustained noise on the baby phone produces a visible, audible, and vibration alert on the parent phone.

## Notes

- Runtime streaming uses local Java sockets over the Wi-Fi Direct link.
- Video: H.264 via Android MediaCodec, target 720p / 20 FPS.
- Audio: AAC microphone stream.
- Torch commands and noise events use small packets on the same local TCP session.
- One Baby Camera to one Parent Monitor.
