# Un agente sin contexto de diseño (E25)

Procedimiento que funcionó el 2026-11-19 con Claude Code y un subagente `general-purpose`, Opus 5.5. *Verificado* en la etapa 14.

## Cómo se aísla

- **Los materiales se copian a una carpeta fuera del repositorio**, en el scratchpad de la sesión: la notación, el canon de ejemplo y la descripción funcional. El subagente comparte el disco, así que la carpeta aparte no basta. Para leer el repositorio no tendría más que abrirlo.
- **El aislamiento es por instrucción.** El encargo dice tres cosas:
  - leer solo esos archivos;
  - no abrir el repositorio, no ejecutar código y no buscar en la web;
  - anotar cada duda en vez de resolverla buscando.

  El subagente no arrastra la conversación. Su informe dio 5 usos de herramientas: 3 lecturas y 2 escrituras. No hay un sandbox que lo garantice; para un aislamiento real haría falta otra máquina o un agente en la nube sin el repositorio.
- **Pedirle un archivo de dudas por operación** fue lo más útil. Las seis correcciones que hubo después estaban todas en sus dudas, así que ese archivo sirve también para auditar la notación.

## Cómo se mide

1. Respaldar el canon original fuera del repositorio. Poner el del agente en su lugar en `resources/canon/` y correr la suite completa: las pruebas ya existen y son el juez.
2. Corregir lo mínimo, una causa por corrección, y volver a correr. Anotar cada corrección con su causa: notación, descripción o error del agente.
3. Restaurar el canon original. Guardar las dos versiones, la del agente y la corregida, junto con la descripción que recibió: `docs/e25/`.

Una diferencia de forma (el agente escribió `[:monto 0 :CLP]` y la prueba esperaba `0`) no es una corrección del agente si su forma es la que pide la notación. En ese caso se corrige la prueba.

## Segunda ronda (2026-11-19, Opus, Sonnet y Haiku 5.5)

- **Sin tocar el canon del repositorio.** Para que la suite juzgue el canon de un agente basta ponerlo delante en el classpath: `deps -Sdeps '{:aliases {:e25 {:extra-paths ["<carpeta>/cp"]}}}' -M:e25:test`, con el archivo en `<carpeta>/cp/canon/ventas.edn`. `io/resource` devuelve el primero; `-Spath` confirma el orden. Así se pueden juzgar varios agentes uno tras otro, y el repositorio no queda con un canon ajeno si algo se corta. *Verificado* leyendo `(io/resource "canon/ventas.edn")` con el alias.
- **No lanzar el subagente con `isolation: "worktree"`.** Le pone el repositorio como carpeta de trabajo: lo contrario de lo que se busca. Error nuestro en la primera de las tres corridas; el agente informó solo sus tres lecturas.
- Con el subagente `general-purpose` y `model` (`opus`, `sonnet`, `haiku`) se cambia el modelo sin cambiar el encargo.

