# Genera dist\camstream-receiver.exe (ejecutable único, sin Python).
# Requisitos: Python 3.10+ instalado (winget install Python.Python.3.12)
# Uso:  powershell -ExecutionPolicy Bypass -File build-windows.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

python -m pip install --upgrade pip
python -m pip install pyinstaller -r requirements.txt

python -m PyInstaller --onefile --console --name camstream-receiver camstream_receiver.py

Write-Host ""
Write-Host "Listo: $PSScriptRoot\dist\camstream-receiver.exe"
Write-Host "Recuerda instalar tambien (una sola vez):"
Write-Host "  winget install OBSProject.OBSStudio    # driver de camara virtual"
Write-Host "  winget install Gyan.FFmpeg             # decodificador de video"
Write-Host "  winget install Google.PlatformTools    # adb, para el modo USB"
