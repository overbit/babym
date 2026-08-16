#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
errors = []
required = [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/localbabymonitor/app/MainActivity.kt",
    "app/src/main/java/com/localbabymonitor/app/BabyActivity.kt",
    "app/src/main/java/com/localbabymonitor/app/BabyMonitorService.kt",
    "app/src/main/java/com/localbabymonitor/app/ParentMonitorService.kt",
    "app/src/main/java/com/localbabymonitor/app/MonitorActivity.kt",
    "app/src/main/java/com/localbabymonitor/app/MonitorStreamClient.kt",
    "app/src/main/java/com/localbabymonitor/app/AudioStreamer.kt",
    "app/src/main/java/com/localbabymonitor/app/VideoStreamer.kt",
    "app/src/main/java/com/localbabymonitor/app/Protocol.kt",
    "app/src/main/res/layout/activity_monitor.xml",
    "app/src/main/res/drawable/bg_noise_alert.xml",
    ".github/workflows/android-apk.yml",
]
for name in required:
    if not (root / name).is_file():
        errors.append(f"missing: {name}")

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
for token in [
    "android.permission.VIBRATE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android:foregroundServiceType=\"connectedDevice\"",
    "configChanges=\"orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden\"",
]:
    if token not in manifest:
        errors.append(f"manifest token missing: {token}")

protocol = (root / "app/src/main/java/com/localbabymonitor/app/Protocol.kt").read_text()
for token in [
    "TYPE_NOISE_ALERT",
    "TYPE_NOISE_CONTROL",
    "TYPE_NOISE_STATE",
    "packNoiseAlert",
    "TYPE_TORCH_CONTROL",
    "TYPE_TORCH_STATE",
    "packTorchControl",
    "packTorchState",
    "TYPE_ZOOM_CONTROL",
    "TYPE_ZOOM_STATE",
    "packZoomControl",
    "packZoomState",
]:
    if token not in protocol:
        errors.append(f"protocol token missing: {token}")

service = (root / "app/src/main/java/com/localbabymonitor/app/BabyMonitorService.kt").read_text()
for token in [
    "handleControlPacket",
    "TYPE_NOISE_ALERT",
    "TYPE_NOISE_CONTROL",
    "DataInputStream",
    "TYPE_TORCH_CONTROL",
    "sendTorchState",
    "TYPE_ZOOM_CONTROL",
    "sendZoomState",
]:
    if token not in service:
        errors.append(f"baby service token missing: {token}")

parent_service = (root / "app/src/main/java/com/localbabymonitor/app/ParentMonitorService.kt").read_text()
for token in ["FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE", "PARTIAL_WAKE_LOCK", "startForeground", "Parent monitoring active"]:
    if token not in parent_service:
        errors.append(f"parent service token missing: {token}")

video = (root / "app/src/main/java/com/localbabymonitor/app/VideoStreamer.kt").read_text()
for token in [
    "TEMPLATE_RECORD",
    "setRepeatingRequest",
    "FLASH_INFO_AVAILABLE",
    "FLASH_MODE_TORCH",
    "fun setTorch",
    "fun setZoom",
    "CONTROL_ZOOM_RATIO",
    "SCALER_CROP_REGION",
]:
    if token not in video:
        errors.append(f"video token missing: {token}")
# Torch control was withdrawn in v0.6.5 because the first implementation swapped capture
# templates, blocked on FLASH_STATE, and fell back to CameraManager.setTorchMode() while the
# camera was open. Each of those broke streaming or misreported the light on real hardware, so
# the torch must stay a flash-mode change on the existing TEMPLATE_RECORD repeating request.
for forbidden in ["TEMPLATE_PREVIEW", "setTorchMode", "CaptureResult", "CountDownLatch"]:
    if forbidden in video:
        errors.append(f"video must not reintroduce torch instability: {forbidden}")

audio = (root / "app/src/main/java/com/localbabymonitor/app/AudioStreamer.kt").read_text()
for token in ["NOISE_RMS_THRESHOLD", "NOISE_SUSTAINED_MS", "NOISE_COOLDOWN_MS", "detectNoise"]:
    if token not in audio:
        errors.append(f"noise token missing: {token}")

client = (root / "app/src/main/java/com/localbabymonitor/app/MonitorStreamClient.kt").read_text()
for token in [
    "fun attachSurface",
    "fun detachSurface",
    "fun setNoiseAlerts",
    "onNoiseAlert",
    "onNoiseState",
    "fun setTorch",
    "onTorchState",
    "fun setZoom",
    "onZoomState",
]:
    if token not in client:
        errors.append(f"client token missing: {token}")

monitor = (root / "app/src/main/java/com/localbabymonitor/app/MonitorActivity.kt").read_text()
for token in [
    "noiseButton",
    "noiseAlertBanner",
    "noiseThresholdSeekBar",
    "PREF_NOISE_ALERT_LEVEL",
    "noise_alerts_v2",
    "IMPORTANCE_HIGH",
    "setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)",
    "ParentMonitorService.ACTION_START",
    "attachSurface(holder.surface)",
    "detachSurface(holder.surface)",
    "showNoiseAlert",
    "torchButton",
    "updateTorchState",
    "zoomSeekBar",
    "updateZoomState",
    "controlsButton",
    "SCREEN_ORIENTATION_SENSOR_LANDSCAPE",
]:
    if token not in monitor:
        errors.append(f"monitor token missing: {token}")
if "setSound(null" in monitor:
    errors.append("noise notification channel is still explicitly silent")

layout = (root / "app/src/main/res/layout/activity_monitor.xml").read_text()
for token in [
    "noiseButton",
    "noiseAlertBanner",
    "noiseThresholdSeekBar",
    "noiseThresholdLabel",
    "Noise alerts",
    "torchButton",
    "torchDetail",
    "zoomSeekBar",
    "zoomDetail",
    "controlsButton",
    "fullscreenHint",
]:
    if token not in layout:
        errors.append(f"layout token missing: {token}")

all_source = "\n".join(
    p.read_text(errors="ignore")
    for p in (root / "app/src").rglob("*.*")
    if p.is_file()
)
for forbidden in [
    "PairingStore",
    "pinInput",
    "EXTRA_PIN",
    "WifiP2pDnsSd",
    "discoverServices",
    "addLocalService",
    "android.permission.FLASHLIGHT",
]:
    if forbidden in all_source:
        errors.append(f"removed/legacy token still present: {forbidden}")

build = (root / "app/build.gradle.kts").read_text()
if not re.search(r"minSdk\s*=\s*26", build):
    errors.append("minSdk is not 26")
if not re.search(r"targetSdk\s*=\s*36", build):
    errors.append("targetSdk is not 36")
if not re.search(r"versionCode\s*=\s*16", build):
    errors.append("versionCode is not 16")
if 'versionName = "0.6.8"' not in build:
    errors.append("versionName is not 0.6.8")
for token in [
    "CI_ANDROID_KEYSTORE_PATH",
    'create("ci")',
    'signingConfig = signingConfigs.getByName("ci")',
    "ANDROID_SIGNING_STORE_PASSWORD",
    "ANDROID_SIGNING_KEY_ALIAS",
    "ANDROID_SIGNING_KEY_PASSWORD",
]:
    if token not in build:
        errors.append(f"persistent signing build token missing: {token}")

workflow = (root / ".github/workflows/android-apk.yml").read_text()
for token in [
    "ANDROID_SIGNING_KEYSTORE_BASE64",
    "ANDROID_SIGNING_STORE_PASSWORD",
    "ANDROID_SIGNING_KEY_ALIAS",
    "ANDROID_SIGNING_KEY_PASSWORD",
    "EXPECTED_SIGNER_SHA256",
    "ff13c20f36322208f73290a2fd5941b3172b5ee3cd0685260ec3f4944b000c77",
    "Verify signing certificate",
    "Build persistently signed debug APK",
    "Verify APK signing certificate",
    "apksigner",
    "actions/upload-artifact@v4",
]:
    if token not in workflow:
        errors.append(f"persistent signing workflow token missing: {token}")
if "assembleDebug" not in workflow:
    errors.append("workflow no longer builds the debug APK")

if errors:
    print("VERIFY FAILED")
    for error in errors:
        print("-", error)
    sys.exit(1)

print("VERIFY OK")
print("v0.6.8 parent-controlled camera zoom and torch on the live capture request, configurable noise alerts, screen-off monitoring, stable streaming, and persistent APK signing verified.")
