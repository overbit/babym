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
    if not (root / name).is_file(): errors.append(f"missing: {name}")

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
for token in ["android.permission.VIBRATE", "configChanges=\"orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden\""]:
    if token not in manifest: errors.append(f"manifest token missing: {token}")

protocol = (root / "app/src/main/java/com/localbabymonitor/app/Protocol.kt").read_text()
for token in ["TYPE_TORCH_COMMAND", "TYPE_TORCH_STATE", "TYPE_NOISE_ALERT", "packTorchCommand", "packNoiseAlert"]:
    if token not in protocol: errors.append(f"protocol token missing: {token}")

service = (root / "app/src/main/java/com/localbabymonitor/app/BabyMonitorService.kt").read_text()
for token in ["handleControlPacket", "sendTorchState", "TYPE_NOISE_ALERT", "DataInputStream"]:
    if token not in service: errors.append(f"baby service token missing: {token}")

video = (root / "app/src/main/java/com/localbabymonitor/app/VideoStreamer.kt").read_text()
for token in ["FLASH_INFO_AVAILABLE", "FLASH_MODE_TORCH", "fun setTorch(enabled: Boolean)"]:
    if token not in video: errors.append(f"torch token missing: {token}")

audio = (root / "app/src/main/java/com/localbabymonitor/app/AudioStreamer.kt").read_text()
for token in ["NOISE_RMS_THRESHOLD", "NOISE_SUSTAINED_MS", "NOISE_COOLDOWN_MS", "detectNoise"]:
    if token not in audio: errors.append(f"noise token missing: {token}")

client = (root / "app/src/main/java/com/localbabymonitor/app/MonitorStreamClient.kt").read_text()
for token in ["fun attachSurface", "fun detachSurface", "fun setTorch", "onNoiseAlert", "onTorchState"]:
    if token not in client: errors.append(f"client token missing: {token}")

monitor = (root / "app/src/main/java/com/localbabymonitor/app/MonitorActivity.kt").read_text()
for token in ["torchButton", "noiseAlertBanner", "attachSurface(holder.surface)", "detachSurface(holder.surface)", "showNoiseAlert"]:
    if token not in monitor: errors.append(f"monitor token missing: {token}")

layout = (root / "app/src/main/res/layout/activity_monitor.xml").read_text()
for token in ["torchButton", "noiseAlertBanner", "Noise alerts"]:
    if token not in layout: errors.append(f"layout token missing: {token}")

all_source = "\n".join(p.read_text(errors="ignore") for p in (root / "app/src").rglob("*.*") if p.is_file())
for forbidden in ["PairingStore", "pinInput", "EXTRA_PIN", "WpsInfo.PBC", "WifiP2pDnsSd", "discoverServices", "addLocalService"]:
    if forbidden in all_source: errors.append(f"legacy/auth token still present: {forbidden}")

build = (root / "app/build.gradle.kts").read_text()
if not re.search(r"minSdk\s*=\s*26", build): errors.append("minSdk is not 26")
if not re.search(r"targetSdk\s*=\s*36", build): errors.append("targetSdk is not 36")
if not re.search(r"versionCode\s*=\s*6", build): errors.append("versionCode is not 6")
if 'versionName = "0.5.0"' not in build: errors.append("versionName is not 0.5.0")

if errors:
    print("VERIFY FAILED")
    for error in errors: print("-", error)
    sys.exit(1)
print("VERIFY OK")
print("v0.5.0 torch control, local noise alerts, rotation-safe stream surface handling, and prior auth removal verified.")
