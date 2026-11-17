# Corre las pruebas con JDK 21, que exige XTDB v2.
# Uso, desde cualquier carpeta:  & "<ruta del proyecto>\pruebas.ps1"
# Argumentos extra se pasan al runner, p. ej.:  .\pruebas.ps1 -n nc.almacen-test

$java = Join-Path $env:LOCALAPPDATA 'Programs\Temurin21\jdk-21.0.13+9\bin\java.exe'
if (-not (Test-Path $java)) { throw "No se encontró JDK 21 en $java" }

$anterior = $env:JAVA_CMD
$env:JAVA_CMD = $java
Push-Location $PSScriptRoot
try {
    deps -M:test @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
    $env:JAVA_CMD = $anterior
}
