# XTDB 2.2: bases de datos múltiples

Observado el 18 de noviembre de 2026 con XTDB 2.2.0. Resultado: se descartó (D80). Este archivo existe por si se reabre.

## Cómo funciona

- `ATTACH DATABASE <nombre> WITH $$ ... $$` se ejecuta conectado a la base primaria `xtdb`, **por pgwire** (JDBC). Cada base lleva su propio log y almacenamiento, en YAML:

```sql
ATTACH DATABASE empresa_a WITH $$log: !Local
  path: 'C:/ruta/a/log'
storage: !Local
  path: 'C:/ruta/a/storage'
mode: read-write
$$
```

- También acepta `!InMemory` para log y almacenamiento. Con Kafka y S3 son un tópico y un prefijo por base.
- Se consulta conectándose a esa base (`jdbc:postgresql://host:puerto/empresa_a`). Sin calificar, una consulta ve solo su base.
- La configuración de las bases adjuntas se guarda como metadatos en la primaria. `DETACH DATABASE` es asíncrono desde 2.2: reponer el mismo nombre muy pronto da `xtdb/db-being-detached`.
- Serializabilidad solo dentro de una base; una transacción no cruza bases.

## Trampas

- **El YAML falla en silencio.** Un espacio antes de `log:` (`$$ log:`) deja la primera línea más indentada que las demás y da solo "Invalid database config in `ATTACH DATABASE`:", sin detalle. Va pegado: `$$log:`.
- **Nombres sin guiones.** `db-a` falla con error de sintaxis SQL; usar `db_a`.
- **No funciona por la API en proceso.** `ATTACH` vía `xt/execute-tx` termina en `AbstractMethodError` (`visitAttachDatabaseStatement`) y detiene la ingesta del nodo. Con `xt/q` da "not a preparable query". Se necesita el servidor pgwire (`{:server {:port N}}`) y un cliente JDBC (pgjdbc y next.jdbc ya están en el classpath).
- **No es una frontera de seguridad.** Desde la base de A, `SELECT * FROM empresa_b.public.compras` devuelve los datos de B.
- `xt.databases` no existe como tabla.

## Por qué se descartó

Migrar `almacen.clj` de la API en proceso a JDBC, costo por base sin medir, y la separación seguiría dependiendo de que solo MARCO arme consultas. Ver D80 y `separacion-por-empresa.md`.

## Si se reabre

Medir un nodo con 50 a 100 bases (memoria, arranque, respaldo por base), y probar el almacén sobre JDBC con una conexión por empresa.
