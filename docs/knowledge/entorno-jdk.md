# Entorno: JDK, deps y Malli

Observado en las etapas 1 y 2 (17 de noviembre de 2026).

## JDK 21 y XTDB

- XTDB v2 exige JDK 21 o superior. La máquina tiene JDK 17 en el PATH y Temurin 21.0.13 instalado aparte, sin tocar el PATH.
- `deps` es deps.clj: ignora `JAVA_HOME` si hay un `java` en el PATH. Se fija `JAVA_CMD` solo mientras corre el comando (`.\pruebas.ps1` y `.\sara.ps1` lo hacen).
- Todo lo que abra un nodo XTDB necesita las opciones de JVM del alias `:xtdb` o `:test` (`--add-opens=java.base/java.nio=ALL-UNNAMED`, `--enable-native-access=ALL-UNNAMED`, `-Dio.netty.tryReflectionSetAccessible=true`, `-Dio.netty.noUnsafe=false`). Con el JDK 17 del PATH, las pruebas del almacén no arrancan.
- Las pruebas que no usan XTDB (etapa 1) pasan igual con JDK 17 y 21.
- Un script suelto se corre con `JAVA_CMD=... deps -M:xtdb -i archivo.clj`.
- **Memoria de la suite** (2026-11-18). La suite completa (60 pruebas, XTDB en memoria) usa como máximo unos 700 MB sin tope y 545 MB con `-Xmx768m -XX:MaxDirectMemorySize=512m`, que ahora fija el alias `:test`. Tarda unos 40 segundos.
- **Una mutación en un namespace que otro guarda como valor no se ve** (2026-11-19). El script recarga solo el namespace mutado. `nc.expresion` guardaba `v/igual?` dentro de un mapa al cargarse, así que mutar `nc.valor` no cambiaba lo que evaluaba `:=`, y la mutación «comparar con nada es un error» no se detectaba aunque E29 sí lo notaba. *Verificado:* con `:=` y `:!=` como `(fn [a b] (v/igual? a b))`, que lee la variable en cada llamada, se detecta. Una mutación que no se detecta puede ser esto y no una prueba que falta.
- **Tope de memoria subido a 2 GB** (2026-11-19). Con `-Xmx768m`, las mutaciones (una sola JVM, 59 mutaciones) usaban unos 3 núcleos de CPU sin otro proceso compitiendo, y cada archivo de pruebas tardaba 2 a 3 veces lo que tarda solo: señal de recolección frecuente. Los alias `:test` y `:mutaciones` usan ahora `-Xmx2g -XX:MaxDirectMemorySize=1g`; el equipo tiene 16 GB. *Verificado:* la corrida completa de mutaciones bajó de 142 a 108 s, con el registro de detectoras ya lleno.
- **Comandos largos en segundo plano** (2026-11-18). Claude Code detiene un comando en segundo plano si el equipo se queda sin memoria mientras la sesión espera, aunque el comando use poco. Un proceso que modifica archivos (como las mutaciones) puede quedar cortado a mitad. Correrlo en primer plano.
- **Dónde se va el tiempo de una corrida** (2026-11-19). De unos 35 s: JVM 1 s, cargar XTDB 1,5 s, compilar el proyecto y las pruebas 5 s, **el primer nodo de XTDB 8 s** (los siguientes arrancan en 30 ms) y las pruebas unos 20 s (una transacción tarda unos 6 ms, una consulta unos 10 ms; `respaldo-test`, con nodos en disco, 6 s). Por eso las mutaciones corren en una sola JVM (`deps -M:mutaciones`): pagan el arranque una vez y paran en el primer archivo de pruebas que falla. Bajaron de unos 16 minutos a unos 100 segundos.
- **Las mutaciones se volvieron lentas otra vez** (2026-11-19, 53 mutaciones). Dos causas, ambas del script y no del proyecto:
  - Un archivo de prueba que no está en `orden-conocido` corre al final. `nc.lenguaje-test` y `nc.composicion-test` no estaban, y cada mutación que solo ellos detectan pagaba la suite casi entera (15 a 19 s cada una). Ponerlos en orden no bastó, porque igual pagan los archivos anteriores. Ahora el script guarda qué archivo detectó cada mutación (`target/mutaciones-detectoras.edn`) y lo prueba primero: 94 s en total, alrededor de 1 s por mutación. La primera corrida, sin ese archivo, tarda más (de 4 a 10 minutos en esta máquina).
  - **Zed bloquea archivos en Windows.** Si Zed tiene abierto un archivo de `src` mientras el script lo muta, escribirlo falla con «La operación solicitada no se puede realizar en un archivo con una sección asignada a usuario abierta». La corrida se caía y dejaba el archivo mutado: pasó dos veces con `src/nc/sara.clj`. Ahora el script reintenta la escritura. Después de una corrida cortada, revisar `git status` en `src`. *Verificado:* la corrida siguiente se negó a seguir («las pruebas fallan sin mutar nada»), y `git diff` mostró la mutación aplicada.
- **El CDS de Java empeora el arranque** (2026-11-19). `-XX:+AutoCreateSharedArchive` llevó el primer nodo de XTDB de 8 a unos 50 segundos, y el archivo de clases no llegó a crearse. Descartado.
- **Las pruebas en una JVM caliente revelan carreras de tiempo** (2026-11-19). Repetidas en la misma JVM, el código corre más rápido y una escritura y la lectura siguiente caen en el mismo instante. Así se encontró la trampa de "leer lo que se acaba de escribir" (`xtdb-comportamientos.md`).

## Malli

- `[:ref :proveedor]` en la notación choca con el `:ref` propio de Malli. Se resolvió reemplazándolo en un registro propio de esquemas (`nc.esquema`); la notación no cambió.
