#!/usr/bin/env bash
# Comprueba que la cámara virtual funciona SIN necesitar el teléfono:
# pinta barras de colores en /dev/video10 durante 30 segundos.
# Mientras corre, abre Zoom/Meet o ejecuta:
#   gst-launch-1.0 v4l2src device=/dev/video10 ! videoconvert ! autovideosink
set -euo pipefail

DEVICE="${1:-/dev/video10}"

if [[ ! -e "$DEVICE" ]]; then
    echo "$DEVICE no existe. Carga el módulo primero:" >&2
    echo "  sudo modprobe v4l2loopback video_nr=10 card_label=CamStream exclusive_caps=1" >&2
    exit 1
fi

echo "Emitiendo barras de prueba en $DEVICE durante 30s…"
timeout 30 gst-launch-1.0 -q \
    videotestsrc is-live=true pattern=smpte \
    ! video/x-raw,width=1280,height=720,framerate=30/1 \
    ! timeoverlay \
    ! videoconvert \
    ! video/x-raw,format=YUY2 \
    ! v4l2sink device="$DEVICE" sync=true || true

echo "Hecho. Si viste las barras en tu app de reuniones, v4l2loopback funciona."
