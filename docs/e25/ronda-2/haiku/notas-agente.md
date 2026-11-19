# Notas del agente: canon de Ventas

Trabajé solo con `notacion-v0.md`, `compras.edn` y `descripcion-ventas.md`. No abrí otros archivos ni ejecuté nada. Cada punto dice qué no estaba claro, qué elegí y cuál era la alternativa.

## Generales

**G1. Salida que nombra una entidad.** En `alta-cliente` la salida `:cliente` podría ser `entrada/id` o `cliente/id`. Elegí `cliente/id`, por analogía con `:como` sobre `:crea-entidad`. Alternativa: `entrada/id`, que no depende de cómo se nombra el resultado de un verbo de entidad.

**G2. Tipo de los parámetros de un concepto.** La descripción dice `{:cliente :ref}` y la notación en su ejemplo usa `{:cliente :cliente}`. Elegí `[:ref :cliente]`, porque la regla de la sección 0 dice que las referencias se escriben así. Alternativa: `:cliente`, como en el ejemplo.

**G3. Entradas opcionales no entregadas.** La notación dice que una entrada opcional no entregada "vale nada y no falta", pero no dice si la clave desaparece o queda con `nil`. Usé `entrada/x` directo en todos los datos que pueden faltar (`iva`, `folio`, `metodo`, `asiento`). Si el validador rechaza un `nil` en un campo `{:optional true}`, esos datos fallan. Alternativa: que el ejecutor omita la clave cuando el valor es nulo, o que el tipo acepte `[:maybe ...]`.

**G4. Presencia como condición.** Usé `[:y entrada/a entrada/b ...]` y `[:si entrada/iva ...]`, es decir, suponer que un valor presente (monto, palabra clave) es verdadero. La notación solo lo muestra con `:cuando entrada/solicitud` (un efecto), y `:y` está definido para booleanos. Alternativa: comparar explícitamente con `nil`, o un operador de presencia.

**G5. Literal `nil` en expresiones.** Usé `nil` como valor en `[:si ... nil]` y en `:metodo nil`. La notación no lo menciona. Alternativa: omitir la clave o un operador de presencia.

**G6. Forma de `:revierte-hecho`.** La notación nombra el verbo, pero no su forma. Asumí `[:revierte-hecho <referencia al hecho>]`, con el mismo orden que `:registra-hecho`.

**G7. Reducir una colección de compromisos.** La notación dice que una reducción "los recorre del que vence antes al que vence después", pero no dice si el monto se reparte en orden hasta agotarse o si se aplica completo a cada uno. Interpreté que se reparte, porque lo necesita `registrar-cobro` ("aunque abarque varias facturas"). Alternativa: aplicar el monto a cada compromiso, que es mal comportamiento para el cobro.

**G8. Causa `:facturado`.** La descripción pide `:facturado` al extinguir el por-cobrar del pedido. La lista de causas de la notación (`:cumplimiento`, `:cierre`, `:castigo`, `:cesion`, `:vencimiento`) no lo incluye. Lo usé porque es el nombre fijo que pide la descripción. Alternativa: `:cumplimiento` (el pedido se cumple al facturarse) o ampliar la lista de causas.

**G9. Anulación de una factura.** En la nota de crédito reduje el por-cobrar a cero. La regla D69 lo extingue entonces por cumplimiento, aunque la factura se anuló y no se cumplió. La notación no tiene causa de anulación. Alternativa: `:extingue-compromiso` con `:cierre`, o una causa nueva.

**G10. Cesiones.** La notación no dice cómo `:transfiere-compromiso` marca una cesión: si cambia el acreedor, el estado o ambos. En `registrar-cobro` filtré por `:acreedor empresa`, así que supuse que la cesión cambia el acreedor. En el concepto `credito/cupo-disponible` no puedo filtrar por empresa (un concepto no la conoce), así que el cupo puede contar lo cedido a un tercero. Alternativa: pasar la empresa como parámetro del concepto, o que la cesión tenga un estado propio.

**G11. Concepto propio `:ventas/total-factura`.** No estaba en la descripción. Lo agregué para no repetir "monto más IVA, o monto si no hay IVA" en cinco lugares (por-cobrar, asiento, nota de crédito). Es una decisión mía; si el equipo prefiere lo contrario, se puede quitar y volver a escribir la expresión en cada lugar.

**G12. Un pedido abre dos compromisos sin compromiso de origen.** La entrega y el por-cobrar tienen como origen el pedido (un hecho), no un compromiso. La notación define la raíz como "el primer compromiso de la cadena", así que cada uno sería raíz de su propio caso. Eso no encaja con "un caso por venta". Lo dejé así, sin resolverlo.

**G13. Tipos de compromiso.** Siguiendo `compras.edn`, no declaré `:ventas/entrega-pendiente` ni `:ventas/por-cobrar` como tipos aparte: aparecen solo en `:abre-compromiso`. Si el validador exige declararlos, faltan.

**G14. Entidad `:cliente` y su desactivación.** La notación no dice cómo se rechaza una entidad desactivada como entrada. Supuse que lo hace el validador al revisar la referencia `[:ref :cliente]`. Tampoco dice dónde se guarda el estado "desactivado": no declaré campo de estado.

**G15. Puerto `:tributario/envio-dte`.** No sé qué datos recibe el puerto. Lo declaré solo con `:op/impl`, sin entradas, porque la descripción no dice nada. Si el puerto necesita el documento, falta una entrada.

**G16. `:op/inverso`.** `compras.edn` declara uno en `emitir-orden`. La descripción no pide anulaciones de operación, así que no declaré ninguno.

**G17. Campos de los contratos de entidad.** Puse `:op/entidad :cliente` en alta, modificación y desactivación, según la notación (sección 5). Sin esto no se recibirían los campos propios de cada empresa.

## Operaciones

### Cliente: `alta-cliente`
- **No estaba claro:** el tipo del `:id`. La descripción dice "palabra clave", así que usé `:keyword`. También G1 para la salida.
- **Elegí:** `[:crea-entidad :cliente {:id entrada/id ...} :como cliente]`, y el `:cupo` opcional llega como `nil` si no se entrega (ver G3).
- **Alternativa:** hacer `:cupo` obligatorio, o un valor por defecto de cero.

### Cliente: `modificar-cliente`
- **No estaba claro:** si `nil` en `:nombre` o `:cupo` deja el campo igual. La notación dice que un valor nulo no cambia el campo, así que lo usé tal cual.
- **Elegí:** `[:modifica-entidad entrada/cliente {...}]`, sin condiciones.
- **Alternativa:** un `[:cuando ...]` por campo, si el ejecutor no aplica la regla de nulos.

### Cliente: `desactivar-cliente`
- **No estaba claro:** cómo se representa "desactivado" (ver G14).
- **Elegí:** solo `[:desactiva-entidad entrada/cliente]`.
- **Alternativa:** un campo `:cliente/activo` que `:ventas/registrar-pedido` revise, pero eso sería lógica en el contrato, que la notación no permite.

### `registrar-pedido`
- **No estaba claro:** el deudor de la entrega. La descripción dice "compromiso de la empresa con el cliente", así que `deudor` es la empresa y `acreedor` el cliente. Además, la descripción no dice qué estado tiene el por-cobrar; la notación dice que la obligación que nace con la orden "se activa con la factura", así que lo puse `:latente`.
- **Elegí:** el por-cobrar como `:latente`, con origen el pedido (ver G12).
- **Alternativa:** `:activo` con una política que lo ignore en el cupo; eso mezcla lógica en el dato.

### `despachar`
- **No estaba claro:** de dónde sale el costo unitario. La descripción dice que se calcula con `:inventario/costo-unitario`, así que lo puse en el dato del hecho.
- **Elegí:** el costo se calcula dentro de `:registra-hecho` (la expresión es un mapa, así que se evalúa cada valor).
- **Alternativa:** un `:como` sobre un efecto que calcule el costo, aunque la notación no tiene efecto de cálculo.
- **No estaba claro:** `:metodo` opcional. Supuse que llega como `nil` cuando falta y que el concepto usa el promedio (la notación dice "con `:metodo` nulo usa el costo promedio").
- **Reserva:** elegí filtrar por `:origen entrada/entrega`. La descripción dice "en el caso de esta venta" y "para la entrega", y la notación dice que la reserva pertenece al mismo caso. Alternativa: `:raiz caso/id`, que da lo mismo si la raíz es la entrega, pero no si hubiera más compromisos.
- **Reserva:** si la cantidad despachada es mayor que la reservada, no sé si el saldo queda negativo o se recorta a cero. La notación no lo dice; no lo resolví.
- **Reducción de la entrega:** la entrega es una sola referencia, así que la reducción no reparte nada (ver G7 para varias).

### `emitir-factura`
- **No estaba claro:** cuándo hay asiento. La descripción dice "solo si vienen las cuentas", pero son tres (clientes, ventas e IVA). Elegí exigir las tres. Alternativa: exigir solo clientes y ventas, con el IVA en cero si no viene su cuenta; eso sería coherente con "sin IVA se cuenta como cero", pero rompería si viene IVA sin cuenta.
- **No estaba claro:** cómo el `:asiento` se convierte en las tres líneas que mueve `:cada`. Usé un mapa con las claves `:clientes`, `:ventas` e `:iva` (la notación muestra la forma `{:clientes {...} :ventas {...}}`).
- **No estaba claro:** el saldo `:ventas/facturado` tiene clave `{:cliente ...}`, pero la factura no guarda el cliente. Lo tomé del pedido con `[:-> hecho/pedido :hecho/datos :cliente]`.
- **No estaba claro:** `:envio impl/seguimiento`. La notación lo muestra así, pero no dice si el puerto se ejecuta antes de registrar el hecho. Supuse que sí (G15).
- **Salida `:cobro`:** la descripción la llama `:cobro` y es el compromiso por-cobrar. Lo nombré `:como por-cobrar` para no confundirlo con el hecho `:ventas/cobro`, que es otra cosa.
- **Causa `:facturado`:** ver G8.
- **Folio:** `[:cuando entrada/folio ...]` registra el folio solo si viene. Usé la forma `[:cuando condición efecto]` de la notación.
- **Por-cobrar nuevo:** `:estado :activo` explícito, porque la descripción dice "exigible". Su monto usa `:ventas/total-factura` (ver G11).
- **Riesgo:** el `:iva` opcional no entregado queda como `nil` dentro de un campo `{:optional true}`. Ver G3.

### `registrar-cobro`
- **No estaba claro:** si el reparto va por fecha de vencimiento. La notación dice "del que vence antes al que vence después", así que lo dejé en manos de la reducción (ver G7).
- **No estaba claro:** cómo excluir lo cedido. Filtré por `:acreedor empresa` (ver G10).
- **Nombre:** `:ventas/cobro` es también el nombre de un compromiso en los ejemplos de la notación (secciones 2.3 y 2.4). Lo usé como tipo de hecho, como pide la descripción. Puede confundir.

### `venta-mostrador`
- **No estaba claro:** `:ventas/despacho` exige `:entrega` en `despachar`, pero aquí no hay entrega. Hice `:entrega` opcional en el tipo del hecho (decisión mía, en `tipos-hecho`).
- **No estaba claro:** el método de costo. La descripción dice "el método por defecto", así que puse `:metodo nil`.
- **No estaba claro:** `:ventas/cobro-contado` solo lleva `:monto`. No liga a la boleta, porque la descripción no lo pide. Si el equipo quiere trazabilidad, falta un campo `:boleta`.
- **Monto:** `cantidad × precio` aparece en la boleta y en el cobro. Lo repetí en vez de usar `:como`, porque un mapa no tiene nombres de resultado.

### `emitir-nota-credito`
- **No estaba claro:** el argumento de `:revierte-hecho` (G6).
- **No estaba claro:** si revertir la factura basta para quitar su monto del saldo `:ventas/facturado` y deshacer su asiento. Supuse que sí, porque la notación dice que "revertir deshace los saldos que movió", y la factura mueve ambos.
- **Por-cobrar:** lo baja en monto más IVA con `:ventas/total-factura`, y la nota toma esos datos de la factura con `[:-> entrada/factura :hecho/datos ...]`. Riesgo: la extinción automática por cumplimiento (G9).
- **Folio:** no se devuelve, como pide la descripción. No hay efecto para eso.

### `credito/cupo-disponible` (concepto)
- **No estaba claro:** los compromisos se filtran por un solo `:estado`, pero el cupo suma los activos y los latentes. Hice dos sumas. Alternativa: un filtro que acepte varios estados, si la notación lo permite.
- **No estaba claro:** qué pasa con un cupo `nil` (cliente sin cupo). El concepto daría error al restar. No lo resolví.
- **Cesiones:** el concepto filtra solo por `:deudor cliente`, y no conoce la empresa (G10). Lo cedido podría contarse.
