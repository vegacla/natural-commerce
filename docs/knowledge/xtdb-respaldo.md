# XTDB 2.2: respaldo y restauración

Probado el 17 de noviembre de 2026 con `test/nc/respaldo_test.clj`, nodo en el mismo proceso, log y almacenamiento en disco local. Detalle de la revisión: `docs/revision-xtdb.md`.

## Qué se observó

- **Respaldo en frío de las dos piezas** (nodo detenido, copiar las carpetas del log y del almacenamiento): la base restaurada es idéntica, con sus dos tiempos, y sigue operando. La marca de cada empresa (D74) continúa donde quedó.
- **Restaurar solo el almacenamiento:** XTDB se niega a arrancar con «invalid transaction log state (the log is empty)». No arranca en silencio con datos a medias. Con una época nueva en el log (`:epoch 1`) arranca; vuelve lo que estaba en bloques y se pierde lo posterior al último bloque.
- Con poco volumen no se cierra ningún bloque: todo vive en el log. En la prueba, la carpeta de almacenamiento quedó vacía y el log tenía 2 archivos (unos 650 KB); un respaldo solo del almacenamiento no habría guardado nada.

## Trampas

- **En Windows, la ruta del log y del almacenamiento debe pasarse como `Path`**, no como texto: con texto XTDB la lee como URI y falla (`{:log [:local {:path (.resolve raiz "log")}]}`).
- **Forzar un bloque:** `xtdb.log/send-flush-block-msg!` (con `(db/primary-db nodo)`), seguido de `xtdb.log/sync-node` y de esperar a que aparezca el archivo. No es una API documentada para usuarios; sirve para un respaldo programado.
- Restaurar no exige cambios en el núcleo: basta abrir el almacén sobre la copia (`nc.ejemplo/vistas` abre las empresas sin recargar los datos).
