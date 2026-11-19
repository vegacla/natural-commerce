# Núcleo: comportamientos no evidentes

Observados al implementar el núcleo de MARCO. Fecha de cada uno entre paréntesis.

- **Lo leído del almacén trae más que el documento** (2026-11-18). `alm/leer` y `alm/buscar` devuelven también `:empresa` y `:nc/decision`, que agrega el almacén al escribir. Si se usa un documento leído como base de uno nuevo (modificar una entidad), hay que quitarlos: si no, la validación los toma por campos del tipo. `ejecutor/entidad-activa` lo hace.
- **Una entrada opcional no entregada vale nil, no falta** (2026-11-18). El resolvedor pone en `entrada` cada opcional ausente con valor nil (P4: ausente no es lo mismo que nil). Por eso `select-keys` sobre la entrada trae claves con nil, que pisan valores al hacer `merge`. Filtrar con `sin-nada` antes de mezclar.
- **Un tipo de Malli sin traducción se ve como `:cualquiera`** (2026-11-18). `validar/tipo-normal` traduce los tipos de contrato a los del validador y de SARA; uno que no conoce (pasó con `:keyword`) queda como `:cualquiera`, y SARA no sabe leerlo ni mostrarlo. La prueba `un-formulario-por-contrato` lo detecta.
