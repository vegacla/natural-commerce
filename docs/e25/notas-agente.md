# E25: dudas y supuestos al escribir ventas.edn

## Generales

- **Restar de un saldo.** `:tipo-hecho/mueve` solo muestra `:suma`. Para que el despacho baje la existencia sumé valores negativos: `[:* -1 hecho/cantidad]`. No sé si el evaluador acepta multiplicar una cantidad por un número suelto, ni si existe una forma propia para restar.
- **Monto cero.** Necesitaba "IVA cero" sin fijar una moneda. Usé `[:* 0 entrada/monto]` para que el cero quede en la moneda del neto. `[:monto 0 :CLP]` habría fijado pesos.
- **Datos de un hecho armados en el contrato.** Compras siempre pasa `entrada` a `:registra-hecho`. Yo pasé un mapa con expresiones (como `:objeto` en `:abre-compromiso`). Supuse que se admite.
- **Vector literal en una expresión.** El asiento es un vector de mapas. Supuse que un vector que no empieza con palabra clave es un valor literal y no una operación.
- **Fecha de `:rige-desde` del concepto.** No la da la descripción. Puse `#inst "2026-11-01"` y versión 1.

## Cliente (alta, modificar, desactivar)

- **Verbos de entidad sin sintaxis.** La notación nombra `:crea-entidad`, `:modifica-entidad` y `:desactiva-entidad`, pero no muestra cómo se escriben. Por analogía con `:registra-hecho` usé:
  - `[:crea-entidad :cliente entrada :como cliente]`;
  - `[:modifica-entidad entrada/cliente entrada]`;
  - `[:desactiva-entidad entrada/cliente]`.
- **De entrada a campos.** Supuse que el ejecutor convierte `:nombre` y `:cupo` en `:cliente/nombre` y `:cliente/cupo`, y que usa `:id` como identificador. Pasar la entrada completa deja pasar los campos propios (`:cliente.b/giro`), porque el `[:map ...]` de Malli es abierto. No sé si el ejecutor hace ese mapeo. La otra opción era un mapa explícito, pero no habría admitido los campos propios.
- **Sin hecho.** Las operaciones de entidad no registran ningún hecho. La regla "va siempre con un hecho" (D61) es solo para los verbos de compromiso.
- **Cliente desactivado.** Supuse que la validación de referencias rechaza solo un cliente desactivado como entrada de otra operación, y que el contrato no tiene que decirlo.

## registrar-pedido

- **Compromiso por cobrar latente.** "Todavía no es exigible" lo escribí como `:estado :latente`.
- **Raíz del caso.** No sé cuál de los dos compromisos queda como raíz del caso. Ambos tienen origen en el pedido, y supuse que el primero que se abre (la entrega) es la raíz.

## despachar

- **Entrega opcional en el despacho.** En el tipo de hecho `:ventas/despacho`, `:entrega` quedó opcional porque la venta en mesón despacha sin entrega.
- **Cómo encontrar la reserva.** Busqué `{:tipo :inventario/reservado :raiz caso/id :estado :activo}`. No sé si `caso/id` es la forma correcta de nombrar el caso de la operación (la tomé de `consultar-estado` de compras). Tampoco sé si el filtro de `:compromisos` puede mirar el objeto (`:producto`, `:bodega`). Por eso no filtré por producto ni bodega: si el caso tiene reservas de varios productos, bajaría todas.
- **`:existe` antes de reducir.** Lo puse por seguridad, porque no sé si `:reduce-compromiso` sobre una colección vacía no hace nada o da error.
- **Llamar al concepto.** Llamé a `:inventario/costo-unitario` con `:metodo entrada/metodo`. Si el método no viene, va nulo y usa el promedio.

## emitir-factura

- **Llamar al puerto (la duda principal).** La notación no tiene forma de llamar a un puerto. `:op/impl` es una función de la operación entera, no un puerto con nombre. Lo escribí como una llamada de concepto, `[:tributario/envio-dte {...}]`, dentro del dato `:envio`. Pero una expresión no tiene efectos, y enviar al SII es un efecto. Tampoco sé qué parámetros recibe el puerto.
- **Asiento con varias líneas.** No encontré cómo un hecho mueve un saldo por cada elemento de una colección. Inventé la clave `:por-cada hecho/asiento` en `:tipo-hecho/mueve`, con `cada/cuenta`, `cada/debe` y `cada/haber`. Si el asiento es nulo, no mueve nada. Una alternativa con solo lo documentado: registrar tres hechos de "línea de asiento" bajo un `:cuando`.
- **Cuándo hay asiento.** Hay asiento solo si vienen las tres cuentas (`[:y ...]`). Si falta una, no hay asiento.
- **Terminar lo anotado del pedido.** Lo hice con `:extingue-compromiso ... :facturado`. La causa `:facturado` no está en la lista de causas de la notación, pero la descripción la pide.
- **Filtros por origen.** Para encontrar lo anotado del pedido filtré `:origen entrada/pedido`, porque el origen es el hecho del pedido.
- **Cliente de la factura.** El deudor y la clave del saldo `:ventas/facturado` se sacan con `[:-> entrada/pedido :hecho/datos :cliente]`, como en la recepción de compras.
- **Saldo `:ventas/facturado` no declarado.** No lo declaré en ningún lado: según la notación, un saldo nace de los `:mueve`.

## registrar-cobro

- **Orden de aplicación.** El pago se aplica sobre la colección de `:ventas/por-cobrar` activos del cliente con `:acreedor empresa`. Supuse que reducir una colección va de lo que vence antes a lo que vence después (el comentario de compras sobre `:solicitudes`).
- **Lo cedido.** Supuse que ceder cambia el acreedor, y por eso el filtro por acreedor deja fuera lo cedido.
- **Pago mayor que la deuda.** No sé qué pasa con el excedente, y no lo traté.

## venta-mostrador

- **Método por defecto.** El método va nulo (`:metodo nil`): "con el método por defecto" lo leí como costo promedio. La operación no recibe `:metodo`, así que una política de valorización no podría fijarlo aquí. Quizás debería aceptar `:metodo` opcional, como `despachar`.
- **Monto del cobro.** El cobro al contado toma el monto de la boleta (`boleta/monto`). No lo enlacé a la boleta, porque la descripción solo pide el monto.

## emitir-nota-credito

- **Deshacer la factura.** Lo hice con `[:revierte-hecho entrada/factura]`. Supuse que revertir un hecho deshace los saldos que movió: el facturado y las cuentas del asiento. El folio usado es otro hecho y no se toca. La notación no dice qué hace exactamente `:revierte-hecho` con los saldos. La alternativa era que la nota de crédito tuviera su propio `:mueve` con signo negativo.
- **IVA de la factura.** El monto a reducir se lee de la factura: `[:-> entrada/factura :hecho/datos :iva]`, y cero si no tiene.
- **Factura cedida.** Si la factura se cedió, igual se reduce lo que se cobraba por ella, porque no filtré por acreedor.

## credito/cupo-disponible

- **Tipo del parámetro.** Usé `:params {:cliente :ref}`, como pide la descripción, aunque el ejemplo de la notación usa `{:cliente :cliente}`.
- **"No extinguido".** El filtro de `:compromisos` no permite decir "estado distinto de extinguido". Por eso sumé los activos y los latentes por separado.
- **Cliente sin cupo.** Si el cliente no tiene cupo, la resta queda con un nulo. No sé qué devuelve el evaluador en ese caso.
