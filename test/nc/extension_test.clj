(ns nc.extension-test
  "Etapa 5, E6: registrar pago se agregó solo con su contrato y su tipo de hecho,
  sin tocar el núcleo. El resolvedor y el ejecutor la usan como a las demás."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nc.almacen :as alm]
            [nc.ejecutor :as ej]
            [nc.estado :as est]
            [nc.marco-test :as m]
            [nc.resolvedor :as r]))

(use-fixtures :once m/con-almacen)

(defn- pago-de [empresa orden]
  (first (alm/buscar m/*alm* (:empresa empresa) :compromisos
                     {:compromiso/tipo :compras/pago-contra-factura :compromiso/origen orden})))

(deftest e6-registrar-pago
  (let [a     (m/empresa! {})
        orden (-> (m/ejecutar! a :persona/ana :compras/emitir-orden m/orden-20) :salidas :orden)
        pagar (fn [monto] (m/ejecutar! a :persona/ana :compras/registrar-pago
                                       {:orden orden :monto [:monto monto :CLP]}))]
    (testing "el resolvedor la conoce: pide lo que su contrato exige"
      (is (= [{:id :entrada/monto :estado :pendiente :pide [:entrada/monto]}]
             (:requisitos (r/resolver a :persona/ana :compras/registrar-pago {:orden orden})))))
    (testing "la factura activa el pago con su vencimiento"
      (m/ejecutar! a :persona/ana :compras/registrar-factura
                   {:orden orden :monto [:monto 600000 :CLP] :vencimiento (m/dias-desde-hoy 30)})
      (is (= :activo (:compromiso/estado (pago-de a orden))))
      (is (some? (:compromiso/vence (pago-de a orden)))))
    (testing "un pago parcial reduce el compromiso que la factura activó"
      (is (= :ejecutado (:estado (pagar 250000))))
      (is (= {:monto [:monto 350000 :CLP]} (:compromiso/saldo (pago-de a orden)))))
    (testing "el resto lo extingue por cumplimiento"
      (pagar 350000)
      (is (= :extinguido (:compromiso/estado (pago-de a orden))))
      (is (= #{:compras/pago}
             (->> (ej/ejecutar! a :persona/ana :compras/consultar-estado {:orden orden} {})
                  :salidas :hechos (map :hecho/tipo) (filter #{:compras/pago}) set))))
    (is (= :compras/registrar-pago (est/registradora (:catalogo a) :compras/pago)))))
