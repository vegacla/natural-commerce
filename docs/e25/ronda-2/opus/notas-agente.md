# Notas del agente: canon de Ventas

Leí solo `notacion-v0.md`, `compras.edn` y `descripcion-ventas.md`. No ejecuté nada. Cada nota dice qué no estaba claro, qué elegí y la alternativa.

## Generales

1. **Datos de un hecho como mapa de expresiones.** Compras siempre registra `entrada` completa. En ventas, varios hechos llevan datos calculados (costo unitario, envío, asiento, monto) o distintos de la entrada.
   - Elegí: pasar a `:registra-hecho` un mapa de expresiones, porque la notación dice que un mapa de expresiones es una expresión.
   - Alternativa: registrar `entrada` y que el ejecutor agregue los datos calculados. No hay sintaxis para eso.
2. **El literal `nil` en expresiones.** Lo uso en dos lugares: como rama "sin asiento" de un `:si` y como `:metodo` en la venta de mostrador. La notación no dice si `nil` es un literal válido. Solo dice que un dato presente sin valor "vale nada".
   - Alternativa: omitir la clave. Pero un dato ausente es un dato que falta (P4), y eso dejaría la operación indeterminada.
3. **Concepto extra `:ventas/total-con-iva`.** La descripción solo pide `:credito/cupo-disponible`. Agregué un concepto para neto + IVA, con el IVA nulo como cero, porque esa expresión se repetía en el cobro, el asiento y la nota de crédito, y la notación no tiene `let`.
   - Alternativa: repetir `[:+ entrada/monto [:si entrada/iva entrada/iva [:* 0 entrada/monto]]]` en cada lugar. Si los nombres fuera de la descripción no están permitidos, se puede volver a esa forma sin cambiar el comportamiento.
   - Duda adicional: si un concepto acepta un parámetro presente pero nulo (`:iva`).
4. **Tipos de compromiso.** Compras no declara tipos de compromiso (`:compras/necesidad` solo aparece en `:abre-compromiso` y en `[:ref ...]`). Hice lo mismo con `:ventas/entrega-pendiente` y `:ventas/por-cobrar`: no los declaro.
5. **El saldo `:ventas/facturado`.** Tampoco lo declaro aparte. La sección 2.5 dice que un saldo no es un primitivo y se define por los tipos de hecho que lo mueven.
6. **Fechas de `:concepto/rige-desde`.** Puse `#inst "2026-11-01"` (la fecha del ejemplo de la notación) y versión 1. Es arbitrario.
7. **Nombres de los resultados (`:como`).** Elegí `pedido`, `entrega`, `despacho`, `factura`, `cobro`, `boleta`, `contado` y `nota` para que las salidas sean `nombre/id`, como en compras.

## Cliente

1. **El `:id` al crear la entidad.** El ejemplo de la notación usa `{:id entrada/id :cliente/nombre ...}`, con `:id` sin espacio de nombres, y lo seguí.
2. **La salida de alta.** Usé `cliente/id`, con `:como cliente` en `:crea-entidad`.
   - Alternativa: `entrada/id`. Daría lo mismo, pero no pasaría por la entidad creada.
3. **Campos propios.** Puse `:op/entidad :cliente` en las tres operaciones. La notación dice que con eso la operación recibe los campos propios de la empresa sin nombrarlos. Supongo que el ejecutor también los aplica al crear o modificar.
   - Duda: en desactivar quizás no hace falta `:op/entidad`. Lo dejé porque la operación modifica una entidad de ese tipo.
4. **Cupo nulo en el alta.** Si no se entrega `:cupo`, el campo queda sin valor. Supongo que eso es aceptable en `:crea-entidad`.
5. **Salidas de modificar y desactivar.** Uso `entrada/cliente`, porque ningún verbo de entidad devuelve algo distinto.
6. **Cliente desactivado como entrada.** No escribí nada para impedirlo: lo entiendo como responsabilidad de la validación de referencias (D65), que comprueba que la referencia exista en la empresa.

## registrar-pedido

1. **Raíz del caso.** El pedido abre dos compromisos en la misma operación (entrega pendiente y por cobrar). No está claro cuál es la raíz.
   - Supongo que es el primero que se abre, la entrega pendiente, y lo abro primero a propósito. Así el despacho y la reserva comparten caso con ella.
   - Alternativa: que la raíz sea el por cobrar.
2. **Por cobrar "todavía no exigible".** Lo abrí con `:estado :latente`, como el pago contra factura de compras.

## despachar

1. **El costo unitario no es entrada.** Se calcula en el contrato con `:inventario/costo-unitario`, y su valor queda en los datos del despacho. No guardo `:metodo` en el hecho porque la descripción no lo lista.
   - Alternativa: guardarlo como evidencia, si el concepto fuera algorítmico. Eso lo decide el concepto, no este contrato.
2. **Bajar la existencia.** El tipo de hecho `:ventas/despacho` mueve `:inventario/existencia` con cantidad negativa y costo negativo (cantidad × costo unitario).
3. **La reserva.** Filtré `[:compromisos {:tipo :inventario/reservado :raiz caso/id :estado :activo}]`, tal como el ejemplo de la sección 5.1.
   - Duda: si hubiera varias entregas o productos en el mismo caso, este filtro podría reducir una reserva de otro producto.
   - Alternativa: agregar `:origen entrada/entrega` al filtro. No sé si la reserva guarda la entrega como origen.
   - Si la reserva es menor que lo despachado, se extingue por cumplimiento (D69).
4. **Producto y entrega.** No compruebo que el producto del despacho sea el de la entrega. Lo considero política, o validación del ejecutor.
5. **Despacho de mostrador.** `:entrega` es opcional en el tipo de hecho porque el despacho de mostrador no la tiene.

## emitir-factura

1. **Causa `:facturado`.** La notación lista las causas de extinción `:cumplimiento | :cierre | :castigo | :cesion | :vencimiento`, pero la descripción pide `:facturado`. Seguí la descripción.
   - Duda: si el validador acepta una causa fuera de esa lista.
2. **Asiento condicional.** El asiento va solo si vienen las tres cuentas: `[:si [:y c1 c2 c3] {...} nil]`. Con asiento nulo, `:cada` no mueve nada.
   - Alternativa: exigir solo algunas cuentas. Pero con una cuenta faltante el asiento quedaría descuadrado o con una línea sin cuenta.
   - Uso `:y` con referencias como valores de verdad, igual que compras usa `:si` con `entrada/solicitudes`.
3. **Los ceros del asiento.** Escribí los ceros como `[:* 0 entrada/monto]`, como recomienda la notación, y el IVA nulo como ese mismo cero.
4. **El envío.** `:envio impl/seguimiento` usa el nombre del ejemplo de la sección 5. No sé si el puerto entrega exactamente `seguimiento`.
5. **El folio.** Si trae folio, registro `:tributario/folio-usado` dentro de un `:cuando`, sin `:como`. No sé si registrar un hecho de otra capacidad requiere algo más.
6. **El cliente.** Lo saco de los datos del pedido: `[:-> entrada/pedido :hecho/datos :cliente]`, igual que la recepción de compras navega a la orden.
7. **Lo latente del pedido.** Lo encuentro con `{:tipo :ventas/por-cobrar :origen entrada/pedido :estado :latente}`.
   - Con facturación parcial, la primera factura extingue todo lo anotado y la segunda no encuentra nada, lo que no es un error.
   - Alternativa: reducir lo anotado por el monto facturado en vez de extinguirlo. La descripción dice "termina", así que extinguí.
8. **El nuevo por cobrar.** Lo abro con `:estado :activo` explícito, aunque probablemente sea el valor por defecto.

## registrar-cobro

1. **Exigible y no cedido.** "Exigible" lo leo como `:estado :activo`. "No cedido" lo leo como `:acreedor empresa`, porque supongo que transferir (ceder) cambia el acreedor.
   - Alternativa, si la cesión no cambia el acreedor: no hay cómo distinguir lo cedido con el filtro.
2. **Orden de aplicación.** Varias facturas: la reducción sobre una colección recorre del que vence antes al que vence después (sección 5.1).
3. **Pago mayor que la deuda.** No dejo registrado el exceso como saldo a favor. La descripción no lo pide.

## venta-mostrador

1. **Método por defecto.** Lo expresé como `:metodo nil` en el concepto, porque la descripción dice que con método nulo usa el promedio.
   - Alternativa: agregar una entrada opcional `:metodo` para que una política la fije. No la agregué porque la descripción fija las entradas.
2. **Bodega en la boleta.** La boleta no la lleva, porque la descripción no la lista; queda en el despacho.
3. **Enlace entre los tres hechos.** No hay compromiso que los una en un caso, y no agregué una referencia a la boleta en el despacho ni en el cobro al contado.
   - Alternativa: un campo opcional `:boleta`, para la trazabilidad.

## emitir-nota-credito

1. **Dejar la factura sin efecto.** Usé `[:revierte-hecho entrada/factura]`, porque "revertir un hecho deshace los saldos que movió": el facturado y el asiento a la vez. La forma de los argumentos de `:revierte-hecho` no aparece en ningún ejemplo, y supuse que recibe la referencia al hecho.
   - Alternativa: que el tipo `:ventas/nota-credito` mueva los saldos en negativo, con su propio asiento inverso. Es más explícito, pero duplica la lógica del asiento.
2. **Revertir y además registrar.** No sé si revertir un hecho ya registra por sí mismo un hecho de reversión. Registro la nota de crédito igual, porque la descripción la pide como documento.
3. **Lo que se cobraba.** Lo encuentro con `{:tipo :ventas/por-cobrar :origen entrada/factura :estado :activo}`, sin filtrar por acreedor.
   - Duda: si la factura se cedió, esto también reduce lo que se le debe al tercero.
   - Alternativa: filtrar `:acreedor empresa`.
4. **Monto + IVA.** Reduzco por neto + IVA leídos de la factura. Si el cliente ya pagó parte, la reducción lleva el saldo a cero o menos y lo extingue (D69). No registro el exceso como saldo a favor.

## Concepto :credito/cupo-disponible

1. **Tipo del parámetro.** La descripción da `{:cliente :ref}` y el ejemplo de la notación `{:cliente :cliente}`. Seguí la descripción.
   - Alternativa: `{:cliente [:ref :cliente]}`, más preciso con la sintaxis de Malli.
2. **Debe y deberá.** "Debe o deberá" lo leo como activo + latente. Hice dos `:suma`, porque el filtro de `:compromisos` parece aceptar un solo valor por clave.
   - Alternativa: un solo filtro sin `:estado`, si el filtro excluyera los extinguidos por defecto. No consta.
3. **Lo debido "a la empresa".** No puedo filtrar `:acreedor empresa`, porque un concepto no conoce la empresa. Por eso lo cedido a un tercero sigue contando contra el cupo.
   - Alternativa: un parámetro `:acreedor`, que cambiaría la firma pedida.
4. **Casos límite.**
   - Cliente sin cupo: la resta tiene un dato que falta, y quedaría indeterminado.
   - Cliente sin deudas: no sé en qué moneda devuelve cero un `:suma` vacío.
