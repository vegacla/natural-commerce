# E23 con Claude de verdad (nc.lenguaje-claude-test). Cuesta dinero: cinco llamadas.
# Uso:  .\e23-claude.ps1   — pide la clave sin mostrarla, corre la prueba y la borra.
# La clave vive solo en este proceso mientras corre; no se escribe en ningún archivo.

$segura = Read-Host -AsSecureString "Clave de la API de Anthropic"
$clave = [System.Net.NetworkCredential]::new("", $segura).Password
if (-not $clave) { throw "No se ingresó una clave." }

$anterior = $env:ANTHROPIC_API_KEY
$env:ANTHROPIC_API_KEY = $clave
try {
    & (Join-Path $PSScriptRoot 'pruebas.ps1') -n nc.lenguaje-claude-test
} finally {
    $env:ANTHROPIC_API_KEY = $anterior
    $clave = $null
}
