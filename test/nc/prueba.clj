(ns nc.prueba
  "Ayudas para las pruebas del almacén, que escriben sin pasar por MARCO.")

(defn decision
  "Una decisión ejecutada que declara estos hechos. Solo para probar el almacén
  por separado: en el sistema, la decisión la arma el ejecutor (D82)."
  [hechos]
  {:decision/id           (random-uuid)
   :decision/en           (java.util.Date.)
   :decision/actor        :persona/prueba
   :decision/origen       :prueba
   :decision/operacion    :prueba/escribir
   :decision/resultado    :ejecutado
   :decision/requisitos   []
   :decision/confirmacion :confirmada
   :decision/hechos       (mapv :hecho/id hechos)})

(defn con-decision
  "Los cambios, con la decisión que los declara."
  [cambios]
  (assoc cambios :decision (decision (:hechos cambios))))
