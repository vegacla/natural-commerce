# SDK de Anthropic para Java junto a XTDB

Observado el 19 de noviembre de 2026, con `com.anthropic/anthropic-java` 3.10.0 y XTDB 2.2, JDK 21.

## okhttp: dos versiones declaradas, sin choque observado

El SDK depende de okhttp 4.12 y XTDB de okhttp 5.5. tools.deps resuelve una sola versión en el classpath. Una llamada real a la API de mensajes desde `nc.sara.claude`, en la misma JVM con un nodo XTDB abierto, salió y volvió con la respuesta del servidor (un 400 por falta de crédito, interpretado por el SDK como `BadRequestException`). No hubo `NoSuchMethodError` ni `ClassNotFoundException`.

Verificado con `.\e23-claude.ps1`: primero con el 400 y después, con crédito, con respuestas completas que usan herramientas (20 llamadas a Haiku 5.5).

## Errores de la cuenta, no del código

- `400 invalid_request_error: Your credit balance is too low` — la clave es válida y la petición llegó; la organización de la consola no tiene crédito. El plan de claude.ai no paga la API.

## Pegar varias líneas en PowerShell con Read-Host

Pegar de una vez `Read-Host ...`, el comando y la limpieza hace que `Read-Host` tome la segunda línea como su entrada: el comando nunca corre y no aparece error. Por eso la clave se pide dentro de un script (`e23-claude.ps1`).
