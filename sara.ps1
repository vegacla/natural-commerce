# Arranca SARA mínima con los datos de ejemplo, usando JDK 21 (lo exige XTDB v2).
# Uso, desde cualquier carpeta:  & "<ruta del proyecto>\sara.ps1" [puerto]
# Luego abrir http://localhost:8081 (o el puerto indicado). Ctrl+C la detiene.
#
# El chat (/chat) usa Claude Haiku 5.5 si hay ANTHROPIC_API_KEY: la toma del entorno
# o del archivo .env del proyecto (ignorado por git). Cada frase es una llamada a la
# API y cuesta dinero. Sin clave, el chat lo dice y los formularios funcionan igual.

$java = Join-Path $env:LOCALAPPDATA 'Programs\Temurin21\jdk-21.0.13+9\bin\java.exe'
if (-not (Test-Path $java)) { throw "No se encontró JDK 21 en $java" }

$clave = $env:ANTHROPIC_API_KEY
$archivo = Join-Path $PSScriptRoot '.env'
if (-not $clave -and (Test-Path $archivo)) {
    foreach ($linea in Get-Content $archivo) {
        if ($linea -match '^\s*ANTHROPIC_API_KEY\s*=\s*"?([^"\s]+)"?\s*$') { $clave = $Matches[1] }
    }
}

$anterior = $env:JAVA_CMD
$anteriorClave = $env:ANTHROPIC_API_KEY
$env:JAVA_CMD = $java
$env:ANTHROPIC_API_KEY = $clave
Push-Location $PSScriptRoot
try {
    deps -M:xtdb:sara @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
    $env:JAVA_CMD = $anterior
    $env:ANTHROPIC_API_KEY = $anteriorClave
    $clave = $null
}
