# Conocimiento

Lo que se aprende al implementar y no cabe en una decisión (`../registro-de-decisiones.md`) ni en una medición (`../mediciones.md`): comportamientos de las herramientas, procedimientos que funcionaron. Un archivo por tema, con fecha y versión de lo observado.

Solo descubrimientos: lo que se observó en el sistema y no era evidente. No van requerimientos, decisiones, reglas de diseño ni documentación general: eso va en el registro de decisiones o en `docs/`. Tampoco planes ni lo que no se probó.

| Archivo | Tema |
|---|---|
| `xtdb-bases-multiples.md` | `ATTACH DATABASE` en XTDB 2.2: cómo funciona y sus límites (evaluado para D80). |
| `xtdb-comportamientos.md` | XTDB 2.2: sus límites, la semántica que hay que respetar y nuestros errores al usarlo, cada uno con cómo se verificó. |
| `xtdb-bitemporalidad.md` | Validez y registro: qué entrega XTDB sin diseño adicional. |
| `xtdb-respaldo.md` | Respaldo y restauración en disco local, y cómo forzar un bloque. |
| `nucleo.md` | Comportamientos no evidentes del núcleo de MARCO. |
| `entorno-jdk.md` | JDK 21, `JAVA_CMD`, opciones de JVM, tiempos y memoria de las pruebas, y un choque con Malli. |

Cada entrada dice si fue verificada y cómo. Una hipótesis sin verificar se marca como tal. `reproducciones/` guarda los programas mínimos que verifican un comportamiento.
