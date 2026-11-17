# Arranca SARA mínima con los datos de ejemplo, usando JDK 21 (lo exige XTDB v2).
# Uso, desde cualquier carpeta:  & "<ruta del proyecto>\sara.ps1" [puerto]
# Luego abrir http://localhost:8081 (o el puerto indicado). Ctrl+C la detiene.

$java = Join-Path $env:LOCALAPPDATA 'Programs\Temurin21\jdk-21.0.13+9\bin\java.exe'
if (-not (Test-Path $java)) { throw "No se encontró JDK 21 en $java" }

$anterior = $env:JAVA_CMD
$env:JAVA_CMD = $java
Push-Location $PSScriptRoot
try {
    deps -M:xtdb:sara @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
    $env:JAVA_CMD = $anterior
}
