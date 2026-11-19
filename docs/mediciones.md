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
