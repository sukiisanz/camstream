# CamStream 📱→💻

Convierte tu teléfono Android en una webcam para Fedora Linux, por WiFi o
por cable USB. Sin apps de pago, sin cuentas, sin nube: todo pasa dentro de
tu casa, de tu teléfono a tu ordenador.

```
┌─────────────────┐   H.264 sobre RTSP/TCP    ┌──────────────────────────┐
│  Teléfono       │  ────────────────────────▶ │  Fedora                  │
│  (app CamStream)│   WiFi  o  USB (adb)       │  camstream-receiver      │
│                 │                            │     │                    │
│  cámara → H.264 │                            │     ▼                    │
│  servidor RTSP  │                            │  /dev/video10 (virtual)  │
│  anuncio mDNS   │                            │     │                    │
└─────────────────┘                            │     ▼                    │
                                               │  Zoom / Meet / Teams     │
                                               └──────────────────────────┘
```

## ¿Cómo funciona? (explicado fácil)

Imagina que tu teléfono es una **emisora de radio**, pero de video:

1. La cámara del teléfono graba lo que ve.
2. Un "compresor" dentro del teléfono (el chip codificador H.264) hace el
   video pequeñito para que quepa por la red.
3. El teléfono "emite" ese video por un canal llamado RTSP, y además grita
   por la red local *"¡estoy aquí, soy CamStream!"* (eso es mDNS, lo mismo
   que usa Chromecast para que lo encuentres sin escribir números).
4. En Fedora, un programita (`camstream-receiver`) escucha ese grito,
   sintoniza la emisora, descomprime el video y lo mete en una
   **cámara de mentira** (`/dev/video10`) que el sistema crea con un módulo
   llamado v4l2loopback.
5. Zoom, Meet y Teams no saben que es de mentira: ven una webcam llamada
   **CamStream** y la usan como cualquier otra.

Si el WiFi falla, el receptor usa el cable USB: con `adb` (la herramienta
oficial de Android) crea un "túnel" por el cable y el video viaja por ahí.

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
└── receiver/                 # Receptor para Fedora
    ├── camstream-receiver         # daemon Python (autodescubre + pipeline)
    ├── camstream-receiver.service # unidad systemd (de usuario)
    ├── install.sh                 # instalador (dnf, v4l2loopback, etc.)
    ├── uninstall.sh
    └── test/loopback-test.sh      # prueba la cámara virtual sin teléfono
```

## Parte 1: compilar e instalar la app Android

Necesitas **Android Studio** (gratis) o el SDK de Android por línea de
comandos. El teléfono debe tener Android 12 o superior.

### Con Android Studio (lo más fácil)

1. Abre Android Studio → *Open* → elige la carpeta `android/`.
2. Espera a que sincronice (descarga las dependencias solo la primera vez).
3. Conecta el teléfono por USB con la *depuración USB* activada
   (Ajustes → Opciones de desarrollador).
4. Pulsa el botón ▶ *Run*. La app se instala y abre sola.

### Por línea de comandos

```bash
cd android
gradle wrapper --gradle-version 8.7   # solo la primera vez
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> Activar la *depuración USB*: Ajustes → Información del teléfono → toca
> 7 veces "Número de compilación" → vuelve atrás → Opciones de
> desarrollador → Depuración USB.

### Usar la app

1. Abre **CamStream** y acepta el permiso de cámara.
2. Pulsa **Iniciar**. Verás el preview y abajo el estado:
   - *Esperando receptor…* → emitiendo, nadie conectado todavía
   - *● WiFi conectado* / *● USB conectado* → ¡Fedora está recibiendo!
3. **Cambiar cámara** alterna entre trasera y frontal.
4. La pantalla puede apagarse de notificaciones, pero no cierres la app
   mientras transmites (Android corta la cámara a las apps cerradas).

## Parte 2: instalar el receptor en Fedora

```bash
cd receiver
sudo ./install.sh
```

El instalador hace todo esto por ti:

- Instala GStreamer, el decodificador H.264 de Cisco (openh264, gratuito),
  `avahi-tools` (para mDNS), `android-tools` (adb) y `v4l2loopback`
  (si no está en los repos oficiales, habilita RPM Fusion free).
- Deja configurado que `/dev/video10` exista en cada arranque, con el
  nombre **CamStream** y `exclusive_caps=1` (necesario para Chrome/Zoom).
- Copia el daemon a `/usr/local/bin` y la unidad systemd de usuario.

### Probar que la cámara virtual funciona (sin teléfono)

```bash
./test/loopback-test.sh
```

Emite barras de colores en `/dev/video10` durante 30 segundos. Abre Zoom o
https://meet.google.com y elige la cámara "CamStream": si ves las barras,
la mitad Linux ya funciona.

### Usar el receptor

```bash
camstream-receiver                  # en primer plano, con mensajes
```

O como servicio que arranca solo al iniciar sesión:

```bash
systemctl --user daemon-reload
systemctl --user enable --now camstream-receiver
journalctl --user -fu camstream-receiver   # ver qué está haciendo
```

Opciones útiles:

```
camstream-receiver --prefer usb          # prueba USB primero
camstream-receiver --url rtsp://192.168.1.50:8554/cam   # IP a mano
camstream-receiver --latency 100         # buffer extra si hay cortes
camstream-receiver --backend ffmpeg      # forzar ffmpeg en vez de GStreamer
```

## Parte 3: prueba end-to-end

1. Teléfono y PC en la **misma red WiFi** (ojo: el "aislamiento de
   clientes" de algunos routers de invitados lo impide).
2. Abre CamStream en el teléfono → **Iniciar**.
3. En Fedora: `camstream-receiver`. En unos segundos debería decir
   *"Teléfono encontrado por WiFi"* y *"la cámara virtual está activa"*.
4. Abre Zoom/Meet/Teams → Configuración → Cámara → **CamStream**.

**Modo USB**: conecta el cable, acepta el diálogo de depuración USB en el
teléfono, y arranca el receptor. Si el WiFi no encuentra nada en 10 s
(configurable), pasa solo a USB. En la app verás *● USB conectado*.

## Latencia

Objetivo: < 300 ms en WiFi local. Las piezas que lo hacen posible:

| Pieza | Ajuste |
|---|---|
| Codificador del teléfono | CBR, prioridad tiempo-real, `KEY_LOW_LATENCY`, keyframe cada 1 s |
| Servidor RTSP | cola corta por cliente: si el receptor se atasca, descarta y re-sincroniza en el siguiente keyframe (nunca acumula retraso) |
| Red | TCP con `TCP_NODELAY`; en WiFi local la retransmisión no se nota |
| Receptor | `rtspsrc latency=0 drop-on-latency=true` y `v4l2sink sync=false` |

Si ves video "a saltos", sube el buffer: `camstream-receiver --latency 100`
(añade ~100 ms pero suaviza). Si la imagen se congela en WiFi con mucha
interferencia, prueba el cable USB: es la opción más estable.

## Decisiones técnicas (y qué cambié del plan original)

- **RTP sobre TCP intercalado, no UDP.** `adb forward` (el modo USB) solo
  puede reenviar TCP, así que UDP nunca funcionaría por cable. Usar TCP en
  ambos modos simplifica todo y en una red local no añade latencia
  apreciable. El receptor siempre pide transporte TCP.
- **Camera2 en vez de CameraX.** Camera2 permite enviar el mismo fotograma
  a dos Surfaces a la vez (pantalla + codificador) sin copiar píxeles por
  CPU. Con CameraX habría que pasar por `ImageAnalysis` y convertir
  formatos a mano: más código y más latencia.
- **Sockets puros en vez de NanoHTTPD/Netty.** RTSP no es HTTP; NanoHTTPD
  no sirve aquí, y Netty es un cañón para matar moscas. El servidor RTSP
  completo son ~300 líneas de Kotlin con `ServerSocket`.
- **Sin fallback MJPEG (por ahora).** Todos los Android 12+ codifican
  H.264 por hardware y openh264/ffmpeg lo decodifican gratis en Fedora.
  Si algún día hiciera falta, el sitio para añadirlo es `H264Encoder` +
  una `m=` adicional en el SDP.

Todo el código es propio y open source (misma licencia que el repo); las
únicas dependencias Android son las librerías estándar de AndroidX.

## Solución de problemas

| Síntoma | Causa probable / arreglo |
|---|---|
| El receptor no encuentra el teléfono por WiFi | mDNS bloqueado: `sudo firewall-cmd --add-service=mdns --permanent && sudo firewall-cmd --reload`. O redes distintas / aislamiento AP. |
| `adb get-state` no dice `device` | Acepta el diálogo "¿Permitir depuración USB?" en el teléfono. |
| `/dev/video10` no existe | `sudo modprobe v4l2loopback video_nr=10 card_label=CamStream exclusive_caps=1` (install.sh lo deja permanente). |
| Chrome/Meet no lista CamStream | El módulo debe cargarse con `exclusive_caps=1` (install.sh ya lo pone). Reinicia el navegador. |
| Video verde o corrupto al conectar | Espera 1 s (siguiente keyframe) o reinicia la transmisión en la app. |
| La app deja de emitir al cambiar de app | Android restringe la cámara en segundo plano; mantén CamStream en pantalla o en pantalla dividida. |
