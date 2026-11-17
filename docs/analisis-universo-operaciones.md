# Natural Commerce — Análisis del universo de operaciones

Fecha: 2026-11-16. Valida D28 (primitivos), D30 (verbos de efecto) y D31 (formas de política).
Revisado el 2026-11-17: se corrigió la columna de primitivos y la clasificación de cinco operaciones; las cifras de la sección 2 son las nuevas.
La clasificación completa de las 74 operaciones está en `analisis/operaciones.csv`.

## 1. Método

- Muestra de 74 operaciones representativas de un ERP integral, con las áreas clásicas de SAP como referencia: compras (14), inventario (9), ventas (14), finanzas (21), costos (5), producción (8) y personal (3). Incluye los documentos tributarios electrónicos chilenos.
- Cada operación se clasificó por primitivos que toca, verbos de efecto y formas de política habituales. Cuando no calzaba limpiamente, se anotó una tensión (T1 a T14).
- Limitaciones: clasificación analítica, no validada con usuarios ni con un contador; personal y producción poco cubiertos; lo tributario chileno debe verificarse (D42).

### Códigos del CSV

- Primitivos: **E** entidad, **H** hecho, **C** compromiso. La columna se deriva de los verbos: una operación toca un primitivo si usa alguno de sus verbos.
- Verbos: **ce** crear entidad, **me** modificar entidad, **de** desactivar entidad, **rh** registrar hecho, **rv** revertir hecho, **ac** abrir compromiso, **rc** reducir compromiso, **ec** extinguir compromiso, **tc** transferir compromiso.
- Políticas: **EX** exige, **PR** prohíbe, **LI** limita, **DF** sugiere, **DT** fija, **AU** exime, **DI** inicia, **AZ** permite. Los códigos conservan las iniciales de los nombres anteriores a D53 (valor por defecto, determina, automatiza, dispara, autoriza).

## 2. Resultados

| Medida | Resultado |
|---|---|
| Operaciones que tocan hechos | 58 de 74 |
| Operaciones que tocan compromisos | 36 |
| Operaciones que tocan entidades | 14 |
| Operaciones sin efectos (consultas o propuestas) | 7 |
| Operaciones sin ninguna tensión | 32 |

Verbos: registrar hecho (58), abrir compromiso (22), reducir compromiso (18), modificar entidad (12), crear entidad (9), revertir (4), extinguir (4), desactivar (2), transferir (1).

Formas de política: exige (34), permite (34), fija (33), limita (19), inicia (14), exime (12), prohíbe (9), sugiere (6).

## 3. Hallazgos

- **H1. Tres primitivos alcanzan** para describir efectos. Las tensiones están en los atributos, no en el número.
- **H2. El compromiso necesita estructura:** puede ser condicional (oferta, pago contra factura: T1), interno (reserva, solicitud: T2) o generado por un compromiso con calendario (contrato marco, contrato de trabajo: T14, resuelto en D39).
- **H3. Presupuesto y cupo de crédito** son entidad + concepto de consumo + política de límite (T3, D34).
- **H4. El período contable** es entidad con estado + política que prohíbe (T4).
- **H5. Nueve verbos** cierran el vocabulario (D56 agregó después un décimo, modificar compromiso, que este análisis no clasificó). Transferir aparece por la cesión de facturas (T8). Extinguir declara causa; reducir puede afectar varios compromisos con un hecho (T9).
- **H6. "Fija" es central** (T10): precios, cuentas, impuestos, folios. El lenguaje de reglas devuelve valores, no solo verdadero o falso.
- **H7. Algunas operaciones producen propuestas** (MRP, propuesta de pagos, reposición: T7). Resuelto en D40 y D43. La reposición automática no tiene efectos propios: es una política "inicia" que plantea el objetivo de abastecer, y MARCO ejecuta o propone según D51.
- **H8. El tiempo es un actor** (T5): depreciación, reajustes por UF, provisiones, sueldos, aceptación tácita de DTE. MARCO necesita identidad propia.
- **H9. Hace falta tiempo de validez** para programar el futuro (T13): listas de precios, listas de materiales, costos estándar, las propias políticas.
- **H10. Lo legal atraviesa ventas, compras y finanzas** (T11): nueve operaciones, siete de ellas con DTE (cinco de ventas y dos de compras). Los folios son un saldo que cada documento consume (D63, por verificar).
- **H11. Los saldos se derivan de hechos** pero se guardan (T6, resuelto en D41).
- **H12. Ningún compromiso cambia sin un hecho** (D61). Reservar stock y comprometer presupuesto también registran un hecho; en la primera versión de este análisis figuraban sin él.
- **H13. El hecho compuesto no pide un primitivo nuevo** (T12): una operación registra varios hechos en la misma transacción y abre compromisos solo por lo que queda pendiente (D62).

## 4. Tensiones

| # | Tensión |
|---|---|
| T1 | Compromiso condicional |
| T2 | Compromiso interno sobre un recurso |
| T3 | Límites (presupuesto, cupo) |
| T4 | Período contable |
| T5 | Hechos generados por el paso del tiempo |
| T6 | Saldos y valores derivados |
| T7 | Resultado como propuesta |
| T8 | Cambio de acreedor sin extinción |
| T9 | Un hecho que reduce varios compromisos |
| T10 | Determinación de valores |
| T11 | Documentos tributarios electrónicos |
| T12 | Hecho compuesto (venta, entrega y cobro simultáneos) |
| T13 | Datos con validez futura |
| T14 | Compromiso que genera compromisos |
