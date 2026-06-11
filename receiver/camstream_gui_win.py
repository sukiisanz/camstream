#!/usr/bin/env python3
"""
camstream-receiver-gui (Windows) — ventana del receptor CamStream.
Misma lógica que el receptor de terminal (lo importa), con interfaz:
estado con puntos de color como la app del teléfono, botón Conectar,
y un panel que comprueba las dependencias y las instala con un clic
(winget + registro del driver de OBS, con su aviso de administrador).

Se empaqueta con PyInstaller --windowed: sin consola a la vista.
"""

import argparse
import base64
import os
import platform
import queue
import shutil
import subprocess
import sys
import threading
import time
import tkinter as tk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import camstream_receiver as core  # noqa: E402

# Paleta CamStream
BG = "#0E1A20"
CARD = "#15262E"
FG = "#FFFFFF"
SUB = "#9FB6BF"
ACCENT = "#1A8CB1"
ACCENT_HOVER = "#2AADA8"
GREEN = "#41DE8F"
COLOR_STOPPED = "#9E9E9E"
COLOR_WAITING = "#FFB300"
COLOR_WIFI = "#41DE8F"
COLOR_USB = "#42A5F5"
COLOR_ERROR = "#FF1744"

DEFAULT_PORT = 8554

OBS_DLL = os.path.join(
    os.environ.get("ProgramFiles", r"C:\Program Files"),
    "obs-studio", "data", "obs-plugins", "win-dshow",
    "obs-virtualcam-module64.dll",
)

# Mismo contenido que setup-windows.ps1, lanzado elevado desde la GUI
PS_SETUP = r'''
$ErrorActionPreference = "Continue"
function Install-IfMissing($id, $reason) {
    Write-Host ""
    Write-Host ">> $id  ($reason)"
    winget list -e --id $id 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Host "   ya instalado, salto" }
    else { winget install -e --id $id --accept-source-agreements --accept-package-agreements }
}
Install-IfMissing "OBSProject.OBSStudio" "driver de camara virtual"
Install-IfMissing "Gyan.FFmpeg"          "video H.264"
Install-IfMissing "Google.PlatformTools" "adb, modo USB"
$dll = Join-Path ${env:ProgramFiles} "obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module64.dll"
Write-Host ""
if (Test-Path $dll) {
    regsvr32 /s $dll
    Write-Host ">> Driver 'OBS Virtual Camera' registrado"
} else {
    Write-Host ">> OBS recien instalado: si algo falla, abre OBS y pulsa 'Iniciar camara virtual' una vez"
}
Read-Host "Listo. Pulsa Enter para cerrar esta ventana"
'''


class ReceiverApp:
    def __init__(self, root):
        self.root = root
        self.running = False
        self.events = queue.Queue()

        root.title("CamStream Receiver")
        root.configure(bg=BG)
        root.geometry("470x560")
        root.minsize(440, 520)
        icon = os.path.join(getattr(sys, "_MEIPASS",
                                    os.path.dirname(os.path.abspath(__file__))),
                            "icon.ico")
        if os.path.exists(icon):
            try:
                root.iconbitmap(icon)
            except tk.TclError:
                pass

        pad = {"padx": 20}
        tk.Label(root, text="CamStream Receiver", bg=BG, fg=FG,
                 font=("Segoe UI Semibold", 17)).pack(anchor="w", pady=(18, 0), **pad)
        tk.Label(root, text="Tu teléfono como webcam, sin cables raros",
                 bg=BG, fg=SUB, font=("Segoe UI", 10)).pack(anchor="w", **pad)

        # --- estado
        status = tk.Frame(root, bg=BG)
        status.pack(fill="x", pady=(16, 4), **pad)
        self.dot = tk.Canvas(status, width=16, height=16, bg=BG,
                             highlightthickness=0)
        self.dot_id = self.dot.create_oval(2, 2, 14, 14,
                                           fill=COLOR_STOPPED, outline="")
        self.dot.pack(side="left", pady=2)
        self.status_label = tk.Label(status, text="Detenido", bg=BG, fg=FG,
                                     font=("Segoe UI", 11), wraplength=380,
                                     justify="left")
        self.status_label.pack(side="left", padx=(8, 0))

        # --- botones
        btns = tk.Frame(root, bg=BG)
        btns.pack(fill="x", pady=(10, 4), **pad)
        self.connect_btn = tk.Button(
            btns, text="Conectar", command=self.on_connect,
            bg=ACCENT, fg=FG, activebackground=ACCENT_HOVER,
            activeforeground=FG, relief="flat", cursor="hand2",
            font=("Segoe UI Semibold", 12), padx=24, pady=8, bd=0)
        self.connect_btn.pack(side="left", expand=True, fill="x", padx=(0, 6))
        self.stop_btn = tk.Button(
            btns, text="Detener", command=self.on_stop, state="disabled",
            bg=CARD, fg=FG, activebackground="#2A3940", activeforeground=FG,
            relief="flat", cursor="hand2",
            font=("Segoe UI", 12), padx=24, pady=8, bd=0)
        self.stop_btn.pack(side="left", expand=True, fill="x", padx=(6, 0))

        # --- URL manual
        tk.Label(root, text="URL manual (opcional, vacío = buscar solo):",
                 bg=BG, fg=SUB, font=("Segoe UI", 9)).pack(anchor="w",
                                                           pady=(14, 2), **pad)
        self.url_entry = tk.Entry(root, bg=CARD, fg=FG, insertbackground=FG,
                                  relief="flat", font=("Segoe UI", 10))
        self.url_entry.pack(fill="x", ipady=6, **pad)

        # --- estado del sistema
        tk.Label(root, text="Estado del sistema", bg=BG, fg=FG,
                 font=("Segoe UI Semibold", 11)).pack(anchor="w",
                                                      pady=(18, 4), **pad)
        self.checks_frame = tk.Frame(root, bg=BG)
        self.checks_frame.pack(fill="x", **pad)

        self.fix_btn = tk.Button(
            root, text="Instalar dependencias (pide permiso de administrador)",
            command=self.on_fix, bg=CARD, fg=GREEN,
            activebackground="#2A3940", activeforeground=GREEN,
            relief="flat", cursor="hand2", font=("Segoe UI", 10),
            padx=12, pady=8, bd=0)
        self.fix_btn.pack(fill="x", pady=(10, 0), **pad)
        tk.Label(root,
                 text="Si acabas de instalar algo, cierra y vuelve a abrir "
                      "este programa para que lo detecte.",
                 bg=BG, fg=SUB, font=("Segoe UI", 8), wraplength=420,
                 justify="left").pack(anchor="w", pady=(4, 0), **pad)

        tk.Label(root,
                 text="En Zoom, Meet o Teams elige la cámara "
                      "«OBS Virtual Camera».",
                 bg=BG, fg=SUB, font=("Segoe UI", 9)).pack(side="bottom",
                                                           pady=12, **pad)

        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.refresh_checks()
        self.poll_events()

    # ------------------------------------------------------- hilo → UI

    def set_status(self, color, text):
        self.events.put(("status", color, text))

    def set_buttons(self, connect_on, stop_on):
        self.events.put(("buttons", connect_on, stop_on))

    def poll_events(self):
        try:
            while True:
                event = self.events.get_nowait()
                if event[0] == "status":
                    _, color, text = event
                    self.dot.itemconfig(self.dot_id, fill=color)
                    self.status_label.config(text=text)
                elif event[0] == "buttons":
                    _, connect_on, stop_on = event
                    self.connect_btn.config(
                        state="normal" if connect_on else "disabled")
                    self.stop_btn.config(
                        state="normal" if stop_on else "disabled")
        except queue.Empty:
            pass
        self.root.after(120, self.poll_events)

    # ------------------------------------------------------ comprobaciones

    def system_checks(self):
        return [
            ("Cámara virtual (OBS Studio)", os.path.exists(OBS_DLL), True),
            ("Video (ffmpeg)", bool(shutil.which("ffmpeg")), True),
            ("Cable USB (adb)", bool(shutil.which("adb")), False),
        ]

    def refresh_checks(self):
        for child in self.checks_frame.winfo_children():
            child.destroy()
        for name, ok, required in self.system_checks():
            mark = "✓" if ok else "✗"
            color = GREEN if ok else COLOR_ERROR
            extra = "" if ok or not required else "  (necesario)"
            row = tk.Frame(self.checks_frame, bg=BG)
            row.pack(fill="x", pady=1)
            tk.Label(row, text=mark, bg=BG, fg=color,
                     font=("Segoe UI Semibold", 10)).pack(side="left")
            tk.Label(row, text=f"  {name}{extra}", bg=BG, fg=FG,
                     font=("Segoe UI", 10)).pack(side="left")

    def on_fix(self):
        encoded = base64.b64encode(PS_SETUP.encode("utf-16-le")).decode()
        subprocess.Popen([
            "powershell", "-NoProfile", "-Command",
            "Start-Process powershell -Verb RunAs -ArgumentList "
            f"'-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','{encoded}'",
        ], creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.set_status(COLOR_WAITING,
                        "Instalador abierto en otra ventana; cuando termine, "
                        "cierra y reabre este programa")

    # ------------------------------------------------------------ conexión

    def on_connect(self):
        if self.running:
            return
        missing = [n for n, ok, req in self.system_checks() if req and not ok]
        if missing:
            self.set_status(COLOR_ERROR,
                            f"Falta: {', '.join(missing)}. Usa el botón "
                            "«Instalar dependencias» de abajo.")
            return
        self.running = True
        self.set_buttons(False, True)
        threading.Thread(target=self.connection_loop, daemon=True).start()

    def on_stop(self):
        self.running = False
        if core.child:
            core.child.terminate()
        self.set_buttons(True, False)
        self.set_status(COLOR_STOPPED, "Detenido")

    def on_close(self):
        self.running = False
        if core.child:
            core.child.terminate()
        self.root.destroy()

    def connection_loop(self):
        manual = self.url_entry.get().strip()
        args = argparse.Namespace(device=None, latency=0)
        backoff = 2
        while self.running:
            url, usb = manual, False
            if not url:
                self.set_status(COLOR_WAITING,
                                "Buscando el teléfono (WiFi y USB)…")
                found = core.discover_wifi(DEFAULT_PORT, 8)
                if not found and self.running:
                    found = core.setup_usb(DEFAULT_PORT)
                if not found:
                    if not self.running:
                        break
                    self.set_status(
                        COLOR_WAITING,
                        f"Teléfono no encontrado, reintento en {backoff}s "
                        "(¿app iniciada y mismo WiFi?)")
                    time.sleep(backoff)
                    backoff = min(backoff * 2, 30)
                    continue
                host, port = found
                usb = host == "127.0.0.1"
                url = f"rtsp://{host}:{port}/cam"
            backoff = 2

            via = "USB" if usb else "WiFi"
            color = COLOR_USB if usb else COLOR_WIFI
            self.set_status(color,
                            f"Conectado por {via} — la cámara «OBS Virtual "
                            "Camera» ya aparece en Zoom/Meet/Teams")
            code = core.run_pyvirtualcam(url, args)
            if code != 0:
                self.set_status(COLOR_ERROR,
                                "No se pudo abrir la cámara virtual. ¿OBS "
                                "instalado? Usa el botón de abajo, o abre OBS "
                                "y pulsa «Iniciar cámara virtual» una vez.")
                break
            if self.running:
                self.set_status(COLOR_WAITING,
                                "Conexión perdida; volviendo a buscar…")

        self.running = False
        self.set_buttons(True, False)


def main():
    if platform.system() != "Windows":
        print("Esta interfaz es para Windows; en Linux usa "
              "camstream-receiver-gui (GTK) o camstream-receiver.")
    root = tk.Tk()
    ReceiverApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
