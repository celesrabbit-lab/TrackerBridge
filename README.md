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
- Lets you pick the resolution and the frame rate per camera, when the camera offers more than one.
- **New in 0.9.0 (beta):** regular USB webcams now work (tested on a Quest 3S), a safety notice for
  DIY cameras, and experimental support for ESP32 boards that stream over a serial port.
- **New in 0.9.1 (beta):** the Pico build works on real hardware, including a fix for Pico hiding the
  camera's video interface from apps.
- **New in 0.10.0 (beta):** an experimental output for other PC apps (see below).

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

## Safety notice for DIY cameras

The app cannot tell whether a camera is safe to point at your eyes, so the first time you plug in a
camera that isn't an ETVR/Babble board, it asks you to accept a safety notice before it reads it.

Some DIY camera modules come with infrared LEDs that are too strong, or too close to the eye, for
continuous use. The EyeTrackVR and Project Babble communities advise against the **GC0308 IR module**
for eye tracking in particular: users report eye pain and dryness, and long infrared exposure is
linked to lens damage over time. Only point a camera at your eyes if you know its illumination is
safe for that, such as the official EyeTrackVR or Project Babble builds. A camera used for mouth or
face tracking doesn't have this problem.

## USB webcams (experimental)

Most regular USB webcams send video with *isochronous* USB transfers, which Android's Java USB API
can't read. Since 0.8.0, Tracker Bridge reads them with a small native library, and since 0.9.0 this
works on real hardware: tested on a Quest 3S with a Sunplus `1bcf:28c4` webcam at 320x240 and
640x480, MJPEG, with no dropped frames.

- The webcam must support **MJPEG** (most 720p and 1080p webcams do; some very cheap 480p ones only
  send uncompressed video, which isn't supported).
- The app picks the MJPEG resolution closest to 240x240 at the highest frame rate the camera offers
  (for example, 320x240 at 120 fps on the HBVCAM GC0308 module). You can override both from the app,
  see **Resolution and frame rate** below.
- A webcam's own auto-exposure can cap its frame rate: in a dim room the tested webcam settled at
  16.7 fps (60 ms per frame) and went up to 20 fps with more light, even though it had negotiated 30.
- Webcams show an extra status line with the video mode and USB mode, or the technical detail of the
  last error. OpenIris cameras (ETVR, Babble) keep working exactly as before.
- Two webcams on the same USB 2.0 hub may not fit if both ask for a lot of bandwidth. The app tries
  smaller USB packet sizes when the headset refuses one.

If a webcam doesn't work, please open an issue with:
1. A screenshot of the camera's card in Tracker Bridge.
2. The camera's USB descriptors: on a Windows PC, open
   [USB Device Tree Viewer](https://www.uwe-sieber.de/usbtreeview_e.html), select the camera's
   **device entry** (usually "USB Composite Device", one level above "USB Camera"), and copy all the
   text from the right panel.

## Serial boards: ESP32-CAM and other ESP32 without native USB (experimental)

Boards built on the classic ESP32 (ESP32-WROOM-32, like the AI-Thinker ESP32-CAM) have no native
USB: OpenIris sends the video as JPEG frames over a serial port, through the board's USB-serial chip,
and on a PC you would pick a COM port in ETVR or Babble. Since 0.9.0 Tracker Bridge reads those
boards too and republishes them as an MJPEG URL, so the PC side is the same as any other camera.

This has **not been tested on real hardware** — nobody involved owns one of these boards — so please
report what happens, good or bad.

- Supported chips: **CDC-ACM** (any board that shows up as a standard serial port), **CH340/CH341**,
  **CP2102/CP2102N** and **FTDI**.
- The app tries 3,000,000 baud first (what OpenIris uses), then 2,000,000, 1,500,000, 921,600 and
  115,200, and keeps the first one that produces a valid JPEG. The card shows which one worked.
- It never writes to the port, and it leaves DTR and RTS off, so it can't reset the board or put it
  into the bootloader.
- Frame rate and resolution come from the firmware; there's nothing to pick in the app.
- Serial is the bottleneck: 3,000,000 baud is about 300 KB/s, so ~10 KB frames cap out around
  30 fps. A CP2102 tops out at 1,000,000 baud and a CH340 at 2,000,000, which lowers that ceiling.
- If the device turns out not to send any JPEG at any speed, the app stops retrying and says so on
  the card instead of hammering the USB port.

## Pico headsets (beta)

There's a separate APK for Pico headsets, `TrackerBridge-Pico-x.y.z.apk`, in the same release as the
Quest one. A community tester used it on a Pico with an OpenIris camera (the OpenIris-ESPIDF firmware in
UVC mode) for two hours with no problems, and with lower latency than over WiFi. Other Pico models and
cameras haven't been tested, so reports are very welcome: please open an issue with your headset model
and what happened.

Differences from the Quest version:
- It asks for Android's regular **camera** permission instead of Horizon OS's USB cameras permission.
  Android requires it before any app can read a USB camera; the app never uses the headset's own cameras.
- It installs as a separate app (`dev.rabbit.trackerbridge.pico`), so it never conflicts with the Quest version.
- Pico's Android hides the camera's video interface from apps, even though the camera declares it. The
  app claims it directly through the Linux kernel (usbfs) on the same USB connection.
- On Pico, any window that opens, even an invisible one, pulls you out of the VR app you're in. So the
  app doesn't open by itself when you plug a camera in, unless you tick **Open by itself when a camera
  is plugged in**. If you turn on Pico's system option that lets several apps run at once, plugging a
  camera in won't close your game either way. If a replugged camera doesn't come back on its own, open
  Tracker Bridge once.
- The Language button needs Android 13 or newer. On older systems, the app follows the headset's language.

## Install

Download the APK from the [Releases](../../releases) page and install it with SideQuest, or with adb
while the Quest is connected to the PC by cable:

```
adb install -r TrackerBridge-x.y.z.apk
```

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

## Experimental output for other PC apps

Some PC apps don't read a camera URL: they expect a "bridge" to send them the cameras over the local
network. The **Experimental: output for other PC apps** option does that. It's off by default, it doesn't
change the MJPEG URLs, and ETVR and Babble don't need it.

## Resolution and frame rate

Each camera card has a **Resolution** and an **FPS** button, and each one appears only when the
camera actually offers more than one option. They're independent: you can pin the resolution and
leave the frame rate automatic, or the other way round. "Automatic" means the resolution closest to
240x240 and the fastest rate that resolution offers, which is what the app has always done.

The list of frame rates follows the resolution you picked, and if you force a rate the camera
doesn't have, it uses the closest one. The card always shows what ended up applied, like `640x480@30`.

Bigger frames and higher rates use more USB bandwidth, more WiFi and more CPU on the PC, and on a
shared hub they can cause dropped frames or extra latency. Go back to Automatic if anything breaks.
OpenIris boards (ETVR, Babble) usually declare a single mode, so neither button shows up for them:
their frame rate is fixed by the firmware.

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
| One camera freezes for a moment every few seconds | Try another USB cable. In testing, a faulty cable caused pauses of 0.1 to 0.7 seconds while other cameras on the same hub were fine. |
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

Every 10 seconds, each camera logs its frame rate, frame size, the time between frames (median, 95th
percentile and maximum), how long a frame takes to cross the USB cable, and why any frame was dropped.

## Build from source

Requirements: JDK 17 or newer and the Android SDK (platform 35, build-tools 35.0.0, NDK 27.0.12077973
and CMake 3.22.1). Point Gradle to your SDK with `ANDROID_HOME` or a `local.properties` file containing
`sdk.dir=/path/to/Android/Sdk`.

```
./gradlew testQuestDebugUnitTest assembleRelease
```

On Windows, use `gradlew.bat`. This builds both APKs:
`app/build/outputs/apk/quest/release/app-quest-release.apk` and
`app/build/outputs/apk/pico/release/app-pico-release.apk`. They're signed with the debug key, which is
fine for sideloading. The code is shared; each headset's permission and manifest live in
`app/src/quest/` and `app/src/pico/`.

## How it works

- **USB:** reads UVC cameras (MJPEG) with `UsbDeviceConnection`, bypassing Android's camera stack so it
  keeps working in the background. OpenIris boards use a bulk endpoint; the payload parser handles their
  64-byte payloads and reads that merge several payloads. Webcams use isochronous endpoints, which the
  native code in `app/src/main/cpp/` reads by sending URBs to the kernel (usbfs) on the same file
  descriptor. Each request holds 8 packets (1 ms on USB 2.0), so a frame waits at most about 1 ms.
  The handle that native code returns is a pointer, and Android's allocator tags the top byte, so it
  looks negative as a Java `long`: errors travel in a separate field instead.
- **Serial:** boards without native USB are read straight from their USB-serial chip (CDC-ACM, CH34x,
  CP210x or FTDI, each with its own baud-rate registers), looking for `FF D8 FF ... FF D9` in the byte
  stream exactly like ETVR and Babble do on the PC.
- **Permission:** on Horizon OS, USB access to video-class devices requires the runtime permission
  `horizonos.permission.USB_CAMERA`, not `android.permission.CAMERA`.
- **Network:** each camera is an HTTP MJPEG stream (`multipart/x-mixed-replace`) that matches OpenIris'
  WiFi stream. Only the newest frame is sent, so a slow client skips frames instead of adding lag. The
  socket's send queue only holds a couple of frames, so a WiFi hiccup doesn't leave old frames in line.
  Packets are tagged with voice priority (WMM).
- **Background:** a `connectedDevice` foreground service. The wake lock and the low-latency WiFi lock
  are held only while a camera is connected. Since Android 14, the low-latency WiFi mode only applies
  while the app holding the lock is in the foreground; during PCVR, Steam Link keeps WiFi in that mode.
- **Reconnect:** `UsbAttachActivity` has no UI. It receives the USB attach event and wakes the service.

## License

[MIT](LICENSE): you can use, modify and share this project, as long as you keep the copyright notice.

## Credits

Created by **LoadingRabbit** ([@celesrabbit-lab](https://github.com/celesrabbit-lab)) with the help of
**Claude** (Anthropic's AI assistant), who wrote the code and helped debug it on real hardware.

Thanks to the [EyeTrackVR](https://github.com/EyeTrackVR) and [Project Babble](https://github.com/Project-Babble)
communities for OpenIris, ETVR and Babble.

This project is not affiliated with EyeTrackVR, Project Babble or Meta.
