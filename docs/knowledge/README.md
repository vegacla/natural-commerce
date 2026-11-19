# Conocimiento

Lo que se aprende al implementar y no cabe en una decisión (`../registro-de-decisiones.md`) ni en una medición (`../mediciones.md`): comportamientos de las herramientas, trampas, procedimientos que funcionaron. Un archivo por tema, con fecha y versión de lo observado.

Solo descubrimientos: lo que se observó en el sistema y no era evidente. No van requerimientos, decisiones, reglas de diseño ni documentación general: eso va en el registro de decisiones o en `docs/`. Tampoco planes ni lo que no se probó.

| Archivo | Tema |
|---|---|
| `xtdb-bases-multiples.md` | `ATTACH DATABASE` en XTDB 2.2: cómo funciona, límites y por qué se descartó (D80). |
| `xtdb-trampas.md` | Comportamientos de XTDB 2.2 que costaron tiempo. |
| `xtdb-bitemporalidad.md` | Validez y registro: qué entrega XTDB y qué hay que resolver. |
| `xtdb-respaldo.md` | Respaldo y restauración en disco local, y cómo forzar un bloque. |
| `nucleo.md` | Comportamientos no evidentes del núcleo de MARCO. |
| `entorno-jdk.md` | JDK 21, `JAVA_CMD`, opciones de JVM y una trampa de Malli. |
