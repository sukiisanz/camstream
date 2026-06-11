# setup-windows.ps1 — preparación de UNA SOLA VEZ para usar CamStream en
# este PC. Después, el uso diario es solo abrir camstream-receiver.exe.
#
# Ejecutar como administrador:
#   clic derecho en PowerShell → "Ejecutar como administrador" y luego:
#   powershell -ExecutionPolicy Bypass -File setup-windows.ps1

$ErrorActionPreference = "Continue"

function Install-IfMissing($id, $reason) {
    Write-Host ""
    Write-Host ">> $id  ($reason)"
    winget list -e --id $id 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "   ya instalado, salto"
    } else {
        winget install -e --id $id --accept-source-agreements --accept-package-agreements
    }
}

Install-IfMissing "OBSProject.OBSStudio" "trae el driver de camara virtual"
Install-IfMissing "Gyan.FFmpeg"          "descomprime el video H.264"
Install-IfMissing "Google.PlatformTools" "adb, para el modo USB"

# Registrar el driver de camara virtual de OBS sin tener que abrir OBS.
# Es idempotente: re-registrarlo no hace dano.
$dll = Join-Path ${env:ProgramFiles} "obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module64.dll"
Write-Host ""
if (Test-Path $dll) {
    regsvr32 /s $dll
    if ($LASTEXITCODE -eq 0) {
        Write-Host ">> Driver 'OBS Virtual Camera' registrado correctamente"
    } else {
        Write-Host ">> No pude registrar el driver (codigo $LASTEXITCODE)."
        Write-Host "   Plan B: abre OBS, pulsa 'Iniciar camara virtual' una vez y cierralo."
    }
} else {
    Write-Host ">> No encuentro $dll"
    Write-Host "   Si OBS se acaba de instalar, cierra esta ventana, abre una nueva y reejecuta."
}

Write-Host ""
Write-Host "Listo. Uso diario: app CamStream en el telefono -> Iniciar,"
Write-Host "y doble clic a camstream-receiver.exe en este PC."
