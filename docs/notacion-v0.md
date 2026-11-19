# Natural Commerce — Notación del núcleo, borrador v0

Fecha: 2026-11-16. Etapa 1 del prototipo mínimo. Se apoya en las decisiones del registro.
Estado: las preguntas marcadas en el texto (P1 a P12) están resueltas en la sección 4 de `registro-de-decisiones.md`, P5 y P6 se resolvieron al construir la etapa 3 (D65, D66) y P12 en la etapa 6 (D78). Donde difieren, manda el registro.

## 0. Convenciones

- Todo se escribe en EDN, el formato de datos de Clojure. Contratos, conceptos, políticas, caminos y hechos son datos.
- Los nombres llevan espacio de nombres: `:compras/emitir-orden`, `:producto/categoria`.
- En las expresiones, un vector que empieza con palabra clave es una operación (`[:> a b]`) y un símbolo es una referencia a un dato (`entrada/cantidad`, `actor`, `ahora`).
- Todo lo que cambia en operación (conceptos, políticas, caminos) lleva `:version` y `:rige-desde`. Los contratos de operación cambian solo con un despliegue (D11).
- Los tipos (`[:ref :proveedor]`, `[:map ...]`) usan la sintaxis de Malli y no son expresiones. Una referencia nombra el tipo de lo que apunta: una entidad, un tipo de hecho o un tipo de compromiso (`[:ref :compras/necesidad]`, D77).
- Un valor con unidad se escribe siempre con ella, en datos y en expresiones: `[:monto 500000 :CLP]`, `[:cantidad 20 :un]`. Un número suelto no es un monto ni una cantidad.
- Todo valor calculado que se guarda lleva la versión de lo que lo calculó (D48). La referencia tiene una sola forma, un mapa de identificador a versión, en `:hecho/reglas`, `:compromiso/reglas`, `:saldo/reglas` y `:camino/reglas`. El mapa incluye políticas y conceptos.

**[P1]** Resuelto al construir la etapa 1: operaciones como palabras clave y referencias como símbolos. EDN los lee sin ayuda y el validador los distingue sin ambigüedad.

## 1. Tiempo

Cada dato tiene dos tiempos:

- **Registro**: cuándo lo supo el sistema. Lo pone la base de datos y no se edita.
- **Validez**: desde cuándo rige. Por defecto coincide con el registro. Se distingue cuando algo se carga hoy para regir más adelante: una lista de precios, una política nueva, un anexo de contrato (H9 del análisis).

Ver cualquier dato en cualquier fecha (D17) es consultar ambos tiempos en ese instante. XTDB los entrega de fábrica. MARCO entero puede mirar una empresa en otra fecha: consulta y resuelve como entonces, pero no ejecuta (D73).

## 2. Primitivos

### 2.1 Entidad

Algo que existe y cambia. El canon define su tipo; la empresa puede agregar campos (D3).

```clojure
{:entidad/tipo   :producto
 :entidad/campos {:producto/codigo    [:texto {:unico true}]
                  :producto/nombre    :texto
                  :producto/categoria [:ref :categoria]
                  :producto/unidad    :unidad}
 :entidad/extensible true}

;; campo propio de la Empresa B, guardado como dato de configuración
{:extension/empresa :empresa-b
 :extension/tipo    :producto
 :extension/campo   [:producto.b/requiere-certificado :booleano]}
```

### 2.2 Hecho

Algo que ocurrió. No se modifica: se revierte con otro hecho (D16).

```clojure
{:hecho/id     #uuid "..."
 :hecho/tipo   :compras/recepcion
 :hecho/en     #inst "2026-12-04T10:15"
 :hecho/actor  :persona/ana
 :hecho/datos  {:orden #uuid "..." :producto :P-100 :bodega :central
                :cantidad [:cantidad 12 :un]}
 :hecho/causa  {:operacion :compras/registrar-recepcion :plan #uuid "..."}
 :hecho/caso   #uuid "..."                                         ; compromiso raíz del caso (D68)
 :hecho/reglas {:empresa-b/compra-formal 3 :legal.cl/periodo 1    ; versiones de las políticas
                :costo/promedio 1}                                 ; y conceptos aplicados (D48)
 :hecho/evidencia {:costo/promedio [:monto 4210 :CLP]}}            ; valores algorítmicos usados (D38)
```

`:hecho/reglas` y `:hecho/evidencia` responden "por qué se hizo esto" sin recalcular nada. La evidencia guarda el valor; la versión del concepto que lo calculó queda en `:hecho/reglas`.

Cada tipo de hecho se declara una vez (D57): sus datos, su régimen (D16) y los saldos que mueve.

```clojure
{:tipo-hecho/id      :compras/recepcion
 :tipo-hecho/datos   [:map
                      [:orden [:ref :compras/orden]]
                      [:producto [:ref :producto]]
                      [:bodega [:ref :bodega]]
                      [:cantidad :cantidad]]
 :tipo-hecho/regimen :solo-agregar
 :tipo-hecho/mueve   [{:saldo :inventario/existencia
                       :clave {:producto hecho/producto :bodega hecho/bodega}
                       :suma  {:cantidad hecho/cantidad}}]}   ; el costo lo fija la política de valorización
```

Un movimiento resta sumando un negativo: `{:cantidad [:* -1 hecho/cantidad]}`.

Con `:cada`, el hecho mueve el saldo una vez por cada elemento de una colección de sus datos (D95). Ese elemento se nombra `cada`. Un asiento contable es un mapa de líneas, y cada línea mueve el saldo de su cuenta. Si la colección es nula, no se mueve nada.

```clojure
{:saldo :contabilidad/cuenta
 :cada  hecho/asiento                      ; {:clientes {:cuenta … :debe … :haber …} :ventas {…}}
 :clave {:cuenta cada/cuenta}
 :suma  {:debe cada/debe :haber cada/haber}}
```

Revertir un hecho (`:revierte-hecho`) deshace los saldos que movió.

### 2.3 Compromiso

Algo pendiente entre partes. Se guarda (D29).

```clojure
{:compromiso/id       #uuid "..."
 :compromiso/tipo     :compras/recepcion-pendiente
 :compromiso/deudor   :proveedor/acme          ; debe cumplir
 :compromiso/acreedor :empresa-b               ; si coincide con el deudor, es interno
 :compromiso/objeto   {:producto :P-100 :cantidad [:cantidad 20 :un]}
 :compromiso/saldo    {:cantidad [:cantidad 8 :un]}
 :compromiso/estado   :activo                  ; :latente | :activo | :extinguido
 :compromiso/vence    #inst "2026-12-20"
 :compromiso/origen   #uuid "..."              ; compromiso o hecho que lo generó
 :compromiso/raiz     #uuid "..."              ; primer compromiso de la cadena
 :compromiso/reglas   {:empresa-b/compra-formal 3}   ; versiones con que se abrió (D48)
 :compromiso/extincion nil}                    ; {:causa :cumplimiento | :cierre | :castigo | :cesion | :vencimiento, :hecho #uuid}
                                               ; o la causa que nombre el contrato: [:extingue-compromiso x :facturado]
```

- **Latente** es el compromiso condicional (H2): una oferta que obliga solo si se acepta, o la obligación de pagar que nace con la orden pero se activa con la factura. Lo activa, con `:modifica-compromiso`, la operación que registra el hecho esperado (sección 5.1, D56); ningún compromiso cambia de estado sin una operación.
- **Saldo**: por defecto, los montos y cantidades del objeto; `:abre-compromiso` puede declararlo con `:saldo`. Si una reducción lo deja en cero o menos, el compromiso se extingue por cumplimiento en la misma operación (D69).
- **Raíz** identifica el caso sin necesidad de una instancia de proceso (D15). Es el primer compromiso de la cadena: la solicitud en la Empresa B, la orden en la Empresa A. Las versiones de su `:compromiso/reglas` son las que rigen el caso para las políticas con `:inicio-del-caso`. Quien inició el caso es el actor del hecho que abrió la raíz: `[:-> caso/origen :hecho/actor]`. El caso de una operación se deduce de sus entradas, y cada hecho lo guarda en `:hecho/caso` (D68).

**[P2]** Resuelto: bastan tres estados. "Vencido" no es un estado: se calcula comparando `:vence` con `ahora`. Extinguir un compromiso por vencimiento es una operación, que una política "inicia" puede plantear a MARCO (D56).

### 2.4 Calendario

Un compromiso con calendario genera compromisos periódicos (D39): contratos marco, contratos de trabajo, arriendos, planes de pago.

```clojure
{:compromiso/tipo       :finanzas/plan-de-pago
 :compromiso/deudor     :cliente/c-17
 :compromiso/acreedor   :empresa-b
 :compromiso/calendario {:cuotas 6 :cada [:meses 1] :desde #inst "2027-01-05"
                         :monto [:/ origen/total 6]}
 :compromiso/genera     :finanzas/cuota-por-cobrar}
```

Cada cuota es un compromiso propio, con origen en el plan. El contrato no se modifica durante su vigencia. Cambia por vía legal (D45), siempre con un hecho nuevo:

```clojure
{:hecho/tipo  :contrato/anexo
 :hecho/datos {:contrato #uuid "..."
               :via      :bilateral              ; :bilateral | :unilateral | :judicial | :legal
               :rige-desde #inst "2027-04-01"
               :calendario {:cuotas 4 :cada [:meses 1] :monto [:monto 250000 :CLP]}}}
```

El anexo reemplaza el calendario desde su fecha. Las cuotas ya generadas no cambian. La operación que registra el anexo aplica el reemplazo con `:modifica-compromiso` (D56).

**[P3]** Vías de modificación: resuelto como política legal.

### 2.5 Saldo

Un valor acumulado que se guarda (D41), con la versión de la regla que lo produjo (D48). No es un cuarto primitivo: se deriva de los hechos, y cada tipo de hecho declara qué saldos mueve (sección 2.2).

```clojure
{:saldo/concepto :inventario/existencia
 :saldo/clave    {:producto :P-100 :bodega :central}
 :saldo/valor    {:cantidad [:cantidad 12 :un] :costo [:monto 50520 :CLP]}
 :saldo/reglas   {:empresa-b/valorizacion 1}}     ; costo promedio, versión 1
```

Lo actualiza el ejecutor al registrar cada hecho, en la misma transacción. La regla de valorización se aplica con la opción `:cierre-del-periodo` (D44, D50): solo cambia al cierre del ejercicio anual, y desde ahí el saldo continúa con la regla nueva y nada se recalcula hacia atrás.

## 3. Lenguaje de expresiones

Uno solo para condiciones, conceptos y valores fijados por política (D25). Los efectos no son expresiones: las contienen (sección 5.1, D55).

### 3.1 Referencias

| Símbolo | Significa |
|---|---|
| `entrada/x` | Dato de entrada de la operación |
| `actor` | Persona o agente que ejecuta |
| `empresa` | La empresa |
| `ahora` | El instante de evaluación |
| `origen/x` | Dato del compromiso o hecho de origen |
| `caso/x` | Dato del compromiso raíz |
| `hecho/x` | Dato del hecho que se está evaluando: el candidato en el `:donde` de un "exige", o el que mueve un saldo |
| `nombre/x` | Dato de un resultado nombrado con `:como` en los efectos de un contrato |
| `cada/x` | Dato de cada elemento, dentro de una operación sobre una colección |

En un contrato están todas estas referencias. En un concepto, solo sus parámetros y `cada`: un concepto no conoce la empresa, porque ya se evalúa dentro de una. `impl/x` es el resultado del puerto que nombra `:op/impl` (sección 5).

Un símbolo sin barra (`entrada`, `cada`) es el documento completo. Un dato ausente es un dato que falta (P4); uno presente sin valor, como una entrada opcional no entregada, vale nada y no falta. `nil` es un literal válido en cualquier expresión, y en una condición (`:si`, `:y`, `:cuando`) vale como falso.

Para navegar entre entidades: `[:-> entrada/producto :producto/categoria]`.

### 3.2 Operaciones

| Grupo | Operaciones |
|---|---|
| Aritmética | `:+ :- :* :/ :redondear` |
| Comparación | `:= :!= :> :>= :< :<= :entre` |
| Lógica | `:y :o :no :si :caso` |
| Fechas | `:+dias :+meses :dias-entre :fin-de-mes :habil-siguiente` |
| Colecciones | `:hechos :compromisos :suma :cuenta :max :min :existe :todos` |
| Conceptos | `[:concepto/id {parametros}]` |
| Tablas | `[:tabla nombre clave...]` para "fija" |
| Valores | `[:monto n moneda]` `[:cantidad n unidad]` `[:% n]` `[:dias n]` `[:meses n]` |
| Personas | `[:rol nombre]`: las personas que tienen ese rol |

Las colecciones se obtienen con `[:hechos filtro]` y `[:compromisos filtro]`, donde el filtro es un mapa que incluye `:tipo`. Las demás operaciones reciben una colección: `[:existe col]`, `[:cuenta col]`, `[:todos col condicion]`, `[:suma x col]`, `[:max x col]`, `[:min x col]`. En las cuatro últimas, la condición o `x` es un campo o una expresión que ve cada elemento como `cada`: `[:= cada/estado :activo]`, `[:-> cada :compromiso/saldo :monto]`.

`[:caso c1 v1 c2 v2 ... defecto]` devuelve el valor de la primera condición que se cumple.

Una operación que no está en esta sección no existe: el validador la rechaza.

Un mapa de expresiones es una expresión: se evalúa cada valor, como en el `:objeto` de un compromiso. Un vector de valores, no: el validador lo rechaza. Una lista de líneas se escribe como un mapa con un nombre por línea.

Un cero en un monto lleva la moneda, igual que cualquier monto. Para no fijar la moneda en el contrato, `[:* 0 entrada/monto]` da el cero en la moneda de otro dato.

### 3.3 Restricciones

- **Sin efectos.** Una expresión solo lee.
- **Siempre termina.** No hay recursión ni ciclos; los conceptos forman un grafo sin ciclos.
- **Tipada.** Montos llevan moneda y cantidades unidad. No se suman ni se comparan pesos con dólares ni kilos con cajas sin conversión explícita. Una cantidad por un precio unitario da un monto.
- **Validada al escribir.** Toda referencia debe existir en el catálogo de la empresa (D6) y su tipo debe calzar.

**[P4]** Resuelto: cuando falta un dato (p. ej. el precio), MARCO busca un valor de referencia por política (sugiere o fija); si no hay, SARA lo pide antes de decidir qué pasos aplican; al ejecutar se reevalúa con el dato real.

## 4. Conceptos

Un concepto es una expresión con nombre, versionada (D38).

```clojure
{:concepto/id      :credito/cupo-disponible
 :concepto/version 2
 :concepto/rige-desde #inst "2026-11-01"
 :concepto/tipo    :monto
 :concepto/params  {:cliente :ref}
 :concepto/forma   [:- [:-> cliente :cliente/cupo]
                       [:suma [:si [:= [:-> cada :compromiso/estado] :extinguido]   ; el filtro solo compara por igualdad:
                                   0                                                 ; "no extinguido" se escribe así
                                   [:-> cada :compromiso/saldo :monto]]
                              [:compromisos {:tipo :ventas/por-cobrar :deudor cliente}]]]}

;; concepto algorítmico: cálculo en código, valor guardado como evidencia
{:concepto/id      :inventario/costo-fifo
 :concepto/version 1                          ; la sube el equipo cuando cambia el algoritmo (D48)
 :concepto/tipo    :monto
 :concepto/params  {:producto :producto :cantidad :cantidad}
 :concepto/calculo nc.inventario/costo-fifo
 :concepto/evidencia :guardar}
```

Quien llama a un concepto le pasa todos sus parámetros, ni más ni menos; el que no tiene valor va como `nil`: `[:inventario/costo-unitario {:producto entrada/producto :bodega entrada/bodega :cantidad entrada/cantidad :metodo nil}]`. El catálogo rechaza una llamada con otros parámetros.

Una capacidad publica sus conceptos. El catálogo de cada empresa reúne los del canon, los de sus campos propios y los de sus capacidades contratadas.

## 5. Contrato de operación

```clojure
{:op/id        :compras/emitir-orden
 :op/capacidad :compras
 :op/tipo      :efecto                       ; :consulta | :efecto | :propuesta
 :op/entradas  [:map
                [:proveedor [:ref :proveedor]]
                [:producto [:ref :producto]]
                [:cantidad :cantidad]
                [:precio-unitario :monto]
                [:solicitud {:optional true} [:ref :compras/necesidad]]
                [:oferta {:optional true} [:ref :compras/oferta-vigente]]]
 :op/salidas   {:orden orden/id}             ; nombre → expresión; el tipo se deduce (D10)
 :op/efectos
 [[:registra-hecho :compras/orden entrada :como orden]
  [:cuando entrada/solicitud
   [:extingue-compromiso entrada/solicitud :cumplimiento]]
  [:cuando entrada/oferta
   [:extingue-compromiso entrada/oferta :cumplimiento]]
  [:abre-compromiso {:tipo :compras/recepcion-pendiente
                     :deudor entrada/proveedor :acreedor empresa
                     :objeto {:producto entrada/producto :cantidad entrada/cantidad}}]
  [:abre-compromiso {:tipo :compras/pago-contra-factura
                     :deudor empresa :acreedor entrada/proveedor
                     :estado :latente
                     :origen orden/id
                     :objeto {:monto [:* entrada/cantidad entrada/precio-unitario]}}]]
 :op/inverso   :compras/anular-orden}
```

`:op/impl` nombra un puerto: una palabra clave como `:tributario/envio-dte`, no una función (D96). Un registro de la plataforma dice qué implementación lo atiende y en qué versión, y la versión queda en el hecho. El resultado del puerto se usa como `impl/x`, por ejemplo `:envio impl/seguimiento` en los datos de la factura.

`:op/entidad` declara que la operación crea o modifica una entidad de ese tipo. Con eso, la operación recibe también los campos propios que cada empresa agregó a ese tipo (D3), sin que el contrato los nombre. Los verbos de entidad se escriben así:

```clojure
[:crea-entidad :cliente {:id entrada/id :cliente/nombre entrada/nombre} :como cliente]
[:modifica-entidad entrada/cliente {:cliente/nombre entrada/nombre}]  ; un valor nulo no cambia el campo
[:desactiva-entidad entrada/cliente]
```

Los campos llevan su nombre completo (`:cliente/nombre`). Un verbo de entidad no necesita un hecho.

`:op/salidas` declara lo que la operación devuelve (D10): un mapa de nombre a expresión, cuyo tipo se deduce porque las expresiones son tipadas. Con eso MARCO comprueba que la salida de una operación sirve de entrada a otra, y SARA sabe qué mostrar. En una consulta, las salidas son todo el contrato.

### 5.1 Efectos y verbos

Un efecto es un vector `[verbo argumentos...]`. Los argumentos son expresiones; una expresión nunca contiene un efecto (D55). Los efectos tienen dos formas propias:

- `[:cuando condicion efecto...]` aplica los efectos solo si la condición se cumple. No es el `:si` de las expresiones, que solo elige entre valores.
- `:como nombre`, al final de un efecto, le da nombre a su resultado para usarlo después como `nombre/x` (arriba, `orden/id`).

Los verbos son diez (D30, D56):

| Verbo | Primitivo |
|---|---|
| `:crea-entidad` `:modifica-entidad` `:desactiva-entidad` | Entidad |
| `:registra-hecho` `:revierte-hecho` | Hecho |
| `:abre-compromiso` `:modifica-compromiso` `:reduce-compromiso` `:extingue-compromiso` `:transfiere-compromiso` | Compromiso |

Un verbo de compromiso recibe un compromiso o una colección de ellos (T9 del análisis), y va siempre en la misma operación que un hecho (D61). Sobre una colección vacía no hace nada y no es un error: el despacho reduce `[:compromisos {:tipo :inventario/reservado :raiz caso/id :estado :activo}]`, la reserva del caso si la hay. Una reducción sobre varios compromisos los recorre del que vence antes al que vence después.

Un contrato puede registrar varios hechos, cada uno con su nombre (`:como`). Abre compromisos solo por lo que queda pendiente al terminar: una venta de mostrador pagada al contado registra boleta, entrega y cobro, y no abre ninguno (D62).

`:modifica-compromiso` cambia los términos de un compromiso sin tocar su saldo: lo activa si era latente, le pone vencimiento o reemplaza su calendario. Va siempre en la misma operación que el hecho que lo justifica (D29, D45). Así activa el pago el contrato de "registrar factura":

```clojure
[[:registra-hecho :compras/factura entrada :como factura]
 [:modifica-compromiso [:compromisos {:tipo :compras/pago-contra-factura :origen entrada/orden}]
                       {:estado :activo :vence entrada/vencimiento}]]
```

Los saldos no tienen verbo propio: cada tipo de hecho declara qué saldos mueve (sección 2.2), y el ejecutor los actualiza.

### 5.2 Lo que el contrato no dice

No hay umbrales, aprobaciones ni ofertas exigidas. Eso es política (D4). El contrato solo declara lo que la operación es y lo que cambia.

**[P5]** Resuelto (D65): el contrato valida el tipo de cada entrada, que cada referencia exista en la empresa con el tipo declarado y que cada verbo aplique a su objetivo. Todo lo demás es política.

**[P6]** Resuelto en la etapa 3 (D66): un ejecutor general aplica los efectos declarados, y las siete operaciones corren sin código propio. `:op/impl` queda opcional, solo para integraciones externas (DTE al SII, cartola bancaria) y algoritmos (FIFO, MRP).

## 6. Políticas

### 6.1 Envoltura común

```clojure
{:politica/id         :empresa-b/compra-formal
 :politica/version    3
 :politica/rige-desde #inst "2026-12-01"
 :politica/origen     :empresa                 ; :legal no es editable por la empresa (D5)
 :politica/aplica-a   :compras/emitir-orden    ; operación, conjunto o evento
 :politica/aplica-con :inicio-del-caso         ; una opción del catálogo de vigencia (D50)
 :politica/cuando     [:y [:= [:-> entrada/producto :producto/categoria] :insumos]
                          [:> [:* entrada/cantidad entrada/precio-unitario] [:monto 500000 :CLP]]]
 :politica/forma      :exige
 :politica/cuerpo     {...}
 :politica/prioridad  nil}
```

`:politica/aplica-a` admite una operación, un conjunto de operaciones o un evento. Los eventos no son expresiones: se toman de un catálogo que entrega la plataforma. Los iniciales son `[:vence tipo-de-compromiso]`, `[:registra tipo-de-hecho]` y `[:cada periodo]`.

### 6.2 Las ocho formas

```clojure
;; EXIGE: hechos previos en el mismo caso
{:politica/forma :exige
 :politica/cuerpo [{:hecho :compras/solicitud}
                   {:hecho :compras/oferta :minimo 2}
                   {:hecho :compras/aprobacion
                    :donde [:= hecho/oferta entrada/oferta]}]}   ; se aprobó la oferta que se ordena

;; PROHÍBE, con la razón que se muestra al rechazar
{:politica/id       :empresa-b/segregacion
 :politica/aplica-a :compras/aprobar
 :politica/cuando   [:= actor [:-> caso/origen :hecho/actor]]    ; quien aprueba es quien solicitó
 :politica/forma    :prohibe
 :politica/cuerpo   {:razon "Quien solicita una compra no puede aprobarla"}}

;; LIMITA, con tolerancia (D34)
{:politica/forma :limita
 :politica/cuerpo {:valor [:presupuesto/consumido {:centro entrada/centro}]
                   :maximo [:presupuesto/asignado {:centro entrada/centro}]
                   :tolerancia [:% 5]
                   :al-exceder-tolerancia :exige-aprobacion}}

;; SUGIERE (valor inicial; el usuario puede cambiarlo)
{:politica/forma :sugiere
 :politica/cuerpo {:campo :entrada/bodega :valor [:-> actor :persona/bodega]}}

;; FIJA (el valor es obligatorio)
{:politica/forma :fija
 :politica/cuerpo {:campo :entrada/precio-unitario
                   :valor [:tabla :empresa-b/lista-precios entrada/producto entrada/cliente]}}

;; EXIME (la operación no necesita confirmación humana)
{:politica/forma :exime
 :politica/cuerpo {:hasta [:< [:* entrada/cantidad entrada/precio-unitario] [:monto 100000 :CLP]]}}

;; INICIA (por evento o calendario; MARCO plantea el objetivo)
;; Sin modo: MARCO ejecuta si puede y propone si no (D51)
{:politica/forma :inicia
 :politica/aplica-a [:vence :ventas/cobro]
 :politica/cuerpo {:objetivo :finanzas/castigar-deuda
                   :despues [:dias 180]}}

;; PERMITE (quién tiene permiso, y en qué ámbito)
{:politica/forma :permite
 :politica/aplica-a :compras/aprobar
 :politica/cuerpo {:quien [:rol :jefe-compras]
                   :ambito [:= [:-> entrada/solicitud :solicitud/sucursal]
                               [:-> actor :persona/sucursal]]}}
```

**[P7]** Resuelto: confirmación humana por defecto; "exime" la levanta.

**[P8]** Resuelto: MARCO actúa con identidad propia por empresa y los roles que la empresa le asigna con "permite". Ejecuta solo si además una política lo exime de confirmación; si no, propone (D51, D53).

Vocabulario (D53): **permiso** es quién puede ejecutar una operación; **aprobación** es un hecho que registra otra persona y que una política exige; **confirmación** es el "sí" de quien ejecuta, justo antes de aplicar los efectos; **activación** es el paso de un camino de borrador a activo.

### 6.3 Combinación y conflictos

**[P9]** Resuelto: esta tabla es el valor por defecto de cómo se combinan varias políticas de la misma forma; la empresa puede cambiarlo.

| Forma | Si varias aplican |
|---|---|
| Exige | Se suman los requisitos |
| Prohíbe | Basta una |
| Limita | Rige la más restrictiva |
| Sugiere | Rige la más específica |
| Fija | Debe haber una sola; si hay más, es conflicto |
| Exime | Deben cumplirse todas |
| Inicia | Se plantean todos los objetivos |
| Permite | Basta una |

Al guardar una política, el validador busca choques con las vigentes (D35): dos "fija" sobre el mismo campo, un "exige" con un "prohíbe" incompatibles, un "exime" que deja fuera un "exige". Si los hay, pide al autor que los resuelva con `:politica/prioridad` o ajustando las condiciones. Las legales siempre ganan.

### 6.4 Vigencia en casos abiertos

**[P10]** Resuelto: cada política declara en `:politica/aplica-con` con qué versión se aplica a un caso abierto. El valor es una opción de un catálogo abierto que entrega la plataforma (D50), no un conjunto cerrado. Opciones iniciales:

| Opción | Qué versión rige |
|---|---|
| `:inicio-del-caso` | La vigente cuando se abrió el compromiso raíz, anotada en su `:compromiso/reglas`. Mientras el caso no tiene raíz, la vigente en ese momento |
| `:fecha-del-paso` | La vigente en cada paso (típica de lo tributario) |
| `:cierre-del-periodo` | La vigente al último cierre; un cambio rige desde el cierre siguiente (D44) |

Las legales traen la opción fijada por el equipo. Una política sin `:aplica-con` usa `:fecha-del-paso` (D70). Desactivar una política es retirarla desde una fecha; su historia queda (D75). Cuando hay una versión nueva, lo que ella declara decide si el caso abierto la adopta (D72).

## 7. Objetivos, caminos y resolvedor

### 7.1 Objetivos (D33)

```clojure
;; operación a alcanzar: MARCO resuelve lo que falta y ejecuta tras confirmar
{:objetivo/operacion :compras/emitir-orden
 :objetivo/entradas  {:producto :P-100 :cantidad [:cantidad 20 :un]}}

;; estado deseado: MARCO busca caminos; ejecuta lo que puede y propone lo demás (D51)
{:objetivo/estado [:>= [:inventario/disponible {:producto :P-100 :bodega :central}] [:cantidad 20 :un]]}
```

### 7.2 Camino guardado (D13, D47)

```clojure
{:camino/id      :empresa-b/abastecer
 :camino/version 2
 :camino/objetivo :compras/emitir-orden
 :camino/reglas  {:empresa-b/compra-formal 3}   ; políticas a las que responde (D54)
 :camino/pasos   [:compras/registrar-solicitud
                  :compras/registrar-oferta
                  :compras/aprobar
                  :compras/emitir-orden]
 :camino/estado  :activo}                    ; :borrador | :activo | :retirado
```

Un plan es la lista de pasos para un objetivo: los de un camino activo cuyas políticas aplican, o los deducidos de los requisitos. Avanzarlo ejecuta lo que el usuario puede y propone lo demás; al cumplirse un plan deducido, su camino queda como borrador (D76).

**[P11]** Resuelto (D47, D54): si existe un camino guardado y las políticas de `:camino/reglas` aplican al caso, se usa. Si no aplican (p. ej. una compra bajo el umbral de `compra-formal`), MARCO lo rechaza y deduce los pasos. Los pasos se deducen también cuando no hay camino. Si una política nueva exige un paso que el camino no tiene, la empresa configura qué ocurre.

### 7.3 Respuesta del resolvedor

Para cada requisito, uno de cinco estados: los cuatro que pide el criterio 4 de la visión, más indeterminado.

| Estado | Cuándo |
|---|---|
| `:satisfecho` | El contrato o una política lo exige, y ya se cumple |
| `:pendiente` | El contrato o una política lo exige, y falta. `:via` dice con qué operación se cumple y `:quien`, quién tiene permiso |
| `:opcional` | El contrato lo admite como entrada opcional, y ninguna política lo exige ni lo prohíbe (D58) |
| `:prohibido` | Una política "prohíbe" aplica o el actor no tiene permiso. Lleva `:razon` |
| `:indeterminado` | Falta un dato para evaluar la condición. `:pide` dice cuál |

El `:estado` general es `:listo` si no queda nada pendiente ni indeterminado, `:bloqueado` si queda algo, y `:prohibido` si la operación misma está prohibida.

Además, cada requisito que viene de una política dice cuál (`:politica`) y con qué operación se cumple (`:via`), y uno pendiente dice cuántos faltan (`:faltan`) y qué entrada de la operación objetivo llena el paso (`:entrega`, D71). Una entrada que falta o no calza con el contrato es un requisito pendiente con `:pide` y, si no calza, `:problema` (D65). La respuesta dice también si hace falta confirmar (`:confirmacion :requerida` o `:eximida`) y a qué caso pertenece la operación (`:caso`).

```clojure
{:objetivo  {:operacion :compras/emitir-orden ...}
 :estado    :bloqueado
 :requisitos
 [{:id :compras/solicitud  :politica :empresa-b/compra-formal :estado :pendiente :faltan 1
   :via :compras/registrar-solicitud :quien :cualquiera :entrega :entrada/solicitud}
  {:id :compras/oferta     :politica :empresa-b/compra-formal :estado :pendiente :faltan 2
   :via :compras/registrar-oferta :quien :cualquiera :entrega :entrada/oferta}
  {:id :compras/aprobacion :politica :empresa-b/compra-formal :estado :pendiente :faltan 1
   :via :compras/aprobar :quien [:rol :jefe-compras] :no-puede :persona/ana}]
 :confirmacion :requerida
 :caso      nil
 :reglas    {:empresa-b/compra-formal 3}}
```

En la Empresa A, que no tiene `compra-formal`, la misma consulta devuelve la solicitud y la oferta como `:opcional`: el contrato las admite y nada las exige.

SARA convierte esta respuesta en conversación, formularios y botones. No necesita conocer la operación.

**[P12]** Resuelto en la etapa 6 (D78): la respuesta del resolvedor basta junto con el contrato, que da el tipo de cada campo. SARA mínima genera formularios, avisos, tablas y el paso siguiente sin código propio para ninguna operación.

## 8. Caso completo: Empresa B compra 20 P-100

| # | Qué pasa | Hechos | Compromisos | Resolvedor |
|---|---|---|---|---|
| 1 | Ana pide 20 P-100 | — | — | Busca precio de referencia; si no hay, indeterminado |
| 2 | Ana estima $30.000 c/u | — | — | Supera $500.000: faltan solicitud, 2 ofertas, aprobación |
| 3 | Ana registra la solicitud | Solicitud | Necesidad interna 20 P-100 (raíz; fija compra-formal v3) | Faltan 2 ofertas y aprobación |
| 4 | Llegan dos ofertas | Oferta 1, Oferta 2 | Dos compromisos latentes de proveedores, con vencimiento | Falta aprobación (no puede Ana) |
| 5 | Luis aprueba la oferta 2 | Aprobación | — | Todo satisfecho; pide confirmación |
| 6 | Ana confirma la orden, con la solicitud y la oferta 2 como entradas | Orden | Necesidad: extinguida por cumplimiento. Oferta 2: extinguida por cumplimiento. Oferta 1: sigue latente y queda vencida al pasar su fecha. Recepción pendiente: 20. Pago contra factura: latente | — |
| 7 | Llegan 12 | Recepción | Recepción pendiente: 8. Saldo existencia P-100: +12, valorización v1 | — |
| 8 | Llega la factura | Factura | Pago contra factura: activo, con el vencimiento de la factura | — |
| 9 | La empresa publica compra-formal v4 (sin ofertas) | — | — | Casos nuevos: sin ofertas. Este caso: según `:aplica-con` |

Notas:

- En el paso 4, si Ana intenta aprobar, `:empresa-b/segregacion` lo prohíbe y le muestra la razón (E3).
- En el paso 6, la orden solo pasa si la oferta de entrada es la que Luis aprobó (el `:donde` de `compra-formal`). Que el proveedor y el precio sean los de esa oferta lo asegura una política "fija" sobre esos campos, por ejemplo `{:campo :entrada/proveedor :valor [:-> entrada/oferta :compromiso/deudor]}`.
- La oferta 1 no cambia de estado sola. Extinguirla por vencimiento es una operación que una política "inicia" puede plantear (P2).
