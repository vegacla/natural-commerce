# Natural Commerce — Plan de trabajo

Escrito el 2026-11-20 para un agente que no participó del desarrollo. Dice qué está hecho, cómo trabajar en este repositorio sin romper nada, qué falta y en qué orden. La fuente de verdad sigue siendo `docs/registro-de-decisiones.md`: si este plan y el registro no coinciden, manda el registro, y hay que corregir el plan.

## 1. Antes de empezar

Lee, en este orden:

1. `CLAUDE.md`: el resumen del proyecto, los comandos y las reglas de trabajo. Es corto; léelo entero.
2. `docs/registro-de-decisiones.md`: la sección 0 (el modelo en una página) y la sección 5 (pendientes). Las decisiones van de D1 a D114; consulta una cuando el código la cite.
3. `docs/notacion-v0.md`: cómo se escriben contratos, políticas y conceptos en EDN. Todo lo que el canon usa está documentado ahí (D104).
4. `docs/prototipo-ampliado.md`: los escenarios E10 a E29 y las etapas 7 a 16.
5. `docs/mediciones.md`: lo que se midió en cada etapa. Las últimas secciones explican las etapas 15 y 16 (documentos con líneas), que son el punto de partida de la tarea principal.
6. `docs/knowledge/README.md`: comportamientos de las herramientas, ya verificados. Léelo antes de pelear con XTDB, el JDK o las mutaciones.

**El proyecto en pocas líneas.** Es un ERP con dos piezas:

- **MARCO**, el motor determinista. Evalúa cada operación contra su contrato, las políticas de la empresa y el estado: la ejecuta, la bloquea, la prohíbe o pide confirmación. Toda evaluación queda en una bitácora.
- **SARA**, la interfaz. Tiene formularios y un chat con Claude Haiku 5.5. El modelo solo elige la operación y sus entradas; decide MARCO.

Contratos, políticas y conceptos son datos EDN, no código. La regla de diseño es D46:

- el significado de una operación va en el **contrato**;
- la forma de trabajar de una empresa va en una **política con valor por defecto**;
- la ley va en una **política legal**.

El criterio que se mide en cada etapa es que una operación nueva cueste **cero líneas de núcleo**. El núcleo solo crece por un mecanismo nuevo, y eso se registra como decisión.

## 2. Entorno y comandos

Windows 11, PowerShell como terminal principal. Clojure con el CLI `deps` (deps.clj) y XTDB 2.2 en el mismo proceso, en memoria para las pruebas. XTDB exige JDK 21, instalado aparte; los scripts lo fijan solos.

| Qué | Comando | Notas |
|---|---|---|
| Suite completa | `.\pruebas.ps1` | Unos 60 a 90 s. Hoy: 105 pruebas, 921 comprobaciones. |
| Un archivo de pruebas | `.\pruebas.ps1 -n nc.lineas-test` | |
| Desde bash | `JAVA_CMD="C:/Users/ceva2/AppData/Local/Programs/Temurin21/jdk-21.0.13+9/bin/java.exe" deps -M:test` | |
| Mutaciones | `deps -M:mutaciones` con el mismo `JAVA_CMD` | Hoy: 72 de 72 detectadas. Ver la sección 4. |
| SARA (formularios y chat) | `.\sara.ps1` y abrir http://localhost:8081 | Datos de ejemplo en memoria; se pierden al detener. El chat usa la clave de `.env`. |
| E23 con Claude de verdad | `.\e23-claude.ps1` (todo) o `.\e23-claude.ps1 -v nc.lenguaje-claude-test/e23-sin-inventar` | **Cuesta dinero.** Pide permiso al usuario antes de correrlo. |
| Conversación larga con Claude | `$env:NC_E23_LARGA='1'; .\e23-claude.ps1 -v nc.lenguaje-claude-test/e23-conversacion-larga` | 24 llamadas. Pide permiso. |
| Volumen, un año | `$env:NC_VOLUMEN='1'; .\pruebas.ps1 -n nc.volumen-test` | Unos 4 minutos. |
| Volumen en disco con 49 empresas | `$env:NC_DISCO='49'; $env:NC_DISCO_DIAS='8'; .\pruebas.ps1 -n nc.disco-test` | Unos 3 minutos. Con más días crece rápido: una corrida de 20 días pasó de 13 minutos. |

**No leas `.env`**: contiene la clave de la API. Los scripts la toman solos.

## 3. Reglas de trabajo

- **Idioma:** español en documentos, nombres de dominio, mensajes de commit y conversación.
- **Commits** directos en `main`, salvo que el usuario pida una rama. Termina cada mensaje con la línea de coautoría que indique tu entorno.
- **Una decisión nueva** se agrega a `docs/registro-de-decisiones.md` con el número siguiente; la última es D114. Si cambia algo pendiente, actualiza también la sección 5.
- **Cada etapa** deja en `docs/mediciones.md` lo que pide la sección 7 de `docs/prototipo-minimo.md`:
  - líneas de núcleo y líneas por operación (usa `git diff --numstat src`);
  - correcciones humanas y del agente;
  - mutaciones nuevas.
- **Lo que se aprende de una herramienta** va en `docs/knowledge/`, un archivo por tema y con cómo se verificó. Agrégalo al índice.
- **Una construcción nueva de la notación** (una clave, una forma de efecto) se documenta en `docs/notacion-v0.md` con un ejemplo, en el mismo cambio (D104).
- **Separación por empresa (D80):**
  - solo `src/nc/almacen.clj` toca XTDB;
  - toda función pública del almacén recibe la empresa;
  - una función pública nueva del almacén se declara en `test/nc/resguardos_test.clj`.
- **Todo resguardo nuevo lleva su mutación** en `scripts/mutaciones.edn`.
- **Método:** escribe primero el escenario y la prueba, después los contratos, y mira qué falla. Lo que falla dice qué le falta al modelo; las etapas 15 y 16 son el ejemplo.
- **Lo tributario chileno** no está verificado por un experto: constrúyelo desde fuentes del SII y márcalo (D42).
- **Antes de preguntar algo de diseño al usuario**, revisa si D46 y el registro ya lo responden. Lo técnico se resuelve construyendo.

## 4. Trampas conocidas

Todas están verificadas; el detalle está en `docs/knowledge/`.

- **Zed bloquea archivos en Windows.** Si el editor tiene abierto un archivo de `src` mientras las mutaciones lo modifican, la escritura puede fallar. El script reintenta, pero **después de una corrida cortada revisa `git status src`**: si quedó un archivo mutado, restáuralo con `git checkout -- <archivo>` después de confirmar que el único cambio es la mutación.
- **No conectes la salida de las mutaciones a `head`** ni a nada que corte la lectura: el proceso muere a mitad y deja un archivo mutado. Redirige a un archivo y léelo después.
- **La primera corrida de mutaciones en una máquina tarda varios minutos.** El script guarda en `target/mutaciones-detectoras.edn` qué archivo de prueba detectó cada mutación, y desde la segunda corrida baja a unos 2 minutos.
- **Un archivo de prueba nuevo** se agrega a `orden-conocido` en `scripts/mutaciones.clj`, ordenado de más rápido a más lento. Si no, corre al final y encarece cada mutación que solo él detecta.
- **Una mutación no detectada no siempre significa que falte una prueba.** El script recarga solo el namespace mutado, y si otro namespace guardó una función como valor al cargarse, la mutación no se ve. Fue el caso de `v/igual?` en `nc.expresion`; se resolvió llamando a través de la variable.
- **Escribir archivos desde bash con heredocs** falla a veces con comillas simples o `#"..."` dentro. Escribe un script de Python en un archivo y córrelo.
- **XTDB solo indexa `_id`.** Filtrar por otra columna recorre la tabla con los datos de todas las empresas, en memoria y en disco. Las búsquedas por caso, reversión, raíz y origen ya van por índices propios (D109); la búsqueda por tipo todavía no.
- **Datos de prueba:**
  - `nc.marco-test/empresa!` crea una empresa con entidades mínimas (solo el producto P-100) e instala las políticas por defecto (D107).
  - Una empresa no vende sin existencia: compra y recibe antes de despachar o vender en mesón.
  - Usa `alm/cargar-entidades!` para más productos.
- **El JDK 21 va solo por `JAVA_CMD`.** No cambies el JDK del sistema.

## 5. Estado actual

Etapas 1 a 16 hechas; E1 a E29 pasan. El prototipo ampliado cumple su criterio de salida (sección 9 de `docs/prototipo-ampliado.md`): la cobertura llega a 74 de 74 operaciones sin primitivos, verbos ni formas nuevos. El usuario todavía no lo ha declarado formalmente como decisión; no lo hagas por tu cuenta.

Lo último que se hizo, para ubicarte:

| Decisión | Qué es |
|---|---|
| D105 | SARA dice a la persona el error de MARCO, en vez de caerse. |
| D107 | Paquete de políticas por defecto de la plataforma (`resources/defecto/politicas.edn`), manejado junto con el plan contratado. |
| D108 | Una empresa reserva una operación a MARCO con un permiso; SARA no la ofrece a las personas. |
| D109 | Índices propios en `almacen.clj` para las búsquedas por caso, reversión, raíz y origen. |
| D110 | Chat de SARA en el cliente web. El modelo recibe los últimos 20 mensajes. |
| D111 | SARA no inventa sistemas ni procedimientos que no existen. |
| D112 | Referencias revisadas también dentro de las líneas de un documento. |
| D113 | `:cada` como expresión y como efecto: una entrega por línea, una reducción por línea. |
| D114 | «Fija» y «sugiere» por línea: precio de lista general y por cliente; comparar con `nil` deja de ser un error. |

Los documentos con líneas existen solo como **experimento**, en `resources/lineas/ventas.edn`, y solo los carga `nc.lineas-test`. El canon sigue con un producto por documento.

## 6. Tareas

En orden de prioridad. Cada una dice si necesita una decisión del usuario.

### T1. Pasar el canon a documentos con líneas — **necesita la aprobación del usuario**

**Por qué.** En un ERP, pedido, despacho, factura, orden de compra y recepción tienen varias líneas. El experimento probó que el modelo lo expresa (E28, E29). Mantener dos versiones de cada operación, una de un producto y otra con líneas, sería duplicar.

**Antes de empezar**, pregunta al usuario si aprueba reemplazar las operaciones de un producto por las de líneas. Es lo que se recomienda: un documento de un producto es un caso de uno con líneas. Registra la respuesta como decisión.

**Qué hacer:**

1. **Ventas** (`resources/canon/ventas.edn`). Llevar pedido, despacho, factura, venta de mesón y nota de crédito a líneas, partiendo de `resources/lineas/ventas.edn`. Mantén los nombres de operación del canon (`:ventas/registrar-pedido`, etc.): los usan las políticas, las pruebas y el paquete legal.
2. **El paquete legal** (`resources/legal/cl.edn`). Hoy el IVA es una «fija» sobre `:entrada/iva` de `:ventas/emitir-factura`, y el folio otra. Decide con D46: el IVA sobre el total sigue igual si la factura lleva un total; si debe ir por línea (por ejemplo, por exenciones), usa una «fija» por línea (D114). Marca lo tributario como no verificado.
3. **Inventario.** `:inventario/reservar` recibe hoy una entrega; con líneas, la reserva es por línea, y el despacho reduce la reserva de la entrega de esa línea. El canon actual busca la reserva por la raíz del caso (`[:compromisos {:tipo :inventario/reservado :raiz caso/id ...}]`); con varias líneas en el mismo caso eso ya no basta. La reserva necesita su origen o su entrega para encontrar la de cada línea.
4. **Política por defecto D107** (`resources/defecto/politicas.edn`). Pásala a línea por línea con `[:todos entrada/lineas ...]`, como `:lineas/salir-de-lo-que-hay` en el experimento.
5. **Compras** (`resources/canon/compras.edn`). Orden con líneas, y recepción y factura por línea. Ojo: la compra formal de la Empresa B (`resources/empresas/empresa-b.edn`) mide el monto con `entrada/cantidad × entrada/precio-unitario`; con líneas será la suma de las líneas.
6. **Políticas de las empresas y de las pruebas.** Las que apuntan a `:entrada/producto`, `:entrada/cantidad` o `:entrada/precio-unitario` pasan a líneas, con `:cada` o `[:todos ...]` / `[:suma ...]`. Hay varias en `test/nc/composicion_test.clj`, `test/nc/formas_test.clj` y `resources/empresas/`.
7. **Pruebas.** Muchas pruebas de E10 a E27 llaman a esas operaciones con un producto, y hay que reescribir sus entradas. **No debilites lo que comprueban**: cambia la forma de la entrada, no la expectativa. Corre la suite completa a menudo.
8. **Planificación (D97, E20).** Un límite con `:cubre-con` mide una entrada suelta (`:parcial` exige `entrada/x`). Revisa cómo se expresa «reservar lo libre» y «abastecer lo que falta» por línea; puede ser un mecanismo nuevo. Si lo es, regístralo como decisión y mide sus líneas de núcleo.
9. **Los datos de ejemplo** (`resources/ejemplos/compras.edn`) y la conversación de E23 se ajustan a las entradas nuevas.
10. **Cobertura** (`nc.cobertura-test`, `resources/cobertura/`). Debe seguir en 74 de 74 sin primitivos, verbos ni formas nuevos.
11. **Al terminar:** borra `resources/lineas/`, reubica sus pruebas en las de ventas y actualiza `CLAUDE.md`.

**Criterio de aceptación:**
- la suite completa pasa;
- las mutaciones se detectan todas;
- la cobertura sigue en 74 de 74;
- `docs/mediciones.md` tiene una sección de la etapa con las líneas de núcleo; la meta es cero, o el mecanismo nuevo registrado.

Es una tarea grande: hazla por partes (ventas, después inventario, después compras), con un commit por parte que deje la suite pasando.

### T2. SARA con líneas: un formulario con filas

**Por qué.** Hoy una entrada que es una lista de líneas se muestra como un solo campo de texto en EDN (`src/nc/sara.clj`, `leer` y `escribir` para `:coleccion`). Sirve para pruebas, no para una persona.

**Qué hacer:**

1. **En el formulario** (`src/nc/sara.clj` y `src/nc/sara/web.clj`):
   - una entrada `[:vector [:map ...]]` se dibuja como una tabla, con una fila por línea, un campo por columna y botones para agregar y quitar filas;
   - las referencias de cada columna ofrecen opciones (`sara/opciones`), como un campo suelto;
   - los valores que llenó una política por línea (`:valores` del resolvedor trae `:linea`) se muestran en su fila, diciendo de qué política salieron.
2. **En el chat** (`src/nc/sara/lenguaje.clj`): la herramienta describe la entrada como una lista de líneas, con el formato de cada campo. Prueba con el modelo guionado (`nc.lenguaje-test`) y, con permiso del usuario, con Claude.
3. **Regla de diseño:** SARA no tiene código propio de ninguna operación (D78). Todo sale del contrato y del resolvedor.

**Criterio de aceptación:**
- una prueba en `nc.web-test` registra un pedido de tres líneas desde el formulario;
- otra prueba lo hace por el chat guionado;
- cero líneas propias de una operación.

Se puede empezar antes de T1, con las operaciones de `resources/lineas/`, pero conviene hacerla después.

### T3. Consulta de existencia

**Por qué.** En el chat la gente pide «el stock de los guantes» y no hay operación. El saldo existe: `:inventario/existencia`, con clave `{:producto :bodega}` y componentes `:cantidad` y `:costo` (D111).

**Qué hacer:** agregar al canon de inventario (`resources/canon/inventario.edn`) una consulta (`:op/tipo :consulta`) con producto y bodega opcional que devuelva la cantidad y el costo:
- con bodega, la de esa bodega;
- sin bodega, la de cada una, si la notación lo permite sin núcleo nuevo; si no lo permite, regístralo como hallazgo.

Considera también lo libre y lo disponible: los conceptos `:inventario/libre` e `:inventario/disponible` ya existen.

**Criterio de aceptación:**
- una prueba con datos reales;
- E24 actualizado (cuenta las operaciones que ve cada empresa: `nc.lenguaje-test`, `e24-...`);
- cero líneas de núcleo.

Es la tarea más chica: sirve como primera tarea para conocer el repositorio.

### T4. Costo al despachar sin existencia

**Por qué.** Si una empresa retira la política por defecto que prohíbe despachar sin existencia (D107), el despacho no tiene costo y MARCO da un error de datos (D105).

**Qué hacer:** que la empresa diga de dónde sale el costo cuando no hay existencia, por ejemplo el último costo conocido o uno fijado. Es forma de trabajar (D46), así que va como política, probablemente una «sugiere» o «fija» sobre el costo de la línea, o un método en `:inventario/costo-unitario`. Escribe primero el escenario.

Prioridad baja: hoy ninguna empresa lo necesita.

### T5. Búsquedas por tipo

**Por qué.** `{:hecho/tipo t}` recorre la tabla con los datos de todas las empresas. Medido en disco con 49 empresas: de 1 a 25–33 ms. Lo usan:
- el costo FIFO y el último costo (`src/nc/calculos.clj`), aunque no el promedio, que es el método por defecto;
- las opciones de los formularios de SARA;
- el tiempo, al buscar lo que vence (`src/nc/tiempo.clj`).

**Qué hacer:** una lista por tipo sería muy larga (todas las ventas), así que no conviene la variante de D109. Hay dos opciones:
- índices por tipo y otro campo, como recepciones por producto y bodega;
- llevar las capas de costo FIFO como saldo.

Mide antes y después con `nc.disco-test`, cuyas columnas incluyen «por tipo». Toda la solución va dentro de `almacen.clj`, con resguardo y mutación, como D109.

Prioridad baja mientras no haya volumen real.

### T6. Medir en el servidor real — **bloqueada**

En disco, leer por `_id` cuesta de 2 a 7 ms y cada operación escribe en el log. Hay que medirlo en el disco del servidor que define D106 antes de optimizar. Espera a que exista el servidor.

### T7. Condiciones antes de datos de clientes (D79) — **decisiones del usuario**

- **Condición 1, log durable:** el camino está decidido (D106), sin implementar: un nodo, log local sobre un disco de nube replicado y con instantáneas, y un cierre de bloque frecuente. **No uses Kafka**: el usuario lo descartó. Se hace al preparar el primer despliegue.
- **Condición 2, almacén de objetos con versionado y respaldo ensayado:** abierta. Con un solo nodo y disco local podría ser una copia externa de las carpetas y una restauración de prueba periódica (ver `docs/revision-xtdb.md` y `test/nc/respaldo_test.clj`). Consulta al usuario antes.
- **Condición 3, suscripción de soporte de XTDB:** el usuario dijo que no por ahora.

### T8. Verificar el paquete legal chileno — **del usuario**

`resources/legal/cl.edn` lo debe revisar un experto tributario (D42, D93). No es tarea de código.

## 7. Lo que no hay que hacer sin que el usuario lo pida

- **Kafka o MinIO:** descartados.
- **Caché de prompt de la API de Anthropic:** se evaluó y se dejó para más adelante. Con Haiku 5.5 el ahorro son fracciones de centavo, y las listas de opciones dentro de las herramientas la invalidarían.
- **Interpretar markdown en el chat o mostrar mejor los identificadores crudos en las salidas:** se propuso y el usuario eligió hacer solo D111.
- **Declarar el veredicto del prototipo ampliado** («el modelo sirve para un ERP») como decisión: es del usuario.
- **Correr pruebas que llaman a Claude** sin permiso: cuestan dinero.

## 8. Al cerrar cada tarea

1. `.\pruebas.ps1`: todo pasa.
2. `deps -M:mutaciones` con la salida redirigida a un archivo: todas detectadas. Después revisa `git status src`, que debe estar sin mutaciones.
3. `git diff --numstat src` para las líneas de núcleo; anótalas en `docs/mediciones.md` en una sección de la etapa.
4. Decisiones nuevas en el registro, y la sección 5 de pendientes al día.
5. `docs/notacion-v0.md` si apareció una construcción; `docs/knowledge/` si se aprendió algo de una herramienta.
6. `CLAUDE.md` si cambió el estado, un comando o un archivo importante.
7. Commit en `main`, en español, explicando qué y por qué.
