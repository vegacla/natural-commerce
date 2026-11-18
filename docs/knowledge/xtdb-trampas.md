# XTDB 2.2: trampas

Observadas implementando el almacén (`src/nc/almacen.clj`).

- **Columna inexistente en una tabla vacía.** XTDB crea las columnas con la primera escritura. Un `ASSERT` o `SELECT` que nombre una columna que ningún documento ha tenido falla con "Column not found". Por eso `crear-tablas!` escribe y borra un documento semilla que ya lleva `:empresa`: toda columna que el SQL nombre debe estar en la semilla.
- **Tabla inexistente.** Tampoco se puede nombrar una tabla que no existe, ni dentro de un `ASSERT`. Misma solución: la semilla.
- **Símbolos.** XTDB no guarda símbolos; se guardan como `{:nc/simbolo "x"}` (`a-xt`, `de-xt`).
- **Fechas.** Vuelven como `ZonedDateTime` y el núcleo usa `java.util.Date`. Ver `xtdb-bitemporalidad.md` para los tiempos de XTDB.
- **Atomicidad.** Un `ASSERT` de SQL en la misma transacción descarta todo si falla. Sirve para que un hecho no se modifique (D16) y para la marca por empresa de E7 (D74): `ASSERT EXISTS (SELECT 1 FROM marcas WHERE _id = ? AND n = ?)`.
- **`_id` de hechos y compromisos.** Es un uuid propio, no derivado de la empresa, a diferencia del resto. Un `put-docs` con el mismo `_id` reemplaza el documento aunque sea de otra empresa: por eso `escribir!` lleva un `ASSERT NOT EXISTS ... empresa <> ?` para compromisos (D80).
- **ASSERT fallido.** El error no distingue cuál `ASSERT` falló (solo "Assert failed"). `escribir!` lo deduce volviendo a consultar la marca y los dueños de los compromisos.
