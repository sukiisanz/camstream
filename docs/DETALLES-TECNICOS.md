# CamStream: detalles técnicos

Material movido del README para quien quiera entender o tocar el código.

## Arquitectura

```
┌─────────────────┐   H.264 sobre RTSP/TCP    ┌──────────────────────────┐
│  Teléfono       │  ────────────────────────▶ │  PC (Fedora o Windows)   │
│  (app CamStream)│   WiFi  o  USB (adb)       │  camstream-receiver      │
│                 │                            │     │                    │
│  cámara → H.264 │                            │     ▼                    │
│  servidor RTSP  │                            │  cámara virtual          │
│  anuncio mDNS   │                            │  Linux: /dev/video10     │
└─────────────────┘                            │  Windows: OBS VirtualCam │
                                               │     ▼                    │
                                               │  Zoom / Meet / Teams     │
                                               └──────────────────────────┘
```

1. La cámara del teléfono graba y el codificador hardware comprime a
   H.264 (`MediaCodec`, entrada por Surface, cero copias por CPU).
2. Un servidor RTSP embebido (sockets puros, RTP intercalado por TCP)
   sirve el stream en `rtsp://<ip>:8554/cam`.
3. El teléfono se anuncia por mDNS como `_camstream._tcp` (NsdManager).
4. El receptor descubre el servicio (zeroconf o avahi-browse), recibe el
   stream y lo vuelca en la cámara virtual: v4l2loopback en Linux
   (GStreamer o ffmpeg) o el driver de OBS en Windows (ffmpeg →
   pyvirtualcam).
5. Si el WiFi no aparece en N segundos, el receptor hace
   `adb forward tcp:8554 tcp:8554` y conecta por `127.0.0.1` (USB).

## Estructura del proyecto

```
camstream/
├── android/                  # App Android (Kotlin, Gradle)
│   └── app/src/main/java/com/camstream/app/
│       ├── MainActivity.kt        # UI: preview + estado + botones
│       ├── StreamService.kt       # servicio en primer plano (une todo)
│       ├── CameraEngine.kt        # cámara (Camera2, doble Surface)
│       ├── encoder/H264Encoder.kt # codificador hardware H.264
│       ├── encoder/Nal.kt         # troceado de NALs Annex-B
│       ├── rtsp/RtspServer.kt     # servidor RTSP embebido (TCP)
│       ├── rtsp/RtpH264Packetizer.kt  # RTP según RFC 6184
│       └── net/NsdAnnouncer.kt    # anuncio mDNS (_camstream._tcp)
└── receiver/                 # Receptor multiplataforma
    ├── camstream_receiver.py      # daemon Python (autodescubre + pipeline)
    ├── camstream-receiver.service # unidad systemd (de usuario, Linux)
    ├── install.sh                 # instalador Fedora (dnf, v4l2loopback…)
    ├── uninstall.sh
    ├── setup-windows.ps1          # preparación de Windows en un paso
    ├── requirements.txt           # deps Python (Windows / pyvirtualcam)
    ├── build-windows.ps1          # genera camstream-receiver.exe
    ├── build-linux.sh             # genera el binario Linux
    └── test/loopback-test.sh      # prueba la cámara virtual sin teléfono
```

## Compilar a mano

App Android (también la compila GitHub Actions en cada cambio):

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Receptor como ejecutable único (PyInstaller):

```bash
receiver/build-linux.sh        # Linux
receiver\build-windows.ps1     # Windows
```

O como script: `pip install -r receiver/requirements.txt` y
`python receiver/camstream_receiver.py`.

Lo que hace `receiver/install.sh` en Fedora:

- Instala GStreamer, openh264, `avahi-tools`, `android-tools` y
  `v4l2loopback` (habilitando RPM Fusion free si hace falta).
- Configura `/dev/video10` permanente con nombre **CamStream** y
  `exclusive_caps=1` (necesario para Chrome/Zoom).
- Copia el daemon a `/usr/local/bin` y la unidad systemd de usuario.

`receiver/test/loopback-test.sh` emite barras de colores en
`/dev/video10` para probar la mitad Linux sin teléfono.

## Opciones del receptor

```
camstream-receiver --prefer usb          # prueba USB primero
camstream-receiver --url rtsp://192.168.1.50:8554/cam   # IP a mano
camstream-receiver --latency 100         # buffer extra si hay cortes
camstream-receiver --backend ffmpeg      # forzar ffmpeg en vez de GStreamer
camstream-receiver --backend pyvirtualcam
camstream-receiver --device /dev/video9  # otro nodo v4l2loopback
```

Como servicio en Linux:

```bash
systemctl --user enable --now camstream-receiver
journalctl --user -fu camstream-receiver
```

## Latencia

Objetivo: < 300 ms en WiFi local. Las piezas que lo hacen posible:

| Pieza | Ajuste |
|---|---|
| Codificador del teléfono | CBR, prioridad tiempo-real, `KEY_LOW_LATENCY`, keyframe cada 1 s |
| Servidor RTSP | cola corta por cliente: si el receptor se atasca, descarta y re-sincroniza en el siguiente keyframe (nunca acumula retraso) |
| Red | TCP con `TCP_NODELAY`; en WiFi local la retransmisión no se nota |
| Receptor | `rtspsrc latency=0 drop-on-latency=true` y `v4l2sink sync=false` |

Si hay video "a saltos": `--latency 100` (añade ~100 ms pero suaviza).
Con WiFi muy congestionado, el cable USB es la opción más estable.

## Decisiones de diseño

- **RTP sobre TCP intercalado, no UDP.** `adb forward` (el modo USB) solo
  reenvía TCP, así que UDP nunca funcionaría por cable. TCP en ambos modos
  simplifica todo y en red local no añade latencia apreciable.
- **Camera2 en vez de CameraX.** Camera2 envía el mismo fotograma a dos
  Surfaces a la vez (pantalla + codificador) sin copiar píxeles por CPU.
  Con CameraX habría que pasar por `ImageAnalysis` y convertir formatos a
  mano: más código y más latencia.
- **Sockets puros en vez de NanoHTTPD/Netty.** RTSP no es HTTP; NanoHTTPD
  no sirve aquí, y Netty es un cañón para matar moscas. El servidor RTSP
  completo son ~300 líneas de Kotlin con `ServerSocket`.
- **Driver de OBS en Windows.** Crear una cámara virtual en Windows exige
  un driver del sistema firmado; firmar drivers cuesta dinero. El driver
  de OBS es gratuito, firmado y aceptado por todas las apps de reuniones —
  se usa solo el driver, no el programa.
- **Sin fallback MJPEG (por ahora).** Todos los Android 12+ codifican
  H.264 por hardware y openh264/ffmpeg lo decodifican gratis. Si hiciera
  falta, el sitio para añadirlo es `H264Encoder` + una `m=` adicional en
  el SDP.

## Solución de problemas (ampliada)

| Síntoma | Causa probable / arreglo |
|---|---|
| El receptor no encuentra el teléfono por WiFi | mDNS bloqueado: en Fedora `sudo firewall-cmd --add-service=mdns --permanent && sudo firewall-cmd --reload`; en Windows, permitir el firewall. O redes distintas / aislamiento AP del router. |
| `adb get-state` no dice `device` | Acepta el diálogo "¿Permitir depuración USB?" en el teléfono. |
| `/dev/video10` no existe | `sudo modprobe v4l2loopback video_nr=10 card_label=CamStream exclusive_caps=1` (install.sh lo deja permanente). |
| Chrome/Meet no lista CamStream | El módulo debe cargarse con `exclusive_caps=1` (install.sh ya lo pone). Reinicia el navegador. |
| Video verde o corrupto al conectar | Espera 1 s (siguiente keyframe) o reinicia la transmisión en la app. |
| (Windows) "No se pudo abrir la cámara virtual" | Reejecuta `setup-windows.ps1`, o abre OBS → "Iniciar cámara virtual" una vez. Solo un programa puede usar la cámara virtual a la vez. |
