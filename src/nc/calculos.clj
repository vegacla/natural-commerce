(ns nc.calculos
  "Cálculos algorítmicos de los conceptos (D38): lo único que queda en código,
  porque no se escribe bien como expresión. Cada uno recibe los parámetros ya
  evaluados y el contexto de evaluación, solo lee, y su resultado se guarda con
  la versión del concepto que lo usó (D48)."
  (:require [nc.valor :as v])
  (:import (java.time ZoneOffset)
           (java.util Date)))

;; --- Inventario ------------------------------------------------------------------

(defn- costo-promedio
  "El costo de la existencia dividido por su cantidad."
  [{:keys [producto bodega]} ctx]
  (let [{:keys [cantidad costo]} ((:saldo ctx) :inventario/existencia {:producto producto :bodega bodega})]
    (when (and cantidad costo (pos? (v/numero cantidad)))
      (v/redondear (v/dividir costo (v/numero cantidad))))))

(defn- ultimo-costo
  "El precio de la última recepción del producto."
  [{:keys [producto]} ctx]
  (let [ultima (->> ((:hechos ctx) {:tipo :compras/recepcion})
                    (filter #(= producto (get-in % [:hecho/datos :producto])))
                    (sort-by #(.getTime ^Date (:hecho/en %)))
                    last)]
    (some-> ultima (get-in [:hecho/datos :orden]) ((:buscar ctx)) (get-in [:hecho/datos :precio-unitario]))))

(defn costo-unitario
  "El costo unitario de lo que sale de una bodega, según el método: :promedio
  (por omisión) o :ultimo-costo."
  [{:keys [metodo] :as args} ctx]
  (case (or metodo :promedio)
    :promedio     (costo-promedio args ctx)
    :ultimo-costo (ultimo-costo args ctx)))

(defn- capas
  "Las recepciones del producto en la bodega, de la más antigua a la más nueva:
  [cantidad precio]."
  [{:keys [producto bodega]} ctx]
  (for [h (sort-by #(.getTime ^Date (:hecho/en %)) ((:hechos ctx) {:tipo :compras/recepcion}))
        :let [{p :producto b :bodega q :cantidad orden :orden} (:hecho/datos h)]
        :when (and (= producto p) (= bodega b))]
    [(v/numero q) (get-in ((:buscar ctx) orden) [:hecho/datos :precio-unitario])]))

(defn costo-fifo
  "El costo unitario de lo que sale según FIFO: las unidades más antiguas
  primero. Salta lo que ya salió (los despachos de la bodega) y promedia el costo
  de las `cantidad` unidades siguientes (una, si no se dice). Reemplaza al costo
  promedio en la versión 2 del concepto (E22)."
  [{:keys [producto bodega cantidad] :as args} ctx]
  (let [salidas (->> ((:hechos ctx) {:tipo :ventas/despacho})
                     (filter #(= [producto bodega] ((juxt :producto :bodega) (:hecho/datos %))))
                     (map #(v/numero (get-in % [:hecho/datos :cantidad])))
                     (reduce + 0))
        pedir   (if cantidad (v/numero cantidad) 1)]
    (loop [[[q precio] & mas] (capas args ctx), saltar salidas, falta pedir, costo nil]
      (cond
        (zero? falta)  (v/redondear (v/dividir costo pedir))
        (nil? precio)  (when costo (v/redondear (v/dividir costo (- pedir falta))))
        (>= saltar q)  (recur mas (- saltar q) falta costo)
        :else          (let [toma (min falta (- q saltar))
                             parte (v/multiplicar toma precio)]
                         (recur mas 0 (- falta toma) (if costo (v/sumar costo parte) parte)))))))

;; --- Contabilidad ----------------------------------------------------------------

(defn periodo
  "El período contable mensual de una fecha: :periodo/2026-11."
  [{:keys [fecha]} _]
  (let [d (.atZone (.toInstant ^Date fecha) ZoneOffset/UTC)]
    (keyword "periodo" (format "%d-%02d" (.getYear d) (.getMonthValue d)))))

(defn estado-del-periodo
  "Si el período de una fecha está :abierto o :cerrado. Un período sin entidad
  está abierto: se cierra con una operación."
  [args ctx]
  (or (:periodo/estado ((:buscar ctx) (periodo args ctx))) :abierto))
