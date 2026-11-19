# XTDB 2.2: los dos tiempos

Observado en las etapas 2, 4 y 5 (17 de noviembre de 2026), XTDB 2.2.0. Origen: `docs/mediciones.md`.

## Qué entrega sin diseño adicional

- Cada escritura guarda la versión anterior con sus dos tiempos: **validez** (desde cuándo rige) y **registro** (`xt/system-from`, cuándo lo supo el sistema). Ver un dato como era en una fecha es pasar ambos a la consulta (`:current-time` y `:snapshot-time`; en el almacén, la opción `:al`, o `:validez` y `:registro` por separado).
- Una política cargada hoy con `:rige-desde` en 30 días no rige hasta entonces, y se puede preguntar qué regirá en una fecha según lo que se sabía en otra.
- La historia completa de un documento se lee con `:for-valid-time :all-time` (`almacen/historia`).
- Borrar con fecha de validez (`:delete-docs {:from t :valid-from d}`) hace que un dato deje de regir desde `d` sin perder su historia. Es lo que usa `retirar!` (D75).
- Valores del dominio (`[:monto 30000 :CLP]`, keywords, mapas anidados) se guardan y vuelven sin conversión. Los símbolos no: ver `xtdb-comportamientos.md`.

Lo que hay que respetar al usarlos (microsegundos, versiones fuera de orden, leer lo recién escrito) está en `xtdb-comportamientos.md`.
