# Local Baby Monitor v0.6.5

A local-only Android baby monitor for two phones using Wi-Fi Direct. One phone runs **Baby Camera** and the other runs **Parent Monitor**. No account, cloud service, mobile data, or app-level pairing code is used.

## v0.6.5

- **Remote flashlight control removed:** device testing showed that remote torch control was not reliable across the target Camera2 hardware. All parent torch UI, socket commands, protocol messages, Camera2 flash changes, and flashlight permission have been removed.
- **Stable video path restored:** the Baby Camera uses the normal continuous `TEMPLATE_RECORD` capture request without flashlight-related session or request changes.
- **Noise alerts:** Baby Camera analyzes the same PCM microphone samples already used for AAC streaming. Parent Monitor has an explicit noise-alert on/off control and explains that sustained loud sound for about 0.75 seconds triggers an alert followed by a 12-second cooldown.
- **Parent alerts:** noise events show an in-app banner, alarm tone and vibration. Android notifications are also available when permission is granted.
- **Connection reliability:** retains the physically tested v0.5.1 Wi-Fi Direct cleanup/PBC connection path and API 33+ baby listen-state improvement.
- **Rotation-safe monitoring:** rotating Parent Monitor keeps the local socket/audio session alive and reattaches only the video surface.

Noise detection is a convenience feature, not a calibrated sound-pressure meter or medical/safety device.

## Requirements

- Android 8.0 / API 26 or newer.
- Wi-Fi Direct support and Wi-Fi enabled on both devices.
- Location services enabled for Android Wi-Fi Direct discovery.
- Camera and microphone permission on the Baby Camera phone.
- Nearby Wi-Fi permission on Android 13+.
- Notification permission on Android 13+ if system noise notifications are desired.

## Use

1. Install the same APK on both phones.
2. On the phone near the baby, choose **Baby Camera** and tap **Start Monitoring**.
3. On the parent phone, choose **Parent Monitor**, scan, and connect.
4. In the live monitor, use **Noise alerts** to enable or disable local sound detection.

## Runtime

- Local Java sockets over Wi-Fi Direct only.
- Video: H.264 via Android MediaCodec, target 720p / 20 FPS.
- Audio: AAC microphone stream.
- One Baby Camera to one Parent Monitor.

GitHub Actions builds and uploads an installable APK on every push so hardware tests use the exact pushed source.
