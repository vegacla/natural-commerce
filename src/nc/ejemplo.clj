(ns nc.ejemplo
  "Carga los datos de ejemplo (resources/ejemplos/compras.edn) en un almacén: las
  dos empresas con sus datos maestros, su configuración y casos en distintas
  etapas. Las políticas se publican vigentes desde hace 30 días, para que rijan
  con el reloj real."
  (:require [clojure.walk :as walk]
            [nc.almacen :as alm]
            [nc.catalogo :as cat]
            [nc.ejecutor :as ej]
            [nc.estado :as est])
  (:import (java.util Date)))

(defn- en-dias [n] (Date. (+ (System/currentTimeMillis) (* n 24 60 60 1000))))

(defn- configuracion [{:keys [configuracion]}]
  (if configuracion (cat/leer configuracion) {}))

(defn- empresa! [almacen canon empresa entidades datos]
  (let [config (configuracion datos)]
    (alm/escribir! almacen empresa {:entidades entidades})
    (doseq [p (:politicas config)]
      (alm/publicar! almacen empresa :politicas (assoc p :politica/rige-desde (en-dias -30))))
    (est/abrir almacen (cat/armar canon config) empresa)))

(defn- con-salidas [salidas entradas]
  (walk/postwalk (fn [x]
                   (cond
                     (and (vector? x) (= :salida (first x))) (get-in salidas (rest x))
                     (and (vector? x) (= :en-dias (first x))) (en-dias (second x))
                     :else x))
                 entradas))

(defn cargar!
  "Carga los datos y devuelve la vista de cada empresa: {empresa estado}."
  ([almacen] (cargar! almacen "ejemplos/compras.edn"))
  ([almacen recurso]
   (let [{:keys [empresas entidades pasos]} (cat/leer recurso)
         canon   (cat/leer "canon/compras.edn")
         estados (into (sorted-map) (for [[e datos] empresas] [e (empresa! almacen canon e entidades datos)]))]
     (reduce (fn [salidas {:keys [empresa actor op entradas como]}]
               (let [r (ej/ejecutar! (estados empresa) actor op (con-salidas salidas entradas) {:confirmado true})]
                 (when-not (= :ejecutado (:estado r))
                   (throw (ex-info (str "El paso de ejemplo " op " no se ejecutó") {:resultado r})))
                 (cond-> salidas como (assoc como (:salidas r)))))
             {}
             pasos)
     estados)))

(defn vistas
  "La vista de cada empresa sobre un almacén donde los datos ya están cargados,
  por ejemplo uno restaurado de un respaldo: {empresa estado}."
  ([almacen] (vistas almacen "ejemplos/compras.edn"))
  ([almacen recurso]
   (let [canon (cat/leer "canon/compras.edn")]
     (into (sorted-map) (for [[e datos] (:empresas (cat/leer recurso))]
                          [e (est/abrir almacen (cat/armar canon (configuracion datos)) e)])))))
