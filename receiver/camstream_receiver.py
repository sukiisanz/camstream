#!/usr/bin/env python3
"""
camstream-receiver — recibe el stream RTSP del teléfono (app CamStream)
y lo convierte en una webcam virtual que Zoom, Meet o Teams usan como
una cámara normal.

Funciona en:
  * Linux  → v4l2loopback (/dev/video10) via GStreamer o ffmpeg
  * Windows → cámara virtual de OBS via pyvirtualcam + ffmpeg
    (requiere tener instalado OBS Studio, que es gratis y open source)

Orden de conexión:
  1. WiFi: busca el servicio mDNS `_camstream._tcp` (zeroconf o avahi).
  2. USB:  si no aparece en --wifi-timeout segundos, hace
           `adb forward tcp:8554 tcp:8554` y conecta por 127.0.0.1.

El transporte RTSP siempre es TCP: es lo único que funciona a través de
`adb forward` y en red local no penaliza la latencia.
"""

import argparse
import os
import platform
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time

IS_WINDOWS = platform.system() == "Windows"
IS_LINUX = platform.system() == "Linux"
SERVICE_TYPE = "_camstream._tcp"

child = None  # proceso gstreamer/ffmpeg en marcha


def log(msg):
    print(f"[camstream] {msg}", flush=True)


def parse_args():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--device", default="/dev/video10",
                   help="dispositivo v4l2loopback de salida, solo Linux "
                        "(def: /dev/video10)")
    p.add_argument("--port", type=int, default=8554,
                   help="puerto RTSP del teléfono (def: 8554)")
    p.add_argument("--path", default="/cam",
                   help="ruta RTSP (def: /cam)")
    p.add_argument("--url", default=None,
                   help="URL RTSP manual (salta el autodescubrimiento)")
    p.add_argument("--wifi-timeout", type=float, default=10.0,
                   help="segundos buscando por mDNS antes de probar USB (def: 10)")
    p.add_argument("--prefer", choices=["wifi", "usb"], default="wifi",
                   help="qué transporte intentar primero (def: wifi)")
    p.add_argument("--backend", default="auto",
                   choices=["auto", "gstreamer", "ffmpeg", "pyvirtualcam"],
                   help="salida de video (def: auto; en Windows siempre pyvirtualcam)")
    p.add_argument("--latency", type=int, default=0,
                   help="jitter buffer en ms; subir si hay cortes (def: 0)")
    return p.parse_args()


# ---------------------------------------------------------------- detección

def tcp_alive(host, port, timeout=2.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def discover_zeroconf(timeout):
    """Descubrimiento mDNS con la librería python-zeroconf (Linux y Windows)."""
    try:
        from zeroconf import ServiceBrowser, ServiceListener, Zeroconf
    except ImportError:
        return None

    log(f"Buscando el teléfono por mDNS ({SERVICE_TYPE}) durante {timeout:.0f}s…")
    found = []
    done = threading.Event()

    class Listener(ServiceListener):
        def add_service(self, zc, type_, name):
            info = zc.get_service_info(type_, name, timeout=3000)
            if not info:
                return
            for addr in info.parsed_addresses():
                if ":" in addr:  # IPv6 fuera, la app anuncia IPv4
                    continue
                if tcp_alive(addr, info.port):
                    found.append((addr, info.port, name))
                    done.set()
                    return
                log(f"Anunciado {addr}:{info.port} pero no responde; sigo buscando")

        def update_service(self, zc, type_, name):
            pass

        def remove_service(self, zc, type_, name):
            pass

    zc = Zeroconf()
    try:
        ServiceBrowser(zc, f"{SERVICE_TYPE}.local.", Listener())
        done.wait(timeout)
    finally:
        zc.close()
    if found:
        addr, port, name = found[0]
        log(f"Teléfono encontrado por WiFi: {name} en {addr}:{port}")
        return addr, port
    return None


def discover_avahi(timeout):
    """Descubrimiento mDNS con avahi-browse (fallback solo Linux)."""
    if not shutil.which("avahi-browse"):
        return None
    log(f"Buscando el teléfono con avahi-browse durante {timeout:.0f}s…")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            out = subprocess.run(
                ["avahi-browse", "--terminate", "--resolve", "--parsable", SERVICE_TYPE],
                capture_output=True, text=True, timeout=max(5, timeout),
            ).stdout
        except subprocess.TimeoutExpired:
            out = ""
        for line in out.splitlines():
            fields = line.split(";")
            # Línea resuelta: =;iface;proto;nombre;tipo;dominio;host;direccion;puerto;txt
            if len(fields) >= 9 and fields[0] == "=" and fields[2] == "IPv4":
                addr, svc_port = fields[7], int(fields[8])
                if tcp_alive(addr, svc_port):
                    log(f"Teléfono encontrado por WiFi: {fields[3]} en {addr}:{svc_port}")
                    return addr, svc_port
                log(f"Anunciado {addr}:{svc_port} pero no responde; sigo buscando")
        time.sleep(1)
    return None


def discover_wifi(port, timeout):
    result = discover_zeroconf(timeout)
    if result is None and IS_LINUX:
        result = discover_avahi(timeout)
    if result is None:
        log("No se encontró el teléfono por WiFi")
        if not IS_LINUX:
            log("(en Windows el descubrimiento necesita el paquete python 'zeroconf';"
                " el ejecutable .exe ya lo lleva dentro)")
    return result


def setup_usb(port):
    """Redirige el puerto RTSP por USB con adb. Devuelve (ip, puerto) o None."""
    if not shutil.which("adb"):
        hint = ("winget install Google.PlatformTools" if IS_WINDOWS
                else "dnf install android-tools")
        log(f"adb no está instalado ({hint}); salto USB")
        return None
    log("Probando conexión USB via adb…")
    state = subprocess.run(["adb", "get-state"], capture_output=True, text=True)
    if state.stdout.strip() != "device":
        log("No hay teléfono autorizado por USB (¿cable conectado y "
            "depuración USB aceptada?)")
        return None
    fwd = subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"],
                         capture_output=True, text=True)
    if fwd.returncode != 0:
        log(f"adb forward falló: {fwd.stderr.strip()}")
        return None
    if tcp_alive("127.0.0.1", port):
        log(f"Teléfono conectado por USB (adb forward tcp:{port})")
        return "127.0.0.1", port
    log("adb forward hecho pero la app no responde (¿está transmitiendo?)")
    return None


def find_source(args):
    if args.url:
        return args.url
    order = [discover_wifi, lambda *_: setup_usb(args.port)]
    if args.prefer == "usb":
        order.reverse()
    for attempt in order:
        result = attempt(args.port, args.wifi_timeout)
        if result:
            host, port = result
            return f"rtsp://{host}:{port}{args.path}"
    return None


# ----------------------------------------------------------- backends Linux

def ensure_loopback(device):
    if os.path.exists(device):
        return True
    log(f"{device} no existe. ¿Está cargado v4l2loopback?")
    log("Prueba: sudo modprobe v4l2loopback video_nr=10 "
        "card_label=CamStream exclusive_caps=1")
    log("(el script receiver/install.sh deja esto configurado para siempre)")
    return False


def gst_decoder():
    """Elige el primer decodificador H.264 disponible en GStreamer."""
    for dec in ("vah264dec", "vaapih264dec", "nvh264dec",
                "avdec_h264", "openh264dec"):
        ok = subprocess.run(["gst-inspect-1.0", "--exists", dec])
        if ok.returncode == 0:
            return dec
    return None


def gst_command(url, args):
    dec = gst_decoder()
    if not dec:
        return None
    log(f"Pipeline: GStreamer (decodificador {dec})")
    return [
        "gst-launch-1.0", "-q",
        "rtspsrc", f"location={url}", "protocols=tcp",
        f"latency={args.latency}", "drop-on-latency=true", "!",
        "rtph264depay", "!",
        "h264parse", "!",
        dec, "!",
        "videoconvert", "!",
        "video/x-raw,format=YUY2", "!",
        "v4l2sink", f"device={args.device}", "sync=false",
    ]


def ffmpeg_v4l2_command(url, args):
    log("Pipeline: ffmpeg → v4l2")
    return [
        "ffmpeg", "-hide_banner", "-loglevel", "warning", "-nostats",
        "-rtsp_transport", "tcp",
        "-fflags", "nobuffer", "-flags", "low_delay",
        "-i", url,
        "-vf", "format=yuyv422",
        "-f", "v4l2", args.device,
    ]


def run_subprocess_pipeline(cmd):
    global child
    log("Conectado: la cámara virtual está activa. Ctrl+C para salir.")
    child = subprocess.Popen(cmd)
    code = child.wait()
    child = None
    return code


# ----------------------------------------- backend pyvirtualcam (multi-OS)

def probe_stream(url):
    """Pregunta a ffprobe el tamaño y los fps del stream."""
    default = (1280, 720, 30)
    if not shutil.which("ffprobe"):
        log("ffprobe no encontrado; asumo 1280x720 a 30 fps")
        return default
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-rtsp_transport", "tcp",
             "-select_streams", "v:0",
             "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate",
             "-of", "default=noprint_wrappers=1", url],
            capture_output=True, text=True, timeout=15,
        ).stdout
        info = dict(line.split("=", 1) for line in out.split() if "=" in line)

        def parse_rate(rate):
            # Los streams en vivo suelen anunciar "0/0": eso no es un fps válido
            num, _, den = rate.partition("/")
            try:
                return round(int(num) / int(den or 1))
            except (ValueError, ZeroDivisionError):
                return 0

        width = int(info.get("width", 0)) or default[0]
        height = int(info.get("height", 0)) or default[1]
        fps = (parse_rate(info.get("avg_frame_rate", "")) or
               parse_rate(info.get("r_frame_rate", "")) or 30)
        return width, height, max(1, min(fps, 60))
    except Exception as e:
        log(f"No pude sondear el stream ({e}); asumo 1280x720 a 30 fps")
        return default


def read_exact(stream, size):
    data = b""
    while len(data) < size:
        chunk = stream.read(size - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def run_pyvirtualcam(url, args):
    """Decodifica con ffmpeg y empuja frames crudos a la cámara virtual."""
    global child
    try:
        import numpy as np
        import pyvirtualcam
    except ImportError:
        log("Faltan dependencias: pip install pyvirtualcam numpy")
        if IS_WINDOWS:
            log("(y recuerda instalar OBS Studio: winget install OBSProject.OBSStudio)")
        return 1
    if not shutil.which("ffmpeg"):
        hint = ("winget install Gyan.FFmpeg" if IS_WINDOWS else "dnf install ffmpeg-free")
        log(f"ffmpeg no encontrado ({hint})")
        return 1

    width, height, fps = probe_stream(url)
    log(f"Pipeline: ffmpeg → pyvirtualcam ({width}x{height} @ {fps} fps)")
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "warning", "-nostats",
        "-rtsp_transport", "tcp",
        "-fflags", "nobuffer", "-flags", "low_delay",
        "-i", url,
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
    ]
    frame_size = width * height * 3
    child = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    try:
        with pyvirtualcam.Camera(width=width, height=height, fps=fps) as cam:
            log(f"Cámara virtual activa: {cam.device}. Ctrl+C para salir.")
            while True:
                raw = read_exact(child.stdout, frame_size)
                if raw is None:
                    break
                cam.send(np.frombuffer(raw, np.uint8).reshape(height, width, 3))
    except RuntimeError as e:
        log(f"No se pudo abrir la cámara virtual: {e}")
        if IS_WINDOWS:
            log("¿Está instalado OBS Studio? Su instalador registra el driver "
                "'OBS Virtual Camera' que usamos como salida.")
        else:
            log("¿Está cargado v4l2loopback? Ejecuta receiver/install.sh")
        return 1
    finally:
        if child:
            child.terminate()
            child = None
    return 0


# ----------------------------------------------------------------- general

def choose_backend(args):
    if args.backend != "auto":
        return args.backend
    if IS_WINDOWS:
        return "pyvirtualcam"
    if shutil.which("gst-launch-1.0") and gst_decoder():
        return "gstreamer"
    if shutil.which("ffmpeg"):
        return "ffmpeg"
    return "pyvirtualcam"


def stream_once(url, args, backend):
    if backend == "gstreamer":
        cmd = gst_command(url, args)
        if cmd is None:
            log("GStreamer no tiene decodificador H.264; ejecuta receiver/install.sh")
            return 1
        return run_subprocess_pipeline(cmd)
    if backend == "ffmpeg":
        return run_subprocess_pipeline(ffmpeg_v4l2_command(url, args))
    return run_pyvirtualcam(url, args)


def handle_signal(signum, _frame):
    log(f"Señal {signum}: cerrando")
    if child:
        child.terminate()
    sys.exit(0)


def main():
    args = parse_args()
    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    backend = choose_backend(args)
    if backend in ("gstreamer", "ffmpeg") and not IS_LINUX:
        log(f"El backend {backend} solo existe en Linux; usa --backend pyvirtualcam")
        return 1
    if IS_LINUX and backend in ("gstreamer", "ffmpeg", "pyvirtualcam"):
        # pyvirtualcam en Linux también escribe en v4l2loopback
        if not ensure_loopback(args.device):
            return 1

    backoff = 2
    while True:
        url = find_source(args)
        if url:
            log(f"Recibiendo {url}")
            stream_once(url, args, backend)
            log("El stream terminó (¿app parada o conexión perdida?); reintento")
            backoff = 2
        else:
            log(f"Teléfono no encontrado; reintento en {backoff}s")
            time.sleep(backoff)
            backoff = min(backoff * 2, 30)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        pass
    except Exception:
        import traceback
        traceback.print_exc()
        if IS_WINDOWS:
            # que la ventana no se cierre sin dejar leer el error
            input("\nError inesperado. Pulsa Enter para cerrar…")
        sys.exit(1)
