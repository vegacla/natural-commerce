# Natural Commerce — Visión funcional de SARA y MARCO

**Estado:** Esbozo de visión funcional. No constituye una especificación cerrada ni una definición tecnológica.

## 1. Propósito

Natural Commerce busca ofrecer una plataforma universal para administrar operaciones comerciales y empresariales. Su horizonte funcional es abarcar progresivamente el conjunto de capacidades de un ERP integral, tomando como referencia la amplitud funcional de soluciones como SAP, sin reproducir necesariamente sus procedimientos, interfaces ni carga administrativa.

La plataforma debe servir tanto a un pequeño comercio que necesita registrar unas pocas operaciones como a una organización que requiere controles, responsabilidades, trazabilidad y procedimientos formales. **No debe imponer un modelo operativo único.**

La aspiración no es implementar toda esa funcionalidad en la primera versión, sino construir un modelo que permita incorporarla progresivamente sin rediseñar la experiencia del usuario ni crear una solución distinta para cada empresa.

## 2. Resultado que se busca

Una empresa podrá:

- Acceder a las capacidades y operaciones incluidas en su plan contratado.
- Elegir cuáles de esas operaciones utiliza y configurar cómo trabaja.
- Aplicar distintos niveles de formalidad según la operación y sus circunstancias, sin depender de categorías rígidas de empresa.
- Ejecutar operaciones de forma aislada o combinarlas en procedimientos, incluso si esa combinación no fue programada específicamente para ese cliente.
- Exigir permisos, validaciones, aprobaciones, documentos, registros y trazabilidad solo cuando correspondan a sus reglas, obligaciones y necesidades.
- Modificar su modelo operativo sin reconstruir la aplicación, preservando el tratamiento coherente de operaciones en curso.
- Incorporar nuevas capacidades y sustituir implementaciones manteniendo, cuando corresponda, sus contratos funcionales.
- Interactuar con el sistema en lenguaje natural y mediante controles visuales generados dinámicamente.

**Principio rector:** la amplitud funcional pertenece a la plataforma; la complejidad cotidiana que experimenta cada empresa depende de lo que necesita y configura, no de todo lo que el sistema es capaz de hacer.

## 3. Esbozo del modelo funcional

### 3.1. Universo de operaciones

Natural Commerce mantiene un catálogo extensible de capacidades empresariales y operaciones asociadas. La referencia a un ERP como SAP describe la **ambición de cobertura funcional**, no una exigencia de copiar sus módulos o procesos.

Una capacidad agrupa un ámbito de comportamiento empresarial (por ejemplo, compras, ventas, inventario, pagos o producción). Una operación representa una acción o consulta con significado de negocio (por ejemplo, emitir una orden de compra, consultar disponibilidad o registrar una recepción).

Las operaciones tienen entradas, resultados, condiciones de aplicación y efectos identificables. Algunas consultan o calculan; otras generan cambios persistentes o compromisos empresariales. No todas requieren un procedimiento previo ni generan el mismo tipo de efecto.

### 3.2. Configuración empresarial

El funcionamiento efectivo resulta de combinar dimensiones que deben permanecer diferenciadas:

| Dimensión | Pregunta que resuelve |
|---|---|
| Plan contratado | ¿Qué capacidades y operaciones tiene derecho a utilizar la empresa? |
| Configuración operativa | ¿Cuáles utiliza y qué procedimientos, requisitos o excepciones establece? |
| Autorizaciones | ¿Qué personas o agentes pueden realizar cada operación y en qué ámbito? |
| Reglas de negocio | ¿Es válida esta operación concreta considerando sus datos y el estado actual? |
| Contexto | ¿Qué establecimiento, producto, tercero, recurso o transacción está involucrado? |

La formalidad administrativa no es una secuencia global obligatoria ni una escala única. Puede variar por operación, monto, riesgo, ubicación, tipo de producto u otras condiciones. El plan habilita posibilidades; no debe confundirse con las reglas operativas ni con los permisos.

### 3.3. Composición orientada a objetivos

Una intención empresarial puede requerir una operación o varias. El sistema debe poder determinar, a partir del objetivo, el estado conocido y las políticas vigentes, qué pasos son necesarios, cuáles son opcionales, cuáles ya están satisfechos y cuáles requieren participación humana.

Se busca que **nuevas combinaciones de operaciones existentes puedan definirse mediante configuración y reglas, sin desarrollar un procedimiento nuevo para cada empresa**. Cuando una necesidad implique un comportamiento empresarial realmente nuevo, se incorpora una nueva operación al catálogo.

Componer no significa ejecutar sin control: el sistema debe distinguir consultas, propuestas, decisiones humanas y acciones con efectos; verificar las condiciones al momento de actuar; y evitar repeticiones o efectos no deseados.

## 4. SARA — Esbozo

SARA es la interfaz universal de interacción. No representa el ERP mediante un conjunto fijo de pantallas por módulo.

Su función es ayudar al usuario a expresar y aclarar lo que quiere hacer, recuperar las alternativas pertinentes y presentar los resultados de manera comprensible. Puede conversar, proponer menús contextuales, solicitar datos faltantes y mostrar componentes visuales adecuados —tablas, formularios, selectores, gráficos, resúmenes o confirmaciones— sin requerir el desarrollo de una pantalla especial para cada operación.

SARA puede asistir en la interpretación de intenciones, pero no decide por su cuenta las autorizaciones, las reglas empresariales ni la validez de una operación. Tampoco supone que una instrucción ambigua autoriza automáticamente acciones irreversibles.

## 5. MARCO — Esbozo

MARCO es el entorno operativo que hace efectivas las capacidades de la plataforma para cada empresa. Conceptualmente, debe:

1. Conocer el catálogo de operaciones disponibles y el significado de sus contratos.
2. Determinar qué operaciones corresponden al plan y configuración de cada empresa.
3. Aplicar autorizaciones, condiciones, reglas y restricciones pertinentes.
4. Proponer o resolver composiciones de operaciones para cumplir objetivos empresariales.
5. Ejecutar las operaciones permitidas mediante implementaciones sustituibles, cuando sea necesario.
6. Mantener resultados, estado, vínculos entre operaciones y evidencia suficiente para seguimiento, recuperación y trazabilidad según el caso.

MARCO no tiene por qué contener toda la lógica especializada de compras, inventario o producción. Esas responsabilidades corresponden a las implementaciones de las operaciones. Tampoco exige que cada capacidad coincida con un programa físico: esa decisión queda deliberadamente fuera de este documento.

## 6. Ejemplo integral: abastecer un producto

### 6.1. Situación común

Dos empresas necesitan adquirir **20 unidades del producto P-100** para disponer de ellas en su establecimiento. Ambas utilizan el mismo catálogo de operaciones de Natural Commerce, pero sus planes y modelos operativos son diferentes.

Para ilustrar la composición, supongamos que el universo funcional contempla operaciones como:

- Consultar existencias y disponibilidad.
- Determinar necesidades de abastecimiento.
- Identificar proveedores y condiciones comerciales.
- Registrar una solicitud de compra.
- Solicitar y comparar cotizaciones.
- Aprobar o rechazar una compra.
- Emitir una orden de compra.
- Registrar recepciones parciales o totales.
- Registrar documentos del proveedor.
- Reconocer obligaciones de pago y registrar pagos parciales o totales.
- Consultar el estado y los antecedentes de la adquisición.

Estas operaciones no constituyen una secuencia obligatoria para todos los clientes. Algunas incluso pueden aplicarse sin las demás.

### 6.2. Empresa A: operación directa

**Configuración ilustrativa:** un pequeño comercio tiene habilitadas las operaciones de compra, recepción y pago. No exige solicitud previa, comparación de cotizaciones ni aprobación formal para esta clase de adquisición. La persona que opera cuenta con los permisos necesarios.

El usuario dice a SARA:

> «Necesito comprar 20 unidades del P-100».

SARA puede mostrar los proveedores conocidos y consultar los datos faltantes, como precio, condiciones de entrega o proveedor seleccionado. No presenta campos de aprobación o cotización que esta operación no necesita.

MARCO identifica que la compra directa está permitida y que los datos necesarios están completos. Una vez confirmada la acción correspondiente, se emite la orden de compra.

Cuando llegan **12 unidades**, el usuario dice:

> «Recibimos 12 de la compra del P-100».

MARCO registra una recepción parcial vinculada con la orden. Quedan **8 unidades pendientes de recepción**. No se supone que la compra esté completamente recibida, pagada ni documentada por el solo hecho de registrar la primera entrega.

Posteriormente pueden registrarse las 8 unidades restantes y uno o varios pagos, según los hechos reales y las reglas aplicables. SARA presenta el estado actual mediante un resumen o una tabla, sin necesitar una pantalla de compras diseñada expresamente para esta empresa.

### 6.3. Empresa B: control formal condicionado

**Configuración ilustrativa:** otra organización tiene habilitadas las mismas operaciones básicas y, además, solicitudes, cotizaciones y aprobaciones. Su política establece que, para compras de esta categoría que superen un umbral configurado, se requiere una solicitud aprobada y la evaluación de dos cotizaciones antes de emitir la orden. Las aprobaciones corresponden a una persona distinta de quien solicita.

El usuario expresa la misma intención:

> «Necesito comprar 20 unidades del P-100».

MARCO evalúa el contexto y determina que todavía **no puede emitirse válidamente la orden**: faltan antecedentes y una aprobación. En lugar de rechazar genéricamente la intención o ejecutar la compra, identifica las condiciones pendientes.

SARA presenta un procedimiento orientado al objetivo, con los pasos pertinentes:

1. Completar o registrar la solicitud de adquisición.
2. Obtener y registrar las cotizaciones requeridas.
3. Compararlas y solicitar la aprobación correspondiente.
4. Emitir la orden una vez satisfechas las condiciones y autorizada su ejecución.

SARA puede pedir los datos de la solicitud mediante conversación o un formulario generado en el momento. Puede mostrar las cotizaciones en una tabla comparativa y ofrecer al aprobador las acciones que le corresponden. Si una operación requiere esperar una respuesta humana, el proceso queda pendiente y puede retomarse posteriormente.

Al registrarse la segunda cotización, MARCO reevalúa lo pendiente. Cuando el aprobador autoriza la adquisición, queda habilitada la emisión de la orden. La confirmación final sigue respetando los permisos y las condiciones del momento.

La recepción también puede ser parcial. Si se reciben 12 unidades, el sistema conserva la relación con la orden y las 8 pendientes. El registro del documento del proveedor y los pagos son hechos distintos: no se inventan ni se ejecutan automáticamente por haber concluido una recepción.

### 6.4. Modificación sin desarrollar otro procedimiento

Meses después, la Empresa B cambia su política: las compras de esta categoría bajo cierto importe ya no necesitan dos cotizaciones, aunque siguen requiriendo aprobación.

La modificación se realiza en su **configuración operativa**, no en el código de la operación «emitir orden de compra» ni en SARA.

Para una solicitud nueva que cumpla esa condición, MARCO identifica que la cotización comparativa ya no es exigible y presenta únicamente los pasos necesarios. Para los procesos que ya estaban iniciados, aplica una política explícita de vigencia de reglas, evitando alterar silenciosamente las condiciones bajo las cuales se tramitaron.

### 6.5. Variantes que debe admitir el mismo modelo

El ejemplo anterior debe poder extenderse sin cambiar su principio de funcionamiento:

- Una empresa puede emitir compras sin solicitud previa.
- Otra puede exigir aprobación solo para ciertos montos o categorías.
- Una misma compra puede recibir varias entregas y asociar varios documentos y pagos.
- Una necesidad de abastecimiento puede resolverse mediante más de una compra; una compra puede atender varias necesidades.
- Algunas empresas pueden utilizar inventario; otras pueden operar sin esa capacidad cuando el negocio lo permita.
- La disponibilidad futura puede ser un cálculo, sin implicar necesariamente reservas persistentes.
- Una acción puede ejecutarse automáticamente solo si el plan, los permisos y las reglas permiten esa automatización.

El objetivo no es programar cada variante como un procedimiento separado. Es **representar las diferencias en las operaciones, condiciones y configuraciones que MARCO interpreta**.

## 7. Qué demuestra el ejemplo

El resultado esperado es que una misma plataforma, con el mismo conjunto de operaciones implementadas, produzca comportamientos distintos según la empresa y el contexto:

| Aspecto | Empresa A | Empresa B |
|---|---|---|
| Intención inicial | Comprar 20 unidades | Comprar 20 unidades |
| Solicitud formal | No requerida | Requerida en el caso ilustrado |
| Cotizaciones | No requeridas | Dos, cuando se supera el umbral |
| Aprobación | No requerida | Requerida por una persona autorizada |
| Emisión de orden | Directa al completar condiciones | Después de satisfacer controles |
| Recepción parcial | Permitida | Permitida |
| Interfaz | Generada según los datos necesarios | Generada según pasos y roles aplicables |
| Código específico por empresa | No | No |

La misma operación puede participar en diferentes procedimientos sin incorporar a su implementación todas las variantes de burocracia empresarial.

## 8. Criterios para evaluar si la visión se cumple

La propuesta se acercará a su objetivo cuando sea posible demostrar que:

1. Una operación nueva puede incorporarse al catálogo y quedar accesible desde SARA sin construir una interfaz específica.
2. Dos clientes pueden utilizar las mismas operaciones con procedimientos y condiciones distintos, sin versiones de código por cliente.
3. Cambiar una regla operativa modifica las alternativas y pasos pertinentes sin reprogramar el procedimiento.
4. El sistema puede reconocer condiciones satisfechas, pendientes, opcionales y prohibidas, en vez de imponer una secuencia fija.
5. Las operaciones con efectos conservan sus garantías de autorización, consistencia, recuperación y trazabilidad, de acuerdo con su naturaleza.
6. La incorporación o sustitución de una implementación no exige, como regla general, detener el funcionamiento del conjunto ni reconstruir las demás capacidades.
7. La incorporación progresiva de funciones propias de un ERP integral no obliga a incrementar proporcionalmente la complejidad visible de SARA.

## 9. Alcance y decisiones abiertas

Este documento **no define** arquitectura física, lenguaje de programación, protocolos, bases de datos, infraestructura, formato de contratos ni estrategia concreta de ejecución. Tampoco fija un catálogo definitivo ni una semántica completa de composición.

Quedan abiertos, entre otros, el detalle del modelo de operaciones, la representación de políticas y efectos, la planificación de objetivos, la gestión de procesos duraderos, los mecanismos de consistencia y la forma de incorporar implementaciones dinámicamente.

La intención es conservar un criterio funcional estable frente a esas decisiones: **ofrecer la amplitud de un ERP integral mediante operaciones reutilizables y componibles, configuradas por empresa, con una interfaz conversacional y visual dinámica, evitando código y burocracia innecesarios.**
