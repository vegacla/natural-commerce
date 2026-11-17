# Natural Commerce — Definición del prototipo mínimo

Fecha: 2026-11-13. Complementa `registro-de-decisiones.md`.

## 1. Decisiones de origen

Este documento dio origen a D25 (núcleo uniforme), D26 (Clojure) y D27 (XTDB v2, con Postgres como respaldo). Su texto vigente está en `registro-de-decisiones.md`.

## 2. Propósito

El prototipo no es una demostración: es un experimento para decidir el stack y validar el modelo. Debe responder cuatro preguntas:

1. ¿Una sola notación alcanza para contratos, efectos y políticas?
2. ¿Cuánto código propio exige el núcleo, y cuánto cada operación nueva?
3. ¿XTDB resuelve la historia universal (D17) y la verificación atómica (D19) sin diseño adicional?
4. ¿Los agentes de IA escriben el contrato correcto de una operación a partir de su descripción funcional, con poca corrección humana? (D52)

## 3. Alcance

### Núcleo (lo que se está probando)

- **Catálogo**: registro de contratos de operación como datos, validados con un esquema.
- **Evaluador de expresiones**: la notación común de condiciones, efectos y políticas, incluyendo sumas, umbrales, fechas y comparación entre personas.
- **Hechos y compromisos**: los efectos crean hechos y abren o extinguen compromisos (D15). No hay instancias de proceso.
- **Políticas**: datos por empresa, versionados y con fecha de vigencia (D8, D9).
- **Resolvedor**: dado un objetivo, el estado y las políticas vigentes, devuelve qué está satisfecho, qué falta y quién puede hacerlo.
- **Ejecutor**: verifica condiciones y aplica efectos en una misma transacción.

### Operaciones (ocho)

Registrar solicitud de compra, registrar oferta de proveedor, aprobar compra, emitir orden de compra, registrar recepción, registrar factura de proveedor, consultar estado de la adquisición y registrar pago.

Las siete primeras se construyen en la etapa 3. Registrar pago se agrega en la etapa 5, solo con su contrato: es la prueba de E6.

Registrar factura es un hecho simple que activa el pago pendiente de la orden (D56, D64). Lo tributario de la factura sigue fuera de alcance.

"Oferta" es lo que la visión llama cotización de un proveedor (D59).

### Fuera de alcance

Obligaciones tributarias, inventario, ventas, autenticación real, interfaz gráfica y SARA con LLM. Las dos empresas se distinguen por un atributo. Se prueba que el núcleo no cruza datos entre empresas (E9); no se prueba el aislamiento de infraestructura (permisos de la base de datos, cifrado).

Una consecuencia de D15 que el prototipo debe confirmar: como no hay procesos sino compromisos abiertos, una espera humana es simplemente un compromiso sin extinguir. MARCO reevalúa cuando llega un hecho nuevo. Si eso se sostiene, no hace falta un motor de workflows; basta un planificador simple para vencimientos.

## 4. Escenarios de aceptación

| # | Escenario | Resultado esperado |
|---|---|---|
| E1 | Empresa A pide 20 P-100 y luego recibe 12. | Orden emitida sin pasos previos; queda un compromiso de recepción por 8. |
| E2 | Empresa B pide lo mismo sobre el umbral. | El resolvedor informa pendientes: solicitud, dos ofertas, aprobación. Al registrar cada hecho se reevalúa; con todo satisfecho se emite. |
| E3 | En B, quien solicita intenta aprobar. | Rechazado, con la razón explicada. |
| E4 | B publica la versión siguiente de su política (sin ofertas) con fecha de vigencia. | Un caso nuevo no pide ofertas; el caso abierto se comporta según lo que declare la política (D9 corregido). |
| E5 | Se consulta una compra en una fecha pasada. | Se ve su estado de entonces y la regla vigente en ese momento. |
| E6 | Se agrega "registrar pago" solo con su contrato. | El resolvedor y la interfaz la usan sin tocar el núcleo. Un pago reduce el compromiso que la factura activó. |
| E7 | Dos recepciones simultáneas superarían lo ordenado, con la política que lo prohíbe activa. | Una falla. Con la política desactivada, ambas pasan. |
| E8 | Objetivo "abastecer 20 P-100" sin camino guardado. | El resolvedor arma un plan; MARCO ejecuta los pasos que puede y propone los que no (D51). Al completarse, deja el camino como borrador por activar (D14). |
| E9 | La Empresa A consulta y opera mientras la B tiene compras abiertas del mismo producto. | Ninguna consulta, resolución ni efecto de A ve o toca datos de B, y viceversa. |

## 5. Etapas

1. **Notación y evaluador.** Esquema del contrato, lenguaje de expresiones, esquema de política (ver `notacion-v0.md`).
2. **Hechos y compromisos sobre XTDB.** Escritura, lectura y consulta en fecha pasada.
3. **Resolvedor y ejecutor.** Escenarios E1 a E3.
4. **Vigencia e historia.** Escenarios E4 y E5.
5. **Extensión, concurrencia, composición y separación.** Escenarios E6 a E9.
6. **SARA mínima.** Generar un formulario y una tabla a partir del contrato, sin LLM. Opcional: exponer las operaciones como herramientas a un modelo.

## 6. Riesgos que el prototipo debe despejar

- **XTDB sin esquema fijo.** La validación recae en el núcleo; hay que ver si eso es aceptable para datos contables.
- **Concurrencia en XTDB.** Verificar que sus transacciones permitan la condición atómica de E7.
- **Separación por empresa en una sola base (D22).** Confirmar cómo se garantiza en XTDB (E9).
- **Clojure escrito por agentes.** Lenguaje dinámico: las barandas son esquemas, pruebas generativas y análisis estático. Medir cuántas correcciones humanas hacen falta.
- **Madurez operativa de XTDB.** Respaldo, costos y soporte, antes de comprometer datos de clientes. No se prueba con un escenario: se evalúa con una revisión aparte, que incluye respaldar y restaurar la base del prototipo.

## 7. Qué se mide

- Líneas de código del núcleo frente a líneas de contrato por operación.
- Cuántas veces hubo que rodear una limitación del almacén.
- Correcciones humanas por contrato escrito por agentes.
- Si E6 se cumple literalmente: cero cambios en el núcleo.

## 8. Criterio de salida

- **Seguir con Clojure y XTDB** si E1 a E7 y E9 pasan sin rodeos importantes y la revisión de madurez operativa no encuentra impedimentos.
- **Cambiar a Clojure y Postgres** si falla la concurrencia (E7), la separación por empresa (E9) o la revisión de madurez operativa.
- **Reabrir la elección de lenguaje** solo si la notación uniforme no alcanza o si lo que escriben los agentes exige corrección constante.
- **E8 no decide el stack.** Prueba el modelo (D14, D51), no el almacén. Si falla, se revisa el modelo.
