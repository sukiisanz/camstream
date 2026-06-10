#!/usr/bin/env bash
# Genera dist/camstream-receiver (binario único, sin Python).
# Normalmente NO lo necesitas en Linux: install.sh instala el script tal
# cual. Esto es para distribuir un binario a máquinas sin Python.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

python3 -m pip install --user --upgrade pyinstaller -r requirements.txt
python3 -m PyInstaller --onefile --console --name camstream-receiver camstream_receiver.py

echo
echo "Listo: $(pwd)/dist/camstream-receiver"
echo "Recuerda que el sistema necesita igualmente GStreamer/ffmpeg,"
echo "v4l2loopback y avahi (receiver/install.sh instala todo eso)."
