# XTDB 2.2: respaldo y restauración

Probado el 17 de noviembre de 2026 con `test/nc/respaldo_test.clj`, nodo en el mismo proceso, log y almacenamiento en disco local. Detalle de la revisión: `docs/revision-xtdb.md`.

## Cómo está hecho el estado

- **Log:** las transacciones en orden. **Almacenamiento:** archivos inmutables con lo ya indexado, en bloques. El nodo no guarda nada propio.
- Lo que no pasó a un bloque existe solo en el log. Un bloque se cierra al llenarse o cada 15 minutos (`:indexer {:flush-duration ...}`).
- Con poco volumen, todo vive en el log: en la prueba no se cerró ningún bloque, y un respaldo solo del almacenamiento no habría guardado nada.

## Qué funcionó

- **Respaldo en frío de las dos piezas** (nodo detenido, copiar las carpetas del log y del almacenamiento): la base restaurada es idéntica, con sus dos tiempos, y sigue operando. La marca de cada empresa (D74) continúa donde quedó.
- **Restaurar solo el almacenamiento:** XTDB se niega a arrancar con «invalid transaction log state (the log is empty)». No arranca en silencio con datos a medias. Con una época nueva en el log (`:epoch 1`) arranca; vuelve lo que estaba en bloques y se pierde lo posterior al último bloque.
- Orden de respaldo según la guía oficial: almacenamiento primero, log después.

## Trampas

- **En Windows, la ruta del log y del almacenamiento debe pasarse como `Path`**, no como texto: con texto XTDB la lee como URI y falla (`{:log [:local {:path (.resolve raiz "log")}]}`).
- **Forzar un bloque:** `xtdb.log/send-flush-block-msg!` (con `(db/primary-db nodo)`), seguido de `xtdb.log/sync-node` y de esperar a que aparezca el archivo. No es una API documentada para usuarios; sirve para un respaldo programado.
- Restaurar no exige cambios en el núcleo: basta abrir el almacén sobre la copia (`nc.ejemplo/vistas` abre las empresas sin recargar los datos).

## No probado

Respaldo en caliente (el log local puede quedar a medio escribir; el almacenamiento es inmutable y se puede copiar), configuración de producción con Kafka y almacén de objetos, y `xtdb.export/export-snapshot!`.
