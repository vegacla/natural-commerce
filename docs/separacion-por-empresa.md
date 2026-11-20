# Separación por empresa (D22, D80)

Una sola base; toda fila lleva `:empresa` y toda lectura filtra por ella. La separación depende de código, así que se protege con resguardos y se verifica con pruebas.

## Resguardos

1. Solo `src/nc/almacen.clj` toca XTDB. Prueba: `solo-el-almacen-toca-xtdb`.
2. Toda función pública del almacén exige una empresa (keyword) y falla con `:empresa-invalida`. Una función nueva debe declararse en `test/nc/resguardos_test.clj`, o `toda-funcion-publica-esta-clasificada` falla.
3. `buscar` rechaza un filtro con `:empresa` (`:filtro-con-empresa`).
4. No se escribe un documento marcado con otra empresa, ni se pisa un compromiso ajeno (`:documento-ajeno`).
5. Los `_id` de políticas, conceptos, caminos, saldos y marcas se derivan de la empresa; dos empresas pueden usar el mismo nombre.
6. Los índices propios (D109) también: el `_id` de cada uno sale de empresa, tabla, campo y valor, y la lectura de los documentos que lista filtra por empresa además de por `_id`. Un índice no puede apuntar a documentos de otra empresa, y aunque lo hiciera la lectura no los traería.

## Verificación

- `test/nc/separacion_test.clj` (E9) y `test/nc/resguardos_test.clj`.
- `deps -M:mutaciones` (con `JAVA_CMD`, ver `CLAUDE.md`) corre `scripts/mutaciones.clj`: rompe cada resguardo de `scripts/mutaciones.edn` y exige que alguna prueba falle, en una sola JVM. El 19 de noviembre de 2026 las 64 mutaciones fueron detectadas; las de este documento son las del almacén (`src/nc/almacen.clj`). La primera versión, del 18 de noviembre, era `scripts/mutaciones.py` y tenía 6.

## Historia

- Etapa 5: una función de historia no filtraba por empresa; E9 la encontró.
- Al implementar D80 apareció otro hueco: un compromiso con el identificador de otra empresa pisaba el original.
- Las bases múltiples de XTDB se evaluaron y descartaron: `knowledge/xtdb-bases-multiples.md`.
