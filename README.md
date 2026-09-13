# Tracker Bridge

Turn your **Meta Quest** into a wired "antenna" for DIY face and eye tracking cameras. The ESP32-S3
cameras plug into the headset over USB, and Tracker Bridge forwards their video over WiFi to your PC
for **EyeTrackVR (ETVR)** and **Project Babble**, without using the cameras' 2.4 GHz WiFi.

```
Cameras (USB) → USB hub → Meta Quest (Tracker Bridge) → WiFi → PC (ETVR / Project Babble)
```

*[Leer en español](README.es.md)*

## Features

- Reads OpenIris cameras in wired (UVC) mode directly through Android's USB host API.
- Serves each camera as an MJPEG HTTP stream in the same format OpenIris uses over WiFi, so ETVR and
  Babble need no changes: you just paste a URL.
- Keeps running in the background while you play (tested with Steam Link).
- Silent reconnect: if a camera resets or you replug the hub, it comes back without popping up a
  window. It also starts by itself when you plug the hub in after a reboot.
- Lightweight: no video preview on the headset. With three cameras on a Quest 3S it uses about 6.5%
  of one CPU core and ~33 MB of RAM.

## Tested setup

| Part | Details |
|---|---|
| Headset | Meta Quest 3S (Horizon OS, Android 14) |
| Eye cameras | 2× Seeed XIAO ESP32-S3 Sense, flashed in wired mode with the [EyeTrackVR Firmware Flashing Tool v2.0.0](https://github.com/EyeTrackVR/FirmwareFlashingTool/releases/tag/v2.0.0) → 60 fps |
| Mouth camera | Official Project Babble tracker, firmware 1.3, wired mode → ~30 fps (that firmware's USB limit) |
| Hub | Generic 4-port USB-C OTG hub |
| PC software | EyeTrackApp 0.3.0 BETA 9, Project Babble v1.1.0, Steam Link for PCVR |

Other OpenIris-based UVC cameras should work, but they haven't been tested.

## Requirements

- A Meta Quest with **developer mode** enabled, to sideload the APK.
- Cameras running OpenIris firmware in **wired / USB (UVC)** mode.
- A USB-C OTG hub if you use more than one camera.
- The Quest and the PC on the same network (5 GHz or 6 GHz WiFi recommended).

## Install

Download the APK from the [Releases](../../releases) page and install it with SideQuest, or with adb
while the Quest is connected to the PC by cable:

```
adb install -r TrackerBridge-0.7.0.apk
```

If you installed v0.6.0, uninstall it first. Starting with v0.7.0, releases are signed with a new key,
so the APK can't be installed over v0.6.0. Future updates will install over v0.7.0 normally.

Optional: precompile the app so it uses less CPU:

```
adb shell cmd package compile -m speed -f dev.rabbit.trackerbridge
```

## First-time setup

The app is available in English and Spanish. It follows the Quest's language, and you can switch it
with the **Language** button.

1. On the Quest, open **Library → Unknown Sources → Tracker Bridge**.
2. Allow the **USB cameras** permission. Horizon OS requires it before any app can read a USB camera.
3. Plug in the hub with your cameras and accept the USB prompt for each one. Tick **"use by default"**
   if it's offered, so reconnects are automatic.
4. The app lists every camera with its name, its status and its URL.

## PC setup

| Camera | URL | Where to put it |
|---|---|---|
| Left eye | `http://QUEST-IP:8081/` | ETVR → Left |
| Right eye | `http://QUEST-IP:8082/` | ETVR → Right |
| Mouth / face | `http://QUEST-IP:8083/` | Project Babble → Face Camera Address (IpCameraCapture backend) |

- The app shows the Quest's IP. **Reserve that IP in your router (DHCP reservation)** so the URLs
  don't change.
- Ports are remembered per camera, by USB serial number. Cameras with "Left" or "Right" in their name
  get 8081 and 8082; any other camera gets 8083 and up.
- Quick check: open the URL in a browser on your PC and you should see the video.

## Daily use

Plug the hub into the Quest. That's it: Tracker Bridge starts in the background, even after a reboot,
and you don't need to open it.

## Troubleshooting

| Problem | What to do |
|---|---|
| A camera doesn't show up | Open Tracker Bridge and tap **Scan for cameras**. Check the cable and the hub. |
| The app says the Quest rejected the USB permission without a prompt | The USB cameras permission is missing. Grant it in the app's permissions, or reinstall the app and allow it. |
| No image in ETVR or Babble | Check the Quest's IP (shown in the app) and that both devices are on the same network. Try the URL in a browser. |
| The mouth camera runs at ~30 fps | Expected with Babble firmware 1.3 over USB. It still looks smooth in Babble. |
| High "ms lat" in ETVR | ETVR measures that number on the PC, after the frame arrives, so the Quest doesn't affect it. Try **GUI OFF** or lighter algorithm settings. |

## Wireless adb (debugging with the cameras plugged in)

With the cable connected, switch adb to WiFi:

```
adb tcpip 5555
```

Connect to the Quest over the network:

```
adb connect QUEST-IP:5555
```

Then unplug the cable and plug in the hub. Wireless adb turns off when the Quest reboots.
To see the app's logs:

```
adb logcat -s BridgeService UvcCamera
```

## Build from source

Requirements: JDK 17 or newer and the Android SDK (platform 35, build-tools 35.0.0). Point Gradle to
your SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=/path/to/Android/Sdk`.

```
./gradlew testDebugUnitTest assembleRelease
```

On Windows, use `gradlew.bat`. The APK ends up in `app/build/outputs/apk/release/app-release.apk`,
signed with the debug key, which is fine for sideloading.

## How it works

- **USB:** reads UVC cameras (MJPEG over a bulk endpoint) with `UsbDeviceConnection`, bypassing
  Android's camera stack so it keeps working in the background. The payload parser handles OpenIris'
  64-byte payloads and reads that merge several payloads.
- **Permission:** on Horizon OS, USB access to video-class devices requires the runtime permission
  `horizonos.permission.USB_CAMERA`, not `android.permission.CAMERA`.
- **Network:** each camera is an HTTP MJPEG stream (`multipart/x-mixed-replace`) that matches OpenIris'
  WiFi stream. Only the newest frame is sent, so a slow client skips frames instead of adding lag.
  Packets are tagged with voice priority (WMM).
- **Background:** a `connectedDevice` foreground service. The wake lock and the low-latency WiFi lock
  are held only while a camera is connected.
- **Reconnect:** `UsbAttachActivity` has no UI. It receives the USB attach event and wakes the service.

## License

[MIT](LICENSE): you can use, modify and share this project, as long as you keep the copyright notice.

## Credits

Created by **LoadingRabbit** ([@celesrabbit-lab](https://github.com/celesrabbit-lab)) with the help of
**Claude** (Anthropic's AI assistant), who wrote the code and helped debug it on real hardware.

Thanks to the [EyeTrackVR](https://github.com/EyeTrackVR) and [Project Babble](https://github.com/Project-Babble)
communities for OpenIris, ETVR and Babble.

This project is not affiliated with EyeTrackVR, Project Babble or Meta.
