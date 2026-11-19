# Natural Commerce — Registro de decisiones

Actualizado: 2026-11-18. Origen: sesión de descubrimiento sobre "Visión funcional de SARA y MARCO".
Estado: modelo cerrado; prototipo mínimo hecho (etapas 1 a 6, E1 a E9 y SARA mínima); prototipo ampliado aprobado (D81), etapas 7 a 13.
Documentos relacionados: `prototipo-minimo.md`, `prototipo-ampliado.md`, `analisis-universo-operaciones.md`, `notacion-v0.md`.

## 0. El modelo en una página

1. **Estado.** Un ERP es entidades, hechos y compromisos. Los hechos no se modifican; los compromisos y saldos se guardan; todo lo calculado se guarda con la versión de lo que lo calculó; todo tiene historia y fecha de vigencia.
2. **Operaciones.** Son transformaciones declaradas de ese estado, con diez verbos. Pertenecen a la plataforma.
3. **Políticas.** Todo lo que una empresa decide son datos versionados, en un solo lenguaje de expresiones y en ocho formas. Lo legal es política que la empresa no edita.
4. **MARCO.** Un motor determinista que evalúa operaciones contra políticas y estado: usa caminos guardados, deduce los que faltan y ejecuta solo con confirmación, o sin ella cuando una política lo exime.
5. **SARA.** Traduce entre personas y MARCO. Es el único lugar con LLM y un cliente entre varios.

**Principio de cierre (D46):** toda pregunta de diseño se responde ubicándola en una de tres capas.

| Si la decisión pertenece a... | Es... | Quién la cambia |
|---|---|---|
| El significado de una operación | Contrato | El equipo, con un despliegue |
| La forma de trabajar de una empresa | Política, con un valor por defecto | La empresa, con permisos |
| La ley | Política legal | El equipo, por régimen |

## 1. Decisiones de modelo

| # | Decisión | Motivo o matiz |
|---|---|---|
| D1 | Las operaciones son código escrito por el equipo (mediante agentes de IA). Solo el equipo implementa capacidades; no hay terceros. | El canon de capacidades se deriva de lo que hacen ERP como SAP. Precisado por D52. |
| D2 | Existe un canon común de entidades y operaciones. Cada empresa activa un subconjunto dentro de su plan. | |
| D3 | Una empresa puede agregar campos propios a entidades del canon. No puede agregar capacidades nuevas; eso solo lo hace la plataforma. | |
| D4 | Casi todo es política configurable por empresa, no invariante canónico (ej.: stock mínimo, sobreventa, recibir más de lo ordenado). | "Cualquier decisión es configuración del tenant." |
| D5 | El único piso no configurable es el legal. Depende del régimen tributario de cada empresa y se entrega como paquetes de reglas que la empresa no edita. | Los escribe el equipo. Primero Chile; lo global sigue buenas prácticas (p. ej. Colegio de Contadores). |
| D6 | Las políticas son reglas estructuradas sobre un catálogo de conceptos configurables. No se puede crear una política sobre algo que el ERP no conoce. | SARA ayuda a formularlas ofreciendo los conceptos posibles. |
| D7 | Las condiciones son expresiones ricas: sumas, umbrales, fechas, relaciones entre personas y cálculos de otras capacidades (cupo de crédito, costo promedio). | Descarta planificadores PDDL tal cual y motores de políticas simples. |
| D8 | Las reglas no se acoplan a los datos. Políticas versionadas, con fecha de vigencia. | |
| D9 | Una regla nueva aplica desde su fecha de vigencia. Si un caso abierto la adopta o conserva la anterior lo declara cada política (D46). | Corregido: no es regla general. Precisado por D72. |
| D10 | El contrato de una operación declara datos (entradas y salidas) y efectos de negocio. "Compatibles por contrato" significa ambas cosas. | |
| D11 | Los contratos de operación viven junto al código (cambian con un despliegue). Políticas y caminos compuestos son datos (cambian en operación). | |
| D12 | MARCO es determinista, no un LLM: planificación basada en acciones, precondiciones y efectos, inspirada en STRIPS/PDDL. "Lo más determinista que se pueda sin dejar de ser inteligente." | El LLM vive solo en SARA. |
| D13 | MARCO trabaja con caminos definidos, guardados y versionados; recorre todos los caminos definidos y propone. | Modelo tipo "métodos + resolvedor" (cercano a HTN). |
| D14 | Ante un objetivo sin camino guardado pero con piezas contratadas y compatibles: MARCO arma el plan, el usuario confirma, se ejecuta, y MARCO propone guardarlo como camino reutilizable sujeto a activación. Si una política lo exige, la combinación requiere aprobación previa. | Tener permiso sobre cada pieza no basta: hace falta confirmación, salvo que una política exima (D53). Si faltan capacidades contratadas, no hay nada que armar. |
| D15 | No existe "el proceso" con principio y fin canónicos. Hay compromisos abiertos que las operaciones crean y extinguen; cuáles los extinguen depende de las capacidades contratadas (ej.: factorizar, pasar a incobrables). | Cómo se reconoce el caso: D68. |
| D16 | La inmutabilidad es propiedad del tipo de dato según la obligación que lo rige (contabilidad: solo agregar y revertir). Lo demás puede ser flexible. | |
| D17 | Cualquier dato debe poder verse como estaba en cualquier fecha, junto con la regla vigente entonces. | Considerado esencial en un ERP. Cómo lo hace MARCO: D73. |
| D18 | Corregir el pasado (distinguir cuándo ocurrió de cuándo se registró) no es prioritario; lo normal es aceptar el error y registrar una operación correctiva. | Sí hace falta tiempo de validez para programar el futuro (H9). |
| D19 | La atomicidad entre verificar una condición y ejecutar el efecto se decide por configuración de cada empresa (ej.: permitir o no sobreventa). | Corregido por D49. |
| D20 | MARCO puede actuar por iniciativa propia (vencimientos, eventos), de forma configurable. | |
| D21 | SARA es un cliente entre varios. No todo pasa por conversación; habrá interfaces más cómodas según el uso (caja, cargas masivas, integraciones). | La interfaz conversacional usa componentes de presentación ya hechos (menús, tablas, etc.). |
| D22 | Una sola base de datos compartida, con separación por empresa (tenancy). | Punto de partida. |
| D23 | Se aceptan ventanas de despliegue fuera de horario. Reemplazo en caliente es deseable, no obligatorio. | Permite un solo programa modular. |
| D24 | Alcance inicial: una empresa que compra y vende. El diseño del core no debe cambiar al crecer. | Core más capacidades que se añaden. |
| D25 | Se prefiere un núcleo uniforme (contrato, reglas y efectos en una sola notación) por sobre recibir hecho lo periférico. | Resultado de la prueba en papel. |
| D26 | Clojure es el lenguaje candidato principal. | El contrato como dato se lee y mantiene mejor. |
| D27 | El prototipo se construye en Clojure con XTDB v2. Respaldo: Clojure con Postgres. | Ver `prototipo-minimo.md`. |
| D28 | Primitivos de estado: entidad, hecho y compromiso. Un documento de proveedor es un hecho. | El análisis de 74 operaciones no encontró la necesidad de un cuarto primitivo (H1). |
| D29 | Los compromisos se guardan, no se deducen. El mismo efecto que registra un hecho reduce o extingue el compromiso, en la misma transacción. | Práctica estándar de ERP y contabilidad. Generalizado por D61. |
| D30 | Los efectos se declaran con un vocabulario cerrado de nueve verbos. | H5 del análisis. Ampliado a diez por D56. |
| D31 | Las políticas tienen ocho formas: exige, prohíbe, limita, sugiere, fija, exime, inicia y permite. | H6 agregó "fija". Nombres según D53. |
| D32 | La resolución de conflictos entre políticas depende del contexto; no hay una regla general. | Precisado por D35. |
| D33 | Hay dos formas de objetivo: operación a alcanzar (MARCO resuelve lo que falta y ejecuta) y estado deseado (MARCO busca caminos alternativos, pero solo propone). | Precisado por D51. |
| D34 | Un presupuesto obliga a no sobrepasar el monto asignado, o a aceptar rangos configurables de incumplimiento. El pronóstico queda fuera del ERP clásico. | Entidad + concepto de consumo + política de límite. |
| D35 | Los conflictos entre políticas se analizan al crear la regla, y el sistema ofrece herramientas para resolverlos, como asignar prioridad. | |
| D36 | Si una empresa deja de contratar una capacidad de la que dependen sus políticas, se le presentan las opciones y ella decide. | |
| D37 | Las reglas no están en el código: el código ejecuta reglas sin conocer lo que definen. Un cambio de código no puede invalidar una regla. | |
| D38 | Los conceptos se definen como expresiones en la misma notación, versionados como las políticas. Solo los cálculos algorítmicos quedan en código, y cada decisión guarda el valor que usó de ellos. | |
| D39 | Contratos marco, contratos de trabajo y arriendos son compromisos con calendario, no un primitivo aparte. | |
| D40 | Las propuestas de MARCO se guardan solo si el usuario quiere. Un plan de pagos con fechas y cuotas es un compromiso con calendario y se guarda. | |
| D41 | Los saldos se guardan, siempre vinculados a la versión de la política o regla con que se calcularon. | Generalizado por D48. |
| D42 | Sin experto contable o SAP disponible por ahora. Lo tributario queda por verificar, y el paquete legal se construirá desde fuentes oficiales del SII. | |
| D43 | El MRP es una capacidad que el cliente adquiere, no una función de MARCO. | Produce propuestas (D40). |
| D44 | Una regla que afecta un saldo solo cambia al cierre del período, que legalmente sería anual. Desde ahí el saldo continúa con la regla nueva, sin recálculo hacia atrás. | Por verificar con fuentes del SII. Es una opción del catálogo de D50. |
| D45 | Un contrato no se modifica durante su vigencia, salvo por vías legales: bilateral, unilateral u otras. Cada modificación es un hecho nuevo que reemplaza el calendario desde su fecha. | |
| D46 | Principio de cierre: lo que pertenece al significado de una operación es contrato; lo que decide una empresa es política con valor por defecto; lo que impone la ley es política legal. | Ver sección 0. |
| D47 | Si existe un camino guardado, se usa. Los pasos se deducen solo cuando no hay camino. | Precisado por D54. |
| D48 | Todo lo calculado que se guarda lleva la versión de lo que lo calculó (concepto, política, regla o cálculo algorítmico). Saldos, valores fijados por una política y evidencia de un hecho son casos de esta regla. | Generaliza D38 (evidencia) y D41 (saldos). |
| D49 | Verificar una condición y aplicar el efecto ocurren siempre en la misma transacción. Lo que la empresa configura es la regla (ej.: prohibir o permitir sobreventa), no la atomicidad. | Corrige D19. Cómo se logra: D74. |
| D50 | `:aplica-con` no tiene un conjunto cerrado de valores: cada política elige una opción de un catálogo de opciones de vigencia que entrega la plataforma (inicio del caso, fecha del paso, cierre del período, y las que se agreguen). | Generaliza P10. D44 pasa a ser una opción del catálogo, no una regla aparte. |
| D51 | Ante un objetivo, MARCO ejecuta lo que puede ejecutar y propone lo que no. Ej.: "comprar 10" cuando se requiere una orden de compra con aprobación: MARCO propone crear la orden, no compra. | Precisa D33: el comportamiento no depende de la forma del objetivo. |
| D52 | Una operación es su contrato. | Precisa D1: lo que el equipo escribe para agregar una operación es el contrato. |
| D53 | Vocabulario preciso. **Permiso**: quién puede ejecutar una operación. **Aprobación**: un hecho que registra otra persona y que una política exige. **Confirmación**: el "sí" de quien ejecuta, justo antes de aplicar los efectos. **Activación**: el paso de un camino de borrador a activo. Las formas de política se renombran: autoriza → **permite**, automatiza → **exime** (de confirmación), dispara → **inicia**, determina → **fija**, valor por defecto → **sugiere**. "Inicia" no lleva modo: si MARCO ejecuta o propone lo decide D51. | La confirmación es el valor por defecto y solo "exime" la levanta; precisa D14, D31 y P7. |
| D54 | Un camino guardado lleva las políticas, con su versión, a las que responde. MARCO lo usa solo si esas políticas aplican al caso y se cumplen; si no, lo rechaza y deduce los pasos. | Precisa D47: "si existe un camino aplicable, se usa". Es D48 aplicado al camino, que también es algo calculado. |
| D55 | Expresiones y efectos son dos niveles. Una expresión solo lee. Un efecto es un verbo con expresiones como argumentos; una expresión nunca contiene un efecto. Los efectos tienen su propio condicional (`:cuando`). | Precisa D25: la notación sigue siendo una sola, pero una condición no puede modificar el estado. |
| D56 | Los verbos son diez: se agrega `:modifica-compromiso`, que cambia los términos de un compromiso sin tocar su saldo (activarlo, ponerle vencimiento, reemplazar su calendario). Va siempre junto al hecho que lo justifica. Ningún compromiso cambia de estado sin una operación. | Amplía D30. Sin él no se podía declarar la activación de un compromiso latente ni el anexo de D45. |
| D57 | Cada tipo de hecho se declara: sus datos, su régimen de inmutabilidad y los saldos que mueve. | Aplica D16 (el régimen es del tipo de dato, no de la operación) y completa D41. |
| D58 | Un requisito es opcional cuando el contrato lo admite como entrada opcional y ninguna política lo exige ni lo prohíbe. No hace falta una forma de política para decirlo. | Da origen al estado "opcional" del criterio 4 de la visión. Es D46: lo que la operación admite es contrato; lo que la empresa exige es política. Precisado por D71. |
| D59 | Vocabulario: **oferta** es lo que un proveedor entrega y lo obliga si se acepta (un compromiso latente). **Cotización** queda para lo que la empresa solicita a proveedores o emite a un cliente. | Amplía D53. La visión llama "cotización" a ambas cosas; el análisis ya las distinguía. |
| D60 | El prototipo prueba la separación por empresa con un escenario propio (E9). La madurez operativa del almacén se evalúa con una revisión aparte, no con un escenario. E8 prueba el modelo y no decide el stack. | Hace verificable el criterio de salida de `prototipo-minimo.md`. |
| D61 | Ningún compromiso se abre, modifica, reduce, extingue ni transfiere sin un hecho registrado en la misma operación. | Generaliza D29 y D56. Reservar stock y comprometer presupuesto también registran un hecho. |
| D62 | Una operación puede registrar varios hechos en la misma transacción (ej.: venta de mostrador: boleta, entrega y cobro). Abre compromisos solo por lo que queda pendiente al terminar. | Resuelve T12 sin un primitivo nuevo. |
| D63 | Los folios de documentos tributarios son un saldo: la autorización del SII lo aumenta y cada documento emitido lo consume. Una política legal "fija" el número de folio y otra impide emitir sin saldo. | Mismo patrón que presupuesto y cupo (H3). Por verificar con fuentes del SII (D42). |
| D64 | El prototipo incluye "registrar factura de proveedor" como octava operación: un hecho simple que activa el pago pendiente de la orden. Lo tributario de la factura sigue fuera de alcance. | Sin ella el pago queda latente y E6 no tiene un compromiso activo que reducir. |
| D65 | El contrato decide si una operación tiene sentido: el tipo de cada entrada, que cada referencia exista en la empresa con el tipo declarado, y que cada verbo aplique a su objetivo (un compromiso extinguido no cambia; `:modifica-compromiso` no toca el saldo). Todo lo demás es política. Una entrada que falta o no calza aparece en el resolvedor como requisito pendiente con `:pide`, sin un estado nuevo. | Resuelve P5. Es D46: el significado de la operación es contrato. |
| D66 | Un ejecutor general aplica los efectos declarados. Las siete operaciones de la etapa 3 corren sin código propio. `:op/impl` queda para integraciones externas y algoritmos. | Resuelve P6 en el prototipo. E6 lo vuelve a probar con una operación nueva. |
| D67 | Si ninguna política "permite" aplica a una operación, cualquier persona de la empresa tiene permiso. La empresa restringe el permiso publicando una "permite". | Valor por defecto según D46. Así la Empresa A opera sin políticas (E1). Negar por defecto obligaría a toda empresa a configurar permisos antes de operar. |
| D68 | El caso de una operación se deduce de sus entradas: es la raíz del primer compromiso o hecho al que apuntan, en el orden en que el contrato las declara. Si ninguna apunta a un caso, el primer compromiso que la operación abre es la raíz. Cada hecho guarda su caso en `:hecho/caso`. | Completa D15: sin instancias de proceso, el caso se reconoce por los datos. |
| D69 | El saldo de un compromiso son, por defecto, los montos y cantidades de su objeto; el contrato puede declararlo con `:saldo`. Si `:reduce-compromiso` lo deja en cero o menos, el compromiso queda extinguido por cumplimiento en la misma operación. Si puede quedar bajo cero (recibir más de lo ordenado) lo decide una política (D4, E7). | Es el significado del verbo, por lo tanto contrato (D46). |
| D70 | Una política sin `:aplica-con` se aplica con la versión vigente en cada paso (`:fecha-del-paso`). | Valor por defecto del catálogo de D50. |
| D71 | Un requisito de una política "exige" dice con qué operación se cumple (`:via`) y, si esa operación tiene una salida que la operación objetivo recibe como entrada, cuál es esa entrada (`:entrega`). Una entrada así deja de ser opcional. | Aplica D10: la salida de una operación es entrada de otra. Es lo que SARA necesita para encadenar pasos (P12). |
| D72 | Cuando una política tiene una versión nueva, la versión vigente en el paso declara si un caso abierto la adopta (`:fecha-del-paso`) o conserva la anotada al abrir el caso (`:inicio-del-caso`). | Precisa D9: decide la versión nueva, porque es la que sabe si su cambio debe alcanzar a los casos abiertos. |
| D73 | MARCO puede mirar una empresa en cualquier fecha. En el pasado ve lo que regía entonces, según lo que se sabía entonces; en el futuro, lo que regirá, según lo que se sabe hoy. Mirando en otra fecha consulta y resuelve, pero no ejecuta. | Aplica D17 a MARCO entero, no solo a la lectura de datos. No ejecutar en otra fecha es D18. |
| D74 | Cada empresa tiene una marca que crece con cada escritura. MARCO lee la marca antes de verificar; la transacción exige que siga igual y la avanza. Si otra escritura se adelantó, nada se escribe y MARCO vuelve a verificar desde el estado nuevo. | Cumple D49 con concurrencia optimista: verificar sobre lo leído equivale a verificar dentro de la transacción. Dos escrituras de una misma empresa nunca se aplican en paralelo; las de empresas distintas no chocan. |
| D75 | Desactivar una política, un concepto o un camino es retirarlo desde una fecha. Deja de regir, pero su historia queda y se puede ver como regía antes. | Aplica D8 y D17: desactivar no borra. |
| D76 | Avanzar un plan es la confirmación del usuario (D14): MARCO ejecuta los pasos que ese usuario puede ejecutar y propone los demás, con lo que falta y quién puede hacerlos. Un paso que otra persona cumplió por fuera del plan cuenta como hecho. Al cumplirse el objetivo de un plan deducido, el camino queda como borrador, con las políticas que produjeron sus pasos y sus versiones (D54). | Precisa D51 para varios pasos. Los pasos se deducen a un solo nivel: de los requisitos del objetivo, no de los requisitos de esos pasos. |
| D77 | Una referencia a un compromiso declara su tipo (`[:ref :compras/necesidad]`), como ya lo hacía una referencia a un hecho. Los tipos de compromiso son los que abren los contratos. `[:ref :compromiso]` queda para cuando sirve cualquiera. | Sin el tipo, el contrato aceptaba una oferta donde va una solicitud (D65) y SARA no podía ofrecer opciones. Se encontró al construir la etapa 6. |
| D78 | SARA genera la interfaz de una operación con dos cosas: el contrato (entradas y salidas declaradas) y la respuesta del resolvedor. No tiene código propio para ninguna operación. Los textos son plantillas sobre el vocabulario del resolvedor (falta, se registra con, puede hacerlo, cumplido). | Resuelve P12: la respuesta del resolvedor sola no basta, porque no trae el tipo de cada campo; junto con el contrato, sí. |
| D79 | Se sigue con Clojure y XTDB. Antes de guardar datos de clientes hacen falta cuatro cosas: un log durable y replicado, un almacén de objetos con versionado y un respaldo ensayado, una suscripción de soporte, y evaluar las bases múltiples de XTDB para separar empresas. | Aplica el criterio de salida del prototipo: E1 a E7 y E9 pasan con cuatro rodeos menores, y la revisión de madurez (`revision-xtdb.md`) no encontró impedimentos. Respaldar y restaurar se probó con los datos de ejemplo. |
| D80 | La cuarta condición de D79 (bases múltiples de XTDB) se descarta: se mantiene una sola base con separación por empresa (D22). En su lugar, la separación se protege con resguardos en código y reglas: filtro por empresa en un solo lugar, pruebas que fallan si se quita, y una regla que impide consultas sin empresa. | Una evaluación con XTDB 2.2 mostró que cada empresa en su base aísla las consultas sin calificar, pero exige migrar el almacén de la API en proceso a JDBC, no es frontera de seguridad (un nombre calificado cruza bases) y su costo por empresa no está medido. Basta filtrar bien. |
| D81 | Se amplía el prototipo para probar que el modelo sirve para un ERP y cumple la visión: cada mecanismo del modelo se ejercita al menos una vez, con quince operaciones nuevas y los escenarios E10 a E27 (`prototipo-ampliado.md`). | El prototipo mínimo decidió el stack, pero con lo implementado solo 6 de las 74 operaciones del análisis son expresables. |
| D82 | MARCO guarda una bitácora inmutable por empresa con cada evaluación de una operación, también las rechazadas: actor, operación, entradas, origen, resultado, razones, versiones de las políticas y confirmación. El almacén solo acepta escribir hechos, compromisos y saldos junto con una decisión ejecutada, y la escribe en la misma transacción. Una verificación de invariantes comprueba que todo hecho tiene la decisión que lo produjo. | Sin ella no se puede demostrar que nada se ejecutó sin que MARCO lo validara (visión, criterio 5), y menos con un LLM en SARA (E23). Hoy los rechazos no dejan rastro y escribir sin MARCO depende de la disciplina, como el filtro por empresa antes de D80. No es un hecho del negocio: va en una tabla propia. |
| D83 | El plan contratado es un dato versionado por empresa, con fecha de vigencia, que la plataforma publica: la lista de capacidades que la empresa puede usar. Una operación fuera del plan está prohibida y SARA no la ofrece. Un requisito que solo se cumple con una operación fuera del plan también está prohibido, con la razón. Sin plan publicado, la empresa tiene todo el catálogo. Dejar una capacidad de la que dependen políticas no publica nada hasta que la empresa elija: retirar esas políticas desde la misma fecha, o conservar las capacidades. | Aplica la visión (3.2) y D36. El plan no es política: no lo cambia la empresa. El valor por defecto mantiene a las empresas del prototipo mínimo sin plan. Que nada quede bloqueado en silencio es la forma de D36 en el resolvedor. |
| D84 | Una operación que crea o cambia una entidad declara su tipo (`:op/entidad`) y recibe, como entradas opcionales, los campos que la empresa agregó a ese tipo (D3). Crear, modificar y desactivar una entidad no exige un hecho. Desactivar no borra: una referencia a una entidad desactivada no calza con el contrato y se explica. La carga inicial de datos maestros es lo único que se escribe sin decisión de MARCO (D82). | Sin `:op/entidad`, cada empresa con campos propios necesitaría su propio contrato. D61 exige un hecho solo para los compromisos; una entidad lleva su historia en el almacén y su decisión en la bitácora. |

## 2. Criterios para elegir stack

- Sin restricciones previas de lenguaje ni infraestructura.
- Menor escritura, mejor mantenimiento, idoneidad para el problema.
- Todo el código lo escriben agentes de IA; la idoneidad pesa más que la popularidad del lenguaje.

## 3. Candidatos de stack evaluados

| Pieza | Elixir + Ash + Postgres | Clojure + XTDB | TypeScript + Postgres + motor duradero |
|---|---|---|---|
| Contrato de operación | Declarativo de fábrica; los efectos requieren extensión propia | Mapa de datos interpretado por código propio | Esquemas más generación propia |
| Reglas por empresa | Capa propia | Capa propia; reglas como datos son nativas | Capa propia |
| Historia de cualquier dato | Diseño propio | Viene resuelto | Diseño propio |
| Esperas e iniciativa propia | Nativo más Oban | Hay que sumar un motor | Temporal, Restate o DBOS |
| SARA | Acciones como herramientas; LiveView | Todo propio | Ecosistema más rico |

## 4. Preguntas del borrador de notación, resueltas

| # | Resolución |
|---|---|
| P1 | Resuelta al construir la etapa 1: operaciones como palabras clave y referencias como símbolos. |
| P5 | D65: el contrato valida tipos, referencias y que cada verbo aplique a su objetivo; lo demás es política. |
| P6 | D66: un ejecutor general basta para las siete operaciones de la etapa 3. |
| P2 | Bastan tres estados. "Vencido" se calcula; extinguir por vencimiento es una operación (D56). |
| P3 | Las vías de modificación de un contrato son política legal. |
| P4 | Precio de referencia por política (sugiere o fija); si no hay, SARA lo pide; se reevalúa al ejecutar. |
| P7 | Confirmación humana por defecto; la política "exime" la levanta. |
| P8 | MARCO actúa con identidad propia y los roles que la empresa le asigna (política "permite"). |
| P9 | Tabla de combinación por forma como valor por defecto, modificable por la empresa. |
| P10 | Cada política declara con qué versión se aplica a un caso abierto, eligiendo una opción de un catálogo abierto (D50). |
| P11 | D47. Si una política nueva exige un paso que el camino no tiene, la empresa configura qué ocurre. |
| P12 | D78: basta junto con el contrato. El texto de la pregunta se reconstruyó el 2026-11-17. |

## 5. Pendiente

- Prototipo ampliado (D81): etapas 7 a 13.
- Tres condiciones de D79 (log durable y replicado, almacén de objetos con respaldo ensayado, soporte), antes de datos de clientes. La cuarta se resolvió con D80.
- Paquete legal chileno inicial, desde fuentes del SII.
