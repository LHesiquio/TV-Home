<#
    Instala TV Home en un televisor Android TV / Google TV y lo deja como launcher.

    Uso normal (un solo equipo conectado por ADB):
        .\instalar-launcher.ps1

    Con la IP y puerto que muestra "Depuracion inalambrica" en la TV:
        .\instalar-launcher.ps1 -Direccion 192.168.0.126:41261

    Recompilando antes de instalar:
        .\instalar-launcher.ps1 -Compilar

    Para devolver el Home a AT4K:
        .\instalar-launcher.ps1 -HomeAt4k
#>

param(
    [string]$Direccion = "",
    [switch]$Compilar,
    [switch]$HomeAt4k
)

$ErrorActionPreference = 'Stop'
$Proyecto = $PSScriptRoot
$Paquete  = "com.bumbu.tvhome"
$Actividad = "$Paquete/.MainActivity"
$Apk = Join-Path $Proyecto "app\build\outputs\apk\release\app-release.apk"

# --- toolchain portable -------------------------------------------------
$Tools = Join-Path $env:TEMP "opencode\tools"
$Sdk   = Join-Path $env:TEMP "opencode\android-sdk"
$Jdk   = Join-Path $Tools "jdk-17.0.20.1+1"
$Gradle = Join-Path $Tools "gradle-8.9\bin\gradle.bat"

$adb = Join-Path $Sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
}
if (-not $adb) { throw "No encuentro adb. Instala platform-tools o revisa la ruta." }

# --- compilar -----------------------------------------------------------
if ($Compilar) {
    if (-not (Test-Path $Gradle)) { throw "No encuentro Gradle en $Gradle" }
    Write-Host "Compilando APK..." -ForegroundColor Cyan
    $env:JAVA_HOME = $Jdk
    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    & $Gradle -p $Proyecto assembleRelease --no-daemon --console=plain | Select-String "BUILD|error:"
}

if (-not (Test-Path $Apk)) { throw "No existe el APK. Ejecuta con -Compilar." }

# --- conectar / elegir equipo -------------------------------------------
if ($Direccion) {
    Write-Host "Conectando a $Direccion..." -ForegroundColor Cyan
    & $adb connect $Direccion
    Start-Sleep -Seconds 2
}

if ($Direccion) {
    $serial = $Direccion
} else {
    # @(...) para que con un solo equipo siga siendo array y no un string
    $lineas = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice\s*$" })
    if ($lineas.Count -eq 0) { throw "No hay ninguna TV conectada por ADB." }
    if ($lineas.Count -gt 1) {
        Write-Host "Hay varios equipos. Indica uno con -Direccion:" -ForegroundColor Yellow
        $lineas | ForEach-Object { Write-Host "  $_" }
        exit 1
    }
    $serial = ($lineas[0].Trim() -split "\s+")[0]
}

Write-Host "Equipo: $serial" -ForegroundColor Cyan
& $adb -s $serial shell getprop ro.product.model

# --- instalar -----------------------------------------------------------
Write-Host "`nInstalando..." -ForegroundColor Cyan
& $adb -s $serial install -r $Apk

Write-Host "`nCompilando AOT (arranque mas rapido)..." -ForegroundColor Cyan
& $adb -s $serial shell "cmd package compile -m speed -f $Paquete"

# --- fijar Home ---------------------------------------------------------
if ($HomeAt4k) {
    Write-Host "`nDevolviendo el Home a AT4K..." -ForegroundColor Yellow
    & $adb -s $serial shell "cmd package set-home-activity com.overdevs.at4k/.MainActivity"
} else {
    Write-Host "`nFijando TV Home como launcher..." -ForegroundColor Cyan
    & $adb -s $serial shell "cmd package set-home-activity $Actividad"
}

Write-Host "`nHome actual:" -ForegroundColor Cyan
& $adb -s $serial shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

Write-Host "`nMemoria del launcher:" -ForegroundColor Cyan
$pss = & $adb -s $serial shell "dumpsys meminfo $Paquete" 2>&1 | Select-String "TOTAL PSS"
if ($pss) { Write-Host "  $pss" } else { Write-Host "  (no esta corriendo todavia; pulsa Inicio en la TV)" }

Write-Host "`nListo. Pulsa Inicio en la TV para verlo." -ForegroundColor Green
