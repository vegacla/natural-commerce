# Natural Commerce

ERP componible, configurable y agéntico. Dos piezas: **SARA** (interfaz conversacional y visual, único lugar con LLM) y **MARCO** (motor determinista que evalúa operaciones contra políticas y estado).

Idioma del proyecto: español (documentos, nombres de dominio, conversación).

## Estado

El modelo está cerrado tras una sesión de descubrimiento (13 al 16 de noviembre de 2026). Prototipo mínimo: etapas 1 a 6 hechas (E1 a E9 y SARA mínima). Prototipo ampliado aprobado (D81, `docs/prototipo-ampliado.md`): etapas 7 a 13, E10 a E27. Revisión de madurez operativa de XTDB hecha (`docs/revision-xtdb.md`): se sigue con Clojure y XTDB, con tres condiciones antes de datos de clientes (D79; la cuarta se descartó, D80).

## Documentos (leer en este orden)

1. `docs/vision-funcional.md` — la visión original.
2. `docs/registro-de-decisiones.md` — el modelo en una página (sección 0) y las decisiones, numeradas desde D1. Es la fuente de verdad.
3. `docs/notacion-v0.md` — notación del núcleo en EDN: primitivos, expresiones, conceptos, contratos, políticas, resolvedor. Sus preguntas están resueltas en la sección 4 del registro, salvo P12, que se valida con el prototipo. P5 y P6 se resolvieron en la etapa 3 (D65, D66).
4. `docs/prototipo-minimo.md` — alcance, escenarios de aceptación E1 a E9, etapas y criterio de salida.
5. `docs/prototipo-ampliado.md` — el modelo como ERP: mecanismos M1 a M25, escenarios E10 a E27, etapas 7 a 13.
6. `docs/revision-xtdb.md` — respaldo, costos y soporte de XTDB; criterio de salida.
7. `docs/separacion-por-empresa.md` — resguardos de la separación por empresa (D22, D80) y cómo se verifican.
8. `docs/knowledge/` — lo que se aprende implementando: comportamientos de las herramientas, trampas y procedimientos (`README.md` es el índice).
9. `docs/analisis-universo-operaciones.md` y `docs/analisis/operaciones.csv` — 74 operaciones de ERP clasificadas por primitivos, verbos y formas de política.

## Principio de diseño (D46)

Toda decisión cae en una de tres capas:

- Significado de una operación → **contrato** (lo cambia el equipo, con un despliegue).
- Forma de trabajar de una empresa → **política** con valor por defecto (la cambia la empresa).
- Ley → **política legal** (la cambia el equipo, por régimen tributario).

Antes de preguntar al usuario algo de diseño, comprobar si este principio y el registro ya lo responden. Las preguntas puramente técnicas se resuelven construyendo.

## Stack del prototipo

- Clojure (deps.edn) con XTDB v2 como almacén. Respaldo: Clojure con Postgres (D27).
- Esquemas con Malli. Pruebas: una por escenario de aceptación (E1 a E9).
- Contratos, conceptos, políticas y caminos son datos EDN, no código.
- El CLI de Clojure es `deps` (deps.clj, en `~/.local/bin`).
- XTDB v2 exige JDK 21 o superior. El proyecto lo usa mediante `JAVA_CMD`, con el JDK 21 instalado aparte en `~/AppData/Local/Programs/Temurin21/jdk-21.0.13+9` (detalle en `docs/knowledge/entorno-jdk.md`).
- SARA mínima: `.\sara.ps1` en PowerShell y abrir http://localhost:8081. Carga los datos de ejemplo en memoria; se pierden al detenerla.
- Pruebas: `.\pruebas.ps1` en PowerShell, desde cualquier carpeta; fija `JAVA_CMD` solo mientras corre. En bash: `JAVA_CMD="C:/Users/ceva2/AppData/Local/Programs/Temurin21/jdk-21.0.13+9/bin/java.exe" deps -M:test`.

## Código

- `src/nc/valor.clj` — valores con unidad. `src/nc/expresion.clj` — evaluador. `src/nc/validar.clj` — validación al escribir. `src/nc/esquema.clj` — esquemas de contrato, política, concepto, tipo de hecho y camino. `src/nc/catalogo.clj` — carga y valida el catálogo. `src/nc/almacen.clj` — hechos, compromisos, saldos y configuración versionada sobre XTDB. `src/nc/estado.clj` — vista de una empresa para MARCO: lecturas, caso y políticas vigentes. `src/nc/resolvedor.clj` — requisitos de un objetivo. `src/nc/ejecutor.clj` — ejecutor general de contratos. `src/nc/planificador.clj` — planes, caminos y borradores. `src/nc/sara.clj` — SARA mínima: interfaz como datos. `src/nc/sara/web.clj` — cliente web de SARA. `src/nc/ejemplo.clj` — carga los datos de ejemplo.
- `resources/canon/` — canon de la plataforma. `resources/empresas/` — configuración de cada empresa. `resources/ejemplos/` — datos de ejemplo para explorar.
- `docs/mediciones.md` — lo que pide medir la sección 7 del prototipo, por etapa.

## Forma de trabajo

- Separación por empresa (D80): solo `src/nc/almacen.clj` toca XTDB; toda función pública del almacén recibe la empresa y la valida; ningún código recibe una empresa de afuera sin pasarla por la vista de empresa (`estado.clj`). Al agregar una función pública al almacén, declararla en `test/nc/resguardos_test.clj`. `python scripts/mutaciones.py` rompe cada resguardo y comprueba que alguna prueba falle.
- El conocimiento que se obtiene al implementar (cómo se comporta una herramienta, una trampa, un procedimiento) se escribe en `docs/knowledge/`, un archivo por tema, y se agrega a su índice.
- Toda decisión nueva se agrega a `docs/registro-de-decisiones.md` con el número siguiente al último.
- Lo tributario chileno no está verificado por un experto: construirlo desde fuentes oficiales del SII y marcarlo (D42).
- Medir lo que pide la sección 7 de `docs/prototipo-minimo.md`: líneas del núcleo frente a líneas por operación, rodeos al almacén, correcciones humanas.
