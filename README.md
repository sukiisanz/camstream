# CamStream 📱→💻

Convierte tu teléfono Android en una webcam para **Linux (Fedora)** o
**Windows**, por WiFi o por cable USB. Sin apps de pago, sin cuentas, sin
nube: todo pasa dentro de tu casa, de tu teléfono a tu ordenador.

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

## ¿Cómo funciona? (explicado fácil)

Imagina que tu teléfono es una **emisora de radio**, pero de video:

1. La cámara del teléfono graba lo que ve.
2. Un "compresor" dentro del teléfono (el chip codificador H.264) hace el
   video pequeñito para que quepa por la red.
3. El teléfono "emite" ese video por un canal llamado RTSP, y además grita
   por la red local *"¡estoy aquí, soy CamStream!"* (eso es mDNS, lo mismo
   que usa Chromecast para que lo encuentres sin escribir números).
4. En tu PC, un programita (`camstream-receiver`) escucha ese grito,
   sintoniza la emisora, descomprime el video y lo mete en una
   **cámara de mentira**: en Linux es `/dev/video10` (creada por el módulo
   v4l2loopback) y en Windows es la "OBS Virtual Camera" (el driver gratuito
   que instala OBS Studio).
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
└── receiver/                 # Receptor multiplataforma
    ├── camstream_receiver.py      # daemon Python (autodescubre + pipeline)
    ├── camstream-receiver.service # unidad systemd (de usuario, Linux)
    ├── install.sh                 # instalador Fedora (dnf, v4l2loopback…)
    ├── uninstall.sh
    ├── requirements.txt           # deps Python (Windows / pyvirtualcam)
    ├── build-windows.ps1          # genera camstream-receiver.exe
    ├── build-linux.sh             # genera el binario Linux
    └── test/loopback-test.sh      # prueba la cámara virtual sin teléfono
```

Además, `.github/workflows/build-receiver.yml` compila los dos ejecutables
en GitHub automáticamente (pestaña **Actions → Artifacts**, o en
**Releases** al crear un tag `v*`).

## Parte 1: compilar e instalar la app Android

Necesitas **Android Studio** (gratis) o el SDK de Android por línea de
comandos. El teléfono debe tener Android 12 o superior.

### Sin compilar nada (lo más rápido)

Cada cambio en `android/` dispara el workflow **build-apk** en GitHub
Actions: entra en la pestaña *Actions* del repo → última ejecución →
descarga el artifact **camstream-apk** (`app-debug.apk`). Pásalo al
teléfono (Drive, WhatsApp, cable…), tócalo e instálalo — solo hay que
permitir "instalar de orígenes desconocidos" cuando Android lo pregunte.

### Con Android Studio (lo más fácil)

1. Abre Android Studio → *Open* → elige la carpeta `android/`.
2. Espera a que sincronice (descarga las dependencias solo la primera vez).
3. Conecta el teléfono por USB con la *depuración USB* activada
   (Ajustes → Opciones de desarrollador).
4. Pulsa el botón ▶ *Run*. La app se instala y abre sola.

### Por línea de comandos

```bash
cd android
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

## Parte 2 (Fedora): instalar el receptor

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

## Parte 2 (Windows): instalar el receptor

En Windows la cámara virtual la pone el driver de **OBS Studio** (gratis y
open source); nuestro receptor la alimenta con el video del teléfono.

> **Opción exprés:** abre PowerShell **como administrador** y ejecuta
> `powershell -ExecutionPolicy Bypass -File receiver\setup-windows.ps1` —
> hace solo todo el paso 1 (instala OBS, ffmpeg y adb, y registra el
> driver de cámara virtual sin abrir OBS). Luego salta al paso 2.

1. Instala los tres requisitos (una sola vez, desde PowerShell):

   ```powershell
   winget install OBSProject.OBSStudio    # trae el driver "OBS Virtual Camera"
   winget install Gyan.FFmpeg             # descomprime el video H.264
   winget install Google.PlatformTools    # adb, para el modo USB
   ```

   > Abre OBS una vez y pulsa "Iniciar cámara virtual" para que el driver
   > quede registrado; luego ciérralo, ya no hace falta más.

2. Descarga `camstream-receiver.exe` (de la pestaña *Actions/Releases* del
   repo) **o** compílalo tú misma:

   ```powershell
   cd receiver
   powershell -ExecutionPolicy Bypass -File build-windows.ps1
   # → dist\camstream-receiver.exe
   ```

3. Ejecútalo (doble clic o desde PowerShell para ver los mensajes):

   ```powershell
   .\camstream-receiver.exe
   ```

4. En Zoom/Meet/Teams elige la cámara **OBS Virtual Camera**.

Si prefieres no compilar nada y tienes Python instalado:

```powershell
cd receiver
pip install -r requirements.txt
python camstream_receiver.py
```

## Ejecutables listos para distribuir

- **Automático**: cada cambio en `receiver/` dispara el workflow
  `build-receiver` en GitHub Actions, que deja `camstream-receiver.exe`
  (Windows) y `camstream-receiver` (Linux) como *artifacts*. Si creas un
  tag `v1.0.0`, se publican en una *Release*.
- **A mano**: `receiver/build-windows.ps1` en Windows y
  `receiver/build-linux.sh` en Linux (PyInstaller, un solo archivo, sin
  necesitar Python en la máquina de destino).

Ojo: el ejecutable lleva dentro Python, zeroconf y pyvirtualcam, pero
**no** ffmpeg/OBS/adb — esos se instalan aparte (paso 1 de cada sección).

## Parte 3: prueba end-to-end

1. Teléfono y PC en la **misma red WiFi** (ojo: el "aislamiento de
   clientes" de algunos routers de invitados lo impide).
2. Abre CamStream en el teléfono → **Iniciar**.
3. En el PC: `camstream-receiver` (Fedora) o `camstream-receiver.exe`
   (Windows). En unos segundos debería decir *"Teléfono encontrado por
   WiFi"* y *"la cámara virtual está activa"*.
4. Abre Zoom/Meet/Teams → Configuración → Cámara → **CamStream**
   (en Windows se llama **OBS Virtual Camera**).

**Modo USB**: conecta el cable, acepta el diálogo de depuración USB en el
teléfono, y arranca el receptor. Si el WiFi no encuentra nada en 10 s
(configurable), pasa solo a USB. En la app verás *● USB conectado*.

## La interfaz

**En el teléfono** (la app es una sola pantalla, en horizontal):

```
┌──────────────────────────────────────────────┐
│                                              │
│        [ preview en vivo de la cámara ]      │
│                                              │
├──────────────────────────────────────────────┤
│   ● WiFi conectado — rtsp://192.168.1.50:8554/cam   │
├──────────────────────┬───────────────────────┤
│      [ Detener ]     │   [ Cambiar cámara ]  │
└──────────────────────┴───────────────────────┘
```

La línea de estado dice en cada momento: *Detenido*, *Esperando receptor
(anunciado por mDNS)*, *● WiFi conectado* o *● USB conectado*, siempre con
la URL del stream por si quieres conectarte a mano (VLC, OBS…).

**En el PC** no hay ventana: es un programa de consola/servicio que se
explica solo:

```
[camstream] Buscando el teléfono por mDNS (_camstream._tcp) durante 10s…
[camstream] Teléfono encontrado por WiFi: CamStream-Pixel en 192.168.1.50:8554
[camstream] Recibiendo rtsp://192.168.1.50:8554/cam
[camstream] Pipeline: GStreamer (decodificador openh264dec)
[camstream] Conectado: la cámara virtual está activa. Ctrl+C para salir.
```

La "interfaz" real del lado PC es la propia app de reuniones: en Zoom,
Meet o Teams simplemente eliges la cámara **CamStream** / **OBS Virtual
Camera** en su selector de siempre.

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
| (Windows) "No se pudo abrir la cámara virtual" | Instala OBS Studio y arranca su "cámara virtual" una vez para registrar el driver. |
| (Windows) no encuentra el teléfono por WiFi | El firewall de Windows pregunta la primera vez: permite el acceso a redes privadas. |
