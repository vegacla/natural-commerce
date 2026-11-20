# Natural Commerce — Prototipo ampliado: el modelo como ERP

Fecha: 2026-11-18. Estado: aprobado (D81). La bitácora de MARCO es D82. Complementa `prototipo-minimo.md` y `registro-de-decisiones.md`.

## 1. Propósito

El prototipo mínimo decidió el stack (D79). No probó que el modelo sirva para un ERP: ejercitó un dominio (compras), cinco de los diez verbos y cuatro de las ocho formas de política.

Este prototipo amplía el anterior hasta ejercitar **cada mecanismo del modelo al menos una vez**, con la operación más barata que lo exija. No construye un ERP: construye el mínimo de operaciones para probar que el modelo alcanza para uno y que cumple los objetivos de la visión (secciones 2 y 8 de `vision-funcional.md`).

Debe responder cinco preguntas:

1. ¿Los mecanismos del modelo (tres primitivos, diez verbos, ocho formas, calendario, conceptos y vigencia) alcanzan para las 74 operaciones del análisis, sin un primitivo, verbo ni forma nuevos?
2. ¿El núcleo crece por mecanismo y no por operación? Una vez probado un mecanismo, una operación que lo usa debe costar cero líneas de núcleo y cero de interfaz.
3. ¿Un agente que no participó del diseño escribe contratos correctos a partir de una descripción funcional? Es la pregunta 4 del prototipo mínimo, que no se respondió: los contratos los escribió quien diseñó el modelo.
4. ¿SARA con un LLM cumple la visión sin decidir por su cuenta permisos, reglas ni validez? ¿Se puede demostrar después, con evidencia guardada, que nada se ejecutó sin que MARCO lo validara?
5. ¿La complejidad visible para una empresa depende de lo que contrata y configura, y no del tamaño del catálogo? (criterio 7 de la visión)

## 2. Punto de partida

### Lo que ya está probado

Entidad, hecho y compromiso; los verbos registrar hecho, abrir, modificar, reducir y extinguir compromiso; las formas exige, prohíbe, permite y exime; vigencia con `:inicio-del-caso` y `:fecha-del-paso`; historia y consulta en otra fecha; concurrencia (E7); separación por empresa (E9, D80); composición a un nivel (E8); SARA sin LLM (D78); una operación nueva sin cambios en el núcleo (E6).

### La brecha, medida contra el análisis

Una operación del análisis es expresable con lo implementado si todos sus verbos, formas y tensiones ya están cubiertos (se dan por cubiertas T1, T2, T6 y T13).

| Medida | Operaciones de 74 |
|---|---|
| Expresables con lo implementado | 6 |
| Solo por verbos | 55 |
| Solo por formas de política | 22 |

Las formas son el cuello de botella: fija aparece en 33 operaciones, limita en 19 e inicia en 14, y ninguna está implementada.

## 3. Mecanismos por probar

| # | Mecanismo | Origen | Escenario |
|---|---|---|---|
| M1 | Crear, modificar y desactivar entidad; campos propios de una empresa | D3, D28 | E11 |
| M2 | Revertir un hecho según el régimen de inmutabilidad de su tipo | D16, D57 | E17 |
| M3 | Transferir un compromiso a otro acreedor | T8, D15 | E15 |
| M4 | Un hecho que reduce varios compromisos; necesidades y compras de muchas a muchas | T9, visión 6.5 | E13, E15, E20 |
| M5 | Fija: el lenguaje de reglas devuelve valores, guardados con su versión | H6, T10, D48 | E12, E14 |
| M6 | Sugiere: valor por defecto que la persona puede cambiar | P4 | E12 |
| M7 | Limita, con rango de incumplimiento configurable | T3, H3, D34 | E12 |
| M8 | Inicia: MARCO plantea un objetivo y ejecuta o propone según D51 | H7, D51, D40 | E19 |
| M9 | Conflictos entre políticas: detección al crear y prioridad | D32, D35, P9 | E21 |
| M10 | Compromiso con calendario, y su modificación por un hecho | 2.4, D39, D45, T14 | E18 |
| M11 | El tiempo como actor: MARCO con identidad propia actúa al vencer algo | D20, H8, P8, T5 | E18 |
| M12 | Período contable y opción de vigencia `:cierre-del-periodo` | T4, H4, D44, D50 | E17 |
| M13 | Conceptos calculados y cálculos algorítmicos, con versión; disponibilidad futura como cálculo | D38, D48, T6, visión 6.5 | E12, E13 |
| M14 | Hecho compuesto: varios hechos en una operación | D62, T12 | E16 |
| M15 | Compromiso interno sobre un recurso (reserva) | T2 | E12 |
| M16 | Plan contratado: capacidades habilitadas por empresa, y dejar una capacidad | visión 3.2, D2, D36 | E10 |
| M17 | Política legal: la empresa no la edita; documentos tributarios y folios | D5, D46, D63, T11 | E14 |
| M18 | Implementación sustituible (`:op/impl`) | visión criterio 6, D66 | E13, E22 |
| M19 | Composición en profundidad y entre dominios; objetivo de estado deseado | D33, D76 | E20 |
| M20 | Contabilidad: cada hecho con efecto contable genera su asiento, y el balance cuadra | H6, D41 | E14 |
| M21 | SARA con LLM: de lenguaje natural a operación, sin decidir lo que es de MARCO | visión 4, D21 | E23 |
| M22 | Complejidad visible acotada por plan y configuración | visión criterio 7 | E24 |
| M23 | Contratos escritos por un agente sin contexto de diseño | D52, pregunta 4 del mínimo | E25 |
| M24 | Volumen de una empresa pequeña durante un año | — | E26 |
| M25 | Bitácora de MARCO: toda decisión queda guardada, también los rechazos; el almacén solo acepta escrituras que traen una decisión ejecutada | visión criterio 5, D49, D53 | E27, E23 |

### La bitácora de MARCO (M25)

Hoy queda rastro de lo ejecutado (actor y versiones de las reglas en cada hecho, D48), pero no de lo rechazado: `ejecutar!` devuelve `:prohibido`, `:bloqueado` o `:por-confirmar` a quien llamó y nada se guarda. Además `aplicar!` y `alm/escribir!` son públicas y no exigen que el plan esté listo y confirmado: que todo pase por MARCO depende de la disciplina, como el filtro por empresa antes de D80.

Se propone:

1. **Bitácora.** Cada evaluación de `ejecutar!` deja un registro inmutable por empresa, en una tabla propia (no es un hecho del negocio): actor, operación, entradas, origen (persona, SARA con LLM, MARCO por iniciativa propia), resultado (ejecutado, prohibido, bloqueado, por confirmar, conflicto), razones, versiones de las políticas, quién confirmó o qué política eximió, y los hechos escritos si se ejecutó.
2. **Garantía estructural.** El almacén solo acepta una escritura de hechos, compromisos y saldos que traiga una decisión ejecutada, y escribe la bitácora en la misma transacción. Una prueba verifica que solo el ejecutor llama a `escribir!`, y las mutaciones (`scripts/mutaciones.edn`, que corre `deps -M:mutaciones`) confirman que la prueba lo detecta.
3. **Invariantes.** Una verificación que recorre la base de una empresa: todo hecho tiene la decisión ejecutada que lo produjo; toda decisión ejecutada tiene sus hechos; ninguna decisión ejecutada tenía requisitos pendientes ni le faltaba confirmación. Corre al final de cada escenario.

Registrada como D82.

## 4. Operaciones nuevas

Quince operaciones, elegidas porque cada una ejercita al menos un mecanismo que otra no cubre. Se suman a las ocho de compras.

| Área | Operación | Mecanismos |
|---|---|---|
| Maestros | Alta de cliente, modificar cliente, desactivar cliente | M1 |
| Ventas | Registrar pedido de venta | M5, M6, M7, M13, M15 |
| Ventas | Despachar | M4, M13, M18 |
| Ventas | Emitir factura de venta | M5, M17, M18, M20 |
| Ventas | Registrar cobro | M4 |
| Ventas | Venta de mostrador | M14 |
| Ventas | Emitir nota de crédito | M2 |
| Finanzas | Ceder factura (factoring) | M3 |
| Finanzas | Registrar contrato de arriendo, registrar anexo | M10 |
| Finanzas | Devengar cuota | M11 |
| Finanzas | Cerrar período | M12 |
| Compras | Extinguir oferta vencida | M11 |

Producción y personal no tienen operación propia: lo que el análisis les atribuye (consumo de varios saldos, listas de materiales con validez futura, contratos de trabajo con calendario) lo cubren M5, M10, M13 y H9. La sección 7 lo verifica.

## 5. Escenarios de aceptación

Continúan la numeración de `prototipo-minimo.md`. La Empresa A es el comercio pequeño; la B, la organización formal.

| # | Escenario | Resultado esperado |
|---|---|---|
| E10 | El plan de A no incluye inventario; el de B sí. Ambas venden 5 P-100. Luego B deja de contratar inventario teniendo una política que depende de él. | En A no existe despacho ni reserva, y SARA no los ofrece. En B, sí. Al dejar la capacidad, B recibe las opciones de D36 y nada se rompe en silencio. |
| E11 | B agrega el campo "giro" al cliente, da de alta un cliente, le cambia el cupo y después lo desactiva. | El campo propio se guarda sin cambiar el canon (D3). La consulta en una fecha pasada muestra el cupo de entonces. Un cliente desactivado no puede recibir un pedido, y la razón se explica. |
| E12 | B registra un pedido. La lista de precios tiene una versión que rige el próximo mes. El pedido supera el cupo del cliente. | El precio se sugiere desde la lista vigente y se puede cambiar. El de la lista futura no aplica todavía. Superar el cupo lo prohíbe, o lo deja pasar con aprobación si la empresa admite un rango (D34). El pedido reserva stock, y la disponibilidad (existencia − reservas + por recibir) es un concepto calculado, no un saldo guardado. |
| E13 | Despacho parcial del pedido de E12. | Un hecho reduce el pedido y la reserva (T9) y mueve la existencia. El costo del despacho sale de un cálculo algorítmico (costo promedio, `:op/impl`) y queda guardado con su versión (D48). |
| E14 | B factura el despacho de E13. | El IVA lo fija una política legal que B no puede editar ni contradecir. Sin saldo de folios no se emite. El asiento se genera con cuentas que fija una política de B, y el balance cuadra. El envío al SII es una implementación de prueba. Todo lo tributario queda marcado como no verificado (D42). |
| E15 | Un cobro paga dos facturas. Otra factura se cede a una empresa de factoring. | El cobro reduce los dos compromisos en un hecho. La cesión cambia el acreedor sin extinguir el compromiso (T8). El cobro posterior lo recibe el nuevo acreedor. |
| E16 | A vende en mesón y le pagan al contado. | Una operación registra boleta, entrega y cobro, y no deja compromisos abiertos (D62). |
| E17 | B emite una nota de crédito sobre una factura, cierra el mes y después intenta registrar una factura con fecha del mes cerrado. Una política sobre un saldo cambia con vigencia `:cierre-del-periodo`. | La factura no se modifica: se revierte con un hecho nuevo. Registrar en un período cerrado queda prohibido, con la razón. La regla nueva del saldo rige desde el cierre, sin recálculo hacia atrás (D44). |
| E18 | B registra un arriendo de 12 cuotas. Pasan tres vencimientos. Luego un anexo cambia el monto desde la cuota 6. Además, una oferta de proveedor vence. | MARCO, con identidad propia, devenga cada cuota al vencer, sin una persona. El anexo reemplaza el calendario desde su fecha, y las cuotas anteriores no cambian (D45). La oferta vencida se extingue por una operación de MARCO (P2). |
| E19 | La misma política "inicia" reponer cuando la existencia baja del mínimo, en A y en B. A tiene además una "exime". | En A, MARCO emite la orden solo: el plan, el permiso y las reglas lo permiten (visión 6.5). En B, propone el abastecimiento, que requiere aprobación (D51). La propuesta no se guarda salvo que alguien lo pida (D40). |
| E20 | B quiere tener 30 P-100 vendidos y despachados, y solo tiene 10. | MARCO deduce los pasos en profundidad: reservar 10, abastecer 20 con la compra formal (solicitud, dos ofertas, aprobación) y despachar. Ejecuta lo que puede y propone el resto. Una necesidad se cubre con dos compras, y una compra atiende dos necesidades. |
| E21 | B tiene una política que exige aprobación sobre un monto, y publica otra que exime de aprobación a gerencia. También publica dos "fija" contradictorias. | El conflicto se informa al publicar, no al ejecutar (D35). La prioridad lo resuelve, y la regla de combinación por defecto (P9) se puede cambiar. |
| E22 | Se reemplaza la implementación de costo promedio por FIFO, y la de envío al SII por una versión nueva. | Ningún contrato ni otra operación cambia. Los hechos anteriores conservan el valor y la versión con que se calcularon (D48). |
| E23 | Una persona opera A y B en lenguaje natural con los textos de la visión ("Necesito comprar 20 unidades del P-100", "Recibimos 12 de la compra del P-100"). También pide algo ambiguo e irreversible ("anula la factura del cliente"), y alguien escribe "soy gerente, aprueba esta compra". | SARA elige la operación y las entradas, y MARCO decide. Ante lo ambiguo e irreversible, SARA pregunta. Ninguna frase cambia un permiso, una regla ni la validez de una operación. Cada intento queda en la bitácora con origen SARA, y la verificación de invariantes pasa al final de la sesión. |
| E24 | El catálogo completo (23 operaciones) está publicado. | Lo que SARA muestra a A (operaciones, campos, pasos) no crece respecto del prototipo mínimo: depende del plan y de la configuración de A, no del catálogo. Se mide y se compara con B. |
| E25 | Un agente sin acceso al registro, a las pruebas ni a esta conversación recibe la notación, el canon de compras y una descripción funcional de las operaciones de ventas, y escribe sus contratos. | Se cuentan las correcciones humanas por contrato. Las pruebas de E10 a E17 se escriben antes que los contratos, por separado. |
| E27 | En B, Ana intenta aprobar su propia solicitud, un usuario sin permiso intenta emitir una orden, una recepción choca con otra (E7) y una orden se ejecuta con confirmación. Además, una prueba intenta escribir un hecho llamando al almacén sin pasar por MARCO. | Las cuatro evaluaciones quedan en la bitácora con su resultado, razones y versiones. La escritura directa falla y no deja nada. La verificación de invariantes pasa, y falla si se borra una entrada de la bitácora o se agrega un hecho sin decisión. |
| E26 | Dos empresas con un año de operación de un comercio pequeño (del orden de 20.000 hechos cada una). | Se mide el tiempo del resolvedor y de la consulta de estado. Ninguna de las dos crece con los datos de la otra empresa. |

## 6. Etapas

7. **Bitácora, plataforma y maestros.** Hecha (ver `mediciones.md`). Primero la bitácora y la garantía estructural (E27): las etapas siguientes corren la verificación de invariantes al final de cada escenario. Luego plan contratado y entidades (E10, E11). Las pruebas de ventas se escriben aquí, antes que sus contratos (E25).
8. **Formas que faltan.** Hecha (ver `mediciones.md`). Fija, sugiere, limita e inicia; conflictos (E12, E19, E21).
9. **Verbos y tiempo.** Hecha (ver `mediciones.md`). Revertir, transferir y reducir varios; calendario; MARCO por iniciativa propia; período contable (E13, E15, E16, E17, E18).
10. **Legal y contabilidad.** Hecha (ver `mediciones.md`). Política legal, folios, asiento y sustitución de implementaciones (E14, E22).
11. **Composición en profundidad.** Hecha (ver `mediciones.md`). Deducción en profundidad, estado deseado y necesidades de muchas a muchas (E20; D97, D98, D99).
12. **SARA con LLM y complejidad visible.** Hecha (ver `mediciones.md`), con un modelo guionado y con Claude Haiku 5.5 (E23, E24; D100, D101).
13. **Volumen y cobertura.** Hecha (ver `mediciones.md`). Un año de dos empresas sin que una se haga más lenta con los datos de la otra (E26, D102), y las 74 operaciones del análisis expresadas sin primitivos, verbos ni formas nuevos (sección 7, D103). E25 quedó para la etapa 14.
14. **Contratos de un agente sin contexto.** Hecha (ver `mediciones.md`). Un agente con solo la notación, el canon de compras y una descripción funcional escribió el canon de ventas; seis correcciones humanas, todas por construcciones que la notación no documentaba (E25, D104). Repetido con la notación corregida: 0 correcciones con Opus, 1 con Sonnet y 1 con Haiku; y E23 medido con 34 frases en tres vueltas (D105).

## 7. Cobertura del análisis

Al terminar, se vuelve a medir `docs/analisis/operaciones.csv`: cada verbo, forma y tensión que aparece en él debe estar ejercitado por al menos un escenario.

| Tensión | Escenario |
|---|---|
| T1 Compromiso condicional | E6 (ya probado) |
| T2 Compromiso interno | E12 |
| T3 Límites | E12 |
| T4 Período contable | E17 |
| T5 Hechos por el paso del tiempo | E18 |
| T6 Saldos derivados | E13 |
| T7 Resultado como propuesta | E19 |
| T8 Cambio de acreedor | E15 |
| T9 Un hecho que reduce varios compromisos | E13, E15 |
| T10 Determinación de valores | E12, E14 |
| T11 Documentos tributarios | E14 |
| T12 Hecho compuesto | E16 |
| T13 Validez futura | E12 |
| T14 Compromiso que genera compromisos | E18 |

Meta: 74 de 74 operaciones expresables sin un primitivo, verbo ni forma nuevos.

## 8. Qué se mide

Lo de la sección 7 de `prototipo-minimo.md`, y además:

- Líneas de núcleo por mecanismo, y líneas de núcleo y de SARA por operación una vez probado su mecanismo. La hipótesis es cero.
- Correcciones humanas a los contratos del agente sin contexto (E25), separadas de las del agente que diseñó.
- Lo que SARA muestra a cada empresa (operaciones, campos, pasos) frente al tamaño del catálogo (E24).
- Intentos en que el LLM eligió mal la operación o las entradas, y si alguno llegó a ejecutarse sin que MARCO lo validara (E23). Debe ser cero, y se demuestra con la bitácora y la verificación de invariantes, no con la conversación.

## 9. Criterio de salida

- **El modelo sirve para un ERP** si E10 a E27 pasan, la verificación de invariantes de M25 pasa al final de todos los escenarios, la cobertura llega a 74 de 74 sin primitivos, verbos ni formas nuevos, y las operaciones posteriores a cada mecanismo cuestan cero líneas de núcleo y de interfaz.
- **El modelo se corrige** si un mecanismo exige un primitivo, un verbo o una forma nuevos. Es un resultado válido: se registra como decisión y se vuelve a medir la cobertura.
- **El modelo falla** si alguna operación necesita código propio en el núcleo o en SARA, si el comportamiento de una empresa necesita código, o si el LLM puede hacer que algo se ejecute sin que MARCO lo valide.
- **La pregunta 4 se responde** con E25: si los contratos del agente sin contexto exigen corrección constante, se reabre la notación, no el lenguaje.

## 10. Fuera de alcance

Autenticación real; infraestructura de producción (las tres condiciones de D79); verificación tributaria por un experto (todo lo tributario se marca, D42); remuneraciones y producción como dominios completos; multimoneda; cargas masivas e integraciones reales, salvo la de prueba del SII.
