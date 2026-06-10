#!/usr/bin/env bash
# Desinstala camstream-receiver (no borra los paquetes dnf).
# Uso: sudo ./uninstall.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "Ejecútame con sudo: sudo $0" >&2
    exit 1
fi

rm -f /usr/local/bin/camstream-receiver
rm -f /etc/systemd/user/camstream-receiver.service
rm -f /etc/modules-load.d/camstream.conf
rm -f /etc/modprobe.d/camstream.conf
modprobe -r v4l2loopback 2>/dev/null || true

echo "Listo. Si activaste el servicio, desactívalo con:"
echo "  systemctl --user disable --now camstream-receiver"
