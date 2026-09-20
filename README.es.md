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

## Aviso de seguridad para cámaras DIY

La app no puede saber si una cámara es segura para apuntarla a los ojos, así que la primera vez que
conectas una que no es una placa de ETVR/Babble, te pide aceptar un aviso antes de leerla.

Algunos módulos DIY traen LEDs infrarrojos demasiado fuertes, o demasiado cerca del ojo, para usarlos
todo el tiempo. Las comunidades de EyeTrackVR y Project Babble desaconsejan en especial el **módulo IR
GC0308** para eye tracking: hay reportes de dolor y sequedad en los ojos, y la exposición prolongada al
infrarrojo se asocia con daño en el cristalino con el tiempo. Apunta una cámara a tus ojos solo si sabes
que su iluminación es segura para eso, por ejemplo la de los kits oficiales de EyeTrackVR o Project
Babble. Una cámara para boca o cara no tiene este problema.

## Webcams USB (experimental)

La mayoría de las webcams USB comunes mandan el video con transferencias USB *isócronas*, que la API
USB de Android en Java no puede leer. Desde la 0.8.0, Tracker Bridge las lee con una pequeña librería
nativa, y desde la 0.9.0 funciona en hardware real: probado en un Quest 3S con una webcam Sunplus
`1bcf:28c4` a 320x240 y 640x480, en MJPEG y sin perder cuadros.

- La webcam tiene que soportar **MJPEG** (casi todas las de 720p y 1080p lo hacen; algunas muy baratas
  de 480p solo mandan video sin comprimir, y esas no funcionan).
- La app elige la resolución MJPEG más parecida a 240x240, con los fps más altos que ofrezca la cámara
  (por ejemplo, 320x240 a 120 fps en el módulo HBVCAM GC0308). Puedes cambiar las dos cosas desde la
  app: mira **Resolución y fps** más abajo.
- El autoexposición de la webcam puede limitar los fps: con poca luz, la webcam de prueba se quedó en
  16,7 fps (60 ms por cuadro) y subió a 20 fps con más luz, aunque había negociado 30.
- Las webcams muestran una línea extra de estado con el modo de video y el modo USB, o el detalle
  técnico del último error. Las cámaras OpenIris (ETVR, Babble) siguen funcionando exactamente igual.
- Dos webcams en el mismo hub USB 2.0 pueden no caber si las dos piden mucho ancho de banda. Si el visor
  rechaza un tamaño de paquete USB, la app prueba con uno más chico.

Si una webcam no funciona, abre un issue con:
1. Una captura de la tarjeta de la cámara en Tracker Bridge.
2. Los descriptores USB de la cámara: en una PC con Windows, abre
   [USB Device Tree Viewer](https://www.uwe-sieber.de/usbtreeview_e.html), selecciona la **entrada del
   dispositivo** (normalmente "USB Composite Device", un nivel arriba de "USB Camera") y copia todo el
   texto del panel derecho.

## Placas de serie: ESP32-CAM y otros ESP32 sin USB nativo (experimental)

Las placas con el ESP32 clásico (ESP32-WROOM-32, como la ESP32-CAM de AI-Thinker) no tienen USB
nativo: OpenIris manda el video como cuadros JPEG por un puerto serie, a través del chip USB-serie de
la placa, y en la PC elegirías un puerto COM en ETVR o Babble. Desde la 0.9.0, Tracker Bridge también
lee esas placas y las publica como una dirección MJPEG, así que del lado de la PC es igual que
cualquier otra cámara.

Esto **no se ha probado en hardware real** (nadie del proyecto tiene una de esas placas), así que
cuenta qué pasó, salga bien o mal.

- Chips soportados: **CDC-ACM** (cualquier placa que aparezca como puerto serie estándar),
  **CH340/CH341**, **CP2102/CP2102N** y **FTDI**.
- La app prueba primero 3.000.000 baudios (los que usa OpenIris), y después 2.000.000, 1.500.000,
  921.600 y 115.200, y se queda con la primera velocidad que dé un JPEG válido. La tarjeta muestra
  cuál funcionó.
- Nunca le escribe nada a la placa, y deja DTR y RTS apagados, así que no puede reiniciarla ni
  meterla en el gestor de arranque.
- Los fps y la resolución los fija el firmware; no hay nada que elegir en la app.
- El cuello de botella es el cable: 3.000.000 baudios son unos 300 KB/s, así que con cuadros de
  10 KB el techo ronda los 30 fps. Un CP2102 llega a 1.000.000 baudios y un CH340 a 2.000.000, lo
  que baja ese techo.
- Si el aparato no manda ningún JPEG con ninguna velocidad, la app deja de insistir y lo dice en la
  tarjeta, en vez de estar molestando al USB.

## Visores Pico (experimental)

Hay una APK aparte para visores Pico, `TrackerBridge-Pico-x.y.z.apk`, en la página de Releases.
Todavía no se ha probado en un Pico real, así que los reportes son muy bienvenidos: abre un issue con
el modelo de tu visor y lo que pasó.

Diferencias con la versión de Quest:
- Pide el permiso de **cámara** normal de Android en vez del permiso de cámaras USB de Horizon OS.
  Android lo exige antes de que cualquier app lea una cámara USB; la app nunca usa las cámaras del visor.
- Se instala como una app separada (`dev.rabbit.trackerbridge.pico`), así que no choca con la versión de Quest.
- El botón de idioma necesita Android 13 o más nuevo. En sistemas anteriores, la app usa el idioma del visor.

## Instalar la APK

Descarga la APK desde [Releases](../../releases) e instálala con SideQuest, o con adb teniendo el Quest
conectado a la PC por cable (y la depuración USB aceptada en el visor):

```
adb install -r TrackerBridge-x.y.z.apk
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

## Resolución y fps

Cada tarjeta de cámara tiene un botón de **Resolución** y otro de **FPS**, y cada uno aparece solo si
la cámara ofrece más de una opción. Son independientes: puedes fijar la resolución y dejar los fps en
automático, o al revés. "Automático" es la resolución más parecida a 240x240 y la velocidad más alta
que ofrezca esa resolución, que es lo que la app hizo siempre.

La lista de fps sigue a la resolución elegida, y si fuerzas una velocidad que la cámara no tiene, usa
la más parecida. La tarjeta siempre muestra lo que quedó aplicado, tipo `640x480@30`.

Los cuadros más grandes y más fps gastan más ancho de banda USB, más WiFi y más CPU en la PC, y en un
hub compartido pueden causar cuadros perdidos o más latencia. Si algo se rompe, vuelve a Automático.
Las placas OpenIris (ETVR, Babble) suelen declarar un solo modo, así que no muestran ninguno de los
dos botones: sus fps los fija el firmware.

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
| Una cámara se congela un momento cada pocos segundos | Prueba con otro cable USB. En las pruebas, un cable defectuoso causó pausas de 0,1 a 0,7 segundos mientras las otras cámaras del mismo hub iban bien. |
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

Cada 10 segundos, cada cámara anota sus fps, el peso de los cuadros, el tiempo entre cuadros (mediana,
percentil 95 y máximo), cuánto tarda un cuadro en pasar por el cable USB y por qué se descartó algún cuadro.

## Compilar desde el código

Requisitos: JDK 17 o más nuevo y Android SDK (plataforma 35, build-tools 35.0.0, NDK 27.0.12077973 y
CMake 3.22.1). Indica la ruta del SDK con `ANDROID_HOME` o con un archivo `local.properties` que contenga
`sdk.dir=ruta/al/Android/Sdk`.

```
gradlew.bat testQuestDebugUnitTest assembleRelease
```

Esto genera las dos APK: `app/build/outputs/apk/quest/release/app-quest-release.apk` y
`app/build/outputs/apk/pico/release/app-pico-release.apk`. Quedan firmadas con la clave de depuración,
que sirve para instalarlas por adb o SideQuest. El código es común; el permiso y el manifiesto de cada
visor están en `app/src/quest/` y `app/src/pico/`.

## Cómo funciona

- Lee las cámaras UVC (MJPEG) directamente con la API USB host de Android, sin el sistema de cámaras,
  para seguir funcionando en segundo plano mientras juegas. Las placas OpenIris usan un endpoint bulk;
  las webcams usan endpoints isócronos, que lee el código nativo de `app/src/main/cpp/` enviando pedidos
  (URB) al kernel (usbfs). Cada pedido junta 8 paquetes (1 ms en USB 2.0), así que un cuadro espera 1 ms
  como mucho. Lo que devuelve ese código es un puntero, y el asignador de memoria de Android le pone una
  etiqueta en los bits altos: como `long` de Java se ve negativo, así que el error viaja aparte.
- Las placas sin USB nativo se leen directo de su chip USB-serie (CDC-ACM, CH34x, CP210x o FTDI, cada
  uno con sus registros de velocidad), buscando `FF D8 FF ... FF D9` en el flujo de bytes, igual que
  hacen ETVR y Babble en la PC.
- Horizon OS exige el permiso `horizonos.permission.USB_CAMERA` para dar acceso USB a cámaras
  (no `android.permission.CAMERA`).
- Cada cámara se publica como stream MJPEG por HTTP, con el mismo formato que OpenIris usa por WiFi.
  Solo se manda el cuadro más nuevo, y la cola de envío guarda apenas un par de cuadros: si el WiFi se
  traba un momento, no quedan cuadros viejos esperando.
- Un servicio en primer plano (`connectedDevice`) mantiene CPU y WiFi despiertos solo mientras hay
  cámaras conectadas. Los paquetes van marcados con prioridad de voz (WMM). Desde Android 14, el modo
  WiFi de baja latencia solo funciona mientras la app que lo pide está en primer plano; mientras juegas
  en PCVR, Steam Link lo mantiene activo.
- `UsbAttachActivity` (sin interfaz) recibe el aviso de "USB conectado" para reconectar sin mostrar ventanas.

## Licencia

[MIT](LICENSE): puedes usar, modificar y compartir este proyecto, siempre que mantengas el aviso de copyright.

## Créditos

Creado por **LoadingRabbit** ([@celesrabbit-lab](https://github.com/celesrabbit-lab)) con la ayuda de
**Claude** (el asistente de IA de Anthropic), que escribió el código y ayudó a depurarlo en el hardware real.

Gracias a las comunidades de [EyeTrackVR](https://github.com/EyeTrackVR) y
[Project Babble](https://github.com/Project-Babble) por OpenIris, ETVR y Babble.

Este proyecto no está afiliado a EyeTrackVR, Project Babble ni Meta.
