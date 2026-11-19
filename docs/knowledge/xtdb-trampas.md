# XTDB 2.2: trampas

Observadas implementando el almacén (`src/nc/almacen.clj`).

- **Columna inexistente en una tabla vacía.** XTDB crea las columnas con la primera escritura. Un `ASSERT` o `SELECT` que nombre una columna que ningún documento ha tenido falla con "Column not found". Por eso `crear-tablas!` escribe y borra un documento semilla que ya lleva `:empresa`: toda columna que el SQL nombre debe estar en la semilla.
- **Tabla inexistente.** Tampoco se puede nombrar una tabla que no existe, ni dentro de un `ASSERT`. Misma solución: la semilla.
- **Símbolos.** XTDB no guarda símbolos; se guardan como `{:nc/simbolo "x"}` (`a-xt`, `de-xt`).
- **Fechas.** Vuelven como `ZonedDateTime` y el núcleo usa `java.util.Date`. Ver `xtdb-bitemporalidad.md` para los tiempos de XTDB.
- **Atomicidad.** Un `ASSERT` de SQL en la misma transacción descarta todo si falla. Sirve para que un hecho no se modifique (D16) y para la marca por empresa de E7 (D74): `ASSERT EXISTS (SELECT 1 FROM marcas WHERE _id = ? AND n = ?)`.
- **`_id` de hechos y compromisos.** Es un uuid propio, no derivado de la empresa, a diferencia del resto. Un `put-docs` con el mismo `_id` reemplaza el documento aunque sea de otra empresa: por eso `escribir!` lleva un `ASSERT NOT EXISTS ... empresa <> ?` para compromisos (D80).
- **ASSERT fallido.** El error no distingue cuál `ASSERT` falló (solo "Assert failed"). `escribir!` lo deduce volviendo a consultar la marca y los dueños de los compromisos.
- **Filtrar por un mapa falla si la columna tiene mapas de formas distintas** (2026-11-19). Los saldos se buscaban por `:saldo/clave`, un mapa. Mientras todos los saldos eran de existencia (`{:producto :bodega}`), funcionaba. Al aparecer otro saldo con clave `{:cliente}` en el mismo nodo, la búsqueda por igualdad dejó de encontrar los de existencia: la columna pasa a guardar estructuras de formas distintas. Un saldo se busca ahora por su `_id`, que se deriva de empresa, concepto y clave. No filtrar por igualdad de mapas.
- **XTDB no guarda `BigInteger`** (2026-11-19). `unknown object type: class java.math.BigInteger`. `nc.valor/redondear` lo producía al redondear a cero decimales; ahora devuelve un `long`.
