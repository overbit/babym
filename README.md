# Local Baby Monitor v0.6.0

A local-only Android baby monitor for two phones using Wi-Fi Direct. One phone runs **Baby Camera** and the other runs **Parent Monitor**. No account, cloud service, mobile data, or app-level pairing code is used.

## v0.6.0

This release finishes the remote monitoring controls on top of the tested v0.5.1 Wi-Fi Direct connection flow.

- **Remote torch:** Parent Monitor can turn the Baby Camera phone's supported rear-camera torch on/off over the existing local socket. The UI waits for baby-side state acknowledgement and disables the control when the selected camera has no torch.
- **Noise alerts:** Baby Camera analyzes the same local PCM microphone samples already used for AAC streaming. Sustained loud sound triggers a small local protocol event after a debounce interval and cooldown.
- **Noise alert control:** Parent Monitor can enable/disable noise detection remotely and receives acknowledged state from the baby phone.
- **Parent alerts:** Noise events show an in-app banner, alarm tone and vibration. On Android 13+ the app can also request notification permission and show a local system notification.
- **Connection reliability:** carries forward the working v0.5.1 Wi-Fi Direct state cleanup/PBC connection path and API 33+ baby listen-state improvement.
- **Rotation-safe monitoring:** rotating Parent Monitor keeps the local socket/audio session alive and only reattaches the video surface.

Noise detection is a convenience feature, not a calibrated sound-pressure meter or medical/safety device.

## Requirements

- Android 8.0 / API 26 or newer.
- Wi-Fi Direct support on both devices.
- Wi-Fi enabled on both devices.
- Location services enabled for Android Wi-Fi Direct discovery.
- Camera and microphone permission on the Baby Camera phone.
- Nearby Wi-Fi permission on Android 13+.
- Notification permission on Android 13+ if system noise notifications are desired; in-app alerts still work without it.

## Use

1. Install the same APK on both phones.
2. On the phone near the baby, choose **Baby Camera** and tap **Start Monitoring**.
3. On the parent phone, choose **Parent Monitor**, then scan and connect.
4. In the live monitor, use **Torch** for remote light control and **Noise alerts** to enable/disable detection.

## Runtime

- Local Java sockets over Wi-Fi Direct only.
- Video: H.264 via Android MediaCodec, target 720p / 20 FPS.
- Audio: AAC microphone stream.
- One Baby Camera to one Parent Monitor.
