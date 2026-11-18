# XTDB 2.2: los dos tiempos

Observado en las etapas 2, 4 y 5 (17 de noviembre de 2026), XTDB 2.2.0. Origen: `docs/mediciones.md`.

## Qué entrega sin diseño adicional

- Cada escritura guarda la versión anterior con sus dos tiempos: **validez** (desde cuándo rige) y **registro** (`xt/system-from`, cuándo lo supo el sistema). Ver un dato como era en una fecha es pasar ambos a la consulta (`:current-time` y `:snapshot-time`; en el almacén, la opción `:al`, o `:validez` y `:registro` por separado).
- Una política cargada hoy con `:rige-desde` en 30 días no rige hasta entonces, y se puede preguntar qué regirá en una fecha según lo que se sabía en otra.
- La historia completa de un documento se lee con `:for-valid-time :all-time` (`almacen/historia`).
- Borrar con fecha de validez (`:delete-docs {:from t :valid-from d}`) hace que un dato deje de regir desde `d` sin perder su historia. Es lo que usa `retirar!` (D75).
- Valores del dominio (`[:monto 30000 :CLP]`, keywords, mapas anidados) se guardan y vuelven sin conversión. Los símbolos no: ver `xtdb-trampas.md`.

## Trampas

- **Precisión del tiempo de registro.** XTDB registra microsegundos; `java.util.Date` solo milisegundos. Convertir el tiempo de una transacción a `Date` y consultar `:al` ese instante deja fuera la propia transacción. Los tiempos de XTDB se devuelven como `Instant` (`de-xt`); solo los datos del dominio pasan a `Date`.
- **Versiones publicadas fuera de orden.** Una escritura con `:valid-from` y sin `:valid-to` rige hasta el fin del tiempo, y pisa una versión posterior ya cargada. Al publicar una versión, `publicar!` busca la siguiente y le pone `:valid-to`. Es semántica bitemporal normal, no un defecto, pero hay que resolverla.
- **El tiempo de registro debe ser creciente.** Las pruebas usan el reloj real. Un tiempo de registro en el futuro deja los datos invisibles para una consulta de hoy. La opción `:registro` de `escribir!` y `publicar!` solo sirve para cargar datos antiguos y para pruebas, y debe ser posterior a la última transacción.
- **El caso abierto y la regla vigente son preguntas distintas.** La regla con que se abrió un caso queda anotada en su raíz y no cambia; la vigente en una fecha la da el resolvedor mirando en esa fecha (E5).
