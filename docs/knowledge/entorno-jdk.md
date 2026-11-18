# Entorno: JDK, deps y Malli

Observado en las etapas 1 y 2 (17 de noviembre de 2026).

## JDK 21 y XTDB

- XTDB v2 exige JDK 21 o superior. La máquina tiene JDK 17 en el PATH y Temurin 21.0.13 instalado aparte, sin tocar el PATH.
- `deps` es deps.clj: ignora `JAVA_HOME` si hay un `java` en el PATH. Se fija `JAVA_CMD` solo mientras corre el comando (`.\pruebas.ps1` y `.\sara.ps1` lo hacen).
- Todo lo que abra un nodo XTDB necesita las opciones de JVM del alias `:xtdb` o `:test` (`--add-opens=java.base/java.nio=ALL-UNNAMED`, `--enable-native-access=ALL-UNNAMED`, `-Dio.netty.tryReflectionSetAccessible=true`, `-Dio.netty.noUnsafe=false`). Con el JDK 17 del PATH, las pruebas del almacén no arrancan.
- Las pruebas que no usan XTDB (etapa 1) pasan igual con JDK 17 y 21.
- Un script suelto se corre con `JAVA_CMD=... deps -M:xtdb -i archivo.clj`.

## Malli

- `[:ref :proveedor]` en la notación choca con el `:ref` propio de Malli. Se resolvió reemplazándolo en un registro propio de esquemas (`nc.esquema`); la notación no cambió.
