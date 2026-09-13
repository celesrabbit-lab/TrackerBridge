# Tracker Bridge

App para Meta Quest (probada en Quest 3S) que convierte el visor en la "antena" de tus cámaras de
face/eye tracking. Las cámaras ESP32-S3 se conectan por USB al Quest y la app las reenvía por WiFi a
la PC, para usarlas en **EyeTrackVR (ETVR)** y **Project Babble** sin el WiFi de 2.4 GHz de las cámaras.

```
Cámaras (USB) → hub → Meta Quest (Tracker Bridge) → WiFi → PC (ETVR / Babble)
```

*[Read in English](README.md)*

## Requisitos

- Quest con **modo desarrollador** activado.
- Cámaras con firmware **OpenIris en modo wired (USB)**. Probado con:
  - Ojos: XIAO ESP32-S3 Sense flasheados con EyeTrackVR Firmware Flashing Tool v2.0.0 → 60 fps.
  - Boca: tracker oficial de Babble con firmware 1.3 → 30 fps (límite de ese firmware por USB).
- Hub USB-C OTG (probado con un hub genérico de 4 puertos).
- Quest y PC en la misma red.

## Instalar la APK

Descarga la APK desde [Releases](../../releases) e instálala con SideQuest, o con adb teniendo el Quest
conectado a la PC por cable (y la depuración USB aceptada en el visor):

```
adb install -r TrackerBridge-0.7.0.apk
```

Opcional: precompila la app para que gaste menos CPU:

```
adb shell cmd package compile -m speed -f dev.rabbit.trackerbridge
```

## Primera vez

La app está en inglés y en español. Usa el idioma del Quest, y puedes cambiarlo con el botón
**Idioma / Language**.

1. En el Quest: **Biblioteca → Orígenes desconocidos → Tracker Bridge**.
2. Acepta el permiso de **cámaras USB** (Horizon OS lo exige para leer cámaras USB).
3. Conecta el hub con las cámaras y acepta el aviso USB de cada una, marcando **"usar siempre"**.
4. La app muestra cada cámara con su nombre, su dirección y si está conectada.

## Direcciones en la PC

| Cámara | Dirección | Dónde ponerla |
|---|---|---|
| Ojo izquierdo | `http://IP-del-Quest:8081/` | ETVR → Left |
| Ojo derecho | `http://IP-del-Quest:8082/` | ETVR → Right |
| Boca | `http://IP-del-Quest:8083/` | Babble → Face Camera Address (backend IpCameraCapture) |

- La app muestra la IP del Quest. **Resérvala en el router** (reserva DHCP) para que no cambie.
- Cada cámara conserva su puerto: la app la reconoce por su número de serie. Las que tienen "Left" o
  "Right" en el nombre toman 8081 y 8082; las demás, 8083 en adelante.
- Para comprobar sin ETVR ni Babble: abre la dirección en el navegador de la PC y deberías ver el video.

## Uso diario

Conecta el hub al Quest y listo: Tracker Bridge arranca solo en segundo plano, también después de
reiniciar el Quest. No hace falta abrir la app. Si una cámara se reinicia o se mueve el cable, se
reconecta sola sin abrir ventanas.

## Si algo falla

| Problema | Qué hacer |
|---|---|
| Una cámara no aparece | Abre Tracker Bridge y pulsa **Buscar cámaras**. Revisa el cable y el hub. |
| "El Quest rechazó el permiso USB sin mostrar aviso" | Falta el permiso de cámaras USB. Revísalo en los permisos de la app o reinstálala y acéptalo. |
| ETVR o Babble sin imagen | Revisa que la IP del Quest no haya cambiado (la muestra la app) y que la PC y el Quest estén en la misma red. Prueba la dirección en el navegador. |
| La boca va a 30 fps | Es normal con el firmware 1.3 de Babble por USB. En Babble se ve fluido. |
| Muchos "ms lat" en ETVR | Ese número se mide en la PC y no depende del Quest. Prueba **GUI OFF**, bajar el suavizado o algoritmos más ligeros. |

## adb por WiFi (para diagnosticar con las cámaras conectadas)

Con el cable conectado, pasa adb a WiFi:

```
adb tcpip 5555
```

Conéctate al Quest por la red:

```
adb connect IP-del-Quest:5555
```

Después puedes desconectar el cable y conectar el hub. Se desactiva al reiniciar el Quest.
Para ver los registros de la app:

```
adb logcat -s BridgeService UvcCamera
```

## Compilar desde el código

Requisitos: JDK 17 o más nuevo y Android SDK (plataforma 35 y build-tools 35.0.0). Indica la ruta del
SDK con `ANDROID_HOME` o con un archivo `local.properties` que contenga `sdk.dir=ruta/al/Android/Sdk`.

```
gradlew.bat testDebugUnitTest assembleRelease
```

La APK queda en `app/build/outputs/apk/release/app-release.apk`. Está firmada con la clave de
depuración, que sirve para instalarla por adb o SideQuest.

## Cómo funciona

- Lee las cámaras UVC (MJPEG, endpoint bulk) directamente con la API USB host de Android, sin el
  sistema de cámaras, para seguir funcionando en segundo plano mientras juegas.
- Horizon OS exige el permiso `horizonos.permission.USB_CAMERA` para dar acceso USB a cámaras
  (no `android.permission.CAMERA`).
- Cada cámara se publica como stream MJPEG por HTTP, con el mismo formato que OpenIris usa por WiFi.
- Un servicio en primer plano (`connectedDevice`) mantiene CPU y WiFi despiertos solo mientras hay
  cámaras conectadas. Los paquetes van marcados con prioridad de voz (WMM).
- `UsbAttachActivity` (sin interfaz) recibe el aviso de "USB conectado" para reconectar sin mostrar ventanas.

## Licencia

[MIT](LICENSE): puedes usar, modificar y compartir este proyecto, siempre que mantengas el aviso de copyright.

## Créditos

Creado por **LoadingRabbit** ([@celesrabbit-lab](https://github.com/celesrabbit-lab)) con la ayuda de
**Claude** (el asistente de IA de Anthropic), que escribió el código y ayudó a depurarlo en el hardware real.

Gracias a las comunidades de [EyeTrackVR](https://github.com/EyeTrackVR) y
[Project Babble](https://github.com/Project-Babble) por OpenIris, ETVR y Babble.

Este proyecto no está afiliado a EyeTrackVR, Project Babble ni Meta.
