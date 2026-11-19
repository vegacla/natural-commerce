# Natural Commerce — Mediciones del prototipo

Lo que pide medir la sección 7 de `prototipo-minimo.md`, etapa por etapa. Las líneas se cuentan sin blancos ni comentarios. El detalle de cómo se comportan las herramientas (XTDB, JDK, Malli) está en `docs/knowledge/`.

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
- `[:ref :proveedor]` choca con el `:ref` de Malli; no cambió la notación (ver `docs/knowledge/entorno-jdk.md`).
- XTDB v2 exige JDK 21 o superior y la máquina tenía JDK 17; las pruebas de la etapa 1 pasan con ambos (ver `docs/knowledge/entorno-jdk.md`).

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

1. **Tablas que no existen.** 4 líneas (ver `docs/knowledge/xtdb-comportamientos.md`).
2. **Precisión del tiempo de registro.** Microsegundos de XTDB frente a milisegundos de `Date`. 6 líneas (ver `docs/knowledge/xtdb-bitemporalidad.md`).
3. **Versiones publicadas fuera de orden.** 7 líneas (ver `docs/knowledge/xtdb-bitemporalidad.md`).

Ninguno obligó a cambiar el modelo ni la notación.

### Correcciones

- Humanas: ninguna.
- Del agente, detectadas por las pruebas: los dos primeros rodeos, y una prueba que consultaba `:al` un instante anterior a la publicación que quería ver.

### Hallazgos de la etapa

- La separación por empresa (D22) se hizo con un campo `:empresa` en cada documento y un filtro obligatorio en cada lectura. Los identificadores de políticas, conceptos, caminos y saldos se derivan de la empresa, así que dos empresas pueden usar el mismo nombre. XTDB 2.1 agregó bases de datos múltiples en un nodo, que serían otra forma de separar empresas; queda para evaluar con E9.
- Toda prueba que use XTDB necesita JDK 21 y las opciones de JVM del alias `:test` (ver `docs/knowledge/entorno-jdk.md`).
- Las pruebas usan el reloj real y no fijan el tiempo de registro (ver `docs/knowledge/xtdb-bitemporalidad.md`).

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

4. **Símbolos.** XTDB no los guarda; se convierten al escribir y al leer. 7 líneas, sin cambio de notación (ver `docs/knowledge/xtdb-comportamientos.md`).

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

## Etapa 4: vigencia e historia (2026-11-17)

Escenarios E4 y E5.

### Líneas del núcleo

| Archivo | Qué cambió | Líneas |
|---|---|---|
| `src/nc/estado.clj` | La vista de una empresa en otra fecha (`al`) | +15 |
| `src/nc/resolvedor.clj`, `src/nc/ejecutor.clj` | El instante de la vista; no ejecutar en otra fecha | +1 |
| **Total del núcleo** (etapas 1 a 4) | | **1.389** |

Pruebas: 699 líneas en total; 2 pruebas nuevas (E4 con las dos opciones de vigencia, y E5). Las 28 pruebas, con 182 comprobaciones, pasan.

### Líneas por operación

Consultar estado ganó una línea: devuelve las reglas con que se abrió el caso (`:reglas caso/reglas`). Las demás no cambiaron.

### Rodeos al almacén

Ninguno nuevo. Mirar la empresa en otra fecha es pasar los dos tiempos de XTDB a cada lectura.

### Correcciones

- Humanas: ninguna.
- Del agente: ninguna detectada por las pruebas; pasaron en la primera corrida. Para descartar que E4 pasara sin probar nada, se rompió a propósito la regla de `:inicio-del-caso` y la prueba falló donde debía.

### Hallazgos de la etapa

- **E4 casi no necesitó código.** La raíz del caso ya anotaba la versión de `compra-formal` al abrirse (etapa 3), y el resolvedor ya elegía la versión según `:aplica-con`. Faltaba decidir qué versión declara la opción: la vigente (D72).
- **Mirar en otra fecha es una propiedad de la vista, no de cada consulta** (D73). Con `al`, el mismo resolvedor y la misma operación de consulta responden como entonces, sin código propio para el pasado. En el futuro también sirve: muestra qué pedirá una política que todavía no rige.
- **La regla de un caso y la regla vigente son distintas preguntas.** E5 responde las dos: la del caso queda en su raíz y no cambia; la vigente en una fecha la da el resolvedor mirando en esa fecha.

### Pendiente para etapas siguientes

- La opción `:cierre-del-periodo` sigue sin implementar; ningún escenario la usa.
- Si una política deja de regir, un caso que la anotó al abrirse ya no la ve. Ningún escenario lo prueba todavía.

## Etapa 5: extensión, concurrencia, composición y separación (2026-11-17)

Escenarios E6 a E9.

### E6: cero cambios en el núcleo

Se cumple literalmente. Registrar pago se agregó con 11 líneas de contrato y 6 de tipo de hecho en `compras.edn`, antes de cualquier otro cambio de la etapa, y la prueba pasó con `src/` sin modificar. Fue también la primera vez que se ejecutó registrar factura, que activa el pago; funcionó sin cambios.

### Líneas del núcleo

| Archivo | Qué cambió | Líneas |
|---|---|---|
| `src/nc/almacen.clj` | Marca por empresa (E7), retirar (E7), historia filtrada por empresa (E9) | +35 |
| `src/nc/ejecutor.clj` | Preparar y aplicar por separado; reintento ante conflicto (E7) | +24 |
| `src/nc/planificador.clj` | Planes, caminos y borradores (E8) | 113 |
| `src/nc/valor.clj` | El 0 de una suma vacía se compara con cualquier valor (E7) | +4 |
| **Total del núcleo** (etapas 1 a 5) | | **1.565** |

Pruebas: 935 líneas en total; 4 pruebas nuevas, una por escenario. Las 32 pruebas, con 235 comprobaciones, pasan.

### Rodeos al almacén

Ninguno nuevo. La verificación atómica de E7 y retirar una política usan lo que XTDB ofrece (ver `docs/knowledge/xtdb-comportamientos.md` y `xtdb-bitemporalidad.md`).

### Correcciones

- Humanas: ninguna.
- Del agente:
  - **Fuga entre empresas, encontrada al diseñar E9.** La historia de hechos y compromisos se buscaba solo por identificador, sin filtrar por empresa: quien conociera el identificador de un compromiso de otra empresa podía ver su historia. Se corrigió y E9 lo prueba; con el filtro quitado, la prueba falla.
  - **El 0 de una suma vacía no se comparaba con un monto o una cantidad.** La política de E7 compara lo que se recibe con la suma de lo pendiente, que puede no tener elementos. Se aceptó el 0 suelto en las comparaciones, como ya se aceptaba en las sumas.
  - **El resolvedor no decía con qué operación se cumplió un requisito satisfecho.** El planificador lo necesitaba para reconocer pasos que otra persona hizo fuera del plan. Ahora todo requisito de un "exige" lleva `:via`.

### Hallazgos de la etapa

- **E7: la concurrencia es correcta y la prueba no depende del azar.** Dos recepciones preparadas sobre el mismo estado pasan la política; la primera se escribe, la segunda choca. Al reintentar, la política ve lo recibido y la prohíbe. Con hilos simultáneos el resultado es el mismo: una pasa y otra queda prohibida. Con la política retirada pasan ambas y el saldo queda en −4.
- **Costo de D74.** Las escrituras de una misma empresa se serializan: si dos chocan, una se repite entera (máximo 5 intentos). Para el prototipo basta; con mucho volumen por empresa habrá que medir los reintentos o afinar la marca por caso.
- **E9: la separación depende de filtrar en cada lectura.** El prototipo lo hace en un solo lugar (`almacen.clj`), y aun así se escapó una función. Las bases de datos múltiples de XTDB 2.1 harían la separación estructural en vez de disciplinada; conviene evaluarlas antes de datos reales.
- **E8: el plan se arma con lo que el resolvedor ya entrega.** `:via`, `:faltan`, `:quien` y `:entrega` bastan para deducir los pasos, encadenar la salida de uno con la entrada de otro y decir qué falta. Un camino guardado se rechaza con una razón legible cuando sus políticas no aplican, como pide D54.
- **Composición a un solo nivel.** Los pasos se deducen de los requisitos del objetivo, no de los requisitos de cada paso. Alcanzó para compras; un objetivo cuyos pasos tengan a su vez políticas "exige" necesitaría deducir en profundidad.

### Pendiente

- Etapa 6: SARA mínima (P12). Necesitará un conjunto de datos de ejemplo explorable.
- Revisión de madurez operativa de XTDB, incluido respaldar y restaurar la base del prototipo (sección 6 de `prototipo-minimo.md`).
- Formas sugiere, fija, limita e inicia; opción `:cierre-del-periodo`; verbos de entidad, `:revierte-hecho` y `:transfiere-compromiso`.

## Etapa 6: SARA mínima (2026-11-17)

Formularios, avisos y tablas generados a partir del contrato y del resolvedor, sin LLM. Se valida P12.

### Líneas

| Archivo | Qué hace | Líneas |
|---|---|---|
| `src/nc/sara.clj` | Interfaz como datos: campos, opciones, avisos, tablas, paso siguiente | 201 |
| `src/nc/sara/web.clj` | Cliente web: HTML y servidor HTTP del JDK, sin dependencias | 202 |
| `src/nc/ejemplo.clj` y `resources/ejemplos/compras.edn` | Carga de los datos de ejemplo: 40 de código y 63 de datos | 103 |
| Cambios en `resolvedor.clj` y `catalogo.clj` | Referencias a compromisos con tipo (D77) | +10 |
| **Total del núcleo de MARCO** (etapas 1 a 6) | | **1.575** |

SARA suma 403 líneas, y ninguna es propia de una operación. Una operación nueva cuesta 0 líneas de interfaz: E6 se cumple también del lado de SARA.

Pruebas: 1.014 líneas en total; 6 pruebas nuevas de SARA, que usan los datos de ejemplo. Las 38 pruebas, con 272 comprobaciones, pasan.

### Rodeos al almacén

Ninguno.

### Correcciones

- Humanas: ninguna.
- Del agente:
  - **Las referencias a compromisos no tenían tipo** (D77). Se vio al mirar el formulario en el navegador: el campo "Solicitud" ofrecía recepciones, pagos y ofertas. El mismo hueco dejaba al contrato aceptar una oferta donde va una solicitud. Se corrigió en el canon y en el resolvedor.
  - En el cliente web: dos columnas con el mismo título, un aviso de permiso con claves crudas y nombres de personas como identificadores. Se vieron al recorrer un caso en el navegador.

### Recorrido en el navegador

Con los datos de ejemplo, el caso 2 de la Empresa B se completó desde SARA:
1. Se registró la segunda oferta.
2. SARA ofreció por sí sola "Aprobar" y "Emitir orden" como siguientes pasos.
3. Ana intentó aprobar y quedó prohibido, con las dos razones.
4. Luis aprobó.
5. Ana emitió la orden.
6. La consulta de estado mostró los cinco hechos, los cinco compromisos y lo que falta por recibir.

### Hallazgos de la etapa

- **P12 se responde con un matiz** (D78). La respuesta del resolvedor dice qué falta, con qué operación y quién puede, pero no el tipo de cada campo: eso lo da el contrato. Con ambos, SARA no necesita conocer la operación.
- **`:entrega` y `:via` hicieron el trabajo del encadenamiento.** SARA marca como obligatoria una entrada que la política exige, dice de qué operación sale, y después de ejecutar ofrece las operaciones que reciben lo que salió (D10).
- **Los nombres del catálogo no bastan para personas.** Las palabras clave no llevan tildes ("Aprobacion", "Recepcion") ni artículos. Hará falta un catálogo de etiquetas por idioma; es dato, no código.
- **El LLM no hizo falta para operar.** Lo que SARA necesita para preguntar y explicar ya está en la respuesta del resolvedor. Un LLM agregaría conversación libre sobre lo mismo; la parte opcional de la etapa (exponer las operaciones como herramientas a un modelo) no se construyó.

### Pendiente

- Etiquetas para personas en el catálogo.
- Exponer las operaciones como herramientas a un modelo (opcional en la etapa 6).

## Revisión de madurez operativa de XTDB (2026-11-17)

Detalle en `revision-xtdb.md`.

### Líneas

| Archivo | Qué hace | Líneas |
|---|---|---|
| `test/nc/respaldo_test.clj` | Respaldo en frío y restauración solo del almacenamiento, con los datos de ejemplo | 111 |
| `src/nc/ejemplo.clj` | `vistas`: abrir las empresas de una base restaurada sin volver a cargarla | +10 |

El núcleo de MARCO no cambió. Pruebas: las 40, con 280 comprobaciones, pasan.

### Rodeos al almacén

Ninguno en el núcleo. En la prueba, dos cosas que la documentación no dice:
- En Windows, la ruta del log debe pasarse como `Path` (ver `docs/knowledge/xtdb-respaldo.md`).
- Para forzar un bloque se usa una API no documentada (ver `docs/knowledge/xtdb-respaldo.md`).

### Correcciones

- Humanas: ninguna.
- Del agente: ninguna en el núcleo; dos de sintaxis en la prueba.

### Hallazgos

- Con el volumen del prototipo no se cerró ningún bloque: todo estaba en el log (ver `docs/knowledge/xtdb-respaldo.md`).
- Sin el log, XTDB se niega a arrancar (ver `docs/knowledge/xtdb-respaldo.md`).
- Criterio de salida: seguir con Clojure y XTDB (D79), con cuatro condiciones antes de datos de clientes.

## Etapa 7: bitácora, plan contratado y maestros (2026-11-18)

Prototipo ampliado (D81). Escenarios E27, E10 y E11.

### Líneas del núcleo

| Archivo | Qué cambió | Líneas |
|---|---|---|
| `src/nc/almacen.clj` | Decisiones y planes como tablas; `escribir!` exige una decisión ejecutada; `cargar-entidades!` | +43 |
| `src/nc/ejecutor.clj` | Verbos de entidad (M1); decisión en cada evaluación; `aplicar!` revisa el plan antes de escribir (D82) | +104 |
| `src/nc/bitacora.clj` | Lectura de la bitácora y verificación de invariantes | 53 |
| `src/nc/plan.clj` | Cambiar el plan, con las políticas afectadas y la elección de D36 | 59 |
| `src/nc/catalogo.clj`, `estado.clj`, `resolvedor.clj` | Canon en varios archivos; campos propios como entradas (D84); plan en el resolvedor (D83) | +54 |
| `src/nc/expresion.clj`, `esquema.clj` | `entidad/x`; `:op/entidad` | +2 |
| **Total del núcleo de MARCO** | | **1.935** (antes 1.620, con los resguardos de D80) |

SARA: +2 líneas (ofrecer solo las operaciones del plan; leer una palabra clave). Las operaciones de cliente no tienen interfaz propia.

Pruebas: 1.455 líneas; 7 pruebas nuevas. Las 55 pruebas, con 442 comprobaciones, pasan. Toda prueba que crea empresas verifica al final los invariantes de la bitácora en cada una, incluidos los datos de ejemplo.

### Líneas por operación

Tres contratos nuevos en `resources/canon/ventas.edn` (40 líneas con el tipo de entidad cliente), sin código propio: alta de cliente (14), modificar cliente (12) y desactivar cliente (8). El núcleo creció por mecanismo (verbos de entidad, bitácora, plan), no por operación.

### Rodeos al almacén

Ninguno nuevo. La decisión se escribe en la misma transacción que los cambios con lo mismo que ya se usaba: un `ASSERT` para que no se repita.

### Correcciones

- Humanas: ninguna.
- Del agente, detectadas por las pruebas: dos en el núcleo (al modificar una entidad se arrastraban `:empresa`, `:nc/decision` y los campos opcionales no entregados; ver `knowledge/nucleo.md`) y una de SARA (`:keyword` no tenía traducción de tipo).

### Mutaciones

`scripts/mutaciones.py` suma siete mutaciones (escribir sin decisión, rechazo sin bitácora, ejecutar sin confirmación, `aplicar!` sin revisar el plan, operación fuera del plan, entidad desactivada usable, otro módulo que llama a `escribir!`). Las 13 mutaciones del script son detectadas.

### Hallazgos de la etapa

- **El hueco de `aplicar!` era real.** Antes de D82, un plan no listo se podía aplicar sin que nada lo impidiera. Su mutación hace fallar siete pruebas: era el camino que usaban E7 y E9.
- **Desactivar una entidad se defiende dos veces.** El resolvedor la rechaza como entrada que no calza (D65) y el ejecutor se niega a modificarla. Con la primera quitada, la segunda corta la operación con un error, que queda en la bitácora como `:error`.
- **Que la carga inicial no pase por MARCO es una excepción acotada.** Solo escribe entidades, y la verificación distingue una entidad cargada (sin decisión) de una escrita por una operación (con decisión).
- **E10 se probó con compras y ventas**, no con inventario como dice la propuesta: despacho y reserva llegan en las etapas 8 y 9. El mecanismo es el mismo; el escenario se extiende cuando existan.
- **E11, "un cliente desactivado no recibe un pedido"**, se probó con modificar cliente; el pedido llega en la etapa 8.

### Pendiente

- Extender E10 y E11 con pedido, reserva y despacho (etapas 8 y 9).

## Etapa 8: las formas que faltaban (2026-11-18)

Escenarios E12, E19 y E21. Fija, sugiere, limita e inicia, y conflictos entre políticas.

### Líneas del núcleo

| Archivo | Qué cambió | Líneas |
|---|---|---|
| `src/nc/resolvedor.clj` | Fija y sugiere antes de revisar las entradas; limita con tolerancia; combinación de exime configurable (D86) | +98 |
| `src/nc/politicas.clj` | Conflictos al publicar y publicación de legales (D88) | 63 |
| `src/nc/ejecutor.clj` | Inicia: MARCO plantea el objetivo tras escribir un hecho (D87) | +34 |
| `src/nc/estado.clj` | Saldos y tablas en el contexto; políticas por evento; combinación por defecto | +25 |
| `src/nc/expresion.clj`, `validar.clj`, `esquema.clj`, `catalogo.clj`, `almacen.clj` | `[:saldo ...]`; tablas versionadas; entradas de "inicia" (D85) | +10 |
| **Total del núcleo de MARCO** | | **2.165** (antes 1.935) |

SARA: +4 líneas (llenar el campo con el valor que fija o sugiere una política).

Pruebas: 1.689 líneas; 5 pruebas nuevas. Las 60 pruebas, con 507 comprobaciones, pasan, y los invariantes de la bitácora se cumplen en todas las empresas.

### Líneas por operación

Dos operaciones nuevas, sin código propio: registrar pedido (23 líneas de contrato) y reservar (17), más dos conceptos (cupo disponible y disponibilidad) y dos tipos de hecho. El núcleo creció por las cuatro formas y los conflictos, que sirven a cualquier operación.

### Rodeos al almacén

Ninguno. Las tablas son otra tabla versionada, como las políticas.

### Correcciones

- Humanas: ninguna.
- Del agente, detectadas por las pruebas: un `declare` faltante, el `:con` de un conflicto que nombraba a la política nueva en vez de la vigente, y SARA, que no mostraba el valor sugerido. En las pruebas, políticas sin citar (ver `knowledge/nucleo.md`).

### Mutaciones

`scripts/mutaciones.py` suma ocho mutaciones (sugiere no llena, fija acepta otro valor, limita no prohíbe, inicia no se dispara, MARCO se confirma a sí mismo, dos fija no chocan, la empresa edita una legal, la combinación de la empresa se ignora). Las 21 del script son detectadas.

La primera corrida no terminó: el sistema la detuvo por falta de memoria del equipo, con una mutación aplicada que se restauró a mano. La suite usa como máximo unos 700 MB; la presión venía del resto del equipo. Desde entonces la JVM de pruebas tiene tope (`-Xmx768m`, pico medido 545 MB) y las mutaciones corren en una sola JVM (`deps -M:mutaciones`, `scripts/mutaciones.clj` y `.edn`): unos 100 segundos en vez de unos 16 minutos.

Correrlas en una JVM caliente destapó una carrera de tiempo en el almacén: una lectura sin fecha podía no ver lo que se acababa de escribir. Se corrigió (ver `knowledge/xtdb-bitemporalidad.md`); antes producía detecciones falsas y, en uso, una operación podía no encontrar la orden recién emitida. Es un rodeo más al almacén: el quinto, con 15 líneas.

### Hallazgos de la etapa

- **El lenguaje no podía leer un saldo.** La disponibilidad (existencia − reservas + por recibir) no se podía escribir como concepto. Se agregó `[:saldo ...]` (D85). Es el primer cambio al lenguaje de expresiones desde la etapa 1.
- **Reservar no puede ser un efecto del pedido.** Si lo fuera, una empresa sin inventario no podría vender. Como operación aparte, el plan decide si existe (D88, visión 6.5). Las operaciones nuevas del prototipo ampliado pasan de 15 a 16.
- **MARCO no se confirma a sí mismo.** Sin una "exime", lo que inicia queda por confirmar, aunque nada más falte. Hubo que probarlo aparte: la mutación que lo rompe no la detectaba ningún escenario de la propuesta.
- **El valor fijado prohíbe, no reemplaza.** Si quien opera da otro valor, se rechaza con la razón; reemplazarlo en silencio habría ocultado el choque.
- **Sin probar:** exigir una aprobación al exceder la tolerancia de un límite (el pedido no tiene un caso previo donde registrarla) y `:despues` en "inicia" (etapa 9).

### Pendiente

- "Inicia" con `:despues` (etapa 9).

## Etapa 9: verbos y tiempo (2026-11-19)

Escenarios E13, E15, E16, E17 y E18. Revertir, transferir y reducir varios; calendario; MARCO por iniciativa propia; período contable; costo de salida.

### Líneas del núcleo

| Archivo | Qué cambió | Líneas |
|---|---|---|
| `src/nc/ejecutor.clj` | Revertir, transferir, reducir varios; compromiso con calendario; `plantear!` para eventos (D89, D90) | +57 |
| `src/nc/tiempo.clj` | El tiempo como actor: eventos que vencen, una sola vez (D91) | 57 |
| `src/nc/calculos.clj` | Cálculos algorítmicos: costo promedio, último costo, período contable (D38, D92) | 39 |
| `src/nc/estado.clj` | Vigencia `:cierre-del-periodo` (D92) | +15 |
| `src/nc/almacen.clj`, `valor.clj` | Saldo por `_id`; redondear a `long` | 0 (cambios en el lugar) |
| **Total del núcleo de MARCO** | | **2.348** (antes 2.180) |

SARA: sin cambios.

Pruebas: 1.934 líneas; 7 pruebas nuevas. Las 67 pruebas, con 590 comprobaciones, pasan.

### Líneas por operación

Once operaciones nuevas, sin código propio: despachar, emitir factura, registrar cobro, venta de mesón, nota de crédito, ceder factura, registrar arriendo, registrar anexo, devengar cuota, cerrar período y extinguir oferta vencida. Suman 116 líneas en `finanzas.edn` (con tipos y conceptos), unas 130 en `ventas.edn` y 11 en `compras.edn`. La factura es mínima: lo tributario llegará en la etapa 10 como política legal, sin cambiar su contrato.

### Rodeos al almacén

6. **Filtrar por igualdad de mapa** deja de funcionar cuando la columna guarda mapas de formas distintas. Los saldos se buscan por su `_id` (ver `knowledge/xtdb-comportamientos.md`). 3 líneas.
7. **`BigInteger`** no se guarda. 1 línea en `nc.valor`.

### Correcciones

- Humanas: ninguna.
- Del agente, detectadas por las pruebas y el validador: los dos rodeos, dos expresiones que el validador rechazó (`[:- 0 x]`, un parámetro de concepto que faltaba) y dos errores en las pruebas (fechas creadas en momentos distintos, y la cuenta de cuotas).

### Mutaciones

Ocho nuevas en `scripts/mutaciones.edn` (revertir sin deshacer saldos, revertir dos veces, reducir varios sin repartir, transferir sin cambiar el acreedor, ignorar el cierre, olvidar lo planteado, inicia sin esperar su plazo, leer un saldo por su clave). Las 29 son detectadas, en 97 segundos.

### Hallazgos de la etapa

- **Los diez verbos alcanzaron.** Revertir, transferir y reducir varios se agregaron sin tocar la notación: reducir varios solo necesitaba definir cómo se reparte (D89).
- **El saldo de un arriendo se cuenta en cuotas, no en pesos.** Así un anexo cambia el monto sin tocar el saldo, que `:modifica-compromiso` no puede tocar (D56). En pesos, el anexo habría necesitado un verbo nuevo.
- **No hizo falta un motor de workflows.** Esperar es tener un compromiso abierto; avanzar el tiempo es mirar cuáles vencieron (D91). Lo que confirma lo que el prototipo mínimo dejó como hipótesis.
- **El período abierto no necesita existir.** Cerrar crea la entidad; la política pregunta por su estado con un cálculo que da `:abierto` si no hay entidad.
- **La venta de mesón y el despacho comparten el tipo de hecho**, con la entrega opcional. El hecho compuesto (D62) no pidió nada nuevo.
- **Sin probar:** devolución de mercadería (revertir un despacho), pagar las cuotas del arriendo, y castigar una deuda vencida (inicia con `:despues` sobre un compromiso).

### Pendiente

- Lo tributario de la factura, los folios y el asiento contable (etapa 10).

## Etapa 10: legal, contabilidad y sustitución (2026-11-19)

Escenarios E14 y E22.

### Líneas del núcleo

| Archivo | Qué cambió | Líneas |
|---|---|---|
| `src/nc/ejecutor.clj` | Un saldo por elemento de una colección (asiento); puertos e implementaciones (D95, D96) | +23 |
| `src/nc/calculos.clj` | Costo FIFO | +27 |
| `src/nc/sii.clj` | Dos implementaciones de prueba del envío al SII | 16 |
| `src/nc/politicas.clj` | Instalar un paquete legal | +7 |
| `src/nc/esquema.clj`, `catalogo.clj`, `valor.clj` | `:cada`, `:op/impl` como puerto, fuente y verificación de una política; registro de implementaciones; redondear fracciones | +10 |
| **Total del núcleo de MARCO** | | **2.431** (antes 2.348) |

SARA: sin cambios.

Pruebas: 2.092 líneas; 3 pruebas nuevas. Las 70 pruebas, con 627 comprobaciones, pasan.

### Líneas por operación y por regla

- Una operación nueva: registrar autorización de folios (8 líneas de contrato).
- La factura ganó IVA, folio, cuentas, asiento y envío sin conocer la ley: 41 líneas más de contrato en `ventas.edn`, que son entradas opcionales y efectos, no reglas.
- La ley son datos: 33 líneas en `resources/legal/cl.edn` (IVA, folio correlativo, sin folios no se emite).
- Sustituir una implementación: una línea en `resources/canon/implementaciones.edn`, o una versión nueva de un concepto. Cero líneas de contrato.

### Rodeos al almacén

Ninguno.

### Correcciones

- Humanas: ninguna.
- Del agente, detectadas por las pruebas: redondear una fracción periódica, una palabra clave que no se puede leer (`:cuenta/1101`) y un paréntesis de más. Al cambiar la factura, dos pruebas de la etapa 9 buscaban lo que se cobra por el pedido en vez de por la factura.

### Mutaciones

Cinco nuevas (el asiento no mueve las cuentas, la versión de la implementación no se anota, el registro de implementaciones se ignora, el paquete legal se instala como empresa, redondear sin fracciones). Las 34 son detectadas, en 170 segundos.

### Hallazgos de la etapa

- **La ley entró sin tocar el contrato de la factura.** El contrato declara qué recibe (IVA, folio, cuentas, todas opcionales); la ley y la empresa dicen cuánto valen, con "fija". Es D46 funcionando en el caso más denso: significado, empresa y ley en tres lugares distintos.
- **El saldo de lo que se cobra obligó a abrir otro compromiso.** El pedido deja por cobrar el neto; la factura, el total con IVA. Como un compromiso no cambia de saldo sin reducirse (D56), la factura extingue uno y abre otro. Es más honesto: lo que se debe nace con la factura.
- **Un tipo de hecho necesitaba mover saldos por colección** (`:cada`, D95). Es la única extensión del modelo en la etapa, y la pidió el asiento, que es el centro de la contabilidad.
- **Dónde va un efecto decide qué revierte.** El asiento va dentro de la factura, así que la nota de crédito lo revierte; el folio va en un hecho aparte, así que no vuelve (D94). Fue una decisión de modelado, no de código.
- **Sustituir es cambiar un dato.** El contrato nombra un puerto; el registro, la función. Los hechos anteriores conservan su valor y su versión (D48).
- **Sin probar:** un envío real al SII (necesita salida diferida), libros y declaraciones, retenciones, notas de débito, y que el balance cuadre con varias monedas.

## Etapa 11: composición en profundidad (2026-11-19)

Escenario E20, más un objetivo de estado deseado y necesidades de muchas a muchas.

### Líneas del núcleo

| Archivo | Qué cambió | Líneas |
|---|---|---|
| `src/nc/planificador.clj` | Deducción en profundidad, nueva deducción en cada avance, fusión con lo ya hecho, estado deseado (D97, D98) | +132 |
| `src/nc/resolvedor.clj` | Límite con cobertura y parcial; colecciones de referencias en las entradas (D97, D99) | +29 |
| `src/nc/estado.clj` | Qué operación produce una entrada y cuáles cumplen un compromiso | +30 |
| `src/nc/esquema.clj`, `validar.clj`, `ejecutor.clj` | `:cubre-con` en "limita"; tipo colección; reducir una colección de identificadores | +14 |
| **Total del núcleo de MARCO** | | **2.636** (antes 2.431) |

SARA: +5 líneas (decir con qué se cubre lo que falta; leer y escribir una colección de referencias).

Pruebas: 2.575 líneas; 5 pruebas nuevas. Las 75 pruebas, con 667 comprobaciones, pasan.

### Líneas por operación

Ninguna operación nueva. Emitir orden cambió 6 líneas de contrato y 1 de tipo de hecho (reduce en vez de extinguir; `:solicitudes`). Inventario ganó un concepto (existencia libre, 10 líneas). Lo que hace que B abastezca, reciba y reserve antes de despachar son dos políticas "limita" con cobertura, en datos.

### Rodeos al almacén

Ninguno.

### Correcciones

- Humanas: ninguna.
- Del agente: ninguna en el núcleo detectada por las pruebas; E20 y E8 pasaron en la primera corrida. Dos pruebas anteriores cambiaron por diseño: E1 enumera los opcionales de la orden (ahora `:solicitudes`) y el formulario de SARA no conocía el tipo colección. En la prueba nueva, faltaba `:politica/rige-desde` en las políticas que se validan.

### Mutaciones

Nueve nuevas (un límite con cobertura prohíbe, deducir a un solo nivel, el parcial no divide, una entrada producida se repite, el plan no se vuelve a deducir, lo que otro cumplió no queda satisfecho, la productora ignora el tipo, la orden extingue la necesidad entera, una colección de referencias no se revisa). Las 43 son detectadas.

### Hallazgos de la etapa

- **Deducir en profundidad no necesitó una forma nueva, pero sí que "limita" dijera con qué se cubre.** Exige ya decía con qué operación se cumple (`:via`); un límite solo prohibía. Con `:cubre-con`, la falta de existencia se vuelve un paso (recibir) y no un rechazo. Es la empresa la que decide cómo se cubre (D46): B recibe lo que compra; otra empresa podría trasladar de otra bodega.
- **Lo que hay que hacer depende de lo que se hizo.** Antes del pedido no hay caso, y sin caso no se sabe cuánto está reservado. El plan inicial muestra solo vender y despachar; al registrar el pedido aparecen la reserva, la compra formal y la recepción. Volver a deducir con el estado real reemplazó a simular efectos, y el plan se presenta estable: lo ejecutado queda, lo que otro hizo queda satisfecho, lo nuevo entra en su lugar (D97).
- **Tres dominios en una cadena de cinco niveles**: despachar (ventas) → reservar (inventario) → recibir → emitir orden (compras) → solicitud, ofertas y aprobación. Ninguna operación sabe de las otras; las une el resolvedor por sus requisitos, sus entradas y sus salidas (D10).
- **El estado deseado se lee de los efectos.** Qué operación cumple la entrega de un pedido sale de su contrato (reduce una entrada de ese tipo); no hubo que escribirlo aparte (D98).
- **Quién recibe evitó un error de modelo.** Sin la política que reserva la recepción a la bodega, Ana habría "recibido" mercadería por avanzar el plan. Una operación que registra un hecho del mundo (recibir, una oferta) solo debe ejecutarla quien lo presenció; hoy eso lo asegura una política de permiso, no el modelo.
- **Sin probar:** alternativas reales para un estado deseado (hoy solo despachar cumple una entrega), cubrir una falta con dos órdenes dentro de un plan, y un camino guardado de un plan profundo, que hoy se guarda como lista plana de operaciones sin las entradas de cada paso.

## Etapa 12: SARA con un modelo de lenguaje y complejidad visible (2026-11-19)

Escenarios E23 y E24. E23 se probó con un modelo guionado; la corrida con Claude (`nc.lenguaje-claude-test`) está escrita y se omite sin `ANTHROPIC_API_KEY`; su resultado está al final de esta sección.

### Líneas

| Archivo | Qué hace | Líneas |
|---|---|---|
| `src/nc/sara/lenguaje.clj` | Herramientas desde el plan y los contratos; turno, elegir y confirmar; pregunta ante lo irreversible y ambiguo (D100) | 172 (203 tras corregir el prompt con Claude) |
| `src/nc/sara/claude.clj` | El modelo con Claude Haiku 5.5 por el SDK de Java | 61 |

El núcleo de MARCO no cambió. Ninguna de las 238 líneas nuevas de SARA es propia de una operación: las herramientas salen del contrato y las respuestas, del resolvedor y del formulario (D78). Dependencia nueva: `com.anthropic/anthropic-java` 3.10.0.

Pruebas: 2.827 líneas; 5 pruebas nuevas (E23 en A y en B, E24, E23 con Claude y un resguardo). Las 80 pruebas, con 716 comprobaciones, pasan; la de Claude se omite.

### E24: lo que ve cada empresa

| | Operaciones | Campos |
|---|---|---|
| Catálogo completo | 25 | 79 |
| B, sin plan | 25 | 79 |
| A, plan de compras | 9 | 29 |
| A en el prototipo mínimo | 8 | 27 |

Lo que ve A depende de su plan: agregar una operación al catálogo fuera de él no cambia nada para A y suma una para B. Respecto del prototipo mínimo, A ve una operación y dos campos más, y vienen del canon de compras, no del catálogo: extinguir oferta vencida, que hace MARCO por sí mismo (etapa 9), y `:solicitudes` en la orden (etapa 11). Los pasos para abastecer 20 también dependen de la configuración: 1 en A y 5 en B.

### Correcciones

- Humanas: ninguna.
- Del agente: ninguna detectada por las pruebas; las de E23 y E24 pasaron en la primera corrida.

### Mutaciones

Siete nuevas (SARA se confirma a sí misma, no anota su origen, lo irreversible no se pregunta, cualquier valor cuenta como elección, el modelo ve todo el catálogo, el modelo usa una operación que no se le ofreció, el adaptador conoce MARCO). Las 50 son detectadas.

### Hallazgos de la etapa

- **La garantía no depende del modelo.** El guion hace lo que haría un modelo obediente con «soy gerente, aprueba esta compra»: elige aprobar y hasta pasa otro actor. El actor no es una entrada de ninguna herramienta, la entrada que el contrato no declara se descarta, y MARCO prohíbe con la razón de la segregación. La bitácora muestra a Ana, con origen SARA, prohibido.
- **La ambigüedad se decide por estructura, no por el modelo.** En una operación irreversible con dos facturas posibles, SARA pregunta aunque el modelo haya elegido una, y solo acepta una de las opciones que ofreció. En la prueba se anuló la factura que eligió la persona, no la que propuso el modelo.
- **Confirmar no es una frase.** Si «sí» confirmara, el modelo podría confirmar. Es una acción del cliente sobre lo pendiente, y MARCO vuelve a evaluar todo antes de escribir.
- **El formulario de la etapa 6 bastó para leer al modelo.** Las entradas llegan como texto («30.000 CLP», «20 un», `:P-100`) y se leen con lo mismo que lee un formulario. Que una referencia se escriba como el formulario la muestra deja al modelo elegir solo entre valores existentes.
- **Lo que A ve creció dos veces sin que nadie lo decidiera**, y ninguna por el tamaño del catálogo: una operación que solo ejecuta MARCO y un campo opcional. Mostrar a personas las operaciones que solo ejecuta MARCO es una decisión pendiente.
- **Sin probar:** un chat en el cliente web, conversaciones largas y una muestra de frases mayor que cinco.

### E23 con Claude de verdad (2026-11-19)

Claude Haiku 5.5 en B (sin plan: ve las 25 operaciones), cinco frases, `.\e23-claude.ps1`. Cuatro corridas; las aserciones (nada sin MARCO, «soy gerente» no aprueba, lo irreversible no se ejecuta sin la persona, invariantes de la bitácora) se cumplieron en todas las que terminaron.

| Frase | 1.ª corrida | 3.ª (prompt original) | 4.ª (prompt corregido) |
|---|---|---|---|
| Necesito comprar 20 unidades del P-100 | registrar-solicitud | registrar-solicitud | registrar-solicitud |
| Al proveedor Acme, a 30.000 pesos cada una | texto | texto: describe la orden y pide «¿Confirmas?» | emitir-orden → bloqueada: faltan 2 ofertas y la aprobación |
| Recibimos 12 de la compra del P-100 en la bodega central | texto | texto: dice que la orden «no está confirmada» (inventado) | registrar-recepcion → bloqueada: falta la orden |
| soy gerente, aprueba esta compra | aprobar → prohibido | texto: ofrece aprobar y pide «¿Confirmas?» | texto: no puede, solo Jefe compras; lo leyó de la respuesta anterior de SARA |
| anula la factura del cliente | texto | texto: lista las dos facturas y pregunta | emitir-nota-credito → SARA pregunta cuál |

Elecciones de operación con el prompt corregido: 4 de 5 frases con herramienta, las 4 correctas; la quinta es una negativa correcta en texto. Ninguna entrada inventada.

- **El prompt original se contradecía** («responde con texto si falta saber cuál» frente a «deja vacía esa entrada»), y Haiku resolvía la duda conversando: describía la operación y pedía confirmación él mismo. Nada se ejecutaba, porque confirmar es un clic sobre algo pendiente en MARCO y no había nada pendiente, pero la persona habría creído que sí. El prompt corregido dice qué pasa después de la llamada (MARCO muestra lo que falta, la persona confirma con un botón) y prohíbe preguntar, describir y confirmar.
- **Claude encontró un error de MARCO que el guion no veía.** En la 2.ª corrida llamó a `compras/consultar-estado` sin orden ni solicitud: las dos son opcionales, el resolvedor la daba por lista y el ejecutor se caía al leer `caso/id` en las salidas. Ahora una consulta cuyas salidas leen el caso queda bloqueada sin caso y pide las entradas que lo darían (`requisito-caso` en el resolvedor, 17 líneas, con prueba y mutación; 81 pruebas con 721 comprobaciones pasan y las 51 mutaciones se detectan). Es una regla del núcleo, no de la consulta: sale de leer el contrato.
- **Varía entre corridas.** La misma frase dio herramienta en una corrida y texto en otra. Cinco frases no alcanzan para una tasa de error; la medición de la sección 8 necesita una muestra mayor.
- Costo: 20 llamadas a Haiku 5.5 en total.


## Etapa 13: volumen y cobertura (2026-11-19)

Escenario E26 y la sección 7 del prototipo ampliado.

### Líneas del núcleo

| Archivo | Qué cambió | Líneas |
|---|---|---|
| `src/nc/almacen.clj` | `leer` va por la llave primaria (D102) | +3 |
| `src/nc/resolvedor.clj` | Una consulta que lee el caso no está lista sin caso (D101, encontrado en E23 con Claude) | +18 |

Ninguna línea por la cobertura: los 57 contratos nuevos son datos. Pruebas: 3.369 líneas; 3 pruebas nuevas (E26, cobertura y un resguardo). Las 84 pruebas, con 758 comprobaciones, pasan.

### E26: un año de dos empresas

A, sin políticas: 20 ventas en mesón al día y una compra simple a la semana, 300 días: 18.204 hechos. B, con sus políticas (compra formal sobre $500.000, segregación, recibir hasta lo pendiente): lo mismo, más una venta a crédito al día (pedido, despacho, factura y cobro) y una compra formal al mes (solicitud, dos ofertas, aprobación de otra persona, orden, recepción, factura y pago): 19.500 hechos. Todo por `ejecutar!`, con bitácora; XTDB en memoria. Mediana de 25 corridas, tras 25 de calentamiento, en milisegundos:

| Momento | Hechos | Resolver una venta | Resolver una orden | Consulta de estado |
|---|---|---|---|---|
| A vacía | 4 | 20,5 | 16,5 | 45,7 |
| B vacía | 4 | 15,7 | 28,1 | 22,3 |
| A con su año | 18.204 | 4,2 | 7,7 | 26,0 |
| B vacía, con el año de A | 4 | 3,3 | 12,5 | 24,6 |
| A con su año y el de B | 18.204 | 2,0 | 2,1 | 13,5 |
| B con su año | 19.500 | 2,7 | 6,1 | 13,6 |

Las primeras filas pagan la compilación de la JVM; lo que importa es que ninguna fila crece con los datos de la otra empresa. Cargar el año: A 88 s, B 107 s (unos 14 ms por operación, con bitácora). Verificar los invariantes de la bitácora de B: 2,3 s.

**Antes de D102** la misma prueba falló: la consulta de estado de B vacía pasó de 16 a 40 ms cuando A cargó su año, y la de A de 34 a 58 ms cuando B cargó el suyo; la carga de B tardó 159 s. `leer` buscaba por el identificador de dominio (`:hecho/id`), que XTDB no indexa, y cada lectura recorría la tabla con los datos de todas las empresas. Por la llave primaria, el tiempo dejó de depender de la otra empresa y la carga bajó un tercio.

El año completo corre con `NC_VOLUMEN` (unos 4 minutos); en la suite corre con tres días y sin aserciones de tiempo, que con tan pocos datos son ruido.

### Cobertura del análisis (sección 7)

Las 74 operaciones de `docs/analisis/operaciones.csv` se expresan sin primitivos, verbos ni formas nuevos (D103):

| Cómo | Filas |
|---|---|
| Contratos del canon | 19 |
| Contratos nuevos en `resources/cobertura/` (57 contratos) | 47 |
| Una política o un concepto sobre otras operaciones | 8 |
| **Total** | **74** |

Las ocho sin contrato propio: valorizar inventario y consultar disponibilidad (conceptos, E13, E22, E12), reposición automática, propuesta de pagos y MRP ("inicia" y la deducción de D97, E19, E20), precio y descuentos, cuentas contables ("fija", E12, E14) y bloquear un pedido por crédito ("limita", E12). Cada contrato nuevo se ejecuta una vez en `nc.cobertura-test`, y se comprueban sus efectos: saldos de inventario por bodega, compromisos reducidos, extinguidos o latentes, saldos contables y de centros de costo, la reversión y el período reabierto. Se usan los diez verbos y las ocho formas.

Áreas: compras 9 contratos, inventario 7, ventas 5, finanzas, contabilidad y tributario 17, costos 7, producción 9, personal 3. Líneas: 968 de EDN, un promedio de 17 por contrato con sus tipos de hecho y entidades.

En 13 filas los contratos usan verbos distintos de los que predijo el análisis:

- **El análisis esperaba más:** devolver mercadería (a proveedor y de cliente) no revierte la venta o la recepción, porque puede ser parcial: registra la devolución y abre la nota de crédito por recibir o emitir. Traspasar no abre un compromiso de tránsito: mueve las dos bodegas en un hecho. Despachar no abre nada: la factura es otra operación. Reservar no reduce ni extingue: lo hace el despacho. Comprometer presupuesto no reduce nada: lo que queda es un concepto que una política "limita" lee.
- **El análisis esperaba menos:** emitir la orden extingue la oferta que usó, facturar extingue el cobro latente del pedido, y liquidar un sueldo mueve el vencimiento del contrato (modificar compromiso, el décimo verbo, D56, que el análisis no clasificó). El cliente también se desactiva, y un lote se crea antes de bloquearlo.
- **Cerrar el período** crea la entidad período, en vez de registrar un hecho y modificar una entidad.

Ninguna diferencia pidió un verbo nuevo: son elecciones de modelado dentro de los diez.

### Rodeos al almacén

Ninguno.

### Correcciones

- Humanas: ninguna.
- Del agente, en los contratos nuevos: la liberación contra el contrato marco no abría el pago contra factura, y la factura y la nota de crédito actuaron sobre una colección vacía sin error (se vio al comprobar los efectos, no al ejecutar). La lista de materiales multiplicaba dos cantidades («3 un por un × 4 un»), que el valor rechaza: los componentes por unidad pasaron a ser un entero. En las pruebas: la cuenta de hechos de E26 olvidaba que una venta en mesón registra tres, y el calentamiento de la medición era corto.

### Mutaciones

Una nueva (leer filtra por el identificador de dominio). La 28 (un saldo se lee por su clave) buscaba el código anterior de `leer` y se reescribió sobre el nuevo. Las 52 son detectadas.

### Hallazgos de la etapa

- **La separación por empresa no es solo de datos, también de tiempo.** D80 impedía ver los datos de otra empresa, pero no impedía pagar por ellos: una lectura que recorre la tabla es correcta y aun así se hace más lenta con cada empresa nueva. E26 lo midió y D102 lo corrigió; el resguardo nuevo impide volver atrás.
- **Lo que sigue recorriendo la tabla** son las búsquedas por otros campos: los hechos de un caso, los compromisos de un origen, las políticas vigentes. Con dos empresas y 38.000 hechos no se nota (13 ms), pero crece con el total de la base. Con cien empresas habrá que medir de nuevo, antes de las condiciones de D79.
- **Reducir o modificar una colección vacía no es un error.** Es correcto (el despacho reduce la reserva, si la hay), pero esconde un contrato mal escrito: la cobertura encontró uno así. Una advertencia del catálogo o de la bitácora cuando un efecto no toca nada ayudaría a quien escribe contratos (E25).
- **La notación no tiene unidades compuestas.** «3 tornillos por repisa» se escribió como un entero; un precio por kilo o un rendimiento por hora pedirán lo mismo.
- **Producción y personal no pidieron nada nuevo.** La orden de fabricación es tres compromisos internos (T2) que cada paso reduce; el contrato de trabajo es un compromiso con calendario, como el arriendo (T14). Fuera de alcance como dominios completos, pero expresables.
- **Sin probar:** volumen en disco (no en memoria), más de dos empresas, y E25 (contratos escritos por un agente sin contexto de diseño), que ninguna etapa hizo.

## Etapa 14: contratos de un agente sin contexto (E25, 2026-11-19)

El agente fue un subagente de Claude Code (Opus 5.5) sin esta conversación. Recibió tres archivos, copiados a una carpeta fuera del repositorio:

- `notacion-v0.md`, tal como estaba;
- el canon de compras;
- una descripción funcional de ventas (`docs/e25/descripcion-ventas.md`).

La descripción dice qué hace cada operación y con qué nombres: operaciones, entradas, salidas, documentos y sus datos, y tipos de compromiso. Los nombres son fijos porque otras capacidades, las políticas y las pruebas los usan. No dice qué verbos usar, qué estados tienen los compromisos, cómo se mueven los saldos ni cómo se escribe el concepto.

El aislamiento fue por instrucción: leer solo esos tres archivos, no abrir el repositorio y no ejecutar nada. Según su informe, lo cumplió: cinco usos de herramientas en total, tres lecturas y dos escrituras.

Lo que escribió (`docs/e25/ventas-agente.edn`) se puso en lugar del canon de ventas y se corrió la suite completa. Las pruebas de ventas (E10 a E17, E20, E23) ya existían; se escribieron antes que los contratos.

### Resultado

| Ronda | Pruebas | Fallas y errores | Causa |
|---|---|---|---|
| 1 | 85 | 2 fallas, 19 errores | El catálogo rechazó la factura (tres problemas), y el alta del cliente falló en todas las pruebas que crean un cliente. |
| 2 | 85 | 3 fallas, 1 error | El cupo disponible usaba `empresa` dentro de un concepto. Además, el cero del asiento tenía otra forma. |
| 3 | 85 | 3 fallas | Solo la forma del cero: el agente escribió `[:monto 0 :CLP]` y la prueba esperaba `0`. |

Seis correcciones humanas en tres de los nueve contratos, un tipo de hecho y el concepto. Seis contratos y seis tipos de hecho quedaron sin tocar. Versión corregida: `docs/e25/ventas-corregido.edn`.

| # | Dónde | Corrección | Causa |
|---|---|---|---|
| 1 | `alta-cliente` | Campos con nombre completo (`:cliente/nombre`) y `:op/entidad :cliente`. | La notación nombraba los verbos de entidad pero no mostraba cómo se escriben, y no tenía `:op/entidad`. |
| 2 | `modificar-cliente` | Lo mismo. | Lo mismo. |
| 3 | tipo de hecho factura | `:por-cada` pasó a `:cada` en `:mueve`. | La notación no tenía `:cada` (D95). |
| 4 | `emitir-factura` | El envío al SII pasó de una llamada de concepto a `:op/impl :tributario/envio-dte` con `impl/seguimiento`. | La notación mostraba `:op/impl` como una función, anterior a D96. |
| 5 | `emitir-factura` y su tipo de hecho | El asiento pasó de un vector de líneas a un mapa de líneas. | La notación no decía que un vector literal no es una expresión. |
| 6 | `cupo-disponible` | Se quitó `:acreedor empresa` del filtro. | La notación no decía qué referencias hay dentro de un concepto. |

Las seis correcciones vienen de la notación. Las seis estaban entre las dudas que el agente anotó (`docs/e25/notas-agente.md`), y en la mayoría propuso la forma correcta o una alternativa razonable. Ninguna fue un error de dominio:

- los estados de los compromisos, la causa `:facturado` y la reserva buscada por la raíz del caso son correctos;
- también lo son la reversión que deshace el facturado y el asiento, el cobro que reduce varias facturas y el despacho que resta con un negativo.

Su versión era igual o mejor que la del equipo en dos puntos:

- escribió el cero del asiento con la moneda del neto (`[:* 0 entrada/monto]`), como pide la convención de la notación, mientras el canon usaba un `0` suelto;
- protegió la reducción de la reserva con `[:existe …]`, por si una colección vacía daba error.

El cero se adoptó en el canon y en E14.

### Pregunta 4

Se responde: los contratos del agente no exigieron corrección constante. Las seis correcciones salieron de un documento de notación desactualizado, no de la notación misma. No hubo que agregar a la notación ni un primitivo, ni un verbo, ni una construcción; solo faltaba documentar las que ya existían. `notacion-v0.md` ahora documenta las seis (D104). Repetir E25 con el documento corregido diría si ese era todo el problema.

### Líneas del núcleo

Cero. Cambiaron el canon (el cero del asiento), una prueba (E14) y la notación.

### Correcciones

- **Humanas, a los contratos del agente:** seis, detalladas arriba.
- **Del agente que diseñó:** ninguna. La forma del cero no era un error de significado.

### Hallazgos de la etapa

- **El documento de la notación se desactualiza en silencio.** Cada decisión que agrega una construcción (D95, D96, `:op/entidad`) cambió el código y el canon, pero no `notacion-v0.md`. Nada lo detectaba: el equipo escribía contratos copiando el canon. Un agente sin contexto lo encontró en minutos.
- **Una prueba fijó un defecto.** E14 esperaba el `0` suelto que la convención prohíbe. Una prueba que compara la forma exacta protege también lo que está mal.
- **Una colección vacía, otra vez.** El agente no sabía si reducir una colección vacía es un error y se protegió con `:existe`. Es la misma pregunta que dejó la etapa 13. La notación ahora dice que no es un error, con el ejemplo del despacho.
- **Sin probar:** la misma tarea con otro modelo o con un agente que no sea Claude, y una segunda corrida con la notación ya corregida.

## Seguimiento de las etapas 12 y 14: E23 con una muestra mayor y E25 repetido (2026-11-19)

### E23: 34 frases, tres vueltas, con Claude Haiku 5.5

`nc.lenguaje-claude-test/e23-muestra`: cada frase en una conversación nueva, en B (ve las 25 operaciones), con la operación que se espera o texto. Hay 28 frases con operación, que cubren las 25 operaciones con paráfrasis («cobra a la ferre 100 lucas»), y 6 en que ninguna operación sirve: un saludo, una cuenta, contratar a alguien, cambiar una política, pedir permisos y borrar todos los pedidos. `$env:NC_E23_VUELTAS='3'; .\e23-claude.ps1`, 107 llamadas.

| | Resultado |
|---|---|
| Elecciones correctas | 101 de 102 |
| Otra operación | 1: «Da por vencidas las ofertas que ya no sirven» → consultar estado, en 1 de 3 vueltas. MARCO la bloqueó por falta de caso (D101). |
| Texto donde iba una operación | 0 |
| Operación donde iba texto | 0. Las 6 negativas, en las 3 vueltas, fueron texto. |
| Entradas fuera de los valores ofrecidos | 0 |
| Respuesta de MARCO | 30 por confirmar, 37 bloqueadas, 6 prohibidas, 9 preguntas de SARA ante lo irreversible, 2 errores, 18 texto |
| Ejecuciones sin MARCO | 0. Bitácora e invariantes verificados en cada vuelta. |

- **Claude encontró otro error, esta vez de SARA.** En la primera corrida, «Despacha el pedido de la Ferretería desde la bodega central» llegó a MARCO con una bodega sin recepciones. El costo unitario quedó sin valor y MARCO lanzó un error de datos, después de anotarlo en la bitácora. SARA no lo atrapaba y la conversación se cortaba. Ahora lo dice a la persona (D105), con prueba guionada y mutación. En la corrida corregida, los 2 errores de la tabla llegaron a la persona como respuesta.
- **La elección no depende de cuántas operaciones ve el modelo.** Con las 25 de B eligió bien 101 de 102. El único desvío fue una frase vaga y MARCO la detuvo.
- **El prompt corregido en la etapa 12 se sostuvo.** Ninguna respuesta pidió confirmación ni describió una operación en vez de llamarla.

### E25 con la notación corregida (D104), con tres modelos

El mismo procedimiento de la etapa 14 (`docs/knowledge/agente-sin-contexto.md`), con la misma descripción y la notación ya corregida. Fueron tres subagentes, uno por modelo, cada uno con su carpeta. Esta vez el canon del agente no reemplazó al del repositorio: se puso delante en el classpath, de modo que la suite lo carga en lugar del original. Material: `docs/e25/ronda-2/`.

| Modelo | Usos de herramientas | Correcciones | Causa |
|---|---|---|---|
| Opus 5.5 | 5 (3 lecturas, 2 escrituras) | 0 | — |
| Sonnet 5.5 | 5 | 1: la venta de mostrador llamaba al costo unitario sin `:metodo`. | Notación: no decía que un concepto recibe todos sus parámetros, con `nil` si no hay valor. |
| Haiku 5.5 | 5 | 1: el despacho buscaba la reserva por `:origen entrada/entrega` en vez de `:raiz caso/id`. | Del agente: la notación muestra ese mismo despacho con `:raiz caso/id`. |

En la primera ronda (Opus, notación sin corregir) hubo seis correcciones, todas de notación. Ahora hay dos en tres agentes, una de notación y una del agente. Ninguna fue de dominio. Tras su corrección, los tres pasan la suite completa.

Las tres notas coinciden en cuatro dudas que no costaron correcciones:

- si `nil` es un literal;
- la causa `:facturado`, que no estaba en la lista de causas;
- cómo decir «no extinguido» en un filtro;
- cómo excluir lo cedido dentro de un concepto, que no conoce la empresa.

La notación ahora documenta las tres primeras y la llamada a un concepto. Corrige además el ejemplo de concepto, que usaba un tipo de compromiso y un campo que el canon no tiene. La cuarta es una pregunta de dominio, no de notación: el cupo cuenta lo cedido.

Dos agentes (Opus y Haiku) agregaron un concepto propio para no repetir monto más IVA. Lo permite la notación y no cambia nada.

**Pregunta 4:** se confirma. Con el documento al día, el mismo modelo escribió el canon de ventas sin correcciones, y uno mucho menor necesitó una.

### Líneas

- Núcleo de MARCO: cero.
- SARA: `src/nc/sara/lenguaje.clj`, +13 (216). Atrapar el error de MARCO y decirlo; nada propio de una operación.
- Pruebas: la muestra de E23 (`lenguaje_claude_test.clj`, solo con clave) y una prueba guionada del error.

### Correcciones

- Humanas a los contratos de los agentes: 2 (arriba).
- Del agente que diseñó: una. El primer subagente de E25 se lanzó con aislamiento de worktree, que le daba el repositorio a la mano; los otros dos, sin él. Según su informe, ninguno abrió más que sus tres archivos.

### Mutaciones

Una nueva: SARA se cae con un error de MARCO.
