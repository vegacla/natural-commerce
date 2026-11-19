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

(defn- tipos-de-compromiso
  "Los tipos de compromiso que abren los contratos: a ellos puede apuntar una
  referencia (D77)."
  [contratos]
  (set (for [c  contratos
             ef (tree-seq vector? seq (vec (:op/efectos c)))
             :when (and (vector? ef) (= :abre-compromiso (first ef)) (map? (second ef)))]
         (:tipo (second ef)))))

(defn- extensiones-por-tipo
  "Tipo de entidad → {campo tipo} con los campos que la empresa agregó (D3)."
  [extensiones]
  (reduce (fn [m {:extension/keys [tipo campo]}] (assoc-in m [tipo (first campo)] (second campo)))
          {} extensiones))

(defn- con-campos-propios
  "Una operación que declara `:op/entidad` recibe además, como entradas
  opcionales, los campos que la empresa agregó a ese tipo (D3)."
  [extensiones {:op/keys [entidad] :as contrato}]
  (if-let [campos (and entidad (seq (extensiones entidad)))]
    (update contrato :op/entradas into (for [[k t] campos] [k {:optional true} t]))
    contrato))

(def canon
  "Los recursos del canon de la plataforma, en el orden en que se unen."
  ["canon/compras.edn" "canon/ventas.edn" "canon/inventario.edn" "canon/finanzas.edn"])

(defn armar
  "Une varias partes en un catálogo. Los campos de entidad incluyen los que cada
  empresa agrega como extensión (D3)."
  [& partes]
  (let [{:keys [entidades tipos-hecho conceptos contratos politicas caminos extensiones combinacion]}
        (apply merge-with into (map #(update-vals % vec) partes))
        ext (extensiones-por-tipo extensiones)]
    {:entidades   (por :entidad/tipo entidades)
     :extensiones ext
     :tipos-hecho (por :tipo-hecho/id tipos-hecho)
     :conceptos   (por :concepto/id conceptos)
     :contratos   (por :op/id (map #(con-campos-propios ext %) contratos))
     :tipos-compromiso (tipos-de-compromiso contratos)
     :politicas   (vec politicas)
     :combinacion (into {} combinacion)
     :caminos     (vec caminos)
     :campos      (into (apply merge {} (map :entidad/campos entidades))
                        (map :extension/campo extensiones))}))

(defn cargar
  "Arma un catálogo desde recursos EDN."
  [& recursos]
  (apply armar (map leer recursos)))

(defn de-empresa
  "El catálogo de una empresa: el canon completo y su configuración."
  [configuracion]
  (apply armar (concat (map leer canon) [configuracion])))

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
