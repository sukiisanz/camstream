<p align="center"><img src="CamStream-logo.png" width="160" alt="CamStream"></p>

# CamStream 📱→💻

Convierte tu teléfono Android en una **webcam para tu PC** (Windows o
Linux), por WiFi o por cable USB. Gratis, open source, sin cuentas y sin
nube: el video va directo de tu teléfono a tu ordenador.

**¿Para qué sirve?** Para usar la cámara de tu teléfono (mucho mejor que
la mayoría de webcams) en Zoom, Google Meet o Teams.

**¿Cómo funciona?** Dos piezas:

- **La app del teléfono (CamStream)**: emite lo que ve la cámara y avisa
  por la red "¡estoy aquí!" para que el PC la encuentre solo.
- **El programa del PC (CamStream Receiver)**: encuentra el teléfono,
  recibe el video y crea una cámara que las apps de reuniones usan como
  cualquier webcam. Si no hay WiFi, funciona por el cable USB.

## Qué puede hacer la app del teléfono

- **Pantalla completa** con botones flotantes y estados de color: gris
  detenido · ámbar esperando · verde WiFi · azul USB · **punto rojo
  latiendo** cuando alguien está recibiendo
- **Datos en vivo**: resolución, fps y Mbps reales en pantalla
- **QR de conexión** para conectar VLC u OBS sin teclear la dirección
- **Calidad**: 1080p / 720p / 480p
- **Orientación 0°/90°/180°/270° y modo espejo** — pon el teléfono en
  el trípode como quieras (¡en vertical también!) y endereza la imagen
  desde ajustes
- **Ajustes de imagen**: brillo, contraste y saturación en vivo
- **Rótulo personalizable** (nombre y cargo) incrustado en el video,
  con 4 estilos, colores y posición a elegir
- **Modo ahorro**: atenúa la pantalla durante reuniones largas
- Lo que ves en el preview **es exactamente lo que recibe el PC**

## Instalación (una sola vez)

### 1. La app en el teléfono (Android 12+)

Descarga el APK: pestaña **Actions** del repo → última ejecución de
**build-apk** → artifact **camstream-apk**. Pásalo al teléfono, tócalo e
instálalo (acepta "orígenes desconocidos" si lo pregunta).

### 2. El programa en el PC

**Windows:**

1. Descarga el artifact **camstream-receiver-windows**: pestaña
   **Actions** → última ejecución de **build-receiver**.
2. Doble clic a `camstream-receiver.exe` — se abre la ventana de
   CamStream Receiver.
3. La primera vez, pulsa **"Instalar dependencias"** (acepta el aviso
   de administrador): instala OBS, ffmpeg y adb él solo. Al terminar,
   cierra y reabre el programa.
4. Pulsa **Conectar**: encuentra el teléfono solo, por WiFi o USB.

**Fedora / Linux:**

1. Descarga el paquete: **Actions** → última ejecución de
   **build-receiver** → artifact **camstream-receiver-rpm**.
2. Instálalo: doble clic sobre el `.rpm`, o en terminal:
   `sudo dnf install ./camstream-receiver-*.rpm`
3. Abre **CamStream Receiver** desde el menú de aplicaciones.
4. La primera vez, pulsa **"Instalar dependencias"** (pide tu
   contraseña). Si lo pide, reinicia.
5. Pulsa **Conectar**.

<details>
<summary>Alternativa sin RPM (terminal)</summary>

Descarga el artifact **camstream-receiver-linux**, y:

```bash
tar -xzf camstream-receiver-linux.tar.gz
cd receiver && sudo ./install.sh   # dependencias (una sola vez)
./camstream-receiver
```

Opcional, para que arranque solo al iniciar sesión:

```bash
systemctl --user enable --now camstream-receiver
```
</details>

## Uso diario

1. **Teléfono**: abre CamStream → **Iniciar**.
2. **PC**: abre **CamStream Receiver** → **Conectar**.
3. **Zoom/Meet/Teams** → Configuración → Cámara → **CamStream** (en
   Windows se llama **OBS Virtual Camera**).

Para usar el cable en vez del WiFi: conéctalo, acepta el aviso de
"depuración USB" en el teléfono, y listo — el PC cambia solo a USB si no
encuentra el teléfono por WiFi.

## Si algo no va

| Problema | Arreglo |
|---|---|
| La imagen sale **tumbada** (teléfono en vertical) | Ajustes (🎚) → **Orientación** → 90° o 270°. La app emite en horizontal por defecto. |
| En Meet/Zoom **te ves espejado** o el rótulo al revés | Es el espejo de tu *propia vista* que hacen Meet/Zoom: los demás lo ven bien. Compruébalo con VLC. |
| El PC no encuentra el teléfono | Mismo WiFi los dos (no la red "invitados"). En Windows, permite el acceso cuando pregunte el firewall. |
| Windows: "No se pudo abrir la cámara virtual" | Pulsa "Instalar dependencias" en la ventana, o abre OBS y dale a "Iniciar cámara virtual" una vez. |
| Meet/Zoom no listan la cámara | Cierra y reabre el navegador o la app de reuniones. |
| El video se ve a saltos | Usa el cable USB, o baja la calidad a 480p en ajustes. |
| La app deja de emitir al cambiar de app | Android corta la cámara en segundo plano: mantén CamStream en pantalla (usa el modo ahorro 🔋). |

## Para desarrolladores

- `android/` — la app (Kotlin: Camera2 → OpenGL → MediaCodec H.264 →
  servidor RTSP propio + anuncio mDNS).
- `receiver/` — el receptor (Python): núcleo CLI multiplataforma, GUI
  GTK para Linux y GUI Tkinter para Windows.
- `packaging/` — paquete RPM, script de configuración del sistema y
  lanzadores de Linux.
- GitHub Actions compila el APK, el exe de Windows, el tar.gz y el RPM
  automáticamente (artifacts en **Actions**; releases al crear un tag
  `v*`).
- Cómo funciona por dentro, decisiones de diseño y ajustes de latencia:
  [docs/DETALLES-TECNICOS.md](docs/DETALLES-TECNICOS.md).
