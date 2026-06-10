#!/usr/bin/env bash
# Instalador de camstream-receiver para Fedora 40+.
# Uso: sudo ./install.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "Ejecútame con sudo: sudo $0" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Instalando dependencias con dnf"
dnf install -y \
    gstreamer1 \
    gstreamer1-plugins-base \
    gstreamer1-plugins-good \
    gstreamer1-plugins-bad-free \
    avahi-tools \
    android-tools \
    python3

echo "==> Habilitando el repo de Cisco para el decodificador H.264 (openh264)"
(dnf config-manager --set-enabled fedora-cisco-openh264 2>/dev/null ||
 dnf config-manager setopt fedora-cisco-openh264.enabled=1 2>/dev/null) || true
dnf install -y gstreamer1-plugin-openh264 || \
    echo "AVISO: no se pudo instalar gstreamer1-plugin-openh264; el receptor probará otros decodificadores"

echo "==> Instalando v4l2loopback (cámara virtual)"
if ! dnf install -y v4l2loopback akmod-v4l2loopback 2>/dev/null; then
    echo "    No está en los repos oficiales; habilito RPM Fusion (free)"
    dnf install -y \
        "https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm" || true
    dnf install -y akmod-v4l2loopback kmod-v4l2loopback || {
        echo "ERROR: no pude instalar v4l2loopback. Instálalo a mano y reejecuta." >&2
        exit 1
    }
fi

echo "==> Configurando v4l2loopback para que cargue en cada arranque"
cat > /etc/modules-load.d/camstream.conf <<'EOF'
v4l2loopback
EOF
cat > /etc/modprobe.d/camstream.conf <<'EOF'
options v4l2loopback video_nr=10 card_label=CamStream exclusive_caps=1
EOF

# Si los akmods acaban de compilar el módulo puede requerir un momento.
if ! modprobe v4l2loopback 2>/dev/null; then
    echo "    Forzando compilación del módulo (akmods)…"
    akmods --force || true
    modprobe v4l2loopback || {
        echo "AVISO: el módulo no cargó todavía; tras reiniciar debería estar disponible." >&2
    }
fi

echo "==> Instalando el daemon y el servicio systemd (de usuario)"
install -m 0755 "$SCRIPT_DIR/camstream_receiver.py" /usr/local/bin/camstream-receiver
install -m 0644 "$SCRIPT_DIR/camstream-receiver.service" /etc/systemd/user/camstream-receiver.service

echo
echo "Instalación completada. Para usarlo:"
echo
echo "  En primer plano (recomendado la primera vez):"
echo "      camstream-receiver"
echo
echo "  Como servicio que arranca solo con tu sesión:"
echo "      systemctl --user daemon-reload"
echo "      systemctl --user enable --now camstream-receiver"
echo
echo "  La cámara virtual aparecerá como 'CamStream' (/dev/video10)"
echo "  en Zoom, Meet y Teams."
