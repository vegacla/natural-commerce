(ns nc.tiempo
  "El tiempo como actor (H8, T5, D20). Al avanzar el reloj, MARCO dispara las
  políticas \"inicia\" cuyo evento llegó: `[:vence tipo-de-compromiso]` cuando un
  compromiso de ese tipo vence (más `:despues`, si lo declara), y
  `[:registra tipo-de-hecho]` con `:despues`, pasado ese plazo desde el hecho.

  No hay procesos esperando: lo pendiente son compromisos abiertos, y avanzar es
  mirar cuáles vencieron (prototipo mínimo, sección 3). Lo llama un planificador
  (en el prototipo, las pruebas) con el instante hasta el que avanzar."
  (:require [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.estado :as est]
            [nc.expresion :as e])
  (:import (java.time Instant)
           (java.util Date)))

(def ^:private vueltas-maximas
  "Un evento puede producir otro (una cuota devengada mueve el vencimiento del
  arriendo); el avance se corta aquí."
  500)

(defn- mas [fecha despues]
  (if despues (e/evaluar [:+dias fecha despues] {}) fecha))

(defn- antes-o-en? [^Date fecha ^Date instante] (not (.after fecha instante)))

(defn- vencidos
  "Los eventos que llegaron hasta `instante`: [política refs clave]."
  [{:keys [almacen empresa] :as estado} instante]
  (for [p (est/vigentes estado)
        :let [[evento tipo] (when (vector? (:politica/aplica-a p)) (:politica/aplica-a p))
              despues (get-in p [:politica/cuerpo :despues])]
        :when (= :inicia (:politica/forma p))
        x (case evento
            :vence    (for [c (alm/buscar almacen empresa :compromisos {:compromiso/tipo tipo})
                            :when (and (:compromiso/vence c) (not= :extinguido (:compromiso/estado c))
                                       (antes-o-en? (mas (:compromiso/vence c) despues) instante))]
                        [{'compromiso c} [(:politica/id p) (:compromiso/id c) (:compromiso/vence c)]])
            :registra (when despues
                        (for [h (alm/buscar almacen empresa :hechos {:hecho/tipo tipo})
                              :when (antes-o-en? (mas (:hecho/en h) despues) instante)]
                          [{'hecho h} [(:politica/id p) (:hecho/id h)]]))
            nil)]
    (into [p] x)))

(defn- planteados
  "Los eventos que MARCO ya planteó: cada uno se plantea una vez, aunque haya
  quedado propuesto. La bitácora lo recuerda (D82)."
  [{:keys [almacen empresa]}]
  (set (keep :decision/evento (alm/buscar almacen empresa :decisiones {:decision/origen :marco}))))

(defn avanzar!
  "Dispara los eventos que llegaron hasta `instante` (por omisión, ahora).
  Devuelve lo que MARCO planteó, en orden: ejecutado o propuesto."
  ([estado] (avanzar! estado (Date.)))
  ([estado instante]
   (let [instante (if (instance? Instant instante) (Date/from instante) instante)]
     (loop [vuelta 0, vistos (planteados estado), hecho []]
       (let [pendientes (remove (fn [[_ _ clave]] (vistos clave)) (vencidos estado instante))]
         (if (or (empty? pendientes) (>= vuelta vueltas-maximas))
           hecho
           (let [[p refs clave] (first pendientes)
                 r (ej/plantear! estado p refs {:evento clave})]
             (recur (inc vuelta) (conj vistos clave) (cond-> hecho r (conj r))))))))))
