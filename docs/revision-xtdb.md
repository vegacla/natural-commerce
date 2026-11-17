# Natural Commerce — Revisión de madurez operativa de XTDB

La pide la sección 6 de `prototipo-minimo.md`: respaldo, costos y soporte antes de comprometer datos de clientes, incluido respaldar y restaurar la base del prototipo. Revisada el 2026-11-17 con XTDB 2.2.0, nodo en el mismo proceso, log y almacenamiento en disco local.

## 1. Respaldo y restauración

### Qué hay que respaldar

XTDB v2 guarda el estado en dos piezas:

- **Log:** las transacciones en orden, tal como llegaron.
- **Almacenamiento:** archivos inmutables con lo ya indexado, en bloques. Cada bloque corresponde a una posición del log.

El nodo que consulta no guarda nada propio y se puede reemplazar. Lo que todavía no pasó a un bloque existe solo en el log. Un bloque se cierra al llenarse o, como máximo, cada 15 minutos (valor por defecto). Según la guía oficial, se respalda primero el almacenamiento y después el log.

### Lo que se probó

`test/nc/respaldo_test.clj` carga los datos de ejemplo de la etapa 6 en un nodo en disco, respalda, borra el original y restaura. Las dos pruebas pasan.

1. **Respaldo en frío de las dos piezas.** Con el nodo detenido, copiar las carpetas del log y del almacenamiento basta.
   - La base restaurada es idéntica: hechos, compromisos, saldos, entidades y políticas de las dos empresas, con sus dos tiempos, y la historia completa de cada compromiso.
   - Se puede ver como era antes de la última escritura, con `:al` (D17).
   - Sigue operando: una orden nueva se ejecuta y la marca de cada empresa (D74) continúa donde quedó.
2. **Restaurar solo el almacenamiento.** Simula perder el log.
   - XTDB se niega a arrancar: «invalid transaction log state (the log is empty)». No arranca en silencio con datos a medias.
   - Con una época nueva en la configuración del log (`:epoch 1`) arranca. Vuelve exactamente lo que estaba en bloques, y se pierde la orden escrita después del último bloque.
   - Sigue operando.

### Hallazgos

- **Con poco volumen, todo vive en el log.** En la primera prueba no se cerró ningún bloque: al detener el nodo, la carpeta de almacenamiento estaba vacía y el log tenía 2 archivos, unos 650 KB. Un respaldo solo del almacenamiento no habría guardado nada. En un nodo local, el log es parte del respaldo, no un complemento.
- **La pérdida máxima depende de qué se respalda.** Si se respaldan las dos piezas, no se pierde nada. Si se respalda solo el almacenamiento, se pierde hasta el intervalo de cierre de bloque: 15 minutos por defecto, ajustable con `:indexer {:flush-duration ...}`.
- **Se puede forzar un bloque.** `xtdb.log/send-flush-block-msg!` lo cierra a pedido. La prueba lo usa antes de respaldar el almacenamiento. Sirve para un respaldo programado, pero no es una API documentada para usuarios.
- **La restauración no pide nada al núcleo.** MARCO no tuvo que cambiar: abrir el almacén sobre la copia basta. El único agregado fue `nc.ejemplo/vistas`, para abrir las vistas de las empresas sin volver a cargar los datos.
- **Ver el pasado no exige restaurar.** La bitemporalidad responde «¿cómo estaba esto el día X?» sin tocar un respaldo. El respaldo queda solo para desastres.

### Lo que no se probó

- **Respaldo en caliente**, copiando con el nodo funcionando. Los archivos del almacenamiento son inmutables, así que copiarlos es seguro. El log local no lo es: puede quedar a medio escribir.
- **La configuración de producción**: log en Kafka y almacenamiento en un servicio de objetos (S3 o equivalente), con versionado y replicación. Es la que recomienda la guía oficial; el log local sirve para un solo nodo.
- **`xtdb.export/export-snapshot!`**, que exporta el último bloque a una carpeta aparte.

## 2. Costos

- **Licencia:** XTDB es código abierto, con licencia MPL 2.0. Usarlo sin modificarlo en un producto comercial no tiene costo. Si se modifica el propio XTDB, hay que publicar esos cambios; el código de Natural Commerce no queda alcanzado.
- **Soporte** (xtdb.com/support/production):

  | Plan | Precio | Qué incluye |
  |---|---|---|
  | Partner Pack | gratis | Comunidad y un canal privado |
  | Developer Edition | US$2.000 al año | Soporte oficial en horario hábil del Reino Unido |
  | Production Support | sin precio publicado | Atención 24/7 y correcciones urgentes |

- **Servicio administrado:** no hay uno oficial. Grid Dynamics lo ofrece en AWS Marketplace, con precio a negociar.
- **Infraestructura propia:** con varios nodos, hay que operar Kafka y un almacén de objetos, además de los nodos con JDK 21. Postgres, el respaldo de D27, tiene servicios administrados en todos los proveedores de nube.

## 3. Soporte y madurez

- **Proveedor único.** Lo desarrolla JUXT, una empresa pequeña. La comunidad es chica frente a la de Postgres: hay menos respuestas publicadas y menos gente que lo conozca.
- **Versión joven.** La v2 es estable desde 2025. Entre versiones menores cambiaron APIs: la configuración en Clojure de algunas versiones antiguas ya no sirve, y las bases múltiples llegaron en la 2.1. La documentación cubre la estrategia de respaldo, pero no tiene un procedimiento paso a paso para disco local; eso se resolvió leyendo el código del nodo.
- **Requisitos de plataforma.** Exige JDK 21 y opciones de JVM propias. En esta máquina fue un tropiezo (etapa 1); en un servidor es configuración.
- **Durante el prototipo se comportó bien.** Cuatro rodeos menores en seis etapas, ninguno importante (ver `mediciones.md`). La atomicidad de E7 y la historia de E4 y E5 salieron de XTDB sin diseño adicional.

## 4. Conclusión

La revisión no encuentra impedimentos para seguir con XTDB. Encuentra condiciones que deben cumplirse antes de guardar datos de clientes:

1. **Log durable y replicado.** Kafka u otro equivalente, no el log local. Hoy el log guarda todo lo que no pasó a un bloque.
2. **Almacén de objetos con versionado** y un procedimiento de respaldo ensayado: almacenamiento primero, log después, con una restauración de prueba periódica.
3. **Suscripción de soporte**, al menos Developer Edition, por el riesgo de proveedor único.
4. **Separación por empresa estructural.** Evaluar las bases múltiples de XTDB 2.1 (hallazgo de E9).

Con E1 a E7 y E9 pasando sin rodeos importantes, la sección 8 del prototipo indica seguir con Clojure y XTDB (D79).
