(ns nc.sii
  "Implementaciones de prueba del puerto :tributario/envio-dte (visión, criterio
  6). No se conectan al SII: devuelven lo que devolvería un envío, para probar
  que una implementación se sustituye sin cambiar contratos ni operaciones.
  Una integración real necesita enviar después de escribir (salida diferida),
  porque `preparar` puede llamarse más de una vez.

  Nada de esto está verificado contra el SII (D42).")

(defn- numero-de-folio [{:keys [folio]}] (if folio (second folio) "sin-folio"))

(defn enviar-v1
  "Primera versión: el envío queda recibido."
  [entradas]
  {:seguimiento (str "V1-" (numero-de-folio entradas)) :estado :recibido})

(defn enviar-v2
  "Segunda versión: el envío queda aceptado, con otro formato de seguimiento."
  [entradas]
  {:seguimiento (str "V2-" (numero-de-folio entradas)) :estado :aceptado})
