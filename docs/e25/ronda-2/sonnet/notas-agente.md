# Notas del agente: canon de Ventas

## Generales

- **Hecho con datos distintos de la entrada.** La notación solo muestra `[:registra-hecho tipo entrada :como x]`. Cuando el hecho lleva datos calculados (costo unitario, envío, asiento) usé un mapa de expresiones como segundo argumento, apoyado en "un mapa de expresiones es una expresión". Alternativa: que el hecho solo pueda recibir `entrada`.
- **Nulo como literal.** Uso `nil` como rama del `:si` (asiento ausente) y paso entradas opcionales sin valor a campos de mapas (`:iva entrada/iva`). La notación dice que un dato presente sin valor "vale nada", pero no dice si `nil` es un literal válido en expresiones. Alternativa: otro mecanismo de omisión.
- **Verdad de un dato.** Uso `[:si entrada/iva ...]` y `[:y entrada/cuenta-x ...]` como prueba de presencia, por analogía con `[:cuando entrada/solicitud ...]` y `[:si entrada/solicitudes false ...]` de compras. Alternativa: `[:!= x nil]`.
- **Cero con moneda.** Uso `[:* 0 entrada/monto]` para el cero de IVA y de líneas del asiento, como indica la notación.
- **Tipos primitivos.** Usé `:keyword`, `:texto`, `:fecha`, `:monto`, `:cantidad` y `[:map-of :keyword ...]` (Malli). `:keyword` y `:fecha` aparecen en compras; `:map-of` no, lo supuse por ser Malli.
- **Régimen.** Todos los tipos de hecho `:solo-agregar`, el único régimen que veo. La factura se revierte con `:revierte-hecho` y no sé si exige otro régimen.
- **Estado por defecto al abrir un compromiso.** Asumo `:activo` si no se declara, y lo escribí explícito en la factura.
- **Capacidad de los hechos y `:ventas/entrega-pendiente`.** Las referencias a tipos de compromiso (`[:ref :ventas/entrega-pendiente]`) las asumo válidas sin declarar el tipo de compromiso en ninguna parte, como en compras.

## Alta, modificación y desactivación de cliente

- `:op/entidad :cliente` para que reciban los campos propios de cada empresa (lo dice la notación). Salida `:cliente`: en el alta uso `cliente/id` (resultado nombrado); en modificar/desactivar `entrada/cliente`. Duda: si `cliente/id` es el identificador keyword dado en `:id`. Alternativa: `entrada/id`.
- El alta pasa `:id` como `:id`, y los campos con nombre completo. Asumo que un `:cupo` nulo no se escribe.
- Que un cliente desactivado no sirva como entrada lo asumo como efecto de la plataforma al desactivar; el contrato no lo dice.

## `:ventas/registrar-pedido`

- La primera abierta, la entrega pendiente, será la raíz del caso (por orden de efectos). Lo puse primero; no está declarado cuál compromiso es raíz. Alternativa: abrir primero el por-cobrar.
- Origen de ambos compromisos: `pedido/id`. El por-cobrar queda `:latente` (todavía no exigible).
- La salida `:entrega` usa `entrega/id` (nombre `:como entrega`).

## `:ventas/despachar`

- El filtro `{:tipo :inventario/reservado :raiz caso/id :estado :activo}` lo copié del ejemplo de la notación. No filtro por bodega ni producto; si una reserva fuera de otra bodega existiera en el caso, también bajaría. Alternativa: agregar `:bodega`, si el filtro admite campos del objeto.
- Pasar `:metodo entrada/metodo` aunque sea nulo: asumo que un parámetro nulo equivale a ausente.
- La reducción de la entrega la extingue en cero (D69), sin efecto explícito.

## `:ventas/emitir-factura`

- `:op/impl :tributario/envio-dte` y `impl/seguimiento` en `:envio`. No sé si el puerto necesita recibir datos; lo supuse sin entradas.
- Terminar el por-cobrar del pedido con causa `:facturado`: esa causa no está en la lista de la notación (`:cumplimiento | :cierre | :castigo | :cesion | :vencimiento`), pero la descripción la pide. La usé tal cual. Alternativa: `:cumplimiento`.
- Para encontrar el por-cobrar del pedido filtro por `:origen entrada/pedido` y `:estado :latente`. Supuse que `:origen` de un compromiso coincide con el id del hecho pedido, igual que en compras (`:origen entrada/orden`).
- El asiento es un mapa de tres líneas (`:clientes`, `:ventas`, `:iva`) con `:cuenta`, `:debe` y `:haber`; se genera solo si las tres cuentas vienen (`:y`). Con una o dos cuentas, no hay asiento. Alternativa: línea por línea.
- El saldo `:ventas/facturado` se mueve en el tipo de hecho, con clave `[:-> hecho/pedido :hecho/datos :cliente]`: asumo que `:->` sobre una referencia a hecho llega a `:hecho/datos` (como en la recepción de compras).
- El folio usado se registra con `[:cuando entrada/folio ...]` y `:documento :factura` como keyword literal.
- La salida `:cobro` es el compromiso por-cobrar nuevo (`:como cobro`).
- `:iva` y `:folio` se guardan en el hecho aunque vengan nulos (campos opcionales).

## `:ventas/registrar-cobro`

- Filtro con `:acreedor empresa` para no tocar lo cedido, apoyado en que `:transfiere-compromiso` cambia el acreedor. Si la transferencia cambia otra cosa (p. ej. extingue con `:cesion`), el filtro sobra pero no daña. Asumo que el filtro admite `:acreedor`.
- Reduce sobre una colección, recorrida del que vence antes al que vence después; no cubro el sobrepago.

## `:ventas/venta-mostrador`

- Despacho sin `:entrega` (campo opcional en el tipo). Costo unitario sin `:metodo`: omito el parámetro. Duda: si el concepto exige todos los parámetros. Alternativa: `:metodo nil`.
- No abre compromisos, como pide la descripción.

## `:ventas/emitir-nota-credito`

- Registro la nota y luego `:revierte-hecho entrada/factura` para deshacer saldo facturado y asiento; asumo que el folio usado (otro hecho) no se toca.
- La reducción del por-cobrar usa monto + IVA con `:si` repetido en la expresión (sin concepto auxiliar, porque un concepto con parámetro opcional nulo me pareció más riesgoso). El filtro `:origen entrada/factura :estado :activo` asume que el por-cobrar de la factura sigue activo y que `:reduce-compromiso` con un monto igual al saldo lo extingue. Si ya se cobró parte, reducir el total dejaría saldo negativo o extinguiría igual: no lo resolví. Alternativa: `:extingue-compromiso` con causa `:cierre`, pero la descripción dice "baja en monto + IVA".
- No hay efecto sobre el folio.

## Concepto `:credito/cupo-disponible`

- El concepto no conoce la empresa, así que no puedo filtrar `:acreedor empresa` para excluir lo cedido. Un compromiso cedido seguiría contando. Alternativa: que la cesión lo extinga.
- Sumo por separado los `:activo` y los `:latente`, porque no sé si el filtro admite varios estados ni si, sin `:estado`, incluye extinguidos. Si una de las dos colecciones está vacía, `:suma` de vacío podría no tener moneda (duda de tipo); alternativa: un solo `:suma` con filtro sin estado y un `:si` sobre `cada/estado`.
- Versión 1, `:rige-desde` arbitrario `2026-01-01` (la descripción no lo dice).
- Parámetro `{:cliente [:ref :cliente]}` (la descripción dice `:ref`).
