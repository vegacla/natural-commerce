(ns nc.catalogo
  "El catálogo reúne lo que el ERP conoce (D6): el canon de la plataforma y lo
  que configura cada empresa. Se arma desde archivos EDN, cada uno un mapa con
  las claves :entidades, :tipos-hecho, :conceptos, :contratos, :politicas,
  :caminos y :extensiones."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [nc.esquema :as esq]))

(defn leer
  "Lee un recurso EDN del classpath."
  [recurso]
  (with-open [r (java.io.PushbackReader. (io/reader (io/resource recurso)))]
    (edn/read r)))

(defn- por [clave xs] (into {} (map (juxt clave identity)) xs))

(defn armar
  "Une varias partes en un catálogo. Los campos de entidad incluyen los que cada
  empresa agrega como extensión (D3)."
  [& partes]
  (let [{:keys [entidades tipos-hecho conceptos contratos politicas caminos extensiones]}
        (apply merge-with into (map #(update-vals % vec) partes))]
    {:entidades   (por :entidad/tipo entidades)
     :tipos-hecho (por :tipo-hecho/id tipos-hecho)
     :conceptos   (por :concepto/id conceptos)
     :contratos   (por :op/id contratos)
     :politicas   (vec politicas)
     :caminos     (vec caminos)
     :campos      (into (apply merge {} (map :entidad/campos entidades))
                        (map :extension/campo extensiones))}))

(defn cargar
  "Arma un catálogo desde recursos EDN."
  [& recursos]
  (apply armar (map leer recursos)))

(defn problemas
  "Todos los problemas de un catálogo, cada uno con :de, el dato al que
  pertenece. Vacío si el catálogo es válido."
  [catalogo]
  (let [de (fn [id ps] (map #(assoc % :de id) ps))]
    (concat
     (mapcat #(de (:entidad/tipo %) (esq/problemas-entidad %)) (vals (:entidades catalogo)))
     (mapcat #(de (:tipo-hecho/id %) (esq/problemas-tipo-hecho % catalogo)) (vals (:tipos-hecho catalogo)))
     (mapcat #(de (:concepto/id %) (esq/problemas-concepto % catalogo)) (vals (:conceptos catalogo)))
     (de :conceptos (esq/problemas-de-ciclos catalogo))
     (mapcat #(de (:op/id %) (esq/problemas-contrato % catalogo)) (vals (:contratos catalogo)))
     (mapcat #(de (:politica/id %) (esq/problemas-politica % catalogo)) (:politicas catalogo))
     (mapcat #(de (:camino/id %) (esq/problemas-camino % catalogo)) (:caminos catalogo)))))
