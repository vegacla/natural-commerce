# Ventas: descripción funcional

Lo que recibe el agente de E25, junto con `notacion-v0.md` y el canon de compras (`compras.edn`). Dice qué hace cada operación de ventas y con qué nombres, no cómo se escribe su contrato.

**Entregable:** un archivo `ventas.edn` con el canon de la capacidad Ventas: el mapa de nivel superior lleva `:entidades`, `:tipos-hecho`, `:conceptos` y `:contratos`, igual que `compras.edn`. Los nombres de esta descripción son fijos: otras capacidades, las políticas de las empresas y las pruebas los usan.

## Lo que ya existe en otras capacidades

Se puede usar; no se declara de nuevo.

- **Entidades** `:producto`, `:bodega` y `:proveedor`, declaradas en compras.
- **Saldo `:inventario/existencia`**, con la clave `{:producto … :bodega …}`. Tiene dos componentes:
  - `:cantidad`, las unidades en la bodega;
  - `:costo`, su valor total.

  La recepción de compras lo aumenta. Lo que sale de una bodega lo disminuye en la cantidad y en cantidad × costo unitario.
- **Compromiso `:inventario/reservado`**. Lo abre la operación `:inventario/reservar`, que recibe una entrega pendiente de ventas (`:entrega`), un producto, una bodega y una cantidad.
  - Es una reserva de stock interna: la empresa se la debe a sí misma.
  - Su objeto es `{:producto … :bodega … :cantidad …}`.
  - Pertenece al mismo caso que la entrega que lo motivó.
- **Concepto `:inventario/costo-unitario`**, de tipo monto, con los parámetros `{:producto :bodega :cantidad :metodo}`.
  - Calcula el costo unitario de lo que sale de una bodega.
  - Con `:metodo` nulo usa el costo promedio. El método de valorización lo fija una política de la empresa.
- **Saldo `:contabilidad/cuenta`**, con la clave `{:cuenta …}` y los componentes `:debe` y `:haber`. Las cuentas son palabras clave, como `:cuenta/c1101`.
- **Tipo de hecho `:tributario/folio-usado`**, con los datos `{:documento :keyword, :folio :cantidad}`.
  - Registrarlo consume un folio del saldo `:tributario/folios`.
  - Un folio usado no se devuelve, aunque el documento se anule.
- **Puerto `:tributario/envio-dte`**, el envío del documento tributario al SII.
  - Una implementación registrada en la plataforma lo atiende; el contrato solo lo nombra.
  - Entrega un código de seguimiento (texto), que se guarda en el documento.
- **La capacidad Finanzas** cede facturas a un tercero: transfiere lo que se cobra por una factura. Para encontrarlo busca el compromiso `:ventas/por-cobrar` activo cuyo origen es la factura.
- **Políticas de la empresa y legales** llenan entradas de las operaciones de ventas: el precio de lista, el IVA, el folio, las cuentas contables y el método de valorización. Estas entradas son opcionales en el contrato, y la operación funciona sin ellas.

## Cliente

Entidad `:cliente`, con dos campos:

- `:cliente/nombre`, de tipo texto;
- `:cliente/cupo`, de tipo monto: el crédito máximo que se le da.

Cada empresa puede agregar campos propios al cliente; un cliente de la empresa B puede tener `:cliente.b/giro`. Un campo propio no cambia el canon, y el alta y la modificación también lo reciben.

| Operación | Entradas | Salidas | Qué hace |
|---|---|---|---|
| `:ventas/alta-cliente` | `:id` (palabra clave, p. ej. `:cliente/ferreteria`), `:nombre` (texto), `:cupo` (monto, opcional) | `:cliente` | Crea el cliente con ese identificador. |
| `:ventas/modificar-cliente` | `:cliente` (referencia a cliente), `:nombre` y `:cupo` (opcionales) | `:cliente` | Cambia los datos que se entregan. |
| `:ventas/desactivar-cliente` | `:cliente` | `:cliente` | Desactiva el cliente. Un cliente desactivado ya no sirve como entrada de otra operación. |

## Venta con pedido

La venta con pedido tiene tres pasos: pedir, despachar y facturar. Después se cobra.

### `:ventas/registrar-pedido`

- **Entradas:** `:cliente`, `:producto`, `:cantidad` y `:precio-unitario` (monto).
- **Salidas:** `:pedido` (el documento del pedido) y `:entrega` (lo que queda por entregar).
- **Qué hace:**
  - Registra el documento `:ventas/pedido`, con el cliente, el producto, la cantidad y el precio unitario.
  - Deja pendiente la entrega al cliente: un compromiso `:ventas/entrega-pendiente` de la empresa con el cliente, por el producto y la cantidad.
  - Deja anotado lo que el cliente deberá pagar: un compromiso `:ventas/por-cobrar` del cliente con la empresa, por cantidad × precio unitario.
    - Todavía no es exigible, porque no hay factura.
    - Su origen es el pedido.
    - Sirve para calcular el cupo disponible del cliente.

### `:ventas/despachar`

- **Entradas:**
  - `:entrega`, una referencia a la entrega pendiente;
  - `:producto` y `:bodega`;
  - `:cantidad`, que puede ser parcial;
  - `:metodo`, el método de valorización: palabra clave, opcional, la fija una política.
- **Salidas:** `:despacho`.
- **Qué hace:**
  - Registra el documento `:ventas/despacho`, con la entrega, el producto, la bodega, la cantidad y el costo unitario (`:costo-unitario`).
    - El costo unitario se calcula con `:inventario/costo-unitario`, usando la cantidad y el método.
    - El despacho saca de la bodega lo despachado, en unidades y en costo.
  - Reduce la entrega pendiente en la cantidad despachada.
  - Si en el caso de esta venta hay stock reservado para la entrega, la reserva baja en la misma cantidad. Si no hay reserva, no pasa nada más.

### `:ventas/emitir-factura`

- **Entradas:**
  - `:pedido`;
  - `:monto`, el neto, sin IVA;
  - `:fecha` y `:vencimiento`;
  - `:iva` (monto, opcional; lo fija una política legal);
  - `:folio` (cantidad, opcional; lo fija una política legal);
  - `:cuenta-clientes`, `:cuenta-ventas` y `:cuenta-iva` (palabras clave, opcionales; las fija una política de la empresa).
- **Salidas:** `:factura` y `:cobro`, que es lo que se le cobra al cliente.
- **Qué hace:**
  - Registra el documento `:ventas/factura`, con estos datos:
    - el pedido, el monto, el IVA, el folio, la fecha y el vencimiento;
    - `:envio`, el código de seguimiento del puerto `:tributario/envio-dte`;
    - `:asiento`, el asiento contable.
  - Si trae folio, registra además el folio usado, con `:documento :factura`.
  - Lo que el pedido dejó anotado por cobrar termina, con causa `:facturado`.
  - Abre lo que el cliente debe pagar: un `:ventas/por-cobrar` del cliente del pedido con la empresa, exigible, por monto + IVA. Vence en la fecha de vencimiento y su origen es la factura.
  - Suma el monto neto al saldo `:ventas/facturado` del cliente (clave `{:cliente …}`, componente `:monto`).
  - **Asiento:** solo si vienen las cuentas. Si no vienen, la factura no lleva asiento y no mueve cuentas. El asiento tiene tres líneas:

    | Línea | Cuenta | Debe | Haber |
    |---|---|---|---|
    | Clientes | `:cuenta-clientes` | monto + IVA | 0 |
    | Ventas | `:cuenta-ventas` | 0 | monto |
    | IVA | `:cuenta-iva` | 0 | IVA |

    Cada línea mueve el saldo `:contabilidad/cuenta` de su cuenta. Sin IVA, se cuenta como cero.

### `:ventas/registrar-cobro`

- **Entradas:** `:cliente` y `:monto`.
- **Salidas:** `:cobro`.
- **Qué hace:**
  - Registra el documento `:ventas/cobro`, con el cliente y el monto.
  - Aplica el pago a lo que el cliente debe y ya es exigible, aunque abarque varias facturas.
  - No toca lo que la empresa cedió a un tercero: eso ya no se le debe a la empresa.

## Venta en mesón

`:ventas/venta-mostrador`: una venta al contado, pagada en el momento.

- **Entradas:** `:producto`, `:bodega`, `:cantidad` y `:precio-unitario`.
- **Salidas:** `:boleta`.
- **Qué hace:** en una sola operación registra tres documentos y no deja nada pendiente:
  1. la boleta `:ventas/boleta`, con el producto, la cantidad, el precio unitario y el monto (cantidad × precio);
  2. el despacho `:ventas/despacho`, que saca la mercadería de la bodega con su costo unitario, calculado con el método por defecto;
  3. el cobro al contado `:ventas/cobro-contado`, con el monto.

## Nota de crédito

`:ventas/emitir-nota-credito` anula una factura entera.

- **Entradas:** `:factura` y `:fecha`.
- **Salidas:** `:nota`.
- **Qué hace:**
  - Registra el documento `:ventas/nota-credito`, con la factura, su monto neto y la fecha.
  - Deja la factura sin efecto: su monto deja de contar en el saldo facturado del cliente, y su asiento se deshace.
  - Lo que se cobraba por esa factura baja en monto + IVA.
  - No devuelve el folio.

## Cupo disponible

Concepto `:credito/cupo-disponible`, de tipo monto, con el parámetro `{:cliente :ref}`. Es el cupo del cliente menos todo lo que el cliente debe o deberá a la empresa en ventas: lo exigible y lo anotado por pedidos aún sin facturar. No cuenta lo ya terminado. Una política de la empresa lo usa para limitar un pedido nuevo.
