# XTDB 2.2: comportamientos

Lo que XTDB 2.2.0 hace y no era evidente, separado en tres grupos. Cada entrada dice cómo se verificó.

- **Límites de XTDB**: algo que XTDB no acepta o no hace. No son defectos nuestros; hay que rodearlos.
- **Semántica que hay que respetar**: XTDB hace lo correcto, pero el núcleo tiene que tenerlo en cuenta.
- **Errores nuestros**: el problema estaba en nuestro código; se anota la regla de XTDB que no respetamos.

## Límites de XTDB

- **Filtrar por igualdad de un mapa deja de funcionar cuando la columna tiene mapas de formas distintas** (2026-11-19). Con un solo documento `{:clave {:producto :P-100 :bodega :central}}`, buscar por esa clave lo encuentra. Al escribir otro con `{:clave {:cliente :c1}}`, ya no se encuentra ninguno de los dos: ni el de la forma antigua ni el de la nueva. No depende del orden en que se escriben ni del orden de las claves, y pasa igual en XTQL (`(from :t [{:clave p0} *])`) y en SQL (`WHERE clave = ?`). Dos mapas de la misma forma sí se distinguen. *Verificado* con un nodo vacío, sin el almacén: `reproducciones/xtdb-igualdad-de-mapas.clj` (`deps -M:xtdb -i` ese archivo). En el núcleo: un saldo se busca por su `_id`, que se deriva de empresa, concepto y clave. No filtrar por igualdad de mapas.
- **No guarda `BigInteger`** (2026-11-19). Escribirlo falla con `unknown object type: class java.math.BigInteger`. *Verificado* por la prueba E13. `nc.valor/redondear` lo producía al redondear a cero decimales; ahora devuelve un `long`.
- **No guarda símbolos de Clojure** (2026-11-17). *Verificado* en la etapa 3. Se guardan como `{:nc/simbolo "x"}` (`a-xt`, `de-xt`).
- **El SQL no puede nombrar una tabla o columna que aún no existe**, ni dentro de un `ASSERT` (2026-11-17). XTDB crea tablas y columnas con la primera escritura. *Verificado* en las etapas 2 y 7 ("Column not found"). `crear-tablas!` escribe y borra un documento semilla que trae toda columna que el SQL nombre (por ejemplo `:empresa`).
- **Un `ASSERT` fallido no dice cuál falló** (2026-11-17): el error es solo "Assert failed". *Verificado* en la etapa 2. `escribir!` lo deduce volviendo a consultar la marca y los dueños de los compromisos.
- **Las fechas vuelven como `ZonedDateTime`** (2026-11-17), aunque se escriban como `java.util.Date`. *Verificado* en la etapa 2. El núcleo las devuelve como `Date` (`de-xt`).

## Semántica que hay que respetar

- **Un dato rige desde el tiempo de registro de su transacción, y una lectura sin fecha usa la hora actual** (2026-11-19). Una lectura que cae en el mismo instante que la escritura no ve el dato: todavía no rige. *Verificado*: `un-hecho-no-se-modifica` fallaba en 2 de 40 vueltas con la JVM caliente. Ver "errores nuestros".
- **El tiempo de registro tiene microsegundos** (2026-11-17); `java.util.Date`, milisegundos. Un `Date` del mismo milisegundo queda antes de la transacción. *Verificado* en la etapa 2 y otra vez en `politicas-con-vigencia`. Los tiempos de XTDB se devuelven como `Instant` (`de-xt`).
- **Una escritura con `:valid-from` y sin `:valid-to` rige hasta el fin del tiempo** (2026-11-17), y pisa una versión posterior ya cargada. Es la semántica bitemporal normal. *Verificado* en la etapa 2. `publicar!` busca la versión siguiente y le pone `:valid-to`.
- **El tiempo de registro debe ser creciente** (2026-11-17). Uno en el futuro deja los datos invisibles para una consulta de hoy. *Verificado* en la etapa 2. Las pruebas usan el reloj real; la opción `:registro` es solo para cargar datos antiguos.
- **`_id` es único en la tabla, no por empresa** (2026-11-18). Un `put-docs` con el mismo `_id` reemplaza el documento aunque sea de otra empresa. *Verificado* al implementar D80. Hechos y compromisos tienen un uuid propio; `escribir!` lleva un `ASSERT NOT EXISTS ... empresa <> ?` para compromisos.
- **Solo `_id` está indexado; filtrar por otra columna recorre la tabla entera, con los datos de todas las empresas** (2026-11-19). Con un nodo en memoria, leer un hecho por `:hecho/id` (más `:empresa`) tarda más a medida que otras empresas escriben; por `:xt/id` no. *Verificado* con E26 (`nc.volumen-test` con `NC_VOLUMEN`): la consulta de estado de una empresa pasó de 34 a 58 ms cuando otra empresa cargó 19.500 hechos; leyendo por `:xt/id` quedó en 13,5 ms. En el núcleo: `leer` va por `:xt/id` (D102), y lo protege `leer-va-por-la-llave-primaria`.
- **En disco tampoco: los metadatos de los bloques no descartan las páginas de otras empresas** (2026-11-19). Con 49 empresas y unos 24.000 hechos en un nodo con log y almacenamiento locales, buscar los hechos de un caso de una empresa chica pasó de 2 ms (sola) a 45 ms con los datos ya en un bloque, y a 25 ms con el nodo reabierto; por tipo, de 1 a 33 y 28 ms. *Verificado* con `nc.disco-test` (`NC_DISCO=49 NC_DISCO_DIAS=8`). Por eso los índices propios de D109.
- **En disco, leer por `_id` cuesta de 2 a 7 ms** (2026-11-19); en memoria, 0,3 ms. *Verificado* con `nc.disco-test`, mediana de 25 tras 25 de calentamiento. Una búsqueda que hace N lecturas por `_id` paga N veces eso: leer varios documentos con una consulta SQL `SELECT * FROM t WHERE empresa = ? AND _id IN (?, ...)` bajó los hechos de un caso de 22 a 15 ms con el bloque recién cerrado, y de 8 a 9 ms con el nodo reabierto (ruido).
- **SQL devuelve los documentos con la misma forma que XTQL** (2026-11-19): `SELECT *` trae las claves con espacio de nombres (`:hecho/caso`, `:xt/id`) y los valores Clojure (palabras clave, mapas). *Verificado* con una reproducción mínima en memoria.
- **XTQL no acepta `*` dentro de `unify`** (2026-11-19): «* is not a valid in from when inside a unify context». Cruzar una lista de identificadores (`rel`) con `from` exige nombrar las columnas. *Verificado* con una reproducción mínima.
- **Compactación en Windows: `AccessDeniedException` al mover un archivo de bloque** (2026-11-19). Con log y almacenamiento locales en `%TEMP%`, XTDB 2.2 registró «error running compaction job» al mover `.tmp/upload…arrow` a `tables/public$hechos/data/l01-rc-b00.arrow`. El nodo siguió respondiendo y la prueba pasó. *Hipótesis sin verificar:* otro proceso (antivirus o indexador) tenía el archivo abierto, como pasa con Zed en `src`. En la misma máquina, una carga de 49 empresas con 20 días cada una se arrastró más de 13 minutos, con la JVM casi sin CPU; no se sabe si fue esto o la escritura del log.
- **Un `ASSERT` en la transacción la descarta entera si falla** (2026-11-17). Es lo que da la atomicidad de verificar y aplicar (D16, D49, D74). *Verificado* en E7.

## Errores nuestros

- **Leer sin fijar el tiempo de validez** (2026-11-19). El almacén leía sin `:current-time`, y una operación podía no encontrar la orden que la anterior acababa de emitir. Ahora anota el registro de su última escritura y lee con el mayor entre ese instante y el reloj (`almacen/ahora`).
- **"Hoy" como `Date` en una prueba** (2026-11-19). `politicas-con-vigencia` consultaba `:al` un `Date` que, con milisegundos, quedaba antes de la transacción. Ahora usa `Instant`.
- **Buscar saldos por su clave** (2026-11-19). Funcionó mientras hubo una sola forma de clave; dejó de hacerlo con el saldo facturado. Ver el primer límite.
