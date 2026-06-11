#!/usr/bin/env python3
"""
camstream-receiver-gui — interfaz gráfica del receptor CamStream para
Linux. Misma lógica que el receptor de terminal (lo importa), pero con
ventana: estado con puntos de color, botón Conectar/Detener y un panel
que comprueba las dependencias del sistema y las instala con un clic
(pide la contraseña con pkexec).
"""

import argparse
import os
import shutil
import subprocess
import sys
import threading
import time

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import camstream_receiver as core  # noqa: E402

# Mismos colores de estado que la app del teléfono
COLOR_STOPPED = "#9E9E9E"
COLOR_WAITING = "#FFB300"
COLOR_WIFI = "#41DE8F"
COLOR_USB = "#42A5F5"
COLOR_ERROR = "#FF1744"

SETUP_SCRIPT = "/usr/libexec/camstream-receiver-setup"
ICON_PATH = "/usr/share/pixmaps/camstream-receiver.png"
DEFAULT_DEVICE = "/dev/video10"
DEFAULT_PORT = 8554


class ReceiverWindow(Gtk.Window):
    def __init__(self):
        super().__init__(title="CamStream Receiver")
        self.set_default_size(440, 420)
        self.set_border_width(16)
        if os.path.exists(ICON_PATH):
            self.set_icon_from_file(ICON_PATH)

        self.child_proc = None
        self.running = False
        self.worker = None

        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self.add(root)

        # --- estado
        status_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.dot = Gtk.Label()
        self.status_label = Gtk.Label(xalign=0)
        self.status_label.set_line_wrap(True)
        status_box.pack_start(self.dot, False, False, 0)
        status_box.pack_start(self.status_label, True, True, 0)
        root.pack_start(status_box, False, False, 0)
        self.set_status(COLOR_STOPPED, "Detenido")

        # --- botones principales
        btn_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.connect_btn = Gtk.Button(label="Conectar")
        self.connect_btn.get_style_context().add_class("suggested-action")
        self.connect_btn.connect("clicked", self.on_connect)
        self.stop_btn = Gtk.Button(label="Detener")
        self.stop_btn.set_sensitive(False)
        self.stop_btn.connect("clicked", self.on_stop)
        btn_box.pack_start(self.connect_btn, True, True, 0)
        btn_box.pack_start(self.stop_btn, True, True, 0)
        root.pack_start(btn_box, False, False, 0)

        # --- avanzado (plegado)
        expander = Gtk.Expander(label="Avanzado")
        adv = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        adv.set_margin_top(6)
        self.url_entry = Gtk.Entry()
        self.url_entry.set_placeholder_text(
            "URL manual (rtsp://…) — vacío = buscar el teléfono solo")
        self.device_entry = Gtk.Entry(text=DEFAULT_DEVICE)
        adv.pack_start(self.url_entry, False, False, 0)
        adv.pack_start(self._labeled("Cámara virtual:", self.device_entry), False, False, 0)
        expander.add(adv)
        root.pack_start(expander, False, False, 0)

        root.pack_start(Gtk.Separator(), False, False, 4)

        # --- estado del sistema
        title = Gtk.Label(xalign=0)
        title.set_markup("<b>Estado del sistema</b>")
        root.pack_start(title, False, False, 0)
        self.checks_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        root.pack_start(self.checks_box, False, False, 0)

        self.fix_btn = Gtk.Button(label="Instalar dependencias (pide contraseña)")
        self.fix_btn.connect("clicked", self.on_fix)
        root.pack_start(self.fix_btn, False, False, 0)

        self.connect("destroy", self.on_destroy)
        self.refresh_checks()

    @staticmethod
    def _labeled(text, widget):
        box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        box.pack_start(Gtk.Label(label=text), False, False, 0)
        box.pack_start(widget, True, True, 0)
        return box

    # ------------------------------------------------------------- estado

    def set_status(self, color, text):
        def apply():
            self.dot.set_markup(
                f'<span foreground="{color}" size="x-large">●</span>')
            self.status_label.set_text(text)
            return False
        GLib.idle_add(apply)

    # ----------------------------------------------- comprobaciones sistema

    def system_checks(self):
        device = self.device_entry.get_text() or DEFAULT_DEVICE
        module = (os.path.exists(device)
                  or os.path.isdir("/sys/module/v4l2loopback"))
        gst = bool(shutil.which("gst-launch-1.0")
                   and shutil.which("gst-inspect-1.0")
                   and core.gst_decoder())
        ffmpeg = bool(shutil.which("ffmpeg"))
        adb = bool(shutil.which("adb"))
        return [
            ("Cámara virtual (v4l2loopback)", module, True),
            ("Video (GStreamer con H.264)", gst or ffmpeg, True),
            ("Cable USB (adb)", adb, False),
        ]

    def refresh_checks(self):
        for child in self.checks_box.get_children():
            self.checks_box.remove(child)
        missing_required = False
        for name, ok, required in self.system_checks():
            row = Gtk.Label(xalign=0)
            mark = ("<span foreground='#41DE8F'>✓</span>" if ok else
                    "<span foreground='#FF1744'>✗</span>")
            extra = "" if ok or not required else "  (necesario)"
            row.set_markup(f"{mark}  {GLib.markup_escape_text(name)}{extra}")
            self.checks_box.pack_start(row, False, False, 0)
            if required and not ok:
                missing_required = True
        self.checks_box.show_all()
        self.fix_btn.set_sensitive(os.path.exists(SETUP_SCRIPT))
        if missing_required:
            self.set_status(COLOR_ERROR,
                            "Faltan dependencias: usa el botón de abajo")

    def on_fix(self, _btn):
        self.fix_btn.set_sensitive(False)
        self.set_status(COLOR_WAITING, "Instalando dependencias…")

        def run():
            proc = subprocess.run(["pkexec", SETUP_SCRIPT],
                                  capture_output=True, text=True)
            ok = proc.returncode == 0
            def done():
                self.refresh_checks()
                self.fix_btn.set_sensitive(True)
                if ok:
                    self.set_status(COLOR_STOPPED,
                                    "Dependencias instaladas. Pulsa Conectar.")
                else:
                    detail = (proc.stderr or proc.stdout or "").strip()
                    self.set_status(COLOR_ERROR,
                                    f"La instalación falló: {detail[-200:]}")
                return False
            GLib.idle_add(done)

        threading.Thread(target=run, daemon=True).start()

    # ------------------------------------------------------------ conexión

    def on_connect(self, _btn):
        if self.running:
            return
        self.running = True
        self.connect_btn.set_sensitive(False)
        self.stop_btn.set_sensitive(True)
        self.worker = threading.Thread(target=self.connection_loop, daemon=True)
        self.worker.start()

    def on_stop(self, _btn=None):
        self.running = False
        if self.child_proc:
            self.child_proc.terminate()
        self.connect_btn.set_sensitive(True)
        self.stop_btn.set_sensitive(False)
        self.set_status(COLOR_STOPPED, "Detenido")

    def on_destroy(self, _win):
        self.running = False
        if self.child_proc:
            self.child_proc.terminate()
        Gtk.main_quit()

    def connection_loop(self):
        device = self.device_entry.get_text() or DEFAULT_DEVICE
        args = argparse.Namespace(device=device, latency=0)
        manual = self.url_entry.get_text().strip()
        backoff = 2
        while self.running:
            url, usb = manual, False
            if not url:
                self.set_status(COLOR_WAITING, "Buscando el teléfono (WiFi y USB)…")
                found = core.discover_wifi(DEFAULT_PORT, 8)
                if not found and self.running:
                    found = core.setup_usb(DEFAULT_PORT)
                if not found:
                    if not self.running:
                        return
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

            if not os.path.exists(device):
                self.set_status(COLOR_ERROR,
                                f"No existe {device}: instala las dependencias "
                                "(botón de abajo) y reinicia si lo pide")
                break

            cmd = core.gst_command(url, args)
            if cmd is None and shutil.which("ffmpeg"):
                cmd = core.ffmpeg_v4l2_command(url, args)
            if cmd is None:
                self.set_status(COLOR_ERROR,
                                "Falta GStreamer o ffmpeg: usa el botón de abajo")
                break

            via = "USB" if usb else "WiFi"
            color = COLOR_USB if usb else COLOR_WIFI
            self.set_status(color,
                            f"Conectado por {via} — la cámara «CamStream» ya "
                            "aparece en Zoom/Meet/Teams")
            self.child_proc = subprocess.Popen(
                cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.child_proc.wait()
            self.child_proc = None
            if self.running:
                self.set_status(COLOR_WAITING,
                                "Conexión perdida; volviendo a buscar…")

        def reset():
            self.connect_btn.set_sensitive(True)
            self.stop_btn.set_sensitive(False)
            return False
        GLib.idle_add(reset)
        self.running = False


def main():
    win = ReceiverWindow()
    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
