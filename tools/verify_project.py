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
    "app/src/main/java/com/localbabymonitor/app/MonitorActivity.kt",
    "app/src/main/java/com/localbabymonitor/app/MonitorStreamClient.kt",
    "app/src/main/java/com/localbabymonitor/app/AudioStreamer.kt",
    "app/src/main/java/com/localbabymonitor/app/VideoStreamer.kt",
    "app/src/main/java/com/localbabymonitor/app/Protocol.kt",
    "app/src/main/res/layout/activity_monitor.xml",
    "app/src/main/res/drawable/bg_noise_alert.xml",
]
for name in required:
    if not (root / name).is_file():
        errors.append(f"missing: {name}")

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
for token in [
    "android.permission.VIBRATE",
    "android.permission.POST_NOTIFICATIONS",
    "configChanges=\"orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden\"",
]:
    if token not in manifest:
        errors.append(f"manifest token missing: {token}")

protocol = (root / "app/src/main/java/com/localbabymonitor/app/Protocol.kt").read_text()
for token in ["TYPE_NOISE_ALERT", "TYPE_NOISE_CONTROL", "TYPE_NOISE_STATE", "packNoiseAlert"]:
    if token not in protocol:
        errors.append(f"protocol token missing: {token}")

service = (root / "app/src/main/java/com/localbabymonitor/app/BabyMonitorService.kt").read_text()
for token in ["handleControlPacket", "TYPE_NOISE_ALERT", "TYPE_NOISE_CONTROL", "DataInputStream"]:
    if token not in service:
        errors.append(f"baby service token missing: {token}")

video = (root / "app/src/main/java/com/localbabymonitor/app/VideoStreamer.kt").read_text()
for token in ["TEMPLATE_RECORD", "setRepeatingRequest"]:
    if token not in video:
        errors.append(f"video token missing: {token}")

audio = (root / "app/src/main/java/com/localbabymonitor/app/AudioStreamer.kt").read_text()
for token in ["NOISE_RMS_THRESHOLD", "NOISE_SUSTAINED_MS", "NOISE_COOLDOWN_MS", "detectNoise"]:
    if token not in audio:
        errors.append(f"noise token missing: {token}")

client = (root / "app/src/main/java/com/localbabymonitor/app/MonitorStreamClient.kt").read_text()
for token in ["fun attachSurface", "fun detachSurface", "fun setNoiseAlerts", "onNoiseAlert", "onNoiseState"]:
    if token not in client:
        errors.append(f"client token missing: {token}")

monitor = (root / "app/src/main/java/com/localbabymonitor/app/MonitorActivity.kt").read_text()
for token in ["noiseButton", "noiseAlertBanner", "attachSurface(holder.surface)", "detachSurface(holder.surface)", "showNoiseAlert"]:
    if token not in monitor:
        errors.append(f"monitor token missing: {token}")

layout = (root / "app/src/main/res/layout/activity_monitor.xml").read_text()
for token in ["noiseButton", "noiseAlertBanner", "Noise alerts"]:
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
    "TYPE_TORCH_COMMAND",
    "TYPE_TORCH_STATE",
    "packTorchCommand",
    "packTorchState",
    "setTorch(",
    "torchButton",
    "FLASH_MODE_TORCH",
    "android.permission.FLASHLIGHT",
]:
    if forbidden in all_source:
        errors.append(f"removed/legacy token still present: {forbidden}")

build = (root / "app/build.gradle.kts").read_text()
if not re.search(r"minSdk\s*=\s*26", build):
    errors.append("minSdk is not 26")
if not re.search(r"targetSdk\s*=\s*36", build):
    errors.append("targetSdk is not 36")
if not re.search(r"versionCode\s*=\s*13", build):
    errors.append("versionCode is not 13")
if 'versionName = "0.6.5"' not in build:
    errors.append("versionName is not 0.6.5")

if errors:
    print("VERIFY FAILED")
    for error in errors:
        print("-", error)
    sys.exit(1)

print("VERIFY OK")
print("v0.6.5 noise alerts, rotation-safe streaming, Wi-Fi Direct recovery, and complete remote torch removal verified.")
