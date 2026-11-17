# Natural Commerce — Mediciones del prototipo

Lo que pide medir la sección 7 de `prototipo-minimo.md`, etapa por etapa. Las líneas se cuentan sin blancos ni comentarios.

## Etapa 1: notación y evaluador (2026-11-17)

### Líneas del núcleo

| Archivo | Qué hace | Líneas |
|---|---|---|
| `src/nc/valor.clj` | Valores con unidad y su aritmética | 67 |
| `src/nc/expresion.clj` | Evaluador del lenguaje de expresiones | 182 |
| `src/nc/validar.clj` | Validación al escribir: expresiones y efectos | 190 |
| `src/nc/esquema.clj` | Esquemas de entidad, tipo de hecho, concepto, contrato, política y camino | 259 |
| `src/nc/catalogo.clj` | Carga y validación del catálogo | 45 |
| **Total del núcleo** | | **743** |

Pruebas: 283 líneas, 16 pruebas, 82 comprobaciones, todas pasan.

### Líneas por operación

Cinco contratos escritos como datos en `resources/canon/compras.edn`, sin código propio:

| Contrato | Líneas de EDN |
|---|---|
| registrar solicitud | 15 |
| registrar oferta | 23 |
| aprobar | 9 |
| emitir orden | 28 |
| registrar factura | 12 |

Todavía no se ejecutan: el ejecutor es de la etapa 3. Hasta entonces no se sabe si la hipótesis de P6 (operaciones sin código propio) se sostiene.

### Rodeos al almacén

Ninguno: la etapa 1 no usa almacén.

### Correcciones

- Humanas: ninguna.
- Del agente, detectadas por las pruebas: una en el núcleo (el tipo de cantidad por monto se infería como cantidad) y una en una prueba.

### Hallazgos de la etapa

- La notación escrita en `notacion-v0.md` pasó por el validador casi sin cambios. Hubo que precisar tres cosas: cómo se ve cada elemento de una colección (`cada/x`), la forma de `:caso` y que una entrada opcional no entregada no es un dato que falta.
- `[:ref :proveedor]` choca con el `:ref` propio de Malli. Se resolvió reemplazándolo en un registro propio; no cambió la notación.
- XTDB v2 exige JDK 21 o superior y la máquina tenía JDK 17. Se instaló Temurin 21.0.13 aparte, sin tocar el PATH; las pruebas de la etapa 1 pasan igual con JDK 17 y con JDK 21.
