# E23 con Claude de verdad (nc.lenguaje-claude-test). Cuesta dinero: una conversación de
# cinco frases y una muestra de 34 frases por vuelta ($env:NC_E23_VUELTAS, por omisión 1).
# Uso:  .\e23-claude.ps1
# Toma la clave de ANTHROPIC_API_KEY en el archivo .env del proyecto (ignorado por
# git); si no está, la pide sin mostrarla. Vive solo en este proceso mientras corre.

$clave = $null
$archivo = Join-Path $PSScriptRoot '.env'
if (Test-Path $archivo) {
    foreach ($linea in Get-Content $archivo) {
        if ($linea -match '^\s*ANTHROPIC_API_KEY\s*=\s*"?([^"\s]+)"?\s*$') { $clave = $Matches[1] }
    }
}
if (-not $clave) {
    $segura = Read-Host -AsSecureString "Clave de la API de Anthropic"
    $clave = [System.Net.NetworkCredential]::new("", $segura).Password
}
if (-not $clave) { throw "No hay clave: ponla en .env (ANTHROPIC_API_KEY=...) o escríbela al pedirla." }

$anterior = $env:ANTHROPIC_API_KEY
$env:ANTHROPIC_API_KEY = $clave
try {
    # Sin argumentos, todo nc.lenguaje-claude-test; con argumentos, se pasan al runner
    # (p. ej. -v nc.lenguaje-claude-test/e23-conversacion-larga).
    $pruebas = if ($args.Count -gt 0) { $args } else { @('-n', 'nc.lenguaje-claude-test') }
    & (Join-Path $PSScriptRoot 'pruebas.ps1') @pruebas
} finally {
    $env:ANTHROPIC_API_KEY = $anterior
    $clave = $null
}
