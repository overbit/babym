# Local Baby Monitor v0.6.6

A local-only Android baby monitor for two phones using Wi-Fi Direct. One phone runs **Baby Camera** and the other runs **Parent Monitor**. No account, cloud service, mobile data, or app-level pairing code is used.

## v0.6.6

- **Configurable alert level:** Parent Monitor now has a persistent alert-level slider from 16% to 100%. Lower values accept more sustained-noise events; higher values notify only for stronger events. The percentage is a relative microphone level, not calibrated dB.
- **Screen-off monitoring:** while Parent Monitor is connected, a `connectedDevice` foreground service keeps monitoring user-visible and holds a partial CPU wake lock so the local session can continue processing when the screen is locked/off.
- **Stronger notifications:** noise alerts use a new high-importance Android notification channel with alarm sound, vibration, and public lock-screen visibility. Android notification permission, channel settings, Do Not Disturb, and OEM battery policies still apply.
- **Noise alerts on/off:** Parent Monitor keeps the explicit noise-alert enable/disable control. Baby Camera still analyzes the same PCM microphone stream already used for AAC and reports sustained-noise events after about 0.75 seconds, with a 12-second cooldown.
- **Remote flashlight control remains removed:** the stable video path keeps the normal continuous `TEMPLATE_RECORD` capture request with no flashlight/session changes.
- **Connection reliability:** retains the physically tested v0.5.1 Wi-Fi Direct cleanup/PBC connection path and API 33+ baby listen-state improvement.
- **Rotation-safe monitoring:** rotating Parent Monitor keeps the local socket/audio session alive and reattaches only the video surface.

Noise detection is a convenience feature, not a calibrated sound-pressure meter or medical/safety device.

## Requirements

- Android 8.0 / API 26 or newer.
- Wi-Fi Direct support and Wi-Fi enabled on both devices.
- Location services enabled for Android Wi-Fi Direct discovery.
- Camera and microphone permission on the Baby Camera phone.
- Nearby Wi-Fi permission on Android 13+.
- Notification permission on Android 13+ for lock-screen/system noise notifications.

## Use

1. Install the same APK on both phones.
2. On the phone near the baby, choose **Baby Camera** and tap **Start Monitoring**.
3. On the parent phone, choose **Parent Monitor**, scan, and connect.
4. In the live monitor, enable **Noise alerts** and set **Alert level**. Keep the live monitor session connected; the foreground-monitoring notification indicates that screen-off monitoring is active.

## Runtime

- Local Java sockets over Wi-Fi Direct only.
- Video: H.264 via Android MediaCodec, target 720p / 20 FPS.
- Audio: AAC microphone stream.
- One Baby Camera to one Parent Monitor.

GitHub Actions builds and uploads an installable APK on every push so hardware tests use the exact pushed source.
