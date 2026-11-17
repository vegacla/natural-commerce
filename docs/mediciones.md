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

## Etapa 2: hechos y compromisos sobre XTDB (2026-11-17)

XTDB 2.2.0, nodo en el mismo proceso y en memoria, con JDK 21.

### Líneas del núcleo

| Archivo | Qué hace | Líneas |
|---|---|---|
| `src/nc/almacen.clj` | Escritura, lectura, historia y versiones vigentes sobre XTDB | 175 |
| **Total del núcleo** (etapas 1 y 2) | | **918** |

Pruebas: 422 líneas en total; 6 pruebas nuevas del almacén. Las 22 pruebas, con 112 comprobaciones, pasan.

### Lo que XTDB entregó sin diseño adicional

- **Historia de cualquier dato (D17):** cada escritura guarda la versión anterior con sus dos tiempos. Ver un compromiso como estaba en una fecha es una opción de la consulta.
- **Validez futura (H9):** una política cargada hoy con `:rige-desde` en 30 días no rige hasta entonces, y se puede preguntar qué regirá en una fecha según lo que se sabía en otra.
- **Atomicidad:** un `ASSERT` de SQL en la misma transacción descarta todo si falla. Se usó para que un hecho no se modifique (D16); es el mismo mecanismo que necesitará E7.
- **Valores del dominio:** `[:monto 30000 :CLP]`, palabras clave y mapas anidados se guardan y vuelven sin conversión.

### Rodeos al almacén

1. **Tablas que no existen.** XTDB crea una tabla con su primera escritura, y su SQL rechaza nombrar una tabla que no existe, incluso dentro de un `ASSERT`. Rodeo: al abrir el almacén se escribe y se borra (sin historia) un documento en cada tabla. 4 líneas.
2. **Precisión del tiempo de registro.** XTDB registra microsegundos; `java.util.Date`, que usa el núcleo para las fechas del dominio, solo milisegundos. Convertir el tiempo de una transacción a `Date` y consultar `:al` ese instante dejaba fuera la propia transacción. Rodeo: los tiempos de XTDB se devuelven como `Instant`. 6 líneas.
3. **Versiones publicadas fuera de orden.** Una escritura con `:valid-from` y sin `:valid-to` rige hasta el fin del tiempo, y pisa una versión posterior ya cargada. Rodeo: al publicar una versión se busca la siguiente y se le pone `:valid-to`. 7 líneas. Es semántica bitemporal normal, no un defecto de XTDB, pero el núcleo tuvo que resolverla.

Ninguno obligó a cambiar el modelo ni la notación.

### Correcciones

- Humanas: ninguna.
- Del agente, detectadas por las pruebas: los dos primeros rodeos, y una prueba que consultaba `:al` un instante anterior a la publicación que quería ver.

### Hallazgos de la etapa

- La separación por empresa (D22) se hizo con un campo `:empresa` en cada documento y un filtro obligatorio en cada lectura. Los identificadores de políticas, conceptos, caminos y saldos se derivan de la empresa, así que dos empresas pueden usar el mismo nombre. XTDB 2.1 agregó bases de datos múltiples en un nodo, que serían otra forma de separar empresas; queda para evaluar con E9.
- Toda prueba que use XTDB necesita JDK 21 y las opciones de JVM del alias `:test`. Con el JDK 17 del PATH, las pruebas del almacén no arrancan.
- Las pruebas usan el reloj real y no fijan el tiempo de registro: XTDB exige que sea creciente, y un tiempo de registro en el futuro deja los datos invisibles para una consulta de hoy.

## Etapa 3: resolvedor y ejecutor (2026-11-17)

Escenarios E1, E2 y E3, sobre XTDB 2.2.0 en memoria.

### Líneas del núcleo

| Archivo | Qué hace | Líneas |
|---|---|---|
| `src/nc/estado.clj` | Vista de una empresa: lecturas, caso, políticas en la versión que rige | 100 |
| `src/nc/resolvedor.clj` | Requisitos de un objetivo: satisfecho, pendiente, opcional, prohibido, indeterminado | 173 |
| `src/nc/ejecutor.clj` | Aplica los efectos declarados de cualquier contrato | 171 |
| Cambios en `almacen.clj` y `esquema.clj` | Símbolos en el almacén; campos en orden | 11 |
| **Total del núcleo** (etapas 1 a 3) | | **1.373** |

Pruebas: 619 líneas en total; 4 pruebas nuevas (E1, E2, E3 y validación de entradas). Las 26 pruebas, con 167 comprobaciones, pasan.

### Líneas por operación

Siete contratos en `resources/canon/compras.edn`, ninguno con código propio (D66):

| Contrato | Líneas de EDN |
|---|---|
| registrar solicitud | 15 |
| registrar oferta | 24 (una más que en la etapa 1: declara su saldo) |
| aprobar | 9 |
| emitir orden | 28 |
| registrar recepción | 13 |
| registrar factura | 12 |
| consultar estado | 10 |

Agregar registrar recepción y consultar estado costó 23 líneas de contrato y 10 de tipo de hecho, y ninguna de núcleo. El núcleo creció por el resolvedor y el ejecutor, que sirven a todas.

### Rodeos al almacén

4. **Símbolos.** XTDB no guarda símbolos de Clojure, y las expresiones de políticas, conceptos y caminos los usan como referencias (`entrada/cantidad`, `actor`). Rodeo: al escribir, cada símbolo se guarda como `{:nc/simbolo "entrada/cantidad"}` y al leer vuelve a ser símbolo. 7 líneas. No cambió la notación.

### Correcciones

- Humanas: ninguna.
- Del agente: ninguna detectada por las pruebas; pasaron en la primera corrida. Para descartar pruebas que no prueban nada, se revisaron a mano tres respuestas reales del resolvedor.

### Hallazgos de la etapa

- **P5 y P6 se resolvieron** (D65, D66). El ejecutor es uno solo y no conoce ninguna operación: aplica cinco verbos y mueve saldos según el tipo de hecho.
- **El caso se reconoce por los datos** (D68). Sin instancias de proceso, basta con que cada hecho guarde su raíz y cada compromiso la suya. En E2 los cuatro hechos quedan en el mismo caso, y la consulta de estado los encuentra con `[:hechos {:caso caso/id}]`.
- **La vigencia por caso funciona sin código especial**: la raíz anota `compra-formal` v3 al abrirse, y la orden se evalúa con esa versión.
- **La respuesta del resolvedor necesitó dos datos que la notación no tenía**: qué política origina cada requisito (`:politica`) y qué entrada llena el paso pendiente (`:entrega`, D71). Sin `:entrega`, SARA no sabría que la salida de "registrar solicitud" es la entrada `:solicitud` de "emitir orden". Es lo que P12 debe confirmar en la etapa 6.
- **Valor por defecto del permiso** (D67): sin una política "permite", cualquiera de la empresa puede ejecutar. Hacía falta decidirlo para que la Empresa A opere sin políticas.

### Pendiente para etapas siguientes

- **Verificar y aplicar en la misma transacción (D49).** Hoy el resolvedor verifica antes de escribir; la transacción solo asegura que un hecho no se repita. E7 exige que la condición se vuelva a comprobar dentro de la transacción.
- Formas que el resolvedor aún no evalúa: sugiere, fija, limita e inicia. Opción de vigencia `:cierre-del-periodo`.
- Verbos que el ejecutor aún no aplica, porque ninguna operación los usa: los tres de entidad, `:revierte-hecho` y `:transfiere-compromiso`. Reducir varios compromisos a la vez no está definido y hoy es un error.
- El resolvedor no usa caminos guardados (D54); llega con E8.
