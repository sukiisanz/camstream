<p align="center"><img src="CamStream-logo.png" width="160" alt="CamStream"></p>

# CamStream 📱→💻

Convierte tu teléfono Android en una **webcam para tu PC** (Windows o
Linux), por WiFi o por cable USB. Gratis, open source, sin cuentas y sin
nube: el video va directo de tu teléfono a tu ordenador.

**¿Para qué sirve?** Para usar la cámara de tu teléfono (mucho mejor que
la mayoría de webcams) en Zoom, Google Meet o Teams.

**¿Cómo funciona?** Dos piezas:

- **La app del teléfono (CamStream)**: emite lo que ve la cámara y avisa
  por la red "¡estoy aquí!" para que el PC la encuentre sola.
- **El programa del PC (camstream-receiver)**: encuentra el teléfono,
  recibe el video y crea una cámara que las apps de reuniones usan como
  cualquier webcam. Si no hay WiFi, funciona por el cable USB.

## Instalación (una sola vez)

### 1. La app en el teléfono (Android 12+)

Descarga el APK: pestaña **Actions** del repo → última ejecución de
**build-apk** → artifact **camstream-apk**. Pásalo al teléfono, tócalo e
instálalo (acepta "orígenes desconocidos" si lo pregunta).

### 2. El programa en el PC

**Windows:**

1. Abre PowerShell como administrador y ejecuta
   `powershell -ExecutionPolicy Bypass -File receiver\setup-windows.ps1`
   (instala solo todo lo necesario).
2. Descarga `camstream-receiver.exe`: pestaña **Actions** → última
   ejecución de **build-receiver** → artifact **camstream-receiver-windows**.

**Fedora / Linux:**

1. Descarga el paquete: **Actions** → última ejecución de
   **build-receiver** → artifact **camstream-receiver-rpm**.
2. Instálalo: doble clic sobre el `.rpm`, o en terminal:
   `sudo dnf install ./camstream-receiver-*.rpm`
3. Abre **CamStream Receiver** desde el menú de aplicaciones.
4. La primera vez, pulsa **"Instalar dependencias"** (pide tu
   contraseña): deja configurada la cámara virtual y todo lo necesario.
   Si lo pide, reinicia.
5. Pulsa **Conectar**: encuentra el teléfono solo, por WiFi o USB.

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

1. **Teléfono**: abre CamStream → **Iniciar** (apóyalo en horizontal).
2. **PC**: doble clic a `camstream-receiver.exe` (en Linux ni eso, si lo
   dejaste como servicio).
3. **Zoom/Meet/Teams** → Configuración → Cámara → **CamStream** (en
   Windows se llama **OBS Virtual Camera**).

Para usar el cable en vez del WiFi: conéctalo, acepta el aviso de
"depuración USB" en el teléfono, y listo — el PC cambia solo a USB si no
encuentra el teléfono por WiFi.

## Si algo no va

| Problema | Arreglo |
|---|---|
| El PC no encuentra el teléfono | Mismo WiFi los dos (no la red "invitados"). En Windows, permite el acceso cuando pregunte el firewall. |
| Windows: "No se pudo abrir la cámara virtual" | Vuelve a ejecutar `setup-windows.ps1`, o abre OBS y pulsa "Iniciar cámara virtual" una vez. |
| Meet/Zoom no listan la cámara | Cierra y reabre el navegador o la app de reuniones. |
| El video se ve a saltos | Ejecuta el receptor con `--latency 100`, o usa el cable USB. |
| La app deja de emitir al cambiar de app | Android corta la cámara en segundo plano: mantén CamStream en pantalla. |

## Para desarrolladores

- `android/` — la app (Kotlin). Se abre con Android Studio o se compila
  con `./gradlew assembleDebug`.
- `receiver/` — el receptor (Python), con scripts de build e instalación.
- GitHub Actions compila el APK y los ejecutables automáticamente
  (artifacts en **Actions**; releases al crear un tag `v*`).
- Cómo funciona por dentro, decisiones de diseño y ajustes de latencia:
  [docs/DETALLES-TECNICOS.md](docs/DETALLES-TECNICOS.md).
