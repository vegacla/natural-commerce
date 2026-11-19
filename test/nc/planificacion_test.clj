(ns nc.planificacion-test
  "Etapa 5, E8: objetivo \"abastecer 20 P-100\" sin camino guardado. MARCO arma
  el plan, ejecuta lo que puede y propone lo demás (D51); al cumplirse, deja el
  camino como borrador por activar (D14). Activado, se usa solo si sus políticas
  aplican al caso (D54)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.marco-test :as m]
            [nc.planificador :as p])
  (:import (java.util Date)))

(use-fixtures :once m/con-almacen)

(def abastecer-20
  {:operacion :compras/emitir-orden
   :entradas  {:producto :P-100 :cantidad [:cantidad 20 :un] :precio-unitario [:monto 30000 :CLP]}})

(defn- estados [plan] (mapv :estado (:pasos plan)))

(defn- ids-que-faltan [plan i] (set (map :id (get-in plan [:pasos i :falta]))))

(deftest e8-objetivo-sin-camino-guardado
  (let [b    (m/empresa! m/empresa-b)
        plan (p/planear b :persona/ana abastecer-20)]
    (testing "sin camino guardado, los pasos se deducen de lo que exige la política"
      (is (= :deducido (:origen plan)))
      (is (= [:compras/registrar-solicitud :compras/registrar-oferta :compras/registrar-oferta
              :compras/aprobar :compras/emitir-orden]
             (map :operacion (:pasos plan)))))
    (let [plan      (p/avanzar! b :persona/ana plan)
          solicitud (get-in plan [:entradas :solicitud])]
      (testing "MARCO ejecuta lo que Ana puede y propone lo demás (D51)"
        (is (= [:ejecutado :propuesto :propuesto :propuesto :propuesto] (estados plan)))
        (is (uuid? solicitud))
        (is (= #{:entrada/proveedor :entrada/vence} (ids-que-faltan plan 1)))
        (is (= #{:permiso :empresa-b/segregacion} (ids-que-faltan plan 3)))
        (is (contains? (ids-que-faltan plan 4) :compras/oferta)))
      (let [oferta (fn [proveedor]
                     (-> (m/ejecutar! b :persona/ana :compras/registrar-oferta
                                      {:solicitud solicitud :proveedor proveedor :producto :P-100
                                       :cantidad [:cantidad 20 :un] :precio-unitario [:monto 30000 :CLP]
                                       :vence (m/dias-desde-hoy 15)})
                         :salidas :oferta))
            _      (oferta :proveedor/acme)
            o2     (oferta :proveedor/beta)
            _      (m/ejecutar! b :persona/luis :compras/aprobar {:solicitud solicitud :oferta o2})
            plan   (p/avanzar! b :persona/ana plan {:proveedor :proveedor/beta :oferta o2})]
        (testing "con lo que otros hicieron y lo que Ana entrega, el objetivo se cumple"
          (is (= [:ejecutado :satisfecho :satisfecho :satisfecho :ejecutado] (estados plan)))
          (is (= :cumplido (:estado plan)))
          (is (uuid? (get-in plan [:entradas :orden]))))
        (testing "queda el camino como borrador por activar (D14)"
          (is (= {:camino/id      :camino/emitir-orden
                  :camino/version 1
                  :camino/pasos   [:compras/registrar-solicitud :compras/registrar-oferta
                                   :compras/aprobar :compras/emitir-orden]
                  :camino/reglas  {:empresa-b/compra-formal 3}
                  :camino/estado  :borrador}
                 (dissoc (:borrador plan) :camino/rige-desde :camino/objetivo)))
          (is (= plan (p/avanzar! b :persona/ana plan)) "avanzar un plan cumplido no hace nada")
          (is (= :deducido (:origen (p/planear b :persona/ana abastecer-20))) "un borrador no se usa"))
        (testing "activado, el camino se usa cuando sus políticas aplican al caso (D54)"
          (alm/publicar! m/*alm* (:empresa b) :caminos
                         (assoc (:borrador plan) :camino/version 2 :camino/estado :activo :camino/rige-desde (Date.)))
          (let [con-camino (p/planear b :persona/ana abastecer-20)]
            (is (= {:camino :camino/emitir-orden :version 2} (:origen con-camino)))
            (is (= 4 (count (:pasos con-camino))))))
        (testing "y se rechaza cuando no aplican: bajo el umbral la orden va sola"
          (let [bajo (p/planear b :persona/ana (assoc-in abastecer-20 [:entradas :cantidad] [:cantidad 10 :un]))]
            (is (= :deducido (:origen bajo)))
            (is (= [:compras/emitir-orden] (map :operacion (:pasos bajo))))
            (is (= "La política :empresa-b/compra-formal no aplica a este caso"
                   (:razon (first (:rechazados bajo)))))))))))
